# money-aggregates-sweep — slice plan, Increment 1 (fail-closed visible totals)

Stage 6 (feature-planner, read-only), persisted by the orchestrator 2026-10-21. Inputs: docs/features/money-aggregates-sweep/{brief.md v2, domain-spec.md (INV-1..20, AE-1..19), impact-map.md}; decisions in workflows/active/planning-finalization-handoff.md (money entries). Paths: M = app/src/main/java/com/yourname/expensetracker/, T = app/src/test/java/com/yourname/expensetracker/, R = app/src/main/res/.

**Tier:** T3. No schema, no writes, no new writer, no conversion, no allowlist.
**Status:** PLANNED. Implementation NOT started. OG-1 and OG-2 APPROVED by owner 2026-10-22 (see build/release-lead-notes/owner-approvals-20261022.md). Implementation still gated by the plan's own gates.

## §0 Sequencing
- Inc 1 does not depend on ai-fixes or money-correctness 1a. group-settle-up comes after it. Inc 3 waits on owner gates.
- app-lock S6 writes AnomalyAlertOrchestrator.kt, BudgetMonitor.kt and their tests. One writer per file per wave; whichever lands second rebases and re-checks config/baselines/cancellation.json counts. Only money S5 removes G-CANCEL-02. Update app-lock-privacy-mode-plan.md:450 note once S5 merges.
- capture-health and others also edit LEGAL_PATHS.md and strings.xml. S6 runs only when no other LEGAL_PATHS writer is in flight. strings.xml: one writer per wave.
- money-correctness 1a / G2-15 edits ManualRecurringExpenseViewModel.kt:108-116, the same file as S3. Serialise per file.
- money-correctness inc 2 (F-4 + offset FX basis) starts only after S4 merges, rebasing onto the new contract. F-4 is the same defect as S4; mark it in that plan.

## §1 S0 — decision record and preflight (paper)
- Adopted: D1–D5; OPEN-1 (cards labelled with group.currency); OPEN-2 (detail rows unchanged); OPEN-3; OPEN-4; OPEN-5 (`EFFECTIVE_SPEND_UNAVAILABLE`); OPEN-6 (test-only changes, no deletions); OPEN-7 (keep VM fallback); OPEN-8 (EUR grep covers touched sites only).
- Doc disagreements resolved (no new decisions):
  - R1. Engine signature unchanged. "Unavailable" = throwing existing `MoneyDisplayUnavailableException` (spec §2, INV-15). The ~8 mocking test files stay untouched.
  - R2. Totals keep `Double` `sumOf { it.totalAmount }` so AE-5 stays bit-identical (spec §0). FG-17 is satisfied by naming: `includedTotal`, `totalMonthly`; no `total: Double`, no `.sumOf { it.amount }`. No allowlist.
  - R3. Budget percent/health stay computed by the repository, unchanged (INV-17). When the breakdown is null the card does not show them (AE-17 forbids a percent in the content description).
  - R4. `BudgetRepository.computeAdjustedSpend` already maps non-cancellation failures to null and rethrows cancellation (:399-403). S4 changes only its KDoc. The Timber line (:401) is untouched (privacy-guardian route).
  - R5. AE-1 scan check = reviewer grep + behavioural equality test. No new source-scanning architecture test (guard growth).
  - R6. OPEN-3 uses `domain/core/money/CurrencyCode` and `domain/currency/SupportedCurrency` (both domain; no layering change).
  - R7. Every new failure boundary uses `CancellationSafe.runCatchingCancellable`. No new broad catch, no new baseline entry.
- Preflight before W1: re-anchor LEGAL_PATHS by heading; record current cancellation.json values (G-CANCEL-01 BudgetViewModel = 1 at :28/:71; G-CANCEL-02 AnomalyAlertOrchestrator = 1 at :35/:78); confirm `EFFECTIVE_SPEND_UNAVAILABLE` needs no guard-owned registry entry (else STOP, OG-3); confirm app-lock S6 and capture-health LEGAL_PATHS slice are not in flight.

## §2 Slices
**S1 — GroupSettlementBalancePolicy.displayedBalances + tests** (INV-2,3,4,5,6,9; domain half of INV-1; counts for INV-8)
- Add nested Availability and DisplayedBalances and pure `displayedBalances(...)` (spec §2). `totals()` / `applyToBalances()` unchanged.
- Write (source): M/domain/groups/GroupSettlementBalancePolicy.kt. Tests: T/domain/groups/GroupSettlementBalancePolicyTest.kt (extend). GroupBalanceCalculatorTest run, not edited.
- Covers AE-2,3,4,5,6 (property/table, all-EUR), AE-9. Edge cases: empty group; all expenses excluded with a same-currency settlement (PARTIAL, total 0.0); "eur" vs "EUR"; NaN in an excluded row counted without evaluating it.
- Legal path: read-only, pure. No new callers of GroupBalanceCalculator or calculateBalances(). Dead callers compile unchanged.
- Gates: FG-12, FG-17, G-MONEY-11, FG-19. T3, reviewer-strict.
- Validation: targeted `*GroupSettlementBalancePolicyTest*`, then `*GroupBalanceCalculatorTest*`.
- Done when: AEs pass by runner run ID; existing tests green with no edits to old cases; no production caller yet (releasable).

