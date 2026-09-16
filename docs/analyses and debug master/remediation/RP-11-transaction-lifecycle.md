# RP-11 — Transaction lifecycle semantics

> **Status:** CONDITIONAL — executable after the contracts in this document are approved.
> **Mode:** strict (transaction lifecycle, deduplication, money, and audit events).
> **Depends on:** RP-01 for cancellation/error-boundary fixes in the coordinator.

## Scope and source contract

The owning path is `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`.
All expense create/update/delete callers, including notification auto-accept, receipt linking,
imports, and the transactions UI, must continue to use this coordinator. DAO writes are not
added to callers. Relevant supporting files are `Expense.kt`, `ExpenseDao`,
`CreateExpenseRequest`, `NotificationProcessingPipeline`, `DefaultExpenseCategoryAssignmentService`,
`CurrencySettingsRepository`, and the transaction lifecycle tests.

This plan does not change the `expenses` schema unless the explicit identity decision below is
approved. The entity currently has two independent unique indexes: nullable `rawNotificationId`
and `dedupeKey`. A change that addresses only one index is incomplete.

## 11a — Missing-row, no-op, and delete semantics

### P2-001 — Entity delete must report a missing row

The entity overload currently exits only the transaction lambda when the row is absent, then can
return success and run post-commit work. Make both delete overloads return the same internal typed
outcome (`Deleted` or `NotFound`) from the transaction. Only `Deleted` writes the `DELETED` event,
plans side effects, and returns `Result.success(Unit)`. `NotFound` returns the same failure type
and controlled message as the id overload. No planner/dispatcher call is allowed for `NotFound`.

Tests must cover both overloads for missing and present rows, asserting identical result, event,
and side-effect behavior.

### P2-005 — Update paths need explicit outcomes

`updateCategory`, `updateLocation`, `updateMerchant`, `updateType`, `updateTransferDetails`, and
`updateTypeAndTransferDetails` currently have inconsistent missing-row and unchanged-value behavior.
For each method, return an internal `Updated`, `NoChange`, or `NotFound` outcome from the database
transaction. Preserve the public signatures for this batch. Only `Updated` writes the lifecycle
event and dispatches post-commit work. `NoChange` is a successful idempotent no-op with no event;
`NotFound` is a typed failure with no event or dispatch. The combined type/transfer method must
also run the dedupe-key collision check whenever the recomputed key differs, even if the type is
unchanged.

Do not remove the distinct `EXPENSE_CATEGORY_ASSIGNED` event emitted by
`DefaultExpenseCategoryAssignmentService` in this batch. Its listener contract must first be
audited. If category assignment is later routed through the coordinator, preserve that event type
through the coordinator/event writer or make an explicit compatibility migration; a generic
`UPDATED` event is not an implicit replacement.

Tests cover every method's missing, unchanged, and changed cases, including event count/type,
planner count, and the stale-key collision case.

## 11b — Deduplication and source identity

### P2-002 — Resolve conflicts by the identity that actually conflicted

`insertAtomic(IGNORE)` can fail on either unique index, while the current resolver checks only the
dedupe key and a fuzzy window. Add an indexed lookup for `rawNotificationId` and resolve in this
order when the request has a source id: raw notification id, dedupe key, then (only in the existing
non-STRICT modes) the bounded fuzzy resolver. STRICT modes never use fuzzy matching. Record only a
controlled resolution code (`RAW_NOTIFICATION_ID`, `DEDUPE_KEY`, `FUZZY_WINDOW`, or
`UNRESOLVED`). Extend the existing `CreateExpenseResult.InsertConflict` with a controlled
`reasonCode` field (defaulting to the existing conflict code), and use
`reasonCode = CREATE_INSERT_CONFLICT_UNRESOLVED` when no identity can be proven. In
`NotificationProcessingPipeline`, map that result to the existing
`dao.markRelevance(rawId, false)` terminal transition, increment duplicate/conflict stats exactly
once, and write a bounded `CONFLICT_UNRESOLVED` diagnostic; remove the `check(...)` assertion so
there is no retry loop. Other callers receive the same typed result and must not guess an expense id.

