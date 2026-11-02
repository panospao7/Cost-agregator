# Impact map: stress-forecast-actions (UF-07)

impact-mapper wrote this map (read-only, Sonnet, `Cost-agregator-int` @ e6967db1). The orchestrator persisted it on 2026-10-26 and appended the "Orchestrator verification" section.

- **Inputs:** `domain-spec.md`, `ux-flows.md`.
- **Path keys:** M = `app/src/main/java/com/yourname/expensetracker/`, T = `app/src/test/java/com/yourname/expensetracker/`.
- **Spec claims hold:** HomeScreen `:919-940`, card `:180-201`, engine `:692-738` and `:722`.

**Tier: T2 (medium).**

## 1. Files
- **Domain**
  - EDIT `M/domain/forecasting/FinancialStressForecastEngine.kt`:
    - `generateRecommendations` (`:692-738`) returns `List<StressRecommendation>` instead of `List<String>`.
    - Drop `CurrencyFormatter.format` at `:722`. `displayCurrency` stays and becomes `bufferCurrency`.
  - The `StressForecastResult` constructors live in the same file:
    - success path `:169`;
    - failure path `:189` (`recommendations = emptyList()` at `:193`).
  - The data class is at `:832-842`, with `recommendations` at `:836`.
  - Remove the `CurrencyFormatter` import only if `:722` was its only use.
  - NEW `M/domain/forecasting/StressRecommendation.kt` (recommended) holds `StressRecommendation`, `StressRecommendationKind` and `StressRecommendationAction`.
  - **No edit** to `ComputeDashboardWidgetsUseCase.kt` (pure wrapper at `:241-242`, `:351`, `:1229-1231`, `:1253`, `:1268`) or `DashboardWidgetRegistry.kt:97`.
- **UI**
  - EDIT `M/ui/components/FinancialStressForecastCard.kt`:
    - `onActionClick` (`:25-29`) changes from `(String)` to `(StressRecommendationAction)`.
    - The loop at `:194-200` maps each kind to a `@StringRes` with an exhaustive `when`, and adds a non-clickable branch for `None` (INV-5).
    - Add an informational-row composable next to the private `RecommendationChip`.
    - `take(2)` becomes a named constant (INV-11).
    - The header at `:186` becomes a string resource. "Min projected:" (`:272`) and the other literals (`:35`, `:77`, `:221`, `:246`) stay as follow-ups.
    - The buffer amount uses `CurrencyFormatter.formatMoney(bufferTarget, bufferCurrency, showCents = false)`.
  - EDIT `M/ui/screens/home/HomeScreen.kt:919-940`. The substring `when` at `:925-937` becomes an exhaustive `when (action)` with no `else`:
    - `OpenRecurring` → `onNavigateToRecurring()`;
    - `OpenTransactionsThisMonth` → `onNavigateToTransactions(TransactionFilter(dateRange = TimePeriodUtils.getMonthRange(state.referenceNowMillis)))`;
    - `OpenSavingsGoals` → `onNavigateToFeature(NavigationDestination.SavingsGoals)`;
    - `None` → no-op.
    - `homeCurrency ?: ""` (`:922`) stays for the horizon amounts only (INV-9).
    - Option: extract a pure `StressRecommendationActionMapper` so AE-8..11 can be unit-tested. No Compose tests exist today.
- **Strings:** `values/`, `values-el/` and `values-es/strings.xml`. Append a feature-scoped block next to `stress_forecast_unavailable` (`values/strings.xml:2682`). `ux-flows.md` lists 11 keys.
- **Persistence:** none. `recommendationsJson` is untouched and has no DAO callers. No version bump.

## 2. Consumers
- **Production readers of `recommendations`:** only the engine (the producer) and `FinancialStressForecastCard.kt:180,194-197`.
- **Other references to the types:** `ComputeDashboardWidgetsUseCase` and `DashboardWidgetRegistry` only wrap the result, and HomeScreen `:919-922` passes it through.
- **Mentions in comments only:** `CashFlowCalculator.kt:242,624`, `ForecastInputAssembler.kt:473`, `domain/model/FinancialForecast.kt:14`.
- **No AI prompt, briefing, notification, export, diagnostics or widget reads the English text.** Nothing serializes `StressForecastResult`. The ripple is compile-only.
- Re-run `rg "StressForecastResult"` on the final tree.

## 3. Tests
- **Compile-clean as they are** (each passes `recommendations = emptyList()`):
  - `metrics/DashboardWidgetConsistencyTest.kt:126`
  - `integration/DashboardCurrencyIntegrationTest.kt:122`
  - `data/repository/DashboardContractsAdapterTest.kt:298`
  - `BlockPartyDstAlignmentTest.kt:149`
  - `FinancialRunwayNoBurnTest.kt:123`
  - `ComputeDashboardWidgetsUseCasePaceWiringTest.kt:518`
  - `…DaysRemainingBoundaryTest.kt:107`
  - `…InsightMoMTest.kt:108`
  - `DashboardBudgetProvenanceChainTest.kt:239`
  - `…SafeToSpendQualityTest.kt:88`
- **Valid without changes:** `FinancialStressForecastEngineTest.kt:95,305` (`isEmpty()`).
- **`contracts/CancellationPropagationContractTest.kt:75-76` is a source-text scan.** It anchors on `"StressForecastResult("` inside `computeStressForecast` and checks that the broad catch there rethrows CancellationException. So:
  - **do not** rename or reorder that construction;
  - do not restructure the catch;
  - do not add an earlier `StressForecastResult(` literal in that function.
  - Run this test after the engine edit.