**S2 — Groups ViewModel/Screen, labels, create-group dialog** (INV-1,5,7,8,10,11,20; OPEN-1, OPEN-2, OPEN-8)
- VM: GroupWithDetails carries DisplayedBalances. Remove the sum (:111), apply (:112-115) and wrapper (:148-153). Per-row split display (:103, :144-146) stays. homeCurrency: String?, read inside its own cancellation-safe boundary in loadGroups; on failure null, list still loads, error stays null. Remove "EUR" placeholder (:49) and the :90 path.
- Screen: each card labelled with group.currency; the `?: homeCurrency` fallback at :50 goes. PARTIAL → plural notice "N item(s) left out of the total". UNAVAILABLE → "balances unavailable"; total reads "unavailable", never 0. Suggested plan (:376-381, :422-432) composed only when settlementPlanAllowed; `remember` key includes the result. CreateGroupDialog: remove "EUR" default (:669); initial selection String? (never ""); Create disabled until a SupportedCurrency is picked.
- Notice copy (el/es too): no word for currency, no description, amount or member name.
- Write (source): M/ui/screens/groups/SharedExpenseGroupsViewModel.kt, SharedExpenseGroupsScreen.kt, R/values{,-el,-es}/strings.xml. Tests: T/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt, SharedExpenseGroupsScreenStateTest.kt.
- Covers AE-1 (equality with policy + AVAILABLE regression), AE-7, AE-8, AE-10 (IOException; CancellationException propagates), AE-11.
- Legal: ViewModel only renders. Create-group writer unchanged.
- Gates: FG-07 (no growth), FG-10, FG-12, FG-17, G-MONEY-11, FG-19. T3, reviewer-strict. Depends on S1. Lint at wave end (plurals, 3 locales).
- Done when: no sumOf over expenses and no calculateBalances(/applyToBalances( in the VM; no "EUR" at VM :49/:90 or Screen :669 (:238-245 is Inc 3); AEs pass.

**S3 — Manual recurring monthly total** (INV-12,13,14,20; D1; OPEN-4)
- One private function sums RecurrenceCalculator.toMonthlyAmount over active rules where rule.currency == homeCode (replaces :93-95). UiState: totalMonthly: Double?, totalCurrency: String?, totalStatus (reuse an existing availability enum if R20a has one, else nested), excludedRuleCount. Home read once in load, behind its own cancellation-safe boundary. No CurrencyConverter.
- Screen: card label uses state.totalCurrency (:142, :356-379). PARTIAL → count notice. UNAVAILABLE → "unavailable", never 0. Per-rule rows (:226, :462) unchanged.
- Write (source): M/ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt, ManualRecurringExpenseScreen.kt, R/values{,-el,-es}/strings.xml. Tests: T/ui/screens/recurringmanual/ManualRecurringExpenseViewModelTest.kt.
- Covers AE-12, 13, 14, plus INV-20 (home read throws CancellationException → propagates; no errorText).
- Legal: read-only; recurring rule coordinator untouched.
- Gates: FG-07, FG-10, FG-17 (no .sumOf { it.amount }), G-MONEY-11, FG-19. T3, reviewer-strict. Depends on S0. Serialise with money-correctness G2-15.
- Done when: AEs pass; VM has no CurrencyConverter dependency; no home-labelled total includes a rule in another currency.

**S4 — Budget offset engine, repository, ViewModel, card, monitor** (INV-15,16,17,18,20; D2, D3; OPEN-5, OPEN-6, OPEN-7)
- M/domain/groups/SharedExpenseBudgetOffsetEngine.kt (:77-80): throw typed MoneyDisplayUnavailableException; no "EUR".
- M/data/repository/BudgetRepository.kt: KDoc at :378 only; keep anchor `offsetEngine.calculateEffectiveBudgetSpend(` exactly.
- M/ui/screens/budget/BudgetViewModel.kt (:164-184): rethrow cancellation; everything else → null. :114-115 fallback stays and mirrors this.
- M/ui/screens/budget/BudgetCardDisplay.kt (new, pure mapping). M/ui/screens/budget/BudgetScreen.kt (:471-500, :602, :682, :690): with null breakdown, spend, remaining, over-budget, percent and health all read "unavailable" in neutral colour; new content description carries no amount or percent; status.spentAmount never shown there.
- M/domain/budget/BudgetMonitor.kt (:243-280): order PERCENT_UNKNOWN → skip → NO_SPEND_OR_LIMIT. Skip emits one STATUS_SKIPPED with grossFallback=true and reason=EFFECTIVE_SPEND_UNAVAILABLE; no amounts; no notify; no notified-at update.
- R/values{,-el,-es}/strings.xml; config/baselines/cancellation.json (OG-1 only).
- Tests: T/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt (AE-15); T/data/repository/BudgetRepositoryDiagnosticsTest.kt and BudgetRepositoryHistoricalStatusTest.kt (AE-16 repo side); T/ui/screens/budget/BudgetViewModelCurrencyProvenanceTest.kt (AE-16 VM cancellation); T/ui/screens/budget/BudgetCardDisplayTest.kt (new, AE-17); T/domain/budget/BudgetMonitorTest.kt (AE-18; OPEN-6: budgetStatus() fixture gets a default breakdown with effectiveSpend = spentAmount; :552-580 now asserts STATUS_SKIPPED; no deletions).
- Run, not edited: CancellationPropagationContractTest, DashboardBudgetProvenanceChainTest, BudgetRepositoryStress/Truncation/SuggestionsBatch, BudgetViewModelAutopilotQualityTest.
- Legal: BudgetRepository stays the only producer of BudgetStatus. No writes. Per-row convertAsOf unchanged (money-correctness inc 2).
- Gates: FG-07 + **OG-1**, FG-10, FG-17, G-MONEY-11, hardcoded-currency-fallback detector, FG-19, FG-20. T3, reviewer-strict. Depends on S0. Single writer for strings.xml and cancellation.json in its wave.
- Validation: targeted `*SharedExpenseBudgetOffsetEngineTest*`, `*BudgetRepository*`, `*BudgetViewModel*`, `*BudgetCardDisplayTest*`, `*BudgetMonitorTest*`, `*DashboardBudgetProvenanceChainTest*`, `*CancellationPropagationContractTest*`; then registered-guard `cancellation`.
- Done when: AEs pass; cancellation ratchet green with the BudgetViewModel entry removed under OG-1; no "EUR" literal at engine :77-80; no assertion weakened (reviewer checks the fixture diff).

**S5 — Anomaly lookup cancellation** (INV-19, INV-20)
- M/domain/alerts/AnomalyAlertOrchestrator.kt (:101): runCatching{}.getOrDefault → CancellationSafe.runCatchingCancellable{…}, still falling back to the expense's own currency. Nothing else changes (Timber :199-206 untouched).
- Write: that file (one hunk), config/baselines/cancellation.json (OG-2 only). Tests: T/domain/alerts/AnomalyAlertOrchestratorTest.kt (AE-19: cancellation propagates, no alert, in-flight cleared; IOException keeps expense currency).
- Legal: alert writer path unchanged.
- Gates: FG-07 + **OG-2**, FG-10, FG-19. T3, reviewer-strict. Depends on S0; app-lock S6 not in flight.
- Validation: targeted `*AnomalyAlertOrchestrator*`, `*CancellationSafetyArchitectureGuardTest*`, `*CancellationPropagationContractTest*`; registered-guard `cancellation`.
- Done when: AE-19 passes; G-CANCEL-02 entry removed under OG-2 (exact manual edit); counts re-checked against app-lock.

**S6 — Docs** (INV-1 owner, INV-7)
- docs/architecture/LEGAL_PATHS.md: replace "CALCULATE balances" block with spec §8 text; add group FORBIDDEN line; add Money FORBIDDEN line "❌ Substituting a literal currency for an unavailable home currency in any displayed total"; update "Record settlement" consumer wording to the policy. SharedBudgetManager reference stays (Inc 3, D6).
- docs/architecture/ENGINE_INTERACTION_MAP.md (:152-159): wording → GroupSettlementBalancePolicy.
- No tests. Depends on S1–S4 merged; LEGAL_PATHS free. Gates: FG-26. T2, standard reviewer. Validation: static-guards.
- Done when: "CALCULATE balances" names only GroupSettlementBalancePolicy; dead paths marked "no production caller; no new callers; pending owner decision (Inc 3)".

## §3 Waves
- W0: S0 (paper) and preflight. OG-1 and OG-2 pending.
- W1: S1, S3, S5 in parallel (policy / recurring+strings / anomaly+baseline). S5 skipped if OG-2 declined or app-lock S6 in flight.
- W2: S2 (needs S1; strings writer).
- W3: S4 (strings and baseline writer).
- W4: S6 (LEGAL_PATHS free).
- G1 gate: unit-tests (18 BudgetStatus consumers), static-guards (cancellation ratchet, money boundaries, raw money aggregates), registered-guard cancellation, lint; app-check only if gate requires. Then a fresh reviewer-strict on the whole diff. Device check with synthetic data and non-EUR home.
- One implementer order: S1, S3, S5, S2, S4, S6.

## §4 Traceability
| INV | Slice | AE |
|---|---|---|
| 1 | S1+S2 | AE-1 |
| 2 | S1 | AE-2 |
| 3 | S1 | AE-3 |
| 4 | S1 | AE-4 |
| 5 | S1, S2 | AE-2, AE-4, AE-5 |
| 6 | S1 | AE-6 |
| 7 | S2 | AE-7 |
| 8 | S1 (counts), S2 (text) | AE-8 |
| 9 | S1 | AE-9 |
| 10 | S2 | AE-10 |
| 11 | S2 | AE-11 |
| 12–14 | S3 | AE-12–14 |
| 15–18 | S4 | AE-15–18 |
| 19 | S5 | AE-19 |
| 20 | S2, S3, S4, S5 | AE-10, AE-15, AE-16, AE-19 + S3 cancel case |

All 19 AEs covered.

## §5 Validation (validation-runner only)
- Command: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <p> [-TestFilter <f>] [-GuardId cancellation]`. Serial; poll the run ID; RUNNING is not failure; missing or stale results are never PASS. Nobody runs ./gradlew directly.
- Per slice: targeted-unit-test per §2 filters; registered-guard cancellation for S4/S5. Per wave: static-guards; lint after S2/S3/S4.
- G1: unit-tests, static-guards, lint (app-check if required). No migration-tests (no schema).

## §6 Owner gates
- **OG-1 (S4, FG-07):** remove the stale `G-CANCEL-01 …/ui/screens/budget/BudgetViewModel.kt` entry (list line and count line) in the same change as the fix.
- **OG-2 (S5, FG-07):** remove the stale `G-CANCEL-02 …/domain/alerts/AnomalyAlertOrchestrator.kt` entry, same way.
- Both: exact manual JSON edit; no other entries; no --update-baseline in CI mode. Registry frozen until 2026-12-01 → owner approval required.
- If OG-1 declined: S4 stops at PARTIAL and returns to the human (land without the VM rethrow, or hold S4). If OG-2 declined: S5 deferred; swallow stays (baseline-neutral).
- **OG-3 (conditional):** if EFFECTIVE_SPEND_UNAVAILABLE needs a guard-owned registry entry → STOP.
- **OG-4 (standing):** any test deletion, allowlist, KNOWN_VIOLATIONS or G-MONEY-RAW entry, or guard weakening → STOP. None planned.

## §7 Top risks
1. BudgetMonitorTest fixture change (OPEN-6) could hide alert regressions. Mitigation: default breakdown; AE-18 cases; reviewer diffs every changed assertion.
2. Cancellation baseline drift or collision with app-lock S6, under the registry freeze. Mitigation: second merger re-checks counts; only S5 removes G-CANCEL-02.
3. FG-17 / G-MONEY-11 traps (`""` initial selection, `total: Double`, `.sumOf { it.amount }`). Mitigation: naming rules R2, nullable selection, static-guards, no allowlist.
4. AVAILABLE groups regress, or plan shows for PARTIAL, through a stale `remember` key. Mitigation: AE-6 bit-identical property test; AE-7 state test.
5. Other BudgetStatus consumers still show gross spend. Only BudgetScreen:472 and BudgetMonitor:243 use `effectiveSpend ?: spentAmount`; other consumers out of scope; G1 runs full unit-tests.

## §8 Out of scope
- Inc 2 (rounding, minor units, solver, myShareAmount) → group-settle-up.
- Inc 3 (D6 deletions of SharedBudgetManager, GroupBalanceCalculator, SharedExpenseManager.calculateBalances() and tests, LEGAL_PATHS reference, dao-approved entry; dead EUR sites VM :238-245, GroupsRepositoryImpl:149-156, SharedExpenseManager:290-299, GroupBalanceCalculator:37, CsvExpenseImporter:238; anomaly currency columns; Challenges; business raw SUMs).
- Date-header fix (money-correctness 1a). Detail-row labels and listing excluded rows (OPEN-2). Any conversion or R20a reuse. Offset FX basis (money-correctness inc 2). Timber items (privacy-guardian). BudgetViewModel second computation removal (OPEN-7, later). Changing budget percent/health computation. Any schema.
