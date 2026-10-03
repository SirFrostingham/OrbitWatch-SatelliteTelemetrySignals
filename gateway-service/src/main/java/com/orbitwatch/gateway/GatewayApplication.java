package com.orbitwatch.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gateway service: the only component clients talk to. It subscribes to the analytics and
 * telemetry topics, keeps small in-memory materialised views, and exposes them over REST,
 * GraphQL (query + subscription) and Server-Sent Events. It also serves the dashboard.
 *
 * <p>Read-only by design: nothing a client does can mutate pipeline state.
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
