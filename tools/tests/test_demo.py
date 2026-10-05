import asyncio
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

DEMO = Path(__file__).resolve().parents[1] / 'demo'
sys.path.insert(0, str(DEMO))
import common
from load_client import run as load, exchange
from run_experiment import Run, nearest_rank, summarize


class DemoTests(unittest.TestCase):
    def setUp(self):
        common.STOP = False
        self.core = min(os.sched_getaffinity(0))

    def test_finite_worker_executes_exact_work_and_digest(self):
        with subprocess.Popen(['/usr/bin/taskset', '-c', str(self.core), '/usr/bin/python3', '-u', str(DEMO/'worker.py')], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as child:
            config = {'parentPid': os.getpid(), 'allowedCpus': [self.core], 'originNs': time.monotonic_ns(),
                      'deadlineNs': time.monotonic_ns()+3_000_000_000, 'hashBudget': 5000}
            output, _ = child.communicate(json.dumps(config)+'\n', timeout=4)
            self.assertEqual(child.returncode, 0)
        events = [json.loads(line) for line in output.splitlines()]
        progress = [e for e in events if e['event'] == 'worker_progress'][-1]
        value = bytes(32)
        for _ in range(5000):
            value = hashlib.sha256(value+bytes(range(256))*4).digest()
        self.assertEqual(progress['hashes'], 5000)
        self.assertEqual(progress['digest'], value.hex())
        self.assertGreater(progress['cpuNs'], 0)
        self.assertEqual(events[-1]['outcome'], 'COMPLETED')

    def test_service_digest_and_real_cpu_instrumentation(self):
        child = subprocess.Popen(['/usr/bin/taskset', '-c', str(self.core), '/usr/bin/python3', '-u', str(DEMO/'service.py')], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            config = {'parentPid': os.getpid(), 'allowedCpus': [self.core], 'originNs': time.monotonic_ns(),
                      'deadlineNs': time.monotonic_ns()+3_000_000_000, 'iterations': 1000, 'token': 'test-only-secret'}
            child.stdin.write(json.dumps(config)+'\n'); child.stdin.flush()
            ready = json.loads(child.stdout.readline())
            result = asyncio.run(asyncio.wait_for(exchange(ready['port'], config['token'], 'unit-1', 'CALIBRATION'), 2))
            self.assertEqual(result['digest'], common.hash_work(1000).hex())
            self.assertGreater(result['cpuServiceNs'], 0)
            self.assertGreaterEqual(result['arrivalOffsetNs'], 0)
        finally:
            child.terminate(); child.communicate(timeout=3)
        self.assertIsNotNone(child.returncode)

    def test_fixed_rate_logs_skips_under_a_real_slow_socket(self):
        async def scenario():
            async def slow(reader, writer):
                try:
                    await reader.read(8192)
                    await asyncio.sleep(.4)
                finally:
                    writer.close()
            server = await asyncio.start_server(slow, '127.0.0.1', 0)
            async with server:
                start = time.monotonic_ns()+30_000_000
                config = {'port': server.sockets[0].getsockname()[1], 'token': 'test-only', 'rateHz': 40,
                          'intervalNs': 25_000_000, 'maxInFlight': 1, 'timeoutMs': 100, 'deadlineMs': 50,
                          'iterations': 1000, 'expectedDigest': '', 'deadlineNs': start+2_000_000_000,
                          'phases': [{'name': 'BASELINE', 'startNs': start, 'endNs': start+250_000_000}]}
                with contextlib.redirect_stdout(io.StringIO()) as output:
                    await load(config)
                return [json.loads(line) for line in output.getvalue().splitlines()]
        events = asyncio.run(scenario())
        requests = [e for e in events if e['event'] == 'request']
        self.assertEqual(len(requests), 10)
        self.assertEqual(len({e['scheduledNs'] for e in requests}), 10)
        self.assertTrue(any(e['outcome'] == 'CAPACITY_SKIP' for e in requests))
        self.assertTrue(any(e['outcome'] == 'TIMEOUT' for e in requests))
        self.assertTrue(all(e['deadlineMiss'] for e in requests))

    def _lifecycle(self, mode):
        read_fd, write_fd = os.pipe()
        with tempfile.TemporaryDirectory() as directory:
            run = Run({'id': 'real-lifecycle-test', 'directory': directory, 'originNs': time.monotonic_ns(),
                       'maxSeconds': 1.5 if mode == 'timeout' else 5, 'core': self.core,
                       'observerCpus': [self.core]}, control_fd=read_fd)
            original = subprocess.Popen
            timer = None
            def launch(*args, **kwargs):
                if mode == 'partial' and str(args[0][-1]).endswith('load_client.py'):
                    raise OSError('test-injected launch failure after real service startup')
                return original(*args, **kwargs)
            if mode == 'stop':
                timer = threading.Timer(.5, lambda: os.write(write_fd, b'STOP\n'))
                timer.start()
            try:
                with contextlib.redirect_stdout(io.StringIO()), patch('run_experiment.subprocess.Popen', launch):
                    summary = run.execute()
                self.assertEqual(summary['state'], {'timeout': 'TIMED_OUT', 'partial': 'FAILED', 'stop': 'CANCELLED'}[mode])
                self.assertTrue(summary['cleanup']['verified'])
                self.assertGreaterEqual(len(run.children), 1)
                self.assertTrue(all(c['process'].poll() is not None for c in run.children))
                self.assertTrue(Path(directory, 'events.jsonl').stat().st_size > 0)
            finally:
                if timer:
                    timer.cancel(); timer.join()
                os.close(read_fd); os.close(write_fd)

    def test_partial_startup_cleans_real_service(self): self._lifecycle('partial')
    def test_timeout_cleans_registered_children(self): self._lifecycle('timeout')
    def test_emergency_stop_cleans_registered_children(self): self._lifecycle('stop')

    def test_percentiles_and_no_observations(self):
        self.assertEqual(nearest_rank(list(range(1, 21)), .95), 19)
        empty = summarize([], 'test', 'BASELINE', 0)
        self.assertIsNone(empty['p95Ms']['value'])
        self.assertIsNone(empty['deadlineMissRate']['value'])
        self.assertIsNone(empty['offeredRateHz']['value'])


if __name__ == '__main__':
    unittest.main()
