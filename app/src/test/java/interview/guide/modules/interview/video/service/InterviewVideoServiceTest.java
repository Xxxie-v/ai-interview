package interview.guide.modules.interview.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.ObjectAccessService;
import interview.guide.infrastructure.file.ObjectStorageService;
import interview.guide.infrastructure.file.StoredObject;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.video.model.InterviewVideoDTO;
import interview.guide.modules.interview.video.model.InterviewVideoEntity;
import interview.guide.modules.interview.video.model.VideoStatus;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试视频分片上传")
class InterviewVideoServiceTest {

  private static final String SESSION_ID = "session-1";
  private static final String CHECKSUM = "a".repeat(64);

  @Mock
  private InterviewSessionRepository sessionRepository;
  @Mock
  private InterviewVideoPersistenceService persistenceService;
  @Mock
  private FileHashService fileHashService;
  @Mock
  private ObjectStorageService objectStorageService;
  @Mock
  private ObjectAccessService objectAccessService;

  private InterviewVideoService service;

  @BeforeEach
  void setUp() {
    service = new InterviewVideoService(
        sessionRepository,
        persistenceService,
        new InterviewVideoProperties(),
        fileHashService,
        objectStorageService,
        objectAccessService);
  }

  @Test
  @DisplayName("完整视频按分片序号流式拼接")
  void streamsCombinedVideoInChunkOrder() throws Exception {
    when(sessionRepository.existsBySessionId(SESSION_ID)).thenReturn(true);
    InterviewVideoEntity first = uploadedChunk(0, "chunk-0", 3L, 1_000L);
    InterviewVideoEntity second = uploadedChunk(1, "chunk-1", 2L, 800L);
    when(persistenceService.findBySessionId(SESSION_ID)).thenReturn(List.of(first, second));
    when(objectAccessService.download("chunk-0")).thenReturn(new byte[] {1, 2, 3});
    when(objectAccessService.download("chunk-1")).thenReturn(new byte[] {4, 5});

    InterviewVideoService.CombinedVideo combined = service.getAdminCombinedVideo(SESSION_ID);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    service.writeCombinedVideo(combined, output);

    assertThat(combined.fileSize()).isEqualTo(5L);
    assertThat(combined.durationMs()).isEqualTo(1_800L);
    assertThat(output.toByteArray()).containsExactly(1, 2, 3, 4, 5);
  }

  private InterviewVideoEntity uploadedChunk(
      int index,
      String objectKey,
      long size,
      long durationMs) {
    return InterviewVideoEntity.builder()
        .sessionId(SESSION_ID)
        .objectKey(objectKey)
        .mimeType("video/webm;codecs=vp9,opus")
        .fileSize(size)
        .durationMs(durationMs)
        .chunkIndex(index)
        .checksum(CHECKSUM)
        .status(VideoStatus.UPLOADED)
        .build();
  }

  @Nested
  @DisplayName("资源归属与完整性")
  class OwnershipAndIntegrity {

    @Test
    @DisplayName("非会话所有者不能上传视频")
    void rejectsNonOwner() {
      when(sessionRepository.findBySessionIdAndOwnerUserId(SESSION_ID, 20L))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.uploadChunk(
          SESSION_ID, 20L, 0, 1_000, CHECKSUM, videoFile()))
          .isInstanceOf(BusinessException.class)
          .hasMessage("面试会话不存在");

      verify(objectStorageService, never()).upload(any(), any());
    }

    @Test
    @DisplayName("客户端 checksum 与服务端计算结果不同时拒绝上传")
    void rejectsChecksumMismatch() {
      when(sessionRepository.findBySessionIdAndOwnerUserId(SESSION_ID, 20L))
          .thenReturn(Optional.of(new InterviewSessionEntity()));
      when(fileHashService.calculateHash(any(InputStream.class)))
          .thenReturn("b".repeat(64));

      assertThatThrownBy(() -> service.uploadChunk(
          SESSION_ID, 20L, 0, 1_000, CHECKSUM, videoFile()))
          .isInstanceOf(BusinessException.class)
          .hasMessage("视频分片校验失败，请重新上传");

      verify(objectStorageService, never()).upload(any(), any());
    }
  }

  @Nested
  @DisplayName("幂等上传")
  class IdempotentUpload {

    @Test
    @DisplayName("已经上传成功的相同分片不会再次写入对象存储")
    void returnsUploadedChunkWithoutDuplicateStorageWrite() {
      InterviewVideoEntity uploaded = entity(VideoStatus.UPLOADED);
      when(sessionRepository.findBySessionIdAndOwnerUserId(SESSION_ID, 20L))
          .thenReturn(Optional.of(new InterviewSessionEntity()));
      when(fileHashService.calculateHash(any(InputStream.class))).thenReturn(CHECKSUM);
      when(persistenceService.reserveChunk(
          eq(SESSION_ID), eq(20L), eq(0), any(), any(), eq(5L), eq(1_000L), eq(CHECKSUM)))
          .thenReturn(uploaded);

      InterviewVideoDTO result = service.uploadChunk(
          SESSION_ID, 20L, 0, 1_000, CHECKSUM, videoFile());

      assertThat(result.status()).isEqualTo(VideoStatus.UPLOADED);
      verify(objectStorageService, never()).upload(any(), any());
    }

    @Test
    @DisplayName("新分片上传后更新为成功状态")
    void uploadsNewChunk() {
      InterviewVideoEntity uploading = entity(VideoStatus.UPLOADING);
      InterviewVideoEntity uploaded = entity(VideoStatus.UPLOADED);
      when(sessionRepository.findBySessionIdAndOwnerUserId(SESSION_ID, 20L))
          .thenReturn(Optional.of(new InterviewSessionEntity()));
      when(fileHashService.calculateHash(any(InputStream.class))).thenReturn(CHECKSUM);
      when(persistenceService.reserveChunk(
          eq(SESSION_ID), eq(20L), eq(0), any(), any(), eq(5L), eq(1_000L), eq(CHECKSUM)))
          .thenReturn(uploading);
      when(objectStorageService.upload(eq(uploading.getObjectKey()), any()))
          .thenReturn(new StoredObject(uploading.getObjectKey(), "video/webm", 5));
      when(persistenceService.markUploaded(1L)).thenReturn(uploaded);

      InterviewVideoDTO result = service.uploadChunk(
          SESSION_ID, 20L, 0, 1_000, CHECKSUM, videoFile());

      assertThat(result.status()).isEqualTo(VideoStatus.UPLOADED);
      verify(objectStorageService).upload(eq(uploading.getObjectKey()), any());
      verify(persistenceService).markUploaded(1L);
    }
  }

  private MockMultipartFile videoFile() {
    return new MockMultipartFile("file", "chunk.webm", "video/webm", "video".getBytes());
  }

  private InterviewVideoEntity entity(VideoStatus status) {
    return InterviewVideoEntity.builder()
        .id(1L)
        .sessionId(SESSION_ID)
        .userId(20L)
        .objectKey("interview-videos/session-1/chunk.webm")
        .mimeType("video/webm")
        .fileSize(5L)
        .durationMs(1_000L)
        .chunkIndex(0)
        .checksum(CHECKSUM)
        .status(status)
        .build();
  }
}
