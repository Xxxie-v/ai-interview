package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.InterviewAssignmentEntity;
import interview.guide.modules.recruitment.model.AssignmentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewAssignmentRepository
    extends JpaRepository<InterviewAssignmentEntity, Long> {

  boolean existsByJobId(Long jobId);

  boolean existsByCandidateIdAndJobId(Long candidateId, Long jobId);

  List<InterviewAssignmentEntity> findByCandidateIdOrderByCreatedAtDesc(Long candidateId);

  Optional<InterviewAssignmentEntity> findByIdAndCandidateId(Long id, Long candidateId);

  Optional<InterviewAssignmentEntity>
      findFirstByCandidateIdAndJobIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
          Long candidateId,
          Long jobId,
          Long resumeId,
          List<AssignmentStatus> statuses);
}
