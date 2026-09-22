# P-06 â€” Budget, forecast, cash flow â€” static audit

## Provenance
- Date: 2026-09-22.
- Pinned source: `37601232b9778170c57a656a245b199ab6d7d965`.
- Auditor: **direct astra session**; cell: **P-06**; mode: AUDIT, static only.
- Pin verification: `git rev-parse --short HEAD` returned `37601232`; `git diff --stat 37601232..HEAD -- app config scripts` returned no output. Working-tree drift is documentation/agent configuration only.
- Governing prompt Â§2 and Â§4 read. Known FRESH-P6-001..015 excluded from new findings. Shared SynthesisEngine ownership is E-02; this audit covers its P-06 use.
- No builds/tests/guards executed; no production edits. Findings are discovery candidates, not independently verified.
- Status: complete for the static audit scope below; independent verification remains pending.

## Scope and intended contracts
- Coverage matrix P-06 row: Forecasting & Runway, Budget Management, Cash Flow Planning, Analytics & Insights, Currency & Exchange.
- Legal paths: Budget Forecasting; Money / Currency; Analytics; supporting Recurring Plan Projection.
- Planned primary coverage: BudgetRepository; BudgetAutopilotEngine; BudgetForecastingEngine; MonteCarloSpendingSimulator; FinancialStressForecastEngine; NetCashflowBalanceProvider; SynthesisEngine usage; relevant DAOs, callers, and tests.
- All 15 defect classes will be evaluated; evidence and exclusions recorded below. Full-file read budget: at most 20.

## Coverage log
- Read governing prompt Â§2 (known debt/intended behavior) and Â§4 (15 classes/schema/severity); P-06 matrix row and shared ownership rows.

### Coverage checkpoint 1
- Read scoped LEGAL_PATHS sections, CODEBASE_SEGMENTS 1/2/8/13/16, inventory budget/forecast entries, and exact engine rows. No large map loaded wholesale.
- Read registry P-06 and P-05 crossovers, D1–D8 decisions, RP-08/RP-09 relevant status/contracts, RP-06 deferral pointers. FRESH-P6-011..015 were not found by repository docs search; no invented descriptions assigned to them.
- Fully read `data/repository/BudgetRepository.kt` (1–1001; recovered truncated middle in separate reads): status/rollover, conversion, suggestions, CRUD, diagnostics, notification marks. Checked finite amount validation, CE propagation, active-scope transaction delegates, post-write diagnostics, partial/unavailable FX handling.
- Fully read `data/database/dao/BudgetDao.kt` (1–272): ABORT insert, transactional scope switching, missing-row update behavior, materialized keys, notification marks, bulk deletes.
- Fully read `domain/budget/BudgetAutopilotEngine.kt` (1–522): typed history read (no reflection), complete-month gating, period normalization, caps, hierarchy scaling, summary, quality model. Candidate unit mismatch and post-cap scaling require caller/debt checks.
- Fully read `domain/budget/BudgetForecastingEngine.kt` (1–713): PERIOD_END limit/spend, historical normalization, exclusion confidence, forecast insert/deactivation, constraint classification, diagnostic side effects, risk/probability math.
- Fully read `domain/forecasting/MonteCarloSpendingSimulator.kt` (1–305): fixed seed, percentile interpolation, recurring subtraction, deterministic degradation, day count and confidence recency.
- Fully read `domain/forecasting/NetCashflowBalanceProvider.kt` (1–29): 90-day aggregate reads, ignored currency parameter, runCatching/default-zero and clamp; tracing caller and debt next.
- FinancialStressForecastEngine was read through its recurring, income, Monte Carlo, error, and result paths (1–880).

## Findings

