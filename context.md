# SchedWise Context & Project State

> **Last Updated:** 2026-10-05T21:51:00+05:30  
> **Status:** Complete: Full UI/UX Redesign across all SchedWise views (Live Monitor, Game Shield, Lab, Models, Advisor, Results, System). Double headers completely eliminated. Browser audit passed with 0 console errors and 0 network failures. All 10 high-resolution screenshots saved. Full verification suite passed cleanly (Exit 0).  
> **Maintenance Protocol:** This file is actively updated upon every prompt execution to track repository context, architectural boundaries, verified milestones, test outcomes, and active next steps.

---

## 1. Project Mission & Core Architecture

SchedWise is a Linux CPU allocation advisor and workload optimizer designed for developers, small-server operators, and gamers/streamers sharing limited CPU capacity between a latency-sensitive application (API service or game) and background jobs (compilation, batch hashing, electron apps).

### Core End-to-End Flows
1. **Contention Advisor Flow:**
   $$\text{Real collection} \longrightarrow \text{Contention evidence} \longrightarrow \text{Measured-input simulation} \longrightarrow \text{Explainable recommendation} \longrightarrow \text{Confirmed priority change} \longrightarrow \text{Measured validation}$$
2. **Game Shield & Workload Optimizer Flow:**
   $$\text{Inspect mode (telemetry)} \longrightarrow \text{Advanced selection} \longrightarrow \text{Exact confirmation} \longrightarrow \text{Pidfd journaled SIGSTOP} \longrightarrow \text{Automatic restore (SIGCONT on lease/timeout/exit)}$$

### Locked Technology Stack
- **Backend:** Java 21 (OpenJDK), Spring Boot 3.5.16, Maven Wrapper 3.3.4 (Maven 3.9.11).
- **Frontend:** React 19.3.0, TypeScript 5.9.3, Vite 8.3.2 (development proxy and static build in `frontend/dist`).
- **Persistence:** SQLite 3.50.3.0 via JDBC, versioned Flyway-style SQL migrations (`V1__sessions.sql`, `V2__actions.sql` via `PRAGMA user_version`).
- **Transport:** Loopback REST APIs + Server-Sent Events (SSE) with sequence cursors and stream replay.
- **Harness & Workloads:** Python 3 standard library (`ctypes`, `resource`, `selectors`, `asyncio`, `hashlib`, `os.pidfd_open`, `signal.pidfd_send_signal`) + Linux utilities (`taskset`, `renice`, `getconf`).

---

## 2. Non-Negotiable Data & Operational Rules (`AGENTS.md`)

1. **Zero Mock Policy:** All live telemetry, capture inputs, recommendation evidence, and action readbacks must originate from real Linux `/proc` files or real running processes. No fake process lists (P1/P2), synthetic chart arrays, random metrics, or fallback simulations presented as live telemetry.
2. **Data Classification:** Every data point must be strictly tagged: `MEASURED`, `DERIVED`, `SIMULATED`, `RECORDED`.
3. **Null vs Zero vs Stale:** Unavailable data is `null`/unavailable with a documented reason; zero is a distinct measured value. Stale data (>3.5s without update) must be explicitly flagged.
4. **Pure Java DSA Engine:** Schedulers implement discrete-event models (FCFS, RR, Priority, SJF, simplified CFS) in pure Java without running OS commands or mutating system state.
5. **Censoring & Event Jumping:** Models jump across idle intervals without nanosecond looping. Unfinished jobs at the simulation horizon remain marked `censored` without fabricated turnaround times.
6. **Strict Control Boundaries:** Priority mutation (nice adjustment) is strictly bounded to same-user, single-threaded, tracked child workers created by the experiment harness.
7. **System Essentials Immunity Shield:** Audio daemons (`pipewire`, `wireplumber`), display compositors (`gnome-shell`, `Xorg`, `kwin`), input managers, and kernel worker threads (`PID <= 100`) are 100% immune from being frozen or killed.
8. **Comparability Integrity:** Never claim an improvement or percentage recovery without evaluating offered rate, parameters, affinity, and worker survival.
9. **Pidfd-Secured Signal Operations:** External pause/resume actions require Linux `pidfd` verification to prevent PID reuse races. Original states and target identities are recorded in a bounded atomic recovery journal (`shield-state.json`) prior to issuing signals.

---

## 3. Repository Structure & Key Components

