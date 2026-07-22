package interview.guide.modules.interview.video.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.ObjectStorageService;
import interview.guide.modules.interview.video.model.InterviewVideoEntity;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewVideoRetentionService {

  private final InterviewVideoProperties properties;
  private final InterviewVideoPersistenceService persistenceService;
  private final ObjectStorageService objectStorageService;

  @Scheduled(cron = "${app.interview.video.cleanup-cron:0 30 3 * * *}")
  public void deleteExpiredChunks() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
    for (InterviewVideoEntity video : persistenceService.findExpired(cutoff)) {
      try {
        objectStorageService.delete(video.getObjectKey());
        persistenceService.delete(video.getId());
        log.info("Expired interview video deleted: videoId={}, sessionId={}",
            video.getId(), video.getSessionId());
      } catch (BusinessException e) {
        log.error("Expired interview video deletion failed: videoId={}", video.getId(), e);
      }
    }
  }
}
