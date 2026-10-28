# UX flows: loan-installments — Installment Overview & Installment-Declared Expenses

Author: ux-flow-designer (stage 4, reconciled against `domain-spec.md`). Docs-only, post-1.0. Writes nothing outside
this file.
Inputs: `brief.md` (Gate 1 GO + owner addenda), `critique.md`, `domain-spec.md` (DRAFT, owner decisions OD-1..OD-15
pending), existing Compose UI under `app/src/main/java/com/yourname/expensetracker/ui/`.

Scope guard (binding from brief): EUR and USD only, shown as separate per-currency totals, never silently summed;
installments count in the budget of the month they fall due; plan history is in v1; lent/borrowed money is deferred to
increment 2 (placeholder note only); no new permissions, no network, no cloud AI; no APR, no interest/principal split,
no amortization input.

Reference note: `AddExpenseViewModel.kt`, `TransactionsScreen.kt` and `TransactionsViewModel.kt` are being modified by
unrelated Release-1.0 work in another worktree. This document refers to them only as the surfaces that host the
installment affordances (Add-expense form, Transactions row menu/filter/list). It does not assume their current exact
content; the planner must re-read them at implementation time.

---

## Reconciliation log (stage 4 pass against `domain-spec.md`)

What changed relative to the first draft (which was written without the spec):

1. **Section 0.2 (DD index)**: every DD-1..DD-15 is now classified RESOLVED BY SPEC (with section/INV/decision),
   MAPPED TO OD-n (flow kept neutral, both variants shown briefly), or SPEC GAP (no spec rule; raised as a new OQ).
   Result: 9 resolved (DD-2, 5, 6, 7, 8, 9, 10, 12, 13), 4 mapped to an OD (DD-1->OD-3, DD-3->OD-5, DD-4->OD-1,
   DD-11->OD-9; some also partly resolved), 2 spec gaps (DD-14 standalone default, DD-15).
2. **Plan states** replaced: Active / Completed / Cancelled(Stop) / **Refunded** / **Paid off** (spec 3.1). Entry
   states replaced with the spec's stored set (Scheduled, Paid, Skipped, Cancelled, Reversed) plus derived
   Upcoming / Due today / Overdue (spec 3.2). The first draft's "Due"/"Past due" wording is gone.
3. **Fulfilment mode** (AUTO_RECORD vs CONFIRM, spec 2.1, 6 Q3, 8.1, 8.2) added to create/declare forms, plan detail
   and the overview "Needs your attention" logic. The first draft's "Mark them as paid / Leave them to confirm later"
   choice for past installments is removed: spec 8.1 and INV-12 say past-at-declaration entries are never
   auto-recorded and need an explicit record/link/skip, with a bulk "Record all past payments" action.
4. **Purchase record "not counted"** now follows `spendingExclusionReason` (spec 2.3, INV-1, INV-2, INV-22): marked
   rows, locked fields on edit (`EXPENSE_LOCKED_BY_INSTALLMENT_PLAN`), and a second marked kind for reversed
   installment expenses.
5. **End-plan flows** re-specified from spec 7.1-7.3 and 7.7: Stop, Refund/Return (with the mandatory "is the refund
   already recorded?" question and no partial refunds), Pay off early (date + amount). Delete is now offered **only
   when no payments are recorded** (7.5, `PLAN_HAS_PAYMENTS`).
6. **Linked expense changed or deleted elsewhere** and **reconcile/repair** states added (spec 7.6, R1-R6, INV-17):
   reopened entries, "Expense removed", purchase detached, plan reopened from Completed, transient
   "paid amount unavailable".
7. **UI states removed or replaced** for lack of spec cause: `UNSUPPORTED_CURRENCY` (state), generic
   `DATA_UNREADABLE`, overview-wide `RESTORE_PENDING` read state, `ALREADY_DECLARED` race as a separate state (now a
   spec refusal code), `END_DATE` unavailable (narrowed to one spec-backed case). See section 9.
8. **Features dropped because the spec does not model them**: plan "Type" chips (OQ-5 closed), "Payments already
   made" history-only field (DD-15 gap), free-text skip note and "Your note" in the audit (spec has bounded
   `adjustReason`/`statusReason` only), history retention footer (INV-26: no cleanup), plan-data export entry (OD-11:
   backup only), frequency chips (OD-8 recommends monthly only).
9. **Undo corrected**: Undo of a *declaration* is a clean Delete plan (INV-27) while no payment exists. Undo of
   *Confirm* is NOT clean (unlink leaves the created expense in place, spec 5.2 "The expense is untouched"), so Confirm
   gets no Undo; it gets "View expense" instead. Skip/unskip, link/unlink and adjust remain reversible.
10. **Change history** reduced to what the spec stores (entry-row audit fields, no lifecycle-event table, OD-13). A
    full chronological log exists only if OD-13's alternative is chosen.
11. **Privacy copy corrected**: plan data stays on device and in encrypted backup, but installment expenses are
    ordinary expenses that use the plan name as merchant and follow normal export/cloud policy (spec 11.5, INV-28).
    "Stays on this device" is no longer a blanket claim.
12. **Open questions re-triaged** (section 10): OQ-2, 4(partly), 5, 6, 9, 12, 13, 15 closed by the spec; OQ-1 and
    OQ-3 mapped to OD-3 and OD-8; OQ-7 and OQ-14 kept visible; new OQ-18..OQ-25 added.

---

## 0. Conventions

### 0.1 Vocabulary used on screen

| Term | Meaning | Never say |
|---|---|---|
| Installment plan ("plan") | A finite list of scheduled payments, attached to a purchase or standalone | "loan balance", "debt" |
| Installment | One scheduled payment (a REGULAR entry) of a plan | "recurring payment" |
| Purchase record | The original full-amount expense. It stays in the list, keeps its receipt and source, and counts 0 toward spending while the plan exists | "deleted", "hidden", "refunded" |
| Remaining scheduled payments | Sum of SCHEDULED entries per currency (includes overdue unrecorded ones) | "outstanding principal", "balance owed" |
| Financing cost | Total of non-cancelled installments minus purchase amount, same currency only (spec 9). Can be negative | "interest", "APR" |
| Recorded / Confirmed / Linked | The three ways an installment becomes a real, counted expense (auto-record, user confirm, user link) | "paid" for a charge that is not linked |
| Not counted | Shown wherever an amount exists but adds 0 to spending (purchase record, reversed installment, overdue unrecorded installment) | "ignored" |

Plan status labels: Active, Completed, Cancelled (the Stop action), Refunded, Paid off. The action is called
"Stop plan"; the resulting status is "Cancelled" to match the owner's wording (B-3).

### 0.2 Domain decision index (DD) after reconciliation

Legend: **RESOLVED** = the spec fixes the rule, the flow is updated. **OD-n** = the spec recommends an answer but the
owner must decide; the flow shows the recommended variant and briefly the alternative. **GAP** = the spec is silent.

| ID | Original question | Status | Spec reference | Effect on UI |
|---|---|---|---|---|
| DD-1 | Stop vs refund vs early payoff | **OD-3** (rule content RESOLVED) | 7.1-7.3, 7.7; INV-20, INV-23 | Chooser with three explicit actions + restricted Delete (3.7). Variant B: owner wants one "Cancel" button -> maps to Stop, Refund stays a separate action |
| DD-2 | Do past-due unconfirmed installments count? | **RESOLVED** (default mode is OD-4) | 6 Q3, 8.1; INV-12, INV-14; AE-10 | No virtual counting. An entry counts only through a real expense. Overdue unrecorded entries show "Not counted yet". Past-at-declaration entries are never auto-recorded (3.1, 3.2, 3.5) |
| DD-3 | Does a "ΔΟΣΗ n/N" charge auto-fulfil an installment? | **OD-5** | 6 Q4, 8.2; INV-13; AE-17 | Never automatic. Captured charge = one-tap suggested link; CONFIRM mode chosen at setup if the bank notifies. Variant B (no linking of captured charges) removes the suggestion UI and the setup question |
| DD-4 | Reuse recurring lifecycle or separate | **OD-1** (recommended variant RESOLVED) | 6 Q1; INV-25 | Plans have their own overview and never appear inside the Recurring rule lists. Header link from Recurring is only a navigation shortcut. If OD-1 flips to "reuse", Recurring list placement must be redesigned |
| DD-5 | When do installments become expenses | **RESOLVED** (mode default is OD-4) | 6 Q3; INV-12, INV-14 | Never virtual. AUTO_RECORD records on/after the due date; CONFIRM requires Confirm or Link. Confirm creates an expense; Transactions contains only real expenses, never future installments |
| DD-6 | Editing after payments | **RESOLVED** | 7.4 E1-E4; INV-19, INV-22 | Only SCHEDULED entries editable; paid entries are edited via their expense; currency and split method locked after payments; purchase amount/currency/type/ownership locked (3.7) |
| DD-7 | Rounding and residual | **RESOLVED** (placement is OD-6, mode OD-7) | INV-9, INV-10, INV-11; 9; AE-2, AE-3 | Exact integer split; residual on the first installment by default ("First payment adjusted"). Under OD-6 alternative the label becomes "Last payment adjusted". Fixed payment has no residual |
| DD-8 | Skip semantics | **RESOLVED** | 3.2; INV-6; AE-16 | Skipped entries add 0, do not reschedule and do not add a trailing payment; lifetime total = sum of what was actually paid. Skip carries a bounded reason, not free text. Unskip is allowed |
| DD-9 | Retention/cleanup of history | **RESOLVED** | INV-26; 11.1, 11.2, 11.3 | No cleanup, no retention footer. Plans are in encrypted backup. Delete from History exists only for a plan without payments |
| DD-10 | Delete plan and purchase counting | **RESOLVED** | 7.5, 7.6; INV-23, INV-27; AE-14, AE-25 | Delete only without PAID/REVERSED entries; purchase then counts in full again on its original date (stated in the dialog). Deleting linked expenses reopens entries / detaches the plan |
| DD-11 | Shared / not-mine / split purchases | **OD-9** | 7.0; AE-22 | Recommended: declaration refused (`NOT_MINE`, `SHARED_NOT_SUPPORTED`, `SPLIT_NOT_SUPPORTED`); shown disabled with reason. Variant B: supported with the share amount |
| DD-12 | Duplicate detection vs legitimate installments | **RESOLVED** | INV-4, INV-5, INV-8, 6 Q4; AE-6, AE-13 | Plan expenses use STRICT_EXTERNAL_ID, so no duplicate card for them. The purchase record stays a duplicate target (a re-captured notification is still blocked). Pickers exclude already linked / purchase records. Derived "possible duplicate of installment n" indicator |
| DD-13 | Atomic declaration and clean undeclare | **RESOLVED** (OD-15 conditional) | 6 Q5; INV-27; AE-6, AE-14, AE-15 | One transaction or nothing; Save failure = "Nothing was changed". Undo of a declaration = Delete plan (while no payments). Under OD-15 fallback the purchase may count in full until the next reconcile (no extra UI) |
| DD-14 | Category default for standalone plans | **GAP** (partly RESOLVED) | 2.1 `categoryId` nullable; 6 Q13; E3 | Resolved: purchase-attached plans copy the purchase category; a category change affects only expenses created afterwards. Not specified: default category for a standalone plan -> OQ-19 |
| DD-15 | Payments made before tracking | **GAP** | 8.1, AE-10c (nearest rules) | The spec has no "history-only payment". Past entries become OVERDUE-unrecorded and must be recorded, linked or skipped. The first draft's "Payments already made" field is removed; neutral guidance replaces it -> OQ-18 |

---

## 1. Entry points & navigation

### 1.1 Findings from the existing app that shape the proposal

- Navigation is a sealed class `NavigationDestination` (`ui/navigation/NavigationDestination.kt`) rendered by
  `MainActivity.kt`; overlay-style screens (AddExpense, ManualRecurringExpense, RecurringExpenses) are destinations
  that return via `navigation.navigateBack()`. Feature screens are listed in `FeatureConfig.allFeatures`
  (`ui/navigation/FeatureConfig.kt`) and shown in the Home Features menu (`HomeScreen.kt` `FeaturesMenu`).
- There is **no standalone expense detail screen**. Expense actions live in the Transactions list row overflow menu
  (`TransactionsScreen.kt` `TransactionItem`: edit ownership, "mark as recurring", location, provenance, delete) and
  in `ModalBottomSheet`/`AlertDialog` helpers. "Declare installments on an existing expense" attaches to that
  overflow menu, next to "mark as recurring" (same family of intent). See OQ-16.
- Add-expense is a full-screen overlay `AddExpenseSheet.kt` with a "Repeat transaction" switch + frequency chips.
  Installments go in the same area (mutually exclusive with Repeat).
- Home upcoming/planned items render in `FinancialWeatherCard` (`ui/components/FinancialWeatherCard.kt`) with
  `onManageClick` (to Recurring) and `onPlanClick` (`AddPlannedExpenseDialog`). Bill reminders:
  `ui/screens/reminder/BillRemindersScreen.kt`. Cash flow: `ui/screens/cashflow/CashFlowCalendarScreen.kt`
  (`DailyCashFlowDetails` "Recurring items").
- Shared state contracts exist and are reused: `LoadableUiState` (Loading/Data/Empty/Error), `MutationState` (dialogs
  close only on success; double-tap guard), `MoneyDisplayUi` (currency explicit, partial warning). Shared components:
  `EmptyState`, `ErrorState`/`InlineErrorBanner`, `LoadingSkeleton`, `RouteContentPattern`.
- Existing anti-pattern NOT to copy: `ManualRecurringExpenseViewModel` puts `e.message` into the UI error and
  `AddExpenseSheet` renders `result.message`. Installment errors use bounded reason codes mapped to fixed strings only
  (section 4).

### 1.2 Proposed destinations (names for the planner; no code)