### CA-P-06-001 | Autopilot compares home-currency history with source-currency budgets and writes the result unchanged | defect class 7 | severity P2
- Evidence at pin: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt`, `generateRecommendations`, 98-112, 161-189, 208-221; `getHistoricalSpendForBudget`, 303-307. `data/repository/MultiCurrencyRepository.kt`, `getHistoricalCategoryMonthlySpend`, 783-825, resolves home currency and normalizes to it. `ui/screens/budget/BudgetViewModel.kt`, `applyAutopilotRecommendation`, 321-329, and `applyAllAutopilotRecommendations`, 376-385, retain the persisted budget currency while replacing its amount.
- Impact path: Budget screen Analyze -> normalized monthly history -> cap against unconverted `budget.amount` -> Apply -> BudgetRepository -> BudgetDao.updateAndEnforceActiveScope. Example: a USD 100 budget with stable EUR 90 history and USD/EUR=0.9 should stay USD 100; the engine recommends 90 and Apply saves USD 90. Larger unit differences systematically hit the wrong cap. This is actual budget configuration mutation, not merely formatting; P2 reflects bounded user-confirmed adjustments, not transaction-ledger corruption.
- Caller trace: `BudgetScreen.kt:247-250` / `BudgetViewModel.generateAutopilotRecommendations:285-291`; apply methods above; repository `updateBudget:689-710` / `updateBudgetOrThrow:740-748`; DAO `updateAndEnforceActiveScope:101-126`.
- Existing tests/guards: `BudgetAutopilotEngineTest` has complete/partial history, cap and scope tests, but its aggregate helper uses EUR and budget helper defaults to EUR; no differing budget/home-currency application assertion found. Money/Currency legal-path contract forbids sums/comparisons with lost currency context. Tests/guards NOT RUN.
- Cross-cell impact: E-01 typed money boundary; budget UI consumers. Repository normalization itself is correct; P-06 loses its currency.
- Old-ID cross-refs: none for this mismatch. FRESH-P6-003 concerned raw mixed-currency expense history/reflection and is not restated; that path has been replaced.

### CA-P-06-002 | Hierarchy scaling runs after the 15% clamp and can apply much larger cuts | defect class 7 | severity P2
- Evidence at pin: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt`, `generateRecommendations`, 180-185 then 227-244. The scaled copy keeps `isActionable=true` and is never clamped again. `BudgetViewModel.kt`, apply paths 310-329 and 363-385, accept that amount.
- Impact path: Analyze with an overall budget and category budgets -> individually bounded recommendations -> proportional hierarchy scaling -> Apply/Apply All -> persisted large category reductions. Same-currency, same-period example: overall recommendation 100; two category recommendations 100 each, with current amounts 100 each. Scaling returns 50 per category (minus 50%), although both first-pass recommendations obey the cap. `BudgetDao` supports simultaneous overall and per-category budgets (activation only deactivates others in the same scope, 64-94).
- Caller trace: BudgetScreen -> BudgetViewModel.generateAutopilotRecommendations -> engine; BudgetViewModel.applyAllAutopilotRecommendations -> BudgetRepository.updateBudgetOrThrow -> BudgetDao.updateAndEnforceActiveScope.
- Existing tests/guards: `BudgetAutopilotEngineTest` tests the positive 15% cap for an individual budget (348 onward); no post-hierarchy lower-bound assertion found. RP-08 explicitly preserves +/-15% safety bounds (plan 163), and engine KDoc promises them (21-25). Tests/guards NOT RUN.
- Cross-cell impact: Budget UI and persisted category configuration; no expense mutation.
- Old-ID cross-refs: none. The existing BUD-5 hierarchy code implements a different constraint; the collision with the safety cap is the new issue.

