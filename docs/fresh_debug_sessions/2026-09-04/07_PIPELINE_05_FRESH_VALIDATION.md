# PIPELINE_05 — Fresh Validation (2026-09-04)

- Validated at: d1fa9c68 (branch atomicity-pr21-enforcement-final). Static validation only — no compile/test run.
- Source doc: docs/analyses and debug master/PIPELINE_5_CONSOLIDATED_ISSUES.md (last validated 2026-05-31)
- Cross-checked against: PIPELINE_ISSUES_MASTER_TRACKER.md (P5 section), FIXED_CLAIMS_VALIDATION_AUDIT_v4.md, docs/architecture/{CODEBASE_SEGMENTS,LEGAL_PATHS,dao-map,ENGINE_INTERACTION_MAP}.md

## 1. Pipeline theme

Currency/money correctness feeding Dashboard & Analytics: historical vs latest FX-rate bases, PURCHASE-only filtering, per-transaction conversion quality (isPartial/warnings/staleRateCount) propagation from `MoneyNormalizationEngine`/`MoneyAggregateBuilder` through `MultiCurrencyRepository` and `TotalsAggregationEngine` into dashboard widgets (trend, runway, MoM comparison). Segments 1/2/8/10/16 (CODEBASE_SEGMENTS.md). All time buckets flow through `TimePeriodUtils` (Segment 32) — directly in the blast radius of the T1–T4C time migration (da2b8565..96c6b27d).

## 2. Verdict summary

Source doc contains 26 issues (12 old + 14 new).

| Verdict | Count |
|---|---:|
| Verified fixed | 24 |
| Verified open | 1 (NEW-P5-012) |
| Verified partial | 0 |
| Regressed | 0 |
| Tracker/doc drift | 9 rows |
| New issues found this session | 3 |
| Not verifiable statically | 0 |

Headline: the P5 consolidated doc is severely stale. Commit `8c2289ef` ("complete P5 — all 9 remaining issues FIXED (P5-PR2+PR3)", 2026-06-01) closed 8 of the 9 "OPEN" new issues the day after the doc was written; `25c636e1`/`985b507e` resolved the P5-P1-08 FX-basis remainder via P6-CURRENT-001. Only NEW-P5-012 (hardcoded 7-day staleness threshold) is still open — and its "fix" claim in the `8c2289ef` commit message was never actually implemented.

## 3. Issue-by-issue validation

