# Fix plan: fix-1.0.4-neutralisation (batch B2)

Author: orchestrator. Status: DRAFT - awaiting Gate 1 before any `app/` edit.
Lane: `build/fix3-wt`, branch `feature/fix-1.0.4-neutralisation`, base `694bdf15` (current origin/feature/ai-evolution tip).
Evidence base: `build/review-wt/build/full-review-2026-11/` + the B2 re-anchor at this commit.

- Risk tier: **T3** (money correctness across analytics, dashboard, map, NL search, sorting).
- Schema: **NO** (adds a field to a domain model only).
- Validation ONLY by `validation-runner`, serially. Never `./gradlew`.
- Concurrency: 2 agents max, **one writer per lane**; second slot for the independent test-author.

## CRITICAL PRE-CONDITION: the B1 guard is NOT on this branch
Batch 1 (`feature/fix-1.0.4-review-findings`) strengthened `scripts/verify_spending_predicate_usage.py` to cover non-aggregate
SELECTs. That commit is NOT in `694bdf15` - verified: the file still has only the aggregate-scoped rule at `:505`
(`in_scope = _TABLE_RE ... and _AGGREGATE_RE ...`).
=> B2 must NOT assume that guard protects it. B2 will fix the row-level queries directly and add its own verification.

## Re-anchor result at 694bdf15 (all 5 findings re-verified)
| ID | Status | Anchor |
|---|---|---|
| N-02-1 / E-01-1 | STILL PRESENT | `ExpenseDao.kt:256,262,272,278,284,1548-1565`; `ExpenseSnapshot.kt:3-16`; `ExpenseRepository.kt:1244-1259`; `AnalyticsInputAssembler.kt:80-84` |
| N-02-2 / E-01-2 | STILL PRESENT | `AnalyticsCurrencyNormalizer.kt:114-168`; `MoneyNormalizationEngine.kt:106,275-281`; `ComputeMoneyRadarUseCase.kt:308-320` |
| N-02-3 / E-01-3 / N-08-8 | STILL PRESENT | `ComparableHomeValue.kt:32-58`; `ExpenseDao.kt:86-90,114-121`; `ExpenseRepository.kt:62-80` |
| S19-1 | STILL PRESENT | `ExpenseDao.kt:2172-2203,2282`; `SpendingMapViewModel.kt:587-601,657-681` |
| S26-1 | STILL PRESENT | `NaturalLanguageSearchEngine.kt:330-338`; `ExpenseDao.kt:1516-1525`; `NaturalLanguageSearchViewModel.kt:130-139` |

**None were fixed by the ~30 newer commits.**

## ROOT-CAUSE ANALYSIS (this shapes the slices)
`spendingExclusionReason` is dropped in SIX places, not one:
- `ExpenseSnapshot` (`:3-16`) and its 6 mapping sites (`ExpenseRepository:1244-1259`, `AnalyticsCurrencyNormalizer:33-83,410-428`,
  `NormalizedAnalyticsInput:79-102`, `AnomalyAlertOrchestrator:344-369`, `RecurringExpenseEngine:291-316`,
  `AnalyticsViewModel:1243-1268`).
- `DashboardExpense` / `DashboardContractsAdapter:190-205` / `ComputeDashboardWidgetsUseCase.toExpenseEntity():595-616` - the
  dashboard path reconstructs an Expense WITHOUT the reason, so neutralised rows enter `MoneyNormalizationEngine` as amount 0.0.
Fixing `ExpenseSnapshot` alone does NOT fix N-02-3 (ComparableHomeValue works on `Expense`/raw SQL, never the snapshot) and does NOT
fix the `MoneyNormalizationEngine` half of N-02-1/2 (it takes `Expense` directly). So B2 needs THREE independent fixes, not one.

## Slice table

| ID | Title | Tier | Prod files | Depends on | Reviewers | Issues closed |
|---|---|---|---|---|---|---|
| S0 | Re-anchor + baseline targeted | - | 0 | lane created | - | - |
| S1 | Carry the reason into snapshots and dashboard rows | T3 | 4-6 | S0 | AG + test-author | N-02-1, N-02-2 (root cause) |
| S2 | Fail-closed BEFORE conversion in the engines | T3 | 3-4 | S1 | AG + test-author | N-02-2 (symptom) |
| S3 | Comparable value: Kotlin + SQL parity | T3 | 3 | S2 | AG + test-author | N-02-3, N-08-8 |
| S4 | Row-level query filters (map + NL search) | T2 | 4-5 | S3 | AG + test-author | S19-1, S26-1 |
| Z | Candidate gate | - | 0 | S4 | reviewer-strict (fresh) | - |

Order rationale: S1 fixes the DATA LOSS (the reason never reaches the engines), which is the precondition for S2's ordering fix and
S3's parity fix. S4 is last because it is mostly query-level and independent.

## Slices

### S0 - Re-anchor + baseline (no `app/` edits)
- Record `git rev-parse HEAD` == `694bdf15`.
- Queue ONE baseline `targeted-unit-test` over the classes S1-S4 will touch.

