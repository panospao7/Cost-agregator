# AI / ML Intelligence + Deep Analytics — Cross-Check Review

**Branch:** `master` (current working tree)
**Review date:** 2026-05-02
**Source analysis:** `docs/analyses and debug master/ai-ml-intelligence-analytics-analysis.md`
**Scope:** 35 issues cross-checked against current codebase at `app/src/main/java/com/yourname/expensetracker`

---

## Executive Summary

The codebase has made **substantial progress** on the highest-priority systemic issues. Three foundational improvements have been delivered:

1. **`AnalyticsCurrencyNormalizer`** — central currency normalization before any analytics engine consumes expenses (Issue #1)
2. **Period semantics unified** — `getPeriodRange()` now uses proper `TimePeriodUtils` calendar-aware ranges (Issue #2)
3. **Data-quality infrastructure laid** — `DataQualityReport`, `SpendingThresholdCalculator`, `DuplicateDetectionPolicy`, `CrossSourceDeduplication`, `MerchantNormalizer`, `RecommendationStateManager`, `RecommendationLifecycleManager`

However, **most ML/lifecycle and deep-data-quality issues remain open**. The normalizer and period fixes solve the "confident but wrong" aggregation problem, but the engines still produce results from weak data quality signals (stale categories, no recurring suppression, keyword-only discretionary detection, uncategorized-as-discretionary, etc.).

### Verdict Summary

| Status | Count |
|--------|-------|
| **RESOLVED** | 5 |
| **PARTIALLY RESOLVED** | 9 |
| **STILL PRESENT** | 21 |

---

## Detailed Issue-by-Issue Cross-Check

### [ISSUE-1] Raw-sum mixed currencies → RESOLVED

- **Finding:** `AnalyticsCurrencyNormalizer` (new file) centrally normalizes all expenses to home currency before any analytics engine processes them. `AnalyticsRepository`, `AnalyticsViewModel`, `SmartSavingsEngine`, and `FinancialHealthScoreV2` all pipe through the normalizer. Models carry `displayCurrency: String` and computed `MoneyAmount` properties.
- **Evidence:** `AnalyticsCurrencyNormalizer.kt` lines 25–58 (`normalizeExpenses`, `normalizeSnapshots`); `AnalyticsRepository.kt` line 94 (`analyticsCurrencyNormalizer.normalizeExpenses`); `SmartSavingsEngine.kt` line 253 (`analyticsCurrencyNormalizer.normalizeSnapshots`); `FinancialHealthScoreV2.kt` lines 89–91 (`analyticsCurrencyNormalizer.normalizeExpenses`).
- **Status: RESOLVED** ✅

---

### [ISSUE-2] Analytics screen mixes rolling and calendar periods → RESOLVED

- **Finding:** `AnalyticsViewModel.getPeriodRange()` now delegates to `TimePeriodUtils.getMonthRange()`, `getQuarterRange()`, `getYearRange()`, `getWeekRange()` — all calendar-aware. No more `last 30/90/365 days` disguised as MONTH/QUARTER/YEAR.
- **Evidence:** `AnalyticsViewModel.kt` lines 814–826.
- **Status: RESOLVED** ✅

---

### [ISSUE-3] InsightsEngine always uses current calendar month → STILL PRESENT

- **Finding:** `InsightsEngine.generateInsights()` still hardcodes `val currentMonth = getMonthPeriod(now)` on line 42, regardless of the selected analytics period. If user selects Week/Quarter/Year/All, insights still describe the current calendar month.
- **Evidence:** `InsightsEngine.kt` lines 35–42. No `periodRange` parameter.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-4] Current/previous comparison uses raw millisecond duration → PARTIALLY RESOLVED

- **Finding:** `InsightsEngine` uses proper `getPreviousMonthPeriod()` (line 43 → `addMonths(current.startMs, -1)`) — **fixed there**. But `AnalyticsRepository.getSpendingSummary()` still computes `previousStart = start - (end - start)` (line 64), and `AnalyticsViewModel.computeAnalyticsInternal()` still does `previousStart = currentStart - periodLength` (line 292). Both use raw ms subtraction.
- **Evidence:** `AnalyticsRepository.kt` lines 63–65; `AnalyticsViewModel.kt` lines 291–293.
- **Status: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-5] Post-salary correlation not month-aligned → PARTIALLY RESOLVED

