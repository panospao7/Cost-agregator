# Domain spec: loan-installments — Installment Overview & Installment-Declared Expenses

Author: domain-modeler (stage 3). Inputs: `brief.md` (Gate 1 GO, owner addenda binding), `critique.md`.
Status: AMENDED 2026-10-16 — owner decisions recorded in section 14 (OD-1…OD-15 DECIDED). Docs-only, post-1.0.
No `app/` changes.

## Amendment log (2026-10-16)

The owner approved the slice plan (`workflows/active/loan-installments-plan.md`: "Proposed defaults" OQ-18 / OQ-24,
"Human decisions required" items 3, 4 and 5). These changes are binding:
- **AMD-1 (OQ-18, plan-gate item 3): new entry status `PAID_BEFORE_TRACKING`.** The entry-status model is chosen.
  Plan-level columns are rejected. The status is stored as a TEXT value, so there is no DDL impact. Changed:
  sections 2.2, 2.4, 3.1, 3.2, 5.2, 7.0, 7.1, 7.2, 7.3, 7.5, 8.1, 11.1, 12. New INV-32. Amended INV-6, INV-15,
  INV-20, INV-21, INV-26. New AE-26, AE-27, AE-28.
- **AMD-2 (OQ-24, plan-gate item 4): financing cost** = Σ (PAID linked amounts, PAYOFF included + SCHEDULED +
  PAID_BEFORE_TRACKING amounts) − purchase amount. SKIPPED, CANCELLED and REVERSED are excluded. Changed: sections
  2.4, 7.3, 9. Amended INV-17. New AE-29. AE-9, AE-11 and AE-18 are unchanged (their figures already match the
  formula).
- **AMD-3 (plan-gate item 5): plan category.** A standalone plan needs a user-picked existing spending category.
  Nothing is preselected and no category is created. None of the 20 defaults is a Loan/Mortgage/Bills/Debt
  category (`discovery-s0.md` section 7), so the "concrete default category from D-7" is moot. A purchase-attached
  plan copies the purchase `categoryId` exactly, and null stays null. Changed: sections 2.1, 6 Q13, 7.0. New
  INV-33. New AE-30.
- **AMD-4: OD-1…OD-15 are DECIDED** (section 14). The recommended options are accepted. OD-15 activates only if
  the planner's D-10 proves that single-transaction composition is impossible.
- **Derived rules (they follow from existing invariants and are listed so the owner can see them):**
  - (a) A declaration whose k covers every entry commits as COMPLETED in the same transaction (INV-21).
  - (b) `PAID_BEFORE_TRACKING` does not trigger `CURRENCY_LOCKED`. It has no linked expense, so there is nothing
    to mismatch.
  - (c) The financing-cost PAID term uses the linked expense's gross `amount`, not `effectiveAmount`.
  - (d) The payoff-date lower bound also takes `PAID_BEFORE_TRACKING` due dates into account.
- Everything else is unchanged: INV-1…INV-31 except those listed above, the schema impact (additive) and AE-1…AE-25.

## Amendment log (2026-10-23, S5 review)

[amended S5-R, 2026-10-23] These edits align the spec with decisions made in the S5 review
(`workflows/active/loan-installments-handoff.md`, "S5 review fixes"). Changed: sections 3.1, 3.2, 5.4 R4, 7.0, 7.5 and
11.1, and INV-14, INV-20, INV-33. There are no new invariants and no schema impact. The Delete source set is
OWNER CONFIRMED 2026-10-23 (section 7.5). Each change carries the tag `[amended S5-R, 2026-10-23]`.

## Amendment log (2026-10-26, pre-S7a reconciliation)

[Amended 2026-10-26 (A2)] These edits align the spec with plan amendment A2 (S7a) in
`workflows/active/loan-installments-plan.md` (G2-6 convergence, MEC AE-29) and with the binding S7a notes in
`workflows/active/loan-installments-s7a-discovery.md` sections 4 to 6. Each change carries the tag
`[Amended 2026-10-26 (A2)]`. Changed: sections 5.2 and 6 Q9. New INV-34 and AE-31. Schema impact: none.
- **FX deferral removed.** `AUTO_RECORD_DEFERRED_FX` no longer exists. Plan-created expenses never set the
  user-present rate flag (`userPresentRate = null`; only `declareWithNewExpense` sets it, plan A1), so TLC never
  refuses them for a missing rate. A USD expense recorded without a rate is a sentinel row, excluded from
  comparable-home-value totals; its display is plan risk R17 / slice S8b (section 6 Q9).
  `AUTO_RECORD_DEFERRED_CANDIDATE` (INV-13) is unchanged.
- **Blank plan name (INV-34, AE-31).** A plan whose `displayName` is blank (in practice a plan restored from a
  redacted backup, IM-9) records no expense until the user renames it. Auto-record, confirm and payoff use the
  bounded reason `PLAN_NAME_REQUIRED`. There is no fallback merchant.
- **Foreground trigger (D-19).** The foreground catch-up trigger is overview-open only. There is no startup hook and
  no persisted marker. Until the overview exists (S10), only the worker runs catch-up (section 5.2).
- **Proposed follow-up (orchestrator; not decided here):** declare, create-standalone and the E3 edit currently accept
  a blank `displayName` (S6a open question 7, S6b decision 1). Refusing a blank name there with `INVALID_VALUE`
  would make INV-34 reachable only through a redacted restore. Recommended, but it changes implemented S6a/S6b
  behaviour, so it needs an explicit decision.

Source facts re-checked for this spec (beyond those cited in the brief/critique):
- `Expense.effectiveAmount` / `normalizedEffectiveAmount` (`data/database/entity/Expense.kt:163-185`) zero
  `isNotMine`; there is no other "does not count" dimension today.
- `TransactionLifecycleCoordinator` exposes `createExpenseDbOnlyV2` (returns planned side effects for use inside an
  outer transaction, `:890`), `createExpenseStandaloneV2`, `updateExpense` and typed update ops, `deleteExpense`
  (`:2444`). `DeduplicationMode.STRICT_EXTERNAL_ID` requires an idempotency key, never fuzzy-matches, and resolves an
  idempotent retry to the existing expense (`:107-147`, `:202-203`, `:596-621`, `:783`).
- Fuzzy duplicate window is 5 minutes, amount ±0.01, same currency (`LEGAL_PATHS.md` RP-11). Monthly installments
  of one plan can't collide with each other under it. Two plans with the same merchant, amount and due date
  would collide if recorded at the same instant. That is why installment expenses use STRICT_EXTERNAL_ID (section 5).
- `recurring_occurrences` columns (`RecurringOccurrence.kt:23-41`): `sourceType`, `sourceId`, unique `occurrenceKey`,
  `dueDate` (epoch millis, start of day), `expectedAmount: Double`, `linkedExpenseId`, `paidAmount`, `status` String.
- `BackupVerifier.kt:522` check is
  `recurring_occurrences WHERE sourceId NOT IN (SELECT id FROM manual_recurring_expenses)`, with no `sourceType` scope.
- `RetentionPolicyContract.kt:29-62`: purge targets include `transaction_events` (snapshots 30 d, rows 365 d), but
  not `expenses` or recurring tables. Plan history therefore can't depend on `transaction_events`.
- `TimePeriodUtils` provides day/week/month/quarter/year ranges and month keys. It has no add-months helper, so
  schedule date arithmetic is specified here (section 10).

## 1. Scope recap & binding owner decisions

**Increment 1 scope** (from brief "Must-have scope"):
1. Mark an existing or new expense as paid in installments (number of payments, split method, frequency, first
   due date). The original expense stays as the purchase record.
2. Create a standalone finite plan with no purchase (loan, mortgage, tax plan) using a fixed payment × N.
3. Installment overview showing active plans, next due, paid vs left, end date and remaining scheduled payments
   per currency.
4. Plan detail with the schedule and entry states. Financing cost is shown only when it can be computed.
5. Confirming or linking a payment fulfils an installment. Manual linking is always available. Skip and adjust
   leave an audit trail.
6. Upcoming installments appear on existing upcoming-payment surfaces (reminders, cash flow, forecast). There is
   no second reminder system.
7. Plans are stored locally only, included in encrypted backup/restore, and follow privacy modes.

**The spending rule (binding, owner):** an installment-declared purchase or plan adds to spending exactly its
installment amounts, each counted in the period it falls due. The full purchase amount never counts on its own.
Over the plan's life, the counted total equals the sum of the installments actually paid.

**Binding owner decisions (addenda 2026-10-16):**
- B-1 Currency: v1 supports EUR and USD. Totals are kept per currency. Currencies are never summed silently.
- B-2 Budgets: an installment counts in the budget of the month it falls due, in the plan's category.
- B-3 Plan history: completed (paid-off) and cancelled plans stay viewable with total paid, start/end dates and
  final status.
- B-4 Lent/borrowed money: deferred to increment 2 as a separate "money owed" record. Out of scope here.
- B-5 Fixed rate is entered at payment level only (constant payment × N). There is no amortization engine,
  no interest/principal split and no rate input.
- B-6 Test data: mock/golden data is enough.
- B-7 Cancel/edit: the owner leans towards "cancel altogether" and asked for an opinion. See section 7 and OD-3.
- B-8 Capture pipelines are unchanged in v1. Nothing is declared automatically from captured content.

## 2. Concepts & entities

Concept level only. The field lists say what must be representable; the planner decides the DDL.

### 2.1 InstallmentPlan

| Field | Type / unit | Null | Notes |
|---|---|---|---|
| id | Long | no | row id |
| planUuid | String (UUID) | no | stable identity, unique. Generated once per create/declare form session (idempotency, section 4) and used in installment expense idempotency keys |
| kind | enum `PURCHASE_ATTACHED` \| `STANDALONE` | no | `PURCHASE_ATTACHED` may turn into `STANDALONE` only by detaching (purchase deleted, section 7.6) |
| purchaseExpenseId | Long → expenses.id | yes | unique when not null. Null for STANDALONE |
| displayName | String, user text | no | defaults to the purchase merchant. **Sensitive** |
| lenderLabel | String, user text | yes | free text (bank, store, tax office). **Sensitive** |
| currency | enum `EUR` \| `USD` (ISO 4217, scale 2) | no | one currency per plan. Can't change once any entry is PAID/REVERSED |
| splitMethod | enum `EQUAL_SPLIT` \| `FIXED_PAYMENT` | no | STANDALONE is always FIXED_PAYMENT |
| declaredTotal | Double, plan currency, 2 dp | yes | EQUAL_SPLIT: the amount split (defaults to the purchase amount when currencies match). FIXED_PAYMENT: null. Kept for history only; the live total is always derived from entries |
| fixedPaymentAmount | Double, 2 dp | yes | FIXED_PAYMENT only |
| installmentCount | Int, 2..480 | no | N as declared. Edits add or cancel entries; this keeps the original N for history |
| frequency | enum, v1: `MONTHLY` (see OD-8) | no | |
| firstDueDate | civil date (epochDay) | no | anchor for date arithmetic |
| anchorDayOfMonth | Int 1..31 | no | day-of-month of the anchor. Kept so the anchor survives clamped months |
| residualPlacement | enum `FIRST` \| `LAST` | no | recorded at creation so regeneration is reproducible (OD-6) |
| categoryId | Long? | yes | PURCHASE_ATTACHED: copied exactly from the purchase at declaration (null stays null). STANDALONE: required, an existing spending category the user picked, never null (INV-33, AMD-3). Applies to installment expenses created from then on |
| paymentMethod | existing `PaymentMethod` | no | copied from the purchase, or UNKNOWN |
| fulfilmentMode | enum `AUTO_RECORD` \| `CONFIRM` | no | section 6 Q3, OD-4/OD-5 |
| declaredOnDate | civil date | no | "today" at creation. Auto-record never applies to entries due before it |
| status | enum, section 3.1 | no | |
| statusReason | bounded code | yes | e.g. `USER_STOP`, `USER_REFUND`, `USER_PAYOFF`, `AUTO_COMPLETED` |
| createdAt / updatedAt / terminalAt | epoch millis (TimeProvider) | terminalAt yes | |

### 2.2 InstallmentEntry (one row per scheduled payment, plus at most one payoff row)

| Field | Type / unit | Null | Notes |
|---|---|---|---|
| id | Long | no | |
| planId | Long → plans.id | no | |
| seq | Int ≥ 1 | no | 1..N for REGULAR. Extensions continue at N+1… |
| kind | enum `REGULAR` \| `PAYOFF` | no | unique (planId, seq, kind). At most one PAYOFF per plan |
| dueDate | civil date (epochDay) | no | PAYOFF: the payoff date |
| scheduledAmount | Double, plan currency, 2 dp | no | > 0 |
| originalScheduledAmount | Double, 2 dp | yes | set on the first manual adjust (audit) |
| status | enum, section 3.2 | no | |
| statusReason | bounded code | yes | e.g. `USER_SKIP_LENDER_HOLIDAY`, `PAID_OFF_EARLY`, `PLAN_STOPPED`, `EXPENSE_REMOVED`, `SHORTENED` |
| linkedExpenseId | Long → expenses.id | yes | **unique** when not null. Required when status ∈ {PAID, REVERSED}. Always null when status = PAID_BEFORE_TRACKING (INV-32) |
| linkOrigin | enum `AUTO_RECORDED` \| `USER_CONFIRMED` \| `USER_LINKED` | yes | set together with linkedExpenseId |
| autoRecordConsumed | Boolean | no | true once auto-record has created an expense for this entry. It never resets (INV-12) |
| recordGeneration | Int ≥ 0 | no | goes up by 1 every time the entry returns to SCHEDULED from PAID (not from PAID_BEFORE_TRACKING, which never had an expense). Part of the expense idempotency key, so a later confirm never re-resolves to an expense the user unlinked |
| adjustReason | bounded code | yes | |
| statusChangedAt / adjustedAt | epoch millis | adjustedAt yes | audit trail without `transaction_events` |

Derived (never stored): `paidAmount` = linked expense `effectiveAmount` in plan currency. Display state
UPCOMING / DUE / OVERDUE for SCHEDULED entries comes from `dueDate` compared with today's civil date (section 10).

### 2.3 Expense: one new dimension

- `spendingExclusionReason`: nullable bounded code. Values: `null` (counts as today), `INSTALLMENT_PURCHASE`
  (purchase record of a plan, counts 0), `INSTALLMENT_REVERSED` (installment expense of a REFUNDED plan, counts 0).
- `effectiveAmount` and `normalizedEffectiveAmount` return 0.0 when it is non-null, the same way `isNotMine` works today.
- This is a denormalised flag. The plan rows are the source of truth (INV-2). It exists so that SQL aggregates
  and Kotlin readers can exclude rows without joins.

### 2.4 Concept glossary

- **Purchase-attached plan**: a plan hanging off an existing expense (captured or manual). The expense stays as a
  provenance/receipt/dedupe record and counts 0.
- **Standalone plan**: a loan, mortgage or tax plan with no purchase expense. The disbursement is never recorded as
  income or spending in v1.
- **Installment**: one REGULAR entry. It counts as spending only through the real expense linked to it.
- **Installment expense**: an ordinary expense row linked to an entry (auto-recorded, user-confirmed, or a captured
  charge the user linked). It follows all existing expense rules, including cloud/export policy.
- **Remaining scheduled payments**: the sum of SCHEDULED entry amounts per currency. It is never labelled
  "outstanding principal".
- **Paid before tracking**: a REGULAR entry, due before `declaredOnDate`, that the user says was paid before the
  app tracked the plan (`PAID_BEFORE_TRACKING`, INV-32). It has no expense and counts 0. It appears only as
  "k paid before tracking" in detail and history (AMD-1).
- **Financing cost**: Σ (PAID linked amounts, PAYOFF included + SCHEDULED + PAID_BEFORE_TRACKING amounts) minus the
  purchase amount. SKIPPED, CANCELLED and REVERSED entries are excluded (section 9, AMD-2). It is shown only under
  the conditions in INV-17.

## 3. State machines

### 3.1 Plan

States: `ACTIVE`, `COMPLETED`, `CANCELLED` (Stop), `REFUNDED` (refund/return), `PAID_OFF` (early payoff).
Deletion is not a state. It removes the plan (section 7.5). There is no persisted DRAFT: the form state is UI-only
and declaration is atomic.

