package interview.guide.modules.interview;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.DeviceCheckRequest;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewFlowStatusDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.SessionListItemDTO;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.SubmitAnswerBody;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.report.service.EnterpriseInterviewReportService;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、问答交互与报告生成")
public class InterviewController {

  private final InterviewSessionService sessionService;
  private final InterviewHistoryService historyService;
  private final InterviewPersistenceService persistenceService;
  private final EnterpriseInterviewReportService enterpriseReportService;
  private final JobPositionRepository jobPositionRepository;

  @GetMapping("/api/interview/sessions")
  @PreAuthorize("hasRole('INTERVIEWEE')")
  public Result<List<SessionListItemDTO>> listSessions(
      @AuthenticationPrincipal AuthPrincipal principal) {
    List<InterviewSessionEntity> sessions = persistenceService.findAll(principal.id());
    List<Long> jobIds = sessions.stream()
        .map(InterviewSessionEntity::getJobId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    Map<Long, JobPositionEntity> jobs = jobPositionRepository.findAllById(jobIds).stream()
        .collect(Collectors.toMap(JobPositionEntity::getId, Function.identity()));
    List<SessionListItemDTO> items = sessions.stream()
        .map(session -> SessionListItemDTO.from(
            session,
            enterpriseReportService.isCandidateReportVisible(session, principal.id()),
            session.getJobId() == null || jobs.get(session.getJobId()) == null
                ? "模拟面试"
                : jobs.get(session.getJobId()).getName()))
        .toList();
    return Result.success(items);
  }

  @PostMapping("/api/interview/sessions")
  @PreAuthorize("hasRole('INTERVIEWEE')")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
  public Result<InterviewSessionDTO> createSession(
      @RequestBody CreateInterviewRequest request,
      @AuthenticationPrincipal AuthPrincipal principal) {
    log.info("Create interview session: userId={}, questionCount={}",
        principal.id(), request.questionCount());
    InterviewSessionDTO session = sessionService.createSession(request, principal.id());
    return Result.success(session);
  }

  @GetMapping("/api/interview/sessions/{sessionId}")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<InterviewSessionDTO> getSession(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    assertSessionOwner(sessionId, principal.id());
    return Result.success(sessionService.getSession(sessionId));
  }

  @PostMapping("/api/interview/sessions/{sessionId}/questions/retry")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 3)
  public Result<InterviewSessionDTO> retryQuestionPreparation(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(sessionService.retryQuestionPreparation(sessionId, principal.id()));
  }

  @GetMapping("/api/interview/sessions/{sessionId}/question")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<Map<String, Object>> getCurrentQuestion(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    assertSessionOwner(sessionId, principal.id());
    return Result.success(sessionService.getCurrentQuestionResponse(sessionId));
  }

