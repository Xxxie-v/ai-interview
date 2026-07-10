package interview.guide.modules.interview.service;

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InterviewQuestionProviderResolver {

  private final LlmGlobalSettingRepository globalSettingRepository;
  private final InterviewQuestionProperties properties;

  public String resolve() {
    return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
        .map(LlmGlobalSettingEntity::getQuestionGenerationProviderId)
        .filter(providerId -> !providerId.isBlank())
        .orElse(properties.getQuestionGenerationProvider());
  }
}
