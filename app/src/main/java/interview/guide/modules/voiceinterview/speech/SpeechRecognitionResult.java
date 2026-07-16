package interview.guide.modules.voiceinterview.speech;

public record SpeechRecognitionResult(
    String text,
    boolean finalResult,
    String language,
    Double confidence) {
}
