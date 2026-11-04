# fix-1.0.4-neutralisation (batch B2) - handoff

## Origin
Owner: continue after batch B1 (`feature/fix-1.0.4-money-display` @ `03d69151`, 13 HIGH closed) with the next HIGH cluster in a NEW
worktree, same rules.

## Lane
- Worktree: `build/fix3-wt` (inside the gitignored `build/`; harness denies access outside the workspace - same deviation as B0/B1).
- Branch: `feature/fix-1.0.4-neutralisation`
- Base: `694bdf15` = current `origin/feature/ai-evolution` tip.
- `local.properties` copied. Worktree clean apart from these two docs.

## Scope: batch B2 = spending-neutralisation propagation (5 findings)
N-02-1/E-01-1, N-02-2/E-01-2, N-02-3/E-01-3/N-08-8, S19-1, S26-1.

## CRITICAL pre-condition
The guard strengthening from batch B1 is NOT on this branch (verified: `verify_spending_predicate_usage.py:505` still scopes to
aggregates only). B2 must not rely on that guard; it fixes the queries directly.

## Re-anchor result
All 5 findings STILL PRESENT at `694bdf15`. None fixed by the ~30 newer commits. Anchors in the plan.

## Root cause is THREE separate defects, not one
1. `spendingExclusionReason` is dropped at 6 snapshot-mapping sites AND on the dashboard path (`DashboardExpense` ->
   `toExpenseEntity()`), so the engines never see it.
2. The normalisers convert BEFORE checking neutralisation, so a neutralised foreign row with no rate poisons `isPartial`.
3. `ComparableHomeValue` + its SQL twin never check the reason at all (independent of the snapshot).
Fixing only the snapshot would leave #2 and #3 live.

## Slice statuses
| Slice | Status |
|---|---|
| S0 Re-anchor + baseline | NOT STARTED |
| S1 Carry reason into snapshots + dashboard rows | NOT STARTED |
| S2 Fail-closed before conversion | NOT STARTED |
| S3 Comparable value Kotlin/SQL parity | NOT STARTED |
| S4 Row-level query filters (map + NL search) | NOT STARTED |
| Z Candidate gate | NOT STARTED |

## Rules carried over
- Planner-first; no slice begins before the plan exists.
- One writer per lane; second slot for the independent test-author.
- Validation ONLY via `validation-runner`, serially.
- `reviewer-strict` at the candidate gate only.
- No guard weakening, no baseline growth, no schema change.
- **B1's lesson: `isPartial` means "not fully trustworthy" and includes the none-converted case. Do NOT set it false for
  all-failed aggregates. Only types with an explicit `isUnavailable` field may split the two.**

## Human decisions still open
1. Gate 1: approve this plan.
2. Merge/push decision at Z.

## Next action
Awaiting Gate 1. On approval: S0 (baseline targeted run + confirm anchors), then S1.

## S0 - COMPLETE (2026-11-02)

### Baseline
- Run `vr-20261102-193855-882cb221`, `targeted-unit-test`, **PASS**. 522 tests, 0 failures, 177s.
- Fingerprint `e0bdd751...1279a` start == end. Revision `694bdf15`.

### Per-filter match counts (which test classes EXIST at this commit)
| Filter | Tests | Classes |
|---|---|---|
| **ExpenseSnapshot** | **0** | **DOES NOT EXIST** |
| **MoneyNormalization** | **0** | **DOES NOT EXIST** |
| AnalyticsCurrencyNormalizer | 7 | 1 |
| ComparableHomeValue | 56 | 3 (BuilderParity 18, SqlParity 9, base 29) |
| AnalyticsInputAssembler | 8 | 1 |
| AdvancedAnalytics | 51 | 7 |
| ComputeMoneyRadar | 23 | 1 |
| ComputeDashboardWidgets | 35 | 5 |
| DashboardContracts | 9 | 1 |
| SpendingMap | 13 | 3 |
| NaturalLanguageSearch | 6 | 2 |
| ExpenseRepository | 104 | 10 |
| AnomalyAlert | 19 | 1 |
| RecurringExpenseEngine | 27 | 3 |
| Architecture | 204 | 12 |

