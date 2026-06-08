package interview.guide.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.sms")
public class SmsProperties {

  private String provider = "noop";
  private String regionId = "cn-hangzhou";
  private String accessKeyId;
  private String accessKeySecret;
  private String signName;
  private String templateCode;
  private String schemeName = "default";
  private String countryCode = "86";
  private int codeLength = 6;
  private long validTime = 300;
  private long interval = 60;
  private int codeType = 1;
  private int duplicatePolicy = 1;
  private String noopCode = "123456";
  private Duration codeExpiration = Duration.ofMinutes(5);
  private Duration resendInterval = Duration.ofSeconds(60);
  private Duration ipWindow = Duration.ofHours(1);
  private int maxSendsPerIp = 20;
  private int maxAttempts = 5;
  private boolean exposeCode;
}
