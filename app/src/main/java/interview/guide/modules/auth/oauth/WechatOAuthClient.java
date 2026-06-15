package interview.guide.modules.auth.oauth;

import interview.guide.common.config.OAuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.IdentityType;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class WechatOAuthClient implements ThirdPartyOAuthClient {

  private static final String DEFAULT_AUTHORIZE_URI =
      "https://open.weixin.qq.com/connect/qrconnect";
  private static final String DEFAULT_TOKEN_URI =
      "https://api.weixin.qq.com/sns/oauth2/access_token";
  private static final String DEFAULT_USER_INFO_URI =
      "https://api.weixin.qq.com/sns/userinfo";

  private final OAuthProperties.Provider properties;
  private final RestClient restClient;

  public WechatOAuthClient(OAuthProperties properties, RestClient.Builder builder) {
    this.properties = properties.getWechat();
    this.restClient = builder.build();
  }

  @Override
  public IdentityType provider() {
    return IdentityType.WECHAT;
  }

  @Override
  public String buildAuthorizationUrl(String state) {
    ensureConfigured();
    return UriComponentsBuilder.fromUriString(orDefault(
            properties.getAuthorizationUri(), DEFAULT_AUTHORIZE_URI))
        .queryParam("appid", properties.getClientId())
        .queryParam("redirect_uri", properties.getRedirectUri())
        .queryParam("response_type", "code")
        .queryParam("scope", "snsapi_login")
        .queryParam("state", state)
        .build(true)
        .toUriString() + "#wechat_redirect";
  }

  @Override
  public OAuthToken exchangeCode(String code) {
    ensureConfigured();
    String uri = UriComponentsBuilder.fromUriString(orDefault(
            properties.getTokenUri(), DEFAULT_TOKEN_URI))
            .queryParam("appid", properties.getClientId())
            .queryParam("secret", properties.getClientSecret())
            .queryParam("code", code)
            .queryParam("grant_type", "authorization_code")
            .build(true)
            .toUriString();
    Map<String, Object> response = restClient.get()
        .uri(uri)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    requireSuccess(response);
    return new OAuthToken(
        stringValue(response, "access_token"),
        longValue(response, "expires_in"),
        stringValue(response, "openid"));
  }

  @Override
  public ThirdPartyUserInfo getUserInfo(OAuthToken token) {
    String uri = UriComponentsBuilder.fromUriString(orDefault(
            properties.getUserInfoUri(), DEFAULT_USER_INFO_URI))
            .queryParam("access_token", token.accessToken())
            .queryParam("openid", token.identifierHint())
            .queryParam("lang", "zh_CN")
            .build(true)
            .toUriString();
    Map<String, Object> response = restClient.get()
        .uri(uri)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    requireSuccess(response);
    return new ThirdPartyUserInfo(
        stringValue(response, "openid"),
        nullableString(response, "unionid"),
        nullableString(response, "nickname"),
        nullableString(response, "headimgurl"));
  }

  private void ensureConfigured() {
    if (isBlank(properties.getClientId()) || isBlank(properties.getClientSecret())
        || isBlank(properties.getRedirectUri())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "微信 OAuth2 配置不完整");
    }
  }

  private void requireSuccess(Map<String, Object> response) {
    if (response == null || response.containsKey("errcode")) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "微信授权失败");
    }
  }

  private String stringValue(Map<String, Object> values, String key) {
    String value = nullableString(values, key);
    if (isBlank(value)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "微信授权响应缺少必要字段");
    }
    return value;
  }

  private String nullableString(Map<String, Object> values, String key) {
    Object value = values == null ? null : values.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private long longValue(Map<String, Object> values, String key) {
    Object value = values.get(key);
    return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
  }

  private String orDefault(String value, String fallback) {
    return isBlank(value) ? fallback : value;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
