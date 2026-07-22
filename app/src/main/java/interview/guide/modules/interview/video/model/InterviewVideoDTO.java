package interview.guide.modules.interview.video.model;

import java.time.LocalDateTime;

public record InterviewVideoDTO(
    Long id,
    String sessionId,
    String mimeType,
    Long fileSize,
    Long durationMs,
    Integer chunkIndex,
    String checksum,
    VideoStatus status,
    LocalDateTime createdAt) {

  public static InterviewVideoDTO from(InterviewVideoEntity entity) {
    return new InterviewVideoDTO(
        entity.getId(),
        entity.getSessionId(),
        entity.getMimeType(),
        entity.getFileSize(),
        entity.getDurationMs(),
        entity.getChunkIndex(),
        entity.getChecksum(),
        entity.getStatus(),
        entity.getCreatedAt());
  }
}
