# 第五阶段：WebSocket 实时事件流改造结果

这一阶段为已有正式面试会话新增统一实时事件通道：

```text
/ws/interviews/{sessionId}
```

原有 `/ws/voice-interview/{sessionId}` 继续保留，避免破坏已经可运行的 ASR、TTS 和语音对话。
后续摄像头、视频分片和设备事件统一接入新通道，不再创建第三套面试系统。

## 一、连接方式

浏览器连接示例：

```text
ws://localhost:8080/ws/interviews/{sessionId}
  ?access_token={短期访问令牌}
  &lastSequence={客户端最后收到的序号}
```

握手阶段会检查：

- JWT 是否有效。
- 用户是否仍允许登录。
- 当前用户是否为该面试会话所有者，或者是否为管理员。
- `sessionId` 是否存在。

管理员可以用管理模式订阅事件，但连接是只读的，只允许心跳；终止会话仍使用受权限保护的
REST 管理接口，不能冒充候选人提交回答。

只验证角色而不验证会话归属是不够的，因此握手直接使用
`findBySessionIdAndOwnerUserId` 防止 IDOR。

## 二、统一消息结构

```json
{
  "eventId": "uuid",
  "type": "NEW_QUESTION",
  "sessionId": "16位会话编号",
  "sequence": 3,
  "timestamp": "2026-08-04T01:30:00+08:00",
  "payload": {}
}
```

客户端发出的 `sequence` 可以为 0。服务端事件的 `sequence` 由 Redis 原子计数器生成，客户端
按 `eventId` 和 `sequence` 双重去重。

## 三、支持的事件

客户端事件：

- `DEVICE_READY`
- `START_INTERVIEW`
- `ANSWER_STARTED`
- `ANSWER_SUBMITTED`
- `VIDEO_CHUNK_UPLOADED`
- `PAUSE_REQUEST`
- `RESUME_REQUEST`
- `FINISH_REQUEST`
- `PING`

服务端事件：

- `SESSION_STATUS_CHANGED`
- `NEW_QUESTION`
- `QUESTION_AUDIO_READY`
- `ANSWER_ACCEPTED`
- `EVALUATION_COMPLETED`
- `NEXT_QUESTION_PENDING`
- `WARNING_EVENT`
- `INTERVIEW_FINISHED`
- `PONG`
- `ERROR`

`ANSWER_SUBMITTED` 的 `payload`：

```json
{
  "questionIndex": 0,
  "answer": "候选人的回答文本"
}
```

事件 ID 会直接复用为答案提交的幂等键。

## 四、异步处理

回答提交会触发保存、逐题评估和动态追问，可能涉及 LLM。处理代码使用 Reactor
`Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 调度到异步线程，WebSocket
消息线程不会等待模型完成。

在处理期间先发送 `NEXT_QUESTION_PENDING`，完成后发送 `ANSWER_ACCEPTED` 以及下一题或
`INTERVIEW_FINISHED`。

## 五、断线恢复

- 每个会话事件在 Redis 中保存 2 小时。
- 默认每个会话最多保留最近 200 条事件。
- 重连携带 `lastSequence` 后，服务端只补发更大的序号。
- 客户端在 `sessionStorage` 保存最后序号。
- 客户端每 20 秒发送一次 `PING`，服务端返回 `PONG`。
- 客户端断线后使用指数退避重连，最多尝试 5 次。

## 六、配置项

```text
APP_INTERVIEW_EVENT_RETENTION=PT2H
APP_INTERVIEW_EVENT_MAX_PER_SESSION=200
```

## 七、主要代码

后端：

1. `InterviewWebSocketEvent.java`：统一事件结构。
2. `InterviewEventType.java`：客户端和服务端事件枚举。
3. `InterviewEventHandshakeInterceptor.java`：认证与归属校验。
4. `InterviewEventStreamService.java`：Redis 序号、保存、补发和幂等。
5. `InterviewEventWebSocketHandler.java`：事件路由与 Reactor 异步处理。
6. `WebSocketConfig.java`：注册标准事件端点。

前端：

- `frontend/src/api/interviewEvents.ts`：连接、心跳、去重和断线重连封装。
- `frontend/src/pages/InterviewPage.tsx`：正式岗位面试进入页面后建立连接，通过事件提交答案、
  接收下一题和结束通知；实时连接暂不可用时自动回退到原 REST 答题接口。

创建文字面试会话后，响应中的 `webSocketPath` 会返回对应事件通道路径。

重连后客户端会再次发送 `START_INTERVIEW`。服务端按状态幂等处理：暂停状态自动恢复，
答题或评估处理中只同步状态，已经结束则返回结束事件，不会重复出题。

## 八、测试

握手测试覆盖：

- 没有令牌时返回 401。
- 候选人连接他人会话时返回 403。
- 会话所有者连接成功，并正确保存 `lastSequence`。

同时运行项目全部后端测试、前端 TypeScript 检查和生产构建。

## 九、当前边界

`VIDEO_CHUNK_UPLOADED` 和 `QUESTION_AUDIO_READY` 已定义协议，但视频分片存储与浏览器
MediaRecorder 属于下一阶段。当前阶段只建立可靠的实时事件底座，不提前实现视频上传。
