package interview.guide.modules.interview.report;

import interview.guide.common.result.Result;
import interview.guide.modules.interview.report.model.EnterpriseInterviewReportDTO;
import interview.guide.modules.interview.report.service.EnterpriseInterviewReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/interviews")
@PreAuthorize("hasRole('ADMIN')")
public class AdminInterviewReportController {

  private final EnterpriseInterviewReportService reportService;

  @GetMapping("/{sessionId}/report")
  public Result<EnterpriseInterviewReportDTO> getReport(@PathVariable String sessionId) {
    log.info("Admin viewed enterprise interview report: sessionId={}", sessionId);
    return Result.success(reportService.getForAdmin(sessionId));
  }
}
