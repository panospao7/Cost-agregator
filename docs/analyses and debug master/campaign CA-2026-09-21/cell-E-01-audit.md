# E-01 — Currency and time shared surface (OWNER)

Date: 2026-09-22
Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`
Auditor: direct astra session
Cell: E-01 | Mode: AUDIT, static only
Status: STATIC DISCOVERY FINISHED — independent verification pending

Findings: 6 (P0: 0, P1: 0, P2: 6, P3: 0). Five new issues and one explicitly identified regression/decision-compliance gap (CA-E-01-004, P6-007). No runtime or review gate is claimed passed.

## Provenance and scope

- `git rev-parse --short HEAD`: `37601232`.
- `git diff --stat 37601232..HEAD -- app config scripts`: empty.
- Working-tree status has documentation and agent-config changes only; accepted as instructed.
- Governing prompt §2 and §4 read before production code. All 15 defect classes are in scope.
- Coverage-matrix E-01 row maps segments 16 (Currency & Exchange) and 32 (Utilities & Shared Helpers), legal heading Money / Currency, and engine rows CurrencyConverter, TimeProvider/TimePeriodUtils, MoneyNormalizationEngine.
- Read extracted LEGAL_PATHS.md:522–540, CODEBASE_SEGMENTS.md:352–396 and 629–649; engine rows 14, 19, 45, 223–234; inventory time-seam entries.
- Direct audit requested: no delegated agents; no builds, tests, or guards executed. Independent verification is not performed by this discovery session.

## Coverage ledger (append during audit)

Full production-file reads: 19 / 20. All named primary money/time files were read end-to-end; supporting callers, DAO projections, settings, bindings and tests were inspected through targeted excerpts. Checkpoints below record the exact coverage. No context compaction occurred.

## Findings

Discovery findings below are source-backed; independent campaign verification remains pending. Evidence paths are relative to `app/src/main/java/com/yourname/expensetracker/` unless explicitly prefixed otherwise. All line ranges refer to the pinned source.

### CA-E-01-001 | Rate refresh discards the provider date and invents download-day history | defect class 8 | severity P2

- **Evidence:** `data/repository/CurrencyRatesRepositoryImpl.kt`, `refresh`, 69–80 and 104–106, reads only Cube currency/rate attributes and passes no validDate. `domain/currency/CurrencyConverter.kt`, `storeRates`, 544–565 and `startOfDay`, 570–574, defaults validDate to download-day UTC midnight. `data/database/dao/ExchangeRateDao.kt`, `getRateAsOf`, 62–63, selects by that fabricated date; `domain/currency/CurrencyConverter.kt`, `convertAsOf`, 249–264 / `convertOutcome`, 364–367, trusts the stored result. Intent: `docs/currency/rate-basis-policy.md` TRANSACTION_DATE contract.
- **Impact path:** a response carrying a rate for day D fetched on D+1 is persisted as valid on D+1. A transaction on D then gets an older rate (or no rate on a fresh installation), even though the newly downloaded response supplies D's rate. Re-downloading an unchanged publication moves its apparent valid date forward and defeats freshness metadata. These are derived FX/date errors; no deletion or duplicate expense is alleged.
- **Caller trace:** `ui/screens/currency/CurrencyManagementScreen.kt:89,152` refresh actions → `CurrencyManagementViewModel.refreshRates:215–224` → repository refresh → converter store → adapter `insertOrUpdateAll:36–38` → DAO batch insert. Subsequent `MultiCurrencyRepository.getHomeCurrencyPurchaseTotalHistoricalResult:610–631` → `MoneyNormalizationEngine.normalizeExpense:62–81` → historical converter → DAO produces wrong/missing historical totals.
- **Existing tests/guards:** `ConversionSemanticsHardeningTest` and `CurrencyNormalizationBehavioralTest` construct validDate directly and cover historical selection/staleness, not provider-date parsing. Search of `app/src/test` found no `CurrencyRatesRepositoryImpl` or ECB XML fixture references. FG-17 (`FINAL_CI_GUARD_ACCEPTANCE_GATE.md`) is a structural money/time gate, not evidence of XML date correctness. NOT RUN.
- **Cross-cell impact:** P-02 expense FX provenance, P-05 totals, P-06 budgets/forecast, P-12 historical exports, E-02/I-05 analytics.
- **Old-ID cross-refs:** none for provider-date ingestion; A03 and P5-NEW-07 concern consumers choosing historical conversion / interpreting validDate, not this upstream loss. No same issue found in the registry, RP-21/debt inputs, or engine-5 report searches.

### CA-E-01-002 | Refresh and selectable currencies disagree with the converter's accepted catalog | defect class 7 | severity P2

- **Evidence:** `data/repository/CurrencyRatesRepositoryImpl.kt`, `PRIORITY_CURRENCIES`, 37–40 and `refresh`, 87–104, restricts rate generation to the priority list plus home/EUR. `domain/currency/CurrencyConverter.kt`, `SupportedCurrency`, 20–37 and `convertOutcome`, 343–356, accepts a different catalog and rejects unmatched currencies before consulting the store. `ui/screens/currency/CurrencyManagementViewModel.kt`, priority list 63–66, `loadCurrencyData:84–96`, and `setHomeCurrency:166–179`, expose and save the refresh catalog, including CNY/NZD/INR and others missing from SupportedCurrency.
- **Impact path:** (a) with EUR home and a provider response containing PLN, refresh drops PLN because it is neither priority nor home; the converter supports PLN, but PLN purchases remain unconvertible after a successful refresh. DKK/CZK/HUF/RON/ISK have the same catalog mismatch. (b) selecting CNY home and refreshing can persist EUR→CNY rates, yet typed aggregation rejects CNY as INVALID_TARGET_CURRENCY; legacy `convert` used by the conversion UI does not apply that whitelist and can succeed with the same stored pair. This is a deterministic wiring mismatch among live supported-product surfaces, not a request to support arbitrary fake codes.
- **Caller trace:** currency screen home selection → ViewModel `setHomeCurrency` → settings; refresh path as in 001. Dashboard/budget aggregate → `MultiCurrencyRepository` → `MoneyNormalizationEngine` / `MoneyAggregateBuilder` → `convertOutcome` rejects or finds no stored pair. `CurrencyManagementViewModel.convert:186–207` reaches the inconsistent legacy path.
- **Existing tests/guards:** converter fixtures focus on EUR/USD/GBP/JPY and inject rates; no provider/selector/converter catalog contract located by targeted CNY/PLN/priority searches. FG-17 money/currency guards do not establish cross-catalog completeness. NOT RUN.
- **Cross-cell impact:** all currency-normalized consumers, particularly P-05/P-06/E-02; currency settings/conversion UI.
- **Old-ID cross-refs:** none for mismatched live catalogs. M03 / engine-5 unsupported-code policy debt is distinct: this finding uses real currencies actively offered or explicitly accepted by production, not permissive `CurrencyCode("ZZZ")` parsing.

### CA-E-01-003 | Locale-sensitive canonical month keys silently discard valid budget history | defect class 8 | severity P2

- **Evidence:** `domain/util/TimePeriodUtils.kt`, `formatMonthKey(year, month)`, 837–840, uses default-locale `String.format("%04d-%02d", ...)`; `buildMonthKeyRange:859–876` uses it for every key. `data/repository/MultiCurrencyRepository.kt`, `getMonthKey:1089–1093`, instead uses ASCII numeric string conversion; `getHistoricalCategoryMonthlySpend:808–825,832–843` emits those keys. `domain/budget/BudgetHistorySeriesBuilder.kt`, `build`, 103–123, intersects the differently encoded strings and returns an empty series when they do not match.
- **Impact path:** under a numeric locale with non-ASCII decimal digits (e.g. Arabic), a canonical key generated by TimePeriodUtils differs from `2026-04` emitted by MultiCurrencyRepository. All existing monthly rows fail the set membership check. `BudgetAutopilotEngine.getHistoricalSpendForBudget:303–320` receives an empty series; `generateRecommendations:114–138` returns LOW_HISTORY/non-actionable recommendations even when several complete months exist. The source data and FX conversions may be entirely correct.
- **Caller trace:** budget UI autopilot load → `BudgetAutopilotEngine.generateRecommendations:98–112` → `MultiCurrencyRepository.getHistoricalCategoryMonthlySpend` → half-open `ExpenseDao.getExpensesBetweenUncapped:210–215` → per-row normalization → ASCII month keys → `BudgetHistorySeriesBuilder.build` using shared localized keys → false insufficient history.
- **Existing tests/guards:** `TimePeriodUtilsT4CBatch1Test:353–423` covers keys/timezones but does not switch to a non-ASCII numeric locale. `BudgetAutopilotEngineTest:188,204–205,229–230` constructs mocked repository keys with the SAME TimePeriodUtils formatter, masking the real producer/consumer discrepancy. The structural time guard associated with FG-17 does not check identifier digit encoding. NOT RUN; example is a static counterexample, not an executed test.
- **Cross-cell impact:** P-06 autopilot and other consumers joining SQL/repository month keys to TimePeriodUtils keys. SQL `ExpenseDao.getMonthlyTotalsBetweenByCurrency:1625–1637` also emits ASCII `%Y-%m` keys.
- **Old-ID cross-refs:** none. FRESH-P12-003 concerns import/export date locale behavior; M09 concerns money display/export formatting. Neither tracks canonical month-key joins.

### CA-E-01-004 | Typed bucket aggregation never reports total FX failure as UNAVAILABLE | defect class 12 | severity P2

- **Evidence:** `domain/core/money/MoneyAggregateBuilder.kt`, typed `fromBuckets`, 147–168, 186–219, accumulates failures but never supplies conversionQuality or included/excluded metadata. `domain/core/money/MoneyAggregate.kt:31–37` therefore defaults every nonempty failure list to PARTIAL, even when no bucket converts and displayAmount is zero. Contrast `MoneyNormalizationEngine.aggregateBuckets:253–258`, which explicitly reports UNAVAILABLE for total failure. Live consumer `domain/budget/BudgetForecastingEngine.kt`, `generateForecastResult`, 159–188, gates only on UNAVAILABLE; 220–224 gives zero input count a zero exclusion ratio.
- **Impact path:** EUR budget limit converts by identity; all actual purchases are USD without historical rates. The builder returns zero EUR, PARTIAL, no source buckets. The forecast skips its missing-rate exit, treats spend as zero, applies no current-spend confidence penalty, and persists a forecast (246–263) marked partial but with numerical risk/remaining/confidence instead of returning Unavailable. `insertForecast:330–337` goes through the barrier and `BudgetForecastDao.insertWithDeactivation`; this finding concerns incorrect input semantics, not an alleged unguarded write.
- **Caller trace:** `ui/screens/budget/BudgetForecastingViewModel.generateForecast:62–72` → engine → `data/repository/BudgetRepository.getCurrentPeriodPurchaseSpendAtPeriodEnd:455–474` → `MultiCurrencyRepository.getHomeCurrencyPurchaseTotalAsOf:910–925` (or category variant 934–951) → grouped ExpenseDao query → typed builder → forecast guard and persistence. All are live production paths.
- **Existing tests/guards:** `BudgetForecastingEngineTest:959–988` manually fabricates UNAVAILABLE in the repository mock; it does not exercise the real builder and therefore proves only the consumer branch. `MoneyAggregateBuilderRestrictionTest:64–79` asserts partial/zero/failure count, not UNAVAILABLE. FG-17 guards do not prove aggregate availability semantics. NOT RUN.
- **Cross-cell impact:** P-06 forecast/risk and every typed bucket consumer expecting total failure to be unavailable.
- **Old-ID cross-refs:** **P6-007 / RP-09 decision-compliance regression/worse**, not a restatement of the original mismatched-FX-basis issue. `remediation/RP-09-budget-fx-partial.md:236–250` claims the new total-failure stop; the actual repository producer cannot supply its discriminator. This is the newly demonstrated producer/consumer gap in that landed fix.

### CA-E-01-005 | Failure buckets disappear from aggregate totals and corrupt exclusion ratios | defect class 11 | severity P2

- **Evidence:** `domain/core/money/MoneyAggregateBuilder.kt`, typed `fromBuckets`, 151–168 and 186–210, adds sourceBuckets only for identity/success. `domain/core/money/MoneyNormalizationEngine.kt`, `aggregateExpenses:115–138` and `aggregateBuckets:184–245`, similarly omit failures. `domain/core/money/MoneyAggregate.kt`, `totalTransactionCount`, 47–49, counts only sourceBuckets, despite the shared contract promising preservation of source buckets (`LEGAL_PATHS.md:525–529`). Legacy builder `MoneyAggregateBuilder.kt:63–73,117–121` preserves all buckets, so the same public property changes semantics by construction path.
- **Impact path:** 2 included + 3 excluded transactions produce totalTransactionCount=2, failedTransactionCount=3 instead of a five-row total. `BudgetForecastingEngine.generateForecastResult:189–192,217–224` computes exclusion ratio 3/2 rather than 3/5: with weight 0.5, retention becomes 0.25 instead of 0.7, distorting persisted forecast confidence. Historical totals consumers also receive reduced transaction counts (`TotalsAggregationEngine.kt:167–177`). Source-currency breakdown cannot show excluded buckets. Amount sums correctly exclude failures; it is the source/count contract that is broken.
- **Caller trace:** same live P-06 chain as 004; historical dashboard/analytics → `MultiCurrencyRepository` historical APIs → `MoneyNormalizationEngine.aggregateExpenses` → `TotalsAggregationEngine` period totals. Forecast persistence path is evidenced in 004.
- **Existing tests/guards:** `BudgetForecastingEngineTest`, `partial period spend conversion lowers confidence and marks forecast partial`, 992–1025, explicitly describes 2 converted + 3 excluded and asserts the erroneous 3/2 calculation (1023–1024). This is a class-14 test-codifies-bug aspect of the same finding, not a separate count. `CurrencyNormalizationBehavioralTest:189–194` checks separate included/excluded metadata but not source conservation/totalTransactionCount. NOT RUN.
- **Cross-cell impact:** P-06 confidence/risk, P-05/E-02/I-05 counts and source breakdowns; shared builder contract belongs to E-01.
- **Old-ID cross-refs:** none for missing source buckets / total-input denominator. M08 and NEW-P5-009 address lost failure counts or mismatched supplied count-list sizes, not this successful-buckets-only total. P6-007 is the newly affected consumer; RP-09 promises a bounded exclusion-proportional penalty, not excluded/included arithmetic.

### CA-E-01-006 | Conversion failures log raw financial amounts | defect class 9 | severity P2

- **Evidence:** `domain/currency/CurrencyConverter.kt`, `convertMultiple`, 476–500, interpolates the exact source amount and currency into `Timber.w` for every failed conversion. There is no redaction/count-only transformation. `startup/AppStartupCoordinator.kt`, `configureDebugTools`, 636–641, installs unredacted DebugTree in DEBUG builds only. This bounds the demonstrated sink: debug-device logs; no release logging tree or remote transmission is claimed.
- **Impact path:** a missing rate in a latest-rate aggregate writes the user's per-currency financial subtotal to logcat. With one transaction in the bucket, that is the exact transaction amount. The shared privacy rules prohibit logging user financial payloads; a count and controlled failure code would describe the failure without the amount.
- **Caller trace:** `domain/forecasting/NetCashflowBalanceProvider.kt:22` / `FinancialStressForecastEngine.kt:705` → `MultiCurrencyRepository.getHomeCurrencyDepositTotal:873–879` → `aggregateToMoneyAggregate:983–995` → legacy `MoneyAggregateBuilder.fromBuckets:95–102` → converter → Timber/DebugTree. Other builder callers reach the same site.
- **Existing tests/guards:** converter tests exercise failed conversions but no log-payload assertion was located. `scripts/verify_money_boundaries.py:225–227` recognizes central money-owner files; money-boundary enforcement does not sanitize logs. No logging/guard execution. NOT RUN.
- **Cross-cell impact:** any current-rate money consumer running in DEBUG; P-08 privacy policy. P-06/P-07 reports contain different logging sites and are not duplicates of this converter site.
- **Old-ID cross-refs:** none found. Startup KDoc 626–633 calls debug PII logging intentional, but this is not an RP-00 decision or accepted-debt entry exempting this site, and it conflicts with the explicit audit privacy invariant. Severity P2 reflects the demonstrated debug-only local sink rather than alleging a release privacy leak.

### Coverage checkpoint 1

Full production files read (15/20): paths below are relative to `app/src/main/java/com/yourname/expensetracker/`.

- `domain/core/money/MoneyAmount.kt` (1–91): finite guard; currency-equality arithmetic; Double storage and two-decimal display are already M02/M06/M09 debt, not new findings.
- `domain/core/money/MoneyAggregate.kt` (1–119): finite display amount, partial defaults, source-bucket counts, factories.
- `domain/core/money/MoneyAggregateBuilder.kt` (1–222): both overloads, legacy count mismatch handling, historical required-date exclusion, typed failure mapping, source-bucket and rate-basis propagation.
- `domain/core/money/MoneyNormalizationEngine.kt` (1–325): normalization, transaction filters, identity, historical date selection, both aggregate paths, quality/metadata, ownership mapping.
- `domain/currency/CurrencyConverter.kt` (1–662): direct/composite/identity, latest versus historical queries, staleness references, batch conversion, rate storage/cleanup; no catch swallowing cancellation. Truncated-output gap 448–511 was re-read in a later targeted call.
- `data/repository/MultiCurrencyRepository.kt` (1–1189): all public and private methods; DAO grouping versus uncapped historical reads; cached settings resolution; error wrappers; bounded period-end APIs. Stub and settings-failure-family overlap known REVAL-8/FRESH-P5-006/FRESH-P5-009; not counted anew.
- `data/currency/ExchangeRateStoreAdapter.kt` (1–81): all rate writes precheck DatabaseWriteBarrier, entity/domain conversion, undated write rejection.
- `data/database/dao/ExchangeRateDao.kt` (1–70): pair/date REPLACE, latest sort, historical <= predicate, deletion policy and flows.
- `data/repository/CurrencyRatesRepositoryImpl.kt` (1–129): privacy gate before fixed-URL network request, secure XML setup, pair derivation, storage, settings timestamp after rate write, connection finally.
- `domain/util/TimeProvider.kt` (1–50), `SystemTimeProvider.kt` (1–13): injected clock contract and sole wall-clock implementation.
- `domain/core/time/PeriodRange.kt` (1–105): half-open contains, ordered bounds, elapsed duration clearly documented.
- `domain/core/time/PeriodKind.kt` (1–228): every enum branch, explicit-zone calendar arithmetic, custom validation, LAST_7/30 include exactly 7/30 calendar dates.
- `domain/core/money/MoneyFormatUtils.kt` (1–16), `domain/util/CurrencyFormatter.kt` (1–111): explicit-currency delegation, finite guards, locale/minor-digit display, stable export formatting; known formatting debt distinguished from new defects.

Additional searches: package-mate mutation/IO patterns; converter storage/cleanup and aggregate-bucket production call sites; P-05/P-06 cross-cell reports; known registry, remediation README/WAVE-1, RP-00 decisions D1–D8 (and appended decisions); legacy engine-5 money/time issue headings. Known custom/rolling-period bugs from old engine-5 report are fixed in current source.

## Defect-class assessment and limitations

### Coverage checkpoint 2

Four further full files (19/20 cumulative; same production root as above):

- `domain/util/TimePeriodUtils.kt` (1–1659): every helper, including legacy pre-Gregorian seam, calendar ranges, month/week/day identifiers, rolling versus elapsed windows, fixed monthly anchors, ISO versus app week numbering and calendar-day differences. Output gap 813–1287 was re-read. Modern calendar branches derive dates from explicit instants and local midnight; elapsed subtraction is documented separately. Localized canonical keys are 003. Legacy extreme/pre-Gregorian compatibility is intentional; no new runtime finding asserted for it.
- `domain/core/money/CurrencyCode.kt` (1–91): ASCII syntax, trimming parser, supported catalog, raw-code symbol fallback. Fake-ISO acceptance is known M03, not a new issue.
- `domain/core/money/MoneyMappers.kt` (1–102): legacy currency assumptions, finite MoneyAmount construction, failure reason/count mapping and warnings. Known precision/display/legacy-assumption debt is not restated; current mapper supports supplied failure counts.
- `domain/analytics/AnalyticsCurrencyNormalizer.kt` (1–367): both entry adapters, identity, invalid-currency exclusion, historical conversion, rate provenance, stale warnings, count-only logging and snapshot outputs. Truncation gaps 155–330 and 324–355 were re-read. It passes TRANSACTION_DATE and explicit transaction dates, never substitutes foreign amounts on conversion failure, and has no cancellation-swallowing catch.

Supporting source excerpts/searches read:

- `ExpenseDao.kt:210–218,1530–1545,1581–1596,1622–1637`: half-open reads, isNotMine filter, per-currency grouping, PURCHASE filtering and SQL month keys. `Expense.kt:152–167`: effectiveAmount share/ownership semantics.
- `ExchangeRate.kt:8–38`: unique pair/date key and date provenance. Its refresh-current-date comment conflicts with historical validity when publication date differs; it is not evidence of a recorded accepted-debt exemption for 001.
- `CurrencyManagementViewModel.kt:30–245` targeted methods; screen refresh/home selection sites 89/152/176–178/304–315/423–431; `MainActivity.kt:843` route. `CurrencySettingsRepositoryImpl.kt:42–63,104–118` confirms arbitrary selected three-letter CNY is persisted/resolved, not rejected before the typed converter. Interface `CurrencySettingsRepository.kt:71–89` inspected.
- `BudgetViewModel.kt:285–295`; `BudgetForecastingViewModel.kt:62–72`; `BudgetAutopilotEngine.kt:98–138,297–320`; `BudgetHistorySeriesBuilder.kt:103–150`; `BudgetRepository.kt:455–474`; `BudgetForecastingEngine.kt:153–244,246–269,289–305,330–352`: entry through aggregation to forecast state/write. `BudgetForecastDao.kt:84–94` is `@Transaction` around deactivation+insert; success diagnostic comes afterward at engine 293–304. No duplicate lifecycle event or forecast write bypass is alleged.
- `TotalsAggregationEngine.kt:146–178` and `AnalyticsRepository.kt:354–377`: count/aggregate consumers. `ForecastInputAssembler.kt:529–565,695–725`: actual aggregateBuckets callers use matching LATEST_AVAILABLE/Latest policies; no live mismatched historical-basis call was found. Latent mislabeling for arbitrary contradictory arguments was not promoted to runtime severity.
- `DatabaseWriteBarrier.kt:10–39`: precheck semantics; `ExchangeRateStoreAdapter` mutation-owner entries in `config/guards/db_ownership_policy.yml:70–101`; Hilt `CurrencyModule.kt:29–37`, `TimeModule.kt:23` binding matches live implementations. These establish ownership/check presence, not atomic exclusion against concurrent restore.
- `StaleRatePolicy.kt` policy declarations/forBasis: legacy 24h, typed latest 7d, historical none, consistent with recorded RP-06 policy. Historical absence fails without latest-rate substitution. Latest and historical source selection were followed to ExchangeRateDao.
- `AppStartupCoordinator.kt:623–641` bounds 006 to DEBUG logging. Network refresh checks PrivacyGate before opening the fixed URL, rejects blocked decisions, uses hardened XML parsing and disconnects in finally; no financial data is attached to that request.

Tests/guards inspected statically (no execution):

- `ConversionSemanticsHardeningTest`: historical midpoint/date requirement, three staleness reference modes, weakest-leg provenance and storage-date guards; `CurrencyNormalizationBehavioralTest`: valid-date lookup and included/excluded metadata.
- `MoneyAggregateBuilderRestrictionTest:64–92`: required-date failure/success assertions; `BudgetForecastingEngineTest:959–988,992–1028`: manually supplied unavailable result and codified bad denominator.
- `TimePeriodUtilsT4CBatch1Test:353–423`: key formatting/parsing and zone cases; `TimePeriodUtilsTest` key/week assertions; `PeriodRangeTest` month/week/half-open/DST assertions; `BudgetAutopilotEngineTest` formatter-built fixture keys; `MultiCurrencyRepositoryHistoricalCategoryMonthlySpendTest` local-month/group/conversion-failure cases.
- `scripts/verify_money_boundaries.py` central-owner classification, `scripts/verify_time_boundaries.py` clock/Calendar detection, and currency guardrails searches. Gate references: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-17 (money/time), FG-03 (unknown/not-run is not pass), FG-06/07 (no weakening). No guard status is inferred from reading scripts.

### All 15 defect classes

| Class | Static assessment |
|---|---|
| 1 Legal path | Core math is read-only; aggregate paths reach the grouped/uncapped ExpenseDao reads; rate writes reach the bound store adapter and its declared mutation owner. No new bypass demonstrated. |
| 2 Barrier | All three adapter mutation functions precheck DatabaseWriteBarrier; forecast insert also prechecks. Barrier is check-then-call, not a held restore permit. Shared call-site enforcement is known architectural debt; concurrent restore safety is not certified here. |
| 3 Atomicity / TOCTOU | Pair/date batch insertion and forecast deactivation+insert paths inspected. Rate-data persistence and settings last-update are separate, with settings updated afterward. Home-cache invalidate/refill and multi-query rates do not provide a snapshot; no independent new interleaving finding promoted without separating known cache/restore debt. |
| 4 Idempotency | Pair/from/to/date uniqueness + REPLACE preserves same-date retry behavior; separate dates retain history. 001 corrupts date identity, rather than inventing duplicate expense records. Cleanup has no production invocation found outside its declaration chain. |
| 5 Cancellation | Converter/normalization methods do not catch cancellation. Legacy MultiCurrencyRepository runCatching and settings catch patterns are already baselined (`config/baselines/cancellation.json:16,44`) and known broad cancellation debt; not re-reported. No new cleanup NonCancellable path here. |
| 6 Side-effect timing | Refresh writes rates before last-update preference; no success timestamp before DB write. Forecast persistence precedes FORECAST_GENERATED. No monetary lifecycle event writer exists in the pure aggregate/read path. Cross-pipeline event durability is not claimed audited wholesale. |
| 7 Money / currency | 002 catalog mismatch; 005 source/count inconsistency. Same-currency MoneyAmount arithmetic validates currencies, finite guards reject NaN/Infinity in approved amount/aggregate types; failed conversions are omitted from amounts. M02/M06 Double precision and M09 display semantics remain known debt. |
| 8 Time | 001 false FX effective dates and 003 canonical identifier locale. Reviewed all modern day/week/month/quarter/year/rolling branches and explicit-zone PeriodKind. Current custom bounds, 7/30-day inclusion and month-anchor behavior match intent; D7 deliberately does not reconstruct old lost anchors. |
| 9 Privacy | 006 raw financial failure log, DEBUG scope explicitly proven. Refresh gate is before IO; structured normalization warnings log counts. No release or remote exfiltration asserted. |
| 10 Worker hygiene | No CoroutineWorker is owned by the named E-01 surface. No worker implementation changed/executed; rate refresh is a ViewModel action. Worker consumers belong to their owning cells. |
| 11 Data integrity | 005 count/source conservation; 001 FX date provenance. DAO uniqueness and source filtering inspected; no schema edits/migration or destructive cleanup path is involved in this audit. |
| 12 Error handling | 004 successful numerical forecast despite total conversion failure; 002 rejects available configured pairs. Missing historical rates do not fall back to latest. Known home-currency error-family/stub debt suppressed. |
| 13 Wiring / dead code | Hilt bindings, currency screen, budget ViewModels, repositories and DAO callers traced. Known updateExpenseCurrency stub is REVAL-8. Arbitrary uncalled metadata combinations are not assigned live runtime severity. |
| 14 Test correctness | 003 same-formatter mocks hide real key mismatch; 004 manually fabricated availability hides builder gap; 005 test explicitly pins 3/2. Assertions were read, not executed. |
| 15 Fix-regression | Source log identifies `b376bd9c` (RP-09 spend quality path), `f10726f1` (RP-08 historical monthly API), `f23f4043` (RP-06 converter policy) and `9101aab3` (RP-04 anchors). 004 is the explicit RP-09 producer/consumer regression/compliance gap; 003 affects the new RP-08 connection. No claim of exhaustive history-wide regression review. |

### Boundaries and remaining work

This completes the bounded static discovery pass for the named E-01 primary files, with all 15 lenses applied. Nineteen unique full production files were read; remaining pure data/enum package mates were searched by relevant patterns rather than individually read. This is not an exhaustive audit of every downstream UI, worker, export implementation, restore barrier internals, platform XML implementation, migration history or test suite. Caller traces above establish demonstrated reach without certifying those other cells.

Five findings are new; 004 explicitly flags a landed known-issue fix as worse/incomplete under its new availability contract. Other tracked debt was excluded. Independent Phase-2 verification and any later targeted runtime validation remain pending. No builds, tests, lint, static guards, reproduction scripts, network requests or production edits were performed.

## Closing provenance

Agents invoked: none (direct astra session, as requested). Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`. Session date supplied: 2026-09-22; tool UTC completion timestamp: `2026-09-21T21:29:36Z`. Final pin recheck returned `37601232`; pinned-to-HEAD, unstaged and staged diffs for `app config scripts` were all empty. Static discovery only; independent verification pending. Only this report and the appended campaign JOURNAL entry are auditor writes.
