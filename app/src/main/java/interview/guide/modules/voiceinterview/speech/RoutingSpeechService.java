package interview.guide.modules.voiceinterview.speech;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Slf4j
@Primary
@Service
public class RoutingSpeechService
    implements SpeechRecognitionService, SpeechSynthesisService {

  private final VoiceInterviewProperties properties;
  private final Map<String, SpeechRecognitionProvider> recognitionProviders;
  private final Map<String, SpeechSynthesisProvider> synthesisProviders;
  private final Map<String, SpeechRecognitionProvider> activeRecognitionProviders =
      new ConcurrentHashMap<>();

  public RoutingSpeechService(
      VoiceInterviewProperties properties,
      List<SpeechRecognitionProvider> recognitionProviders,
      List<SpeechSynthesisProvider> synthesisProviders) {
    this.properties = properties;
    this.recognitionProviders = indexRecognitionProviders(recognitionProviders);
    this.synthesisProviders = indexSynthesisProviders(synthesisProviders);
    log.info(
        "Speech providers ready: configuredAsr={}, configuredTts={}, availableAsr={}, availableTts={}",
        properties.getSpeech().getAsrProvider(),
        properties.getSpeech().getTtsProvider(),
        this.recognitionProviders.keySet(),
        this.synthesisProviders.keySet());
  }

  @Override
  public String providerId() {
    return "routing";
  }

  @Override
  public void startTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError) {
    SpeechRecognitionProvider provider = recognitionProvider();
    provider.startTranscription(sessionId, onFinal, onPartial, onError);
    activeRecognitionProviders.put(sessionId, provider);
  }

  @Override
  public void restartTranscription(
      String sessionId,
      Consumer<String> onFinal,
      Consumer<String> onPartial,
      Consumer<Throwable> onError) {
    SpeechRecognitionProvider provider = activeRecognitionProviders.get(sessionId);
    if (provider == null) {
      provider = recognitionProvider();
    }
    provider.restartTranscription(sessionId, onFinal, onPartial, onError);
    activeRecognitionProviders.put(sessionId, provider);
  }

  @Override
  public void sendAudio(String sessionId, byte[] audioData) {
    activeRecognitionProvider(sessionId).sendAudio(sessionId, audioData);
  }

  @Override
  public void stopTranscription(String sessionId) {
    SpeechRecognitionProvider provider = activeRecognitionProviders.remove(sessionId);
    if (provider != null) {
      provider.stopTranscription(sessionId);
    }
  }

  @Override
  public boolean hasActiveSession(String sessionId) {
    SpeechRecognitionProvider provider = activeRecognitionProviders.get(sessionId);
    return provider != null && provider.hasActiveSession(sessionId);
  }

  @Override
  public SpeechAudio synthesizeAudio(String text) {
    return synthesisProvider().synthesizeAudio(text);
  }

  @Override
  public int sampleRate() {
    return synthesisProvider().sampleRate();
  }

  private SpeechRecognitionProvider recognitionProvider() {
    String providerId = normalize(properties.getSpeech().getAsrProvider());
    SpeechRecognitionProvider provider = recognitionProviders.get(providerId);
    if (provider == null) {
      throw unsupportedProvider("ASR", providerId);
    }
    return provider;
  }

  private SpeechRecognitionProvider activeRecognitionProvider(String sessionId) {
    SpeechRecognitionProvider provider = activeRecognitionProviders.get(sessionId);
    if (provider == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "语音识别会话尚未启动");
    }
    return provider;
  }

  private SpeechSynthesisProvider synthesisProvider() {
    String providerId = normalize(properties.getSpeech().getTtsProvider());
    SpeechSynthesisProvider provider = synthesisProviders.get(providerId);
    if (provider == null) {
      throw unsupportedProvider("TTS", providerId);
    }
    return provider;
  }

  private Map<String, SpeechRecognitionProvider> indexRecognitionProviders(
      List<SpeechRecognitionProvider> providers) {
    Map<String, SpeechRecognitionProvider> indexed = new LinkedHashMap<>();
    for (SpeechRecognitionProvider provider : providers) {
      indexed.put(normalize(provider.providerId()), provider);
    }
    return Map.copyOf(indexed);
  }

  private Map<String, SpeechSynthesisProvider> indexSynthesisProviders(
      List<SpeechSynthesisProvider> providers) {
    Map<String, SpeechSynthesisProvider> indexed = new LinkedHashMap<>();
    for (SpeechSynthesisProvider provider : providers) {
      indexed.put(normalize(provider.providerId()), provider);
    }
    return Map.copyOf(indexed);
  }

  private String normalize(String providerId) {
    return providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
  }

  private BusinessException unsupportedProvider(String capability, String providerId) {
    return new BusinessException(
        ErrorCode.VOICE_CONFIG_READ_FAILED,
        "不支持的 " + capability + " Provider: " + providerId);
  }
}
