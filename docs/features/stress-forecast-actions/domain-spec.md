# Domain spec: stress-forecast-actions (UF-07, provisional T2)

Source: `C:\Users\panos\Desktop\Cost-agregator-int` @ integration/ai-evolution-1a e6967db1. Paths below are relative to `app/src/main/java/com/yourname/expensetracker/` unless prefixed `test:`.
Status: REVISED, all decisions closed (2026-10-26). Closed by the orchestrator applying all recommendations in `decision-sheet.md` ("Resolution") under the owner's standing instruction; the only remaining gate is el/es copy sign-off before merge (UX-6). Section 13 holds the closed table and follow-ups. Schema impact: NONE. Behavior-preserving except the three routing defects below.

## 1. Evidence verification (UF-07)
- CONFIRMED: `HomeScreen.kt:923-938` routes by `recommendation.contains("subscriptions"|"spending"|"emergency", ignoreCase=true)` in that order (`ui/screens/home/HomeScreen.kt`).
- CONFIRMED: engine emits hardcoded English `List<String>` at `domain/forecasting/FinancialStressForecastEngine.kt:692-738` (`generateRecommendations`); result field `StressForecastResult.recommendations: List<String>` at `:836`.
- CONFIRMED defects: (a) "Critical: ...delaying non-essential purchases..." (`:728`) and "Great job!..." (`:734`) match no substring -> chip is clickable but no-ops; (b) "No immediate concerns. Continue monitoring your spending." (`:737`) contains "spending" -> wrongly opens Transactions.
- CONFIRMED: `StressForecastSnapshot.recommendationsJson` (`data/database/entity/StressForecastSnapshot.kt:73`) is declared only; no `src/main` code outside entity/DAO/DI/AppDatabase migrations references the snapshot or the column (DAO `data/database/dao/StressForecastSnapshotDao.kt:19-91`, DI `di/DaoModule.kt:240`). Note: `BudgetForecast.kt:134` has a different table with a same-named column; out of scope.
- CORRECTED/ADDED: every chip is clickable regardless of kind (`ui/components/FinancialStressForecastCard.kt:194-198` always passes `onClick`); card shows only `take(2)` (`:194`), so with 4 simultaneous items the 3rd/4th are hidden; header "Recommendations" (`:186`) and "Min projected:" (`:272`) are hardcoded English; failure path emits `emptyList()` (`engine:193`) so the card hides the section (`:180`). `ComputeDashboardWidgetsUseCase.kt:241,:1268` only wraps the result in `DashboardWidget.FinancialStressForecast`; it has no recommendation logic.

## 2. Entities and value objects (domain, pure Kotlin, no Android resources)
```
enum class StressRecommendationKind(val action: StressRecommendationAction) {
  REVIEW_RECURRING_OBLIGATIONS(OpenRecurring), REDUCE_DISCRETIONARY_SPENDING(OpenTransactionsThisMonth),
  BUILD_EMERGENCY_BUFFER(OpenSavingsGoals), DELAY_NON_ESSENTIAL_PURCHASES(None),
  HEALTHY_OUTLOOK(None), NO_IMMEDIATE_CONCERNS(None) }
enum class StressRecommendationAction { OpenRecurring, OpenTransactionsThisMonth, OpenSavingsGoals, None }
data class StressRecommendation(val kind: StressRecommendationKind,
  val daysAhead: Int? = null,                 // only RUN-OUT/DISCRETIONARY: worst horizon (30|60|90)
  val bufferTarget: Double? = null,           // only BUILD_EMERGENCY_BUFFER; units = major units of currency below
  val bufferCurrency: String? = null)         // = StressForecastResult.displayCurrency
StressForecastResult.recommendations: List<StressRecommendation>   // was List<String> (:836)
```
Pattern precedent: domain sealed action + UI exhaustive `when` = `MoneyRadarAction` handled at `HomeScreen.kt:900-915`.
Money: `bufferTarget` is the exact `Double` the engine already compares (`getEmergencyBuffer()`, `engine:719`, in display currency, `:772`); domain applies no rounding; display rounding/scale is solely `CurrencyFormatter.formatMoney(..., showCents=false)` (same call as `FinancialStressForecastCard.kt:227`).