```text
.
├── AGENTS.md                          # Mandatory agent instructions and locked rules
├── plan.md                            # Comprehensive specification, roadmap, and milestone gates
├── README.md                          # Quickstart, prerequisites, and run instructions
├── context.md                         # Living project context file (updated each execution)
├── backend/                           # Java 21 / Spring Boot application
│   ├── pom.xml                        # Maven configuration
│   ├── mvnw / mvnw.cmd                # Maven wrapper
│   └── src/
│       ├── main/java/dev/schedwise/
│       │   ├── Application.java       # Spring Boot main entrypoint
│       │   ├── action/                # LinuxActionAdapter (unprivileged renice, prechecks, readback)
│       │   ├── api/                   # REST controllers (MonitorApi, ExperimentApi, SimulationApi, RecommendationAndActionApi, GameShieldApi, SessionAuth)
│       │   ├── experiment/            # ExperimentManager (owns Python runner, child lifecycle, worker reset, ComparabilityChecker)
│       │   ├── gameshield/            # SystemProcessShield, ProcessClassifier, ShieldController, ShieldState, ShieldCandidate, GuardianBridge
│       │   ├── linux/                 # ProcParser, LinuxSource, ProcSource, ThreadAffinity
│       │   ├── model/                 # Telemetry DTOs (Snapshot, Cpu, ProcessSample, Value<T>, WorkloadRole)
│       │   ├── monitor/               # Collector (1s scheduled ring buffer), Capabilities
│       │   ├── persistence/           # SessionStore, ActionAuditStore (SQLite migrations V1 & V2)
│       │   ├── recommendation/        # ContentionDetector, RecommendationEngine, RoleRegistry
│       │   ├── scheduler/             # Pure Java DSA schedulers (Job, LinuxWeights, FCFS, RR, Priority, SJF, CFS)
│       │   └── simulation/            # Event-driven engine, CaptureAdapter, CaptureService, SimulationModels
│       ├── main/resources/            # application.properties, shield/guardian.py, db/migration/V1__sessions.sql, V2__actions.sql
│       └── test/java/dev/schedwise/   # JUnit tests (AuthTest, CollectorTest, ProcParserTest, SchedulerTest, SimulationApiTest, RecommendationAndActionTest, ComparabilityTest, SystemProcessShieldTest, ShieldControllerTest, GameShieldApiTest)
├── frontend/                          # Vite / React / TypeScript dashboard
│   ├── index.html                     # Single-page entry HTML
│   ├── package.json                   # Pinned frontend dependencies
│   ├── vite.config.ts                 # Dev server configuration with /api proxy to :8080 and dev token injection
│   ├── tests.mjs                      # Native Node test runner for SSE stream and data state logic (12 tests)
│   └── src/
│       ├── main.tsx                   # Main App component (4 grouped tabs: Monitor, Game Shield, Experiments, System, URL hash routing, auto-auth)
│       ├── http.ts                    # Centralized fetch client with automatic session bootstrap, cookies, CSRF, and timeouts
│       ├── LiveMonitorPanel.tsx       # Real machine vs core CPU history sparkline (no backfill), process table, inspector
│       ├── ExperimentPanel.tsx        # Experiment control UI (7-stage phase stepper bar, AFTER_ACTION support, p99)
│       ├── SimulationPanel.tsx        # What-If DSA simulation UI (capture selector, model comparison, Gantt)
│       ├── RecommendationPanel.tsx    # Evidence, candidate cards, confirmation modal, audit table
│       ├── ResultsPanel.tsx           # 3-way measurement table (Baseline vs Contention vs After), Comparability Engine, Session Archive
│       ├── GameShieldPanel.tsx        # Redesigned 3-tab Game Shield (Overview with Contention Card, Applications, Activity & Recovery with journal)
│       ├── EnvironmentPanel.tsx       # Discovered Linux scope, kernel, allowed CPUs, SC_CLK_TCK, storage, PSI, limitations
│       ├── gameshield-types.ts        # TypeScript interfaces for Game Shield candidates, state, and typed errors
│       ├── style.css                  # Restrained Sunset color palette on dark canvas (#09080d, #17121b, coral, orange, violet)
│       └── types.ts                   # TypeScript interfaces matching backend Telemetry DTOs
├── tools/
│   ├── demo/                          # Real bounded experiment harness
│   ├── shield_recover.py              # Standalone manual recovery tool reading recovery journal
│   └── tests/
│       ├── test_demo.py               # Unit tests for experiment harness
│       └── test_guardian.py           # Unit tests for Python pidfd guardian, journal, and signal handling
├── scripts/                           # Operational scripts
│   ├── doctor.sh                      # Diagnostic check of Linux environment, Java, Node, tools, pidfd
│   ├── dev.sh                         # Starts backend (:8080) and frontend dev server (:5173) with automatic auth
│   ├── start.sh                       # Production launcher building and serving frontend via backend
│   ├── shield_recover.sh              # Standalone shell script for manual emergency recovery
│   ├── verify.sh                      # Full verification: Maven verify + Python tests + npm test + tsc + vite build
│   └── run_trials.py                  # Bounded multi-trial stability runner (3 matched pairs / 6 trials)
└── docs/                              # Evidence, user manuals, and presentation guides
    ├── SCHEDWISE_EASY_EXPLANATION.md  # Plain-English guide on CPU threads, Monitor, Shield, and Experiments
    ├── metrics.md                     # Metric units, sources, and definitions
    ├── architecture.md                # Full system architecture
    └── limitations.md                 # Unprivileged Linux and virtualization boundaries
```

---

## 4. Milestone Progress Matrix

