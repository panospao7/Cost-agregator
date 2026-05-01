# Review of commit `448a51d`

Commit reviewed:

- `448a51d` — Phase 5 + Phase 1–4 review fixes
- URL: https://github.com/panospao7/Cost-agregator/commit/448a51d

Static GitHub review only. I did not run Gradle/Room migration tests locally.

---

# Executive verdict

`448a51d` is a strong stabilization commit. It fixes several previously flagged problems:

- Hilt `@Inject` added to recurrence expander/resolver.
- Recurrence expansion now uses `rule.nextDate` / anchor semantics.
- Recurring materialization is wrapped in a DB transaction.
- Reminder delivery index is now unique.
- Reminder insert uses `OnConflictStrategy.IGNORE`.
- Recurring expense-to-occurrence matching now checks merchant, amount, currency, type, ownership.
- Raw `86_400_000L` in the touched recurrence paths was replaced with `TimePeriodUtils`.
- `TransactionLifecycleCoordinator.createExpense()` is transactional.
- Update/delete transaction events are transactional.
- Snapshots now use `JSONObject`.
- Bulk import dedupe is restored.
- Receipt asset double-persistence was mostly removed.
- Email ingestion now routes expense creation through `TransactionLifecycleCoordinator` and links receipts through `ReceiptLinkService`.

However, I would **not proceed to the next phases yet**. There is one critical blocker and several still-open lifecycle issues.

---

# Critical blocker: Room schema/version problem

## Problem

The commit changes the schema for `planned_expenses` and `recurring_reminder_deliveries`, but the database version remains:

```kotlin
APP_DATABASE_SCHEMA_VERSION = 100
```

In `100.json`, `planned_expenses` now has new fields:

- `status`
- `linkedActualExpenseId`
- `merchantKey`
- `updatedAt`

And `recurring_reminder_deliveries` now has a unique index on:

```text
occurrenceId, reminderWindow
```

But `AppDatabase.MIGRATION_96_100` only appears to add:

- `sourceOccurrenceKey`
- `sourceRecurringRuleId`

I did **not** find migration SQL for:

- `planned_expenses.status`
- `planned_expenses.linkedActualExpenseId`
- `planned_expenses.merchantKey`
- `planned_expenses.updatedAt`

Also, changing an index from non-unique to unique with the same DB version is not safe for existing version-100 installs.

## Why this matters

This can cause:

1. Room schema validation failure after migration.
2. Existing dev/test installs at version 100 not receiving the new columns.
3. Runtime crash because the expected schema hash changed without a version bump.
4. Unique-index creation failure if duplicate reminder deliveries already exist.

## Required fix

Preferred: bump DB version to `101`.

Add:

```kotlin
MIGRATION_100_101
```

It should:

1. Add planned columns:
   ```sql
   ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED';
   ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER;
   ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT;
   ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0;
   ```

2. Deduplicate reminder deliveries before unique index:
   ```sql
   DELETE FROM recurring_reminder_deliveries
   WHERE id NOT IN (
       SELECT MIN(id)
       FROM recurring_reminder_deliveries
       GROUP BY occurrenceId, reminderWindow
   );
   ```

3. Replace old index:
   ```sql
   DROP INDEX IF EXISTS index_recurring_reminder_deliveries_occurrenceId_reminderWindow;
   CREATE UNIQUE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_occurrenceId_reminderWindow
   ON recurring_reminder_deliveries (occurrenceId, reminderWindow);
   ```

4. Add Room schema `101.json`.

Also consider adding:

```kotlin
@ColumnInfo(defaultValue = "0")
val updatedAt: Long = 0L
```

to `PlannedExpense.updatedAt` so entity schema and migration default are aligned.

This is the main thing blocking a clean baseline.

---

# Phase 1 — Currency

## Status

Mostly acceptable, but not perfectly closed.

## Good

- Transaction creation validates currency shape.
- Email receipt coordinator now tries home currency instead of silent EUR in the main email path.
- Recurring/planned projection carries occurrence currency into planned rows.

## Remaining issues

### 1. `ReceiptLifecycleCoordinator` still has `FALLBACK_CURRENCY = "EUR"`

Manual OCR fallback still does:

```kotlin
getOrDefault(FALLBACK_CURRENCY)
```

This is better than always using EUR, but it is still a silent final fallback.

Recommended:

- if home currency cannot be resolved, fail with a review-required state
- or store an explicit currency assumption field
- avoid silent EUR in lifecycle code

### 2. Some UI/recommendation text still hardcodes `€`

