package interview.guide.modules.interview.websocket;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewEventStreamService {

  private static final String EVENT_KEY_PREFIX = "interview:events:";

  private final RedisService redisService;
  private final ObjectMapper objectMapper;
  private final InterviewEventProperties properties;

  public InterviewWebSocketEvent publish(
      String sessionId,
      InterviewEventType type,
      Map<String, Object> payload) {
    String eventKey = eventKey(sessionId);
    RAtomicLong sequence = redisService.getClient().getAtomicLong(eventKey + ":sequence");
    long nextSequence = sequence.incrementAndGet();
    sequence.expire(properties.getRetention());
    InterviewWebSocketEvent event = new InterviewWebSocketEvent(
        UUID.randomUUID().toString(),
        type,
        sessionId,
        nextSequence,
        OffsetDateTime.now(),
        payload == null ? Map.of() : Map.copyOf(payload));
    try {
      RList<String> events = redisService.getClient().getList(eventKey, StringCodec.INSTANCE);
      events.add(objectMapper.writeValueAsString(event));
      int overflow = events.size() - Math.max(1, properties.getMaxEventsPerSession());
      if (overflow > 0) {
        events.trim(overflow, -1);
      }
      events.expire(properties.getRetention());
      return event;
    } catch (Exception e) {
      log.error("Interview event persistence failed: sessionId={}, type={}", sessionId, type, e);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存面试实时事件失败");
    }
  }

  public List<InterviewWebSocketEvent> readAfter(String sessionId, long lastSequence) {
    RList<String> events = redisService.getClient().getList(
        eventKey(sessionId),
        StringCodec.INSTANCE);
    return events.readAll().stream()
        .map(this::readEvent)
        .filter(event -> event.sequence() > Math.max(0, lastSequence))
        .toList();
  }

  public boolean markClientEvent(String sessionId, String eventId) {
    if (eventId == null || eventId.isBlank() || eventId.length() > 100) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的 WebSocket eventId");
    }
    RBucket<String> marker = redisService.getClient().getBucket(
        eventKey(sessionId) + ":client:" + eventId,
        StringCodec.INSTANCE);
    return marker.setIfAbsent("1", properties.getRetention());
  }

  private InterviewWebSocketEvent readEvent(String json) {
    try {
      return objectMapper.readValue(json, InterviewWebSocketEvent.class);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取面试实时事件失败");
    }
  }

  private String eventKey(String sessionId) {
    return EVENT_KEY_PREFIX + "{" + sessionId + "}";
  }
}
