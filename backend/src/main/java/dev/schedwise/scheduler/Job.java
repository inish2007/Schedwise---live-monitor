package dev.schedwise.scheduler;

import java.util.Objects;

/**
 * Immutable and discrete-event execution job representation for CPU scheduling models.
 * All timing is in integer simulation nanoseconds.
 */
public final class Job {
    private final String id;
    private final String role;
    private final long arrivalNs;
    private final long totalDemandNs;
    private final int priority;
    private final int nice;
    private final int weight;

    private long remainingDemandNs;
    private long demandServedNs;
    private long vruntimeNs;
    private Long firstDispatchNs;
    private Long completionNs;

    public Job(String id, String role, long arrivalNs, long totalDemandNs, int priority, int nice, int weight) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Job ID must not be blank");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("Role must not be blank");
        if (arrivalNs < 0) throw new IllegalArgumentException("Arrival time cannot be negative: " + arrivalNs);
        if (totalDemandNs <= 0) throw new IllegalArgumentException("Total demand must be positive: " + totalDemandNs);
        if (weight <= 0) throw new IllegalArgumentException("Weight must be positive: " + weight);

        this.id = id;
        this.role = role;
        this.arrivalNs = arrivalNs;
        this.totalDemandNs = totalDemandNs;
        this.remainingDemandNs = totalDemandNs;
        this.priority = priority;
        this.nice = nice;
        this.weight = weight;
        this.vruntimeNs = 0;
    }

    public Job copy() {
        Job clone = new Job(id, role, arrivalNs, totalDemandNs, priority, nice, weight);
        clone.remainingDemandNs = this.remainingDemandNs;
        clone.demandServedNs = this.demandServedNs;
        clone.vruntimeNs = this.vruntimeNs;
        clone.firstDispatchNs = this.firstDispatchNs;
        clone.completionNs = this.completionNs;
        return clone;
    }

    public String getId() { return id; }
    public String getRole() { return role; }
    public long getArrivalNs() { return arrivalNs; }
    public long getTotalDemandNs() { return totalDemandNs; }
    public long getRemainingDemandNs() { return remainingDemandNs; }
    public long getDemandServedNs() { return demandServedNs; }
    public int getPriority() { return priority; }
    public int getNice() { return nice; }
    public int getWeight() { return weight; }
    public long getVruntimeNs() { return vruntimeNs; }
    public void setVruntimeNs(long vruntimeNs) { this.vruntimeNs = vruntimeNs; }
    public Long getFirstDispatchNs() { return firstDispatchNs; }
    public Long getCompletionNs() { return completionNs; }

    public boolean isCompleted() {
        return remainingDemandNs == 0;
    }

    public void recordFirstDispatch(long currentNs) {
        if (firstDispatchNs == null) {
            firstDispatchNs = currentNs;
        }
    }

    public void serve(long durationNs, long currentNs) {
        if (durationNs <= 0) return;
        long actual = Math.min(durationNs, remainingDemandNs);
        remainingDemandNs -= actual;
        demandServedNs += actual;
        if (remainingDemandNs == 0 && completionNs == null) {
            completionNs = currentNs + actual;
        }
    }

    public Long getResponseTimeNs() {
        return firstDispatchNs == null ? null : (firstDispatchNs - arrivalNs);
    }

    public Long getTurnaroundTimeNs() {
        return completionNs == null ? null : (completionNs - arrivalNs);
    }

    public Long getWaitingTimeNs(long currentNs) {
        long end = completionNs != null ? completionNs : currentNs;
        if (end < arrivalNs) return 0L;
        return Math.max(0L, (end - arrivalNs) - demandServedNs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Job job = (Job) o;
        return Objects.equals(id, job.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Job{" + id + ", role=" + role + ", arr=" + arrivalNs + ", rem=" + remainingDemandNs + "/" + totalDemandNs + "}";
    }
}