| From | To | Trigger | Who | Effect |
|---|---|---|---|---|
| (none) | ACTIVE | declare on expense / create standalone | user → plan coordinator | plan + entries inserted. Purchase flagged `INSTALLMENT_PURCHASE` in the same transaction (INV-27). The first k entries may be inserted as PAID_BEFORE_TRACKING (section 3.2, INV-32). If no entry is left SCHEDULED, INV-21 completes the plan in the same transaction |
| ACTIVE | COMPLETED | the last SCHEDULED entry becomes PAID, SKIPPED or PAID_BEFORE_TRACKING | plan coordinator, in the same transaction as that entry change | terminalAt set. Stops projecting |
| COMPLETED | ACTIVE | a PAID entry reopens (linked expense deleted, unlinked, currency/type changed), a SKIPPED entry is unskipped, or a PAID_BEFORE_TRACKING entry is reverted to SCHEDULED | user or reconcile → plan coordinator | the only allowed exit from a terminal-looking state |
| ACTIVE | CANCELLED | Stop | user | SCHEDULED → CANCELLED(`PLAN_STOPPED`). PAID entries stay counted. Purchase stays at 0 |
| ACTIVE, COMPLETED | REFUNDED | Refund/return ("reverse everything") | user | SCHEDULED → CANCELLED(`PLAN_REFUNDED`). PAID → REVERSED and their expenses flagged `INSTALLMENT_REVERSED`. Purchase stays at 0 |
| ACTIVE | PAID_OFF | Pay off early (amount, date) | user | SCHEDULED → CANCELLED(`PAID_OFF_EARLY`). One PAYOFF entry created PAID, linked to an expense |
| ACTIVE, CANCELLED (no PAID/REVERSED entries; PAID_BEFORE_TRACKING entries don't block) | deleted | Delete (undo declaration) | user | entries (PAID_BEFORE_TRACKING included) and plan removed. Purchase flag cleared, so the purchase counts in full again on its own date. Only these two source states (section 7.5, OWNER CONFIRMED 2026-10-23) [amended S5-R, 2026-10-23] |

Illegal: any transition out of CANCELLED, REFUNDED or PAID_OFF other than the delete row above. Deleting a plan that
has PAID/REVERSED entries (`PLAN_HAS_PAYMENTS`). PAID_OFF from COMPLETED (nothing left). Any transition on a plan
whose purchase has been deleted while kind is still PURCHASE_ATTACHED (reconcile detaches it first).
[amended S5-R, 2026-10-23] Delete checks payments first: a plan with any PAID or REVERSED entry is refused
`PLAN_HAS_PAYMENTS` from every status. Without payments, Delete from COMPLETED, REFUNDED or PAID_OFF is refused
`ILLEGAL_TRANSITION` (section 7.5).

Retry, cancellation and process death (applies to every row):
- Each transition is one DB transaction inside the write barrier, with compare-and-set on the current status.
  Repeating a transition when the plan is already in the target state returns `AlreadyInState`, which counts as
  success. A different current state returns `IllegalTransition(code)`.
- [amended S5-R, 2026-10-23] Entry-level retries depend on the plan status too (section 3.2): a retried "mark paid
  before tracking" on an entry that is already PAID_BEFORE_TRACKING is `AlreadyInState` in an ACTIVE or COMPLETED
  plan (so a retry after the mark completed the plan succeeds), and `ILLEGAL_TRANSITION` in a CANCELLED, REFUNDED
  or PAID_OFF plan.
- A coroutine cancellation rolls back the transaction. `CancellationException` is always rethrown.
- If the process dies inside the transaction, nothing is persisted. If it dies after commit but before post-commit
  side effects (budget recheck, reminder suppression), the integrity reconcile (section 5.4) and read-time
  computation cover it. Budgets and totals are computed on read, so they are never stale.

### 3.2 InstallmentEntry

Stored states: `SCHEDULED`, `PAID`, `SKIPPED`, `CANCELLED`, `REVERSED`, `PAID_BEFORE_TRACKING` (AMD-1, INV-32).
Derived display for SCHEDULED: `UPCOMING` (due > today), `DUE` (due = today), `OVERDUE` (due < today, unrecorded).
`PAID_BEFORE_TRACKING` gets no derived display state. It is shown as "k paid before tracking".

| From | To | Trigger | Who | Precondition |
|---|---|---|---|---|
| SCHEDULED | PAID | auto-record (catch-up) | worker/foreground → plan coordinator | plan ACTIVE, mode AUTO_RECORD, `declaredOnDate` ≤ due ≤ today, `autoRecordConsumed = false`, no candidate charge (INV-13) |
| SCHEDULED | PAID | confirm ("mark paid", creates expense) | user | plan ACTIVE. `dueDate` ≤ today (local civil date, section 10). If today is unknown, the confirm is refused `ILLEGAL_TRANSITION` (fail closed; INV-14) [amended S5-R, 2026-10-23] |
| SCHEDULED | PAID | link an existing expense | user | plan ACTIVE. Link policy passes (INV-5, INV-16). No due-date gate: linking an existing expense to a not-yet-due entry stays allowed [amended S5-R, 2026-10-23] |
| PAID | SCHEDULED | unlink, or linked expense deleted / currency changed / type changed to non-expense | user, or reconcile (side effect of a TLC update/delete) | plan ACTIVE or COMPLETED (a COMPLETED plan reopens) |
| PAID | CANCELLED(`EXPENSE_REMOVED`) | linked expense deleted, currency changed or type changed to non-expense. `EXPENSE_REMOVED` is the one reason for all three causes; there are no separate currency or type codes. A user unlink is not offered here (it needs plan ACTIVE or COMPLETED) [amended S5-R, 2026-10-23] | reconcile | plan CANCELLED or PAID_OFF (stays terminal) |
| SCHEDULED | SKIPPED | skip (bounded reason: exactly one of `USER_SKIP_LENDER_HOLIDAY`, `USER_SKIP_WAIVED`, `USER_SKIP_OTHER`; OQ-20, owner-approved) [amended S5-R, 2026-10-23] | user | plan ACTIVE, unlinked. A skip on an auto-recorded entry means deleting that expense through the normal expense delete first. A missing reason or any other reason is refused `ILLEGAL_TRANSITION`. A retry on an already SKIPPED entry is `AlreadyInState` whatever reason it carries [amended S5-R, 2026-10-23] |
| SKIPPED | SCHEDULED | unskip | user | plan ACTIVE or COMPLETED |
| SCHEDULED | SCHEDULED (amount/date changed) | adjust | user | audit fields set (INV-19) |
| SCHEDULED | CANCELLED | stop / refund / payoff / shorten | user via plan op | |
| PAID | REVERSED | refund | user via plan op | the expense is flagged `INSTALLMENT_REVERSED` in the same transaction |
| REVERSED | CANCELLED(`EXPENSE_REMOVED`) | reversed expense deleted by the user | reconcile | |
| (inserted) | PAID_BEFORE_TRACKING | declare / create with "payments already made: k". The first k REGULAR entries by `seq` are inserted in this state | user → plan coordinator, in the declaration transaction | each of the k entries has `dueDate < declaredOnDate`, i.e. k ≤ P, where P = the number of REGULAR entries due before `declaredOnDate`. Otherwise `INVALID_PAID_BEFORE_TRACKING` and nothing is written |
| SCHEDULED | PAID_BEFORE_TRACKING | bulk "mark paid before tracking" on selected overdue entries | user → plan coordinator | plan ACTIVE. Every selected entry is REGULAR, unlinked and has current `dueDate < declaredOnDate`. All or nothing: one ineligible entry refuses the whole request (`INVALID_PAID_BEFORE_TRACKING`) |
| PAID_BEFORE_TRACKING | SCHEDULED | revert ("not paid before tracking") | user → plan coordinator | plan ACTIVE or COMPLETED (a COMPLETED plan reopens). `recordGeneration` and `autoRecordConsumed` are unchanged. The entry becomes OVERDUE-unrecorded and is never auto-recorded (INV-12: due before `declaredOnDate`) |
| PAID_BEFORE_TRACKING | (removed) | plan Delete | user via plan op | it is not a payment, so it never blocks Delete (section 7.5) |

PAID_BEFORE_TRACKING entries are untouched by Stop, Refund, Pay off, shorten, re-spread and E4. They stay in
that state in CANCELLED, REFUNDED and PAID_OFF plans.

Illegal: PAID → SKIPPED (unlink first). Any change to a PAID entry's amount (edit the expense instead). Any change
from CANCELLED or REVERSED other than the delete-cleanup rows above. A second link on a PAID entry. Auto-record on an
entry with `autoRecordConsumed = true`. Also illegal for PAID_BEFORE_TRACKING:
- PAID_BEFORE_TRACKING → PAID, SKIPPED or CANCELLED directly. The user reverts to SCHEDULED first.
- Linking or confirming an expense on a PAID_BEFORE_TRACKING entry.
- Changing a PAID_BEFORE_TRACKING entry's amount or date.
- PAID_BEFORE_TRACKING on a PAYOFF entry, or on an entry with `dueDate ≥ declaredOnDate`.
- Any PAID_BEFORE_TRACKING transition (mark or revert) on a CANCELLED, REFUNDED or PAID_OFF plan
  (`IllegalTransition`).

Retry, cancellation and process death: auto-record, confirm and link each run as one transaction. Inside it:
expense create via `createExpenseDbOnlyV2` (STRICT_EXTERNAL_ID key
`installment:<planUuid>:<kind>:<seq>:<recordGeneration>`), then a compare-and-set on the entry
(`WHERE status='SCHEDULED' AND linkedExpenseId IS NULL AND recordGeneration = :g`). If the compare-and-set claims
0 rows, the whole transaction rolls back, so no expense is left behind. If the create resolves to an existing
expense with the same key, that expense is linked. This is defensive: it covers the fallback where the two writes
can't share one transaction (section 6 Q5). Post-commit side effects run through the returned batch.

Mark and revert for PAID_BEFORE_TRACKING each run as one transaction, with no TLC call and no expense touched. Mark
does a compare-and-set
`WHERE status='SCHEDULED' AND linkedExpenseId IS NULL AND kind='REGULAR' AND dueDate < :declaredOnDate`, and revert
does a compare-and-set `WHERE status='PAID_BEFORE_TRACKING'`. Selected entries that are already in the target state
count as `AlreadyInState`. If the claimed row count is lower than the number of remaining selected entries, the
transaction rolls back. INV-21 completion and the COMPLETED → ACTIVE reopen happen in the same transaction. A
cancellation or process death rolls back everything.

[amended S5-R, 2026-10-23] Retry over plan status, for mark and revert:
- A CANCELLED, REFUNDED or PAID_OFF plan is refused `ILLEGAL_TRANSITION` first, even when the entry is already in
  the target state.
- Mark: an entry already PAID_BEFORE_TRACKING is `AlreadyInState` in an ACTIVE or COMPLETED plan. This covers a
  retry after the mark itself completed the plan (INV-21). An actual move SCHEDULED → PAID_BEFORE_TRACKING still
  requires plan ACTIVE.
- Bulk mark: in a COMPLETED plan, if every selected entry is already PAID_BEFORE_TRACKING the request succeeds with
  nothing to write. If any selected entry still needs a move, the whole request is refused `ILLEGAL_TRANSITION`.
- Revert: an entry already SCHEDULED is `AlreadyInState` in an ACTIVE or COMPLETED plan.

## 4. Invariants

Each invariant names exactly one owner. "Plan coordinator" means `InstallmentPlanLifecycleCoordinator` (proposed
name, OD-1). "TLC" means `TransactionLifecycleCoordinator`. AE-n refers to section 13.

**Spending rule and double count**

- **INV-1 Purchase neutralisation.** An expense with `spendingExclusionReason ≠ null` adds 0 to every spending
  aggregate: SQL `SUM`/`AVG`/`COUNT`-as-spending, budget usage, analytics, category trends, savings-engine inputs,
  forecast baselines, and Kotlin sums over `effectiveAmount`/`normalizedEffectiveAmount`. Owner: the expense
  spending-inclusion rule, defined once as `Expense.effectiveAmount` plus one canonical SQL predicate constant
  (`isNotMine = 0 AND spendingExclusionReason IS NULL`) in the data layer. A parity test keeps the two projections
  equal. Every spending aggregate must use the constant (proposed static guard). (AE-1, AE-6)
- **INV-2 Flag consistency.** `spendingExclusionReason = INSTALLMENT_PURCHASE` holds exactly when the expense is
  `purchaseExpenseId` of an existing plan (any status). `INSTALLMENT_REVERSED` holds exactly when the expense is
  linked to a REVERSED entry. The plan/entry rows are the source of truth. Owner: plan coordinator (writes the flag
  only through TLC; repaired by reconcile R1/R3). (AE-6, AE-14)
- **INV-3 One plan per purchase.** At most one plan references a given `purchaseExpenseId` (unique). A second
  declaration is refused with `ALREADY_DECLARED`. Owner: plan coordinator. (AE-22)
- **INV-4 One-to-one linking.** Each entry links to at most one expense, and each expense links to at most one entry
  (unique `linkedExpenseId`). Owner: plan coordinator. (AE-13)
- **INV-5 Schedule exclusivity.** An expense is never more than one of: (a) linked to an installment entry, (b) linked
  to a recurring occurrence, (c) a plan purchase record. Owner: one pure `ExpenseScheduleLinkPolicy`. The plan
  coordinator consults it on link/confirm/declare, and `RecurringLifecycleCoordinator` consults it before auto-link
  or manual link. The rule lives only in the policy. (AE-13)
- **INV-6 Lifetime counted total.** For any plan, the spending attributable to it over all periods equals Σ
  `effectiveAmount` of expenses linked to PAID entries (REGULAR + PAYOFF). The purchase adds 0, and REVERSED,
  SKIPPED, CANCELLED and PAID_BEFORE_TRACKING entries add 0. For an EQUAL_SPLIT plan with unedited linked expenses
  where every entry that is not PAID_BEFORE_TRACKING is PAID, this equals `declaredTotal` − Σ PAID_BEFORE_TRACKING
  entry amounts (just `declaredTotal` when there are none). Owner: derived property, asserted by tests over
  INV-1 + INV-4 + INV-32. (AE-1, AE-7, AE-8, AE-9, AE-26, AE-29) [amended AMD-1]
- **INV-7 No period double count.** No period counts both the purchase amount and any installment of the same plan.
  No entry counts twice. Owner: follows from INV-1 + INV-4 + INV-13. Tested per period. (AE-1, AE-6, AE-17)
- **INV-8 Dedupe families unchanged.** The spending predicate is never added to the duplicate BLOCKING or RESOLVER
  query families. Flagged purchase rows stay duplicate targets, so a re-captured purchase notification is still
  blocked. Owner: TLC duplicate policy (`LEGAL_PATHS.md` RP-11). (AE-6)

**Schedule and money**

- **INV-9 Equal-split conservation.** At generation and at re-spread, the entry amounts in minor units add up exactly
  to the amount being split. All entries equal `base = floor(totalMinor / n)` except the residual entry
  (`residualPlacement` FIRST or LAST), which is `base + r`, where `r = totalMinor − base·n` and `0 ≤ r < n`.
  Owner: pure `InstallmentScheduleCalculator`, called only by the plan coordinator. (AE-2, AE-3, AE-21)
- **INV-10 Fixed-payment schedule.** For FIXED_PAYMENT, every generated entry equals `fixedPaymentAmount`. The total
  is `fixedPaymentAmount × N`, exact in minor units. Owner: `InstallmentScheduleCalculator`. (AE-18)
- **INV-11 Money boundary.** Stored amounts are Double values exactly representable at 2 dp. All schedule arithmetic,
  sums and comparisons run in Long minor units (`round(x × 100)`, HALF_UP). No equality check on Double sums.
  Owner: `InstallmentScheduleCalculator` (generation) and the plan read model (aggregation). (AE-2, AE-5)
- **INV-16 Currency integrity.** A plan has one currency in {EUR, USD}. All its entries share it. Linking requires
  the expense currency to equal the plan currency (`CURRENCY_MISMATCH`). Overview and history totals are per
  currency and never summed across currencies (v1 has no conversion; a future conversion needs `RateBasis`
  provenance and labelling). Owner: plan coordinator (writes); plan read model (totals). (AE-5)
- **INV-17 Unavailable is not zero.** Financing cost (formula in section 9, AMD-2) is shown only if a purchase is
  attached, the purchase currency equals the plan currency, and status ∈ {ACTIVE, COMPLETED, PAID_OFF}. Otherwise
  it is "unavailable", never 0. Paid-so-far is "unavailable" if any linked expense's currency drifted (transient
  until reconcile unlinks it). Financing cost is also unavailable while paid-so-far is unavailable. A negative
  financing cost is shown as is. A currency with no plans shows no line, not "0". Owner: plan read model. (AE-5,
  AE-18, AE-29) [amended AMD-2]

**Lifecycle and recording**

- **INV-12 Auto-record at most once and idempotent.** Auto-record creates at most one expense per entry over its
  lifetime (`autoRecordConsumed`). It never runs for CONFIRM-mode plans, for entries due before `declaredOnDate`,
  or for entries due after today. The expense uses a deterministic STRICT_EXTERNAL_ID key. Owner: plan coordinator.
  (AE-10, AE-15, AE-16)
