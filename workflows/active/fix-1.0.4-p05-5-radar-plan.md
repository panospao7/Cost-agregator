# P-05-5 Radar/Home single source: slice R1 (feature-planner, 2026-11-06, read at c6 07950fcb)

The old branch `feature/fix104-ao-home-verdicts` is closed, because it hit its 2-review limit. Its ideas are reused here; its commits are not. Tier T2 money: a Sonnet writer, a separate test-author, and one combined guardian. reviewer-strict runs at the merge. Paths are under `app/src/main/java/com/yourname/expensetracker/`.

## Invariants
- Single source: Radar budget risk is derived from the SAME Monte Carlo result object the Home card uses. There is no second simulation.
- Fail-closed: a budget read failure, or an unknown budget, never reads "All Clear". It reads UNAVAILABLE, using the existing `money_radar_unavailable` string.
- Keep the mainline YELLOW cap (3e11d36b, `ComputeMoneyRadarUseCase.kt:195-200`). Not taken: b0cf3da6 (the RED floor) and a 5% rule (item 143 defaults).
- AE-1 (rent): before confirm, base 1,100, Runway 0 CRITICAL, weather -100. After confirm, 700 / 300 / 300. AE-21: a suggestion alone changes nothing.

## Source
1. `domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`
   - Add `sealed interface RadarBudgetInput { None; Unknown; Unreadable; Simulated(MonteCarloResult) }`, and change `compute()` (:152) to `compute(budget)`.
   - `getBudgetRisk` (:292-387) takes its risk from `getMonteCarloBudgetImpact(result.budgetAmount, result)`.
   - Drop the ctor params `monteCarloSimulator` and `budgetRepository`. Keep `expenseRepository`, which `getMonthlyIncome` (:487) uses.
   - Keep the outer catch with the CancellationException rethrow (allowlist entry `CancellationSafetyArchitectureGuardTest:109`).
   - Unreadable → UNAVAILABLE. Unknown with GREEN → UNAVAILABLE. Unknown with YELLOW or RED is kept.
2. `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
   - Build the input from `monteCarloWidget` (:347) and `ctx.overallBudget` (:591, :614), and call `compute(budget)` at :368.
   - Mapping:
     - None: no overall budget, a non-month-scale budget, or `budgetNotComparable` NO_POSITIVE_LIMIT.
     - Unknown: any other limit-unavailable reason, or an unavailable card while a month-scale budget exists.
     - Simulated: a card result is available.
3. `domain/usecase/dashboard/DashboardDataProvider.kt`
   - :49 swallows the budget read failure.
   - Add `budgetReadFailed: Boolean = false` to `BaseData` (:147) and `DashboardData` (:160), set in `getBaseDataFlow` (:46) and `getAllDataFlow` (:63).
   - `budgetReadFailed` → Unreadable.

## Tests
- `ComputeMoneyRadarUseCaseTest`: rebase onto the sealed inputs and drop the simulator mocks and verifies.
- `BudgetSurfaceAgreementTest`: Radar risk equals the card result (:238, :255, :263-297).
- New `HomeVerdictParityTest`, using the real widgets and Radar use cases (simulator seeded):
  - AE-1 before and after confirm;
  - AE-21;
  - one simulation per compute;
  - Unreadable → UNAVAILABLE;
  - Unknown+GREEN → UNAVAILABLE;
  - Unknown with bills → non-GREEN.
- Stub fallout: `compute()` becomes `compute(any())` in about 17 files (see the planner list in the handoff). `NotificationParserDashboardTotalSeamTest:456` drops 2 ctor params.

## Filter
`*ComputeMoneyRadarUseCaseTest,*HomeVerdictParityTest,*BudgetSurfaceAgreementTest,*ComputeDashboardWidgets*,*BlockParty*,*DashboardBudgetProvenanceChainTest,*FinancialRunwayNoBurnTest,*CrossEngineParityMatrixTest,*DashboardWidgetConsistencyTest,*DashboardCurrencyIntegrationTest,*NotificationParserDashboardTotalSeamTest,*DashboardContractsAdapterTest,*PaymentLinkArchitectureGuardTest,*CancellationSafetyArchitectureGuardTest`

Run static-guards once at the candidate. No db_access. The allowlist must not grow.

## Deferred
- The `recordCorrection` bulk flag: not proven to stop merchant learning (`TransactionSideEffectPlanner.kt:209`); it needs its own slice.
- FC-FCST4: weekly recurring rules are not passed to the simulator.
- A distinct "Partly checked" label: it would need a new string.

## Risks
- Before confirm, Radar still lists the paid-unlinked rent as due (expected under AE-1).
- The card's committed+likely total replaces the Radar's own 7-day recurring sum, so scores can shift. Pinned by the new tests.
