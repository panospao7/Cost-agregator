# Pipeline 2 Debugging Report — Transaction Lifecycle

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static code review, not local/device execution.

## 1. Executive summary

Pipeline 2 is the central expense lifecycle:

```text
manual / notification / review / receipt / group / bank / email / import
→ TransactionLifecycleCoordinator
→ ExpenseDao
→ TransactionEventDao
→ side effects
→ budget / anomaly / merchant learning / recurring link
→ dashboard / analytics / forecast
```

The design is good: there is a single lifecycle coordinator, source tracking, dedupe modes, event logging, restore-mode write blocking, and post-create side effects.

But the implementation currently has several high-risk seams.

The most important issue:

> Several callers wrap `TransactionLifecycleCoordinator.createExpense()` inside their own `database.withTransaction { ... }`. The coordinator then runs “post-commit” side effects after its inner transaction, but if the caller’s outer transaction is still active, those side effects are **not truly post-commit**.

This can cause budget/anomaly/merchant-learning/recurring side effects to run for data that may later roll back.

Second big issue:

> Many update paths still mutate `ExpenseDao` directly and do not write `TransactionEvent.UPDATED`.

So the create path is mostly centralized, but the update lifecycle is still partially bypassed.

---

# 2. Intended architecture contract

From the dependency map, the transaction lifecycle is supposed to be:

```text
ALL expense creation paths
→ TransactionLifecycleCoordinator
→ validate
→ normalize
→ dedupe
→ insert atomic
→ transaction event
→ side effects
```

Main consumers:

- `ManualExpenseRepository`
- `ReviewQueueRepository`
- `ReceiptRepository`
- `ExpenseRepository`
- `GroupTransactionCoordinator`
- `EmailReceiptIngestionService`
- `BankApiIntegration`

This is the correct architecture. The coordinator should be the only place where expense CUD writes become official business transactions.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

---

# 3. Actual code path

## 3.1 Create flow

`TransactionLifecycleCoordinator.createExpense()` currently does:

```text
restore write guard
→ validate request
→ generate merchantKey
→ generate dedupeKey
→ build Expense
→ normalize ownership
→ optionally convert non-EUR into baseAmount/baseCurrency/exchangeRateUsed
→ dedupe according to mode
→ database.withTransaction {
      expenseDao.insertAtomic(expense)
      transactionEventDao.insert(CREATED)
  }
→ sideEffectDispatcher.dispatchOnCreated()
→ recurringLifecycleCoordinator.linkExpenseToOccurrence()
→ return Created
```

Strengths:

- central coordinator exists,
- restore mode blocks writes,
- validation exists,
- unique `dedupeKey` index exists,
- insert + created event are inside one Room transaction,
- side effects are best-effort and do not break the caller,
- recurring matching is attempted.

Relevant source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

---

## 3.2 Event table

`TransactionEvent` records:

```text
expenseId
eventType
source
actor
occurredAt
dedupeKey
duplicateExpenseId
beforeSnapshot
afterSnapshot
metadata
reason
```

This is a good audit/event model.

But currently, event usage is incomplete:

- `CREATED` is written.
- `UPDATED` is written only if `TransactionLifecycleCoordinator.updateExpense()` is used.
- `DELETED` is written only if `TransactionLifecycleCoordinator.deleteExpense()` is used.
- `CREATE_DUPLICATE_SKIPPED` can be written.
- `CREATE_VALIDATION_FAILED`, `CREATE_INSERT_CONFLICT`, `CREATE_ATTEMPTED`, `SIDE_EFFECT_FAILED`, `BULK_UPDATED` are defined but not consistently used.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt

---

# 4. Major findings

## Finding P0-1 — “Post-commit” side effects are not always post-commit

The coordinator says side effects are post-commit. That is true only when `createExpense()` is called directly.

But some callers do this:

```kotlin
database.withTransaction {
    transactionLifecycleCoordinator.createExpense(request)
    // more writes
}
```

Visible examples:

- `ManualExpenseRepository.addManualExpense()`
- `ReviewQueueRepository.approveReview()` / mark relevant path
- `GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()`

Problem:

`TransactionLifecycleCoordinator.createExpense()` internally does:

```kotlin
database.withTransaction {
    insert expense
    insert CREATED event
}

sideEffectDispatcher.dispatchOnCreated(...)
recurringLifecycleCoordinator.linkExpenseToOccurrence(...)
```

