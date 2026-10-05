# SchedWise: Simple & Complete Guide
*Everything you need to know about SchedWise in plain English.*

---

## Quick Summary: What is SchedWise?

**SchedWise** is a smart Linux CPU manager and advisor. 

Imagine your computer's CPU is a busy kitchen:
* You are cooking an important meal (your **Game** or **Web Server**).
* Other people are also in the kitchen doing background tasks (updating software, running heavy browser tabs, compiling code).
* If everyone fights for the same stove at the same time, your important meal gets delayed and freezes up.

SchedWise monitors this kitchen, shows you who is using the CPU, tests how different scheduling rules handle the traffic, and can safely freeze background tasks while you game so your important app runs smoothly.

---

## 1. Why does it say your CPU has "11 threads" (0 to 11)?

### The Hardware:
Your computer has an **AMD Ryzen 5 5500U** processor:
* It has **6 physical CPU cores**.
* Each physical core has **SMT (Simultaneous Multithreading)**, which splits each core into 2 virtual hardware threads.
* Total logical CPU threads = $6 \times 2 = \mathbf{12\text{ threads}}$.

### Why does it count to 11?
In computer science and Linux, computers **start counting from 0**, not 1:
$$\text{Core 1} \rightarrow \text{cpu0}$$
$$\text{Core 2} \rightarrow \text{cpu1}$$
$$\text{Core 3} \rightarrow \text{cpu2}$$
$$\dots$$
$$\text{Core 12} \rightarrow \text{cpu11}$$

So the cores are numbered **0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11**.
When Linux and SchedWise show `0-11` or list up to `cpu11`, it means **all 12 hardware threads are active and online!**

### How does SchedWise know this?
SchedWise does **not** guess or use fake numbers. It asks the Linux kernel directly:
1. It reads `/proc/stat`: Linux outputs exact tick counters for `cpu0`, `cpu1`, ..., `cpu11`.
2. It reads `/proc/self/status`: The Linux kernel explicitly reports `Cpus_allowed_list: 0-11`.
3. SchedWise calculates how much time each of these 12 threads spends working every single second.

---

## 2. What is the "Monitor" function?

The **Live Monitor** is your real-time system x-ray. It gives you 100% honest Linux telemetry with zero fake data.

### Key features of the Monitor:
1. **Machine CPU vs Core CPU**:
   * **Machine CPU**: Shows the overall CPU usage across your entire laptop.
   * **Per-Core CPU (`cpu0` to `cpu11`)**: Shows exactly which specific core is being overloaded.
2. **CPU Pressure (PSI - Pressure Stall Information)**:
   * Reads `/proc/pressure/cpu`.
   * Tells you if tasks are literally waiting in line because the CPU is overwhelmed (`some avg10`).
3. **Live Process Table**:
   * Scans all running programs on your machine.
   * Shows Process ID (PID), program name, CPU usage percentage, current priority (`nice` value), and CPU core affinity.
4. **PID Reuse Protection**:
   * In Linux, when a program closes, its Process ID (PID) can be reused by a completely new program.
   * SchedWise tags each process with `(bootId, PID, startTicks)`. If a process closes and another takes its PID, SchedWise notices immediately and never mixes them up.

---

## 3. What is the "Game Shield" function?

**Game Shield** protects your latency-sensitive apps (like a Game, audio workstation, or streaming software) from lag spikes caused by background apps.

### How it works step-by-step:
```
[1. Inspect] ──► [2. Select Apps] ──► [3. Confirm] ──► [4. Shield ON] ──► [5. Auto-Restore & OFF]
```

1. **Step 1: Inspect (Safe Observation)**
   * Shows you the real CPU usage and CPU pressure.
   * Tells you if your system has contention (heavy background competition) or plenty of free headroom.
   * Honest policy: SchedWise explicitly reminds you that freeing CPU headroom does not automatically promise higher FPS unless your game was bottlenecked by CPU.

2. **Step 2: Select Apps (Advanced Choice)**
   * Lets you choose up to 16 non-essential background applications that you want to temporarily pause (like heavy browsers, discord, torrent clients, code compilers).
   * **System Essentials Immunity**: SchedWise guarantees that essential system services (your sound system like `pipewire`, your screen/desktop like `gnome-shell`/`Xorg`, and kernel threads) **CAN NEVER be paused**.

