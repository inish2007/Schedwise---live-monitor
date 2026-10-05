# SchedWise v1 Limitations & Platform Boundaries

This document defines the architectural, operational, and mathematical limitations of SchedWise v1. These boundaries are intentional design choices to ensure unprivileged execution, real-system safety, and scientific honesty.

---

## 1. Single Logical Core Scope

- **Core Pinning:** SchedWise v1 evaluates CPU contention by pinning competing demo workloads (the protected HTTP service and background hashing workers) to a single allowed logical CPU using `taskset`.
- **Multi-Core Limitations:** While the live monitor observes all logical CPU cores, the recommendation and control engine does not model inter-core migration, NUMA node topology, shared L3 cache contention, or Intel/AMD hardware thread siblings (hyperthreading/SMT core pairing).
- **Core Allocation Advice:** SchedWise recommends CPU priority adjustments (CFS nice values) rather than dynamic CPU affinity re-assignment or cgroup `cpuset` partitioning in v1.

---

## 2. Platform & Virtualization Scope

- **Linux-First Platform:** SchedWise requires the Linux kernel and `/proc` pseudo-filesystem. Native Windows scheduling APIs are unsupported in v1.
- **WSL2 / Linux VM Limitations:**
  - Running SchedWise in WSL2 or a Linux VM monitors and manages the Linux environment only.
  - SchedWise has zero visibility into Windows host processes, Windows background services, or host GPU activity.
  - Hypervisor CPU stealing (exposed via `/proc/stat` steal counters) and host-level CPU overcommit can introduce latency variance outside SchedWise's control.

---

## 3. Privilege Boundaries & Priority Mutation Limits

- **Unprivileged Execution:**
  - SchedWise runs strictly as an unprivileged user (UID $> 0$). It does not use `sudo`, does not run as root, and does not hold Linux capabilities (`CAP_SYS_NICE`).
- **Irreversible Lowering (The "No Undo" Rule):**
  - Standard Linux security policies permit unprivileged processes to *increase* nice (reducing scheduling priority from 0 up to 19).
  - Unprivileged processes **cannot** reduce nice (increasing priority, e.g. from 5 back to 0).
  - Consequently, SchedWise cannot promise an unconditional "Undo" action on an existing process.
  - **Reset Mechanism:** To return to baseline priority, SchedWise stops the existing background worker processes and launches fresh replacement workers with new identities at default nice 0.
- **Narrow Control Target:**
  - SchedWise strictly mutates owned, single-threaded background demo workers recorded in the active experiment supervisor registry.
  - Arbitrary user processes and system daemons are marked `OBSERVE_ONLY` and are strictly read-only.

---

## 4. Race Conditions & Identity Verification

- **Process Identity 3-Tuple:** Processes are identified by `(bootId, pid, startTicks)`.
- **Residual PID-Reuse Race:**
  - SchedWise validates process identity, UID, single-thread count, role, and current nice immediately before invoking `/usr/bin/renice`.
  - However, in standard Linux systems without `pidfd_setpriority` (available only in very recent kernels with specific permissions), a theoretical microsecond race exists between identity validation and syscall execution if the target process terminates and the PID is rapidly recycled by the kernel.
  - SchedWise mitigates this by maintaining a direct parent-child process tree and performing immediate post-action `/proc/<pid>/stat` field 19 readback verification.

---

## 5. Workload Characteristics

- **CPU-Bound Workload Focus:**
  - The demo workloads (HTTP service and background workers) perform CPU-bound SHA-256 hashing.
  - Workloads dominated by disk I/O, heavy memory allocation/paging, or network socket latency will exhibit different contention and recovery dynamics.
- **Single-Threaded Child Assumption:**
  - V1 demo workers are intentionally single-threaded. Multi-threaded processes in Linux have per-thread nice values; process-level nice mutation in multi-threaded programs requires mutating every individual thread (TID), which introduces thread-creation race conditions outside v1 scope.

---

## 6. Discrete-Event DSA Simulation Assumptions

- **Offline Reference Models:**
  - The pure-Java simulation engine (FCFS, Round Robin, Priority, SJF, classic CFS) evaluates offline models driven by captured application-level request arrivals and CPU demand.
  - They are educational and reference comparison models, **not** an exact emulation of Linux kernel dispatch traces or the Linux EEVDF scheduler.
- **Reference vs OS Settings:**
  - Non-preemptive FCFS, Priority, and SJF rankings serve as theoretical benchmarks for understanding queueing tradeoffs. They cannot be set as Linux CFS kernel policies.
- **Queue Wait vs HTTP Latency:**
  - Simulated waiting time describes time spent in a model ready-queue. It is strictly separate from, and must not be placed on the same scale as, end-to-end HTTP response latency (which includes socket transmission, OS buffers, network stack overhead, and full response parsing).
- **Censored Horizons:**
  - Jobs in-flight or waiting when the simulation horizon expires are marked `censored`. No estimated turnaround times are fabricated for incomplete jobs.

---

## 7. Optional Kernel Metrics

- **Pressure Stall Information (PSI):**
  - `/proc/pressure/cpu` requires kernel configuration `CONFIG_PSI=y`. On kernels where PSI is disabled or inaccessible, the metric is displayed as unavailable rather than zero pressure.
- **Scheduler Statistics (`schedstat`):**
  - `/proc/sys/kernel/sched_schedstats` is often disabled (`0`) by default on Linux distributions to minimize accounting overhead. Zero values represent disabled accounting, not proof of zero waiting. SchedWise does not modify global kernel sysctl settings.