| ID | Title (short) | Old status | Fresh status | Class | Evidence (file:line) | Notes |
|----|---------------|------------|--------------|-------|----------------------|-------|
| P5-P1-01 | Historical totals used latest-rate conversion | FIXED | VERIFIED_FIXED | UNIVERSAL (money basis) | `MultiCurrencyRepository.kt:662-679` (getCategoryAggregatesHistorical → `normalizationEngine.aggregateExpenses(..., RateBasis.TRANSACTION_DATE)`); `TotalsAggregationEngine.kt:284-285,364,408` (purchaseTotalHistorical) | Per-expense transaction-date basis throughout historical APIs |
| P5-P1-02 | getRate() ambiguous with historical rows | FIXED | VERIFIED_FIXED | PIPELINE | `ExchangeRateDao.kt:34,40-44` — ORDER BY `validDate DESC, lastUpdated DESC`; getRate() deprecated with backfill-poisoning rationale | |
| P5-P1-03 | Dashboard adapter dropped MoneyAggregate warnings | FIXED | VERIFIED_FIXED | PIPELINE | `DashboardContractsAdapter.kt:73-88,137-139,173` forwards isPartial/warningMessage | |
| P5-P1-04 | Weekly/daily drilldown broken | FIXED | VERIFIED_FIXED | PIPELINE | `TotalsAggregationEngine.kt:130-160` (weekly, PURCHASE-only historical), `:194-221` (daily) | |
| P5-P1-05 | Widgets raw-summed effectiveAmount | FIXED | VERIFIED_FIXED | UNIVERSAL (money math) | `ComputeDashboardWidgetsUseCase.kt:238,289,805` — DashboardNormalizedInput path; `buildTrendFromNormalizedInput` sums `normalizedAmount` (:853) | |
| P5-P1-06 | Stale-rate state not in analytics quality | FIXED | VERIFIED_FIXED | PIPELINE | `NormalizedAnalyticsInput.kt:60` (staleRateCount); `AnalyticsInputAssembler.kt:169` (counted from warnings) | |
| P5-P1-07 | MCR inconsistent builder use | FIXED | VERIFIED_FIXED | UNIVERSAL (money math) | `MultiCurrencyRepository.kt:73-74` (MoneyNormalizationEngine default), `:928-933` (fromBuckets with counts) | |
| P5-P1-08 | Budget-vs-actual not fully normalized (limit PERIOD_END vs spend latest) | PARTIAL | VERIFIED_FIXED (drift) | UNIVERSAL (money basis) | `BudgetRepository.kt:202-221` — limit converted `convertBudgetAmountToHomeCurrencyAsOf(asOfMillis = periodEnd)` (:212-216); spend via `getAggregateSpent` → `getHomeCurrencyPurchaseTotalAsOf` PERIOD_END (`MCR.kt:803-819`, `BudgetRepository.kt:411,416`) | P6-CURRENT-001 marked RESOLVED in code. Both docs still say PARTIAL |
| P5-NEW-01 | Drilldown included non-spending types | FIXED | VERIFIED_FIXED | PIPELINE | `MCR.kt:684,706,728` — historical aggregates default `TransactionTypeFilter.PURCHASE_ONLY`; `TotalsAggregationEngine.kt:127-129` | |
| P5-NEW-06 | Budget dashboard dropped partial/conversion warning | FIXED | VERIFIED_FIXED | PIPELINE | `BudgetModels.kt:21-22` (isPartial/conversionWarning on snapshot); adapter forwarding | |
| P5-NEW-07 | Stale detection used lastUpdated not validDate | FIXED | VERIFIED_FIXED | PIPELINE | `AnalyticsCurrencyNormalizer.kt:142-152` — `convertOutcome(RateBasis.TRANSACTION_DATE)`; `:170-185` validDate-based stale flag (0L sentinel excluded) | |
| P5-NEW-09 | Monthly/yearly PeriodTotal dropped warnings | FIXED | VERIFIED_FIXED | PIPELINE | `TotalsAggregationEngine.kt:541-592` computeFromNormalized emits `dataQuality = input.dataQuality` per PeriodTotal | |
| NEW-P5-001 | previousMonthAggregate always null (P0) | FIXED (P5-PR1) | VERIFIED_FIXED | PIPELINE | `ComputeDashboardWidgetsUseCase.kt:375-381,421,448,479`; adapter loads prior month `DashboardContractsAdapter.kt:57-62` | Audit v4 re-verification re-confirmed at HEAD |
| NEW-P5-002 | Division by zero in projectedTotal | FIXED (P5-PR1) | VERIFIED_FIXED | PIPELINE | `ComputeDashboardWidgetsUseCase.kt:573` `if (daysElapsed > 0) ... else ...` | Audit v4 re-verification re-confirmed at HEAD |
| NEW-P5-003 | Deposit filter includes not-mine/shared items | OPEN | VERIFIED_FIXED (drift) | UNIVERSAL (money math) | `ExpenseDao.kt:1304-1319` — `AND isNotMine = 0 AND isSharedExpense = 0` in getDepositTotalsBetweenByCurrency; live via `MCR.kt:766-773` → `FinancialStressForecastEngine.kt:705`, `NetCashflowBalanceProvider.kt:22` | Fixed in `8c2289ef`; both docs stale |
| NEW-P5-004 | getAverageForPeriodType(DAY) wrong denominator | OPEN (doc) / FIXED (tracker, 37a0b83d) | VERIFIED_FIXED (drift: consolidated doc) | PIPELINE | `TotalsAggregationEngine.kt:405-409` — `daysBetween(startMs, now).coerceAtLeast(1)`; daysBetween is DST-safe `ChronoUnit.DAYS` on LocalDate (`TimePeriodUtils.kt:1575-1580`) | Commit `8c2289ef` |
| NEW-P5-005 | SynthesisEngine sums planned expenses across currencies | FIXED (U-PR3) | VERIFIED_FIXED | UNIVERSAL (money math) | `SynthesisEngine.kt:209-235,246-275` — occurrences/patterns/planned converted to displayCurrency; failures counted (`recurringConversionFailures`, :207) and dropped, not raw-summed | Residual: blank displayCurrency path still raw-sums (:213,222,232) — callers pass resolved currency |
| NEW-P5-006 | homeCurrency().first() cold Flow per call | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `MCR.kt:86-108` — @Volatile cachedHomeCurrency + init-block Flow collection invalidation (fix of incomplete first attempt, commit `617ba60e`) | Fixed in `8c2289ef`; docs stale |
| NEW-P5-007 | NormalizedAnalyticsInput.homeCurrency defaults to EUR | FIXED (P5-PR2) | VERIFIED_FIXED | UNIVERSAL (silent EUR fallback) | `8c2289ef` diff removed EUR default in `NormalizedAnalyticsInput.kt`; assembler resolves homeCurrency explicitly (`AnalyticsInputAssembler.kt:394-403,543`) | |
| NEW-P5-008 | Category aggregates ALL_TYPES vs PURCHASE-only | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `MCR.kt:666` default PURCHASE_ONLY; caller `TotalsAggregationEngine.kt:313` uses default | Commit `8c2289ef` |
| NEW-P5-009 | MoneyAggregateBuilder silently drops counts on size mismatch | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `MoneyAggregateBuilder.kt:42-55` — Timber.w on mismatch + `getOrElse(index){0}` | Commit `8c2289ef` |
| NEW-P5-010 | computeFromNormalized per-expense average not per-day | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `TotalsAggregationEngine.kt:545-554` — divides by calendar days (DST caveat → NEW-P5-2026-001) | Commit `8c2289ef` |
| NEW-P5-011 | FinancialRunway always 0 days | FIXED (P5-PR1) | VERIFIED_FIXED | PIPELINE | `ComputeDashboardWidgetsUseCase.kt:603-621` — runwayDays = totalRemaining/averageDailyBurn from budget or normalized income | Audit caveat still present: `:623-624` returns NO_INCOME when monthlyIncome==0 even with positive budget-derived runway → NEW-P5-2026-003 |
| NEW-P5-012 | Stale-rate detection fixed 7-day threshold | OPEN | VERIFIED_OPEN | PIPELINE | `AnalyticsCurrencyNormalizer.kt:175` — inline `val sevenDaysMs = 7L*24*60*60*1000` | `8c2289ef` message claims "MAX_RATE_AGE_MS with TODO for AppConfig" but AnalyticsCurrencyNormalizer.kt is NOT in that commit's file list (git show 8c2289ef --stat) — over-claimed fix |
| NEW-P5-013 | aggregateCurrencyTotals returns empty on unknown type | OPEN | VERIFIED_FIXED (drift, behavior note) | PIPELINE | `MCR.kt:920-925` — Timber.w (class name only, no payload) before empty return | Commit `8c2289ef`. Note: empty-return behavior unchanged (data still dropped); only silence removed |
| NEW-P5-014 | buildTrendFromNormalizedInput timezone edge case | OPEN | VERIFIED_FIXED (drift) | PIPELINE | `ComputeDashboardWidgetsUseCase.kt:809-867` — ZonedDateTime/java.time grouping, YearMonth lengthOfMonth, daysBetween day index | Commit `8c2289ef` |