| Destination | Type | Reached from |
|---|---|---|
| `Installments` (overview) | Feature screen, full screen, back arrow | Features menu entry; Recurring screens header chip; reminder/cash-flow/budget rows; Transactions installment chip |
| `InstallmentPlanDetail(planId)` | Full screen, back arrow | Overview card; History card; reminder row; cash-flow row; Transactions installment chip; post-declare snackbar "View plan" |
| `CreateInstallmentPlan` | Full-screen overlay | Overview FAB; overview empty state |
| `EditInstallmentPlan(planId)` | Full-screen overlay (details + schedule changes) | Plan detail overflow (Active plans) |
| `InstallmentHistory` | Full screen, back arrow | Overview top-bar action "History" |
| Declare sheet (not a destination) | `ModalBottomSheet`, fully expanded, hosted by Transactions | Row overflow "Pay in installments…" |

### 1.3 Feature-menu entry

New `FeatureConfig` item: id `installments`, title "Installments & loans", description "Track payments you still owe and
when they end". Icon suggestion: `Rounded.EventRepeat` or `Rounded.Payments` (distinct from `Rounded.Repeat` used by
Recurring). Deep-link tokens follow the existing `NavigationController` scheme (`installments`,
`installment_plan?id=`); deep links are only needed for reminder-notification taps (reuse BillReminder tap routing, no
new external links).

### 1.4 Back behavior

- Overview, History, Plan detail: top-bar back arrow and system back pop one level. Plan detail opened from a
  reminder/cash-flow row returns to that surface (stack-based).
- Create plan / Edit plan / Declare sheet with unsaved input: back or Close shows "Discard this plan?" (Discard / Keep
  editing). AddExpense keeps its current back behaviour (discard), with no extra dialog, to avoid changing the
  existing flow.
- After successful create/declare: Create plan pops to Plan detail. Declare sheet closes and a snackbar appears on
  Transactions: "Installments set up" with **View plan** and **Undo** (Undo = Delete plan, valid while no payment is
  recorded, see 3.2).
- Dialogs/sheets close only after success (`MutationState` rule); failures render in-context. A transition reported as
  `AlreadyInState` (spec 3.1: idempotent retry) is treated as success: no error is shown.

### 1.5 Where installments appear elsewhere (entry affordances)

- Transactions row: chip "Installment 3 of 12" on installment expenses (tap -> plan detail); chip "Paid in 12
  installments" + "Not counted as spending" on the purchase record. Reversed installment expenses show "Not counted
  (refunded)".
- Bill reminders / Home weather card upcoming items / cash flow day detail: row subtitle "Installment 4 of 12 · Laptop";
  tap -> plan detail. Only SCHEDULED entries of ACTIVE plans due today or later appear there (INV-15). Overdue
  unrecorded entries are surfaced on the overview "Needs your attention" instead.
- Recurring screens: optional header link "Installments & loans" (navigation only; plans are never listed among
  recurring rules, 6 Q1).

### 1.6 Increment-2 placeholder (informational only)

No UI for "money owed" in v1 (B-4). Layout allows a later section below "Active plans". Nothing rendered, labelled or
navigable for it. See OQ-17.

---

## 2. Shared patterns

- **Screen architecture:** Route (ViewModel, events) / Content (pure state) / Cards / Dialogs per `RouteContentPattern`.
  One sealed UI state per screen (section 4).
- **Plan status chips** (icon + text, never colour alone): Active, Completed, Cancelled, Refunded, Paid off.
- **Entry status chips** (icon + text): Upcoming (calendar), Due today (clock, emphasised), Overdue (warning),
  Paid (check) with origin sub-label, Skipped (skip icon, muted), Cancelled (muted, reason), Reversed (undo-arrow,
  muted), plus secondary chips **Adjusted** and **Differs from plan** (paid amount != scheduled).
- **Progress:** linear "4 of 12 paid" always with text. Paid counts REGULAR entries in PAID; skipped/cancelled are not
  paid.
- **Money:** `CurrencyFormatter.formatMoney(amount, currency)`; currency always present (section 5).
- **Snackbar with Undo only where the inverse is clean:** Undo is offered for Skip (unskip), Link (unlink), Adjust
  (adjust back; the original amount is retained as `originalScheduledAmount`) and Declare (Delete plan, while no
  payment exists). It is NOT offered for Confirm (unlink would leave the created expense, spec 5.2): Confirm shows
  "Payment recorded" with **View expense**. Stop, Refund, Pay off and Delete have no Undo.
- **Destructive dialogs:** `AlertDialog`, error-coloured confirm button, cancel `TextButton` (pattern from
  `ManualRecurringExpenseScreen` delete confirm and `DeleteConfirmationDialog` in Transactions).
- **Sheets:** `ModalBottomSheet(skipPartiallyExpanded = true)` for row actions and declare form.
- **Tokens:** `Dimens.Space*`, `Dimens.TouchTargetMin` (48dp), `SemanticColors`.

---

## 3. Screens and flows

### 3.1 Flow 1 — Declare installments on a NEW expense (inside Add expense)

**Purpose:** let the user say "I'm paying this in N parts" while recording it, so the app never records a single
full-amount spend. **Entry:** Add expense overlay (`AddExpenseSheet`).

**Placement:** directly under the "Repeat transaction" switch row and before "Not mine".

**Eligibility rules in UI (spec 7.0):**
- Visible only when Transaction type is a spending type (Purchase); hidden for Deposit/Withdrawal/Transfer
  (`NOT_A_SPENDING_EXPENSE`).
- "Repeat transaction" and "Pay in installments" are mutually exclusive, with the supporting text "Repeat and
  installments can't be combined."
- "Not mine" on: switch disabled with "Installments aren't available for expenses that aren't yours." (`NOT_MINE`).
  "Shared expense" on: switch disabled with "Installments aren't available for shared expenses yet."
  (`SHARED_NOT_SUPPORTED`). **OD-9 variant B:** switch enabled and helper "Installments split your share."
- Split-category purchases: same disabled treatment (`SPLIT_NOT_SUPPORTED`).
- Currency: the Add-expense form has no currency picker (home currency only). The plan currency defaults to the
  expense currency and the plan currency row is hidden when expense currency is EUR or USD. **OQ-7 stays open:**
  new USD plans need either a currency field in Add expense (change to an existing screen) or only standalone / existing
  USD expenses. If the home currency is neither EUR nor USD the switch shows a currency row (EUR / USD, **OD-14**
  recommended: plan currency may differ from the purchase currency) and a note "Financing cost won't be available when
  the currency differs." The amount to split must then be entered (spec 2.1 `declaredTotal` only defaults when
  currencies match).

**Wireframe (Add expense, installments on, recommended monthly-only per OD-8):**

```
┌───────────────────────────────────────────┐
│ ✕  Add expense                    [ Save ] │
├───────────────────────────────────────────┤
│  Merchant  [ Demo Electronics .......... ] │
│  Amount    [ € 600.00 ................. ]  │
│  Payment method  (Card)(Cash)(Transfer)    │
│  Category  [grid]                          │
│  Date      [ 10 Mar 2027 ]                 │
│  Transaction type: Purchase  v             │
│  Notes v                                   │
│  Repeat transaction                 (off)  │
│  Pay in installments                (ON)   │
│ ┌───────────────────────────────────────┐ │
│ │ Number of payments   [ - ]  12  [ + ]  │ │
│ │ How is it split?                       │ │
│ │ (• Equal split of total) ( Fixed       │ │
│ │                          payment × N)  │ │
│ │ Each payment   € 50.00   (calculated)  │ │   <- editable in Fixed mode
│ │ Repeats        Monthly                 │ │   <- static unless OD-8 adds more
│ │ First payment on  [ 10 Apr 2027 ]      │ │
│ │ How should payments be recorded?       │ │
│ │ (• Record automatically on due date)   │ │
│ │ (  I'll confirm each one)              │ │
│ │ ⓘ Choose "I'll confirm" if your bank   │ │
│ │   sends a notification for each        │ │
│ │   installment, so it can be linked.    │ │
│ │ ───────────────────────────────────── │ │
│ │ 12 × €50.00 = €600.00                  │ │
│ │ Ends 10 Mar 2028                       │ │
│ │ This purchase will count as 12 monthly │ │
│ │ installments, not €600.00 at once.     │ │
│ └───────────────────────────────────────┘ │
│  Not mine                           (off)  │
│  Shared expense                     (off)  │
└───────────────────────────────────────────┘
```

**Fields:**
1. Number of payments: stepper + numeric field, integer **2..480** (`INVALID_COUNT`); message "Enter a number from 2 to
   480."
2. Split mode: **Equal split of total** (exact integer split, INV-9) or **Fixed payment × N** (INV-10; the user types
   the payment; the total may differ from the purchase price).
3. Each payment: read-only in Equal mode, editable in Fixed mode. Amounts below the minimum that would give an entry
   under 0.01 are refused (`AMOUNT_TOO_SMALL_FOR_COUNT`): "The amount is too small for this many payments."
4. Repeats: **Monthly** only (**OD-8** recommended). If the owner approves BIWEEKLY/QUARTERLY the static row becomes
   chips; nothing else changes.
5. First payment date: reuse `DateSelector`. Must not be before the purchase date (`FIRST_DUE_BEFORE_PURCHASE`):
   "Choose a date on or after the purchase date." Proposed default one month after the purchase date (UX default, the
   spec sets only the lower bound; OQ-4 partly closed). If the anchor day is 29-31 the preview shows
   "Payments due on the 31st fall on the last day of shorter months." and lists actual dates for the first months
   (INV-18, AE-4).
6. Recording mode (spec 2.1 `fulfilmentMode`): two options as above. Default preselection is **OD-4** (recommended:
   record automatically); the helper text implements **OD-5** (bank notification -> confirm mode). If OD-5 is declined
   the helper sentence and its captured-charge suggestions disappear, the radio stays.
7. Live preview card (always visible when switch on): `N × amount = total`, end date, one spending-rule sentence.
   - Equal mode with a residual: "First payment €33.34, then €33.33" with the "First payment adjusted" marker (**OD-6**
     recommended FIRST; under the alternative it reads "Last payment €33.34").
   - Fixed mode: "Financing cost: €24.00 (total of installments €624.00 − purchase €600.00)". If the total is below
     the purchase price the line reads "Financing cost: −€10.00" with sub-text "Total of installments is less than
     the purchase price." (negative values are legitimate, spec 9; OQ-6 closed).
8. **First payment in the past** (`firstDueDate < today`): inline notice (not a choice): "N installments are already
   past due. They won't count until you record or link them." After saving, those entries show as Overdue and the plan
   shows the bulk action **Record all past payments** (3.5). They are never auto-recorded (spec 8.1, INV-12, AE-10c).

**Primary action:** the existing top-bar **Save** (single atomic save of purchase record + plan, INV-27). Disabled
while `mutation.isRunning` or home currency unknown. **Secondary:** turn the switch off to discard the installment
configuration; values are kept in memory until the sheet closes so a re-toggle restores them. A stable plan
identity (`planUuid`) is generated once per form session so a double-submitted Save creates one plan (AE-15d); the UI
needs no extra state for that beyond the existing double-tap guard.

**Duplicate handling (DD-12 resolved):** the existing "Duplicate transaction" error card applies to the purchase only.
Plan-generated installment expenses use strict idempotency keys and never trigger it.

**States (installment section of Add expense):**

| State | Rendered |
|---|---|
| Idle / invalid input | Preview hidden or "Fill in the number of payments"; installment-invalid blocks Save with field errors |
| Saving | Existing Save spinner; fields disabled |
| Home currency not yet loaded | Switch disabled (same gate as Save); no preview |
| Amount empty/invalid | Preview shows "Enter the purchase amount first" (never "€0.00 × 12") |
| Save refused (spec code) | Field-level or banner message mapped from the bounded code (section 4.3) |
| Save failed (storage) | In-context card: "Couldn't save the installment plan. Nothing was changed." (atomic, INV-27) |
| Offline | N/A (local only) |

### 3.2 Flow 2 — Declare installments on an EXISTING expense (manual or captured)

