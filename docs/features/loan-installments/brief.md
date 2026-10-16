# Feature brief: loan-installments — Installment Overview & Installment-Declared Expenses

Author: feature-strategist (stage 1 reshape — the single allowed pass). Inputs: prior brief, `critique.md`
(RESHAPE), owner answers 2026-10-16. Persisted by feature-orchestrator, 2026-10-16.
Status: RESHAPED DRAFT — awaiting owner confirmation. Docs-only.

## What changed from the previous draft

- Interpretation E is the core: one overview across all finite payment plans (owner).
- One spending rule: an installment purchase counts as spending one installment at a time, each in the period
  it falls due — never all at once (owner).
- New capability: mark an expense (new or already recorded) as paid in installments by setting the number of
  payments and how the amount is split (owner).
- Fixed rate only, entered at payment level. Amortization engine (principal + rate + term, interest split)
  moves to increment 2 (owner + critique).
- Code claims corrected and re-checked against source.
- Informal borrowed/lent money (C) not in increment 1.

## Problem

People paying for something over time can't see what they still owe, when each plan ends, or what is due next —
card installments (άτοκες δόσεις), pay-later, tax payment plans, fixed-rate bank loans. Worse, the app records
an installment purchase as one full-amount expense on day 0, overstating spending in the purchase month and
understating it in every month the user actually pays.

## Target users / situations

- Greek card holder buying 600 € paid as 12 × 50 €; often the bank notification already captured the purchase
  as one expense (`domain/parser/parsers/GreekBankParser.kt` focus).
- Pay-later / store financing and tax/government plans (structurally the same; folded in).
- Fixed-rate bank loan or mortgage holder, tracked by payment: "N payments of X left, ends on date D".
- Anyone wanting one screen answering "what am I committed to, and until when?"

## The spending rule (single rule, owner-defined)

> An installment-declared purchase or plan contributes to spending exactly its installment amounts, each counted
> in the period it falls due. The full purchase amount never counts by itself. Over the plan's life, the counted
> total equals the sum of the installments — never more, never less.

Concept-level implications (no design):
- **Spending totals / Home / Transactions.** 600 € in 12 × 50 € shows 50 € in each of 12 months, not 600 € in
  month 1. If the original captured expense is kept as the purchase record (provenance, receipt, dedupe), it
  counts 0 toward spending.
- **Budgets.** Each monthly budget sees only that month's installment, in the purchase's category. Not-yet-due
  installments are commitments, not spending. "Committed in future months" hint is later.
- **Analytics / category trends / savings engines.** Per-month installment amounts; essential-category logic
  (`SmartSavingsEngine`, `AutomatedSavingsRuleEngine`) sees the same. No separate analytics rule.
- **Cash flow / forecast / runway.** Future installments are known, bounded outflows; nothing projected past the
  final payment.
- **Fixed-rate loans (payment level).** Each full payment counts as spending in its period. Disbursement is never
  income or spending in v1. "Only interest is spending" is a later option, not v1.
- **Double count — the central case.** No period may count both the full purchase and an installment, and no
  installment may count twice. Colliding sources:
  1. the captured full-amount purchase (notification, receipt, statement);
  2. the installments the plan produces;
  3. monthly "ΔΟΣΗ n/N" charges captured later.

  The domain-modeler must define one enforcement point. Source constraint: spending aggregates are largely SQL —
  `ExpenseDao.kt` has ~44 `SUM(` queries and ~72 `isNotMine = 0` filters; `Expense.effectiveAmount` is documented
  as the value "all calculations must use". A new "doesn't count" dimension must reach every aggregate, as
  ownership exclusion does today. Read-time spreading in every reader would be even wider.

## Must-have scope (increment 1)

1. **Mark an expense as paid in installments.**
   - Existing expense (manual or captured: notification, receipt, statement). Set: number of payments; split
     (equal split of total, or fixed payment × N where total may exceed purchase price due to fixed rate/fee);
     frequency (monthly default); first due date.
   - New expense: same choice inside the add-expense flow.
   - The original expense is kept, not deleted — it is the purchase record the spending rule neutralises,
     preserving receipt links, provenance and dedupe keys.
