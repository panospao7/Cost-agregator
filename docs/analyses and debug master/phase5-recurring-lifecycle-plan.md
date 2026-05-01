# Phase 5 — Recurring / Planned / Reminder Lifecycle Foundation — Final Implementation Plan

> **Verdict on template plan:** **Option A — Endorse with refinements.**
> The `phase5-recurring-planned-reminder-lifecycle-plan.md` is comprehensive and architecturally sound.
> This document keeps its structure but consolidates PRs (13 → 9), reorders the schema, hardens
> the occurrence key design, adds explicit integration with the Phase 3 `TransactionLifecycleCoordinator`,
> and defers UI/inome/guardrail PRs to a Phase 5b to keep the critical path tight.

---

## Decisions

### Decision 1: Occurrences — their own entity/table

**Chosen:** Persistent `recurring_occurrences` table with stable `occurrenceKey`.

**Why not on-the-fly expansion only:**
- Without persistent identity, reminder dedup is impossible.
- Without persistent identity, planned-vs-actual linking cannot work.
- Without persistent identity, each consumer reimplements expansion (the audit found 4 ad-hoc reimplementations).

**Key design constraint:** The `occurrenceKey` must be deterministic and collision-proof. Proposed format:
```
ruleId|normalizedDueDateStart|frequency
```
`normalizedDueDateStart` = `TimePeriodUtils.getStartOfDay(dueDate)`.  For virtual/detected patterns with no `ruleId`, use `patternSignature|normalizedDueDateStart|frequency` where `patternSignature = sha256(merchantKey, currency, frequency.ordinal).take(12)`.
Do NOT include `amount` in the key — amounts can be edited by the user; the key must stay stable.

### Decision 2: Reminder state persistence

**Chosen:** Separate `recurring_reminder_deliveries` table (one row per occurrence × reminder window).  
NOT inline on the occurrence row, because one occurrence needs multiple reminder windows.

**Why not a single `reminderSent` boolean on the occurrence:**  
An occurrence can be reminded at 14d, 7d, 3d, 1d, due-day, and overdue. Each delivery must be tracked independently.

### Decision 3: Planned expenses from recurring patterns

**Chosen:** Opt-in generation via `RecurringPlanProjectionService`, disabled by default.  
When enabled, planned rows get `sourceType`, `sourceOccurrenceKey`, `sourceRecurringRuleId` FKs.

**Why not mandatory:**  
Existing users may have manually created planned expenses. Forcing automated generation would create duplicates. Opt-in with clear UX is safer.

### Decision 4: PR count and merge candidates

**Chosen:** 9 PRs (down from 13).
Merges:
- PR 0 (baseline) + PR 1 (math contract) → **PR A** — no behavioral change, pure documentation + hardening.
- PR 3 (expander) + PR 4 (resolver) → **PR C** — both are pure domain logic with no persistence; the resolver needs the expander.
- PR 7 (forecast migration) + PR 8 (planned lifecycle) → **PR F** — both migrate consumers; planned generation is useless if forecasts still use old patterns.
- PR 11 (UI), PR 12 (income), PR 13 (guardrails) → deferred to **Phase 5b**. UI can be updated incrementally; income is a stretch goal; guardrails belong in a final sweep.

### Decision 5: Execution order

**Chosen:** Math & docs → Schema → Repository consolidation → Pure expander + resolver → Coordinator + materializer → Consumer migration (forecast + planned + reminders) → Subscription fixes → Integration hook into TransactionLifecycleCoordinator.

**Rationale:** Schema MUST come before the coordinator (it needs tables to persist into). The pure expander/resolver can come before schema (they work on in-memory data). Repository consolidation should come early because every downstream PR depends on the right data access path.

---

## Scope

- **In:** Recurring occurrence identity, persistent reminder state, centralized occurrence expansion, conflict resolution (recurring vs actual vs planned), WorkManager-based reminder scheduling, forecast/cashflow migration to single expansion source, subscription math fixes, DAO/repository consolidation, planned-expense lifecycle linking.
- **Out:** UI redesign, bill payment execution, external subscription cancellation, real `Expense` auto-creation from rules, income recurrence unification (deferred to Phase 5b), guardrail enforcement (deferred to Phase 5b).

## Files Summary

| Category | Files |
|---|---|
| **Create (new domain/logic)** | `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`, `domain/recurring/expansion/RecurringOccurrenceExpander.kt`, `domain/recurring/resolution/RecurringConflictResolver.kt`, `domain/recurring/materialization/RecurringOccurrenceMaterializer.kt`, `domain/recurring/planning/RecurringPlanProjectionService.kt`, `domain/recurring/planning/PlannedActualReconciliationService.kt`, `domain/recurring/reminder/RecurringReminderScheduler.kt`, `domain/recurring/reminder/BillReminderWorker.kt`, `domain/recurring/rule/RecurringRuleLike.kt`, `domain/recurring/rule/RecurringRuleRepository.kt`, `domain/recurring/occurrence/RecurringOccurrence.kt` |
| **Create (new data/entity)** | `data/database/entity/RecurringOccurrenceEntity.kt`, `data/database/entity/RecurringReminderDeliveryEntity.kt`, `data/database/entity/RecurringLifecycleEventEntity.kt` |
| **Create (new data/dao)** | `data/database/dao/RecurringOccurrenceDao.kt`, `data/database/dao/RecurringReminderDeliveryDao.kt`, `data/database/dao/RecurringLifecycleEventDao.kt` |
| **Create (new DI module)** | `di/RecurringLifecycleModule.kt` |
| **Modify (core)** | `data/database/AppDatabase.kt` (migration 96→100, new DAOs, new entities), `data/database/entity/ManualRecurringExpense.kt`, `data/database/entity/PlannedExpense.kt`, `data/database/dao/ManualRecurringExpenseDao.kt`, `data/database/dao/PlannedExpenseDao.kt`, `domain/model/RecurrenceFrequency.kt`, `domain/logic/RecurrenceCalculator.kt` |
| **Modify (consumers)** | `domain/forecasting/FinancialStressForecastEngine.kt`, `domain/logic/SynthesisEngine.kt`, `domain/cashflow/CashFlowCalculator.kt`, `domain/forecasting/ForecastInputAssembler.kt`, `domain/forecasting/MergedRecurringPatternsProvider.kt`, `domain/savings/MonthlySavingsSweepUseCase.kt`, `data/repository/FinancialWeatherRepository.kt`, `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt` |
| **Modify (reminders)** | `domain/reminder/BillReminderManager.kt` |
| **Modify (subscriptions)** | `domain/subscription/SubscriptionManagerEngine.kt`, `ui/screens/subscription/SubscriptionManagementViewModel.kt`, `domain/negotiation/SmartBillNegotiationEngine.kt` |
| **Modify (repos/DAO)** | `data/repository/RecurringExpenseRepository.kt`, `data/repository/ManualRecurringExpenseRepository.kt`, `data/repository/ManualExpenseRepository.kt`, `di/DaoModule.kt` |
| **Modify (transaction lifecycle)** | `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt` (add occurrence matching side effect) |

