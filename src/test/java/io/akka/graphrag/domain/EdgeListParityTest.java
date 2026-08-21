package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.graphrag.domain.EdgeListPreparation.Options;
import io.akka.graphrag.domain.Records.GraphRelationship;
import io.akka.graphrag.domain.Records.WeightedEdge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rules 1-5 and §4 decision 4, against the exact edge lists the source handed to
 * {@code graspologic_native} (probe_02.py {@code leiden_input/*}).
 */
class EdgeListParityTest {

    private static List<WeightedEdge> expected(String tag) {
        List<WeightedEdge> out = new ArrayList<>();
        for (JsonNode row : Fixture.answers("source-answers-2.json").get("leiden_input/" + tag)) {
            out.add(new WeightedEdge(row.get(0).asText(), row.get(1).asText(),
                    row.get(2).asDouble()));
        }
        return out;
    }

    @Test
    void withTheLargestConnectedComponentFilterTheEdgeListIsTheSource() {
        assertThat(EdgeListPreparation.prepare(Fixture.relationships(), new Options(true, false)))
                .isEqualTo(expected("lcc"));
    }

    @Test
    void withoutItTheEdgeListIsAlsoTheSource() {
        assertThat(EdgeListPreparation.prepare(Fixture.relationships(), new Options(false, false)))
                .isEqualTo(expected("nolcc"));
    }

    @Test
    void theFilterDropsExactlyTheEdgesTheSourceDrops() {
        // Not a restatement of the two tests above: it names the size of the effect, so a
        // filter that silently became a no-op would fail here rather than pass both.
        assertThat(expected("lcc")).hasSize(916);
        assertThat(expected("nolcc")).hasSize(935);
    }

    @Test
    void submissionOrderChangesTheEdgeListJustAsItChangesTheSourceS() {
        List<GraphRelationship> shuffled = new ArrayList<>(Fixture.relationships());
        Collections.shuffle(shuffled, new Random(3));

        List<WeightedEdge> inOrder =
                EdgeListPreparation.prepare(Fixture.relationships(), Options.defaults());
        List<WeightedEdge> reordered = EdgeListPreparation.prepare(shuffled, Options.defaults());

        // Rule 3. The source is order-sensitive here and the port reproduces that; a port
        // that had quietly sorted its input would pass every other test and fail this one.
        assertThat(reordered).isNotEqualTo(inOrder);
        assertThat(reordered).hasSameSizeAs(inOrder);
        assertThat(reordered.stream().map(e -> e.source() + "|" + e.target()).toList())
                .isEqualTo(inOrder.stream().map(e -> e.source() + "|" + e.target()).toList());
    }

    @Test
    void tieBreakingOnIdMakesTheEdgeListIndependentOfSubmissionOrder() {
        // §4 decision 4: the opt-in repair, which the source does not have.
        List<GraphRelationship> shuffled = new ArrayList<>(Fixture.relationships());
        Collections.shuffle(shuffled, new Random(11));
        Options repaired = new Options(true, true);

        assertThat(EdgeListPreparation.prepare(shuffled, repaired))
                .isEqualTo(EdgeListPreparation.prepare(Fixture.relationships(), repaired));
    }

    @Test
    void everyDeliveryOrderOfTiedPairsGivesTheAnswerTheSourceGivesForIt() {
        // Rule 3 and rule 4 together, over the workload the benchmark uses. Six
        // relationships, four of which share a normalised pair, delivered all 720 ways.
        // The source gives six distinct edge lists; the port has to give the same six.
        //
        // This started as a benchmark row rather than a test, and it found the port picking
        // a different connected component when two tie on size: the source's tie-break is
        // node order, not alphabetical, and only a workload small enough for two components
        // to tie could tell them apart.
        JsonNode workloads = Fixture.bench("workloads.json");
        List<GraphRelationship> rows = new ArrayList<>();
        for (JsonNode workload : workloads) {
            if (workload.get("name").asText().equals("arrival-order-duplicated-pairs")) {
                workload.get("rows").forEach(r -> rows.add(new GraphRelationship(
                        r.get("id").asText(), r.get("human_readable_id").asLong(),
                        r.get("source").asText(), r.get("target").asText(),
                        r.get("description").asText(), r.get("weight").asDouble(),
                        r.get("combined_degree").asLong(), List.of())));
            }
        }
        assertThat(rows).hasSize(6);

        Set<String> answers = new TreeSet<>();
        permute(rows, new ArrayList<>(), answers);

        JsonNode expected = Fixture.bench("source-results.json")
                .get("arrival-order-duplicated-pairs");
        Set<String> want = new TreeSet<>();
        expected.get("distinct_answers").forEach(a -> want.add(a.asText()));

        assertThat(answers).isEqualTo(want);
        assertThat(answers).hasSize(6);
    }

    private static void permute(List<GraphRelationship> remaining,
                                List<GraphRelationship> delivered, Set<String> answers) {
        if (remaining.isEmpty()) {
            answers.add(String.join("\n",
                    EdgeListPreparation.prepare(delivered, Options.defaults()).stream()
                            .map(e -> e.source() + "|" + e.target() + "|" + e.weight())
                            .toList()));
            return;
        }
        for (int i = 0; i < remaining.size(); i++) {
            List<GraphRelationship> rest = new ArrayList<>(remaining);
            GraphRelationship next = rest.remove(i);
            List<GraphRelationship> withNext = new ArrayList<>(delivered);
            withNext.add(next);
            permute(rest, withNext, answers);
        }
    }

    @Test
    void whenTwoComponentsTieOnSizeTheEarliestNodeWinsAndPositionDecidesIt() {
        // Rule 4, over the three inputs probe_11.py ran because neither the fixture nor the
        // arrival-order workload could reach the tie: in both of those the largest component
        // wins outright. Two disjoint pairs tie at two nodes each, and which of them holds
        // the earliest node depends on where the deduplicated repeat ends up — the source
        // keeps the last occurrence's position, so the repeated pair moves behind the other.
        JsonNode expected = Fixture.answers("source-answers-11.json");

        assertThat(prepared(List.of(
                rel("r1", 1, "A", "B", 1.0),
                rel("r2", 2, "C", "D", 2.0),
                rel("r3", 3, "A", "B", 3.0))))
                .isEqualTo(edges(expected.get("repeat_last")));

        assertThat(prepared(List.of(
                rel("r1", 1, "A", "B", 1.0),
                rel("r3", 2, "A", "B", 3.0),
                rel("r2", 3, "C", "D", 2.0))))
                .isEqualTo(edges(expected.get("repeat_first")));

        // And size still beats position: a three-node component wins over a two-node one
        // however early the two-node one's nodes appear.
        assertThat(prepared(List.of(
                rel("r1", 1, "E", "F", 1.0),
                rel("r2", 2, "A", "B", 1.0),
                rel("r3", 3, "B", "C", 1.0),
                rel("r4", 4, "X", "Y", 1.0),
                rel("r5", 5, "Y", "Z", 1.0))))
                .isEqualTo(edges(expected.get("three_components")));
    }

    private static GraphRelationship rel(String id, long hrid, String source, String target,
                                         double weight) {
        return new GraphRelationship(id, hrid, source, target, "d", weight, 2L,
                List.of("t1"));
    }

    private static List<WeightedEdge> prepared(List<GraphRelationship> rows) {
        return EdgeListPreparation.prepare(rows, Options.defaults());
    }

    private static List<WeightedEdge> edges(JsonNode array) {
        List<WeightedEdge> out = new ArrayList<>();
        array.forEach(e -> out.add(new WeightedEdge(e.get(0).asText(), e.get(1).asText(),
                e.get(2).asDouble())));
        return out;
    }

    @Test
    void aRelationshipWithNoWeightCountsAsOne() {
        List<GraphRelationship> one = List.of(new GraphRelationship(
                "r1", 1, "B", "A", "d", null, 3L, List.of()));
        assertThat(EdgeListPreparation.prepare(one, new Options(false, false)))
                .containsExactly(new WeightedEdge("A", "B", 1.0));
    }
}
