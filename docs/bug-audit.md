# Hardening audit — 5 October 2026

## Scope and decision

Audited the supplied testing plan against the actual source. Its prefilled “100% clean” claims were not evidence: production simulation and Shield code contained fabricated fallback values. This report supersedes those claims for the reviewed paths.

The user explicitly approved **selected external applications with identity checks and exact confirmation**. This extends the original managed-worker-only scope for Game Shield only. The existing Allocation Advisor and managed nice-action API retain their original boundary.

Shield now requires an explicit background selection (1–16 processes), full boot/PID/start-ticks identities, expected current nice values, a recognized strategy, and a duration of 1–180 seconds. The UI defaults to no background selections and no core pinning. Old activation requests containing only a target PID are rejected; the updated frontend supplies the new fields.

## Findings and fixes

| Severity | Finding | Resolution |
| --- | --- | --- |
| Critical | Shield scanned and acted on unrelated same-user applications; tests invoked that broad path | Removed implicit selection. All actions require exact selected identities. Tests use isolated adapters or explicitly created child processes. |
| High | Shield restoration used bare PIDs and suppressed command failures | Revalidate identity/UID, isolate each restoration attempt, check exit/readback, retain failed targets and errors for retry. Reused PIDs are never intentionally signalled. |
| High | Partial activation could leave earlier targets changed | Track attempted mutations before invoking commands and attempt rollback on failure, retaining ambiguous/failed outcomes. |
| High | Unbounded Shield lifetime; target-exit check used PID existence alone | Identity-aware watchdog and a monotonic 180-second maximum. No default affinity pin. |
| High | Invented Shield CPU savings and fixed zero CPU observations | Savings and unmeasured CPU are null. RSS comes from observed `VmRSS` in KiB, not assumed 4096-byte pages; missing RSS remains null. Full command lines are no longer collected. |
| High | Simulation invented 38 ms service demand and two workers with 45%-of-window demand | Missing measured inputs produce INSUFFICIENT with no synthetic jobs. Available worker CPU is explicitly documented as an aggregate approximation, not an exact burst. |
| High | Simulation accepted browser-provided capture paths | Require a session UUID, confine paths to the capture root, reject symlinked directories. |
| Medium | Pinning errors were ignored; no restore/readback | Validate allowed CPU and single-threaded target, read back affinity, save original mask, and retain restoration failures. |
| Medium | Invalid strategy silently became SUSPEND | Reject unknown strategies, nonpositive/group PIDs, duplicate targets, stale identities, stopped/traced targets, foreign UID, backend/ancestors, and protected classifications. Deprioritize requires a single-threaded target. |
| Medium | Failed auth retried every two seconds forever | HTTP 401 clears the invalid token, stops automatic retry, and opens session entry. Cleanly ended streams now use delayed retry rather than a tight reconnect loop. |
| Medium | Render failures could blank the application | Panel and application error boundaries with explicit recovery; they do not claim to stop backend workloads. |
| Medium | Inconsistent activation error responses | Game Shield activation returns JSON `{error}` for validation/conflict errors; frontend shows structured failures in the confirmation dialog. |
| Medium | Duplicate scheduler IDs could lose tasks in ordered sets | Reject duplicate/null identities and already-served simulation inputs. Zero/negative demand was already rejected; regression coverage confirms it. |
| Medium | Invalid simulation parameters silently became defaults | Reject nonpositive/out-of-range explicit inputs; bound model count, events, and horizon. |
| Low | Hardcoded executable location | Approved-name BinaryFinder checks executable files in `/usr/bin`, then `/bin`; used for control commands and timing probes. |
| Low | Counter edge cases | Reject negative CPU counters, detect arithmetic overflow, clamp valid machine busy percentages while keeping steal separate. |

Real retained captures also contained phase-tagged service arrivals outside the phase boundaries. Modeling now excludes and reports those arrivals rather than shifting early arrivals to time zero. Recorded journals are preserved.

## Verification

Environment: unprivileged UID 1000, Linux 7.0.0-28-generic x86_64, Java 21, Node 22, Python 3.14, 100 clock ticks/second, visible affinity 0–11. CPU PSI was available; schedstat accounting was disabled. `scripts/doctor.sh` was run read-only.

Executed checks:

- Maven test suite: 54 tests, zero failures/errors/skips.
- Python harness: 7 tests.
- Frontend: 12 tests, strict typecheck, production build.
- `scripts/verify.sh`: final combined verification recorded in `docs/build-status.md`.

Added focused checks for executable fallback; invalid/duplicate jobs and simultaneous RR boundary arrivals; absent/corrupt capture evidence; capture traversal; CPU counter errors; 400/409 JSON; Shield origin/method authorization; zombie/transient process exclusion; stale identity; bounded timeout; partial-startup rollback; and restoration failure isolation.

A real Linux test created three finite `sleep` children: one target, one explicitly selected background child, and one unselected sibling. It verified the selected child reached state T, the sibling did not, and the selected child resumed. All three children were terminated and waited for in cleanup. This is real process-control evidence, not a throughput or performance benchmark. No arbitrary desktop application was intentionally frozen during this audit. Existing managed nice-action tests also ran.

Production text/source review found and removed the concrete synthetic fallbacks above. This was not a repository-wide AST proof or certification that every possible defect has been eliminated. Test fixtures remain under test sources. The workspace has no usable Git metadata, so no commit or Git diff was produced.

## Remaining limitations and next checks

- **Abrupt backend death:** the in-process watchdog and shutdown hook cannot run after SIGKILL, JVM failure, or host failure. Suspended external applications may need manual resumption. Independent crash recovery and durable restoration journals remain unimplemented; do not call Shield crash-safe.
- **Residual PID race:** proc checks and readback narrow but do not eliminate the exit/PID-reuse gap before a syscall. This implementation does not use pidfds for signalling.
- **Restoration permissions:** reducing nice back to its original value may fail without privileges. Failed restoration remains visible and blocks a new Shield session. It is not an unconditional Undo.
- **Thread creation:** pinning/deprioritization check a single-threaded target at action time; they do not provide atomic protection against later thread creation.
- **Classification:** process-name/cgroup exclusions are conservative protections, not proof that pausing an application is harmless.
- **Browser acceptance update:** the later user-requested `http://localhost:5173/` origin was accessible. A focused rendering, focus, responsive and controlled Shield interaction pass completed; see `browser-testing.md` for exact checks and unexercised cases. This does not establish exhaustive browser acceptance.
- No new full contention trial or performance claim was run for this hardening work. Older performance evidence predates these changes.
