package interview.guide.modules.interview.vision;

import interview.guide.common.result.Result;
import interview.guide.infrastructure.file.ObjectAccessResponse;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.interview.report.model.InterviewViolationConclusion;
import interview.guide.modules.interview.report.service.InterviewViolationAssessmentService;
import interview.guide.modules.interview.vision.model.InterviewVisionEventDTO;
import interview.guide.modules.interview.vision.service.InterviewVisionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminInterviewVisionController {

  private final InterviewVisionService visionService;
  private final InterviewViolationAssessmentService violationAssessmentService;

  @GetMapping("/api/admin/interviews/{sessionId}/vision-events")
  @PreAuthorize("hasRole('ADMIN')")
  public Result<List<InterviewVisionEventDTO>> listEvents(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    log.info("Admin viewed interview vision events: adminId={}, sessionId={}",
        principal.id(), sessionId);
    return Result.success(visionService.listForAdmin(sessionId));
  }

  @GetMapping("/api/admin/interviews/{sessionId}/violation-conclusion")
  @PreAuthorize("hasRole('ADMIN')")
  public Result<InterviewViolationConclusion> getViolationConclusion(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    log.info("Admin viewed interview violation conclusion: adminId={}, sessionId={}",
        principal.id(), sessionId);
    return Result.success(
        violationAssessmentService.assess(visionService.listForAdmin(sessionId)));
  }

  @GetMapping("/api/admin/interviews/{sessionId}/vision-events/{eventId}/access")
  @PreAuthorize("hasRole('ADMIN')")
  public Result<ObjectAccessResponse> createEvidenceAccess(
      @PathVariable String sessionId,
      @PathVariable Long eventId) {
    return Result.success(visionService.createAdminEvidenceAccess(sessionId, eventId));
  }

  @GetMapping("/api/admin/interviews/{sessionId}/vision-events/{eventId}/content")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<byte[]> evidenceContent(
      @PathVariable String sessionId,
      @PathVariable Long eventId) {
    InterviewVisionService.EvidenceContent content =
        visionService.getAdminEvidenceContent(sessionId, eventId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(content.mimeType()))
        .body(content.bytes());
  }
}
