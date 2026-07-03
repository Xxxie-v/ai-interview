package interview.guide.common.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.config.LlmRouterProperties;
import interview.guide.common.config.LlmRouterProperties.ProviderProfile;
import interview.guide.common.config.LlmRouterProperties.TaskPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("单实例 LLM 任务路由")
class LlmTaskRouterTest {

  @Nested
  @DisplayName("任务感知与故障切换")
  class Routing {

    @Test
    @DisplayName("当前 Provider 失败后切换到其他 Provider 而不是重试原 Provider")
    void switchesProviderAfterFailure() {
      LlmRouterProperties properties = properties();
      properties.setProviders(new LinkedHashMap<>());
      properties.getProviders().put(
          "fast-a",
          profile(ModelLevel.FAST, Set.of(LlmTaskType.FOLLOW_UP)));
      properties.getProviders().put(
          "fast-b",
          profile(ModelLevel.FAST, Set.of(LlmTaskType.FOLLOW_UP)));
      LlmTaskRouter router = new LlmTaskRouter(properties, null);
      List<String> attempts = new ArrayList<>();

      String result = router.execute(LlmTaskType.FOLLOW_UP, "fast-a", provider -> {
        attempts.add(provider);
        if (provider.equals("fast-a")) throw new IllegalStateException("timeout");
        return "ok";
      });

      assertThat(result).isEqualTo("ok");
      assertThat(attempts).containsExactly("fast-a", "fast-b");
      assertThat(router.snapshots().get("fast-a").errorRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("报告任务只进入支持 REPORT 的 Provider 池")
    void filtersProvidersByTask() {
      LlmRouterProperties properties = properties();
      properties.setProviders(new LinkedHashMap<>());
      properties.getProviders().put(
          "follow-up-only",
          profile(ModelLevel.FAST, Set.of(LlmTaskType.FOLLOW_UP)));
      properties.getProviders().put(
          "report-provider",
          profile(ModelLevel.STRONG, Set.of(LlmTaskType.REPORT)));
      LlmTaskRouter router = new LlmTaskRouter(properties, null);

      String selected = router.execute(
          LlmTaskType.REPORT,
          "follow-up-only",
          provider -> provider);

      assertThat(selected).isEqualTo("report-provider");
    }

    @Test
    @DisplayName("实时预算耗尽后不再尝试第二个 Provider")
    void stopsFailoverWhenRetryBudgetIsExhausted() {
      LlmRouterProperties properties = properties();
      TaskPolicy policy = properties.getTasks().get(LlmTaskType.FOLLOW_UP);
      policy.setTotalBudget(Duration.ofMillis(20));
      policy.setTargetLatency(Duration.ofMillis(1));
      policy.setMinimumRetryBudget(Duration.ofMillis(10));
      properties.setProviders(new LinkedHashMap<>());
      properties.getProviders().put(
          "fast-a",
          profile(ModelLevel.FAST, Set.of(LlmTaskType.FOLLOW_UP)));
      properties.getProviders().put(
          "fast-b",
          profile(ModelLevel.FAST, Set.of(LlmTaskType.FOLLOW_UP)));
      LlmTaskRouter router = new LlmTaskRouter(properties, null);
      List<String> attempts = new ArrayList<>();

      assertThatThrownBy(() -> router.execute(
          LlmTaskType.FOLLOW_UP,
          "fast-a",
          provider -> {
            attempts.add(provider);
            Thread.sleep(25);
            throw new IllegalStateException("timeout");
          }))
          .isInstanceOf(IllegalStateException.class);

      assertThat(attempts).containsExactly("fast-a");
    }

    @Test
    @DisplayName("不同任务使用独立并发隔离，追问池满时快速拒绝新的追问")
    void isolatesConcurrencyByTask() throws Exception {
      LlmRouterProperties properties = properties();
      properties.getTasks().get(LlmTaskType.FOLLOW_UP).setMaxConcurrency(1);
      properties.setProviders(MapBuilder.of(
          "fast-a",
          profile(ModelLevel.FAST, Set.of(LlmTaskType.FOLLOW_UP))));
      LlmTaskRouter router = new LlmTaskRouter(properties, null);
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      FutureTask<String> firstCall = new FutureTask<>(() -> router.execute(
          LlmTaskType.FOLLOW_UP,
          "fast-a",
          provider -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "done";
          }));
      Thread thread = Thread.ofVirtual().start(firstCall);
      assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> router.execute(
          LlmTaskType.FOLLOW_UP,
          "fast-a",
          provider -> "second"))
          .isInstanceOf(interview.guide.common.exception.BusinessException.class)
          .hasMessageContaining("并发已达到隔离上限");

      release.countDown();
      assertThat(firstCall.get(1, TimeUnit.SECONDS)).isEqualTo("done");
      thread.join();
    }
  }

