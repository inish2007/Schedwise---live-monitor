import type {Identity} from './types';
export interface ShieldCandidate {
 pid:number;name:string;cmdline:string;uid:number|null;nice:number;state:string;cpuPercent:number|null;rssBytes:number|null;
 isImmune:boolean;category:'SYSTEM_ESSENTIAL'|'USER_APPLICATION'|'OTHER_USER_PROCESS';immunityReason:string|null;identity:Identity;threads:number;
}
export interface ShieldedProcess {pid:number;name:string;previousState:string;previousNice:number;actionApplied:string;timestampMs:number;identity:Identity}
export interface ShieldState {
 active:boolean;status:'OFF'|'PREPARING'|'ON'|'RESTORING'|'NEEDS_ATTENTION';operationId:string|null;
 targetPid:number|null;targetName:string|null;strategy:string|null;pinnedCore:number|null;
 affectedProcesses:ShieldedProcess[];activatedAtEpochMs:number;expiresAtEpochMs:number;
 estimatedCpuFreed:null;estimatedRamFreedBytes:null;guardianAvailable:boolean;
 failures:{code:string;message:string;identity:Identity|null;retryable:boolean}[];
 events:{code:string;message:string;timestampMs:number}[];
}
