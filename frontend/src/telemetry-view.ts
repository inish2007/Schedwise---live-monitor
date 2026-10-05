import type {Identity} from './types.ts';
import type {TimelineSegment} from './simulation-types.ts';
export interface CpuPoint {timestamp:string;sequence:number;value:number|null}
export function identityKey(identity:Identity):string{return `${identity.bootId}:${identity.pid}:${identity.startTicks}`}
export function psiAverage(raw:string|null|undefined):number|null {
  const match=raw?.match(/(?:^|\s)avg10=([\d.]+)(?:\s|$)/);if(!match)return null;
  const n=Number(match[1]);return Number.isFinite(n)&&n>=0&&n<=100?n:null;
}
export function cpuPath(history:CpuPoint[]):string {
  if(history.length===0)return '';
  const start=Date.parse(history[0].timestamp),end=Date.parse(history[history.length-1].timestamp);
  let previous:CpuPoint|undefined;
  return history.map(point=>{
    if(point.value===null||!Number.isFinite(point.value)){previous=undefined;return '';}
    const x=end>start?(Date.parse(point.timestamp)-start)/(end-start)*1000:1000;
    const y=144-Math.min(100,Math.max(0,point.value))*1.28;
    const command=previous&&point.sequence===previous.sequence+1?'L':'M';previous=point;
    return `${command}${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(' ');
}
export function validTimeline(segments:TimelineSegment[]):boolean {
  let end=0;
  for(const segment of segments){
    if(!Number.isFinite(segment.startNs)||!Number.isFinite(segment.endNs)||segment.startNs<end||segment.endNs<=segment.startNs||segment.durationNs!==segment.endNs-segment.startNs)return false;
    end=segment.endNs;
  }return true;
}
export function cpuOptions(mask:string|null|undefined):number[]{
  if(!mask)return [];
  const result=new Set<number>();
  for(const part of mask.split(',')){
    if(!/^\d+(?:-\d+)?$/.test(part.trim()))return [];
    const [start,end=start]=part.trim().split('-').map(Number);
    if(start<0||end<start||end>65535||end-start>4096)return [];
    for(let i=start;i<=end;i++)result.add(i);
    if(result.size>4096)return [];
  }return [...result].sort((a,b)=>a-b);
}
export function phaseDone(name:string,current:string,state:string,phases:string[]):boolean {
  if(['FAILED','CANCELLED','TIMED_OUT','CLEANUP_FAILED'].includes(state))return false;
  return state==='COMPLETED'||phases.indexOf(current)>phases.indexOf(name);
}
