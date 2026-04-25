# Validated Remedy Plan: Section C + D

> Note: the request says "Top 20," but the explicit issue list names 16 items. To complete the requested "all 5" D.4 batch, this plan also includes the two omitted audited D.4 issues (`BudgetMonitor` eager injection and `BKTree` unsynchronized reads), for a total of 18 validated checks.

## C.1 Blockers

## Issue: Domain/resource boundary still leaks into domain logic
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:3,34-35`; `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:3,517-547`; `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:23-25,82-86`  
**Fix:** Stop importing Android resources / `Context` into domain-layer classes. Replace domain-produced `UiText.StringResource` / `context.getString(...)` resolution with domain-safe message keys or plain DTOs, and move final localization/string resolution into presentation or adapter code.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt`, `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`, `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`, plus the presentation-side resolver/adapter that will own final string resolution.

## Issue: TimeProvider rollout is still incomplete
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt:112,127`; `app/src/main/java/com/yourname/expensetracker/domain/carbon/CarbonFootprintCalculator.kt:14-16,113-115`  
**Fix:** Remove `System.currentTimeMillis()` defaults from public APIs. Either require callers to pass `referenceDate` explicitly or convert these utilities/classes to injected `TimeProvider` consumers. Keep one clock source per operation and add deterministic tests for due/upcoming boundaries and default carbon-report periods.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/logic/RecurrenceCalculator.kt`, `app/src/main/java/com/yourname/expensetracker/domain/carbon/CarbonFootprintCalculator.kt`, affected callers/tests.

## Issue: CancellationException is still swallowed in health-score path
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt:182-195`  
**Fix:** Add an explicit `CancellationException` rethrow before any generic catch. Replace the synthetic score-50 fallback with an explicit failure result (or nullable/no-widget path) so callers do not render fake health data.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`, `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, affected tests.

## C.3 Critical Groups

## Issue: Group 12 - Dashboard still emits dual financial-health KPIs
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:647-653`; supporting render paths still exist in `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt:664-672`  
**Fix:** Choose one authoritative health KPI. Prefer V2 once failure handling is fixed; keep legacy only as a temporary fallback path, not side-by-side. Remove duplicate emission, update widget ordering/telemetry, and adjust UI/tests to expect one health card.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`, dashboard widget tests.

## Issue: Group 13 - FinancialHealthScoreV2 still fabricates a neutral score on failure
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt:182-195`  
**Fix:** Replace fabricated `50` values with a real error/no-data result and let the dashboard choose fallback rendering explicitly. This should be implemented together with the `CancellationException` fix and before removing the legacy KPI.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`, `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, health widget consumers/tests.

## Issue: Group 15 - SavingsSweepPrompt widget has no production emission path
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:140-147` defines `SavingsSweepPrompt`; `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:190-199` does not inject `MonthlySavingsSweepUseCase`; `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:631-684` never instantiates the widget; the recommendation is wired only in `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt:140-155`  
**Fix:** Decide whether month-end sweep belongs on the dashboard. If yes, inject `MonthlySavingsSweepUseCase`, compute once per dashboard refresh, and emit `DashboardWidget.SavingsSweepPrompt` when the recommendation is valid. If no, delete the dead widget type and related UI branches to remove misleading dead code.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`, `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`, optionally `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt`, related tests.

## Issue: Group 25 - Duplicate CategoryBreakdown / PeriodRange model families still exist
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/model/CategoryBreakdown.kt:3-8`; `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsModels.kt:159-164`; `app/src/main/java/com/yourname/expensetracker/domain/model/PeriodRange.kt:3-13`; `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsModels.kt:15-20`; active imports still split between both families in `app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt:4`, `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt:54`, and `app/src/main/java/com/yourname/expensetracker/data/repository/BudgetRepository.kt:12`  
**Fix:** Canonicalize one model per concept. Either migrate callers to shared domain models or rename analytics-specific shapes so they no longer collide conceptually. Do this as an incremental rename/migration to avoid broad breakage.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/model/CategoryBreakdown.kt`, `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsModels.kt`, `app/src/main/java/com/yourname/expensetracker/domain/model/PeriodRange.kt`, `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsModels.kt`, import sites/tests.

## Issue: Group 26 - Domain/resource boundary remains open
**Verified:** YES  
**Evidence:** Same verified evidence as C.1 blocker: `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:3,34-35`; `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:3,517-547`; `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:23-25,82-86`  
**Fix:** Resolve this in the C.1 blocker batch; do not treat Group 26 as separate implementation work. Use one domain-safe text contract and move all Android localization/materialization out of domain code.  
**Files to modify:** Same as C.1 domain boundary fix.

## D.2 High Quick Wins

## Issue: BillReminderManager urgency semantics are wrong
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt:58-63` maps overdue to `CRITICAL` but due-today (`daysUntil == 0`) and due-tomorrow (`daysUntil == 1`) to `URGENT`  
**Fix:** Align urgency mapping to the enum contract: due today or overdue => `CRITICAL`; 1-2 days => `URGENT`; 3-7 days => `WARNING`; otherwise `INFO`. Keep `getNotificationsDue()` consistent with the same thresholds.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt`, reminder tests.

