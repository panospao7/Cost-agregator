# Feature plan: stress-forecast-actions (UF-07)

**Status: APPROVED (owner "I approve", plan gate 2026-10-26), NOT STARTABLE (owner: planning only; after QF-2 or rebased onto it).**

## Overview
- **Provenance:** feature-planner wrote this plan (read-only, Sonnet) and the orchestrator persisted it.
- **Decisions:** the orchestrator applied the decision-sheet recommendations under the standing owner instruction. The owner may override any item at this gate.
- **Risk tier:** T2. No schema, guard, allowlist, baseline or permission change. No existing assertion is edited.
- **Worktree:** `Cost-agregator-sfa`, branch `feature/stress-forecast-actions`, cut from integration e6967db1 (or from integration after QF-2).
- **Lane:** one writer lane. The implementer and the test-author are separate agents. Only `validation-runner` runs builds, tests and guards.
- **Path keys:** M = `app/src/main/java/com/yourname/expensetracker/`, T = `app/src/test/java/com/yourname/expensetracker/`, R = `app/src/main/res/`.
- **Authority order:** decision-sheet, then impact-map, then ux-flows, then domain-spec.

## S-pre: re-anchor (read-only; a Sonnet scout, then the orchestrator)
- **Re-anchor line refs:**
  - `HomeScreen.kt:919-940`: the substring `when` at `:925-937`, and `homeCurrency ?: ""` at `:922`.
  - Card `:180-201`, and `RecommendationChip` at `:326-358`.
  - Engine `:692-738`: `CurrencyFormatter.format` at `:722`, the class at `:832-842`, and the constructions at `:169` and `:189`.
- **Check QF-2:**
  - If it is merged on integration, base on it. If it isn't, stop and report; S1/S2 do not start.
  - Record its `HomeScreen.kt:909-914` hunk and confirm it doesn't overlap.
  - Settle the QF plan's own inconsistency about whether QF-2 edits HomeScreen (lines 24/56/135 vs 197).
- **Read `CancellationPropagationContractTest.kt:60-90`.** Record the anchor semantics: the first `"StressForecastResult("` inside `computeStressForecast`, plus the catch rethrow check. Note whether the scan also matches comments and KDoc.
- **Confirm the ripple is compile-only.** Run `rg "StressForecastResult"` and `rg "recommendations"`, and confirm nothing serializes the result.
- **Ask validation-runner for `-Action Plan` / `-Action Profiles`** to get the filter grammar and the names of the dashboard and home shards.
- **Check two signatures:** that `CurrencyFormatter.formatMoney(Double, String, showCents)` exists as written, and whether `TimePeriodUtils.getMonthRange` is pure.
- **Strings anchor:** confirm `stress_forecast_unavailable` at `values/strings.xml:2682` and its counterparts in el and es.
- **Done when:** the anchors are recorded, QF-2 has landed or been rebased in, and nothing surprising turned up. Otherwise, return to the owner.

## S1: domain types and engine (implementer, then test-author)
- **Invariants:** INV-1, 2, 7, 8, 10, 12, 13. INV-3 is the kind→action mapping.
- **Source:**
  - NEW `M/domain/forecasting/StressRecommendation.kt`, Android-free, containing:
    - `StressRecommendation(kind, daysAhead?, bufferTarget?, bufferCurrency?)`;
    - `StressRecommendationKind(val action)`, with 6 kinds;
    - `StressRecommendationAction`: `OpenRecurring`, `OpenTransactionsThisMonth`, `OpenSavingsGoals`, `None`.
  - EDIT `M/domain/forecasting/FinancialStressForecastEngine.kt`:
    - `generateRecommendations` returns `List<StressRecommendation>`, with the same conditions, thresholds and order. `StressForecastResult.recommendations` changes type to match.
    - Drop `CurrencyFormatter.format` at `:722`, and its import if this was the only use. `displayCurrency` becomes `bufferCurrency`.
    - **Keep the `StressForecastResult(` construction and the catch in `computeStressForecast` where they are.**
      - Do not add an earlier `StressForecastResult(` literal in that function, including in comments or KDoc if the scan matches them.
      - The failure path keeps `recommendations = emptyList()`, and its bounded log stays.
