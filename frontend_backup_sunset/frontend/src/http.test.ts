import {test} from 'node:test';
import assert from 'node:assert/strict';
import {AuthenticationError,requireAuthentication,apiError} from './http.ts';
test('401 is terminal authentication failure, not an ordinary retry',()=>{
 assert.throws(()=>requireAuthentication(new Response(null,{status:401})),AuthenticationError);
 assert.doesNotThrow(()=>requireAuthentication(new Response(null,{status:503})));
});
test('API error renders server detail or status without HTML injection',async()=>{
 assert.equal(await apiError(new Response(JSON.stringify({error:'Identity changed'}),{status:409})),'Identity changed');
 assert.equal(await apiError(new Response('<html>error</html>',{status:500})),'Request failed (500)');
});
