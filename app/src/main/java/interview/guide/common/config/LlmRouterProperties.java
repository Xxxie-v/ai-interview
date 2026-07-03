package interview.guide.common.config;

import interview.guide.common.ai.routing.LlmTaskType;
import interview.guide.common.ai.routing.ModelLevel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.router")
public class LlmRouterProperties {

  private boolean enabled = true;
  private double latencyEwmaAlpha = 0.2;
  private int errorWindowSize = 50;
  private int minimumErrorSamples = 20;
  private double openErrorRate = 0.3;
  private int openConsecutiveFailures = 5;
  private Duration openCooldown = Duration.ofSeconds(30);
  private int halfOpenMaxCalls = 2;
  private int halfOpenSuccessThreshold = 2;
  private Map<String, ProviderProfile> providers = new LinkedHashMap<>();
  private Map<LlmTaskType, TaskPolicy> tasks = new LinkedHashMap<>();

  @Data
  public static class ProviderProfile {
    private boolean enabled = true;
    private Set<LlmTaskType> supportedTasks = new LinkedHashSet<>();
    private int maxConcurrency = 20;
    private ModelLevel level = ModelLevel.FAST;
    private double costWeight = 1.0;
  }

  @Data
  public static class TaskPolicy {
    private ModelLevel preferredLevel = ModelLevel.FAST;
    private Duration totalBudget = Duration.ofSeconds(10);
    private Duration targetLatency = Duration.ofSeconds(2);
    private Duration minimumRetryBudget = Duration.ofMillis(300);
    private int maxProviderAttempts = 2;
    private int maxConcurrency = 20;
    private double latencyWeight = 0.5;
    private double capacityWeight = 0.3;
    private double healthWeight = 0.2;
    private double costWeight = 0.0;
  }
}
