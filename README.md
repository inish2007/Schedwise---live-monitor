# SchedWise — Linux CPU Allocation Advisor (v1)

SchedWise is a Linux CPU allocation advisor and workload analyzer designed for developers and small-server operators sharing limited CPU capacity between a latency-sensitive service and CPU-bound background jobs (such as batch hashing, compilation, or compression).

SchedWise delivers an end-to-end, scientifically validated, closed-loop workflow:
$$\text{Real collection} \longrightarrow \text{Contention evidence} \longrightarrow \text{Measured-input simulation} \longrightarrow \text{Explainable recommendation} \longrightarrow \text{Confirmed priority change} \longrightarrow \text{Measured validation}$$

Zero mock telemetry policy: every chart, process identity, queue prediction, and latency figure is grounded in real Linux `/proc` interfaces or application event instrumentation.

---

## 1. System Requirements & Platform Scope

### Supported Platforms
- **Native Linux (Preferred):** Ubuntu 22.04+, Debian 12+, Fedora 38+, or any modern Linux distribution with kernel $\ge 5.4$ and a mounted `/proc` filesystem.
- **Windows via WSL2 / Linux VM:** Supported by running the backend, workloads, and collection within WSL2 (Ubuntu 22.04+). Open the dashboard in your Windows browser via forwarded localhost (`127.0.0.1`).
  - *Important Scope Limitation:* Telemetry and scheduler modifications describe the Linux WSL2 subsystem only; native Windows host applications are outside the scope of Linux scheduler control.

### Prerequisites
- **Java:** OpenJDK or Oracle JDK **21** (LTS).
- **Node.js:** Node **20+** (tested with Node 22.22.1) and **npm**.
- **Python:** Python **3.10+** (tested with Python 3.14.4) using standard-library tooling.
- **Linux Utilities:** `taskset`, `renice`, `getconf`, `top`, `df`.
- **Privilege:** Unprivileged regular user (UID $> 0$). Running as `root` is strictly forbidden.

---

## 2. Quickstart & Development Setup

### Step 1: Pre-flight Diagnostic Check
Inspect your platform capabilities, clock tick rate, CPU topology, and optional kernel metrics:
```bash
./scripts/doctor.sh
```

### Step 2: Start Development Server
Starts the Spring Boot backend on `127.0.0.1:8080` and the Vite development server on `127.0.0.1:5173`:
```bash
./scripts/dev.sh
```
Startup is completely passive: it does **not** launch workloads, saturate cores, or alter system priorities.

### Step 3: Access the Dashboard & Authenticate
1. Open **http://127.0.0.1:5173** in your browser.
2. Read the per-launch security token generated in `data/session-token`:
   ```bash
   cat data/session-token
   ```
3. Paste the token into the **Session Token** field in the dashboard header and click **Connect**.
4. The token is kept only in browser memory and validated on loopback REST mutations and the Server-Sent Events (`/api/events`) stream.

### Step 4: Graceful Shutdown
Press `Ctrl+C` in the terminal running `dev.sh`. The trap handler terminates both servers and cleans up temporary files without leaking background processes.

---

## 3. Production Build & Execution

To build and run the same-origin production distribution:

```bash
# 1. Build frontend bundle and backend JAR
./scripts/verify.sh

# 2. Run standalone production JAR (binds to loopback 127.0.0.1:8080)
java -jar backend/target/schedwise-0.1.0.jar --server.port=8080
```

The production frontend bundle is located in `frontend/dist/`.

---

## 4. Verification Suite

Run the full automated test and build verification suite:
```bash
./scripts/verify.sh
```

This verifies:
1. **Java Backend:** 32 JUnit tests passing (parsers, CPU delta formulas, DSA schedulers, simulation engine, comparability checker, audit store, auth).
2. **Python Workloads:** 7 unit tests in `tools/tests/test_demo.py` (hashing service, open-loop load client, finite worker budget, monotonic timers).
3. **Frontend Dashboard:** 4 Node stream/state tests, strict TypeScript typecheck (`tsc --noEmit`), and production Vite bundle build.

---

## 5. Running the Real Contention Demo

The real contention demo executes finite SHA-256 hashing batches competing with a local HTTP hashing service pinned to the same logical CPU core.

