# UX flows: money-correctness (inc 1a read side; 1b export cells only)

Author: ux-flow-designer (stage 4). Date 2026-10-16. Docs only. Inputs: `brief.md` v2, `domain-spec.md` (INV/AE/OD cited), handoff §Human decisions (Gate 1 Q1-Q6, binding). No new permissions, network or screens; existing Compose screens only. Tags: [V] re-read this pass, [U] unverified.

## 1. Entry points and navigation
- **Transactions** (bottom-nav tab; initial tab is MONTH, `TransactionsViewModel.kt:81` [V], RF-6). Behaviour is identical on ALL and time-range tabs (INV-7). Sort menu: top-bar menu (`TransactionsScreen.kt:272-303`). Min/max: shown only in the Active Filter Banner (`:450-451`). The filter sheet has **no** min/max editor [V]; bounds arrive through programmatic or restored filters (`MainActivity.kt:355-356`) [U: which caller sets them]. 1a adds no editor.
- **Currency Management** (Settings > Currency). Two entry points call the same request: `HomeCurrencyCard` picker (`:174-179`) and supported-currency card (`:310-316`). Back from the dialog = Cancel. No deep links added.
- **Export options**: no change (section 6).
- Back behaviour unchanged. Notices and dialogs are not destinations. The pending warning survives rotation and is lost on process death (= cancel, section 5.2).

## 2. Transactions: amount ordering, unranked rows, count
Reuse: the Active Filter Banner pattern (`Surface` + `labelMedium` + 48dp action), `AlertDialog` as in `ConversionDialog` (`:653`), `InlineErrorBanner` look for the paused notice, `ListSkeleton` while paging resets (H change, INV-10).

**Rows (decision).** No per-row marker. Rows keep their original amount and currency exactly as today (INV-19), whether or not they have a home value. A badge would appear on only a subset of foreign rows, repeats N times, and competes with the existing manual-entry/type/ownership icons. The explanation lives in one notice and one divider.

**Amount sort active, no bound, H Available, N > 0.** Amount sort renders one flat group "Sorted By Amount" with a sticky header (`TransactionsViewModel.kt:297-298` [V]). The unranked rows are the tail of that list:
```
[Search] Transactions      [sort][filter][search]
[This month][All]...
(i) 12 transactions without a EUR value are shown last    <- notice, tap = explanation
-- Sorted By Amount (sticky, unchanged) -----------------
 Coffee  M1            EUR 25.00      <- ranked rows by home value
 Taxi    M2            USD 20.00      <- ranked (valid snapshot)
 ---- Without a EUR value ----        <- non-sticky divider, heading
 Hotel   M3            USD 20.00      <- unranked, date DESC, id DESC
```
**Min and/or max set, H Available, N > 0.** Unranked rows are hidden, not listed (INV-6). Notice sits below the Active Filter Banner: "12 transactions without a EUR value are not shown". The banner shows the bound with its currency: "Min: EUR 15.00". Tap on the notice opens the same explanation. Rows with no bound are never hidden, so the notice is not shown for date sort without a bound (INV-8, D-3).

**Notice rules.** Single line item, min height 48dp (`Dimens.TouchTargetMin`), info icon + text (not color only), one tap target, opens the explanation `AlertDialog` (title, body, one "Got it" TextButton). Hidden while the count is loading (never shows a placeholder or "0"). Number only, no merchants/amounts (INV-22). If the count query fails: bound active -> generic "Some transactions may be hidden because they have no EUR value" (no number); sort only -> no notice (rows are still listed). Count refreshes on mutation and on any INV-10 key change; it is advisory (spec 4.3).

