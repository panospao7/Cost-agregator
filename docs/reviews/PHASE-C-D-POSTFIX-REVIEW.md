# Phase C + D Post-Fix Review

## Summary
- Fixed: 13
- Partially fixed: 1
- Still open: 0
- Premature registry markers: [`MASTER-ISSUE-REGISTRY.md:731`]

## C.1
### Domain/resource boundary in validated files
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:8-13,33-58`; `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:8-10,518-535`; `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:7-10,50-53` now use `UiText`/domain keys and contain no Android `Context`/`R` imports.
**Remaining issue:** None in the validated files. The broader registry item at `MASTER-ISSUE-REGISTRY.md:701` should stay open for other boundary debt.
**Suggested solution:** None for this claimed fix.

### TimeProvider rollout in validated files
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt:112-129` now requires `referenceDate`; `app/src/main/java/com/yourname/expensetracker/domain/carbon/CarbonFootprintCalculator.kt:15-18,115-120` injects `TimeProvider` and derives default period from `timeProvider.now()`.
**Remaining issue:** None in the validated files. The partial registry marker at `MASTER-ISSUE-REGISTRY.md:702` is accurate because broader rollout still remains elsewhere.
**Suggested solution:** None for this claimed fix.

### FinancialHealthScoreV2 failure masking / Group 13
**Status:** PARTIALLY_FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt:183-187` now rethrows instead of fabricating an overall neutral result. But `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt:377-418` still catches generic failures inside `calculateBillReliabilityScore()` and returns synthetic `75`.
**Remaining issue:** V2 can still emit a seemingly valid score when the recurring-pattern/bill-reliability subpath fails, so failure masking is reduced but not fully removed.
**Suggested solution:** Remove the `75` fallback from `calculateBillReliabilityScore()`. Re-throw non-cancellation failures or return a typed component-failure result so `ComputeDashboardWidgetsUseCase` can suppress/fallback explicitly.

## C.3
### Group 12 - dual financial-health KPI emission
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:689-694` now emits exactly one health widget: V2 when available, legacy otherwise. `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt:665-679` only renders whichever single widget was emitted.
**Remaining issue:** None for this claim.
**Suggested solution:** None.

### Group 15 - SavingsSweepPrompt emission path
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:607-629` computes `DashboardWidget.SavingsSweepPrompt`; `.../ComputeDashboardWidgetsUseCase.kt:685-687` emits it; `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt:734-738` renders `SavingsSweepPromptCard`.
**Remaining issue:** None for this claim.
**Suggested solution:** None.

## D.2
### BillReminderManager urgency semantics
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt:56-63` maps due-now/overdue to `CRITICAL`, `1..2` days to `URGENT`, and `3..7` days to `WARNING`. Regression coverage exists at `app/src/test/java/com/yourname/expensetracker/domain/reminder/BillReminderManagerTest.kt:102-117`.
**Remaining issue:** None for the claimed urgency fix. The separate `markBillPaid()` issue remains outside this claim.
**Suggested solution:** None.

### SharedBudgetManager stub removal
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/budget/SharedBudgetManager.kt:78-84` no longer returns fabricated member names/zero values; it now fails fast with explicit unsupported behavior.
**Remaining issue:** The feature is still unsupported, but the stubbed fake output is gone and the registry text at `MASTER-ISSUE-REGISTRY.md:156,765` matches current code.
**Suggested solution:** When implemented, prefer a typed unsupported/result contract over throwing `UnsupportedOperationException`.

### AdvancedAnalyticsDashboard transfer-as-income
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt:101-105` counts only `DEPOSIT` as income; `TRANSFER` is excluded. The same policy is applied in `.../AdvancedAnalyticsDashboard.kt:207-211` for monthly trend buckets.
**Remaining issue:** None for this claim.
**Suggested solution:** None.

### InsightsEngine canonical key display
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:1182-1183,1718-1720` now exposes canonical `merchantName` plus `displayName`; `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:371-386` groups by canonical key but displays `ms.displayName.ifBlank { ms.merchantName }`.
**Remaining issue:** None for this claim.
**Suggested solution:** None.

### SuggestReceiptExtractionUseCase unstable sourceHash
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:69-70,183-200` uses stable SHA-256 over deterministic fields and excludes volatile `currentTimeMs`; `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt:160-179` verifies cache reuse across different timestamps.
**Remaining issue:** None for this use case. Broader `hashCode()`-based artifact identity debt still exists in other AI use cases.
**Suggested solution:** Extract and reuse this stable hashing pattern across the remaining AI artifact producers.

## D.4
### ExpenseTrackerApp bespoke CoroutineScope
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt:77-79` uses `ProcessLifecycleOwner.get().lifecycleScope.launch`; there is no application-owned `CoroutineScope` left in the file.
**Remaining issue:** None for this claim.
**Suggested solution:** None.

### ExpenseTrackerApp eager TransactionClassifier / BudgetMonitor injection
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt:25-29` injects `Lazy<TransactionClassifier>` and `Lazy<BudgetMonitor>`; `.../ExpenseTrackerApp.kt:84-93` resolves them only inside lifecycle callbacks.
**Remaining issue:** None for this claim.
**Suggested solution:** None.

### NotificationIdGenerator signed modulo mapping
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt:38-45,52,59,66,73,80-87` routes all range mapping through `positiveRangeOffset()` which uses `Math.floorMod(...)` at lines `86-87`.
**Remaining issue:** None for this claim.
**Suggested solution:** None.

### BKTree mutable state outside mutex
**Status:** FIXED
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/util/BKTree.kt:20-30` now exposes a single immutable `TreeState` snapshot via `@Volatile state`; writes replace that snapshot under mutex at `46-66` and `123-124`, so `size`/`isEmpty` no longer read torn mutable fields.
**Remaining issue:** None for the claimed race fix.
**Suggested solution:** Add a concurrent stress test if this structure is expected to be hot under contention.

## Verdict
NEEDS_FOCUSED_CODER_PASS

`FinancialHealthScoreV2` is only partially fixed: the top-level fake-neutral fallback is gone, but `calculateBillReliabilityScore()` still masks subpath failures with synthetic `75`, so the resolved registry marker at `MASTER-ISSUE-REGISTRY.md:731` is premature.
