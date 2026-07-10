package interview.guide.modules.interview.report;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.interview.report.model.EnterpriseInterviewReportDTO;
import interview.guide.modules.interview.report.service.EnterpriseInterviewReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/interviews")
@PreAuthorize("hasRole('INTERVIEWEE')")
public class IntervieweeReportController {

  private final EnterpriseInterviewReportService reportService;

  @GetMapping("/{sessionId}/report")
  public Result<EnterpriseInterviewReportDTO> getReport(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(reportService.getForCandidate(sessionId, principal.id()));
  }
}
