package interview.guide.modules.llmprovider.dto;

public record DefaultProviderDTO(
    String defaultProvider,
    String defaultEmbeddingProvider,
    String questionGenerationProvider
) {
    public DefaultProviderDTO(String defaultProvider) {
        this(defaultProvider, null, null);
    }

    public DefaultProviderDTO(String defaultProvider, String defaultEmbeddingProvider) {
        this(defaultProvider, defaultEmbeddingProvider, null);
    }
}
