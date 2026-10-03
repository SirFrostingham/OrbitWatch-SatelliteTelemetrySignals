package com.orbitwatch.propagation;

import com.orbitwatch.astro.Frames;
import com.orbitwatch.astro.Sgp4;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * On-demand ground track (and orbit path) for one object: propagate from {@code from} over a span at
 * a fixed step. Independent SGP4 instance per request so the ticker's propagators are never shared
 * across threads.
 */
@Service
public class GroundTrackService {
    private final Catalog catalog;

    public GroundTrackService(Catalog catalog) { this.catalog = catalog; }

    public record Point(Instant t, double latDeg, double lonDeg, double altKm, double x, double y, double z) {}

    public record GroundTrack(int noradId, String name, String regime, Instant from, int stepSeconds, List<Point> points) {}

    public Optional<GroundTrack> compute(int noradId, Instant from, int spanMinutes, int stepSeconds) {
        return catalog.get(noradId).map(obj -> {
            var e = obj.elements();
            Sgp4 sat = Sgp4.fromOmm(Frames.daysSince1950(e.epoch()), e.meanMotion(), e.eccentricity(), e.inclination(),
                    e.raOfAscNode(), e.argOfPericenter(), e.meanAnomaly(), e.bstar());
            List<Point> pts = new ArrayList<>(spanMinutes * 60 / stepSeconds + 1);
            for (long s = 0; s <= (long) spanMinutes * 60; s += stepSeconds) {
                Instant t = from.plusSeconds(s);
                try {
                    Sgp4.StateVector sv = sat.propagate(Frames.minutesSince(e.epoch(), t));
                    Frames.Geodetic g = Frames.temeToGeodetic(sv.x(), sv.y(), sv.z(), t);
                    pts.add(new Point(t, g.latDeg(), g.lonDeg(), g.altKm(), sv.x(), sv.y(), sv.z()));
                } catch (Sgp4.PropagationException ex) {
                    break;
                }
            }
            return new GroundTrack(noradId, obj.name(), obj.regime().name(), from, stepSeconds, pts);
        });
    }
}