- **INV-13 Auto-record defers on a candidate charge.** Before auto-recording entry e, the coordinator looks for an
  unlinked candidate expense: same currency, |amount − scheduledAmount| ≤ 0.01, date in [due − 3 days, due],
  not a purchase record, not linked to any entry or occurrence. If one exists, auto-record is skipped for this run
  (reason `AUTO_RECORD_DEFERRED_CANDIDATE`), e stays SCHEDULED, and the read model offers the candidate as a
  suggested link. Owner: plan coordinator. (AE-17)
- **INV-14 Future entries never count.** No expense is ever created for an entry with `dueDate` > today. Future
  entries are commitments shown only in projections. Owner: plan coordinator. (AE-10)
  [amended S5-R, 2026-10-23] This covers CONFIRM: it requires `dueDate` ≤ today (local civil date). If today is
  unknown, CONFIRM is refused `ILLEGAL_TRANSITION` (fail closed). LINK creates no expense, so linking an existing
  expense to a not-yet-due entry stays allowed.
- **INV-15 Projection bounds.** Projections (cash flow, forecast, stress forecast, recurring plan projection,
  reminders) include only SCHEDULED entries of ACTIVE plans with `dueDate` ≥ today. Terminal plans and entries
  after the last SCHEDULED one project nothing. PAID_BEFORE_TRACKING entries are never projected or reminded.
  Owner: plan read model / projection port. (AE-11, AE-26) [amended AMD-1]
- **INV-19 Paid entries are immutable from the plan side.** Plan edits touch only SCHEDULED entries. A PAID entry's
  amount comes from its linked expense. Owner: plan coordinator. (AE-21)
- **INV-20 Terminal plans.** CANCELLED, REFUNDED and PAID_OFF plans accept no transitions, except that a CANCELLED
  plan with no PAID/REVERSED entries may be deleted. REFUNDED and PAID_OFF plans can't be deleted (section 7.5,
  OWNER CONFIRMED 2026-10-23) [amended S5-R, 2026-10-23]. PAID_BEFORE_TRACKING entries are not payments for this
  rule. Only COMPLETED may reopen (to ACTIVE). Owner: plan coordinator. (AE-7, AE-11, AE-27, AE-28) [amended AMD-1]
- **INV-21 Automatic completion.** An ACTIVE plan with zero SCHEDULED entries becomes COMPLETED in the same
  transaction as the change that removed the last one. PAID_BEFORE_TRACKING counts as non-SCHEDULED, and this
  includes a declaration whose k leaves no SCHEDULED entry. Owner: plan coordinator. (AE-11, AE-27) [amended
  AMD-1]
- **INV-22 Purchase lock.** TLC refuses changes to amount, currency, transactionType or ownership
  (isNotMine/shared), and refuses duplicate-merge operations, on any expense whose `spendingExclusionReason ≠ null`
  (`EXPENSE_LOCKED_BY_INSTALLMENT_PLAN`). Merchant, date, category, notes and receipt links stay editable.
  Owner: TLC, using only its own row. (AE-20)
- **INV-27 Atomic declaration.** Flagging the purchase, inserting the plan and inserting the entries commit together
  or not at all (one transaction). Undeclare (delete without payments) clears the flag and removes the rows
  together. Owner: plan coordinator. (AE-6, AE-14)
- **INV-28 Category inheritance.** Installment expenses created by the plan (auto-record, confirm, payoff) carry the
  plan's `categoryId`, `paymentMethod` and currency, merchant = `displayName`, and no notes text. Linked captured
  expenses keep their own fields. Owner: plan coordinator. (AE-1)
- **INV-29 No double projection.** Expenses linked to installment entries are excluded from recurring pattern
  detection (DETECTED_PATTERN) and from forecast history baselines when the plan's future entries are projected
  explicitly. Owner: `ForecastInputAssembler` for forecast inputs; the recurring pattern detector for detection
  (two distinct rules, one owner each). (AE-11)
  - **Amendment (S6c-F.2, 2026-10-27; owner-approvals 37(a)/(c), 46(b)).** `InsightsEngine.findAnomalies` and
    `NotificationSubscriptionDetector.detectSubscriptions` also exclude installment-linked and plan-purchase
    expenses, through the single owner `RecurringInstallmentEligibility` (anomalies: by expense id on both
    anomaly paths, baselines unchanged; subscriptions: filtered before MIN_TRANSACTIONS is re-checked).
  - **Amendment (S8a final gate, 2026-10-27; plan A15).** The forecast-baseline exclusion and the projected entries
    live ONLY on a separate, installment-aware entry point (a separate file) feeding the UI flow. They are never
    added to `assemble()` as called by `FinancialWeatherRepository`, because that path fills the shared
    `FinancialWeather` scalars that `DashboardBriefingInputBuilder` copies into the cloud briefing (INV-31).
    Home keeps INV-29 without double counting: the UI-side installment-aware weather REPLACES the baseline view
    for Home only (downstream of `getProcessedDataFlow`, in the HomeViewModel/UI layer); the cloud path keeps the
    installment-free one. The two views are never summed.
    Verification (S8b differential): expense rows, link state and spending-predicate state are held constant;
    only the projection-port output varies (empty vs non-empty). Spending aggregates follow ordinary aggregate
    semantics (a PURCHASE_ATTACHED flag and the S6c-3 recurring exclusion legitimately change them), so they are
    not part of the differential.
- **INV-32 Paid before tracking (AMD-1, OQ-18).** An entry may be `PAID_BEFORE_TRACKING` only if kind = REGULAR
  and `dueDate < declaredOnDate` (strict, evaluated on the entry's current `dueDate` at the transition). It is set
  in one of two ways:
  - at declaration, where "payments already made: k" marks the first k REGULAR entries by `seq`, with
    0 ≤ k ≤ P (P = REGULAR entries due before `declaredOnDate`);
  - later, by an all-or-nothing bulk mark on SCHEDULED, unlinked, eligible entries of an ACTIVE plan.

  Such an entry:
  - has `linkedExpenseId = null`, and no expense is ever created or linked for it;
  - adds 0 to every spending aggregate;
  - is never auto-recorded, projected, reminded or offered link suggestions;
  - counts as non-SCHEDULED for INV-21;
  - is included in financing cost (section 9);
  - reverts to SCHEDULED only while the plan is ACTIVE or COMPLETED (COMPLETED reopens), with `recordGeneration`
    and `autoRecordConsumed` unchanged;
  - does not block Delete, and is untouched by Stop, Refund, Pay off, shorten, re-spread and E4.

  Invalid requests are refused with `INVALID_PAID_BEFORE_TRACKING` and nothing is written. Owner: plan coordinator.
  (AE-26, AE-27, AE-28, AE-29)
- **INV-33 Plan category source (AMD-3).**
  - PURCHASE_ATTACHED: `categoryId` = the purchase's `categoryId`, copied exactly at declaration. Null stays null,
    and there is no fallback or default. [amended S5-R, 2026-10-23] The category may be null, and an E3 edit may set
    it to null. A non-null pick must exist (`CATEGORY_NOT_FOUND`).
  - STANDALONE: `categoryId` is required. It must reference an existing spending category the user picked. It is
    checked inside the create transaction (`CATEGORY_REQUIRED` when none is given, `CATEGORY_NOT_FOUND` when it no
    longer exists). An E3 edit may change it to another existing spending category, never to null.
  - No plan operation creates, renames or seeds a category. The category count is unchanged by every plan
    operation.
  - Owner: plan coordinator (validation at create, declare and edit). The no-preselection rule is a UI rule
    (section 6 Q13). (AE-30)

**Write paths, persistence and privacy**

- **INV-18 Due-date arithmetic.** See section 10. Owner: `InstallmentScheduleCalculator`. (AE-4, AE-19)
- **INV-23 Expenses never deleted by plan actions.** The plan coordinator never calls `deleteExpense`, and no plan
  operation lowers the expense row count. Removing an expense is always a user expense-delete on the standard path.
  Owner: plan coordinator (guard test). (AE-7, AE-8, AE-14, AE-16)
- **INV-24 Expense writes only via TLC.** Plan-feature code never touches `ExpenseDao`. Every expense create/update
  goes through TLC public operations. Owner: TLC (db-ownership policy + guard). (all)
- **INV-25 Single writer for plan tables.** Only the plan coordinator writes plan/entry tables (including restore
  replay through the existing restore path). Owner: plan coordinator (architecture guard similar to
  `RecurringArchitectureGuardTest`). (all)
- **INV-26 History retention.** Plan and entry rows are never purge targets of the retention worker. History (total
  paid, start/end, final status, counts including paid before tracking [AMD-1]) can be derived from
  plan/entry/expense rows alone, without
  `transaction_events`. Owner: plan read model (derivation); `RetentionPolicyContract` (no target registered).
  (AE-11)
- **INV-30 Backup integrity.** Plans and entries are included in encrypted backup and verified (section 11). Restore
  is followed by reconcile. Owner: `BackupVerifier` (verification) / plan coordinator (post-restore reconcile).
  (AE-12)
- **INV-31 Privacy.** Plan fields (displayName, lenderLabel, amounts, counts, dates, status) never appear in
  diagnostics, logs, reason-code payloads or cloud payloads. Diagnostics carry bounded codes and integer counts
  only. Owner: plan coordinator (diagnostics it emits); cloud payload assemblers (exclusion, guard test). (AE-23)

**Recording preconditions** [Amended 2026-10-26 (A2)]

- **INV-34 A plan needs a name to record.** The plan coordinator creates no installment expense (auto-record,
  confirm or payoff) for a plan whose `displayName` is blank (empty or whitespace only). In practice this is a plan
  restored from a redacted backup (IM-9 blanks `displayName` and `lenderLabel`). The check runs before any TLC
  call, so TLC is not called and writes nothing (no expense, no `transaction_events` row).
  - Auto-record skips every due entry of that plan in this run with reason `PLAN_NAME_REQUIRED`. Each entry stays
    SCHEDULED, and `autoRecordConsumed` and `recordGeneration` are unchanged. The run reports an integer count per
    reason. Every later run checks again.
  - Confirm and payoff are refused with `PLAN_NAME_REQUIRED` and write nothing (this replaces today's
    `RECORD_FAILED` for this case).
  - There is no fallback merchant: not `lenderLabel` (also blanked by IM-9), not the purchase merchant, not a fixed
    text. The restored data deliberately lost the name, and the app neither rebuilds nor replaces it.
  - Operations that create no expense stay allowed: link, skip, adjust, stop, refund, delete and the E3 rename.
    After a rename to a non-blank name (E3, ACTIVE plans only), the next run records every still-eligible entry,
    each dated on its own due date. INV-12, INV-13 and INV-14 apply unchanged at that time.
  - TLC's merchant validation (`MERCHANT_BLANK`, `MERCHANT_PLACEHOLDER`) is unchanged and stays the single owner of
    expense merchant validity. INV-34 is a plan precondition ("a plan with no name is not recordable") that
    short-circuits only the blank case before TLC. Every other TLC refusal, including a placeholder name such as
    "Unknown", stays `RECORD_FAILED`.
  - Diagnostics carry the code and integer counts only, never a name, amount or date (INV-31).
  - Owner: plan coordinator. One predicate is used by `recordDueEntries`, confirm and payoff. (AE-31)

(Invariants are listed by group, not in numeric order. All of INV-1…INV-34 are present: 34 invariants. INV-32 and
INV-33 were added by the 2026-10-16 amendment. INV-34 was added 2026-10-26 [Amended 2026-10-26 (A2)].)

## 5. Ownership & legal paths

### 5.1 Owners

| Concern | Single owner |
|---|---|
| Plan and entry state, all plan/entry writes | `InstallmentPlanLifecycleCoordinator` (new, OD-1) |
| Schedule generation and date arithmetic | `InstallmentScheduleCalculator` (pure, no I/O) |
| Expense rows (create, update, spending flag) | `TransactionLifecycleCoordinator` → `ExpenseDao.insertAtomic()` / coordinator update ops |
| Expense ↔ schedule exclusivity | `ExpenseScheduleLinkPolicy` (pure) |
| Spending inclusion | `Expense.effectiveAmount` + canonical SQL predicate constant (one rule, parity-tested) |
| Overview, detail, history, projections, suggestions | `InstallmentPlanReadModel` / projection port (read-only) |
| Due-installment recording in the background | existing or new worker → plan coordinator (WorkerExecutionGuard, barriers, WorkerRegistry) |
| Reminders | existing `BillReminderManager` / `BillReminderWorker` via the projection port. No new reminder system |

### 5.2 Operations (legal paths)

All plan coordinator writes check `writeBarrier.checkWritesAllowed(...)` on entry and run inside
`writeBarrier.runWrite { database.withTransaction { … } }`, the same as other legal writers.

| Operation | Path |
|---|---|
| Declare on existing expense | plan coordinator `declareOnExpense` → [txn: load purchase in txn, validate, insert plan + entries, TLC `setSpendingExclusionDbOnly(expenseId, INSTALLMENT_PURCHASE)` (new TLC op, UPDATED event, new `TransactionUpdateKind.SPENDING_INCLUSION`)] → post-commit batch |
| Declare with new expense | plan coordinator `declareWithNewExpense` → [txn: TLC `createExpenseDbOnlyV2` (standard dedupe for the purchase) + same steps as above] → post-commit batches |
| Create standalone plan | plan coordinator `createStandalone` → [txn: validate category (INV-33), insert plan + entries] |
| Payments already made at declaration (AMD-1) | `declareOnExpense` / `declareWithNewExpense` / `createStandalone` take `paidBeforeTrackingCount` k. The first k REGULAR entries are inserted as PAID_BEFORE_TRACKING in the same transaction (INV-32, INV-27) |
| Mark paid before tracking (bulk) / revert (AMD-1) | plan coordinator `markPaidBeforeTracking(planId, entryIds)` / `revertPaidBeforeTracking(planId, entryId)` (proposed names) → [txn: compare-and-set per entry (section 3.2). INV-21 completion or COMPLETED → ACTIVE in the same txn]. No TLC call, no expense touched |
| Confirm installment | plan coordinator `confirmEntry` → [txn: TLC `createExpenseDbOnlyV2` (STRICT_EXTERNAL_ID) + compare-and-set entry → PAID] → post-commit batch |
| Link captured expense | plan coordinator `linkExpense` → [txn: policy check, compare-and-set] |
| Unlink | plan coordinator `unlinkExpense` → [txn: entry → SCHEDULED, `recordGeneration`+1]. The expense is untouched |
| Skip / unskip / adjust / edit / extend / shorten | plan coordinator ops (section 7.4) |
| Stop / refund / pay off / delete | plan coordinator ops (section 7). Refund also calls TLC `setSpendingExclusionDbOnly(…, INSTALLMENT_REVERSED)` per paid expense in the same txn. Delete calls TLC `setSpendingExclusionDbOnly(purchase, null)` |
| Record due installments | worker/foreground trigger → plan coordinator `recordDueEntries(today)` (per-entry txn, INV-12/13/34). [Amended 2026-10-26 (A2)] Every run calls `reconcile()` first and dispatches its batches (D-18). The foreground trigger is overview-open only (D-19): no startup hook and no persisted marker (either one is stop-and-ask). Until the overview exists (S10), only the daily worker runs catch-up; S7a exposes the coordinator entry point that the overview calls later |
| React to expense update/delete | TLC post-commit side effects (`TransactionSideEffectPlanner.planUpdated/planDeleted`) → plan coordinator `onExpenseChanged/onExpenseDeleted` (unlink/reopen/detach). Reconcile R2/R4 backs this up |
| Integrity reconcile | plan coordinator `reconcile()` at the start of each `recordDueEntries` run and after restore |

Forbidden (proposed `LEGAL_PATHS.md` section "Installment Plan Mutations", OD-1):
- plan/entry DAO insert/update/delete outside the plan coordinator;
- any `ExpenseDao` call from plan-feature code;
- plan code calling `TransactionLifecycleCoordinator.deleteExpense`;
- writing installment schedules into `recurring_occurrences`, `manual_recurring_expenses` or `planned_expenses`;
- setting `spendingExclusionReason` from anywhere except TLC `setSpendingExclusion*`, and calling that op from
  anywhere except the plan coordinator;
- adding the spending predicate to dedupe BLOCKING/RESOLVER families;
- plan repositories or DAOs reachable from cloud payload assembly.

### 5.3 Wiring into existing readers (separate source, explicit and enumerated)

From brief Q1(b). Each reader consumes the read-only projection port. None reads plan DAOs directly.
- `CashFlowCalculator` (:210, :521): future SCHEDULED entries become dated outflows.
- `ForecastInputAssembler` (:483): add projected entries, and exclude installment-linked historical expenses from
  pattern baselines (INV-29).
- `FinancialStressForecastEngine` (:327): known bounded outflows.
- `RecurringPlanProjectionService` (:69): include as a distinct item type, or add a sibling projection. The planner
  decides without changing the existing RECURRING_RULE contract.
- `BillReminderWorker` / `BillReminderManager`: installment due reminders under the existing settings (enabled,
  quiet hours), with a delivery dedupe key per entry (`planUuid:seq:recordGeneration`).
- Not wired in v1 (decision, Q1): `FinancialHealthScoreV2` Bill Reliability (documented query contract kept as is);
  `SynthesisEngine` (:873) unless the planner proves its output never feeds a cloud payload (privacy fail-closed).

### 5.4 Integrity reconcile rules (plan coordinator; diagnostics are counts per rule only)

- R1: a plan with `purchaseExpenseId` whose expense is not flagged `INSTALLMENT_PURCHASE` → re-flag via TLC.
  An expense flagged `INSTALLMENT_PURCHASE` that no plan references → clear the flag via TLC.
- R2: a PAID entry whose linked expense no longer exists → SCHEDULED (plan ACTIVE/COMPLETED; COMPLETED reopens)
  or CANCELLED(`EXPENSE_REMOVED`) (plan CANCELLED/PAID_OFF). A REVERSED entry whose expense is missing →
  CANCELLED(`EXPENSE_REMOVED`).
- R3: a REVERSED entry whose expense is not flagged `INSTALLMENT_REVERSED` → re-flag. An expense flagged
  `INSTALLMENT_REVERSED` with no REVERSED entry → clear.
- R4: a PAID entry whose linked expense currency ≠ plan currency, or whose type is no longer an expense → unlink
  (same as R2, reason `EXPENSE_REMOVED` for the CANCELLED target; there are no separate currency or type codes)
  [amended S5-R, 2026-10-23].
- R5: a PURCHASE_ATTACHED plan whose purchase expense no longer exists → detach (kind → STANDALONE,
  `purchaseExpenseId` → null). Financing cost becomes unavailable.
- R6: an ACTIVE plan with zero SCHEDULED entries → COMPLETED (INV-21 repair).

## 6. Decisions on brief domain questions 1–13

### Q1 Reuse the recurring lifecycle, or use a separate plan lifecycle?

**Decision (recommended; needs human approval as a new legal path, OD-1): separate lifecycle.** A new
`InstallmentPlanLifecycleCoordinator` owns new plan/entry tables. Nothing is written to `recurring_occurrences`,
`manual_recurring_expenses` or `planned_expenses`. Readers are wired in explicitly through one projection port.

| Criterion | Reuse (extend `ManualRecurringExpense` + `recurring_occurrences`) | Separate plan lifecycle |
|---|---|---|
| (a) Expander has no rule-level end and no per-occurrence residual override | The expander needs a rule-level end and per-occurrence amount overrides. Every recurring reader uses it, and it touches RP-04 fixed-anchor reconcile and the golden no-double-count tests. The rolling 12-month regeneration and deactivate-DELETES-planned semantics (`LEGAL_PATHS.md` "DEACTIVATE rule") conflict with keeping plan history. | No expander. A finite plan (N ≤ 480) is fully materialised at declaration, one stored row per entry with its own amount, so the residual sits on a real row. The recurring code is untouched. |
| (b) `RECURRING_RULE` hard filters (CashFlow, ForecastInputAssembler, StressForecast, RecurringPlanProjection, Synthesis, HealthScore) | Plans become visible "for free", including where they shouldn't (Bill Reliability contract, synthesis/privacy). They would also show up mixed in the indefinite recurring-rule UI, which the market evidence says users want kept separate. | Invisible unless wired. The wiring is explicit and enumerated (section 5.3): 4 readers + reminders. Health score and synthesis are left out deliberately. More work, but bounded and testable. |
| (c) `BackupVerifier.kt:522` orphan check ignores `sourceType` | It would need to be scoped per `sourceType`, or plan IDs would have to live in `manual_recurring_expenses`. A shared ID space risks silent passes. | The existing check is unchanged. New dedicated orphan checks are added (section 11). No ID collision is possible. |
| (d) Same-day / merchant / ±10% auto-link ignores source type | A plan and a rule with the same lender and amount could claim each other's expenses. Auto-link changes are needed in the most-guarded recurring path. | Plans are never auto-linked. Exclusivity goes through `ExpenseScheduleLinkPolicy` (INV-5). The only fuzzy logic is the narrow candidate check that *defers* auto-record (INV-13), which only adds a suggestion. |
| (e) `PlannedExpense` chain as an existing surface | Reuse would write planned rows, but the user can edit those from Home (a second writer), and `linkedActualExpenseId` would duplicate the entry link (two sources of truth). | Not used in v1. Upcoming installments reach forecasting through the projection port instead. Rejected for v1 because of the second writer and duplicated link state. |

Rationale: the plan-specific semantics (purchase neutralisation, auto-record, terminal states with history, refund
reversal, residual) would otherwise be added to the most heavily guarded lifecycle in the app. A separate lifecycle
keeps that risk out of existing recurring behaviour, at the cost of explicit wiring.

### Q2 Where is the spending rule enforced?

**Decision:** one dimension on the expense row (`spendingExclusionReason`, section 2.3). It is honoured by
`effectiveAmount`/`normalizedEffectiveAmount` (Kotlin readers) and by one canonical SQL predicate constant that
every spending aggregate in `ExpenseDao` uses (this replaces the bare `isNotMine = 0` in *spending* aggregates
only). Lists, search, detail, dedupe families (INV-8) and receipt linking keep showing the purchase row, marked.
**Rationale:** it follows the `isNotMine` precedent that every aggregate already respects. The captured original
is kept. Reading the plan table at read time (a subquery or Kotlin spreading) would reach every Kotlin reader
without a chokepoint. **Proposed guard:** a static check that every `SUM(`/`AVG(` over `expenses` in spending
queries references the constant (planner enumerates the ~44 aggregates; test-author covers a representative set
for totals, budget, analytics, category trend and savings inputs).

### Q3 When do installments become expenses?

**Decision:** never virtually. An installment counts only through a real expense row:
- `AUTO_RECORD` plans: the plan coordinator creates the expense on or after the due date (worker + foreground
  catch-up), dated on the due date (section 10). This applies only to entries due on or after `declaredOnDate`,
  at most once (INV-12), and is deferred when a captured candidate exists (INV-13).
- `CONFIRM` plans: an entry counts once the user confirms it (creates an expense) or links a captured charge.
- Virtual counting (adding not-yet-recorded entries in aggregates) is rejected: it would need a union in ~44 SQL
  aggregates and in every Kotlin reader.

The default mode and the effect on unconfirmed past entries are owner decisions (OD-4, OD-5; section 8).

### Q4 How are double counts between the captured purchase, plan installments and later "ΔΟΣΗ" charges resolved?

**Decision:** there are three enforcement points, each with one owner, and no changes to `CrossSourceDeduplication`
in v1.
1. Purchase vs installments: INV-1 (the purchase counts 0 as long as the plan exists).
2. An installment counted twice: INV-4 (one-to-one link) + STRICT_EXTERNAL_ID keys (INV-12).
3. A captured "ΔΟΣΗ" charge vs an auto-recorded installment: INV-13 defers auto-record when the charge came first.
   When the charge comes after the auto-recorded expense, the read model shows a derived "possible duplicate of
   installment n" indicator (same currency, amount ±0.01, date within ±7 days of the entry's due date, charge not
   linked anywhere). Nothing is resolved automatically. The user deletes one of the two (standard expense delete;
   if it was the auto-recorded one, the entry reopens and can then be linked to the charge).

