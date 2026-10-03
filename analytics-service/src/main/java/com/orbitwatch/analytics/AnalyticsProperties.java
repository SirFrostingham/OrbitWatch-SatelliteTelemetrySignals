package com.orbitwatch.analytics;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the analytics topology. Every threshold here is an operational policy decision and
 * is documented in docs/ARCHITECTURE.md ("Analytics rules").
 */
@ConfigurationProperties(prefix = "orbitwatch.analytics")
public record AnalyticsProperties(
        Duration tickInterval,          // must match propagation tick (window size)
        Duration windowGrace,           // how long to wait for late vectors before closing a tick window
        Conjunction conjunction,
        Decay decay,
        Duration staleElementsAfter,    // element epoch age beyond which STALE_ELEMENTS fires
        Duration alertDedupeTtl,        // suppress repeat alerts with the same dedupe key for this long
        Maneuver maneuver
) {
    /**
     * {@code ignoreBelowKm}: pairs closer than this are docked vehicles or modules catalogued
     * under one element set (e.g. the ISS and its visiting spacecraft) — physically one object,
     * not a conjunction.
     */
    public record Conjunction(double thresholdKm, double cellKm, double criticalKm, double warningKm, double ignoreBelowKm) {}
    public record Decay(double warningPerigeeKm, double criticalPerigeeKm, double bstarWarning) {}
    public record Maneuver(double semiMajorAxisKm, double inclinationDeg, double eccentricityTimesA, Duration minSeparation) {}

    public static AnalyticsProperties defaults() {
        return new AnalyticsProperties(Duration.ofSeconds(10), Duration.ofSeconds(3),
                new Conjunction(10.0, 50.0, 1.0, 5.0, 0.01),
                new Decay(250.0, 180.0, 5e-3),
                Duration.ofDays(30), Duration.ofMinutes(30),
                new Maneuver(1.0, 0.02, 2.0, Duration.ofHours(1)));
    }
}
