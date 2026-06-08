package interview.guide.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.auth.jwt")
public class AuthProperties {

  private String secret;
  private String issuer = "interview-guide";
  private Duration accessTokenTtl = Duration.ofMinutes(30);
  private Duration refreshTokenTtl = Duration.ofDays(14);
  private String identityEncryptionKey;
}
