# DAO Access Guardrails

> **Phase 3, PR 12** — Document which files are approved to call `ExpenseDao` methods
> and how to enforce this policy.

## Purpose

`ExpenseDao` is the gateway to the expenses table — the central entity in the
application.  Direct DAO access from arbitrary classes creates coupling,
bypasses lifecycle validation, and makes it harder to reason about data flow.

This document defines:

1. Which files **may** call `ExpenseDao` methods (the approved list).
2. Which patterns are **forbidden** outside the approved list.
3. How to run a guardrail check to detect violations.

---

## Approved Files

### Tier 1 — Core Lifecycle Owner

These files own the expense creation/update/delete lifecycle and are **required**
to access `ExpenseDao`:

| File | Role |
|------|------|
| `TransactionLifecycleCoordinator.kt` | Primary expense creation, deduplication, validation, atomic insert |
| `TransactionSideEffectDispatcher.kt` | Post-creation side effects (category learning, etc.) |
| `ReviewQueueRepository.kt` | Block approval → expense promotion for pending reviews |
| `NotificationProcessingPipeline.kt` | Auto-accept path from notifications |

### Tier 2 — Read-Only / Query Repositories

These files access `ExpenseDao` for **queries only** (no inserts/updates/deletes
outside established patterns):

| File | Role |
|------|------|
| `ExpenseRepository.kt` | Primary read facade; all read queries, category/merchant updates |
| `ReceiptRepository.kt` | Receipt-to-expense matching, candidate lookup |
| `ManualExpenseRepository.kt` | Manual expense entry (coordinator is the actual creator) |
| `BudgetRepository.kt` | Budget calculations reading expense totals |
| `AnalyticsRepository.kt` | Analytics queries across expenses |
| `MultiCurrencyRepository.kt` | Currency-aware expense lookups |
| `NotificationRepository.kt` | Notification → expense lookups |
| `NaturalLanguageExpenseQueryRepositoryImpl.kt` | NL search queries |
| `BusinessExpenseRepository.kt` | Business expense filtering/queries |

### Tier 3 — Domain Engines (Read-Only)

Domain-layer engines that need expense data for computation, but **must not**
perform writes directly on `ExpenseDao`:

| File | Role |
|------|------|
| `BudgetForecastingEngine.kt` | Forecast calculations |
| `BudgetAutopilotEngine.kt` | Autopilot budget adjustments |
| `SharedBudgetManager.kt` | Shared budget sync computations |
| `AdvancedAnalyticsDashboard.kt` | Analytics dashboard aggregation |
| `AnomalyAlertOrchestrator.kt` | Anomaly detection queries |
| `SpendingThresholdCalculator.kt` | Threshold evaluation |
| `LifestyleInflationDetector.kt` | Lifestyle inflation detection |
| `CarbonFootprintCalculator.kt` | Carbon footprint computation |
| `RecurringIncomeTracker.kt` | Income recurrence detection |
| `SpendingChallengeManager.kt` | Challenge progress tracking |
| `TaxEstimator.kt` | Tax estimation queries |

### Tier 4 — Infrastructure

| File | Role |
|------|------|
| `GroupTransactionCoordinator.kt` | Group sharing expense coordination |
| `EmailReceiptIngestionService.kt` | Email → expense ingestion service |
| `DatabaseModule.kt` | DI module wiring `ExpenseDao` only |

---

## Forbidden Patterns

### ❌ Direct `expenseDao.insertAtomic(...)` in new files

The **only** files that may call `insertAtomic` are:

- `TransactionLifecycleCoordinator.kt`
- `ReviewQueueRepository.kt` (for the `markAsRelevant` path, grandfathered — see note)

> **Note:** `ReviewQueueRepository.markAsRelevant()` still calls `expenseDao.insertAtomic`
> directly for the manual-debug recovery path.  This is a legacy pattern that
> should eventually be migrated to go through the coordinator.

### ❌ Direct `expenseDao.update(...)` / `expenseDao.delete(...)` in domain engines

Domain-layer classes (`BudgetForecastingEngine`, `AnomalyAlertOrchestrator`, etc.)
**must not** perform mutations on `ExpenseDao`.  They may read data only.

### ❌ Access from ViewModels / UI Layer

