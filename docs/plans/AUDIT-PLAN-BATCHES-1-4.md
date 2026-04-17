# Audit: Batches 1-4 (Post Phase A-C)

Audited against current source code. Classified: RESOLVED / STILL_OPEN / PARTIALLY_AFFECTED / NOT_VERIFIED.

---

## BATCH 1: UI / Challenge / Currency Closeout

| # | Severity | Status | Summary |
|---|----------|--------|---------|
| B1-1 | HIGH | ✅ RESOLVED | Challenge creation wired end-to-end |
| B1-2 | HIGH | 🔴 STILL_OPEN | 43+ hardcoded €/`EUR` remain across UI |
| B1-3 | MEDIUM | 🟡 PARTIALLY_AFFECTED | Challenge UI improved but hardcoded English and state issues remain |
| B1-4 | MEDIUM | ⚪ NOT_VERIFIED | Filter/screen-state semantics |
| B1-5 | MEDIUM | ⚪ NOT_VERIFIED | Composition-time mutation |
| B1-6 | LOW | 🔴 STILL_OPEN | UI polish: hardcoded English copy |

### B1-1: Challenge creation (RESOLVED)
- **Evidence**: `MainActivity.kt` lines 663-669 navigate to `NavigationDestination.SpendingChallenges(showCreateDialog = true)`. `SpendingChallengesScreen` catches flag and opens `CreateChallengeDialog`. Dialog calls `viewModel.createChallenge()` → `challengeManager`. Fully wired.

### B1-2: Hardcoded € (STILL_OPEN)
- **Evidence**: Grep found **43 matches** of hardcoded `€`/`"EUR"` across UI files. Files still using raw `"€${...}"` string interpolation:
  - `FinancialStressForecastCard.kt`
  - `RetroTotalsDashboardCard.kt`
  - `RetroBudgetBlockPartyCard.kt`
  - `MonteCarloForecastCard.kt`
  - `FinancialRunwayCard.kt`
  - `SpendingTrendChart.kt`
  - `StatisticalVisualizations.kt`
  - `PeriodBlock.kt`
  - `RecurringExpensesScreen.kt`
  - `AddExpenseSheet.kt`
  - `BentoCard.kt`
  - `VisualSplitEditorScreen.kt`
  - `RetroCategoryBreakdownSheet.kt`
- **Fix**: Replace all with `CurrencyFormatter.format(amount, currencyCode)`. 13+ files.

### B1-3: Challenge UI state (PARTIALLY_AFFECTED)
- Uses `completedActions` from `actionRegistry` for empty-state. Types use `toDisplayLabel()` with hardcoded English. Active-challenges branch renders real UI.

### B1-6: Hardcoded English (STILL_OPEN)
- **Evidence**: Confirmed in multiple domain/UI files. Requires systematic sweep.

---

## BATCH 2: AI Runtime / Provider Contract Hardening

| # | Severity | Status | Summary |
|---|----------|--------|---------|
| B2-1 | MEDIUM | 🟡 PARTIALLY_AFFECTED | Route metadata built after route, but re-routing still possible |
| B2-2 | MEDIUM | 🔴 STILL_OPEN | Duplicated JSON extraction, no file-size pre-check, retry ignores Retry-After |
| B2-3 | MEDIUM | ⚪ NOT_VERIFIED | Sanitization/domain-boundary cleanup |
| B2-4 | MEDIUM | ⚪ NOT_VERIFIED | Router/contract support gaps |
| B2-5 | LOW/MEDIUM | ⚪ NOT_VERIFIED | Review-priority consistency |
| B2-6 | cross-cutting | 🟡 MIXED | Some resolved, others still open |

### B2-1: Route metadata (PARTIALLY_AFFECTED)
- `SmartReceiptAssistService` builds metadata after route choice via `withExecutionMetadata`.
- `usedImageInput()` remains static check, not reflecting actual runtime after fallback.

### B2-2: JSON extraction / file-size (STILL_OPEN)
- `CloudReceiptAssistService` has **duplicated** `extractFirstJsonObject`/`extractFencedJsonObject` (also in `CloudJsonParser.kt`)
- `buildImagePart()` reads entire file into memory without size check (has `MAX_INLINE_IMAGE_BYTES` check but reads first)
- Retry logic does not check `Retry-After` header
- `CloudJsonParser` strict parsing ✅ (RESOLVED sub-item)
- `OnDeviceReceiptAssistService` also has its own `extractFirstJsonObject` (line 149) — duplicated