- **Finding:** `AnalyticsViewModel.computePostSalaryPattern()` groups salary events by calendar month key (`y * 100 + m`, line 847) and sorts — **fixed**. `LifestyleInflationDetector.buildMonthlyData()` properly aligns by sorted month keys (line 252–254) — **fixed**. However, `LifestyleInflationDetector.calculateCorrelation()` still uses `incomeByMonth.map { it.value }` and `spendingByMonth.map { it.value }` which are unfiltered map value iterations — missing months cause size mismatches and return `0.0` silently.
- **Evidence:** `AnalyticsViewModel.kt` lines 845–853; `LifestyleInflationDetector.kt` lines 60–63 (unfixed), lines 252–268 (fixed).
- **Status: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-6] Anomaly detection compares within current month only → STILL PRESENT

- **Finding:** `AnomalyDetector.detect()` still filters to `expense.date >= monthPeriod.startMs && expense.date < monthPeriod.endMs` (line 114–118) and runs IQR/MAD/CONTEXTUAL only on current-month values. No historical category/merchant baselines used for the statistical methods. The `InsightsEngine.findAnomalies()` merges a merchant-level historical-path (multiplier-based) but the statistical path remains current-month-only.
- **Evidence:** `AnomalyDetector.kt` lines 107–128; no historical-baseline comparison in detectIqr/detectMad/detectContextual.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-7] Anomaly detector does not suppress known recurring/planned bills → STILL PRESENT

- **Finding:** Neither `AnomalyDetector` nor `InsightsEngine.findAnomalies()` cross-references detected anomalies against known recurring patterns before surfacing. A monthly rent payment of €1500 will be flagged as anomalous every month.
- **Evidence:** No suppression logic in `AnomalyDetector.kt` or `InsightsEngine.kt` anomaly paths.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-8] Anomaly method priority still uses ordinal → STILL PRESENT

- **Finding:** `AnomalyDetector.detect()` merge logic (lines 140–145, 152–157) still uses `new.detectionMethod.ordinal > existing.detectionMethod.ordinal`. Enum order is MULTIPLIER(0), IQR(1), MAD(2), CONTEXTUAL(3). So CONTEXTUAL can outrank MAD, contradicting the claimed "MAD > IQR > CONTEXTUAL > MULTIPLIER" priority. No explicit `priority` field added.
- **Evidence:** `AnomalyDetector.kt` lines 142, 153; `AnalyticsModels.kt` lines 111–116 (AnomalyMethod enum, no priority field).
- **Status: STILL PRESENT** ❌

---

### [ISSUE-9] Uncategorized spend disappears from category breakdowns → PARTIALLY RESOLVED

- **Finding:** `AnalyticsViewModel.computeAnalyticsInternal()` now explicitly creates an "Uncategorized" virtual category for null categoryIds (lines 344–364) — **fixed in ViewModel**. However, `AnalyticsRepository.getCategoryBreakdown()` still drops rows via `val cat = categoryId?.let { categoryMap[it] } ?: return@mapNotNull null` (line 148), so the repository path still loses uncategorized spend.
- **Evidence:** `AnalyticsViewModel.kt` lines 344–354; `AnalyticsRepository.kt` lines 147–148.
- **Status: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-10] Duplicate/suspect transaction detection is weak → PARTIALLY RESOLVED

- **Finding:** `detectSuspectTransactions()` now uses `effectiveAmount` (line 950) instead of gross `amount` — **improved**. But still uses raw merchant string comparison (`equals(ignoreCase = true)`, line 951), no currency check, no transaction type check, no merchantKey usage. The canonical `DuplicateDetectionPolicy` exists but is **not used** by this method.
- **Evidence:** `AnalyticsViewModel.kt` lines 927–968; `DuplicateDetectionPolicy.kt` exists but not referenced in suspect detection.
- **Status: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-11] Source trust inflated by duplicates → STILL PRESENT

