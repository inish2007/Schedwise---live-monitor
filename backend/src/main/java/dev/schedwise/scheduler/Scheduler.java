package dev.schedwise.scheduler;

import java.util.List;

/**
 * Common scheduling policy interface for discrete-event CPU simulation.
 * Scheduler implementations operate exclusively on immutable/stateful Job models in pure Java;
 * they cannot execute OS commands or mutate Linux system settings.
 */
public interface Scheduler {
    /** Algorithm display name. */
    String name();

    /** Underlying Java collection / data structure name. */
    String dataStructure();

    /** Big-O algorithmic time and space complexity description. */
    String complexity();

    /** Documented model assumptions, simplifications, and boundaries. */
    String assumptions();

    /** Add an arrived or unblocked job to the ready queue. */
    void enqueue(Job job, long currentNs);

    /** Select and extract the next job to execute on the CPU. */
    Job selectNext(long currentNs);

    /** True if there is at least one job ready to execute. */
    boolean hasRunnableJobs();

    /** Number of jobs currently waiting in the ready queue. */
    int runnableCount();

    /** Whether this policy supports preemption before job completion. */
    boolean isPreemptive();

    /** Compute maximum uninterrupted duration for the given job. */
    long computeSliceNs(Job runningJob, long currentNs);

    /**
     * Callback when a slice of execution finishes on the CPU.
     * If the job is unfinished and preemptive, it should be re-enqueued appropriately.
     */
    void onSliceComplete(Job runningJob, long sliceDurationNs, long currentNs);

    /** Returns an immutable snapshot list of currently ready jobs for queue logging. */
    List<Job> getReadyJobsSnapshot();
}
