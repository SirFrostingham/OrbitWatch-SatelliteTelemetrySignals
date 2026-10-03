package com.orbitwatch.contracts.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a compacted topic from the beginning to its current end offsets and hands every record to
 * a consumer — i.e. materialises a Kafka "table" into local memory at startup. No consumer group is
 * used (manual assignment), so this never interferes with the service's live listeners.
 *
 * <p>This is how services rebuild state after a restart without a database: the compacted topic is
 * the system of record for "latest element set per object".
 */
public final class CompactedTopicLoader {
    private static final Logger log = LoggerFactory.getLogger(CompactedTopicLoader.class);

    private final String bootstrapServers;
    private final Map<String, Object> extraConsumerProps;

    public CompactedTopicLoader(String bootstrapServers, Map<String, Object> extraConsumerProps) {
        this.bootstrapServers = bootstrapServers;
        this.extraConsumerProps = extraConsumerProps == null ? Map.of() : extraConsumerProps;
    }

    /** @return number of records read */
    public <V> long load(String topic, Class<V> valueType, BiConsumer<String, V> sink) {
        Properties props = new Properties();
        props.putAll(extraConsumerProps);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "2000");
        props.put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, String.valueOf(50 * 1024 * 1024));
        props.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, String.valueOf(10 * 1024 * 1024));
        JsonSerde<V> serde = JsonSerde.of(valueType);
        long count = 0;
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic);
            if (infos == null || infos.isEmpty()) {
                log.info("topic {} has no partitions yet; nothing to load", topic);
                return 0;
            }
            List<TopicPartition> tps = new ArrayList<>();
            for (PartitionInfo pi : infos) tps.add(new TopicPartition(topic, pi.partition()));
            consumer.assign(tps);
            consumer.seekToBeginning(tps);
            Map<TopicPartition, Long> end = consumer.endOffsets(tps);
            Map<TopicPartition, Long> remaining = new HashMap<>();
            for (TopicPartition tp : tps) {
                long pos = consumer.position(tp);
                if (end.get(tp) > pos) remaining.put(tp, end.get(tp));
            }
            int idlePolls = 0;
            while (!remaining.isEmpty() && idlePolls < 20) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) { idlePolls++; continue; }
                idlePolls = 0;
                for (ConsumerRecord<String, byte[]> r : records) {
                    if (r.value() != null) {
                        sink.accept(r.key(), serde.deserializer().deserialize(topic, r.value()));
                        count++;
                    }
                    TopicPartition tp = new TopicPartition(r.topic(), r.partition());
                    Long target = remaining.get(tp);
                    if (target != null && r.offset() + 1 >= target) remaining.remove(tp);
                }
            }
        }
        log.info("loaded {} records from compacted topic {}", count, topic);
        return count;
    }
}
