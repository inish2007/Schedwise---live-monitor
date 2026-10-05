package dev.schedwise.action;

import dev.schedwise.experiment.ExperimentManager;
import dev.schedwise.linux.LinuxSource;
import dev.schedwise.linux.ProcParser;
import dev.schedwise.model.Telemetry.Identity;
import dev.schedwise.monitor.Capabilities;
import dev.schedwise.persistence.ActionAuditStore;
import dev.schedwise.persistence.ActionAuditStore.ActionAudit;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Bounded Linux action adapter for unprivileged nice increases.
 * Targets ONLY managed, same-user, single-threaded demo workers with verified identities.
 * Enforces race-resistant pre-checks and readback verification.
 */
@Component
public class LinuxActionAdapter {
    private final Capabilities capabilities;
    private final ExperimentManager experimentManager;
    private final ActionAuditStore auditStore;
    private final LinuxSource linux;
    private final long currentUid;

    public record TargetActionRequest(
            long pid,
            Identity identity,
            int expectedCurrentNice,
            int requestedNice
    ) {}

    public record TargetActionResult(
            long pid,
            String role,
            Identity identity,
            int originalNice,
            int requestedNice,
            Integer observedNice,
            String status,
            String reason,
            String timestamp
    ) {}

    public record BatchActionResult(
            String actionId,
            String experimentId,
            String recommendationId,
            String overallStatus,
            List<TargetActionResult> targets,
            String residualRaceDisclosure,
            String timestamp
    ) {}

    public LinuxActionAdapter(Capabilities capabilities, ExperimentManager experimentManager, ActionAuditStore auditStore, LinuxSource linux) {
        this.capabilities = capabilities;
        this.experimentManager = experimentManager;
        this.auditStore = auditStore;
        this.linux = linux;
        long uid = -1;
        try {
            Map<String, String> status = ProcParser.status(linux.read("/proc/self/status"));
            uid = Long.parseLong(status.get("Uid").split("\\s+")[0]);
        } catch (Exception ignored) {}
        this.currentUid = uid;
    }

    public synchronized BatchActionResult applyNice(
            String actionId,
            String experimentId,
            String recommendationId,
            List<TargetActionRequest> targets
    ) {
        if (actionId == null || actionId.isBlank()) throw new IllegalArgumentException("Action ID is required for idempotency");
        if (targets == null || targets.isEmpty()) throw new IllegalArgumentException("Targets list cannot be empty");
        if (targets.size() > 8) throw new IllegalArgumentException("Target count exceeds allowed bound (8)");

        // 1. Check idempotency: if already processed, return stored audit results
        Optional<ActionAudit> existing = auditStore.findById(actionId);
        if (existing.isPresent()) {
            ActionAudit first = existing.get();
            List<TargetActionResult> recalled = List.of(new TargetActionResult(
                    first.targetPid(),
                    first.targetRole(),
                    first.targetIdentity(),
                    first.originalNice(),
                    first.requestedNice(),
                    first.observedNice(),
                    first.status(),
                    first.reason() != null ? first.reason() : "Recalled from idempotent audit store",
                    first.createdAt()
            ));
            String recalledBatchStatus = "SUCCESS".equals(first.status()) ? "SUCCESS" : "FAILED";
            return new BatchActionResult(
                    actionId,
                    first.experimentId(),
                    first.recommendationId(),
                    recalledBatchStatus,
                    recalled,
                    residualRaceNote(),
                    first.createdAt()
            );
        }

        List<TargetActionResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        String now = Instant.now().toString();

        for (TargetActionRequest target : targets) {
            TargetActionResult res = executeSingleTarget(actionId, experimentId, recommendationId, target, now);
            results.add(res);
            if ("SUCCESS".equals(res.status())) successCount++;
            else failureCount++;
        }

        String overallStatus = failureCount == 0 ? "SUCCESS"
                : (successCount > 0 ? "PARTIAL_SUCCESS" : "FAILED");

        return new BatchActionResult(
                actionId,
                experimentId,
                recommendationId,
                overallStatus,
                results,
                residualRaceNote(),
                now
        );
    }

