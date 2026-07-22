package interview.guide.modules.interview.vision.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.vision.model.VisionEventType;
import interview.guide.modules.interview.vision.model.VisionMonitoringState;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("视频异常状态跟踪器")
class VisionAnomalyTrackerTest {

  private static final String SESSION_ID = "session-1";

  private VisionAnomalyTracker tracker;
  private LocalDateTime startedAt;

  @BeforeEach
  void setUp() {
    InterviewVisionProperties properties = new InterviewVisionProperties();
    properties.setFrameInterval(Duration.ofSeconds(5));
    properties.setSuspectFrameInterval(Duration.ofSeconds(1));
    properties.setConfirmationFrames(3);
    properties.setFaceMissingMinDuration(Duration.ofSeconds(2));
    properties.setMultipleFacesMinDuration(Duration.ofSeconds(1));
    properties.setRecoveryWindow(Duration.ofSeconds(2));
    tracker = new VisionAnomalyTracker(properties);
    startedAt = LocalDateTime.of(2026, 8, 18, 10, 0);
  }

  @Test
  @DisplayName("首帧异常只进入疑似态并提高采样频率")
  void entersSuspectStateAfterFirstCandidate() {
    VisionAnomalyTracker.Decision decision = track(
        VisionEventType.FACE_MISSING,
        Duration.ZERO);

    assertThat(decision.monitoringState()).isEqualTo(VisionMonitoringState.SUSPECT);
    assertThat(decision.recommendedIntervalMs()).isEqualTo(1_000);
    assertThat(decision.activeEvents()).isEmpty();
    assertThat(decision.newlyConfirmedEvents()).isEmpty();
  }

  @Test
  @DisplayName("连续次数和持续时间同时满足后才确认异常")
  void confirmsOnlyAfterCountAndDurationThresholds() {
    track(VisionEventType.FACE_MISSING, Duration.ZERO);
    track(VisionEventType.FACE_MISSING, Duration.ofSeconds(1));
    VisionAnomalyTracker.Decision decision = track(
        VisionEventType.FACE_MISSING,
        Duration.ofSeconds(2));

    assertThat(decision.monitoringState()).isEqualTo(VisionMonitoringState.CONFIRMED);
    assertThat(decision.activeEvents()).containsExactly(VisionEventType.FACE_MISSING);
    assertThat(decision.newlyConfirmedEvents()).singleElement().satisfies(event -> {
      assertThat(event.eventType()).isEqualTo(VisionEventType.FACE_MISSING);
      assertThat(event.durationMs()).isEqualTo(2_000);
      assertThat(event.videoOffsetMs()).isEqualTo(0);
    });
  }

  @Test
  @DisplayName("单帧误检恢复后返回正常态且不产生事件")
  void resetsUnconfirmedCandidateImmediately() {
    track(VisionEventType.MULTIPLE_FACES, Duration.ZERO);
    VisionAnomalyTracker.Decision decision = tracker.track(
        SESSION_ID,
        List.of(),
        startedAt.plusSeconds(1),
        1_000L);

    assertThat(decision.monitoringState()).isEqualTo(VisionMonitoringState.NORMAL);
    assertThat(decision.recommendedIntervalMs()).isEqualTo(5_000);
    assertThat(decision.newlyConfirmedEvents()).isEmpty();
  }

  @Test
  @DisplayName("确认异常在恢复窗口内保持高频并在窗口结束后恢复")
  void keepsFastSamplingDuringRecoveryWindow() {
    track(VisionEventType.MULTIPLE_FACES, Duration.ZERO);
    track(VisionEventType.MULTIPLE_FACES, Duration.ofSeconds(1));
    track(VisionEventType.MULTIPLE_FACES, Duration.ofSeconds(2));

    VisionAnomalyTracker.Decision recovering = tracker.track(
        SESSION_ID,
        List.of(),
        startedAt.plusSeconds(3),
        3_000L);
    VisionAnomalyTracker.Decision recovered = tracker.track(
        SESSION_ID,
        List.of(),
        startedAt.plusSeconds(5),
        5_000L);

    assertThat(recovering.monitoringState()).isEqualTo(VisionMonitoringState.CONFIRMED);
    assertThat(recovering.recommendedIntervalMs()).isEqualTo(1_000);
    assertThat(recovered.monitoringState()).isEqualTo(VisionMonitoringState.NORMAL);
    assertThat(recovered.recommendedIntervalMs()).isEqualTo(5_000);
  }

  @Test
  @DisplayName("摄像头中断作为硬信号立即确认")
  void confirmsCameraInterruptionImmediately() {
    VisionAnomalyTracker.Decision decision = track(
        VisionEventType.CAMERA_INTERRUPTED,
        Duration.ZERO);

    assertThat(decision.monitoringState()).isEqualTo(VisionMonitoringState.CONFIRMED);
    assertThat(decision.newlyConfirmedEvents()).hasSize(1);
  }

  @Test
  @DisplayName("同一异常时段内出现的多种异常聚合为一个结果")
  void aggregatesDifferentEventsIntoOneEpisode() {
    track(VisionEventType.MULTIPLE_FACES, Duration.ZERO);
    track(VisionEventType.MULTIPLE_FACES, Duration.ofSeconds(1));
    track(VisionEventType.MULTIPLE_FACES, Duration.ofSeconds(2));
    tracker.track(
        SESSION_ID,
        List.of(VisionEventType.MULTIPLE_FACES, VisionEventType.LOW_LIGHT),
        startedAt.plusSeconds(3),
        3_000L);
    tracker.track(
        SESSION_ID,
        List.of(VisionEventType.MULTIPLE_FACES, VisionEventType.LOW_LIGHT),
        startedAt.plusSeconds(4),
        4_000L);
    VisionAnomalyTracker.Decision expanded = tracker.track(
        SESSION_ID,
        List.of(VisionEventType.MULTIPLE_FACES, VisionEventType.LOW_LIGHT),
        startedAt.plusSeconds(5),
        5_000L);
    tracker.track(SESSION_ID, List.of(), startedAt.plusSeconds(6), 6_000L);
    VisionAnomalyTracker.Decision closed = tracker.track(
        SESSION_ID,
        List.of(),
        startedAt.plusSeconds(8),
        8_000L);

    assertThat(expanded.episodeUpdate()).isNotNull();
    assertThat(expanded.episodeUpdate().eventTypes())
        .containsExactlyInAnyOrder(
            VisionEventType.MULTIPLE_FACES,
            VisionEventType.LOW_LIGHT);
    assertThat(closed.episodeUpdate()).isNotNull();
    assertThat(closed.episodeUpdate().closed()).isTrue();
    assertThat(closed.episodeUpdate().durationMs()).isEqualTo(5_000);
    assertThat(closed.episodeUpdate().eventTypes())
        .containsExactlyInAnyOrder(
            VisionEventType.MULTIPLE_FACES,
            VisionEventType.LOW_LIGHT);
  }

  private VisionAnomalyTracker.Decision track(
      VisionEventType eventType,
      Duration elapsed) {
    return tracker.track(
        SESSION_ID,
        List.of(eventType),
        startedAt.plus(elapsed),
        elapsed.toMillis());
  }
}