**Read-time H not Available (INV-9, AE-11).** Sort menu: the two amount items are disabled with the hint "Needs your home currency"; the user's chosen sort and bounds are kept (the menu still ticks the chosen item) and re-apply when H becomes Available. Effective order is date DESC. Min/max are not applied; the banner appends "(paused)" to the bound. A paused notice replaces the count notice:
```
(!) Can't read your home currency right now. Amount sorting and min/max filters are paused. Showing newest first.   [Try Again]
```
- `Loading`: show the "Checking your home currency..." variant only if Loading lasts > 400 ms (avoid a flash; Loading <= 5 s). No retry button.
- `Unavailable` (READ_ERROR, TIMEOUT, INVALID_CODE): one message for all three; the reason is never shown (`Failed.reason` raw text never reaches UI, RF-11). "Try Again" re-subscribes (state 5.1); pull-to-refresh does the same.

**Empty with filter.** If bounds hide every row (all None + bound) the list is empty and the notice still shows the count. The existing `EmptyTransactionsState` only knows search vs "no transactions" (`:959-1023`) and would say "no transactions yet" with an Add button. Needs a filtered-empty variant (UX-Q-2).

**Unchanged:** date headers and signing (RF-4), tab badges, row actions, search, skeleton while loading.

## 3. Home-switch warning (Currency Management, INV-12, AE-14)
Trigger: `request(code)` from both entry points. No dialog when `code ==` the current Available H (no write, as today) or for an unsupported code (`UNSUPPORTED_HOME_CURRENCY` inline error, as today). Dialog also shows when H is Loading/Unavailable (D-9).
```
+---------------------------------------------+
| Switch home currency to USD?                |
|                                             |
| Your existing transactions are not          |
| converted. Transactions in other currencies,|
| including your history so far, will have no |
| USD value for now, so amount sorting and    |
| min/max filters cannot use them. They stay  |
| in your list with their original amounts.   |
| Transactions already in USD are unaffected. |
| 1,248 transactions will have no USD value.  |  <- optional count line
|                                             |
|                  [Cancel] [Switch to USD]   |
+---------------------------------------------+
```
- Same shape as `ConversionDialog`: `AlertDialog`, bold title, `TextButton` Cancel (`action_cancel`) left of a filled `Button` confirm (default primary, not red: nothing is deleted, the setting can be switched back through the same flow). Specific verb on confirm ("Switch to USD"), never "OK".
- Cancel, outside tap, back = Cancel: nothing written, H unchanged, classifier not invalidated. Confirm: exactly one write then today's invalidation. While `Writing` the confirm button is disabled with a small progress indicator and a second tap is ignored. Write failure: dialog closes, existing inline error banner (`:150-157`) shows "Couldn't change your home currency. Try again." (bounded; H unchanged).
- Copy never claims conversion, never names amounts/merchants/dates. It does not name the old home currency (unknown when H is Loading/Unavailable). "For now" is used instead of promising a future feature (UX-Q-3).
- Count line (UX-Q-1): number only, from the INV-8 COUNT with H bound to the **candidate** code, no filters. The dialog opens immediately with static text; the line appears when the count arrives and is omitted on failure. No spinner text.
- After confirm: Transactions resets paging and recomputes the count (AE-12); Currency Management shows its existing loading state until the DataStore flow re-emits. No Undo snackbar (an undo would re-trigger this warning); switching back repeats the flow.

## 4. UI state model (one sealed state per surface)
`TransactionsAmountUi` (new, alongside the existing list state):
| State | Produced by | User sees |
|---|---|---|
| `Inactive` | date sort, no bound (INV-8) | nothing new |
| `Ready(count: Int)` | H Available, amount sort or bound, count loaded (INV-8) | notice if `count > 0` + divider (sort) / hidden-rows notice (bound) |
| `CountPending` | H Available, count in flight (INV-10) | no notice; list renders |
| `CountUnavailable` | count query failed [no spec cause: UX-Q-4] | bound: generic notice; sort only: nothing |
| `HomeLoading` | `ReadHomeCurrencyState.Loading` (INV-9) | date order; delayed "Checking..." notice; amount sort items disabled |
| `HomeUnavailable` | `Unavailable(READ_ERROR/TIMEOUT/INVALID_CODE)` (INV-9, 5.1) | paused notice + Try Again; items disabled |
| Empty (existing state + filter flag) | all None + bound (spec section 6) | filtered-empty variant + notice |
| Offline / consent / permission | **not applicable**: local only; no network, cloud AI or permission involved | - |
`HomeSwitchUi` (Currency Management): `Idle`, `PendingConfirm(target, predictedCount: Int?)`, `Writing`, `Error(WRITE_FAILED | UNSUPPORTED_HOME_CURRENCY)` (spec 5.2). Spec states with no UI of their own: INV-10 stale-result discard (invisible by design); the three `ReadHomeFailure` reasons (one shared message); `NoComparableValueReason` (tests only, never shown). UI states with no spec cause: `CountUnavailable`, the 400 ms Loading threshold, filtered-empty copy (all flagged in UX-Q).