If the caller already has an outer Room transaction, the inner transaction block is not a true global commit boundary. The standard side effects can run while the caller’s outer transaction is still active.

## Why this is dangerous

Example: group expense creation.

```text
outer transaction starts
→ coordinator inserts expense + event
→ coordinator runs budget/anomaly/merchant/recurring side effects
→ group link insert fails
→ outer transaction rolls back expense/event
```

Now side effects may have run for an expense that no longer exists.

Possible symptoms:

- budget alerts for rolled-back expenses,
- merchant-category learning from rolled-back expense,
- anomaly alerts for data that did not commit,
- recurring occurrence linked to a rolled-back or not-yet-committed row,
- dashboard/budget briefly inconsistent,
- hard-to-reproduce bugs because behavior depends on nested transaction timing.

## Recommendation

Create a lifecycle API that supports outer atomic flows.

Suggested model:

```kotlin
data class LifecycleCreateTxResult(
    val result: CreateExpenseResult,
    val postCommitActions: List<PostCommitAction>
)
```

Then:

```kotlin
database.withTransaction {
    val create = coordinator.createExpenseDbOnly(request)
    // caller-specific DB writes: review status, group link, receipt link, stats
    create
}

coordinator.dispatchPostCommitActions(create)
```

Or simpler:

```kotlin
coordinator.createExpense(
    request,
    sideEffectMode = SideEffectMode.DEFER
)
```

Then caller executes deferred side effects after its own outer transaction commits.

Priority: highest.

Relevant files:

- `TransactionLifecycleCoordinator.kt`
- `ManualExpenseRepository.kt`
- `ReviewQueueRepository.kt`
- `GroupTransactionCoordinator.kt`
- `NotificationProcessingPipeline.kt`

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

---

## Finding P0-2 — Several update paths bypass lifecycle event logging

`ExpenseRepository.updateExpense()` uses the coordinator.

But many specific update methods still call `ExpenseDao` directly:

```text
updateExpenseCategory
updateExpenseCategoryBulk
updateExpenseMerchant
updateExpenseMerchantBulk
updateExpenseType
updateTransferDetails
updateNotMineDetails
updateSharedExpenseDetails
updateOwnership
```

These update real expense rows but do not write `TransactionEvent.UPDATED` or `BULK_UPDATED`.

Also, `GroupTransactionCoordinator.normalizeLinkedSystemExpense()` directly mutates:

```text
isNotMine
isSharedExpense
mySharePercentage
myShareAmount
```

without lifecycle event logging.

## Why this matters

A user can edit:

- category,
- merchant,
- transaction type,
- ownership,
- transfer details,
- shared-expense fields,

and the audit trail will not show it.

This violates the architecture contract:

```text
all expense create/update/delete should route through lifecycle coordinator
```

## Recommendation

Add specialized lifecycle update APIs:

```kotlin
updateCategory(expenseId, categoryId, source, reason)
updateMerchant(expenseId, merchant, applyToAll, source, reason)
updateType(expenseId, type, source, reason)
updateOwnership(expenseId, ownershipPatch, source, reason)
updateTransferDetails(expenseId, transferPatch, source, reason)
bulkUpdateMerchant(...)
bulkUpdateCategory(...)
```

Each should:

```text
load beforeSnapshot
normalize fields
persist update
write UPDATED or BULK_UPDATED event
dispatch relevant post-commit side effects
```

Priority: highest.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

---

## Finding P1-1 — Strict external ID idempotency returns `InsertConflict`, not useful duplicate identity

In `STRICT_EXTERNAL_ID`, the coordinator sets:

```text
dedupeKey = idem:{source}:{idempotencyKey}
```

and relies on the unique `dedupeKey` index.

If insert conflicts, current result is:

```kotlin
CreateExpenseResult.InsertConflict(dedupeKey)
```

But for idempotent systems like:

- bank sync,
- notification retry,
- email receipt retry,
- import retry,

a duplicate idempotency key should usually return:

```text
Already exists / DuplicateSkipped(existingExpenseId)
```

not a generic insert conflict.

## Recommendation

On insert conflict in `STRICT_EXTERNAL_ID` mode:

```kotlin
val existingId = expenseDao.findIdByDedupeKey(expense.dedupeKey)
return DuplicateSkipped(existingId, "Idempotent duplicate")
```

or introduce:

```kotlin
CreateExpenseResult.AlreadyCreated(existingExpenseId)
```

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/DeduplicationMode.kt  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

