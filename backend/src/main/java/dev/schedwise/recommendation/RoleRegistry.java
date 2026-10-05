package dev.schedwise.recommendation;

import dev.schedwise.experiment.ExperimentManager;
import dev.schedwise.model.Telemetry.Identity;
import dev.schedwise.model.WorkloadRole;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of workload roles for active experiment children and arbitrary observed processes.
 * Enforces the nonnegotiable v1 policy: tagging an external process does not enroll it for control.
 */
@Component
public class RoleRegistry {
    private final ExperimentManager experimentManager;
    private final Map<Long, UserTag> userTags = new ConcurrentHashMap<>();

    public record UserTag(long pid, Identity identity, WorkloadRole role, String comment, long taggedAtNanos) {}
    public record RoleAssignment(long pid, Identity identity, WorkloadRole role, boolean isManaged, boolean eligibleForControl, String eligibilityReason) {}

    public RoleRegistry(ExperimentManager experimentManager) {
        this.experimentManager = experimentManager;
    }

    public synchronized RoleAssignment tagProcess(long pid, Identity identity, WorkloadRole role, String comment) {
        if (pid <= 1) throw new IllegalArgumentException("Cannot tag PID <= 1");
        userTags.put(pid, new UserTag(pid, identity, role, comment, System.nanoTime()));
        return resolveRole(pid, identity);
    }

    public RoleAssignment resolveRole(long pid, Identity identity) {
        // 1. Check managed children in active experiment
        var managed = experimentManager.findManagedChild(pid);
        if (managed.isPresent()) {
            var owned = managed.get();
            WorkloadRole role = WorkloadRole.fromString(owned.role());
            boolean eligible = role == WorkloadRole.BACKGROUND;
            String reason = eligible
                    ? "Managed background worker; eligible for unprivileged nice increase"
                    : (role == WorkloadRole.PROTECTED_SERVICE
                    ? "Protected service; priority mutation disabled to prevent latency regression"
                    : "Managed non-worker child; not eligible for priority mutation");
            return new RoleAssignment(pid, owned.identity(), role, true, eligible, reason);
        }

        // 2. Check user tags
        UserTag tag = userTags.get(pid);
        if (tag != null) {
            return new RoleAssignment(pid, tag.identity(), tag.role(), false, false,
                    "External process tagged by user. External processes are strictly read-only in v1.");
        }

        // 3. Default observed external process
        return new RoleAssignment(pid, identity, WorkloadRole.OBSERVE_ONLY, false, false,
                "General observed process; strictly read-only in v1.");
    }

    public Map<Long, UserTag> getUserTags() {
        return Collections.unmodifiableMap(userTags);
    }
}
