# AGENTS.md — SchedWise Agent Instructions

These instructions apply to the entire SchedWise repository. Read `plan.md` for the locked product scope, architecture, measurement protocol, milestones and acceptance gates. This file is intended for Astra/Codex and other coding agents working on the project.

## 1. Mission and locked choices

Build a useful Linux CPU allocation advisor for developers or small-server operators who need a protected service to remain responsive while background jobs consume CPU.

Deliver a working real-system flow:

**Real collection → contention evidence → measured-input simulation → explainable recommendation → confirmed priority change → measured validation.**

Locked stack: Java 21 + Spring Boot/Maven backend; React + TypeScript/Vite frontend; SQLite/JDBC; REST + SSE; small Python-standard-library demo tools. Linux is the backend/control platform. Windows users run that backend in WSL2/Linux VM and open the dashboard in their browser. Do not add a native Windows scheduler implementation in v1.

No cloud-only substitute, teaching-only simulator, kernel patch, privileged backend, AI classifier, LLM dependency, or decorative dashboard that lacks real collection.

## 2. Nonnegotiable data policy

1. All live metrics must come from the real Linux environment or actual application instrumentation.
2. Never introduce random values, fixed process lists, fake percentages, hardcoded benchmark outcomes, placeholder chart arrays, or fallback fake telemetry.
3. Build frontend views against the real backend. Until a source works, show empty/loading/unavailable states.
4. Deterministic algorithm inputs and parser fixtures are allowed solely inside automated tests. They must never enter product/demo endpoints, production bundles, screenshots presented as real measurements, or exported performance claims.
5. A demo workload may be purpose-built: it must actually execute the work and report observed timing, CPU time and progress. Sleeping while increasing a fake progress counter is prohibited.
6. Classify values as measured, derived, simulated, or recorded. Preserve source ID, units, timestamps, validity, input window and environment.
7. Simulation results must be labeled simulated. A real input does not make the simulated output a measurement.
8. Unavailable is distinct from zero; stale is distinct from live. Do not conceal collection failure behind cached data without a stale label.
9. Recorded mode uses real captured sessions and an explicit original timestamp. It never silently replaces live mode.
10. Report unchanged or worse results honestly. Never tune display values to match a promised demo outcome.

## 3. Work sequence and autonomy

Before editing:

- Inspect the repository and existing instructions. Preserve unrelated changes.
- Read `plan.md` and determine the earliest incomplete milestone using actual files and verification evidence.
- Check runtime/platform capabilities; do not assume proc, PSI, priority permissions or affinity support.
- State a short implementation plan and continue through a reviewable working increment.

Implement M1 through M6 in order. Establish a real collector and measurable contention demo before adding extensive graphs or optimization claims. Build vertical increments that can be run, then integrate. Do not treat a passing frontend build as a working system.

Choose routine implementation details autonomously within the locked scope. Ask only when missing information blocks a significant product choice or an operation requires authorization. Do not repeatedly ask the user to confirm the stack or the no-mock requirement.

Do not launch resource-heavy experiments automatically. Small bounded integration workloads are acceptable within an authorized implementation/testing session; a full demo starts through an explicit user action or documented invoked command. Starting the app must not silently saturate a core or change priorities.

Do not claim a milestone is complete until its acceptance gate has evidence. When Linux integration cannot run in the available environment, deliver implementable code and checks, and plainly mark the environment limitation and unverified gates. Do not substitute fixtures as proof of live behavior.

## 4. Layer boundaries and coding rules

- Keep collectors, pure simulation, analysis, recommendations, Linux actions and API handlers separate.
- Scheduler classes implement one documented interface and operate on immutable measured-input DTOs; they cannot execute OS commands.
- Store platform reads/commands behind testable adapters. Use argument arrays, fixed approved executables, checked exit codes, timeouts and bounded output; never `sh -c` with interpolated input.
- Validate request bodies, numerical ranges, captured-session ownership, identities and roles on the backend.
- Use TypeScript strict mode. Do not weaken types with `any` to hide invalid/unavailable data.
- Use a tagged value/result structure for unavailable data and provenance; preserve these semantics throughout SSE, storage and UI.
- Pin dependencies, keep wrappers/lockfiles, and verify third-party APIs before relying on them. Use maintained compatible versions rather than assuming the newest major versions work together.
- Keep data retention, sample size, simulation events, work duration, spawned workers and request concurrency bounded.
- Avoid blocking collection while a simulation or SQLite write is running. Publish immutable snapshots and apply bounded buffering/backpressure.
- Use monotonic elapsed time for durations. Document units in DTOs and `docs/metrics.md`.
- Do not add external services or complex infrastructure without a concrete requirement in the locked plan.

## 5. Linux collection requirements

