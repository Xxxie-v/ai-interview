package interview.guide.modules.recruitment.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record CreateInterviewAssignmentRequest(
    @NotNull(message = "候选人不能为空") Long candidateId,
    @NotNull(message = "岗位不能为空") Long jobId,
    Long resumeId,
    LocalDateTime availableFrom,
    @NotNull(message = "截止时间不能为空")
    @Future(message = "截止时间必须晚于当前时间")
    LocalDateTime deadline,
    Boolean reportVisibleToCandidate
) {
}