Coordinator duplicate detection doesn't merge legitimate monthly installments: they are a month apart (outside
the 5-minute fuzzy window), and plan-created installments use STRICT_EXTERNAL_ID (no fuzzy matching at all).

### Q5 How are declaration and undeclaration made atomic across TLC and the plan owner?

**Decision:** one DB transaction opened by the plan coordinator, composing TLC's DbOnly operations
(`createExpenseDbOnlyV2` exists for exactly this use, `TransactionLifecycleCoordinator.kt:952-967`). This needs a new
TLC op `setSpendingExclusionDbOnly` returning a `MutationResult`. Post-commit batches run after the outer commit.
Undeclare is the same in reverse. **Fallback** if the planner finds that write-barrier/transaction nesting can't be
composed: the plan coordinator first writes plan + entries, then calls the TLC op. Reconcile R1 makes the plan the
source of truth and re-applies the flag after any crash in between. Worst-case exposure: the purchase counts in full
until the next reconcile run, which starts on the next foreground or worker run. This fallback is a human-visible
trade-off and must be reported by the planner if chosen.

### Q6 Money representation

**Decision:** store Double at 2 dp (app precedent: `Expense.amount`, `RecurringOccurrence.expectedAmount`) and
compute in Long minor units internally (INV-11). **Rationale:** mixed representations at join/compare boundaries
with expenses would bring more risk than they remove. Any 2 dp value below 10^9 round-trips exactly through
`round(x × 100)`. This feature doesn't become the app's first minor-unit storage. That precedent stays an
app-wide decision (M02).

### Q7 Rounding and residual

**Decision:** an exact integer split in minor units (INV-9), so the split itself needs no rounding mode. The
residual goes on the **first** entry by default (OD-6). It lives on a stored entry row, so the expander limitation
doesn't apply. HALF_UP is used only where a non-2 dp value must be normalised (input sanitising, any future
conversion) (OD-7). See section 9.

### Q8 Plan lifecycle (edit after payments, early payoff, cancel, delete)

**Decision:** section 7. Expenses are unlinked, never deleted (INV-23). There are no PLANNED-occurrence rows to
clean (Q1). Reminder deliveries for CANCELLED/SKIPPED entries are suppressed by the reminder path re-reading
entry state after claiming (same pattern as `getDispatchableClaimedReminder` post-claim revalidation). The audit
trail lives on entry rows (`statusReason`, `originalScheduledAmount`, `adjustReason`, timestamps).

### Q9 Currency

**Decision:** per-currency totals. No conversion in v1. Financing cost requires the same currency (INV-16,
INV-17). A purchase-attached plan may have a plan currency different from the purchase currency (for example a USD
purchase turned into EUR card installments). Financing cost is then unavailable (OD-14). Installment expenses in
USD get their home-currency `baseAmount` through TLC's existing conversion at creation (existing expense
behaviour). [Amended 2026-10-26 (A2)] Plan-created expenses (auto-record, confirm, payoff) never set the
user-present rate flag (`userPresentRate = null`; only `declareWithNewExpense` sets it, plan A1). TLC therefore never
refuses them for a missing rate, and there is no `AUTO_RECORD_DEFERRED_FX` deferral. A USD installment recorded
with an empty rate table is Created with the sentinel triple (0.0 / "" / 0.0) and exactly one MISSING_RATE
diagnostic. There is no RateRefusal and no MANUAL_SAVE_BLOCKED_NO_RATE (MEC AE-29), and the entry becomes PAID as
usual. The row is excluded from comparable-home-value totals; how it is displayed is plan risk R17 / slice S8b. Any
future loan-local FX deferral must call `RateEligibilityEvaluator` (never re-implement the rule) and needs the owner.

### Q10 Schema and backup

**Decision:** additive only. Section 12 (schema) and section 11 (backup).

### Q11 Privacy plumbing

**Decision:** exclusion at payload assembly plus a static dependency guard (section 11). The plan lifecycle writes
no recurring events, so it adds no new data to the recurring upcoming-payment event metadata. Whether that existing
metadata reaches any export is a pre-existing question, outside this feature. The planner records it as a separate
item and doesn't fix it here.

### Q12 Time

**Decision:** section 10 (civil-date due dates, anchored month-end clamp, expense timestamp at local noon, no
business-day shift, the linked charge's own date governs).

### Q13 Category and savings consistency

**Decision:** installment expenses carry the plan's category (INV-28). Savings engines (`SmartSavingsEngine`,
`AutomatedSavingsRuleEngine`) and analytics see per-period installment expenses and never the purchase, as long
as they use `effectiveAmount` or the canonical predicate. The planner must audit these engines for raw `amount`
sums. Any found is a pre-existing bug in the spending rule, fixed in the same slice. Editing the purchase's category
doesn't propagate. A plan edit "change category" applies to installment expenses created after the edit. Recorded
installment expenses are recategorised individually like any expense.

**Plan category source (owner decision 2026-10-16, plan-gate item 5; AMD-3, INV-33):**
- Standalone plan: the user must pick an existing spending category. The form preselects none. None of the 20
  default categories is a Loan/Mortgage/Bills/Debt category (`discovery-s0.md` section 7), and the feature creates
  no new category. Submitting without a pick is refused (`CATEGORY_REQUIRED`). The planner confirms which category
  source counts as "spending categories" (the set the standard expense category picker offers). The concrete
  default category that D-7 was asked for is no longer needed.
- Purchase-attached plan: the plan copies the purchase's `categoryId` exactly. A null category stays null. Nothing
  is substituted, defaulted or created.
- If a referenced category is later deleted through the existing category path, the plan follows whatever that
  path does for expenses. The plan coordinator never recreates the category. The planner verifies that path.

## 7. Cancellation / edit semantics

### 7.0 Declaration preconditions (refusals use bounded codes)

The purchase must exist (checked inside the transaction by the plan coordinator → otherwise `PURCHASE_NOT_FOUND`
[amended S5-R, 2026-10-23]) and must meet all of the following:
- transactionType is a spending type, meaning `PURCHASE` only (the canonical `ExpenseDao.SPENDING_TYPE`; income,
  transfer, refund and every other type are refused) → otherwise `NOT_A_SPENDING_EXPENSE`;
- `isNotMine = false` → otherwise `NOT_MINE`;
- `isSharedExpense = false` → otherwise `SHARED_NOT_SUPPORTED` (OD-9);
- no split template or visualisation → otherwise `SPLIT_NOT_SUPPORTED` (OD-9);
- `spendingExclusionReason = null` and not already a plan purchase → otherwise `ALREADY_DECLARED`;
- not linked to a recurring occurrence or an installment entry → otherwise `LINKED_ELSEWHERE` (INV-5).

Plan inputs must satisfy all of the following. They are checked in this order and the first failing rule wins
[amended S5-R, 2026-10-23]:
- N ∈ [2, 480] → otherwise `INVALID_COUNT`;
- amounts > 0 → otherwise `INVALID_AMOUNT`. An amount that is not finite, rounds to 0 minor units, or has
  |amount| ≥ 10^9 major units is also `INVALID_AMOUNT` [amended S5-R, 2026-10-23];
- currency ∈ {EUR, USD} → otherwise `UNSUPPORTED_CURRENCY`;
- every date in 1970-01-01..2200-12-31 → otherwise `INVALID_DATE`. This covers the input dates (`firstDueDate`,
  `declaredOnDate`, the purchase civil date when there is one) and every generated due date, including the last
  one [amended S5-R, 2026-10-23];
- `firstDueDate` ≥ purchase civil date → otherwise `FIRST_DUE_BEFORE_PURCHASE`;
- for EQUAL_SPLIT, `totalMinor ≥ N`, so each entry is ≥ 0.01 → otherwise `AMOUNT_TOO_SMALL_FOR_COUNT`;
- payments already made k ∈ [0, P], where P = the number of REGULAR entries with `dueDate < declaredOnDate` (= today)
  → otherwise `INVALID_PAID_BEFORE_TRACKING` (AMD-1, INV-32);
- STANDALONE: a category is picked and still exists as a spending category (checked in the transaction) → otherwise
  `CATEGORY_REQUIRED` / `CATEGORY_NOT_FOUND` (AMD-3, INV-33). Purchase-attached plans copy the purchase category and
  have no category check.

### 7.1 Stop (plan → CANCELLED)

- Meaning: "I'm not tracking or paying this any more" (transferred, refinanced into a new plan, or stopped tracking).
- Effect: SCHEDULED entries → CANCELLED(`PLAN_STOPPED`). PAID entries and their expenses are unchanged and still
  count. The purchase stays at 0. Lifetime counted = Σ paid.
- Overdue unrecorded entries are cancelled too. The UI must say so ("2 unrecorded past payments will not count").
- PAID_BEFORE_TRACKING entries stay unchanged and count 0 (INV-32).

### 7.2 Refund / return (plan → REFUNDED, "everything reverses")

- Meaning: the purchase was returned or cancelled and the money came back.
- Effect: SCHEDULED → CANCELLED(`PLAN_REFUNDED`). PAID → REVERSED, and each linked expense is flagged
  `INSTALLMENT_REVERSED` through TLC in the same transaction (counts 0, never deleted). The purchase stays at 0.
  Lifetime counted = 0. PAID_BEFORE_TRACKING entries stay unchanged (no expense to flag, already 0; INV-32).
