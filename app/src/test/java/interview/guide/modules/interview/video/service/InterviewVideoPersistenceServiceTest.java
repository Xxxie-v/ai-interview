package interview.guide.modules.interview.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.video.model.InterviewVideoEntity;
import interview.guide.modules.interview.video.model.VideoStatus;
import interview.guide.modules.interview.video.repository.InterviewVideoRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试视频分片持久化")
class InterviewVideoPersistenceServiceTest {

  @Mock
  private InterviewVideoRepository repository;

  @Test
  @DisplayName("同一序号上传不同内容时返回不可重试的冲突错误码")
  void rejectsDifferentContentForExistingChunkIndex() {
    InterviewVideoEntity existing = InterviewVideoEntity.builder()
        .sessionId("session-1")
        .chunkIndex(0)
        .checksum("a".repeat(64))
        .status(VideoStatus.UPLOADED)
        .build();
    when(repository.findBySessionIdAndChunkIndex("session-1", 0))
        .thenReturn(Optional.of(existing));
    InterviewVideoPersistenceService service = new InterviewVideoPersistenceService(repository);

    assertThatThrownBy(() -> service.reserveChunk(
        "session-1",
        1L,
        0,
        "interview-videos/session-1/chunk-0.webm",
        "video/webm",
        10L,
        1_000L,
        "b".repeat(64)))
        .isInstanceOf(BusinessException.class)
        .satisfies(error -> assertThat(((BusinessException) error).getCode())
            .isEqualTo(ErrorCode.INTERVIEW_VIDEO_CHUNK_CONFLICT.getCode()));

    verify(repository, never()).saveAndFlush(any());
  }
}
