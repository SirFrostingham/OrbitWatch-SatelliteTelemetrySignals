package com.orbitwatch.astro;

/**
 * Coarse orbital regime classification from mean elements — the vocabulary operators actually use
 * on a status board. Thresholds follow common SDA convention (ESA / 18 SDS style):
 *
 * <ul>
 *   <li>LEO: apogee &lt; 2,000 km</li>
 *   <li>MEO: 2,000 km ≤ perigee, apogee &lt; ~35,586 km (GEO belt lower edge)</li>
 *   <li>GEO: near-circular, period within ±0.5% of a sidereal day, inclination &lt; 25°</li>
 *   <li>HEO: eccentricity ≥ 0.25 with apogee reaching MEO/GEO altitudes (Molniya, GTO...)</li>
 *   <li>OTHER: anything not captured above (e.g. graveyard / super-GEO, lunar-distance)</li>
 * </ul>
 */
public enum OrbitRegime {
    LEO, MEO, GEO, HEO, OTHER;

    public static final double EARTH_RADIUS_KM = 6378.137;
    public static final double MU_KM3_S2 = 398600.4418;
    private static final double SIDEREAL_DAY_MIN = 1436.0683;

    /** Semi-major axis (km) from mean motion in rev/day. */
    public static double semiMajorAxisKm(double meanMotionRevPerDay) {
        double n = meanMotionRevPerDay * 2 * Math.PI / 86400.0; // rad/s
        return Math.cbrt(MU_KM3_S2 / (n * n));
    }

    public static double periodMinutes(double meanMotionRevPerDay) {
        return 1440.0 / meanMotionRevPerDay;
    }

    public static double perigeeAltKm(double meanMotionRevPerDay, double ecc) {
        return semiMajorAxisKm(meanMotionRevPerDay) * (1 - ecc) - EARTH_RADIUS_KM;
    }

    public static double apogeeAltKm(double meanMotionRevPerDay, double ecc) {
        return semiMajorAxisKm(meanMotionRevPerDay) * (1 + ecc) - EARTH_RADIUS_KM;
    }

    public static OrbitRegime classify(double meanMotionRevPerDay, double eccentricity, double inclinationDeg) {
        double period = periodMinutes(meanMotionRevPerDay);
        double perigee = perigeeAltKm(meanMotionRevPerDay, eccentricity);
        double apogee = apogeeAltKm(meanMotionRevPerDay, eccentricity);

        // ±0.5% of a sidereal day ≈ ±7 min ≈ ±200 km: keeps station-kept and slow-drifting GEO
        // objects, excludes the graveyard band (+235 km and up, IADC re-orbit rule)
        if (Math.abs(period - SIDEREAL_DAY_MIN) / SIDEREAL_DAY_MIN < 0.005 && eccentricity < 0.1 && inclinationDeg < 25) {
            return GEO;
        }
        if (eccentricity >= 0.25 && apogee >= 2000) {
            return HEO;
        }
        if (apogee < 2000) {
            return LEO;
        }
        if (perigee >= 2000 && apogee < 35_586) {
            return MEO;
        }
        return OTHER;
    }
}
