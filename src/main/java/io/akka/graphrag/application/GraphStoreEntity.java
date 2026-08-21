package io.akka.graphrag.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.graphrag.domain.Records.Claim;
import io.akka.graphrag.domain.Records.ClusterAssignment;
import io.akka.graphrag.domain.Records.GraphEntity;
import io.akka.graphrag.domain.Records.GraphRelationship;

import java.util.ArrayList;
import java.util.List;

/**
 * One run's input graph and the hierarchy it is to be assembled against — SPEC-001 §3 rule 36.
 *
 * <p>Submission is batched because the source's own fixture is 798 KB as a single payload and a
 * command over 1,048,491 bytes is rejected outright (question-log rows 1 and 2). Reads are paged
 * for the same reason: the whole graph never crosses the wire in one message.
 */
@Component(id = "graph-store")
public class GraphStoreEntity extends EventSourcedEntity<GraphStoreEntity.State,
        GraphStoreEntity.Event> {

    public record State(
            List<GraphEntity> entities,
            List<GraphRelationship> relationships,
            List<Claim> claims,
            List<ClusterAssignment> clusters,
            boolean sealed) {}

    public sealed interface Event {
        @TypeName("entities-added")
        record EntitiesAdded(List<GraphEntity> entities) implements Event {}

        @TypeName("relationships-added")
        record RelationshipsAdded(List<GraphRelationship> relationships) implements Event {}

        @TypeName("claims-added")
        record ClaimsAdded(List<Claim> claims) implements Event {}

        @TypeName("clusters-added")
        record ClustersAdded(List<ClusterAssignment> clusters) implements Event {}

        @TypeName("sealed")
        record Sealed() implements Event {}
    }

    public record Summary(int entities, int relationships, int claims, int clusters,
                          boolean sealed) {}

    /**
     * A generic record cannot be a command parameter: the runtime deserializes a command from
     * its declared type and a type variable does not survive that. So the request and each
     * response are their own concrete types.
     */
    public record PageRequest(int offset, int limit) {}

    public record EntityPage(List<GraphEntity> rows, int offset, int total) {}

    public record RelationshipPage(List<GraphRelationship> rows, int offset, int total) {}

    public record ClusterPage(List<ClusterAssignment> rows, int offset, int total) {}

    /**
     * The source's own fixture is 1,628 rows and 798 KB, so this leaves room for roughly
     * twelve of it before the accumulated state approaches the platform's 10 MB snapshot
     * ceiling. Past that an entity stops replicating, which is the failure worth naming.
     */
    private static final int MAX_ROWS = 20_000;

    private final String runId;

    public GraphStoreEntity(EventSourcedEntityContext context) {
        this.runId = context.entityId();
    }

    @Override
    public State emptyState() {
        return new State(List.of(), List.of(), List.of(), List.of(), false);
    }

    public Effect<Done> addEntities(List<GraphEntity> entities) {
        return append(entities, GraphEntity::id, currentState().entities(),
                GraphEntity::id, Event.EntitiesAdded::new);
    }

    public Effect<Done> addRelationships(List<GraphRelationship> relationships) {
        return append(relationships, GraphRelationship::id, currentState().relationships(),
                GraphRelationship::id, Event.RelationshipsAdded::new);
    }

    public Effect<Done> addClaims(List<Claim> claims) {
        return append(claims, Claim::id, currentState().claims(), Claim::id,
                Event.ClaimsAdded::new);
    }

    public Effect<Done> addClusters(List<ClusterAssignment> clusters) {
        return append(clusters, GraphStoreEntity::clusterKey, currentState().clusters(),
                GraphStoreEntity::clusterKey, Event.ClustersAdded::new);
    }

    private static String clusterKey(ClusterAssignment cluster) {
        return cluster.level() + ":" + cluster.community();
    }

    /**
     * Appends the rows that are not already here, keyed by their own identity, so a retried
     * batch adds nothing rather than doubling a graph. Duplicate <em>pairs</em> of endpoints
     * are untouched by this: SPEC-001 §3 rule 3 depends on them surviving, and the 43 such
     * pairs in the source's fixture carry distinct relationship ids.
     */
    private <T> Effect<Done> append(
            List<T> incoming,
            java.util.function.Function<T, String> incomingKey,
            List<T> existing,
            java.util.function.Function<T, String> existingKey,
            java.util.function.Function<List<T>, Event> event) {

        if (currentState().sealed()) {
            return effects().error(
                    "graph for run " + runId + " is sealed and cannot take more rows");
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (T row : existing) {
            seen.add(existingKey.apply(row));
        }
        List<T> fresh = new ArrayList<>();
        for (T row : incoming) {
            if (seen.add(incomingKey.apply(row))) {
                fresh.add(row);
            }
        }
        if (fresh.isEmpty()) {
            return effects().reply(Done.done());
        }
        if (rowCount() + fresh.size() > MAX_ROWS) {
            // A graph this size would snapshot past the platform's 10 MB state ceiling and
            // become unreplicable. It fails here, named, rather than there, silently.
            return effects().error("graph for run " + runId + " would hold more than "
                    + MAX_ROWS + " rows; split it across runs");
        }
        return effects().persist(event.apply(fresh)).thenReply(s -> Done.done());
    }

    private int rowCount() {
        State state = currentState();
        return state.entities().size() + state.relationships().size()
                + state.claims().size() + state.clusters().size();
    }

    /** Marks the graph complete. A run may only start once its graph is sealed. */
    public Effect<Done> seal() {
        if (currentState().sealed()) {
            return effects().reply(Done.done());
        }
        return effects().persist(new Event.Sealed()).thenReply(s -> Done.done());
    }

    public ReadOnlyEffect<Summary> summary() {
        State state = currentState();
        return effects().reply(new Summary(state.entities().size(),
                state.relationships().size(), state.claims().size(),
                state.clusters().size(), state.sealed()));
    }

    public ReadOnlyEffect<EntityPage> entityPage(PageRequest request) {
        List<GraphEntity> all = currentState().entities();
        return effects().reply(new EntityPage(slice(all, request), offset(all, request),
                all.size()));
    }

    public ReadOnlyEffect<RelationshipPage> relationshipPage(PageRequest request) {
        List<GraphRelationship> all = currentState().relationships();
        return effects().reply(new RelationshipPage(slice(all, request), offset(all, request),
                all.size()));
    }

    public ReadOnlyEffect<ClusterPage> clusterPage(PageRequest request) {
        List<ClusterAssignment> all = currentState().clusters();
        return effects().reply(new ClusterPage(slice(all, request), offset(all, request),
                all.size()));
    }

    private static <T> int offset(List<T> all, PageRequest request) {
        return Math.min(Math.max(request.offset(), 0), all.size());
    }

    private static <T> List<T> slice(List<T> all, PageRequest request) {
        int from = offset(all, request);
        int to = Math.min(from + Math.max(request.limit(), 0), all.size());
        return List.copyOf(all.subList(from, to));
    }

    @Override
    public State applyEvent(Event event) {
        State state = currentState();
        return switch (event) {
            case Event.EntitiesAdded e -> new State(concat(state.entities(), e.entities()),
                    state.relationships(), state.claims(), state.clusters(), state.sealed());
            case Event.RelationshipsAdded e -> new State(state.entities(),
                    concat(state.relationships(), e.relationships()), state.claims(),
                    state.clusters(), state.sealed());
            case Event.ClaimsAdded e -> new State(state.entities(), state.relationships(),
                    concat(state.claims(), e.claims()), state.clusters(), state.sealed());
            case Event.ClustersAdded e -> new State(state.entities(), state.relationships(),
                    state.claims(), concat(state.clusters(), e.clusters()), state.sealed());
            case Event.Sealed ignored -> new State(state.entities(), state.relationships(),
                    state.claims(), state.clusters(), true);
        };
    }

    private static <T> List<T> concat(List<T> existing, List<T> added) {
        List<T> out = new ArrayList<>(existing);
        out.addAll(added);
        return out;
    }
}
