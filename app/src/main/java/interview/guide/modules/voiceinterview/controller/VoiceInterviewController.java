package interview.guide.modules.voiceinterview.controller;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.result.Result;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationStatusDTO;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.auth.security.AuthPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;
import java.util.Map;

/**
 * Voice Interview Controller
 * 语音面试控制器
 * <p>
 * REST API endpoints for voice interview session management:
 * - Session lifecycle (create, retrieve, end)
 * - Message history retrieval
 * - Async evaluation trigger and status polling
 * </p>
 */
@RestController
@RequestMapping("/api/voice-interview")
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewController {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    /**
     * Create a new voice interview session
     */
    @PostMapping("/sessions")
    @PreAuthorize("hasRole('INTERVIEWEE')")
    public Result<SessionResponseDTO> createSession(
        @Valid @RequestBody CreateSessionRequest request,
        @AuthenticationPrincipal AuthPrincipal principal) {
        throw new BusinessException(
            ErrorCode.FORBIDDEN,
            "模拟面试功能已下线，请从岗位列表参加正式面试");
    }

    /**
     * Get session details by ID
     */
    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("@interviewPermission.canViewVoice(#sessionId, authentication)")
    public Result<SessionResponseDTO> getSession(@PathVariable Long sessionId) {
        log.info("Getting session details for: {}", sessionId);
        SessionResponseDTO session = voiceInterviewService.getSessionDTO(sessionId);
        if (session == null) {
            return Result.error("Session not found: " + sessionId);
        }
        return Result.success(session);
    }

    /**
     * End interview session
     * <p>
     * This also triggers async evaluation via Redis Stream.
     * </p>
     */
    @PostMapping("/sessions/{sessionId}/end")
    @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isVoiceOwner(#sessionId, authentication)")
    public Result<Void> endSession(@PathVariable Long sessionId) {
        log.info("Ending session: {}", sessionId);
        voiceInterviewService.endSession(sessionId.toString());
        return Result.success();
    }

    /**
     * Pause interview session
     */
    @PutMapping("/sessions/{sessionId}/pause")
    @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isVoiceOwner(#sessionId, authentication)")
    public Result<Void> pauseSession(
        @PathVariable Long sessionId,
        @RequestBody Map<String, String> request
    ) {
        log.info("Pausing session: {}", sessionId);
        String reason = request.getOrDefault("reason", "user_initiated");
        voiceInterviewService.pauseSession(sessionId.toString(), reason);
        return Result.success();
    }

    /**
     * Resume interview session
     */
    @PutMapping("/sessions/{sessionId}/resume")
    @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isVoiceOwner(#sessionId, authentication)")
    public Result<SessionResponseDTO> resumeSession(@PathVariable Long sessionId) {
        log.info("Resuming session: {}", sessionId);
        SessionResponseDTO session = voiceInterviewService.resumeSession(sessionId.toString());
        return Result.success(session);
    }

    /**
     * Get all sessions for user
     */
    @GetMapping("/sessions")
    @PreAuthorize("hasRole('INTERVIEWEE')")
    public Result<List<SessionMetaDTO>> getAllSessions(
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) String status,
        @AuthenticationPrincipal AuthPrincipal principal
    ) {
        log.info("Getting voice sessions: userId={}, status={}", principal.id(), status);
        List<SessionMetaDTO> sessions = voiceInterviewService.getAllSessions(
            String.valueOf(principal.id()), status);
        return Result.success(sessions);
    }

    /**
     * 删除语音面试会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isVoiceOwner(#sessionId, authentication)")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        log.info("Deleting voice interview session: {}", sessionId);
        voiceInterviewService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * Get conversation history for a session
     */
    @GetMapping("/sessions/{sessionId}/messages")
    @PreAuthorize("@interviewPermission.canViewVoice(#sessionId, authentication)")
    public Result<List<VoiceInterviewMessageDTO>> getMessages(@PathVariable Long sessionId) {
        log.info("Getting messages for session: {}", sessionId);
        List<VoiceInterviewMessageDTO> messages =
                voiceInterviewService.getConversationHistoryDTO(sessionId.toString());
        return Result.success(messages);
    }

    /**
     * Get evaluation status and result for a session
     * <p>
     * Returns the current evaluation status (PENDING/PROCESSING/COMPLETED/FAILED)
     * along with the evaluation result when COMPLETED.
     * Frontend polls this endpoint until evaluation is complete.
     * </p>
     */
    @GetMapping("/sessions/{sessionId}/evaluation")
    @PreAuthorize("@interviewPermission.canViewVoice(#sessionId, authentication)")
    public Result<VoiceEvaluationStatusDTO> getEvaluation(@PathVariable Long sessionId) {
        log.info("Getting evaluation status for session: {}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        AsyncTaskStatus status = session.getEvaluateStatus();
        VoiceEvaluationStatusDTO.VoiceEvaluationStatusDTOBuilder builder = VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(status != null ? status.name() : null)
                .evaluateError(session.getEvaluateError());

        if (status == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            builder.evaluation(evaluation);
        }

        return Result.success(builder.build());
    }

    /**
     * Trigger async evaluation for a session
     * <p>
     * Enqueues evaluation task to Redis Stream and returns immediately.
     * Frontend should then poll GET /evaluation to track progress.
     * If evaluation is already in progress or completed, returns current status.
     * </p>
     */
    @PostMapping("/sessions/{sessionId}/evaluation")
    @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isVoiceOwner(#sessionId, authentication)")
    public Result<VoiceEvaluationStatusDTO> generateEvaluation(@PathVariable Long sessionId) {
        log.info("Triggering async evaluation for session: {}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        // If already completed, return cached result
        if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(AsyncTaskStatus.COMPLETED.name())
                    .evaluation(evaluation)
                    .build());
        }

        // If already in progress, return current status
        if (session.getEvaluateStatus() == AsyncTaskStatus.PENDING
                || session.getEvaluateStatus() == AsyncTaskStatus.PROCESSING) {
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(session.getEvaluateStatus().name())
                    .build());
        }

        // Trigger new async evaluation via service
        voiceInterviewService.triggerEvaluation(sessionId);

        return Result.success(VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(AsyncTaskStatus.PENDING.name())
                .build());
    }
}
