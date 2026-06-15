package interview.guide.modules.auth.service;

import interview.guide.common.config.OAuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.IdentityType;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuthStateService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final String KEY_PREFIX = "auth:oauth2:state:";

  private final RedisService redisService;
  private final OAuthProperties properties;

  public String create(IdentityType provider) {
    byte[] random = new byte[32];
    SECURE_RANDOM.nextBytes(random);
    String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    redisService.set(KEY_PREFIX + state, provider.name(), properties.getStateExpiration());
    return state;
  }

  public void consume(String state, IdentityType provider) {
    String key = KEY_PREFIX + state;
    String storedProvider = redisService.get(key);
    redisService.delete(key);
    if (!provider.name().equals(storedProvider)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "OAuth2 state 无效或已过期");
    }
  }
}
