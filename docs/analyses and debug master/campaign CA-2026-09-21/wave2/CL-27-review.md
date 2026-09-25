# CL-27 post-implementation review — rp-26

Date: 2026-09-25. Reviewer: direct post-implementation review; no subagents.

**VERDICT: PASS — mergeable; no confirmed source regression.** All four work items are DONE-CORRECT. After the initial review, the owner explicitly accepted the correct A8 reconciliation despite the missing pre-edit approval provenance and waived/closed the missing architecture/Room guardian evidence. The separate compile profile now passes, and the constraint-branch advisory has dedicated regression coverage. The authorized forecast-constraint deviation remains **SANCTIONED**, for the current production call path and schema.

The owner's 2026-09-25 confirmation is recorded as a post-review disposition and waiver; it is not backdated or represented as proof that approval occurred before the assertion edits.

## Scope and environment

- Working directory: `C:/Users/panos/Desktop/cost agregator/ExpenseTracker/build/worktrees/rp-26`.
- `git rev-parse --abbrev-ref HEAD`: `rp-26-wip`.
- `git merge-base --is-ancestor ead80016 HEAD`: exit 0.
- Initial `git status --porcelain`: empty; remained empty throughout the read-only review.
- Reviewed source HEAD: `459a4651aa326c57bfee650ce42498dd61d7a681`. Comparison ref: `bug-fixes` at `91adfb1c9d1e0cabcaa97d60c78f5fecdcec697e`. All source/test line references below refer to that reviewed HEAD; the subsequent review commit changes documentation only.
- Governing document read in full: `CL-27-implementation-spec.md`, including its GATE LIFTED stamp, four work items, A8 approval requirement, test boundaries, fences and money constraints. Independent verification read: `../verification-2026-09-25-wave2-s2.md`, particularly lines 18–46 and 115–120. The narrower E-01-003 framing and E-01-005 legacy-overload exclusion were respected.
- Architecture inspected: `docs/architecture/CODEBASE_SEGMENTS.md` (Segment 16), `CODEBASE_INVENTORY.md`, `LEGAL_PATHS.md` (Money / Currency), and `ENGINE_INTERACTION_MAP.md` (CurrencyConverter impact chain). Relevant storage, forecast, period, history-series and UI dependencies were read without edits.
- Reviewed every file/hunk in `git diff bug-fixes..HEAD`: six production files and ten test files; 1,037 additions, 113 deletions. No source, tests, campaign status, decision register, JOURNAL, guards, configuration or generated output was changed by this review. No validation command was executed.

Lane commits, oldest first (`git log --oneline bug-fixes..HEAD`):

| Commit | Change |
|---|---|
| `cd4777a0` | Persist ECB publication dates |
| `9ece8bdf` | Canonicalize month keys with ASCII digits |
| `ac9e5748` | Conserve failed money source buckets |
| `eb8c8bb9` | Keep ECB XML declaration at document start |
| `ae20fdcd` | Align aggregate failure-count contracts |
| `459a4651` | Return typed forecast constraint outcomes |

Path abbreviations: `M/` = `app/src/main/java/com/yourname/expensetracker/`; `T/` = `app/src/test/java/com/yourname/expensetracker/`.

## Per-work-item verdicts

