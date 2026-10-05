# SchedWise: Complete Glossary, Concepts & Feature Reference Manual

> **Document Version:** 1.0.0 (Comprehensive Reference)  
> **Target Audience:** Developers, System Operators, Evaluators, Faculty & Students  
> **Application Scope:** Real Linux CPU Allocation Advisor & Gaming / Latency-Sensitive Workload Optimizer

---

## Table of Contents

1. [Executive Overview & Purpose](#1-executive-overview--purpose)
2. [Core Architecture & Workflow Concepts](#2-core-architecture--workflow-concepts)
3. [Operating System & Linux Kernel Concepts](#3-operating-system--linux-kernel-concepts)
4. [Data Structures & Algorithms (DSA) Concepts](#4-data-structures--algorithms-dsa-concepts)
5. [Contention, Benchmarking & Experimentation Metrics](#5-contention-benchmarking--experimentation-metrics)
6. [Recommendation & OS Control Concepts](#6-recommendation--os-control-concepts)
7. [Game Shield & Process Optimization Concepts](#7-game-shield--process-optimization-concepts)
8. [Security, Session & Networking Concepts](#8-security-session--networking-concepts)
9. [Complete Feature Reference by UI Panel](#9-complete-feature-reference-by-ui-panel)
10. [Alphabetical Dictionary of All Terms & Acronyms](#10-alphabetical-dictionary-of-all-terms--acronyms)

---

## 1. Executive Overview & Purpose

### 1.1 What is SchedWise?
**SchedWise** is an intelligent, transparent Linux CPU resource manager and allocation advisor. On single-socket machines, small development servers, or PC gaming rigs, multiple processes compete for CPU time. When background tasks (like code compilation, batch image hashing, package updates, or browser helper threads) consume computing resources, latency-sensitive applications (such as web APIs, database services, or games) suffer severe response latency spikes, frame stutter, and deadline misses.

### 1.2 Core Problem Solved
Traditional task managers (like `top`, `htop`, or Windows Task Manager) merely observe CPU percentages without explaining **contention**, predicting the impact of scheduling priority adjustments, or verifying whether a priority change actually fixed latency. SchedWise provides a closed-loop engineering workflow:
$$\text{Real Linux Collection} \longrightarrow \text{Contention Evidence} \longrightarrow \text{Measured What-If Simulation} \longrightarrow \text{Explainable Recommendation} \longrightarrow \text{Confirmed Priority Action} \longrightarrow \text{Empirical Validation}$$

---

## 2. Core Architecture & Workflow Concepts

### 2.1 The Closed-Loop Lifecycle
* **Baseline Phase:** The latency-sensitive service runs alone under fixed offered client load. Measures nominal round-trip times and CPU demand without background competition.
* **Contention Phase:** CPU-intensive background tasks run concurrently on the same pinned core. Measures latency degradation, queue queuing delays, and throughput loss.
* **What-If Simulation:** Uses traces from the contention phase as input to deterministic queueing models (FCFS, RR, Priority, SJF, CFS) to predict behavior under different scheduling policies.
* **Recommendation Engine:** Rule-based heuristics evaluate evidence (latency inflation, CPU pressure, thread counts) to propose exact `nice` adjustments.
* **Confirmed Action:** The user reviews proposed priority shifts in an explicit confirmation modal before any process is modified.
* **After-Action (Validation) Phase:** Measures the system after priorities are applied to verify whether latency recovered without breaking comparability.

### 2.2 Data Provenance & Taxonomy
SchedWise strictly tags every single number according to its origin:
1. **MEASURED:** Directly sampled from the real operating system (`/proc`, `clock_gettime`, or HTTP socket response times).
2. **DERIVED:** Mathematically computed from two or more measured points (e.g., CPU percentage delta over an elapsed interval).
3. **SIMULATED:** Output from an abstract discrete-event algorithm model. Real trace inputs do not make simulated outputs real measurements.
4. **RECORDED:** Historical data from a previously completed session stored in SQLite.

### 2.3 Zero-Mock Policy
A strict design invariant across the entire application:
* No `Math.random()`, fake process lists (e.g., "P1", "P2"), synthetic chart arrays, or hardcoded benchmarks.
* If a metric cannot be read (e.g., unsupported kernel feature or missing permissions), it is labeled **UNAVAILABLE**, never disguised as zero or simulated fake data.

---

## 3. Operating System & Linux Kernel Concepts

### 3.1 `/proc` Virtual File System
A pseudo-filesystem provided by the Linux kernel that serves as an interface to internal kernel data structures.
* **/proc/stat:** Global system activity counters (user, nice, system, idle, iowait, irq, softirq, steal, guest).
* **/proc/[pid]/stat:** Per-process state including CPU ticks spent in user mode (`utime`) and kernel mode (`stime`), nice level, thread count, and start time ticks.
* **/proc/[pid]/status:** Human-readable process metadata including User ID (`Uid`), Group ID (`Gid`), memory usage, and voluntary/involuntary context switches.
* **/proc/[pid]/cmdline:** The full command-line invocation arguments of the process separated by null bytes (`\0`).
* **/proc/[pid]/cgroup:** Control group hierarchy paths assigned to the process.
* **/proc/pressure/cpu (PSI):** Pressure Stall Information showing the percentage of time tasks are stalled waiting for runnable CPU capacity.

### 3.2 Linux CPU Timing & Accounting
* **Clock Tick (`SC_CLK_TCK` / `sysconf`):** The internal timer frequency of the kernel (typically 100 Hz on Linux x86_64, meaning 1 tick = 10ms). SchedWise detects this dynamically using `getconf CLK_TCK`.
* **User Time (`utime`):** CPU ticks consumed executing process code in user space.
* **System Time (`stime`):** CPU ticks consumed executing kernel system calls on behalf of the process.
* **Steal Time (`steal`):** CPU cycles involuntarily stolen from a virtualized guest OS by the hypervisor while servicing other virtual machines. SchedWise separates steal time from regular idle/busy time.
* **Process Identity `(bootId, pid, startTicks)`:** In Linux, Process IDs (PIDs) wrap around and get reused. To prevent race conditions and mutating the wrong process, SchedWise tracks processes using a 3-tuple: system boot ID, PID, and process start ticks since boot.

### 3.3 Priorities, Nice Values & CPU Weights
* **Nice Value:** A user-space priority knob ranging from `-20` (highest priority) to `+19` (lowest priority). Normal default is `0`.
* **Unprivileged Execution:** Standard non-root users can only increase nice values (making processes *nicer* or lower priority). Decreasing nice (higher priority) requires `CAP_SYS_NICE` or root. SchedWise runs completely unprivileged.
* **CFS CPU Weights:** In Linux Completely Fair Scheduler, nice levels map exponentially to proportional weights:
  $$\text{weight} \approx \frac{1024}{1.25^{\text{nice}}}$$
  A nice 0 task has weight 1024; a nice 5 task has weight 335; a nice 10 task has weight 110. A nice 0 task receives $\approx 3\times$ more CPU time than nice 5, and $\approx 9.3\times$ more than nice 10.

### 3.4 CPU Affinity & Isolation
* **CPU Affinity (`taskset`):** Directing a process or thread to execute solely on specific designated logical CPU cores.
* **Cache Locality & Contention Isolation:** By pinning both the protected service and background workers to a single core (e.g., Core 0) while leaving other cores for observers and the dashboard, SchedWise creates reproducible, measurable CPU starvation without crashing the entire system.

### 3.5 Inter-Process Communication & POSIX Signals
* **`SIGSTOP` (Signal 19):** Uncatchable signal that immediately freezes/pauses process execution. The process remains resident in RAM but consumes 0% CPU.
* **`SIGCONT` (Signal 18):** Resumes execution of a previously stopped process from the exact instruction where it was suspended.
* **`SIGTERM` / `SIGKILL`:** Requests termination or forcibly terminates a process.

---

## 4. Data Structures & Algorithms (DSA) Concepts

SchedWise includes an internal, pure-Java discrete-event scheduling engine designed to evaluate educational and production scheduling algorithms using real measured traces:

```
+-------------------------------------------------------------------------------+
|                        PURE JAVA DISCRETE-EVENT ENGINE                        |
|                                                                               |
|  [Measured Request Arrivals] ----> [ Ready Queue Data Structure ]             |
|                                                  |                            |
|                                         (Algorithm Policy)                    |
|                                                  v                            |
|  [Simulated Timeline / Gantt] <--- [ Discrete Event Time Advance / Jumps ]    |
+-------------------------------------------------------------------------------+
```

### 4.1 Algorithms Implemented
1. **First-Come, First-Served (FCFS):**
   * *Data Structure:* `java.util.ArrayDeque` (FIFO queue).
   * *Mechanism:* Non-preemptive. Jobs run in the exact order of their arrival until completion.
   * *Phenomenon:* Susceptible to the **Convoy Effect**, where short requests wait behind long-running batch jobs.
2. **Textbook Round Robin (RR):**
   * *Data Structure:* `java.util.ArrayDeque` with tail re-enqueueing.
   * *Mechanism:* Preemptive with a fixed time quantum (default: 20ms).
   * *Quantum Boundary Rule:* If a new job arrives at time $t$ while a current slice finishes at time $t$, the new arrival enters the queue *before* the preempted task is requeued.
   * *Note:* SchedWise explicitly documents this as textbook RR, separate from Linux real-time `SCHED_RR`.
3. **Shortest Job First (SJF):**
   * *Data Structure:* `java.util.PriorityQueue` (Min-Heap ordered by remaining CPU demand, tie-broken by arrival time and unique Job ID).
   * *Mechanism:* Non-preemptive. Always picks the job with the shortest execution demand. Minimizes average waiting time but requires prior knowledge of job duration.
4. **Static Priority Scheduling:**
   * *Data Structure:* `java.util.PriorityQueue` (Min-Heap ordered by priority value, tie-broken by Job ID).
   * *Mechanism:* Non-preemptive in reference model. Lower priority numbers represent higher priority (0 is highest).
5. **Simplified Classic Completely Fair Scheduler (CFS):**
   * *Data Structure:* `java.util.TreeSet` (Red-Black Self-Balancing Binary Search Tree).
   * *Key Metric:* **Virtual Runtime (`vruntime`)**:
     $$\Delta\text{vruntime} = \Delta\text{runtime} \times \left(\frac{1024}{\text{weight}}\right)$$
   * *Mechanism:* Preemptive with dynamic time slices proportional to process weight within a target latency window (default: 24ms, min granularity: 3ms). Tasks with lowest `vruntime` are dispatched first.

### 4.2 Engine Invariants & Edge Cases
* **Event-Driven Idle Jumps:** Instead of looping nanosecond by nanosecond, the simulator computes the next arrival or slice completion and leaps directly to that timestamp ($O(1)$ idle advance).
* **Single-CPU Non-Overlap Invariant:** Verifies that no two jobs execute simultaneously on the single logical CPU core during the simulation.
* **Censoring at Horizon:** If a simulation reaches its time horizon (e.g., 30s) while a job is still partially executed, it is marked **CENSORED**. SchedWise never fabricates a completed turnaround time for an unfinished job.

---

## 5. Contention, Benchmarking & Experimentation Metrics

### 5.1 Workload Definitions
* **Protected Workload:** The primary user application requiring low latency (e.g., an HTTP API computing cryptographic hashes or an interactive PC game).
* **Background Workload:** CPU-heavy tasks running in batch mode (e.g., CPU-bound SHA-256 worker threads, background rendering, or file indexing).
* **Observer Workload:** Monitoring threads, metrics collection, and browser UI servers pinned to separate cores to avoid perturbing measurements.

### 5.2 Latency Statistics
* **Mean Latency (ms):** Arithmetic average of measured request round-trip times.
* **p95 Latency (95th Percentile):** The latency threshold below which 95% of requests complete. Critical for spotting service degradation.
* **p99 Latency (99th Percentile):** The tail latency threshold below which 99% of requests complete. Reflects worst-case user experience.
* **Turnaround Time (ms):** Time elapsed between job submission/arrival and its complete finish:
  $$\text{Turnaround} = T_{\text{completion}} - T_{\text{arrival}}$$
* **Waiting Time (ms):** Total time a job spent sitting in the ready queue ready to run but not dispatched:
  $$\text{Waiting Time} = \text{Turnaround} - \text{CPU Service Time}$$
* **Response Time (ms):** Time elapsed from arrival until the CPU grants the very first execution slice:
  $$\text{Response Time} = T_{\text{first\_dispatch}} - T_{\text{arrival}}$$

### 5.3 Contention Evidence Indicators
* **Contention Ratio:** Ratio of p95 latency under contention to baseline p95 latency:
  $$\text{Ratio} = \frac{\text{p95}_{\text{contention}}}{\text{p95}_{\text{baseline}}}$$
  A ratio $> 1.5\times$ indicates noticeable interference; $> 3.0\times$ indicates severe starvation.
* **Service Throughput (req/s):** Completed HTTP requests per second.
* **Background Progress (hashes/s):** Real cryptographic work executed by background workers per second.
* **Comparability Check:** Before declaring an optimization successful, SchedWise checks:
  1. Did the client offer the identical request rate?
  2. Did all background workers stay alive during the trial?
  3. Were core pinnings identical across both phases?
  If parameters differed, SchedWise flags the result as **INCOMPARABLE** to avoid misleading conclusions.

---

## 6. Recommendation & OS Control Concepts

### 6.1 Contention Detection Engine
Evaluates real-time snapshot windows:
* Sustained CPU utilization on shared core $> 85\%$.
* Co-existence of at least one protected task and one background task sharing the core.
* Corroborating evidence from PSI (`some > 15%`) or measured p95 latency inflation $> 1.5\times$.

### 6.2 Candidate Evaluation
Evaluates unprivileged nice changes for background processes:
* **Option A: Nice +5:** Reduces background worker CPU weight from 1024 to 335 ($\approx 67\%$ weight reduction). Recommended for moderate contention.
* **Option B: Nice +10:** Reduces background worker CPU weight from 1024 to 110 ($\approx 89\%$ weight reduction). Recommended for heavy contention.

### 6.3 Action Safety Guardrails
* **Target Registry Validation:** Actions target *only* owned, single-threaded demo workers spawned by the experiment supervisor. Arbitrary external user processes cannot be mutated.
* **Idempotency Key:** Every action request requires an `actionId` UUID. Retrying a request returns the previously recorded result from SQLite without repeating OS commands.
* **Readback Verification:** Immediately after executing `renice`, SchedWise re-reads `/proc/[pid]/stat`. If the observed nice value does not match the requested target, the action is marked **FAILED**.
* **PID-Reuse Protection:** Verifies that `startTicks` did not change during the mutation to prevent acting on an exited process replaced by a new process.

---

## 7. Game Shield & Process Optimization Concepts

```
+---------------------------------------------------------------------------------+
|                               GAME SHIELD ENGINE                                |
|                                                                                 |
|  [Target Process Selected] (e.g., Game.exe / PID 4120)                          |
|         |                                                                       |
|         v                                                                       |
|  [System Essentials Shield]                                                     |
|         |---> Audio: pipewire, wireplumber, pulseaudio        [IMMUNE: PRESERVED] |
|         |---> Display: Xorg, gnome-shell, kwin, wayland       [IMMUNE: PRESERVED] |
|         |---> Kernel: kthreadd, systemd, PID <= 100           [IMMUNE: PRESERVED] |
|         |---> Shell: bash, sshd, antigravity, schedwise       [IMMUNE: PRESERVED] |
|         |                                                                       |
|         v                                                                       |
|  [Non-Essential User Apps] (Compilers, Discord, Chrome, Torrent, Electron)     |
|         |                                                                       |
|         +---> Strategy: SUSPEND (SIGSTOP) ----> 0% CPU, 100% RAM Reclaimed      |
|         +---> Strategy: DEPRIORITIZE (renice +19) -> Lowest CPU Priority        |
|         |                                                                       |
|  [Safety Watchdog Loop] (1000ms Poll)                                           |
|         `---> When Target Game Exits ----> Auto-Unfreeze All via SIGCONT        |
+---------------------------------------------------------------------------------+
```

### 7.1 System Essentials Immunity
To ensure game optimization never destabilizes the operating system, freezes the display, or mutes Discord/in-game audio, SchedWise implements an immutable safety shield:
* **Kernel & Init:** PIDs $\le 100$, PID 1 (`systemd`/`init`), `kthreadd`, workqueues.
* **Display Compositors & Window Managers:** `gnome-shell`, `Xorg`, `Xwayland`, `kwin`, `mutter`, `sway`, `hyprland`.
* **Audio Daemons:** `pipewire`, `wireplumber`, `pulseaudio`, `alsactl`.
* **Input & Hardware Bus Daemons:** `dbus-daemon`, `systemd-logind`, `udevd`.
* **Parent Shell & IDE:** SchedWise itself, current terminal session, SSH daemon.
Any process matching these signatures receives an immutable `IMMUNE` badge and cannot be suspended.

### 7.2 Optimization Strategies
* **SUSPEND (SIGSTOP):** Sends POSIX signal 19 to pause execution. Instantly eliminates 100% of CPU contention caused by background browsers, electron apps, or background builds.
* **DEPRIORITIZE (Renice +19):** Lowers CPU weight to minimum possible priority (weight 15). Background tasks only execute when the game/service is completely idle.
* **CPU Pinning (`taskset`):** Optionally binds the game process exclusively to a specific high-performance CPU core.

### 7.3 Automatic Unfreeze Watchdog
A background Java daemon monitors `/proc/[targetPid]` once per second. The moment the user closes the protected application or game, the watchdog automatically dispatches `SIGCONT` to all suspended processes, restoring their execution without manual intervention.

---

## 8. Security, Session & Networking Concepts

### 8.1 Per-Launch Ephemeral Session Token
* On backend boot, SchedWise generates a cryptographically secure 256-bit random token (`SecureRandom`) encoded as a URL-safe Base64 string.
* Stored in `data/session-token` with restricted Linux POSIX permissions (`rw-------` / 0600).
* All REST endpoints and SSE streams require `Authorization: Bearer <token>`.
* In-flight token comparison uses constant-time `MessageDigest.isEqual` to prevent timing attacks.

### 8.2 Origin & CORS Protection
* Mutations (POST requests) require strict `Origin` header matching (`http://127.0.0.1:5173`, `http://localhost:5173`, `http://127.0.0.1:8080`, `http://localhost:8080`).
* Arbitrary external websites cannot execute CSRF attacks against local SchedWise APIs.

### 8.3 Server-Sent Events (SSE) Stream Protocol
* Endpoint: `GET /api/events` (Media type: `text/event-stream`).
* Emits live snapshots, experiment state transitions, and background heartbeat pings.
* Employs sequence cursors (`sessionId:sequence`). If the browser temporarily disconnects, it reconnects using `Last-Event-ID` to replay missed telemetry without duplicate processing.

---

## 9. Complete Feature Reference by UI Panel

```
+-------------------------------------------------------------------------------+
|                             SCHEDWISE DASHBOARD                               |
| [Monitor] [Experiment] [What-If] [Recommendations] [Results] [GameShield] [Env] |
+-------------------------------------------------------------------------------+
```

### 9.1 Tab 1: Live Monitor (`LiveMonitorPanel.tsx`)
* **Real CPU Sparklines:** Displays 30-sample rolling history of whole-machine CPU busy % and selected core CPU busy %. Never backfilled with fake history on initial connection.
* **Pressure Stall Information (PSI):** Displays 10-second, 60-second, and 300-second CPU stall percentages if supported by kernel.
* **Real Linux Process Table:** Paginated list of real OS processes displaying PID, process name, state (R/S/D/Z), nice level, thread count, user/system CPU %, and role tags.
* **Workload Role Tagging:** Allows users to manually tag any external process as `PROTECTED_SERVICE`, `BACKGROUND`, or `OBSERVE_ONLY`.

### 9.2 Tab 2: Contention Experiment (`ExperimentPanel.tsx`)
* **Phase Stepper Bar:** Visual progress tracking across 7 distinct states: `IDLE`, `BASELINE_STARTING`, `BASELINE_RUNNING`, `CONTENTION_STARTING`, `CONTENTION_RUNNING`, `COMPLETED`, `STOPPED`.
* **Workload Calibrator:** Automatically runs a 2-second calibration trial to determine the CPU demand required for 100 SHA-256 iterations on the host machine before freezing parameters.
* **Pinned Core Selector:** Allows selecting which logical core (e.g., Core 0) will host the competing experiment workloads.
* **Emergency Stop Control:** Immediately halts Python supervisor, terminating child workers and releasing core reservations.

### 9.3 Tab 3: What-If Schedulers (`SimulationPanel.tsx`)
* **Capture Selector:** Dropdown to select any completed real trial from the SQLite archive.
* **Algorithm Comparison Cards:** Side-by-side performance cards comparing FCFS, Round Robin, SJF, Priority, and CFS across:
  * Mean Waiting Time (ms)
  * Mean Turnaround Time (ms)
  * Response Time (ms)
  * CPU Utilization %
  * Completed vs Censored Job counts
* **Interactive Gantt Timeline:** Visual execution bar showing exact time intervals where each request and background worker occupied the CPU.

### 9.4 Tab 4: Recommendations & Control (`RecommendationPanel.tsx`)
* **Evidence Cards:** Summarizes measured contention ratio, CPU utilization, thread concurrency, and PSI metrics.
* **Candidate Priority Options:** Displays proposed nice adjustments (Nice +5 / Nice +10) along with expected trade-offs and CFS weight drops.
* **Confirmation Safety Modal:** Prompts user to confirm the exact target PID, current nice, proposed nice, and non-reversible nice limitations.
* **Action Audit Ledger:** Persistent log from SQLite showing timestamp, target PID, action status (`SUCCESS` / `FAILED`), original nice, and verified readback nice.

### 9.5 Tab 5: Results & Validation (`ResultsPanel.tsx`)
* **Three-Way Comparison Matrix:** Structured table comparing Baseline vs Contention vs After-Action phases across:
  * Mean Latency (ms)
  * p95 Latency (ms)
  * p99 Latency (ms)
  * Error Rate (%)
  * Background Hash Throughput (hashes/s)
* **Comparability Engine:** Automatic verification validating that client request frequency and worker counts remained unchanged across phases.
* **Session Exporter:** Generates a downloadable `.zip` archive containing `summary.json`, `events.jsonl`, `samples.jsonl`, and SQLite audit logs.

### 9.6 Tab 6: Game Shield (Optimizer) (`GameShieldPanel.tsx`)
* **Process Selection Dropdown:** Lists active user processes sorted by RAM consumption for easy selection of target games or IDEs.
* **System Essentials Immunity Badges:** Visual labels identifying immune audio, compositor, shell, and kernel daemons.
* **Optimization Mode Radio:** Choose between **SUSPEND** (SIGSTOP) or **DEPRIORITIZE** (Renice +19).
* **Live Status HUD:** Real-time display of active shielded PID, reclaimed RAM (MB), estimated reclaimed CPU load (%), and watchdog heartbeat.
* **Instant Unfreeze Control:** One-click manual restoration of all paused applications.

### 9.7 Tab 7: Environment & Scope (`EnvironmentPanel.tsx`)
* **Platform Scope Card:** Explains whether the application is running on native Linux or WSL2 (Windows Subsystem for Linux).
* **Kernel & CPU Discovery:** Kernel release string, CPU model name, logical CPU count, allowed CPU list, and timer tick rate (`CLK_TCK`).
* **Storage & Persistence Status:** Location of SQLite database, database size, and session token path.
* **Capability Matrix:** Verification status of `/proc`, PSI, taskset, renice, and unprivileged permissions.

---

## 10. Alphabetical Dictionary of All Terms & Acronyms

### A
* **Action Audit Store:** SQLite database table (`action_audits`) tracking every OS priority change attempt, timestamp, PID, and post-action readback verification.
* **Affinity:** The binding of a process or thread to one or more specific CPU cores (see `taskset`).
* **After-Action Phase:** The measurement window occurring after a priority change has been applied, verifying whether performance recovered.
* **ArrayDeque:** Resizable array implementation of the `Deque` interface in Java, used for $O(1)$ enqueue and dequeue operations in FCFS and Round Robin.

### B
* **Baseline Phase:** The initial phase of an experiment measuring nominal service latency under load without any background competition.
* **BinaryFinder:** Backend utility locating approved system executables across `/usr/bin` and `/bin` with executable permission verification.
* **Boot ID:** A random UUID generated by Linux on every boot (`/proc/sys/kernel/random/boot_id`), used in SchedWise process identity tuples.

### C
* **CAP_SYS_NICE:** Linux kernel capability required to lower a process's nice value (increase priority) or set real-time scheduling policies. SchedWise operates without this capability.
* **Censored Job:** A simulated task that did not finish executing before the simulation horizon elapsed. Turnaround time is marked `null` rather than estimated.
* **CFS (Completely Fair Scheduler):** The default general-purpose Linux CPU scheduler introduced in kernel 2.6.23, utilizing red-black trees and virtual runtime accounting.
* **Cgroup (Control Group):** Linux kernel feature for isolating and limiting resource usage (CPU, memory, disk I/O) of process collections.
* **CLK_TCK:** Operating system clock ticks per second (usually 100 on Linux), obtained via `sysconf(_SC_CLK_TCK)` or `getconf CLK_TCK`.
* **Contention:** Interference occurring when multiple runnable tasks simultaneously demand more CPU time than the allocated hardware core can provide.
* **Convoy Effect:** An operating system phenomenon in non-preemptive schedulers (like FCFS) where short processes queue behind a long CPU-intensive process.
* **CORS (Cross-Origin Resource Sharing):** HTTP header mechanism that restricts which external web origins can communicate with the backend.

### D
* **Delta Ticks:** The difference in process or CPU ticks between two consecutive samples: $\Delta\text{ticks} = \text{ticks}_{t_2} - \text{ticks}_{t_1}$.
* **Deprioritize:** Lowering a process's scheduling priority by increasing its nice value to `+19` (minimum CFS weight).
* **Discrete-Event Simulation:** Modeling system behavior as a chronological sequence of distinct instantaneous events (arrivals, dispatches, slice completions) rather than continuous time.

### E
* **EEVDF (Earliest Eligible Virtual Deadline First):** Modern Linux scheduler policy (introduced in Linux 6.6) succeeding classic CFS. SchedWise models classic CFS and explicitly documents this historical distinction.
* **Elapsed Time:** Monotonic wall-clock time passed between two events, measured via `System.nanoTime()` or `clock_gettime(CLOCK_MONOTONIC)`.
* **ErrorBoundary:** React component wrapper catching unexpected JavaScript rendering errors in child components and rendering a recovery HUD.

### F
* **FCFS (First-Come, First-Served):** Simplest scheduling algorithm where jobs are serviced strictly in arrival order. Non-preemptive.
* **Flyway-Style Migration:** Database schema versioning mechanism using incremental SQL scripts (`V1__sessions.sql`, `V2__actions.sql`) and SQLite `PRAGMA user_version`.

### G
* **Gantt Chart:** Visual horizontal bar chart illustrating the chronological execution segments of competing tasks over a timeline.
* **Game Shield:** Optimization feature protecting gaming and interactive tasks by temporarily suspending background applications while shielding system essentials.

### H
* **Horizon (Simulation Horizon):** The maximum simulated time window (e.g., 30,000ms) beyond which the discrete-event engine stops executing.

### I
* **Idempotency:** A property where making the identical API call multiple times produces the exact same outcome without duplicate actions.
* **Immunity Shield:** SchedWise safety filter preventing system-critical daemons (compositors, audio servers, init) from being suspended or reniced.
* **Involuntary Context Switch:** When the OS kernel forces a running thread off the CPU because its time slice expired or a higher-priority task woke up.
* **I/O Wait (`iowait`):** Time the CPU spent idle waiting for an outstanding disk or network I/O request to finish.

### J
* **Job:** SchedWise internal Java abstraction (`Job.java`) representing a schedulable task unit with an arrival offset, CPU demand, priority, and weight.
* **JSONL (JSON Lines):** A text format where each line is a valid independent JSON object, used for streaming telemetry and storing raw request traces (`events.jsonl`).

### L
* **Latency:** Time taken for an individual request to travel from client dispatch, process on the server, and return.
* **Latency Target (`latencyTargetNs`):** CFS parameter representing the time period during which all runnable tasks must be scheduled at least once (default: 24ms).
* **LinuxSource:** Testable abstraction layer mediating all direct filesystem reads to Linux `/proc` and `/sys`.

### M
* **Manifest (`manifest.json`):** Metadata file generated at the start of an experiment recording trial parameters, CPU pinning, and worker identities.
* **Min Granularity (`minGranularityNs`):** The minimum uninterrupted execution slice CFS allocates to any task to avoid excessive context-switching overhead (default: 3ms).

### N
* **Nice:** POSIX priority value ranging from `-20` to `+19`. See [Nice Value](#33-priorities-nice-values--cpu-weights).
* **Non-Preemptive:** Scheduling policy where a task, once granted the CPU, executes uninterrupted until completion or I/O blockage.

### O
* **Observe Only:** Workload role assigned to monitoring daemons and passive processes that should neither be protected nor deprioritized.
* **Offered Load:** The target rate of requests scheduled by the benchmark load generator (e.g., 20 req/s), maintained independently of server response speed.

### P
* **p95 Latency:** 95th percentile latency. See [Latency Statistics](#52-latency-statistics).
* **p99 Latency:** 99th percentile latency. See [Latency Statistics](#52-latency-statistics).
* **PID (Process ID):** Unique numerical identifier assigned by the Linux kernel to an active process.
* **Preemptive:** Scheduling policy where the operating system or simulator can interrupt a running task to grant the CPU to another task.
* **Priority Scheduler:** Algorithm dispatching tasks based on numerical priority levels.
* **ProcessBuilder:** Standard Java API used to safely invoke external approved executables with fixed string argument arrays without shell interpolation.
* **PSI (Pressure Stall Information):** Linux kernel feature measuring CPU, memory, and I/O starvation percentages.

### Q
* **Quantum (Time Slice):** Fixed duration of continuous execution granted to a task in Round Robin scheduling before preemption occurs.

### R
* **Ready Queue:** Abstract data structure holding all tasks currently ready to execute on the CPU.
* **Reclaimed RAM:** Total physical resident memory (RSS) held by background applications that are currently suspended via `SIGSTOP`.
* **Renice:** Linux system command used to modify the nice value of an existing running process.
* **Response Time:** Time elapsed between arrival and first dispatch.
* **Round Robin (RR):** Preemptive scheduling algorithm allocating fixed cyclic time slices.
* **RSS (Resident Set Size):** The portion of memory occupied by a process that is held in actual physical RAM.

### S
* **SC_CLK_TCK:** System configuration variable defining clock ticks per second.
* **SCHED_RR:** Linux real-time scheduling policy, distinct from textbook Round Robin.
* **SIGCONT:** POSIX signal 18 resuming paused processes.
* **SIGSTOP:** POSIX signal 19 freezing processes.
* **SJF (Shortest Job First):** Scheduling algorithm prioritizing tasks with the lowest remaining CPU burst.
* **Sparkline:** Compact trend chart illustrating CPU history without full axis overhead.
* **SSE (Server-Sent Events):** Unidirectional HTTP streaming protocol pushing real-time server updates to the browser.
* **StartTicks:** Process start time in ticks since boot, extracted from field 22 of `/proc/[pid]/stat`.
* **Steal Time:** Virtual CPU cycles stolen by a hypervisor.
* **System Essentials:** Core OS daemons protected by the SchedWise immunity shield.

### T
* **Taskset:** Linux command used to retrieve or set the CPU affinity mask of a process.
* **Throughput:** Number of requests or units of work completed per unit time (req/s, hashes/s).
* **TreeSet:** Java implementation of a self-balancing Red-Black binary search tree, used to maintain `vruntime` ordering in the simplified CFS model.
* **Turnaround Time:** Total duration from task arrival to completion.

### U
* **UID (User ID):** Numeric identifier of the Linux user running a process.
* **Unprivileged:** Operating within standard user security constraints without root (`sudo`) or elevated capabilities.
* **Utime:** CPU time spent in user space (in clock ticks).

### V
* **Voluntary Context Switch:** When a thread willingly yields the CPU before its slice expires, typically to wait for I/O or sleep.
* **Vruntime (Virtual Runtime):** Metric tracked by CFS representing the scaled execution time a task has consumed.

### W
* **Watchdog:** Background monitoring thread verifying target process health and ensuring automated cleanup or unfreezing.
* **Weight (CFS Weight):** Numerical value representing a process's proportional CPU entitlement.
* **Workload Role:** Semantic classification of a process (`PROTECTED_SERVICE`, `BACKGROUND`, `OBSERVE_ONLY`).
* **WSL2 (Windows Subsystem for Linux 2):** Virtualized Linux utility environment running on Windows, supported by SchedWise via standard `/proc` interfaces.

### Z
* **Zero-Mock Policy:** Core architectural rule barring fake telemetry or simulated mock charts in production endpoints.
* **Zombie Process (`Z` state):** A process that has completed execution but still has an entry in the process table to report exit status to its parent. SchedWise filters out zombies during candidate scans.
