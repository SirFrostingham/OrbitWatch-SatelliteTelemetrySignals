package com.orbitwatch.contracts;

/**
 * Kafka topic catalogue. Naming: {@code ow.<domain>.<entity>.v<schema>}.
 *
 * <p>The version suffix is the schema version of the payload. A breaking change to a record means
 * a new topic (v2) with a bridge consumer, never an in-place mutation — consumers on v1 keep
 * working until they migrate. Additive, optional fields do not bump the version (Jackson is
 * configured to ignore unknown properties, so older consumers tolerate newer producers).
 *
 * <p>Compaction policy is part of the contract: compacted topics are "tables" (latest value per
 * key), delete-retention topics are "streams".
 */
public final class Topics {
    private Topics() {}

    /** Latest GP element set per object. Key: NORAD id. Compacted (a table). */
    public static final String GP_ELEMENTS = "ow.catalog.gp-elements.v1";

    /** Latest SATCAT row per object (owner, type, launch). Key: NORAD id. Compacted (a table). */
    public static final String CATALOG_OBJECTS = "ow.catalog.objects.v1";

    /** Propagated state vectors, one per object per tick. Key: NORAD id. Short retention (a stream). */
    public static final String STATE_VECTORS = "ow.telemetry.state-vectors.v1";

    /** Per-tick aggregate statistics. Key: tick ISO timestamp. Short retention. */
    public static final String TICK_STATS = "ow.analytics.tick-stats.v1";

    /** Operator alerts (conjunctions, decay watch, stale elements, new objects, manoeuvre candidates). Key: alert id. */
    public static final String ALERTS = "ow.analytics.alerts.v1";

    /** Ingest run summaries (what was fetched, how many changed). Key: run id. */
    public static final String INGEST_RUNS = "ow.ops.ingest-runs.v1";

    /** Internal repartition topic for spatial-cell keyed state vectors (analytics-owned). */
    public static final String STATE_VECTORS_BY_CELL = "ow.analytics.state-vectors-by-cell.v1";

    public static final int DEFAULT_PARTITIONS = 6;
}
