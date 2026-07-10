package interview.guide.modules.interview.model;

import java.util.List;
import java.util.Map;

public record DynamicFollowUpModelResult(
    Map<String, Double> coverage,
    List<String> coveredTopics,
    String targetDimension,
    Boolean needFollowUp,
    String question,
    String reasoningSummary
) {
}
