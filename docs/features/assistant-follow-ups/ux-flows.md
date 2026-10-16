# UX flows: assistant-follow-ups (inc 1)

Author: ux-flow-designer (stage 4). Date 2026-10-16. Docs only; implementation post-1.0.
Inputs: `brief.md` v2, `domain-spec.md` (INV-F/AE-F/OD-F/RF-F), `docs/features/ai-fixes/domain-spec-part-c.md` (C-2, C-4), `AssistantSheet.kt`, `AssistantResultCard.kt`.
Inc 1 only (chips, indicator, empty, comparison, invalidation). No new permissions, network or persistence. Inc 1b (free text) is out of scope.
Assumed defaults: OD-F1..F4 = recommended (typed Empty for MAX/MIN, FOLLOW_UP kind, previous FULL unit, mixed-currency rule for typed compare).

## 1. Entry points and navigation
- No new screen or route. Everything lives in `AssistantSheet` (ModalBottomSheet). Reached as today.
- Chips are reached only after an answer. Back/dismiss closes the sheet; context and chips survive close/reopen, config change and a drilldown round trip (INV-F4, AE-F16).
- Process death or VM clearance: sheet reopens with an empty transcript, the starter prompts, no chips, no indicator, no note (INV-F16, S6). Nothing restores (Part C also does not restore the transcript).
- Deep links: none. Chip "Show transactions" (if kept, UX-Q1) reuses `OpenTransactions(item.drilldownFilter)`; back returns to the assistant as today.

## 2. Screen: Assistant sheet (changed regions only)
```
+----------------------------------------------+
| (*) Assistant                      [hist][del]|
|  ... older items: cards with NO chips ...    |
|  [ Assistant card: Food, This month        ] |
|  [  142.30 EUR                              ] |
|  [  Previous period (Sep 2026): 80.00 EUR   ] |   <- comparison line, in-card
|  [  [ Open Transactions ]                   ] |   <- existing button, unchanged
|   (Compare) (Previous month) (Remove Food)    |   <- follow-up chips, wrap
|----------------------------------------------|
|  Follow-ups for: Food · Oct       [New question]|  <- indicator strip
|  [ Ask about your expenses            ] [>]  |
+----------------------------------------------+
```
1. **Chip row.** Directly below the latest `AssistantResultCard` (and below any `AiRecommendationCard` stack), inside the same LazyColumn item, 8dp gap, left aligned.
   - Use the existing `SuggestionChip` (same family as starter prompts and clarification options). Wrap with `FlowRow`, not a scrolling `LazyRow`: all <=3 chips stay visible at 200% font; nothing hides off-screen.
   - Visible only for the item that is the transcript's last item and `sourceItemId` (INV-F9). Older answers lose the row entirely (removed, not disabled). Reason: chips are suggestions about "the answer in front of you"; a dead row of greyed chips is noise. This differs on purpose from Part C OD-C3, where clarification options stay visible but disabled because they are part of the question record.
   - Order = catalogue order (INV-F5); the UI never reorders or caps.
2. **RF-F4 resolution (recommended).** The card's "Open Transactions" button stays on every answer (it works on old answers too and is the one drilldown affordance). The chip row does not render SHOW_TRANSACTIONS. No domain change is needed to ship; UX-Q1 asks the domain to drop it from the catalogue so the freed slot is refilled (otherwise a few answers show 2 chips where 3 were eligible).
3. **Chip tap feedback = replacement, not a spinner.** On tap the VM consumes the chip and appends the follow-up turn (section 3). The row disappears at once (source item is no longer last), the existing `AiTypingIndicator` plus "Cancel" appear, and the transcript auto-scrolls. All other chips are gone, so double taps and cross taps are impossible by construction (INV-F9). Cancel or failure uses the existing error text plus "Retry"; retry re-executes the edited intent (INV-F17) and no chips return until a success.
4. **Indicator strip** (section 4). Outside the LazyColumn, between the transcript and the input row, so it stays visible while scrolling and sits where the user types.
5. Nothing else on the sheet changes: header, starter prompts (empty transcript only), disabled block, clear-history dialog, input row.

