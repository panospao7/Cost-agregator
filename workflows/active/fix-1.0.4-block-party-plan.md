# Plan: fix-1.0.4-block-party (batch E)

Written by feature-planner (read-only, Sonnet) on 2026-11-03; persisted by the orchestrator. Decisions are taken under owner item 116.

Paths are relative to `app/src/main/java/com/yourname/expensetracker/`. (U) = not verified.

**Limit of the planner's run.** It had no shell, so it read `build/review-wt` and `Cost-agregator-int`, not the tip. The orchestrator confirmed at 8de38722 that `SynthesisEngine.kt:598,602,631` and `ComputeDashboardWidgetsUseCase.kt:852,903` match. Other anchors are unconfirmed, so writers must re-anchor.

## Findings
- **BL-02 claim B, STILL-PRESENT, CRITICAL.** `SynthesisEngine.kt:598-608` subtracts `toMonthlyAmount` (1/12 of an annual bill) every month; :708 adds the full cash amount on the bill day (days from `recurringByDay`, :657-671). Worked example, recomputed by the planner: 30-day month, limit 2000, annual bill 1200 on day 15. Pool 1900, 63.33 a day. A non-due month sums to 1900; a due month sums to 3100 (limit + 1100). `synthesizeInternal:505-508` subtracts the full 1200 in the due month, so the two widgets disagree by 1100.
- **BL-02 claim A, same-day double count, DEMOTED.** Only an unreconciled same-day payment counts twice, and counting a bill not yet paid is conservative. Deferred, needs a reconciliation-timing design. The accrual use at `SynthesisEngine.kt:356-366` is correct and stays.
- **P-05-1, STILL-PRESENT, HIGH.** `ComputeDashboardWidgetsUseCase.kt:903` passes `monthAggregate.displayAmount` as the limit, so targets rise and fall with spending. Golden tests pass 2000.0 directly, which hides it.
- **P-05-6, STILL-PRESENT, HIGH.** `ctx.expenseEntities` (:578) maps all of `data.expenses`; `ctx.purchases` (:550) filters PURCHASE and not-mine, but :901 does not use it. Deposits and transfers can mark a day OVER_BUDGET. `TransactionSummary` has no type field, so the fix is at the call site.

## Rule: cash basis wins
`synthesizeInternal` already subtracts bills only in the month they fall, so Block Party does the same.
- Pool = limit - (every bill or planned item placed on a day this month) - goal reserves, with each bill on its due day at full amount.
- Invariant: sum of `targetBudget` over the month = limit - reserves, whenever the pool is not clamped at 0.
- Weekly and biweekly bills count actual occurrences, not 4.33x.
- Limit = overall budget if > 0, else the sum of category budgets, through a resolver shared with `synthesizeInternal:494-496`.

## Decisions (orchestrator, owner item 116)
1. **D1:** with no budget the card is hidden, since an unbudgeted target means nothing. Users with no budget lose this card; report it.
2. **D2:** keep pin P6-CURRENT-014: a PAID bill stays off its payment day, so that day can show OVER_BUDGET. Residual; report it.
3. **D3:** claim A deferred.

## Overlap (checked by the orchestrator with git)
- `origin/feature/fix-1.0.4-money-display` (03d69151) does not touch `SynthesisEngine.kt`. In the use case its hunks are at about :113 and :1301-1378, clear of `computeBlockParty` (:852-904).
- The neutralisation lane `build/fix3-wt` has uncommitted edits in the same use case, planned at `toExpenseEntity():595-616`. No textual overlap is expected, but it is semantically close: S2 reads `ctx.purchases`, and neutralised rows may need excluding. Whichever merges second must re-check that.

