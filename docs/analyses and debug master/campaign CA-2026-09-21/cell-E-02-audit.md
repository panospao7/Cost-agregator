# E-02 — Analytics shared surface (OWNER)

Provenance: 2026-09-22 | pinned commit `37601232b9778170c57a656a245b199ab6d7d965` | direct astra session | cell E-02 | AUDIT, static only.

Pin verification: `git rev-parse --short HEAD` returned `37601232`; `git diff --stat 37601232..HEAD -- app config scripts` empty; `git status --short -- app config scripts` empty. Production reads therefore reflect the pin.

Status: direct static discovery finished for the eight assigned primary files. Six new findings (P0: 0, P1: 0, P2: 6, P3: 0). No builds, tests, or guards executed. Findings await independent Phase 2 verification; this is not a GREEN implementation/review gate.

## Scope and governing intent

Read governing prompt §2 (known-debt and D1–D8 intended behavior) and §4 (all 15 defect classes, strict schema and severity). COVERAGE_MATRIX E-02 maps to Analytics and Budget Forecasting legal paths; segments Analytics & Insights, Dashboard Totals & Widgets, Forecasting & Runway, Budget Management. Exact sections and engine rows are read selectively.

## Coverage log

Full source-file reads: 9/20 (all eight assigned primary files plus CalculateFinancialForecastUseCase). Supporting repositories, DAOs, callers, tests and guards were read as bounded excerpts. Incremental evidence below was written during the audit.

## Findings

Finding IDs: CA-E-02-001 through CA-E-02-006. Coverage increments are retained with the findings to preserve the incremental audit trail.

### Coverage increment 1

- Fully read `domain/analytics/AnalyticsInputAssembler.kt` (1–191): repository fetch, spending/not-mine filters, category snapshot, normalization, included/excluded mapping, quality counters. Candidate: pre-fetched overload does not enforce the advertised period; reachability pending.
- Fully read `domain/analytics/AnalyticsCurrencyNormalizer.kt` (1–367): both entry adapters, identity conversion, transaction-date typed outcome, excluded rows, warning aggregation, provenance, snapshot mapping. No raw source-amount fallback; no broad cancellation catch; logging at 219–221 is counts only. Warning lists aggregate by warning type, while assembler counts warning objects (candidate quality-count mismatch).
- Fully read `domain/analytics/DailyBucketEngine.kt` (1–54): explicit half-open filtering, zero-filled days, local-zone calendar `plusDays(1)`; no IO or mutation.
- Fully read `domain/analytics/BudgetVsActualEngine.kt` (1–80): actuals normalized and ownership filtered; raw `budget.amount` and null-category lookup require caller checks.
- Fully read `domain/analytics/TotalsAggregationEngine.kt` (1–end, split reads): historical currency repository aggregation and partial flags on period rows; category output/averages/reactive triggers require follow-through. Cancellation rethrown at 444. No expense/event mutation.
- Extracted exact Analytics and Budget Forecasting legal paths, segments 1/2/8/10, inventory analytics lines, and named engine rows. Read D1–D8 decisions and relevant registry/RP-06/Wave-1 extracts; known-debt reconciliation remains in progress.

Full primary source files read: 5/20.

### Coverage increment 2

