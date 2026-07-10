package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.DynamicFollowUpModelResult;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.NextAction;
import interview.guide.modules.interview.model.QuestionAnswerSnapshot;
import interview.guide.modules.interview.model.QuestionContext;
import interview.guide.modules.interview.model.QuestionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

@ExtendWith(MockitoExtension.class)
@DisplayName("基于回答的动态追问")
class DynamicFollowUpServiceTest {

  @Mock
  private StructuredOutputInvoker invoker;
  @Mock
  private LlmProviderRegistry providerRegistry;
  @Mock
  private ChatClient chatClient;
  @Mock
  private LlmTaskRouter taskRouter;

  private DynamicFollowUpService service;

  @BeforeEach
  void setUp() throws Exception {
    lenient().when(providerRegistry.getPlainChatClient(any())).thenReturn(chatClient);
    lenient().when(taskRouter.execute(any(), anyString(), any()))
        .thenAnswer(invocation -> {
          LlmTaskRouter.ProviderOperation<?> operation = invocation.getArgument(2);
          return operation.execute("dashscope");
        });
    service = new DynamicFollowUpService(
        invoker,
        providerRegistry,
        taskRouter,
        new PromptSanitizer(new LlmProviderProperties()),
        new InterviewQuestionProperties(),
        new DefaultResourceLoader());
  }

