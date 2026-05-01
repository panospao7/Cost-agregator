# Phase 1–5 Clean-State Review

Reviewed latest visible commit:

- `b10c136` — `Post-review fixes: transactional createExpense + link/unlink...`
- Parent: `1ea5eb2` — Phase 5 foundation commit

Static GitHub review only. I did **not** run Gradle or Room schema tests locally.

## Executive verdict

Do **not** proceed to the next phases yet.

The code is moving in the right architectural direction, but the current pushed state is **not clean enough** to treat Phases 1–5 as closed.

There are three categories of blockers:

1. **Possible compile/Hilt blocker**
2. **Phase 3/4 lifecycle correctness gaps**
3. **Phase 5 foundation still incomplete / unsafe**

Also, the raw GitHub view renders several Kotlin files as extremely compressed single-line files. If that is the actual repository content and not a web-rendering artifact, Kotlin compilation will fail immediately because `package`, `import`, and declarations appear on the same line without separators. First validation step should be:

```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:kaptDebugKotlin
./gradlew.bat :app:testDebugUnitTest
```

---

# Latest visible commit status

The PR commit list shows:

- `1ea5eb2` — Phase 5 foundation
- `b10c136` — post-review fixes

I do **not** see a separate newer commit that fixes Phase 5 issues after `b10c136`.

So this review is based on:

- `b10c136`
- Phase 5 code as of `1ea5eb2`, plus the small recurring hook added in `b10c136`

If you expected another commit to be reviewed, push it or confirm the SHA.

---

# Phase 1 — Currency

## Status

**Almost closed, but not fully clean.**

## Good

Currency work is broadly integrated across the earlier commits, and transaction creation now validates currency as a 3-letter uppercase ISO-like code.

## Remaining blocker

`ReceiptLifecycleCoordinator` still has:

```kotlin
private const val FALLBACK_CURRENCY = "EUR"
```

and email receipts still use:

```kotlin
currency = emailData.currency ?: FALLBACK_CURRENCY
```

That violates the Phase 1 rule: lifecycle creation should not silently assume EUR.

## Required fix

For email receipts:

1. Use parsed email currency if present.
2. Else use home currency from `CurrencySettingsRepository`.
3. Else return validation/review-required result.
4. If using home currency, record assumption metadata if your schema supports it.

Do not silently fall back to EUR in lifecycle code.

## Phase 1 conclusion

**Conditionally acceptable after removing the email EUR fallback.**

---

# Phase 2 — Time / Period Semantics

## Status

**Mostly good, but raw millis day math has regressed.**

## Good

- Half-open period model is established.
- `NaturalLanguageSearchEngine` now documents and applies calendar-month semantics for “last month”.
- TimeProvider direction is mostly correct.

## Remaining violations

### 1. Transaction future-date validation

`TransactionLifecycleCoordinator.validate()` still uses:

```kotlin
now + 86_400_000L
```

Replace with calendar-aware logic:

```kotlin
TimePeriodUtils.addDays(now, 1)
```

or:

```kotlin
TimePeriodUtils.getEndOfDay(now)
```

depending on whether policy is “not beyond today” or “allow until tomorrow”.

### 2. Recurring occurrence linking

`RecurringLifecycleCoordinator.linkExpenseToOccurrence()` uses:

```kotlin
expenseDayStart + 86_400_000L
```

Replace with:

```kotlin
TimePeriodUtils.getEndOfDay(expense.date)
```

### 3. Reminder scheduling

`RecurringOccurrenceMaterializer.computeScheduledAt()` uses:

```kotlin
dueDate - days * 86_400_000L
```

Replace with:

```kotlin
TimePeriodUtils.addDays(dueDate, -days)
```

## Phase 2 conclusion

**Not fully closed until the raw millis day math is removed again.**

---

# Phase 3 — Transaction Lifecycle

## Status

**Improved, but not complete.**

## Good fixes in `b10c136`

### 1. Create path now uses transaction

`createExpense()` now wraps insert + created-event in:

```kotlin
database.withTransaction
```

That fixes the earlier “expense without audit event” risk for creates.

### 2. Validation is stronger

Now checks:

