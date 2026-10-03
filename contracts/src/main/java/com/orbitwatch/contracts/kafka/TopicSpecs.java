package com.orbitwatch.contracts.kafka;

import static com.orbitwatch.contracts.Topics.*;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;

/**
 * Topic provisioning specs. Retention and compaction are part of the contract, so they live here
 * next to the topic names rather than in per-service YAML. Each service registers the specs for the
 * topics it *produces* (the owning service provisions its own outputs).
 */
public final class TopicSpecs {
    private TopicSpecs() {}

    private static final long HOUR = 3_600_000L;

    /** Table semantics: keep the latest record per key forever. */
    private static Map<String, String> compacted() {
        return Map.of(
                TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT,
                TopicConfig.MIN_CLEANABLE_DIRTY_RATIO_CONFIG, "0.2",
                TopicConfig.SEGMENT_MS_CONFIG, String.valueOf(6 * HOUR));
    }

    /** Stream semantics: time-bounded retention. */
    private static Map<String, String> stream(long retentionMs) {
        return Map.of(
                TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE,
                TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retentionMs));
    }

    public static NewTopic gpElements() {
        return new NewTopic(GP_ELEMENTS, DEFAULT_PARTITIONS, (short) 1).configs(compacted());
    }

    public static NewTopic catalogObjects() {
        return new NewTopic(CATALOG_OBJECTS, DEFAULT_PARTITIONS, (short) 1).configs(compacted());
    }

    public static NewTopic ingestRuns() {
        return new NewTopic(INGEST_RUNS, 1, (short) 1).configs(stream(7 * 24 * HOUR));
    }

    /** ~1.6k msgs/s at a 10 s tick over 16k objects: 1 hour is plenty for replay/debug. */
    public static NewTopic stateVectors() {
        return new NewTopic(STATE_VECTORS, DEFAULT_PARTITIONS, (short) 1).configs(stream(HOUR));
    }

    public static NewTopic stateVectorsByCell() {
        return new NewTopic(STATE_VECTORS_BY_CELL, DEFAULT_PARTITIONS, (short) 1).configs(stream(HOUR));
    }

    public static NewTopic tickStats() {
        return new NewTopic(TICK_STATS, 1, (short) 1).configs(stream(24 * HOUR));
    }

    public static NewTopic alerts() {
        return new NewTopic(ALERTS, DEFAULT_PARTITIONS, (short) 1).configs(stream(7 * 24 * HOUR));
    }

    public static List<NewTopic> all() {
        return List.of(gpElements(), catalogObjects(), ingestRuns(), stateVectors(), stateVectorsByCell(), tickStats(), alerts());
    }
}
