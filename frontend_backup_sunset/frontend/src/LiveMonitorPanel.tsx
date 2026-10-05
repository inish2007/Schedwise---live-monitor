import { useId, useState } from 'react';
import type { Identity, Latest, Value } from './types';
import { display } from './stream';
import { cpuPath, identityKey, psiAverage } from './telemetry-view';
import type { CpuPoint } from './telemetry-view';
import { Dialog, EmptyState, Provenance } from './ui';

interface Props {
  latest: Latest | null;
  connection: string;
  age: number;
  live: boolean;
  cpuHistory: CpuPoint[];
}

export function LiveMonitorPanel({ latest, connection, age, live, cpuHistory }: Props) {
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<Identity | null>(null);
  const gradient = useId();
  const snap = latest?.snapshot;
  const val = (value: Value<string | number> | undefined) => display(value);
  const processes = snap?.processes ?? [];
  const rows = processes
    .filter(p => `${p.identity.pid} ${p.name.value ?? ''}`.toLowerCase().includes(query.toLowerCase()))
    .sort((a, b) => (b.cpuPercent.value ?? -1) - (a.cpuPercent.value ?? -1));
  const selectedProcess = selected ? processes.find(p => identityKey(p.identity) === identityKey(selected)) : undefined;
  const machineCpu = snap?.cpus.cpu?.busyPercent.value;
  const pressure = psiAverage(snap?.cpuPressureSome.value);
  const path = cpuPath(cpuHistory);
  const latestPoint = cpuHistory.at(-1);
  const elapsed = cpuHistory.length > 1 ? (Date.parse(cpuHistory.at(-1)!.timestamp) - Date.parse(cpuHistory[0].timestamp)) / 1000 : 0;
  const cores = Object.entries(snap?.cpus ?? {}).filter(([id]) => id !== 'cpu');

  return (
    <section className="live-monitor-view panel-root" aria-label="Live system telemetry">
      {/* 1. Tactical Telemetry HUD Tiles */}
      <div className="telemetry-hud-grid">
        <article className={`metric-hud-tile ${machineCpu != null && machineCpu >= 85 ? 'warning' : ''}`}>
          <div className="metric-hud-heading">
            <span className="metric-hud-label">MACHINE UTILIZATION</span>
            <Provenance kind="DERIVED" />
          </div>
          <div className="metric-hud-body">
            <strong>{machineCpu != null ? machineCpu.toFixed(1) : '—'}</strong>
            <span className="metric-hud-unit">{machineCpu != null ? '%' : ''}</span>
          </div>
          <small className="metric-hud-sub">Visible capacity · idle/steal excluded</small>
        </article>

        <article className="metric-hud-tile">
          <div className="metric-hud-heading">
            <span className="metric-hud-label">CPU PRESSURE (PSI)</span>
            <Provenance kind="MEASURED" />
          </div>
          <div className="metric-hud-body">
            <strong>{pressure !== null ? pressure.toFixed(2) : '—'}</strong>
            <span className="metric-hud-unit">{pressure !== null ? '%' : ''}</span>
          </div>
          <small className="metric-hud-sub">PSI some · observed avg10</small>
        </article>

        <article className="metric-hud-tile">
          <div className="metric-hud-heading">
            <span className="metric-hud-label">ACTIVE PROCESS COHORT</span>
            <Provenance kind="MEASURED" />
          </div>
          <div className="metric-hud-body">
            <strong>{snap ? processes.filter(p => p.lifecycle === 'PRESENT').length : '—'}</strong>
            <span className="metric-hud-unit">{snap ? 'PIDs' : ''}</span>
          </div>
          <small className="metric-hud-sub">100% usage = one logical CPU</small>
        </article>

        <article className={`metric-hud-tile ${snap && !live ? 'warning' : ''}`}>
          <div className="metric-hud-heading">
            <span className="metric-hud-label">SAMPLE FRESHNESS</span>
            <span className={`signal-badge ${live ? 'live' : ''}`}>
              <i aria-hidden="true" />
              {live ? 'LIVE' : snap ? 'STALE' : 'WAITING'}
            </span>
          </div>
          <div className="metric-hud-body">
            <strong>{age >= 0 ? (age / 1000).toFixed(1) : '—'}</strong>
            <span className="metric-hud-unit">{age >= 0 ? 's' : ''}</span>
          </div>
          <small className="metric-hud-sub">
            {connection} · {snap ? `seq ${snap.sequence}` : 'no samples'}
          </small>
        </article>
      </div>

      {/* 2. Enhanced Sparkline Card */}
      <article className="history-card cyber-card" aria-label="Measured-input machine CPU history">
        <div className="card-header">
          <div>
            <div className="eyebrow">TIME-SERIES TELEMETRY</div>
            <h3>Real-Time Machine Activity</h3>
          </div>
          <div className="chart-readout">
            <Provenance kind="DERIVED" />
            <strong className="readout-highlight">{machineCpu != null ? machineCpu.toFixed(1) + '%' : 'Unavailable'}</strong>
          </div>
        </div>

        {cpuHistory.some(p => p.value !== null) ? (
          <div className={`sparkline-container ${!live ? 'stale-chart' : ''}`}>
            <div className="chart-axis">
              <span>100%</span>
              <span>50%</span>
              <span>0%</span>
            </div>
            <svg
              viewBox="0 0 1000 160"
              preserveAspectRatio="none"
              className="sparkline"
              role="img"
              aria-label={`Actual CPU observations over ${elapsed.toFixed(1)} seconds; gaps are not interpolated`}
            >
              <defs>
                <linearGradient id={gradient} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--sunset-accent)" stopOpacity=".22" />
                  <stop offset="100%" stopColor="var(--sunset-accent)" stopOpacity="0" />
                </linearGradient>
              </defs>
              <rect x="0" y="16" width="1000" height="128" fill={`url(#${gradient})`} />
              {[16, 48, 80, 112, 144].map(y => (
                <line key={y} x1="0" x2="1000" y1={y} y2={y} className="chart-grid" />
              ))}
              {[0, 200, 400, 600, 800, 1000].map(x => (
                <line key={x} x1={x} x2={x} y1="16" y2="144" className="chart-grid vertical" />
              ))}
              <path d={path} fill="none" stroke="var(--sunset-accent)" strokeWidth="2.2" vectorEffect="non-scaling-stroke" />
              {latestPoint?.value != null && (
                <circle
                  cx="998"
                  cy={144 - Math.min(100, Math.max(0, latestPoint.value)) * 1.28}
                  r="4"
                  fill="var(--sunset-accent)"
                  className="sparkline-endpoint"
                />
              )}
            </svg>
            <div className="sparkline-labels">
              <span>{cpuHistory.length} verified samples / {elapsed.toFixed(1)}s window</span>
              <span>{live ? 'Live stream' : 'Last received observation · stale'}</span>
            </div>
          </div>
        ) : (
          <EmptyState title="Awaiting a CPU signal">
            Connect a session to see collected observations. No samples are backfilled.
          </EmptyState>
        )}
      </article>

      {/* 3. 12-Core Hardware Processors Grid */}
      <section className="cyber-card cores-section">
        <div className="card-header">
          <div>
            <div className="eyebrow">PER-CORE TELEMETRY</div>
            <h3>Logical Processors Breakdown</h3>
          </div>
          <span className="metadata-chip">{snap ? `${cores.length} VISIBLE CORES` : '—'}</span>
        </div>

        <div className="core-grid">
          {cores.map(([id, cpu]) => {
            const busy = cpu.busyPercent.value;
            const steal = cpu.stealPercent.value;
            const isHigh = busy != null && busy >= 85;
            return (
              <article key={id} className={`core-tactical-pod ${isHigh ? 'warning' : ''}`}>
                <div className="core-pod-header">
                  <span className="core-pod-title">{id.replace('cpu', 'CPU ')}</span>
                  <strong className="core-pod-value">{busy != null ? `${busy.toFixed(1)}%` : '—'}</strong>
                </div>
                <div className="core-pod-meter-bg">
                  <div
                    className={`core-pod-meter-bar ${isHigh ? 'meter-high' : 'meter-normal'}`}
                    style={{ width: `${Math.min(100, Math.max(0, busy ?? 0))}%` }}
                  />
                </div>
                <div className="core-pod-footer">
                  <small>{busy == null ? 'Unavailable' : `Steal ${steal != null ? `${steal.toFixed(1)}%` : '0.0%'}`}</small>
                </div>
              </article>
            );
          })}
        </div>
        {!snap && (
          <EmptyState title="No core observations">
            Core utilization will appear after authentication and collection.
          </EmptyState>
        )}
      </section>

      {/* 4. Process Inspector Section */}
      <section className="cyber-card process-section">
        <div className="card-header">
          <div>
            <div className="eyebrow">PROCESS INSPECTOR</div>
            <h3>Observed Workload Cohort</h3>
          </div>
          <label className="search-field">
            <span className="sr-only">Filter processes by name or PID</span>
            <input placeholder="Search process name or PID…" value={query} onChange={e => setQuery(e.target.value)} />
            <kbd>/ PID</kbd>
          </label>
        </div>

        {snap && (
          <div className="table-meta">
            <span>{snap.availability}{snap.reason ? ` · ${snap.reason}` : ''}</span>
            <span>
              {snap.unreadableProcesses} unreadable · {snap.omittedProcesses ? 'scan cap reached' : 'within scan cap'} · {snap.collectionMillis.toFixed(1)} ms scan
            </span>
          </div>
        )}

        <div className="tablewrap">
          <table className="data-table-terminal" aria-label="Process observations">
            <thead>
              <tr>
                {['PID / Identity', 'Process', 'State', 'UID', 'CPU %', 'Nice · Leader', 'Threads', 'Affinity · Leader', 'Lifecycle'].map(h => (
                  <th key={h}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map(p => (
                <tr
                  key={identityKey(p.identity)}
                  className={selected && identityKey(selected) === identityKey(p.identity) ? 'selected-row' : ''}
                >
                  <td>
                    <button className="pid-link" onClick={() => setSelected(p.identity)} aria-label={`Inspect PID ${p.identity.pid}`}>
                      {p.identity.pid}
                    </button>
                    <small>{p.identity.startTicks}</small>
                  </td>
                  <td>{val(p.name)}</td>
                  <td>
                    <span className={`state-badge state-${p.state.value ?? 'unknown'}`}>
                      {val(p.state)}
                    </span>
                  </td>
                  <td>{val(p.uid)}</td>
                  <td className="signal-value">{val(p.cpuPercent)}</td>
                  <td>
                    <span className="nice-badge">{val(p.nice)}</span>
                  </td>
                  <td>{val(p.threads)}</td>
                  <td><code>{val(p.allowedCpus)}</code></td>
                  <td>
                    <span className="lifecycle-tag">{p.lifecycle}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {!rows.length && (
          <EmptyState title={snap ? 'No matching processes' : 'Waiting for processes'}>
            {snap ? 'Try another name or PID.' : 'An authenticated Linux sample is required.'}
          </EmptyState>
        )}

        <details className="methodology">
          <summary>Units & observation limits</summary>
          <p>
            Machine utilization and individual core utilization use separate capacities. Process CPU can exceed 100% across threads. State samples are not kernel dispatch events. Nice and affinity describe the leader thread.
          </p>
        </details>
      </section>

      {/* 5. Process Detail Drawer */}
      {selected && (
        <Dialog title={`Process ${selected.pid}`} drawer onClose={() => setSelected(null)}>
          <Provenance kind="MEASURED" />
          <p>{selectedProcess ? val(selectedProcess.name) : 'This identity is no longer present in the latest snapshot.'}</p>
          <dl className="inspector-facts">
            <dt>Boot identity</dt>
            <dd>{selected.bootId}</dd>
            <dt>Start ticks</dt>
            <dd>{selected.startTicks}</dd>
            <dt>UID</dt>
            <dd>{val(selectedProcess?.uid)}</dd>
            <dt>CPU usage</dt>
            <dd>{val(selectedProcess?.cpuPercent)}</dd>
            <dt>Current nice · leader</dt>
            <dd>{val(selectedProcess?.nice)}</dd>
            <dt>Threads · process</dt>
            <dd>{val(selectedProcess?.threads)}</dd>
            <dt>Affinity · leader</dt>
            <dd>{val(selectedProcess?.allowedCpus)}</dd>
            <dt>Role</dt>
            <dd>{selectedProcess?.role ?? 'Unavailable'}</dd>
            <dt>Lifecycle</dt>
            <dd>{selectedProcess?.lifecycle ?? 'Not observed'}</dd>
          </dl>
          <p className="muted">
            Values follow the full boot/PID/start-ticks identity. {live ? 'Latest received sample.' : 'These observations are stale.'}
          </p>
        </Dialog>
      )}
    </section>
  );
}
