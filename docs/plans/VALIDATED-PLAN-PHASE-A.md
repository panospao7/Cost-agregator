# Validated Remedy Plan: Phase A (A.2, A.3, A.5, A.9 + B.1, B.2)

Current-tree validation completed against:
- `docs/reviews/AUDIT-PHASE-A-B1-B3.md`
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`

## Issue 1: A.2 Domain/Data Boundary
**Verified:** YES
**Evidence:**
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:3-4` imports `android.content.Context`; `:23-25` injects `@ApplicationContext`; `:69-70` resolves `UiText`; `:82-86` calls `context.getString(...)` / `getQuantityString(...)` inside domain.
- `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:3,34-83,111-205` imports app `R` and constructs `UiText.StringResource(R.string...)` in domain logic.
- `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:3,520-547` imports app `R` and returns resource-backed `UiText` from domain.
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt:3,116-119` imports app `R` for fallback weather text in domain.
**Fix:**
- Remove Android/resource resolution from domain entirely.
- Replace `UiText.StringResource(R.string...)` usage in `NarrativeGenerator`, `SynthesisEngine`, and `DashboardDataProvider` with domain-owned message keys (`UiText.fromKey(...)`) backed by new `DomainTextKeys` entries.
- Refactor `DashboardBriefingInputBuilder` so it emits pure domain data only (structured warning/upcoming-item inputs and unresolved `UiText`/message keys), and move all `Context`/resource/string formatting into a data-layer prompt formatter used by `OnDeviceDashboardBriefingService` and `CloudDashboardBriefingService`.
- Extend the presentation/data adapter layer (`UiTextExtensions` or a dedicated prompt formatter) to map the new message keys to Android strings.
**Files to modify:**
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:23-25,46-79,82-86`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt:117-128`
- `app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt:3-83,103-205`
- `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:3,518-547`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt:113-127`
- `app/src/main/java/com/yourname/expensetracker/domain/text/DomainTextKeys.kt:7-27`
- `app/src/main/java/com/yourname/expensetracker/ui/components/UiTextExtensions.kt:65-89`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceDashboardBriefingService.kt:59-78`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt:185-211`

## Issue 2: A.3 Non-deterministic Defaults
**Verified:** YES
**Evidence:**
- `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt:334` reads `System.currentTimeMillis()` inside `UpcomingRow`.
- `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt:55` defaults `date` to `System.currentTimeMillis()` in `addExpenseWithLink(...)`.
- `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt:70` defaults `date` to `System.currentTimeMillis()` in `createSystemExpenseAndLinkToGroup(...)`.
**Fix:**
- For `FinancialWeatherCard`, stop reading wall-clock time in the composable. Pass a captured `referenceNowMillis` from a `TimeProvider`-backed caller and compute labels from that single value.
- For `GroupsRepository`, remove wall-clock default parameters from the repository contract. Require an explicit `date` from the boundary use case (`AddGroupExpenseUseCase` already resolves `date ?: timeProvider.now()` at `app/src/main/java/com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt:34,70`).
- Update repository implementation signatures/call sites so no repository API can silently fall back to real time.
**Files to modify:**
- `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt:333-340`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt:579-595`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt:172-195,259-315`
- `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt:47-56,62-71`
- `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt:106-158`

## Issue 3: A.5 Time Boundary Issues
**Verified:** YES
**Evidence:**
- `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt:334-339` computes `daysUntil` via raw millisecond division and then maps it to `Today` / `Tomorrow` / formatted date.
**Fix:**
- Replace the ad-hoc `((item.date - now) / DAY_MS).toInt()` logic with calendar-safe day-boundary math using the same caller-supplied `referenceNowMillis`.
- Use `TimePeriodUtils.getStartOfDay(...)` + `TimePeriodUtils.daysBetween(...)` (or equivalent `LocalDate` math seeded from the same timestamp) so DST and partial-day differences do not mislabel “Today” and “Tomorrow”.
- Keep the user-facing labels in UI, but base them on canonical start-of-day comparisons rather than elapsed milliseconds.
**Files to modify:**
- `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt:333-340`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt:579-595`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt:172-195,259-315`

