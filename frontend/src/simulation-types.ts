export interface CaptureInfo {
  id: string;
  state: string;
  testedAt: string;
  core: number | null;
  observerCpus: number[];
  rawRequestCount: number;
  sampleCount: number;
  hasBaseline: boolean;
  hasContention: boolean;
  sufficiency: string;
  notes: string;
}

export interface TimelineSegment {
  jobId: string;
  role: string;
  startNs: number;
  endNs: number;
  durationNs: number;
  reason: string;
}

export interface QueueSnapshot {
  timestampNs: number;
  eventType: string;
  runningJobId: string | null;
  readyJobIds: string[];
}

export interface JobMetric {
  id: string;
  role: string;
  arrivalNs: number;
  totalDemandNs: number;
  demandServedNs: number;
  firstDispatchNs: number | null;
  completionNs: number | null;
  responseTimeNs: number | null;
  waitingTimeNs: number | null;
  turnaroundTimeNs: number | null;
  completed: boolean;
}

export interface ModelMetrics {
  totalJobs: number;
  completedJobs: number;
  censoredJobs: number;
  meanResponseTimeMs: number | null;
  p50ResponseTimeMs: number | null;
  p95ResponseTimeMs: number | null;
  meanWaitingTimeMs: number | null;
  p50WaitingTimeMs: number | null;
  p95WaitingTimeMs: number | null;
  meanTurnaroundTimeMs: number | null;
  p50TurnaroundTimeMs: number | null;
  p95TurnaroundTimeMs: number | null;
  cpuUtilizationPercent: number | null;
  allocationShares: Record<string, number>;
  jainFairnessIndex: number | null;
}

export interface AlgorithmResult {
  algorithm: string;
  dataStructure: string;
  complexity: string;
  assumptions: string;
  metrics: ModelMetrics;
  timeline: TimelineSegment[];
  queueEvents: QueueSnapshot[];
  jobs: JobMetric[];
  partialResult: boolean;
  partialReason: string | null;
}

export interface SimulationResponse {
  captureId: string;
  phase: string;
  inputKind: string;
  sufficiency: string;
  qualityNotes: string;
  horizonNs: number;
  results: Record<string, AlgorithmResult>;
  kind: 'SIMULATED';
}
