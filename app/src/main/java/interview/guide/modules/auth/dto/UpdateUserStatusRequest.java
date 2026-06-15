package interview.guide.modules.auth.dto;

import interview.guide.modules.auth.model.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(
    @NotNull(message = "用户状态不能为空") UserStatus status
) {
}
