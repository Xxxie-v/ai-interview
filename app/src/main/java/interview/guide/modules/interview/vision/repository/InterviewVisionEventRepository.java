package interview.guide.modules.interview.vision.repository;

import interview.guide.modules.interview.vision.model.InterviewVisionEventEntity;
import interview.guide.modules.interview.vision.model.VisionEventType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewVisionEventRepository
    extends JpaRepository<InterviewVisionEventEntity, Long> {

  boolean existsBySessionIdAndEventTypeAndOccurredAtAfter(
      String sessionId,
      VisionEventType eventType,
      LocalDateTime cutoff);

  List<InterviewVisionEventEntity> findBySessionIdOrderByOccurredAtAsc(String sessionId);

  Optional<InterviewVisionEventEntity> findBySessionIdAndClientEventId(
      String sessionId,
      String clientEventId);
}
