# SchedWise Metrics, Schema & Formulas (v1)

All product values originate in the visible Linux environment. The API's `Value<T>` envelope contains `value`, `unit`, `kind`, `sourceId`, `timestamp`, `sessionId`, `availability`, `reason`, and `windowSeconds`.
- `kind`: `MEASURED`, `DERIVED`, `SIMULATED`, or `RECORDED`.
- `availability`: `AVAILABLE`, `UNAVAILABLE`, or `STALE`.
- Null is `UNAVAILABLE`, never zero. Zero is a distinct, valid measured quantity.

---

## 1. Process Identity & Telemetry

### 1.1 Process Identity 3-Tuple
A process is identified across its lifecycle by:
$$\text{Identity} = (\text{bootId}, \text{pid}, \text{startTicks})$$
- `bootId`: Read from `/proc/sys/kernel/random/boot_id` at backend startup.
- `pid`: Process identifier from `/proc/<pid>/stat`.
- `startTicks`: Process startup clock tick counter from `/proc/<pid>/stat` (field 22).

*Zero-Reuse Rule:* PID alone is never trusted. If a process exits and its PID is recycled by the kernel, `startTicks` changes and all delta accumulators reset immediately.

### 1.2 Process CPU Percentage
Process CPU utilization is calculated over monotonic intervals:
$$\text{CPU \%} = 100 \times \frac{\Delta (\text{utime} + \text{stime})}{\text{CLK\_TCK} \times \Delta t_{\text{monotonic}}}$$
- `CLK_TCK`: Dynamically resolved via `/usr/bin/getconf CLK_TCK` (typically 100 Hz on Linux x86_64).
- **Scale:** **100% means one logical CPU**. Multi-threaded processes may exceed 100%.
- Process CPU describes the thread group leader; waited-for dead child CPU is excluded.

---

## 2. Machine & Logical Core Telemetry

### 2.1 CPU Counters (`/proc/stat`)
Eight counter deltas are retained between 1-second sample intervals:
$$\text{total} = \Delta \text{user} + \Delta \text{nice} + \Delta \text{system} + \Delta \text{idle} + \Delta \text{iowait} + \Delta \text{irq} + \Delta \text{softirq} + \Delta \text{steal}$$

### 2.2 Core Utilization & Steal
$$\text{Core Busy \%} = 100 \times \frac{\text{total} - \Delta \text{idle} - \Delta \text{iowait} - \Delta \text{steal}}{\text{total}}$$
$$\text{Steal \%} = 100 \times \frac{\Delta \text{steal}}{\text{total}}$$
- I/O wait is excluded from CPU busy percentage.
- `cpus.cpu`: Whole visible machine aggregate capacity.
- `cpus.cpuN`: Individual logical CPU core capacity.
- Steal is tracked separately to detect hypervisor CPU starvation under virtualization or WSL2.

---

## 3. Kernel Pressure Stall Information (PSI)

Read from `/proc/pressure/cpu`:
- Tracks `some` pressure averages (`avg10`, `avg60`, `avg300`) and cumulative stall time ($\mu s$).
- Represents the percentage of time that at least one non-idle thread was stalled waiting for CPU.
- System CPU `full` is ignored (it is semantically meaningless for CPU scheduling because the CPU is never fully stalled for all runnable tasks simultaneously).
- Absence of `/proc/pressure/cpu` indicates an unconfigured kernel (`CONFIG_PSI=n`), not zero contention.

---

## 4. Experiment & Workload Request Metrics

### 4.1 Phase Structure
Controlled trials execute through 5 scheduled phases:
1. `WARMUP` (5s): Service pre-initialization.
2. `BASELINE` (30s): Protected service running alone; establishes unconstrained performance.
3. `TRANSITION` (4s): Competing background workers launched and verified on the pinned core.
4. `CONTENTION` (30s): Both background workers run at baseline nice (nice 0); contention observed.
5. `AFTER_ACTION` (30s): Background workers run under unprivileged confirmed priority setting (e.g. nice 5).

### 4.2 Request Timings
- `scheduledNs`: Monotonic timestamp of planned request release (open-loop schedule).
- `dispatchNs`: Monotonic timestamp when socket write was initiated.
- `completionNs`: Monotonic timestamp when full HTTP response was parsed.
- `dispatchDelayNs`: Queueing delay prior to dispatch: $\text{dispatchNs} - \text{scheduledNs}$.
- `latencyNs`: Socket exchange duration: $\text{completionNs} - \text{dispatchNs}$.
- `cpuServiceNs`: Self-measured CPU execution time inside `service.py` performing SHA-256 rounds.
- `deadlineMiss`: Boolean flag indicating failure or completion exceeding the 500 ms SLA deadline:
  $$\text{outcome} \ne \text{'OK'} \lor (\text{completionNs} - \text{scheduledNs}) > 500 \times 10^6 \text{ ns}$$