---

## Finding P1-2 — Duplicate lookup does not mirror duplicate detection

The duplicate check uses:

```kotlin
expenseDao.isDuplicateCurrencyAware(...)
```

This checks:

- merchant key,
- merchant-key prefix containment,
- raw merchant,
- date/amount tolerance,
- currency,
- compatible transaction type including `UNKNOWN`.

But duplicate ID retrieval uses:

```kotlin
findDuplicateId(...)
```

That query is narrower:

- exact merchant key,
- exact-ish amount/date,
- exact currency,
- exact transaction type.

So the app can detect a duplicate but fail to identify the duplicate ID.

Current behavior:

```text
DuplicateSkipped(existingExpenseId = -1)
```

or a duplicate event with `duplicateExpenseId = null`.

## Recommendation

Create one policy-consistent method:

```kotlin
findDuplicateCandidateCurrencyAware(...)
```

or reuse `getDuplicateCandidateForImportCurrencyAware(...)` with the same window/tolerance/type logic.

Then `DuplicateSkipped` should normally include the real existing ID.

Priority: high.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

---

## Finding P1-3 — Duplicate event writing is best-effort and outside atomic create flow

When duplicate is detected, coordinator writes duplicate event using `writeDuplicateEvent()`.

But that method catches errors and only logs warning.

So a duplicate can be skipped with no audit record.

This may be acceptable for robustness, but it weakens debugging and lifecycle auditability.

## Recommendation

Return event-writing status in the result:

```kotlin
DuplicateSkipped(
    existingExpenseId,
    reason,
    eventLogged: Boolean
)
```

Or, if audit is required, fail loudly in debug/release-candidate builds.

Priority: medium-high.

---

## Finding P1-4 — Failed creates are invisible in `transaction_events`

`LifecycleEventType` includes:

```text
CREATE_ATTEMPTED
CREATE_VALIDATION_FAILED
CREATE_INSERT_CONFLICT
```

But `createExpense()` currently returns validation failures and conflicts without writing those events.

Because `TransactionEvent.expenseId` is nullable, the model clearly supports attempted-but-failed creates.

## Recommendation

Write events for:

```text
CREATE_ATTEMPTED
CREATE_VALIDATION_FAILED
CREATE_INSERT_CONFLICT
CREATE_DUPLICATE_SKIPPED
```

Use `expenseId = null` when no expense exists.

This will massively improve debugging because you can answer:

```text
Did creation fail before insert?
Was it validation?
Was it dedupe?
Was it unique-index conflict?
```

Priority: high.

---

## Finding P1-5 — Currency snapshot always assumes EUR, not user home currency

Coordinator uses:

```kotlin
val homeCurrency = CurrencyConverter.DEFAULT_BASE_CURRENCY
```

and comments imply EUR.

But the app has a currency settings system and multi-currency repository.

If the user’s home currency is not EUR, `baseAmount`, `baseCurrency`, and `exchangeRateUsed` can be misleading.

## Recommendation

Inject `CurrencySettingsRepository` or a lifecycle currency policy:

```kotlin
val homeCurrency = currencySettingsRepository.homeCurrency().first()
```

Then snapshot conversion should use the user’s home currency at creation time.

Priority: high for non-EUR users.

Sources:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

---

## Finding P1-6 — Update path may leave `merchantKey` stale

`updateExpense()` recomputes `dedupeKey` if key fields changed.

But if merchant changed, it does not generate a new `merchantKey`. It trusts the `Expense` object passed by the caller.

So this can happen:

```text
expense.merchant = "New Merchant"
expense.merchantKey = old key
dedupeKey = generated for New Merchant
```

Then:

- duplicate checks use stale merchant key,
- merchant grouping may be wrong,
- dashboard/analytics by merchant can drift,
- future dedupe becomes unreliable.

Some repository-specific merchant update methods do update merchant key, but those bypass lifecycle events.

## Recommendation

Coordinator update should normalize:

```text
merchantKey
dedupeKey
ownership fields
base currency snapshot if amount/currency changed
```

Do not trust callers to keep derived fields synced.

Priority: high.

---

## Finding P1-7 — Update/delete do not dispatch lifecycle side effects

Create dispatches:

```text
budget monitor
anomaly alert
merchant-category learning
recurring link
```

But update/delete do not appear to dispatch equivalent recalculation hooks.