**Entry:** Transactions list row overflow menu -> **Pay in installments…** (icon `Rounded.EventRepeat`, next to "Mark as
recurring").

**Menu variants by expense state (spec 7.0):**
- Eligible spending expense: **Pay in installments…**.
- Already the purchase record of a plan, or already an installment of a plan: **View installment plan**.
- Not mine / shared / split / linked to a recurring occurrence: item shown disabled with a reason line
  (`NOT_MINE`, `SHARED_NOT_SUPPORTED`, `SPLIT_NOT_SUPPORTED`, `LINKED_ELSEWHERE` -> "This expense is already part of a
  recurring payment."). Non-spending types (income/transfer): item hidden.

**Surface:** fully-expanded `ModalBottomSheet` titled "Pay in installments" with a purchase header (merchant, date,
amount with currency, source badge using the existing provenance vocabulary).

**Wireframe:**

```
┌───────────────────────────────────────────┐
│ ━━                                         │
│ Pay in installments                    ✕   │
│ ┌───────────────────────────────────────┐ │
│ │ Demo Electronics   10 Mar 2027  Card    │ │
│ │ €600.00            From: notification   │ │
│ └───────────────────────────────────────┘ │
│ Number of payments   [ - ]  12  [ + ]      │
│ (• Equal split of total)(  Fixed payment×N)│
│ Each payment  €50.00                       │
│ Repeats       Monthly                      │
│ First payment on  [ 10 Apr 2027 ]          │
│ How should payments be recorded?           │
│ (• Record automatically)(  I'll confirm)   │
│ ─────────────────────────────────────────  │
│ 12 × €50.00 = €600.00 · ends 10 Mar 2028   │
│ ⓘ What changes                             │
│   • This purchase stays in your list and  │
│     its receipt/source link is kept. It    │
│     can no longer change amount, currency  │
│     or type while the plan exists.         │
│   • It stops counting as €600.00 on        │
│     10 Mar 2027. Each installment counts   │
│     in the month it is recorded and due.   │
│   • Totals and budgets for those months    │
│     will change.                           │
│ [ Cancel ]            [ Set up installments ]│
└───────────────────────────────────────────┘
```

**Primary:** **Set up installments** (enabled when valid; spinner while running; sheet closes only on success).
**Secondary:** Cancel / ✕ (discard confirmation only if values changed from defaults).

**"What changes" block** is mandatory here because the action rewrites how past months look. It is an expandable info
row, collapsed by default on small screens but announced by TalkBack; "Totals for N months will change" stays visible.
When `firstDueDate` is in the past it adds: "N installments are already past due and won't count until you record or
link them. Totals for those months drop until then." (spec AE-10c: those months show 0 until the user acts).

**Captured-expense specifics:** receipt links, provenance and dedupe stay on the purchase record. Helper text:
"The receipt stays attached to the purchase." No raw notification/receipt text is rendered (only merchant, amount, date
and source label already shown in the list).

**After success:** snackbar on Transactions: "Installments set up for Demo Electronics" with **View plan** and **Undo**.
Undo = Delete plan (INV-27): clears the purchase flag, the purchase counts in full again on its original date. If the
plan already has a payment (an auto-recorded entry due today), Undo fails with `PLAN_HAS_PAYMENTS` and the snackbar
becomes "A payment was already recorded. Use End plan to change this plan." with **View plan**. The purchase row
immediately renders in its neutralised style (3.9).

**States:**

| State | Spec cause | Rendered |
|---|---|---|
| Preparing sheet | n/a | Skeleton for header + form |
| Ready | n/a | As wireframe |
| Expense not found/changed meanwhile | expense row missing at txn start (7.0 "must exist") | ErrorState `NOT_FOUND`, non-retryable: "This expense is no longer available." + Close |
| Not eligible (stale menu) | 7.0 refusal codes | ErrorState: "Installments can't be set up for this expense." plus the specific reason line |
| Already declared (race) | `ALREADY_DECLARED` (INV-3) | Banner "This purchase is already paid in installments." + **View plan** |
| Validation failed | `INVALID_COUNT`, `INVALID_AMOUNT`, `FIRST_DUE_BEFORE_PURCHASE`, `AMOUNT_TOO_SMALL_FOR_COUNT`, `UNSUPPORTED_CURRENCY` | Field messages (section 8.2) |
| Save running | n/a | Button spinner, fields disabled |
| Save failed (storage / write blocked) | transaction rolled back (INV-27); restore/retention barrier | `InlineErrorBanner` "Couldn't set up installments. Nothing was changed." + Retry |
| Offline | N/A | not rendered |

### 3.3 Flow 3 — Create a standalone plan (loan, mortgage, tax plan)

**Entry:** Overview FAB "+" ("Add plan"), or overview empty-state primary action. Full-screen overlay.

**Purpose:** track a fixed number of payments with a fixed payment amount from a lender statement. STANDALONE plans
are always FIXED_PAYMENT (spec 2.1). No principal, rate or term inputs (B-5). The loan disbursement is never recorded
as income or spending in v1.

**Wireframe:**

```
┌───────────────────────────────────────────┐
│ ←  Add plan                       [ Save ] │
├───────────────────────────────────────────┤
│ Plan name *         [ Home loan ........ ] │
│ Lender (optional)   [ ................. ]  │
│ Currency            (• EUR) (  USD)        │
│ Payment amount *    [ 420.00 ........... ] │
│ Number of payments *  [ - ] 240 [ + ]      │
│   Enter the payments you still have to     │
│   make from the first one you want to      │
│   track.                                   │
│ Repeats             Monthly                │
│ First payment on *  [ 5 Nov 2026 ]         │
│ How should payments be recorded?           │
│ (• Record automatically)(  I'll confirm)   │
│ Category            [ choose category v ]  │
│ ───────────────────────────────────────── │
│ 240 × €420.00 = €100,800.00                │
│ Ends 5 Oct 2046                            │
│ Figures are estimates. Your lender's       │
│ statement is authoritative.                │
│ Plan details stay on this device and in    │
│ your encrypted backups.                    │
└───────────────────────────────────────────┘
```

**Field rules:**
- Plan name required (free text, bounded length). Sensitive: never in logs/diagnostics. It becomes the merchant of
  expenses the plan creates (INV-28), so the helper reads "Shown as the merchant on recorded payments."
- Lender optional free text (sensitive; included in encrypted backup, stripped in privacy modes that strip expense text).
- **No Type chips** (Loan/Mortgage/…): the spec stores no plan type (OQ-5 closed). Cards show "Standalone plan" or
  "Purchase installments" instead.
- Currency: segmented EUR / USD, explicit choice. Default home currency if EUR/USD, else EUR; no silent conversion.
- Payment amount: positive, 2 decimals (reuse `AmountInputSanitizer` behaviour; do not change that file under this
  feature, spec 9).
- Number of payments: integer **2..480** (a one-payment plan is `INVALID_COUNT`). For payments already made before
  using the app, the user enters only the payments remaining, starting at the next one (DD-15 gap, OQ-18). If the first
  date is in the past the same inline notice and Overdue behaviour as 3.1 step 8 apply.
- Repeats: Monthly (static) per OD-8.
- Recording mode: as 3.1 step 6.
- Category: no spec default for standalone plans (`categoryId` nullable). The field is optional and defaults to the
  app's "uncategorised" behaviour; the recorded expenses carry whatever is chosen (DD-14 gap -> OQ-19).

**Primary:** Save (disabled until valid; spinner; pops to Plan detail on success). **Secondary:** back (discard
confirmation if dirty).

**States:**

| State | Rendered |
|---|---|
| Editing | Form + live preview ("Enter a payment amount" instead of 0) |
| Validation errors | Field supporting text with fixed messages (8.2) |
| Saving | Spinner in Save; form disabled |
| Save failed | In-context banner "Couldn't save this plan. Nothing was changed." + Retry |
| Offline | N/A |

### 3.4 Flow 4 — Installment overview

**Purpose:** one screen answering "what am I committed to, and until when, and what needs me?"
**Entry:** Features menu "Installments & loans"; links in 1.5.

**Content, top to bottom:**
1. **Remaining scheduled payments** card — one row per currency that has at least one ACTIVE plan with SCHEDULED
   entries (EUR, USD). Never a combined total; a currency with no plans has no row (INV-17). Footnote: "Sum of payments
   still to be made. Not your lender's outstanding balance."
2. **Next due** card — the nearest SCHEDULED entry with `dueDate >= today` across plans: name, date, amount with
   currency, "in N days" / "Today". Different currencies are compared by date only.
3. **Needs your attention** (only when non-empty), grouped rows:
   - Overdue unrecorded installments (`dueDate < today`, SCHEDULED): "Overdue 2 days · not counted yet", actions
     **Record**, Link, Skip, Adjust. Header line per currency: "1 past payment awaiting confirmation: €50.00 (not
     counted)" (AE-10b).
   - Due today in CONFIRM mode: **Confirm**.
   - Suggested link found while auto-record was deferred (`AUTO_RECORD_DEFERRED_CANDIDATE`): "A matching charge was
     found: Demo Electronics €50.00, 9 May" with **Link** / View expense (INV-13, AE-17).
   - Possible duplicate: "Possible duplicate of installment 3: a €50.00 charge on 11 Jun" with **View expense**. No
     automatic resolution (Q4). Under OD-5 variant B this row type does not exist.
   - [Amended 2026-10-26 (A2)] No exchange-rate row. Auto-record never waits for a rate (`AUTO_RECORD_DEFERRED_FX`
     removed, spec 6 Q9): a USD installment recorded without a rate is Paid, and its home-currency value follows the
     shared missing-rate display (plan R17, slice S8b).
   - [Amended 2026-10-26 (A2)] Plan without a name (`PLAN_NAME_REQUIRED`, spec INV-34; a plan restored from a
     redacted backup): its due installments are not recorded until the plan is renamed. Row text: "Row: name needed"
     in section 8 (PROPOSED, needs owner es/el sign-off). The action that opens the rename (E3) is S10's choice.
4. **Active plans** list — plan cards (ACTIVE only; a COMPLETED plan that reopens returns here).
5. Top bar: back, title "Installments & loans", actions **History**, Refresh. FAB "Add plan".

**Plan card:**

```
┌───────────────────────────────────────────┐
│ [icon] Laptop                      Active  │
│ Purchase installments · Recorded automatically│
│ ▓▓▓▓▓░░░░░░░  4 of 12 paid                 │
│ Next: 15 Jun 2027 · €50.00                 │
│ Ends 15 Jan 2028 · 8 payments left         │
│ Remaining scheduled payments  €400.00      │
└───────────────────────────────────────────┘
```

**Wireframe (overview with content):**

```
┌───────────────────────────────────────────┐
│ ←  Installments & loans        ⟳  History │
├───────────────────────────────────────────┤
│ Remaining scheduled payments               │
│  EUR   €1,250.00        (3 plans)          │
│  USD   $300.00          (1 plan)           │
│  ⓘ Not your lender's outstanding balance.  │
├───────────────────────────────────────────┤
│ Next due                                   │
│  Laptop     15 Jun · €50.00     in 3 days  │
├───────────────────────────────────────────┤
│ Needs your attention (2)                   │
│  EUR · 1 past payment not counted: €420.00 │
│  Home loan · 5 of 240   5 Jun · €420.00    │
│  Overdue 2 days    [ Record ]  ⋮           │
│  Phone plan · a matching charge found      │
│  Demo Shop €50.00, 9 May     [ Link ]      │
├───────────────────────────────────────────┤
│ Active plans (4)                           │
│  [plan card] [plan card] [plan card] ...   │
│                                  ( + )     │
└───────────────────────────────────────────┘
```

**Empty state** (`EmptyState`, plan icon): "No installment plans yet" / "Track card installments, pay-later
purchases, tax plans and loans. See what you still owe and when each plan ends." Primary **Add a plan**; secondary
text "To split a purchase you already recorded, open it in Transactions and choose Pay in installments." with
**Go to Transactions**. History action stays in the top bar.

**All-plans-finished variant** (no ACTIVE plan, History non-empty): "No active plans" / "Your finished plans are in
History." Actions **Add a plan**, **View history**.

**Per-currency row rules:** section 5. If a plan's paid amount can't be derived (transient, INV-17) the row still
shows Remaining scheduled payments (derived from SCHEDULED entries only) and omits paid-so-far figures for that plan.

**Loading:** `LoadingSkeleton`. **Error:** `ErrorState` with Retry for load failure; `InlineErrorBanner` when a refresh
fails but cached content exists. **Offline:** N/A.

### 3.5 Flow 5 — Plan detail

**Purpose:** schedule, progress, financing cost, linked purchase, activity.
**Entry:** overview card, reminders, cash flow, Transactions chip, post-declare snackbar, History.

**Wireframe (Active, purchase-attached):**

```
┌───────────────────────────────────────────┐
│ ←  Laptop                        ⋮         │
├───────────────────────────────────────────┤
│ Active          Recorded automatically     │
│ ▓▓▓▓▓░░░░░░░   4 of 12 paid                │
│ ┌───────────┬───────────┬───────────────┐ │
│ │ Each      │ Remaining │ Ends          │ │
│ │ €50.00    │ €400.00   │ 15 Jan 2028   │ │
│ └───────────┴───────────┴───────────────┘ │
│ Purchase   Demo Electronics · €600.00      │
│            10 Mar 2027 · Not counted    ›  │
│ Total of installments        €600.00       │
│ Financing cost               €0.00         │
│   (total of installments − purchase)       │
├───────────────────────────────────────────┤
│ [ Record all past payments (2) ]           │  <- only when Overdue entries exist
├───────────────────────────────────────────┤
│ Schedule                                   │
│  1  15 Mar   €50.00  Paid · recorded auto ›│
│  2  15 Apr   €50.00  Paid · confirmed      │
│  3  15 May   €50.00  Skipped · lender break│
│  4  15 Jun   €52.00  Paid · linked [Differs│
│                      from plan]            │
│  5  15 Jul   €50.00  Overdue · not counted │
│                      [Record] ⋮            │
│  6  15 Aug   €50.00  Upcoming            ⋮ │
│  ...                                       │
│  12 15 Jan   €49.96  Upcoming              │
│      ↑ first/last payment adjusted         │
├───────────────────────────────────────────┤
│ Activity (3)                            v  │
│  Plan set up 10 Mar 2027                   │
│  Installment 3 skipped · lender break      │
│  Installment 4 amount was €50.00           │
└───────────────────────────────────────────┘
```

(Glyphs are wireframe placeholders; production uses Material icons with text labels.)

**Header facts:** status chip, kind label (Purchase installments / Standalone plan), lender (when present), recording
mode, progress.

**Summary tiles:** payment amount (shows "Varies" when entries differ), remaining scheduled payments (own currency),
end date (last non-cancelled entry date; for a Completed plan with no paid entry: "Unavailable", spec 11.1 defines end
= last PAID due date).

**Financing cost row (INV-17):** shown only when a purchase is attached, the purchase currency equals the plan
currency and status is Active, Completed or Paid off. Value = sum of non-cancelled entries (for Paid off: sum paid +
payoff) minus purchase, plain subtraction, formula on a second line; may be negative (shown with sign, sub-text "Less
than the purchase price"). Otherwise "Financing cost  Unavailable" with sub-line "Needs a purchase in the same
currency" (standalone, detached, currency differs) or "Not available for cancelled or refunded plans". Never €0.00.

**Schedule row states (spec 3.2 mapping):**

| Stored / derived | Row label | Row details | Actions (Active plan) |
|---|---|---|---|
| SCHEDULED / Upcoming | Upcoming | | Link, Adjust, Skip. **Confirm is not offered** (no expense is created before the due date, INV-14) |
| SCHEDULED / Due today | Due today | AUTO: "Will be recorded automatically"; CONFIRM: "Waiting for your confirmation" | Confirm, Link, Adjust, Skip |
| SCHEDULED / Overdue | Overdue · not counted yet | Sub-reason when known: "Before you set up this plan" (past at declaration), "Its expense was removed" (reopened, auto-record already used), "A matching charge was found" (deferred, INV-13), "Row: name needed" copy (INV-34, PROPOSED, owner es/el sign-off). [Amended 2026-10-26 (A2): "Waiting for an exchange rate" removed] | Record (confirm), Link, Adjust, Skip |
| PAID | Paid | Origin: "Recorded automatically" / "Confirmed" / "Linked"; linked expense merchant + date; chip **Differs from plan** when the paid amount != scheduled amount (7.6); chip "Counts €0" if the expense was marked not mine | View expense, Unlink |
| SKIPPED | Skipped | Bounded reason label | Unskip |
| CANCELLED | Cancelled | Reason: Stopped / Refunded / Paid off early / Shortened / Expense removed | none |
| REVERSED | Refunded | "Not counted" | none |
| PAYOFF (PAID) | Payoff | payoff date, amount, origin | View expense |

`Adjusted` is a secondary chip on any SCHEDULED row (and on a Paid row if it was adjusted before being recorded)
whose amount or date differs from the original (`originalScheduledAmount`). Tap on a row opens the action sheet (3.6).
Paid rows never offer amount edits: "To change a paid amount, edit its expense." with **View expense** (INV-19).

**Skip on an auto-recorded Paid entry:** not offered directly. The row sheet explains "To skip this payment, delete its
expense in Transactions first. The installment then reopens and you can skip it." (spec 3.2).

**Purchase tile states:** purchase present (tap -> opens the purchase in Transactions); purchase detached (R5, see
below); standalone (no tile).

**Repair and drift states (reconcile R1-R6, spec 5.4 and 7.6) — rendered on next open, no toast:**

| Spec cause | UI |
|---|---|
| Installment expense deleted elsewhere, plan Active/Completed (R2) | Entry becomes Overdue/Upcoming per date, sub-reason "Its expense was removed". A Completed plan returns to Active (chip changes, appears in overview). It is not re-recorded automatically (INV-12, AE-16); offer Record / Link / Skip |
| Installment expense deleted elsewhere, plan Cancelled/Paid off (R2) | Entry shows Cancelled · "Expense removed"; plan stays terminal |
| Reversed expense deleted (R2) | Entry Cancelled · "Expense removed" |
| Linked expense currency or type changed (R4) | Entry unlinked: sub-reason "The linked expense changed currency" / "The linked expense is no longer a purchase" |
| Linked expense amount edited | Entry stays Paid, chip **Differs from plan** with "Paid €52.00 · scheduled €50.00"; the expense governs counting |
| Linked expense date edited | Paid row shows the expense's own date; month totals follow it |
| Linked expense marked not mine | Stays linked; chip "Counts €0" and Differs from plan |
| Purchase deleted elsewhere (R5, AE-25) | Info banner "The original purchase was deleted. This plan is now standalone." Purchase tile removed; Financing cost Unavailable; all totals unchanged |
| Paid amount cannot be derived (linked expense currency drifted before reconcile, INV-17) | Paid-so-far tile "Unavailable" with info icon; other tiles normal. Transient until reconcile unlinks |
| Plan with zero SCHEDULED entries (R6) | Shown Completed, never Active with an empty schedule |
| Purchase flag repair (R1/R3) | No UI (invisible repair; counts only in diagnostics) |

**Activity section (spec-limited, OD-13):** derived from entry rows (`statusReason`, `statusChangedAt`,
`originalScheduledAmount`, `adjustedAt`, `adjustReason`) and the plan (`createdAt`, `terminalAt`, `statusReason`). It
shows the latest status change per affected entry and "was €X" for adjusted amounts, newest first, capped at 20 with
"Show all". Repeated adjustments of one entry collapse to original -> current. No user note text exists. A full
chronological log appears only if OD-13's alternative (plan event table) is approved.

**Plan status banners (read-only behaviour):**
- Active: full actions.
- Completed: banner "All payments are accounted for." Row actions limited to Unlink (Paid) and Unskip (Skipped), which
  reopen the plan; overflow has Refund / return only.
- Cancelled: "This plan was stopped on 18 Jun. Payments already made still count." No row actions. Delete only if it
  has no recorded payments.
- Refunded: "This plan was refunded. Its payments no longer count." No actions.
- Paid off: "Paid off early on 5 Jul." No actions.

**Detail-level states:** Loading skeleton; Content (one of five plan statuses); Error LOAD_FAILED (retryable); Not found
(plan deleted meanwhile, non-retryable, Back); Unavailable sub-parts as above.

### 3.6 Flow 6 — Confirm / link a payment, skip, adjust

**Entry:** Due/Overdue row Record/Confirm, row tap -> **Installment actions** sheet, overview "Needs your attention".

**Installment actions sheet (contextual items only):**

```
┌───────────────────────────────────────────┐
│ Installment 5 of 12 · Laptop               │
│ Due 15 Jul 2027 · €50.00 · Overdue         │
│ ─────────────────────────────────────────  │
│ ✔  Record payment            (Due/Overdue) │
│ 🔗 Link to an expense                       │
│ ⏭  Skip this installment                   │
│ ✎  Adjust amount or date                   │
│ ⛓  Unlink expense             (Paid only)   │
└───────────────────────────────────────────┘
```

**Record payment (confirm):** one-line description of the effect: "Creates an expense of €50.00 dated 15 Jul 2027 in
[category] and marks this installment paid." For an Overdue entry the dialog additionally says "It counts in July 2027."
(the expense is dated on the due date at noon, spec 10). The action goes through the expense lifecycle, never from
the UI. No Undo; after success the snackbar offers **View expense**. If a matching unlinked charge exists the sheet
shows it first ("A matching charge was found") and recommends Link, to prevent counting the payment twice (INV-13 /
INV-7). [Amended 2026-10-26 (A2)] A missing exchange rate never blocks Record payment: the expense is created and
its home-currency value follows the shared missing-rate display (R17/S8b). If the plan has no name (INV-34), Record
payment is refused with `PLAN_NAME_REQUIRED` and nothing changes; the message is the "Row: name needed" copy
(section 8, PROPOSED).
**Bulk Record all past payments:** confirmation dialog "Creates N expenses totalling €X, each dated on its due date.
Totals for those months will change. If you already have these charges, link them instead." Buttons **Record N payments**
/ Cancel; per-entry transactions, so partial success is possible: the result states "Recorded 3 of 4. 1 could not be
recorded." (bounded reason per failed entry in diagnostics only).

**Link to an expense:**
- Candidate list: unlinked expenses in the plan currency, not purchase records, not linked to another installment or
  recurring occurrence (INV-4, INV-5), ordered by closeness to the due date. Suggested chip **Likely match** for
  amount within 0.01 and date between 5 days before and 10 days after the due date (spec 8.2). Selection enables
  **Link**; manual linking is always available.
- Search/filter by merchant (existing Transactions search pattern).
- Refusals: wrong currency -> "That expense is in a different currency than this plan." (`CURRENCY_MISMATCH`); already
  linked / part of a recurring payment -> "That expense is already linked to another installment or payment."
  (`LINKED_ELSEWHERE`).
- A captured charge keeps its own date: if it falls in another month the row note says "Counts in the month of the
  charge (OD-10)." Variant B of OD-10 (force nominal month) would require an expense date change and is not shown.
- Empty picker: "No matching expenses found. You can still record the payment without linking."
- Linking is reversible: Undo = unlink.

**Skip this installment:**
- Dialog "Skip installment 3?" Body: "It won't be counted and no payment is added at the end of the plan. You can undo
  this." (DD-8 resolved). A **reason** radio list from a bounded set (e.g. "Lender payment break", "Paid another way",
  "Other"); the exact set is a planner/owner item (OQ-20). No free text.
- Result: row Skipped; snackbar "Installment skipped" + **Undo** (unskip). If skipping leaves no SCHEDULED entries
  the plan becomes Completed in the same step (INV-21) and a notice "That was the last payment. This plan is now
  completed." appears.

**Adjust amount or date:**
- Dialog with amount and due date prefilled, "Was €50.00 on 15 Jul", delta "+€2.00", and "Remaining scheduled payments
  will change from €400.00 to €402.00."
- Validation: amount > 0; same currency; only SCHEDULED entries. A bounded reason is optional (`adjustReason`).
- Paid entries: not editable here (INV-19); "Edit the expense instead."
- Result: **Adjusted** chip; snackbar with Undo (restores previous values; `originalScheduledAmount` is retained).

**Unlink expense (Paid rows):** dialog "Unlink this expense from installment 2? The expense stays in your list and
still counts as spending. The installment becomes unpaid. If the payment did not happen, delete the expense in
Transactions instead." Buttons **Unlink** / Cancel. Snackbar with Undo (relink) is optional; the link action in the
sheet is the recovery path. A warning applies if the installment is later recorded again: "This creates a new
expense." (new `recordGeneration` key; OQ-21 asks whether Unlink should be user-facing at all).

**Audit visibility:** see the Activity section in 3.5.

**States (action flows):** Idle -> Running (spinner, sheet stays) -> Success (sheet closes, snackbar) or Failed
(in-sheet `InlineErrorBanner` with bounded reason, section 4.5). Double-tap guarded by `MutationState.isRunning`.
`AlreadyInState` is silent success. A different current state (`IllegalTransition`): "This installment was already
updated. Refreshing." + auto-refresh.

### 3.7 Flow 7 — Edit, end (Stop / Refund / Pay off early) and delete

Entry points: Plan detail overflow and footer buttons (TalkBack-friendly): **Edit plan**, **End plan…**, **Delete
plan** (only when allowed).

**Which actions exist by status:**

| Plan status | Edit | Stop | Refund / return | Pay off early | Delete |
|---|---|---|---|---|---|
| Active | yes | yes | yes | yes (needs >= 1 scheduled entry) | only if no Paid/Reversed entries |
| Completed | details only | no | yes | no (nothing left) | no |
| Cancelled | no | no | no | no | only if no Paid/Reversed entries |
| Refunded | no | no | no | no | no |
| Paid off | no | no | no | no | no |

**Edit plan (spec 7.4, DD-6 resolved):**
- *Details* (always, Active/Completed): name, lender, category, recording mode. Helper: "Changes apply to payments
  recorded from now on. Recorded expenses are not changed." (E3).
- *Schedule* (Active only, affects SCHEDULED entries only): per-installment Adjust (E1, via row); **Add payments at the
  end / Remove payments from the end** (E2, with "Spread the remaining amount again" for equal-split plans, using the
  same exact-split preview as creation); **Change due day** (E4, applies from the next unrecorded installment).
- *Locked* (shown with lock icon + text): currency after any payment ("Can't be changed after payments are
  recorded." `CURRENCY_LOCKED`), split method after payments ("Stop the plan and create a new one."), paid and
  reversed installments ("Edit the expense instead.").
- Result preview always states the change in Remaining scheduled payments and end date.

**Editing the purchase expense from Transactions (INV-22, AE-20):** while the plan exists the purchase's amount,
currency, transaction type and ownership/shared fields are locked. In the edit form those controls are disabled with the
line "Locked while this purchase is paid in installments." and a **View plan** link; merchant, date, category, notes and
receipt links remain editable. If a stale attempt is refused (`EXPENSE_LOCKED_BY_INSTALLMENT_PLAN`) the form shows
"This purchase is paid in installments, so its amount can't be changed. To change it, delete the plan (if no payment is
recorded) or adjust the plan." Duplicate-merge actions on the purchase are likewise refused with the same message.
Planner note: the edit form lives in the Add-expense/Transactions code under concurrent change; apply the disabled
treatment through the existing field-enabled mechanism only.

**End plan… chooser (OD-3 recommended: three explicit actions):**

```
┌───────────────────────────────────────────┐
│ End "Laptop"                               │
│ ○ Stop the plan                            │
│   Payments already made stay in your       │
│   spending. Future ones are removed.       │
│ ○ The purchase was refunded or returned    │
│   Everything is reversed. This purchase    │
│   and its installments count €0.           │
│ ○ I paid it off early                      │
│   The remaining amount counts on the       │
│   payoff date.                             │
│                       [ Cancel ] [ Next ]   │
└───────────────────────────────────────────┘
```

Each option has its own confirmation (statuses and effects from spec 7.1-7.3). Options not allowed for the plan status
(table above) are hidden.

| Option | Dialog | Content | Confirm | Resulting status |
|---|---|---|---|---|
| Stop | "Stop this plan?" | "N unpaid payments (€X) will be removed. Payments already made (M, €Y) stay in your spending. This purchase stays at €0." If overdue unrecorded entries exist: "2 unrecorded past payments will not count." "This can't be undone." | **Stop plan** (error colour) | Cancelled, "Stopped on <date>" |
| Refund / return | Step 1 question: "Is the refund already recorded as a transaction?" **Yes** -> informational dialog: "Then stop the plan instead. Your payments stay counted and the refund you recorded offsets them." with **Stop the plan**. **No** -> Step 2: "Everything is reversed: M paid installments (€Y) will count €0, N unpaid ones are removed. The expenses are kept, marked 'Not counted (refunded)'. This can't be undone." | **Record refund** (error colour) | Refunded |
| Pay off early | Form: payoff date (not in the future, not before the last paid installment's due date), payoff amount (prefilled with the sum of remaining scheduled payments, including overdue; may be changed for a discount or fee), option "Use an existing charge" (link instead of creating). Body: "The payoff counts in full in <month>. N unpaid installments are removed." Shows the resulting financing cost when computable. "This can't be undone." | **Pay off plan** | Paid off |

Rules: partial refunds are not supported ("For a partial refund, adjust the remaining payments instead." helper text on
the Refund dialog). Refunded and Paid off are terminal. Ended plans move to History automatically. After an end
action, the snackbar offers **View plan** (no Undo).

**OD-3 variant B (owner wants one "Cancel"):** the chooser collapses to a single **Cancel plan** action implemented as
Stop (dialog above); "Refund / return" stays as a separate overflow item. The spec advises against a "cancel
altogether = full purchase counts again" meaning (a sixth state, 7.7).

**Delete plan (undo of the declaration, 7.5, DD-10 resolved):**
- Offered only when the plan has no Paid or Reversed entries (Active, or Cancelled without payments). Otherwise the
  overflow shows no Delete; a tooltip/info row reads "Plans with recorded payments can't be deleted. Use End plan."
  (`PLAN_HAS_PAYMENTS`).
- Dialog "Delete this plan?" Body (purchase-attached): "The plan and its schedule will be removed. Your purchase will
  count as €600.00 again on 10 Mar 2027, so totals and budgets for that month will change." (standalone: "The plan and
  its schedule will be removed.") "This can't be undone." Buttons **Delete plan** (error colour) / Cancel. No expense
  is deleted.
- If recorded payments exist and the user wants to delete anyway, the info row explains the alternative path (delete or
  unlink the installment expenses in Transactions, which reopens the entries).

**Deleting a plan-linked expense from Transactions (spec 7.6):** the existing `DeleteConfirmationDialog` gains one line
depending on the expense role, and offers **View plan**:
- Installment expense: "This expense is part of an installment plan. The installment will become unpaid and will not
  be recorded again automatically."
- Purchase record: "This is the purchase of an installment plan. The plan will remain without a purchase record, and
  financing cost won't be available." (R5)
- Reversed installment expense: "This is a refunded installment. Deleting it only removes it from your list."
These lines are static labels from the expense's flag, so the dialog needs no plan read.

### 3.8 Flow 8 — Plan history (Completed, Cancelled, Refunded, Paid off)

**Purpose:** keep a picture of past loans/installments (B-3).
**Entry:** Overview top-bar **History**.

```
┌───────────────────────────────────────────┐
│ ←  Plan history                            │
│ (All)(Completed)(Cancelled)(Refunded)(Paid off)│
├───────────────────────────────────────────┤
│  Laptop                      Completed     │
│  Apr 2027 – Mar 2028 · 12 of 12 paid       │
│  Total counted  €600.00 · Financing €0.00  │
├───────────────────────────────────────────┤
│  Phone plan                  Cancelled     │
│  Jan 2027 – Jun 2027 · 5 paid, 7 removed   │
│  Total counted  €250.00   (stopped 18 Jun) │
├───────────────────────────────────────────┤
│  Camera                      Refunded      │
│  Total counted  €0.00 · refunded 2 Aug     │
├───────────────────────────────────────────┤
│  Car insurance               Paid off      │
│  Total counted  $480.00 · Financing —      │
└───────────────────────────────────────────┘
```

**Card content (spec 11.1):** name (+ lender), final status chip, start (first entry due date) – end (terminal date, or
last paid due date for Completed), counts of paid / skipped / cancelled / reversed, **Total counted** in the plan
currency (sum of Paid linked expenses + payoff; **€0.00 for Refunded, a legitimate zero shown with the reason**), and
financing cost or "Unavailable" (INV-17). No cross-plan grand total (optional per-currency header: OQ-8). Sort: newest
ended first.

**Read-only detail:** the plan detail screen in the status-banner mode of 3.5. A Completed plan can reopen (3.5
repair states) and then leaves History for the overview.

**Actions in History:** only **Delete plan**, and only for a Cancelled plan with no recorded payments (it then
disappears with no history, spec 11.1). No export in v1 (OD-11).

**States:**

| State | Spec cause | Rendered |
|---|---|---|
| Loading | n/a | Skeleton list |
| Content | plans in terminal statuses | List |
| Empty | none | "No finished plans yet" + "Plans you complete or cancel are kept here so you can look back." |
| Filtered empty | none for filter | "No completed plans" / "No cancelled plans" / "No refunded plans" / "No paid-off plans" |
| Error | storage read failure | `ErrorState` retryable |
| Item with unavailable field | financing cost or total not derivable (INV-17), end date for a Completed plan with no paid entry (11.1) | That field "Unavailable"; card otherwise normal |

No retention footer: plans are never purged (INV-26, 11.2). History does not depend on `transaction_events`.

### 3.9 Flow 9 — How installments appear in existing surfaces

**Principle:** installment expenses are ordinary expenses and count where they are dated (the due date at noon, or the
linked charge's own date). The purchase record counts 0 while the plan exists (INV-1). Future entries are shown only as
commitments (projections). Nothing is shown after the last scheduled entry (INV-15).

| Surface | Representation | Purchase record / reversed expense |
|---|---|---|
| **Transactions list** | Installment expense rows look normal (merchant = plan name, category, amount, payment method) plus chip "Installment 3 of 12" (a UI join, not stored text; tap -> plan detail) and a source badge "Installment plan" (new bounded source). Future/unrecorded installments are **not** rows (Q3). | Purchase record stays at its original date; amount muted (>= 4.5:1 contrast, not struck through) with chip "Paid in 12 installments" and sub-text "Not counted as spending". Excluded from day/period totals in the list header. Reversed installment expenses: "Not counted (refunded)". |
| **Transactions filter** | New toggle "Installments": All / Installment payments / Purchase records. | Purchase records can be hidden with one tap. |
| **Budget** | A month's category spent includes installment expenses dated in that month in the plan's category. Optional info line "Includes €50.00 in installments" (tap -> plan detail/overview). Not-yet-recorded entries never inflate spent. | Purchase is excluded from budget usage (INV-1, AE-24). |
| **Home / upcoming** | Upcoming SCHEDULED entries of ACTIVE plans (due today or later) appear alongside planned/recurring items with subtitle "Installment 4 of 12". The planned-expense dialog is not extended. Final placement depends on the projection design (5.3). | n/a |
| **Bill reminders** | Installments appear as reminder cards: plan name, "Installment 4 of 12", due text, amount with currency; per-currency aware monthly total. Reminders for Skipped/Cancelled entries are suppressed (Q8). Reuse `BillReminderManager`/Worker; no new reminder system. | n/a |
| **Cash flow calendar** | Day marker includes installments; day detail lists "Installments" items: "Laptop · installment 4 of 12 · −€50.00". Stops after the last scheduled entry. | n/a |
| **Forecast / runway** | Future entries are known bounded outflows; installment expenses are not double-projected as recurring patterns (INV-29). No new widget in v1. | n/a |
| **Analytics / savings** | Per-month installment expenses, category inherited (INV-28). No new UI. | Purchase excluded. |
| **Health score / synthesis** | Not wired in v1 (OD-12): no installment content. | n/a |
| **Backup** | Backup screen counts include plans and installment entries as data categories; plan name and lender follow the privacy mode (redacted when expense text is). | n/a |
| **Expense export** | No plan-data export in v1 (OD-11 recommended). Purchase rows are included marked "not counted (installment plan)". Export options UI unchanged. | Marked rows. |

**Spending-rule affordance:** the month-total label never changes; only the plan detail and the Transactions chip
explain the rule. A one-time dismissible tip (persisted locally) on the first declared plan: "Installments count in the
month they are paid, not on the purchase date."

### 3.10 Privacy note (UI)

- No consent dialog or permission is needed for v1; no network, no new permissions; nothing is read from
  notifications, OCR or receipts (spec 11.5).
- Accurate wording for forms: "Plan details stay on this device and in your encrypted backups. Expenses created for
  installments follow your usual privacy settings." The earlier blanket "Stays on this device" is not used because
  installment expenses are ordinary expenses (merchant = plan name, no notes) and follow existing export/cloud policy.
  The planner must verify that plan repositories and the projection port are unreachable from cloud payload assembly
  (INV-31) before this line ships.
- Reminders reuse the existing notification path; if notification permission is denied, the plan detail shows the same
  inline hint Bill reminders uses (no new permission request).
- No "ΔΟΣΗ" text recognition in v1 (increment 2). Nothing in the UI reads or shows notification text.

---

## 4. UI state model

Skeleton: `Loading | Content | Empty | Error(reason) | Unavailable(reason)`; forms add
`Editing | Saving | SaveFailed(reason)`. Reason codes are bounded enums; user text comes only from fixed strings
(section 8). Raw exception messages, SQL, stack traces, notification/OCR/receipt text, paths, merchant text from
captured sources, plan names and lender names are never put in errors, logs or diagnostics.

### 4.1 Overview (`InstallmentOverviewUiState`)

| State | Condition | Spec cause | Rendering |
|---|---|---|---|
| Loading | first load | n/a | Skeleton |
| Content | >= 1 ACTIVE plan | plan status ACTIVE | Summary + cards. Sub-parts: attention items (Overdue, Due today in CONFIRM, deferred candidate, possible duplicate, FX wait) |
| Content / Partial | some per-plan values not derivable | INV-17 (paid amount drift) | That plan's paid-so-far "Unavailable"; remaining scheduled payments still derived |
| Empty (no plans ever) | no ACTIVE, no history | none exist | "No installment plans yet" |
| Empty (all finished) | no ACTIVE, history > 0 | all plans terminal | "No active plans" + History |
| Error: LOAD_FAILED | storage read failed | infrastructure (not a domain state) | ErrorState, Retry |
| Error: REFRESH_FAILED | refresh failed with cached content | infrastructure | InlineErrorBanner over content |
| Mutation: WRITES_BLOCKED | an action while restore/retention barrier is active | 3.1 retry rules, 10, 11.3 | In-sheet banner "Your data is being restored. Try again in a moment." (reads still work; the name of the bounded code is a planner item) |
| Offline | N/A | no network | not rendered |
| Permission denied | N/A | no new permissions | not rendered (reminder notification hint only, 3.10) |

Removed: global `DATA_UNREADABLE` (no spec cause beyond infrastructure read failure), overview-wide `RESTORE_PENDING`
read state (the spec only blocks writes during restore and runs reconcile afterwards), `MIXED_CURRENCY_TOTAL` (a
rendering rule in section 5, not a state).

### 4.2 Plan detail (`InstallmentPlanDetailUiState`)

| State | Condition | Spec cause | Rendering |
|---|---|---|---|
| Loading | open | n/a | Skeleton |
| Content(Active) | status ACTIVE | 3.1 | Full actions |
| Content(Completed) | status COMPLETED | 3.1, INV-21 | Banner; Unlink / Unskip / Refund only |
| Content(Cancelled) | status CANCELLED | Stop, 7.1 | Read-only; Delete if no payments |
| Content(Refunded) | status REFUNDED | 7.2 | Read-only |
| Content(Paid off) | status PAID_OFF | 7.3 | Read-only |
| Error: LOAD_FAILED | read failed | infrastructure | ErrorState Retry |
| Error: NOT_FOUND | plan id missing | plan deleted (7.5) | ErrorState NOT_FOUND, Back |
| Unavailable: FINANCING_COST | no purchase, detached, currency differs, or status Cancelled/Refunded | INV-17, R5, OD-14 | Row "Unavailable" + reason line |
| Unavailable: PAID_AMOUNT | linked expense currency drift before reconcile | INV-17 | Paid-so-far tile "Unavailable" |
| Unavailable: END_DATE | Completed plan with no PAID entry (all skipped) | 11.1 | End date "Unavailable" |
| Entry sub-states | Upcoming, Due today, Overdue(+reason), Paid(+origin, variance), Skipped, Cancelled(+reason), Reversed, Payoff | 3.2 | Section 3.5 table |
| Mutation Running / Success / Failed(reason) | any action | 4.5 | per 3.6/3.7 |

Removed: `UNSUPPORTED_CURRENCY` as a *state* (the spec fixes the plan currency enum to EUR/USD, INV-16; an
unsupported currency is only an input refusal), and a generic `DATA_UNREADABLE`.

### 4.3 Declare on new expense (installment sub-state of `AddExpenseUiState`)

| State | Condition | Spec cause | Rendering |
|---|---|---|---|
| Off | switch off | n/a | Section hidden |
| Editing(valid) | inputs valid | n/a | Preview |
| Editing(invalid) | count, amount, date, too-small | `INVALID_COUNT`, `INVALID_AMOUNT`, `FIRST_DUE_BEFORE_PURCHASE`, `AMOUNT_TOO_SMALL_FOR_COUNT`, `UNSUPPORTED_CURRENCY` | Field errors, Save blocked |
| Unavailable: CURRENCY_UNKNOWN | home currency not loaded | existing Save gate | Switch disabled |
| Blocked | type not spending / not mine / shared / split | `NOT_A_SPENDING_EXPENSE`, `NOT_MINE`, `SHARED_NOT_SUPPORTED`, `SPLIT_NOT_SUPPORTED` (OD-9) | Hidden / disabled + reason |
| Saving | | | Spinner |
| SaveFailed: ATOMIC_FAILED | transaction rolled back | INV-27 | "Couldn't save. Nothing was changed." |
| SaveFailed: DUPLICATE | duplicate purchase detected | TLC duplicate policy, INV-8 | Existing duplicate card (purchase only) |
| SaveFailed: WRITES_BLOCKED | restore/retention barrier | 3.1 | "Your data is being restored. Try again in a moment." |

### 4.4 Declare on existing expense (sheet)

States: Preparing, Ready, Editing(invalid), Saving, SaveFailed, NotFound, Ineligible, AlreadyDeclared.
Reasons (all spec codes): `EXPENSE_NOT_FOUND` (7.0), `NOT_A_SPENDING_EXPENSE`, `NOT_MINE`, `SHARED_NOT_SUPPORTED`,
`SPLIT_NOT_SUPPORTED`, `ALREADY_DECLARED` (INV-3), `LINKED_ELSEWHERE` (INV-5), validation codes of 4.3, atomic failure
(INV-27), `WRITES_BLOCKED`.

### 4.5 Row and plan actions (record / link / skip / unskip / adjust / unlink / edit / stop / refund / pay off / delete)

`Idle | Running(op) | Success(op) | Failed(op, reason)` via `MutationState`.
Bounded reasons mapped to fixed strings:

| Reason | Spec cause | Message key |
|---|---|---|
| ALREADY_RESOLVED (`IllegalTransition`) | stale state, 3.1/3.2 | "This installment was already updated. Refreshing." |
| (AlreadyInState) | idempotent retry | none, success |
| ALREADY_LINKED | INV-4 (planner names the code) | "That expense is already linked to another installment or payment." |
| LINKED_ELSEWHERE | INV-5 | same message |
| CURRENCY_MISMATCH | INV-16 | "That expense is in a different currency than this plan." |
| CURRENCY_LOCKED | 7.4 | "Currency can't be changed after payments are recorded." |
| PLAN_HAS_PAYMENTS | 7.5, 3.1 illegal | "Plans with recorded payments can't be deleted. Use End plan." |
| PLAN_ENDED | INV-20 | "This plan is finished and can't be changed." |
| EXPENSE_LOCKED_BY_INSTALLMENT_PLAN | INV-22 | "This purchase is paid in installments, so its amount can't be changed." |
| INVALID_VALUE | validation | "Check the highlighted fields." |
| DEFERRED (`AUTO_RECORD_DEFERRED_CANDIDATE`) | INV-13 | row sub-reason, not an error. [Amended 2026-10-26 (A2): `_FX` removed] |
| PLAN_NAME_REQUIRED | INV-34 | "Row: name needed" copy (section 8, PROPOSED). [Amended 2026-10-26 (A2)] |
| WRITES_BLOCKED | barrier | "Your data is being restored. Try again in a moment." |
| WRITE_FAILED | storage failure / rollback | "Couldn't save. Nothing was changed." |

### 4.6 History list and read-only detail

History list: `Loading | Content | Empty | FilteredEmpty | Error(LOAD_FAILED)` with per-field "Unavailable" (4.8). The
read-only detail shares 4.2 with status-dependent action sets; mutation states are only entered for Completed plans
(Unlink/Unskip/Refund) and Cancelled-without-payments plans (Delete).

### 4.7 Edit plan and end-plan flows

Edit: `Editing | Saving | SaveFailed(reason) | Locked(field)` with reasons `CURRENCY_LOCKED`, `PLAN_ENDED`,
`INVALID_COUNT`, `AMOUNT_TOO_SMALL_FOR_COUNT`, `WRITE_FAILED`. End-plan: `Chooser | Question(refund recorded?) |
ConfirmStop | ConfirmRefund | PayoffForm | Running | Failed(reason)`. Pay-off input errors: payoff date in the future or
before the last paid due date -> "Choose a date that is not in the future and not before your last payment."; amount
<= 0 -> `INVALID_AMOUNT`.

### 4.8 Spec-to-UI cross-check (reconciled)

**Spec states and invariants -> UI:**

| Spec state / invariant | UI state or rendering |
|---|---|
| Plan ACTIVE / COMPLETED / CANCELLED / REFUNDED / PAID_OFF (3.1) | Plan chips, status banners (3.5), History filters (3.8) |
| COMPLETED -> ACTIVE reopen (3.1, R2) | Plan returns to overview; "Its expense was removed" row reason |
| Delete without payments (3.1, 7.5) | Delete dialog with "counts in full again" line (3.7); `PLAN_HAS_PAYMENTS` -> no Delete shown |
| Entry SCHEDULED + derived Upcoming/Due/Overdue | Rows and "Needs your attention" (3.4, 3.5) |
| Entry PAID (auto/confirm/link origin) | Paid row with origin label |
| Entry SKIPPED / unskip | Skipped row + Undo |
| Entry CANCELLED reasons (`PLAN_STOPPED`, `PLAN_REFUNDED`, `PAID_OFF_EARLY`, `SHORTENED`, `EXPENSE_REMOVED`) | Cancelled row reason labels |
| Entry REVERSED | "Refunded · not counted" row; expense flagged "Not counted (refunded)" |
| PAYOFF entry | Payoff row |
| AUTO_RECORD vs CONFIRM (2.1, INV-12, INV-13) | Mode radio, header label, attention items |
| `AUTO_RECORD_DEFERRED_CANDIDATE` | Suggested-link attention row. [Amended 2026-10-26 (A2): `_FX` and "Waiting for an exchange rate" removed] |
| Plan without a name (INV-34, `PLAN_NAME_REQUIRED`) | Attention row and Record refusal ("Row: name needed", PROPOSED). [Amended 2026-10-26 (A2)] |
| Possible duplicate indicator (Q4) | Overview attention row + detail row note |
| Past-at-declaration entries (8.1, AE-10c) | Inline notice, Overdue rows, bulk Record all past payments |
| `spendingExclusionReason` INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED (2.3, INV-1/2) | Marked rows in Transactions, locked fields (INV-22) |
| Purchase deleted -> plan detached (R5, AE-25) | Detached banner, Financing cost Unavailable |
| Financing cost unavailable (INV-17, OD-14) | Financing cost row states |
| Per-currency totals (INV-16, B-1) | Overview per-currency rows, CURRENCY_MISMATCH |
| Declaration refusals (7.0) | Disabled/hidden affordances and field messages |
| Declaration atomic (INV-27) | "Nothing was changed" failure; Undo = Delete |
| History from plan/entry/expense rows (INV-26, 11.1) | History cards; no retention footer |
| Backup/restore with reconcile (INV-30, 11.3) | Backup counts; `WRITES_BLOCKED` while restoring; repair states after |

**UI states removed because the spec has no cause:** generic `DATA_UNREADABLE`; overview-wide `RESTORE_PENDING`
(replaced by `WRITES_BLOCKED`); `UNSUPPORTED_CURRENCY` as a state; `END_DATE` unavailable in general (kept only for the
Completed-with-no-paid case); `ALREADY_DECLARED` as a free-floating race state (now the spec refusal code);
free-text skip note and "Your note" audit; plan Type chips; history retention footer; "Payments already made"
history-only field; Confirm for Upcoming entries (INV-14); user-facing Undo of Confirm.

**Spec states with deliberately no UI:** `recordGeneration` and `autoRecordConsumed` internals (surfaced only through
row reasons and the "new expense" warning), reconcile R1/R3 flag repair (invisible), `AlreadyInState` (silent success),
INV-29 forecast baseline exclusion, INV-8 dedupe family, reminder dedupe keys, `ExpenseSource` value (only the badge
text), `declaredTotal` (history only), `terminalAt`, diagnostics codes.

**Spec states whose UI depends on an unfinished planner choice:** projection placement on Home/cash flow (5.3), name
of the write-blocked code, ALREADY_LINKED code name, whether reconcile outcomes are announced (currently silent).

---

## 5. Money display

- Every amount is rendered with its currency through `CurrencyFormatter.formatMoney(amount, currency)`; no bare numbers
  (`MoneyDisplayUi` rule). In per-currency summary rows the ISO code is also shown ("EUR  €1,250.00", "USD  $300.00").
- **Mixed currency:** one row per currency present; no "Total" row; no conversion (INV-16, B-1). A plan's amounts stay
  in the plan currency. Next-due compares by date only. A purchase in a different currency than its plan is allowed
  (OD-14 recommended): both currencies are shown, financing cost is "Unavailable", and a USD installment expense
  gets its home-currency value from the existing expense conversion at creation (not a new UI figure).
- **Unavailable conversions:** no conversion exists in v1, so no "approx" markers. [Amended 2026-10-26 (A2)] A
  missing rate never keeps an installment unrecorded. The expense is created as a sentinel row without a
  home-currency value, excluded from home-currency totals, and shown through the shared missing-rate display (plan
  R17, slice S8b), never as a zero amount. No installment-specific copy.
- **Unavailable values:** the word "Unavailable" (info icon with explanation); where space-constrained the em dash is
  allowed only with an "Unavailable" content description (existing `formatBillReminderAmount` convention). Never
  "€0.00".
- **Legitimate zeros:** displayed as "€0.00" with context: financing cost of an interest-free plan (formula line),
  Total counted of a Refunded plan ("Refunded: nothing counted"), purchase record "Not counted" (no amount of 0 is
  shown on it; the original amount stays muted).
- **Partial sums:** known sum + "Partial" chip + warning line; if nothing is known, "Unavailable".
- **Rounding as the user sees it:** all amounts at 2 decimals. The displayed total of installments always equals the
  sum of displayed installment amounts (exact conservation, INV-9). The equal-split residual lands on the first
  installment by default (OD-6) and is labelled "First payment adjusted" (alternative: "Last payment adjusted"); the
  preview states it before saving. Fixed-payment plans have no residual. Rounding mode (OD-7, HALF_UP) is invisible to
  the user: it only normalises typed input with more than two decimals, so the UI sanitises input to 2 decimals as
  today. No other rounding disclaimers.
- **Financing cost:** plain subtraction of two displayed amounts; may be negative and then shows with a minus sign and
  the wording "Less than the purchase price"; no percentage, no "APR". Paid off plans: (paid + payoff) − purchase.
- **Variance:** a Paid installment whose expense amount differs from the scheduled amount shows both ("Paid €52.00 ·
  scheduled €50.00"). The expense governs counting (7.6).
- **Remaining label:** "Remaining scheduled payments" plus the "not your lender's balance" footnote on the overview;
  the detail tile uses the same label. Overdue unrecorded entries are included and labelled "not counted yet".
- **Refunds:** refunded installments are shown as "Refunded · not counted", never as negative amounts and never netted
  silently.

---

## 6. Destructive or irreversible actions

| Action | Confirmation | Undo | Notes |
|---|---|---|---|
| Declare installments on existing expense | Sheet with "What changes" block | Snackbar **Undo** = Delete plan, valid while no payment is recorded | Changes totals of past months; purchase becomes locked |
| Record payment (confirm) | Description line in sheet | **None** (unlink would leave the created expense); "View expense" | Creates a real expense |
| Record all past payments | Dialog with count and total | None; delete expenses individually in Transactions | Creates N expenses in past months |
| Link to expense | Explicit selection | Snackbar Undo (unlink) | Never deletes expenses |
| Unlink expense | Dialog stating the expense stays and still counts | Relink via Link | Expense kept (INV-23) |
| Skip / unskip | Dialog with reason | Snackbar Undo / unskip | |
| Adjust amount/date | Dialog with delta preview | Snackbar Undo (adjust back) | `originalScheduledAmount` kept |
| Stop plan | Dedicated dialog, error-coloured confirm | None; "This can't be undone." | Terminal (INV-20) |
| Refund / return | Question + dialog, error-coloured confirm | None; "This can't be undone." | Terminal; expenses kept, marked not counted |
| Pay off early | Form + dialog | None | Terminal; creates payoff expense or links one |
| Delete plan | Dialog with "counts in full again" line, error-coloured confirm | None | Only without recorded payments; expenses kept |
| Delete plan-linked expense (Transactions) | Existing delete dialog + role line | Existing behaviour | Reopens entry / detaches plan |
| Edit purchase amount of a planned purchase | Not possible (locked) | n/a | Delete the plan (no payments) or adjust the plan |

All confirmations disable the confirm button while running and close only on success.

---

## 7. Accessibility

- **Touch targets:** all buttons, chips, steppers, row overflow >= `Dimens.TouchTargetMin` (48dp). Stepper +/- buttons
  are 48dp each with a numeric text field between (also directly typeable).
- **Content descriptions (examples):**
  - Plan card: merged semantics "Laptop, installment plan, active, 4 of 12 paid, next payment 15 June 2027, 50 euro,
    ends 15 January 2028, remaining 400 euro."
  - Schedule row: "Installment 5 of 12, due 15 July 2027, 50 euro, overdue, not counted yet, button: record payment."
  - Paid row: "Installment 4 of 12, paid 52 euro, scheduled 50 euro, linked expense."
  - Purchase record in Transactions: "Demo Electronics, 600 euro, not counted as spending, paid in 12 installments."
  - Per-currency summary: "Remaining scheduled payments. In euro: 1,250 euro across 3 plans. In US dollars: 300 US
    dollars across 1 plan."
  - Unavailable value: "Unavailable. Double tap for explanation."
  - Progress bar: "4 of 12 installments paid."
  - Icon-only buttons: "Plan history", "Refresh", "More options", "Decrease number of payments", "Increase number of
    payments".
  - Status chip: text + icon (decorative icon `contentDescription = null`; text carries meaning).
- **Not colour alone:** Due today / Overdue / Skipped / Adjusted / Differs from plan use icon + label; error text uses
  error colour plus icon/label.
- **Font scaling:** wrap-content heights; at >= 1.5x the schedule row stacks (number and date first; amount and status
  chip second); summary tiles stack; amounts never truncated; no fixed-height cards. History filter chips scroll
  horizontally.
- **Contrast:** muted purchase-record amount >= 4.5:1 (do not use low-alpha `TextMuted`); use `onSurfaceVariant`-grade
  tone. Error container text uses `onErrorContainer`.
- **TalkBack order:** top bar title -> actions -> summary (per-currency rows, currency first) -> next due -> needs
  attention -> active plans (each card one focus stop with custom actions) -> FAB. Plan detail: header -> tiles ->
  purchase link -> financing cost -> bulk record action -> schedule row by row -> activity. Forms follow visual order; the
  live preview card is a polite live region announced at most once per field blur.
- **Custom actions:** no swipe; every row action is also reachable via the sheet and overflow; cards expose "Record",
  "Skip", "Adjust" where applicable. Locked controls expose the reason as their description ("Locked while this
  purchase is paid in installments").
- **Errors:** field errors use supporting text with `error` semantics; banners use `liveRegion = Polite`; the
  `ErrorState` format `a11y_error_format` is reused.
- **Dialog/sheet focus:** focus moves to the dialog title on open and returns to the invoking control on close.
- **Reduced motion:** the animation in `EmptyTransactionsState` is not reused; empty states are static.
- **Locale:** dates and currency use `Locale.getDefault()`; plurals via resources.

---

## 8. Copy (English; keys to be defined in `strings.xml`)

Greek localisation: the app ships `values-el` and `values-es`; new strings need Greek and, if parity is required,
Spanish (OQ-10). Greek notes: installment = δόση (δόσεις); interest-free = άτοκες δόσεις; loan = δάνειο; mortgage =
στεγαστικό δάνειο; tax plan = ρύθμιση/δόσεις φόρου; "remaining scheduled payments" has no natural single term
("Υπολειπόμενες προγραμματισμένες πληρωμές" is a candidate for native review). Greek text expands ~20-30%, so no
fixed-width chips. Use plurals resources; positional args (`%1$d of %2$d`), never concatenated ordinals.

### 8.1 Labels

| Key (suggested) | English |
|---|---|
| installments_title | Installments & loans |
| installments_feature_desc | Track payments you still owe and when they end |
| installments_remaining_label | Remaining scheduled payments |
| installments_remaining_note | Sum of payments still to be made. Not your lender's outstanding balance. |
| installments_next_due | Next due |
| installments_needs_attention | Needs your attention |
| installments_active_plans | Active plans |
| installments_history | History |
| installments_add_plan | Add plan |
| installments_progress | %1$d of %2$d paid |
| installments_ends | Ends %1$s |
| installments_payments_left | %d payments left (plural) |
| installments_installment_n_of_m | Installment %1$d of %2$d |
| installments_paid_in_n | Paid in %d installments |
| installments_not_counted | Not counted as spending |
| installments_not_counted_refunded | Not counted (refunded) |
| installments_pay_in_installments | Pay in installments |
| installments_set_up | Set up installments |
| installments_number_of_payments | Number of payments |
| installments_split_equal | Equal split of total |
| installments_split_fixed | Fixed payment × N |
| installments_each_payment | Each payment |
| installments_repeats_monthly | Repeats monthly |
| installments_first_payment_on | First payment on |
| installments_mode_title | How should payments be recorded? |
| installments_mode_auto | Record automatically on the due date |
| installments_mode_confirm | I'll confirm each one |
| installments_mode_hint | Choose "I'll confirm" if your bank sends a notification for each installment, so it can be linked. |
| installments_financing_cost | Financing cost |
| installments_financing_cost_formula | Total of installments minus purchase price |
| installments_unavailable | Unavailable |
| installments_first_adjusted | First payment adjusted (or Last payment adjusted per OD-6) |
| installments_estimates_note | Figures are estimates. Your lender's statement is authoritative. |
| entry_status upcoming / due_today / overdue / paid / skipped / cancelled / refunded / payoff | Upcoming / Due today / Overdue / Paid / Skipped / Cancelled / Refunded / Payoff |
| entry_origin auto / confirmed / linked | Recorded automatically / Confirmed / Linked |
| entry_chip adjusted / differs | Adjusted / Differs from plan |
| plan_status active / completed / cancelled / refunded / paid_off | Active / Completed / Cancelled / Refunded / Paid off |
| installments_record_payment | Record payment |
| installments_record_all_past | Record all past payments |
| installments_link_expense | Link to an expense |
| installments_skip / unskip | Skip this installment / Unskip |
| installments_adjust | Adjust amount or date |
| installments_unlink | Unlink expense |
| installments_view_expense | View expense |
| installments_end_plan | End plan… |
| installments_stop_plan | Stop the plan |
| installments_refund_option | The purchase was refunded or returned |
| installments_payoff_option | I paid it off early |
| installments_delete_plan | Delete plan |
| installments_edit_plan | Edit plan |
| installments_activity | Activity |
| installments_history_title | Plan history |
| installments_total_counted | Total counted |
| installments_filter all / completed / cancelled / refunded / paid_off | All / Completed / Cancelled / Refunded / Paid off |
| installments_tip_first | Installments count in the month they are paid, not on the purchase date. |

### 8.2 Messages

| Situation | Message |
|---|---|
| Empty (no plans) | No installment plans yet. Track card installments, pay-later purchases, tax plans and loans. See what you still owe and when each plan ends. |
| Empty (to split a recorded purchase) | To split a purchase you already recorded, open it in Transactions and choose Pay in installments. |
| Empty (no active) | No active plans. Your finished plans are in History. |
| History empty | No finished plans yet. Plans you complete or cancel are kept here so you can look back. |
| Load failed | Couldn't load your plans. |
| Refresh failed (inline) | Couldn't refresh. Showing the last loaded plans. |
| Writes blocked | Your data is being restored. Try again in a moment. |
| Save failed (declare/create/edit) | Couldn't save. Nothing was changed. |
| Already resolved | This installment was already updated. Refreshing. |
| Already linked / linked elsewhere | That expense is already linked to another installment or payment. |
| Currency mismatch | That expense is in a different currency than this plan. |
| Currency locked | Currency can't be changed after payments are recorded. |
| Plan ended | This plan is finished and can't be changed. |
| Plan has payments | Plans with recorded payments can't be deleted. Use End plan. |
| Purchase locked | This purchase is paid in installments, so its amount can't be changed. |
| Locked control hint | Locked while this purchase is paid in installments. |
| Validation: count | Enter a number from 2 to 480. |
| Validation: amount | Enter an amount greater than 0. |
| Validation: too small | The amount is too small for this many payments. |
| Validation: first date | Choose a date on or after the purchase date. |
| Validation: purchase amount | Enter the purchase amount first. |
| Validation: payoff date | Choose a date that is not in the future and not before your last payment. |
| Mutual exclusion | Repeat and installments can't be combined. |
| Not mine | Installments aren't available for expenses that aren't yours. |
| Shared / split | Installments aren't available for shared or split expenses yet. |
| Linked to recurring | This expense is already part of a recurring payment. |
| Past-due notice | %d installments are already past due. They won't count until you record or link them. |
| Declare impact | This purchase stays in your list and its receipt link is kept. It stops counting as %1$s on %2$s. Each installment counts in the month it is paid. Totals and budgets for those months will change. |
| Month-end note | Payments due on the 31st fall on the last day of shorter months. |
| Skip dialog | Skip installment %d? It won't be counted and no payment is added at the end of the plan. You can undo this. |
| Record dialog | Creates an expense of %1$s dated %2$s and marks this installment paid. |
| Record all dialog | Creates %1$d expenses totalling %2$s, each dated on its due date. Totals for those months will change. If you already have these charges, link them instead. |
| Unlink dialog | Unlink this expense from installment %d? The expense stays in your list and still counts as spending. The installment becomes unpaid. If the payment did not happen, delete the expense in Transactions instead. Recording this installment again later creates a new expense. (last sentence: PE-D, owner-approvals 68) |
| Stop dialog | Stop this plan? %1$d unpaid payments (%2$s) will be removed. Payments already made stay in your spending. This purchase stays at 0. This can't be undone. |
| Stop (overdue) | %d unrecorded past payments will not count. |
| Refund question | Is the refund already recorded as a transaction? |
| Refund yes | Then stop the plan instead. Your payments stay counted and the refund you recorded offsets them. |
| Refund dialog | Everything is reversed: %1$d paid installments (%2$s) will count 0 and the unpaid ones are removed. The expenses are kept and marked not counted. This can't be undone. |
| Refund partial hint | For a partial refund, adjust the remaining payments instead. |
| Payoff dialog | The payoff counts in full in %1$s. %2$d unpaid installments are removed. This can't be undone. |
| Delete dialog (purchase) | Delete this plan? The plan and its schedule will be removed. Your purchase will count as %1$s again on %2$s, so totals and budgets for that month will change. This can't be undone. |
| Delete dialog (standalone) | Delete this plan? The plan and its schedule will be removed. This can't be undone. |
| Delete linked expense: installment | This expense is part of an installment plan. The installment will become unpaid and will not be recorded again automatically. |
| Delete linked expense: purchase | This is the purchase of an installment plan. The plan will remain without a purchase record, and financing cost won't be available. |
| Delete linked expense: refunded | This is a refunded installment. Deleting it only removes it from your list. |
| Row: expense removed | Its expense was removed |
| Row: before plan | Before you set up this plan |
| Row: candidate found | A matching charge was found |
| Row: name needed | Name this plan to record its payments. **PROPOSED [Amended 2026-10-26 (A2)]: new string for INV-34, needs owner es/el sign-off; used by S10 (attention row, row sub-reason, Record refusal).** ("Row: FX wait" removed [Amended 2026-10-26 (A2)].) |
| Possible duplicate | Possible duplicate of installment %1$d: a %2$s charge on %3$s |
| Detached banner | The original purchase was deleted. This plan is now standalone. |
| Snackbar declared | Installments set up for %s (actions: View plan, Undo) |
| Snackbar recorded | Payment recorded (action: View expense) |
| Snackbar skipped / adjusted / linked | Installment skipped / updated / linked (action: Undo) |
| Declare Undo failed | A payment was already recorded. Use End plan to change this plan. |
| Completed banner | All payments are accounted for. |
| Cancelled banner | This plan was stopped on %s. Payments already made still count. |
| Refunded banner | This plan was refunded. Its payments no longer count. |
| Paid-off banner | Paid off early on %s. |
| Privacy line | Plan details stay on this device and in your encrypted backups. Expenses created for installments follow your usual privacy settings. |

### 8.3 Data-handling rules for copy and diagnostics

- Messages above are fixed strings; the only dynamic parts are numbers, dates, currency amounts, and the plan or
  merchant name the user already sees. Raw OCR, notification, receipt, statement text, file paths, SQL messages,
  exception messages and payloads are never inserted into errors, snackbars, logs or diagnostics.
- Failures log only the bounded reason code and integer counts (INV-31); never plan name, lender, amount or dates.
- The spec stores no user note on entries, so no user-entered free text other than plan name and lender exists in this
  feature (OQ-20 covers skip reasons).

---

## 9. Observable-state coverage summary

| Flow | Loading | Content | Empty | Error | Unavailable | Offline | Consent/permission |
|---|---|---|---|---|---|---|---|
| 1 Declare on new expense | gate on home currency | preview | n/a (form) | spec refusals; SaveFailed atomic / DUPLICATE / WRITES_BLOCKED | amount empty, currency unknown | N/A | N/A |
| 2 Declare on existing | sheet skeleton | form | n/a | NOT_FOUND, ineligible reasons, ALREADY_DECLARED, SaveFailed | n/a | N/A | N/A |
| 3 Create standalone | n/a | form + preview | n/a | validation, SaveFailed | preview placeholder until valid | N/A | N/A (privacy line) |
| 4 Overview | skeleton | cards, per-currency rows, attention items | none ever / none active | LOAD_FAILED / REFRESH_FAILED | partial paid-so-far | N/A | reminders use existing permission |
| 5 Plan detail | skeleton | schedule, five plan statuses, repair states | n/a | LOAD_FAILED / NOT_FOUND | financing cost, paid amount, end date (narrow) | N/A | N/A |
| 6 Record/link/skip/adjust | running | success snackbar | picker empty | Failed(reason) | FX wait (row) | N/A | N/A |
| 7 Edit/End/Delete | running | dialogs | n/a | Failed(reason), locked fields | n/a | N/A | N/A |
| 8 History | skeleton | list | no finished / filtered empty | LOAD_FAILED | per-field "Unavailable" | N/A | N/A |
| 9 Existing surfaces | existing | chips / marked rows | existing | existing | existing "—" convention | N/A | N/A |

Offline: nothing in this feature uses the network, so no offline state is rendered. Documented as N/A on purpose so QA
does not look for it.

---

## 10. Open questions for the human

**Status key:** CLOSED = answered by `domain-spec.md`; OD-n = owner decision already listed in the spec section 14
(answer it there); OWNER = needs an owner/product answer that is not in the spec's list; PLANNER = for the planner or
domain-modeler.

### Closed or re-mapped from the first draft

| Original | Status | Answer / mapping |
|---|---|---|
| OQ-1 Which end actions in v1 | OD-3 (owner) | Spec recommends Stop / Refund-Return / Pay off early + Delete only without payments. Variant B: single "Cancel" = Stop |
| OQ-2 Do unconfirmed past installments count | CLOSED | No virtual counting (6 Q3). They count once recorded or linked. Default mode is OD-4 |
| OQ-3 Monthly-only or all frequencies | OD-8 (owner) | Recommended monthly only; UI shows a static "Monthly" |
| OQ-4 Default first-payment date | CLOSED (partly) | Spec sets only the lower bound (`FIRST_DUE_BEFORE_PURCHASE`); UX default one month after the purchase date stays a UX choice |
| OQ-5 Is the plan Type stored | CLOSED | No: the plan has no type field. Type chips removed |
| OQ-6 Negative financing cost | CLOSED | Shown with sign (spec 9, AE-9). See OQ-24 for a related wording check |
| OQ-8 Per-currency header in History | OWNER (minor) | Spec silent; UI default omits it |
| OQ-9 Linked expense deleted elsewhere | CLOSED | Entry reopens (or Cancelled · Expense removed in terminal plans); Completed plan reopens (7.6, R2) |
| OQ-10 Spanish parity | OWNER | Unchanged |
| OQ-11 Home weather card link | OWNER (minor) | Unchanged |
| OQ-12 Plan length / amount bounds | CLOSED | N in 2..480; amounts positive and below 10^9 (6 Q6) |
| OQ-13 Warn about per-installment notifications | CLOSED | Replaced by the recording-mode question/hint (OD-5) |
| OQ-15 Attach a standalone plan to a purchase later | CLOSED | No: purchase-attached plans can only detach (2.1 `kind`). Revisit only in a later increment |

### Kept open and visible

- **OQ-7 (OWNER, keep visible):** Add expense has no currency picker. USD new-expense plans need either a currency
  field in Add expense (a change to an existing screen under concurrent Release-1.0 edits) or are only possible via
  standalone plans / existing USD expenses. Related to OD-14. Which?
- **OQ-14 (OWNER, keep visible):** no app-wide "hide amounts"/privacy masking mode was found in `ui/`. If one exists or is
  planned, plan screens must honour it. Confirm none is intended.
- **OQ-16 (OWNER):** there is no expense detail screen; confirm that entry via the Transactions overflow menu and the
  plan chip is acceptable, rather than introducing a detail screen.
- **OQ-17 (OWNER, minor):** confirm no teaser for lent/borrowed money in the overview (deferred by B-4).

### New questions from reconciliation

- **OQ-18 (OWNER, DD-15 gap):** payments made before using the app. The spec has no history-only payment. Options:
  (a) the user enters only the remaining payments starting from the next one (current UI guidance); (b) allow a
  "payments already made" history-only count that does not create expenses (needs a domain change); (c) past entries
  become Overdue and are bulk-recorded (creates expenses in past months, which changes historic totals). Which?
- **OQ-19 (OWNER/PLANNER, DD-14 gap):** default category for a standalone plan (e.g. "Loans & debt"?). Spec leaves
  `categoryId` nullable with no default.
- **OQ-20 (PLANNER/OWNER):** the bounded set of skip reasons (spec names only `USER_SKIP_LENDER_HOLIDAY`) and whether any
  free-text note is allowed. The UI currently allows no free text.
- **OQ-21 (PLANNER):** is user-facing Unlink safe? Unlinking an auto-recorded or confirmed installment leaves its
  expense counting, and a later Record creates a second expense (new generation key). The current copy warns; consider
  limiting Unlink to captured/linked entries, or offering "Delete expense and reopen" as one action through the
  standard expense-delete path.
- **OQ-22 (OWNER):** auto-recorded expenses appear in Transactions without user action. Is a visible cue sufficient
  (source badge "Installment plan" + chip), or should a notification/summary ("3 installments recorded") exist? No new
  permission or reminder system is assumed.
- **OQ-23 (OWNER, OD-13):** Activity is limited to the latest change per entry because the spec has no event table. Is
  that enough, or should OD-13's alternative (bounded-code event table with retention) be approved to get a full log?
- **OQ-24 (PLANNER/domain-modeler):** spec 9 defines financing cost over "non-cancelled" entries, which includes
  Skipped ones even though skipped entries count 0 in spending. Confirm intended behaviour or exclude Skipped.
- **OQ-25 (PLANNER):** Home upcoming-item placement of installments depends on the projection design (5.3,
  `RecurringPlanProjectionService` item type vs sibling projection). The UI request is only "show with subtitle
  Installment n of N".

### Still-pending OD items this UX stays neutral on

OD-1 (separate coordinator; Recurring placement flips if rejected), OD-3 (end-plan shape), OD-4 (default mode),
OD-5 (captured-charge linking), OD-6 (residual first/last label), OD-8 (frequencies), OD-9 (shared/split purchases),
OD-10 (month of a linked charge), OD-11 (export marking), OD-12 (health score/synthesis not wired), OD-13 (event
table), OD-14 (plan currency vs purchase currency), OD-15 (conditional fallback, no UI difference).

## Clarification 2026-10-27 — owner decisions (owner-approvals item 51c)

Append-only. Nothing above this heading is rewritten. For the entries named below, this section wins over the text
above.

**Source.** The owner replied "I approve" to the orchestrator's recommendations on 2026-10-27 (owner-approvals item
51c; also item 49c (OQ-21); OD-14, F4 and F5 were raised in the S12a reviews (handoff :2171-2198);
`build/release-lead-notes/owner-approvals-20261022.md` in the main checkout). Recorded in
`workflows/active/loan-installments-handoff.md`, "2026-10-27 OWNER DECISIONS" (:2229-2237). Four of the six 51c(c)
decisions are spec/UX decisions. This file covers OQ-21, F4 and F5. The fourth of these, OD-14, is a domain rule and is recorded in `domain-spec.md` (same heading); its UX
consequence is cross-referenced in U-4.

**Impact.** No schema, DAO, guard or allowlist change. No new or removed string. The es/el strings stay DRAFTS and the
native sign-off is still open.

### U-1. OQ-21 DROPPED: there is no "Delete expense and reopen" action

**Decision.** The action is not built. The app has no expense-delete use case (the only delete is
`ExpenseRepository.deleteExpense`, :583 and :603, which goes to the transaction lifecycle coordinator), and the INV-23
guard forbids any `deleteExpense(` call in installment code, UI included
(`InstallmentPlanArchitectureGuardTest.kt:211`, registered at :353, self-checked at :719-745). Wiring it would have
needed a guard change or a new wrapper outside the installment packages (handoff :2015).

**Entries superseded or narrowed**

| Entry | Where | Original | Now |
|---|---|---|---|
| OQ-21 | section 10, :1373-1376 | PLANNER question: "is user-facing Unlink safe? ... consider limiting Unlink ..., or offering 'Delete expense and reopen' as one action through the standard expense-delete path." | CLOSED. The delete half is dropped. The Unlink half is answered by U-1 rule 4 (record: owner-approvals item 56, orchestrator, standing approval; owner FYI). |
| Unlink expense (Paid rows) | section 3.6, :726-730 | The dialog paragraph ends "OQ-21 asks whether Unlink should be user-facing at all". | Closed: Unlink is user-facing. The dialog text is NOT unchanged: owner-approvals item 68 (PE-D) appends the sentence "Recording this installment again later creates a new expense." to `installments_unlink_body` (EN; es/el DRAFT), so the "creates a new expense" warning is now in the dialog itself. |
| OQ-21 resolution and S10b-2 goal | `loan-installments-plan.md` :543 and :446 (cross-file) | "AUTO_RECORDED and USER_CONFIRMED entries get 'Delete expense and reopen' via the standard expense-delete use case." | The "Delete expense and reopen" half is dropped. The "Unlink only for USER_LINKED" half is not carried by the decision (see rule 4). |

No wireframe, copy key or state in this file ever carried "Delete expense and reopen". The action existed only in the
plan and handoff text, so no string is removed.

**Rule (precise).**
1. No installment screen deletes an expense (INV-23 is unchanged).
2. To drop the expense of a recorded installment, the user either (a) unlinks it from the plan detail and then deletes
   it in Transactions, or (b) deletes it directly in Transactions. The Unlink dialog already says "If the payment did
   not happen, delete the expense in Transactions instead" (`values/strings.xml:2921`, copy table :1269). The
   Delete-plan info row (:817-818) already names both routes.
3. Where the entry reopens:
   - Unlink: `unlinkExpense` moves a PAID entry to SCHEDULED with `recordGeneration` + 1 and leaves the expense
     untouched (`InstallmentPlanLifecycleCoordinator.kt:379-399`). Only a REGULAR PAID entry of an ACTIVE or COMPLETED
     plan can be unlinked (`InstallmentPlanTransitions.kt:199`, :360-368).
   - Direct delete of a still-linked expense: the transaction lifecycle coordinator deletes the row and then plans the
     post-commit batch (`TransactionLifecycleCoordinator.kt:3077-3082`, best effort). The batch includes
     `installment_expense_deleted` (`TransactionSideEffectPlanner.kt:157-169`, :504-515), which calls
     `InstallmentPlanLifecycleCoordinator.onExpenseDeleted` (:1069) and reconcile R2. The entry reopens (ACTIVE or
     COMPLETED plan, `recordGeneration` + 1, `autoRecordConsumed` unchanged), or becomes CANCELLED(`EXPENSE_REMOVED`)
     in a CANCELLED or PAID_OFF plan (`InstallmentPlanTransitions.kt:564-583`; domain-spec section 7.6). If the
     post-commit reaction fails, the reconcile run repairs it (domain-spec :524).
   - Unlink first, then delete: the entry is already open after the unlink. The later delete finds no linked entry, so
     the reaction does nothing (`reconcileForExpense`, :1100-1109).
   So the owner's sentence "unlink, then delete; that already reopens the entry through the post-commit reaction" is
   exact for the direct-delete route. In the two-step route the reopen happens at the unlink step.
4. Unlink scope as implemented (not changed by 51c): it is NOT restricted by link origin. The coordinator allows it
   for any PAID REGULAR entry of an ACTIVE or COMPLETED plan, whether the entry was auto-recorded, confirmed or linked,
   and the read model carries no link origin (handoff :2018, :2177). The owner's route "unlink, then delete" only
   works for an auto-recorded or confirmed entry if Unlink is unrestricted, so this clarification records
   unrestricted Unlink as the rule. Record: owner-approvals item 56 (orchestrator, standing approval; owner FYI).
   The plan's earlier "Unlink only for USER_LINKED" would be a new decision.
5. Known risk, unchanged: unlinking without deleting leaves the expense counting while the entry is open, and a later
   Record creates a second expense (new generation key). The consequence-first dialog is the only mitigation.

**Pre-enable items (not part of 51c).**
- The Unlink row entry point: BUILT by PE-B (2026-10-28). `canUnlink` on the detail row and the entry menu item
  call `openUnlink` (`InstallmentPlanActions.kt`), so route (a) above has a UI trigger. (Before PE-B, `openUnlink`
  had no caller.)
- The role-dependent lines in the Transactions delete dialog (:820-826): BUILT by PE-D (2026-10-28, implemented, not
  yet validated). `DeleteConfirmationDialog` (`TransactionsScreen.kt`) shows one of three role lines from the row
  marks (the stored flag is the fallback) plus a neutral **View plan** button, only when the installments view
  model exists (dormant while the flag is false). With OQ-21 dropped, these lines are what tells the user that
  deleting an installment expense makes the installment unpaid.

### U-2. F4 accepted: the Transactions installments filter is a single toggle

**Decision.** The filter is one toggle, not the three-way choice.

**Entries superseded or narrowed**

| Entry | Where | Original | Now |
|---|---|---|---|
| Transactions filter row | section 3.9 table, :889 | "New toggle 'Installments': All / Installment payments / Purchase records." and the note "Purchase records can be hidden with one tap." | One toggle "Installments". The three-way choice and the "hide purchase records" claim are superseded. |

No other section describes the three options, and no copy key existed for them (the section 8 copy table has none), so
no string is removed.

**Rule (precise).**
1. Control: one `FilterChip` labelled "Installments" (`transactions_installment_filter`, `values/strings.xml:2847`;
   es "Cuotas" :656 and el "Δόσεις" :405 are DRAFTS). It sits above the top bar (`TransactionsScreen.kt:298-300`;
   `TransactionsInstallmentUi.kt:107-117`) and exists only while `InstallmentsFeature.ENABLED`.
2. OFF (default) is "All": the list is unchanged. ON keeps only installment-related rows in one list: purchase
   records (chips "Paid in N installments" and "Not counted as spending") together with installment payment expenses
   (entries that are PAID or REVERSED, REGULAR or PAYOFF). Date groups left empty are dropped
   (`TransactionsInstallmentViewModel.kt:127-149` builds the marks, :293-299 `filterGrouped`; test
   `TransactionsViewModelInstallmentTest.kt:194-210`).
3. Not available: payments only, purchase records only, hiding purchase records. The 3.9 rationale "purchase records
   can be hidden with one tap" no longer holds. Purchase records stay recognisable by their chips.
4. Scope and state: session state in `TransactionsInstallmentViewModel` (`_filterOn`, :263-264; `toggleFilter`,
   :284-287). It is not persisted and is forced off when the feature is disabled (:268-273). The filter is applied to
   the loaded, grouped list (`TransactionsScreen.kt:150-156`). While it is ON the screen keeps paging to the end
   (`TransactionsLoadMorePolicy`, :236-248; screen :266-291), so the filtered list is complete only after paging ends.
5. No filtered-empty copy was specified or added. The pre-enable QA items for paging and the retry window stay open
   (handoff :2205, :2209-2213).

### U-3. F5 accepted: the locked-expense message has one sentence

**Decision.** The message for a refused edit of a plan purchase is the shortened form now in the strings.

**Entries superseded or narrowed**

| Entry | Where | Original | Now |
|---|---|---|---|
| Editing the purchase expense from Transactions | section 3.7, :765-770 | The refused-attempt text adds a second sentence: "To change it, delete the plan (if no payment is recorded) or adjust the plan." | One sentence only. The second sentence is superseded. |

The error-code table (:1002) and the copy table (:1251, "Purchase locked") already show the short form, so :768-770 was
the only differing text.

**Rule (precise).**
1. Exact text (en): "This purchase is paid in installments, so its amount can't be changed." Key
   `transactions_installment_purchase_locked` (`values/strings.xml:2848`). The es (:657) and el (:406) strings are
   DRAFT translations of the same short form; native sign-off is still open.
2. It is a fixed resource message. It carries no data, no exception text and no instruction to delete or adjust the
   plan. It is shown through `_error` from `TransactionsViewModel.updateExpenseType` (:757-760) and `updateOwnership`
   (:866-869), and the log line is the constant `TRANSACTION_UPDATE_BLOCKED code=EXPENSE_LOCKED_BY_INSTALLMENT_PLAN`.
   Tests: `TransactionsViewModelTest.kt:205`, :224, :243.
3. Not part of this decision and not implemented: the disabled controls, the line "Locked while this purchase is
   paid in installments." and the "View plan" link in the edit form (:765-768). There is no such string, and
   `InstallmentLockedExpenseException` is mapped only in `TransactionsViewModel` (:757, :866).

### U-4. Cross-reference for OD-14 (the UX side of the domain-spec clarification)

- :224-227 (section 3.1, currency note): superseded. The Add-expense plan-currency row is removed by G2-6, and an
  amount to split is never typed when currencies differ (`FIXED_PAYMENT` only).
- :1073-1075 (section 5, mixed currency): "A purchase in a different currency than its plan is allowed" now reads
  "allowed as a fixed-payment plan". Financing cost stays "Unavailable".
- Declare sheet as implemented: when the currencies differ, "Equal split of total" is disabled, "Fixed payment × N" is
  selected, and the note `declare_installments_currency_differs` (`values/strings.xml:2853`) is shown
  (`DeclareInstallmentsSheet.kt:512-528`).

### Inconsistencies found while recording these decisions

- F5 wording versus where it is shown. The accepted text names only the amount, but INV-22 also locks currency,
  transaction type and ownership (domain-spec :382-385). The only two places that show the message are the type-change
  and ownership-change paths, so a user changing the type or ownership reads "its amount can't be changed". The handoff
  note (:2198) says the message "states the type and ownership lock but not the amount", which does not match the
  string. The wording is accepted as is. A field-neutral wording would be a new decision.
- F4 versus its original purpose. The single toggle cannot hide purchase records (U-2 rule 3).
- OQ-21 rationale versus mechanics. The reopen after "unlink, then delete" comes from the unlink, not from the post-commit
  reaction (U-1 rule 3).

## Clarification 2026-10-28 — F5 wording (owner-approvals 59d)

Supersedes the short text in U-3 (and the "Inconsistencies" note "F5 wording versus where it is shown"): the owner
approved a field-neutral wording, because the message is shown only when the user changes the transaction TYPE or the
OWNERSHIP of a plan purchase (`TransactionsViewModel.updateExpenseType`, `updateOwnership`), never an amount edit.

1. New text (en), key `transactions_installment_purchase_locked` (unchanged): "This purchase is paid in installments,
   so this can't be changed here."
2. es: "Esta compra se paga a plazos, así que esto no se puede cambiar aquí." el: "Αυτή η αγορά πληρώνεται σε δόσεις,
   οπότε αυτό δεν μπορεί να αλλάξει εδώ." Both are DRAFT (`<!-- DRAFT: owner native sign-off pending -->` in the
   resource files); native sign-off is still open.
3. Unchanged from U-3: one fixed resource message, no data or exception text, no instruction to delete or adjust the
   plan, shown through `_error` from the type-change and ownership-change paths. The key, the call sites and the tests
   (which assert the resource id) are not touched. The older copies of the amount-specific sentence (the error-code
   table, the copy table "Purchase locked", section 3.7) are superseded by this text.
