package com.orbitwatch.astro;

import static org.assertj.core.api.Assertions.assertThat;

import com.orbitwatch.astro.ConjunctionScreener.CloseApproach;
import com.orbitwatch.astro.ConjunctionScreener.Object3D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConjunctionScreenerTest {

    @Test
    void findsPairAcrossCellBoundary() {
        ConjunctionScreener s = new ConjunctionScreener(100, 25);
        // straddle the x=100 boundary, 4 km apart
        List<Object3D> objs = List.of(new Object3D(1, 98, 50, 50), new Object3D(2, 102, 50, 50), new Object3D(3, 500, 500, 500));
        List<CloseApproach> hits = s.screen(objs);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).idA()).isEqualTo(1);
        assertThat(hits.get(0).idB()).isEqualTo(2);
        assertThat(hits.get(0).distanceKm()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void gridScreenIsExactAgainstBruteForce() {
        Random rnd = new Random(42);
        List<Object3D> objs = new ArrayList<>();
        // Dense-ish shell to force many hits
        for (int i = 0; i < 4000; i++) {
            double r = 6800 + rnd.nextDouble() * 200;
            double th = rnd.nextDouble() * Math.PI, ph = rnd.nextDouble() * 2 * Math.PI;
            objs.add(new Object3D(i, r * Math.sin(th) * Math.cos(ph), r * Math.sin(th) * Math.sin(ph), r * Math.cos(th)));
        }
        double threshold = 60;
        ConjunctionScreener s = new ConjunctionScreener(60, threshold);
        Set<String> grid = new HashSet<>();
        for (CloseApproach c : s.screen(objs)) grid.add(c.idA() + "-" + c.idB());

        Set<String> brute = new HashSet<>();
        for (int i = 0; i < objs.size(); i++) for (int j = i + 1; j < objs.size(); j++) {
            Object3D a = objs.get(i), b = objs.get(j);
            double d = Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
            if (d < threshold) brute.add(Math.min(a.id(), b.id()) + "-" + Math.max(a.id(), b.id()));
        }
        assertThat(brute).isNotEmpty();
        assertThat(grid).isEqualTo(brute);
    }

    @Test
    void ghostCellsCoverEveryNeighbourWithinThreshold() {
        // Streaming variant: an object is published to its own cell plus neighbours within
        // threshold. Any pair closer than threshold must share at least one key, with at least one
        // of them "resident" (own cell) so the pair is reported exactly once.
        Random rnd = new Random(7);
        ConjunctionScreener s = new ConjunctionScreener(100, 25);
        for (int trial = 0; trial < 20000; trial++) {
            double ax = rnd.nextDouble() * 1000 - 500, ay = rnd.nextDouble() * 1000 - 500, az = rnd.nextDouble() * 1000 - 500;
            double d = rnd.nextDouble() * 25;
            double th = rnd.nextDouble() * Math.PI, ph = rnd.nextDouble() * 2 * Math.PI;
            double bx = ax + d * Math.sin(th) * Math.cos(ph), by = ay + d * Math.sin(th) * Math.sin(ph), bz = az + d * Math.cos(th);
            long ownA = ConjunctionScreener.cellKey(ax, ay, az, 100), ownB = ConjunctionScreener.cellKey(bx, by, bz, 100);
            List<Long> ga = s.ghostCells(ax, ay, az), gb = s.ghostCells(bx, by, bz);
            assertThat(ga).contains(ownA);
            assertThat(gb).contains(ownB);
            // B's ghosts must include A's own cell (so that in A's cell, B is present)
            assertThat(gb).as("trial " + trial).contains(ownA);
            assertThat(ga).as("trial " + trial).contains(ownB);
            assertThat(ga.size()).isLessThanOrEqualTo(8); // at most 2 per axis when threshold <= cell/2
        }
    }

    @Test
    void packUnpackRoundTripsNegativeCoordinates() {
        long[] c = ConjunctionScreener.unpack(ConjunctionScreener.pack(-3, 7, -1000));
        assertThat(c).containsExactly(-3, 7, -1000);
    }
}