### Consequences for the plan
1. **No `ExpenseSnapshotTest` and no `MoneyNormalizationEngineTest`.** S1 (snapshot field) and S2 (fail-closed ordering) have no
   existing test class to extend. The test-author must create new ones, or assert through an existing consumer
   (`AnalyticsCurrencyNormalizerTest`, `AdvancedAnalyticsEngineMoneyTest`, `ComputeMoneyRadarUseCaseTest`).
2. **`ComparableHomeValueSqlParityTest` (9 tests) already exists** - S3's Kotlin/SQL parity work has a natural home. Good.
3. `SpendingMapViewModel` has 3 test classes but none for marker/insight filtering - S4 needs a new test.
4. `NaturalLanguageSearch` has only 2 classes (default-window boundary + voice input) - S4 needs a new test for TOTAL_AMOUNT.

### Slice status
S0 COMPLETE. S1 next.
## S1 - COMPLETE (2026-11-02) - carry spendingExclusionReason across the boundary

### Validation
Final: `vr-20261102-205431-5a5d8db0`, `targeted-unit-test`, **PASS**. 500 tests, 0 failures, 301s, fingerprint stable.
Two rounds: 1 failure (dashboard path), then PASS.

### What changed (14 production files)
- `ExpenseSnapshot` gained `spendingExclusionReason: String? = null` (matched the entity: `Expense.spendingExclusionReason` is a
  `String?` holding the enum `.name`).
- `DashboardExpense` gained the same field.
- Populated at EIGHT `ExpenseSnapshot(` sites (the review said 6 - there were 8): ExpenseRepository, AnalyticsCurrencyNormalizer
  (:417 fed from :34/:70), NormalizedAnalyticsInput, AnomalyAlertOrchestrator, RecurringExpenseEngine, AnalyticsViewModel:1244,
  ForecastInputAssembler:144. The 8th (AnalyticsViewModel:621) is a synthetic dummy with no source Expense - correctly left null.
- `NormalizedExpense` and `NormalizableAnalyticsExpense` also gained the field so the two `toExpenseSnapshot()` helpers can forward it.
- `DashboardContractsAdapter` populates it; `ComputeDashboardWidgetsUseCase.toExpenseEntity()` restores it.

### The gap the independent test-author caught
The dashboard round-trip STILL lost the reason because `MoneyNormalizationEngine.Expense.toNormalizedExpense` constructed
`NormalizedExpense` without it. Fixed at `MoneyNormalizationEngine.kt:319-320`. This is exactly why the second agent exists - the
writer's own report listed this as "left for S2", and the test proved it was needed NOW.

Note for future work: `MoneyNormalizationEngine` imports `com.yourname.expensetracker.domain.analytics.NormalizedExpense` - there is
no separate NormalizedExpense in `domain/core/money/`. One class, not two.

### Deliberate scope
This slice is PURE PLUMBING. The field is carried but NOT yet used for filtering. A test asserts that carrying it does not change
any aggregate (`displayAmount == 40.0` over `[0.0 neutralised, 40.0 normal]`, `totalTransactionCount == 2`). S2 and S3 will consume
it.

### `isPartial` untouched
Confirmed untouched in this slice - batch B1 proved that changing its semantics breaks `BudgetAutopilotEngine` and
`BudgetRepository`.

### Slice status
S0, S1 COMPLETE. S2 (fail-closed before conversion) next.
## S2 - CODE COMPLETE, VALIDATION PENDING (2026-11-03)

### Status: NOT yet green. Do not treat S2 as done.

