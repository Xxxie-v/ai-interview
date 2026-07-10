package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.QuestionContext;
import interview.guide.modules.interview.model.QuestionState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class QuestionContextFactory {

  private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("[\\r\\n。；;]+");
  private static final Pattern TERM_SEPARATOR = Pattern.compile("[^a-zA-Z0-9+#.一-龥]+");
  private static final int MAX_SEGMENT_LENGTH = 120;

  private QuestionContextFactory() {
  }

  static List<InterviewQuestionDTO> enrich(
      List<InterviewQuestionDTO> questions,
      String jobDescription,
      String resumeText) {
    List<InterviewQuestionDTO> enriched = new ArrayList<>(questions.size());
    for (InterviewQuestionDTO question : questions) {
      if (question.isFollowUp() || question.questionContext() != null) {
        enriched.add(question);
        continue;
      }
      Set<String> focusTerms = focusTerms(question);
      List<String> jdCapabilities = relevantSegments(jobDescription, focusTerms, 5);
      if (jdCapabilities.isEmpty()) {
        jdCapabilities = fallbackCapabilities(question);
      }
      List<String> resumeEvidence = relevantSegments(resumeText, focusTerms, 4);
      QuestionContext context = new QuestionContext(
          Integer.toString(question.questionIndex()),
          question.question(),
          jdCapabilities,
          resumeEvidence,
          QuestionContext.DEFAULT_DIMENSIONS);
      enriched.add(question.withFollowUpMemory(
          context,
          QuestionState.initial(context.dimensions())));
    }
    return enriched;
  }

  private static Set<String> focusTerms(InterviewQuestionDTO question) {
    Set<String> terms = new LinkedHashSet<>();
    addTerms(terms, question.type());
    addTerms(terms, question.category());
    addTerms(terms, question.topicSummary());
    addTerms(terms, question.question());
    return terms;
  }

  private static void addTerms(Set<String> terms, String value) {
    if (value == null || value.isBlank()) return;
    for (String term : TERM_SEPARATOR.split(value.toLowerCase(Locale.ROOT))) {
      if (term.length() >= 2) terms.add(term);
    }
  }

  private static List<String> relevantSegments(
      String source,
      Set<String> focusTerms,
      int limit) {
    if (source == null || source.isBlank()) return List.of();
    return SEGMENT_SEPARATOR.splitAsStream(source)
        .map(String::trim)
        .filter(segment -> !segment.isBlank())
        .map(segment -> new ScoredSegment(segment, relevance(segment, focusTerms)))
        .filter(segment -> segment.score() > 0)
        .sorted(Comparator.comparingInt(ScoredSegment::score).reversed())
        .map(ScoredSegment::value)
        .map(QuestionContextFactory::truncate)
        .distinct()
        .limit(limit)
        .toList();
  }

  private static int relevance(String segment, Set<String> focusTerms) {
    String normalized = segment.toLowerCase(Locale.ROOT);
    return (int) focusTerms.stream().filter(normalized::contains).count();
  }

  private static List<String> fallbackCapabilities(InterviewQuestionDTO question) {
    return List.of(question.topicSummary() == null || question.topicSummary().isBlank()
        ? question.category()
        : question.topicSummary());
  }

  private static String truncate(String value) {
    return value.length() <= MAX_SEGMENT_LENGTH
        ? value
        : value.substring(0, MAX_SEGMENT_LENGTH) + "…";
  }

  private record ScoredSegment(String value, int score) {
  }
}
