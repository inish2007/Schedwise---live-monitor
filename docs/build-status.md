# Phase 6 (M6) build status

Updated 4 October 2026 (Asia/Kolkata). M1, M2, M3, M4, M5, and M6 are fully implemented and verified. SchedWise v1 delivery is complete and locked. `AGENTS.md` remains unchanged.

## VERIFIED

- **M1 Core Collection & Foundation:**
  - Java 21 + Spring Boot 3.5.16 backend, SQLite JDBC 3.50.3.0, React 19.3.0, Vite 8.3.2, TypeScript 5.9.3.
  - Real `/proc` collection, CPU formulas (100% = 1 logical CPU), authenticated REST & SSE (`/api/events`).
  - `./scripts/doctor.sh`: Linux 7.0.0-28-generic x86_64, OpenJDK 21.0.11, Node 22.22.1, CPUs 0–11, CLK_TCK 100.
  - `./scripts/verify.sh`: 32/32 JUnit tests passed, 4/4 frontend unit tests passed, TypeScript strict typecheck passed, Vite production build succeeded.
  - `python3 scripts/integration.py`: M1 Linux integration passed (retained in `docs/m1-integration-evidence.json`).

- **M2 Measurable Real Contention Demo:**
  - Single-threaded HTTP hashing service, finite background workers, open-loop load client, core pinning, autogroup/cgroup verification, child lifecycle registry.
  - Python test suite (`tools/tests/test_demo.py`): 7/7 unit tests passed.
  - Live trial (`scripts/m2_integration.py`): Evidence in `docs/m2-integration-evidence.json` (baseline p95 39.51 ms degraded to 734.40 ms under contention).

- **M3 DSA Scheduling Models & Simulation Engine:**
  - Five pure-Java algorithms (`FcfsScheduler`, `RoundRobinScheduler`, `PriorityScheduler`, `SjfScheduler`, `SimplifiedCfsScheduler`).
  - Deterministic discrete-event engine (`SimulationEngine.java`) with idle jumps, single-CPU non-overlap, and horizon censoring.
  - Capture adapter and simulation API (`CaptureAdapter.java`, `CaptureService.java`, `SimulationApi.java`).
  - What-If simulation dashboard (`frontend/src/SimulationPanel.tsx`).
  - Verified via `SchedulerTest.java` (9 tests), `SimulationApiTest.java` (2 tests), and `scripts/m3_integration.py` (`docs/m3-integration-evidence.json`).

- **M4 Explainable Recommendations & Bounded Apply:**
  - Workload roles (`WorkloadRole.java`, `RoleRegistry.java`): Protected service, Background, Observe only.
  - Contention detector (`ContentionDetector.java`): core busy $\ge 80\%$, p95 degradation $\ge 1.5\times$ or deadline misses $> 0$.
  - Explainable recommendation engine (`RecommendationEngine.java`): candidate nice allocations (+5/+10) via weighted CFS simulation.
  - Unprivileged Linux action adapter (`LinuxActionAdapter.java`): targets ONLY managed, same-user, single-threaded demo background workers recorded at launch. Strict fail-closed prechecks, unprivileged `/usr/bin/renice`, immediate `/proc/<pid>/stat` field 19 readback verification.
  - Action audit trail & idempotency (`ActionAuditStore.java`, `V2__actions.sql`).
  - Verified via `RecommendationAndActionTest.java` (7 tests) and `scripts/m4_integration.py` (`docs/m4-integration-evidence.json`).

