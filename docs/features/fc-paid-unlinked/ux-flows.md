# FC-PAID-UNLINKED: UX flows

Status: DESIGN ONLY. Inputs: `domain-spec.md`, `decisions.md` (D-1: suggestions on the Recurring bill row, plus one Home hint).
Source read: mainline e4376a77 (`Cost-agregator-c34`). Paths below are under `app/src/main/java/com/yourname/expensetracker/`.
Scope: one affordance on an existing row, one hint on Home. No new screen, no new destination, no new permission, no network.

## 0. Findings that shape the design

1. **The "bill row" with Mark as Paid is `RecurringExpenseCard` in `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt`** (title "Recurring Expenses", destination `NavigationDestination.ManualRecurringExpense`). It is NOT `ui/screens/recurring/RecurringExpensesScreen.kt` ("Manage Upcoming"), which lists detected patterns and planned items and has no occurrence, no Mark paid and no Already recorded. Suggestions therefore go on the Manual screen's card.
2. **Home's existing `onNavigateToRecurring` goes to `NavigationDestination.RecurringExpenses`** (`MainActivity.kt:664`), the wrong screen for this hint. The hint needs a second callback that navigates to the existing `ManualRecurringExpense` destination. That destination already exists and is reachable from `FeatureConfig`; back returns to Home. No new destination.
3. The Manual screen already has the transient-message pattern this design reuses: `actionMessage: UiText?` shown as a snackbar, `inFlightRuleIds` disabling the row buttons, and a full-screen `ErrorState` only for load failures. The write-barrier message `ui_write_blocked_maintenance` already exists in en, es and el.
4. Assumption to verify in S3: `ManualRecurringExpense.id` is the rule id that `PaymentLinkSuggestion.ruleId` and `RecurringOccurrence.sourceId` refer to. If not, the VM needs a rule-id mapping.

## 1. Entry points and navigation

| Entry | Path | Back |
|---|---|---|
| Recurring bill row | Features menu -> Recurring Expenses -> the rule's card. The suggestion block appears inside the card, between the amount/due line and "Mark as Paid". | Unchanged (system back or toolbar back to the previous screen). |
| Home hint | Home -> hint card (tap anywhere on the card, or the "Review" text button) -> Recurring Expenses (`ManualRecurringExpense`). | Back returns to Home. Home recomputes the hint on resume, so it reflects any Link or Not this bill done meanwhile. |

No deep link. No notification. No new route.

## 2. Screens

### 2.1 Recurring Expenses screen (existing), card with suggestion

Purpose: let the user say "this expense already paid this bill" so the bill stops being counted twice. Key content per suggestion: the paid amount with currency, the payment date, the due date of the bill it matches. Primary action: Link. Secondary: Not this bill. The existing Mark as Paid button stays.

```
+-----------------------------------------------+
| Rent                                  [Switch]|
| Created from manual entry                     |
|                                               |
| 400,00 EUR Monthly               Due Nov 27   |
| +-------------------------------------------+ |
| | Paid already? 400,00 EUR on Nov 25        | |
| | Due Nov 27                                | |
| | [ Link ]            [ Not this bill ]     | |
| +-------------------------------------------+ |
| [ v Mark as Paid                            ] |
+-----------------------------------------------+
```

Behaviour:

- The block is a tonal sub-surface (same `PrimaryIndigo` at 0.15 alpha as `TotalMonthlyCard`). Link is an `OutlinedButton`, Not this bill a `TextButton`, both at least 48dp high. At font scale 1.3 or more, or when a label wraps, the two buttons stack vertically, full width.
- A rule can have several open occurrences in the 31-day lookback (for example a weekly rule). One block per suggestion, stacked, ordered by due date. The second line of each block ("Due ...") names which bill it is. Nothing else is capped.
- The line 2 date is the occurrence due date from the suggestion, not the rule's `nextDate`, which can differ.
- While a call for this rule is in flight (`inFlightRuleIds`), Link, Not this bill and Mark as Paid are all disabled. This mirrors the per-rule mutex.
- "Different bill" picker from the D-1 proposal is NOT offered (see section 10, Q1). After "Not this bill" the next-nearest bill, if any, is suggested on the next recompute.
- Inactive rules and IRREGULAR rules (no Mark paid) never show a block. This matches the domain rules (paused rule is never a target, S(P) = 0 for irregular).

Unavailable line (only when suggestions are `Unavailable`): one muted `bodySmall` line, `TextSecondary`, as the first list item under the summary cards.

```
 Payment suggestions are unavailable right now.
```

