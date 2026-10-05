import {apiFetch} from './http';
import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import type { Capabilities, Latest, Snapshot } from './types';
import { accept, fresh, readEvents } from './stream';
import './style.css';
import { LiveMonitorPanel } from './LiveMonitorPanel';
import { ExperimentPanel } from './ExperimentPanel';
import { SimulationPanel } from './SimulationPanel';
import { RecommendationPanel } from './RecommendationPanel';
import { ResultsPanel } from './ResultsPanel';
import { GameShieldPanel } from './GameShieldPanel';
import { EnvironmentPanel } from './EnvironmentPanel';
import type { Experiment } from './experiment-types';
import {Icon} from './ui';
import {CyberpunkErrorBoundary} from './CyberpunkErrorBoundary';
import {AuthenticationError,requireAuthentication,connectSession} from './http';
import type {CpuPoint} from './telemetry-view';

type Tab = 'monitor' | 'experiment' | 'simulation' | 'recommendation' | 'results' | 'gameshield' | 'environment';

interface TabItem {id:Tab;label:string;title:string;description:string;group:string;number:string}
const TABS:TabItem[]=[
  {id:'monitor',label:'Monitor',title:'Live telemetry',description:'Observe machine capacity, CPU pressure and real process activity.',group:'TELEMETRY',number:'01'},
  {id:'experiment',label:'Contention lab',title:'Contention lab',description:'Run a bounded trial. Measure the service and the competing work.',group:'EXPERIMENTATION',number:'02'},
  {id:'simulation',label:'Scheduling models',title:'Scheduling models',description:'Compare modeled allocations using captured workload inputs.',group:'SIMULATION',number:'03'},
  {id:'recommendation',label:'Allocation advisor',title:'Allocation advisor',description:'Review the workload data and choose whether a priority change is justified.',group:'EXPERIMENTS',number:'04'},
  {id:'results',label:'Results & audit',title:'Results & audit',description:'Compare observed phases and inspect retained trial evidence.',group:'EXPERIMENTATION',number:'05'},
  {id:'gameshield',label:'Game Shield',title:'Game Shield',description:'Inspect running applications and control the existing shielding policy.',group:'APPLICATIONS',number:'06'},
  {id:'environment',label:'System scope',title:'Environment & scope',description:'Know what this Linux environment can measure and control.',group:'SYSTEM',number:'07'}
];

