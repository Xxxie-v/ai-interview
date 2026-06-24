package interview.guide.modules.hr.dto;

import interview.guide.modules.interview.model.InterviewReviewStatus;
import java.time.LocalDateTime;

public record HrInterviewResultDTO(
    String sessionId,
    Long resumeId,
    Long jobId,
    String jobName,
    Long candidateId,
    String candidateName,
    String candidatePhone,
    String resumeFilename,
    String skillId,
    String difficulty,
    InterviewReviewStatus status,
    LocalDateTime createdAt,
    LocalDateTime completedAt
) {}
