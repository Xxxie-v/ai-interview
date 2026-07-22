package interview.guide.modules.interview.vision.service;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview.vision")
public class InterviewVisionProperties {

  private boolean enabled = true;
  private String provider = "mock";
  private Duration frameInterval = Duration.ofSeconds(5);
  private Duration suspectFrameInterval = Duration.ofSeconds(1);
  private int confirmationFrames = 3;
  private Duration faceMissingMinDuration = Duration.ofSeconds(2);
  private Duration multipleFacesMinDuration = Duration.ofSeconds(1);
  private Duration otherEventMinDuration = Duration.ofSeconds(1);
  private Duration recoveryWindow = Duration.ofSeconds(2);
  private Duration stateTtl = Duration.ofHours(4);
  private long maxFrameSize = 1024 * 1024;
  private Duration eventCooldown = Duration.ofSeconds(30);
  private double lowLightThreshold = 40;
  private MockConfig mock = new MockConfig();
  private OnnxConfig onnx = new OnnxConfig();

  @Data
  public static class MockConfig {
    private int faceCount = 1;
    private double confidence = 0.5;
  }

  @Data
  public static class OnnxConfig {
    private String detectorModel = "models/vision/det_10g.onnx";
    private String recognitionModel = "models/vision/w600k_r50.onnx";
    private int detectorInputSize = 640;
    private int recognitionInputSize = 112;
    private double detectionThreshold = 0.5;
    private double nmsThreshold = 0.4;
    private double identityThreshold = 0.5;
    private double referenceMinConfidence = 0.75;
    private Duration referenceTtl = Duration.ofHours(4);
  }
}