### 2.2 Home hint (existing Home top column)

Placed in the same full-width top `Column` as `NotificationCaptureStatusCard`, below it. Style matches that card's non-error variant: `surfaceVariant` at 0.4 alpha, `titleSmall`/`bodySmall`, 12dp by 8dp padding. Shown only when the count is 1 or more.

```
+-----------------------------------------------+
| 2 bills may already be paid.         [Review] |
+-----------------------------------------------+
```

- Whole card is clickable (min height 48dp); the "Review" `TextButton` is the same target, not a second one in TalkBack order.
- No amounts, no merchant names on Home (so no currency questions), and no forecast numbers change when it is shown or tapped. The forecast changes only after Link on the Recurring screen.
- No dismiss. It disappears when the count reaches zero. Decline is the only way to clear a wrong suggestion.

## 3. UI state model

### 3.1 Recurring Expenses screen

New sealed state `PaymentLinkSuggestionsUiState` (held alongside the existing `ManualRecurringExpenseUiState`; it is independent of the rule list, so a suggestion failure never blanks the list):

| State | Renders | Produced by |
|---|---|---|
| `Loading` | Nothing (no spinner, no placeholder). The list and the other cards are fully usable. A refresh keeps the previous `Content` until the new result arrives (no flicker). | Initial call, refresh toolbar action, resume. |
| `Content(byRuleId: Map<Long, List<SuggestionUi>>)` | Suggestion blocks on the matching cards. | `PaymentLinkSuggestions.Available(items non-empty)` (INV-8, INV-9). |
| `None` | Nothing. No "no suggestions" text. | `Available([])`: no rules, no expenses, no pairs, all declined, SKIPPED/PAID neighbour blocks (INV-6), unmaterialized, future-dated (AE-24, AE-25, AE-26, AE-27). |
| `Unavailable` | The muted unavailable line. Never `None`, never a zero. | `Unavailable(READ_BLOCKED)` (restore running or read barrier, INV-11, AE-18) and `Unavailable(READ_FAILED)` (any other read error). Both map to the same line; the reason code is not shown. Retry = the existing toolbar Refresh action. |

`SuggestionUi` holds only what is rendered: `occurrenceId`, `ruleId`, `expenseId`, formatted paid amount (from amount plus currency), payment date, due date. If the expense cannot be rendered truthfully at render time, the item is dropped and the next normal recompute applies (no automatic recompute, to avoid a loop); the Home hint counts only renderable items. If the rule is missing, the item is dropped and a recompute is requested. A blank or zero amount is never shown.

Action results (transient, via the existing `actionMessage` snackbar plus `inFlightRuleIds`; not a sealed screen state):

| Domain result | User sees | Then |
|---|---|---|
| `Linked` | Snackbar `recurring_link_done_format` | Block disappears; recompute; the card's due date moves forward if the advance succeeded (INV-13). |
| `AlreadyLinkedToThis` | Same as `Linked` (it is a success; retry after a lost response) | Same. |
| `Declined` | Snackbar `recurring_link_declined` | Block disappears; recompute. |
| `AlreadyDeclined` | Same as `Declined` | Same. |
| `NoLongerValid` | Snackbar `recurring_link_no_longer_valid` | Recompute (stale UI: expense deleted, bill paid by Mark as Paid, rule paused; AE-14, AE-20, AE-27). |
| `ExpenseLinkedElsewhere` | Same snackbar as `NoLongerValid` (the user need not distinguish the cause) | Recompute. |
| `Failed(WRITE_BLOCKED)` | Existing `ui_write_blocked_maintenance` ("Changes are paused while a backup or restore is running...") | Nothing written (INV-16, AE-18). Block stays so the user can retry. |
| `Failed(INTERNAL)` | Existing `recurring_error_generic` | Block stays; retry allowed. |

Reads are blocked and writes are blocked by the same restore, so during a restore the user normally sees the unavailable line and no buttons. The `WRITE_BLOCKED` path covers a restore that starts after the block rendered.

### 3.2 Home

New sealed state `PaymentLinkHintUiState`:

| State | Renders | Produced by |
|---|---|---|
| `Loading` | Nothing | First call. Keep the previous value while recomputing. |
| `Ready(count)` (count >= 1) | The hint card | `Available(items)`; count = number of suggestions, not rules. |
| `None` | Nothing | `Available([])`. |
| `Unavailable` | Nothing. Home never shows "0 bills" or an error for this hint. | `Unavailable(READ_BLOCKED | READ_FAILED)`. The Recurring screen shows the unavailable line; Home stays quiet so a hint failure cannot add noise or fake a clean state. |

