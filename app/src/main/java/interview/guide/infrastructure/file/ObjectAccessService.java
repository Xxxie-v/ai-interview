package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ObjectAccessService {

  private static final Duration ACCESS_VALIDITY = Duration.ofMinutes(10);

  private final ObjectStorageService objectStorageService;
  private final StorageConfigProperties storageProperties;

  public ObjectAccessResponse createAccess(
      String objectKey,
      String mimeType,
      String protectedUrl) {
    if ("s3".equalsIgnoreCase(storageProperties.getProvider())
        && storageProperties.isDirectAccessEnabled()) {
      return new ObjectAccessResponse(
          objectStorageService.generatePresignedUrl(objectKey, ACCESS_VALIDITY),
          true,
          mimeType,
          Instant.now().plus(ACCESS_VALIDITY));
    }
    return new ObjectAccessResponse(
        protectedUrl,
        false,
        mimeType,
        Instant.now().plus(ACCESS_VALIDITY));
  }

  public byte[] download(String objectKey) {
    return objectStorageService.download(objectKey);
  }
}
