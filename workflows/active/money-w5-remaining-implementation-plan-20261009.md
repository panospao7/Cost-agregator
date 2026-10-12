# Wave 5 remaining implementation plan — 2026-10-09

Status: **PARTIAL**. User requested a planner subagent followed by implementation. Planner Turing returned RP-22; the orchestrator verified the first batch against source and incorporated scout/guardian findings below. Execute one bounded batch at a time with strict review before serialized runner validation. This is not whole-wave acceptance.

## Continuation amendment - no further subagents

The user subsequently instructed the orchestrator to stop using subagents. The active correction agent was stopped. Further work, review, documentation, and serialized validation-wrapper execution are local. Local self-review does not replace independent acceptance. Earlier FAIL reviews found incomplete financial inputs reaching the simulator; the same two-file batch was corrected and its 15 focused tests passed. Independent re-review remains outstanding.

## Verified starting state

- Branch `bug-fixes`, HEAD `a1c66173219d7d959b0d36859a68176aaf814242`.
- Runner byte-hash fingerprint `c19ee12aa4dff6f73d2276245c97038f859334f68b582d3ce2ffc97fec62eb3d`; 140 tracked modified and 43 untracked paths. Mixed tree includes unrelated work.
- No active lock, live Gradle/validation process, or STARTING/RUNNING durable record found during preflight.
- Physically inspected result metadata, markers, and meaningful log tails: compile `vr-20261009-091844-16fee3ac`; historical distribution `vr-20261009-092916-fbb539c3`; simulator `vr-20261009-094036-ca89f6e6`; simulator golden `vr-20261009-094157-e6eb1093`; synthesis golden `vr-20261009-094318-95a20225`. Each PASS/0, matching start/end c19 fingerprint. They validate the previous forecasting slice only and become historical after edits.
- Baseline diff contains only the older authorized AdvancedAnalyticsEngine removal. The four newly resolved cancellation entries remain present. No new baseline authorization supplied.
- Read scoped Segment 1/10/16/35 ownership, inventory, Money/Currency and Diagnostics legal paths, engine impact matrix, and `money-w5-forecast-quality-20261009-handoff.md`.

## Evidence and acceptance contracts

