package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.AuthProperties;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JWT 安全载荷")
class JwtServiceTest {

  private JwtService jwtService;

  @BeforeEach
  void setUp() {
    AuthProperties properties = new AuthProperties();
    properties.setSecret("test-secret-that-is-long-enough-for-hmac-signing");
    properties.setAccessTokenTtl(Duration.ofMinutes(5));
    jwtService = new JwtService(properties, new AuthPrincipalFactory(), new ObjectMapper());
  }

  @Test
  @DisplayName("访问令牌只保存鉴权所需字段，不包含手机号等敏感信息")
  void excludesSensitiveProfileFields() {
    RoleEntity role = RoleEntity.builder().code("INTERVIEWEE").name("面试者").build();
    UserEntity user = UserEntity.builder()
        .id(8L)
        .username("candidate")
        .phone("13800138000")
        .email("candidate@example.com")
        .enabled(true)
        .status(UserStatus.ACTIVE)
        .roles(new HashSet<>(Set.of(role)))
        .build();

    String token = jwtService.createAccessToken(user);
    String payload = new String(
        Base64.getUrlDecoder().decode(token.split("\\.")[1]),
        StandardCharsets.UTF_8);

    assertThat(payload).contains("candidate", "INTERVIEWEE");
    assertThat(payload).doesNotContain("13800138000", "candidate@example.com", "password");
    assertThat(jwtService.verifyAccessToken(token).userId()).isEqualTo(8L);
  }
}