## 3. Engine branch -> kind (all emitters; order preserved; thresholds unchanged)
Inputs: `anyRisk = any horizon.riskLevel >= MODERATE` (`:699`), `highRisk = any >= HIGH` (`:700`).
| # | Kind | Condition (engine line) | Old English text | Action today |
|---|------|-------------------------|------------------|--------------|
| R1 | REVIEW_RECURRING_OBLIGATIONS | anyRisk && `first().recurringObligations > first().expectedIncome/daysAhead*30*0.8` (`:704-708`) | "Your recurring obligations are high. Consider canceling unused subscriptions." | Recurring (`HomeScreen:927`) |
| R2 | REDUCE_DISCRETIONARY_SPENDING | anyRisk && min-`projectedBalance` horizon `< 0` (`:712-716`); param `daysAhead` of that horizon | "You may run out of money in $d days. Consider reducing discretionary spending." | Transactions this month (`:930-932`) |
| R3 | BUILD_EMERGENCY_BUFFER | anyRisk && min `discretionaryBuffer` < emergency buffer (`:719-723`); param buffer amount | "Your emergency buffer is low. Aim for at least $X." | SavingsGoals (`:935`) |
| R4 | DELAY_NON_ESSENTIAL_PURCHASES | highRisk (`:727-729`) | "Critical: Consider delaying non-essential purchases..." | NO-OP (defect) |
| R5 | HEALTHY_OUTLOOK | !anyRisk && all `projectedBalance > emergencyBuffer` (`:732-735`) | "Great job! Your financial stress level is low..." | NO-OP (defect) |
| R6 | NO_IMMEDIATE_CONCERNS | list otherwise empty (`:737`) | "No immediate concerns. Continue monitoring your spending." | Transactions (defect) |
Notes: R5 is mutually exclusive with R1-R4. R6 is reachable at MODERATE risk when R1-R3 do not fire and !highRisk, and when !anyRisk but some balance <= buffer. `getEmergencyBuffer()` is read twice (`:719`, `:732`).

## 4. Kind -> destination (existing destinations only)
- OpenRecurring -> `onNavigateToRecurring()` (`HomeScreen.kt:927`).
- OpenTransactionsThisMonth -> `onNavigateToTransactions(TransactionFilter(dateRange = TimePeriodUtils.getMonthRange(state.referenceNowMillis)))` (`:930-932`).
- OpenSavingsGoals -> `onNavigateToFeature(NavigationDestination.SavingsGoals)` (`:935`).
- None (informational: DELAY_NON_ESSENTIAL_PURCHASES, HEALTHY_OUTLOOK, NO_IMMEDIATE_CONCERNS) -> rendered as plain text row, no chip semantics, no callback. No new destination/route is introduced.

## 5. Invariants (owner in brackets)
- INV-1 Each branch R1-R6 emits exactly one `StressRecommendation` of the kind in section 3; no free-text recommendation exists in the domain result. [FinancialStressForecastEngine.generateRecommendations]
- INV-2 Emission conditions, thresholds and order (R1,R2,R3,R4,R5; R6 only when empty) are unchanged by this feature; at most one item per kind. [engine]
- INV-3 `kind.action` is the only kind->intent mapping; UI maps `StressRecommendationAction` -> navigation with an exhaustive `when` (no `else`). [StressRecommendationKind / HomeScreen]
- INV-4 No UI code inspects recommendation text to route or branch (type system: UI never receives a String recommendation). [HomeScreen, FinancialStressForecastCard]
- INV-5 `action == None` kinds never invoke any navigation callback and render non-clickable. [FinancialStressForecastCard]
- INV-6 Routing/kind/params are locale-independent; text is resolved at render only via string resources keyed by kind (EN, EL, ES). [UI]
- INV-7 Domain/engine does not import Android resources or `CurrencyFormatter` for recommendation text (removes `engine:722`). [engine]
- INV-8 Params: `daysAhead` present iff kind==REDUCE_DISCRETIONARY_SPENDING and equals the worst horizon's `daysAhead` in {30,60,90}; `bufferTarget`/`bufferCurrency` present iff BUILD_EMERGENCY_BUFFER, currency == `result.displayCurrency`; other kinds carry no params. UI does no arithmetic on them. [engine; UI formats only]
- INV-9 Money text uses `CurrencyFormatter.formatMoney(bufferTarget, bufferCurrency, showCents=false)`; never the Home `homeCurrency ?: ""` fallback (`HomeScreen:922`) for this amount. If `bufferCurrency` is blank, render without an amount via a param-free string variant (no guessed currency). [UI]
- INV-10 `failure != null` or empty horizons -> empty recommendations; NO_IMMEDIATE_CONCERNS is never synthesized in UI or on failure (`engine:186-199`). [engine]
- INV-11 Visible cap stays 2 (`FinancialStressForecastCard.kt:194`) as one named constant; domain order decides which two show. [UI]
- INV-12 No persistence or schema change: `StressForecastSnapshot.recommendationsJson` is not written, read, or reformatted. [none]
- INV-13 No new logging of recommendation params; diagnostics remain bounded codes (`engine:188`). [engine]
- INV-14 (closed by ORD/impact-map verification) In `computeStressForecast`, the `StressForecastResult(` construction and the broad `catch (e: Exception)` keep their position, shape and relative order: no rename/reorder of the construction, no restructuring of the catch (it still rethrows `CancellationException` first, `engine:187`), and no earlier `StressForecastResult(` literal is added in that function. This is the anchor of the source-text scan at `test: contracts/CancellationPropagationContractTest.kt:75-76`; that test must pass unchanged after the engine edit. The engine file is not on `RAW_RUN_CATCHING_ALLOWLIST` (keyed by file name; `CancellationSafetyArchitectureGuardTest.kt:190`), so line shifts are guard-safe. [engine]
- INV-15 (D-PL) The R2 text is a `<plurals>` resource (quantity = `daysAhead`, number via `%1$d`) in `res/values`, `values-el`, `values-es`; quantity selection happens in UI only; resources are lint-clean (no MissingQuantity). [UI]
- INV-16 (UX-1) Information-only rows (action == None) use a neutral tint with no border and no chevron; actionable rows keep the chip and add a chevron. The distinction is never by colour alone (shape/chevron cue plus INV-5 semantics). [FinancialStressForecastCard]
- INV-17 (UX-7) The decorative bulb emoji is hidden from TalkBack (no content description, cleared semantics); the localized text is the only announced content. [FinancialStressForecastCard]
- INV-18 (O-3) The card header "Recommendations" (`FinancialStressForecastCard.kt:186`) is a string resource in en/el/es. "Min projected:" (`:272`) and all other card literals are unchanged in this feature (follow-up F-1). [FinancialStressForecastCard]

