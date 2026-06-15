package interview.guide.modules.auth.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MockSmsService implements SmsService {

  @Override
  public void sendCode(String mobile, String code) {
    log.info("Mock SMS sent: mobile={}", maskMobile(mobile));
  }

  @Override
  public boolean supports(String provider) {
    return "noop".equalsIgnoreCase(provider) || "mock".equalsIgnoreCase(provider);
  }

  private String maskMobile(String mobile) {
    return mobile.substring(0, 3) + "****" + mobile.substring(7);
  }
}
