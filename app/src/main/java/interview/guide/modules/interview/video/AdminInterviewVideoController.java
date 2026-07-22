package interview.guide.modules.interview.video;

import interview.guide.common.result.Result;
import interview.guide.infrastructure.file.ObjectAccessResponse;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.interview.video.model.InterviewVideoDTO;
import interview.guide.modules.interview.video.service.InterviewVideoService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminInterviewVideoController {

  private final InterviewVideoService videoService;

  @GetMapping("/api/admin/interviews/{sessionId}/videos")
  @PreAuthorize("hasRole('ADMIN')")
  public Result<List<InterviewVideoDTO>> listVideos(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    log.info("Admin viewed interview video metadata: adminId={}, sessionId={}",
        principal.id(), sessionId);
    return Result.success(videoService.listForAdmin(sessionId));
  }

  @GetMapping("/api/admin/interviews/{sessionId}/videos/{videoId}/access")
  @PreAuthorize("hasRole('ADMIN')")
  public Result<ObjectAccessResponse> createAccess(
      @PathVariable String sessionId,
      @PathVariable Long videoId) {
    return Result.success(videoService.createAdminAccess(sessionId, videoId));
  }

  @GetMapping("/api/admin/interviews/{sessionId}/videos/{videoId}/content")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<byte[]> content(
      @PathVariable String sessionId,
      @PathVariable Long videoId) {
    InterviewVideoService.VideoContent content = videoService.getAdminContent(sessionId, videoId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(content.mimeType()))
        .body(content.bytes());
  }

  @GetMapping("/api/admin/interviews/{sessionId}/videos/combined/content")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<StreamingResponseBody> combinedContent(
      @PathVariable String sessionId) {
    InterviewVideoService.CombinedVideo video = videoService.getAdminCombinedVideo(sessionId);
    StreamingResponseBody body = outputStream ->
        videoService.writeCombinedVideo(video, outputStream);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(video.mimeType()))
        .contentLength(video.fileSize())
        .header("X-Video-Duration-Ms", String.valueOf(video.durationMs()))
        .body(body);
  }
}
