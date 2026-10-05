export interface TargetWorker {
  pid: number;
  role: string;
  identity: { bootId: string; pid: number; startTicks: number };
  currentNice: number;
}

export interface CandidateScenario {
  targetNice: number;
  title: string;
  targetWeight: number;
  serviceWeight: number;
  expectedProtectedSharePercent: number;
  expectedBackgroundSharePercent: number;
  simulatedServiceResponseP50Ms: number | null;
  simulatedServiceResponseP95Ms: number | null;
  simulatedServiceTurnaroundP50Ms: number | null;
  tradeoffSummary: string;
  targetWorkers: TargetWorker[];
  eligible: boolean;
  eligibilityNote: string;
}

export interface ContentionEvidence {
  status: string;
  core: number;
  baselineP95Ms: number | null;
  contentionP95Ms: number | null;
  latencyDegradationRatio: number | null;
  deadlineMissCount: number;
  deadlineMissRate: number;
  errorCount: number;
  coreCpuBusyPercent: number | null;
  psiPressureSomeAvg: number | null;
  corroboratedByPressure: boolean;
  assessment: string;
  missingSources: string[];
  attributionLimitation: string;
}

export interface Recommendation {
  id: string;
  experimentId: string;
  createdAt: string;
  expiresAt: string;
  status: string;
  evidence: ContentionEvidence;
  candidates: CandidateScenario[];
  limitations: string[];
  referenceModelExclusions: string[];
  restorationNote: string;
  confirmationRequired: boolean;
}

export interface TargetActionResult {
  pid: number;
  role: string;
  identity: { bootId: string; pid: number; startTicks: number };
  originalNice: number;
  requestedNice: number;
  observedNice: number | null;
  status: string;
  reason: string | null;
  timestamp: string;
}

export interface BatchActionResult {
  actionId: string;
  experimentId: string;
  recommendationId: string;
  overallStatus: string;
  targets: TargetActionResult[];
  residualRaceDisclosure: string;
  timestamp: string;
}

export interface ActionAudit {
  id: string;
  sessionId: string;
  experimentId: string | null;
  recommendationId: string | null;
  actionType: string;
  targetPid: number;
  targetRole: string;
  targetIdentity: { bootId: string; pid: number; startTicks: number };
  originalNice: number;
  requestedNice: number;
  observedNice: number | null;
  status: string;
  reason: string | null;
  createdAt: string;
}
