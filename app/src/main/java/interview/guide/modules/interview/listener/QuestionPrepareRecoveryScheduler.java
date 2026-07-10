package interview.guide.modules.interview.listener;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import java.time.LocalDateTime;
import java.util.List;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionPrepareRecoveryScheduler {

  private static final int RECOVERY_BATCH_SIZE = 50;
  private static final int STALE_AFTER_SECONDS = 90;

  private final InterviewSessionRepository sessionRepository;
  private final InterviewPersistenceService persistenceService;
  private final QuestionPrepareStreamProducer producer;

  @PostConstruct
  public void reconcileExistingSessions() {
    int reconciled = persistenceService.reconcileExistingPreparedQuestions();
    if (reconciled > 0) {
      log.info("Reconciled existing prepared interview sessions: count={}", reconciled);
    }
  }

  @Scheduled(
      fixedDelayString = "${app.interview.question-prepare-recovery-interval-ms:30000}",
      initialDelayString = "${app.interview.question-prepare-recovery-initial-delay-ms:30000}")
  public void recoverStaleTasks() {
    List<AsyncTaskStatus> recoverable = List.of(
        AsyncTaskStatus.PENDING,
        AsyncTaskStatus.PROCESSING);
    var sessions = sessionRepository.findStaleQuestionPreparationSessions(
        InterviewSessionEntity.SessionStatus.CREATED,
        recoverable,
        LocalDateTime.now().minusSeconds(STALE_AFTER_SECONDS),
        PageRequest.of(0, RECOVERY_BATCH_SIZE));
    for (var session : sessions) {
      try {
        persistenceService.updateQuestionPrepareStatus(
            session.getSessionId(),
            AsyncTaskStatus.PENDING,
            null);
        producer.sendQuestionPrepareTask(session.getSessionId());
        log.info("Recovered stale question preparation: sessionId={}", session.getSessionId());
      } catch (Exception e) {
        log.error(
            "Failed to recover stale question preparation: sessionId={}",
            session.getSessionId(),
            e);
      }
    }
  }
}
