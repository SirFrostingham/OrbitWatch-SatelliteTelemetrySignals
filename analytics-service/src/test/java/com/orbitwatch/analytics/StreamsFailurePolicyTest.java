package com.orbitwatch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KafkaStreams.State;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;

class StreamsFailurePolicyTest {

    @Test
    void exitsExactlyOnceWhenStreamsEntersError() {
        List<Integer> exits = new ArrayList<>();
        StreamsFailurePolicy policy = new StreamsFailurePolicy(exits::add);

        policy.onChange(State.REBALANCING, State.CREATED);
        policy.onChange(State.RUNNING, State.REBALANCING);
        assertThat(exits).isEmpty();

        policy.onChange(State.PENDING_ERROR, State.RUNNING);
        policy.onChange(State.ERROR, State.PENDING_ERROR);
        policy.onChange(State.ERROR, State.ERROR);

        assertThat(exits).containsExactly(StreamsFailurePolicy.EXIT_CODE);
    }

    @Test
    void gracefulShutdownDoesNotExit() {
        List<Integer> exits = new ArrayList<>();
        StreamsFailurePolicy policy = new StreamsFailurePolicy(exits::add);
        policy.onChange(State.PENDING_SHUTDOWN, State.RUNNING);
        policy.onChange(State.NOT_RUNNING, State.PENDING_SHUTDOWN);
        assertThat(exits).isEmpty();
    }

    static Status healthFor(State state) {
        StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
        KafkaStreams streams = mock(KafkaStreams.class);
        when(factory.getKafkaStreams()).thenReturn(streams);
        when(streams.state()).thenReturn(state);
        return new StreamsHealthIndicator(factory).health().getStatus();
    }

    @Test
    void healthIsUpOnlyWhileTheClientCanProcess() {
        assertThat(healthFor(State.CREATED)).isEqualTo(Status.UP);
        assertThat(healthFor(State.REBALANCING)).isEqualTo(Status.UP);
        assertThat(healthFor(State.RUNNING)).isEqualTo(Status.UP);
        assertThat(healthFor(State.PENDING_ERROR)).isEqualTo(Status.DOWN);
        assertThat(healthFor(State.ERROR)).isEqualTo(Status.DOWN);
        assertThat(healthFor(State.PENDING_SHUTDOWN)).isEqualTo(Status.DOWN);
        assertThat(healthFor(State.NOT_RUNNING)).isEqualTo(Status.DOWN);
    }

    @Test
    void healthBeforeStartIsUpWithDetail() {
        StreamsBuilderFactoryBean factory = mock(StreamsBuilderFactoryBean.class);
        assertThat(new StreamsHealthIndicator(factory).health().getDetails()).containsEntry("state", "NOT_STARTED");
    }
}
