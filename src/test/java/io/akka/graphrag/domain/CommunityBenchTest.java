package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.graphrag.domain.EdgeListPreparation.Options;
import io.akka.graphrag.domain.LevelRollup.Driver;
import io.akka.graphrag.domain.LevelRollup.RolledContext;
import io.akka.graphrag.domain.LocalContextBuilder.CommunityContext;
import io.akka.graphrag.domain.Records.Community;
import io.akka.graphrag.domain.Records.GraphRelationship;
import io.akka.graphrag.domain.Records.WeightedEdge;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.zip.CRC32;

/**
 * The port's half of {@code graphrag-port/bench}. Writes {@code port-results.json} beside the
 * source's; {@code bench/compare.py} puts them together.
 *
 * <p>It runs with the rest of the suite rather than behind a flag. A benchmark nobody runs
 * goes stale, and `toolkit/test_census.py` reads a flag-gated test as one that exists and did
 * not run, which is the same shape as the fault that census exists to find. It costs about
 * seven seconds and it asserts: every checksum it writes has to equal the one the source
 * wrote for the same workload, so a harness that quietly rendered something else fails here
 * rather than publishing a ratio between two different computations.
 *
 * <p>A window is sized to run for tens of milliseconds and the figure is its total divided by
 * what was in it. The minimum of many short windows reports the best artefact rather than the
 * typical cost, and a short window that provably did the work still reads as zero from the
 * platform clock often enough to be picked.
 */
class CommunityBenchTest {

    private static final Tokenizer TOKENIZER = Tokenizer.cl100k();
    private static final Path OUT = Path.of("..", "graphrag-port", "bench",
            "port-results.json");
    private static final long WINDOW_TARGET_NS = 50_000_000L;
    private static final int WINDOWS = 7;

    private record Measured(double ns, long checksum, int items) {}

