package io.akka.graphrag.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.graphrag.domain.Records.Claim;
import io.akka.graphrag.domain.Records.ClusterAssignment;
import io.akka.graphrag.domain.Records.GraphEntity;
import io.akka.graphrag.domain.Records.GraphRelationship;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The source's own test fixture and the answers its code gave for it, as written out by
 * {@code graphrag-port/probes}. Every parity test reads from here rather than from a copy,
 * so re-running a probe is what changes an expectation.
 */
public final class Fixture {

    private Fixture() {}

    private static final Path ROOT = Path.of("..", "graphrag-port", "answers");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonNode answers(String file) {
        try {
            return MAPPER.readTree(Files.readString(ROOT.resolve(file)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "run graphrag-port/probes/probe_0N.py first: " + ROOT.resolve(file), e);
        }
    }

    /** A file from the port's bench directory rather than its answers directory. */
    public static JsonNode bench(String file) {
        try {
            return MAPPER.readTree(Files.readString(
                    ROOT.getParent().resolve("bench").resolve(file)));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "run graphrag-port/bench/bench_source.py first: " + file, e);
        }
    }

    private static JsonNode fixture(String name) {
        return answers("fixture/" + name + ".json");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        JsonNode value = node.get(field);
        if (value != null && value.isArray()) {
            value.forEach(v -> out.add(v.asText()));
        }
        return out;
    }

    public static List<GraphEntity> entities() {
        List<GraphEntity> out = new ArrayList<>();
        for (JsonNode row : fixture("entities")) {
            out.add(new GraphEntity(
                    text(row, "id"),
                    row.get("human_readable_id").asLong(),
                    text(row, "title"),
                    text(row, "description"),
                    row.get("degree").asLong(),
                    strings(row, "text_unit_ids")));
        }
        return out;
    }

    public static List<GraphRelationship> relationships() {
        List<GraphRelationship> out = new ArrayList<>();
        for (JsonNode row : fixture("relationships")) {
            JsonNode weight = row.get("weight");
            JsonNode degree = row.get("combined_degree");
            out.add(new GraphRelationship(
                    text(row, "id"),
                    row.get("human_readable_id").asLong(),
                    text(row, "source"),
                    text(row, "target"),
                    text(row, "description"),
                    weight == null || weight.isNull() ? null : weight.asDouble(),
                    degree == null || degree.isNull() ? null : degree.asLong(),
                    strings(row, "text_unit_ids")));
        }
        return out;
    }

    public static List<Claim> claims() {
        List<Claim> out = new ArrayList<>();
        for (JsonNode row : fixture("covariates")) {
            out.add(new Claim(
                    text(row, "id"),
                    row.get("human_readable_id").asLong(),
                    text(row, "subject_id"),
                    text(row, "type"),
                    text(row, "status"),
                    text(row, "description")));
        }
        return out;
    }

    /** The hierarchy the source's Leiden run produced at {@code max_cluster_size=10, seed=7}. */
    public static List<ClusterAssignment> clusters() {
        List<ClusterAssignment> out = new ArrayList<>();
        for (JsonNode row : answers("source-answers.json").get("clusters/d10_lcc_s7")) {
            List<String> titles = new ArrayList<>();
            row.get("titles").forEach(t -> titles.add(t.asText()));
            out.add(new ClusterAssignment(
                    row.get("level").asInt(),
                    row.get("community").asInt(),
                    row.get("parent").asInt(),
                    titles));
        }
        return out;
    }
}
