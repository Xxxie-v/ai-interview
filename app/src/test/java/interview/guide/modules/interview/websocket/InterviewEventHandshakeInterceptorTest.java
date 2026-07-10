package interview.guide.modules.interview.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import interview.guide.modules.auth.service.JwtService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.WebSocketHandler;

@ExtendWith(MockitoExtension.class)
@DisplayName("正式面试 WebSocket 握手认证")
class InterviewEventHandshakeInterceptorTest {

  @Mock
  private JwtService jwtService;
  @Mock
  private UserRepository userRepository;
  @Mock
  private AuthPrincipalFactory principalFactory;
  @Mock
  private InterviewSessionRepository sessionRepository;
  @Mock
  private ServerHttpRequest request;
  @Mock
  private ServerHttpResponse response;
  @Mock
  private WebSocketHandler handler;

  private InterviewEventHandshakeInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor = new InterviewEventHandshakeInterceptor(
        jwtService,
        userRepository,
        principalFactory,
        sessionRepository);
  }

  @Test
  @DisplayName("未携带访问令牌时拒绝连接")
  void rejectsMissingToken() {
    when(request.getURI()).thenReturn(URI.create("ws://localhost/ws/interviews/session-1"));

    boolean accepted = interceptor.beforeHandshake(
        request,
        response,
        handler,
        new java.util.HashMap<>());

    assertThat(accepted).isFalse();
    verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("候选人不能连接其他用户的面试会话")
  void rejectsNonOwner() {
    UserEntity user = mock(UserEntity.class);
    AuthPrincipal principal = new AuthPrincipal(
        20L,
        "candidate",
        "",
        true,
        new ArrayList<>(java.util.List.of(new SimpleGrantedAuthority("ROLE_INTERVIEWEE"))));
    when(request.getURI()).thenReturn(URI.create(
        "ws://localhost/ws/interviews/session-1?access_token=token&lastSequence=3"));
    when(jwtService.verifyAccessToken("token"))
        .thenReturn(new JwtService.JwtClaims(20L, "candidate"));
    when(userRepository.findById(20L)).thenReturn(Optional.of(user));
    when(user.isLoginAllowed()).thenReturn(true);
    when(user.getId()).thenReturn(20L);
    when(principalFactory.fromUser(user)).thenReturn(principal);
    when(sessionRepository.findBySessionIdAndOwnerUserId("session-1", 20L))
        .thenReturn(Optional.empty());

    boolean accepted = interceptor.beforeHandshake(
        request,
        response,
        handler,
        new java.util.HashMap<>());

    assertThat(accepted).isFalse();
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("会话所有者可以连接并携带断线续传序号")
  void acceptsOwnerAndStoresLastSequence() {
    UserEntity user = mock(UserEntity.class);
    AuthPrincipal principal = new AuthPrincipal(
        20L,
        "candidate",
        "",
        true,
        java.util.List.of(new SimpleGrantedAuthority("ROLE_INTERVIEWEE")));
    when(request.getURI()).thenReturn(URI.create(
        "ws://localhost/ws/interviews/session-1?access_token=token&lastSequence=3"));
    when(jwtService.verifyAccessToken("token"))
        .thenReturn(new JwtService.JwtClaims(20L, "candidate"));
    when(userRepository.findById(20L)).thenReturn(Optional.of(user));
    when(user.isLoginAllowed()).thenReturn(true);
    when(user.getId()).thenReturn(20L);
    when(principalFactory.fromUser(user)).thenReturn(principal);
    when(sessionRepository.findBySessionIdAndOwnerUserId("session-1", 20L))
        .thenReturn(Optional.of(new InterviewSessionEntity()));
    Map<String, Object> attributes = new java.util.HashMap<>();

    boolean accepted = interceptor.beforeHandshake(
        request,
        response,
        handler,
        attributes);

    assertThat(accepted).isTrue();
    assertThat(attributes)
        .containsEntry(InterviewEventHandshakeInterceptor.PRINCIPAL_ATTRIBUTE, principal)
        .containsEntry(InterviewEventHandshakeInterceptor.LAST_SEQUENCE_ATTRIBUTE, 3L);
  }
}
