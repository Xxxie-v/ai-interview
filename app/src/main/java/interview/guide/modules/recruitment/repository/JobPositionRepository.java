package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.JobStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPositionRepository extends JpaRepository<JobPositionEntity, Long> {

  List<JobPositionEntity> findByStatusOrderByCreatedAtDesc(JobStatus status);

  Optional<JobPositionEntity> findByIdAndStatus(Long id, JobStatus status);
}