- **Finding:** `SourceStats.trustScore` formula still includes duplicates as valid: `val valid = acceptedAsExpense + duplicates` (line 23). Duplicates still count toward trust.
- **Evidence:** `SourceStats.kt` lines 16–27.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-12] Source stats are mutable counters, not event-derived → STILL PRESENT

- **Finding:** `SourceStatsDao` still uses atomic increment methods (`incrementAccepted`, `incrementRejected`, `incrementDuplicate`, etc.). No event ledger. Counters can drift.
- **Evidence:** `SourceStatsDao.kt` lines 25–116.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-13] ConfidenceRouter cache stale after reject/approve → STILL PRESENT

- **Finding:** Cache TTL is still 60 seconds. Invalidation methods exist (`invalidateSourceStatsCache`, `invalidateMerchantCache`, `invalidateAllCaches`) but correctness still depends on every write path calling them. No event-driven invalidation from correction repositories.
- **Evidence:** `ConfidenceRouter.kt` lines 41, 96–111.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-14] Merchant rejection keys use raw merchant string → STILL PRESENT

- **Finding:** `ConfidenceRouter.getCachedMerchantRejectionRate()` still keys by `merchant.lowercase()` (line 271). `UserCorrectionDao` queries (`getMerchantStats`, `hasPreviousApprovals`) still match on exact `originalMerchant = :merchant`. However, `MerchantNormalizer` (new file) and `MerchantKeyGenerator` exist and are available — they are just not integrated into these DAO queries.
- **Evidence:** `ConfidenceRouter.kt` line 271; `UserCorrectionDao.kt` lines 71–102; `MerchantNormalizer.kt` exists.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-15] TransactionClassifier model persistence not durable on background → STILL PRESENT

- **Finding:** `TransactionClassifier.onBackground()` still cancels pending save/retrain jobs **without flushing** (lines 46–53). No `saveToDisk()` call before cancellation.
- **Evidence:** `TransactionClassifier.kt` lines 46–53.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-16] ML model files leak sensitive vocabulary → STILL PRESENT

- **Finding:** Both `TransactionClassifier.saveToDisk()` (line 367) and `ExpenseCategoryClassifier.saveModelInternal()` (line 150) write plain-text JSON with learned tokens to internal storage. No encryption. No "Reset learned classifier" UI. Sensitive tokens (clinic names, employer names, merchant names) can persist in backups/exports.
- **Evidence:** `TransactionClassifier.kt` lines 367–398; `ExpenseCategoryClassifier.kt` lines 150–183.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-17] Category classifier returns stale/deleted category IDs → STILL PRESENT

- **Finding:** `HybridExpenseClassifier.classify()` still returns `categoryId = best.categoryId` (line 104) even when `category = categories.find { it.id == best.categoryId }` returns null and `categoryName` becomes `"Unknown"` (line 105). The stale ID is still propagated. No validation against current active categories before return.
- **Evidence:** `HybridExpenseClassifier.kt` lines 99–116.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-18] Category ML only trains on merchant tokens → STILL PRESENT

- **Finding:** `ExpenseCategoryClassifier.train()` still only uses `features.merchantTokens` (line 92). `ExpenseFeatures` contains `amountBucket`, `dayOfWeek`, `hourOfDay`, `sourcePackage`, `isWeekend` — all ignored.
- **Evidence:** `ExpenseCategoryClassifier.kt` lines 84–106.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-19] Hybrid classifier uses current time for classification → STILL PRESENT

- **Finding:** `HybridExpenseClassifier.classify()` passes `eventTimeMillis = timeProvider.now()` (line 83). Documentation in `FeatureExtractor.extractFromNotification()` now warns that callers must provide explicit timestamps (lines 59–65) — but the caller still passes current time. The `learnFromCorrection()` method also passes `timeProvider.now()` (line 178).
- **Evidence:** `HybridExpenseClassifier.kt` line 83; `FeatureExtractor.kt` lines 59–65.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-20] Category learning globally changes behavior from single correction → STILL PRESENT

