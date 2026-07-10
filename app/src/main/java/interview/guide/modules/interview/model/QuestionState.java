package interview.guide.modules.interview.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record QuestionState(
    Map<String, Double> coverage,
    List<String> coveredTopics,
    List<QuestionAnswerSnapshot> recentQa,
    int followUpCount,
    int stagnantRounds,
    Long startedAtEpochMillis
) {

  public QuestionState {
    coverage = coverage == null ? Map.of() : Map.copyOf(coverage);
    coveredTopics = coveredTopics == null ? List.of() : List.copyOf(coveredTopics);
    recentQa = recentQa == null ? List.of() : List.copyOf(recentQa);
  }

  public static QuestionState initial(List<String> dimensions) {
    Map<String, Double> initialCoverage = new LinkedHashMap<>();
    dimensions.forEach(dimension -> initialCoverage.put(dimension, 0.0));
    return new QuestionState(initialCoverage, List.of(), List.of(), 0, 0, null);
  }

  public QuestionState update(
      Map<String, Double> evaluatedCoverage,
      List<String> evaluatedTopics,
      String question,
      String answer,
      int recentQaLimit,
      long nowEpochMillis) {
    Map<String, Double> mergedCoverage = new LinkedHashMap<>(coverage);
    if (evaluatedCoverage != null) {
      evaluatedCoverage.forEach((dimension, value) -> {
        if (dimension == null || dimension.isBlank() || value == null) return;
        double normalized = Math.max(0.0, Math.min(1.0, value));
        mergedCoverage.merge(dimension, normalized, Math::max);
      });
    }

    Set<String> mergedTopics = new LinkedHashSet<>(coveredTopics);
    int previousTopicCount = mergedTopics.size();
    if (evaluatedTopics != null) {
      evaluatedTopics.stream()
          .filter(topic -> topic != null && !topic.isBlank())
          .map(String::trim)
          .forEach(mergedTopics::add);
    }
    int updatedStagnantRounds = mergedTopics.size() == previousTopicCount
        ? stagnantRounds + 1
        : 0;

    List<QuestionAnswerSnapshot> updatedRecentQa = new ArrayList<>(recentQa);
    updatedRecentQa.add(new QuestionAnswerSnapshot(question, answer));
    int limit = Math.max(1, recentQaLimit);
    if (updatedRecentQa.size() > limit) {
      updatedRecentQa = new ArrayList<>(
          updatedRecentQa.subList(updatedRecentQa.size() - limit, updatedRecentQa.size()));
    }

    return new QuestionState(
        mergedCoverage,
        List.copyOf(mergedTopics),
        updatedRecentQa,
        followUpCount,
        updatedStagnantRounds,
        startedAtEpochMillis == null ? nowEpochMillis : startedAtEpochMillis);
  }

  public QuestionState acceptFollowUp() {
    return new QuestionState(
        coverage,
        coveredTopics,
        recentQa,
        followUpCount + 1,
        stagnantRounds,
        startedAtEpochMillis);
  }
}
