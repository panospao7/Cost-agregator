# UX flows: manual-entry-currency, increment 1 (currency control, rate preview, blocked save)

Author: ux-flow-designer (stage 4). Date 2026-10-16. Docs only; implementation post-1.0. Inputs: `brief.md` v2, `domain-spec.md` (INV/AE/OD cited), handoff Human decisions, `nl-quick-add/ux-flows.md`, loan `ux-flows.md` §3.1. `A` = `app/src/main/java/com/yourname/expensetracker`. Existing screens only: add-expense sheet (`ui/screens/addexpense/AddExpenseSheet.kt`) and receipt review (`ui/screens/receiptscan/ReceiptScanScreen.kt`). No new screen, route, permission, network call or setting. Strings: EN + el + es in the same slice (ai-fixes G2-7 precedent); Greek/Spanish are proposals for owner review.

## 1. Entry and navigation
- Entry unchanged: add-expense sheet (create mode) and receipt review. Back/close/rotation/process death unchanged; the sheet has no `SavedStateHandle` (F13), so process death resets the form and currency to home (AE-32). Currency never persists between opens (no last-used, no storage).
- Edit mode (INV-23): currency is read-only, no preview, no block. Amount precision follows the row's currency.
- "Open exchange-rate setting" navigates to the R23 part 1 setting (route owned by R23). See UX-Q-1 for what happens to the form.
- No deep link changes. The confirmation-gated `add` deep link never preselects a currency.

