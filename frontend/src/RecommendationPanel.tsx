import { apiFetch } from './http';
import { useState, useEffect } from 'react';
import { Dialog, EmptyState, Provenance } from './ui';
import type {
  Recommendation,
  CandidateScenario,
  BatchActionResult,
  ActionAudit,
} from './recommendation-types';
import type { Experiment } from './experiment-types';

interface Props {
  token: string;
  experiment: Experiment | null;
}

export function RecommendationPanel({ token, experiment }: Props) {
  const [recommendation, setRecommendation] = useState<Recommendation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [audits, setAudits] = useState<ActionAudit[]>([]);
  const [pendingCandidate, setPendingCandidate] = useState<CandidateScenario | null>(null);
  const [actionResult, setActionResult] = useState<BatchActionResult | null>(null);
  const [auditError, setAuditError] = useState('');
  const [resetMessage, setResetMessage] = useState<string | null>(null);

  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const expired = !!recommendation && now >= Date.parse(recommendation.expiresAt);
  const headers = {
    'X-CSRF-Token': token,
    'Content-Type': 'application/json',
  };

  useEffect(() => {
    setRecommendation(null);
    setPendingCandidate(null);
    setActionResult(null);
    setAudits([]);
    if (token) void loadAudits();
  }, [token]);

  async function loadAudits() {
    try {
      const res = await apiFetch('/api/actions/audits?limit=20', { headers });
      if (!res.ok) throw new Error(`Audit unavailable (${res.status})`);
      setAudits((await res.json()) as ActionAudit[]);
      setAuditError('');
    } catch (e: unknown) {
      setAuditError(e instanceof Error ? e.message : 'Audit unavailable');
    }
  }

  async function generateRecommendation() {
    if (!token) return;
    setLoading(true);
    setError(null);
    setActionResult(null);
    try {
      const body = experiment?.id ? JSON.stringify({ experimentId: experiment.id }) : undefined;
      const res = await apiFetch('/api/recommendations', {
        method: 'POST',
        headers,
        body,
      });
      if (!res.ok) throw new Error(`Recommendation error (${res.status})`);
      const data = (await res.json()) as Recommendation;
      setRecommendation(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to generate recommendation');
    } finally {
      setLoading(false);
    }
  }

  async function confirmApply() {
    if (!pendingCandidate || !token || !pendingCandidate.eligible || expired) return;
    setLoading(true);
    setError(null);
    try {
      const actionId = crypto.randomUUID();
      const targets = pendingCandidate.targetWorkers.map(w => ({
        pid: w.pid,
        identity: w.identity,
        expectedCurrentNice: w.currentNice,
        requestedNice: pendingCandidate.targetNice,
      }));

      const res = await apiFetch('/api/actions/nice', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          actionId,
          experimentId: recommendation?.experimentId ?? experiment?.id,
          recommendationId: recommendation?.id,
          targets,
        }),
      });

      if (!res.ok) throw new Error(`Action mutation error (${res.status})`);
      const result = (await res.json()) as BatchActionResult;
      setActionResult(result);
      setPendingCandidate(null);
      await loadAudits();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action mutation failed');
    } finally {
      setLoading(false);
    }
  }

  async function resetWorkers() {
    if (!experiment?.id || !token) return;
    setLoading(true);
    setResetMessage(null);
    setError(null);
    try {
      const res = await apiFetch(`/api/experiments/${experiment.id}/reset`, {
        method: 'POST',
        headers,
      });
      if (!res.ok) throw new Error(`Reset error (${res.status})`);
      const data = (await res.json()) as { status: string; message: string };
      setResetMessage(data.message);
      setRecommendation(null);
      setActionResult(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Reset failed');
    } finally {
      setLoading(false);
    }
  }

  const evidence = recommendation?.evidence;
  const number = (value: number | null | undefined, unit = '') =>
    value == null ? 'Unavailable' : `${value.toFixed(1)}${unit}`;

  return (
    <section className="recommendations-panel panel-root" aria-label="Allocation advisor">
      {/* 1. Tactical Advisor Hero Banner (No Double Header) */}
      <div className="experiment-hero-banner">
        <div>
          <div className="scope-badge-group">
            <span className="scope-tag scope-tag-primary">MEASURED-INPUT ADVISOR</span>
            <span className="scope-tag">CFS WEIGHT-DECAY FORMULA</span>
            <span className="scope-tag scope-tag-success">UNPRIVILEGED RENICE ONLY</span>
          </div>
          <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--cyber-text-primary)' }}>
            Review empirical contention evidence and calculate candidate Linux nice allocations for managed background workers.
            Mutations require explicit user confirmation and affect only verified owned workers.
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <button
            className="primary"
            onClick={() => void generateRecommendation()}
            disabled={loading || !token}
            style={{ minHeight: 38 }}
          >
            {loading ? 'Evaluating…' : 'Evaluate Contention'}
          </button>
          {experiment?.state === 'RUNNING' && (
            <button
              onClick={() => void resetWorkers()}
              disabled={loading || !token}
              title="Stops and recreates owned workers with fresh identities"
            >
              Reset Workers
            </button>
          )}
        </div>
      </div>

      {error && !pendingCandidate && (
        <p className="notice alert-error" role="alert">
          {error}
        </p>
      )}
      {resetMessage && (
        <p className="notice scope-tag-success" role="status">
          {resetMessage}
        </p>
      )}

      {/* 2. Prerequisite Guidance Card (When No Recommendation) */}
      {!recommendation && (
        <div className="cyber-card" style={{ padding: 24, marginBottom: 20 }}>
          <div className="card-header">
            <div>
              <div className="eyebrow">PREREQUISITE FLOW</div>
              <h3>Evidence Precedes Optimization</h3>
            </div>
            <Provenance kind="MEASURED" />
          </div>
          <p style={{ fontSize: 13, lineHeight: 1.6, color: 'var(--cyber-text-primary)' }}>
            SchedWise operates strictly on verified empirical evidence. To generate explainable priority advice:
          </p>
          <div className="workload-params-hud" style={{ marginBottom: 16 }}>
            <div className="param-pod">
              <span className="param-label">Step 01</span>
              <span className="param-val" style={{ fontSize: 13 }}>Run Experiment in Lab</span>
              <small className="muted">Execute baseline and contention phases on a pinned core.</small>
            </div>
            <div className="param-pod">
              <span className="param-label">Step 02</span>
              <span className="param-val" style={{ fontSize: 13 }}>Click Evaluate Contention</span>
              <small className="muted">Parse verified HTTP latency degradation and CPU pressure.</small>
            </div>
            <div className="param-pod">
              <span className="param-label">Step 03</span>
              <span className="param-val" style={{ fontSize: 13 }}>Review Modeled Tradeoffs</span>
              <small className="muted">Inspect CFS weight shifts and confirm unprivileged renice.</small>
            </div>
            <div className="param-pod">
              <span className="param-label">Step 04</span>
              <span className="param-val" style={{ fontSize: 13 }}>Validate After-Change</span>
              <small className="muted">Measure whether latency actually recovered in the Lab.</small>
            </div>
          </div>
        </div>
      )}

      {/* 3. Evidence HUD & Candidate Allocations */}
      {recommendation && evidence && (
        <>
          <div className="cyber-card" style={{ padding: 20, marginBottom: 20 }}>
            <div className="card-header">
              <div>
                <div className="eyebrow">CONTENSIO EVIDENCE ASSESSMENT</div>
                <h3>{recommendation.status.replaceAll('_', ' ')}</h3>
              </div>
              <Provenance kind="MEASURED" />
            </div>
            <p style={{ fontSize: 13, lineHeight: 1.5, marginBottom: 16 }}>{evidence.assessment}</p>

            <div className="telemetry-hud-grid" style={{ marginBottom: 0 }}>
              <div className="metric-hud-tile">
                <span className="metric-hud-label">Baseline HTTP p95</span>
                <div className="metric-hud-body">
                  <strong>{number(evidence.baselineP95Ms)}</strong>
                  <span className="metric-hud-unit">ms</span>
                </div>
                <small className="metric-hud-sub">Uncontended reference service</small>
              </div>

              <div className="metric-hud-tile warning">
                <span className="metric-hud-label">Contention HTTP p95</span>
                <div className="metric-hud-body">
                  <strong>{number(evidence.contentionP95Ms)}</strong>
                  <span className="metric-hud-unit">ms</span>
                </div>
                <small className="metric-hud-sub">Competitors on same logical core</small>
              </div>

              <div className="metric-hud-tile">
                <span className="metric-hud-label">Latency Degradation</span>
                <div className="metric-hud-body">
                  <strong>{number(evidence.latencyDegradationRatio)}</strong>
                  <span className="metric-hud-unit">×</span>
                </div>
                <small className="metric-hud-sub">Derived ratio: Contention / Baseline</small>
              </div>

              <div className="metric-hud-tile">
                <span className="metric-hud-label">Deadline Miss Rate</span>
                <div className="metric-hud-body">
                  <strong>{number(evidence.deadlineMissRate * 100)}</strong>
                  <span className="metric-hud-unit">%</span>
                </div>
                <small className="metric-hud-sub">{evidence.deadlineMissCount} missed dispatch deadlines</small>
              </div>
            </div>
          </div>

          {evidence.missingSources.length > 0 && (
            <p className="notice alert-warning">Missing sources: {evidence.missingSources.join('; ')}</p>
          )}

          {/* Candidate Allocations */}
          <div className="cyber-card" style={{ padding: 20, marginBottom: 20 }}>
            <div className="card-header">
              <div>
                <div className="eyebrow">PREDICTIVE CFS MODEL</div>
                <h3>Candidate Allocation Scenarios</h3>
              </div>
              <Provenance kind="SIMULATED" />
            </div>
            <p className="muted" style={{ fontSize: 12, marginBottom: 16 }}>
              Valid until {new Date(recommendation.expiresAt).toLocaleTimeString()}.{' '}
              {expired
                ? 'Expired — evaluate again before applying.'
                : 'Shares describe the modeled runnable cohort, not guaranteed kernel CPU reservations.'}
            </p>

            <div className="candidate-grid">
              {recommendation.candidates.map(candidate => (
                <article className="candidate-card" key={candidate.targetNice} style={{ padding: 18 }}>
                  <div className="card-header" style={{ marginBottom: 10, paddingBottom: 0, border: 'none' }}>
                    <h3 style={{ margin: 0 }}>{candidate.title}</h3>
                    <span className="nice-chip">NICE +{candidate.targetNice}</span>
                  </div>
                  <p style={{ fontSize: 13, lineHeight: 1.5, marginBottom: 12 }}>{candidate.tradeoffSummary}</p>

                  <div
                    className="share-bar"
                    role="img"
                    aria-label={`Modeled protected share ${number(
                      candidate.expectedProtectedSharePercent,
                      '%'
                    )}; each worker ${number(candidate.expectedBackgroundSharePercent, '%')}`}
                  >
                    <span
                      className="share-protected"
                      style={{
                        width: `${Math.max(0, Math.min(100, candidate.expectedProtectedSharePercent))}%`,
                      }}
                    />
                    {candidate.targetWorkers.map(worker => (
                      <span
                        key={worker.pid}
                        className="share-background"
                        style={{
                          width: `${Math.max(0, Math.min(100, candidate.expectedBackgroundSharePercent))}%`,
                        }}
                      />
                    ))}
                  </div>

                  <p className="muted" style={{ fontSize: 11, margin: '8px 0 12px' }}>
                    Service {number(candidate.expectedProtectedSharePercent, '%')} · Each worker{' '}
                    {number(candidate.expectedBackgroundSharePercent, '%')}
                  </p>

                  <dl className="candidate-facts">
                    <div>
                      <dt>Service / Worker Weight</dt>
                      <dd>
                        {candidate.serviceWeight} / {candidate.targetWeight}
                      </dd>
                    </div>
                    <div>
                      <dt>Simulated First-Dispatch p95</dt>
                      <dd>{number(candidate.simulatedServiceResponseP95Ms, ' ms')}</dd>
                    </div>
                  </dl>

                  <p className="muted" style={{ fontSize: 11, margin: '8px 0 12px' }}>
                    {candidate.eligibilityNote}
                  </p>

                  <div className="candidate-footer" style={{ marginTop: 12 }}>
                    <small>{candidate.targetWorkers.length} managed worker processes</small>
                    <button
                      className="primary"
                      disabled={loading || !token || !candidate.eligible || expired}
                      onClick={() => {
                        setError(null);
                        setPendingCandidate(candidate);
                      }}
                    >
                      Review Change →
                    </button>
                  </div>
                </article>
              ))}
            </div>

            {recommendation.candidates.length === 0 && (
              <EmptyState title="No eligible allocation">{evidence.assessment}</EmptyState>
            )}

            <details style={{ marginTop: 16 }}>
              <summary>Mathematical Assumptions, Weight Decay & Restoration Limits</summary>
              <div style={{ padding: '8px 0', fontSize: 12, lineHeight: 1.5 }}>
                <p>{evidence.attributionLimitation}</p>
                {recommendation.limitations.map((item, i) => (
                  <p key={i}>{item}</p>
                ))}
                {recommendation.referenceModelExclusions.map((item, i) => (
                  <p key={i}>{item}</p>
                ))}
                <p>{recommendation.restorationNote}</p>
              </div>
            </details>
          </div>
        </>
      )}

      {/* Confirmation Dialog */}
      {pendingCandidate && (
        <Dialog
          title="Confirm Priority Change (Renice)"
          onClose={() => setPendingCandidate(null)}
          busy={loading}
        >
          <p>
            Increase nice to lower scheduling priority for these managed background workers.
            The backend verifies identity (bootId/startTicks) and unprivileged permissions before applying.
          </p>
          <div className="confirmation-targets">
            {pendingCandidate.targetWorkers.map(worker => (
              <article key={worker.pid}>
                <strong>
                  PID {worker.pid} · {worker.role}
                </strong>
                <p>
                  Current nice {worker.currentNice} <span aria-hidden="true">→</span> requested nice{' '}
                  {pendingCandidate.targetNice}
                </p>
                <small className="muted">
                  Boot {worker.identity.bootId} · start ticks {worker.identity.startTicks}
                </small>
              </article>
            ))}
          </div>
          <p className="notice alert-warning">
            Lowering nice again requires elevated privileges. Reset stops and recreates owned workers with new
            identities; it does not unprivileged undo.
          </p>
          {error && (
            <p className="notice alert-error" role="alert">
              {error}
            </p>
          )}
          {expired && (
            <p className="notice alert-warning">Recommendation expired. Close this dialog and evaluate again.</p>
          )}
          <div className="dialog-actions">
            <button disabled={loading} onClick={() => setPendingCandidate(null)}>
              Cancel
            </button>
            <button
              className="primary"
              disabled={loading || expired || !token || !pendingCandidate.eligible}
              onClick={() => void confirmApply()}
            >
              {loading ? 'Applying…' : `Confirm Nice +${pendingCandidate.targetNice}`}
            </button>
          </div>
        </Dialog>
      )}

      {actionResult && (
        <article className="action-result" role="status" style={{ marginBottom: 20 }}>
          <h3>Action result · {actionResult.overallStatus}</h3>
          <small>Action ID: {actionResult.actionId}</small>
          {actionResult.targets.map(target => (
            <p key={target.pid}>
              PID {target.pid} · {target.status} · requested nice {target.requestedNice} · observed nice{' '}
              {target.observedNice ?? 'Unavailable'}
              {target.reason && ` · ${target.reason}`}
            </p>
          ))}
          <p className="muted">{actionResult.residualRaceDisclosure}</p>
        </article>
      )}

      {auditError && (
        <p className="notice alert-error" role="status">
          {auditError}
        </p>
      )}

      {/* 4. Action Audit Trail Table */}
      {audits.length > 0 && (
        <div className="cyber-card" style={{ padding: 20 }}>
          <div className="card-header">
            <div>
              <div className="eyebrow">MUTATION AUDIT TRAIL</div>
              <h3>Recorded Priority Adjustments</h3>
            </div>
            <Provenance kind="RECORDED" />
          </div>
          <div className="tablewrap">
            <table className="data-table-terminal" aria-label="Priority action audit">
              <thead>
                <tr>
                  <th>Timestamp</th>
                  <th>Action ID</th>
                  <th>Target PID / Role</th>
                  <th>Original Nice</th>
                  <th>Requested Nice</th>
                  <th>Observed Nice</th>
                  <th>Result Status</th>
                </tr>
              </thead>
              <tbody>
                {audits.map(a => (
                  <tr key={`${a.id}:${a.targetPid}`}>
                    <td>{new Date(a.createdAt).toLocaleTimeString()}</td>
                    <td>
                      <code>{a.id.slice(0, 8)}</code>
                    </td>
                    <td>
                      {a.targetPid} · {a.targetRole}
                    </td>
                    <td>{a.originalNice}</td>
                    <td>+{a.requestedNice}</td>
                    <td className="signal-value">{a.observedNice ?? 'Unavailable'}</td>
                    <td>
                      <span className="state-badge scope-tag-success">{a.status}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  );
}