| Milestone | Scope | Implementation | Verification Gate | Status |
|:---|:---|:---:|:---:|:---:|
| **M1: Real Collection** | Proc parsing, CPU formulas, capabilities, SSE/REST, SQLite, Live Monitor UI | Complete | Verified via `integration.py`, JUnit, frontend tests. | **Passed** |
| **M2: Measurable Demo** | HTTP hashing service, load client, background workers, core pinning, supervisor, baseline/contention phases | Complete | Verified via `tools/tests/test_demo.py` & `docs/m2-integration-evidence.json`. | **Passed** |
| **M3: Scheduling Models & DSA** | FCFS, RR, Priority, SJF, simplified CFS (`TreeSet`), event engine, modeled timeline from real trace | Complete | Verified via `SchedulerTest.java`, `SimulationApiTest.java`, and `scripts/m3_integration.py`. | **Passed** |
| **M4: Recommendations & Apply** | Contention detection, candidate evaluation (nice 5/10), unprivileged renice adapter, audit log, confirmation UI | Complete | Verified via `RecommendationAndActionTest.java` (7 tests) and `scripts/m4_integration.py`. | **Passed** |
| **M5: Integrated Dashboard & Validation** | 6-tab UI, 3-way measurements, AFTER_ACTION phase, p99Ms, ComparabilityChecker, action audits in export, session browser | Complete | Verified via `./scripts/verify.sh` and full live trial (`docs/m5-integration-evidence.json`). | **Passed** |
| **M6: Delivery & Runbooks** | Diagnostic doctor, unified verify, architecture docs, runbook, 3-pair matched-trial stability evaluation | Complete | Verified via `doctor.sh`, `verify.sh`, and `run_trials.py` (`docs/m6-trials-evidence.json`). | **Passed** |
| **Extension: Game Shield** | Safer controls (Inspect->Select->Confirm->On->Restore->Off), pidfd Python guardian, automatic local auth (cookie/CSRF), Sunset UI redesign | Complete | Verified via `./scripts/verify.sh` (48 JUnit tests, 12 frontend Node tests, 15 Python tests, TypeScript strict, Vite build). | **Passed** |

---

## 5. Current Verified System State

- **Environment:** Linux 7.0.0-28-generic x86_64, 12 logical CPUs (0–11), OpenJDK 21.0.11, Node 22.22.1, npm 9.2.0, Python 3.14.4, CLK_TCK 100, native `os.pidfd_open` and `signal.pidfd_send_signal` verified.
- **Backend Tests:** 48/48 JUnit tests passing (`AuthTest`, `ProcParserTest`, `CollectorTest`, `SchedulerTest`, `SimulationApiTest`, `RecommendationAndActionTest`, `ComparabilityTest`, `SystemProcessShieldTest`, `ShieldControllerTest`, `GameShieldApiTest`).
- **Frontend Tests & Build:** 12/12 Node stream/state tests passing; strict `tsc --noEmit` cleanly passing (0 errors); Vite production bundle built successfully in 305ms.
- **Python Tests:** 15/15 tests passing (7/7 in `tools/tests/test_demo.py`, 8/8 in `tools/tests/test_guardian.py`).
- **Full Verification Suite:** `./scripts/verify.sh` executes frontend node tests, TypeScript compile, Vite build, backend Maven verify, and Python tests, exiting cleanly with code 0.
- **Zero Mock Policy Audit:** Confirmed zero instances of `Math.random()`, fake metrics, placeholder chart arrays, or synthetic fallback telemetry across all production files.
- **Safer Controls Verification:**
  - Game Shield defaults to Inspect mode with real-time contention observations, CPU PSI, and explicit FPS disclaimers.
  - Pausing background apps is an advanced option starting with zero selections, bounded to 16 same-user processes and 1–30 min duration (default 15 min).
  - Explicit confirmation modal details the exact target, signals (`SIGSTOP`), process identity, and potential disruption.
  - Persistent On/Off command bar visible across all Shield subtabs (`Overview`, `Applications`, `Activity & recovery`).
  - Python guardian handles signals using `pidfd` and records state to atomic journal `shield-state.json` before signal issuance.
  - Automatic unpause occurs on explicit Off, backend disconnect (pipe EOF), heartbeat lease expiration (60s lease / 15s heartbeat), or target process exit.
  - Independent manual recovery CLI tool (`./scripts/shield_recover.sh`) provided and verified.
  - Automatic connection management replaces manual session key entry via HttpOnly session cookies, CSRF tokens, and launcher token bootstrapping.
  - Restrained Sunset color scheme implemented on dark canvas (`#09080d`, `#17121b`, coral `#ff977d`, orange `#ffb26b`, violet `#c4a7ff`) with zero electric cyan and full reduced-motion accessibility.
- **Documentation & Viva Preparation:**
  - Added [`docs/SCHEDWISE_EASY_EXPLANATION.md`](file:///home/k-inish-kumar/Projects/os%20project/docs/SCHEDWISE_EASY_EXPLANATION.md) covering AMD Ryzen 5 5500U 12-thread counting (`0-11`), Live Monitor, Game Shield, and the 4 Experiment functions in plain English.
  - Added OS and DSA defense guide and viva presentation scripts for academic and faculty review.
