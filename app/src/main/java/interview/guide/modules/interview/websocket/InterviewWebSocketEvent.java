package interview.guide.modules.interview.websocket;

import java.time.OffsetDateTime;
import java.util.Map;

public record InterviewWebSocketEvent(
    String eventId,
    InterviewEventType type,
    String sessionId,
    long sequence,
    OffsetDateTime timestamp,
    Map<String, Object> payload
) {
}