2. **Plan without a purchase.** Create a finite plan directly (loan, mortgage, tax plan): name, free-text lender
   label, currency, fixed payment amount, N, frequency, first due date.
3. **Installment overview (the E core).** Active plans with next due date/amount, payments made vs left, end date.
   "Remaining scheduled payments" per currency, labelled as such (never "outstanding principal"), never summed
   across currencies without explicit conversion provenance.
4. **Plan detail.** Schedule with each installment paid / due / upcoming / skipped. With a same-currency purchase
   attached: "financing cost = total of installments − purchase amount" (plain subtraction). Unknown values show
   "unavailable", never 0.
5. **Confirm and link payments.** An installment is fulfilled by linking a real expense (plan-produced or a captured
   monthly charge). Manual link always available. Skip/adjust leaves an audit trail.
6. **Existing surfaces.** Upcoming installments appear where upcoming recurring items appear today (bill reminders,
   cash flow, forecast). No second reminder system.
7. **Storage and export.** Local only, encrypted backup/restore, respects export privacy modes.

### Fixed-rate decision for v1

"Fixed rate" in v1 = **entered at payment level**: user types the constant payment the lender quotes, × N.
Nothing computed from principal + rate + term.
- **Why.** A fixed-rate annuity payment is constant, so remaining payments, end date and remaining committed amount
  behave exactly like card installments; no day-count, nominal-vs-effective (ΣΕΠΕ) or residual-rounding conventions;
  the lender's statement gives the payment.
- **Trade-off accepted.** Users knowing only principal and rate must look up their payment. No interest/principal
  split or "interest paid so far" in v1.
- **Increment 2.** Computed schedule from principal/rate/term as a pure derived calculator with golden-schedule tests.

### Legal path (concept level)

- **Expense rows.** Every expense row (installment expenses and any change to the purchase expense) goes through
  `TransactionLifecycleCoordinator` → `ExpenseDao.insertAtomic()` (or coordinator update operations) only. The
  feature never writes expenses or touches the expense DAO directly.
- **Plan/schedule state.** Exactly one lifecycle owner: extended recurring rule coordinator or a new plan
  coordinator (domain-modeler decides).
- **Atomicity.** Declaring installments = purchase stops counting + plan starts; atomic or recoverable without
  partial state. Cross-coordinator atomicity is a domain question.
- **Capture pipelines unchanged in v1.** Notification/receipt/statement capture create the purchase expense as
  today; the user declares installments afterwards. Receipts stay attached to the purchase expense via receipt
  lifecycle services; receipt total stays the purchase amount.

## Later increments

- Computed fixed-rate schedule with interest/principal split, total interest, interest paid so far (increment 2).
- "Committed future installments" hint in budgets.
- Variable/Euribor-linked rates with mid-life recalculation.
- Early/extra payments, payoff what-ifs, snowball/avalanche.
- On-device "ΔΟΣΗ n/N" recognition suggesting a link/declaration — behind notification-capture toggle, bounded
  reason codes, no raw text stored.
- Informal borrowed/lent money (C), possibly reusing group settlement ideas.
- Liabilities next to Investment Tracking (net worth); debt insights in Analytics/health score.
- Fees/insurance breakout, grace periods, payment holidays.

## Non-goals

- No bank/lender API, open banking, credit-bureau access. No new network calls.
- No cloud AI for advice or extraction.
- No loan origination, comparison, refinancing offers, marketing.
- No APR/regulatory disclosures; figures are estimates, lender statement authoritative.
- No revolving credit-card balance tracking.
- No principal/interest spending semantics in v1.
- No change to how non-installment expenses count.
- No automatic installment declaration from captured content in v1.

## Existing functionality reused / overlapped

