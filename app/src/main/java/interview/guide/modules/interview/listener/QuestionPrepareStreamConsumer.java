package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.websocket.InterviewEventWebSocketHandler;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class QuestionPrepareStreamConsumer
    extends AbstractStreamConsumer<QuestionPrepareStreamConsumer.QuestionPreparePayload> {

  private final InterviewSessionRepository sessionRepository;
  private final InterviewPersistenceService persistenceService;
  private final InterviewSessionService sessionService;
  private final InterviewEventWebSocketHandler eventHandler;

  public QuestionPrepareStreamConsumer(
      RedisService redisService,
      InterviewSessionRepository sessionRepository,
      InterviewPersistenceService persistenceService,
      InterviewSessionService sessionService,
      InterviewEventWebSocketHandler eventHandler) {
    super(redisService);
    this.sessionRepository = sessionRepository;
    this.persistenceService = persistenceService;
    this.sessionService = sessionService;
    this.eventHandler = eventHandler;
  }

  record QuestionPreparePayload(String sessionId) {}

  @Override
  protected String taskDisplayName() {
    return "interview question preparation";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.INTERVIEW_QUESTION_PREPARE_STREAM_KEY;
  }

  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.INTERVIEW_QUESTION_PREPARE_GROUP_NAME;
  }

  @Override
  protected String consumerPrefix() {
    return AsyncTaskStreamConstants.INTERVIEW_QUESTION_PREPARE_CONSUMER_PREFIX;
  }

  @Override
  protected String threadName() {
    return "interview-question-prepare-consumer";
  }

  @Override
  protected QuestionPreparePayload parsePayload(
      StreamMessageId messageId,
      Map<String, String> data) {
    String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
    if (sessionId == null || sessionId.isBlank()) {
      log.warn("Question preparation message has no sessionId: messageId={}", messageId);
      return null;
    }
    return new QuestionPreparePayload(sessionId);
  }

  @Override
  protected String payloadIdentifier(QuestionPreparePayload payload) {
    return "sessionId=" + payload.sessionId();
  }

  @Override
  protected void markProcessing(QuestionPreparePayload payload) {
    sessionRepository.findBySessionId(payload.sessionId()).ifPresent(session -> {
      if (session.getQuestionPrepareStatus() != AsyncTaskStatus.COMPLETED) {
        persistenceService.updateQuestionPrepareStatus(
            payload.sessionId(),
            AsyncTaskStatus.PROCESSING,
            null);
      }
    });
  }

  @Override
  protected void processBusiness(QuestionPreparePayload payload) {
    sessionService.prepareQuestions(payload.sessionId());
  }

  @Override
  protected void markCompleted(QuestionPreparePayload payload) {
    InterviewSessionEntity session = sessionRepository.findBySessionId(payload.sessionId())
        .orElse(null);
    if (session != null && session.getQuestionPrepareStatus() != AsyncTaskStatus.COMPLETED) {
      persistenceService.updateQuestionPrepareStatus(
          payload.sessionId(),
          AsyncTaskStatus.COMPLETED,
          null);
    }
    publishStatusSafely(payload.sessionId(), true, null);
  }

  @Override
  protected void markFailed(QuestionPreparePayload payload, String error) {
    persistenceService.updateQuestionPrepareStatus(
        payload.sessionId(),
        AsyncTaskStatus.FAILED,
        error);
    publishStatusSafely(payload.sessionId(), false, error);
  }

  @Override
  protected void retryMessage(QuestionPreparePayload payload, int retryCount, String taskId) {
    redisService().streamAdd(
        AsyncTaskStreamConstants.INTERVIEW_QUESTION_PREPARE_STREAM_KEY,
        Map.of(
            AsyncTaskStreamConstants.FIELD_SESSION_ID, payload.sessionId(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount),
            AsyncTaskStreamConstants.FIELD_TASK_ID, taskId),
        AsyncTaskStreamConstants.STREAM_MAX_LEN);
  }

  private void publishStatusSafely(String sessionId, boolean completed, String error) {
    try {
      eventHandler.publishQuestionPreparationStatus(sessionId, completed, error);
    } catch (Exception e) {
      log.error(
          "Failed to publish question preparation status: sessionId={}, completed={}",
          sessionId,
          completed,
          e);
    }
  }
}
