package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import java.time.LocalDateTime;

public record ResumeListItemDTO(
    Long id,
    String filename,
    Long fileSize,
    LocalDateTime uploadedAt,
    Integer accessCount,
    Integer interviewCount,
    AsyncTaskStatus questionPrepareStatus,
    String questionPrepareError,
    LocalDateTime questionsPreparedAt
) {}
