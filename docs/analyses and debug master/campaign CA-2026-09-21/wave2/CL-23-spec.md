# CL-23 Implementation Spec — worker terminal diagnostics and bounded drain

Provenance: 2026-09-26; current HEAD `a4807632`; audit pin `37601232` verified as ancestor. Sources: cluster map, Wave-2 spec prompts, `verification-2026-09-22-wave2.md`, and current production/tests.

## GATE

Finding verification: both historical findings remain confirmed. `git diff 37601232..HEAD` is empty for `WorkerRunLogger.kt`, `WorkerLeaseRegistryImpl.kt`, and the existing monotonic clock implementations. Their target functions were reread at current HEAD. This reuses the historical independent finding verification; it does not claim a new independent implementation review.

Implementation: WI-1 and WI-2 authored, with eight new regression tests and required constructor-fixture adaptations. WI-3 needs no duplicate production edit. Static author self-review is recorded in `CL-23-self-review.md`; independent review/guardian approval and live validation remain pending. The cluster is not closed.

## Context for the coder

Workers keep a durable terminal run record and can fall back to a bounded diagnostic when the terminal database write fails. Backup/restore asks active workers to drain before proceeding. The former currently misattributes persistence failures to the worker exception; the latter measures its budget against a wall clock.

## Pre-implementation checks

1. Verify the scoped files have no pre-existing uncommitted changes.
2. Inspect `WorkerRunLoggerImpl.Handle.terminal` / `TerminalResult.toOutcome`, all six terminal callers, and `WorkerLeaseRegistryImpl.awaitNoActiveWorkers`.
3. Enumerate direct registry-constructor callers and update only fixtures affected by the injected clock type.
4. Preserve existing Hilt clock bindings in `di/TimeModule.kt`; no new clock abstraction is needed.

## Legal path constraints

- Follow `LEGAL_PATHS.md` Workers / Background Jobs and Diagnostics sections; segment 12 owns this work.
- Keep WorkerExecutionGuard, DatabaseWriteBarrier, terminal-write CAS, lease acquisition/stop locking, and maintenance timeout policy intact.
- Do not add direct DAO writers or alter backup/restore state transitions. No Room schema change is needed.

## Work items

### WI-1 — attribute terminal persistence failure correctly (CA-P-09-002, P2, CONFIRMED)

- Location: `domain/workers/WorkerRunLogger.kt`, `TerminalResult.toOutcome`, current lines 168-184; terminal callers at 292, 303, 316, 329, 341, 352.
- Remove the separate worker-error argument from the private outcome mapper and all its callers.
- For `NotDurableFailure`, derive `errorClass` from that result's own stored exception. Preserve terminal status, controlled reason code, and failure code.
- Do not change the separate worker-error attribution in the attempted database terminal record.
- Preserve timeout and zero-affected classifications, cancellation propagation, and the ability to retry a failed terminal write.
- Acceptance: failed persistence reports SQLException even when the worker succeeded or failed with a different exception; no exception message/path/financial payload enters the fallback outcome.

### WI-2 — use elapsed time for drain budgets (CA-P-09-003, P2, CONFIRMED)

- Location: `domain/workers/WorkerLeaseRegistryImpl.kt`, constructor and `awaitNoActiveWorkers`, current lines 15-18 and 60-70.
- Replace the wall-clock dependency with existing `domain/util/MonotonicTimeProvider`. Its `nowNanos()` values are meaningful as differences; `TimeModule` already binds the implementation.
- Compute elapsed time from a starting monotonic reading rather than adding an absolute deadline. Avoid deadline-addition overflow.
- Bound each polling delay by the remaining budget and the existing 50 ms polling interval.
- Empty registry drains immediately. Active registry with a non-positive budget fails immediately. Cancellation during the drain propagates; a timed-out drain must not release another worker's lease.
- The registry persists no timestamps; leave `WorkerRunLogger` and other wall-clock timestamp users unchanged.

### WI-3 — adjudicate A1 retention site (already fixed; no production edit)

`data/privacy/DataRetentionWorker.kt` current catch sequence at lines 438-446 rethrows CancellationException. Retain that landed fix; do not rewrite the worker merely to produce a diff. The transaction site belongs to CL-09 and startup site to CL-15.

## Tests

- `WorkerRunLoggerTest`: correct the existing retry attribution expectation; add successful-worker/failed-persistence and failed-worker/different-persistence-exception cases. Keep timeout, durable success, zero-affected, duplicate-terminal, cancellation, and retryability coverage.
- `WorkerLeaseRegistryTest`: use a scheduler-backed monotonic test clock. Cover forward/backward wall-clock changes, exact timeout, release before timeout, zero/negative budget, cancellation, stop/late-acquire behavior, and an elapsed reading across the signed counter boundary without deadline-addition overflow.
- Existing integration regressions to execute: `WorkerExecutionGuardTest`, `MaintenanceOperationRunnerTest`, and `DataRetentionWorkerTest`; constructor-only fixture updates are allowed if discovery identifies them.

## Validation

Human-run policy from `stage3-wave2-coder-prompts.md` remains in effect unless explicitly changed. Prepare targeted-unit-test runner commands for the classes above, then compile. Tests added or read are not tests executed. No direct Gradle or guard invocation.

## Blast-radius fence

ALLOWED: the two named production files, their two named unit-test files, direct-constructor test fixtures requiring clock adaptation, and these campaign execution/spec records.

FORBIDDEN: clock implementation/binding redesign; RestoreMaintenanceMode debt repairs; database entities/migrations; privacy-policy changes; terminal diagnostic sink format changes; unrelated worker logging cleanup; baselines/allowlists.

## Review gate

Architecture focus: dependency injection, timeout units and overflow, cancellation, polling bounds, lease ownership, and actual persistence-exception attribution. Perform and label a self-review; do not represent it as an independent architecture-guardian approval.

## Privacy and worker constraints

Reuse controlled terminal failure codes. Emit exception class only in fallback outcomes. Preserve WorkerExecutionGuard semantics, retryability, cancellation propagation, and the separation of worker outcome from persistence outcome.

## Out of scope

CL-09 stale writes; CL-15 unavailable semantics; CL-21 cloud policy; CL-22 guard changes. Broader static-guard and suite debt remain recorded separately.
