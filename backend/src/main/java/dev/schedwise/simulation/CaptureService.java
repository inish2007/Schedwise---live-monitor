package dev.schedwise.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.schedwise.model.Telemetry.Kind;
import dev.schedwise.scheduler.*;
import dev.schedwise.simulation.SimulationModels.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class CaptureService {
    private final ObjectMapper json;
    private final CaptureAdapter adapter;

    public CaptureService(ObjectMapper json) {
        this.json = json;
        this.adapter = new CaptureAdapter(json);
    }

    private Path experimentsRoot() {
        return Path.of(System.getProperty("schedwise.data", "../data")).toAbsolutePath().resolve("experiments");
    }

    public List<CaptureInfo> listCaptures() {
        Path root = experimentsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        List<CaptureInfo> list = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                Path summaryFile = dir.resolve("summary.json");
                Path manifestFile = dir.resolve("manifest.json");

                String state = "UNKNOWN";
                String testedAt = "Unknown";
                Integer core = null;
                List<Integer> observers = List.of();
                int rawRequests = 0;
                int sampleCount = 0;
                boolean hasBaseline = false;
                boolean hasContention = false;

                if (Files.isRegularFile(summaryFile)) {
                    try {
                        JsonNode s = json.readTree(summaryFile.toFile());
                        state = s.path("state").asText("COMPLETED");
                        core = s.path("core").isNumber() ? s.path("core").asInt() : null;
                        rawRequests = s.path("requestEvidenceCount").asInt(0);
                        if (s.path("observerCpus").isArray()) {
                            List<Integer> obs = new ArrayList<>();
                            for (JsonNode n : s.path("observerCpus")) obs.add(n.asInt());
                            observers = obs;
                        }
                        JsonNode summaries = s.path("summaries");
                        JsonNode bSummary = summaries.path("BASELINE");
                        if (!bSummary.isMissingNode() && bSummary.path("scheduledCount").asInt(0) > 0) {
                            hasBaseline = true;
                        }
                        JsonNode cSummary = summaries.path("CONTENTION");
                        if (!cSummary.isMissingNode() && cSummary.path("scheduledCount").asInt(0) > 0) {
                            hasContention = true;
                        }
                    } catch (Exception ignored) {}
                }

                if (Files.isRegularFile(manifestFile)) {
                    try {
                        JsonNode m = json.readTree(manifestFile.toFile());
                        testedAt = m.path("startedAt").asText("Unknown");
                    } catch (Exception ignored) {}
                }

                Path samplesFile = dir.resolve("samples.jsonl");
                if (Files.isRegularFile(samplesFile)) {
                    try (var lines = Files.lines(samplesFile)) {
                        sampleCount = (int) lines.count();
                    } catch (Exception ignored) {}
                }

                String sufficiency = (hasBaseline && hasContention && rawRequests > 0) ? "SUFFICIENT" : "PARTIAL";
                String notes = "Core " + (core != null ? core : "?") + ", " + rawRequests + " requests, " + sampleCount + " samples.";

                list.add(new CaptureInfo(id, state, testedAt, core, observers, rawRequests, sampleCount,
                        hasBaseline, hasContention, sufficiency, notes));
            }
        } catch (IOException e) {
            return List.of();
        }

        list.sort(Comparator.comparing(CaptureInfo::testedAt).reversed());
        return list;
    }

    public CaptureInfo getCapture(String id) {
        return listCaptures().stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Capture not found: " + id));
    }

    public SimulationResponse simulate(SimulationRequest request) throws IOException {
        if (request.captureId() == null || request.captureId().isBlank()) {
            throw new IllegalArgumentException("captureId must be provided");
        }

        if(!request.captureId().matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new IllegalArgumentException("captureId must be a captured session UUID");
        Path root=experimentsRoot().toAbsolutePath().normalize();
        Path dir = root.resolve(request.captureId()).normalize();
        if(Files.isSymbolicLink(dir)||!dir.startsWith(root))throw new IllegalArgumentException("Invalid capture path");
        if (!Files.isDirectory(dir)) {
            throw new NoSuchElementException("Capture directory not found: " + request.captureId());
        }

        String phase=request.phase()==null?"CONTENTION":request.phase().toUpperCase(Locale.ROOT);
        if(!Set.of("BASELINE","CONTENTION").contains(phase))throw new IllegalArgumentException("Unsupported capture phase");
        long quantumNs=bounded(request.quantumNs(),20_000_000L,500_000_000L,"quantumNs");
        long cfsTargetNs=bounded(request.cfsLatencyTargetNs(),24_000_000L,500_000_000L,"cfsLatencyTargetNs");
        long cfsMinGranNs=bounded(request.cfsMinGranularityNs(),3_000_000L,500_000_000L,"cfsMinGranularityNs");
        int maxEvents=(int)bounded(request.maxEvents()==null?null:request.maxEvents().longValue(),10000,20000,"maxEvents");
        bounded(request.horizonNs(),180_000_000_000L,180_000_000_000L,"horizonNs");
        Integer bgNice=request.backgroundNice();
        if(bgNice!=null&&(bgNice<0||bgNice>19))throw new IllegalArgumentException("backgroundNice must be 0–19");
        if(request.models()!=null&&(request.models().size()>5||request.models().stream().anyMatch(Objects::isNull)))throw new IllegalArgumentException("Select at most five non-null models");

        CaptureAdapter.CapturedWorkload workload = adapter.loadFromExperiment(dir, phase, bgNice);

        if (!"SUFFICIENT".equals(workload.sufficiency()) || workload.jobs().isEmpty()) {
            return new SimulationResponse(
                    request.captureId(), phase, workload.inputKind(), workload.sufficiency(),
                    workload.notes(), workload.horizonNs(), Map.of(), Kind.SIMULATED
            );
        }

        long horizonNs = (request.horizonNs() != null && request.horizonNs() > 0)
                ? Math.min(workload.horizonNs(), request.horizonNs())
                : workload.horizonNs();

        List<String> requestedModels = request.models();
        if (requestedModels == null || requestedModels.isEmpty()) {
            requestedModels = List.of("FCFS", "ROUND_ROBIN", "PRIORITY", "SJF", "CFS");
        }

        Map<String, AlgorithmResult> results = new LinkedHashMap<>();

        for (String m : requestedModels) {
            Scheduler scheduler = switch (m.toUpperCase()) {
                case "FCFS" -> new FcfsScheduler();
                case "ROUND_ROBIN", "RR" -> new RoundRobinScheduler(quantumNs);
                case "PRIORITY" -> new PriorityScheduler();
                case "SJF" -> new SjfScheduler();
                case "CFS", "SIMPLIFIED_CFS" -> new SimplifiedCfsScheduler(cfsTargetNs, cfsMinGranNs);
                default -> throw new IllegalArgumentException("Unknown scheduling algorithm: " + m);
            };

            SimulationEngine engine = new SimulationEngine(scheduler, workload.jobs(), horizonNs, maxEvents);
            AlgorithmResult result = engine.run();
            results.put(m.toUpperCase(), result);
        }

        return new SimulationResponse(
                request.captureId(),
                phase,
                workload.inputKind(),
                workload.sufficiency(),
                workload.notes() + " Simulated " + results.size() + " algorithms over " + (horizonNs / 1_000_000_000.0) + "s.",
                horizonNs,
                results,
                Kind.SIMULATED
        );
    }
    private static long bounded(Long value,long defaultValue,long max,String name){
        if(value==null)return defaultValue;
        if(value<=0||value>max)throw new IllegalArgumentException(name+" must be in [1, "+max+"]");
        return value;
    }
}
