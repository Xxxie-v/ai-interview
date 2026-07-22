package interview.guide.modules.interview.video.service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview.video")
public class InterviewVideoProperties {

  private Duration chunkDuration = Duration.ofSeconds(25);
  private long maxChunkSize = 25L * 1024 * 1024;
  private int retentionDays = 30;
  private Set<String> allowedMimeTypes = new LinkedHashSet<>(Set.of(
      "video/webm",
      "video/webm;codecs=vp8,opus",
      "video/webm;codecs=vp9,opus",
      "video/mp4"));
}
