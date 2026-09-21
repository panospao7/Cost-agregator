# PIPELINE_06 — Fresh Validation (2026-09-04)

- Validated at: d1fa9c68 (branch atomicity-pr21-enforcement-final). Static validation only — no compile/test run.
- Source doc: docs/analyses and debug master/PIPELINE_6_CONSOLIDATED_ISSUES.md (last validated 2026-05-31)
- Cross-checked against: PIPELINE_ISSUES_MASTER_TRACKER.md (P6 section), FIXED_CLAIMS_VALIDATION_AUDIT_v4.md (esp. NEW-P6-004 PARTIAL flag), docs/architecture/{CODEBASE_SEGMENTS,LEGAL_PATHS,dao-map,ENGINE_INTERACTION_MAP}.md

## 1. Pipeline theme

Budget/forecasting/cashflow arithmetic: budget lifecycle & rollover compounding (`BudgetRepository`/`BudgetCalculator`), budget forecasting & write barriers (`BudgetForecastingEngine`), the forecast pipeline (`ForecastInputAssembler` → `SynthesisEngine`), cashflow day math (`CashFlowCalculator`), stress forecasting (`FinancialStressForecastEngine`), and alerting (`BudgetMonitor`). Segments 1/2/13 (CODEBASE_SEGMENTS.md). This pipeline owns the repo's densest calendar math — directly in the blast radius of the T1–T4C time migration (da2b8565..96c6b27d: T4B BudgetCalculator windows `5eec67c7`, CashFlow day iteration `1364aec1`, T4C month/year/quarter boundaries `eb94c15d`/`96c6b27d`).

## 2. Verdict summary

Source doc contains 31 issues (15 old + 16 new).

| Verdict | Count |
|---|---:|
| Verified fixed | 29 |
| Verified open | 0 |
| Verified partial | 1 (P6-P1-13) |
| Regressed | 0 |
| Tracker/doc drift | 14 rows |
| New issues found this session | 4 |
| Not verifiable statically | 0 |

Headline: every "TODO ONLY" P1 item except P6-P1-13 has been implemented since the doc was written (P6-CURRENT-001 batch + `985b507e` + `8c2289ef`); every 🔴 OPEN new issue except none is closed; and the audit v4 PARTIAL flag on NEW-P6-004 (365-day cap keeping the OLDEST periods) is resolved at HEAD by the ArrayDeque sliding window introduced in `25c636e1`. The only genuinely unfinished design item is P6-P1-13 (real account-balance source). Remaining findings are time-migration leftovers and two silent-fallback money paths in `SynthesisEngine`.

## 3. Issue-by-issue validation

