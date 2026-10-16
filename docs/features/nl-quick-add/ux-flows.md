# UX flows: nl-quick-add — increment 1 (typed, deterministic, fill-only)

Author: ux-flow-designer (stage 4). Date 2026-10-16. Docs-only; implementation post-1.0. Inputs: `brief.md` v2, `domain-spec.md` (INV/AE/OD ids cited), handoff §Human decisions. Root `A` = `app/src/main/java/com/yourname/expensetracker`; Sheet = `A/ui/screens/addexpense/AddExpenseSheet.kt`; VM = `AddExpenseViewModel.kt`.
Scope: edits to the existing add-expense sheet only. No new screen, route, permission, network or setting. Strings follow the ai-fixes G2-7 precedent: EN + el + es in the same slice.

## 1. Entry and navigation
- Entry unchanged: the add-expense sheet (FAB/Add flows, `add` deep link stays confirmation-gated). Quick add appears **only in create mode** (spec §8, U2: hide if the Sheet has an edit mode).
- Back/Close/system back: unchanged (`reset()` + dismiss). The line, summary and form are discarded together. Rotation: the form, flags and summary survive (VM state); the line is lost (`remember`, INV-12) and Fill goes back to disabled. Process death: whole sheet resets (as today); line loss is acceptable.
- Save success: unchanged (`reset()` + dismiss). No toast about quick add.

## 2. Screen: add-expense sheet (changed regions only)

### 2.1 Wireframe A: idle (top of the scroll column, above Merchant)
```
 [x]  Add Expense                         [ Save ]
 ┌────────────────────────────────────────────────┐
 │ Quick add                                      │
 │ ┌──────────────────────────────┐ ┌──────────┐ │
 │ │ coffee 3.50 yesterday        │ │   Fill   │ │   <- OutlinedTextField + FilledTonalButton
 │ └──────────────────────────────┘ └──────────┘ │
 │ English or Greek (Greeklish too). Not saved.   │   <- supportingText
 └────────────────────────────────────────────────┘
 Merchant / Place  [ e.g. Sklavenitis...        ]
 Amount (€)        [ 0.00                       ]
 Payment  [Card] [Cash] [Transfer]
 Category   No category yet. Assigned automatically when you save.
 [category grid]      Date: Fri, 16 Oct 2026, 14:28
```
### 2.2 Wireframe B: after Fill "taxi home 12 αύριο" (partial; category false match; future date)
```
 │ taxi home 12 αύριο           │ │   Fill   │
 │ ✓ Filled: amount, merchant, category.          │  <- polite live region
 │ ⚠ Needs your check: date.                      │
 │ Category Home is set from your line - check it.│
 Merchant / Place  [ taxi ]            ✓ Filled
 Amount (€)        [ 12.00 ]           ✓ Filled
 Payment  [Card] [Cash*] [Transfer]
 Category   ┌───────────────────────────────┐
            │ 🏠 Home · matched from your line  [ Remove ] │ <- chip row, 48dp
            └───────────────────────────────┘
 [category grid, Home tile highlighted]
 Date: Fri, 16 Oct 2026, 14:30
 ⚠ Future dates aren't allowed. Left as today.
```
### 2.3 Component choices (reuse first)
- Line: `OutlinedTextField` (same style as Merchant), `singleLine`, `maxLength` 200 enforced by **rejecting** the edit (INV §4.1), counter in `supportingText` only from 180 chars ("185 / 200").
- Fill: `FilledTonalButton`, min 48dp height, trailing in a Row. At fontScale >= 1.3 or width < 360dp the Row stacks (button below, end-aligned) so nothing truncates.
- Summary block: plain `Column` (no Card elevation) under the line, `Text` + small icons, styled like the sheet's existing error Card but with neutral/warning tones. Warnings use `SemanticColors.Warning` (orange) plus an icon plus text, never colour alone. Errors keep `errorContainer`.
- Filled marker: small text "Filled" with a check icon in the field's `supportingText` (amount, merchant) or as a caption under the Payment row / Date row. No new colour system.
- Category chip: `AssistChip`-style row above `CategoryGrid` (reuses selected-tile colour `catColor`), trailing "Remove" `TextButton` (48dp). Grid tile for the filled category already shows the selected border.

