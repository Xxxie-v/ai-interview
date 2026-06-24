package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.recruitment.dto.CreateJobPositionRequest;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.JobStatus;
import interview.guide.modules.recruitment.repository.InterviewAssignmentRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位管理服务")
class JobPositionServiceTest {

  @Mock
  private JobPositionRepository jobRepository;
  @Mock
  private InterviewAssignmentRepository assignmentRepository;
  @Mock
  private JobQuestionBankService questionBankService;

  private JobPositionService service;

  @BeforeEach
  void setUp() {
    service = new JobPositionService(
        jobRepository,
        assignmentRepository,
        questionBankService,
        new ObjectMapper());
    org.mockito.Mockito.lenient().when(questionBankService.selectFixedQuestions(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
  }

  @Test
  @DisplayName("创建岗位时记录管理员并默认保存为草稿")
  void createsDraftJob() {
    when(questionBankService.selectFixedQuestions(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(
            InterviewQuestionDTO.create(0, "固定题", "JAVA", "Java", "并发", false, null)));
    when(jobRepository.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          JobPositionEntity job = invocation.getArgument(0);
          job.setId(10L);
          return job;
        });

    var result = service.create(new CreateJobPositionRequest(
        "Java 开发工程师",
        "负责企业平台开发",
        "熟悉 Java 与 Spring",
        "中级",
        null), 1L);

    assertThat(result.id()).isEqualTo(10L);
    assertThat(result.status()).isEqualTo("DRAFT");
    assertThat(result.createdBy()).isEqualTo(1L);
    assertThat(result.fixedQuestions()).extracting(InterviewQuestionDTO::question)
        .containsExactly("固定题");
  }

  @Test
  @DisplayName("已有面试任务的岗位不能直接删除")
  void rejectsDeletingAssignedJob() {
    JobPositionEntity job = JobPositionEntity.builder()
        .id(10L)
        .name("Java 开发工程师")
        .status(JobStatus.ACTIVE)
        .build();
    when(jobRepository.findById(10L)).thenReturn(java.util.Optional.of(job));
    when(assignmentRepository.existsByJobId(10L)).thenReturn(true);

    assertThatThrownBy(() -> service.delete(10L))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不能删除");
    verify(assignmentRepository).existsByJobId(10L);
  }

  @Test
  @DisplayName("候选人只能看到招聘中的岗位")
  void listsOnlyActiveJobs() {
    JobPositionEntity job = JobPositionEntity.builder()
        .id(10L)
        .name("Java 开发工程师")
        .description("负责企业平台开发")
        .requirements("熟悉 Java 与 Spring")
        .level("中级")
        .status(JobStatus.ACTIVE)
        .build();
    when(jobRepository.findByStatusOrderByCreatedAtDesc(JobStatus.ACTIVE))
        .thenReturn(List.of(job));

    var result = service.listActive();

    assertThat(result).hasSize(1);
    assertThat(result.getFirst().status()).isEqualTo("ACTIVE");
    verify(jobRepository).findByStatusOrderByCreatedAtDesc(JobStatus.ACTIVE);
  }
}