Home does not read any forecast value from the suggestion state, and the forecast code does not read this state (INV-1). Only the ViewModel calls the suggestion API.

### 3.3 Spec states with no UI, UI states with no spec cause

| Item | Finding |
|---|---|
| `PaymentLinkSuggestion.alternatives` ("Different bill") | Spec state with no UI by design (Q1). The field is unused by S3. |
| Consent-denied / permission-denied | None exist. The feature needs no permission or consent. No UI state. |
| Offline | None. All work is local; no network. No UI state. |
| `Failed(code)` codes | Only `WRITE_BLOCKED` and `INTERNAL` exist; both mapped above. |
| Already linked or already paid (occurrence not open) | Not a state: the suggestion simply does not exist (INV-8). A stale tap gives `NoLongerValid`. |
| UI `Unavailable` line on Recurring | Cause: `Unavailable(...)`. Slight reading of AE-18 "UI hides the section" (see Q3). |
| `Loading` | No spec cause beyond an async read; it is UI-only and renders nothing. |

## 4. Money display

- Every amount is formatted with `CurrencyFormatter.formatMoney(amount, currency)`, so the currency always shows ("400,00 EUR"). The paid amount uses the expense's own currency. By INV (section 1.2 of the spec) it equals the rule's currency, so the block never mixes currencies and never converts.
- No totals, no sums, no mixed-currency lines: one expense pays one bill. No FX, so no "conversion unavailable" state exists in this feature.
- The paid amount may differ from the rule's amount by up to 10 percent. The block shows the true paid amount; the rule's own amount is on the line above in the same card. The tolerance and the difference are not shown.
- Rounding as the user sees it: formatter rounding only. Stored values are untouched (the claim snapshot stores the exact expense amount).
- Dates use `MMM dd` in the device zone, as the card does today.
- Home hint shows a count only, no money.

## 5. Destructive or irreversible actions

- **Link.** Not a deletion: no data is lost. It moves the bill out of the committed obligations and can be undone only by editing or deleting the expense (spec L-11, D-6: no unlink path in v1). Design: one tap, no confirmation dialog, because the block already shows the exact amount, the payment date and the due date. The snackbar states the effect ("marked as paid"). No Undo action (there is no undo operation in the domain). See Q2.
- **Not this bill.** Permanent in v1 (D-9, no undo of a decline). Low impact: the bill stays counted and "Mark as Paid" / "Already recorded" are still available. No dialog; the snackbar says the suggestion will not return. The snackbar action is intentionally not "Undo". See Q2.
- Double taps and retries are safe: both operations are idempotent (AE-19), and the row buttons disable while a call is in flight.
- Nothing here deletes an expense or a rule.

## 6. Accessibility

TalkBack order inside a card (top to bottom, one stop each):

1. Merchant name and note (existing).
2. Active switch (existing).
3. Amount and frequency, due label (existing).
4. Suggestion block summary (one merged node).
5. Link button.
6. Not this bill button.
7. Mark as Paid (existing).

Details:

- **Block summary node:** `semantics(mergeDescendants = true)` with `contentDescription = recurring_link_suggestion_cd_format` ("Possible payment for Rent: 400,00 EUR on Nov 25, matches the bill due Nov 27"). Visible lines are not read twice.
- **Buttons:** visible text is short ("Link", "Not this bill"), but each carries a `contentDescription` naming the merchant, amount and date, so several stacked blocks are distinguishable by TalkBack and by voice access. Voice control: visible label wins for "click Link"; the description disambiguates.
- **Touch targets:** at least 48dp by 48dp for both buttons and the Home card (set `heightIn(min = 48.dp)`; Material3 Button defaults to 40dp).
- **Font scaling:** no fixed heights, no `maxLines` truncation of the block text; buttons stack at 1.3 or more; Home card text wraps and the action stays reachable.
- **Contrast:** text uses `TextPrimary` / `TextSecondary` on the tinted surface, as `TotalMonthlyCard` does. Do not rely on the tint to convey meaning; the "Paid already?" wording does.
- **Announcements:** results are snackbars (announced by default). The unavailable line is a normal text node. Do not set live regions that would re-announce on every recompute.
- **Focus after Link / Not this bill:** the block is removed; leave focus on the card (do not jump). The snackbar announces the result.
- **Home hint:** one clickable node, `onClickLabel = home_paid_bill_hint_review_cd`, text read as the plural string. The "Review" text button does not add a second TalkBack stop.
- Decorative icons: none are added.

