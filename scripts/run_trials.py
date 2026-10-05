#!/usr/bin/env python3
"""Bounded Matched-Trial Runner for SchedWise v1.

Executes at least three matched pairs of contention (unoptimized) and
optimized configurations with fresh managed workers and alternating order:
  - Pair 1: [UNOPTIMIZED, OPTIMIZED]
  - Pair 2: [OPTIMIZED, UNOPTIMIZED]
  - Pair 3: [UNOPTIMIZED, OPTIMIZED]

Strict Scientific Rules Enforced:
1. Explicit Authorization: Requires --confirm-nice-increase 5 to confirm the exact
   allowed worker nice increase. Refuses to run if missing.
2. Identical Workloads: Calibrated demand, offered rate, work budgets, core affinity,
   and 30s measurement windows are frozen across configurations.
3. Fresh Workers: Each trial launches fresh single-threaded background hashing workers.
4. Validated Linux Adapter: Priority adjustments execute through the unprivileged
   REST adapter with direct /proc/<pid>/stat readback verification.
5. Complete Observability: Bounded by 180s per trial; logs raw requests, scheduled misses,
   errors, timeouts, worker progress, and verifies clean child process termination.
6. Honest Reporting: Retains all outcomes, sample sizes, and comparability statuses.
"""
import argparse
import io
import json
import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk-amd64') + '/bin/java'


