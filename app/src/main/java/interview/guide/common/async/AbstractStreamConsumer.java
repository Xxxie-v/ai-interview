package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastPendingRecoveryAt = new AtomicLong();
    private ExecutorService executorService;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    @PostConstruct
    public void init() {
        int concurrency = Math.max(1, Math.min(32, consumerConcurrency()));
        String instanceId = UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger threadSequence = new AtomicInteger();
        this.executorService = new ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(
                    r,
                    threadName() + "-" + threadSequence.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        prepareConsumerGroup();
        for (int worker = 0; worker < concurrency; worker++) {
            String consumerName = consumerPrefix() + instanceId + "-" + worker;
            executorService.submit(() -> consumeLoop(consumerName));
        }
        log.info("{} consumers started: instanceId={}, concurrency={}",
            taskDisplayName(), instanceId, concurrency);
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("{} consumers stopped", taskDisplayName());
    }

    private void prepareConsumerGroup() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

    }

    private void consumeLoop(String consumerName) {
        while (running.get()) {
            try {
                recoverPendingMessagesIfDue(consumerName);
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    private void recoverPendingMessagesIfDue(String consumerName) {
        long now = System.currentTimeMillis();
        long previousRecoveryAt = lastPendingRecoveryAt.get();
        if (now - previousRecoveryAt
                < AsyncTaskStreamConstants.PENDING_RECOVERY_INTERVAL_MS) {
            return;
        }
        if (!lastPendingRecoveryAt.compareAndSet(previousRecoveryAt, now)) {
            return;
        }

        StreamMessageId cursor = StreamMessageId.MIN;
        int recovered = 0;
        for (int batch = 0;
                batch < AsyncTaskStreamConstants.MAX_RECOVERY_BATCHES && running.get();
                batch++) {
            RedisService.StreamClaimBatch result = redisService.streamAutoClaimMessages(
                streamKey(),
                groupName(),
                consumerName,
                AsyncTaskStreamConstants.PENDING_MIN_IDLE_MS,
                cursor,
                AsyncTaskStreamConstants.BATCH_SIZE,
                this::processMessage
            );
            recovered += result.claimedCount();
            if (result.claimedCount() == 0 || result.nextId().equals(StreamMessageId.MIN)) {
                break;
            }
            cursor = result.nextId();
        }
        if (recovered > 0) {
            log.info("Recovered pending {} tasks: count={}, consumerName={}",
                taskDisplayName(), recovered, consumerName);
        }
    }

    void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload = parsePayload(messageId, data);
        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        String taskId = data.getOrDefault(
            AsyncTaskStreamConstants.FIELD_TASK_ID,
            streamKey() + ":" + messageId
        );
        String completedKey = "async:completed:{" + taskId + "}";
        String lockKey = "async:processing:{" + taskId + "}";

        if (redisService.exists(completedKey)) {
            ackMessage(messageId);
            log.info("Skipped completed duplicate {} task: taskId={}, messageId={}",
                taskDisplayName(), taskId, messageId);
            return;
        }
        if (!redisService.tryLock(
                lockKey,
                0,
                AsyncTaskStreamConstants.TASK_LOCK_LEASE_MS,
                TimeUnit.MILLISECONDS)) {
            log.info("Deferred duplicate in-flight {} task: taskId={}, messageId={}",
                taskDisplayName(), taskId, messageId);
            return;
        }

        log.info("Processing {} task: payload={}, taskId={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), taskId, messageId, retryCount);

        try {
            if (redisService.exists(completedKey)) {
                ackMessage(messageId);
                return;
            }
            markProcessing(payload);
            processBusiness(payload);
            markCompleted(payload);
            redisService.set(
                completedKey,
                "1",
                Duration.ofDays(AsyncTaskStreamConstants.COMPLETED_MARKER_TTL_DAYS)
            );
            ackMessage(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1, taskId);
            } else {
                String error = truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                );
                markFailed(payload, error);
                writeDeadLetter(data, taskId, error);
            }
            ackMessage(messageId);
        } finally {
            redisService.unlock(lockKey);
        }
    }

    private void writeDeadLetter(
            Map<String, String> originalData,
            String taskId,
            String error) {
        Map<String, String> deadLetter = new LinkedHashMap<>(originalData);
        deadLetter.put(AsyncTaskStreamConstants.FIELD_TASK_ID, taskId);
        deadLetter.put(AsyncTaskStreamConstants.FIELD_SOURCE_STREAM, streamKey());
        deadLetter.put(AsyncTaskStreamConstants.FIELD_ERROR, error);
        deadLetter.put(
            AsyncTaskStreamConstants.FIELD_FAILED_AT,
            OffsetDateTime.now().toString()
        );
        redisService.streamAdd(
            deadLetterStreamKey(),
            deadLetter,
            AsyncTaskStreamConstants.STREAM_MAX_LEN
        );
    }

    protected String deadLetterStreamKey() {
        if (streamKey().endsWith(":stream")) {
            return streamKey().substring(0, streamKey().length() - 7) + ":dead-letter";
        }
        return streamKey() + ":dead-letter";
    }

    protected int parseRetryCount(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    /**
     * Number of independent Redis Stream consumer loops for this task type.
     */
    protected int consumerConcurrency() {
        return AsyncTaskStreamConstants.CONSUMER_CONCURRENCY;
    }

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void markProcessing(T payload);

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount, String taskId);
}
