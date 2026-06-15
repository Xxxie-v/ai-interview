package interview.guide.modules.auth.dto;

import java.util.List;

public record AdminUserPageDTO(
    List<AdminUserDTO> items,
    long totalElements,
    int totalPages,
    int page,
    int size
) {
}
