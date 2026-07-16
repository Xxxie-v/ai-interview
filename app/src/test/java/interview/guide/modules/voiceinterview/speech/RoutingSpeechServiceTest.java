package interview.guide.modules.voiceinterview.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("语音 Provider 路由")
class RoutingSpeechServiceTest {

  @Test
  @DisplayName("根据配置分别选择 ASR 与 TTS Provider")
  void routesConfiguredProviders() {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    properties.getSpeech().setAsrProvider("mock-asr");
    properties.getSpeech().setTtsProvider("mock-tts");
    SpeechRecognitionProvider asr = mock(SpeechRecognitionProvider.class);
    SpeechSynthesisProvider tts = mock(SpeechSynthesisProvider.class);
    when(asr.providerId()).thenReturn("mock-asr");
    when(tts.providerId()).thenReturn("mock-tts");
    when(tts.sampleRate()).thenReturn(24_000);
    RoutingSpeechService service = new RoutingSpeechService(
        properties, List.of(asr), List.of(tts));

    service.startTranscription("session-1", text -> { }, text -> { }, error -> { });

    verify(asr).startTranscription(
        org.mockito.ArgumentMatchers.eq("session-1"),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
    assertThat(service.sampleRate()).isEqualTo(24_000);
  }

  @Test
  @DisplayName("不支持的 Provider 返回业务异常")
  void rejectsUnsupportedProvider() {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    properties.getSpeech().setTtsProvider("missing");
    RoutingSpeechService service = new RoutingSpeechService(
        properties, List.of(), List.of());

    assertThatThrownBy(service::sampleRate)
        .isInstanceOf(BusinessException.class)
        .hasMessage("不支持的 TTS Provider: missing");
  }
}
