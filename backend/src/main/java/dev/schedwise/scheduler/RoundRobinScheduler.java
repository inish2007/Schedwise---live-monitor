package dev.schedwise.scheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Textbook Round Robin (RR) preemptive scheduler with configurable time quantum.
 * Implemented using java.util.ArrayDeque.
 * 
 * IMPORTANT: This is textbook discrete-quantum Round Robin for educational and reference
 * comparison; it is separate and distinct from Linux real-time SCHED_RR.
 * 
 * Quantum Boundary Rule:
 * When a running job completes its allocated quantum slice at time t, any newly arriving jobs
 * with arrival timestamp <= t are appended to the ready queue before the preempted job is requeued.
 */
public final class RoundRobinScheduler implements Scheduler {
    private final long quantumNs;
    private final Deque<Job> queue = new ArrayDeque<>();

    public RoundRobinScheduler(long quantumNs) {
        if (quantumNs <= 0) throw new IllegalArgumentException("Quantum must be positive: " + quantumNs);
        this.quantumNs = quantumNs;
    }

    public long getQuantumNs() {
        return quantumNs;
    }

    @Override
    public String name() {
        return "Round Robin (Textbook RR, q=" + (quantumNs / 1_000_000.0) + "ms)";
    }

    @Override
    public String dataStructure() {
        return "ArrayDeque (FIFO with tail requeue)";
    }

    @Override
    public String complexity() {
        return "Enqueue: O(1), Select: O(1). Total time: O(n log n) arrival sort + O(e) slice operations where e is the number of slices. Space: O(e) bounded by horizon.";
    }

    @Override
    public String assumptions() {
        return "Textbook Round Robin. Distinct from Linux SCHED_RR. Fixed quantum. Preempted tasks are requeued at tail after newly arrived tasks at the slice boundary. Zero context switch overhead.";
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
        return true;
    }

    @Override
    public long computeSliceNs(Job runningJob, long currentNs) {
        return Math.min(quantumNs, runningJob.getRemainingDemandNs());
    }

    @Override
    public void onSliceComplete(Job runningJob, long sliceDurationNs, long currentNs) {
        if (!runningJob.isCompleted()) {
            queue.addLast(runningJob);
        }
    }

    @Override
    public List<Job> getReadyJobsSnapshot() {
        return new ArrayList<>(queue);
    }
}
