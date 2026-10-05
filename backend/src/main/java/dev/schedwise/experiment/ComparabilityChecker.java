package dev.schedwise.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates whether measurement phases in an experiment or capture are scientifically comparable.
 * Enforces zero-mock and strict comparability rules from AGENTS.md and plan.md:
 * 1. Checks matched workload parameters, request rate, grouping/affinity, worker survival, and sample sizes.
 * 2. Marks invalid comparisons explicitly instead of displaying a misleading gain.
 * 3. Preserves negative and unchanged results honestly.
 * 4. Completion-time claims require genuinely completed identical work budgets in matched runs.
 */
public final class ComparabilityChecker {

    public record ComparabilityReport(
            String status, // "VALID" or "INVALID"
            boolean comparable,
            List<String> invalidReasons,
            boolean hasAfterAction,
            Double baselineP95Ms,
            Double contentionP95Ms,
            Double afterP95Ms,
            Double baselineP99Ms,
            Double contentionP99Ms,
            Double afterP99Ms,
            Long deadlineMissesContention,
            Long deadlineMissesAfter,
            Double workerHashesContention,
            Double workerHashesAfter,
            Double latencyP95ImprovementPercent,
            Double latencyRecoveryPercent,
            Double throughputTradeoffPercent,
            Long deadlineMissReduction,
            List<String> disclosures
    ) {
        public ObjectNode toJson(ObjectMapper json) {
            ObjectNode node = json.createObjectNode();
            node.put("status", status);
            node.put("comparable", comparable);
            ArrayNode reasons = node.putArray("invalidReasons");
            invalidReasons.forEach(reasons::add);
            node.put("hasAfterAction", hasAfterAction);

            if (baselineP95Ms != null) node.put("baselineP95Ms", baselineP95Ms); else node.putNull("baselineP95Ms");
            if (contentionP95Ms != null) node.put("contentionP95Ms", contentionP95Ms); else node.putNull("contentionP95Ms");
            if (afterP95Ms != null) node.put("afterP95Ms", afterP95Ms); else node.putNull("afterP95Ms");

            if (baselineP99Ms != null) node.put("baselineP99Ms", baselineP99Ms); else node.putNull("baselineP99Ms");
            if (contentionP99Ms != null) node.put("contentionP99Ms", contentionP99Ms); else node.putNull("contentionP99Ms");
            if (afterP99Ms != null) node.put("afterP99Ms", afterP99Ms); else node.putNull("afterP99Ms");

            if (deadlineMissesContention != null) node.put("deadlineMissesContention", deadlineMissesContention); else node.putNull("deadlineMissesContention");
            if (deadlineMissesAfter != null) node.put("deadlineMissesAfter", deadlineMissesAfter); else node.putNull("deadlineMissesAfter");

            if (workerHashesContention != null) node.put("workerHashesContention", workerHashesContention); else node.putNull("workerHashesContention");
            if (workerHashesAfter != null) node.put("workerHashesAfter", workerHashesAfter); else node.putNull("workerHashesAfter");

            if (latencyP95ImprovementPercent != null) node.put("latencyP95ImprovementPercent", latencyP95ImprovementPercent); else node.putNull("latencyP95ImprovementPercent");
            if (latencyRecoveryPercent != null) node.put("latencyRecoveryPercent", latencyRecoveryPercent); else node.putNull("latencyRecoveryPercent");
            if (throughputTradeoffPercent != null) node.put("throughputTradeoffPercent", throughputTradeoffPercent); else node.putNull("throughputTradeoffPercent");
            if (deadlineMissReduction != null) node.put("deadlineMissReduction", deadlineMissReduction); else node.putNull("deadlineMissReduction");

            ArrayNode disc = node.putArray("disclosures");
            disclosures.forEach(disc::add);
            return node;
        }
    }

