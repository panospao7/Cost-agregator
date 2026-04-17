# Phase 2 B.7-12 Final Review

Reviewed against:
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- `docs/reviews/REVIEW-B7-export-backup-pipeline.md`
- `docs/reviews/REVIEW-B8.md`
- `docs/reviews/REVIEW-B9-ui-compose-pipeline.md`
- `docs/reviews/REVIEW-B10-Batch9.md`
- `docs/reviews/REVIEW-B11.md`
- `docs/reviews/REVIEW-B12.md`
- `docs/reviews/PHASE2-REVIEW-B7-12.md`
- current source/test state
- commits `0f660aa`, `cfd2696`, `aeeb2d7`, `7b75348`, `741bf09`, `25f9f0e`, `339bc4b`

## B.7 Export/Backup Pipeline
Status: ✅ RESOLVED

- [HIGH] `AccountingExportRepository.exportExpenses()` inherited the 2000-row cap - RESOLVED - Evidence: `0f660aa`; current `AccountingExportRepository.kt` uses `DeterministicExpenseExportPager.fetchAllBetween(...)`; `PHASE2-REVIEW-B7-12.md` line 12.
- [HIGH] Accountant report summed raw amounts across currencies and hardcoded `€` - RESOLVED - Evidence: `0f660aa`; `PHASE2-REVIEW-B7-12.md` line 13.
- [HIGH] `ExportTransaction` omitted `currency` - RESOLVED - Evidence: `0f660aa`; `PHASE2-REVIEW-B7-12.md` line 14.
- [HIGH] `ExportTransaction` omitted `transactionType` - RESOLVED - Evidence: `0f660aa`; `PHASE2-REVIEW-B7-12.md` line 15.
- [HIGH] `ACCOUNTANT_REPORT_PDF` emitted plain text instead of PDF - RESOLVED - Evidence: `0f660aa`; current `AccountingExportRepository.kt` writes `accountantReportPdfExporter` bytes to a `.pdf`; `PHASE2-REVIEW-B7-12.md` line 16.
- [HIGH] QuickBooks IIF used the category account on both `TRNS` and `SPL` rows - RESOLVED - Evidence: `0f660aa`; `PHASE2-REVIEW-B7-12.md` line 17.
- [HIGH] Repository/UI export paths diverged and one still truncated / handled empty datasets differently - RESOLVED - Evidence: `0f660aa`; current `AccountingExportRepository.kt` and `ExportOptionsViewModel.kt` both allow empty accounting datasets and share the same empty-dataset rule; `PHASE2-REVIEW-B7-12.md` line 18. `REVIEW-B7-export-backup-pipeline.md` is stale relative to the current tree.

## B.8 Savings/Investment Pipeline
Status: ✅ RESOLVED

