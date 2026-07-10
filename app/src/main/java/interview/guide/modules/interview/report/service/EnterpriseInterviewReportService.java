package interview.guide.modules.interview.report.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.EnterpriseInterviewReportMapper;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.report.model.EnterpriseInterviewReportDTO;
import interview.guide.modules.interview.report.model.EnterpriseReportAssessment;
import interview.guide.modules.interview.report.model.InterviewReportEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.vision.model.InterviewVisionEventDTO;
import interview.guide.modules.interview.vision.service.InterviewVisionEventPersistenceService;
import interview.guide.modules.recruitment.model.InterviewAssignmentEntity;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.repository.InterviewAssignmentRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class EnterpriseInterviewReportService {

  private final InterviewSessionRepository sessionRepository;
  private final InterviewAssignmentRepository assignmentRepository;
  private final JobPositionRepository jobRepository;
  private final EnterpriseReportAssessmentService assessmentService;
  private final EnterpriseReportPersistenceService persistenceService;
  private final InterviewVisionEventPersistenceService visionEventPersistenceService;
  private final InterviewViolationAssessmentService violationAssessmentService;
  private final EnterpriseInterviewReportMapper mapper;
  private final ObjectMapper objectMapper;

  public EnterpriseInterviewReportDTO getForAdmin(String sessionId) {
    InterviewSessionEntity session = requireSession(sessionId);
    return getPersisted(session);
  }

  public EnterpriseInterviewReportDTO getForCandidate(
      String sessionId,
      Long candidateId) {
    InterviewSessionEntity session = assertCandidateCanView(sessionId, candidateId);
    return getPersisted(session);
  }

  public InterviewSessionEntity assertCandidateCanView(String sessionId, Long candidateId) {
    InterviewSessionEntity session = requireSession(sessionId);
    if (!candidateId.equals(session.getOwnerUserId())) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    if (isCandidateReportVisible(session, candidateId)) {
      return session;
    }
    throw new BusinessException(
        ErrorCode.INTERVIEW_REPORT_NOT_VISIBLE,
        "管理员尚未开放该面试报告");
  }

  public boolean isCandidateReportVisible(
      InterviewSessionEntity session,
      Long candidateId) {
    if (!candidateId.equals(session.getOwnerUserId())) {
      return false;
    }
    if (!session.isOfficialInterview()) {
      return true;
    }
    if (session.getAssignmentId() == null) {
      return false;
    }
    return assignmentRepository.findById(session.getAssignmentId())
        .filter(assignment -> assignment.getCandidateId().equals(candidateId))
        .map(InterviewAssignmentEntity::isReportVisibleToCandidate)
        .orElse(false);
  }

  public EnterpriseInterviewReportDTO generateFromEvaluation(
      String sessionId,
      InterviewReportDTO baseReport) {
    InterviewSessionEntity session = requireSession(sessionId);
    if (session.getStatus() != InterviewSessionEntity.SessionStatus.COMPLETED
        && session.getStatus() != InterviewSessionEntity.SessionStatus.EVALUATED) {
      throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED);
    }
    return persistenceService.findBySessionId(sessionId)
        .map(this::toDTO)
        .orElseGet(() -> generate(session, baseReport));
  }

  private EnterpriseInterviewReportDTO getPersisted(InterviewSessionEntity session) {
    if (session.getStatus() != InterviewSessionEntity.SessionStatus.COMPLETED
        && session.getStatus() != InterviewSessionEntity.SessionStatus.EVALUATED) {
      throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED);
    }
    return persistenceService.findBySessionId(session.getSessionId())
        .map(this::toDTO)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.INTERVIEW_REPORT_NOT_FOUND,
            "企业报告正在生成，请稍后重试"));
  }

  private EnterpriseInterviewReportDTO generate(
      InterviewSessionEntity session,
      InterviewReportDTO baseReport) {
    JobPositionEntity job = session.getJobId() == null
        ? null
        : jobRepository.findById(session.getJobId()).orElse(null);
    EnterpriseReportAssessment assessment = assessmentService.assess(
        session.getLlmProvider(), job, baseReport);
    InterviewReportEntity saved = persistenceService.save(
        session.getSessionId(),
        session.getAssignmentId(),
        baseReport.overallScore(),
        assessment);
    return toDTO(saved);
  }

  private EnterpriseInterviewReportDTO toDTO(InterviewReportEntity entity) {
    List<InterviewVisionEventDTO> events = visionEventPersistenceService
        .listBySessionId(entity.getSessionId()).stream()
        .map(InterviewVisionEventDTO::from)
        .toList();
    var violationConclusion = violationAssessmentService.assess(events);
    return mapper.toDTO(
        entity,
        readList(entity.getStrengthsJson()),
        readList(entity.getWeaknessesJson()),
        readList(entity.getRiskNotesJson()),
        violationConclusion,
        events);
  }

  private List<String> readList(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "最终报告读取失败", e);
    }
  }

  private InterviewSessionEntity requireSession(String sessionId) {
    return sessionRepository.findBySessionId(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
  }

}