### 2.4 Interaction rules
- **Fill enabled iff**: trimmed line non-blank AND `homeCurrency != null` AND not `isSaving` (INV-17). Otherwise disabled with stateDescription ("Type a line first" / "Loading currency settings"). While `homeCurrency` is loading, Fill is disabled but shows no spinner (Save already shows one).
- **IME**: `ImeAction.Done` **= Fill** (one-gesture path, matches "type, Fill, check, Save"). `KeyboardType.Text`, `capitalization = None`, `autoCorrectEnabled = false`, `singleLine`. Not `KeyboardType.Password` (breaks Greek/Greeklish layouts and TalkBack). Disabling IME personalised learning is not exposed in Compose (U7); do not use view-interop hacks; privacy relies on INV-12/13, not the IME (UX-Q-6).
- **After Fill**: hide keyboard and clear focus (so the form is visible); do not scroll; keep the line text (editable, re-Fill allowed, still memory-only). Summary replaces the previous summary. Blank line + Done = hide keyboard only.
- **No spinner** for Fill: parse is local and instant; repeated taps are safe (INV-18 latest-wins, INV-7 idempotent).
- **Summary visibility rule (INV-20):** because Category and Date sit below the fold on small phones, the summary at the top lists **every** filled/flagged field with its value (amount with currency, merchant, date, category). On-field markers repeat it where the user will edit.
- **Edits after Fill** (any user gesture on a field): that field's "Filled" marker/flag disappears (origin USER, INV-7 §5.1); the summary text is **not** rewritten (it describes the Fill; stable, no flicker). Typing in the line does not clear anything until the next Fill.
- **Marker logic for implementers:** per field, show "Filled" iff origin = FILL and no flag; show the flag iff `fieldFlags[f]` exists. A Flagged field has origin FILL (apply table) but must render the flag, never "Filled".

## 3. UI state model (sealed, quick-add region; all enums from spec §2.9, no strings in state)
`QuickAddUi` = f(line text [Sheet `remember`], `lineHints`, `fieldOrigins`, `fieldFlags`, `lastFillOutcome`, `homeCurrency`, `isSaving`). Orthogonal to save state (`SaveResult`).

| UI state | Produced by | Region rendering | Fill | Save |
|---|---|---|---|---|
| Idle | open/`reset()`; outcome null | helper text only; no summary | disabled (blank) | enabled |
| Typing | line non-blank, no Fill yet or edited since | helper (or counter >= 180) | enabled | enabled |
| Unavailable (currency loading) | `homeCurrency == null` (INV-17, S5-001) | helper; Fill stateDescription "Loading currency settings" | disabled | spinner (as today) |
| Filled-full | `FILL_FULL` (INV: amount+merchant, no flags) | "Filled: ..." + "Check the details, then Save" | enabled | enabled |
| Filled-partial | `FILL_PARTIAL` | "Filled: ..." + "Needs your check: ..." + per-field flags | enabled | enabled (INV-16) |
| Nothing recognised | `FILL_NONE` + hint `NOTHING_RECOGNISED` | neutral notice with example; no field changes | enabled | enabled |
| Fill-none without that hint | `FILL_NONE` where some field is Flagged/non-fillable (e.g. every parsed field was user-edited) | "Nothing new filled." + flags if any + "Fields you edited were kept." | enabled | enabled |
| Foreign blocked | `FOREIGN_CURRENCY_BLOCKED`, `AMOUNT_FOREIGN_CURRENCY` (INV-4) | warning hint at line; amount empty with flag | enabled | enabled; pressing Save with empty amount shows existing "Enter a valid amount" |
| Foreign, amount typed | user types amount after block (flag cleared) | **hint stays** ("saved in EUR") until next Fill/reset | enabled | enabled; saves home currency only (AE-5) |
| Duplicate | `SaveResult.Duplicate` (§6, AE-42) | unchanged message + form kept | enabled | enabled |
| Save error | `SaveResult.Error`, catch (INV-15) | bounded message (section 7) | enabled | enabled (`isSaving` false) |
| Line too long | `LINE_TOO_LONG` (defensive; UI already rejects >200) | notice "too long (max 200)" | enabled | enabled |

Spec states without dedicated UI: `TIME_IGNORED` (shown as a short hint line), `PAYMENT_CONFLICT`/`CATEGORY_AMBIGUOUS` (inline field flags, no top-level state). UI states without spec cause: **none**; "Fill-none without hint" is derived from §5.4 (W empty, hint absent). Not in UI: outcome codes (diagnostic only), `Absent` fields (silent, by design).

