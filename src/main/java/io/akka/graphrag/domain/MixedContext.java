package io.akka.graphrag.domain;

import io.akka.graphrag.domain.SortContext.NodeContext;
import io.akka.graphrag.domain.SortContext.SubCommunityReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Replaces a community's own detail with its sub-communities' reports until what is left fits —
 * SPEC-001 §3 rules 31-33.
 *
 * <p>Substitution goes largest sub-community first, one at a time, and after each one the whole
 * string is rebuilt from the reports so far plus every remaining sub-community's raw detail. A
 * sub-community with no report contributes its detail and does not use up a step. If nothing
 * fits, the last fallback is reports alone, appended until the next one would overrun.
 */
public final class MixedContext {

    private MixedContext() {}

    /** One sub-community as the parent sees it: its report if it has one, its detail either way. */
    public record SubContext(
            int subCommunity, List<NodeContext> allContext, String fullContent, int contextSize) {

        boolean hasReport() {
            return fullContent != null && !fullContent.isEmpty();
        }
    }

    public static String build(
            List<SubContext> subContexts, Tokenizer tokenizer, int maxContextTokens) {

        List<SubContext> sorted = new ArrayList<>(subContexts);
        sorted.sort(Comparator.comparingInt(SubContext::contextSize).reversed());

        List<SubCommunityReport> substitutes = new ArrayList<>();
        List<NodeContext> keptDetail = new ArrayList<>();
        String contextString = "";
        boolean exceeded = true;

        for (int i = 0; i < sorted.size(); i++) {
            SubContext sub = sorted.get(i);
            if (!sub.hasReport()) {
                // Rule 32: no report, so its detail stays and the step is not spent.
                keptDetail.addAll(sub.allContext());
                continue;
            }
            substitutes.add(new SubCommunityReport(sub.subCommunity(), sub.fullContent()));

            List<NodeContext> remaining = new ArrayList<>();
            for (int j = i + 1; j < sorted.size(); j++) {
                remaining.addAll(sorted.get(j).allContext());
            }
            remaining.addAll(keptDetail);

            String candidate =
                    SortContext.sortContext(remaining, tokenizer, substitutes, null);
            if (tokenizer.countTokens(candidate) <= maxContextTokens) {
                exceeded = false;
                contextString = candidate;
                break;
            }
        }

        if (exceeded) {
            // Rule 33: reports only, up to the last one that fit — which is nothing at all
            // when even the first overruns.
            contextString = "";
            List<SubCommunityReport> reportsOnly = new ArrayList<>();
            for (SubContext sub : sorted) {
                reportsOnly.add(new SubCommunityReport(sub.subCommunity(), sub.fullContent()));
                String candidate = PandasCsv.toCsv(
                        reportsOnly.stream().map(SubCommunityReport::asRow).toList());
                if (tokenizer.countTokens(candidate) > maxContextTokens) {
                    break;
                }
                contextString = candidate;
            }
        }
        return contextString;
    }
}
