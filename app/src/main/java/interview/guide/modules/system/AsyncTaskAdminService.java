package interview.guide.modules.system;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AsyncTaskAdminService {

  private static final List<PipelineDefinition> PIPELINES = List.of(
      new PipelineDefinition(
          "kb-vectorize",
          "知识库向量化",
          AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
          AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME),
      new PipelineDefinition(
          "resume-analyze",
          "简历出题",
          AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
          AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME),
      new PipelineDefinition(
          "interview-evaluate",
          "面试评估",
          AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
          AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME),
      new PipelineDefinition(
          "interview-question-prepare",
          "面试出题",
          AsyncTaskStreamConstants.INTERVIEW_QUESTION_PREPARE_STREAM_KEY,
          AsyncTaskStreamConstants.INTERVIEW_QUESTION_PREPARE_GROUP_NAME),
      new PipelineDefinition(
          "voice-evaluate",
          "语音面试评估",
          AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY,
          AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME));

  private final RedisService redisService;

  public List<PipelineStatusResponse> listStatus() {
    return PIPELINES.stream().map(this::loadStatus).toList();
  }

  public List<DeadLetterResponse> listDeadLetters(String pipelineId, int limit) {
    PipelineDefinition pipeline = requirePipeline(pipelineId);
    int safeLimit = Math.max(1, Math.min(limit, 100));
    List<DeadLetterResponse> responses = new ArrayList<>();
    redisService.streamRangeReversed(deadLetterStreamKey(pipeline.streamKey()), safeLimit)
        .forEach((messageId, data) -> responses.add(
            new DeadLetterResponse(messageId.toString(), data)));
    return responses;
  }

  public ReplayResponse replay(String pipelineId, String deadLetterId) {
    PipelineDefinition pipeline = requirePipeline(pipelineId);
    StreamMessageId messageId = parseMessageId(deadLetterId);
    String deadLetterStream = deadLetterStreamKey(pipeline.streamKey());
    Map<String, String> deadLetter = redisService.streamGet(deadLetterStream, messageId);
    if (deadLetter == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "死信消息不存在或已重放");
    }

    Map<String, String> replayMessage = new LinkedHashMap<>(deadLetter);
    replayMessage.remove(AsyncTaskStreamConstants.FIELD_SOURCE_STREAM);
    replayMessage.remove(AsyncTaskStreamConstants.FIELD_ERROR);
    replayMessage.remove(AsyncTaskStreamConstants.FIELD_FAILED_AT);
    replayMessage.put(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
    String newMessageId = redisService.streamAdd(
        pipeline.streamKey(),
        replayMessage,
        AsyncTaskStreamConstants.STREAM_MAX_LEN);
    redisService.streamRemove(deadLetterStream, messageId);
    return new ReplayResponse(deadLetterId, newMessageId);
  }

  private PipelineStatusResponse loadStatus(PipelineDefinition pipeline) {
    RedisService.StreamGroupStatus groupStatus = redisService.streamGroupStatus(
        pipeline.streamKey(), pipeline.groupName());
    return new PipelineStatusResponse(
        pipeline.id(),
        pipeline.name(),
        pipeline.streamKey(),
        redisService.streamLen(pipeline.streamKey()),
        groupStatus.pendingCount(),
        groupStatus.lag(),
        groupStatus.consumerCount(),
        redisService.streamLen(deadLetterStreamKey(pipeline.streamKey())));
  }

  private PipelineDefinition requirePipeline(String pipelineId) {
    return PIPELINES.stream()
        .filter(item -> item.id().equals(pipelineId))
        .findFirst()
        .orElseThrow(() -> new BusinessException(
            ErrorCode.BAD_REQUEST, "不支持的异步任务管道: " + pipelineId));
  }

  private StreamMessageId parseMessageId(String messageId) {
    try {
      String[] parts = messageId.split("-", 2);
      if (parts.length != 2) {
        throw new NumberFormatException("missing sequence");
      }
      return new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    } catch (NumberFormatException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的 Redis Stream 消息 ID");
    }
  }

  private String deadLetterStreamKey(String streamKey) {
    return streamKey.substring(0, streamKey.length() - 7) + ":dead-letter";
  }

  private record PipelineDefinition(
      String id,
      String name,
      String streamKey,
      String groupName) {
  }

  public record PipelineStatusResponse(
      String id,
      String name,
      String streamKey,
      long streamLength,
      long pendingCount,
      long lag,
      int consumerCount,
      long deadLetterCount) {
  }

  public record DeadLetterResponse(String messageId, Map<String, String> data) {
  }

  public record ReplayResponse(String deadLetterId, String newMessageId) {
  }
}
