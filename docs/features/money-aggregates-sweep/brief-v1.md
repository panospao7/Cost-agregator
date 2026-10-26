# Feature brief — money-aggregates-sweep

Stage 1 (Brief), feature-strategist output persisted by the orchestrator 2026-10-21. Read-only research. Paths are short for `app/src/main/java/com/yourname/expensetracker/...`. [V] = opened at that line; UNVERIFIED = not opened.

## Problem
Some money numbers shown are still wrong after money-correctness (1a/1b, planned) and release R20. Group balances, totals and the suggested settlement add amounts in any currency and round as if every currency has cents. The manual-recurring monthly total adds amounts in different currencies and labels the result in home currency. The mixed-currency date header ignores whether a row is income or spending. Several paths fall back to "EUR" when the home currency can't be read, instead of failing closed. Rounding differs between components. Group settle-up (a new money writer) should not be built on these numbers.

## Evidence (still wrong after R20)
Visible in release builds:
1. **Group balances mix currencies.** `domain/logic/SplitCalculator.kt:323-353` adds `expense.totalAmount` with no currency check [V]. Group total `ui/screens/groups/SharedExpenseGroupsViewModel.kt:111` does the same, formatted in group currency (`SharedExpenseGroupsScreen.kt:348`) [V]. New writes are forced to group currency (`SharedExpenseGroupsViewModel.kt:245`, `domain/groups/SharedExpenseManager.kt:242`) [V]. Mixed rows come only from legacy, restored or rescued data. Column default `'EUR'` (`data/database/entity/GroupExpense.kt:111`) [V]. Whether real databases contain such rows: UNVERIFIED.
2. **Settlements in another currency silently ignored.** `domain/groups/GroupSettlementBalancePolicy.kt:32` skips them with no count [V].
3. **Suggested settlement uses the deprecated greedy Double solver.** `SharedExpenseGroupsScreen.kt:431` calls `SplitCalculator.simplifyBalances` (`SplitCalculator.kt:366-420`, 0.01 tolerance, Double) [V]. The exact cents-based `SettlementCalculator.calculateSettlements` (`domain/groups/SettlementCalculator.kt:59-100`) has no UI caller [V].
4. **Group math assumes 2 decimals.** `SplitCalculator.kt:301-306` (setScale(2, HALF_UP)), `SettlementCalculator.kt:431-436`, `SharedExpenseGroupsScreen.kt:486-494`, `domain/util/Money.kt:104` (movePointRight(2)) [V]. Example: JPY 1000 three-way split stored as 333.33/333.33/333.34; formatter shows ¥333 ×3 = ¥999 (inferred). Three-decimal currencies lose precision.
5. **Manual recurring monthly total mixes currencies.** `ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt:93-95` sums `amount` across rules; `ManualRecurringExpenseScreen.kt:142,379` formats the sum in home currency [V]. Pre-1.0 bills default to EUR (`ManualRecurringExpense.kt:25`; known limitation in 1.0 user notes).
6. **Date-header sign and "not mine" differ.** Same-currency path signed via `signedEffectiveAmount` (`TransactionsScreen.kt:528,1520-1527`) but ignores `isNotMine`. Mixed path sums unsigned `normalizedEffectiveAmount` (`:535`), which zeroes not-mine rows (`Expense.kt:176-184`) [V]. A mixed day with income plus spending shows a wrong total. RF-4, routed out of money-correctness.
7. **"EUR" fallback instead of failing closed.** `domain/groups/SharedExpenseBudgetOffsetEngine.kt:77-80` [V]; `data/repository/GroupsRepositoryImpl.kt:149-155` [V]; `SharedExpenseGroupsViewModel.kt:238-244` and placeholder `:49` [V]; `SharedExpenseManager.kt:294-299` [V].
8. **Anomaly alert rows carry the wrong currency.** Stored `amount` is home-converted (`domain/alerts/AnomalyAlertOrchestrator.kt:183`); the row is written without currency or base fields (`data/repository/AnomalyAlertRepositoryImpl.kt:38-46`) so it falls to entity default `'EUR'` (`entity/AnomalyAlert.kt:52-54`) [V]. Money Radar reads the amount with no currency (`AnomalyAlertRepositoryImpl.kt:50-58`, `ComputeMoneyRadarUseCase.kt:264-272`) [V]: after a home-currency switch, old alerts show in the new currency. Home lookup `runCatching{...}.getOrDefault(...)` (`:101`) swallows CancellationException [V].
9. **Rounding differs by component.** HALF_UP: `Money.kt:34`, SplitCalculator, SettlementCalculator, group display, SharedExpenseManager, EnhancedSplitManager, AddExpenseViewModel, FxRateFormatter [V]. HALF_EVEN: SourceCurrencyAllocation and some analytics/forecast [V]. money-correctness 1b adds HALF_EVEN only for conversion snapshots. Nobody owns the app-wide policy (routed out at `docs/features/money-correctness/brief.md:65`).

