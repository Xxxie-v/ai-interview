package interview.guide.modules.recruitment.dto;

import java.util.List;

public record PagedResponseDTO<T>(
    List<T> items,
    long totalElements,
    int totalPages,
    int page,
    int size
) {
}
