package dev.schedwise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.schedwise.experiment.ComparabilityChecker;
import dev.schedwise.experiment.ComparabilityChecker.ComparabilityReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ComparabilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void testNullOrEmptySummaryIsInvalid() {
        ComparabilityReport report = ComparabilityChecker.evaluate(null);
        assertEquals("INVALID", report.status());
        assertFalse(report.comparable());
        assertFalse(report.invalidReasons().isEmpty());
    }

    @Test
    void testMissingAfterActionIsMarkedExplicitly() {
        ObjectNode summary = json.createObjectNode();
        ObjectNode params = summary.putObject("parameters");
        params.put("iterations", 20000);
        params.put("bufferBytes", 1024);

        ObjectNode summaries = summary.putObject("summaries");
        ObjectNode base = summaries.putObject("BASELINE");
        base.put("scheduledCount", 780);
        base.put("successCount", 780);
        base.putObject("p95Ms").put("value", 38.5);
        base.putObject("offeredRateHz").put("value", 26.0);

        ObjectNode cont = summaries.putObject("CONTENTION");
        cont.put("scheduledCount", 780);
        cont.put("successCount", 780);
        cont.putObject("p95Ms").put("value", 720.0);
        cont.putObject("offeredRateHz").put("value", 26.0);

        ComparabilityReport report = ComparabilityChecker.evaluate(summary);
        assertEquals("INVALID", report.status());
        assertFalse(report.hasAfterAction());
        assertTrue(report.invalidReasons().stream().anyMatch(r -> r.contains("After-action phase is not recorded")));
        assertNull(report.latencyP95ImprovementPercent(), "Must not compute gain when after-action phase is missing");
    }

    @Test
    void testValidThreeWayComparisonCalculatesTradeoffs() {
        ObjectNode summary = json.createObjectNode();
        ObjectNode params = summary.putObject("parameters");
        params.put("iterations", 20000);
        params.put("bufferBytes", 1024);

        ObjectNode summaries = summary.putObject("summaries");
        ObjectNode base = summaries.putObject("BASELINE");
        base.put("scheduledCount", 780);
        base.put("successCount", 780);
        base.put("deadlineMissCount", 0);
        base.putObject("p95Ms").put("value", 40.0);
        base.putObject("offeredRateHz").put("value", 26.0);

        ObjectNode cont = summaries.putObject("CONTENTION");
        cont.put("scheduledCount", 780);
        cont.put("successCount", 780);
        cont.put("deadlineMissCount", 50);
        cont.putObject("p95Ms").put("value", 700.0);
        cont.putObject("offeredRateHz").put("value", 26.0);

        ObjectNode after = summaries.putObject("AFTER_ACTION");
        after.put("scheduledCount", 780);
        after.put("successCount", 780);
        after.put("deadlineMissCount", 5);
        after.putObject("p95Ms").put("value", 70.0);
        after.putObject("offeredRateHz").put("value", 26.0);

        var workers = summary.putArray("workers");
        var w1 = workers.addObject();
        w1.putObject("hashesPerSecond").put("value", 150000.0);
        w1.putObject("hashesByPhase").putObject("AFTER_ACTION").put("value", 90000.0);
        var w2 = workers.addObject();
        w2.putObject("hashesPerSecond").put("value", 150000.0);
        w2.putObject("hashesByPhase").putObject("AFTER_ACTION").put("value", 90000.0);

        ComparabilityReport report = ComparabilityChecker.evaluate(summary);
        assertEquals("VALID", report.status());
        assertTrue(report.comparable());
        assertTrue(report.hasAfterAction());
        assertEquals(0, report.invalidReasons().size());

        assertNotNull(report.latencyP95ImprovementPercent());
        // (700 - 70) / 700 = 630 / 700 = 90.0%
        assertEquals(90.0, report.latencyP95ImprovementPercent(), 0.01);

        // Recovery: (700 - 70) / (700 - 40) = 630 / 660 = 95.45%
        assertEquals(95.45, report.latencyRecoveryPercent(), 0.1);

        // Throughput tradeoff: (180,000 - 300,000) / 300,000 = -40.0%
        assertNotNull(report.throughputTradeoffPercent());
        assertEquals(-40.0, report.throughputTradeoffPercent(), 0.01);

        // Deadline misses reduced: 50 - 5 = 45
        assertEquals(45L, report.deadlineMissReduction());

        // Disclosures present
        assertFalse(report.disclosures().isEmpty());
    }

    @Test
    void testOfferedRateMismatchFailsComparability() {
        ObjectNode summary = json.createObjectNode();
        ObjectNode params = summary.putObject("parameters");
        params.put("iterations", 20000);
        params.put("bufferBytes", 1024);

        ObjectNode summaries = summary.putObject("summaries");
        ObjectNode base = summaries.putObject("BASELINE");
        base.put("scheduledCount", 780);
        base.put("successCount", 780);
        base.putObject("p95Ms").put("value", 40.0);
        base.putObject("offeredRateHz").put("value", 26.0);

        ObjectNode cont = summaries.putObject("CONTENTION");
        cont.put("scheduledCount", 780);
        cont.put("successCount", 780);
        cont.putObject("p95Ms").put("value", 700.0);
        cont.putObject("offeredRateHz").put("value", 15.0); // Dropped rate!

        ComparabilityReport report = ComparabilityChecker.evaluate(summary);
        assertEquals("INVALID", report.status());
        assertTrue(report.invalidReasons().stream().anyMatch(r -> r.contains("Offered request rate shifted")));
    }
    @Test void mixedSettingsInOneWindowSuppressChangePercentages(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var summary=json.createObjectNode().put("state","COMPLETED");
        var phases=summary.putArray("phases");
        phases.addObject().put("name","CONTENTION").put("startNs",100).put("endNs",200);
        phases.addObject().put("name","AFTER_ACTION").put("startNs",200).put("endNs",300);
        StringBuilder events=new StringBuilder();
        for(int time:new int[]{110,150,210,250}) {
            var event=json.createObjectNode().put("event","cohort_verified").put("monotonicNs",time);
            var members=event.putArray("members");
            for(int pid=1;pid<=3;pid++) {
                var member=members.addObject().put("nice",time==250&&pid>1?5:0).put("threads",1).put("cgroup","test");
                member.putObject("identity").put("pid",pid).put("bootId","test").put("startTicks",1);
                member.putArray("allowedCpus").add(2);
            }
            events.append(json.writeValueAsString(event)).append('\n');
        }
        java.nio.file.Files.writeString(directory.resolve("events.jsonl"),events);
        var result=ComparabilityChecker.evaluateRecorded(summary,directory,json);
        assertFalse(result.comparable());assertNull(result.latencyP95ImprovementPercent());
        assertTrue(result.invalidReasons().stream().anyMatch(reason->reason.contains("changed within the AFTER_ACTION")));
    }

}
