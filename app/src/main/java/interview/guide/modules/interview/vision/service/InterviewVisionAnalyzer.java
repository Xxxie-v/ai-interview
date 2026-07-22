package interview.guide.modules.interview.vision.service;

import interview.guide.modules.interview.vision.model.FrameInput;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;

public interface InterviewVisionAnalyzer {

  String providerId();

  VisionAnalysisResult analyze(FrameInput frame);
}
