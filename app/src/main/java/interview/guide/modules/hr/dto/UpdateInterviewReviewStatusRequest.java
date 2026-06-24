package interview.guide.modules.hr.dto;

import interview.guide.modules.interview.model.InterviewReviewStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateInterviewReviewStatusRequest(
    @NotNull InterviewReviewStatus status
) {}