| Work item | Verdict | HEAD evidence and assessment |
|---|---|---|
| WI-1 — provider publication date | **DONE-CORRECT** | `M/data/repository/CurrencyRatesRepositoryImpl.kt:69–74,77–111,114–159,164–196`: parse one namespace-qualified dated node, reject absent/blank/malformed/multiple dates, read only its child rates, encode UTC midnight, and explicitly pass publication milliseconds to storage. Fetch freshness remains separate. No download-day fallback is reachable on refresh. Repository tests pass 9/9 at reviewed HEAD; converter/store-contract tests pass 24/24 on unchanged relevant code. |
| WI-2 — ASCII month identifiers | **DONE-CORRECT** | `M/domain/util/TimePeriodUtils.kt:827–840,860–877`: only canonical year/month formatting changes to `Locale.ROOT`; timestamp interpretation remains local, validation and inclusive ranges remain intact. Tests retain independent ASCII oracles, locale restoration, boundary checks, real series joining and existing scope/window behavior. Three relevant classes pass 25/25, 8/8 and 11/11. |
| WI-3 — conserve failed inputs | **DONE-CORRECT** | `M/domain/core/money/MoneyAggregateBuilder.kt:153–207,225–233` and `MoneyNormalizationEngine.kt:115–136,188–238`: each typed bucket is recorded once before branching; expense failures contribute original effective amount/currency/count. Only successes affect display totals. `MoneyAggregate.kt:49–62` arithmetic is unchanged. Real builder/normalizer tests pass 12/12 and 17/17. Legacy overload lines 31–131 are unchanged. |
| WI-4 — reconcile A8 assertions | **DONE-CORRECT — owner disposition recorded** | `T/scenarios/MoneyAggregateConversionScenarioTest.kt:240–241,355–359,437–456,519–525`; `T/domain/budget/BudgetForecastingEngineTest.kt:988–1018`; real-producer regressions at `T/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt:126–146` and `CurrencyNormalizationBehavioralTest.kt:141–160`. Correct fixed-contract assertions and 6/6 scenario plus 32/32 forecast execution are verified. On 2026-09-25 the owner explicitly accepted the reconciliation despite missing pre-edit provenance and waived/closed the outstanding guardian-evidence gate; this is a current disposition, not a fabricated historical approval. |
| Authorized WI-4 constraint-outcome deviation | **SANCTIONED** | `M/domain/budget/BudgetForecastingEngine.kt:263–305,347–368,697–699`: only SQLite constraint exceptions become payload-free outcomes; UNIQUE stays duplicate, other constraints become ConstraintViolation, both are handled exhaustively as unavailable. No exception text or throwable enters the new outcomes/logs/diagnostics. Non-constraint failures and cancellation still propagate. The ancillary diagnostics test adjustment is part of this narrow authorized deviation, not an unrelated fence expansion. |

### File-by-file delta inventory

| File | Changed behavior / review disposition |
|---|---|
| `M/data/repository/CurrencyRatesRepositoryImpl.kt:69–196` | Publication parser/internal fixture seam and explicit storage date; secure XML settings, privacy-before-network, IO dispatch and disconnect retained. |
| `M/domain/budget/BudgetForecastingEngine.kt:288–305,341–368,697–699` | Authorized typed non-duplicate constraint outcome, bounded unavailable diagnostic/reason and exhaustive dispatch. No forecasting formula edits. |
| `M/domain/core/money/MoneyAggregate.kt:12–13,48` | Contract comments only. Getter arithmetic and quality policy unchanged. |
| `M/domain/core/money/MoneyAggregateBuilder.kt:150–233` | Typed-source conservation and transaction-count metadata. Legacy overload untouched. |
| `M/domain/core/money/MoneyNormalizationEngine.kt:120–131,189,228` | Failed expense provenance retained; XXX fallback consistent with invalid-currency marker; bucket additions moved before branches, not duplicated. |
| `M/domain/util/TimePeriodUtils.kt:12,840` | Locale import and ROOT formatter only. |
| `T/data/repository/CurrencyRatesRepositoryImplTest.kt:1–374` | Nine deterministic fixture tests, pair/date-keyed fake, freshness tracking and secure-XML/privacy checks. XML-declaration fixture repair is legitimate. |
| `T/data/repository/MultiCurrencyRepositoryHistoricalCategoryMonthlySpendTest.kt:187–220` | Arabic-digit locale test with literal April key, purchase filtering, category/overall scopes and finally restoration. |
| `T/domain/budget/BudgetForecastingEngineDiagnosticsTest.kt:153–171,269–272` | UNIQUE/FK outcomes plus exact payload-free log assertions; message-preserving exception fixture, no raw throwable logging. |
| `T/domain/budget/BudgetForecastingEngineTest.kt:263–265,383–388,691–695,740–761,993–1018,1057–1060` | Remaining-period expectations, constraint fixture/contract correction and conserved forecast denominator. See arithmetic and authorization discussion below. |
| `T/domain/budget/BudgetHistorySeriesBuilderTest.kt:180–207` | Persian-digit locale with literal source keys, real join, zero-fill and completeness assertions. |
| `T/domain/core/money/CurrencyNormalizationBehavioralTest.kt:122–126,140–311,316–335` | Real normalization conservation, effective shares, stale/invalid failures, filtering and missing-date counts. |
| `T/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt:20–294` | Identity/mixed/all-failed/date-separated/signed/empty/zero-count coverage, lookup observation and legacy count contracts. |
| `T/domain/currency/ConversionSemanticsHardeningTest.kt:403–434,445–476` | Explicit-date as-of boundary and repeat upsert with date-keyed fake; existing rate/staleness behavior preserved. |
| `T/domain/util/TimePeriodUtilsT4CBatch1Test.kt:381,388–405,494,500–501` | US/Arabic/Persian digit checks; independent ASCII oracle replaces locale-sensitive expected-value formatting. Existing boundaries remain. |
| `T/scenarios/MoneyAggregateConversionScenarioTest.kt:240–241,359,447–456,519–520` | Bucket-vs-transaction distinctions and successful-only investment display. Existing seeds/transaction operations untouched. |

