package dev.schedwise.gameshield;

/**
 * Record of a background process affected by an active Game Shield session.
 */
public record ShieldedProcess(
        long pid,
        String name,
        String previousState,
        int previousNice,
        String actionApplied,
        long timestampMs,
        dev.schedwise.model.Telemetry.Identity identity
) {}