---

## Implementation Steps — 9 PRs

---

### PR A — Baseline, Docs, and Recurrence Math Contract (merged PR 0+1)

**Objective:** Record current behavior. Harden `RecurrenceCalculator` and `RecurrenceFrequency` with tests and KDoc. Make deprecated `days`/`intervalInMs` properties strictly forbidden for production code. Zero behavioral change.

**What must exist at end:**
1. `docs/development/RECURRING_LIFECYCLE.md` with the audit checklist from the audit document.
2. `RecurrenceFrequency.kt`: `@Deprecated` on `days` and `intervalInMs` upgraded with explicit KDoc stating they MUST NOT be used in production code paths. Add `companion object` with `EXPANSION_SAFE` set documenting which frequencies can be expanded.
3. `RecurrenceCalculator.kt`: KDoc added; `addFrequencyInterval()` verified to use `TimePeriodUtils.addDays()` for WEEKLY/BIWEEKLY and `TimePeriodUtils.addMonths()` for MONTHLY+; no raw millis arithmetic at all.
4. Unit tests covering all expansion edge cases from §7.2 of the template plan: DST spring-forward, DST fall-back, Jan 31 monthly, March 31 monthly, Feb 29 annual, range boundary inclusion/exclusion, max-occurrence guard.
5. Existing tests pass; no behavioral change.

**Files:**
- `domain/model/RecurrenceFrequency.kt` — update deprecation messages
- `domain/logic/RecurrenceCalculator.kt` — add KDoc, verify logic (already correct, just document)
- `domain/util/TimePeriodUtils.kt` — verify `addDays`/`addMonths` correctness (already solid from Phase 2)
- New: test file for recurrence edge cases
- New: `docs/development/RECURRING_LIFECYCLE.md`

**Risk:** Low. Pure documentation + test hardening.

---

### PR B — Schema Foundation (migration 96→100, new tables + column additions)

**Objective:** Add persistent occurrence identity, reminder state, and lifecycle events. Update existing tables with linking columns. One migration (96→97 for occurrences, 97→98 for reminder deliveries, 98→99 for lifecycle events, 99→100 for planned/manual recurring column additions — consolidated into a single 96→100 migration for simplicity, or kept as individual migrations).

> **IMPORTANT:** The migration must be backward-compatible. All new columns are nullable or have safe defaults. No data is deleted. The old `nextDate` column stays on `manual_recurring_expenses` for compatibility.

**Migration 96→97: Create `recurring_occurrences` table**

```sql
CREATE TABLE recurring_occurrences (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    occurrenceKey TEXT NOT NULL,
    ruleId INTEGER,
    sourceType TEXT NOT NULL DEFAULT 'UNKNOWN',
    merchant TEXT NOT NULL,
    merchantKey TEXT,
    amount REAL NOT NULL,
    currency TEXT NOT NULL DEFAULT 'EUR',
    frequency TEXT NOT NULL,
    dueDate INTEGER NOT NULL,
    categoryId INTEGER,
    confidence REAL NOT NULL DEFAULT 1.0,
    status TEXT NOT NULL DEFAULT 'PENDING',
    linkedExpenseId INTEGER,
    linkedPlannedExpenseId INTEGER,
    dateVarianceDays INTEGER,
    amountVariancePercent REAL,
    generatedAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX index_recurring_occurrences_occurrenceKey ON recurring_occurrences (occurrenceKey);
CREATE INDEX index_recurring_occurrences_ruleId_dueDate ON recurring_occurrences (ruleId, dueDate);
CREATE INDEX index_recurring_occurrences_dueDate_status ON recurring_occurrences (dueDate, status);
CREATE INDEX index_recurring_occurrences_linkedExpenseId ON recurring_occurrences (linkedExpenseId);
CREATE INDEX index_recurring_occurrences_linkedPlannedExpenseId ON recurring_occurrences (linkedPlannedExpenseId);
CREATE INDEX index_recurring_occurrences_merchantKey ON recurring_occurrences (merchantKey);
```

**Migration 97→98: Create `recurring_reminder_deliveries` table**

```sql
CREATE TABLE recurring_reminder_deliveries (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    occurrenceId INTEGER NOT NULL,
    occurrenceKey TEXT NOT NULL,
    reminderType TEXT NOT NULL,
    notificationId INTEGER,
    sentAt INTEGER,
    dismissedAt INTEGER,
    snoozedUntil INTEGER,
    actionedAt INTEGER,
    status TEXT NOT NULL DEFAULT 'PENDING'
);
CREATE UNIQUE INDEX index_reminder_deliveries_occurrenceKey_reminderType ON recurring_reminder_deliveries (occurrenceKey, reminderType);
CREATE INDEX index_reminder_deliveries_sentAt ON recurring_reminder_deliveries (sentAt);
CREATE INDEX index_reminder_deliveries_snoozedUntil ON recurring_reminder_deliveries (snoozedUntil);
CREATE INDEX index_reminder_deliveries_status ON recurring_reminder_deliveries (status);
```

**Migration 98→99: Create `recurring_lifecycle_events` table**

```sql
CREATE TABLE recurring_lifecycle_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    ruleId INTEGER,
    occurrenceId INTEGER,
    occurrenceKey TEXT,
    eventType TEXT NOT NULL,
    source TEXT,
    occurredAt INTEGER NOT NULL,
    oldStatus TEXT,
    newStatus TEXT,
    linkedExpenseId INTEGER,
    linkedPlannedExpenseId INTEGER,
    metadataJson TEXT,
    reason TEXT
);
CREATE INDEX index_lifecycle_events_ruleId ON recurring_lifecycle_events (ruleId);
CREATE INDEX index_lifecycle_events_occurrenceId ON recurring_lifecycle_events (occurrenceId);
CREATE INDEX index_lifecycle_events_occurredAt ON recurring_lifecycle_events (occurredAt);
CREATE INDEX index_lifecycle_events_eventType ON recurring_lifecycle_events (eventType);
```

**Migration 99→100: Add columns to existing tables**

