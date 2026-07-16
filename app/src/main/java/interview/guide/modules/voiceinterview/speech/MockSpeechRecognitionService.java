package interview.guide.modules.voiceinterview.speech;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MockSpeechRecognitionService implements SpeechRecognitionProvider {

  private final VoiceInterviewProperties properties;
  private final Map<String, MockSession> sessions = new ConcurrentHashMap<>();

  @Override
  public String providerId() {
    return "mock";
  }

  @Override
  public void startTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError) {
    sessions.put(sessionId, new MockSession(onFinal, onPartial, onError));
  }

  @Override
  public void restartTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError) {
    stopTranscription(sessionId);
    startTranscription(sessionId, onFinal, onPartial, onError);
  }

  @Override
  public void sendAudio(String sessionId, byte[] audioData) {
    MockSession session = sessions.get(sessionId);
    if (session == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "Mock ASR 会话不存在");
    }
    if (audioData == null || audioData.length < 2) return;

    VoiceInterviewProperties.MockSpeechConfig config = properties.getSpeech().getMock();
    long energy = averageEnergy(audioData);
    if (energy >= config.getEnergyThreshold()) {
      session.speaking = true;
      session.silenceBytes = 0;
      if (!session.partialSent && session.onPartial != null) {
        session.onPartial.accept(config.getTranscript());
        session.partialSent = true;
      }
      return;
    }
    if (!session.speaking) return;

    session.silenceBytes += audioData.length;
    long requiredSilenceBytes = (long) config.getSampleRate()
        * 2
        * config.getSilenceDurationMs()
        / 1_000;
    if (session.silenceBytes >= requiredSilenceBytes) {
      session.onFinal.accept(config.getTranscript());
      session.speaking = false;
      session.partialSent = false;
      session.silenceBytes = 0;
    }
  }

  @Override
  public void stopTranscription(String sessionId) {
    sessions.remove(sessionId);
  }

  @Override
  public boolean hasActiveSession(String sessionId) {
    return sessions.containsKey(sessionId);
  }

  private long averageEnergy(byte[] audioData) {
    long total = 0;
    int samples = audioData.length / 2;
    for (int index = 0; index + 1 < audioData.length; index += 2) {
      short sample = (short) ((audioData[index] & 0xff) | (audioData[index + 1] << 8));
      total += Math.abs((int) sample);
    }
    return samples == 0 ? 0 : total / samples;
  }

  private static final class MockSession {
    private final Consumer<String> onFinal;
    private final Consumer<String> onPartial;
    @SuppressWarnings("unused")
    private final Consumer<Throwable> onError;
    private boolean speaking;
    private boolean partialSent;
    private long silenceBytes;

    private MockSession(
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Consumer<Throwable> onError) {
      this.onFinal = onFinal;
      this.onPartial = onPartial;
      this.onError = onError;
    }
  }
}
