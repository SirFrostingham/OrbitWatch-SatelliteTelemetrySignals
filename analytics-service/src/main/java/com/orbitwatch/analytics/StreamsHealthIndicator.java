package com.orbitwatch.analytics;

import org.apache.kafka.streams.KafkaStreams;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

/**
 * Readiness reflects the Streams client, not just the web server: DOWN once the client is shutting
 * down, not running or in error. Part of the readiness group, which the Docker HEALTHCHECK polls.
 */
@Component("kafkaStreams")
public class StreamsHealthIndicator implements HealthIndicator {
    private final StreamsBuilderFactoryBean factory;

    public StreamsHealthIndicator(StreamsBuilderFactoryBean factory) { this.factory = factory; }

    @Override
    public Health health() {
        KafkaStreams streams = factory.getKafkaStreams();
        if (streams == null) return Health.up().withDetail("state", "NOT_STARTED").build();
        KafkaStreams.State state = streams.state();
        boolean ok = state == KafkaStreams.State.CREATED || state == KafkaStreams.State.REBALANCING
                || state == KafkaStreams.State.RUNNING;
        return (ok ? Health.up() : Health.down()).withDetail("state", state.name()).build();
    }
}
