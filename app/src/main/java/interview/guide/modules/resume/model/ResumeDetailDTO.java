package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewHistoryItemDTO;
import java.time.LocalDateTime;
import java.util.List;

public record ResumeDetailDTO(
    Long id,
    String filename,
    Long fileSize,
    String contentType,
    String storageUrl,
    LocalDateTime uploadedAt,
    Integer accessCount,
    String resumeText,
    AsyncTaskStatus questionPrepareStatus,
    String questionPrepareError,
    LocalDateTime questionsPreparedAt,
    List<InterviewHistoryItemDTO> interviews
) {
  /** Legacy mapping target retained for reading historical data internally. */
  public record AnalysisHistoryDTO(
      Long id,
      Integer overallScore,
      Integer contentScore,
      Integer structureScore,
      Integer skillMatchScore,
      Integer expressionScore,
      Integer projectScore,
      String summary,
      LocalDateTime analyzedAt,
      List<String> strengths,
      List<Object> suggestions
  ) {}
}