Dashboard/analytics may update through Flow/queries, but side-effect systems can remain stale:

- budget alert state,
- anomaly state,
- merchant-category learning,
- recurring link/unlink,
- recommendation refresh.

## Recommendation

Add:

```kotlin
dispatchOnUpdated(expenseId, before, after, source)
dispatchOnDeleted(expenseId, before, source)
```

At minimum:

```text
budget monitor after amount/category/date/type/ownership changes
recurring relink after date/merchant/amount changes
merchant learning after merchant/category changes
```

Priority: medium-high.

---

## Finding P2-1 — Delete source is hardcoded

`deleteExpense(expense)` writes event with:

```text
source = "USER_ACTION"
```

There is no way for callers to pass:

- `REVIEW_REJECTION`
- `DEBUG_TOOL`
- `RESTORE`
- `GROUP_DELETE`
- `BANK_SYNC_REVERSAL`
- `USER_ACTION`

## Recommendation

Change delete API to:

```kotlin
deleteExpense(
    expenseId: Long,
    source: String,
    reason: String?,
    actor: String?
)
```

Priority: medium.

---

## Finding P2-2 — TransactionEventDao is too minimal

`TransactionEventDao` only has:

```text
insert
getEventsForExpense
```

For debugging and audit screens, you need:

```text
getRecentEvents(limit)
getEventsByType(type)
getEventsBySource(source)
getEventsBetween(start,end)
getDuplicateEvents()
getFailedCreateEvents()
countEventsForExpense(id)
```

Priority: medium.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt

---

## Finding P2-3 — Existing lifecycle tests are mock-only

`TransactionLifecycleCoordinatorTest` exists, but it mocks:

- `AppDatabase`
- `ExpenseDao`
- `TransactionEventDao`
- side effects
- recurring coordinator

It tests a few cases:

- valid create returns `Created`
- negative amount validation
- blank merchant validation
- invalid currency validation

This is useful smoke coverage, but it does not prove:

- real Room insert works,
- `transaction_events` row persists,
- unique `dedupeKey` blocks duplicates,
- duplicate candidate ID is correct,
- rollback behavior works,
- side effects run only after commit,
- dashboard/analytics observe the created row,
- restore mode blocks real DB writes,
- update/delete event snapshots are correct.

## Recommendation

Keep the mock tests, but add DB-backed contract tests.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt

---

# 5. Debugging checklist for Pipeline 2

## For every expense creation path

Check:

- [ ] request source is correct,
- [ ] restore mode allows write,
- [ ] validation passes/fails with visible event,
- [ ] merchant key generated correctly,
- [ ] dedupe key generated correctly,
- [ ] duplicate check result correct,
- [ ] insert result > 0,
- [ ] `CREATED` event exists,
- [ ] source-specific links/stats written,
- [ ] side effects run after final transaction commit,
- [ ] recurring link attempted only after commit,
- [ ] dashboard total changes,
- [ ] analytics category total changes,
- [ ] budget status changes.

## Creation sources to verify

- [ ] manual entry
- [ ] notification auto-accept
- [ ] review approval
- [ ] receipt scan
- [ ] email receipt
- [ ] bank API sync
- [ ] bank statement review
- [ ] CSV import
- [ ] group expense
- [ ] recurring generated
- [ ] debug/restore

## For updates

Check:

- [ ] category edit writes event,
- [ ] merchant edit writes event,
- [ ] amount edit writes event,
- [ ] date edit writes event,
- [ ] transaction type edit writes event,
- [ ] ownership/shared edit writes event,
- [ ] transfer details edit writes event,
- [ ] bulk category/merchant edit writes `BULK_UPDATED`,
- [ ] derived fields are regenerated,
- [ ] budget/analytics/dedupe behavior remains correct.

## For deletion

Check:

- [ ] delete writes `DELETED`,
- [ ] source/reason/actor recorded,
- [ ] linked receipt/group/recurring behavior is correct,
- [ ] dashboard total updates,
- [ ] no unsafe orphan state.

---

# 6. Recommended fix plan

## PR 1 — Observability and tests first

Add transaction lifecycle diagnostics:

```kotlin
enum class TransactionLifecycleStage {
    CREATE_ATTEMPTED,
    DROPPED_RESTORE_MODE,
    VALIDATION_FAILED,
    DUPLICATE_SKIPPED,
    INSERT_ATTEMPTED,
    INSERT_CONFLICT,
    CREATED_EVENT_WRITTEN,
    SIDE_EFFECT_STARTED,
    SIDE_EFFECT_FAILED,
    RECURRING_LINK_STARTED,
    CREATED,
    UPDATED,
    DELETED
}
```

