package interview.guide.modules.voiceinterview.speech;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class QwenSpeechSynthesisAdapter implements SpeechSynthesisProvider {

  private final QwenTtsService delegate;
  private final VoiceInterviewProperties properties;

  @Override
  public String providerId() {
    return "qwen";
  }

  @Override
  public SpeechAudio synthesizeAudio(String text) {
    return new SpeechAudio(delegate.synthesize(text), "pcm", "audio/pcm", sampleRate());
  }

  @Override
  public int sampleRate() {
    return properties.getQwen().getTts().getSampleRate();
  }
}
