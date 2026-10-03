package com.orbitwatch.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * Aggregate statistics for one propagation tick, published to {@link Topics#TICK_STATS}.
 * Produced by the analytics service's windowed aggregation over {@link StateVectorEvent}s.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TickStats(
        Instant tick,
        long objectsPropagated,
        Map<String, Long> byRegime,
        Map<String, Long> byObjectType,
        Map<String, Long> byOwnerTop,
        double meanElementAgeHours,
        long staleElementCount,      // > 30 days
        long lowPerigeeCount,        // decay watch
        double minAltitudeKm,
        double maxAltitudeKm,
        long propagationFailures,
        Instant computedAt
) {
    public static final int SCHEMA_VERSION = 1;
}