### CA-P-06-003 | Forecast failure text is exposed directly to the budget UI | defect class 9 | severity P2
- Evidence at pin: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt`, `generateForecastResult`, 99-116 and 131-149, constructs `BudgetForecastResult.Unavailable.reason` with `${resolution.reason}` or `${outcome.message}`. `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt`, `generateForecast`, 77-80, assigns `result.reason` directly to UI error state; its broad catch at 120-123 also assigns `${e.message}` directly.
- Impact path: budget forecast request -> home-currency/conversion failure -> raw resolution/converter/exception detail -> `BudgetForecastUiState.error` -> BudgetForecastingScreen error rendering. Exception text can contain SQL/provider/path details prohibited by the privacy policy; no sanitizer or controlled reason-only mapping sits between engine and UI.
- Caller trace: `BudgetForecastingScreen` invokes `BudgetForecastingViewModel.generateForecast` (route contract in `LEGAL_PATHS.md`); ViewModel -> `BudgetForecastingEngine.generateForecastResult`; engine uses `CurrencySettingsRepository`/`CurrencyConverter`.
- Existing tests/guards: `BudgetForecastingViewModelTest` covers unavailable/error state transitions but no assertion that error text is controlled/redacted. `SENSITIVE_DIAGNOSTICS_POLICY.md` and G-PII-01 prohibit raw `e.message`; no production guard was run.
- Cross-cell impact: privacy/security diagnostics policy; budget UI only, no DB mutation. This is separate from autopilot sanitized constants in `BudgetViewModel`.
- Old-ID cross-refs: none found; FRESH-P6-002/003 concern autopilot history/reflection and are not this UI error path.

### CA-P-06-004 | Synthesis fallback logs the full exception/stack trace | defect class 9 | severity P2
- Evidence at pin: `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt`, `synthesize` overload, 180-207, catches every non-cancellation exception and calls `Timber.e(e, "Error in synthesize")` at 187. Passing `e` logs the throwable/stack trace; the code does not reduce it to a controlled class/reason code. The same catch returns a zeroed fallback forecast, so this is on the production fallback path.
- Impact path: any synthesis failure in dashboard/weather or financial forecast flow -> Timber receives exception and stack trace (which may contain SQL/path/user-data details under downstream implementations) -> logging backend. Privacy rules prohibit persisting/logging stack traces; the fallback also masks the specific failure from callers.
- Caller trace: `FinancialWeatherRepository.getFinancialWeather`, 57-67 -> `SynthesisEngine.synthesize`; `CalculateFinancialForecastUseCase.synthesizeForecast`, 61-70 -> same; dashboard use case calls synthesis through its forecast path.
- Existing tests/guards: cancellation guard covers CE rethrow, but no privacy assertion or sanitized logging wrapper for this catch was found. `EventMetadataSanitizer` cannot sanitize a throwable passed to Timber. Tests/guards NOT RUN.
- Cross-cell impact: E-02 SynthesisEngine owner and P-05 dashboard consumers; no direct write.
- Old-ID cross-refs: none found. P-05â€™s audit records other dashboard error paths but no SynthesisEngine stack-trace finding; no known P-06 ID covers this.

### CA-P-06-005 | Stress forecast converts calculation failures into a fabricated MODERATE result | defect class 12 | severity P2
- Evidence at pin: `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt`, `computeStressForecast`, 175-203, catches all non-cancellation exceptions, logs them, then returns `StressForecastResult` with three zero-valued horizons, `overallRiskLevel = MODERATE`, `fallbackCrunchProbability = 0.20`, `mode = ESTIMATED_INDEX`, and recommendations claiming degraded estimate. No `isPartial`, controlled failure code, or unavailable result is set on this branch.
- Impact path: database/read/conversion/recurrence failure -> dashboard stress widget receives synthetic 20%/MODERATE risk and zero balances -> user sees a substantive risk assessment instead of unavailable/error; downstream overall-risk ordering can treat this as a real moderate warning. This is separate from the tracked currency-blind numeric fallbacks FRESH-P6-009 and the tracked recurrence fallback FRESH-P6-001/008.
- Caller trace: `ComputeDashboardWidgetsUseCase.computeStressForecast`, 1151-1157 -> `FinancialStressForecastEngine.computeStressForecast`; result is added as `DashboardWidget.FinancialStressForecast` at 1187-1189.
- Existing tests/guards: `FinancialStressForecastEngineTest` explicitly asserts the non-low degraded fallback (test around 192), thereby codifying the fabricated result; no unavailable/error-state contract test was found. Cancellation baseline allowlists broad catches for this file (MIT-034), but that does not justify a misleading success result.
- Cross-cell impact: dashboard stress widget and E-01/E-02 normalized data sources; no mutation.
- Old-ID cross-refs: MIT-034 covers cancellation catch allowlisting only; FRESH-P6-009 covers currency-blind fallback amounts only; neither covers exception-to-MODERATE conversion.

## Defect-class coverage
- 1 Legal path: checked BudgetRepository/BudgetForecastingEngine DAO ownership and callers; forecast writes funnel through `insertForecast`/`BudgetForecastDao.insertWithDeactivation`; no new bypass found.
- 2 Barrier: checked budget CRUD, forecast generation/insert, diagnostics, and restore snapshot paths; write barriers precede writes. Stress reads use DatabaseReadBarrier for materialized occurrences; no new barrier gap found.
- 3 Atomicity/TOCTOU: checked BudgetDao transactional active-scope helpers, BudgetForecastDao deactivation+insert transaction, BudgetRepository delete transaction, and Apply All transaction; no new finding.
- 4 Idempotency: checked forecast unique index/ABORT classification and recurring occurrence merge-by-key; no new finding beyond excluded known IDs.
- 5 Cancellation: checked catches in audited files; CE is rethrown in the main budget/stress paths. Allowlisted broad catches remain known MIT-034 and are not restated.
- 6 Side-effect timing: checked forecast persistence before completion diagnostic and budget CRUD diagnostics after DAO success; no new ordering defect.
- 7 Money/currency: CA-P-06-001 and CA-P-06-002; also checked PERIOD_END forecast basis, typed repository normalization, Monte Carlo display currency, and SynthesisEngine conversion paths. Tracked FRESH-P6-003/007/009 excluded.
- 8 Time: checked period windows, half-open ranges, calendar month/week/day calculations, elapsed forecast end, and 90-day provider window; no new finding.
- 9 Privacy: CA-P-06-003 and CA-P-06-004; checked SafeEventMetadata sanitizer and diagnostic metadata. Raw UI reason and throwable logging remain outside that sanitizer.
- 10 Worker hygiene: no workers in this cell; no new worker finding.
- 11 Data integrity: checked budget active-scope materialized keys, forecast FK/delete, and forecast history deactivation; no new orphan/data-loss finding.
- 12 Error handling: CA-P-06-005; checked typed unavailable paths and repository `Result.Error` handling.
- 13 Wiring/dead code: traced BudgetAutopilot, BudgetForecasting, Monte Carlo, stress, NetCashflowBalanceProvider, SynthesisEngine, and dashboard callers; no zero-caller production path requiring a new finding.
- 14 Test correctness: identified the stress degraded-fallback assertion as existing coverage of the misleading contract in CA-P-06-005; no separate tautology finding.
- 15 Fix-regression: compared current code against the pinned remediation contracts and known IDs; no additional diff-scoped regression established.

## Completion and limitations
- Static audit complete for the listed P-06 primary files and traced production callers, within the 20-full-file/approximately-150K context budget. No builds, tests, lint, or guards were run.
- Package mates were searched for mutation/IO patterns and read when they contained relevant paths; UI rendering was traced only to the relevant ViewModel/dashboard call sites.
- Known FRESH-P6-001..015 and other explicitly cross-referenced debt (MIT-034, U-MONEY-01, P6-P1-13) were not restated. Findings above are new discovery candidates and require independent verification.

## Final provenance
- Agents invoked: none; direct astra session as requested.
- Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`.
- Timestamp: 2026-09-22 (America/New_York).





