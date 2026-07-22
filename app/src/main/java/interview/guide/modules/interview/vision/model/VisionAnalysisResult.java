package interview.guide.modules.interview.vision.model;

import java.util.List;

public record VisionAnalysisResult(
    boolean facePresent,
    int faceCount,
    double confidence,
    boolean lowLight,
    boolean cameraActive,
    Double identitySimilarity,
    Boolean samePerson,
    VisionMonitoringState monitoringState,
    long recommendedIntervalMs,
    List<VisionEventType> candidateEvents,
    List<VisionEventType> events) {

  public VisionAnalysisResult(
      boolean facePresent,
      int faceCount,
      double confidence,
      boolean lowLight,
      boolean cameraActive,
      Double identitySimilarity,
      Boolean samePerson,
      List<VisionEventType> events) {
    this(
        facePresent,
        faceCount,
        confidence,
        lowLight,
        cameraActive,
        identitySimilarity,
        samePerson,
        events == null || events.isEmpty()
            ? VisionMonitoringState.NORMAL
            : VisionMonitoringState.SUSPECT,
        0,
        events,
        events);
  }

  public VisionAnalysisResult(
      boolean facePresent,
      int faceCount,
      double confidence,
      boolean lowLight,
      boolean cameraActive,
      List<VisionEventType> events) {
    this(facePresent, faceCount, confidence, lowLight, cameraActive, null, null, events);
  }

  public VisionAnalysisResult {
    monitoringState = monitoringState == null
        ? VisionMonitoringState.NORMAL
        : monitoringState;
    candidateEvents = candidateEvents == null ? List.of() : List.copyOf(candidateEvents);
    events = events == null ? List.of() : List.copyOf(events);
  }
}
