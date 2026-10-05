package dev.schedwise.simulation;

import dev.schedwise.model.Telemetry.Kind;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Objects for the pure-Java discrete-event scheduling simulator.
 * All simulation outputs are explicitly tagged as SIMULATED.
 */
public final class SimulationModels {
    private SimulationModels() {}

    public record SimulationRequest(
            String captureId,
            String phase,
            List<String> models,
            Long quantumNs,
            Long cfsLatencyTargetNs,
            Long cfsMinGranularityNs,
            Integer backgroundNice,
            Long horizonNs,
            Integer maxEvents
    ) {}

    public record TimelineSegment(
            String jobId,
            String role,
            long startNs,
            long endNs,
            long durationNs,
            String reason
    ) {}

    public record QueueSnapshot(
            long timestampNs,
            String eventType,
            String runningJobId,
            List<String> readyJobIds
    ) {}

    public record JobMetric(
            String id,
            String role,
            long arrivalNs,
            long totalDemandNs,
            long demandServedNs,
            Long firstDispatchNs,
            Long completionNs,
            Long responseTimeNs,
            Long waitingTimeNs,
            Long turnaroundTimeNs,
            boolean completed
    ) {}

    public record ModelMetrics(
            int totalJobs,
            int completedJobs,
            int censoredJobs,
            Double meanResponseTimeMs,
            Double p50ResponseTimeMs,
            Double p95ResponseTimeMs,
            Double meanWaitingTimeMs,
            Double p50WaitingTimeMs,
            Double p95WaitingTimeMs,
            Double meanTurnaroundTimeMs,
            Double p50TurnaroundTimeMs,
            Double p95TurnaroundTimeMs,
            Double cpuUtilizationPercent,
            Map<String, Double> allocationShares,
            Double jainFairnessIndex
    ) {}

    public record AlgorithmResult(
            String algorithm,
            String dataStructure,
            String complexity,
            String assumptions,
            ModelMetrics metrics,
            List<TimelineSegment> timeline,
            List<QueueSnapshot> queueEvents,
            List<JobMetric> jobs,
            boolean partialResult,
            String partialReason
    ) {}

    public record SimulationResponse(
            String captureId,
            String phase,
            String inputKind,
            String sufficiency,
            String qualityNotes,
            long horizonNs,
            Map<String, AlgorithmResult> results,
            Kind kind
    ) {}

    public record CaptureInfo(
            String id,
            String state,
            String testedAt,
            Integer core,
            List<Integer> observerCpus,
            int rawRequestCount,
            int sampleCount,
            boolean hasBaseline,
            boolean hasContention,
            String sufficiency,
            String notes
    ) {}
}
