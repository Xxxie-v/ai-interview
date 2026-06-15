package interview.guide.modules.auth.service;

import interview.guide.common.config.AuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.RefreshTokenEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final AuthProperties authProperties;
  private final RefreshTokenRepository refreshTokenRepository;

  public RefreshTokenService(
      AuthProperties authProperties,
      RefreshTokenRepository refreshTokenRepository) {
    this.authProperties = authProperties;
    this.refreshTokenRepository = refreshTokenRepository;
  }

  @Transactional
  public String create(UserEntity user) {
    byte[] bytes = new byte[48];
    SECURE_RANDOM.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    refreshTokenRepository.save(RefreshTokenEntity.builder()
        .tokenHash(hash(token))
        .user(user)
        .expiresAt(LocalDateTime.now().plus(authProperties.getRefreshTokenTtl()))
        .build());
    return token;
  }

  @Transactional
  public RefreshTokenEntity consume(String refreshToken) {
    LocalDateTime now = LocalDateTime.now();
    RefreshTokenEntity entity = refreshTokenRepository.findByTokenHash(hash(refreshToken))
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "Refresh Token 无效"));
    if (!entity.isActive(now)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "Refresh Token 已失效");
    }
    entity.setRevokedAt(now);
    refreshTokenRepository.save(entity);
    return entity;
  }

  @Transactional
  public void revoke(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }
    refreshTokenRepository.findByTokenHash(hash(refreshToken)).ifPresent(entity -> {
      entity.setRevokedAt(LocalDateTime.now());
      refreshTokenRepository.save(entity);
    });
  }

  @Transactional
  public void revokeAll(UserEntity user) {
    refreshTokenRepository.deleteByUser(user);
  }

  private String hash(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(bytes.length * 2);
      for (byte value : bytes) {
        result.append(String.format("%02x", value));
      }
      return result.toString();
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Refresh Token 处理失败", e);
    }
  }
}