```sql
-- manual_recurring_expenses: add lifecycle columns (all nullable/defaulted)
ALTER TABLE manual_recurring_expenses ADD COLUMN anchorDate INTEGER;
ALTER TABLE manual_recurring_expenses ADD COLUMN merchantKey TEXT;
ALTER TABLE manual_recurring_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE manual_recurring_expenses ADD COLUMN endedAt INTEGER;
ALTER TABLE manual_recurring_expenses ADD COLUMN lastGeneratedThrough INTEGER;
ALTER TABLE manual_recurring_expenses ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL_RULE';
ALTER TABLE manual_recurring_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE';

-- planned_expenses: add linking columns
ALTER TABLE planned_expenses ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'MANUAL';
ALTER TABLE planned_expenses ADD COLUMN sourceOccurrenceKey TEXT;
ALTER TABLE planned_expenses ADD COLUMN sourceRecurringRuleId INTEGER;
ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER;
ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED';
ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT;
ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0;

-- Indices for new FK/path columns
CREATE INDEX index_planned_expenses_sourceOccurrenceKey ON planned_expenses (sourceOccurrenceKey);
CREATE INDEX index_planned_expenses_sourceRecurringRuleId ON planned_expenses (sourceRecurringRuleId);
CREATE INDEX index_planned_expenses_linkedActualExpenseId ON planned_expenses (linkedActualExpenseId);
CREATE INDEX index_planned_expenses_date_status ON planned_expenses (date, status);
CREATE INDEX index_planned_expenses_merchantKey ON planned_expenses (merchantKey);
```

> **NOTE:** Consolidated 96→100 into one migration for simplicity, or keep as 4 individual migrations. The `ALL_MIGRATIONS` array must be updated. Schema version becomes `100`.

**Backfill (run in migration):**
```sql
-- For manual_recurring_expenses: set anchorDate = nextDate, status = 'ACTIVE' if isActive=1 else 'INACTIVE'
UPDATE manual_recurring_expenses SET anchorDate = nextDate;
UPDATE manual_recurring_expenses SET status = CASE WHEN isActive = 1 THEN 'ACTIVE' ELSE 'INACTIVE' END;
UPDATE manual_recurring_expenses SET updatedAt = COALESCE(createdAt, 0);
```

**What must exist at end:**
1. Four new entities with their `@Entity` annotations.
2. Four new DAOs.
3. Entity + DAO registered in `AppDatabase`.
4. Migration 96→100 registered in `ALL_MIGRATIONS`.
5. Fresh-install callback updated to create new tables.
6. Migration test passes.
7. Existing schema tests pass.

**Files:**
- `data/database/AppDatabase.kt` — new entities, DAOs, migration, version bump to 100, `ALL_MIGRATIONS` update
- `data/database/entity/RecurringOccurrenceEntity.kt` — create
- `data/database/entity/RecurringReminderDeliveryEntity.kt` — create
- `data/database/entity/RecurringLifecycleEventEntity.kt` — create
- `data/database/dao/RecurringOccurrenceDao.kt` — create
- `data/database/dao/RecurringReminderDeliveryDao.kt` — create
- `data/database/dao/RecurringLifecycleEventDao.kt` — create
- `data/database/entity/ManualRecurringExpense.kt` — add `anchorDate`, `merchantKey`, `updatedAt`, `endedAt`, `lastGeneratedThrough`, `source`, `status`
- `data/database/entity/PlannedExpense.kt` — add `sourceType`, `sourceOccurrenceKey`, `sourceRecurringRuleId`, `linkedActualExpenseId`, `status`, `merchantKey`, `updatedAt`
- `data/database/dao/PlannedExpenseDao.kt` — add `getById`, `getByIdFlow`, `update`, `linkActualExpense`, `getByOccurrenceKey`, `getForPeriodOneShot`, `getOpenPlannedForPeriod`
- `di/DaoModule.kt` — add provider methods for new DAOs
- `data/database/dao/ManualRecurringExpenseDao.kt` — add query methods for new columns (`getByMerchantKey`, `updateMerchantKey`, `updateNextDateAndAnchor`)

**Risk:** Medium. Schema migrations touching 2 existing tables. Must be tested against existing user databases. No data destruction — all new columns are nullable/defaulted.

> **Fallback:** If migration fails on any device, Room's migration path will throw `IllegalStateException`. The app will fall back to the legacy behavior (existing tables still work). We do NOT use `fallbackToDestructiveMigration()`.

---

### PR C — Repository Consolidation + Direct DAO Leak Fix (PR 2 from template)

**Objective:** Create a single `RecurringRuleRepository` as the one access path for `ManualRecurringExpense`. Migrate all consumers away from the deprecated `RecurringExpenseDao`. Fix direct DAO leaks.

**Architecture:**
```
RecurringRuleRepository (NEW — single source of truth)
  └── ManualRecurringExpenseDao (injected, NOT exposed beyond repository)

RecurringExpenseRepository (KEPT as thin delegator → RecurringRuleRepository)
  └── migrate consumers gradually, then remove

ManualRecurringExpenseRepository (DELETED — merged into RecurringRuleRepository)
```

**What `RecurringRuleRepository` must provide:**
- `getAllActive(): List<ManualRecurringExpense>` (one-shot)
- `getAllActiveFlow(): Flow<List<ManualRecurringExpense>>`
- `getActiveSubscriptions(): List<ManualRecurringExpense>`
- `getById(id: Long): ManualRecurringExpense?`
- `insert(expense): Long`
- `update(expense)`
- `deactivate(id: Long)`
- `updateNextDate(id: Long, nextDate: Long)`
- `backfillMerchantKeys()` — runs `MerchantKeyGenerator` on all rows with null `merchantKey`

**Direct DAO leak fixes:**
1. `SmartBillNegotiationEngine.kt` line 124: Replace `recurringExpenseDao.getAll()` with `RecurringRuleRepository.getAllActive()`.
2. `ManualExpenseRepository.kt` line 198: Replace `database.recurringExpenseDao()` insert with `RecurringRuleRepository.insert()`.
3. `RecurringIncomeTracker.kt`: Wrap `ExpenseDao` access behind a query port (deferred to Phase 5b if too risky; at minimum add `@Suppress` with TODO).

**Consumer migration list (update these files to use `RecurringRuleRepository`):**
- `BillReminderManager.kt` — currently injects `RecurringExpenseRepository`; switch to `RecurringRuleRepository`
- `RecurringExpenseEngine.kt` — same
- `MergedRecurringPatternsProvider.kt` — same
- `FinancialWeatherRepository.kt` — same
- `CalculateFinancialForecastUseCase.kt` — same
- `MonthlySavingsSweepUseCase.kt` — same
- `ManualRecurringExpenseViewModel.kt` — currently injects `ManualRecurringExpenseRepository`; switch to `RecurringRuleRepository`

