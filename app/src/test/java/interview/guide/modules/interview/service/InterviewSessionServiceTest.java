package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.listener.QuestionPrepareStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.JobStatus;
import interview.guide.modules.recruitment.repository.InterviewAssignmentRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import interview.guide.modules.recruitment.service.JobQuestionBankService;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试会话创建服务")
class InterviewSessionServiceTest {

  @Mock
  private InterviewPlannerService plannerService;
  @Mock
  private AnswerEvaluationService evaluationService;
  @Mock
  private InterviewPersistenceService persistenceService;
  @Mock
  private InterviewSessionCache sessionCache;
  @Mock
  private ObjectMapper objectMapper;
  @Mock
  private EvaluateStreamProducer evaluateStreamProducer;
  @Mock
  private QuestionPrepareStreamProducer questionPrepareStreamProducer;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private LlmTaskRouter taskRouter;
  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private JobPositionRepository jobRepository;
  @Mock
  private InterviewAssignmentRepository assignmentRepository;
  @Mock
  private JobQuestionBankService jobQuestionBankService;
  @Mock
  private InterviewStateMachineService stateMachineService;
  @Mock
  private DynamicFollowUpService dynamicFollowUpService;
  @Mock
  private InterviewQuestionProperties questionProperties;
  @Mock
  private RedisService redisService;
  @Mock
  private UserRepository userRepository;

  @InjectMocks
  private InterviewSessionService service;

  @Test
  @DisplayName("选择岗位参加面试时必须先上传简历")
  void jobInterviewRequiresResume() {
    CreateInterviewRequest request = new CreateInterviewRequest(
        "",
        8,
        null,
        true,
        null,
        "custom",
        "mid",
        null,
        null,
        true,
        10L);

    assertThatThrownBy(() -> service.createSession(request, 20L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("请先上传并选择一份简历");
  }

  @Test
  @DisplayName("点击参加面试后立即入队且不在请求线程调用大模型")
  void createSessionEnqueuesQuestionPreparationWithoutPlanningSynchronously() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(30L);
    resume.setOwnerUserId(20L);
    resume.setResumeText("Java and Redis project experience");
    JobPositionEntity job = JobPositionEntity.builder()
        .id(10L)
        .name("Java developer")
        .description("Backend development")
        .requirements("Java, Redis")
        .level("mid")
        .status(JobStatus.ACTIVE)
        .build();
    List<InterviewQuestionDTO> fixedQuestions = List.of(
        InterviewQuestionDTO.create(0, "Q1", "TECHNICAL", "Java", "Java", false, null),
        InterviewQuestionDTO.create(1, "Q2", "TECHNICAL", "Redis", "Redis", false, null),
        InterviewQuestionDTO.create(2, "Q3", "TECHNICAL", "System", "System", false, null));
    when(resumeRepository.findByIdAndOwnerUserId(30L, 20L)).thenReturn(Optional.of(resume));
    when(jobRepository.findByIdAndStatus(10L, JobStatus.ACTIVE)).thenReturn(Optional.of(job));
    when(jobQuestionBankService.selectFixedQuestions(anyString(), anyString(), anyString()))
        .thenReturn(fixedQuestions);
    when(assignmentRepository
        .findFirstByCandidateIdAndJobIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
            eq(20L), eq(10L), eq(30L), any()))
        .thenReturn(Optional.empty());
    when(userRepository.findById(20L)).thenReturn(Optional.empty());
    when(persistenceService.findByOwnerUserIdAndJobId(20L, 10L))
        .thenReturn(Optional.empty());
    when(redisService.executeWithLock(
        anyString(), anyLong(), anyLong(), eq(TimeUnit.SECONDS), any()))
        .thenAnswer(invocation -> {
          RedisService.LockedOperation<?> operation = invocation.getArgument(4);
          return operation.execute();
        });

    CreateInterviewRequest request = new CreateInterviewRequest(
        "",
        8,
        30L,
        false,
        null,
        "custom",
        "mid",
        null,
        null,
        true,
        10L);

    InterviewSessionDTO session = service.createSession(request, 20L);

    assertThat(session.questions()).isEmpty();
    assertThat(session.questionPrepareStatus()).isEqualTo(
        interview.guide.common.model.AsyncTaskStatus.PENDING);
    verify(questionPrepareStreamProducer).sendQuestionPrepareTask(session.sessionId());
    verify(plannerService, never()).planQuestions(any(), any(), any(), anyInt(), any());
  }
}