- **Tests** (test-author, additions only):
  - EDIT `T/domain/forecasting/FinancialStressForecastEngineTest.kt`, adding AE-1..AE-7:
    - AE-3: `bufferTarget == 500.0` and `bufferCurrency == "EUR"`;
    - AE-2: `daysAhead ∈ {30,60,90}`;
    - AE-7: failure → `CALCULATION_UNAVAILABLE` with an empty list.
  - NEW `T/domain/forecasting/StressRecommendationKindTest.kt`: each kind's action, and R4/R5/R6 → `None`.
- **Legal path:** `ComputeDashboardWidgetsUseCase` is not edited. No writes. `recommendationsJson` and `StressForecastSnapshot*` are untouched.
- **Gates:** part of the single cheap review (INV-2 diff, cancellation anchor).
- **Validation:** none of its own; S1 is batched with S2. It is one release unit with S2, because the type change can't sit behind a flag.
- **Done when:**
  - the cancellation anchor is intact;
  - the emission conditions are unchanged;
  - the domain has no `CurrencyFormatter` or Android import;
  - the 10 other `StressForecastResult` test sites are untouched.

## S2: card, HomeScreen, mapper, strings in 3 locales (implementer, then test-author)
- **Invariants:** INV-3, 4, 5, 6, 9, 11, plus formatting-only INV-8. Routing does not depend on locale.
- **Card:** EDIT `M/ui/components/FinancialStressForecastCard.kt`.
  - **Callback and text:**
    - `onActionClick` takes a `(StressRecommendationAction)`.
    - An exhaustive `when` on the kind picks the string resource.
    - R2 uses `pluralStringResource(id, daysAhead, daysAhead)`.
  - **R3 amount:**
    - Show it with `CurrencyFormatter.formatMoney(bufferTarget, bufferCurrency, showCents = false)`, using `bufferCurrency`, not the card's currency.
    - If `bufferCurrency` is blank, use `stress_rec_build_emergency_buffer_no_amount`.
  - **Informational rows** (`None` kinds): neutral tint; no border, chevron, `onClick` or role; no 48dp minimum.
  - **Actionable rows:**
    - keep `RecommendationChip`;
    - add a trailing `ChevronRight` (`contentDescription = null`, tint `TextSecondary`);
    - set `role = Role.Button`;
    - give each one an `onClickLabel` from `stress_rec_action_*`.
  - **Accessibility:** hide the bulb from TalkBack. The header becomes `stress_recs_header` with `heading()`.
  - **Two-item cap:** `take(2)` becomes a named constant, with an `internal fun` that returns the visible items (INV-11).
  - **Layout and literals:** no `maxLines` and no fixed heights. Other literals stay English as follow-ups.
- **HomeScreen:** EDIT `M/ui/screens/home/HomeScreen.kt:919-940`, rebased onto QF-2.
  - Use an exhaustive `when (action)` with no `else`:
    - `OpenRecurring` → `onNavigateToRecurring()`;
    - `OpenTransactionsThisMonth` → `onNavigateToTransactions(TransactionFilter(dateRange = TimePeriodUtils.getMonthRange(state.referenceNowMillis)))`;
    - `OpenSavingsGoals` → `onNavigateToFeature(NavigationDestination.SavingsGoals)`;
    - `None` → nothing.
  - `homeCurrency ?: ""` is used only for the horizon amounts.
  - **Touch only this hunk.** Do not touch the QF-2 `:909-914` hunk.
