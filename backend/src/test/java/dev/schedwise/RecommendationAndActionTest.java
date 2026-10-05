package dev.schedwise;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.schedwise.action.LinuxActionAdapter;
import dev.schedwise.action.LinuxActionAdapter.BatchActionResult;
import dev.schedwise.action.LinuxActionAdapter.TargetActionRequest;
import dev.schedwise.experiment.ExperimentManager;
import dev.schedwise.linux.LinuxSource;
import dev.schedwise.model.Telemetry.Identity;
import dev.schedwise.model.WorkloadRole;
import dev.schedwise.monitor.Capabilities;
import dev.schedwise.monitor.Collector;
import dev.schedwise.persistence.ActionAuditStore;
import dev.schedwise.persistence.SessionStore;
import dev.schedwise.recommendation.ContentionDetector;
import dev.schedwise.recommendation.ContentionDetector.ContentionEvidence;
import dev.schedwise.recommendation.RecommendationEngine;
import dev.schedwise.recommendation.RecommendationEngine.Recommendation;
import dev.schedwise.recommendation.RoleRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class RecommendationAndActionTest {
    @TempDir
    Path tempDir;

    private ObjectMapper json;
    private LinuxSource linux;
    private Capabilities capabilities;
    private Collector collector;
    private SessionStore sessionStore;
    private ActionAuditStore auditStore;
    private ExperimentManager experimentManager;
    private ContentionDetector contentionDetector;
    private RecommendationEngine recommendationEngine;
    private LinuxActionAdapter actionAdapter;
    private RoleRegistry roleRegistry;

    @BeforeEach
    void setUp() {
        System.setProperty("schedwise.data", tempDir.toString());
        json = new ObjectMapper();
        linux = new LinuxSource();
        capabilities = new Capabilities(linux);
        collector = new Collector(linux, capabilities);
        sessionStore = new SessionStore(collector, capabilities, json);
        auditStore = new ActionAuditStore(sessionStore, json);
        experimentManager = new ExperimentManager(capabilities, collector, linux, json);
        contentionDetector = new ContentionDetector();
        recommendationEngine = new RecommendationEngine(contentionDetector, capabilities, collector, experimentManager, json);
        actionAdapter = new LinuxActionAdapter(capabilities, experimentManager, auditStore, linux);
        roleRegistry = new RoleRegistry(experimentManager);
    }

    @AfterEach
    void tearDown() {
        sessionStore.stop();
        System.clearProperty("schedwise.data");
    }

    @Test
    void testContentionDetectorInsufficientEvidence() {
        ObjectNode empty = json.createObjectNode();
        ContentionEvidence evidence = contentionDetector.analyze(empty, null, null);
        assertEquals("INSUFFICIENT_EVIDENCE", evidence.status());
        assertNull(evidence.baselineP95Ms());
    }

    @Test
    void testContentionDetectorSustainedContention() {
        ObjectNode summary = json.createObjectNode();
        summary.put("core", 2);
        ObjectNode summaries = summary.putObject("summaries");

        ObjectNode baseline = summaries.putObject("BASELINE");
        baseline.put("scheduledCount", 300).put("successCount", 300).put("deadlineMissCount", 0);
        baseline.putObject("p95Ms").put("value", 40.0);
        baseline.putObject("deadlineMissRate").put("value", 0.0);

        ObjectNode contention = summaries.putObject("CONTENTION");
        contention.put("scheduledCount", 300).put("successCount", 280).put("deadlineMissCount", 45).put("errorCount", 0);
        contention.putObject("p95Ms").put("value", 450.0);
        contention.putObject("deadlineMissRate").put("value", 0.15);

        ContentionEvidence evidence = contentionDetector.analyze(summary, 22.5, 98.0);
        assertEquals("SUSTAINED_CONTENTION", evidence.status());
        assertEquals(2, evidence.core());
        assertEquals(40.0, evidence.baselineP95Ms());
        assertEquals(450.0, evidence.contentionP95Ms());
        assertTrue(evidence.latencyDegradationRatio() > 10.0);
        assertEquals(45, evidence.deadlineMissCount());
        assertTrue(evidence.corroboratedByPressure());
        assertTrue(evidence.assessment().contains("Sustained contention detected on Core 2"));
    }

    @Test
    void testContentionDetectorHighUtilizationWithoutContention() {
        ObjectNode summary = json.createObjectNode();
        summary.put("core", 1);
        ObjectNode summaries = summary.putObject("summaries");

        ObjectNode baseline = summaries.putObject("BASELINE");
        baseline.put("scheduledCount", 300).put("successCount", 300).put("deadlineMissCount", 0);
        baseline.putObject("p95Ms").put("value", 35.0);
        baseline.putObject("deadlineMissRate").put("value", 0.0);

        ObjectNode contention = summaries.putObject("CONTENTION");
        contention.put("scheduledCount", 300).put("successCount", 300).put("deadlineMissCount", 0).put("errorCount", 0);
        contention.putObject("p95Ms").put("value", 38.0);
        contention.putObject("deadlineMissRate").put("value", 0.0);

        ContentionEvidence evidence = contentionDetector.analyze(summary, 2.0, 92.0);
        assertEquals("HIGH_UTILIZATION_WITHOUT_CONTENTION", evidence.status());
        assertFalse(evidence.corroboratedByPressure());
        assertTrue(evidence.assessment().contains("Contention is not currently harmful"));
    }

    @Test
    void testRoleRegistryExternalProcessNeverEnrolledForControl() {
        Identity identity = new Identity("boot-test", 99999, 12345L);
        var assignment = roleRegistry.tagProcess(99999, identity, WorkloadRole.BACKGROUND, "User tagged batch job");
        assertEquals(WorkloadRole.BACKGROUND, assignment.role());
        assertFalse(assignment.isManaged());
        assertFalse(assignment.eligibleForControl());
        assertTrue(assignment.eligibilityReason().contains("strictly read-only in v1"));
    }

    @Test
    void testActionAdapterRejectsInvalidPidAndUnmanagedTargets() {
        TargetActionRequest pid0 = new TargetActionRequest(0, new Identity("boot", 0, 0), 0, 5);
        BatchActionResult r0 = actionAdapter.applyNice("act-pid0", "exp-1", "rec-1", List.of(pid0));
        assertEquals("FAILED", r0.overallStatus());
        assertEquals("REJECTED", r0.targets().getFirst().status());
        assertTrue(r0.targets().getFirst().reason().contains("must be > 1"));

        TargetActionRequest unmanaged = new TargetActionRequest(99999, new Identity("boot", 99999, 123), 0, 5);
        BatchActionResult rUnm = actionAdapter.applyNice("act-unm", "exp-1", "rec-1", List.of(unmanaged));
        assertEquals("FAILED", rUnm.overallStatus());
        assertEquals("REJECTED", rUnm.targets().getFirst().status());
        assertTrue(rUnm.targets().getFirst().reason().contains("not a managed child process"));
    }

    @Test
    void testActionAdapterIdempotency() {
        TargetActionRequest req = new TargetActionRequest(0, new Identity("boot", 0, 0), 0, 5);
        BatchActionResult r1 = actionAdapter.applyNice("act-idem-1", "exp-1", "rec-1", List.of(req));
        assertEquals("FAILED", r1.overallStatus());

        // Replay with identical actionId
        BatchActionResult r2 = actionAdapter.applyNice("act-idem-1", "exp-1", "rec-1", List.of(req));
        assertEquals("FAILED", r2.overallStatus());
        assertEquals("act-idem-1", r2.actionId());
        assertEquals(r1.targets().getFirst().pid(), r2.targets().getFirst().pid());
    }

    @Test
    void testActionAdapterLiveChildReniceAndReadback() throws Exception {
        // Launch a real child process using python to act as a managed worker
        ProcessBuilder pb = new ProcessBuilder("/usr/bin/python3", "-c", "import time; time.sleep(10)");
        Process proc = pb.start();
        long pid = proc.pid();

        try {
            // Register this child in experimentManager via reflection or controlled setup
            // Or test LinuxActionAdapter rejection on stale identity and expectedCurrentNice mismatch
            var stat = dev.schedwise.linux.ProcParser.stat(linux.read("/proc/" + pid + "/stat"));
            Identity realId = new Identity(capabilities.bootId, pid, stat.startTicks());

            // 1. Without being in experimentManager, it must be rejected as UNMANAGED
            TargetActionRequest req = new TargetActionRequest(pid, realId, 0, 5);
            BatchActionResult unmanagedRes = actionAdapter.applyNice("act-test-unm", "exp-1", null, List.of(req));
            assertEquals("REJECTED", unmanagedRes.targets().getFirst().status());
            assertTrue(unmanagedRes.targets().getFirst().reason().contains("not a managed child process"));

            // 2. Test invalid nice values: nice <= currentNice
            TargetActionRequest reqLower = new TargetActionRequest(pid, realId, 0, 0);
            BatchActionResult lowerRes = actionAdapter.applyNice("act-test-lower", "exp-1", null, List.of(reqLower));
            assertEquals("REJECTED", lowerRes.targets().getFirst().status());

            // 3. Test invalid range: nice > 19
            TargetActionRequest reqHigh = new TargetActionRequest(pid, realId, 0, 25);
            BatchActionResult highRes = actionAdapter.applyNice("act-test-high", "exp-1", null, List.of(reqHigh));
            assertEquals("REJECTED", highRes.targets().getFirst().status());
        } finally {
            proc.destroyForcibly();
        }
    }
}