**Make `RecurringExpenseRepository` a thin delegator:**
```kotlin
@Singleton
class RecurringExpenseRepository @Inject constructor(
    private val ruleRepo: RecurringRuleRepository  // NOT the DAO
) {
    fun getAllFlow() = ruleRepo.getAllActiveFlow()
    suspend fun getAll() = ruleRepo.getAllActive()
    // ... delegate all methods
}
```
This keeps existing consumers compiling while they are migrated in PR F.

**Remove `ManualRecurringExpenseRepository` entirely** — its 29 lines are fully replaced by `RecurringRuleRepository`.

**What must exist at end:**
1. `RecurringRuleRepository` created and injected.
2. `ManualRecurringExpenseRepository` deleted.
3. `RecurringExpenseRepository` is a thin delegator to `RecurringRuleRepository` (marked `@Deprecated`).
4. All 8 consumers migrated to use `RecurringRuleRepository` directly, OR continue using the delegator temporarily.
5. Direct DAO access in `SmartBillNegotiationEngine` and `ManualExpenseRepository` replaced with repository calls.
6. `DI/DaoModule.kt` updated — may still expose both DAOs temporarily, but `RecurringRuleRepository` is the only injectable for domain code.

**Files:**
- Create: `domain/recurring/rule/RecurringRuleRepository.kt`
- Modify: `data/repository/RecurringExpenseRepository.kt` — delegator
- Delete: `data/repository/ManualRecurringExpenseRepository.kt`
- Modify: `domain/negotiation/SmartBillNegotiationEngine.kt` — DAO leak fix
- Modify: `data/repository/ManualExpenseRepository.kt` — DAO leak fix
- Modify: `di/DaoModule.kt` — add `RecurringRuleRepository` provider (optional; @Inject constructor + @Singleton handles it)
- Modify: `domain/reminder/BillReminderManager.kt` — switch injection
- Modify: `domain/logic/RecurringExpenseEngine.kt`
- Modify: `domain/forecasting/MergedRecurringPatternsProvider.kt`
- Modify: `data/repository/FinancialWeatherRepository.kt`
- Modify: `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
- Modify: `domain/savings/MonthlySavingsSweepUseCase.kt` (if it uses recurring repository)
- Modify: UI ViewModels (if they inject repositories directly)

**Risk:** Medium-High. Touches many consumer files. Each consumer migration should be a separate commit within the PR. Rollback plan: keep `RecurringExpenseDao` wired in DI until all consumers are confirmed migrated.

---

### PR D — Pure Occurrence Expander + Conflict Resolver (merged PR 3+4)

**Objective:** Add centralized, testable ocurrence expansion and conflict resolution as pure domain services (no persistence).

**Domain models (new):**

```kotlin
// domain/recurring/rule/RecurringRuleLike.kt
data class RecurringRuleLike(
    val ruleId: Long?,
    val sourceType: String, // MANUAL_RULE, CONFIRMED_SUBSCRIPTION, DETECTED_PATTERN, etc.
    val merchant: String,
    val merchantKey: String,
    val amount: Double,
    val currency: String,
    val frequency: RecurrenceFrequency,
    val anchorDate: Long,
    val nextDate: Long,
    val categoryId: Long?,
    val confidence: Float,
    val isConfirmed: Boolean, // true = materialize + remind; false = forecast-only
    val isSubscription: Boolean
)

