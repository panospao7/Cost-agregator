# Critique — money-aggregates-sweep (brief v1)

Stage 2. Read-only feature-critic output, persisted by the orchestrator 2026-10-21. Paths short for `app/src/main/java/com/yourname/expensetracker/...`.

**Verdict: RESHAPE.** Most claims hold. Three blocking issues change scope.

## Claims that hold
- SplitCalculator.kt:323-353 adds amounts without a currency check.
- GroupSettlementBalancePolicy.kt:32 skips other-currency settlements without counting them.
- SharedExpenseGroupsScreen.kt:431 still calls deprecated `simplifyBalances`.
- SharedExpenseGroupsViewModel.kt:111 adds a raw total.
- ManualRecurringExpenseViewModel.kt:93-95 adds amounts across currencies.
- TransactionsScreen.kt:535 adds unsigned amounts.
- AnomalyAlertOrchestrator.kt:101 uses runCatching (swallows CancellationException); :183 stores the home-converted amount; AnomalyAlertRepositoryImpl.kt:38-46 writes no currency.
- SharedBudgetManager and GroupBalanceCalculator have no production callers (only tests).
- Groups are not hidden in release (DeferredFeatureGate.kt:21).
- money-correctness handoff (workflows/active/money-correctness-handoff.md:86) lists 1a/1b as planned, parked behind ai-fixes (approved, not built).

## Blocking
1. **Item 6 / Q13 is based on a false claim.** The same-currency header already zeroes "not mine" rows: `signedEffectiveAmount` (TransactionsScreen.kt:1520-1524) uses `effectiveAmount`, which returns 0 for isNotMine (data/database/entity/Expense.kt:163-165). The real bug is only that the mixed path is unsigned (:535), so deposits add to spending and transfers count. money-correctness 1a already edits this block (TransactionsScreen.kt:522-541; money-correctness-plan.md:39). Keeping it in Inc 1 makes Inc 1 wait for ai-fixes and 1a for a one-line fix. Move it into 1a and drop Q13.
2. **Item 3 / Q6 (convert the recurring total) can make the number worse.** Before 1.0 rules were saved as EUR regardless of home currency (release-1.0-phase3-4-findings-ledger.md:204 V-C4; release-1.0.1-backlog.md:118; entity default ManualRecurringExpense.kt:25). A GBP-home user with old rules: today's raw sum labelled GBP is right; converting treats amounts as EUR, applies a rate, and shows AVAILABLE, not PARTIAL. R20a set a precedent on the same table (SubscriptionManagementViewModel.kt:186-209), but no one accepted it for this screen.
3. **No single owner for the group balance rule.** LEGAL_PATHS.md:764-768 names GroupBalanceCalculator and SharedExpenseManager.calculateBalances as the legal balance path. Neither has a production caller. The only live path is SharedExpenseGroupsViewModel.kt:111-115 calling SplitCalculator from the UI layer. Putting exclude-and-count in the ViewModel would put a business rule in UI and leave LEGAL_PATHS stale. Inc 1 must name one domain owner (e.g. extend GroupSettlementBalancePolicy) and fix LEGAL_PATHS:764-768 in the same slice.

## Cautions
4. Most EUR fallbacks are unreachable or dead: SharedExpenseGroupsViewModel.kt:238-245 and GroupsRepositoryImpl.kt:149-156 apply only when the group row is null; SharedExpenseManager.kt:290-299 is inside a method with no caller; AnomalyAlertOrchestrator.kt:101 falls back to the expense's currency. The only live money path is SharedExpenseBudgetOffsetEngine.kt:77-80. If it fails closed, both callers fall back to gross spend (BudgetRepository.kt:378-403; BudgetViewModel.kt:180-183). P6-CURRENT-002 (BudgetRepository.kt:372-376) says gross spend can trigger false budget alerts. The brief must state what the budget shows when the home currency is unavailable.
5. Item 2 (foreign-currency settlements) covers rows no production code can create: there is no settlement writer (LEGAL_PATHS.md:752-754) and SettlementCalculator.recordSettlement (:170) has no caller. Keep, but it is not a user-visible fix.
6. Excluding mixed rows may hurt trust. Both columns default to 'EUR' (ExpenseGroup.kt:24, GroupExpense.kt:111). No way to change a group's currency; new writes are forced to group currency. A mismatch is most likely an old, mislabelled row whose amount really is in group currency; excluding it shrinks real debts. The user cannot fix it (R18 blocks group-linked edits). Hiding the plan is right; the notice must not claim rows are "in other currencies" and users need a way to see which rows were left out. Existence in real databases: UNVERIFIED.
7. Inc 1 item 5 overlaps Inc 3 Q10: the anomaly EUR comes from the entity default and can only be removed by writing the currency. In Inc 1 limit item 5 to the CancellationException fix at :101.
8. Inc 2 changes stored values, not only computation: SplitCalculator.calculateMemberShare sets the persisted myShareAmount (GroupTransactionCoordinator.kt:1065-1070). New JPY rows would store integer shares while old rows keep 333.33; personal effectiveAmount, budgets and analytics change. State this; T3; owner sign-off.
9. Is Inc 2 needed before settle-up? Settle-up has no writer and is not planned. Tie Inc 2 to the group-settle-up gate. SupportedCurrency has no 3-decimal currency (domain/currency/CurrencyConverter.kt:21-49): drop that claim (JPY, KRW, ISK are real). calculateSettlements is suspend (SettlementCalculator.kt:59), so it cannot run in Compose remember; it moves into the ViewModel or domain.
10. Q8 stop-and-ask is correct (deleting tests). Also needed: LEGAL_PATHS edits (:754, :765), removal of the scripts/guardrails/dao-approved-files.txt:20 entry, and SharedExpenseManager.calculateBalances is also dead and should be listed. "Migrate and keep" adds nothing for dead code.
11. Unlisted: SharedExpenseGroupsViewModel.kt:90 reads home currency outside the try block in loadGroups.
12. Tiers: Inc 1 T3 right; Inc 3 T2 fine; deleting tests is stop-and-ask regardless.
13. Privacy: no new risk. Timber.i merchant log (:199-206) correctly routed to privacy-guardian.

## Minimal reshaping that would flip the verdict
- Move item 4 into money-correctness 1a; drop Q13. Inc 1 then depends on neither ai-fixes nor 1a.
- Owner decision on old EUR-labelled recurring rules: accept R20a precedent, or show the total PARTIAL when a rule's currency is EUR and home is not.
- Name the domain owner for group balances (exclusion + count); include the LEGAL_PATHS:764-768 fix in Inc 1.
- Shrink item 5 to the budget-offset fallback (with budget behaviour written down) and the anomaly CancellationException fix. List dead EUR sites as hygiene only.
- Inc 2: state that persisted myShareAmount changes for new rows; gate Inc 2 on group-settle-up.

## Questions for the human
1. Old EUR-labelled recurring rules: convert (R20a) or PARTIAL?
2. Mixed group rows: exclude-and-count acceptable if the user cannot fix them? Should the notice list them?
3. Should Inc 2 belong to this sweep or group-settle-up?
4. Home currency unavailable in budget: gross spend or "unavailable"?
5. Approve deleting the two dead classes and their tests, plus LEGAL_PATHS and guardrail allowlist edits?
