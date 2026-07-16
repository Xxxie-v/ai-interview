package interview.guide.modules.voiceinterview.config;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import interview.guide.modules.auth.service.JwtService;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class VoiceInterviewHandshakeInterceptor implements HandshakeInterceptor {

  public static final String PRINCIPAL_ATTRIBUTE = "authPrincipal";

  private final JwtService jwtService;
  private final UserRepository userRepository;
  private final AuthPrincipalFactory principalFactory;
  private final VoiceInterviewSessionRepository sessionRepository;

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    try {
      URI uri = request.getURI();
      String token = UriComponentsBuilder.fromUri(uri).build()
          .getQueryParams().getFirst("access_token");
      if (token == null || token.isBlank()) {
        return reject(response);
      }
      JwtService.JwtClaims claims = jwtService.verifyAccessToken(token);
      UserEntity user = userRepository.findById(claims.userId()).orElse(null);
      if (user == null || !user.isLoginAllowed()) {
        return reject(response);
      }
      Long sessionId = extractSessionId(uri.getPath());
      AuthPrincipal principal = principalFactory.fromUser(user);
      boolean admin = principal.getAuthorities().stream()
          .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
      boolean owner = sessionRepository.findByIdAndUserId(
          sessionId, String.valueOf(user.getId())).isPresent();
      if (!admin && !owner) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
      }
      attributes.put(PRINCIPAL_ATTRIBUTE, principal);
      return true;
    } catch (Exception e) {
      return reject(response);
    }
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {
  }

  private Long extractSessionId(String path) {
    int separator = path.lastIndexOf('/');
    return Long.parseLong(path.substring(separator + 1));
  }

  private boolean reject(ServerHttpResponse response) {
    response.setStatusCode(HttpStatus.UNAUTHORIZED);
    return false;
  }
}