The only departures from the original five-production/nine-test fence are the explicitly authorized forecast production change and its directly related diagnostics test. No schema, DAO, entity, migration, lifecycle, worker, guard, baseline, selector, consumer-wiring or unrelated UI file is in the delta.

## Acceptance and boundary coverage

### WI-1

`CurrencyRatesRepositoryImplTest.kt:36–198` covers all ten enumerated categories across nine methods: actual ECB nesting/default namespace; D fetched D+1; repeat fetch; direct and derived pairs; D versus before-D historical lookup; missing/empty/invalid-leap/conflicting dates with no writes/freshness advancement; locale/timezone independence; privacy denial; invalid numeric rates; and rejected external-entity/DOCTYPE input. The pair/date store at lines 270–321 proves publication-key upsert instead of hiding it behind pair-only storage. `ConversionSemanticsHardeningTest.kt:403–434` independently checks explicit-date storage and repeat fetch.

The legal write path remains repository → `CurrencyConverter.storeRates` (`M/domain/currency/CurrencyConverter.kt:552–574`) → `ExchangeRateStoreAdapter.insertOrUpdateAll` (`M/data/currency/ExchangeRateStoreAdapter.kt:36–38`) → the existing barrier/DAO. `ExchangeRateDao.kt:13–26,33–34,62–63` retains pair/date replacement and as-of selection. The manual/default `startOfDay(now)` behavior still exists in the unchanged converter, as the spec requires; refresh bypasses it with a non-null publication date. No fabrication remains on that refresh path.

### WI-2

`TimePeriodUtilsT4CBatch1Test.kt:357–473` covers local UTC/New York month boundaries, all three named locales, January/December padding and rollover, months 0/13 rejection and inclusive ranges. The new expected-value helper at lines 500–501 does not call production formatting. `BudgetHistorySeriesBuilderTest.kt:26–175,181–207` preserves edge trimming, gaps and counts while exercising literal ASCII keys under Persian digits. `MultiCurrencyRepositoryHistoricalCategoryMonthlySpendTest.kt:75–182,188–218` retains purchase and category/overall scope checks and half-open query arguments. Effective-share preservation is additionally exercised by the real normalizer at `CurrencyNormalizationBehavioralTest.kt:163–189`; ownership/type rules were not changed.

The independent verifier narrowed E-01-003: current producers already use the same helper. This review sanctions canonical ASCII keys, not the stale claim that current production necessarily uses two different formatters.

### WI-3

The two named classes collectively cover all eleven requested categories. Builder tests at lines 71–264 cover missing-date-before-lookup, converted/identity success, 2+3 conservation, all failure, repeated currency with distinct historical dates, finite zero/negative amounts, empty/zero-count inputs, explicit/incomplete/unknown legacy counts. Normalizer tests at lines 108–311 cover missing/stale/invalid conversion, effective amounts, filtered transaction types, XXX plus raw invalid-currency domain provenance, all-invalid UNAVAILABLE and missing-date counts. Both real 2+3 producers would fail against success-only source buckets; this is not merely constructed-object coverage.

Included/excluded metadata counts transactions, not entries. Failed amounts do not enter `displayAmount`. Missing-date checks still precede conversion and identity still short-circuits. Existing finite guards, signed arithmetic, conversion order, staleness policy, cancellation propagation and controlled warnings are retained. Builder all-failed quality remains PARTIAL for CL-15; the normalizer's explicit UNAVAILABLE behavior remains unchanged.

## A8 reconciliation and forecast arithmetic

This before/fixed table is reconstructed from the reviewed diff and fixtures. It verifies the substance now; it is **not** retrospective proof that the required table/approval existed before editing.

