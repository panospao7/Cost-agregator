# fix-1.0.4-money-display (batch B1) - handoff

## Origin
Owner decision 2026-11-02: after batch 1 (2 BLOCKERs + 4 CRITICALs) was pushed as
`feature/fix-1.0.4-review-findings` @ `03d519a8`, continue with the HIGH findings in a NEW worktree, same rules.

## Lane
- Worktree: `build/fix2-wt` (inside the main checkout's gitignored `build/`; the harness denies access outside the workspace,
  so a `../Cost-aggregator-<id>` sibling is unreachable to subagent tools - same deviation as batch 1).
- Branch: `feature/fix-1.0.4-money-display`
- Base: `da5310d8` = current `origin/feature/ai-evolution` tip (NOT the older `d9c55d4d`).
- `local.properties` copied. Worktree clean apart from these two docs.

## Scope: batch B1 = money display (10 findings + 2 new)
E-01-4, N-08-1/J-07-1, P-05-4, E-05-1, J-08-1, N-08-5, FL-06/P-05-7, P-12-1, N-08-7, P-06-1
PLUS two NEW instances introduced by the merged `stress-forecast-actions` work:
- B1-11: `FinancialStressForecastCard.kt:278` locale-sensitive `%.0f` percent interpolation.
- B1-12: `FinancialStressForecastEngine.kt:292,809,814-816,872-875` labels a HOME-currency emergency-buffer scalar with
  `displayCurrency` without converting it.

## Re-anchor result
All 10 original findings STILL PRESENT at `da5310d8`. None were fixed by the 26 newer commits. Verified anchors are in the plan.

## Slice statuses
| Slice | Status |
|---|---|
| S0 Re-anchor + baseline | NOT STARTED |
| S1 Locale-safe keys + percent | NOT STARTED |
| S2 Currency-correct formatting | NOT STARTED |
| S3 Honest "unavailable" | NOT STARTED |
| S4 Export sentinel + budget forecast | NOT STARTED |
| Z Candidate gate | NOT STARTED |

## Rules carried over from batch 1
- Planner-first; no slice starts before the plan exists.
- One writer per lane; second concurrency slot for the independent test-author on T2 slices.
- Validation ONLY via `validation-runner`, serially, one queue across all worktrees.
- `reviewer-strict` at the candidate gate only, not per slice.
- No guard weakening, no baseline growth, no schema change.
- Minimal diffs; no refactors or reformatting.

## Human decisions still open
1. Gate 1: approve this plan (or amend scope/order).
2. Merge/push decision at Z.

## Next action
Awaiting Gate 1. On approval: S0 (baseline targeted run + confirm anchors), then S1.

## S0 - COMPLETE (2026-11-02)

### Baseline
- Run `vr-20261102-090255-e34de6a2`, `targeted-unit-test`, **PASS** (exit 0). 401 tests, 0 failures, 136s.
- Fingerprint `a91db62d...92df8` start == end. Revision `da5310d8`.

### Per-filter match counts (which test classes actually EXIST at this commit)
| Filter | Tests | Classes |
|---|---|---|
| TotalsAggregation | 32 | 5 classes |
| RecurringExpenses | 2 | RecurringExpensesViewModelWriteBlockedTest only |
| FinancialWeather | 13 | FinancialWeatherRepositoryTest only |
| WarrantyTracker | 95 | 4 classes |
| **BudgetScreen** | **0** | **DOES NOT EXIST** |
| BudgetForecast | 51 | 3 classes |
| BudgetRecommendation | 5 | 1 class |
| ComputeDashboardWidgets | 35 | 5 classes |
| ExportOptions | 44 | 2 classes |
| ExpenseExportMapper | 2 | 1 class |
| AnalyticsViewModel | 15 | 3 classes |
| FinancialStressForecast | 72 | 2 classes |
| StressRecommendation | 31 | 3 classes |
| CurrencyFormatter | 4 | 1 class |
| **HomeScreen** | **0** | **DOES NOT EXIST** |
| **TotalsDashboard** | **0** | **DOES NOT EXIST** |

