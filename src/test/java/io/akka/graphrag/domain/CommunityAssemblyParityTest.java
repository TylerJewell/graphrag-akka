package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.graphrag.domain.CommunityAssembly.Options;
import io.akka.graphrag.domain.Records.Community;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rules 6-12 and §4 decision 3, against the 121 community rows the source's
 * {@code create_communities} produced for the same graph and hierarchy (probe_01.py).
 * {@code id} and {@code period} are excluded because the source does not derive them from the
 * graph — that exclusion is decision 3, and the reproducibility it buys is checked separately.
 */
class CommunityAssemblyParityTest {

    private static final Options OPTIONS = new Options("run-a", "2026-08-21");

    private static List<Community> assembled() {
        return CommunityAssembly.assemble(
                Fixture.clusters(), Fixture.entities(), Fixture.relationships(), OPTIONS);
    }

    private static Map<Integer, Map<String, Object>> expected() {
        Map<Integer, Map<String, Object>> out = new TreeMap<>();
        for (JsonNode row : Fixture.answers("source-answers.json").get("communities")) {
            Map<String, Object> fields = new TreeMap<>();
            fields.put("level", row.get("level").asInt());
            fields.put("parent", row.get("parent").asInt());
            fields.put("title", row.get("title").asText());
            fields.put("human_readable_id", row.get("human_readable_id").asInt());
            fields.put("size", row.get("size").asInt());
            fields.put("children", sortedInts(row.get("children")));
            fields.put("entity_ids", strings(row.get("entity_ids")));
            fields.put("relationship_ids", strings(row.get("relationship_ids")));
            fields.put("text_unit_ids", strings(row.get("text_unit_ids")));
            out.put(row.get("community").asInt(), fields);
        }
        return out;
    }

    private static Map<Integer, Map<String, Object>> actual() {
        Map<Integer, Map<String, Object>> out = new TreeMap<>();
        for (Community c : assembled()) {
            Map<String, Object> fields = new TreeMap<>();
            fields.put("level", c.level());
            fields.put("parent", c.parent());
            fields.put("title", c.title());
            fields.put("human_readable_id", (int) c.humanReadableId());
            fields.put("size", c.size());
            fields.put("children", c.children().stream().sorted().toList());
            fields.put("entity_ids", c.entityIds());
            fields.put("relationship_ids", c.relationshipIds());
            fields.put("text_unit_ids", c.textUnitIds());
            out.put(c.community(), fields);
        }
        return out;
    }

    private static List<Integer> sortedInts(JsonNode array) {
        List<Integer> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asInt()));
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void everyCommunityMatchesTheSourceFieldForField() {
        Map<Integer, Map<String, Object>> want = expected();
        Map<Integer, Map<String, Object>> got = actual();

        assertThat(got.keySet()).isEqualTo(want.keySet());
        List<String> disagreements = new ArrayList<>();
        for (var entry : want.entrySet()) {
            Map<String, Object> mine = got.get(entry.getKey());
            for (var field : entry.getValue().entrySet()) {
                if (!field.getValue().equals(mine.get(field.getKey()))) {
                    disagreements.add("community " + entry.getKey() + " " + field.getKey()
                            + ": source " + field.getValue() + " but port "
                            + mine.get(field.getKey()));
                }
            }
        }
        assertThat(disagreements).isEmpty();
    }

    @Test
    void theSourceProducedOneHundredAndTwentyOneCommunitiesAcrossThreeLevels() {
        // Names the size of the comparison, so a fixture that silently emptied would fail
        // here rather than let the field-for-field test pass over nothing.
        assertThat(expected()).hasSize(121);
        assertThat(expected().values().stream().map(f -> f.get("level")).distinct().sorted())
                .containsExactly(0, 1, 2);
    }

    @Test
    void theSameGraphRunTwiceGivesTheSameCommunityIds() {
        // §4 decision 3: where the source stamps a fresh uuid4 per row, this derives the id.
        assertThat(assembled().stream().map(Community::id).toList())
                .isEqualTo(assembled().stream().map(Community::id).toList())
                .allMatch(id -> id.startsWith("run-a-"));
    }

    @Test
    void aClusterWhoseMembersShareNoEdgeProducesNoRow() {
        // Rule 12, driven directly rather than inferred from the fixture happening not to
        // contain one.
        List<Records.ClusterAssignment> clusters = List.of(
                new Records.ClusterAssignment(0, 7, -1, List.of("A", "B")));
        List<Records.GraphEntity> entities = List.of(
                new Records.GraphEntity("e1", 1, "A", "a", 0, List.of()),
                new Records.GraphEntity("e2", 2, "B", "b", 0, List.of()));
        assertThat(CommunityAssembly.assemble(clusters, entities, List.of(), OPTIONS)).isEmpty();
    }

    @Test
    void anEdgeCrossingCommunitiesIsNotCountedForEither() {
        // Rule 8, stated as its own case: membership is per community, not per level.
        List<Records.ClusterAssignment> clusters = List.of(
                new Records.ClusterAssignment(0, 1, -1, List.of("A", "B")),
                new Records.ClusterAssignment(0, 2, -1, List.of("C", "D")));
        List<Records.GraphEntity> entities = List.of(
                new Records.GraphEntity("e1", 1, "A", "a", 1, List.of()),
                new Records.GraphEntity("e2", 2, "B", "b", 1, List.of()),
                new Records.GraphEntity("e3", 3, "C", "c", 1, List.of()),
                new Records.GraphEntity("e4", 4, "D", "d", 1, List.of()));
        List<Records.GraphRelationship> relationships = List.of(
                new Records.GraphRelationship("r1", 1, "A", "B", "in", 1.0, 2L, List.of("t1")),
                new Records.GraphRelationship("r2", 2, "B", "C", "across", 1.0, 2L,
                        List.of("t2")),
                new Records.GraphRelationship("r3", 3, "C", "D", "in", 1.0, 2L, List.of("t3")));

        List<Community> out =
                CommunityAssembly.assemble(clusters, entities, relationships, OPTIONS);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).relationshipIds()).containsExactly("r1");
        assertThat(out.get(0).textUnitIds()).containsExactly("t1");
        assertThat(out.get(1).relationshipIds()).containsExactly("r3");
        assertThat(out.get(1).textUnitIds()).containsExactly("t3");
    }
}