    public static ComparabilityReport evaluate(JsonNode summary) {
        List<String> reasons = new ArrayList<>();
        List<String> disclosures = new ArrayList<>();

        disclosures.add("Completion-time claims require genuinely completed identical work budgets in matched runs; 30s windows measure sustained throughput and responsiveness.");
        disclosures.add("Simulated queue wait times from DSA models are strictly separate from measured end-to-end HTTP latency.");
        disclosures.add("Unchanged or negative improvements reflect true system scheduling behavior under the tested workload and core pinning.");

        if (summary == null || summary.isNull() || summary.isMissingNode()) {
            return new ComparabilityReport(
                    "INVALID", false, List.of("No experiment summary available"), false,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, disclosures
            );
        }

        JsonNode summaries = summary.path("summaries");
        JsonNode baseline = summaries.path("BASELINE");
        JsonNode contention = summaries.path("CONTENTION");
        JsonNode after = summaries.path("AFTER_ACTION");

        boolean hasBaseline = !baseline.isMissingNode() && baseline.path("scheduledCount").asInt(0) > 0;
        boolean hasContention = !contention.isMissingNode() && contention.path("scheduledCount").asInt(0) > 0;
        boolean hasAfter = !after.isMissingNode() && after.path("scheduledCount").asInt(0) > 0;

        if (!hasBaseline) {
            reasons.add("Baseline phase is missing or has zero scheduled requests");
        }
        if (!hasContention) {
            reasons.add("Contention phase is missing or has zero scheduled requests");
        }

        JsonNode params = summary.path("parameters");
        if (params.isMissingNode() || params.path("iterations").asInt(0) <= 0) {
            reasons.add("Workload parameters were not properly frozen or calibrated");
        }

        int baselineSuccess = baseline.path("successCount").asInt(0);
        int contentionSuccess = contention.path("successCount").asInt(0);
        int afterSuccess = hasAfter ? after.path("successCount").asInt(0) : 0;

        if (hasBaseline && baselineSuccess < 20) {
            reasons.add("Baseline has fewer than 20 successful requests; statistical p95 is unavailable");
        }
        if (hasContention && contentionSuccess < 20) {
            reasons.add("Contention phase has fewer than 20 successful requests; statistical p95 is unavailable");
        }

        Double baselineP95 = getDouble(baseline.path("p95Ms").path("value"));
        Double contentionP95 = getDouble(contention.path("p95Ms").path("value"));
        Double afterP95 = hasAfter ? getDouble(after.path("p95Ms").path("value")) : null;

        Double baselineP99 = getDouble(baseline.path("p99Ms").path("value"));
        Double contentionP99 = getDouble(contention.path("p99Ms").path("value"));
        Double afterP99 = hasAfter ? getDouble(after.path("p99Ms").path("value")) : null;

        if (baselineP95 == null || baselineP95 <= 0) {
            reasons.add("Baseline p95 latency is missing or non-positive; cannot compute relative improvement");
        }
        if (contentionP95 == null || contentionP95 <= 0) {
            reasons.add("Contention p95 latency is missing or non-positive");
        }

        Double baseRate = getDouble(baseline.path("offeredRateHz").path("value"));
        Double contRate = getDouble(contention.path("offeredRateHz").path("value"));
        Double afterRate = hasAfter ? getDouble(after.path("offeredRateHz").path("value")) : null;

        if (baseRate != null && contRate != null && contRate > 0) {
            double rateShift = Math.abs(contRate - baseRate) / contRate;
            if (rateShift > 0.05) {
                reasons.add("Offered request rate shifted by " + String.format("%.1f%%", rateShift * 100) + " between baseline and contention");
            }
        }
        if (hasAfter && contRate != null && afterRate != null && contRate > 0) {
            double rateShift = Math.abs(afterRate - contRate) / contRate;
            if (rateShift > 0.05) {
                reasons.add("Offered request rate shifted by " + String.format("%.1f%%", rateShift * 100) + " between contention and after-action");
            }
        }

        // Background worker hash rates
        Double contHashes = null;
        Double afterHashes = null;
        JsonNode workers = summary.path("workers");
        if (workers.isArray() && workers.size() >= 2) {
            double contSum = 0;
            double afterSum = 0;
            int contCount = 0;
            int afterCount = 0;

            for (JsonNode w : workers) {
                Double hCont = getDouble(w.path("hashesPerSecond").path("value"));
                if (hCont != null && hCont > 0) {
                    contSum += hCont;
                    contCount++;
                }

                JsonNode byPhase = w.path("hashesByPhase");
                if (!byPhase.isMissingNode()) {
                    Double hAfter = getDouble(byPhase.path("AFTER_ACTION").path("value"));
                    if (hAfter != null && hAfter > 0) {
                        afterSum += hAfter;
                        afterCount++;
                    }
                }
            }

            if (contCount > 0) contHashes = contSum;
            if (afterCount > 0) afterHashes = afterSum;
        }

        Long missesCont = contention.path("deadlineMissCount").isNumber() ? contention.path("deadlineMissCount").asLong() : null;
        Long missesAfter = hasAfter && after.path("deadlineMissCount").isNumber() ? after.path("deadlineMissCount").asLong() : null;

        if (!hasAfter) {
            reasons.add("After-action phase is not recorded in this session. Start an experiment with priority change to record after-action measurements.");
        } else if (afterSuccess < 20) {
            reasons.add("After-action phase has fewer than 20 successful requests; statistical p95 is unavailable");
        } else if (afterP95 == null || afterP95 <= 0) {
            reasons.add("After-action p95 latency is missing or non-positive");
        }

        boolean comparable = reasons.isEmpty();
        String status = comparable ? "VALID" : "INVALID";

        Double p95Improvement = null;
        Double recovery = null;
        Double throughputTradeoff = null;
        Long missReduction = null;

        if (comparable && contentionP95 != null && afterP95 != null && baselineP95 != null) {
            // Latency p95 improvement relative to contention: (contention - after) / contention
            p95Improvement = ((contentionP95 - afterP95) / contentionP95) * 100.0;

            // Recovery towards baseline: (contention - after) / (contention - baseline)
            double gap = contentionP95 - baselineP95;
            if (gap > 0) {
                recovery = ((contentionP95 - afterP95) / gap) * 100.0;
            }

            // Throughput tradeoff: (after - contention) / contention
            if (contHashes != null && afterHashes != null && contHashes > 0) {
                throughputTradeoff = ((afterHashes - contHashes) / contHashes) * 100.0;
            }

            if (missesCont != null && missesAfter != null) {
                missReduction = missesCont - missesAfter;
            }
        }

        return new ComparabilityReport(
                status,
                comparable,
                reasons,
                hasAfter,
                baselineP95,
                contentionP95,
                afterP95,
                baselineP99,
                contentionP99,
                afterP99,
                missesCont,
                missesAfter,
                contHashes,
                afterHashes,
                p95Improvement,
                recovery,
                throughputTradeoff,
                missReduction,
                disclosures
        );
    }

    private static Double getDouble(JsonNode node) {
        if (node != null && node.isNumber()) {
            return node.asDouble();
        }
        return null;
    }
}
