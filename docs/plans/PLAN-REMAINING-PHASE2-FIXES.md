# Plan: Remaining Phase 2 Issue Fixes

## Overview
Total remaining issues: 32
Prioritized by CRITICAL > HIGH > MEDIUM

- Reviewer-derived remainder in scope: B.2, B.3, B.6, B.9, B.10, B.11.
- Current severity mix: no remaining CRITICAL items in this subset; all 32 remaining reviewer issues are HIGH.
- Recommended execution order:
  1. Financial correctness / forecast pipeline (B.2)
  2. Receipt-assist privacy + matching reliability (B.3 intake path)
  3. Warranty lifecycle + return-window persistence (B.3 lifecycle path)
  4. Navigation / UI presentation / Google Wallet cleanup (B.6, B.9, B.11)
  5. Spending challenges + intelligence completion (B.9, B.10)
- Highest-risk unknowns to verify before coding:
  - Whether the on-device GenAI API can actually accept image parts for receipt assist.
  - What the canonical anomaly deep-link target should be, since there is no dedicated transaction-detail destination.
  - Spending-challenge completion likely requires new persisted storage and therefore a Room migration lane.

---

## Batch 1: Forecast / Sweep / Radar Completion (8 issues)
### Issues
1. [B.2][HIGH][sources: B02] `BudgetRecommendationEngine.potentialSavings` can go negative.
2. [B.2][HIGH][sources: B05/B48] `CalculateFinancialForecastUseCase` still feeds `SynthesisEngine` placeholder inputs.
3. [B.2][HIGH][sources: B05] `MonthlySavingsSweepUseCase` still hardcodes `knownUpcoming = 0.0`.
4. [B.2][HIGH][sources: B05] Goal allocations are still not capped by remaining goal gap before allocation.
5. [B.2][HIGH][sources: B05/B48] `ComputeMoneyRadarUseCase.getBudgetRisk()` still counts future-dated purchases.
6. [B.2][HIGH][sources: B05] `CalculateFinancialForecastUseCase` still lacks day/month rollover recomputation.
7. [B.2][HIGH][sources: B01] `TotalsAggregationEngine` still omits zero-spend periods / stable zero buckets.
8. [B.2][HIGH][sources: B48] `ComputeMoneyRadarUseCase` urgency still uses only overrun probability, not magnitude / risk tier.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationEngine.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/forecast/CalculateFinancialForecastUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngineValidationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetRecommendationEngineTest.kt`

### Approach
- Replace placeholder forecast inputs with real derived cumulative history, real spending-pace inputs, and real goal-protection mapping from persisted goal entities.
- Add a rollover trigger to `CalculateFinancialForecastUseCase` so flows recompute at day/month boundaries even when repositories stay silent.
- Feed `MonthlySavingsSweepUseCase` with real known upcoming obligations before month-end, then cap each suggested allocation by remaining goal gap before applying concentration rules.
- In `ComputeMoneyRadarUseCase`, exclude future-dated purchases from spent-to-date and combine probability, expected overrun, and risk tier into urgency scoring.
- Zero-fill daily/weekly/monthly totals inside `TotalsAggregationEngine` so charts and drill-downs keep stable chronological buckets.
- Clamp `BudgetRecommendationEngine.potentialSavings` at zero so recommendation copy never shows negative savings.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.forecast.CalculateFinancialForecastUseCaseTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.savings.MonthlySavingsSweepUseCaseTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.dashboard.ComputeMoneyRadarUseCaseTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.analytics.TotalsAggregationEngineTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.analytics.TotalsAggregationEngineValidationTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.budget.BudgetRecommendationEngineTest"`

### Done when
- Forecast synthesis no longer ships fake history / pace / goal-protection values.
- Month-end sweep uses real upcoming obligations and never overfunds a goal past its remaining gap.
- Money Radar spent-to-date excludes future rows and urgency changes when overrun magnitude/risk tier changes.
- Totals APIs return explicit zero buckets for empty periods.
- Budget recommendations never expose negative savings.

---

