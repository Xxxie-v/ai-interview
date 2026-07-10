package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.interview.model.InterviewReviewStatus;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    boolean existsBySessionId(String sessionId);

    /**
     * 根据会话ID查找
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE InterviewSessionEntity s SET "
        + "s.overallScore = :overallScore, "
        + "s.overallFeedback = :overallFeedback, "
        + "s.strengthsJson = :strengthsJson, "
        + "s.improvementsJson = :improvementsJson, "
        + "s.referenceAnswersJson = :referenceAnswersJson, "
        + "s.status = :status, "
        + "s.reviewStatus = CASE "
        + "WHEN s.reviewStatus IS NULL OR s.reviewStatus = :incomplete "
        + "THEN :underReview ELSE s.reviewStatus END, "
        + "s.completedAt = :completedAt, "
        + "s.version = s.version + 1 "
        + "WHERE s.sessionId = :sessionId")
    int updateReportAtomically(
        @Param("sessionId") String sessionId,
        @Param("overallScore") Integer overallScore,
        @Param("overallFeedback") String overallFeedback,
        @Param("strengthsJson") String strengthsJson,
        @Param("improvementsJson") String improvementsJson,
        @Param("referenceAnswersJson") String referenceAnswersJson,
        @Param("status") SessionStatus status,
        @Param("incomplete") InterviewReviewStatus incomplete,
        @Param("underReview") InterviewReviewStatus underReview,
        @Param("completedAt") LocalDateTime completedAt);

    Optional<InterviewSessionEntity> findBySessionIdAndOwnerUserId(String sessionId, Long ownerUserId);

    Optional<InterviewSessionEntity> findFirstByOwnerUserIdAndJobIdOrderByCreatedAtDesc(
        Long ownerUserId,
        Long jobId);

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);

    @Query("SELECT s FROM InterviewSessionEntity s "
        + "WHERE s.status = :sessionStatus "
        + "AND s.questionPrepareStatus IN :statuses "
        + "AND (s.questionsJson IS NULL OR s.questionsJson = '' OR s.questionsJson = '[]') "
        + "AND (s.questionPrepareUpdatedAt IS NULL OR s.questionPrepareUpdatedAt < :cutoff) "
        + "ORDER BY s.createdAt ASC")
    List<InterviewSessionEntity> findStaleQuestionPreparationSessions(
        @Param("sessionStatus") SessionStatus sessionStatus,
        @Param("statuses") List<AsyncTaskStatus> statuses,
        @Param("cutoff") LocalDateTime cutoff,
        Pageable pageable);

    @Modifying
    @Query("UPDATE InterviewSessionEntity s "
        + "SET s.questionPrepareStatus = :completed, "
        + "s.questionPrepareError = NULL, "
        + "s.questionPreparedAt = COALESCE(s.questionPreparedAt, s.createdAt), "
        + "s.questionPrepareUpdatedAt = :updatedAt "
        + "WHERE s.questionPrepareStatus <> :completed "
        + "AND s.questionsJson IS NOT NULL "
        + "AND s.questionsJson <> '' "
        + "AND s.questionsJson <> '[]'")
    int reconcileExistingPreparedQuestions(
        @Param("completed") AsyncTaskStatus completed,
        @Param("updatedAt") LocalDateTime updatedAt);
    
    /**
     * 根据简历查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    List<InterviewSessionEntity> findByResumeIdAndOwnerUserIdOrderByCreatedAtDesc(
        Long resumeId, Long ownerUserId);

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );

    Optional<InterviewSessionEntity> findFirstByResumeIdAndOwnerUserIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId,
        Long ownerUserId,
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）
     */
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume "
        + "WHERE s.ownerUserId = :ownerUserId ORDER BY s.createdAt DESC")
    List<InterviewSessionEntity> findByOwnerUserIdOrderByCreatedAtDesc(
        @Param("ownerUserId") Long ownerUserId);

    @Query(
        value = "SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume "
            + "WHERE s.officialInterview = true",
        countQuery = "SELECT COUNT(s) FROM InterviewSessionEntity s "
            + "WHERE s.officialInterview = true")
    Page<InterviewSessionEntity> findOfficialSessionsWithResume(Pageable pageable);

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);
}
