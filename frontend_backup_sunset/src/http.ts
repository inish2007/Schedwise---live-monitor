export class AuthenticationError extends Error {}
export function requireAuthentication(response:Response):void {
  if(response.status===401)throw new AuthenticationError('Local session expired. Reconnecting securely…');
}
export async function apiError(response:Response):Promise<string>{
  try{const value:unknown=await response.json();if(value&&typeof value==='object'&&'error' in value&&typeof value.error==='string')return value.error}catch{/* No structured body. */}
  return `Request failed (${response.status})`;
}
/** Deadline bounds headers/network wait; caller AbortSignal also cancels streaming reads. */
export async function apiFetch(input:RequestInfo|URL,init:RequestInit={}):Promise<Response>{
  const controller=new AbortController();const timeout=setTimeout(()=>controller.abort(new Error('Request timed out; check connection and current state before retrying')),12000);
  try{
    const response=await fetch(input,{...init,credentials:'same-origin',signal:init.signal?AbortSignal.any([init.signal,controller.signal]):controller.signal});
    if(response.status===401)window.dispatchEvent(new Event('schedwise-auth-expired'));
    return response;
  }finally{clearTimeout(timeout)}
}
async function establishSession(launch?:string):Promise<string>{
  const existing=await fetch('/api/session',{credentials:'same-origin',signal:AbortSignal.timeout(8000)});
  if(existing.ok)return (await existing.json() as {csrfToken:string}).csrfToken;
  const response=await fetch('/api/session/bootstrap',{method:'POST',credentials:'same-origin',headers:launch?{'X-Launch-Bootstrap':launch}:{},signal:AbortSignal.timeout(8000)});
  if(!response.ok)throw new Error(response.status===401?'Open SchedWise using the local launcher to connect.':'Local connection unavailable. Check that the backend is running.');
  return (await response.json() as {csrfToken:string}).csrfToken;
}

let connecting:Promise<string>|null=null;
export function connectSession(launch?:string):Promise<string>{
 if(!connecting)connecting=establishSession(launch).finally(()=>{connecting=null});
 return connecting;
}
