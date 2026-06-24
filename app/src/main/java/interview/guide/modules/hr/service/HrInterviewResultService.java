package interview.guide.modules.hr.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.auth.model.UserEntity;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.hr.dto.HrInterviewResultDTO;
import interview.guide.modules.hr.dto.HrInterviewResultPageDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewReviewStatus;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import java.util.List;
import java.util.Map;
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
public class HrInterviewResultService {

  private static final int MAX_PAGE_SIZE = 100;

  private final InterviewSessionRepository interviewSessionRepository;
  private final UserRepository userRepository;
  private final FileStorageService fileStorageService;
  private final JobPositionRepository jobPositionRepository;

  public record ResumeDownloadResult(
      String filename,
      String contentType,
      byte[] content) {
  }

  @Transactional(readOnly = true)
  public HrInterviewResultPageDTO listOfficialResults(int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    Page<InterviewSessionEntity> sessions = interviewSessionRepository
        .findOfficialSessionsWithResume(PageRequest.of(
            safePage,
            safeSize,
            Sort.by(Sort.Direction.DESC, "createdAt")));

    Map<Long, UserEntity> candidates = userRepository.findAllById(
            sessions.getContent().stream()
                .map(InterviewSessionEntity::getOwnerUserId)
                .distinct()
                .toList())
        .stream()
        .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    Map<Long, JobPositionEntity> jobs = jobPositionRepository.findAllById(
            sessions.getContent().stream()
                .map(InterviewSessionEntity::getJobId)
                .filter(jobId -> jobId != null)
                .distinct()
                .toList())
        .stream()
        .collect(Collectors.toMap(JobPositionEntity::getId, Function.identity()));

    List<HrInterviewResultDTO> items = sessions.getContent().stream()
        .map(session -> toDTO(
            session,
            candidates.get(session.getOwnerUserId()),
            jobs.get(session.getJobId())))
        .toList();
    return new HrInterviewResultPageDTO(
        items,
        sessions.getTotalElements(),
        sessions.getTotalPages(),
        safePage,
        safeSize);
  }

  @Transactional(readOnly = true)
  public ResumeDownloadResult downloadResume(String sessionId) {
    InterviewSessionEntity session = interviewSessionRepository.findBySessionIdWithResume(sessionId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "面试记录不存在"));
    ResumeEntity resume = session.getResume();
    if (resume == null) {
      throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "该面试记录未绑定简历");
    }
    if (!session.getOwnerUserId().equals(resume.getOwnerUserId())) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "面试记录与简历所属用户不一致");
    }
    if (resume.getStorageKey() == null || resume.getStorageKey().isBlank()) {
      throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "简历文件未保存到对象存储");
    }

    byte[] content = fileStorageService.downloadFile(resume.getStorageKey());
    String contentType = resume.getContentType() != null && !resume.getContentType().isBlank()
        ? resume.getContentType()
        : "application/octet-stream";
    return new ResumeDownloadResult(resume.getOriginalFilename(), contentType, content);
  }

  @Transactional
  public HrInterviewResultDTO updateReviewStatus(
      String sessionId,
      InterviewReviewStatus targetStatus) {
    InterviewSessionEntity session = interviewSessionRepository.findBySessionIdWithResume(sessionId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "面试记录不存在"));
    InterviewReviewStatus currentStatus = effectiveReviewStatus(session);
    if (!isAllowedTransition(currentStatus, targetStatus)) {
      throw new BusinessException(
          ErrorCode.BAD_REQUEST,
          "不允许将审核状态从 " + currentStatus + " 修改为 " + targetStatus);
    }
    session.setReviewStatus(targetStatus);
    interviewSessionRepository.save(session);
    return toDTO(session);
  }

  private HrInterviewResultDTO toDTO(InterviewSessionEntity session) {
    UserEntity candidate = userRepository.findById(session.getOwnerUserId()).orElse(null);
    JobPositionEntity job = session.getJobId() == null
        ? null
        : jobPositionRepository.findById(session.getJobId()).orElse(null);
    return toDTO(session, candidate, job);
  }

  private HrInterviewResultDTO toDTO(
      InterviewSessionEntity session,
      UserEntity candidate,
      JobPositionEntity job) {
    ResumeEntity resume = session.getResume();
    String candidateName = candidate != null ? candidate.getUsername() : "用户 #" + session.getOwnerUserId();
    String candidatePhone = candidate != null ? candidate.getPhone() : null;
    String resumeFilename = resume != null ? resume.getOriginalFilename() : null;

    return new HrInterviewResultDTO(
        session.getSessionId(),
        session.getResumeId(),
        session.getJobId(),
        job == null ? "未关联岗位" : job.getName(),
        session.getOwnerUserId(),
        candidateName,
        candidatePhone,
        resumeFilename,
        session.getSkillId(),
        session.getDifficulty(),
        effectiveReviewStatus(session),
        session.getCreatedAt(),
        session.getCompletedAt());
  }

  private InterviewReviewStatus effectiveReviewStatus(InterviewSessionEntity session) {
    return session.getEffectiveReviewStatus();
  }

  private boolean isAllowedTransition(
      InterviewReviewStatus currentStatus,
      InterviewReviewStatus targetStatus) {
    if (currentStatus == targetStatus) return true;
    return switch (currentStatus) {
      case INCOMPLETE -> false;
      case UNDER_MANUAL_REVIEW -> targetStatus == InterviewReviewStatus.PASSED
          || targetStatus == InterviewReviewStatus.REJECTED;
      case PASSED, REJECTED -> targetStatus == InterviewReviewStatus.UNDER_MANUAL_REVIEW;
    };
  }
}
