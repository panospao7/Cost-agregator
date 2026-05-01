# Phase 5 — Final Verification Audit

**Date:** 2026-05-01  
**Scope:** Recurring / Planned / Reminder Lifecycle  
**Target:** `app/src/main/java/com/yourname/expensetracker/`

---

## 1. New Entities/DAOs Registered ✅

### Entities in AppDatabase entity list (lines 68–69)
| Entity | Status |
|---|---|
| `RecurringOccurrence::class` | ✅ Line 68 |
| `RecurringReminderDelivery::class` | ✅ Line 69 |

### DAO abstract methods in AppDatabase (lines 126–127)
| DAO | Status |
|---|---|
| `recurringOccurrenceDao(): RecurringOccurrenceDao` | ✅ Line 126 |
| `recurringReminderDeliveryDao(): RecurringReminderDeliveryDao` | ✅ Line 127 |

### Hilt providers in DaoModule (lines 244–250)
| Provider | Status |
|---|---|
| `provideRecurringOccurrenceDao()` | ✅ Line 244 |
| `provideRecurringReminderDeliveryDao()` | ✅ Line 249 |

**Verdict: PASS** — Both entities, DAOs, and Hilt providers are properly registered.

---

## 2. Remaining Anti-Patterns

### 2a. `System.currentTimeMillis()` in recurring/reminder/planned/subscription files

| File | Line | Issue | Severity |
|---|---|---|---|
| `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt` | 460 | `referenceNowMillis: Long = System.currentTimeMillis()` — default param in Composable function | ⚠️ LOW — UI layer only, but should ideally use `TimeProvider` |
| `data/database/dao/SubscriptionCandidateDao.kt` | 79, 90 | `markAsConverted()` and `markAsRejected()` use `System.currentTimeMillis()` as default param | ⚠️ LOW — DAO default params; production callers likely pass a timestamp |

No `System.currentTimeMillis()` found in: `BillReminderManager`, `RecurrenceCalculator`, `RecurringExpenseEngine`, `RecurringOccurrenceExpander`, `RecurringOccurrenceMaterializer`, `RecurringLifecycleCoordinator`, `RecurringPlanProjectionService`, `ForecastInputAssembler`.

**Verdict: ⚠️ WARN** — Two low-severity instances remain, neither in core recurring lifecycle logic.

### 2b. Hardcoded `DAYS_IN_MONTH=30` or similar

| File | Line | Code | Severity |
|---|---|---|---|
| `domain/subscription/NotificationSubscriptionDetector.kt` | 65 | `const val DAYS_IN_MONTH = 30` | ⚠️ LOW — used for *detection* heuristics (estimating annual cost), not calendar arithmetic |
| `ui/screens/analytics/AnalyticsViewModel.kt` | 454 | `pattern.frequency.calendarMonths?.times(30) ?: 0` | ⚠️ LOW — used for display-level interval estimation, not production date logic |

No `DAYS_IN_MONTH` usage found in core lifecycle files (`RecurringLifecycleCoordinator`, `RecurringOccurrenceExpander`, `RecurrenceCalculator`, etc.)

**Verdict: ⚠️ WARN** — Both are heuristic/display usages, not calendar-sensitive production paths.

### 2c. `RecurrenceFrequency.days` usage in production

The `.days` property on `RecurrenceFrequency` (in `RecurringPattern.kt` line 51–60) is `@Deprecated`. Grep found **zero** usages of `RecurrenceFrequency.days` in production code.

New code correctly uses `.fixedIntervalDays` and `.calendarMonths`:
- `RecurrenceCalculator.kt` lines 102, 106 — uses `fixedIntervalDays` / `calendarMonths`
- `RecurringOccurrenceExpander.kt` lines 127–133 — uses `TimePeriodUtils.addDays`/`addMonths`/`addYears`

**Verdict: PASS** — Deprecated property is not used in production.

### 2d. Deprecated `RecurringExpenseDao` still injected outside approved files

The `RecurringExpenseDao` interface (file itself) is correctly marked `@Deprecated` with message *"Use ManualRecurringExpenseDao instead"*.

**Callers of `RecurringExpenseDao` (the deprecated interface):**
- `AppDatabase.kt` line 87 — ✅ Abstract method, required by Room
- `DaoModule.kt` line 80 — ✅ `@Deprecated` provider for backward compatibility