### 3.1 Flags and hints (per field, inline `supportingText`/caption, warning style, wrap freely, no maxLines)
| Reason (spec) | Where | Behaviour |
|---|---|---|
| `AMOUNT_FOREIGN_CURRENCY` | Amount `supportingText` + line hint | amount empty |
| `AMOUNT_QUANTITY` | Amount | empty; "quantity detected" |
| `AMOUNT_MULTIPLE_NUMBERS`, `AMOUNT_AMBIGUOUS_SEPARATOR`, `AMOUNT_UNCLEAR` | Amount | empty; separate wording |
| `AMOUNT_NOT_POSITIVE`, `AMOUNT_OVER_LIMIT` | Amount | empty |
| `DATE_FUTURE`, `DATE_INVALID`, `DATE_UNSUPPORTED`, `DATE_AMBIGUOUS` (several dates, or weekday = today, OD-4) | caption under Date row | date = now at Fill, stays editable |
| `PAYMENT_CONFLICT` | caption under Payment chips | CASH default kept |
| `CATEGORY_AMBIGUOUS` | caption under Category label | none selected |
| `TIME_IGNORED` | summary line | informational |
Flag uses the existing `isError` red only for **amount** (it blocks Save); date/payment/category flags are warnings because Save is still valid.

## 4. Category visibility and R2 mitigation (e.g. "taxi home 12" -> Home)
1. A filled category is the most prominent post-Fill element: chip row **directly above** the grid ("Home · matched from your line" for EXACT_NAME, "· from your history" for LEARNED) in the category's own colour, plus the highlighted grid tile.
2. The summary names it with its value ("category Home"), so it is visible without scrolling; TalkBack announcement includes it.
3. **One-tap removal**: the chip's "Remove" button (48dp, onClickLabel "Remove category Home") sets category to none. Removal counts as a user gesture on the field (origin USER), so a later Fill will not put it back. Tapping a different tile also replaces it (existing).
4. After removal the empty-category hint appears (section 6). No confirmation, no undo needed: re-picking is one tap and nothing is saved before Save.
5. Chip copy is neutral, never "recommended"; EXACT_NAME says "matched from your line" so the user can see why. Learned-on-save behaviour is unchanged (Gate 1 Q5); that is why visibility, not blocking, is the mitigation. Revisit if the owner finds false matches noisy (spec R1; the brief calls this R2).

## 5. Fill-contract visuals
- **User-edited fields untouched (INV-1):** no per-field message (spec sets no flag). When any of the five fields has origin USER, the summary appends "Fields you edited were kept." so a user who typed 4.00 and sees it unchanged after "καφές 3,50" understands (AE-9).
- **Second Fill:** summary and markers are replaced, never stacked. Previously Filled fields not mentioned this time revert to default and lose their marker (AE-22); that disappearance is the indication.
- **Clipboard-prefilled amount overwritten or cleared (PREFILL, AE-20):** no extra indicator in increment 1. A new value shows the normal "Filled" marker; a foreign/flagged result clears it and shows the flag (the user sees why). The plain add-expense clipboard behaviour is not given a "from clipboard" caption (non-goal; UX-Q-3).
- **Merchant suggestions:** Fill hides the dropdown (INV-9). A later suggestion tap is a user edit: it removes the "Filled" markers it overwrites.

## 6. Empty category hint (brief risk)
Caption under the Category label when `selectedCategoryId == null`: "No category chosen. One is assigned automatically when you save." Shown in every add flow (not quick-add-specific) and never in warning colour. Honest: save-time auto-categorisation may yield Uncategorized.

## 7. Money display
- Every amount shown by this feature carries the home currency symbol/code: summary "3.50 €" via `CurrencyFormatter.formatMoney`; field keeps the leading symbol. Pre-existing: amount label hardcodes "(€)" and its CD says "Euro" for non-EUR home currency; not changed here (route F-note).
- **No mixed-currency or converted amounts anywhere**: a foreign marker never produces a number, conversion or relabel (INV-4). The hint always states the save currency ("saved in EUR"), including after the user types an amount.
- **Unavailable is never zero**: unclear/foreign amounts leave the field empty with a flag; "0" typed is a flag ("above zero"). No "0.00" placeholder values.
- **Rounding as seen:** Fill never rounds or truncates; more than 2 decimals is flagged "Amount unclear" instead of shown rounded. Display always 2 decimals (e.g. "3.50").

