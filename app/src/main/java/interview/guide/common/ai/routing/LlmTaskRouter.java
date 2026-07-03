package interview.guide.common.ai.routing;

import interview.guide.common.config.LlmRouterProperties;
import interview.guide.common.config.LlmRouterProperties.ProviderProfile;
import interview.guide.common.config.LlmRouterProperties.TaskPolicy;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmTaskRouter {

  private static final double PREFERRED_PROVIDER_BONUS = 0.05;

  private final LlmRouterProperties properties;
  private final MeterRegistry meterRegistry;
  private final Map<String, ProviderRuntimeState> states = new ConcurrentHashMap<>();
  private final Map<LlmTaskType, AtomicInteger> taskInFlight = new ConcurrentHashMap<>();

  public LlmTaskRouter(
      LlmRouterProperties properties,
      @Autowired(required = false) MeterRegistry meterRegistry) {
    this.properties = properties;
    this.meterRegistry = meterRegistry;
  }

  public <T> T execute(
      LlmTaskType taskType,
      String preferredProvider,
      ProviderOperation<T> operation) {
    if (!properties.isEnabled()) {
      return invokeUnchecked(preferredProvider, operation);
    }
    TaskPolicy policy = policyFor(taskType);
    AtomicInteger taskCounter = taskInFlight.computeIfAbsent(
        taskType,
        ignored -> new AtomicInteger());
    if (taskCounter.incrementAndGet() > Math.max(1, policy.getMaxConcurrency())) {
      taskCounter.decrementAndGet();
      throw new BusinessException(
          ErrorCode.AI_RATE_LIMIT_EXCEEDED,
          taskType + " 任务并发已达到隔离上限");
    }
    try {
      return executeWithPolicy(taskType, preferredProvider, operation, policy);
    } finally {
      taskCounter.updateAndGet(value -> Math.max(0, value - 1));
    }
  }

  private <T> T executeWithPolicy(
      LlmTaskType taskType,
      String preferredProvider,
      ProviderOperation<T> operation,
      TaskPolicy policy) {
    long startedAt = System.nanoTime();
    Set<String> excluded = new HashSet<>();
    RuntimeException lastError = null;

    for (int attempt = 1; attempt <= Math.max(1, policy.getMaxProviderAttempts()); attempt++) {
      long elapsedNanos = System.nanoTime() - startedAt;
      Duration remainingBudget = policy.getTotalBudget().minusNanos(elapsedNanos);
      if (remainingBudget.isNegative() || remainingBudget.isZero()) break;
      if (attempt > 1 && remainingBudget.compareTo(policy.getMinimumRetryBudget()) < 0) break;

      Selection selection = select(taskType, preferredProvider, policy, excluded, remainingBudget);
      if (selection == null) break;
      String providerId = selection.providerId();
      ProviderRuntimeState state = selection.state();
      long attemptStartedAt = System.nanoTime();
      try {
        T result = operation.execute(providerId);
        long latencyNanos = System.nanoTime() - attemptStartedAt;
        state.complete(true, latencyNanos, properties);
        record(taskType, providerId, "success", latencyNanos);
        return result;
      } catch (Exception e) {
        long latencyNanos = System.nanoTime() - attemptStartedAt;
        state.complete(false, latencyNanos, properties);
        record(taskType, providerId, "failure", latencyNanos);
        excluded.add(providerId);
        lastError = asRuntimeException(e);
        log.warn(
        "LLM provider attempt failed: task={}, provider={}, attempt={}, "
          + "remainingMs={}, error={}",
            taskType,
            providerId,
            attempt,
            Math.max(0L, remainingBudget.toMillis()),
            e.getMessage());
      }
    }

    if (lastError != null) throw lastError;
    throw new BusinessException(
        ErrorCode.AI_SERVICE_UNAVAILABLE,
        "当前没有可用于 " + taskType + " 的 LLM Provider");
  }

  public Map<String, ProviderStateSnapshot> snapshots() {
    Map<String, ProviderStateSnapshot> snapshots = new LinkedHashMap<>();
    properties.getProviders().forEach((providerId, profile) -> {
      ProviderRuntimeState state = stateFor(providerId);
      snapshots.put(providerId, state.snapshot(providerId, profile));
    });
    return Map.copyOf(snapshots);
  }

  private Selection select(
      LlmTaskType taskType,
      String preferredProvider,
      TaskPolicy policy,
      Set<String> excluded,
      Duration remainingBudget) {
    List<ScoredProvider> candidates = new ArrayList<>();
    properties.getProviders().forEach((providerId, profile) -> {
      if (!profile.isEnabled()
          || excluded.contains(providerId)
          || !supports(profile, taskType)) {
        return;
      }
      ProviderRuntimeState state = stateFor(providerId);
      double score = score(providerId, preferredProvider, profile, state, policy);
      if (state.predictedLatencyNanos(policy) <= remainingBudget.toNanos()) {
        candidates.add(new ScoredProvider(providerId, profile, state, score));
      }
    });

    boolean hasPreferredLevel = candidates.stream()
        .anyMatch(candidate -> candidate.profile().getLevel() == policy.getPreferredLevel());
    if (hasPreferredLevel) {
      candidates.removeIf(
          candidate -> candidate.profile().getLevel() != policy.getPreferredLevel());
    }

    if (candidates.isEmpty() && preferredProvider != null && !preferredProvider.isBlank()
        && !excluded.contains(preferredProvider)) {
      ProviderProfile compatibilityProfile = new ProviderProfile();
      compatibilityProfile.setMaxConcurrency(20);
      candidates.add(new ScoredProvider(
          preferredProvider,
          compatibilityProfile,
          stateFor(preferredProvider),
          0.0));
    }

    candidates.sort(Comparator.comparingDouble(ScoredProvider::score).reversed());
    for (ScoredProvider candidate : candidates) {
      if (candidate.state().tryAcquire(candidate.profile(), properties)) {
        return new Selection(candidate.providerId(), candidate.state());
      }
    }
    return null;
  }

  private double score(
      String providerId,
      String preferredProvider,
      ProviderProfile profile,
      ProviderRuntimeState state,
      TaskPolicy policy) {
    double latencyScore = 1.0 / (1.0
        + state.latencyEwmaNanos(policy) / Math.max(1.0, policy.getTargetLatency().toNanos()));
    double capacityScore = 1.0 - Math.min(
        1.0,
        (double) state.inFlight() / Math.max(1, profile.getMaxConcurrency()));
    double healthScore = 1.0 - state.errorRate();
    double costScore = 1.0 / Math.max(1.0, profile.getCostWeight());
    double levelScore = profile.getLevel() == policy.getPreferredLevel() ? 0.05 : 0.0;
    double preferenceScore = providerId.equals(preferredProvider) ? PREFERRED_PROVIDER_BONUS : 0.0;
    return policy.getLatencyWeight() * latencyScore
        + policy.getCapacityWeight() * capacityScore
        + policy.getHealthWeight() * healthScore
        + policy.getCostWeight() * costScore
        + levelScore
        + preferenceScore;
  }

  private boolean supports(ProviderProfile profile, LlmTaskType taskType) {
    return profile.getSupportedTasks().isEmpty()
        || profile.getSupportedTasks().contains(taskType)
        || profile.getSupportedTasks().contains(LlmTaskType.GENERAL);
  }

  private TaskPolicy policyFor(LlmTaskType taskType) {
    TaskPolicy configured = properties.getTasks().get(taskType);
    return configured == null ? new TaskPolicy() : configured;
  }

  private ProviderRuntimeState stateFor(String providerId) {
    return states.computeIfAbsent(providerId, ignored -> new ProviderRuntimeState());
  }

  private <T> T invokeUnchecked(String providerId, ProviderOperation<T> operation) {
    try {
      return operation.execute(providerId);
    } catch (Exception e) {
      throw asRuntimeException(e);
    }
  }

  private RuntimeException asRuntimeException(Exception error) {
    if (error instanceof RuntimeException runtimeException) return runtimeException;
    return new BusinessException(ErrorCode.AI_SERVICE_ERROR, "LLM Provider 调用失败", error);
  }

  private void record(
      LlmTaskType taskType,
      String providerId,
      String outcome,
      long latencyNanos) {
    if (meterRegistry == null) return;
    Tags tags = Tags.of(
        "task", taskType.name().toLowerCase(),
        "provider", providerId,
        "outcome", outcome);
    meterRegistry.counter("app.ai.router.calls", tags).increment();
    meterRegistry.timer("app.ai.router.latency", tags).record(
        latencyNanos,
        java.util.concurrent.TimeUnit.NANOSECONDS);
  }

  @FunctionalInterface
  public interface ProviderOperation<T> {
    T execute(String providerId) throws Exception;
  }

  public record ProviderStateSnapshot(
      String providerId,
      CircuitState circuitState,
      double latencyEwmaMillis,
      double errorRate,
      int inFlight,
      int maxConcurrency,
      int consecutiveFailures,
      long lastSuccessEpochMillis) {
  }

  private record Selection(String providerId, ProviderRuntimeState state) {
  }

  private record ScoredProvider(
      String providerId,
      ProviderProfile profile,
      ProviderRuntimeState state,
      double score) {
  }

  private static final class ProviderRuntimeState {
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Deque<Boolean> recentResults = new ArrayDeque<>();
    private CircuitState circuitState = CircuitState.CLOSED;
    private double latencyEwmaNanos;
    private int consecutiveFailures;
    private long openedAtNanos;
    private int halfOpenCalls;
    private int halfOpenSuccesses;
    private long lastSuccessEpochMillis;

    synchronized boolean tryAcquire(
        ProviderProfile profile,
        LlmRouterProperties properties) {
      long now = System.nanoTime();
      if (circuitState == CircuitState.OPEN) {
        if (now - openedAtNanos < properties.getOpenCooldown().toNanos()) return false;
        circuitState = CircuitState.HALF_OPEN;
        halfOpenCalls = 0;
        halfOpenSuccesses = 0;
      }
      if (circuitState == CircuitState.HALF_OPEN
          && halfOpenCalls >= properties.getHalfOpenMaxCalls()) {
        return false;
      }
      if (inFlight.get() >= Math.max(1, profile.getMaxConcurrency())) return false;
      inFlight.incrementAndGet();
      if (circuitState == CircuitState.HALF_OPEN) halfOpenCalls++;
      return true;
    }

    synchronized void complete(
        boolean success,
        long latencyNanos,
        LlmRouterProperties properties) {
      inFlight.updateAndGet(value -> Math.max(0, value - 1));
      double alpha = Math.max(0.01, Math.min(1.0, properties.getLatencyEwmaAlpha()));
      latencyEwmaNanos = latencyEwmaNanos == 0.0
          ? latencyNanos
          : alpha * latencyNanos + (1.0 - alpha) * latencyEwmaNanos;
      recentResults.addLast(success);
      while (recentResults.size() > properties.getErrorWindowSize()) {
        recentResults.removeFirst();
      }

      if (success) {
        consecutiveFailures = 0;
        lastSuccessEpochMillis = System.currentTimeMillis();
        if (circuitState == CircuitState.HALF_OPEN) {
          halfOpenSuccesses++;
          if (halfOpenSuccesses >= properties.getHalfOpenSuccessThreshold()) {
            closeCircuit();
          }
        }
        return;
      }

      consecutiveFailures++;
      if (circuitState == CircuitState.HALF_OPEN
          || consecutiveFailures >= properties.getOpenConsecutiveFailures()
          || recentResults.size() >= properties.getMinimumErrorSamples()
              && errorRate() >= properties.getOpenErrorRate()) {
        openCircuit();
      }
    }

    synchronized long predictedLatencyNanos(TaskPolicy policy) {
      return Math.round(latencyEwmaNanos(policy));
    }

    synchronized double latencyEwmaNanos(TaskPolicy policy) {
      return latencyEwmaNanos == 0.0
          ? policy.getTargetLatency().toNanos()
          : latencyEwmaNanos;
    }

    synchronized double errorRate() {
      if (recentResults.isEmpty()) return 0.0;
      long failures = recentResults.stream().filter(success -> !success).count();
      return (double) failures / recentResults.size();
    }

    int inFlight() {
      return inFlight.get();
    }

    synchronized ProviderStateSnapshot snapshot(
        String providerId,
        ProviderProfile profile) {
      return new ProviderStateSnapshot(
          providerId,
          circuitState,
          latencyEwmaNanos / 1_000_000.0,
          errorRate(),
          inFlight.get(),
          profile.getMaxConcurrency(),
          consecutiveFailures,
          lastSuccessEpochMillis);
    }

    private void openCircuit() {
      circuitState = CircuitState.OPEN;
      openedAtNanos = System.nanoTime();
      halfOpenCalls = 0;
      halfOpenSuccesses = 0;
    }

    private void closeCircuit() {
      circuitState = CircuitState.CLOSED;
      consecutiveFailures = 0;
      halfOpenCalls = 0;
      halfOpenSuccesses = 0;
      recentResults.clear();
    }
  }
}