## 6. State machine
Stateless derivation: horizons -> recommendations on every compute; no stored state, retry = recompute, cancellation propagates (`engine:187` rethrows `CancellationException`), process death loses nothing (nothing persisted). Tap: action != None -> callback exactly once per tap; None -> no event. Illegal: a chip with action None being clickable; unknown action reaching UI (impossible with exhaustive `when`).

## 7. Edge cases
- Mixed currency/stale FX: `isPartial`/`qualityWarnings` (`engine:176-183`) unchanged; buffer is already display-currency, so no conversion in UI. Unavailable (failure) = empty list, distinct from "no concerns" (R6) and from healthy (R5).
- Zero income & zero recurring: `0 > 0` false, R1 silent. Zero income & recurring > 0: R1 fires (preserved).
- R6 at MODERATE risk is misleading ("no immediate concerns") but is preserved (see O-4).
- Rounding: buffer shown in whole units; domain value unrounded; no conservation concern (no splits/aggregates).
- Locale switch while visible: recomposition re-resolves text; kind/action unchanged. Plural/placeholder (D-PL closed): R2 is a `<plurals>` resource with `%1$d` days (INV-15); `%1$s` formatted money (positional, identical across EN/EL/ES). English only ever shows 30/60/90, but EL/ES grammar and lint require plurals.
- Time zones/DST/month-end: only R2's Transactions filter uses `referenceNowMillis` month range (existing); no new date logic. Past/recurring edits: not applicable.
- Duplicates/idempotency: recompute is pure; two identical computes give equal lists (data class equality).
- Empty states: empty list -> no section; `take(2)` over <2 items fine.

## 8. Text source (O-1 CLOSED: localize now)
Domain emits kind+params; UI maps kind -> `@StringRes` via exhaustive `when` and formats params. Keys proposed `stress_rec_<kind_lowercase>` in `res/values`, `values-el`, `values-es` (verify existing file layout; lint fails EN-only). EL/ES wording needs owner sign-off before merge (UX-6). R2 uses `<plurals>` (D-PL, INV-15). The card header "Recommendations" is localized in this change (O-3, INV-18); "Min projected:" and other card literals are follow-up F-1.

## 9. Persistence impact
None. `recommendationsJson TEXT` on the stress snapshot table stays nullable and unwritten; no `@Entity` change, no version bump, no migration, no backfill, nothing destructive. Recommendation: leave the column and its migrations untouched (removing it would be a destructive schema change needing approval).

## 10. Privacy
Content = bounded enum + two numeric params; nothing new is stored, exported, or logged. No raw amounts in Timber. Existing failure log (`engine:188`) is a bounded class-name code.

