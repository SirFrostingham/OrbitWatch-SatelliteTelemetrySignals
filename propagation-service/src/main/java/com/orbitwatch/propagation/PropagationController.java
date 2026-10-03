package com.orbitwatch.propagation;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Internal API consumed by the gateway (ground tracks) and by operators (status). */
@RestController
@RequestMapping("/api/propagation")
public class PropagationController {
    private final Catalog catalog;
    private final PropagationTicker ticker;
    private final GroundTrackService groundTracks;

    public PropagationController(Catalog catalog, PropagationTicker ticker, GroundTrackService groundTracks) {
        this.catalog = catalog;
        this.ticker = ticker;
        this.groundTracks = groundTracks;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "catalogSize", catalog.size(),
                "elementUpdatesApplied", catalog.elementUpdates(),
                "pendingCatalogRows", catalog.pendingCatalogRows(),
                "lastTick", ticker.lastTick().toString(),
                "lastTickObjects", ticker.lastTickCount(),
                "tickIntervalMs", ticker.tickInterval().toMillis());
    }

    @GetMapping("/objects/{noradId}/groundtrack")
    public ResponseEntity<GroundTrackService.GroundTrack> groundTrack(
            @PathVariable int noradId,
            @RequestParam(defaultValue = "100") int spanMinutes,
            @RequestParam(defaultValue = "30") int stepSeconds,
            @RequestParam(required = false) Instant from) {
        int span = Math.min(Math.max(spanMinutes, 1), 24 * 60);
        int step = Math.min(Math.max(stepSeconds, 5), 3600);
        return groundTracks.compute(noradId, from == null ? Instant.now() : from, span, step)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/objects/{noradId}")
    public ResponseEntity<Map<String, Object>> object(@PathVariable int noradId) {
        return catalog.get(noradId)
                .map(o -> ResponseEntity.ok(Map.<String, Object>of(
                        "elements", o.elements(),
                        "catalog", o.catalog() == null ? Map.of() : o.catalog(),
                        "regime", o.regime().name(),
                        "propagationFailed", o.hasFailed())))
                .orElse(ResponseEntity.notFound().build());
    }
}
