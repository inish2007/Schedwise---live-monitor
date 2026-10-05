import { apiFetch } from './http';
import { useEffect, useState } from 'react';
import { cpuOptions, phaseDone } from './telemetry-view';
import { Provenance } from './ui';
import type { Experiment, ExperimentMetric, PhaseSummary } from './experiment-types';

export function metricText(metric: ExperimentMetric | undefined, percent = false): string {
  if (!metric || metric.value === null) return 'Unavailable';
  return `${(metric.value * (percent ? 100 : 1)).toFixed(2)}${percent ? '%' : ''}`;
}

export function ExperimentPanel({
  token,
  allowedCpus,
  experiment,
  onChange,
}: {
  token: string;
  allowedCpus: string | null;
  experiment: Experiment | null;
  onChange: (value: Experiment | null) => void;
}) {
  const [core, setCore] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!token) {
      onChange(null);
      return;
    }
    const controller = new AbortController();
    apiFetch('/api/experiments', { headers: { 'X-CSRF-Token': token }, signal: controller.signal })
      .then(async r => {
        if (!r.ok) throw new Error(`Experiment status (${r.status})`);
        onChange((await r.json()) as Experiment | null);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(e instanceof Error ? e.message : 'Status unavailable');
      });
    return () => controller.abort();
  }, [token, onChange]);

  async function action(path: string, body: object) {
    setBusy(true);
    setError('');
    try {
      const response = await apiFetch(path, {
        method: 'POST',
        headers: { 'X-CSRF-Token': token, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        const data = (await response.json()) as { error?: string };
        throw new Error(data.error ?? `Operation failed (${response.status})`);
      }
      onChange((await response.json()) as Experiment);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Request failed');
    } finally {
      setBusy(false);
    }
  }

  async function download() {
    if (!experiment) return;
    setBusy(true);
    setError('');
    try {
      const response = await apiFetch(`/api/sessions/${experiment.id}/export`, { headers: { 'X-CSRF-Token': token } });
      if (!response.ok) throw new Error(`Export failed (${response.status})`);
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a');
      link.href = url;
      link.download = `schedwise-${experiment.id}.zip`;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Export failed');
    } finally {
      setBusy(false);
    }
  }

  const active = !!experiment && !experiment.finished;
  const stale = active && now - experiment.timestamp / 1e6 > 3500;
  const parameters = experiment?.parameters;
  const summaryRows: [string, (s: PhaseSummary) => string][] = [
    ['Scheduled / successful', s => `${s.scheduledCount} / ${s.successCount}`],
    ['HTTP p95 · successful requests (ms)', s => metricText(s.p95Ms)],
    ['HTTP p99 · successful requests (ms)', s => (s.p99Ms ? metricText(s.p99Ms) : 'Unavailable')],
    ['HTTP p50 · successful requests (ms)', s => metricText(s.p50Ms)],
    ['Dispatch delay p95 (ms)', s => metricText(s.dispatchDelayP95Ms)],
    ['Observed offered rate (requests/s)', s => metricText(s.offeredRateHz)],
    ['Successful cohort rate (requests/s)', s => metricText(s.completedRateHz)],
    ['Errors / timeouts', s => `${s.errorCount} / ${s.timeoutCount}`],
    ['Skipped / missed dispatch', s => `${s.skippedCount} / ${s.missedDispatchCount}`],
    ['Deadline misses / scheduled', s => `${s.deadlineMissCount} · ${metricText(s.deadlineMissRate, true)}`],
  ];

  const phasesList = [
    'CALIBRATION',
    'WARMUP',
    'BASELINE',
    'TRANSITION',
    'CONTENTION',
    'AFTER_ACTION',
    'DRAINING',
    'FINISHED',
  ];

  return (
    <section className="experiment panel-root" aria-label="Real contention experiment">
      {/* 1. Tactical Experiment Hero Banner (No Double Header) */}
      <div className="experiment-hero-banner">
        <div>
          <div className="scope-badge-group">
            <span className="scope-tag scope-tag-primary">PINNED HARDWARE CORE</span>
            <span className="scope-tag">FROZEN WORKLOAD</span>
            <span className="scope-tag scope-tag-success">STRICT COMPARABILITY</span>
          </div>
          <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--cyber-text-primary)' }}>
            One hashing HTTP service and two finite hashing workers share one isolated CPU.
            Calibration freezes offered load; baseline, contention, and after-change phases record genuine measurements.
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <Provenance kind={experiment?.finished ? 'RECORDED' : 'MEASURED'} />
          <span
            className={`defense-status-chip ${active ? 'active' : ''}`}
            role="status"
          >
            {experiment ? `${experiment.state} · ${experiment.phase}${stale ? ' · STALE' : ''}` : 'NOT STARTED'}
          </span>
        </div>
      </div>

      {/* 2. Glowing 7-Stage Phase Pipeline Stepper */}
      <div className="phase-pipeline-stepper" aria-label="Experiment Phase Progression">
        {phasesList.map((p, idx) => {
          const isCurrent = experiment?.phase === p;
          const isDone = experiment ? phaseDone(p, experiment.phase, experiment.state, phasesList) : false;
          return (
            <div key={p} className={`phase-step-item ${isCurrent ? 'active' : ''} ${isDone ? 'done' : ''}`}>
              <span className="step-circle">{isDone ? '✓' : idx + 1}</span>
              <span className="step-label">{p.replace('_', ' ')}</span>
            </div>
          );
        })}
      </div>

      {/* 3. Controls Bar */}
      <div className="experiment-controls">
        <label>
          CPU Core
          <select
            aria-label="Experiment CPU"
            value={core}
            onChange={e => setCore(e.target.value)}
            disabled={active || busy}
          >
            <option value="">Auto · allowed core</option>
            {cpuOptions(allowedCpus).map(c => (
              <option key={c} value={c}>
                Core {c}
              </option>
            ))}
          </select>
        </label>
        <span className="muted" style={{ font: '11px var(--mono)', alignSelf: 'center' }}>
          Allowed: {allowedCpus ?? 'Unavailable'}
        </span>
        <button
          className="primary"
          disabled={!token || active || busy || !allowedCpus}
          onClick={() => void action('/api/experiments', core === '' ? {} : { core: Number(core) })}
        >
          Start Full Experiment Run
        </button>
        <button
          className="stop"
          disabled={!token || !active}
          onClick={() => {
            if (experiment) void action(`/api/experiments/${experiment.id}/stop`, {});
          }}
        >
          Emergency Stop
        </button>
        <button disabled={!token || !experiment?.exportAvailable || busy} onClick={() => void download()}>
          Export Real Capture
        </button>
      </div>

      {error && (
        <p className="notice alert-error" role="alert">
          {error}
        </p>
      )}
      {experiment?.stopRequested && active && (
        <p role="status" className="notice">
          Stopping owned children and retaining partial evidence…
        </p>
      )}
      {experiment?.reason && <p className="notice">{experiment.reason}</p>}
      {stale && (
        <p className="notice alert-warning">
          Experiment updates are stale. Stop remains available; measurements below are the last received observations.
        </p>
      )}
      {experiment?.observerLimitation && <p className="notice">{experiment.observerLimitation}</p>}

      {/* 4. Frozen Parameters HUD */}
      {parameters ? (
        <div className="workload-params-hud">
          <div className="param-pod">
            <span className="param-label">Work Budget</span>
            <span className="param-val">{parameters.iterations.toLocaleString()} SHA-256 ops</span>
          </div>
          <div className="param-pod">
            <span className="param-label">Offered Load</span>
            <span className="param-val">{parameters.rateHz} req/s</span>
          </div>
          <div className="param-pod">
            <span className="param-label">Deadline / Timeout</span>
            <span className="param-val">{parameters.deadlineMs}ms / {parameters.timeoutMs}ms</span>
          </div>
          <div className="param-pod">
            <span className="param-label">Core Assignment</span>
            <span className="param-val">Core {experiment.core} (Obs: {experiment.observerCpus.join(', ')})</span>
          </div>
        </div>
      ) : (
        <p className="muted" style={{ margin: '12px 0 20px', font: '12px var(--mono)' }}>
          Work measurements and comparison results become available once the real experiment runs.
        </p>
      )}

      {/* 5. Real-Time Latency Readout Pods */}
      {experiment?.summaries?.[experiment.phase] && (
        <div className="telemetry-hud-grid" style={{ marginBottom: 20 }}>
          {(['p50Ms', 'p95Ms', 'p99Ms'] as const).map(key => (
            <article className="metric-hud-tile" key={key}>
              <div className="metric-hud-heading">
                <span className="metric-hud-label">{experiment.phase.replaceAll('_', ' ')} · HTTP {key.replace('Ms', '')}</span>
                <Provenance kind="MEASURED" />
              </div>
              <div className="metric-hud-body">
                <strong>{metricText(experiment.summaries![experiment.phase][key])}</strong>
                <span className="metric-hud-unit">ms</span>
              </div>
              <small className="metric-hud-sub">
                {experiment.summaries![experiment.phase].successCount} successful responses
              </small>
            </article>
          ))}
        </div>
      )}

      {/* 6. Measured Phase Comparison Matrix Table */}
      <div className="cyber-card" style={{ padding: 20, marginBottom: 20 }}>
        <div className="card-header">
          <div>
            <div className="eyebrow">PHASE OBSERVATIONS</div>
            <h3>Measured Phase Comparison Matrix</h3>
          </div>
          <Provenance kind={experiment?.finished ? 'RECORDED' : 'MEASURED'} />
        </div>
        <div className="tablewrap">
          <table className="data-table-terminal" aria-label="Measured phase comparison">
            <thead>
              <tr>
                <th>Observed Measure</th>
                <th>Baseline</th>
                <th>Contention</th>
                <th>After Change</th>
              </tr>
            </thead>
            <tbody>
              {summaryRows.map(([label, render]) => (
                <tr key={label}>
                  <td><strong>{label}</strong></td>
                  {['BASELINE', 'CONTENTION', 'AFTER_ACTION'].map(phase => (
                    <td key={phase} className="signal-value">
                      {experiment?.summaries?.[phase] ? (
                        render(experiment.summaries[phase])
                      ) : (
                        <span className="muted">Unavailable</span>
                      )}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="muted" style={{ marginTop: 12, fontSize: 11 }}>
          p95 uses nearest rank and requires 20 successes; p99 requires 100 successes. Errors, skipped requests, and
          deadline misses remain separate; no priority changes or predicted gains are applied automatically.
        </p>
      </div>

      {/* 7. Managed Workers Grid */}
      {experiment?.workers && (
        <div className="worker-grid" style={{ marginBottom: 20 }}>
          {experiment.workers.map(worker => {
            const afterRate = worker.hashesByPhase?.AFTER_ACTION?.value;
            return (
              <article key={worker.role} className="metric-hud-tile">
                <div className="metric-hud-heading">
                  <span className="metric-hud-label">{worker.role.replace('_', ' ')} · PID {worker.identity.pid}</span>
                  <span className={`state-badge ${worker.alive ? 'state-RUNNING' : 'state-SLEEPING'}`}>
                    {worker.alive ? 'RUNNING' : 'EXITED'}
                  </span>
                </div>
                <div style={{ margin: '8px 0' }}>
                  <p style={{ margin: 0, font: '11px var(--mono)' }}>
                    Nice: <strong>{worker.observed?.nice ?? 'Unavailable'}</strong> · Affinity: {worker.observed?.allowedCpus.join(', ') ?? 'Unavailable'}
                  </p>
                  <p style={{ margin: '4px 0', font: '12px var(--mono)' }}>
                    <strong>{worker.progress ? worker.progress.hashes.toLocaleString() : '—'}</strong> hashes completed
                  </p>
                </div>
                <small className="metric-hud-sub">
                  {metricText(worker.hashesPerSecond)} hashes/s (Contention)
                  {afterRate != null && ` → ${Math.round(afterRate).toLocaleString()} hashes/s (After Change)`}
                </small>
              </article>
            );
          })}
        </div>
      )}

      {experiment?.finished && (
        <p className={`notice ${experiment.cleanup?.verified && !experiment.coordinatorAlive ? 'scope-tag-success' : 'alert-warning'}`}>
          Cleanup:{' '}
          {experiment.cleanup?.verified && !experiment.coordinatorAlive
            ? 'Verified; all registered child workloads exited cleanly.'
            : 'Inspect audit; cleanup is not fully verified.'}
        </p>
      )}

      {experiment && (
        <details style={{ marginTop: 16 }}>
          <summary>Experiment identity, grouping, and validity metadata</summary>
          <div style={{ padding: 12, background: 'var(--cyber-surface-2)', marginTop: 8, borderRadius: 4, font: '11px var(--mono)' }}>
            <p>
              Session: {experiment.id} · {experiment.elapsedSeconds?.toFixed(1) ?? '—'} elapsed seconds · Cohort scope:{' '}
              {experiment.contentionCohortVerified ? 'Verified' : 'Not yet verified'}
            </p>
            {experiment.children?.map(child => (
              <p key={child.identity.pid} style={{ margin: '4px 0' }}>
                {child.role}: PID {child.identity.pid}, start ticks {child.identity.startTicks}, UID{' '}
                {child.observed?.uid ?? 'Unavailable'}, threads {child.observed?.threads ?? 'Unavailable'}, cgroup{' '}
                {child.observed?.cgroup ?? 'Unavailable'}, autogroup {child.observed?.autogroup ?? 'Unavailable'}
              </p>
            ))}
          </div>
        </details>
      )}
    </section>
  );
}
