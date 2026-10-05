import importlib.util, json, os, signal, subprocess, sys, tempfile, time, unittest, uuid
from pathlib import Path
from unittest.mock import patch
SCRIPT=Path(__file__).resolve().parents[2]/'backend/src/main/resources/shield/guardian.py'
spec=importlib.util.spec_from_file_location('guardian',SCRIPT);mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)

class GuardianTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.children=[subprocess.Popen(['/bin/sleep','30']) for _ in range(4)]
  self.g=mod.Guardian(self.temp.name)
 def tearDown(self):
  self.g.close()
  for child in self.children: child.kill(); child.wait()
  self.temp.cleanup()
 def identity(self,index):
  st=self.g.stat(self.children[index].pid);return dict(bootId=self.g.boot,pid=st['pid'],startTicks=st['startTicks'])
 def request(self,indices=(1,),seconds=5):
  return dict(action='activate',operationId=str(uuid.uuid4()),ownerId=str(uuid.uuid4()),targetIdentity=self.identity(0),targets=[dict(identity=self.identity(i),expectedNice=0) for i in indices],maxSeconds=seconds)
 def wait_running(self,index):
  end=time.monotonic()+2
  while self.g.stat(self.children[index].pid)['state']=='T' and time.monotonic()<end:time.sleep(.01)
  self.assertNotEqual('T',self.g.stat(self.children[index].pid)['state'])
 def test_selected_only_and_idempotency(self):
  req=self.request();self.g.activate(req);self.g.activate(req)
  self.assertEqual('T',self.g.stat(self.children[1].pid)['state']);self.wait_running(2)
  self.g.restore('TEST');self.wait_running(1);self.assertFalse(self.g.state['active'])
 def test_invalid_identity_and_existing_stop_rejected(self):
  req=self.request();req['targets'][0]['identity']['startTicks']+=1
  with self.assertRaises(ValueError):self.g.activate(req)
  self.wait_running(1)
  self.children[1].send_signal(signal.SIGSTOP)
  for _ in range(100):
   if self.g.stat(self.children[1].pid)['state']=='T':break
   time.sleep(.005)
  with self.assertRaises(ValueError):self.g.activate(self.request())
  self.children[1].send_signal(signal.SIGCONT)
 def test_partial_failure_rolls_back(self):
  original=mod.signal.pidfd_send_signal;stops=0
  def send(fd,sig):
   nonlocal stops
   if sig==signal.SIGSTOP:
    stops+=1
    if stops==2:raise PermissionError('injected stop failure')
   return original(fd,sig)
  with patch.object(mod.signal,'pidfd_send_signal',send):
   with self.assertRaises(PermissionError):self.g.activate(self.request((1,2)))
  self.wait_running(1);self.wait_running(2);self.assertFalse(self.g.state['active'])
 def test_restore_failure_retains_only_pending_and_retry_works(self):
  self.g.activate(self.request((1,2)));bad=self.g.fds[self.children[1].pid];original=mod.signal.pidfd_send_signal
  def send(fd,sig):
   if fd==bad and sig==signal.SIGCONT:raise PermissionError('injected restore failure')
   return original(fd,sig)
  with patch.object(mod.signal,'pidfd_send_signal',send):self.g.restore('TEST')
  self.assertEqual('NEEDS_ATTENTION',self.g.state['status']);self.assertEqual(1,len(self.g.state['affectedProcesses']));self.wait_running(2)
  self.g.restore('RETRY');self.wait_running(1)
 def test_lease_and_timeout_and_target_exit(self):
  for cause in ('lease','deadline','target'):
   self.g.activate(self.request())
   if cause=='lease':self.g.lease=0
   elif cause=='deadline':self.g.deadline=0
   else:self.children[0].terminate();self.children[0].wait()
   self.g.tick();self.wait_running(1);self.assertFalse(self.g.state['active'])
 def test_wrong_owner_cannot_extend_lease(self):
  req=self.request();self.g.activate(req);before=self.g.lease
  with self.assertRaises(ValueError):self.g.handle(dict(action='heartbeat',sessionId=req['operationId'],ownerId=str(uuid.uuid4())))
  self.assertEqual(before,self.g.lease)
 def test_backend_pipe_eof_and_crash_journal_recovery(self):
  # Separate guardian processes exercise the actual IPC and persistent journal.
  for crash in (False,True):
   with tempfile.TemporaryDirectory() as directory:
    proc=subprocess.Popen([sys.executable,'-u',str(SCRIPT),'--data',directory],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    try:
     json.loads(proc.stdout.readline());req=self.request();proc.stdin.write(json.dumps(req)+'\n');proc.stdin.flush()
     self.assertEqual('ON',json.loads(proc.stdout.readline())['state']['status'])
     if crash:proc.kill()
     else:proc.stdin.close()
     proc.wait(timeout=4)
     if crash:
      repaired=subprocess.run([sys.executable,str(SCRIPT),'--data',directory,'--recover'],capture_output=True,text=True,timeout=4)
      self.assertEqual(0,repaired.returncode,repaired.stderr)
     self.wait_running(1)
    finally:
     if proc.poll() is None:proc.kill();proc.wait()
     for stream in (proc.stdin,proc.stdout,proc.stderr):
      if stream and not stream.closed:stream.close()
 def test_corrupt_journal_and_symlink_fail_closed(self):
  with tempfile.TemporaryDirectory() as directory:
   Path(directory,'shield-state.json').write_text('invalid')
   with self.assertRaises(json.JSONDecodeError):mod.Guardian(directory)
  with tempfile.TemporaryDirectory() as directory:
   Path(directory,'shield-state.json').symlink_to('/dev/null')
   with self.assertRaises(ValueError):mod.Guardian(directory)

if __name__=='__main__':unittest.main()
