package interview.guide.modules.system;

import interview.guide.common.result.Result;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/async-tasks")
@PreAuthorize("hasRole('ADMIN')")
public class AsyncTaskAdminController {

  private final AsyncTaskAdminService asyncTaskAdminService;

  @GetMapping
  public Result<List<AsyncTaskAdminService.PipelineStatusResponse>> listStatus() {
    return Result.success(asyncTaskAdminService.listStatus());
  }

  @GetMapping("/{pipelineId}/dead-letters")
  public Result<List<AsyncTaskAdminService.DeadLetterResponse>> listDeadLetters(
      @PathVariable String pipelineId,
      @RequestParam(defaultValue = "20") int limit) {
    return Result.success(asyncTaskAdminService.listDeadLetters(pipelineId, limit));
  }

  @PostMapping("/{pipelineId}/dead-letters/{messageId}/replay")
  public Result<AsyncTaskAdminService.ReplayResponse> replay(
      @PathVariable String pipelineId,
      @PathVariable String messageId) {
    return Result.success(asyncTaskAdminService.replay(pipelineId, messageId));
  }
}