### B2-6: Mixed
- `GetAiRuntimeStatusUseCase`: sequential capability checks (`.map` not parallel) — STILL_OPEN
- `CloudReceiptAssistService` file-size pre-check — STILL_OPEN
- `AiSettingsRepositoryImpl`: safe enum parsing but no corruption recovery — PARTIALLY_AFFECTED
- `CloudJsonParser` strict parsing — RESOLVED

---

## BATCH 3: Budget / Forecast / Dashboard / Analytics

| # | Severity | Status | Summary |
|---|----------|--------|---------|
| B3-1 | MEDIUM | 🔴 STILL_OPEN | Zero-spend months dropped, sparse-history confidence backward, seasonal trivial |
| B3-2 | MEDIUM | 🟡 PARTIALLY_AFFECTED | Some engines fixed, but AdvancedAnalyticsDashboard still has N+1/placeholders |
| B3-3 | MEDIUM | 🟡 PARTIALLY_AFFECTED | Shared-budget spend calc fixed, but member contributions are placeholder |
| B3-4 | D.2/D.3 | 🔴 STILL_OPEN | MoneyRadar sequential calls, multiple `now` values, hardcoded English |
| B3-5 | Section C | ⚪ NOT_VERIFIED | Dependency cleanup |
| B3-6 | LOW | 🔴 STILL_OPEN | Seasonal adjustment trivial, hardcoded English |

### B3-1: Budget forecasting (STILL_OPEN)
- `BudgetForecastingEngine.getHistoricalSpendingData`: does NOT synthesize zero-spend months — absent months skew averages upward
- Confidence formula rewards sparse history (more months → higher confidence) — mathematically backward
- `calculateSeasonalFactor`: only December gets 1.2x, all others 1.0 — trivially unrealistic

### B3-2: AdvancedAnalyticsDashboard (PARTIALLY_AFFECTED)
- ✅ `AdvancedAnalyticsEngine`: parallel IO, proper TimeProvider, sparklines up to today, percentile edge cases fixed
- ✅ `TotalsAggregationEngine`: thread-safe, proper TimeProvider, aggregate queries
- 🔴 `AdvancedAnalyticsDashboard`:
  - `getMonthlyTrend()` does **N+1 queries** — loop calling `expenseRepository.getExpensesBetween()` per month
  - `getTopCategories()` uses placeholder `"Category $catId"` (line 133) — no real category name
  - `changeFromLastPeriod` hardcoded to `0.0` — never computed
  - `generateInsights()` has hardcoded English descriptions
  - Debug logging has hardcoded `€` (lines 483-486)

### B3-3: SharedBudgetManager (PARTIALLY_AFFECTED)
- ✅ `getSharedBudgetProgress()` uses canonical DAO methods — spend calc RESOLVED
- 🔴 `getMemberContributions()` entirely placeholder: hardcoded `"Member $memberId"`, `amountSpent = 0.0`
- 🔴 `budgetName` uses placeholder `"Category ${budget.categoryId} Budget"`

### B3-4: MoneyRadar (STILL_OPEN)
- Sequential calls: `getDueBills`, `getUnresolvedAnomalies`, `getBudgetRisk` called sequentially (could be parallelized)
- Hardcoded English strings (lines 394-429): `"Due in X days"`, `"Overdue by X days"`
- Second `now` value in `getBudgetRisk` line 239 — can differ from first

---

## BATCH 4: Receipt / OCR / Warranty Runtime Hardening

| # | Severity | Status | Summary |
|---|----------|--------|---------|
| B4-1 | MEDIUM | 🔴 STILL_OPEN | OCR close/recognize lock mismatch — real concurrency bug |
| B4-2 | MEDIUM | 🟡 PARTIALLY_AFFECTED | Parsing improved but negative subtotal fabrication still possible |
| B4-3 | MEDIUM | ✅ RESOLVED | WarrantyTextExtractor now uses java.time throughout |
| B4-4 | MEDIUM/LOW | 🔴 STILL_OPEN | ImageCache ignores dimensions/no eviction, wrong token constant, hardcoded € |
| B4-5 | D.3 | 🟡 PARTIALLY_AFFECTED | lineItemsFromJson handles failure but silently swallows it |

### B4-1: OCR lock race (STILL_OPEN — CONCURRENCY BUG)
- `ReceiptOcrService`: `close()` uses `synchronized(this)` (line 634) while `recognizeText()` uses `recognizerMutex` (kotlinx.coroutines Mutex, line 462) — **two completely different lock primitives**
- `close()` can execute while `recognizeText()` holds the Mutex, potentially closing recognizer mid-OCR
- **Fix**: Unify on one lock mechanism. `close()` should also use the Mutex (make it suspend or use `runBlocking`)

