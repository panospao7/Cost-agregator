# P-05 — Dashboard and synthesis static audit

Date: 2026-09-21
Pinned source commit: `37601232b9778170c57a656a245b199ab6d7d965`
Auditor: direct astra session
Cell: P-05
Mode: AUDIT; static only; no builds or tests executed.
Status: PARTIAL - 4 new P2 discovery findings; 20 full-file read ceiling reached. Independent campaign verification pending.

## Provenance and scope

- `git rev-parse --short HEAD` returned `37601232`; `git diff --stat 37601232..HEAD -- app config scripts` was empty. Initial status showed only documentation and agent-configuration drift.
- Read governing prompt sections 2 and 4 before code. All 15 defect classes apply. Known debt and intended decisions are exclusions, not new findings.
- Read P-05 coverage matrix row: Dashboard Totals & Widgets; Analytics & Insights; Currency & Exchange; Utilities & Shared Helpers. Legal-path headings: Analytics and Money / Currency. Shared internals belong to E-01/E-02; this audit assesses their P-05 usage.
- Read exact legal-path sections, segments 8 and 10, and inventory matches. Legal intent requires normalized inputs and preservation of currency quality through UI adapters.
- Read FRESH-P5-001..014 registry entries and additional P5/U-MONEY-01 references; these will not be re-reported. Read D1–D8 decisions and relevant remediation/Wave-1 excerpts.

## Coverage ledger

Initial coverage queue (completion recorded in increments below): TotalsAggregationEngine; ComputeDashboardWidgetsUseCase; DashboardDataProvider; DashboardContractsAdapter; DashboardRepository; HomeViewModel; SynthesisEngine; AnalyticsInputAssembler (usage); money/quality display mapping. Read selected reachable DAO, converter, event/side-effect, tests, and guard sections as needed.

Coverage matrix additionally assigns lifestyle/challenges to P-05; inspect IO/mutation hits and record any remaining coverage limit explicitly.

Full-file read budget: at most 20. Primary files read fully; shared/package peers inspected by targeted excerpts and mutation/IO searches.

## Findings

Four findings recorded incrementally below: CA-P-05-001 through CA-P-05-004.

## Class-by-class assessment

See the final 15-class assessment below.

## Completion and limitations

PARTIAL audit. See final outcome and uncovered work below. No runtime validation performed; no production edits.

## Closing provenance

