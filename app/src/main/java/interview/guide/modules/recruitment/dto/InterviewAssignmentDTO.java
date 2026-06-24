package interview.guide.modules.recruitment.dto;

import java.time.LocalDateTime;

public record InterviewAssignmentDTO(
    Long id,
    Long candidateId,
    String candidateName,
    String candidateMobile,
    Long jobId,
    String jobName,
    String jobLevel,
    Long resumeId,
    String resumeFilename,
    String status,
    LocalDateTime availableFrom,
    LocalDateTime deadline,
    boolean reportVisibleToCandidate,
    LocalDateTime createdAt
) {
}
