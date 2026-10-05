# Phase 6 (M6) handoff — SchedWise v1 Final Delivery

## Run and reproduce

From the repository root:
1. Run `./scripts/doctor.sh` to confirm environment and prerequisites (Linux kernel, JDK 21, Node 22, Python 3, taskset).
2. Start development servers: `./scripts/dev.sh`. Open `http://127.0.0.1:5173`, read `data/session-token`, paste it into the UI and click **Connect**.
3. In the 6-tab dashboard:
   - **Environment:** Inspect kernel, CPU topology, clock ticks, PSI pressure, and explicit environment limitations.
   - **Live Monitor:** Observe real-time per-core CPU utilization, rolling 30-sample history (no fake backfill), and the live process table with identities and nice values.
   - **Experiment:** Run a controlled 5-stage trial on an allowed logical core (`CALIBRATION` $\rightarrow$ `WARMUP` $\rightarrow$ `BASELINE` $\rightarrow$ `TRANSITION` $\rightarrow$ `CONTENTION` $\rightarrow$ `AFTER_ACTION`).
   - **What-If:** Select real captured sessions to simulate FCFS, Round Robin, Priority, SJF, and CFS on real request arrival traces.
   - **Recommendations:** View transparent contention evidence, evaluate candidate nice changes (+5/+10), view simulated tradeoffs, and confirm unprivileged apply with readback.
   - **Results:** Inspect the 3-way comparison table (Baseline vs Contention vs After Action), check scientific comparability criteria, and download the session export bundle.
4. Run automated verification:
   - Full build & test verification: `./scripts/verify.sh` (32 JUnit tests, 7 Python harness tests, 4 Node stream tests, TypeScript strict typecheck, and Vite production bundle).
   - Python experiment harness unit tests: `python3 tools/tests/test_demo.py`
   - Real multi-trial stability evaluation (3 matched pairs / 6 trials with alternating order): `python3 scripts/run_trials.py --confirm-nice-increase 5`

## Decisions and boundaries established in v1

- **Zero Mock Policy (`AGENTS.md`):**
  - All metrics originate from `/proc` or real instrumentation.
  - Zero instances of `Math.random()`, fake metrics, or synthetic fallback charts across all production code.
- **Pure-Java DSA Engine:**
  - Schedulers implement discrete-event models (FCFS, Round Robin, Priority, SJF, classic simplified CFS) using pure Java collections (`ArrayDeque`, `PriorityQueue`, `TreeSet`).
  - Schedulers operate on immutable capture DTOs and never run OS commands or mutate system state.
- **Strict Control Boundaries:**
  - Unprivileged `renice` targets ONLY managed, same-user, single-threaded demo workers whose identities are recorded at launch.
  - Fail-closed prechecks verify UID, thread count, and current nice before acting.
  - Immediate readback of `/proc/<pid>/stat` field 19 confirms priority modification.
  - Discloses irreversible unprivileged lower priority (cannot raise priority back to nice 0 without root).
- **Scientific Comparability Engine:**
  - Enforces matched trials: offered rate consistency ($\pm 5\%$), identical workload parameters, core affinity consistency, worker survival, and sample size adequacy ($\ge 100$ successes for p50/p95/p99).
  - Produces `VALID` vs `REJECTED` status with explicit human-readable rejection reasons when non-comparable.
- **Clean Lifecycle Guarantee:**
  - 100% child process cleanup on exit, timeout, or stop via `PR_SET_PDEATHSIG` and explicit PID registry tracking.

## Verification evidence

- **Full Automated Verification:**
  - `./scripts/verify.sh` exits 0 cleanly.
  - 32/32 JUnit tests pass.
  - 7/7 Python demo harness tests pass.
  - 4/4 Node stream tests pass.
  - TypeScript strict typecheck passes with 0 errors.
  - Vite production build succeeds cleanly.
- **Environment Doctor (`scripts/doctor.sh`):**
  - All 6 diagnostic categories pass cleanly in read-only mode.
- **Multi-Trial Stability Run (`docs/m6-trials-evidence.json`):**
  - 3 matched pairs (6 trials) on Core 11 with alternating order:
    - Average Latency p95 Improvement: **89.91%**
    - Average Latency Recovery: **95.16%**
    - Deadline misses dropped from 1,120 to **0** across 14,310 measured requests
    - Worker hash tradeoff: $\approx -17\%\text{ to }-19\%$ (matching CFS weight change from 33.3% to 19.8%)
  - Clean child cleanup across all trials (exit code 0).

## Final Delivery

SchedWise v1 is complete and locked. All milestones M1 through M6 are fully implemented and verified. Future extensions (EEVDF-inspired simulation, cgroup v2 delegation, multi-core scheduling) belong to v2 and are tracked in `docs/limitations.md`.

