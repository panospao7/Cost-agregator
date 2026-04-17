# Phase 2 B.7-12 Pipeline Review

Reviewed against:
- `MASTER-ISSUE-REGISTRY.md`
- pipeline commits `0f660aa`, `cfd2696`, `aeeb2d7`, `7b75348`, `741bf09`, `25f9f0e`
- existing review artifacts in `docs/reviews/`
- current source/test state (`:app:compileDebugKotlin` passes; `:app:compileDebugUnitTestKotlin` still has unrelated/global failures plus B.9-adjacent test-source regressions)

## B.7 Export/Backup Pipeline
Status: ✅ RESOLVED
Issues:
- RESOLVED - export path no longer inherits the old capped read; repository/UI now use `DeterministicExpenseExportPager`.
- RESOLVED - accountant report no longer hardcodes euro totals; PDF groups/reporting are per currency.
- RESOLVED - `ExportTransaction` now carries `currency`.
- RESOLVED - `ExportTransaction` now carries `transactionType`.
- RESOLVED - `ACCOUNTANT_REPORT_PDF` now emits a real `.pdf` artifact.
- RESOLVED - QuickBooks `TRNS`/`SPL` account semantics are separated correctly.
- RESOLVED - repository/UI accounting export behavior is now converged, including empty-dataset handling for Xero/QuickBooks/FreshBooks.

## B.8 Savings/Investment Pipeline
Status: ✅ RESOLVED
Issues:
- RESOLVED - invalid/negative/NaN/infinite percentage rules are rejected.
- RESOLVED - `WEEKLY_NO_SPEND` is now idempotent per calendar week.
- RESOLVED - `WEEKLY_NO_SPEND` uses stable week boundaries instead of rolling `now - 7 days`.
- RESOLVED - monthly-cap enforcement is persisted via repository state instead of in-memory singleton state.
- RESOLVED - `SmartSavingsEngine.calculateBudgetSurplus()` no longer double-counts overall + category budgets.
- RESOLVED - safe-to-save recommendations are portfolio-scoped and allocated across goals instead of duplicated per goal.
- RESOLVED - savings streaks/achievements now use recorded contribution history instead of fabricated placeholders.
- RESOLVED - investment gain/loss now includes `purchaseFees`.
- RESOLVED - `getInvestmentPerformance()` uses the latest recent snapshot instead of the oldest one.
- RESOLVED - `getPortfolioValueHistory()` collapses same-day snapshots before summing.
- RESOLVED - `TaxEstimator` now applies progressive brackets cumulatively.
- RESOLVED - tax income is aligned to the requested period instead of collapsing to one month.
- RESOLVED - `getTaxYearSummary()` now uses real yearly deposit income instead of hardcoded `30000.0`.
- RESOLVED - deductible tax totals use business/effective-amount aggregates.
- RESOLVED - VAT is derived from business deductible spend instead of all purchases.
- RESOLVED - `FinancialHealthCalculator` excludes non-purchase rows from spending inputs.
- RESOLVED - `FinancialHealthCalculator` now normalizes targets across budget windows and avoids overall/category double-counting.
- RESOLVED - `FinancialHealthScoreV2` now requests budget statuses for the evaluated period.

## B.9 UI/Compose Pipeline
Status: ❌ NOT RESOLVED
Issues:
- RESOLVED - review approval now short-circuits after `Duplicate`/`Error`.
- RESOLVED - `LifestyleInflationScreen` no longer uses `Modifier.weight(0f)`.
- RESOLVED - ALL-tab pagination now tracks end-of-results.
- RESOLVED - `ChangeTypeDialog` can save transfer metadata edits without forcing a type change.
- RESOLVED - date chips initialize from the current filter.
- OPEN - date headers still sum unsigned `effectiveAmount` and color positive expense-heavy days green (`TransactionsScreen.kt`).
- RESOLVED - category/merchant/type/ownership edits now refresh paged results.
- RESOLVED - `HomeViewModel.reloadDashboard()` now has a recovery/retry path.
- RESOLVED - ownership filtering now uses the same state that the UI displays.
- RESOLVED - manual expense + recurring-rule creation is now atomic.
- RESOLVED - year-over-year analytics now loads comparison history outside the selected period window.
- RESOLVED - budget forecast retry can recover after first-load failure.
- RESOLVED - `SharedExpenseGroupsViewModel.loadGroups()` preserves selected state/dialog flags.
- RESOLVED - UI edit paths now prevent `isNotMine` + `isSharedExpense` from being set together.
- RESOLVED - external `dateRange` filters are no longer clipped by the default MONTH tab window.
- RESOLVED - visual split apply is now wired end-to-end for expense targets.
- OPEN - spending challenges are still not backed by persisted challenge storage/domain completion; the UI now surfaces an unavailable state, but the original feature-completeness issue remains open.
- RESOLVED - savings-goal contributions now use atomic repository updates.
- RESOLVED - assistant clarification replies preserve conversation history.
- RESOLVED - AI settings test connection no longer persists a failing API key first.
- RESOLVED - visual split formatting now honors `currencyCode`.
- RESOLVED - visual split completion maps by participant index instead of `participantName`.
- RESOLVED - lifestyle inflation analysis now cancels stale in-flight jobs.
- RESOLVED - carbon footprint loading now cancels stale in-flight jobs.
- RESOLVED - `activeChallenges` is now populated from an explicit snapshot/unavailable-state path instead of staying silently empty.
- RESOLVED - `AddGroupExpenseUseCase` now validates invalid/non-finite/blank inputs.
- RESOLVED - transfer/deposit review approval now preserves direction/account metadata.
- RESOLVED - review approval now preserves optional metadata end-to-end.
- OPEN - currency presentation is still not centralized; many UI surfaces still hardcode `€`.

