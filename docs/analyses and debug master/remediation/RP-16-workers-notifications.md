# RP-16 — Worker observability & notification identity (Pipeline 9)

> **Scope class:** universal engine — `WorkerExecutionGuard`/`WorkerRunLogger` serve all 9+ workers. **Mode:** strict (workers).
> **Files:** `domain/workers/WorkerExecutionGuard.kt`, `WorkerRunLogger.kt`, `WorkerRunContext.kt`, `WorkerSpecScheduler.kt`, `WorkerSpec.kt`; `domain/util/NotificationIdGenerator.kt`; `service/reminder/BillReminderWorker.kt`; `service/receiptmatching/ReceiptMatchingWorker.kt` ⟂; `service/warranty/WarrantyExpirationWorker.kt`; `worker/NotificationIntakeWorker.kt` + `data/database/dao/NotificationIntakeDao.kt`; `data/privacy/DataRetentionWorker.kt` (P9-004 half); tests: `WorkerExecutionGuardTest`, `WorkerRunLoggerTest`, `P9RemainingWorkerFixesTest`, `BillReminderWorkerTest`, `WorkerSpecSchedulerTest`.
> **PR shape:** 3 PRs — (16a) notification identity = P9-001 (D6); (16b) ledger truth = P9-002 + P9-003 + P9-004 + P9-007; (16c) smalls = P9-005 + P9-006 + P9-008 + P9-009.

---

## 16a — Notification identity (P9-001, gated by D6 approve)

**Problem.** `BillReminderWorker.kt:247` posts with raw `(delivery.id % Int.MAX_VALUE).toInt()` (tag=null); receipt alerts post `NotificationIdGenerator.forReceipt(receipt.id)` = `20000 + floorMod(id, 9999)` (tag=null, `AndroidNotificationService.kt:84`). Android notification identity is `(tag, id)` — channels irrelevant → `delivery.id = 20450` silently **replaces** the receipt alert 20450 (and ids 1-9999 / 10000-19999 invade the budget/warranty ranges). `NotificationIdGenerator.forBill` (30000+) exists with zero callers. (Snooze/dismiss PendingIntent sub-concern was refuted — different receivers make `filterEquals` false; no action needed there.)

**Fix design.**
1. `BillReminderWorker:247`: `val notificationId = NotificationIdGenerator.forBill(delivery.id)` — extend the generator: BILL range 30000–39999 gives 9999 slots via `30000 + floorMod(deliveryId, 9999)` (same wrap semantics as `forReceipt` — replacement *within* bills is the historical id-reuse behavior, acceptable).
2. Architecture guard (new, small): test asserting every `notify(` / `NotificationManagerCompat.notify(` call site in main sources passes an ID obtained from `NotificationIdGenerator` (grep-style source scan like the existing guard tests; allowlist the generator itself). Prevents the next worker from reintroducing raw ids.
3. Post-fix hygiene: any currently-posted bill notification from the old scheme simply never gets updated again (stale id) — they age out; no migration needed (note in PR).

**What it solves.** Cross-type notification replacement eliminated; ID allocation centralized and guarded.

**Guardrails.** Notification-permission pattern in the worker (check + SecurityException catch + metric-after-post) untouched. **Tests:** generator range/wrap test; guard test fixture (raw-id call site → flagged); `BillReminderWorkerTest` updated where it pinned the raw id.

## 16b — Run-ledger truth

### P9-002 — Counters dropped on non-success terminals (MED)

**Problem.** Only `run.success(...)` forwards `ctx` counters (`WorkerExecutionGuard.kt:363-370`); `retry/failure/cancelled` signatures accept none (`WorkerRunLogger.kt:46-48`) and `TerminalArgs` defaults counters to 0 (`:173-186`) → a run that updated 500 rows then failed records `rowsUpdated = 0`.