## 3. Chip turn in the transcript
- A chip tap appends a **follow-up line**, not a normal user bubble: start-aligned, `labelMedium`, `onSurfaceVariant`, a leading decorative "subdirectory arrow" icon, text = chip label ("Previous month", "Remove Lidl"). No bubble fill, no right alignment. Reason: the user did not type it; a right-aligned bubble would misrepresent it and "Remove Lidl" would look like a typed sentence.
- Data stays `User(origin = FOLLOW_UP)`, persisted kind FOLLOW_UP only under opt-in history (INV-F10). Typed turns and C-4 clarification taps keep the normal user bubble (those are QUERY, INV-C13).
- Same style for a future restored FOLLOW_UP row (INV-F16 says "plain user bubble"; UX-Q2 asks to align the domain note).
- CD: "Follow-up: {label}". The label is the only text shown; never the executed intent, filter values or amounts.
- SHOW_TRANSACTIONS adds no turn (INV-F8 / section 2.2).

## 4. Context indicator and "New question"
- Format: "Follow-ups for: Food · Oct" / «Συνέχεια για: Φαγητό · Οκτ». Parts: up to 2 category names then "+n more", merchant, "+ filters" token if min/max/types/ownership are set (no values), short period (INV-F12, §3.10). Rendered from ids at display time, so a rename updates it.
- Wording is deliberate: in inc 1 a *typed* query never inherits context (INV-F1). "Follow-ups for" tells the user the context feeds the chips only. Revisit the wording when inc 1b adds free-text inheritance (UX-Q4).
- Placement: the strip above the input row, tonal surface (`surfaceVariant` 0.4), `bodySmall`, a trailing text button "New question" (>=48dp, `TextButton`). At large font or narrow width the button wraps under the text (Column fallback, no truncation of the button).
- Shown iff `lastIntent != null`, including when the catalogue returns 0 chips (indicator only, AE-F19 context). Hidden for NONE, pending-clarification-only (INV-F19), Unsupported, Error and Clarification answers.
- During chip execution it keeps showing the base context; it flips to the edited one with the new answer.
- **New question tap:** no confirmation (non-destructive: transcript stays, nothing is deleted). Effects: strip disappears, any chips already gone, focus moves to the input field (keyboard not forced), polite announcement "New question. Follow-up context cleared." No "Context cleared" note for a user-initiated clear. Log `FOLLOW_UP_NEW_QUESTION`.
- Also clears with: typed submit, header "Clear current conversation", clear-all-history (no note for those).

## 5. Empty result card (typed `Empty`, INV-F13)
```
| (*) Assistant                               |
|  No matching transactions · Sep 2026        |   titleMedium, wraps
|  (no amount, no "0.00", no Open button)     |
  (Remove Lidl) (Previous month)
```
- Single line "No matching transactions · {period}" (`el` «Καμία συναλλαγή · Σεπ 2026»). Period label per §3.9 using locale-aware formatting (no hard-coded month names).
- Cause ALL_EXCLUDED_MISSING_RATE: title "Couldn't evaluate any transactions" plus a `bodyMedium` line "{n} transactions excluded: missing exchange rate" plus the period. No link to settings (no new navigation). The promoted chip is "Remove amount filter" (AE-F23).
- `Empty` has no query title and no amount; the card does not invent one (UX-Q3).
- If `comparison` is present (AE-F4), the comparison line renders below, same style as section 6.
- Chips: at most 2, Remove first (promoted, leftmost), then Previous {unit}. No Compare/By category/Show. If neither is eligible (e.g. Custom period, no filter) the row is absent and the indicator still shows.
- Persisted/announced text is the same localized line (section 3.8 of the domain spec).

## 6. Comparison line (TOTAL only, INV-F14)
Rendered inside the result card directly under the primary amount, `bodyMedium`, `onSurfaceVariant`. Never in the separate `AiInsightsCard` (the Summary has `supportingText = null` when a comparison exists). No delta, percentage, arrow or red/green color in inc 1.
| Outcome | Line (EN) |
|---|---|
| Available(single currency) | "Previous period (Sep 2026): 80.00 EUR" |
| Available(NoTransactions) | "Previous period (Sep 2026): no transactions" |
| Unavailable(MIXED_CURRENCIES) | "Comparison unavailable: multiple currencies" (leading decorative info icon) |
- Currency code with every amount. The current side keeps today's per-currency primary ("100.00 EUR + 20.00 USD"); it wraps at large font and is never summed.
- The unavailable line is informational, not an error: no `error` color, no retry.
- Never "0.00" for an empty side; never a cross-currency number.
- Rounding as the user sees it: 2 decimals, half-up, existing formatting; no new rounding in inc 1.

