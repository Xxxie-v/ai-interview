package interview.guide.modules.recruitment.dto;

import java.time.LocalDateTime;
import java.util.List;
import interview.guide.modules.interview.model.InterviewQuestionDTO;

public record JobPositionDTO(
    Long id,
    String name,
    String description,
    String requirements,
    String level,
    List<InterviewQuestionDTO> fixedQuestions,
    String status,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
