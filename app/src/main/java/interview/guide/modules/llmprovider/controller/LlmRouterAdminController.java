package interview.guide.modules.llmprovider.controller;

import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.common.ai.routing.LlmTaskRouter.ProviderStateSnapshot;
import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/llm-router")
@PreAuthorize("hasRole('ADMIN')")
public class LlmRouterAdminController {

  private final LlmTaskRouter taskRouter;

  @GetMapping("/status")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 30)
  public Result<Map<String, ProviderStateSnapshot>> getStatus() {
    return Result.success(taskRouter.snapshots());
  }
}
