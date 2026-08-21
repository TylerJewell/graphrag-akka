package io.akka.graphrag.application;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.graphrag.domain.Records.Community;
import io.akka.graphrag.domain.SortContext.NodeContext;
import io.akka.graphrag.domain.SortContext.NodeDetails;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** One community's three facts, in the order a run establishes them. */
class CommunityEntityTest {

    private static final Community COMMUNITY = new Community(
            "run-1-7", 7, 1, 3, List.of(11, 12), "Community 7", 7,
            List.of("e1", "e2"), List.of("r1"), List.of("t1"), "2026-08-21", 2);

    private static EventSourcedTestKit<CommunityEntity.State, CommunityEntity.Event,
            CommunityEntity> kit() {
        return EventSourcedTestKit.of("run-1:7", CommunityEntity::new);
    }

    @Test
    void formingThenBuildingThenRollingUpLeavesEveryFactReadable() {
        var kit = kit();
        kit.method(CommunityEntity::form)
                .invoke(new CommunityEntity.Event.Formed("run-1", COMMUNITY));

        List<NodeContext> nodes = List.of(new NodeContext("A", 1,
                new NodeDetails(1, "A", "a", 1), List.of(), null));
        kit.method(CommunityEntity::setLocalContext)
                .invoke(new CommunityEntity.Event.LocalContextBuilt("ctx", 3, false, nodes));
        kit.method(CommunityEntity::rollUp)
                .invoke(new CommunityEntity.Event.RolledUp("rolled", 2, "REPORT c=7 l=1 n=6"));

        var state = kit.method(CommunityEntity::get).invoke().getReply();
        assertThat(state.runId()).isEqualTo("run-1");
        assertThat(state.community()).isEqualTo(7);
        assertThat(state.level()).isEqualTo(1);
        assertThat(state.parent()).isEqualTo(3);
        assertThat(state.children()).containsExactly(11, 12);
        assertThat(state.entityIds()).containsExactly("e1", "e2");
        assertThat(state.localContext()).isEqualTo("ctx");
        assertThat(state.localContextSize()).isEqualTo(3);
        assertThat(state.localContextNodes()).isEqualTo(nodes);
        assertThat(state.rolledContext()).isEqualTo("rolled");
        assertThat(state.report()).isEqualTo("REPORT c=7 l=1 n=6");
    }

    @Test
    void aCommunityThatWasNeverFormedRefusesTheLaterTwoFacts() {
        // The workflow forms every community before it builds any context; this says what
        // happens if that order is ever broken, rather than leaving it to a null.
        var kit = kit();
        var built = kit.method(CommunityEntity::setLocalContext)
                .invoke(new CommunityEntity.Event.LocalContextBuilt("ctx", 3, false, List.of()));
        assertThat(built.isError()).isTrue();

        var rolled = kit.method(CommunityEntity::rollUp)
                .invoke(new CommunityEntity.Event.RolledUp("rolled", 2, "r"));
        assertThat(rolled.isError()).isTrue();
    }

    @Test
    void rollingUpDoesNotDiscardWhatWasBuiltBefore() {
        var kit = kit();
        kit.method(CommunityEntity::form)
                .invoke(new CommunityEntity.Event.Formed("run-1", COMMUNITY));
        kit.method(CommunityEntity::setLocalContext)
                .invoke(new CommunityEntity.Event.LocalContextBuilt("ctx", 3, true, List.of()));
        kit.method(CommunityEntity::rollUp)
                .invoke(new CommunityEntity.Event.RolledUp("rolled", 2, "r"));

        var state = kit.method(CommunityEntity::get).invoke().getReply();
        assertThat(state.localContext()).isEqualTo("ctx");
        assertThat(state.localContextExceedsBudget()).isTrue();
        assertThat(state.title()).isEqualTo("Community 7");
    }
}
