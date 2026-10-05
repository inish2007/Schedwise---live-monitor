import {test} from 'node:test';
import assert from 'node:assert/strict';
import {cpuOptions,cpuPath,identityKey,phaseDone,psiAverage,validTimeline} from './telemetry-view.ts';
const point=(sequence:number,value:number|null)=>({sequence,value,timestamp:new Date(sequence*1000).toISOString()});
test('CPU trace preserves measured zero and breaks across missing observations',()=>{
 const path=cpuPath([point(1,0),point(2,null),point(3,50),point(5,100),point(6,25)]);
 assert.equal((path.match(/M/g)||[]).length,3);assert.equal((path.match(/L/g)||[]).length,1);
 assert.match(path,/M0.00,144.00/);assert.match(path,/L1000.00,112.00/);
 assert.equal(cpuPath([point(1,null)]).trim(),'');
});
test('pressure distinguishes missing source from measured zero',()=>{
 assert.equal(psiAverage(null),null);assert.equal(psiAverage('avg10=0.00 avg60=0.00'),0);
 assert.equal(psiAverage('avg10=10.25 avg60=3.00'),10.25);
 assert.equal(psiAverage('avg10=101'),null);assert.equal(psiAverage('avg10=1.2.3'),null);
});
test('affinity options use only a valid observed CPU mask',()=>{
 assert.deepEqual(cpuOptions(null),[]);assert.deepEqual(cpuOptions('2-4,7,3'),[2,3,4,7]);
 for(const invalid of ['4-2','x','0-99999','-1'])assert.deepEqual(cpuOptions(invalid),[]);
});
test('PID reuse and boot changes cannot select the previous process identity',()=>{
 const identity={bootId:'boot-a',pid:12,startTicks:40};
 assert.notEqual(identityKey(identity),identityKey({...identity,startTicks:41}));
 assert.notEqual(identityKey(identity),identityKey({...identity,bootId:'boot-b'}));
});
test('modeled timeline permits idle gaps but rejects overlap or false durations',()=>{
 const segment={jobId:'test',role:'PROTECTED_SERVICE',startNs:10,endNs:20,durationNs:10,reason:'complete'};
 assert.equal(validTimeline([segment,{...segment,startNs:30,endNs:40}]),true);
 assert.equal(validTimeline([segment,{...segment,startNs:15,endNs:25}]),false);
 assert.equal(validTimeline([{...segment,durationNs:11}]),false);
 assert.equal(validTimeline([{...segment,endNs:Infinity}]),false);
});
test('cancelled or failed trials never mark planned phases as completed',()=>{
 const phases=['BASELINE','CONTENTION','AFTER_ACTION','FINISHED'];
 assert.equal(phaseDone('AFTER_ACTION','FINISHED','CANCELLED',phases),false);
 assert.equal(phaseDone('AFTER_ACTION','FINISHED','FAILED',phases),false);
 assert.equal(phaseDone('AFTER_ACTION','CONTENTION','RUNNING',phases),false);
 assert.equal(phaseDone('BASELINE','CONTENTION','RUNNING',phases),true);
});