## 7. Copy

Rules: strings never contain raw notification, OCR or receipt text. The block shows the rule's merchant (the user's own saved name) and the expense's amount and date; it never shows the expense note or description. Snackbars show the rule merchant only. Logs and diagnostics carry bounded codes only (`PAYMENT_LINK_CONFIRMED`, `PAYMENT_LINK_DECLINED`, `PAYMENT_LINK_NO_LONGER_VALID`, `PAYMENT_LINK_SUGGESTIONS_UNAVAILABLE`, `suggestionCount=<n>` per spec section 6). Never log the merchant, amount or ids with text.

### 7.1 Reused keys (no change)

| Key | English | Used for |
|---|---|---|
| `label_due_format` | Due %1$s | Line 2 of the block. el and es have no translation of this key today, so it falls back to English there, as on the existing card. Pre-existing gap, not caused by this feature. |
| `ui_write_blocked_maintenance` | Changes are paused while a backup or restore is running. Please try again in a moment. | `Failed(WRITE_BLOCKED)`. en, es, el exist. |
| `recurring_error_generic` | Something went wrong. Please try again. | `Failed(INTERNAL)`. en, es, el exist. |
| `recurring_mark_paid`, `recurring_mark_already_recorded` | Mark as Paid / Already recorded | Unchanged existing actions. |
| `cd_refresh` | (existing) | The toolbar Refresh is the retry for the unavailable line. |

### 7.2 New keys (13: 12 strings, 1 plural)

es and el values are DRAFT: machine-style drafts for the owner to sign off (CLAUDE memory: es/el need sign-off). Do not ship without that.

| # | Key | English | es (DRAFT) | el (DRAFT) |
|---|---|---|---|---|
| 1 | `recurring_link_suggestion_title_format` (%1$s amount with currency, %2$s date) | Paid already? %1$s on %2$s | ¿Ya pagada? %1$s el %2$s | Έχει ήδη πληρωθεί; %1$s στις %2$s |
| 2 | `recurring_link_action` | Link | Vincular | Σύνδεση |
| 3 | `recurring_link_decline` | Not this bill | No es esta factura | Όχι αυτός ο λογαριασμός |
| 4 | `recurring_link_suggestion_cd_format` (merchant, amount, payment date, due date) | Possible payment for %1$s: %2$s on %3$s, matches the bill due %4$s | Posible pago de %1$s: %2$s el %3$s, coincide con la factura que vence el %4$s | Πιθανή πληρωμή για %1$s: %2$s στις %3$s, ταιριάζει με τον λογαριασμό που λήγει στις %4$s |
| 5 | `recurring_link_action_cd_format` (merchant, amount, date) | Link the %2$s payment on %3$s to %1$s | Vincular el pago de %2$s del %3$s con %1$s | Σύνδεση της πληρωμής %2$s της %3$s με το %1$s |
| 6 | `recurring_link_decline_cd_format` (merchant, amount, date) | The %2$s payment on %3$s is not for %1$s | El pago de %2$s del %3$s no es de %1$s | Η πληρωμή %2$s της %3$s δεν αφορά το %1$s |
| 7 | `recurring_link_done_format` (merchant) | Linked. %1$s is marked as paid. | Vinculado. %1$s se marcó como pagada. | Συνδέθηκε. Το %1$s επισημάνθηκε ως πληρωμένο. |
| 8 | `recurring_link_declined` | Got it. We won't suggest this payment for this bill again. | Entendido. No volveremos a sugerir este pago para esta factura. | Εντάξει. Δεν θα προτείνουμε ξανά αυτή την πληρωμή για αυτόν τον λογαριασμό. |
| 9 | `recurring_link_no_longer_valid` | That payment can no longer be linked to this bill. The list was refreshed. | Ese pago ya no se puede vincular a esta factura. Se actualizó la lista. | Αυτή η πληρωμή δεν μπορεί πλέον να συνδεθεί με αυτόν τον λογαριασμό. Η λίστα ανανεώθηκε. |
| 10 | `recurring_link_suggestions_unavailable` | Payment suggestions are unavailable right now. | Las sugerencias de pago no están disponibles ahora. | Οι προτάσεις πληρωμής δεν είναι διαθέσιμες αυτή τη στιγμή. |
| 11 | `home_paid_bill_hint` (plural, %1$d) | one: %1$d bill may already be paid. / other: %1$d bills may already be paid. | one: %1$d factura podría estar ya pagada. / other: %1$d facturas podrían estar ya pagadas. | one: %1$d λογαριασμός ίσως έχει ήδη πληρωθεί. / other: %1$d λογαριασμοί ίσως έχουν ήδη πληρωθεί. |
| 12 | `home_paid_bill_hint_action` | Review | Revisar | Έλεγχος |
| 13 | `home_paid_bill_hint_review_cd` | Review possible paid bills | Revisar posibles facturas pagadas | Έλεγχος πιθανών πληρωμένων λογαριασμών |