Agents invoked: none (user requested direct cell audit).
Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`.
Session: direct astra session; 2026-09-21.

### Coverage increment 1

Fully read production files (5 so far): `domain/analytics/TotalsAggregationEngine.kt` (699 lines), `data/repository/DashboardContractsAdapter.kt` (219), `domain/usecase/dashboard/DashboardDataProvider.kt` (175), `data/repository/DashboardRepository.kt` (112), and `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` (1436). Paths relative to `app/src/main/java/com/yourname/expensetracker/`.

Checked historical conversion API choices; half-open current/previous/historical slices; shared deposit exclusion; six-month fetch; pseudo-category percentages; canonical pace wiring; cancellation catches; preference persistence; reactive triggers; forecast assembly; block-party mapping; health/savings side-effect entry points. Known P5 debt is not counted as new. Follow-ups under investigation: month-clipped weekly aggregate; block-party loss of currency-quality metadata; fail-open budget summary; swallowed Monte Carlo cancellation. No finding finalized before tracing callers and debt exclusions.

### CA-P-05-001 | Week summary loses purchases before the first of the month | defect class 8 | severity P2

- **ID:** CA-P-05-001
- **Title:** Week summary is truncated to the current month when a calendar week crosses a month boundary.
- **Defect class:** 8 (Time correctness); related class 15 (fix-regression).
- **Severity:** P2 (incorrect derived display; no persistent money mutation).
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `produceDashboardNormalizedInput`, lines 383–413: `purchases` comes from `currentPeriodExpenses` (date >= month start); `weekAggregate` filters that already-truncated list. `buildContext`, lines 514–526, passes monthStart/now. `assembleWidgets`, lines 1278–1283, emits that value as `weekSpent`. `app/src/main/java/com/yourname/expensetracker/ui/screens/home/HomeScreen.kt`, PeriodSummary branch, lines 583–603, displays it under the Week label.
- **Impact path:** Home dashboard ? six-month expense fetch ? current-month prefilter ? weekly filter ? Week total omits same-week purchases in the previous month. Example: Wednesday April 1, 2026, with a Monday March 30 purchase of 100 and April 1 purchase of 20: weekly display is 20 rather than 120. Both rows are present in the adapter window; this is not missing source history.
- **Caller trace:** `HomeViewModel.processedDataFlow` lines 242–249 ? `DashboardDataProvider.getProcessedDataFlow` ? `ComputeDashboardWidgetsUseCase.compute`/`buildContext`; fetch is `DashboardContractsAdapter.observeDashboardExpenses` lines 54–68 ? `ExpenseRepository.getExpensesWithCategoryInPeriod` lines 172–173 ? `ExpenseDao.getExpensesWithCategoryInPeriodFlow` lines 176–178 (half-open SELECT).
- **Existing tests/guards:** Static inspection of `ComputeDashboardNormalizedInputWindowTest.kt` lines 99–150 found current/previous-month and exclusive-end assertions, but no cross-month weekly-total assertion. No checks executed. Time-boundary guards cannot establish the correct input set merely from calendar-safe helper usage.
- **Cross-cell impact:** P-05 PeriodSummary; E-01 owns time helpers, whose correct week start cannot recover rows removed by this caller.
- **Old-ID cross-refs:** FRESH-P5-001 / RP-05 is the related current-month slicing fix, not the same defect: categories/income/MTD should be month-scoped; the independent Week KPI should not. No existing ID found for this cross-month weekly omission. RP-05 wording also intersects week with month; no D1–D8 or accepted-debt decision relabels Week as month-clipped.

### Coverage increment 2

Fully read `ui/screens/home/HomeViewModel.kt` (886), `domain/logic/SynthesisEngine.kt` (978), `domain/analytics/AnalyticsInputAssembler.kt` (191), and `data/database/dao/PlannedExpenseDao.kt` (176). Full-file count now 9.

Read targeted `ExpenseRepository`/`ExpenseDao` SELECT delegation, `ForecastInputAssembler.assembleNormalized` (685–746), PlannedExpense status contract (13–29), and HomeScreen PeriodSummary rendering (583–603). Checked synthesis occurrence lookup, confirmed/legacy branches, planned status filter, repeated conversion and exclusion metadata, confidence/risk calculation, and no writes inside synthesis itself.

Debt exclusions discovered during tracing: `BlockPartyDay.conversionFailureCount` having no UI reader is explicitly documented in RP-06 lines 661–663, so not new. Dashboard planned-status loss overlaps the existing closed/skipped-plans-enter-forecast issue P6-P1-09, and is held out of new findings pending identity reconciliation. Totals normalized-input DST arithmetic/dead week helper already have NEW-P5-2026-001/002. These are not counted.

### CA-P-05-002 | Lifestyle growth is calculated backwards in production | defect class 8 | severity P2

- **ID:** CA-P-05-002
- **Title:** Newest-first DAO order reverses lifestyle income/spending growth and suppresses the savings prompt.
- **Defect class:** 8 (Time correctness); related class 14 (fixture fails to reproduce production ordering).
- **Severity:** P2.
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`, `getExpensesBetweenFlowUncapped`, lines 230–231 orders rows `date DESC`. `app/src/main/java/com/yourname/expensetracker/domain/lifestyle/LifestyleInflationDetector.kt`, `analyzeLifestyleInflation`, lines 46–89 inserts month totals into insertion-ordered maps in that order, then passes unsorted values to `calculateTrend`; lines 234–240 calculate `(last - first) / first`. Its report at lines 100–102 therefore treats the oldest month as the endpoint. `LifestyleSavingsPromptUseCase.kt`, `evaluateAndPrompt`, lines 53–57 rejects the resulting negative inflation rate.
- **Impact path:** Jan spending 500/income 1000 ? Mar spending 900/income 1200 should produce spending growth +80%, income +20%, inflation +60 percentage points. DAO order Mar?Jan instead yields -44.44%, -16.67%, inflation -27.78 percentage points. The lifestyle overview labels growing expenditure as deflation and the dashboard savings nudge exits before recommendation creation.
- **Caller trace:** `LifestyleInflationScreen` lines 99–107 ? `LifestyleInflationViewModel.analyze` lines 40–51 ? detector ? DAO. Independently `HomeViewModel.processedDataFlow` ? `ComputeDashboardWidgetsUseCase.compute` lines 337/1102–1105 ? `LifestyleSavingsPromptUseCase.evaluateAndPrompt` ? detector. `LifestyleInflationScreen.LifestyleOverviewCard` lines 337–359 renders the negative rate/deflation state.
- **Existing tests/guards:** `app/src/test/java/com/yourname/expensetracker/verification/LifestyleAnalysisTest.kt`, `lifestyle inflation detects creep`, lines 44–56 supplies Jan?Feb?Mar order, contrary to the DAO, and asserts spending growth exceeds income growth. Thus its assertion does not cover the production ordering. Static inspection only; no execution.
- **Cross-cell impact:** P-05 lifestyle surface (segment 22) and dashboard savings-prompt consumer (segment 23); shared analytics ordering contracts relevant to E-02.
- **Old-ID cross-refs:** none. AIML-32 tracks English merchant-keyword categorization, not chronological trend reversal. Checked registry, tracker, remediation and archived references for this detector/order defect.