## 5. Money display
- Every amount shows its currency. Rows: original amount + original currency, unchanged; an unranked row still shows e.g. "USD 20.00", never "EUR 0.00", "0" or "-". "Unavailable" is expressed only as position (last), the count and the notice, never as a zero amount.
- Min/max banner: "Min: EUR 15.00" using the home currency (today `Min: %1$.2f` has no currency, `strings.xml:229`). When paused: "Min: EUR 15.00 (paused)" if H is known from the last Available value, else "Min: 15.00 (paused)" [U: whether the VM keeps the last H].
- Mixed-currency totals: date header unchanged (INV-19, RF-4). The flat "Sorted By Amount" header keeps today's behaviour; no unranked row ever contributes to a displayed home total.
- Rounding as seen: 1a changes no displayed amount. After 1b new converted snapshots are HALF_EVEN-rounded to home minor units, but lists still show the original amount; the visible effect is only the MEC preview (AE-24) and exports. Shared-row home values are never displayed.
- Export (1b, AE-25): no-rate rows export empty home currency/base cells. **No in-app note recommended**: the export screen has no per-row preview, a note would add a surface for an accepted, release-noted change (Q5). Revisit at inc 3 (null semantics). Release-note wording: "Rows without an exchange rate now export empty base currency and home currency cells instead of EUR."

## 6. Destructive or irreversible actions
None destructive. The home switch is reversible and non-destructive but confirmed (mandatory, Q2). Edits of old rows re-snapshot against the current H (RF-9, inc 2); not surfaced in 1a.

## 7. Accessibility
- Notice: one focus node, `Role.Button`, text = full sentence (count included) + `onClickLabel` "Why some transactions have no EUR value". Min 48dp, wraps to 3+ lines at 200% font; no truncation. `liveRegion = Polite` only on appearance/state change, not on every count refresh.
- Divider: `heading()` semantics so TalkBack heading navigation finds the unranked block; text is not color-only. Rows keep existing content descriptions (amount + currency read as formatted).
- Disabled amount sort items: item cd reads "Sort by Amount High to Low, not available, needs your home currency" (extends `transactions_sort_by_cd`). Sort labels come from the non-localized `SortOrder.displayName` today (UX-Q-5).
- Dialogs: title announced as pane title; body is one text node inside a `verticalScroll` (large fonts); buttons wrap vertically (M3 default); confirm cd "Switch home currency to US Dollar"; focus starts on the title, not on Confirm. Explanation dialog has a single "Got it".
- TalkBack order: top bar, tabs, active filter banner, notice, list. Colors from `SemanticColors` (`TextSecondary` on surface, icon plus text) meet existing contrast.