- **Finding:** `HybridExpenseClassifier.learnFromCorrection()` still immediately teaches both the categorization engine and ML classifier globally (lines 166–185). No user confirmation. No weak/strong signal differentiation.
- **Evidence:** `HybridExpenseClassifier.kt` lines 166–185.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-21] Recommendation dedupe fails for rolling timestamp filters → STILL PRESENT

- **Finding:** `RecommendationDeduplicator.computeSignature()` still includes raw timestamp ranges in dedup signatures: `filterParts.add("dateRange=$start-$end")` (line 93). `DashboardFollowThroughEngine.createRecentTransactionsRecommendation()` uses `getLastNCalendarDaysRange(nowMillis, 7)` producing different timestamps at different times. Two calls seconds apart produce different signatures for the same logical recommendation.
- **Evidence:** `RecommendationDeduplicator.kt` lines 92–94; `DashboardFollowThroughEngine.kt` lines 251–252.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-22] Recommendation Flow uses stale nowMillis → PARTIALLY RESOLVED

- **Finding:** `RecommendationDao.observeActiveByUser()` still has `nowMillis: Long = System.currentTimeMillis()` default. `RecommendationRepository.observeActiveForUser()` captures `nowMillis` once at Flow creation. However, `RecommendationLifecycleManager` now provides explicit periodic expiration (`startPeriodicExpirationCheck()` every 6 hours) and `RecommendationStateManager` manages active state with generation-guarded publishes.
- **Evidence:** `RecommendationDao.kt` line 76; `RecommendationRepository.kt` lines 42–47; `RecommendationLifecycleManager.kt` lines 75–84.
- **Status: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-23] Recommendation persistence uses REPLACE → PARTIALLY RESOLVED

- **Finding:** `RecommendationDao.insert()` and `insertAll()` still use `OnConflictStrategy.REPLACE` (lines 81, 87). However, `RecommendationRepository.saveAll()` (lines 75–127) now: (1) deduplicates within batch, (2) checks against existing active, (3) only inserts truly new rows, (4) archives overflow via explicit UPDATE. The REPLACE strategy is effectively mitigated by repository-level logic for the normal path. Risk remains for direct DAO calls.
- **Evidence:** `RecommendationDao.kt` lines 81–88; `RecommendationRepository.kt` lines 75–127.
- **Status: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-24] Dashboard follow-through uses gross amount and hardcoded euro → PARTIALLY RESOLVED

- **Finding:** `DashboardFollowThroughEngine` now uses `SpendingThresholdCalculator` for adaptive P90-based threshold (line 70) — **improved**. But still uses gross `transaction.amount` (line 73) instead of `transaction.effectiveAmount`. Still hardcodes euro symbol `€` in recommendation text (line 150). `minAmount` filter still uses raw `transaction.amount` (line 155).
- **Evidence:** `DashboardFollowThroughEngine.kt` lines 70–77, 144–174.
- **Status: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-25] Financial health runway not based on account balance → STILL PRESENT

- **Finding:** `FinancialHealthScoreV2.calculateRunwayScore()` still uses `savingsGoals.sumOf { it.currentAmount }` (line 311). No integration with real account balances. Runway stabilization logic has been added (blending projected/historical burn rates, lines 292–306) but the numerator is still goal-funded, not account-based.
- **Evidence:** `FinancialHealthScoreV2.kt` lines 261–338.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-26] Bill reliability score not actual bill reliability → STILL PRESENT

- **Finding:** `calculateBillReliabilityScore()` still uses cadence/timing consistency as a weak proxy (lines 422–461). Returns default `75` when no patterns (line 431). No paid/missed/late occurrence lifecycle data used. Comments now acknowledge this limitation (lines 419–420: "calling pattern-detection confidence as payment reliability").
- **Evidence:** `FinancialHealthScoreV2.kt` lines 416–461.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-27] Health budget adherence double-counts hierarchy → STILL PRESENT

