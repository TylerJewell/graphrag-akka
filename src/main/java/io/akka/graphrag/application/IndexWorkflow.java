package io.akka.graphrag.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.workflow.Workflow;
import akka.javasdk.workflow.WorkflowContext;
import io.akka.graphrag.domain.CommunityAssembly;
import io.akka.graphrag.domain.LevelRollup;
import io.akka.graphrag.domain.LevelRollup.Driver;
import io.akka.graphrag.domain.LevelRollup.RolledContext;
import io.akka.graphrag.domain.Levels;
import io.akka.graphrag.domain.LocalContextBuilder;
import io.akka.graphrag.domain.LocalContextBuilder.CommunityContext;
import io.akka.graphrag.domain.Records.ClusterAssignment;
import io.akka.graphrag.domain.Records.Community;
import io.akka.graphrag.domain.Records.CommunityReport;
import io.akka.graphrag.domain.Records.GraphEntity;
import io.akka.graphrag.domain.Records.GraphRelationship;
import io.akka.graphrag.domain.Tokenizer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One indexing run: assemble the communities, build each one's own detail, then roll the
 * hierarchy up a level at a time — SPEC-001 §3 rules 6-35.
 *
 * <p>The rollup is one step transitioning to itself once per level, with the levels discovered
 * while the run is going rather than fixed when it starts (rule 37, question-log row 3). That is
 * the shape the capability already had: level <i>L</i> is defined in terms of level <i>L+1</i>,
 * so the levels are a sequence, not a fan-out. Making each level a durable step means a run that
 * dies part-way resumes at the level it reached instead of from the top.
 */
@Component(id = "index-run")
public class IndexWorkflow extends Workflow<IndexWorkflow.State> {

    private static final int PAGE = 200;

    // Building a cl100k registry walks a merge-rules table; one per node, not one per run.
    private static final Tokenizer TOKENIZER = Tokenizer.cl100k();

    public record Start(int maxContextTokens, Driver driver, String period) {}

    public record State(
            String runId,
            int maxContextTokens,
            Driver driver,
            String period,
            List<Integer> remainingLevels,
            List<Integer> completedLevels,
            Map<Integer, Integer> levelByCommunity,
            Map<Integer, List<Integer>> childrenByCommunity,
            String phase) {

        State withPhase(String next) {
            return new State(runId, maxContextTokens, driver, period, remainingLevels,
                    completedLevels, levelByCommunity, childrenByCommunity, next);
        }
    }

    private final ComponentClient componentClient;
    private final String runId;

    public IndexWorkflow(ComponentClient componentClient, WorkflowContext context) {
        this.componentClient = componentClient;
        this.runId = context.workflowId();
    }

    @Override
    public WorkflowSettings settings() {
        return WorkflowSettings.builder()
                .timeout(Duration.ofMinutes(30))
                .defaultStepTimeout(Duration.ofMinutes(5))
                .build();
    }

    public Effect<String> start(Start start) {
        if (currentState() != null) {
            return effects().error("run " + runId + " has already been started");
        }
        return effects()
                .updateState(new State(runId, start.maxContextTokens(), start.driver(),
                        start.period(), List.of(), List.of(), Map.of(), Map.of(), "preparing"))
                .transitionTo(IndexWorkflow::prepare)
                .thenReply(runId);
    }

    public ReadOnlyEffect<State> get() {
        return effects().reply(currentState());
    }

    /** Rules 6-26: everything derivable from the graph in one pass over it. */
    private StepEffect prepare() {
        State state = currentState();

        List<GraphEntity> entities = readEntities();
        List<GraphRelationship> relationships = readRelationships();
        List<ClusterAssignment> clusters = readClusters();

        List<Community> communities = CommunityAssembly.assemble(clusters, entities,
                relationships, new CommunityAssembly.Options(runId, state.period()));
        List<CommunityContext> contexts = LocalContextBuilder.build(communities, entities,
                relationships, TOKENIZER, state.maxContextTokens());

        Map<Integer, CommunityContext> contextByCommunity = new LinkedHashMap<>();
        for (CommunityContext context : contexts) {
            contextByCommunity.put(context.community(), context);
        }

        Map<Integer, Integer> levelByCommunity = new TreeMap<>();
        Map<Integer, List<Integer>> children = new TreeMap<>();
        for (Community community : communities) {
            componentClient.forEventSourcedEntity(key(community.community()))
                    .method(CommunityEntity::form)
                    .invoke(new CommunityEntity.Event.Formed(runId, community));
            CommunityContext context = contextByCommunity.get(community.community());
            if (context != null) {
                componentClient.forEventSourcedEntity(key(community.community()))
                        .method(CommunityEntity::setLocalContext)
                        .invoke(new CommunityEntity.Event.LocalContextBuilt(
                                context.contextString(), context.contextSize(),
                                context.exceedsBudget(), context.nodes()));
            }
            levelByCommunity.put(community.community(), community.level());
            children.put(community.community(), community.children());
        }

        List<Integer> levels = Levels.descending(new ArrayList<>(levelByCommunity.values()));
        return stepEffects()
                .updateState(new State(runId, state.maxContextTokens(), state.driver(),
                        state.period(), levels, List.of(), levelByCommunity, children,
                        "rolling-up"))
                .thenTransitionTo(IndexWorkflow::rollUpOneLevel);
    }

