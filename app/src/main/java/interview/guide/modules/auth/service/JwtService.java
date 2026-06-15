package interview.guide.modules.auth.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.AuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.security.AuthPrincipalFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  public static final String TOKEN_TYPE_ACCESS = "access";

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

  private final AuthProperties authProperties;
  private final AuthPrincipalFactory principalFactory;
  private final ObjectMapper objectMapper;

  public JwtService(
      AuthProperties authProperties,
      AuthPrincipalFactory principalFactory,
      ObjectMapper objectMapper) {
    this.authProperties = authProperties;
    this.principalFactory = principalFactory;
    this.objectMapper = objectMapper;
  }

  public String createAccessToken(UserEntity user) {
    Instant now = Instant.now();
    Instant expiresAt = now.plus(authProperties.getAccessTokenTtl());
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", authProperties.getIssuer());
    claims.put("sub", String.valueOf(user.getId()));
    claims.put("username", user.getUsername());
    claims.put("roles", principalFactory.roleCodes(user));
    claims.put("permissions", principalFactory.permissionCodes(user));
    claims.put("type", TOKEN_TYPE_ACCESS);
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", expiresAt.getEpochSecond());
    claims.put("jti", UUID.randomUUID().toString());
    return sign(claims);
  }

  public JwtClaims verifyAccessToken(String token) {
    Map<String, Object> claims = verify(token);
    if (!TOKEN_TYPE_ACCESS.equals(asString(claims.get("type")))) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 类型无效");
    }
    return new JwtClaims(Long.parseLong(asString(claims.get("sub"))), asString(claims.get("username")));
  }

  public long accessTokenTtlSeconds() {
    return authProperties.getAccessTokenTtl().toSeconds();
  }

  private String sign(Map<String, Object> claims) {
    try {
      Map<String, String> header = Map.of("alg", "HS256", "typ", "JWT");
      String encodedHeader = encodeJson(header);
      String encodedPayload = encodeJson(claims);
      String signingInput = encodedHeader + "." + encodedPayload;
      return signingInput + "." + hmac(signingInput);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "生成 Token 失败", e);
    }
  }

  private Map<String, Object> verify(String token) {
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 3) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 格式无效");
      }
      String signingInput = parts[0] + "." + parts[1];
      String expected = hmac(signingInput);
      if (!constantTimeEquals(expected, parts[2])) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 签名无效");
      }
      Map<String, Object> claims = objectMapper.readValue(
          BASE64_URL_DECODER.decode(parts[1]), MAP_TYPE);
      if (!authProperties.getIssuer().equals(asString(claims.get("iss")))) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 签发方无效");
      }
      long expiresAt = asLong(claims.get("exp"));
      if (Instant.now().getEpochSecond() >= expiresAt) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 已过期");
      }
      return claims;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "Token 校验失败");
    }
  }

  private String encodeJson(Object value) throws Exception {
    return BASE64_URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(value));
  }

  private String hmac(String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    SecretKeySpec key = new SecretKeySpec(
        authProperties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    mac.init(key);
    return BASE64_URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  private boolean constantTimeEquals(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    if (leftBytes.length != rightBytes.length) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < leftBytes.length; i++) {
      result |= leftBytes[i] ^ rightBytes[i];
    }
    return result == 0;
  }

  private String asString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(asString(value));
  }

  public record JwtClaims(Long userId, String username) {
  }
}
