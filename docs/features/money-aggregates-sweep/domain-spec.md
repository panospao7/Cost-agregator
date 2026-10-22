# Domain spec — money-aggregates-sweep, Increment 1 only

Stage 3 (domain-modeler), 2026-10-21. Inputs: brief.md v2 (D1-D5 adopted), critique.md, LEGAL_PATHS.md:593-611 and :752-768.
Inc 2 belongs to group-settle-up; Inc 3 is owner-gated. Neither is specified here. Paths are short for
`app/src/main/java/com/yourname/expensetracker/...`; every `file:line` below was opened for this spec.

## 0. Global statements (binding)
- **No schema change.** No entity, column, index, DAO, or migration change. No backfill. No "legacy/excluded" flag is stored (brief Schema).
- **No rounding change.** SplitCalculator cents logic (SplitCalculator.kt:301-312), settlement BigDecimal application (GroupSettlementBalancePolicy.kt:21-22),
  RecurrenceCalculator multipliers (RecurrenceCalculator.kt:22-43) and CurrencyFormatter display stay as they are. Totals keep today's `Double` summation.
- **No new writer.** No new LEGAL_PATHS write path, no DAO write, no settlement writer. All Inc 1 rules are read and presentation rules.
- **No conversion is added.** No new CurrencyConverter call on any Inc 1 path (brief non-goal; R20a is not reused).

## 1. Shared definitions
- **D-HOME (home-currency read).** *Available* means the read returned a code. The first-run value "EUR" that `homeCurrency()` emits when the key is
  missing (CurrencySettingsRepositoryImpl.kt:37,42-45), and `FirstRunDefault` (HomeCurrencyResolution.kt:24), count as a real home currency, not a fallback.
  *Unavailable* means the read threw (other than cancellation), timed out, or returned `Failed`. On unavailable, no literal may be substituted.
- **D-CUR (currency equality).** Exact string equality, the same comparison the writer uses (GroupTransactionCoordinator.kt:313,747,942) and the
  settlement filter uses (GroupSettlementBalancePolicy.kt:32). Codes are not normalised on read. A row the writer accepts is always included.
- **D-AVAIL.** `Availability { AVAILABLE, PARTIAL, UNAVAILABLE }`. If the value is UNAVAILABLE, no amount is rendered in that place.
  If it is PARTIAL, the included-only amount is rendered together with a partial notice and a count.

## 2. Entities and value objects (read models only)
| Name | Fields (type, nullability, unit) | Owner |
|---|---|---|
| `GroupSettlementBalancePolicy.Availability` (nested enum) | AVAILABLE / PARTIAL / UNAVAILABLE | GroupSettlementBalancePolicy |
| `GroupSettlementBalancePolicy.DisplayedBalances` (nested data class) | `availability: Availability`; `currency: String` (group currency as stored; the unit of every amount below); `includedTotal: Double?` (major units, unrounded, null iff UNAVAILABLE); `memberBalances: Map<Long, Double>` (memberId to net, + is owed, empty iff UNAVAILABLE); `excludedExpenseCount: Int >= 0`; `excludedSettlementCount: Int >= 0`; derived `settlementPlanAllowed = availability == AVAILABLE` | GroupSettlementBalancePolicy |
| Entry point `displayedBalances(groupId: Long, groupCurrency: String, expenses: List<GroupExpense>, members: List<GroupMember>, settlements: List<SharedGroupSettlement>)` | pure, no I/O, no suspend | GroupSettlementBalancePolicy |
| `GroupWithDetails` (SharedExpenseGroupsViewModel.kt:52-62) | carries the `DisplayedBalances` result in place of its own `totalSpent`/`memberBalances` math | renders only |
| `GroupsUiState.homeCurrency` (:48-49) | becomes `String?`; null means not read yet or unavailable. The "EUR" placeholder is removed | SharedExpenseGroupsViewModel |
| Recurring total state in `ManualRecurringExpenseUiState` (:29-49) | `totalMonthly: Double?` (null iff UNAVAILABLE), `totalCurrency: String?` (home code used for inclusion), `totalStatus: AVAILABLE/PARTIAL/UNAVAILABLE`, `excludedRuleCount: Int >= 0` | ManualRecurringExpenseViewModel (OPEN-4) |
| Budget effective-spend state | `BudgetStatus.adjustedSpendBreakdown: AdjustedSpendBreakdown?` (BudgetModels.kt:24). **null means UNAVAILABLE.** No new field | BudgetRepository (producer) |
| Engine unavailable signal | existing `MoneyDisplayUnavailableException(MoneyDisplayUnavailableReasonCode.HOME_CURRENCY_UNAVAILABLE)` (domain/core/money/MoneyAggregateResult.kt:28-33; message = reason name only) | SharedExpenseBudgetOffsetEngine |

