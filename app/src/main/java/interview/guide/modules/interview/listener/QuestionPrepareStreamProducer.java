package interview.guide.modules.interview.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QuestionPrepareStreamProducer extends AbstractStreamProducer<String> {

  private final InterviewPersistenceService persistenceService;

  public QuestionPrepareStreamProducer(
      RedisService redisService,
      InterviewPersistenceService persistenceService) {
    super(redisService);
    this.persistenceService = persistenceService;
  }

  public void sendQuestionPrepareTask(String sessionId) {
    sendTask(sessionId);
  }

  @Override
  protected String taskDisplayName() {
    return "interview question preparation";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.INTERVIEW_QUESTION_PREPARE_STREAM_KEY;
  }

  @Override
  protected Map<String, String> buildMessage(String sessionId) {
    return Map.of(
        AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
  }

  @Override
  protected String payloadIdentifier(String sessionId) {
    return "sessionId=" + sessionId;
  }

  @Override
  protected void onSendFailed(String sessionId, String error) {
    persistenceService.updateQuestionPrepareStatus(
        sessionId,
        AsyncTaskStatus.FAILED,
        truncateError(error));
  }
}
