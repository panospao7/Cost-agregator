# Feature brief v2 — money-aggregates-sweep (fail-closed visible totals)

Stage 1 reshape (the single allowed one), feature-strategist output persisted by the orchestrator 2026-10-21. v1 kept as brief-v1.md. Folds in critique.md (RESHAPE, 13 items). Paths short for app/src/main/java/com/yourname/expensetracker/... [V] = opened at that line; UNVERIFIED = not opened.

## What changed from v1
- Date-header item removed (moved to money-correctness 1a, which owns TransactionsScreen.kt:522-541). The only real bug there: the mixed path sums unsigned amounts (:535). The "not mine" claim was false (same-currency path zeroes not-mine rows via effectiveAmount, Expense.kt:163-165).
- Recurring total: no conversion. Old EUR-labelled rules make the total PARTIAL. The R20a precedent is not reused on this screen.
- Group balances get one domain owner: GroupSettlementBalancePolicy. LEGAL_PATHS entry fixed in Inc 1.
- Budget with home currency unavailable shows "unavailable", not gross spend.
- v1 item 5 shrinks to the budget-offset fallback plus the anomaly CancellationException fix. Dead EUR sites move to hygiene (Inc 3).
- v1 Inc 2 (rounding, minor units, solver) leaves this feature; it moves to the group-settle-up gate as a dependency note.
- Added: SharedExpenseGroupsViewModel.kt:90 reads home currency outside the try block [V].
- Dropped the three-decimal-currency claim (SupportedCurrency has none).

## Problem
Some totals in the release build add amounts in different currencies, or quietly use EUR when the home currency can't be read, and show the result as one trustworthy number. Users with a non-EUR home currency, old EUR-labelled data, or mixed group rows see wrong group balances, a wrong recurring monthly total, or a budget built on a made-up currency.

## Target users / situations
- Users whose home currency is not EUR and who still have rules or group rows from before 1.0 (column defaults 'EUR': ManualRecurringExpense.kt:25, ExpenseGroup.kt:24, GroupExpense.kt:111).
- Group users with legacy, restored or rescued rows whose currency differs from the group currency. Existence in real databases UNVERIFIED; assume possible.
- Anyone using budgets whose home-currency read fails.
- EUR-only users with no groups are mostly unaffected.

## Increment 1 — fail-closed visible totals (T3, no schema). Independent of ai-fixes and money-correctness 1a.
1. **Group balances: exclude and count, one owner.** Extend domain/groups/GroupSettlementBalancePolicy.kt (:25-54) to own the displayed-balance rule:
   - Expense rows whose currency differs from the group currency: left out of balances and total, and counted.
   - Settlements in another currency: left out and counted (no longer silently skipped at :32 [V]).
   - Result carries availability (AVAILABLE / PARTIAL / UNAVAILABLE), balances, total and excluded counts.
   - The ViewModel only renders the result. Today SharedExpenseGroupsViewModel.kt:111-115 [V] sums and calls SplitCalculator itself; that moves behind the policy.
   - Notice says rows were *left out* of the total, with a count. It does not say "in other currencies".
   - Hide the suggested settlement (SharedExpenseGroupsScreen.kt:431) whenever anything is excluded. Solver unchanged.
2. **Fix LEGAL_PATHS.md:764-768 [V].** "CALCULATE balances" names GroupSettlementBalancePolicy as the one legal path. GroupBalanceCalculator and SharedExpenseManager.calculateBalances() are marked "no production caller; no new callers; pending owner decision (Inc 3)". Not deleted in Inc 1.
3. **Manual recurring monthly total: PARTIAL, not a mixed sum.** ManualRecurringExpenseViewModel.kt:93-95 sums across currencies; the screen labels it in home currency (ManualRecurringExpenseScreen.kt:142,379). When a rule's currency is EUR and home is not: leave that rule out, show PARTIAL with the count of such rules. No conversion. Home currency unavailable: UNAVAILABLE.
4. **Budget offset: unavailable, not gross spend.**
   - SharedExpenseBudgetOffsetEngine.kt:77-80 [V] uses "EUR" when the home-currency read fails. It returns a bounded unavailable result instead.
   - (a) Budget screen shows effective spend as "unavailable"; never gross spend in that place.
   - (b) BudgetViewModel.kt:180-183 [V] catches Exception, returns null and falls back to raw spend. It must produce the unavailable state and rethrow CancellationException.
   - (c) BudgetRepository.kt:378-403 and BudgetMonitor.kt:243-246 fall back to gross spend for alerts — behaviour per D2.
