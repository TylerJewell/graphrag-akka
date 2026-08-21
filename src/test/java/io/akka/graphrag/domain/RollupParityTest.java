package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.graphrag.domain.LevelRollup.Driver;
import io.akka.graphrag.domain.LevelRollup.RolledContext;
import io.akka.graphrag.domain.LocalContextBuilder.CommunityContext;
import io.akka.graphrag.domain.Records.Community;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rules 27-35 and rule 29a, against both of the drivers probe_08.py ran the
 * source's own {@code build_level_context} under.
 *
 * <p>The default driver is graphrag's: no level ever sees a report, so every over-long context is
 * re-trimmed. The other one feeds reports forward, which is what the substitution machinery was
 * written for and what no caller in the source does. Both are compared, and so is the gap between
 * them — 0 rows at a budget of 16,000, 2 at 800, 14 at 200 — because a port that had quietly
 * implemented only one of them would still pass a comparison against that one.
 */
class RollupParityTest {

    private static final Tokenizer TOKENIZER = Tokenizer.cl100k();
    private static final List<Integer> BUDGETS = List.of(16_000, 800, 200);

    private static List<Community> communities() {
        return CommunityAssembly.assemble(
                Fixture.clusters(), Fixture.entities(), Fixture.relationships(),
                new CommunityAssembly.Options("run-a", "2026-08-21"));
    }

    private static Map<Integer, List<Integer>> children() {
        Map<Integer, List<Integer>> out = new LinkedHashMap<>();
        for (Community community : communities()) {
            out.put(community.community(), community.children());
        }
        return out;
    }

    private static List<CommunityContext> local(int budget) {
        return LocalContextBuilder.build(communities(), Fixture.entities(),
                Fixture.relationships(), TOKENIZER, budget);
    }

    private static Map<String, String> rolled(int budget, Driver driver) {
        Map<String, String> out = new TreeMap<>();
        for (RolledContext c : LevelRollup.roll(local(budget), children(), TOKENIZER, budget,
                driver, LevelRollup.ReportWriter.deterministic()).contexts()) {
            out.put(c.level() + ":" + c.community(),
                    c.contextSize() + "|" + c.contextString());
        }
        return out;
    }

    private static Map<String, String> expected(int budget, String key) {
        Map<String, String> out = new TreeMap<>();
        for (JsonNode row : Fixture.answers("source-answers-8.json")
                .get(key + "/" + budget)) {
            out.put(row.get("level").asInt() + ":" + row.get("community").asInt(),
                    row.get("context_size").asInt() + "|"
                            + row.get("context_string").asText());
        }
        return out;
    }

    @Test
    void theDefaultDriverMatchesTheSourceAtEveryBudget() {
        List<String> disagreements = new ArrayList<>();
        for (int budget : BUDGETS) {
            Map<String, String> want = expected(budget, "rollup_source_order");
            Map<String, String> got = rolled(budget, Driver.SOURCE_ORDER);
            if (!want.keySet().equals(got.keySet())) {
                disagreements.add("budget " + budget + ": " + got.size()
                        + " rows against the source's " + want.size());
                continue;
            }
            for (var entry : want.entrySet()) {
                if (!entry.getValue().equals(got.get(entry.getKey()))) {
                    disagreements.add("budget " + budget + " " + entry.getKey());
                }
            }
        }
        assertThat(disagreements).isEmpty();
    }

    @Test
    void theFedForwardDriverMatchesTheSourceSOwnFunctionDrivenThatWay() {
        List<String> disagreements = new ArrayList<>();
        for (int budget : BUDGETS) {
            Map<String, String> want = expected(budget, "rollup_fed_forward");
            Map<String, String> got = rolled(budget, Driver.FEED_REPORTS_FORWARD);
            if (!want.keySet().equals(got.keySet())) {
                disagreements.add("budget " + budget + ": " + got.size()
                        + " rows against " + want.size());
                continue;
            }
            for (var entry : want.entrySet()) {
                if (!entry.getValue().equals(got.get(entry.getKey()))) {
                    disagreements.add("budget " + budget + " " + entry.getKey());
                }
            }
        }
        assertThat(disagreements).isEmpty();
    }

    @Test
    void theTwoDriversDifferByExactlyAsMuchAsTheyDoInTheSource() {
        // Rule 29a. Without this the two tests above could both pass with one driver
        // implemented twice.
        assertThat(differingRows(16_000)).isZero();
        assertThat(differingRows(800)).isEqualTo(2);
        assertThat(differingRows(200)).isEqualTo(14);
    }

    private static long differingRows(int budget) {
        Map<String, String> source = rolled(budget, Driver.SOURCE_ORDER);
        Map<String, String> fed = rolled(budget, Driver.FEED_REPORTS_FORWARD);
        return source.entrySet().stream()
                .filter(e -> !e.getValue().equals(fed.get(e.getKey())))
                .count();
    }

    @Test
    void everyRolledContextFitsItsBudgetUnlessNothingCouldMakeItFit() {
        // Rule 35 as a property. A context that still overruns can only be one whose
        // smallest possible rendering overruns (rule 25), so it is named rather than
        // allowed silently.
        for (int budget : BUDGETS) {
            for (RolledContext c : LevelRollup.roll(local(budget), children(), TOKENIZER,
                    budget, Driver.SOURCE_ORDER,
                    LevelRollup.ReportWriter.deterministic()).contexts()) {
                if (c.contextSize() > budget) {
                    String smallest = SortContext.sortContext(
                            local(budget).stream()
                                    .filter(l -> l.community() == c.community()
                                            && l.level() == c.level())
                                    .findFirst().orElseThrow().nodes(),
                            TOKENIZER, budget);
                    assertThat(TOKENIZER.countTokens(smallest))
                            .describedAs("budget %d community %d:%d", budget, c.level(),
                                    c.community())
                            .isGreaterThan(budget);
                }
            }
        }
    }

    @Test
    void aReportIsWrittenForEveryContext() {
        LevelRollup.Result result = LevelRollup.roll(local(800), children(), TOKENIZER, 800,
                Driver.SOURCE_ORDER, LevelRollup.ReportWriter.deterministic());
        assertThat(result.reports()).hasSameSizeAs(result.contexts());
        assertThat(result.reports()).allMatch(r -> r.fullContent().startsWith("REPORT c="));
    }

    @Test
    void levelsAreVisitedHighestFirstWithTheSentinelDropped() {
        // Rule 27, over the seven cases probe_02.py ran get_levels against.
        JsonNode expected = Fixture.answers("source-answers-2.json").get("get_levels");
        Map<String, List<Integer>> cases = new LinkedHashMap<>();
        cases.put("plain", List.of(0, 1, 2));
        cases.put("with_minus_one", List.of(-1, 0, 1));
        cases.put("unsorted_with_repeats", List.of(2, 0, 2, 1, 0));
        cases.put("only_minus_one", List.of(-1, -1));
        cases.put("floats", List.of(0, 1, 2));
        cases.put("empty", List.of());

        for (var entry : cases.entrySet()) {
            List<Integer> want = new ArrayList<>();
            expected.get(entry.getKey()).forEach(n -> want.add(n.asInt()));
            assertThat(Levels.descending(entry.getValue()))
                    .describedAs(entry.getKey()).isEqualTo(want);
        }
        List<Integer> withNull = new ArrayList<>(List.of(0));
        withNull.add(null);
        withNull.add(2);
        List<Integer> wantNull = new ArrayList<>();
        expected.get("with_nan").forEach(n -> wantNull.add(n.asInt()));
        assertThat(Levels.descending(withNull)).isEqualTo(wantNull);
    }
}
