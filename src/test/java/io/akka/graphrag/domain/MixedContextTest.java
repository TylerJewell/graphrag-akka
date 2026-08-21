package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.akka.graphrag.domain.MixedContext.SubContext;
import io.akka.graphrag.domain.SortContext.EdgeDetails;
import io.akka.graphrag.domain.SortContext.NodeContext;
import io.akka.graphrag.domain.SortContext.NodeDetails;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rules 31-33, against what the source's own {@code build_mixed_context} returned for
 * the same three sub-communities at five budgets (probe_01.py).
 *
 * <p>The five budgets are the point. One of them fits everything, one fits nothing at all, and
 * the ones between take different numbers of substitution steps — a single budget would exercise
 * one branch of three and report itself as having checked the rule.
 */
class MixedContextTest {

    private static final Tokenizer TOKENIZER = Tokenizer.cl100k();

    private static NodeContext node(String title, long id, long degree, String description,
                                    List<EdgeDetails> edges) {
        return new NodeContext(title, degree,
                new NodeDetails(id, title, description, degree), edges, null);
    }

    private static List<NodeContext> threeNodes() {
        return List.of(
                node("A", 1, 3, "alpha", List.of(
                        new EdgeDetails(10, "A", "B", "ab", 5L),
                        new EdgeDetails(11, "A", "C", "ac", 9L))),
                node("B", 2, 2, "beta", List.of(new EdgeDetails(12, "B", "C", "bc", 9L))),
                node("C", 3, 4, "gamma", List.of()));
    }

    private static List<SubContext> subContexts() {
        List<NodeContext> nodes = threeNodes();
        return List.of(
                new SubContext(1, nodes.subList(0, 1), "R1 " + "x".repeat(40), 500),
                new SubContext(2, nodes.subList(1, 2), "R2 " + "y".repeat(40), 300),
                new SubContext(3, nodes.subList(2, 3), "", 100));
    }

    @Test
    void everyBudgetSubstitutesExactlyWhereTheSourceSubstitutes() {
        JsonNode expected =
                Fixture.answers("source-answers.json").get("mixed_context/budgets");
        for (int budget : List.of(5, 20, 60, 120, 10_000)) {
            assertThat(MixedContext.build(subContexts(), TOKENIZER, budget))
                    .describedAs("budget %d", budget)
                    .isEqualTo(expected.get(String.valueOf(budget)).asText());
        }
    }

    @Test
    void theFiveBudgetsReachThreeDifferentOutcomes() {
        // Guards the test above against five budgets that all land in one branch.
        String tightest = MixedContext.build(subContexts(), TOKENIZER, 5);
        String middle = MixedContext.build(subContexts(), TOKENIZER, 60);
        String roomy = MixedContext.build(subContexts(), TOKENIZER, 10_000);

        assertThat(tightest).isEmpty();
        assertThat(middle).isNotEmpty().doesNotContain("-----Entities-----");
        assertThat(roomy).contains("----Reports-----").contains("-----Entities-----");
    }

    @Test
    void theLargestSubCommunityHavingNoReportIsWhatSeparatesEmptyFromAbsent() {
        // Rule 32, over the arrangement probe_10.py ran because the earlier one could not
        // see it: with the *biggest* sub-community reportless, treating its empty report as
        // a real one substitutes a blank row and changes the answer at every budget.
        List<NodeContext> nodes = threeNodes();
        List<SubContext> subContexts = List.of(
                new SubContext(1, nodes.subList(0, 1), "", 500),
                new SubContext(2, nodes.subList(1, 2), "R2 " + "y".repeat(40), 300),
                new SubContext(3, nodes.subList(2, 3), "R3 " + "z".repeat(40), 100));

        JsonNode expected = Fixture.answers("source-answers-10.json")
                .get("biggest_has_no_report");
        for (int budget : List.of(5, 20, 60, 120, 10_000)) {
            assertThat(MixedContext.build(subContexts, TOKENIZER, budget))
                    .describedAs("budget %d", budget)
                    .isEqualTo(expected.get(String.valueOf(budget)).asText());
        }
    }

    @Test
    void aSubCommunityWithNoReportContributesItsOwnDetail() {
        // Rule 32. Sub-community 3 has no report, so at a budget that fits everything its
        // node appears as an entity rather than as a report row.
        String roomy = MixedContext.build(subContexts(), TOKENIZER, 10_000);
        String reportsBlock = roomy.substring(0, roomy.indexOf("-----Entities-----"));
        assertThat(reportsBlock).contains("1,R1 ").doesNotContain("3,");
        assertThat(roomy).contains("3,C,gamma,4");
    }
}
