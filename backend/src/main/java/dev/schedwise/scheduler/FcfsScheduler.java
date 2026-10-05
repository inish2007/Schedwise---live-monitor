package dev.schedwise.scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * First-Come, First-Served (FCFS) non-preemptive reference scheduler.
 * Implemented using java.util.ArrayDeque for efficient O(1) FIFO queueing.
 */
public final class FcfsScheduler implements Scheduler {
    private final Deque<Job> queue = new ArrayDeque<>();

    @Override
    public String name() {
        return "FCFS";
    }

    @Override
    public String dataStructure() {
        return "ArrayDeque (FIFO)";
    }

    @Override
    public String complexity() {
        return "Enqueue: O(1), Select: O(1). Total time: O(n log n) arrival sort + O(n) queue operations. Space: O(n).";
    }

    @Override
    public String assumptions() {
        return "Non-preemptive reference model. Jobs run to completion upon dispatch. Zero context-switch cost. Exposes starvation if a long batch job arrives first.";
    }

    @Override
    public void enqueue(Job job, long currentNs) {
        queue.addLast(job);
    }

    @Override
    public Job selectNext(long currentNs) {
        return queue.pollFirst();
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
        // Non-preemptive: if incomplete because horizon was hit, no requeueing occurs
    }

    @Override
    public List<Job> getReadyJobsSnapshot() {
        return new ArrayList<>(queue);
    }
}
