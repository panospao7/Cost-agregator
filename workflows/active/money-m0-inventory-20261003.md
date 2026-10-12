# M-0 money findings and contract inventory

Date: 2026-10-03. Source checkpoint: `a1c66173219d7d959b0d36859a68176aaf814242` on `bug-fixes`.

## Status and scope

Current reconciliation (2026-10-07): M-3 through M-5 now have authored changes; the dated October 4 status below is historical. The October 6 fingerprint `8454ae...` passed the executed narrow validation scope and 25/25 static guards, but independent review rejected semantic money regressions. Resume from `workflows/active/money-w5-focused-review-20261007-handoff.md`; source remediation and fresh validation remain pending, with no completion or commit-readiness claim.

M-0 discovery and classification are recorded. Local contract review on 2026-10-03 corrected currency-support validation and the M060 description; independent money/architecture review remains pending. As of 2026-10-04, M-1a/M-1b, M-2a through M-2c2, and the M-2d personality caller audit are authored, not validated or closed; legacy/scalar consumers remain open. M-3 through M-5 remain unstarted. Resume from `workflows/active/money-m2c1-20261004-handoff.md`, `workflows/active/money-m2c2-20261004-handoff.md`, and `workflows/active/money-m2d-20261004-handoff.md`; earlier handoffs and validation queues still apply. This inventory is historical classification, not proof that any finding is closed.

This document reconciles the money track in `workflows/active/wave4-money-batch-plan-20261001.md` against current source. It is not a production patch, fresh guard result, release approval, or claim that all 83 findings are runtime bugs. No builds, tests, scanners, or registered guards were executed for M-0. No subagents were used.

