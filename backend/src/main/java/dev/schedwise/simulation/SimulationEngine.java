package dev.schedwise.simulation;

import dev.schedwise.scheduler.Job;
import dev.schedwise.scheduler.Scheduler;
import dev.schedwise.simulation.SimulationModels.*;

import java.util.*;

/**
 * Deterministic, pure-Java discrete-event simulation engine for one logical CPU.
 * 
 * Rules & Invariants:
 * - Time advances via discrete events and idle jumps; no per-nanosecond loops.
 * - Single-CPU non-overlap invariant: only one job runs at any instant.
 * - Work conservation: CPU is never left idle while runnable jobs exist.
 * - Quantum boundary rule: new arrivals at or before the slice boundary are enqueued before preempted tasks.
 * - Unfinished jobs remain censored at the horizon; no fake turnaround is extrapolated.
 */
public final class SimulationEngine {
    private final Scheduler scheduler;
    private final List<Job> jobs;
    private final long horizonNs;
    private final int maxEvents;

    public SimulationEngine(Scheduler scheduler, List<Job> inputJobs, long horizonNs, int maxEvents) {
        if (scheduler == null) throw new IllegalArgumentException("Scheduler must not be null");
        if (inputJobs == null || inputJobs.isEmpty()) throw new IllegalArgumentException("Jobs must not be empty");
        if (horizonNs <= 0) throw new IllegalArgumentException("Horizon must be positive: " + horizonNs);
        if (maxEvents <= 0) throw new IllegalArgumentException("Max events must be positive: " + maxEvents);

        this.scheduler = scheduler;
        this.horizonNs = horizonNs;
        this.maxEvents = maxEvents;

        // Defensive deep copy so simulations are fully deterministic and side-effect free
        this.jobs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Job j : inputJobs) {
            if (j == null || !ids.add(j.getId())) throw new IllegalArgumentException("Jobs require unique non-null identities");
            if (j.getDemandServedNs() != 0 || j.getFirstDispatchNs() != null) throw new IllegalArgumentException("Simulation requires unserved input jobs");
            this.jobs.add(j.copy());
        }
        this.jobs.sort(Comparator.comparingLong(Job::getArrivalNs).thenComparing(Job::getId));
    }

    public AlgorithmResult run() {
        List<TimelineSegment> timeline = new ArrayList<>();
        List<QueueSnapshot> queueEvents = new ArrayList<>();
        int arrivalIndex = 0;
        long currentNs = 0L;
        int eventCount = 0;
        boolean partial = false;
        String partialReason = null;

        while (currentNs < horizonNs) {
            if (++eventCount > maxEvents) {
                partial = true;
                partialReason = "MAX_EVENTS_REACHED (" + maxEvents + ")";
                break;
            }

            // 1. Ingest all jobs that have arrived at or before currentNs
            while (arrivalIndex < jobs.size() && jobs.get(arrivalIndex).getArrivalNs() <= currentNs) {
                Job arriving = jobs.get(arrivalIndex++);
                scheduler.enqueue(arriving, currentNs);
                if (queueEvents.size() < 200) {
                    queueEvents.add(new QueueSnapshot(currentNs, "ARRIVAL:" + arriving.getId(), null,
                            scheduler.getReadyJobsSnapshot().stream().map(Job::getId).toList()));
                }
            }

            // 2. If no jobs are runnable, jump idle time
            if (!scheduler.hasRunnableJobs()) {
                if (arrivalIndex >= jobs.size()) {
                    // No future arrivals; CPU remains idle until horizon
                    currentNs = horizonNs;
                    break;
                }
                long nextArrival = jobs.get(arrivalIndex).getArrivalNs();
                long jumpTo = Math.min(horizonNs, nextArrival);
                if (queueEvents.size() < 200 && jumpTo > currentNs) {
                    queueEvents.add(new QueueSnapshot(currentNs, "IDLE_JUMP", null, List.of()));
                }
                currentNs = jumpTo;
                continue;
            }

            // 3. Dispatch the next job
            Job running = scheduler.selectNext(currentNs);
            running.recordFirstDispatch(currentNs);

            // Compute maximum uninterrupted slice duration
            long slice = scheduler.computeSliceNs(running, currentNs);
            if (slice <= 0) {
                slice = running.getRemainingDemandNs();
            }
            // Bound by horizon
            slice = Math.min(slice, horizonNs - currentNs);

            long nextNs = currentNs + slice;

            // Serve execution time
            running.serve(slice, currentNs);

            String reason = running.isCompleted() ? "COMPLETED" : (nextNs >= horizonNs ? "HORIZON_REACHED" : "PREEMPTED");
            timeline.add(new TimelineSegment(running.getId(), running.getRole(), currentNs, nextNs, slice, reason));

            // Advance time
            currentNs = nextNs;

            // 4. Handle arrivals that occurred during the slice up to currentNs BEFORE requeueing preempted job
            while (arrivalIndex < jobs.size() && jobs.get(arrivalIndex).getArrivalNs() <= currentNs) {
                Job arriving = jobs.get(arrivalIndex++);
                scheduler.enqueue(arriving, arriving.getArrivalNs());
                if (queueEvents.size() < 200) {
                    queueEvents.add(new QueueSnapshot(currentNs, "ARRIVAL:" + arriving.getId(), running.getId(),
                            scheduler.getReadyJobsSnapshot().stream().map(Job::getId).toList()));
                }
            }

            // 5. Notify scheduler that slice completed (re-enqueues if preemptive and unfinished)
            scheduler.onSliceComplete(running, slice, currentNs);

            if (queueEvents.size() < 200) {
                queueEvents.add(new QueueSnapshot(currentNs, reason + ":" + running.getId(), null,
                        scheduler.getReadyJobsSnapshot().stream().map(Job::getId).toList()));
            }
        }

        final long finalNs = currentNs;
        ModelMetrics metrics = computeMetrics(timeline, finalNs);

        List<JobMetric> jobMetrics = jobs.stream().map(j -> new JobMetric(
                j.getId(), j.getRole(), j.getArrivalNs(), j.getTotalDemandNs(), j.getDemandServedNs(),
                j.getFirstDispatchNs(), j.getCompletionNs(),
                j.getResponseTimeNs(), j.getWaitingTimeNs(finalNs), j.getTurnaroundTimeNs(),
                j.isCompleted()
        )).toList();

        return new AlgorithmResult(
                scheduler.name(),
                scheduler.dataStructure(),
                scheduler.complexity(),
                scheduler.assumptions(),
                metrics,
                timeline,
                queueEvents,
                jobMetrics,
                partial,
                partialReason
        );
    }

    private ModelMetrics computeMetrics(List<TimelineSegment> timeline, long actualHorizonNs) {
        int total = jobs.size();
        int completed = 0;
        int censored = 0;

        List<Double> responseTimesMs = new ArrayList<>();
        List<Double> waitingTimesMs = new ArrayList<>();
        List<Double> turnaroundTimesMs = new ArrayList<>();
        Map<String, Long> roleServedNs = new LinkedHashMap<>();
        long totalServedNs = 0L;

        for (Job j : jobs) {
            if (j.isCompleted()) {
                completed++;
                turnaroundTimesMs.add(j.getTurnaroundTimeNs() / 1_000_000.0);
            } else {
                censored++;
            }
            if (j.getFirstDispatchNs() != null) {
                responseTimesMs.add(j.getResponseTimeNs() / 1_000_000.0);
            }
            waitingTimesMs.add(j.getWaitingTimeNs(actualHorizonNs) / 1_000_000.0);
            roleServedNs.merge(j.getRole(), j.getDemandServedNs(), Long::sum);
            totalServedNs += j.getDemandServedNs();
        }

        Collections.sort(responseTimesMs);
        Collections.sort(waitingTimesMs);
        Collections.sort(turnaroundTimesMs);

        Double meanResp = mean(responseTimesMs);
        Double p50Resp = nearestRank(responseTimesMs, 0.50);
        Double p95Resp = nearestRank(responseTimesMs, 0.95);

        Double meanWait = mean(waitingTimesMs);
        Double p50Wait = nearestRank(waitingTimesMs, 0.50);
        Double p95Wait = nearestRank(waitingTimesMs, 0.95);

        Double meanTurn = mean(turnaroundTimesMs);
        Double p50Turn = nearestRank(turnaroundTimesMs, 0.50);
        Double p95Turn = nearestRank(turnaroundTimesMs, 0.95);

        double busyNs = 0L;
        for (TimelineSegment seg : timeline) {
            busyNs += seg.durationNs();
        }
        Double cpuUtil = actualHorizonNs > 0 ? (busyNs * 100.0 / actualHorizonNs) : null;

        Map<String, Double> allocationShares = new LinkedHashMap<>();
        for (var e : roleServedNs.entrySet()) {
            double share = totalServedNs > 0 ? (e.getValue() * 100.0 / totalServedNs) : 0.0;
            allocationShares.put(e.getKey(), Math.round(share * 100.0) / 100.0);
        }

        // Jain's fairness index across roles: J = (sum x_i)^2 / (n * sum x_i^2)
        Double jainFairness = null;
        if (!roleServedNs.isEmpty() && totalServedNs > 0) {
            int n = roleServedNs.size();
            double sum = 0.0;
            double sumSq = 0.0;
            for (long served : roleServedNs.values()) {
                double val = (double) served / totalServedNs;
                sum += val;
                sumSq += (val * val);
            }
            if (sumSq > 0) {
                jainFairness = Math.round(((sum * sum) / (n * sumSq)) * 1000.0) / 1000.0;
            }
        }

        return new ModelMetrics(
                total, completed, censored,
                meanResp, p50Resp, p95Resp,
                meanWait, p50Wait, p95Wait,
                meanTurn, p50Turn, p95Turn,
                cpuUtil, allocationShares, jainFairness
        );
    }

    private static Double mean(List<Double> values) {
        if (values.isEmpty()) return null;
        double sum = 0.0;
        for (double v : values) sum += v;
        return Math.round((sum / values.size()) * 100.0) / 100.0;
    }

    private static Double nearestRank(List<Double> sorted, double quantile) {
        if (sorted.isEmpty()) return null;
        int idx = (int) Math.ceil(quantile * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return Math.round(sorted.get(idx) * 100.0) / 100.0;
    }
}