// domain/recurring/occurrence/RecurringOccurrence.kt
data class RecurringOccurrencePreview(
    val occurrenceKey: String,
    val ruleId: Long?,
    val sourceType: String,
    val dueDate: Long,
    val amount: Double,
    val currency: String,
    val merchant: String,
    val merchantKey: String,
    val frequency: RecurrenceFrequency,
    val categoryId: Long?,
    val confidence: Float
)
```

**`RecurringOccurrenceExpander` (pure service):**
```kotlin
fun expand(
    rule: RecurringRuleLike,
    rangeStartInclusive: Long,
    rangeEndExclusive: Long,
    maxOccurrences: Int = 500
): List<RecurringOccurrencePreview>
```
Rules:
1. Normalize range boundaries via `TimePeriodUtils.getStartOfDay()`.
2. Use `RecurrenceCalculator.addFrequencyInterval()` for EVERY step — never raw millis.
3. `IRREGULAR` frequency: expand to at most 1 occurrence (the `nextDate`) only if `isConfirmed` and `nextDate` is within range.
4. Generate `occurrenceKey` as `ruleId ?: signature | normalizedDueDate | frequency.name`.
5. Stop if: next date doesn't advance, exceeds `maxOccurrences`, or goes past `rangeEndExclusive`.
6. Start from `anchorDate` (if available) or `nextDate` rolled back to first occurrence within range.

**`RecurringConflictResolver` (pure service):**
Inputs: `List<RecurringOccurrencePreview>`, `List<ActualExpenseSummary>`, `List<PlannedExpenseSummary>`
Outputs: `List<ResolvedOccurrence>` with:
- `isCoveredByActual: Boolean` — matched to a real expense
- `isCoveredByPlanned: Boolean` — matched to a planned expense
- `linkedExpenseId: Long?`
- `linkedPlannedExpenseId: Long?`
- `contribution: Double` — amount to count in forecast (0 if covered)
- `resolution: ResolutionType` — PAID_BY_ACTUAL, COVERED_BY_PLANNED, DUPLICATE_PLANNED, UNRESOLVED

**Matching policies:**
- **Actual match:** `merchantKey` exact OR fuzzy (same canonical), `currency` exact, date within `±periodVarianceDays` (default 3), amount within `±20%` tolerance.
- **Planned match:** `sourceOccurrenceKey` exact (for generated planned), OR `merchantKey` + date within `±2 days` + `currency` exact.

**What must exist at end:**
1. `RecurringRuleLike` domain model.
2. `RecurringOccurrencePreview` domain model.
3. `RecurringOccurrenceExpander` with full test coverage per §7.2 of template plan.
4. `RecurringConflictResolver` with test coverage for: actual depletes occurrence, planned covers occurrence, recurring+planned counted once, actual+planned counted once, currency mismatch doesn't match, merchant mismatch doesn't match, amount drift recorded.
5. Conversion helpers: `ManualRecurringExpense → RecurringRuleLike`, `RecurringPattern → RecurringRuleLike`.

**Files:**
- Create: `domain/recurring/rule/RecurringRuleLike.kt`
- Create: `domain/recurring/occurrence/RecurringOccurrence.kt`
- Create: `domain/recurring/expansion/RecurringOccurrenceExpander.kt`
- Create: `domain/recurring/resolution/RecurringConflictResolver.kt`
- Create: test files for expander and resolver

**Risk:** Low. Pure domain logic. No persistence. No consumer changes yet.

---

### PR E — Lifecycle Coordinator + Materializer (PR 5+6 merged, but schema already done)

**Objective:** Create the `RecurringLifecycleCoordinator` and `RecurringOccurrenceMaterializer` that persist occurrences and manage their lifecycle.

**`RecurringOccurrenceMaterializer`:**
```kotlin
suspend fun materializeOccurrences(
    horizonStart: Long = timeProvider.now() - 30.days,
    horizonEnd: Long = timeProvider.now() + 365.days
)
```
Flow:
1. Fetch all confirmed active rules from `RecurringRuleRepository`.
2. For each rule, call `RecurringOccurrenceExpander.expand()`.
3. Upsert each occurrence into `recurring_occurrences` (use `INSERT OR REPLACE` on unique `occurrenceKey`).
4. Existing occurrences with `status = PAID` or `status = SKIPPED` are NOT overwritten.
5. Log lifecycle events for newly generated occurrences.

**`RecurringLifecycleCoordinator`:**
```kotlin
// Core lifecycle methods
suspend fun materializeUpcomingOccurrences()                         // calls materializer for default horizon
suspend fun getUpcomingOccurrences(rangeStart, rangeEnd): List<RecurringOccurrenceEntity>
suspend fun getOccurrencesForDateRange(rangeStart, rangeEnd): List<RecurringOccurrenceEntity>
suspend fun markOccurrencePaid(occurrenceId: Long, linkedExpenseId: Long?)
suspend fun markOccurrenceSkipped(occurrenceId: Long)
suspend fun dismissOccurrence(occurrenceId: Long)
suspend fun snoozeOccurrence(occurrenceId: Long, until: Long)
suspend fun deactivateRule(ruleId: Long)                              // set inactive, cancel future pending occurrences
suspend fun reconcileOccurrencesWithActuals(rangeStart, rangeEnd)     // run resolver + update DB links
```

**Integration with `TransactionLifecycleCoordinator` (Phase 3):**
When an expense is created via `TransactionLifecycleCoordinator.createExpense()`, a new `TransactionSideEffect` is dispatched:
```kotlin
class OccurrenceMatchingSideEffect @Inject constructor(
    private val lifecycleCoordinator: RecurringLifecycleCoordinator
) : TransactionSideEffect {
    override suspend fun onExpenseCreated(expense: Expense, source: ExpenseSource) {
        lifecycleCoordinator.reconcileSingleExpense(expense)
    }
}
```
This finds matching occurrences for the newly created expense, marks them `PAID`, and links them.

**What must exist at end:**
1. `RecurringOccurrenceMaterializer` — idempotent, honors existing statuses.
2. `RecurringLifecycleCoordinator` — all core methods.
3. `OccurrenceMatchingSideEffect` registered in the side effect dispatcher.
4. Unit tests: materialize idempotently, deactivated rules stop generating, mark paid updates occurrence, OK key collision on upsert.
5. Integration test: create expense → occurrence auto-matched.

**Files:**
- Create: `domain/recurring/materialization/RecurringOccurrenceMaterializer.kt`
- Create: `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`
- Create: `domain/recurring/lifecycle/OccurrenceMatchingSideEffect.kt`
- Modify: `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt` — register new side effect
- Create: `di/RecurringLifecycleModule.kt` — wire coordinator, materializer, expander, resolver

**Risk:** Medium. First time persistence is touched in the new system. Must handle DB constraint violations gracefully.

---

### PR F — Forecast, Cashflow, and Planned Expense Migration (merged PR 7+8)

**Objective:** Replace all ad-hoc recurrence expansion in forecast/cashflow/planned systems with the centralized coordinator.

**Consumer migrations:**

| File | Current behavior | New behavior |
|---|---|---|
| `FinancialStressForecastEngine.kt` | `calculateRecurringOutflows()` — hardcoded weekly/biweekly millis expansion (line 248-249) | Call `lifecycleCoordinator.getUpcomingOccurrences()`, sum unpaid amounts |
| `SynthesisEngine.kt` | `isRecurringExpected()` — day-by-day matching (line 426-484) + `synthesizeInternal()` — single-date matching | Consume resolved occurrence list from coordinator; committed = confirmed occurrences; likely = high-confidence detected patterns (virtual expansion) |
| `CashFlowCalculator.kt` | Single `nextExpectedDate` matching per day (line 110-118) | Expand confirmed occurrences over cashflow period; add every unpaid occurrence to daily cashflow |
| `ForecastInputAssembler.kt` | Passes raw patterns + planned expenses as unrelated obligations | Use coordinator's resolved occurrence list → `assemble()` takes `List<ResolvedOccurrence>` instead of raw patterns + planned |
| `MergedRecurringPatternsProvider.kt` | Returns raw `RecurringPattern` lists | Add method `getResolvedOccurrences(range)` → delegates to coordinator |
| `MonthlySavingsSweepUseCase.kt` | Only checks `nextDate` inside current month | Query all unpaid occurrences in target period |
| `FinancialWeatherRepository.kt` | Duplicate pipeline of `CalculateFinancialForecastUseCase` | Use coordinator-backed occurrence list |
| `CalculateFinancialForecastUseCase.kt` | Own pipeline with raw patterns | Use coordinator-backed occurrence list |

**Planned expense lifecycle (merged from template PR 8):**

1. **`RecurringPlanProjectionService`** — generates planned expenses from occurrences (opt-in, disabled by default).
   ```kotlin
   suspend fun projectRecurringAsPlanned(
       ruleIds: List<Long>? = null,  // null = all confirmed rules
       horizonDays: Int = 90
   )
   ```
   When a planned row is generated:
   - `sourceType = "RECURRING_OCCURRENCE"`
   - `sourceOccurrenceKey = occurrence.occurrenceKey`
   - `sourceRecurringRuleId = occurrence.ruleId`
   - `merchantKey = occurrence.merchantKey`

2. **`PlannedActualReconciliationService`** — when an actual expense is created:
   - Find matching planned expense by merchant key + date window + currency + amount tolerance.
   - Set `linkedActualExpenseId` on planned row.
   - Set `status = "FULFILLED"` on planned row.
   - If planned was generated from occurrence, also mark occurrence `PAID`.

3. **New DAO methods** on `PlannedExpenseDao`: `getById`, `getByIdFlow`, `update`, `updateStatus`, `linkActualExpense`, `getByOccurrenceKey`, `getForPeriodOneShot`, `getOpenPlannedForPeriod`.

**What must exist at end:**
1. All 8 consumer files migrated to use coordinator/expander.
2. `SynthesisEngine.isRecurringExpected()` removed.
3. `FinancialStressForecastEngine.calculateRecurringOutflows()` uses occurrences.
4. `CashFlowCalculator` uses expanded occurrences.
5. `ForecastInputAssembler` no longer double-counts recurring + planned.
6. `RecurringPlanProjectionService` implemented (disabled by default).
7. `PlannedActualReconciliationService` implemented.
8. Tests: weekly bill appears 4-5 times/month, biweekly bill correct count, paid occurrence not forecast again, planned duplicate not double-counted, stress forecast counts quarterly subscription only when due, cashflow daily list shows all future occurrences, generated planned row is idempotent, actual expense fulfills planned row.

**Files:**
- Modify: `domain/forecasting/FinancialStressForecastEngine.kt`
- Modify: `domain/logic/SynthesisEngine.kt`
- Modify: `domain/cashflow/CashFlowCalculator.kt`
- Modify: `domain/forecasting/ForecastInputAssembler.kt`
- Modify: `domain/forecasting/MergedRecurringPatternsProvider.kt`
- Modify: `domain/savings/MonthlySavingsSweepUseCase.kt`
- Modify: `data/repository/FinancialWeatherRepository.kt`
- Modify: `domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
- Create: `domain/recurring/planning/RecurringPlanProjectionService.kt`
- Create: `domain/recurring/planning/PlannedActualReconciliationService.kt`
- Modify: `data/database/dao/PlannedExpenseDao.kt`
- Modify: `data/repository/PlannedExpenseRepository.kt`

