package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.model.InterviewFlowStatus;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("企业面试状态机")
class InterviewStateMachineServiceTest {

  @Mock
  private InterviewSessionRepository sessionRepository;

  private InterviewStateMachineService service;

  @BeforeEach
  void setUp() {
    service = new InterviewStateMachineService(sessionRepository);
  }

  @Test
  @DisplayName("就绪状态可以进入提问并记录开始时间")
  void readyCanMoveToQuestioning() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-1");
    session.setFlowStatus(InterviewFlowStatus.READY);
    when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));

    var status = service.transition("session-1", InterviewFlowStatus.QUESTIONING);

    assertThat(status).isEqualTo(InterviewFlowStatus.QUESTIONING);
    assertThat(session.getStartedAt()).isNotNull();
    assertThat(session.getStatus()).isEqualTo(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
    verify(sessionRepository).save(session);
  }

  @Test
  @DisplayName("完成后的面试不能重新进入提问")
  void finishedCannotMoveBackToQuestioning() {
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setSessionId("session-1");
    session.setFlowStatus(InterviewFlowStatus.FINISHED);
    when(sessionRepository.findBySessionId("session-1")).thenReturn(Optional.of(session));

    assertThatThrownBy(() -> service.transition(
        "session-1",
        InterviewFlowStatus.QUESTIONING))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能从 FINISHED");
  }
}