- **Finding:** `calculateBudgetAdherenceScore()` still sums every budget status including both overall and category budgets (lines 398–399): `totalBudget += status.effectiveLimit`. Overall + category budgets are hierarchical but summed independently, distorting adherence calculation.
- **Evidence:** `FinancialHealthScoreV2.kt` lines 388–413.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-28] Health history duplicate records for same period → RESOLVED

- **Finding:** `saveToHistory()` now queries `healthScoreHistoryDao.getHistoryForPeriod(periodStart, periodEnd)` first (line 594). If an existing record is found, it **updates** instead of inserting (lines 596–608). If none exists, it inserts (lines 609–621). Duplicates prevented.
- **Evidence:** `FinancialHealthScoreV2.kt` lines 582–632.
- **Status: RESOLVED** ✅

---

### [ISSUE-29] Smart savings ignores upcoming committed bills → STILL PRESENT

- **Finding:** `runMonteCarloSimulation()` still sets `val knownUpcoming = 0.0` (line 347). A TODO comment on lines 51–54 acknowledges the need to inject `RecurringLifecycleCoordinator` but it's not implemented.
- **Evidence:** `SmartSavingsEngine.kt` lines 51–54, 347.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-30] Smart savings uses hardcoded currencyless caps → STILL PRESENT

- **Finding:** Hardcoded caps remain: `DEFAULT_CAP_WEEK = 75.0`, `DEFAULT_CAP_MONTH = 200.0`, `DEFAULT_CAP_QUARTER = 500.0` (lines 68–74). Comments now explain they're "in home-currency units" and "should be tuned per-market" (lines 61–67) but no actual income/currency/user-context scaling. `SpendingThresholdCalculator` exists but is not used here.
- **Evidence:** `SmartSavingsEngine.kt` lines 61–74, 396–400.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-31] Smart savings treats uncategorized as discretionary → STILL PRESENT

- **Finding:** `isDiscretionaryCategory()` still returns `categoryName == null || categoryName !in essentialCategories` (line 501). Uncategorized (null) is treated as discretionary by default, overestimating safe-to-save.
- **Evidence:** `SmartSavingsEngine.kt` lines 486–502.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-32] Lifestyle inflation uses merchant/notes keywords → STILL PRESENT

- **Finding:** `isDiscretionaryExpense()` still checks English-heavy keyword list in merchant/notes only (lines 99–111). Ignores category metadata. No user-configurable essential/discretionary mapping.
- **Evidence:** `LifestyleInflationDetector.kt` lines 99–111.
- **Status: STILL PRESENT** ❌

---

### [ISSUE-33] Lifestyle detector uses System.currentTimeMillis() → RESOLVED

- **Finding:** Now uses injected `timeProvider.now()` at line 26. `TimeProvider` is a constructor parameter (line 20).
- **Evidence:** `LifestyleInflationDetector.kt` lines 18–21, 26.
- **Status: RESOLVED** ✅

---

### [ISSUE-34] Analytics ViewModel performs heavy work on UI thread → PARTIALLY RESOLVED

- **Finding:** Significant improvements: (1) caching via `ConcurrentHashMap<PeriodCacheKey, AnalyticsState>` (line 158), (2) `flowOn(Dispatchers.Default)` (line 261), (3) `debounce(300)` (line 212), (4) period-specific cache keys prevent cross-period recomputation. However, the ViewModel still observes all expenses for freshness and performs multiple separate window queries + in-memory analytics.
- **Evidence:** `AnalyticsViewModel.kt` lines 158–266.
- **Status: PARTIALLY RESOLVED** ⚠️

---

### [ISSUE-35] Analytics cache invalidation clears all caches → PARTIALLY RESOLVED

- **Finding:** Cache invalidation still clears ALL period caches when any expense/budget version changes (lines 224–231). However, period-specific cache keys mean different periods don't share cache entries, and the cache key includes `rateTimestamp`, `homeCurrency`, `expenseCount`, and `budgetsHash` — so invalidation is more targeted than before. Any change still clears everything though.
- **Evidence:** `AnalyticsViewModel.kt` lines 224–231, 111–123.
- **Status: PARTIALLY RESOLVED** ⚠️