- [HIGH] `PERCENTAGE_OF_INCOME` accepted negative/NaN/infinite percentages - RESOLVED - Evidence: `cfd2696`; current `AutomatedSavingsRuleEngine.kt` rejects non-finite / negative percentages; `PHASE2-REVIEW-B7-12.md` line 23.
- [HIGH] `WEEKLY_NO_SPEND` could mint repeated rewards in the same week - RESOLVED - Evidence: `cfd2696`; current `AutomatedSavingsRuleEngine.kt` uses `AutomatedSavingsRuleStateRepository.reserveWeeklyNoSpendRewardWithinMonthlyCap(...)`; `PHASE2-REVIEW-B7-12.md` line 24.
- [HIGH] `WEEKLY_NO_SPEND` used rolling `now - 7 days` instead of calendar week boundaries - RESOLVED - Evidence: `cfd2696`; current `AutomatedSavingsRuleEngine.kt` uses `TimePeriodUtils.getWeekRange(now)`; `PHASE2-REVIEW-B7-12.md` line 25.
- [HIGH] Monthly-cap enforcement lived only in in-memory state - RESOLVED - Evidence: `cfd2696`; `AutomatedSavingsRuleStateRepository` persists cap consumption; `PHASE2-REVIEW-B7-12.md` line 26.
- [HIGH] `SmartSavingsEngine.calculateBudgetSurplus()` double-counted overall + category budgets - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 27.
- [HIGH] `calculateSafeToSaveAmount()` returned a portfolio-wide amount per goal - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 28.
- [HIGH] `SavingsGamificationEngine` fabricated streaks/achievements from placeholders - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 29.
- [HIGH] Investment gain/loss ignored `purchaseFees` - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 30.
- [HIGH] `getInvestmentPerformance()` used the oldest recent snapshot - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 31.
- [HIGH] `getPortfolioValueHistory()` double-counted same-day snapshots - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 32.
- [HIGH] `TaxEstimator` applied a single tax bracket to all income - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 33.
- [HIGH] `estimateTaxes()` collapsed arbitrary periods to one month of income - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 34.
- [HIGH] `getTaxYearSummary()` hardcoded annual income to `30000.0` - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 35.
- [HIGH] Deductible tax totals used raw `amount` - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 36.
- [HIGH] VAT was derived from all purchases and inherited the capped read - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 37.
- [HIGH] `FinancialHealthCalculator` included non-purchase rows in spending inputs - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 38.
- [HIGH] `FinancialHealthCalculator` mixed budget periods and double-counted targets - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 39.
- [HIGH] `FinancialHealthScoreV2` always used current-period budget statuses instead of the requested period - RESOLVED - Evidence: `cfd2696`; `PHASE2-REVIEW-B7-12.md` line 40.

## B.9 UI/Compose Pipeline
Status: ❌ NOT RESOLVED

- [CRITICAL] `ReviewViewModel.approveReviewWithEdits()` continued bulk mutations after `Duplicate`/`Error` - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 45.
- [CRITICAL] `LifestyleInflationScreen` used `Modifier.weight(0f)` - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 46.
- [HIGH] `ALL` tab pagination never recorded end-of-results - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 47.
- [HIGH] `ChangeTypeDialog` could not save transfer metadata without changing type - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 48.
- [HIGH] Date chips were not initialized from `currentFilter` - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 49.
- [HIGH] Date headers summed unsigned `effectiveAmount` and colored expense-heavy days green - RESOLVED - Evidence: `339bc4b`; current `TransactionsScreen.kt` uses `items.sumOf { it.expense.signedEffectiveAmount() }` and negative totals map to `SemanticColors.DangerRed`; covered by `TransactionsScreenTest.kt`; this closes the line-50 OPEN item from `PHASE2-REVIEW-B7-12.md`.
- [HIGH] Category/merchant/type/ownership edits did not refresh `_pagedExpenses` - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 51.
- [HIGH] `HomeViewModel.reloadDashboard()` could not recover from `Error` - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 52.
- [HIGH] Ownership filtering used state different from what the UI displayed - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 53.
- [HIGH] Manual expense creation and recurring-rule creation were non-atomic - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 54.
- [HIGH] Year-over-year analytics only had selected-period purchases - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 55.
- [HIGH] Budget forecast retry could not recover after first-load failure - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 56.
- [HIGH] `SharedExpenseGroupsViewModel.loadGroups()` wiped selected state/dialog flags - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 57.
- [HIGH] `isNotMine` and `isSharedExpense` could be set together - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 58.
- [HIGH] External `dateRange` filters were clipped by the default MONTH tab window - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 59.
- [HIGH] `VisualSplitEditorScreen` apply action was a no-op - RESOLVED - Evidence: `REVIEW-B9-ui-compose-pipeline.md` PASS; `PHASE2-REVIEW-B7-12.md` line 60.
- [HIGH] Spending challenges end-to-end feature was incomplete - OPEN - Evidence: `339bc4b` added persistence/domain wiring (`SpendingChallengeEntity`, `SpendingChallengeDao`, `SpendingChallengeRepository`, `SpendingChallengeManager`, `SpendingChallengesViewModel`), but `MainActivity.kt` still wires `onCreateChallenge` to a "Coming soon" placeholder (`navigation.navigateBack()`), so the feature is still not end-to-end.
- [HIGH] `SavingsGoalsViewModel` contributions used read-modify-write snapshots - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 62.
- [HIGH] Assistant clarification replies dropped `conversationHistory` - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 63.
- [HIGH] `AiSettingsViewModel.testConnection()` persisted the API key before connectivity succeeded - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 64.
- [HIGH] Visual split formatting ignored `currencyCode` - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 65.
- [HIGH] Visual split assigned amounts by `participantName` - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 66.
- [HIGH] `LifestyleInflationViewModel.analyze()` allowed stale in-flight jobs to win - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 67.
- [HIGH] `CarbonFootprintViewModel.loadReport()` had the same stale-job problem - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 68.
- [HIGH] `SpendingChallengesViewModel.activeChallenges` was never populated - RESOLVED - Evidence: `339bc4b`; current `SpendingChallengesViewModel.kt` loads `challengeManager.getActiveChallengesSnapshot()` into `_activeChallenges`; this closes the line-69 OPEN item from `PHASE2-REVIEW-B7-12.md`.
- [HIGH] `AddGroupExpenseUseCase` accepted invalid / blank / non-finite input - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 70.
- [HIGH] Transfer/deposit review approval lost direction/account metadata - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 71.
- [HIGH] Review approval lost optional metadata end-to-end - RESOLVED - Evidence: `aeeb2d7`; `PHASE2-REVIEW-B7-12.md` line 72.
- [HIGH] Currency presentation was not centralized and many UI surfaces hardcoded `€` - OPEN - Evidence: current tree still contains hardcoded `€` usage in many UI files (for example `ui/components/RetroTopCategoriesCard.kt`, `ui/components/ForecastTimeline.kt`, `ui/screens/cashflow/CashFlowCalendarScreen.kt`, `ui/screens/budget/BudgetScreen.kt`, `ui/components/TotalsDashboardCard.kt`), so the line-73 OPEN item from `PHASE2-REVIEW-B7-12.md` remains open.

