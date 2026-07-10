package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.common.ai.routing.LlmTaskType;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.DynamicAnswerEvaluation;
import interview.guide.modules.interview.model.DynamicFollowUpModelResult;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.NextAction;
import interview.guide.modules.interview.model.QuestionContext;
import interview.guide.modules.interview.model.QuestionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DynamicFollowUpService {

  private static final int MAX_QUESTION_LENGTH = 60;
  private static final int MAX_REASON_LENGTH = 300;

  private final StructuredOutputInvoker structuredOutputInvoker;
  private final LlmProviderRegistry llmProviderRegistry;
  private final LlmTaskRouter taskRouter;
  private final PromptSanitizer promptSanitizer;
  private final BeanOutputConverter<DynamicFollowUpModelResult> outputConverter;
  private final String systemPrompt;
  private final InterviewQuestionProperties properties;
  private final OpenAiChatOptions followUpOptions = OpenAiChatOptions.builder()
      .temperature(0.35)
      .maxCompletionTokens(350)
      .extraBody(Map.of("enable_thinking", false))
      .build();

  public DynamicFollowUpService(
      StructuredOutputInvoker structuredOutputInvoker,
      LlmProviderRegistry llmProviderRegistry,
      LlmTaskRouter taskRouter,
      PromptSanitizer promptSanitizer,
      InterviewQuestionProperties properties,
      ResourceLoader resourceLoader) throws IOException {
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.llmProviderRegistry = llmProviderRegistry;
    this.taskRouter = taskRouter;
    this.promptSanitizer = promptSanitizer;
    this.properties = properties;
    this.outputConverter = new BeanOutputConverter<>(DynamicFollowUpModelResult.class);
    this.systemPrompt = resourceLoader.getResource(properties.getDynamicEvaluationPromptPath())
        .getContentAsString(StandardCharsets.UTF_8);
  }

  public DynamicAnswerEvaluation evaluate(
      String llmProvider,
      InterviewQuestionDTO currentQuestion,
      String answer,
      List<InterviewQuestionDTO> questionHistory) {
    TopicMemory memory = resolveTopicMemory(currentQuestion, questionHistory);
    if (isExplicitUnknown(answer)) {
      return newTopic("候选人明确表示不了解当前问题，切换到下一主题", memory.state());
    }
    if (memory.followUpCount() >= properties.getDynamicMaxFollowUpsPerTopic()) {
      return newTopic("当前主题已达到追问上限，切换到下一主题", memory.state());
    }
    if (hasTimedOut(memory.state(), System.currentTimeMillis())) {
      return newTopic("当前主题已达到最大考察时长，切换到下一主题", memory.state());
    }

    String userPrompt = buildUserPrompt(memory, currentQuestion, answer);
    FutureTask<DynamicFollowUpModelResult> evaluationTask = new FutureTask<>(
        () -> taskRouter.execute(
            LlmTaskType.FOLLOW_UP,
            resolveProvider(llmProvider),
            routedProvider -> structuredOutputInvoker.invokeOnce(
                llmProviderRegistry.getPlainChatClient(routedProvider),
                systemPrompt + "\n\n" + outputConverter.getFormat(),
                userPrompt,
                outputConverter,
                followUpOptions,
                ErrorCode.INTERVIEW_EVALUATION_FAILED,
                "逐题评估失败：",
                "dynamic-follow-up",
                log)));
    Thread evaluationThread = Thread.ofVirtual()
        .name("dynamic-follow-up-" + currentQuestion.questionIndex())
        .start(evaluationTask);
    long timeoutMillis = Math.max(500L, properties.getDynamicEvaluationTimeout().toMillis());
    try {
      DynamicFollowUpModelResult modelResult = evaluationTask.get(
          timeoutMillis,
          TimeUnit.MILLISECONDS);
      return normalize(modelResult, memory, currentQuestion, answer);
    } catch (TimeoutException e) {
      evaluationTask.cancel(true);
      log.warn("Dynamic answer evaluation timed out, switching topic: timeoutMs={}", timeoutMillis);
      return newTopic("实时追问生成超时，为保证面试流畅切换到下一主题", memory.state());
    } catch (InterruptedException e) {
      evaluationTask.cancel(true);
      Thread.currentThread().interrupt();
      log.warn("Dynamic answer evaluation interrupted, switching topic");
      return newTopic("实时追问生成被中断，切换到下一主题", memory.state());
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof BusinessException businessException) {
        log.warn("Dynamic answer evaluation degraded: error={}", businessException.getMessage());
      } else {
        log.error("Dynamic answer evaluation failed", cause);
      }
      return newTopic("实时追问暂不可用，为避免固定模板追问而切换主题", memory.state());
    } finally {
      if (evaluationTask.isCancelled()) evaluationThread.interrupt();
    }
  }

  private String buildUserPrompt(
      TopicMemory memory,
      InterviewQuestionDTO currentQuestion,
      String answer) {
    QuestionContext context = memory.context();
    QuestionState state = memory.state();
    int remainingFollowUps = Math.max(
        0,
        properties.getDynamicMaxFollowUpsPerTopic() - memory.followUpCount());
    String recentQa = state.recentQa().isEmpty()
        ? "暂无，这是当前主题的第一轮回答"
        : state.recentQa().stream()
            .map(item -> "问题：" + item.question() + "\n回答：" + item.answer())
            .reduce((left, right) -> left + "\n\n" + right)
            .orElse("暂无");
    String askedQuestions = memory.topicQuestions().stream()
        .map(InterviewQuestionDTO::question)
        .reduce((left, right) -> left + "\n- " + right)
        .map(value -> "- " + value)
        .orElse("- 无");

    return """
        【主问题】
        %s

        【JD 重点能力】
        %s

        【简历相关证据】
        %s

        【考察维度与当前覆盖度】
        %s

        【已覆盖主题】
        %s

        【最近问答，仅保留当前题最近 %d 轮】
        %s

        【本轮问题】
        %s

        【本轮回答】
        %s

        【本主题已问问题，不得重复】
        %s

        当前已经追问 %d 次，程序最多还允许追问 %d 次。请一次完成覆盖度更新、
        新证据摘要、能力缺口选择和下一问生成。追问上限不是必须追满的次数。
        """.formatted(
        sanitizeBlock("main_question", context.mainQuestion()),
        sanitizeList("jd_capabilities", context.jdCapabilities()),
        sanitizeList("resume_evidence", context.resumeEvidence()),
        sanitizeBlock("coverage", renderCoverage(context, state)),
        sanitizeList("covered_topics", state.coveredTopics()),
        properties.getDynamicRecentQaLimit(),
        sanitizeBlock("recent_qa", recentQa),
        sanitizeBlock("current_question", currentQuestion.question()),
        sanitizeBlock("current_answer", answer),
        sanitizeBlock("asked_questions", askedQuestions),
        memory.followUpCount(),
        remainingFollowUps);
  }

  private DynamicAnswerEvaluation normalize(
      DynamicFollowUpModelResult result,
      TopicMemory memory,
      InterviewQuestionDTO currentQuestion,
      String answer) {
    if (result == null) {
      return newTopic("模型未返回有效的覆盖分析，切换到下一主题", memory.state());
    }
    long now = System.currentTimeMillis();
    QuestionState updatedState = memory.state().update(
        result.coverage(),
        result.coveredTopics(),
        currentQuestion.question(),
        answer,
        properties.getDynamicRecentQaLimit(),
        now);
    String reason = truncate(result.reasoningSummary(), MAX_REASON_LENGTH);

    if (!Boolean.TRUE.equals(result.needFollowUp())) {
      return newTopic(reason.isBlank() ? "当前主题证据已充分" : reason, updatedState);
    }
    if (!hasImportantGap(memory.context(), updatedState)) {
      return newTopic("核心考察维度覆盖充分，切换到下一主题", updatedState);
    }
    if (updatedState.stagnantRounds() >= properties.getDynamicMaxStagnantRounds()) {
      return newTopic("连续回答未产生新的有效证据，切换到下一主题", updatedState);
    }
    if (memory.followUpCount() >= properties.getDynamicMaxFollowUpsPerTopic()) {
      return newTopic("当前主题已达到追问上限，切换到下一主题", updatedState);
    }
    if (hasTimedOut(updatedState, now)) {
      return newTopic("当前主题已达到最大考察时长，切换到下一主题", updatedState);
    }

    String nextQuestion = result.question() == null ? "" : result.question().trim();
    if (nextQuestion.isBlank()) {
      return newTopic("模型未生成有效追问，切换到下一主题", updatedState);
    }
    if (nextQuestion.length() > MAX_QUESTION_LENGTH) {
      log.info("Overlong dynamic follow-up rejected: length={}", nextQuestion.length());
      return newTopic("候选追问过长，为保持单点追问而切换到下一主题", updatedState);
    }
    if (isDuplicateQuestion(nextQuestion, memory.topicQuestions())) {
      log.info("Duplicate dynamic follow-up rejected: question={}", nextQuestion);
      return newTopic("候选追问与本主题已提问题语义重复，切换到下一主题", updatedState);
    }

    NextAction action = isClarification(result, updatedState)
        ? NextAction.CLARIFY
        : NextAction.DEEP_FOLLOW_UP;
    QuestionState acceptedState = updatedState.acceptFollowUp();
    return new DynamicAnswerEvaluation(action, nextQuestion, reason, acceptedState);
  }

  private TopicMemory resolveTopicMemory(
      InterviewQuestionDTO currentQuestion,
      List<InterviewQuestionDTO> questionHistory) {
    int parentIndex = currentQuestion.isFollowUp()
        && currentQuestion.parentQuestionIndex() != null
            ? currentQuestion.parentQuestionIndex()
            : currentQuestion.questionIndex();
    List<InterviewQuestionDTO> topicQuestions = questionHistory.stream()
        .filter(question -> belongsToTopic(question, parentIndex))
        .sorted(Comparator.comparingInt(InterviewQuestionDTO::questionIndex))
        .toList();
    InterviewQuestionDTO root = topicQuestions.stream()
        .filter(question -> question.questionIndex() == parentIndex)
        .findFirst()
        .orElse(currentQuestion);
    QuestionContext context = root.questionContext() != null
        ? root.questionContext()
        : fallbackContext(root);
    QuestionState state = root.questionState() != null
        ? root.questionState()
        : QuestionState.initial(context.dimensions());
    int persistedFollowUpCount = Math.max(
        state.followUpCount(),
        Math.toIntExact(topicQuestions.stream().filter(InterviewQuestionDTO::isFollowUp).count()));
    return new TopicMemory(parentIndex, context, state, topicQuestions, persistedFollowUpCount);
  }

  private QuestionContext fallbackContext(InterviewQuestionDTO root) {
    String capability = root.topicSummary() == null || root.topicSummary().isBlank()
        ? root.category()
        : root.topicSummary();
    return new QuestionContext(
        Integer.toString(root.questionIndex()),
        root.question(),
        List.of(capability),
        List.of(),
        QuestionContext.DEFAULT_DIMENSIONS);
  }

  private boolean hasImportantGap(QuestionContext context, QuestionState state) {
    double threshold = properties.getDynamicCoverageThreshold();
    return context.dimensions().stream()
        .anyMatch(dimension -> state.coverage().getOrDefault(dimension, 0.0) < threshold);
  }

  private boolean hasTimedOut(QuestionState state, long nowEpochMillis) {
    if (state.startedAtEpochMillis() == null) return false;
    Duration elapsed = Duration.ofMillis(nowEpochMillis - state.startedAtEpochMillis());
    return elapsed.compareTo(properties.getDynamicMaxTopicDuration()) >= 0;
  }

  private boolean isClarification(
      DynamicFollowUpModelResult result,
      QuestionState state) {
    if ("clarification".equalsIgnoreCase(result.targetDimension())) return true;
    return state.coverage().values().stream().mapToDouble(Double::doubleValue).sum() < 0.5;
  }

  private String renderCoverage(QuestionContext context, QuestionState state) {
    return context.dimensions().stream()
        .map(dimension -> "%s=%.2f".formatted(
            dimension,
            state.coverage().getOrDefault(dimension, 0.0)))
        .reduce((left, right) -> left + "\n" + right)
        .orElse("暂无维度");
  }

  private String sanitizeList(String label, List<String> values) {
    String rendered = values == null || values.isEmpty()
        ? "- 无"
        : values.stream().map(value -> "- " + value).reduce((a, b) -> a + "\n" + b).orElse("- 无");
    return sanitizeBlock(label, rendered);
  }

  private String sanitizeBlock(String label, String value) {
    return promptSanitizer.wrapWithDelimiters(label, promptSanitizer.sanitize(value));
  }

  private DynamicAnswerEvaluation newTopic(String reason, QuestionState state) {
    return new DynamicAnswerEvaluation(NextAction.NEW_TOPIC, null, reason, state);
  }

  private boolean belongsToTopic(InterviewQuestionDTO question, int parentIndex) {
    return question.questionIndex() == parentIndex
        || question.isFollowUp()
            && question.parentQuestionIndex() != null
            && question.parentQuestionIndex() == parentIndex;
  }

  private boolean isDuplicateQuestion(
      String candidate,
      List<InterviewQuestionDTO> topicQuestions) {
    String normalizedCandidate = normalizeQuestion(candidate);
    return topicQuestions.stream()
        .map(InterviewQuestionDTO::question)
        .map(this::normalizeQuestion)
        .anyMatch(previous -> isSemanticallySimilar(normalizedCandidate, previous));
  }

  private boolean isSemanticallySimilar(String left, String right) {
    if (left.equals(right)) return true;
    int shorterLength = Math.min(left.length(), right.length());
    int longerLength = Math.max(left.length(), right.length());
    if (shorterLength >= 8
        && longerLength <= Math.ceil(shorterLength * 1.35)
        && (left.contains(right) || right.contains(left))) {
      return true;
    }
    Set<String> leftBigrams = bigrams(left);
    Set<String> rightBigrams = bigrams(right);
    if (leftBigrams.isEmpty() || rightBigrams.isEmpty()) return false;
    long intersection = leftBigrams.stream().filter(rightBigrams::contains).count();
    double diceSimilarity = 2.0 * intersection / (leftBigrams.size() + rightBigrams.size());
    return diceSimilarity >= 0.82;
  }

  private Set<String> bigrams(String value) {
    Set<String> result = new HashSet<>();
    for (int index = 0; index < value.length() - 1; index++) {
      result.add(value.substring(index, index + 2));
    }
    return result;
  }

  private String normalizeQuestion(String question) {
    return question.toLowerCase(Locale.ROOT)
        .replaceAll("[\\s，。！？、；：,.!?;:\\-—_‘’“”()（）]", "")
        .replace("请你", "")
        .replace("请", "")
        .replace("能否", "")
        .replace("可以", "")
        .replace("具体", "")
        .replace("一下", "")
        .replace("再", "");
  }

  private String resolveProvider(String sessionProvider) {
    String configuredProvider = properties.getDynamicFollowUpProvider();
    return configuredProvider == null || configuredProvider.isBlank()
        ? sessionProvider
        : configuredProvider;
  }

  private boolean isExplicitUnknown(String answer) {
    if (answer == null) return true;
    String normalized = answer.trim().toLowerCase().replaceAll("[\\s，。！？,.!?]", "");
    if (normalized.length() > 30) return false;
    return normalized.matches(
        ".*(不知道|不清楚|不了解|不会|没接触过|没有接触过|没做过|下一题|换一题|skip|dontknow|donotknow).*");
  }

  private String truncate(String value, int limit) {
    if (value == null || value.isBlank()) return "";
    String trimmed = value.trim();
    return trimmed.substring(0, Math.min(trimmed.length(), limit));
  }

  private record TopicMemory(
      int parentIndex,
      QuestionContext context,
      QuestionState state,
      List<InterviewQuestionDTO> topicQuestions,
      int followUpCount) {
  }
}
