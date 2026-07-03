package interview.guide.common.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.stream.StreamMessageId;

@ExtendWith(MockitoExtension.class)
@DisplayName("Redis Stream 消费者可靠性")
class AbstractStreamConsumerTest {

  private static final StreamMessageId MESSAGE_ID = new StreamMessageId(100, 1);
  private static final String TASK_ID = "task-001";

  @Mock
  private RedisService redisService;

  private TestConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new TestConsumer(redisService);
  }

  @Nested
  @DisplayName("幂等处理")
  class Idempotency {

    @Test
    @DisplayName("已完成的重复消息直接确认，不重复执行业务")
    void shouldSkipCompletedDuplicate() {
      when(redisService.exists("async:completed:{" + TASK_ID + "}")).thenReturn(true);

      consumer.processMessage(MESSAGE_ID, message(0));

      assertThat(consumer.businessCalls).isZero();
      verify(redisService).streamAck("test:stream", "test-group", MESSAGE_ID);
      verify(redisService, never()).tryLock(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("成功后写入完成标记再确认消息")
    void shouldMarkCompletedBeforeAck() {
      when(redisService.exists("async:completed:{" + TASK_ID + "}")).thenReturn(false);
      when(redisService.tryLock(
          eq("async:processing:{" + TASK_ID + "}"),
          eq(0L),
          eq(AsyncTaskStreamConstants.TASK_LOCK_LEASE_MS),
          any())).thenReturn(true);

      consumer.processMessage(MESSAGE_ID, message(0));

      assertThat(consumer.businessCalls).isOne();
      verify(redisService).set(
          eq("async:completed:{" + TASK_ID + "}"),
          eq("1"),
          eq(Duration.ofDays(AsyncTaskStreamConstants.COMPLETED_MARKER_TTL_DAYS)));
      verify(redisService).streamAck("test:stream", "test-group", MESSAGE_ID);
      verify(redisService).unlock("async:processing:{" + TASK_ID + "}");
    }
  }

  @Nested
  @DisplayName("失败处理")
  class FailureHandling {

    @Test
    @DisplayName("可重试失败保留同一个任务 ID")
    void shouldKeepTaskIdWhenRetrying() {
      prepareLock();
      consumer.failBusiness = true;

      consumer.processMessage(MESSAGE_ID, message(1));

      assertThat(consumer.retriedTaskId).isEqualTo(TASK_ID);
      assertThat(consumer.retriedCount).isEqualTo(2);
      verify(redisService).streamAck("test:stream", "test-group", MESSAGE_ID);
    }

    @Test
    @DisplayName("超过重试上限后写入统一死信队列")
    void shouldWriteDeadLetterAfterMaxRetries() {
      prepareLock();
      consumer.failBusiness = true;
      ArgumentCaptor<Map<String, String>> messageCaptor = ArgumentCaptor.forClass(Map.class);

      consumer.processMessage(
          MESSAGE_ID,
          message(AsyncTaskStreamConstants.MAX_RETRY_COUNT));

      assertThat(consumer.failedError).contains("failed after retry");
      verify(redisService).streamAdd(
          eq("test:dead-letter"),
          messageCaptor.capture(),
          eq(AsyncTaskStreamConstants.STREAM_MAX_LEN));
      assertThat(messageCaptor.getValue())
          .containsEntry(AsyncTaskStreamConstants.FIELD_TASK_ID, TASK_ID)
          .containsEntry(AsyncTaskStreamConstants.FIELD_SOURCE_STREAM, "test:stream")
          .containsKey(AsyncTaskStreamConstants.FIELD_FAILED_AT);
    }

    private void prepareLock() {
      when(redisService.exists("async:completed:{" + TASK_ID + "}")).thenReturn(false);
      when(redisService.tryLock(any(), eq(0L), anyLong(), any())).thenReturn(true);
    }
  }

  private Map<String, String> message(int retryCount) {
    return Map.of(
        "value", "payload",
        AsyncTaskStreamConstants.FIELD_TASK_ID, TASK_ID,
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount));
  }

  private static final class TestConsumer extends AbstractStreamConsumer<String> {

    private int businessCalls;
    private boolean failBusiness;
    private int retriedCount;
    private String retriedTaskId;
    private String failedError;

    private TestConsumer(RedisService redisService) {
      super(redisService);
    }

    @Override
    protected String taskDisplayName() {
      return "测试";
    }

    @Override
    protected String streamKey() {
      return "test:stream";
    }

    @Override
    protected String groupName() {
      return "test-group";
    }

    @Override
    protected String consumerPrefix() {
      return "test-consumer-";
    }

    @Override
    protected String threadName() {
      return "test-thread";
    }

    @Override
    protected String parsePayload(StreamMessageId messageId, Map<String, String> data) {
      return data.get("value");
    }

    @Override
    protected String payloadIdentifier(String payload) {
      return payload;
    }

    @Override
    protected void markProcessing(String payload) {
    }

    @Override
    protected void processBusiness(String payload) {
      businessCalls++;
      if (failBusiness) {
        throw new IllegalStateException("expected failure");
      }
    }

    @Override
    protected void markCompleted(String payload) {
    }

    @Override
    protected void markFailed(String payload, String error) {
      failedError = error;
    }

    @Override
    protected void retryMessage(String payload, int retryCount, String taskId) {
      retriedCount = retryCount;
      retriedTaskId = taskId;
    }
  }
}
