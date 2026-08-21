package io.akka.graphrag.domain;

import io.akka.graphrag.domain.LocalContextBuilder.CommunityContext;
import io.akka.graphrag.domain.Records.CommunityReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rolls the hierarchy up one level at a time — SPEC-001 §3 rules 27-35.
 *
 * <p>Which driver runs is a stated choice (rule 29a, §4 decision 7). Under
 * {@link Driver#SOURCE_ORDER}, the default, every level's context is built before any report is
 * written, so the report set handed to each level is always empty and the substitution in rules
 * 30-33 never fires — that is what graphrag does, measured at question-log row 30. Under
 * {@link Driver#FEED_REPORTS_FORWARD} a level sees the level above's reports, which changes 2 of
 * 121 rows at a budget of 800 and 14 at 200.
 */
public final class LevelRollup {

    private LevelRollup() {}

    public enum Driver {
        /** graphrag's own order: no level ever sees a report. */
        SOURCE_ORDER,
        /** Level L sees the reports written for level L+1. Not a route the source has. */
        FEED_REPORTS_FORWARD
    }

    /** Writes a report body from a finished context. */
    @FunctionalInterface
    public interface ReportWriter {
        String write(int community, int level, String context);

        /**
         * SPEC-001 §4 decision 2: the rollup only ever reads a report's length and whether it
         * exists, so the shipped writer is deterministic and a timing measures the rollup.
         */
        static ReportWriter deterministic() {
            return (community, level, context) -> "REPORT c=" + community + " l=" + level
                    + " n=" + context.codePointCount(0, context.length());
        }
    }

    public record RolledContext(int community, int level, String contextString, int contextSize) {}

    public record Result(List<RolledContext> contexts, List<CommunityReport> reports) {}

    /**
     * @param childrenByCommunity each community's sub-communities, which sit one level below it
     */
    public static Result roll(
            List<CommunityContext> localContexts,
            Map<Integer, List<Integer>> childrenByCommunity,
            Tokenizer tokenizer,
            int maxContextTokens,
            Driver driver,
            ReportWriter writer) {

        Map<Integer, Map<Integer, CommunityContext>> byLevel =
                LocalContextBuilder.byLevel(localContexts);
        List<Integer> levels = Levels.descending(
                localContexts.stream().map(CommunityContext::level).toList());

        List<CommunityReport> reports = new ArrayList<>();
        List<RolledContext> all = new ArrayList<>();
        List<List<RolledContext>> perLevel = new ArrayList<>();

        for (int level : levels) {
            List<RolledContext> contexts = buildLevelContext(
                    reports, childrenByCommunity, byLevel, level, tokenizer, maxContextTokens);
            perLevel.add(contexts);
            if (driver == Driver.FEED_REPORTS_FORWARD) {
                reports.addAll(write(contexts, writer));
            }
        }
        for (List<RolledContext> contexts : perLevel) {
            all.addAll(contexts);
            if (driver == Driver.SOURCE_ORDER) {
                reports.addAll(write(contexts, writer));
            }
        }
        return new Result(all, reports);
    }

    private static List<CommunityReport> write(
            List<RolledContext> contexts, ReportWriter writer) {
        return contexts.stream()
                .map(c -> new CommunityReport(c.community(), c.level(),
                        writer.write(c.community(), c.level(), c.contextString())))
                .toList();
    }

    /**
     * Rules 28-35 for one level. Public because a durable run does one level per step, and each
     * step reads only what that level needs rather than holding the whole run in memory.
     */
    public static List<RolledContext> buildLevelContext(
            List<CommunityReport> reports,
            Map<Integer, List<Integer>> childrenByCommunity,
            Map<Integer, Map<Integer, CommunityContext>> byLevel,
            int level,
            Tokenizer tokenizer,
            int maxContextTokens) {

        Map<Integer, CommunityContext> atLevel =
                byLevel.getOrDefault(level, Map.of());

        List<RolledContext> valid = new ArrayList<>();
        List<CommunityContext> invalid = new ArrayList<>();
        for (CommunityContext context : sorted(atLevel)) {
            if (context.exceedsBudget()) {
                invalid.add(context);
            } else {
                valid.add(new RolledContext(context.community(), level,
                        context.contextString(), context.contextSize()));
            }
        }
        if (invalid.isEmpty()) {
            return valid;
        }
        if (reports.isEmpty()) {
            // Rule 29, and the only branch the source's own driver ever reaches.
            List<RolledContext> out = new ArrayList<>(valid);
            for (CommunityContext context : invalid) {
                out.add(trimmed(context, level, tokenizer, maxContextTokens));
            }
            return out;
        }

        Map<Integer, String> reportBelow = new LinkedHashMap<>();
        for (CommunityReport report : reports) {
            if (report.level() == level + 1) {
                reportBelow.put(report.community(), report.fullContent());
            }
        }
        Map<Integer, CommunityContext> below = byLevel.getOrDefault(level + 1, Map.of());

        List<RolledContext> rebuilt = new ArrayList<>();
        List<RolledContext> remaining = new ArrayList<>();
        for (CommunityContext context : invalid) {
            List<MixedContext.SubContext> subContexts = new ArrayList<>();
            for (int child : childrenByCommunity
                    .getOrDefault(context.community(), List.of())) {
                CommunityContext childContext = below.get(child);
                if (childContext != null) {
                    subContexts.add(new MixedContext.SubContext(child, childContext.nodes(),
                            reportBelow.get(child), childContext.contextSize()));
                }
            }
            if (subContexts.isEmpty()) {
                // Rule 34.
                remaining.add(trimmed(context, level, tokenizer, maxContextTokens));
                continue;
            }
            String rebuiltString =
                    MixedContext.build(subContexts, tokenizer, maxContextTokens);
            rebuilt.add(new RolledContext(context.community(), level, rebuiltString,
                    tokenizer.countTokens(rebuiltString)));
        }

        List<RolledContext> out = new ArrayList<>(valid);
        out.addAll(rebuilt);
        out.addAll(remaining);
        return out;
    }

    private static RolledContext trimmed(
            CommunityContext context, int level, Tokenizer tokenizer, int maxContextTokens) {
        String text = SortContext.sortContext(context.nodes(), tokenizer, maxContextTokens);
        return new RolledContext(context.community(), level, text,
                tokenizer.countTokens(text));
    }

    private static List<CommunityContext> sorted(Map<Integer, CommunityContext> atLevel) {
        List<CommunityContext> out = new ArrayList<>(new TreeMap<>(atLevel).values());
        out.sort(Comparator.comparingInt(CommunityContext::community));
        return out;
    }
}
