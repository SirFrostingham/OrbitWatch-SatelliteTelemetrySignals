package com.orbitwatch.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate for one (spatial cell, tick window): the objects present in that cell, flagged as
 * resident (their own cell) or ghost (published there because they are within the screening
 * threshold of the boundary). Small by construction — a 50 km cube holds a handful of objects.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class CellBucket {
    public List<Entry> entries = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(int id, String name, String type, String regime, double x, double y, double z, double alt, boolean resident) {}

    public CellBucket add(Entry e) {
        entries.add(e);
        return this;
    }
}
