package com.orbitwatch.astro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class OrbitRegimeTest {

    @Test
    void issIsLeo() {
        assertThat(OrbitRegime.classify(15.49, 0.0004, 51.64)).isEqualTo(OrbitRegime.LEO);
        assertThat(OrbitRegime.perigeeAltKm(15.49, 0.0004)).isBetween(400.0, 430.0);
    }

    @Test
    void gpsIsMeo() {
        assertThat(OrbitRegime.classify(2.0056, 0.01, 55.0)).isEqualTo(OrbitRegime.MEO);
    }

    @Test
    void geostationaryIsGeo() {
        assertThat(OrbitRegime.classify(1.00272, 0.0002, 0.05)).isEqualTo(OrbitRegime.GEO);
        assertThat(OrbitRegime.periodMinutes(1.00272)).isCloseTo(1436.1, within(0.1));
    }

    @Test
    void molniyaIsHeo() {
        assertThat(OrbitRegime.classify(2.006, 0.72, 63.4)).isEqualTo(OrbitRegime.HEO);
    }

    @Test
    void gtoIsHeo() {
        assertThat(OrbitRegime.classify(2.3, 0.73, 27.0)).isEqualTo(OrbitRegime.HEO);
    }

    @Test
    void graveyardIsOther() {
        // ~300 km above GEO, drifting: period > 5% off sidereal day
        assertThat(OrbitRegime.classify(0.99, 0.0005, 5.0)).isEqualTo(OrbitRegime.OTHER);
    }

    @Test
    void inclinedGeosynchronousIsOther() {
        // Geosynchronous but 60° inclined (e.g. some SIGINT / Beidou IGSO)
        assertThat(OrbitRegime.classify(1.00272, 0.001, 60.0)).isEqualTo(OrbitRegime.OTHER);
    }
}
