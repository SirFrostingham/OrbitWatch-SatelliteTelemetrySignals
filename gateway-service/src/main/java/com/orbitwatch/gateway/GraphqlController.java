package com.orbitwatch.gateway;

import com.orbitwatch.contracts.AlertEvent;
import com.orbitwatch.contracts.StateVectorEvent;
import com.orbitwatch.contracts.TickStats;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;

/** GraphQL resolvers over the same {@link LiveViews}; subscriptions ride the reactive sinks. */
@Controller
public class GraphqlController {
    private final LiveViews views;

    public GraphqlController(LiveViews views) { this.views = views; }

    public record Count(String key, long value) {}
    public record Count2(String key, String value) {}

    static List<Count> counts(Map<String, Long> m) {
        return m == null ? List.of() : m.entrySet().stream().map(e -> new Count(e.getKey(), e.getValue())).toList();
    }

    @QueryMapping
    public ApiController.Overview overview() {
        return new ApiController.Overview(views.currentTick(), views.trackedCount(), views.vectorsSeen(),
                views.latestStats().orElse(null), views.alertCounts(), List.of(), java.time.Instant.now());
    }

    @SchemaMapping(typeName = "Overview", field = "alertCounts")
    public List<Count> overviewAlertCounts(ApiController.Overview o) { return counts(o.alertCounts()); }

    @QueryMapping
    public StateVectorEvent satellite(@Argument int noradId) { return views.vector(noradId).orElse(null); }

    @QueryMapping
    public List<StateVectorEvent> satellites(@Argument String regime, @Argument String owner, @Argument String type,
                                             @Argument String q, @Argument String sort, @Argument int limit) {
        Comparator<StateVectorEvent> order = switch (sort == null ? "ALT" : sort) {
            case "NAME" -> ApiController.comparator("name");
            case "ELEMENT_AGE" -> ApiController.comparator("elementAge");
            case "SPEED" -> ApiController.comparator("speed");
            case "NORAD_ID" -> ApiController.comparator("noradId");
            default -> ApiController.comparator("altKm");
        };
        return views.query(regime, owner, type, q, Math.min(limit, 5000), order);
    }

    @SchemaMapping(typeName = "Satellite", field = "recentAlerts")
    public List<AlertEvent> recentAlerts(StateVectorEvent sat, @Argument int limit) {
        return views.alerts(null, null, sat.noradId(), limit);
    }

    @QueryMapping
    public List<AlertEvent> alerts(@Argument AlertEvent.AlertType type, @Argument AlertEvent.Severity minSeverity,
                                   @Argument Integer noradId, @Argument int limit) {
        return views.alerts(type, minSeverity, noradId, Math.min(limit, 2000));
    }

    @SchemaMapping(typeName = "Alert", field = "details")
    public List<Count2> alertDetails(AlertEvent a) {
        return a.details() == null ? List.of() : a.details().entrySet().stream().map(e -> new Count2(e.getKey(), String.valueOf(e.getValue()))).toList();
    }

    @QueryMapping
    public List<TickStats> tickStats(@Argument int last) { return views.statsHistory(Math.min(last, 720)); }

    @SchemaMapping(typeName = "TickStats", field = "byRegime")
    public List<Count> byRegime(TickStats s) { return counts(s.byRegime()); }

    @SchemaMapping(typeName = "TickStats", field = "byObjectType")
    public List<Count> byObjectType(TickStats s) { return counts(s.byObjectType()); }

    @SchemaMapping(typeName = "TickStats", field = "byOwnerTop")
    public List<Count> byOwnerTop(TickStats s) { return counts(s.byOwnerTop()); }

    @SubscriptionMapping
    public Flux<AlertEvent> alertStream(@Argument AlertEvent.Severity minSeverity) {
        return views.alertFlux().filter(a -> minSeverity == null || a.severity().ordinal() >= minSeverity.ordinal());
    }

    @SubscriptionMapping
    public Flux<TickStats> tickStatsStream() { return views.statsFlux(); }
}
