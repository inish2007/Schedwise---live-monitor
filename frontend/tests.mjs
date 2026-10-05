import ts from 'typescript';
import {mkdtempSync,readFileSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
const dir=mkdtempSync(join(tmpdir(),'schedwise-tests-'));
try {
 writeFileSync(join(dir,'package.json'),'{"type":"module"}');
 for(const file of ['types','stream','stream.test','telemetry-view','telemetry-view.test','http','http.test','advisor-state','advisor-state.test']) {
  const source=readFileSync(new URL(`./src/${file}.ts`,import.meta.url),'utf8').replaceAll("./types.ts","./types.js").replaceAll("./stream.ts","./stream.js").replaceAll("./telemetry-view.ts","./telemetry-view.js").replaceAll("./http.ts","./http.js").replaceAll("./advisor-state.ts","./advisor-state.js");
  writeFileSync(join(dir,`${file}.js`),ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText);
 }
 const result=spawnSync(process.execPath,['--test',join(dir,'stream.test.js'),join(dir,'telemetry-view.test.js'),join(dir,'http.test.js'),join(dir,'advisor-state.test.js')],{stdio:'inherit'});
 process.exitCode=result.status??1;
} finally {rmSync(dir,{recursive:true,force:true});}