- **M5 Integrated Dashboard & Measured Validation:**
  - **Unified 6-Tab Browser Interface:**
    - Tabs: Environment, Live Monitor, Experiment, What-If, Recommendations, Results.
    - Accessible keyboard navigation, dark theme, responsive layout, clear typography, labeled units, and strict provenance tags.
    - Zero mock policy: absent telemetry displays loading/unavailable states; no fake chart arrays or hardcoded mock data.
  - **Live Monitor (`LiveMonitorPanel.tsx`):**
    - Rolling 30-sample CPU history (no backfill), logical core grid, system PSI pressure indicators, active process inspector with identity, role, and nice values.
  - **Experiment & Phase Stepper (`ExperimentPanel.tsx`, `run_experiment.py`):**
    - 7-stage phase stepper (Idle, Calibration, Warmup, Baseline, Transition, Contention, After Action, Draining, Completed).
    - 30s `AFTER_ACTION` phase measuring service responsiveness and worker throughput following confirmed priority mutation.
    - Real-time latency (p50, p95, p99), error count, deadline miss count, and worker hash rates.
  - **Scientific Comparability Engine (`ComparabilityChecker.java`, `ComparabilityTest.java`):**
    - Validates matched trial parameters (hash iterations, budget, targets), offered load rate ($\pm 5\%$), affinity core consistency, worker survival, and sample adequacy ($\ge 100$ successes for p50/p95/p99).
    - Produces `VALID` vs `REJECTED` status with explicit human-readable rejection reasons when non-comparable.
    - Mathematical metrics: $\Delta \text{Latency p95 Improvement}$, $\text{Latency Recovery}$, $\text{Worker Tradeoff}$.
  - **Results & Archive View (`ResultsPanel.tsx`):**
    - 3-way side-by-side comparison table (Baseline vs Contention vs After Action).
    - Single-click session export downloading structured archive bundle (`events.jsonl`, `samples.jsonl`, `summary.json`, `supervisor.json`, `collector-affinity.json`, `action-audits.json`).
  - **Live Validation Trial (`docs/m5-integration-evidence.json`):**
    - Latency p95 Improvement = **91.37%**; Latency Recovery = **97.27%**; Deadline misses reduced from 89 to 0.

- **M6 Reproducible Delivery & Multi-Trial Stability:**
  - **Read-Only Environment Doctor (`scripts/doctor.sh`):**
    - Inspects kernel release, architecture, WSL2 vs native Linux scope, Java 21, Node 22, npm, Python 3, platform utilities (`getconf`, `taskset`, `renice`, `top`), `SC_CLK_TCK` (100 Hz), unprivileged regular user UID (1000), CPU affinity list (`0-11`), cgroup/autogroup, PSI CPU `some` availability (with explanation that `full` is omitted for CPU), schedstat accounting status, and storage health. Exits 0 cleanly.
  - **Unified Verification Script (`scripts/verify.sh`):**
    - Executes Maven verification with 32 JUnit tests, Python demo harness test suite with 7 tests, frontend test suite with 4 Node stream/state tests, TypeScript strict typecheck, and Vite production bundle. Exits 0 cleanly with zero errors.
  - **Passive Development Startup (`scripts/dev.sh`):**
    - Starts backend on port 8080 and Vite dev server on port 5173 with port collision checks, graceful SIGINT/SIGTERM teardown, and explicit session token display. Never starts saturation workloads silently on startup.
  - **Bounded Multi-Trial Stability Evidence (`scripts/run_trials.py`, `docs/m6-trials-evidence.json`):**
    - Executed 3 matched pairs (6 trials) on Core 11 with alternating execution order:
      - Pair 1: `['UNOPTIMIZED', 'OPTIMIZED']`
      - Pair 2: `['OPTIMIZED', 'UNOPTIMIZED']`
      - Pair 3: `['UNOPTIMIZED', 'OPTIMIZED']`
    - All 6 trials completed cleanly with 100% child lifecycle cleanup (exit code 0):
      - **Pair 1:** Unoptimized p95 = 473.35 ms (336 misses) $\rightarrow$ Optimized p95 = 50.78 ms (0 misses). Improvement: **89.27%**, Recovery: **94.90%**.
      - **Pair 2:** Unoptimized p95 = 505.58 ms (399 misses) $\rightarrow$ Optimized p95 = 54.50 ms (0 misses). Improvement: **89.22%**, Recovery: **94.08%**.
      - **Pair 3:** Unoptimized p95 = 483.14 ms (385 misses) $\rightarrow$ Optimized p95 = 42.36 ms (0 misses). Improvement: **91.23%**, Recovery: **96.51%**.
      - **Aggregate Across 3 Pairs (14,310 total requests):**
        - Average p95 Latency Improvement: **89.91%**
        - Average Latency Recovery: **95.16%**
        - Deadline misses dropped from 1,120 to **0**
        - Worker hash tradeoff: $\approx -17\%\text{ to }-19\%$ (mirroring CFS weight reduction from 33.3% to 19.8%)
    - Complete raw telemetry and comparisons recorded in `docs/m6-trials-evidence.json`.
  - **Complete Technical & Operational Documentation:**
    - `README.md`: Quickstart, prerequisites, verification, development, and production build instructions.
    - `docs/architecture.md`: Closed-loop architectural pipeline, subsystem boundaries, pure-Java DSA data structures, complexity proofs, and security invariants.
    - `docs/metrics.md`: Precise metric definitions, sources, formulas, and comparability rules.
    - `docs/limitations.md`: Linux scope, WSL2 boundary, unprivileged action limits, PID race disclosures, and model caveats.
    - `docs/demo.md`: Step-by-step reproducible 8-step faculty demonstration runbook and troubleshooting.
  - **Data Integrity Audit:**
    - Zero instances of `Math.random()`, fake metrics, or synthetic fallback charts across all production frontend and backend files.

