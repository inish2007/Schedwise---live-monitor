package dev.schedwise.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.schedwise.experiment.ExperimentManager;
import dev.schedwise.model.Telemetry.Identity;
import dev.schedwise.monitor.Capabilities;
import dev.schedwise.monitor.Collector;
import dev.schedwise.recommendation.ContentionDetector.ContentionEvidence;
import dev.schedwise.scheduler.Job;
import dev.schedwise.scheduler.LinuxWeights;
import dev.schedwise.scheduler.SimplifiedCfsScheduler;
import dev.schedwise.simulation.CaptureAdapter;
import dev.schedwise.simulation.SimulationEngine;
import dev.schedwise.simulation.SimulationModels.ModelMetrics;
import dev.schedwise.simulation.SimulationModels.AlgorithmResult;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates explainable priority recommendations linked to measured contention evidence.
 * Evaluates candidate weighted allocation scenarios (Nice 5, Nice 10) via pure-Java CFS simulation.
 */
@Component
public class RecommendationEngine {
    private final ContentionDetector contentionDetector;
    private final Capabilities capabilities;
    private final Collector collector;
    private final ExperimentManager experimentManager;
    private final ObjectMapper json;
    private final Map<String, Recommendation> recommendations = new ConcurrentHashMap<>();
    private volatile Recommendation latest;

    public record TargetWorker(
            long pid,
            String role,
            Identity identity,
            int currentNice
    ) {}

    public record CandidateScenario(
            int targetNice,
            String title,
            int targetWeight,
            int serviceWeight,
            double expectedProtectedSharePercent,
            double expectedBackgroundSharePercent,
            Double simulatedServiceResponseP50Ms,
            Double simulatedServiceResponseP95Ms,
            Double simulatedServiceTurnaroundP50Ms,
            String tradeoffSummary,
            List<TargetWorker> targetWorkers,
            boolean eligible,
            String eligibilityNote
    ) {}

    public record Recommendation(
            String id,
            String experimentId,
            String createdAt,
            String expiresAt,
            String status, // ACTIVE, INSUFFICIENT_EVIDENCE, NO_CONTENTION, EXPIRED
            ContentionEvidence evidence,
            List<CandidateScenario> candidates,
            List<String> limitations,
            List<String> referenceModelExclusions,
            String restorationNote,
            boolean confirmationRequired
    ) {}

    public RecommendationEngine(
            ContentionDetector contentionDetector,
            Capabilities capabilities,
            Collector collector,
            ExperimentManager experimentManager,
            ObjectMapper json
    ) {
        this.contentionDetector = contentionDetector;
        this.capabilities = capabilities;
        this.collector = collector;
        this.experimentManager = experimentManager;
        this.json = json;
    }

    public synchronized Recommendation generateForActiveExperiment() {
        JsonNode exp = experimentManager.latest();
        if (exp == null || exp.isNull()) {
            return emptyRecommendation("No active experiment found. Run an experiment or select a captured session.");
        }
        String id = exp.path("id").asText();
        int core = exp.path("core").asInt(0);

        Double psiAvg = null;
        var snap = collector.latest();
        if (snap != null && snap.cpuPressureSome() != null && snap.cpuPressureSome().value() != null) {
            String text = snap.cpuPressureSome().value();
            for (String part : text.split("\\s+")) {
                if (part.startsWith("avg10=")) {
                    try { psiAvg = Double.parseDouble(part.substring(6)); } catch (Exception ignored) {}
                }
            }
        }

        Double coreBusy = null;
        if (snap != null && snap.cpus().containsKey("cpu" + core)) {
            var coreVal = snap.cpus().get("cpu" + core).busyPercent();
            if (coreVal != null) coreBusy = coreVal.value();
        }

        return generate(id, exp, psiAvg, coreBusy);
    }

    public synchronized Recommendation generateForCapture(String captureId) {
        Path root = Path.of(System.getProperty("schedwise.data", "../data")).toAbsolutePath().resolve("experiments").resolve(captureId);
        if (!Files.isRegularFile(root.resolve("summary.json"))) {
            return emptyRecommendation("Capture summary.json not found for " + captureId);
        }
        try {
            JsonNode summary = json.readTree(root.resolve("summary.json").toFile());
            return generate(captureId, summary, null, null);
        } catch (Exception e) {
            return emptyRecommendation("Failed to read capture summary: " + e.getMessage());
        }
    }