**Risk:** High. Touches ALL forecast/cashflow engines. Must be tested carefully for regression. Run existing forecast tests before and after. The ad-hoc logic removed from `SynthesisEngine.isRecurringExpected()` and `FinancialStressForecastEngine.calculateRecurringOutflows()` must produce equivalent results.

> **Rollback:** If forecasts regress, the old code paths remain as private methods for the duration of the PR. The coordinator call sites are added via a feature flag (`useCoordinatorExpansion: Boolean = true`). If tests fail, toggle to `false` in a hotfix.

---

### PR G — Reminder Scheduling Infrastructure (template PR 9)

**Objective:** Make reminders real, deduped, and scheduled via WorkManager.

**Components:**

1. **`BillReminderWorker`** (`androidx.work.Worker`):
   - Periodic daily worker (enqueue via `PeriodicWorkRequestBuilder<BillReminderWorker>`).
   - Flow:
     1. Capture `now = timeProvider.now()`.
     2. Ensure occurrences materialized for next 30 days via `lifecycleCoordinator.materializeUpcomingOccurrences()`.
     3. Query pending occurrences where `dueDate` is within reminder windows and `status = PENDING`.
     4. For each occurrence × reminder window combination:
        - Check `recurring_reminder_deliveries` for `(occurrenceKey, reminderType)`.
        - If `status = SENT` → skip.
        - If `status = DISMISSED` → skip.
        - If `status = SNOOZED` and `snoozedUntil > now` → skip.
        - Otherwise → send notification, write delivery row with `status = SENT`, write lifecycle event.

2. **`RecurringReminderScheduler`:**
   ```kotlin
   fun schedulePeriodicCheck()                              // enqueue daily WorkManager job
   fun scheduleImmediateCheck()                             // one-shot after rule changes
   fun cancelRemindersForOccurrence(occurrenceKey: String)  // cancel on mark-paid/dismiss
   ```

3. **`BillReminderManager` migration:**
   - Replace `getUpcomingReminders()`: query `recurring_occurrences WHERE status = PENDING AND dueDate BETWEEN now AND now+daysAhead`.
   - Replace `getNotificationsDue()`: query `recurring_occurrences` with status PENDING within due window, check delivery table to avoid duplicates.
   - Replace `markBillPaid()`: call `lifecycleCoordinator.markOccurrencePaid()` instead of manually advancing `nextDate`.
   - `getMonthlyBillsTotal()`: continues using `RecurrenceCalculator.toMonthlyAmount()` — this is already correct.

4. **App startup registration:**
   - In `AppStartupCoordinator` or `MainApplication`, call `reminderScheduler.schedulePeriodicCheck()`.

**Notification design:**
- Channel: "Bill Reminders" (importance: HIGH).
- Content: "${merchant} — ${currency} ${amount} due ${formattedDate}".
- Actions (minimum): "Mark Paid" (broadcast receiver → calls lifecycle coordinator), "Dismiss" (marks delivery as DISMISSED).
- Notification ID: `NotificationIdGenerator` stable per `occurrenceKey + reminderType`.

**What must exist at end:**
1. `BillReminderWorker` created and scheduled.
2. `RecurringReminderScheduler` created.
3. `BillReminderManager` migrated to use occurrences + delivery state.
4. Worker does NOT send duplicate notifications on re-run.
5. Dismiss prevents future sends for that reminder window.
6. Snooze delays sends until snooze expires.
7. Mark paid prevents all future reminders for that occurrence.
8. Monthly bill total still computed correctly (was already using `RecurrenceCalculator.toMonthlyAmount()`).

**Files:**
- Create: `domain/recurring/reminder/BillReminderWorker.kt`
- Create: `domain/recurring/reminder/RecurringReminderScheduler.kt`
- Modify: `domain/reminder/BillReminderManager.kt`
- Modify: `domain/util/NotificationIdGenerator.kt` — add occurrence-based ID generation
- Modify: `startup/AppStartupCoordinator.kt` or `MainApplication.kt` — register periodic worker
- Modify: `AndroidManifest.xml` — if broadcast receiver needed for notification actions

**Risk:** Medium. WorkManager scheduling is tested. Notification actions need a broadcast receiver — if too complex for this PR, defer actions to Phase 5b and only implement notification display + state tracking.

---

### PR H — Subscription Lifecycle Cleanup (template PR 10)

**Objective:** Fix subscription monthly cost normalization, remove hardcoded math, route candidate acceptance through lifecycle coordinator.

**Fixes:**

1. **`SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()`** (audit §5.2 bug):
   ```kotlin
   // BEFORE (WRONG):
   total += analysis.currentPrice  // doesn't normalize quarterly/yearly
   
   // AFTER:
   total += RecurrenceCalculator.toMonthlyAmount(analysis.currentPrice, analysis.frequency)
   ```

