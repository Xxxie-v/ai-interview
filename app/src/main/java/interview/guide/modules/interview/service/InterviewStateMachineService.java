package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewFlowStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewStateMachineService {

  private static final Map<InterviewFlowStatus, Set<InterviewFlowStatus>> ALLOWED_TRANSITIONS =
      buildTransitions();

  private final InterviewSessionRepository sessionRepository;

  @Transactional
  public InterviewFlowStatus transition(String sessionId, InterviewFlowStatus target) {
    InterviewSessionEntity session = sessionRepository.findBySessionId(sessionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    InterviewFlowStatus current = resolveCurrentStatus(session);
    if (current == target) {
      return current;
    }
    if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "面试状态不能从 " + current + " 切换到 " + target);
    }

    LocalDateTime now = LocalDateTime.now();
    session.setFlowStatus(target);
    if (target == InterviewFlowStatus.QUESTIONING && session.getStartedAt() == null) {
      session.setStartedAt(now);
    }
    if (target == InterviewFlowStatus.FINISHED || target == InterviewFlowStatus.TERMINATED) {
      session.setEndedAt(now);
    }
    syncLegacyStatus(session, target);
    sessionRepository.save(session);
    log.info("Interview state changed: sessionId={}, from={}, to={}", sessionId, current, target);
    return target;
  }

  @Transactional(readOnly = true)
  public InterviewFlowStatus getStatus(String sessionId) {
    return sessionRepository.findBySessionId(sessionId)
        .map(this::resolveCurrentStatus)
        .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
  }

  private InterviewFlowStatus resolveCurrentStatus(InterviewSessionEntity session) {
    if (session.getFlowStatus() != null) {
      return session.getFlowStatus();
    }
    return switch (session.getStatus()) {
      case CREATED -> InterviewFlowStatus.READY;
      case IN_PROGRESS -> InterviewFlowStatus.QUESTIONING;
      case COMPLETED, EVALUATED -> InterviewFlowStatus.FINISHED;
    };
  }

  private void syncLegacyStatus(
      InterviewSessionEntity session,
      InterviewFlowStatus flowStatus) {
    InterviewSessionEntity.SessionStatus legacyStatus = switch (flowStatus) {
      case INIT, DEVICE_CHECK, READY -> InterviewSessionEntity.SessionStatus.CREATED;
      case QUESTIONING, ANSWERING, EVALUATING, PAUSED ->
          InterviewSessionEntity.SessionStatus.IN_PROGRESS;
      case FINISHED, TERMINATED -> InterviewSessionEntity.SessionStatus.COMPLETED;
    };
    session.setStatus(legacyStatus);
  }

  private static Map<InterviewFlowStatus, Set<InterviewFlowStatus>> buildTransitions() {
    Map<InterviewFlowStatus, Set<InterviewFlowStatus>> transitions =
        new EnumMap<>(InterviewFlowStatus.class);
    transitions.put(InterviewFlowStatus.INIT, EnumSet.of(
        InterviewFlowStatus.DEVICE_CHECK,
        InterviewFlowStatus.READY,
        InterviewFlowStatus.TERMINATED));
    transitions.put(InterviewFlowStatus.DEVICE_CHECK, EnumSet.of(
        InterviewFlowStatus.READY,
        InterviewFlowStatus.TERMINATED));
    transitions.put(InterviewFlowStatus.READY, EnumSet.of(
        InterviewFlowStatus.QUESTIONING,
        InterviewFlowStatus.TERMINATED));
    transitions.put(InterviewFlowStatus.QUESTIONING, EnumSet.of(
        InterviewFlowStatus.ANSWERING,
        InterviewFlowStatus.PAUSED,
        InterviewFlowStatus.FINISHED,
        InterviewFlowStatus.TERMINATED));
    transitions.put(InterviewFlowStatus.ANSWERING, EnumSet.of(
        InterviewFlowStatus.EVALUATING,
        InterviewFlowStatus.PAUSED,
        InterviewFlowStatus.FINISHED,
        InterviewFlowStatus.TERMINATED));
    transitions.put(InterviewFlowStatus.EVALUATING, EnumSet.of(
        InterviewFlowStatus.QUESTIONING,
        InterviewFlowStatus.FINISHED,
        InterviewFlowStatus.TERMINATED));
    transitions.put(InterviewFlowStatus.PAUSED, EnumSet.of(
        InterviewFlowStatus.QUESTIONING,
        InterviewFlowStatus.FINISHED,
        InterviewFlowStatus.TERMINATED));
    transitions.put(InterviewFlowStatus.FINISHED, EnumSet.noneOf(InterviewFlowStatus.class));
    transitions.put(InterviewFlowStatus.TERMINATED, EnumSet.noneOf(InterviewFlowStatus.class));
    return Map.copyOf(transitions);
  }
}
