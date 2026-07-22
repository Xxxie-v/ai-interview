package interview.guide.modules.interview.vision.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.vision.model.FrameInput;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Primary
@Service
public class RoutingInterviewVisionAnalyzer implements InterviewVisionAnalyzer {

  private final InterviewVisionProperties properties;
  private final Map<String, VisionAnalyzerProvider> providers;

  public RoutingInterviewVisionAnalyzer(
      InterviewVisionProperties properties,
      List<VisionAnalyzerProvider> providers) {
    this.properties = properties;
    Map<String, VisionAnalyzerProvider> indexed = new LinkedHashMap<>();
    for (VisionAnalyzerProvider provider : providers) {
      indexed.put(normalize(provider.providerId()), provider);
    }
    this.providers = Map.copyOf(indexed);
  }

  @Override
  public String providerId() {
    return "routing";
  }

  @Override
  public VisionAnalysisResult analyze(FrameInput frame) {
    String providerId = normalize(properties.getProvider());
    VisionAnalyzerProvider provider = providers.get(providerId);
    if (provider == null) {
      throw new BusinessException(
          ErrorCode.VISION_ANALYSIS_FAILED,
          "不支持的视觉分析 Provider: " + providerId);
    }
    return provider.analyze(frame);
  }

  private String normalize(String providerId) {
    return providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
  }
}