2. **`SubscriptionManagementViewModel.calculateCostPerUse()`** (audit §5.3 — hardcoded division):
   Remove hardcoded `÷3`, `÷6`, `÷12`. Use `RecurrenceCalculator.toMonthlyAmount()`.

3. **`SubscriptionManagementViewModel.acceptCandidate()`** (audit §5.3 — hardcoded nextDate):
   Route through `RecurringLifecycleCoordinator.createRuleFromCandidate()`.
   ```kotlin
   // BEFORE (wrong):
   val nextDate = when (detectedInterval) {
       "weekly" -> now + 7 * DAY_IN_MILLIS
       "monthly" -> now + 30 * DAY_IN_MILLIS
       ...
   }
   
   // AFTER:
   val rule = lifecycleCoordinator.createRule(
       merchant = candidate.merchant,
       amount = candidate.averageAmount,
       currency = candidate.currency,
       frequency = detectedInterval.toRecurrenceFrequency(),
       anchorDate = candidate.lastSeen,
       isSubscription = true
   )
   ```

4. **`SmartBillNegotiationEngine`** (audit §8.1 AP-3 — DAO leak):
   Already fixed in PR C above. Verify it stays fixed.

5. **`NotificationSubscriptionDetector`**: Keep as detection-only (no change). It feeds candidates; lifecycle coordinator handles creation.

**What must exist at end:**
1. Quarterly €90 subscription counts as €30/month.
2. Annual €120 subscription counts as €10/month.
3. Accepted candidate creates confirmed recurring rule through coordinator.
4. Candidate conversion preserves currency.
5. Next date calculated through `RecurrenceCalculator`, not hardcoded millis.
6. `calculateCostPerUse()` uses `RecurrenceCalculator.toMonthlyAmount()`.

**Files:**
- Modify: `domain/subscription/SubscriptionManagerEngine.kt`
- Modify: `ui/screens/subscription/SubscriptionManagementViewModel.kt`
- Modify: `domain/negotiation/SmartBillNegotiationEngine.kt` — verify fix from PR C
- Create: test file for subscription cost normalization

**Risk:** Low. Focused changes in 3 files. Primary risk is changing `calculateCostPerUse()` behavior — verify numeric equivalence with existing hardcoded math.

---

### PR I — Integration Hook: TransactionLifecycleCoordinator Side Effect (final wire-up)

**Objective:** Connect the recurring lifecycle to the Phase 3 `TransactionLifecycleCoordinator` so that expense creation automatically matches occurrences and planned expenses.

**Implementation:**
1. Register `OccurrenceMatchingSideEffect` in `TransactionSideEffectDispatcher` (created in PR E).
2. Register `PlannedActualReconciliationSideEffect` — runs after expense creation:
   - Finds matching planned expense.
   - Links `linkedActualExpenseId`.
   - Marks planned `FULFILLED`.
   - If planned was from occurrence, also marks occurrence `PAID`.
3. Add `lifecycleCoordinator.reconcileSingleExpense(expense)` to the coordinator API.

**Side effect registration:**
```kotlin
// In TransactionSideEffectDispatcher or DI module
@Singleton
class OccurrenceMatchingSideEffect @Inject constructor(
    private val lifecycleCoordinator: RecurringLifecycleCoordinator
) : TransactionSideEffect {
    override suspend fun onExpenseCreated(expense: Expense, source: ExpenseSource) {
        lifecycleCoordinator.tryMatchOccurrence(expense)
    }
}

@Singleton
class PlannedActualMatchingSideEffect @Inject constructor(
    private val reconciliationService: PlannedActualReconciliationService
) : TransactionSideEffect {
    override suspend fun onExpenseCreated(expense: Expense, source: ExpenseSource) {
        reconciliationService.tryMatchPlannedExpense(expense)
    }
}
```

**What must exist at end:**
1. `OccurrenceMatchingSideEffect` registered and active.
2. `PlannedActualMatchingSideEffect` registered and active.
3. Creating an expense that matches a pending occurrence → occurrence marked PAID, lifecycle event written.
4. Creating an expense that matches a planned expense → planned marked FULFILLED.
5. Creating an expense that matches a planned-from-occurrence → both occurrence and planned updated.

**Files:**
- Modify: `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt` — register new side effects
- Modify: `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt` — add `tryMatchOccurrence()`
- Modify: `domain/recurring/planning/PlannedActualReconciliationService.kt` — add `tryMatchPlannedExpense()`

**Risk:** Low. Additive change. Existing side effect infrastructure is already tested from Phase 3.

---

### Deferred to Phase 5b

| Item | Reason |
|---|---|
| PR 11 — UI integration (RecurringExpensesScreen, BillRemindersScreen, etc.) | Can be done incrementally; UI already works with legacy data. |
| PR 12 — Recurring income alignment | Low priority; income recurrence is independent and not broken. |
| PR 13 — Guardrails and cleanup | Best done as a final sweep after all functionality is stable. |
| Remove `RecurringExpenseDao` from DI entirely | Only safe after ALL consumers are confirmed migrated (post Phase 5b). |
| `RecurringIncomeTracker` DAO leak fix | Requires `ExpenseDao` query port; non-trivial refactor; defer. |

---

## Dependency Graph

```
PR A (math + docs)
 │
 ├─► PR C (repo consolidation)
 │     │
 │     └─► PR D (expander + resolver)
 │           │
 ├─► PR B (schema) ─► PR E (coordinator + materializer)
 │                         │
 │                         ├─► PR F (forecast + planned migration)
 │                         │     │
 │                         │     ├─► PR G (reminder scheduling)
 │                         │     │
 │                         │     └─► PR H (subscription cleanup)
 │                         │
 │                         └─► PR I (transaction lifecycle hook)
 │
 └──────────────────────────────────────────────────────────────► Phase 5b
```

**Parallelizable:** PR B (schema) and PR C (repo consolidation) can be developed in parallel since they touch different files. PR D depends on PR C (needs `RecurringRuleLike` and repository). PR E depends on BOTH PR B (schema) and PR D (expander).

---

## Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Schema migration fails on production DBs | HIGH | New columns are nullable/defaulted. No data deletion. Test migration against a copy of a real user DB. Rollback: old code paths still work since no columns are removed. |
| Forecast regression after consumer migration | HIGH | Feature flag `useCoordinatorExpansion`. Run existing forecast tests before/after. Compare numeric outputs for 30-day horizon on sample data. |
| Occurrence key collision | MEDIUM | Use `ruleId|normalizedDueDate|frequency` — for a given rule, only one occurrence per due date is possible. UNIQUE index prevents insert collisions. |
| Double-counting in SynthesisEngine | MEDIUM | Resolver explicitly marks duplicates. Dedicated test for recurring+planned overlap. |
| Reminder spam on first scheduler run | MEDIUM | Worker checks `recurring_reminder_deliveries` before sending. Dedup enforced by UNIQUE(occurrenceKey, reminderType). |
| WorkManager job not scheduled on some devices | LOW | Register in `AppStartupCoordinator` using `ExistingPeriodicWorkPolicy.KEEP`. Test on API 26 and API 33 emulators. |