### P2-003 — Bulk merchant rename contract must be explicit

`bulkUpdateMerchant` currently returns `Unit`, performs one transaction, and emits one aggregate
event. Select the **atomic all-or-nothing** contract for this remediation to preserve lifecycle
atomicity: preflight every target key inside the transaction; any collision aborts all changes,
writes no lifecycle event, dispatches no side effects, and returns `Result.failure` with the
controlled `MERCHANT_RENAME_DUPLICATE` reason. A successful operation returns `Result.success(Unit)`
and emits the existing one aggregate event. Update the bulk-edit caller to handle this result and
show a bounded conflict message; do not introduce per-row partial success in this plan. Add tests
for mixed collisions, all-success, zero-row/no-op, rollback, event count, and dispatch count.

### P2-004 — Define `skipDeduplication` against both unique indexes

The request KDoc currently says “skip deduplication entirely,” but the coordinator bypasses only
preflight checks. Select the **preflight-only** contract for this remediation: rename/document the
flag as `skipPreflightDeduplication`, retain the authoritative nullable `rawNotificationId` and
unique `dedupeKey` constraints, and allow the database to return a typed duplicate/conflict. Do not
generate a random UUID to disguise a source identity. A future “bypass all identity constraints”
feature would have to omit/null `rawNotificationId` or add a separate source identity with a Room
migration and audit review; it is out of scope here.

Tests cover repeated requests with the same dedupe key, the same raw notification id, both
identities, and a null source id, asserting the renamed flag's preflight-only behavior.

### NEW-P2-005 — Category-assignment lifecycle compatibility

The receipt-link category assignment port writes a distinct `EXPENSE_CATEGORY_ASSIGNED` event.
Before routing it through the coordinator, enumerate listeners and consumers. Either retain the
port's atomic event and add the missing side effect through a coordinator-owned post-commit hook,
or add a coordinator event-writer operation that preserves the exact event type and payload. The
change must not silently replace a consumed event with `UPDATED`. Test already-categorized,
assigned, missing-expense, and event/dispatch behavior.

## 11c — Correlation and currency availability

### P2-007 — Transfer edits carry caller correlation

Add an optional `correlationId` to `updateTransferDetails` and
`updateTypeAndTransferDetails`, pass the existing ViewModel correlation id, and use it for both the
transaction event and the planned side-effect batch. Defaulting to `null` preserves other callers.
Test that one id is present in both records.

### P2-008 — Keep blocking and resolution policies distinct

The blocking dedupe queries intentionally include `isNotMine` rows, while suggestions/resolution
exclude them. Preserve that policy, document it in DAO KDoc, and ensure the fuzzy resolver cannot
return a not-mine row. Add a regression test for blocking versus resolution behavior.

### NEW-P2-016 — Home currency timeout must fail closed

Replace the two lifecycle hot-path `homeCurrency().first()` reads with the existing typed
`resolveHomeCurrency()` contract. `Resolved` and `FirstRunDefault` may proceed. `Failed` (including
timeout) must return a typed unavailable/conversion-failed outcome: do not silently substitute the
default currency or persist a fabricated `baseAmount`/rate. Clear or mark conversion fields using
the existing model, write a controlled diagnostic, and let the caller surface the failure according
to the coordinator's existing result contract. Do not expand this batch to every repository-wide
`.first()` call.

Tests use a never-emitting settings fake and assert bounded completion, no invented currency, safe
conversion fields, and a controlled event/result.

## Required validation

Targeted tests (when approved; not run while editing this plan):

```text
*TransactionLifecycleCoordinator*
*DuplicateLogicConsistency*
*NotificationProcessingPipeline*
*CurrencySettingsRepository*
```

Static review must verify both unique indexes, event compatibility, lifecycle-only DAO access,
and that no cancellation or raw diagnostic text is swallowed. Sequence implementation as
`RP-01 → 11a → 11b → 11c`; coordinate identity decisions with RP-10 and statement import paths.