### B4-2: Negative subtotal (PARTIALLY_AFFECTED)
- Line 153: `finalSubtotal = if (finalTotal != null && tax != null) finalTotal - tax else null` — if OCR misreads tax > total, subtotal goes negative. No guard.
- Date validation uses dynamic year range ✅ (RESOLVED)
- Line item deduplication exists ✅ (RESOLVED)
- `detectCurrency` defaults to `"EUR"` — hardcoded but acceptable for Greek locale

### B4-4: ImageCache (STILL_OPEN)
- Cache key is `uri.toString().hashCode().toString()` (line 28) — ignores `maxWidth`/`maxHeight`
- No eviction policy — writes indefinitely to disk
- `CloudReceiptItemCategorizationService`: wrong constant `ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS` for cloud service (line 249)
- `OnDeviceReceiptAssistService.buildImagePart()` reads bytes without size check (line 125)

---

## Fix Plans

### Fix Plan 1: Currency Sweep (B1-2, B1-6)
- **Files**: 13+ UI files with hardcoded `€`/`EUR`, `CurrencyFormatter.kt`
- **Fix**: Replace raw `"€${amount}"` with `CurrencyFormatter.format(amount, currencyCode)`. Thread currency code through composable parameters where missing.
- **Validation**: `rg "€|EUR" app/src/main/java/com/yourname/expensetracker/ui` → zero hits. Compile.

### Fix Plan 2: OCR Lock Race Fix + ImageCache (B4-1, B4-4)
- **Files**: `ReceiptOcrService.kt`, `ImageCache.kt`
- **Fix**: Unify `close()` and `recognizeText()` on same lock (use Mutex for both, or make close suspend). Make ImageCache key include dimensions. Add LRU/size-based eviction.
- **Validation**: Concurrent test for close+recognize. Dimension-different cache test. Compile.

### Fix Plan 3: AI Provider Cleanup (B2-2, B2-6)
- **Files**: `CloudReceiptAssistService.kt`, `CloudReceiptItemCategorizationService.kt`, `OnDeviceReceiptAssistService.kt`, `GetAiRuntimeStatusUseCase.kt`
- **Fix**: Remove duplicated `extractFirstJsonObject`, delegate to `CloudJsonParser`. Add file-size check before `readBytes()`. Fix wrong token constant. Replace hardcoded `€` with currency param. Parallelize capability checks.
- **Validation**: Grep for `ON_DEVICE.*MAX_TOKENS` in cloud services. Compile.

### Fix Plan 4: AdvancedAnalyticsDashboard Fixes (B3-2, B3-6)
- **Files**: `AdvancedAnalyticsDashboard.kt`
- **Fix**: Fix N+1 monthly trend — fetch all expenses once, bucket in memory. Fetch actual category names. Compute `changeFromLastPeriod`. Extract English strings to `UiText` keys.
- **Validation**: `./gradlew.bat :app:testDebugUnitTest --tests "*Analytics*"`. Compile.

### Fix Plan 5: Budget Forecasting Engine Fixes (B3-1)
- **Files**: `BudgetForecastingEngine.kt`
- **Fix**: Synthesize zero-spend months in `getHistoricalSpendingData`. Fix sparse-history confidence (more gaps = lower confidence). Expand seasonal adjustment beyond December-only.
- **Validation**: `./gradlew.bat :app:testDebugUnitTest --tests "*BudgetForecast*"`. Compile.

### Fix Plan 6: MoneyRadar + SharedBudgetManager (B3-3, B3-4)
- **Files**: `ComputeMoneyRadarUseCase.kt`, `SharedBudgetManager.kt`
- **Fix**: Parallelize MoneyRadar's sequential calls. Capture single `now` value. Extract hardcoded English strings. Document or implement member contributions. Fetch real category name.
- **Validation**: `./gradlew.bat :app:testDebugUnitTest --tests "*MoneyRadar*"`. Compile.

---

## Summary

| Status | Count | Issues |
|--------|-------|--------|
| ✅ RESOLVED | 4 | B1-1, B4-3, plus sub-items in B2-6, B3-2 |
| 🔴 STILL_OPEN | 9 | B1-2, B1-6, B2-2, B3-1, B3-4, B3-6, B4-1, B4-4 |
| 🟡 PARTIALLY_AFFECTED | 5 | B1-3, B2-1, B3-2, B3-3, B4-2, B4-5 |
| ⚪ NOT_VERIFIED | 5 | B1-4, B1-5, B2-3, B2-4, B2-5, B3-5 |

**Note**: 5 NOT_VERIFIED issues require reading additional ViewModel/ReviewScreen files before scheduling for implementation.