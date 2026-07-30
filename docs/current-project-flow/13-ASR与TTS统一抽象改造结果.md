# 第七阶段：ASR 与 TTS 统一抽象改造结果

这一阶段把实时语音面试从 Qwen 厂商类中解耦，并增加本地 Mock Provider。现有语音
WebSocket 地址和前端消息协议保持不变。

## 一、调用结构

```text
VoiceInterviewWebSocketHandler
  -> SpeechRecognitionService / SpeechSynthesisService
  -> RoutingSpeechService
      -> MockSpeechRecognitionService / MockSpeechSynthesisService
      -> QwenSpeechRecognitionAdapter / QwenSpeechSynthesisAdapter
          -> QwenAsrService / QwenTtsService
```

WebSocket Handler 不再直接依赖 `QwenAsrService` 和 `QwenTtsService`。

## 二、统一接口

`SpeechRecognitionService` 负责：

- 启动、重启和停止流式识别会话。
- 接收 PCM 音频分片。
- 返回实时字幕和最终转写。
- 查询会话是否仍处于活动状态。

`SpeechSynthesisService` 负责：

- 把问题文本合成为 `SpeechAudio`。
- 返回音频格式、MIME 类型和采样率。

项目的语音识别是长连接流式模型，因此接口采用 session + callback，而不是把完整音频
一次性传给 `recognize()`。`SpeechRecognitionResult` 保留统一结果结构，便于后续接入
文件识别或其他厂商。

## 三、Provider 路由

```text
APP_VOICE_ASR_PROVIDER=mock
APP_VOICE_TTS_PROVIDER=mock
```

当前支持：

- `mock`：本地开发，不访问云端。
- `qwen`：复用现有百炼 Qwen3 Realtime ASR/TTS。

每个已经开始的 ASR 会话会固定使用启动时选择的 Provider。即使管理员随后修改全局配置，
正在进行的会话也不会突然切换厂商。

未知 Provider 会通过 `BusinessException` 返回统一业务错误，不会静默回退。

## 四、本地 Mock 行为

Mock ASR 不保存原始音频：

1. 根据 16-bit PCM 的平均振幅判断是否开始说话。
2. 首次检测到语音时发送实时字幕。
3. 连续静音达到阈值后发送最终转写。
4. 最终转写内容可以通过环境变量配置。

Mock TTS 根据文本长度生成短 PCM 测试音，用于验证前端音频播放和整个调用链。它不是自然
语言语音，不应在生产环境使用。

## 五、真实 Qwen Provider

Qwen 适配器继续复用已有实现和配置，不复制厂商 SDK 代码。只有选择 `qwen` 时，面试
链路才会调用百炼语音接口。

没有配置 API Key 时，应用仍可使用 Mock 正常启动；如果强制选择 Qwen，则在真正调用时
返回 `AI_API_KEY_INVALID`，密钥不会返回前端或写入普通业务日志。

## 六、异步与转写确认

- LLM、TTS 和数据库工作继续运行在语音管线的虚拟线程中，不阻塞 WebSocket 消息线程。
- ASR 只生成可编辑的转写文本，不会在识别定稿后直接替候选人提交。
- 候选人可以在现有语音面试页面确认或修改文本后手动提交。
- ASR 失败时保留手工编辑和提交能力。

## 七、配置项

```text
APP_VOICE_ASR_PROVIDER=mock
APP_VOICE_TTS_PROVIDER=mock
APP_VOICE_MOCK_TRANSCRIPT=这是本地模拟语音识别结果，请结合当前问题继续面试。
APP_VOICE_MOCK_SAMPLE_RATE=16000
APP_VOICE_MOCK_SILENCE_MS=800
APP_VOICE_MOCK_ENERGY_THRESHOLD=500
APP_VOICE_MOCK_TTS_SAMPLE_RATE=24000
```

切换真实服务：

```text
APP_VOICE_ASR_PROVIDER=qwen
APP_VOICE_TTS_PROVIDER=qwen
AI_BAILIAN_API_KEY=有效的百炼密钥
```

## 八、数据库变更

本阶段只改造服务抽象和运行时路由，没有新增数据库表或字段，因此没有迁移脚本。

## 九、主要代码

- `modules/voiceinterview/speech/SpeechRecognitionService.java`
- `modules/voiceinterview/speech/SpeechSynthesisService.java`
- `modules/voiceinterview/speech/RoutingSpeechService.java`
- `modules/voiceinterview/speech/MockSpeechRecognitionService.java`
- `modules/voiceinterview/speech/MockSpeechSynthesisService.java`
- `modules/voiceinterview/speech/QwenSpeechRecognitionAdapter.java`
- `modules/voiceinterview/speech/QwenSpeechSynthesisAdapter.java`
- `modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java`

## 十、测试

新增测试覆盖：

- Mock ASR 在语音后连续静音时产生字幕和最终转写。
- Mock TTS 生成非空 PCM 音频。
- ASR 与 TTS 可以分别路由到配置的 Provider。
- 不存在的 Provider 返回统一业务异常。
- 项目原有 Qwen、语音 WebSocket 和其他模块测试继续通过。
