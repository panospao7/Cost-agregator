# REFUND-NETTING (DR-05): UX flows

Status: DESIGN ONLY. Inputs: `domain-spec.md`, `decisions.md` (D-4 no auto-link, D-5 cardinality, D-7 same currency, D-8 list stays gross, D-10 edit lock, D-17 UX surface), `docs/features/fc-paid-unlinked/ux-flows.md` (the model for suggest, confirm and decline).
Source read: worktree `Cost-agregator-fu1`. Paths below are under `app/src/main/java/com/yourname/expensetracker/`.
Scope: v1 minimal. No new screen, no new destination, no new permission, no network. Everything lives on the existing Transactions screen: row notice, row chips, overflow items, one bottom sheet for picking, one for details, and confirm dialogs.

## 0. Findings that shape the design

1. **There is no separate transaction detail screen.** `ui/screens/transactions/TransactionsScreen.kt` shows each transaction as a `TransactionItem` card with an overflow menu (`DropdownMenu`) and opens modal dialogs and sheets from it (`ChangeTypeDialog`, `DeleteConfirmationDialog`, the location `ModalBottomSheet`). The brief's "deposit detail screen" and "purchase detail" therefore map to: the **row card + overflow menu** for entry, and a small **Refund details sheet** for the purchase-with-refunds view. No amount editor for an existing expense exists in the UI, so the INV-12 lock is reachable only through `ChangeTypeDialog` (and defensively through any future editor).
2. **The refund notice exists in two places**: inside the row (`TransactionItem`, `isLikelyRefund`, text `transactions_refund_likelihood_notice`, `WarningOrange`) and inside `ChangeTypeDialog`. The current text tells the user to edit or delete the purchase and delete the deposit. That advice is wrong once linking exists, so the text is **reworded** (section 7.3) and both places use one shared composable with the new action.
3. **The notice is computed from the loaded page** (`likelyRefundDepositIds` in `TransactionsScreen`), while the picker reads candidates from the database (`RefundLinkUseCase.findCandidates`, 90 days, relaxed rules). They can disagree; the picker is authoritative and the notice is only a nudge.
4. **Row chips are row-local.** Every chip, the net line and the "still counts as income" line are derived from `refundNettedAmount`, `amount` and `currency` already on the row. The list therefore needs no extra read, and has no loading, unavailable or error state for chips. Counterpart names are read only on demand (picker, details sheet, unlink confirm).
5. **Spec gap, UI needs a read.** `domain-spec.md` 1.5 has `findCandidates` but no read of the links of one expense. The Refund details sheet and the unlink confirm need it. See section 3.4 and Q1.
6. Existing patterns reused: `viewModel.error` and `viewModel.successMessage` snackbars (success carries an "OK" action and closes pending dialogs), `mutatingExpenseIds` to disable a row during a write, `expense_edit_blocked_group_linked` and `transactions_installment_purchase_locked` as the pattern for lock messages, `ui_write_blocked_maintenance` for the write barrier, `transactions_installment_chip_not_counted` for a `labelSmall` chip under the amount, the fc-paid tonal block and 48dp buttons that stack at font scale 1.3 or more.

## 1. Entry points and navigation

| Entry | Path | Result | Back |
|---|---|---|---|
| A. Refund notice on a deposit row | Transactions -> a deposit that `RefundLikelihoodDetector` flags -> notice text with button "Apply to purchase". The same notice and button appear in `ChangeTypeDialog`; the button there closes the dialog first. | Opens the **Picker sheet** | Back or swipe closes the sheet. Nothing is written. |
| B. Deposit overflow item | Transactions -> any deposit with no link and no installment flag (`refundNettedAmount == 0.0`, `spendingExclusionReason == null`) -> overflow "This is a refund of..." | Opens the **Picker sheet** | Same |
| C. Linked row, either side | Transactions -> a row with `refundNettedAmount > 0` -> overflow "Refund details" | Opens the **Refund details sheet** (purchase: its refunds with net; deposit: the purchase it was applied to) | Back closes the sheet |
| D. Unlink | Refund details sheet -> "Remove link" on a link -> confirm dialog | Link removed, snackbar | Cancel closes the dialog only |

Notes:

- No deep link, no notification, no route change. Back never leaves Transactions because of these flows.
- The overflow item "This is a refund of..." is hidden for rows that are not deposits, that are linked already, or that carry `spendingExclusionReason` (installment-managed, INV-19). That is AE-28: a deposit that is not eligible offers no action. Group-linked deposits are not row-local, so they still show the item and get a refusal after the user picks (section 3.3, Q5).
- Unlink exists only in the details sheet (one path). A linked deposit's overflow shows "Refund details", not a second "Remove link".
- Delete of a linked row keeps its existing confirm dialog, with one added line (section 5).

## 2. Screens

### 2.1 Transactions row, deposit with likely-refund notice (existing card, changed)

Purpose: nudge, then one tap into the picker. Primary action: "Apply to purchase" (`TextButton`, at least 48dp high, under the notice). No secondary action; the user ignores the notice to keep today's behaviour (L-1).

```
+-----------------------------------------------------+
| (icon) ZARA                              +40,00 EUR |
|        Clothing                      [type] [more]  |
|        [Deposit badge]                              |
|        This looks like a refund of a recent         |
|        purchase. Apply it to that purchase so its   |
|        category and budget totals go down and this  |
|        deposit stops counting as income.            |
|        [ Apply to purchase ]                        |
|        14:05                                        |
+-----------------------------------------------------+
```

The notice and button disappear when the deposit becomes linked (INV-18), which happens after the next list emission.

### 2.2 Transactions row, linked purchase and linked deposit (existing card, chips added)

The row amount stays gross (D-8). Chips sit under the amount, `labelSmall`, same slot as the installment "Not counted" chip. They are text, not icons, and not tappable.

