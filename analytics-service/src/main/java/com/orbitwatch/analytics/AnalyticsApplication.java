package com.orbitwatch.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Analytics service: a Kafka Streams application that turns the raw state-vector firehose into
 * operator-meaningful outputs — per-tick statistics and alerts. Horizontally scalable by
 * partition; all state lives in Kafka-backed stores.
 */
@SpringBootApplication
@EnableConfigurationProperties(AnalyticsProperties.class)
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }
}