### Coverage increment 3

Fully read `domain/challenge/SpendingChallengeManager.kt` (289), `data/repository/SpendingChallengeRepository.kt` (89), `data/database/dao/SpendingChallengeDao.kt` (46), `domain/lifestyle/LifestyleInflationDetector.kt` (450), `ui/screens/lifestyle/LifestyleInflationViewModel.kt` (68), `ui/screens/challenge/SpendingChallengesViewModel.kt` (160), `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` (516), and `domain/usecase/savings/LifestyleSavingsPromptUseCase.kt` (240). Count: 17 complete files. MonthlySavingsSweepUseCase read begun; initial combined output truncated and must be recovered before counting complete.

Challenge path: ViewModel ? manager ? repository write-barrier precheck ? insert/deactivate DAO; no lifecycle event or notification path in these files. Lifestyle detector is read-only; its dashboard prompt consumer writes prompt cooldown state. Challenge and MoneyRadar raw-currency aggregates and cancellation catches require known universal-debt reconciliation; not automatically treated as new. Known DST stepping in challenge manager overlaps NEW-REG-2026-005. Additional exact excerpts read: ExpenseDao lines 210–234 and LifestyleAnalysisTest lines 18–57.

Reconciled closed/skipped planned-expense tracking ID: **P6-P1-09**. Adapter status loss is a live residual of that tracked behavior and is excluded from the new finding count (no independently demonstrated temporal regression).

### CA-P-05-003 | Money Radar turns failed reads into All Clear | defect class 12 | severity P2

