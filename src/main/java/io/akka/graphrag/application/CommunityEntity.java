package io.akka.graphrag.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import io.akka.graphrag.domain.Records.Community;
import io.akka.graphrag.domain.SortContext.NodeContext;

import java.util.List;

/**
 * One community of one run, keyed {@code <runId>:<community>}.
 *
 * <p>A community is the unit that survives a restart here: its membership, its own detail and its
 * finished report are each written once and are individually small — the largest local context on
 * the source's fixture is 57 KB, well inside Akka's per-request ceiling (question-log row 1),
 * where the run's whole context set is 548 KB and would not be.
 */
@Component(id = "community")
public class CommunityEntity extends EventSourcedEntity<CommunityEntity.State,
        CommunityEntity.Event> {

    public record State(
            String runId,
            int community,
            int level,
            int parent,
            List<Integer> children,
            String title,
            List<String> entityIds,
            List<String> relationshipIds,
            List<String> textUnitIds,
            String period,
            int size,
            String localContext,
            int localContextSize,
            boolean localContextExceedsBudget,
            List<NodeContext> localContextNodes,
            String rolledContext,
            int rolledContextSize,
            String report) {

        public boolean formed() {
            return title != null;
        }
    }

    public sealed interface Event {
        @TypeName("formed")
        record Formed(String runId, Community community) implements Event {}

        @TypeName("local-context-built")
        record LocalContextBuilt(String contextString, int contextSize, boolean exceedsBudget,
                                 List<NodeContext> nodes) implements Event {}

        @TypeName("rolled-up")
        record RolledUp(String contextString, int contextSize, String report) implements Event {}
    }

    private final String entityId;

    public CommunityEntity(EventSourcedEntityContext context) {
        this.entityId = context.entityId();
    }

    @Override
    public State emptyState() {
        return new State(null, -1, -1, -1, List.of(), null, List.of(), List.of(), List.of(),
                null, 0, "", 0, false, List.of(), "", 0, null);
    }

    public Effect<Done> form(Event.Formed formed) {
        return effects().persist(formed).thenReply(s -> Done.done());
    }

    public Effect<Done> setLocalContext(Event.LocalContextBuilt built) {
        if (!currentState().formed()) {
            return effects().error("community " + entityId + " has not been formed");
        }
        return effects().persist(built).thenReply(s -> Done.done());
    }

    public Effect<Done> rollUp(Event.RolledUp rolled) {
        if (!currentState().formed()) {
            return effects().error("community " + entityId + " has not been formed");
        }
        return effects().persist(rolled).thenReply(s -> Done.done());
    }

    public ReadOnlyEffect<State> get() {
        return effects().reply(currentState());
    }

    @Override
    public State applyEvent(Event event) {
        State state = currentState();
        return switch (event) {
            case Event.Formed e -> {
                Community c = e.community();
                yield new State(e.runId(), c.community(), c.level(), c.parent(), c.children(),
                        c.title(), c.entityIds(), c.relationshipIds(), c.textUnitIds(),
                        c.period(), c.size(), state.localContext(), state.localContextSize(),
                        state.localContextExceedsBudget(), state.localContextNodes(),
                        state.rolledContext(), state.rolledContextSize(), state.report());
            }
            case Event.LocalContextBuilt e -> new State(state.runId(), state.community(),
                    state.level(), state.parent(), state.children(), state.title(),
                    state.entityIds(), state.relationshipIds(), state.textUnitIds(),
                    state.period(), state.size(), e.contextString(), e.contextSize(),
                    e.exceedsBudget(), e.nodes(), state.rolledContext(),
                    state.rolledContextSize(), state.report());
            case Event.RolledUp e -> new State(state.runId(), state.community(), state.level(),
                    state.parent(), state.children(), state.title(), state.entityIds(),
                    state.relationshipIds(), state.textUnitIds(), state.period(), state.size(),
                    state.localContext(), state.localContextSize(),
                    state.localContextExceedsBudget(), state.localContextNodes(),
                    e.contextString(), e.contextSize(), e.report());
        };
    }
}
