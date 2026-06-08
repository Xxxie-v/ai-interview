package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.io.IOException;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
public class S3ObjectStorageService implements ObjectStorageService {

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final StorageConfigProperties storageConfig;

  @Override
  public StoredObject upload(String objectKey, MultipartFile file) {
    try {
      PutObjectRequest request = PutObjectRequest.builder()
          .bucket(storageConfig.getBucket())
          .key(objectKey)
          .contentType(file.getContentType())
          .contentLength(file.getSize())
          .build();
      s3Client.putObject(
          request,
          RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
      return new StoredObject(objectKey, file.getContentType(), file.getSize());
    } catch (IOException e) {
      log.error("Read object upload failed: objectKey={}", objectKey, e);
      throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "读取视频分片失败", e);
    } catch (S3Exception e) {
      log.error("Object upload failed: objectKey={}", objectKey, e);
      throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "视频分片存储失败", e);
    }
  }

  @Override
  public String generatePresignedUrl(String objectKey, Duration validity) {
    GetObjectRequest request = GetObjectRequest.builder()
        .bucket(storageConfig.getBucket())
        .key(objectKey)
        .build();
    GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
        .signatureDuration(validity)
        .getObjectRequest(request)
        .build();
    return s3Presigner.presignGetObject(presignRequest).url().toString();
  }

  @Override
  public void delete(String objectKey) {
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder()
          .bucket(storageConfig.getBucket())
          .key(objectKey)
          .build());
    } catch (S3Exception e) {
      log.error("Object deletion failed: objectKey={}", objectKey, e);
      throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "视频分片删除失败", e);
    }
  }

  @Override
  public byte[] download(String objectKey) {
    try {
      GetObjectRequest request = GetObjectRequest.builder()
          .bucket(storageConfig.getBucket())
          .key(objectKey)
          .build();
      return s3Client.getObjectAsBytes(request).asByteArray();
    } catch (S3Exception e) {
      log.error("Object download failed: objectKey={}", objectKey, e);
      throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件下载失败", e);
    }
  }

  @Override
  public boolean exists(String objectKey) {
    try {
      s3Client.headObject(builder -> builder
          .bucket(storageConfig.getBucket())
          .key(objectKey));
      return true;
    } catch (S3Exception e) {
      return false;
    }
  }

  @Override
  public long size(String objectKey) {
    try {
      return s3Client.headObject(builder -> builder
          .bucket(storageConfig.getBucket())
          .key(objectKey)).contentLength();
    } catch (S3Exception e) {
      throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "获取文件信息失败", e);
    }
  }

  @Override
  public void ensureReady() {
    try {
      s3Client.headBucket(builder -> builder.bucket(storageConfig.getBucket()));
    } catch (S3Exception e) {
      s3Client.createBucket(builder -> builder.bucket(storageConfig.getBucket()));
    }
  }
}