### Consequences for the plan (IMPORTANT)
1. **No `BudgetScreenTest` exists.** S2's J-08-1 fix (hardcoded euro in budget strings) has NO existing UI test class. The
   test-author must either create one or assert at a lower level (e.g. a formatter/helper unit test). Do NOT plan tests against a
   class that does not exist.
2. **No `HomeScreenTest` and no `TotalsDashboardTest`.** S3's N-08-5 fix (render "unavailable" instead of 0.0) has no existing
   Compose test class. Same consequence: assert the STATE (PeriodSummary/PeriodTotal carrying an unavailable flag) in a unit test
   rather than the rendering.
3. **`RecurringExpenses` has only a write-blocked test** - no formatting test. S2's N-08-1 fix needs a new test.
4. `FinancialWeather` has only a REPOSITORY test - no card/formatting test. S2's P-05-4 fix needs a new test.

### Slice status
S0 COMPLETE. S1 next.
## S1 - COMPLETE (2026-11-02) - locale-safe keys + percent formatting

### Validation
- Run `vr-20261102-092732-39755da3`, `targeted-unit-test`, **PASS** (exit 0). 504 tests / 31 suites, 0 failures, 431s.
- Fingerprint `0117f071...4d7ae` start == end. Revision `da5310d8`. No locale leak detected.

### E-01-4 - CLOSED
All 5 key sites in `TotalsAggregationEngine.kt` now use `String.format(Locale.ROOT, ...)`:
`:106` periodKey, `:434` currentMonthKey, `:561` week key, `:566` day key, `:612` day key.
Convention matched the existing project precedent (`TimePeriodUtils.kt:840`, `DuplicateDetectionPolicy.kt:95`,
`AiArtifactSourceHash.kt:258,264,271`). Key SHAPE and format patterns unchanged - only the locale.
`java.util.Locale` was already imported at :33.

### B1-11 - CLOSED
`FinancialStressForecastCard.kt:278` now renders via `stringResource(R.string.stress_forecast_risk_percent_format, ...)`.
New key in `values/strings.xml` only: `<string name="stress_forecast_risk_percent_format">%1$s%% risk</string>`.
No el/es translation added (deliberate - separate decision; MissingTranslation is baselined).

### Tests (independent test-author)
- `TotalsAggregationEngineValidationTest`: monthly totals + baseline average use ASCII month keys under ar-EG, with a
  Locale.ROOT control case asserting IDENTICAL totals (proves locale-independence, not just "works in Arabic").
- `TotalsAggregationEngineNormalizedMoneyTest`: normalized daily keys are ASCII under ar-EG.
- `StressRecommendationVisibleCapTest`: asserts the string resource exists, has a %1$s placeholder, and formats.
- Every locale test restores `Locale.getDefault()` in a finally block - mandatory, a leaked locale would poison the JVM.

### Known test gap (recorded, not a defect)
The in-card percent NUMBER formatting lives inside a private @Composable with no extracted helper, so it needs a Compose UI test
to assert directly. The resource contract is tested; the rendering is not. Acceptable for this slice.

### Slice status
S0, S1 COMPLETE. S2 (currency-correct formatting, 4 surfaces) next.
## S2 - COMPLETE (2026-11-02) - currency-correct formatting (4 surfaces)

### Validation
- Run vr-20261102-095431-92d5f27b, targeted-unit-test, PASS (exit 0). 396 tests / 24 suites, 0 failures, 352s.
- Fingerprint 06b2bf0a...daf98 start == end. Revision da5310d8. No compile break from the new abstract property.

### N-08-1 / J-07-1 - CLOSED
RecurringExpensesScreen.kt: :396-398 now formats with expense.currency; :449 and :460 now use
CurrencyFormatter.formatMoney(pattern.averageAmount, pattern.currency) instead of a bare %.2f.

### P-05-4 - CLOSED (domain model change, allowed by the plan)
UpcomingItem gained abstract val currency: String, overridden in all three subclasses from their wrapped model:
Recurring -> pattern.currency, Occurrence -> occurrence.expectedCurrency, Planned -> expense.currency.
Because each wrapper DERIVES the currency, every construction site (FinancialWeatherRepository, HomeInstallmentWeatherSource)
carries the right currency automatically - ZERO home-currency fallbacks needed. FinancialWeatherCard now formats with item.currency.
NO Room change - pure domain-model addition, exactly as the plan permitted.

