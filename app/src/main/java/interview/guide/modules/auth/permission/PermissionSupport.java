package interview.guide.modules.auth.permission;

import interview.guide.modules.auth.security.AuthPrincipal;
import org.springframework.security.core.Authentication;

final class PermissionSupport {

  private PermissionSupport() {
  }

  static Long userId(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
      return null;
    }
    return principal.id();
  }

  static boolean isAdmin(Authentication authentication) {
    return authentication != null && authentication.getAuthorities().stream()
        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
  }
}