- **Mapper:** NEW `M/ui/screens/home/StressRecommendationActionMapper.kt` (optional, recommended). A pure dispatcher that takes the action, three lambdas and `referenceNowMillis`, and no text. It makes AE-8..AE-10 unit-testable.
- **Strings:** EDIT `R/values{,-el,-es}/strings.xml`. Append one feature-scoped block next to `stress_forecast_unavailable`, using positional placeholders only. These are the 11 keys from ux-flows (`stress_recs_header`, the 7 row keys including one `<plurals>`, and the 3 click labels).
  - **Copy:**
    - EN for R1, R2, R3 and R6: verbatim from the engine.
    - EN for R4 and R5: copy the **full** text from engine `:728` and `:734`. The specs quote it truncated.
    - EL and ES: drafted by the implementer and flagged for owner sign-off.
  - **Plurals:** check plural quantities against lint (`MissingQuantity`, `ImpliedQuantity`).
- **Tests** (test-author, NEW only):
  - `T/ui/screens/home/StressRecommendationActionMapperTest.kt`: AE-8, AE-9, AE-10.
    - Each action fires its own callback exactly once.
    - R4, R5 and R6 fire no callback.
    - The mapper takes no text input.
  - `T/ui/components/StressRecommendationVisibleCapTest.kt`: AE-11. With 4 items, the first 2 show, in domain order.
  - AE-12 is a review check: no change under `app/schemas`, to the `@Database` version, or to any entity or DAO.
- **Gap to record in the handoff:** non-clickability, TalkBack roles and 200% font scale can only be checked manually or in review.
- **Legal path:** only the existing HomeScreen callbacks and destinations. No new route, permission, network call or write.
- **Depends on:** S1 (same compile batch), S-pre, QF-2.
- **Done when:**
  - nothing in the hunk routes by text (`rg "contains\("` returns nothing);
  - all 11 keys exist in en, el and es;
  - no new `@Suppress`, `@Ignore` or allowlist entry;
  - the batch is green.

## S3: docs (only if needed)
- **Write set:** `docs/architecture/CODEBASE_SEGMENTS.md` and/or `CODEBASE_INVENTORY.md`. Add one line, and only if those docs list the new files.
- **Gate:** FG-26 only.
- **Otherwise:** record "S3 not needed".

## Validation (validation-runner only, one batch)
1. **Compile** S1 and S2 together.
2. **One `targeted-unit-test` run** with these filters:
   - `*FinancialStressForecastEngineTest*`, `*CancellationPropagationContractTest*`
   - `*DashboardContractsAdapterTest*`, `*DashboardWidgetConsistencyTest*`
   - `*BlockPartyDstAlignmentTest*`, `*FinancialRunwayNoBurnTest*`
   - `*HomeViewModelWidgetsTest*`, `*DashboardWidgetRenderCoverageTest*`
   - `*StressRecommendation*`, `*ComputeDashboardWidgets*`
3. **`lint` once,** after rebasing the shared strings files (FG-20).
4. **`static-guards` once** at the candidate (FG-10, 07, 16, 17, 19).

Notes:
- There is no full `unit-tests` or `app-check` run. A named shard is used only if the targeted run is too slow.
- A missing, stale or errored result is never a PASS. RUNNING is not a failure.

## Review
- **T2: one cheap Sonnet review after S1+S2.** It checks:
  - **Money formatting:** `bufferCurrency` is used, `showCents = false`, a blank currency gives the no-amount string, and the UI does no arithmetic.
  - **Code shape:** exhaustive `when`s with no `else`, and the cancellation anchor and catch are intact.
  - **Scope:** no change to the emission rules (INV-2).
  - **Diff:** no guard, allowlist, baseline, schema or assertion changes.
- **No reviewer-strict.** Architecture-guardian is used only if the diff becomes cross-segment.
- **Limit:** at most 2 review and 2 debug iterations, then PARTIAL/BLOCKED.

## Stop-gates (return to the owner)
- Any guard, allowlist or baseline change.
- Any schema change, including removing `recommendationsJson`.
- Any test deletion or rewrite, or any change to the emission rules.
- Making R4 navigable, or changing R6-at-MODERATE or `take(2)`.
- Editing `ComputeDashboardWidgetsUseCase.kt`.
- Merging EL/ES copy without sign-off.
- QF-2 not landed, and not rebased, by S2.
- A surprise at S-pre, such as something that serializes `StressForecastResult` or a Compose test on the card.

