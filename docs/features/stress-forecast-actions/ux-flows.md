# UX flows: stress-forecast-actions (UF-07)

Source: `Cost-agregator-int` @ e6967db1. Inputs: `domain-spec.md` (O-1 localize now, O-2 three informational kinds non-clickable). Paths relative to `app/src/main/java/com/yourname/expensetracker/`. No new permission, network, route, or screen. Design only.

## 1. Entry points & navigation
- Entry: Home dashboard -> `DashboardWidget.FinancialStressForecast` -> `ui/components/FinancialStressForecastCard.kt`, call site `ui/screens/home/HomeScreen.kt:919-939`. No deep link.
- Tap on an actionable row navigates (existing destinations only, spec section 4): R1 -> Recurring (`onNavigateToRecurring`, HomeScreen:927); R2 -> Transactions with this-month filter (`:930-932`); R3 -> Savings Goals (`:935`). System Back returns to Home, scroll position as other Home widgets (MoneyRadar `:896-916` behaves the same). Informational rows: no tap, no navigation, no ripple.
- Card callback carries the action type, never text (INV-3/4). HomeScreen maps it with an exhaustive `when` modelled on `MoneyRadarAction` (`:900-915`).

## 2. Screen: stress forecast card, recommendations section (only part that changes)
```
 STRESS FORECAST ...                 [risk badge]      (unchanged, still English: out of scope)
 ... horizon tabs / detail ...                         (unchanged)
 -------------------------------------------------
 Recommendations                       <- string, heading semantics
 +-----------------------------------------------+
 | (bulb) Your recurring obligations are...   >  |  actionable: tint + 1dp border + chevron, >=48dp
 +-----------------------------------------------+
   (bulb) Critical: Consider delaying non-es...      informational: no border, no chevron, plain text
```
- Actionable row = existing `RecommendationChip` (`FinancialStressForecastCard.kt:326-358`: indigo 8% tint, indigo 20% border, `minimumInteractiveComponentSize` + `heightIn(min=48.dp)`) plus a trailing `Icons.Rounded.ChevronRight`, `contentDescription = null`, tint `SemanticColors.TextSecondary` (same pattern as the Home feature row `HomeScreen.kt:1778-1782`).
- Informational row = non-clickable `Surface`/`Row`, same padding/shape/bulb/text style, neutral tint (no indigo border), no chevron, no `onClick`, no min-48dp interactive sizing (not interactive). Distinction is never colour alone: border + chevron present vs absent.
- Visible cap: `take(2)` stays, extracted to one named constant (INV-11). Domain order decides the two shown. No "+N more" indicator (see Q4). Because R4 is emitted after R1-R3 and R5/R6 only appear alone, an informational row can displace an actionable one only if fewer than 2 actionable kinds fire, so no actionable row is lost to an informational one.

## 3. UI state model (card level; sealed state is derived, no ViewModel change)
| State | Produced by | Rendering |
|---|---|---|
| Unavailable | `overallRisk == null` or `failure != null` (card `:32-45`; engine `:186-199`, AE-7) | existing card with `stress_forecast_unavailable` (already EN/EL/ES). Never shown as "no concerns" or zero. |
| Content, recs hidden | recommendations empty without failure (defensive; engine always emits R5 or R6 on success, INV-10) | divider, header and rows omitted (`:180`). |
| Content, recs shown | 1..n items | first 2 rows, each Actionable or Informational by `kind.action` (INV-5). |
Loading: card is only composed once the dashboard widget exists (no card-level spinner). Offline, consent-denied, permission-denied: not applicable (local derivation, no permission, no network). Spec states with no UI: none missing. UI states with no spec cause: "recs hidden" is defensive only (flag). R6 at MODERATE risk reads "No immediate concerns" next to a MODERATE badge: preserved (spec O-4), see Q3.

## 4. Parameters and money display
- R2 `daysAhead` (30/60/90): `<plurals>` `stress_rec_reduce_discretionary_spending`, resolved with `pluralStringResource(id, daysAhead, daysAhead)`. 30/60/90 never hit "one" in EN, but plurals are used so EL/ES/lint have the correct grammar structure and any future horizon (e.g. 1) is correct.
- R3 buffer: string `%1$s` = `CurrencyFormatter.formatMoney(bufferTarget, bufferCurrency, showCents = false)` (`domain/util/CurrencyFormatter`, same call/rounding as `FinancialStressForecastCard.kt:227`). Whole units, currency symbol/code always shown by the formatter, no conversion (already display currency), no UI arithmetic. Use `bufferCurrency`, not the card `currency` param (Home passes `homeCurrency ?: ""`, `HomeScreen:922`, INV-9).
- `bufferCurrency` blank: use `stress_rec_build_emergency_buffer_no_amount` (no amount, no guessed currency).
- Mixed currency / stale FX: unchanged, `excludedCount` warning (`:122-129`) still covers the card; the buffer value is never shown as 0 when missing.