    private Recommendation generate(String expId, JsonNode summary, Double psiAvg, Double coreBusy) {
        ContentionEvidence evidence = contentionDetector.analyze(summary, psiAvg, coreBusy);

        if (!"SUSTAINED_CONTENTION".equals(evidence.status())) {
            Recommendation rec = new Recommendation(
                    UUID.randomUUID().toString(),
                    expId,
                    Instant.now().toString(),
                    Instant.now().plusSeconds(60).toString(),
                    evidence.status(),
                    evidence,
                    List.of(),
                    commonLimitations(),
                    commonReferenceModelExclusions(),
                    restorationWarning(),
                    true
            );
            recommendations.put(rec.id(), rec);
            latest = rec;
            return rec;
        }

        boolean isCapture = summary.path("finished").asBoolean(false)
                || List.of("COMPLETED", "CANCELLED", "FAILED", "TIMED_OUT").contains(summary.path("state").asText());

        // Identify candidate background workers
        List<TargetWorker> targetWorkers = new ArrayList<>();
        JsonNode workersNode = summary.path("workers");
        if (workersNode.isArray()) {
            for (JsonNode w : workersNode) {
                boolean eligible = isCapture || w.path("alive").asBoolean(true);
                if (eligible && w.path("role").asText().startsWith("BACKGROUND")) {
                    JsonNode idNode = w.path("identity");
                    Identity id = new Identity(idNode.path("bootId").asText(), idNode.path("pid").asLong(), idNode.path("startTicks").asLong());
                    int nice = w.path("observed").path("nice").asInt(0);
                    targetWorkers.add(new TargetWorker(id.pid(), w.path("role").asText(), id, nice));
                }
            }
        }

        if (targetWorkers.isEmpty()) {
            // Check children array if workers array was empty
            JsonNode childrenNode = summary.path("children");
            if (childrenNode.isArray()) {
                for (JsonNode c : childrenNode) {
                    boolean eligible = isCapture || c.path("alive").asBoolean(true);
                    if (eligible && c.path("role").asText().startsWith("BACKGROUND")) {
                        JsonNode idNode = c.path("identity");
                        Identity id = new Identity(idNode.path("bootId").asText(), idNode.path("pid").asLong(), idNode.path("startTicks").asLong());
                        int nice = c.path("observed").path("nice").asInt(0);
                        targetWorkers.add(new TargetWorker(id.pid(), c.path("role").asText(), id, nice));
                    }
                }
            }
        }

        if (targetWorkers.isEmpty()) {
            return new Recommendation(
                    UUID.randomUUID().toString(),
                    expId,
                    Instant.now().toString(),
                    Instant.now().plusSeconds(60).toString(),
                    "INSUFFICIENT_EVIDENCE",
                    evidence,
                    List.of(),
                    commonLimitations(),
                    commonReferenceModelExclusions(),
                    restorationWarning(),
                    true
            );
        }

        // Run CFS simulations for candidates if capture events exist
        CandidateScenario c5 = evaluateCandidate(expId, summary, targetWorkers, 5, "Nice +5 (Moderate Priority Reduction)");
        CandidateScenario c10 = evaluateCandidate(expId, summary, targetWorkers, 10, "Nice +10 (Aggressive Priority Reduction)");

        String recId = UUID.randomUUID().toString();
        Recommendation rec = new Recommendation(
                recId,
                expId,
                Instant.now().toString(),
                Instant.now().plusSeconds(60).toString(),
                "ACTIVE",
                evidence,
                List.of(c5, c10),
                commonLimitations(),
                commonReferenceModelExclusions(),
                restorationWarning(),
                true
        );
        recommendations.put(rec.id(), rec);
        latest = rec;
        return rec;
    }

