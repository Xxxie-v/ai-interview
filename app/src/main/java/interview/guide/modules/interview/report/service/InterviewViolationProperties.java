package interview.guide.modules.interview.report.service;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview.violation")
public class InterviewViolationProperties {

  private int screenSwitchThreshold = 3;
  private Duration screenSwitchMergeWindow = Duration.ofSeconds(2);
  private double minAnomalyCount = 3;
  private double maxAnomalyCount = 5;
  private Duration anomalyDurationThreshold = Duration.ofSeconds(30);
  private double countWeight = 0.6;
  private double durationWeight = 0.4;
  private double riskThreshold = 0.6;
  private double severeEventWeight = 1.0;
  private double faceMissingWeight = 0.75;
  private double lowLightWeight = 0.25;
}
