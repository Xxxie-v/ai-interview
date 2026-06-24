package interview.guide.modules.recruitment;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.recruitment.dto.CreateInterviewAssignmentRequest;
import interview.guide.modules.recruitment.dto.InterviewAssignmentDTO;
import interview.guide.modules.recruitment.dto.PagedResponseDTO;
import interview.guide.modules.recruitment.service.InterviewAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/interview-assignments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminInterviewAssignmentController {

  private final InterviewAssignmentService assignmentService;

  @PostMapping
  public Result<InterviewAssignmentDTO> create(
      @Valid @RequestBody CreateInterviewAssignmentRequest request,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(assignmentService.create(request, principal.id()));
  }

  @GetMapping
  public Result<PagedResponseDTO<InterviewAssignmentDTO>> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.success(assignmentService.listForAdmin(page, size));
  }

  @GetMapping("/{assignmentId}")
  public Result<InterviewAssignmentDTO> get(@PathVariable Long assignmentId) {
    return Result.success(assignmentService.getForAdmin(assignmentId));
  }
}
