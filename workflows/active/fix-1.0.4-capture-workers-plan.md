# Plan: fix-1.0.4-capture-workers (batch C)

Written by feature-planner (read-only, Sonnet) on 2026-11-03 at cbebaf4b; persisted by the orchestrator. Decisions are taken under owner item 116.

Paths are relative to `app/src/main/java/com/yourname/expensetracker/`. Evidence: `build/review-wt/build/full-review-2026-11/` (13b, 13c, 08-FINAL-REPORT sections 6.6, 6.7).

**Scope limit.** The other session owns money display (B1), neutralisation (B2), DR-01..08 (B3) and BL-04..11 (B4). No file here overlaps them. The only shared-file risk is `config/baselines/cancellation.json`, if another lane also shrinks it. (U) = not verified by the planner.

## Re-anchor (at cbebaf4b)
| Finding | State | Anchor | Evidence |
|---|---|---|---|
| P-01-1 | STILL-PRESENT, harm reduced | `domain/notification/capture/NotificationIntakeCoordinator.kt:216-217,487-488`; `NotificationIntakeRecoveryScheduler.kt:71-72` | Two unique names for one row. `NotificationIntakeWorker.kt:102-114` claims atomically, so a second worker no-ops. Paging part demoted: `ORDER BY capturedAt ASC LIMIT 100`, later starts drain the rest. |
| P-01-2 | CHANGED: retry claim REFUTED; unguarded encrypt STILL-PRESENT | `worker/NotificationIntakeWorker.kt:97,229,329,386,428`; `WorkerExecutionGuard.kt:694-706`; Coordinator:149 | No thrown code matches `classifyTransient`. `crypto.encrypt` at Coordinator:149 is unguarded, while `captureForRetry` guards it at :350-370. `service/NotificationCaptureService.kt:755-795` maps the failure to UNKNOWN_ERROR and releases the dedupe key. |
| P-01-3 | STILL-PRESENT | `service/NotificationCaptureService.kt:164,458,463,596,613` | Unbounded launch before dedupe and filter. (U) `NotificationCaptureGate` not opened to confirm no package allowlist. |
| P-09-2 | STILL-PRESENT | `domain/debug/DiagnosticsRepository.kt:57,58,60,61,62,66,85,98,111,124,145`; `config/baselines/cancellation.json:33,72` | 11 raw `runCatching` in suspend functions. `CancellationSafe.runCatchingCancellable` exists (`domain/util/CancellationSafe.kt:31`). |
| P-09-3 | STILL-PRESENT, low impact | `domain/workers/WorkerExecutionGuard.kt:346-350`, :353 to `startRunSafely` :615 | Lease before barrier in `runGuardedWithContext`. `finally` releases it; the final result is the same WORKER_WRITE_BARRIER_DENIED. |
| RA-FX-1 / N-07-1 | STILL-PRESENT | `data/repository/CurrencyRatesRepositoryImpl.kt:111-115` | `refresh()` lacks `instanceFollowRedirects=false` and `useCaches=false`; history path has them at :207-208. |
| N-07-2 | STILL-PRESENT | same file :118-126, :548-552 | No `contentLengthLong` pre-check, no future-date bound. Fixed-depth walk demoted by the 256 KB cap. |
| N-07-3 | DEMOTED | same file :96, :120 | Callers wrap and map exceptions. Only the bare gate call at :96 is a real gap. |
| N-07-4 | STILL-PRESENT | same file :94, :626-638 | No single-flight lock; blocking read with no cancellation check or disconnect on cancel. |

## Decisions (orchestrator, owner item 116)
1. **P-01-1 name:** `notification-intake-<id>` everywhere; do not cancel legacy work. Keyset paging deferred (could grow `db_raw_query_classification.yml`).
2. **P-01-2:** latent only. Guard the encrypt call and add a pin test. A non-retryable exception type is deferred (it changes `classifyTransient`).
3. **P-01-3:** a semaphore with no drops, because a drop would lose financial notifications. Drop channel and package allowlist deferred.
4. **P-09-2:** shrink the baseline by hand, exactly two lines. This only removes entries, never adds; static-guards proves it. `generate_baselines.py` is not run because it rewrites the whole file. Accepted: `runCatchingCancellable` catches `Exception`, so `Error` now propagates.
5. **P-09-3:** keep the WRITE_BARRIER_DENIED reason code.
6. **RA-FX-1:** no new outcome variant, which would force edits to 3 view models and `strings.xml`. DOM walk, N-07-3 exception reshaping and N-07-5 (a guard change) deferred.