Architecture authority: `docs/architecture/CODEBASE_SEGMENTS.md`, `CODEBASE_INVENTORY.md`, `LEGAL_PATHS.md`, and `ENGINE_INTERACTION_MAP.md`, followed by the owning source. Guard decisions remain subject to `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07, and FG-23. Existing lifecycle, worker, privacy, restore, and permission boundaries remain unchanged.

### Evidence provenance

- Persisted report: `build/ci/static-guards/raw_money_aggregates.log`.
- Report SHA-256: `D771BC967CABD2C5F1D46C6124AC77CE906B89A24C71932C012AF5570F71045D`.
- Associated durable static run: `vr-20261003-164616-50beeeac`, overall `FAIL`, exit 1 / `E_COMMAND_FAILED`; 24 of 25 guards passed, with `raw_money_aggregates` the sole blocking violation.
- That run's start/end fingerprint: `e375fbbfb1ff1e9c412faf17251b54e98b7cb262c62092532b9420db349ea05d`. It describes the pre-commit validated snapshot, not a fresh run on this document's worktree.
- The 28 finding files were inspected against current source. There is no uncommitted production or raw-money scanner change at inventory creation. The report is a source-matched historical inventory, not current-fingerprint validation evidence.
- Findings: **83 in 28 files**. Earlier 84/29 and 85/29 reports are historical and must not be substituted for this inventory.

| Guard rule | Count | What the current scanner recognizes |
| --- | ---: | --- |
| G-MONEY-RAW-01 | 5 | `sumOf { it.amount }` |
| G-MONEY-RAW-02 | 55 | `sumOf { it.effectiveAmount }` |
| G-MONEY-RAW-03 | 5 | `sumOf { it.normalizedAmount }` |
| G-MONEY-RAW-04 | 4 | price-field collection sums |
| G-MONEY-RAW-05 | 0 | integer-converting amount sums |
| G-MONEY-RAW-06 | 14 | lexical `total: Double` declarations, including some unintended matches |
| G-MONEY-RAW-07 | 0 | running-total pattern |
| Total | 83 | No finding is closed by this document. |

## Classification

The scanner in `scripts/verify_raw_money_aggregates.py` is syntactic, not a proof of dataflow or currency compatibility. Its approved builder/engine file exclusions do not authorize consumer-side arithmetic. It also recognizes `fromBuckets` block scope; moving a sum into such a block or changing lambda spelling is not remediation.

| Code | Count | Meaning and disposition |
| --- | ---: | --- |
| A-N | 47 | Financial sum with an inspected normalization path. Preserve that path and migrate the aggregate/quality boundary; do not infer a current mixed-currency bug merely from the sum. Raw-list overloads still need explicit preconditions. |
| A-S | 13 | Financial sum within one receipt or one source-currency group. Use an explicit validated single-currency aggregation boundary; do not introduce unnecessary FX conversion. |
| A-U | 9 | Financial sum whose currency precondition is absent or bypassable on the inspected path. Includes five findings confined to a deprecated raw personality path. Fix or explicitly close the path; do not assume every path is currently used by production UI. |
| B | 10 | Aggregate/output scalar contract needs currency/quality ownership. A typed getter over a stored `Double` does not by itself establish that boundary. |
| C | 4 | Candidate scanner-scope overreach: helper parameters, a `subtotal` substring, or a presentation-only parameter. Requires source-backed guard review; not an exemption or automatic closure. |
| Total | 83 | 69 financial sums, 10 scalar contracts, 4 guard-review candidates. |

### Highest-priority source-backed risks

1. **Money radar:** `ComputeMoneyRadarUseCase.kt:291` sums expenses that the method itself fetches from `ExpenseRepository`. Its comment requiring callers to normalize does not apply to those privately fetched rows. Budget and upcoming recurring amounts must also share the comparison currency and rate policy. Fix the complete risk-input boundary, not just the matched sum.
2. **Cash-flow details:** `CashFlowCalculator` normalizes day aggregates for balances but publishes original expense/income lists in `DailyCashFlow`. `CashFlowCalendarScreen.kt:376` recomputes a raw total from those lists and displays it as home currency. Adjacent income/recurring sums have the same consumer problem even though they are not among the 83 matches. Publish/consume the existing domain aggregates rather than converting in Compose.
3. **Contribution history:** `SavingsContributionEvent` records goal ID, amount, timestamp, and source, but no currency. `SavingsGoal` does have currency/assumption fields. Looking up today's goal currency does not prove an old contribution's currency, especially after edits/deletion or home-currency changes. The legacy-data contract must be decided before claiming a normalized cross-goal total.
4. **Blank synthesis currency:** `SynthesisEngine.kt:674` deliberately retains a raw identity branch when block-party display currency is blank. The explicit-currency branch has conversion handling. Preserve valid callers, but define controlled unavailable behavior for missing currency rather than blessing the legacy branch.
5. **Deprecated personality path:** the five raw personality sums are reached by `classify()`'s repository-fetching path. The inspected analytics ViewModel calls `classify(NormalizedAnalyticsInput)` instead, which has separate calculations. Deprecation alone does not make the old method currency-safe; verify callers before removal or adaptation.

These are source-inspection conclusions. No new runtime reproduction was performed during M-0.

## Contracts to preserve before implementation

### Existing boundaries, not a second money system

All paths below are under `app/src/main/java/com/yourname/expensetracker/`.

- `domain/core/money/MoneyAmount.kt`: a single-currency amount. It currently stores `Double`, rejects non-finite input, and restricts currency arithmetic. A global `BigDecimal` migration or replacement of percentages/scores is outside this workstream.
- `domain/core/money/CurrencyCode.kt`: `CurrencyCode.parse` validates three-letter ASCII shape and normalizes case, but does not check the supported catalog. External source currency also requires membership in `CurrencyCode.ALL_SUPPORTED`. Neither successful parsing nor constructor validation alone proves support. No guessed EUR/USD/home currency on failed resolution.
- `domain/core/money/MoneyAggregate.kt` and `MoneyAggregateMetadata.kt`: aggregate value, source buckets, conversion failures, requested/actual rate basis, quality, included/excluded counts, rate dates, and count completeness belong together.
- `domain/core/money/MoneyAggregateResult.kt`: `Available(aggregate)` / `Unavailable(reason, requestedRateBasis, metadata, warningMessage)`. Partial is aggregate quality, not a separate result subtype. Some current engine APIs also express all-failed conversion as `ConversionQuality.UNAVAILABLE` on an aggregate; adapters must inspect quality, not blindly treat every returned aggregate as displayable.
- `domain/core/money/MoneyNormalizationEngine.kt`: use for original transaction rows and typed planned/source-currency buckets. Historical transactions and planned/latest buckets are distinct contracts.
- `domain/analytics/AnalyticsCurrencyNormalizer.kt` and `NormalizedAnalyticsInput.kt`: already-normalized rows retain original currency, rate provenance, exclusions, and confidence/quality. `toExpenseSnapshot()` changes amount/effective amount/currency to normalized values; those snapshots must not then be converted again as original transactions.
- `domain/core/money/HomeCurrencyForMoneyMath.kt`: distinguish valid resolved currency, explicitly supported first-run default, and unavailable settings. A settings failure is not valid empty spending.

### Reusable adapters to review in the producer batches

1. **Already-normalized slice aggregation (M-2a):** prefer a narrow overload/helper beside the existing approved money boundary, consuming typed normalized rows plus explicit currency and quality context. Validate row currency, retain original/rate metadata where available, and state which exclusions belong to the slice. Do not attach a whole six-month excluded count to every day/category or drop it completely. Where exclusion attribution is unavailable, carry that uncertainty instead of inventing exact counts.
2. **Known source-currency/receipt aggregation (M-1a, reused in M-3a/M-4c):** aggregate typed same-currency values without FX. Reject missing/conflicting currency and non-finite amounts before producing a total. Keep monetary totals inside approved builders; counts, ratios, and allocation weights may remain scalar. Preserve tax allocation and rounding behavior rather than rounding each source item prematurely.
3. **Typed original-row aggregation (M-4a):** reuse `MoneyNormalizationEngine` for radar/cash-flow original rows, preserving transaction-type filters, ownership, signs, transaction dates, and target currency. Do not pass normalized snapshots back through historical conversion.
4. **Presentation projection (M-4b/M-4c):** consume aggregate availability/quality and a typed amount/currency together. Conversion to chart `Float` or formatted text happens only after the financial calculation; UI must not recompute totals from metadata-only rows.

`MoneyAggregateBuilder.fromHomeCurrencyAmounts` currently validates finite values/currency and creates an identity aggregate/count. It does **not** carry upstream exclusions, source-rate provenance, or confidence. Wrapping every existing sum in that helper alone would erase information. Extend/reuse it only with an explicit reviewed quality contract. The deprecated `fromBuckets(List<Pair<Double, String>>, ...)` is latest-only and must not gain new historical callers; prefer typed bucket APIs.

### Availability and quality expectations

| Input/state | Required result/consumer behavior |
| --- | --- |
| Valid currency, genuinely empty eligible input | Available empty/zero aggregate with correct currency and zero included count; not a fabricated conversion success. |
| Unresolved home currency, invalid target, unsupported source | Controlled unavailable or explicit excluded-source outcome according to the owning policy; never default to EUR, never compare unconverted values. |
| Some conversions fail | Preserve successful value, affected/excluded counts, original buckets and partial warning. Consumer may decline partial results; health engines' existing all-or-nothing policy must not be relaxed silently. |
| All eligible conversions fail | Unavailable display/decision, not a healthy zero-spend result. Preserve failure metadata. |
| Already-normalized rows | No second FX pass; retain historical/estimated/stale provenance rather than relabeling all input as identity/complete. |
| Planned commitments or recurring estimates | Keep latest/planned conversion policy distinct from historical actuals; preserve existing materialized-occurrence/deduplication and restore-blocked quality. |
| Dimensionless ratio, score, count, chart coordinate | May stay `Double`/`Float`/integer. Monetary numerators/denominators must first have compatible currency and quality. |
| Invalid/non-finite input or overflow | Controlled failure/unavailable at the boundary; no infinite, NaN, or arbitrary exception-message output. |

## File ownership and consumer contracts

File IDs resolve the occurrence table below. Paths are relative to `app/src/main/java/com/yourname/expensetracker/`. Segment numbers refer to `CODEBASE_SEGMENTS.md`; slice IDs are defined in the implementation queue later in this document.

| File ID | Path | Findings | Owning segment / slice | Current consumer boundary -> expected boundary |
| --- | --- | ---: | --- | --- |
| F01 | `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt` | 2 | 5; tax collaboration 17 / M-1b | `ReceiptItemCategorizationResult.taxDistribution: Map<Long, Double>` -> currency-backed aggregate/allocation contract; use the input receipt currency, retain allocation ratios and residual policy. |
| F02 | `domain/analytics/AdvancedAnalyticsEngine.kt` | 5 | 8 / M-2c | Category deltas, patterns, daily statistics and velocity amounts -> typed aggregates over normalized slices, with existing advanced-analysis quality retained. |
| F03 | `domain/analytics/AdvancedAnalyticsModels.kt` | 1 | 8 / M-2c | `HistogramBin.total` plus currency/getter -> currency-owned aggregate storage, with scalar histogram coordinates unchanged. |
| F04 | `domain/analytics/AnalyticsModels.kt` | 3 | 8 / M-2b | `SpendingPeriod`, `AnalyticsCategoryBreakdown`, `MonthlyYearTotal` -> typed period/category/year totals; period currently lacks explicit currency. Preserve available partial metadata. |
| F05 | `domain/analytics/CategoryInsightEngine.kt` | 3 | 8 / M-2c | Raw-list calculation yielding `CategoryInsight` -> normalized-input/aggregate contract; public raw overload cannot rely only on an EUR default. |
| F06 | `domain/analytics/DailyBucketEngine.kt` | 2 | 8 / M-2b | `DailyBucket(date, total, count)` -> currency-owned daily aggregate; retain calendar-day and empty-day semantics. |
| F07 | `domain/analytics/DayOfWeekAnalyzer.kt` | 1 | 8 / M-2c | Raw-list `DayOfWeekInsight` calculation -> normalized slice aggregates before averages/shares. |
| F08 | `domain/analytics/MonthlyComparisonCalculator.kt` | 2 | 8 / M-2b | Current/previous values in `MonthlyComparison` -> compatible typed period aggregates before change percentage. |
| F09 | `domain/analytics/SpendingPaceCalculator.kt` | 2 | 8 / M-2b | Current/previous totals in `SpendingPace` -> typed period totals preserving unavailable/projection sentinels. |
| F10 | `domain/analytics/SpendingPersonalityClassifier.kt` | 5 | 8 / M-2d | Deprecated raw self-fetch `classify()` -> adapt to normalized contract or remove only after caller/test audit; final feature scores remain dimensionless. |
| F11 | `domain/analytics/TotalsAggregationEngine.kt` | 5 | 10 / M-2b | `PeriodTotal`, `TotalsSummary`, day averages and status helper -> typed canonical totals; independently review helper-parameter scanner hit. |
| F12 | `domain/budget/BudgetForecastingEngine.kt` | 1 | 2; forecast consumer 1 / M-3b | Normalized history -> `MonthlySpendingTotal`/budget forecast using typed monthly money and unchanged budget FX/quality. |
| F13 | `domain/export/AccountantReportPdfExporter.kt` | 5 | 17; export consumer 18 / M-4c | Per-currency PDF sections and deductible aggregate -> typed same-source-currency totals; keep report sections separate and explicitly pin combined deductible rate basis. |
| F14 | `domain/forecasting/FinancialStressForecastEngine.kt` | 3 | 13; forecast collaboration 1 / M-3a | Normalized income, discretionary day totals and `RecurringOutflowResult` -> typed forecast inputs with existing blocked/partial behavior. |
| F15 | `domain/forecasting/ForecastInputAssembler.kt` | 4 | 1 / M-3a | Normalized historical pace/owned spending plus per-currency planned buckets -> typed values with historical actuals and latest planned amounts kept distinct. |
| F16 | `domain/forecasting/HistoricalSpendingDistribution.kt` | 2 | 1 / M-3a | Normalized weekly amount in `WeekData`/distribution fitting -> typed weekly money then scalar distribution statistics. |
| F17 | `domain/health/FinancialHealthCalculator.kt` | 5 | 35 / M-3c | Day/week/month spending into `FinancialHealthScore` -> typed normalized inputs; final scores remain scalar. |
| F18 | `domain/health/FinancialHealthScoreV2.kt` | 3 | 35 / M-3c | Income/purchase/runway totals into health output -> typed currency-compatible inputs; preserve refusal of excluded/incomplete input. |
| F19 | `domain/logic/SynthesisEngine.kt` | 1 | 1; dashboard consumer 10 / M-3a | Blank-currency identity branch in block-party actuals -> explicit available/unavailable aggregate; preserve valid explicit-currency behavior and daily-history fallback. |
| F20 | `domain/receipt/ReceiptParser.kt` | 7 | 4 / M-1a | `ParsedReceipt` monetary fields, line-item sums and confidence inputs -> currency-owned receipt totals and optional absence; two declaration hits need guard review rather than blind API replacement. |
| F21 | `domain/savings/SavingsGamificationEngine.kt` | 1 | 35 / M-3d | Currency-less `SavingsContributionEvent` history into monthly contribution metrics -> attributable typed money or explicit unknown-currency exclusion/unavailable; legacy-history decision required. |
| F22 | `domain/savings/SmartSavingsEngine.kt` | 6 | 35 / M-3c | Normalized actual/history totals into recommendations/simulation -> typed inputs preserving confidence, seeded simulation and budget semantics. |
| F23 | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 2 | 10 / M-4b | `CategorySpending.total` plus currency/getter; normalized block-party chart sum -> typed producer totals and final chart-only scalar projection. |
| F24 | `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` | 1 | 10 / M-4a | Original rows into budget-risk Monte Carlo -> normalized spending, budget and upcoming obligations in one explicit comparison currency; unavailable on failed resolution. |
| F25 | `domain/usecase/savings/MonthlySavingsSweepUseCase.kt` | 1 | 35; orchestration 31 / M-3c | Normalized owned spending into sweep recommendation -> typed spending with recurring/committed and conversion quality preserved. |
| F26 | `ui/components/TotalsDashboardCard.kt` | 1 | 10 / M-4b | Private `CurrentPeriodSummary(total, currency)` formatting parameter -> presentation projection from typed totals; separately review scanner parameter scope. |
| F27 | `ui/screens/analytics/AnalyticsViewModel.kt` | 8 | 8 / M-4b | Normalized period/category/merchant/velocity/year/pay-cycle values -> consume owner aggregates rather than duplicate financial arithmetic in ViewModel. |
| F28 | `ui/screens/cashflow/CashFlowCalendarScreen.kt` | 1 | 13 / M-4a | Original lists displayed as home-currency totals -> consume normalized aggregates published by `CashFlowCalculator`/`DailyCashFlow`; preserve raw lists for item metadata only. |

Primary source-group counts remain M-1: 9, M-2: 29, M-3: 27, M-4: 18 = 83. M-5 reviews residual rule evidence across those groups; it does not add another set of findings.

## Every finding, reconciled to source

`RAWxx` means `G-MONEY-RAW-xx`. Line numbers are checkpoint anchors, not permanent identities. File ID supplies the path, owner, consumer contract, and implementation slice above. Stable IDs M001-M083 survive later line movement. All entries remain open until implementation or an explicitly reviewed scanner disposition and validation.

| ID | File | Line | Rule | Class | Inspected use |
| --- | --- | ---: | --- | --- | --- |
| M001 | F01 | 170 | RAW01 | A-S | All-item amount denominator for receipt tax allocation. |
| M002 | F01 | 175 | RAW01 | A-S | Category item amount weight for receipt tax allocation. |
| M003 | F02 | 287 | RAW02 | A-N | Previous category spending. |
| M004 | F02 | 550 | RAW02 | A-N | Pattern spending total. |
| M005 | F02 | 719 | RAW02 | A-N | Daily-average spending statistic. |
| M006 | F02 | 912 | RAW02 | A-N | First-half spending velocity basis. |
| M007 | F02 | 914 | RAW02 | A-N | Second-half spending velocity basis. |
| M008 | F03 | 253 | RAW06 | B | Histogram-bin total storage. |
| M009 | F04 | 197 | RAW06 | B | Spending-period total storage. |
| M010 | F04 | 218 | RAW06 | B | Category-breakdown total storage. |
| M011 | F04 | 310 | RAW06 | B | Monthly-year total storage. |
| M012 | F05 | 82 | RAW02 | A-N | Overall current spending for category shares. |
| M013 | F05 | 118 | RAW02 | A-N | Previous category spending. |
| M014 | F05 | 125 | RAW02 | A-N | Current category spending. |
| M015 | F06 | 22 | RAW06 | B | DailyBucket total storage. |
| M016 | F06 | 44 | RAW03 | A-N | Calendar-day normalized total. |
| M017 | F07 | 43 | RAW02 | A-N | Day-of-week spending before insight calculation. |
| M018 | F08 | 34 | RAW02 | A-N | Current-month total. |
| M019 | F08 | 36 | RAW02 | A-N | Previous-month total. |
| M020 | F09 | 64 | RAW02 | A-N | Current-month pace basis. |
| M021 | F09 | 74 | RAW02 | A-N | Previous-month pace basis. |
| M022 | F10 | 222 | RAW02 | A-U | Deprecated raw-path weekend spending numerator. |
| M023 | F10 | 225 | RAW02 | A-U | Deprecated raw-path weekend share denominator. |
| M024 | F10 | 239 | RAW02 | A-U | Deprecated raw-path night spending numerator. |
| M025 | F10 | 242 | RAW02 | A-U | Deprecated raw-path night share denominator. |
| M026 | F10 | 289 | RAW02 | A-U | Deprecated raw-path category budget adherence. |
| M027 | F11 | 453 | RAW06 | C | getPeriodStatus helper parameter, not an aggregate declaration. |
| M028 | F11 | 590 | RAW03 | A-N | Period day-average numerator. |
| M029 | F11 | 603 | RAW03 | A-N | Per-day normalized spending. |
| M030 | F11 | 628 | RAW06 | B | TotalsSummary aggregate storage. |
| M031 | F11 | 637 | RAW03 | A-N | Normalized owned-purchase summary. |
| M032 | F12 | 501 | RAW02 | A-N | Normalized historical monthly spending. |
| M033 | F13 | 65 | RAW02 | A-S | Deductible amount within a source-currency group. |
| M034 | F13 | 109 | RAW02 | A-S | PDF source-currency section total. |
| M035 | F13 | 123 | RAW02 | A-S | Source-currency denominator for category percentages. |
| M036 | F13 | 129 | RAW02 | A-S | Source-currency category sorting total. |
| M037 | F13 | 133 | RAW02 | A-S | Source-currency category display total. |
| M038 | F14 | 449 | RAW02 | A-N | Normalized deposit income. |
| M039 | F14 | 520 | RAW02 | A-N | Normalized discretionary day totals. |
| M040 | F14 | 844 | RAW06 | B | RecurringOutflowResult amount with read-blocked flag. |
| M041 | F15 | 342 | RAW02 | A-N | Spending-pace monthly baseline; raw public input precondition needs enforcement. |
| M042 | F15 | 539 | RAW01 | A-S | Planned-expense source-currency bucket before latest conversion. |
| M043 | F15 | 672 | RAW02 | A-N | Owned-purchase total of normalized snapshots. |
| M044 | F15 | 701 | RAW01 | A-S | Alternate planned-expense source-currency bucket. |
| M045 | F16 | 178 | RAW02 | A-N | Normalized historical weekly spending. |
| M046 | F16 | 242 | RAW06 | B | WeekData monetary total storage. |
| M047 | F17 | 137 | RAW02 | A-N | Normalized today spending. |
| M048 | F17 | 186 | RAW02 | A-N | Normalized week spending. |
| M049 | F17 | 191 | RAW02 | A-N | Week daily spending. |
| M050 | F17 | 241 | RAW02 | A-N | Normalized month spending. |
| M051 | F17 | 246 | RAW02 | A-N | Month daily spending. |
| M052 | F18 | 305 | RAW02 | A-N | Normalized income total. |
| M053 | F18 | 307 | RAW02 | A-N | Normalized purchase total. |
| M054 | F18 | 353 | RAW02 | A-N | Normalized monthly spend for runway. |
| M055 | F19 | 674 | RAW02 | A-U | Blank display-currency legacy block-party branch. |
| M056 | F20 | 72 | RAW04 | A-S | Line-item total comparison for tax-inclusive inference. |
| M057 | F20 | 97 | RAW06 | B | Optional ParsedReceipt monetary total. |
| M058 | F20 | 98 | RAW06 | C | subtotal substring matched by an unbounded total regex; still a monetary scalar. |
| M059 | F20 | 233 | RAW04 | A-S | Positive item-total fallback when receipt total is absent. |
| M060 | F20 | 258 | RAW04 | A-S | Inline line-item sum for tax-inclusive comparison. |
| M061 | F20 | 848 | RAW06 | C | Optional calculateConfidence helper parameter. |
| M062 | F20 | 888 | RAW04 | A-S | Item-total comparison in confidence calculation. |
| M063 | F21 | 196 | RAW01 | A-U | Monthly contribution history lacks event currency. |
| M064 | F22 | 257 | RAW02 | A-N | Normalized month spending. |
| M065 | F22 | 284 | RAW02 | A-N | Normalized historical baseline. |
| M066 | F22 | 314 | RAW02 | A-N | Simulation current-month spending. |
| M067 | F22 | 327 | RAW02 | A-N | Simulation previous-month spending. |
| M068 | F22 | 341 | RAW02 | A-N | Simulation historical daily-average basis. |
| M069 | F22 | 588 | RAW02 | A-N | Normalized projection-range spending. |
| M070 | F23 | 234 | RAW06 | B | CategorySpending total plus currency/typed getter. |
| M071 | F23 | 824 | RAW03 | A-N | Block-party day total before chart Float projection. |
| M072 | F24 | 291 | RAW02 | A-U | Privately fetched original expenses compared with budget/recurring inputs. |
| M073 | F25 | 231 | RAW02 | A-N | Normalized owned spending for monthly sweep. |
| M074 | F26 | 143 | RAW06 | C | Private CurrentPeriodSummary formatting parameter with separate currency. |
| M075 | F27 | 420 | RAW02 | A-N | Current normalized period total. |
| M076 | F27 | 422 | RAW02 | A-N | Previous normalized period total. |
| M077 | F27 | 442 | RAW02 | A-N | Normalized category total. |
| M078 | F27 | 458 | RAW02 | A-N | Normalized merchant total. |
| M079 | F27 | 778 | RAW02 | A-N | Normalized daily velocity basis. |
| M080 | F27 | 838 | RAW02 | A-N | Normalized monthly-year total. |
| M081 | F27 | 955 | RAW02 | A-N | Normalized post-payday spending window. |
| M082 | F27 | 964 | RAW02 | A-N | Normalized pay-cycle category spending. |
| M083 | F28 | 376 | RAW02 | A-U | UI recomputes home-currency expense display from original rows. |

## Implementation queue and gates

These are proposed pipeline-local slices, not permission to change all 28 files together. Each slice needs an exact write set, focused behavioral tests, strict money/architecture review, then user-run serialized validation before its successor. No batching of Gradle processes or changing source between evidence runs. Shared-file slices are sequential; their scope must not be duplicated by parallel workers. No subagents are part of the current workflow.

The original M-1 -> M-5 numbering remains intact. Dependencies are producer contracts, not a requirement to leave a confirmed unsafe consumer untouched until every unrelated engine is migrated. M-4a radar/cash-flow fixes can be brought forward as separately reviewed slices once the existing normalization contracts and required domain DTO changes are pinned.

### M-1: receipt and source-currency producers (9 findings)

| Slice | Initial write scope | Contract and regression work | Dependency / gate |
| --- | --- | --- | --- |
| M-1a | `ReceiptParser`, necessary receipt money boundary in `domain/core/money`, direct parser tests | Establish validated same-receipt-currency aggregation. Preserve optional total/subtotal, item fallback, tax-inclusive tolerance, confidence thresholds, decimal parsing and rounding. Decide how typed totals coexist with the parser DTO without changing every receipt consumer at once. Test explicit/missing currency and malformed/non-finite amounts. M058/M061 remain guard-review candidates if not meaningfully resolved by the contract change. | Start here after M-0 contract review. Focus `*ReceiptParserTest*`, `*ReceiptParserOcrPatternsTest*`, `*MoneyAggregateBuilder*`; include receipt lifecycle/parser consumers affected by any DTO change. |
| M-1b | `OnDeviceReceiptItemCategorizationService`, categorization input/result contract only as needed, direct tests | Thread the existing receipt currency into tax-distribution arithmetic; typed category/all-item totals, stable weights, explicit no-tax/zero/unavailable behavior, and preserved unrounded shares with a typed unallocated/residual amount. No AI routing/provider selection/privacy changes. | Authored, NOT RUN; see `money-m1b-20261003-handoff.md`. Reuse M-1a boundary. Focus `*OnDeviceReceiptItemCategorizationServiceTest*`, `*ReceiptItemCategorizationInputBuilderTest*`; strict receipt/money review remains pending. |

### M-2: normalized analytics producers and canonical totals (29 findings)

| Slice | Initial write scope | Contract and regression work | Dependency / gate |
| --- | --- | --- | --- |
| M-2a | Existing approved money builder/normalization boundary, narrow normalized-input adapter, direct contract tests | Implemented in `money-m2a-20261004-handoff.md`: reusable already-normalized aggregation with no second conversion, explicit currency agreement, empty/partial/all-excluded outcomes, slice-local exclusion attribution, requested/actual rate basis, source/rate provenance and counts. No mass caller migration in this foundation slice. | M-0 contracts; reuse M-1a primitives where appropriate. Review and validation pending before M-2b1. |
| M-2b1 | `DailyBucketEngine`, `TotalsAggregationEngine`, immediately affected totals models/tests | Typed calendar-day and period totals; keep DST/half-open period/owned-purchase/zero-day rules and unavailable/status baselines. Review M027 separately from substantive totals. | M-2a. Focus `*TotalsAggregationEngine*`, dashboard consistency/window tests; add daily-bucket fixtures in the existing unit-test tree. |
| M-2b2 | `AnalyticsModels`, `MonthlyComparisonCalculator`, `SpendingPaceCalculator`, direct consumers/tests | Typed current/previous period values and currency-owned DTOs. Keep zero baseline, percentage change, month-length and projection-sentinel behavior. Update minimal callers atomically to keep compilation possible; do not retain fake scalar defaults. | M-2a/M-2b1 contracts. Focus `*MonthlyComparisonCalculatorTest*`, `*SpendingPace*`, analytics/dashboard budget parity golden. |
| M-2c1 | `AdvancedAnalyticsEngine`, `AdvancedAnalyticsModels`, direct tests | Aggregate normalized category/pattern/statistic/velocity slices; preserve histogram coordinates, sample size, confidence, anomaly and period-window behavior. | M-2a; model integration after M-2b2 if shared. Focus `*AdvancedAnalyticsEngine*`, `*AdvancedAnalyticsDashboardTest*`. |
| M-2c2 | `CategoryInsightEngine`, `DayOfWeekAnalyzer`, direct tests | Use normalized contracts for category and weekday financial numerators/denominators; retain dimensionless shares/averages and neutral empty results. Audit legacy raw-list entry points rather than trusting comments/default EUR. | M-2a/M-2b2. Focus `*CategoryInsightEngineTest*`, `*DayOfWeekAnalyzerTest*`, insight integration tests. |
| M-2d | `SpendingPersonalityClassifier`, actual legacy callers/tests only | Audit `classify()` callers; either adapt its self-fetched rows/budgets safely or remove the legacy path only after verified ownership/caller approval. Keep the canonical normalized classifier's current feature/confidence behavior unchanged. | M-2a and caller audit. Focus `*SpendingPersonalityClassifierTest*` and analytics ViewModel insights tests. Deprecation/test-only assertions alone do not close a production API. |

### M-3: forecast, budget, health, savings (27 findings)

| Slice | Initial write scope | Contract and regression work | Dependency / gate |
| --- | --- | --- | --- |
| M-3a1 | `ForecastInputAssembler`, `SynthesisEngine`, direct forecast/block-party tests | Typed historical pace/owned totals; typed same-source-currency planned buckets with latest rates; missing block-party currency becomes controlled unavailable. Preserve weighted planned priorities, converted proportional distribution, paid/committed deduplication, daily-history fallback and seeded outputs. | M-1a source-group boundary / M-2a normalized adapter. Focus `*ForecastInputAssembler*`, `*SynthesisEngine*`, `*ForecastSynthesisGoldenTest*`. |
| M-3a2 | `FinancialStressForecastEngine`, `HistoricalSpendingDistribution`, direct tests | Typed monthly income, discretionary-day/weekly totals and recurring outflows. Do not normalize distribution coordinates/scores as if they were currencies. Retain restore/read-blocked and forecast quality. | M-2a/M-3a1 contracts. Focus `*FinancialStressForecastEngineTest*`, `*HistoricalSpendingDistributionBoundaryTest*`. |
| M-3b | `BudgetForecastingEngine`, direct forecast tests | Typed monthly historical spending and budget comparison; preserve historical expense rates, budget rate policy and warning/diagnostic behavior. No new hierarchy/autopilot redesign. | M-2a. Focus `*BudgetForecastingEngine*`, budget FX/parity goldens. |
| M-3c1 | `FinancialHealthCalculator`, `FinancialHealthScoreV2`, direct tests | Typed today/week/month/income/purchase/runway inputs. Preserve V2's refusal of incomplete converted input and neutral/unavailable status; no zero-spend improvement on missing rates. | M-2a/M-2b1. Focus `*FinancialHealthCalculator*`, `*FinancialHealthScoreV2Test*`, health-score cross-regressions. |
| M-3c2 | `SmartSavingsEngine`, `MonthlySavingsSweepUseCase`, direct tests | Typed history/month/range/sweep inputs, preserving recommendation confidence, ownership filters, seeded simulations, budget/committed obligations and allocation semantics. | M-2a/M-3a contracts. Focus `*SmartSavingsEngineTest*`, `*MonthlySavingsSweepUseCaseTest*`. |
| M-3d | `SavingsGamificationEngine`, `SavingsContributionHistoryRepository`, contribution recording callers/tests only as required | First record an owner decision for currency-less legacy events. New events need attributable currency/assumption at write time if persisted schema is changed. Unknown history must stay unavailable/excluded, not relabeled with today's home/goal currency. Audit goal deletion/currency changes and preserve existing data before designing any storage migration. | Decision-gated, not ready for a one-line sum replacement. Focus `*SavingsGamificationEngineTest*`, `*SavingsContributionHistoryRepositoryTest*`, contribution callers. Any Room/DataStore change gets its own migration/privacy review. |

### M-4: consumer corrections and rendering (18 findings)

| Slice | Initial write scope | Contract and regression work | Dependency / gate |
| --- | --- | --- | --- |
| M-4a1 | `ComputeMoneyRadarUseCase`, budget/recurring normalization adapter only as needed, direct tests | Fix the privately fetched original-row risk inputs. Actual spending, budget cap and recurring obligations must have one validated target currency with explicit historical/planned policies. Show unavailable rather than raw Monte Carlo inputs if resolution/conversion fails. Preserve owned-purchase/time-window semantics. | Can run early using existing `MoneyNormalizationEngine`; do not wait for unrelated model migrations. Focus `*ComputeMoneyRadarUseCaseTest*`, dashboard projection safety and unavailable-currency fixtures. |
| M-4a2 | `CashFlowCalculator`/`DailyCashFlow`, `CashFlowCalendarScreen`, directly affected ViewModel/tests | Publish domain day aggregates already used for balance arithmetic; UI consumes them for income/expense/recurring details and quality. Retain original lists for drill-down metadata, transfer directions, sign conventions and occurrence deduplication. No FX/network work in Compose. | Can run early as an isolated producer/consumer patch. Focus `*CashFlowCalculatorTest*`, `*CashFlowCalendarViewModelTest*`, new mixed-currency detail projection fixture. |
| M-4b1 | `AnalyticsViewModel`, minimal owner DTO adapters and direct tests | Replace duplicated period/category/merchant/velocity/year/pay-cycle arithmetic with owner aggregates; preserve exclusions, selection windows and immutable UI state. | M-2 producer contracts. Focus `*AnalyticsViewModel*`, `*AdvancedAnalyticsViewModelTest*`, lifestyle/dashboard parity tests. |
| M-4b2 | `ComputeDashboardWidgetsUseCase`, `TotalsDashboardCard`, required widget DTO consumers/tests | Typed category/day producer values; chart `Float` and text are terminal projections. Keep missing/partial warnings, uncategorized identity, clickable/filter rules, period windows and block-party/day alignment. M074 remains a reviewed presentation-scope candidate if the contract is already sufficient. | M-2/M-3 outputs. Focus `*ComputeDashboard*`, `*DashboardWidgetConsistencyTest*`, `*DashboardCurrencyIntegrationTest*`, `*HomeDashboardFinancialInvariantTest*`. |
| M-4c | `AccountantReportPdfExporter`, direct export/report tests | Same-source-currency sections remain separate. Route per-category/section/deductible totals through typed producers; pin combined deductible latest/historical policy before changing the legacy builder. Preserve transaction filters, category ordering, formatting, percentages and PDF error handling. | M-1a source-group primitives and explicit deductible rate-policy review. No dedicated exporter test was located in the scoped inventory; add direct fixtures using existing test infrastructure and run export-policy cross-regressions. Device PDF/rendering remains a separate gate. |

### M-5: evidence, scanner-scope review, and closure

1. Reconcile all M001-M083 against final source and fresh guard output. Expect line movement; match stable file/use/rule identity, not line counts alone. Adjacent fixes may add tests/files outside the 28 finding files but must stay in the owning pipeline.
2. Review C candidates M027, M058, M061 and M074 against the actual intended rule contract. `RAW06` currently has no leading word boundary and does not distinguish DTO properties from helper/presentation parameters. If a minimal scanner correction is warranted, add positive/negative regression fixtures proving valid money sums and untyped aggregate DTOs remain blocked. No new filename allowlist, baseline, exclusion, or wrapper bypass.
3. Review any scanner change separately under `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03/FG-06/FG-07/FG-23 before executing it. A false-positive label in M-0 is not approval to weaken the check.
4. User-run registered raw-money/money-boundary checks and the full static suite on one stable final fingerprint. Inspect durable status, exit code, start/end fingerprint and completion marker, not wrapper text alone. No stale/hung/infra run is PASS.
5. Record source dispositions, test results, review/guardian outcome, current finding counts and explicit outstanding device/privacy debts. A nonzero static-suite result remains FAIL even if the only failure is inherited and separately carried.

