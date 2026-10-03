package com.orbitwatch.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.IngestRun;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST + SSE surface. Every endpoint reads from {@link LiveViews}; nothing here blocks on Kafka.
 * OpenAPI is generated at /v3/api-docs (Swagger UI at /swagger-ui.html).
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final LiveViews views;
    private final PropagationClient propagation;

    public ApiController(LiveViews views, PropagationClient propagation) {
        this.views = views;
        this.propagation = propagation;
    }

    public record Overview(Instant currentTick, int trackedObjects, long vectorsSeen, TickStats latestStats,
                           Map<String, Long> alertCounts, List<IngestRun> recentIngestRuns, Instant serverTime) {}

    @GetMapping("/overview")
    public Overview overview() {
        return new Overview(views.currentTick(), views.trackedCount(), views.vectorsSeen(), views.latestStats().orElse(null),
                views.alertCounts(), views.ingestRuns().stream().limit(5).toList(), Instant.now());
    }

    @GetMapping("/satellites")
    public List<StateVectorEvent> satellites(@RequestParam(required = false) String regime,
                                             @RequestParam(required = false) String owner,
                                             @RequestParam(required = false) String type,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "altKm") String sort,
                                             @RequestParam(defaultValue = "200") int limit) {
        return views.query(regime, owner, type, q, Math.min(limit, 20_000), comparator(sort));
    }

    static Comparator<StateVectorEvent> comparator(String sort) {
        return switch (sort) {
            case "name" -> Comparator.comparing(StateVectorEvent::objectName, Comparator.nullsLast(String::compareTo));
            case "elementAge" -> Comparator.comparingDouble(StateVectorEvent::elementAgeHours).reversed();
            case "speed" -> Comparator.comparingDouble(StateVectorEvent::speedKmS).reversed();
            case "noradId" -> Comparator.comparingInt(StateVectorEvent::noradId);
            default -> Comparator.comparingDouble(StateVectorEvent::altKm);
        };
    }

    @GetMapping("/satellites/{noradId}")
    public Mono<ResponseEntity<Map<String, Object>>> satellite(@PathVariable int noradId) {
        return propagation.objectDetails(noradId).defaultIfEmpty(com.orbitwatch.contracts.Json.MAPPER.createObjectNode())
                .map(details -> {
                    var v = views.vector(noradId);
                    if (v.isEmpty() && details.isEmpty()) return ResponseEntity.<Map<String, Object>>notFound().build();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("noradId", noradId);
                    out.put("latest", v.orElse(null));
                    out.put("details", details);
                    out.put("recentAlerts", views.alerts(null, null, noradId, 20));
                    return ResponseEntity.ok(out);
                });
    }

    @GetMapping("/satellites/{noradId}/groundtrack")
    public Mono<ResponseEntity<JsonNode>> groundTrack(@PathVariable int noradId,
                                                      @RequestParam(defaultValue = "100") int spanMinutes,
                                                      @RequestParam(defaultValue = "30") int stepSeconds) {
        return propagation.groundTrack(noradId, spanMinutes, stepSeconds)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/alerts")
    public List<AlertEvent> alerts(@RequestParam(required = false) AlertEvent.AlertType type,
                                   @RequestParam(required = false) AlertEvent.Severity minSeverity,
                                   @RequestParam(required = false) Integer noradId,
                                   @RequestParam(defaultValue = "100") int limit) {
        return views.alerts(type, minSeverity, noradId, Math.min(limit, 2000));
    }

    @GetMapping("/stats/history")
    public List<TickStats> statsHistory(@RequestParam(defaultValue = "120") int last) {
        return views.statsHistory(Math.min(last, 720));
    }

    /** Compact positions snapshot: [noradId, lat, lon, altKm, regimeCode] per object. */
    public record Positions(Instant tick, int count, List<double[]> points) {}

    static final Map<String, Integer> REGIME_CODES = Map.of("LEO", 0, "MEO", 1, "GEO", 2, "HEO", 3, "OTHER", 4);

    @GetMapping("/positions")
    public Positions positions(@RequestParam(required = false) String regime, @RequestParam(defaultValue = "0") int sample) {
        return snapshot(regime, sample);
    }

    private Positions snapshot(String regime, int sample) {
        List<double[]> pts = new java.util.ArrayList<>(views.trackedCount());
        int i = 0;
        for (StateVectorEvent v : views.vectors()) {
            if (regime != null && !regime.equalsIgnoreCase(v.regime())) continue;
            if (sample > 1 && (i++ % sample) != 0) continue;
            pts.add(new double[]{v.noradId(), round(v.latDeg(), 3), round(v.lonDeg(), 3), Math.round(v.altKm()),
                    REGIME_CODES.getOrDefault(v.regime(), 4)});
        }
        return new Positions(views.currentTick(), pts.size(), pts);
    }

    private static double round(double v, int p) { double f = Math.pow(10, p); return Math.round(v * f) / f; }

    // ---- Server-Sent Events ----

    @GetMapping(value = "/stream/positions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Positions>> streamPositions(@RequestParam(required = false) String regime,
                                                            @RequestParam(defaultValue = "0") int sample) {
        // one snapshot per tick, shortly after the tick's vectors have landed
        return views.tickFlux().delayElements(Duration.ofMillis(1500))
                .startWith(views.currentTick())
                .map(t -> ServerSentEvent.builder(snapshot(regime, sample)).event("positions").build());
    }

    @GetMapping(value = "/stream/alerts", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AlertEvent>> streamAlerts() {
        return views.alertFlux().map(a -> ServerSentEvent.builder(a).event("alert").id(a.alertId()).build());
    }

    @GetMapping(value = "/stream/stats", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<TickStats>> streamStats() {
        return views.statsFlux().map(s -> ServerSentEvent.builder(s).event("stats").build());
    }
}
