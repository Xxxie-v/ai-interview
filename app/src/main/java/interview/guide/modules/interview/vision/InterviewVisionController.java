package interview.guide.modules.interview.vision;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;
import interview.guide.modules.interview.vision.model.VisionEventType;
import interview.guide.modules.interview.vision.service.InterviewVisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class InterviewVisionController {

  private final InterviewVisionService visionService;

  @PostMapping("/api/interviews/{sessionId}/vision/analyze")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 30)
  public Result<VisionAnalysisResult> analyze(
      @PathVariable String sessionId,
      @RequestParam(required = false) MultipartFile frame,
      @RequestParam(required = false) Double brightness,
      @RequestParam(defaultValue = "true") boolean cameraActive,
      @RequestParam(required = false) Long videoOffsetMs,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(visionService.analyze(
        sessionId,
        principal.id(),
        frame,
        brightness,
        cameraActive,
        videoOffsetMs));
  }

  @PostMapping("/api/interviews/{sessionId}/proctor/events")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 180)
  public Result<Void> recordProctorEvent(
      @PathVariable String sessionId,
      @RequestParam String clientEventId,
      @RequestParam VisionEventType eventType,
      @RequestParam(required = false) MultipartFile evidence,
      @RequestParam(required = false) String metadata,
      @RequestParam(required = false) Long videoOffsetMs,
      @AuthenticationPrincipal AuthPrincipal principal) {
    visionService.recordProctorEvent(
        sessionId,
        principal.id(),
        clientEventId,
        eventType,
        evidence,
        metadata,
        videoOffsetMs);
    return Result.success();
  }
}
