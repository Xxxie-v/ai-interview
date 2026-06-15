package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.RoleRepository;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("管理员账号状态管理")
class AdminUserServiceTest {

  @Mock
  private UserRepository userRepository;
  @Mock
  private RoleRepository roleRepository;
  @Mock
  private RefreshTokenService refreshTokenService;

  private AdminUserService service;

  @BeforeEach
  void setUp() {
    service = new AdminUserService(
        userRepository,
        roleRepository,
        refreshTokenService,
        new AuthPrincipalFactory());
  }

  @Test
  @DisplayName("禁用账号时同步清除刷新令牌")
  void disablesUserAndRevokesRefreshTokens() {
    UserEntity user = user(2L);
    when(userRepository.findById(2L)).thenReturn(Optional.of(user));
    when(userRepository.save(user)).thenReturn(user);

    var result = service.updateStatus(2L, UserStatus.DISABLED, 1L);

    assertThat(result.status()).isEqualTo("DISABLED");
    assertThat(user.isEnabled()).isFalse();
    verify(refreshTokenService).revokeAll(user);
  }

  @Test
  @DisplayName("管理员不能禁用自己的账号")
  void rejectsDisablingSelf() {
    assertThatThrownBy(() -> service.updateStatus(1L, UserStatus.DISABLED, 1L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能禁用");
  }

  @Test
  @DisplayName("候选人可以被标记为无限面试测试账号")
  void enablesUnlimitedInterviewsForInterviewee() {
    UserEntity user = user(2L);
    RoleEntity testRole = RoleEntity.builder()
        .code(AuthBootstrapService.ROLE_TEST_INTERVIEWEE)
        .name("测试候选人")
        .build();
    when(userRepository.findById(2L)).thenReturn(Optional.of(user));
    when(roleRepository.findById(AuthBootstrapService.ROLE_TEST_INTERVIEWEE))
        .thenReturn(Optional.of(testRole));
    when(userRepository.save(user)).thenReturn(user);

    var result = service.updateUnlimitedInterviews(2L, true);

    assertThat(result.unlimitedInterviews()).isTrue();
    assertThat(result.roles()).contains(
        AuthBootstrapService.ROLE_INTERVIEWEE,
        AuthBootstrapService.ROLE_TEST_INTERVIEWEE);
  }

  private UserEntity user(Long id) {
    RoleEntity role = RoleEntity.builder()
        .code(AuthBootstrapService.ROLE_INTERVIEWEE)
        .name("面试者")
        .build();
    return UserEntity.builder()
        .id(id)
        .username("user" + id)
        .enabled(true)
        .status(UserStatus.ACTIVE)
        .roles(new HashSet<>(Set.of(role)))
        .createdAt(LocalDateTime.now())
        .updatedAt(LocalDateTime.now())
        .build();
  }
}