  @Test
  @DisplayName("模型暂不可用时不使用固定模板并直接切换主题")
  void switchesTopicWhenModelIsUnavailable() {
    when(invoker.invokeOnce(
        any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any()))
        .thenThrow(new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED));
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0, "如何处理缓存一致性？", "REDIS", "Redis", "缓存一致性", false, null);

    var result = service.evaluate(
        "default", question, "我使用延迟双删", List.of(question));

    assertThat(result.nextAction()).isEqualTo(NextAction.NEW_TOPIC);
    assertThat(result.nextQuestion()).isNull();
  }

  @Test
  @DisplayName("逐题决策使用配置的轻量 Provider 且接受模型切换主题")
  void usesConfiguredProviderAndAcceptsTopicSwitch() {
    when(invoker.invokeOnce(
        any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new DynamicFollowUpModelResult(
            Map.of(
                "implementation", 0.9,
                "principle", 0.8,
                "failureHandling", 0.8,
                "tradeoff", 0.8),
            List.of("Redis 热点数据缓存"),
            "tradeoff",
            false,
            null,
            "无需追问"));
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0, "请介绍你的缓存方案。", "REDIS", "Redis", "缓存方案", false, null);

    var result = service.evaluate(
        "deep", question, "使用 Redis 保存热点数据。", List.of(question));

    assertThat(result.nextAction()).isEqualTo(NextAction.NEW_TOPIC);
    verify(providerRegistry).getPlainChatClient("dashscope");
  }

  @Test
  @DisplayName("候选人明确表示不知道时不再调用模型并直接切换主题")
  void skipsFollowUpWhenCandidateExplicitlyDoesNotKnow() {
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0, "请介绍慢 SQL 定位过程。", "DATABASE", "数据库", "慢 SQL", false, null);

    var result = service.evaluate("default", question, "这个我不知道，下一题吧", List.of(question));

    assertThat(result.nextAction()).isEqualTo(NextAction.NEW_TOPIC);
    assertThat(result.nextQuestion()).isNull();
    verify(providerRegistry, never()).getPlainChatClient(any());
  }

  @Test
  @DisplayName("模型生成与本主题已提问题重复的追问时直接拒绝")
  void rejectsDuplicateQuestionInSameTopic() {
    when(invoker.invokeOnce(
        any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new DynamicFollowUpModelResult(
            Map.of("implementation", 0.8),
            List.of("Redis 热点数据缓存"),
            "tradeoff",
            true,
            "请你再具体说明一下为什么选择 Redis？",
            "继续验证技术选型"));
    InterviewQuestionDTO root = InterviewQuestionDTO.create(
        0, "为什么选择 Redis？", "REDIS", "Redis", "技术选型", false, null)
        .withAnswer("因为需要低延迟访问热点数据");

    var result = service.evaluate(
        "default", root, root.userAnswer(), List.of(root));

    assertThat(result.nextAction()).isEqualTo(NextAction.NEW_TOPIC);
    assertThat(result.nextQuestion()).isNull();
  }

  @Test
  @DisplayName("只向模型提供当前主题的问答链并明确剩余追问上限")
  void providesTopicScopedHistoryToModel() {
    when(invoker.invokeOnce(
        any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new DynamicFollowUpModelResult(
            Map.of("implementation", 0.8),
            List.of("延迟双删"),
            "failureHandling",
            false,
            null,
            "当前主题已充分验证"));
    QuestionContext context = new QuestionContext(
        "0",
        "如何保证缓存一致性？",
        List.of("Redis", "高并发"),
        List.of("项目使用延迟双删"),
        QuestionContext.DEFAULT_DIMENSIONS);
    QuestionState state = new QuestionState(
        Map.of(
            "implementation", 0.7,
            "principle", 0.3,
            "failureHandling", 0.1,
            "tradeoff", 0.1),
        List.of("延迟双删"),
        List.of(new QuestionAnswerSnapshot("如何保证缓存一致性？", "采用延迟双删")),
        1,
        0,
        System.currentTimeMillis());
    InterviewQuestionDTO root = InterviewQuestionDTO.create(
        0, "如何保证缓存一致性？", "REDIS", "Redis", "缓存一致性", false, null,
        context, state).withAnswer("采用延迟双删");
    InterviewQuestionDTO followUp = InterviewQuestionDTO.create(
        1, "第二次删除延迟如何确定？", "REDIS", "Redis（动态追问）", null, true, 0,
        context, state)
        .withAnswer("根据数据库写入耗时的 P99 确定");
    InterviewQuestionDTO otherTopic = InterviewQuestionDTO.create(
        2, "如何优化慢 SQL？", "DATABASE", "数据库", "慢 SQL", false, null)
        .withAnswer("先查看执行计划");
    ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);

    service.evaluate(
        "default",
        followUp,
        followUp.userAnswer(),
        List.of(root, followUp, otherTopic));

    verify(invoker).invokeOnce(
        any(), anyString(), promptCaptor.capture(), any(), any(), any(), anyString(), anyString(), any());
    assertThat(promptCaptor.getValue())
        .contains("程序最多还允许追问 1 次")
        .contains("implementation=0.70")
        .contains("项目使用延迟双删")
        .contains("如何保证缓存一致性？")
        .doesNotContain("如何优化慢 SQL？");
  }

  @Test
  @DisplayName("覆盖度已经充分时即使模型要求追问也由程序强制切换主题")
  void stopsWhenCoreDimensionsAreCovered() {
    when(invoker.invokeOnce(
        any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new DynamicFollowUpModelResult(
            Map.of(
                "implementation", 0.9,
                "principle", 0.85,
                "failureHandling", 0.9,
                "tradeoff", 0.8),
            List.of("Lua 原子扣减", "失败补偿", "最终一致性取舍"),
            "tradeoff",
            true,
            "为什么不用数据库事务？",
            "继续验证取舍"));
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0, "高并发场景如何扣减库存？", "REDIS", "Redis", "库存扣减", false, null);

    var result = service.evaluate(
        "default",
        question,
        "使用 Redis Lua 扣减，失败后按流水补偿，业务允许最终一致性。",
        List.of(question));

    assertThat(result.nextAction()).isEqualTo(NextAction.NEW_TOPIC);
    assertThat(result.questionState().coverage().get("tradeoff")).isEqualTo(0.8);
    assertThat(result.questionState().coveredTopics()).contains("失败补偿");
  }

  @Test
  @DisplayName("接受追问时原子更新覆盖状态、最近问答和追问次数")
  void updatesQuestionStateWhenFollowUpIsAccepted() {
    when(invoker.invokeOnce(
        any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new DynamicFollowUpModelResult(
            Map.of(
                "implementation", 0.9,
                "principle", 0.8,
                "failureHandling", 0.2,
                "tradeoff", 0.1),
            List.of("Lua 原子扣减", "Kafka 异步订单"),
            "failureHandling",
            true,
            "Kafka 消息发送失败时如何补偿？",
            "回答已覆盖实现与原理，继续验证故障处理"));
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0, "高并发场景如何扣减库存？", "REDIS", "Redis", "库存扣减", false, null);

    var result = service.evaluate(
        "default",
        question,
        "使用 Redis Lua 原子扣减，成功后发送 Kafka 消息。",
        List.of(question));

    assertThat(result.nextAction()).isEqualTo(NextAction.DEEP_FOLLOW_UP);
    assertThat(result.nextQuestion()).isEqualTo("Kafka 消息发送失败时如何补偿？");
    assertThat(result.questionState().followUpCount()).isEqualTo(1);
    assertThat(result.questionState().recentQa()).hasSize(1);
    assertThat(result.questionState().coverage().get("implementation")).isEqualTo(0.9);
  }

  @Test
  @DisplayName("单题超过最大考察时长后不调用模型")
  void stopsBeforeModelWhenTopicTimesOut() {
    QuestionContext context = new QuestionContext(
        "0",
        "如何设计库存扣减？",
        List.of("Redis"),
        List.of("使用 Lua 扣减库存"),
        QuestionContext.DEFAULT_DIMENSIONS);
    QuestionState expiredState = new QuestionState(
        Map.of(
            "implementation", 0.5,
            "principle", 0.2,
            "failureHandling", 0.0,
            "tradeoff", 0.0),
        List.of("Lua 扣减"),
        List.of(),
        1,
        0,
        System.currentTimeMillis() - 5 * 60 * 1000L);
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0, "如何设计库存扣减？", "REDIS", "Redis", "库存扣减", false, null,
        context, expiredState);

    var result = service.evaluate(
        "default",
        question,
        "还可以继续说明。",
        List.of(question));

    assertThat(result.nextAction()).isEqualTo(NextAction.NEW_TOPIC);
    assertThat(result.reasoningSummary()).contains("最大考察时长");
    verify(providerRegistry, never()).getPlainChatClient(any());
  }

  @Test
  @DisplayName("连续两轮没有新增覆盖主题时强制结束追问")
  void stopsAfterTwoStagnantRounds() {
    when(invoker.invokeOnce(
        any(), anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any()))
        .thenReturn(new DynamicFollowUpModelResult(
            Map.of("implementation", 0.5),
            List.of("Redis"),
            "failureHandling",
            true,
            "故障时如何恢复？",
            "继续验证故障处理"));
    QuestionContext context = new QuestionContext(
        "0",
        "如何设计缓存？",
        List.of("Redis"),
        List.of("项目使用 Redis"),
        QuestionContext.DEFAULT_DIMENSIONS);
    QuestionState state = new QuestionState(
        Map.of(
            "implementation", 0.5,
            "principle", 0.1,
            "failureHandling", 0.0,
            "tradeoff", 0.0),
        List.of("Redis"),
        List.of(new QuestionAnswerSnapshot("如何设计缓存？", "使用 Redis")),
        0,
        1,
        System.currentTimeMillis());
    InterviewQuestionDTO question = InterviewQuestionDTO.create(
        0, "如何设计缓存？", "REDIS", "Redis", "缓存设计", false, null,
        context, state);

    var result = service.evaluate(
        "default",
        question,
        "还是使用 Redis。",
        List.of(question));

    assertThat(result.nextAction()).isEqualTo(NextAction.NEW_TOPIC);
    assertThat(result.reasoningSummary()).contains("未产生新的有效证据");
    assertThat(result.questionState().stagnantRounds()).isEqualTo(2);
  }

  @Test
  @DisplayName("达到同主题追问上限后不再调用模型")
  void skipsModelAfterTopicFollowUpLimit() {
    InterviewQuestionDTO root = InterviewQuestionDTO.create(
        0, "如何设计缓存？", "REDIS", "Redis", "缓存设计", false, null)
        .withAnswer("使用 Redis");
    List<InterviewQuestionDTO> questions = new ArrayList<>();
    questions.add(root);
    for (int index = 1; index <= 4; index++) {
      questions.add(InterviewQuestionDTO.create(
          index,
          "追问" + index,
          "REDIS",
          "Redis（动态追问）",
          null,
          true,
          0).withAnswer("回答" + index));
    }

    var result = service.evaluate(
        "default",
        questions.get(questions.size() - 1),
        "还可以继续回答",
        questions);

    assertThat(result.nextAction()).isEqualTo(NextAction.NEW_TOPIC);
    verify(providerRegistry, never()).getPlainChatClient(any());
  }
}