```
Purchase (100,00 EUR, 40,00 refunded)       Deposit (40,00 EUR, fully applied)
+----------------------------------------+  +----------------------------------------+
| ZARA                        100,00 EUR |  | ZARA                        +40,00 EUR |
| Clothing                  Refunded     |  | Clothing        Applied to a purchase: |
|                           40,00 EUR    |  |                              40,00 EUR |
|                           Net 60,00 EUR|  |                         [type] [more]  |
|                           [type][more] |  +----------------------------------------+
+----------------------------------------+
Deposit larger than the purchase (35,00 deposit, 30,00 applied):
 ... Applied to a purchase: 30,00 EUR
     5,00 EUR still counts as income
```

Rules:

- Purchase chip lines: "Refunded {refundNetted}" then "Net {amount - refundNetted}". When fully refunded the net line reads "Net 0,00 EUR" (a real zero, not unavailable).
- Deposit chip lines: "Applied to a purchase: {refundNetted}". When `refundNetted < amount` (at the currency scale) a second line "{amount - refundNetted} still counts as income".
- Chip colour: `SuccessGreen` for the purchase refunded line, `MaterialTheme.colorScheme.primary` for the deposit line, `TextSecondary` for net and left-over lines. Meaning is carried by the words, not the colour.
- Each chip group is one merged semantics node with its own content description (section 6).
- The shared/not-mine share is not shown in the chips. Net is the gross net; the user's share effect appears only in totals (AE-7, AE-8, AE-9).

### 2.3 Picker sheet (new `ModalBottomSheet`, same style as the location sheet)

Purpose: choose which purchase the deposit refunds. Opened from entry A or B. The read starts when the sheet opens. Nothing is pre-selected and nothing is applied without the confirm dialog (D-4).

```
+-----------------------------------------------------+
|  Which purchase was refunded?                  [X]  |
|  Refund of 40,00 EUR on Dec 02                      |
|  -------------------------------------------------  |
|  ZARA                                  100,00 EUR   |
|  Nov 03 - Clothing                                  |
|  -------------------------------------------------  |
|  ZARA                                   40,00 EUR   |
|  Nov 20 - Clothing                                  |
|  -------------------------------------------------  |
|  H&M                                    40,00 EUR   |
|  Nov 25 - Clothing                                  |
|  60,00 EUR not yet refunded   (only if partly       |
|                                refunded already)    |
+-----------------------------------------------------+
```

- Each candidate is one row, whole row is the tap target (at least 56dp). Order is the domain ranking (exact amount, then merchant, then nearest date, then newest), at most 50 rows, scrollable (`LazyColumn`). The UI does not re-sort and does not show why a row ranks first.
- Each row shows: merchant (read live), amount with currency, date (`MMM dd`), category name (or "Uncategorized"), and "{remaining} not yet refunded" only when `remaining < amount`.
- A single candidate is still shown as a list of one and still needs a tap. A short list is simply fewer rows; no paging, no search, no filter.
- States inside the sheet: loading (centered progress), content (above), none (message plus the currency/window hint), unavailable (message plus Retry). See 3.1.
- Close icon button: 48dp, description `a11y_close`.

### 2.4 Confirm dialog (new `AlertDialog`)

Opened by tapping a candidate. Shows exactly what will change before anything is written. The applied amount is the preview `min(remaining of purchase, remaining of deposit)` from `RefundNettingPolicy`; the write recomputes it, and the result snackbar shows the true outcome.

```
+-------------------------------------------------+
| Apply this refund?                              |
|                                                 |
| Purchase     ZARA, Nov 03          100,00 EUR   |
| Refunded                            40,00 EUR   |
| Net                                 60,00 EUR   |
|                                                 |
| This lowers spending in November 2026           |
| (Clothing) by 40,00 EUR, even if that month     |
| has passed. It also stops 40,00 EUR of this     |
| deposit counting as income.                     |
|                                                 |
| (only when the deposit is not fully used:)      |
| Only 30,00 EUR of this 35,00 EUR deposit can    |
| be applied. The rest still counts as income.    |
|                                                 |
|                       [ Cancel ]   [ Apply ]    |
+-------------------------------------------------+
```

- "Refunded" is the applied amount for this link; "Net" is `purchase.amount - purchase.refundNettedAmount - applied`, so earlier refunds are included.
- Apply is a normal (indigo) text button; there is no danger colour because no data is deleted. While the write is in flight the dialog stays, both buttons are disabled and a thin progress indicator shows; it closes on the result (same rule as `successMessage` closing dialogs).
- The month and category text uses the purchase's own date and category (INV-10). It is the only place the retroactive effect (D-3, L-5) is stated to the user.

### 2.5 Refund details sheet (new `ModalBottomSheet`)

Opened by entry C. For a **purchase**: gross, refunded, net, then one row per linked refund. For a **deposit**: one row for the purchase it was applied to. Each row has "Remove link".

```
Purchase view                                     Deposit view
+---------------------------------------------+   +---------------------------------------------+
| Refund details                         [X]  |   | Refund details                         [X]  |
| ZARA - Nov 03                               |   | Refund of 40,00 EUR on Dec 02               |
| Purchase                        100,00 EUR  |   | Applied to                                  |
| Refunded                         40,00 EUR  |   | ZARA - Nov 03 - Clothing         40,00 EUR  |
| Net                              60,00 EUR  |   |                          [ Remove link ]    |
| Refunds on this purchase                    |   +---------------------------------------------+
| ZARA - Dec 02                    40,00 EUR  |
|                          [ Remove link ]    |
+---------------------------------------------+
```

- The refund rows show the refund's merchant (read live), date and applied amount (the link's `appliedAmount`, not the deposit total).
- A purchase can list many refunds; the list scrolls inside the sheet. Header numbers (Purchase, Refunded, Net) come from the purchase row, the same values as the chips.
- "Remove link" is a `TextButton`, at least 48dp, one per link. It opens the unlink confirm (2.6).
- States inside the sheet: loading, content, unavailable (with Retry). If the read says the row has no link any more, the sheet closes and the list refreshes (no message, the chips have already gone).

### 2.6 Unlink confirm dialog (new `AlertDialog`)