| ID | Title (short) | Old status | Fresh status | Class | Evidence (file:line) | Notes |
|----|---------------|------------|--------------|-------|----------------------|-------|
| P6-P1-01 | Forecast refresh fails on unique index conflict | FIXED | VERIFIED_FIXED | PIPELINE | `BudgetForecastingEngine.kt:212` (ABORT strategy comment), `:256` (delegates to `BudgetForecastDao.insertWithDeactivation`) | |
| P6-P1-02 | Forecast rows createdAt=0 / wrong currency | FIXED | VERIFIED_FIXED | PIPELINE | `BudgetForecastingEngine.kt:115,147,201-202,230` — `createdAt = now`, `currency = homeCurrency` | |
| P6-P1-03 | Budget/forecast/planned writes lack restore guard | FIXED | VERIFIED_FIXED | UNIVERSAL (barrier) | `BudgetRepository.kt:555,580,621,671,727,762,799,835,918,923`; `BudgetForecastingEngine.kt:96,325,657`; `PlannedExpenseRepository.kt:61,123,133,162` — all `writeBarrier.checkWritesAllowed(...)` | |
| P6-P1-04 | Budget alerts use gross percentUsed | FIXED | VERIFIED_FIXED | PIPELINE | `BudgetMonitor.kt:235-246` — reads `adjustedSpendBreakdown?.effectiveSpend`, grossFallback tracked | |
| P6-P1-05 | Rollover ignores partial conversion state | FIXED | VERIFIED_FIXED | PIPELINE | `BudgetRepository.kt:221` (ORs isPartial), `:291-295` (merges warning messages distinct+join) | |
| P6-P1-06 | Budget limit uses current rate, not period-specific | TODO ONLY | VERIFIED_FIXED (drift) | UNIVERSAL (money basis) | `BudgetRepository.kt:208-216` — limit converted via `convertBudgetAmountToHomeCurrencyAsOf(asOfMillis = periodEnd)`; `:478-492` uses `CurrencyConverter.convertAsOf` with partial/warning fallback | P6-CURRENT-001; tracker still 📝 TODO ONLY |
| P6-P1-07 | SynthesisEngine ignores forecast data quality | FIXED | VERIFIED_FIXED | PIPELINE | `SynthesisEngine.kt:116` — `forecast.confidence - input.dataQuality.confidencePenalty` coerced | |
| P6-P1-08 | Planned expenses not normalized before forecast | TODO ONLY | VERIFIED_FIXED (drift) | UNIVERSAL (money math) | `ForecastInputAssembler.kt:96` ("P6-P1-08: canonical normalizer"), `:521` ("Normalize planned expenses through MoneyNormalizationEngine") | Tracker still 📝 TODO ONLY |
| P6-P1-09 | Cancelled/skipped planned expenses enter forecast | FIXED | VERIFIED_FIXED | PIPELINE | `SynthesisEngine.kt:183-188` — in-engine PLANNED-only filter (caller-independent) | |
| P6-P1-10 | Recurring occurrence status lost before forecast | FIXED | VERIFIED_FIXED | PIPELINE | `SynthesisEngine.kt:703-709` — PLANNED-only occurrences on bill-day calendar; assembler REC-20 effectiveAmount semantics (`ForecastInputAssembler.kt:102-110`) | |
| P6-P1-11 | Cash-flow calendar raw-sums multi-currency | TODO ONLY | VERIFIED_FIXED (drift) | UNIVERSAL (money math) | `CashFlowCalculator.kt:79-83` (MoneyNormalizationEngine injected), `:365` ("P6-P1-11: Normalize multi-currency amounts via MoneyNormalizationEngine") | Fixed in `985b507e`; tracker still 📝 TODO ONLY |
| P6-P1-12 | Cash-flow shows pre-dedup recurring predictions | FIXED | VERIFIED_FIXED | UNIVERSAL (double dispatch) | `ForecastInputAssembler.kt:56-58,222` — occurrence-driven cross-dedup of planned expenses vs materialized occurrences | |
| P6-P1-13 | Stress forecast is not a real account-balance forecast | TODO ONLY | VERIFIED_PARTIAL (drift) | PIPELINE | `FinancialStressForecastEngine.kt:97-100` (neutral baseline), `:688-710` (resolveStartingBalanceBaseline delegates to AccountBalanceProvider, net-cashflow fallback), `:169` mode=`NET_CASHFLOW_ESTIMATE`; DI seam `CashFlowModule.kt:28-30` | Production provider is still `NetCashflowBalanceProvider` (90-day net cashflow) — honest labeling + provider seam exist, but no real balance source is wired. Design TODO remains half-done |
| P6-P1-14 | Stress counts PAID occurrences as outflows | TODO ONLY | VERIFIED_FIXED (drift) | PIPELINE | `FinancialStressForecastEngine.kt:285` `ACTIVE_OCCURRENCE_STATUSES = setOf("PLANNED","OVERDUE","DUE")` (PAID excluded), filter `:357-369` | Note: statuses still raw Strings (`RecurringOccurrence.kt:28`); "OVERDUE"/"DUE" never occur per entity's status vocabulary — filter de-facto counts PLANNED only. Harmless but inconsistent with the typed-enum direction |
| P6-P1-15 | Deleting budget fails after forecasts exist | FIXED | VERIFIED_FIXED | PIPELINE | `BudgetRepository.kt:59-64,725-732` — FK CASCADE (schema v142) + explicit `deleteForecastsForBudget` in same transaction | |
| NEW-P6-001 | computeStressForecast swallows CE | FIXED (U-PR1) | VERIFIED_FIXED | UNIVERSAL (CE swallow) | `FinancialStressForecastEngine.kt:175-177` — `if (e is CancellationException) throw e` before structured log | |
| NEW-P6-002 | writeAlertDiagnostic swallows CE | FIXED | VERIFIED_FIXED | UNIVERSAL (CE swallow) | `BudgetMonitor.kt:381-382` — CE rethrown inside runCatching-style catch | |
| NEW-P6-003 | CHECK_FAILED diagnostic swallows CE | FIXED | VERIFIED_FIXED | UNIVERSAL (CE swallow) | `BudgetMonitor.kt:169-171` — CE rethrown | |
| NEW-P6-004 | Unbounded rollover loop O(N) per daily budget | FIXED (P6-PR1); audit v4: ACTUALLY_PARTIAL (cap keeps OLDEST periods, drops MOST RECENT; per-period query unbatched) | VERIFIED_FIXED at HEAD (audit flag resolved) | PIPELINE | `BudgetRepository.kt:263-286` — ArrayDeque sliding window: `periods.addLast(currentWindow)`, `if (periods.size > MAX_ROLLOVER_PERIODS) periods.removeFirst()` (MAX_ROLLOVER_PERIODS=365, `:100`); fold stays chronological `:288-301`; comment `:269-277` now correctly states oldest surplus is dropped. Fix introduced in `25c636e1` (2026-06-01), after audit v4 snapshot | Residual (accepted trade-off): still one `getAggregateSpent` query per retained period (≤365), no batch/ledger — BUD-11 materialized ledger remains TODO (`BudgetRepository.kt:230-252`, `:227-228` N+1 TODO) |
| NEW-P6-005 | BudgetRepository CRUD swallows CE | FIXED (U-PR1) | VERIFIED_FIXED | UNIVERSAL (CE swallow) | 8 catch blocks, each with CE rethrow: `BudgetRepository.kt:384,568,602,641,743,773,802,843` | |
| NEW-P6-006 | computeAdjustedSpend swallows CE | OPEN | VERIFIED_FIXED (drift) | UNIVERSAL (CE swallow) | `BudgetRepository.kt:384-385` — CE rethrow before Timber.w | Fixed in `985b507e` ("computeAdjustedSpend CE rethrow verified") |
| NEW-P6-007 | expandDetectedPatterns closed interval double-counts | FIXED (P6-PR2) | VERIFIED_FIXED | PIPELINE | `FinancialStressForecastEngine.kt:432-433` — half-open `nextDate >= startDate && nextDate < endDate` | |
| NEW-P6-008 | Stale detected patterns silently skipped | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `FinancialStressForecastEngine.kt:416-429` — 90-day stale threshold + Timber.w with merchant name (controlled field, no payload) | Fixed in `985b507e` |
| NEW-P6-009 | DST-unsafe day arithmetic in stress horizon | FIXED (U-PR7) | VERIFIED_FIXED | UNIVERSAL (time) | `FinancialStressForecastEngine.kt:104-106` ("DST-safe calendar arithmetic (U-TIME-02)") via `TimePeriodUtils.addDays`; expansion `:437-443` uses addDays/addMonths (no fixed DAY_MS) | |
| NEW-P6-010 | Hardcoded currency-specific risk thresholds | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `FinancialStressForecastEngine.kt:78-81` — named constants RISK_THRESHOLD_LOW/MODERATE/ELEVATED/HIGH | Fixed in `985b507e` (TODO for AppConfig noted in commit msg) |
| NEW-P6-011 | calculateSeasonalFactor dead stub | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `BudgetForecastingEngine.kt:495` — "calculateSeasonalFactor was a dead stub that always returned 1.0" removed | Fixed in `985b507e` |
| NEW-P6-012 | MIN_HISTORY_MONTHS unused | OPEN | VERIFIED_FIXED (drift) | PIPELINE | grep of `BudgetForecastingEngine.kt` for MIN_HISTORY_MONTHS: 0 hits — constant removed | Fixed in `985b507e` |
| NEW-P6-013 | pacePercentage=0 misleading with no baseline | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `SpendingPaceCalculator.kt:91-96` — returns -1f sentinel when hasBaseline=false | Fixed in `985b507e` |
| NEW-P6-014 | estimateIncome divides by hardcoded 3.0 | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `FinancialStressForecastEngine.kt:458-481` — month count from `ChronoUnit.MONTHS.between` on LocalDates, `coerceAtLeast(1.0)` | Fixed in `985b507e` |
| NEW-P6-015 | Income recurring treated as expense in cashflow | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `CashFlowCalculator.kt:286-294` (DEPOSIT/INCOMING → incomeList), `:412` + `:647` isIncomePattern routing | Fixed in `985b507e` |
| NEW-P6-016 | Weekly period uses locale-dependent WEEK_OF_YEAR | OPEN | VERIFIED_FIXED (drift) | UNIVERSAL (time) | `BudgetCalculator.kt:127-134` — "NEW-P6-016: Use ISO week fields instead of Calendar.WEEK_OF_YEAR"; java.time week arithmetic (further migrated DST-safely in `5eec67c7`) | Fixed in `985b507e` |