### 3.1 Tracker/doc drift found this session

1. `PIPELINE_5_CONSOLIDATED_ISSUES.md` says "Last validated 2026-05-31 … 14 NEW open". 8 of those (NEW-P5-003/004/006/008/009/010/013/014) were fixed by `8c2289ef` (2026-06-01). Stale.
2. `PIPELINE_ISSUES_MASTER_TRACKER.md` P5 section lists only 6 of the 14 new issues (NEW-P5-001/002/003/004/005/011); NEW-P5-006/007/008/009/010/012/013/014 have no rows at all. NEW-P5-003 row still 🔴 OPEN although fixed in `8c2289ef`.
3. NEW-P5-004: tracker says FIXED (commit 37a0b83d) but consolidated doc says OPEN — internally inconsistent docs; code is fixed.
4. P5-P1-08: both docs say PARTIAL; P6-CURRENT-001 landed (PERIOD_END basis on both limit and spend, `BudgetRepository.kt:202-221`) — now resolved.
5. `8c2289ef` commit message over-claims NEW-P5-012 ("MAX_RATE_AGE_MS") — the constant never existed in AnalyticsCurrencyNormalizer.kt (git log -S MAX_RATE_AGE_MS on that file: no commits). Commit-message dishonesty, doc-worthy.
6. Consolidated doc "Priority Order" still lists fixed P0/P1 items (NEW-P5-001/011/002/003/005) as remaining work.

## 4. New issues & regressions found this session