    private static long crc32(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    /** Total over a window divided by the repetitions in it, median of seven windows. */
    private static <T> Measured measure(Supplier<T> run, java.util.function.Function<T, String> render) {
        T answer = run.get();
        for (int i = 0; i < 3; i++) {
            run.get();
        }
        long start = System.nanoTime();
        run.get();
        long pilot = System.nanoTime() - start;
        if (pilot <= 0) {
            throw new IllegalStateException("a pilot measured nothing; time more per window");
        }
        int perWindow = (int) Math.max(1, Math.min(20_000, WINDOW_TARGET_NS / pilot));

        List<Double> totals = new ArrayList<>();
        for (int w = 0; w < WINDOWS; w++) {
            long windowStart = System.nanoTime();
            for (int i = 0; i < perWindow; i++) {
                run.get();
            }
            totals.add((System.nanoTime() - windowStart) / (double) perWindow);
        }
        totals.sort(Comparator.naturalOrder());
        String rendered = render.apply(answer);
        return new Measured(totals.get(totals.size() / 2), crc32(rendered),
                rendered.isEmpty() ? 0 : rendered.split("\n", -1).length);
    }

    private static List<Community> communities() {
        return CommunityAssembly.assemble(Fixture.clusters(), Fixture.entities(),
                Fixture.relationships(), new CommunityAssembly.Options("run-a", "2026-08-21"));
    }

    private static Map<Integer, List<Integer>> children(List<Community> communities) {
        Map<Integer, List<Integer>> out = new LinkedHashMap<>();
        for (Community community : communities) {
            out.put(community.community(), community.children());
        }
        return out;
    }

    private static String renderEdges(List<WeightedEdge> edges) {
        return String.join("\n", edges.stream()
                .map(e -> e.source() + "|" + e.target() + "|" + e.weight()).toList());
    }

    private static String renderContexts(List<CommunityContext> contexts) {
        return String.join("\n", contexts.stream()
                .sorted(Comparator.comparingInt(CommunityContext::level)
                        .thenComparingInt(CommunityContext::community))
                .map(c -> c.level() + "|" + c.community() + "|" + c.contextString())
                .toList());
    }

    private static String renderRolled(List<RolledContext> contexts) {
        return String.join("\n", contexts.stream()
                .sorted(Comparator.comparingInt(RolledContext::level)
                        .thenComparingInt(RolledContext::community))
                .map(c -> c.level() + "|" + c.community() + "|" + c.contextString())
                .toList());
    }

    @Test
    void writeResults() throws Exception {
        List<GraphRelationship> relationships = Fixture.relationships();
        List<Community> communities = communities();
        Map<Integer, List<Integer>> children = children(communities);

        Map<String, Object> results = new TreeMap<>();

        for (var entry : Map.of("lcc", true, "nolcc", false).entrySet()) {
            boolean lcc = entry.getValue();
            Measured m = measure(
                    () -> EdgeListPreparation.prepare(relationships, new Options(lcc, false)),
                    CommunityBenchTest::renderEdges);
            results.put("edge-list-" + entry.getKey(), row(m));
        }

        for (int budget : List.of(16_000, 800, 200)) {
            Measured m = measure(
                    () -> LocalContextBuilder.build(communities, Fixture.entities(),
                            relationships, TOKENIZER, budget),
                    CommunityBenchTest::renderContexts);
            results.put("local-context-" + budget, row(m));
        }

        for (int budget : List.of(16_000, 200)) {
            List<CommunityContext> local = LocalContextBuilder.build(communities,
                    Fixture.entities(), relationships, TOKENIZER, budget);
            for (Driver driver : Driver.values()) {
                Measured m = measure(
                        () -> LevelRollup.roll(local, children, TOKENIZER, budget, driver,
                                LevelRollup.ReportWriter.deterministic()).contexts(),
                        CommunityBenchTest::renderRolled);
                String tag = driver == Driver.SOURCE_ORDER ? "source-order" : "fed-forward";
                results.put("rollup-" + tag + "-" + budget, row(m));
            }
        }

        // Both sides count cl100k tokens and neither can avoid it, so this is not work
        // one side never attempts — but the trimming loop counts once per edge per
        // community, so the row belongs next to the ratio a reader is about to draw.
        List<String> contextStrings = LocalContextBuilder.build(communities,
                Fixture.entities(), relationships, TOKENIZER, 16_000).stream()
                .map(CommunityContext::contextString).toList();
        Measured tokenizing = measure(
                () -> contextStrings.stream().mapToInt(TOKENIZER::countTokens).sum(),
                String::valueOf);
        Map<String, Object> tokenizeRow = new LinkedHashMap<>();
        tokenizeRow.put("ns", tokenizing.ns());
        tokenizeRow.put("checksum",
                contextStrings.stream().mapToInt(TOKENIZER::countTokens).sum());
        tokenizeRow.put("items", contextStrings.size());
        results.put("tokenize-contexts", tokenizeRow);

        results.putAll(correctnessExperiments());

        Files.createDirectories(OUT.getParent());
        Files.writeString(OUT, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(results));
        results.forEach((name, r) -> System.out.println("  " + name + ": " + r));

        var source = Fixture.bench("source-results.json");
        List<String> disagreements = new ArrayList<>();
        results.forEach((name, value) -> {
            var theirs = source.get(name);
            if (theirs == null) {
                disagreements.add(name + ": the source side has no result for it");
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> mine = (Map<String, Object>) value;
            if (mine.containsKey("checksum")
                    && !theirs.get("checksum").asText().equals(
                            String.valueOf(mine.get("checksum")))) {
                disagreements.add(name + ": source " + theirs.get("checksum").asText()
                        + ", port " + mine.get("checksum"));
            }
            if (mine.containsKey("distinct_count")
                    && theirs.get("distinct_count").asInt()
                            != (int) mine.get("distinct_count")) {
                disagreements.add(name + ": source found "
                        + theirs.get("distinct_count").asInt() + " distinct answers, port "
                        + mine.get("distinct_count"));
            }
        });
        org.assertj.core.api.Assertions.assertThat(disagreements).isEmpty();
    }

    /**
     * The two questions a table of single answers cannot ask. Arrival order: every one of the
     * 720 delivery orders of six relationships, four of which share a normalised pair with
     * another — so the dedup can resolve them two ways and the edge list must move. And the
     * same six rows cut into one batch and into three, where the answer must not move.
     */
    private Map<String, Object> correctnessExperiments() throws Exception {
        Map<String, Object> out = new TreeMap<>();
        var workloads = new ObjectMapper().readTree(Files.readString(
                Path.of("..", "graphrag-port", "bench", "workloads.json")));

        for (var workload : workloads) {
            String name = workload.get("name").asText();
            if (workload.has("rows")) {
                List<GraphRelationship> rows = new ArrayList<>();
                workload.get("rows").forEach(r -> rows.add(relationship(r)));
                java.util.SortedSet<String> answers = new java.util.TreeSet<>();
                permute(rows, new ArrayList<>(), answers);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("orders_run", factorial(rows.size()));
                result.put("distinct_answers", new ArrayList<>(answers));
                result.put("distinct_count", answers.size());
                out.put(name, result);
            } else if (workload.has("batches")) {
                List<GraphRelationship> rows = new ArrayList<>();
                workload.get("batches").forEach(
                        batch -> batch.forEach(r -> rows.add(relationship(r))));
                String answer = renderEdges(
                        EdgeListPreparation.prepare(rows, new Options(true, false)));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("checksum", crc32(answer));
                result.put("answer", answer);
                out.put(name, result);
            }
        }
        return out;
    }

    private static void permute(List<GraphRelationship> remaining,
                                List<GraphRelationship> delivered,
                                java.util.SortedSet<String> answers) {
        if (remaining.isEmpty()) {
            answers.add(renderEdges(
                    EdgeListPreparation.prepare(delivered, new Options(true, false))));
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

    private static long factorial(int n) {
        long out = 1;
        for (int i = 2; i <= n; i++) {
            out *= i;
        }
        return out;
    }

    private static GraphRelationship relationship(com.fasterxml.jackson.databind.JsonNode r) {
        List<String> textUnits = new ArrayList<>();
        if (r.has("text_unit_ids")) {
            r.get("text_unit_ids").forEach(t -> textUnits.add(t.asText()));
        }
        return new GraphRelationship(
                r.get("id").asText(),
                r.get("human_readable_id").asLong(),
                r.get("source").asText(),
                r.get("target").asText(),
                r.get("description").asText(),
                r.get("weight").asDouble(),
                r.get("combined_degree").asLong(),
                textUnits);
    }

    private static Map<String, Object> row(Measured m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ns", m.ns());
        out.put("checksum", m.checksum());
        out.put("items", m.items());
        return out;
    }
}
