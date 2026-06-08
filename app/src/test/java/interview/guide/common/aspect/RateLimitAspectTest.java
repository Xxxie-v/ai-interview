package interview.guide.common.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import interview.guide.modules.auth.security.AuthPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class RateLimitAspectTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("用户限流从认证主体中提取用户ID")
  void shouldResolveUserIdFromAuthenticatedPrincipal() {
    AuthPrincipal principal = new AuthPrincipal(
        42L,
        "candidate",
        "",
        true,
        List.of());
    var authentication = new UsernamePasswordAuthenticationToken(
        principal,
        null,
        principal.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(authentication);
    RateLimitAspect aspect = new RateLimitAspect(mock(RedissonClient.class));

    String userId = ReflectionTestUtils.invokeMethod(aspect, "getCurrentUserId");

    assertThat(userId).isEqualTo("42");
  }
}
