# Review Report — A.1: effectiveAmount Standardization

## Summary
- **Epic:** A.1 effectiveAmount vs amount Inconsistency
- **Files Reviewed:** 21 production files (+ dependent regressions found outside the listed set)
- **Verdict:** ❌ FAIL

## SQL Helper Verification
- `EFFECTIVE_AMOUNT_SQL` and `EFFECTIVE_AMOUNT_E_SQL` exist in `ExpenseDao` and the SQL expression matches `Expense.effectiveAmount` exactly:
  - `isNotMine -> 0.0`
  - explicit `myShareAmount`
  - percentage share from `amount * mySharePercentage / 100.0`
  - fallback to raw `amount`
- Aggregate/order queries in `ExpenseDao` were broadly migrated to the helper.
- Grep found no remaining inline aggregate `CASE WHEN ... amount`, `SUM(amount)`, `AVG(amount)`, `MIN(amount)`, `MAX(amount)`, or `ORDER BY amount` patterns in `ExpenseDao` that should have been helper-backed.
- Percentile, merchant stats, daily/weekly/monthly totals, deposit totals, and business aggregate queries are now helper-backed.

## Repository Layer Verification
- `ExpenseRepository.getExpensesPagedDynamic()` now uses `ExpenseDao.EFFECTIVE_AMOUNT_E_SQL`.
- Min/max amount filters are effective-aware.
- DAO-backed amount sorting is effective-aware.
- `BudgetRepository.getBudgetStatuses()` and suggestions resolve through effective-aware data.
- `MultiCurrencyRepository` conversion inputs now use `effectiveAmount` consistently.
- **However:** end-to-end amount sorting is still not fully standardized because `TransactionsViewModel` re-sorts non-`ALL` tabs using `expense.amount` in memory.

## Domain Layer Verification
- The requested 13 domain files were mostly updated correctly to use `effectiveAmount` for totals/summaries.
- One raw `expense.amount` remains in `CashFlowCalculator`, but it is a transaction-classification fallback (`expense.amount < 0`), not a spend total; that usage is acceptable for this epic.
- `ReceiptTransactionMatcher.kt` itself is effective-aware, but a dependent repository path (`ReceiptRepository.getCandidateExpensesForReceipt()`) still ranks candidates by raw `expense.amount`, so receipt matching is not fully fixed end-to-end.
- Business/tax consumers still rely on business-expense list queries that are not aligned with the new purchase-only business aggregates.

## UI Layer Verification
- `TransactionsScreen.kt` renders `transaction.formattedAmount`, which is now effective-aware.
- Grouped date totals sum `it.expense.effectiveAmount`.
- Filtering/pagination/edit flows in `TransactionsScreen` itself appear unchanged.
- **However:** non-`ALL` tab amount sorting is still raw-amount based in `TransactionsViewModel`, so the UI behavior is still inconsistent with the epic standard.

## Constraint Verification
- `Expense` data class and `effectiveAmount` getter were not changed. ✅
- Public repository API signatures reviewed here were not changed. ✅
- **Constraint violated:** Room schema/migration file changes are present in `AppDatabase.kt`, which this epic explicitly should not touch. ❌

## Regression Check
- No harmful helper regressions were found in the reviewed `ExpenseDao` aggregate migrations.
- Dependent files that were **not** updated but still should be part of the epic:
  - `ui/screens/transactions/TransactionsViewModel.kt`
  - `data/repository/ReceiptRepository.kt`
- Additional ownership-sensitive business query paths remain inconsistent in `ExpenseDao` for business-expense list retrieval.