- Fully read `domain/analytics/AdvancedAnalyticsEngine.kt` (1–1243, three contiguous chunks): all canonical/self-fetching overloads, category/merchant cores, patterns, statistics, period/comparison helpers, sparklines, empty factories, histogram and percentiles. Canonical sums consume normalized effective amounts. Found live empty-result locale-currency dependency to reconcile. Raw budget comparison matches old `E2-CURRENT-009` and is not a new finding.
- Fully read `domain/analytics/InsightsEngine.kt` (1–865, two contiguous chunks): normalized and legacy entries, historical merge/dedup, parallel subengine branches, cancellation, fallback snapshots, legacy display, anomaly merge, recurrence inference, pace and all helpers. Historical time windows and warning reconstruction require caller checks. Public raw convenience paths overlap old `E2-CURRENT-013/014`; do not promote merely hypothetical mixed-currency use.
- Fully read `domain/logic/SynthesisEngine.kt` (1–978, three contiguous chunks): suspend typed conversion, confidence/quality adapter, forecast totals and chart projection, block-party day amounts, occurrence DAO read/status exclusion, legacy matcher, risk and insights. No DAO writes, lifecycle mutations, event writes, worker execution, or notifications. PAID/SKIPPED/CANCELLED rows suppress fallback and do not become future bill-day amounts. Conversion exceptions propagate cancellation; amount failures excluded and counted. Checking forecast/chart consistency against known debt.
- Production symbol traversal found AnalyticsViewModel normalized analytics calls; HomeViewModel live deprecated category call and totals `.first()` consumers; synthesis calls in ComputeDashboardWidgetsUseCase, CalculateFinancialForecastUseCase and FinancialWeatherRepository. Package-mate mutation/IO pattern scan completed (scoped `domain/analytics`).
- Consulted root `engine_2_analytical_engines_debug_report.yaml`: includes E2-CURRENT-001..020. Budget comparison, adapter quality losses, raw legacy APIs, self-fetching duplication and stale snapshot assembly already recorded there; suppress duplicates.

Full primary source files read: 8/20. Boundary files will be read selectively, with full-file reads counted separately.

### CA-E-02-001

ID: CA-E-02-001
Title: Overall budget actuals include only uncategorized purchases
Defect class: 11 (Data integrity — incorrect derived aggregate)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/analytics/BudgetVsActualEngine.kt`, `compute`, 36–40 and 49–70: spending is grouped by category; every budget reads `categorySpending[budget.categoryId]`. A null category denotes an overall budget, but here selects only null-category expenses. `data/repository/BudgetRepository.kt`, `getActiveBudgetSnapshots`, 111–121, preserves that null category. `ui/screens/analytics/AnalyticsViewModel.kt`, `buildBudgetVsActualItems`, 1136–1173, passes the overall snapshot through and displays its returned amount/percentage.
Impact path: Analytics screen → active overall budget → BudgetVsActualEngine → budget utilization chart. With a EUR 100 overall budget and EUR 150 categorized purchases, the item reports actualSpent=0 and 0% usage rather than 150 and 150%; it is labelled Unknown. No currency failure is needed. This affects the displayed overall row even when all conversions succeed.
Caller trace: `AnalyticsViewModel.computeAnalyticsInternal` 476 → `buildBudgetVsActualItems` 1154 → `BudgetVsActualEngine.compute`; `AnalyticsScreen.kt` 284 and `BudgetVsActualChart` 1037–1078 render returned spending and green utilization.
Existing tests/guards: `golden/HomeDashboardFinancialInvariantTest.kt` 86–122 and `golden/AnalyticsDashboardBudgetParityGoldenTest.kt` 83–110 use only non-null category budgets covering each expense category. They do not exercise the overall/null case. No dedicated BudgetVsActualEngine test file found. Static inspection only; no checks run.
Cross-cell impact: I-05 analytics UI; P-06 budget consumption. No budget/expense persistence is changed by this path.
Old-ID cross-refs: none. E2-CURRENT-009/010 concern FX comparison and quality propagation, not null overall-budget aggregation.

### CA-E-02-002

ID: CA-E-02-002
Title: Empty analytics ignores resolved home currency and can fail the whole screen on a countryless locale
Defect class: 12 (Error handling)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt`, `computeSpendingPatternsCore` 546–547 → `createEmptyPatternAnalysis` 610–623; `computeStatisticalInsightsCore` 683–684 → `createEmptyStatisticalInsights` 744–750. Both empty factories discard the supplied display currency and call `defaultDisplayCurrency` 1002–1004, which invokes `Currency.getInstance(Locale.getDefault()).currencyCode` and throws on an unsupported/countryless locale. The normalized overloads already possess `input.homeCurrency` (537, 674).
Impact path: Select a period with no purchases (also possible when all rows fail conversion), while home currency is valid → empty result factories consult unrelated locale → either zero-valued results have the wrong currency, or a language-only locale such as `en` causes an exception. `AnalyticsViewModel` launches both calls in `coroutineScope`; a failing child cancels that scope even though await sites catch ordinary exceptions. The outer compute catch emits the whole-screen “Analytics failed to load” state.
Caller trace: `AnalyticsViewModel.computeAnalyticsInternal` 573–592 → `getSpendingPatterns(currentInput)` / `getStatisticalInsights(currentInput)`; enclosing flow catch 320–335 maps computation failure to visible error. AdvancedAnalyticsDashboard is a separate implementation and is not claimed as a caller of these empty factories.
Existing tests/guards: `AdvancedAnalyticsEngineNormalizedTest.kt` 127–151 checks only empty collections, never currency or locale. `AdvancedAnalyticsEngineDeepTest.kt` 289–299 checks zero counts/means, not currency or countryless locales. No execution performed.
Cross-cell impact: I-05 analytics screen and empty-state UX; E-01 resolved-currency boundary.
Old-ID cross-refs: none. E2-CURRENT-017 is failed settings resolution falling back to EUR; this occurs after successful explicit currency resolution and is an empty-result locale dependency.