  @PostMapping("/api/interview/sessions/{sessionId}/answers")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 2)
  public Result<SubmitAnswerResponse> submitAnswer(
      @PathVariable String sessionId,
      @Valid @RequestBody SubmitAnswerBody body,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @AuthenticationPrincipal AuthPrincipal principal) {
    assertSessionOwner(sessionId, principal.id());
    Integer questionIndex = body.questionIndex();
    String answer = body.answer();
    log.info("Submit answer: userId={}, sessionId={}, questionIndex={}",
        principal.id(), sessionId, questionIndex);
    SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
    return Result.success(sessionService.submitAnswer(request, idempotencyKey));
  }

  @PostMapping("/api/interviews/{sessionId}/device-check")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<InterviewFlowStatusDTO> confirmDeviceReady(
      @PathVariable String sessionId,
      @Valid @RequestBody DeviceCheckRequest request) {
    return Result.success(new InterviewFlowStatusDTO(
        sessionId,
        sessionService.confirmDeviceReady(sessionId)));
  }

  @GetMapping("/api/interview/sessions/{sessionId}/report")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<InterviewReportDTO> getReport(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    assertSessionOwner(sessionId, principal.id());
    enterpriseReportService.assertCandidateCanView(sessionId, principal.id());
    log.info("Generate interview report: userId={}, sessionId={}", principal.id(), sessionId);
    return Result.success(sessionService.generateReport(sessionId));
  }

  @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
  public Result<InterviewSessionDTO> findUnfinishedSession(
      @PathVariable Long resumeId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    return Result.success(sessionService.findUnfinishedSessionOrThrow(resumeId, principal.id()));
  }

  @PutMapping("/api/interview/sessions/{sessionId}/answers")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<Void> saveAnswer(
      @PathVariable String sessionId,
      @RequestBody Map<String, Object> body,
      @AuthenticationPrincipal AuthPrincipal principal) {
    assertSessionOwner(sessionId, principal.id());
    Integer questionIndex = (Integer) body.get("questionIndex");
    String answer = (String) body.get("answer");
    log.info("Save answer: userId={}, sessionId={}, questionIndex={}",
        principal.id(), sessionId, questionIndex);
    SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
    sessionService.saveAnswer(request);
    return Result.success(null);
  }

  @PostMapping("/api/interview/sessions/{sessionId}/complete")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<Void> completeInterview(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    assertSessionOwner(sessionId, principal.id());
    log.info("Complete interview: userId={}, sessionId={}", principal.id(), sessionId);
    sessionService.completeInterview(sessionId);
    return Result.success(null);
  }

  @GetMapping("/api/interview/sessions/{sessionId}/status")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<InterviewFlowStatusDTO> getFlowStatus(@PathVariable String sessionId) {
    return Result.success(new InterviewFlowStatusDTO(
        sessionId,
        sessionService.getFlowStatus(sessionId)));
  }

  @PostMapping("/api/interview/sessions/{sessionId}/pause")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<Void> pauseInterview(@PathVariable String sessionId) {
    sessionService.pauseInterview(sessionId);
    return Result.success();
  }

  @PostMapping("/api/interview/sessions/{sessionId}/resume")
  @PreAuthorize("hasRole('INTERVIEWEE') and @interviewPermission.isOwner(#sessionId, authentication)")
  public Result<Void> resumeInterview(@PathVariable String sessionId) {
    sessionService.resumeInterview(sessionId);
    return Result.success();
  }

  @GetMapping("/api/interview/sessions/{sessionId}/details")
  public Result<InterviewDetailDTO> getInterviewDetail(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    assertCanViewSession(sessionId, principal);
    InterviewDetailDTO detail = historyService.getInterviewDetail(sessionId);
    return Result.success(detail);
  }

  @GetMapping("/api/interview/sessions/{sessionId}/export")
  public ResponseEntity<byte[]> exportInterviewPdf(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    try {
      assertCanViewSession(sessionId, principal);
      byte[] pdfBytes = historyService.exportInterviewPdf(sessionId);
      String filename = URLEncoder.encode("模拟面试报告_" + sessionId + ".pdf",
          StandardCharsets.UTF_8);
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
          .contentType(MediaType.APPLICATION_PDF)
          .body(pdfBytes);
    } catch (Exception e) {
      log.error("Export interview PDF failed: sessionId={}", sessionId, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @DeleteMapping("/api/interview/sessions/{sessionId}")
  public Result<Void> deleteInterview(
      @PathVariable String sessionId,
      @AuthenticationPrincipal AuthPrincipal principal) {
    log.info("Delete interview session: userId={}, sessionId={}", principal.id(), sessionId);
    persistenceService.deleteSessionBySessionId(sessionId, principal.id());
    return Result.success(null);
  }

  private void assertSessionOwner(String sessionId, Long ownerUserId) {
    if (persistenceService.findBySessionIdAndOwnerUserId(sessionId, ownerUserId).isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
  }

  private void assertCanViewSession(String sessionId, AuthPrincipal principal) {
    Optional<InterviewSessionEntity> sessionOpt = persistenceService.findBySessionId(sessionId);
    if (sessionOpt.isEmpty()) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }

    InterviewSessionEntity session = sessionOpt.get();
    if (principal.id().equals(session.getOwnerUserId())) {
      if (session.isOfficialInterview()) {
        enterpriseReportService.assertCandidateCanView(sessionId, principal.id());
      }
      return;
    }
    if (session.isOfficialInterview() && hasAnyRole(principal, "ROLE_HR", "ROLE_ADMIN")) {
      return;
    }
    throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
  }

  private boolean hasAnyRole(AuthPrincipal principal, String... roles) {
    return principal.getAuthorities().stream()
        .map(authority -> authority.getAuthority())
        .anyMatch(authority -> java.util.Arrays.asList(roles).contains(authority));
  }
}
