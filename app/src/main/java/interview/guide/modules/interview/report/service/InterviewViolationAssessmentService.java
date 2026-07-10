package interview.guide.modules.interview.report.service;

import interview.guide.modules.interview.report.model.InterviewViolationConclusion;
import interview.guide.modules.interview.report.model.ViolationVerdict;
import interview.guide.modules.interview.vision.model.InterviewVisionEventDTO;
import interview.guide.modules.interview.vision.model.VisionEventType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class InterviewViolationAssessmentService {

  private static final Set<VisionEventType> SCREEN_SWITCH_EVENTS = Set.of(
      VisionEventType.TAB_HIDDEN,
      VisionEventType.WINDOW_BLUR,
      VisionEventType.FULLSCREEN_EXIT);
  private static final Set<VisionEventType> VISUAL_ANOMALY_EVENTS = Set.of(
      VisionEventType.FACE_MISSING,
      VisionEventType.MULTIPLE_FACES,
      VisionEventType.CAMERA_INTERRUPTED,
      VisionEventType.LOW_LIGHT,
      VisionEventType.IDENTITY_MISMATCH);

  private final InterviewViolationProperties properties;

  public InterviewViolationAssessmentService(InterviewViolationProperties properties) {
    this.properties = properties;
  }

  public InterviewViolationConclusion assess(List<InterviewVisionEventDTO> events) {
    List<InterviewVisionEventDTO> safeEvents = events == null ? List.of() : events;
    int screenSwitchCount = countScreenSwitches(safeEvents);
    List<InterviewVisionEventDTO> anomalyEpisodes = safeEvents.stream()
        .filter(this::isVisualAnomalyEpisode)
        .toList();
    double effectiveCount = anomalyEpisodes.stream()
        .mapToDouble(this::severityWeight)
        .sum();
    long weightedDurationMs = Math.round(anomalyEpisodes.stream()
        .mapToDouble(event -> Math.max(0, event.durationMs() == null
            ? 0
            : event.durationMs()) * severityWeight(event))
        .sum());

    boolean directRuleTriggered = screenSwitchCount >= properties.getScreenSwitchThreshold();
    double countRisk = normalize(
        effectiveCount,
        properties.getMinAnomalyCount(),
        properties.getMaxAnomalyCount());
    double durationRisk = clamp((double) weightedDurationMs
        / properties.getAnomalyDurationThreshold().toMillis());
    double calculatedRisk = clamp(
        properties.getCountWeight() * countRisk
            + properties.getDurationWeight() * durationRisk);
    boolean visualRuleTriggered = effectiveCount >= properties.getMinAnomalyCount()
        && calculatedRisk >= properties.getRiskThreshold();
    boolean violated = directRuleTriggered || visualRuleTriggered;
    int riskScore = directRuleTriggered ? 100 : (int) Math.round(calculatedRisk * 100);

    List<String> reasons = buildReasons(
        screenSwitchCount,
        anomalyEpisodes.size(),
        effectiveCount,
        weightedDurationMs,
        riskScore,
        directRuleTriggered,
        visualRuleTriggered);
    return new InterviewViolationConclusion(
        violated ? ViolationVerdict.VIOLATION : ViolationVerdict.NORMAL,
        violated,
        directRuleTriggered,
        screenSwitchCount,
        anomalyEpisodes.size(),
        round(effectiveCount),
        weightedDurationMs,
        riskScore,
        reasons);
  }

  private int countScreenSwitches(List<InterviewVisionEventDTO> events) {
    List<LocalDateTime> switchTimes = events.stream()
        .filter(event -> eventTypes(event).stream().anyMatch(SCREEN_SWITCH_EVENTS::contains))
        .map(InterviewVisionEventDTO::occurredAt)
        .filter(time -> time != null)
        .sorted(Comparator.naturalOrder())
        .toList();
    int count = 0;
    LocalDateTime lastCountedAt = null;
    for (LocalDateTime switchTime : switchTimes) {
      if (lastCountedAt == null
          || Duration.between(lastCountedAt, switchTime)
              .compareTo(properties.getScreenSwitchMergeWindow()) > 0) {
        count++;
        lastCountedAt = switchTime;
      }
    }
    return count;
  }

  private boolean isVisualAnomalyEpisode(InterviewVisionEventDTO event) {
    return eventTypes(event).stream().anyMatch(VISUAL_ANOMALY_EVENTS::contains);
  }

  private double severityWeight(InterviewVisionEventDTO event) {
    return eventTypes(event).stream()
        .filter(VISUAL_ANOMALY_EVENTS::contains)
        .mapToDouble(this::severityWeight)
        .max()
        .orElse(0);
  }

  private double severityWeight(VisionEventType eventType) {
    return switch (eventType) {
      case LOW_LIGHT -> properties.getLowLightWeight();
      case FACE_MISSING -> properties.getFaceMissingWeight();
      case MULTIPLE_FACES, CAMERA_INTERRUPTED, IDENTITY_MISMATCH ->
          properties.getSevereEventWeight();
      default -> 0;
    };
  }

  private List<VisionEventType> eventTypes(InterviewVisionEventDTO event) {
    return event.eventTypes() == null || event.eventTypes().isEmpty()
        ? List.of(event.eventType())
        : event.eventTypes();
  }

  private List<String> buildReasons(
      int screenSwitchCount,
      int anomalyEpisodeCount,
      double effectiveCount,
      long weightedDurationMs,
      int riskScore,
      boolean directRuleTriggered,
      boolean visualRuleTriggered) {
    List<String> reasons = new ArrayList<>();
    if (directRuleTriggered) {
      reasons.add("切屏达到 " + screenSwitchCount + " 次，触发直接违规规则");
    }
    if (visualRuleTriggered) {
      reasons.add("视觉异常风险达到 " + riskScore + " 分，超过违规阈值");
    }
    reasons.add("视觉异常时段 " + anomalyEpisodeCount
        + " 个，有效异常次数 " + round(effectiveCount)
        + "，加权持续 " + formatSeconds(weightedDurationMs) + " 秒");
    if (!directRuleTriggered && !visualRuleTriggered) {
      reasons.add("切屏次数和视觉异常风险均未达到违规阈值");
    }
    return reasons;
  }

  private double normalize(double value, double minimum, double maximum) {
    if (maximum <= minimum) return value >= maximum ? 1 : 0;
    if (value < minimum) return 0;
    return clamp((value - minimum + 1) / (maximum - minimum + 1));
  }

  private double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }

  private double round(double value) {
    return Math.round(value * 100D) / 100D;
  }

  private double formatSeconds(long durationMs) {
    return Math.round(durationMs / 100D) / 10D;
  }
}
