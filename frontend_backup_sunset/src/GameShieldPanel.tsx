import { useEffect, useRef, useState } from 'react';
import { Dialog, EmptyState, Provenance } from './ui';
import { apiFetch, apiError } from './http';
import { identityKey, psiAverage } from './telemetry-view';
import type { ShieldCandidate, ShieldState } from './gameshield-types';
import type { Latest } from './types';

type Section = 'overview' | 'applications' | 'activity';

const sectionFromUrl = (): Section => {
  const part = location.hash.split('/')[2];
  if (part === 'applications' || part === 'apps') return 'applications';
  if (part === 'activity' || part === 'recovery') return 'activity';
  return 'overview';
};

export function GameShieldPanel({ token, latest, age }: { token: string; latest: Latest | null; age: number }) {
  const [section, setSection] = useState<Section>(sectionFromUrl);
  const [status, setStatus] = useState<ShieldState | null>(null);
  const [candidates, setCandidates] = useState<ShieldCandidate[]>([]);
  const [target, setTarget] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [advanced, setAdvanced] = useState(false);
  const [minutes, setMinutes] = useState(15);
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');
  const [statusError, setStatusError] = useState('');
  const [busy, setBusy] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [scannedAt, setScannedAt] = useState(0);
  const [now, setNow] = useState(Date.now());
  const [owner] = useState(() => crypto.randomUUID());

  const owned = useRef<string | null>(null);
  const pendingOperation = useRef<string | null>(null);
  const generation = useRef(0);

  const [pending, setPending] = useState<{
    target: ShieldCandidate;
    workers: ShieldCandidate[];
    operationId: string;
    seconds: number;
  } | null>(null);

  const headers = { 'X-CSRF-Token': token, 'Content-Type': 'application/json' };
  const targetApp = candidates.find(c => identityKey(c.identity) === target);
  const workers = candidates.filter(c => selected.includes(identityKey(c.identity)));
  const blocked = busy || !!status?.active || !!statusError || !token || !status?.guardianAvailable;
  const sample = (candidate: ShieldCandidate) =>
    latest?.snapshot?.processes.find(p => identityKey(p.identity) === identityKey(candidate.identity));
  const machineCpu = latest?.snapshot?.cpus.cpu?.busyPercent.value;
  const psiSome = psiAverage(latest?.snapshot?.cpuPressureSome?.value);
  const isStale = age > 3000;
  const hasTelemetry = latest?.snapshot != null && !isStale;
  const isContention = hasTelemetry && ((machineCpu != null && machineCpu >= 85) || (psiSome != null && psiSome >= 15));

  function navigate(next: Section) {
    location.hash = `/gameshield/${next}`;
    setSection(next);
  }

  function accept(value: ShieldState) {
    setStatus(value);
    setStatusError('');
    if (value.operationId === pendingOperation.current && value.active) {
      owned.current = value.operationId;
    }
    if (!value.active) {
      owned.current = null;
    }
  }

  async function refreshStatus() {
    const version = generation.current;
    try {
      const res = await apiFetch('/api/gameshield/status', { headers });
      if (!res.ok) throw new Error(await apiError(res));
      const value = (await res.json()) as ShieldState;
      if (version === generation.current) accept(value);
    } catch (e) {
      if (version === generation.current) {
        setStatusError(e instanceof Error ? e.message : 'Shield state unavailable');
      }
    }
  }

  async function scan() {
    const version = generation.current;
    setScanning(true);
    setError('');
    try {
      const res = await apiFetch('/api/gameshield/candidates', { headers });
      if (!res.ok) throw new Error(await apiError(res));
      const values = (await res.json()) as ShieldCandidate[];
      if (version !== generation.current) return;
      setCandidates(values);
      setScannedAt(Date.now());
      setSelected(keys => keys.filter(key => values.some(c => identityKey(c.identity) === key)));
    } catch (e) {
      if (version === generation.current) {
        setError(e instanceof Error ? e.message : 'Scan unavailable');
      }
    } finally {
      if (version === generation.current) setScanning(false);
    }
  }

  useEffect(() => {
    const change = () => setSection(sectionFromUrl());
    window.addEventListener('hashchange', change);
    return () => window.removeEventListener('hashchange', change);
  }, []);

  useEffect(() => {
    generation.current++;
    setStatus(null);
    setCandidates([]);
    setTarget('');
    setSelected([]);
    setPending(null);
    owned.current = null;
    if (!token) return;

    let stopped = false;
    async function poll() {
      await refreshStatus();
      if (!stopped) timer = setTimeout(() => void poll(), 2000);
    }
    let timer: ReturnType<typeof setTimeout>;
    void poll();
    void scan();
    const clock = setInterval(() => setNow(Date.now()), 1000);
    return () => {
      stopped = true;
      generation.current++;
      clearTimeout(timer);
      clearInterval(clock);
    };
  }, [token]);

  useEffect(() => {
    if (!token) return;
    const interval = setInterval(() => {
      if (owned.current) {
        void apiFetch('/api/gameshield/heartbeat', {
          method: 'POST',
          headers,
          body: JSON.stringify({ sessionId: owned.current, ownerId: owner }),
        })
          .then(res => {
            if (!res.ok) {
              throw new Error('Dashboard lease could not renew; restoration will run automatically');
            }
          })
          .catch(e => setStatusError(e instanceof Error ? e.message : 'Lease unavailable'));
      }
    }, 15000);
    return () => clearInterval(interval);
  }, [token, owner]);

  useEffect(() => {
    if (!status?.active) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    const exit = () => {
      if (owned.current) {
        void apiFetch('/api/gameshield/deactivate', {
          method: 'POST',
          headers,
          keepalive: true,
          body: JSON.stringify({ sessionId: owned.current, operationId: crypto.randomUUID() }),
        }).catch(() => {
          /* Guardian lease is authoritative. */
        });
      }
    };
    window.addEventListener('beforeunload', warn);
    window.addEventListener('pagehide', exit);
    return () => {
      window.removeEventListener('beforeunload', warn);
      window.removeEventListener('pagehide', exit);
    };
  }, [status?.active, token]);

  async function activate() {
    if (!pending) return;
    setBusy(true);
    setError('');
    pendingOperation.current = pending.operationId;
    try {
      const res = await apiFetch('/api/gameshield/activate', {
        method: 'POST',
        headers,
        body: JSON.stringify({
          targetIdentity: pending.target.identity,
          targets: pending.workers.map(c => ({ identity: c.identity, expectedNice: c.nice })),
          maxSeconds: pending.seconds,
          operationId: pending.operationId,
          ownerId: owner,
        }),
      });
      if (!res.ok) throw new Error(await apiError(res));
      accept((await res.json()) as ShieldState);
      setPending(null);
      navigate('overview');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Activation failed');
      await refreshStatus();
    } finally {
      setBusy(false);
    }
  }

  async function stop() {
    if (!status?.operationId) {
      await refreshStatus();
      return;
    }
    setBusy(true);
    setError('');
    try {
      const res = await apiFetch('/api/gameshield/deactivate', {
        method: 'POST',
        headers,
        body: JSON.stringify({ sessionId: status.operationId, operationId: crypto.randomUUID() }),
      });
      if (!res.ok) throw new Error(await apiError(res));
      accept((await res.json()) as ShieldState);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Restoration request failed');
      await refreshStatus();
    } finally {
      setBusy(false);
    }
  }

  const filtered = candidates.filter(
    c => !search || `${c.name} ${c.pid}`.toLowerCase().includes(search.toLowerCase())
  );
  const stateName = statusError ? 'State unavailable' : status?.status.replaceAll('_', ' ') ?? 'Connecting';

  return (
    <section className="gameshield-panel panel-root" aria-label="Game Shield">
      {/* 1. Tactical Defense Status Hero Banner (No Double Header) */}
      <div className="defense-status-banner">
        <div className="defense-banner-left">
          <div className="defense-tag-cluster">
            <span className="scope-tag scope-tag-primary">
              <i className="pulse-dot" aria-hidden="true" /> {status?.active ? 'SHIELD ENGAGED' : 'INSPECT MODE'}
            </span>
            <span className="scope-tag">LINUX PIDFD SUPERVISED</span>
            <span className={`scope-tag ${status?.guardianAvailable ? 'scope-tag-success' : 'scope-tag-muted'}`}>
              {status?.guardianAvailable ? 'GUARDIAN ARMED' : 'GUARDIAN UNAVAILABLE'}
            </span>
          </div>
          <p className="defense-target-info">
            Protected Application:{' '}
            {targetApp ? (
              <strong>
                {targetApp.name} <code style={{ color: 'var(--sunset-accent)' }}>PID {targetApp.pid}</code>
              </strong>
            ) : (
              <em className="muted">None selected · choose in Applications tab</em>
            )}
            <span className="defense-target-sub">
              Unprivileged SIGSTOP isolation with independent Python guardian and 60s lease auto-recovery
            </span>
          </p>
        </div>

        <div className="defense-banner-right">
          <div className="defense-control-row">
            <span className={`defense-status-chip ${status?.active ? 'active' : ''}`} role="status">
              {busy ? 'TRANSITIONING…' : stateName}
            </span>
            {status?.active ? (
              <button className="primary stop" disabled={busy || !token} onClick={() => void stop()}>
                {status.status === 'NEEDS_ATTENTION' ? 'Retry restoration' : 'Turn Off · Restore Apps'}
              </button>
            ) : (
              <button
                className="primary"
                disabled={
                  blocked ||
                  !advanced ||
                  !targetApp ||
                  workers.length === 0 ||
                  !Number.isInteger(minutes) ||
                  minutes < 1 ||
                  minutes > 30
                }
                onClick={() => {
                  setError('');
                  setPending({
                    target: targetApp!,
                    workers,
                    operationId: crypto.randomUUID(),
                    seconds: minutes * 60,
                  });
                }}
              >
                Review & Turn On
              </button>
            )}
          </div>
          <p className="defense-countdown">
            {status?.status === 'ON'
              ? `${Math.max(0, Math.ceil((status.expiresAtEpochMs - now) / 1000))}s remaining · auto unpause active`
              : 'Off is verified only after confirmed SIGCONT restoration.'}
          </p>
        </div>
      </div>

      {/* 2. Subtab Navigation (Clean Pills) */}
      <div className="subtabs" role="tablist" aria-label="Shield sections">
        {(['overview', 'applications', 'activity'] as Section[]).map((key, index) => (
          <button
            key={key}
            id={`shield-tab-${key}`}
            role="tab"
            aria-selected={section === key}
            aria-controls={`shield-${key}`}
            tabIndex={section === key ? 0 : -1}
            onClick={() => navigate(key)}
            onKeyDown={e => {
              const keys: Section[] = ['overview', 'applications', 'activity'];
              const next =
                e.key === 'ArrowRight'
                  ? (index + 1) % 3
                  : e.key === 'ArrowLeft'
                  ? (index + 2) % 3
                  : e.key === 'Home'
                  ? 0
                  : e.key === 'End'
                  ? 2
                  : -1;
              if (next >= 0) {
                e.preventDefault();
                navigate(keys[next]);
                document.getElementById(`shield-tab-${keys[next]}`)?.focus();
              }
            }}
          >
            {key === 'activity' ? 'Activity & Recovery' : key === 'overview' ? 'Overview' : 'Applications'}
          </button>
        ))}
      </div>

      {statusError && (
        <p role="alert" className="notice alert-error">
          {statusError}. Status is unknown; Off has not been confirmed.{' '}
          <button className="text-button" onClick={() => void refreshStatus()}>
            Check state
          </button>
        </p>
      )}
      {error && !pending && (
        <p className="notice alert-error" role="alert">
          {error}
        </p>
      )}
      {status?.failures.map((f, i) => (
        <p className="notice alert-error" role="alert" key={i}>
          {f.code}: {f.message}
          {f.identity ? ` · PID ${f.identity.pid}` : ''}
        </p>
      ))}

      {/* Tab 1: Overview */}
      <div id="shield-overview" role="tabpanel" aria-labelledby="shield-tab-overview" hidden={section !== 'overview'}>
        <div className="shield-overview-grid">
          {/* Card 1: Contention Radar */}
          <article className="shield-overview-card">
            <div>
              <div className="card-header" style={{ marginBottom: 8, paddingBottom: 0, border: 'none' }}>
                <div className="eyebrow">RESOURCE OBSERVATION</div>
                <Provenance kind="DERIVED" />
              </div>
              <h3>Contention Diagnosis</h3>
              <div className="radar-metric-grid">
                <div className="radar-pod">
                  <span className="radar-pod-label">Machine CPU</span>
                  <span className="radar-pod-value">{machineCpu != null ? `${machineCpu.toFixed(1)}%` : '—'}</span>
                </div>
                <div className="radar-pod">
                  <span className="radar-pod-label">PSI (some avg10)</span>
                  <span className="radar-pod-value">{psiSome != null ? `${psiSome.toFixed(2)}%` : '—'}</span>
                </div>
              </div>
              <div className={`radar-diagnosis-box ${isContention ? 'warning' : ''}`}>
                {hasTelemetry ? (
                  isContention ? (
                    <span>
                      <strong>High Contention Observed:</strong> Background activity may compete with interactive tasks.
                    </span>
                  ) : (
                    <span>
                      <strong>Headroom Available:</strong> Machine CPU utilization is stable. Background tasks are not
                      saturating core capacity.
                    </span>
                  )
                ) : (
                  <span className="muted">
                    <strong>Evidence Insufficient:</strong> Telemetry is {isStale ? 'stale' : 'awaiting observations'}.
                  </span>
                )}
              </div>
            </div>
            <p className="radar-disclaimer">
              Linux kernel observations do not measure game FPS or display refresh jitter.
            </p>
          </article>

          {/* Card 2: Workload Safety */}
          <article className="shield-overview-card">
            <div>
              <div className="card-header" style={{ marginBottom: 8, paddingBottom: 0, border: 'none' }}>
                <div className="eyebrow">OPERATIONAL PROTOCOL</div>
                <Provenance kind="MEASURED" />
              </div>
              <h3>Safe Workload Selection</h3>
              <p style={{ fontSize: 13, lineHeight: 1.5, marginBottom: 12 }}>
                Pausing is appropriate <strong>only for explicitly chosen, disposable background work</strong>.
              </p>
              <p className="muted" style={{ fontSize: 12, lineHeight: 1.5 }}>
                Active downloads, text editors, cloud synchronizers, launchers, and voice chat can drop TCP sockets or
                miss protocol deadlines if paused.
              </p>
            </div>
            <button className="secondary" onClick={() => navigate('applications')} style={{ width: '100%', marginTop: 12 }}>
              Inspect & Select Applications →
            </button>
          </article>

          {/* Card 3: Fail-Safe Restoration */}
          <article className="shield-overview-card">
            <div>
              <div className="card-header" style={{ marginBottom: 8, paddingBottom: 0, border: 'none' }}>
                <div className="eyebrow">INDEPENDENT SUPERVISOR</div>
                <span className="badge">{status?.guardianAvailable ? 'AVAILABLE' : 'UNAVAILABLE'}</span>
              </div>
              <h3>Auto-Unpause Guardrails</h3>
              <ul className="trigger-list">
                <li className="trigger-item">
                  <span className="trigger-num">01</span>
                  <span>Manual Turn Off click</span>
                </li>
                <li className="trigger-item">
                  <span className="trigger-num">02</span>
                  <span>Protected application termination</span>
                </li>
                <li className="trigger-item">
                  <span className="trigger-num">03</span>
                  <span>Duration timer expiry ({minutes}m default)</span>
                </li>
                <li className="trigger-item">
                  <span className="trigger-num">04</span>
                  <span>Dashboard lease timeout (60s without heartbeat)</span>
                </li>
              </ul>
            </div>
            <p className="muted" style={{ fontSize: 11, margin: 0 }}>
              Guardian survives backend exits. Forced kill of entire machine cannot guarantee SIGCONT.
            </p>
          </article>
        </div>

        {/* Affected processes table */}
        {status?.affectedProcesses.length ? (
          <div className="cyber-card" style={{ padding: 20 }}>
            <div className="card-header">
              <div>
                <div className="eyebrow">SIGNAL AUDIT</div>
                <h3>Currently Paused Cohort ({status.affectedProcesses.length})</h3>
              </div>
              <Provenance kind="MEASURED" />
            </div>
            <table className="data-table-terminal" aria-label="Affected paused processes">
              <thead>
                <tr>
                  <th>PID</th>
                  <th>Process Name</th>
                  <th>Previous State</th>
                  <th>Current State</th>
                </tr>
              </thead>
              <tbody>
                {status.affectedProcesses.map(p => (
                  <tr key={identityKey(p.identity)}>
                    <td>
                      <code className="pid-link">{p.pid}</code>
                    </td>
                    <td>{p.name}</td>
                    <td>
                      <span className="state-badge">{p.previousState}</span>
                    </td>
                    <td>
                      <span className="state-badge state-SLEEPING" style={{ color: 'var(--sunset-accent)' }}>
                        PAUSED (SIGSTOP)
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState title="No confirmed paused processes">
            Select a protected application and choose background work in the Applications tab to enable defense.
          </EmptyState>
        )}
      </div>

      {/* Tab 2: Applications */}
      <div id="shield-applications" role="tabpanel" aria-labelledby="shield-tab-applications" hidden={section !== 'applications'}>
        <div className="shield-config-card">
          <div className="shield-config-row">
            <div className="shield-config-field" style={{ flex: '2 1 300px' }}>
              <label>Protected Application (Target Game / Task)</label>
              <select
                value={target}
                disabled={blocked || scanning}
                onChange={e => {
                  setTarget(e.target.value);
                  setSelected(keys => keys.filter(key => key !== e.target.value));
                }}
              >
                <option value="">Choose an application to protect…</option>
                {candidates
                  .filter(c => !c.isImmune && c.category === 'USER_APPLICATION')
                  .map(c => (
                    <option value={identityKey(c.identity)} key={identityKey(c.identity)}>
                      {c.name} · PID {c.pid}
                    </option>
                  ))}
              </select>
            </div>

            <div className="shield-config-field" style={{ flex: '1 1 120px' }}>
              <label>Duration (Minutes)</label>
              <input
                type="number"
                min="1"
                max="30"
                value={minutes}
                disabled={blocked}
                onChange={e => setMinutes(Number(e.target.value))}
              />
            </div>

            <button
              className="secondary"
              disabled={!token || scanning || busy || !!status?.active}
              onClick={() => void scan()}
              style={{ minHeight: 38 }}
            >
              {scanning ? 'Scanning…' : 'Refresh Applications'}
            </button>
          </div>

          <div className="advanced-choice-card">
            <div className="advanced-choice-left">
              <input
                type="checkbox"
                id="advanced-shield-toggle"
                checked={advanced}
                disabled={blocked}
                onChange={e => setAdvanced(e.target.checked)}
              />
              <label htmlFor="advanced-shield-toggle" style={{ fontWeight: 600, cursor: 'pointer' }}>
                Enable advanced pause for explicitly selected background apps
              </label>
            </div>
            <div className="advanced-choice-meta">
              <strong>{selected.length}</strong> of 16 selected ·{' '}
              {scannedAt ? `Scan age ${Math.floor((now - scannedAt) / 1000)}s` : 'No scan yet'}
            </div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <label className="search-field" style={{ width: '100%', maxWidth: 360 }}>
              <span className="sr-only">Filter applications</span>
              <input
                placeholder="Search candidate name or PID…"
                value={search}
                onChange={e => setSearch(e.target.value)}
                style={{ width: '100%' }}
              />
              <kbd>/ PID</kbd>
            </label>
          </div>

          <div className="tablewrap">
            <table className="data-table-terminal" aria-label="Application candidate selections">
              <thead>
                <tr>
                  <th style={{ width: 60 }}>Select</th>
                  <th>Application / PID</th>
                  <th>CPU % (One Core)</th>
                  <th>Affinity</th>
                  <th>Eligibility & Immunity</th>
                </tr>
              </thead>
              <tbody>
                {filtered.slice(0, 50).map(c => {
                  const key = identityKey(c.identity);
                  const measured = sample(c);
                  const eligible = !c.isImmune && c.category === 'USER_APPLICATION' && key !== target && !['T', 't'].includes(c.state);
                  return (
                    <tr key={key}>
                      <td>
                        <input
                          type="checkbox"
                          aria-label={`Select background PID ${c.pid}`}
                          checked={selected.includes(key)}
                          disabled={
                            blocked ||
                            !advanced ||
                            !eligible ||
                            (!selected.includes(key) && selected.length >= 16)
                          }
                          onChange={e =>
                            setSelected(keys => (e.target.checked ? [...keys, key] : keys.filter(k => k !== key)))
                          }
                        />
                      </td>
                      <td>
                        <strong>{c.name}</strong>
                        <br />
                        <code className="pid-link">{c.pid}</code>
                      </td>
                      <td className="signal-value">
                        {measured?.cpuPercent.value == null
                          ? 'Unavailable'
                          : `${measured.cpuPercent.value.toFixed(1)}%${age > 2500 ? ' · stale' : ''}`}
                      </td>
                      <td>{measured?.allowedCpus.value ?? 'Unavailable'}</td>
                      <td>
                        {key === target ? (
                          <span className="app-eligibility-badge target">Protected Target</span>
                        ) : c.isImmune ? (
                          <span className="app-eligibility-badge immune" title={c.immunityReason ?? 'System Essential'}>
                            🛡️ {c.immunityReason ?? 'System Essential'}
                          </span>
                        ) : eligible ? (
                          <span className="app-eligibility-badge eligible">Eligible Background</span>
                        ) : (
                          <span className="app-eligibility-badge immune">Not Eligible</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {filtered.length > 50 && (
            <p className="muted" style={{ marginTop: 12 }}>
              Showing the first 50 matches. Search to narrow the candidate list.
            </p>
          )}
          {!filtered.length && (
            <EmptyState title="No matching observations">Refresh the scan or refine the search query.</EmptyState>
          )}
        </div>
      </div>

      {/* Tab 3: Activity & Recovery */}
      <div id="shield-activity" role="tabpanel" aria-labelledby="shield-tab-activity" hidden={section !== 'activity'}>
        <div className="shield-audit-card">
          <div className="card-header">
            <div>
              <div className="eyebrow">AUDIT STREAM</div>
              <h3>Controller Activity & Actions</h3>
            </div>
            <Provenance kind="RECORDED" />
          </div>
          <p className="muted">Events describe observed controller actions, not performance gains.</p>

          {status?.events.length ? (
            <ol className="shield-events">
              {[...status.events].reverse().map((e, i) => (
                <li key={i}>
                  <time>{new Date(e.timestampMs).toLocaleTimeString()}</time>
                  <strong style={{ color: 'var(--sunset-accent)' }}>{e.code}</strong>
                  <span>{e.message}</span>
                </li>
              ))}
            </ol>
          ) : (
            <EmptyState title="No recorded Shield events">Actions and restoration results will appear here.</EmptyState>
          )}

          <div className="recovery-cmd-box">
            <span className="scope-meta-label">MANUAL RECOVERY COMMAND</span>
            <code>python3 tools/shield_recover.py --data data</code>
            <p className="muted" style={{ margin: 0, fontSize: 11 }}>
              If automatic restoration cannot complete, stop the backend and run this command as the same user.
              Stored identities are strictly verified via pidfd before unpausing. Refuses to compete with a live guardian.
            </p>
          </div>
        </div>
      </div>

      {/* Confirmation Modal */}
      {pending && (
        <Dialog
          title="Confirm Exact Workload Pause"
          busy={busy}
          onClose={() => {
            if (!busy) setPending(null);
          }}
        >
          <p>
            Protected target: <strong>{pending.target.name} · PID {pending.target.pid}</strong>. Its priority and
            affinity will not be modified.
          </p>
          <p>
            Action: <strong>Pause (SIGSTOP)</strong> selected background applications. No priority or affinity settings
            will be changed.
          </p>
          <p>
            Duration: <strong>{pending.seconds / 60} minutes</strong> (expires automatically; no automatic extension).
            Resumes on explicit Off, game exit, backend loss, or lease expiry.
          </p>
          <p>Selected background processes ({pending.workers.length}):</p>
          <ul>
            {pending.workers.map(c => (
              <li key={identityKey(c.identity)}>
                <strong>{c.name}</strong> · PID {c.pid} · {c.state} → stopped (SIGSTOP)
                <br />
                <small className="muted">
                  Boot {c.identity.bootId} · start ticks {c.identity.startTicks} · UID {c.uid}
                </small>
              </li>
            ))}
          </ul>
          <p className="notice alert-warning">
            <strong>Possible disruption:</strong> Pausing interrupts application execution. Network connections,
            active downloads, editors, cloud sync, launchers, and voice chat may lose connections or miss deadlines.
            Confirm only work you can safely interrupt.
          </p>
          {error && (
            <p role="alert" className="notice alert-error">
              {error}
            </p>
          )}
          <div className="dialog-actions">
            <button type="button" disabled={busy} onClick={() => setPending(null)}>
              Cancel
            </button>
            <button
              type="button"
              className="primary"
              disabled={busy || !!status?.active}
              onClick={() => void activate()}
            >
              {busy ? 'Checking and applying…' : 'Confirm & Turn On'}
            </button>
          </div>
        </Dialog>
      )}
    </section>
  );
}
