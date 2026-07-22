package interview.guide.modules.interview.vision.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.interview.vision.model.InterviewVisionEventEntity;
import interview.guide.modules.interview.vision.model.VisionEventType;
import interview.guide.modules.interview.vision.repository.InterviewVisionEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("画面事件持久化")
class InterviewVisionEventPersistenceServiceTest {

  @Mock
  private InterviewVisionEventRepository repository;

  private InterviewVisionEventPersistenceService service;

  @BeforeEach
  void setUp() {
    service = new InterviewVisionEventPersistenceService(
        repository,
        new InterviewVisionProperties());
  }

  @Test
  @DisplayName("冷却时间内的同类事件不会重复写入")
  void skipsDuplicateEventInsideCooldown() {
    when(repository.existsBySessionIdAndEventTypeAndOccurredAtAfter(
        any(), any(), any())).thenReturn(true);

    assertThat(service.recordIfOutsideCooldown(
        "session-1", 20L, VisionEventType.LOW_LIGHT, LocalDateTime.now(), 1_000L, "{}"))
        .isEmpty();

    verify(repository, never()).save(any());
  }

  @Test
  @DisplayName("新事件只保存指标元数据且不保存抽帧证据")
  void savesMetadataWithoutFrameEvidence() {
    when(repository.existsBySessionIdAndEventTypeAndOccurredAtAfter(
        any(), any(), any())).thenReturn(false);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.recordIfOutsideCooldown(
        "session-1", 20L, VisionEventType.LOW_LIGHT, LocalDateTime.now(), 1_000L,
        "{\"brightness\":20}");

    ArgumentCaptor<InterviewVisionEventEntity> captor =
        ArgumentCaptor.forClass(InterviewVisionEventEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getEvidenceObjectKey()).isNull();
    assertThat(captor.getValue().getMetadataJson()).contains("brightness");
    assertThat(captor.getValue().getDurationMs()).isEqualTo(5_000L);
  }

  @Test
  @DisplayName("同一会话的异常时段始终更新同一条记录")
  void updatesSameRowForOneEpisode() {
    AtomicReference<InterviewVisionEventEntity> stored = new AtomicReference<>();
    when(repository.save(any())).thenAnswer(invocation -> {
      InterviewVisionEventEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) entity.setId(10L);
      stored.set(entity);
      return entity;
    });
    when(repository.findById(10L)).thenAnswer(ignored -> Optional.of(stored.get()));
    LocalDateTime startedAt = LocalDateTime.of(2026, 8, 18, 10, 0);

    service.upsertEpisode(
        "session-1",
        20L,
        new VisionAnomalyTracker.EpisodeUpdate(
            List.of(VisionEventType.MULTIPLE_FACES),
            startedAt,
            startedAt.plusSeconds(2),
            2_000,
            1_000L,
            false),
        "{}");
    InterviewVisionEventEntity result = service.upsertEpisode(
        "session-1",
        20L,
        new VisionAnomalyTracker.EpisodeUpdate(
            List.of(VisionEventType.MULTIPLE_FACES, VisionEventType.LOW_LIGHT),
            startedAt,
            startedAt.plusSeconds(5),
            5_000,
            1_000L,
            true),
        "{}");

    verify(repository, org.mockito.Mockito.times(2)).save(any());
    assertThat(result.getId()).isEqualTo(10L);
    assertThat(result.getEventTypes()).contains("MULTIPLE_FACES", "LOW_LIGHT");
    assertThat(result.getDurationMs()).isEqualTo(5_000);
    assertThat(result.getEpisodeClosed()).isTrue();
  }
}
