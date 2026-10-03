package com.orbitwatch.astro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class FramesTest {

    @Test
    void julianDateOfJ2000() {
        // J2000.0 = 2000-01-01 12:00:00 TT ≈ UTC for this purpose
        assertThat(Frames.julianDate(Instant.parse("2000-01-01T12:00:00Z"))).isCloseTo(2451545.0, within(1e-9));
    }

    @Test
    void daysSince1950MatchesReferenceConvention() {
        // The reference Python package computes epoch as (EPOCH - 1949-12-31).total_seconds()/86400
        Instant epoch = Instant.parse("2026-09-09T20:27:22.665024Z");
        double expected = (epoch.getEpochSecond() + epoch.getNano() / 1e9 - Instant.parse("1949-12-31T00:00:00Z").getEpochSecond()) / 86400.0;
        assertThat(Frames.daysSince1950(epoch)).isCloseTo(expected, within(1e-9));
    }

    @Test
    void gmstKnownValue() {
        // Vallado example 3-5: 1992-08-20 12:14:00 UT1 -> GMST = 152.578787810 deg
        double jd = Frames.julianDate(Instant.parse("1992-08-20T12:14:00Z"));
        double gmstDeg = Math.toDegrees(Frames.gstime(jd));
        assertThat(gmstDeg).isCloseTo(152.578787810, within(1e-6));
    }

    @Test
    void geodeticRoundTripOnEquatorAndPole() {
        Frames.Geodetic eq = Frames.ecefToGeodetic(new Frames.Ecef(Frames.WGS84_A + 400, 0, 0));
        assertThat(eq.latDeg()).isCloseTo(0, within(1e-9));
        assertThat(eq.lonDeg()).isCloseTo(0, within(1e-9));
        assertThat(eq.altKm()).isCloseTo(400, within(1e-6));

        double b = Frames.WGS84_A * (1 - Frames.WGS84_F);
        Frames.Geodetic pole = Frames.ecefToGeodetic(new Frames.Ecef(0, 0, b + 500));
        assertThat(pole.latDeg()).isCloseTo(90, within(1e-6));
        assertThat(pole.altKm()).isCloseTo(500, within(1e-3));
    }

    @Test
    void geodeticSeattle() {
        // Seattle ≈ 47.6062 N, 122.3321 W, h=0 -> ECEF via forward formula, then invert
        double lat = Math.toRadians(47.6062), lon = Math.toRadians(-122.3321);
        double n = Frames.WGS84_A / Math.sqrt(1 - Frames.WGS84_E2 * Math.sin(lat) * Math.sin(lat));
        Frames.Ecef p = new Frames.Ecef(n * Math.cos(lat) * Math.cos(lon), n * Math.cos(lat) * Math.sin(lon),
                n * (1 - Frames.WGS84_E2) * Math.sin(lat));
        Frames.Geodetic g = Frames.ecefToGeodetic(p);
        assertThat(g.latDeg()).isCloseTo(47.6062, within(1e-7));
        assertThat(g.lonDeg()).isCloseTo(-122.3321, within(1e-7));
        assertThat(g.altKm()).isCloseTo(0, within(1e-6));
    }

    @Test
    void temeToEcefRotatesByGmst() {
        Instant at = Instant.parse("2026-09-10T00:00:00Z");
        double theta = Frames.gstime(Frames.julianDate(at));
        // A point on the TEME x-axis should land at ECEF longitude = -GMST
        Frames.Ecef e = Frames.temeToEcef(7000, 0, 0, at);
        double lon = Math.toDegrees(Math.atan2(e.y(), e.x()));
        double expected = -Math.toDegrees(theta);
        if (expected < -180) expected += 360;
        assertThat(lon).isCloseTo(expected, within(1e-9));
    }
}