### E-05-1 - CLOSED
WarrantyTrackerState gained protectedValueCurrency, isProtectedValuePartial, isProtectedValueAvailable.
Screen formats with CurrencyFormatter, renders "Unavailable" (not 0.00) when unavailable, and shows DataQualityWarningChip when
partial.

### J-08-1 - CLOSED
Six budget format strings changed from euro-%.2f to %1$s placeholders, fed by
CurrencyFormatter.formatMoney(amount, status.currency): budget_spent_format, budget_limit_format, budget_over_format,
budget_remaining_format, budget_adjusted_spent_format, budget_pending_reimbursement_format. String NAMES unchanged so any
existing el/es bindings still resolve.

### Tests (independent test-author)
- FinancialWeatherRepositoryTest +2: all three UpcomingItem subclasses report source currency (USD) with a EUR negative control.
- WarrantyTrackerViewModelTest +4: currency/available/partial state, including UNAVAILABLE -> not available.
- BudgetCardDisplayTest +1: the six budget strings contain %1$s and neither euro nor %.2f.
- RecurringExpensesViewModelWriteBlockedTest +1: planned expenses preserve source currency independent of homeCurrency.

### Recorded gap (out of scope, NOT a defect of this slice)
values/strings.xml still contains 36 other euro signs in unrelated strings (e.g. avg_amount_format, amount_label_full,
a11y_category_spending_format). Those are NOT part of J-08-1 and were deliberately left alone - fixing them is a separate sweep
needing its own review. Noted so it is not mistaken for an oversight.

### Slice status
S0, S1, S2 COMPLETE. S3 (honest "unavailable") next.
## S3 - COMPLETE (2026-11-02) - honest "unavailable" state (4 rounds)

### Validation
Final: `vr-20261102-122351-909abe77`, `targeted-unit-test`, **PASS**. 453 tests, 0 failures, 181s, fingerprint stable.
Rounds: 1 compile error, then 5 failures, then 3 failures, then PASS. Every round found something real.

### N-08-5 - CLOSED
`PeriodSummary` and `PeriodTotal` gained `isUnavailable`. Unavailable emits `Double.NaN` + `isUnavailable = true` + `isPartial = false`
(NaN over 0.0 so a missed guard fails loudly rather than showing a plausible zero). Verified EVERY consumer checks the flag first:
HomeScreen (:674-676 guards all three stats), TotalsDashboardCard (:105,:146,:157,:183), PeriodBlock.
Also fixed the deeper cause the test-author found: `MoneyNormalizationEngine.aggregateExpenses` conflated partial with unavailable,
and `CurrencyDataQuality.fromAggregates` never preserved UNAVAILABLE. All period totals (monthly/weekly/daily/range/yearly) now set
the flag consistently.

### FL-06 / P-05-7 - CLOSED
`SafeToSpend` is now computed: `(validBudget - monthSpend).coerceAtLeast(0.0)` using the ALREADY-VALIDATED budget source
(`hasCompleteAmountIn(homeCurrency)`). Stays unavailable when the budget is missing/incomplete or the input is partial.

### N-08-7 - CLOSED (and the same defect on two more paths)
Analytics now attributes exclusions PER CATEGORY via `aggregateSelectedSlice(..., ExclusionAttribution.TEMPORAL)` instead of
`aggregateUnattributedSlice`. A category with one unconvertible row is partial; a fully-unconvertible one is unavailable; the LIST is
never emptied.
Two additional instances of the same defect were found and fixed:
- `TotalsAggregationEngine.getCategoryBreakdown` THREW `CATEGORY_MONEY_UNAVAILABLE` if ANY category was unavailable, emptying the
  whole breakdown. Now marks the category and returns the rest.
- `AdvancedAnalyticsEngine.getCategoryAnalytics` still used `aggregateUnattributedSlice` and threw `MoneyDisplayUnavailableException`,
  dropping all `enhancedCategories`. Now uses `MoneySliceContext.forCategory(categoryId)`.

