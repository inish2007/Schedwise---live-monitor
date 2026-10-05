package dev.schedwise.gameshield;

/**
 * Telemetry record representing a candidate process inspected for Game Shield.
 */
public record ShieldCandidate(
        long pid,
        String name,
        String cmdline,
        Long uid,
        int nice,
        String state,
        Double cpuPercent,
        Long rssBytes,
        boolean isImmune,
        String category,
        String immunityReason,
        dev.schedwise.model.Telemetry.Identity identity,
        int threads
) {}
