package interview.guide.modules.interview.model;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 面试会话实体
 */
@Entity
@Table(name = "interview_sessions", indexes = {
    @Index(name = "idx_interview_session_resume_created", columnList = "resume_id,created_at"),
    @Index(name = "idx_interview_session_resume_status_created", columnList = "resume_id,status,created_at"),
    @Index(name = "idx_interview_session_skill_created", columnList = "skillId,createdAt"),
    @Index(name = "idx_interview_session_owner_job", columnList = "owner_user_id,job_id"),
    @Index(
        name = "idx_interview_question_prepare_recovery",
        columnList = "question_prepare_status,question_prepare_updated_at")
})
public class InterviewSessionEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "assignment_id")
    private Long assignmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "flow_status", length = 24)
    private InterviewFlowStatus flowStatus = InterviewFlowStatus.INIT;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long version = 0L;
    
    // 会话ID (UUID)
    @Column(nullable = false, unique = true, length = 36)
    private String sessionId;
    
    // 面试主题
    @Column(length = 64)
    private String skillId = "java-backend";

    // 难度级别 (junior / mid / senior)
    @Column(length = 16)
    private String difficulty = "mid";

    // 简历ID（直接映射FK列，避免LAZY加载触发额外查询）
    @Column(name = "resume_id", insertable = false, updatable = false)
    private Long resumeId;

    // 关联的简历（可选，支持无简历通用面试）
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id")
    private ResumeEntity resume;
    
    // 问题总数
    private Integer totalQuestions;
    
    // 当前问题索引
    private Integer currentQuestionIndex = 0;
    
    // 会话状态
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SessionStatus status = SessionStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "review_status",
        nullable = false,
        length = 32,
        columnDefinition = "varchar(32) default 'INCOMPLETE'")
    private InterviewReviewStatus reviewStatus = InterviewReviewStatus.INCOMPLETE;
    
    // 问题列表 (JSON格式)
    @Column(columnDefinition = "TEXT")
    private String questionsJson;
    
    // 总分 (0-100)
    private Integer overallScore;
    
    // 总体评价
    @Column(columnDefinition = "TEXT")
    private String overallFeedback;
    
    // 优势 (JSON)
    @Column(columnDefinition = "TEXT")
    private String strengthsJson;
    
    // 改进建议 (JSON)
    @Column(columnDefinition = "TEXT")
    private String improvementsJson;
    
    // 参考答案 (JSON)
    @Column(columnDefinition = "TEXT")
    private String referenceAnswersJson;
    
    // 面试答案记录
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InterviewAnswerEntity> answers = new ArrayList<>();
    
    // 创建时间
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    // 完成时间
    private LocalDateTime completedAt;

    // 评估状态（异步评估）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AsyncTaskStatus evaluateStatus;

    // 评估错误信息
    @Column(length = 500)
    private String evaluateError;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "question_prepare_status",
        nullable = false,
        length = 20,
        columnDefinition = "varchar(20) default 'PENDING'")
    private AsyncTaskStatus questionPrepareStatus = AsyncTaskStatus.PENDING;

    @Column(name = "question_prepare_error", length = 500)
    private String questionPrepareError;

    @Column(name = "question_prepared_at")
    private LocalDateTime questionPreparedAt;

    @Column(name = "question_prepare_updated_at")
    private LocalDateTime questionPrepareUpdatedAt;

    // LLM提供商
    @Column(length = 50)
    private String llmProvider;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean officialInterview;
    
    public enum SessionStatus {
        CREATED,      // 会话已创建
        IN_PROGRESS,  // 面试进行中
        COMPLETED,    // 面试已完成
        EVALUATED     // 已生成评估报告
    }
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (questionPrepareUpdatedAt == null) {
            questionPrepareUpdatedAt = createdAt;
        }
        if (reviewStatus == null) {
            reviewStatus = InterviewReviewStatus.INCOMPLETE;
        }
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Long getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(Long assignmentId) {
        this.assignmentId = assignmentId;
    }

    public InterviewFlowStatus getFlowStatus() {
        return flowStatus;
    }

    public void setFlowStatus(InterviewFlowStatus flowStatus) {
        this.flowStatus = flowStatus;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public Long getVersion() {
        return version;
    }
    
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public Long getResumeId() {
        return resumeId;
    }

    public ResumeEntity getResume() {
        return resume;
    }

    public void setResume(ResumeEntity resume) {
        this.resume = resume;
    }
    
    public Integer getTotalQuestions() {
        return totalQuestions;
    }
    
    public void setTotalQuestions(Integer totalQuestions) {
        this.totalQuestions = totalQuestions;
    }
    
    public Integer getCurrentQuestionIndex() {
        return currentQuestionIndex;
    }
    
    public void setCurrentQuestionIndex(Integer currentQuestionIndex) {
        this.currentQuestionIndex = currentQuestionIndex;
    }
    
    public SessionStatus getStatus() {
        return status;
    }
    
    public void setStatus(SessionStatus status) {
        this.status = status;
    }

    public InterviewReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public InterviewReviewStatus getEffectiveReviewStatus() {
        if ((reviewStatus == null || reviewStatus == InterviewReviewStatus.INCOMPLETE)
            && (status == SessionStatus.COMPLETED || status == SessionStatus.EVALUATED)) {
            return InterviewReviewStatus.UNDER_MANUAL_REVIEW;
        }
        return reviewStatus != null ? reviewStatus : InterviewReviewStatus.INCOMPLETE;
    }

    public void setReviewStatus(InterviewReviewStatus reviewStatus) {
        this.reviewStatus = reviewStatus;
    }
    
    public String getQuestionsJson() {
        return questionsJson;
    }
    
    public void setQuestionsJson(String questionsJson) {
        this.questionsJson = questionsJson;
    }
    
    public Integer getOverallScore() {
        return overallScore;
    }
    
    public void setOverallScore(Integer overallScore) {
        this.overallScore = overallScore;
    }
    
    public String getOverallFeedback() {
        return overallFeedback;
    }
    
    public void setOverallFeedback(String overallFeedback) {
        this.overallFeedback = overallFeedback;
    }
    
    public String getStrengthsJson() {
        return strengthsJson;
    }
    
    public void setStrengthsJson(String strengthsJson) {
        this.strengthsJson = strengthsJson;
    }
    
    public String getImprovementsJson() {
        return improvementsJson;
    }
    
    public void setImprovementsJson(String improvementsJson) {
        this.improvementsJson = improvementsJson;
    }
    
    public String getReferenceAnswersJson() {
        return referenceAnswersJson;
    }
    
    public void setReferenceAnswersJson(String referenceAnswersJson) {
        this.referenceAnswersJson = referenceAnswersJson;
    }
    
    public List<InterviewAnswerEntity> getAnswers() {
        return answers;
    }
    
    public void setAnswers(List<InterviewAnswerEntity> answers) {
        this.answers = answers;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
    
    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public AsyncTaskStatus getEvaluateStatus() {
        return evaluateStatus;
    }

    public void setEvaluateStatus(AsyncTaskStatus evaluateStatus) {
        this.evaluateStatus = evaluateStatus;
    }

    public String getEvaluateError() {
        return evaluateError;
    }

    public void setEvaluateError(String evaluateError) {
        this.evaluateError = evaluateError;
    }

    public AsyncTaskStatus getQuestionPrepareStatus() {
        if (questionPrepareStatus != null) {
            return questionPrepareStatus;
        }
        return questionsJson != null && !questionsJson.isBlank() && !"[]".equals(questionsJson)
            ? AsyncTaskStatus.COMPLETED
            : AsyncTaskStatus.PENDING;
    }

    public void setQuestionPrepareStatus(AsyncTaskStatus questionPrepareStatus) {
        this.questionPrepareStatus = questionPrepareStatus;
    }

    public String getQuestionPrepareError() {
        return questionPrepareError;
    }

    public void setQuestionPrepareError(String questionPrepareError) {
        this.questionPrepareError = questionPrepareError;
    }

    public LocalDateTime getQuestionPreparedAt() {
        return questionPreparedAt;
    }

    public void setQuestionPreparedAt(LocalDateTime questionPreparedAt) {
        this.questionPreparedAt = questionPreparedAt;
    }

    public LocalDateTime getQuestionPrepareUpdatedAt() {
        return questionPrepareUpdatedAt;
    }

    public void setQuestionPrepareUpdatedAt(LocalDateTime questionPrepareUpdatedAt) {
        this.questionPrepareUpdatedAt = questionPrepareUpdatedAt;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public boolean isOfficialInterview() {
        return officialInterview;
    }

    public void setOfficialInterview(boolean officialInterview) {
        this.officialInterview = officialInterview;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public void addAnswer(InterviewAnswerEntity answer) {
        answers.add(answer);
        answer.setSession(this);
    }
}
