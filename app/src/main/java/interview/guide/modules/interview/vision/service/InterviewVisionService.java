package interview.guide.modules.interview.vision.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.ObjectAccessResponse;
import interview.guide.infrastructure.file.ObjectAccessService;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.vision.model.FrameInput;
import interview.guide.modules.interview.vision.model.InterviewVisionEventDTO;
import interview.guide.modules.interview.vision.model.InterviewVisionEventEntity;
import interview.guide.modules.interview.vision.model.VisionAnalysisResult;
import interview.guide.modules.interview.vision.model.VisionEventType;
import interview.guide.modules.interview.vision.model.VisionMonitoringState;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewVisionService {

  private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

  private final InterviewSessionRepository sessionRepository;
  private final InterviewVisionAnalyzer analyzer;
  private final InterviewVisionProperties properties;
  private final VisionAnomalyTracker anomalyTracker;
  private final InterviewVisionEventPersistenceService persistenceService;
  private final FileStorageService fileStorageService;
  private final ObjectAccessService objectAccessService;
  private final ObjectMapper objectMapper;

  public VisionAnalysisResult analyze(
      String sessionId,
      Long ownerUserId,
      MultipartFile frame,
      Double brightness,
      boolean cameraActive,
      Long videoOffsetMs) {
    requireOwnedSession(sessionId, ownerUserId);
    if (!properties.isEnabled()) {
      return new VisionAnalysisResult(
          false,
          0,
          0,
          false,
          cameraActive,
          null,
          null,
          VisionMonitoringState.NORMAL,
          properties.getFrameInterval().toMillis(),
          List.of(),
          List.of());
    }
    byte[] frameBytes = readFrame(frame);
    validateFrame(frame, frameBytes, brightness, cameraActive);
    validateVideoOffset(videoOffsetMs);
    LocalDateTime capturedAt = LocalDateTime.now();
    FrameInput input = new FrameInput(
        sessionId,
        ownerUserId,
        frameBytes,
        frame == null ? null : frame.getContentType(),
        brightness,
        cameraActive,
        capturedAt);
    VisionAnalysisResult analysis = analyzer.analyze(input);
    VisionAnomalyTracker.Decision decision = anomalyTracker.track(
        sessionId,
        analysis.events(),
        capturedAt,
        videoOffsetMs);
    VisionAnalysisResult result = new VisionAnalysisResult(
        analysis.facePresent(),
        analysis.faceCount(),
        analysis.confidence(),
        analysis.lowLight(),
        analysis.cameraActive(),
        analysis.identitySimilarity(),
        analysis.samePerson(),
        decision.monitoringState(),
        decision.recommendedIntervalMs(),
        analysis.events(),
        decision.activeEvents());
    String metadata = toMetadataJson(result, brightness, videoOffsetMs);
    if (decision.episodeUpdate() != null) {
      persistenceService.upsertEpisode(
          sessionId,
          ownerUserId,
          decision.episodeUpdate(),
          metadata);
    }
    return result;
  }

  public List<InterviewVisionEventDTO> listForAdmin(String sessionId) {
    InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    return persistenceService.listBySessionId(sessionId).stream()
        .map(event -> InterviewVisionEventDTO.from(
            event,
            fallbackVideoOffsetMs(session.getStartedAt(), event.getOccurredAt())))
        .toList();
  }

  public void recordProctorEvent(
      String sessionId,
      Long ownerUserId,
      String clientEventId,
      VisionEventType eventType,
      MultipartFile evidence,
      String metadataJson,
      Long videoOffsetMs) {
    InterviewSessionEntity session = sessionRepository
        .findBySessionIdAndOwnerUserId(sessionId, ownerUserId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    if (!session.isOfficialInterview()) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "仅正式面试允许记录监考事件");
    }
    validateClientEventId(clientEventId);
    validateVideoOffset(videoOffsetMs);
    if (persistenceService.findByClientEventId(sessionId, clientEventId).isPresent()) {
      return;
    }
    if (!isProctorEvent(eventType)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的监考事件类型");
    }
    if (metadataJson != null && metadataJson.length() > 2_000) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "监考事件元数据过长");
    }

    String evidenceObjectKey = null;
    if (evidence != null && !evidence.isEmpty()) {
      byte[] bytes = readFrame(evidence);
      if (evidence.getSize() > properties.getMaxFrameSize()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "监考截图超过大小限制");
      }
      if (!ALLOWED_CONTENT_TYPES.contains(evidence.getContentType())
          || !hasValidImageSignature(bytes)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "监考截图格式无效");
      }
      evidenceObjectKey = fileStorageService.uploadInterviewEvidence(evidence);
    }
    if (eventType == VisionEventType.SCREEN_CAPTURED && evidenceObjectKey == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "屏幕采样事件必须包含截图证据");
    }

    persistenceService.record(
        sessionId,
        ownerUserId,
        clientEventId,
        eventType,
        LocalDateTime.now(),
        null,
        videoOffsetMs,
        evidenceObjectKey,
        metadataJson);
  }

  private Long fallbackVideoOffsetMs(LocalDateTime startedAt, LocalDateTime occurredAt) {
    if (startedAt == null || occurredAt == null) {
      return null;
    }
    return Math.max(0, Duration.between(startedAt, occurredAt).toMillis());
  }

  public ObjectAccessResponse createAdminEvidenceAccess(String sessionId, Long eventId) {
    InterviewVisionEventEntity event = requireAdminEvidence(sessionId, eventId);
    String mimeType = evidenceMimeType(event.getEvidenceObjectKey());
    return objectAccessService.createAccess(
        event.getEvidenceObjectKey(),
        mimeType,
        "/api/admin/interviews/" + sessionId + "/vision-events/" + eventId + "/content");
  }

  public EvidenceContent getAdminEvidenceContent(String sessionId, Long eventId) {
    InterviewVisionEventEntity event = requireAdminEvidence(sessionId, eventId);
    return new EvidenceContent(
        objectAccessService.download(event.getEvidenceObjectKey()),
        evidenceMimeType(event.getEvidenceObjectKey()));
  }

  private InterviewVisionEventEntity requireAdminEvidence(String sessionId, Long eventId) {
    if (!sessionRepository.existsBySessionId(sessionId)) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    InterviewVisionEventEntity event = persistenceService.findRequired(eventId);
    if (!sessionId.equals(event.getSessionId()) || event.getEvidenceObjectKey() == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "监考截图不存在");
    }
    return event;
  }

  private void validateClientEventId(String clientEventId) {
    try {
      UUID.fromString(clientEventId);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "clientEventId 必须是 UUID");
    }
  }

  private void validateVideoOffset(Long videoOffsetMs) {
    if (videoOffsetMs != null && videoOffsetMs < 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "videoOffsetMs 不能小于 0");
    }
  }

  private String evidenceMimeType(String objectKey) {
    return objectKey.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
  }

  public record EvidenceContent(byte[] bytes, String mimeType) {
  }

  private void requireOwnedSession(String sessionId, Long ownerUserId) {
    if (sessionRepository.findBySessionIdAndOwnerUserId(sessionId, ownerUserId).isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
  }

  private boolean isProctorEvent(VisionEventType eventType) {
    return eventType == VisionEventType.TAB_HIDDEN
        || eventType == VisionEventType.WINDOW_BLUR
        || eventType == VisionEventType.FULLSCREEN_EXIT
        || eventType == VisionEventType.SCREEN_SHARE_STOPPED
        || eventType == VisionEventType.SCREEN_CAPTURED;
  }

  private void validateFrame(
      MultipartFile frame,
      byte[] frameBytes,
      Double brightness,
      boolean cameraActive) {
    if (brightness != null && (brightness < 0 || brightness > 255)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "画面亮度必须在 0 到 255 之间");
    }
    if (!cameraActive) return;
    if (frame == null || frame.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "摄像头正常时必须提供抽帧图片");
    }
    if (frame.getSize() > properties.getMaxFrameSize()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "抽帧图片超过大小限制");
    }
    if (!ALLOWED_CONTENT_TYPES.contains(frame.getContentType())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的抽帧图片格式");
    }
    if (!hasValidImageSignature(frameBytes)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "抽帧图片内容无效");
    }
  }

  private byte[] readFrame(MultipartFile frame) {
    if (frame == null) return new byte[0];
    try {
      return frame.getBytes();
    } catch (IOException e) {
      throw new BusinessException(ErrorCode.VISION_ANALYSIS_FAILED, "无法读取抽帧图片", e);
    }
  }

  private boolean hasValidImageSignature(byte[] bytes) {
    boolean jpeg = bytes.length >= 3
        && (bytes[0] & 0xff) == 0xff
        && (bytes[1] & 0xff) == 0xd8
        && (bytes[2] & 0xff) == 0xff;
    boolean png = bytes.length >= 8
        && (bytes[0] & 0xff) == 0x89
        && bytes[1] == 0x50
        && bytes[2] == 0x4e
        && bytes[3] == 0x47;
    return jpeg || png;
  }

  private String toMetadataJson(
      VisionAnalysisResult result,
      Double brightness,
      Long videoOffsetMs) {
    try {
      return objectMapper.writeValueAsString(Map.of(
          "faceCount", result.faceCount(),
          "confidence", result.confidence(),
          "lowLight", result.lowLight(),
          "cameraActive", result.cameraActive(),
          "identitySimilarity", result.identitySimilarity() == null
              ? -1
              : result.identitySimilarity(),
          "samePerson", result.samePerson() == null ? "UNKNOWN" : result.samePerson(),
          "monitoringState", result.monitoringState(),
          "candidateEvents", result.candidateEvents(),
          "brightness", brightness == null ? -1 : brightness,
          "videoOffsetMs", videoOffsetMs == null ? -1 : videoOffsetMs));
    } catch (JsonProcessingException e) {
      log.error("Vision event metadata serialization failed", e);
      throw new BusinessException(ErrorCode.VISION_ANALYSIS_FAILED, "画面事件保存失败", e);
    }
  }
}
