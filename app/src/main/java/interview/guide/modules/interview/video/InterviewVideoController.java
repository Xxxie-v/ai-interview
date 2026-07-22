package interview.guide.modules.interview.video;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.interview.video.model.InterviewVideoDTO;
import interview.guide.modules.interview.video.model.VideoUploadCompleteResponse;
import interview.guide.modules.interview.video.service.InterviewVideoService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class InterviewVideoController {

  private final InterviewVideoService videoService;

  @PostMapping("/api/interviews/{sessionId}/videos/chunks")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 20)
  public Result<InterviewVideoDTO> uploadChunk(
      @PathVariable String sessionId,
      @RequestParam int chunkIndex,
      @RequestParam long durationMs,
      @RequestParam String checksum,
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(videoService.uploadChunk(
        sessionId,
        principal.id(),
        chunkIndex,
        durationMs,
        checksum,
        file));
  }

  @PostMapping("/api/interviews/{sessionId}/videos/complete")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<VideoUploadCompleteResponse> completeUpload(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(videoService.completeUpload(sessionId, principal.id()));
  }

  @GetMapping("/api/interviews/{sessionId}/videos")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<List<InterviewVideoDTO>> listVideos(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(videoService.listOwned(sessionId, principal.id()));
  }
}