```
+-------------------------------------------------+
| Remove this link?                               |
|                                                 |
| The 40,00 EUR refund will count as income       |
| again, and ZARA will go back to 100,00 EUR in   |
| its month and category.                         |
|                                                 |
|                  [ Cancel ]  [ Remove link ]    |
+-------------------------------------------------+
```

Remove link is a normal text button (reversible: the user can link again). The amounts are the link's applied amount and the purchase's gross-after-removal (`amount - (refundNetted - applied)`), so with several refunds the second figure is the purchase's new net, not necessarily its gross. Copy uses "go back to" only for the single-refund case; see Q7 for the multi-refund wording default.

## 3. UI state model

All state is held in `TransactionsViewModel` (or a small `RefundLinkViewModel` scoped to the screen; open implementation choice for S4). Three independent sealed states plus transient results. None of them blocks the list: the list, filters and other rows work while a sheet is closed or loading.

### 3.1 Picker (`RefundPickerUiState`)

| State | Renders | Produced by |
|---|---|---|
| `Idle` | Nothing (no sheet) | Sheet not open |
| `Loading` | Sheet header, centered progress with content description `refund_picker_loading_cd`. No list, no empty text. | `findCandidates` running, or live display reads of the candidate rows running |
| `Content(depositId, candidates)` | The list (2.3) | `RefundCandidates.Available(items non-empty)` and at least one item renderable (INV-3, INV-5) |
| `None(currency, windowDays)` | Message `refund_picker_none_format` ("No matching purchases found. Only EUR purchases from the 90 days before this refund can be matched.") | `Available([])` (AE-28): no purchase in the window, all fully refunded, all other currency (D-7), group-linked, installment-flagged, or deposit not eligible |
| `Unavailable` | Message `refund_picker_unavailable` plus a "Retry" button. Never the empty text, never a zero. | `Unavailable(READ_BLOCKED)` (restore running, INV-17, AE-22) and `Unavailable(READ_FAILED)`. Both give the same message; the reason code is not shown. Also: every candidate failed its live display read (see below). |
| `Confirming(deposit, candidate, previewApplied, submitting)` | The confirm dialog over the sheet | User tapped a candidate |

Rules:

- **Render truthfulness.** `RefundCandidate` carries ids and amounts only (spec 1.5). The VM reads each purchase's merchant and category by id. A candidate that cannot be read is dropped. If all are dropped the state is `Unavailable`, never `None`.
- **Cross-currency refused (D-7).** Cross-currency purchases are never listed (the policy filters them), so there is no refusal screen. The user learns the rule from the `None` message, which names the deposit's currency and the window. If the user could somehow reach `Confirming` with a stale pair, the write returns `Refused(CURRENCY_MISMATCH)` and the message in 3.3 shows.
- Retry reruns `findCandidates`. There is no automatic retry or polling.
- While a restore runs, reads are blocked, so the user normally sees `Unavailable` and no candidates. A restore that starts after `Confirming` shows the `WRITE_BLOCKED` message instead.

### 3.2 Refund details (`RefundDetailsUiState`)

| State | Renders | Produced by |
|---|---|---|
| `Idle` | Nothing | Sheet not open |
| `Loading` | Header (from the row) and progress. Gross, refunded and net are already known from the row, so they are shown during loading; only the link rows wait. | Link read running |
| `Content(summary, links)` | 2.5 | New link read `Available(links non-empty)` (Q1) |
| `NoLinks` | Sheet closes, list refreshes | Link read returns no link (stale row; INV-1 says the row column and the links agree, so this is only a race with an unlink or delete) |
| `Unavailable` | Message `refund_details_unavailable` plus Retry. The row-derived summary numbers stay visible. | Link read `Unavailable(READ_BLOCKED or READ_FAILED)` |
| `ConfirmingUnlink(link, submitting)` | 2.6 | User tapped Remove link |

### 3.3 Action results (transient snackbars via `viewModel.error` and `viewModel.successMessage`; not sealed screen states)

Row buttons for both ids disable while the id is in `mutatingExpenseIds`.

| Domain result | User sees | Then |
|---|---|---|
| `Linked(applied)` | `refund_done_format` ("Refund applied to ZARA. Net: 60,00 EUR.") | Dialog and sheet close. Chips update from the next list emission. |
| `Linked(applied)`, net figure cannot be computed (unknown currency scale) | `refund_done_plain_format` ("Refund applied to ZARA.") | Dialog and sheet close, as for `Linked(applied)` |
| `AlreadyLinked` | `refund_already_applied` (success tone; a lost-response retry) | Dialog and sheet close |
| `Refused(NOTHING_TO_APPLY)` (AE-3) | `refund_refused_nothing_to_apply` | Dialog closes; sheet reloads candidates |
| `Refused(REFUND_LINKED_ELSEWHERE)` (AE-5) | `refund_refused_linked_elsewhere` | Dialog and sheet close; the row now shows its chip |
| `Refused(REFUND_BEFORE_PURCHASE)` (AE-12) | `refund_refused_before_purchase` | Dialog closes; sheet reloads |
| `Refused(CURRENCY_MISMATCH)` (AE-6) | `refund_refused_currency` | Dialog closes; sheet reloads |
| `Refused(INSTALLMENT_MANAGED)` (AE-11) | `refund_refused_installment` | Dialog closes; sheet reloads |
| `Refused(GROUP_LINKED)` (AE-11) | `refund_refused_group` | Dialog closes; sheet reloads |
| `Refused(REFUND_NOT_FOUND, PURCHASE_NOT_FOUND, SAME_ROW, NOT_A_DEPOSIT, NOT_A_PURCHASE, INVALID_AMOUNT, CURRENCY_DIGITS_UNAVAILABLE)` | `refund_refused_stale` ("That refund can no longer be applied to this purchase. The list was refreshed.") One message for all seven: the user cannot act on the difference. | Dialog closes; list refreshes |
| `Failed(WRITE_BLOCKED)` (AE-22) | Existing `ui_write_blocked_maintenance` | Nothing written. Dialog stays so the user can retry; buttons re-enable. |
| `Failed(INTERNAL)` | Existing `recurring_error_generic` ("Something went wrong. Please try again.") | Dialog stays; retry allowed |
| `Unlinked` or `NotLinked` | `refund_unlinked` (both are success; idempotent) | Dialog closes; details sheet reloads or closes if no links remain |
| Unlink `Failed(WRITE_BLOCKED / INTERNAL)` | Same two existing messages as above | Dialog stays |
| `RefundLinkedExpenseException` (INV-12), from `ChangeTypeDialog` confirm | `transactions_refund_linked_locked` via `viewModel.error` (same pattern as `transactions_installment_purchase_locked`) | Dialog stays or closes as the existing type-change flow does on refusal |
| `GroupTransactionCoordinator` refuses a refunded row (`REFUND_LINKED`, INV-19) | Not a refund-netting screen. The existing group flow shows its own refusal; it needs one string, see Q6. | Out of this doc's strings |