const ROUTES:Record<Tab,string>={monitor:'/monitor',gameshield:'/gameshield/overview',experiment:'/experiments/lab',simulation:'/experiments/models',recommendation:'/experiments/advisor',results:'/experiments/results',environment:'/system'};
function tabFromUrl():Tab {
 const route=location.hash.slice(1);
 if(route.startsWith('/gameshield'))return 'gameshield';
 return (Object.entries(ROUTES).find(([,path])=>path===route)?.[0] as Tab|undefined)??'monitor';
}
const initialFragment=new URLSearchParams(location.hash.slice(1));
const launchCredential=initialFragment.get('bootstrap')??undefined;
if(launchCredential)history.replaceState(null,'',location.pathname+location.search+'#/monitor');
function App() {
  const [tab, setTab] = useState<Tab>(tabFromUrl);
  const [visited,setVisited]=useState<Tab[]>([tabFromUrl()]);
  const [authEpoch,setAuthEpoch]=useState(0);
  const [mobile,setMobile]=useState(()=>window.matchMedia('(max-width:760px)').matches);
  useEffect(()=>{const query=window.matchMedia('(max-width:760px)');const update=()=>setMobile(query.matches);query.addEventListener('change',update);return()=>query.removeEventListener('change',update)},[]);
  const [experiment, setExperiment] = useState<Experiment | null>(null);
  const [token, setToken] = useState('');
  const [caps, setCaps] = useState<Capabilities | null>(null);
  const [latest, setLatest] = useState<Latest | null>(null);
  const [cpuHistory, setCpuHistory] = useState<CpuPoint[]>([]);
  const [connection, setConnection] = useState('Disconnected');
  const [streamingEnabled, setStreamingEnabled] = useState(true);
  const streamingRef = useRef(true);
  useEffect(() => {
    streamingRef.current = streamingEnabled;
  }, [streamingEnabled]);
  const [error, setError] = useState('');
  const [gap, setGap] = useState('');
  const [clock, setClock] = useState(performance.now());

  useEffect(()=>{
    let cancelled=false;let timer:ReturnType<typeof setTimeout>;let attempt=0;
    async function signIn(){
      setConnection(attempt?'Reconnecting':'Connecting');
      try{const csrf=await connectSession(launchCredential);if(!cancelled){setToken(csrf);setError('')}}
      catch(e){if(!cancelled){setConnection('Connection unavailable');setError(e instanceof Error?e.message:'Connection unavailable');if(++attempt<5)timer=setTimeout(()=>void signIn(),Math.min(1000*2**attempt,10000));}}
    }
    void signIn();return()=>{cancelled=true;clearTimeout(timer)};
  },[authEpoch]);
  useEffect(()=>{const expired=()=>{setToken('');setAuthEpoch(value=>value+1)};window.addEventListener('schedwise-auth-expired',expired);return()=>window.removeEventListener('schedwise-auth-expired',expired)},[]);
  useEffect(()=>{const change=()=>{const next=tabFromUrl();setTab(next);setVisited(prev=>prev.includes(next)?prev:[...prev,next])};window.addEventListener('hashchange',change);return()=>window.removeEventListener('hashchange',change)},[]);
  const last = useRef<Snapshot | null>(null);
  const received = useRef(0);
  const cursor = useRef('');

  useEffect(() => {
    const timer = setInterval(() => setClock(performance.now()), 500);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    setExperiment(null);
    if (!token) return;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    let active = true;

    last.current = null;
    cursor.current = '';
    setLatest(null);
    setCaps(null);
    setCpuHistory([]);
    setError('');
    setGap('');

    const headers = { 'X-CSRF-Token':token };

    const ingest = (payload: Latest) => {
      if (!streamingRef.current) return;
      if (payload.snapshot && accept(last.current, payload.snapshot)) {
        const restarted=last.current&&last.current.sessionId!==payload.snapshot.sessionId;
        last.current = payload.snapshot;
        received.current = performance.now();
        setLatest(payload);

        const cpuVal = payload.snapshot.cpus?.cpu?.busyPercent?.value;
        setCpuHistory(prev => [...(restarted?[]:prev.slice(-29)),{timestamp:payload.snapshot!.timestamp,sequence:payload.snapshot!.sequence,value:typeof cpuVal==='number'?cpuVal:null}]);
      }
    };

    async function connect() {
      setConnection('Connecting');
      try {
        const capRes = await apiFetch('/api/capabilities', { headers, signal: controller.signal });
        requireAuthentication(capRes);
          if (!capRes.ok) throw new Error(`Authentication / API error (${capRes.status})`);
        setCaps(await capRes.json() as Capabilities);

        if (!last.current) {
          const initial = await apiFetch('/api/snapshots/latest', { headers, signal: controller.signal });
          requireAuthentication(initial);
          if (!initial.ok) throw new Error(`Snapshot error (${initial.status})`);
          const payload = await initial.json() as Latest;
          ingest(payload);
          if (payload.snapshot) cursor.current = `${payload.snapshot.sessionId}:${payload.snapshot.sequence}`;
        }

        while (active) {
          const res = await apiFetch('/api/events', {
            headers: {
              ...headers,
              ...(cursor.current ? { 'Last-Event-ID': cursor.current } : {})
            },
            signal: controller.signal
          });
          requireAuthentication(res);
          if (!res.ok) throw new Error(`Stream error (${res.status})`);

          setConnection('Connected');
          setError('');

          await readEvents(res, event => {
            if (event.event === 'gap') {
              setCpuHistory([]);
              setGap('Stream history expired or backend restarted. Missing samples are not reconstructed.');
            }
            if (event.event === 'experiment') {
              setExperiment(JSON.parse(event.data) as Experiment | null);
            }
            if (event.event === 'snapshot') {
              const next = JSON.parse(event.data) as Latest;
              ingest(next);
              cursor.current = event.id;
            }
          });

          if (active) throw new Error('Stream ended; reconnecting with the last accepted cursor');
        }
      } catch (e) {
        if (active) {
          if(e instanceof AuthenticationError){active=false;setToken('');setLatest(null);setCaps(null);setCpuHistory([]);setConnection('Reconnecting');setError(e.message);return;}
          setConnection('Disconnected');
          setError(e instanceof Error ? e.message : 'Connection failed');
          timer = setTimeout(() => void connect(), 2000);
        }
      }
    }

    void connect();
    return () => {
      active = false;
      controller.abort();
      clearTimeout(timer);
    };
  }, [token]);

  const snap = latest?.snapshot;
  const age = latest ? latest.sampleAgeMillis + Math.max(0, clock - received.current) : -1;
  const live = connection === 'Connected' && fresh(age);

  const activeModule=TABS.find(item=>item.id===tab)!;
  const selectTab=(id:Tab)=>{location.hash=ROUTES[id];setTab(id);setVisited(prev=>prev.includes(id)?prev:[...prev,id]);};
  const experiments:Tab[]=['experiment','simulation','recommendation','results'];
  const primary:Tab[]=['monitor','gameshield','experiment','environment'];
  const keyboard=(event:React.KeyboardEvent,index:number,items:Tab[])=>{
    const next=event.key==='ArrowDown'||event.key==='ArrowRight'?(index+1)%items.length:event.key==='ArrowUp'||event.key==='ArrowLeft'?(index+items.length-1)%items.length:event.key==='Home'?0:event.key==='End'?items.length-1:-1;
    if(next>=0){event.preventDefault();selectTab(items[next]);document.getElementById(`tab-${items[next]}`)?.focus()}
  };
  const kernel=caps?.sources.kernel?.value;
  const ticks=caps?.sources.clockTicks?.value;
  return (
    <div className="app-shell">
      <a className="skip-link" href="#workspace">Skip to workspace</a>
      <header className="system-header">
        <a className="brand" href="#/monitor" onClick={()=>selectTab('monitor')} aria-label="SchedWise monitor"><span className="brand-mark"><Icon name="monitor"/></span><span>SCHEDWISE<small>KERNEL MONITOR</small></span></a>
        <div className="header-telemetry"><span className="kernel-chip" title={String(kernel??'Connect to discover kernel')}>[ LINUX <b>{kernel??'—'}</b> ]</span><span className="clock-chip">[ CLK_TCK <b>{ticks!=null?`${ticks}Hz`:'—'}</b> ]</span></div>
        <div className="header-status">
          <div className="stream-toggle-control" role="group" aria-label="Live telemetry stream power">
            <span className="stream-toggle-label">STREAM</span>
            <button
              type="button"
              className={`toggle-switch-btn ${streamingEnabled ? 'is-on' : 'is-off'}`}
              onClick={() => setStreamingEnabled(prev => !prev)}
              title={streamingEnabled ? "Pause live telemetry stream to inspect metrics" : "Resume live telemetry stream"}
              aria-pressed={streamingEnabled}
            >
              <span className={`switch-pill ${streamingEnabled ? 'active-on' : ''}`}>ON</span>
              <span className={`switch-pill ${!streamingEnabled ? 'active-off' : ''}`}>OFF</span>
            </button>
          </div>
          <span className={`signal-badge ${!streamingEnabled ? 'paused' : live ? 'live' : snap ? 'stale' : ''}`}><i aria-hidden="true"/>{!streamingEnabled ? 'STREAM PAUSED' : live ? 'STREAM LIVE' : snap ? 'STALE' : connection.toUpperCase()}</span>
          <span className="header-age">{age<0?'NO SAMPLE':`${(age/1000).toFixed(1)}s AGE`}</span>
          <button className="session-button" onClick={()=>setAuthEpoch(value=>value+1)} title="Reconnect local session"><Icon name="key"/>{token?'Connected':'Reconnect'}<span className={`session-light ${token&&live&&streamingEnabled?'on':''}`}/></button>
        </div>
      </header>
      <aside className="tactical-rail">
        <div className="rail-heading">WORKSPACE </div>
        <nav className="rail-navigation" aria-label="Workspace modules" role="tablist" aria-orientation={mobile?'horizontal':'vertical'}>
          {primary.map((id,index)=>{const item=TABS.find(t=>t.id===id)!;const selected=tab===id||(id==='experiment'&&experiments.includes(tab));return <button key={id} id={`tab-${id}`} role="tab" aria-selected={selected} aria-controls={id==='experiment'?'experiment-navigation':`panel-${id}`} tabIndex={selected?0:-1} className={`rail-link ${selected?'active':''}`} onClick={()=>selectTab(id)} onKeyDown={e=>keyboard(e,index,primary)}><Icon name={id}/><span className="module-copy">{id==='experiment'?'Experiments':id==='environment'?'System':item.label}</span></button>})}
        </nav>
        <div className="rail-footer"><p>Linux process monitoring<br/>Changes require confirmation</p><button className="text-button" onClick={()=>selectTab('environment')}>Inspect capabilities <Icon name="arrow"/></button></div>
      </aside>
      <main id="workspace" className="workspace" tabIndex={-1}>
        <div className="workspace-heading"><div><div className="eyebrow">{activeModule.group}</div><h1>{activeModule.title}</h1><p>{activeModule.description}</p></div><div className="sequence-chip"><span>Samples collected</span><strong>{snap?.sequence ?? 'Unavailable'}</strong></div></div>
        <p className="sample-metadata"><span className="mobile-sample-count">Samples collected: {snap?.sequence ?? 'Unavailable'} · </span>Last sample: {snap ? new Date(snap.timestamp).toLocaleTimeString() : 'Unavailable'} · {live ? 'Live' : snap ? 'Stale' : 'No sample'} · Kernel: {kernel ?? 'Unavailable'} · CPU: {caps?.sources.cpuModel?.value ?? 'Unavailable'}</p>
        {!token&&<div className="connection-prompt"><Icon name="key"/><div><strong>{connection}</strong><span>Connecting securely to your local backend.</span></div></div>}
        {error&&<p role="alert" className="notice alert-error">{error} <button className="text-button" onClick={()=>setAuthEpoch(value=>value+1)}>Reconnect</button></p>}
        {experiments.includes(tab)&&<div id="experiment-navigation" className="subtabs" role="tablist" aria-label="Experiment sections">{experiments.map((id,index)=><button key={id} id={`experiment-tab-${id}`} role="tab" aria-selected={tab===id} aria-controls={`panel-${id}`} tabIndex={tab===id?0:-1} onClick={()=>selectTab(id)} onKeyDown={e=>{const next=e.key==='ArrowRight'?(index+1)%4:e.key==='ArrowLeft'?(index+3)%4:e.key==='Home'?0:e.key==='End'?3:-1;if(next>=0){e.preventDefault();selectTab(experiments[next]);document.getElementById(`experiment-tab-${experiments[next]}`)?.focus()}}}>{id==='experiment'?'LAB':id==='simulation'?'MODELS':id==='recommendation'?'ADVISOR':'RESULTS'}</button>)}</div>}
        {gap&&<p className="notice">{gap}</p>}
      {/* Tab Panels */}
      {TABS.filter(item=>visited.includes(item.id)).map(item=><div key={item.id} id={`panel-${item.id}`} role="tabpanel" aria-label={item.title} hidden={tab!==item.id} className="tab-content"><CyberpunkErrorBoundary>
        {item.id === 'monitor' && (
          <LiveMonitorPanel
            latest={latest}
            connection={connection}
            age={age}
            live={live}
            cpuHistory={cpuHistory}
          />
        )}

        {item.id === 'experiment' && (
          <ExperimentPanel
            token={token}
            allowedCpus={caps?.sources.allowedCpus?.value != null ? String(caps.sources.allowedCpus.value) : null}
            experiment={experiment}
            onChange={setExperiment}
          />
        )}

        {item.id === 'simulation' && (
          <SimulationPanel token={token} />
        )}

        {item.id === 'recommendation' && (
          <RecommendationPanel
            token={token}
            experiment={experiment}
            latest={latest} live={live}
          />
        )}

        {item.id === 'results' && (
          <ResultsPanel
            token={token}
            experiment={experiment}
          />
        )}

        {item.id === 'gameshield' && (
          <GameShieldPanel
            token={token}
            latest={latest} age={age}
          />
        )}

        {item.id === 'environment' && (
          <EnvironmentPanel
            caps={caps}
            latest={latest}
          />
        )}
      </CyberpunkErrorBoundary></div>)}

      <footer className="system-footer">
        {snap
          ? `Session ${snap.sessionId} · samples collected: ${snap.sequence} · sampled ${snap.timestamp}`
          : 'AWAITING SESSION · No telemetry received'}
      </footer>
      </main>

    </div>
  );
}

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <CyberpunkErrorBoundary><App /></CyberpunkErrorBoundary>
  </React.StrictMode>
);