## Shared regression fixture matrix

Use these cases where they apply to the slice; do not duplicate every engine's fixture or mutate financial policies to get tests green.

| Fixture | Required observation |
| --- | --- |
| Same-currency USD input with two owned purchases | Exact existing numeric result, correct currency/counts, no FX call; excludes not-mine/non-spending according to owner. |
| Mixed EUR/USD original rows at two transaction dates | Historical rates chosen per actual row; typed target result agrees with normalizer; no source-currency addition or second conversion. |
| Planned future obligations mixed with actual historical rows | Latest/planned bucket policy separated from historical actuals; requested/actual rate provenance not fabricated. |
| One successful and several failed conversions | Partial amount, affected transaction count (not warning count), exclusions and confidence preserved. |
| All conversions fail versus genuinely empty input | Unavailable/all-failed is visibly different from valid zero/empty; health/budget/Monte Carlo never improve on failed data. |
| Missing/invalid home currency and legacy blank source currency | Controlled unavailable/exclusion; explicit supported first-run behavior only, no accidental EUR fallback. |
| Already-normalized rows with stale/estimated rate metadata | No second conversion; quality/rate dates and ownership remain visible at the slice/window boundary. |
| Category/day subsets of a partial window | Correct included counts; do not assign every window exclusion to every slice or declare slice conversion complete without evidence. |
| Zero, refunds/negative values where supported, tiny values, huge/non-finite values | Preserve sign/type policy and existing rounding tolerances; reject invalid/overflow states in a controlled way. |
| Month/year boundaries, leap day, DST, empty days | Preserve calendar grouping and half-open ranges, not fixed-millisecond approximations. |
| Receipt items with tax-inclusive/exclusive totals | Stable fallback/confidence and allocation rounding; one explicit receipt currency, no invented per-item FX. |
| Cash-flow original lists with normalized day balance | Detail income/expense/recurring amounts agree with published domain aggregates and warnings. |
| Radar budget, actuals and upcoming obligations in different currencies | Comparison inputs share validated target/rate policy; unavailable when any required component cannot be resolved. |
| Legacy savings history with no currency or deleted/edited goal | No attribution from today's settings without provenance; preserve stored history and expose unknown contribution basis. |
| PDF with multiple source currencies | Separate section totals/categories remain separate; combined deductible policy explicit; financial data never recomputed by formatting helpers. |

