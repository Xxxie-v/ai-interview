package interview.guide.modules.auth;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.AdminUserDTO;
import interview.guide.modules.auth.dto.AdminUserPageDTO;
import interview.guide.modules.auth.dto.UpdateUserStatusRequest;
import interview.guide.modules.auth.dto.UpdateUnlimitedInterviewsRequest;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

  private final AdminUserService adminUserService;

  @GetMapping
  public Result<AdminUserPageDTO> listUsers(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.success(adminUserService.listUsers(page, size));
  }

  @PatchMapping("/{userId}/status")
  public Result<AdminUserDTO> updateStatus(
      @PathVariable Long userId,
      @Valid @RequestBody UpdateUserStatusRequest request,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(adminUserService.updateStatus(userId, request.status(), principal.id()));
  }

  @PatchMapping("/{userId}/unlimited-interviews")
  public Result<AdminUserDTO> updateUnlimitedInterviews(
      @PathVariable Long userId,
      @Valid @RequestBody UpdateUnlimitedInterviewsRequest request) {
    return Result.success(
        adminUserService.updateUnlimitedInterviews(userId, request.enabled()));
  }
}
