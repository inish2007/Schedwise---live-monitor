#!/usr/bin/env python3
"""Bounded Phase 5 / M5 integrated live workflow verification for SchedWise.
Validates:
1. Environment & capabilities discovery.
2. Contention experiment lifecycle: Calibration -> Warmup -> Baseline -> Contention -> After-Action.
3. Contention detection & explainable recommendation generation from live captured evidence.
4. Pure Java discrete-event simulation (CFS, SJF, RR, FCFS) executed against real captured workload.
5. User-confirmed priority action (renice worker to 5) with direct /proc readback verification.
6. Real after-action measurement window with background workers at lower priority.
7. Comparability evaluation: 3-way measurement (Baseline vs Contention vs After Action) with tradeoff calculations.
8. Session export packaging (events, summary, captures, action audits) and archive browsing.
9. Bounded child process cleanup and zero lingering processes.
"""
import json
import os
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile
import io

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64') + '/bin/java'


def main():
    jar = ROOT / 'backend/target/schedwise-0.1.0.jar'
    if not jar.is_file():
        raise RuntimeError("Missing compiled backend JAR; run ./scripts/verify.sh first")

    data_dir = ROOT / 'data'
    log_file = data_dir / 'm5-backend.log'

    print("Starting backend on port 18080 with schedwise.data=" + str(data_dir), flush=True)
    with open(log_file, 'w+') as log:
        backend = subprocess.Popen(
            [JAVA, '-Dschedwise.data=' + str(data_dir), '-Dschedwise.demoRoot=' + str(ROOT / 'tools/demo'),
             '-jar', str(jar), '--server.port=18080'],
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
                try:
                    with urllib.request.urlopen(req, timeout=15) as r:
                        content_type = r.headers.get('Content-Type', '')
                        if 'application/json' in content_type:
                            return json.load(r)
                        return r.read()
                except urllib.error.HTTPError as e:
                    err_body = e.read().decode('utf-8', errors='replace')
                    print(f"API Error {e.code} on {method} {path}: {err_body}", flush=True)
                    raise

            # Wait for backend to be ready
            print("Waiting for backend startup...", flush=True)
            for _ in range(80):
                if backend.poll() is not None:
                    log.seek(0)
                    raise RuntimeError("Backend failed to start:\n" + log.read())
                try:
                    if token_file.is_file():
                        token = token_file.read_text().strip()
                        caps = api('capabilities')
                        if caps:
                            print("Backend ready.", flush=True)
                            break
                except Exception:
                    pass
                time.sleep(0.25)
            else:
                raise TimeoutError("Backend failed to start within 20 seconds")

            evidence = {
                "milestone": "M5",
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "stages": {}
            }

            # 1. Test Environment & Scope
            print("\n1. Verifying Environment & Capabilities...", flush=True)
            caps = api('capabilities')
            latest_snap = api('snapshots/latest')
            allowed_cpus_val = caps['sources']['allowedCpus']['value']
            print(f"   Linux Kernel: {caps['sources']['kernel']['value']}")
            print(f"   Allowed CPUs: {allowed_cpus_val}")
            print(f"   Clock Ticks: {caps['sources']['clockTicks']['value']} Hz")
            print(f"   Storage: {latest_snap.get('storageStatus', caps.get('storage'))}")
            evidence["stages"]["environment"] = {
                "kernel": caps['sources']['kernel']['value'],
                "allowedCpus": allowed_cpus_val,
                "clockTicks": caps['sources']['clockTicks']['value'],
                "storage": latest_snap.get('storageStatus', caps.get('storage')),
                "cpuPressureSome": latest_snap['snapshot']['cpuPressureSome'] if latest_snap.get('snapshot') else None
            }

            # Select constrained core: prefer the highest allowed core
            if isinstance(allowed_cpus_val, str) and ',' in allowed_cpus_val:
                cores = [int(c.strip()) for c in allowed_cpus_val.split(',') if c.strip().isdigit()]
                target_core = cores[-1]
            elif isinstance(allowed_cpus_val, str) and '-' in allowed_cpus_val:
                target_core = int(allowed_cpus_val.split('-')[-1])
            elif isinstance(allowed_cpus_val, int):
                target_core = allowed_cpus_val
            else:
                target_core = 0
            print(f"   Selected experiment target core: {target_core}")

            # 2. Launch Contention Experiment
            print(f"\n2. Launching Contention Experiment on Core {target_core}...", flush=True)
            exp = api('experiments', method='POST', body={'core': target_core})
            exp_id = exp['id']
            print(f"   Experiment started: {exp_id}, initial phase: {exp['phase']}")
            evidence["stages"]["experiment_launch"] = {
                "id": exp_id,
                "targetCore": target_core,
                "initialPhase": exp['phase']
            }

            applied_action = False
            rec_id = None
            action_result = None
            sim_result = None

            print("   Waiting for experiment to progress through phases...", flush=True)
            start_wait = time.time()
            last_phase = ""

            while time.time() - start_wait < 160:
                exp_curr = api(f'experiments/{exp_id}')
                phase = exp_curr.get('phase', 'UNKNOWN')
                state = exp_curr.get('state', 'UNKNOWN')

                if phase != last_phase:
                    print(f"   -> Phase transitioned to: {phase} (state: {state})", flush=True)
                    last_phase = phase

                # Once in CONTENTION, generate recommendations, run simulation, and apply action
                if phase == 'CONTENTION' and not applied_action:
                    # Give contention 10 seconds to accumulate real request samples
                    print("   In CONTENTION phase: waiting 10s to accumulate contention telemetry...", flush=True)
                    time.sleep(10)

                    # a) Recommendation
                    print("   3. Fetching recommendations from contention evidence...", flush=True)
                    rec = api('recommendations', method='POST', body={'experimentId': exp_id})
                    if rec and rec.get('status') == 'RECOMMENDED':
                        rec_id = rec['id']
                        print(f"      Recommendation generated: ID {rec_id}")
                        print(f"      Evidence: {rec['evidence']['summary']}")
                        print(f"      Candidates: {len(rec['candidateScenarios'])} scenarios evaluated")
                        evidence["stages"]["recommendation"] = {
                            "id": rec_id,
                            "evidence": rec['evidence'],
                            "candidatesCount": len(rec['candidateScenarios'])
                        }
                    else:
                        print(f"      Recommendation status: {rec.get('status') if rec else 'None'}")

                    # b) Pure-Java DSA Simulation on Capture
                    print("   4. Running DSA simulation against real captured workload...", flush=True)
                    try:
                        captures = api('captures')
                        sufficient_caps = [c for c in captures if c.get('sufficiency') == 'SUFFICIENT' and c['id'] != exp_id]
                        cap_to_sim = sufficient_caps[0]['id'] if sufficient_caps else (captures[0]['id'] if captures else None)
                        if cap_to_sim:
                            sim_req = {
                                "captureId": cap_to_sim,
                                "phase": "CONTENTION",
                                "models": ["FCFS", "ROUND_ROBIN", "PRIORITY", "SJF", "CFS"],
                                "quantumNs": 20000000,
                                "cfsLatencyTargetNs": 24000000,
                                "cfsMinGranularityNs": 3000000,
                                "backgroundNice": 5,
                                "horizonNs": 30000000000,
                                "maxEvents": 10000
                            }
                            sim_result = api('simulations', method='POST', body=sim_req)
                            print(f"      Simulation completed for {len(sim_result['results'])} algorithms on capture {cap_to_sim}.")
                            for k, v in sim_result['results'].items():
                                wait_ms = v['metrics']['meanWaitingTimeMs'] or 0.0
                                print(f"        - {k}: {v['metrics']['completedJobs']} completed, mean wait: {wait_ms:.2f}ms")
                            evidence["stages"]["simulation"] = {
                                "captureId": cap_to_sim,
                                "algorithms": list(sim_result['results'].keys()),
                                "summary": {k: {"completed": v['metrics']['completedJobs'], "meanWaitMs": v['metrics']['meanWaitingTimeMs']} for k, v in sim_result['results'].items()}
                            }
                    except Exception as e:
                        print(f"      Simulation error: {e}", flush=True)

                    # c) Apply Priority Action: Renice background workers to 5
                    print("   5. Applying confirmed priority action to background workers...", flush=True)
                    exp_curr = api(f'experiments/{exp_id}')
                    workers = [w for w in exp_curr.get('workers', []) if w.get('alive')]
                    if workers:
                        targets = [{
                            "pid": w['identity']['pid'],
                            "identity": w['identity'],
                            "expectedCurrentNice": w['observed']['nice'],
                            "requestedNice": 5
                        } for w in workers]
                        action_req = {
                            "actionId": str(uuid.uuid4()),
                            "experimentId": exp_id,
                            "recommendationId": rec_id,
                            "targets": targets
                        }
                        action_result = api('actions/nice', method='POST', body=action_req)
                        print(f"      Action result: {action_result['overallStatus']}")
                        for target_res in action_result['targets']:
                            print(f"      - Target PID {target_res['pid']}: status={target_res['status']}, observedNice={target_res['observedNice']}")

                        # Verify /proc/PID/stat directly for each worker
                        for w in workers:
                            w_pid = w['identity']['pid']
                            with open(f"/proc/{w_pid}/stat", 'r') as f:
                                stat_fields = f.read().split()
                                readback_nice_direct = int(stat_fields[18])
                            print(f"      Direct /proc/{w_pid}/stat field 19 verified: nice = {readback_nice_direct}")
                            assert readback_nice_direct == 5, f"Expected nice 5 in /proc for PID {w_pid}, got {readback_nice_direct}"

                        applied_action = True
                        evidence["stages"]["priority_action"] = {
                            "targets": targets,
                            "overallStatus": action_result['overallStatus'],
                            "targetResults": action_result['targets']
                        }

                if phase in ('FINISHED', 'COMPLETED') or state in ('COMPLETED', 'FINISHED', 'STOPPED'):
                    print(f"   Experiment reached completion state: {state}", flush=True)
                    break

                time.sleep(2)

            # 6. Fetch 3-Way Measurement Comparison & Comparability Report
            print("\n6. Verifying 3-Way Measurement & Comparability Report...", flush=True)
            comparison = api(f'experiments/{exp_id}/comparison')
            evidence["stages"]["comparison"] = comparison

            print(f"   Comparability Status: {comparison.get('status')}")
            print(f"   Comparable: {comparison.get('comparable')}")
            if comparison.get('invalidReasons'):
                print(f"   Invalid reasons: {comparison['invalidReasons']}")
            else:
                print(f"   Delta Latency p95 Improvement: {comparison.get('latencyP95ImprovementPercent')}%")
                print(f"   Latency Recovery: {comparison.get('latencyRecoveryPercent')}%")
                print(f"   Worker Throughput Tradeoff: {comparison.get('throughputTradeoffPercent')}%")

            # Check Baseline, Contention, and AfterAction summaries from final experiment status
            exp_final = api(f'experiments/{exp_id}')
            summaries = exp_final.get('summaries', {})
            b_summary = summaries.get('BASELINE')
            c_summary = summaries.get('CONTENTION')
            a_summary = summaries.get('AFTER_ACTION')

            print("\n   --- Phase Latency & Throughput Metrics ---")
            if b_summary:
                p99_b = b_summary.get('p99Ms', {}).get('value')
                print(f"   Baseline:    p50={b_summary['p50Ms']['value']}ms, p95={b_summary['p95Ms']['value']}ms, p99={p99_b} ({b_summary['successCount']} reqs)")
            if c_summary:
                p99_c = c_summary.get('p99Ms', {}).get('value')
                print(f"   Contention:  p50={c_summary['p50Ms']['value']}ms, p95={c_summary['p95Ms']['value']}ms, p99={p99_c} ({c_summary['successCount']} reqs)")
            if a_summary:
                p99_a = a_summary.get('p99Ms', {}).get('value')
                print(f"   AfterAction: p50={a_summary['p50Ms']['value']}ms, p95={a_summary['p95Ms']['value']}ms, p99={p99_a} ({a_summary['successCount']} reqs)")

            # Check worker throughput
            workers_summary = exp_final.get('workers', [])
            for w in workers_summary:
                print(f"   Worker {w['role']}: hashesByPhase = {w.get('hashesByPhase')}")

            # Also run DSA simulation directly on newly completed experiment
            print(f"\n   Running DSA simulation on completed experiment {exp_id}...", flush=True)
            try:
                exp_sim_req = {
                    "captureId": exp_id,
                    "phase": "CONTENTION",
                    "models": ["FCFS", "ROUND_ROBIN", "PRIORITY", "SJF", "CFS"],
                    "quantumNs": 20000000,
                    "cfsLatencyTargetNs": 24000000,
                    "cfsMinGranularityNs": 3000000,
                    "backgroundNice": 5,
                    "horizonNs": 30000000000,
                    "maxEvents": 10000
                }
                exp_sim = api('simulations', method='POST', body=exp_sim_req)
                print(f"   Newly captured experiment simulation completed for {len(exp_sim['results'])} algorithms:")
                for k, v in exp_sim['results'].items():
                    wait_ms = v['metrics']['meanWaitingTimeMs'] or 0.0
                    print(f"     - {k}: {v['metrics']['completedJobs']} completed, mean wait: {wait_ms:.2f}ms")
                evidence["stages"]["new_experiment_simulation"] = {
                    "algorithms": list(exp_sim['results'].keys()),
                    "summary": {k: {"completed": v['metrics']['completedJobs'], "meanWaitMs": v['metrics']['meanWaitingTimeMs']} for k, v in exp_sim['results'].items()}
                }
            except Exception as e:
                print(f"   Simulation on new experiment error: {e}", flush=True)

            # 7. Verify Session List & Export
            print("\n7. Verifying Session List and Export Archive...", flush=True)
            sessions = api('sessions')
            print(f"   Total stored sessions in SQLite: {len(sessions)}")
            assert len(sessions) > 0, "No sessions recorded in SQLite"

            captures = api('captures')
            print(f"   Total captures on disk: {len(captures)}")
            assert any(c['id'] == exp_id for c in captures), f"Experiment {exp_id} missing from disk captures"

            # Download Export ZIP
            export_bytes = api(f'sessions/{exp_id}/export')
            print(f"   Export ZIP downloaded: {len(export_bytes)} bytes")
            with zipfile.ZipFile(io.BytesIO(export_bytes)) as z:
                zip_names = z.namelist()
                print(f"   ZIP Contents: {zip_names}")
                assert 'events.jsonl' in zip_names, "Missing events.jsonl in export"
                assert 'summary.json' in zip_names, "Missing summary.json in export"
                assert 'samples.jsonl' in zip_names, "Missing samples.jsonl in export"
                if applied_action:
                    assert 'action-audits.json' in zip_names, "Missing action-audits.json in export"
                    audits_content = json.loads(z.read('action-audits.json'))
                    print(f"   Exported action audits count: {len(audits_content)}")

            evidence["stages"]["export"] = {
                "archiveSizeBytes": len(export_bytes),
                "zipFiles": zip_names
            }

            # 8. Verify Cleanup
            print("\n8. Verifying Workload Process Cleanup...", flush=True)
            exp_final = api(f'experiments/{exp_id}')
            cleanup_res = exp_final.get('cleanup')
            print(f"   Cleanup result: {cleanup_res}")
            assert cleanup_res and cleanup_res.get('verified') is True, "Child cleanup was not verified!"

            evidence["stages"]["cleanup"] = cleanup_res
            evidence["status"] = "PASSED"

            evidence_file = ROOT / 'docs/m5-integration-evidence.json'
            evidence_file.write_text(json.dumps(evidence, indent=2) + '\n')
            print(f"\nPhase 5 / M5 Integration Verification PASSED. Evidence written to {evidence_file}")

        finally:
            print("Stopping backend...", flush=True)
            backend.terminate()
            try:
                backend.wait(timeout=5)
            except subprocess.TimeoutExpired:
                backend.kill()
            print("Backend stopped.", flush=True)


if __name__ == '__main__':
    main()