**Fix design.**
1. Extend `WorkerRunHandle.retry/failure/cancelled` with an optional `counters: WorkerCounters?` param (data class snapshotting the three `AtomicInteger`s — add `WorkerRunContext.snapshot()`); `TerminalArgs` gains the same nullable field defaulting to null → SQL writes `COALESCE(:counters, 0)` so existing callers are unchanged.
2. Guard's ctx-variant terminal paths (`:416-427`, `:381-388`) pass `ctx.snapshot()` on every terminal, success included (success currently reads fields directly — unify through the snapshot).
3. `WorkerRunLogger.terminal()` plumbing: counters captured **before** the durable write (mutex held) so the row reflects the moment of terminal decision.

**What it solves.** The ledger reports partial work on retries/failures/cancellations — observability the P9 wave built the ledger for.

**Guardrails.** DB columns already exist (`BackgroundJobRunDao.kt:90-92`) — no schema change; nullable handling keeps old rows' 0s meaningful as "unknown/pre-fix". **Tests:** `WorkerRunLoggerTest`: retry-with-40-rows fixture → row shows 40; `WorkerExecutionGuardTest` counters-on-cancel fixture.

### P9-003 — Permanent run-insert failure retries forever (MED)

**Problem.** `startRunSafely`'s generic catch (`:574-580`) returns `StartRunResult.Retry` in **both** branches (`classifyTransient` true/false) → e.g. `SQLiteFullException` retries every backoff cycle indefinitely, no run row, no FAILED terminal (per-attempt `recordBlockedOperation` diagnostics do fire).