- amount positive/finite
- amount max
- merchant not blank
- placeholder merchant rejection
- currency shape
- date positive/future
- transfer metadata

### 3. Recurring linkage hook added

After creation, transaction lifecycle attempts:

```kotlin
recurringLifecycleCoordinator.linkExpenseToOccurrence(insertedId)
```

This is directionally correct, although the recurring matching implementation is currently unsafe.

## Remaining blockers

## 1. Hilt binding risk

`TransactionLifecycleCoordinator` now injects `RecurringLifecycleCoordinator`.

`RecurringLifecycleCoordinator` injects:

```kotlin
RecurringOccurrenceExpander
OccurrenceConflictResolver
```

But these classes appear to be plain classes without `@Inject constructor()` and without an obvious provider module.

If no module exists, Hilt will fail.

Required fix:

```kotlin
class RecurringOccurrenceExpander @Inject constructor()
class OccurrenceConflictResolver @Inject constructor()
```

or provide them in a Hilt module.

## 2. `STRICT_EXTERNAL_ID` is still logically broken

The code checks:

```kotlin
expenseDao.findIdByDedupeKey(idempotencyKey)
```

But the inserted expense still receives a normal dedupe key generated from:

- amount
- merchant
- date
- currency
- transaction type

It does **not** store `idempotencyKey` as the dedupe key.

So retries with the same external ID will not reliably find the original row.

Required fix options:

### Preferred

Add dedicated columns:

- `sourceIdempotencyKey`
- `externalFingerprint`

with a unique index by source where appropriate.

### Acceptable short-term

For `STRICT_EXTERNAL_ID`, set a namespaced dedupe key:

```kotlin
dedupeKey = "external:${request.source}:${idempotencyKey}"
```

But this changes dedupe semantics, so document it.

## 3. `BULK_IMPORT` skips range-based dedupe

Current behavior:

```kotlin
DeduplicationMode.BULK_IMPORT -> {
    // Skip range-based check; rely on insertAtomic + dedupeKey
}
```

This is risky because CSV/import was one of the largest original dedupe gaps.

Bulk import should still run standard duplicate detection. The difference should be side-effect/reporting policy, not weaker dedupe.

Required fix:

- `BULK_IMPORT` should do range dedupe + atomic insert.
- Suppress noisy side effects.
- Return row-level duplicate result.

## 4. Duplicate ID lookup is not equivalent to duplicate check

`isDuplicateCurrencyAware()` may use richer matching semantics than `findDuplicateId()`.

`findDuplicateId()` only checks:

- merchantKey exact
- amount
- date window
- currency
- transaction type

It may miss the row that made `isDuplicateCurrencyAware()` return true, causing:

```kotlin
existingExpenseId = -1L
```

Required fix:

- create one DAO query that returns the duplicate candidate row/ID and use it for both boolean and ID.
- Or replace boolean precheck with candidate lookup.

## 5. Update lifecycle is not transactional

`updateExpense()` currently:

1. loads existing
2. computes snapshot
3. updates expense
4. inserts event

Update + event should be atomic.

Required fix:

```kotlin
database.withTransaction {
    expenseDao.update(updatedExpense)
    transactionEventDao.insert(...)
}
```

Also still missing:

- duplicate check before update when key fields changed
- typed patch model instead of whole-entity replacement
- proper source/reason enforcement

## 6. Delete lifecycle is not transactional

`deleteExpense()` currently writes event then deletes.

If delete fails, the audit ledger lies.

Required fix:

```kotlin
database.withTransaction {
    transactionEventDao.insert(deleteEvent)
    expenseDao.delete(expense)
}
```

## 7. Snapshot JSON is still unsafe

`expenseToSnapshot()` manually builds JSON:

```kotlin
"""{"merchant":"${e.merchant}"}"""
```

This breaks on quotes, backslashes, and newlines.

Use:

- `JSONObject`
- kotlinx.serialization
- Moshi
- or a small escaping helper at minimum

## 8. Ownership validation may be product-wrong

Current rule rejects:

```kotlin
isNotMine && PURCHASE/WITHDRAWAL
```

But your app historically supports “not mine” purchases. The invariant should likely reject conflicting ownership/shared combinations, not ban not-mine purchase rows entirely.

