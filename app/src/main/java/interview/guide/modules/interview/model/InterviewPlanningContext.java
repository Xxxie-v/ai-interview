package interview.guide.modules.interview.model;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import java.util.List;

public record InterviewPlanningContext(
    Long candidateId,
    Long resumeId,
    Long jobId,
    String resumeText,
    String jobDescription,
    String jobLevel,
    List<CategoryDTO> categories,
    List<HistoricalQuestion> history,
    List<InterviewQuestionDTO> fixedJobQuestions,
    List<InterviewQuestionDTO> preparedResumeQuestions
) {
}
