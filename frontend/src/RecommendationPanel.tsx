import { apiFetch } from './http';
import { useState, useEffect, useRef } from 'react';
import type { Latest } from './types';
import { psiAverage } from './telemetry-view';
import {advisorState,advisorLabels,measuredNumber} from './advisor-state';
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
  latest: Latest | null;
  live: boolean;
}

export function RecommendationPanel({ token, experiment, latest, live }: Props) {
  const [recommendation, setRecommendation] = useState<Recommendation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [audits, setAudits] = useState<ActionAudit[]>([]);
  const [pendingCandidate, setPendingCandidate] = useState<CandidateScenario | null>(null);
  const [actionResult, setActionResult] = useState<BatchActionResult | null>(null);
  const [auditError, setAuditError] = useState('');
  const [auditsLoaded,setAuditsLoaded]=useState(false);
  const [resetMessage, setResetMessage] = useState<string | null>(null);

  const [operation, setOperation] = useState('idle');
  const generation = useRef(0);
  const busy = useRef(false);
  const pendingAction = useRef<{actionId:string;experimentId:string;recommendationId:string;targets:{pid:number;identity:CandidateScenario['targetWorkers'][number]['identity'];expectedCurrentNice:number;requestedNice:number}[]} | null>(null);
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  const expired = !!recommendation && recommendation.status === 'ACTIVE' && now >= Date.parse(recommendation.expiresAt);
  const actionable = recommendation?.status === 'ACTIVE' && !expired && !actionResult;
  const headers = {'X-CSRF-Token': token, 'Content-Type': 'application/json'};

  async function responseError(res:Response, fallback:string):Promise<Error> {
    const data = await res.json().catch(() => null) as {message?:string;error?:string;detail?:string}|null;
    return new Error(data?.message ?? data?.detail ?? data?.error ?? `${fallback} (${res.status})`);
  }
  async function loadAudits(epoch=generation.current) {
    try {
      const res = await apiFetch('/api/actions/audits?limit=100', {headers});
      if (!res.ok) throw await responseError(res, 'Audit unavailable');
      const data = await res.json() as ActionAudit[];
      if (epoch === generation.current) {setAudits(data);setAuditError('');setAuditsLoaded(true);}
    } catch (e) { if (epoch === generation.current) setAuditError(e instanceof Error ? e.message : 'Audit unavailable'); }
  }
  useEffect(() => {
    const epoch=++generation.current;
    busy.current=false;pendingAction.current=null;
    setLoading(false);setOperation('idle');setError(null);setRecommendation(null);setPendingCandidate(null);setActionResult(null);setAudits([]);setAuditsLoaded(false);setAuditError('');setResetMessage(null);
    if (token) {
      void loadAudits(epoch);
      void (async () => {
        try {
          const res=await apiFetch('/api/recommendations/latest',{headers});
          if(res.status===204)return;
          if(!res.ok)throw await responseError(res,'Evaluation unavailable');
          const rec=await res.json() as Recommendation;
          if(epoch===generation.current && !busy.current && (!experiment?.id || rec.experimentId===experiment.id))setRecommendation(rec);
        } catch(e) {if(epoch===generation.current&&!busy.current)setError(e instanceof Error?e.message:'Evaluation unavailable');}
      })();
    }
    return () => {generation.current++;};
  }, [token, experiment?.id]);

  async function run(kind:string, task:(epoch:number)=>Promise<void>) {
    if(!token || busy.current)return;
    busy.current=true;setLoading(true);setOperation(kind);setError(null);
    const epoch=++generation.current;
    try {await task(epoch);}
    catch(e) {if(epoch===generation.current){setError(e instanceof Error?e.message:'Request failed');setOperation('failed');}}
    finally {if(epoch===generation.current){busy.current=false;setLoading(false);if(!auditsLoaded)void loadAudits(epoch);}}
  }
  async function generateRecommendation() {
    await run('evaluating', async epoch => {
      const res=await apiFetch('/api/recommendations',{method:'POST',headers,body:JSON.stringify({experimentId:experiment?.id})});
      if(!res.ok)throw await responseError(res,'Evaluation failed');
      const rec=await res.json() as Recommendation;
      if(epoch===generation.current){setRecommendation(rec);setPendingCandidate(null);setActionResult(null);pendingAction.current=null;setOperation('complete');}
    });
  }
  async function rejectRecommendation() {
    if(!recommendation)return;
    await run('rejecting',async epoch=>{
      const res=await apiFetch(`/api/recommendations/${recommendation.id}/reject`,{method:'POST',headers});
      if(!res.ok)throw await responseError(res,'Could not reject recommendation');
      const rec=await res.json() as Recommendation;
      if(epoch===generation.current){setRecommendation(rec);setPendingCandidate(null);setOperation('rejected');}
    });
  }
  async function confirmApply() {
    if(!pendingCandidate || !recommendation || !pendingCandidate.eligible || (!actionable && !pendingAction.current))return;
    await run('applying',async epoch=>{
      const request=pendingAction.current ?? {actionId:crypto.randomUUID(),experimentId:recommendation.experimentId,recommendationId:recommendation.id,targets:pendingCandidate.targetWorkers.map(w=>({pid:w.pid,identity:w.identity,expectedCurrentNice:w.currentNice,requestedNice:pendingCandidate.targetNice}))};
      pendingAction.current=request;
      // Recover a completed response before resending the same idempotent request.
      let result:BatchActionResult|null=null;
      const previous=await apiFetch(`/api/actions/nice/${request.actionId}`,{headers});
      if(epoch!==generation.current)return;
      if(previous.ok)result=await previous.json() as BatchActionResult;
      else if(previous.status!==404)throw await responseError(previous,'Could not verify the previous action');
      if(!result){
        if(expired){pendingAction.current=null;setPendingCandidate(null);throw new Error('Recommendation expired and no completed result was found. Review action history and evaluate again.');}
        const res=await apiFetch('/api/actions/nice',{method:'POST',headers,body:JSON.stringify(request)});
        if(!res.ok){if([400,404,409].includes(res.status))pendingAction.current=null;throw await responseError(res,'Priority change failed');}
        result=await res.json() as BatchActionResult;
      }
      if(epoch===generation.current){setActionResult(result);if(result.overallStatus==='SUCCESS'||result.overallStatus==='PARTIAL_SUCCESS')setRecommendation({...recommendation,status:result.overallStatus==='SUCCESS'?'APPLIED':'PARTIALLY_APPLIED'});setPendingCandidate(null);setOperation(result.overallStatus==='SUCCESS'?'applied':result.overallStatus==='PARTIAL_SUCCESS'?'partially-applied':'failed');await loadAudits(epoch);}
    });
  }
  async function resetWorkers() {
    if(!experiment?.id)return;
    await run('resetting',async epoch=>{
      const res=await apiFetch(`/api/experiments/${experiment.id}/reset`,{method:'POST',headers});
      if(!res.ok)throw await responseError(res,'Reset failed');
      const data=await res.json() as {message:string};
      if(epoch===generation.current){setResetMessage(data.message);setRecommendation(null);setActionResult(null);pendingAction.current=null;setOperation('idle');}
    });
  }

  const evidence = recommendation?.evidence;
  const number = measuredNumber;
  const state=advisorState(recommendation,actionResult,operation,loading,error,now);

  return (
    <section className="recommendations-panel panel-root" aria-label="Allocation advisor" aria-busy={loading}>

      <div className="experiment-hero-banner">
        <div>
          <div className="scope-badge-group">
            <span className="scope-tag scope-tag-primary">Measured input</span>
            <span className="scope-tag">Priority model</span>
            <span className="scope-tag scope-tag-success">Safe changes only</span>
          </div>
          <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--cyber-text-primary)' }}>
            Run the experiment → review the results → apply the change → measure again.
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <button
            className="primary"
            onClick={() => void generateRecommendation()}
            disabled={loading || !token || !!pendingAction.current && !actionResult}
            style={{ minHeight: 38 }}
          >
            {operation === 'evaluating' && loading ? 'Evaluating…' : 'Evaluate Contention'}
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

      <p role="status" className="advisor-status">{advisorLabels[state]}</p>
      <div className="system-health" aria-label="System health">
        <strong>{!live ? 'Telemetry unavailable or stale' : latest?.snapshot?.availability !== 'AVAILABLE' ? 'Needs attention' : error || auditError || actionResult && actionResult.overallStatus!=='SUCCESS' ? 'Needs attention' : 'Good'}</strong>
        <span>{experiment?.workers ? `${experiment.workers.filter(w=>w.alive).length} managed workers monitored` : 'Managed worker count unavailable'}</span>
        <span>{!auditsLoaded || auditError ? 'Permission error count unavailable' : `${audits.filter(a=>/permission denied|operation not permitted/i.test(a.reason ?? '')).length} known permission errors in loaded action history`}</span>
        <span>{loading && operation==='evaluating' ? 1 : 0} pending evaluation</span>
      </div>
      <p className="sample-metadata">Current system pressure (PSI some, 10-second average): CPU {number(psiAverage(latest?.snapshot?.cpuPressureSome?.value),'%')} · I/O {number(psiAverage(latest?.snapshot?.ioPressureSome?.value),'%')} · {live ? 'Live' : 'Stale / unavailable'}{latest?.snapshot && ` · Sample ${new Date(latest.snapshot.timestamp).toLocaleTimeString()}`}. These readings are separate from experiment measurements.</p>
      {experiment?.workers?.filter(w=>w.alive&&!w.progress).map(w=><p className="notice alert-warning" key={w.identity.pid}>Worker telemetry unavailable for PID {w.identity.pid}. Progress is unavailable.</p>)}
      {error && !pendingCandidate && (
        <p className="notice alert-error" role="alert">
          {error} {pendingAction.current ? 'The action outcome may be unknown. Reopen the same change to check its result before retrying.' : 'Retry when the connection and experiment are ready.'}
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
              <div className="eyebrow">GETTING STARTED</div>
              <h3>Review before changing</h3>
            </div>
            <Provenance kind="MEASURED" />
          </div>
          <p>Run the experiment → review the results → apply the change → measure again.</p>
          <p className="muted">Complete the baseline and contention phases in the lab, then evaluate the measured results.</p>
        </div>
      )}

      {/* 3. Evidence HUD & Candidate Allocations */}
      {recommendation && evidence && (
        <>
          <div className="cyber-card" style={{ padding: 20, marginBottom: 20 }}>
            <div className="card-header">
              <div>
                <div className="eyebrow">EXPERIMENT RESULTS</div>
                <h3>{expired ? 'Evaluation expired' : recommendation.status === 'REJECTED' ? 'Recommendation rejected' : 'Evaluation complete'}</h3>
              </div>
              <Provenance kind="MEASURED" />
            </div>
            <p><strong>{evidence.core >= 0 ? `CPU contention: pinned core ${evidence.core}` : 'No experiment selected'}</strong> · {recommendation.status.replaceAll('_',' ').toLowerCase()}</p>
            <p style={{ fontSize: 13, lineHeight: 1.5, marginBottom: 16 }}>{evidence.assessment}</p>
            <p className="muted">Evaluated {new Date(recommendation.createdAt).toLocaleTimeString()} · Experiment {recommendation.experimentId}</p>
            <p>Latency impact: {number(evidence.baselineP95Ms == null || evidence.contentionP95Ms == null ? null : evidence.contentionP95Ms-evidence.baselineP95Ms, ' ms')} (contention p95 − baseline p95)</p>
            <p className="muted">Context-switch comparison: unavailable · Worker CPU during the contention window: unavailable</p>
            {recommendation.status !== 'REJECTED' && !pendingAction.current && !actionResult && !['APPLIED','PARTIALLY_APPLIED'].includes(recommendation.status) && <button disabled={loading} onClick={()=>void rejectRecommendation()}>{operation==='rejecting'&&loading?'Rejecting…':'Reject'}</button>}
            {recommendation.status === 'REJECTED' && <p role="status">Rejected for this backend session. No priority change was requested by Reject.</p>}

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
                  <strong>{number(evidence.deadlineMissRate == null ? null : evidence.deadlineMissRate * 100)}</strong>
                  <span className="metric-hud-unit">%</span>
                </div>
                <small className="metric-hud-sub">{evidence.deadlineMissCount ?? 'Unavailable'} missed dispatch deadlines</small>
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
                <div className="eyebrow">CANDIDATES</div>
                <h3>Priority model</h3>
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
                    <span className="nice-chip">NICE {candidate.targetNice}</span>
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
                    <div>{candidate.targetWorkers.map(w=><p key={w.pid}>{w.role} · PID {w.pid} · evaluated nice {w.currentNice} → {candidate.targetNice}</p>)}</div>
                    <button
                      className="primary"
                      disabled={loading || !token || !candidate.eligible || (!actionable && (actionResult != null || pendingAction.current?.targets[0]?.requestedNice!==candidate.targetNice))}
                      onClick={() => {
                        setError(null);
                        if(pendingAction.current && pendingAction.current.targets[0]?.requestedNice!==candidate.targetNice){setError('Resolve the previous action before choosing a different change.');return;}
                        setPendingCandidate(candidate);
                      }}
                    >
                      Apply Change
                    </button>
                  </div>
                </article>
              ))}
            </div>

            {recommendation.candidates.length === 0 && (
              <EmptyState title="No eligible allocation">{evidence.assessment}</EmptyState>
            )}

            <details style={{ marginTop: 16 }}>
              <summary>Model assumptions and restoration limits</summary>
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
          title="Confirm priority change"
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
            Lowering nice again may require privileges or suitable resource limits. Reset stops and recreates owned workers with new
            identities; it does not restore the old processes.
          </p>
          {error && (
            <p className="notice alert-error" role="alert">
              {error}
            </p>
          )}
          {expired && (
            <p className="notice alert-warning">Recommendation expired. Check any pending action result before evaluating again.</p>
          )}
          <div className="dialog-actions">
            <button disabled={loading} onClick={() => setPendingCandidate(null)}>
              Cancel
            </button>
            <button
              className="primary"
              disabled={loading || (!actionable && !pendingAction.current) || !token || !pendingCandidate.eligible}
              onClick={() => void confirmApply()}
            >
              {loading ? 'Applying…' : pendingAction.current ? 'Check result and retry' : `Confirm nice ${pendingCandidate.targetNice}`}
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

      {(actionResult || ['APPLIED','PARTIALLY_APPLIED'].includes(recommendation?.status ?? '')) && <p className="notice">Measure again in the contention lab, then open <a href="#/experiments/results">Results &amp; audit</a> to compare measured phases. An applied change does not establish a latency improvement.</p>}
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
              <div className="eyebrow">ACTION HISTORY</div>
              <h3>Priority changes</h3>
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
                      <span className={`state-badge ${a.status==='SUCCESS'?'':'alert-warning'}`}>{a.status}</span>
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
