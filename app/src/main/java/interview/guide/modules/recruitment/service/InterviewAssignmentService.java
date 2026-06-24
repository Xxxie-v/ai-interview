package interview.guide.modules.recruitment.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.RoleEntity;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.service.AuthBootstrapService;
import interview.guide.modules.recruitment.dto.CreateInterviewAssignmentRequest;
import interview.guide.modules.recruitment.dto.CandidateResumeDTO;
import interview.guide.modules.recruitment.dto.InterviewAssignmentDTO;
import interview.guide.modules.recruitment.dto.PagedResponseDTO;
import interview.guide.modules.recruitment.model.AssignmentStatus;
import interview.guide.modules.recruitment.model.InterviewAssignmentEntity;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.JobStatus;
import interview.guide.modules.recruitment.repository.InterviewAssignmentRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InterviewAssignmentService {

  private static final int MAX_PAGE_SIZE = 100;

  private final InterviewAssignmentRepository assignmentRepository;
  private final JobPositionRepository jobRepository;
  private final UserRepository userRepository;
  private final ResumeRepository resumeRepository;

  @Transactional
  public InterviewAssignmentDTO create(
      CreateInterviewAssignmentRequest request,
      Long administratorId) {
    UserEntity candidate = findEligibleCandidate(request.candidateId());
    JobPositionEntity job = findActiveJob(request.jobId());
    ResumeEntity resume = findCandidateResume(request.resumeId(), candidate.getId());
    LocalDateTime availableFrom = request.availableFrom() == null
        ? LocalDateTime.now()
        : request.availableFrom();
    if (!request.deadline().isAfter(availableFrom)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "截止时间必须晚于任务开放时间");
    }

    if (assignmentRepository.existsByCandidateIdAndJobId(candidate.getId(), job.getId())) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "每位候选人每个岗位只能分配一次面试机会");
    }

    InterviewAssignmentEntity assignment = assignmentRepository.save(
        InterviewAssignmentEntity.builder()
            .candidateId(candidate.getId())
            .jobId(job.getId())
            .resumeId(resume == null ? null : resume.getId())
            .status(AssignmentStatus.PENDING)
            .availableFrom(availableFrom)
            .deadline(request.deadline())
            .reportVisibleToCandidate(Boolean.TRUE.equals(request.reportVisibleToCandidate()))
            .createdBy(administratorId)
            .build());
    return toDTO(assignment, candidate, job, resume);
  }

  @Transactional(readOnly = true)
  public PagedResponseDTO<InterviewAssignmentDTO> listForAdmin(int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    Page<InterviewAssignmentEntity> assignments = assignmentRepository.findAll(PageRequest.of(
        safePage,
        safeSize,
        Sort.by(Sort.Direction.DESC, "createdAt")));
    return new PagedResponseDTO<>(
        toDTOs(assignments.getContent()),
        assignments.getTotalElements(),
        assignments.getTotalPages(),
        safePage,
        safeSize);
  }

  @Transactional(readOnly = true)
  public InterviewAssignmentDTO getForAdmin(Long assignmentId) {
    return toDTO(loadAssignment(assignmentId));
  }

  @Transactional(readOnly = true)
  public List<InterviewAssignmentDTO> listForCandidate(Long candidateId) {
    return toDTOs(assignmentRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId));
  }

  @Transactional(readOnly = true)
  public InterviewAssignmentDTO getForCandidate(Long assignmentId, Long candidateId) {
    InterviewAssignmentEntity assignment = assignmentRepository
        .findByIdAndCandidateId(assignmentId, candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试任务不存在"));
    return toDTO(assignment);
  }

  @Transactional(readOnly = true)
  public List<CandidateResumeDTO> listCandidateResumes(Long candidateId) {
    if (!userRepository.existsById(candidateId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "候选人不存在");
    }
    return resumeRepository.findByOwnerUserIdOrderByUploadedAtDesc(candidateId).stream()
        .map(resume -> new CandidateResumeDTO(
            resume.getId(),
            resume.getOriginalFilename(),
            resume.getQuestionPrepareStatus() == null
                ? null
                : resume.getQuestionPrepareStatus().name(),
            resume.getUploadedAt()))
        .toList();
  }

  private InterviewAssignmentEntity loadAssignment(Long assignmentId) {
    return assignmentRepository.findById(assignmentId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "面试任务不存在"));
  }

  private UserEntity findEligibleCandidate(Long candidateId) {
    UserEntity candidate = userRepository.findById(candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "候选人不存在"));
    boolean interviewee = candidate.getRoles().stream()
        .map(RoleEntity::getCode)
        .anyMatch(AuthBootstrapService.ROLE_INTERVIEWEE::equals);
    if (!interviewee) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "所选用户不是面试者");
    }
    if (!candidate.isLoginAllowed()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "候选人账号当前不可用");
    }
    return candidate;
  }

  private JobPositionEntity findActiveJob(Long jobId) {
    JobPositionEntity job = jobRepository.findById(jobId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "岗位不存在"));
    if (job.getStatus() != JobStatus.ACTIVE) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "只能为启用中的岗位分配面试任务");
    }
    return job;
  }

  private ResumeEntity findCandidateResume(Long resumeId, Long candidateId) {
    if (resumeId == null) {
      return null;
    }
    return resumeRepository.findByIdAndOwnerUserId(resumeId, candidateId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.FORBIDDEN,
            "简历不存在或不属于所选候选人"));
  }

  private List<InterviewAssignmentDTO> toDTOs(List<InterviewAssignmentEntity> assignments) {
    if (assignments.isEmpty()) {
      return List.of();
    }
    Map<Long, UserEntity> users = byId(
        userRepository.findAllById(ids(assignments, InterviewAssignmentEntity::getCandidateId)),
        UserEntity::getId);
    Map<Long, JobPositionEntity> jobs = byId(
        jobRepository.findAllById(ids(assignments, InterviewAssignmentEntity::getJobId)),
        JobPositionEntity::getId);
    List<Long> resumeIds = assignments.stream()
        .map(InterviewAssignmentEntity::getResumeId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    Map<Long, ResumeEntity> resumes = resumeIds.isEmpty()
        ? Collections.emptyMap()
        : byId(resumeRepository.findAllById(resumeIds), ResumeEntity::getId);
    return assignments.stream()
        .map(assignment -> toDTO(
            assignment,
            users.get(assignment.getCandidateId()),
            jobs.get(assignment.getJobId()),
            resumes.get(assignment.getResumeId())))
        .toList();
  }

  private InterviewAssignmentDTO toDTO(InterviewAssignmentEntity assignment) {
    UserEntity candidate = userRepository.findById(assignment.getCandidateId()).orElse(null);
    JobPositionEntity job = jobRepository.findById(assignment.getJobId()).orElse(null);
    ResumeEntity resume = assignment.getResumeId() == null
        ? null
        : resumeRepository.findById(assignment.getResumeId()).orElse(null);
    return toDTO(assignment, candidate, job, resume);
  }

  private InterviewAssignmentDTO toDTO(
      InterviewAssignmentEntity assignment,
      UserEntity candidate,
      JobPositionEntity job,
      ResumeEntity resume) {
    AssignmentStatus status = effectiveStatus(assignment);
    String candidateName = candidate == null
        ? "用户 #" + assignment.getCandidateId()
        : candidate.getNickname() == null || candidate.getNickname().isBlank()
            ? candidate.getUsername()
            : candidate.getNickname();
    return new InterviewAssignmentDTO(
        assignment.getId(),
        assignment.getCandidateId(),
        candidateName,
        candidate == null ? null : candidate.getPhone(),
        assignment.getJobId(),
        job == null ? "已删除岗位" : job.getName(),
        job == null ? null : job.getLevel(),
        assignment.getResumeId(),
        resume == null ? null : resume.getOriginalFilename(),
        status.name(),
        assignment.getAvailableFrom(),
        assignment.getDeadline(),
        assignment.isReportVisibleToCandidate(),
        assignment.getCreatedAt());
  }

  private AssignmentStatus effectiveStatus(InterviewAssignmentEntity assignment) {
    if ((assignment.getStatus() == AssignmentStatus.PENDING
        || assignment.getStatus() == AssignmentStatus.IN_PROGRESS)
        && assignment.getDeadline().isBefore(LocalDateTime.now())) {
      return AssignmentStatus.EXPIRED;
    }
    return assignment.getStatus();
  }

  private <T> List<Long> ids(
      List<T> items,
      Function<T, Long> idExtractor) {
    return items.stream().map(idExtractor).distinct().toList();
  }

  private <T> Map<Long, T> byId(
      Collection<T> items,
      Function<T, Long> idExtractor) {
    return items.stream().collect(Collectors.toMap(idExtractor, Function.identity()));
  }
}
