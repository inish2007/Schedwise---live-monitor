import type { Value } from './types.ts';
export function display(v:Value<string|number>|undefined):string {return !v||v.value===null?'Unavailable'+(v?.reason?` · ${v.reason}`:''):typeof v.value==='number'?v.value.toFixed(Number.isInteger(v.value)?0:1):v.value;}
export function fresh(age:number):boolean {return age>=0&&age<=3500;}
export function accept(previous:{sessionId:string;sequence:number}|null,next:{sessionId:string;sequence:number}):boolean {return !previous||previous.sessionId!==next.sessionId||next.sequence>previous.sequence;}
export function parseEvent(block:string):{event:string;id:string;data:string} {let event='message',id='';const data:string[]=[];for(const line of block.split('\n')){if(line.startsWith('event:'))event=line.slice(6).trim();if(line.startsWith('id:'))id=line.slice(3).trim();if(line.startsWith('data:'))data.push(line.slice(5).trimStart());}return {event,id,data:data.join('\n')};}
export async function readEvents(response:Response,onEvent:(event:ReturnType<typeof parseEvent>)=>void):Promise<void> {
 if(!response.body)throw new Error('Streaming response unavailable');const reader=response.body.getReader();const decoder=new TextDecoder();let buffer='';
 try{while(true){const {value,done}=await reader.read();if(done)break;buffer=(buffer+decoder.decode(value,{stream:true})).replace(/\r\n/g,'\n');if(buffer.length>16*1024*1024)throw new Error('Stream event exceeds limit');let end:number;while((end=buffer.indexOf('\n\n'))>=0){onEvent(parseEvent(buffer.slice(0,end)));buffer=buffer.slice(end+2);}}}finally{await reader.cancel().catch(()=>{});reader.releaseLock();}
}