- `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `computeMonteCarlo`: simulator call omits display currency; catch swallows cancellation, logs throwable, and collapses unavailable to null. Early paths accept free-form reasons. `CurrencyQualityUi.unavailable` and null-currency `MonteCarloResult.unavailable` already exist; UI intentionally renders unavailable result instead of money.
- `domain/forecasting/MonteCarloSpendingSimulator.kt`, `simulate` and branch helpers, and `HistoricalSpendingDistribution.kt`, compatibility/fit models: implicit EUR defaults remain. Prior end-month unavailable/quality fixes must be preserved.
- `domain/savings/SmartSavingsEngine.kt`, recommendation production, and `domain/usecase/savings/MonthlySavingsSweepUseCase.kt`, goal filtering: same-home LEGACY_DEFAULT goals remain eligible. Equal labels do not prove denomination.
- `ui/screens/savings/SavingsGoalsViewModel.kt`, acceptance: balance and history use separate Room and DataStore writes; multi-goal rejection and hidden failures need an honest contract. `data/database/entity/SavingsSweepPlan.kt` exists but planner found no production consumer; verify its DAO/schema/backup integration before using it.
- `data/repository/SavingsContributionHistoryRepository.kt`: malformed JSON handling exists, but parsed event validation must precede pruning/writing. Missing legacy currency is unknown provenance; it is not automatically corrupt history.
- Receipt/bank/PDF changes have historical tests; current source acceptance and device evidence remain distinct.

## Batch 1 — Dashboard Monte Carlo boundary (R15/R17 caller slice)

Write scope: `ComputeDashboardWidgetsUseCase.kt`, its directly affected unit tests and dashboard fixture stubs. No simulator API/default changes, savings changes, schema, runner, or baseline edits.

1. Extract Available normalized input once; pass `input.homeCurrency.code` explicitly as simulator display currency.
2. Catch and rethrow CancellationException unchanged before broad fallback.
3. Map MoneyDisplayUnavailableException using only `reasonCode.name` into explicit unavailable result plus CurrencyQualityUi.unavailable.
4. Ensure early normalized/runway unavailable paths expose controlled constants; do not forward arbitrary reasons into Monte Carlo confidence or quality warning fields.
5. Unexpected errors use a fixed reason and exception class only (no throwable/message/stack). Preserve unrelated widgets; preserve null only for genuine simulator no-result if the contract allows it.
6. Preserve sampling, financial arithmetic, seeded values, and rate policies.
7. Require complete owner normalization and a synthesized forecast with neither partial input nor exclusions before simulation. Incomplete actuals or future obligations return controlled unavailable output with zero simulator calls.

Tests: observable `compute()` caller tests for USD/GBP forwarding and result denomination; typed unavailable visibility and reason; identity cancellation; generic exception with sensitive message never exposed; genuine null/no-result; home-currency unavailable early branch. Update directly affected mocks to match explicit display currency.

Gates: privacy-security guardian recheck; independent reviewer-strict; validation-runner `compile`, `targeted-unit-test` filters for `*ComputeDashboardWidgetsUseCase*`, directly affected dashboard consistency tests, `*DashboardContractsAdapterTest*`. Expand only on new failures or unresolved concerns.

## Batch 2 — Explicit forecast currencies (R15)

Files: `MonteCarloSpendingSimulator.kt`, `HistoricalSpendingDistribution.kt`, production simulator/distribution callers and affected tests.

1. Require explicit supported display currency at public decision entry points; remove EUR defaults from private branch helpers.
2. Audit DistributionFit/compatibility defaults and every production caller: dashboard, Money Radar, smart savings, sweep, forecast consumers. Compatibility adapters must not drive production decisions with guessed currency.
3. Validate invalid currency before deterministic last-day/no-fit fallback. Settings failure must not fabricate EUR.
4. Keep legacy persistence defaults on SavingsGoal outside this batch.

Tests/filters: `*MonteCarloSpendingSimulatorTest*`, `*MonteCarloSpendingSimulatorGoldenTest*`, `*HistoricalSpendingDistributionBoundaryTest*`, `*ForecastSynthesisGoldenTest*`, `*ComputeMoneyRadarUseCaseTest*`, plus changed callers. Cover USD/GBP, invalid/omitted target (compile contract), settings failure, end-month and no-fit branches. No arithmetic assertion weakening.

## Batch 3 — Typed savings outcomes and presentation (R17)

Files: `SmartSavingsEngine.kt`, `MonthlySavingsSweepUseCase.kt`, `SavingsGoalsViewModel.kt`, `SavingsGoalsScreen.kt`, dashboard savings adapter and related models/tests.

1. Introduce controlled Available / Unavailable / valid NoRecommendation smart-savings outcome, following existing sweep result structure.
2. Remove unavailable-to-empty-list conversion. Carry currency, normalization counts/warnings, conversion quality, rate basis, and timestamp where known; statistical confidence is separate.
3. Invalid home currency, partial budgets, missing/stale conversion, and unavailable forecast must remain Unavailable. Genuine lack of underspend/eligible goals stays NoRecommendation.
4. Render unavailable state and retain reason in ViewModel/dashboard adapter; no fabricated zeros or silent no-op success.
5. Audit deprecated nullable adapters: no production decision entry points.

Tests: smart/sweep available-empty versus unavailable, partial/invalid input, retained metadata, cancellation identity, ViewModel/screen and dashboard mapping. Filters: `*SmartSavingsEngineTest*`, `*MonthlySavingsSweepUseCaseTest*`, `*SavingsGoalsViewModelTest*`, directly affected dashboard tests.

## Batch 4 — Legacy savings provenance (R14)

Files: narrowly scoped domain savings eligibility policy if warranted, SmartSavingsEngine, MonthlySavingsSweepUseCase, SavingsGoalsViewModel acceptance, SavingsGamificationEngine, tests.

1. Reject unknown/LEGACY_DEFAULT denomination consistently for active unfinished balances and gaps, even same-home.
2. Maintain explicit same-currency-only recommendation limitation; do not introduce cross-currency allocation.
3. Recheck provenance and home currency at acceptance, including changed settings without goal-list changes.
4. Unknown values cannot drive levels, achievements, zero-based outcomes, or actionable recommendations.

Tests: same-home legacy, foreign legacy, mixed explicit+legacy portfolio, currency-less history, changed home currency, stale recommendation rejection, explicit EUR/USD/GBP behavior. Filters: savings family above plus `*SavingsGamificationEngineTest*` and repository currency tests.

## Batch 5 — Semantic history corruption (R19)

Files: SavingsContributionHistoryRepository, directly affected gamification/callers/tests.

1. Validate every parsed event before prune or write: non-null compatible object, finite positive amount, valid goal ID/timestamp, supported supplied currency, and controlled bounded source.
2. Distinguish real empty history, legacy missing currency, and unreadable/incompatible history.
3. Preserve corrupt persisted bytes; return controlled corruption outcome and reject recording. Do not auto-rewrite as empty.
4. Callers handle recording failure; history-dependent achievements/streaks remain unavailable.

Tests: parseable arrays with null/invalid events, boundary amount/IDs/time/source, no-overwrite read/write, restart persistence, missing-currency legacy, achievements unavailable. Filters: `*SavingsContributionHistoryRepositoryTest*`, `*SavingsGamificationEngineTest*`, acceptance caller tests.

## Batch 6 — Source boundaries and original review verification (R18/R01–R12)

Files: BankStatementParser, BankStatementLifecycleProcessor; ReceiptParser/ReceiptLifecycleCoordinator; directly related tests. Verify before editing repaired behavior.

1. Missing source and unresolved/invalid home currency must stay unresolved; explicit valid source still works. Validate home target support, including timeout/empty settings.
2. Unresolved bank rows cannot enter actionable review/expense creation. Follow lifecycle legal path; no direct DAO mutation.
3. Explicit unsupported receipt currency fails closed even if supported evidence appears elsewhere; reject before lifecycle create/link.
4. Reconcile original acceptance: merchant transaction-ID ownership (null/blank/alias), tax allocation conservation, empty zero versus excluded/unresolved unavailable, currency-change recommendation rejection, pace owner quality, completion ordering, bounded contribution conversion counts.

Tests/filters: `*BankStatementParserTest*`, bank lifecycle regressions, `*ReceiptParserMoneyBoundaryTest*`, `*ReceiptLifecycleCoordinatorTest*`, `*ProcessReceiptUseCaseHomeCurrencyTest*`, existing merchant/tax/money/pace/savings regressions. Privacy and architecture guardians for lifecycle changes.

## Batch 7 — PDF policy and device evidence (R11/R20)

Files: AccountantReportPdfExporter, AccountantReportPdfMoneyTest, connected PDF test only if needed.

Verify per-source-currency sections and explicit deductible LATEST_AVAILABLE / BucketDatePolicy.Latest policy. Keep source arithmetic/counts/FX evidence separate from Android serialization/rendering. Test section totals, shared effective amounts, category/item/unallocated reconciliation, empty section, unsupported currency and missing conversion. Unit filters include `*AccountantReportPdfMoneyTest*`; connected-device PDF gate is separate. If unavailable, document NOT RUN and outstanding release gate.

## Batch 8 — Savings acceptance persistence (R16)

Architecture design gate before implementation, not blanket human permission: the user's request authorizes remaining fixes. Planner's preferred Room-authoritative design is provisional until the current owner/schema/backup paths are verified; a supported non-destructive migration follows repository procedure.

1. Inspect existing SavingsSweepPlan/DAO, Room transaction runner, savings repository, DatabaseWriteBarrier, backup/restore, history ownership, and UI acceptance callbacks with architecture/Room/privacy guardians.
2. Select a safe owner-level idempotent commit/ledger design, or a fully honest independently accepted allocation contract. Do not claim a Room transaction makes Room plus DataStore atomic.
3. If Room is authoritative: persist identity/allocation snapshots; conditional pending-to-accepted transition and goal revision/CAS checks; balance and contribution ledger commit in one owner transaction; no independent dual-write for authoritative sweep success.
4. Keep validated legacy DataStore history read-only unless explicit migration/reconciliation semantics are implemented. Define retry, cancellation rollback/propagation, duplicates, interruption/restart, stale recommendations, and visible failures before coding.
5. If schema changes: verify current version (map snapshots may be stale), add version/migration/schema snapshots and migration/backup tests, preserve data, no destructive migration.
6. UI must not offer a contract acceptance silently rejects; accepted outcomes require durable success.

Tests: multi-goal semantics, balance/history failure, cancellation, duplicate tap/retry, restart before/after commit, stale currency/provenance/revision, visible accepted/stale/unavailable states. Compile, targeted owner/DAO/ViewModel tests, migration profile, required backup and connected upgrade evidence. No destructive commands or schema shortcuts.

## Human-blocked cancellation ratchet (R13)

Persisted cancellation `vr-20261009-043313-5546468e` reports baseline117/current113 NEW0 RESOLVED4; these exact baseline entries are still present, count1 each:

- `G-CANCEL-01 app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`
- `G-CANCEL-01 app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
- `G-CANCEL-02 app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt`
- `G-CANCEL-02 app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`

