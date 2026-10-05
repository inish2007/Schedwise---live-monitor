export interface Value<T> {value:T|null;unit:string;kind:'MEASURED'|'DERIVED'|'SIMULATED'|'RECORDED';sourceId:string;timestamp:string;sessionId:string;availability:string;reason:string|null;windowSeconds:number|null}
export interface Identity {bootId:string;pid:number;startTicks:number}
export interface ProcessSample {identity:Identity;name:Value<string>;state:Value<string>;uid:Value<number>;nice:Value<number>;threads:Value<number>;allowedCpus:Value<string>;cpuPercent:Value<number>;role:string;lifecycle:string}
export interface Cpu {busyPercent:Value<number>;stealPercent:Value<number>;counters:Value<number[]>}
export interface Snapshot {sessionId:string;sequence:number;timestamp:string;environmentId:string;availability:string;reason:string|null;cpus:Record<string,Cpu>;processes:ProcessSample[];cpuPressureSome:Value<string>;omittedProcesses:number;unreadableProcesses:number;collectionMillis:number}
export interface Latest {snapshot:Snapshot|null;sampleAgeMillis:number;storageStatus:string}
export interface Capabilities {sessionId:string;sources:Record<string,Value<string|number>>;storage:string}