5. **Anomaly lookup cancellation.** AnomalyAlertOrchestrator.kt:101 wraps the home-currency lookup in runCatching{}.getOrDefault, which swallows CancellationException. Rethrow it. Nothing else in the anomaly path changes in Inc 1. The FG-07 cancellation baseline (config/baselines/cancellation.json, G-CANCEL-02 for this file) may go down; it still touches a guard file, so the orchestrator must confirm.
6. **Groups list: home currency read outside try.** SharedExpenseGroupsViewModel.kt:90 [V] calls homeCurrency().first() before the try at :94; a failure escapes viewModelScope. Move inside the try and fail closed. The list still loads (balances are in group currency); the home-currency field is unavailable; group creation needs an explicit currency pick.

## Later increments
- **Increment 2 — moved to group-settle-up (dependency note only).** Rounding and minor units move with group-settle-up. Its gate inherits: per-operation rounding table (entry HALF_UP; conversion snapshot HALF_EVEN per money-correctness 1b; split/settlement in integer minor units with largest-remainder; display via formatter only); 1b fraction-digits accessor in SplitCalculator.kt:301-306, SettlementCalculator.kt:431-436, SharedExpenseGroupsScreen.kt:486-494, Money.kt:104; UI moves from deprecated simplifyBalances to SettlementCalculator.calculateSettlements (suspend: ViewModel or domain, not Compose remember). **Stored values change:** SplitCalculator.calculateMemberShare sets persisted myShareAmount (GroupTransactionCoordinator.kt:1065-1070); new JPY rows store whole-unit shares, old rows keep 333.33. T3; owner sign-off (AGENTS.md:193); release note.
- **Increment 3 — hygiene and dead code (T2; T3 if Challenges is shown again).**
  - OWNER-APPROVAL (single item, not decided here): delete domain/budget/SharedBudgetManager.kt, domain/groups/GroupBalanceCalculator.kt, SharedExpenseManager.calculateBalances(), and their unit tests; remove the LEGAL_PATHS.md:754 reference and the scripts/guardrails/dao-approved-files.txt:20 entry. Deleting tests and editing guardrail files is stop-and-ask.
  - Dead or unreachable EUR sites: SharedExpenseGroupsViewModel.kt:238-245 and placeholder :49 (null-group only); GroupsRepositoryImpl.kt:149-156 (null-group only); SharedExpenseManager.kt:290-299 (no caller); GroupBalanceCalculator.kt:37; util/CsvExpenseImporter.kt:238 (debug screen only).
  - Anomaly alert currency: write home currency at alert time into existing currency/baseAmount/baseCurrency columns (entity/AnomalyAlert.kt:52-54). Money Radar hides the amount when it differs from current home currency. Existing rows left as they are.
  - Challenges (SpendingChallengeManager.kt) stays hidden (DeferredFeatureGate.kt:39). Fix numbers before any un-hide; otherwise drop from the sweep.
  - Business raw SUMs (BusinessExpenseRepository.kt:49,59,79): left to the P5-P1-5 owner unless shown reachable.

## Non-goals
- Date-header sign fix (money-correctness 1a). Any "not mine" header change.
- Listing excluded group rows (Inc 1 shows counts only).
- Converting old EUR-labelled recurring rules; reusing R20a conversion here.
- Rounding, minor-unit or solver changes (group-settle-up gate); rewriting or re-rounding stored rows.
- Settlement writer, recordSettlement caller, new LEGAL_PATHS writer.
- New currency features: converting group balances with rates, per-group FX, trip mode, historical rates (manual-entry-currency).
- Budget-offset FX basis (convertAsOf at expense date): money-correctness inc 2.
- Snapshot, analytics or export FX alignment; assistant amount filters; Greek/EUR benchmark labels.

## Existing functionality reused / overlapped
- GroupSettlementBalancePolicy: extended as the single balance owner (no new class).
- R20a AVAILABLE/PARTIAL/UNAVAILABLE presentation: reused for notices only; its conversion is not reused.
- Existing bounded reason codes and the P6-CURRENT-027(b) gross-fallback diagnostic (BudgetMonitor.kt:244-246): reused.
- Overlaps and serialisation:
  - app-lock-privacy-mode S6 edits AnomalyAlertOrchestrator.kt and its test (app-lock plan :439-455). One writer per wave.
  - LEGAL_PATHS.md is hot (capture-health, ai-fixes, app-lock). Serialise.
  - money-correctness inc 2 touches SharedExpenseBudgetOffsetEngine (FX basis). Sequence carefully.
  - group-settle-up is downstream.
