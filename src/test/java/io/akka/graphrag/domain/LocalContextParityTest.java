package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.graphrag.domain.LocalContextBuilder.CommunityContext;
import io.akka.graphrag.domain.Records.Community;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rules 17-26, against the 121 contexts the source's own {@code build_local_context}
 * produced at each of three budgets (probe_08.py).
 *
 * <p>Three budgets rather than one because the behaviour being compared is a boundary: at 16,000
 * tokens nothing overruns, at 800 three communities do, at 200 fifty do. A single budget would
 * agree on every row and have compared nothing about trimming.
 */
class LocalContextParityTest {

    private static final Tokenizer TOKENIZER = Tokenizer.cl100k();
    private static final List<Integer> BUDGETS = List.of(16_000, 800, 200);
    // A block heading ends in a bare newline and a CSV row in a CRLF, and the two sitting
    // next to each other in one literal is where a rendering bug would hide.
    private static final String LF = "\n";
    private static final String CRLF = "\r\n";

    private static List<Community> communities() {
        return CommunityAssembly.assemble(
                Fixture.clusters(), Fixture.entities(), Fixture.relationships(),
                new CommunityAssembly.Options("run-a", "2026-08-21"));
    }

    private static List<CommunityContext> built(int budget) {
        return LocalContextBuilder.build(communities(), Fixture.entities(),
                Fixture.relationships(), TOKENIZER, budget);
    }

    private record Expected(String contextString, int contextSize, boolean exceeds) {}

    private static Map<String, Expected> expected(int budget) {
        Map<String, Expected> out = new TreeMap<>();
        for (JsonNode row : Fixture.answers("source-answers-8.json")
                .get("local_context/" + budget)) {
            out.put(row.get("level").asInt() + ":" + row.get("community").asInt(),
                    new Expected(row.get("context_string").asText(),
                            row.get("context_size").asInt(),
                            row.get("context_exceed_limit").asBoolean()));
        }
        return out;
    }

    @Test
    void everyContextAtEveryBudgetMatchesTheSource() {
        List<String> disagreements = new ArrayList<>();
        for (int budget : BUDGETS) {
            Map<String, Expected> want = expected(budget);
            Map<String, Expected> got = new TreeMap<>();
            for (CommunityContext c : built(budget)) {
                got.put(c.level() + ":" + c.community(),
                        new Expected(c.contextString(), c.contextSize(), c.exceedsBudget()));
            }
            if (!want.keySet().equals(got.keySet())) {
                disagreements.add("budget " + budget + " covers " + got.keySet().size()
                        + " communities, source " + want.keySet().size());
                continue;
            }
            for (var entry : want.entrySet()) {
                Expected mine = got.get(entry.getKey());
                if (!entry.getValue().equals(mine)) {
                    disagreements.add("budget " + budget + " community " + entry.getKey()
                            + ": source size " + entry.getValue().contextSize()
                            + " exceeds " + entry.getValue().exceeds()
                            + ", port size " + mine.contextSize()
                            + " exceeds " + mine.exceeds()
                            + (entry.getValue().contextString().equals(mine.contextString())
                            ? "" : ", and the strings differ"));
                }
            }
        }
        assertThat(disagreements).isEmpty();
    }

    @Test
    void thethreeBudgetsExerciseThreeDifferentAmountsOfTrimming() {
        // Without this the test above could pass over three identical comparisons.
        assertThat(overrunning(16_000)).isZero();
        assertThat(overrunning(800)).isEqualTo(3);
        assertThat(overrunning(200)).isEqualTo(50);
    }

    private static long overrunning(int budget) {
        return expected(budget).values().stream().filter(Expected::exceeds).count();
    }

    @Test
    void noNodeCarriesMoreThanOneEdge() {
        // Rule 18, as the property probe_05.py measured rather than as a string comparison.
        assertThat(built(16_000).stream()
                .flatMap(c -> c.nodes().stream())
                .mapToInt(n -> n.edgeDetails().size())
                .max().orElseThrow()).isEqualTo(1);
    }

    @Test
    void someEdgesInAContextHaveAnEndOutsideItsCommunity() {
        // Rule 17. probe_05.py counted 223 such edges across the three levels; a port that
        // restricted candidacy to the community would find none.
        Map<Integer, Map<Integer, java.util.Set<String>>> members = new TreeMap<>();
        for (Community community : communities()) {
            java.util.Set<String> titles = new java.util.HashSet<>();
            for (var entity : Fixture.entities()) {
                if (community.entityIds().contains(entity.id())) {
                    titles.add(entity.title());
                }
            }
            members.computeIfAbsent(community.level(), k -> new TreeMap<>())
                    .put(community.community(), titles);
        }

        long outside = 0;
        for (CommunityContext context : built(16_000)) {
            var titles = members.get(context.level()).get(context.community());
            for (var node : context.nodes()) {
                for (var edge : node.edgeDetails()) {
                    if (!titles.contains(edge.source()) || !titles.contains(edge.target())) {
                        outside++;
                    }
                }
            }
        }
        assertThat(outside).isEqualTo(223);
    }

    @Test
    void theSourceSContextsAreTheSameWithClaimsSuppliedAsWithout() {
        // Rule 20. The port does not attach claims to a local context; what makes that the
        // same answer rather than a shortcut is probe_06.py's measurement, asserted here so
        // the justification lives with the code that relies on it. Test one above already
        // compares the port against the source's claims-free run, and this says that run and
        // the claims-supplied run are the same 121 contexts.
        JsonNode claims = Fixture.answers("source-answers-6.json");
        assertThat(claims.get("covariate_rows").asInt()).isEqualTo(406);
        assertThat(claims.get("claim_subjects_that_are_entity_titles").asInt())
                .isEqualTo(134);
        assertThat(claims.get("contexts_compared").asInt()).isEqualTo(121);
        assertThat(claims.get("contexts_changed_by_supplying_claims").asInt()).isZero();
        assertThat(claims.get("contexts_containing_a_claims_block").asInt()).isZero();
    }

    @Test
    void theClaimsBlockRendersWhenAContextIsAssembledWithOne() {
        // The block the source cannot reach is still reachable through this port's own
        // renderer, so it is checked rather than left as untested code.
        SortContext.EdgeDetails edge =
                new SortContext.EdgeDetails(1, "A", "B", "ab", 2L);
        SortContext.NodeContext a = new SortContext.NodeContext("A", 1,
                new SortContext.NodeDetails(1, "A", "a", 1), List.of(edge),
                List.of(new SortContext.ClaimDetails(9, "A", "FACT", "TRUE", "a claim")));
        SortContext.NodeContext b = new SortContext.NodeContext("B", 1,
                new SortContext.NodeDetails(2, "B", "b", 1), List.of(), null);

        assertThat(SortContext.sortContext(List.of(a, b), TOKENIZER))
                .contains("-----Claims-----" + LF
                        + "human_readable_id,subject_id,type,status,description" + CRLF
                        + "9,A,FACT,TRUE,a claim" + CRLF);
    }
}