Prevention (so the lock is rarely hit): `ChangeTypeDialog` for a row with `refundNettedAmount > 0` shows `transactions_refund_linked_locked` as an info line in the dialog and disables Confirm. The snackbar path above is the safety net for a race.

### 3.4 Spec states with no UI, UI states with no spec cause

| Item | Finding |
|---|---|
| `RefundCandidate.merchantMatch`, `exactAmountMatch`, `dayDelta` | Spec fields with no UI by design. They only drive ranking. The user sees the order, not the reason. |
| Seven refusal codes folded into `refund_refused_stale` | Spec states with one shared UI message (listed in 3.3). Not a gap. |
| Consent-denied / permission-denied | None exist. The feature needs no permission or consent. No UI state. |
| Offline | None. All work is local, no network. No UI state. |
| Unavailable FX / conversion | None. Linking uses no FX (D-7). The chip and dialog amounts are native currency. No UI state. |
| Candidate `Available([])` with different causes (other currency, group-linked deposit, already fully refunded) | One UI state `None`; the causes are not distinguishable by the spec (AE-28), so the UI cannot name them. Q5. |
| **Spec gap: link read for details sheet and unlink confirm** | The UI needs `RefundLinkUseCase` to expose a read-only, barrier-checked read of the links of one expense (counterpart id, applied amount, currency, createdAt) returning `Available(items)` or `Unavailable(READ_BLOCKED or READ_FAILED)`. It is not in `domain-spec.md` 1.5. Q1. |
| UI `Loading` states (picker, details) | UI-only, async reads; no spec cause beyond that. |
| Row chips | No state at all: row-local data. A `refundNettedAmount` that is `> 0` but exceeds `amount` cannot occur (INV-5); if it did the net chip would clamp at 0,00 and not show a negative. |
| Day-header total | Existing UI that now nets linked rows (INV-11, decision 131 precedent). No new state. See edge cases. |

## 4. Money display

- **Every amount carries its currency**: `CurrencyFormatter.formatMoney(amount, currency)` (for example "40,00 EUR"). The picker candidate row, the confirm dialog, the chips and the details sheet each use the row's own currency. There is no home-currency conversion anywhere in these screens.
- **No mixed-currency totals.** A link exists only between rows of one currency (D-7), so every block shows one currency. The picker subtitle and the `None` message name the deposit's currency so the user understands why other-currency purchases are missing.
- **Gross, refunded, net.**
  - Gross = the row amount, unchanged in the list (D-8).
  - Refunded = `refundNettedAmount` on the purchase, or the link's `appliedAmount` in the dialogs.
  - Net = `amount - refundNettedAmount`, computed in BigDecimal at the currency's scale (HALF_EVEN, the spec's `s(x)`), then formatted. It is never computed by adding Doubles and never negative.
  - The "net" shown is of the gross amount. A shared or not-mine purchase's personal share is not shown in these screens (the totals apply it, INV-6).
- **Fully refunded** shows "Net 0,00 EUR": a real zero. A failed read is `Unavailable`, never a zero.
- **Excess refund** (refund larger than what is left on the purchase): the confirm dialog says how much can be applied; the deposit chip says how much "still counts as income".
- **Rounding as the user sees it.** Applied amounts are already at the currency scale. The formatter may round a stored value that is not at scale (an imported 10.005 EUR shows as 10,01 while the capacity uses 10.00, AE-20). In that case "Refunded 10,00" plus "Net 0,00" does not add up to a displayed "10,01" gross by one cent. This is accepted for v1 and affects only imported odd-scale values.
- **Dates** use `MMM dd` in the device zone, as the cards do today. The confirm dialog uses month and year ("November 2026").
- **Day header** totals on Transactions use the netted amounts (INV-11), so a header total can be lower than the sum of the gross row amounts under it once a refund is linked. The chips explain why; no extra text in v1 (Q4).

## 5. Destructive or irreversible actions

| Action | Treatment |
|---|---|
| **Apply (link)** | Not a deletion, and reversible by Remove link. Because it changes totals of a possibly closed month, sends no alert withdrawal and does not re-run savings sweeps (L-5, D-3), it **always goes through the confirm dialog** (2.4), which states the month, category and amounts. No Undo snackbar action: the reverse is Remove link, and the snackbar says what happened. |
| **Remove link** | Confirm dialog (2.6). Reversible by linking again. No Undo action. |
| **Delete a linked row** | The existing `DeleteConfirmationDialog` gains one line, `transactions_delete_refund_linked_note`, shown when `refundNettedAmount > 0`. Deleting removes the link and restores the other side in the same transaction (INV-13). No refund-specific undo: an undo of a delete re-creates the row unlinked (L-4, AE-17); the note should not promise otherwise. |
| **Change type / amount / currency of a linked row** | Refused (INV-12). Prevented in `ChangeTypeDialog` (info line plus disabled Confirm); safety-net snackbar. The text tells the user to remove the link first. |
| **Decline of a suggestion** | There is none. Ignoring the notice or closing the sheet is free and changes nothing (no persisted decline state in v1). |
| **Double tap / retry** | Safe. `AlreadyLinked` and `NotLinked` are success results. Buttons disable while the pair is in `mutatingExpenseIds`. |

