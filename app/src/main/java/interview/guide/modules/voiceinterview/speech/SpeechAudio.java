package interview.guide.modules.voiceinterview.speech;

public record SpeechAudio(
    byte[] data,
    String format,
    String mimeType,
    int sampleRate) {

  public SpeechAudio {
    data = data == null ? new byte[0] : data.clone();
  }

  @Override
  public byte[] data() {
    return data.clone();
  }
}
