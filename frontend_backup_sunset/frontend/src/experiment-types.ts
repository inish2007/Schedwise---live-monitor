import type {Identity} from './types';
export interface ExperimentMetric {
  value:number|null; unit:string; kind:'MEASURED'|'DERIVED'; sourceId:string;
  timestamp:number; sessionId:string; windowSeconds:number; availability:string; reason:string|null;
}
export interface PhaseSummary {
  scheduledCount:number;successCount:number;errorCount:number;timeoutCount:number;skippedCount:number;
  missedDispatchCount:number;deadlineMissCount:number;p50Ms:ExperimentMetric;p95Ms:ExperimentMetric;p99Ms?:ExperimentMetric;
  dispatchDelayP95Ms:ExperimentMetric;offeredRateHz:ExperimentMetric;completedRateHz:ExperimentMetric;
  errorRate:ExperimentMetric;deadlineMissRate:ExperimentMetric;
}
export interface ChildObservation {
  identity:Identity;uid:number;parentPid:number;threads:number;nice:number;allowedCpus:number[];cgroup:string;autogroup:string|null;
}
export interface WorkerProgress {hashes:number;hashBudget:number;cpuNs:number;elapsedNs:number;monotonicNs:number;digest:string;timestamp:number}
export interface Experiment {
  id:string;state:string;phase:string;core:number;revision:number;timestamp:number;maxSeconds:number;
  backendSessionId:string;finished:boolean;stopRequested:boolean;exportAvailable:boolean;
  observerCpus:number[];observerLimitation?:string|null;reason?:string|null;supervisorFailure?:string;
  elapsedSeconds?:number;parameters?:{iterations:number;bufferBytes:number;rateHz:number;intervalNs:number;workerHashBudget:number;
    workers:number;deadlineMs:number;timeoutMs:number;maxInFlight:number;warmupSeconds:number;baselineSeconds:number;contentionSeconds:number;
    afterActionSeconds?:number;transitionSeconds:number;calibrationMedianCpuNs:number;calibrationCount:number;expectedDigest:string;frozenAtNs:number}|null;
  phases?:{name:string;startNs:number;endNs:number}[];
  summaries?:Record<string,PhaseSummary>;
  workers?:{role:string;identity:Identity;alive:boolean;observed:ChildObservation|null;lastVerifiedNs:number|null;progress:WorkerProgress|null;hashesPerSecond:ExperimentMetric;hashesByPhase?:Record<string,ExperimentMetric>}[];
  children?:{role:string;identity:Identity;observed:ChildObservation|null;alive:boolean;lastVerifiedNs:number|null}[];
  cleanup?:{verified:boolean;children:{role:string;identity:Identity;exitCode:number|null;alive:boolean}[];checkedAtNs:number}|null;
  javaCleanup?:{identity:Identity;role:string;alive:boolean}[];
  coordinatorAlive?:boolean;collectorAffinityRestored?:boolean;contentionCohortVerified?:boolean;
}

export interface ComparisonReport {
  status: 'VALID' | 'INVALID';
  comparable: boolean;
  invalidReasons: string[];
  hasAfterAction: boolean;
  baselineP95Ms: number | null;
  contentionP95Ms: number | null;
  afterP95Ms: number | null;
  baselineP99Ms: number | null;
  contentionP99Ms: number | null;
  afterP99Ms: number | null;
  deadlineMissesContention: number | null;
  deadlineMissesAfter: number | null;
  workerHashesContention: number | null;
  workerHashesAfter: number | null;
  latencyP95ImprovementPercent: number | null;
  latencyRecoveryPercent: number | null;
  throughputTradeoffPercent: number | null;
  deadlineMissReduction: number | null;
  disclosures: string[];
}

export interface SessionInfo {
  id: string;
  startedAt: string;
  environment?: Record<string, unknown>;
}
