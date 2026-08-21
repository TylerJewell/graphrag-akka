package io.akka.graphrag.domain;

import io.akka.graphrag.domain.Records.GraphRelationship;
import io.akka.graphrag.domain.Records.WeightedEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The edge list a hierarchical clusterer is handed — SPEC-001 §3 rules 1-5.
 *
 * <p>Rule 3 is the one worth knowing about. Two relationships that name the same pair of titles
 * collapse to one, and the one kept is whichever arrived last, so the weight the clusterer sees
 * — and therefore the whole partition — is a function of the order the relationships were
 * submitted in. On the source's own fixture 43 of 978 pairs are duplicated and every one of them
 * differs between its copies. That is reproduced rather than repaired;
 * {@link Options#tieBreakOnId} exists for a caller who wants the repair and is off by default.
 */
public final class EdgeListPreparation {

    private EdgeListPreparation() {}

    /** One normalised pair of endpoints, which is what a duplicate is a duplicate of. */
    private record Pair(String source, String target) {}

    /**
     * @param useLargestConnectedComponent keep only the biggest connected component (rule 4)
     * @param tieBreakOnId order duplicated pairs by relationship id before the last-wins dedup,
     *                     which makes the whole preparation independent of submission order.
     *                     Not the source's behaviour — SPEC-001 §4 decision 4.
     */
    public record Options(boolean useLargestConnectedComponent, boolean tieBreakOnId) {

        public static Options defaults() {
            return new Options(true, false);
        }
    }

    public static List<WeightedEdge> prepare(
            List<GraphRelationship> relationships, Options options) {

        List<GraphRelationship> ordered = new ArrayList<>(relationships);
        if (options.tieBreakOnId()) {
            ordered.sort(Comparator.comparing(GraphRelationship::id));
        }

        // Rules 1-2: normalise direction, then last one wins per pair. Keyed on the pair
        // itself rather than on the two titles joined by a separator: titles contain spaces
        // and commas, so any separator that reads naturally can also be inside a title.
        Map<Pair, WeightedEdge> byPair = new LinkedHashMap<>();
        for (GraphRelationship r : ordered) {
            String source = r.source();
            String target = r.target();
            if (source.compareTo(target) > 0) {
                String swap = source;
                source = target;
                target = swap;
            }
            double weight = r.weight() == null ? 1.0 : r.weight();
            Pair pair = new Pair(source, target);
            // The surviving row keeps the *last* occurrence's position, not the first. That
            // is invisible in the sorted edge list and decides which connected component is
            // the largest when two tie, because the tie is broken by node order.
            byPair.remove(pair);
            byPair.put(pair, new WeightedEdge(source, target, weight));
        }

        List<WeightedEdge> edges = new ArrayList<>(byPair.values());
        if (options.useLargestConnectedComponent()) {
            edges = largestConnectedComponent(edges);
        }

        // Rule 5.
        edges.sort(Comparator.comparing(WeightedEdge::source)
                .thenComparing(WeightedEdge::target)
                .thenComparingDouble(WeightedEdge::weight));
        return List.copyOf(edges);
    }

    /**
     * Rule 4. Titles are HTML-unescaped, upper-cased and trimmed before the components are
     * found, so two spellings of one name join the same component.
     *
     * <p>Ties on component size are broken by node order, not alphabetically: the component
     * containing the earliest node wins, where the nodes are ordered by every edge's source in
     * row order followed by every target not already seen. Alphabetical looks like the tidier
     * rule and is a different answer — on six relationships over four pairs it picks a
     * different component, which the benchmark's arrival-order workload caught.
     *
     * <p>Upper-casing can reverse two titles' order, so direction is normalised a second time
     * afterwards and any pair that collided as a result collapses, keeping the first.
     */
    private static List<WeightedEdge> largestConnectedComponent(List<WeightedEdge> edges) {
        List<WeightedEdge> normalized = edges.stream()
                .map(e -> new WeightedEdge(normalize(e.source()), normalize(e.target()),
                        e.weight()))
                .toList();

        // Components are found over one edge per pair; the full list is what gets filtered.
        List<WeightedEdge> distinctPairs = new ArrayList<>();
        Set<Pair> seenPairs = new java.util.HashSet<>();
        for (WeightedEdge e : normalized) {
            if (seenPairs.add(new Pair(e.source(), e.target()))) {
                distinctPairs.add(e);
            }
        }

        List<String> nodeOrder = new ArrayList<>();
        Set<String> seenNodes = new java.util.LinkedHashSet<>();
        for (WeightedEdge e : distinctPairs) {
            if (seenNodes.add(e.source())) {
                nodeOrder.add(e.source());
            }
        }
        for (WeightedEdge e : distinctPairs) {
            if (seenNodes.add(e.target())) {
                nodeOrder.add(e.target());
            }
        }

        Map<String, String> parent = new HashMap<>();
        for (String node : nodeOrder) {
            parent.put(node, node);
        }
        for (WeightedEdge e : distinctPairs) {
            union(parent, e.source(), e.target());
        }

        Map<String, Set<String>> components = new LinkedHashMap<>();
        for (String node : nodeOrder) {
            components.computeIfAbsent(find(parent, node), k -> new java.util.HashSet<>())
                    .add(node);
        }
        Set<String> largest = Set.of();
        for (Set<String> component : components.values()) {
            if (component.size() > largest.size()) {
                largest = component;
            }
        }

        List<WeightedEdge> kept = new ArrayList<>();
        Set<Pair> keptPairs = new java.util.HashSet<>();
        for (WeightedEdge e : normalized) {
            if (!largest.contains(e.source()) || !largest.contains(e.target())) {
                continue;
            }
            String source = e.source();
            String target = e.target();
            if (source.compareTo(target) > 0) {
                String swap = source;
                source = target;
                target = swap;
            }
            if (keptPairs.add(new Pair(source, target))) {
                kept.add(new WeightedEdge(source, target, e.weight()));
            }
        }
        return kept;
    }

    private static String normalize(String title) {
        return unescapeHtml(title).toUpperCase().strip();
    }

    /**
     * The five entities {@code html.unescape} resolves that occur in entity titles, plus numeric
     * references. Titles are extracted names, not markup, so the full HTML5 entity table is not
     * what is being reproduced here — the escaping the source removes is.
     */
    private static String unescapeHtml(String value) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            int amp = value.indexOf('&', i);
            if (amp < 0) {
                out.append(value, i, value.length());
                break;
            }
            out.append(value, i, amp);
            int semi = value.indexOf(';', amp);
            if (semi < 0 || semi - amp > 10) {
                out.append('&');
                i = amp + 1;
                continue;
            }
            String name = value.substring(amp + 1, semi);
            String replacement = switch (name) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> numericEntity(name);
            };
            if (replacement == null) {
                out.append('&');
                i = amp + 1;
            } else {
                out.append(replacement);
                i = semi + 1;
            }
        }
        return out.toString();
    }

    private static String numericEntity(String name) {
        if (name.isEmpty() || name.charAt(0) != '#') {
            return null;
        }
        try {
            int code = (name.length() > 1 && (name.charAt(1) == 'x' || name.charAt(1) == 'X'))
                    ? Integer.parseInt(name.substring(2), 16)
                    : Integer.parseInt(name.substring(1));
            return Character.toString(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String find(Map<String, String> parent, String node) {
        String root = node;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        String walk = node;
        while (!walk.equals(root)) {
            String next = parent.get(walk);
            parent.put(walk, root);
            walk = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (!rootA.equals(rootB)) {
            parent.put(rootA, rootB);
        }
    }
}
