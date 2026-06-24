package interview.guide.modules.recruitment.dto;

import interview.guide.modules.recruitment.model.JobStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJobPositionRequest(
    @NotBlank(message = "岗位名称不能为空")
    @Size(max = 120, message = "岗位名称不能超过120个字符")
    String name,

    @NotBlank(message = "岗位描述不能为空")
    String description,

    @NotBlank(message = "岗位要求不能为空")
    String requirements,

    @NotBlank(message = "岗位级别不能为空")
    @Size(max = 64, message = "岗位级别不能超过64个字符")
    String level,

    JobStatus status
) {
}
