package interview.guide.modules.auth.oauth;

public record ThirdPartyUserInfo(
    String identifier,
    String unionId,
    String nickname,
    String avatarUrl
) {
}