## 6. Accessibility

- **Touch targets**: Apply to purchase button, picker rows (56dp), Retry, Remove link, confirm and cancel buttons, and the close icon are all at least 48dp (`Dimens.TouchTargetMin`); set `heightIn(min = 48.dp)` because Material3 buttons default to 40dp. The new overflow items follow the existing menu.
- **Font scaling**: no fixed heights and no `maxLines` on the notice, chip lines, candidate rows or dialog text. At font scale 1.3 or more the dialog and sheet buttons stack full width (the `STACK_BUTTONS_FONT_SCALE` rule in `ManualRecurringExpenseScreen`). Sheets scroll; the confirm dialog body scrolls if it exceeds the screen.
- **TalkBack order, row**: merchant and amount (existing), category, notice text, **Apply to purchase** button, chips, time, then the row actions (change type, more options). Chips come after the amount in reading order, not before.
- **Chip nodes**: each chip group is `semantics(mergeDescendants = true)` with a description: `refund_chip_purchase_cd_format` ("Refunded 40,00 EUR of 100,00 EUR, net 60,00 EUR") and `refund_chip_deposit_cd_format`. The deposit "still counts as income" line is its own text node, read after the chip.
- **Picker rows**: one merged node per row (merchant, amount, date, category, remaining are read as visible text, in that order) with `onClickLabel` = `refund_candidate_select_cd_format` ("Apply the refund to the ZARA purchase"), so identical merchants are separated by amount and date. TalkBack order: sheet title, subtitle, rows top to bottom, close.
- **Remove link buttons** carry `refund_details_remove_cd_format` (amount and date) so several refunds on one purchase can be told apart by TalkBack and voice access.
- **Confirm dialog**: Material `AlertDialog` provides the title as the dialog heading. The three label/value pairs are each one merged node. Initial focus on the title; leave focus on the originating row after close; the snackbar announces the result.
- **Loading**: the progress indicator has `refund_picker_loading_cd`. Do not mark live regions that re-announce on each recompute.
- **Contrast**: text uses `TextPrimary` / `TextSecondary` / `WarningOrange` / `SuccessGreen` on the card surface, as existing chips do. Status is conveyed by words ("Refunded", "Applied", "Net"), never by colour alone.
- **Decorative icons**: none added (menu items use `contentDescription = null` icons like the others).

## 7. Copy

Rules: no string contains raw notification, OCR or receipt text. Screens show the user's stored merchant, category, amount and date only; they never show the note, the address or any notification text. Snackbars show the purchase merchant only. Logs and diagnostics carry the bounded codes from spec section 6 only (`REFUND_LINKED`, `REFUND_UNLINKED`, `REFUND_LINK_REFUSED(<code>)`, `REFUND_LINK_FAILED(<code>)`, `REFUND_CANDIDATES_UNAVAILABLE(<reason>)`); never the merchant, amount, category or ids with text. The ViewModel maps results to string resources; it never builds user text from a refusal code name.

### 7.1 Reused keys (no change)

| Key | English | Used for |
|---|---|---|
| `ui_write_blocked_maintenance` | Changes are paused while a backup or restore is running. Please try again in a moment. | `Failed(WRITE_BLOCKED)` (en, es, el exist) |
| `recurring_error_generic` | Something went wrong. Please try again. | `Failed(INTERNAL)` (en, es, el exist; same reuse as fc-paid-unlinked) |
| `retry` | Retry | Picker and details Retry |
| `cancel_button` | Cancel | Both dialogs |
| `a11y_close` | Close | Sheet close icon |
| `uncategorized_label` | Uncategorized | Candidate category fallback |
| `a11y_more_options` | More options | Existing overflow |

Note: `values-el/strings.xml` gained DRAFT Greek translations (owner native sign-off pending) for `action_ok` ("Εντάξει"), `a11y_close` ("Κλείσιμο") and `uncategorized_label` ("Χωρίς κατηγορία"), because the new sheets use them.

### 7.2 New keys (44 strings, new file `values/strings_refund_netting.xml`, plus `values-es` and `values-el`)

es and el values are **DRAFT**: machine-style drafts for the owner to sign off (project rule: es/el need sign-off). Do not ship without that. In en XML, apostrophes are escaped (`can\'t`). Placeholders: `%1$s` amounts are pre-formatted with currency.

