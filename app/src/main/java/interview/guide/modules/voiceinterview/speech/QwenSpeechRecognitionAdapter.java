package interview.guide.modules.voiceinterview.speech;

import interview.guide.modules.voiceinterview.service.QwenAsrService;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QwenSpeechRecognitionAdapter implements SpeechRecognitionProvider {

  private final QwenAsrService delegate;

  @Override
  public String providerId() {
    return "qwen";
  }

  @Override
  public void startTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError) {
    delegate.startTranscription(sessionId, onFinal, onPartial, onError);
  }

  @Override
  public void restartTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError) {
    delegate.restartTranscription(sessionId, onFinal, onPartial, onError);
  }

  @Override
  public void sendAudio(String sessionId, byte[] audioData) {
    delegate.sendAudio(sessionId, audioData);
  }

  @Override
  public void stopTranscription(String sessionId) {
    delegate.stopTranscription(sessionId);
  }

  @Override
  public boolean hasActiveSession(String sessionId) {
    return delegate.hasActiveSession(sessionId);
  }
}
