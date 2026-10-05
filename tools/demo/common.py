"""Shared bounded instrumentation. All runtime metrics are observed, never synthesized."""
import ctypes
import hashlib
import json
import os
from pathlib import Path
import resource
import signal
import sys
import time

BUFFER = bytes(range(256)) * 4
STOP = False


def stop(*_):
    global STOP
    STOP = True


def read_config():
    # No secret-bearing arguments, URL query strings, or environment variables.
    line = b''
    while not line.endswith(b'\n') and len(line) <= 8192:
        byte = os.read(sys.stdin.fileno(), 1)
        if not byte:
            raise EOFError('launcher closed configuration pipe')
        line += byte
    if len(line) > 8192:
        raise ValueError('configuration exceeds bound')
    config = json.loads(line)
    expected_parent = config['parentPid']
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(1, signal.SIGTERM, 0, 0, 0) != 0:  # Linux PR_SET_PDEATHSIG
        raise OSError(ctypes.get_errno(), 'parent-death signal unavailable')
    if os.getppid() != expected_parent:
        raise RuntimeError('launcher exited during startup')
    resource.setrlimit(resource.RLIMIT_AS, (256 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_CPU, (175, 176))
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    if sorted(os.sched_getaffinity(0)) != config['allowedCpus']:
        raise RuntimeError('affinity does not match launch configuration')
    return config


def emit(event, **fields):
    print(json.dumps({'event': event, 'kind': 'MEASURED', 'monotonicNs': time.monotonic_ns(),
                      'timestamp': time.time_ns(), **fields}, separators=(',', ':')), flush=True)


def identity(pid):
    raw = Path(f'/proc/{pid}/stat').read_text()
    fields = raw[raw.rfind(')') + 1:].split()
    return {'bootId': Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
            'pid': pid, 'startTicks': int(fields[19])}


def metadata(pid):
    status = dict(line.split(':', 1) for line in Path(f'/proc/{pid}/status').read_text().splitlines() if ':' in line)
    raw = Path(f'/proc/{pid}/stat').read_text()
    fields = raw[raw.rfind(')') + 1:].split()
    try:
        autogroup = Path(f'/proc/{pid}/autogroup').read_text().strip()
    except OSError:
        autogroup = None
    return {'identity': identity(pid), 'uid': int(status['Uid'].split()[0]),
            'parentPid': int(fields[1]), 'threads': int(fields[17]), 'nice': int(fields[16]),
            'allowedCpus': sorted(os.sched_getaffinity(pid)),
            'cgroup': Path(f'/proc/{pid}/cgroup').read_text().strip(), 'autogroup': autogroup}


def hash_work(iterations, seed=bytes(32)):
    value = seed
    for _ in range(iterations):
        value = hashlib.sha256(value + BUFFER).digest()
    return value
