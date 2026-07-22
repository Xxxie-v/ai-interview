package interview.guide.modules.interview.video.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
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
    name = "interview_videos",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_interview_video_session_chunk",
        columnNames = {"session_id", "chunk_index"}),
    indexes = {
        @Index(name = "idx_interview_video_session", columnList = "session_id"),
        @Index(name = "idx_interview_video_user_created", columnList = "user_id,created_at")
    })
public class InterviewVideoEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false, length = 36)
  private String sessionId;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "object_key", nullable = false, unique = true, length = 320)
  private String objectKey;

  @Column(name = "mime_type", nullable = false, length = 80)
  private String mimeType;

  @Column(name = "file_size", nullable = false)
  private Long fileSize;

  @Column(name = "duration_ms", nullable = false)
  private Long durationMs;

  @Column(name = "chunk_index", nullable = false)
  private Integer chunkIndex;

  @Column(nullable = false, length = 64)
  private String checksum;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private VideoStatus status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }
}
