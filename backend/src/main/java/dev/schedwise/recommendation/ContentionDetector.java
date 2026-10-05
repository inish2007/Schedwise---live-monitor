package dev.schedwise.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Detects sustained CPU contention around the shared core scope.
 * Distinguishes high CPU utilization alone from harmful contention corroborated
 * by measured latency degradation, deadline misses, or kernel pressure metrics.
 */
@Component
public class ContentionDetector {

    public record ContentionEvidence(
            String status, // SUSTAINED_CONTENTION, HIGH_UTILIZATION_WITHOUT_CONTENTION, INSUFFICIENT_EVIDENCE, NO_CONTENTION
            int core,
            Double baselineP95Ms,
            Double contentionP95Ms,
            Double latencyDegradationRatio,
            Integer deadlineMissCount,
            Double deadlineMissRate,
            Integer errorCount,
            Double coreCpuBusyPercent,
            Double psiPressureSomeAvg,
            boolean corroboratedByPressure,
            String assessment,
            List<String> missingSources,
            String attributionLimitation
    ) {}

    public ContentionEvidence analyze(JsonNode experimentSummary, Double latestPsiAvg, Double coreBusy) {
        List<String> missingSources = new ArrayList<>();
        int core = experimentSummary.path("core").asInt(-1);

        JsonNode summaries = experimentSummary.path("summaries");
        JsonNode baseline = summaries.path("BASELINE");
        JsonNode contention = summaries.path("CONTENTION");

        if (baseline.isMissingNode() || contention.isMissingNode()) {
            return insufficient("Baseline or contention phase measurements not found in summary", core, missingSources);
        }

        int baselineScheduled = baseline.path("scheduledCount").asInt(0);
        int contentionScheduled = contention.path("scheduledCount").asInt(0);
        int baselineSuccess = baseline.path("successCount").asInt(0);
        int contentionSuccess = contention.path("successCount").asInt(0);

        if (baselineScheduled == 0 || contentionScheduled == 0) {
            return insufficient("Experiment has not completed both baseline and contention measurement windows", core, missingSources);
        }

        JsonNode baseP95Node = baseline.path("p95Ms").path("value");
        JsonNode contP95Node = contention.path("p95Ms").path("value");

        Double baseP95 = baseP95Node.isNumber() ? baseP95Node.asDouble() : null;
        Double contP95 = contP95Node.isNumber() ? contP95Node.asDouble() : null;

        int deadlineMisses = contention.path("deadlineMissCount").asInt(0);
        double deadlineMissRate = contention.path("deadlineMissRate").path("value").asDouble(0.0);
        int errorCount = contention.path("errorCount").asInt(0);

        if (baseP95 == null || contP95 == null) {
            if (deadlineMisses > 0 || errorCount > 0) {
                // We have deadline misses or timeouts even if p95 has fewer than 20 observations
                missingSources.add("Latency p95 requires >= 20 observations; evaluated using deadline misses");
            } else {
                return insufficient("Fewer than 20 successful observations in baseline or contention window for p95 evaluation", core, missingSources);
            }
        }

        Double degradationRatio = (baseP95 != null && contP95 != null && baseP95 > 0)
                ? contP95 / baseP95 : null;

        boolean corroborated = latestPsiAvg != null && latestPsiAvg > 10.0;
        if (latestPsiAvg == null) {
            missingSources.add("Kernel CPU PSI pressure metrics unavailable on this platform");
        }

        String limitation = "Global runnable count or machine CPU cannot prove per-core competition. Evidence is strictly scoped to the shared pinned core " + core + ".";

        // Evaluate contention threshold: degradation ratio >= 1.5 OR deadline misses > 0 OR timeout errors > 0
        boolean isDegraded = (degradationRatio != null && degradationRatio >= 1.5)
                || deadlineMisses > 0
                || errorCount > 0;

        if (isDegraded) {
            String explanation = "Sustained contention detected on Core " + core + ": "
                    + (baseP95 != null && contP95 != null
                       ? String.format(Locale.US,"protected service p95 changed from %.2f ms to %.2f ms",baseP95,contP95)
                       : "p95 latency is unavailable; the assessment uses observed deadline misses and errors")
                    + ", with " + deadlineMisses + " deadline misses and " + errorCount + " errors during contention.";
            return new ContentionEvidence(
                    "SUSTAINED_CONTENTION",
                    core,
                    baseP95,
                    contP95,
                    degradationRatio,
                    deadlineMisses,
                    deadlineMissRate,
                    errorCount,
                    coreBusy,
                    latestPsiAvg,
                    corroborated,
                    explanation,
                    missingSources,
                    limitation
            );
        }

        // Check high utilization without contention
        if (coreBusy != null && coreBusy >= 80.0) {
            String explanation = String.format(
                    Locale.US,
                    "High CPU utilization (%.1f%%) observed on Core %d, but protected service latency remains below the 1.5× degradation threshold (%.2f ms vs %.2f ms) with 0 deadline misses. Contention is not currently harmful.",
                    coreBusy,
                    core,
                    baseP95 != null ? baseP95 : 0.0,
                    contP95 != null ? contP95 : 0.0
            );
            return new ContentionEvidence(
                    "HIGH_UTILIZATION_WITHOUT_CONTENTION",
                    core,
                    baseP95,
                    contP95,
                    degradationRatio,
                    deadlineMisses,
                    deadlineMissRate,
                    errorCount,
                    coreBusy,
                    latestPsiAvg,
                    corroborated,
                    explanation,
                    missingSources,
                    limitation
            );
        }

        return new ContentionEvidence(
                "NO_CONTENTION",
                core,
                baseP95,
                contP95,
                degradationRatio,
                deadlineMisses,
                deadlineMissRate,
                errorCount,
                coreBusy,
                latestPsiAvg,
                corroborated,
                "No significant contention detected between protected service and background workloads.",
                missingSources,
                limitation
        );
    }

    private ContentionEvidence insufficient(String reason, int core, List<String> missing) {
        return new ContentionEvidence(
                "INSUFFICIENT_EVIDENCE",
                core,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                reason,
                missing,
                "Insufficient observations to assess per-core competition."
        );
    }
}