## 8. Destructive actions
None added. Chip "Remove" is reversible by re-picking; Fill never writes to storage (INV-11), so no confirmation or undo. Overwriting is bounded by INV-1 (user edits are never replaced).

## 9. Save-error hygiene (owner-approved, OD-2 recommended yes)
- VM maps every non-success outcome to a **string resource key** (via existing `UiText.StringResource`); `e.message` and repository `result.message` are never displayed (INV-15). Logs only `ADD_EXPENSE_SAVE_FAILED`.
- Duplicate: unchanged (`error_duplicate_transaction`, sheet Card). Wrapper `add_expense_error_format` ("❌ %1$s") kept for errors, with the leading emoji hidden from TalkBack by using the text-only content description.
- **Visibility:** the error/duplicate Card is at the very bottom of the scroll column and quick add lengthens the sheet. Proposal: on `saveResult` becoming Error/Duplicate, `scrollState.animateScrollTo(bottom)` and mark the Card `liveRegion = Assertive` (UX-Q-5).
- Validation messages that quick-add flows depend on are currently hard-coded English in the VM ("Enter a valid amount", etc.). Localise those four in the same slice (keys below); other VM messages (transfer, ownership) are out of scope.

## 10. Accessibility
- Field: label "Quick add", helper read with it. Fill button content description `quick_add_fill_cd`; disabled stateDescription explains why. Done on the IME announces nothing extra; the summary does.
- **TalkBack announcement:** the summary node is `liveRegion = Polite` and its text is the full sentence, e.g. "Filled: amount 3.50 €, merchant taxi, category Home. Needs your check: date." No focus jump. Nothing recognised is announced the same way.
- On-field markers are text ("Filled") with a decorative icon (`contentDescription = null`); flags are read with the field via `supportingText`. Category chip: single node "Category Home, matched from your line" + custom click label "Remove category Home".
- Order (TalkBack/focus): top bar, Quick add field, Fill, summary, Merchant, Amount, Payment, Category (hint/chip, grid), Date, rest. Summary sits before the fields it describes.
- Touch targets >= 48dp (Fill, Remove, chip). Font scale to 200%: Row stacks, all hint/flag text wraps, no truncation of flags. Contrast >= 4.5:1 on `BaseNavy` for helper, warning (`WarningOrange` text on navy checked by implementer) and flags; meaning never colour-only (icon + text).
- Never read or expose the raw line in content descriptions of anything except the field the user is typing in.

## 11. Copy: resource keys (EN / el / es; `quick_add_` prefix unless stated)
Quick add understands English, Greek and Greeklish only; the es locale therefore shows an **English** example (UX-Q-6). `%1$s` names are localised field names, joined with ", ". Greek uses formal-neutral plural imperative; translator review before release.