## IMPLEMENTED BUT UNVERIFIED

- Exhaustive browser acceptance remains incomplete. A focused interactive pass at localhost is now recorded below; earlier browser denials describe the previous attempts.

## Delivery Status

SchedWise v1 is complete and locked. All milestones M1 through M6 have met their acceptance gates with verifiable evidence.


## UI redesign increment — 4 October 2026

Applied the supplied Cyberpunk OS UI plan across all seven modules, with responsive navigation, shared visual tokens, session/confirmation dialogs, real-data charting and process inspection. Corrected several display-integrity defects while preserving backend APIs. See [UI redesign and verification](ui-redesign.md).

Verification executed for this increment: `./scripts/verify.sh` passed with **42 Java tests, 7 Python tests, and 10 frontend tests**, plus strict TypeScript and production build. These counts supersede the older verification counts above. Browser visual and interaction acceptance remains **unverified** because localhost browser access was blocked by a saved permission. No new performance trial was run for the redesign; existing milestone evidence above is historical.

## Hardening audit — 5 October 2026

The supplied audit plan's prefilled zero-mock assertions were disproved by source inspection: Shield manufactured CPU savings and CaptureAdapter manufactured missing service/worker demands. These fallbacks are removed. Explicit external-process selection was approved by the user for Shield, and is implemented with identity/UID checks, exact confirmation, bounded duration, command/readback checks and visible restoration failures. Old broad activation requests now fail closed.

Final hardening tests: **54 Java tests, 7 Python tests, 12 frontend tests**. TypeScript and production build are included in `./scripts/verify.sh`. Real controlled-child suspension/resumption and unselected-sibling isolation passed. See [audit findings and residual limits](bug-audit.md). Earlier “complete and locked” statements are historical; they do not establish browser acceptance or crash-safe external-process control.

## Browser testing increment — 5 October 2026

The user-requested localhost origin was accessible. Real telemetry, process inspection/search, recorded captures, five simulated models, session authentication/reconnection, and isolated Shield confirmation/suspension/resumption were exercised through the UI. Phone-width checks covered all seven modules. Fixed hidden authentication feedback/input focus and stale simulation results after input changes. Frontend verification passed 12 tests, typecheck and production build. See [browser evidence and precise limits](browser-testing.md). No new performance trial was run.