## 7. Invalidation UX (INV-F11, INV-F20)
- On any reason: indicator and chips vanish; the strip slot shows a **note** in the same position: info icon, "Context cleared" (`titleSmall`) plus the reason line (`bodySmall`); not error colored; not persisted; `liveRegion = Polite`.
- The note clears on the next typed submit, any chip tap, New question or clear session. The transcript is unchanged; nothing is recomputed against the stale id.
- Reasons: CATEGORY_REMOVED, HOME_CURRENCY_CHANGED, ASSISTANT_DISABLED, VALIDATION_UNAVAILABLE (fail closed). Rename is not a reason.
- ASSISTANT_DISABLED: while disabled the sheet shows the existing disabled block only (no transcript, so no note). Recommended: show the note once on re-enable (UX-Q6).
- Invalidation occurs while the sheet is closed (observers are VM-lifetime); the note is simply waiting on next open.

## 8. UI state model (one sealed state per region, derived from VM; domain cause in brackets)
**ChipRowState** (per latest Result item):
| State | Cause | UI |
|---|---|---|
| Hidden | NONE context, non-last item, source not last (turn in flight/error/cancel), Clarification/Unsupported/Error answer, invalidated, disabled | nothing |
| Visible(chips 1..3) | ACTIVE + non-empty catalogue output [INV-F5, F9] | row |
| Visible(chips 1..2, empty-promoted) | `Empty` answer [INV-F13, 3.8] | row, Remove first |
| Consumed | tap accepted [epoch++] | row removed, turn line + typing indicator |
| Expired tap (race) | stale epoch [FOLLOW_UP_CHIP_EXPIRED] | silent no-op; no UI, no message |

**ContextStripState**: `Hidden` (NONE / pending-only) | `Indicator(spec)` (ACTIVE or EXECUTING) | `Note(reason)` (invalidated, until cleared) | `DisabledBlock` (existing UI dominates).

**Result card states** (new/changed): `Summary(+comparison: Available/NoTransactions/Unavailable)`, `Empty(NO_MATCHING_TRANSACTIONS | ALL_EXCLUDED_MISSING_RATE)`, `Breakdown(rows=[])` (matched but uncategorized-only, S11: show "No categorized transactions in this period"; not the empty card; UX-Q3), existing Unsupported with Part C C-2 bounded message (`assistant_unsupported` title + localized reason, never raw text), Clarification with disabled expired options (OD-C3), `Error` (existing text + Retry).
- Loading: `isLoading` => typing indicator + Cancel (existing). Offline: chips are local; the sheet shows no offline state for chip turns. Consent/permission: none required (no cloud, no permission); chip turns never show a privacy-blocked card.
- Unavailable data is never rendered as zero: missing rate => ALL_EXCLUDED cause line; mixed currency => comparison unavailable; unavailable context validation => note.
- Spec states with no UI by design: FOLLOW_UP_CHIP_EXPIRED (log only), EXECUTING with moved epoch (result appended, no chips: same as Hidden). UI states with no spec cause: none found. Spec gap: `Breakdown(rows=[])` copy (UX-Q3).

## 9. Money display
- Currency code beside every amount ("80.00 EUR"). Mixed currencies stay as per-currency sums joined by " + ", no home-equivalent, no total across currencies (non-goal).
- Unavailable conversion: excluded count in the ALL_EXCLUDED line or existing partial-data warning; never "N/A"/"0.00"/"UNKNOWN" on the empty and comparison paths (RF-F7 for non-empty rows is routed).
- Labels contain no amounts (REMOVE amount chip says "Remove amount filter" only).

## 10. Destructive or irreversible actions
None. No chip writes data. "New question" is reversible in effect (the user can re-ask; old chips cannot return, by design) and needs no confirmation or undo. Existing clear-history dialog is untouched.