### Boundary evidence / candidate corrections

- `ExpenseRepository.kt` 768–784 → `ExpenseDao.kt` 209–218: uncapped SELECT with half-open date bounds and ownership filtering, no LIMIT truncation. Prefetched assembler callers at AnalyticsViewModel 359–398 and 574–580 fetch exactly their advertised windows; do not report missing assembler date filtering as a live defect.
- `BudgetRepository.getActiveBudgetSnapshots` 111–121 already converts to current home currency. The tentative claim that live callers pass raw foreign budget amounts is refuted by source. Historical/latest budget basis remains old E2-CURRENT-010 territory, not a new finding.
- `RecurringOccurrenceDao.getByDateRange` 26–27 is read-only and half-open; synthesis does not call DAO mutations.

### CA-E-02-003

ID: CA-E-02-003
Title: Aggregated warning objects are counted as affected transactions, limiting missing-rate confidence reduction to 2%
Defect class: 11 (Data integrity — quality metadata)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsCurrencyNormalizer.kt`, `accumulateWarning`, 235–246, keys only on `(type, message)` and accumulates expense IDs; `WarningAccumulator.toWarning`, 310–315, exposes the actual affected transaction count. `domain/analytics/AnalyticsInputAssembler.kt`, `buildNormalizedInput`, 145–159 and 166–179, instead uses list `.count { type == ... }` for missing/stale/invalid counts and derives the multiplier from that count. Because every missing-rate warning uses the same type/message (normalizer 154–165), missingRateCount can only be 0 or 1 irrespective of loss volume; minimum multiplier 0.8 is unreachable.
Impact path: Many foreign purchases cannot convert → normalizer correctly excludes N rows and creates one warning with affectedTransactionCount=N → canonical input reports missingRateCount=1 and confidenceMultiplier=0.98 → SpendingPersonalityClassifier produces an overstated confidence. For 100 input rows, 20 distinct missing-rate rows and 80 valid purchases, the existing formula yields 0.8×0.98−0.2=0.584 instead of 0.8×0.8−0.2=0.44 when applied to the 20 affected rows. ExcludedCount itself remains correct; this is not a claim of entirely missing partial-state warnings.
Caller trace: `AnalyticsViewModel.computeAnalyticsInternal` 392–395 builds allInput; 693–698 passes it to `SpendingPersonalityClassifier.classify`; classifier 627–645 consumes multiplier and penalty and returns profile confidence; ViewModel 727 stores that profile in screen state.
Existing tests/guards: `AnalyticsInputAssemblerProvenanceTest.kt` 125–197 covers one invalid-currency and one missing-rate row, but never multiple rows of the same warning class or the aggregate counter/multiplier. Normalizer test inventory inspected; no assembler counter-scaling assertion found. Tests/guards not run.
Cross-cell impact: I-05 personality confidence and canonical analytics quality consumers; P-05/P-06 must not assume these are per-transaction counts.
Old-ID cross-refs: none. A11/E2 audit notes already track downstream consumers ignoring quality; this is different upstream corruption even in the classifier that does consume it.

### Coverage increment 3

- Boundary extracts: `AnalyticsViewModel` 280–415, 465–500, 550–630, 684–741, 1120–1190; real fetch windows, warnings, budget conversion, advanced child coroutines, result mapping, personality consumer.
- `BudgetRepository` 109–122 and `ExpenseRepository` 768–784 traced to actual DAO SELECTs. `MultiCurrencyRepository` 610–744 traced each totals entry to uncapped expense reads and PURCHASE_ONLY / TRANSACTION_DATE normalization. Its sparse month/day/week groups are not calendar zero-filled; no separate new finding without distinguishing old baseline semantics.
- `ForecastInputAssembler` 391–535, 557–610, 685–753: ordinary assembly resolves currency fail-closed; read-only projections merge with materialized statuses by key; materialized read uses DatabaseReadBarrier; linked planned rows are removed before synthesis; normalized dashboard assembly supplies no confirmed occurrences. This is a supporting boundary review, not a full assembler audit.
- Fully read `CalculateFinancialForecastUseCase.kt` 1–81 (ninth full source file); `FinancialWeatherRepository` 1–150 read as caller/output boundary only. `ComputeDashboardWidgetsUseCase` 690–729 and 819–849 read for normalized synthesis/block-party entry contracts.
- Read relevant assertions in normalized/deep advanced analytics tests, assembler provenance tests and two real-engine budget parity goldens. No runtime result inferred.
- Baseline search expanded to archived Engine 2 audit/implementation/final-gate documents, current P-05/P-06 cell reports, remediation RP-07/RP-09 and RP-20-21 test-debt plan references. No candidate is promoted merely because it is absent from the supplied cell block.

### CA-E-02-004

ID: CA-E-02-004
Title: Forecast spending trajectory omits recurring obligations counted in the same forecast
Defect class: 11 (Data integrity — incomplete derived projection)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`, `synthesizeInternal`, 262–279 and 298–319 count recurring obligations; 332–348 subtract monthly recurring amounts from the discretionary baseline. However, 376–435 builds projected points exclusively from discretionary spend and MUST/LIKELY planned-expense maps, never adding recurring occurrences/patterns. The incomplete series is returned at 487–498 alongside totals which do include those obligations. `domain/forecasting/ForecastInputAssembler.kt`, `assemble`, 499–518 and 581–591, removes linked planned rows represented by confirmed occurrences, so those rows cannot repair the omission in the planned maps.
Impact path: A remaining EUR 100 recurring bill, EUR 200 cumulative actual spend, no other planned purchases, and a zero discretionary baseline produce totalCommitted=100 but a flat trajectory ending at 200, rather than 300. This applies to successful same-currency data; it does not require missing rates or fallback errors. In the live weather card, the committed metric and projected spending line disagree, understating the month-end outflow curve.
Caller trace: Home dashboard data → `DashboardDataProvider.getPlanningDataFlow` 54–60 / `getFinancialWeatherWithDefaults` 117–118 → `DashboardContractsAdapter.observeFinancialWeather` 103–104 → `FinancialWeatherRepository.getFinancialWeather` 40–67 → assembler → synthesis. Repository 100–105 maps both committed totals and projected points; `HomeScreen.kt` 695–705 forwards them; `FinancialWeatherCard.kt` 168–179 renders both metrics and ForecastTimeline. Also exposed by CalculateFinancialForecastUseCase 61–70.
Existing tests/guards: `SynthesisEngineGoldenTest.kt` 32–53 checks committed/likely/confidence for recurring patterns without asserting trajectory completeness. `SynthesisEngineRatePolicyPinTest.kt` 262–298 checks the last projection point only with recurringPatterns=emptyList and failed planned conversions. No recurrence-total/trajectory parity assertion found in the scoped synthesis tests. No tests run.
Cross-cell impact: P-05 dashboard financial weather; P-06 forecasting; I-05 chart consumption. Read-only calculation; no missing persisted expense is alleged.
Old-ID cross-refs: none. FRESH-P5-003 concerns the historical discretionary baseline; FRESH-P5-007/U-MONEY-01 concern conversion/blocking/fallbacks. This omission persists with a valid zero baseline and all successful identity conversions.

