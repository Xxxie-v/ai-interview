package interview.guide.modules.interview.report.model;

import interview.guide.modules.interview.vision.model.InterviewVisionEventDTO;
import java.time.LocalDateTime;
import java.util.List;

public record EnterpriseInterviewReportDTO(
    String sessionId,
    Long assignmentId,
    int overallScore,
    int technicalScore,
    int communicationScore,
    int jobMatchScore,
    List<String> strengths,
    List<String> weaknesses,
    List<String> riskNotes,
    String summary,
    String recommendation,
    InterviewViolationConclusion violationConclusion,
    List<InterviewVisionEventDTO> objectiveVisionEvents,
    LocalDateTime generatedAt) {
}