3. **Step 3: Exact Confirmation**
   * Before anything happens, a modal pops up showing you:
     * Your game name.
     * The exact list of apps being paused with their unique IDs.
     * The exact signal being sent (`SIGSTOP` - pause).
     * The timer duration (1 to 30 minutes, default 15 min).

4. **Step 4: Shield ON (Independent Python Guardian)**
   * SchedWise uses a separate helper process called the **Python Guardian**.
   * The guardian uses Linux `pidfd` (Process File Descriptors) so signals can never hit the wrong program.
   * It writes a backup journal (`shield-state.json`) to disk *before* doing anything.
   * It sends `SIGSTOP` to freeze the background apps, saving 100% of the CPU for your game.

5. **Step 5: Fail-Safe Automatic Restoration**
   * You never have to worry about your background apps staying frozen forever.
   * The guardian automatically unpauses them (`SIGCONT`) when:
     - You click "Turn Off".
     - Your game closes.
     - The timer (e.g. 15 minutes) expires.
     - The browser tab closes or disconnects (lease expiration).
     - The backend crashes (pipe disconnect).
   * You can also run `./scripts/shield_recover.sh` in the terminal anytime to instantly wake up all paused apps!

---

## 4. What is the "Experiment" function?

The **Experiment** tab (with **Lab**, **Models**, **Advisor**, and **Results**) is a scientific laboratory for testing and understanding CPU scheduling algorithms.

It simulates and solves a real developer problem:
> *"My web server needs to reply quickly, but a background batch job is eating 100% of the CPU core. What happens, and how does Linux fix it?"*

### The 4 sub-sections:

### A. Lab (The Live Benchmark)
* Runs a real test on one isolated CPU core (e.g., Core 3).
* **Phase 1 (Baseline)**: Sends requests to a local web service and measures response time (p50, p95 latency) when the CPU is quiet.
* **Phase 2 (Contention)**: Starts real background hashing programs on the exact same core. You see the web service response times skyrocket and requests wait in line.

### B. Models (The What-If DSA Simulator)
* Takes the real request arrival times and CPU service times captured from the Lab.
* Runs them through 5 textbook and real-world CPU scheduling algorithms in pure Java:
  1. **FCFS (First-Come, First-Served)**: Jobs run in arrival order. Long background jobs block fast web requests.
  2. **Round Robin (RR)**: Slices time equally into quantum intervals.
  3. **SJF (Shortest Job First)**: Prioritizes fast web requests over heavy background jobs.
  4. **Priority Scheduling**: Prioritizes jobs with higher importance.
  5. **Simplified CFS (Completely Fair Scheduler)**: Linux's real algorithm using a virtual runtime (`TreeSet`) and priority weights.
* Visualizes an interactive Gantt chart comparing how each algorithm behaves.

### C. Advisor (Actionable Recommendation)
* Uses Linux mathematical weight formulas:
  $$Weight \approx \frac{1024}{1.25^{\text{nice}}}$$
* Recommends lowering the priority of background jobs (increasing their `nice` value from 0 to +10).
* You can click **Apply** to confirm the change. SchedWise calls unprivileged Linux `renice` on the background workers and reads back the new priority from the kernel.

### D. Results (Scientific Verification)
* Compares **Baseline** vs **Contention** vs **After Action**.
* Shows exactly how much response time was restored (e.g. p95 latency dropped from 150ms back down to 12ms) and how many hashes per second the background worker still accomplished.
* Compares whether the simulation predictions matched reality.

---

## Summary Table

| Tab / Function | What It Does | Key Question It Answers |
| :--- | :--- | :--- |
| **Monitor** | Real-time CPU & process telemetry | *"What is running right now, and which core is hot?"* |
| **Game Shield** | Reversible pause (`SIGSTOP`) of background apps with fail-safe recovery | *"How can I stop background apps from lagging my game?"* |
| **Experiment: Lab** | Real HTTP service vs background hash worker benchmark | *"How bad does contention hurt my latency?"* |
| **Experiment: Models** | Discrete-event simulator (FCFS, RR, SJF, Priority, CFS) | *"Which scheduling algorithm handles this traffic best?"* |
| **Experiment: Advisor** | Linux `nice` priority calculation & unprivileged apply | *"What priority adjustment fixes the problem?"* |
| **Experiment: Results** | 3-way statistical comparison (Before, During, After) | *"Did the priority change actually work in real life?"* |
| **System** | Linux kernel version, tick rate, clock speed, and CPU PSI | *"What does the Linux kernel support on this machine?"* |