ViewModels and UI components **must never** receive an `ExpenseDao` reference.
All data access goes through repositories or use cases.

### ❌ Bypassing the Coordinator for Creation

Any new code path that creates an `Expense` row must go through
`TransactionLifecycleCoordinator.createExpense()`.  Direct `insertAtomic` calls
are only allowed in the grandfathered locations listed in Tier 1.

---

## Approved Patterns

### ✅ Read queries via `ExpenseRepository`

```kotlin
// In a ViewModel or UseCase:
val expenses = expenseRepository.getExpensesBetween(startDate, endDate)
```

### ✅ Creation via TransactionLifecycleCoordinator

```kotlin
val result = coordinator.createExpense(request)
when (result) {
    is CreateExpenseResult.Created -> { /* handle */ }
    is CreateExpenseResult.DuplicateSkipped -> { /* handle */ }
    // ...
}
```

### ✅ Query-only injection in domain engines

```kotlin
class MyEngine @Inject constructor(
    private val expenseDao: ExpenseDao  // OK: read-only queries only
)
```

---

## How to Run the Check

### Option 1: Grep-based check (cross-platform)

```bash
# Find files outside the approved list that reference expenseDao
rg "expenseDao\." app/src/main/java/ --include="*.kt" -l | \
  grep -v -f scripts/guardrails/dao-approved-files.txt
```

### Option 2: PowerShell (Windows)

```powershell
$approved = @(
    'TransactionLifecycleCoordinator',
    'TransactionSideEffectDispatcher',
    'ReviewQueueRepository',
    'NotificationProcessingPipeline',
    'ExpenseRepository',
    'ReceiptRepository',
    'ManualExpenseRepository',
    'BudgetRepository',
    'AnalyticsRepository',
    'MultiCurrencyRepository',
    'NotificationRepository',
    'NaturalLanguageExpenseQueryRepositoryImpl',
    'BusinessExpenseRepository',
    'BudgetForecastingEngine',
    'BudgetAutopilotEngine',
    'SharedBudgetManager',
    'AdvancedAnalyticsDashboard',
    'AnomalyAlertOrchestrator',
    'SpendingThresholdCalculator',
    'LifestyleInflationDetector',
    'CarbonFootprintCalculator',
    'RecurringIncomeTracker',
    'SpendingChallengeManager',
    'TaxEstimator',
    'GroupTransactionCoordinator',
    'EmailReceiptIngestionService',
    'DatabaseModule'
)

$files = Get-ChildItem -Recurse -Filter "*.kt" -Path "app/src/main/java" | Select-Object -ExpandProperty FullName
$violations = @()

foreach ($file in $files) {
    $content = Get-Content $file -Raw
    if ($content -match "expenseDao\.") {
        $matched = $false
        foreach ($name in $approved) {
            if ($file -match [regex]::Escape($name)) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            $relPath = $file.Substring($ProjectRoot.Length + 1)
            $violations += $relPath
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Host "VIOLATIONS found ($($violations.Count)):"
    $violations | ForEach-Object { Write-Host "  - $_" }
    exit 1
} else {
    Write-Host "OK — All expenseDao access is in approved files."
    exit 0
}
```

### Option 3: Kotlin script (scripts/guardrails/dao-access-check.kts)

See [`scripts/guardrails/dao-access-check.kts`](../../scripts/guardrails/dao-access-check.kts)
for a Kotlin-based check that can be run as a Gradle task or CI step.

---

## Adding a New File to the Approved List

1. Determine the **tier** your file belongs in (see tables above).
2. Verify the file follows the **approved patterns** (no mutations from domain,
   no direct inserts bypassing coordinator, etc.).
3. Add the entry to the appropriate table in this document.
4. Update the approved file list in the check script(s).

---

## Enforcement in CI

The guardrail check should be part of the CI pipeline:

```yaml
# Example GitHub Actions step
- name: DAO Access Guardrails
  run: |
    pwsh -File scripts/guardrails/dao-access-check.ps1
```

If the check fails, the PR must be reviewed to determine whether:
- The new file should be added to the approved list (with justification), **or**
- The code should be refactored to use `TransactionLifecycleCoordinator` or
  an existing repository instead.
