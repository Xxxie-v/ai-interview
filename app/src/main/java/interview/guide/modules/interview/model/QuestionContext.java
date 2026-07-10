package interview.guide.modules.interview.model;

import java.util.List;

public record QuestionContext(
    String questionId,
    String mainQuestion,
    List<String> jdCapabilities,
    List<String> resumeEvidence,
    List<String> dimensions
) {

  public static final List<String> DEFAULT_DIMENSIONS = List.of(
      "implementation",
      "principle",
      "failureHandling",
      "tradeoff");

  public QuestionContext {
    jdCapabilities = jdCapabilities == null ? List.of() : List.copyOf(jdCapabilities);
    resumeEvidence = resumeEvidence == null ? List.of() : List.copyOf(resumeEvidence);
    dimensions = dimensions == null || dimensions.isEmpty()
        ? DEFAULT_DIMENSIONS
        : List.copyOf(dimensions);
  }
}