### Production changes (2 files)
- MoneyNormalizationEngine.normalizeExpense: short-circuits expense.isSpendingNeutralised to
  NormalizationResult.Included(rateBasis = IDENTITY, rateUsed = 1.0, normalizedAmount = 0.0) BEFORE currencyConverter.convertOutcome.
  Chose isSpendingNeutralised (not countsAsSpending) because countsAsSpending also conflates !isNotMine (ownership); this matches
  ExecuteFinancialQueryUseCase.kt:506.
- AnalyticsCurrencyNormalizer.normalizeInternal: first when-branch now handles expense.spendingExclusionReason != null ->
  ConversionResult(amount = 0.0, rateBasis = IDENTITY, rateUsed = 1.0) before the conversion. Shared by normalizeExpenses and
  normalizeSnapshots, so both paths are covered.

### Tests added (9)
AnalyticsCurrencyNormalizerTest +4 (regression, snapshot path, negative control, isPartial contract), ComputeMoneyRadarUseCaseTest
+2 (radar not unavailable / still unavailable for real failures), AnalyticsInputAssemblerProvenanceTest +2, SmartSavingsEngineTest +1.

### A STALE TEST HELPER masked the field across 19 files (important finding)
AnalyticsTestCompat.kt Expense.toExpenseSnapshot() constructed ExpenseSnapshot WITHOUT forwarding spendingExclusionReason. Because
S1 gave the field a null default, every test using that helper saw a null reason and could not exercise neutralisation at all. 18 more
test-only helpers had the same omission and were also fixed. This is test infrastructure, not production.

### Validation history
| # | Run | Result |
|---|---|---|
| 1 | vr-20261102-201659-9a8048c3 | FAIL 1 - dashboard round-trip lost the reason (S1 gap, fixed) |
| 2 | vr-20261102-221728-1e4eab20 | FAIL 1 - SmartSavingsEngineTest, caused by the stale test helper (fixed) |
| 3+ | - | BLOCKED repeatedly by E_EXTERNAL_GRADLE_ACTIVE |

Confirmed on run 2: the three batch-B1 regression guards PASSED (CurrencyNormalizationBehavioralTest 21/21,
CurrencyNormalizationPost9a6Test 7/7, MultiCurrencyRepositoryHistoricalCategoryMonthlySpendTest 11/11), so isPartial semantics were
NOT broken this time.

### BLOCKED: machine-wide validation queue
Runs 3, 4 and 5 all failed pre-launch with E_EXTERNAL_GRADLE_ACTIVE. External lanes observed: Cost-agregator-qf (:app:check twice),
Cost-agregator-we (:app:testDebugUnitTest), Cost-agregator-sgd (:app:testDebugUnitTest). Each Gradle job holds the machine-wide lock
for 10-30 minutes, so the queue never opened for ~2 hours.

### NEXT ACTION (resume here)
Run S2 validation when the queue is free:
-Profile targeted-unit-test -TestFilter "*AnalyticsCurrencyNormalizer*Test,*AnalyticsInputAssembler*Test,*ComputeMoneyRadar*Test,
*SmartSavingsEngine*Test,*MoneyNormalization*Test,*CurrencyNormalization*Test,*MultiCurrencyRepository*Test,
*ComputeDashboardWidgets*Test,*Dashboard*Test,*RecurringExpenseEngine*Test,*InsightsEngine*Test,*AdvancedAnalytics*Test,
*Architecture*Test" -TimeoutSeconds 7200
Expected 0 failures. If SmartSavingsEngineTest#neutralised foreign row... still fails, re-check AnalyticsTestCompat.kt.

### Slice status
S0, S1 COMPLETE (green). S2 CODE COMPLETE, VALIDATION PENDING. S3, S4 NOT STARTED.
## S2 - COMPLETE (2026-11-03) - GREEN

### Validation
`vr-20261103-080214-354513a3`, `targeted-unit-test`, **PASS**. 403 tests / 29 suites, 0 failures, 184s, fingerprint stable.
(The queue finally opened after 6 blocked attempts over ~3 hours.)

