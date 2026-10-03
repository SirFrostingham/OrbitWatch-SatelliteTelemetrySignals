package com.orbitwatch.ingest;

import com.orbitwatch.contracts.IngestRun;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints: inspect recent runs, force a run (respects the provider interval via cache). */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {
    private final GpIngestService gp;
    private final SatcatIngestService satcat;
    private final ElementChangeDetector detector;

    public IngestController(GpIngestService gp, SatcatIngestService satcat, ElementChangeDetector detector) {
        this.gp = gp;
        this.satcat = satcat;
        this.detector = detector;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("knownObjects", detector.size(), "recentRuns", gp.recentRuns());
    }

    @PostMapping("/run")
    public List<IngestRun> run() {
        return List.of(gp.runOnce(), satcat.runOnce());
    }
}
