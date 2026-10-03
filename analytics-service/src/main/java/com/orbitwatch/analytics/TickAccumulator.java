package com.orbitwatch.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable aggregate for one tick window. Serialised into the windowed state store (JSON) between
 * updates, so keep it small: a handful of count maps and scalars, never the vectors themselves.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TickAccumulator {
    public long count;
    public Map<String, Long> byRegime = new HashMap<>();
    public Map<String, Long> byType = new HashMap<>();
    public Map<String, Long> byOwner = new HashMap<>();
    public double ageSumHours;
    public long stale;
    public long lowPerigee;
    public double minAlt = Double.MAX_VALUE;
    public double maxAlt = -Double.MAX_VALUE;

    public TickAccumulator add(StateVectorEvent v, double staleHours, double lowPerigeeKm) {
        count++;
        byRegime.merge(nz(v.regime()), 1L, Long::sum);
        byType.merge(nz(v.objectType()), 1L, Long::sum);
        byOwner.merge(nz(v.owner()), 1L, Long::sum);
        ageSumHours += v.elementAgeHours();
        if (v.elementAgeHours() > staleHours) stale++;
        if (v.altKm() < lowPerigeeKm) lowPerigee++;
        if (v.altKm() < minAlt) minAlt = v.altKm();
        if (v.altKm() > maxAlt) maxAlt = v.altKm();
        return this;
    }

    private static String nz(String s) { return s == null ? "UNK" : s; }

    /** Merge another partial into this one (stage-2 of the two-level aggregation). */
    public TickAccumulator merge(TickAccumulator o) {
        count += o.count;
        o.byRegime.forEach((k, v) -> byRegime.merge(k, v, Long::sum));
        o.byType.forEach((k, v) -> byType.merge(k, v, Long::sum));
        o.byOwner.forEach((k, v) -> byOwner.merge(k, v, Long::sum));
        ageSumHours += o.ageSumHours;
        stale += o.stale;
        lowPerigee += o.lowPerigee;
        minAlt = Math.min(minAlt, o.minAlt);
        maxAlt = Math.max(maxAlt, o.maxAlt);
        return this;
    }

    public TickStats toStats(Instant tick, int topOwners) {
        Map<String, Long> top = new LinkedHashMap<>();
        byOwner.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(topOwners)
                .forEach(e -> top.put(e.getKey(), e.getValue()));
        return new TickStats(tick, count, Map.copyOf(byRegime), Map.copyOf(byType), top,
                count == 0 ? 0 : ageSumHours / count, stale, lowPerigee,
                count == 0 ? 0 : minAlt, count == 0 ? 0 : maxAlt, 0, Instant.now());
    }
}
