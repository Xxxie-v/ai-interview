package interview.guide.modules.interview.report.model;

import java.util.List;

public record EnterpriseReportAssessment(
    Integer technicalScore,
    Integer communicationScore,
    Integer jobMatchScore,
    List<String> strengths,
    List<String> weaknesses,
    List<String> riskNotes,
    String summary,
    String recommendation) {
}
