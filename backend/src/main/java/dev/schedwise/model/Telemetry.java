package dev.schedwise.model;
import java.time.Instant;
import java.util.List;
import java.util.Map;
public final class Telemetry {
 private Telemetry() {}
 public enum Kind { MEASURED, DERIVED, SIMULATED, RECORDED }
 public record Value<T>(T value, String unit, Kind kind, String sourceId, Instant timestamp,
                        String sessionId, String availability, String reason, Double windowSeconds) {}
 public record Identity(String bootId, long pid, long startTicks) {}
 public record ProcessSample(Identity identity, Value<String> name, Value<String> state, Value<Long> uid,
    Value<Integer> nice, Value<Integer> threads, Value<String> allowedCpus, Value<Double> cpuPercent,
    String role, String lifecycle) {}
 public record Cpu(Value<Double> busyPercent, Value<Double> stealPercent, Value<List<Long>> counters) {}
 public record Snapshot(String sessionId, long sequence, Instant timestamp, String environmentId,
    String availability, String reason, Map<String,Cpu> cpus, List<ProcessSample> processes,
    Value<String> cpuPressureSome, Value<String> ioPressureSome, int omittedProcesses, int unreadableProcesses, double collectionMillis) {}
 public record Latest(Snapshot snapshot, long sampleAgeMillis, String storageStatus) {}
}
