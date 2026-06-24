package interview.guide.modules.recruitment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
@Table(name = "interview_assignment", indexes = {
    @Index(name = "idx_assignment_candidate_status", columnList = "candidate_id,status"),
    @Index(name = "idx_assignment_job", columnList = "job_id"),
    @Index(name = "idx_assignment_deadline", columnList = "deadline")
}, uniqueConstraints = {
    @UniqueConstraint(
        name = "uk_interview_assignment_candidate_job",
        columnNames = {"candidate_id", "job_id"})
})
public class InterviewAssignmentEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "candidate_id", nullable = false)
  private Long candidateId;

  @Column(name = "job_id", nullable = false)
  private Long jobId;

  @Column(name = "resume_id")
  private Long resumeId;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private AssignmentStatus status = AssignmentStatus.PENDING;

  @Column(name = "available_from", nullable = false)
  private LocalDateTime availableFrom;

  @Column(nullable = false)
  private LocalDateTime deadline;

  @Builder.Default
  @Column(name = "report_visible_to_candidate", nullable = false)
  private boolean reportVisibleToCandidate = false;

  @Column(name = "created_by", nullable = false)
  private Long createdBy;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
    if (availableFrom == null) {
      availableFrom = now;
    }
    if (status == null) {
      status = AssignmentStatus.PENDING;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