## Slices
Disjoint write sets, no dependencies. Order: S4, S1, S2, then S3, S5.

### Batch A
- **S4. P-09-2. T1.** `domain/debug/DiagnosticsRepository.kt`, `config/baselines/cancellation.json`.
  - Replace the 11 blocks with `runCatchingCancellable`, same defaults, same public API. Remove the two baseline lines, :33 and :72.
  - Tests, same writer, new `DiagnosticsRepositoryImplTest`: at each of the 6 + 5 call sites a cancelled DAO call rethrows; a non-cancellation failure still gives the empty default and does not hide other sources.
- **S1. P-09-3. T2, worker path for all 12 workers.** `domain/workers/WorkerExecutionGuard.kt` only.
  - In the NORMAL-mode path of `runGuardedWithContext`, before `leaseRegistry.acquire`, add the `requiresDatabaseWrite` barrier check, keeping `MaintenanceBlockedReason.WRITE_BARRIER_DENIED` and `applyBlockedPolicy(WORKER_WRITE_BARRIER_DENIED)`. Do not touch `classifyTransient`.
  - Tests, separate test-author, `WorkerExecutionGuardTest`: denied means no lease, no run row, BlockedRetry with WORKER_WRITE_BARRIER_DENIED and one blocked diagnostic; allowed is unchanged; cancellation propagates; `runGuarded` and `runGuardedWithContext` agree on denial.
- **S2. P-01-1, P-01-2. T2, privacy-adjacent.** `NotificationIntakeCoordinator.kt`, `NotificationIntakeRecoveryScheduler.kt`, new `domain/notification/capture/NotificationIntakeWorkNames.kt`. Worker file unchanged.
  - One name function at all three enqueue sites. Wrap `crypto.encrypt` in `capture()` as `captureForRetry` does: rethrow cancellation, bounded `DEFERRED_TRANSIENT_ENCRYPT_FAILED` diagnostic, `StorageFailure`, no row.
  - Upgrade: legacy work named `<id>` is neither cancelled nor renamed. Recovery may enqueue a second worker for a row that already has legacy work; the claim makes it a no-op or an existing backoff retry. Code comment plus a test.
  - Guard trap: no `.delete()` or `.insert*` on untyped receivers.
  - Tests, separate test-author: name parity on a fake WorkManager; encrypt failure gives `StorageFailure`, no row, cancellation rethrown; a legacy-name duplicate no-ops when the claim returns 0; a pin that every failure code the worker throws is non-transient.

### Batch B
- **S3. P-01-3. T2.** `service/NotificationCaptureService.kt`, new `service/NotificationCaptureConcurrencyLimiter.kt` (Android-free, wraps `Semaphore`).
  - RECEIVED diagnostic first; gate, dedupe, filter and capture under `withPermit` (about 4 permits). Nothing dropped. NonCancellable region unchanged.
  - Tests, separate test-author: peak concurrency bounded; cancellation releases the permit; existing `NotificationCaptureService*Test` stays green.
- **S5. RA-FX-1. T2, network.** `data/repository/CurrencyRatesRepositoryImpl.kt` only.
  - `instanceFollowRedirects=false`, `useCaches=false`; oversized `contentLengthLong` gives RATE_PAYLOAD_TOO_LARGE; publication date after today + 1 UTC (injected `timeProvider`) gives new `EcbPublicationDateFailureReason.OUT_OF_BOUNDS`; a thrown gate gives `PrivacyCheckFailed(PRIVACY_BLOCKED)`, cancellation first; `Mutex.withLock`; `coroutineScope` + `async` + `disconnect()` in `finally`, mirroring the history path. `parseEcbDailyRates(stream)` stays non-suspend. A `dailyConnectionOpener` seam with the same default URL. No new host, no new permission.
  - Tests, separate test-author, `CurrencyRatesRepositoryImplTest`: flags set; a 3xx fails without following `Location`; oversized length fails before reading; future date rejected; gate throw; two concurrent refreshes open one connection at a time; cancellation disconnects; existing fixtures pass.