## 8. Copy (EN keys; el and es are proposals, owner reviews Greek; existing `values-el`/`values-es` are partial and fall back to EN [V]; es plurals need `many`)
No raw OCR/notification/receipt text, amounts, merchants or exception text anywhere. `%2$s` = home currency code.
| Key | EN | el | es |
|---|---|---|---|
| `transactions_amount_notice_sort` (plural) | "%1$d transaction without a %2$s value is shown last" / "%1$d transactions ... are shown last" | "%1$d συναλλαγή χωρίς τιμή σε %2$s εμφανίζεται τελευταία" / "συναλλαγές ... εμφανίζονται τελευταίες" | "%1$d transacción sin valor en %2$s se muestra al final" / "transacciones ... se muestran al final" |
| `transactions_amount_notice_filter` (plural) | "%1$d transaction without a %2$s value is not shown" / "... are not shown" | "%1$d συναλλαγή χωρίς τιμή σε %2$s δεν εμφανίζεται" / "δεν εμφανίζονται" | "%1$d transacción sin valor en %2$s no se muestra" / "no se muestran" |
| `transactions_amount_notice_filter_unknown` | "Some transactions may be hidden because they have no %1$s value" | "Ορισμένες συναλλαγές μπορεί να κρύβονται επειδή δεν έχουν τιμή σε %1$s" | "Puede que se oculten transacciones porque no tienen valor en %1$s" |
| `transactions_amount_notice_action_cd` | "Why some transactions have no %1$s value" | "Γιατί ορισμένες συναλλαγές δεν έχουν τιμή σε %1$s" | "Por qué algunas transacciones no tienen valor en %1$s" |
| `transactions_amount_divider` | "Without a %1$s value" | "Χωρίς τιμή σε %1$s" | "Sin valor en %1$s" |
| `transactions_amount_explain_title` | "No %1$s value" | "Χωρίς τιμή σε %1$s" | "Sin valor en %1$s" |
| `transactions_amount_explain_body` | "These transactions were recorded in a currency other than %1$s, and no exchange rate to %1$s is saved for them. Sorting and min/max filters can only use %1$s amounts, so they are listed last or left out. Their original amounts and currencies are unchanged." | "Αυτές οι συναλλαγές καταγράφηκαν σε νόμισμα διαφορετικό από %1$s και δεν υπάρχει αποθηκευμένη ισοτιμία προς %1$s. Η ταξινόμηση και τα φίλτρα ελάχιστου/μέγιστου χρησιμοποιούν μόνο ποσά σε %1$s, γι' αυτό εμφανίζονται τελευταίες ή παραλείπονται. Τα αρχικά ποσά και νομίσματα δεν αλλάζουν." | "Estas transacciones se registraron en una moneda distinta de %1$s y no hay un tipo de cambio guardado a %1$s. La ordenación y los filtros de mínimo/máximo solo usan importes en %1$s, por eso aparecen al final o se omiten. Los importes y monedas originales no cambian." |
| `action_got_it` (reuse if present) | "Got it" | "Εντάξει" | "Entendido" |
| `transactions_amount_paused_loading` | "Checking your home currency. Showing newest first." | "Έλεγχος του κύριου νομίσματος. Εμφάνιση πρώτα των πιο πρόσφατων." | "Comprobando tu moneda principal. Se muestran primero las más recientes." |
| `transactions_amount_paused_unavailable` | "Can't read your home currency right now. Amount sorting and min/max filters are paused. Showing newest first." | "Δεν είναι δυνατή η ανάγνωση του κύριου νομίσματος. Η ταξινόμηση ποσού και τα φίλτρα ελάχιστου/μέγιστου έχουν παύσει. Εμφάνιση πρώτα των πιο πρόσφατων." | "Ahora no se puede leer tu moneda principal. La ordenación por importe y los filtros de mínimo/máximo están en pausa. Se muestran primero las más recientes." |
| `transactions_sort_amount_disabled_hint` | "Needs your home currency" | "Απαιτεί το κύριο νόμισμα" | "Requiere tu moneda principal" |
| `transactions_filter_min_money_format` / `max` | "Min: %1$s" / "Max: %1$s" (formatted money) + `transactions_filter_paused_suffix` " (paused)" | " (σε παύση)" | " (en pausa)" |
| `transactions_empty_filtered_title` / `_subtitle` | "No transactions match these filters" / "Clear a filter to see more." | "Καμία συναλλαγή δεν ταιριάζει με τα φίλτρα" / "Καθαρίστε ένα φίλτρο για περισσότερα." | "Ninguna transacción coincide con los filtros" / "Quita un filtro para ver más." |
| `currency_home_switch_title` | "Switch home currency to %1$s?" | "Αλλαγή κύριου νομίσματος σε %1$s;" | "¿Cambiar la moneda principal a %1$s?" |
| `currency_home_switch_body` | "Your existing transactions are not converted. Transactions in other currencies, including your history so far, will have no %1$s value for now, so amount sorting and min/max filters cannot use them. They stay in your list with their original amounts. Transactions already in %1$s are unaffected." | "Οι υπάρχουσες συναλλαγές δεν μετατρέπονται. Οι συναλλαγές σε άλλα νομίσματα, μαζί με το ιστορικό σας μέχρι τώρα, δεν θα έχουν προς το παρόν τιμή σε %1$s, οπότε η ταξινόμηση ποσού και τα φίλτρα ελάχιστου/μέγιστου δεν μπορούν να τις χρησιμοποιήσουν. Παραμένουν στη λίστα με τα αρχικά τους ποσά. Οι συναλλαγές που είναι ήδη σε %1$s δεν επηρεάζονται." | "Tus transacciones existentes no se convierten. Las transacciones en otras monedas, incluido tu historial hasta ahora, no tendrán valor en %1$s por el momento, por lo que la ordenación por importe y los filtros de mínimo/máximo no podrán usarlas. Siguen en tu lista con sus importes originales. Las transacciones que ya están en %1$s no se ven afectadas." |
| `currency_home_switch_count` (plural) | "%1$d transaction will have no %2$s value." / "%1$d transactions will have no %2$s value." | "%1$d συναλλαγή δεν θα έχει τιμή σε %2$s." / "συναλλαγές δεν θα έχουν" | "%1$d transacción no tendrá valor en %2$s." / "transacciones no tendrán" |
| `currency_home_switch_confirm` / `_cd` | "Switch to %1$s" / "Switch home currency to %1$s" | "Αλλαγή σε %1$s" / "Αλλαγή κύριου νομίσματος σε %1$s" | "Cambiar a %1$s" / "Cambiar la moneda principal a %1$s" |
| `currency_home_switch_error` | "Couldn't change your home currency. Try again." | "Δεν ήταν δυνατή η αλλαγή του κύριου νομίσματος. Δοκιμάστε ξανά." | "No se pudo cambiar tu moneda principal. Inténtalo de nuevo." |
Reused: `action_cancel`, `action_retry` ("Try Again"), `cd_dismiss`. Existing UI says "Home Currency" (`label_home_currency`); copy above uses "home currency" to match.

