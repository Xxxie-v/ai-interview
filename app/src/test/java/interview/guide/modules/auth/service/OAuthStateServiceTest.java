package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.OAuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.IdentityType;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2 state 服务")
class OAuthStateServiceTest {

  @Mock
  private RedisService redisService;

  private OAuthStateService service;

  @BeforeEach
  void setUp() {
    OAuthProperties properties = new OAuthProperties();
    properties.setStateExpiration(Duration.ofMinutes(10));
    service = new OAuthStateService(redisService, properties);
  }

  @Test
  @DisplayName("生成的 state 会按 Provider 写入 Redis")
  void storesStateInRedis() {
    String state = service.create(IdentityType.WECHAT);

    verify(redisService).set(
        "auth:oauth2:state:" + state, "WECHAT", Duration.ofMinutes(10));
  }

  @Test
  @DisplayName("state 校验成功后立即删除")
  void consumesStateOnce() {
    when(redisService.get("auth:oauth2:state:state-value")).thenReturn("QQ");

    service.consume("state-value", IdentityType.QQ);

    verify(redisService).delete("auth:oauth2:state:state-value");
  }

  @Test
  @DisplayName("Provider 不匹配时拒绝授权")
  void rejectsProviderMismatch() {
    when(redisService.get(startsWith("auth:oauth2:state:"))).thenReturn("QQ");

    assertThatThrownBy(() -> service.consume("invalid", IdentityType.WECHAT))
        .isInstanceOf(BusinessException.class);
  }
}
