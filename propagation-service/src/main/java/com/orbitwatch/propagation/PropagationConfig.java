package com.orbitwatch.propagation;

import com.orbitwatch.contracts.kafka.TopicSpecs;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PropagationConfig {
    // Owns the state-vector stream.
    @Bean NewTopic stateVectorsTopic() { return TopicSpecs.stateVectors(); }
}
