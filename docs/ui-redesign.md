# SchedWise UI redesign — 4 October 2026

## Implemented

The supplied Cyberpunk OS plan is applied across the seven existing modules. The design uses a numbered left navigation rail, responsive horizontal navigation, obsidian/gunmetal surfaces, restrained cyan controls, violet simulation labels, monospace measurements, and visible keyboard focus. The session token lives in a native modal dialog and stays in page memory.

- Monitor: timestamp-scaled real CPU history with explicit gaps, separate machine/core readings, searchable process cohort, and an identity-aware process inspector drawer.
- Lab: phase progression, current-phase p50/p95/p99 cards, allowed-core selector, start/export controls, and an emergency stop.
- DSA what-if: algorithm selector, visible quantum/minimum-slice/horizon parameters, and timestamp-positioned simulated segments that preserve idle time. The view explicitly limits its timeline to the first 200 segments.
- Advisor: measured evidence grid, simulated cohort-share bars, eligible candidate controls, exact worker/nice confirmation, observed action results, and the audit trail.
- Results: three-phase comparison and explicit current-versus-archived source selection. Archive metadata opens in a drawer. Archived phase summaries are not supplied by the existing metadata API; the screen says to use the recorded ZIP instead of substituting another run's measurements.
- Shield: restyled controller/status surfaces and actual backend process classifications. Unavailable affinity no longer produces an invented CPU list. Suspended RSS is labeled as an estimate of affected memory, not freed RAM. Performance and zero-data-loss promises were removed.
- Scope: capability cards, optional-source states, and platform disclosures.

The original endpoint paths and action payloads are preserved. No benchmark, full experiment, or Shield activation was initiated for this redesign. This increment does not establish new performance claims or certify the existing Shield controller's safety boundary.

## Display defects corrected

Cancelled/failed trials no longer mark all future phases completed. Unavailable per-core utilization has no zero-valued meter. CPU charts break across missing observations and sequence gaps. Process selection uses boot/PID/start-ticks identity. Finished runs are recorded rather than live. Deadline-miss rates render as JSX instead of `[object Object]`. Zero hashes/second remains a real zero. Archived selection does not silently retain the current run's table. Ineligible or expired recommendations cannot be submitted through the redesigned control. Modal action errors stay visible inside the modal.

## Verification evidence

Environment observed: Linux 7.0.0-28-generic, x86_64, GNU/Linux; `getconf CLK_TCK` returned 100.

- `./scripts/verify.sh` — exit 0: 42 Java tests, 7 Python harness tests, 10 frontend tests, strict TypeScript check, Vite production build.
- Final frontend changes were checked again with `npm --prefix frontend test` and `npm --prefix frontend run build`.
- Production source review found no random metrics, fabricated CPU fallback list, `any` escape hatches, or hardcoded performance series introduced by the redesign. Algorithm parameters and visual grid coordinates are configuration/layout, not telemetry.

Regression coverage adds six focused tests for chart gaps/zero, PSI availability, CPU-mask validation, PID reuse, timeline validity, and cancelled phase progression. The existing four stream tests cover stale/unavailable/zero values and SSE reconnect/framing.

## Unverified browser acceptance

The browser tool rejected localhost access under a saved permission. No browser screenshot, responsive layout inspection, keyboard walkthrough, or interactive dialog verification was completed. Builds and pure-function tests do not prove these checks passed.

When browser access is available, inspect desktop and narrow widths; navigate all seven panels by keyboard; connect/disconnect and interrupt SSE; inspect an exited/reused process identity; refresh captures; verify model labels and idle gaps; review/cancel exact priority confirmations; and check error/readback rendering. Exercise mutations only with explicitly chosen eligible controlled workloads.

Start using the existing `./scripts/dev.sh` workflow and open the printed local frontend address. Startup itself does not launch an experiment.

## Browser verification update — 5 October 2026

Localhost access later succeeded. A focused desktop/mobile and interactive pass is now recorded in [browser-testing.md](browser-testing.md), with actual screenshots and two browser-discovered fixes. The earlier unverified section records the status before this follow-up; unexercised cases remain listed in the new report.