| A8 method | Before | Correct contract at HEAD |
|---|---|---|
| Mixed warranty | Two failure entries, one transaction each; failed transactions already 2 | Keep failed transactions 2; explicitly assert failed buckets 2; preserve all three sources (`T/scenarios/MoneyAggregateConversionScenarioTest.kt:208–246`). |
| Mixed subscription | Two one-transaction failures; failed transactions already 2 | Keep failed transactions 2 and assert buckets 2; EUR-only display fixture and three source currencies retained (lines 317–365). |
| Investment portfolio | One USD failure with count 2, but expected failed transactions 1; summed unlike-currency source amounts | Failed buckets 1, failed transactions 2, total 3; USD 19,250 and EUR 700 remain separate provenance; display is only EUR 700 (lines 408–456). |
| Partial MoneyAggregate object | Two one-transaction failures and three source transactions | Failed transactions 2, buckets 2, total 3; no zeroing or loss of partiality (lines 485–529). |
| Partial period-spend forecast | Failed USD source omitted; denominator 2 and confidence about 0.183333 | EUR count 2 plus USD count 3 gives total 5, failed 3; confidence about 0.513333, partiality and PERIOD_SPEND_PARTIAL retained (`T/domain/budget/BudgetForecastingEngineTest.kt:988–1018`). |

The retention calculation matches spec lines 58, 64 and 75 exactly:

- `total = 2 + 3 = 5`, `failed = 3`, exclusion ratio `3/5 = 0.6`.
- Weight remains `0.5`, so spend retention is `1 - 0.6 * 0.5 = 0.7`.
- Two equal observed history months give baseline `(2/3)*0.8 + 0.2 = 0.7333333333333333`; historical exclusions are zero. Final confidence is `0.7333333333333333 * 0.7 = 0.5133333333333333`.
- Production calculations are unchanged: `M/domain/budget/BudgetForecastingEngine.kt:85,191–224,580–597`. The fixture/assertion, not coefficients or the aggregate getter, was repaired.

Both remaining-period assertion changes are also correct; the spec does not literally prescribe the fractions, so their derivation was checked against unchanged production period semantics:

- **16/30:** the April fixture anchors its monthly period on April 1, 2026 and evaluates on April 15. The exclusive end is May 1; 16 local calendar days remain. History mean is 125 with increasing multiplier 1.1: `125 * 1.1 * 16/30 = 73.33333333333333` (`T/domain/budget/BudgetForecastingEngineTest.kt:350–386`).
- **31/30:** the December fixture uses default ROLLING mode and anchors on December 15, 2026. Its end is January 15, 2027, not January 1. Flat history mean 100 therefore gives `100 * 31/30 = 103.33333333333333`, with no seasonal uplift (test lines 240–265).
- Supporting unchanged code: `M/data/database/entity/Budget.kt:60`, `M/domain/budget/BudgetCalculator.kt:80–100,144–167`, `M/domain/util/TimePeriodUtils.kt:1631–1635`, and `M/domain/budget/BudgetForecastingEngine.kt:193–195,555–574`. No time-window, display-formatting or rounding policy was changed to get these tests green.

## Authorized constraint deviation — SANCTIONED

1. **Classification:** insertion success returns Inserted. A caught SQLiteConstraintException with UNIQUE in its message returns DuplicateInSameInstant; every other such exception, including FK/NOT NULL/CHECK/empty or null message, reaches ConstraintViolation. The latter categories beyond FK are verified by the exhaustive source branch, not claimed as executed test cases. The only production caller constructs a fresh `BudgetForecast` with auto-generated id 0 (`BudgetForecastingEngine.kt:246–263`; `BudgetForecast.kt:107–112`); its current unique composite key is budget/period-start/forecast-instant. Thus UNIQUE identifies the expected duplicate on this path. This is not a general-purpose classifier for arbitrary ids or future additional unique indexes.
2. **Privacy:** exception message is inspected only for classification. Returned failure objects carry no fields; Timber receives a constant stage/code plus exception class, not a Throwable or `e.message` (`BudgetForecastingEngine.kt:355–366`). The new caller outcome has a constant reason and PERSISTENCE_CONSTRAINT metadata (lines 288–305). Diagnostics tests assert exact bounded log output for deliberately payload-bearing UNIQUE/FK messages (`T/domain/budget/BudgetForecastingEngineDiagnosticsTest.kt:153–171`).
3. **Exhaustive consumption:** all three sealed outcomes are handled at lines 263–305; a constraint never falls through to the persisted/generated-success path. The ViewModel already handles typed Unavailable (`M/ui/screens/budget/BudgetForecastingViewModel.kt:73–87`); the legacy wrapper throws the existing typed unavailable exception with the bounded reason (engine lines 425–435).
4. **Safety:** write-barrier checking remains before insertion and outside the constraint catch. The DAO's deactivate/insert transaction is unchanged (`M/data/database/dao/BudgetForecastDao.kt:90–94`), so a rejected insertion does not commit a deactivation-only update. Non-constraint exceptions are not caught here; cancellation is not swallowed. Best-effort diagnostic emission still rethrows CancellationException (engine lines 403–404).
5. **Evidence limitation:** helper-level UNIQUE/FK outcomes and log privacy execute in the supplied runs. There is no new end-to-end test injecting a persistence constraint into `generateForecastResult`, nor explicit null-message/NOT NULL/CHECK or cancellation fixture in these two classes. Additional tests would strengthen this newly authorized branch; static inspection found no defect requiring the deviation to be rejected.

