package com.orbitwatch.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * A propagated position/velocity for one object at one tick, published to
 * {@link Topics#STATE_VECTORS}. Cartesian values are TEME km and km/s (SGP4's native frame);
 * geodetic values are WGS-84. Denormalised catalogue fields (name, owner, type, regime) are carried
 * so downstream consumers and the UI do not need a join.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StateVectorEvent(
        int noradId,
        String objectName,
        String objectType,
        String owner,
        String regime,
        Instant tick,
        Instant elementEpoch,
        double elementAgeHours,
        double x, double y, double z,
        double vx, double vy, double vz,
        double latDeg, double lonDeg, double altKm,
        double speedKmS
) {
    public static final int SCHEMA_VERSION = 1;
}
