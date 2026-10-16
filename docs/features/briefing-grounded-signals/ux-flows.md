# UX flows: briefing-grounded-signals, increment 1 (merged Money Radar card)

Stage 4. Date 2026-10-16. Docs-only, post-1.0. Inputs: `brief.md` v2 (binding), Gate 1 Q1..Q10 (handoff). `domain-spec.md` is written in parallel; every assumption about it is a UX-Q item. `M/` = `app/src/main/java/com/yourname/expensetracker/`. [V] = checked in source this pass.

## 1. Entry points and navigation
- Only entry: Home dashboard, card at registry order 1, full width, under Financial Weather [V `DashboardWidgetRegistry.kt:44`]. No new screen, route, deep link or permission. Back behaviour unchanged (tap targets push existing destinations; back returns to Home with scroll kept).
- Card always composed once the dashboard has computed (brief; `ComputeDashboardWidgetsUseCase.kt:1262`). Until then Home's existing dashboard loading applies; the card has no own skeleton (UX-Q9).
- Edit mode (`HomeScreen.kt:335-345`, `WidgetWrapper` :996-1073): unchanged generic scrim with move up / hide / move down. Off switch for the whole summary = hide this widget.
- Tap destinations use existing callbacks only: `onNavigateToRecurring`, `onNavigateToTransactions(filter)`, `onNavigateToAnalytics("month")`, `onNavigateToReview` [V `HomeScreen.kt:80-86`].

## 2. The merged card

Anatomy, top to bottom: (1) header: dot + "Money Radar" + urgency badge; (2) score ring (64 dp, unchanged); (3) narrative area; (4) chips; (5) primary CTA (kept, logic unchanged). Narrative is full width; at font scale >= 1.3 or width < 360 dp the ring sits left of the badge line instead of beside the text, so sentences never share a row with the ring.

```
Default (template, Greek-length safe, cap 3)
+----------------------------------------------------+
| (o) MONEY RADAR                  [ ATTENTION NEEDED ]|
|  (42)   Summary from your data              (i)-label|
|  ring   * 2 bills due in the next 7 days (EUR 61.98) |
|           From: recurring bills                   >  |
|         * Unusual charge: Shell - needs review       |
|           From: unusual-charge alerts             >  |
|         * 3 expenses waiting for your review         |
|           From: expenses awaiting review          >  |
| [Event 2 bills] [Warning 1 alert] [Trend Med risk]   |
| [ View 2 upcoming bills                           ]  |
+----------------------------------------------------+
AI Ready: label row = sparkle + "AI Briefing"; body = one AI paragraph, no per-sentence rows
          (UX-Q1); runtimeStatusMessage line kept below; chips + CTA unchanged.
Radar UNAVAILABLE: header + neutral badge "Score unavailable"; no ring/chips/CTA;
  * Spending is on track compared with last month   (From: this month vs last month)
  (status line, no tag, not tappable) Bills, alerts and budget risk can't be checked right now
Large font 200%: header wraps (title above badge); ring row; sentences full width; chips wrap
  (FlowRow, not horizontal scroll); CTA text wraps to 2-3 lines, min height 48 dp.
```

Sentence row: min 48 dp, whole row is one click target (text + tag + chevron). Sentence = bodyMedium (up from bodySmall for legibility), tag = labelSmall TextSecondary, wraps, no ellipsis, no max lines. Non-tappable sentences (unchecked, see table) show no chevron and no ripple. First sentence keeps the old emphasis (Medium weight, urgency-coloured dot); others muted dot. Dot is decorative, never the only urgency cue (badge carries text).

