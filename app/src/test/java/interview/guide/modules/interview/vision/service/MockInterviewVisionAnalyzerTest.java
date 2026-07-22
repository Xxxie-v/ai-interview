package interview.guide.modules.interview.vision.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.vision.model.FrameInput;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;
import interview.guide.modules.interview.vision.model.VisionEventType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Mock 画面分析器")
class MockInterviewVisionAnalyzerTest {

  private InterviewVisionProperties properties;
  private MockInterviewVisionAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    properties = new InterviewVisionProperties();
    analyzer = new MockInterviewVisionAnalyzer(properties);
  }

  @Nested
  @DisplayName("客观事件识别")
  class ObjectiveEvents {

    @Test
    @DisplayName("画面正常时不产生事件")
    void returnsNoEventsForNormalFrame() {
      VisionAnalysisResult result = analyzer.analyze(frame(120D, true));

      assertThat(result.facePresent()).isTrue();
      assertThat(result.events()).isEmpty();
    }

    @Test
    @DisplayName("亮度低于阈值时记录低光事件")
    void detectsLowLight() {
      VisionAnalysisResult result = analyzer.analyze(frame(20D, true));

      assertThat(result.events()).containsExactly(VisionEventType.LOW_LIGHT);
    }

    @Test
    @DisplayName("摄像头停止时记录中断事件")
    void detectsCameraInterruption() {
      VisionAnalysisResult result = analyzer.analyze(frame(null, false));

      assertThat(result.facePresent()).isFalse();
      assertThat(result.events()).containsExactly(VisionEventType.CAMERA_INTERRUPTED);
    }

    @Test
    @DisplayName("Mock 配置为两张人脸时记录多人事件")
    void detectsMultipleFacesFromMockConfiguration() {
      properties.getMock().setFaceCount(2);

      VisionAnalysisResult result = analyzer.analyze(frame(120D, true));

      assertThat(result.faceCount()).isEqualTo(2);
      assertThat(result.events()).containsExactly(VisionEventType.MULTIPLE_FACES);
    }
  }

  private FrameInput frame(Double brightness, boolean cameraActive) {
    return new FrameInput(
        new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff},
        "image/jpeg",
        brightness,
        cameraActive,
        LocalDateTime.now());
  }
}
