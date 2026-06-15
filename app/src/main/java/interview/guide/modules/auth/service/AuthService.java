package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.dto.CurrentUserDTO;
import interview.guide.modules.auth.dto.LoginRequest;
import interview.guide.modules.auth.dto.PhoneRegisterRequest;
import interview.guide.modules.auth.dto.RegisterRequest;
import interview.guide.modules.auth.dto.SmsLoginRequest;
import interview.guide.modules.auth.dto.TokenPairResponse;
import interview.guide.modules.auth.model.RefreshTokenEntity;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserIdentityEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.model.IdentityType;
import interview.guide.modules.auth.repository.RoleRepository;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.repository.UserIdentityRepository;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final RefreshTokenService refreshTokenService;
  private final AuthPrincipalFactory principalFactory;
  private final SmsVerificationService smsVerificationService;
  private final UserIdentityRepository userIdentityRepository;

  @Transactional
  public TokenPairResponse register(RegisterRequest request) {
    String username = request.username().trim();
    String email = trimOrNull(request.email());
    if (userRepository.existsByUsernameIgnoreCase(username)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
    }
    if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱已存在");
    }

    RoleEntity role = roleRepository.findById(AuthBootstrapService.ROLE_INTERVIEWEE)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "默认角色不存在"));

    UserEntity user = userRepository.save(UserEntity.builder()
        .username(username)
        .email(email)
        .passwordHash(passwordEncoder.encode(request.password()))
        .enabled(true)
        .status(UserStatus.ACTIVE)
        .roles(new HashSet<>(Set.of(role)))
        .build());
    return createTokenPair(user);
  }

  @Transactional
  public TokenPairResponse registerByPhone(PhoneRegisterRequest request) {
    String username = request.username().trim();
    String phone = request.phone().trim();
    if (userRepository.existsByUsernameIgnoreCase(username)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已存在");
    }
    if (userRepository.existsByPhone(phone)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号已注册");
    }
    smsVerificationService.verifyRegisterCode(phone, request.verifyCode().trim());

    RoleEntity role = roleRepository.findById(AuthBootstrapService.ROLE_INTERVIEWEE)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "默认角色不存在"));
    UserEntity user = userRepository.save(UserEntity.builder()
        .username(username)
        .phone(phone)
        .passwordHash(passwordEncoder.encode(request.password()))
        .enabled(true)
        .status(UserStatus.ACTIVE)
        .roles(new HashSet<>(Set.of(role)))
        .build());
    saveMobileIdentity(user, phone);
    return createTokenPair(user);
  }

  @Transactional
  public TokenPairResponse loginBySms(SmsLoginRequest request) {
    String mobile = request.mobile().trim();
    smsVerificationService.verifyLoginCode(mobile, request.code());
    UserEntity user = userRepository.findByPhone(mobile)
        .orElseGet(() -> createMobileUser(mobile));
    if (!user.isLoginAllowed()) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
    }
    if (!userIdentityRepository.existsByUserIdAndIdentityType(user.getId(), IdentityType.MOBILE)) {
      saveMobileIdentity(user, mobile);
    }
    return createTokenPair(user);
  }

  @Transactional
  public TokenPairResponse login(LoginRequest request) {
    UserEntity user = findByUsernameOrPhone(request.username().trim())
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));
    if (!user.isLoginAllowed() || user.getPasswordHash() == null
        || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
    }
    return createTokenPair(user);
  }

  @Transactional
  public TokenPairResponse refresh(String refreshToken) {
    RefreshTokenEntity consumed = refreshTokenService.consume(refreshToken);
    UserEntity user = consumed.getUser();
    if (!user.isLoginAllowed()) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已禁用");
    }
    return createTokenPair(user);
  }

  @Transactional
  public void logout(String refreshToken, AuthPrincipal principal) {
    if (refreshToken != null && !refreshToken.isBlank()) {
      refreshTokenService.revoke(refreshToken);
      return;
    }
    if (principal != null) {
      userRepository.findById(principal.id()).ifPresent(refreshTokenService::revokeAll);
    }
  }

  @Transactional(readOnly = true)
  public CurrentUserDTO currentUser(AuthPrincipal principal) {
    if (principal == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
    }
    UserEntity user = userRepository.findById(principal.id())
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在"));
    return toDTO(user);
  }

  private TokenPairResponse createTokenPair(UserEntity user) {
    String accessToken = jwtService.createAccessToken(user);
    String refreshToken = refreshTokenService.create(user);
    return new TokenPairResponse(
        accessToken,
        refreshToken,
        jwtService.accessTokenTtlSeconds(),
        toDTO(user));
  }

  public TokenPairResponse issueTokenPair(UserEntity user) {
    if (!user.isLoginAllowed()) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
    }
    return createTokenPair(user);
  }

  private Optional<UserEntity> findByUsernameOrPhone(String loginId) {
    return userRepository.findByUsernameIgnoreCase(loginId)
        .or(() -> userRepository.findByPhone(loginId));
  }

  private CurrentUserDTO toDTO(UserEntity user) {
    return new CurrentUserDTO(
        user.getId(),
        user.getUsername(),
        user.getNickname(),
        user.getAvatarUrl(),
        user.getEmail(),
        user.getPhone(),
        user.getStatus().name(),
        principalFactory.roleCodes(user),
        principalFactory.permissionCodes(user));
  }

  private UserEntity createMobileUser(String mobile) {
    RoleEntity role = roleRepository.findById(AuthBootstrapService.ROLE_INTERVIEWEE)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "默认角色不存在"));
    return userRepository.save(UserEntity.builder()
        .username("mobile_" + mobile.substring(7) + "_" + UUID.randomUUID().toString().substring(0, 8))
        .nickname("面试者" + mobile.substring(7))
        .phone(mobile)
        .enabled(true)
        .status(UserStatus.ACTIVE)
        .roles(new HashSet<>(Set.of(role)))
        .build());
  }

  private void saveMobileIdentity(UserEntity user, String mobile) {
    userIdentityRepository.save(UserIdentityEntity.builder()
        .user(user)
        .identityType(IdentityType.MOBILE)
        .identifier(mobile)
        .build());
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
