package interview.guide.modules.hr.dto;

import java.util.List;

public record HrInterviewResultPageDTO(
    List<HrInterviewResultDTO> items,
    long totalElements,
    int totalPages,
    int page,
    int size
) {
}
