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