| # | Key | English | es (DRAFT) | el (DRAFT) |
|---|---|---|---|---|
| 1 | `refund_notice_action` | Apply to purchase | Aplicar a una compra | Εφαρμογή σε αγορά |
| 2 | `refund_menu_apply` | This is a refund of... | Esto es un reembolso de... | Αυτό είναι επιστροφή χρημάτων για... |
| 3 | `refund_menu_details` (also the details sheet title) | Refund details | Detalles del reembolso | Λεπτομέρειες επιστροφής |
| 4 | `refund_chip_purchase_format` (amount) | Refunded %1$s | Reembolsado: %1$s | Επιστράφηκαν %1$s |
| 5 | `refund_chip_net_format` (amount) | Net %1$s | Neto: %1$s | Καθαρό: %1$s |
| 6 | `refund_chip_deposit_format` (amount) | Applied to a purchase: %1$s | Aplicado a una compra: %1$s | Εφαρμόστηκε σε αγορά: %1$s |
| 7 | `refund_chip_deposit_left_format` (amount) | %1$s still counts as income | %1$s sigue contando como ingreso | %1$s εξακολουθεί να μετρά ως εισόδημα |
| 8 | `refund_chip_purchase_cd_format` (refunded, gross, net) | Refunded %1$s of %2$s, net %3$s | Reembolsado %1$s de %2$s, neto %3$s | Επιστράφηκαν %1$s από %2$s, καθαρό %3$s |
| 9 | `refund_chip_deposit_cd_format` (applied, deposit amount) | Refund applied to a purchase: %1$s of %2$s | Reembolso aplicado a una compra: %1$s de %2$s | Η επιστροφή εφαρμόστηκε σε αγορά: %1$s από %2$s |
| 10 | `refund_picker_title` | Which purchase was refunded? | ¿Qué compra se reembolsó? | Ποια αγορά επιστράφηκε; |
| 11 | `refund_picker_subtitle_format` (amount, date) | Refund of %1$s on %2$s | Reembolso de %1$s el %2$s | Επιστροφή %1$s στις %2$s |
| 12 | `refund_picker_loading_cd` | Looking for matching purchases | Buscando compras coincidentes | Αναζήτηση αντίστοιχων αγορών |
| 13 | `refund_picker_none_format` (currency code, days) | No matching purchases found. Only %1$s purchases from the %2$d days before this refund can be matched. | No se encontraron compras coincidentes. Solo se pueden relacionar compras en %1$s de los %2$d días anteriores a este reembolso. | Δεν βρέθηκαν αντίστοιχες αγορές. Αντιστοιχίζονται μόνο αγορές σε %1$s από τις %2$d ημέρες πριν από αυτή την επιστροφή. |
| 14 | `refund_picker_unavailable` | Matching purchases are unavailable right now. | Las compras coincidentes no están disponibles ahora. | Οι αντίστοιχες αγορές δεν είναι διαθέσιμες αυτή τη στιγμή. |
| 15 | `refund_candidate_remaining_format` (amount) | %1$s not yet refunded | %1$s sin reembolsar | %1$s δεν έχουν επιστραφεί ακόμη |
| 16 | `refund_candidate_select_cd_format` (merchant) | Apply the refund to the %1$s purchase | Aplicar el reembolso a la compra de %1$s | Εφαρμογή της επιστροφής στην αγορά %1$s |
| 17 | `refund_confirm_title` | Apply this refund? | ¿Aplicar este reembolso? | Να εφαρμοστεί αυτή η επιστροφή; |
| 18 | `refund_label_purchase` | Purchase | Compra | Αγορά |
| 19 | `refund_label_refunded` | Refunded | Reembolsado | Επιστράφηκαν |
| 20 | `refund_label_net` | Net | Neto | Καθαρό |
| 21 | `refund_confirm_effect_format` (month year, category, amount) | This lowers spending in %1$s (%2$s) by %3$s, even if that month has passed. It also stops %3$s of this deposit counting as income. | Esto reduce el gasto de %1$s (%2$s) en %3$s, aunque ese mes ya haya pasado. También hace que %3$s de este depósito deje de contar como ingreso. | Αυτό μειώνει τις δαπάνες του %1$s (%2$s) κατά %3$s, ακόμη κι αν ο μήνας έχει περάσει. Επίσης, %3$s από αυτή την κατάθεση παύουν να μετρούν ως εισόδημα. |
| 22 | `refund_confirm_partial_format` (applied, deposit amount) | Only %1$s of this %2$s deposit can be applied. The rest still counts as income. | Solo se pueden aplicar %1$s de este depósito de %2$s. El resto sigue contando como ingreso. | Μπορούν να εφαρμοστούν μόνο %1$s από αυτή την κατάθεση των %2$s. Το υπόλοιπο εξακολουθεί να μετρά ως εισόδημα. |
| 23 | `refund_confirm_action` | Apply | Aplicar | Εφαρμογή |
| 24 | `refund_done_format` (merchant, net) | Refund applied to %1$s. Net: %2$s. | Reembolso aplicado a %1$s. Neto: %2$s. | Η επιστροφή εφαρμόστηκε στο %1$s. Καθαρό: %2$s. |
| 25 | `refund_already_applied` | This refund is already applied to that purchase. | Este reembolso ya está aplicado a esa compra. | Αυτή η επιστροφή έχει ήδη εφαρμοστεί σε αυτή την αγορά. |
| 26 | `refund_refused_nothing_to_apply` | That purchase has nothing left to refund. | Esa compra no tiene nada más que reembolsar. | Αυτή η αγορά δεν έχει άλλο ποσό προς επιστροφή. |
| 27 | `refund_refused_linked_elsewhere` | This refund is already applied to another purchase. Remove that link first. | Este reembolso ya está aplicado a otra compra. Quita primero ese vínculo. | Αυτή η επιστροφή έχει ήδη εφαρμοστεί σε άλλη αγορά. Αφαιρέστε πρώτα τη σύνδεση. |
| 28 | `refund_refused_before_purchase` | A refund can\'t be dated more than a day before the purchase. | Un reembolso no puede tener fecha más de un día anterior a la compra. | Μια επιστροφή δεν μπορεί να έχει ημερομηνία περισσότερο από μία ημέρα πριν από την αγορά. |
| 29 | `refund_refused_currency` | A refund can only be applied to a purchase in the same currency. | Un reembolso solo se puede aplicar a una compra en la misma moneda. | Μια επιστροφή μπορεί να εφαρμοστεί μόνο σε αγορά στο ίδιο νόμισμα. |
| 30 | `refund_refused_installment` | Installment purchases are refunded from the installment plan. | Las compras a plazos se reembolsan desde el plan de cuotas. | Οι αγορές με δόσεις επιστρέφονται από το πλάνο δόσεων. |
| 31 | `refund_refused_group` | This transaction is part of a shared group, so it can\'t be linked to a refund. | Esta transacción forma parte de un grupo compartido, por lo que no se puede vincular a un reembolso. | Αυτή η συναλλαγή ανήκει σε κοινή ομάδα, επομένως δεν μπορεί να συνδεθεί με επιστροφή. |
| 32 | `refund_refused_stale` | That refund can no longer be applied to this purchase. The list was refreshed. | Ese reembolso ya no se puede aplicar a esta compra. Se actualizó la lista. | Αυτή η επιστροφή δεν μπορεί πλέον να εφαρμοστεί σε αυτή την αγορά. Η λίστα ανανεώθηκε. |
| 33 | `refund_unlinked` | Refund removed from this purchase. | Reembolso eliminado de esta compra. | Η επιστροφή αφαιρέθηκε από αυτή την αγορά. |
| 34 | `refund_details_purchase_header` | Refunds on this purchase | Reembolsos de esta compra | Επιστροφές αυτής της αγοράς |
| 35 | `refund_details_deposit_header` | Applied to | Aplicado a | Εφαρμόστηκε σε |
| 36 | `refund_details_unavailable` | Refund details are unavailable right now. | Los detalles del reembolso no están disponibles ahora. | Οι λεπτομέρειες επιστροφής δεν είναι διαθέσιμες αυτή τη στιγμή. |
| 37 | `refund_details_remove` (also the unlink confirm button) | Remove link | Quitar vínculo | Αφαίρεση σύνδεσης |
| 38 | `refund_details_remove_cd_format` (amount, date) | Remove the link for the %1$s refund dated %2$s | Quitar el vínculo del reembolso de %1$s con fecha %2$s | Αφαίρεση της σύνδεσης για την επιστροφή %1$s με ημερομηνία %2$s |
| 39 | `refund_unlink_title` | Remove this link? | ¿Quitar este vínculo? | Να αφαιρεθεί αυτή η σύνδεση; |
| 40 | `refund_unlink_body_format` (applied, purchase merchant, purchase amount after removal) | The %1$s refund will count as income again, and %2$s will go back to %3$s in its month and category. | El reembolso de %1$s volverá a contar como ingreso y %2$s volverá a %3$s en su mes y categoría. | Η επιστροφή %1$s θα μετρά ξανά ως εισόδημα και το %2$s θα επανέλθει στα %3$s στον μήνα και την κατηγορία του. |
| 41 | `transactions_refund_linked_locked` | This transaction is linked to a refund, so its amount, currency and type can\'t be changed. Remove the link first. | Esta transacción está vinculada a un reembolso, por lo que no se pueden cambiar su importe, moneda ni tipo. Quita primero el vínculo. | Αυτή η συναλλαγή συνδέεται με επιστροφή, επομένως δεν μπορούν να αλλάξουν το ποσό, το νόμισμα και ο τύπος της. Αφαιρέστε πρώτα τη σύνδεση. |
| 42 | `transactions_delete_refund_linked_note` | This transaction is linked to a refund. Deleting it removes the link, and the other transaction goes back to its full amount. | Esta transacción está vinculada a un reembolso. Al eliminarla se quita el vínculo y la otra transacción vuelve a su importe completo. | Αυτή η συναλλαγή συνδέεται με επιστροφή. Η διαγραφή της αφαιρεί τη σύνδεση και η άλλη συναλλαγή επανέρχεται στο πλήρες ποσό της. |

