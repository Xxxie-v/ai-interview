package interview.guide.modules.recruitment.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.recruitment.dto.CreateJobPositionRequest;
import interview.guide.modules.recruitment.dto.JobPositionDTO;
import interview.guide.modules.recruitment.dto.PagedResponseDTO;
import interview.guide.modules.recruitment.dto.UpdateJobPositionRequest;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.JobStatus;
import interview.guide.modules.recruitment.repository.InterviewAssignmentRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import interview.guide.modules.interview.model.InterviewQuestionDTO;

@Service
@RequiredArgsConstructor
public class JobPositionService {

  private static final int MAX_PAGE_SIZE = 100;

  private final JobPositionRepository jobRepository;
  private final InterviewAssignmentRepository assignmentRepository;
  private final JobQuestionBankService questionBankService;
  private final ObjectMapper objectMapper;

  @Transactional
  public JobPositionDTO create(CreateJobPositionRequest request, Long administratorId) {
    JobPositionEntity job = JobPositionEntity.builder()
        .name(request.name().trim())
        .description(request.description().trim())
        .requirements(request.requirements().trim())
        .level(request.level().trim())
        .status(request.status() == null ? JobStatus.DRAFT : request.status())
        .createdBy(administratorId)
        .build();
    job.setFixedQuestionsJson(writeQuestions(questionBankService.selectFixedQuestions(
        job.getName(), job.getDescription(), job.getRequirements())));
    return toDTO(jobRepository.save(job));
  }

  @Transactional
  public JobPositionDTO update(Long jobId, UpdateJobPositionRequest request) {
    JobPositionEntity job = findEntity(jobId);
    boolean questionScopeChanged = !job.getName().equals(request.name().trim())
        || !job.getDescription().equals(request.description().trim())
        || !job.getRequirements().equals(request.requirements().trim());
    job.setName(request.name().trim());
    job.setDescription(request.description().trim());
    job.setRequirements(request.requirements().trim());
    job.setLevel(request.level().trim());
    job.setStatus(request.status());
    if (questionScopeChanged || job.getFixedQuestionsJson() == null) {
      job.setFixedQuestionsJson(writeQuestions(questionBankService.selectFixedQuestions(
          job.getName(), job.getDescription(), job.getRequirements())));
    }
    return toDTO(jobRepository.save(job));
  }

  @Transactional
  public void delete(Long jobId) {
    JobPositionEntity job = findEntity(jobId);
    if (assignmentRepository.existsByJobId(jobId)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "该岗位已有面试任务，不能删除；可以将岗位状态改为 CLOSED");
    }
    jobRepository.delete(job);
  }

  @Transactional(readOnly = true)
  public JobPositionDTO get(Long jobId) {
    return toDTO(findEntity(jobId));
  }

  @Transactional(readOnly = true)
  public PagedResponseDTO<JobPositionDTO> list(int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    Page<JobPositionEntity> jobs = jobRepository.findAll(PageRequest.of(
        safePage,
        safeSize,
        Sort.by(Sort.Direction.DESC, "createdAt")));
    return new PagedResponseDTO<>(
        jobs.getContent().stream().map(this::toDTO).toList(),
        jobs.getTotalElements(),
        jobs.getTotalPages(),
        safePage,
        safeSize);
  }

  @Transactional(readOnly = true)
  public List<JobPositionDTO> listActive() {
    return jobRepository.findByStatusOrderByCreatedAtDesc(JobStatus.ACTIVE).stream()
        .map(this::toDTO)
        .toList();
  }

  JobPositionEntity findEntity(Long jobId) {
    return jobRepository.findById(jobId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位不存在"));
  }

  private JobPositionDTO toDTO(JobPositionEntity job) {
    return new JobPositionDTO(
        job.getId(),
        job.getName(),
        job.getDescription(),
        job.getRequirements(),
        job.getLevel(),
        readQuestions(job.getFixedQuestionsJson()),
        job.getStatus().name(),
        job.getCreatedBy(),
        job.getCreatedAt(),
        job.getUpdatedAt());
  }

  private String writeQuestions(List<InterviewQuestionDTO> questions) {
    try {
      return objectMapper.writeValueAsString(questions);
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "岗位固定题保存失败", e);
    }
  }

  private List<InterviewQuestionDTO> readQuestions(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JacksonException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "岗位固定题读取失败", e);
    }
  }
}
