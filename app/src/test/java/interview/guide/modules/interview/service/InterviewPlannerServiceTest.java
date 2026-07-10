package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewPlanningContext;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位面试规划服务")
class InterviewPlannerServiceTest {

  @Mock
  private InterviewQuestionService questionService;

  @Mock
  private InterviewQuestionProviderResolver questionProviderResolver;
  @Mock
  private LlmTaskRouter taskRouter;

  private InterviewPlannerService service;

  @BeforeEach
  void setUp() {
    service = new InterviewPlannerService(
        questionService,
        new InterviewQuestionProperties(),
        questionProviderResolver,
        taskRouter);
  }

  @Test
  @DisplayName("正式面试在确定岗位后生成岗位匹配简历题并与固定题组合")
  void generatesJobMatchedResumeQuestionsAndCombinesFixedQuestions() {
    List<InterviewQuestionDTO> fixed = questions("岗位", 3);
    List<InterviewQuestionDTO> resume = questions("简历", 3);
    List<HistoricalQuestion> history = List.of(
        new HistoricalQuestion("已考过的 Redis 题", "REDIS", "Redis 缓存"));
    InterviewPlanningContext context = new InterviewPlanningContext(
        1L,
        2L,
        3L,
        "简历正文",
        "岗位 JD",
        "mid",
        List.of(),
        history,
        fixed,
        resume);
    when(questionService.generateJobMatchedResumeQuestions(
        "dashscope-question", "简历正文", "岗位 JD", 3, history)).thenReturn(resume);
    when(questionProviderResolver.resolve()).thenReturn("dashscope-question");
    when(taskRouter.execute(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq("dashscope-question"),
        org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          LlmTaskRouter.ProviderOperation<?> operation = invocation.getArgument(2);
          return operation.execute("dashscope-question");
        });

    var result = service.planQuestions("default", "custom", "mid", 6, context);

    assertThat(result).hasSize(6);
    assertThat(result).allMatch(question -> !question.isFollowUp());
    assertThat(result).allSatisfy(question -> {
      assertThat(question.questionContext()).isNotNull();
      assertThat(question.questionContext().dimensions())
          .containsExactly("implementation", "principle", "failureHandling", "tradeoff");
      assertThat(question.questionState().coverage()).allSatisfy(
          (dimension, coverage) -> assertThat(coverage).isZero());
    });
    assertThat(result).extracting(InterviewQuestionDTO::questionIndex)
        .containsExactly(0, 1, 2, 3, 4, 5);
    verify(questionService).generateJobMatchedResumeQuestions(
        "dashscope-question", "简历正文", "岗位 JD", 3, history);
  }

  private List<InterviewQuestionDTO> questions(String prefix, int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(index -> InterviewQuestionDTO.create(
            index, prefix + index, "GENERAL", prefix, prefix + index, false, null))
        .toList();
  }
}