### 3.1 Tracker/doc drift found this session

1. `PIPELINE_ISSUES_MASTER_TRACKER.md` P6 section still shows 📝 TODO ONLY for P6-P1-06/08/11/13/14 — of these, 06/08/11/14 are implemented (evidence above); only 13 is genuinely unfinished (partial).
2. `PIPELINE_6_CONSOLIDATED_ISSUES.md` marks NEW-P6-006/008/010/011/012/013/014/015/016 🔴 OPEN — all fixed by `985b507e` (2026-06-01) and still present at HEAD.
3. FIXED_CLAIMS_VALIDATION_AUDIT_v4's NEW-P6-004 ACTUALLY_PARTIAL flag is obsolete at HEAD: the truncation-direction regression it described was fixed by `25c636e1` (sliding window now retains the MOST RECENT 365 periods). Its "inverted comment" note is also fixed (`BudgetRepository.kt:269-277`). The per-period-query batching part of the plan remains undone (BUD-11 ledger TODO).
4. Consolidated doc "Priority Order" still lists fixed items (NEW-P6-001..005, 007, 009, P6-P1-06/08/11/14) as remaining work.

## 4. New issues & regressions found this session

| ID | Sev | Evidence | Description | Suspected origin |
|----|-----|----------|-------------|------------------|
| NEW-P6-2026-001 | MINOR | `FinancialStressForecastEngine.kt:91,160` — `System.currentTimeMillis()` for duration measurement of computeStressForecast | Direct wall-clock read in violation of TimeProvider law (`domain/util/TimeProvider.kt` KDoc: "Every piece of production logic that needs the current time MUST obtain it through this interface"). Diagnostics-only (duration log), no calendar math — low impact, but it is exactly the pattern the T1–T4C/gr-00-local sweeps target | Time migration (T1–T4C did not touch this file; unmerged branch gr-00-local commit `8b45879e` claims the full 78-violation sweep) |
| NEW-P6-2026-002 | MINOR | `SynthesisEngine.kt:190-194` — `Calendar.getInstance().apply { timeInMillis = now }` for daysInMonth/dayOfMonth/daysRemaining driving `predictedDiscretionary`; plus 11 more Calendar sites at `:324,445,490,507,699,724-725,740-741,767` | Last major java.util.Calendar usage in a core forecast engine. All sites are seeded from `timeProvider.now()` or entity dates (no direct wall-clock read), and noon-anchored `set(HOUR_OF_DAY, 12)` avoids most DST edges — but Calendar's lenient field semantics and system-default zone are un-injectable, inconsistent with the java.time migration law, and untestable against fake clocks/zones | Time migration (T1–T4C migrated BudgetCalculator/CashFlow/dashboard; SynthesisEngine was out of scope; gr-00-local `8b45879e` covers it but is NOT an ancestor of HEAD) |
| NEW-P6-2026-003 | MINOR | `SynthesisEngine.kt:277-286` — `monthlyRecurringTotal = recurringPatterns.mapNotNull { ... convertAmount(monthly, pattern.currency, displayCurrency) }.sum()` with NO failure accounting | Silent mixed-currency drop asymmetry: the committed/likely paths count failures in `recurringConversionFailures` (:207, :215, :224, :234, :254, :274), but a missing rate here silently drops the pattern from monthlyRecurringTotal, which deflates `typicalDailyDiscretionary`/`predictedDiscretionary` (`:288-292`) with no warning surfaced | U-PR3 partial application (`204c677f` added failure tracking to committed/likely paths only) |
| NEW-P6-2026-004 | MINOR (MAJOR for multi-currency users) | `SynthesisEngine.kt:471-479` (`totalMonthlyPlanned`, block-party section) and `:549-556` (`plannedOnDay`) — `convertAmount(raw, expense.currency, bpCurrency) ?: raw` | Silent raw-currency fallback: when conversion fails, the raw amount in the expense's own currency is summed into a home-currency daily target — mixed-currency money math (fail-open), exactly the U-PR3 category. Committed/likely forecast paths (:229-235, :269-275) correctly drop+count instead | Pre-existing; U-PR3 rework missed the block-party section |