Confirmed clean:
- `SmartSavingsEngineTest#neutralised foreign row with missing exchange rate does not fail complete money input in savings`: PASS
- `AnalyticsCurrencyNormalizerTest` 13/13, `AnalyticsInputAssemblerProvenanceTest` 11/11, `ComputeMoneyRadarUseCaseTest` 25/25
- All 3 batch-B1 regression guards PASS (`CurrencyNormalizationBehavioralTest`, `CurrencyNormalizationPost9a6Test`,
  `MultiCurrencyRepositoryHistoricalCategoryMonthlySpendTest`) - `isPartial` semantics preserved.
- All 12 `*Architecture*Test` suites 198/198.

### Slice status
S0, S1, S2 COMPLETE and GREEN. S3, S4 NOT STARTED.
## S3 - COMPLETE (2026-11-03) - GREEN

### Validation
`vr-20261103-090724-9e8bf8e4`, `targeted-unit-test`, **PASS**. 417 tests, 0 failures, 214s, fingerprint stable.
Round 1 `vr-20261103-085343-e6ea71c9`: 7 failures, ALL stale golden-SQL pins (legitimately changed by the S3 SQL edit).

### What changed
- `ComparableHomeValue.kt`: `isSpendingNeutralised -> Value(0.0)` is now the FIRST branch in both `comparableValue` and
  `snapshotValue`, before `isNotMine` and before the currency/snapshot branches.
- `ExpenseDao.kt`: `EFFECTIVE_AMOUNT_SQL`, `EFFECTIVE_AMOUNT_E_SQL`, `COMPARABLE_HOME_VALUE_E_SQL` each gained
  `WHEN [e.]spendingExclusionReason IS NOT NULL THEN 0.0` as the FIRST `WHEN` in the CASE.
- `ExpenseRepository.kt`: `SortOrder.AMOUNT_DESC` / `AMOUNT_ASC` gained the same first branch in the outer AND inner CASE.
  The writer found that fixing only `EFFECTIVE_AMOUNT_E_SQL` was NOT enough - a neutralised row with `baseAmount > 0` would have
  matched the earlier `WHEN e.baseAmount > 0` branch and leaked its snapshot value. The SortOrder CASE needed its own guard.
- `countExpensesWithoutHomeValue`: no change needed - with the Kotlin and SQL branches above, a neutralised row now evaluates to
  0.0 (not NULL/None), so it is no longer counted as "missing a home value".

### Verified independently before validation
A read-only review confirmed the SQL placeholder contract: `COMPARABLE_HOME_VALUE_E_SQL` still has EXACTLY 2 `?` placeholders, all
`@RawQuery` arg lists still match (`[home, home]` for IS NULL, `[home, home, bound]` for min/max, `repeat(4)` for the order-by), and
the neutralised branch is first in all five CASE expressions. This was the trap: Kotlin and SQL previously agreed with each other and
both disagreed with the model, so a naive "Kotlin == SQL" test would have passed on the buggy code.

### Tests (independent test-author)
`ComparableHomeValueFixture` +3 cases (39 neutralised-with-snapshot, 40 neutralised-no-snapshot, 41 home neutralised);
`ComparableHomeValueSqlParityTest` +4; `ComparableHomeValueTest` +2; `ComparableHomeValueBuilderParityTest` +1 (includes the
ORDER-BY assertion that a neutralised 550.0 row sorts BELOW an ordinary 10.0 row). Every assertion pins Kotlin AND SQL to the MODEL's
`effectiveAmount`, not to each other.

### Slice status
S0, S1, S2, S3 COMPLETE and GREEN. S4 NOT STARTED.
## S4 + PER-SLICE REVIEW/FIX/REVIEW + Z GATE - COMPLETE (2026-11-03) - ALL GREEN

