package io.akka.graphrag.domain;

import io.akka.graphrag.domain.Records.ClusterAssignment;
import io.akka.graphrag.domain.Records.Community;
import io.akka.graphrag.domain.Records.GraphEntity;
import io.akka.graphrag.domain.Records.GraphRelationship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns a supplied hierarchy into community rows — SPEC-001 §3 rules 6-12.
 *
 * <p>A community's relationships are the ones with <em>both</em> ends inside it at that level
 * (rule 8), which is a narrower set than the edges its context string will later be allowed to
 * describe (rule 17). The two are deliberately different and both come from the source.
 */
public final class CommunityAssembly {

    private CommunityAssembly() {}

    /**
     * @param runId    makes a community's id reproducible across runs of the same graph, where
     *                 the source stamps a fresh uuid4 — SPEC-001 §4 decision 3
     * @param period   the date stamped on every row, injected for the same reason
     */
    public record Options(String runId, String period) {}

    public static List<Community> assemble(
            List<ClusterAssignment> clusters,
            List<GraphEntity> entities,
            List<GraphRelationship> relationships,
            Options options) {

        Map<String, String> entityIdByTitle = new HashMap<>();
        for (GraphEntity entity : entities) {
            entityIdByTitle.put(entity.title(), entity.id());
        }

        // Which community each title sits in, per level. A title named by two clusters at one
        // level would be a malformed hierarchy; last wins, as the source's explode does.
        Map<Integer, Map<String, Integer>> communityByTitle = new TreeMap<>();
        Map<Integer, Integer> parentOf = new LinkedHashMap<>();
        for (ClusterAssignment cluster : clusters) {
            Map<String, Integer> atLevel =
                    communityByTitle.computeIfAbsent(cluster.level(), k -> new LinkedHashMap<>());
            for (String title : cluster.titles()) {
                atLevel.put(title, cluster.community());
            }
            parentOf.put(cluster.community(), cluster.parent());
        }

        // Rule 7: member entity ids, in hierarchy order, skipping titles with no entity.
        Map<Integer, List<String>> entityIds = new LinkedHashMap<>();
        for (ClusterAssignment cluster : clusters) {
            List<String> ids = entityIds
                    .computeIfAbsent(cluster.community(), k -> new ArrayList<>());
            for (String title : cluster.titles()) {
                String id = entityIdByTitle.get(title);
                if (id != null) {
                    ids.add(id);
                }
            }
        }

        // Rules 8-9, one level at a time.
        Map<Integer, Set<String>> relationshipIds = new LinkedHashMap<>();
        Map<Integer, Set<String>> textUnitIds = new LinkedHashMap<>();
        Set<Integer> withRelationships = new LinkedHashSet<>();
        for (var levelEntry : communityByTitle.entrySet()) {
            Map<String, Integer> atLevel = levelEntry.getValue();
            for (GraphRelationship relationship : relationships) {
                Integer source = atLevel.get(relationship.source());
                Integer target = atLevel.get(relationship.target());
                if (source == null || !source.equals(target)) {
                    continue;
                }
                withRelationships.add(source);
                relationshipIds.computeIfAbsent(source, k -> new TreeSet<>())
                        .add(relationship.id());
                textUnitIds.computeIfAbsent(source, k -> new TreeSet<>())
                        .addAll(relationship.textUnitIds());
            }
        }

        // Rule 10: the tree the other way round. Only communities that survive rule 12 are
        // counted as children, because only they exist as rows.
        Map<Integer, List<Integer>> children = new LinkedHashMap<>();
        for (ClusterAssignment cluster : clusters) {
            if (withRelationships.contains(cluster.community()) && cluster.parent() != -1) {
                children.computeIfAbsent(cluster.parent(), k -> new ArrayList<>())
                        .add(cluster.community());
            }
        }

        List<Community> out = new ArrayList<>();
        Set<Integer> emitted = new LinkedHashSet<>();
        for (ClusterAssignment cluster : clusters) {
            int community = cluster.community();
            if (!withRelationships.contains(community) || !emitted.add(community)) {
                continue;
            }
            List<String> members = entityIds.getOrDefault(community, List.of());
            out.add(new Community(
                    options.runId() + "-" + community,
                    community,
                    cluster.level(),
                    parentOf.getOrDefault(community, -1),
                    List.copyOf(children.getOrDefault(community, List.of())),
                    "Community " + community,
                    community,
                    List.copyOf(members),
                    List.copyOf(relationshipIds.getOrDefault(community, Set.of())),
                    List.copyOf(textUnitIds.getOrDefault(community, Set.of())),
                    options.period(),
                    members.size()));
        }
        return out;
    }
}
