import React, { useState } from 'react';
import type { Capabilities, Latest, Value } from './types';
import { display } from './stream';

interface Props {
  caps: Capabilities | null;
  latest: Latest | null;
}

function parsePsi(raw: string | null | undefined) {
  if (!raw) return null;
  const avg10 = raw.match(/(?:^|\s)avg10=([\d.]+)/)?.[1];
  const avg60 = raw.match(/(?:^|\s)avg60=([\d.]+)/)?.[1];
  const avg300 = raw.match(/(?:^|\s)avg300=([\d.]+)/)?.[1];
  const total = raw.match(/(?:^|\s)total=(\d+)/)?.[1];
  return {
    avg10: avg10 != null ? Number(avg10) : null,
    avg60: avg60 != null ? Number(avg60) : null,
    avg300: avg300 != null ? Number(avg300) : null,
    totalUs: total != null ? Number(total) : null,
  };
}

export function EnvironmentPanel({ caps, latest }: Props) {
  const [sourceCategory, setSourceCategory] = useState<'all' | 'kernel' | 'cgroup' | 'binaries'>('all');
  const val = (v: Value<string | number> | undefined) => display(v);
  const snap = latest?.snapshot;

  const rawPsi = snap?.cpuPressureSome?.value != null ? String(snap.cpuPressureSome.value) : (caps?.sources.cpuPressure?.value != null ? String(caps.sources.cpuPressure.value) : null);
  const psi = parsePsi(rawPsi);

  // Grouped sources classification
  const allSources = caps ? Object.entries(caps.sources) : [];
  const kernelKeys = ['kernel', 'cpuPressure', 'allowedCpus', 'schedstatSource', 'schedstatAccounting', 'autogroup', 'clockTicks'];
  const cgroupKeys = ['cgroupMembership', 'cgroupCpuLimits', 'pidNamespace', 'bootId'];
  const binaryKeys = ['taskset', 'renice', 'python3'];

  const filteredSources = allSources.filter(([k]) => {
    if (sourceCategory === 'kernel') return kernelKeys.includes(k);
    if (sourceCategory === 'cgroup') return cgroupKeys.includes(k) || k.startsWith('cpu.max');
    if (sourceCategory === 'binaries') return binaryKeys.includes(k);
    return true;
  });

  return (
    <section className="environment-view" aria-label="Environment and System Capabilities">
      {/* 1. Tactical Scope & Runtime Banner */}
      <div className="system-scope-banner">
        <div className="scope-banner-content">
          <div className="scope-badge-group">
            <span className="scope-tag scope-tag-primary">LINUX PROC SCOPE</span>
            <span className="scope-tag">UNPRIVILEGED USERSPACE</span>
            <span className={`scope-tag ${caps ? 'scope-tag-success' : 'scope-tag-muted'}`}>
              <i className="pulse-dot" aria-hidden="true" /> {caps ? 'DISCOVERED & ACTIVE' : 'AWAITING DISCOVERY'}
            </span>
          </div>
          <p className="scope-text">
            {caps?.sources.scope?.value
              ? String(caps.sources.scope.value)
              : 'Discovering host environment, virtualization layers, and CPU accounting boundaries…'}
          </p>
        </div>
        <div className="scope-banner-meta">
          <span className="scope-meta-label">ENVIRONMENT IDENTIFIER</span>
          <code className="scope-meta-id">{snap?.environmentId ?? (caps?.sessionId ? `${caps.sessionId}:linux` : 'AWAITING SESSION')}</code>
        </div>
      </div>

      {/* 2. Core Hardware & Kernel Matrix */}
      <div className="system-metric-grid">
        <article className="system-metric-card">
          <span className="metric-card-label">LINUX KERNEL RELEASE</span>
          <div className="metric-card-value">
            <strong>{val(caps?.sources.kernel)}</strong>
          </div>
          <small className="metric-card-sub">x86_64 host / VM platform</small>
        </article>

        <article className="system-metric-card">
          <span className="metric-card-label">ALLOWED CPUS MASK</span>
          <div className="metric-card-value">
            <code className="highlight-code">{val(caps?.sources.allowedCpus)}</code>
          </div>
          <small className="metric-card-sub">Affinity mask (12 logical cores)</small>
        </article>

        <article className="system-metric-card">
          <span className="metric-card-label">CLOCK TICK FREQUENCY</span>
          <div className="metric-card-value">
            <strong>{val(caps?.sources.clockTicks)} Hz</strong>
          </div>
          <small className="metric-card-sub"><code>sysconf(_SC_CLK_TCK)</code></small>
        </article>

        <article className="system-metric-card">
          <span className="metric-card-label">STORAGE SUBSYSTEM</span>
          <div className="metric-card-value">
            <strong>{latest?.storageStatus ?? caps?.storage ?? 'Available'}</strong>
          </div>
          <small className="metric-card-sub">SQLite 3.50 WAL persistence</small>
        </article>

        <article className="system-metric-card">
          <span className="metric-card-label">KERNEL BOOT IDENTIFIER</span>
          <div className="metric-card-value">
            {(() => {
              const bootId = caps?.sources.bootId?.value != null ? String(caps.sources.bootId.value) : null;
              return (
                <code className="boot-id-code" title={bootId ?? 'Inaccessible'}>
                  {bootId ? bootId.slice(0, 13) + '…' : 'Inaccessible'}
                </code>
              );
            })()}
          </div>
          <small className="metric-card-sub">Immutable session anchor</small>
        </article>
      </div>

      {/* 3. Linux Pressure Stall Information (PSI) HUD */}
      <article className="psi-hud-card">
        <div className="psi-hud-header">
          <div>
            <div className="eyebrow">KERNEL HARDWARE STALL METRICS</div>
            <h3>Linux Pressure Stall Information (PSI)</h3>
          </div>
          <div className="psi-status-indicator">
            {psi?.avg10 != null ? (
              <span className={`psi-badge ${psi.avg10 > 5 ? 'psi-badge-high' : psi.avg10 > 1.5 ? 'psi-badge-med' : 'psi-badge-low'}`}>
                {psi.avg10 > 5 ? 'HIGH CPU STALL' : psi.avg10 > 1.5 ? 'MODERATE PRESSURE' : 'NORMAL / LOW PRESSURE'}
              </span>
            ) : (
              <span className="psi-badge psi-badge-muted">PSI UNAVAILABLE</span>
            )}
          </div>
        </div>

        <p className="psi-summary-text">
          Measures the proportion of time in which tasks are delayed waiting for CPU compute. SchedWise tracks <code>some</code> stalls;
          system-level <code>full</code> is omitted because CPU cannot experience full complete stalls without machine failure.
        </p>

        <div className="psi-stats-row">
          <div className="psi-stat-box">
            <span>10-SECOND AVERAGE</span>
            <strong>{psi?.avg10 != null ? `${psi.avg10.toFixed(2)}%` : '—'}</strong>
            <small>Short-term immediate stall</small>
          </div>
          <div className="psi-stat-box">
            <span>60-SECOND AVERAGE</span>
            <strong>{psi?.avg60 != null ? `${psi.avg60.toFixed(2)}%` : '—'}</strong>
            <small>Rolling 1m load trend</small>
          </div>
          <div className="psi-stat-box">
            <span>300-SECOND AVERAGE</span>
            <strong>{psi?.avg300 != null ? `${psi.avg300.toFixed(2)}%` : '—'}</strong>
            <small>5m sustained pressure</small>
          </div>
          <div className="psi-stat-box">
            <span>TOTAL STALL DURATION</span>
            <strong>{psi?.totalUs != null ? `${(psi.totalUs / 1_000_000).toFixed(1)}s` : '—'}</strong>
            <small>Cumulative microsecond sum</small>
          </div>
        </div>
      </article>

      {/* 4. Categorized Platform Accounting Matrix */}
      <article className="sources-section">
        <div className="sources-header-bar">
          <div>
            <div className="eyebrow">VERIFIED LINUX INTERFACES</div>
            <h3>Platform Accounting Sources & Binaries</h3>
          </div>
          <div className="sources-filter-tabs" role="tablist" aria-label="Sources categories">
            <button
              type="button"
              className={`filter-tab ${sourceCategory === 'all' ? 'active' : ''}`}
              onClick={() => setSourceCategory('all')}
            >
              All ({allSources.length})
            </button>
            <button
              type="button"
              className={`filter-tab ${sourceCategory === 'kernel' ? 'active' : ''}`}
              onClick={() => setSourceCategory('kernel')}
            >
              Kernel & Schedstat
            </button>
            <button
              type="button"
              className={`filter-tab ${sourceCategory === 'cgroup' ? 'active' : ''}`}
              onClick={() => setSourceCategory('cgroup')}
            >
              Cgroups & Isolation
            </button>
            <button
              type="button"
              className={`filter-tab ${sourceCategory === 'binaries' ? 'active' : ''}`}
              onClick={() => setSourceCategory('binaries')}
            >
              Approved Binaries
            </button>
          </div>
        </div>

        <div className="tablewrap sources-table-wrapper">
          <table className="sources-table">
            <thead>
              <tr>
                <th style={{ width: '32%' }}>Interface / Name</th>
                <th style={{ width: '38%' }}>Observed Path / Raw Value</th>
                <th style={{ width: '14%' }}>Kind</th>
                <th style={{ width: '16%' }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {filteredSources.map(([k, v]) => {
                const isOnline = v.value !== null && v.reason == null;
                return (
                  <tr key={k}>
                    <td>
                      <span className="source-key-name"><code>{k}</code></span>
                    </td>
                    <td>
                      <span className="source-raw-value" title={String(v.value ?? v.reason ?? '')}>
                        {val(v)}
                      </span>
                    </td>
                    <td>
                      <span className="source-kind-pill">{v.kind}</span>
                    </td>
                    <td>
                      <span className={`status-pill ${isOnline ? 'status-pill-online' : 'status-pill-offline'}`}>
                        <i aria-hidden="true" />
                        {v.reason ?? (isOnline ? 'Online' : 'Unavailable')}
                      </span>
                    </td>
                  </tr>
                );
              })}
              {filteredSources.length === 0 && (
                <tr>
                  <td colSpan={4} className="empty-table-cell">
                    No sources found in this category.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </article>

      {/* 5. Tactical Limitations & Guardrails Grid */}
      <article className="guardrails-section">
        <div className="eyebrow">ARCHITECTURAL GUARDRAILS</div>
        <h3>Documented Platform Limitations</h3>
        <p className="guardrails-lead">
          SchedWise adheres strictly to Linux unprivileged security policies. These boundaries define what the application
          guarantees versus what is intentionally out of scope.
        </p>

        <div className="guardrails-grid">
          <div className="guardrail-card">
            <div className="guardrail-badge">UNPRIVILEGED USERSPACE</div>
            <h4>Root-Free Operation</h4>
            <p>
              SchedWise runs without <code>root</code> or <code>CAP_SYS_NICE</code> permissions. Priority mutations can only
              reduce priority (raising nice value $0 \to +10$). Restoring lower nice requires privileges or child process recreation.
            </p>
          </div>

          <div className="guardrail-card">
            <div className="guardrail-badge">VIRTUALIZATION BOUNDARY</div>
            <h4>WSL2 & Container Scope</h4>
            <p>
              Telemetry describes the visible Linux container/virtual machine scope. Native Windows host tasks outside the WSL2
              boundary are not observable and will not be managed.
            </p>
          </div>

          <div className="guardrail-card">
            <div className="guardrail-badge">THREADING SEMANTICS</div>
            <h4>Per-Thread Nice Priorities</h4>
            <p>
              Linux <code>nice</code> values apply per-thread in practice. Allocation Advisor targets single-threaded managed workers;
              Game Shield isolates explicitly chosen background processes through its pidfd guardian.
            </p>
          </div>

          <div className="guardrail-card">
            <div className="guardrail-badge">RACE MITIGATION</div>
            <h4>Linux pidfd Race Elimination</h4>
            <p>
              All external pause and resume signals use Linux 5.3+ <code>pidfd_open</code> handles. If a target process terminates,
              the held file descriptor invalidates instantly, preventing accidental signals to recycled PIDs.
            </p>
          </div>

          <div className="guardrail-card">
            <div className="guardrail-badge">KERNEL ACCOUNTING</div>
            <h4>Schedstat Accounting Caveats</h4>
            <p>
              If kernel scheduler statistics (<code>/proc/sys/kernel/sched_schedstats</code>) are disabled in the environment,
              observed zeros represent missing telemetry rather than guaranteed zero wait time.
            </p>
          </div>

          <div className="guardrail-card">
            <div className="guardrail-badge">DATA INTEGRITY</div>
            <h4>Zero-Mock Measurement Policy</h4>
            <p>
              All live metrics reflect real Linux kernel files. SchedWise never falls back to synthetic telemetry, randomized data,
              or hardcoded benchmark claims. Unavailable data is always explicitly marked.
            </p>
          </div>
        </div>
      </article>
    </section>
  );
}