- Guard against double reversal: if the bank's refund credit was captured as its own transaction, reversing the
  installments as well would push the total below zero. The flow asks first: "Is the refund already recorded as a
  transaction?" Yes → Stop is offered instead (paid installments stay counted and the captured credit offsets them).
  No → REFUNDED. Partial refunds are not supported in v1. The user adjusts future entries (7.4) instead.

### 7.3 Early payoff (plan → PAID_OFF)

- Input: payoff date (civil date, ≤ today, ≥ the latest due date among PAID and PAID_BEFORE_TRACKING entries) and
  payoff amount (default = Σ SCHEDULED entries, including overdue unrecorded ones; PAID_BEFORE_TRACKING entries are
  excluded; the user may enter less, for a discount, or more, for a fee).
- Effect: SCHEDULED → CANCELLED(`PAID_OFF_EARLY`). One PAYOFF entry is created PAID, and its expense is either
  created through TLC (STRICT key `installment:<planUuid>:PAYOFF:0:0`, dated payoff date 12:00 local) or a linked
  captured charge. The payoff counts in full in the payoff month. Lifetime counted = Σ paid + payoff.
- Financing cost for a PAID_OFF plan = (Σ paid + payoff + Σ PAID_BEFORE_TRACKING) − purchase (same currency only;
  section 9, AMD-2). The CANCELLED(`PAID_OFF_EARLY`) entries are excluded.
- PAID_BEFORE_TRACKING entries stay unchanged (INV-32).

### 7.4 Edit after payments (plan stays ACTIVE)

Allowed, and affecting only SCHEDULED entries (INV-19):
- E1 Adjust one entry's amount or date (`originalScheduledAmount` is kept; bounded `adjustReason`).
- E2 Extend (append entries N+1…, amount = fixed payment, or re-spread) or shorten (trailing SCHEDULED → CANCELLED
  (`SHORTENED`)). With EQUAL_SPLIT the user may re-spread: Σ remaining SCHEDULED is split again over the new
  remaining count using INV-9 and the plan's `residualPlacement`.
- E3 Change displayName, lenderLabel, category or fulfilmentMode. These apply to expenses created afterwards.
  Recorded expenses are unchanged.
- E4 Change the due day: SCHEDULED entries are recomputed from a new anchor starting at the next unrecorded entry.

Illegal: editing PAID/REVERSED entries (edit the expense instead), changing currency once any entry is
PAID/REVERSED (`CURRENCY_LOCKED`), changing the split method after payments (stop and create a new plan).
Editing the purchase's amount, currency, type or ownership is refused by TLC (INV-22). To change the basis, the user
edits the plan, or deletes it (7.5, only without payments) and declares again.

### 7.5 Delete (undo declaration)

- Allowed only when the plan has no PAID or REVERSED entries and is ACTIVE or CANCELLED. Payments are checked first
  (`PLAN_HAS_PAYMENTS` from every status). Without payments, COMPLETED, REFUNDED and PAID_OFF are refused
  `ILLEGAL_TRANSITION` [amended S5-R, 2026-10-23]. Effect:
  entries and plan are removed and the purchase flag is cleared, so the purchase counts in full again on its
  original date. A standalone plan just disappears. PAID_BEFORE_TRACKING entries don't block Delete and are removed
  with the plan (INV-32).
- With payments: refused (`PLAN_HAS_PAYMENTS`). The user picks Stop, Refund or Pay off. Alternatively, the user
  first deletes or unlinks the installment expenses on standard paths, which reopens the entries, and then deletes.
- Never deletes expenses (INV-23).
- **OWNER CONFIRMED 2026-10-23** [amended S5-R, 2026-10-23]. The S5 code follows the fail-closed reading of this
  section (ACTIVE or CANCELLED only). The earlier INV-20 wording allowed REFUNDED and PAID_OFF without payments too.
  - REFUNDED is excluded because Delete clears the purchase flag. A refunded purchase (the money came back) would
    then count in full again on its original date.
  - COMPLETED is excluded too. It is not in the list above, and the fail-closed reading doesn't add it.
  - If the owner wants a wider set, the plan delete row in INV-20, section 3.1 and this section change together.

### 7.6 Changes to linked expenses made outside the plan

- Purchase deleted (standard expense delete is allowed): the plan detaches (R5). Counting is unaffected, because
  the purchase was 0 and is now gone.
- Installment expense deleted: the entry reopens (ACTIVE/COMPLETED plan, `recordGeneration` + 1,
  `autoRecordConsumed` unchanged), or becomes CANCELLED(`EXPENSE_REMOVED`) in terminal plans (R2).
- Installment expense amount edited: the entry stays PAID and the read model shows the variance against
  `scheduledAmount`. The expense is the source of truth for counting.
- Installment expense date edited: it counts in its new month (the expense governs, section 10).
- Installment expense currency or type changed: it is unlinked (R4).
- Installment expense marked not-mine: it stays linked, counts 0, and the variance is shown.

### 7.7 Recommendation on "cancel altogether" (OD-3)

A single "cancel" that wipes everything back to the original state would misreport months whose payments really
happened (unless the money was refunded). It would also need either deleting expenses or a hidden reversal of
real payments. The recommendation is three explicit end actions, each with a one-line consequence, plus a
restricted delete:
- **Stop**: "Payments already made stay in your spending. Future ones are removed."
- **Refund / return**: "Everything is reversed. This purchase and its installments count 0."
- **Pay off early**: "The remaining amount counts on the payoff date."
- **Delete** (only without recorded payments): "Undo. The purchase counts in full on its original date."

If the owner wants a single "Cancel": map it to Stop (the safe default, since paid months stay true) and keep
Refund as a separate explicit action. "Cancel altogether = revert to the full purchase on day 0 even after
payments" is possible only by flagging the paid installment expenses REVERSED and clearing the purchase flag. That
amounts to an alternative refund with the purchase counted again, and needs a sixth plan state. Not recommended.

## 8. Proposed defaults (open decisions)

### 8.1 Unconfirmed past installments (owner Q5) → OD-4

Virtual counting is rejected (Q3), so the question becomes **which fulfilment mode is the default**:
- **Proposed default: `AUTO_RECORD`** for both purchase-attached and standalone plans. Each entry is recorded as a
  real expense on its due date, so it counts in its month without the user acting. B-2 (budget of the month due)
  holds automatically. If a payment was in fact skipped, the user deletes the auto-recorded expense (the entry
  reopens) and marks it skipped.
- Alternative: `CONFIRM` default. Entries count only once confirmed or linked. Totals dip until then. The overview
  shows "n past payments awaiting confirmation" with their per-currency sum, labelled as not counted.
- In both modes, entries already past due at declaration (`dueDate < declaredOnDate`) are **not** auto-recorded.
  They show as OVERDUE-unrecorded with a bulk "record all past payments" action, because the user may already
  have captured those charges and should link them instead (AE-10c). Payments made before the app tracked the plan
  can instead be marked PAID_BEFORE_TRACKING, either at declaration ("payments already made: k") or later in bulk.
  They count 0 (INV-32, AE-26, AE-27).

### 8.2 Per-installment bank notifications "ΔΟΣΗ n/N" (owner Q6) → OD-5

- **Proposed:** yes, a captured per-installment charge can fulfil a planned installment, through linking. At
  declaration the user answers "Does your bank notify you of each installment?". Yes → `CONFIRM` mode, and each
  captured charge is offered as a suggested link (same currency, amount ±0.01, date in [due − 5 d, due + 10 d],
  unlinked) that the user accepts with one tap. No → `AUTO_RECORD`.
- Safety net in `AUTO_RECORD`: INV-13 defers auto-record when a candidate charge already exists. A later charge
  triggers the "possible duplicate" indicator (Q4). Nothing is linked or deleted automatically in v1. Automatic
  recognition of "ΔΟΣΗ n/N" text is increment 2 (brief "Later increments").
- Alternative: no linking of captured charges for plans (users would avoid declaring plans for such cards). Not
  recommended, because it leaves the central double count to the user.

## 9. Money & rounding

