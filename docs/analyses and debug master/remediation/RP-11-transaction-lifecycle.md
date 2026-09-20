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

## 11a implementation status (lane worktree rp-11-wip)

> **Status:** implemented, validation NOT RUN — coordinator/repository/test edits are in
> this worktree; no Gradle or static-guard suite has executed against them yet.

Implemented per the orchestrator-approved re-scope (single coordinator delete overload,
no coordinator id-overload added):

- **P2-001** — `deleteExpense(expense)` returns an internal `ExpenseDeleteOutcome`
  (`Deleted`/`NotFound`) from the transaction. Only `Deleted` writes the DELETED event,
  plans side effects, and returns `Result.success`; `NotFound` returns
  `Result.failure(IllegalArgumentException("Expense not found: <id>"))` with no
  planner/dispatcher call. `ExpenseRepository.deleteExpense(id)` now routes through the
  coordinator (no pre-check read; TOCTOU-safe transactional re-read produces the same
  `IllegalArgumentException` failure type as before).
- **P2-005** — `updateCategory`, `updateLocation`, `updateMerchant`, `updateType`,
  `updateTransferDetails`, `updateTypeAndTransferDetails` each produce an internal
  `ExpenseUpdateOutcome` (`Updated`/`NoChange`/`NotFound`) from the database transaction.
  Only `Updated` writes the UPDATED event and dispatches post-commit side effects
  (`updateLocation` keeps its intentional no-side-effect contract). `NoChange` is a
  silent idempotent success; `NotFound` throws `IllegalArgumentException` (same failure
  pattern as `updateBusinessExpensePatch`'s typed result family). Public signatures
  unchanged. `updateTypeAndTransferDetails` now runs the dedupe-key collision check
  whenever the recomputed key differs from the stored key, even if the type is unchanged.
- **NEW-P2-005 not touched** — `DefaultExpenseCategoryAssignmentService`'s distinct
  `EXPENSE_CATEGORY_ASSIGNED` event is preserved for the later batch.
- Tests: typed-outcome matrix added in
  `TransactionLifecycleCoordinatorUpdateTest` (missing/unchanged/changed per method,
  merchant collision, stale-key collision for the combined method, event count/type +
  planner-count assertions), delete present/missing cases in
  `TransactionLifecycleCoordinatorTest`, and a DB-contract missing-row delete case in
  `TransactionLifecycleCoordinatorDbContractTest`. Golden test is DAO-direct and needed
  no pin updates.

## 11b implementation status (lane worktree rp-11-wip)

> **Slice B2 status (2026-09-19) — implemented, validation NOT RUN** — coordinator/pipeline/DAO/
> repository/ViewModel/test edits are in this worktree; no Gradle or static-guard suite has
> executed against them yet.

- **P2-002** — `resolveExistingIdAfterInsertConflict` now resolves in identity order
  rawNotificationId (new `ExpenseDao.findIdByRawNotificationId`, read-only on the EXISTING unique
  index — no new index) → dedupeKey → bounded fuzzy window (STANDARD/BULK_IMPORT only;
  STRICT_EXTERNAL_ID and SKIP_FOR_DEBUG_RESTORE never fuzzy). Returns the resolved id paired with a
  controlled resolution code. `CreateExpenseResult.InsertConflict` gained
  `reasonCode: String = InsertConflictCodes.UNRESOLVED` (default preserves prior behavior; codes
  `CREATE_INSERT_CONFLICT_RESOLVED_{RAW_NOTIFICATION_ID,DEDUPE_KEY,FUZZY_WINDOW}` /
  `CREATE_INSERT_CONFLICT_UNRESOLVED`). A resolved conflict still returns `DuplicateSkipped` with
  the proven existing id; an unresolved one returns `InsertConflict` with the attempted key and the
  UNRESOLVED code — callers never guess an expense id.
  In `NotificationProcessingPipeline.handleAutoAcceptInTransaction` the `check(...)` retry assertion
  was REMOVED: every InsertConflict is a terminal transition (`markRelevance(rawId,false)` +
  duplicate stats exactly once + dedupe source link + `markProcessed` inside the write barrier),
  and an UNRESOLVED conflict additionally writes a bounded diagnostic (controlled
  `DUPLICATE` reason code + hashed package name only — no raw text).
- **P2-003** — `bulkUpdateMerchant` is now atomic all-or-nothing returning `Result<Unit>`: it
  preflights every recomputed target dedupe key INSIDE the transaction before writing any row; a
  collision with an expense outside the rename set throws `DuplicateUpdateException` carrying the
  controlled constant `BulkMerchantRenameFailure.MERCHANT_RENAME_DUPLICATE`, so the transaction
  rolls back with no row change, no BULK_UPDATED event, and no post-commit dispatch. Success emits
  the existing one aggregate BULK_UPDATED event and dispatches the one aggregate batch; zero
  matching rows and same-merchant are successful no-ops. `ExpenseRepository.updateExpenseMerchantBulk`
  propagates via `getOrThrow()`; `updateExpenseMerchant(applyToAll=true)` aborts before the
  pending-review rename on failure (no half-renamed stores); `ReviewViewModel` surfaces a bounded
  conflict message (no raw exception text). Intra-set collisions (rows renamed onto each other)
  are allowed.
- **P2-004** — `CreateExpenseRequest.skipDeduplication` renamed to `skipPreflightDeduplication`
  (all 4 construction sites updated: NotificationProcessingPipeline, ReviewQueueRepository,
  LegacyDataMigrationService, coordinator). KDoc states the preflight-only contract: the nullable
  rawNotificationId and unique dedupeKey DB constraints are ALWAYS enforced; the flag only bypasses
  coordinator preflight checks; no random-UUID key rewrite. Pinned by tests asserting conflicts
  still surface as typed InsertConflict/DuplicateSkipped with the canonical key intact.
- **NEW-P2-005** — audit found ZERO listeners of `EXPENSE_CATEGORY_ASSIGNED` in main source.
  Minimal-diff option chosen: the port (`DefaultExpenseCategoryAssignmentService`) RETAINS its
  atomic update + exact `EXPENSE_CATEGORY_ASSIGNED` event (type/actor/payload unchanged — no
  listener compatibility risk, no coordinator event-writer op added), and the previously missing
  post-commit side effects were added via a coordinator-owned hook
  `TransactionLifecycleCoordinator.dispatchCategoryAssignmentSideEffects` (CATEGORY_ONLY plan —
  budget recheck + anomaly alert — via `runBestEffortAfterCommit`, best-effort, CE-rethrow
  preserved). Only a real `Assigned` outcome dispatches; Skipped/Failed dispatch nothing.
  P2-008's not-mine resolver guard was NOT co-located (non-trivial: shared blocking query) — left
  for 11c, documented as such in code comments and the DAO KDoc.
- Tests: new `TransactionLifecycleCoordinatorConflictResolutionTest` (14 tests: resolution order +
  codes incl. null-source-id, STRICT/SKIP_FOR_DEBUG_RESTORE never-fuzzy, fuzzy fallback, fully
  unresolved typed conflict, preflight-bypass conflict surfacing, repeated raw id, canonical-key
  no-UUID-rewrite, bulk all-success/zero-row/same-merchant/extra-set-collision/intra-set-collision);
  `CategoryAssignmentServiceBarrierTest` gained the coordinator constructor arg (+ withTransaction
  static-mock stub per the documented hang family) and 4 NEW-P2-005 tests (assigned→event+dispatch,
  already-categorized→no event/no dispatch, missing→no event/no dispatch, hook-failure
  containment); `ExpenseRepositoryTest`/`ExpenseRepositoryStressTest` stub the new `Result<Unit>`
  return explicitly (relaxed-mock value-class hazard). Known-broken
  `NotificationProcessingPipeline{Atomicity,Reliability,SourceLink}Test` files were NOT edited;
  pipeline behavior is pinned via the coordinator reason-code tests. Validation of the pipeline
  suites remains conditional per RP-21.

## Slice B3 status (2026-09-19) — implemented, validation NOT RUN

> **Slice B3 status (2026-09-19) — implemented, validation NOT RUN** — coordinator/DAO/repository/
> ViewModel/test edits are in this worktree; no Gradle or static-guard suite has executed against
> them yet.

- **P2-007** — `updateTransferDetails` and `updateTypeAndTransferDetails` gained an optional
  `correlationId: String? = null` (default preserves all other callers) threaded into BOTH the
  UPDATED TransactionEvent and the planned post-commit batch (`planUpdated` correlation slot,
  replacing the hard-coded `null`). `ExpenseRepository.updateTransferDetails` /
  `updateExpenseTypeAndTransfer` forward an optional `correlationId` (default null preserves
  other callers); `TransactionsViewModel.updateTransferDetails` generates a fresh
  `CorrelationIds.newId()` per call (scout verified: no pre-existing ViewModel correlation id).
  Tests pin that ONE id appears in both the event and the dispatch record for both methods.
- **P2-008** — the blocking dedupe query family (`existsByMerchantKeyInRangeCurrencyAware` and
  siblings, `isDuplicateCurrencyAware`) is now documented as intentionally INCLUDING `isNotMine`
  rows; the resolution/suggestion candidate family
  (`getDuplicateCandidateBy*InRangeCurrencyAware`) gained `AND isNotMine = 0` in SQL — the
  fuzzy resolver `findDuplicateIdCurrencyAware` can therefore NEVER return a not-mine row, and
  the import-suggestion helper `getDuplicateCandidateForImportCurrencyAware` (zero external
  callers today) inherits the same policy-consistent exclusion. Blocking precheck behavior is
  unchanged. The B2 "planned separately" comments in the coordinator were replaced with the
  implemented-policy wording. Regression tests pin blocking-includes-not-mine vs
  resolver-never-not-mine against the real Room DB, plus the resolver still resolving mine rows.
- **NEW-P2-016** — both lifecycle hot-path `homeCurrency().first()` reads were replaced with the
  typed `resolveHomeCurrency()` contract. `Resolved`/`FirstRunDefault` proceed exactly as before.
  `Failed` (incl. DataStore read timeout) fails CLOSED: no silent
  `CurrencyConverter.DEFAULT_BASE_CURRENCY` substitution, no fabricated `baseAmount`/rate —
  conversion fields are marked with the existing sentinel model (baseAmount 0.0 / baseCurrency ""
  / exchangeRateUsed 0.0, so downstream falls back to raw effectiveAmount) and ONE bounded
  best-effort diagnostic is written (`DiagnosticReasonCode.HOME_CURRENCY_UNAVAILABLE` +
  `HomeCurrencyFailureReason.HOME_CURRENCY_UNAVAILABLE` metadata; constant reason code only, no
  exception text; CE rethrow preserved in the emit helper). The mutation itself still completes
  (create/update succeed with marked-unavailable conversion fields) per the coordinator's
  existing conversion-failure result contract.
- Fixture audit: the three lifecycle coordinator test fixtures already stubbed
  `resolveHomeCurrency()` explicitly and needed no changes;
  `TransactionLifecycleCoordinatorDbContractTest` and `GroupTransactionCoordinatorTest` gained
  explicit `Resolved(EUR)` stubs (relaxed-mock sealed-return hazard);
  `TransactionTargetedUpdateSideEffectsTest` was audited and needs no stub (its paths never call
  `resolveHomeCurrency`); golden/e2e suites use real fakes or already-stub the resolver.
- Tests added: `updateTransferDetails correlation id appears in event and dispatch record`,
  `updateTypeAndTransferDetails correlation id appears in event and dispatch record`,
  `updateExpense with failed home currency resolution marks conversion fields unavailable`,
  `updateExpense with first-run default home currency pins identity base fields`,
  `createExpense with failed home currency resolution marks conversion fields unavailable`,
  `createExpense with never-emitting home currency settings completes bounded and fails closed`,
  `createExpense with resolved home currency pins converted base snapshot`,
  `createExpense with first-run default home currency pins identity base snapshot`,
  `insert conflict fuzzy resolver excludes not-mine rows and reports unresolved`,
  `blocking duplicate check includes not-mine rows while resolver excludes them`,
  `duplicate resolver still returns a matching mine row` (last two in
  `TransactionLifecycleCoordinatorDbContractTest`, real-Room regression).

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
