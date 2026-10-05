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

    public synchronized Recommendation generateForActiveExperiment() { return generateForExperiment(null); }

    public synchronized Recommendation generateForExperiment(String experimentId) {
        JsonNode exp = experimentId == null ? experimentManager.latest() : experimentManager.get(experimentId);
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
        if(captureId==null||!captureId.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid capture ID");
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
            remember(rec);
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
                    if(!w.path("observed").path("nice").isInt()) continue;
                    int nice = w.path("observed").path("nice").asInt();
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
                        if(!c.path("observed").path("nice").isInt()) continue;
                        int nice = c.path("observed").path("nice").asInt();
                        targetWorkers.add(new TargetWorker(id.pid(), c.path("role").asText(), id, nice));
                    }
                }
            }
        }

        if (targetWorkers.isEmpty()) {
            return remember(new Recommendation(
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
            ));
        }

        // Run CFS simulations for candidates if capture events exist
        CandidateScenario c5 = evaluateCandidate(expId, summary, targetWorkers, 5, "Set worker nice to 5", !isCapture);
        CandidateScenario c10 = evaluateCandidate(expId, summary, targetWorkers, 10, "Set worker nice to 10", !isCapture);

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
        remember(rec);
        return rec;
    }

    private CandidateScenario evaluateCandidate(
            String expId,
            JsonNode summary,
            List<TargetWorker> workers,
            int targetNice,
            String title,
            boolean liveExperiment
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

        double currentTotal=wService+workers.stream().mapToInt(w->LinuxWeights.niceToWeight(w.currentNice())).sum();
        String tradeoff = String.format(Locale.US,
                "Modeled protected share: %.1f%% → %.1f%%. Each background worker receives %.1f%% while all tasks are runnable. Background progress may slow; measure the result.",
                wService/currentTotal*100,protectedShare,bgShare);
        boolean eligible=liveExperiment&&workers.stream().allMatch(w->w.currentNice()<targetNice);

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
                eligible,
                !liveExperiment ? "Recorded experiment: review only. Start a live experiment to apply a change." : !eligible ? "This candidate does not increase nice for every worker. Evaluate fresh workers." : "Managed background workers; identities and permissions are checked again before applying."
        );
    }

    public synchronized Optional<Recommendation> findById(String id) {
        Recommendation r = recommendations.get(id);
        if (r != null) {
            // Check expiration (60 seconds)
            if ("ACTIVE".equals(r.status()) && Instant.now().isAfter(Instant.parse(r.expiresAt()))) {
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


    private Recommendation remember(Recommendation rec) {
        if(recommendations.size()>=100) recommendations.values().stream().min(Comparator.comparing(Recommendation::createdAt)).ifPresent(old->recommendations.remove(old.id()));
        recommendations.put(rec.id(),rec); latest=rec; return rec;
    }

    public synchronized Recommendation reject(String id) {
        Recommendation rec=findById(id).orElseThrow(()->new NoSuchElementException("Unknown recommendation"));
        if("REJECTED".equals(rec.status())) return rec;
        if("APPLIED".equals(rec.status())||"PARTIALLY_APPLIED".equals(rec.status())) throw new IllegalStateException("This recommendation has already changed worker priorities");
        Recommendation rejected=withStatus(rec,"REJECTED"); recommendations.put(id,rejected); return rejected;
    }

    private Recommendation withStatus(Recommendation r,String status) {
        return new Recommendation(r.id(),r.experimentId(),r.createdAt(),r.expiresAt(),status,r.evidence(),r.candidates(),r.limitations(),r.referenceModelExclusions(),r.restorationNote(),false);
    }

    public synchronized dev.schedwise.action.LinuxActionAdapter.BatchActionResult apply(String id,String experimentId,
            List<dev.schedwise.action.LinuxActionAdapter.TargetActionRequest> targets,
            java.util.function.Supplier<dev.schedwise.action.LinuxActionAdapter.BatchActionResult> operation) {
        Recommendation rec=findById(id).orElseThrow(()->new NoSuchElementException("Unknown recommendation"));
        if(!"ACTIVE".equals(rec.status())) throw new IllegalStateException("Recommendation is "+rec.status()+". Evaluate again before applying.");
        if(!Objects.equals(experimentId,rec.experimentId())) throw new IllegalArgumentException("Experiment does not match recommendation");
        boolean matches=targets!=null&&rec.candidates().stream().filter(CandidateScenario::eligible).anyMatch(c->
            targets.size()==c.targetWorkers().size()&&new HashSet<>(targets).size()==targets.size()&&c.targetWorkers().stream().allMatch(w->targets.stream().anyMatch(t->t.pid()==w.pid()&&Objects.equals(t.identity(),w.identity())&&t.expectedCurrentNice()==w.currentNice()&&t.requestedNice()==c.targetNice())));
        if(!matches) throw new IllegalArgumentException("Targets do not match an eligible recommendation candidate");
        var result=operation.get();
        if("SUCCESS".equals(result.overallStatus())) recommendations.put(id,withStatus(rec,"APPLIED"));
        else if(result.targets().stream().anyMatch(t->"SUCCESS".equals(t.status()))) recommendations.put(id,withStatus(rec,"PARTIALLY_APPLIED"));
        return result;
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
        return remember(new Recommendation(
                UUID.randomUUID().toString(),
                "none",
                Instant.now().toString(),
                Instant.now().plusSeconds(60).toString(),
                "INSUFFICIENT_EVIDENCE",
                new ContentionEvidence("INSUFFICIENT_EVIDENCE", -1, null, null, null, null, null, null, null, null, false, reason, List.of(), "No active workload scope."),
                List.of(),
                commonLimitations(),
                commonReferenceModelExclusions(),
                restorationWarning(),
                false
        ));
    }
}