### S1 - Carry `spendingExclusionReason` into snapshots and dashboard rows (N-02-1, N-02-2 root cause)
- `A/domain/model/ExpenseSnapshot.kt`: add the field (nullable, default null so every construction site keeps compiling).
- Populate it in ALL mapping sites: `ExpenseRepository.toExpenseSnapshot()`, `AnalyticsCurrencyNormalizer`, `NormalizedAnalyticsInput`,
  `AnomalyAlertOrchestrator`, `RecurringExpenseEngine`, `AnalyticsViewModel` (6 sites).
- `A/domain/model/dashboard/DashboardPrimitives.kt` (`DashboardExpense`): carry the reason.
- `A/data/repository/DashboardContractsAdapter.kt:190-205`: populate it from the entity.
- `A/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.toExpenseEntity():595-616`: restore it instead of dropping it.
- Tests: assert a neutralised row survives the snapshot AND the dashboard mapping with its reason intact.

### S2 - Fail closed BEFORE conversion in the engines (N-02-2 symptom)
- `A/domain/core/money/MoneyNormalizationEngine.kt`: short-circuit `isSpendingNeutralised` to `0.0` IDENTITY BEFORE any conversion,
  so a neutralised foreign row with a missing rate does NOT become `Excluded(MISSING_EXCHANGE_RATE)`.
- `A/domain/analytics/AnalyticsCurrencyNormalizer.kt`: same ordering fix.
- **PRESERVE the `isPartial` contract**: `MoneyAggregate.isPartial` means "not fully trustworthy" and must remain
  `conversionFailures.isNotEmpty()`. Do NOT repeat batch B1's mistake of setting it false for the none-converted case. Only types
  with an explicit `isUnavailable` field may split the two.
- Tests: a neutralised foreign row with no rate must NOT increment excludedCount and must NOT flip isPartial; the period must still
  be `requireCompleteMoneyInput`-clean.

### S3 - Comparable value parity (N-02-3, N-08-8)
- `A/domain/core/money/ComparableHomeValue.kt`: add the neutralised branch FIRST (return `Value(0.0)`), before the isNotMine and
  currency branches.
- `A/data/database/dao/ExpenseDao.kt`: `COMPARABLE_HOME_VALUE_E_SQL` and `EFFECTIVE_AMOUNT_E_SQL` must add the neutralised case
  (`WHEN e.spendingExclusionReason IS NOT NULL THEN 0.0`) so SQL matches the Kotlin policy.
- `A/data/repository/ExpenseRepository.kt:62-80` (`SortOrder.AMOUNT_*`): the fallback to `EFFECTIVE_AMOUNT_E_SQL` must not restore a
  raw amount for a neutralised row.
- Tests: a Kotlin/SQL parity test with a neutralised foreign row that HAS a snapshot (must rank 0.0) and one WITHOUT (must be 0.0,
  not None/unranked).

### S4 - Row-level query filters (S19-1, S26-1)
- `A/data/database/dao/ExpenseDao.kt`: add the filters to the map queries (`:2172-2203`, `:2282`) and to
  `getExpensesFilteredKeyset` (`:1516-1525`). For the map: exclude `isNotMine` and neutralised rows (a pin for a 0.0 row is
  meaningless).
- `A/ui/screens/map/SpendingMapViewModel.kt`: filter the marker/heatmap/insight lists consistently.
- `A/domain/naturallanguage/NaturalLanguageSearchEngine.kt` + `NaturalLanguageSearchViewModel.kt`: align the VM's re-aggregation
  with the engine's `convertAsOf` basis, and skip neutralised rows.
- CAUTION: `getExpensesBetweenUncapped` is ALSO called by `ReceiptTransactionMatcher` and `ExportDataRepository`, which may
  legitimately need installment parent rows. Do NOT change that query's contract blindly - decide per CALLER, and prefer filtering
  at the consumer rather than in a shared query if the contract must stay broad.
- Tests: a neutralised row does not appear as a map pin; NL search TOTAL_AMOUNT excludes neutralised and non-PURCHASE rows.

## Hot files (one writer program-wide)
| File | Slice |
|---|---|
| `ExpenseDao.kt` | **S3 then S4** - same file, strictly sequential |
| `MoneyNormalizationEngine.kt` | S2 only |
| No strings, no guards, no baselines | - |

## Validation plan
- After S1+S2: targeted (analytics/dashboard), then the `domain-*` shard.
- After S3: targeted (comparable/sort/parity).
- After S4: targeted (map/NL search).
- Z: full ladder once + fresh `reviewer-strict`.

## Explicitly OUT of scope
- Adding or changing any guard rule (batch 1 owns the guard strengthening; merging B1 will bring it).
- The domain-model batch (DR-*) and engine batch (BL-04..BL-11).
- `I-01-1` barrier dominance.
- Any refactor, rename, or reformat.

## Stop and ask before
- Changing `getExpensesBetweenUncapped` in a way that affects `ReceiptTransactionMatcher` or `ExportDataRepository`.
- Any Room schema change.
- Touching `TransactionLifecycleCoordinator`.