### Test-harness bug found (worth remembering)
Three of the S3 tests re-stubbed `expenseRepository.getAllExpenses()` / `categoryRepository.allCategories` AFTER the ViewModel had
already captured those flows at construction, so the re-stubs had zero effect and categories resolved to id -1. Fixed by stubbing
first and constructing a fresh ViewModel inside each test. This is a trap for any test of a ViewModel that captures flows at init.

### Stale expectations corrected (not bugs)
- `ComputeDashboardWidgetsSafeToSpendQualityTest` asserted `isUnavailable` for a user WITH a complete budget - that encoded the FL-06
  bug. Now asserts the computed amount.
- It also asserted a missing rate makes SafeToSpend PARTIAL - that encoded the partial/unavailable conflation. Now asserts
  UNAVAILABLE and not partial.

### Slice status
S0-S3 COMPLETE. S4 (export sentinel + budget forecast currency) next.
## S4 - COMPLETE (2026-11-02) - export sentinel + budget forecast + emergency buffer

### Validation
Final: `vr-20261102-141608-4e68fb9c`, `targeted-unit-test`, **PASS**. 440 tests, 0 failures, 177s, fingerprint stable.
Two rounds: 4 failures then PASS. All 4 were legitimate expectation changes or a bug in my own test instruction.

### P-12-1 - CLOSED
Sentinel detection: `exchangeRateUsed <= 0.0 || baseCurrency.isBlank()` (plus `!isFinite()` in the mapper).
CSV now emits BLANK cells; JSON emits literal `null` tokens (not the string "null"). Column count verified unchanged by asserting
header/sentinel/converted rows all split to the same field count.
The accounting exporters (Xero, FreshBooks, QuickBooks IIF) were ALREADY correct and were left untouched.

### P-06-1 - CLOSED
`BudgetForecastingViewModel` now passes `forecast.normalizedBudgetAmount` (home currency) instead of raw `budget.amount`.
`BudgetRecommendationEngine.getBudgetHealthSummary` gained a `currency` parameter and formats via `CurrencyFormatter` instead of a
hardcoded euro sign.

### B1-12 - CLOSED
The emergency buffer is now CONVERTED to `displayCurrency` via the engine's existing `convertToDisplayOrNull`, falling back to
`rawBuffer to homeCurrency` when no rate exists. Chosen because neighbouring fields (`calculateRecurringOutflows`,
`expandDetectedPatterns`, installment projection) all use the same helper, so the buffer is now consistent with them.

### Test-harness notes
- My own instruction used `split(",", limit = -1)`, which is invalid Kotlin (Java-ism). Fixed to `split(",")` - Kotlin retains
  trailing empty strings by default, which is what the column-count assertion needs.
- `BudgetRecommendationEngineTest` now pins `Locale.US` and restores default/FORMAT/DISPLAY locales in a finally block, because
  `CurrencyFormatter` is locale-dependent. Without pinning, the assertion would be flaky across machines.
- `FinancialStressForecastEngineTest` AE-3 needed an `EUR -> USD` rate stub added to its fixture - the test's NAME says the buffer
  currency should follow the display currency, so the fixture was incomplete, not the assertion wrong.

### Slice status
S0-S4 COMPLETE. Z (candidate gate) next.
## Z - CANDIDATE GATE COMPLETE (2026-11-02)

### Ladder
| Step | Run | Result |
|---|---|---|
| 1 | unit-tests (FULL, fresh) | vr-20261102-161050-b51584d2 | PASS - 13640 tests, 0 failures |
| 2 | app-check | vr-20261102-164043-034373e5 | PASS - 0 lint errors, all verifiers PASS |
| 3 | static-guards | vr-20261102-171506-3860d0c0 | PASS - 27/27 |
| 4 | targeted after nit fixes | vr-20261102-182248-938f7272 | PASS - 545 tests |
| 5 | strict review | - | PASS-WITH-NITS, ZERO blocking |

