package interview.guide.modules.interview.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SubmitAnswerBody(
    @NotNull(message = "问题索引不能为空")
    @Min(value = 0, message = "问题索引无效")
    Integer questionIndex,

    @NotBlank(message = "答案不能为空")
    String answer
) {
}
