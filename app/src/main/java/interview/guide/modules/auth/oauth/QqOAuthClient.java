package interview.guide.modules.auth.oauth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.OAuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.IdentityType;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class QqOAuthClient implements ThirdPartyOAuthClient {

  private static final String DEFAULT_AUTHORIZE_URI = "https://graph.qq.com/oauth2.0/authorize";
  private static final String DEFAULT_TOKEN_URI = "https://graph.qq.com/oauth2.0/token";
  private static final String DEFAULT_OPEN_ID_URI = "https://graph.qq.com/oauth2.0/me";
  private static final String DEFAULT_USER_INFO_URI = "https://graph.qq.com/user/get_user_info";

  private final OAuthProperties.Provider properties;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public QqOAuthClient(
      OAuthProperties properties,
      RestClient.Builder builder,
      ObjectMapper objectMapper) {
    this.properties = properties.getQq();
    this.restClient = builder.build();
    this.objectMapper = objectMapper;
  }

  @Override
  public IdentityType provider() {
    return IdentityType.QQ;
  }

  @Override
  public String buildAuthorizationUrl(String state) {
    ensureConfigured();
    return UriComponentsBuilder.fromUriString(orDefault(
            properties.getAuthorizationUri(), DEFAULT_AUTHORIZE_URI))
        .queryParam("response_type", "code")
        .queryParam("client_id", properties.getClientId())
        .queryParam("redirect_uri", properties.getRedirectUri())
        .queryParam("state", state)
        .queryParam("scope", "get_user_info")
        .build(true)
        .toUriString();
  }

  @Override
  public OAuthToken exchangeCode(String code) {
    ensureConfigured();
    String uri = UriComponentsBuilder.fromUriString(orDefault(properties.getTokenUri(), DEFAULT_TOKEN_URI))
        .queryParam("grant_type", "authorization_code")
        .queryParam("client_id", properties.getClientId())
        .queryParam("client_secret", properties.getClientSecret())
        .queryParam("code", code)
        .queryParam("redirect_uri", properties.getRedirectUri())
        .queryParam("fmt", "json")
        .build(true)
        .toUriString();
    String body = restClient.get().uri(uri).retrieve().body(String.class);
    Map<String, String> values = parseTokenResponse(body);
    String accessToken = values.get("access_token");
    if (isBlank(accessToken)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "QQ 授权失败");
    }
    long expiresIn = Long.parseLong(values.getOrDefault("expires_in", "0"));
    return new OAuthToken(accessToken, expiresIn, null);
  }

  @Override
  public ThirdPartyUserInfo getUserInfo(OAuthToken token) {
    String openId = getOpenId(token.accessToken());
    String uri = UriComponentsBuilder.fromUriString(orDefault(
            properties.getUserInfoUri(), DEFAULT_USER_INFO_URI))
        .queryParam("access_token", token.accessToken())
        .queryParam("oauth_consumer_key", properties.getClientId())
        .queryParam("openid", openId)
        .build(true)
        .toUriString();
    Map<String, Object> response = restClient.get()
        .uri(uri)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    if (response == null || !"0".equals(String.valueOf(response.get("ret")))) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "QQ 用户信息获取失败");
    }
    return new ThirdPartyUserInfo(
        openId,
        null,
        nullableString(response, "nickname"),
        nullableString(response, "figureurl_qq_2"));
  }

  private String getOpenId(String accessToken) {
    String uri = UriComponentsBuilder.fromUriString(orDefault(
            properties.getOpenIdUri(), DEFAULT_OPEN_ID_URI))
        .queryParam("access_token", accessToken)
        .queryParam("fmt", "json")
        .build(true)
        .toUriString();
    String body = restClient.get().uri(uri).retrieve().body(String.class);
    try {
      int start = body == null ? -1 : body.indexOf('{');
      int end = body == null ? -1 : body.lastIndexOf('}');
      if (start < 0 || end <= start) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "QQ OpenID 获取失败");
      }
      Map<String, Object> values = objectMapper.readValue(
          body.substring(start, end + 1), new TypeReference<>() {});
      String openId = nullableString(values, "openid");
      if (isBlank(openId)) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "QQ OpenID 获取失败");
      }
      return openId;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "QQ OpenID 获取失败", e);
    }
  }

  private Map<String, String> parseTokenResponse(String body) {
    if (body == null) {
      return Map.of();
    }
    if (body.trim().startsWith("{")) {
      try {
        Map<String, Object> json = objectMapper.readValue(body, new TypeReference<>() {});
        return json.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey, entry -> String.valueOf(entry.getValue())));
      } catch (Exception e) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "QQ Token 响应无效", e);
      }
    }
    return Arrays.stream(body.split("&"))
        .map(item -> item.split("=", 2))
        .filter(parts -> parts.length == 2)
        .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
  }

  private void ensureConfigured() {
    if (isBlank(properties.getClientId()) || isBlank(properties.getClientSecret())
        || isBlank(properties.getRedirectUri())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "QQ OAuth2 配置不完整");
    }
  }

  private String nullableString(Map<String, Object> values, String key) {
    Object value = values.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private String orDefault(String value, String fallback) {
    return isBlank(value) ? fallback : value;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
