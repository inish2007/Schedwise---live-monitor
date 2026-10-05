import { apiFetch } from './http';
import { useEffect, useState } from 'react';
import { Provenance, EmptyState } from './ui';
import { validTimeline } from './telemetry-view';
import type { CaptureInfo, SimulationResponse, AlgorithmResult } from './simulation-types';

export function SimulationPanel({ token }: { token: string }) {
  const [refresh, setRefresh] = useState(0);
  const [captures, setCaptures] = useState<CaptureInfo[]>([]);
  const [selectedCapture, setSelectedCapture] = useState<string>('');
  const [phase, setPhase] = useState<'CONTENTION' | 'BASELINE'>('CONTENTION');
  const [quantumMs, setQuantumMs] = useState(20);
  const [cfsLatencyMs, setCfsLatencyMs] = useState(24);
  const [cfsMinGranMs, setCfsMinGranMs] = useState(3);
  const [bgNice, setBgNice] = useState(5);
  const [horizonSec, setHorizonSec] = useState(30);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [simulation, setSimulation] = useState<SimulationResponse | null>(null);
  const [selectedAlgo, setSelectedAlgo] = useState<string>('CFS');

  useEffect(() => {
    setSimulation(null);
  }, [selectedCapture, phase, quantumMs, cfsLatencyMs, cfsMinGranMs, bgNice, horizonSec]);

  useEffect(() => {
    if (!token) {
      setCaptures([]);
      setSimulation(null);
      setSelectedCapture('');
      return;
    }
    apiFetch('/api/captures', { headers: { 'X-CSRF-Token': token } })
      .then(r => {
        if (!r.ok) throw new Error(`Captures unavailable (${r.status})`);
        return r.json();
      })
      .then((data: CaptureInfo[]) => {
        setCaptures(data);
        if (data.length > 0 && !selectedCapture) {
          setSelectedCapture(data[0].id);
        }
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Captures unavailable'));
  }, [token, refresh]);

  async function runSimulation() {
    if (!token || !selectedCapture) return;
    setBusy(true);
    setError('');
    try {
      const body = {
        captureId: selectedCapture,
        phase,
        models: ['FCFS', 'ROUND_ROBIN', 'PRIORITY', 'SJF', 'CFS'],
        quantumNs: Math.round(quantumMs * 1e6),
        cfsLatencyTargetNs: Math.round(cfsLatencyMs * 1e6),
        cfsMinGranularityNs: Math.round(cfsMinGranMs * 1e6),
        backgroundNice: bgNice,
        horizonNs: Math.round(horizonSec * 1e9),
        maxEvents: 20000,
      };
      const res = await apiFetch('/api/simulations', {
        method: 'POST',
        headers: { 'X-CSRF-Token': token, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const errData = (await res.json()) as { error?: string };
        throw new Error(errData.error ?? `Simulation failed (${res.status})`);
      }
      const data = (await res.json()) as SimulationResponse;
      setSimulation(data);
      if (data.results && !data.results[selectedAlgo]) {
        const firstKey = Object.keys(data.results)[0];
        if (firstKey) setSelectedAlgo(firstKey);
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Simulation request failed');
    } finally {
      setBusy(false);
    }
  }

  const activeCapture = captures.find(c => c.id === selectedCapture);
  const results = simulation?.results ?? {};
  const currentResult: AlgorithmResult | undefined = results[selectedAlgo];

  return (
    <section className="simulation-panel panel-root" aria-label="What-If CPU Scheduling Simulator">
      {/* 1. Tactical Simulation Hero Banner (No Double Header) */}
      <div className="experiment-hero-banner">
        <div>
          <div className="scope-badge-group">
            <span className="scope-tag scope-tag-primary">DISCRETE EVENT SIMULATION</span>
            <span className="scope-tag">MEASURED-INPUT TRACE DRIVEN</span>
            <span className="scope-tag scope-tag-success">5 DSA SCHEDULERS</span>
          </div>
          <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--cyber-text-primary)' }}>
            Compare pure-Java scheduling models driven by actual captured trace inputs.
            Outputs are <strong>simulations with documented mathematical assumptions</strong>, not live Linux kernel traces.
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Provenance kind="SIMULATED" />
          <button className="secondary" disabled={!token || busy} onClick={() => setRefresh(v => v + 1)} style={{ minHeight: 36 }}>
            Refresh Captures
          </button>
        </div>
      </div>

      {/* 2. Capture Selector & Parameter Controls */}
      <div className="shield-config-card" style={{ marginBottom: 20 }}>
        <div className="shield-config-row">
          <div className="shield-config-field" style={{ flex: '2 1 280px' }}>
            <label>Source Capture</label>
            <select
              value={selectedCapture}
              onChange={e => setSelectedCapture(e.target.value)}
              disabled={busy || captures.length === 0}
            >
              {captures.length === 0 && <option value="">No recorded captures available</option>}
              {captures.map(c => (
                <option key={c.id} value={c.id}>
                  {c.id.substring(0, 8)}… ({c.testedAt.substring(0, 19)} · Core {c.core ?? '?'})
                </option>
              ))}
            </select>
          </div>

          <div className="shield-config-field" style={{ flex: '1 1 180px' }}>
            <label>Workload Phase</label>
            <select value={phase} onChange={e => setPhase(e.target.value as 'CONTENTION' | 'BASELINE')} disabled={busy}>
              <option value="CONTENTION">Contention (Service + Workers)</option>
              <option value="BASELINE">Baseline (Protected Service Only)</option>
            </select>
          </div>

          <div className="shield-config-field" style={{ flex: '1 1 120px' }}>
            <label>RR Quantum (ms)</label>
            <input
              type="number"
              min="1"
              max="500"
              value={quantumMs}
              onChange={e => setQuantumMs(Number(e.target.value))}
              disabled={busy}
            />
          </div>

          <div className="shield-config-field" style={{ flex: '1 1 140px' }}>
            <label>CFS Latency Target</label>
            <input
              type="number"
              min="5"
              max="500"
              value={cfsLatencyMs}
              onChange={e => setCfsLatencyMs(Number(e.target.value))}
              disabled={busy}
            />
          </div>

          <div className="shield-config-field" style={{ flex: '1 1 150px' }}>
            <label>Candidate Nice</label>
            <select value={bgNice} onChange={e => setBgNice(Number(e.target.value))} disabled={busy}>
              <option value="0">Nice 0 (Weight 1024)</option>
              <option value="5">Nice 5 (Weight 335)</option>
              <option value="10">Nice 10 (Weight 110)</option>
              <option value="15">Nice 15 (Weight 36)</option>
            </select>
          </div>
        </div>

        <div className="shield-config-row" style={{ alignItems: 'center' }}>
          <div className="shield-config-field" style={{ flex: '1 1 140px' }}>
            <label>CFS Min Slice (ms)</label>
            <input
              type="number"
              min="1"
              max="500"
              value={cfsMinGranMs}
              onChange={e => setCfsMinGranMs(Number(e.target.value))}
              disabled={busy}
            />
          </div>

          <div className="shield-config-field" style={{ flex: '1 1 140px' }}>
            <label>Horizon Window (s)</label>
            <input
              type="number"
              min="1"
              max="180"
              value={horizonSec}
              onChange={e => setHorizonSec(Number(e.target.value))}
              disabled={busy}
            />
          </div>

          <button
            className="primary"
            style={{ minHeight: 38, marginTop: 18 }}
            disabled={
              !token ||
              !selectedCapture ||
              busy ||
              quantumMs <= 0 ||
              cfsLatencyMs <= 0 ||
              cfsMinGranMs <= 0 ||
              horizonSec <= 0
            }
            onClick={() => void runSimulation()}
          >
            {busy ? 'Simulating…' : 'Run What-If Simulation'}
          </button>
        </div>

        {activeCapture && (
          <div className="table-meta" style={{ marginTop: 12 }}>
            <span>Status: <strong>{activeCapture.state}</strong> · Quality: <strong>{activeCapture.sufficiency}</strong></span>
            <span>Recorded: <strong>{activeCapture.rawRequestCount} requests</strong> ({activeCapture.sampleCount} CPU samples)</span>
          </div>
        )}
      </div>

      {error && (
        <p className="notice alert-error" role="alert">
          {error}
        </p>
      )}

      {simulation?.sufficiency === 'INSUFFICIENT' && (
        <p className="notice alert-warning">
          Simulation could not run: {simulation.qualityNotes}. This capture does not contain sufficient valid request events.
        </p>
      )}

      {!simulation && (
        <EmptyState title="Model a real capture">
          Select a recorded experiment run and evaluate 5 classic and fair scheduling algorithms. Pure in-memory discrete event models.
        </EmptyState>
      )}

      {Object.keys(results).length > 0 && (
        <>
          {/* 3. Algorithm Cards Deck */}
          <div className="algorithm-deck" aria-label="Scheduling model selector">
            {Object.entries(results).map(([key, result]) => {
              const isSelected = selectedAlgo === key;
              return (
                <div
                  key={key}
                  className={`algo-card ${isSelected ? 'active' : ''}`}
                  onClick={() => setSelectedAlgo(key)}
                  role="button"
                  tabIndex={0}
                  onKeyDown={e => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      setSelectedAlgo(key);
                    }
                  }}
                >
                  <div className="algo-card-header">
                    <span className="algo-card-title">{result.algorithm}</span>
                    <span className="complexity-badge">{result.complexity}</span>
                  </div>
                  <div className="algo-stats">
                    <small>{result.dataStructure}</small>
                    <div style={{ marginTop: 6, font: '11px var(--mono)', color: 'var(--sunset-accent)' }}>
                      p95: {result.metrics.p95ResponseTimeMs != null ? `${result.metrics.p95ResponseTimeMs.toFixed(1)}ms` : '—'}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* 4. Model Comparison Metrics Table */}
          <div className="cyber-card" style={{ padding: 20, marginBottom: 20 }}>
            <div className="card-header">
              <div>
                <div className="eyebrow">METRIC COMPARISON</div>
                <h3>Model Performance Matrix</h3>
              </div>
              <Provenance kind="SIMULATED" />
            </div>
            <div className="tablewrap">
              <table className="data-table-terminal" aria-label="Model Comparison">
                <thead>
                  <tr>
                    <th>Algorithm</th>
                    <th>Data Structure</th>
                    <th>Completed / Censored</th>
                    <th>Response p50 (ms)</th>
                    <th>Response p95 (ms)</th>
                    <th>Wait p50 (ms)</th>
                    <th>Turnaround p50 (ms)</th>
                    <th>CPU Util (%)</th>
                    <th>Fairness (Jain)</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(results).map(([key, res]) => {
                    const m = res.metrics;
                    return (
                      <tr key={key} className={selectedAlgo === key ? 'selected-row' : ''}>
                        <td>
                          <strong>{res.algorithm}</strong>
                        </td>
                        <td>
                          <small>{res.dataStructure}</small>
                        </td>
                        <td>
                          {m.completedJobs} / {m.censoredJobs}
                        </td>
                        <td className="signal-value">{m.p50ResponseTimeMs != null ? m.p50ResponseTimeMs.toFixed(2) : '—'}</td>
                        <td className="signal-value" style={{ color: 'var(--sunset-accent)' }}>
                          {m.p95ResponseTimeMs != null ? m.p95ResponseTimeMs.toFixed(2) : '—'}
                        </td>
                        <td>{m.p50WaitingTimeMs != null ? m.p50WaitingTimeMs.toFixed(2) : '—'}</td>
                        <td>{m.p50TurnaroundTimeMs != null ? m.p50TurnaroundTimeMs.toFixed(2) : '—'}</td>
                        <td>{m.cpuUtilizationPercent != null ? `${m.cpuUtilizationPercent.toFixed(1)}%` : '—'}</td>
                        <td>{m.jainFairnessIndex != null ? m.jainFairnessIndex.toFixed(3) : '—'}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>

          {/* 5. Interactive Gantt Timeline */}
          {currentResult && (
            <div className="cyber-card" style={{ padding: 20 }}>
              <div className="card-header">
                <div>
                  <div className="eyebrow">SIMULATED GANTT TIMELINE · {currentResult.algorithm}</div>
                  <h3>Modeled Task Dispatch Slices</h3>
                </div>
                <small className="muted">{currentResult.timeline.length} modeled segments</small>
              </div>

              <div className="timeline-legend" style={{ margin: '8px 0 14px' }}>
                <span className="service">Protected Service</span>
                <span className="background-one">Background Worker 1</span>
                <span className="background-two">Background Worker 2</span>
                <span className="muted">Empty space = Idle CPU interval</span>
              </div>

              {currentResult.partialResult && <p className="notice">{currentResult.partialReason}</p>}

              {validTimeline(currentResult.timeline) ? (
                <>
                  <svg
                    className="gantt-timeline"
                    viewBox="0 0 1000 44"
                    preserveAspectRatio="none"
                    role="img"
                    aria-label={`${currentResult.algorithm} simulated execution timeline; first 200 segments`}
                  >
                    {currentResult.timeline.slice(0, 200).map((seg, idx) => {
                      const end = currentResult.timeline[Math.min(199, currentResult.timeline.length - 1)]?.endNs ?? 1;
                      return (
                        <rect
                          key={idx}
                          x={(seg.startNs / end) * 1000}
                          width={(seg.durationNs / end) * 1000}
                          y="4"
                          height="36"
                          fill={
                            seg.role === 'PROTECTED_SERVICE'
                              ? 'var(--sunset-accent)'
                              : seg.role === 'BACKGROUND_1'
                              ? 'var(--cyber-amber)'
                              : 'var(--cyber-violet)'
                          }
                        >
                          <title>
                            {seg.jobId} · {seg.role} · {(seg.startNs / 1e6).toFixed(2)}–{(seg.endNs / 1e6).toFixed(2)} ms ·{' '}
                            {seg.reason}
                          </title>
                        </rect>
                      );
                    })}
                  </svg>
                  <div className="sparkline-labels" style={{ marginTop: 6 }}>
                    <span>0 ms</span>
                    <span>
                      {((currentResult.timeline[Math.min(199, currentResult.timeline.length - 1)]?.endNs ?? 0) / 1e6).toFixed(
                        2
                      )}{' '}
                      ms
                    </span>
                  </div>
                  <p className="muted" style={{ fontSize: 11, marginTop: 8 }}>
                    Showing first {Math.min(200, currentResult.timeline.length)} of {currentResult.timeline.length} segments.
                    Positions reflect modeled timestamps; first-dispatch response is separate from measured HTTP latency.
                  </p>
                </>
              ) : (
                <p className="notice">Timeline unavailable: returned segments contain invalid durations or overlaps.</p>
              )}

              <details style={{ marginTop: 16 }}>
                <summary>Algorithm Details, Complexity & Model Limitations</summary>
                <div style={{ padding: '8px 0', fontSize: 13, lineHeight: 1.5 }}>
                  <p>
                    <strong>Complexity:</strong> {currentResult.complexity}
                  </p>
                  <p>
                    <strong>Underlying Data Structure:</strong> {currentResult.dataStructure}
                  </p>
                  <p>
                    <strong>Assumptions & Limits:</strong> {currentResult.assumptions}
                  </p>
                  <p className="muted">
                    <em>Notice:</em> Textbook Round Robin is modeled with discrete time quanta, distinct from Linux real-time{' '}
                    <code>SCHED_RR</code>. Simplified classic CFS implements red-black virtual runtime tracking with Linux
                    nice-to-weight mapping (historical CFS model, distinct from current Linux EEVDF).
                  </p>
                </div>
              </details>
            </div>
          )}
        </>
      )}
    </section>
  );
}
