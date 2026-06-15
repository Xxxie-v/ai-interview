package interview.guide.modules.auth;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.CurrentUserDTO;
import interview.guide.modules.auth.dto.LoginRequest;
import interview.guide.modules.auth.dto.LogoutRequest;
import interview.guide.modules.auth.dto.PhoneRegisterRequest;
import interview.guide.modules.auth.dto.RefreshTokenRequest;
import interview.guide.modules.auth.dto.RegisterRequest;
import interview.guide.modules.auth.dto.SendSmsCodeRequest;
import interview.guide.modules.auth.dto.SendSmsCodeResponse;
import interview.guide.modules.auth.dto.SmsLoginRequest;
import interview.guide.modules.auth.dto.TokenPairResponse;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.service.AuthService;
import interview.guide.modules.auth.service.SmsVerificationService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthService authService;
  private final SmsVerificationService smsVerificationService;

  @PostMapping("/register")
  public Result<TokenPairResponse> register(@Valid @RequestBody RegisterRequest request) {
    return Result.success(authService.register(request));
  }

  @PostMapping("/sms/send")
  public Result<SendSmsCodeResponse> sendSmsCode(
      @Valid @RequestBody SendSmsCodeRequest request,
      HttpServletRequest servletRequest) {
    String debugCode = smsVerificationService.sendLoginCode(
        request.mobile(), resolveClientIp(servletRequest));
    return Result.success(new SendSmsCodeResponse(debugCode));
  }

  @PostMapping("/sms/login")
  public Result<TokenPairResponse> loginBySms(@Valid @RequestBody SmsLoginRequest request) {
    return Result.success(authService.loginBySms(request));
  }

  @PostMapping("/register/phone")
  public Result<TokenPairResponse> registerByPhone(@Valid @RequestBody PhoneRegisterRequest request) {
    return Result.success(authService.registerByPhone(request));
  }

  @PostMapping("/login")
  public Result<TokenPairResponse> login(@Valid @RequestBody LoginRequest request) {
    return Result.success(authService.login(request));
  }

  @PostMapping("/refresh")
  public Result<TokenPairResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return Result.success(authService.refresh(request.refreshToken()));
  }

  @PostMapping("/logout")
  public Result<Void> logout(
      @RequestBody(required = false) LogoutRequest request,
      @AuthenticationPrincipal AuthPrincipal principal) {
    authService.logout(request != null ? request.refreshToken() : null, principal);
    return Result.success();
  }

  @GetMapping("/me")
  public Result<CurrentUserDTO> me(@AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(authService.currentUser(principal));
  }

  private String resolveClientIp(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
