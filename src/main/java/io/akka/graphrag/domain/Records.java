package io.akka.graphrag.domain;

import java.util.List;

/**
 * The graph a run is given, and what SPEC-001 §2 says each part carries. These are plain
 * records with no Akka in them, so every rule in §3 is testable without a runtime.
 */
public final class Records {

    private Records() {}

    /** A node. {@code title} is what the clustering and the context strings work in terms of. */
    public record GraphEntity(
            String id,
            long humanReadableId,
            String title,
            String description,
            long degree,
            List<String> textUnitIds) {

        public String descriptionOrDefault() {
            return description == null ? "No Description" : description;
        }
    }

    /**
     * An edge. {@code weight} is what a clusterer is given; {@code combinedDegree} is the sort
     * key a context string is ordered by, and is allowed to be absent (§3 rule 21).
     */
    public record GraphRelationship(
            String id,
            long humanReadableId,
            String source,
            String target,
            String description,
            Double weight,
            Long combinedDegree,
            List<String> textUnitIds) {

        public String descriptionOrDefault() {
            return description == null ? "No Description" : description;
        }
    }

    /** A claim about an entity. Carried, attached, and — per §3 rule 20 — never rendered. */
    public record Claim(
            String id,
            long humanReadableId,
            String subjectId,
            String type,
            String status,
            String description) {

        public String descriptionOrDefault() {
            return description == null ? "No Description" : description;
        }
    }

    /** One row of a supplied hierarchy: which titles form which community at which level. */
    public record ClusterAssignment(int level, int community, int parent, List<String> titles) {}

    /** An assembled community — SPEC-001 §3 rules 6-12. */
    public record Community(
            String id,
            int community,
            int level,
            int parent,
            List<Integer> children,
            String title,
            long humanReadableId,
            List<String> entityIds,
            List<String> relationshipIds,
            List<String> textUnitIds,
            String period,
            int size) {}

    /** One community's own detail at one level, and whether it fits the budget. */
    public record LocalContext(
            int community,
            int level,
            String contextString,
            int contextSize,
            boolean exceedsBudget) {}

    /** What the level below consumes. A blank {@code fullContent} means "no report". */
    public record CommunityReport(int community, int level, String fullContent) {}

    /** An edge as the clusterer is given it — SPEC-001 §3 rules 1-5. */
    public record WeightedEdge(String source, String target, double weight) {}
}
