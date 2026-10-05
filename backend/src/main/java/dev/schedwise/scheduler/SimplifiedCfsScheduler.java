package dev.schedwise.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Simplified classic Completely Fair Scheduler (CFS) model.
 * Implemented using java.util.TreeSet (Red-Black self-balancing binary search tree).
 * 
 * IMPORTANT: This models the historical Linux CFS algorithm (introduced in 2.6.23).
 * Modern Linux (6.6+) fair scheduling has evolved toward EEVDF (Earliest Eligible Virtual Deadline First).
 * This model is historical and simplified; it does not claim kernel dispatch equivalence.
 * 
 * Invariants & Mutability:
 * - Ready tasks are ordered by (vruntimeNs, uniqueId).
 * - A job is extracted from the TreeSet before executing.
 * - Virtual runtime is updated when execution completes or preemption occurs:
 *     delta_vruntime = delta_runtime * (1024 / weight)
 * - The job is reinserted into the TreeSet only after its key has been updated.
 * - min_vruntime tracks the minimum virtual runtime of all active/ready tasks.
 */
public final class SimplifiedCfsScheduler implements Scheduler {
    private static final Comparator<Job> COMPARATOR = Comparator
            .comparingLong(Job::getVruntimeNs)
            .thenComparing(Job::getId);

    private final long latencyTargetNs;
    private final long minGranularityNs;
    private final TreeSet<Job> runQueue = new TreeSet<>(COMPARATOR);
    private long minVruntimeNs = 0L;

    public SimplifiedCfsScheduler(long latencyTargetNs, long minGranularityNs) {
        if (latencyTargetNs <= 0) throw new IllegalArgumentException("Latency target must be positive: " + latencyTargetNs);
        if (minGranularityNs <= 0) throw new IllegalArgumentException("Min granularity must be positive: " + minGranularityNs);
        this.latencyTargetNs = latencyTargetNs;
        this.minGranularityNs = minGranularityNs;
    }

    public SimplifiedCfsScheduler() {
        this(24_000_000L, 3_000_000L); // Standard Linux-inspired defaults: 24ms latency target, 3ms min granularity
    }

    public long getLatencyTargetNs() { return latencyTargetNs; }
    public long getMinGranularityNs() { return minGranularityNs; }
    public long getMinVruntimeNs() { return minVruntimeNs; }

    @Override
    public String name() {
        return "Simplified Classic CFS";
    }

    @Override
    public String dataStructure() {
        return "TreeSet (Red-Black Self-Balancing Tree)";
    }

    @Override
    public String complexity() {
        return "Enqueue: O(log n), Extract min: O(log n), Reinsert: O(log n). Total time: O(n log n + e log n) for e slices. Space: O(n).";
    }

    @Override
    public String assumptions() {
        return "Historical simplified classic CFS model (not modern EEVDF). Ordered by virtual runtime in a Red-Black tree. Dynamic slices proportional to Linux weight with minimum granularity. Zero context-switch cost.";
    }

    @Override
    public void enqueue(Job job, long currentNs) {
        // CFS invariant: newly arriving / waking jobs cannot have a vruntime far behind min_vruntime
        if (job.getVruntimeNs() < minVruntimeNs) {
            job.setVruntimeNs(minVruntimeNs);
        }
        runQueue.add(job);
        updateMinVruntime(null);
    }

    @Override
    public Job selectNext(long currentNs) {
        if (runQueue.isEmpty()) return null;
        Job next = runQueue.pollFirst();
        updateMinVruntime(next);
        return next;
    }

    @Override
    public boolean hasRunnableJobs() {
        return !runQueue.isEmpty();
    }

    @Override
    public int runnableCount() {
        return runQueue.size();
    }

    @Override
    public boolean isPreemptive() {
        return true;
    }

    @Override
    public long computeSliceNs(Job runningJob, long currentNs) {
        int totalWeight = runningJob.getWeight();
        for (Job j : runQueue) {
            totalWeight += j.getWeight();
        }

        // Target slice proportional to weight within latencyTargetNs
        long targetSlice = (long) Math.floor((double) latencyTargetNs * runningJob.getWeight() / totalWeight);
        long slice = Math.max(minGranularityNs, targetSlice);
        return Math.min(slice, runningJob.getRemainingDemandNs());
    }

    @Override
    public void onSliceComplete(Job runningJob, long sliceDurationNs, long currentNs) {
        if (sliceDurationNs > 0) {
            // Delta vruntime = delta execution * (1024 / weight)
            long deltaVruntime = Math.round((double) sliceDurationNs * LinuxWeights.NICE_0_LOAD / runningJob.getWeight());
            runningJob.setVruntimeNs(runningJob.getVruntimeNs() + deltaVruntime);
        }

        if (!runningJob.isCompleted()) {
            runQueue.add(runningJob);
        }
        updateMinVruntime(runningJob.isCompleted() ? null : runningJob);
    }

    private void updateMinVruntime(Job candidate) {
        long min = Long.MAX_VALUE;
        if (!runQueue.isEmpty()) {
            min = runQueue.first().getVruntimeNs();
        }
        if (candidate != null && !candidate.isCompleted()) {
            min = Math.min(min, candidate.getVruntimeNs());
        }
        if (min != Long.MAX_VALUE && min > minVruntimeNs) {
            minVruntimeNs = min;
        }
    }

    @Override
    public List<Job> getReadyJobsSnapshot() {
        return new ArrayList<>(runQueue);
    }
}
