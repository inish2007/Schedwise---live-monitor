# SchedWise Architecture & System Design

SchedWise is a Linux CPU allocation advisor and workload visualizer designed for developers and small-server operators sharing limited CPU capacity between a protected latency-sensitive service and CPU-bound background jobs.

---

## 1. System Overview & The Closed-Loop Flow

SchedWise implements a complete real-system scientific cycle:

```
+---------------------------------------------------------------------------------------------------+
|                                  THE CLOSED-LOOP SCIENTIFIC FLOW                                  |
|                                                                                                   |
|  [ Real /proc ] --> [ Contention ] --> [ Measured-Input ] --> [ Explainable   ]                   |
|  [ Collection ]     [  Evidence  ]     [ DSA Simulation ]     [ Recommendation]                   |
|         ^                                                             |                           |
|         |                                                             v                           |
|  [  Measured  ] <-------------------------------------------- [ Confirmed OS  ]                   |
|  [ Validation ]                                               [ Priority Apply]                   |
+---------------------------------------------------------------------------------------------------+
```

1. **Real Collection:** Unprivileged continuous sampling of Linux `/proc/stat`, `/proc/<pid>/stat`, and `/proc/pressure/cpu` into an in-memory ring buffer.
2. **Contention Evidence:** Corroborated detection combining core CPU saturation ($\ge 80\%$), latency degradation ($\ge 1.5\times$ baseline or deadline misses), and kernel PSI signals.
3. **Measured-Input DSA Simulation:** Offline discrete-event scheduling models (FCFS, Round Robin, Priority, SJF, classic CFS) evaluated against real captured request arrival and CPU service traces.
4. **Explainable Recommendations:** Transparent evaluation of candidate CFS nice allocations (+5, +10) detailing explicit weight ratios and expected CPU share shifts.
5. **Confirmed Priority Apply:** Authenticated, unprivileged user mutation (`renice`) strictly targeting owned, single-threaded background worker processes with immediate `/proc` readback verification.
6. **Measured Validation:** Side-by-side three-way comparison (`Baseline` vs `Contention` vs `After Action`) evaluated by a scientific comparability engine and packaged into reproducible session exports.

---

## 2. Component Architecture

```
+-----------------------------------------------------------------------------------+
|                                    BROWSER UI                                     |
|  React 19 + TypeScript + Vite | Accessible 6-Tab Dashboard (Dark Theme)           |
|  [ Environment ] [ Live Monitor ] [ Experiment ] [ What-If ] [ Recom ] [ Results] |
+-----------------------------------------------------------------------------------+
          | REST (Auth Token)                       ^ Server-Sent Events (SSE)
          v                                         | (/api/events)
+-----------------------------------------------------------------------------------+
|                            JAVA 21 / SPRING BOOT BACKEND                          |
|                                                                                   |
|  +--------------------+  +----------------------+  +---------------------------+  |
|  |  Monitor & Linux   |  |   Experiment Manager |  |   DSA Simulation Engine   |  |
|  |  - Collector (1Hz) |  |   - Python Process   |  |   - Fcfs (ArrayDeque)     |  |
|  |  - ProcParser      |  |     Supervisor       |  |   - RoundRobin (ArrayDeque|  |
|  |  - Capabilities    |  |   - Lifecycle / Kill |  |   - Priority (Heap PQ)    |  |
|  |  - ThreadAffinity  |  |   - Comparability    |  |   - Sjf (Heap PQ)         |  |
|  +--------------------+  +----------------------+  |   - Simplified CFS        |  |
|                                                    |     (TreeSet vruntime)    |  |
|  +--------------------+  +----------------------+  +---------------------------+  |
|  |   Recommendation   |  |     Linux Action     |                                 |
|  |   - ContentionDet  |  |     - LinuxAction    |  +---------------------------+  |
|  |   - RecomEngine    |  |       Adapter        |  |        Persistence        |  |
|  |   - RoleRegistry   |  |     - Audit Store    |  |   - SQLite (V1/V2 Flyway) |  |
|  +--------------------+  +----------------------+  +---------------------------+  |
+-----------------------------------------------------------------------------------+
          | subprocess (taskset, Popen)             | unprivileged /usr/bin/renice
          v                                         v
+-----------------------------------------------------------------------------------+
|                        REAL LINUX WORKLOAD PROCESSES (Core N)                     |
|  - Protected Service: tools/demo/service.py (HTTP SHA-256 worker, nice 0)         |
|  - Competing Worker 1: tools/demo/worker.py (Finite hashing batch, nice 0 -> 5)   |
|  - Competing Worker 2: tools/demo/worker.py (Finite hashing batch, nice 0 -> 5)   |
|  - Open-Loop Client:  tools/demo/load_client.py (Fixed-rate scheduled requests)  |
+-----------------------------------------------------------------------------------+
```

---

## 3. Data Structures and Algorithm (DSA) Engine

SchedWise models scheduling algorithms in pure Java (`dev.schedwise.scheduler.*`). The engine is deterministic, discrete-event driven, and operates on immutable DTOs constructed from real session captures.

### Algorithms Implemented

| Algorithm | Data Structure | Tie-Breaking Rule | Slice / Preemption | Complexity per Event |
| :--- | :--- | :--- | :--- | :--- |
| **FCFS** | `ArrayDeque<Job>` | Arrival order; unique Job ID | Non-preemptive | $O(1)$ enqueue / dequeue |
| **Round Robin (RR)** | `ArrayDeque<Job>` | Arrival order; unique Job ID | Preemptive (configurable quantum $q$) | $O(1)$ per slice |
| **Priority** | `PriorityQueue<Job>` | Lowest nice / priority first; arrival time; ID | Non-preemptive | $O(\log n)$ enqueue / dequeue |
| **SJF** | `PriorityQueue<Job>` | Shortest remaining service demand; arrival; ID | Non-preemptive | $O(\log n)$ enqueue / dequeue |
| **Simplified CFS** | `TreeSet<CfsEntry>` | Smallest $vruntime$; arrival time; unique ID | Fair slice: $\max(\text{granularity}, \frac{w_i}{\sum w} \cdot L)$ | $O(\log n)$ insert / pollFirst |