Re-check this before keeping it.

## Phase 3 conclusion

**Foundation is good, but Phase 3 is not closed.**

Minimum closeout fixes:

1. Hilt binding risk.
2. strict external ID storage.
3. bulk import standard dedupe.
4. update/delete transactions.
5. safe JSON snapshots.
6. review ownership validation semantics.

---

# Phase 4 — Receipt Lifecycle

## Status

**Improved, but not closed.**

## Good fixes

### 1. ReceiptLinkService is much better

It now:

- uses `database.withTransaction`
- prevents non-bank relinking unless `allowRelink = true`
- supports multi-link bank statements
- updates legacy `ScannedReceipt.expenseId`
- writes link/unlink events

This is a strong improvement.

### 2. Text/semantic dedupe was added after OCR

`processReceiptInput()` now computes:

- text fingerprint
- semantic fingerprint

and checks duplicates after OCR/parse.

### 3. Manual fallback uses home currency first

Catastrophic OCR fallback uses:

```kotlin
currencySettingsRepository.homeCurrency().first()
```

before EUR fallback.

## Remaining blockers

## 1. Asset double-persistence risk remains

`processReceiptInput()` still does:

1. `assetStore.persistReceiptAsset(uri)`
2. `receiptRepository.processReceipt(imageUri = uri)`

But `ReceiptRepository.processReceipt()` historically also persists/copies the receipt image through OCR/storage logic.

This can create orphaned assets.

Required fix:

Choose one owner:

### Option A — Coordinator owns asset

- Persist asset once in `ReceiptLifecycleCoordinator`.
- OCR processes the persisted asset.
- Repository does not create another image copy.

### Option B — OCR/repository owns asset

- Remove pre-copy from coordinator.
- Compute hash from the saved `receipt.imagePath` after processing.

Do not do both.

## 2. Email lifecycle is still partial

`processEmailReceipt()` now saves a `ScannedReceipt`, which is better than TODO, but it still does not appear to fully implement the audit contract.

Missing:

- `EmailReceiptSource` insert
- `EmailReceiptDao` message-ID unique handling
- provider/sender/subject metadata persistence
- fingerprint dedupe beyond `sourceFingerprint`
- optional `TransactionLifecycleCoordinator` auto-expense creation
- receipt-expense linking
- external idempotency handling
- currency fallback discipline

So email receipt lifecycle is **started**, not complete.

## 3. Email still silently defaults to EUR

Same Phase 1 issue.

```kotlin
currency = emailData.currency ?: FALLBACK_CURRENCY
```

Must be changed.

## 4. Text/semantic duplicate leaves a duplicate row behind

The current flow:

1. OCR creates/saves a new receipt.
2. Coordinator computes text/semantic duplicate.
3. It updates the new receipt with fingerprints.
4. It returns the existing receipt.

This leaves the new duplicate receipt row in DB.

That may be okay only if it is explicitly marked:

```kotlin
processingStatus = DUPLICATE_DETECTED
```

But currently it looks like a normal saved receipt.

Required fix:

- either delete the duplicate row after detecting existing duplicate
- or mark it `DUPLICATE_DETECTED` and link/reference existing receipt
- or stage OCR output before saving the final receipt

## 5. Receipt deletion is not transactional

`deleteReceipt()` does:

1. event insert
2. link delete
3. asset delete
4. receipt delete

No DB transaction, and file deletion happens before DB row deletion.

If DB delete fails after file deletion, the receipt row points to a missing asset.

Required fix:

1. DB transaction:
   - insert delete event
   - delete links
   - delete receipt row or tombstone
2. Post-commit:
   - delete asset file
3. If asset delete fails:
   - log cleanup issue/event

## 6. Relink prevention has a race window

`ReceiptLinkService` checks existing links before entering `withTransaction`.

Two concurrent link attempts can both pass the pre-check.

Required fix:

- move existing-link check inside the transaction
- add DB-level constraint for one primary non-bank link if possible
- or enforce via transactional DAO query + insert

## Phase 4 conclusion

**ReceiptLinkService is close. ReceiptLifecycleCoordinator is not closed.**

Minimum closeout fixes:

