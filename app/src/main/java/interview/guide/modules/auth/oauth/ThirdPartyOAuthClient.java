package interview.guide.modules.auth.oauth;

import interview.guide.modules.auth.model.IdentityType;

public interface ThirdPartyOAuthClient {

  IdentityType provider();

  String buildAuthorizationUrl(String state);

  OAuthToken exchangeCode(String code);

  ThirdPartyUserInfo getUserInfo(OAuthToken token);
}