No removal without explicit human-owner approval for the exact resolved keys. Recompute the complete resolved set after source fixes (new cancellation repairs may resolve additional entries); do not assume these four exhaust the final approval set. Preserve correct cancellation fixes. Reference `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03/FG-06/FG-07/FG-23; no waiver or guard weakening.

## Final freeze, evidence, documentation, and candidate boundary

1. Every batch gets strict review before runner validation, relevant guardians, meaningful targeted tests. Failed/unknown/stale/orphan records never PASS; one runner owner/process, poll active runs rather than rerun.
2. Update authoritative handoff/candidate paths before freeze; documents carry limitations and historical/final evidence separately. Any later doc/source edit changes the runner fingerprint.
3. Freeze one final tree: compile, repaired focused filters, callers/consistency/goldens, supported serial shards including integration/golden, migration, registered money/raw-money/cancellation guards, documented freshness procedure, full static suite. `stamp` is not assumed a runner profile. Counts may overlap.
4. Require terminal PASS/0, physical marker, meaningful logs, exact command, matching start/end/final fingerprints. Report task execution/cache honestly. Obtain independent reviewer acceptance of final candidate.
5. Candidate list is selection-only. Verify path/hunk ownership; no staging/committing/pushing. Preserve unrelated `.codex`, campaign/docs, `.codex-temp.patch`, `### VERDICT.txt`, generated outputs, and line-ending-only guard policy noise. Reviewed runner changes may remain candidates according to prior handoff; do not indiscriminately exclude or stage them.
6. Preserve all five untracked production helpers: ReceiptTaxAllocator, NormalizedAnalyticsMoneyAdapter, SingleCurrencyAnalyticsInput, NormalizedMoneyRow, SourceCurrencyAllocation. Reconcile direct tests and narrow docs too.
7. Device/connected gates executed or explicitly deferred with NOT RUN; no release/commit-ready claim while R13/independent/device/required behavior gates remain open.

Rollback: preserve mixed work. Reverse only authored hunks if needed, never destructive reset/clean. Released schema changes require forward migration; do not downgrade destructively.