## 2. Shared currency control (add-expense and receipt review)
One composable, one list, one policy (`CurrencySelectionPolicy`, INV-10/11). Replaces the private 7-currency `CurrencyPicker` (`ReceiptScanScreen.kt:1385-1389`) and S11's EUR/USD segmented control.
- **Form:** a field-style button (`OutlinedButton`-like, min 48dp) showing symbol + code ("$ USD ▾"). Tap opens a `ModalBottomSheet` with a search field and a list. It is not a free-text field and not inside the amount field (the amount field's leading symbol follows the selection but is not tappable). Reason: 20+ currencies need search and long names; the sheet pattern is already used by loan declare sheet.
- **Add-expense layout:** `[ $ USD ▾ ] [ Amount ............ ]` in one Row (button fixed ~104dp, amount weight 1). At fontScale >= 1.3 or width < 360dp the Row stacks (control above, full width).
- **Receipt review layout:** keeps today's slot (labelled field below Total amount, above Date). Same control, same sheet, same preview/block block under it.
- **List order:** (1) "Your home currency" header + home (always present, even if inactive, INV-10); (2) "Other currencies" header + the rest sorted by code. No "recent" group in inc 1 (no new storage or DAO query; UX-Q-3). Row: symbol, code, localized name (platform display name), check icon on the selected row. Search matches code and localized name (case/accent-insensitive).
- **Omitted, not greyed:** BGN, RUB, HRK (and any non-selectable currency) do not appear unless home. No disabled rows, so nothing needs an explanation. Installment mode (see 8) restricts the list to EUR/USD and the sheet shows a one-line note.
- **Selection:** tap row, sheet closes, focus returns to the control, amount text kept. Dismiss without choosing leaves the selection unchanged. `Unset` shows "Choose currency" in error style (INV-11).
- Default = home on every open; while `homeCurrency == null` the control is disabled (same gate as Save today, S5-027).

### Wireframe A: add-expense, foreign currency, eligible
```
 [x]  Add Expense                                [ Save ]
 Merchant / Place  [ Hotel Central ................... ]
 ┌──────────┐ ┌─────────────────────────────────────┐
 │ $ USD  ▾ │ │ Amount       $ 20.00                 │
 └──────────┘ └─────────────────────────────────────┘
 ≈ €18.52 · ECB rate of 15 Oct
 Payment  [Card] [Cash] [Transfer]          (Repeat row hidden)
```
### Wireframe B: blocked (no rate, setting off)
```
 ┌──────────┐ ┌─────────────────────────────────────┐
 │ $ USD  ▾ │ │ Amount       $ 20.00                 │
 └──────────┘ └─────────────────────────────────────┘
 ┌────────────────────────────────────────────────────┐
 │ ⓧ No exchange rate for this date.                 │   <- errorContainer, icon + text
 │ Exchange-rate updates are off. Turn them on in    │
 │ settings to download rates.                       │
 │ [ Open exchange-rate setting ]  [ Change currency ]│   <- TextButtons, 48dp, wrap
 └────────────────────────────────────────────────────┘
```
### Wireframe C: picker sheet
```
 Choose currency                                  [x]
 [ 🔍 Search currency                          ]
 Your home currency
   € EUR   Euro                                  ✓
 Other currencies
   $ USD   US dollar
   £ GBP   Pound sterling
   ¥ JPY   Japanese yen
```

## 3. Preview line (INV-15, INV-3)
Single text line directly under the amount row (never inside the amount field's `supportingText`, which stays for amount errors).
- **Hidden** for home currency, `Unset`, edit mode, home loading, and while the amount is invalid.
- **Eligible + valid amount:** "≈ €18.52 · ECB rate of 15 Oct" (`CurrencyFormatter.formatMoney(roundSnapshot(amount x rate, home), home)`: the value the save stores as `baseAmount`, formatted by the same function as the list; home-currency decimals, e.g. a JPY-home user sees no decimals in the preview. RF-10, 2026-10-23). If `roundSnapshot` is `Unavailable`, no amount is shown (spec §12). What Save does then is OPEN (spec OD-6). "ECB" wording only when `sourceLabel = ECB`; otherwise "rate of 15 Oct". The date is the ECB `LocalDate`, rendered without any time zone; include the year when it is not the current year ("7 Oct 2025").
- **Eligible + amount empty:** rate date only ("ECB rate of 15 Oct"). Never "0" or a placeholder amount.
- **Evaluating:** "Checking exchange rate…" shown only if evaluation exceeds ~250 ms (local read, normally instant; avoids flicker). Latest inputs win (7.4).
- **Re-evaluates on:** currency, date, amount (figure only), home change, refresh completion (INV-3 triggers). Typing is never logged.
- The preview never needs network, so offline only matters for "Refresh rates" (section 4).

## 4. Blocked state (INV-6, INV-20; AE-3, 4, 22, 25)
Same inline card (Wireframe B) for preview-time and post-tap refusal. It appears under the amount row, replacing the preview line. All input (merchant, amount text, currency, date, category, notes) is kept; nothing is written.

| Variant (RateEligibility / RateRefusal) | Message | Actions |
|---|---|---|
| `None` / `NO_RATE` | "No exchange rate for this date." | see action rules |
| `Stale(latestRateDate)` / `STALE_RATE` | "No exchange rate for this date. The latest rate is from 7 Oct, more than 7 days earlier." | see action rules |
| `Unsupported` / `UNSUPPORTED_CURRENCY` | "This currency can't be used for new expenses. Choose another one." | Change currency |
| `HOME_UNRESOLVED` (save refusal only) | "Couldn't read your home currency. Nothing was saved. Try again." | Save (retry); no refresh |

**Action rules** (`ratesSetting` = On / Off / Unknown from R23; Unknown is treated as Off, fail-closed):
- Setting **On:** "Refresh rates" (user-started, reuses `CurrencyRatesRepository.refresh`, INV-20) + "Change currency". Running: button shows a progress indicator and "Refreshing rates…", disabled. On success the preview re-evaluates; if still blocked the card shows "Rates refreshed, but there is still no rate within 7 days of this date. Try a later date." (the AE-4 case: refresh adds only the latest date, so a past entry stays blocked; honest copy instead of a loop).
- Setting **Off/Unknown:** "Open exchange-rate setting" + "Change currency", plus the line "Exchange-rate updates are off. Turn them on in settings to download rates." Nothing enables the setting and no refresh button is shown.
- Every blocked card also says (secondary text): "You can also change the currency or the date." The user fixes it by changing date (the card clears as soon as the new date evaluates eligible) or currency.
- Refresh failure (bounded, existing refresh outcomes): offline "Couldn't refresh rates. Check your connection and try again."; setting turned off meanwhile = switch to the Off variant; BGN/RUB home (`HOME_CURRENCY_QUOTE_UNAVAILABLE`) "Rates can't be downloaded for your home currency." (no retry button); other "Couldn't refresh rates. Try again later." No raw exception text.

**Save button.** While the preview state is `None/Stale/Unsupported`, amount invalid, or currency `Unset`, Save is **disabled** with `stateDescription` ("Needs an exchange rate" / "Choose a currency" / "Fix the amount"), and the reason is visible in the card or field. While `Evaluating`, Save stays enabled (coordinator is authoritative, INV-6). **Post-tap refusal** (rate vanished or changed between preview and save, AE-22; or `HOME_UNRESOLVED`): the form stays open, `isSaving` clears, the card appears with the matching variant, the sheet scrolls it into view and the card is an assertive live region. Save then stays disabled until an input change or refresh re-evaluates. No toast, no extra dialog.

## 5. Per-currency amount validation (INV-12, 13, 14; AE-15..19)
- Shown as the amount field's `supportingText` with `isError`; text is kept as typed, never rounded or truncated. Checks run on the parsed value (R16 parser: JPY "1,500" ok, "1500,50" and "1.500" rejected).
- Excess decimals: "JPY amounts have no decimals. Enter a whole number." / "USD amounts allow up to 2 decimals."
- **Switching currency** re-validates the unchanged text immediately: "12.50" then JPY shows the error, switching back clears it (AE-16). No toast, no silent change.
- Cap: "Amount is too large. Maximum is ¥100,000,000." (cap formatted in the selected currency; independent of rate availability, AE-18).
- Not positive/blank: existing "Enter a valid amount" (localised by the quick-add slice).
- The amount field label must stop hard-coding "(€)" and "Euro" in its content description; the currency is carried by the control and the field's leading symbol (this also fixes the quick-add UX note about non-EUR homes).
- Amount error and rate card can both show: amount error first, preview/card suppressed while the amount is invalid.

## 6. Receipt review (INV-17, 11; AE-23..25)
- Same control/preview/block under Total amount. OCR proposes a currency: selected if selectable, otherwise `Unset` with "We couldn't match the receipt's currency. Choose one." (no echo of the OCR token, INV-19/privacy). Save disabled until chosen. Never swapped to home (INV-11).
- Eligible: save as today (one transaction, expense created and linked).
- **Refused save:** stays on the review screen, fields intact (merchant, amount, currency, date, category). Card as in section 4 plus the line "Your receipt is kept. Nothing was saved." The screen scrolls to the card (Save is at the bottom) and announces it assertively. The user changes currency/date or refreshes rates and saves again. No `ReceiptEvent`, no file removal (INV-17, section 8 of the spec). Closing the screen later behaves as abandoning review does today.
- Quick-save confirm refusal: no room for the card there; it opens the full review with the card shown (UX-Q-5).
- Batch review and email receipts: unchanged, no control (OD-5).

## 7. Recurring (INV-16; AE-26)
- Non-home currency selected: the "Repeat transaction" row is **hidden** (not disabled). Brief one-line note in its place: "Repeat is only available in your home currency (EUR)."
- Switching to a foreign currency while Repeat is on: Repeat turns **off and its frequency settings are cleared** in the same state update. The note becomes "Repeat was turned off because this expense is in another currency." (polite live region, stays while the currency is foreign). Switching back to home shows the Repeat row **off**; the user re-enables it. No dialog; reversible. Save can never carry a foreign recurrence.

## 8. Loan S11 merge (N2, OD-4; INV-18; AE-28)
- One control only. S11's EUR/USD segmented control and its plan-currency row (and the OD-14 "Financing cost won't be available when the currency differs" note) are removed; plan currency = expense currency.
- "Pay in installments" on: mode = INSTALLMENT, list = {EUR, USD} with the sheet note "Installments are available in EUR and USD only." If the current selection is outside that set (GBP selected, or home SEK), the control becomes `Unset` with "Choose EUR or USD for installments." and Save is disabled. Turning installments off restores the selection held before it was turned on (in-memory), if still selectable.
- Preview/block card appears under the amount, above the installments card, identical copy. A refused declaration is atomic: the card plus "Nothing was saved." S11's "FX unavailable, keep form" text is replaced by the shared copy. Installment auto-record is background and shows no UI here (AE-29).
- Transition: until this lands, S11's interim UI stays as approved. Loan plan/UX docs need a follow-up edit (route to the loan planner).

## 9. Interaction with quick add (nl-quick-add, same sheet)
- Quick-add line + Fill stay at the top; the currency region sits at Amount. Fill never changes the selected currency (INV-11, no guessing). Its amount is interpreted in the selected currency: the summary shows "amount 3.50 $" using the control's currency, and an amount with excess decimals for that currency is flagged inline (not rounded).
- **Obsolete copy:** `quick_add_hint_foreign` ("Foreign currency isn't supported here yet. ... saved in %1$s") and `quick_add_flag_amount_foreign` are only correct until this lands. Replace with "Foreign currency detected. Choose it next to the amount, then enter the amount." (nl-quick-add inc 1b later preselects the control). The "foreign blocked" state stays, with new copy.
- Keyboard: control sits before the amount in focus order; IME Done on amount unchanged.

## 10. UI state model
Per-screen sealed state `CurrencyRegionUi` (add-expense and receipt share it); no strings in state, only enums, codes and `LocalDate`.

| State | Produced by | Rendering | Save |
|---|---|---|---|
| `Loading` (home null) | S5-027 | control disabled, no preview | spinner (as today) |
| `ReadOnly` | edit mode, INV-23 | static chip, no preview | as today |
| `Home` | currency == home (AE-13) | no preview | enabled |
| `Unset(reason)` | `PROPOSAL_NOT_SELECTABLE`, installment mode switch, NONE_PROPOSED (UX-Q-2) | "Choose currency" error style, message per reason | disabled |
| `AmountInvalid(kind)` | `PrecisionExceeded`, `AboveCap`, `NotPositive` (INV-13/14) | field error, preview/card hidden | disabled |
| `Evaluating` | 7.4 | "Checking exchange rate…" after 250 ms | enabled |
| `Eligible(preview)` | `Eligible` | preview line (3) | enabled |
| `Blocked(variant, ratesSetting, refresh, origin)` | `None`/`Stale`/`Unsupported` (preview) or `RateRefusal` (save refusal, origin SAVE = assertive) | card (4) | disabled |
| `Blocked` + `refresh = Running/FailedX/DoneStillBlocked` | user-tapped refresh, INV-20 | button progress, bounded failure line | disabled |
| `Blocked(HOME_UNRESOLVED)` | save refusal only, INV-8, AE-14 | card, retry via Save | re-enabled for retry |

Mapping checks. **Spec states with no UI:** `Eligible.path` (DIRECT vs VIA) and `ageDays` (not shown, only the rate date); `CancellationException`/cancel mid-save (silent, AE-33); `InsertConflict`/duplicate keep today's UI (AE-31). **UI states with no spec cause:** `Evaluating` delay rule and `refresh` sub-states derive from existing refresh outcomes (bounded), not from the domain spec; "recurrence cleared note" derives from INV-16 plus AE-26. **Permission/consent:** no new permission; the R23 setting Off is the consent-denied analogue (`ratesSetting`). **Offline:** preview/save are local, only Refresh can fail offline. **Unavailable is never zero:** no amount is rendered for any non-Eligible state (INV-15).

## 11. Money display
- Every amount carries its currency: control (symbol + code), amount field symbol, preview "€18.52" via `CurrencyFormatter.formatMoney`. The amount in the original currency and the converted amount are never summed or shown as one number; no mixed totals on these screens.
- "≈" marks the preview as the app's rounding to home decimals (same function as the stored snapshot / list, INV-15; AE-38). Since money-correctness 1b (RF-10, 2026-10-23) the previewed figure is `roundSnapshot(amount x rate, home)`, the same value stored as `baseAmount`. The interim "display rounding only" rule has ended. Acceptance: the preview amount equals the stored snapshot, and the preview text equals the list text.
- Whole-unit currencies (JPY, KRW, ISK) show no decimals in field and caps. Large symbols ambiguous across currencies ($, kr) always appear with the code in the control.

## 12. Destructive or irreversible actions
- Switching to foreign clears Repeat (reversible by switching back and re-enabling; stated inline).
- "Open exchange-rate setting" may leave the form (UX-Q-1).
- Refresh never destructive; a refused save writes nothing and keeps the receipt (INV-6, 17). No undo needed.

## 13. Accessibility
- Control CD: "Currency: US dollar, USD. Double tap to change." (`Role.Button`). Read-only chip: "Currency USD. It can't be changed when editing." Sheet rows: "USD, US dollar" with state "selected". Search field has a label; empty result text is announced.
- Touch targets >= 48dp (control, list rows 56dp, TextButtons, Refresh). Content must not truncate at 200% font: Row stacks, card text and buttons wrap, no `maxLines`.
- Announcements: the preview/card is a **polite** live region triggered by currency, date, refresh or rate-state changes, **not** by each amount keystroke (the figure updates silently and is read on focus); the post-save refusal card is **assertive** and scrolled into view. Same pattern as the quick-add summary.
- Contrast >= 4.5:1: blocked card uses `errorContainer`/`onErrorContainer` plus an icon (never colour only); preview uses `onSurfaceVariant`. Navy surfaces to be checked by the implementer.
- TalkBack order: Merchant, currency control, Amount (with its error), preview or blocked card, its actions, Payment, Category, Date, Repeat/Installments. Receipt: Total amount, currency control, preview/card, Date.
- Never put the amount, merchant or OCR text into the control's or card's content descriptions beyond what the field itself reads. No amount, currency, date or rate in logs, events or diagnostics (INV-19).

## 14. Copy (string keys; EN / el / es). Prefix `mec_`.
| Key | EN | el | es |
|---|---|---|---|
| `mec_control_label` | Currency | Νόμισμα | Moneda |
| `mec_control_cd` | Currency: %1$s. Double tap to change. | Νόμισμα: %1$s. Πατήστε δύο φορές για αλλαγή. | Moneda: %1$s. Toca dos veces para cambiar. |
| `mec_control_unset` | Choose currency | Επιλέξτε νόμισμα | Elige una moneda |
| `mec_control_readonly_cd` | Currency %1$s. It can't be changed when editing. | Νόμισμα %1$s. Δεν αλλάζει κατά την επεξεργασία. | Moneda %1$s. No se puede cambiar al editar. |
| `mec_sheet_search` | Search currency | Αναζήτηση νομίσματος | Buscar moneda |
| `mec_sheet_home` / `_other` | Your home currency / Other currencies | Το νόμισμά σας / Άλλα νομίσματα | Tu moneda principal / Otras monedas |
| `mec_sheet_no_results` | No currency matches your search. | Κανένα νόμισμα δεν ταιριάζει με την αναζήτηση. | Ninguna moneda coincide con la búsqueda. |
| `mec_sheet_installment_note` | Installments are available in EUR and USD only. | Οι δόσεις είναι διαθέσιμες μόνο σε EUR και USD. | Los pagos a plazos solo están disponibles en EUR y USD. |
| `mec_installment_unset` | Choose EUR or USD for installments. | Επιλέξτε EUR ή USD για δόσεις. | Elige EUR o USD para pagos a plazos. |
| `mec_preview_checking` | Checking exchange rate… | Έλεγχος ισοτιμίας… | Comprobando el tipo de cambio… |
| `mec_preview_ecb` | ≈ %1$s · ECB rate of %2$s | ≈ %1$s · ισοτιμία ΕΚΤ της %2$s | ≈ %1$s · tipo del BCE del %2$s |
| `mec_preview_other` | ≈ %1$s · rate of %2$s | ≈ %1$s · ισοτιμία της %2$s | ≈ %1$s · tipo del %2$s |
| `mec_preview_rate_only` | ECB rate of %1$s | Ισοτιμία ΕΚΤ της %1$s | Tipo del BCE del %1$s |
| `mec_block_none` | No exchange rate for this date. | Δεν υπάρχει ισοτιμία για αυτή την ημερομηνία. | No hay tipo de cambio para esta fecha. |
| `mec_block_stale` | No exchange rate for this date. The latest rate is from %1$s, more than 7 days earlier. | Δεν υπάρχει ισοτιμία για αυτή την ημερομηνία. Η πιο πρόσφατη είναι της %1$s, πάνω από 7 ημέρες νωρίτερα. | No hay tipo de cambio para esta fecha. El más reciente es del %1$s, de hace más de 7 días. |
| `mec_block_unsupported` | This currency can't be used for new expenses. Choose another one. | Αυτό το νόμισμα δεν χρησιμοποιείται για νέα έξοδα. Επιλέξτε άλλο. | Esta moneda no se puede usar en gastos nuevos. Elige otra. |
| `mec_block_home_unresolved` | Couldn't read your home currency. Nothing was saved. Try again. | Δεν ήταν δυνατή η ανάγνωση του βασικού νομίσματος. Δεν αποθηκεύτηκε τίποτα. Δοκιμάστε ξανά. | No se pudo leer tu moneda principal. No se guardó nada. Inténtalo de nuevo. |
| `mec_block_off_hint` | Exchange-rate updates are off. Turn them on in settings to download rates. | Οι ενημερώσεις ισοτιμιών είναι ανενεργές. Ενεργοποιήστε τις στις ρυθμίσεις για λήψη ισοτιμιών. | Las actualizaciones de tipos de cambio están desactivadas. Actívalas en ajustes para descargar tipos. |
| `mec_block_alt_hint` | You can also change the currency or the date. | Μπορείτε επίσης να αλλάξετε νόμισμα ή ημερομηνία. | También puedes cambiar la moneda o la fecha. |
| `mec_action_refresh` / `_refreshing` | Refresh rates / Refreshing rates… | Ανανέωση ισοτιμιών / Ανανέωση ισοτιμιών… | Actualizar tipos / Actualizando tipos… |
| `mec_action_open_setting` | Open exchange-rate setting | Άνοιγμα ρύθμισης ισοτιμιών | Abrir ajuste de tipos de cambio |
| `mec_action_change_currency` | Change currency | Αλλαγή νομίσματος | Cambiar moneda |
| `mec_refresh_still_blocked` | Rates refreshed, but there is still no rate within 7 days of this date. Try a later date. | Οι ισοτιμίες ανανεώθηκαν, αλλά δεν υπάρχει ισοτιμία έως 7 ημέρες πριν από αυτή την ημερομηνία. Δοκιμάστε μεταγενέστερη. | Tipos actualizados, pero sigue sin haber un tipo a menos de 7 días de esta fecha. Prueba una fecha posterior. |
| `mec_refresh_offline` | Couldn't refresh rates. Check your connection and try again. | Η ανανέωση ισοτιμιών απέτυχε. Ελέγξτε τη σύνδεση και δοκιμάστε ξανά. | No se pudieron actualizar los tipos. Revisa la conexión e inténtalo de nuevo. |
| `mec_refresh_failed` | Couldn't refresh rates. Try again later. | Η ανανέωση ισοτιμιών απέτυχε. Δοκιμάστε αργότερα. | No se pudieron actualizar los tipos. Inténtalo más tarde. |
| `mec_refresh_home_unavailable` | Rates can't be downloaded for your home currency. | Δεν είναι δυνατή η λήψη ισοτιμιών για το βασικό σας νόμισμα. | No se pueden descargar tipos para tu moneda principal. |
| `mec_save_disabled_rate` / `_currency` | Needs an exchange rate / Choose a currency | Χρειάζεται ισοτιμία / Επιλέξτε νόμισμα | Necesita un tipo de cambio / Elige una moneda |
| `mec_amount_no_decimals` | %1$s amounts have no decimals. Enter a whole number. | Τα ποσά σε %1$s δεν έχουν δεκαδικά. Εισάγετε ακέραιο αριθμό. | Los importes en %1$s no tienen decimales. Introduce un número entero. |
| `mec_amount_max_decimals` | %1$s amounts allow up to %2$d decimals. | Τα ποσά σε %1$s δέχονται έως %2$d δεκαδικά. | Los importes en %1$s admiten hasta %2$d decimales. |
| `mec_amount_too_large` | Amount is too large. Maximum is %1$s. | Το ποσό είναι πολύ μεγάλο. Το μέγιστο είναι %1$s. | El importe es demasiado grande. El máximo es %1$s. |
| `mec_repeat_home_only` | Repeat is only available in your home currency (%1$s). | Η επανάληψη διατίθεται μόνο στο βασικό σας νόμισμα (%1$s). | La repetición solo está disponible en tu moneda principal (%1$s). |
| `mec_repeat_cleared` | Repeat was turned off because this expense is in another currency. | Η επανάληψη απενεργοποιήθηκε επειδή το έξοδο είναι σε άλλο νόμισμα. | Se desactivó la repetición porque este gasto está en otra moneda. |
| `mec_receipt_unmatched` | We couldn't match the receipt's currency. Choose one. | Δεν αναγνωρίστηκε το νόμισμα της απόδειξης. Επιλέξτε ένα. | No pudimos identificar la moneda del recibo. Elige una. |
| `mec_receipt_not_detected` | No currency found on the receipt. Check it. | Δεν βρέθηκε νόμισμα στην απόδειξη. Ελέγξτε το. | No se encontró moneda en el recibo. Revísala. |
| `mec_receipt_kept` | Your receipt is kept. Nothing was saved. | Η απόδειξη διατηρήθηκε. Δεν αποθηκεύτηκε τίποτα. | Se conserva tu recibo. No se guardó nada. |
| `mec_declaration_nothing_saved` | Nothing was saved. | Δεν αποθηκεύτηκε τίποτα. | No se guardó nada. |
| `quick_add_hint_foreign` (replaced) | Foreign currency detected. Choose it next to the amount, then enter the amount. | Εντοπίστηκε ξένο νόμισμα. Επιλέξτε το δίπλα στο ποσό και εισάγετε το ποσό. | Moneda extranjera detectada. Elígela junto al importe y escribe el importe. |

Copy rules: errors name the problem and the action, never echo OCR/notification text, merchant, or amounts in diagnostics; the dates in messages are public ECB dates shown on screen only.

## 15. Open questions
- **UX-Q-1 Open setting vs the form.** Navigating away may discard the sheet (no `SavedStateHandle`). Rec: if the sheet's host keeps its back stack/VM, keep the form; otherwise confirm first when the form has an amount ("Open settings? Your entry will be discarded.") and never auto-enable. Impact-mapper to confirm hosting; owner to confirm acceptable. Alternative later: in-sheet consent dialog (R23 owner).
- **UX-Q-2 Receipt with no OCR currency.** Spec leaves `NONE_PROPOSED` undefined for receipts. Rec: preselect home plus caption `mec_receipt_not_detected` (today's behaviour, user still sees and confirms it), deviating from a strict `Unset`. Fallback: `Unset` for every receipt without currency (safest, extra tap). Domain-modeler to align.
- **UX-Q-3 Recent currencies.** Rec: none in inc 1 (home first, rest by code). A "used before" group would need a new read of existing expenses (no new storage), a candidate for a later increment.
- **UX-Q-4 Refresh on a stale card.** Refresh can't unblock a past date (AE-4) and is shown anyway for recent dates, with honest "still no rate" copy after. Rec: keep; alternative: hide Refresh when the expense date is more than 7 days before today (clearer, but needs a "today" input in UI state).
- **UX-Q-5 Quick-save confirm surface.** Rec: a refused quick-save opens full review with the card. Confirm the surface exists as described (F12) in the receipt slice.
- **UX-Q-6 Installments with an unsupported selection.** Rec: spec behaviour (`Unset`, restore on switch-off) rather than disabling the switch (which would lock out non-EUR/USD homes). Loan plan/UX docs need follow-up for the removed plan-currency row and OD-14 note.
- **UX-Q-7 R23 setting copy.** Names and wording of the setting are R23 part 1's; `mec_action_open_setting`/`mec_block_off_hint` must match it. Greek/Spanish copy needs native review.

---

## 1b — Rate history download

Author: ux-flow-designer (MEC 1b design). Date 2026-10-30. Docs only. Frame: `brief.md:18, 38-39, 84`; `critique.md:37, 54, 60`; `domain-spec.md` §10 (`:301-310`) and "1b - ECB 90-day history (domain rules)", which is authoritative where the two differ.
Reconciled 2026-10-30 per owner-approvals 94 (D-1b-1..6). Every earlier `[ALIGN]` mark is resolved below and cites the INV that settles it. Domain rules win for the Refresh/Download choice (D-1b-1, INV-1b-5), the home change (D-1b-2, INV-1b-20) and the reason-to-state mapping (D-1b-5); the UX wins for Cancel (D-1b-3, INV-1b-21). The Currency-management "Rate history" row is DEFERRED (D-1b-4, F-1b-CURMGMT): every Currency-management mention in this section is parked design, not 1b scope, and INV-1b-3 holds (the download starts only from add-expense and receipt review). Existing surfaces only: the shared blocked card (`ui/components/currency/CurrencyRateRegion.kt`, `BlockedRateCard`), used by add-expense (`AddExpenseSheet.kt`, VM `refreshRates()` `AddExpenseViewModel.kt:1424`) and receipt review (`ReceiptScanScreen.kt:1046`, VM `ReceiptScanViewModel.kt:1704`), plus an optional row on Currency management (`CurrencyManagementScreen.kt`, VM `refreshRates()` `CurrencyManagementViewModel.kt:471`). No new screen, route, deep link, permission, setting, worker or background job. Network: only the same ECB host, only while the R23 setting is On, only on a user tap (INV-20). Sections 1–15 above stay authoritative for everything this section does not change.

### 1b.1 Entry points and navigation
- **Primary entry: the blocked card** (Wireframe B family), variants `NONE` and `STALE` only. `UNSUPPORTED` and `HOME_UNRESOLVED` never offer a download.
- **Secondary entry: none in 1b.** The Currency-management "Rate history" row is DEFERRED (D-1b-4, follow-up F-1b-CURMGMT); see 1b.3. INV-1b-3 holds.
- Never offered (the download): edit mode (no region), home currency selected, `Unset`, `AmountInvalid` (the card is suppressed while the amount is invalid, section 5), setting Off/Unknown.
- **Action rule.** The VM supplies one bounded `rateFixAction` (the composable never compares dates). The VM does not decide the remedy: it copies the domain `RateRemedy` computed by `RateRemedyPolicy` (INV-1b-5) from the entry's calendar date in the session's `entryZone`, `today` in the same zone (`ratesToday`, already in state), the latest stored pair date (supplied on `Blocked`) and the session flag. The 90-day reach is the policy's `today - 90 days` bound (INV-1b-5); the UI never hard-codes it (the copy says "90 days", and the copy changes if the bound does). Domain rule (D-1b-1): **Refresh comes first.** `REFRESH_LATEST` when the stored table is empty or the latest stored date is on or before the entry date; `DOWNLOAD_HISTORY` only when the latest stored date is after the entry date (and the entry date is inside the window and no history download succeeded this session). Download never replaces Refresh. Naming: `NONE`, `REFRESH_LATEST` and `DOWNLOAD_HISTORY` are the domain `RateRemedy` values; `OPEN_SETTING` and `TOO_OLD` are UX-only presentation states (table below).

| Setting | Domain input (`RateRemedyPolicy`, INV-1b-5) | `rateFixAction` | Origin | Card shows |
|---|---|---|---|---|
| On | Blocked (`None`/`Stale`); stored table empty, or latest stored date <= entry date | `REFRESH_LATEST` | domain `RateRemedy` | "Refresh rates" (unchanged, section 4) |
| On | Blocked; latest stored date > entry date; entry date >= today - 90 days; no successful history download this session | `DOWNLOAD_HISTORY` | domain `RateRemedy` | "Download rate history" + disclosure line. Refresh is not shown with it: the latest stored date is already after the entry date, so the daily file can't help |
| On | `NONE` with `OUTSIDE_HISTORY_WINDOW` (entry date < today - 90 days), no download attempted yet | `TOO_OLD` | UX-only presentation of `NONE` | `mec_history_too_old`, no network action |
| On | `NONE` with `HISTORY_ALREADY_DOWNLOADED` | `NONE` | domain `RateRemedy` | the status line chosen by the mapping in 1b.2 (`DONE_TOO_OLD` or `DONE_NO_RATE`), no network action |
| On | `NONE` after `FAILED_HOME_UNAVAILABLE` (BGN/RUB home; the policy doesn't know the home can't be quoted) | `NONE` | UX override | `mec_refresh_home_unavailable`, no network action |
| On | `Home`, `Eligible` or `Unsupported` | `NONE` | domain `RateRemedy` | no network action; the existing card variants only |
| Off / Unknown | not consulted (INV-1b-3: the policy is used only when the setting is On) | `OPEN_SETTING` | UX-only | unchanged: "Open exchange-rate setting" + the existing confirm dialog (`mec_open_setting_confirm_title/body`, D-11, C-1) |

  "Change currency" stays on every row. This resolves UX-Q-4 by the domain rule: Refresh is offered only while the daily file can still add a helpful date (latest stored date on or before the entry date); a backdated date whose latest stored date is later gets the history download instead (AE-4). `mec_refresh_still_blocked` remains only for `REFRESH_LATEST`.

  **First-use two-step (AE-1b-5d).** Empty table, entry dated 5 Oct, setting On:
  1. The table is empty, so the remedy is `REFRESH_LATEST`. The card shows [Refresh rates] only (no history button, no 90-day disclosure).
  2. The user taps Refresh. The daily file stores the latest publication (16 Oct). The region is still blocked, and the latest stored date 16 Oct is after the entry date 5 Oct, so the remedy becomes `DOWNLOAD_HISTORY`. The card now shows [Download rate history] with the disclosure.
  3. The user taps Download. On `Stored` the region re-evaluates to `Eligible` and the preview line appears (1b.2).
  Two taps is deliberate (domain-spec 1b.2 note): the small daily file first, and the larger history file only when the daily file can't help.
- **Navigation.** The download happens in place; the sheet or review screen stays open and keeps every input. Back/close while downloading cancels the download (1b.4). The open-setting path, its confirm and its discard behaviour are unchanged; after the user turns the setting on and comes back, the form starts fresh (C-1) and the card offers the download on the next blocked evaluation.

### Wireframe D: blocked, backdated date, setting On
```
 ┌──────────┐ ┌─────────────────────────────────────┐
 │ $ USD  ▾ │ │ Amount       $ 20.00                 │
 └──────────┘ └─────────────────────────────────────┘
 ┌────────────────────────────────────────────────────┐
 │ ⚠ No exchange rate for this date. The latest rate │  <- live region (polite; assertive if from Save)
 │   is from 7 Oct, more than 7 days earlier.        │
 │ You can also change the currency or the date.     │
 │ Downloads the last 90 days of public ECB exchange │  <- disclosure, outside the live region
 │ rates. No personal data is sent.                  │
 │ [ Download rate history ]  [ Change currency ]    │  <- TextButtons, 48dp, wrap
 └────────────────────────────────────────────────────┘
```
### Wireframe E: downloading
```
 ┌────────────────────────────────────────────────────┐
 │ ⚠ No exchange rate for this date. …               │
 │ You can also change the currency or the date.     │
 │ Downloads the last 90 days of public ECB …        │
 │ [ ◌ Downloading rate history… ] (disabled)        │
 │ [ Cancel ]  [ Change currency ]                   │
 └────────────────────────────────────────────────────┘
```
### 1b.2 Download states and behaviour (add-expense and receipt review, one composable)
- **Idle** (`historyStatus = IDLE`, `rateFixAction = DOWNLOAD_HISTORY`): Wireframe D. Save disabled ("Needs an exchange rate", section 4).
- **Downloading** (`RUNNING`): Wireframe E. Indeterminate `CircularProgressIndicator` in the button (same pattern as Refresh; no percentage, the payload size is not known up front). The button is disabled and reads "Downloading rate history…". A **Cancel** TextButton appears (yes, cancellable: the file is larger than the daily one and slow networks are the travel case). All form inputs stay editable; Save stays disabled while the region is blocked. No toast, no snackbar, no sheet-level spinner.
- **Cancelled** (`CANCELLED`; domain outcome `Cancelled`, INV-1b-21): the user's Cancel cancels the download job (the VM rethrows `CancellationException`, never swallowed); the card returns to Idle with the line "Download stopped." The button is enabled again (retry = another tap) and the session flag stays unset. The store is all-or-nothing (INV-1b-11): a cancel before the commit stores nothing, and a cancel during it leaves zero rows or all rows, never a subset. Which of those happened is invisible to the user and harmless (public rates, idempotent REPLACE). Leaving the screen cancels the same way but shows no status.
- **Success, now eligible:** the VM re-evaluates the region with the **latest** inputs (`refreshCompleted = true` path, the same as Refresh). The card disappears and the existing preview line appears ("≈ €18.52 · ECB rate of 15 Oct"). The completion **bumps the announce key once**, so the preview line is announced politely, exactly as for a date change (F-B2-A11Y-1 latch; no extra "downloaded" announcement, so no rate or amount is spoken beyond the existing preview). Save becomes enabled. `historyStatus` returns to `IDLE`.
- **Success, still blocked** (the download stored rows but the date still has no eligible rate). Two bounded sub-states, chosen by the mapping table below (D-1b-5), from the re-evaluation's `NoRemedyReason` (INV-1b-5) and the session's `Stored.earliestDate` (domain-spec 1b.7):
  - `DONE_TOO_OLD`: "Rate history covers only the last 90 days. Choose a later date or another currency." The download button is hidden (it can't help).
  - `DONE_NO_RATE`: "The rate history has no rate for this currency within 7 days of this date. Choose another currency or date." (the ECB stopped or paused quoting it inside the window). Button hidden for this date/currency; it returns if either input changes and the new evaluation is again `DOWNLOAD_HISTORY`.
  The card message itself (none/stale + latest date) re-renders from the new evaluation, so a newer "latest rate" date can appear; that date is a public ECB date shown on screen only.
- **Offline** (`FAILED_OFFLINE`, an `IOException` class): "Couldn't download rate history. Check your connection and try again." Button enabled again (retry = tap it). Preview/save never need network, so nothing else changes.
- **Failed** (`FAILED`: timeout, HTTP error, malformed or oversized file, no rows stored, any other bounded code): "Couldn't download rate history. Try again later." Button enabled. No exception text, URL, HTTP code or file content is shown or logged (INV-19).
- **Home rates not downloadable** (`FAILED_HOME_UNAVAILABLE`, BGN/RUB home): reuse `mec_refresh_home_unavailable`; button hidden for the rest of the session (`rateFixAction = NONE`).
- **Setting turned off meanwhile** (`DisabledByPrivacy`) or **privacy check failed** (`PrivacyCheckFailed`): no failure line; `ratesSetting` becomes Off/Unknown and the card switches to the Off variant (open-setting path), the same as Refresh today.
- **Setting Off from the start:** unchanged section 4 Off variant. Nothing enables the setting; the download action is never shown.

**Outcome-to-state mapping (D-1b-5).** This is the only definition of the mapping; domain-spec 1b.7 refers here. The domain values are the `CurrencyRateHistoryOutcome` variants, the `Failed` reasons (INV-1b-17) and `NoRemedyReason` (INV-1b-5).

| Domain value | UX state (`historyStatus`) | Rendering |
|---|---|---|
| `Stored`, then re-evaluation `Eligible` | none (`IDLE`; the region becomes `Eligible`) | preview line (above) |
| Still blocked with `NoRemedyReason = OUTSIDE_HISTORY_WINDOW`, or `HISTORY_ALREADY_DOWNLOADED` with entry date < the session's `Stored.earliestDate` | `DONE_TOO_OLD` (before any download in the session it is the `TOO_OLD` action, same copy) | `mec_history_too_old`, no button |
| Still blocked, any other case (including `HISTORY_ALREADY_DOWNLOADED` with entry date on or after the earliest stored date) | `DONE_NO_RATE` | `mec_history_no_rate`, no button |
| `Failed(OFFLINE)` | `FAILED_OFFLINE` | `mec_history_offline`, button enabled |
| `Failed(INVALID_HOME_CURRENCY)`, `Failed(HOME_QUOTE_UNAVAILABLE)` | `FAILED_HOME_UNAVAILABLE` | `mec_refresh_home_unavailable`, no button |
| Every other `Failed` reason (`TIMEOUT`, `HTTP_ERROR`, `PAYLOAD_*`, `STORE_FAILED`), and `AlreadyInProgress` (a backstop D-1b-5 doesn't list) | `FAILED` | `mec_history_failed`, button enabled |
| `Cancelled` (user Cancel, INV-1b-21) | `CANCELLED` | `mec_history_cancelled`, button enabled |
| `DisabledByPrivacy`, `PrivacyCheckFailed` | Off variant (`OPEN_SETTING`) | no failure line (above) |

**Receipt review parity (MEC C).** Same card, same `rateFixAction`, same states, same copy, driven by `ReceiptScanViewModel` with the receipt's entry date. Differences: (1) a post-Save refusal card still adds "Your receipt is kept. Nothing was saved." and is assertive; the download that follows uses the polite rules above; (2) a refused quick-save opens full review with the card (UX-Q-5), where the download is offered; (3) leaving the review screen cancels a running download, and the receipt state is unchanged either way (section 8 of the spec: no `ReceiptEvent`, no file change). Batch review and email receipts: no control, no download (OD-5).

### 1b.3 Currency management "Rate history" row: DEFERRED (D-1b-4, follow-up F-1b-CURMGMT)
**Not in 1b scope.** The row is not built in 1b. INV-1b-3 allows the download only from add-expense and receipt review, and domain-spec 1b.11 has no Currency-management owner. The rest of this subsection is parked design for the follow-up, kept so the work isn't redone; it is not a requirement and nothing in it is built, tested or reviewed in 1b.
- Placement: a card directly under the "Exchange rates" header row (`CurrencyManagementScreen.kt:285-307`), above the rates list. Shown only when the setting is On and the home currency is resolved (`homeCurrency != null`) and downloadable; hidden (not disabled) when `rateDownloadDisabled`, where today's disabled message already explains why.
- Content: title "Rate history", the disclosure line, one `OutlinedButton` "Download rate history" (48dp, full width at fontScale >= 1.3), then a status line.
- States: Idle; Downloading (button progress + Cancel, as Wireframe E; the toolbar Refresh icon is disabled while it runs, so the two downloads never overlap); Done "Rate history downloaded." or, with the range from `Stored.earliestDate` and `Stored.latestDate` (INV-1b-17), "Rate history downloaded. Rates from 2 Aug to 30 Oct are available."; Offline/Failed/Home-unavailable with the same keys and mapping as 1b.2; setting turned off meanwhile → the row hides and today's disabled message shows. After Done the screen reloads its data (`loadCurrencyData()`, as after Refresh).
- No preview, no amounts, no per-currency list of what was stored.

```
 Exchange rates                          Updated Oct 30, 17:05
 ┌────────────────────────────────────────────────────┐
 │ Rate history                                       │
 │ Downloads the last 90 days of public ECB exchange │
 │ rates. No personal data is sent.                  │
 │ [        Download rate history         ]          │
 │ Rate history downloaded.                          │  <- polite live region (status line only)
 └────────────────────────────────────────────────────┘
```

### 1b.4 Edge cases
- **Double taps.** The button is disabled from the first tap; the VM also ignores a start while `historyStatus == RUNNING` or `rateRefresh == RUNNING` (same guard as `refreshRates()`), and Refresh and Download are never shown together. One job per screen; the repository is single-flight (INV-1b-6), so a second entry screen (for example in multi-window) that gets `AlreadyInProgress` shows `FAILED` (mapping in 1b.2) and the user may tap again. Cancel (D-1b-3) is offered only while `RUNNING`.
- **Inputs change while downloading.** Allowed. The download continues; on completion the region re-evaluates with the latest currency/date/amount (latest inputs win, spec 7.4). If the user switched to home currency or an eligible date meanwhile, the card is already gone: the download finishes silently, no announcement (the announce key is bumped only if the region is foreign after completion).
- **Rotation / configuration change.** The VM survives; `RUNNING` is shown again after recreation and the result lands normally. Nothing new goes into a `SavedStateHandle`.
- **Process death during download.** The form resets (F13, AE-32) and the job dies. No resume, no retry on next launch, no background continuation. The store is all-or-nothing (INV-1b-11), so the table holds zero rows or all rows of the download; they are public and harmless. Next open evaluates fresh.
- **Sheet/screen closed during download.** Cancelled (`onSheetLeft` for add-expense, screen exit for receipt review), so no network happens after the user leaves. No status is shown (the screen is gone).
- **Home currency changed during an entry (F-B2-HOME-CHANGE; D-1b-2, INV-1b-20).** The home currency can change while a sheet session or a retained draft exists (Currency management, multi-window, a restore). The domain rule wins: a home change never re-denominates an amount, and a draft whose denomination would change is discarded, never re-denominated or pinned. For H1 to H2, the same whether the sheet is open or between sessions (`onSheetOpened`):
  - Blank amount: the selection follows the new home silently (nothing to re-denominate). INV-1b-20(a).
  - A **foreign** selection X: kept. The region re-evaluates against the new home (announce key bump, polite). If X equals the new home, the selection becomes `Selected(H2, home)` (same code, amount kept) and the region becomes `Home`. INV-1b-20(b).
  - Home selected and an amount typed: the whole draft is discarded through the existing `reset()` path (as in R-1(b)): blank amount, the new home selected. One polite note is shown under the control, `mec_home_changed_cleared` ("Your home currency changed, so the amount was cleared."), dismissed by the first edit or currency change. H1 is never pinned as a foreign override. INV-1b-20(c). This is the only place 1b discards typed input. Installment mode follows the same rule.
  - While SAVING the rule waits: the request already carries the explicit code H1 and the coordinator's home is authoritative (spec §12). After `Created` the sheet closes; after a refusal or error the discard rule applies.
  - Receipt review keeps its explicit (proposed or picked) code. If its selection could follow home implicitly it becomes `Unset` with the existing Unset treatment (Save disabled) and the receipt's fields are kept; there is no home-change note key for it.
  - A download running across the change completes; the stored data covers all cross pairs (spec §10), so the result is valid for the new home.
- **Future-dated entries.** `REFRESH_LATEST` (unchanged); history can't produce a rate dated after today.
- **Date older than history reach before any download.** `TOO_OLD` is shown up front (no wasted download), with the same copy as `DONE_TOO_OLD`.
- **Installment mode / loan declare.** Same card and download (section 8); no other change.

### 1b.5 UI state model (additions only)
`CurrencyRegionUi` is unchanged (no new sealed variant; a download never makes an amount appear outside `Eligible`). Two bounded inputs are added next to `ratesSetting` / `rateRefresh` on the add-expense and receipt states, and passed to `CurrencyRateRegionContent`:
- `rateFixAction: CurrencyRateFixAction { NONE, REFRESH_LATEST, DOWNLOAD_HISTORY, OPEN_SETTING, TOO_OLD }` (1b.1 table). `NONE`, `REFRESH_LATEST` and `DOWNLOAD_HISTORY` are the domain `RateRemedy` values from `RateRemedyPolicy` (INV-1b-5), copied through; `OPEN_SETTING` (setting Off/Unknown) and `TOO_OLD` (`NONE` with `OUTSIDE_HISTORY_WINDOW`, before any download) are UX-only presentation states. The VM computes no date bound itself.
- `historyStatus: CurrencyHistoryStatus { IDLE, RUNNING, CANCELLED, DONE_TOO_OLD, DONE_NO_RATE, FAILED_OFFLINE, FAILED_HOME_UNAVAILABLE, FAILED }`. Each non-`IDLE`, non-`RUNNING` value is produced from a domain outcome by the mapping table in 1b.2 (D-1b-5). Success-and-eligible has no status of its own: the region becomes `Eligible` and the status goes back to `IDLE`.
- Add-expense home-change note: one one-shot flag `homeChangeClearedNote: Boolean`, set when INV-1b-20(c) discards a draft and cleared by the first edit or currency change. The former `homeChangeNote { NONE, KEPT, UNSET }` enum is removed (D-1b-2).

| UI state | Produced by (domain / data cause) | Rendering | Save |
|---|---|---|---|
| `Blocked` + `DOWNLOAD_HISTORY` + `IDLE` | `RateEligibility.None/Stale`, setting On, `RateRemedy = DOWNLOAD_HISTORY` (INV-1b-5) | Wireframe D | disabled |
| `+ RUNNING` | user tap, INV-1b-3 | Wireframe E | disabled |
| `+ CANCELLED` | `Cancelled` outcome: user Cancel (INV-1b-21); leaving the screen shows nothing | Idle + "Download stopped." | disabled |
| `Eligible` (after download) | re-evaluation finds a rate ≤ 7 days, INV-1 | preview line, one polite announcement | enabled |
| `+ DONE_TOO_OLD` / `TOO_OLD` | mapping table (D-1b-5): `OUTSIDE_HISTORY_WINDOW`, or `HISTORY_ALREADY_DOWNLOADED` with entry date before the earliest stored date | card + `mec_history_too_old`, no button | disabled |
| `+ DONE_NO_RATE` | mapping table (D-1b-5): still blocked, any other case | card + `mec_history_no_rate`, no button | disabled |
| `+ FAILED_OFFLINE` | `Failed(OFFLINE)` | card + offline line, button enabled | disabled |
| `+ FAILED` | every other `Failed` reason, and `AlreadyInProgress` (mapping table) | card + generic line, button enabled | disabled |
| `+ FAILED_HOME_UNAVAILABLE` | `Failed(INVALID_HOME_CURRENCY)` / `Failed(HOME_QUOTE_UNAVAILABLE)` | card + home line, no button | disabled |
| Off variant | `DisabledByPrivacy` / `PrivacyCheckFailed` / setting Off (consent-denied analogue) | open-setting path | disabled |
| Add-expense home change | INV-1b-20(c): home selected with an amount, home changed (`homeChangeClearedNote`) | blank form, new home selected, `mec_home_changed_cleared` note | disabled (blank amount) |
| `Unset` after home change (receipt review) | INV-1b-20: selection followed home implicitly; receipt fields kept | existing Unset treatment, no home-change note | disabled |

Mapping checks. **Spec states with no UI:** the stored row count and date range (not shown in 1b; the Currency-management range line is parked, 1b.3); single-flight inside the repository (it surfaces only as the `AlreadyInProgress` backstop, shown as `FAILED`); atomicity of the store. **UI states with no spec cause:** none left. `TOO_OLD`, `DONE_TOO_OLD` and `DONE_NO_RATE` come from `NoRemedyReason` and `Stored.earliestDate` (D-1b-5); `CANCELLED` comes from the `Cancelled` outcome (INV-1b-21); the home-change note comes from INV-1b-20 (D-1b-2). **Permission:** none new. **Offline:** only the download (and Refresh) can be offline; preview and save stay local. **Unavailable is never zero:** no state except `Eligible` renders an amount.

### 1b.6 Money display, destructive actions
- Money display is unchanged (section 11). The download never shows a rate value, a count of rates, or an amount; the only figure is the existing preview after success. Rate dates in copy are public ECB `LocalDate`s formatted by `formatRateDate` (year when not the current year).
- Nothing is destructive. The download only adds or replaces public rate rows; it never touches expenses or receipts and needs no undo. Cancel is always available while it runs. The open-setting confirm (which discards the form) is unchanged. The between-sessions home-change discard (1b.4) reuses the existing reset path and is the same accepted behaviour as R-1(b).

### 1b.7 Accessibility
- **TalkBack order inside the card:** message (icon decorative) → alt hint → status line (cancelled / still blocked / failure) → disclosure → [Download rate history] or [Downloading rate history…] → [Cancel] → [Change currency]. The disclosure comes **before** the button so the user hears what the tap does first.
- **Live regions:** the existing merged message group stays the only live region on the card (polite, or assertive when the card came from Save). The status line sits **inside** that group so outcomes are announced politely once. The disclosure sits **outside** it, so it isn't re-announced on every status change. Success is announced only through the preview line (announce-key bump, 1b.2). Never assertive for a download outcome. The home-change note is polite. Currency management: only the status line is a polite live region.
- **Button semantics:** while running, the button is disabled with `stateDescription` "Downloading rate history" so focus that stays on it hears the change. Cancel has the content description "Cancel rate history download" (the visible label "Cancel" alone is ambiguous next to other buttons).
- **Focus on success:** the focused button disappears when the card is replaced. Move accessibility focus to the preview line if the download button or Cancel had focus. If that isn't feasible in Compose, the polite announcement is enough, and focus must not jump to the top of the sheet.
- Touch targets ≥ 48dp. Card buttons wrap (FlowRow or Column), no `maxLines`, nothing truncated at 200% font. Contrast: `onErrorContainer` on `errorContainer` as today. The progress indicator uses `onCard` and is decorative (the label carries the state).
- The F-A11Y-LATCH-PURE follow-up applies: the extra key bump on download completion must go through the same latch. Review it at the 1b candidate.

### 1b.8 Privacy disclosure and diagnostics
- One line, shown wherever the download action is shown (card and Currency management): **"Downloads the last 90 days of public ECB exchange rates. No personal data is sent."** It is accurate only if the request carries no app-specific query, header or identifier beyond a plain GET to the ECB host (INV-1b-1 and INV-1b-19: one constant URL, no query, body, cookie or app-specific header, no redirect followed). If the review finds otherwise, the copy must change, not the request.
- The fetch timing reveals a backdated-entry attempt (brief `:39`). This is accepted as residual and not disclosed in the UI. It goes to the privacy review (UX-1b-Q-4).
- Diagnostics: bounded codes only (for example a history outcome enum). Never the entry date, the currency, the amount, the URL, the HTTP body or the exception message. Nothing from OCR or receipt text appears in any history copy or log. The receipt card shows no OCR token (section 6).

### 1b.9 Copy (new keys; EN final for review, **el / es DRAFT** pending the owner's es/el sign-off)
| Key | EN | el (DRAFT) | es (DRAFT) |
|---|---|---|---|
| `mec_history_action` | Download rate history | Λήψη ιστορικού ισοτιμιών | Descargar historial de tipos |
| `mec_history_action_running` | Downloading rate history… | Λήψη ιστορικού ισοτιμιών… | Descargando historial de tipos… |
| `mec_history_running_state` | Downloading rate history | Λήψη ιστορικού ισοτιμιών σε εξέλιξη | Descargando historial de tipos |
| `mec_history_cancel` | Cancel | Ακύρωση | Cancelar |
| `mec_history_cancel_cd` | Cancel rate history download | Ακύρωση λήψης ιστορικού ισοτιμιών | Cancelar la descarga del historial de tipos |
| `mec_history_disclosure` | Downloads the last 90 days of public ECB exchange rates. No personal data is sent. | Κατεβάζει τις δημόσιες ισοτιμίες της ΕΚΤ των τελευταίων 90 ημερών. Δεν αποστέλλονται προσωπικά δεδομένα. | Descarga los tipos de cambio públicos del BCE de los últimos 90 días. No se envían datos personales. |
| `mec_history_cancelled` | Download stopped. | Η λήψη διακόπηκε. | Descarga detenida. |
| `mec_history_too_old` | Rate history covers only the last 90 days. Choose a later date or another currency. | Το ιστορικό ισοτιμιών καλύπτει μόνο τις τελευταίες 90 ημέρες. Επιλέξτε μεταγενέστερη ημερομηνία ή άλλο νόμισμα. | El historial de tipos solo cubre los últimos 90 días. Elige una fecha posterior u otra moneda. |
| `mec_history_no_rate` | The rate history has no rate for this currency within 7 days of this date. Choose another currency or date. | Το ιστορικό δεν έχει ισοτιμία για αυτό το νόμισμα έως 7 ημέρες πριν από αυτή την ημερομηνία. Επιλέξτε άλλο νόμισμα ή ημερομηνία. | El historial no tiene un tipo para esta moneda a menos de 7 días de esta fecha. Elige otra moneda u otra fecha. |
| `mec_history_offline` | Couldn't download rate history. Check your connection and try again. | Η λήψη ιστορικού ισοτιμιών απέτυχε. Ελέγξτε τη σύνδεση και δοκιμάστε ξανά. | No se pudo descargar el historial de tipos. Revisa la conexión e inténtalo de nuevo. |
| `mec_history_failed` | Couldn't download rate history. Try again later. | Η λήψη ιστορικού ισοτιμιών απέτυχε. Δοκιμάστε αργότερα. | No se pudo descargar el historial de tipos. Inténtalo más tarde. |
| `mec_history_section_title` | Rate history | Ιστορικό ισοτιμιών | Historial de tipos |
| `mec_history_done` | Rate history downloaded. | Το ιστορικό ισοτιμιών λήφθηκε. | Historial de tipos descargado. |
| `mec_history_done_range` | Rate history downloaded. Rates from %1$s to %2$s are available. | Το ιστορικό ισοτιμιών λήφθηκε. Διαθέσιμες ισοτιμίες από %1$s έως %2$s. | Historial de tipos descargado. Hay tipos disponibles del %1$s al %2$s. |
| `mec_home_changed_cleared` | Your home currency changed, so the amount was cleared. | Το βασικό σας νόμισμα άλλαξε, οπότε το ποσό διαγράφηκε. | Tu moneda principal cambió, por lo que se borró el importe. |

Reused unchanged: `mec_refresh_home_unavailable`, `mec_action_open_setting`, `mec_open_setting_confirm_title/body`, `mec_block_*`, `mec_action_change_currency`, `mec_save_disabled_rate`, `mec_receipt_kept`, `mec_repeat_cleared`. `mec_history_cancel` may be replaced by the app's existing generic Cancel string if one exists (implementer to check; no duplicate key). "90 days" and "7 days" are literal in copy. If the domain's reach differs, change the copy to match (use a `%d` plural if it becomes variable). Greek and Spanish copy needs native review. Use ΕΚΤ/BCE as in the existing `mec_preview_*` keys. Placeholders take only currency codes or public ECB dates, never amounts.

### 1b.10 Open questions
- **UX-1b-Q-1 Currency management row.** RESOLVED (D-1b-4): DEFERRED as follow-up F-1b-CURMGMT. The parked design is in 1b.3, and INV-1b-3 is unchanged (the download starts only from add-expense and receipt review).
- **UX-1b-Q-2 Download replaces Refresh for backdated dates.** RESOLVED (D-1b-1): no. The domain rule wins (INV-1b-5): `REFRESH_LATEST` when the table is empty or the latest stored date is on or before the entry date; `DOWNLOAD_HISTORY` only when the latest stored date is after the entry date. This holds whatever the file's latest day is. The first-use two-step is in 1b.1.
- **UX-1b-Q-3 F-B2-HOME-CHANGE.** RESOLVED (D-1b-2): neither pin nor keep. The domain rule wins (INV-1b-20): a draft whose denomination would change is discarded, with one note, `mec_home_changed_cleared` (1b.4, 1b.9).
- **UX-1b-Q-4 Privacy review items.** These are disclosure accuracy (1b.8), whether fetch timing needs any mention, and payload size on mobile data. Rec: no size or confirm dialog, because the R23 setting is the consent and the tap is user-started. Add a size hint only if the review finds the file large (for example over ~1 MB).
- **UX-1b-Q-5 Cancel on leave.** Rec: cancel the download when the sheet or review screen closes, so no network happens after the user leaves. Alternative: let it finish in the VM scope. That is useful for a quick reopen, but it is hidden network activity.
- **UX-1b-Q-6 es/el.** All new keys are DRAFT. They join the pending MEC es/el sign-off, which is a release gate for 1b together with the existing `mec_` strings.
