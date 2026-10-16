# Critique: loan-installments

Author: feature-critic (stage 2, independent; read the brief only). Persisted by feature-orchestrator, 2026-10-16.

**Verdict: RESHAPE.** The gap is real and confirmed in source. Increment 1 as written still bundles two
features whose spending rules contradict each other, adds an amortization engine, and makes four claims about
existing code that are wrong or incomplete.

## Brief claims checked against source

- **Gap claim: correct.** Only essential-category keyword lists (`domain/savings/SmartSavingsEngine.kt:677`,
  `domain/savings/AutomatedSavingsRuleEngine.kt:351`) and unrelated "μισθοδοσία" (salary) parser hits.
- **`ManualRecurringExpense` claim: correct.** `amount: Double`, single amount, no end date/count;
  `RecurringRuleLifecycleCoordinator.createRule` regenerates `addMonths(start, 12)` (lines 168, 230, 422).
- **"Free if loans reuse recurring": wrong in both directions.**
  - Downstream readers hard-filter on `sourceType == SOURCE_TYPE_RECURRING_RULE` and active rule IDs:
    `domain/cashflow/CashFlowCalculator.kt:210,521`, `domain/forecasting/ForecastInputAssembler.kt:483`,
    Bill Reliability query `domain/health/FinancialHealthScoreV2.kt:589`. A separate plan source type is invisible to all.
  - Plain reuse isn't free: `domain/recurring/RecurringOccurrenceExpander.kt` only bounds by requested range, no
    rule-level end, so projections/forecasts would show payments after payoff unless the expander changes. Every
    recurring reader goes through it.
- **Domain Q4 implies `MoneyAmount` is exact: wrong.** `domain/core/money/MoneyAmount.kt:21-24` stores `Double`
  ("M02 PARTIAL … requires BigDecimal or minorUnits Long"); `Expense.amount` is `Double`
  (`data/database/entity/Expense.kt:57`). Either exact-internal schedule rounded at stored-money boundaries, or
  this feature becomes the app's first minor-unit storage (app-wide precedent).
- **"Excluded from cloud AI by default via `CloudPayloadPolicy`": no such mechanism.** `domain/privacy/CloudPayloadPolicy.kt`
  applies per-purpose redaction only, no per-data-type allowlist. Exclusion must be enforced at payload assembly
  (candidate: `CloudPayloadPurpose.DASHBOARD_BRIEFING`, `domain/privacy/CloudPayloadRedactor.kt:30`; briefing
  assembly not checked for recurring/planned reads).
- **Overlaps the brief misses:**
  - `data/database/entity/PlannedExpense.kt` + Home "add planned expense" flow (`ui/screens/home/HomeViewModel.kt:550`):
    dated one-off planned payments already read by forecasting and synthesis.
  - `data/database/entity/RecurringOccurrence.kt` already has free-form `sourceType` and per-row `expectedAmount`
    — obvious extension points shaping reuse-vs-separate.

## Blocking objections

1. **Bundling A with B/D + "mark paid by linking to an expense" (must-have 4) gives contradictory spending meaning.**
   - B: the 600 € purchase is usually already captured as one expense; linking each 50 € installment double-counts.
   - A: the monthly debit is the real expense; arguably only interest is spending.
   - "What counts as spending" differs by plan type and by surface (accrual-style spending/budgets vs cash flow).
     Increment 1 can't ship without choosing, yet the brief defers it while committing to must-have 4.
2. **Backup orphan check blocks sharing the occurrence table.** `data/backup/BackupVerifier.kt:522` treats any
   `recurring_occurrences` row whose `sourceId` isn't in `manual_recurring_expenses` as orphan regardless of
   `sourceType`. A plan lifecycle writing occurrences under a new source type would fail verification, or pass
   silently when a plan ID collides with a rule ID. Must be part of the reuse decision.
3. **Increment 1 includes an amortization engine** (principal+rate+term, interest split, total interest), pulling
   day-count, nominal vs effective rate and residual-rounding (Domain Q5–6) into the first slice. Payment-level
   tracking needs none of it: a fixed-rate annuity payment is constant, so A behaves like B for remaining
   payments, end date and remaining committed amount. Interest split can be a later pure derived calculator.

## Cautions

- **Auto-link timing.** `RecurringLifecycleCoordinator.linkExpenseToOccurrence` (323–354) matches only same calendar
  day, same merchant key, amount ±10%, same currency. Business-day shifts → frequent misses → manual linking. Ignores
  source type, so a plan and a rule with same lender/amount could claim each other's expenses.
- **Remaining balance.** "Sum of remaining scheduled payments" ≠ "lender's outstanding principal" for A. Label it;
  unknown rate/fees → "unavailable", never 0.
- **Final-payment rounding.** Occurrence rows can hold per-payment amounts, but the expander has no per-occurrence
  override; the residual has nowhere to go without new work. Success signal needs a stated residual rule.
- **Currency.** Per-currency totals fine; converting (e.g. CHF→EUR) needs `RateBasis` provenance or no conversion.
- **Privacy.** Keep loan data out of diagnostics/reason codes. Upcoming-payment events from
  `RecurringLifecycleCoordinator` store expense ID, amount, currency in event metadata (381–385) — check whether
  event logs reach any export.
- **Reversibility.** Nullable column or new table is additive. Deleting a plan must unlink (never delete) expenses
  and clean PLANNED rows and reminder deliveries.
- **Release timing.** Post-1.0 is correct; nothing in `app/` while assurance is pending.

## Minimal reshape that flips the verdict to GO

- Increment 1 = **payment tracking only**: a fixed-amount plan with a known number of payments (covers B/D, and A at
  payment level). Remove principal/rate/term input, interest split, total interest → increment 2 as a pure derived
  calculator with golden-schedule tests.
- **State one spending rule before domain modeling**, e.g.: plan payments are commitments shown in cash flow and
  reminders, and don't count toward spending, budgets or analytics unless linked to a real captured expense; a plan
  may optionally link to the original purchase expense to block the 600 € + 12 × 50 € double count.
- Reuse-vs-separate decision must explicitly cover: expander's missing rule-level end; `RECURRING_RULE` source-type
  filters in `CashFlowCalculator`, `ForecastInputAssembler`, `FinancialHealthScoreV2`; orphan check at
  `BackupVerifier.kt:522`; same-day auto-link matching.
- Correct the brief's code claims: `MoneyAmount` is `Double`; `CloudPayloadPolicy` has no data-type allowlist;
  PlannedExpense overlaps.

## Questions the owner must answer before domain modeling

1. Which case first, A (bank loan) or B (card installments / pay-later)? One real plan to test with?
2. For card installments, is the full purchase already one expense in your data? Should the plan hang off that
   expense, with installments as commitments only?
3. Should plan payments count against monthly budgets and spending analytics, or only appear in cash flow and reminders?
4. Is "payments left × amount" acceptable as the remaining figure in v1, without interest/principal?
5. Extend recurring rules with an end (touches every recurring reader, one shared mechanism) vs separate plan
   lifecycle (isolated, but explicit wiring into cash flow, forecast, reminders, backup checks)?
6. Any non-euro plans in v1, or EUR-only?
7. Confirm: post-1.0 only, docs-only until the release candidate is accepted.
