package interview.guide.modules.resume.service;

import interview.guide.common.config.AppConfigProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;

/**
 * 简历上传服务
 * 处理简历上传、解析的业务逻辑
 * 上传阶段只负责文件解析、存储和入库，不调用 AI 出题服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    private final ResumeParseService parseService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    private final FileValidationService fileValidationService;
    private final ResumeRepository resumeRepository;

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * 上传并分析简历（异步）
     *
     * @param file 简历文件
     * @return 上传结果（分析将异步进行）
     */
    public Map<String, Object> uploadAndPrepareQuestions(
        org.springframework.web.multipart.MultipartFile file,
        Long ownerUserId) {
        long startTime = System.currentTimeMillis();

        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();
        log.info("收到简历上传请求: {}, 大小: {} bytes ({}), 上传开始处理",
            fileName, fileSize, formatFileSize(fileSize));

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);

        // 3. 检查简历是否已存在（去重）
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file, ownerUserId);
        if (existingResume.isPresent()) {
            log.info("简历上传处理完成（重复）: {} - 耗时: {}ms",
                fileName, System.currentTimeMillis() - startTime);
            return handleDuplicateResume(existingResume.get());
        }

        // 4. 解析简历文本
        long parseStart = System.currentTimeMillis();
        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
        }
        log.info("简历文本解析完成: {} - 解析耗时: {}ms, 文本长度: {} 字符",
            fileName, System.currentTimeMillis() - parseStart, resumeText.length());

        // 5. 保存简历到RustFS
        long storageStart = System.currentTimeMillis();
        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到RustFS: {} - 存储耗时: {}ms",
            fileKey, System.currentTimeMillis() - storageStart);

        // 6. 保存简历到数据库。文本已经在当前请求完成解析，上传阶段不再 AI 出题。
        ResumeEntity savedResume = persistenceService.saveResume(
            file, resumeText, ownerUserId, fileKey, fileUrl);

        savedResume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
        savedResume.setAnalyzeError(null);
        savedResume.setQuestionPrepareStatus(AsyncTaskStatus.COMPLETED);
        savedResume.setQuestionPrepareError(null);
        savedResume.setPreparedQuestionsJson(null);
        savedResume.setQuestionsPreparedAt(LocalDateTime.now());
        resumeRepository.save(savedResume);

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("简历上传处理完成: {}, resumeId={} - 总耗时: {}ms (解析+存储+入库)",
            fileName, savedResume.getId(), totalTime);

        // 7. 返回解析完成状态。岗位匹配题将在用户确定岗位后生成。
        return Map.of(
            "resume", Map.of(
                "id", savedResume.getId(),
                "filename", savedResume.getOriginalFilename(),
                "questionPrepareStatus", AsyncTaskStatus.COMPLETED.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl,
                "resumeId", savedResume.getId()
            ),
            "duplicate", false
        );
    }

    /**
     * 格式化文件大小为可读字符串
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType) {
        fileValidationService.validateContentTypeByList(
            contentType,
            appConfig.getAllowedTypes(),
            "不支持的文件类型: " + contentType
        );
    }

    /**
     * 处理重复简历
     */
    private Map<String, Object> handleDuplicateResume(ResumeEntity resume) {
        log.info("检测到重复简历，返回已有解析结果: resumeId={}", resume.getId());
        resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
        resume.setAnalyzeError(null);
        resume.setQuestionPrepareStatus(AsyncTaskStatus.COMPLETED);
        resume.setQuestionPrepareError(null);
        resume.setPreparedQuestionsJson(null);
        resume.setQuestionsPreparedAt(
            resume.getQuestionsPreparedAt() == null ? resume.getUploadedAt() : resume.getQuestionsPreparedAt());
        resumeRepository.save(resume);
        return Map.of(
            "resume", Map.of(
                "id", resume.getId(),
                "filename", resume.getOriginalFilename(),
                "questionPrepareStatus",
                resume.getQuestionPrepareStatus() != null
                    ? resume.getQuestionPrepareStatus().name()
                    : AsyncTaskStatus.COMPLETED.name()),
            "storage", Map.of(
                "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                "resumeId", resume.getId()),
            "duplicate", true);
    }

    /**
     * 重新解析简历（保留旧接口名称以兼容前端）。
     *
     * @param resumeId 简历ID
     */
    @Transactional
    public void reanalyze(Long resumeId, Long ownerUserId) {
        ResumeEntity resume = resumeRepository.findByIdAndOwnerUserId(resumeId, ownerUserId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        log.info("开始重新解析简历: resumeId={}, filename={}",
            resumeId, resume.getOriginalFilename());

        String resumeText = resume.getResumeText();
        if (resumeText == null || resumeText.trim().isEmpty()) {
            // 如果没有缓存的文本，尝试重新解析
            resumeText = parseService.downloadAndParseContent(resume.getStorageKey(), resume.getOriginalFilename());
            if (resumeText == null || resumeText.trim().isEmpty()) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
            // 更新缓存的文本
            resume.setResumeText(resumeText);
        }

        resume.setAnalyzeStatus(AsyncTaskStatus.COMPLETED);
        resume.setAnalyzeError(null);
        resume.setQuestionPrepareStatus(AsyncTaskStatus.COMPLETED);
        resume.setQuestionPrepareError(null);
        resume.setPreparedQuestionsJson(null);
        resume.setQuestionsPreparedAt(LocalDateTime.now());
        resumeRepository.save(resume);
        log.info("简历重新解析完成: resumeId={}, parsedAt={}", resumeId, LocalDateTime.now());
    }
}