Hidden or dormant in release (lower priority):
10. **Challenges.** Raw SUMs at `domain/challenge/SpendingChallengeManager.kt:59-60,214-219,225-227`; "saved" = 30-day raw average ÷ 30 (`:83,222-227`); no-record days count as no-spend (`:69-73`) [V]. Hidden by R19 (`ui/navigation/DeferredFeatureGate.kt:39`) [V].
11. **SharedBudgetManager** raw SUMs (`domain/budget/SharedBudgetManager.kt:46-58`) [V] — **no production caller** [V]. Same for `domain/groups/GroupBalanceCalculator.kt` (EUR fallback `:37`) [V]. Both have unit tests: dead code.
12. **ExpenseRepository raw wrappers (`:769-1042`).** Only production use: `getTotalSpent()` as an invalidation trigger, value ignored (`domain/analytics/TotalsAggregationEngine.kt:762,786`) [V]. `BusinessExpenseRepository.kt:49,59,79` raw SUMs feed TaxEstimator (hidden) and BusinessExpenseReportGenerator (reachability UNVERIFIED).
13. **CSV importer** `util/CsvExpenseImporter.kt:238` "EUR" last resort; reachable only from the debug screen [V].

Already fixed or stale (excluded): R20a (subscriptions, stress forecast, sweep riskBuffer); R20b (analytics windows); R20c (date pickers); R18 (group-linked edits blocked); ManualRecurring write currency already fail-closed (`ManualRecurringExpenseViewModel.kt:141-155`) — G2-15 ":108-116 EUR default" is stale; InsightsEngine EUR (`InsightsEngine.kt:74,505-508`, A06-FIXED); AnalyticsScreen `:718` stale KDoc only; InvestmentTracker `:553` hidden.

## Target users / situations
- Users whose home currency is not EUR, or who have any rule or expense in a foreign currency.
- Group users with legacy or restored mixed rows, or groups in zero-decimal currencies (JPY, KRW, ISK).
- Everyone whose daily header mixes income and spending, or includes "not mine" rows.
- EUR-only users with no groups are mostly unaffected.

## Goals
- Every money total shown in release is single-currency or converted. When amounts can't be included, the screen says "partial" with a count, or "unavailable". Never a silent mixed sum.
- One documented rounding and minor-unit policy per operation, which group settle-up can build on.
- No "EUR" stand-in for an unknown home currency in money read or compute paths.

## Increment 1 — visible totals, fail closed (T3, no schema)
1. Group balances and total count only rows in the group currency. Show an excluded-row count and notice, using R20a's AVAILABLE/PARTIAL/UNAVAILABLE pattern. Hide the suggested settlement when anything is excluded (critique row 7: single-currency only).
2. Settlements in another currency counted as excluded and stated, not dropped (`GroupSettlementBalancePolicy`). Read-only; no settlement writes.
3. Manual recurring monthly total converted to home currency, with partial/unavailable handling (reuse `MultiCurrencyRepository.aggregateDisplayAmounts`, as R20a did).
4. Transactions date header: mixed path signed like the same-currency path; both treat `isNotMine` the same. Lands after money-correctness 1a, which owns that screen.
5. Remove the home-currency "EUR" fallbacks in items 7 and 8. Use a bounded unavailable state or an existing bounded reason code. Fix the CancellationException swallow at `AnomalyAlertOrchestrator.kt:101`.