| 43 | `refund_unlink_body_remaining_format` (applied, purchase merchant, purchase amount after removal; Q7 variant of key 40) | The %1$s refund will count as income again, and %2$s will show %3$s in its month and category. | El reembolso de %1$s volverá a contar como ingreso y %2$s mostrará %3$s en su mes y categoría. | Η επιστροφή %1$s θα μετρά ξανά ως εισόδημα και το %2$s θα εμφανίζει %3$s στον μήνα και την κατηγορία του. |
| 44 | `refund_done_plain_format` (merchant; used when the net figure cannot be computed) | Refund applied to %1$s. | Reembolso aplicado a %1$s. | Η επιστροφή εφαρμόστηκε στο %1$s. |

Keys 41 to 44 live in `strings_refund_netting.xml` too (prefix kept to match the `transactions_` lock-message neighbours).

### 7.3 Changed key

| Key | New English | es (DRAFT) | el (DRAFT) |
|---|---|---|---|
| `transactions_refund_likelihood_notice` (existing in `values`, `values-es`, `values-el`; value replaced, key unchanged) | This looks like a refund of a recent purchase. Apply it to that purchase so its category and budget totals go down and this deposit stops counting as income. | Esto parece un reembolso de una compra reciente. Aplíquelo a esa compra para que los totales de su categoría y presupuesto bajen y este depósito deje de contarse como ingreso. | Αυτό μοιάζει με επιστροφή χρημάτων για πρόσφατη αγορά. Εφαρμόστε την σε εκείνη την αγορά, ώστε να μειωθούν τα σύνολα κατηγορίας και προϋπολογισμού και αυτή η κατάθεση να μην υπολογίζεται ως εισόδημα. |

Notes: the old text advised deleting the deposit; keeping it would contradict the new action. The existing es text uses the formal "usted" form, so this draft follows it; the fc-paid-unlinked drafts use the informal form (consistency decision for the owner, Q9).

## 8. Edge cases as the user sees them

| Case | UI |
|---|---|
| Partial refund (40 of 100) | Purchase row: "Refunded 40,00 EUR / Net 60,00 EUR". Deposit row: "Applied to a purchase: 40,00 EUR", no left-over line. |
| Second refund on the same purchase | Picker lists the purchase with "60,00 EUR not yet refunded". Confirm shows Refunded (this link) and the new Net. The purchase chip shows the total refunded. |
| Refund larger than what is left (35 vs 30) | Confirm adds the "Only 30,00 EUR of this 35,00 EUR deposit can be applied" line. After linking, the deposit shows "5,00 EUR still counts as income". |
| Purchase already fully refunded | It is not listed (capacity 0). If a stale tap reaches the write: "That purchase has nothing left to refund." |
| Refund already linked | The overflow shows "Refund details", not "This is a refund of...". A stale picker gives "already applied to another purchase" or "already applied to that purchase". |
| Different currency | Purchase is never listed. Empty picker explains "Only EUR purchases...". Stale write: currency refusal message. |
| Refund dated more than a day before the purchase | Not listed. Stale write: date refusal message. |
| Installment purchase or installment-flagged deposit | Flagged deposit has no menu item. A flagged purchase is not listed; stale write says installment purchases are refunded from the plan. |
| Group-linked row | Deposit: menu item present (not row-local), picker `None`, or group refusal after confirm. Purchase: not listed. |
| Unlinked refund deleted by the user | Existing delete flow; nothing refund-specific. |
| Linked row deleted | The delete dialog has the extra line. After deleting, the counterpart's chips disappear on the next emission and its amount is gross again. |
| Change type on a linked row | Dialog shows the lock info line, Confirm disabled. |
| Day header after linking | Header for the purchase's day drops, header for the refund's day no longer counts the applied part. Row amounts stay gross. |
| Notice still shown after the user linked elsewhere | Notice is hidden for a linked deposit (INV-18) and for candidates fully refunded. |
| Restore running | Picker and details: `Unavailable` + Retry. Apply and Remove link: write-blocked message; dialog stays. |
| Process death with a dialog open | Dialogs and sheets are not restored; nothing was written before the commit. |
| User closes the sheet during the read | The read result is dropped; nothing is shown later. |