- **Representation:** amounts are stored as Double at exactly 2 dp in the plan currency (EUR and USD both have
  scale 2). Every stored money value is paired with a currency (the plan currency for entries and plans; the
  expense's own currency for linked expenses).
- **Arithmetic:** Long minor units only. `toMinor(x) = round_half_up(x × 100)`; `fromMinor(m) = m / 100.0`.
  Sums, differences, conservation and tolerance checks run on minor units. No Double sum is compared for equality.
- **Equal split (INV-9):** `base = totalMinor / n` (integer division), `r = totalMinor − base·n`, and the residual
  entry gets `base + r`. Default placement is FIRST (OD-6), recorded on the plan. Re-spread (E2) applies the same
  rule to Σ remaining SCHEDULED over the new remaining count.
- **Rounding mode:** HALF_UP, used only to normalise inputs with more than 2 decimals and in any future conversion
  (OD-7). Banker's rounding (HALF_EVEN) is not used. Lender statements and consumer displays round half-up, and
  the integer split makes the mode irrelevant for schedules. The planner confirms that the existing
  `AmountUtils`/`AmountInputSanitizer` behaviour matches, and must not change those files under this feature
  without approval (they are dirty in the working tree for unrelated work).
- **Fixed payment (INV-10):** each entry = `fixedPaymentAmount`. There is no residual. A different final payment is
  entered with E1 adjust.
- **Financing cost (amended 2026-10-16, AMD-2 / OQ-24):**
  `Σ toMinor(linked expense amount) over PAID entries (REGULAR + PAYOFF) + Σ toMinor(scheduledAmount) over
  SCHEDULED entries + Σ toMinor(scheduledAmount) over PAID_BEFORE_TRACKING entries − toMinor(purchase.amount)`.
  - SKIPPED, CANCELLED and REVERSED entries are excluded.
  - It is computed in minor units, same currency only, and may be negative (shown as is).
  - The PAID term uses the linked expense's gross `amount` in plan currency, not `effectiveAmount`. Ownership and
    exclusion flags decide spending, not what the lender charged. A linked expense that is marked not-mine
    therefore leaves the financing cost unchanged.
  - For PAID_OFF plans the SCHEDULED term is 0, because every SCHEDULED entry was cancelled. The formula then
    reduces to Σ paid + payoff + Σ PAID_BEFORE_TRACKING − purchase (section 7.3).
  - Availability follows INV-17. Otherwise the value is "unavailable", never 0.
- **Remaining scheduled payments:** Σ SCHEDULED entry amounts per currency, labelled "remaining scheduled
  payments". It is never called principal or balance.
- **EUR/USD:** v1 shows per-currency lines ("300.00 EUR · 500.00 USD"). There is no grand total and no implicit
  conversion. Any future converted figure must carry `RateBasis` provenance (rate source + date) and be labelled
  approximate. Home-currency spending views keep using `baseAmount` from TLC for installment expenses, unchanged
  from today's expense behaviour.
- **Tolerances:** conservation is exact (0 minor units). The candidate/suggestion amount tolerance is ±1 minor
  unit (0.01).

## 10. Time

- **Source of "now"/"today":** `TimeProvider` only. Today = civil date of `TimeProvider` now in the device's current
  zone. Month buckets use `TimePeriodUtils` month ranges (unchanged).
- **Due dates are civil dates** (epochDay), not instants. DST and timezone changes never move a due date.
- **Monthly arithmetic (INV-18):** `due(k) = clampToMonthEnd(year/month of firstDueDate + (k−1) months,
  anchorDayOfMonth)`. Each date is computed from the anchor, never chained from the previous one, so an anchor of
  31 Jan gives 28/29 Feb, 31 Mar, 30 Apr, 31 May (java.time `LocalDate.plusMonths` from the anchor has this
  behaviour). E4 starts a new anchor. A leap-day anchor (29 Feb) gives 28 Feb in non-leap years and 29 Mar after.
  Implemented in `InstallmentScheduleCalculator` (`TimePeriodUtils` has no add-months helper. If one is added,
  it goes there, not in the feature).
- **Installment expense timestamp:** due civil date at **12:00 local** in the zone at record time. Noon keeps the
  expense in the right day/month bucket under DST shifts and timezone changes of up to ±11 h. A late catch-up run
  (for example 3 days later) still dates the expense on its due date.
- **Business-day shifts:** due dates are not shifted automatically in v1. The schedule shows the nominal date. A
  linked captured charge keeps its own date and counts in its own month, even when that differs from the nominal
  due month (e.g. due 31 Jan, charged 2 Feb → counts in February) (OD-10). Candidate and suggestion windows
  (INV-13, section 8.2) tolerate shifts.
- **Catch-up scheduling:** `recordDueEntries(today)` runs from a unique periodic worker (existing recurring
  maintenance worker extended, or a new one registered in `WorkerRegistry`; the planner decides) and from an app
  foreground trigger. Rules: `WorkerExecutionGuard`, restore/retention barriers (writes blocked → skip, retry next
  run), `CancellationException` rethrown, per-entry transactions, bounded reason codes, no plan content in logs.
- **Overdue derivation:** SCHEDULED with `dueDate < today`. After a timezone change, "today" may move by one day.
  That only changes the display state, never the stored data.

## 11. History, retention, backup, export, privacy

### 11.1 History (B-3)

- History lists plans in COMPLETED, CANCELLED, REFUNDED and PAID_OFF, showing: displayName, currency, start
  (= first REGULAR entry due date, PAID_BEFORE_TRACKING entries included; a PAYOFF entry never sets the start
  [amended S5-R, 2026-10-23]), end (= terminal date, or for COMPLETED the latest due date among PAID and
  PAID_BEFORE_TRACKING entries; a COMPLETED plan with no PAID or PAID_BEFORE_TRACKING entry ends at the date it
  reached COMPLETED, or null if that date is unknown [amended S5-R, 2026-10-23]), final status, total counted
  (Σ PAID linked expense amounts + payoff; 0 for REFUNDED; PAID_BEFORE_TRACKING adds 0), counts of
  paid/skipped/cancelled/reversed, the count of PAID_BEFORE_TRACKING entries shown as "k paid before tracking"
  (only when k > 0), and financing cost (section 9) or "unavailable" (INV-17). [amended AMD-1, AMD-2]
- The active overview and plan detail show PAID_BEFORE_TRACKING the same way ("n paid · k paid before tracking ·
  m left"). It is separate from paid-so-far (which excludes it) and from remaining scheduled payments (SCHEDULED
  only).
- Everything is derived from plan, entry and expense rows (INV-26). Nothing comes from `transaction_events`, which
  is purged at 30/365 days by `RetentionPolicyContract`.
- A COMPLETED plan moves back to the active overview if it reopens (section 3.1).
- Deleted plans have no history (delete is an undo, allowed only without payments).

### 11.2 Retention

- Plan and entry tables are user financial records, treated like `expenses`. They are **not** added to
  `RetentionPolicyContract.requiredTargets`, and the retention worker never purges them.
- If the planner adds a plan lifecycle-event table, it may hold only bounded codes and IDs (no names, amounts or
  dates). It must also be registered as a retention target with a cutoff, which needs human approval
  (`RetentionPolicyContract` change, OD-13). History must never depend on it.
- "Erase all data" / factory-reset paths must include the plan tables (the planner verifies the wipe path).

### 11.3 Backup / restore / verify

- Plan and entry tables are included in the encrypted backup and follow `BackupPrivacyMode` in the same way as
  expense merchant/notes text. If a mode strips or redacts expense free text, it also strips `displayName` and
  `lenderLabel` (the planner verifies the mode set).
- `BackupVerifier`: add both tables to `TABLE_TIERS` at TIER_1_EXACT (financial records). Add semantic orphan checks:
  1. entries whose `planId` is not in plans;
  2. entries whose `linkedExpenseId` (non-null) is not in expenses;
  3. plans whose `purchaseExpenseId` (non-null) is not in expenses;
  4. expenses flagged `INSTALLMENT_PURCHASE` that no plan references;
  5. expenses flagged `INSTALLMENT_REVERSED` that no REVERSED entry references.
  Messages are fixed strings with counts only, as at `BackupVerifier.kt:520-537`.
- The existing check at `BackupVerifier.kt:522` is **unchanged** (plans don't write `recurring_occurrences`).
  Observation for the orchestrator (out of scope, not fixed here): that check would already flag any
  `recurring_occurrences` row with a non-RECURRING_RULE `sourceType`, if such rows are ever written.
- Restore: plan writes are blocked by the restore barrier while it runs. After restore, `reconcile()` (R1–R6) runs
  before the first `recordDueEntries`.

### 11.4 Export

- No CSV/accounting export of plan data in v1. Plan data leaves the app only inside the encrypted backup.
- Expense exports: installment expenses are ordinary rows. Purchase rows flagged `INSTALLMENT_PURCHASE` /
  `INSTALLMENT_REVERSED` are proposed to be **included and marked "not counted (installment plan)"** where the
  format has a type/notes field. Any totals the export computes must apply INV-1 (OD-11).
- **Amended by owner-approvals 41/42 (supersedes the proposal above for the accounting formats):** the three
  accounting formats (Xero CSV, FreshBooks CSV, QuickBooks IIF) EXCLUDE flagged rows; their empty check, policy
  validation sample and record count use only unflagged rows. Generic CSV/JSON, the accountant PDF and the business
  report still include flagged rows and mark them. Importers SKIP rows whose notes begin with an export marker
  (counted as skipped), so re-importing an export never creates a flagged or double-counted expense.

### 11.5 Privacy

- No network, no new permissions, no reading of notification, OCR or receipt content. Declaring uses only stored
  expense fields.
- Diagnostics/logs: bounded reason codes (section 7.0, 3.x codes, `AUTO_RECORD_DEFERRED_*`, reconcile rule IDs)
  and integer counts only. Never displayName, lenderLabel, amounts, dates, schedule, merchant or IDs in free-text
  messages.
- Cloud payloads: plan repositories, DAOs and the projection port must not be reachable from any cloud payload
  assembly (`CloudPayloadPurpose.*`, including `DASHBOARD_BRIEFING`). Proposed static dependency guard. Any
  cash-flow/forecast output that feeds a cloud payload must be computed **without** the installment projection
  (an explicit exclusion flag at assembly), or that payload stays out of v1 scope. The planner must check the
  briefing assembly.
- Installment expenses are ordinary expenses and follow the existing cloud/export policy (brief). That is why
  plan-created expenses carry no notes text and use only `displayName` as merchant (INV-28). The
  "installment n/N" label is a UI join, never stored on the expense.
- `ExpenseSource` for plan-created expenses is a new bounded enum value (e.g. `INSTALLMENT_PLAN`), never text.

## 12. Schema implications (concept level)

**Impact: additive (non-destructive).** No backfill. No existing column changes meaning.

- **New table `installment_plans`** (section 2.1): unique `planUuid`, unique nullable `purchaseExpenseId`, index on
  `status`.
- **New table `installment_entries`** (section 2.2): FK `planId` → plans (CASCADE is acceptable, because delete is
  only allowed without payments and runs through the coordinator), unique (`planId`, `kind`, `seq`), unique nullable
  `linkedExpenseId`, indexes on `dueDate` and `status`. `linkedExpenseId` has **no** FK cascade or SET NULL to
  expenses: link repair goes through the coordinator (R2), so a PAID entry is never left with a null link.
- **Entry `status` value `PAID_BEFORE_TRACKING` (AMD-1):** a new value in the existing TEXT `status` column. No DDL,
  no extra migration step and no backfill. It is restored as is by backup, and the `BackupVerifier` tiers and
  orphan checks are unchanged. The planner-chosen schema must not constrain `status` in a way that rejects the value.
- **`expenses.spendingExclusionReason`** TEXT NULL, default NULL. Existing rows keep counting. An index is optional
  (the planner measures aggregate plans).
- **Required:** a Room version bump N → N+1, one migration (2× CREATE TABLE + indexes, 1× ALTER TABLE ADD COLUMN),
  an exported schema snapshot, and migration tests (from N with seeded expenses: all rows have
  `spendingExclusionReason IS NULL`, totals identical before and after, the new tables are empty, and the existing
  backup verify passes).
- **Also required:** `BackupVerifier` tiers and orphan checks (11.3); backup manifest counts; `db_ownership_policy.yml`
  entries (plan DAO writes → plan coordinator only; the new TLC op is the only writer of the new column);
  an architecture guard test for plan single-writer and the INV-23/24 forbidden calls.
- **Proposed architecture-doc changes (not made here; for the planner/orchestrator):** a `LEGAL_PATHS.md` section
  "Installment Plan Mutations" (5.2 forbidden list), plus TLC `setSpendingExclusion*` under "UPDATE expense"; an
  `ENGINE_INTERACTION_MAP.md` row for the plan projection port and the 4 wired readers; a `CODEBASE_SEGMENTS.md`
  owner for the new segment.
- **Destructive operations:** none. Nothing needs destructive-migration approval.

## 13. Acceptance examples

All data is synthetic. Zone Europe/Athens unless stated. "Plan spending(M)" = spending in month M computed by the
canonical aggregates, counting only the purchase and the plan's installment expenses. Defaults: residual FIRST,
AUTO_RECORD (unless stated), MONTHLY.

**AE-1 600 € / 12 (INV-1, INV-6, INV-28; B-2)**
- Given expense E1 = 600.00 EUR, merchant "Demo Electronics", category Electronics, CARD, captured from a
  notification, dated 2027-03-10 14:32, and today = 2027-03-12.
- When the user declares EQUAL_SPLIT, N = 12, first due 2027-04-10.
- Then the plan is ACTIVE with 12 entries of 50.00 EUR, due 2027-04-10 … 2028-03-10 (Σ 60000 minor units). E1 has
  `spendingExclusionReason = INSTALLMENT_PURCHASE`. Plan spending(2027-03) = 0.00.
- When catch-up runs on each due date, then each run creates one expense of 50.00 EUR dated on the due date at
  12:00, category Electronics, merchant "Demo Electronics", no notes. Plan spending(M) = 50.00 for each M in
  2027-04…2028-03. Σ over 2027-03…2028-03 = 600.00. E1 still exists.

**AE-2 100 € / 3, residual (INV-9)**
- Given an EQUAL_SPLIT total of 100.00 EUR with N = 3.
- When the schedule is generated with FIRST, then the entries are 33.34, 33.33, 33.33.
- When it is generated with LAST, then they are 33.33, 33.33, 33.34. In both cases Σ = 10000 minor units exactly.

**AE-3 Other residuals (INV-9)**
- 200.00 / 7 with FIRST gives 28.58 + 6 × 28.57 = 200.00.
- 0.02 / 3 is refused with `AMOUNT_TOO_SMALL_FOR_COUNT`, and nothing is written.

**AE-4 Month-end (INV-18)**
- Anchor 2027-01-31, N = 6 → 2027-01-31, 02-28, 03-31, 04-30, 05-31, 06-30.
- Anchor 2028-01-31, N = 3 → 2028-01-31, 02-29, 03-31.
- Anchor 2028-02-29, N = 14 → entry 13 = 2029-02-28, entry 14 = 2029-03-29.

**AE-5 USD plan next to EUR plan (INV-16, INV-17)**
- Given:
  - Plan A: EUR, 12 × 50.00, 6 PAID.
  - Plan B: STANDALONE, USD, FIXED 125.00 × 8, 4 PAID.
  - Plan C: purchase 240.00 USD, plan currency EUR, 4 × 56.00, 0 PAID.
- When the overview is shown, then it lists "Remaining scheduled payments: 300.00 EUR + 224.00 EUR = 524.00 EUR"
  and "500.00 USD" as two separate lines, with no combined total. Financing cost is "unavailable" for B
  (standalone) and for C (currency differs).
- When the user links a 56.00 USD expense to a C entry, then it is refused with `CURRENCY_MISMATCH`.

**AE-6 Captured purchase + declare → no double count (INV-1, INV-7, INV-8, INV-27)**
- Given AE-1 after declaration.
- When the identical bank notification for E1 is processed again, then no new expense is created (blocking dedupe
  still sees the flagged E1) and plan spending(2027-03) stays 0.00.
- For every month M in 2027-03…2028-03, plan spending(M) ∈ {0.00, 50.00}, never 600.00 or 650.00.
- Given an injected failure after the entries insert and before the flag write inside the declaration transaction,
  then no plan or entries exist, E1 is unflagged, and plan spending(2027-03) = 600.00. A retry succeeds exactly once.

**AE-7 Stop (INV-6, INV-20, INV-23)**
- Given AE-1 with entries 1–3 PAID (Apr–Jun) and today = 2027-07-05.
- When the user chooses Stop, then:
  - entries 4–12 become CANCELLED(`PLAN_STOPPED`);
  - plan spending for 2027-07…2028-03 is 0.00 and the lifetime total is 150.00;
  - E1 is still at 0, and there are still 4 expense rows (E1 + 3);
  - projections and reminders show nothing for this plan;
  - History shows "Cancelled, 2027-04-10 → 2027-07-05, total 150.00 EUR, 3 paid / 9 cancelled".

**AE-8 Refund / return (INV-2, INV-6, INV-23)**
- Given the same state as AE-7.
- When the user chooses Refund and answers "refund not recorded separately", then:
  - entries 1–3 become REVERSED and their expenses are flagged `INSTALLMENT_REVERSED`;
  - entries 4–12 become CANCELLED(`PLAN_REFUNDED`);
  - plan spending for 2027-04, 05 and 06 is 0.00, and the lifetime total is 0.00;
  - all 4 expense rows still exist.
- When the user answers "refund already recorded" instead, then the flow offers Stop and the outcome equals AE-7.

**AE-9 Early payoff (INV-6, INV-17)**
- Given the same state as AE-7 (today 2027-07-05, before the 07-10 entry).
- When the user pays off with the default amount 450.00 EUR dated 2027-07-05, then:
  - a PAYOFF entry is PAID with an expense of 450.00 EUR dated 2027-07-05 12:00;
  - entries 4–12 become CANCELLED(`PAID_OFF_EARLY`);
  - plan spending(2027-07) = 450.00, and 0.00 afterwards;
  - the lifetime total is 600.00 and the financing cost is 0.00.
- With a payoff amount of 440.00 instead, the lifetime total is 590.00 and the financing cost is −10.00.

**AE-10 Unconfirmed past installments (INV-12, INV-14; proposed defaults OD-4)**
- a) AUTO_RECORD: given entry 2 due 2027-05-10 and no catch-up run until 2027-05-13, when catch-up runs on
  2027-05-13, then an expense dated 2027-05-10 12:00 is created and plan spending(2027-05) = 50.00. On 2027-05-09
  no expense exists for entry 2.
- b) CONFIRM: given entry 2 due 2027-05-10 and nothing linked by 2027-05-31, then:
  - plan spending(2027-05) = 0.00;
  - the overview shows "1 past payment awaiting confirmation: 50.00 EUR (not counted)";
  - when on 2027-06-02 the user links a captured expense of 50.00 EUR dated 2027-05-11, plan spending(2027-05)
    becomes 50.00.
- c) Late declaration: given a purchase of 600.00 EUR on 2027-01-15, declared on 2027-04-02 (AUTO_RECORD) with first
  due 2027-02-15, then:
  - entries for 02-15 and 03-15 are OVERDUE-unrecorded and are not auto-recorded;
  - the 04-15 entry onward is auto-recorded;
  - plan spending for 2027-01, 02 and 03 is 0.00 until the user records or links those entries.

**AE-11 History after completion (INV-15, INV-21, INV-26, INV-29)**
- Given AE-1. When entry 12 is auto-recorded on 2028-03-10, then:
  - the plan becomes COMPLETED in the same transaction and leaves the active overview;
  - the EUR remaining line disappears if this was the only EUR plan;
  - History shows "Demo Electronics, EUR, 2027-04-10 → 2028-03-10, Completed, total 600.00, 12 paid,
    financing cost 0.00";
  - the forecast and cash flow for 2028-04 onward show no 50.00 outflow, neither from the plan nor from pattern
    detection on the 12 installment expenses.
- When `transaction_events` rows older than 365 days are purged, History is unchanged.
- When the user then deletes the 2028-03-10 installment expense, then entry 12 becomes SCHEDULED (overdue), the plan
  goes back to ACTIVE, and the entry is not auto-recorded again.

**AE-12 Backup verify with plans present (INV-30)**
- Given:
  - Plan P1: purchase-attached, 12 entries, 3 PAID;
  - Plan P2: standalone, 240 entries, 10 PAID.
- When a backup is created and verified, then it passes:
  - exact counts are 2 plans and 252 entries;
  - all 5 new orphan checks report 0;
  - the existing `recurring_occurrences` orphan check reports 0.
- When the backup is restored into an empty database, then counts match, `reconcile()` changes 0 rows, and per-month
  totals equal the pre-backup values.
- Given a copy with P2's plan row removed, then verify fails with the fixed message for orphan entries and the count
  240. The message contains no names, amounts or dates.

**AE-13 Exclusivity (INV-4, INV-5)**
- Given a recurring rule "Demo Electronics" 50.00 EUR monthly on day 10, when the installment expense of 2027-04-10
  is auto-recorded, then the recurring auto-link doesn't claim it and the occurrence stays PLANNED.
- When the user links an expense that is already linked to a recurring occurrence, then it is refused with
  `LINKED_ELSEWHERE`.
- When the user links expense X, already linked to entry 3, to entry 4, then it is refused.

**AE-14 Delete = undo (INV-2, INV-23, INV-27)**
- Given AE-1 before 2027-04-10 with no payments, when the user deletes the plan, then the plan and 12 entries are
  gone, E1 is unflagged, and plan spending(2027-03) = 600.00.
- Given 1 PAID entry, when the user deletes the plan, then it is refused with `PLAN_HAS_PAYMENTS` and no state
  changes.

**AE-15 Idempotency and process death (INV-12, INV-27)**
- a) The process is killed inside the auto-record transaction for entry 2. Then no expense exists and entry 2 is
  SCHEDULED. When the next run happens, exactly one expense with key `installment:<uuid>:REGULAR:2:0` is created.
- b) The worker and foreground catch-up run at the same time for entry 2. Then exactly one expense and one PAID
  entry exist.
- c) The coroutine is cancelled mid-run. Then `CancellationException` propagates, the entries committed earlier
  stay, and the current entry rolls back.
- d) Declaration is submitted twice with the same `planUuid`. Then exactly one plan exists.

**AE-16 User deletes an auto-recorded installment (INV-12, INV-23)**
- Given entry 2 auto-recorded on 2027-05-10, when the user deletes that expense from Transactions, then:
  - entry 2 is SCHEDULED with `recordGeneration = 1` and `autoRecordConsumed = true`;
  - no catch-up run re-creates it, and plan spending(2027-05) = 0.00.
- When the user marks entry 2 Skip(`USER_SKIP_LENDER_HOLIDAY`), then it is SKIPPED. At completion, the lifetime
  total is 550.00.

**AE-17 Captured "ΔΟΣΗ" charge (INV-7, INV-13; OD-5)**
- Given an AUTO_RECORD plan and captured expense C2 = 50.00 EUR dated 2027-05-09, when catch-up runs on 2027-05-10,
  then no installment expense is created (`AUTO_RECORD_DEFERRED_CANDIDATE`) and C2 is offered as a suggestion.
  When the user accepts, then entry 2 is PAID (USER_LINKED) and plan spending(2027-05) = 50.00, not 100.00.
- Given captured C3 = 50.00 EUR dated 2027-06-11, after the 2027-06-10 auto-record, then the detail shows
  "possible duplicate of installment 3", 2027-06 spending includes 100.00, and nothing changes automatically.
  When the user deletes C3, June becomes 50.00.

**AE-18 Fixed payment, financing cost, standalone (INV-10, INV-17)**
- Given a purchase of 1,000.00 EUR declared FIXED_PAYMENT 12 × 87.50, then the total is 1,050.00, the financing
  cost is 50.00, and each month counts 87.50.
- Given a standalone plan of 240 × 523.17 EUR with first due 2027-05-01, then:
  - the end date is 2047-04-01;
  - remaining scheduled payments at creation are 125,560.80 EUR, labelled "remaining scheduled payments";
  - the financing cost is "unavailable".

**AE-19 Time zone / DST (INV-18)**
- Given an entry due 2027-03-28 (EU DST start), when it is recorded, then its timestamp is 2027-03-28 12:00
  Europe/Athens and it is in the March bucket.
- Given an entry due 2027-04-01 recorded at 12:00 Athens, when it is later viewed in America/New_York, then it is in
  the April bucket. (Counter-example the test must guard against: a start-of-day stamp of 2027-04-01 00:00 Athens
  would fall into March in Europe/London.)

**AE-20 Purchase lock (INV-22)**
- Given flagged E1:
  - an amount edit 600.00 → 650.00 is refused with `EXPENSE_LOCKED_BY_INSTALLMENT_PLAN`;
  - a merchant edit is accepted;
  - a category edit is accepted, and the plan category is unchanged;
  - marking E1 not-mine is refused.

