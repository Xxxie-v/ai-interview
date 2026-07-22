package interview.guide.modules.interview.vision.model;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public record InterviewVisionEventDTO(
    Long id,
    String sessionId,
    VisionEventType eventType,
    List<VisionEventType> eventTypes,
    LocalDateTime occurredAt,
    LocalDateTime endedAt,
    Long durationMs,
    Long videoOffsetMs,
    String metadataJson,
    boolean episodeClosed,
    boolean evidenceAvailable) {

  public static InterviewVisionEventDTO from(InterviewVisionEventEntity entity) {
    return new InterviewVisionEventDTO(
        entity.getId(),
        entity.getSessionId(),
        entity.getEventType(),
        parseEventTypes(entity),
        entity.getOccurredAt(),
        entity.getEndedAt(),
        entity.getDurationMs(),
        entity.getVideoOffsetMs(),
        entity.getMetadataJson(),
        entity.getEpisodeClosed() == null || entity.getEpisodeClosed(),
        entity.getEvidenceObjectKey() != null);
  }

  public static InterviewVisionEventDTO from(
      InterviewVisionEventEntity entity,
      Long fallbackVideoOffsetMs) {
    InterviewVisionEventDTO dto = from(entity);
    if (dto.videoOffsetMs() != null) {
      return dto;
    }
    return new InterviewVisionEventDTO(
        dto.id(),
        dto.sessionId(),
        dto.eventType(),
        dto.eventTypes(),
        dto.occurredAt(),
        dto.endedAt(),
        dto.durationMs(),
        fallbackVideoOffsetMs,
        dto.metadataJson(),
        dto.episodeClosed(),
        dto.evidenceAvailable());
  }

  private static List<VisionEventType> parseEventTypes(
      InterviewVisionEventEntity entity) {
    if (entity.getEventTypes() == null || entity.getEventTypes().isBlank()) {
      return List.of(entity.getEventType());
    }
    try {
      return Arrays.stream(entity.getEventTypes().split(","))
          .map(VisionEventType::valueOf)
          .toList();
    } catch (IllegalArgumentException ignored) {
      return List.of(entity.getEventType());
    }
  }
}
