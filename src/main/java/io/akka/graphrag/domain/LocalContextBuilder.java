package io.akka.graphrag.domain;

import io.akka.graphrag.domain.Records.Community;
import io.akka.graphrag.domain.Records.GraphEntity;
import io.akka.graphrag.domain.Records.GraphRelationship;
import io.akka.graphrag.domain.SortContext.EdgeDetails;
import io.akka.graphrag.domain.SortContext.NodeContext;
import io.akka.graphrag.domain.SortContext.NodeDetails;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds each community's own detail, level by level — SPEC-001 §3 rules 17-26.
 *
 * <p>Two rules here look like defects and are not: they are what the source does, measured
 * (question-log rows 25 and 26). A node carries <em>one</em> edge into its community's context,
 * so 1,093 of 1,301 available edges reach a context on the source's own fixture. And an edge is
 * a candidate because both its ends are at this <em>level</em>, not because both are in this
 * community, so 223 of the edges that do reach a context describe something outside it.
 * Repairing either would change 33 and 65 of 121 contexts respectively.
 */
public final class LocalContextBuilder {

    private LocalContextBuilder() {}

    /** A community's context together with the records it was rendered from. */
    public record CommunityContext(
            int community,
            int level,
            List<NodeContext> nodes,
            String contextString,
            int contextSize,
            boolean exceedsBudget) {}

    public static List<CommunityContext> build(
            List<Community> communities,
            List<GraphEntity> entities,
            List<GraphRelationship> relationships,
            Tokenizer tokenizer,
            int maxContextTokens) {

        Map<String, GraphEntity> entityById = new HashMap<>();
        for (GraphEntity entity : entities) {
            entityById.put(entity.id(), entity);
        }

        // Rule 19: membership comes from the community rows, so a node in no community is
        // simply absent rather than filtered out by a sentinel comparison.
        Map<Integer, List<Membership>> byLevel = new TreeMap<>();
        for (Community community : communities) {
            for (String entityId : community.entityIds()) {
                GraphEntity entity = entityById.get(entityId);
                if (entity != null) {
                    byLevel.computeIfAbsent(community.level(), k -> new ArrayList<>())
                            .add(new Membership(entity, community.community()));
                }
            }
        }

        List<CommunityContext> out = new ArrayList<>();
        for (var levelEntry : byLevel.entrySet()) {
            out.addAll(atLevel(levelEntry.getKey(), levelEntry.getValue(), relationships,
                    tokenizer, maxContextTokens));
        }
        return out;
    }

    private record Membership(GraphEntity entity, int community) {}

    private static List<CommunityContext> atLevel(
            int level,
            List<Membership> members,
            List<GraphRelationship> relationships,
            Tokenizer tokenizer,
            int maxContextTokens) {

        Set<String> titlesAtLevel = new HashSet<>();
        for (Membership member : members) {
            titlesAtLevel.add(member.entity().title());
        }

        // Rule 17: candidacy is decided by the level, not by the community.
        // Rule 18: the first candidate on which a title is the source wins, else the first
        // on which it is the target.
        Map<String, EdgeDetails> firstAsSource = new HashMap<>();
        Map<String, EdgeDetails> firstAsTarget = new HashMap<>();
        for (GraphRelationship r : relationships) {
            if (!titlesAtLevel.contains(r.source()) || !titlesAtLevel.contains(r.target())) {
                continue;
            }
            EdgeDetails details = new EdgeDetails(r.humanReadableId(), r.source(), r.target(),
                    r.descriptionOrDefault(), r.combinedDegree());
            firstAsSource.putIfAbsent(r.source(), details);
            firstAsTarget.putIfAbsent(r.target(), details);
        }

        // Grouped by title within a community, which is the order the source's groupby leaves
        // the records in and therefore the order they are rendered in.
        Map<Integer, Map<String, NodeContext>> byCommunity = new TreeMap<>();
        for (Membership member : members) {
            GraphEntity entity = member.entity();
            EdgeDetails edge = firstAsSource.get(entity.title());
            if (edge == null) {
                edge = firstAsTarget.get(entity.title());
            }
            // Rule 20: no claims are attached here. The source attaches them in a shape its
            // own renderer does not read, so a context is the same either way.
            byCommunity.computeIfAbsent(member.community(), k -> new TreeMap<>())
                    .put(entity.title(), new NodeContext(
                            entity.title(),
                            entity.degree(),
                            new NodeDetails(entity.humanReadableId(), entity.title(),
                                    entity.descriptionOrDefault(), entity.degree()),
                            edge == null ? List.of() : List.of(edge),
                            null));
        }

        List<CommunityContext> out = new ArrayList<>();
        for (var entry : byCommunity.entrySet()) {
            List<NodeContext> nodes = new ArrayList<>(entry.getValue().values());
            String contextString = SortContext.sortContext(nodes, tokenizer, maxContextTokens);
            int size = tokenizer.countTokens(contextString);
            out.add(new CommunityContext(entry.getKey(), level, nodes, contextString, size,
                    size > maxContextTokens));
        }
        out.sort(Comparator.comparingInt(CommunityContext::community));
        return out;
    }

    /** Index a context list the way the rollup wants to read it: by level, then community. */
    public static Map<Integer, Map<Integer, CommunityContext>> byLevel(
            List<CommunityContext> contexts) {
        Map<Integer, Map<Integer, CommunityContext>> out = new TreeMap<>();
        for (CommunityContext context : contexts) {
            out.computeIfAbsent(context.level(), k -> new LinkedHashMap<>())
                    .put(context.community(), context);
        }
        return out;
    }
}
