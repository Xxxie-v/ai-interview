package interview.guide.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.auth.oauth2")
public class OAuthProperties {

  private Duration stateExpiration = Duration.ofMinutes(10);
  private Provider wechat = new Provider();
  private Provider qq = new Provider();

  @Data
  public static class Provider {
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String authorizationUri;
    private String tokenUri;
    private String userInfoUri;
    private String openIdUri;
  }
}
