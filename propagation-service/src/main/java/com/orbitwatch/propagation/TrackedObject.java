package com.orbitwatch.propagation;

import com.orbitwatch.astro.Frames;
import com.orbitwatch.astro.OrbitRegime;
import com.orbitwatch.astro.Sgp4;
import com.orbitwatch.contracts.CatalogObject;
import com.orbitwatch.contracts.GpElementSet;

/**
 * One catalogue entry: the latest element set, optional SATCAT metadata, the derived regime, and a
 * lazily-initialised SGP4 instance. Immutable except for the propagator cache; a new element set
 * yields a new TrackedObject (see {@link Catalog}).
 */
public final class TrackedObject {
    private final GpElementSet elements;
    private final CatalogObject catalog;
    private final OrbitRegime regime;
    private volatile Sgp4 propagator;
    private volatile boolean failed;

    public TrackedObject(GpElementSet elements, CatalogObject catalog) {
        this.elements = elements;
        this.catalog = catalog;
        this.regime = OrbitRegime.classify(elements.meanMotion(), elements.eccentricity(), elements.inclination());
    }

    public TrackedObject withCatalog(CatalogObject c) {
        TrackedObject t = new TrackedObject(elements, c);
        t.propagator = this.propagator;
        t.failed = this.failed;
        return t;
    }

    public GpElementSet elements() { return elements; }
    public CatalogObject catalog() { return catalog; }
    public OrbitRegime regime() { return regime; }
    public int noradId() { return elements.noradId(); }
    public boolean hasFailed() { return failed; }
    public void markFailed() { failed = true; }

    public String owner() { return catalog == null ? "UNK" : nz(catalog.owner(), "UNK"); }
    public String objectType() { return catalog == null ? "UNK" : nz(catalog.objectType(), "UNK"); }
    public String name() { return elements.objectName() != null ? elements.objectName() : (catalog != null ? catalog.objectName() : "NORAD " + noradId()); }

    private static String nz(String s, String d) { return s == null || s.isBlank() ? d : s; }

    /** Build (once) the SGP4 propagator for this element set. */
    public Sgp4 propagator() {
        Sgp4 p = propagator;
        if (p == null) {
            synchronized (this) {
                p = propagator;
                if (p == null) {
                    GpElementSet e = elements;
                    p = Sgp4.fromOmm(Frames.daysSince1950(e.epoch()), e.meanMotion(), e.eccentricity(), e.inclination(),
                            e.raOfAscNode(), e.argOfPericenter(), e.meanAnomaly(), e.bstar());
                    propagator = p;
                }
            }
        }
        return p;
    }
}