### Option A: Interactive Browser Workflow
1. Navigate to the **Experiment** tab.
2. Select an allowed target core (e.g. Core 11) and click **Start Controlled Trial**.
3. Watch the 7-stage phase stepper:
   - `CALIBRATION`: 8 requests measure service CPU service demand and freeze workload parameters.
   - `WARMUP` (5s): Service warms up.
   - `BASELINE` (30s): Service runs alone; baseline latency (p50, p95, p99) is measured.
   - `TRANSITION` (4s): Two background hashing workers are launched and verified.
   - `CONTENTION` (30s): Severe contention degrades service p95 latency by $\sim 15\text{--}18\times$.
4. Switch to **Recommendations** to review corroborated evidence, CFS weight tradeoffs (1024 vs 335), and expected CPU share shifts (33.3% $\rightarrow$ 60.4%).
5. Click **Apply (Nice +5)** and confirm in the modal.
6. Return to **Experiment** or **Results** to view the `AFTER_ACTION` (30s) phase where service latency recovers while background workers continue hashing.
7. Download the session export bundle from the **Results** tab.

### Option B: Bounded Matched-Trial Suite (CLI)
To run automated matched pairs with alternating order:
```bash
python3 scripts/run_trials.py --confirm-nice-increase 5 --pairs 3
```
*Note:* The `--confirm-nice-increase 5` flag is mandatory to explicitly authorize unprivileged background worker priority reduction.

---

## 6. Dashboard Structure

| Tab | Purpose & Features |
| :--- | :--- |
| **Environment** | Hardware specs, kernel release, clock ticks (`CLK_TCK`), allowed CPU mask, PSI pressure, and documented platform limitations. |
| **Live Monitor** | Real-time machine vs selected-core CPU history sparkline (rolling 30s, no backfill), core utilization grid, active process inspector with PIDs, roles, and nice values. |
| **Experiment** | Controlled trial controller, 7-stage stepper, real-time latency (p50, p95, p99), deadline misses, and worker progress. |
| **What-If** | Discrete-event simulation comparing FCFS, Round Robin, Priority, SJF, and classic CFS on real captured request traces. |
| **Recommendations** | Sustained contention detector, candidate CFS nice scenarios, weight tradeoff cards, and authenticated confirmation modal. |
| **Results** | 3-way side-by-side comparison (`Baseline` vs `Contention` vs `After Action`), scientific comparability checker, and session export ZIP downloader. |

---

## 7. Key REST APIs

All endpoints require loopback connection and `Authorization: Bearer <token>`.

| Endpoint | Method | Description |
| :--- | :---: | :--- |
| `/api/capabilities` | `GET` | Discovered Linux kernel, CPU mask, clock ticks, and PSI availability. |
| `/api/snapshots/latest` | `GET` | Latest 1-second system and per-core CPU telemetry snapshot. |
| `/api/events` | `GET` | Real-time Server-Sent Events (SSE) telemetry stream with sequence cursors. |
| `/api/experiments` | `POST` | Launches a controlled trial pinned to a requested logical core (`{"core": 11}`). |
| `/api/experiments/{id}` | `GET` | Fetches active experiment phase, parameters, and worker progress. |
| `/api/experiments/{id}/comparison` | `GET` | Scientific comparability report and 3-way recovery/tradeoff calculations. |
| `/api/recommendations` | `POST` | Generates explainable recommendations from active or captured contention evidence. |
| `/api/simulations` | `POST` | Simulates DSA scheduling algorithms on a captured session. |
| `/api/actions/nice` | `POST` | Applies confirmed unprivileged nice adjustment to owned background workers. |
| `/api/sessions/{id}/export` | `GET` | Downloads session export archive ZIP (`events.jsonl`, `summary.json`, etc.). |

---

## 8. Documentation Index

- [Architecture & System Design](docs/architecture.md) — Detailed subsystems, pure-Java DSA engine, and security boundaries.
- [Metrics, Units & Formulas](docs/metrics.md) — Metric classifications, precision, and comparability mathematics.
- [Demonstration Runbook](docs/demo.md) — Step-by-step reproduction guide and faculty walkthrough.
- [Limitations & Platform Boundaries](docs/limitations.md) — WSL2 constraints, unprivileged restore boundaries, and single-core scope.
- [Build Status](docs/build-status.md) — Milestone verification log (M1–M6).
- [Handoff Notes](docs/handoff.md) — Operational instructions and engineering decisions.