## 3. Invariants
**Group balances (single owner: GroupSettlementBalancePolicy)**
- **INV-1** Only `GroupSettlementBalancePolicy.displayedBalances` computes the displayed group total, member balances, availability and excluded counts.
  SharedExpenseGroupsViewModel renders the result and does no balance or total math. This removes the inline sum (:111), the inline
  applyToBalances (:112-115) and the private calculateBalances wrapper (:148-153). Per-row split display (:103, :144-146) is unchanged.
- **INV-2** An expense row is *included* iff `row.currency == groupCurrency` (D-CUR). An excluded row adds to neither balances nor total, and
  `excludedExpenseCount` = number of excluded rows.
- **INV-3** A settlement is *considered* iff `groupId` matches and `status in {RECORDED, COMPLETED}` (policy :32-33). A considered settlement in the group currency is
  applied with the existing sign contract (sender `paid` +, recipient `received` -, :36-39). A considered settlement in another currency is not applied and adds 1 to
  `excludedSettlementCount`; today it is silently skipped. A settlement that is not considered is ignored and **not** counted.
- **INV-4** Availability: UNAVAILABLE iff the group currency is blank or unknown (D4; definition OPEN-3). Otherwise PARTIAL iff
  `excludedExpenseCount + excludedSettlementCount > 0`. Otherwise AVAILABLE. If UNAVAILABLE, both counts are set to all rows of that kind (expenses, considered settlements).
- **INV-5** `includedTotal` = `Double` sum of `totalAmount` over included rows, using the same summation as today (:111). It is never the all-rows sum (D5).
  If UNAVAILABLE: `includedTotal = null`, `memberBalances = emptyMap()`, and the screen shows "unavailable" instead of a number.
- **INV-6** `memberBalances` = `SplitCalculator.calculateBalances(includedExpenses, members)` (SplitCalculator.kt:323-353, unchanged), then included settlements
  are applied through the existing `totals`/`applyTo`. **Regression:** if a group is AVAILABLE, the result equals today's output exactly.
- **INV-7** The suggested settlement plan (SharedExpenseGroupsScreen.kt:376-381, 422-432) renders iff `settlementPlanAllowed`. The solver is unchanged
  (deprecated `simplifyBalances` stays at :431; moving it belongs to group-settle-up).
- **INV-8** If PARTIAL, show one combined notice: "N item(s) left out of the total", where N = sum of both counts. The notice must not say "in other currencies" or similar.
  It contains no description, amount, or member name. UNAVAILABLE shows a bounded "balances unavailable" notice.
- **INV-9** The existing `totals()` and `applyToBalances()` keep their current behaviour. Their dead callers (GroupBalanceCalculator.kt:53, SharedExpenseManager.kt:305)
  see no change. No new callers of GroupBalanceCalculator or `SharedExpenseManager.calculateBalances()`.

**Groups list home currency (owner: SharedExpenseGroupsViewModel)**
- **INV-10** In `loadGroups` the home-currency read (:90) gets its own failure boundary, separate from the groups load (:94-140). CancellationException
  propagates. Any other failure sets `homeCurrency = null`. The groups list and balances still load and `error` stays null. "EUR" is never substituted.
- **INV-11** If `homeCurrency == null`, CreateGroupDialog (SharedExpenseGroupsScreen.kt:665-673) starts with no currency selected and Create stays disabled until the user
  explicitly picks a currency from the SupportedCurrency list. The default parameter `"EUR"` (:669) is removed. Writer validation is unchanged. Owner: CreateGroupDialog (input rule).

