package com.orbitwatch.astro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All-vs-all close-approach screening for one time step using a uniform spatial hash grid.
 *
 * <p>Naive screening of N objects is O(N²): at 16k objects that is ~130M distance checks per tick.
 * Bucketing positions into cubes of side {@code cellKm} and comparing only against the 26
 * neighbouring cells makes it O(N·k) where k is the (small) occupancy of a neighbourhood. With the
 * cell side ≥ the screening threshold, every pair closer than the threshold is guaranteed to land
 * in the same or an adjacent cell, so the screen is exact (no false negatives).
 *
 * <p>The same idea is what lets the streaming analytics service partition the work across Kafka
 * partitions: the cell index is the message key (see analytics-service ConjunctionTopology).
 *
 * <p>This is a *screening* step: it finds candidates in a single snapshot. A real conjunction
 * assessment would then propagate the candidate pair at fine time resolution to find the time of
 * closest approach and fold in covariance. The dashboard reports what this layer reports:
 * "these two objects are within X km right now".
 */
public final class ConjunctionScreener {

    public record Object3D(long id, double x, double y, double z) {}

    public record CloseApproach(long idA, long idB, double distanceKm) {}

    private final double cellKm;
    private final double thresholdKm;

    public ConjunctionScreener(double cellKm, double thresholdKm) {
        if (cellKm < thresholdKm) throw new IllegalArgumentException("cellKm must be >= thresholdKm for an exact screen");
        this.cellKm = cellKm;
        this.thresholdKm = thresholdKm;
    }

    /** Integer cell coordinate triple packed into one long (21 bits per axis, signed). */
    public static long cellKey(double x, double y, double z, double cellKm) {
        long cx = (long) Math.floor(x / cellKm);
        long cy = (long) Math.floor(y / cellKm);
        long cz = (long) Math.floor(z / cellKm);
        return pack(cx, cy, cz);
    }

    static long pack(long cx, long cy, long cz) {
        return ((cx & 0x1FFFFFL) << 42) | ((cy & 0x1FFFFFL) << 21) | (cz & 0x1FFFFFL);
    }

    static long[] unpack(long key) {
        long cx = signExtend21(key >>> 42);
        long cy = signExtend21((key >>> 21) & 0x1FFFFFL);
        long cz = signExtend21(key & 0x1FFFFFL);
        return new long[]{cx, cy, cz};
    }

    private static long signExtend21(long v) {
        return (v & 0x100000L) != 0 ? v | ~0x1FFFFFL : v;
    }

    /**
     * Cells that an object must be published to so that a neighbour-only (same-cell) comparison is
     * exact: its own cell plus every adjacent cell whose boundary is within the threshold.
     * Typically 1–4 keys rather than the full 27. Used by the streaming implementation.
     */
    public List<Long> ghostCells(double x, double y, double z) {
        long cx = (long) Math.floor(x / cellKm);
        long cy = (long) Math.floor(y / cellKm);
        long cz = (long) Math.floor(z / cellKm);
        int[] dxs = axisOffsets(x, cx);
        int[] dys = axisOffsets(y, cy);
        int[] dzs = axisOffsets(z, cz);
        List<Long> keys = new ArrayList<>(dxs.length * dys.length * dzs.length);
        for (int dx : dxs) for (int dy : dys) for (int dz : dzs) keys.add(pack(cx + dx, cy + dy, cz + dz));
        return keys;
    }

    private int[] axisOffsets(double v, long c) {
        double lo = c * cellKm;
        double fracLo = v - lo;
        double fracHi = cellKm - fracLo;
        boolean nearLo = fracLo < thresholdKm;
        boolean nearHi = fracHi < thresholdKm;
        if (nearLo && nearHi) return new int[]{-1, 0, 1};
        if (nearLo) return new int[]{-1, 0};
        if (nearHi) return new int[]{0, 1};
        return new int[]{0};
    }

    /** Screen a full snapshot in memory. */
    public List<CloseApproach> screen(List<Object3D> objects) {
        Map<Long, List<Object3D>> grid = new HashMap<>(objects.size() * 2);
        for (Object3D o : objects) {
            grid.computeIfAbsent(cellKey(o.x(), o.y(), o.z(), cellKm), k -> new ArrayList<>(4)).add(o);
        }
        List<CloseApproach> out = new ArrayList<>();
        double t2 = thresholdKm * thresholdKm;
        for (Map.Entry<Long, List<Object3D>> e : grid.entrySet()) {
            long[] c = unpack(e.getKey());
            List<Object3D> here = e.getValue();
            // within-cell pairs
            for (int i = 0; i < here.size(); i++)
                for (int j = i + 1; j < here.size(); j++) check(here.get(i), here.get(j), t2, out);
            // neighbour cells: only visit "greater" neighbours so each pair is checked once
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                if (dx < 0 || (dx == 0 && dy < 0) || (dx == 0 && dy == 0 && dz < 0)) continue;
                List<Object3D> there = grid.get(pack(c[0] + dx, c[1] + dy, c[2] + dz));
                if (there == null) continue;
                for (Object3D a : here) for (Object3D b : there) check(a, b, t2, out);
            }
        }
        return out;
    }

    /** Pairwise screen within one bucket (used by the streaming implementation per cell). */
    public List<CloseApproach> screenBucket(List<Object3D> bucket) {
        List<CloseApproach> out = new ArrayList<>();
        double t2 = thresholdKm * thresholdKm;
        for (int i = 0; i < bucket.size(); i++)
            for (int j = i + 1; j < bucket.size(); j++) check(bucket.get(i), bucket.get(j), t2, out);
        return out;
    }

    private static void check(Object3D a, Object3D b, double t2, List<CloseApproach> out) {
        if (a.id() == b.id()) return;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        double d2 = dx * dx + dy * dy + dz * dz;
        if (d2 < t2) {
            long lo = Math.min(a.id(), b.id()), hi = Math.max(a.id(), b.id());
            out.add(new CloseApproach(lo, hi, Math.sqrt(d2)));
        }
    }
}
