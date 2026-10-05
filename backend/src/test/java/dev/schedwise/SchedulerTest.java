package dev.schedwise;

import dev.schedwise.scheduler.*;
import dev.schedwise.simulation.SimulationEngine;
import dev.schedwise.simulation.SimulationModels.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SchedulerTest {

    @Test
    void testFcfsFIFOAndNonPreemptive() {
        Job j1 = new Job("j1", "PROTECTED_SERVICE", 0L, 50_000_000L, 0, 0, 1024);
        Job j2 = new Job("j2", "PROTECTED_SERVICE", 10_000_000L, 20_000_000L, 0, 0, 1024);
        Job j3 = new Job("j3", "PROTECTED_SERVICE", 20_000_000L, 10_000_000L, 0, 0, 1024);

        SimulationEngine engine = new SimulationEngine(new FcfsScheduler(), List.of(j1, j2, j3), 100_000_000L, 1000);
        AlgorithmResult result = engine.run();

        assertEquals(3, result.timeline().size());
        assertEquals("j1", result.timeline().get(0).jobId());
        assertEquals(0L, result.timeline().get(0).startNs());
        assertEquals(50_000_000L, result.timeline().get(0).endNs());

        assertEquals("j2", result.timeline().get(1).jobId());
        assertEquals(50_000_000L, result.timeline().get(1).startNs());
        assertEquals(70_000_000L, result.timeline().get(1).endNs());

        assertEquals("j3", result.timeline().get(2).jobId());
        assertEquals(70_000_000L, result.timeline().get(2).startNs());
        assertEquals(80_000_000L, result.timeline().get(2).endNs());

        assertEquals(3, result.metrics().completedJobs());
        assertEquals(0, result.metrics().censoredJobs());
    }

    @Test
    void testRoundRobinQuantumBoundaryAndArrivalOrdering() {
        // j1 arrives at 0, demand 30ms. Quantum = 10ms.
        // j2 arrives at 10ms (exactly at quantum boundary), demand 10ms.
        // In textbook RR, j2 should enter ready queue BEFORE j1 is requeued!
        Job j1 = new Job("j1", "BACKGROUND", 0L, 30_000_000L, 0, 0, 1024);
        Job j2 = new Job("j2", "SERVICE", 10_000_000L, 10_000_000L, 0, 0, 1024);

        RoundRobinScheduler rr = new RoundRobinScheduler(10_000_000L); // 10ms quantum
        SimulationEngine engine = new SimulationEngine(rr, List.of(j1, j2), 50_000_000L, 1000);
        AlgorithmResult result = engine.run();

        List<TimelineSegment> tl = result.timeline();
        assertTrue(tl.size() >= 4);

        // 1st slice: j1 from 0 to 10ms
        assertEquals("j1", tl.get(0).jobId());
        assertEquals(0L, tl.get(0).startNs());
        assertEquals(10_000_000L, tl.get(0).endNs());

        // 2nd slice: j2 MUST execute next because it arrived at the boundary before j1 was requeued
        assertEquals("j2", tl.get(1).jobId());
        assertEquals(10_000_000L, tl.get(1).startNs());
        assertEquals(20_000_000L, tl.get(1).endNs());

        // 3rd slice: j1 from 20 to 30ms
        assertEquals("j1", tl.get(2).jobId());
        assertEquals(20_000_000L, tl.get(2).startNs());
        assertEquals(30_000_000L, tl.get(2).endNs());

        // 4th slice: j1 from 30 to 40ms (finishes)
        assertEquals("j1", tl.get(3).jobId());
        assertEquals(30_000_000L, tl.get(3).startNs());
        assertEquals(40_000_000L, tl.get(3).endNs());
        assertEquals("COMPLETED", tl.get(3).reason());
    }

    @Test
    void testPriorityDeterministicTieBreaking() {
        // Priority: lower value = higher priority.
        // j1: priority 1, arrival 0
        // j2: priority 2, arrival 0
        // j3: priority 1, arrival 10
        Job j1 = new Job("j1", "LOW_PRIO", 0L, 20_000_000L, 10, 10, 110);
        Job j2 = new Job("j2", "HIGH_PRIO_A", 5_000_000L, 10_000_000L, 1, 0, 1024);
        Job j3 = new Job("j3", "HIGH_PRIO_B", 5_000_000L, 10_000_000L, 1, 0, 1024);

        SimulationEngine engine = new SimulationEngine(new PriorityScheduler(), List.of(j1, j2, j3), 50_000_000L, 1000);
        AlgorithmResult result = engine.run();

        List<TimelineSegment> tl = result.timeline();
        assertEquals(3, tl.size());

        // j1 starts at 0 because at t=0, only j1 was present (non-preemptive)
        assertEquals("j1", tl.get(0).jobId());
        assertEquals(20_000_000L, tl.get(0).endNs());

        // At t=20ms, j2 and j3 are both ready with equal priority 1 and equal arrival 5ms.
        // Deterministic tie-breaker by ID: "j2" < "j3"
        assertEquals("j2", tl.get(1).jobId());
        assertEquals("j3", tl.get(2).jobId());
    }

    @Test
    void testSjfDeterministicShortestJob() {
        Job j1 = new Job("j1", "LONG", 0L, 50_000_000L, 0, 0, 1024);
        Job j2 = new Job("j2", "MEDIUM", 5_000_000L, 30_000_000L, 0, 0, 1024);
        Job j3 = new Job("j3", "SHORT", 5_000_000L, 10_000_000L, 0, 0, 1024);

        SimulationEngine engine = new SimulationEngine(new SjfScheduler(), List.of(j1, j2, j3), 100_000_000L, 1000);
        AlgorithmResult result = engine.run();

        List<TimelineSegment> tl = result.timeline();
        // j1 runs first (non-preemptive) from 0 to 50ms
        assertEquals("j1", tl.get(0).jobId());
        // At t=50ms, both j2 (30ms) and j3 (10ms) are ready. SJF chooses j3 first!
        assertEquals("j3", tl.get(1).jobId());
        assertEquals(50_000_000L, tl.get(1).startNs());
        assertEquals(60_000_000L, tl.get(1).endNs());
        // Then j2
        assertEquals("j2", tl.get(2).jobId());
        assertEquals(60_000_000L, tl.get(2).startNs());
        assertEquals(90_000_000L, tl.get(2).endNs());
    }

    @Test
    void testSimplifiedCfsWeightedService() {
        // High weight job (nice 0 = 1024) vs Low weight job (nice 10 = 110)
        // Ratio is roughly 1024 / 110 ~ 9.3x
        Job jNormal = new Job("normal", "SERVICE", 0L, 60_000_000L, 0, 0, LinuxWeights.niceToWeight(0));
        Job jLow = new Job("low", "BACKGROUND", 0L, 60_000_000L, 10, 10, LinuxWeights.niceToWeight(10));

        SimplifiedCfsScheduler cfs = new SimplifiedCfsScheduler(10_000_000L, 2_000_000L);
        SimulationEngine engine = new SimulationEngine(cfs, List.of(jNormal, jLow), 50_000_000L, 2000);
        AlgorithmResult result = engine.run();

        long normalServed = 0L;
        long lowServed = 0L;
        for (TimelineSegment seg : result.timeline()) {
            if ("normal".equals(seg.jobId())) normalServed += seg.durationNs();
            if ("low".equals(seg.jobId())) lowServed += seg.durationNs();
        }

        assertTrue(normalServed > lowServed, "Normal priority task must receive significantly more CPU time than low priority task");
        assertTrue(normalServed >= lowServed * 3, "Weight 1024 vs 110 should grant at least 3x more execution time");
    }

    @Test
    void testIdlePeriodJumpAndLateArrivals() {
        Job j1 = new Job("j1", "A", 0L, 10_000_000L, 0, 0, 1024);
        // Huge idle gap between 10ms and 100ms
        Job j2 = new Job("j2", "B", 100_000_000L, 10_000_000L, 0, 0, 1024);

        SimulationEngine engine = new SimulationEngine(new FcfsScheduler(), List.of(j1, j2), 150_000_000L, 1000);
        AlgorithmResult result = engine.run();

        assertEquals(2, result.timeline().size());
        assertEquals(0L, result.timeline().get(0).startNs());
        assertEquals(10_000_000L, result.timeline().get(0).endNs());

        // j2 starts at 100ms after the idle gap
        assertEquals(100_000_000L, result.timeline().get(1).startNs());
        assertEquals(110_000_000L, result.timeline().get(1).endNs());

        // CPU utilization should be (10ms + 10ms) / 150ms = 13.33%
        assertNotNull(result.metrics().cpuUtilizationPercent());
        assertEquals(13.33, result.metrics().cpuUtilizationPercent(), 0.1);
    }

    @Test
    void testSingleCpuNonOverlapInvariant() {
        Job j1 = new Job("j1", "A", 0L, 35_000_000L, 0, 0, 1024);
        Job j2 = new Job("j2", "B", 5_000_000L, 45_000_000L, 0, 0, 1024);

        RoundRobinScheduler rr = new RoundRobinScheduler(10_000_000L);
        SimulationEngine engine = new SimulationEngine(rr, List.of(j1, j2), 80_000_000L, 1000);
        AlgorithmResult result = engine.run();

        List<TimelineSegment> tl = result.timeline();
        for (int i = 0; i < tl.size() - 1; i++) {
            TimelineSegment cur = tl.get(i);
            TimelineSegment next = tl.get(i + 1);
            assertEquals(cur.endNs(), next.startNs(), "Segments must not overlap and must be contiguous unless idle");
        }
    }

    @Test
    void testHorizonCensoring() {
        // Job requires 100ms, horizon is 40ms
        Job j1 = new Job("j1", "BATCH", 0L, 100_000_000L, 0, 0, 1024);

        SimulationEngine engine = new SimulationEngine(new FcfsScheduler(), List.of(j1), 40_000_000L, 100);
        AlgorithmResult result = engine.run();

        assertEquals(1, result.timeline().size());
        assertEquals("HORIZON_REACHED", result.timeline().get(0).reason());
        assertEquals(40_000_000L, result.timeline().get(0).endNs());

        assertEquals(0, result.metrics().completedJobs());
        assertEquals(1, result.metrics().censoredJobs());
        assertNull(result.metrics().meanTurnaroundTimeMs(), "Censored jobs must not produce fake turnaround times");
    }

    @Test
    void testNonNegativeWaitTimesAndResponse() {
        Job j1 = new Job("j1", "A", 0L, 10_000_000L, 0, 0, 1024);
        Job j2 = new Job("j2", "B", 5_000_000L, 15_000_000L, 0, 0, 1024);

        SimulationEngine engine = new SimulationEngine(new FcfsScheduler(), List.of(j1, j2), 50_000_000L, 100);
        AlgorithmResult result = engine.run();

        for (JobMetric j : result.jobs()) {
            assertNotNull(j.responseTimeNs());
            assertTrue(j.responseTimeNs() >= 0L, "Response time cannot be negative");
            assertNotNull(j.waitingTimeNs());
            assertTrue(j.waitingTimeNs() >= 0L, "Waiting time cannot be negative");
        }
    }

    @Test void rejectsZeroNegativeDemandAndDuplicateIds() {
        for(long demand:List.of(0L,-1L))assertThrows(IllegalArgumentException.class,()->new Job("a","TEST",0,demand,0,0,1024));
        Job a=new Job("a","TEST",0,1,0,0,1024);
        assertThrows(IllegalArgumentException.class,()->new SimulationEngine(new SimplifiedCfsScheduler(10,1),List.of(a,a),20,100));
    }
    @Test void simultaneousBoundaryArrivalsPrecedeRequeuedTask() {
        var jobs=List.of(new Job("a","TEST",0,30,0,0,1024),new Job("c","TEST",10,10,0,0,1024),new Job("b","TEST",10,10,0,0,1024));
        var result=new SimulationEngine(new RoundRobinScheduler(10),jobs,100,100).run();
        assertEquals(List.of("a","b","c","a","a"),result.timeline().stream().map(TimelineSegment::jobId).toList());
    }
}