## Human-executed validation evidence — read, never rerun

Evidence root: `build/validation-runs/`. For every run listed, the corresponding `<run-id>/result.json`, `<run-id>/stdout.log`, `<run-id>/stderr.log` and `complete.marker` were inspected. Exact commands are preserved in result.json. All are `targeted-unit-test` invocations of `gradlew.bat :app:testDebugUnitTest --tests <filter> --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon`; table filters retain the actual wildcard/non-wildcard distinctions.

| Run id | Filter | Result / exit | Executed cases | Source revision |
|---|---|---|---|---|
| `vr-20260925-165641-394dc397` | `*ConversionSemanticsHardeningTest` | PASS / 0 | 24/24 | `ac9e5748` |
| `vr-20260925-165808-5fc94533` | `*TimePeriodUtilsT4CBatch1Test` | PASS / 0 | 25/25 | `ac9e5748` |
| `vr-20260925-165917-61470289` | `*BudgetHistorySeriesBuilderTest` | PASS / 0 | 8/8 | `ac9e5748` |
| `vr-20260925-170116-1af350fe` | `*MultiCurrencyRepositoryHistoricalCategoryMonthlySpendTest` | PASS / 0 | 11/11 | `ac9e5748` |
| `vr-20260925-170240-bd04df17` | `*MoneyAggregateBuilderRestrictionTest` | PASS / 0 | 12/12 | `ac9e5748` |
| `vr-20260925-170358-3b577919` | `com.yourname.expensetracker.domain.core.money.CurrencyNormalizationBehavioralTest` | PASS / 0 | 17/17 | `ac9e5748` |
| `vr-20260925-172610-3ac61c0e` | `*MoneyAggregateConversionScenarioTest` | PASS / 0 | 6/6 | `ae20fdcd` |
| `vr-20260925-174628-354ef232` | `BudgetForecastingEngineTest` | PASS / 0 | 32/32 | `459a4651` |
| `vr-20260925-175839-f58b8551` | `BudgetForecastingEngineDiagnosticsTest` | PASS / 0 | 7/7 | `459a4651` |
| `vr-20260925-175945-5e4e4397` | `CurrencyRatesRepositoryImplTest` | PASS / 0 | 9/9 | `459a4651` |

These ten selected class runs provide **151 passing test cases**, not a full-suite claim. Each has a PASS/exit-0 completion marker, matching start/end fingerprints (`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`, the empty working-diff fingerprint), and a recorded source revision. Runs are sequential. Git diffs from the earlier passing revisions to reviewed HEAD show no later edits to the corresponding money/month/helper classes or their production dependencies; the later forecast change does not invalidate those scoped results. The scenario source is unchanged after its passing run.

Key stdout anchors: forecast `vr-20260925-174628-354ef232/stdout.log:1852–1916`; diagnostic privacy `vr-20260925-175839-f58b8551/stdout.log:59–73`; repository `vr-20260925-175945-5e4e4397/stdout.log:50–71`; A8 scenario `vr-20260925-172610-3ac61c0e/stdout.log:49–61`. These are actual test outcomes, not compile-success inference.

### Fix trail