## Batch 2: Receipt-Assist Privacy, Vision, Parser, and Matching Reliability (6 issues)
### Issues
1. [B.3][HIGH][sources: B08/B09] `CloudReceiptAssistService` uploads raw images when `redactBeforeCloud=true`.
2. [B.3][HIGH][sources: B09/B26] `OnDeviceReceiptAssistService` still does not attach image input and remains text-only.
3. [B.3][HIGH][sources: B45] `ReceiptParser` line-item extraction still double-adds overlapping quantity-formatted lines.
4. [B.3][HIGH][sources: B45] `ReceiptTransactionMatcher` still treats any positive-amount transaction as receipt-compatible.
5. [B.3][HIGH][sources: B45] `ReceiptTransactionMatcher.normalizeMerchant()` still strips non-ASCII characters and can collapse Greek merchants.
6. [B.3][HIGH][sources: B45] `ReceiptMatchingWorker` still retries permanent failures indefinitely.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt`
- `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt`
- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistServiceTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistServiceTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistServiceTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/ReceiptParserTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/ReceiptParserOcrPatternsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcherTest.kt`

### Approach
- Settle the redaction contract first: if cloud image redaction is not available, suppress cloud image upload whenever redaction is required instead of sending raw images.
- Verify whether the on-device GenAI API supports image parts; if yes, wire a real multimodal request; if no, gate/metadata-align the feature so the route no longer falsely claims vision capability.
- Deduplicate `ReceiptParser` line-item outputs after pattern extraction using normalized description/quantity/amount keys.
- Restrict receipt-to-expense matching candidates to purchase/spending-compatible rows only, and replace ASCII-only merchant normalization with Unicode-safe normalization.
- Split worker failures into permanent vs transient categories so malformed data / logical conflicts stop retrying while actual transient failures still retry.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.CloudReceiptAssistServiceTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.OnDeviceReceiptAssistServiceTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.SmartReceiptAssistServiceTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receipt.ReceiptParserTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receipt.ReceiptParserOcrPatternsTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receiptmatching.ReceiptTransactionMatcherTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.receiptmatching.ReceiptMatchingWorkerTest"`

### Done when
- Redaction-enabled cloud receipt assist never sends a raw image.
- On-device receipt assist either sends a real image part or is honestly capability-gated.
- Quantity-formatted receipt lines cannot be emitted twice.
- Receipt matching only suggests purchase-compatible transactions and handles Greek/non-ASCII merchants correctly.
- Permanent receipt-matching failures stop looping forever.

---

## Batch 3: Warranty Lifecycle and Return-Window Persistence Closeout (6 issues)
### Issues
1. [B.3][HIGH][sources: B45] `WarrantyTextExtractor.isReasonablePurchaseDate()` still rejects receipts older than one year.
2. [B.3][HIGH][sources: B05] `AutoCreateWarrantyFromReceiptUseCase` still conflicts between low-confidence draft creation and later review confirmation on the same `receiptId`.
3. [B.3][HIGH][sources: B09] `CloudWarrantyExtractionService` still returns `null` for return-policy-only receipts.
4. [B.3][HIGH][sources: B09] Extracted `returnDays` / `returnConditions` are still ignored during persistence.
5. [B.3][HIGH][sources: B12] Warranty extraction still uses fixed 30-day month math instead of calendar-month addition.
6. [B.3][HIGH][sources: B14] Warranty / return-window rows still lack production expiry reconciliation.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyDao.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ReturnWindowDao.kt`
- `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionServiceTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepositoryTest.kt`
- `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt`

### Approach
- Relax old-date gating in `WarrantyTextExtractor` so multi-year receipts remain eligible while future/impossible dates are still rejected.
- Convert review-confirmation flow from “insert a second warranty” to “promote/update the existing draft for the same receipt.”
- Extend warranty-extraction parsing so return-policy-only responses survive instead of collapsing to `null` when `warrantyMonths` is absent.
- Persist extracted `returnDays` / `returnConditions` directly into `ReturnWindow` creation, and switch warranty end-date math to calendar-month addition to match UI behavior.
- Reuse the existing warranty expiration worker (or repository helper it calls) to reconcile ACTIVE / RETURNABLE rows into EXPIRED without schema changes.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receipt.WarrantyTextExtractorTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.usecase.warranty.AutoCreateWarrantyFromReceiptUseCaseTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.ai.provider.CloudWarrantyExtractionServiceTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.WarrantyTrackerRepositoryTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.service.warranty.WarrantyExpirationWorkerTest"`