Label row above the sentences (text + icon, never colour alone): AI Ready = sparkle + "AI Briefing" (reuse `home_ai_briefing_title` [V strings.xml:793]); template = list icon + "Summary from your data"; neutral = no label. Template is never styled like AI (different icon, no indigo AI tint; keep the card's urgency tint).

## 3. Per-fact tap destinations (existing screens only)

| Fact | Tap goes to | Status |
|---|---|---|
| Bills due | `onNavigateToRecurring()` | OK, same as chip/CTA [V :825] |
| Anomaly alerts | `onNavigateToTransactions(month range)` | GAP: no alerts screen in nav (no Anomal* destination [V]); lands on all of the month's transactions, not the alerts. Keep, label honestly "Open this month's transactions" (UX-Q4) |
| Budget risk HIGH/CRITICAL | `onNavigateToAnalytics("month")` | OK [V :833-835] |
| Budget risk MEDIUM | same Analytics month | GAP today: MEDIUM tap does nothing [V :832-836]. Recommend one destination for all tiers; also fix chip/CTA (UX-Q5) |
| Pending review | `onNavigateToReview()` | OK, same as PendingReviewAlert widget |
| Pace (lower/higher/on track) | `onNavigateToTransactions(month range)` | OK, same as SpendingTrend tap [V :513-519] |
| Unchecked (no exchange rate) | none in inc 1 | GAP: no screen lists no-rate expenses. Non-tappable; future `why-this-number` (UX-Q6) |
Chips and CTA keep their current actions; the CTA may duplicate the first sentence's destination (accepted, brief keeps CTA).

## 4. UI state model

One sealed state for the card (name is a suggestion, not code): `RadarCardUiState`.

| State | Rendering | Produced by (assumed; confirm in domain-spec) |
|---|---|---|
| HiddenByUser | not composed, no gap | widget `isVisible=false` [V `HomeViewModel.kt:379,463-473`] |
| DashboardLoading | Home-level loading, no card | `ProcessedDashboardUiState.Loading` [V :104] |
| Scored + narrative | full card | Radar score present, level GREEN/YELLOW/RED |
| RadarUnavailable + narrative | no ring/chips/CTA; badge "Score unavailable"; status line (section 2); independent facts (pace, pending, unchecked) as sentences | `UrgencyLevel.UNAVAILABLE` / null score (fail-closed on missing rate [V `ComputeMoneyRadarUseCase.kt:171,246,300`]). Never shows "nothing due" |
| NoData (new user) | badge "Getting started" (no "All Clear"), no ring, neutral no-data line, no chips/CTA | UX-Q7: domain must expose "empty ledger" distinct from all-clear |

Narrative sub-state (sealed `NarrativeUi`), precedence AI, template, neutral (brief):
| Narrative | When | Notes |
|---|---|---|
| AiBriefing | `AiLoadState.Ready` [V `HomeScreen.kt:531`] | label "AI Briefing"; paragraph; no tags (UX-Q1); `isAi` stays true only here |
| Template(1..3 sentences) | not Ready (Disabled, loading, error, cloud off, AI off, notifications denied, offline) and >= 1 fact | label "Summary from your data"; never `isAi` |
| Neutral | no facts and Radar scored | "Nothing needs your attention today" |
| PartialStatus | RadarUnavailable with no independent facts | the status line alone is the narrative |
| NoDataLine | NoData | "Add expenses to see your daily summary" |
- Observable spec states with no UI: none known; a template compute exception has no UI by design (falls to Radar-unavailable/neutral, bounded reason code logged, never content). UI states with no spec cause: NoData (UX-Q7), PartialStatus (needs per-fact availability, UX-Q8).
- Offline: no state. Everything is local; cached AI artifact still shows if Ready. No offline banner, no spinner, no "AI unavailable" nag on the card (AI off / cloud off simply yields the template; the existing `home_ai_briefing_unavailable` error text must NOT appear in the card).
- AI turning Ready after first paint: crossfade the narrative (no motion if animations are off), keep card height changes animated via `animateContentSize`, no live-region announcement.
- Error: no user-visible error state. Messages only from the fixed strings below; never raw merchant/OCR/notification text in diagnostics (merchant names shown only in-UI from the user's own alerts).

## 5. Money display
- Every amount = home currency with code, via `CurrencyFormatter`, locale-formatted (e.g. "EUR 61.98" / "61,98 EUR" per locale). No bare numbers that look like money.
- Never sum across currencies. Bills total shown only when the Radar value exists (home currency, converted); if unavailable the sentence drops the amount and states the count only. Never "0" or "EUR 0.00" for unavailable.
- Anomaly sentences: merchant only, never an amount (alert has no currency).
- Unchecked line: "N expenses not included (no exchange rate)" is the only mixed-currency disclosure; pinned (UX-Q3). Its count is an integer, never money.
- Rounding as the user sees it: bills total 2 decimals; budget chance as whole percent; no amounts in pace sentence.
- Existing CTA/budget text "You may exceed your budget by %s" [V strings.xml:963-965] is built with budgetAmount 0.0 and no code check; confirm it shows currency (UX-Q10).

## 6. Many facts, 0-20% pace, removal of the Insight card
- Cap 3 sentences, highest priority first (order from domain-spec). Overflow: no expander and no "+N more" in inc 1; counts of bills/alerts/budget stay visible on chips, so only pace/pending/unchecked can be dropped. Exception: unchecked is a data-quality caveat, pinned as the 3rd line when N>0 (UX-Q3).
- Pace 0-20%: sentence "Spending is on track compared with last month" with tag; pace unavailable: no pace sentence (never "on track" by default).
- Upgrade: separate Insight card disappears. The widget list is built from computed items [V `HomeScreen.kt:323-329`] so the grid reflows, no blank cell. Saved config rows with id `insight` hit existing unknown-ID handling [V `HomeViewModel.kt:383`] (domain Q5). The summary now lives where Radar sits, which for customised layouts may be higher or lower than Insight was.
- Edit mode: the "Insight" tile is gone; move arrows recompute over the shorter list; Radar tile unchanged. Users who hid Insight but kept Radar now see the narrative (no per-user override; UX-Q2). Users who hid Radar but kept Insight lose all summary text (accepted by Gate 1 Q4; UX-Q2).
- No one-time banner (needs persisted flag). Release notes only.

## 7. Destructive or irreversible actions
None new. Hiding the card is reversible in edit mode (same toggle), consistent with other widgets, no confirmation. Because hiding now removes the summary too, the hide button description for this tile names it (key `radar_a11y_hide_widget`). No writes from the card.

## 8. Accessibility
- Remove the whole-card `contentDescription` ("... Tap for details." [V `MoneyRadarWidget.kt:107-114`]): it is not a click target and hides per-sentence semantics.
- TalkBack order: (1) header node as heading: "Money Radar, Attention needed, score 42 out of 100" (ring and badge not separate stops); (2) label row ("Summary from your data" / "AI Briefing"); (3) each sentence one node: sentence + "From: ..." in one `contentDescription`/merged semantics, role Button, `onClickLabel` e.g. "Open recurring bills"; non-tappable sentences have no click action and no "double tap" hint; (4) chips; (5) CTA. Status line (Radar unavailable) read as plain text before sentences.
- Touch targets >= 48 dp (rows, chips, CTA). Decorative dots, chevrons, ring track: `contentDescription = null`.
- Font 200%: no fixed heights; no `maxLines`; header wraps; chips wrap; test at 200% EN and EL.
- Contrast: source tags and TextSecondary on the 5% urgency tint must reach 4.5:1 in light and dark; AI vs template distinguished by label text + icon, urgency by badge text, not colour alone. Dark theme: reuse `SemanticColors`; no hard-coded light tints.
- Badge: `uppercase()` must pass the locale (Greek capitals drop accents by convention); consider sentence case to avoid shouting at 200%.

## 9. Copy and string keys (EN final-ish; EL DRAFT, owner/native review required)
Plurals use `<plurals>` (EL has one/other). Pace lower/higher reuse the Insight card's existing strings (no duplicates); "on track" reuses the `home-insight-gap` string; keys below are suggestions.

| Key | EN | EL (draft) |
|---|---|---|
| radar_title | Money Radar | Money Radar |
| radar_level_green / yellow / red | All Clear / Attention Needed / Action Required | Όλα καλά / Χρειάζεται προσοχή / Απαιτείται ενέργεια |
| radar_level_unavailable | Score unavailable | Μη διαθέσιμη βαθμολογία |
| radar_level_no_data | Getting started | Αρχή |
| radar_chip_bills (plural) | %d bill / %d bills | %d λογαριασμός / %d λογαριασμοί |
| radar_chip_alerts (plural) | %d alert / %d alerts | %d ειδοποίηση / %d ειδοποιήσεις |
| radar_chip_budget_critical / high / medium / on_track | Critical / High risk / Medium risk / On track | Κρίσιμο / Υψηλός κίνδυνος / Μέτριος κίνδυνος / Εντός στόχου |
| radar_cta_bills (plural) | View %d upcoming bill / bills | Προβολή %d επερχόμενου λογαριασμού / λογαριασμών |
| radar_cta_anomalies (plural) | Review %d unusual charge / charges | Έλεγχος %d ασυνήθιστης χρέωσης / χρεώσεων |
| radar_label_template | Summary from your data | Σύνοψη από τα δεδομένα σας |
| (reuse) home_ai_briefing_title | AI Briefing | check EL exists |
| radar_fact_bills (plural, args n, days) | %d bill is due in the next %d days / %d bills are due in the next %d days | %d λογαριασμός λήγει τις επόμενες %d ημέρες / %d λογαριασμοί λήγουν τις επόμενες %d ημέρες |
| radar_fact_bills_total | ... (%s total) suffix | ... (σύνολο %s) |
| radar_fact_anomaly_single | Unusual charge: %s needs review | Ασυνήθιστη χρέωση: %s, χρειάζεται έλεγχο |
| radar_fact_anomalies (plural) | %d unusual charges need review | %d ασυνήθιστες χρεώσεις χρειάζονται έλεγχο |
| radar_fact_budget_medium / high / critical (%d%%) | You may go over your budget (%d%%) / High chance of going over your budget (%d%%) / Very likely to go over your budget (%d%%) | Ενδέχεται να ξεπεράσετε τον προϋπολογισμό (%d%%) / Υψηλή πιθανότητα να ξεπεράσετε τον προϋπολογισμό (%d%%) / Πολύ πιθανό να ξεπεράσετε τον προϋπολογισμό (%d%%) |
| radar_fact_pace_lower / higher / on_track | reuse; "Spending is on track compared with last month" | reuse; "Οι δαπάνες είναι σε αναμενόμενα επίπεδα σε σχέση με τον προηγούμενο μήνα" |
| radar_fact_pending (plural) | %d expense is waiting for your review / %d expenses are waiting... | %d δαπάνη περιμένει έλεγχο / %d δαπάνες περιμένουν έλεγχο |
| radar_fact_unchecked (plural) | %d expense not included (no exchange rate) / %d expenses not included (no exchange rate) | %d δαπάνη δεν περιλαμβάνεται (δεν υπάρχει ισοτιμία) / %d δαπάνες δεν περιλαμβάνονται (δεν υπάρχει ισοτιμία) |
| radar_neutral_nothing | Nothing needs your attention today | Τίποτα δεν χρειάζεται την προσοχή σας σήμερα |
| radar_neutral_no_data | Add expenses to see your daily summary | Προσθέστε δαπάνες για να δείτε την ημερήσια σύνοψη |
| radar_status_unavailable | Bills, alerts and budget risk can't be checked right now | Οι λογαριασμοί, οι ειδοποιήσεις και ο κίνδυνος προϋπολογισμού δεν μπορούν να ελεγχθούν αυτή τη στιγμή |
| radar_src_bills | From: recurring bills | Από: επαναλαμβανόμενους λογαριασμούς |
| radar_src_anomaly | From: unusual-charge alerts | Από: ειδοποιήσεις ασυνήθιστων χρεώσεων |
| radar_src_budget | From: budget forecast | Από: πρόβλεψη προϋπολογισμού |
| radar_src_pace | From: this month vs last month | Από: σύγκριση με τον προηγούμενο μήνα |
| radar_src_pending | From: expenses awaiting review | Από: δαπάνες προς έλεγχο |
| radar_src_unchecked | From: expenses without an exchange rate | Από: δαπάνες χωρίς ισοτιμία |
| radar_a11y_header_format | Money Radar, %1$s, score %2$d out of 100 | Money Radar, %1$s, βαθμολογία %2$d στα 100 |
| radar_a11y_header_no_score_format | Money Radar, %1$s | Money Radar, %1$s |
| radar_a11y_open_bills / _transactions / _analytics / _review | Open recurring bills / Open this month's transactions / Open monthly analytics / Open expenses awaiting review | Άνοιγμα επαναλαμβανόμενων λογαριασμών / Άνοιγμα συναλλαγών του μήνα / Άνοιγμα μηνιαίων αναλύσεων / Άνοιγμα δαπανών προς έλεγχο |
| radar_a11y_hide_widget | Hide Money Radar and daily summary | Απόκρυψη Money Radar και ημερήσιας σύνοψης |
Copy rules: no raw OCR/notification/receipt text anywhere; bounded fixed strings only; merchant text only from the user's own alert, rendered in-UI, never logged. Radar's other hard-coded strings ("Attention Needed", "Unavailable", icon descriptions, `RiskTier` chip labels, "Critical/High Risk/Medium Risk/On Track") are included above; any English left in the card in EL fails the brief's dogfood signal.

## 10. Open UX questions (UX-Q) with recommendations
1. AI Ready text has no fact IDs, so the per-sentence source-tag rule cannot apply (inc 1 keeps cloud text as is). Rec: AI paragraph + "AI Briefing" label, no tags; chips/CTA remain the tap targets; owner to confirm this is not a violation of "every sentence has a source tag" (which then applies to template only).
2. Layout intent on upgrade: hidden-Insight/visible-Radar users get the narrative; visible-Insight/hidden-Radar users lose it. Rec: no config migration, mention in release notes; owner to confirm.
3. Pin unchecked as the 3rd line when N>0 even if lower-priority? Rec: yes (data-quality honesty); domain to confirm ordering and that unchecked counts toward the cap.
4. Anomaly tap lands on month transactions, not alerts. Rec: keep, honest label; backlog an alerts list (or merchant filter if `TransactionFilter` supports it, UNVERIFIED).
5. MEDIUM-risk tap does nothing today. Rec: same Analytics month for all tiers on sentence, chip and CTA (small shipped-behaviour fix inside this card).
6. Unchecked sentence non-tappable. Rec: accept in inc 1; `why-this-number` follow-up.
7. New user / empty ledger: does Radar render "All Clear" with score? Rec: no; show "Getting started" and the no-data line. Needs a NoData signal in the domain spec.
8. Radar UNAVAILABLE whole-card fail-closed vs per-fact (domain Q3). Rec: score stays fail-closed, but bills/anomalies/budget lines may appear only if individually computable; otherwise the status line. Never zero-as-absent.
9. First-compute skeleton for the card? Rec: no; rely on existing Home loading and ensure no layout jump when the card appears at order 1 (keep a fixed-min-height placeholder only if dogfood shows jank).
10. Budget CTA/overrun amount currency: verify code is shown and `MonteCarloBudgetImpactUiMapper` output is localized to EL (currently EN strings [V strings.xml:963-967]); in scope as part of Radar localization.
11. Edit-mode scrim leaves the card's content in the TalkBack tree behind the scrim (existing, all widgets). Rec: out of scope; note for a11y pass. Also the hide button always shows VisibilityOff even for hidden widgets in edit mode [V `HomeViewModel.kt:379`]; out of scope.
12. Wording check: "your budget" assumes the overall budget is monthly/overall; domain to confirm period wording. EL strings need native review.