- `vr-20260925-164059-2e7f8ff2`: FAIL/exit 1 at `ac9e5748`, **7 failed of 9** (2 passed). `stdout.log:1846–1856` identifies an XML declaration displaced by fixture indentation, not publication-date business logic. `eb8c8bb9` fixes fixture indentation without disabling tests. The same repository class passes 9/9 in `vr-20260925-171931-47926751` at `ae20fdcd`, and again in the supplied final run at reviewed HEAD.
- `vr-20260925-172743-b54e7167`: FAIL/exit 1 at `ae20fdcd`, **4 failed of 32**. `stdout.log:79–80,135–136,175–176,211–212` identifies the same-instant exception fixture, 16/30 expectation, FK-message assertion and 31/30 expectation. `459a4651` supplies message-preserving test exceptions, correct remaining-period expectations and the explicitly authorized typed FK outcome. The subsequent 32/32 and diagnostic 7/7 runs cover those corrections. The partial-confidence test already passes in the earlier run; it was not a remaining failure hidden by the deviation.
- The initial review found no standalone compile-profile result. Post-review remediation supplied `vr-20260925-184157-ddd18ae5`: profile `compile`, PASS/exit 0, completion marker present, with matching start/end worktree fingerprints. No full suite, lint or static-guard PASS is asserted.

## Regression findings and remaining gates

**Confirmed introduced regressions: 0** (0 CRITICAL / 0 MAJOR / 0 MINOR). No money representation/rounding change, cross-currency display sum, swallowed cancellation, getter double count, failed-source loss, legacy-overload weakening, write-barrier bypass, schema drift or unauthorized unrelated file was found in the delta. Existing CL-05 catalog/warning work and CL-15 total-failure availability are not relabeled as CL-27 regressions.

The following are merge-readiness/evidence findings, not invented source regressions:

- **[CLOSED G1] Owner disposition recorded.** On 2026-09-25 the owner explicitly accepted the correct A8 reconciliation despite missing pre-edit approval provenance. This closes merge readiness without inventing a historical approval date.
- **[CLOSED G2] Guardian-evidence gate waived/closed by owner.** The owner explicitly waived the missing architecture/Room guardian evidence after the review found no schema, migration, barrier or legal-path change. This is an owner disposition, not an impersonated guardian sign-off.
- **[CLOSED G3] Separate final compile evidence supplied.** `vr-20260925-184157-ddd18ae5` is PASS/exit 0 with a completion marker and matching fingerprints.
- **[CLOSED ADVISORY] Constraint branch coverage strengthened.** `BudgetForecastingEngineDiagnosticsTest` now covers a null-message non-UNIQUE fallback plus the end-to-end persistence-constraint unavailable event, fixed payload-free outcome, bounded metadata/logging and absence of a success event. All 9 tests pass in `vr-20260925-183428-56851761`.

**Remaining steps to mergeable:** none. **Mergeable.** A future schema/index change must revisit the deliberately narrow UNIQUE-message classifier.

## Cross-lane notes — CL-05 / rp-27 only

1. Rebase/reconcile against this lane before editing the overlapping refresh repository/tests and converter-test fake. Keep `validDate = parsedRates.publicationMillis`, one-dated-node parsing, UTC encoding, typed date failures, pair/date upserts and privacy/secure-XML boundaries while replacing the catalog. `CL-05-implementation-spec.md:137,160` already requires preservation of this date contract. Do not restore download-day fallback.
2. Consumers must treat `sourceBuckets` as all-input provenance, not converted contributions: use `displayAmount` for spendable value, sum source counts once, and distinguish failed buckets from affected transactions. Preserve XXX/INVALID_CURRENCY domain provenance without exposing raw currency text in warnings. Legacy unknown counts still mean unknown; do not fabricate them.
3. New adapter all-failed handling must use actual conversion outcomes, not `displayAmount == 0` or transaction-count zero (`CL-05-implementation-spec.md:75–80,138`). Do not pull CL-15's general builder-availability policy into this lane.
4. Keep canonical ASCII month identifiers and local/half-open calendar semantics. Preserve the unchanged CL-17 converter logging work; warning-content/catalog fixes remain CL-05-owned. The forecast constraint deviation is separate from currency catalog work and must not become an excuse for a broader forecast refactor.

## Review artifact delivery

The initial reviewer commit touched only this new `CL-27-review.md`. Post-review remediation additionally strengthens `BudgetForecastingEngineDiagnosticsTest.kt`; production source remains unchanged.

Validation by the initial reviewer was **NOT RUN**, as explicitly required. Post-review remediation was executed only through `scripts/validation-runner.ps1`: diagnostics 9/9 PASS (`vr-20260925-183428-56851761`) and compile PASS (`vr-20260925-184157-ddd18ae5`), both with completion markers and matching fingerprints. Do not edit JOURNAL.md.
