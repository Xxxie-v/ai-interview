package interview.guide.infrastructure.file;

import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@ConditionalOnProperty(
    name = "app.storage.provider",
    havingValue = "local",
    matchIfMissing = true)
public class LocalObjectStorageService implements ObjectStorageService {

  private final Path root;

  public LocalObjectStorageService(StorageConfigProperties properties) {
    this.root = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
  }

  @Override
  public StoredObject upload(String objectKey, MultipartFile file) {
    Path target = resolve(objectKey);
    try {
      Files.createDirectories(target.getParent());
      try (var inputStream = file.getInputStream()) {
        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return new StoredObject(objectKey, file.getContentType(), file.getSize());
    } catch (IOException e) {
      log.error("Local object upload failed: objectKey={}", objectKey, e);
      throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "本地文件存储失败", e);
    }
  }

  @Override
  public String generatePresignedUrl(String objectKey, Duration validity) {
    throw new BusinessException(
        ErrorCode.STORAGE_DOWNLOAD_FAILED,
        "本地开发存储不提供公开链接，请通过后端受保护接口读取");
  }

  @Override
  public void delete(String objectKey) {
    try {
      Files.deleteIfExists(resolve(objectKey));
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "本地文件删除失败", e);
    }
  }

  @Override
  public byte[] download(String objectKey) {
    try {
      return Files.readAllBytes(resolve(objectKey));
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "本地文件下载失败", e);
    }
  }

  @Override
  public boolean exists(String objectKey) {
    return Files.isRegularFile(resolve(objectKey));
  }

  @Override
  public long size(String objectKey) {
    try {
      return Files.size(resolve(objectKey));
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "获取本地文件信息失败", e);
    }
  }

  @Override
  public void ensureReady() {
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "无法创建本地存储目录", e);
    }
  }

  private Path resolve(String objectKey) {
    Path target = root.resolve(objectKey).normalize();
    if (!target.startsWith(root)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "非法的文件存储路径");
    }
    return target;
  }
}