1. Asset single-owner decision.
2. Full email lifecycle with `EmailReceiptSource`.
3. Remove silent EUR fallback.
4. Decide duplicate-row policy.
5. Transactional receipt delete.
6. Move relink check inside transaction.

---

# Phase 5 — Recurring / Planned / Reminder Lifecycle

## Status

**Foundation only. Not complete.**

I do not see a newer Phase 5 fix commit after `1ea5eb2`.

## Good

Phase 5 adds the right concepts:

- `RecurringOccurrenceExpander`
- `OccurrenceConflictResolver`
- `RecurringLifecycleCoordinator`
- `RecurringOccurrenceMaterializer`
- `RecurringOccurrence`
- `RecurringReminderDelivery`
- planned source occurrence fields
- transaction lifecycle hook to recurring occurrence linking

This is the correct direction.

## Critical blockers

## 1. Possible Hilt compile failure

As noted above:

- `RecurringOccurrenceExpander`
- `OccurrenceConflictResolver`

look like plain classes.

But `RecurringLifecycleCoordinator` injects them.

Add:

```kotlin
@Inject constructor()
```

or provide them in DI.

## 2. Occurrence expansion anchor is wrong

`RecurringOccurrenceExpander.expand()` starts from:

```kotlin
request.startDate
```

`RecurringLifecycleCoordinator.generateOccurrences()` passes the requested range start as `startDate`.

So if today is May 1 and the rule’s `nextDate` is May 15, expansion can incorrectly create an occurrence on May 1.

Required model:

```kotlin
ExpandRequest(
    anchorDate = rule.nextDate,
    rangeStartInclusive = startDate,
    rangeEndExclusive = endDate
)
```

Expansion should advance from the anchor and only emit dates inside the requested range.

## 3. Expense-to-occurrence linking is unsafe

`linkExpenseToOccurrence()` finds the first planned occurrence on the same day:

```kotlin
occ.status == "PLANNED" && occ.linkedExpenseId == null
```

It does not check:

- merchant
- merchant key
- amount tolerance
- currency
- transaction type
- category
- ownership

So any expense on the same day can mark the wrong recurring occurrence as paid.

Required fix:

- reuse `OccurrenceConflictResolver`
- or implement equivalent matching:
  - same merchant key
  - same currency
  - amount within tolerance
  - purchase-compatible transaction type
  - date window

## 4. Raw millis day math remains

In Phase 5:

- `expenseDayStart + 86_400_000L`
- `dueDate - days * 86_400_000L`

Replace with `TimePeriodUtils`.

## 5. Reminder delivery dedupe is not race-safe

`RecurringReminderDelivery` index is not unique:

```kotlin
Index(value = ["occurrenceId", "reminderWindow"])
```

DAO insert uses plain `@Insert`.

Required fix:

```kotlin
Index(value = ["occurrenceId", "reminderWindow"], unique = true)
```

and use:

```kotlin
@Insert(onConflict = OnConflictStrategy.IGNORE)
```

## 6. Materialization is not transactional

`RecurringOccurrenceMaterializer.materialize()` loops over occurrence insert/update and reminder insert without transaction.

Required fix:

- run materialization inside `database.withTransaction`
- or expose DAO transaction method

## 7. No reminder worker yet

`getDueReminders()` exists, but there is no completed dispatch worker path.

Still missing:

- WorkManager worker
- notification dispatch
- SENT state update
- dismiss/snooze action path
- dedup under concurrent runs

## 8. No lifecycle event ledger

The plan required `recurring_lifecycle_events`.

Current Phase 5 has:

- occurrences
- reminder deliveries

but no recurring event ledger.

## 9. Planned-vs-actual still incomplete

Planned fields added:

- `sourceOccurrenceKey`
- `sourceRecurringRuleId`

But still missing:

- planned status
- linked actual expense ID
- merchant key
- updatedAt
- drift detection
- transaction lifecycle reconciliation with planned expenses
- linking occurrence to generated planned expense

## 10. Forecast/cashflow not migrated

The central occurrence system is not yet the source of truth for:

