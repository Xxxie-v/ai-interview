package interview.guide.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.StorageConfigProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("对象存储证据访问")
class ObjectAccessServiceTest {

  @Mock
  private ObjectStorageService objectStorageService;

  @Test
  @DisplayName("S3 存储返回短期签名地址")
  void createsPresignedUrlForS3() {
    StorageConfigProperties properties = new StorageConfigProperties();
    properties.setProvider("s3");
    properties.setDirectAccessEnabled(true);
    when(objectStorageService.generatePresignedUrl(eq("evidence/a.jpg"), eq(Duration.ofMinutes(10))))
        .thenReturn("https://oss.example/signed");
    ObjectAccessService service = new ObjectAccessService(objectStorageService, properties);

    ObjectAccessResponse response = service.createAccess(
        "evidence/a.jpg", "image/jpeg", "/protected");

    assertThat(response.direct()).isTrue();
    assertThat(response.url()).isEqualTo("https://oss.example/signed");
    verify(objectStorageService).generatePresignedUrl(
        "evidence/a.jpg", Duration.ofMinutes(10));
  }

  @Test
  @DisplayName("S3 内网端点默认通过受保护的后端地址访问")
  void createsProtectedUrlWhenDirectAccessIsDisabled() {
    StorageConfigProperties properties = new StorageConfigProperties();
    properties.setProvider("s3");
    ObjectAccessService service = new ObjectAccessService(objectStorageService, properties);

    ObjectAccessResponse response = service.createAccess(
        "evidence/a.jpg", "image/jpeg", "/protected");

    assertThat(response.direct()).isFalse();
    assertThat(response.url()).isEqualTo("/protected");
  }

  @Test
  @DisplayName("本地存储返回受保护后端地址")
  void createsProtectedUrlForLocalStorage() {
    StorageConfigProperties properties = new StorageConfigProperties();
    properties.setProvider("local");
    ObjectAccessService service = new ObjectAccessService(objectStorageService, properties);

    ObjectAccessResponse response = service.createAccess(
        "evidence/a.jpg", "image/jpeg", "/protected");

    assertThat(response.direct()).isFalse();
    assertThat(response.url()).isEqualTo("/protected");
  }
}