Time-migration regression hunt result (T4B/T4C commits): no regressions found in this pipeline. `5eec67c7` (BudgetCalculator windows) preserves anchor-day clamping semantics exactly and added `BudgetCalculatorTimeBoundaryTest` (291 lines); `1364aec1` (CashFlow day iteration) replaced the Calendar cursor with a LocalDate cursor using half-open `[dayStart, dayEnd)` matching (`CashFlowCalculator.kt:318-321`) — verified equivalent to prior `getStartOfDay/getEndOfDay` semantics with a 1ms-boundary strictness improvement; T4C batches touched only `TimePeriodUtils` internals with 1000+-line dedicated test files (`eb94c15d`, `96c6b27d`). The PR18–PR24 cancellation/atomicity range (`bffc4299..65c265fb`) and the PII burn-down commits (`e7f3496e`, `92a6ebf7`, `b2025407`, `ca760783`, `f652218f`) touch no P6 files (git diff --name-only overlap: empty).

## 5. Universal candidates

- **CancellationException swallow (U-PR1)** — fully verified across this pipeline: 8/8 catches in BudgetRepository, both BudgetMonitor diagnostic paths, both stress-engine catch blocks rethrow CE. U-PR1 coverage here is complete; no residual CE swallows found.
- **Mixed-currency money math / silent raw fallback (U-PR3)** — the recurring pattern that is NOT fully closed: NEW-P6-2026-003 and NEW-P6-2026-004 are both `?: raw`/silent-drop variants inside `SynthesisEngine`, missed by the U-PR3 sweep that fixed the committed/likely paths. Recommend a targeted U-PR3-completion pass over `SynthesisEngine.kt` (and any `?: raw`/`?: 0.0` fallbacks around convertAmount repo-wide).
- **System.currentTimeMillis instead of TimeProvider (U-PR7/T-sweeps)** — one residual duration-measurement site (NEW-P6-2026-001) plus the Calendar debt (NEW-P6-2026-002). The completed sweep lives on unmerged branch `gr-00-local` (`8b45879e`, "78 violations, 40 files") — merging it would close both.
- **Restore/write barrier mishandling (U-BARRIER-01 family)** — verified healthy: 17 barrier check sites across budget/forecast/planned writes; audit's U-BARRIER-01 (createCostBackup try/finally) re-verified fixed at `DatabaseBackupRepositoryImpl.kt:518-525,477-483` including CE rethrow at :478.
- **Maintenance/restore read barrier in forecasts** — good pattern to keep universal: stress engine degrades + flags `materializedReadBlocked` instead of failing (`FinancialStressForecastEngine.kt:337-350,261-263`).

