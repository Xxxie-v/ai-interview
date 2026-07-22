package interview.guide.modules.interview.video.repository;

import interview.guide.modules.interview.video.model.InterviewVideoEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewVideoRepository extends JpaRepository<InterviewVideoEntity, Long> {

  Optional<InterviewVideoEntity> findBySessionIdAndChunkIndex(String sessionId, Integer chunkIndex);

  List<InterviewVideoEntity> findBySessionIdOrderByChunkIndexAsc(String sessionId);

  List<InterviewVideoEntity> findTop100ByCreatedAtBeforeOrderByCreatedAtAsc(
      LocalDateTime cutoff);
}