Examples remain in subscription/negotiation recommendation descriptions. This is less severe than lifecycle storage, but it is still a currency UX debt.

## Phase 1 verdict

**Near closed**, but remove/document the final EUR fallback and clean hardcoded display symbols later.

---

# Phase 2 — Time / Period Semantics

## Status

Mostly good, with a few remaining regressions.

## Good

- Transaction future-date validation now uses `TimePeriodUtils.addDays`.
- Recurrence expansion uses `TimePeriodUtils.addDays/addMonths/addYears`.
- Recurring link day range uses `getStartOfDay/getEndOfDay`.
- Reminder scheduled date uses `TimePeriodUtils.addDays`.

## Remaining issues

### 1. Email receipt duplicate lookback uses raw millis

`EmailReceiptIngestionService.findExistingScannedReceipt()` still uses:

```kotlin
timeProvider.now() - (30L * 24 * 60 * 60 * 1000)
```

Use:

```kotlin
TimePeriodUtils.addDays(timeProvider.now(), -30)
```

### 2. Receipt semantic fingerprint uses UTC-ish millis day bucket

`ReceiptDuplicateDetector` uses:

```kotlin
date / 86_400_000L
```

For fingerprinting this is less dangerous than period math, but it can mismatch local receipt dates around timezone/DST boundaries. Prefer a local date key via `TimePeriodUtils` or java.time local date.

### 3. Forecast code still has old day/month approximations in places

`ForecastInputAssembler` and `SynthesisEngine` still contain older forecast logic and TODOs. That is more Phase 5/forecast migration than Phase 2, but it means the whole app is not yet fully time-clean.

## Phase 2 verdict

**Mostly closed**, but run a raw-millis audit and fix the remaining logical-date cases.

---

# Phase 3 — Transaction Lifecycle

## Status

Much improved. Still not fully closed.

## Good

`TransactionLifecycleCoordinator` now has:

- transactional create event + insert
- transactional update + event
- transactional delete + event
- `JSONObject` snapshots
- bulk import range dedupe restored
- relaxed ownership validation
- recurring occurrence linking hook after creation

## Remaining issues

### 1. `STRICT_EXTERNAL_ID` still needs stronger semantics

Current behavior:

```kotlin
dedupeKey = "idem:$key"
```

This is better than before, but:

- it is not source-namespaced
- if `idempotencyKey` / `externalFingerprint` is null, strict mode still skips range dedupe
- duplicate retry returns insert conflict, not existing duplicate ID

Recommended:

```kotlin
if (deduplicationMode == STRICT_EXTERNAL_ID && key.isNullOrBlank()) {
    return ValidationFailed(...)
}
dedupeKey = "idem:${request.source.name}:$key"
```

Long-term better:

- add `sourceIdempotencyKey` / `externalFingerprint` column
- unique index on `(source, sourceIdempotencyKey)`

### 2. Duplicate ID lookup is still not fully equivalent to duplicate detection

`isDuplicateCurrencyAware()` has richer fallback logic than `findDuplicateId()`. So duplicate result can still return `-1`.

Better:

- create one DAO method that returns the duplicate candidate row
- use that for both duplicate existence and duplicate ID

### 3. Update lifecycle still lacks pre-update duplicate check

When merchant/date/amount/currency/type changes, the coordinator recomputes dedupe key, but it does not check whether the new key/window conflicts with another existing expense.

Add:

- duplicate check excluding current `expense.id`
- clear failure result or explicit override policy

### 4. Source-specific side effects are still not fully centralized

The dispatcher handles standard side effects, but source-specific behavior still exists in individual paths. That may be acceptable for now, but Phase 3 should document which side effects are intentionally source-owned.

## Phase 3 verdict

**Good foundation, not fully concluded.**

Minimum before close:

1. strict external ID key required + source namespace
2. duplicate candidate DAO unification
3. update duplicate prevention
4. source side-effect matrix documented/enforced

---

# Phase 4 — Receipt Lifecycle

## Status

Better, but still not fully closed.

## Good

- Coordinator no longer pre-copies the asset before `ReceiptRepository.processReceipt`.
- File hash is computed from repository-managed `imagePath`.
- Duplicate receipt rows are now marked `DUPLICATE_DETECTED`.
- Receipt delete is DB-transactional for event/link/row deletion, with asset cleanup post-commit.
- Email ingestion now:
  - checks `EmailReceiptDao` by message ID
  - checks fingerprint
  - creates `EmailReceiptSource`
  - routes expense creation through transaction coordinator
  - links via `ReceiptLinkService`

