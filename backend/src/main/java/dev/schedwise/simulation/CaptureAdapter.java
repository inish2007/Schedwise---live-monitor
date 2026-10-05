package dev.schedwise.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.schedwise.scheduler.Job;
import dev.schedwise.scheduler.LinuxWeights;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Adapter that constructs immutable scheduling inputs from real captured sessions.
 * 
 * Rules:
 * 1. An application request arrival is not an exact kernel wake-up trace.
 * 2. Served CPU time under contention is a lower bound on requested demand.
 * 3. Never invent exact future bursts, arrivals, or remaining demand from /proc samples alone.
 * 4. Insufficient inputs return an explicit INSUFFICIENT result status with documented reasons.
 */
public final class CaptureAdapter {
    private final ObjectMapper json;

    public record CapturedWorkload(
            String captureId,
            String phase,
            String inputKind,
            String sufficiency,
            String notes,
            long horizonNs,
            List<Job> jobs
    ) {}

    public CaptureAdapter(ObjectMapper json) {
        this.json = json;
    }

    private CapturedWorkload insufficient(String id,String phase,String reason){return new CapturedWorkload(id,phase,"INSTRUMENTED_DEMO","INSUFFICIENT",reason,0L,List.of());}

    public CapturedWorkload loadFromExperiment(Path experimentDir, String phaseName, Integer backgroundNiceOverride) throws IOException {
        String captureId = experimentDir.getFileName().toString();
        Path eventsFile = experimentDir.resolve("events.jsonl");
        Path summaryFile = experimentDir.resolve("summary.json");

        if (!Files.isRegularFile(eventsFile) || !Files.isRegularFile(summaryFile)) {
            return new CapturedWorkload(captureId, phaseName, "INSTRUMENTED_DEMO", "INSUFFICIENT",
                    "Missing events.jsonl or summary.json in capture directory", 0L, List.of());
        }

        JsonNode summary;
        try{summary=json.readTree(summaryFile.toFile());}catch(IOException e){return insufficient(captureId,phaseName,"Unreadable or malformed capture summary");}
        if(summary==null)return insufficient(captureId,phaseName,"Empty summary");
        JsonNode phases = summary.path("phases");
        long phaseStartNs = -1L;
        long phaseEndNs = -1L;

        for (JsonNode p : phases) {
            if (phaseName.equalsIgnoreCase(p.path("name").asText())) {
                phaseStartNs = p.path("startNs").asLong();
                phaseEndNs = p.path("endNs").asLong();
                break;
            }
        }

        if (phaseStartNs <= 0 || phaseEndNs <= phaseStartNs) {
            return new CapturedWorkload(captureId, phaseName, "INSTRUMENTED_DEMO", "INSUFFICIENT",
                    "Phase '" + phaseName + "' not found or has non-positive duration in summary.json", 0L, List.of());
        }

        long horizonNs = phaseEndNs - phaseStartNs;

        List<Job> jobs = new ArrayList<>();
        int requestCount = 0;
        int outsideWindow=0;
        Set<String> ids=new HashSet<>();
        if(Files.size(eventsFile)>32L*1024*1024)return insufficient(captureId,phaseName,"Capture exceeds 32 MiB input bound");

        try (BufferedReader reader = Files.newBufferedReader(eventsFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"service_request\"") && !line.contains("\"request\"")) continue;
                JsonNode event;
                try{event=json.readTree(line);}catch(IOException e){return insufficient(captureId,phaseName,"Malformed event journal");}
                JsonNode payload = event.path("payload");
                if (payload.isMissingNode() || payload.isNull()) {
                    payload = event;
                }

                if (!phaseName.equalsIgnoreCase(payload.path("phase").asText())) continue;

                if ("service_request".equals(payload.path("event").asText())) {
                    String reqId = payload.path("requestId").asText();
                    long arrivalNs = payload.path("arrivalNs").asLong();
                    if(arrivalNs<phaseStartNs||arrivalNs>=phaseEndNs){outsideWindow++;continue;}
                    long offsetNs = arrivalNs - phaseStartNs;
                    long cpuServiceNs = payload.path("cpuServiceNs").asLong();
                    if (cpuServiceNs <= 0 || reqId.isBlank() || !ids.add(reqId))
                        return insufficient(captureId,phaseName,"Invalid, duplicate, or missing measured service request data");
                    if(requestCount>=20000)return insufficient(captureId,phaseName,"Capture exceeds 20,000 modeled request bound");

                    // Protected Service has nice 0 (weight 1024), priority 0
                    jobs.add(new Job(reqId, "PROTECTED_SERVICE", offsetNs, cpuServiceNs, 0, 0, LinuxWeights.niceToWeight(0)));
                    requestCount++;
                }
            }
        }

        if (jobs.isEmpty()) {
            return new CapturedWorkload(captureId, phaseName, "INSTRUMENTED_DEMO", "INSUFFICIENT",
                    "Zero valid service request events found in phase " + phaseName, horizonNs, List.of());
        }

        // If CONTENTION phase, incorporate the background batch hashing workers
        if ("CONTENTION".equalsIgnoreCase(phaseName)) {
            int bgNice = backgroundNiceOverride != null ? backgroundNiceOverride : 0;
            int bgWeight = LinuxWeights.niceToWeight(bgNice);
            int bgPriority = Math.max(1, bgNice + 1); // lower priority than protected service (priority 0)

            JsonNode workers = summary.path("workers");
            if (workers.isArray() && workers.size() >= 2) {
                for (int i = 0; i < 2; i++) {
                    JsonNode w = workers.get(i);
                    String role = w.path("role").asText();
                    long cpuNs = w.path("progress").path("cpuNs").asLong();
                    long elapsedNs = w.path("progress").path("elapsedNs").asLong();
                    if(!role.startsWith("BACKGROUND") || cpuNs<=0 || elapsedNs<=0)
                        return insufficient(captureId,phaseName,"Missing measured background CPU/time/role evidence");
                    // Explicit aggregate approximation; not an observed burst or arrival.
                    long demandNs=Math.round(cpuNs*Math.min(1.0,(double)horizonNs/elapsedNs));
                    if(demandNs<=0)return insufficient(captureId,phaseName,"Measured background demand cannot support this window");
                    // Background job arrives at beginning of contention window
                    jobs.add(new Job("bg-" + (i + 1), role, 0L, demandNs, bgPriority, bgNice, bgWeight));
                }
            } else {
                return insufficient(captureId,phaseName,"Missing measured background workers; no synthetic cohort is substituted");
            }
        }

        jobs.sort(Comparator.comparingLong(Job::getArrivalNs).thenComparing(Job::getId));

        String notes = "Background inputs, when present, scale observed aggregate CPU service to the phase duration and assume arrival at phase start; these are model assumptions, not exact runnable bursts. Captured " + requestCount + " requests in " + phaseName + " phase. Horizon: " + (horizonNs / 1_000_000_000.0) + "s. Excluded " + outsideWindow + " service arrivals outside the phase window.";
        return new CapturedWorkload(captureId, phaseName, "INSTRUMENTED_DEMO", "SUFFICIENT", notes, horizonNs, jobs);
    }
}