  @Nested
  @DisplayName("熔断与恢复")
  class CircuitBreaker {

    @Test
    @DisplayName("连续失败后 OPEN，冷却后通过 HALF_OPEN 探测恢复")
    void opensAndRecoversCircuit() {
      LlmRouterProperties properties = properties();
      properties.setOpenConsecutiveFailures(2);
      properties.setOpenCooldown(Duration.ZERO);
      properties.setHalfOpenSuccessThreshold(1);
      TaskPolicy policy = properties.getTasks().get(LlmTaskType.FOLLOW_UP);
      policy.setMaxProviderAttempts(1);
      properties.setProviders(MapBuilder.of(
          "fast-a",
          profile(ModelLevel.FAST, Set.of(LlmTaskType.FOLLOW_UP))));
      LlmTaskRouter router = new LlmTaskRouter(properties, null);

      for (int attempt = 0; attempt < 2; attempt++) {
        assertThatThrownBy(() -> router.execute(
            LlmTaskType.FOLLOW_UP,
            "fast-a",
            provider -> {
              throw new IllegalStateException("provider down");
            })).isInstanceOf(IllegalStateException.class);
      }
      assertThat(router.snapshots().get("fast-a").circuitState())
          .isEqualTo(CircuitState.OPEN);

      String result = router.execute(
          LlmTaskType.FOLLOW_UP,
          "fast-a",
          provider -> "recovered");

      assertThat(result).isEqualTo("recovered");
      assertThat(router.snapshots().get("fast-a").circuitState())
          .isEqualTo(CircuitState.CLOSED);
    }
  }

  private LlmRouterProperties properties() {
    LlmRouterProperties properties = new LlmRouterProperties();
    TaskPolicy followUp = new TaskPolicy();
    followUp.setPreferredLevel(ModelLevel.FAST);
    followUp.setTotalBudget(Duration.ofSeconds(2));
    followUp.setTargetLatency(Duration.ofMillis(100));
    followUp.setMinimumRetryBudget(Duration.ofMillis(10));
    followUp.setMaxProviderAttempts(2);
    properties.getTasks().put(LlmTaskType.FOLLOW_UP, followUp);
    TaskPolicy report = new TaskPolicy();
    report.setPreferredLevel(ModelLevel.STRONG);
    properties.getTasks().put(LlmTaskType.REPORT, report);
    return properties;
  }

  private ProviderProfile profile(ModelLevel level, Set<LlmTaskType> tasks) {
    ProviderProfile profile = new ProviderProfile();
    profile.setLevel(level);
    profile.setSupportedTasks(tasks);
    profile.setMaxConcurrency(10);
    return profile;
  }

  private static final class MapBuilder {
    private MapBuilder() {
    }

    static LinkedHashMap<String, ProviderProfile> of(
        String providerId,
        ProviderProfile profile) {
      LinkedHashMap<String, ProviderProfile> result = new LinkedHashMap<>();
      result.put(providerId, profile);
      return result;
    }
  }
}
