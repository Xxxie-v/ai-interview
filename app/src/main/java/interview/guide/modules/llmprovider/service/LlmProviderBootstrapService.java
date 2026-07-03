package interview.guide.modules.llmprovider.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.interview.service.InterviewQuestionProperties;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

  private final LlmProviderProperties properties;
  private final LlmProviderRepository providerRepository;
  private final LlmGlobalSettingRepository globalSettingRepository;
  private final ApiKeyEncryptionService encryptionService;
  private final InterviewQuestionProperties interviewQuestionProperties;

  @PostConstruct
  @Transactional
  public void seedProvidersIfNecessary() {
    if (providerRepository.count() == 0) {
      seedProviders();
    } else {
      syncConfiguredDefaultProviders();
    }
    ensureGlobalSetting();
  }

  private void seedProviders() {
    Map<String, ProviderConfig> providers = properties.getProviders();
    if (providers == null || providers.isEmpty()) {
      log.warn("No app.ai.providers seed configuration found");
      return;
    }

    providers.forEach(this::seedProvider);
    log.info("Seeded {} LLM providers from application configuration", providerRepository.count());
  }

  private void syncConfiguredDefaultProviders() {
    Set<String> providerIds = new LinkedHashSet<>();
    providerIds.add(properties.getDefaultProvider());
    providerIds.add(properties.getDefaultEmbeddingProvider());
    if (properties.getManagedProviders() != null) {
      providerIds.addAll(properties.getManagedProviders());
    }
    providerIds.stream()
        .filter(id -> !isBlank(id))
        .forEach(this::syncConfiguredProvider);
  }

  private void syncConfiguredProvider(String providerId) {
    ProviderConfig config = properties.getProviders() != null
        ? properties.getProviders().get(providerId)
        : null;
    if (!isValidProviderConfig(providerId, config)) {
      log.warn("Skip invalid configured default provider: id={}", providerId);
      return;
    }
    providerRepository.findById(providerId).ifPresentOrElse(entity -> {
      entity.setBaseUrl(config.getBaseUrl());
      entity.setModel(config.getModel());
      entity.setEmbeddingModel(trimOrNull(config.getEmbeddingModel()));
      entity.setEmbeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()));
      entity.setSupportsEmbedding(Boolean.TRUE.equals(config.getSupportsEmbedding())
          || !isBlank(config.getEmbeddingModel()));
      entity.setTemperature(config.getTemperature());
      entity.setEnabled(true);
      entity.setBuiltin(true);
      if (!isBlank(config.getApiKey())) {
        ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(config.getApiKey());
        entity.setApiKeyNonce(encrypted.nonce());
        entity.setApiKeyCiphertext(encrypted.ciphertext());
      }
      providerRepository.save(entity);
      log.info("Synced configured LLM provider: {}", providerId);
    }, () -> seedProvider(providerId, config));
  }

  private void seedProvider(String id, ProviderConfig config) {
    if (!isValidProviderConfig(id, config)) {
      log.warn("Skip invalid provider seed: id={}", id);
      return;
    }
    ApiKeyEncryptionService.EncryptedValue encrypted =
        encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");
    boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
        || !isBlank(config.getEmbeddingModel());

    LlmProviderEntity entity = LlmProviderEntity.builder()
        .id(id)
        .baseUrl(config.getBaseUrl())
        .apiKeyNonce(encrypted.nonce())
        .apiKeyCiphertext(encrypted.ciphertext())
        .model(config.getModel())
        .embeddingModel(trimOrNull(config.getEmbeddingModel()))
        .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
        .supportsEmbedding(supportsEmbedding)
        .temperature(config.getTemperature())
        .enabled(true)
        .builtin(true)
        .build();
    providerRepository.save(entity);
    log.info("Seeded configured LLM provider: id={}, model={}", id, config.getModel());
  }

  private void ensureGlobalSetting() {
    LlmGlobalSettingEntity existing = globalSettingRepository
        .findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .orElse(null);
    if (existing != null) {
      migrateLegacyDefaults(existing);
      return;
    }
    String defaultChatProvider = resolveExistingProvider(
        properties.getDefaultProvider(),
        providerRepository.findAll().stream().findFirst().map(LlmProviderEntity::getId)
            .orElse("dashscope")
    );
    String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
        ? properties.getDefaultEmbeddingProvider()
        : defaultChatProvider;
    String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);
    String questionGenerationProvider = resolveExistingProvider(
        interviewQuestionProperties.getQuestionGenerationProvider(),
        defaultChatProvider);

    globalSettingRepository.save(LlmGlobalSettingEntity.builder()
        .id(LlmGlobalSettingEntity.SINGLETON_ID)
        .defaultChatProviderId(defaultChatProvider)
        .defaultEmbeddingProviderId(defaultEmbeddingProvider)
        .questionGenerationProviderId(questionGenerationProvider)
        .build());
    log.info(
        "Initialized LLM global setting: chatProvider={}, embeddingProvider={}, questionProvider={}",
        defaultChatProvider, defaultEmbeddingProvider, questionGenerationProvider);
  }

  private void migrateLegacyDefaults(LlmGlobalSettingEntity setting) {
    boolean changed = false;
    String configuredChatProvider = properties.getDefaultProvider();
    if (shouldMigrateDefault(setting.getDefaultChatProviderId(), configuredChatProvider)
        && providerRepository.existsById(configuredChatProvider)) {
      setting.setDefaultChatProviderId(configuredChatProvider);
      changed = true;
    }
    String configuredEmbeddingProvider = properties.getDefaultEmbeddingProvider();
    if (shouldMigrateDefault(setting.getDefaultEmbeddingProviderId(), configuredEmbeddingProvider)
        && providerRepository.findById(configuredEmbeddingProvider)
            .filter(this::canProvideEmbedding)
            .isPresent()) {
      setting.setDefaultEmbeddingProviderId(configuredEmbeddingProvider);
      changed = true;
    }
    String questionProvider = setting.getQuestionGenerationProviderId();
    if (isBlank(questionProvider) || !providerRepository.existsById(questionProvider)) {
      setting.setQuestionGenerationProviderId(resolveExistingProvider(
          interviewQuestionProperties.getQuestionGenerationProvider(),
          setting.getDefaultChatProviderId()));
      changed = true;
    }
    if (changed) {
      globalSettingRepository.save(setting);
      log.info("Migrated legacy LLM defaults: chatProvider={}, embeddingProvider={}",
          setting.getDefaultChatProviderId(), setting.getDefaultEmbeddingProviderId());
    }
  }

  private boolean shouldMigrateDefault(String currentProviderId, String configuredProviderId) {
    if (isBlank(configuredProviderId)
        || configuredProviderId.equalsIgnoreCase(currentProviderId)) {
      return false;
    }
    return "dashscope".equalsIgnoreCase(currentProviderId)
        || "siliconflow".equalsIgnoreCase(currentProviderId)
        || "default".equalsIgnoreCase(currentProviderId);
  }

  private boolean isValidProviderConfig(String id, ProviderConfig config) {
    return !isBlank(id) && config != null
        && !isBlank(config.getBaseUrl()) && !isBlank(config.getModel());
  }

  private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
    if (!isBlank(preferredProvider) && providerRepository.existsById(preferredProvider)) {
      return preferredProvider;
    }
    return fallbackProvider;
  }

  private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
    return providerRepository.findById(preferredProvider)
        .filter(this::canProvideEmbedding)
        .map(LlmProviderEntity::getId)
        .orElseGet(() -> providerRepository.findAll().stream()
            .filter(this::canProvideEmbedding)
            .findFirst()
            .map(LlmProviderEntity::getId)
            .orElse(fallbackProvider));
  }

  private boolean canProvideEmbedding(LlmProviderEntity provider) {
    return provider.isEnabled()
        && provider.isSupportsEmbedding()
        && !isBlank(provider.getEmbeddingModel());
  }

  private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
    if (configuredDimensions != null && configuredDimensions > 0) {
      return configuredDimensions;
    }
    return properties.getEmbeddingDimensions();
  }

  private String trimOrNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
