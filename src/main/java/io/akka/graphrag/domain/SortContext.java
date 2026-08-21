package io.akka.graphrag.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a community's nodes and edges into the context string a report is written from —
 * SPEC-001 §3 rules 21-26.
 *
 * <p>The walk is edge-driven: entities arrive because an edge named them, so an entity no edge
 * reaches never appears and a community with no edges produces the empty string rather than an
 * empty table (rule 23). The string is rebuilt and re-counted after every edge, and the first
 * rebuild that overruns the budget is thrown away — but if even the first overruns, the
 * untrimmed string is returned rather than nothing (rule 25).
 */
public final class SortContext {

    private SortContext() {}

    public record NodeDetails(
            long humanReadableId, String title, String description, long degree) {

        Map<String, Object> asRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("human_readable_id", humanReadableId);
            row.put("title", title);
            row.put("description", description);
            row.put("degree", degree);
            return row;
        }
    }

    public record EdgeDetails(
            long humanReadableId, String source, String target, String description,
            Long combinedDegree) {

        Map<String, Object> asRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("human_readable_id", humanReadableId);
            row.put("source", source);
            row.put("target", target);
            row.put("description", description);
            row.put("combined_degree", combinedDegree);
            return row;
        }
    }

    public record ClaimDetails(
            long humanReadableId, String subjectId, String type, String status,
            String description) {

        Map<String, Object> asRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("human_readable_id", humanReadableId);
            row.put("subject_id", subjectId);
            row.put("type", type);
            row.put("status", status);
            row.put("description", description);
            return row;
        }
    }

    /**
     * One node's slice of a community's context. {@code edgeDetails} holds at most one edge for a
     * context built by {@link LocalContextBuilder} (§3 rule 18); a caller assembling one directly
     * may pass more.
     */
    public record NodeContext(
            String title,
            long degree,
            NodeDetails nodeDetails,
            List<EdgeDetails> edgeDetails,
            List<ClaimDetails> claimDetails) {

        public NodeContext {
            edgeDetails = edgeDetails == null ? List.of() : List.copyOf(edgeDetails);
            claimDetails = claimDetails == null ? null : List.copyOf(claimDetails);
        }
    }

    /** A sub-community's finished report, as the Reports block renders it. */
    public record SubCommunityReport(int community, String fullContent) {

        Map<String, Object> asRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("community", community);
            row.put("full_content", fullContent);
            return row;
        }
    }

    public static String sortContext(List<NodeContext> localContext, Tokenizer tokenizer) {
        return sortContext(localContext, tokenizer, List.of(), null);
    }

    public static String sortContext(
            List<NodeContext> localContext, Tokenizer tokenizer, Integer maxContextTokens) {
        return sortContext(localContext, tokenizer, List.of(), maxContextTokens);
    }

    public static String sortContext(
            List<NodeContext> localContext,
            Tokenizer tokenizer,
            List<SubCommunityReport> subCommunityReports,
            Integer maxContextTokens) {

        List<EdgeDetails> edges = new ArrayList<>();
        Map<String, NodeDetails> nodeByTitle = new HashMap<>();
        Map<String, List<ClaimDetails>> claimsByTitle = new HashMap<>();
        for (NodeContext record : localContext) {
            edges.addAll(record.edgeDetails());
            nodeByTitle.put(record.title(), record.nodeDetails());
            if (record.claimDetails() != null) {
                claimsByTitle.put(record.title(), record.claimDetails());
            }
        }

        // Rule 21: strongest edge first, and the edge id breaks a tie. A missing degree
        // counts as zero, which puts it behind every edge that has one.
        edges.sort((a, b) -> {
            int byDegree = Long.compare(degreeOf(b), degreeOf(a));
            return byDegree != 0 ? byDegree
                    : Long.compare(a.humanReadableId(), b.humanReadableId());
        });

        Set<Long> seenEdges = new HashSet<>();
        Set<Long> seenNodes = new HashSet<>();
        Set<Long> seenClaims = new HashSet<>();
        List<NodeDetails> sortedNodes = new ArrayList<>();
        List<EdgeDetails> sortedEdges = new ArrayList<>();
        List<ClaimDetails> sortedClaims = new ArrayList<>();
        String contextString = "";

        for (EdgeDetails edge : edges) {
            for (String title : List.of(edge.source(), edge.target())) {
                NodeDetails node = nodeByTitle.get(title);
                if (node != null && seenNodes.add(node.humanReadableId())) {
                    sortedNodes.add(node);
                }
                List<ClaimDetails> claims = claimsByTitle.get(title);
                if (claims != null) {
                    for (ClaimDetails claim : claims) {
                        if (seenClaims.add(claim.humanReadableId())) {
                            sortedClaims.add(claim);
                        }
                    }
                }
            }
            if (seenEdges.add(edge.humanReadableId())) {
                sortedEdges.add(edge);
            }

            // With no budget there is nothing to stop at, so every render but the last is
            // discarded unread. The loop is quadratic in edges and this is its whole cost;
            // the rollup's mixed-context path calls in without a budget on every parent.
            if (maxContextTokens == null) {
                continue;
            }
            String candidate = render(sortedNodes, sortedEdges, sortedClaims,
                    subCommunityReports);
            if (tokenizer.countTokens(candidate) > maxContextTokens) {
                break;
            }
            contextString = candidate;
        }
        if (maxContextTokens == null) {
            return render(sortedNodes, sortedEdges, sortedClaims, subCommunityReports);
        }

        // Rule 25: a non-empty input never produces an empty output. What is rendered here
        // includes the additions from the edge that overran, which is what the source returns.
        return contextString.isEmpty()
                ? render(sortedNodes, sortedEdges, sortedClaims, subCommunityReports)
                : contextString;
    }

    private static long degreeOf(EdgeDetails edge) {
        return edge.combinedDegree() == null ? 0L : edge.combinedDegree();
    }

    /** Rule 13: headings in a fixed order, empty blocks omitted, blocks joined by a blank line. */
    private static String render(
            List<NodeDetails> entities,
            List<EdgeDetails> edges,
            List<ClaimDetails> claims,
            List<SubCommunityReport> subCommunityReports) {

        List<String> blocks = new ArrayList<>();
        if (subCommunityReports != null && !subCommunityReports.isEmpty()) {
            blocks.add("----Reports-----\n"
                    + PandasCsv.toCsv(subCommunityReports.stream()
                    .map(SubCommunityReport::asRow).toList()));
        }
        if (!entities.isEmpty()) {
            blocks.add("-----Entities-----\n"
                    + PandasCsv.toCsv(entities.stream().map(NodeDetails::asRow).toList()));
        }
        if (!claims.isEmpty()) {
            blocks.add("-----Claims-----\n"
                    + PandasCsv.toCsv(claims.stream().map(ClaimDetails::asRow).toList()));
        }
        if (!edges.isEmpty()) {
            blocks.add("-----Relationships-----\n"
                    + PandasCsv.toCsv(edges.stream().map(EdgeDetails::asRow).toList()));
        }
        return String.join("\n\n", blocks);
    }
}
