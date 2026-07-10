package interview.guide.modules.interview.websocket;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview.events")
public class InterviewEventProperties {

  private Duration retention = Duration.ofHours(2);
  private int maxEventsPerSession = 200;
}