**AE-21 Edit after payments with re-spread (INV-9, INV-19)**
- Given AE-1 with entries 1–2 PAID, when on 2027-05-20 the user shortens the plan to 5 remaining with re-spread,
  then:
  - entries 3–7 are 100.00 each (due 2027-06-10 … 2027-10-10);
  - entries 8–12 are CANCELLED(`SHORTENED`);
  - entries 1–2 stay 50.00;
  - the lifetime total at completion is 600.00.

**AE-22 Declaration refusals (section 7.0)**
- Each of these is refused with no rows written and the flag unchanged:
  - an `isNotMine` purchase → `NOT_MINE`;
  - a shared purchase → `SHARED_NOT_SUPPORTED`;
  - an income-type purchase → `NOT_A_SPENDING_EXPENSE`;
  - a second declaration → `ALREADY_DECLARED`;
  - first due before the purchase date → `FIRST_DUE_BEFORE_PURCHASE`;
  - N = 1 → `INVALID_COUNT`;
  - CHF → `UNSUPPORTED_CURRENCY`.

**AE-23 Privacy (INV-31)**
- After running AE-1, AE-8 and AE-17, captured diagnostics and logs contain only bounded codes and integers, and none
  of "Demo Electronics", "600", "50.00", the lender label or any due date.
- A `DASHBOARD_BRIEFING` payload assembled in a test with plans present contains no plan fields and no projected
  installment amounts.
- The static guard finds no dependency path from payload assemblers to plan DAOs, repositories or the projection
  port.

**AE-24 Budget of the month due (B-2, INV-1)**
- Given the Electronics budget for 2027-04 has a limit of 100.00 with 60.00 of other Electronics spending, when the
  04-10 installment is recorded, then budget usage is 110.00 (over).
- Electronics usage for 2027-03 excludes E1's 600.00.

**AE-25 Purchase deleted (R5)**
- Given AE-1 with 2 PAID entries, when the user deletes E1 on the standard path, then the plan becomes STANDALONE,
  the financing cost is "unavailable", the entries are unchanged, and every month's total is unchanged.

The following examples were added by the 2026-10-16 amendment (AMD-1…AMD-3).

**AE-26 Declared after payments already made (INV-32, INV-6, INV-12, INV-15; AMD-1)**
- Given expense P1 = 1,200.00 EUR, merchant "Demo Furniture", dated 2027-01-05 11:00, and today = 2027-06-20.
- When the user declares EQUAL_SPLIT, N = 12, first due 2027-02-05, AUTO_RECORD, "payments already made: 4", then:
  - 12 entries of 100.00 EUR, due 2027-02-05 … 2028-01-05, and `declaredOnDate` = 2027-06-20;
  - entries 1–4 are PAID_BEFORE_TRACKING with `linkedExpenseId = null`, and no expense is created for them;
  - entries 5–12 are SCHEDULED (8 remain), and the plan is ACTIVE;
  - remaining scheduled payments = 800.00 EUR;
  - the detail shows "0 paid · 4 paid before tracking · 8 left";
  - plan spending(M) = 0.00 for every M in 2027-01…2027-06. 2027-01 drops from 1,200.00 to 0.00 at declaration,
    because the purchase is neutralised and the PAID_BEFORE_TRACKING entries add 0;
  - the expense row count is 1 (P1 only);
  - projections and reminders contain entries 6–12 only (700.00). Entry 5 (due 2027-06-05) is OVERDUE-unrecorded
    and is not projected.
- When catch-up runs on 2027-07-05, then entry 6 is auto-recorded (100.00, dated 2027-07-05 12:00). Entry 5 is
  never auto-recorded, on that run or any later one.
- When entries 5–12 all end PAID (entry 5 through confirm or link), then the lifetime counted total is 800.00
  (= 1,200.00 − 400.00), and the plan becomes COMPLETED when the last SCHEDULED entry is resolved.
- When the same declaration uses k = 6, it is refused with `INVALID_PAID_BEFORE_TRACKING` (P = 5: entries 1–5 are
  due before 2027-06-20). No rows are written and P1 is unflagged.
- Strictness: given the same purchase declared on 2027-06-05, then P = 4, because entry 5 (due 2027-06-05) is not
  due before `declaredOnDate`. k = 5 is refused, and entry 5 is auto-recorded on 2027-06-05.

**AE-27 Bulk mark later, revert, completion (INV-32, INV-21, INV-20; AMD-1)**
- Given AE-26 right after declaration and today = 2027-06-21.
- When the user bulk-marks entry 5 as paid before tracking, then entry 5 is PAID_BEFORE_TRACKING, 7 entries remain
  SCHEDULED, remaining scheduled payments = 700.00, and every month's spending is unchanged. Repeating the same
  request returns `AlreadyInState` and changes nothing.
- When instead the user bulk-marks entries {5, 6}, then the request is refused with `INVALID_PAID_BEFORE_TRACKING`
  (entry 6 is due 2027-07-05, which is ≥ `declaredOnDate`), and entry 5 is still SCHEDULED (all or nothing).
- When the user reverts entry 3, then:
  - entry 3 is SCHEDULED (OVERDUE) with `recordGeneration = 0` and `autoRecordConsumed = false`;
  - remaining scheduled payments go up by 100.00;
  - no catch-up run creates an expense for entry 3.
  When the user then confirms entry 3 on 2027-06-22, then one expense of 100.00 dated 2027-04-05 12:00 is created
  (key `installment:<uuid>:REGULAR:3:0`), and plan spending(2027-04) = 100.00.
- Given AE-26 with entries 5–12 PAID (plan COMPLETED), when the user reverts entry 2, then the plan is ACTIVE again
  in the same transaction, and entry 2 is SCHEDULED (OVERDUE) and not auto-recorded.
- Given AE-26 followed by Stop, then reverting entry 1 and bulk-marking are both refused (`IllegalTransition`), and
  entries 1–4 stay PAID_BEFORE_TRACKING.
- Given a purchase of 300.00 EUR dated 2027-01-05, when it is declared on 2027-06-20 as EQUAL_SPLIT N = 3 with
  first due 2027-02-05 and k = 3, then:
  - the plan commits as COMPLETED in the declaration transaction (INV-21);
  - the purchase is flagged `INSTALLMENT_PURCHASE`, and the plan counts 0.00;
  - nothing is projected or reminded;
  - History shows "Completed, 2027-02-05 → 2027-04-05, total 0.00 EUR, 0 paid, 3 paid before tracking, financing
    cost 0.00".

**AE-28 Delete still allowed with paid-before-tracking entries (INV-32, INV-20, INV-27, INV-23; AMD-1)**
- Given AE-26 right after declaration, when the user deletes the plan, then:
  - the plan and all 12 entries (4 PAID_BEFORE_TRACKING + 8 SCHEDULED) are removed;
  - P1 is unflagged and plan spending(2027-01) = 1,200.00;
  - the expense row count is still 1.
- Given AE-26 after entry 6 was auto-recorded on 2027-07-05, when the user deletes the plan, then it is refused
  with `PLAN_HAS_PAYMENTS` and no state changes.
- Given AE-26 right after declaration, when the user chooses Stop on 2027-06-21, then:
  - entries 5–12 are CANCELLED(`PLAN_STOPPED`) and entries 1–4 stay PAID_BEFORE_TRACKING;
  - History shows "Cancelled, 2027-02-05 → 2027-06-21, total 0.00 EUR, 0 paid / 4 paid before tracking /
    8 cancelled", with financing cost "unavailable" (status CANCELLED, INV-17);
  - a later Delete is allowed (no PAID/REVERSED entries). It removes the plan and its entries and unflags P1.

**AE-29 Financing cost per OQ-24 (INV-17, INV-6, INV-32; section 9; AMD-2)**
- Given:
  - purchase P2 = 1,000.00 EUR dated 2027-01-10;
  - declared on 2027-03-20 as FIXED_PAYMENT 12 × 90.00 with first due 2027-02-10 and k = 2 (entries 1–2 are due
    02-10 and 03-10);
  - entries 3–5 PAID with linked expenses of 90.00, 90.00 and 92.00 (entry 5's expense was edited to 92.00);
  - entries 6–12 SCHEDULED;
  - today = 2027-06-15.
- Then:
  - financing cost = 18000 (PAID_BEFORE_TRACKING) + 27200 (PAID linked) + 63000 (SCHEDULED) − 100000 =
    8200 minor units = 82.00 EUR;
  - lifetime counted = 272.00;
  - remaining scheduled payments = 630.00.
- When entry 6 is skipped (`USER_SKIP_WAIVED`), then financing cost = 18000 + 27200 + 54000 − 100000 = −8.00 EUR.
  SKIPPED is excluded, and the value is shown as −8.00, never "unavailable" or 0.
- When instead entry 1 is reverted to SCHEDULED, then the financing cost is still 82.00 (the amount moves from the
  PAID_BEFORE_TRACKING term to the SCHEDULED term).
- When instead entry 4's expense is marked not-mine, then the financing cost is still 82.00 (gross linked
  `amount`), and the lifetime counted total is 182.00.
- When instead the user pays off 600.00 on 2027-06-15, then:
  - entries 6–12 are CANCELLED(`PAID_OFF_EARLY`) and the PAYOFF entry is PAID at 600.00;
  - financing cost = 18000 + 27200 + 60000 − 100000 = 52.00 EUR. CANCELLED is excluded.
- When instead the user chooses Stop, then the financing cost is "unavailable".
- REVERSED entries exist only in REFUNDED plans, whose financing cost is "unavailable" (INV-17). The formula
  excludes them anyway.

**AE-30 Plan category source (INV-33, section 6 Q13; AMD-3)**
- Given the 20 default categories (none is Loan/Mortgage/Bills/Debt), when the user opens the create-standalone-plan
  form, then no category is preselected.
  - Submitting without a category is refused with `CATEGORY_REQUIRED`, and nothing is written.
  - Picking existing category id 7 gives `plan.categoryId = 7`, and auto-recorded installment expenses carry
    category 7.
  - The category row count is unchanged.
- Given category 7 is deleted between pick and submit, then the submit is refused with `CATEGORY_NOT_FOUND`. Nothing
  is written and no category is created.
- Given a purchase with `categoryId = 3`, when it is declared, then `plan.categoryId = 3`.
- Given a purchase with `categoryId = null`, when it is declared, then `plan.categoryId = null`, and plan-created
  installment expenses have a null category. No category is assigned or created.
- Given a standalone plan, when an E3 edit sets the category to null, then it is refused with `CATEGORY_REQUIRED`.

**AE-31 Blank plan name after a redacted restore (INV-34, INV-12, INV-31; A2)** [Amended 2026-10-26 (A2)]
- Given an ACTIVE AUTO_RECORD EUR plan restored from a redacted backup (`displayName = ""`, `lenderLabel = null`),
  with `declaredOnDate` = 2027-03-01, entries 3 (due 2027-05-10) and 4 (due 2027-06-10) SCHEDULED at 50.00 with
  `autoRecordConsumed = false`, no candidate charge, and today = 2027-06-12.
- When `recordDueEntries` runs, then:
  - TLC is not called: no expense is created and no `transaction_events` row is written;
  - entries 3 and 4 stay SCHEDULED, with `autoRecordConsumed = false` and `recordGeneration` unchanged;
  - the run reports `PLAN_NAME_REQUIRED` = 2, and no diagnostic contains a name, amount, date or ID.
- When the user confirms entry 3, or pays off the plan, then it is refused with `PLAN_NAME_REQUIRED` and nothing is
  written.
- When the user links captured expense C = 50.00 EUR dated 2027-05-10 to entry 3, then entry 3 is PAID (USER_LINKED).
- When the user then renames the plan to "Laptop" (E3) and the next run happens on 2027-06-12, then exactly one
  expense is created, for entry 4: merchant "Laptop", 50.00 EUR, dated 2027-06-10 at noon local, key
  `installment:<uuid>:REGULAR:4:0`. Entry 4 is PAID and plan spending(2027-06) = 50.00.
- Given instead `displayName = "   "` (whitespace only), then the same outcomes apply.
- Given instead a CONFIRM-mode plan with the blank name, then `recordDueEntries` does nothing for it (INV-12), and
  confirming any entry is refused with `PLAN_NAME_REQUIRED`.

## 14. Owner decisions (DECIDED 2026-10-16)

**Decision status (owner, 2026-10-16, after approving the slice plan):**
- OD-1…OD-14 are **DECIDED: the recommended option is accepted** for each row below. The spec as written is final
  for them.
- OD-15 is **DECIDED as conditional**. Single-transaction composition stays the rule. The two-step fallback activates
  only if the planner's D-10 proves that single-transaction composition is impossible. In that case the proof is
  reported and recorded at the gate, together with the transient double-count window until the next reconcile.
- Amendments decided at the same gate: AMD-1 (OQ-18, `PAID_BEFORE_TRACKING`), AMD-2 (OQ-24, financing cost) and
  AMD-3 (plan category source). See the amendment log at the top.

The table is kept for traceability. "Recommended" is now the decided option.

