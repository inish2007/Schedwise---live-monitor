package dev.schedwise.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.schedwise.model.Telemetry.Identity;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Persists and retrieves authenticated action audits for priority changes.
 */
@Component
public class ActionAuditStore {
    private final SessionStore sessionStore;
    private final ObjectMapper json;
    private final List<ActionAudit> inMemoryAudits = new CopyOnWriteArrayList<>();

    public record ActionAudit(
            String id,
            String sessionId,
            String experimentId,
            String recommendationId,
            String actionType,
            long targetPid,
            String targetRole,
            Identity targetIdentity,
            int originalNice,
            int requestedNice,
            Integer observedNice,
            String status,
            String reason,
            String createdAt
    ) {}

    public ActionAuditStore(SessionStore sessionStore, ObjectMapper json) {
        this.sessionStore = sessionStore;
        this.json = json;
    }

    public synchronized void record(ActionAudit audit) {
        inMemoryAudits.add(0, audit);
        while (inMemoryAudits.size() > 100) {
            inMemoryAudits.remove(inMemoryAudits.size() - 1);
        }
        try {
            Connection conn = sessionStore.getConnection();
            try (PreparedStatement p = conn.prepareStatement(
                    "INSERT OR REPLACE INTO action_audits (id, session_id, experiment_id, recommendation_id, action_type, " +
                    "target_pid, target_role, target_identity, original_nice, requested_nice, observed_nice, status, reason, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                p.setString(1, audit.id());
                p.setString(2, audit.sessionId());
                p.setString(3, audit.experimentId());
                p.setString(4, audit.recommendationId());
                p.setString(5, audit.actionType());
                p.setLong(6, audit.targetPid());
                p.setString(7, audit.targetRole());
                p.setString(8, json.writeValueAsString(audit.targetIdentity()));
                p.setInt(9, audit.originalNice());
                p.setInt(10, audit.requestedNice());
                if (audit.observedNice() != null) p.setInt(11, audit.observedNice());
                else p.setNull(11, Types.INTEGER);
                p.setString(12, audit.status());
                p.setString(13, audit.reason());
                p.setString(14, audit.createdAt());
                p.executeUpdate();
            }
        } catch (Exception ignored) {
            // Memory audit is retained if storage is temporarily busy
        }
    }

    public Optional<ActionAudit> findById(String id) {
        for (ActionAudit audit : inMemoryAudits) {
            if (audit.id().equals(id)) return Optional.of(audit);
        }
        try {
            Connection conn = sessionStore.getConnection();
            try (PreparedStatement p = conn.prepareStatement("SELECT * FROM action_audits WHERE id = ?")) {
                p.setString(1, id);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        Identity identity = json.readValue(r.getString("target_identity"), Identity.class);
                        int obs = r.getInt("observed_nice");
                        Integer observed = r.wasNull() ? null : obs;
                        return Optional.of(new ActionAudit(
                                r.getString("id"),
                                r.getString("session_id"),
                                r.getString("experiment_id"),
                                r.getString("recommendation_id"),
                                r.getString("action_type"),
                                r.getLong("target_pid"),
                                r.getString("target_role"),
                                identity,
                                r.getInt("original_nice"),
                                r.getInt("requested_nice"),
                                observed,
                                r.getString("status"),
                                r.getString("reason"),
                                r.getString("created_at")
                        ));
                    }
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    public List<ActionAudit> listRecent(int limit) {
        return inMemoryAudits.stream().limit(limit).toList();
    }

    public List<ActionAudit> findByExperimentId(String experimentId) {
        if (experimentId == null) return List.of();
        List<ActionAudit> list = inMemoryAudits.stream().filter(a -> experimentId.equals(a.experimentId())).toList();
        if (!list.isEmpty()) return list;
        try {
            Connection conn = sessionStore.getConnection();
            try (PreparedStatement p = conn.prepareStatement("SELECT * FROM action_audits WHERE experiment_id = ? ORDER BY created_at DESC")) {
                p.setString(1, experimentId);
                try (ResultSet r = p.executeQuery()) {
                    List<ActionAudit> fromDb = new ArrayList<>();
                    while (r.next()) {
                        Identity identity = json.readValue(r.getString("target_identity"), Identity.class);
                        int obs = r.getInt("observed_nice");
                        Integer observed = r.wasNull() ? null : obs;
                        fromDb.add(new ActionAudit(
                                r.getString("id"),
                                r.getString("session_id"),
                                r.getString("experiment_id"),
                                r.getString("recommendation_id"),
                                r.getString("action_type"),
                                r.getLong("target_pid"),
                                r.getString("target_role"),
                                identity,
                                r.getInt("original_nice"),
                                r.getInt("requested_nice"),
                                observed,
                                r.getString("status"),
                                r.getString("reason"),
                                r.getString("created_at")
                        ));
                    }
                    return fromDb;
                }
            }
        } catch (Exception ignored) {}
        return List.of();
    }
}
