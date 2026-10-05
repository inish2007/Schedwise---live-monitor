package dev.schedwise.experiment;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.schedwise.linux.*;
import dev.schedwise.model.Telemetry.*;
import dev.schedwise.monitor.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Owns a bounded Python coordinator and its explicitly registered descendants. */
@Component
public class ExperimentManager {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED", "FAILED", "TIMED_OUT", "CLEANUP_FAILED");
    private final Capabilities capabilities;
    private final Collector collector;
    private final LinuxSource linux;
    private final ObjectMapper json;
    private final ExecutorService supervisor = Executors.newSingleThreadExecutor();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private volatile Run current;
    private volatile boolean closing;

    public record Owned(Identity identity, ProcessHandle handle, String role) {}
    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final int core;
        final Path directory;
        final List<Integer> allowed;
        final List<Integer> observers;
        final ConcurrentMap<Long, Owned> children = new ConcurrentHashMap<>();
        final long startedNanos = System.nanoTime();
        volatile JsonNode summary;
        volatile Process process;
        volatile boolean stop;
        volatile boolean finished;
        volatile String failure;
        volatile Identity coordinatorIdentity;
        Run(int core, Path directory, List<Integer> allowed, List<Integer> observers) {
            this.core = core; this.directory = directory.resolve(id); this.allowed = allowed; this.observers = observers;
        }
    }

    private final dev.schedwise.persistence.ActionAuditStore actionAuditStore;

    @org.springframework.beans.factory.annotation.Autowired
    public ExperimentManager(Capabilities capabilities, Collector collector, LinuxSource linux, ObjectMapper json, dev.schedwise.persistence.ActionAuditStore actionAuditStore) {
        this.capabilities = capabilities; this.collector = collector; this.linux = linux; this.json = json; this.actionAuditStore = actionAuditStore;
    }

    public ExperimentManager(Capabilities capabilities, Collector collector, LinuxSource linux, ObjectMapper json) {
        this(capabilities, collector, linux, json, null);
    }

    public synchronized JsonNode start(Integer requestedCore) throws Exception {
        if (closing) throw new IllegalStateException("Backend is stopping");
        if (current != null && !current.finished) throw new IllegalStateException("An experiment is already active");
        if (capabilities.bootId == null || capabilities.clockTicks <= 0) throw new IllegalStateException("Linux identity/clock capabilities unavailable");
        Map<String,String> status = ProcParser.status(linux.read("/proc/self/status"));
        if (Long.parseLong(status.get("Uid").split("\\s+")[0]) == 0) throw new IllegalStateException("Run the backend as a regular user");
        List<Integer> allowed = List.copyOf(ThreadAffinity.parse(status.get("Cpus_allowed_list")));
        int core = requestedCore == null ? allowed.getLast() : requestedCore;
        if (!allowed.contains(core)) throw new IllegalArgumentException("CPU must be in the backend's allowed mask");
        List<Integer> observers = allowed.size() > 1 ? allowed.stream().filter(c -> c != core).toList() : allowed;
        Path root = Path.of(System.getProperty("schedwise.data", "../data")).toAbsolutePath().resolve("experiments");
        Files.createDirectories(root);
        try (var entries = Files.list(root)) {
            if (entries.count() >= 20) throw new IllegalStateException("Experiment retention limit reached (20); archive old evidence before starting another run");
        }
        for (String name : List.of("/usr/bin/python3", "/usr/bin/taskset"))
            if (!Files.isExecutable(Path.of(name))) throw new IllegalStateException("Missing fixed tool: " + name);
        Path tools = demoRoot();
        for (String name : List.of("common.py", "service.py", "worker.py", "load_client.py", "run_experiment.py"))
            if (!Files.isRegularFile(tools.resolve(name))) throw new IllegalStateException("Missing demo source: " + name);
        Run run = new Run(core, root, allowed, observers);
        Files.createDirectory(run.directory);
        ObjectNode initial = json.createObjectNode();
        initial.put("id", run.id).put("state", "STARTING").put("phase", "STARTING").put("core", core)
                .put("revision", 0).put("timestamp", System.currentTimeMillis()*1_000_000L).put("maxSeconds", 180);
        initial.set("observerCpus", json.valueToTree(observers));
        run.summary = initial;
        ObjectNode manifest = json.createObjectNode();
        manifest.put("schemaVersion", 2).put("experimentId", run.id).put("backendSessionId", capabilities.sessionId)
                .put("environmentId", capabilities.environmentId).put("kind", "MEASURED")
                .put("startedAt", Instant.now().toString()).put("javaVersion", System.getProperty("java.version"))
                .put("appVersion", "0.2.0").put("exportClassification", "RECORDED original measured/derived evidence")
                .put("timing", "Python monotonicNs share CLOCK_MONOTONIC; Java sample timestamps are UTC and not modeled arrivals")
                .put("limitations", "Controlled single-core workload, not a representative server benchmark. No priority changes. Proc identity prechecks alone do not eliminate PID reuse races.");
        manifest.set("capabilities", json.valueToTree(capabilities.discover()));
        json.writerWithDefaultPrettyPrinter().writeValue(run.directory.resolve("manifest.json").toFile(), manifest);
        runs.put(run.id, run); current = run;
        supervisor.submit(() -> execute(run));
        return view(run);
    }

    private Path demoRoot() {
        String prop = System.getProperty("schedwise.demoRoot");
        if (prop != null) return Path.of(prop).toAbsolutePath().normalize();
        if (Files.isDirectory(Path.of("tools/demo"))) return Path.of("tools/demo").toAbsolutePath().normalize();
        return Path.of("../tools/demo").toAbsolutePath().normalize();
    }

    public JsonNode latest() { Run run = current; return run == null ? json.nullNode() : view(run); }

    public synchronized JsonNode get(String id) {
        Run run = runs.get(id);
        if (run == null) throw new NoSuchElementException("No experiment owned by this backend session");
        return view(run);
    }

    public synchronized JsonNode stop(String id) {
        Run run = runs.get(id);
        if (run == null) throw new NoSuchElementException("No experiment owned by this backend session");
        if (!run.finished) { run.stop = true; requestStop(run); }
        return view(run);
    }

    public Optional<Owned> findManagedChild(long pid) {
        Run run = current;
        if (run == null || run.finished) return Optional.empty();
        return Optional.ofNullable(run.children.get(pid));
    }

    public boolean isManagedBackgroundWorker(long pid, Identity expectedIdentity) {
        Run run = current;
        if (run == null || run.finished) return false;
        Owned owned = run.children.get(pid);
        if (owned == null || !owned.role().startsWith("BACKGROUND")) return false;
        return expectedIdentity == null || owned.identity().equals(expectedIdentity);
    }

    public synchronized JsonNode resetWorkers(String id) {
        Run run = runs.get(id);
        if (run == null) throw new NoSuchElementException("No experiment owned by this backend session");
        if (run.finished) {
            ObjectNode res = json.createObjectNode();
            res.put("status", "FINISHED");
            res.put("message", "Experiment has already finished. Start a new experiment to launch fresh workers.");
            return res;
        }
        Process process = run.process;
        if (process != null && process.isAlive()) {
            try {
                synchronized (process) {
                    process.getOutputStream().write("RESET_WORKERS\n".getBytes(StandardCharsets.US_ASCII));
                    process.getOutputStream().flush();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to send reset command to coordinator", e);
            }
        }
        ObjectNode res = json.createObjectNode();
        res.put("status", "RESET_REQUESTED");
        res.put("message", "Reset command dispatched to recreate background workers with fresh identities at default nice 0.");
        return res;
    }

    public synchronized JsonNode getComparison(String id) {
        Run run = runs.get(id);
        JsonNode summaryNode = null;
        if (run != null) {
            summaryNode = run.summary;
        } else {
            Path root = Path.of(System.getProperty("schedwise.data", "../data")).toAbsolutePath().resolve("experiments");
            Path summaryFile = root.resolve(id).resolve("summary.json");
            if (Files.isRegularFile(summaryFile)) {
                try {
                    summaryNode = json.readTree(summaryFile.toFile());
                } catch (IOException ignored) {}
            }
        }
        if (summaryNode == null) {
            throw new NoSuchElementException("No experiment found for ID: " + id);
        }
        return ComparabilityChecker.evaluate(summaryNode).toJson(json);
    }

    private JsonNode view(Run run) {
        ObjectNode node = run.summary.deepCopy();
        node.put("backendSessionId", capabilities.sessionId);
        node.put("finished", run.finished);
        node.put("stopRequested", run.stop);
        node.put("exportAvailable", run.finished && Files.isRegularFile(run.directory.resolve("events.jsonl")));
        if (run.failure != null) node.put("supervisorFailure", run.failure);
        return node;
    }

    private Identity identity(long pid) throws IOException {
        var stat = ProcParser.stat(linux.read("/proc/" + pid + "/stat"));
        return new Identity(capabilities.bootId, pid, stat.startTicks());
    }

    private void requestStop(Run run) {
        Process process = run.process;
        if (process == null || !process.isAlive()) return;
        try { synchronized (process) { process.getOutputStream().write("STOP\n".getBytes(StandardCharsets.US_ASCII)); process.getOutputStream().flush(); } }
        catch (IOException ignored) { /* supervisor's bounded wait and verified fallback remain active */ }
    }

    private void readEvents(Run run) {
        try (InputStream stream = run.process.getInputStream()) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int next;
            long bytes = 0;
            while ((next = stream.read()) != -1) {
                if (++bytes > 12L*1024*1024) throw new IOException("Coordinator output bound exceeded");
                if (next != '\n') {
                    if (line.size() >= 65536) throw new IOException("Coordinator line bound exceeded");
                    line.write(next); continue;
                }
                JsonNode event = json.readTree(line.toByteArray()); line.reset();
                switch (event.path("event").asText()) {
                    case "child_registered" -> {
                        JsonNode value = event.path("identity");
                        long pid = value.path("pid").asLong();
                        Identity id = new Identity(value.path("bootId").asText(), pid, value.path("startTicks").asLong());
                        run.children.entrySet().removeIf(e -> !e.getValue().handle().isAlive());
                        if (pid <= 1 || run.children.size() >= 4 || !id.equals(identity(pid))) throw new IOException("Unverified child identity");
                        ProcessHandle handle = ProcessHandle.of(pid).orElseThrow();
                        if (handle.parent().map(ProcessHandle::pid).orElse(-1L) != run.process.pid()) throw new IOException("Child is outside the coordinator boundary");
                        run.children.put(pid, new Owned(id, handle, event.path("role").asText()));
                    }
                    case "summary" -> run.summary = event.path("summary").deepCopy();
                    default -> { /* Raw evidence is retained by the coordinator, never synthesized here. */ }
                }
            }
        } catch (Exception e) {
            run.failure = "Coordinator stream: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            run.stop = true; requestStop(run);
        }
    }

    private void execute(Run run) {
        String originalAffinity = null;
        Future<?> stream = null;
        try {
            originalAffinity = collector.observerAffinity(null).get(3, TimeUnit.SECONDS);
            String observerMask = String.join(",", run.observers.stream().map(Object::toString).toList());
            collector.observerAffinity(observerMask).get(3, TimeUnit.SECONDS);
            ObjectNode observerRecord = json.createObjectNode();
            observerRecord.put("originalCpuList", originalAffinity).put("verifiedCpuList", collector.observerAffinity(null).get(3, TimeUnit.SECONDS))
                    .put("scope", "collector thread only; other JVM/UI activity can still contribute observer overhead");
            json.writeValue(run.directory.resolve("collector-affinity.json").toFile(), observerRecord);
            if (run.stop) throw new CancellationException("Stopped before launch");
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/taskset", "-c", observerMask,
                    "/usr/bin/python3", "-u", demoRoot().resolve("run_experiment.py").toString());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            run.process = builder.start(); // Keep the owned Process even if identity discovery fails.
            run.coordinatorIdentity = identity(run.process.pid());
            ObjectNode config = json.createObjectNode();
            config.put("id", run.id).put("directory", run.directory.toString()).put("parentPid", ProcessHandle.current().pid())
                    .put("core", run.core).put("maxSeconds", 180);
            // Python establishes its own monotonic origin (System.nanoTime's origin is deliberately not assumed).
            config.set("allowedCpus", json.valueToTree(run.observers));
            config.set("observerCpus", json.valueToTree(run.observers));
            config.set("originalAllowedCpus", json.valueToTree(run.allowed));
            synchronized (run.process) {
                run.process.getOutputStream().write((json.writeValueAsString(config)+"\n").getBytes(StandardCharsets.UTF_8));
                run.process.getOutputStream().flush();
            }
            stream = reader.submit(() -> readEvents(run));
            long sequence = 0, sampleBytes = 0, stopAt = 0;
            try (BufferedWriter samples = Files.newBufferedWriter(run.directory.resolve("samples.jsonl"))) {
                while (run.process.isAlive()) {
                    if (run.stop || closing || System.nanoTime()-run.startedNanos > TimeUnit.SECONDS.toNanos(177)) {
                        if (stopAt == 0) { stopAt = System.nanoTime(); requestStop(run); }
                        if (System.nanoTime()-stopAt > TimeUnit.SECONDS.toNanos(3)) break;
                    }
                    Snapshot snapshot = collector.latest();
                    if (snapshot != null && snapshot.sequence() > sequence) {
                        sequence = snapshot.sequence();
                        ObjectNode row = json.createObjectNode();
                        row.put("experimentId", run.id).put("sessionId", snapshot.sessionId()).put("sequence", sequence)
                                .put("timestamp", snapshot.timestamp().toString()).put("kind", "MEASURED")
                                .put("phaseAtCollection", run.summary.path("phase").asText()).put("sampleAgeMillis", collector.age());
                        row.set("machineCpu", json.valueToTree(snapshot.cpus().get("cpu")));
                        row.set("selectedCoreCpu", json.valueToTree(snapshot.cpus().get("cpu"+run.core)));
                        row.set("cpuPressureSome", json.valueToTree(snapshot.cpuPressureSome()));
                        row.set("processes", json.valueToTree(snapshot.processes().stream().filter(p -> run.children.containsKey(p.identity().pid())
                                && run.children.get(p.identity().pid()).identity().equals(p.identity())).toList()));
                        String encoded = json.writeValueAsString(row)+"\n";
                        sampleBytes += encoded.getBytes(StandardCharsets.UTF_8).length;
                        if (sampleBytes > 4L*1024*1024) throw new IOException("Sample evidence bound reached");
                        samples.write(encoded); samples.flush();
                    }
                    Thread.sleep(100);
                }
            }
            if (!run.process.waitFor(1, TimeUnit.SECONDS)) { run.failure = "Coordinator cleanup deadline exceeded"; }
            if (stream != null && !run.process.isAlive()) stream.get(2, TimeUnit.SECONDS);
            if (!TERMINAL.contains(run.summary.path("state").asText())) run.failure = "Coordinator exited without terminal evidence";
            if (!run.process.isAlive() && run.process.exitValue() != 0) run.failure = "Coordinator exit code " + run.process.exitValue();
        } catch (Exception e) {
            run.failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            run.stop = true;
        } finally {
            requestStop(run);
            if (run.process != null) {
                try { run.process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            ArrayNode cleanup = json.createArrayNode();
            for (Owned owned : run.children.values()) {
                boolean match = false;
                try { match = owned.identity().equals(identity(owned.identity().pid())); } catch (Exception ignored) {}
                if (match && owned.handle().isAlive()) owned.handle().destroy();
                if (match && owned.handle().isAlive()) {
                    try { owned.handle().onExit().get(500, TimeUnit.MILLISECONDS); } catch (Exception ignored) {}
                    if (owned.handle().isAlive()) owned.handle().destroyForcibly();
                }
                ObjectNode result = cleanup.addObject();
                result.set("identity", json.valueToTree(owned.identity()));
                result.put("role", owned.role()).put("alive", owned.handle().isAlive()).put("identityMatchedBeforeFallback", match);
                if (owned.handle().isAlive()) run.failure = "Registered child remains alive after cleanup";
            }
            if (run.process != null && run.process.isAlive()) {
                run.process.destroy();
                try { if (!run.process.waitFor(500, TimeUnit.MILLISECONDS)) run.process.destroyForcibly().waitFor(500, TimeUnit.MILLISECONDS); }
                catch (Exception ignored) {}
            }
            if (stream != null) { try { stream.get(1, TimeUnit.SECONDS); } catch (Exception ignored) {} }
            try {
                if (originalAffinity != null) collector.observerAffinity(originalAffinity).get(3, TimeUnit.SECONDS);
            } catch (Exception e) { run.failure = "Collector affinity restoration failed"; }
            ObjectNode finished = run.summary.deepCopy();
            if (run.failure != null) finished.put("state", "FAILED").put("reason", run.failure);
            finished.set("javaCleanup", cleanup);
            finished.put("coordinatorAlive", run.process != null && run.process.isAlive());
            finished.put("collectorAffinityRestored", originalAffinity != null && !"Collector affinity restoration failed".equals(run.failure));
            finished.put("timestamp", System.currentTimeMillis()*1_000_000L);
            run.summary = finished;
            try { json.writerWithDefaultPrettyPrinter().writeValue(run.directory.resolve("supervisor.json").toFile(), finished); }
            catch (IOException e) { run.failure = "Could not retain supervisor audit"; }
            run.finished = true;
        }
    }

    public synchronized Path exportDirectory(String id) {
        Run run = runs.get(id);
        if (run == null) throw new NoSuchElementException("Unknown experiment for this backend launch");
        if (!run.finished) throw new IllegalStateException("Stop or finish the experiment before exporting an immutable capture");
        if (actionAuditStore != null) {
            var audits = actionAuditStore.findByExperimentId(id);
            if (!audits.isEmpty()) {
                try {
                    json.writerWithDefaultPrettyPrinter().writeValue(run.directory.resolve("action-audits.json").toFile(), audits);
                } catch (IOException ignored) {}
            }
        }
        return run.directory;
    }

    public void writeExport(Path directory, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (String name : List.of("manifest.json", "events.jsonl", "samples.jsonl", "summary.json", "supervisor.json", "collector-affinity.json", "action-audits.json")) {
                Path file = directory.resolve(name);
                if (!Files.isRegularFile(file)) continue;
                if (Files.size(file) > 12L*1024*1024) throw new IOException("Export evidence exceeds bound");
                zip.putNextEntry(new ZipEntry(name)); Files.copy(file, zip); zip.closeEntry();
            }
        }
    }

    @PreDestroy public void shutdown() {
        closing = true; Run run = current;
        if (run != null && !run.finished) { run.stop = true; requestStop(run); }
        supervisor.shutdown();
        try { if (!supervisor.awaitTermination(10, TimeUnit.SECONDS)) supervisor.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); supervisor.shutdownNow(); }
        reader.shutdownNow();
    }
}