## User-run validation policy and next action

No validation is requested for a production change in M-0 because no production change was made. Document inventory consistency can be checked without executing a guard. The source classifications and proposed contracts still need independent money/architecture review.

For implementation slices, use `powershell.exe` 5.1 and `scripts/validation-runner.ps1` (or `scripts/vrun.ps1` with the mandatory correct `-Worktree` parameter). Test filters need literal `*Name*` wildcards, not markdown-derived underscores. Only one validation process at a time; poll an existing active run instead of restarting it. Do not invoke Gradle directly. Exact runner commands and registered guard IDs must be confirmed from the current runner/registry when a slice is handed off, not guessed from this inventory.

The first implementation slices, **M-1a receipt/source-currency** and **M-1b on-device category/tax producer boundaries**, are authored after local contract review. See their respective handoffs for contracts, open scalar/consumer boundaries, and user-run validation queues. M-1b adds 21 regressions without executing them. Neither slice updates the historical 83-finding report or supplies independent review approval. Radar and cash-flow normalization fixes should follow early as isolated M-4a slices; they need not wait for all analytics/forecast migrations. M-3d remains decision-gated on legacy contribution currency.

## Commit boundary and remaining gates

- This M-0 change is documentation only: this inventory, `money-m0-20261003-handoff.md`, and the M-0 status/link in the active plan.
- Do not stage unrelated `.codex` files, campaign prompt, verdict/report/coverage files, generated outputs, or line-ending-only ownership-policy dirtiness.
- No new money implementation, independent review, user-run validation, migration, or device gate is complete by virtue of this document.
- Wave 4's committed checkpoint and the separate raw-money track must not be described as a fully green refactoring program. Historical RP-20/RP-21 cleanup/test-debt planning uses a different roadmap than this M-0 through M-5 money workstream.
