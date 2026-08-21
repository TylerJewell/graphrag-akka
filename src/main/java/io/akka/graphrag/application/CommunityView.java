package io.akka.graphrag.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;

import java.util.List;

/**
 * A run's communities, queryable by run and by level without knowing their ids.
 *
 * <p>Rows carry sizes and the report, not the context strings: a query returning every context
 * of a run would be 548 KB on the source's own fixture, and a view row is read far more often
 * than a context is. The context itself is read from the community that owns it.
 */
@Component(id = "community-index")
public class CommunityView extends View {

    public record CommunityEntry(
            String id,
            String runId,
            int community,
            int level,
            int parent,
            String title,
            int size,
            int localContextSize,
            boolean localContextExceededBudget,
            int rolledContextSize,
            boolean rolledUp,
            String report) {}

    public record CommunityEntries(List<CommunityEntry> communities) {}

    public record RunLevel(String runId, int level) {}

    @Consume.FromEventSourcedEntity(CommunityEntity.class)
    public static class Updater extends TableUpdater<CommunityEntry> {

        public Effect<CommunityEntry> onEvent(CommunityEntity.Event event) {
            CommunityEntry current = rowState();
            return switch (event) {
                case CommunityEntity.Event.Formed e -> effects().updateRow(new CommunityEntry(
                        updateContext().eventSubject().orElseThrow(),
                        e.runId(), e.community().community(), e.community().level(),
                        e.community().parent(), e.community().title(), e.community().size(),
                        current == null ? 0 : current.localContextSize(),
                        current != null && current.localContextExceededBudget(),
                        current == null ? 0 : current.rolledContextSize(),
                        current != null && current.rolledUp(),
                        // A view row's string field cannot be absent, so a community with no
                        // report yet carries an empty one rather than a null.
                        current == null ? "" : current.report()));
                case CommunityEntity.Event.LocalContextBuilt e -> effects().updateRow(new CommunityEntry(
                        current.id(), current.runId(), current.community(), current.level(),
                        current.parent(), current.title(), current.size(),
                        e.contextSize(), e.exceedsBudget(),
                        current.rolledContextSize(), current.rolledUp(), current.report()));
                case CommunityEntity.Event.RolledUp e -> effects().updateRow(new CommunityEntry(
                        current.id(), current.runId(), current.community(), current.level(),
                        current.parent(), current.title(), current.size(),
                        current.localContextSize(), current.localContextExceededBudget(),
                        e.contextSize(), true, e.report()));
            };
        }
    }

    @Query("SELECT * AS communities FROM community_index WHERE runId = :runId ORDER BY level DESC, community ASC")
    public QueryEffect<CommunityEntries> byRun(String runId) {
        return queryResult();
    }

    @Query("SELECT * AS communities FROM community_index WHERE runId = :runId AND level = :level ORDER BY community ASC")
    public QueryEffect<CommunityEntries> byRunAndLevel(RunLevel input) {
        return queryResult();
    }
}
