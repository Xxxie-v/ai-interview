package interview.guide.modules.interview.vision.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.vision.model.InterviewVisionEventEntity;
import interview.guide.modules.interview.vision.model.VisionEventType;
import interview.guide.modules.interview.vision.repository.InterviewVisionEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InterviewVisionEventPersistenceService {

  private final InterviewVisionEventRepository repository;
  private final InterviewVisionProperties properties;
  private final Map<String, Long> activeEpisodeIds = new ConcurrentHashMap<>();

  @Transactional
  public InterviewVisionEventEntity upsertEpisode(
      String sessionId,
      Long ownerUserId,
      VisionAnomalyTracker.EpisodeUpdate episode,
      String metadataJson) {
    Long activeEpisodeId = activeEpisodeIds.get(sessionId);
    InterviewVisionEventEntity entity = activeEpisodeId == null
        ? new InterviewVisionEventEntity()
        : repository.findById(activeEpisodeId).orElseGet(InterviewVisionEventEntity::new);
    entity.setSessionId(sessionId);
    entity.setOwnerUserId(ownerUserId);
    entity.setEventType(primaryEventType(episode.eventTypes()));
    entity.setEventTypes(joinEventTypes(episode.eventTypes()));
    entity.setOccurredAt(episode.startedAt());
    entity.setEndedAt(episode.endedAt());
    entity.setDurationMs(episode.durationMs());
    entity.setVideoOffsetMs(episode.videoOffsetMs());
    entity.setEpisodeClosed(episode.closed());
    if (entity.getMetadataJson() == null) {
      entity.setMetadataJson(metadataJson);
    }
    InterviewVisionEventEntity saved = repository.save(entity);
    if (episode.closed()) {
      activeEpisodeIds.remove(sessionId);
    } else if (saved.getId() != null) {
      activeEpisodeIds.put(sessionId, saved.getId());
    }
    return saved;
  }

  @Transactional
  public Optional<InterviewVisionEventEntity> recordIfOutsideCooldown(
      String sessionId,
      Long ownerUserId,
      VisionEventType eventType,
      LocalDateTime occurredAt,
      Long videoOffsetMs,
      String metadataJson) {
    return recordIfOutsideCooldown(
        sessionId,
        ownerUserId,
        eventType,
        occurredAt,
        properties.getFrameInterval().toMillis(),
        videoOffsetMs,
        metadataJson);
  }

  @Transactional
  public Optional<InterviewVisionEventEntity> recordIfOutsideCooldown(
      String sessionId,
      Long ownerUserId,
      VisionEventType eventType,
      LocalDateTime occurredAt,
      Long durationMs,
      Long videoOffsetMs,
      String metadataJson) {
    LocalDateTime cutoff = occurredAt.minus(properties.getEventCooldown());
    if (repository.existsBySessionIdAndEventTypeAndOccurredAtAfter(
        sessionId, eventType, cutoff)) {
      return Optional.empty();
    }
    InterviewVisionEventEntity entity = InterviewVisionEventEntity.builder()
        .sessionId(sessionId)
        .ownerUserId(ownerUserId)
        .eventType(eventType)
        .eventTypes(eventType.name())
        .occurredAt(occurredAt)
        .endedAt(occurredAt.plusNanos(durationMs * 1_000_000))
        .durationMs(durationMs)
        .episodeClosed(true)
        .videoOffsetMs(videoOffsetMs)
        .metadataJson(metadataJson)
        .build();
    return Optional.of(repository.save(entity));
  }

  @Transactional
  public InterviewVisionEventEntity record(
      String sessionId,
      Long ownerUserId,
      String clientEventId,
      VisionEventType eventType,
      LocalDateTime occurredAt,
      Long durationMs,
      Long videoOffsetMs,
      String evidenceObjectKey,
      String metadataJson) {
    InterviewVisionEventEntity entity = InterviewVisionEventEntity.builder()
        .sessionId(sessionId)
        .ownerUserId(ownerUserId)
        .clientEventId(clientEventId)
        .eventType(eventType)
        .eventTypes(eventType.name())
        .occurredAt(occurredAt)
        .endedAt(durationMs == null
            ? occurredAt
            : occurredAt.plusNanos(durationMs * 1_000_000))
        .durationMs(durationMs)
        .episodeClosed(true)
        .videoOffsetMs(videoOffsetMs)
        .evidenceObjectKey(evidenceObjectKey)
        .metadataJson(metadataJson)
        .build();
    return repository.save(entity);
  }

  @Transactional(readOnly = true)
  public List<InterviewVisionEventEntity> listBySessionId(String sessionId) {
    return repository.findBySessionIdOrderByOccurredAtAsc(sessionId);
  }

  @Transactional(readOnly = true)
  public Optional<InterviewVisionEventEntity> findByClientEventId(
      String sessionId,
      String clientEventId) {
    return repository.findBySessionIdAndClientEventId(sessionId, clientEventId);
  }

  @Transactional(readOnly = true)
  public InterviewVisionEventEntity findRequired(Long eventId) {
    return repository.findById(eventId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "监考事件不存在"));
  }

  private VisionEventType primaryEventType(List<VisionEventType> eventTypes) {
    return eventTypes.stream()
        .min((left, right) -> Integer.compare(priority(left), priority(right)))
        .orElse(VisionEventType.LOW_LIGHT);
  }

  private int priority(VisionEventType eventType) {
    return switch (eventType) {
      case CAMERA_INTERRUPTED -> 0;
      case IDENTITY_MISMATCH -> 1;
      case MULTIPLE_FACES -> 2;
      case FACE_MISSING -> 3;
      case LOW_LIGHT -> 4;
      default -> 5;
    };
  }

  private String joinEventTypes(List<VisionEventType> eventTypes) {
    return eventTypes.stream()
        .map(VisionEventType::name)
        .sorted()
        .reduce((left, right) -> left + "," + right)
        .orElse(VisionEventType.LOW_LIGHT.name());
  }
}
