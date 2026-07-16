package interview.guide.modules.voiceinterview.speech;

import java.util.function.Consumer;

public interface SpeechRecognitionService {

  String providerId();

  default void startTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<Throwable> onError) {
    startTranscription(sessionId, onFinal, null, onError);
  }

  void startTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError);

  void restartTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError);

  void sendAudio(String sessionId, byte[] audioData);

  void stopTranscription(String sessionId);

  boolean hasActiveSession(String sessionId);
}
