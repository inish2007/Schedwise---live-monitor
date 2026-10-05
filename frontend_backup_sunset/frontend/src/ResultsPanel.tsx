import { apiFetch } from './http';
import { useState, useEffect, useRef } from 'react';
import { Dialog, EmptyState, Provenance } from './ui';
import type { Experiment, ComparisonReport, SessionInfo } from './experiment-types';
import type { CaptureInfo } from './simulation-types';

interface Props {
  token: string;
  experiment: Experiment | null;
}

export function ResultsPanel({ token, experiment }: Props) {
  const [captures, setCaptures] = useState<CaptureInfo[]>([]);
  const [sessions, setSessions] = useState<SessionInfo[]>([]);
  const [selectedCaptureId, setSelectedCaptureId] = useState<string>('');
  const [inspectedSummary, setInspectedSummary] = useState<CaptureInfo | null>(null);
  const [comparison, setComparison] = useState<ComparisonReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const comparisonRequest = useRef(0);
  const headers = { 'X-CSRF-Token': token };

  useEffect(() => {
    comparisonRequest.current++;
    setCaptures([]);
    setSessions([]);
    setComparison(null);
    setSelectedCaptureId('');
    setInspectedSummary(null);
    if (!token) return;
    void loadCapturesAndSessions();
  }, [token]);

  useEffect(() => {
    const id = selectedCaptureId || experiment?.id;
    setComparison(null);
    if (id && token) void loadComparison(id);
  }, [token, experiment?.id, experiment?.summaries, selectedCaptureId]);

  async function loadCapturesAndSessions() {
    try {
      const [cRes, sRes] = await Promise.all([
        apiFetch('/api/captures', { headers }),
        apiFetch('/api/sessions', { headers }),
      ]);
      if (!cRes.ok || !sRes.ok) {
        throw new Error(`Archive unavailable (captures ${cRes.status}, sessions ${sRes.status})`);
      }
      if (cRes.ok) {
        const cData = (await cRes.json()) as CaptureInfo[];
        setCaptures(cData);
        if (cData.length > 0 && !selectedCaptureId && !experiment?.id) {
          setSelectedCaptureId(cData[0].id);
        }
      }
      if (sRes.ok) {
        setSessions((await sRes.json()) as SessionInfo[]);
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Archive unavailable');
    }
  }

  async function loadComparison(id: string) {
    if (!token || !id) return;
    const request = ++comparisonRequest.current;
    setLoading(true);
    setError(null);
    try {
      const res = await apiFetch(`/api/experiments/${id}/comparison`, { headers });
      if (res.ok) {
        const data = (await res.json()) as ComparisonReport;
        if (request === comparisonRequest.current) setComparison(data);
      } else {
        const capRes = await apiFetch(`/api/captures/${id}/comparison`, { headers });
        if (capRes.ok) {
          const data = (await capRes.json()) as ComparisonReport;
          if (request === comparisonRequest.current) setComparison(data);
        } else {
          if (request === comparisonRequest.current) setComparison(null);
        }
      }
    } catch (e) {
      if (request === comparisonRequest.current) setError(e instanceof Error ? e.message : 'Failed to load comparison');
    } finally {
      if (request === comparisonRequest.current) setLoading(false);
    }
  }

  async function downloadExport(id: string) {
    if (!token || !id) return;
    try {
      const res = await apiFetch(`/api/sessions/${id}/export`, { headers });
      if (!res.ok) throw new Error(`Export failed (${res.status})`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `schedwise-${id}.zip`;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Download failed');
    }
  }

  async function inspectCapture(id: string) {
    setSelectedCaptureId(id);
    setLoading(true);
    try {
      const res = await apiFetch(`/api/captures/${id}`, { headers });
      if (res.ok) {
        setInspectedSummary((await res.json()) as CaptureInfo);
      } else throw new Error(`Capture unavailable (${res.status})`);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Capture metadata unavailable');
    } finally {
      setLoading(false);
    }
  }

  const activeId = selectedCaptureId || experiment?.id;
  const isCurrentExperiment = !!experiment && activeId === experiment.id;
  const isLiveExperiment = isCurrentExperiment && !experiment.finished;
  const summaries = isCurrentExperiment ? experiment?.summaries : undefined;

  function renderMetric(
    m: { value: number | null; unit: string; availability: string; reason: string | null } | undefined,
    percent = false
  ) {
    if (!m || m.value === null) {
      return <span className="muted">{m?.reason ? `Unavailable (${m.reason})` : 'Unavailable'}</span>;
    }
    const valStr = percent ? (m.value * 100).toFixed(2) + '%' : m.value.toFixed(2);
    return (
      <span>
        {valStr} {m.unit && !percent ? m.unit : ''}
      </span>
    );
  }

  return (
    <section className="results-view panel-root" aria-label="Measured Validation and Results">
      {/* 1. Tactical Results Hero Banner (No Double Header) */}
      <div className="experiment-hero-banner">
        <div>
          <div className="scope-badge-group">
            <span className="scope-tag scope-tag-primary">EMPIRICAL VALIDATION</span>
            <span className="scope-tag">STRICT COMPARABILITY RULES</span>
            <span className="scope-tag scope-tag-success">UNBIASED REPORTING</span>
          </div>
          <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--cyber-text-primary)' }}>
            Compare observed service latency and background throughput across <strong>Baseline</strong>,{' '}
            <strong>Contention</strong>, and <strong>After Priority Change</strong>. Negative, unchanged, and
            incomplete outcomes are reported honestly.
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Provenance kind={isLiveExperiment ? 'MEASURED' : 'RECORDED'} />
          <span className={`defense-status-chip ${isLiveExperiment ? 'active' : ''}`} role="status">
            {isLiveExperiment ? 'LIVE RUN' : activeId ? 'RECORDED' : 'UNAVAILABLE'}
          </span>
        </div>
      </div>

      <div className="results-source" style={{ margin: '0 0 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span className="muted" style={{ font: '11px var(--mono)' }}>
          Active Evidence Source: <strong>{activeId ?? 'No capture selected'}</strong>
          {loading ? ' · Loading evaluation…' : ''}
        </span>
        {selectedCaptureId && experiment && (
          <button className="secondary" onClick={() => setSelectedCaptureId('')} style={{ minHeight: 32 }}>
            Switch to Active Lab Experiment
          </button>
        )}
      </div>

      {!isCurrentExperiment && activeId && (
        <p className="notice" style={{ marginBottom: 16 }}>
          Archived comparison selected. Individual phase summaries are available in the capture archive; live run measurements are not substituted.
        </p>
      )}
      {error && (
        <p className="notice alert-error" role="alert">
          {error}
        </p>
      )}

      {/* 2. Scientific Comparability Engine & Tradeoff Pods */}
      {comparison && (
        <article className={`comparability-card ${comparison.comparable ? 'valid-comparison' : 'invalid-comparison'}`}>
          <div className="card-header" style={{ marginBottom: 12 }}>
            <div>
              <div className="eyebrow">COMPARABILITY EVALUATION</div>
              <h3 style={{ margin: 0 }}>
                Protocol Status:{' '}
                <span className={`badge ${comparison.comparable ? 'scope-tag-success' : 'alert-warning'}`}>
                  {comparison.status}
                </span>
              </h3>
            </div>
            <span className="muted">
              {comparison.hasAfterAction ? 'All 3 measurement phases completed' : 'After-action phase pending'}
            </span>
          </div>

          {comparison.comparable ? (
            <div className="tradeoff-results-grid">
              <div className="tradeoff-pod highlight">
                <span>Service Latency (p95)</span>
                <strong style={{ color: 'var(--sunset-accent)' }}>
                  {comparison.latencyP95ImprovementPercent != null
                    ? `${comparison.latencyP95ImprovementPercent >= 0 ? '↓ ' : '↑ '}${Math.abs(
                        comparison.latencyP95ImprovementPercent
                      ).toFixed(1)}%`
                    : '—'}
                </strong>
                <small>
                  {comparison.latencyP95ImprovementPercent != null && comparison.latencyP95ImprovementPercent >= 0
                    ? 'Measured p95 reduction between comparable phases'
                    : 'Unchanged or worse latency'}
                </small>
              </div>

              <div className="tradeoff-pod">
                <span>Latency Gap Recovery</span>
                <strong>
                  {comparison.latencyRecoveryPercent != null
                    ? `${comparison.latencyRecoveryPercent.toFixed(1)}%`
                    : '—'}
                </strong>
                <small>Recovery towards unconstrained baseline latency</small>
              </div>

              <div className="tradeoff-pod">
                <span>Background Work Tradeoff</span>
                <strong style={{ color: 'var(--cyber-violet)' }}>
                  {comparison.throughputTradeoffPercent != null
                    ? `${comparison.throughputTradeoffPercent >= 0 ? '+' : ''}${comparison.throughputTradeoffPercent.toFixed(
                        1
                      )}%`
                    : '—'}
                </strong>
                <small>Change in background hashing throughput</small>
              </div>

              <div className="tradeoff-pod">
                <span>Deadline Misses Avoided</span>
                <strong style={{ color: '#00ff9d' }}>
                  {comparison.deadlineMissReduction != null ? comparison.deadlineMissReduction : '—'}
                </strong>
                <small>Reduction in misses of the configured deadline</small>
              </div>
            </div>
          ) : (
            <div className="comparability-rejection" style={{ marginTop: 12 }}>
              <h4 style={{ color: '#ff3366' }}>Direct Performance Comparison Suppressed</h4>
              <p className="muted">
                To prevent false or misleading optimization claims, percentage gain calculations are suppressed:
              </p>
              <ul>
                {comparison.invalidReasons.map((r, i) => (
                  <li key={i} style={{ color: 'var(--cyber-text-secondary)', fontSize: 12 }}>
                    {r}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div style={{ marginTop: 16 }}>
            <details>
              <summary>Methodological Disclosures & Experimental Constraints</summary>
              <ul style={{ paddingLeft: 18, margin: '8px 0', fontSize: 12 }}>
                {comparison.disclosures.map((d, i) => (
                  <li key={i} className="muted">
                    {d}
                  </li>
                ))}
              </ul>
            </details>
          </div>
        </article>
      )}

      {/* 3. Three-Way Measurement Comparison Table */}
      <article className="cyber-card" style={{ padding: 20, marginBottom: 20 }}>
        <div className="card-header">
          <div>
            <div className="eyebrow">RAW MEASUREMENTS</div>
            <h3>Observed Metrics Across Workflow Phases</h3>
          </div>
          <Provenance kind={isLiveExperiment ? 'MEASURED' : 'RECORDED'} />
        </div>
        <div className="tablewrap">
          <table className="data-table-terminal" aria-label="Phase comparison table">
            <thead>
              <tr>
                <th>Measurement Field</th>
                <th>Baseline <small>(Service Only)</small></th>
                <th>Contention <small>(Compete @ Nice 0)</small></th>
                <th>After Change <small>(Recorded Nice +10)</small></th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td><strong>Scheduled Requests</strong></td>
                <td>{summaries?.BASELINE?.scheduledCount ?? '—'}</td>
                <td>{summaries?.CONTENTION?.scheduledCount ?? '—'}</td>
                <td>{summaries?.AFTER_ACTION?.scheduledCount ?? <span className="muted">Not recorded</span>}</td>
              </tr>
              <tr>
                <td><strong>Successful Cohort Requests</strong></td>
                <td>{summaries?.BASELINE?.successCount ?? '—'}</td>
                <td>{summaries?.CONTENTION?.successCount ?? '—'}</td>
                <td>{summaries?.AFTER_ACTION?.successCount ?? <span className="muted">Not recorded</span>}</td>
              </tr>
              <tr>
                <td><strong>HTTP Latency p50 (ms)</strong></td>
                <td className="signal-value">{renderMetric(summaries?.BASELINE?.p50Ms)}</td>
                <td className="signal-value">{renderMetric(summaries?.CONTENTION?.p50Ms)}</td>
                <td className="signal-value">{renderMetric(summaries?.AFTER_ACTION?.p50Ms)}</td>
              </tr>
              <tr style={{ background: 'rgba(255, 151, 125, 0.05)' }}>
                <td><strong>HTTP Latency p95 (ms)</strong></td>
                <td className="signal-value" style={{ color: 'var(--sunset-accent)' }}>
                  {renderMetric(summaries?.BASELINE?.p95Ms)}
                </td>
                <td className="signal-value" style={{ color: '#ff3366' }}>
                  {renderMetric(summaries?.CONTENTION?.p95Ms)}
                </td>
                <td className="signal-value" style={{ color: '#00ff9d' }}>
                  {renderMetric(summaries?.AFTER_ACTION?.p95Ms)}
                </td>
              </tr>
              <tr>
                <td><strong>HTTP Latency p99 (ms) <small>(&ge; 100 samples)</small></strong></td>
                <td className="signal-value">{renderMetric(summaries?.BASELINE?.p99Ms)}</td>
                <td className="signal-value">{renderMetric(summaries?.CONTENTION?.p99Ms)}</td>
                <td className="signal-value">{renderMetric(summaries?.AFTER_ACTION?.p99Ms)}</td>
              </tr>
              <tr>
                <td><strong>Dispatch Delay p95 (ms)</strong></td>
                <td className="signal-value">{renderMetric(summaries?.BASELINE?.dispatchDelayP95Ms)}</td>
                <td className="signal-value">{renderMetric(summaries?.CONTENTION?.dispatchDelayP95Ms)}</td>
                <td className="signal-value">{renderMetric(summaries?.AFTER_ACTION?.dispatchDelayP95Ms)}</td>
              </tr>
              <tr>
                <td><strong>Offered Rate (requests/s)</strong></td>
                <td className="signal-value">{renderMetric(summaries?.BASELINE?.offeredRateHz)}</td>
                <td className="signal-value">{renderMetric(summaries?.CONTENTION?.offeredRateHz)}</td>
                <td className="signal-value">{renderMetric(summaries?.AFTER_ACTION?.offeredRateHz)}</td>
              </tr>
              <tr>
                <td><strong>Errors / Timeouts</strong></td>
                <td>
                  {summaries?.BASELINE ? `${summaries.BASELINE.errorCount} / ${summaries.BASELINE.timeoutCount}` : '—'}
                </td>
                <td>
                  {summaries?.CONTENTION
                    ? `${summaries.CONTENTION.errorCount} / ${summaries.CONTENTION.timeoutCount}`
                    : '—'}
                </td>
                <td>
                  {summaries?.AFTER_ACTION
                    ? `${summaries.AFTER_ACTION.errorCount} / ${summaries.AFTER_ACTION.timeoutCount}`
                    : <span className="muted">Not recorded</span>}
                </td>
              </tr>
              <tr>
                <td><strong>Deadline Misses</strong></td>
                <td>
                  {summaries?.BASELINE ? (
                    <>
                      {summaries.BASELINE.deadlineMissCount} (
                      {renderMetric(summaries.BASELINE.deadlineMissRate, true)})
                    </>
                  ) : (
                    '—'
                  )}
                </td>
                <td>
                  {summaries?.CONTENTION ? (
                    <>
                      {summaries.CONTENTION.deadlineMissCount} (
                      {renderMetric(summaries.CONTENTION.deadlineMissRate, true)})
                    </>
                  ) : (
                    '—'
                  )}
                </td>
                <td>
                  {summaries?.AFTER_ACTION ? (
                    <>
                      {summaries.AFTER_ACTION.deadlineMissCount} (
                      {renderMetric(summaries.AFTER_ACTION.deadlineMissRate, true)})
                    </>
                  ) : (
                    <span className="muted">Not recorded</span>
                  )}
                </td>
              </tr>
              <tr>
                <td><strong>Background Hashes/s</strong></td>
                <td>
                  <span className="muted">{summaries?.BASELINE ? 'Service-only phase' : 'Unavailable'}</span>
                </td>
                <td>
                  {isCurrentExperiment && experiment?.workers ? (
                    <span>
                      {experiment.workers
                        .map(
                          w =>
                            `${w.role.replace('BACKGROUND_', 'W')}: ${
                              w.hashesPerSecond?.value != null
                                ? Math.round(w.hashesPerSecond.value).toLocaleString()
                                : '—'
                            } h/s`
                        )
                        .join(', ')}
                    </span>
                  ) : (
                    '—'
                  )}
                </td>
                <td>
                  {isCurrentExperiment && experiment?.workers ? (
                    <span>
                      {experiment.workers
                        .map(w => {
                          const rate = w.hashesByPhase?.AFTER_ACTION?.value;
                          return `${w.role.replace('BACKGROUND_', 'W')}: ${
                            rate != null ? Math.round(rate).toLocaleString() : '—'
                          } h/s`;
                        })
                        .join(', ')}
                    </span>
                  ) : (
                    <span className="muted">Not recorded</span>
                  )}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      {/* 4. Session Archive & Capture Explorer */}
      <article className="cyber-card" style={{ padding: 20 }}>
        <div className="card-header">
          <div>
            <div className="eyebrow">ARCHIVE REPOSITORY</div>
            <h3>Captured Sessions & Evidence ({sessions.length})</h3>
          </div>
          <button className="secondary" disabled={!token} onClick={() => void loadCapturesAndSessions()}>
            Refresh Archive
          </button>
        </div>
        <p className="muted" style={{ fontSize: 13, marginBottom: 16 }}>
          Historical captures recorded from genuine Linux trials. All records preserve provenance,
          parameters, and raw request journals.
        </p>

        {captures.length === 0 && (
          <EmptyState title="No captures loaded">
            Recorded trials appear here after a real experiment. Connect a session to load the archive.
          </EmptyState>
        )}

        {captures.length > 0 && (
          <div className="tablewrap">
            <table className="data-table-terminal" aria-label="Captured sessions table">
              <thead>
                <tr>
                  <th>Capture ID / Timestamp</th>
                  <th>Status</th>
                  <th>Core</th>
                  <th>Requests</th>
                  <th>Sufficiency Quality</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {captures.map(c => (
                  <tr key={c.id} className={selectedCaptureId === c.id ? 'selected-row' : ''}>
                    <td>
                      <code className="pid-link">{c.id.substring(0, 8)}…</code>
                      <small className="muted" style={{ display: 'block', marginTop: 4 }}>
                        {c.testedAt}
                      </small>
                    </td>
                    <td>
                      <span className={`state-badge ${c.state === 'COMPLETED' ? 'state-RUNNING' : ''}`}>{c.state}</span>
                    </td>
                    <td>Core {c.core ?? '?'}</td>
                    <td>{c.rawRequestCount}</td>
                    <td>
                      <span className={`badge ${c.sufficiency === 'SUFFICIENT' ? 'scope-tag-success' : 'alert-warning'}`}>
                        {c.sufficiency}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: 8 }}>
                        <button className="secondary" onClick={() => inspectCapture(c.id)} style={{ minHeight: 30, padding: '4px 10px' }}>
                          Inspect
                        </button>
                        <button className="secondary" onClick={() => downloadExport(c.id)} style={{ minHeight: 30, padding: '4px 10px' }}>
                          ZIP
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </article>

      {/* Capture Inspector Dialog */}
      {inspectedSummary && (
        <Dialog title="Recorded Capture Details" drawer onClose={() => setInspectedSummary(null)}>
          <dl className="identity-grid">
            <dt>Capture ID</dt>
            <dd><code>{inspectedSummary.id}</code></dd>
            <dt>Original Timestamp</dt>
            <dd>{inspectedSummary.testedAt}</dd>
            <dt>State</dt>
            <dd>{inspectedSummary.state}</dd>
            <dt>Quality / Sufficiency</dt>
            <dd>{inspectedSummary.sufficiency}</dd>
            <dt>Requests / Samples</dt>
            <dd>{inspectedSummary.rawRequestCount} requests / {inspectedSummary.sampleCount} CPU samples</dd>
          </dl>
          <p style={{ marginTop: 12 }}>{inspectedSummary.notes}</p>
          <button className="primary" onClick={() => void downloadExport(inspectedSummary.id)} style={{ width: '100%', marginTop: 16 }}>
            Download Recorded Evidence (ZIP)
          </button>
        </Dialog>
      )}
    </section>
  );
}