(re-verified; paths under `app/src/main/java/com/yourname/expensetracker/`)
- `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` — sole expense write path; own duplicate
  detection (`findDuplicateIdForExpense`, dedupe keys). Repeated same-merchant/same-amount monthly installments must
  not be treated as duplicates (not verified how they would be).
- `data/database/entity/Expense.kt` — `amount: Double` (57); `effectiveAmount` (163–169) documented totals
  chokepoint, already zeroes `isNotMine` and applies shared share — precedent for a "does not count" dimension.
- `data/database/dao/ExpenseDao.kt` — ~44 `SUM(` aggregates, ~72 `isNotMine = 0` filters (enforcement reach).
- `domain/core/money/MoneyAmount.kt:21-24` — stores `Double` ("M02 PARTIAL … requires BigDecimal or minorUnits
  Long"); not exact; rounding needs an explicit rule.
- `data/database/entity/ManualRecurringExpense.kt` + `RecurringRuleLifecycleCoordinator.kt` — single `Double`
  amount, no end/count, rolling 12-month regeneration.
- `data/database/entity/RecurringOccurrence.kt` — extension points: free-form `sourceType` (RECURRING_RULE,
  DETECTED_PATTERN, SUBSCRIPTION, PLANNED), per-row `expectedAmount`, `linkedExpenseId`,
  PLANNED/PAID/SKIPPED/MISSED/CANCELLED/IGNORED.
- `domain/recurring/RecurringOccurrenceExpander.kt` — bounded only by requested range; no rule-level end (plain
  reuse projects past payoff); no per-occurrence override (residual has nowhere to go).
- `RECURRING_RULE` source-type hard filters (verified) — a separate source type is invisible unless wired in:
  `domain/cashflow/CashFlowCalculator.kt:210,521`; `domain/forecasting/ForecastInputAssembler.kt:483`;
  `domain/forecasting/FinancialStressForecastEngine.kt:327`; `domain/recurring/RecurringPlanProjectionService.kt:69`;
  `domain/logic/SynthesisEngine.kt:873`; `domain/health/FinancialHealthScoreV2.kt:589` (Bill Reliability,
  documented query contract).
- `domain/recurring/lifecycle/RecurringLifecycleCoordinator.linkExpenseToOccurrence` (312–354) — auto-link same
  calendar day, same merchant key, ±10%, same currency; ignores source type (plan and rule could claim each other's
  expenses); business-day shifts often miss. Upcoming-payment event metadata holds expense ID, amount, currency
  (~381–385) — check whether event logs reach any export.
- `data/database/entity/PlannedExpense.kt` + Home "add planned expense" (`ui/screens/home/HomeViewModel.kt:550`) —
  dated one-off planned payments read by forecasting/synthesis; chained to occurrences via `sourceOccurrenceKey` /
  `sourceRecurringRuleId`, with `linkedActualExpenseId`. Overlaps "upcoming installment"; candidate surface.
- `data/backup/BackupVerifier.kt:522` — orphan check flags any `recurring_occurrences` row whose `sourceId` isn't in
  `manual_recurring_expenses`, regardless of `sourceType` (false failures or silent pass on ID collision).
- `domain/privacy/CloudPayloadPolicy.kt` — per-purpose text preparation only (`prepareText`,
  `prepareReceiptAssist`, `prepareBankStatementValidation`); no per-data-type allowlist. Exclusion must be at payload
  assembly; candidate `CloudPayloadPurpose.DASHBOARD_BRIEFING` (`CloudPayloadRedactor.kt:30`); briefing assembly
  reads of recurring/planned data not yet checked.
- Reminders: `domain/reminder/BillReminderManager.kt`, `service/reminder/BillReminderWorker.kt` — reuse.
- Add-expense flow: `ui/screens/addexpense/AddExpenseViewModel.kt` (new expense entry); transactions/expense detail
  screens (existing expense entry).
- Shared Expense Groups / `domain/groups/SettlementCalculator.kt` — overlaps only deferred C.

## Market patterns (reliability varies)

- YNAB Loan Planner — payoff simulator, extra-payment scenarios (first-party). https://ynab.com/blog/ynab-loan-planner
- Monarch — one plan across debts, snowball/avalanche (secondhand). https://robberger.com/best-debt-payoff-apps/
- Wallet by BudgetBakers — "Debts and Loans" next to planned payments (section titles).
  https://support.budgetbakers.com/hc/en-us/sections/6950462319250-Getting-Started
- Spendeezy — listing claims installment plans and debts alongside recurring (unverified).
  https://mwm.ai/apps/simple-budget-app-spendeezy/6740217240
- BudgetFlow user request — separate end-dated purchases (laptop over 10 months) from indefinite fixed expenses.
  https://budgetflow.featurebase.app/p/dedicated-chart-for-purchases-in-installments
- Brazilian card practice — each installment on a different statement, limit committed for full amount; supports
  "count per installment, show remaining commitment separately".
  https://antecipafacil.com.br/artigo/compras-parceladas-no-cartao-sem-juros-explicado-de-forma-simples
- Greek context — card installment conversion (incl. 12 interest-free for tax), pay-later without card (news, some 2014–2017).
  https://www.powergame.gr/forologia/115709/foros-eisodimatos-mechri-kai-12-atokes-doseis-i-exoflisi-stis-trapezes/ ,
  https://www.newmoney.gr/roh/palmos-oikonomias/trapezes/snappi-pay-later-dosis-choris-karta-apo-ti-neobank/amp/
- Gap (weak): Organizze/Mobills installment recording unverified (https://www.mobills.com.br/blog/parcelamento-da-fatura-do-cartao-de-credito/
  searched, no app-feature evidence); splitting one recorded purchase into per-month installments looks uncommon.

## Privacy & permissions impact

**Low** for increment 1; **high** if "ΔΟΣΗ n/N" recognition is added later.
- Fully on device: no network, no new permissions, no cloud AI, no new reading of notification/OCR/receipt content.
  Declaring installments on a captured expense uses only stored expense fields.
- Plan data (lender label, counts, schedule) is sensitive: encrypted backup respecting `BackupPrivacyMode` and export
  privacy modes; retention/restore barriers; never in diagnostics/logs/reason codes; excluded at cloud payload
  assembly (no allowlist exists).
- Installment expenses are ordinary expenses and follow existing cloud/export policy.
- Later recognition: notification-capture toggle, fail closed, no raw text persisted.

## Success signal (no content-leaking telemetry)

- Declared 600 € / 12: 50 € in each of 12 periods, 0 € extra in the purchase month; lifetime total = sum of
  installments. Unit tests on totals, budget and analytics readers.
- No period counts both purchase and an installment; no installment linked to more than one expense; local integrity
  check reports counts only.
- Remaining payments and end date match the lender's statement; per-installment amounts follow the stated residual
  rule within one minor unit.
- Projections and reminders show nothing after the final installment.
- Backup verify/restore passes with plans present; no false orphan errors.
- Nothing about plans leaves the device.

## Release timing

**Post-1.0** (owner confirmed). Touches schema/migration, money, expense and recurring lifecycles, backup
verification, reminder workers → planner-first, high-risk. Docs-only under `docs/features/loan-installments/` until
the release candidate is accepted.

## Open owner questions (still unanswered)

1. **Currency.** EUR-only in v1, or non-euro plans (e.g. CHF mortgage)?
2. **Test case.** One real plan to test first: card installments, pay-later, tax plan or bank loan?
3. **Borrowed/lent money.** Needed at all (C), or are group settlements enough? Deferred either way.
4. **Editing/cancelling.** If the amount is edited after declaring, the plan is cancelled, or paid off early, what
   should spending show? (e.g. revert to full amount on purchase day, or count remaining balance on payoff date)
5. **Unconfirmed past installments.** Count in their month even if not confirmed/linked (totals right, but wrong if
   skipped), or only once confirmed (totals dip until confirmed)?
6. **Per-installment bank notifications.** When the bank sends a notification per monthly installment, should that
   charge fulfil the planned installment, or would you rather not declare a plan for such cards?

## Domain questions for domain-modeler (flagged, not resolved)

1. **Reuse vs separate lifecycle** (single writer either way), decided against: (a) expander has no rule-level end
   and no per-occurrence residual override; (b) `RECURRING_RULE` filters in CashFlowCalculator, ForecastInputAssembler,
   FinancialStressForecastEngine, RecurringPlanProjectionService, SynthesisEngine, FinancialHealthScoreV2 Bill
   Reliability contract; (c) `BackupVerifier.kt:522` orphan check ignoring `sourceType`; (d) same-day/merchant/±10%
   auto-link ignoring source type; (e) `PlannedExpense` chain as existing surface.
2. **Spending-rule enforcement point** across ~44 SQL `SUM(` aggregates plus Kotlin readers (`effectiveAmount`
   precedent), without deleting captured originals.
3. **When installments become expenses**: auto on due date (worker: `WorkerExecutionGuard`, barriers,
   `WorkerRegistry`), on user confirmation, or counted virtually? Interacts with owner Q5.
4. **Double-count resolution** across captured purchase, plan-produced installments, later "ΔΟΣΗ" charges (compare
   `CrossSourceDeduplication`); coordinator duplicate detection must not merge legitimate monthly installments.
5. **Atomic declaration** across `TransactionLifecycleCoordinator` and the plan owner; clean undeclare.
6. **Money representation**: exact internal schedule rounded at storage vs first minor-unit storage (app-wide precedent).
7. **Rounding and residual**: minor-unit rounding, residual on first or last payment, half-up vs banker's, where the
   residual lives given expander limits.
8. **Plan lifecycle**: edit after payments, early payoff, cancel, delete; unlink never delete expenses; clean PLANNED
   rows and reminder deliveries; audit trail; effect on purchase spending status.
9. **Currency**: per-currency totals; conversion only with `RateBasis` provenance; financing cost same-currency only.
10. **Schema and backup**: version bump, migration, snapshots, migration tests, verifier tier, orphan-check update;
    additive only.
11. **Privacy plumbing**: exclusion at cloud payload assembly (briefing path); whether recurring event metadata
    reaches any export.
12. **Time**: month-end due dates, timezone, business-day shifts vs auto-link; `TimeProvider` / `TimePeriodUtils` only.
13. **Category and savings consistency**: installments inherit purchase category; savings engines see per-period amounts.

## Owner addenda (recorded by orchestrator, 2026-10-16 — owner decisions, not strategist output)

- **Currency:** EUR + USD in v1; per-currency totals, no silent cross-currency sum.
- **Test data:** mock/golden data in tests is sufficient.
- **Budgets:** installments count in the monthly budget of the month they fall due.
- **Scope addition — plan history:** completed (paid-off) and cancelled plans remain viewable, so the user keeps a
  picture of past loans/installments (e.g. total paid, start/end dates, final status). Domain-modeler must check
  interaction with retention/cleanup policies and backup.
- **Cancel/edit semantics:** owner leans "cancel altogether" and asked for an opinion; domain-modeler to formalise
  distinct cases (stop plan / refund-return / early payoff) with a recommendation for owner decision.
- **Lent/borrowed money (C):** DEFERRED to increment 2 (owner, 2026-10-16) as a separate lightweight "money owed"
  record (person, direction, amount, date, optional expected-back date, repayment log, open/settled), not a payment
  plan and not spending/income; shown later as its own section in the overview and in history. Not in v1.
- **Gate 1: GO** on this reshaped brief (owner, 2026-10-16).

## Recommendation

**Pursue (post-1.0).** Reshape resolves the critique's blocking objections: one owner-defined spending rule applied
uniformly to purchases and fixed-rate loans; payment-level fixed-rate entry, no amortization engine in increment 1;
code claims corrected. Planning waits on domain-modeler decisions for domain Q1–5 and owner answers on currency,
test case and edit/cancel semantics.
