package interview.guide.infrastructure.file;

import java.time.Duration;
import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {

  StoredObject upload(String objectKey, MultipartFile file);

  String generatePresignedUrl(String objectKey, Duration validity);

  void delete(String objectKey);

  byte[] download(String objectKey);

  boolean exists(String objectKey);

  long size(String objectKey);

  void ensureReady();
}
