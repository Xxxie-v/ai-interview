package interview.guide.modules.interview.report.model;

import java.util.List;

public record InterviewViolationConclusion(
    ViolationVerdict verdict,
    boolean violated,
    boolean directRuleTriggered,
    int screenSwitchCount,
    int anomalyEpisodeCount,
    double effectiveAnomalyCount,
    long weightedAnomalyDurationMs,
    int riskScore,
    List<String> reasons) {

  public InterviewViolationConclusion {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