## B.10 Categorization/Intelligence Pipeline
Status: ✅ RESOLVED

- [HIGH] `SpendingChallengeManager.checkNoSpendStreak()` performed one DB read per day - RESOLVED - Evidence: `339bc4b`; current `SpendingChallengeManager.kt` uses `expenseDao.getSpendingDailyTotalsBetween(...)` plus one oldest-date lookup; `SpendingChallengeManagerTest.kt` verifies no day-by-day `getExpensesBetween(...)` reads; this closes the line-78 OPEN item from `PHASE2-REVIEW-B7-12.md`.
- [HIGH] Challenge spend calculations used `expense.amount` instead of `effectiveAmount` - RESOLVED - Evidence: `339bc4b`; current `SpendingChallengeManager.kt` delegates to `ExpenseDao.getTotalSpentBetween(...)` / `getCategorySpentInPeriod(...)`, both SUM `EFFECTIVE_AMOUNT_SQL`; `PHASE2-REVIEW-B7-12.md` line 79.
- [HIGH] Budget-style challenges treated under-budget state as completion - RESOLVED - Evidence: `339bc4b`; current `getChallengeProgress()` only completes budget-style challenges on expiry or failure; `SpendingChallengeManagerTest.kt` covers the regression; this closes the line-80 OPEN item from `PHASE2-REVIEW-B7-12.md`.
- [HIGH] `CategoryKeywords` tie behavior depended on declaration order - RESOLVED - Evidence: `339bc4b`; current `CategoryKeywords.kt` normalizes and deterministically sorts entries; `CategoryKeywordsTest.kt` verifies equal-confidence duplicates resolve independent of declaration order; this closes the line-81 OPEN item from `PHASE2-REVIEW-B7-12.md`.
- [HIGH] `ExpenseCategoryClassifier` persistence was deferred / non-durable - RESOLVED - Evidence: `7b75348`; `REVIEW-B10-Batch9.md` lines 7-20 and 81-85.
- [HIGH] `HybridExpenseClassifier` ignored persisted models on cold start - RESOLVED - Evidence: `7b75348`; `REVIEW-B10-Batch9.md` lines 22-35 and 81-85.
- [HIGH] `TransactionClassifier.cleanup()` permanently canceled the singleton scope on app background - RESOLVED - Evidence: `7b75348`; `REVIEW-B10-Batch9.md` lines 37-55 and 81-85.
- [HIGH] `AnomalyDetector` zero-dispersion bailout missed obvious spikes like `[10,10,10,100]` - RESOLVED - Evidence: `339bc4b`; current `AnomalyDetector.kt` uses `detectZeroDispersionOutliers(...)` with `ZERO_DISPERSION_MULTIPLIER`; this closes the line-85 OPEN item from `PHASE2-REVIEW-B7-12.md`.
- [HIGH] `REDUCE_SPENDING` challenges had no stored baseline/reference period - RESOLVED - Evidence: `339bc4b`; current `SpendingChallenge` / repository persist `baselineAmount`, `baselineStartDate`, and `baselineEndDate`; `SpendingChallengeManagerTest.kt` covers baseline persistence and progress; this closes the line-86 OPEN item from `PHASE2-REVIEW-B7-12.md`.
- [HIGH] Challenge creation was in-memory only with no repository-backed persistence - RESOLVED - Evidence: `339bc4b`; current `createChallenge()` calls `spendingChallengeRepository.saveChallenge(...)`, and active challenges are loaded from the DAO-backed repository; this closes the line-87 OPEN item from `PHASE2-REVIEW-B7-12.md`.

