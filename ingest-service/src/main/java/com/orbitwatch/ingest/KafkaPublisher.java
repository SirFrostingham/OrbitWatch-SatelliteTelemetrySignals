package com.orbitwatch.ingest;

import com.orbitwatch.contracts.Json;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Small wrapper so ingest code publishes contract records without touching Kafka APIs directly. */
@Component
public class KafkaPublisher {
    private final KafkaTemplate<String, byte[]> template;

    public KafkaPublisher(KafkaTemplate<String, byte[]> template) {
        this.template = template;
    }

    public CompletableFuture<Void> send(String topic, String key, Object value) {
        return template.send(topic, key, Json.write(value)).thenApply(r -> null);
    }

    /** Send a batch and block until the broker has acknowledged all of them; returns failures. */
    public int sendAll(String topic, List<KeyedRecord> records) {
        AtomicInteger failures = new AtomicInteger();
        List<CompletableFuture<Void>> futures = records.stream()
                .map(r -> send(topic, r.key(), r.value()).exceptionally(ex -> { failures.incrementAndGet(); return null; }))
                .toList();
        template.flush();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return failures.get();
    }

    public record KeyedRecord(String key, Object value) {}
}