## Slices (lane `Cost-agregator-sfa`, `feature/fix104-block-party`, S1 then S2; starts when a lane frees)
- **S1. BL-02. T1, money.** Writer plus separate test-author. `domain/logic/SynthesisEngine.kt` only.
  - Compute the per-day recurring sums first. Pool = their sum + amounts of PAID occurrences due this month (so a payment does not widen the pool) + planned items. Replace the `toMonthlyAmount` pool at :598-608. Count conversion failures once.
  - Shared `resolveBudgetLimit(budgetStatuses)`, used at :494-496, changing no numbers.
  - Leave `buildRecurringByDayFromOccurrences` pinned; return PAID amounts separately (the PAID amount field is U).
  - Tests: new `domain/logic/SynthesisEngineBlockPartyCashBasisTest.kt`. Golden examples (April, 30 days, limit 2000): annual 1200 PLANNED on day 15 gives base 26.667, day 15 = 1226.667, sum 2000.0 (was 3100). A 30-day month with no bill gives base 66.667, sum 2000.0 (was 1900); the test uses June, because May has 31 days. Weekly 50 on days 3/10/17/24 gives pool 1800, base 60, sum 2000. Bills + planned > limit gives base 0, sum = bills + planned. PAID rent 800 on day 1 gives pool 1200, base 40, target sum 1200.
  - `SynthesisEngineGoldenTest` (monthly bill, 30/830/230) must stay green unchanged, along with `SynthesisEngineBlockPartyPaidExclusionTest`, `...MultiCurrencyTest`, `SynthesisEngineTest`.
- **S2. P-05-1 + P-05-6. T1, money.** Depends on S1. Writer plus separate test-author. `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, inside `computeBlockParty` only.
  - `expenses = ctx.purchases.map { it.toTransactionSummary() }`, keeping the `G-MONEY-ALLOW` tag on the same line (the guard matches per line).
  - `budgetLimit` from `resolveBudgetLimit(budgetStatuses)`. No budget, or an overall budget not complete in the home currency (as at :656), returns `emptyList()`.
  - No change to `buildContext`, `ComputeContext` or `ExpenseDao`.
  - Tests: new `ComputeDashboardWidgetsUseCaseBlockPartyTest`: limit 2000, spent 500 gives base 66.667; a 1000 deposit on day 3 plus a 20 purchase gives actual 20, UNDER_BUDGET, and only the purchase in `topTransactions`; a not-mine row is absent; no budget gives an empty list. Re-run `BlockPartyDstAlignmentTest`.

## Numbers users will see change
- Every Block Party daily target: lower in a bill month, higher in other months, no longer dependent on spend.
- Deposits and transfers stop marking days OVER_BUDGET; not-mine rows leave `topTransactions`.
- Users with no budget lose the card.
- Weekly bills are valued by actual occurrences.

## Validation
`targeted-unit-test` with `*SynthesisEngine*`, `*BlockParty*`, `*ComputeDashboardWidgetsUseCase*`; `static-guards` once; `app-check` on the candidate. No schema or guard change. Combined guardian per slice; `reviewer-strict` at the candidate.

## Status
| Slice | State |
|---|---|
| S1 | 24dd6ddd; 17 tests |
| S2 | 145f154f, bb4ceea4; 15 tests; targeted vr-20261103-082115-fdc97f4c 161/163, the two DST fixtures fixed, vr-20261103-082846-973bbbb8 PASS; guardian PASS-WITH-MINORS |

## Notes during implementation
- S2 addition beyond the plan: with no overall budget, category budgets could be in another currency, so summing them would mix currencies. The card is now unavailable unless every budget feeding the limit is complete in the home currency. Report this; it hides the card for more users than D1 alone.
- S2 residual: neutralised rows are in `ctx.purchases`, because `DashboardExpense` has no exclusion field. They add 0 to `actual` but can still show in `topTransactions`. The neutralisation lane restores the field in `toExpenseEntity()`; re-check at whichever merge comes second.
- Targeted vr-20261103-082115-fdc97f4c on 145f154f: 161 of 163 pass. `BlockPartyDstAlignmentTest` (spring forward and fall back) expected OVER_BUDGET with a 5000 budget and 42.5 spent, which held only because the limit was month spend, the P-05-1 bug. The orchestrator changed those two fixtures to a 620 budget, so the OVER_BUDGET assertion and the DST checks stay as strict, against the real limit. The test's DST values all passed before the status line. Reviewer to confirm. To commit and re-run after the guardian reports.
- Guardian on 145f154f, PASS-WITH-MINORS: a normal single-currency overall budget keeps the card (`hasCompleteAmountIn` accepts EXPLICIT_HOME_CURRENCY). A category-only budget with LEGACY_DEFAULT (migrated users) hides the card, which is new. A PAID bill's day can show OVER_BUDGET (D2). Not fully checked: whether planned rows created from recurring occurrences (`sourceOccurrenceKey`) double count against PLANNED occurrences; the old arithmetic had the same overlap. reviewer-strict to check.
