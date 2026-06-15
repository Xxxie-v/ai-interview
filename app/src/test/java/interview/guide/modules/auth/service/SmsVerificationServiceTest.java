package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.SmsProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.sms.SmsService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("短信验证码服务")
class SmsVerificationServiceTest {

  @Mock
  private SmsService smsService;
  @Mock
  private RedisService redisService;
  @Mock
  private RedissonClient redissonClient;
  @Mock
  private RBucket<String> cooldownBucket;

  private SmsProperties properties;
  private SmsVerificationService service;

  @BeforeEach
  void setUp() {
    properties = new SmsProperties();
    properties.setProvider("mock");
    properties.setExposeCode(true);
    properties.setCodeExpiration(Duration.ofMinutes(5));
    properties.setResendInterval(Duration.ofSeconds(60));
    service = new SmsVerificationService(properties, List.of(smsService), redisService);
  }

  @Nested
  @DisplayName("发送验证码")
  class SendCode {

    @Test
    @DisplayName("验证码写入 Redis 且只向 Mock 服务发送")
  void storesGeneratedCodeInRedis() {
    when(smsService.supports("mock")).thenReturn(true);
      when(redisService.getClient()).thenReturn(redissonClient);
      when(redissonClient.<String>getBucket(anyString())).thenReturn(cooldownBucket);
      when(cooldownBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
      when(redisService.increment("auth:sms:ip:127.0.0.1")).thenReturn(1L);

      service.sendLoginCode("13800000000", "127.0.0.1");

      ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
      verify(smsService).sendCode(anyString(), codeCaptor.capture());
      verify(redisService).set(
          "auth:sms:code:13800000000", codeCaptor.getValue(), Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("60 秒内重复发送会被拒绝")
    void rejectsRepeatedSend() {
      when(redisService.getClient()).thenReturn(redissonClient);
      when(redissonClient.<String>getBucket(anyString())).thenReturn(cooldownBucket);
      when(cooldownBucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);

      assertThatThrownBy(() -> service.sendLoginCode("13800000000", "127.0.0.1"))
          .isInstanceOf(BusinessException.class);
    }
  }

  @Nested
  @DisplayName("校验验证码")
  class VerifyCode {

    @Test
    @DisplayName("成功后立即删除验证码和失败计数")
    void deletesCodeAfterSuccess() {
      when(redisService.get("auth:sms:code:13800000000")).thenReturn("123456");

      service.verifyLoginCode("13800000000", "123456");

      verify(redisService).delete("auth:sms:code:13800000000");
      verify(redisService).delete("auth:sms:attempts:13800000000");
    }

    @Test
    @DisplayName("错误验证码会累计失败次数")
    void countsFailedAttempt() {
      when(redisService.get("auth:sms:code:13800000000")).thenReturn("123456");
      when(redisService.increment("auth:sms:attempts:13800000000")).thenReturn(1L);

      assertThatThrownBy(() -> service.verifyLoginCode("13800000000", "000000"))
          .isInstanceOf(BusinessException.class);

      verify(redisService).expire(
          "auth:sms:attempts:13800000000", Duration.ofMinutes(5));
    }
  }
}
