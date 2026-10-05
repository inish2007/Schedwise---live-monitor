CREATE TABLE IF NOT EXISTS action_audits (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    experiment_id TEXT,
    recommendation_id TEXT,
    action_type TEXT NOT NULL,
    target_pid INTEGER NOT NULL,
    target_role TEXT NOT NULL,
    target_identity TEXT NOT NULL,
    original_nice INTEGER NOT NULL,
    requested_nice INTEGER NOT NULL,
    observed_nice INTEGER,
    status TEXT NOT NULL,
    reason TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_action_audits_session ON action_audits(session_id);
