package com.orbitwatch.astro;

import static java.lang.Math.PI;
import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;

import java.time.Instant;

/**
 * Reference-frame and time utilities.
 *
 * <p>SGP4 emits TEME. For display and for geometry between objects we rotate TEME into an
 * Earth-fixed frame (ECEF) using Greenwich Mean Sidereal Time and then convert to WGS-84 geodetic
 * latitude / longitude / altitude. Polar motion and the tiny TEME→PEF nuance are ignored — that is
 * a &lt;1 km effect, well inside SGP4's own accuracy envelope, and is the standard trade-off for a
 * situational-awareness display (as opposed to a precision conjunction product).
 */
public final class Frames {
    private Frames() {}

    private static final double TWOPI = 2.0 * PI;
    private static final double DEG2RAD = PI / 180.0;

    /** WGS-84 ellipsoid. */
    public static final double WGS84_A = 6378.137;           // km
    public static final double WGS84_F = 1.0 / 298.257223563;
    public static final double WGS84_E2 = WGS84_F * (2 - WGS84_F);

    /** Julian date of the SGP4 epoch origin (1949-12-31 00:00 UTC). */
    public static final double JD_1950_OFFSET = 2433281.5;
    /** Julian date of the Unix epoch (1970-01-01 00:00 UTC). */
    public static final double JD_UNIX_EPOCH = 2440587.5;

    /** Geodetic position. Longitude is normalised to [-180, 180). */
    public record Geodetic(double latDeg, double lonDeg, double altKm) {}

    /** Earth-fixed cartesian (km). */
    public record Ecef(double x, double y, double z) {}

    /** Julian date (UT) for an instant. */
    public static double julianDate(Instant instant) {
        double secs = instant.getEpochSecond() + instant.getNano() / 1e9;
        return JD_UNIX_EPOCH + secs / 86400.0;
    }

    /** Days since 1949-12-31 00:00 UTC — the epoch convention SGP4 initialisation expects. */
    public static double daysSince1950(Instant epoch) {
        return julianDate(epoch) - JD_1950_OFFSET;
    }

    /** Minutes elapsed from {@code epoch} to {@code now} (SGP4's "tsince"). */
    public static double minutesSince(Instant epoch, Instant now) {
        long nanos = now.getEpochSecond() - epoch.getEpochSecond();
        return nanos / 60.0 + (now.getNano() - epoch.getNano()) / 60e9;
    }

    /**
     * Greenwich Mean Sidereal Time (rad) for a Julian date (UT1 ≈ UTC here).
     * Vallado 2004, eq 3-45. Identical to the reference SGP4 gstime().
     */
    public static double gstime(double jdut1) {
        double tut1 = (jdut1 - 2451545.0) / 36525.0;
        double temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1
                + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841;
        temp = (temp * DEG2RAD / 240.0) % TWOPI;
        if (temp < 0.0) temp += TWOPI;
        return temp;
    }

    /** Rotate a TEME position into ECEF by the GMST angle for the given instant. */
    public static Ecef temeToEcef(double x, double y, double z, Instant at) {
        double theta = gstime(julianDate(at));
        double c = cos(theta), s = sin(theta);
        return new Ecef(c * x + s * y, -s * x + c * y, z);
    }

    /**
     * ECEF → WGS-84 geodetic using Bowring's iteration (converges in 2-3 passes for satellites).
     */
    public static Geodetic ecefToGeodetic(Ecef p) {
        double x = p.x(), y = p.y(), z = p.z();
        double lon = atan2(y, x);
        double r = sqrt(x * x + y * y);
        double lat = atan2(z, r * (1 - WGS84_E2));
        double alt = 0;
        for (int i = 0; i < 6; i++) {
            double sinLat = sin(lat), cosLat = cos(lat);
            double n = WGS84_A / sqrt(1 - WGS84_E2 * sinLat * sinLat);
            // height formula that is stable at the poles (no division by cos(lat))
            alt = r * cosLat + z * sinLat - WGS84_A * sqrt(1 - WGS84_E2 * sinLat * sinLat);
            double newLat = atan2(z, r * (1 - WGS84_E2 * n / (n + alt)));
            if (Math.abs(newLat - lat) < 1e-12) { lat = newLat; break; }
            lat = newLat;
        }
        double lonDeg = lon / DEG2RAD;
        if (lonDeg >= 180) lonDeg -= 360;
        if (lonDeg < -180) lonDeg += 360;
        return new Geodetic(lat / DEG2RAD, lonDeg, alt);
    }

    /** One-shot TEME → geodetic. */
    public static Geodetic temeToGeodetic(double x, double y, double z, Instant at) {
        return ecefToGeodetic(temeToEcef(x, y, z, at));
    }
}