## 9. Open questions (UX-Q)
- **UX-Q-1** Show the predicted count in the switch dialog? Rec: yes, optional line, number only, omitted on failure (brief says a count shows how many; the spec allows it). A large number (the whole old history) is honest and discourages accidental taps (CURR-6).
- **UX-Q-2** Filtered-empty variant of `EmptyTransactionsState` is a gap (it only checks search). Rec: add it (two strings above); small, avoids "no transactions yet" when bounds hide everything.
- **UX-Q-3** Wording "for now" instead of the brief's "until conversion support arrives". Rec: "for now", no feature promise; revisit when inc 2 lands (the warning can then mention recovery).
- **UX-Q-4** Spec has no state for a failed COUNT. Rec: spec adds a bounded `CountUnavailable` (UI treats as section 2: generic notice only when a bound is set).
- **UX-Q-5** `SortOrder.displayName` is hardcoded English (`ExpenseRepository.kt:49-72`) while the menu a11y text is localized. Out of scope; rec: route to the Transactions owner and use string resources only for the new hint.
- **UX-Q-6** Min/max has no editor in the filter sheet [V] and its setters are [U]. If bounds can never be set by the user, the "not shown" notice is reachable only through drill-downs. Rec: impact-mapper confirms the setters; 1a adds no editor.