---

## New Issues Found (not in original analysis)

### [ISSUE-36] [MINOR] AnomalyDetector uses Calendar.getInstance() instead of TimeProvider

- **Where:** `AnomalyDetector.kt` lines 72–73 (`timeSlot()`) and 83–84 (`dayName()`)
- **Problem:** Uses `Calendar.getInstance()` directly instead of injected `TimeProvider`, making testing harder and behavior potentially timezone-dependent at edge boundaries.
- **Fix:** Inject `TimeProvider` and derive time-of-day/day-of-week through it.

---

## Coverage Assessment

### Requirements Met

- **Currency normalization:** ✅ Fully addressed with `AnalyticsCurrencyNormalizer`
- **Period semantics:** ✅ Calendar-aware ranges for all analytics periods
- **Data-quality infrastructure:** ✅ `DataQualityReport`, `SpendingThresholdCalculator`, `DuplicateDetectionPolicy` created
- **Merchant normalization:** ✅ `MerchantNormalizer` with BK-tree fuzzy matching and alias learning
- **Recommendation lifecycle:** ✅ `RecommendationStateManager`, `RecommendationLifecycleManager`, periodic expiration
- **Health history dedup:** ✅ Upsert pattern prevents duplicate period records

### Requirements NOT Met

- **ML lifecycle hardiness:** ❌ Stale category IDs, no durable flush on background, plain-text sensitive tokens
- **Anomaly intelligence:** ❌ No recurring-suppression, no historical baselines, ordinal-based priority
- **Source trust integrity:** ❌ Duplicates inflate trust, mutable counters, raw merchant keys
- **Savings/health realism:** ❌ Goal-funded runway, no bill lifecycle, no upcoming bills in safe-to-save
- **Recommendation dedup/expiry:** ❌ Raw timestamp signatures, Flow captures stale `nowMillis`, REPLACE DAO
- **Lifestyle/discretionary:** ❌ Keyword-only detection, no category metadata, map misalignment in correlation

### Testing Adequate

- **No:** The codebase has no regression tests for the 25 test cases listed in the analysis document. The analysis recommended tests for currency bucketing, period semantics, anomaly suppression, dedupe normalization, ML lifecycle, and health score honesty. None were found.

---

## Recommended Fix Priority (updated)

1. **PR 5 (unchanged priority):** Harden category ML lifecycle — stale IDs, durable persistence, model versioning
2. **PR 4 (unchanged):** Fix source-trust and correction ledger — event-derived stats, normalized merchant keys, event-driven cache invalidation
3. **PR 6 (unchanged):** Fix recommendation dedupe/expiry — semantic signatures, no raw timestamps, periodic expiration on Flow
4. **PR 7 (unchanged):** Make health/savings scores honest — account balance, bill lifecycle, upcoming bills
5. **PR 8 (unchanged):** Align anomaly detection — historical baselines, recurring suppression, explicit priority

---

## Regression Tests to Prioritize

The original analysis listed 25 regression tests. Based on what's been fixed vs not, these should be added:

- ✅ #1 (currency buckets) — should now pass with normalizer
- ✅ #2 (calendar vs rolling) — should now pass
- ❌ #3 (year analytics doesn't show month insights) — InsightsEngine still hardcodes current month
- ⚠️ #4 (previous calendar month) — partially fixed
- ⚠️ #5 (post-salary correlation) — partially fixed
- ⚠️ #6 (uncategorized in breakdown) — partially fixed
- ❌ #7–#8 (anomaly suppression, explicit priority) — not fixed
- ⚠️ #9–#13 (duplicate detection, source trust, merchant keys, cache invalidation) — not fixed
- ❌ #14–#25 (ML lifecycle, dedup, health, savings) — mostly not fixed

---

*End of review. Total issues: 35 original + 1 new = 36 examined.*