## Human decisions remaining
- EL/ES sign-off before merge.
- Lift "planning only", and confirm QF-2 goes first.
- Merge and PR stay on owner request.

## Follow-ups (not in this feature)
- Localize the rest of the card: "Min projected:", "Stress Forecast", the tabs, and `:35/:77/:221/:246/:272`.
- R6 appears at MODERATE risk.
- A hidden 3rd or 4th recommendation has no "+N more".
- Check that `displayCurrency == homeCurrency`.
- `recommendationsJson` is dead storage. Removing it is destructive and needs approval.
- Add Compose semantics tests for the card.

## Top risks
1. **HomeScreen conflict with QF-2** (`:909-914`, about 5 lines away). Use one writer, land QF-2 first, and rebase before S2.
2. **`CancellationPropagationContractTest` source scan.** A rename, reorder, comment or earlier literal breaks it. Run it right after the engine edit.
3. **Money formatting.** Watch for the wrong currency (the `""` fallback), a blank currency, or arithmetic in the UI.
4. **Accessibility checks are manual or review only.**
5. **The strings files are shared,** including with the parked briefing-grounded-signals. Use a feature-scoped block, rebase before lint, and watch plural-quantity lint.
6. **Copy risks:** EL/ES wording isn't signed off yet, and the R4/R5 English copy is truncated in the specs (copy it verbatim from the engine).

## Exact first action when unblocked
1. Lift "planning only", and confirm QF-2 is on integration (or rebase onto it).
2. Run `git -C C:\Users\panos\Desktop\Cost-agregator-int worktree add -b feature/stress-forecast-actions C:\Users\panos\Desktop\Cost-agregator-sfa <integration tip after QF-2>`.
3. Send the S-pre anchor list to a Sonnet `scout`, and ask validation-runner for `-Action Plan` / `-Action Profiles`.
4. Start S1 only after S-pre comes back clean.

## Re-anchor amendment (2026-11-02, orchestrator)
The owner lifted "planning only" on 2026-11-02; owner-approvals 101 is the standing instruction.
- **Base:** `feature/uf-quick-fixes` @ c8149f25, which carries QF-2a/2b/2c and QF-3a/3b/3c, validated in Batch A. That base is `a5d659de`, not e6967db1. The QF-2 prerequisite (the HomeScreen `:909-914` Money Radar hunk, QF-2c ed431d51) is therefore present.
- **Merge ordering:** this lane merges after feature/uf-quick-fixes, or together with it.
- **Re-anchor (explorer at a5d659de):** report in `docs/features/stress-forecast-actions/reanchor-20261102.md`.
  - `HomeScreen.kt:919-940` holds (`when` :925-937, `homeCurrency` :922). QF-2c removed 3 lines above it, so re-check the offsets at S-pre.
  - FinancialStressForecastCard is unchanged (:27, :180, :194, :327).
  - The engine drifted about +86 lines:
    - `generateRecommendations` :778-824;
    - `CurrencyFormatter.format` :808, import :29;
    - class :918;
    - `StressForecastResult(` :212 (success) and :232 (catch), rethrow :230;
    - `calculateHorizon(` :162-196;
    - R4/R5 copy :794/:801.
  - `CancellationPropagationContractTest` is in the `contracts/` package, entry :75-76. The KDoc :106 has no "(", so it is safe.
  - `stress_forecast_unavailable`: en `strings.xml:2790`, el :66, es :338. No `stress_rec*` collision.
- **fix-1.0.4 lane** (unmerged, another session):
  - No edits to the engine, HomeScreen or the Card.
  - It changes `MonteCarloSpendingSimulator` (BL-01), injected at engine :62, so engine golden numbers may shift: rebase.
  - strings.xml: a one-line rebase.
- **Findings:** BL-09 and P-06-4 are adjacent engine risk/buffer logic. NOT folded in; do not fix them in this lane.
- **es/el:** sign-off is deferred to the release gate (owner-approvals 103), so it does not block the merge.
