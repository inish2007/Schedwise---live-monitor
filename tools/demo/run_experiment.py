#!/usr/bin/env python3
"""Real experiment coordinator, launched by Java; no shell or priority changes."""
import asyncio
import json
import math
import os
from pathlib import Path
import secrets
import selectors
import signal
import statistics
import subprocess
import sys
import time
import common
from load_client import exchange

MAX_EVIDENCE_BYTES = 12 * 1024 * 1024


class Cancelled(Exception):
    pass


class Deadline(Exception):
    pass


def nearest_rank(values, quantile):
    return sorted(values)[math.ceil(quantile * len(values))-1] if values else None


def metric(value, unit, experiment_id, phase, seconds, reason=None):
    return {'value': value, 'unit': unit, 'kind': 'DERIVED', 'sourceId': f'{experiment_id}/events.jsonl:{phase}',
            'timestamp': time.time_ns(), 'sessionId': experiment_id, 'windowSeconds': seconds,
            'availability': 'AVAILABLE' if value is not None else 'UNAVAILABLE',
            'reason': reason if value is None else None}


def summarize(rows, experiment_id, phase, seconds):
    successes = [r for r in rows if r['outcome'] == 'OK']
    latency = [r['latencyNs']/1e6 for r in successes]
    delays = [r['dispatchDelayNs']/1e6 for r in rows if r['dispatchDelayNs'] is not None]
    errors = sum(r['outcome'] in ('TIMEOUT', 'ERROR', 'CANCELLED') for r in rows)
    skips = sum(r['outcome'] in ('CAPACITY_SKIP', 'MISSED_DISPATCH') for r in rows)
    misses = sum(r['deadlineMiss'] for r in rows)
    make = lambda v, u, why='NO_OBSERVATIONS': metric(v, u, experiment_id, phase, seconds, why)
    return {'scheduledCount': len(rows), 'successCount': len(successes), 'errorCount': errors,
            'timeoutCount': sum(r['outcome'] == 'TIMEOUT' for r in rows), 'skippedCount': skips,
            'missedDispatchCount': sum(r['outcome'] == 'MISSED_DISPATCH' for r in rows),
            'deadlineMissCount': misses,
            'p50Ms': make(nearest_rank(latency, .5), 'ms'),
            'p95Ms': make(nearest_rank(latency, .95) if len(latency) >= 20 else None, 'ms', 'FEWER_THAN_20_SUCCESSES'),
            'p99Ms': make(nearest_rank(latency, .99) if len(latency) >= 100 else None, 'ms', 'FEWER_THAN_100_SUCCESSES'),
            'dispatchDelayP95Ms': make(nearest_rank(delays, .95), 'ms'),
            'offeredRateHz': make(len(rows)/seconds if seconds > 0 else None, 'requests/s'),
            'completedRateHz': make(len(successes)/seconds if seconds > 0 else None, 'successful cohort requests/s'),
            'errorRate': make(errors/len(rows) if rows else None, 'fraction of scheduled'),
            'deadlineMissRate': make(misses/len(rows) if rows else None, 'fraction of scheduled')}


