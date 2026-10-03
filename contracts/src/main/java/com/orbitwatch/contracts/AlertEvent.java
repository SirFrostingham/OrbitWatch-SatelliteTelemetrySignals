package com.orbitwatch.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * An operator-facing alert, published to {@link Topics#ALERTS}. Alerts are facts about the
 * catalogue at a moment in time, not tickets: they are emitted whenever the condition is observed,
 * and de-duplicated / rate-limited by the analytics service using {@code dedupeKey}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertEvent(
        String alertId,
        AlertType type,
        Severity severity,
        Instant occurredAt,
        int primaryNoradId,
        String primaryName,
        Integer secondaryNoradId,
        String secondaryName,
        String title,
        Map<String, Object> details,
        String dedupeKey
) {
    public static final int SCHEMA_VERSION = 1;

    public enum AlertType {
        /** Two objects within the screening distance at this tick. */
        CONJUNCTION,
        /** Perigee altitude below the decay-watch floor, or very high drag term. */
        DECAY_WATCH,
        /** Element set epoch older than the staleness threshold. */
        STALE_ELEMENTS,
        /** NORAD id seen for the first time. */
        NEW_OBJECT,
        /** Successive element sets differ by more than natural perturbation explains. */
        MANEUVER_CANDIDATE,
        /** Propagation failed (decayed / non-physical elements). */
        PROPAGATION_FAILURE
    }

    public enum Severity { INFO, WARNING, CRITICAL }
}
