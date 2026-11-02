# Verification — gated / disabled features UF-31..UF-35 (2026-10-22)

Source: read-only Sonnet scout pass (persisted by orchestrator). DB/money/lifecycle claims marked
`needs-confirm` were not fully traced. **Tier normalization (orchestrator):** the scout used T1 for
"highest risk" in places; tiers below are normalized to the feature-orchestrator scale
(T1 trivial → T3 lifecycle/privacy/Room/money/security).

Gate context: `ui/navigation/DeferredFeatureGate.kt` (R19 "H-1 hide"; ids tax, investment, challenges;
`available = BuildConfig.DEBUG`). Reason recorded in
`docs/releases/release-1.0-assurance/release-1.0-remediation-plan.md` §22 (R19) and
`workflows/active/money-correctness-handoff.md`. Tables + backup verifier entries intentionally kept.
Shared budgets and Rescue are not in the gate.

## Audit corrections
- **UF-32 corrected:** `TaxConfigurationViewModel` *does* call `taxEstimator.estimateTaxes(..., TaxConfigurationFactory.getConfiguration(selectedCountry))`.
  Real gap: country/filing currency never persisted — `TaxSettingsRepository.setTaxCountry/setFilingCurrency`
  have no production caller; estimator reads filing currency from prefs while VM passes its own
  country config → they can disagree. Stale TODO(T03) in `TaxConfiguration.kt` contradicts
  `TaxEstimator` "T03/T09-FIXED" comments.
- UF-31, UF-33 confirmed as written.

## UF-31 Investment — PARTIAL (read path real, write UI missing; correctly hidden)
- Exists: entities `Investment`, `InvestmentValue`, `InvestmentTransaction` (Double + currency string);
  DAOs (`InvestmentDao.update/delete/updatePrice`); `domain/investment/InvestmentTracker.kt`
  (`addHolding` :150 validated, write-barrier, atomic holding+snapshot+BUY; aggregates via
  `MoneyAggregateBuilder` + `CurrencyConverter`; staleness 7/30d; target/stop-loss hit queries);
  read-only `InvestmentViewModel`; tests `InvestmentTrackerTest`, `InvestmentGoldenScenarioTest`.
- Missing: add/edit/delete holding form + VM actions; tracker APIs for edit/delete/price update/
  SELL/DIVIDEND (FEATURES.md `updatePrice` is stale); price source (manual only offline; live = network);
  realized gains/lot ledger (deferred in code); target/stop-loss notifications; "EUR" fallback in
  `getPortfolioValueHistory`; deprecated raw `getPortfolioSummary`; **no investment write path in
  LEGAL_PATHS.md** (must define tracker as sole writer + allowlist/guard config).
- Schema: none for manual MVP; price-source columns would need bump+migration (needs-confirm).
- Stop gates: network price feed; legal-path/guard allowlist addition; schema; money (Double).
- Track **B** (manual-price MVP) / live prices separate **C**. Tier **T3** (new legal path + money). Size M (L with live feed).
- Entry: brief + critique (manual vs live, realized P&L, net-worth inclusion).
- Owner decisions: keep or drop; manual-only prices; SELL/DIVIDEND/realized gains; net-worth inclusion.

## UF-32 Tax — PARTIAL; self-declared DEFERRED_DESIGN (`TaxEstimator.kt` header requires full design review)
- Exists: `TaxEstimator` (filing-currency `MoneyAggregate`, cumulative progressive brackets, configurable
  fiscal year; VAT confidence hard LOW; FX LATEST_AVAILABLE only (PR7-DEFERRED); `estimatedRecoverableVat`
  always 0); `TaxConfiguration.kt` GR+US only — **US top bracket ends at 95,375 so income above it
  is untaxed (bug)**, no filing status/state tax, simplified GR; `TaxSettingsRepository` (prefs; setters
  unused); `DemoTaxRateProvider` (static_demo, isDemo, unknown country → 20% EUR) bound in `TaxModule.kt`;
  VM hard-coded 50k sample, hard-coded country list; tests `TaxEstimatorTest`, `TaxCalculationTest`, `TaxGoldenScenarioTest`.
- Missing: persist settings (single source of truth); real income source (`buildIncomeAggregate` only in
  `getTaxYearSummary`); vetted bracket data + country coverage; replace demo provider; disclaimers;
  per-transaction FX (DAO/schema); summary/export UI (partial overlap with Business tax reports).