class Run:
    def __init__(self, config, control_fd=None):
        self.config = config
        self.directory = Path(config['directory'])
        self.directory.mkdir(parents=True, exist_ok=True)
        self.journal = (self.directory/'events.jsonl').open('x', encoding='utf-8')
        self.bytes_written = 0
        self.selector = selectors.DefaultSelector()
        self.children = []
        self.requests = []
        self.worker_events = {}
        self.revision = 0
        self.phase = 'CALIBRATION'
        self.state = 'RUNNING'
        self.reason = None
        self.frozen = None
        self.phases = []
        self.origin = config['originNs']
        self.deadline = self.origin + int(config['maxSeconds']*1e9)
        self.work_deadline = self.deadline - int(min(3, config['maxSeconds']/3)*1e9)
        self.port = None
        self.token = secrets.token_urlsafe(32)
        self.cleanup_result = None
        self.verified_contention = False
        self.last_verified = 0
        self.last_summary = 0
        self.stop_input = b''
        control_fd = sys.stdin.fileno() if control_fd is None else control_fd
        os.set_blocking(control_fd, False)
        self.selector.register(control_fd, selectors.EVENT_READ, None)

    def emit(self, event, **fields):
        self.revision += 1
        item = {'event': event, 'kind': 'MEASURED', 'sourceId': self.config['id']+'/coordinator',
                'experimentId': self.config['id'], 'sequence': self.revision,
                'timestamp': time.time_ns(), 'monotonicNs': time.monotonic_ns(), **fields}
        encoded = json.dumps(item, separators=(',', ':')) + '\n'
        self.bytes_written += len(encoded.encode())
        if self.bytes_written > MAX_EVIDENCE_BYTES:
            raise RuntimeError('evidence size bound reached')
        self.journal.write(encoded)
        self.journal.flush()
        try:
            sys.stdout.write(encoded)
            sys.stdout.flush()
        except BrokenPipeError:
            common.stop()
        return item

    def check(self):
        if common.STOP:
            raise Cancelled('stop requested or parent exited')
        if time.monotonic_ns() >= self.work_deadline:
            raise Deadline('experiment time limit')

    def launch(self, script, role, cpus, extra):
        self.check()
        if script not in ('service.py', 'worker.py', 'load_client.py') or len(self.children) >= 4:
            raise ValueError('unapproved child')
        process = subprocess.Popen(['/usr/bin/taskset', '-c', ','.join(map(str, cpus)),
                                    '/usr/bin/python3', '-u', str(Path(__file__).parent/script)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=False, bufsize=0)
        # Retain Popen immediately, before any fallible identity/config operation.
        child = {'process': process, 'role': role, 'identity': None, 'pidfd': None, 'ready': False,
                 'buffer': b'', 'observed': None, 'lastVerifiedNs': None, 'streamClosed': False}
        self.children.append(child)
        try:
            child['identity'] = common.identity(process.pid)
            try:
                child['pidfd'] = os.pidfd_open(process.pid)
            except (OSError, AttributeError):
                pass
            os.set_blocking(process.stdout.fileno(), False)
            self.selector.register(process.stdout.fileno(), selectors.EVENT_READ, child)
            self.emit('child_registered', identity=child['identity'], role=role,
                      launch={'script': script, 'allowedCpus': cpus,
                              **{k: v for k, v in extra.items() if k in ('iterations','hashBudget','rateHz','maxInFlight','timeoutMs','deadlineMs')}},
                      signaling='PIDFD' if child['pidfd'] is not None else 'VERIFIED_OWNED_POPEN_WITH_RESIDUAL_EXIT_RACE')
            child_config = {'parentPid': os.getpid(), 'allowedCpus': cpus, 'originNs': self.origin,
                            'deadlineNs': self.deadline, 'token': self.token, **extra}
            process.stdin.write((json.dumps(child_config)+'\n').encode())
            process.stdin.close()
        except BaseException:
            self.signal_child(child, signal.SIGTERM)
            raise
        return child

    def signal_child(self, child, sig):
        process = child['process']
        if process.poll() is not None:
            return
        if child['identity'] is not None:
            try:
                if common.identity(process.pid) != child['identity']:
                    return  # fail closed; never signal a reused numeric PID
            except OSError:
                return
        if child['pidfd'] is not None and hasattr(signal, 'pidfd_send_signal'):
            try:
                signal.pidfd_send_signal(child['pidfd'], sig)
            except ProcessLookupError:
                pass
        else:
            process.send_signal(sig)  # still an owned, unreaped Popen child

    def pump(self, timeout=.02):
        for key, _ in self.selector.select(timeout):
            child = key.data
            try:
                chunk = os.read(key.fd, 65536)
            except BlockingIOError:
                continue
            if child is None:
                if not chunk:
                    common.stop()
                    self.selector.unregister(key.fd)
                else:
                    self.stop_input += chunk
                    while b'\n' in self.stop_input:
                        cmd, self.stop_input = self.stop_input.split(b'\n', 1)
                        cmd = cmd.strip()
                        if cmd == b'STOP':
                            common.stop()
                        elif cmd == b'RESET_WORKERS':
                            self.reset_workers()
                    if len(self.stop_input) > 2048:
                        self.stop_input = b''
                continue
            if not chunk:
                self.selector.unregister(key.fd)
                child['streamClosed'] = True
                continue
            child['buffer'] += chunk
            if len(child['buffer']) > 131072:
                raise RuntimeError('child output bound exceeded')
            while b'\n' in child['buffer']:
                line, child['buffer'] = child['buffer'].split(b'\n', 1)
                if len(line) > 16384:
                    raise RuntimeError('child event too large')
                try:
                    payload = json.loads(line)
                except (ValueError, UnicodeDecodeError):
                    raise RuntimeError('invalid child instrumentation output') from None
                self.emit('child_event', role=child['role'], identity=child['identity'], payload=payload)
                if payload['event'] == 'ready':
                    child['ready'] = True
                    observed = common.metadata(child['process'].pid)
                    if observed['identity'] != child['identity'] or observed['uid'] != os.getuid():
                        raise RuntimeError('child identity or UID changed')
                    child['observed'] = observed
                    child['lastVerifiedNs'] = time.monotonic_ns()
                    self.emit('child_verified', role=child['role'], **observed)
                    if child['role'] == 'PROTECTED_SERVICE':
                        self.port = payload['port']
                elif payload['event'] == 'request':
                    if len(self.requests) >= 6000:
                        raise RuntimeError('request evidence bound exceeded')
                    self.requests.append(payload)
                elif payload['event'] == 'worker_progress':
                    self.worker_events.setdefault(child['role'], []).append(payload)
                elif payload['event'] == 'worker_end' and self.state == 'RUNNING':
                    raise RuntimeError('finite worker ended before comparison completed')

    def wait_ready(self, children):
        until = min(self.work_deadline, time.monotonic_ns()+3_000_000_000)
        while not all(c['ready'] for c in children):
            self.check()
            if time.monotonic_ns() > until or any(c['process'].poll() is not None for c in children):
                raise RuntimeError('child startup failed or timed out')
            self.pump()

    def reset_workers(self):
        workers = [c for c in self.children if c['role'].startswith('BACKGROUND')]
        for c in workers:
            self.signal_child(c, signal.SIGTERM)
        until = time.monotonic_ns() + 1_000_000_000
        while time.monotonic_ns() < until and any(c['process'].poll() is None for c in workers):
            self.pump(0.01)
        for c in workers:
            if c['process'].poll() is None:
                self.signal_child(c, signal.SIGKILL)
            if c in self.children:
                self.children.remove(c)
        budget = self.frozen.get('workerHashBudget', 100_000_000) if self.frozen else 100_000_000
        new_workers = [self.launch('worker.py', f'BACKGROUND_{i+1}', [self.config['core']], {'hashBudget': budget}) for i in range(2)]
        self.wait_ready(new_workers)
        self.verify_group()
        self.emit('workers_reset', newChildren=[c['identity'] for c in new_workers])
        self.publish()

    def verify_group(self):
        cohort = [c for c in self.children if c['role'] != 'LOAD_CLIENT']
        observed = [common.metadata(c['process'].pid) for c in cohort]
        for child, info in zip(cohort, observed):
            if (info['identity'] != child['identity'] or info['uid'] != os.getuid()
                    or info['allowedCpus'] != [self.config['core']] or info['threads'] != 1
                    or info['cgroup'] != observed[0]['cgroup']
                    or info['autogroup'] is None or info['autogroup'] != observed[0]['autogroup']):
                raise RuntimeError('shared single-threaded CPU/group/UID scope could not be verified')
            if child['role'] == 'PROTECTED_SERVICE' and info['nice'] != 0:
                raise RuntimeError('protected service priority was modified')
            if child['role'].startswith('BACKGROUND') and not (0 <= info['nice'] <= 19):
                raise RuntimeError('background worker nice out of allowed range 0..19')
            child['observed'] = info
            child['lastVerifiedNs'] = time.monotonic_ns()
        self.emit('cohort_verified', members=[{'role': c['role'], **i} for c, i in zip(cohort, observed)])
        self.last_verified = time.monotonic_ns()

    def status(self):
        now = time.monotonic_ns()
        summaries = {}
        for name in ('BASELINE', 'CONTENTION', 'AFTER_ACTION'):
            interval = next((p for p in self.phases if p['name'] == name), None)
            seconds = max(0, (min(now, interval['endNs'])-interval['startNs'])/1e9) if interval else 0
            summaries[name] = summarize([r for r in self.requests if r['phase'] == name], self.config['id'], name, seconds)
        workers = []
        for child in self.children:
            if not child['role'].startswith('BACKGROUND'):
                continue
            events = self.worker_events.get(child['role'], [])
            last = events[-1] if events else None
            rates_by_phase = {}
            for pname in ('CONTENTION', 'AFTER_ACTION'):
                p_interval = next((p for p in self.phases if p['name'] == pname), None)
                if p_interval:
                    within = [e for e in events if p_interval['startNs'] <= e['monotonicNs'] <= p_interval['endNs']]
                    duration = (within[-1]['monotonicNs']-within[0]['monotonicNs'])/1e9 if len(within) >= 2 else 0
                    rate = (within[-1]['hashes']-within[0]['hashes'])/duration if duration > 0 else None
                    rates_by_phase[pname] = metric(rate, 'hashes/s', self.config['id'], pname, duration, 'NEEDS_TWO_PROGRESS_EVENTS')
            contention_rate = rates_by_phase.get('CONTENTION', metric(None, 'hashes/s', self.config['id'], 'CONTENTION', 0, 'NO_INTERVAL'))
            workers.append({'role': child['role'], 'identity': child['identity'], 'alive': child['process'].poll() is None,
                            'observed': child['observed'], 'lastVerifiedNs': child['lastVerifiedNs'],
                            'progress': last, 'hashesPerSecond': contention_rate, 'hashesByPhase': rates_by_phase})
        return {'id': self.config['id'], 'state': self.state, 'phase': self.phase, 'reason': self.reason,
                'revision': self.revision, 'timestamp': time.time_ns(), 'originNs': self.origin,
                'elapsedSeconds': (now-self.origin)/1e9, 'maxSeconds': self.config['maxSeconds'],
                'core': self.config['core'], 'observerCpus': self.config['observerCpus'],
                'observerLimitation': None if self.config['observerCpus'] != [self.config['core']] else 'Single allowed CPU: observer overhead shares the experiment core',
                'parameters': self.frozen, 'phases': self.phases, 'summaries': summaries, 'workers': workers,
                'contentionCohortVerified': self.verified_contention, 'cleanup': self.cleanup_result,
                'requestEvidenceCount': len(self.requests),
                'children': [{'role': c['role'], 'identity': c['identity'], 'observed': c['observed'],
                              'alive': c['process'].poll() is None, 'lastVerifiedNs': c['lastVerifiedNs']} for c in self.children]}

    def publish(self):
        self.emit('summary', summary=self.status())
        self.last_summary = time.monotonic_ns()

    def cleanup(self):
        self.state = 'STOPPING'
        for child in reversed(self.children):
            self.signal_child(child, signal.SIGTERM)
        until = time.monotonic_ns()+2_000_000_000
        while time.monotonic_ns() < until and any(c['process'].poll() is None or not c['streamClosed'] for c in self.children):
            try:
                self.pump(.02)
            except Exception:
                # Never allow a corrupt final event to bypass owned-child cleanup.
                break
        for child in self.children:
            if child['process'].poll() is None:
                self.signal_child(child, signal.SIGKILL)
        results = []
        for child in self.children:
            try:
                code = child['process'].wait(timeout=.5)
            except subprocess.TimeoutExpired:
                code = None
            results.append({'role': child['role'], 'identity': child['identity'], 'exitCode': code,
                            'alive': child['process'].poll() is None})
            if child['pidfd'] is not None:
                os.close(child['pidfd'])
            child['process'].stdout.close()
        self.cleanup_result = {'verified': all(not c['alive'] for c in results), 'children': results,
                               'checkedAtNs': time.monotonic_ns()}
        self.selector.close()

    def execute(self):
        outcome = 'FAILED'
        try:
            self.emit('environment', coordinator=common.metadata(os.getpid()), pythonVersion=sys.version,
                      kernel=os.uname().release, clockTicks=os.sysconf('SC_CLK_TCK'),
                      bufferBytes=len(common.BUFFER), hashAlgorithm='sha256(previous_digest + fixed_1024_byte_buffer)',
                      timeUnit='nanoseconds; monotonic clock for durations; Unix nanoseconds for timestamp')
            service = self.launch('service.py', 'PROTECTED_SERVICE', [self.config['core']], {'iterations': 20000})
            self.wait_ready([service])
            self.verify_group()
            calibration = []
            expected = None
            for index in range(8):
                self.check()
                self.pump(0)
                before = time.monotonic_ns()
                result = asyncio.run(asyncio.wait_for(exchange(self.port, self.token, f'cal-{index}', 'CALIBRATION'), 2))
                after = time.monotonic_ns()
                if expected is not None and expected != result['digest']:
                    raise RuntimeError('calibration digest changed')
                expected = result['digest']
                calibration.append(result['cpuServiceNs'])
                self.emit('calibration_request', requestId=f'cal-{index}', dispatchNs=before, completionNs=after,
                          latencyNs=after-before, response=result)
            median_cpu = statistics.median(calibration)
            if median_cpu <= 0:
                raise RuntimeError('invalid measured calibration demand')
            rate = min(60, max(1, math.floor(.60 * 1e9 / median_cpu)))
            budget = min(500_000_000, max(1_000_000, math.ceil(20000/(median_cpu/1e9)*45*4)))
            self.frozen = {'iterations': 20000, 'bufferBytes': 1024, 'rateHz': rate, 'intervalNs': round(1e9/rate),
                           'workerHashBudget': budget, 'workers': 2, 'deadlineMs': 500, 'timeoutMs': 1500,
                           'maxInFlight': 8, 'calibrationMedianCpuNs': median_cpu, 'calibrationCount': len(calibration),
                            'warmupSeconds': 5, 'baselineSeconds': 30, 'transitionSeconds': 4, 'contentionSeconds': 30,
                            'afterActionSeconds': 30,
                            'frozenAtNs': time.monotonic_ns(), 'expectedDigest': expected}
            self.emit('parameters_frozen', parameters=self.frozen)
            begin = time.monotonic_ns()+500_000_000
            cursor = begin
            for name, seconds in [('WARMUP', 5), ('BASELINE', 30), ('TRANSITION', 4), ('CONTENTION', 30), ('AFTER_ACTION', 30)]:
                self.phases.append({'name': name, 'startNs': cursor, 'endNs': cursor+seconds*1_000_000_000})
                cursor += seconds*1_000_000_000
            client = self.launch('load_client.py', 'LOAD_CLIENT', self.config['observerCpus'],
                                 {**self.frozen, 'port': self.port, 'phases': self.phases})
            self.wait_ready([client])
            workers = []
            while time.monotonic_ns() < self.phases[-1]['endNs']:
                self.check()
                self.pump()
                now = time.monotonic_ns()
                phase = next((p['name'] for p in self.phases if p['startNs'] <= now < p['endNs']), 'WARMUP')
                if phase != self.phase:
                    self.phase = phase
                    self.emit('phase_boundary', phase=phase, planned=next(p for p in self.phases if p['name'] == phase))
                if phase == 'TRANSITION' and not workers and now >= self.phases[2]['startNs'] + 2_000_000_000:
                    workers = [self.launch('worker.py', f'BACKGROUND_{i+1}', [self.config['core']], {'hashBudget': budget}) for i in range(2)]
                    self.wait_ready(workers)
                    if time.monotonic_ns() >= self.phases[3]['startNs']:
                        raise RuntimeError('workers were not verified before the contention window')
                    self.verify_group()
                if phase in ('CONTENTION', 'AFTER_ACTION'):
                    if len(workers) != 2:
                        raise RuntimeError('workers were not ready before contention')
                    if not self.verified_contention:
                        self.verify_group()
                        self.verified_contention = True
                if now-self.last_verified > 1_000_000_000:
                    self.verify_group()
                if any(c['process'].poll() is not None for c in self.children if c['role'] != 'LOAD_CLIENT'):
                    raise RuntimeError('owned workload exited during measurement window')
                if now-self.last_summary > 500_000_000:
                    self.publish()
            self.phase = 'DRAINING'
            self.emit('phase_boundary', phase='DRAINING')
            until = time.monotonic_ns()+2_500_000_000
            while client['process'].poll() is None and time.monotonic_ns() < until:
                self.check()
                self.pump()
                if any(c['process'].poll() is not None for c in workers):
                    raise RuntimeError('background worker exited before request drain completed')
            self.pump(.05)
            if client['process'].poll() != 0:
                raise RuntimeError('load client failed or drain timed out')
            self.verify_group()
            expected_count = math.ceil((self.phases[-1]['endNs']-begin)/self.frozen['intervalNs'])
            if len(self.requests) != expected_count or len({r['requestId'] for r in self.requests}) != expected_count:
                raise RuntimeError('incomplete or duplicate scheduled-request evidence')
            outcome = 'COMPLETED'
        except Cancelled as exc:
            outcome, self.reason = 'CANCELLED', str(exc)
        except Deadline as exc:
            outcome, self.reason = 'TIMED_OUT', str(exc)
        except Exception as exc:
            outcome, self.reason = 'FAILED', f'{type(exc).__name__}: {exc}'
        finally:
            self.cleanup()
            self.state = outcome if self.cleanup_result['verified'] else 'CLEANUP_FAILED'
            self.phase = 'FINISHED'
            self.publish()
            (self.directory/'summary.json').write_text(json.dumps(self.status(), indent=2)+'\n')
            self.journal.close()
        return self.status()


if __name__ == '__main__':
    configuration = common.read_config()
    configuration['originNs'] = time.monotonic_ns()
    if not 1 <= configuration['maxSeconds'] <= 180 or configuration['core'] not in configuration['originalAllowedCpus']:
        raise ValueError('experiment limits invalid')
    Run(configuration).execute()
