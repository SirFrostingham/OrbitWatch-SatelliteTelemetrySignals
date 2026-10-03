package com.orbitwatch.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * One General Perturbations (GP) mean element set for one object, as published to
 * {@link Topics#GP_ELEMENTS}. Field names mirror the CCSDS OMM / CelesTrak JSON so the mapping from
 * source is one-to-one and auditable. Units: degrees, rev/day, rev/day², rev/day³, 1/earth-radii.
 *
 * <p>{@code fingerprint} is a stable hash of the orbital fields — the ingest service only publishes
 * when it changes, turning a 2-hourly bulk poll into a true change stream.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GpElementSet(
        int noradId,
        String objectName,
        String objectId,          // international designator e.g. 1998-067A
        Instant epoch,
        double meanMotion,        // rev/day
        double eccentricity,
        double inclination,       // deg
        double raOfAscNode,       // deg
        double argOfPericenter,   // deg
        double meanAnomaly,       // deg
        double bstar,
        double meanMotionDot,
        double meanMotionDdot,
        int elementSetNo,
        int revAtEpoch,
        String classificationType,
        int ephemerisType,
        String source,            // e.g. "celestrak:active"
        Instant ingestedAt,
        String fingerprint
) {
    public static final int SCHEMA_VERSION = 1;
}
