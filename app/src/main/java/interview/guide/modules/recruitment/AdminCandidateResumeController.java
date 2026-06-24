package interview.guide.modules.recruitment;

import interview.guide.common.result.Result;
import interview.guide.modules.recruitment.dto.CandidateResumeDTO;
import interview.guide.modules.recruitment.service.InterviewAssignmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users/{candidateId}/resumes")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCandidateResumeController {

  private final InterviewAssignmentService assignmentService;

  @GetMapping
  public Result<List<CandidateResumeDTO>> list(@PathVariable Long candidateId) {
    return Result.success(assignmentService.listCandidateResumes(candidateId));
  }
}