| Key | EN | el | es |
|---|---|---|---|
| `quick_add_label` | Quick add | Γρήγορη προσθήκη | Añadir rápido |
| `quick_add_placeholder` | e.g. coffee 3.50 yesterday | π.χ. καφές 3,50 χθες | p. ej. coffee 3.50 yesterday |
| `quick_add_helper` | English or Greek (Greeklish too). Your line is not saved. | Αγγλικά ή ελληνικά (και greeklish). Η γραμμή δεν αποθηκεύεται. | Inglés o griego (también greeklish). La línea no se guarda. |
| `quick_add_counter` | %1$d / %2$d | %1$d / %2$d | %1$d / %2$d |
| `quick_add_fill_button` | Fill | Συμπλήρωση | Rellenar |
| `quick_add_fill_cd` | Fill the form from the quick add line | Συμπλήρωση της φόρμας από τη γραμμή | Rellenar el formulario con la línea |
| `quick_add_fill_disabled_blank` | Type a line first | Πληκτρολογήστε πρώτα μια γραμμή | Escribe primero una línea |
| `quick_add_fill_disabled_loading` | Loading currency settings | Φόρτωση ρυθμίσεων νομίσματος | Cargando la configuración de moneda |
| `quick_add_summary_filled` | Filled: %1$s. | Συμπληρώθηκαν: %1$s. | Rellenado: %1$s. |
| `quick_add_summary_review` | Needs your check: %1$s. | Χρειάζεται έλεγχο: %1$s. | Revisa: %1$s. |
| `quick_add_summary_check_save` | Check the details, then Save. | Ελέγξτε τα στοιχεία και πατήστε Αποθήκευση. | Revisa los datos y pulsa Guardar. |
| `quick_add_summary_kept_edits` | Fields you edited were kept. | Τα πεδία που αλλάξατε διατηρήθηκαν. | Se mantuvieron los campos que editaste. |
| `quick_add_summary_none` | Nothing recognised. Try an amount and a place, like "coffee 3.50 yesterday". | Δεν αναγνωρίστηκε τίποτα. Δοκιμάστε ποσό και μέρος, π.χ. "καφές 3,50 χθες". | No se reconoció nada. Prueba con un importe y un lugar, como "coffee 3.50 yesterday". |
| `quick_add_summary_nothing_new` | Nothing new was filled. | Δεν συμπληρώθηκε κάτι νέο. | No se rellenó nada nuevo. |
| `quick_add_field_amount` / `_merchant` / `_date` / `_category` / `_payment` | amount / merchant / date / category / payment method | ποσό / κατάστημα / ημερομηνία / κατηγορία / τρόπος πληρωμής | importe / comercio / fecha / categoría / método de pago |
| `quick_add_filled_marker` | Filled | Συμπληρώθηκε | Rellenado |
| `quick_add_category_exact_fmt` | %1$s · matched from your line | %1$s · από τη γραμμή σας | %1$s · según tu línea |
| `quick_add_category_learned_fmt` | %1$s · from your history | %1$s · από το ιστορικό σας | %1$s · según tu historial |
| `quick_add_category_remove` / `_remove_cd` | Remove / Remove category %1$s | Αφαίρεση / Αφαίρεση κατηγορίας %1$s | Quitar / Quitar la categoría %1$s |
| `quick_add_hint_foreign` | Foreign currency isn't supported here yet. This expense will be saved in %1$s. | Το ξένο νόμισμα δεν υποστηρίζεται ακόμη εδώ. Το έξοδο θα αποθηκευτεί σε %1$s. | Aún no se admite moneda extranjera aquí. El gasto se guardará en %1$s. |
| `quick_add_hint_time_ignored` | Time of day was ignored. | Η ώρα αγνοήθηκε. | Se ignoró la hora. |
| `quick_add_hint_too_long` | The line is too long (max %1$d characters). | Η γραμμή είναι πολύ μεγάλη (έως %1$d χαρακτήρες). | La línea es demasiado larga (máx. %1$d caracteres). |
| `quick_add_flag_amount_foreign` | Enter the amount in %1$s. | Εισάγετε το ποσό σε %1$s. | Introduce el importe en %1$s. |
| `quick_add_flag_amount_quantity` | Quantity detected. Enter the total amount. | Εντοπίστηκε ποσότητα. Εισάγετε το συνολικό ποσό. | Se detectó una cantidad. Introduce el importe total. |
| `quick_add_flag_amount_multiple` | More than one number found. Enter the amount. | Βρέθηκαν περισσότεροι αριθμοί. Εισάγετε το ποσό. | Hay más de un número. Introduce el importe. |
| `quick_add_flag_amount_separator` | Amount unclear: check the decimal and thousands separators. | Μη σαφές ποσό: ελέγξτε τα διαχωριστικά δεκαδικών και χιλιάδων. | Importe poco claro: revisa los separadores de decimales y miles. |
| `quick_add_flag_amount_unclear` | Amount unclear. Enter it yourself. | Μη σαφές ποσό. Εισάγετέ το εσείς. | Importe poco claro. Introdúcelo tú. |
| `quick_add_flag_amount_not_positive` / `_over_limit` | Amount must be above zero. / Amount is too large. | Το ποσό πρέπει να είναι μεγαλύτερο από μηδέν. / Το ποσό είναι πολύ μεγάλο. | El importe debe ser mayor que cero. / El importe es demasiado grande. |
| `quick_add_flag_date_future` | Future dates aren't allowed. Left as today. | Δεν επιτρέπονται μελλοντικές ημερομηνίες. Έμεινε η σημερινή. | No se permiten fechas futuras. Se dejó hoy. |
| `quick_add_flag_date_ambiguous` | Date unclear (several dates, or the weekday is today). Left as today. | Μη σαφής ημερομηνία (πολλές ημερομηνίες ή η σημερινή ημέρα). Έμεινε η σημερινή. | Fecha poco clara (varias fechas, o el día de la semana es hoy). Se dejó hoy. |
| `quick_add_flag_date_unsupported` / `_invalid` | That date form isn't supported yet. Left as today. / Not a valid date. Left as today. | Αυτή η μορφή ημερομηνίας δεν υποστηρίζεται ακόμη. Έμεινε η σημερινή. / Μη έγκυρη ημερομηνία. Έμεινε η σημερινή. | Ese formato de fecha aún no se admite. Se dejó hoy. / Fecha no válida. Se dejó hoy. |
| `quick_add_flag_payment_conflict` | Both card and cash found. Choose one. | Βρέθηκαν και κάρτα και μετρητά. Επιλέξτε ένα. | Se encontraron tarjeta y efectivo. Elige uno. |
| `quick_add_flag_category_ambiguous` | More than one category matched. Choose one. | Ταίριαξαν περισσότερες κατηγορίες. Επιλέξτε μία. | Coinciden varias categorías. Elige una. |
| `add_expense_category_auto_hint` | No category chosen. One is assigned automatically when you save. | Δεν επιλέχθηκε κατηγορία. Θα ανατεθεί αυτόματα κατά την αποθήκευση. | Sin categoría. Se asignará una automáticamente al guardar. |
| `add_expense_error_save_failed` | Couldn't save the expense. Please try again. | Δεν ήταν δυνατή η αποθήκευση του εξόδου. Δοκιμάστε ξανά. | No se pudo guardar el gasto. Inténtalo de nuevo. |
| `add_expense_error_currency_loading` | Loading currency settings… | Φόρτωση ρυθμίσεων νομίσματος… | Cargando la configuración de moneda… |
| `add_expense_error_merchant_required` | Merchant name is required | Το όνομα καταστήματος είναι υποχρεωτικό | El nombre del comercio es obligatorio |
| `add_expense_error_amount_invalid` | Enter a valid amount | Εισάγετε έγκυρο ποσό | Introduce un importe válido |
| `add_expense_error_amount_too_large` | Amount is too large | Το ποσό είναι πολύ μεγάλο | El importe es demasiado grande |
| `add_expense_error_date_future` | Date cannot be in the future | Η ημερομηνία δεν μπορεί να είναι στο μέλλον | La fecha no puede ser futura |
| `add_expense_error_unexpected` (Result.Loading branch) | = `add_expense_error_save_failed` (reuse) | | |

