package interview.guide.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.interview.model.InterviewReviewStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class HrInterviewResultServiceTest {

  private InterviewSessionRepository sessionRepository;
  private UserRepository userRepository;
  private HrInterviewResultService service;

  @BeforeEach
  void setUp() {
    sessionRepository = mock(InterviewSessionRepository.class);
    userRepository = mock(UserRepository.class);
    service = new HrInterviewResultService(
        sessionRepository,
        userRepository,
        mock(FileStorageService.class),
        mock(JobPositionRepository.class));
  }

  @Test
  @DisplayName("已完成面试可以进入人工审核")
  void shouldReviewCompletedInterview() {
    InterviewSessionEntity session = completedSession();
    when(sessionRepository.findBySessionIdWithResume("session-1"))
        .thenReturn(Optional.of(session));
    when(userRepository.findById(7L)).thenReturn(Optional.of(new UserEntity()));

    var result = service.updateReviewStatus(
        "session-1", InterviewReviewStatus.PASSED);

    assertThat(result.status()).isEqualTo(InterviewReviewStatus.PASSED);
    assertThat(session.getReviewStatus())
        .isEqualTo(InterviewReviewStatus.PASSED);
    verify(sessionRepository).save(session);
  }

  @Test
  @DisplayName("未完成面试不能直接标记为通过")
  void shouldRejectInvalidReviewTransition() {
    InterviewSessionEntity session = completedSession();
    session.setReviewStatus(InterviewReviewStatus.INCOMPLETE);
    session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    when(sessionRepository.findBySessionIdWithResume("session-1"))
        .thenReturn(Optional.of(session));

    assertThatThrownBy(() -> service.updateReviewStatus(
        "session-1", InterviewReviewStatus.PASSED))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("正式面试结果按页返回并限制最大页容量")
  void shouldPageOfficialInterviewResults() {
    when(sessionRepository.findOfficialSessionsWithResume(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));
    when(userRepository.findAllById(any())).thenReturn(List.of());

    var result = service.listOfficialResults(-1, 500);

    assertThat(result.items()).isEmpty();
    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(100);
    verify(sessionRepository).findOfficialSessionsWithResume(any(Pageable.class));
  }

  private InterviewSessionEntity completedSession() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-1");
    session.setOwnerUserId(7L);
    session.setStatus(InterviewSessionEntity.SessionStatus.COMPLETED);
    session.setReviewStatus(InterviewReviewStatus.UNDER_MANUAL_REVIEW);
    return session;
  }
}
