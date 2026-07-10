package interview.guide.modules.interview.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.mapper.EnterpriseInterviewReportMapper;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.report.model.EnterpriseInterviewReportDTO;
import interview.guide.modules.interview.report.model.EnterpriseReportAssessment;
import interview.guide.modules.interview.report.model.InterviewViolationConclusion;
import interview.guide.modules.interview.report.model.InterviewReportEntity;
import interview.guide.modules.interview.report.model.ViolationVerdict;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.vision.service.InterviewVisionEventPersistenceService;
import interview.guide.modules.recruitment.model.InterviewAssignmentEntity;
import interview.guide.modules.recruitment.repository.InterviewAssignmentRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("企业最终面试报告")
class EnterpriseInterviewReportServiceTest {

  private static final String SESSION_ID = "session-1";

  @Mock
  private InterviewSessionRepository sessionRepository;
  @Mock
  private InterviewAssignmentRepository assignmentRepository;
  @Mock
  private JobPositionRepository jobRepository;
  @Mock
  private EnterpriseReportAssessmentService assessmentService;
  @Mock
  private EnterpriseReportPersistenceService persistenceService;
  @Mock
  private InterviewVisionEventPersistenceService visionEventPersistenceService;
  @Mock
  private EnterpriseInterviewReportMapper mapper;

  private EnterpriseInterviewReportService service;
  private InterviewViolationAssessmentService violationAssessmentService;

  @BeforeEach
  void setUp() {
    violationAssessmentService = new InterviewViolationAssessmentService(
        new InterviewViolationProperties());
    service = new EnterpriseInterviewReportService(
        sessionRepository,
        assignmentRepository,
        jobRepository,
        assessmentService,
        persistenceService,
        visionEventPersistenceService,
        violationAssessmentService,
        mapper,
        new ObjectMapper());
  }

  @Nested
  @DisplayName("候选人可见性")
  class CandidateVisibility {

    @Test
    @DisplayName("正式面试报告未开放时拒绝候选人查看")
    void rejectsHiddenOfficialReport() {
      InterviewSessionEntity session = session(true);
      when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(session));
      when(assignmentRepository.findById(30L)).thenReturn(Optional.of(
          InterviewAssignmentEntity.builder()
              .id(30L)
              .candidateId(20L)
              .reportVisibleToCandidate(false)
              .build()));

      assertThatThrownBy(() -> service.getForCandidate(SESSION_ID, 20L))
          .isInstanceOf(BusinessException.class)
          .hasMessage("管理员尚未开放该面试报告");
    }

    @Test
    @DisplayName("其他候选人无法通过会话 ID 读取报告")
    void rejectsDifferentCandidate() {
      when(sessionRepository.findBySessionId(SESSION_ID))
          .thenReturn(Optional.of(session(true)));

      assertThatThrownBy(() -> service.getForCandidate(SESSION_ID, 99L))
          .isInstanceOf(BusinessException.class)
          .hasMessage("面试会话不存在");
    }
  }

  @Test
  @DisplayName("已存在的报告直接返回且不会再次调用 AI")
  void returnsPersistedReportWithoutRegeneration() {
    InterviewSessionEntity session = session(false);
    InterviewReportEntity entity = reportEntity();
    EnterpriseInterviewReportDTO expected = dto();
    when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(session));
    when(persistenceService.findBySessionId(SESSION_ID)).thenReturn(Optional.of(entity));
    when(visionEventPersistenceService.listBySessionId(SESSION_ID)).thenReturn(List.of());
    when(mapper.toDTO(
        eq(entity),
        eq(List.of()),
        eq(List.of()),
        eq(List.of()),
        any(InterviewViolationConclusion.class),
        eq(List.of())))
        .thenReturn(expected);

    assertThat(service.getForAdmin(SESSION_ID)).isEqualTo(expected);
  }

  @Test
  @DisplayName("首次生成复用已有逐题评估并保存结构化报告")
  void generatesFromExistingEvaluationPipeline() {
    InterviewSessionEntity session = session(false);
    InterviewReportDTO base = new InterviewReportDTO(
        SESSION_ID, 1, 80, List.of(), List.of(), "总体评价",
        List.of("基础扎实"), List.of("补充案例"), List.of());
    EnterpriseReportAssessment assessment = new EnterpriseReportAssessment(
        82, 76, 79, List.of("基础扎实"), List.of("补充案例"),
        List.of(), "符合主要要求", "建议人工复核");
    InterviewReportEntity entity = reportEntity();
    EnterpriseInterviewReportDTO expected = dto();
    when(sessionRepository.findBySessionId(SESSION_ID)).thenReturn(Optional.of(session));
    when(persistenceService.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());
    when(assessmentService.assess("mock-llm", null, base)).thenReturn(assessment);
    when(persistenceService.save(SESSION_ID, null, 80, assessment)).thenReturn(entity);
    when(visionEventPersistenceService.listBySessionId(SESSION_ID)).thenReturn(List.of());
    when(mapper.toDTO(
        eq(entity),
        eq(List.of()),
        eq(List.of()),
        eq(List.of()),
        any(InterviewViolationConclusion.class),
        eq(List.of())))
        .thenReturn(expected);

    assertThat(service.generateFromEvaluation(SESSION_ID, base)).isEqualTo(expected);

    verify(assessmentService).assess("mock-llm", null, base);
  }

  @Test
  @DisplayName("查看时报告尚未生成不会触发 AI")
  void doesNotGenerateReportWhenViewed() {
    when(sessionRepository.findBySessionId(SESSION_ID))
        .thenReturn(Optional.of(session(false)));
    when(persistenceService.findBySessionId(SESSION_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getForAdmin(SESSION_ID))
        .isInstanceOf(BusinessException.class)
        .hasMessage("企业报告正在生成，请稍后重试");

    verify(assessmentService, never()).assess(any(), any(), any());
  }

  private InterviewSessionEntity session(boolean official) {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId(SESSION_ID);
    session.setOwnerUserId(20L);
    session.setAssignmentId(official ? 30L : null);
    session.setOfficialInterview(official);
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    session.setLlmProvider("mock-llm");
    return session;
  }

  private InterviewReportEntity reportEntity() {
    return InterviewReportEntity.builder()
        .id(1L)
        .sessionId(SESSION_ID)
        .overallScore(80)
        .technicalScore(82)
        .communicationScore(76)
        .jobMatchScore(79)
        .strengthsJson("[]")
        .weaknessesJson("[]")
        .riskNotesJson("[]")
        .summary("符合主要要求")
        .recommendation("建议人工复核")
        .generatedAt(LocalDateTime.now())
        .build();
  }

  private EnterpriseInterviewReportDTO dto() {
    return new EnterpriseInterviewReportDTO(
        SESSION_ID, null, 80, 82, 76, 79, List.of(), List.of(), List.of(),
        "符合主要要求",
        "建议人工复核",
        new InterviewViolationConclusion(
            ViolationVerdict.NORMAL,
            false,
            false,
            0,
            0,
            0,
            0,
            0,
            List.of()),
        List.of(),
        LocalDateTime.now());
  }
}
