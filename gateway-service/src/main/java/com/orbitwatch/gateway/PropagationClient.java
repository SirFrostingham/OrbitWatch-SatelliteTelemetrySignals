package com.orbitwatch.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Service-to-service client for on-demand computations that live in the propagation service
 * (ground tracks, element details). Kept behind one class so the integration surface — URL,
 * timeout, error mapping — is in exactly one place.
 */
@Component
public class PropagationClient {
    private final WebClient client;

    public PropagationClient(WebClient.Builder builder, @Value("${orbitwatch.gateway.propagation-url:http://localhost:8082}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    public Mono<JsonNode> groundTrack(int noradId, int spanMinutes, int stepSeconds) {
        return client.get()
                .uri(u -> u.path("/api/propagation/objects/{id}/groundtrack")
                        .queryParam("spanMinutes", spanMinutes).queryParam("stepSeconds", stepSeconds).build(noradId))
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }

    public Mono<JsonNode> objectDetails(int noradId) {
        return client.get().uri("/api/propagation/objects/{id}", noradId)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }
}
