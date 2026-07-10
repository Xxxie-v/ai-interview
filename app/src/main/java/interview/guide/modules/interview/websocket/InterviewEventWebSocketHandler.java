package interview.guide.modules.interview.websocket;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.model.InterviewFlowStatus;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.service.InterviewStateMachineService;
import interview.guide.modules.voiceinterview.speech.PcmWavEncoder;
import interview.guide.modules.voiceinterview.speech.SpeechAudio;
import interview.guide.modules.voiceinterview.speech.SpeechRecognitionService;
import interview.guide.modules.voiceinterview.speech.SpeechSynthesisService;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewEventWebSocketHandler extends TextWebSocketHandler {

  private static final int SEND_TIME_LIMIT_MS = 10_000;
  private static final int SEND_BUFFER_LIMIT_BYTES = 4 * 1024 * 1024;

  private final InterviewEventStreamService eventStreamService;
  private final InterviewStateMachineService stateMachineService;
  private final InterviewSessionService sessionService;
  private final SpeechRecognitionService speechRecognitionService;
  private final SpeechSynthesisService speechSynthesisService;
  private final ObjectMapper objectMapper;
  private final Map<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    String sessionId = extractSessionId(session);
    WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
        session,
        SEND_TIME_LIMIT_MS,
        SEND_BUFFER_LIMIT_BYTES);
    sessions.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet()).add(safeSession);
    long lastSequence = lastSequence(session);
    var missedEvents = eventStreamService.readAfter(sessionId, lastSequence);
    if (missedEvents.isEmpty()) {
      publishAndBroadcast(
          sessionId,
          InterviewEventType.SESSION_STATUS_CHANGED,
          Map.of("status", stateMachineService.getStatus(sessionId).name()));
    } else {
      missedEvents.forEach(event -> send(safeSession, event));
    }
    log.info(
        "Interview event socket connected: sessionId={}, lastSequence={}, replayed={}",
        sessionId,
        lastSequence,
        missedEvents.size());
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String sessionId = extractSessionId(session);
    try {
      InterviewWebSocketEvent event = objectMapper.readValue(
          message.getPayload(),
          InterviewWebSocketEvent.class);
      validateClientEvent(session, sessionId, event);
      if (event.type() != InterviewEventType.AUDIO_CHUNK
          && !eventStreamService.markClientEvent(sessionId, event.eventId())) {
        return;
      }
      handleClientEvent(sessionId, event);
    } catch (BusinessException e) {
      sendError(sessionId, e.getMessage());
    } catch (Exception e) {
      log.error("Interview WebSocket message failed: sessionId={}", sessionId, e);
      sendError(sessionId, "消息格式错误或处理失败");
    }
  }

  private void handleClientEvent(String sessionId, InterviewWebSocketEvent event) {
    switch (event.type()) {
      case DEVICE_READY -> handleDeviceReady(sessionId);
      case START_INTERVIEW -> handleStart(sessionId);
      case ANSWER_STARTED -> handleAnswerStarted(sessionId);
      case ANSWER_SUBMITTED -> handleAnswerSubmitted(sessionId, event);
      case AUDIO_CHUNK -> handleAudioChunk(sessionId, event);
      case READ_QUESTION -> synthesizeCurrentQuestion(sessionId);
      case PAUSE_REQUEST -> handlePause(sessionId);
      case RESUME_REQUEST -> handleResume(sessionId);
      case FINISH_REQUEST -> handleFinish(sessionId);
      case VIDEO_CHUNK_UPLOADED -> publishAndBroadcast(
          sessionId,
          InterviewEventType.ANSWER_ACCEPTED,
          Map.of("sourceEventId", event.eventId(), "kind", "VIDEO_CHUNK"));
      case PING -> publishAndBroadcast(
          sessionId,
          InterviewEventType.PONG,
          Map.of("sourceEventId", event.eventId()));
      default -> throw new BusinessException(
          interview.guide.common.exception.ErrorCode.BAD_REQUEST,
          "不支持的客户端事件: " + event.type());
    }
  }

  private void handleDeviceReady(String sessionId) {
    InterviewFlowStatus status = stateMachineService.getStatus(sessionId);
    if (status == InterviewFlowStatus.INIT) {
      stateMachineService.transition(sessionId, InterviewFlowStatus.DEVICE_CHECK);
      stateMachineService.transition(sessionId, InterviewFlowStatus.READY);
    } else if (status == InterviewFlowStatus.DEVICE_CHECK) {
      stateMachineService.transition(sessionId, InterviewFlowStatus.READY);
    }
    publishStatus(sessionId);
  }

  private void handleStart(String sessionId) {
    sessionService.assertQuestionsReady(sessionId);
    InterviewFlowStatus status = stateMachineService.getStatus(sessionId);
    if (status == InterviewFlowStatus.INIT || status == InterviewFlowStatus.DEVICE_CHECK) {
      throw new BusinessException(
          interview.guide.common.exception.ErrorCode.BAD_REQUEST,
          "请先完成摄像头和麦克风设备检查");
    }
    if (status == InterviewFlowStatus.PAUSED) {
      sessionService.resumeInterview(sessionId);
      status = InterviewFlowStatus.QUESTIONING;
    }
    if (status == InterviewFlowStatus.FINISHED || status == InterviewFlowStatus.TERMINATED) {
      publishAndBroadcast(
          sessionId,
          InterviewEventType.INTERVIEW_FINISHED,
          Map.of("status", status.name()));
      return;
    }
    if (status == InterviewFlowStatus.ANSWERING || status == InterviewFlowStatus.EVALUATING) {
      publishStatus(sessionId);
      startSpeechRecognition(sessionId);
      synthesizeCurrentQuestion(sessionId);
      return;
    }

    Map<String, Object> current = sessionService.getCurrentQuestionResponse(sessionId);
    publishStatus(sessionId);
    if (!Boolean.TRUE.equals(current.get("completed"))) {
      publishAndBroadcast(
          sessionId,
          InterviewEventType.NEW_QUESTION,
          Map.of("question", current.get("question")));
      startSpeechRecognition(sessionId);
      synthesizeQuestion(sessionId, current.get("question"));
    }
  }

  private void startSpeechRecognition(String sessionId) {
    if (speechRecognitionService.hasActiveSession(sessionId)) {
      publishAndBroadcast(
          sessionId,
          InterviewEventType.SPEECH_RECOGNITION_READY,
          Map.of("ready", true));
      return;
    }
    Mono.fromRunnable(() -> speechRecognitionService.startTranscription(
            sessionId,
            text -> publishTranscript(sessionId, text, true),
            text -> publishTranscript(sessionId, text, false),
            error -> {
              log.error("Formal interview ASR failed: sessionId={}", sessionId, error);
              sendError(sessionId, "语音识别服务异常，请稍后重试");
            }))
        .subscribeOn(Schedulers.boundedElastic())
        .doOnSuccess(ignored -> publishAndBroadcast(
                sessionId,
                InterviewEventType.SPEECH_RECOGNITION_READY,
                Map.of("ready", true)))
        .subscribe(
            ignored -> { },
            error -> {
              log.error("Formal interview ASR startup failed: sessionId={}", sessionId, error);
              sendError(sessionId, "语音识别启动失败");
            });
  }

  private void handleAudioChunk(String sessionId, InterviewWebSocketEvent event) {
    Object rawData = event.payload().get("data");
    if (rawData == null || rawData.toString().isBlank()
        || rawData.toString().length() > 64 * 1024) {
      throw new BusinessException(
          interview.guide.common.exception.ErrorCode.BAD_REQUEST,
          "音频分片内容无效");
    }
    try {
      speechRecognitionService.sendAudio(
          sessionId,
          Base64.getDecoder().decode(rawData.toString()));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          interview.guide.common.exception.ErrorCode.BAD_REQUEST,
          "音频分片编码无效");
    }
  }

  private void publishTranscript(String sessionId, String text, boolean finalResult) {
    if (text == null || text.isBlank()) return;
    Map<String, Object> payload = Map.of("text", text, "final", finalResult);
    if (finalResult) {
      publishAndBroadcast(sessionId, InterviewEventType.ANSWER_TRANSCRIPT, payload);
    } else {
      broadcastTransient(sessionId, InterviewEventType.ANSWER_TRANSCRIPT, payload);
    }
  }

  private void synthesizeCurrentQuestion(String sessionId) {
    Map<String, Object> current = sessionService.getCurrentQuestionResponse(sessionId);
    if (!Boolean.TRUE.equals(current.get("completed"))) {
      synthesizeQuestion(sessionId, current.get("question"));
    }
  }

  private void synthesizeQuestion(String sessionId, Object rawQuestion) {
    if (!(rawQuestion instanceof InterviewQuestionDTO question)) {
      log.warn(
          "Formal interview TTS skipped because question type is invalid: sessionId={}, type={}",
          sessionId,
          rawQuestion == null ? "null" : rawQuestion.getClass().getName());
      return;
    }
    if (question.question() == null || question.question().isBlank()) {
      return;
    }
    log.info(
        "Formal interview TTS requested: sessionId={}, questionIndex={}",
        sessionId,
        question.questionIndex());
    Mono.fromCallable(() -> speechSynthesisService.synthesizeAudio(question.question()))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            audio -> publishQuestionAudio(sessionId, question.questionIndex(), audio),
            error -> {
              log.error("Formal interview TTS failed: sessionId={}", sessionId, error);
              sendError(sessionId, "AI 读题失败，可根据屏幕文字继续作答");
            });
  }

  private void publishQuestionAudio(
      String sessionId,
      int questionIndex,
      SpeechAudio audio) {
    byte[] pcmData = audio.data();
    if (pcmData.length == 0) {
      log.warn(
          "Formal interview TTS returned empty audio: sessionId={}, questionIndex={}",
          sessionId,
          questionIndex);
      sendError(sessionId, "AI 读题没有生成音频，请点击重新播放题目重试");
      return;
    }
    byte[] wavData = PcmWavEncoder.encode(pcmData, audio.sampleRate());
    broadcastTransient(
        sessionId,
        InterviewEventType.QUESTION_AUDIO_READY,
        Map.of(
            "questionIndex", questionIndex,
            "data", Base64.getEncoder().encodeToString(wavData),
            "mimeType", "audio/wav",
            "sampleRate", audio.sampleRate()));
  }

  private void handleAnswerStarted(String sessionId) {
    stateMachineService.transition(sessionId, InterviewFlowStatus.ANSWERING);
    publishStatus(sessionId);
  }

  private void handleAnswerSubmitted(String sessionId, InterviewWebSocketEvent event) {
    Object rawIndex = event.payload().get("questionIndex");
    Object rawAnswer = event.payload().get("answer");
    if (!(rawIndex instanceof Number number)
        || rawAnswer == null
        || rawAnswer.toString().isBlank()) {
      throw new BusinessException(
          interview.guide.common.exception.ErrorCode.BAD_REQUEST,
          "ANSWER_SUBMITTED 缺少 questionIndex 或 answer");
    }
    SubmitAnswerRequest request = new SubmitAnswerRequest(
        sessionId,
        number.intValue(),
        rawAnswer.toString());
    publishAndBroadcast(
        sessionId,
        InterviewEventType.NEXT_QUESTION_PENDING,
        Map.of("questionIndex", number.intValue()));
    Mono.fromCallable(() -> sessionService.submitAnswer(request, event.eventId()))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            response -> handleAnswerResponse(sessionId, response),
            error -> {
              log.error("Async answer processing failed: sessionId={}", sessionId, error);
              sendError(sessionId, safeMessage(error));
            });
  }

  private void handleAnswerResponse(String sessionId, SubmitAnswerResponse response) {
    publishAndBroadcast(
        sessionId,
        InterviewEventType.ANSWER_ACCEPTED,
        Map.of(
            "currentIndex", response.currentIndex(),
            "totalQuestions", response.totalQuestions()));
    publishStatus(sessionId);
    if (response.hasNextQuestion()) {
      publishAndBroadcast(
          sessionId,
          InterviewEventType.NEW_QUESTION,
          Map.of("question", response.nextQuestion()));
      synthesizeQuestion(sessionId, response.nextQuestion());
    } else {
      publishAndBroadcast(
          sessionId,
          InterviewEventType.INTERVIEW_FINISHED,
          Map.of("status", InterviewFlowStatus.FINISHED.name()));
    }
  }

  private void handlePause(String sessionId) {
    sessionService.pauseInterview(sessionId);
    publishStatus(sessionId);
  }

  private void handleResume(String sessionId) {
    sessionService.resumeInterview(sessionId);
    publishStatus(sessionId);
    Map<String, Object> current = sessionService.getCurrentQuestionResponse(sessionId);
    if (!Boolean.TRUE.equals(current.get("completed"))) {
      publishAndBroadcast(
          sessionId,
          InterviewEventType.NEW_QUESTION,
          Map.of("question", current.get("question")));
    }
  }

  private void handleFinish(String sessionId) {
    sessionService.completeInterview(sessionId);
    publishStatus(sessionId);
    publishAndBroadcast(
        sessionId,
        InterviewEventType.INTERVIEW_FINISHED,
        Map.of("status", InterviewFlowStatus.FINISHED.name()));
  }

  private void publishStatus(String sessionId) {
    publishAndBroadcast(
        sessionId,
        InterviewEventType.SESSION_STATUS_CHANGED,
        Map.of("status", stateMachineService.getStatus(sessionId).name()));
  }

  public void publishQuestionPreparationStatus(
      String sessionId,
      boolean completed,
      String error) {
    if (completed) {
      publishAndBroadcast(
          sessionId,
          InterviewEventType.QUESTIONS_READY,
          Map.of("status", "COMPLETED"));
      return;
    }
    publishAndBroadcast(
        sessionId,
        InterviewEventType.QUESTIONS_FAILED,
        Map.of(
            "status", "FAILED",
            "message", error == null ? "Question preparation failed" : error));
  }

  private void publishAndBroadcast(
      String sessionId,
      InterviewEventType type,
      Map<String, Object> payload) {
    InterviewWebSocketEvent event = eventStreamService.publish(sessionId, type, payload);
    sessions.getOrDefault(sessionId, Set.of()).forEach(session -> send(session, event));
  }

  private void broadcastTransient(
      String sessionId,
      InterviewEventType type,
      Map<String, Object> payload) {
    InterviewWebSocketEvent event = new InterviewWebSocketEvent(
        UUID.randomUUID().toString(),
        type,
        sessionId,
        0,
        OffsetDateTime.now(),
        payload == null ? Map.of() : Map.copyOf(payload));
    sessions.getOrDefault(sessionId, Set.of()).forEach(session -> send(session, event));
  }

  private void sendError(String sessionId, String message) {
    publishAndBroadcast(
        sessionId,
        InterviewEventType.ERROR,
        Map.of("message", message == null ? "处理失败" : message));
  }

  private void send(WebSocketSession session, InterviewWebSocketEvent event) {
    try {
      if (session.isOpen()) {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
      }
    } catch (IOException e) {
      log.warn("Interview event send failed: connectionId={}", session.getId(), e);
    }
  }

  private void validateClientEvent(
      WebSocketSession session,
      String sessionId,
      InterviewWebSocketEvent event) {
    if (event == null || event.type() == null || !event.type().isClientEvent()) {
      throw new BusinessException(
          interview.guide.common.exception.ErrorCode.BAD_REQUEST,
          "无效的客户端事件类型");
    }
    if (!sessionId.equals(event.sessionId())) {
      throw new BusinessException(
          interview.guide.common.exception.ErrorCode.BAD_REQUEST,
          "事件 sessionId 与连接不一致");
    }
    boolean managementMode = Boolean.TRUE.equals(session.getAttributes().get(
        InterviewEventHandshakeInterceptor.MANAGEMENT_MODE_ATTRIBUTE));
    if (managementMode && event.type() != InterviewEventType.PING) {
      throw new BusinessException(
          interview.guide.common.exception.ErrorCode.FORBIDDEN,
          "管理员订阅连接为只读模式");
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String sessionId = extractSessionId(session);
    Set<WebSocketSession> sessionConnections = sessions.get(sessionId);
    if (sessionConnections != null) {
      sessionConnections.removeIf(connection -> connection.getId().equals(session.getId()));
      if (sessionConnections.isEmpty()) {
        sessions.remove(sessionId);
        speechRecognitionService.stopTranscription(sessionId);
      }
    }
    log.info("Interview event socket closed: sessionId={}, status={}", sessionId, status);
  }

  private String extractSessionId(WebSocketSession session) {
    String path = session.getUri() == null ? "" : session.getUri().getPath();
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private long lastSequence(WebSocketSession session) {
    Object value = session.getAttributes().get(
        InterviewEventHandshakeInterceptor.LAST_SEQUENCE_ATTRIBUTE);
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private String safeMessage(Throwable error) {
    if (error instanceof BusinessException && error.getMessage() != null) {
      return error.getMessage();
    }
    return "处理回答失败，请重试";
  }
}