### 4.3 Statistical Aggregates
- **Quantiles:** Nearest-rank formula: $\text{index} = \lceil q \cdot N \rceil - 1$.
- **p95 Requirement:** Requires $\ge 20$ successful responses ($N \ge 20$). Returns `null` if fewer.
- **p99 Requirement:** Requires $\ge 100$ successful responses ($N \ge 100$). Returns `null` with reason `FEWER_THAN_100_SUCCESSES` if fewer.

---

## 5. Contention Detection & Explainable Recommendations

### 5.1 Contention Trigger Thresholds (`ContentionDetector.java`)
Contention is flagged only when all corroborating criteria are met:
1. Target logical core busy $\ge 80\%$.
2. Latency p95 degraded $\ge 1.5\times$ compared to baseline OR deadline misses $> 0$.
3. Corroborated by PSI CPU `some > 0` or active runnable queue competition.

*Distinction:* High CPU utilization alone is NOT proof of harmful contention. High utilization without latency degradation or pressure is classified as harmless saturation.

### 5.2 Candidate CFS Nice Scenarios & Weights
Linux CFS allocates CPU proportion to task weight $W_i$:
- Nice 0: Weight 1024
- Nice 5: Weight 335
- Nice 10: Weight 110

When background workers are reniced from 0 to 5:
$$\text{Nominal Service Share} = \frac{1024}{1024 + 335 + 335} = \frac{1024}{1694} \approx 60.4\% \quad (\text{up from } 33.3\%)$$
$$\text{Nominal Worker Share (each)} = \frac{335}{1694} \approx 19.8\% \quad (\text{down from } 33.3\%)$$

---

## 6. Scientific Comparability & Improvement Formulas

The `ComparabilityChecker` enforces scientific rigour before computing gains:

### 6.1 Validity Gates
A trial comparison is marked `VALID` and `comparable: true` strictly when:
1. Workload buffer (1024 bytes), hash algorithm, and iterations (20,000) are identical.
2. Offered request rate divergence between phases is $\le 5\%$:
   $$\left|\frac{\text{Rate}_{\text{phase}} - \text{Rate}_{\text{base}}}{\text{Rate}_{\text{base}}}\right| \le 0.05$$
3. Both background workers survived the entire measurement window.
4. Core affinity mask is identical across all phases.
5. Baseline, Contention, and After-Action phases each have $\ge 20$ successful samples.

### 6.2 Improvement Formulas
1. **$\Delta$ Latency p95 Improvement:**
   $$\Delta \text{Latency p95 \%} = \frac{p95_{\text{contention}} - p95_{\text{after}}}{p95_{\text{contention}}} \times 100\%$$
2. **Latency Recovery (towards baseline):**
   $$\text{Recovery \%} = \frac{p95_{\text{contention}} - p95_{\text{after}}}{p95_{\text{contention}} - p95_{\text{baseline}}} \times 100\%$$
3. **Background Worker Throughput Tradeoff:**
   $$\text{Tradeoff \%} = \frac{\text{hashes/s}_{\text{after}} - \text{hashes/s}_{\text{contention}}}{\text{hashes/s}_{\text{contention}}} \times 100\%$$
4. **Deadline Miss Reduction:**
   $$\Delta \text{Misses} = \text{missCount}_{\text{contention}} - \text{missCount}_{\text{after}}$$

---

## 7. Export Archive Schema

The exported ZIP archive (`schedwise-<id>.zip`) contains six raw evidence files:

| File | Format | Contents |
| :--- | :--- | :--- |
| `manifest.json` | JSON | Hardware capabilities, kernel release, JVM version, provenance metadata. |
| `events.jsonl` | JSONL | Full event log of coordinator, service, worker, and load client events. |
| `samples.jsonl` | JSONL | Periodic 100ms `/proc` snapshots: machine CPU, core CPU, PSI, and child stats. |
| `summary.json` | JSON | Phase metrics (Baseline, Contention, AfterAction), worker hashes, and frozen parameters. |
| `supervisor.json` | JSON | Java supervisor log: child process exit codes and verification states. |
| `collector-affinity.json` | JSON | Thread affinity verification proving collector was isolated from experiment core. |
| `action-audits.json` | JSON | SQLite persistent audit records of verified nice mutations and readbacks. |

## Shield and capture audit corrections

Shield `cpuPercent`, `estimatedCpuFreed`, and `estimatedRamFreedBytes` are nullable. No point-in-time scan can establish CPU savings, and suspension does not free a resident set. Missing values must render unavailable. Candidate RSS uses the observed `/proc/<pid>/status` `VmRSS` value (KiB converted to bytes); missing RSS is null. Candidate identity is `(bootId,pid,startTicks)`.

Simulation service demand requires recorded positive `cpuServiceNs`; there is no default demand. Contention worker inputs require observed positive CPU and elapsed time and an explicit background role. Scaling aggregate observed worker CPU to a shorter phase window and assuming phase-start arrival are disclosed model assumptions. Missing evidence returns INSUFFICIENT. Service arrivals outside the phase window are excluded with a count in quality notes.
