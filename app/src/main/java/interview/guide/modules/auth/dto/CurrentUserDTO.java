package interview.guide.modules.auth.dto;

import java.util.Set;

public record CurrentUserDTO(
    Long id,
    String username,
    String nickname,
    String avatarUrl,
    String email,
    String mobile,
    String status,
    Set<String> roles,
    Set<String> permissions
) {
}
