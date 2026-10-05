#!/usr/bin/env python3
"""Unprivileged Shield guardian. JSON-lines on stdin; atomic, bounded recovery journal.
Only this process sends signals, using pidfds. No shell commands or PID-only signals.
"""
import argparse, fcntl, json, os, selectors, signal, sys, time
from pathlib import Path

class Guardian:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.directory.is_symlink() or self.directory.stat().st_uid != os.getuid():
            raise ValueError('Unsafe recovery directory')
        os.chmod(self.directory, 0o700)
        self.lock = os.open(self.directory / 'shield.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.path = self.directory / 'shield-state.json'
        self.boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        self.fds = {}; self.target_fd = None; self.deadline = 0; self.lease = 0
        self.owner = None; self.events = []; self.seen = {}; self.available = False
        self.excluded = {os.getpid()}
        parent = os.getppid()
        while parent > 1 and parent not in self.excluded:
            self.excluded.add(parent)
            try: parent = self.stat(parent)['parent']
            except OSError: break
        self.state = self.idle()
        try:
            fd = os.pidfd_open(os.getpid()); signal.pidfd_send_signal(fd, 0); os.close(fd)
            self.available = True
        except (AttributeError, OSError): pass
        if self.path.exists():
            if self.path.is_symlink() or self.path.stat().st_size > 131072: raise ValueError('Invalid recovery journal')
            old = json.loads(self.path.read_text())
            self.state = old
            self.events = old.get('events', [])[-32:]
            if old.get('active') or old.get('affectedProcesses'):
                self.restore('BACKEND_RESTART')
            else: self.state = self.idle()
        self.save()

    def idle(self):
        return dict(active=False, status='OFF', operationId=None, targetPid=None, targetName=None,
                    strategy=None, pinnedCore=None, affectedProcesses=[], activatedAtEpochMs=0,
                    expiresAtEpochMs=0, estimatedCpuFreed=None, estimatedRamFreedBytes=None,
                    failures=[], events=[], guardianAvailable=False)

    def stat(self, pid):
        raw = Path(f'/proc/{pid}/stat').read_text(); end = raw.rindex(')'); fields = raw[end+2:].split()
        return dict(pid=pid, name=raw[raw.index('(')+1:end], state=fields[0], parent=int(fields[1]),
                    startTicks=int(fields[19]), nice=int(fields[16]))

    def identify(self, identity):
        pid = identity['pid']
        if not isinstance(pid, int) or pid <= 1 or identity['bootId'] != self.boot: raise ValueError('Identity changed')
        st = self.stat(pid)
        if st['startTicks'] != identity['startTicks']: raise ValueError('Identity changed')
        uid = next(x.split()[1] for x in Path(f'/proc/{pid}/status').read_text().splitlines() if x.startswith('Uid:'))
        if int(uid) != os.getuid(): raise PermissionError('Process belongs to another user')
        if st['state'] in ('Z', 'X', 'x'): raise ProcessLookupError('Process exited')
        return st

    def open_verified(self, identity):
        self.identify(identity)
        fd = os.pidfd_open(identity['pid'])
        try: self.identify(identity); signal.pidfd_send_signal(fd, 0); return fd
        except BaseException: os.close(fd); raise

    def eligible(self, identity):
        st = self.identify(identity)
        if st['pid'] in self.excluded or st['state'] in ('T', 't'): raise ValueError('Protected or already stopped process')
        group = Path(f"/proc/{st['pid']}/cgroup").read_text()
        if any(x in group for x in ('/system.slice', '/session.slice')): raise ValueError('System/session process')
        return st

    def event(self, code, message):
        self.events.append(dict(code=code, message=message, timestampMs=int(time.time()*1000)))
        self.events = self.events[-32:]

    def save(self):
        self.state['guardianAvailable'] = self.available
        self.state['events'] = self.events
        encoded = json.dumps(self.state).encode()
        if len(encoded) > 131072: raise ValueError('Recovery journal too large')
        temp = self.directory / 'shield-state.tmp'
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'wb') as stream: stream.write(encoded); stream.flush(); os.fsync(stream.fileno())
        os.replace(temp, self.path)
        fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(fd)
        finally: os.close(fd)

    def restore(self, reason):
        self.state['status'] = 'RESTORING'; self.save()
        pending = []; failures = []
        for item in self.state.get('affectedProcesses', []):
            identity = item['identity']; pid = identity['pid']; fd = self.fds.get(pid)
            try:
                try: st = self.identify(identity)
                except (FileNotFoundError, ProcessLookupError, ValueError): continue
                if fd is None: fd = self.open_verified(identity); self.fds[pid] = fd
                # Never overwrite a nice/affinity change; this guardian only owns pauses.
                signal.pidfd_send_signal(fd, signal.SIGCONT)
                for _ in range(20):
                    try: st = self.identify(identity)
                    except (FileNotFoundError, ProcessLookupError, ValueError): break
                    if st['state'] not in ('T', 't'): break
                    time.sleep(.01)
                else: raise RuntimeError('Resume readback timed out')
            except Exception as exc:
                pending.append(item)
                failures.append(dict(code='RESTORE_FAILED', message=str(exc), identity=identity, retryable=True))
        self.state['affectedProcesses'] = pending; self.state['failures'] = failures
        self.state['active'] = bool(pending); self.state['status'] = 'NEEDS_ATTENTION' if pending else 'OFF'
        self.event(reason, 'Restoration needs attention' if pending else 'Selected processes restored or exited')
        for pid in list(self.fds):
            if not any(p['identity']['pid'] == pid for p in pending): os.close(self.fds.pop(pid))
        if self.target_fd is not None: os.close(self.target_fd); self.target_fd = None
        self.save()

    def activate(self, req):
        op = req['operationId']; owner = req['ownerId']
        if op in self.seen:
            if self.seen[op] != req: raise ValueError('Operation ID reused with different request')
            return
        if self.state['active']: raise ValueError('Shield active or restoration pending')
        if not self.available: raise ValueError('pidfd guardian unavailable')
        targets = req['targets']; duration = req['maxSeconds']
        if not isinstance(duration, int) or not 1 <= duration <= 1800: raise ValueError('Invalid duration')
        if not 1 <= len(targets) <= 16: raise ValueError('Select 1–16 processes')
        target = req['targetIdentity']; target_stat = self.eligible(target)
        pids = {target['pid']}; enrolled = []
        for selection in targets:
            identity = selection['identity']; st = self.eligible(identity)
            if st['pid'] in pids or st['nice'] != selection['expectedNice']: raise ValueError('Duplicate or stale selection')
            pids.add(st['pid'])
            enrolled.append(dict(pid=st['pid'],name=st['name'],previousState=st['state'],previousNice=st['nice'],
                                 actionApplied='SUSPEND',timestampMs=int(time.time()*1000),identity=identity))
        self.target_fd = self.open_verified(target)
        try:
            for item in enrolled: self.fds[item['pid']] = self.open_verified(item['identity'])
        except BaseException:
            for fd in self.fds.values(): os.close(fd)
            self.fds.clear(); os.close(self.target_fd); self.target_fd = None; raise
        self.owner = owner; self.deadline = time.monotonic()+duration; self.lease = time.monotonic()+60
        self.state = self.idle()
        self.state.update(active=True,status='PREPARING',operationId=op,targetPid=target['pid'],targetName=target_stat['name'],
                          strategy='SUSPEND',activatedAtEpochMs=int(time.time()*1000),expiresAtEpochMs=int((time.time()+duration)*1000))
        self.seen[op] = req
        if len(self.seen)>128: del self.seen[next(iter(self.seen))]
        try:
            for item in enrolled:
                st = self.eligible(item['identity'])
                if st['nice'] != item['previousNice']: raise ValueError('Nice changed before action')
                self.state['affectedProcesses'].append(item); self.save()  # durable intent before syscall
                signal.pidfd_send_signal(self.fds[item['pid']], signal.SIGSTOP)
                for _ in range(20):
                    if self.identify(item['identity'])['state'] == 'T': break
                    time.sleep(.01)
                else: raise RuntimeError('Stop readback timed out')
            self.state['status'] = 'ON'; self.event('ACTIVATED', 'Explicitly selected processes paused'); self.save()
        except BaseException as exc:
            self.event('ACTIVATION_FAILED', str(exc)); self.restore('ROLLBACK')
            raise

    def handle(self, req):
        action = req.get('action')
        if action == 'activate': self.activate(req)
        elif action == 'stop':
            if req.get('sessionId') != self.state.get('operationId'): raise ValueError('Shield session changed')
            if self.state['active']: self.restore('USER_STOP')
        elif action == 'heartbeat':
            if req.get('ownerId') != self.owner or req.get('sessionId') != self.state.get('operationId'): raise ValueError('Lease belongs to another dashboard')
            if self.state['status'] == 'ON': self.lease = time.monotonic()+60
        elif action != 'status': raise ValueError('Unknown action')
        return self.state

    def tick(self):
        if self.state['status'] != 'ON': return
        reason = None
        if time.monotonic() >= self.deadline: reason = 'TIMEOUT'
        elif time.monotonic() >= self.lease: reason = 'DASHBOARD_LOST'
        elif self.target_fd is not None:
            poll = selectors.DefaultSelector()
            try:
                poll.register(self.target_fd, selectors.EVENT_READ)
                if poll.select(0): reason = 'TARGET_EXITED'
            finally: poll.close()
        if reason: self.restore(reason)

    def close(self):
        if self.state['active']: self.restore('BACKEND_DISCONNECTED')
        for fd in self.fds.values(): os.close(fd)
        if self.target_fd is not None: os.close(self.target_fd)
        os.close(self.lock)

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--data', required=True); parser.add_argument('--recover', action='store_true'); args=parser.parse_args()
    os.umask(0o077)
    if os.getsid(0) != os.getpid(): os.setsid()
    guardian=Guardian(args.data)
    if args.recover:
        print(json.dumps(guardian.state)); guardian.close(); return
    def terminate(_sig, _frame): raise SystemExit()
    signal.signal(signal.SIGTERM, terminate); signal.signal(signal.SIGINT, terminate)
    print(json.dumps({'state':guardian.state}), flush=True)
    selector=selectors.DefaultSelector(); selector.register(sys.stdin,selectors.EVENT_READ); buffer=b''
    try:
        while True:
            guardian.tick()
            if not selector.select(.25): continue
            chunk=os.read(sys.stdin.fileno(),16384)
            if not chunk: break
            buffer+=chunk
            if len(buffer)>65536: raise ValueError('Request too large')
            while b'\n' in buffer:
                line,buffer=buffer.split(b'\n',1)
                try: response={'state':guardian.handle(json.loads(line))}
                except Exception as exc: response={'error':str(exc),'state':guardian.state}
                print(json.dumps(response),flush=True)
    finally: selector.close(); guardian.close()

if __name__=='__main__': main()