## Issue: SharedBudgetManager member contributions API is still a stub
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/budget/SharedBudgetManager.kt:82-90` explicitly returns placeholder member names and zeroed contribution fields  
**Fix:** Either implement real member-spend aggregation from shared/group expense ownership data, or change the API to return an explicit unsupported/not-available state until attribution data exists. Prefer a real implementation using the same budget period window and effective-amount semantics as the main budget pipeline.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/budget/SharedBudgetManager.kt`, supporting repository/DAO query layer if missing, tests.

## Issue: AdvancedAnalyticsDashboard still treats incoming transfers as income
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt:105-107`  
**Fix:** Exclude transfers from income/spend totals or model them as a separate cash-movement metric. Also align monthly-trend income calculations to the same transaction-type policy so totals stay consistent across the dashboard.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsDashboard.kt`, analytics tests.

## Issue: InsightsEngine exposes canonical merchant key as a display label
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:371-374` documents that DAO merchant stats alias `merchantKey -> merchantName`; `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt:385-386` then publishes `ms.merchantName` as the visible merchant label  
**Fix:** Preserve canonical merchant key for grouping only; source or resolve a human-readable display name before building `MerchantInsight`. If necessary, extend merchant stats DTOs to carry both canonical key and best display label.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`, merchant stats DTO/repository/DAO layer if needed, tests.

## Issue: SuggestReceiptExtractionUseCase uses unstable sourceHash
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:68-69` uses `input.hashCode().toString()`; `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt:66` injects `currentTimeMs = timeProvider.now()` into the input; `app/src/main/java/com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt:23-37` includes that field in `ReceiptAssistInput`  
**Fix:** Replace `hashCode()` with a stable content hash over normalized deterministic fields only. Exclude volatile timestamps and any fields that change per invocation but not per business input. Add cache-hit regression tests across repeated calls for the same receipt.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`, `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt` and/or a new stable hashing helper, AI cache tests.

## D.4 Low Quick Wins (batchable)

## Issue: ExpenseTrackerApp creates its own CoroutineScope
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt:38,80-82`  
**Fix:** Remove the bespoke application scope. Trigger startup sync via injected startup orchestration, lifecycle-aware app scope, or WorkManager so lifecycle and cancellation are centrally managed.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`, startup orchestration/work scheduling code.

## Issue: TransactionClassifier is eagerly injected into Application
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt:26-27,66-68`  
**Fix:** Replace eager field injection with lazy/provider-based resolution or move lifecycle registration behind a dedicated startup coordinator so the classifier initializes only when needed.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`, lifecycle/startup wiring.

## Issue: BudgetMonitor is eagerly injected into Application
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt:29-30,66-68`  
**Fix:** Apply the same lazy/startup-coordinator approach as the classifier. Avoid constructing monitoring infrastructure just by starting the `Application` object.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`, lifecycle/startup wiring.

## Issue: NotificationIdGenerator uses signed modulo for range mapping
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt:39,52,59,66,73,83`  
**Fix:** Centralize positive range mapping with `Math.floorMod(...)` (or an equivalent helper) before `Long -> Int` conversion. Add regression tests for negative IDs, very large IDs, and mixed-hash values.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt`, notification ID tests.

## Issue: BKTree exposes mutable state outside the mutex
**Verified:** YES  
**Evidence:** `app/src/main/java/com/yourname/expensetracker/domain/util/BKTree.kt:24-25` reads `_size` / `root` without locking, while writes happen under mutex in `app/src/main/java/com/yourname/expensetracker/domain/util/BKTree.kt:41-64,117-120`  
**Fix:** Make reads consistent with writes by using suspend getters under the same mutex, or switch `_size`/`root` to atomic snapshot-safe state. Keep the public API thread-safety contract explicit.  
**Files to modify:** `app/src/main/java/com/yourname/expensetracker/domain/util/BKTree.kt`, callers/tests if API shape changes.

## Priority Order
1. C.1 domain boundary - blocks clean domain/presentation separation and overlaps Group 26.
2. C.1 TimeProvider - blocks deterministic behavior and test reliability.
3. C.1 cancellation cleanup + C.3 Group 13 - unblock trustworthy health-score failure handling.
4. C.3 Group 12 - remove dual KPI only after Group 13 is fixed.
5. C.3 Group 15 - either wire or delete the dead sweep widget.
6. C.3 Group 25 - canonicalize duplicate model families after blocker churn settles.
7. D.2 high issues - semantic correctness fixes with moderate local blast radius.
8. D.4 low issues - batch together once higher-risk correctness work is stable.

## Estimated Effort
- **8-12 hours for C.1 blockers** (domain/resource boundary refactor is the main driver).
- **6-8 hours for C.3 groups** (net-new effort after reusing the C.1 blocker fixes for Groups 13 and 26).
- **5-7 hours for D issues** (D.2 high quick wins plus one batched D.4 cleanup pass).
