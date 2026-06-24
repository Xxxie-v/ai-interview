package interview.guide.modules.resume.service;

import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.common.ai.routing.LlmTaskType;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.service.InterviewQuestionService;
import interview.guide.modules.interview.service.InterviewQuestionProperties;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeQuestionPreparationService {

  private final ResumeRepository resumeRepository;
  private final InterviewQuestionService questionService;
  private final InterviewQuestionProperties properties;
  private final ObjectMapper objectMapper;
  private final LlmTaskRouter taskRouter;

  public void prepare(Long resumeId) {
    ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
    if (resume == null || resume.getResumeText() == null || resume.getResumeText().isBlank()) {
      return;
    }
    resume.setQuestionPrepareStatus(AsyncTaskStatus.PROCESSING);
    resume.setQuestionPrepareError(null);
    resumeRepository.save(resume);
    try {
      List<InterviewQuestionDTO> questions = taskRouter.execute(
          LlmTaskType.QUESTION_GENERATE,
          null,
          routedProvider -> questionService.generateResumeQuestionsForPreparation(
              routedProvider,
              resume.getResumeText(),
              Math.max(3, Math.min(6, properties.getResumePreparedQuestionCount()))));
      ResumeEntity current = resumeRepository.findById(resumeId).orElse(null);
      if (current == null) return;
      current.setPreparedQuestionsJson(objectMapper.writeValueAsString(questions));
      current.setQuestionPrepareStatus(AsyncTaskStatus.COMPLETED);
      current.setQuestionsPreparedAt(LocalDateTime.now());
      resumeRepository.save(current);
      log.info("Resume questions prepared: resumeId={}, questions={}", resumeId, questions.size());
    } catch (Exception e) {
      markFailed(resumeId, e);
      if (e instanceof BusinessException businessException) {
        throw businessException;
      }
      throw new BusinessException(
          ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
          "简历出题失败",
          e);
    }
  }

  private void markFailed(Long resumeId, Exception error) {
    resumeRepository.findById(resumeId).ifPresent(current -> {
      current.setQuestionPrepareStatus(AsyncTaskStatus.FAILED);
      current.setQuestionPrepareError(truncate(error.getMessage()));
      resumeRepository.save(current);
    });
    log.error("Resume question preparation failed: resumeId={}", resumeId, error);
  }

  private String truncate(String value) {
    if (value == null) return "unknown";
    return value.length() <= 500 ? value : value.substring(0, 500);
  }
}