- **ID:** CA-P-05-003
- **Title:** Failed risk-source reads produce a successful green financial assessment.
- **Defect class:** 12 (Error handling).
- **Severity:** P2.
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`: `getDueBills` lines 195-215 returns empty on error; `getUnresolvedAnomalies` lines 225-242 does likewise; `getBudgetRisk` lines 249-309 returns null on error. `compute` lines 151-185 treats these as real observations; null risk scores zero at lines 355-356, and `buildTopReasons` lines 468-474 emits FINANCES_HEALTHY. `ui/components/dashboard/MoneyRadarWidget.kt` lines 50-59 maps GREEN to green styling and the label `All Clear`.
- **Impact path:** Any ordinary repository/DAO failure in risk sources is indistinguishable from no obligations/no alerts/no risk. If no other source contributes risk (or all three fail), score=0, GREEN, healthy message, no recovery action. Example: active anomaly SELECT throws while bills and budgets legitimately return empty; the widget asserts All Clear instead of unavailable. This is separate from cancellation and raw-currency debt.
- **Caller trace:** `HomeViewModel.processedDataFlow` -> `ComputeDashboardWidgetsUseCase.compute` line 339 -> `ComputeMoneyRadarUseCase.compute`; anomaly path -> `data/repository/AnomalyAlertRepositoryImpl.kt` lines 50-51 -> `data/database/dao/AnomalyAlertDao.kt` lines 47-51, SELECT non-dismissed alerts. `ComputeDashboardWidgetsUseCase.assembleWidgets` lines 1184-1185 emits the result; `HomeScreen` lines 817-819 renders MoneyRadarWidget. Recurring source entry is `MergedRecurringPatternsProvider.getConfirmedPatterns` lines 23-24 -> `RecurringExpenseRepository.getAll` line 67 -> recurring DAO active rows.
- **Existing tests/guards:** `ComputeMoneyRadarUseCaseTest.kt` lines 318-336 proves GREEN/healthy for genuinely empty sources. It does not distinguish that legitimate case from failures; focused timeout/exception/failure search in this test found no such case. `CancellationSafetyArchitectureGuardTest.kt` lines 118-119 explicitly tracks broad catches as MIT-034, but neither that guard nor the cancellation baseline establishes ordinary-error availability semantics. Static review only.
- **Cross-cell impact:** P-05 radar; consumes P-04 recurring, P-06 budget/Monte Carlo, and E-02/I-05 anomaly data. No writes or money records created here.
- **Old-ID cross-refs:** none for ordinary-read-failure-to-healthy behavior. MIT-034 concerns cancellation catches; PB-04 concerns raw money sums; neither is re-reported here.

### CA-P-05-004 | Optional savings timeout aborts the entire dashboard | defect class 12 | severity P2

- **ID:** CA-P-05-004
- **Title:** The savings widget's local three-second timeout escapes as dashboard failure.
- **Defect class:** 12 (Error handling); related class 5 (local timeout versus caller cancellation).
- **Severity:** P2.
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `computeSavingsSweepWidget`, lines 1120-1148 wraps work in `withTimeout(3000L)` then rethrows every CancellationException, including its own TimeoutCancellationException. `compute` lines 338-346 awaits this optional widget before assembling any widgets. `ui/screens/home/HomeViewModel.kt`, `processedDataFlow`, lines 242-268 has only the outer error handling, which replaces the whole dashboard result; it has no local recovery for this widget or retry of the completed inner source subscription.
- **Impact path:** On a slow database/normalization/simulation response, the local sweep deadline expires while the dashboard's parent is still active. The exception exits compute before widget assembly, so already-computed totals/pace/categories are not published. The outer flow reports dashboard load failure; ordinary subsequent source emissions cannot resume that terminated inner subscription without reload/resubscription. Actual parent cancellation must still propagate; the defect is failure to distinguish the timeout this method owns.
- **Caller trace:** HomeScreen collection -> HomeViewModel.processedDataFlow -> ComputeDashboardWidgetsUseCase.compute -> computeSavingsSweepWidget -> `MonthlySavingsSweepUseCase.computeSweepRecommendation` lines 84-181: home-currency read, budget Flow.first(), expense normalization, recurring/planned reads, Monte Carlo and goals read. No money mutation is involved in the sweep computation.
- **Existing tests/guards:** Focused search for timeout/Timeout/delay/Cancellation across `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/` found no local timeout scenario. Cancellation guards verify rethrow patterns, not ownership-aware timeout recovery. No tests executed.
- **Cross-cell impact:** P-05 dashboard availability; P-06 Monte Carlo/budget and savings consumers contribute latency.
- **Old-ID cross-refs:** none. Analogous worker/OCR timeout issues NEW-P9-001/FRESH-P3-003 concern different entry points and recovery contracts. MIT-034 is swallowed-cancellation debt, not this local deadline escape. `git blame` attributes this branch to 28e47b0c0 (2026-04-25), so it is NOT claimed as a wave regression.

### Coverage increment 4 (final read budget)

Recovered MonthlySavingsSweepUseCase lines 1-201 after truncated output; remaining lines 202-546 were visible in the earlier output. Read `data/repository/PromptStateRepository.kt` (84) and `data/database/dao/PromptStateDao.kt` (65) fully. Total: **20 unique production files fully read**, with chunk recovery where necessary. No further full-file reads performed.

Additional targeted evidence: FinancialHealthScoreV2.saveToHistory lines 717-766 (precheck, read-then-update/insert, retention cleanup, CE rethrow); MergedRecurringPatternsProvider.getConfirmedPatterns, RecurringExpenseRepository.getAll, AnomalyAlertRepositoryImpl.getActiveAlerts and AnomalyAlertDao query; TimePeriodUtils.getStartOfWeek lines 546-556 (Monday-start); HomeScreen and MoneyRadarWidget rendering; cancellation guard tracked entries and `config/baselines/cancellation.json` matches; AnalyticsRepository normalization call-site search; UI mapper quality-field search. Shared-engine implementation coverage is not claimed from these searches.

Static provenance for class 15: `git blame` pins the new current-period prefilter at ComputeDashboardWidgetsUseCase lines 392/398 to **215c26222 (2026-09-15)**. The weekly aggregate statement at line 413 predates it; the new input narrowing creates CA-P-05-001. No other finding is asserted to have been introduced by the remediation wave.

## Final assessment across all 15 defect classes

| Class | Static assessment and evidence/limit |
|---|---|
| 1 Legal path | Dashboard read path traced to expense/planned/anomaly SELECTs. Synthesis has no mutations. Health score, prompt cooldown and challenges are the reachable writes examined. HomeViewModel's deprecated self-fetching category-analytics use (502-507) conflicts with preferred assembler intent but is already explicitly documented at 496-500; no new lifecycle-bypass finding asserted. |
| 2 Barrier | PromptStateRepository 30-50/79-82 and SpendingChallengeRepository 31-45 check DatabaseWriteBarrier; health history checks at 727. These are prechecks, not proof of a held barrier lease; global restore/read-barrier ownership remains I-cell scope. No assertion that these calls prove TOCTOU safety. |
| 3 Atomicity/TOCTOU | Read-only dashboard combines independent flows and performs later reads; it is not a database-wide transactional snapshot. Prompt cooldown check/record and health read/upsert are not enclosed in one transaction in the inspected methods. No new persisted corruption claim without schema/ownership revalidation. |
| 4 Idempotency | Compute can rerun per source emission; challenge deactivation updates selected IDs, prompt insertion has no dedupe at DAO, health upsert looks up existing period. Schema uniqueness and concurrent invocations not exhaustively checked; no clean bill of health claimed. |
| 5 Cancellation | Synthesis/total-engine/health catches rethrow CE. Dashboard Monte Carlo/stress/lifestyle and challenge catches retain tracked MIT-034 behavior (also in cancellation baseline); excluded from new count. Local sweep timeout is CA-P-05-004. |
| 6 Side-effect timing | Lifestyle prompt state is written at evaluateAndPrompt line 133 before compute returns and before HomeViewModel config visibility filtering (376-388). Health history writes before final widget publication. No money or critical lifecycle event is emitted by synthesis; prompt-delivery acknowledgment semantics need follow-up, not assumed correct. |
| 7 Money/currency | Reviewed historical normalization, income exclusion, failed-conversion exclusions, metadata propagation, category denominator and uncategorized row. Raw aggregate debt in radar/sweep/lifestyle/challenges is not re-reported; PB-04 and U-MONEY coverage require owner reconciliation. Block-party missing UI reader is explicit RP-06 accepted residual. |
| 8 Time | CA-P-05-001 and CA-P-05-002. Calendar trend and block-party key fixes present. Totals normalized DST arithmetic and challenge DAY_MS stepping overlap existing NEW-P5-2026-001 and NEW-REG-2026-005; excluded. |
| 9 Privacy | Inspected AI-briefing setting gate in HomeViewModel 275-326, bounded synthesis failure counts, exception logging and challenge e.message UI exposure. Provider/diagnostic sanitization and active logging-tree behavior were not fully traced; no new leak claim made without that evidence. |
| 10 Worker hygiene | No CoroutineWorker or notification posting entry in the 20 full-read files. Worker guards/retries are not executed by these direct foreground computations. Shared workers and AI scheduling remain their owning cells. |
| 11 Integrity | Planned status is dropped by DashboardContractsAdapter and defaults to PLANNED, defeating downstream filtering; this is a live residual of tracked P6-P1-09, not counted as new. No schema edit/migration in audit; currency reload and independent totals flow reviewed but not fully revalidated. |
| 12 Errors | CA-P-05-003 and CA-P-05-004. Provider catch-to-empty and TotalsAggregationEngine fallback-to-zero inspected; home-currency outcome inconsistency is already FRESH-P5-009. |
| 13 Wiring/dead code | Production caller traces attached to every finding. Synthesis normalized entry is live; block-party failure-count UI absence documented debt. Totals private week helper already NEW-P5-2026-002. Full-file names/comments were not treated as caller proof. |
| 14 Tests | CA-P-05-002 exposes a production-order mismatch in LifestyleAnalysisTest. Window tests cover month/exclusive end, but not cross-month Week; radar test covers genuine empty state, not failures. No test execution or pass claim. |
| 15 Fix-regression | CA-P-05-001 traced with blame to 215c26222 current-month slicing. Other defects pre-exist or have no temporal proof; no unsupported regression labels. |

## Final outcome and uncovered work

**PARTIAL cell audit: 4 new discovery findings, all P2 (0 P0 / 0 P1 / 4 P2 / 0 P3). Independent verification pending.** The 20-full-file ceiling was reached. The core dashboard/totals/adapter/synthesis path and lifestyle/challenge primary services were read end-to-end, but a complete transitive cell audit is not claimed.

Uncovered or excerpt-only surfaces requiring continuation:

- FinancialWeatherRepository and AdvancedAnalyticsEngine category-trend consumer flow, full AnalyticsRepository/DailyBucketEngine usage, and all UI-adapter currency-quality consumers.
- Full FinancialHealthScoreV2/HealthScoreHistoryDao/schema behavior; concurrent prompt-recording and delivery semantics; UI actions after prompt presentation and challenge error-sanitization path.
- Independent snapshots versus restore coordination and global read-barrier semantics (coordinate with owning infrastructure cell).
- Exhaustive repository-wide RP-21 feed reconciliation: no separately named RP-21 file was found; read the available `docs/testing/TEST_INDICATED_PRODUCTION_BUGS.md` PB-04 excerpt and active diagnosis menu hits, plus registry/remediation/archived tracker references. Broad recorded debt was conservatively excluded.
- Untriaged consumer-level quality candidates (runway/pace/category and budget UNKNOWN summary) overlap older P5 warning contracts; not promoted into new findings without complete old-ID reconciliation.

No builds, tests, lint, static guards or runtime experiments were run. Only report and campaign journal were written. Production source remained unchanged.

## Closing provenance (final)

Auditor: **direct astra session**; agents invoked: **none** (direct-session instruction).
Campaign: CA-2026-09-21; cell: P-05; mode: AUDIT/static only.
Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`.
Date: 2026-09-21. Final timestamp is recorded in JOURNAL.md.
Discovery is not independent verification and is not a PASS/GREEN campaign gate.

Final pin check: full HEAD remained 37601232b9778170c57a656a245b199ab6d7d965; both required committed production diff and working-tree production diff were empty. Timestamp: 2026-09-21T20:22:14Z.
