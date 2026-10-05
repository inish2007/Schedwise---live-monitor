CREATE TABLE sessions (id TEXT PRIMARY KEY, started_at TEXT NOT NULL, environment_json TEXT NOT NULL);
CREATE TABLE summaries (session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE, sequence INTEGER NOT NULL, sampled_at TEXT NOT NULL, cpu_percent REAL, process_count INTEGER NOT NULL);
CREATE TABLE captures (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, created_at TEXT NOT NULL, payload_json TEXT NOT NULL CHECK(length(payload_json) <= 8388608));