- **Assertion changes needing owner approval: 0.** No test asserts the English text. All new tests are additions:
  - AE-1..7 in `FinancialStressForecastEngineTest`;
  - NEW `StressRecommendationKindTest`;
  - NEW `T/ui/screens/home/StressRecommendationActionMapperTest.kt` (AE-8..11).

## 4. Overlap and ordering
- **briefing-grounded-signals** (parked, post-1.0):
  - Its S-fix edits CDW at `:128,336-342,1352,1375-1408`. This feature doesn't edit CDW, so there is **no CDW conflict**, and the triage rule "after briefing S-fix" is not needed.
  - Its S3/S4 HomeScreen hunks (`:527-582`, `:819-841`) are far from `:919-940`, so expect rebase noise only.
  - The strings files are shared. Use a feature-scoped append block and rebase before lint.
- **ai-fixes:** no overlap on HomeScreen, the card or CDW.
- **uf-quick-fixes QF-2:**
  - It edits HomeScreen `:909-914` (the MoneyRadar AdjustBudget `if`, which sits **about 5 lines from** `:919`), plus `:1645`, `:1708` and `:1760-1769`.
  - **Textual-conflict risk.** Keep one writer on `HomeScreen.kt` at a time. Land QF-2 first, then UF-07 rebased; or put both in one lane.
  - The QF plan contradicts itself about whether it edits HomeScreen (lines 24/56/135 vs 197). Re-check this at its S-pre.

## 5. Guards
- **No source-scan guard affected.** There is no domain→Android import guard, no HomeScreen string guard and no hardcoded-string FG. The domain stays Android-free by design (INV-6/7).
- **FG in scope:**
  - FG-10: catch retention in `computeStressForecast`.
  - FG-12: trivial.
  - FG-16: no new logging.
  - FG-17: formatting moves to `formatMoney` in the UI, with no new aggregate.
  - FG-20: EN/EL/ES completeness.
  - FG-07: no-growth ratchets.
  - FG-19: no `@Ignore`.
  - FG-26: docs, only if they change.
- **No guard, allowlist or baseline edit expected.**

## 6. Tier, review, validation, stop-gates
- **Tier: T2.**
  - It changes a domain-model contract, adds strings in 3 locales, and changes money formatting in a display string.
  - It has no schema, worker, permission or privacy surface, persists nothing, and has a single UI consumer.
- **Review:** no T3 guardians and no reviewer-strict needed. One cheap review covers it, with a money-formatting check.
  - The orchestrator adds architecture-guardian **only if** the slice ends up cross-segment. Domain → UI in a single seam does not count.
  - The owner signs off the EL/ES wording.
- **Targeted filters:**
  - `*FinancialStressForecastEngineTest*`, `*CancellationPropagationContractTest*`
  - `*DashboardContractsAdapterTest*`, `*DashboardWidgetConsistencyTest*`
  - `*BlockPartyDstAlignmentTest*`, `*FinancialRunwayNoBurnTest*`
  - `*HomeViewModelWidgetsTest*`, `*DashboardWidgetRenderCoverageTest*`
  - `*StressRecommendation*`, `*ComputeDashboardWidgets*`
- **Then:** `lint` once, and `static-guards` once at the candidate (FG-10).
- **Stop-gates:**
  - any guard, allowlist or baseline change;
  - any schema change, including removing `recommendationsJson`;
  - any test deletion or rewrite, or a change to the emission rules (INV-2);
  - making R4 navigable, or changing R6-at-MODERATE;
  - EL/ES wording without sign-off;
  - editing CDW.

## 7. Slice sketch
- **S1 Domain:** the types file (NEW), the engine edit, engine test additions and the kind test. It compiles together with S2, as one batch.
- **S2 UI and strings:** the card, HomeScreen `:919-940`, the optional mapper and its test, and the strings in 3 locales.
- **S3 Docs:** only if the segment docs list the new file.
- **Order:** S1 then S2 in one lane, with one writer for `HomeScreen.kt` and the strings. QF-2 lands before S2, or S2 rebases onto it.

## 8. Owner questions (mapper recommendations)
- **O-1:** localize now. Yes.
- **O-2:** R4, R5 and R6 are informational.
- **O-3:** localize the header now. "Min projected:" and the other literals are a follow-up.
- **O-4:** R6-at-MODERATE and `take(2)` are out of scope (follow-up). Because R4 is emitted after R1–R3, the behaviour is preserved.
- **Plural:** with horizons of only 30/60/90, a plain `%1$d days` would do. `ux-flows.md` specifies `<plurals>` for EL/ES grammar. See D-PL in the decision sheet.
- **Ordering:** QF-2 first, then UF-07.

## Orchestrator verification (2026-10-26, read-only)
- **The allowlist-format concern is closed.**
  - `RAW_RUN_CATCHING_ALLOWLIST` lives in `CancellationSafetyArchitectureGuardTest.kt:190`.
  - It is keyed by **file name** (`RawRunCatchingAllowlistEntry("<File>.kt", …)`), not by line or snippet.
  - On integration, `FinancialStressForecastEngine.kt` **is not on the list**: the entries are RestoreJournalImporter, AnalyticsRepository, DatabaseBackupRepositoryImpl, CarbonFootprintCalculator, DiagnosticsRepository, InvestmentTracker and RestoreDiagnosticsSink.
  - So line shifts in the engine can't invalidate any entry. The audit note (`test-suite-audit-b1r-map.md:33`) described an older state.