## B.11 Email/Parsing Pipeline
Status: ✅ RESOLVED

- [HIGH] Email ingestion deduped by fingerprint before `messageId` and used destructive overwrite semantics - RESOLVED - Evidence: `741bf09` plus B.4 email-source hardening; `PHASE2-REVIEW-B7-12.md` line 92.
- [HIGH] `createExpenseFromReceipt()` swallowed failures while email ingestion still returned success - RESOLVED - Evidence: `741bf09`; `PHASE2-REVIEW-B7-12.md` line 93.
- [HIGH] `cleanHtml()` destroyed semantic line breaks/entities - RESOLVED - Evidence: `741bf09`; `PHASE2-REVIEW-B7-12.md` line 94.
- [HIGH] Amazon date extraction always read a missing capture group - RESOLVED - Evidence: `741bf09`; `PHASE2-REVIEW-B7-12.md` line 95.
- [HIGH] Apple date extraction had the same capture-group bug - RESOLVED - Evidence: `741bf09`; `PHASE2-REVIEW-B7-12.md` line 96.
- [HIGH] Uber timestamp parsing read the wrong subgroup - RESOLVED - Evidence: `741bf09`; `PHASE2-REVIEW-B7-12.md` line 97.
- [HIGH] Provider parsing assumed English month names / dot-decimal amounts - RESOLVED - Evidence: `741bf09`; `PHASE2-REVIEW-B7-12.md` line 98.
- [HIGH] `GenericTransactionParser` mapped `transfer received` to `DEPOSIT` instead of `TRANSFER` - RESOLVED - Evidence: `741bf09`; `PHASE2-REVIEW-B7-12.md` line 99.
- [HIGH] `GoogleWalletParser` lacked a correct transfer path / could misclassify P2P transactions - RESOLVED - Evidence: `741bf09` added transfer handling, and `339bc4b` tightened `hasExplicitP2pCue()` / `hasOutgoingPeerTransferMarker()` so plain `paid ... to <person-like merchant>` is no longer enough; current `GoogleWalletParser.kt` requires a real P2P/payment-app cue for outgoing transfers; `GoogleWalletParserTest.kt` covers purchase wording vs friend wording. This closes the line-100 OPEN item from `PHASE2-REVIEW-B7-12.md`. `REVIEW-B11.md` is stale relative to the current tree.
- [HIGH] Revolut statement parsing emitted only `DEPOSIT` or `PURCHASE` - RESOLVED - Evidence: `b655d94`; `PHASE2-REVIEW-B7-12.md` line 101.
- [HIGH] `OcrLanguageProcessor.normalizeForLanguage()` destroyed non-Latin scripts - RESOLVED - Evidence: `ffe1199`; `PHASE2-REVIEW-B7-12.md` line 102.
- [HIGH] OCR amount extraction mishandled locale-specific separators - RESOLVED - Evidence: `b655d94`; `PHASE2-REVIEW-B7-12.md` line 103.
- [HIGH] `AndroidSpeechInputGateway` lacked permission/startup/error guarding - RESOLVED - Evidence: `741bf09`; `PHASE2-REVIEW-B7-12.md` line 104.
- [HIGH] `BillReminderManager` mishandled `SEMI_ANNUALLY` - RESOLVED - Evidence: `25f9f0e`; `PHASE2-REVIEW-B7-12.md` line 105.