- `SynthesisEngine`
- `CashFlowCalculator`
- `FinancialStressForecastEngine`
- `MonthlySavingsSweepUseCase`
- `ForecastInputAssembler`

So the original double-count and ad-hoc expansion risks mostly remain.

## Phase 5 conclusion

**Phase 5 is not closeable yet. It is Phase 5 PR1/Foundation.**

---

# Clean-State Recommendation

Before proceeding to the next phases, do one stabilization commit/PR only.

Call it something like:

`Phase 1–5 stabilization closeout`

## Required checklist

### Build/Schema

- [ ] `:app:compileDebugKotlin`
- [ ] `:app:kaptDebugKotlin`
- [ ] Room schema validation
- [ ] migration tests for 94→100 path
- [ ] Hilt graph check

### Phase 1

- [ ] remove email EUR fallback
- [ ] no lifecycle creation silently defaults currency

### Phase 2

- [ ] remove all logical `86_400_000L` day math from transaction/recurring code
- [ ] recurrence reminders use `TimePeriodUtils.addDays`

### Phase 3

- [ ] fix `STRICT_EXTERNAL_ID`
- [ ] make `BULK_IMPORT` use standard dedupe
- [ ] make update/delete transactional
- [ ] safe snapshot JSON
- [ ] review not-mine validation
- [ ] duplicate candidate lookup returns reliable ID

### Phase 4

- [ ] decide asset owner; no double-copy
- [ ] complete email lifecycle with `EmailReceiptSource`
- [ ] no email EUR fallback
- [ ] duplicate receipt row policy
- [ ] transactional receipt delete
- [ ] relink check inside transaction

### Phase 5

- [ ] add DI bindings for expander/resolver
- [ ] fix expansion anchor to rule.nextDate
- [ ] safe expense-to-occurrence matching
- [ ] unique reminder delivery constraint
- [ ] transactional materialization
- [ ] add reminder worker or explicitly mark reminders as foundation-only
- [ ] recurring lifecycle event table
- [ ] planned-vs-actual minimum link path
- [ ] migrate at least one forecast/cashflow consumer to prove the occurrence source of truth

---

# Final decision

Current state is **not clean enough to proceed**.

Recommended status:

| Phase | Status |
|---|---|
| Phase 1 Currency | Almost done; email fallback still blocks clean close |
| Phase 2 Time | Mostly done; raw millis regressions block clean close |
| Phase 3 Transaction Lifecycle | Improved foundation; not fully closed |
| Phase 4 Receipt Lifecycle | Improved foundation; not fully closed |
| Phase 5 Recurring/Planned/Reminder | Foundation only; not closed |

If you want a safe next step, do **one narrow stabilization commit** addressing the checklist above, then run compile/tests. After that, we can re-review and create the implementation plan for the next phase from a genuinely clean baseline.

---

# Sources checked

- PR commits page: https://github.com/panospao7/Cost-agregator/pull/4/commits
- Latest visible fix commit `b10c136`: https://github.com/panospao7/Cost-agregator/commit/b10c136dbff90eba7eeb1584da8230abe26a0c55
- Phase 5 commit `1ea5eb2`: https://github.com/panospao7/Cost-agregator/commit/1ea5eb2a2def45d77fdb53a9d66aa451b761943b
- TransactionLifecycleCoordinator at `b10c136`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b10c136dbff90eba7eeb1584da8230abe26a0c55/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- ReceiptLifecycleCoordinator at `b10c136`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b10c136dbff90eba7eeb1584da8230abe26a0c55/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- ReceiptLinkService at `b10c136`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b10c136dbff90eba7eeb1584da8230abe26a0c55/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- RecurringLifecycleCoordinator at `b10c136`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b10c136dbff90eba7eeb1584da8230abe26a0c55/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- RecurringOccurrenceExpander at `b10c136`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b10c136dbff90eba7eeb1584da8230abe26a0c55/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt
- RecurringOccurrenceMaterializer at `b10c136`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b10c136dbff90eba7eeb1584da8230abe26a0c55/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt
- RecurringReminderDelivery at `b10c136`: https://raw.githubusercontent.com/panospao7/Cost-agregator/b10c136dbff90eba7eeb1584da8230abe26a0c55/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringReminderDelivery.kt