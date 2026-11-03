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

## reviewer-strict on 92346ad0: REJECT (round 1 of 2)
- BLOCKER, BL-02 stays open in production: `RecurringPlanProjectionService.kt:78-94` creates a MUST planned row for each PLANNED occurrence; `DashboardContractsAdapter.kt:113-131` passes all rows and drops `status`, so FULFILLED and CANCELLED rows read as PLANNED; `ForecastInputAssembler.assembleNormalized` (:714-788) has no dedupe, unlike `assemble()` :526-529. Recurring bills count twice. Annual bill alone, limit 3000: correct base 60, old arithmetic 56.67, candidate 20, so in due months the candidate was further from correct than the old code. No Block Party test used `sourceOccurrenceKey`; the guardian had flagged this as unchecked.
- MAJOR, category-only budgets: all purchases are compared with a partial limit (FORE-05 comment, `SynthesisEngine.kt:530-531`), so Groceries 300 plus rent 800 gives base 0 and any spending day is red. Decision (orchestrator, item 116): Block Party needs an overall budget above 0, otherwise the card is hidden. Consistent with money-display's SafeToSpend, which uses overall only. Report it: users with only category budgets lose the card.
- MINOR: legacy fallback places a biweekly bill on about 5 days per cycle, so a 100 bill removes about 1000 from the pool (217 before). `synthesizeInternal` disagreement remains on the dashboard path, because `confirmedOccurrences` is empty (P-05-2, not in this batch).
- For the neutralisation lane: neutralised rows can take top-3 slots showing 0 (`SynthesisEngine.kt:708` sorts by raw amount). That lane also edits `DashboardContractsAdapter`, in a different hunk.

## Fix pass, round 1 (source; tests following)
- `DashboardContractsAdapter.kt:131` maps `status`. `ForecastInputAssembler.assembleNormalized` keeps only PLANNED rows and drops rows with a non-null `sourceOccurrenceKey`. `assemble()` shares `withoutRepresentedOccurrences`, with the same behaviour.
- Wider than Block Party, and correct by the writer's trace: `synthesizeInternal` committed and likely totals, runway `totalCommitted` (runway gets longer) and the MUST-planned insight count all lose the double count. Stress uses another path and is unchanged. Report this: other widgets' numbers change.
- `computeBlockParty` needs an overall budget above 0, complete in the home currency. Category-only budgets hide the card.
- Biweekly: `BIWEEKLY_TOLERANCE_DAYS` removed, one day per 14-day cycle. This also changes `synthesizeInternal` and the existing test pinning the +/-2 tolerance, which the plan had said would stay unchanged. Reviewer to judge.
- Invariant corrected: target sum = limit - reserves - PAID when not clamped, not limit - reserves.
- A keyed planned row is dropped only when an active rule places that bill this month (`sourceRecurringRuleId` equals the manual pattern's id, and the rule has an occurrence row this month or `nextExpectedDate` is in the row's month). Otherwise it counts once, so a bill cannot vanish. Added after the test-author showed a 700 bill disappearing with no pattern.
- Guard catch: static-guards on 92346ad0 (vr-20261103-085000-1875b34e) failed `raw_money_aggregates` G-MONEY-RAW-01 on `sumOf { it.amount }` at `SynthesisEngine.kt:685-686`. These values are already converted once, so the holder's field is now `convertedAmount`. Guard unchanged. 26 of 27 guards passed.
- Lane targeted vr-20261103-091743-b353e692 PASS on ca0f71b8; later commit is a field rename and a comment.

## reviewer-strict on c6c51b19: ACCEPT-WITH-MINORS (round 2 of 2)
Round 1 findings closed; hand computations match (annual case base 60, sum 3000; mixed June base 18.33, sum 2050). To apply before merge: m3 tests, m6 comments.
- m3: `DashboardContractsAdapterTest` does not pin `sourceRecurringRuleId`; deleting `DashboardContractsAdapter.kt:132` restores the double count in production and no test would fail. `BlockPartyRecurringChainTest` hand-builds rows and bypasses the adapter and service, and its DAO mock ignores the range. No test covers a non-PLANNED occurrence with a PLANNED keyed row, or a DAO throw.
- m6: stale text at `SynthesisEngine.kt:86-87` (shared limit) and :610-612 (invariant omits PAID).
Follow-up lane, not fixed before merge (production changes after the last review round):
- m1, paid then paused: rent 800 PAID on day 1, rule paused on day 10. The PAID occurrence goes unread and the FULFILLED row is filtered, so with limit 3000 the base goes 73.33 to 100 and day 1 turns red. At 8de38722 the row still counted. Narrow regression.
- m4: `ForecastInputAssembler.kt:737-740` swallows a blocked read with no partial flag or log, unlike DBG-06 at :487-493. During a restore the double count shows unflagged. Transient.
- m5, orphan keyed row: no normal path creates one. Monthly rule with no occurrence rows and a rolled date next month: row kept and legacy also places it, counting twice. IRREGULAR rule: row dropped and never placed.
- Plan line about `synthesizeInternal`: it never calls `isRecurringExpected`, so the biweekly change does not affect it.
Not verified by the reviewer: `SynthesisEngine` receiving a non-null DAO under Hilt; which code marks occurrences MISSED.
- m3, m6 applied: d48994e7, targeted vr-20261103-100224-a5ef6ebb PASS. Gates on c6c51b19: raw_money_aggregates vr-20261103-093152-72cd2b21 PASS, targeted vr-20261103-093209-61f3c955 PASS, static-guards vr-20261103-094246-67256b70 PASS. Later changes are tests and two comments, so app-check runs on the final commit.
