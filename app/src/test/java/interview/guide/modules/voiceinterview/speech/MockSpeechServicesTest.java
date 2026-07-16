package interview.guide.modules.voiceinterview.speech;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Mock 语音服务")
class MockSpeechServicesTest {

  private VoiceInterviewProperties properties;

  @BeforeEach
  void setUp() {
    properties = new VoiceInterviewProperties();
    properties.getSpeech().getMock().setTranscript("模拟回答");
    properties.getSpeech().getMock().setSampleRate(1_000);
    properties.getSpeech().getMock().setEnergyThreshold(100);
    properties.getSpeech().getMock().setSilenceDurationMs(100);
  }

  @Nested
  @DisplayName("ASR")
  class Asr {

    @Test
    @DisplayName("检测到语音后在连续静音时返回定稿文本")
    void emitsPartialAndFinalTranscript() {
      MockSpeechRecognitionService service = new MockSpeechRecognitionService(properties);
      List<String> partials = new ArrayList<>();
      List<String> finals = new ArrayList<>();
      service.startTranscription("session-1", finals::add, partials::add, error -> { });

      service.sendAudio("session-1", pcm(1_000, 100));
      service.sendAudio("session-1", pcm(0, 100));

      assertThat(partials).containsExactly("模拟回答");
      assertThat(finals).containsExactly("模拟回答");
      assertThat(service.hasActiveSession("session-1")).isTrue();
    }
  }

  @Nested
  @DisplayName("TTS")
  class Tts {

    @Test
    @DisplayName("生成可播放的 PCM 测试音频")
    void generatesPcmAudio() {
      MockSpeechSynthesisService service = new MockSpeechSynthesisService(properties);

      SpeechAudio audio = service.synthesizeAudio("你好");

      assertThat(audio.data()).isNotEmpty();
      assertThat(audio.format()).isEqualTo("pcm");
      assertThat(audio.sampleRate()).isEqualTo(24_000);
    }
  }

  private byte[] pcm(int amplitude, int sampleCount) {
    byte[] bytes = new byte[sampleCount * 2];
    for (int index = 0; index < sampleCount; index++) {
      bytes[index * 2] = (byte) (amplitude & 0xff);
      bytes[index * 2 + 1] = (byte) ((amplitude >>> 8) & 0xff);
    }
    return bytes;
  }
}
