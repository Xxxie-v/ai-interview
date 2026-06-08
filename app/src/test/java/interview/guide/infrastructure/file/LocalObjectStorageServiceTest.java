package interview.guide.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.config.StorageConfigProperties;
import interview.guide.common.exception.BusinessException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("本地对象存储")
class LocalObjectStorageServiceTest {

  @TempDir
  Path tempDir;

  private LocalObjectStorageService storageService;

  @BeforeEach
  void setUp() {
    StorageConfigProperties properties = new StorageConfigProperties();
    properties.setLocalDir(tempDir.toString());
    storageService = new LocalObjectStorageService(properties);
  }

  @Test
  @DisplayName("可以上传、读取并删除文件")
  void storesAndDeletesObject() {
    MockMultipartFile file = new MockMultipartFile(
        "file", "resume.txt", "text/plain", "hello".getBytes());

    storageService.upload("resumes/test.txt", file);

    assertThat(storageService.exists("resumes/test.txt")).isTrue();
    assertThat(storageService.size("resumes/test.txt")).isEqualTo(5);
    assertThat(storageService.download("resumes/test.txt")).isEqualTo("hello".getBytes());

    storageService.delete("resumes/test.txt");
    assertThat(storageService.exists("resumes/test.txt")).isFalse();
  }

  @Test
  @DisplayName("拒绝目录穿越路径")
  void rejectsPathTraversal() {
    assertThatThrownBy(() -> storageService.exists("../../outside.txt"))
        .isInstanceOf(BusinessException.class)
        .hasMessage("非法的文件存储路径");
  }
}
