package interview.guide.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuthBindRequest(
    @NotBlank(message = "授权码不能为空") String code,
    @NotBlank(message = "state 不能为空") String state
) {
}
