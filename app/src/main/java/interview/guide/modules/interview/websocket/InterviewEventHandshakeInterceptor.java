package interview.guide.modules.interview.websocket;

import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import interview.guide.modules.auth.service.JwtService;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
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
public class InterviewEventHandshakeInterceptor implements HandshakeInterceptor {

  public static final String PRINCIPAL_ATTRIBUTE = "authPrincipal";
  public static final String LAST_SEQUENCE_ATTRIBUTE = "lastSequence";
  public static final String MANAGEMENT_MODE_ATTRIBUTE = "managementMode";

  private final JwtService jwtService;
  private final UserRepository userRepository;
  private final AuthPrincipalFactory principalFactory;
  private final InterviewSessionRepository sessionRepository;

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    try {
      URI uri = request.getURI();
      var query = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
      String token = query.getFirst("access_token");
      if (token == null || token.isBlank()) {
        return reject(response, HttpStatus.UNAUTHORIZED);
      }
      JwtService.JwtClaims claims = jwtService.verifyAccessToken(token);
      UserEntity user = userRepository.findById(claims.userId()).orElse(null);
      if (user == null || !user.isLoginAllowed()) {
        return reject(response, HttpStatus.UNAUTHORIZED);
      }

      String sessionId = extractSessionId(uri.getPath());
      AuthPrincipal principal = principalFactory.fromUser(user);
      boolean admin = principal.getAuthorities().stream()
          .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
      boolean owner = sessionRepository
          .findBySessionIdAndOwnerUserId(sessionId, user.getId())
          .isPresent();
      if (!admin && !owner) {
        return reject(response, HttpStatus.FORBIDDEN);
      }

      attributes.put(PRINCIPAL_ATTRIBUTE, principal);
      attributes.put(LAST_SEQUENCE_ATTRIBUTE, parseLastSequence(query.getFirst("lastSequence")));
      attributes.put(MANAGEMENT_MODE_ATTRIBUTE, admin && !owner);
      return true;
    } catch (Exception e) {
      return reject(response, HttpStatus.UNAUTHORIZED);
    }
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {
  }

  private String extractSessionId(String path) {
    int separator = path.lastIndexOf('/');
    String sessionId = path.substring(separator + 1);
    if (sessionId.isBlank() || sessionId.length() > 36) {
      throw new IllegalArgumentException("Invalid session ID");
    }
    return sessionId;
  }

  private long parseLastSequence(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0L;
    }
    return Math.max(0L, Long.parseLong(raw));
  }

  private boolean reject(ServerHttpResponse response, HttpStatus status) {
    response.setStatusCode(status);
    return false;
  }
}