## Issue 4: A.9 DAO Truncation Footgun
**Verified:** YES
**Evidence:**
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:95-96` — `getAllFlow(limit: Int = 500)`.
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:156-158` — `getAll(limit: Int = 2000)`.
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:788-817` — `getExpensesBetween(...)`, `getExpensesBetweenForExport(...)`, `getExpensesByTypeBetween(...)`, `getExpensesBetweenFlow(...)`, and `getExpensesByTypeBetweenFlow(...)` all still expose `limit = 2000` defaults.
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:1355-1376` — `getLocatedExpenses(limit: Int = 2000)`, `getUnlocatedExpenses(limit: Int = 500)`, and `getUnlocatedExpensesForBackfill(limit: Int = 500, ...)` still default-cap results.
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:1456-1459` — `getExpensesInBoundingBox(..., limit: Int = 2000)`.
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt:22-27` — `getPending(limit: Int = 500)`.
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt:353-365` currently calls `pendingReviewDao.getPending()` with the silent default cap for bulk approve/reject flows.
**Fix:**
- Remove silent default caps from DAO methods whose names imply full-history/full-result semantics. Either:
  - rename bounded methods to explicit `...Paged(...)` / `...Batch(...)` forms and require `limit` at every call site, or
  - add uncapped/full-read variants and deprecate the ambiguous default-capped methods.
- Keep already-fixed repository full-read paths (`ExpenseRepository.getAllExpenses()` and `getExpensesBetween()` now using uncapped DAO methods at `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt:69-72,507-545`) intact; do not regress them.
- For intentionally batch-oriented location/backfill methods, keep them bounded but make the limit explicit and caller-owned.
- Add an uncapped or exhaustively paged pending-review retrieval path and update `ReviewQueueRepository.approveAllReview()` / `rejectAllReviews()` so bulk operations cannot stop at 500 rows.
**Files to modify:**
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:95-96,156-158,788-817,1355-1376,1456-1459`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt:69-75,514-545,603-637`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt:21-27`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt:353-365`

## Issue 5: B.1 Dashboard Briefing Hardcoding
**Verified:** YES
**Evidence:**
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:50` hardcodes `"Overall"`.
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:52` hardcodes `"$name at $pct%"`.
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:59-62` hardcodes `"${item.description} €... on $dateLabel"`, including a fixed `€` symbol and English sentence structure.
**Fix:**
- Stop assembling natural-language prompt text in the domain builder.
- Change `DashboardBriefingInput` to carry structured dashboard facts (for example: budget warning `{categoryName, percentUsed}` and upcoming item `{description, amount, date, currency}`) instead of preformatted English strings.
- Introduce one data-layer prompt formatter shared by `OnDeviceDashboardBriefingService` and `CloudDashboardBriefingService` to render those facts into prompt text, so localization/currency/date wording lives outside domain and can reuse the A.2 boundary cleanup.
- Update compile-neighbor builders that share `DashboardBriefingInput` if the DTO shape changes.
**Files to modify:**
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:46-63`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt:117-128`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceDashboardBriefingService.kt:59-78`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt:185-211`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/TransactionInsightInputBuilder.kt:22-47`

## Issue 6: B.2 SharedBudgetManager Stub
**Verified:** YES
**Evidence:**
- `app/src/main/java/com/yourname/expensetracker/domain/budget/SharedBudgetManager.kt:82-90` maps every `memberId` to `memberName = "Member $memberId"` and zeroed `amountSpent`, `percentOfTotal`, and `remainingAllowance`.
- `app/src/test/java/com/yourname/expensetracker/domain/budget/SharedBudgetManagerTest.kt:377-393` currently locks in the placeholder behavior.
**Fix:**
- Do not keep returning fabricated data.
- Immediate safe remedy: replace the stub with an explicit unsupported/unavailable result (or fail-fast exception) so callers are not shown fake member names and fake zero spending.
- If the product requires real member contributions now, add a real shared-budget data contract first: `SharedBudgetManager` currently receives only `budgetId` and `List<String> memberIds`, while the existing group data model uses `GroupMember.id: Long` and there is no explicit budget→group linkage. A real implementation therefore needs a new mapping source before it can compute `amountSpent`, `percentOfTotal`, and `remainingAllowance` from actual shared/group expenses inside the active budget window.
- Update tests to stop asserting placeholder output.
**Files to modify:**
- `app/src/main/java/com/yourname/expensetracker/domain/budget/SharedBudgetManager.kt:78-90`
- `app/src/test/java/com/yourname/expensetracker/domain/budget/SharedBudgetManagerTest.kt:377-393`

## Priority Order
1. **A.9 DAO truncation footgun** — highest severity (`CRITICAL`) and still has an active default-capped caller in `ReviewQueueRepository`.
2. **A.2 + B.1 dashboard briefing/domain-text boundary cleanup** — one shared change set removes Android/resource leakage and hardcoded prompt text from the same AI path.
3. **A.3 + A.5 financial weather time cleanup** — small, isolated fix set that removes live wall-clock reads and replaces DST-unsafe day math.
4. **B.2 SharedBudgetManager stub** — isolated, but blocked on a clear product/contract choice (explicit unsupported result vs real budget↔group linkage).

## Estimated Effort
~20-28 hours / 3-4 days total.

Notes:
- The lower end assumes B.2 is fixed by replacing the fake placeholder API with an explicit unsupported result.
- Add ~1-2 extra days if B.2 must be fully implemented with a new budget-to-group/member mapping contract.
