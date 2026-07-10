package interview.guide.modules.interview.service;

import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.common.ai.routing.LlmTaskType;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewPlanningContext;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPlannerService {

  private final InterviewQuestionService questionService;
  private final InterviewQuestionProperties properties;
  private final InterviewQuestionProviderResolver questionProviderResolver;
  private final LlmTaskRouter taskRouter;

  public List<InterviewQuestionDTO> planQuestions(
      String llmProvider,
      String skillId,
      String difficulty,
      int questionCount,
      InterviewPlanningContext context) {
    List<InterviewQuestionDTO> planned;
    if (context.jobId() != null) {
      planned = taskRouter.execute(
          LlmTaskType.QUESTION_GENERATE,
          questionProviderResolver.resolve(),
          routedProvider -> assembleOfficialQuestions(routedProvider, questionCount, context));
    } else {
      List<InterviewQuestionDTO> generated = taskRouter.execute(
          LlmTaskType.QUESTION_GENERATE,
          llmProvider,
          routedProvider -> questionService.generateQuestionsBySkill(
              routedProvider,
              skillId,
              difficulty,
              context.resumeText(),
              questionCount,
              context.history(),
              context.categories(),
              context.jobDescription()));
      planned = reindex(generated, questionCount);
    }
    return QuestionContextFactory.enrich(
        planned,
        context.jobDescription(),
        context.resumeText());
  }

  private List<InterviewQuestionDTO> assembleOfficialQuestions(
      String llmProvider,
      int requestedCount,
      InterviewPlanningContext context) {
    int min = properties.getOfficialMinQuestionsPerSource();
    int max = properties.getOfficialMaxQuestionsPerSource();
    int total = Math.max(min * 2, Math.min(max * 2, requestedCount));
    int jobCount = Math.max(min, Math.min(max, total / 2));
    int resumeCount = Math.max(min, Math.min(max, total - jobCount));

    List<InterviewQuestionDTO> fixed = new ArrayList<>(context.fixedJobQuestions());
    List<InterviewQuestionDTO> resume = new ArrayList<>(
        questionService.generateJobMatchedResumeQuestions(
            llmProvider,
            context.resumeText(),
            context.jobDescription(),
            resumeCount,
            context.history()));
    Collections.shuffle(resume);
    resume.sort((left, right) -> Boolean.compare(
        isHistoricalDuplicate(left, context.history()),
        isHistoricalDuplicate(right, context.history())));
    List<InterviewQuestionDTO> combined = new ArrayList<>(total);
    for (int index = 0; index < Math.max(jobCount, resumeCount); index++) {
      if (index < jobCount && index < fixed.size()) combined.add(fixed.get(index));
      if (index < resumeCount && index < resume.size()) combined.add(resume.get(index));
    }
    log.info(
        "Official interview assembled: candidateId={}, jobId={}, fixed={}, resume={}, total={}",
        context.candidateId(), context.jobId(), Math.min(jobCount, fixed.size()),
        Math.min(resumeCount, resume.size()), combined.size());
    return reindex(combined, total);
  }

  private boolean isHistoricalDuplicate(
      InterviewQuestionDTO question,
      List<HistoricalQuestion> history) {
    if (history == null || history.isEmpty()) return false;
    String normalizedQuestion = normalize(question.question());
    String normalizedTopic = normalize(question.topicSummary());
    return history.stream().anyMatch(item ->
        (!normalizedQuestion.isEmpty() && normalizedQuestion.equals(normalize(item.question())))
            || (!normalizedTopic.isEmpty()
                && normalizedTopic.equals(normalize(item.topicSummary()))));
  }

  private String normalize(String value) {
    if (value == null) return "";
    return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
  }

  private List<InterviewQuestionDTO> reindex(
      List<InterviewQuestionDTO> generated,
      int questionCount) {
    List<InterviewQuestionDTO> mainQuestions = generated.stream()
        .filter(question -> !question.isFollowUp())
        .limit(questionCount)
        .toList();
    List<InterviewQuestionDTO> reindexed = new ArrayList<>(mainQuestions.size());
    for (int index = 0; index < mainQuestions.size(); index++) {
      InterviewQuestionDTO question = mainQuestions.get(index);
      reindexed.add(InterviewQuestionDTO.create(
          index,
          question.question(),
          question.type(),
          question.category(),
          question.topicSummary(),
          false,
          null));
    }
    return reindexed;
  }
}