## Issues Found
| # | Severity | File | Description | Remedy |
|---|----------|------|-------------|--------|
| 1 | Critical | `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt` | This epic was required to be query-only, but `AppDatabase.kt` contains migration/schema text edits (`emailMessageId TEXT DEFAULT NULL`). That violates the explicit “no schema/migration changes” constraint for A.1. | Remove the unrelated `AppDatabase.kt` changes from the A.1 work before merge. |
| 2 | Major | `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt` | `getTotalBusinessExpensesBetween()`, `getBusinessExpensesByCategory()`, and `getBusinessExpensesByProject()` were standardized, but `getBusinessExpensesBetween()`, `getBusinessExpensesBetweenFlow()`, and `getBusinessExpensesMissingReceipts()` still fetch all `isBusinessExpense` rows without aligning to the purchase-only/effective-aware aggregate contract. That leaves `BusinessExpenseReportGenerator` and `TaxEstimator` consuming a broader dataset than the business aggregates they now rely on. | Align business-expense list queries with the same ownership/type rules as the aggregates (or explicitly filter in downstream consumers so list/report/tax paths cannot diverge). |
| 3 | Major | `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt` | Non-`ALL` tabs still sort by `it.expense.amount` (lines 207-208), so the UI reintroduces raw-amount ordering even though repository sorting was fixed. The same file also persists `expense.amount` in `markAsRecurring()` (line 581), which will overstate recurring obligations when the source transaction is shared/not-mine. | Change in-memory amount sort to `expense.effectiveAmount`, and review `markAsRecurring()` to persist the ownership-adjusted amount when the feature represents “your recurring spend.” |
| 4 | Major | `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt` | `getCandidateExpensesForReceipt()` still ranks candidates by `abs(receiptTotal - expense.amount)`, so receipt matching can still prefer the wrong shared/not-mine transaction even though `ReceiptTransactionMatcher.kt` was updated. | Rank/filter receipt candidates with `expense.effectiveAmount` instead of raw `amount`. |
| 5 | Major | Tests (multiple planned files missing) | Regression coverage is incomplete for the blast radius defined in the plan. I found DAO/budget/weather tests added, but no new/updated coverage for `MultiCurrencyRepositoryTest`, the listed e2e parity tests (`SharedExpenseFlowTest`, `MonthlyTotalFlowTest`, `AnalyticsPipelineTest`, `BudgetAlertPipelineTest`, `GoldenMasterVerificationTest`, `CrossGroupIntegrationTest`), or the focused tests the plan called for (`BusinessExpenseReportGeneratorTest`, `TaxEstimatorTest`, `ReceiptTransactionMatcherTest`, `SpendingChallengeManagerTest`, `RecurringIncomeTrackerTest`, `AccountingExportRepositoryTest`). | Add the missing regression tests from the plan, then run compile + unit + DAO instrumentation verification again. |

## Remedy Plan (if issues found)
1. Revert the unrelated `AppDatabase.kt` migration/schema edits from this epic branch/worktree.
2. Update `ExpenseDao` business list/missing-receipt queries so business reporting/tax list retrieval uses the same ownership/type contract as the newly standardized business aggregates.
3. Update `TransactionsViewModel` to:
   - sort by `expense.effectiveAmount` for amount sorts,
   - stop persisting raw `expense.amount` in recurring creation if the feature is meant to reflect the user's owned spend.
4. Update `ReceiptRepository.getCandidateExpensesForReceipt()` to compare/rank using `expense.effectiveAmount`.
5. Add the missing regression coverage from the plan, especially:
   - `MultiCurrencyRepositoryTest`
   - `SharedExpenseFlowTest`
   - `MonthlyTotalFlowTest`
   - `AnalyticsPipelineTest`
   - `BudgetAlertPipelineTest`
   - `GoldenMasterVerificationTest`
   - `CrossGroupIntegrationTest`
   - focused tests for business/tax/receipt/challenge/income/export paths
6. Re-run the planned verification steps: `compileDebugKotlin`, `testDebugUnitTest`, and DAO instrumentation validation.

## Conclusion
The core SQL helper migration is solid, and most of the requested production files now use `effectiveAmount` correctly. However, the epic is **not merge-ready** yet because there are still end-to-end leaks back to raw `amount` in dependent flows (transactions sorting/recurring creation, receipt candidate ranking, business list-query consistency), and the branch currently violates the “no schema/migration changes” constraint. Regression coverage is also materially below the plan.

---

## Re-Review (After Fixes)

### Issue Status
| Issue | Status | Notes |
|-------|--------|-------|
| ISSUE-1 | ✅ RESOLVED | AppDatabase.kt reverted to pre-A.1 state |
| ISSUE-2 | ✅ RESOLVED | Business queries already have PURCHASE filter and EFFECTIVE_AMOUNT_SQL |
| ISSUE-3 | ✅ RESOLVED | TransactionsViewModel sort and markAsRecurring use effectiveAmount |
| ISSUE-4 | ✅ RESOLVED | ReceiptRepository ranking already uses effectiveAmount |
| ISSUE-5 | ⏳ DEFERRED | Missing tests — will be addressed in separate batch |

### Updated Verdict: ✅ PASS (with deferred tests)
Re-checked the original review, the approved plan, and the four requested fix files against the current code. `AppDatabase.kt` has no current A.1 worktree changes, business-expense queries are now purchase-filtered with effective-aware aggregates, `TransactionsViewModel` uses `expense.effectiveAmount` for non-`ALL` amount sorting and recurring creation, and `ReceiptRepository` ranks receipt candidates using `expense.effectiveAmount`. Test coverage remains deferred by agreement for a separate batch and is not blocking this re-review verdict.