    private TargetActionResult executeSingleTarget(
            String actionId,
            String experimentId,
            String recommendationId,
            TargetActionRequest target,
            String timestamp
    ) {
        long pid = target.pid();
        String role = "UNKNOWN";
        int origNice = target.expectedCurrentNice();

        // 1. PID range check
        if (pid <= 1) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "REJECTED",
                    "Target PID must be > 1; PID 0 and 1 are strictly forbidden", timestamp);
        }

        // 2. Managed child check in launch registry
        var managedOpt = experimentManager.findManagedChild(pid);
        if (managedOpt.isEmpty()) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "REJECTED",
                    "PID is not a managed child process in the active experiment registry. External processes are strictly read-only in v1.", timestamp);
        }
        var managed = managedOpt.get();
        role = managed.role();

        // 3. Role check: only BACKGROUND workers permitted
        if (role.contains("SERVICE") || "PROTECTED_SERVICE".equals(role)) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "REJECTED",
                    "Target is the Protected Service workload; priority mutation is strictly forbidden to preserve responsiveness", timestamp);
        }
        if (!role.startsWith("BACKGROUND")) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "REJECTED",
                    "Target role '" + role + "' is not an eligible background worker", timestamp);
        }

        // 4. Alive and handle check
        if (!managed.handle().isAlive()) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "REJECTED",
                    "Target process is no longer alive", timestamp);
        }

        // 5. UID check & single-threaded check via /proc/<pid>/status
        Map<String, String> status;
        try {
            status = ProcParser.status(linux.read("/proc/" + pid + "/status"));
        } catch (Exception e) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "FAILED",
                    "Cannot read /proc/" + pid + "/status: " + e.getMessage(), timestamp);
        }

        long targetUid = Long.parseLong(status.get("Uid").split("\\s+")[0]);
        if (targetUid != currentUid || targetUid == 0) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "REJECTED",
                    "Target UID (" + targetUid + ") does not match backend UID (" + currentUid + ")", timestamp);
        }

        int threads = Integer.parseInt(status.getOrDefault("Threads", "0"));
        if (threads != 1) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "REJECTED",
                    "Target has " + threads + " threads; only single-threaded workers supported in v1", timestamp);
        }

        // 6. Identity & current nice pre-check via /proc/<pid>/stat
        ProcParser.Stat stat;
        try {
            stat = ProcParser.stat(linux.read("/proc/" + pid + "/stat"));
        } catch (Exception e) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, target.identity(),
                    origNice, target.requestedNice(), null, "FAILED",
                    "Cannot read /proc/" + pid + "/stat: " + e.getMessage(), timestamp);
        }

        Identity currentId = new Identity(capabilities.bootId, pid, stat.startTicks());
        if (!currentId.equals(target.identity())) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                    origNice, target.requestedNice(), null, "REJECTED",
                    "Stale identity: startTicks mismatch (expected " + target.identity().startTicks() + ", observed " + stat.startTicks() + ")", timestamp);
        }

        if (stat.nice() != target.expectedCurrentNice()) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                    stat.nice(), target.requestedNice(), stat.nice(), "REJECTED",
                    "Current nice mismatch (expected " + target.expectedCurrentNice() + ", observed " + stat.nice() + ")", timestamp);
        }

        // 7. Requested nice validation: must be an unprivileged increase [1..19]
        if (target.requestedNice() < 0 || target.requestedNice() > 19) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                    stat.nice(), target.requestedNice(), stat.nice(), "REJECTED",
                    "Requested nice (" + target.requestedNice() + ") out of valid Linux range [0, 19]", timestamp);
        }
        if (target.requestedNice() <= stat.nice()) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                    stat.nice(), target.requestedNice(), stat.nice(), "REJECTED",
                    "Unprivileged users can only increase nice (lower priority); requested " + target.requestedNice() + " <= current " + stat.nice(), timestamp);
        }

        // 8. Execute /usr/bin/renice with fixed arguments
        Path reniceBinary;
        try { reniceBinary = dev.schedwise.linux.BinaryFinder.find("renice"); } catch (IllegalStateException e) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                    stat.nice(), target.requestedNice(), stat.nice(), "FAILED",
                    e.getMessage(), timestamp);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    reniceBinary.toString(),
                    "-n", String.valueOf(target.requestedNice()),
                    "-p", String.valueOf(pid)
            );
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            boolean finished = proc.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                        stat.nice(), target.requestedNice(), null, "FAILED",
                        "renice command timed out after 2 seconds", timestamp);
            }
            if (proc.exitValue() != 0) {
                return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                        stat.nice(), target.requestedNice(), null, "FAILED",
                        "renice exited with code " + proc.exitValue(), timestamp);
            }
        } catch (Exception e) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                    stat.nice(), target.requestedNice(), null, "FAILED",
                    "renice execution error: " + e.getMessage(), timestamp);
        }

        // 9. Readback verification
        ProcParser.Stat readback;
        try {
            readback = ProcParser.stat(linux.read("/proc/" + pid + "/stat"));
        } catch (Exception e) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, currentId,
                    stat.nice(), target.requestedNice(), null, "FAILED",
                    "Post-action readback failed to read /proc/" + pid + "/stat: " + e.getMessage(), timestamp);
        }

        Identity postIdentity = new Identity(capabilities.bootId, pid, readback.startTicks());
        if (!postIdentity.equals(currentId)) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, postIdentity,
                    stat.nice(), target.requestedNice(), readback.nice(), "FAILED",
                    "Post-action identity verification failed (PID was reused during mutation)", timestamp);
        }

        if (readback.nice() != target.requestedNice()) {
            return recordAndReturn(actionId, experimentId, recommendationId, pid, role, postIdentity,
                    stat.nice(), target.requestedNice(), readback.nice(), "FAILED",
                    "Post-action readback verification failed: observed nice " + readback.nice() + " != requested " + target.requestedNice(), timestamp);
        }

        return recordAndReturn(actionId, experimentId, recommendationId, pid, role, postIdentity,
                stat.nice(), target.requestedNice(), readback.nice(), "SUCCESS", null, timestamp);
    }

    private TargetActionResult recordAndReturn(
            String actionId,
            String experimentId,
            String recommendationId,
            long pid,
            String role,
            Identity identity,
            int originalNice,
            int requestedNice,
            Integer observedNice,
            String status,
            String reason,
            String timestamp
    ) {
        ActionAudit audit = new ActionAudit(
                actionId,
                capabilities.sessionId,
                experimentId,
                recommendationId,
                "RENICE",
                pid,
                role,
                identity,
                originalNice,
                requestedNice,
                observedNice,
                status,
                reason,
                timestamp
        );
        auditStore.record(audit);
        return new TargetActionResult(
                pid,
                role,
                identity,
                originalNice,
                requestedNice,
                observedNice,
                status,
                reason,
                timestamp
        );
    }

    private static String residualRaceNote() {
        return "Pre-execution proc identity check and post-execution readback verify target identity and setting. " +
               "However, an inherent kernel process-exit/PID-reuse race exists if a child exits and the kernel wraps the PID " +
               "before the renice syscall. Mitigated by verifying the child is an owned direct descendant of the experiment supervisor.";
    }
}