## Validation (validation-runner only, through the queue)
- Batch A filter: `DiagnosticsRepositoryImplTest`, `WorkerExecutionGuardTest`, `NotificationIntakeCoordinatorTest`, new recovery-scheduler and work-name tests, `NotificationIntakeWorkerTimeoutTest`.
- Batch B filter: limiter test, `NotificationCaptureService*Test`, `CurrencyRatesRepositoryImplTest`, `CurrencyConverterTest`.
- `static-guards` once after both batches; `app-check` once per candidate. No schema change, so no migration tests.
- Combined guardian per lane; `reviewer-strict` at the candidate.

## Deferred, recorded
Keyset paging; non-retryable intake exception type; capture drop channel and package allowlist; fixed-depth DOM walk; N-07-3 reshaping; N-07-5 static DTD guard. **For the owner:** `messageSafe = exceptionMessage` in `DiagnosticsRepository` raises a bounded-reason-code question outside these findings.

## Status
| Slice | State |
|---|---|
| S4, S1, S2, S3, S5 | merged into candidate 7c14da67; reviewer-strict ACCEPT-WITH-MINORS |

## Notes during Batch A
- S2: only `capture()` used the bare `<id>` name; the other two sites already used `notification-intake-<id>`. All sites use `KEEP`, so a collision cannot cancel a running worker.
- S2, pre-existing and not fixed: an encrypt failure in `capture()` loses that notification, because no row exists for recovery and the dedupe key is released; only a re-post by the source app brings it back. The fix gives this an honest bounded diagnostic instead of UNKNOWN_ERROR.
- S1: an unexpected exception from the first barrier check now counts as a denial (BlockedRetry), matching `runGuarded`; it used to take the plain retry path. The diagnostic reason differs from `runGuarded` by design (decision 5).

## S3 accepted residual (orchestrator, item 116; wording corrected after reviewer-strict)
The limiter bounds the capture pipelines only. Coroutines, each retained notification object and the RECEIVED emission stay unbounded.
If the service shuts down while notifications wait for a permit, those are lost; each leaves one `CAPTURE_CANCELLED` event, and a listener refresh is not automatic. This does not need a burst alone: four stalled permit holders would let ordinary traffic queue, and shutdown then drops that backlog, whereas the old code's work was already inside NonCancellable. Permit holders have no timeout on `intakeCoordinator.capture` or DO_NOT_STORE `processAndSave`; gate calls time out at 300 ms. Accepted because reaching this takes both a stall and a shutdown, and because the limit stops a flood running unbounded pipelines.

## S5 known limit
A device clock more than a day behind the ECB publication date makes a valid file fail the refresh (`OUT_OF_BOUNDS`, status FAILED), with no user-visible reason, so rates go stale on that device. Pinned by a test; reviewer-strict to judge. S5 test gap: nothing deterministically proves the second refresh waits on the mutex.

## reviewer-strict on 7c14da67: ACCEPT-WITH-MINORS
Follow-ups, not fixed before merge so the running gates stay valid:
- `WorkerExecutionGuard.kt:354` records the captured `mode`, which always reads NORMAL on this branch; pass `restoreMaintenanceMode.currentMode()`. `runGuarded` :148 has the same issue already.
- `NotificationCaptureService.kt:608`: the dedupe window starts after the permit wait. Two reposts up to 5 s apart can both pass if permits stall past 5 s, which adds an intake row. Canonical checks (`NotificationProcessingPipeline.kt:442-466`) should stop a duplicate expense; not fully traced. Take the arrival time before the permit.
- Also deferred: RA-FX-1 per-chunk `ensureActive` (disconnect on cancel looks an adequate substitute); N-07-4's lock is not shared with the history path, so a refresh and a history download can still interleave.
- Not checked by anyone: a live ECB response. One real refresh on the emulator before release.