## B.10 Categorization/Intelligence Pipeline
Status: ❌ NOT RESOLVED
Issues:
- OPEN - `SpendingChallengeManager.checkNoSpendStreak()` still walks backward day-by-day with one DB read per day.
- RESOLVED - challenge spend calculations now use `effectiveAmount` instead of raw `amount`.
- OPEN - budget-style challenge progress/completion logic is still wrong (`progress >= 100` marks fresh under-budget challenges completed).
- OPEN - `CategoryKeywords` declaration-order/tie behavior remains unaddressed.
- RESOLVED - `ExpenseCategoryClassifier` persistence is now durable/awaited.
- RESOLVED - `HybridExpenseClassifier` no longer depends on the cold-start `isReady()` gate.
- RESOLVED - `TransactionClassifier.cleanup()` no longer gets called on normal backgrounding; lifecycle handling is now non-destructive.
- OPEN - `AnomalyDetector` still bails out on zero-dispersion series, so spike cases like `[10,10,10,100]` remain missed.
- OPEN - `REDUCE_SPENDING` challenges still have no stored baseline/reference period.
- OPEN - challenge creation is still in-memory only; there is no persisted active-challenge source.

## B.11 Email/Parsing Pipeline
Status: ❌ NOT RESOLVED
Issues:
- RESOLVED - email ingestion now checks nonblank `messageId` first and no longer relies on destructive overwrite behavior.
- RESOLVED - expense-creation failure now aborts success and is wrapped in a transaction boundary.
- RESOLVED - `cleanHtml()` now preserves semantic line breaks and decodes entities.
- RESOLVED - Amazon provider date extraction no longer assumes broken capture groups.
- RESOLVED - Apple provider date extraction no longer assumes broken capture groups.
- RESOLVED - Uber timestamp parsing now reads the actual date subgroup.
- RESOLVED - provider parsing now uses locale-aware amount/date helpers instead of English-only assumptions.
- RESOLVED - `GenericTransactionParser` now maps transfer-received wording to `TRANSFER`.
- OPEN - `GoogleWalletParser` added a transfer path, but the heuristic is still too broad and can relabel ordinary purchases as P2P transfers.
- RESOLVED - Revolut statement transfer/top-up/refund handling was audit-verified as compliant in the B.11 lane.
- RESOLVED - OCR script-preserving normalization was audit-verified as compliant in the B.11 lane.
- RESOLVED - OCR locale-aware amount extraction was audit-verified as compliant in the B.11 lane.
- RESOLVED - speech input now guards permission/startup and surfaces recognizer errors.
- RESOLVED - `BillReminderManager` semi-annual handling was audit-verified as compliant in the B.11 lane.

## B.12 Groups/Shared Expenses Pipeline
Status: ✅ RESOLVED
Issues:
- RESOLVED - budget-offset calculation no longer swallows failures into zeroed breakdowns.
- RESOLVED - member deletion now guards against historical equal-split drift after `joinedAt`.
- RESOLVED - budget-offset share math now delegates to `SplitCalculator`.
- RESOLVED - malformed/custom split handling is aligned to the canonical split pipeline.
- RESOLVED - recurrence semantics are now centralized on `RecurrenceCalculator` across manager/repository/reminders.
- RESOLVED - `SplitCalculator` cent math now uses `Long`, avoiding large-amount overflow.
- RESOLVED - linked/system group expenses are normalized so budget pipelines do not double-count full system amount plus user share.

## Summary
Total resolved: 74/85 issues

Open pipelines:
- B.9 UI/Compose
- B.10 Categorization/Intelligence
- B.11 Email/Parsing

Resolved pipelines:
- B.7 Export/Backup
- B.8 Savings/Investment
- B.12 Groups/Shared Expenses

Additional verification notes:
- `:app:compileDebugKotlin` passes.
- `:app:compileDebugUnitTestKotlin` currently fails because of unrelated legacy test-source errors (`SmartReceiptAssistServiceTest`, `WarrantyExpirationWorkerTest`) and current B.9-adjacent test regressions (`SharedExpenseGroupsViewModelTest`, `VisualSplitViewModelTest`).
- Existing B.7/B.8 review files are stale relative to the current tree: their previously flagged follow-up issues have been fixed in later commits.