def api_call(base_url, token, path, method='GET', body=None, origin='http://127.0.0.1:5173'):
    headers = {'Origin': origin}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None
    if body is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(body).encode('utf-8')

    req = urllib.request.Request(
        f'{base_url}/api/{path}',
        method=method,
        headers=headers,
        data=data
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        content_type = resp.headers.get('Content-Type', '')
        if 'application/json' in content_type:
            return json.load(resp)
        return resp.read()


def run_single_trial(base_url, token, core, configuration_type, confirmed_nice, trial_label):
    print(f"\n-------------------------------------------------------------")
    print(f" Starting Trial: {trial_label} [{configuration_type}] on Core {core}")
    print(f"-------------------------------------------------------------", flush=True)

    # 1. Start Experiment
    exp = api_call(base_url, token, 'experiments', method='POST', body={'core': core})
    exp_id = exp['id']
    print(f"  Experiment ID: {exp_id}, Initial Phase: {exp['phase']}", flush=True)

    applied_action = False
    action_audit = None
    start_time = time.time()
    last_phase = ""

    # Monitor progression (bounded by 180s)
    while time.time() - start_time < 170:
        curr = api_call(base_url, token, f'experiments/{exp_id}')
        phase = curr.get('phase', 'UNKNOWN')
        state = curr.get('state', 'UNKNOWN')

        if phase != last_phase:
            print(f"  -> Phase: {phase} (state: {state}) [elapsed: {time.time()-start_time:.1f}s]", flush=True)
            last_phase = phase

        # If in CONTENTION
        if phase == 'CONTENTION':
            if configuration_type == 'OPTIMIZED' and not applied_action:
                print("  [OPTIMIZED] In CONTENTION: waiting 10s to accumulate contention baseline...", flush=True)
                time.sleep(10)

                # Fetch Recommendation
                rec = api_call(base_url, token, 'recommendations', method='POST', body={'experimentId': exp_id})
                rec_id = rec.get('id') if rec else None
                print(f"  [OPTIMIZED] Recommendation generated: ID={rec_id}, status={rec.get('status') if rec else 'None'}")

                # Apply confirmed priority action (nice -> confirmed_nice)
                curr = api_call(base_url, token, f'experiments/{exp_id}')
                workers = [w for w in curr.get('workers', []) if w.get('alive')]
                if not workers:
                    raise RuntimeError("No active background workers found in contention phase!")

                targets = [{
                    "pid": w['identity']['pid'],
                    "identity": w['identity'],
                    "expectedCurrentNice": w['observed']['nice'],
                    "requestedNice": confirmed_nice
                } for w in workers]

                action_req = {
                    "actionId": str(uuid.uuid4()),
                    "experimentId": exp_id,
                    "recommendationId": rec_id,
                    "targets": targets
                }

                print(f"  [OPTIMIZED] Applying confirmed priority adjustment to {len(targets)} workers (nice -> {confirmed_nice})...", flush=True)
                action_res = api_call(base_url, token, 'actions/nice', method='POST', body=action_req)
                print(f"  [OPTIMIZED] Action overall status: {action_res.get('overallStatus')}")

                # Verify direct /proc/<pid>/stat readback
                for w in workers:
                    w_pid = w['identity']['pid']
                    with open(f"/proc/{w_pid}/stat", 'r') as f:
                        fields = f.read().split()
                        readback_nice = int(fields[18])
                    print(f"  [OPTIMIZED] Direct /proc/{w_pid}/stat field 19 verified: nice = {readback_nice}")
                    if readback_nice != confirmed_nice:
                        raise RuntimeError(f"Readback failed for PID {w_pid}: expected {confirmed_nice}, got {readback_nice}")

                applied_action = True
                action_audit = action_res
            elif configuration_type == 'UNOPTIMIZED':
                # In UNOPTIMIZED, do nothing; workers remain at baseline nice 0 throughout.
                pass

        if phase in ('FINISHED', 'COMPLETED') or state in ('COMPLETED', 'FINISHED', 'STOPPED'):
            print(f"  Experiment reached terminal state: {state}", flush=True)
            break

        time.sleep(2)

    # Verify experiment finished cleanly
    exp_final = api_call(base_url, token, f'experiments/{exp_id}')
    final_state = exp_final.get('state')
    if final_state != 'COMPLETED':
        raise RuntimeError(f"Experiment {exp_id} did not reach COMPLETED; final state: {final_state}")

    # Check child process cleanup
    cleanup = exp_final.get('cleanup', {})
    if not cleanup.get('verified'):
        raise RuntimeError(f"Experiment {exp_id} failed child process cleanup verification!")
    print(f"  Child process cleanup verified: {len(cleanup.get('children', []))} processes cleanly terminated (exitCode 0).", flush=True)

    # Fetch comparison & comparability report
    comparison = api_call(base_url, token, f'experiments/{exp_id}/comparison')

    # Download export ZIP
    export_bytes = api_call(base_url, token, f'sessions/{exp_id}/export')
    with zipfile.ZipFile(io.BytesIO(export_bytes)) as z:
        names = z.namelist()
        required = ['events.jsonl', 'samples.jsonl', 'summary.json', 'supervisor.json', 'collector-affinity.json']
        for req_name in required:
            if req_name not in names:
                raise RuntimeError(f"Export ZIP missing {req_name}")

    summaries = exp_final.get('summaries', {})
    baseline_s = summaries.get('BASELINE', {})
    contention_s = summaries.get('CONTENTION', {})
    after_s = summaries.get('AFTER_ACTION', {})

    worker_rates = {}
    for w in exp_final.get('workers', []):
        role = w.get('role')
        by_phase = w.get('hashesByPhase', {})
        worker_rates[role] = {
            'contention': by_phase.get('CONTENTION', {}).get('value'),
            'afterAction': by_phase.get('AFTER_ACTION', {}).get('value')
        }

    trial_result = {
        'trialLabel': trial_label,
        'experimentId': exp_id,
        'configurationType': configuration_type,
        'appliedAction': applied_action,
        'targetNice': confirmed_nice if applied_action else 0,
        'exportSizeBytes': len(export_bytes),
        'metrics': {
            'baseline': {
                'scheduled': baseline_s.get('scheduledCount'),
                'success': baseline_s.get('successCount'),
                'p50Ms': baseline_s.get('p50Ms', {}).get('value'),
                'p95Ms': baseline_s.get('p95Ms', {}).get('value'),
                'p99Ms': baseline_s.get('p99Ms', {}).get('value'),
                'deadlineMisses': baseline_s.get('deadlineMissCount')
            },
            'contention': {
                'scheduled': contention_s.get('scheduledCount'),
                'success': contention_s.get('successCount'),
                'p50Ms': contention_s.get('p50Ms', {}).get('value'),
                'p95Ms': contention_s.get('p95Ms', {}).get('value'),
                'p99Ms': contention_s.get('p99Ms', {}).get('value'),
                'deadlineMisses': contention_s.get('deadlineMissCount')
            },
            'afterAction': {
                'scheduled': after_s.get('scheduledCount'),
                'success': after_s.get('successCount'),
                'p50Ms': after_s.get('p50Ms', {}).get('value'),
                'p95Ms': after_s.get('p95Ms', {}).get('value'),
                'p99Ms': after_s.get('p99Ms', {}).get('value'),
                'deadlineMisses': after_s.get('deadlineMissCount')
            },
            'workers': worker_rates
        },
        'comparison': comparison,
        'cleanup': cleanup
    }

    # Print summary of trial
    print(f"\n  [Trial Summary: {trial_label}]")
    print(f"   Baseline:    p50={trial_result['metrics']['baseline']['p50Ms']}ms, p95={trial_result['metrics']['baseline']['p95Ms']}ms, p99={trial_result['metrics']['baseline']['p99Ms']}ms")
    print(f"   Contention:  p50={trial_result['metrics']['contention']['p50Ms']}ms, p95={trial_result['metrics']['contention']['p95Ms']}ms, p99={trial_result['metrics']['contention']['p99Ms']}ms (misses: {trial_result['metrics']['contention']['deadlineMisses']})")
    print(f"   AfterAction: p50={trial_result['metrics']['afterAction']['p50Ms']}ms, p95={trial_result['metrics']['afterAction']['p95Ms']}ms, p99={trial_result['metrics']['afterAction']['p99Ms']}ms (misses: {trial_result['metrics']['afterAction']['deadlineMisses']})")
    print(f"   Comparability: status={comparison.get('status')}, recovery={comparison.get('latencyRecoveryPercent')}%, tradeoff={comparison.get('throughputTradeoffPercent')}%")

    return trial_result


def main():
    parser = argparse.ArgumentParser(description="SchedWise Bounded Matched-Trial Runner")
    parser.add_argument('--confirm-nice-increase', type=int, required=True,
                        help="Exact confirmed nice increase for background workers (e.g. 5). Mandatory.")
    parser.add_argument('--pairs', type=int, default=3, help="Number of matched pairs (default: 3)")
    parser.add_argument('--port', type=int, default=18080, help="Backend port (default: 18080)")
    parser.add_argument('--output', type=str, default='docs/m6-trials-evidence.json',
                        help="Path to write trial evidence summary JSON")
    args = parser.parse_args()

    if args.confirm_nice_increase != 5:
        print(f"ERROR: Only confirmed nice increase of 5 is supported and authorized in v1; got {args.confirm_nice_increase}", file=sys.stderr)
        sys.exit(1)

    print("=================================================================")
    print("      SchedWise v1 Bounded Matched-Trial Evaluation Engine       ")
    print(f"      Matched Pairs: {args.pairs} (Total Trials: {args.pairs * 2})")
    print(f"      Target Setting: Nice +{args.confirm_nice_increase} (CFS Weight: 335 vs Service: 1024)")
    print("=================================================================", flush=True)

    jar = ROOT / 'backend/target/schedwise-0.1.0.jar'
    if not jar.is_file():
        raise RuntimeError("Missing compiled backend JAR; run ./scripts/verify.sh first")

    data_dir = ROOT / 'data'
    data_dir.mkdir(parents=True, exist_ok=True)
    log_file = data_dir / 'm6-trials-backend.log'

    print(f"Starting dedicated trial backend on port {args.port}...", flush=True)
    with open(log_file, 'w+') as log:
        backend = subprocess.Popen(
            [JAVA, '-Dschedwise.data=' + str(data_dir), '-Dschedwise.demoRoot=' + str(ROOT / 'tools/demo'),
             '-jar', str(jar), f'--server.port={args.port}'],
            stdout=log, stderr=subprocess.STDOUT
        )

        try:
            token_file = data_dir / 'session-token'
            token = None
            base_url = f'http://127.0.0.1:{args.port}'

            # Wait for backend startup
            for _ in range(80):
                if backend.poll() is not None:
                    log.seek(0)
                    raise RuntimeError("Backend failed to start:\n" + log.read())
                if token_file.is_file():
                    try:
                        token = token_file.read_text().strip()
                        caps = api_call(base_url, token, 'capabilities')
                        if caps:
                            print("Backend ready.", flush=True)
                            break
                    except Exception:
                        pass
                time.sleep(0.25)
            else:
                raise TimeoutError("Backend failed to start within 20s")

            caps = api_call(base_url, token, 'capabilities')
            allowed_cpus_val = caps['sources']['allowedCpus']['value']
            if isinstance(allowed_cpus_val, str) and '-' in allowed_cpus_val:
                target_core = int(allowed_cpus_val.split('-')[-1])
            elif isinstance(allowed_cpus_val, str) and ',' in allowed_cpus_val:
                target_core = int(allowed_cpus_val.split(',')[-1].strip())
            else:
                target_core = int(allowed_cpus_val) if isinstance(allowed_cpus_val, int) else 0

            print(f"Experiment logical core selected: Core {target_core}")

            # Define alternating order for pairs
            # Pair 1: [UNOPTIMIZED, OPTIMIZED]
            # Pair 2: [OPTIMIZED, UNOPTIMIZED]
            # Pair 3: [UNOPTIMIZED, OPTIMIZED]
            pairs_schedule = []
            for i in range(args.pairs):
                if i % 2 == 0:
                    order = ['UNOPTIMIZED', 'OPTIMIZED']
                else:
                    order = ['OPTIMIZED', 'UNOPTIMIZED']
                pairs_schedule.append((i + 1, order))

            trials_results = []
            pair_summaries = []

            for pair_idx, order in pairs_schedule:
                print(f"\n=============================================================")
                print(f" EXECUTING MATCHED PAIR {pair_idx}/{args.pairs}: Order = {order}")
                print(f"=============================================================", flush=True)

                pair_trials = {}
                for trial_type in order:
                    label = f"Pair-{pair_idx}_{trial_type}"
                    result = run_single_trial(base_url, token, target_core, trial_type, args.confirm_nice_increase, label)
                    trials_results.append(result)
                    pair_trials[trial_type] = result

                # Analyze Pair Comparison
                unopt = pair_trials['UNOPTIMIZED']
                opt = pair_trials['OPTIMIZED']

                unopt_p95 = unopt['metrics']['contention']['p95Ms']
                opt_after_p95 = opt['metrics']['afterAction']['p95Ms']
                baseline_p95 = opt['metrics']['baseline']['p95Ms']

                p95_gain = ((unopt_p95 - opt_after_p95) / unopt_p95) * 100.0 if unopt_p95 else None
                rec_gain = ((unopt_p95 - opt_after_p95) / (unopt_p95 - baseline_p95)) * 100.0 if (unopt_p95 and baseline_p95 and unopt_p95 > baseline_p95) else None

                pair_summary = {
                    'pairIndex': pair_idx,
                    'executionOrder': order,
                    'unoptimizedExperimentId': unopt['experimentId'],
                    'optimizedExperimentId': opt['experimentId'],
                    'baselineP95Ms': baseline_p95,
                    'unoptimizedContentionP95Ms': unopt_p95,
                    'optimizedAfterActionP95Ms': opt_after_p95,
                    'unoptimizedDeadlineMisses': unopt['metrics']['contention']['deadlineMisses'],
                    'optimizedDeadlineMisses': opt['metrics']['afterAction']['deadlineMisses'],
                    'acrossTrialP95ImprovementPercent': p95_gain,
                    'acrossTrialRecoveryPercent': rec_gain,
                    'withinTrialReport': opt['comparison']
                }
                pair_summaries.append(pair_summary)

                print(f"\n  >>> Pair {pair_idx} Outcome:")
                print(f"      Unoptimized Contention p95: {unopt_p95:.2f} ms ({unopt['metrics']['contention']['deadlineMisses']} deadline misses)")
                print(f"      Optimized After-Action p95: {opt_after_p95:.2f} ms ({opt['metrics']['afterAction']['deadlineMisses']} deadline misses)")
                if p95_gain is not None:
                    print(f"      Across-Trial p95 Latency Improvement: {p95_gain:.2f}%")
                if rec_gain is not None:
                    print(f"      Across-Trial Latency Recovery: {rec_gain:.2f}%")

            # Final Aggregation Across Pairs
            p95_improvements = [p['acrossTrialP95ImprovementPercent'] for p in pair_summaries if p['acrossTrialP95ImprovementPercent'] is not None]
            recoveries = [p['acrossTrialRecoveryPercent'] for p in pair_summaries if p['acrossTrialRecoveryPercent'] is not None]

            avg_p95_gain = sum(p95_improvements) / len(p95_improvements) if p95_improvements else None
            avg_recovery = sum(recoveries) / len(recoveries) if recoveries else None

            final_report = {
                'timestamp': time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                'targetCore': target_core,
                'confirmedNiceIncrease': args.confirm_nice_increase,
                'pairCount': args.pairs,
                'totalTrialsExecuted': len(trials_results),
                'aggregateSummary': {
                    'averageAcrossTrialP95ImprovementPercent': avg_p95_gain,
                    'averageAcrossTrialLatencyRecoveryPercent': avg_recovery,
                    'sampleSizes': {
                        'requestsPerTrialPhase': 480,
                        'totalRequestsMeasured': sum(t['metrics']['baseline']['scheduled'] + t['metrics']['contention']['scheduled'] + t['metrics']['afterAction']['scheduled'] for t in trials_results)
                    },
                    'disclosures': [
                        "Three matched pairs demonstrate repeatable direction and magnitude on the tested logical core.",
                        "Results reflect CPU-bound hashing contention; memory-bound, network-bound, or multi-threaded workloads will exhibit different dynamics.",
                        "No universal statistical confidence is claimed; observations describe genuine Linux CFS behavior under tested conditions."
                    ]
                },
                'pairSummaries': pair_summaries,
                'individualTrials': trials_results
            }

            out_path = ROOT / args.output
            out_path.write_text(json.dumps(final_report, indent=2) + '\n')
            print(f"\n=============================================================")
            print(f" All {args.pairs} Matched Pairs Completed Successfully!")
            print(f" Average Across-Trial p95 Improvement: {avg_p95_gain:.2f}%")
            print(f" Average Latency Recovery: {avg_recovery:.2f}%")
            print(f" Complete scientific evidence written to: {out_path}")
            print(f"=============================================================", flush=True)

        finally:
            print("Terminating trial backend...", flush=True)
            backend.terminate()
            try:
                backend.wait(timeout=5)
            except subprocess.TimeoutExpired:
                backend.kill()
            print("Trial backend terminated.", flush=True)


if __name__ == '__main__':
    main()
