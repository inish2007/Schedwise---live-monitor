package dev.schedwise.api;

import dev.schedwise.action.LinuxActionAdapter;
import dev.schedwise.action.LinuxActionAdapter.BatchActionResult;
import dev.schedwise.action.LinuxActionAdapter.TargetActionRequest;
import dev.schedwise.experiment.ExperimentManager;
import dev.schedwise.model.Telemetry.Identity;
import dev.schedwise.model.WorkloadRole;
import dev.schedwise.persistence.ActionAuditStore;
import dev.schedwise.persistence.ActionAuditStore.ActionAudit;
import dev.schedwise.recommendation.RecommendationEngine;
import dev.schedwise.recommendation.RecommendationEngine.Recommendation;
import dev.schedwise.recommendation.RoleRegistry;
import dev.schedwise.recommendation.RoleRegistry.RoleAssignment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class RecommendationAndActionApi {
    private final RoleRegistry roleRegistry;
    private final RecommendationEngine recommendationEngine;
    private final LinuxActionAdapter actionAdapter;
    private final ActionAuditStore auditStore;
    private final ExperimentManager experimentManager;

    private record CompletedAction(ActionNiceRequest request, BatchActionResult result) {}
    private final Map<String, CompletedAction> completedActions = new LinkedHashMap<>();

    public record RoleTagRequest(long pid, Identity identity, String role, String comment) {}
    public record RecommendationRequest(String experimentId, String captureId) {}
    public record ActionNiceRequest(
            String actionId,
            String experimentId,
            String recommendationId,
            List<TargetActionRequest> targets
    ) {}

    public RecommendationAndActionApi(
            RoleRegistry roleRegistry,
            RecommendationEngine recommendationEngine,
            LinuxActionAdapter actionAdapter,
            ActionAuditStore auditStore,
            ExperimentManager experimentManager
    ) {
        this.roleRegistry = roleRegistry;
        this.recommendationEngine = recommendationEngine;
        this.actionAdapter = actionAdapter;
        this.auditStore = auditStore;
        this.experimentManager = experimentManager;
    }

    @GetMapping("/roles")
    public ResponseEntity<Map<String, Object>> getRoles() {
        return ResponseEntity.ok(Map.of(
                "userTags", roleRegistry.getUserTags(),
                "availableRoles", Arrays.stream(WorkloadRole.values()).map(r -> Map.of(
                        "name", r.name(),
                        "label", r.label(),
                        "description", r.description()
                )).toList(),
                "v1Scope", "Only managed demo background workers are eligible for priority control in v1."
        ));
    }

    @PostMapping("/roles/tags")
    public ResponseEntity<RoleAssignment> tagRole(@RequestBody RoleTagRequest req) {
        WorkloadRole role = WorkloadRole.fromString(req.role());
        RoleAssignment assignment = roleRegistry.tagProcess(req.pid(), req.identity(), role, req.comment());
        return ResponseEntity.ok(assignment);
    }

    @PostMapping("/recommendations")
    public ResponseEntity<Recommendation> createRecommendation(@RequestBody(required = false) RecommendationRequest req) {
        if (req != null && req.captureId() != null && !req.captureId().isBlank()) {
            return ResponseEntity.ok(recommendationEngine.generateForCapture(req.captureId()));
        }
        return ResponseEntity.ok(recommendationEngine.generateForExperiment(req == null ? null : req.experimentId()));
    }

    @GetMapping("/recommendations/latest")
    public ResponseEntity<Recommendation> getLatestRecommendation() {
        return recommendationEngine.getLatest()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/recommendations/{id}")
    public ResponseEntity<Recommendation> getRecommendation(@PathVariable String id) {
        return recommendationEngine.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/recommendations/{id}/reject")
    public ResponseEntity<Recommendation> rejectRecommendation(@PathVariable String id) {
        return ResponseEntity.ok(recommendationEngine.reject(id));
    }

    @GetMapping("/actions/nice/{id}")
    public synchronized ResponseEntity<BatchActionResult> getAction(@PathVariable String id) {
        CompletedAction action = completedActions.get(id);
        return action == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(action.result());
    }

    @PostMapping("/actions/nice")
    public synchronized ResponseEntity<BatchActionResult> applyNiceAction(@RequestBody ActionNiceRequest req) {
        if (req.actionId() == null || !req.actionId().matches("[a-f0-9-]{36}"))
            throw new IllegalArgumentException("A UUID action ID is required");
        CompletedAction completed = completedActions.get(req.actionId());
        if (completed != null) {
            if (!completed.request().equals(req)) throw new IllegalStateException("Action ID already belongs to a different request");
            return ResponseEntity.ok(completed.result());
        }
        if (auditStore.findById(req.actionId()).isPresent())
            throw new IllegalStateException("Action was already attempted. Review its audit and evaluate again.");
        if (req.recommendationId() == null || req.recommendationId().isBlank())
            throw new IllegalArgumentException("Recommendation ID is required");
        BatchActionResult result = recommendationEngine.apply(req.recommendationId(), req.experimentId(), req.targets(),
                () -> actionAdapter.applyNice(req.actionId(), req.experimentId(), req.recommendationId(), req.targets()));
        if (completedActions.size() >= 100) completedActions.remove(completedActions.keySet().iterator().next());
        completedActions.put(req.actionId(), new CompletedAction(req, result));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/actions/audits")
    public ResponseEntity<List<ActionAudit>> getAudits(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(auditStore.listRecent(Math.min(100, Math.max(1, limit))));
    }

    @PostMapping("/experiments/{id}/reset")
    public ResponseEntity<?> resetExperiment(@PathVariable String id) {
        return ResponseEntity.ok(experimentManager.resetWorkers(id));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String,String>> missing(Exception e) { return error(404,e); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalid(Exception e) { return error(400,e); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,String>> conflict(Exception e) { return error(409,e); }
    private ResponseEntity<Map<String,String>> error(int status,Exception e) {
        return ResponseEntity.status(status).body(Map.of("error",e.getMessage()));
    }

}
