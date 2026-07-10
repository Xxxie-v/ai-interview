package interview.guide.modules.interview.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.report.model.ViolationVerdict;
import interview.guide.modules.interview.vision.model.InterviewVisionEventDTO;
import interview.guide.modules.interview.vision.model.VisionEventType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("面试违规结论判定")
class InterviewViolationAssessmentServiceTest {

  private InterviewViolationAssessmentService service;
  private LocalDateTime startedAt;

  @BeforeEach
  void setUp() {
    service = new InterviewViolationAssessmentService(
        new InterviewViolationProperties());
    startedAt = LocalDateTime.of(2026, 8, 18, 10, 0);
  }

  @Test
  @DisplayName("切屏达到三次后直接判定违规")
  void screenSwitchThresholdTriggersDirectViolation() {
    var conclusion = service.assess(List.of(
        event(VisionEventType.TAB_HIDDEN, 0, 0),
        event(VisionEventType.WINDOW_BLUR, 5, 0),
        event(VisionEventType.FULLSCREEN_EXIT, 10, 0)));

    assertThat(conclusion.verdict()).isEqualTo(ViolationVerdict.VIOLATION);
    assertThat(conclusion.directRuleTriggered()).isTrue();
    assertThat(conclusion.screenSwitchCount()).isEqualTo(3);
    assertThat(conclusion.riskScore()).isEqualTo(100);
  }

  @Test
  @DisplayName("两秒内同时产生的切屏信号只计算一次")
  void mergesDuplicateScreenSignalsInsideWindow() {
    var conclusion = service.assess(List.of(
        event(VisionEventType.TAB_HIDDEN, 0, 0),
        event(VisionEventType.WINDOW_BLUR, 1, 0),
        event(VisionEventType.FULLSCREEN_EXIT, 2, 0)));

    assertThat(conclusion.violated()).isFalse();
    assertThat(conclusion.screenSwitchCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("三次严重异常且加权时长达到三十秒时判定违规")
  void countAndDurationFunctionTriggersViolation() {
    var conclusion = service.assess(List.of(
        event(VisionEventType.MULTIPLE_FACES, 0, 10_000),
        event(VisionEventType.IDENTITY_MISMATCH, 20, 10_000),
        event(VisionEventType.CAMERA_INTERRUPTED, 40, 10_000)));

    assertThat(conclusion.verdict()).isEqualTo(ViolationVerdict.VIOLATION);
    assertThat(conclusion.directRuleTriggered()).isFalse();
    assertThat(conclusion.anomalyEpisodeCount()).isEqualTo(3);
    assertThat(conclusion.effectiveAnomalyCount()).isEqualTo(3);
    assertThat(conclusion.weightedAnomalyDurationMs()).isEqualTo(30_000);
    assertThat(conclusion.riskScore()).isEqualTo(60);
  }

  @Test
  @DisplayName("五次严重异常即使持续较短也达到次数风险上限")
  void fiveSevereEpisodesReachCountRiskLimit() {
    var conclusion = service.assess(List.of(
        event(VisionEventType.MULTIPLE_FACES, 0, 0),
        event(VisionEventType.MULTIPLE_FACES, 5, 0),
        event(VisionEventType.MULTIPLE_FACES, 10, 0),
        event(VisionEventType.MULTIPLE_FACES, 15, 0),
        event(VisionEventType.MULTIPLE_FACES, 20, 0)));

    assertThat(conclusion.violated()).isTrue();
    assertThat(conclusion.riskScore()).isEqualTo(60);
  }

  @Test
  @DisplayName("低光异常按低权重计算不会轻易判定违规")
  void lowLightEpisodesUseReducedWeight() {
    var conclusion = service.assess(List.of(
        event(VisionEventType.LOW_LIGHT, 0, 30_000),
        event(VisionEventType.LOW_LIGHT, 40, 30_000),
        event(VisionEventType.LOW_LIGHT, 80, 30_000)));

    assertThat(conclusion.violated()).isFalse();
    assertThat(conclusion.anomalyEpisodeCount()).isEqualTo(3);
    assertThat(conclusion.effectiveAnomalyCount()).isEqualTo(0.75);
  }

  private InterviewVisionEventDTO event(
      VisionEventType eventType,
      int offsetSeconds,
      long durationMs) {
    LocalDateTime occurredAt = startedAt.plusSeconds(offsetSeconds);
    return new InterviewVisionEventDTO(
        (long) offsetSeconds + 1,
        "session-1",
        eventType,
        List.of(eventType),
        occurredAt,
        occurredAt.plusNanos(durationMs * 1_000_000),
        durationMs,
        offsetSeconds * 1_000L,
        null,
        true,
        false);
  }
}