### A REAL REGRESSION was caught at the gate (most important event in this batch)
The full suite found 4 failures. An independent reviewer analysed all 4 and determined THREE were a genuine regression WE introduced
in S3: changing MoneyNormalizationEngine.aggregateExpenses to set isPartial = false when nothing converted broke the documented
MoneyAggregate.isPartial contract (conversionFailures.isNotEmpty()) and two production callers:
- BudgetAutopilotEngine would treat an all-failed month as COMPLETE with 0.00 spend.
- BudgetRepository would clear budgetIsPartial.
REVERTED - MoneyNormalizationEngine.kt is now byte-identical to base. The isUnavailable distinction was KEPT only on types that have
an explicit field for it (PeriodTotal, PeriodSummary, CategoryBreakdown, CurrencyDataQuality), where it is legitimate.
The 4th failure was a stale test encoding the old FL-06 bug; updated.

LESSON: "partial" and "unavailable" are NOT interchangeable across types. Where a type has only isPartial, it means "not fully
trustworthy" and INCLUDES the none-converted case. Only types with an explicit isUnavailable field may split the two.

### Non-blocking nits found by strict review, all fixed
- N-1: RetroTotalsDashboardCard rendered 0.00 for unavailable periods (4 sites) and HomeScreen averaged them in. Fixed.
- N-3: computeFromNormalized silently DROPPED unavailable days instead of emitting an unavailable entry. Fixed for consistency.
- N-4: the emergency buffer fell back to an unconverted home-currency value then compared against displayCurrency balances. Now
  guarded by a currency-equality check so units are never mixed.
- N-5: DashboardBriefingInputBuilder passed null currency for Planned items. Fixed.
- N-2 (recorded, NOT fixed): Retro/Analytics sheets render "Unavailable" for the amount but still show "0%" and a medal. Cosmetic.
- N-6 (recorded): S1 added the el/es translations the plan had assigned to S2. Harmless - correct and lint-required.

### Downstream compatibility note (for the owner)
The export change means sentinel rows now emit BLANK (CSV) / NULL (JSON) where they previously emitted 0.0 / "" / 0. A strict consumer
that does Double.parseDouble(cell) without a blank check, or binds a non-nullable Double, will now fail. Nullable-aware parsers
(pandas, org.json, Jackson/Kotlinx with nullable types) will read NULL correctly instead of corrupting sums with 0.0. Intended, but
it IS a contract change.

### STATUS: branch is merge-ready pending owner decision. Nothing committed, nothing pushed.
## RETRO PER-SLICE REVIEW + FIX (2026-11-03) - ALL GREEN

### What the retroactive per-slice review found (4 MAJOR half-fixes)
1. **RB1-S3-2**: `PeriodSummary` shared ONE `isUnavailable` flag across three horizons (today/week/month), so an unconvertible Today
   with a convertible Month rendered `0.00` for Today. Fixed: added `isTodayUnavailable`, `isWeekUnavailable`, `isMonthUnavailable` and
   wired each `StatLabel` in `HomeScreen.kt:674-676`.
2. **RB1-S4-1**: `FinancialStressForecastEngine.getEmergencyBuffer` returned `rawBuffer to displayCurrency` when
   `resolveHomeCurrency()` failed (`homeCurrency == null`), relabelling an unconverted home buffer as `displayCurrency` and comparing
   incompatible units. Fixed: returns `rawBuffer to ""` so `bufferCurrency.equals(displayCurrency)` is false.
3. **RB1-S3-1**: unavailable categories still showed `"0%"` / `"0% of total"` and top-3 medals, and were not sorted last. Fixed in
   `TotalsAggregationEngine`, `AnalyticsViewModel`, `RetroCategoryBreakdownSheet`, `CategoryBreakdownSheet`, and `AnalyticsScreen`.
4. **RB1-S2-1**: `budget_cd_format`, `budget_suggestion_cd_format`, and `budget_suggestion_message_format` still hardcoded euro signs,
   and `budget_spent_format` was unwired. Fixed in `BudgetScreen.kt` and `values/strings.xml`.

### Verification
- Strict review of the 4 fixes: **GREEN** (all 4 rows).
- Architecture guardian: **PASS** (0 violations).
- Privacy-security guardian: **PASS**.
- Validation `vr-20261103-134730-2d2e5e14`: **PASS** - 466 tests / 35 suites (including 6 new tests).