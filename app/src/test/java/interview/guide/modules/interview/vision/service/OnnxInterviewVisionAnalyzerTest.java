package interview.guide.modules.interview.vision.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.interview.vision.model.FrameInput;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;
import interview.guide.modules.interview.vision.model.VisionEventType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisplayName("SCRFD + ArcFace ONNX 真实模型推理")
class OnnxInterviewVisionAnalyzerTest {

  private static final Logger log = LoggerFactory.getLogger(
      OnnxInterviewVisionAnalyzerTest.class);

  private OnnxInterviewVisionAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(modelExists("models/vision/scrfd_500m_kps.onnx"));
    Assumptions.assumeTrue(modelExists("models/vision/w600k_mbf.onnx"));
    InterviewVisionProperties properties = new InterviewVisionProperties();
    properties.getOnnx().setDetectorModel("models/vision/scrfd_500m_kps.onnx");
    properties.getOnnx().setRecognitionModel("models/vision/w600k_mbf.onnx");
    analyzer = new OnnxInterviewVisionAnalyzer(properties);
  }

  private boolean modelExists(String configuredPath) {
    Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    if (Files.isRegularFile(workingDirectory.resolve(configuredPath))) return true;
    Path parent = workingDirectory.getParent();
    return parent != null && Files.isRegularFile(parent.resolve(configuredPath));
  }

  @AfterEach
  void tearDown() {
    if (analyzer != null) {
      analyzer.close();
    }
  }

  @Test
  @DisplayName("同一张正脸连续推理时能够检测人脸并通过身份一致性校验")
  void detectsFaceAndRecognizesSamePerson() throws IOException {
    byte[] image = readImage("/vision/synthetic-face.png");

    long startedAt = System.nanoTime();
    VisionAnalysisResult enrollment = analyzer.analyze(frame(image));
    long enrolledAt = System.nanoTime();
    VisionAnalysisResult verification = analyzer.analyze(frame(image));
    long verifiedAt = System.nanoTime();

    log.info(
        "ONNX test metrics: detectionConfidence={}, enrollmentSimilarity={}, "
            + "verificationSimilarity={}, enrollmentMs={}, verificationMs={}",
        enrollment.confidence(),
        enrollment.identitySimilarity(),
        verification.identitySimilarity(),
        elapsedMillis(startedAt, enrolledAt),
        elapsedMillis(enrolledAt, verifiedAt));

    assertThat(enrollment.faceCount()).isEqualTo(1);
    assertThat(enrollment.confidence()).isGreaterThan(0.75);
    assertThat(enrollment.samePerson()).isTrue();
    assertThat(enrollment.identitySimilarity()).isEqualTo(1D);
    assertThat(verification.faceCount()).isEqualTo(1);
    assertThat(verification.samePerson()).isTrue();
    assertThat(verification.identitySimilarity()).isGreaterThan(0.99);
    assertThat(verification.events()).doesNotContain(
        VisionEventType.FACE_MISSING,
        VisionEventType.MULTIPLE_FACES,
        VisionEventType.IDENTITY_MISMATCH);
  }

  @Test
  @DisplayName("基准建立后输入另一张人脸时能够识别身份不一致")
  void detectsDifferentPerson() throws IOException {
    long startedAt = System.nanoTime();
    VisionAnalysisResult enrollment = analyzer.analyze(
        frame(readImage("/vision/synthetic-face.png")));
    long enrolledAt = System.nanoTime();
    VisionAnalysisResult verification = analyzer.analyze(
        frame(readImage("/vision/synthetic-face-other.png")));
    long verifiedAt = System.nanoTime();

    log.info(
        "ONNX mismatch metrics: enrollmentConfidence={}, verificationConfidence={}, "
            + "similarity={}, enrollmentMs={}, verificationMs={}",
        enrollment.confidence(),
        verification.confidence(),
        verification.identitySimilarity(),
        elapsedMillis(startedAt, enrolledAt),
        elapsedMillis(enrolledAt, verifiedAt));
    assertThat(enrollment.samePerson()).isTrue();
    assertThat(verification.faceCount()).isEqualTo(1);
    assertThat(verification.samePerson()).isFalse();
    assertThat(verification.identitySimilarity()).isLessThan(0.5);
    assertThat(verification.events()).contains(VisionEventType.IDENTITY_MISMATCH);
  }

  private byte[] readImage(String resourcePath) throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
      assertThat(stream).as("test image %s", resourcePath).isNotNull();
      return stream.readAllBytes();
    }
  }

  private double elapsedMillis(long startedAt, long endedAt) {
    return (endedAt - startedAt) / 1_000_000D;
  }

  private FrameInput frame(byte[] image) {
    return new FrameInput(
        "onnx-test-session",
        1L,
        image,
        "image/png",
        120D,
        true,
        LocalDateTime.now());
  }
}
