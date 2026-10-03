package com.orbitwatch.ingest;

import com.orbitwatch.contracts.CatalogObject;
import com.orbitwatch.contracts.GpElementSet;
import com.orbitwatch.contracts.Topics;
import com.orbitwatch.contracts.kafka.CompactedTopicLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Startup: warm the change detector from Kafka (so a restart does not republish the whole catalogue),
 * then run immediately. Afterwards, run on the configured schedule; the services themselves enforce
 * the provider's minimum interval via the cache, so the schedule can be shorter than the interval.
 */
@Component
public class IngestScheduler {
    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final GpIngestService gp;
    private final SatcatIngestService satcat;
    private final ElementChangeDetector detector;
    private final CompactedTopicLoader loader;
    private final IngestProperties props;

    public IngestScheduler(GpIngestService gp, SatcatIngestService satcat, ElementChangeDetector detector,
                           CompactedTopicLoader loader, IngestProperties props) {
        this.gp = gp;
        this.satcat = satcat;
        this.detector = detector;
        this.loader = loader;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            long n = loader.load(Topics.GP_ELEMENTS, GpElementSet.class, (k, v) -> detector.remember(v));
            long m = loader.load(Topics.CATALOG_OBJECTS, CatalogObject.class, (k, v) -> satcat.remember(v));
            log.info("warmed change detector with {} element sets and {} catalog rows", n, m);
        } catch (Exception e) {
            log.warn("could not warm from Kafka ({}); first run will publish the full catalogue", e.toString());
        }
        gp.markWarmed();
        scheduledRun();
    }

    @Scheduled(fixedDelayString = "${orbitwatch.ingest.schedule:PT15M}", initialDelayString = "${orbitwatch.ingest.schedule:PT15M}")
    public void scheduledRun() {
        gp.runOnce();
        if (props.satcat().enabled()) satcat.runOnce();
    }
}