Copy rules: no string quotes, echoes or logs the user's line; flags name the problem, never the offending token. `quick_add_summary_*` values (amount, merchant, date, category) are form values, shown on screen only.

## 12. State-to-spec traceability (all observable spec states)
Idle/typing: INV-17, §4.1. Filled-full/partial/none: §5.4. Per-field flags: §2.4. Foreign: INV-4, AE-5. Future date: AE-8. Quantity/ambiguous: AE-6/7. User-edit kept: INV-1, AE-9. Clipboard race: INV-8 (no UI needed). Duplicate: §6, AE-42/43. Save error: INV-15, AE-53. Process death/rotation: INV-12.

## 13. Open questions (UX-Q)
- **UX-Q-1 Category source in state.** The chip needs EXACT_NAME vs LEARNED, but spec §2.9 state has no category source. Rec: add `categoryFillSource` (enum only) to the VM state; cleared with the origin on user edit.
- **UX-Q-2 Category removal.** Spec §5.1 has no "clear category" transition. Rec: add `clearCategory()` = category null, origin USER (never re-filled by a later Fill); tests alongside AE-23-style cases.
- **UX-Q-3 Overwrite/skip indication.** Rec: no "from clipboard" caption and no per-field "kept" list; use the single "Fields you edited were kept." line (no spec change). If the owner wants precision, the VM would need an enum set `skippedUserEdited`.
- **UX-Q-4 Summary stability.** Rec: summary describes the last Fill and is not rewritten when the user edits fields afterwards; "Filled" markers do disappear on edit. Confirm.
- **UX-Q-5 Error visibility and extra scope.** Rec: scroll-to + assertive live region for the save error/duplicate Card, and localise the four VM validation messages listed. Both are small additions to the same sheet/VM slice; decline = follow-up.
- **UX-Q-6 Locale/IME.** (a) es-locale placeholder shows an English example because the parser has no Spanish vocabulary; add Spanish later? Rec: no. (b) Compose cannot set IME no-personalised-learning (U7); rec: only `autoCorrectEnabled = false`, no interop. (c) Greek/Spanish copy is a proposal; native review before release.