Expose in debug screen:

```text
last create attempt
last validation error
last duplicate skip
last insert conflict
last side effect failure
last update event
last delete event
```

Add DB-backed test skeleton.

---

## PR 2 — Fix nested transaction side-effect boundary

Introduce:

```kotlin
createExpenseDbOnly()
dispatchPostCommit()
```

or:

```kotlin
SideEffectMode.IMMEDIATE / DEFER
```

Then change nested callers:

- `ManualExpenseRepository`
- `ReviewQueueRepository`
- `GroupTransactionCoordinator`
- `NotificationProcessingPipeline`

so side effects run only after the outer transaction commits.

This is the most important behavioral fix.

---

## PR 3 — Route update paths through lifecycle

Replace direct update calls in `ExpenseRepository` with lifecycle update methods.

At minimum:

```text
updateExpenseCategory
updateExpenseMerchant
updateExpenseType
updateOwnership
updateTransferDetails
```

should write `UPDATED`.

Bulk operations should write `BULK_UPDATED` with metadata.

---

## PR 4 — Fix dedupe result identity

Make duplicate detection and duplicate ID retrieval use the same policy.

Add:

```kotlin
findDuplicateCandidateCurrencyAware(...)
```

Use it in:

- `STANDARD`
- `BULK_IMPORT`
- update duplicate prevention.

---

## PR 5 — Fix idempotent strict mode

For `STRICT_EXTERNAL_ID` insert conflict:

```text
lookup existing by dedupeKey
return DuplicateSkipped or AlreadyCreated
```

---

## PR 6 — Fix currency home snapshot

Inject actual home-currency settings instead of hardcoded `CurrencyConverter.DEFAULT_BASE_CURRENCY`.

---

# 7. Tests to add

## 7.1 `TransactionLifecycleCoordinatorDbContractTest`

Use real in-memory Room DB.

Cases:

1. create manual expense
2. assert expense row exists
3. assert `CREATED` event exists
4. duplicate create returns `DuplicateSkipped`
5. duplicate event exists
6. strict external ID retry returns existing ID
7. validation failure creates failed event if enabled
8. update amount/category writes `UPDATED`
9. delete writes `DELETED`
10. restore mode blocks write

---

## 7.2 `TransactionLifecyclePostCommitContractTest`

Goal: prove side effects do not run before final commit.

Test:

```text
outer transaction
→ createExpense deferred
→ force failure after expense insert
→ transaction rolls back
→ assert side effects not called
```

Then success case:

```text
outer transaction succeeds
→ side effects run exactly once
```

This catches the biggest current risk.

---

## 7.3 `ExpenseRepositoryLifecycleBypassTest`

Guard that these methods write events:

- category edit
- merchant edit
- type edit
- ownership edit
- transfer edit
- bulk category update
- bulk merchant update

---

## 7.4 `GroupExpenseLifecycleAtomicityTest`

Test:

```text
create system expense + group link
→ group link insert fails
→ no expense row
→ no transaction event
→ no side effect
```

Then success:

```text
expense row + event + group link + shared amount normalization all persist
```

---

## 7.5 `NotificationReviewLifecycleScenarioTest`

Seed:

```text
category
budget
raw notification
pending review
```

Feed:

```text
approve review
```

Assert:

```text
expense row
transaction CREATED event
raw notification marked relevant
pending review APPROVED
source stats accepted
budget/dashboard/analytics updated
```

---

## 7.6 `CurrencySnapshotLifecycleTest`

Seed home currency:

```text
USD or GBP
```

Create non-home expense.

Assert:

```text
baseAmount
baseCurrency
exchangeRateUsed
dashboard total
```

---

# 8. Suggested scenario test

## `transaction_lifecycle_db_contract`

Seed:

```text
home currency EUR
categories: Food, Transport
budget: Food €100/month
fixed time: 2026-05-01
```

Steps:

```text
1. create manual grocery expense €20
2. create duplicate grocery expense
3. update grocery amount to €25
4. update category Food → Transport
5. delete expense
```

Expected:

