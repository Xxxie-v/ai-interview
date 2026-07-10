package interview.guide.modules.interview.report.repository;

import interview.guide.modules.interview.report.model.InterviewReportEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewReportRepository extends JpaRepository<InterviewReportEntity, Long> {

  Optional<InterviewReportEntity> findBySessionId(String sessionId);
}