**Manual recurring total (owner: ManualRecurringExpenseViewModel; OPEN-4)**
- **INV-12** The monthly total sums `RecurrenceCalculator.toMonthlyAmount(amount, frequency)` (unchanged) only over **active** rules with
  `rule.currency == homeCode` (D-CUR, D1). Active rules in any other currency (EUR-labelled legacy or otherwise) are left out and counted in `excludedRuleCount`.
  Inactive rules are never summed or counted. No CurrencyConverter is used. This replaces the mixed sum at :93-95.
- **INV-13** `totalStatus`: UNAVAILABLE iff the home read is unavailable (D-HOME). Then the total is null, the rule list still loads, and `errorText` is not set
  (the read sits inside the load but is caught separately). Otherwise PARTIAL iff `excludedRuleCount > 0`, otherwise AVAILABLE.
- **INV-14** The total card label (ManualRecurringExpenseScreen.kt:142,379) uses `totalCurrency` from UiState, the same code that decided inclusion. It does not use
  the screen's separate `homeCurrency` flow (:47). Per-rule rows keep their own currency (:226,462).

**Budget effective spend (D2, D3)**
- **INV-15** SharedExpenseBudgetOffsetEngine (:77-80) never substitutes "EUR". A home read failure (other than cancellation) throws
  `MoneyDisplayUnavailableException(HOME_CURRENCY_UNAVAILABLE)` before any conversion. CancellationException propagates. Per-row `convertAsOf`
  behaviour (:116-179) is unchanged (its FX basis belongs to money-correctness inc 2). Owner: engine.
- **INV-16** `adjustedSpendBreakdown == null` is the one effective-spend-unavailable signal. Its sole producer is `BudgetRepository.computeAdjustedSpend`
  (:380-404; the only BudgetStatus constructor in main is :347), which maps **every** non-cancellation engine failure to null (D3). `BudgetViewModel.calculateAdjustedSpend`
  (:164-184) follows the same mapping and additionally rethrows CancellationException; today :180 catches it. Owner: BudgetRepository; the ViewModel only mirrors the mapping.
- **INV-17** If the breakdown is null, BudgetCard shows effective spend, remaining and over-budget as "unavailable", and the content description says "unavailable" with no amount.
  It must never show `status.spentAmount` in those places (BudgetScreen.kt:472, 475, 490-500, 602, 682, 690). `percentUsed` and `healthStatus` stay repository-owned (S8-001) and unchanged.
- **INV-18** BudgetMonitor (:243-246): if the breakdown is null, it runs no threshold comparison, sends no notification and updates no notified-at timestamp. It emits exactly one
  `STATUS_SKIPPED`/`SKIPPED` diagnostic with `grossFallback=true` and `reason=<OPEN-5>`, with no amount keys. Check order: the existing `PERCENT_UNKNOWN` (:255-280)
  first, then this skip, then `NO_SPEND_OR_LIMIT`. Nothing is persisted, so the next cycle re-evaluates. Gross spend never reaches an alert. Owner: BudgetMonitor.

**Cancellation and anomaly**
- **INV-19** AnomalyAlertOrchestrator.kt:101: a CancellationException from the home read propagates, reaching the existing rethrow at :207/:212, and `finally` :214-215 still runs.
  Any other failure keeps today's fallback to the expense currency. Nothing else in the anomaly path changes. Owner: AnomalyAlertOrchestrator.
- **INV-20** No Inc 1 home read or engine catch swallows CancellationException: engine :77, BudgetViewModel :180, SharedExpenseGroupsViewModel :90,
  ManualRecurringExpenseViewModel load, Anomaly :101. If an implementer uses `resolveHomeCurrency()`, it must call `currentCoroutineContext().ensureActive()`
  after a `Failed`, because the default (CurrencySettingsRepository.kt:86) and the impl (CurrencySettingsRepositoryImpl.kt:114) catch `Exception`.