```text
after create:
  expense count = 1
  CREATED event = 1
  dashboard monthly total = €20
  Food budget remaining = €80

after duplicate:
  expense count = 1
  CREATE_DUPLICATE_SKIPPED event = 1

after amount update:
  UPDATED event = 1
  dashboard monthly total = €25

after category update:
  UPDATED/BULK_UPDATED event = 1
  Food total = €0
  Transport total = €25

after delete:
  expense count = 0
  DELETED event = 1
  dashboard monthly total = €0
```

This single test would verify the main lifecycle contract.

---

# 9. Most likely real instability sources

Ranked:

1. **Nested transaction side effects.**
   - Can produce side effects for rolled-back data.

2. **Direct DAO update bypasses.**
   - Events/audit/debug visibility missing for many edits.

3. **Dedupe identity mismatch.**
   - Duplicate detected but `existingExpenseId` missing or wrong.

4. **Strict external ID conflict semantics.**
   - Idempotent retries become generic conflicts.

5. **Hardcoded EUR base snapshot.**
   - Wrong for non-EUR home currency.

6. **Mock-only lifecycle tests.**
   - Real DB constraints and rollback behavior unproven.

---

# 10. Final recommendation

For Pipeline 2, do not start by adding more features.

Stabilize it in this order:

```text
1. Add DB-backed lifecycle tests.
2. Fix nested transaction / post-commit side effects.
3. Route all update paths through lifecycle.
4. Fix dedupe identity/idempotency.
5. Fix home-currency snapshot.
6. Add lifecycle diagnostics/debug screen.
```

The guiding rule should be:

> No expense row should be created, updated, deleted, shared, imported, approved, or restored without a lifecycle event and a clear source.

Once this is true, debugging the rest of the app becomes much easier.

---

# 11. Verification & Fix Log (2026-05-06)

## Methodology
Each finding from the original report was verified against the actual source code at the current commit. Fixes were applied where safe and impactful.

## Finding Verification Status

### P0-1 — "Post-commit" side effects are not always post-commit
**Status: CONFIRMED & FIXED**

Four callers wrap `TransactionLifecycleCoordinator.createExpense()` inside their own `database.withTransaction { … }`, causing "post-commit" side effects (budget monitoring, anomaly alerts, merchant learning, recurring link) to execute while the outer transaction is still active:

1. `ManualExpenseRepository.addManualExpense()` — wraps coordinator call at line 151
2. `ReviewQueueRepository.approveReview()` — wraps at line 181
3. `ReviewQueueRepository.markAsRelevant()` — wraps at line 524
4. `NotificationProcessingPipeline.handleAutoAcceptInTransaction()` — called inside `database.withTransaction` at line 371
5. `GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()` — wraps at line 461

**Fix applied:** Introduced `SideEffectMode` enum (`IMMEDIATE` / `DEFER`). All four nested-transaction callers now use `SideEffectMode.DEFER` and call `coordinator.dispatchPostCreationSideEffects()` only after their outer transaction commits. This eliminates side effects for rolled-back data.

Files changed:
- `SideEffectMode.kt` — new enum
- `TransactionLifecycleCoordinator.kt` — added `sideEffectMode` parameter + public `dispatchPostCreationSideEffects()` method
- `ManualExpenseRepository.kt` — DEFER + dispatch after tx
- `ReviewQueueRepository.kt` — DEFER in approveReview + markAsRelevant + dispatch after tx
- `NotificationProcessingPipeline.kt` — DEFER + dispatch in AutoAccepted post-commit
- `GroupTransactionCoordinator.kt` — DEFER + dispatch after tx via `.also {}`

### P0-2 — Several update paths bypass lifecycle event logging
**Status: PARTIALLY FIXED (2026-05-06) — C1 migration started, NOT complete**

**FIXED:**
- `updateExpenseCategory()` — both overloads now route through `TransactionLifecycleCoordinator.updateCategory()`, which writes `TransactionEvent.UPDATED` with before/after snapshots in an atomic DB transaction
- `ExpenseRepository.kt` now has a comprehensive `## C1 LIFECYCLE MIGRATION — PARTIALLY COMPLETE` KDoc block documenting all remaining bypasses