## 9. Test and review hooks (for S4 authors)

- **Compose/semantic tests**
  - Notice button is present only when `isLikelyRefund` and the row is unlinked; its tap opens the picker.
  - Overflow "This is a refund of..." is shown for an unlinked deposit without `spendingExclusionReason`, absent for linked, installment-flagged and non-deposit rows; "Refund details" is shown only for `refundNettedAmount > 0`.
  - Chips: purchase chip (refunded and net lines), fully refunded "Net 0,00" (not blank, not "unavailable"), deposit chip with and without the "still counts as income" line; every amount has its currency.
  - Picker: `Loading` shows only the progress; `Content` rows in the given order, "not yet refunded" only when `remaining < amount`; `None` shows the currency and window message; `Unavailable` shows the message and Retry and never the `None` text; all-candidates-unreadable gives `Unavailable`.
  - Confirm: preview figures; partial line only when applied < deposit amount; buttons disabled while submitting; `WRITE_BLOCKED` leaves the dialog open and shows `ui_write_blocked_maintenance`.
  - Each result in 3.3 maps to exactly one string resource; no text node shows a code name, an id, "0" or blank for an unavailable state.
  - `ChangeTypeDialog` on a linked row: info line plus disabled Confirm; `RefundLinkedExpenseException` shows `transactions_refund_linked_locked`.
  - Delete dialog shows the extra line only for a linked row.
  - Unlink: confirm text uses the link's applied amount; `Unlinked` and `NotLinked` both show `refund_unlinked`.
  - Touch targets at least 48dp; font scale 1.3 and 2.0 render with no clipped text and stacked buttons.
- **ViewModel tests**: the VM calls only `RefundLinkUseCase` (architecture test, INV-16); a candidate read that blocks gives `Unavailable(READ_BLOCKED)`; cancellation is rethrown; `mutatingExpenseIds` is released on every result.
- **Strings**: each of the 44 new keys present in en, es and el; es and el flagged DRAFT in the review note; `transactions_refund_likelihood_notice` changed in all three files; no hardcoded user text; no string built from a raw expense note.
- **Privacy**: log and diagnostic output in these flows contains only the bounded codes; no merchant, amount or note (AE-27).
- **Acceptance mapping**: AE-1 (apply), AE-2 (second refund), AE-3 (nothing to apply), AE-4 (excess), AE-5 (linked elsewhere, already linked), AE-6 (currency), AE-11 (installment, group), AE-12 (date), AE-13 (unlink), AE-14 and AE-15 (delete note), AE-16 (lock), AE-21 (candidate order), AE-22 (restore), AE-26 (no link without a tap), AE-28 (empty and not-eligible).

## 10. Open questions for the human (each with a recommended default)

1. **Link read API.** The details sheet and unlink confirm need a read-only `RefundLinkUseCase` read of one expense's links, which `domain-spec.md` 1.5 does not define. Default: add it to S3 (barrier-checked, `Available | Unavailable(READ_BLOCKED | READ_FAILED)`), no write path, no change to INV-16.
2. **Menu wording.** The brief says "This is a refund of..."; `decisions.md` D-17 says "Apply to purchase". Default: the overflow item says "This is a refund of..." and the notice button says "Apply to purchase"; both open the same sheet, whose title "Which purchase was refunded?" joins them.
3. **Chips show no counterpart name.** Default: yes (row-local, no per-row reads). Names appear in the sheets and confirm dialogs only. Alternative: a name in the chip needs a link read per row.
4. **Day-header totals net while rows show gross.** Default: no extra explanation in v1; the chips are the explanation. Revisit if testers are confused.
5. **Empty picker cannot say why.** A group-linked or otherwise ineligible deposit gets the same `None` text as "no purchase nearby". Default: accept; the message names the currency and window, which are the common causes. Alternative: add a bounded "deposit not eligible" result to `findCandidates`.
6. **Group flow string.** `GroupTransactionCoordinator` will refuse to link a refunded row (`REFUND_LINKED`, INV-19). The group screens need their own message (not in this doc's 42 strings). Default: add one string in the group flow slice; reuse the wording of `transactions_refund_linked_locked`.
7. **Unlink body with several refunds.** With more than one refund, "go back to {gross}" is wrong; the purchase goes back to its new net. Default: the amount shown is the purchase's net after removal, and the word is "show" (not "go back to") when other links remain. If the owner prefers one wording, `refund_unlink_body_format` needs a second plural form.
8. **Always confirm, no Undo.** Default: the confirm dialog always shows (unlike fc-paid-unlinked, which links on one tap) because the effect reaches past months; no Undo action because Remove link is the reverse. Alternative: one-tap with snackbar Undo, which needs a new undo operation in the domain.
9. **Tone in es.** The existing notice is formal ("edite"), other drafts are informal. Default: keep each file's existing tone for changed strings, informal for new ones. Owner to decide when signing off the 42 new and 1 changed strings (Q-1 in `decisions.md`).
10. **Menu item on every unlinked deposit.** Salary and other non-refund deposits also show "This is a refund of...". Default: yes, as D-17 says; the picker simply shows `None` or the user cancels. Alternative: hide it for deposits with no candidate in the loaded list (hides valid older purchases, so not recommended).
11. **Render path for `ChangeTypeDialog`.** Default: one shared notice composable with the action in both places. Alternative: notice text only in the dialog (no button), which leaves the new text pointing at an action that is not there, so not recommended.