### Done when
- Older receipts can still generate warranties / return windows.
- Reviewing a low-confidence warranty no longer collides with an existing draft for the same receipt.
- Return-policy-only receipts still create a persisted `ReturnWindow`.
- Extracted return-window metadata is no longer dead code.
- Warranty end dates match calendar-month semantics.
- Expired warranty/return-window rows transition out of ACTIVE/RETURNABLE in production code.

---

## Batch 4: Navigation, Day-Header Totals, Currency Centralization, and Google Wallet Cleanup (5 issues)
### Issues
1. [B.6][HIGH][sources: B20] Anomaly notifications still deep-link to unsupported host `expensetracker://transaction/{id}`.
2. [B.6][HIGH][sources: B20] `NavigationAction.ToAnalytics(period)` / `ToMap(location)` payloads are still dropped by `HomeScreen` and `MainActivity`.
3. [B.9][HIGH][sources: B16/B17] `TransactionsScreen` day headers still sum unsigned `effectiveAmount` and color expense-heavy days green.
4. [B.9][HIGH][sources: B18] Currency presentation is still not centralized; many UI surfaces hardcode `€`.
5. [B.11][HIGH][sources: B43] `GoogleWalletParser` transfer detection is still too broad and can relabel ordinary purchases as transfers.

### Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/service/AndroidNotificationService.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/service/NavigationTargetResolver.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/navigation/NavigationDestination.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/navigation/NavigationController.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/util/CurrencyFormatter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/GoogleWalletParser.kt`
- Grep-identified high-traffic hardcoded-currency callers:
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsScreen.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapScreen.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsScreen.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/components/CategoryBreakdownSheet.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialWeatherCard.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/components/BudgetBlockPartyCard.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/parser/GoogleWalletParserTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeScreenWidgetTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/ui/MainActivityDeepLinkTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsScreenTest.kt`

### Approach
- Replace unsupported anomaly URIs with a supported host/query contract handled by `MainActivity`; if no transaction-detail screen exists, route to Transactions with a focused filter or stored target id rather than inventing a fake host.
- Thread analytics period and map location payloads through `HomeScreen` callbacks into typed navigation destinations instead of using parameterless tab switches.
- Fix transaction day-header totals to use signed transaction polarity and consistent currency formatting.
- Promote `CurrencyFormatter` to the canonical UI currency formatter, then sweep remaining high-traffic hardcoded-`€` surfaces through a shared formatting API.
- Narrow `GoogleWalletParser` transfer classification so only explicit P2P cues trigger `TRANSFER`, while merchant/card purchase wording remains `PURCHASE`.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.parser.GoogleWalletParserTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.MainActivityDeepLinkTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.transactions.TransactionsScreenTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.home.HomeScreenWidgetTest"`

### Done when
- Anomaly notifications always open a supported destination.
- Analytics period and map location payloads survive end-to-end navigation.
- Day headers display signed daily totals with correct color semantics.
- High-traffic UI surfaces stop hardcoding `€`.
- Google Wallet purchases no longer get mislabeled as P2P transfers.

---

## Batch 5: Spending Challenges Persistence and Remaining Intelligence Fixes (7 issues)
### Issues
1. [B.9][HIGH][sources: B19] Spending challenges remain feature-incomplete; UI only surfaces an unavailable state.
2. [B.10][HIGH][sources: B38] `SpendingChallengeManager.checkNoSpendStreak()` still performs one DB read per day walking backward.
3. [B.10][HIGH][sources: B38] Budget-style challenge progress/completion logic is still wrong and can complete fresh under-budget challenges immediately.
4. [B.10][HIGH][sources: B38/B38-missed] `CategoryKeywords` duplicate/tie behavior still depends on declaration order.
5. [B.10][HIGH][sources: B01] `AnomalyDetector` still bails out on zero-dispersion series and misses obvious spikes.
6. [B.10][HIGH][sources: B38] `REDUCE_SPENDING` challenges still have no stored baseline/reference period.
7. [B.10][HIGH][sources: B38] Challenge creation is still in-memory only; there is no persisted active-challenge source.