**STILL BYPASSING (16 methods across 3 files):**
- `updateExpenseCategoryBulk()` — calls `expenseDao.updateCategoryForMerchant()` directly
- `updateExpenseMerchant()` / `updateExpenseMerchantBulk()` — direct DAO
- `updateExpenseType()` — direct DAO
- `updateTransferDetails()` — direct DAO
- `updateNotMineDetails()` / `updateSharedExpenseDetails()` / `updateOwnership()` — direct DAO
- `updateExpenseLocation()` / `conditionallySetLocation()` / `clearExpenseLocation()` — direct DAO
- `ReceiptLinkService.kt` RCP-30 category propagation — `runCatching` block
- `GroupTransactionCoordinator.kt` — shared-expense flags clearing + ownership normalization

The remaining bypasses are deprecated with `@Deprecated` annotations and documented in the code.
Full migration requires adding `updateMerchant()`, `updateType()`, `updateTransferDetails()`, `updateOwnership()`,
`updateLocation()`, `bulkUpdateCategory()`, and `bulkUpdateMerchant()` to the coordinator (staged PRs 2-5).

### P1-1 — Strict external ID idempotency returns InsertConflict
**Status: CONFIRMED, NOT FIXED**

When `STRICT_EXTERNAL_ID` mode encounters a unique-index conflict, it returns `CreateExpenseResult.InsertConflict(dedupeKey)` instead of looking up the existing expense and returning `DuplicateSkipped(existingId)`. Low-risk but reduces caller clarity. Recommend adding `expenseDao.findIdByDedupeKey()` query.

### P1-2 — Duplicate lookup does not mirror duplicate detection
**Status: FIXED (2026-05-06):** Added `findDuplicateIdCurrencyAware()` to ExpenseDao.kt that uses the same 3-tier matching as `isDuplicateCurrencyAware()`. TransactionLifecycleCoordinator now calls this unified method in all 3 call sites instead of `findDuplicateId()`.

### P1-3 — Duplicate event writing is best-effort
**Status: CONFIRMED, ACCEPTABLE**

`writeDuplicateEvent()` catches exceptions and logs. This is intentional robustness — duplicate events are informational and should not block the caller.

### P1-4 — Failed creates are invisible in transaction_events
**Status: CONFIRMED, NOT FIXED**

`CREATE_ATTEMPTED`, `CREATE_VALIDATION_FAILED`, `CREATE_INSERT_CONFLICT` event types exist but are never written. The model supports `expenseId = null` for attempted-but-failed creates. Recommend as a follow-up improvement.

### P1-5 — Currency snapshot always assumes EUR, not user home currency
**Status: CONFIRMED — FIXED**

- `TransactionLifecycleCoordinator` now injects `CurrencySettingsRepository` and reads the user's home currency via `currencySettingsRepository.homeCurrency().first()`.
- Falls back to `CurrencyConverter.DEFAULT_BASE_CURRENCY` if the setting cannot be read.
- Both `createExpense` and `updateExpense` now use the user's actual home currency for the conversion snapshot.

### P1-6 — Update path may leave merchantKey stale
**Status: CONFIRMED — ALREADY FIXED**

- The `updateExpense` method already regenerates `merchantKey` via `MerchantKeyGenerator.generate()` when `existing.merchant != expense.merchant`.

### P1-7 — Update/delete do not dispatch lifecycle side effects
**Status: FIXED (2026-05-06):** `TransactionSideEffectDispatcher` now has `dispatchOnUpdated()` (budget + anomaly + merchant) and `dispatchOnDeleted()` (budget) methods. `TransactionLifecycleCoordinator.updateExpense()` and `deleteExpense()` call them post-commit.

### P2-1 — Delete source is hardcoded
**Status: CONFIRMED & FIXED**

Both `deleteExpense(expenseId)` and `deleteExpense(expense)` now accept `source: String = "USER_ACTION"`, `reason: String? = null`, and `actor: String? = null` parameters. Existing callers remain unchanged due to defaults.

### P2-2 — TransactionEventDao is too minimal
**Status: CONFIRMED, NOT FIXED**

Only has `insert` and `getEventsForExpense`. Recommend adding audit/debug queries as a follow-up.

### P2-3 — Existing lifecycle tests are mock-only
**Status: CONFIRMED, NOTED (testing strategy under separate refactor)**

---

# 12. New issues discovered (not in original report)

### NEW-1: `deleteAllExpenses()` bypasses lifecycle entirely (P2)
`ExpenseRepository.deleteAllExpenses()` calls `expenseDao.deleteAll()` with no lifecycle events. Used by `DebugViewModel.resetExpenses()` and `NotificationRepository.deleteAll()`. Debug-only path but creates audit gaps.

