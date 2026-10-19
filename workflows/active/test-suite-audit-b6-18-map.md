# B6-18 partial map + decision (scout 2026-10-19; orchestrator decision under standing approval)

Directory: app/src/test/java/com/yourname/expensetracker/data/repository/. All Budget/Expense classes here are mock-based (no Room), fast; nothing in known-hanging-tests.json.

## Excluded (WAIT for release merge)
- SavingsSweepAcceptanceCoordinatorTest (29 tests): really constructs MonthlySavingsSweepUseCase (sweepProducer :673-733; 9 tests call computeSweepRecommendationResult), which the release changed.
- ReviewQueueRepositoryNestedRollbackTest.
- BudgetRepositoryValidationTest does not exist (validation lives in Historical :219-:292 and Stress :122-:303).

## Decision (55 → ~23 tests; no class deleted, so no rename notes; BudgetRepository.kt:413 KDoc and RP-09 doc cite kept classes)
W1 — BudgetRepositoryHistoricalStatusTest (22→~14) + BudgetRepositoryStressTest (15→~2):
- Historical: one REJECT table (addBudget rows: NaN, +Inf, 0.0, -100, startDate 0, startDate -1000, inverted 0.9/0.5, warning 0.0, warning 1.0, critical==warning 0.75, critical 1.5; updateBudget rows 0.0, -50) — keep coVerify(exactly=0) on insert/insertAndActivateOverall per add row (update rows: the matching update DAO call exactly 0). One ACCEPT table (critical 1.0, valid, 1e9 YEARLY, 0.01) → Success. Merge :391 into :466 adding the warning-contains "latest rate" assertion. Everything else stays. Money: assertMoneyExact for pass-through values (spent 800, limits 850/580, zero fields at :433); computed values assertMoneyEquals; float percent keeps isWithin.
- Stress: delete :256 and :274 (no assertions → vacuous); delete :336 (survivor Truncation :117 wallet row, same pass-through shape); move :381 to W2's category table as the CRITICAL row; validation tests move into the Historical tables. Keep ONE rollover table: row 12-period (anchor Jan 1 2025, now Jan 10 2026, 2000/1800, limit 4400, atLeast(13), zero row reads) + Truncation :231 (anchor Jan 1 2026, now Apr 10 2026, 5000/4800, atLeast(4)) + :425 (anchor Jan 1 2026, now Mar 15 2026, 1000/800, atLeast(3)). Derive exact expected limits for :231/:425 from production BudgetCalculator rollover code; if exactness cannot be derived with certainty keep isAtLeast and say so. Add @get:Rule PinnedUtcTimeZoneRule() (real BudgetCalculator uses ZoneId.systemDefault()). Keep :515 (only observeExpenseMutationClock trigger test).
W2 — BudgetRepositoryTruncationTest (9→~2) + ExpenseRepositoryTruncationTest (9→5):
- Truncation wallet table: 10000/8000 WARNING (replaces :117 and exact duplicate :281), 20000/25000 EXCEEDED (percent >= 1), 10000/9500 CRITICAL, 1000/500 ON_TRACK; per row getTotalSpentBetweenByCurrency exactly once + verifyNoRowLevelReads. Category table: 3000/3500 EXCEEDED, 800/200 remaining 600 (getTotalForPeriod exactly 0), 5000/4500 CRITICAL remaining 500 (from Stress :381). Rows :231/:425 moved to W1's rollover table (W2 deletes them only after W1 has added them).
- ExpenseRepositoryTruncation: delete :87 (750 rows; survivor :101, 2500 > cap); merge each size test with its delegation test (getAllExpenses 2500, getExpensesBetween 3000, getExpensesBetweenFlow 2500); keep :191 and :205. Do not change list sizes.
Table hygiene (main risk): fresh mocks/repository per row (or clearMocks + re-stub) so coVerify counts don't leak; row-named messages; size guards; catch only AssertionError.

## Validation
targeted-unit-test: data.repository.BudgetRepositoryHistoricalStatusTest, BudgetRepositoryStressTest, BudgetRepositoryTruncationTest, ExpenseRepositoryTruncationTest (+ neighbours BudgetRepositoryDiagnosticsTest, BudgetRolloverTest). Review: reviewer-strict (money).
