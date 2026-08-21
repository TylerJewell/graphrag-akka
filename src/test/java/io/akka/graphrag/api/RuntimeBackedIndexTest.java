package io.akka.graphrag.api;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import io.akka.graphrag.application.CommunityView;
import io.akka.graphrag.application.GraphStoreEntity;
import io.akka.graphrag.domain.Fixture;
import io.akka.graphrag.domain.Records.ClusterAssignment;
import io.akka.graphrag.domain.Records.GraphEntity;
import io.akka.graphrag.domain.Records.GraphRelationship;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rules 36-37, and the whole capability driven the way a caller outside a test
 * would drive it: submit the source's own 529 entities, 978 relationships and 121 clusters in
 * batches over HTTP, run the index, and read the answers back out.
 *
 * <p>The parity tests in {@code domain} check the rules; this checks that a real run over the
 * real fixture reaches the same answers through the real components, which is a different claim.
 */
public class RuntimeBackedIndexTest extends TestKitSupport {

    private static final int BATCH = 200;

    private String submitFixture(int budget) {
        String runId = "run-" + UUID.randomUUID();
        List<GraphEntity> entities = Fixture.entities();
        List<GraphRelationship> relationships = Fixture.relationships();
        List<ClusterAssignment> clusters = Fixture.clusters();

        for (int i = 0; i < entities.size(); i += BATCH) {
            httpClient.POST("/index/" + runId + "/graph/entities")
                    .withRequestBody(entities.subList(i, Math.min(i + BATCH, entities.size())))
                    .invoke();
        }
        for (int i = 0; i < relationships.size(); i += BATCH) {
            httpClient.POST("/index/" + runId + "/graph/relationships")
                    .withRequestBody(relationships.subList(i,
                            Math.min(i + BATCH, relationships.size())))
                    .invoke();
        }
        for (int i = 0; i < clusters.size(); i += BATCH) {
            httpClient.POST("/index/" + runId + "/graph/clusters")
                    .withRequestBody(clusters.subList(i, Math.min(i + BATCH, clusters.size())))
                    .invoke();
        }

        GraphStoreEntity.Summary sealed = httpClient.POST("/index/" + runId + "/graph/seal")
                .responseBodyAs(GraphStoreEntity.Summary.class).invoke().body();
        assertThat(sealed.entities()).isEqualTo(529);
        assertThat(sealed.relationships()).isEqualTo(978);
        assertThat(sealed.clusters()).isEqualTo(121);
        assertThat(sealed.sealed()).isTrue();

        httpClient.POST("/index/" + runId + "/run")
                .withRequestBody(new IndexEndpoint.StartRequest(budget, "SOURCE_ORDER"))
                .invoke();

        Awaitility.await().atMost(Duration.ofMinutes(3)).untilAsserted(() ->
                assertThat(status(runId).phase()).isEqualTo("done"));
        return runId;
    }

    private IndexEndpoint.RunStatus status(String runId) {
        return httpClient.GET("/index/" + runId + "/status")
                .responseBodyAs(IndexEndpoint.RunStatus.class).invoke().body();
    }

    private IndexEndpoint.CommunityDetail community(String runId, int community) {
        return httpClient.GET("/index/" + runId + "/communities/" + community)
                .responseBodyAs(IndexEndpoint.CommunityDetail.class).invoke().body();
    }

    @Test
    void aRunOverTheSourceSFixtureReachesTheSourceSAnswersThroughTheRealComponents() {
        int budget = 200;
        String runId = submitFixture(budget);

        // Rule 37: the levels were not known when the run started, and all three were done,
        // highest first.
        IndexEndpoint.RunStatus state = status(runId);
        assertThat(state.completedLevels()).containsExactly(2, 1, 0);
        assertThat(state.remainingLevels()).isEmpty();

        Map<String, String> expected = new TreeMap<>();
        for (JsonNode row : Fixture.answers("source-answers-8.json")
                .get("rollup_source_order/" + budget)) {
            expected.put(row.get("level").asInt() + ":" + row.get("community").asInt(),
                    row.get("context_string").asText());
        }

        List<String> disagreements = new ArrayList<>();
        for (var entry : expected.entrySet()) {
            int community = Integer.parseInt(entry.getKey().split(":")[1]);
            IndexEndpoint.CommunityDetail detail = community(runId, community);
            if (!entry.getValue().equals(detail.rolledContext())) {
                disagreements.add(entry.getKey());
            }
        }
        assertThat(disagreements).isEmpty();
        assertThat(expected).hasSize(121);
    }

    @Test
    void theViewListsEveryCommunityOfARunAndCanBeAskedForOneLevel() {
        String runId = submitFixture(16_000);

        Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            CommunityView.CommunityEntries rows = httpClient.GET("/index/" + runId + "/communities")
                    .responseBodyAs(CommunityView.CommunityEntries.class).invoke().body();
            assertThat(rows.communities()).hasSize(121);
            assertThat(rows.communities()).allMatch(CommunityView.CommunityEntry::rolledUp);
        });

        CommunityView.CommunityEntries levelTwo = httpClient
                .GET("/index/" + runId + "/communities/level/2")
                .responseBodyAs(CommunityView.CommunityEntries.class).invoke().body();
        assertThat(levelTwo.communities()).isNotEmpty();
        assertThat(levelTwo.communities()).allMatch(r -> r.level() == 2);
    }

    @Test
    void theEdgeListRouteReturnsWhatTheSourceHandedItsClusterer() {
        List<Object> expected = new ArrayList<>();
        Fixture.answers("source-answers-2.json").get("leiden_input/lcc")
                .forEach(row -> expected.add(row.get(0).asText() + "|" + row.get(1).asText()
                        + "|" + row.get(2).asDouble()));

        IndexEndpoint.EdgeListResponse response = httpClient.POST("/index/edge-list")
                .withRequestBody(new IndexEndpoint.EdgeListRequest(
                        Fixture.relationships(), true, false))
                .responseBodyAs(IndexEndpoint.EdgeListResponse.class).invoke().body();

        assertThat(response.edges().stream()
                .map(e -> e.source() + "|" + e.target() + "|" + e.weight()).toList())
                .isEqualTo(expected);
    }

    @Test
    void aGraphCannotGrowAfterItIsSealedAndARunCannotStartBeforeIt() {
        String runId = "run-" + UUID.randomUUID();
        httpClient.POST("/index/" + runId + "/graph/entities")
                .withRequestBody(Fixture.entities().subList(0, 5)).invoke();

        assertThat(httpClient.POST("/index/" + runId + "/run")
                .withRequestBody(new IndexEndpoint.StartRequest(200, "SOURCE_ORDER"))
                .invoke().status().isFailure()).isTrue();

        httpClient.POST("/index/" + runId + "/graph/seal").invoke();
        assertThat(httpClient.POST("/index/" + runId + "/graph/entities")
                .withRequestBody(Fixture.entities().subList(5, 6))
                .invoke().status().isFailure()).isTrue();
    }
}
