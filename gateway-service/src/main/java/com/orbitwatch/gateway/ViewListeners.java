package com.orbitwatch.gateway;

import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.IngestRun;
import com.orbitwatch.contracts.Json;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import com.orbitwatch.contracts.Topics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds {@link LiveViews}. Each gateway instance has its own consumer group (it is a cache, not a
 * work queue): state vectors from the latest offset (only "now" matters), alerts / stats / ingest
 * runs from the earliest retained offset so a freshly started gateway shows recent history.
 */
@Component
public class ViewListeners {
    private static final Logger log = LoggerFactory.getLogger(ViewListeners.class);
    private final LiveViews views;

    public ViewListeners(LiveViews views) { this.views = views; }

    @KafkaListener(topics = Topics.STATE_VECTORS, groupId = "gateway-vectors-#{T(java.util.UUID).randomUUID()}",
            batch = "true", concurrency = "2", properties = {"auto.offset.reset=latest", "max.poll.records=5000"})
    public void onVectors(List<byte[]> batch) {
        for (byte[] b : batch) if (b != null) safe(() -> views.onVector(Json.read(b, StateVectorEvent.class)));
    }

    @KafkaListener(topics = Topics.TICK_STATS, groupId = "gateway-stats-#{T(java.util.UUID).randomUUID()}",
            batch = "true", properties = {"auto.offset.reset=earliest"})
    public void onStats(List<byte[]> batch) {
        for (byte[] b : batch) if (b != null) safe(() -> views.onStats(Json.read(b, TickStats.class)));
    }

    @KafkaListener(topics = Topics.ALERTS, groupId = "gateway-alerts-#{T(java.util.UUID).randomUUID()}",
            batch = "true", properties = {"auto.offset.reset=earliest"})
    public void onAlerts(List<byte[]> batch) {
        for (byte[] b : batch) if (b != null) safe(() -> views.onAlert(Json.read(b, AlertEvent.class)));
    }

    @KafkaListener(topics = Topics.INGEST_RUNS, groupId = "gateway-ingest-#{T(java.util.UUID).randomUUID()}",
            batch = "true", properties = {"auto.offset.reset=earliest"})
    public void onIngestRuns(List<byte[]> batch) {
        for (byte[] b : batch) if (b != null) safe(() -> views.onIngestRun(Json.read(b, IngestRun.class)));
    }

    private static void safe(Runnable r) {
        try { r.run(); } catch (RuntimeException e) { log.warn("dropping malformed record: {}", e.getMessage()); }
    }
}
