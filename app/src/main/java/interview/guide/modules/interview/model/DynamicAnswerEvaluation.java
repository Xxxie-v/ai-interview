package interview.guide.modules.interview.model;

public record DynamicAnswerEvaluation(
    NextAction nextAction,
    String nextQuestion,
    String reasoningSummary,
    QuestionState questionState
) {

  public DynamicAnswerEvaluation(
      NextAction nextAction,
      String nextQuestion,
      String reasoningSummary) {
    this(nextAction, nextQuestion, reasoningSummary, null);
  }
}
