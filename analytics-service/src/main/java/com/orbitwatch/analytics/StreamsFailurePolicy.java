package com.orbitwatch.analytics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import org.apache.kafka.streams.KafkaStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Kafka Streams client that has gone to {@code ERROR} is permanently dead, but the JVM and the web
 * server keep running — the container looks healthy while no statistics or alerts are produced.
 * (Seen 2026-09-10: RocksDB failed to load on the runtime image, the Streams client shut down, and
 * `docker compose ps` showed analytics healthy with zero restarts.)
 *
 * <p>Policy: on {@code ERROR}, exit the process with a non-zero code exactly once. Docker's
 * {@code restart: unless-stopped} and Kubernetes restart it, a transient failure heals, and a
 * permanent one shows up as a climbing restart count instead of silence.
 */
public final class StreamsFailurePolicy implements KafkaStreams.StateListener {
    private static final Logger log = LoggerFactory.getLogger(StreamsFailurePolicy.class);
    public static final int EXIT_CODE = 70; // EX_SOFTWARE

    private final IntConsumer exiter;
    private final AtomicBoolean exiting = new AtomicBoolean();

    public StreamsFailurePolicy(IntConsumer exiter) { this.exiter = exiter; }

    @Override
    public void onChange(KafkaStreams.State newState, KafkaStreams.State oldState) {
        log.info("kafka streams state {} -> {}", oldState, newState);
        if (newState == KafkaStreams.State.ERROR && exiting.compareAndSet(false, true)) {
            log.error("kafka streams client entered ERROR (see the exception above); exiting with code {} so the "
                    + "orchestrator restarts analytics-service rather than leaving a running process that produces nothing", EXIT_CODE);
            exiter.accept(EXIT_CODE);
        }
    }
}