### What the per-slice strict review caught and fixed in B2 (before Z)
- **Round 1 on S1-S3**: found 5 MAJORs - (1) including neutralised rows at 0.0 left N-02-1 broken (count inflation + average
  dilution), so we switched to FILTERING them out before aggregation while still not recording them as conversion failures;
  (2) `ComputeDashboardWidgetsUseCase` raw lists still contained neutralised rows; (3) `ExecuteFinancialQueryUseCase` missed
  `executeList`/`executeCategoryBreakdown`/`executeMerchantBreakdown`/`executeTotal`; (4) `SharedExpenseBudgetOffsetEngine` converted
  neutralised personal expenses; (5) `MultiCurrencyRepository` and `ExpenseDao` (`getCountForPeriod`, inlined CASE in
  `getLocatedMerchantTotalsByCurrency`) missed neutralisation.
- **Round 2 on S1-S2**: caught 2 remaining defects - (1) keeping neutralised rows in `RecentTransactions` leaked them into
  `RetroTopCategoriesCard`'s `LAST HITS` drilldown; (2) `executeList` (no amount filter) still used the SQL count without
  neutralisation filtering. Both fixed -> S1, S2, S3 GREEN.
- **S4 round 1 -> round 2**: S4 fixed 7 map queries + `SpendingMapViewModel`/`Screen` (S19-1) and `getExpensesFilteredKeyset` +
  `NaturalLanguageSearchEngine`/`ViewModel` (S26-1). Review caught 2 missed bounding-box queries (`getExpensesInBoundingBox`,
  `getExpensesInBoundingBoxBatch`) and `NaturalLanguageSearchScreen:444` formatting `amount` with `homeCurrency`. Fixed -> S4 GREEN.

### A REAL REGRESSION caught by the full unit suite at Z (and fixed)
Run `vr-20261103-153338-7a83e83e` (14,084 tests) failed on 5 tests: `SemanticAggregatesTest#AE2` and 4
`ExpenseDaoSpendingPredicate*Test` classes.
Root cause: in S3 we added `WHEN spendingExclusionReason IS NOT NULL THEN 0.0` to `ExpenseDao.EFFECTIVE_AMOUNT_SQL` and
`EFFECTIVE_AMOUNT_E_SQL`. **That broke contract AE2**: `EFFECTIVE_AMOUNT_SQL` is the ownership-only (`isNotMine`) formula shared with
income (`DEPOSIT`) aggregates (`INCOME_EFFECTIVE_BY_CURRENCY`), and income must IGNORE `spendingExclusionReason`. Spending queries use
`SpendingInclusionSql.EFFECTIVE_AMOUNT` or `WHERE SPENDING_PREDICATE`.
REVERTED `EFFECTIVE_AMOUNT_SQL` and `EFFECTIVE_AMOUNT_E_SQL` to their 4-branch `isNotMine`-only form, while KEEPING the first
neutralised branch in `COMPARABLE_HOME_VALUE_E_SQL` and `SortOrder.AMOUNT_*`.

### Exemption-count strengthening in S4
Adding `SPENDING_PREDICATE` to `countLocated` and `countUnlocated` shrank the spending-predicate exemption set from 34 to 32
(`ROW_COUNT` 7 -> 5). Updated the exact-count pins in `scripts/test_verify_spending_predicate_usage.py`.

### Z Candidate Gate Ladder (all GREEN, worktree-stable)
| Step | Profile | Run | Result |
|---|---|---|---|
| 1 | targeted-unit-test (combined B2) | vr-20261103-152541-9e9ddcfc | PASS - 691 tests / 55 classes |
| 2 | unit-tests (FULL, fresh) | vr-20261103-155611-4d1031e5 | PASS - 14,084 tests, 0 failures |
| 3 | static-guards | vr-20261103-171050-482f4100 | PASS - 27/27 |
| 4 | app-check | vr-20261103-175415-57ba399a | PASS - 0 lint errors, all verifiers PASS |
| 5 | architecture-guardian | - | PASS-WITH-FINDINGS (no violations) |
| 6 | privacy-security-guardian | - | PASS (confirmed spendingExclusionReason cannot reach cloud AI) |
| 7 | final reviewer-strict | - | PASS-WITH-NITS, 0 blocking |