package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.model.UserStatus;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.service.AuthBootstrapService;
import interview.guide.modules.recruitment.dto.CreateInterviewAssignmentRequest;
import interview.guide.modules.recruitment.model.InterviewAssignmentEntity;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.JobStatus;
import interview.guide.modules.recruitment.repository.InterviewAssignmentRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试任务服务")
class InterviewAssignmentServiceTest {

  @Mock
  private InterviewAssignmentRepository assignmentRepository;
  @Mock
  private JobPositionRepository jobRepository;
  @Mock
  private UserRepository userRepository;
  @Mock
  private ResumeRepository resumeRepository;

  private InterviewAssignmentService service;

  @BeforeEach
  void setUp() {
    service = new InterviewAssignmentService(
        assignmentRepository,
        jobRepository,
        userRepository,
        resumeRepository);
  }

  @Test
  @DisplayName("只能把候选人自己的简历绑定到任务")
  void rejectsResumeOwnedByAnotherCandidate() {
    UserEntity candidate = candidate(20L);
    when(userRepository.findById(20L)).thenReturn(Optional.of(candidate));
    when(jobRepository.findById(30L)).thenReturn(Optional.of(activeJob()));
    when(resumeRepository.findByIdAndOwnerUserId(40L, 20L)).thenReturn(Optional.empty());

    CreateInterviewAssignmentRequest request = new CreateInterviewAssignmentRequest(
        20L,
        30L,
        40L,
        LocalDateTime.now(),
        LocalDateTime.now().plusDays(2),
        false);

    assertThatThrownBy(() -> service.create(request, 1L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不属于");
  }

  @Test
  @DisplayName("候选人修改任务ID不能读取其他人的任务")
  void rejectsAssignmentIdor() {
    when(assignmentRepository.findByIdAndCandidateId(99L, 20L))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getForCandidate(99L, 20L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("有效候选人可以获得启用岗位的面试任务")
  void createsAssignment() {
    UserEntity candidate = candidate(20L);
    JobPositionEntity job = activeJob();
    ResumeEntity resume = new ResumeEntity();
    resume.setId(40L);
    resume.setOwnerUserId(20L);
    resume.setOriginalFilename("resume.pdf");
    when(userRepository.findById(20L)).thenReturn(Optional.of(candidate));
    when(jobRepository.findById(30L)).thenReturn(Optional.of(job));
    when(resumeRepository.findByIdAndOwnerUserId(40L, 20L)).thenReturn(Optional.of(resume));
    when(assignmentRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          InterviewAssignmentEntity assignment = invocation.getArgument(0);
          assignment.setId(50L);
          assignment.setCreatedAt(LocalDateTime.now());
          return assignment;
        });

    var result = service.create(new CreateInterviewAssignmentRequest(
        20L,
        30L,
        40L,
        LocalDateTime.now(),
        LocalDateTime.now().plusDays(2),
        true), 1L);

    assertThat(result.id()).isEqualTo(50L);
    assertThat(result.candidateId()).isEqualTo(20L);
    assertThat(result.jobName()).isEqualTo("Java 开发工程师");
    assertThat(result.resumeFilename()).isEqualTo("resume.pdf");
    assertThat(result.reportVisibleToCandidate()).isTrue();
  }

  private UserEntity candidate(Long id) {
    RoleEntity role = RoleEntity.builder()
        .code(AuthBootstrapService.ROLE_INTERVIEWEE)
        .name("面试者")
        .build();
    return UserEntity.builder()
        .id(id)
        .username("candidate")
        .nickname("候选人")
        .enabled(true)
        .status(UserStatus.ACTIVE)
        .roles(new HashSet<>(Set.of(role)))
        .build();
  }

  private JobPositionEntity activeJob() {
    return JobPositionEntity.builder()
        .id(30L)
        .name("Java 开发工程师")
        .description("负责企业平台开发")
        .requirements("熟悉 Java 与 Spring")
        .level("中级")
        .status(JobStatus.ACTIVE)
        .createdBy(1L)
        .build();
  }
}