- Knowingly accepted divergence: Subscriptions (R20a, SubscriptionManagementViewModel.kt:186-209) converts rows from the same table; this screen shows PARTIAL.

## Market patterns (secondary sources, not official docs)
- Splitwise allows several currencies per group; reports conflict on whether conversion is automatic or paid, and whether totals are per currency. No source on how its simplification handles mixed currencies.
- Common design pattern: fix the exchange rate at expense creation so balances do not drift (system-design article, not Splitwise documentation).
- Tricount documents 1-cent accuracy (100 split three ways → 33.34/33.33/33.33). Relevant to the group-settle-up gate, not Inc 1.
- Takeaway: amounts per currency without conversion is an accepted pattern; supports exclude, count and hide the plan.

## Privacy & permissions: none
- No network, cloud AI, permissions, exported fields, or notification/OCR/receipt content.
- Notices hold counts and availability only.
- Routed to privacy-guardian (not this scope): AnomalyAlertOrchestrator.kt:199-206 logs merchant and amount with Timber.i (advisory C7); BudgetRepository.kt:401 logs a throwable with Timber.w(e, ...) — stack trace in diagnostics may conflict with bounded-diagnostics; to confirm.

## Schema: none
Groups, settlements and recurring rules already carry currency. Inc 1 changes reads and presentation only. Stop and ask if a stored "legacy/excluded" flag is ever proposed.

## Tier and order
- Inc 1 T3: money read paths, fail-closed semantics, domain-owner change in LEGAL_PATHS. reviewer-strict required.
- Inc 3 T2. Any test, guardrail or LEGAL_PATHS deletion is stop-and-ask whatever the tier.
- Inc 1 can go ahead of group-settle-up without waiting for ai-fixes or money-correctness 1a. Serialise with app-lock S6 (AnomalyAlertOrchestrator.kt) and other LEGAL_PATHS writers.

## Domain decisions (RECOMMENDED defaults, adopted by orchestrator)
- **D1 Non-home, non-EUR recurring rules:** also left out and counted in the same PARTIAL notice; no conversion in Inc 1 ("only home-currency rules are summed").
- **D2 Budget alerts when effective spend is unavailable:** BudgetMonitor skips threshold alerts for that budget this cycle and records the existing gross-fallback bounded reason. Gross spend never triggers an alert (matches RP-09 percentKnown gating, BudgetMonitor.kt:250+).
- **D3 Other offset-engine failures:** same unavailable state on screen and in alerts (one fail-closed rule for the path).
- **D4 Group policy result shape:** separate counts for excluded expenses and settlements; one combined notice. UNAVAILABLE only if the group currency itself is blank or unknown; otherwise PARTIAL whenever any count > 0.
- **D5 Group total when PARTIAL:** included-only total, labelled partial. Never the raw sum.
- **D6 (OWNER-APPROVAL, Q8):** deleting dead classes and tests in Inc 3. NOT decided by orchestrator.

## Success signals (no content telemetry)
- Mixed group fixture (one row mismatched): PARTIAL, excluded count 1, included-only total, no suggested plan.
- Foreign-currency settlement counted, not dropped.
- GBP-home fixture with an old EUR rule: PARTIAL recurring total, count 1, no conversion call.
- Home-currency failure: UNAVAILABLE on group home field, recurring total and budget effective spend; no EUR literal reaches any of them.
- CancellationException propagates from AnomalyAlertOrchestrator.kt:101, BudgetViewModel.kt:180 and SharedExpenseGroupsViewModel.kt:90 paths.
- Grep/architecture: no "EUR" home-currency fallback literal in Inc 1 files; no balance summing in SharedExpenseGroupsViewModel beyond rendering the policy result; LEGAL_PATHS "CALCULATE balances" names only GroupSettlementBalancePolicy.
- Device check (synthetic, non-EUR home): no number labelled in home currency includes unconverted foreign or EUR-labelled amounts; partial notices show counts only.
- Existing group, settlement, recurring and budget tests stay green or changes explicitly approved.

## Recommendation
Pursue (reshaped). Inc 1 is small and independent: it fixes numbers on screen that are wrong for non-EUR and mixed-data users and gives group balances one domain owner. Rounding goes to group-settle-up; deleting dead code waits for owner approval.