## Later increments
- **Increment 2 — money policy, rounding and minor units (T3, no schema).**
  - Per-operation rounding table (entry, conversion snapshot, split allocation, settlement, display) in the LEGAL_PATHS money section.
  - Use money-correctness 1b's fraction-digits accessor in SplitCalculator, SettlementCalculator, group display and Money.kt cents handling.
  - Group UI uses the single exact solver (`SettlementCalculator`); retire `simplifyBalances` from the UI.
  - Needs owner sign-off on the rounding change (AGENTS.md:193 "preserve rounding semantics") and a release note: JPY groups show different shares.
- **Increment 3 — hidden and dormant surfaces (T2; T3 if Challenges is shown again).**
  - Challenges: currency-aware spend and honest "saved" before any un-hide; otherwise stays hidden.
  - Anomaly alerts: write home-at-alert-time currency into existing columns; show nothing when it differs from the current home currency.
  - CSV importer: fail closed.
  - SharedBudgetManager / GroupBalanceCalculator: delete or migrate (Q8).
  - Business raw SUMs: left to the P5-P1-5 owner unless reachable.

## Non-goals
- No group settle-up writer, no `recordSettlement` caller, no new LEGAL_PATHS writer (critique B2).
- No new currency features: no converting group balances via rates, no trip mode, no per-group FX, no historical rates (manual-entry-currency inc 4 / MEC).
- No rewriting stored amounts or re-rounding existing rows.
- No snapshot/analytics FX-basis alignment, re-snapshot, or export changes (money-correctness inc 2/3).
- No assistant amount filters (SRH-7 / assistant-follow-ups).
- No Greek/EUR benchmark labels (ai-fixes Part A).

## Existing functionality reused / overlapped
- `MultiCurrencyRepository.aggregateDisplayAmounts`: R20a partial-total pattern.
- `domain/core/money` digits accessor + HALF_EVEN helper: money-correctness 1b. Increment 2 hard-depends on it.
- `SettlementCalculator.calculateSettlements`: exact solver already exists.
- `MISSING_RATE` / bounded reason codes reused; no new enum expected.
- Overlaps: money-correctness 1a (`TransactionsScreen`, `AmountSemantics`) — sequence after 1a; money-correctness inc 2 (`SharedExpenseBudgetOffsetEngine` uses `convertAsOf` at expense date, differs from snapshot rule); group-settle-up (downstream); loan-installments (none found).

## Market patterns (secondary sources, not official docs)
- Splitwise keeps several currencies in one group; conversion to a base currency is reportedly paid, free users keep original currencies. No source found on how simplification treats mixed currencies.
- Tricount documents about 1-cent accuracy; a three-way split of 100 leaves one person a cent more.
- Equal integer shares in zero-decimal currencies leave a 1-unit gap someone must absorb (KRW example). Remainder assignment is a general design pattern.
- Takeaway: keeping amounts per currency without converting is an accepted pattern → supports "exclude + count + hide plan" over adding conversion.

## Privacy & permissions: none
- No network, cloud AI, permissions, new exported fields, notification/OCR/receipt content.
- Notices hold counts and currency codes only. Removing EUR fallbacks makes the app more fail-closed.
- Routed (not this scope): `AnomalyAlertOrchestrator.kt:199-206` logs merchant name and formatted amount at `Timber.i` — breaks bounded-diagnostics; privacy-guardian (matches advisory C7).

