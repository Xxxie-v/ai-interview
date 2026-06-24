package interview.guide.modules.recruitment.dto;

import java.time.LocalDateTime;

public record CandidateResumeDTO(
    Long id,
    String filename,
    String questionPrepareStatus,
    LocalDateTime uploadedAt
) {
}