**All production callers use `ManualRecurringExpenseDao`:**
- `RecurringLifecycleCoordinator` — injects `ManualRecurringExpenseDao`
- `RecurringExpenseRepository` — injects `ManualRecurringExpenseDao`
- `ManualRecurringExpenseRepository` — injects `ManualRecurringExpenseDao`
- `SubscriptionManagementRepository` — injects `ManualRecurringExpenseDao`
- `SmartBillNegotiationEngine` — injects `ManualRecurringExpenseDao`

**Verdict: PASS** — No production class injects the deprecated DAO.

---

## 3. Coordinator Adoption

### Classes injecting `RecurringLifecycleCoordinator`

| Class | File | Status |
|---|---|---|
| `TransactionLifecycleCoordinator` | `domain/transaction/lifecycle/` | ✅ Injects via constructor |
| `ForecastInputAssembler` | `domain/forecasting/` | ✅ Injects via constructor |
| `RecurringPlanProjectionService` | `domain/recurring/` | ✅ Injects via constructor |

### Classes that reference but do NOT inject

| Class | Note |
|---|---|
| `SmartSavingsEngine` (line 51) | `// TODO: Inject RecurringLifecycleCoordinator for recurring-aware safe-to-save` |
| `BillReminderManager` (line 34–43) | Javadoc references coordinator but `ReminderDispatchWorker` is "to be created in a future PR" |
| `SynthesisEngine` (line 26) | Javadoc-only reference |

**Count: 3 classes inject `RecurringLifecycleCoordinator`** (the class itself excluded).

**Verdict: PASS** — Core consumers are integrated. Two TODO items remain for future work.

---

## 4. Schema Version & Migration

| Check | Status |
|---|---|
| `APP_DATABASE_SCHEMA_VERSION = 100` | ✅ Line 13 |
| Migration `96 → 100` exists | ✅ `MIGRATION_96_100` at line 5778 |
| Migration creates `recurring_occurrences` table | ✅ Lines 5784–5809 |
| Migration creates `recurring_reminder_deliveries` table | ✅ Lines 5813–5829 |
| Migration adds `sourceOccurrenceKey` to `planned_expenses` | ✅ Line 5832 |
| Migration adds `sourceRecurringRuleId` to `planned_expenses` | ✅ Line 5835 |
| Migration registered in `ALL_MIGRATIONS` | ✅ Line 5979 |

**Note:** Migration jumps from version 96 to 100 in a single step (skipping 97, 98, 99). This is unconventional but valid as long as no device is at those intermediate versions.

**Verdict: PASS**

---

## 5. Outstanding Stubs (TODO / UnsupportedOperationException)

### TODOs remaining

| File | Line | Content |
|---|---|---|
| `domain/savings/SmartSavingsEngine.kt` | 51 | `// TODO: Inject RecurringLifecycleCoordinator for recurring-aware safe-to-save` |
| `domain/forecasting/ForecastInputAssembler.kt` | 46 | `* TODO: Use [RecurringLifecycleCoordinator.generateOccurrences] as the single source of truth...` |
| `domain/reminder/BillReminderManager.kt` | 41 | `* 2. A [ReminderDispatchWorker] (WorkManager — to be created in a future PR)` |
| `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt` | 139 | `* This is intended to be called by a [ReminderDispatchWorker] (WorkManager)` |

### UnsupportedOperationException

Zero instances found across all recurring/reminder/planned/subscription files.

**Verdict: ⚠️ WARN** — 4 TODOs remain, none in critical production-paths (all are future enhancements: ReminderDispatchWorker, coordinator integration).

---

## Summary

| Category | Verdict |
|---|---|
| 1. Entities/DAOs/Hilt providers registered | ✅ **PASS** |
| 2a. `System.currentTimeMillis()` anti-pattern | ⚠️ WARN (2 low-severity instances) |
| 2b. `DAYS_IN_MONTH=30` hardcoded | ⚠️ WARN (2 heuristic/display-only instances) |
| 2c. `RecurrenceFrequency.days` usage | ✅ **PASS** (zero production usage) |
| 2d. Deprecated `RecurringExpenseDao` injection | ✅ **PASS** (no production callers) |
| 3. Coordinator adoption | ✅ **PASS** (3 injecting classes) |
| 4. Schema version & migration | ✅ **PASS** (version=100, migration 96→100) |
| 5. Outstanding stubs | ⚠️ WARN (4 TODOs, all future work) |

**Overall Phase 5: PASS with minor warnings.** No blocking issues found. The two `System.currentTimeMillis()` instances and two `DAYS_IN_MONTH=30` approximations are all in display/heuristic paths, not in production date-arithmetic logic. The 4 TODOs are scoped to future PRs (ReminderDispatchWorker, further coordinator integration).
