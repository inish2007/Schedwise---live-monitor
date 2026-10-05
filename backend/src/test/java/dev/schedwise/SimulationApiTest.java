package dev.schedwise;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.schedwise.simulation.CaptureService;
import dev.schedwise.simulation.SimulationModels.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SimulationApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CaptureService service = new CaptureService(json);

    @Test
    void testListAndSimulateRealCapture() throws Exception {
        List<CaptureInfo> captures = service.listCaptures();
        assertNotNull(captures);

        // Find the genuine Phase 2 capture if present on disk
        CaptureInfo realCapture = captures.stream()
                .filter(c -> "COMPLETED".equals(c.state()) && c.hasContention() && c.rawRequestCount() > 0)
                .findFirst()
                .orElse(null);

        if (realCapture != null) {
            SimulationRequest req = new SimulationRequest(
                    realCapture.id(),
                    "CONTENTION",
                    List.of("FCFS", "ROUND_ROBIN", "PRIORITY", "SJF", "CFS"),
                    20_000_000L, // 20ms
                    24_000_000L, // 24ms CFS latency
                    3_000_000L,  // 3ms CFS min granularity
                    5,           // candidate background nice 5
                    15_000_000_000L, // 15 seconds horizon
                    20000
            );

            SimulationResponse response = service.simulate(req);
            assertNotNull(response);
            assertEquals("SUFFICIENT", response.sufficiency());
            assertEquals(5, response.results().size());

            // Check each algorithm generated a timeline and metrics
            for (String algo : List.of("FCFS", "ROUND_ROBIN", "PRIORITY", "SJF", "CFS")) {
                AlgorithmResult res = response.results().get(algo);
                assertNotNull(res, "Missing result for " + algo);
                assertNotNull(res.metrics());
                assertFalse(res.timeline().isEmpty(), "Timeline must not be empty for " + algo);
                assertTrue(res.metrics().cpuUtilizationPercent() > 50.0, "CPU should be heavily utilized under contention");
                assertNotNull(res.complexity());
                assertNotNull(res.assumptions());
            }

            // Verify SJF prioritized short service requests over long background batch, completing far more jobs
            AlgorithmResult sjf = response.results().get("SJF");
            AlgorithmResult fcfs = response.results().get("FCFS");
            assertTrue(sjf.metrics().completedJobs() > fcfs.metrics().completedJobs(),
                    "SJF must complete far more jobs than non-preemptive FCFS when long background batch jobs arrive at t=0");
        }
    }

    @Test
    void testInsufficientInputReturnsHandledStatus() throws Exception {
        // Unknown capture ID returns 404/exception
        assertThrows(Exception.class, () -> {
            service.simulate(new SimulationRequest("non-existent-id", "CONTENTION", List.of("FCFS"), null, null, null, null, null, null));
        });
    }
    @Test void rejectsBrowserSuppliedPaths() {
        for(String id:List.of("../", "/tmp", "../../data/experiments"))assertThrows(IllegalArgumentException.class,()->service.simulate(new SimulationRequest(id,"CONTENTION",List.of("FCFS"),null,null,null,null,null,null)));
    }
}