Notes on the table:

- In en XML the apostrophe in key 8 must be escaped (`won\'t`).
- Plural: es may also need a `many` quantity under current CLDR for large counts; follow the pattern of the existing `money_total_partial_notice` in es and el.
- Key 12 is new rather than reusing `widget_review` ("REVIEW"): that key is uppercase and has no el translation.
- Wording is "bill" throughout, matching "Mark bill as paid" in `recurring_mark_paid_dialog_title`.

## 8. Edge cases as the user sees them

| Case | UI |
|---|---|
| Suggestion present, user taps Mark as Paid, then picks "Already recorded" | Works as today. The suggestion disappears on recompute (occurrence no longer open). |
| Suggestion present, user taps Mark as Paid, then "Mark paid (creates expense)" | Creates a second expense for a bill the user may already have paid. Existing dialog; unchanged by this design. See Q4. |
| Two suggestions for one rule | Two stacked blocks, each naming its due date. |
| Expense edited or deleted while the screen is open | Block can go stale; the next recompute or a tap (`NoLongerValid`) resolves it. |
| After Link, advance (INV-13) fails | The card may still show the old due date (L-7). The bill is PAID in the persisted state; the Dashboard path may still count it until self-heal. Not a visible error. |
| After Link, expense later deleted | Existing behaviour: occurrence returns to PLANNED, counted again (AE-12). A new suggestion can appear only if another matching expense exists. |
| Partial or split payments, other currency, outside the window | Nothing is suggested (L-2, L-3, L-4). The user's path remains Already recorded. |
| Rule paused | Card is under Inactive; no block (AE-27). |
| Restore running | Unavailable line; no buttons (AE-18). |

## 9. Test and review hooks (for S3 authors)

- Compose/semantic tests: block present only for `Content`; absent for `Loading`, `None`; unavailable line present only for `Unavailable`; buttons disabled while the rule id is in `inFlightRuleIds`; `Failed(WRITE_BLOCKED)` shows `ui_write_blocked_maintenance`; no text node ever shows `0` or a blank amount for an unavailable state.
- Home: no hint for `Loading`, `None`, `Unavailable`; plural `one` vs `other`; hint tap invokes the new navigation callback (not `onNavigateToRecurring`).
- Strings: each new key present in en; es and el present and flagged DRAFT in the review note; no hardcoded user text.
- Architecture test from INV-1: only `ui/` ViewModels and `MarkRecurringBillPaidUseCase` reference the suggestion API.

## 10. Open questions for the human

1. **"Different bill" picker.** The domain spec D-1 proposal mentioned it when `alternatives` exist; `decisions.md` D-1 says only "row plus Home hint". This design omits it (no extra surface, no dialog). After "Not this bill" the next-nearest bill is suggested on recompute. Confirm, or approve a small dialog in a follow-up.
2. **No confirmation dialog, no Undo for Link and Not this bill.** Link is reversible only by editing/deleting the expense, Not this bill not at all in v1. Default: single tap with an informative snackbar. Add a confirm dialog for Link if the owner wants a stronger guard.
3. **Unavailable line on the Recurring screen.** AE-18 says "the UI hides the section rather than showing none". This design hides suggestion blocks and adds one muted "unavailable" line (and nothing on Home). If the owner wants strict hiding, drop key 10 and the `Unavailable` rendering; the state stays but renders nothing.
4. **Mark as Paid while a suggestion exists.** Consider adding a note to `recurring_mark_paid_dialog_body` or disabling "creates expense" when a suggestion is present, to avoid double expenses. Out of scope for this minimal design; the existing dialog is untouched.
5. **Home hint target.** Needs a new callback to `ManualRecurringExpense` (finding 2). Confirm it is acceptable to leave `onNavigateToRecurring` (patterns screen) alone.
6. **es and el sign-off** for the 13 keys (DRAFT). Also, `label_due_format` and `widget_review` have no el/es translation today.
7. **Rule-id mapping** (finding 4): confirm `ManualRecurringExpense.id` equals the rule id used by the occurrences.