## 11. Acceptance examples (test-author)
- AE-1 (INV-1,2) Given horizons risk MODERATE, 30d recurring 900 vs income 1000 (avg 1000*0.8=800), all balances >= 0, buffers >= emergency buffer; When generate; Then `[REVIEW_RECURRING_OBLIGATIONS]`.
- AE-2 (INV-1,8) Given risk MODERATE, 60d projected -50 (worst), When generate; Then contains REDUCE_DISCRETIONARY_SPENDING with `daysAhead=60`.
- AE-3 (INV-1,8,9) Given risk MODERATE, min buffer 100 < emergency buffer 500, displayCurrency "EUR"; Then BUILD_EMERGENCY_BUFFER(bufferTarget=500.0, bufferCurrency="EUR"); UI text equals `formatMoney(500.0,"EUR",false)` in the string.
- AE-4 (INV-1,2) Given a HIGH horizon; Then list ends with DELAY_NON_ESSENTIAL_PURCHASES after R1-R3 items present.
- AE-5 (INV-1,2) Given all LOW and all projectedBalance > buffer; Then exactly `[HEALTHY_OUTLOOK]` (no R6).
- AE-6 (INV-1,2) Given all LOW and one projectedBalance <= buffer; Then exactly `[NO_IMMEDIATE_CONCERNS]`.
- AE-7 (INV-10) Given engine throws a non-cancellation Exception; Then `failure=CALCULATION_UNAVAILABLE`, recommendations empty.
- AE-8 (INV-3,5) Given each kind, When the Home mapper handles a tap; Then actions: R1->recurring once, R2->transactions(this-month filter) once, R3->SavingsGoals once; R4/R5/R6 -> zero navigation calls and chip not clickable.
- AE-9 (INV-6) Given kinds under locales en/el/es; Then same action routing, text differs, R6 never opens Transactions.
- AE-10 (INV-4) Given a recommendation whose localized text contains "spending"/"emergency"/"subscriptions" in any locale; Then routing is determined by kind only (R6 with "spending" in text -> no navigation).
- AE-11 (INV-11) Given four emitted items; Then UI shows the first two in domain order.
- AE-12 (INV-12) Given the change set; Then `app/schemas` and `AppDatabase.version` unchanged.
- AE-13 (INV-14) Given the engine edit is applied; When `contracts/CancellationPropagationContractTest` runs (source-text scan of `computeStressForecast`); Then it passes unchanged: the `StressForecastResult(` anchor is still found inside `computeStressForecast`, the broad catch still rethrows `CancellationException` first, and `computeStressForecast` contains exactly one `StressForecastResult(` literal before the catch.
- AE-14 (INV-15) Given R2 with `daysAhead` 30, 60, 90 under en, el, es; Then the text is resolved via `getQuantityString` on a `<plurals>` resource present in all three locale folders, contains the number, and lint reports no MissingQuantity/MissingTranslation.
- AE-15 (INV-16, INV-5, INV-17) Given kinds R4/R5/R6 (action None) and R1/R2/R3 (actionable) rendered in a Compose semantics test; Then None rows have no click action, neutral tint, no border, no chevron; actionable rows have click action and a chevron; the bulb emoji node has no TalkBack-announced text.
- AE-16 (INV-18) Given locales en/el/es; Then the "Recommendations" header resolves from a string resource in each locale, while "Min projected:" is unchanged (still the existing literal; F-1).

## 12. Affected existing tests (retarget only with owner approval)
- CONFIRMED (grep of all `app/src`, incl. androidTest): the six English phrases occur only in `FinancialStressForecastEngine.kt:708-737`; NO test asserts them, and no test (unit or androidTest) references `FinancialStressForecastCard` (only `HomeScreen.kt:920`). So retargeting count = 0 existing assertions; no owner approval needed for test rewrites.
- CONFIRMED: `StressForecastSnapshotDao` is only declared (`StressForecastSnapshotDao.kt:13`), exposed (`AppDatabase.kt:160`) and provided (`DaoModule.kt:240-241`); it has no callers, so the snapshot table/column is dead storage. `StressForecastResult` in `src/main` is touched only by the engine, the card, and `ComputeDashboardWidgetsUseCase.kt:242,1229,1253` (no serialization seen).
- Residual (O-6): three tests build `StressForecastResult` but were not inspected for the `recommendations` argument: `contracts/CancellationPropagationContractTest.kt:76`, `domain/usecase/dashboard/BlockPartyDstAlignmentTest.kt:143`, `.../FinancialRunwayNoBurnTest.kt:117`; they compile unchanged only if they pass `emptyList()`/omit it.
- Compile-compatible (`recommendations = emptyList()` / `.isEmpty()` stays valid for `List<StressRecommendation>`): test: `metrics/DashboardWidgetConsistencyTest.kt:126`, `data/repository/DashboardContractsAdapterTest.kt:298`, `integration/DashboardCurrencyIntegrationTest.kt:122`, `domain/usecase/dashboard/DashboardBudgetProvenanceChainTest.kt:239`, `ComputeDashboardWidgetsUseCasePaceWiringTest.kt:518`, `...DaysRemainingBoundaryTest.kt:107`, `...SafeToSpendQualityTest.kt:88`, `...InsightMoMTest.kt:108`, `domain/forecasting/FinancialStressForecastEngineTest.kt:95,:305`. New tests are additions (AE-1..AE-12), not rewrites.