## 6. Recommended next actions (ordered)

1. Flip the 14 stale rows in `docs/analyses and debug master/PIPELINE_6_CONSOLIDATED_ISSUES.md` and the master tracker (P6-P1-06/08/11/14 → FIXED; NEW-P6-006/008/010/011/012/013/014/015/016 → FIXED) citing `985b507e`, `25c636e1`, `8c2289ef`; keep P6-P1-13 as PARTIAL with the AccountBalanceProvider seam noted.
2. Complete U-PR3 in `SynthesisEngine`: replace `?: raw` fallbacks at `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt:478,554` with drop+count (pattern already present at :229-235), and add failure accounting to `monthlyRecurringTotal` (:277-286) — closes NEW-P6-2026-003/004.
3. Merge or cherry-pick `gr-00-local` commit `8b45879e` (TimeProvider/java.time sweep) to eliminate NEW-P6-2026-001 (FinancialStressForecastEngine.kt:91,160) and NEW-P6-2026-002 (SynthesisEngine Calendar debt); alternatively migrate `SynthesisEngine.kt:190-194` to `TimePeriodUtils.getDaysInMonth/getDayOfMonth` as done in `5a90ebad` for the dashboard.
4. Finish P6-P1-13: implement a real `AccountBalanceProvider` (bank connection or manual balance) behind the existing DI seam (`di/CashFlowModule.kt:28-30`); keep `NetCashflowBalanceProvider` as fallback only.
5. For daily budgets >365 periods old, add a test asserting the current effective limit reflects the MOST RECENT 365 completed periods (`BudgetRepository.kt:263-301`), locking in the `25c636e1` sliding-window semantics against regression.
6. Consider replacing raw status-string filtering (`FinancialStressForecastEngine.kt:285`, `SynthesisEngine.kt:185,709`) with the typed `RecurringOccurrenceStatus` enum the segment docs describe, so "DUE"/"OVERDUE" vocabulary is compile-checked.
7. When running tests (human/guardrail): `./gradlew :app:testDebugUnitTest --tests "*P6BudgetCleanupTest*" --tests "*BudgetCalculatorTimeBoundary*" --tests "*CashFlowCalculatorTest*" --console=plain`.
