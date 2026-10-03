package com.orbitwatch.ingest;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Ingest configuration. CelesTrak's published usage policy asks clients not to fetch the same GP
 * data more than once every two hours; {@code gp.minInterval} enforces that regardless of the
 * schedule, and the on-disk cache means restarts never re-fetch inside that window.
 *
 * <p>{@code seedDir} is the last resort: a directory of snapshots committed with the repository,
 * used only when a fetch fails and there is no cache at all (see {@link SnapshotSeed}).
 */
@Validated
@ConfigurationProperties(prefix = "orbitwatch.ingest")
public record IngestProperties(Gp gp, Satcat satcat, @NotBlank String cacheDir, String seedDir) {

    public record Gp(
            @NotBlank String baseUrl,             // https://celestrak.org/NORAD/elements/gp.php
            List<String> groups,                  // e.g. [active]
            Duration minInterval,                 // 2h per CelesTrak policy
            String replayFile                     // optional: read from file instead of network
    ) {}

    public record Satcat(
            boolean enabled,
            @NotBlank String url,                 // https://celestrak.org/pub/satcat.csv
            Duration minInterval,                 // 24h
            String replayFile
    ) {}
}
