package dev.schedwise.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Non-preemptive Priority scheduler with deterministic tie-breaking.
 * Implemented using java.util.PriorityQueue (binary min-heap).
 * 
 * Lower numeric priority value = higher execution priority.
 * Ties are broken deterministically by arrivalNs, then unique job ID.
 */
public final class PriorityScheduler implements Scheduler {
    private static final Comparator<Job> COMPARATOR = Comparator
            .comparingInt(Job::getPriority)
            .thenComparingLong(Job::getArrivalNs)
            .thenComparing(Job::getId);

    private final PriorityQueue<Job> queue = new PriorityQueue<>(COMPARATOR);

    @Override
    public String name() {
        return "Priority (Non-preemptive)";
    }

    @Override
    public String dataStructure() {
        return "PriorityQueue (Binary Min-Heap)";
    }

    @Override
    public String complexity() {
        return "Enqueue: O(log n), Select: O(log n). Total time: O(n log n). Space: O(n).";
    }

    @Override
    public String assumptions() {
        return "Non-preemptive priority reference model. Lower numeric value indicates higher priority. Stable tie handling by arrival and unique ID. Exposes starvation risk for lower-priority workloads.";
    }

    @Override
    public void enqueue(Job job, long currentNs) {
        queue.offer(job);
    }

    @Override
    public Job selectNext(long currentNs) {
        return queue.poll();
    }

    @Override
    public boolean hasRunnableJobs() {
        return !queue.isEmpty();
    }

    @Override
    public int runnableCount() {
        return queue.size();
    }

    @Override
    public boolean isPreemptive() {
        return false;
    }

    @Override
    public long computeSliceNs(Job runningJob, long currentNs) {
        return runningJob.getRemainingDemandNs();
    }

    @Override
    public void onSliceComplete(Job runningJob, long sliceDurationNs, long currentNs) {
        // Non-preemptive: runs until completion unless stopped at horizon
    }

    @Override
    public List<Job> getReadyJobsSnapshot() {
        List<Job> copy = new ArrayList<>(queue);
        copy.sort(COMPARATOR);
        return copy;
    }
}
