# SchedWise v1 Demonstration Runbook & Faculty Walkthrough

This document provides a step-by-step reproduction runbook for faculty review and peer demonstration, following the locked real-system flow:
$$\text{Real collection} \longrightarrow \text{Contention evidence} \longrightarrow \text{Measured-input simulation} \longrightarrow \text{Explainable recommendation} \longrightarrow \text{Confirmed priority change} \longrightarrow \text{Measured validation}$$

All figures and examples below are grounded in genuine Linux `/proc` measurements from verified trials.

---

## 1. Quick Demonstration Setup

### 1.1 Start Backend and Frontend
In a Linux terminal (native Linux or WSL2):
```bash
# 1. Verify environment health (read-only)
./scripts/doctor.sh

# 2. Launch development environment
./scripts/dev.sh
```

### 1.2 Authenticate in Dashboard
1. Open **http://127.0.0.1:5173** in your browser.
2. Retrieve the per-launch security token:
   ```bash
   cat data/session-token
   ```
3. Paste the token into the header input and click **Connect**.
4. Confirm the connection status indicator changes from `Disconnected` to `Connected (Live)`.

---

## 2. The 8-Step Faculty Demonstration Script

### Step 1: Inspect Environment & Platform Scope (Tab: Environment)
- **Show the Audience:**
  - Hardware & Kernel: Linux kernel release (e.g. `7.0.0-28-generic x86_64`).
  - Clock Ticks: `SC_CLK_TCK = 100 Hz` (dynamically discovered, not hardcoded).
  - Allowed CPU mask: e.g. `0-11` (12 logical cores).
  - Kernel PSI (`/proc/pressure/cpu`): `some` pressure averages displayed; explain that system `full` is omitted because it is semantically meaningless for CPU scheduling.
  - Zero-Root Compliance: Verified non-root UID (1000). The backend runs without privileges or `CAP_SYS_NICE`.

### Step 2: Observe Idle State & Start Experiment (Tab: Live Monitor & Experiment)
- **Show the Audience:**
  - Live Monitor: Real-time 30-sample rolling CPU history sparkline (notice there is zero synthetic backfill; absent past seconds are blank).
  - Process Table: Displays verified PIDs, process names, identities `(bootId, pid, startTicks)`, and current nice values.
- **Action:** Switch to the **Experiment** tab.
  - Select target core: e.g. **Core 11** (the highest allowed core, keeping collector and load client isolated on observer cores).
  - Click **Start Controlled Trial**.

### Step 3: Observe Calibration, Warmup & Baseline
- **Show the Audience:**
  - `CALIBRATION`: 8 requests measure service CPU execution time (median $\sim 35\text{--}38$ ms).
  - Workload parameters freeze: Offered rate $\sim 16$ req/s (interval 62.5 ms), worker hash budget $\sim 100\text{M}$ iterations.
  - `BASELINE` (30s): Service runs alone on Core 11.
  - **Observed Baseline Metrics:**
    - Latency p50: $\approx 37.5$ ms
    - Latency p95: $\approx 39.1$ ms
    - Latency p99: $\approx 44.7$ ms
    - Deadline Misses: **0**
    - Errors: **0**

### Step 4: Observe Contention Shock
- **Show the Audience:**
  - `TRANSITION` (4s): Two background hashing workers (`tools/demo/worker.py`) launch on Core 11.
  - `CONTENTION` (30s): Both workers actively hash at default priority (`nice 0`), competing for 100% of Core 11 capacity with the HTTP service.
  - **Observed Contention Metrics:**
    - Core 11 CPU Utilization jumps to **100.0%**.
    - Latency p50: climbs to $\approx 47.7$ ms.
    - Latency p95: degrades severely to $\approx 644.5$ ms ($16.5\times$ degradation!).
    - Latency p99: degrades to $\approx 773.9$ ms.
    - Deadline Misses (>500ms): climbs to **89 misses**.
    - Background Worker Throughput: $\approx 513,865$ hashes/s.

### Step 5: Inspect What-If Discrete-Event Simulations (Tab: What-If)
- **Show the Audience:**
  - Select the active or previous capture in the capture selector.
  - Compare the 5 pure-Java algorithms evaluated on real request arrivals:
    - **FCFS (`ArrayDeque`):** Background batch worker arriving at $t=0$ blocks the single CPU non-preemptively; short service requests stall behind it.
    - **Round Robin (`ArrayDeque`):** Preemptive time slicing ($q=20$ ms) allows service requests to interleave with batch workers.
    - **SJF (`PriorityQueue`):** Min-heap prioritizes short 35 ms service requests over 10s background batches, completing far more jobs.
    - **Simplified CFS (`TreeSet`):** Orders execution by virtual runtime ($vruntime$). Explains that Nice 0 shares CPU equally ($33.3\%$ each).
  - *Distinction:* Highlight the label **"SIMULATED"** and explain that model queue wait time is strictly distinct from end-to-end HTTP response latency.