| ID | Sev | Evidence | Description | Suspected origin |
|----|-----|----------|-------------|------------------|
| NEW-P5-2026-001 | MINOR | `TotalsAggregationEngine.kt:549-554` (computeFromNormalized) and `:605-610` (summarize): `java.time.Duration.between(start, end).toDays()`; `:568` `dayEnd = dayStart + 86_400_000L` | DST-unsafe day-count: `Duration.toDays()` counts 24-hour blocks, so a range containing a spring-forward 23-hour day floors to one day FEWER than the calendar-day count (e.g., 30-day March range in Europe → 29), overstating avgPerDay by ~3% and skewing OVER/UNDER_AVERAGE status. Same fixed-24h math makes PeriodTotal.startDateMs/endDateMs metadata overlap/gap by 1h on DST days. Fix: `ChronoUnit.DAYS` between LocalDates (as `TimePeriodUtils.daysBetween` already does) and `atStartOfDay(zone)` day buckets | Time migration adjacent (introduced in `8c2289ef` NEW-P5-010 fix, before T1–T4C standardized on LocalDate arithmetic) |
| NEW-P5-2026-002 | MINOR | `TotalsAggregationEngine.kt:513-515` — private `weekKey()` uses `getAppCalendarWeekYear/getAppCalendarWeekNumber` (WeekFields(MONDAY,1)); all live weekly keys are ISO (`MCR.kt:993-995` → `TimePeriodUtils.getIsoWeekKey`, WeekFields(MONDAY,4); parser `TimePeriodUtils.kt:885-897` uses Jan-4 ISO anchor) | Dead/conflicting week-key scheme: `weekKey()` is currently unused, but if a future caller uses it, its app-calendar keys ("yyyy-Www" with week 1 = week containing Jan 1) collide textually with ISO keys while meaning different weeks when Jan 1 falls on Fri/Sat/Sun — parseWeekKeyToStart would decode them wrongly. Remove the helper or make it delegate to getIsoWeekKey | Pre-existing; partially caused by `3372b917` week-key migration that converted MCR but left this helper |
| NEW-P5-2026-003 | MINOR | `ComputeDashboardWidgetsUseCase.kt:623-627` — `monthlyIncome == 0.0 -> RunwayStatus.NO_INCOME` precedes runwayDays checks | runwayStatus can contradict runwayDays: with no deposits but a budget, totalRemaining/runwayDays are positive (budget branch :609-610) yet the widget is labelled NO_INCOME. Day count right, label wrong (residual caveat already flagged in FIXED_CLAIMS_VALIDATION_AUDIT_v4; still present at HEAD) | Pre-existing (documented in audit v4; not a regression) |

No regressions found from the T1–T4C time migration in P5-owned paths: `TotalsAggregationEngine.getAverageForPeriodType` uses DST-safe `TimePeriodUtils.daysBetween` (:405-409); trend builder uses ZonedDateTime (verified); weekly keys consistent ISO end-to-end (MCR producer ↔ parseWeekKeyToStart consumer).

## 5. Universal candidates

- **Mixed-currency money math / silent fallback (U-PR3 family)** — the dominant recurring pattern in this pipeline. U-PR3 fixed the committed/likely/occurrence paths with failure counters (`SynthesisEngine.kt:207-275`), but silent raw fallbacks survive elsewhere in the same engine (see P6 doc NEW-P6-2026-004: `SynthesisEngine.kt:478,554` `?: raw`). U-PR3 does NOT fully cover the engine.
- **Stale-rate / missing-rate propagation** — P5-P1-06/NEW-07 fixed; `StaleRatePolicy.forBasis` design (policy per RateBasis, `StaleRatePolicy.kt:10-21`) is a good universal hook; the hardcoded 7-day inline value (NEW-P5-012) is the leftover tail.
- **Cold-Flow settings reads** — NEW-P5-006 fixed for homeCurrency via cache; same `.first()` pattern still exists in `FinancialStressForecastEngine.getEmergencyBuffer/resolveDisplayCurrency` (`:723-731`); U-PR6's scope should confirm coverage.
- **CE-swallow** — spot-checked: `TotalsAggregationEngine.kt:412-416` rethrows CE; U-PR1 (`e12aad97`, 146 guards/38 files) covered this pipeline. No residuals found in P5 files.

## 6. Recommended next actions (ordered)

1. Flip the 9 stale rows in `docs/analyses and debug master/PIPELINE_5_CONSOLIDATED_ISSUES.md` and the master tracker to FIXED citing `8c2289ef` / `25c636e1`; add missing NEW-P5-006..014 rows to the tracker P5 table.
2. Fix NEW-P5-2026-001: replace `Duration.between(...).toDays()` with `TimePeriodUtils.daysBetween(...)` (LocalDate-based) in `app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt:549-554` and `:605-610`; derive `dayEnd` via `TimePeriodUtils.getStartOfDay` of next day at `:568`. Add a DST-month unit test (e.g., March in a Europe/Athens-style zone).
3. Fix NEW-P5-012 for real: extract `MAX_RATE_AGE_MS` constant (with AppConfig TODO) replacing the inline `sevenDaysMs` at `AnalyticsCurrencyNormalizer.kt:175`; note the `8c2289ef` claim was never implemented.
4. Fix NEW-P5-2026-003: compute runwayStatus from runwayDays/budget presence, not `monthlyIncome == 0.0` alone (`ComputeDashboardWidgetsUseCase.kt:623-627`).
5. Delete or ISO-align the dead `weekKey()` helper (`TotalsAggregationEngine.kt:513-515`) — see NEW-P5-2026-002.
6. Decide NEW-P5-013 semantics: current warn+empty still loses data; consider surfacing an INVALID_BUCKETS warning into MoneyAggregate.warnings instead of returning empty.
7. When running tests (human/guardrail): `./gradlew :app:testDebugUnitTest --tests "*P5AnalyticsFixesTest*" --console=plain`.
