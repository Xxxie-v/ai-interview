# 语音面试与 WebSocket 流程

## REST 会话流程

```text
POST /api/voice-interview/sessions
  -> VoiceInterviewController
  -> VoiceInterviewService.createSession
  -> 创建 VoiceInterviewSessionEntity
  -> 返回 sessionId 和 WebSocket URL

PUT /sessions/{id}/pause|resume
POST /sessions/{id}/end
  -> VoiceInterviewService 更新状态、时间和 Redis 缓存

结束会话
  -> VoiceEvaluateStreamProducer
  -> VoiceEvaluateStreamConsumer
  -> VoiceInterviewEvaluationService
  -> UnifiedEvaluationService
  -> VoiceInterviewEvaluationEntity
```

## WebSocket 实时问答

```text
浏览器连接 /ws/voice-interview/{sessionId}
  -> WebSocketConfig
  -> VoiceInterviewWebSocketHandler.afterConnectionEstablished
  -> 启动 Qwen ASR 会话
  -> 生成/发送开场问题和 TTS 音频

浏览器发送音频块
  -> QwenAsrService.sendAudio
  -> ASR partial/final 文本
  -> Handler 合并用户语句
  -> DashscopeLlmService 生成下一轮内容
  -> QwenTtsService 合成语音
  -> Handler 向浏览器发送字幕和音频
  -> VoiceInterviewService.saveMessage 持久化问答
```

`VoiceInterviewService` 还负责 INTRO、TECH、PROJECT、HR 阶段切换，以及每阶段问题数和时长
限制。

## 核心文件

- REST Controller：[VoiceInterviewController.java](../../app/src/main/java/interview/guide/modules/voiceinterview/controller/VoiceInterviewController.java)
- WebSocket 注册：[WebSocketConfig.java](../../app/src/main/java/interview/guide/modules/voiceinterview/config/WebSocketConfig.java)
- WebSocket Handler：[VoiceInterviewWebSocketHandler.java](../../app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java)
- 会话 Service：[VoiceInterviewService.java](../../app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java)
- Prompt：[VoiceInterviewPromptService.java](../../app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewPromptService.java)
- LLM：[DashscopeLlmService.java](../../app/src/main/java/interview/guide/modules/voiceinterview/service/DashscopeLlmService.java)
- ASR：[QwenAsrService.java](../../app/src/main/java/interview/guide/modules/voiceinterview/service/QwenAsrService.java)
- TTS：[QwenTtsService.java](../../app/src/main/java/interview/guide/modules/voiceinterview/service/QwenTtsService.java)
- 评估：[VoiceInterviewEvaluationService.java](../../app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewEvaluationService.java)
- 配置：[VoiceInterviewProperties.java](../../app/src/main/java/interview/guide/modules/voiceinterview/config/VoiceInterviewProperties.java)

## 阅读时必须注意的安全缺口

- `/ws/**` 当前允许匿名访问，握手没有 JWT 验证。
- `VoiceInterviewController` 没有从 `AuthPrincipal` 强制获取用户 ID。
- 会话、消息、评估、暂停和删除目前没有统一资源归属校验。
- Handler 内维护的是单 JVM `ConcurrentHashMap` 会话状态，Redis 尚未用于事件序列和重连补发。

