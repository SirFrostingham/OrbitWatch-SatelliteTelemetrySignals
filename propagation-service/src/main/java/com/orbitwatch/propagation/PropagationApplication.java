package com.orbitwatch.propagation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Propagation service: consumes the compacted element-set and catalogue topics into an in-memory
 * table, and on every tick propagates the whole catalogue with SGP4, emitting one state vector per
 * object. Stateless across restarts — the Kafka topics are the state.
 */
@SpringBootApplication
@EnableScheduling
public class PropagationApplication {
    public static void main(String[] args) {
        SpringApplication.run(PropagationApplication.class, args);
    }
}
