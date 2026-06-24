package interview.guide.modules.recruitment;

import interview.guide.common.result.Result;
import interview.guide.modules.recruitment.dto.JobPositionDTO;
import interview.guide.modules.recruitment.service.JobPositionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/interviewee/jobs")
@PreAuthorize("hasRole('INTERVIEWEE')")
public class IntervieweeJobController {

  private final JobPositionService jobService;

  @GetMapping
  public Result<List<JobPositionDTO>> listActiveJobs() {
    return Result.success(jobService.listActive());
  }
}
