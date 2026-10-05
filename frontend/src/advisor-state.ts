import type {Recommendation, BatchActionResult} from './recommendation-types';
export type AdvisorState = 'idle'|'evaluating'|'complete'|'insufficient-evidence'|'failed'|'applying'|'applied'|'partially-applied'|'rejected'|'expired';
export function advisorState(rec:Recommendation|null, result:BatchActionResult|null, operation:string, loading:boolean, error:string|null, now:number):AdvisorState {
  if(loading && operation==='evaluating')return 'evaluating';
  if(loading && operation==='applying')return 'applying';
  if(error)return 'failed';
  if(result)return result.overallStatus==='SUCCESS'?'applied':result.overallStatus==='PARTIAL_SUCCESS'?'partially-applied':'failed';
  if(!rec)return 'idle';
  if(rec.status==='REJECTED')return 'rejected';
  if(rec.status==='APPLIED')return 'applied';
  if(rec.status==='PARTIALLY_APPLIED')return 'partially-applied';
  if(rec.status==='EXPIRED'||rec.status==='ACTIVE'&&now>=Date.parse(rec.expiresAt))return 'expired';
  return rec.status==='INSUFFICIENT_EVIDENCE'?'insufficient-evidence':'complete';
}
export const advisorLabels:Record<AdvisorState,string>={idle:'Ready to evaluate',evaluating:'Evaluating contention…',complete:'Evaluation complete','insufficient-evidence':'More measurements needed',failed:'Request failed',applying:'Applying priority change…',applied:'Change applied','partially-applied':'Some workers could not be changed',rejected:'Recommendation rejected',expired:'Evaluation expired'};
export function measuredNumber(value:number|null|undefined,unit=''):string {
  return value==null||!Number.isFinite(value)?'Unavailable':`${value.toFixed(1)}${unit}`;
}
