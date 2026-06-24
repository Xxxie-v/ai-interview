package interview.guide.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.interview.service.InterviewQuestionProperties;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历面试题预生成")
class ResumeQuestionPreparationServiceTest {

  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private InterviewQuestionService questionService;
  @Mock
  private LlmTaskRouter taskRouter;

  private ResumeQuestionPreparationService service;

  @BeforeEach
  void setUp() {
    service = new ResumeQuestionPreparationService(
        resumeRepository,
        questionService,
        new InterviewQuestionProperties(),
        new ObjectMapper(),
        taskRouter);
    lenient().when(taskRouter.execute(
        any(),
        org.mockito.ArgumentMatchers.nullable(String.class),
        any())).thenAnswer(invocation -> {
          LlmTaskRouter.ProviderOperation<?> operation = invocation.getArgument(2);
          return operation.execute("dashscope-question");
        });
  }

  @Test
  @DisplayName("上传后的异步流程会保存六道纯主问题")
  void preparesQuestionsAfterResumeAnalysis() {
    ResumeEntity resume = new ResumeEntity();
    resume.setId(10L);
    resume.setResumeText("Java 后端项目经历");
    List<InterviewQuestionDTO> questions = java.util.stream.IntStream.range(0, 6)
        .mapToObj(index -> InterviewQuestionDTO.create(
            index, "简历题" + index, "PROJECT", "项目", "项目" + index, false, null))
        .toList();
    when(resumeRepository.findById(10L)).thenReturn(Optional.of(resume));
    when(resumeRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(questionService.generateResumeQuestionsForPreparation(
        "dashscope-question", resume.getResumeText(), 6))
        .thenReturn(questions);

    service.prepare(10L);

    assertThat(resume.getQuestionPrepareStatus()).isEqualTo(AsyncTaskStatus.COMPLETED);
    assertThat(resume.getPreparedQuestionsJson()).contains("简历题0", "简历题5");
    assertThat(resume.getQuestionsPreparedAt()).isNotNull();
    verify(resumeRepository, atLeastOnce()).save(resume);
  }
}
