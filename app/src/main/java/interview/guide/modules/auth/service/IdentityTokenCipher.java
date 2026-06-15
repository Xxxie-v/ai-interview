package interview.guide.modules.auth.service;

import interview.guide.common.config.AuthProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class IdentityTokenCipher {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH = 128;

  private final SecretKeySpec key;

  public IdentityTokenCipher(AuthProperties properties) {
    String rawKey = properties.getIdentityEncryptionKey();
    if (rawKey == null || rawKey.isBlank()) {
      rawKey = properties.getSecret();
    }
    try {
      byte[] keyBytes = MessageDigest.getInstance("SHA-256")
          .digest(rawKey.getBytes(StandardCharsets.UTF_8));
      this.key = new SecretKeySpec(keyBytes, "AES");
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "身份 Token 加密初始化失败", e);
    }
  }

  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[IV_LENGTH];
      SECURE_RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] result = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, result, 0, iv.length);
      System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(result);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "身份 Token 加密失败", e);
    }
  }
}
