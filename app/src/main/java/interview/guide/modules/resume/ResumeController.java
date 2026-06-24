package interview.guide.modules.resume;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.model.ResumeListItemDTO;
import interview.guide.modules.auth.security.AuthPrincipal;
import interview.guide.modules.resume.service.ResumeDeleteService;
import interview.guide.modules.resume.service.ResumeHistoryService;
import interview.guide.modules.resume.service.ResumeUploadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 简历控制器
 * Resume Controller for upload and analysis
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "简历管理", description = "简历上传、文本解析与删除")
public class ResumeController {

    private final ResumeUploadService uploadService;
    private final ResumeDeleteService deleteService;
    private final ResumeHistoryService historyService;

    /**
     * 上传简历并异步准备面试题。
     *
     * @param file 简历文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 上传结果和文本解析状态
     */
    @PostMapping(value = "/api/resumes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('INTERVIEWEE')")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<Map<String, Object>> uploadAndPrepareQuestions(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Map<String, Object> result = uploadService.uploadAndPrepareQuestions(
            file, principal.id());
        boolean isDuplicate = (Boolean) result.get("duplicate");
        if (isDuplicate) {
            return Result.success("检测到相同简历，已返回已有解析结果", result);
        }
        return Result.success(result);
    }

    /**
     * 获取所有简历列表
     */
    @GetMapping("/api/resumes")
    @PreAuthorize("hasRole('INTERVIEWEE')")
    public Result<List<ResumeListItemDTO>> getAllResumes(
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<ResumeListItemDTO> resumes = historyService.getAllResumes(principal.id());
        return Result.success(resumes);
    }

    /**
     * 获取简历详情和解析状态。
     */
    @GetMapping("/api/resumes/{id}/detail")
    @PreAuthorize("hasRole('INTERVIEWEE') and @resumePermission.isOwner(#id, authentication)")
    public Result<ResumeDetailDTO> getResumeDetail(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        ResumeDetailDTO detail = historyService.getResumeDetail(id, principal.id());
        return Result.success(detail);
    }

    /**
     * 导出简历分析报告为PDF
     */
    @GetMapping("/api/resumes/{id}/export")
    @PreAuthorize("hasRole('INTERVIEWEE') and @resumePermission.isOwner(#id, authentication)")
    public ResponseEntity<byte[]> exportAnalysisPdf(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        try {
            var result = historyService.exportAnalysisPdf(id, principal.id());
            String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(result.pdfBytes());
        } catch (Exception e) {
            log.error("导出PDF失败: resumeId={}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除简历
     *
     * @param id 简历ID
     * @return 删除结果
     */
    @DeleteMapping("/api/resumes/{id}")
    @PreAuthorize("hasRole('INTERVIEWEE') and @resumePermission.isOwner(#id, authentication)")
    public Result<Void> deleteResume(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        deleteService.deleteResume(id, principal.id());
        return Result.success(null);
    }

    /**
     * 重新生成简历面试题。
     *
     * @param id 简历ID
     * @return 结果
     */
    @PostMapping({
        "/api/resumes/{id}/questions/prepare",
        "/api/resumes/{id}/reanalyze"
    })
    @PreAuthorize("hasRole('INTERVIEWEE') and @resumePermission.isOwner(#id, authentication)")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> prepareQuestions(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        uploadService.reanalyze(id, principal.id());
        return Result.success(null);
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/api/resumes/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of(
            "status", "UP",
            "service", "AI Interview Platform - Resume Service"
        ));
    }

}
