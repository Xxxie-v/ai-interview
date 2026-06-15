package interview.guide.modules.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.dto.OAuthAuthorizeResponse;
import interview.guide.modules.auth.dto.OAuthBindRequest;
import interview.guide.modules.auth.dto.TokenPairResponse;
import interview.guide.modules.auth.model.IdentityType;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.service.OAuthService;
import jakarta.validation.Valid;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/auth/oauth2")
public class OAuthController {

  private final OAuthService oauthService;

  @GetMapping("/{provider}/authorize")
  public Result<OAuthAuthorizeResponse> authorize(@PathVariable String provider) {
    IdentityType type = parseProvider(provider);
    return Result.success(new OAuthAuthorizeResponse(oauthService.buildAuthorizationUrl(type)));
  }

  @GetMapping("/{provider}/callback")
  public Result<TokenPairResponse> callback(
      @PathVariable String provider,
      @RequestParam String code,
      @RequestParam String state) {
    return Result.success(oauthService.callback(parseProvider(provider), code, state));
  }

  @PostMapping("/{provider}/bind")
  public Result<Void> bind(
      @PathVariable String provider,
      @Valid @RequestBody OAuthBindRequest request,
      @AuthenticationPrincipal AuthPrincipal principal) {
    if (principal == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    }
    oauthService.bind(principal.id(), parseProvider(provider), request.code(), request.state());
    return Result.success();
  }

  private IdentityType parseProvider(String provider) {
    try {
      IdentityType type = IdentityType.valueOf(provider.toUpperCase(Locale.ROOT));
      if (type == IdentityType.MOBILE) {
        throw new IllegalArgumentException();
      }
      return type;
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持 wechat 或 qq");
    }
  }
}