## 11. Accessibility
- Chips: `SuggestionChip` with `role = Button`; touch target >=48dp (`minimumInteractiveComponentSize`), 8dp spacing both axes; label wraps to 2 lines then ellipsis (merchant names clipped visually at ~28 chars, full text in CD).
- Row container: group semantics, CD "Follow-up suggestions"; TalkBack order: header, transcript top to bottom, latest card, chip row, indicator strip ("New question" last in the strip), input, Send.
- Chip CDs add context the label omits: "Previous month: show September 2026"; "Remove filter: Lidl"; "Remove category filter"; "Compare with previous period"; "By category: group this answer by category". Dynamic text is read from the same formatter as the label.
- New answer: result card is a polite live region (title, primary, comparison/empty line). After a **chip** turn (focused chip vanished) move accessibility focus to the new result card; after a **typed** turn leave focus in the input and rely on the live region. Chips are not auto-announced; the group CD is reached by swipe.
- Indicator and note: text plus decorative icons (`contentDescription = null`); "New question" CD "New question. Clears follow-up context". Note is a polite live region.
- Font scale 200%: chips wrap onto multiple rows, strip stacks vertically, nothing is scroll-hidden. Contrast: Material tokens only (>=4.5:1); no meaning by color alone (unavailable comparison uses an icon and words).
- Follow-up line has no touch behavior, so no target requirement.

## 12. Copy (new keys; `values`, `values-el`, `values-es`; owner reviews Greek, Spanish proposed)
Per-unit keys, not a template (Greek agreement, INV-F12). `%1$s` = category/merchant text from user data, shown as plain text only.
| Key | EN | el | es |
|---|---|---|---|
| assistant_followup_group_cd | Follow-up suggestions | Προτάσεις συνέχειας | Sugerencias de seguimiento |
| assistant_followup_prev_day / _week | Previous day / Previous week | Προηγούμενη ημέρα / Προηγούμενη εβδομάδα | Día anterior / Semana anterior |
| assistant_followup_prev_month | Previous month | Προηγούμενος μήνας | Mes anterior |
| assistant_followup_prev_quarter / _year | Previous quarter / Previous year | Προηγούμενο τρίμηνο / Προηγούμενο έτος | Trimestre anterior / Año anterior |
| assistant_followup_prev_cd_format | %1$s: show %2$s | %1$s: εμφάνιση %2$s | %1$s: mostrar %2$s |
| assistant_followup_compare | Compare | Σύγκριση | Comparar |
| assistant_followup_by_category | By category | Ανά κατηγορία | Por categoría |
| assistant_followup_remove_format | Remove %1$s | Αφαίρεση %1$s | Quitar %1$s |
| assistant_followup_remove_cd_format | Remove filter: %1$s | Αφαίρεση φίλτρου: %1$s | Quitar filtro: %1$s |
| assistant_followup_remove_merchant / _category / _amount | Remove merchant / category / amount filter | Αφαίρεση φίλτρου εμπόρου / κατηγορίας / ποσού | Quitar filtro de comercio / categoría / importe |
| assistant_followup_turn_cd_format | Follow-up: %1$s | Συνέχεια: %1$s | Seguimiento: %1$s |
| assistant_context_label_format | Follow-ups for: %1$s | Συνέχεια για: %1$s | Seguimiento de: %1$s |
| assistant_context_more_categories_format | +%1$d more | +%1$d ακόμα | +%1$d más |
| assistant_context_more_filters | + filters | + φίλτρα | + filtros |
| assistant_context_new_question | New question | Νέα ερώτηση | Nueva pregunta |
| assistant_context_new_question_cd | New question. Clears follow-up context | Νέα ερώτηση. Διαγράφει το πλαίσιο συνέχειας | Nueva pregunta. Borra el contexto de seguimiento |
| assistant_context_new_question_announce | New question. Follow-up context cleared. | Νέα ερώτηση. Το πλαίσιο συνέχειας διαγράφηκε. | Nueva pregunta. Contexto de seguimiento borrado. |
| assistant_context_cleared | Context cleared | Το πλαίσιο διαγράφηκε | Contexto borrado |
| assistant_context_cleared_category | A category in the last answer was changed or removed. | Μια κατηγορία της τελευταίας απάντησης άλλαξε ή αφαιρέθηκε. | Se cambió o eliminó una categoría de la última respuesta. |
| assistant_context_cleared_home_currency | Your home currency changed. | Το βασικό σας νόμισμα άλλαξε. | Cambió tu moneda principal. |
| assistant_context_cleared_disabled | The assistant was turned off. | Ο βοηθός απενεργοποιήθηκε. | El asistente se desactivó. |
| assistant_context_cleared_unavailable | Couldn't check your data. | Δεν ήταν δυνατός ο έλεγχος των δεδομένων σας. | No se pudieron comprobar tus datos. |
| assistant_empty_no_matching_format | No matching transactions · %1$s | Καμία συναλλαγή · %1$s | Ninguna transacción coincidente · %1$s |
| assistant_empty_unevaluable_format | Couldn't evaluate any transactions · %1$s | Δεν ήταν δυνατή η αξιολόγηση συναλλαγών · %1$s | No se pudo evaluar ninguna transacción · %1$s |
| assistant_empty_excluded (plurals) | %d transaction / transactions excluded: missing exchange rate | %d συναλλαγή εξαιρέθηκε / συναλλαγές εξαιρέθηκαν: λείπει η ισοτιμία | %d transacción excluida / transacciones excluidas: falta el tipo de cambio |
| assistant_breakdown_no_categorized | No categorized transactions in this period | Καμία κατηγοριοποιημένη συναλλαγή στην περίοδο | Ninguna transacción categorizada en este periodo |
| assistant_comparison_available_format | Previous period (%1$s): %2$s | Προηγούμενη περίοδος (%1$s): %2$s | Periodo anterior (%1$s): %2$s |
| assistant_comparison_none_format | Previous period (%1$s): no transactions | Προηγούμενη περίοδος (%1$s): καμία συναλλαγή | Periodo anterior (%1$s): sin transacciones |
| assistant_comparison_unavailable_mixed | Comparison unavailable: multiple currencies | Η σύγκριση δεν είναι διαθέσιμη: πολλά νομίσματα | Comparación no disponible: varias monedas |
- Period labels (Full MONTH/WEEK/QUARTER/YEAR/DAY, to-date, custom range) come from a locale-aware formatter (ICU skeleton patterns per unit), not per-month strings; unit forms like "Week of 5 Oct 2026", "Q3 2026", "2026 to date", "Today" each get one format key per unit (el/es for the owner). Short form omits the year when it equals the current year.
- Errors and notes never contain raw OCR, notification, receipt or query text; only the user's own category/merchant names appear (already stored in QUERY rows) and never in logs (INV-F15).
- Not translated here (l10n track): existing assistant strings. See UX-Q7.

