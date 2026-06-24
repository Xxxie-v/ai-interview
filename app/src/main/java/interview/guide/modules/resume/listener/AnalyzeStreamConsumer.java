package interview.guide.modules.resume.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeQuestionPreparationService;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

/**
 * 简历出题 Stream 消费者。
 * 上传后只生成面试题，不再执行简历评分或分析。
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer
    extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

  private final ResumeRepository resumeRepository;
  private final ResumeQuestionPreparationService questionPreparationService;

  public AnalyzeStreamConsumer(
      RedisService redisService,
      ResumeRepository resumeRepository,
      ResumeQuestionPreparationService questionPreparationService) {
    super(redisService);
    this.resumeRepository = resumeRepository;
    this.questionPreparationService = questionPreparationService;
  }

  record AnalyzePayload(Long resumeId, String content) {}

  @Override
  protected String taskDisplayName() {
    return "简历出题";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
  }

  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
  }

  @Override
  protected String consumerPrefix() {
    return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
  }

  @Override
  protected String threadName() {
    return "resume-question-consumer";
  }

  @Override
  protected AnalyzePayload parsePayload(
      StreamMessageId messageId,
      Map<String, String> data) {
    String resumeId = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
    String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
    if (resumeId == null || content == null) {
      log.warn("简历出题消息格式错误，跳过: messageId={}", messageId);
      return null;
    }
    return new AnalyzePayload(Long.parseLong(resumeId), content);
  }

  @Override
  protected String payloadIdentifier(AnalyzePayload payload) {
    return "resumeId=" + payload.resumeId();
  }

  @Override
  protected void markProcessing(AnalyzePayload payload) {
    updateQuestionStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
  }

  @Override
  protected void processBusiness(AnalyzePayload payload) {
    if (!resumeRepository.existsById(payload.resumeId())) {
      log.warn("简历已删除，跳过出题任务: resumeId={}", payload.resumeId());
      return;
    }
    questionPreparationService.prepare(payload.resumeId());
  }

  @Override
  protected void markCompleted(AnalyzePayload payload) {
    updateQuestionStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
  }

  @Override
  protected void markFailed(AnalyzePayload payload, String error) {
    updateQuestionStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
  }

  @Override
  protected void retryMessage(AnalyzePayload payload, int retryCount, String taskId) {
    try {
      Map<String, String> message = Map.of(
          AsyncTaskStreamConstants.FIELD_RESUME_ID, payload.resumeId().toString(),
          AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
          AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount),
          AsyncTaskStreamConstants.FIELD_TASK_ID, taskId);
      redisService().streamAdd(
          AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
          message,
          AsyncTaskStreamConstants.STREAM_MAX_LEN);
      log.info(
          "简历出题任务已重新入队: resumeId={}, retryCount={}",
          payload.resumeId(),
          retryCount);
    } catch (Exception e) {
      log.error("简历出题重试入队失败: resumeId={}", payload.resumeId(), e);
      updateQuestionStatus(
          payload.resumeId(),
          AsyncTaskStatus.FAILED,
          truncateError("重试入队失败: " + e.getMessage()));
    }
  }

  private void updateQuestionStatus(Long resumeId, AsyncTaskStatus status, String error) {
    try {
      resumeRepository.findById(resumeId).ifPresent(resume -> {
        resume.setQuestionPrepareStatus(status);
        resume.setQuestionPrepareError(error);
        resumeRepository.save(resume);
        log.debug("简历出题状态已更新: resumeId={}, status={}", resumeId, status);
      });
    } catch (Exception e) {
      log.error("更新简历出题状态失败: resumeId={}, status={}", resumeId, status, e);
    }
  }
}
