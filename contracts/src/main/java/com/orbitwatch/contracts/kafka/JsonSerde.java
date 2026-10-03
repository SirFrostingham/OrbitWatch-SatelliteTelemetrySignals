package com.orbitwatch.contracts.kafka;

import com.orbitwatch.contracts.Json;
import java.util.Map;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Kafka Serde for contract records using the shared {@link Json} mapper. Used by both the plain
 * producer/consumer services and the Kafka Streams topology so every service agrees byte-for-byte
 * on the wire format.
 */
public final class JsonSerde<T> implements Serde<T> {
    private final Class<T> type;

    public JsonSerde(Class<T> type) { this.type = type; }

    public static <T> JsonSerde<T> of(Class<T> type) { return new JsonSerde<>(type); }

    @Override public void configure(Map<String, ?> configs, boolean isKey) {}
    @Override public void close() {}

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> data == null ? null : Json.write(data);
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> bytes == null ? null : Json.read(bytes, type);
    }
}
