package interview.guide.modules.interview.vision.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "interview_vision_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_vision_event_session_client_event",
        columnNames = {"session_id", "client_event_id"}),
    indexes = {
    @Index(name = "idx_vision_event_session_time", columnList = "session_id,occurred_at"),
    @Index(name = "idx_vision_event_type_time", columnList = "event_type,occurred_at"),
    @Index(name = "idx_vision_event_session_video_offset", columnList = "session_id,video_offset_ms")
})
public class InterviewVisionEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false, length = 36)
  private String sessionId;

  @Column(name = "owner_user_id", nullable = false)
  private Long ownerUserId;

  @Column(name = "client_event_id", length = 36)
  private String clientEventId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private VisionEventType eventType;

  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  @Column(name = "ended_at")
  private LocalDateTime endedAt;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "event_types", length = 256)
  private String eventTypes;

  @Column(name = "episode_closed")
  private Boolean episodeClosed;

  @Column(name = "video_offset_ms")
  private Long videoOffsetMs;

  @Column(name = "evidence_object_key", length = 320)
  private String evidenceObjectKey;

  @Column(name = "metadata_json", columnDefinition = "TEXT")
  private String metadataJson;
}
