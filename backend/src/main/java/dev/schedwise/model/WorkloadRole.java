package dev.schedwise.model;

/**
 * Explicit workload roles in SchedWise.
 * Managed demo workloads are assigned roles at launch; arbitrary observed
 * processes default to OBSERVE_ONLY unless tagged by the user.
 * Note: Tagging an external process does NOT enroll it for priority control in v1.
 */
public enum WorkloadRole {
    PROTECTED_SERVICE("Protected Service", "Latency-sensitive service workload; protected from preemption or deprioritization"),
    BACKGROUND("Background", "Batch, compute-heavy, or compilation background work; candidate for deprioritization"),
    OBSERVE_ONLY("Observe Only", "General monitored external process; strictly read-only in v1"),
    LOAD_CLIENT("Load Client", "Load generator generating fixed-rate benchmark requests");

    private final String label;
    private final String description;

    WorkloadRole(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static WorkloadRole fromString(String val) {
        if (val == null) return OBSERVE_ONLY;
        String normalized = val.trim().toUpperCase();
        if (normalized.contains("SERVICE") || normalized.equals("PROTECTED_SERVICE")) return PROTECTED_SERVICE;
        if (normalized.contains("BACKGROUND") || normalized.contains("WORKER")) return BACKGROUND;
        if (normalized.contains("CLIENT") || normalized.equals("LOAD_CLIENT")) return LOAD_CLIENT;
        return OBSERVE_ONLY;
    }
}
