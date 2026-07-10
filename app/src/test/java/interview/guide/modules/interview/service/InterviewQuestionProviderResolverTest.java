package interview.guide.modules.interview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试出题 Provider 解析")
class InterviewQuestionProviderResolverTest {

  @Mock
  private LlmGlobalSettingRepository globalSettingRepository;

  private InterviewQuestionProperties properties;
  private InterviewQuestionProviderResolver resolver;

  @BeforeEach
  void setUp() {
    properties = new InterviewQuestionProperties();
    resolver = new InterviewQuestionProviderResolver(globalSettingRepository, properties);
  }

  @Test
  @DisplayName("优先使用管理员保存的出题 Provider")
  void resolvesPersistedProvider() {
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.of(LlmGlobalSettingEntity.builder()
            .id(LlmGlobalSettingEntity.SINGLETON_ID)
            .questionGenerationProviderId("custom-question-provider")
            .build()));

    assertThat(resolver.resolve()).isEqualTo("custom-question-provider");
  }

  @Test
  @DisplayName("未配置时回退到环境默认 Provider")
  void fallsBackToConfiguredDefault() {
    when(globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID))
        .thenReturn(Optional.empty());

    assertThat(resolver.resolve()).isEqualTo("dashscope-question");
  }
}