### Mathematical Model of CFS Virtual Runtime

Linux CFS schedules tasks by advancing virtual runtime inversely proportional to task weight:

$$vruntime_{i} \leftarrow vruntime_{i} + \Delta exec \times \frac{W_{\text{NICE\_0}}}{W_i}$$

Where:
- $W_{\text{NICE\_0}} = 1024$ (nice 0 base weight).
- $W_i$ is mapped via the kernel `sched_prio_to_weight` table:
  - Nice 0: weight 1024
  - Nice 5: weight 335
  - Nice 10: weight 110
- When background workers are reniced from 0 to 5, their weight drops from 1024 to 335.
- The protected service's nominal CPU share increases:

$$\text{Share}_{\text{service}} = \frac{1024}{1024 + 335 + 335} = \frac{1024}{1694} \approx 60.4\% \quad (\text{vs } 33.3\% \text{ at nice 0})$$

### Event Engine Mechanics (`SimulationEngine.java`)
- **Idle Jumps:** When the ready queue is empty, the engine advances simulation time directly to the next arrival event:
  $$t \leftarrow \text{nextArrivalNs}$$
  Nano-second or micro-second busy loops are strictly avoided.
- **Horizon Censoring:** Jobs in-flight or waiting in queue when the simulation horizon $H$ is reached are marked `censored`. Turnaround times are never fabricated for unfinished jobs.
- **Zero Kernel Mutation:** Scheduler classes are pure algorithms and have no capability to run OS commands or alter Linux priority.

---

## 4. Linux Collection & Kernel Telemetry

### Monitored Sources

1. **`/proc/stat`:**
   - Evaluates aggregate and per-core CPU tick deltas between samples:
     $$\text{CPU \%} = \frac{\Delta \text{user} + \Delta \text{system} + \Delta \text{nice}}{\Delta \text{total}} \times 100\%$$
   - 100% represents one fully utilized logical CPU.
   - Steal and guest counters are tracked explicitly.
2. **`/proc/<pid>/stat`:**
   - Robustly parses the command field `(comm)` handling spaces and parentheses:
     Finds the first `(` and last `)` before tokenizing numeric fields.
   - Computes process CPU percentage using tick deltas over monotonic elapsed time.
   - Reads field 18 (`priority`), field 19 (`nice`), field 22 (`startTicks`).
3. **`/proc/pressure/cpu`:**
   - Parses kernel Pressure Stall Information (PSI).
   - Monitors `some` averages (`avg10`, `avg60`, `avg300`) and cumulative stall time.
   - System-level CPU `full` is ignored as it is semantically meaningless for CPU scheduling.
4. **`/proc/self/status` & CPU Affinity:**
   - Reads `Cpus_allowed_list` to discover allowed logical cores.
   - Ensures the experiment core is strictly within the allowed affinity mask.

### Process Identity Tuple
Processes are identified by a 3-tuple:
$$\text{Identity} = (\text{bootId}, \text{pid}, \text{startTicks})$$
PID alone is never trusted. If a process exits and its PID is recycled by the kernel, `startTicks` mismatches and all delta accumulators reset immediately.

---

## 5. Security, Boundaries & Permissions

1. **Zero Root Privilege:**
   - The backend runs as a standard unprivileged user. It does not run as root, does not require `sudo`, and does not hold `CAP_SYS_NICE`.
2. **Narrow Action Scope:**
   - Only processes registered in the active experiment's child registry with role `BACKGROUND` can be mutated.
   - System processes, arbitrary user processes, and the protected service are strictly rejected.
   - Monitored external processes are marked `OBSERVE_ONLY` and are strictly read-only.
3. **Directional Mutation Boundary:**
   - In Linux, unprivileged users can only *increase* nice (reducing priority). Lowering nice requires root.
   - Negative nice and priority promotions are rejected fail-closed.
4. **No Shell Injection:**
   - OS executions (`taskset`, `renice`, `python3`) use fixed argument arrays with validated numeric parameters. `sh -c` is forbidden.
5. **Session Authentication & Loopback Binding:**
   - The backend binds strictly to `127.0.0.1:8080`.
   - Every launch generates a cryptographically random 256-bit session token in `data/session-token` (file permission `0600`).
   - Mutations validate `Origin: http://127.0.0.1:5173` to prevent cross-site request forgery.

---

## 6. Scientific Comparability & Validation

The `ComparabilityChecker` enforces rigid scientific rules before declaring any optimization outcome:
1. **Workload Consistency:** Workload buffer (1024 bytes), hash algorithm (`sha256`), and iterations (20,000) must be identical across comparison phases.
2. **Offered Load Stability:** The load client must maintain open-loop scheduled request rate within $\pm 5\%$ divergence:
   $$\left|\frac{\text{Rate}_{\text{after}} - \text{Rate}_{\text{contention}}}{\text{Rate}_{\text{contention}}}\right| \le 0.05$$
3. **Core Pinning Isolation:** Service and workers must be pinned to the identical single logical core.
4. **Statistical Adequacy:** p50/p95 percentiles require $\ge 20$ successful samples; p99 requires $\ge 100$ successful samples.
5. **Transparent Reporting:** If conditions fail, the comparison status is marked `INVALID` or `REJECTED`, with specific human-readable reasons, rather than presenting ungrounded improvement percentages.
