package interview.guide.modules.auth.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试资源归属校验")
class InterviewPermissionServiceTest {

  @Mock
  private InterviewSessionRepository interviewRepository;
  @Mock
  private VoiceInterviewSessionRepository voiceRepository;

  private InterviewPermissionService service;
  private Authentication authentication;

  @BeforeEach
  void setUp() {
    service = new InterviewPermissionService(interviewRepository, voiceRepository);
    AuthPrincipal principal = new AuthPrincipal(
        100L, "candidate", null, true,
        List.of(new SimpleGrantedAuthority("ROLE_INTERVIEWEE")));
    authentication = new UsernamePasswordAuthenticationToken(
        principal, null, principal.getAuthorities());
  }

  @Test
  @DisplayName("本人会话允许访问")
  void allowsOwner() {
    when(interviewRepository.findBySessionIdAndOwnerUserId("session-1", 100L))
        .thenReturn(Optional.of(new InterviewSessionEntity()));

    assertThat(service.isOwner("session-1", authentication)).isTrue();
  }

  @Test
  @DisplayName("修改 sessionId 访问他人会话会被拒绝")
  void rejectsIdor() {
    when(interviewRepository.findBySessionIdAndOwnerUserId("other-session", 100L))
        .thenReturn(Optional.empty());

    assertThat(service.isOwner("other-session", authentication)).isFalse();
  }
}
