package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateUnlimitedInterviewsRequest(
    @NotNull(message = "是否允许无限面试不能为空") Boolean enabled
) {
}
