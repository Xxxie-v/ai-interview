package interview.guide.modules.interview.vision.service;

import interview.guide.modules.interview.vision.model.FrameInput;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;
import interview.guide.modules.interview.vision.model.VisionEventType;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MockInterviewVisionAnalyzer implements VisionAnalyzerProvider {

  private final InterviewVisionProperties properties;

  @Override
  public String providerId() {
    return "mock";
  }

  @Override
  public VisionAnalysisResult analyze(FrameInput frame) {
    List<VisionEventType> events = new ArrayList<>();
    int faceCount = frame.cameraActive() ? properties.getMock().getFaceCount() : 0;
    boolean facePresent = faceCount > 0;
    boolean lowLight = frame.brightness() != null
        && frame.brightness() < properties.getLowLightThreshold();

    if (!frame.cameraActive()) {
      events.add(VisionEventType.CAMERA_INTERRUPTED);
    } else if (!facePresent) {
      events.add(VisionEventType.FACE_MISSING);
    } else if (faceCount > 1) {
      events.add(VisionEventType.MULTIPLE_FACES);
    }
    if (lowLight) {
      events.add(VisionEventType.LOW_LIGHT);
    }
    return new VisionAnalysisResult(
        facePresent,
        faceCount,
        properties.getMock().getConfidence(),
        lowLight,
        frame.cameraActive(),
        events);
  }
}
