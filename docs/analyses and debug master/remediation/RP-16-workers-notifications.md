# RP-16 — Worker observability and notification identity

> **Mode:** strict (workers). **Status:** CONDITIONAL — counter persistence and checkpoint compatibility are blocking decisions.

## Invariants

Preserve WorkerExecutionGuard, barriers, idempotency, cancellation propagation, sanitized diagnostics, and metrics only after actual delivery. Retention cleanup is never gated by the retention capability it enforces.

## 16-A — Typed notification identity

Use `NotificationIdGenerator.forBill(...)` in BillReminderWorker. Establish a typed allocation boundary (`NotificationId`/`NotificationKey`) accepted by AndroidNotificationService, or notification-kind-specific service methods whose IDs can only originate from the generator. A source scan is supplementary only.

Tests: all reserved ranges; cross-kind collision separation; negative/large IDs and floor-mod wrap; helper-mediated posting cannot pass raw IDs; bill delivery metric increments only after actual delivery.

## 16-B — Terminal ledger counters

Add immutable `WorkerRunCounters(rowsScanned, rowsUpdated, notificationsSent, rowsSkipped, errors, partialFailureCount, failedTargetCount)` and `WorkerRunContext.snapshot()`. Capture snapshot immediately before success, retry, failure, cancellation, stale abort, and blocked/skipped terminal persistence under the terminal mutex.

Extend terminal APIs with optional snapshot/defaults for old callers. Map only scalar arguments to DAO columns—never `COALESCE(:counters, 0)` or a Room data object.

`rowsSkipped` and `errors` are not durable columns today. Decide explicitly before code: add columns with Room version, migration, snapshots and migration tests, or retain them exclusively in diagnostics/in-memory NO_WORK logic. Non-null snapshot means measured; null partial/failed means unknown/not supplied; zero means measured zero. Terminal-write failure leaves a safe non-durable state/diagnostic, never false completion. Start-run errors: transient retries; permanent errors map to controlled failure/blocked, not endless retry.

Tests: success, retry after partial work, permanent failure, cancellation, stale abort, old defaults, null/zero compatibility, terminal-write failure, and NO_WORK including skipped/errors.

## 16-C — Retention checkpoint state machine

Replace boolean `completed_<target>`/ignored failed-key semantics with versioned per-target records: `PENDING`, `COMPLETED(rowsPurged, auditEmitted)`, and `FAILED(controlledErrorCode, transient, attemptMetadata)`. Read legacy booleans compatibly before writing v2; never reinterpret booleans as counts.

Define resumed states, permanent-failure scheduled-run policy, exactly-once audit identity, and cleanup only when all desired states/audits are durable. No blanket preferences clear. Feed partial/failed target counts into the chosen ledger contract.

Tests: legacy keys; purge→checkpoint failure; checkpoint→audit crash; cancellation; transient/permanent failure; rerun; exactly-once audit; no payload/error-message leakage.

## 16-D — Bounded repairs

Add `nextAttemptAt` predicate to notification intake claims. Return an unexpectedly absent claimed warranty item to retryable/failed state. Add required specVersions. NO_WORK includes skipped/errors; BillReminder counts scanned work; ReceiptMatching counts only `DELIVERED` notification results and records controlled non-delivery error/suppression.

## Gates and completion

Order: 16-A → 16-B schema decision/API → 16-C → 16-D. Any Room change requires version, migration, snapshot, and migration test. Run focused worker tests when approved; strict worker/architecture review is required after each risky batch.
