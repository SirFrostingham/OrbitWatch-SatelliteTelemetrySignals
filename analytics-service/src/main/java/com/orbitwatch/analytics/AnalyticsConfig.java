package com.orbitwatch.analytics;

import com.orbitwatch.contracts.kafka.TopicSpecs;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

@Configuration
@EnableKafkaStreams
public class AnalyticsConfig {

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    KafkaStreamsConfiguration streamsConfig(KafkaProperties kafka, org.springframework.beans.factory.ObjectProvider<org.springframework.boot.ssl.SslBundles> sslBundles) {
        Map<String, Object> props = new HashMap<>(kafka.buildStreamsProperties(sslBundles.getIfAvailable()));
        props.putIfAbsent(StreamsConfig.APPLICATION_ID_CONFIG, "orbitwatch-analytics");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafka.getBootstrapServers()));
        props.putIfAbsent(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 3);
        props.putIfAbsent(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        props.putIfAbsent(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);
        props.putIfAbsent(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "latest");
        props.putIfAbsent(StreamsConfig.consumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), 2000);
        props.putIfAbsent(StreamsConfig.producerPrefix("compression.type"), "lz4");
        props.putIfAbsent(StreamsConfig.producerPrefix("linger.ms"), 50);
        return new KafkaStreamsConfiguration(props);
    }

    /** Exit the JVM when Streams dies, so the container restarts instead of idling (StreamsFailurePolicy). */
    @Bean
    org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer streamsFailurePolicy(org.springframework.context.ConfigurableApplicationContext ctx) {
        StreamsFailurePolicy policy = new StreamsFailurePolicy(code -> {
            // off the Streams thread: SpringApplication.exit closes the context, which stops Streams
            Thread t = new Thread(() -> System.exit(org.springframework.boot.SpringApplication.exit(ctx, () -> code)), "streams-fatal-exit");
            t.setDaemon(false);
            t.start();
        });
        return factoryBean -> factoryBean.setStateListener(policy);
    }

    @Bean
    Topology analyticsTopology(StreamsBuilder builder, AnalyticsProperties props) {
        AnalyticsTopology.build(builder, props);
        return builder.build();
    }

    // Owns the analytics outputs.
    @Bean NewTopic tickStatsTopic() { return TopicSpecs.tickStats(); }
    @Bean NewTopic alertsTopic() { return TopicSpecs.alerts(); }
}