## 5. Destructive / irreversible actions
None. Tapping only navigates; nothing is written, so no confirmation or undo.

## 6. Accessibility
- Actionable row: single merged node (text only, bulb glyph and chevron excluded), `role = Role.Button` set only when actionable (precedent: conditional role in `BudgetBlockPartyCard.kt:215-216`), `onClickLabel` from `stress_rec_action_*` so TalkBack says "<text>, Button, double tap to open recurring payments". Do not rely on `Surface(onClick)` default semantics; implementer verifies role is announced.
- Informational row: no role, no click label, no "double tap" hint; read as plain text. Bulb glyph hidden from semantics in both (avoids "light bulb" announcement).
- Header: `Modifier.semantics { heading() }`. TalkBack order = visual (header, row 1, row 2).
- Touch target: actionable >= 48dp x full width (existing). Informational need none.
- Font scale: no fixed heights, no `maxLines`/ellipsis; rows grow (EL/ES run longer than EN). Check 200%.
- Contrast: `TextPrimary` on the 8% indigo/neutral tint (existing pairing); chevron is decorative so exempt; state is conveyed by role/chevron/border, not colour only.

## 7. Copy: new string keys (EN draft; EL/ES drafted by implementer, owner-signed)
Resource files: `res/values`, `values-el`, `values-es` (EN-only fails lint, spec section 8). Positional placeholders only. EN of kinds R1/R2/R3/R6 kept verbatim from the engine (behavior-preserving); R4/R5 must be copied verbatim from `FinancialStressForecastEngine.kt:728/:734` (spec quotes them truncated).
| Key | EN draft |
|---|---|
| `stress_recs_header` | Recommendations |
| `stress_rec_review_recurring_obligations` | Your recurring obligations are high. Consider canceling unused subscriptions. |
| `stress_rec_reduce_discretionary_spending` (plurals) | one: You may run out of money in %1$d day. Consider reducing discretionary spending. / other: ...in %1$d days. ... |
| `stress_rec_build_emergency_buffer` | Your emergency buffer is low. Aim for at least %1$s. |
| `stress_rec_build_emergency_buffer_no_amount` | Your emergency buffer is low. Consider building it up. |
| `stress_rec_delay_non_essential_purchases` | Critical: Consider delaying non-essential purchases... (verbatim from engine :728) |
| `stress_rec_healthy_outlook` | Great job! Your financial stress level is low... (verbatim from engine :734) |
| `stress_rec_no_immediate_concerns` | No immediate concerns. Continue monitoring your spending. |
| `stress_rec_action_open_recurring` | Open recurring payments |
| `stress_rec_action_open_transactions` | View this month's transactions |
| `stress_rec_action_open_savings_goals` | Open savings goals |
EL/ES: drafted by implementer, flagged for owner sign-off (follow-recommendations memory: es/el sign-off still required). Errors/logs never contain recommendation text or amounts (INV-13); there is no raw OCR/notification/receipt text in this feature.

## 8. Header and "Min projected:" decision
- Header `"Recommendations"` (`:186`): becomes `stress_recs_header` in this feature (spec O-3). It sits directly over the newly localized rows, so leaving it English would make the section half-translated, and the fix is one line.
- `"Min projected:"` (`:272`): stays English and is a follow-up (it belongs to the horizon detail, not recommendations; also needs a `%1$s` string). Honest caveat: the rest of the card ("Stress Forecast", "Projected Balance", tabs, "% risk", subtitle) remains English; recommend a single follow-up "localize FinancialStressForecastCard" task.
- `take(2)`: unchanged behaviour, named constant only.

## 9. Open questions (recommendation first)
- Q1 Informational look: recommended neutral tint, no border, no chevron (vs dimmed chip). Needs owner nod.
- Q2 Localize header only, defer "Min projected:" and other card strings: recommended yes.
- Q3 R6 shown at MODERATE risk reads misleadingly: recommended preserve now, follow-up (changes emission rules, INV-2).
- Q4 Hidden 3rd/4th recommendation and no "+N more" affordance: recommended keep, follow-up.
- Q5 Buffer uses `bufferCurrency` while the other card amounts use `currency` (`homeCurrency ?: ""`); if they differ, the card mixes currencies: recommended follow-up check that `displayCurrency == homeCurrency`; do not change here.
- Q6 EL/ES wording: owner sign-off required before merge.
- Q7 Hide bulb emoji from TalkBack: recommended yes.