| # | Decision | Recommended | Alternatives | Affects |
|---|---|---|---|---|
| OD-1 | **New legal path:** separate `InstallmentPlanLifecycleCoordinator` + new plan/entry tables, wired into readers through one projection port | Approve (section 6 Q1 table) | Extend `RecurringRuleLifecycleCoordinator` / `recurring_occurrences` (expander end + overrides, verifier scoping, auto-link changes) | sections 5, 6, 12; INV-5, 11, 25 |
| OD-2 | **Expense spending dimension:** new `expenses.spendingExclusionReason` column, new TLC op `setSpendingExclusion*`, all spending aggregates moved to one canonical predicate + static guard | Approve | Read-time subquery against the plan table (no expense column; Kotlin readers need a new port) | sections 2.3, 6 Q2, 12; INV-1, 2, 8, 22 |
| OD-3 | **End-plan semantics** (owner leaned "cancel altogether") | Distinct Stop / Refund-Return / Pay-off-early actions, plus Delete only without recorded payments. A single "Cancel" button maps to Stop | "Cancel altogether" = revert to full purchase on day 0 even after payments (reverses paid installments, needs a sixth state) | section 7; INV-20, 23 |
| OD-4 | **Default fulfilment mode / unconfirmed past installments** (owner Q5) | `AUTO_RECORD`: each installment is recorded and counted in its due month automatically. Entries already past due at declaration are not auto-recorded | `CONFIRM` default: counts only once confirmed/linked, totals dip until then | sections 6 Q3, 8.1; INV-12, 14 |
| OD-5 | **Per-installment bank notifications "ΔΟΣΗ n/N"** (owner Q6) | Yes: a captured charge fulfils the installment through a one-tap suggested link (CONFIRM mode chosen at declaration). In AUTO mode, auto-record defers when a candidate exists, and later charges show "possible duplicate" | Don't link captured charges to plans | sections 6 Q4, 8.2; INV-13 |
| OD-6 | **Residual placement** for equal splits | FIRST installment (e.g. 33.34 / 33.33 / 33.33) | LAST installment | INV-9; AE-2 |
| OD-7 | **Rounding mode** for normalising inputs and any future conversion | HALF_UP | HALF_EVEN (banker's) | section 9; INV-11 |
| OD-8 | **Frequencies in v1** | MONTHLY only (card installments, tax plans, loans) | Also BIWEEKLY (pay-later) and/or QUARTERLY | sections 2.1, 10 |
| OD-9 | **Shared / split-category / not-mine purchases** | Refuse declaration in v1 (`SHARED_NOT_SUPPORTED`, `SPLIT_NOT_SUPPORTED`, `NOT_MINE`) | Support, with installments inheriting the share | section 7.0; AE-22 |
| OD-10 | **Month of a linked charge with a business-day shift** | The charge's own date governs (due 31 Jan, charged 2 Feb → counts in Feb) | Force the count into the nominal due month (would require changing the expense date) | section 10 |
| OD-11 | **Expense exports** (CSV/accounting) | Include flagged purchase rows, marked "not counted (installment plan)". Export totals apply INV-1. No plan-data export in v1 (backup only) | Exclude flagged purchase rows. Add a plan-data export | section 11.4 |
| OD-12 | **Readers not wired in v1** | Leave out `FinancialHealthScoreV2` Bill Reliability and `SynthesisEngine` (contract stability, privacy fail-closed) | Wire them now | section 5.3 |
| OD-13 | **Plan lifecycle-event table** (if the planner wants one) | Don't add one in v1. The audit trail lives on entry rows. If added: bounded codes only, registered as a retention target with a cutoff (a `RetentionPolicyContract` change needs approval) | Add it with a stated cutoff | section 11.2 |
| OD-14 | **Plan currency ≠ purchase currency** (e.g. USD purchase → EUR card installments) | Allow. Financing cost "unavailable" | Refuse (`CURRENCY_MISMATCH`) | sections 6 Q9, 9; AE-5 |
| OD-15 | **Conditional, raised only if the planner finds that TLC DbOnly ops can't share the plan coordinator's transaction:** accept the two-step fallback (plan rows first, flag second, R1 repairs). The purchase may count in full until the next reconcile run | Prefer single-transaction composition. Accept the fallback only with this note | Block the feature until TLC supports composition | section 6 Q5; INV-27 |

**Out of scope, recorded for the orchestrator (not decisions for this feature):**
- `BackupVerifier.kt:522` already ignores `sourceType`. It would flag any non-RECURRING_RULE `recurring_occurrences`
  rows if such rows are ever written.
- Whether the upcoming-payment event metadata from `RecurringLifecycleCoordinator` (expense ID, amount, currency)
  reaches any export is unverified.

## Summary for orchestrator

- **Invariants:** 34 (INV-1…INV-34). INV-32 and INV-33 are new (2026-10-16). INV-34 is new [Amended 2026-10-26
  (A2)]. INV-6, 15, 17, 20, 21 and 26 were amended.
- [Amended 2026-10-26 (A2)] `AUTO_RECORD_DEFERRED_FX` is removed (section 6 Q9). The foreground trigger is
  overview-open only (D-19, section 5.2). Proposed follow-up for the orchestrator: refuse a blank `displayName` at
  declare/create/E3 (2026-10-26 amendment log).
- **Schema impact:** additive. 2 new tables, 1 nullable expense column, a version bump, migration, snapshot,
  migration tests, and verifier tiers + 5 orphan checks. `PAID_BEFORE_TRACKING` is a TEXT status value with no DDL.
  Nothing destructive.
- **Open decisions:** none. OD-1…OD-15 are DECIDED. OD-15 is conditional on D-10.
- **Acceptance examples:** AE-1…AE-31 (AE-26…AE-30 are new; AE-31 is new [Amended 2026-10-26 (A2)]).


### Amendment 2026-10-27 (S8a privacy review; owner-approvals item 40(b))
- INV-31 scope: INV-31 covers PLAN-ENTITY fields (displayName, lenderLabel, n/N, schedule, remaining, status, planUuid) and projected installments. A RECORDED installment expense is an ordinary expense. Its merchant (copied from the plan displayName at record time) follows the ordinary merchant / redactBeforeCloud cloud policy of §11.5. No plan-only field is stored on the expense (INV-28).
- S8b binding: the FinancialWeather path that feeds the cloud briefing stays installment-free by construction. Projections reach Home through a separate flow and are merged only at the UI. Plan projections are never expressed as an existing UpcomingItem variant.

### Amendment 2026-10-27 (S6c-F.3; owner item 37(b), decision 46(a))
- Recurring layer-1 rule (append-only clarification; INV-5 unchanged): the recurring lifecycle's own type/ownership rule has ONE owner, `RecurringLinkTypeEligibility` (pure, `domain/recurring`). A NEW link of an expense to a recurring occurrence (auto-link, manual link, generation-time matching, relink after a no-longer-matches unlink) is refused for a not-mine expense and for TRANSFER, DEPOSIT and UNKNOWN. UNKNOWN is added by this amendment: an unclassified row is not a confirmed bill payment. The generation-time resolver already refused UNKNOWN; the coordinator link paths now agree with it.
- EXISTING links are kept (decision 46(a), R2): re-validating an existing link (reconcile "still matches", including the bulk-update reconcile) excludes only not-mine, TRANSFER and DEPOSIT, so an existing link on an UNKNOWN expense stays and its occurrence stays PAID. A bulk edit never reverts a PAID occurrence to PLANNED. The installment layer (INV-5) is unchanged and applies to both forms.

## Clarification 2026-10-27 — owner decisions (owner-approvals item 51c)

Append-only. Nothing above this heading is rewritten. For the entries named below, this section wins over the text
above.

**Source.** The owner replied "I approve" to the orchestrator's recommendations on 2026-10-27 (owner-approvals item
51c; also item 49c (OQ-21); OD-14, F4 and F5 were raised in the S12a reviews (handoff :2171-2198);
`build/release-lead-notes/owner-approvals-20261022.md` in the main checkout). Recorded
in `workflows/active/loan-installments-handoff.md`, "2026-10-27 OWNER DECISIONS" (:2229-2237). Four of the six 51c(c)
decisions are spec/UX decisions. Only OD-14 touches this file. OQ-21, F4 and F5 are UX decisions and are recorded in `ux-flows.md` under the
same heading.

**Impact.** Schema: none. New invariants: none. New refusal codes: none. Coordinator code: unchanged. AE-1…AE-31
stay valid (AE-5 is consistent with the narrowing, see below).

### C-1. OD-14 narrowed: a plan whose currency differs from its purchase allows FIXED_PAYMENT only

**Decision.** OD-14 stays "allow a plan currency different from the purchase currency", with one narrowing. Such a
plan can only be `FIXED_PAYMENT`. `EQUAL_SPLIT` is not available across currencies, because no conversion rule exists
and none is invented.

**Entries superseded or narrowed**

| Entry | Where | Original | Now |
|---|---|---|---|
| OD-14 row | section 14, :1385 | "Allow. Financing cost 'unavailable'" | "Allow, `FIXED_PAYMENT` only. Financing cost 'unavailable'". The status line at :1360 ("recommended option accepted") is read as "accepted with this narrowing". |
| Q9 Currency | section 6, :661-663 | "A purchase-attached plan may have a plan currency different from the purchase currency (for example a USD purchase turned into EUR card installments)." | The same, but only as a `FIXED_PAYMENT` plan. The financing-cost sentence is unchanged. |
| `splitMethod` | section 2.1, :127 | `EQUAL_SPLIT` \| `FIXED_PAYMENT`; "STANDALONE is always FIXED_PAYMENT" | Add: a PURCHASE_ATTACHED plan can be `EQUAL_SPLIT` only when the plan currency equals the purchase currency. |
| `declaredTotal` | section 2.1, :128 | "EQUAL_SPLIT: the amount split (defaults to the purchase amount when currencies match)" | An `EQUAL_SPLIT` plan always has matching currencies, so `declaredTotal` is always the purchase amount. There is no "user types the total when currencies differ" branch. |
| Add-expense currency note | `ux-flows.md` :224-227 (cross-file) | "The amount to split must then be entered (spec 2.1 `declaredTotal` only defaults when currencies match)." | Superseded by this rule. The Add-expense plan-currency row is also removed by G2-6 (`loan-installments-plan.md` :671), so the declare sheet on an existing expense is the only place a differing currency can be chosen. |
| `gate2-decisions.md` :24 | outside this write set | "Allow; financing cost shows 'unavailable'" | Not edited. This section governs. |

**Not changed:** INV-16, INV-17 (financing cost stays "unavailable" with reason `CURRENCY_DIFFERS`, never 0;
`InstallmentPlanMath.kt:181-183`), section 9 (no conversion), the `CURRENCY_MISMATCH` link refusal, AE-5. AE-5 Plan C
(purchase 240.00 USD, plan currency EUR, 4 × 56.00 = 224.00 ≠ 240.00) is a `FIXED_PAYMENT` plan by construction, so it
already complies.

**Rule (precise).** Let P be the purchase expense's currency code and C the plan currency, C ∈ {EUR, USD}. For a
PURCHASE_ATTACHED declaration on an existing expense:
1. C == P: `EQUAL_SPLIT` or `FIXED_PAYMENT`, as before.
2. C ≠ P: the split method is `FIXED_PAYMENT`. The user types the fixed payment in C (2 dp, > 0). The terms carry
   `fixedPaymentAmount` = that amount and `declaredTotal = null`. The total is `fixedPaymentAmount × N` (INV-10) and may
   differ from the purchase amount. The purchase amount is never converted, scaled or reinterpreted in C. A stored
   `EQUAL_SPLIT` selection is overridden to `FIXED_PAYMENT` when the currencies differ.
3. P outside {EUR, USD} (for example CHF): no plan currency is preselected and saving is blocked until the user picks
   EUR or USD. After the pick C ≠ P, so rule 2 applies.
4. Financing cost stays "unavailable" (INV-17). It is never 0.
5. The Add-expense path (`declareWithNewExpense`, S11) never has C ≠ P (G2-6), and STANDALONE plans are always
   `FIXED_PAYMENT`. Rules 2 and 3 therefore only occur in the existing-expense declare sheet.
6. After declaration the currency and split method are locked once a payment exists (section 7.4, `CURRENCY_LOCKED`),
   and the Edit screen does not change the currency, so the UI cannot move a plan into a cross-currency
   `EQUAL_SPLIT` state later.

**Implemented behaviour (evidence, as of this date).**
- The rule has ONE owner today, the declare-sheet state. `DeclareInstallmentsUiState.effectiveSplit` returns
  `f.split` only when `f.currency?.code == t.currency`, otherwise `FIXED_PAYMENT`
  (`DeclareInstallmentsSheet.kt:141-147`). `currencyDiffers` is at :151. `canSave` is at :159-167: a null plan
  currency blocks saving, and `FIXED_PAYMENT` needs a parsed fixed amount.
- `save()` builds the terms from `effectiveSplit` (:293-302): `declaredTotal = if (equal) target.amount else null` and
  `fixedPaymentAmount = if (equal) null else fixed`. It then calls only `coordinator.declareOnExpense` (:318).
- UI (:500-528): the currency chips are always shown. When the currencies differ, the "Equal split of total" chip is
  disabled (:519), "Fixed payment × N" is selected, and the note `declare_installments_currency_differs` is shown
  (:512-514; text at `values/strings.xml:2853`: "This plan is in a different currency than the purchase. Enter each
  payment; financing cost will be unavailable."). The KDoc at :100-105 documents the narrowing.
- Tests: `DeclareInstallmentsViewModelTest.kt:126-149` (`OD-14 a plan currency different from the purchase forces a
  fixed payment`: `currencyDiffers`, `effectiveSplit == FIXED_PAYMENT`, `canSave` false until a fixed payment is
  typed, then terms `FIXED_PAYMENT` / `USD` / 50.0 / `declaredTotal == null`) and :151-160 (a CHF purchase needs a plan
  currency pick before saving).
- The coordinator does NOT enforce it. `declareOnExpense` (`InstallmentPlanLifecycleCoordinator.kt:133-179`) reads
  the purchase facts (:149) and checks the declaration policy (:156-168), but it never compares `terms.currency` or
  `terms.splitMethod` with the purchase currency. `editDetails` accepts a currency change on an ACTIVE plan with no
  PAID or REVERSED entries whatever the split method and purchase currency are (:690-708). Its only UI caller sends
  the unchanged currency (`InstallmentEditScreen.kt:103-105`, :167, :238).
- **Enforced in the coordinator since PE-A (2026-10-28, pre-enable batch).** The pure rule is
  `InstallmentSplitCurrencyRule.equalSplitAllowed(planCurrency, purchaseCurrency)` (true only when both are known and
  equal; null fails closed). It refuses with the existing `CURRENCY_MISMATCH` code (already mapped in the UI; no new
  code or string):
  - `declareOnExpense`: `EQUAL_SPLIT` with `terms.currency` ≠ the stored purchase currency, after the schedule checks
    (so UNSUPPORTED_CURRENCY, INVALID_COUNT and the other earlier refusals keep their precedence) and before any write;
  - `declareWithNewExpense`: the same comparison against the request currency, in the pure-check block before the TLC
    create, so nothing is created and no TLC audit event is written;
  - `editDetails`: a currency change of a PURCHASE_ATTACHED `EQUAL_SPLIT` plan to a currency other than the purchase
    currency, AFTER `CURRENCY_LOCKED`. Name, lender, category and mode edits of a legacy mismatched plan stay allowed,
    and so does a change back to the purchase currency. `FIXED_PAYMENT` plans and STANDALONE plans are unaffected.
  The restore path is not enforced (a restored legacy plan is left as stored). The declare sheet enables the
  equal-split chip only through the same function (`DeclareInstallmentsUiState.equalSplitAllowed`), so a null plan
  currency no longer leaves the chip enabled (F-2).
- **Past-due catch-up (PE-A proof, item 3).** A plan declared with `paidBeforeTrackingCount = 0` on an old purchase
  leaves its already-due entries SCHEDULED. Auto-record applies only when `declaredOn <= due <= today`
  (`InstallmentPlanTransitions.isAutoRecordApplicable`), so neither the worker catch-up nor the pre-backup reconcile
  records an entry due before `declaredOn`; the user confirms or skips those (S13 re-confirms).

**Example (clarifies AE-5; no new AE number; synthetic data).**
- Given a purchase of 240.00 USD dated 2027-01-05 and N = 4:
  - plan currency USD: both split methods are offered, and `EQUAL_SPLIT` gives 4 × 60.00 USD with
    `declaredTotal = 240.00`;
  - plan currency EUR: `EQUAL_SPLIT` is not offered and `FIXED_PAYMENT` is the effective method. Save is blocked
    until a payment is typed. After 56.00 is typed, the plan is 4 × 56.00 EUR, `declaredTotal = null`, and financing
    cost is "unavailable" (currency differs).
- Given a purchase of 300.00 CHF: no plan currency is preselected and Save is blocked. After EUR is picked, only
  `FIXED_PAYMENT` is possible.

**Follow-ups raised, not decided (need an explicit decision; they are not part of 51c).**
- F-1 Single owner. The narrowing lives in the UI layer only. If it must hold for every caller, make it an invariant
  (it would be INV-35) owned by the plan coordinator, which refuses a PURCHASE_ATTACHED `EQUAL_SPLIT` whose plan
  currency ≠ the purchase currency. The UI check would then only mirror it. This changes implemented S6a behaviour and
  needs a refusal code choice: the existing `CURRENCY_MISMATCH` means "linked expense in another currency". It also
  covers the `editDetails` currency change. Recommendation: pre-enable hardening, not before.
- F-2 UI nit. For a purchase in an unsupported currency (CHF) before a plan currency is picked, `currencyDiffers`
  is false (:151), so the "Equal split" chip is enabled (:519) although `effectiveSplit` already returns
  `FIXED_PAYMENT` (:146). A tap changes nothing visible. It cannot violate the rule, because saving needs a picked
  currency.

## Clarification 2026-10-28 — INV-29 owner (S8b-3)

Append-only. Nothing above this heading is rewritten. This section clarifies who owns INV-29 on the Home weather path
and in which scope it applies. Schema: none. New invariants: none. New refusal codes: none.

- **Owner of the Home weather path.** `HomeInstallmentWeatherSource` (`data/repository`, off the cloud surface) is the
  Home-only installment-aware weather source. It is consumed only by `HomeViewModel`, which REPLACES the single
  `FinancialWeatherWidget` after `ComputeDashboardWidgetsUseCase.compute()` (`HomeWeatherMerge`). The exclusion itself
  stays single-owned by `RecurringInstallmentEligibility.excludedSnapshotIds`; the source only calls it.
- **Projected entries are planned inputs.** Each projected entry (31-day window, SCHEDULED entries of ACTIVE plans) is a
  MUST planned input in plan currency fed to the unmodified `ForecastInputAssembler.assemble`. Its existing planned-expense
  normalisation converts it (INV-16) or omits it and marks the forecast partial; it is never 0 and never summed raw.
  Projections are never an `UpcomingItem`, and `upcomingItems` equal the baseline list.
- **Scope is GLOBAL while any entry is projected (owner-approvals 53a), not per plan, and applies to history BEFORE the
  current month only (owner-approvals 60b).** While at least one entry is projected, ALL installment-linked and
  plan-purchase history dated before the start of the current month (home time zone) is removed from the aware view's
  snapshots, whichever plan the projected entry belongs to. Those are the baselines (average month, previous month,
  typical daily discretionary). Current-month actual installment payments stay in month-to-date, so a paid installment
  is counted once (as an actual) and never zero times; the projection adds only the entries still SCHEDULED, so an
  installment is counted exactly once for the month. The aware view replaces the baseline view and the two are never
  summed. Accepted trade-off: the pace ratio compares a current month that includes installments against a baseline
  that excludes them.
- **Expected step change.** When the last projection ends (the final entry is paid, the plan is stopped or completed, or
  the window holds no entry), the source emits null and Home shows the baseline again. The linked history then
  re-enters the baseline patterns, so the baseline can change by a step on that day. This is expected behaviour, not a
  defect (AE-7, AE-11).
- **Cloud path unchanged (A15).** The `FinancialWeatherRepository` chain (`ForecastInputAssembler`, `SynthesisEngine`,
  `NarrativeGenerator`, `DashboardDataProvider`, `DashboardBriefingInputBuilder`) stays installment-free; the registered
  guard `installment_cloud_reachability` fences it (INV-31). No `FinancialWeather` field carries installment data.
  Other Home widgets (Monte Carlo is handled in S8b-2; runway and health score) are not changed by S8b-3.
