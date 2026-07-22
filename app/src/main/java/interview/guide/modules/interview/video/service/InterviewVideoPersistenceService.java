package interview.guide.modules.interview.video.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.video.model.InterviewVideoEntity;
import interview.guide.modules.interview.video.model.VideoStatus;
import interview.guide.modules.interview.video.repository.InterviewVideoRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InterviewVideoPersistenceService {

  private final InterviewVideoRepository repository;

  @Transactional
  public InterviewVideoEntity reserveChunk(
      String sessionId,
      Long userId,
      int chunkIndex,
      String objectKey,
      String mimeType,
      long fileSize,
      long durationMs,
      String checksum) {
    InterviewVideoEntity existing = repository.findBySessionIdAndChunkIndex(sessionId, chunkIndex)
        .orElse(null);
    if (existing != null) {
      if (!existing.getChecksum().equalsIgnoreCase(checksum)) {
        throw new BusinessException(
            ErrorCode.INTERVIEW_VIDEO_CHUNK_CONFLICT,
            "同一分片序号不能上传不同内容");
      }
      if (existing.getStatus() != VideoStatus.UPLOADED) {
        existing.setStatus(VideoStatus.UPLOADING);
        existing.setUpdatedAt(LocalDateTime.now());
      }
      return existing;
    }

    InterviewVideoEntity entity = InterviewVideoEntity.builder()
        .sessionId(sessionId)
        .userId(userId)
        .objectKey(objectKey)
        .mimeType(mimeType)
        .fileSize(fileSize)
        .durationMs(durationMs)
        .chunkIndex(chunkIndex)
        .checksum(checksum)
        .status(VideoStatus.UPLOADING)
        .build();
    try {
      return repository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "该视频分片正在上传，请稍后重试",
          e);
    }
  }

  @Transactional
  public InterviewVideoEntity markUploaded(Long id) {
    InterviewVideoEntity entity = findRequired(id);
    entity.setStatus(VideoStatus.UPLOADED);
    entity.setUpdatedAt(LocalDateTime.now());
    return repository.save(entity);
  }

  @Transactional
  public void markFailed(Long id) {
    InterviewVideoEntity entity = findRequired(id);
    entity.setStatus(VideoStatus.FAILED);
    entity.setUpdatedAt(LocalDateTime.now());
    repository.save(entity);
  }

  @Transactional(readOnly = true)
  public List<InterviewVideoEntity> findBySessionId(String sessionId) {
    return repository.findBySessionIdOrderByChunkIndexAsc(sessionId);
  }

  @Transactional(readOnly = true)
  public List<InterviewVideoEntity> findExpired(LocalDateTime cutoff) {
    return repository.findTop100ByCreatedAtBeforeOrderByCreatedAtAsc(cutoff);
  }

  @Transactional
  public void delete(Long id) {
    repository.deleteById(id);
  }

  @Transactional(readOnly = true)
  public InterviewVideoEntity findRequired(Long id) {
    return repository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "视频分片不存在"));
  }
}