### Step 6: Review Explainable Recommendations (Tab: Recommendations)
- **Show the Audience:**
  - Contention Detector Evidence Card: Core busy $100\%$, latency degradation $16.5\times$, 89 deadline misses. Corroborated by PSI CPU `some`.
  - Candidate Scenarios Evaluated:
    - Candidate 1: Renice workers to **+5** (Weight: 335). Nominal service share increases from $33.3\%$ to $60.4\%$.
    - Candidate 2: Renice workers to **+10** (Weight: 110). Nominal service share increases to $82.3\%$.
  - Reference Algorithm Rejection: Explain why FCFS/SJF are marked "Reference Model Only" and cannot be applied as Linux OS settings.
- **Action:** Click **Apply (Nice +5)** on Candidate 1.
  - Review the Confirmation Modal:
    - Displays exact target PIDs (e.g. `80415`, `80416`).
    - Displays matching start ticks and boot ID.
    - Displays current nice (`0`) and requested nice (`5`).
    - Prominently warns: *"In Linux, unprivileged processes cannot reduce nice back to 0. Restoring normal priority requires resetting the workload."*
  - Click **Confirm and Apply**.
  - Show the readback banner: `/proc/<pid>/stat` field 19 directly read back and verified as `nice = 5`.

### Step 7: Observe Measured Validation in After-Action (Tab: Results)
- **Show the Audience:**
  - `AFTER_ACTION` (30s): The service and background workers continue running on Core 11, now with workers at `nice = 5`.
  - **Observed After-Action Metrics:**
    - Latency p50: drops back to $\approx 36.8$ ms.
    - Latency p95: recovers to $\approx 55.6$ ms (**91.37% improvement** over contention).
    - Latency p99: recovers to $\approx 87.5$ ms.
    - Deadline Misses: drops from 89 to **0** (**100% reduction**).
    - Background Workers: Continue making steady progress at $\approx 562,446$ hashes/s (they were deprioritized, not starved or killed).
  - Comparability Engine Card: Status is **`VALID`**. Rate shift was $< 5\%$, parameters were matched, workers survived.
  - Latency Recovery Score: **97.27%** of the contention gap recovered.

### Step 8: Download Archive & Verify Clean Shutdown (Tab: Results)
- **Action:** Click **Download Session Archive (ZIP)**.
  - Show the downloaded ZIP contents: `events.jsonl`, `samples.jsonl`, `summary.json`, `supervisor.json`, `collector-affinity.json`, `action-audits.json`.
- **Show the Audience:**
  - When the experiment reaches `COMPLETED`, all 4 child processes (service, 2 workers, load client) were terminated and verified dead with exit code 0.
  - No orphaned Python or hashing processes remain in the system.

---

## 3. Truthful Contingencies & Diagnostic Scenarios

### Contingency A: What If No Latency Improvement Occurs?
- **Root Cause Analysis:**
  1. Check CPU Pinning: Run `taskset -cp <pid>` on the workers and service. If they are not sharing the same logical CPU, contention did not occur.
  2. Check Autogroup: In some desktop Linux distributions, systemd puts separate terminal sessions in separate autogroups. SchedWise verifies that all demo children share `/proc/self/autogroup`.
  3. Workload Premature Exit: If background workers exhausted their hash budget before the measurement window completed, contention ceased naturally. SchedWise detects this and marks the trial `FAILED`.
- **Reporting Policy:** Never tune or fabricate numbers to force a positive outcome. If a trial exhibits 0% recovery, report it honestly and inspect the grouping and core affinity.

### Contingency B: What If PSI or Schedstat is Missing?
- If `/proc/pressure/cpu` is absent, the Environment and Recommendation tabs explicitly state:
  *"CPU PSI is unavailable on this kernel (CONFIG_PSI not enabled). Contention is corroborated by CPU core saturation and observed latency degradation."*
- If `/proc/sys/kernel/sched_schedstats` is 0, SchedWise displays:
  *"Schedstat accounting is inactive (0). Zero values indicate disabled kernel accounting, not zero wait time."*

### Contingency C: Recorded Demonstrations
- If presenting historical evidence when a live Linux machine is unavailable, the dashboard displays:
  **"RECORDED SESSION: Captured at [timestamp] on Linux [kernel]"**.
- A recording is never silently presented as live telemetry.
