package interview.guide.modules.interview.report.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.report.model.EnterpriseReportAssessment;
import interview.guide.modules.interview.report.model.InterviewReportEntity;
import interview.guide.modules.interview.report.repository.InterviewReportRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class EnterpriseReportPersistenceService {

  private final InterviewReportRepository repository;
  private final ObjectMapper objectMapper;

  @Transactional(readOnly = true)
  public Optional<InterviewReportEntity> findBySessionId(String sessionId) {
    return repository.findBySessionId(sessionId);
  }

  @Transactional
  public InterviewReportEntity save(
      String sessionId,
      Long assignmentId,
      int overallScore,
      EnterpriseReportAssessment assessment) {
    try {
      InterviewReportEntity entity = repository.findBySessionId(sessionId)
          .orElseGet(InterviewReportEntity::new);
      entity.setSessionId(sessionId);
      entity.setAssignmentId(assignmentId);
      entity.setOverallScore(overallScore);
      entity.setTechnicalScore(assessment.technicalScore());
      entity.setCommunicationScore(assessment.communicationScore());
      entity.setJobMatchScore(assessment.jobMatchScore());
      entity.setStrengthsJson(toJson(assessment.strengths()));
      entity.setWeaknessesJson(toJson(assessment.weaknesses()));
      entity.setRiskNotesJson(toJson(assessment.riskNotes()));
      entity.setSummary(assessment.summary());
      entity.setRecommendation(assessment.recommendation());
      return repository.save(entity);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "最终报告保存失败", e);
    }
  }

  private String toJson(List<String> values) throws JacksonException {
    return objectMapper.writeValueAsString(values == null ? List.of() : values);
  }
}