## Schema: NONE required
- Group expenses and settlements already have `currency`.
- `AnomalyAlert` already has `currency`/`baseAmount`/`baseCurrency` (`entity/AnomalyAlert.kt:52-54`); writing them needs no migration.
- Rounding changes affect computation and display only; stored rows not rewritten.
- Schema only if the owner later wants a stored "excluded/legacy" flag or per-group rate provenance — not needed for correctness. Stop-and-ask if one appears.

## Tier
- Inc 1 T3: money read paths across groups, recurring, Transactions, budget offset; fail-closed semantics; files shared with money-correctness 1a.
- Inc 2 T3: app-wide rounding change (owner sign-off); amounts settle-up will persist.
- Inc 3 T2: hidden/dormant surfaces and dead code. T3 if Challenges un-hidden, or if deleting tests or guards (stop-and-ask).
- Order: after money-correctness 1a (Inc 1) and 1b (Inc 2); before group-settle-up.

## Domain questions with RECOMMENDED defaults (orchestrator decides by recommendation)
- Q1 Mixed-currency group rows: exclude from balances and total; show "N entries in other currencies not included"; hide suggested settlement. (Converting with rates rejected: new currency feature.)
- Q2 Foreign-currency settlements: excluded and counted in the same notice.
- Q3 Settlement solver: UI switches to `SettlementCalculator` in Inc 2 with minor units; `simplifyBalances` removed from UI.
- Q4 Rounding table: entry HALF_UP unchanged; conversion snapshot HALF_EVEN (1b, approved); split/settlement allocation = integer minor units with largest-remainder distribution in the currency's digits; display = formatter only. No mass switch to one mode; owner sign-off on the table.
- Q5 Minor units: reuse 1b accessor; unknown code → amount ops disabled (unavailable), never assume 2 decimals.
- Q6 Manual recurring total: convert to home currency, partial notice and count (R20a pattern).
- Q7 Home currency unavailable in group, budget-offset or anomaly paths: bounded unavailable, no number. Group creation still allowed with an explicit currency pick. Anomaly check skipped.
- Q8 Dead SharedBudgetManager / GroupBalanceCalculator: delete them and their tests in Inc 3. **Deleting tests needs explicit owner approval (stop-and-ask).** Alt: migrate and keep.
- Q9 Challenges: keep hidden; Inc 3 fixes numbers first. Drop from sweep if no un-hide plan.
- Q10 Anomaly alert currency: write home currency at alert time into existing columns; Money Radar hides the amount when it differs from current home currency. Existing rows left as is.
- Q11 Mixed-currency groups in practice: assume possible (legacy 'EUR' default); handle fail-closed in Inc 1 at low cost. No telemetry.
- Q12 Budget offset FX basis: out of scope; route to money-correctness inc 2. This sweep only removes the EUR fallback.
- Q13 Not-mine rows in same-currency header: exclude (match `normalizedEffectiveAmount`); pin with a test.

## Success signals (no content telemetry)
- Unit tests: mixed-currency group fixture shows excluded count and no plan; foreign-currency settlement counted, not dropped; JPY 1000 three-way split gives integer shares summing to 1000 (Inc 2); USD+EUR recurring fixture gives a partial total; mixed date header with deposit plus purchase has the same sign as the same-currency equivalent; home-currency failure gives bounded unavailable on every Inc 1 surface.
- Grep/architecture test: no "EUR" fallback literal in Inc 1 files.
- Device check (synthetic, non-EUR home): no number in home currency includes unconverted foreign amounts.
- Existing group, settlement and recurring tests stay green, or changes explicitly approved.

## Recommendation
Pursue as a money-correctness follow-up, ahead of group-settle-up. The numbers are on screen and wrong for non-EUR, mixed-currency or zero-decimal users. Inc 1 needs no schema. Inc 2 removes settle-up's rounding dependency (critique B2). Scope is smaller than the portfolio assumed: SharedBudgetManager, GroupBalanceCalculator and most ExpenseRepository raw SUMs are dead or trigger-only, Challenges is hidden, and the InsightsEngine/AnalyticsScreen EUR claims are stale.
