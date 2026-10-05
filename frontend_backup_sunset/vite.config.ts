import {defineConfig} from 'vite';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
// Credential stays in the trusted local dev server, never in the browser bundle.
export default defineConfig({server:{host:'127.0.0.1',port:5173,strictPort:true,allowedHosts:['localhost','127.0.0.1'],proxy:{'/api':{
  target:'http://127.0.0.1:8080',
  configure(proxy){proxy.on('proxyReq',(outgoing,request)=>{
    if(request.url==='/api/session/bootstrap'&&request.method==='POST'){
      const origin=request.headers.origin; const host=request.headers.host;
      const local=(host==='localhost:5173'||host==='127.0.0.1:5173')&&(origin==='http://localhost:5173'||origin==='http://127.0.0.1:5173');
      if(local&&request.headers['sec-fetch-site']!=='cross-site'){
        try{outgoing.setHeader('Authorization',`Bearer ${readFileSync(fileURLToPath(new URL('../data/session-token',import.meta.url)),'utf8').trim()}`)}catch{/* Backend reports unavailable authentication. */}
      }
    }
  })}
}}}});
