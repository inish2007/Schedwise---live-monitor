#!/usr/bin/env python3
"""Explicit bounded Linux M1 test. Starts/stops only its own backend and CPU child."""
import shutil, http.client, json, os, pathlib, re, sqlite3, subprocess, tempfile, time, urllib.error, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
JAVA=os.environ.get('JAVA_HOME','/usr/lib/jvm/java-21-openjdk-amd64')+'/bin/java'
def main():
 with tempfile.TemporaryDirectory(prefix='schedwise-m1-') as directory:
  data=pathlib.Path(directory); log=(data/'backend.log').open('w+')
  shutil.copyfile(ROOT/'backend/target/schedwise-0.1.0.jar',data/'schedwise.jar')
  backend=subprocess.Popen([JAVA,'-Dschedwise.data='+directory,'-jar',str(data/'schedwise.jar'),'--server.port=18080'],stdout=log,stderr=subprocess.STDOUT)
  child=None;top=None
  try:
   token=None
   def api(path,auth=True,origin=None):
    headers={}
    if auth: headers['Authorization']='Bearer '+token
    if origin: headers['Origin']=origin
    with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18080/api/'+path,headers=headers),timeout=5) as r:return json.load(r)
   for _ in range(100):
    if backend.poll() is not None:
     log.seek(0);raise RuntimeError(log.read())
    try:
     token=(data/'session-token').read_text();caps=api('capabilities');break
    except (OSError,urllib.error.URLError):time.sleep(.2)
   else: raise RuntimeError('Backend startup timeout')
   for path in ['capabilities','snapshots/latest','events','sessions/test/export']:
    try:api(path,False);raise AssertionError('Unauthenticated access')
    except urllib.error.HTTPError as e:assert e.code==401
   try:api('capabilities',origin='https://untrusted.example');raise AssertionError('Origin allowed')
   except urllib.error.HTTPError as e:assert e.code==403
   time.sleep(1.2)
   first=api('snapshots/latest');assert first['sampleAgeMillis']<3500
   assert first['snapshot']['cpus']['cpu']['busyPercent']['value'] is not None
   assert first['snapshot']['processes']
   def event(cursor=None):
    conn=http.client.HTTPConnection('127.0.0.1',18080,timeout=5)
    headers={'Authorization':'Bearer '+token}
    if cursor:headers['Last-Event-ID']=cursor
    conn.request('GET','/api/events',headers=headers);res=conn.getresponse();assert res.status==200
    lines=[]
    while True:
     line=res.readline().decode().strip()
     if line.startswith('data: ') and 'snapshot' in line:
      value=json.loads(line[6:]);break
     lines.append(line)
    conn.close();return value
   streamed=event(first['snapshot']['sessionId']+':'+str(first['snapshot']['sequence']))
   assert streamed['snapshot']['sequence']>first['snapshot']['sequence']
   again=event(streamed['snapshot']['sessionId']+':'+str(streamed['snapshot']['sequence']))
   assert again['snapshot']['sequence']>streamed['snapshot']['sequence']
   # One actual hashing child, bounded to six seconds, with independent top intervals.
   child=subprocess.Popen(['/usr/bin/python3','-c',"import hashlib,time; end=time.monotonic()+6; x=b'schedwise';\nwhile time.monotonic()<end: x=hashlib.sha256(x).digest()"],stdout=subprocess.DEVNULL)
   top=subprocess.Popen(['/usr/bin/top','-b','-d','1','-n','6','-p',str(child.pid),'-w','160'],stdout=subprocess.PIPE,text=True,env={**os.environ,'LC_ALL':'C'})
   observed=[];exit_seen=False
   for _ in range(9):
    sample=api('snapshots/latest')['snapshot']
    for p in sample['processes']:
     if p['identity']['pid']==child.pid:
      if p['cpuPercent']['value'] is not None:observed.append({'sequence':sample['sequence'],'cpuPercent':p['cpuPercent']['value'],'windowSeconds':p['cpuPercent']['windowSeconds'],'startTicks':p['identity']['startTicks']})
      if p['lifecycle']=='EXITED':exit_seen=True
    if child.poll() is not None:child.wait()
    time.sleep(1)
   topout=top.communicate(timeout=3)[0]
   top_values=[]
   for line in topout.splitlines():
    f=line.split()
    if f and f[0]==str(child.pid):top_values.append(float(f[8]))
   top_values=top_values[1:] # top's first frame is not the matching interval convention
   assert len(observed)>=3 and max(p['cpuPercent'] for p in observed)>0
   assert top_values and max(top_values)>0
   mid=[p['cpuPercent'] for p in observed[1:-1]]
   assert abs(sum(mid)/len(mid)-sum(top_values)/len(top_values))<35, (mid,top_values)
   assert exit_seen,'Exit tombstone missing'
   time.sleep(1)
   latest=api('snapshots/latest');assert latest['storageStatus']=='AVAILABLE'
   with sqlite3.connect(data/'schedwise.sqlite') as db:
    assert db.execute('pragma user_version').fetchone()[0]==1
    assert db.execute('select count(*) from sessions').fetchone()[0]==1
    assert db.execute('select count(*) from summaries').fetchone()[0]==1
    assert db.execute('select count(*) from captures').fetchone()[0]==0
   evidence={'testedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'kernel':os.uname().release,'allowedCpus':sorted(os.sched_getaffinity(0)),'clockTicks':os.sysconf('SC_CLK_TCK'),'checks':['API authentication','origin rejection','real aggregate CPU','real PID identities','fresh snapshots','SSE disconnect/reconnect','controlled hashing CPU vs top','process exit','SQLite migration and real summary; zero seeded captures'],'childSamples':observed,'topCpuPercentIntervals':top_values,'comparison':'100% = one logical CPU; independent approximately one-second windows, 35 percentage-point tolerance including child startup/exit','scope':caps['sources']['scope'],'schedstatAccounting':caps['sources']['schedstatAccounting']}
   output=ROOT/'docs/m1-integration-evidence.json';output.write_text(json.dumps(evidence,indent=2)+'\n');print(json.dumps(evidence,indent=2))
  finally:
   if top is not None and top.poll() is None:top.terminate();top.wait(timeout=3)
   if child is not None and child.poll() is None:child.terminate();child.wait(timeout=3)
   backend.terminate()
   try:backend.wait(timeout=8)
   except subprocess.TimeoutExpired:backend.kill();backend.wait(timeout=3)
   log.close()
if __name__=='__main__':main()
