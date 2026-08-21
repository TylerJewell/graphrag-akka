package io.akka.graphrag.application;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.graphrag.domain.Records.ClusterAssignment;
import io.akka.graphrag.domain.Records.GraphEntity;
import io.akka.graphrag.domain.Records.GraphRelationship;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC-001 §3 rule 36: batched submission, and what the store refuses. */
class GraphStoreEntityTest {

    private static GraphEntity entity(String id, String title) {
        return new GraphEntity(id, 1, title, "d", 1, List.of("t1"));
    }

    private static GraphRelationship relationship(String id, String source, String target) {
        return new GraphRelationship(id, 1, source, target, "d", 1.0, 2L, List.of("t1"));
    }

    private static EventSourcedTestKit<GraphStoreEntity.State, GraphStoreEntity.Event,
            GraphStoreEntity> kit() {
        return EventSourcedTestKit.of("run-1", GraphStoreEntity::new);
    }

    @Test
    void batchesAccumulateAndTheSummaryCounts() {
        var kit = kit();
        kit.method(GraphStoreEntity::addEntities)
                .invoke(List.of(entity("e1", "A"), entity("e2", "B")));
        kit.method(GraphStoreEntity::addRelationships)
                .invoke(List.of(relationship("r1", "A", "B")));
        kit.method(GraphStoreEntity::addClusters)
                .invoke(List.of(new ClusterAssignment(0, 1, -1, List.of("A", "B"))));

        var summary = kit.method(GraphStoreEntity::summary).invoke().getReply();
        assertThat(summary.entities()).isEqualTo(2);
        assertThat(summary.relationships()).isEqualTo(1);
        assertThat(summary.clusters()).isEqualTo(1);
        assertThat(summary.sealed()).isFalse();
    }

    @Test
    void aRetriedBatchAddsNothing() {
        var kit = kit();
        List<GraphEntity> batch = List.of(entity("e1", "A"), entity("e2", "B"));
        kit.method(GraphStoreEntity::addEntities).invoke(batch);
        var second = kit.method(GraphStoreEntity::addEntities).invoke(batch);

        assertThat(second.didPersistEvents()).isFalse();
        assertThat(kit.method(GraphStoreEntity::summary).invoke().getReply().entities())
                .isEqualTo(2);
    }

    @Test
    void anOverlappingBatchAddsOnlyWhatIsNew() {
        var kit = kit();
        kit.method(GraphStoreEntity::addEntities).invoke(List.of(entity("e1", "A")));
        kit.method(GraphStoreEntity::addEntities)
                .invoke(List.of(entity("e1", "A"), entity("e2", "B")));

        var event = kit.getAllEvents().getLast();
        assertThat(event).isInstanceOf(GraphStoreEntity.Event.EntitiesAdded.class);
        assertThat(((GraphStoreEntity.Event.EntitiesAdded) event).entities())
                .extracting(GraphEntity::id).containsExactly("e2");
    }

    @Test
    void twoRelationshipsOverTheSamePairBothSurvive() {
        // SPEC-001 §3 rule 3 turns on duplicated pairs reaching the edge list, so the
        // idempotence above must key on the relationship id and not on its endpoints.
        var kit = kit();
        kit.method(GraphStoreEntity::addRelationships).invoke(List.of(
                relationship("r1", "A", "B"), relationship("r2", "B", "A")));
        assertThat(kit.method(GraphStoreEntity::summary).invoke().getReply().relationships())
                .isEqualTo(2);
    }

    @Test
    void aSealedGraphRefusesMoreRowsAndSealingTwiceIsHarmless() {
        var kit = kit();
        kit.method(GraphStoreEntity::addEntities).invoke(List.of(entity("e1", "A")));
        kit.method(GraphStoreEntity::seal).invoke();

        var again = kit.method(GraphStoreEntity::seal).invoke();
        assertThat(again.didPersistEvents()).isFalse();

        var refused = kit.method(GraphStoreEntity::addEntities)
                .invoke(List.of(entity("e2", "B")));
        assertThat(refused.isError()).isTrue();
        assertThat(refused.getError()).contains("sealed");
    }

    @Test
    void aPageNeverReadsPastTheEndAndReportsTheTotal() {
        var kit = kit();
        kit.method(GraphStoreEntity::addEntities).invoke(List.of(
                entity("e1", "A"), entity("e2", "B"), entity("e3", "C")));

        var first = kit.method(GraphStoreEntity::entityPage)
                .invoke(new GraphStoreEntity.PageRequest(0, 2)).getReply();
        assertThat(first.rows()).hasSize(2);
        assertThat(first.total()).isEqualTo(3);

        var last = kit.method(GraphStoreEntity::entityPage)
                .invoke(new GraphStoreEntity.PageRequest(2, 2)).getReply();
        assertThat(last.rows()).hasSize(1);

        var past = kit.method(GraphStoreEntity::entityPage)
                .invoke(new GraphStoreEntity.PageRequest(9, 2)).getReply();
        assertThat(past.rows()).isEmpty();
        assertThat(past.total()).isEqualTo(3);
    }
}