## 4. State and lifecycle
No persisted state machine. Each availability value is a pure function of inputs read on each load:
groups `loadGroups()`/`refresh()`, recurring `loadRecurringExpenses()`, budget repository flow plus the monitor cycle.
- Retry: a reload recomputes from scratch, so there is no sticky PARTIAL or UNAVAILABLE.
- Cancellation: it propagates (INV-20) and no partial UI state is committed by the cancelled coroutine.
- Process death: nothing to restore; the next load recomputes.
- A home-currency change while a screen is alive: values computed earlier stay internally consistent (INV-14) until the next reload.

| Inputs (group) | Availability | Total | Plan |
|---|---|---|---|
| currency blank/unknown | UNAVAILABLE | null ("unavailable") | hidden |
| known; all rows match; no foreign considered settlement | AVAILABLE | sum of all rows (= today) | shown |
| known; >= 1 excluded expense or settlement | PARTIAL | included-only sum + notice(N) | hidden |

## 5. Edge cases
- **Empty group** (no expenses or settlements): AVAILABLE, total 0.0, every member 0.0, no transfers. **Empty recurring** (no active rules): AVAILABLE, 0.0.
- **All expenses excluded but a same-currency settlement included**: PARTIAL, total 0.0. Balances come from settlements only and are labelled partial; the plan is hidden. This is defined behaviour.
- **All active recurring rules foreign**: PARTIAL, total 0.0 in home, count = number of rules.
- **Mixed currencies / missing or stale FX**: not applicable in groups or recurring (no conversion). The budget engine's MISSING_RATE handling (:124-129) is unchanged.
- **Unavailable vs zero**: UNAVAILABLE never renders as 0 (INV-5, INV-13, INV-17). 0.0 appears only when it was computed.
- **Non-finite amounts**: unchanged. `BigDecimal.valueOf(NaN)` on an included settlement still throws and the list load fails (:34-35, VM :132-139). Excluded rows are counted without evaluating the amount.
- **Lower-case or legacy codes**: under D-CUR, "eur" vs "EUR" is a mismatch, so the row is excluded and counted (fail-closed). See OPEN-3 for the group currency itself.
- **Time zones, DST, month-end, recurrence dates, edits or deletes of past or recurring items, duplicates, receipts**: not touched. No date, write, or idempotency logic changes.
- **Budget percent/health** are still computed from gross aggregate spend (BudgetRepository.kt:227,323). Not changed here; this is not the "effective spend" place.

## 6. Persistence impact
None. There are no tables, columns, version bump, migration, snapshot, or backfill, and nothing destructive. Stored `myShareAmount` is untouched (that is an Inc 2 concern).

## 7. Privacy
- Notices hold availability and counts only. No row description, amount, member name, or currency list.
- New diagnostics: a single bounded reason literal (OPEN-5) plus the existing `grossFallback` boolean. No amounts, no exception text.
  The engine exception message is the reason enum name only, so if BudgetRepository.kt:401 logs it, nothing sensitive leaks.
  The existing Timber items (Anomaly :199-206, BudgetRepository :401) are with privacy-guardian and are not changed here.
- Nothing new is exported, sent over the network, or permission-gated.

## 8. Proposed LEGAL_PATHS change (applied in the implementation slice, serialised with other writers)
Replace LEGAL_PATHS.md:764-768 with:
```
CALCULATE balances (display):
  → GroupSettlementBalancePolicy.displayedBalances(groupId, groupCurrency, expenses, members, settlements)
  → Read-only, pure. Includes only rows/settlements in the group currency; excludes and counts the rest.
  → Returns availability (AVAILABLE/PARTIAL/UNAVAILABLE), included-only total, balances, excluded counts.
  → Suggested settlement plan only when AVAILABLE.
  GroupBalanceCalculator.calculateMemberBalance / SharedExpenseManager.calculateBalances():
  → no production caller; no new callers; pending owner decision (Inc 3).
```
Add under that section's FORBIDDEN list: `❌ Summing group amounts or calling SplitCalculator.calculateBalances for display outside GroupSettlementBalancePolicy`.

