package interview.guide.modules.interview.model;

import java.time.LocalDateTime;

public record InterviewHistoryItemDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String executionStatus,
    InterviewReviewStatus status,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {}
