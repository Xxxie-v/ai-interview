package interview.guide.modules.interview.model;

import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import java.time.LocalDateTime;

public record SessionListItemDTO(
    String sessionId,
    String skillId,
    String difficulty,
    Long resumeId,
    Long jobId,
    String jobName,
    int totalQuestions,
    SessionStatus executionStatus,
    InterviewReviewStatus status,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {
  public static SessionListItemDTO from(InterviewSessionEntity session, String jobName) {
    return new SessionListItemDTO(
        session.getSessionId(),
        session.getSkillId(),
        session.getDifficulty(),
        session.getResumeId(),
        session.getJobId(),
        jobName,
        session.getTotalQuestions() != null ? session.getTotalQuestions() : 0,
        session.getStatus(),
        effectiveReviewStatus(session),
        session.getCreatedAt(),
        session.getCompletedAt());
  }

  public static SessionListItemDTO from(
      InterviewSessionEntity session,
      boolean reportVisible,
      String jobName) {
    return from(session, jobName);
  }

  private static InterviewReviewStatus effectiveReviewStatus(
      InterviewSessionEntity session) {
    return session.getEffectiveReviewStatus();
  }
}