## 13. Decisions (all CLOSED 2026-10-26) and follow-ups
Source: `decision-sheet.md` "Resolution" (orchestrator applied all recommendations under the owner's standing instruction of 2026-10-25). The owner may still override any item at the plan gate. No guard, test, destructive or permission item exists in this feature.

| ID | Decision (closed) | Spec effect |
|---|---|---|
| O-1 | Localize now: engine emits kind + params only; UI maps kind -> `@StringRes` (EN/EL/ES); engine stops formatting money | Sections 2, 8; INV-1, 6, 7 |
| O-2 | R4, R5, R6 are informational (action None): plain rows, no tap | Section 4; INV-5, 16 |
| O-3 | Localize the "Recommendations" header now; "Min projected:" and other card literals are a follow-up | INV-18; F-1 |
| O-4 | R6-at-MODERATE and `take(2)` are out of scope; emission rules unchanged. With today's order (R4 after R1-R3; R5 exclusive; R6 only when empty) an information-only row can never push out an actionable one inside `take(2)` | INV-2, 11; F-2, F-3 |
| O-5 / O-6 | `StressForecastSnapshot.recommendationsJson` untouched (not written, read or reformatted); no schema/migration (removing the column would be destructive) | INV-12; section 9 |
| D-PL | R2 text uses `<plurals>` (EL/ES grammar, lint-safe) | INV-15; AE-14 |
| UX-1 | Info-only rows: neutral tint, no border/chevron; actionable rows keep chip + chevron; not colour alone | INV-16 |
| UX-2 | Partial card localization (header only) accepted, with follow-up F-1 | INV-18; F-1 |
| UX-3 / UX-4 | "No concerns" at MODERATE and `take(2)` = follow-ups (same as O-4) | F-2, F-3 |
| UX-5 | Buffer amount uses the forecast's own `bufferCurrency` (= `displayCurrency`), not Home `homeCurrency`; add a currency-match check follow-up | INV-9; F-4 |
| UX-6 | EL/ES wording needs owner sign-off before merge (standing rule); the only remaining gate | Section 8 |
| UX-7 | Bulb emoji hidden from TalkBack | INV-17 |
| ORD | QF-2 (Money Radar, ~5 lines away in `HomeScreen.kt`) lands FIRST; this feature rebases onto it; one writer on `HomeScreen.kt` at a time (or one lane). No edit to the dashboard calculation file (CDW), so the briefing S-fix ordering is not needed | Planning only |
| R-1 | T2: one cheap review with a money-formatting check; no reviewer-strict. Validation runner: targeted tests (incl. `CancellationPropagationContractTest`), lint once, `static-guards` once | Planning only |

Standing constraints (carried from former O-5): no guard/allowlist/baseline edits (type-based INV-4 avoids a new source-scan guard); no test deletion or rewrite (none required); stray `data/database/_schema_tmp.json` in `src/main` is unrelated, do not touch.
Pre-coding check for the planner (not a decision): re-confirm nothing serializes `StressForecastResult` (dashboard cache), no androidTest/Compose test references the card or old strings, and the three residual tests in section 12 pass `emptyList()`/omit `recommendations`.

### Follow-ups (out of scope; none blocks this feature)
- F-1 Localize the rest of `FinancialStressForecastCard` (e.g. "Min projected:" `:272` and every other English literal) as one whole-card task.
- F-2 R6 "No immediate concerns" firing at MODERATE risk (misleading). Changing it alters emission rules (INV-2) and needs a human-approved forecast-behaviour change.
- F-3 `take(2)` cap hides the 3rd/4th item (INV-11): add a "+N more" affordance or revisit the cap.
- F-4 Verify `StressForecastResult.displayCurrency` always equals Home `homeCurrency` for the card's other amounts; until verified the buffer keeps using `bufferCurrency` (INV-9).

Open decisions: none. Only gate: el/es copy sign-off before merge (UX-6).
