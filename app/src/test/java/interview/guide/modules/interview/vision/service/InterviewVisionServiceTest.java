package interview.guide.modules.interview.vision.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.ObjectAccessService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.vision.model.FrameInput;
import interview.guide.modules.interview.vision.model.InterviewVisionEventEntity;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;
import interview.guide.modules.interview.vision.model.VisionEventType;
import interview.guide.modules.interview.vision.model.VisionMonitoringState;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试画面分析服务")
class InterviewVisionServiceTest {

  private static final String SESSION_ID = "session-1";

  @Mock
  private InterviewSessionRepository sessionRepository;
  @Mock
  private InterviewVisionAnalyzer analyzer;
  @Mock
  private InterviewVisionEventPersistenceService persistenceService;
  @Mock
  private FileStorageService fileStorageService;
  @Mock
  private ObjectAccessService objectAccessService;

  private InterviewVisionService service;

  @BeforeEach
  void setUp() {
    InterviewVisionProperties properties = new InterviewVisionProperties();
    properties.setConfirmationFrames(1);
    properties.setOtherEventMinDuration(Duration.ZERO);
    service = new InterviewVisionService(
        sessionRepository,
        analyzer,
        properties,
        new VisionAnomalyTracker(properties),
        persistenceService,
        fileStorageService,
        objectAccessService,
        new ObjectMapper());
  }

  @Nested
  @DisplayName("安全校验")
  class Validation {

    @Test
    @DisplayName("非会话所有者不能提交画面")
    void rejectsNonOwner() {
      when(sessionRepository.findBySessionIdAndOwnerUserId(SESSION_ID, 20L))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.analyze(SESSION_ID, 20L, jpeg(), 100D, true, 1_000L))
          .isInstanceOf(BusinessException.class)
          .hasMessage("面试会话不存在");

      verify(analyzer, never()).analyze(any());
    }

    @Test
    @DisplayName("声明为 JPEG 但签名无效的文件会被拒绝")
    void rejectsInvalidImageSignature() {
      when(sessionRepository.findBySessionIdAndOwnerUserId(SESSION_ID, 20L))
          .thenReturn(Optional.of(new InterviewSessionEntity()));
      MockMultipartFile invalid = new MockMultipartFile(
          "frame", "frame.jpg", "image/jpeg", "not-an-image".getBytes());

      assertThatThrownBy(() -> service.analyze(SESSION_ID, 20L, invalid, 100D, true, 1_000L))
          .isInstanceOf(BusinessException.class)
          .hasMessage("抽帧图片内容无效");

      verify(analyzer, never()).analyze(any());
    }
  }

  @Test
  @DisplayName("确认异常后持久化聚合时段和指标元数据")
  void persistsObjectiveEventMetadata() {
    when(sessionRepository.findBySessionIdAndOwnerUserId(SESSION_ID, 20L))
        .thenReturn(Optional.of(new InterviewSessionEntity()));
    VisionAnalysisResult analysis = new VisionAnalysisResult(
        true, 1, 0.5, true, true, List.of(VisionEventType.LOW_LIGHT));
    when(analyzer.analyze(any())).thenReturn(analysis);

    VisionAnalysisResult result = service.analyze(
        SESSION_ID, 20L, jpeg(), 20D, true, 1_000L);

    assertThat(result.monitoringState()).isEqualTo(VisionMonitoringState.CONFIRMED);
    assertThat(result.candidateEvents()).containsExactly(VisionEventType.LOW_LIGHT);
    assertThat(result.events()).containsExactly(VisionEventType.LOW_LIGHT);
    ArgumentCaptor<FrameInput> frameCaptor = ArgumentCaptor.forClass(FrameInput.class);
    verify(analyzer).analyze(frameCaptor.capture());
    assertThat(frameCaptor.getValue().frame()).hasSize(3);
    ArgumentCaptor<VisionAnomalyTracker.EpisodeUpdate> episodeCaptor =
        ArgumentCaptor.forClass(VisionAnomalyTracker.EpisodeUpdate.class);
    verify(persistenceService).upsertEpisode(
        eq(SESSION_ID),
        eq(20L),
        episodeCaptor.capture(),
        org.mockito.ArgumentMatchers.contains("\"brightness\":20.0"));
    assertThat(episodeCaptor.getValue().eventTypes())
        .containsExactly(VisionEventType.LOW_LIGHT);
  }

  @Test
  @DisplayName("相同客户端事件重复提交时不会重复上传证据")
  void skipsDuplicateClientEvent() {
    String clientEventId = "7a6ba5d1-f18d-4ad9-9a0f-2f442833c100";
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setOfficialInterview(true);
    when(sessionRepository.findBySessionIdAndOwnerUserId(SESSION_ID, 20L))
        .thenReturn(Optional.of(session));
    when(persistenceService.findByClientEventId(SESSION_ID, clientEventId))
        .thenReturn(Optional.of(new InterviewVisionEventEntity()));

    service.recordProctorEvent(
        SESSION_ID,
        20L,
        clientEventId,
        VisionEventType.SCREEN_CAPTURED,
        jpeg(),
        null,
        1_000L);

    verifyNoInteractions(fileStorageService);
  }

  private MockMultipartFile jpeg() {
    return new MockMultipartFile(
        "frame",
        "frame.jpg",
        "image/jpeg",
        new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
  }
}
