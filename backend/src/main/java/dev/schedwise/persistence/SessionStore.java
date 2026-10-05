package dev.schedwise.persistence;

import dev.schedwise.monitor.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import jakarta.annotation.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

@Component
public class SessionStore {
    private final Collector collector;
    private final Capabilities caps;
    private final ObjectMapper json;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private volatile String status = "INITIALIZING";
    private Connection db;

    public SessionStore(Collector collector, Capabilities caps, ObjectMapper json) {
        this.collector = collector;
        this.caps = caps;
        this.json = json;
    }

    public String status() {
        return status;
    }

    public record SessionInfo(String id, String startedAt, com.fasterxml.jackson.databind.JsonNode environment) {}

    public List<SessionInfo> listSessions() {
        try {
            Connection conn = getConnection();
            try (Statement s = conn.createStatement();
                 ResultSet r = s.executeQuery("SELECT id, started_at, environment_json FROM sessions ORDER BY started_at DESC LIMIT 20")) {
                List<SessionInfo> list = new ArrayList<>();
                while (r.next()) {
                    com.fasterxml.jackson.databind.JsonNode env = json.readTree(r.getString("environment_json"));
                    list.add(new SessionInfo(r.getString("id"), r.getString("started_at"), env));
                }
                return list;
            }
        } catch (Exception ignored) {
            return List.of();
        }
    }

    @PostConstruct
    public void start() {
        writer.scheduleWithFixedDelay(this::write, 0, 10, TimeUnit.SECONDS);
    }

    public synchronized Connection getConnection() throws SQLException {
        if (db == null || db.isClosed()) {
            initDb();
        }
        return db;
    }

    private synchronized void initDb() throws SQLException {
        try {
            Path dir = Path.of(System.getProperty("schedwise.data", "../data"));
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            db = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("schedwise.sqlite"));
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA busy_timeout=2000");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA max_page_count=16384");
                int version;
                try (ResultSet r = s.executeQuery("PRAGMA user_version")) {
                    version = r.getInt(1);
                }
                if (version > 2) throw new SQLException("Unsupported schema version: " + version);
                if (version == 0) {
                    db.setAutoCommit(false);
                    try (var in = getClass().getResourceAsStream("/db/migration/V1__sessions.sql")) {
                        if (in != null) {
                            for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                                if (!sql.isBlank()) s.execute(sql);
                            }
                        }
                        s.execute("PRAGMA user_version=1");
                        db.commit();
                    } catch (Exception e) {
                        db.rollback();
                        throw e;
                    } finally {
                        db.setAutoCommit(true);
                    }
                    version = 1;
                }
                if (version == 1) {
                    db.setAutoCommit(false);
                    try (var in = getClass().getResourceAsStream("/db/migration/V2__actions.sql")) {
                        if (in != null) {
                            for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                                if (!sql.isBlank()) s.execute(sql);
                            }
                        }
                        s.execute("PRAGMA user_version=2");
                        db.commit();
                    } catch (Exception e) {
                        db.rollback();
                        throw e;
                    } finally {
                        db.setAutoCommit(true);
                    }
                    version = 2;
                }
                s.executeUpdate("DELETE FROM sessions WHERE julianday(started_at) < julianday('now','-7 days') OR id IN (SELECT id FROM sessions ORDER BY started_at DESC LIMIT -1 OFFSET 19)");
            }
            try (PreparedStatement p = db.prepareStatement("INSERT OR IGNORE INTO sessions VALUES(?,?,?)")) {
                p.setString(1, caps.sessionId);
                p.setString(2, java.time.Instant.now().toString());
                p.setString(3, json.writeValueAsString(caps.discover()));
                p.executeUpdate();
            }
            status = "AVAILABLE";
        } catch (Exception e) {
            status = "UNAVAILABLE: " + e.getClass().getSimpleName();
            if (db != null) {
                try { db.close(); } catch (Exception ignored) {}
                db = null;
            }
            if (e instanceof SQLException se) throw se;
            throw new SQLException("Database initialization failed", e);
        }
    }

    private void write() {
        try {
            if (db == null || db.isClosed()) {
                initDb();
            }
            var snap = collector.latest();
            if (snap != null) {
                try (PreparedStatement p = db.prepareStatement("INSERT OR REPLACE INTO summaries VALUES(?,?,?,?,?)")) {
                    p.setString(1, snap.sessionId());
                    p.setLong(2, snap.sequence());
                    p.setString(3, snap.timestamp().toString());
                    var cpu = snap.cpus().get("cpu");
                    p.setObject(4, cpu == null ? null : cpu.busyPercent().value());
                    p.setInt(5, snap.processes().size());
                    p.executeUpdate();
                }
            }
            status = "AVAILABLE";
        } catch (Exception e) {
            status = "UNAVAILABLE: " + e.getClass().getSimpleName();
        }
    }

    @PreDestroy
    public void stop() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) writer.shutdownNow();
            if (db != null) db.close();
        } catch (Exception ignored) {}
    }
}
