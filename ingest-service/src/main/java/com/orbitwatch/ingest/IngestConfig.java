package com.orbitwatch.ingest;

import com.orbitwatch.contracts.kafka.CompactedTopicLoader;
import com.orbitwatch.contracts.kafka.TopicSpecs;
import java.nio.file.Path;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestConfig {

    @Bean
    SourceCache sourceCache(IngestProperties props, io.micrometer.core.instrument.MeterRegistry meters) {
        SourceCache cache = new SourceCache(Path.of(props.cacheDir()));
        meters.gauge("orbitwatch.ingest.cache.write_failures", cache, SourceCache::writeFailures);
        return cache;
    }

    @Bean
    ElementChangeDetector elementChangeDetector() {
        return new ElementChangeDetector();
    }

    @Bean
    CompactedTopicLoader compactedTopicLoader(KafkaProperties kafka, org.springframework.beans.factory.ObjectProvider<org.springframework.boot.ssl.SslBundles> sslBundles) {
        return new CompactedTopicLoader(String.join(",", kafka.getBootstrapServers()), kafka.buildConsumerProperties(sslBundles.getIfAvailable()));
    }

    // The ingest service owns (provisions) the topics it produces.
    @Bean NewTopic gpElementsTopic() { return TopicSpecs.gpElements(); }
    @Bean NewTopic catalogObjectsTopic() { return TopicSpecs.catalogObjects(); }
    @Bean NewTopic ingestRunsTopic() { return TopicSpecs.ingestRuns(); }
    @Bean NewTopic alertsTopic() { return TopicSpecs.alerts(); }
}