### Files to modify
- create: `app/src/main/java/com/yourname/expensetracker/data/database/entity/SpendingChallengeEntity.kt`
- create: `app/src/main/java/com/yourname/expensetracker/data/database/dao/SpendingChallengeDao.kt`
- create: `app/src/main/java/com/yourname/expensetracker/data/repository/SpendingChallengeRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`
- `app/src/main/java/com/yourname/expensetracker/di/DaoModule.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/categorization/CategoryKeywords.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnomalyDetector.kt`
- `app/src/androidTest/java/com/yourname/expensetracker/data/database/DatabaseMigrationTest.kt`
- `app/src/androidTest/java/com/yourname/expensetracker/data/database/MigrationContractTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesViewModelTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/AnomalyDetectorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/categorization/CategorizationComponentsTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/challenge/SpendingChallengeManagerTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/categorization/CategoryKeywordsTest.kt`

### Approach
- Treat this as the only likely schema/migration batch: add a canonical persisted challenge table/source before fixing challenge UI semantics.
- Persist baseline/reference-period fields for `REDUCE_SPENDING` challenges so progress compares against a stored baseline, not a fabricated current target.
- Replace day-by-day streak reads with one bounded aggregate/range query or grouped-day read from `ExpenseDao`.
- Redefine challenge completion semantics so budget/category/reduce-spending challenges complete at the right lifecycle point instead of immediately at 0 spend.
- Normalize keyword data and matcher ordering so equal-confidence duplicate keywords resolve deterministically by confidence/specificity rules, not source declaration order.
- Add a zero-dispersion fallback in `AnomalyDetector` for cases like `[10,10,10,100]` while preserving existing low-sample guards.

### Validation
- `./gradlew.bat :app:compileDebugKotlin`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.challenge.SpendingChallengeManagerTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.challenge.SpendingChallengesViewModelTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.analytics.AnomalyDetectorTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.categorization.CategoryKeywordsTest"`
- `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.categorization.CategorizationComponentsTest"`
- `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.data.database.DatabaseMigrationTest,com.yourname.expensetracker.data.database.MigrationContractTest`

### Done when
- The challenges screen reads from a real persisted active-challenge source.
- `NO_SPEND`, `BUDGET_LIMIT`, `CATEGORY_SPECIFIC`, and `REDUCE_SPENDING` all use correct progress/completion semantics.
- `REDUCE_SPENDING` challenges store and reuse their baseline/reference period.
- No-spend streak calculation is bounded and no longer issues one DB read per day.
- Keyword ties are deterministic and no longer depend on declaration order.
- Zero-dispersion anomaly series can still flag obvious spikes.

---

## Cross-Batch Risks
- **On-device receipt vision support may be API-limited.** If ML Kit cannot accept image parts, close the false-advertising bug by capability-gating and route metadata correction rather than leaving a fake vision path in place.
- **Challenge persistence is the only likely schema-changing lane.** Keep it isolated to the final batch so earlier HIGH no-schema fixes can land and validate independently.
- **Currency centralization can create broad UI churn.** Start with a shared formatter and high-traffic surfaces first; use grep only for deterministic replacement, not opportunistic redesign.
- **Deep-link fixes can drift into navigation refactors.** Keep the solution inside supported manifest hosts and typed destination payload plumbing.
- **Warranty lifecycle fixes cross AI, repository, DAO, and worker layers.** Do not mark the batch complete until expiry reconciliation and return-policy-only persistence are both proven.

## Final Completion Criteria
- [ ] All 32 remaining reviewer issues are assigned to one of the 5 execution batches above.
- [ ] Batches 1-4 can be executed without schema changes; Batch 5 is isolated as the only migration-risk lane.
- [ ] Each batch has concrete target files, validation commands, and a stop/unknown boundary.
- [ ] High-risk unknowns (on-device image support, anomaly deep-link destination, challenge schema path) are explicitly called out before coding starts.
- [ ] The plan is saved at `docs/plans/PLAN-REMAINING-PHASE2-FIXES.md`.
