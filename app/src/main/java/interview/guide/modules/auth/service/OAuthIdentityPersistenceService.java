package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.IdentityType;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserIdentityEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.oauth.OAuthToken;
import interview.guide.modules.auth.oauth.ThirdPartyUserInfo;
import interview.guide.modules.auth.repository.RoleRepository;
import interview.guide.modules.auth.repository.UserIdentityRepository;
import interview.guide.modules.auth.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OAuthIdentityPersistenceService {

  private final UserIdentityRepository identityRepository;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final IdentityTokenCipher tokenCipher;

  @Transactional
  public UserEntity loginOrCreate(
      IdentityType provider,
      OAuthToken token,
      ThirdPartyUserInfo userInfo) {
    Optional<UserIdentityEntity> existing = findIdentity(provider, userInfo);
    if (existing.isPresent()) {
      UserIdentityEntity identity = existing.get();
      updateToken(identity, token);
      UserEntity user = identity.getUser();
      if (!user.isLoginAllowed()) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户已被禁用或锁定");
      }
      return user;
    }

    RoleEntity role = roleRepository.findById(AuthBootstrapService.ROLE_INTERVIEWEE)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "默认角色不存在"));
    UserEntity user = userRepository.save(UserEntity.builder()
        .username(provider.name().toLowerCase() + "_" + UUID.randomUUID().toString().replace("-", ""))
        .nickname(userInfo.nickname())
        .avatarUrl(userInfo.avatarUrl())
        .enabled(true)
        .status(UserStatus.ACTIVE)
        .roles(new HashSet<>(Set.of(role)))
        .build());
    identityRepository.save(buildIdentity(user, provider, token, userInfo));
    return user;
  }

  @Transactional
  public void bind(
      Long userId,
      IdentityType provider,
      OAuthToken token,
      ThirdPartyUserInfo userInfo) {
    UserEntity user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户不存在"));
    Optional<UserIdentityEntity> existing = findIdentity(provider, userInfo);
    if (existing.isPresent() && !existing.get().getUser().getId().equals(userId)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "该第三方账号已绑定其他用户");
    }
    UserIdentityEntity identity = existing.orElseGet(() ->
        buildIdentity(user, provider, token, userInfo));
    updateToken(identity, token);
    identityRepository.save(identity);
  }

  private Optional<UserIdentityEntity> findIdentity(
      IdentityType provider,
      ThirdPartyUserInfo userInfo) {
    if (provider == IdentityType.WECHAT
        && userInfo.unionId() != null && !userInfo.unionId().isBlank()) {
      Optional<UserIdentityEntity> byUnionId =
          identityRepository.findByIdentityTypeAndUnionId(provider, userInfo.unionId());
      if (byUnionId.isPresent()) {
        return byUnionId;
      }
    }
    return identityRepository.findByIdentityTypeAndIdentifier(provider, userInfo.identifier());
  }

  private UserIdentityEntity buildIdentity(
      UserEntity user,
      IdentityType provider,
      OAuthToken token,
      ThirdPartyUserInfo userInfo) {
    UserIdentityEntity identity = UserIdentityEntity.builder()
        .user(user)
        .identityType(provider)
        .identifier(userInfo.identifier())
        .unionId(userInfo.unionId())
        .build();
    updateToken(identity, token);
    return identity;
  }

  private void updateToken(UserIdentityEntity identity, OAuthToken token) {
    identity.setAccessTokenEncrypted(tokenCipher.encrypt(token.accessToken()));
    identity.setTokenExpireAt(LocalDateTime.now().plusSeconds(token.expiresInSeconds()));
  }
}