**Fix design.** Split the branches: transient → `Retry(WORKER_TRANSIENT_ERROR)` (unchanged); non-transient → return `StartRunResult.Blocked`-family → map to `Result.failure()` at the worker boundary (`toWorkerResult` already maps Blocked → failure/SKIPPED per policy — reuse the failure mapping; a run row was never inserted so nothing to finalize, and the blocked-op diagnostic carries the reason). **Guardrail:** do not introduce a retry cap here (WorkManager's own stop/backoff governs); we only stop *guaranteed-pointless* retries. **Tests:** fake DAO throwing `SQLiteFullException` on insert → guard returns failure (not retry); transient exception → retry (unchanged).

### P9-004 — Retention permanent failures masked as SUCCESS/NO_WORK (MED, with RP-15 note)

**Problem.** Only transient failures throw `RetryableWorkerException` (`DataRetentionWorker.kt:231-235`); permanent failures log-and-continue → guard SUCCESS; if all failing targets purged 0 rows → literally `NO_WORK`. Crash-resume **skips** audit events for pre-crash purges (`if (count > 0)` guard against absent results, `:195-205`); `_failed` checkpoint keys are never read and `clearCheckpoint` wipes them (`:266, :281, :298`).

**Fix design.**
1. Track and report partial failure in the run row: accumulate `permanentFailureCount` and pass via 16b's counters extension (add `partialFailures` to the snapshot/`TerminalArgs` — the columns `partialFailureCount`/`failedTargetCount` already exist unused, `WorkerRunLogger.kt:184-185`) → SUCCESS-with-failures is visibly distinct from clean SUCCESS and from NO_WORK.
2. Crash-resume audit: persist the purge count at checkpoint time (extend checkpoint prefs value: `"completed_<target>" → "<count>"`), and on resume emit the audit event with the **persisted** count for targets completed pre-crash (fixes the `count > 0`-vs-absent gap).
3. Delete the never-read `_failed` keys and `clearCheckpoint`'s unconditional wipe → clear only genuinely-completed targets (keeps failed marks for the next run's information; they're informational, not reprocessing gates — verified idempotent purges make reprocessing safe anyway).
4. `DataRetentionWorkerTest:206` re-pin (REVAL-7): permanent failure → `Result.success()` **with** `partialFailureCount > 0` in the ledger (keep success semantics per RP-15's reasoning; visibility, not retries).

**Guardrails.** Checkpoint prefs values are counts/target names — no PII. **Tests:** permanent-failure fixture → ledger shows count; crash-resume → audit event carries pre-crash count.

### P9-007 — NO_WORK mislabeling + DeliveryResult ignored (LOW)

**Problem.** NO_WORK predicate (`WorkerExecutionGuard.kt:361`) excludes `rowsSkipped`/`errors`; `BillReminderWorker` never calls `addRowsScanned` (quiet-hours skip / all-claims-lost runs read as NO_WORK); `ReceiptMatchingWorker.kt:103-108` increments `notificationsSent` without checking `DeliveryResult` (contrast `WarrantyExpirationWorker.kt:203`).

**Fix design.**
1. Predicate: `noWork = rowsScanned == 0 && rowsUpdated == 0 && notificationsSent == 0 && rowsSkipped == 0 && errors == 0` (any activity = work).
2. `BillReminderWorker`: add `ctx.addRowsScanned(dueCount)` at claim time (and keep skip counters as-is) — quiet-hours and lost-claim runs become honest SKIP-flavored successes with counts.
3. `ReceiptMatchingWorker`: only increment when `DeliveryResult.DELIVERED` (mirror the warranty worker's check); NOT_DELIVERED increments `errors` or a suppressed-diagnostic event per its existing suppression-event convention.

**Tests.** Guard predicate fixture; bill-quiet-hours run → not NO_WORK; receipt-match NOT_DELIVERED → not counted sent.

## 16c — Smalls

### P9-005 — Intake backoff defeated by WorkManager retries (MED)

**Problem.** `claimForProcessing` (`NotificationIntakeDao.kt:88-99`) lacks a `nextAttemptAt` predicate; WM's 30s exponential re-runs the worker before the in-row backoff (30s→1h, `:441-447`) elapses → attempts burn at WM pace → premature `MAX_ATTEMPTS_EXCEEDED`.

**Fix design.** Add `AND (:nowMs >= COALESCE(nextAttemptAt, 0))` to the claim WHERE. The worker then finds nothing claimable → idempotent success (existing zero-claim semantics) → the *recovery scheduler* path (`getReadyForProcessing`, which already respects `nextAttemptAt`) re-enqueues at the right time. Verify the worker's zero-claim early-return doesn't mark the row processed (it doesn't — verified claim-based state machine). **Tests:** claim before/after `nextAttemptAt` fixtures.

### P9-006 — Warranty claim stranded on null getByKey (LOW, near-unreachable)

**Fix design.** `WarrantyExpirationWorker.kt:185-193`: on `getByKey == null` after a successful claim, call `markFailedFromClaimed` by the known key fields (window/expiry are in scope) instead of returning silently — the row returns to retryable state immediately rather than waiting for the daily stale sweep. **Tests:** fake DAO getByKey-null → markFailed invoked.

### P9-008 — Four workers omit `specVersion` (LOW)

**Fix design.** Add `specVersion = WorkerSpec.DEFAULTS[...].version` to `WorkerGuardRequest` in `WarrantyExpirationWorker`, `LocationBackfillWorker`, `MerchantKeyBackfillWorker`, `ReceiptMatchingWorker` (the three passers show the pattern; intake's deliberate-null has its documented reason — leave). **Tests:** none beyond compile + existing guard tests (column no longer NULL).

### P9-009 — Scheduler doc/log claims non-existent UPDATE policy (LOW)

**Fix design.** Correct the comments/log strings at `WorkerSpecScheduler.kt:127-141, :242-256` and the `WorkerSpec.kt:26` KDoc to say REPLACE (the behavior, test-locked in `WorkerSpecSchedulerTest:137-171` — behavior intentionally unchanged; this is a truthfulness fix). **Tests:** none (doc-only; the existing test already pins reality).

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*WorkerExecutionGuard*" --tests "*WorkerRunLogger*" --tests "*BillReminderWorker*" --tests "*ReceiptMatchingWorker*" --tests "*NotificationIntakeWorker*" --tests "*DataRetentionWorker*" --tests "*WorkerSpecScheduler*" --tests "*NotificationIdGenerator*"
```

## Sequencing & risk
- 16a independent (D6 approval first). 16b is engine-wide but signature-additive (optional params). 16c mechanical. Risk: low-medium; the ledger changes touch the terminal write path — covered by the existing guard/logger test families plus new fixtures.