### CA-E-02-005

ID: CA-E-02-005
Title: Recurring insights relabel foreign manual-rule amounts as home currency without conversion
Defect class: 7 (Money / currency)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`, `findRecurringExpenses`, 752–778, calls the recurring engine and maps pattern.averageAmount directly while replacing currency with displayCurrency. `domain/logic/RecurringExpenseEngine.kt`, `getPatternsFromSnapshots`, 44–75, performs a fresh repository read and injects manual.amount/manual.currency unchanged into the returned patterns, even when input expense snapshots are fully normalized. `InsightsEngine.getLegacyInsights`, 496–507, then formats this unchanged numeric amount with homeCurrency.
Impact path: Valid EUR analytics input plus an active USD 100 monthly manual rule → recurring pattern retains USD 100 → InsightsEngine publishes EUR 100. With a 0.90 EUR/USD conversion the amount should be EUR 90 (or remain explicitly USD 100); no converter runs, no exclusion/quality warning is generated, and a missing rate cannot fail closed. Manual patterns have confidence=1, placing them ahead of detected patterns, so the first-three insight limit does not prevent the defect.
Caller trace: `AnalyticsViewModel.computeAnalyticsInternal` 479–488 → normalized `InsightsEngine.generateInsights` → `generateInsightsForPeriods` recurring branch 318–319 → `findRecurringExpenses` → RecurringExpenseEngine → `RecurringExpenseRepository.getAll` 67 → `ManualRecurringExpenseDao.getAllActive` 37–38. Returned snapshot → getLegacyInsights → screen state 710 → `AnalyticsScreen.kt` 216–217 / 288–290, with description rendering at 896/1201.
Existing tests/guards: `InsightsEngineValidationTest.kt` 105–106 stubs getPatternsFromSnapshots to emptyList; the scoped InsightsEngine test family uses relaxed recurring-engine mocks. No foreign manual rule → displayed recurring insight assertion found. Normalizing the input is insufficient to protect the separate manual-rule read. No tests or guards run.
Cross-cell impact: I-05 analytics UI; P-04 manual recurring source; E-01 conversion boundary. Stored rules remain correct; the analytics adapter loses the currency.
Old-ID cross-refs: none. Related U-MONEY-01 was explicitly narrowed to SynthesisEngine conversion-failure fallbacks in the registry; E2-CURRENT-013 tracks default EUR legacy entry points. This live normalized-input path explicitly passes EUR and independently injects foreign manual-rule amounts afterward.

### CA-E-02-006

ID: CA-E-02-006
Title: Analytics error handlers send unsanitized exceptions and stack traces to debug logcat
Defect class: 9 (Privacy)
Severity: P2
Evidence: `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt`, `getAverageForPeriodType`, 443–446; `reactiveFlow`, 670–673; `reactiveCategoryBreakdownFlow`, 691–694, pass the complete caught Throwable to Timber.e. Same error shape occurs in `domain/analytics/InsightsEngine.kt`, `generateInsightsForPeriods`, 297–326, and `domain/logic/SynthesisEngine.kt`, internal `synthesize`, 182–187. `startup/AppStartupCoordinator.kt`, `configureDebugTools`, 636–640, plants an ordinary Timber.DebugTree without a sanitizer when DEBUG is true.
Impact path: A database/conversion/subengine exception reaches a live analytics catch → Timber formats the throwable, including stack and original message/cause → debug logcat. This violates the bounded exception-class/controlled-reason rule; SQL/provider/path details are exposed if present in that exception. Scope is the debug build's local logcat. No release tree, remote upload, or persistent diagnostic-table leak is asserted; P2 reflects that restricted reach.
Caller trace: `HomeViewModel` totals consumers (609, 643–655, 817/870) → TotalsAggregationEngine → MultiCurrencyRepository/ExpenseRepository → DAO read → throwable catch; AnalyticsViewModel 479–488 and the weather repository 67 reach the other named catches. Source-tree search found the debug-only plant at AppStartupCoordinator 640 and no release plant.
Existing tests/guards: Scoped analytics tests and privacy/log architecture-guard symbol searches did not identify assertions that capture/sanitize these thrown-exception logs. Cancellation guards protect cancellation propagation, not message/stack privacy. No checks run.
Cross-cell impact: P-05 dashboard error diagnostics, I-05 analytics UI, P-08 privacy/logging. E-01 also observes a different raw financial-amount log site in CurrencyConverter.
Old-ID cross-refs: none. CA-E-01-006 is a separate converter log that interpolates monetary values; cluster at verification if remediation shares a sink. AppStartupCoordinator 623–633 documents debug PII logging as intentional, which explains the reach restriction, but is not a D1–D8/accepted-debt authorization to override this audit's explicit no-stack-trace rule. No matching suppression was found in the consulted registry/Engine 2/remediation baselines.

## All 15 defect classes

| Class | Static checks and disposition |
|---|---|
| 1 Legal path | All eight primary files read. No expense/rule/receipt/forecast writes in these engines. Canonical analytics uses assembler; Totals uses historical money repository. HomeViewModel's deprecated category self-fetch is explicitly allowlisted by DeprecatedApiArchitectureGuardTest 130–150 and overlaps E2-CURRENT-008; not a new discovery. |
| 2 Barriers | Traced SELECTs in ExpenseDao, ManualRecurringExpenseDao and RecurringOccurrenceDao. No primary-file writes to bypass a write/restore barrier. ForecastInputAssembler's materialized occurrence read checks DatabaseReadBarrier 469; Synthesis block-party independently reads occurrences at 816 without a local read check. Did not infer a P0 write bypass from a read-only engine; comprehensive restore read coherence remains a P-07 boundary concern. |
| 3 Atomicity / TOCTOU | Primary computation is in-memory, but assembler/category/budget reads are separate snapshots; prior E2-CURRENT-018 already owns non-atomic analytics assembly. No mutation/event atomicity to test here. No new write race found. |
| 4 Idempotency / duplicates | No insert/retry path. Insights merges snapshots by id (191) and anomalies by expense id (716–739). Forecast assembler merges occurrence keys (491–503), dedups linked plans (515–518); synthesis filters non-PLANNED plans and suppresses PAID/SKIPPED/CANCELLED occurrence fallback (829–830). Projection omissions are 004, not fabricated duplicate financial records. |
| 5 Cancellation | Normalizer/assembler do not catch conversion exceptions. Totals catches rethrow cancellation (444/671/692); Insights async branches and recurring suppression do so; Synthesis does so at 185. Advanced resolveHomeCurrency runCatching 997–999 has no call found and is already MIT-034/G-CANCEL-02 baseline debt. Ordinary child failure in empty analytics is 002. |
| 6 Side-effect timing | No primary engine event writer, enqueue, notification or DAO mutation found. RecurringExpenseEngine helper performs a SELECT, then returns patterns; no hidden mutation on that followed branch. Therefore no new commit-before-notify or event-order finding; helper rate storage internals belong to E-01. |
| 7 Money / currency | Historical normalizer passes transaction date and no-latest stale policy; Totals historical family uses per-row normalization; BudgetRepository normalizes snapshots; failed conversions exclude source amounts. New live post-normalization currency loss is 005; empty-state label defect is 002. Intentional forecast historical/latest basis mix and RP-06 accepted residuals not restated. |
| 8 Time | DailyBucketEngine filters `[start,end)` and uses calendar plusDays. Advanced range helper is nonrecursive; year/month/week/quarter and comparative windows read. Synthesis uses injected now and local-zone fields. Totals computeFromNormalized has fixed 24-hour day ends but zero production callers found; do not report hypothetical runtime DST impact. Legacy Insights current-window helper issues overlap E2-CURRENT-013/014. |
| 9 Privacy | Normalizer conversion warnings log counts only; Synthesis conversion-failure log uses failure enum/counts. No network/cloud/payload persistence in primaries. Followed logging sink and recorded bounded debug-only exception disclosure as 006. No release privacy-leak claim. |
| 10 Worker hygiene | None of the eight files implements or schedules a worker, posts notifications, records worker success, or catches worker timeout. No WorkerExecutionGuard adoption obligation arises in these read-only engines; broader worker consumers not re-audited. |
| 11 Data integrity | Verified uncapped DAO range query, ownership/purchase filters, normalized snapshot fields, completeness of budget actuals and forecast curve, and quality aggregation. Findings 001, 003, 004. No schema/migration or persistent-row mutation in cell. |
| 12 Errors | Traced invalid currency, failed conversion, empty input, catch-to-zero/empty, and parallel child exceptions. Empty factory failure is 002. Totals inconsistent unavailable behavior is existing FRESH-P5-009; do not re-report. Synthesis confidence-zero fallback and per-branch Insights fallbacks were inspected, with no claim they are typed unavailable results. |
| 13 Wiring / dead code | Followed actual AnalyticsViewModel/HomeViewModel/weather/dashboard calls through adapters. Production search found no calls to Totals.computeFromNormalized/summarize or Insights.buildDailyTotals/getSpendingPaceSuspend. Deprecated Advanced merchant/pattern/statistics self-fetch overloads have no live callers found; canonical overloads are live. Existing legacy API debt is not promoted to runtime severity. |
| 14 Test correctness | Inspected assertion bodies and fixtures supporting each finding. Category-only budget goldens omit overall scope; empty tests omit locale/currency; warning fixtures omit multiplicity; projection test omits recurring input; recurring insights mocks return empty patterns. No tautology-only standalone finding or claimed execution. RP-21 suite/hang debt remains tracked separately. |
| 15 Fix-regression | Scoped git history since 2026-09-07 for all eight files. Read 686b3256 conversion/CE/projection-fallback hunks, f23f4043 per-day failure propagation diff, c92c4709 completed-history baseline diff, 68fda8c7 analytics recursion removal diff, and 9101aab3 synthesis documentation diff. No new issue is asserted to be introduced by those fixes without proof. RP-06 known quality/UI residuals and RP-07 daily include-current exception retained as intended/deferred. |

## Known-debt and intended-behavior reconciliation

Read registry relevant Pipeline 5/6/U-MONEY entries; RP-00 D1–D8; RP-06/07/09 extracts and recorded deferrals; RP-20-21 test triage; WAVE-1-STATUS feed extracts; root Engine 2 YAML and targeted archived Engine 2 audit/plan/review sections. No old finding was given a new ID. In particular: FRESH-P5-009 unavailable fallbacks, E2-CURRENT-008 self-fetch debt, E2-CURRENT-010 budget FX/quality, E2-CURRENT-013/014 legacy input/window APIs, E2-CURRENT-018 snapshot assembly, MIT-034 cancellation baseline, and U-MONEY-01 conversion-fallback history were treated as baseline context only. RP-06 intentionally mixes transaction-date historical spend and latest future obligations; month-level block-party failures remain log-only by recorded design. Candidate raw foreign budget limits was refuted by BudgetRepository 111–121. Existing CA-P-05/P-06 and newly available CA-E-01 reports were checked for cross-cell overlap; E-01-005's MoneyAggregate denominator defect is distinct from 003's AnalyticsInputAssembler warning-type count.

## Completion and limitations

All eight assigned primary files read end-to-end; nine full source files total, below the 20-file cap. No context compaction occurred. Supporting files were bounded caller/DAO/event/side-effect/test extracts, not full audits of those other cells. Pure helper algorithms and every one of the normalizer's many downstream consumers were not exhaustively read; currency provider storage/network implementation, Room migration internals, worker pipelines and UI composition outside demonstrated consumers remain with their owners. COVERAGE_MATRIX orphan notes for natural-language search/carbon were not treated as extra primary-file scope; this report covers the user-specified eight-file E-02 owner block.

No production/config/script edits, builds, tests, lint, guards or runtime repros. Counterexamples are static constructions. No independent verifier/guardian gate performed; six findings await Phase 2 adversarial verification. Report and one JOURNAL append are the only files written by this session.

Final pin check: full HEAD `37601232b9778170c57a656a245b199ab6d7d965`, short HEAD `37601232`; required committed production diff and production working-tree status empty.

Provenance: direct astra session; cell E-02; environment date 2026-09-22; tool-observed completion UTC timestamp 2026-09-21T21:33:56Z (environment/tool date discrepancy recorded explicitly); pinned commit `37601232b9778170c57a656a245b199ab6d7d965`; agents invoked: none, per direct-auditor request. Discovery only; independent verification pending. JOURNAL entry appended once with this timestamp and six findings.
