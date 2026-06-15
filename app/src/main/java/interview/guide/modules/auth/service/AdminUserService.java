package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.dto.AdminUserDTO;
import interview.guide.modules.auth.dto.AdminUserPageDTO;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.RoleRepository;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {

  private static final int MAX_PAGE_SIZE = 100;

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final RefreshTokenService refreshTokenService;
  private final AuthPrincipalFactory principalFactory;

  @Transactional(readOnly = true)
  public AdminUserPageDTO listUsers(int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    Page<UserEntity> users = userRepository.findAll(PageRequest.of(
        safePage,
        safeSize,
        Sort.by(Sort.Direction.DESC, "createdAt")));
    return new AdminUserPageDTO(
        users.getContent().stream().map(this::toDTO).toList(),
        users.getTotalElements(),
        users.getTotalPages(),
        safePage,
        safeSize);
  }

  @Transactional
  public AdminUserDTO updateStatus(Long userId, UserStatus status, Long operatorId) {
    if (userId.equals(operatorId) && status != UserStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员不能禁用或锁定自己的账号");
    }
    UserEntity user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    user.setStatus(status);
    user.setEnabled(status == UserStatus.ACTIVE);
    UserEntity saved = userRepository.save(user);
    if (status != UserStatus.ACTIVE) {
      refreshTokenService.revokeAll(saved);
    }
    return toDTO(saved);
  }

  @Transactional
  public AdminUserDTO updateUnlimitedInterviews(Long userId, boolean enabled) {
    UserEntity user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    boolean interviewee = user.getRoles().stream()
        .anyMatch(role -> AuthBootstrapService.ROLE_INTERVIEWEE.equals(role.getCode()));
    if (!interviewee) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "只有候选人账号可以设为测试账号");
    }
    if (enabled) {
      var testRole = roleRepository.findById(AuthBootstrapService.ROLE_TEST_INTERVIEWEE)
          .orElseThrow(() -> new BusinessException(
              ErrorCode.INTERNAL_ERROR,
              "测试账号角色不存在"));
      user.getRoles().add(testRole);
    } else {
      user.getRoles().removeIf(
          role -> AuthBootstrapService.ROLE_TEST_INTERVIEWEE.equals(role.getCode()));
    }
    return toDTO(userRepository.save(user));
  }

  private AdminUserDTO toDTO(UserEntity user) {
    return new AdminUserDTO(
        user.getId(),
        user.getUsername(),
        user.getNickname(),
        user.getEmail(),
        user.getPhone(),
        user.getStatus().name(),
        user.getRoles().stream().anyMatch(
            role -> AuthBootstrapService.ROLE_TEST_INTERVIEWEE.equals(role.getCode())),
        principalFactory.roleCodes(user),
        user.getCreatedAt());
  }
}