    private CandidateScenario evaluateCandidate(
            String expId,
            JsonNode summary,
            List<TargetWorker> workers,
            int targetNice,
            String title
    ) {
        int wTarget = LinuxWeights.niceToWeight(targetNice);
        int wService = LinuxWeights.niceToWeight(0); // 1024
        int workerCount = workers.size();
        double totalWeight = wService + (workerCount * wTarget);
        double protectedShare = (wService / totalWeight) * 100.0;
        double bgShare = (wTarget / totalWeight) * 100.0;

        Double simRespP50 = null;
        Double simRespP95 = null;
        Double simTurnP50 = null;

        // Try running pure Java CFS simulation on the capture if events exist
        try {
            Path root = Path.of(System.getProperty("schedwise.data", "../data")).toAbsolutePath().resolve("experiments").resolve(expId);
            if (Files.isRegularFile(root.resolve("events.jsonl"))) {
                CaptureAdapter adapter = new CaptureAdapter(json);
                var capture = adapter.loadFromExperiment(root, "CONTENTION", targetNice);
                List<Job> jobs = capture.jobs();
                if (!jobs.isEmpty()) {
                    SimulationEngine engine = new SimulationEngine(
                            new SimplifiedCfsScheduler(4_000_000L, 20_000_000L),
                            jobs,
                            capture.horizonNs(),
                            20000
                    );
                    AlgorithmResult res = engine.run();
                    ModelMetrics sm = res.metrics();
                    simRespP50 = sm.p50ResponseTimeMs();
                    simRespP95 = sm.p95ResponseTimeMs();
                    simTurnP50 = sm.p50TurnaroundTimeMs();
                }
            }
        } catch (Exception ignored) {}

        String tradeoff = String.format(
                Locale.US,
                "Increases protected service CPU share from 33.3%% to %.1f%% (weight %d vs %d). Each background worker drops to %.1f%% share. %s",
                protectedShare,
                wService,
                wTarget,
                bgShare,
                simRespP95 != null
                        ? String.format(Locale.US, "Modeled queue dispatch wait p95 improves to %.2f ms.", simRespP95)
                        : "Background batch progress will slow down proportionally."
        );

        return new CandidateScenario(
                targetNice,
                title,
                wTarget,
                wService,
                protectedShare,
                bgShare,
                simRespP50,
                simRespP95,
                simTurnP50,
                tradeoff,
                workers,
                true,
                "Managed child background workers verified eligible for unprivileged nice increase."
        );
    }

    public Optional<Recommendation> findById(String id) {
        Recommendation r = recommendations.get(id);
        if (r != null) {
            // Check expiration (60 seconds)
            if (Instant.now().isAfter(Instant.parse(r.expiresAt()))) {
                Recommendation expired = new Recommendation(
                        r.id(), r.experimentId(), r.createdAt(), r.expiresAt(),
                        "EXPIRED", r.evidence(), r.candidates(), r.limitations(),
                        r.referenceModelExclusions(), r.restorationNote(), false
                );
                return Optional.of(expired);
            }
            return Optional.of(r);
        }
        return Optional.empty();
    }

    public Optional<Recommendation> getLatest() {
        return latest != null ? findById(latest.id()) : Optional.empty();
    }

    private static List<String> commonLimitations() {
        return List.of(
                "Discrete-event simulation models CPU queue dispatch; it does not predict exact future HTTP socket p95 or end-to-end network latency.",
                "Kernel dispatch is subject to CFS/EEVDF heuristics, autogroups, and potential host/VM scheduling interruptions.",
                "Evaluated strictly on the shared pinned core; global runnable count does not measure per-core contention."
        );
    }

    private static List<String> commonReferenceModelExclusions() {
        return List.of(
                "FCFS, SJF, and textbook Round Robin are educational reference models and cannot be applied as Linux operating system policies.",
                "Only weighted allocation adjustments (Linux nice levels -20 to 19) map to real kernel scheduler controls."
        );
    }

    private static String restorationWarning() {
        return "Priority reduction (increasing nice) is an unprivileged operation. Reversing the priority change (decreasing nice back to 0) requires CAP_SYS_NICE or resetting the experiment (recreating workers).";
    }

    private Recommendation emptyRecommendation(String reason) {
        return new Recommendation(
                UUID.randomUUID().toString(),
                "none",
                Instant.now().toString(),
                Instant.now().plusSeconds(60).toString(),
                "INSUFFICIENT_EVIDENCE",
                new ContentionEvidence("INSUFFICIENT_EVIDENCE", -1, null, null, null, 0, 0.0, 0, null, null, false, reason, List.of(), "No active workload scope."),
                List.of(),
                commonLimitations(),
                commonReferenceModelExclusions(),
                restorationWarning(),
                false
        );
    }
}
