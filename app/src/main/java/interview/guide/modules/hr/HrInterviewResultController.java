package interview.guide.modules.hr;

import interview.guide.common.result.Result;
import interview.guide.modules.hr.dto.HrInterviewResultDTO;
import interview.guide.modules.hr.dto.HrInterviewResultPageDTO;
import interview.guide.modules.hr.dto.UpdateInterviewReviewStatusRequest;
import interview.guide.modules.hr.service.HrInterviewResultService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/hr/interview-results")
public class HrInterviewResultController {

  private final HrInterviewResultService hrInterviewResultService;

  @GetMapping
  public Result<HrInterviewResultPageDTO> listOfficialResults(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.success(hrInterviewResultService.listOfficialResults(page, size));
  }

  @PatchMapping("/{sessionId}/review-status")
  public Result<HrInterviewResultDTO> updateReviewStatus(
      @PathVariable String sessionId,
      @Valid @RequestBody UpdateInterviewReviewStatusRequest request) {
    return Result.success(hrInterviewResultService.updateReviewStatus(
        sessionId, request.status()));
  }

  @GetMapping("/{sessionId}/resume")
  public ResponseEntity<byte[]> downloadResume(@PathVariable String sessionId) {
    var result = hrInterviewResultService.downloadResume(sessionId);
    String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
        .contentType(MediaType.parseMediaType(result.contentType()))
        .body(result.content());
  }
}
