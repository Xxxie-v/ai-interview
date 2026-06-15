package interview.guide.modules.auth.oauth;

public record OAuthToken(
    String accessToken,
    long expiresInSeconds,
    String identifierHint
) {
}
