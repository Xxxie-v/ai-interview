package interview.guide.modules.interview.report.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.common.ai.routing.LlmTaskType;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.report.model.EnterpriseReportAssessment;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class EnterpriseReportAssessmentService {

  private final StructuredOutputInvoker invoker;
  private final LlmProviderRegistry providerRegistry;
  private final LlmTaskRouter taskRouter;
  private final PromptSanitizer promptSanitizer;
  private final BeanOutputConverter<EnterpriseReportAssessment> outputConverter;
  private final String systemPrompt;

  public EnterpriseReportAssessmentService(
      StructuredOutputInvoker invoker,
      LlmProviderRegistry providerRegistry,
      LlmTaskRouter taskRouter,
      PromptSanitizer promptSanitizer,
      ResourceLoader resourceLoader) throws IOException {
    this.invoker = invoker;
    this.providerRegistry = providerRegistry;
    this.taskRouter = taskRouter;
    this.promptSanitizer = promptSanitizer;
    this.outputConverter = new BeanOutputConverter<>(EnterpriseReportAssessment.class);
    this.systemPrompt = resourceLoader
        .getResource("classpath:prompts/enterprise-interview-report-system.st")
        .getContentAsString(StandardCharsets.UTF_8);
  }

  public EnterpriseReportAssessment assess(
      String provider,
      JobPositionEntity job,
      InterviewReportDTO baseReport) {
    String jobContext = job == null
        ? "未绑定企业岗位，按通用岗位能力评估"
        : "岗位：%s\n级别：%s\nJD：%s\n要求：%s".formatted(
            job.getName(), job.getLevel(), job.getDescription(), job.getRequirements());
    String qaContext = baseReport.questionDetails().stream()
        .map(item -> "问题：%s\n回答：%s\n评分：%d\n反馈：%s".formatted(
            item.question(), item.userAnswer(), item.score(), item.feedback()))
        .reduce((left, right) -> left + "\n\n" + right)
        .orElse("无有效问答");
    String userPrompt = """
        基础总分：%d
        已有优势：%s
        已有改进建议：%s

        %s

        问答与结构化评分：
        %s
        """.formatted(
            baseReport.overallScore(),
            baseReport.strengths(),
            baseReport.improvements(),
            promptSanitizer.wrapWithDelimiters(
                "job", promptSanitizer.sanitize(jobContext)),
            promptSanitizer.wrapWithDelimiters(
                "qa", promptSanitizer.sanitize(qaContext)));
    EnterpriseReportAssessment result = taskRouter.execute(
        LlmTaskType.REPORT,
        provider,
        routedProvider -> invoker.invokeOnce(
            providerRegistry.getPlainChatClient(routedProvider),
            systemPrompt + "\n\n" + outputConverter.getFormat(),
            userPrompt,
            outputConverter,
            null,
            ErrorCode.INTERVIEW_EVALUATION_FAILED,
            "最终报告生成失败：",
            "enterprise-report",
            log));
    return normalize(result, baseReport);
  }

  private EnterpriseReportAssessment normalize(
      EnterpriseReportAssessment assessment,
      InterviewReportDTO baseReport) {
    return new EnterpriseReportAssessment(
        clamp(assessment.technicalScore(), baseReport.overallScore()),
        clamp(assessment.communicationScore(), baseReport.overallScore()),
        clamp(assessment.jobMatchScore(), baseReport.overallScore()),
        safeList(assessment.strengths(), baseReport.strengths()),
        safeList(assessment.weaknesses(), baseReport.improvements()),
        safeList(assessment.riskNotes(), List.of()),
        safeText(assessment.summary(), baseReport.overallFeedback()),
        safeText(assessment.recommendation(), "建议结合人工复核后决策"));
  }

  private int clamp(Integer score, int fallback) {
    return Math.max(0, Math.min(100, score == null ? fallback : score));
  }

  private List<String> safeList(List<String> values, List<String> fallback) {
    return values == null ? fallback : values.stream()
        .filter(value -> value != null && !value.isBlank())
        .limit(8)
        .toList();
  }

  private String safeText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