- Stop gates: tax tables from network; legal/financial accuracy claims; money currency semantics; schema; mandated design review.
- Track **C** (properly designed new feature) or **X** (keep hidden / drop). Tier T2–T3. Size S (persist GR/US only) / L (credible multi-country).
- Owner decisions: countries; who owns rate data/updates; liability/disclaimer stance; estimator vs reporting-only.

## UF-33 Spending challenges — MOSTLY COMPLETE; un-hide blocked on money correctness
- Exists: `SpendingChallengeManager` (create, progress, no-spend streak, auto-deactivate, REDUCE_SPENDING
  baseline); `SpendingChallengeRepository` + entity (`currency` column default 'EUR'); VM + screen + create
  dialog; tests `SpendingChallengeManagerTest`, `SpendingChallengesViewModelTest`.
- Missing: replace deprecated raw-SUM DAO calls at manager :59, :214, :225 (mixed-currency sums) with
  home-currency aggregates; define baseline/target currency + home-currency-change behaviour (entity
  `currency` unused in calc); VM `homeCurrency` placeholder "EUR"; no worker/notification (evaluated on
  screen open only); edit/cancel/history UI unverified. money-aggregates-sweep brief: "fix numbers before
  any un-hide", T3 if re-shown. Stale `challenges_unavailable_message` (UF-09).
- Schema: likely none (needs-confirm if currency freeze needed). Manager reads `ExpenseDao` directly (confirm guard exception).
- Track **B**, after / inside money-aggregates-sweep. Tier **T3**. Size M. Cheapest of the five to un-hide.
- Entry: short brief on currency semantics, or domain-modeler directly; candidate extra increment of money-aggregates-sweep.
- Owner decisions: ship challenges at all; currency behaviour; notifications.

## UF-34 Shared budgets — DEAD CODE → recommend removal
- `SharedBudgetManager` (raw-sum DEPRECATION_ERROR reads; `getMemberContributions` throws; fake
  `budgetName`; no UI; no production caller; `SharedBudgetManagerTest` only). No budget↔group mapping entity.
- Overlaps Groups + `docs/features/group-settle-up/`. money-aggregates-sweep brief Increment 3 already
  proposes deleting `SharedBudgetManager.kt`, `GroupBalanceCalculator.kt`, `SharedExpenseManager.calculateBalances()`
  + tests, `LEGAL_PATHS.md:754` reference, `scripts/guardrails/dao-approved-files.txt:20` entry —
  **OWNER-APPROVAL** (test deletion + guardrail edits).
- Track **X** (remove via sweep Inc 3); any group-budget idea → future **C** after group-settle-up. Tier T2. Size S.

## UF-35 Financial rescue — INTENTIONALLY DISABLED break-glass tool, not an unfinished feature
- `data/rescue/` (`RescueConfig.ENABLE_FINANCIAL_RESCUE = false` const; `FinancialRescueCoordinator`,
  `FinancialRescueSnapshot`, `RescueActivity` exported=false). Raw-SQLite read → JSON snapshot → move old DB →
  fresh Room DB → import financial tables only. Documented `LEGAL_PATHS.md:1214`.
- Known issues: no home currency (pre-Hilt) → "" sentinel (money-correctness OD-2); fabricated snapshot at
  `FinancialRescueCoordinator.kt:418-420`; stale table coverage (no investments/tax/challenges/newer tables);
  superseded in part by R25 export-only recovery, `BlockedGateStartupRecovery`, `DatabaseOpenGate`.
- Stop gates: any enabling (data destruction), destructive migration logic, exported activity.
- Track **X** (keep disabled + document, or remove in favour of R25). Tier T3 if touched. Size S (leave) / M (reconcile).
- Owner decision: keep as break-glass vs delete. needs-confirm: schema coverage, `config/db_access_allowlist.yml`, `config/guards/db_structural_exceptions.yml` entries.

## Scout-recommended order
1. UF-34 delete via sweep Inc 3 (owner approval). 2. UF-33 after money-aggregates-sweep.
3. UF-31 brief → manual-price MVP. 4. UF-32 keep hidden unless owner wants it (persist-only S vs L design).
5. UF-35 leave disabled; reconcile with R25 later.