## 13. Open questions (UX-Q)
- **UX-Q1 (RF-F4).** Merge the duplicate. Rec: keep the card button, hide the SHOW_TRANSACTIONS chip in the UI now; domain-modeler then removes it from the catalogue (drops the Navigate action, retargets AE-F1/F7/F14/F19 slot expectations, example 1 becomes [Compare, Previous month, Remove Food]). Alternative: keep the chip and hide the card button on the latest answer only (inconsistent with older answers; not recommended).
- **UX-Q2.** Chip turn style: follow-up line (rec) vs right-aligned bubble. INV-F16 says future restored FOLLOW_UP rows render as plain user bubbles; rec amend to "render as the follow-up line".
- **UX-Q3.** `Empty` carries no title; this doc renders only the empty line plus period. `Breakdown(rows=[])` (uncategorized-only, S11) has no spec'd copy; rec the "No categorized transactions" line above, not the empty card. RF-F5's 8-row cap with no "+n more" remains routed.
- **UX-Q4.** Indicator wording "Follow-ups for:" prevents implying typed queries inherit it. Revisit in inc 1b. Rec accept.
- **UX-Q5.** Pre-existing: `Summary.supportingText` renders twice (inside the card, `AssistantResultCard.kt:69`, and again as `AiInsightsCard`, `AssistantSheet.kt:177-187`). The comparison line avoids this by design. Rec route a separate small fix (not inc 1 scope).
- **UX-Q6.** Show the invalidation note on re-enable after ASSISTANT_DISABLED. Rec yes (once); alternative: suppress, since "context lost" is implied by the cleared state.
- **UX-Q7.** Existing assistant strings have no `values-el` (l10n track); in Greek the sheet will mix Greek chips/notes with English title, Retry and placeholder. Rec ship anyway (additive), but flag in release notes; or hold Greek strings until the l10n track lands (owner decision).
