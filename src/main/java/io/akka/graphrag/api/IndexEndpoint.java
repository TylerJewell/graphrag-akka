package io.akka.graphrag.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.graphrag.application.CommunityEntity;
import io.akka.graphrag.application.CommunityView;
import io.akka.graphrag.application.GraphStoreEntity;
import io.akka.graphrag.application.IndexWorkflow;
import io.akka.graphrag.domain.EdgeListPreparation;
import io.akka.graphrag.domain.LevelRollup.Driver;
import io.akka.graphrag.domain.Records.Claim;
import io.akka.graphrag.domain.Records.ClusterAssignment;
import io.akka.graphrag.domain.Records.GraphEntity;
import io.akka.graphrag.domain.Records.GraphRelationship;
import io.akka.graphrag.domain.Records.WeightedEdge;

import java.util.List;

/**
 * The capability's own surface: submit a graph in batches, run the index, read what came out.
 *
 * <p>The edge-list route is here because SPEC-001 §3 rules 1-5 are the part of clustering that is
 * graphrag's own code — the algorithm itself is a third-party library the port takes a hierarchy
 * from instead (§4 decision 1). Exposing the prepared edge list is what makes that half usable
 * rather than only testable.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/index")
public class IndexEndpoint {

    public record StartRequest(Integer maxContextTokens, String driver) {}

    /**
     * What a caller needs to know about a run. The workflow's own state also carries the
     * level and children maps it walks with — 121 entries on the source's fixture — which
     * are working notes rather than an answer, so they stay inside.
     */
    public record RunStatus(
            String runId,
            String phase,
            int maxContextTokens,
            String driver,
            String period,
            int communities,
            List<Integer> completedLevels,
            List<Integer> remainingLevels) {

        static RunStatus of(IndexWorkflow.State state) {
            return new RunStatus(state.runId(), state.phase(), state.maxContextTokens(),
                    state.driver().name(), state.period(),
                    state.levelByCommunity().size(), state.completedLevels(),
                    state.remainingLevels());
        }
    }

    public record EdgeListRequest(
            List<GraphRelationship> relationships,
            Boolean useLargestConnectedComponent,
            Boolean tieBreakOnId) {}

    public record EdgeListResponse(List<WeightedEdge> edges) {}

    public record CommunityDetail(
            int community,
            int level,
            int parent,
            List<Integer> children,
            String title,
            int size,
            List<String> entityIds,
            List<String> relationshipIds,
            List<String> textUnitIds,
            String period,
            String localContext,
            int localContextSize,
            boolean localContextExceededBudget,
            String rolledContext,
            int rolledContextSize,
            String report) {}

    private final ComponentClient componentClient;

    public IndexEndpoint(ComponentClient componentClient) {
        this.componentClient = componentClient;
    }

    @Post("/{runId}/graph/entities")
    public GraphStoreEntity.Summary addEntities(String runId, List<GraphEntity> entities) {
        componentClient.forEventSourcedEntity(runId)
                .method(GraphStoreEntity::addEntities).invoke(entities);
        return summary(runId);
    }

    @Post("/{runId}/graph/relationships")
    public GraphStoreEntity.Summary addRelationships(
            String runId, List<GraphRelationship> relationships) {
        componentClient.forEventSourcedEntity(runId)
                .method(GraphStoreEntity::addRelationships).invoke(relationships);
        return summary(runId);
    }

    @Post("/{runId}/graph/claims")
    public GraphStoreEntity.Summary addClaims(String runId, List<Claim> claims) {
        componentClient.forEventSourcedEntity(runId)
                .method(GraphStoreEntity::addClaims).invoke(claims);
        return summary(runId);
    }

    @Post("/{runId}/graph/clusters")
    public GraphStoreEntity.Summary addClusters(String runId, List<ClusterAssignment> clusters) {
        componentClient.forEventSourcedEntity(runId)
                .method(GraphStoreEntity::addClusters).invoke(clusters);
        return summary(runId);
    }

    @Post("/{runId}/graph/seal")
    public GraphStoreEntity.Summary seal(String runId) {
        componentClient.forEventSourcedEntity(runId).method(GraphStoreEntity::seal).invoke();
        return summary(runId);
    }

    @Get("/{runId}/graph")
    public GraphStoreEntity.Summary summary(String runId) {
        return componentClient.forEventSourcedEntity(runId)
                .method(GraphStoreEntity::summary).invoke();
    }

    @Post("/{runId}/run")
    public RunStatus run(String runId, StartRequest request) {
        GraphStoreEntity.Summary graph = summary(runId);
        if (!graph.sealed()) {
            // Otherwise this surfaces as a 500 with only a correlation id, which tells the
            // caller nothing about the one thing they have to do first.
            throw HttpException.badRequest(
                    "seal the graph for run " + runId + " before running the index");
        }
        int budget = request == null || request.maxContextTokens() == null
                ? 16_000 : request.maxContextTokens();
        Driver driver = request == null || request.driver() == null
                ? Driver.SOURCE_ORDER : Driver.valueOf(request.driver());
        componentClient.forWorkflow(runId).method(IndexWorkflow::start)
                .invoke(new IndexWorkflow.Start(budget, driver,
                        java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()));
        return status(runId);
    }

    @Get("/{runId}/status")
    public RunStatus status(String runId) {
        return RunStatus.of(
                componentClient.forWorkflow(runId).method(IndexWorkflow::get).invoke());
    }

    @Get("/{runId}/communities")
    public CommunityView.CommunityEntries communities(String runId) {
        return componentClient.forView().method(CommunityView::byRun).invoke(runId);
    }

    @Get("/{runId}/communities/level/{level}")
    public CommunityView.CommunityEntries communitiesAtLevel(String runId, int level) {
        return componentClient.forView().method(CommunityView::byRunAndLevel)
                .invoke(new CommunityView.RunLevel(runId, level));
    }

    @Get("/{runId}/communities/{community}")
    public CommunityDetail community(String runId, int community) {
        CommunityEntity.State state = componentClient
                .forEventSourcedEntity(runId + ":" + community)
                .method(CommunityEntity::get).invoke();
        return new CommunityDetail(state.community(), state.level(), state.parent(),
                state.children(), state.title(), state.size(), state.entityIds(),
                state.relationshipIds(), state.textUnitIds(), state.period(),
                state.localContext(), state.localContextSize(),
                state.localContextExceedsBudget(), state.rolledContext(),
                state.rolledContextSize(), state.report());
    }

    @Post("/edge-list")
    public EdgeListResponse edgeList(EdgeListRequest request) {
        EdgeListPreparation.Options options = new EdgeListPreparation.Options(
                request.useLargestConnectedComponent() == null
                        || request.useLargestConnectedComponent(),
                Boolean.TRUE.equals(request.tieBreakOnId()));
        return new EdgeListResponse(
                EdgeListPreparation.prepare(request.relationships(), options));
    }
}
