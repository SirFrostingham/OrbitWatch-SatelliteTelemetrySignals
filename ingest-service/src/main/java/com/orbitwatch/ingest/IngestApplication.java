package com.orbitwatch.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Ingest service: the only component that talks to external data providers. It turns a bulk,
 * rate-limited, pull-based source (CelesTrak) into a keyed, compacted change stream on Kafka.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(IngestProperties.class)
public class IngestApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngestApplication.class, args);
    }
}
