import {test} from 'node:test';
import assert from 'node:assert/strict';
import {advisorState,measuredNumber} from './advisor-state.ts';
import type {Recommendation,BatchActionResult} from './recommendation-types';
// Test-only fixtures: never imported by product code.
const rec={status:'ACTIVE',expiresAt:new Date(1000).toISOString()} as Recommendation;
const result=(status:string)=>({overallStatus:status} as BatchActionResult);
test('advisor never presents unavailable telemetry as measured zero',()=>{
 assert.equal(measuredNumber(0,'%'),'0.0%');assert.equal(measuredNumber(null,'%'),'Unavailable');assert.equal(measuredNumber(undefined),'Unavailable');assert.equal(measuredNumber(NaN),'Unavailable');
});
test('advisor handles loading, missing evidence, expiry and terminal decisions',()=>{
 const state=(recommendation:Recommendation|null,op='idle',loading=false,error:string|null=null,now=0)=>advisorState(recommendation,null,op,loading,error,now);
 assert.equal(state(null),'idle');assert.equal(state(null,'evaluating',true),'evaluating');
 assert.equal(state(rec),'complete');assert.equal(state(rec,'idle',false,null,1000),'expired');
 assert.equal(state({...rec,status:'INSUFFICIENT_EVIDENCE'}),'insufficient-evidence');
 assert.equal(state({...rec,status:'REJECTED'},'idle',false,null,2000),'rejected');
 assert.equal(state({...rec,status:'APPLIED'},'idle',false,null,2000),'applied');
 assert.equal(state(rec,'applying',true),'applying');assert.equal(state(rec,'idle',false,'Connection lost'),'failed');
});
test('partial and failed readbacks never appear as a successful application',()=>{
 assert.equal(advisorState(rec,result('SUCCESS'),'idle',false,null,0),'applied');
 assert.equal(advisorState(rec,result('PARTIAL_SUCCESS'),'idle',false,null,0),'partially-applied');
 assert.equal(advisorState(rec,result('FAILED'),'idle',false,null,0),'failed');
});
