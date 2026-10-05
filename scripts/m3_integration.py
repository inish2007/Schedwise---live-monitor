#!/usr/bin/env python3
"""Bounded Phase 3 / M3 integration verification for SchedWise.
Validates the pure-Java scheduling models and capture simulation APIs against genuine Phase 2 traces.
"""
import http.client
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64') + '/bin/java'


def main():
    jar = ROOT / 'backend/target/schedwise-0.1.0.jar'
    if not jar.is_file():
        raise RuntimeError("Missing compiled backend JAR; run ./scripts/verify.sh first")

    # Use existing data directory where genuine Phase 2 captures are stored
    data_dir = ROOT / 'data'
    log_file = data_dir / 'm3-backend.log'

    print("Starting backend on port 18080 with schedwise.data=" + str(data_dir), flush=True)
    with open(log_file, 'w+') as log:
        backend = subprocess.Popen(
            [JAVA, '-Dschedwise.data=' + str(data_dir), '-jar', str(jar), '--server.port=18080'],
            stdout=log, stderr=subprocess.STDOUT
        )

        try:
            token = None
            token_file = data_dir / 'session-token'

            def api(path, method='GET', body=None, auth=True, origin='http://127.0.0.1:5173'):
                headers = {}
                if auth and token:
                    headers['Authorization'] = 'Bearer ' + token
                if origin:
                    headers['Origin'] = origin
                data = None
                if body is not None:
                    headers['Content-Type'] = 'application/json'
                    data = json.dumps(body).encode('utf-8')

                req = urllib.request.Request(
                    'http://127.0.0.1:18080/api/' + path,
                    method=method,
                    headers=headers,
                    data=data
                )
                with urllib.request.urlopen(req, timeout=10) as r:
                    return json.load(r)

            # Wait for backend to be ready
            for _ in range(80):
                if backend.poll() is not None:
                    log.seek(0)
                    raise RuntimeError("Backend failed to start:\n" + log.read())
                try:
                    if token_file.is_file():
                        token = token_file.read_text().strip()
                        caps = api('capabilities')
                        if caps:
                            break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(0.25)
            else:
                raise RuntimeError("Backend startup timed out")

            print("Backend ready. Testing unauthenticated & unauthorized protections...", flush=True)

            # Check unauthenticated access rejected on captures & simulations
            try:
                api('captures', auth=False)
                raise AssertionError("Unauthenticated GET /api/captures should be rejected")
            except urllib.error.HTTPError as e:
                assert e.code == 401, f"Expected 401, got {e.code}"

            try:
                api('simulations', method='POST', body={'captureId': 'none'}, auth=False)
                raise AssertionError("Unauthenticated POST /api/simulations should be rejected")
            except urllib.error.HTTPError as e:
                assert e.code == 401, f"Expected 401, got {e.code}"

            # Check untrusted origin rejected
            try:
                api('captures', origin='http://malicious.example.com')
                raise AssertionError("Untrusted origin should be rejected")
            except urllib.error.HTTPError as e:
                assert e.code == 403, f"Expected 403, got {e.code}"

            print("Testing GET /api/captures...", flush=True)
            captures = api('captures')
            assert isinstance(captures, list), "Captures must be a list"
            assert len(captures) > 0, "Expected at least one genuine Phase 2 capture in data/experiments"

            # Find a completed contention capture
            real_capture = next((c for c in captures if c['hasContention'] and c['rawRequestCount'] > 0), None)
            assert real_capture is not None, "Could not find a capture with contention requests"
            capture_id = real_capture['id']
            print(f"Selected genuine capture: {capture_id} ({real_capture['rawRequestCount']} requests)", flush=True)

            print("Testing POST /api/simulations on real capture...", flush=True)
            sim_request = {
                'captureId': capture_id,
                'phase': 'CONTENTION',
                'models': ['FCFS', 'ROUND_ROBIN', 'PRIORITY', 'SJF', 'CFS'],
                'quantumNs': 20_000_000,           # 20ms Round Robin quantum
                'cfsLatencyTargetNs': 24_000_000,  # 24ms CFS latency target
                'cfsMinGranularityNs': 3_000_000,  # 3ms CFS min granularity
                'backgroundNice': 5,               # candidate nice 5 (weight 335)
                'horizonNs': 15_000_000_000,       # 15s horizon
                'maxEvents': 20000
            }

            sim_response = api('simulations', method='POST', body=sim_request)

            # Invariant verifications
            assert sim_response['kind'] == 'SIMULATED', "All simulation responses must be explicitly tagged SIMULATED"
            assert sim_response['sufficiency'] == 'SUFFICIENT', f"Expected SUFFICIENT, got {sim_response['sufficiency']}"
            assert sim_response['phase'] == 'CONTENTION'
            results = sim_response['results']
            assert len(results) == 5, f"Expected 5 model results, got {len(results)}"

            for model_name in ['FCFS', 'ROUND_ROBIN', 'PRIORITY', 'SJF', 'CFS']:
                assert model_name in results, f"Missing model result for {model_name}"
                res = results[model_name]
                assert res['dataStructure'], f"Missing data structure name for {model_name}"
                assert res['complexity'], f"Missing complexity description for {model_name}"
                assert res['assumptions'], f"Missing assumptions description for {model_name}"

                timeline = res['timeline']
                assert len(timeline) > 0, f"Timeline must not be empty for {model_name}"

                # Invariant: Single-CPU non-overlapping execution
                for i in range(len(timeline) - 1):
                    seg_cur = timeline[i]
                    seg_next = timeline[i + 1]
                    assert seg_cur['endNs'] <= seg_next['startNs'], (
                        f"Non-overlap invariant violated in {model_name}: "
                        f"{seg_cur['endNs']} > {seg_next['startNs']}"
                    )

                # Invariant: Metrics sanity
                metrics = res['metrics']
                assert metrics['totalJobs'] > 0
                assert metrics['cpuUtilizationPercent'] is not None
                assert metrics['cpuUtilizationPercent'] > 0.0

                # Invariant: Response and waiting times are non-negative
                for job in res['jobs']:
                    if job['responseTimeNs'] is not None:
                        assert job['responseTimeNs'] >= 0, f"Negative response time in {model_name}"
                    if job['waitingTimeNs'] is not None:
                        assert job['waitingTimeNs'] >= 0, f"Negative waiting time in {model_name}"

            # Tradeoff inspection: SJF prioritizes short service requests over long background batch
            fcfs_res = results['FCFS']
            sjf_res = results['SJF']
            cfs_res = results['CFS']
            rr_res = results['ROUND_ROBIN']

            print(f"FCFS Completed: {fcfs_res['metrics']['completedJobs']}, Censored: {fcfs_res['metrics']['censoredJobs']}")
            print(f"SJF  Completed: {sjf_res['metrics']['completedJobs']}, Censored: {sjf_res['metrics']['censoredJobs']}")
            print(f"RR   Completed: {rr_res['metrics']['completedJobs']}, Censored: {rr_res['metrics']['censoredJobs']}")
            print(f"CFS  Completed: {cfs_res['metrics']['completedJobs']}, Censored: {cfs_res['metrics']['censoredJobs']}")

            assert sjf_res['metrics']['completedJobs'] > fcfs_res['metrics']['completedJobs'], (
                "Under contention, non-preemptive SJF must complete more short requests than non-preemptive FCFS"
            )

            # Retain evidence
            evidence = {
                'testedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                'captureId': capture_id,
                'request': sim_request,
                'summary': {
                    'algorithmsTested': list(results.keys()),
                    'metrics': {k: v['metrics'] for k, v in results.items()}
                }
            }
            evidence_file = ROOT / 'docs/m3-integration-evidence.json'
            evidence_file.write_text(json.dumps(evidence, indent=2) + '\n')
            print("Wrote evidence to " + str(evidence_file), flush=True)
            print("Phase 3 / M3 integration verification PASSED!", flush=True)

        finally:
            print("Stopping test backend...", flush=True)
            backend.terminate()
            try:
                backend.wait(timeout=5)
            except subprocess.TimeoutExpired:
                backend.kill()
                backend.wait()
            print("Test backend stopped.", flush=True)


if __name__ == '__main__':
    main()
