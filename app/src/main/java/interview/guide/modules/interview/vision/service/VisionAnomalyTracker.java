package interview.guide.modules.interview.vision.service;

import interview.guide.modules.interview.vision.model.VisionEventType;
import interview.guide.modules.interview.vision.model.VisionMonitoringState;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class VisionAnomalyTracker {

  private static final int CLEANUP_FREQUENCY = 100;
  private static final Set<VisionEventType> IMMEDIATE_EVENTS = Set.of(
      VisionEventType.CAMERA_INTERRUPTED);

  private final InterviewVisionProperties properties;
  private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
  private final AtomicInteger analysisCount = new AtomicInteger();

  public VisionAnomalyTracker(InterviewVisionProperties properties) {
    this.properties = properties;
  }

  public Decision track(
      String sessionId,
      List<VisionEventType> candidates,
      LocalDateTime capturedAt,
      Long videoOffsetMs) {
    if (analysisCount.incrementAndGet() % CLEANUP_FREQUENCY == 0) {
      cleanupExpired(capturedAt);
    }
    SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
    synchronized (state) {
      state.lastUpdatedAt = capturedAt;
      return state.update(candidates, capturedAt, videoOffsetMs);
    }
  }

  public void clear(String sessionId) {
    sessions.remove(sessionId);
  }

  private void cleanupExpired(LocalDateTime now) {
    LocalDateTime cutoff = now.minus(properties.getStateTtl());
    sessions.entrySet().removeIf(entry -> entry.getValue().lastUpdatedAt.isBefore(cutoff));
  }

  public record ConfirmedEvent(
      VisionEventType eventType,
      LocalDateTime startedAt,
      long durationMs,
      Long videoOffsetMs) {
  }

  public record Decision(
      VisionMonitoringState monitoringState,
      long recommendedIntervalMs,
      List<VisionEventType> activeEvents,
      List<ConfirmedEvent> newlyConfirmedEvents,
      EpisodeUpdate episodeUpdate) {
  }

  public record EpisodeUpdate(
      List<VisionEventType> eventTypes,
      LocalDateTime startedAt,
      LocalDateTime endedAt,
      long durationMs,
      Long videoOffsetMs,
      boolean closed) {
  }

  private final class SessionState {

    private final Map<VisionEventType, EventState> events =
        new EnumMap<>(VisionEventType.class);
    private LocalDateTime lastUpdatedAt = LocalDateTime.now();
    private EpisodeState episode;

    private Decision update(
        List<VisionEventType> candidates,
        LocalDateTime capturedAt,
        Long videoOffsetMs) {
      Set<VisionEventType> current = candidates == null
          ? Set.of()
          : Set.copyOf(candidates);
      List<ConfirmedEvent> newlyConfirmed = new ArrayList<>();

      current.forEach(eventType -> events
          .computeIfAbsent(eventType, ignored -> new EventState())
          .hit(eventType, capturedAt, videoOffsetMs, newlyConfirmed));

      events.forEach((eventType, eventState) -> {
        if (!current.contains(eventType)) {
          eventState.miss(capturedAt);
        }
      });
      events.entrySet().removeIf(entry -> entry.getValue().isInactive());

      List<VisionEventType> activeEvents = events.entrySet().stream()
          .filter(entry -> entry.getValue().confirmed)
          .map(Map.Entry::getKey)
          .toList();
      boolean suspect = !events.isEmpty();
      EpisodeUpdate episodeUpdate = updateEpisode(
          current,
          activeEvents,
          newlyConfirmed,
          capturedAt,
          suspect);
      VisionMonitoringState monitoringState = !activeEvents.isEmpty()
          ? VisionMonitoringState.CONFIRMED
          : suspect ? VisionMonitoringState.SUSPECT : VisionMonitoringState.NORMAL;
      long intervalMs = monitoringState == VisionMonitoringState.NORMAL
          ? properties.getFrameInterval().toMillis()
          : properties.getSuspectFrameInterval().toMillis();
      return new Decision(
          monitoringState,
          intervalMs,
          activeEvents,
          List.copyOf(newlyConfirmed),
          episodeUpdate);
    }

    private EpisodeUpdate updateEpisode(
        Set<VisionEventType> current,
        List<VisionEventType> activeEvents,
        List<ConfirmedEvent> newlyConfirmed,
        LocalDateTime capturedAt,
        boolean suspect) {
      if (!activeEvents.isEmpty()) {
        if (episode == null) {
          ConfirmedEvent first = newlyConfirmed.stream()
              .min((left, right) -> left.startedAt().compareTo(right.startedAt()))
              .orElseThrow();
          episode = new EpisodeState(first.startedAt(), first.videoOffsetMs());
        }
        boolean typesChanged = episode.eventTypes.addAll(activeEvents);
        boolean anomalyObserved = activeEvents.stream().anyMatch(current::contains);
        if (anomalyObserved) {
          episode.lastAnomalyAt = capturedAt;
        }
        if (anomalyObserved || !newlyConfirmed.isEmpty() || typesChanged) {
          return episode.snapshot(capturedAt, false);
        }
        return null;
      }
      if (episode != null && !suspect) {
        EpisodeUpdate closedEpisode = episode.snapshot(episode.lastAnomalyAt, true);
        episode = null;
        return closedEpisode;
      }
      return null;
    }
  }

  private static final class EpisodeState {

    private final LocalDateTime startedAt;
    private final Long videoOffsetMs;
    private final Set<VisionEventType> eventTypes = EnumSet.noneOf(VisionEventType.class);
    private LocalDateTime lastAnomalyAt;

    private EpisodeState(LocalDateTime startedAt, Long videoOffsetMs) {
      this.startedAt = startedAt;
      this.videoOffsetMs = videoOffsetMs;
      this.lastAnomalyAt = startedAt;
    }

    private EpisodeUpdate snapshot(LocalDateTime endedAt, boolean closed) {
      return new EpisodeUpdate(
          List.copyOf(eventTypes),
          startedAt,
          endedAt,
          Math.max(0, Duration.between(startedAt, endedAt).toMillis()),
          videoOffsetMs,
          closed);
    }
  }

  private final class EventState {

    private int consecutiveHits;
    private LocalDateTime firstSeenAt;
    private LocalDateTime recoveryStartedAt;
    private Long firstVideoOffsetMs;
    private boolean confirmed;

    private void hit(
        VisionEventType eventType,
        LocalDateTime capturedAt,
        Long videoOffsetMs,
        List<ConfirmedEvent> newlyConfirmed) {
      recoveryStartedAt = null;
      if (firstSeenAt == null) {
        firstSeenAt = capturedAt;
        firstVideoOffsetMs = videoOffsetMs;
      }
      consecutiveHits++;
      long durationMs = Math.max(0, Duration.between(firstSeenAt, capturedAt).toMillis());
      if (!confirmed && isConfirmed(eventType, durationMs)) {
        confirmed = true;
        newlyConfirmed.add(new ConfirmedEvent(
            eventType,
            firstSeenAt,
            durationMs,
            firstVideoOffsetMs));
      }
    }

    private void miss(LocalDateTime capturedAt) {
      if (!confirmed) {
        reset();
        return;
      }
      if (recoveryStartedAt == null) {
        recoveryStartedAt = capturedAt;
        return;
      }
      if (!Duration.between(recoveryStartedAt, capturedAt)
          .minus(properties.getRecoveryWindow()).isNegative()) {
        reset();
      }
    }

    private boolean isConfirmed(VisionEventType eventType, long durationMs) {
      if (IMMEDIATE_EVENTS.contains(eventType)) return true;
      return consecutiveHits >= properties.getConfirmationFrames()
          && durationMs >= minimumDuration(eventType).toMillis();
    }

    private Duration minimumDuration(VisionEventType eventType) {
      return switch (eventType) {
        case FACE_MISSING -> properties.getFaceMissingMinDuration();
        case MULTIPLE_FACES -> properties.getMultipleFacesMinDuration();
        default -> properties.getOtherEventMinDuration();
      };
    }

    private boolean isInactive() {
      return firstSeenAt == null;
    }

    private void reset() {
      consecutiveHits = 0;
      firstSeenAt = null;
      recoveryStartedAt = null;
      firstVideoOffsetMs = null;
      confirmed = false;
    }
  }
}
