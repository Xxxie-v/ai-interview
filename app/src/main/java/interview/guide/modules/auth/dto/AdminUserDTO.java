package interview.guide.modules.auth.dto;

import java.time.LocalDateTime;
import java.util.Set;

public record AdminUserDTO(
    Long id,
    String username,
    String nickname,
    String email,
    String mobile,
    String status,
    boolean unlimitedInterviews,
    Set<String> roles,
    LocalDateTime createdAt
) {
}
