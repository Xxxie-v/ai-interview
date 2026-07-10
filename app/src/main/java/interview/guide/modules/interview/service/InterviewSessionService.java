package interview.guide.modules.interview.service;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.routing.LlmTaskRouter;
import interview.guide.common.ai.routing.LlmTaskType;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.repository.UserRepository;
import interview.guide.modules.auth.service.AuthBootstrapService;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.listener.QuestionPrepareStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.DynamicAnswerEvaluation;
import interview.guide.modules.interview.model.HistoricalQuestion;
import interview.guide.modules.interview.model.InterviewFlowStatus;
import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewPlanningContext;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.NextAction;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.recruitment.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.AssignmentStatus;
import interview.guide.modules.recruitment.model.InterviewAssignmentEntity;
import interview.guide.modules.recruitment.model.JobStatus;
import interview.guide.modules.recruitment.repository.InterviewAssignmentRepository;
import interview.guide.modules.recruitment.repository.JobPositionRepository;
import interview.guide.modules.recruitment.service.JobQuestionBankService;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 面试会话管理服务
 * 管理面试会话的生命周期，使用 Redis 缓存会话状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private final InterviewPlannerService plannerService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final QuestionPrepareStreamProducer questionPrepareStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
    private final LlmTaskRouter taskRouter;
    private final ResumeRepository resumeRepository;
    private final JobPositionRepository jobRepository;
    private final InterviewAssignmentRepository assignmentRepository;
    private final JobQuestionBankService jobQuestionBankService;
    private final InterviewStateMachineService stateMachineService;
    private final DynamicFollowUpService dynamicFollowUpService;
    private final InterviewQuestionProperties questionProperties;
    private final RedisService redisService;
    private final UserRepository userRepository;

    /**
     * 创建新的面试会话
     * 注意：如果已有未完成的会话，不会创建新的，而是返回现有会话
     * 前端应该先调用 findUnfinishedSession 检查，或者使用 forceCreate 参数强制创建
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request, Long ownerUserId) {
        InterviewCreationContext context = resolveCreationContext(request, ownerUserId);
        boolean unlimitedInterviews = hasUnlimitedInterviews(ownerUserId);
        if (request.jobId() != null && !unlimitedInterviews) {
            String lockKey = "interview:create:{" + ownerUserId + ":" + request.jobId() + "}";
            return redisService.executeWithLock(
                lockKey,
                5,
                300,
                TimeUnit.SECONDS,
                () -> createSessionInternal(request, ownerUserId, false, context));
        }
        return createSessionInternal(request, ownerUserId, unlimitedInterviews, context);
    }

    private InterviewSessionDTO createSessionInternal(
        CreateInterviewRequest request,
        Long ownerUserId,
        boolean unlimitedInterviews,
        InterviewCreationContext context) {

        if (context.jobId() != null && !unlimitedInterviews) {
            Optional<InterviewSessionEntity> existing = persistenceService
                .findByOwnerUserIdAndJobId(ownerUserId, context.jobId());
            if (existing.isPresent()) {
                InterviewSessionEntity session = existing.get();
                if (session.getStatus() == InterviewSessionEntity.SessionStatus.CREATED
                    || session.getStatus() == InterviewSessionEntity.SessionStatus.IN_PROGRESS) {
                    log.info(
                        "Resume existing official interview: ownerUserId={}, jobId={}, sessionId={}",
                        ownerUserId,
                        context.jobId(),
                        session.getSessionId());
                    return getSession(session.getSessionId());
                }
                throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "每位候选人每个岗位只有一次面试机会，您已参加过该岗位面试");
            }
        }

        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (context.jobId() == null
            && context.resumeId() != null
            && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(
                context.resumeId(), ownerUserId);
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                    request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = context.skillId();
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;
        String sessionProvider = llmProviderRegistry.resolveChatProviderId(request.llmProvider());

        log.info(
            "创建新面试会话: sessionId={}, skill={}, difficulty={}, questionCount={}, resumeId={}, jobId={}",
            sessionId, skillId, difficulty, request.questionCount(), context.resumeId(),
            context.jobId());

        List<InterviewQuestionDTO> questions = List.of();

        // 保存到数据库
        try {
            persistenceService.saveSession(
                sessionId,
                context.resumeId(),
                context.jobId(),
                context.assignmentId(),
                ownerUserId,
                request.questionCount(), questions, sessionProvider, skillId, difficulty,
                context.officialInterview());
        } catch (DataIntegrityViolationException e) {
            if (context.jobId() != null) {
                throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "每位候选人每个岗位只有一次面试机会，不能重复参加",
                    e);
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存面试会话失败", e);
        } catch (Exception e) {
            log.error("Failed to persist interview session: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Failed to create interview session", e);
        }

        sessionCache.saveSession(
            sessionId,
            context.resumeText(),
            context.resumeId(),
            questions,
            0,
            SessionStatus.CREATED);
        questionPrepareStreamProducer.sendQuestionPrepareTask(sessionId);

        return new InterviewSessionDTO(
            sessionId,
            context.resumeText(),
            request.questionCount(),
            0,
            questions,
            SessionStatus.CREATED,
            "/ws/interviews/" + sessionId,
            AsyncTaskStatus.PENDING,
            null,
            AsyncTaskStatus.PENDING,
            null
        );
    }

    private boolean hasUnlimitedInterviews(Long ownerUserId) {
        return userRepository.findById(ownerUserId)
            .map(user -> user.getRoles().stream().anyMatch(
                role -> AuthBootstrapService.ROLE_TEST_INTERVIEWEE.equals(role.getCode())))
            .orElse(false);
    }

    private InterviewCreationContext resolveCreationContext(
        CreateInterviewRequest request,
        Long ownerUserId) {
        if (request.jobId() == null) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "模拟面试功能已下线，请选择招聘岗位参加正式面试");
        }
        ResumeEntity resume = null;
        if (request.resumeId() != null) {
            resume = resumeRepository.findByIdAndOwnerUserId(request.resumeId(), ownerUserId)
                .orElseThrow(() -> new BusinessException(
                    ErrorCode.RESUME_NOT_FOUND,
                    "简历不存在或不属于当前用户"));
        }

        String resumeText = resume == null
            ? request.resumeText() == null ? "" : request.resumeText()
            : resume.getResumeText();
        String skillId = request.skillId() != null
            ? request.skillId()
            : InterviewDefaults.SKILL_ID;
        List<CategoryDTO> categories = request.customCategories();
        String jdText = request.jdText();
        boolean officialInterview = Boolean.TRUE.equals(request.officialInterview());
        Long assignmentId = null;
        List<InterviewQuestionDTO> fixedJobQuestions = List.of();
        List<InterviewQuestionDTO> preparedResumeQuestions = resume == null
            ? List.of()
            : readPreparedQuestions(resume.getPreparedQuestionsJson());

        if (request.jobId() != null) {
            if (resume == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请先上传并选择一份简历");
            }
            JobPositionEntity job = jobRepository
                .findByIdAndStatus(request.jobId(), JobStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "岗位不存在或已停止招聘"));
            skillId = InterviewSkillService.CUSTOM_SKILL_ID;
            categories = List.of(
                new CategoryDTO("job-skills", "岗位专业能力", "CORE", null, false),
                new CategoryDTO("project-experience", "项目与实践经历", "CORE", null, false),
                new CategoryDTO("role-fit", "岗位匹配与综合能力", "NORMAL", null, false));
            jdText = buildJobDescription(job);
            officialInterview = true;
            fixedJobQuestions = readPreparedQuestions(job.getFixedQuestionsJson());
            if (fixedJobQuestions.size() < 3) {
                fixedJobQuestions = jobQuestionBankService.selectFixedQuestions(
                    job.getName(), job.getDescription(), job.getRequirements());
                job.setFixedQuestionsJson(writePreparedQuestions(fixedJobQuestions));
                jobRepository.save(job);
            }
            assignmentId = assignmentRepository
                .findFirstByCandidateIdAndJobIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
                    ownerUserId,
                    request.jobId(),
                    resume.getId(),
                    List.of(AssignmentStatus.PENDING, AssignmentStatus.IN_PROGRESS))
                .map(InterviewAssignmentEntity::getId)
                .orElse(null);
        }

        return new InterviewCreationContext(
            resume == null ? null : resume.getId(),
            request.jobId(),
            assignmentId,
            resumeText == null ? "" : resumeText,
            skillId,
            categories,
            jdText,
            officialInterview,
            fixedJobQuestions,
            preparedResumeQuestions);
    }

    private List<InterviewQuestionDTO> readPreparedQuestions(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JacksonException e) {
            log.warn("Prepared question cache is invalid and will be regenerated");
            return List.of();
        }
    }

    private String writePreparedQuestions(List<InterviewQuestionDTO> questions) {
        try {
            return objectMapper.writeValueAsString(questions);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "岗位固定题保存失败", e);
        }
    }

    private String buildJobDescription(JobPositionEntity job) {
        return """
            岗位名称：%s
            岗位级别：%s
            岗位描述：%s
            任职要求：%s
            """.formatted(
                job.getName(),
                job.getLevel(),
                job.getDescription(),
                job.getRequirements());
    }

    private record InterviewCreationContext(
        Long resumeId,
        Long jobId,
        Long assignmentId,
        String resumeText,
        String skillId,
        List<CategoryDTO> customCategories,
        String jdText,
        boolean officialInterview,
        List<InterviewQuestionDTO> fixedJobQuestions,
        List<InterviewQuestionDTO> preparedResumeQuestions) {
    }

    public void prepareQuestions(String sessionId) {
        String lockKey = "interview:question:prepare:{" + sessionId + "}";
        redisService.executeWithLock(
            lockKey,
            1,
            180,
            TimeUnit.SECONDS,
            () -> {
                prepareQuestionsInternal(sessionId);
                return null;
            });
    }

    private void prepareQuestionsInternal(String sessionId) {
        long startedAt = System.nanoTime();
        InterviewSessionEntity entity = persistenceService.findBySessionIdWithResume(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        List<InterviewQuestionDTO> existingQuestions = readPreparedQuestions(
            entity.getQuestionsJson());
        if (!existingQuestions.isEmpty()) {
            persistenceService.updateQuestionPrepareStatus(
                sessionId,
                AsyncTaskStatus.COMPLETED,
                null);
            ResumeEntity existingResume = entity.getResume();
            sessionCache.saveSession(
                sessionId,
                existingResume == null ? "" : existingResume.getResumeText(),
                existingResume == null ? null : existingResume.getId(),
                existingQuestions,
                entity.getCurrentQuestionIndex(),
                convertStatus(entity.getStatus()));
            log.info(
                "Question preparation skipped because questions already exist: sessionId={}",
                sessionId);
            return;
        }
        if (entity.getQuestionPrepareStatus() == AsyncTaskStatus.COMPLETED) {
            log.warn(
                "Question preparation status was completed without questions; regenerating: sessionId={}",
                sessionId);
            persistenceService.updateQuestionPrepareStatus(
                sessionId,
                AsyncTaskStatus.PROCESSING,
                null);
        }
        if (entity.getStatus() != InterviewSessionEntity.SessionStatus.CREATED) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Interview has already started; questions cannot be regenerated");
        }
        ResumeEntity resume = entity.getResume();
        if (resume == null || entity.getJobId() == null) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Official interview question preparation requires a resume and a job");
        }
        JobPositionEntity job = jobRepository.findById(entity.getJobId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Job does not exist"));
        List<InterviewQuestionDTO> fixedQuestions = readPreparedQuestions(job.getFixedQuestionsJson());
        if (fixedQuestions.size() < 3) {
            fixedQuestions = jobQuestionBankService.selectFixedQuestions(
                job.getName(), job.getDescription(), job.getRequirements());
            job.setFixedQuestionsJson(writePreparedQuestions(fixedQuestions));
            jobRepository.save(job);
        }
        List<CategoryDTO> categories = List.of(
            new CategoryDTO("job-skills", "岗位专业能力", "CORE", null, false),
            new CategoryDTO("project-experience", "项目与实践经验", "CORE", null, false),
            new CategoryDTO("role-fit", "岗位匹配与综合能力", "NORMAL", null, false));
        List<HistoricalQuestion> history = persistenceService.getHistoricalQuestions(
            entity.getSkillId(),
            resume.getId());
        List<InterviewQuestionDTO> questions = plannerService.planQuestions(
            entity.getLlmProvider(),
            entity.getSkillId(),
            entity.getDifficulty(),
            entity.getTotalQuestions(),
            new InterviewPlanningContext(
                entity.getOwnerUserId(),
                resume.getId(),
                entity.getJobId(),
                resume.getResumeText(),
                buildJobDescription(job),
                entity.getDifficulty(),
                categories,
                history,
                fixedQuestions,
                readPreparedQuestions(resume.getPreparedQuestionsJson())));
        persistenceService.savePreparedQuestions(sessionId, questions);
        sessionCache.saveSession(
            sessionId,
            resume.getResumeText(),
            resume.getId(),
            questions,
            0,
            SessionStatus.CREATED);
        log.info(
            "Asynchronous question preparation completed: sessionId={}, questionCount={}, durationMs={}",
            sessionId,
            questions.size(),
            Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
    }

    public void assertQuestionsReady(String sessionId) {
        InterviewSessionEntity entity = persistenceService.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        if (entity.getQuestionPrepareStatus() != AsyncTaskStatus.COMPLETED) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Interview questions are still being prepared");
        }
    }

    public InterviewSessionDTO retryQuestionPreparation(String sessionId, Long ownerUserId) {
        InterviewSessionEntity entity = persistenceService
            .findBySessionIdAndOwnerUserId(sessionId, ownerUserId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        if (entity.getStatus() != InterviewSessionEntity.SessionStatus.CREATED) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Interview has already started; questions cannot be regenerated");
        }
        if (entity.getQuestionPrepareStatus() != AsyncTaskStatus.FAILED) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Only failed question preparation tasks can be retried");
        }
        persistenceService.updateQuestionPrepareStatus(
            sessionId,
            AsyncTaskStatus.PENDING,
            null);
        sessionCache.updateQuestionPreparation(sessionId, AsyncTaskStatus.PENDING, null);
        questionPrepareStreamProducer.sendQuestionPrepareTask(sessionId);
        return getSession(sessionId);
    }

    /**
     * 获取会话信息（优先从缓存获取，缓存未命中则从数据库恢复）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return toDTO(restoredSession);
    }

    /**
     * 查找并恢复未完成的面试会话
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId, Long ownerUserId) {
        try {
            // 1. 先从 Redis 缓存查找
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(resumeId);
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
                if (cachedOpt.isPresent()) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. 缓存未命中，从数据库查找
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findUnfinishedSession(
                resumeId, ownerUserId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            InterviewSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("恢复未完成会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 查找并恢复未完成的面试会话，如果不存在则抛出异常
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId, Long ownerUserId) {
        return findUnfinishedSession(resumeId, ownerUserId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 从数据库恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从实体恢复会话并缓存到 Redis
     */
    private CachedSession restoreSessionFromEntity(InterviewSessionEntity entity) {
        try {
            // 解析问题列表
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                entity.getQuestionsJson(),
                new TypeReference<>() {}
            );

            // 恢复已保存的答案
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            for (InterviewAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (index >= 0 && index < questions.size()) {
                    InterviewQuestionDTO question = questions.get(index);
                    questions.set(index, question.withAnswer(answer.getUserAnswer()));
                }
            }

            SessionStatus status = convertStatus(entity.getStatus());

            // 保存到 Redis 缓存
            sessionCache.saveSession(
                entity.getSessionId(),
                entity.getResume() != null ? entity.getResume().getResumeText() : "",
                entity.getResume() != null ? entity.getResume().getId() : null,
                questions,
                entity.getCurrentQuestionIndex(),
                status
            );
            sessionCache.updateQuestionPreparation(
                entity.getSessionId(),
                entity.getQuestionPrepareStatus(),
                entity.getQuestionPrepareError());

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            // 返回缓存的会话
            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 获取当前问题的响应（包含完成状态）
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                "completed", true,
                "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
            "completed", false,
            "question", question
        );
    }

    /**
     * 获取当前问题
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        InterviewFlowStatus flowStatus = stateMachineService.getStatus(sessionId);
        if (flowStatus == InterviewFlowStatus.READY) {
            stateMachineService.transition(sessionId, InterviewFlowStatus.QUESTIONING);
            session.setStatus(SessionStatus.IN_PROGRESS);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);
        } else if (flowStatus != InterviewFlowStatus.QUESTIONING) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "当前面试状态不允许获取问题: " + flowStatus);
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案（并进入下一题）
     * 如果是最后一题，自动触发异步评估
     */
    public SubmitAnswerResponse submitAnswer(
        SubmitAnswerRequest request,
        String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少有效的 Idempotency-Key");
        }
        String resultKey = "interview:idempotency:{" + request.sessionId() + "}:" + idempotencyKey;
        SubmitAnswerResponse cached = redisService.get(resultKey);
        if (cached != null) {
            return cached;
        }
        String lockKey = resultKey + ":lock";
        return redisService.executeWithLock(lockKey, 2, 180, TimeUnit.SECONDS, () -> {
            SubmitAnswerResponse lockedCached = redisService.get(resultKey);
            if (lockedCached != null) {
                return lockedCached;
            }
            SubmitAnswerResponse response = submitAnswerInternal(request);
            redisService.set(resultKey, response, Duration.ofHours(24));
            return response;
        });
    }

    private SubmitAnswerResponse submitAnswerInternal(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size() || index != session.getCurrentIndex()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }
        InterviewFlowStatus submissionStatus = stateMachineService.getStatus(request.sessionId());
        if (submissionStatus != InterviewFlowStatus.QUESTIONING
            && submissionStatus != InterviewFlowStatus.ANSWERING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前面试状态不允许提交答案");
        }
        if (submissionStatus == InterviewFlowStatus.QUESTIONING) {
            stateMachineService.transition(request.sessionId(), InterviewFlowStatus.ANSWERING);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);
        stateMachineService.transition(request.sessionId(), InterviewFlowStatus.EVALUATING);

        persistenceService.saveAnswer(
            request.sessionId(),
            index,
            question.question(),
            question.category(),
            request.answer(),
            0,
            null);

        InterviewSessionEntity persistedSession = persistenceService
            .findBySessionId(request.sessionId())
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        DynamicAnswerEvaluation dynamicEvaluation = null;
        if (persistedSession.getJobId() != null) {
            dynamicEvaluation = dynamicFollowUpService.evaluate(
                persistedSession.getLlmProvider(),
                answeredQuestion,
                request.answer(),
                questions);
            questions = updateQuestionState(questions, index, dynamicEvaluation);
            questions = insertDynamicFollowUp(questions, index, dynamicEvaluation);
        }

        // 移动到下一题
        int newIndex = index + 1;

        // 检查是否全部完成
        // 面试是否结束只由已规划题目数决定。逐题模型只能决定是否追问，
        // 不能在尚有主问题时让候选人提前退出面试。
        boolean hasNextQuestion = newIndex < questions.size();
        InterviewQuestionDTO nextQuestion = hasNextQuestion ? questions.get(newIndex) : null;

        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);
        sessionCache.updateCurrentIndex(request.sessionId(), newIndex);
        if (newStatus == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.COMPLETED);
        }

        // 保存答案到数据库
        try {
            persistenceService.updateCurrentQuestionIndex(request.sessionId(), newIndex);
            persistenceService.updateQuestions(request.sessionId(), questions);
            persistenceService.updateSessionStatus(request.sessionId(),
                newStatus == SessionStatus.COMPLETED
                    ? InterviewSessionEntity.SessionStatus.COMPLETED
                    : InterviewSessionEntity.SessionStatus.IN_PROGRESS);

            // 如果是最后一题，设置评估状态为 PENDING 并触发异步评估
            if (!hasNextQuestion) {
                persistenceService.updateEvaluateStatus(request.sessionId(), AsyncTaskStatus.PENDING, null);
            }
        } catch (Exception e) {
            log.error("保存答案到数据库失败: sessionId={}", request.sessionId(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存面试答案失败");
        }

        stateMachineService.transition(
            request.sessionId(),
            hasNextQuestion ? InterviewFlowStatus.QUESTIONING : InterviewFlowStatus.FINISHED);

        // 先完成会话状态持久化，再发送异步评估任务，避免消费者
        // 与当前请求同时更新 InterviewSessionEntity 引发乐观锁冲突。
        if (!hasNextQuestion) {
            evaluateStreamProducer.sendEvaluateTask(request.sessionId());
            log.info("会话 {} 已完成所有问题，评估任务已入队", request.sessionId());
        }

        log.info("会话 {} 提交答案: 问题{}, 剩余{}题",
            request.sessionId(), index, questions.size() - newIndex);

        return new SubmitAnswerResponse(
            hasNextQuestion,
            nextQuestion,
            newIndex,
            questions.size()
        );
    }

    private List<InterviewQuestionDTO> insertDynamicFollowUp(
        List<InterviewQuestionDTO> questions,
        int currentIndex,
        DynamicAnswerEvaluation evaluation) {
        if (evaluation.nextAction() != NextAction.DEEP_FOLLOW_UP
            && evaluation.nextAction() != NextAction.CLARIFY) {
            return questions;
        }
        if (questions.size() >= questionProperties.getDynamicMaxTotalQuestions()) {
            return questions;
        }

        InterviewQuestionDTO current = questions.get(currentIndex);
        int parentIndex = current.isFollowUp() && current.parentQuestionIndex() != null
            ? current.parentQuestionIndex()
            : currentIndex;
        long followUpCount = questions.stream()
            .filter(InterviewQuestionDTO::isFollowUp)
            .filter(question -> question.parentQuestionIndex() != null
                && question.parentQuestionIndex() == parentIndex)
            .count();
        if (followUpCount >= questionProperties.getDynamicMaxFollowUpsPerTopic()) {
            return questions;
        }

        List<InterviewQuestionDTO> updated = new ArrayList<>(questions.size() + 1);
        for (int index = 0; index < questions.size(); index++) {
            InterviewQuestionDTO question = questions.get(index);
            int newQuestionIndex = index > currentIndex ? index + 1 : index;
            Integer adjustedParent = question.parentQuestionIndex();
            if (adjustedParent != null && adjustedParent > currentIndex) {
                adjustedParent++;
            }
            updated.add(question.reindex(newQuestionIndex, adjustedParent));
            if (index == currentIndex) {
                InterviewQuestionDTO root = questions.get(parentIndex);
                updated.add(InterviewQuestionDTO.create(
                    currentIndex + 1,
                    evaluation.nextQuestion(),
                    current.type(),
                    current.category() + "（动态追问）",
                    null,
                    true,
                    parentIndex,
                    root.questionContext(),
                    evaluation.questionState()));
            }
        }
        log.info(
            "Dynamic follow-up inserted: parentIndex={}, action={}, totalQuestions={}",
            parentIndex,
            evaluation.nextAction(),
            updated.size());
        return updated;
    }

    private List<InterviewQuestionDTO> updateQuestionState(
        List<InterviewQuestionDTO> questions,
        int currentIndex,
        DynamicAnswerEvaluation evaluation) {
        if (evaluation.questionState() == null) return questions;
        InterviewQuestionDTO current = questions.get(currentIndex);
        int parentIndex = current.isFollowUp() && current.parentQuestionIndex() != null
            ? current.parentQuestionIndex()
            : currentIndex;
        List<InterviewQuestionDTO> updated = new ArrayList<>(questions);
        InterviewQuestionDTO root = updated.get(parentIndex);
        updated.set(parentIndex, root.withFollowUpMemory(
            root.questionContext(),
            evaluation.questionState()));
        return updated;
    }

    /**
     * 暂存答案（不进入下一题）
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        // 保存答案到数据库（不更新currentIndex）
        try {
            persistenceService.saveAnswer(
                request.sessionId(), index,
                question.question(), question.category(),
                request.answer(), 0, null
            );
            persistenceService.updateSessionStatus(request.sessionId(),
                InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: {}", e.getMessage());
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId(), index);
    }

    /**
     * 提前交卷（触发异步评估）
     */
    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        // 更新 Redis 缓存
        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 更新数据库状态
        try {
            persistenceService.updateSessionStatus(sessionId,
                InterviewSessionEntity.SessionStatus.COMPLETED);
            // 设置评估状态为 PENDING
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (Exception e) {
            log.warn("更新会话状态失败: {}", e.getMessage());
        }

        stateMachineService.transition(sessionId, InterviewFlowStatus.FINISHED);

        // 状态持久化完成后再入队，避免消费者与请求线程并发更新同一会话。
        evaluateStreamProducer.sendEvaluateTask(sessionId);

        log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
    }

    public void pauseInterview(String sessionId) {
        stateMachineService.transition(sessionId, InterviewFlowStatus.PAUSED);
    }

    public InterviewFlowStatus confirmDeviceReady(String sessionId) {
        InterviewFlowStatus status = stateMachineService.getStatus(sessionId);
        if (status == InterviewFlowStatus.INIT) {
            stateMachineService.transition(sessionId, InterviewFlowStatus.DEVICE_CHECK);
            stateMachineService.transition(sessionId, InterviewFlowStatus.READY);
            return InterviewFlowStatus.READY;
        }
        if (status == InterviewFlowStatus.DEVICE_CHECK) {
            stateMachineService.transition(sessionId, InterviewFlowStatus.READY);
            return InterviewFlowStatus.READY;
        }
        return status;
    }

    public void resumeInterview(String sessionId) {
        stateMachineService.transition(sessionId, InterviewFlowStatus.QUESTIONING);
    }

    public InterviewFlowStatus getFlowStatus(String sessionId) {
        return stateMachineService.getStatus(sessionId);
    }

    /**
     * 获取或恢复会话（优先从缓存获取）
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 生成评估报告
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        // 获取 LLM 客户端
        String provider = null;
        Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        if (entityOpt.isPresent()) {
            provider = entityOpt.get().getLlmProvider();
        }
        String preferredProvider = llmProviderRegistry.resolveChatProviderId(provider);
        InterviewReportDTO report = taskRouter.execute(
            LlmTaskType.REPORT,
            preferredProvider,
            routedProvider -> evaluationService.evaluateInterview(
                llmProviderRegistry.getPlainChatClient(routedProvider),
                sessionId,
                session.getResumeText(),
                questions));

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage());
        }

        return report;
    }

    /**
     * 将缓存会话转换为 DTO
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        Optional<InterviewSessionEntity> entity = persistenceService.findBySessionId(
            session.getSessionId());
        AsyncTaskStatus prepareStatus = entity
            .map(InterviewSessionEntity::getQuestionPrepareStatus)
            .orElse(session.getQuestionPrepareStatus());
        String prepareError = entity
            .map(InterviewSessionEntity::getQuestionPrepareError)
            .orElse(session.getQuestionPrepareError());
        return new InterviewSessionDTO(
            session.getSessionId(),
            session.getResumeText(),
            questions.size(),
            session.getCurrentIndex(),
            questions,
            session.getStatus(),
            "/ws/interviews/" + session.getSessionId(),
            prepareStatus,
            prepareError,
            entity.map(InterviewSessionEntity::getEvaluateStatus).orElse(null),
            entity.map(InterviewSessionEntity::getEvaluateError).orElse(null)
        );
    }
}
