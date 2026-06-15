package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.dto.TokenPairResponse;
import interview.guide.modules.auth.model.IdentityType;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.oauth.OAuthToken;
import interview.guide.modules.auth.oauth.ThirdPartyOAuthClient;
import interview.guide.modules.auth.oauth.ThirdPartyUserInfo;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OAuthService {

  private final Map<IdentityType, ThirdPartyOAuthClient> clients;
  private final OAuthStateService stateService;
  private final OAuthIdentityPersistenceService persistenceService;
  private final AuthService authService;

  public OAuthService(
      List<ThirdPartyOAuthClient> clients,
      OAuthStateService stateService,
      OAuthIdentityPersistenceService persistenceService,
      AuthService authService) {
    this.clients = new EnumMap<>(IdentityType.class);
    clients.forEach(client -> this.clients.put(client.provider(), client));
    this.stateService = stateService;
    this.persistenceService = persistenceService;
    this.authService = authService;
  }

  public String buildAuthorizationUrl(IdentityType provider) {
    String state = stateService.create(provider);
    return client(provider).buildAuthorizationUrl(state);
  }

  public TokenPairResponse callback(
      IdentityType provider,
      String code,
      String state) {
    stateService.consume(state, provider);
    ThirdPartyOAuthClient client = client(provider);
    OAuthToken token = client.exchangeCode(code);
    ThirdPartyUserInfo userInfo = client.getUserInfo(token);
    UserEntity user = persistenceService.loginOrCreate(provider, token, userInfo);
    return authService.issueTokenPair(user);
  }

  public void bind(
      Long userId,
      IdentityType provider,
      String code,
      String state) {
    stateService.consume(state, provider);
    ThirdPartyOAuthClient client = client(provider);
    OAuthToken token = client.exchangeCode(code);
    ThirdPartyUserInfo userInfo = client.getUserInfo(token);
    persistenceService.bind(userId, provider, token, userInfo);
  }

  private ThirdPartyOAuthClient client(IdentityType provider) {
    ThirdPartyOAuthClient client = clients.get(provider);
    if (client == null || provider == IdentityType.MOBILE) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的 OAuth2 Provider");
    }
    return client;
  }
}