## B.12 Groups/Shared Expenses Pipeline
Status: ✅ RESOLVED

- [HIGH] `SharedExpenseBudgetOffsetEngine` swallowed calculation failures into zeroed breakdowns - RESOLVED - Evidence: `25f9f0e`; `REVIEW-B12.md` PASS; `PHASE2-REVIEW-B7-12.md` line 110.
- [HIGH] Historical equal-split recomputation used the current member list - RESOLVED - Evidence: `25f9f0e`; `REVIEW-B12.md` line 11; `PHASE2-REVIEW-B7-12.md` line 111.
- [HIGH] Budget-offset share math diverged from `SplitCalculator` - RESOLVED - Evidence: `25f9f0e`; `REVIEW-B12.md` line 11; `PHASE2-REVIEW-B7-12.md` line 112.
- [HIGH] Custom/malformed split handling diverged between settlement and budget-offset code - RESOLVED - Evidence: `25f9f0e`; `REVIEW-B12.md` line 11; `PHASE2-REVIEW-B7-12.md` line 113.
- [HIGH] Recurrence semantics were inconsistent across manager/repository/reminders - RESOLVED - Evidence: `25f9f0e`; `REVIEW-B12.md` line 11; `PHASE2-REVIEW-B7-12.md` line 114.
- [HIGH] `SplitCalculator` used `Int` cents and overflowed at large amounts - RESOLVED - Evidence: `25f9f0e`; `REVIEW-B12.md` line 11; `PHASE2-REVIEW-B7-12.md` line 115.
- [HIGH] Linked/system group expenses could be double-counted in budget pipelines - RESOLVED - Evidence: `25f9f0e`; `REVIEW-B12.md` line 11; `PHASE2-REVIEW-B7-12.md` line 116.

## Summary
Total: 83/85 CRITICAL/HIGH issues RESOLVED

Pipelines fully closed:
- B.7 Export/Backup
- B.8 Savings/Investment
- B.10 Categorization/Intelligence
- B.11 Email/Parsing
- B.12 Groups/Shared Expenses

Pipelines still open:
- B.9 UI/Compose

Open items remaining after re-checking `339bc4b`:
- B.9 spending challenges are still not fully end-to-end because challenge creation is still stubbed in `MainActivity.kt`.
- B.9 currency presentation is still not centralized; many UI surfaces still hardcode `€`.

Re-verification result for `docs/reviews/PHASE2-REVIEW-B7-12.md` OPEN items:
- B.9 day headers - RESOLVED by `339bc4b`
- B.9 challenges - STILL OPEN (partially improved by `339bc4b`, but create flow remains stubbed)
- B.9 currency - STILL OPEN
- B.10 streak query - RESOLVED by `339bc4b`
- B.10 challenge logic - RESOLVED by `339bc4b`
- B.10 `CategoryKeywords` tie behavior - RESOLVED by `339bc4b`
- B.10 `AnomalyDetector` zero-dispersion - RESOLVED by `339bc4b`
- B.10 `REDUCE_SPENDING` baseline - RESOLVED by `339bc4b`
- B.10 challenge persistence - RESOLVED by `339bc4b`
- B.11 `GoogleWalletParser` - RESOLVED by `339bc4b`