## 9. Acceptance examples (synthetic)
Shared fixture G: group id 7, currency "EUR"; members A=1 (payer of E1), B=2, both joined before all dates; EQUAL splits.
- **AE-1 (INV-1)** Given SharedExpenseGroupsViewModel.kt after the change, when it is scanned, then it contains no `sumOf` over expenses and no `SplitCalculator.calculateBalances`/`applyToBalances` call; GroupWithDetails values equal `displayedBalances(...)` output.
- **AE-2 (INV-2, INV-5)** Given G with E1 EUR 30.00 paid by A and E2 USD 20.00 paid by B, when displayedBalances runs, then PARTIAL, excludedExpenseCount=1, includedTotal=30.0 (not 50.0), balances {1:+15.0, 2:-15.0}.
- **AE-3 (INV-3)** Given G with E1 EUR 30.00 paid by A plus S1 RECORDED B to A 15.00 EUR, S2 COMPLETED B to A 5.00 GBP, S3 CANCELLED B to A 10.00 GBP, then balances {1:0.0, 2:0.0}, excludedSettlementCount=1 (S2 only), PARTIAL.
- **AE-4 (INV-4)** Given group currency "" with E1 "" 10.00, then UNAVAILABLE, includedTotal=null, balances empty, settlementPlanAllowed=false. Given G with only EUR rows and S1, then AVAILABLE.
- **AE-5 (INV-5)** Given G with EUR rows 10.10 and 20.20, then includedTotal == listOf(10.10, 20.20).sumOf { it } (bit-identical to today).
- **AE-6 (INV-6)** Given any all-EUR fixture for G (property or table test), then memberBalances == `applyToBalances(SplitCalculator.calculateBalances(all, members), settlements, 7, "EUR")`.
- **AE-7 (INV-7)** Given PARTIAL (AE-2), the settlement-plan section is not composed. Given AVAILABLE, it is composed and its transfers equal `simplifyBalances(memberBalances)`.
- **AE-8 (INV-8)** Given 1 excluded expense and 1 excluded settlement, then the notice shows count 2, and its text contains neither "currenc" nor any description or amount from the fixture.
- **AE-9 (INV-9)** Given the existing GroupSettlementBalancePolicyTest and GroupBalanceCalculatorTest inputs, `totals()`/`applyToBalances()` outputs are unchanged.
- **AE-10 (INV-10)** Given `homeCurrency()` throws IOException and the repository returns G, then `homeCurrency == null`, groups=[G], error=null. Given it throws CancellationException, then it propagates and no error state is set.
- **AE-11 (INV-11)** Given homeCurrency=null, when CreateGroupDialog opens, then no currency is selected and Create is disabled. After the user picks "GBP", `createGroup(currency="GBP")` is called.
- **AE-12 (INV-12)** Given home "GBP" and active rules R1 GBP 10.00 MONTHLY, R2 EUR 12.00 MONTHLY, R3 USD 5.00 MONTHLY, plus inactive R4 EUR 99.00, then total=10.0, totalCurrency="GBP", PARTIAL, excludedRuleCount=2, and the ViewModel has no CurrencyConverter dependency.
- **AE-13 (INV-13)** Given the home read throws IOException with R1..R4, then totalStatus=UNAVAILABLE, totalMonthly=null, 4 rules listed, errorText=null. Given home "EUR" (first run) and only EUR rules, then AVAILABLE.
- **AE-14 (INV-14)** Given state totalCurrency="GBP" while the screen flow emits "" (initial), then the card label is formatted with "GBP".
- **AE-15 (INV-15)** Given `homeCurrency()` throws IOException, then calculateEffectiveBudgetSpend throws MoneyDisplayUnavailableException(HOME_CURRENCY_UNAVAILABLE) and convertAsOf is never invoked. Given a CancellationException, then it propagates.
- **AE-16 (INV-16)** Given the engine throws MoneyDisplayUnavailableException or RuntimeException, then BudgetStatus.adjustedSpendBreakdown=null. Given the ViewModel fallback engine call throws CancellationException, then it propagates (no null).
- **AE-17 (INV-17)** Given spentAmount=80.0, effectiveLimit=100.0, breakdown=null, then the card shows "unavailable" for spend and remaining, and neither the visible text nor the content description contains "80".
- **AE-18 (INV-18)** Given breakdown=null, percentKnown=true, spentAmount=95.0, limit=100.0, then there is no notification, no update*Notification call, one STATUS_SKIPPED {grossFallback:true, reason:<OPEN-5>}, and no STATUS_COMPUTED. Given percentKnown=false and breakdown=null, then a single event with reason PERCENT_UNKNOWN. Given a breakdown with effectiveSpend=95.0, alerts fire as today.
- **AE-19 (INV-19, INV-20)** Given `homeCurrency()` throws CancellationException in the anomaly check, then it propagates, no alert is written, and the expense id is removed from in-flight. Given IOException, then normalisation uses the expense currency (today's behaviour).

## 10. Test and guard impact (for test-author and orchestrator)
- The BudgetMonitorTest helper `budgetStatus()` (:684-702) leaves the breakdown null, so under INV-18 the alert tests (e.g. :186, :316, :522, :609, :642) would skip.
  The fixture needs a breakdown, and `monitor_flags_gross_fallback_when_adjusted_null` (:552-580) changes to assert STATUS_SKIPPED (OPEN-6).
- CancellationPropagationContractTest.kt:88 anchors `offsetEngine.calculateEffectiveBudgetSpend(` inside computeAdjustedSpend. Keep that call text.
- config/baselines/cancellation.json: G-CANCEL-02 AnomalyAlertOrchestrator (count 1) and possibly G-CANCEL-01 BudgetViewModel (count 1) may ratchet down.
  That is a guard-file edit, so the orchestrator must confirm it (FG-07).

## 11. Open decisions (recommended default in brackets; scope items are NOT decided here)
- **OPEN-1 (scope).** The groups *list* labels every group total with home currency: `groupCurrency = selectedGroup?.currency ?: homeCurrency` (SharedExpenseGroupsScreen.kt:50), passed to every GroupCard (:158, 222) and formatted at :299. A EUR group therefore shows as GBP for a GBP-home user, and the list has no label when home is null (INV-10). Brief item 6's "balances are in group currency" is false for list labels. [Include in Inc 1: label each card with `group.currency`. Otherwise the success signal "no number labelled in home currency includes unconverted amounts" cannot pass.]
- **OPEN-2 (scope).** Detail expense rows are formatted with the group currency (:604), including excluded rows whose stored currency differs. [Leave as is in Inc 1, per the non-goal "listing excluded rows"; critique #6 says such rows are likely mislabelled.]
- **OPEN-3.** Meaning of "unknown" group currency (D4). [Unknown iff `CurrencyCode.parse(x)?.code != x` or `SupportedCurrency.fromCode(x) == null` (CurrencyConverter.kt:57-63). Inactive codes such as HRK count as known.]
- **OPEN-4.** Recurring-rule owner location. [Keep it in ManualRecurringExpenseViewModel as one private function, following the R20a precedent SubscriptionManagementViewModel.kt:183-213. No second copy. A domain extraction can be decided later.]
- **OPEN-5.** BudgetMonitor skip reason literal for D2. [`EFFECTIVE_SPEND_UNAVAILABLE`, next to the existing `grossFallback=true`. D2's "existing gross-fallback bounded reason" is a key, not a reason value, so one new bounded literal is needed.]
- **OPEN-6.** Approval of the test adaptations: BudgetMonitorTest fixture and :552 assertion, new GroupSettlementBalancePolicyTest cases, SharedExpenseGroupsViewModel tests for the new GroupWithDetails shape. [Approve as test-only adaptations; no deletions.]
- **OPEN-7.** BudgetViewModel recomputes through the engine when the repository returned null (:114-115), a second computation site. [Keep in Inc 1 with the INV-16 mapping, per brief 4(b); propose removing it later.]
- **OPEN-8.** Scope of the success-signal grep "no EUR fallback literal in Inc 1 files". SharedExpenseGroupsViewModel.kt:238-245 (null-group only) is Inc 3. [The grep covers :49, :90, Screen :669 and engine :77-80. :238-245 stays Inc 3.]
