package com.orbitwatch.analytics;

import java.util.Map;
import org.apache.kafka.streams.KafkaStreams;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final StreamsBuilderFactoryBean factory;
    private final AnalyticsProperties props;

    public AnalyticsController(StreamsBuilderFactoryBean factory, AnalyticsProperties props) {
        this.factory = factory;
        this.props = props;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        KafkaStreams streams = factory.getKafkaStreams();
        return Map.of(
                "state", streams == null ? "NOT_STARTED" : streams.state().name(),
                "threads", streams == null ? 0 : streams.metadataForLocalThreads().size(),
                "config", props);
    }

    @GetMapping("/topology")
    public String topology() {
        return factory.getTopology() == null ? "" : factory.getTopology().describe().toString();
    }
}
