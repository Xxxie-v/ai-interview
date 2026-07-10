package interview.guide.modules.interview;

import interview.guide.common.result.Result;
import interview.guide.modules.interview.model.InterviewFlowStatus;
import interview.guide.modules.interview.service.InterviewStateMachineService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/interview-sessions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminInterviewSessionController {

  private final InterviewStateMachineService stateMachineService;

  @PostMapping("/{sessionId}/terminate")
  public Result<Void> terminate(@PathVariable String sessionId) {
    stateMachineService.transition(sessionId, InterviewFlowStatus.TERMINATED);
    return Result.success();
  }
}
