package interview.guide.modules.interview.vision.model;

import java.time.LocalDateTime;

public record FrameInput(
    String sessionId,
    Long ownerUserId,
    byte[] frame,
    String contentType,
    Double brightness,
    boolean cameraActive,
    LocalDateTime capturedAt) {

  public FrameInput(
      byte[] frame,
      String contentType,
      Double brightness,
      boolean cameraActive,
      LocalDateTime capturedAt) {
    this(null, null, frame, contentType, brightness, cameraActive, capturedAt);
  }

  public FrameInput {
    frame = frame == null ? new byte[0] : frame.clone();
  }

  @Override
  public byte[] frame() {
    return frame.clone();
  }
}
