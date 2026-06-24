package interview.guide.modules.recruitment;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.recruitment.dto.InterviewAssignmentDTO;
import interview.guide.modules.recruitment.service.InterviewAssignmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/interviewee/assignments")
@PreAuthorize("hasRole('INTERVIEWEE')")
public class IntervieweeAssignmentController {

  private final InterviewAssignmentService assignmentService;

  @GetMapping
  public Result<List<InterviewAssignmentDTO>> list(
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(assignmentService.listForCandidate(principal.id()));
  }

  @GetMapping("/{assignmentId}")
  public Result<InterviewAssignmentDTO> get(
      @PathVariable Long assignmentId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(assignmentService.getForCandidate(assignmentId, principal.id()));
  }
}
