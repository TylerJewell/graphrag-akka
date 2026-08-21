package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.graphrag.domain.SortContext.EdgeDetails;
import io.akka.graphrag.domain.SortContext.NodeContext;
import io.akka.graphrag.domain.SortContext.NodeDetails;
import io.akka.graphrag.domain.SortContext.SubCommunityReport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC-001 §3 rules 21-26 and 23, against what the source's own {@code sort_context} returned
 * for the same inputs (probes 01 and 02).
 */
class SortContextTest {

    private static final Tokenizer TOKENIZER = Tokenizer.cl100k();
    private static final Path PORT = Path.of("..", "graphrag-port", "answers");

    private static JsonNode answers(String file) throws Exception {
        return new ObjectMapper().readTree(Files.readString(PORT.resolve(file)));
    }

    private static NodeContext node(String title, long id, long degree, String description,
                                    List<EdgeDetails> edges) {
        return new NodeContext(title, degree,
                new NodeDetails(id, title, description, degree), edges, null);
    }

    private static EdgeDetails edge(long id, String source, String target, String description,
                                    Long degree) {
        return new EdgeDetails(id, source, target, description, degree);
    }

    /** The three-node input probe_01.py fed to the source. */
    private static List<NodeContext> threeNodes() {
        return List.of(
                node("A", 1, 3, "alpha", List.of(
                        edge(10, "A", "B", "ab", 5L),
                        edge(11, "A", "C", "ac", 9L))),
                node("B", 2, 2, "beta", List.of(edge(12, "B", "C", "bc", 9L))),
                node("C", 3, 4, "gamma", List.of()));
    }

    @Test
    void anUnboundedContextMatchesTheSource() throws Exception {
        assertThat(SortContext.sortContext(threeNodes(), TOKENIZER))
                .isEqualTo(answers("source-answers.json").get("sort_context/unbounded").asText());
    }

    @Test
    void everyBudgetTrimsWhereTheSourceTrims() throws Exception {
        JsonNode expected = answers("source-answers.json").get("sort_context/budgets");
        for (int budget : List.of(10, 40, 80, 200, 10_000)) {
            assertThat(SortContext.sortContext(threeNodes(), TOKENIZER, budget))
                    .describedAs("budget %d", budget)
                    .isEqualTo(expected.get(String.valueOf(budget)).asText());
        }
    }

    @Test
    void aBudgetTooSmallForOneEdgeStillReturnsTheWholeString() throws Exception {
        // Rule 25 stated as a property rather than a fixture: the smallest budget in the
        // sweep above is 10 and the answer it produces is far longer than that.
        String tightest = SortContext.sortContext(threeNodes(), TOKENIZER, 10);
        assertThat(tightest).isNotEmpty();
        assertThat(TOKENIZER.countTokens(tightest)).isGreaterThan(10);
    }

    @Test
    void aReportsBlockLeadsAndMatchesTheSource() throws Exception {
        assertThat(SortContext.sortContext(threeNodes(), TOKENIZER,
                List.of(new SubCommunityReport(4, "sub four")), null))
                .isEqualTo(answers("source-answers.json")
                        .get("sort_context/with_reports").asText());
    }

    @Test
    void anEmptyInputIsTheEmptyString() throws Exception {
        assertThat(SortContext.sortContext(List.of(), TOKENIZER))
                .isEqualTo(answers("source-answers.json").get("sort_context/empty").asText());
    }

    @Test
    void everyNodeAndEdgeShapeMatchesTheSource() throws Exception {
        JsonNode expected = answers("source-answers-2.json").get("sort_context/node_shapes");
        EdgeDetails ab = edge(1, "A", "B", "ab", 4L);

        assertThat(SortContext.sortContext(List.of(), TOKENIZER))
                .isEqualTo(expected.get("no_nodes").asText());
        assertThat(SortContext.sortContext(List.of(node("A", 1, 0, "a", List.of())), TOKENIZER))
                .isEqualTo(expected.get("one_node_no_edges").asText());
        assertThat(SortContext.sortContext(List.of(
                node("A", 1, 0, "a", List.of()), node("B", 2, 0, "b", List.of())), TOKENIZER))
                .isEqualTo(expected.get("two_nodes_no_edges").asText());
        assertThat(SortContext.sortContext(List.of(
                node("A", 1, 1, "a", List.of(ab)), node("B", 2, 1, "b", List.of())), TOKENIZER))
                .isEqualTo(expected.get("two_nodes_one_edge").asText());
        assertThat(SortContext.sortContext(
                List.of(node("A", 1, 1, "a", List.of(ab))), TOKENIZER))
                .isEqualTo(expected.get("edge_naming_absent_node").asText());
    }

    @Test
    void tiesBreakOnEdgeIdTheWayTheSourceBreaksThem() throws Exception {
        List<NodeContext> context = List.of(
                node("A", 1, 3, "a", List.of(
                        edge(3, "A", "B", "third", 5L),
                        edge(1, "A", "C", "first", 5L),
                        edge(2, "A", "D", "second", 9L))),
                node("B", 2, 1, "b", List.of()),
                node("C", 3, 1, "c", List.of()),
                node("D", 4, 1, "d", List.of()));
        assertThat(SortContext.sortContext(context, TOKENIZER))
                .isEqualTo(answers("source-answers-2.json")
                        .get("sort_context/tie_breaking").asText());
    }

    @Test
    void aMissingDegreeSortsLastAndFloatsItsColumn() throws Exception {
        List<NodeContext> context = List.of(
                node("A", 1, 2, "a", List.of(
                        edge(1, "A", "B", "no deg", null),
                        edge(2, "A", "C", "has deg", 1L))),
                node("B", 2, 1, "b", List.of()),
                node("C", 3, 1, "c", List.of()));
        assertThat(SortContext.sortContext(context, TOKENIZER))
                .isEqualTo(answers("source-answers-2.json")
                        .get("sort_context/missing_combined_degree").asText());
    }
}
