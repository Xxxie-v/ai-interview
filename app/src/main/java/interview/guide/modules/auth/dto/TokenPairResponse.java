package interview.guide.modules.auth.dto;

public record TokenPairResponse(
    String accessToken,
    String refreshToken,
    long expiresIn,
    CurrentUserDTO user
) {
}
