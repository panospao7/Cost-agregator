# Impact map — money-aggregates-sweep, Increment 1 (condensed)

Stage 5, impact-mapper output persisted by the orchestrator 2026-10-21 (condensed; full detail in the mapper's report). Scope: Inc 1 only. Inc 2 belongs to group-settle-up. Inc 3 listed only.

## Tier
**T3** for Inc 1: money display and fail-closed semantics, cross-segment contracts (policy, offset engine, budget model, alerts), a LEGAL_PATHS owner change, a guard-baseline edit required by FG-07, and cancellation-sensitive paths. No schema, no writes, low end of T3. Planner-first required.

## Segments
Seg 24 (groups), Seg 25 (budget offset), Seg 2 (budget), Seg 7 recurring (`ui/screens/recurringmanual/` — the brief's `recurring/` path is wrong), Seg 8 analytics (AnomalyAlertOrchestrator — not in the segments file), Seg 16 currency (read-only), Seg 39 only if the baseline changes.

## Source changes (Inc 1)
- `domain/groups/GroupSettlementBalancePolicy.kt` (:25-54): result type with availability, balances, total, excluded expense and settlement counts. Count the other-currency skip at :32.
- `ui/screens/groups/SharedExpenseGroupsViewModel.kt`: home-currency read at :90-92 moves inside the try at :94; :111-115 stop summing in the ViewModel; placeholder :49 becomes unavailable.
- `ui/screens/groups/SharedExpenseGroupsScreen.kt`: hide the suggested plan at :431 when anything is excluded; `remember(memberBalances)` at :423 needs the new input; notice near :299/:348. **Also :669/:673: create-group dialog defaults to "EUR"** (not in brief).
- `ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt`: :93-95 stop summing across currencies; UI state gains availability and EUR-rule count; home currency needs a ViewModel-side value (currently a Flow, :75).
- `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt`: :142 and :356-379 TotalMonthlyCard partial notice.
- `domain/groups/SharedExpenseBudgetOffsetEngine.kt` (:77-80): unavailable result instead of "EUR". Return-type change ripples to `BudgetRepository.kt:386` and `BudgetViewModel.kt:166`, and to about 8 test files that mock it.
- `data/repository/BudgetRepository.kt` (:380-404 computeAdjustedSpend): keep the CancellationException guard and the anchor token `offsetEngine.calculateEffectiveBudgetSpend(` (contracts/CancellationPropagationContractTest.kt:87-88).
- `domain/budget/BudgetMonitor.kt` (:243-246): skip threshold alerts when unavailable (D2), reuse the existing gross-fallback reason; follow the `!percentKnown` pattern at :255.
- `ui/screens/budget/BudgetViewModel.kt` (:164-184): broad `catch (Exception)` returns null — add CancellationException rethrow and an unavailable state. **This is a baseline entry (G-CANCEL-01, :28/:71), which the brief omitted.**
- `ui/screens/budget/BudgetScreen.kt` (:471-477): stop using gross spend for effective spend, remaining, **percent and health** (:474, :477). The unavailable card must cover all four.
- `domain/alerts/AnomalyAlertOrchestrator.kt:101`: replace `runCatching{}.getOrDefault` with the canonical cancellation-safe helper (`CancellationSafe.runCatchingCancellable`, recognised by scripts/verify_cancellation_boundaries.py:94). Keep the expense's own currency as fallback (not an EUR literal). Baseline entry G-CANCEL-02 (:35/:78).
- `res/values{,-el,-es}/strings.xml`: notice strings (no string-parity test found by grep).

## Guards (FG IDs)
- **FG-07** (:528-573) no-growth ratchet on `config/baselines/cancellation.json`: two entries resolve when the two sites are fixed (G-CANCEL-02 AnomalyAlertOrchestrator, G-CANCEL-01 BudgetViewModel). FG-07 requires the stale entries removed in the same change, so **the baseline edit is mandatory once the fixes land**. The guard tool supports `--update-baseline` only for decreases and not in CI mode, so the JSON edit must be manual and exact. Adding any new broad catch is forbidden.
- **FG-10** (:663-697): new catches must be guarded. Kotlin test `CancellationSafetyArchitectureGuardTest.kt` has file-scoped KNOWN_VIOLATIONS (:97, :140, :157, :165). File-scoped, so a fix does not force an edit there.
- **FG-17** (:914-947): `scripts/verify_raw_money_aggregates.py` traps for the new policy — `.sumOf { it.amount }`, `.sumOf { it.effectiveAmount }`, a field `total: Double`, `var total = 0.0 … sum`. Use BigDecimal; name the field differently (e.g. `includedTotal`). `scripts/verify_money_boundaries.py` G-MONEY-11 (non-allowlistable): no `CurrencyCode("XXX")`, no `""`, no `"N/A"` sentinel. **UNAVAILABLE must be a typed state, not a sentinel currency.**
- "Hardcoded currency fallback" detector: removing the `:79` literal is aligned. Check `BudgetAutopilotArchitectureGuardTest.kt` (mentions "EUR") is not source-scanning these files.
- **FG-12** (:737): business rule moves out of the ViewModel into the policy.
- **FG-16** (:864): only if the Timber items are touched (they are not in Inc 1; routed to privacy-guardian).
- **FG-06, FG-23**: no broadened allowlist; a baseline change must not come with a guard weakening. **FG-19**: no test deletion in Inc 1. **FG-20**: Gradle, unit tests, lint. **FG-26**: LEGAL_PATHS and doc traceability.

## Legal paths and docs
- LEGAL_PATHS "CALCULATE balances" (:764-768 at mapping time; re-anchor by heading): name GroupSettlementBalancePolicy as the one owner; GroupBalanceCalculator and SharedExpenseManager.calculateBalances() marked "no production caller; no new callers; pending owner decision (Inc 3)".
- LEGAL_PATHS "Record settlement" (:752-754): consumer wording goes stale; edit in the same slice. No writer added.
- Optional one-liner under Money FORBIDDEN (~:607): no "EUR" fallback for a displayed total.
- ENGINE_INTERACTION_MAP.md:152-159: update "GroupBalanceCalculator / SplitCalculator" to the policy.
- Line numbers in LEGAL_PATHS drift; re-anchor by heading.

## Callers that constrain signatures
- GroupSettlementBalancePolicy callers: SharedExpenseGroupsViewModel.kt:112; SharedExpenseManager.kt:305 (dead); GroupBalanceCalculator.kt:53,58 (dead but compiled and tested). Keep old functions as overloads or update dead callers and tests. Inc 3 deletion must not be required for Inc 1 to compile.
- BudgetStatus / AdjustedSpendBreakdown (BudgetModels.kt:24, :48) used by 18 main files. Prefer adding the unavailable state without changing existing fields.

## Collisions
- **app-lock S6** edits AnomalyAlertOrchestrator.kt (:193, :199-205), its test, BudgetMonitor.kt (:474) and its tests. Different hunks; one writer per wave; whoever lands second rebases and re-verifies baseline counts. Only one change may remove G-CANCEL-02.
- **capture-health** edits LEGAL_PATHS (review and group blocks). Serialise.
- **money-correctness 1a**: TransactionsScreen not in Inc 1. ManualRecurring :108-116 follow-up (G2-15) is the error region, not our :93-95 hunk, but the same file.
- **money-correctness inc 2 (F-4 budget fallback)** overlaps Inc 1 item 4 (BudgetRepository, BudgetViewModel, offset engine). Inc 1 lands first; Inc 2 rebases onto the unavailable contract. **Flag: F-4 and Inc 1 item 4 are the same defect.**
- manual-entry-currency: no overlap. ai-fixes and nl-quick-add: LEGAL_PATHS doc hot spot only.

## Tests to extend (app/src/test/java/com/yourname/expensetracker/)
GroupSettlementBalancePolicyTest; SharedExpenseGroupsViewModelTest; SharedExpenseGroupsScreenStateTest; ManualRecurringExpenseViewModelTest; SharedExpenseBudgetOffsetEngineTest; BudgetRepositoryHistoricalStatusTest (:211), BudgetRepositoryDiagnosticsTest; BudgetRollover/Stress/Truncation/SuggestionsBatch tests (mock the engine); BudgetViewModelCurrencyProvenanceTest; BudgetMonitorTest; AnomalyAlertOrchestratorTest; CancellationPropagationContractTest (:87-88 stays green); DashboardBudgetProvenanceChainTest (:167-169).

## Validation (validation-runner only, serial)
Targeted per slice: `*GroupSettlementBalancePolicyTest*`, `*SharedExpenseGroups*`, `*ManualRecurringExpenseViewModelTest*`, `*SharedExpenseBudgetOffsetEngineTest*`, `*BudgetRepository*`, `*BudgetMonitorTest*`, `*BudgetViewModel*`, `*DashboardBudgetProvenanceChainTest*`, `*CancellationPropagationContractTest*`, `*AnomalyAlertOrchestrator*`, `*CancellationSafetyArchitectureGuardTest*`. Then static-guards (cancellation ratchet, money, raw money aggregates), registered-guard `cancellation`, and unit-tests (cross-slice contract change; 18 BudgetStatus consumers). No migration tests (no schema).

## Privacy
None new. Notices carry counts and availability only. Routed (not Inc 1): AnomalyAlertOrchestrator.kt:199-206 (Timber.i merchant and amount); BudgetRepository.kt:401 (Timber.w with throwable).

## Owner decisions and approvals
1. **OWNER-APPROVAL (guard config):** removing the two resolved baseline entries (G-CANCEL-02 Anomaly, G-CANCEL-01 BudgetViewModel). Required by FG-07 once the fixes land; the registry is frozen until 2026-12-01. Alternative: defer both fixes and keep Inc 1 baseline-neutral (leaves the swallow in place). **Not decided here.**
2. **Not allowed:** any new allowlist, KNOWN_VIOLATIONS or G-MONEY-RAW file-allowlist entry. Rewrite instead.
3. **OWNER-APPROVAL (D6, Inc 3):** deleting SharedBudgetManager.kt, GroupBalanceCalculator.kt, SharedExpenseManager.calculateBalances(), their tests, LEGAL_PATHS :754 reference, dao-approved-files.txt:20 entry.
4. Optional LEGAL one-liner (under Money FORBIDDEN): recommended yes.
5. Brief gaps resolved by recommendation: create-group dialog must require an explicit currency pick when home is unavailable (no EUR default); the unavailable budget card also covers percent and health; UNAVAILABLE is a typed state, never a sentinel currency.

## Inc 3 and Inc 2 (not in this map)
Inc 2 (rounding, myShareAmount) belongs to group-settle-up; T3. Inc 3 hygiene is T2; every deletion is stop-and-ask regardless of tier.