    /** Rules 27-35, one level per durable step, highest level first. */
    private StepEffect rollUpOneLevel() {
        State state = currentState();
        if (state.remainingLevels().isEmpty()) {
            return stepEffects().updateState(state.withPhase("done")).thenEnd();
        }
        int level = state.remainingLevels().getFirst();

        Map<Integer, Map<Integer, CommunityContext>> byLevel = new TreeMap<>();
        List<CommunityReport> reports = new ArrayList<>();
        for (var entry : state.levelByCommunity().entrySet()) {
            int communityLevel = entry.getValue();
            if (communityLevel != level && communityLevel != level + 1) {
                continue;
            }
            CommunityEntity.State community = componentClient
                    .forEventSourcedEntity(key(entry.getKey()))
                    .method(CommunityEntity::get).invoke();
            byLevel.computeIfAbsent(communityLevel, k -> new LinkedHashMap<>())
                    .put(entry.getKey(), new CommunityContext(entry.getKey(), communityLevel,
                            community.localContextNodes(), community.localContext(),
                            community.localContextSize(),
                            community.localContextExceedsBudget()));
            // Under the source's own driver no report is ever visible to a later level, so
            // the set handed to the rollup stays empty however many have been written.
            if (state.driver() == Driver.FEED_REPORTS_FORWARD
                    && communityLevel == level + 1 && community.report() != null) {
                reports.add(new CommunityReport(entry.getKey(), communityLevel,
                        community.report()));
            }
        }

        List<RolledContext> rolled = LevelRollup.buildLevelContext(reports,
                state.childrenByCommunity(), byLevel, level, TOKENIZER,
                state.maxContextTokens());

        LevelRollup.ReportWriter writer = LevelRollup.ReportWriter.deterministic();
        for (RolledContext context : rolled) {
            componentClient.forEventSourcedEntity(key(context.community()))
                    .method(CommunityEntity::rollUp)
                    .invoke(new CommunityEntity.Event.RolledUp(context.contextString(),
                            context.contextSize(),
                            writer.write(context.community(), context.level(),
                                    context.contextString())));
        }

        List<Integer> remaining = new ArrayList<>(state.remainingLevels().subList(1,
                state.remainingLevels().size()));
        List<Integer> completed = new ArrayList<>(state.completedLevels());
        completed.add(level);
        return stepEffects()
                .updateState(new State(runId, state.maxContextTokens(), state.driver(),
                        state.period(), remaining, completed, state.levelByCommunity(),
                        state.childrenByCommunity(), "rolling-up"))
                .thenTransitionTo(IndexWorkflow::rollUpOneLevel);
    }

    private String key(int community) {
        return runId + ":" + community;
    }

    // Rule 36 the other way round: the graph comes back a page at a time too, so a run over a
    // graph larger than the per-message ceiling reads it rather than failing to.

    private List<GraphEntity> readEntities() {
        List<GraphEntity> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            var page = componentClient.forEventSourcedEntity(runId)
                    .method(GraphStoreEntity::entityPage)
                    .invoke(new GraphStoreEntity.PageRequest(offset, PAGE));
            out.addAll(page.rows());
            offset += page.rows().size();
            if (page.rows().isEmpty() || offset >= page.total()) {
                return out;
            }
        }
    }

    private List<GraphRelationship> readRelationships() {
        List<GraphRelationship> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            var page = componentClient.forEventSourcedEntity(runId)
                    .method(GraphStoreEntity::relationshipPage)
                    .invoke(new GraphStoreEntity.PageRequest(offset, PAGE));
            out.addAll(page.rows());
            offset += page.rows().size();
            if (page.rows().isEmpty() || offset >= page.total()) {
                return out;
            }
        }
    }

    private List<ClusterAssignment> readClusters() {
        List<ClusterAssignment> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            var page = componentClient.forEventSourcedEntity(runId)
                    .method(GraphStoreEntity::clusterPage)
                    .invoke(new GraphStoreEntity.PageRequest(offset, PAGE));
            out.addAll(page.rows());
            offset += page.rows().size();
            if (page.rows().isEmpty() || offset >= page.total()) {
                return out;
            }
        }
    }
}