- Identify a process by `(bootId, pid, startTicks)`, not PID alone. Reset deltas on identity change.
- Parse `/proc/<pid>/stat` around its command field correctly. Names can contain whitespace and parentheses.
- Determine clock ticks per second; do not hardcode a presumed value.
- Process CPU percentage uses CPU-time delta / elapsed time; 100% means one logical CPU. A process may use more than 100%.
- Whole-machine CPU and selected-core CPU use separate labels and formulas. Account for guest double counting and expose steal where relevant.
- First samples, invalid deltas, inaccessible files, exited processes and changed identities are explicit states.
- Linux schedules threads. Do not present leader-thread wait/context-switch statistics as full-process totals. Aggregate selected TIDs when the feature needs totals and label incomplete access.
- Global runnable count is not a per-core queue measurement. Sampled process states are not dispatch events.
- CPU PSI is optional; use `some` appropriately and do not interpret system-level CPU `full` as meaningful.
- Schedstat may be absent or inactive. Do not automatically enable global kernel settings or treat disabled-accounting zeros as proof of no waiting.
- Detect affinity, cgroup quotas and autogroup context before predicting effects of nice changes.
- WSL2/container/VM collection reports the visible Linux scope. Never claim native Windows-host coverage.
- Avoid collecting command lines/environment contents unless the feature requires them. They can contain secrets. Prefer process name, ID and role; redact exported machine identifiers where appropriate.

## 6. Modeling and DSA requirements

Implement FCFS, Round Robin, nonpreemptive Priority, nonpreemptive SJF and simplified classic CFS first. Add EEVDF-inspired modeling only after the real v1 experiment is complete.

- FCFS/RR use `ArrayDeque`; SJF/Priority use `PriorityQueue`; simplified CFS uses an ordered `TreeSet`.
- Stable ordering requires unique task/job IDs; equal ordering keys must not lose tasks.
- Never mutate a key while an object remains in a heap or ordered set. Remove/update/reinsert where necessary.
- Model scheduling events and jump over idle intervals; do not loop through every nanosecond.
- Validate quanta, demands, weights, arrivals, horizon and event limits. Handle zero/invalid values explicitly.
- Account for arrivals during an RR slice and document ready-queue ordering at the quantum boundary.
- Use a documented weight mapping and fair-runtime formula for CFS. Document rounding and model overhead.
- Name textbook RR separately from Linux's real-time `SCHED_RR`. Do not apply textbook algorithms as kernel policies.
- Treat classic CFS as a simplified historical model; current Linux fair scheduling includes EEVDF development. Verify documentation and actual environment rather than claiming exact kernel replication.
- Arbitrary process CPU samples do not expose exact bursts, runnable arrivals or remaining demand. Model aggregate served demand with limitations, or return insufficient-input status.
- Instrumented demo service events can provide arrival and CPU-service measurements. They are application-level events, not an exact kernel scheduler trace.
- Report simulated first-dispatch response separately from measured HTTP end-to-end latency.
- Unfinished jobs have no observed/model-completed turnaround. Mark them censored at the horizon.
- Explain complexity using task count, modeled event count, quantum/slice count and output storage; avoid blanket `O(n)` claims.
- If showing fairness, define the runnable cohort and target shares. Do not penalize sleeping tasks for unused capacity or call unequal deliberate allocations inherently unfair.

## 7. Recommendation requirements

Workload roles come from explicit user tags or managed launch metadata. CPU-heavy does not automatically mean unimportant; a process name does not prove latency sensitivity.

Use transparent rules, configurable sustained thresholds and corroborating evidence. High CPU utilization alone is not proof of harmful contention. Tie each recommendation to a real capture, protected workload, competing background tasks, shared CPU scope and observed evidence.

Only weighted-allocation scenarios with a documented nice relationship may drive a nice recommendation. FCFS/SJF/RR comparison results are reference-model comparisons, not operating-system settings.

Show the expected tradeoff and model limitations. Do not invent confidence percentages, precise future p95 values, background completion times or causal explanations unsupported by measurements. If the data cannot support quantitative modeling, offer qualified advice or an insufficient-input state.

Keep simulated predictions and real after-change measurements in separate fields and panels. Only show a prediction-error calculation when the predicted and measured quantity, workload and time window are comparable.

## 8. Control, permissions and lifecycle

MVP actions target only managed, same-user, single-threaded background demo workers whose identities are recorded at launch. General monitoring is read-only. External-process mutation, negative-nice promotion, affinity optimization and cgroup control are outside the v1 action scope.

