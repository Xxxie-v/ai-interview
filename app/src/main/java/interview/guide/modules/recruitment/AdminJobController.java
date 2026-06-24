package interview.guide.modules.recruitment;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.recruitment.dto.CreateJobPositionRequest;
import interview.guide.modules.recruitment.dto.JobPositionDTO;
import interview.guide.modules.recruitment.dto.PagedResponseDTO;
import interview.guide.modules.recruitment.dto.UpdateJobPositionRequest;
import interview.guide.modules.recruitment.service.JobPositionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/jobs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminJobController {

  private final JobPositionService jobService;

  @PostMapping
  public Result<JobPositionDTO> create(
      @Valid @RequestBody CreateJobPositionRequest request,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(jobService.create(request, principal.id()));
  }

  @PutMapping("/{jobId}")
  public Result<JobPositionDTO> update(
      @PathVariable Long jobId,
      @Valid @RequestBody UpdateJobPositionRequest request) {
    return Result.success(jobService.update(jobId, request));
  }

  @DeleteMapping("/{jobId}")
  public Result<Void> delete(@PathVariable Long jobId) {
    jobService.delete(jobId);
    return Result.success();
  }

  @GetMapping("/{jobId}")
  public Result<JobPositionDTO> get(@PathVariable Long jobId) {
    return Result.success(jobService.get(jobId));
  }

  @GetMapping
  public Result<PagedResponseDTO<JobPositionDTO>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.success(jobService.list(page, size));
  }
}
