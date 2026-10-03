package com.orbitwatch.propagation;

import com.orbitwatch.contracts.CatalogObject;
import com.orbitwatch.contracts.GpElementSet;
import com.orbitwatch.contracts.Json;
import com.orbitwatch.contracts.Topics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka listeners that keep the {@link Catalog} current. Each instance uses a unique consumer group
 * and reads from the earliest offset: on a compacted topic that is exactly "load the table, then
 * follow changes". Batch mode so the initial 16k-record load takes well under a second.
 */
@Component
public class CatalogListeners {
    private static final Logger log = LoggerFactory.getLogger(CatalogListeners.class);
    private final Catalog catalog;

    public CatalogListeners(Catalog catalog) { this.catalog = catalog; }

    @KafkaListener(topics = Topics.GP_ELEMENTS, groupId = "propagation-elements-#{T(java.util.UUID).randomUUID()}",
            batch = "true", properties = {"auto.offset.reset=earliest", "max.poll.records=2000"})
    public void onElements(List<byte[]> batch) {
        for (byte[] b : batch) {
            if (b == null) continue;
            try {
                catalog.applyElements(Json.read(b, GpElementSet.class));
            } catch (RuntimeException ex) {
                log.warn("skipping malformed element set: {}", ex.getMessage());
            }
        }
        log.debug("applied {} element sets; catalog size {}", batch.size(), catalog.size());
    }

    @KafkaListener(topics = Topics.CATALOG_OBJECTS, groupId = "propagation-catalog-#{T(java.util.UUID).randomUUID()}",
            batch = "true", properties = {"auto.offset.reset=earliest", "max.poll.records=2000"})
    public void onCatalog(List<byte[]> batch) {
        for (byte[] b : batch) {
            if (b == null) continue;
            try {
                catalog.applyCatalog(Json.read(b, CatalogObject.class));
            } catch (RuntimeException ex) {
                log.warn("skipping malformed catalog row: {}", ex.getMessage());
            }
        }
    }
}
