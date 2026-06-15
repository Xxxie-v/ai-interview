package interview.guide.modules.auth.service;

import interview.guide.common.config.SmsProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.sms.SmsService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SmsVerificationService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final String CODE_PREFIX = "auth:sms:code:";
  private static final String MOBILE_LIMIT_PREFIX = "auth:sms:limit:";
  private static final String IP_LIMIT_PREFIX = "auth:sms:ip:";
  private static final String ATTEMPTS_PREFIX = "auth:sms:attempts:";

  private final SmsProperties smsProperties;
  private final List<SmsService> smsServices;
  private final RedisService redisService;

  public String sendLoginCode(String mobile, String clientIp) {
    String normalizedMobile = mobile.trim();
    String cooldownKey = MOBILE_LIMIT_PREFIX + normalizedMobile;
    boolean accepted = redisService.getClient().getBucket(cooldownKey)
        .setIfAbsent("1", smsProperties.getResendInterval());
    if (!accepted) {
      throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "验证码发送过于频繁，请稍后再试");
    }

    enforceIpLimit(clientIp);
    String code = generateCode();
    try {
      sender().sendCode(normalizedMobile, code);
    } catch (BusinessException e) {
      redisService.delete(cooldownKey);
      throw e;
    }
    redisService.set(CODE_PREFIX + normalizedMobile, code, smsProperties.getCodeExpiration());
    redisService.delete(ATTEMPTS_PREFIX + normalizedMobile);
    return smsProperties.isExposeCode() && isMockProvider() ? code : null;
  }

  public void verifyLoginCode(String mobile, String verifyCode) {
    String normalizedMobile = mobile.trim();
    String codeKey = CODE_PREFIX + normalizedMobile;
    String attemptsKey = ATTEMPTS_PREFIX + normalizedMobile;
    String expected = redisService.get(codeKey);
    if (expected == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "短信验证码错误或已过期");
    }
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        verifyCode.trim().getBytes(StandardCharsets.UTF_8))) {
      long attempts = redisService.increment(attemptsKey);
      if (attempts == 1) {
        redisService.expire(attemptsKey, smsProperties.getCodeExpiration());
      }
      if (attempts >= smsProperties.getMaxAttempts()) {
        redisService.delete(codeKey);
      }
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "短信验证码错误或已过期");
    }
    redisService.delete(codeKey);
    redisService.delete(attemptsKey);
  }

  public void verifyRegisterCode(String mobile, String verifyCode) {
    verifyLoginCode(mobile, verifyCode);
  }

  private SmsService sender() {
    return smsServices.stream()
        .filter(candidate -> candidate.supports(smsProperties.getProvider()))
        .findFirst()
        .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
            "不支持的短信服务 Provider: " + smsProperties.getProvider()));
  }

  private void enforceIpLimit(String clientIp) {
    String normalizedIp = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
    String key = IP_LIMIT_PREFIX + normalizedIp;
    long count = redisService.increment(key);
    if (count == 1) {
      redisService.expire(key, smsProperties.getIpWindow());
    }
    if (count > smsProperties.getMaxSendsPerIp()) {
      throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "当前网络请求验证码过于频繁");
    }
  }

  private String generateCode() {
    int length = Math.max(4, Math.min(8, smsProperties.getCodeLength()));
    StringBuilder code = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      code.append(SECURE_RANDOM.nextInt(10));
    }
    return code.toString();
  }

  private boolean isMockProvider() {
    return "noop".equalsIgnoreCase(smsProperties.getProvider())
        || "mock".equalsIgnoreCase(smsProperties.getProvider());
  }
}