---

## Acceptance Criteria (Phase 5)

- [ ] **AC-1:** `RecurrenceCalculator` edge cases tested (DST, Jan 31, Feb 29, range boundaries).
- [ ] **AC-2:** `RecurringOccurrenceExpander` produces correct occurrences for all frequencies.
- [ ] **AC-3:** `recurring_occurrences` table exists and is populated by materializer.
- [ ] **AC-4:** `recurring_reminder_deliveries` table exists and deduplicates.
- [ ] **AC-5:** `RecurringRuleRepository` is the single access path for all recurring rule CRUD.
- [ ] **AC-6:** Direct DAO leaks in `SmartBillNegotiationEngine` and `ManualExpenseRepository` are removed.
- [ ] **AC-7:** `FinancialStressForecastEngine` uses expanded occurrences, not hardcoded millis.
- [ ] **AC-8:** `SynthesisEngine` uses resolved occurrences, not `isRecurringExpected()` day matching.
- [ ] **AC-9:** `CashFlowCalculator` includes all occurrences in range, not just single `nextDate`.
- [ ] **AC-10:** `ForecastInputAssembler` does not double-count recurring + planned obligations.
- [ ] **AC-11:** `BillReminderWorker` sends reminders via WorkManager with dedup.
- [ ] **AC-12:** Reminder dismiss/snooze state is persisted and honored.
- [ ] **AC-13:** Marking a bill paid updates occurrence status and prevents re-reminding.
- [ ] **AC-14:** `SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()` normalizes non-monthly frequencies.
- [ ] **AC-15:** `SubscriptionManagementViewModel` does not hardcode quarterly÷3, annual÷12.
- [ ] **AC-16:** Creating an expense auto-links to matching occurrence (if any).
- [ ] **AC-17:** Creating an expense auto-links to matching planned expense (if any).
- [ ] **AC-18:** Planned expenses generated from occurrences are idempotent.
- [ ] **AC-19:** Deprecated `RecurringExpenseDao` is no longer injected into any NEW domain code (legacy delegator OK).
- [ ] **AC-20:** No new hardcoded `DAY_IN_MILLIS`, `30`, `90`, `365` recurrence math in production code.
- [ ] **AC-21:** All existing tests pass (no regression).
- [ ] **AC-22:** Migration 96→100 applies cleanly on empty DB and on DB with existing recurring/planned data.

---

## File-by-File Migration Map

| File | PR | Action |
|---|---|---|
| `RecurrenceFrequency.kt` | A | Strengthen `@Deprecated`, add KDoc |
| `RecurrenceCalculator.kt` | A | Add KDoc, verify Phase 2 math used |
| `docs/development/RECURRING_LIFECYCLE.md` | A | Create |
| `AppDatabase.kt` | B | New entities, DAOs, migration 96→100, version 100, `ALL_MIGRATIONS` |
| `RecurringOccurrenceEntity.kt` | B | Create |
| `RecurringReminderDeliveryEntity.kt` | B | Create |
| `RecurringLifecycleEventEntity.kt` | B | Create |
| `RecurringOccurrenceDao.kt` | B | Create |
| `RecurringReminderDeliveryDao.kt` | B | Create |
| `RecurringLifecycleEventDao.kt` | B | Create |
| `ManualRecurringExpense.kt` | B | Add lifecycle columns |
| `PlannedExpense.kt` | B | Add linking columns |
| `PlannedExpenseDao.kt` | B | Add CRUD/linking methods |
| `ManualRecurringExpenseDao.kt` | B | Add new query methods |
| `DaoModule.kt` | B, C | Add new DAO providers |
| `RecurringRuleRepository.kt` | C | Create |
| `RecurringExpenseRepository.kt` | C | Thin delegator |
| `ManualRecurringExpenseRepository.kt` | C | Delete |
| `SmartBillNegotiationEngine.kt` | C | DAO leak fix |
| `ManualExpenseRepository.kt` | C | DAO leak fix |
| `BillReminderManager.kt` | C, G | Repository switch (C), occurrence migration (G) |
| `RecurringExpenseEngine.kt` | C | Repository switch |
| `MergedRecurringPatternsProvider.kt` | C, F | Repository switch (C), occurrence API (F) |
| `FinancialWeatherRepository.kt` | C, F | Repository switch (C), occurrence API (F) |
| `CalculateFinancialForecastUseCase.kt` | C, F | Repository switch (C), occurrence API (F) |
| `MonthlySavingsSweepUseCase.kt` | C, F | Repository switch (C), occurrence API (F) |
| `RecurringRuleLike.kt` | D | Create |
| `RecurringOccurrence.kt` | D | Create |
| `RecurringOccurrenceExpander.kt` | D | Create |
| `RecurringConflictResolver.kt` | D | Create |
| `RecurringOccurrenceMaterializer.kt` | E | Create |
| `RecurringLifecycleCoordinator.kt` | E | Create |
| `OccurrenceMatchingSideEffect.kt` | E | Create |
| `RecurringLifecycleModule.kt` | E | Create |
| `TransactionSideEffectDispatcher.kt` | E, I | Register side effects |
| `FinancialStressForecastEngine.kt` | F | Use coordinator |
| `SynthesisEngine.kt` | F | Use coordinator |
| `CashFlowCalculator.kt` | F | Use coordinator |
| `ForecastInputAssembler.kt` | F | Use coordinator |
| `RecurringPlanProjectionService.kt` | F | Create |
| `PlannedActualReconciliationService.kt` | F | Create |
| `PlannedExpenseRepository.kt` | F | Add lifecycle methods |
| `BillReminderWorker.kt` | G | Create |
| `RecurringReminderScheduler.kt` | G | Create |
| `NotificationIdGenerator.kt` | G | Add occurrence-based ID |
| `AppStartupCoordinator.kt` | G | Register periodic worker |
| `SubscriptionManagerEngine.kt` | H | Fix monthly normalization |
| `SubscriptionManagementViewModel.kt` | H | Remove hardcoded math |
| `TransactionLifecycleCoordinator.kt` | I | Wire side effects (minimal) |

---

@orchestrator The Advanced Technical Plan is ready. Please begin execution of Batch 1.