## Remaining issues

### 1. `ReceiptLinkService` relink check still has a race window

The existing-link check happens before `database.withTransaction`.

Two concurrent calls can both pass and insert two primary links for a non-bank receipt.

Move this inside the transaction:

```kotlin
database.withTransaction {
    val existingLinks = receiptExpenseLinkDao.getLinksForReceipt(receiptId)
    if (!isBankStatement && existingLinks.isNotEmpty() && !allowRelink) error(...)
    insert link
    update legacy receipt
    insert event
}
```

Also consider DB-level enforcement for one primary non-bank receipt link if possible.

### 2. `ReceiptExpenseLinkDao.insert()` uses `REPLACE`

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
```

For lifecycle/audit records, `IGNORE` or explicit update is usually safer. `REPLACE` can delete/reinsert rows and overwrite metadata unexpectedly.

### 3. `ReceiptLifecycleCoordinator.processEmailReceipt()` is still partial

There are now two email paths:

- `EmailReceiptIngestionService.processEmailReceipt()` — fuller path
- `ReceiptLifecycleCoordinator.processEmailReceipt()` — saves only `ScannedReceipt`, does not persist `EmailReceiptSource`, does not auto-create/link expense

This split is risky. Either:

- make coordinator own full email lifecycle, and ingestion service only parses/provider-detects
- or remove/mark coordinator `processEmailReceipt()` as lower-level/partial to avoid accidental use

### 4. Duplicate receipt marking has no duplicate event

The duplicate row is marked `DUPLICATE_DETECTED`, but I did not see a `DUPLICATE_DETECTED` receipt event written in that branch.

Add event with existing receipt ID in metadata.

### 5. Email service still has raw 30-day millis

See Phase 2.

## Phase 4 verdict

**Close to foundation-complete, but not fully concluded.**

Minimum closeout:

1. move relink check inside transaction
2. avoid `REPLACE` for link insert unless intentional
3. unify/clarify email lifecycle ownership
4. add duplicate-detected event
5. remove raw millis from email dedupe lookback

---

# Phase 5 — Recurring / Planned / Reminder Lifecycle

## Status

Foundation improved, but Phase 5 is still not complete.

## Good fixes

- `RecurringOccurrenceExpander @Inject constructor()` added.
- `OccurrenceConflictResolver @Inject constructor()` added.
- Expansion now has `anchorDate`.
- Coordinator advances from `rule.nextDate`.
- Expense-to-occurrence linking checks:
  - same day
  - merchant key
  - amount tolerance
  - currency
  - skips `isNotMine`, transfer, deposit
- Materializer uses `database.withTransaction`.
- Reminder delivery unique index added.
- Reminder insert now ignores conflicts.
- Planned fields added:
  - status
  - linkedActualExpenseId
  - merchantKey
  - updatedAt
- `RecurringPlanProjectionService` fixed today-start filtering.
- Subscription cost-per-use and monthly total were improved.

## Major remaining Phase 5 issues

### 1. Room migration/version blocker

This is the top issue. See the critical section above.

### 2. No `BillReminderWorker` yet

`BillReminderManager` still says the WorkManager worker is future work.

So reminder persistence exists, but proactive reminder dispatch is not implemented.

Still missing:

- worker
- scheduler
- notification send
- mark delivery `SENT`
- dismiss/snooze action handling

### 3. No recurring lifecycle event ledger

The Phase 5 plan called for `recurring_lifecycle_events`.

Current code has:

- `recurring_occurrences`
- `recurring_reminder_deliveries`

But no event ledger for:

- occurrence generated
- occurrence paid
- reminder scheduled/sent
- planned generated
- drift detected

### 4. Forecast/cashflow are not migrated to occurrence source of truth

`ForecastInputAssembler` still has the TODO saying it independently merges recurring patterns and planned expenses, causing double-count risk.

`SynthesisEngine` still consumes `RecurringPattern` and `PlannedExpense` separately.

So the original double-count risk is explicitly still present.

### 5. Planned-vs-actual is partial

Good:

- planned rows now have `linkedActualExpenseId`
- DAO has `linkToActualExpense`

Still missing:

- planned actual reconciliation service
- transaction lifecycle hook to fulfill planned expense
- drift metrics
- `RecurringOccurrence.linkedPlannedExpenseId`
- projection setting `merchantKey` and `updatedAt`

`RecurringPlanProjectionService` creates planned rows but does not set:

- `merchantKey`
- `updatedAt`

### 6. Direct DAO leaks are not fully closed

`SmartBillNegotiationEngine` still injects:

```kotlin
ManualRecurringExpenseDao
```

`ManualExpenseRepository` still directly calls:

```kotlin
database.manualRecurringExpenseDao().insert(...)
```

This is better than using the deprecated DAO, but still bypasses the recurring lifecycle/repository abstraction.

### 7. Subscription recommendation savings still use raw period amount

Some recommendation calculations still use:

```kotlin
subscription.amount * 0.5
```

For quarterly/annual subscriptions, this is not monthly-normalized.

Also text still hardcodes `€`.

## Phase 5 verdict

**Phase 5 foundation is improving, but not closed.**

Minimum closeout:

1. fix Room version/migration
2. add reminder worker/scheduler or explicitly mark reminders as foundation-only
3. add recurring lifecycle event table
4. migrate at least one forecast/cashflow path to occurrences
5. implement planned-vs-actual reconciliation
6. remove remaining recurring DAO leaks
7. normalize subscription recommendation savings

---

# Clean-state decision

## Can we proceed to next phases now?

**No — not yet.**

The biggest reason is the Room migration/version problem. Even if the logic is mostly okay, the current schema state is not safe.

## Recommended next commit

Create one narrow commit:

`Phase 1–5 stabilization closeout`

Do only stabilization, no new features.

Checklist:

### Build/schema

- [ ] bump DB to `101`
- [ ] add `MIGRATION_100_101`
- [ ] add `101.json`
- [ ] migration test from previous schema to latest
- [ ] dedupe old reminder deliveries before unique index
- [ ] add planned column migration defaults

### Phase 1/2

- [ ] remove final silent EUR fallback or document as explicit assumption
- [ ] replace email 30-day raw millis
- [ ] replace receipt semantic UTC-day bucket if it affects local matching

### Phase 3

- [ ] require strict external ID key
- [ ] namespace strict external dedupe key by source
- [ ] unify duplicate candidate query
- [ ] duplicate-check updates that change key fields

### Phase 4

- [ ] move receipt relink check inside transaction
- [ ] reconsider `REPLACE` on receipt link insert
- [ ] consolidate/clarify email lifecycle ownership
- [ ] write duplicate-detected receipt event

### Phase 5

- [ ] add recurring lifecycle events
- [ ] add reminder worker or explicitly defer it
- [ ] planned-vs-actual reconciliation hook
- [ ] forecast/cashflow occurrence migration
- [ ] remove `SmartBillNegotiationEngine` DAO injection
- [ ] route manual recurring creation through lifecycle/repository

---

# Current phase status table

| Phase | Status after `448a51d` |
|---|---|
| Phase 1 Currency | Mostly done; minor fallback/display issues |
| Phase 2 Time | Mostly done; a few raw day-math leftovers |
| Phase 3 Transaction Lifecycle | Strong foundation; not fully closed |
| Phase 4 Receipt Lifecycle | Stronger foundation; link/email cleanup remains |
| Phase 5 Recurring/Planned/Reminder | Foundation only; schema blocker + missing worker/events/forecast migration |

---

# Validation commands to run before next phase

```bash
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:kaptDebugKotlin
./gradlew.bat :app:testDebugUnitTest
```

If you have migration tests:

```bash
./gradlew.bat :app:connectedDebugAndroidTest
```

Also run grep/audit checks for:

- `86_400_000`
- `24 * 60 * 60 * 1000`
- `System.currentTimeMillis()` outside whitelist
- direct `ManualRecurringExpenseDao` injection
- direct `ScannedReceiptDao` usage outside approved classes
- direct `expenseDao.insert*` outside transaction lifecycle
- `@Insert(onConflict = REPLACE)` on lifecycle link/audit tables
- hardcoded `"EUR"` in lifecycle creation paths

---

# Sources reviewed

- Commit `448a51d`: https://github.com/panospao7/Cost-agregator/commit/448a51d
- `TransactionLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `ReceiptLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
- `ReceiptLinkService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
- `RecurringOccurrenceExpander.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/domain/recurring/RecurringOccurrenceExpander.kt
- `RecurringLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- `RecurringOccurrenceMaterializer.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringOccurrenceMaterializer.kt
- `PlannedExpense.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/data/database/entity/PlannedExpense.kt
- `AppDatabase.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
- `EmailReceiptIngestionService.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- `BillReminderManager.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt
- `ForecastInputAssembler.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/448a51d/app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt