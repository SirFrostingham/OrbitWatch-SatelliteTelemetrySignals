package com.orbitwatch.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** Summary of one ingest cycle, published to {@link Topics#INGEST_RUNS} for observability. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestRun(
        String runId,
        String source,
        Instant startedAt,
        Instant finishedAt,
        boolean fromCache,
        int fetched,
        int published,
        int unchanged,
        int newObjects,
        String error
) {
    public static final int SCHEMA_VERSION = 1;
}
