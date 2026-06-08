package interview.guide.infrastructure.mapper;

import interview.guide.modules.interview.report.model.EnterpriseInterviewReportDTO;
import interview.guide.modules.interview.report.model.InterviewViolationConclusion;
import interview.guide.modules.interview.report.model.InterviewReportEntity;
import interview.guide.modules.interview.vision.model.InterviewVisionEventDTO;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface EnterpriseInterviewReportMapper {

  default EnterpriseInterviewReportDTO toDTO(
      InterviewReportEntity entity,
      List<String> strengths,
      List<String> weaknesses,
      List<String> riskNotes,
      InterviewViolationConclusion violationConclusion,
      List<InterviewVisionEventDTO> objectiveVisionEvents) {
    return new EnterpriseInterviewReportDTO(
        entity.getSessionId(),
        entity.getAssignmentId(),
        entity.getOverallScore(),
        entity.getTechnicalScore(),
        entity.getCommunicationScore(),
        entity.getJobMatchScore(),
        strengths,
        weaknesses,
        riskNotes,
        entity.getSummary(),
        entity.getRecommendation(),
        violationConclusion,
        objectiveVisionEvents,
        entity.getGeneratedAt());
  }
}
