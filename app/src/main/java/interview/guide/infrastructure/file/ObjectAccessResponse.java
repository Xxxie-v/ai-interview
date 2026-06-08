package interview.guide.infrastructure.file;

import java.time.Instant;

public record ObjectAccessResponse(
    String url,
    boolean direct,
    String mimeType,
    Instant expiresAt) {
}
