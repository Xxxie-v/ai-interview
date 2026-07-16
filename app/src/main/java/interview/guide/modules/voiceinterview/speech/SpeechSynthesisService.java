package interview.guide.modules.voiceinterview.speech;

public interface SpeechSynthesisService {

  String providerId();

  SpeechAudio synthesizeAudio(String text);

  int sampleRate();

  default byte[] synthesize(String text) {
    return synthesizeAudio(text).data();
  }
}
