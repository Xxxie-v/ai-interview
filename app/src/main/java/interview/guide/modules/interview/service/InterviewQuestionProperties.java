package interview.guide.modules.interview.service;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewQuestionProperties {

    private int followUpCount = 1;
    private String questionSystemPromptPath = "classpath:prompts/interview-question-skill-system.st";
    private String questionUserPromptPath = "classpath:prompts/interview-question-skill-user.st";
    private String resumeQuestionSystemPromptPath = "classpath:prompts/interview-question-resume-system.st";
    private String resumeQuestionUserPromptPath = "classpath:prompts/interview-question-resume-user.st";
    private String dynamicEvaluationPromptPath =
        "classpath:prompts/dynamic-answer-evaluation-system.st";
    private String dynamicFollowUpProvider = "dashscope";
    private String questionGenerationProvider = "dashscope-question";
    private Duration dynamicEvaluationTimeout = Duration.ofSeconds(6);
    private int dynamicMaxTotalQuestions = 48;
    private int dynamicMaxFollowUpsPerTopic = 2;
    private Duration dynamicMaxTopicDuration = Duration.ofMinutes(4);
    private double dynamicCoverageThreshold = 0.75;
    private int dynamicMaxStagnantRounds = 2;
    private int dynamicRecentQaLimit = 2;
    private int officialMinQuestionsPerSource = 3;
    private int officialMaxQuestionsPerSource = 6;
    private int jobFixedQuestionCount = 6;
    private int resumePreparedQuestionCount = 6;
}
