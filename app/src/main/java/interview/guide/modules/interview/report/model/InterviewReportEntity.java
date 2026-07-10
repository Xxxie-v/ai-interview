package interview.guide.modules.interview.report.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "interview_reports", indexes = {
    @Index(name = "idx_report_assignment", columnList = "assignment_id"),
    @Index(name = "idx_report_generated", columnList = "generated_at")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_report_session", columnNames = "session_id")
})
public class InterviewReportEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false, length = 36)
  private String sessionId;

  @Column(name = "assignment_id")
  private Long assignmentId;

  @Column(name = "overall_score", nullable = false)
  private Integer overallScore;

  @Column(name = "technical_score", nullable = false)
  private Integer technicalScore;

  @Column(name = "communication_score", nullable = false)
  private Integer communicationScore;

  @Column(name = "job_match_score", nullable = false)
  private Integer jobMatchScore;

  @Column(name = "strengths_json", nullable = false, columnDefinition = "TEXT")
  private String strengthsJson;

  @Column(name = "weaknesses_json", nullable = false, columnDefinition = "TEXT")
  private String weaknessesJson;

  @Column(name = "risk_notes_json", nullable = false, columnDefinition = "TEXT")
  private String riskNotesJson;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String summary;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String recommendation;

  @Column(name = "generated_at", nullable = false)
  private LocalDateTime generatedAt;

  @PrePersist
  void prePersist() {
    if (generatedAt == null) {
      generatedAt = LocalDateTime.now();
    }
  }
}