- Never run the whole backend as root or assign it `CAP_SYS_NICE`.
- Apply requires explicit confirmation of the exact change; no detector-triggered automatic mutation.
- Verify PID identity, UID, launch registry, role, thread count and expected current nice immediately before acting. Use narrow targets; reject PID 0, negative/group targeting, stale identity and invalid ranges.
- Use race-resistant targeting where supported. Otherwise disclose the remaining process-exit/PID-reuse race, fail closed on mismatches and keep the managed-child boundary; do not claim prechecks eliminate every race.
- Re-read the target value after acting. Record original/requested/observed settings, result and time per action. Do not claim success merely because a button was clicked.
- Linux nice values are per-thread in practice. Single-threaded demo workers simplify v1; process-wide control later must deliberately handle every relevant thread and thread creation races.
- Increasing nice lowers priority; reducing it later can require privileges or suitable resource limits. Never promise unconditional Undo.
- Resetting the managed experiment stops and recreates owned workers from the unchanged normal-priority launcher. Explain that reset creates new work/process identities; it is not restoration of the old process.
- Do not alter parent/launcher priority or global scheduler settings.
- Track every owned child at launch. Cleanup only those verified children; no `pkill python`, broad process-name matching, or stopping unrelated user work.
- Implement stop, timeout, cancellation, partial-startup cleanup and shutdown cleanup. Preserve session evidence after cleanup.
- On lost permissions or capability changes, disable affected actions and explain the condition.

## 9. Local API and UI requirements

Bind the backend to loopback by default. Protect all measurements, exports and actions with a per-launch session token. Validate origin for mutations, allow only explicit frontend dev origins, and do not accept arbitrary browser-provided probe URLs, command names, shell text or disk paths.

Required UI behavior:

- Visible environment scope, collector connection, sample age and optional-source availability.
- Real process IDs, affinities, roles and current nice values.
- Clear empty/loading/unavailable/stale/error states; absent data never becomes a dummy chart.
- Core CPU separate from machine CPU.
- Live measured sample charts separate from simulated Gantt and model queue states.
- Confirmation naming the exact owned worker, current setting, proposed setting and restoration limitation.
- Actual measured latency/errors and background progress following a change.
- A stop control that invokes bounded backend cleanup.
- SSE reconnect without silently duplicating samples; sequence/session IDs distinguish restarted sessions.
- Functional controls with actual APIs; no simulated network delays or nonfunctional buttons presented as completed features.

## 10. Real demo requirements

Follow the protocol in `plan.md` and implement a reproducible runbook.

Use real finite hashing work for background jobs and a real CPU-consuming local HTTP endpoint. Pin competing demo processes to the same allowed core and verify compatible grouping. Keep observers elsewhere when possible; disclose overhead on single-core environments.

Calibrate once before a comparison; then freeze workload parameters, offered load and timing windows. Record baseline, contention and after-change phases. Use fixed-rate scheduled requests with bounded in-flight work; log missed dispatches, errors and timeouts. Never allow slow responses to silently reduce the offered load and make latency look better.

Show p95 with counts and error/deadline-miss rate; show real background hashes/sec. Completed-job time requires a matched fixed-work run that actually finishes. Report model-versus-measurement only for equivalent quantities.

Run at least three matched trials for a performance claim, alternate order with fresh workers, and retain every outcome. Improvement targets are hypotheses, not prefilled results. Inspect low/no improvement rather than hiding it.

Default demo bounds: two background workers on one selected core, bounded memory, 180-second maximum live run, hard request timeouts and an emergency stop. Any longer validation run must be explicitly configured with finite limits. Startup never automatically launches the demo.

## 11. Required verification

Use focused meaningful tests, not tests that merely mirror implementation.

- Java: parser edge cases and identity changes; CPU-unit/delta correctness; scheduler invariants, idle/arrival/tie/quantum cases, weighted service and horizon limits.
- Linux: real controlled child CPU collection; eligible nice increase/readback; invalid/stale/foreign target rejection; worker cleanup after stop and failure.
- Frontend: typecheck/build; unavailable/zero/stale states; SSE reconnection; simulation labels; control failure and confirmation behavior.
- Experiment: end-to-end live flow, comparable measurements, retained raw requests, actual worker progress and bounded lifecycle.
- Data-integrity review: inspect production code/bundle for hardcoded chart/process series, random metrics and fallback fake data. Legitimate fixtures remain isolated under tests.

Use the project's Maven wrapper and documented frontend package-manager scripts once implemented. Implement `scripts/verify.sh` only after those commands exist. Record the exact commands, outcomes and tested Linux environment. Do not say tests or demos passed when they were not run.

## 12. Completion and handoff

At each substantial increment, report what works, relevant checks, actual measurement evidence, limitations and the next milestone. Update implementation status in `plan.md` without deleting its requirements or confusing planned files with existing files.

Final repository delivery includes runnable backend/frontend, actual setup/start commands, `doctor.sh`, verification command, real demo tools, a demo runbook, metric definitions, technical limitations and an export from a real trial when the environment permits.

Before declaring v1 complete, verify the full real flow. Clearly distinguish completed, implemented-but-unverified and deferred work. Do not replace a missing Linux measurement/action layer with a polished mock dashboard.
