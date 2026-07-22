package interview.guide.modules.interview.video.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.ObjectAccessResponse;
import interview.guide.infrastructure.file.ObjectAccessService;
import interview.guide.infrastructure.file.ObjectStorageService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.video.model.InterviewVideoDTO;
import interview.guide.modules.interview.video.model.InterviewVideoEntity;
import interview.guide.modules.interview.video.model.VideoStatus;
import interview.guide.modules.interview.video.model.VideoUploadCompleteResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewVideoService {

  private final InterviewSessionRepository sessionRepository;
  private final InterviewVideoPersistenceService persistenceService;
  private final InterviewVideoProperties properties;
  private final FileHashService fileHashService;
  private final ObjectStorageService objectStorageService;
  private final ObjectAccessService objectAccessService;

  public InterviewVideoDTO uploadChunk(
      String sessionId,
      Long userId,
      int chunkIndex,
      long durationMs,
      String expectedChecksum,
      MultipartFile file) {
    requireOwnedSession(sessionId, userId);
    validateChunk(file, chunkIndex, durationMs, expectedChecksum);
    String actualChecksum = calculateChecksum(file);
    if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "视频分片校验失败，请重新上传");
    }

    String mimeType = file.getContentType().toLowerCase(Locale.ROOT);
    String objectKey = generateObjectKey(sessionId, chunkIndex, mimeType);
    InterviewVideoEntity entity = persistenceService.reserveChunk(
        sessionId,
        userId,
        chunkIndex,
        objectKey,
        mimeType,
        file.getSize(),
        durationMs,
        actualChecksum);
    if (entity.getStatus() == VideoStatus.UPLOADED) {
      return InterviewVideoDTO.from(entity);
    }

    try {
      objectStorageService.upload(entity.getObjectKey(), file);
      InterviewVideoEntity uploaded = persistenceService.markUploaded(entity.getId());
      log.info(
          "Interview video chunk uploaded: sessionId={}, chunkIndex={}, size={}",
          sessionId,
          chunkIndex,
          file.getSize());
      return InterviewVideoDTO.from(uploaded);
    } catch (BusinessException e) {
      persistenceService.markFailed(entity.getId());
      throw e;
    }
  }

  public VideoUploadCompleteResponse completeUpload(String sessionId, Long userId) {
    requireOwnedSession(sessionId, userId);
    List<InterviewVideoEntity> chunks = persistenceService.findBySessionId(sessionId);
    if (chunks.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "尚未上传任何视频分片");
    }
    boolean complete = chunks.stream().allMatch(chunk -> chunk.getStatus() == VideoStatus.UPLOADED);
    if (!complete) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "仍有视频分片未上传成功");
    }
    for (int index = 0; index < chunks.size(); index++) {
      if (chunks.get(index).getChunkIndex() != index) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "视频分片不连续，请补传后重试");
      }
    }
    return new VideoUploadCompleteResponse(
        sessionId,
        chunks.size(),
        chunks.stream().mapToLong(InterviewVideoEntity::getFileSize).sum(),
        chunks.stream().mapToLong(InterviewVideoEntity::getDurationMs).sum(),
        true);
  }

  public List<InterviewVideoDTO> listOwned(String sessionId, Long userId) {
    requireOwnedSession(sessionId, userId);
    return listForAdmin(sessionId);
  }

  public List<InterviewVideoDTO> listForAdmin(String sessionId) {
    if (!sessionRepository.existsBySessionId(sessionId)) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    return persistenceService.findBySessionId(sessionId).stream()
        .map(InterviewVideoDTO::from)
        .toList();
  }

  public ObjectAccessResponse createAdminAccess(String sessionId, Long videoId) {
    InterviewVideoEntity video = requireAdminVideo(sessionId, videoId);
    if (video.getStatus() != VideoStatus.UPLOADED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "视频分片尚未上传完成");
    }
    return objectAccessService.createAccess(
        video.getObjectKey(),
        responseMimeType(video.getMimeType()),
        "/api/admin/interviews/" + sessionId + "/videos/" + videoId + "/content");
  }

  public VideoContent getAdminContent(String sessionId, Long videoId) {
    InterviewVideoEntity video = requireAdminVideo(sessionId, videoId);
    return new VideoContent(
        objectAccessService.download(video.getObjectKey()),
        responseMimeType(video.getMimeType()));
  }

  public CombinedVideo getAdminCombinedVideo(String sessionId) {
    if (!sessionRepository.existsBySessionId(sessionId)) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    List<InterviewVideoEntity> chunks = persistenceService.findBySessionId(sessionId);
    if (chunks.isEmpty()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "尚未上传面试视频");
    }
    if (chunks.stream().anyMatch(chunk -> chunk.getStatus() != VideoStatus.UPLOADED)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "视频分片尚未全部上传完成");
    }
    for (int index = 0; index < chunks.size(); index++) {
      if (chunks.get(index).getChunkIndex() != index) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "视频分片不连续，无法播放完整视频");
      }
    }
    return new CombinedVideo(
        responseMimeType(chunks.getFirst().getMimeType()),
        chunks.stream().mapToLong(InterviewVideoEntity::getFileSize).sum(),
        chunks.stream().mapToLong(InterviewVideoEntity::getDurationMs).sum(),
        chunks.stream().map(InterviewVideoEntity::getObjectKey).toList());
  }

  public void writeCombinedVideo(CombinedVideo video, OutputStream outputStream)
      throws IOException {
    for (String objectKey : video.objectKeys()) {
      outputStream.write(objectAccessService.download(objectKey));
      outputStream.flush();
    }
  }

  private InterviewVideoEntity requireAdminVideo(String sessionId, Long videoId) {
    if (!sessionRepository.existsBySessionId(sessionId)) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    InterviewVideoEntity video = persistenceService.findRequired(videoId);
    if (!sessionId.equals(video.getSessionId())) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "视频分片不存在");
    }
    return video;
  }

  public record VideoContent(byte[] bytes, String mimeType) {
  }

  public record CombinedVideo(
      String mimeType,
      long fileSize,
      long durationMs,
      List<String> objectKeys) {
  }

  private String responseMimeType(String mimeType) {
    int parameterStart = mimeType.indexOf(';');
    return parameterStart < 0 ? mimeType : mimeType.substring(0, parameterStart);
  }

  private InterviewSessionEntity requireOwnedSession(String sessionId, Long userId) {
    return sessionRepository.findBySessionIdAndOwnerUserId(sessionId, userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
  }

  private void validateChunk(
      MultipartFile file,
      int chunkIndex,
      long durationMs,
      String expectedChecksum) {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "视频分片不能为空");
    }
    if (file.getSize() > properties.getMaxChunkSize()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "视频分片超过大小限制");
    }
    if (chunkIndex < 0 || durationMs <= 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "视频分片参数无效");
    }
    if (expectedChecksum == null || !expectedChecksum.matches("(?i)[0-9a-f]{64}")) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "checksum 必须是 SHA-256");
    }
    String mimeType = file.getContentType();
    if (mimeType == null || !properties.getAllowedMimeTypes().contains(mimeType.toLowerCase(Locale.ROOT))) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的视频格式");
    }
  }

  private String calculateChecksum(MultipartFile file) {
    try (InputStream inputStream = file.getInputStream()) {
      return fileHashService.calculateHash(inputStream);
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无法读取视频分片", e);
    }
  }

  private String generateObjectKey(String sessionId, int chunkIndex, String mimeType) {
    String extension = mimeType.startsWith("video/mp4") ? "mp4" : "webm";
    return "interview-videos/" + sessionId + "/" + UUID.randomUUID()
        + "-" + chunkIndex + "." + extension;
  }
}
