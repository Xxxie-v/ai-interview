package interview.guide.modules.interview.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record DeviceCheckRequest(
    @NotNull @AssertTrue(message = "摄像头尚未就绪") Boolean cameraReady,
    @NotNull @AssertTrue(message = "麦克风尚未就绪") Boolean microphoneReady) {
}
