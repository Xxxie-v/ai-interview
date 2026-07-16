package interview.guide.modules.voiceinterview.speech;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MockSpeechSynthesisService implements SpeechSynthesisProvider {

  private final VoiceInterviewProperties properties;

  @Override
  public String providerId() {
    return "mock";
  }

  @Override
  public SpeechAudio synthesizeAudio(String text) {
    if (text == null || text.isBlank()) {
      return new SpeechAudio(new byte[0], "pcm", "audio/pcm", sampleRate());
    }
    int durationMs = Math.min(2_000, Math.max(350, text.length() * 45));
    int sampleCount = sampleRate() * durationMs / 1_000;
    byte[] pcm = new byte[sampleCount * 2];
    for (int index = 0; index < sampleCount; index++) {
      double envelope = Math.min(1.0, index / (sampleRate() * 0.03));
      short sample = (short) (Math.sin(2 * Math.PI * 440 * index / sampleRate())
          * 1_500
          * envelope);
      pcm[index * 2] = (byte) (sample & 0xff);
      pcm[index * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
    }
    return new SpeechAudio(pcm, "pcm", "audio/pcm", sampleRate());
  }

  @Override
  public int sampleRate() {
    return properties.getSpeech().getMock().getTtsSampleRate();
  }
}