### NEW-2: `restoreDebugSnapshot()` inserts via `expenseDao.insertAll()` directly (P2)
Bypasses coordinator validation, dedup, and event logging. Debug-only but the audit trail gap during snapshot restore is invisible.

### NEW-3: `ReceiptLinkService` updates category directly (P1)
`ReceiptLinkService.kt` line 207 calls `expenseDao.updateCategory()`, bypassing lifecycle. Same pattern as P0-2 but from a different service.

### NEW-4: `expenseToSnapshot()` only captures 6 fields — FIXED
Before/after snapshots for lifecycle events only included id, amount, currency, merchant, date, type. Missing: categoryId, merchantKey, dedupeKey, ownership fields, transfer details, notes, base amount/currency/exchange rate.

**Fix applied:** Enriched snapshot to include merchantKey, categoryId, dedupeKey, isNotMine, isSharedExpense, mySharePercentage, myShareAmount, transferDirection, notes, baseAmount, baseCurrency, exchangeRateUsed.

### NEW-5: `updateExpense()` uses latest exchange rate instead of historical — FIXED
`createExpense()` correctly uses `currencyConverter.convertAsOf(atMillis = expense.date)` for historical rates. But `updateExpense()` used `currencyConverter.convert()` which fetches the latest rate. If a user changes the date of a foreign-currency expense, the base amount conversion would use the wrong rate.

**Fix applied:** `updateExpense()` now uses `currencyConverter.convertAsOf(atMillis = updatedExpense.date)` matching the create path.

### NEW-6: `expenseToSnapshot()` was inconsistent between overloads (code quality)
The `expenseToSnapshot(e: Expense)` and `expenseToSnapshot(id: Long, e: Expense)` overloads duplicated logic. Consolidated: the single-arg version now delegates to the two-arg version.

---

# 13. Applied fixes summary

| # | Finding | Severity | Fix |
|---|---------|----------|-----|
| 1 | P0-1: Nested transaction side effects | P0 | `SideEffectMode.DEFER` in 4 callers |
| 2 | P1-6: Stale merchantKey on update | P1 | Regenerate merchantKey in `updateExpense()` |
| 3 | P2-1: Hardcoded delete source | P2 | Added source/reason/actor params |
| 4 | NEW-4: Incomplete lifecycle snapshots | P1 | Enriched `expenseToSnapshot()` |
| 5 | NEW-5: Wrong exchange rate in update | P1 | Use `convertAsOf()` in `updateExpense()` |
| 6 | P1-5: Use home currency for conversion snapshot | P1 | Use home currency instead of hardcoded EUR for conversion snapshot in `TransactionLifecycleCoordinator.kt` |

---

# 14. Remaining work priority

1. **Route all update paths through lifecycle** (P0-2) — dedicated PR, ~10 methods in `ExpenseRepository` + `ReceiptLinkService`
2. ~~**Align duplicate detection and ID retrieval** (P1-2) — create `findDuplicateCandidateCurrencyAware()`~~ **DONE**
3. **Fix strict external ID conflict semantics** (P1-1) — return `DuplicateSkipped(existingId)` on conflict
4. **Write failed-create events** (P1-4) — use existing `LifecycleEventType` values
5. ~~**Add update/delete side-effect dispatch** (P1-7) — `dispatchOnUpdated()`, `dispatchOnDeleted()`~~ **DONE**
6. **Add audit queries to TransactionEventDao** (P2-2) — `getRecentEvents`, `getEventsByType`, etc.

---

# Sources

- Dependency map  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `TransactionLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionSideEffectDispatcher.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt

- `CreateExpenseRequest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

- `CreateExpenseResult.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseResult.kt

- `DeduplicationMode.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/DeduplicationMode.kt

- `ExpenseSource.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseSource.kt

- `LifecycleEventType.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt

- `TransactionEvent.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- `TransactionEventDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt

- `ExpenseDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `ManualExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt

- `ExpenseRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `ReviewQueueRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt

- `ReceiptRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

- `GroupTransactionCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt

- `BankApiIntegration.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/bank/BankApiIntegration.kt

- `EmailReceiptIngestionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt

- `AppDatabase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- Schema v113, expenses unique `dedupeKey` index  
  https://github.com/panospao7/Cost-agregator/blob/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/schemas/com.yourname.expensetracker.data.database.AppDatabase/113.json

- Existing mock lifecycle test  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorTest.kt