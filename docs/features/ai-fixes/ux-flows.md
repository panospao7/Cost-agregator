# UX flows: ai-fixes (defect bundle; UI-visible changes only)

Author: ux-flow-designer (stage 4). Date 2026-10-16. Docs-only. Inputs: `brief.md` v2, `domain-spec.md` (INV-/AE- ids), `workflows/active/ai-fixes-handoff.md` §Human decisions (Q1(a), insight on-device only, pending warranties expire, on-device briefing unchanged).
`A` = `app/src/main/java/com/yourname/expensetracker`. No new screens, no new navigation, no new permissions, no network use. Everything below edits existing screens: `A/ui/screens/aisettings`, `A/ui/screens/warranty`, Home briefing slot, `A/ui/components/RecommendationCard.kt`.
Verified in source while writing: (1) `WarrantyTrackerViewModel.mutationError` has **no reader** in `WarrantyTrackerScreen` and the Scaffold has no SnackbarHost, so today's confirm error is invisible; (2) `values-el/strings.xml` has 0 `ai_*`/`warranty_*` keys and `values-es` has 1, so both screens are effectively English-only today; (3) `AiSettingsScreen.kt:129,137` also hard-code "Cloud AI blocked: ..." and "Cloud AI is active"; (4) `CapabilityMatrixRow` shows "Cloud fallback available" when `cloudFallbackAvailable` is true.

## 1. Entry points and navigation
Unchanged. Settings > AI settings (insight row, proactive copy); Warranty tracker (cards, snackbar); Home (briefing slot, recommendation cards). Back behaviour unchanged. No deep links. The new snackbar and the reject confirmation dialog (see 4.2) are transient and dismiss on back.

## 2. AI settings screen

### 2.1 Layout change (capability section, `ai_section_capability`)
```
 Dashboard briefing                [Ready]        (o--)   <- existing row, unchanged
   <runtime text>
 Transaction insight (on-device)   [Ready]        (--o)   <- NEW row, directly below
   <runtime text OR requirement caption>
   Adds one on-device sentence to a new recommendation when you add an expense
   while the app is open. Never uses the cloud.
 Receipt assist ...                                         <- existing
 (Warranty extraction row REMOVED, see 2.3)
```
Placement: a `CapabilityMatrixRow` immediately after "Dashboard briefing", because the insight was formerly bundled with it and existing users inherit its value. It stays in the capability matrix (not "Experimental") so it gets the same runtime badge and runtime text. Pass `cloudFallbackAvailable = false` always, so the "Cloud fallback available" hint can never appear on this row (INV-16). Do not render a route line implying CLOUD.

### 2.2 Insight row states (maps to INV-15/16, §1.6, §4)
| Situation | Switch | Caption under row | Source |
|---|---|---|---|
| `aiEnabled=false` | on/off per stored value, kept interactive (matches every other row) | `ai_insight_needs_ai` | INV-15 `AI_DISABLED` |
| `allowOnDeviceAi=false` | kept interactive | `ai_insight_needs_on_device` | INV-15 `ON_DEVICE_NOT_ALLOWED` |
| Nano AVAILABLE | interactive | status Ready | `OnDeviceModelStatus.AVAILABLE` |
| NOT_INSTALLED / DOWNLOADING / UNAVAILABLE / UNKNOWN (transient) | interactive | existing `toRuntimeStatusMessage` text + "Needs attention" badge | `OnDeviceRuntimePresentation.kt` |
| UNSUPPORTED_DEVICE / UNSUPPORTED_ANDROID_VERSION (permanent) | **disabled**, stored value kept, shown off if never on | existing message ("requires Android 14 or newer" / "does not support...") | same file |
| Loading runtime status | interactive | `ai_status_not_loaded` (existing) | existing |
| Settings write fails | reverts to stored value | bounded error line (section 5) | INV-2 |

Rule: a permanent platform limit is the only case that disables the switch, because toggling can never help and the explanation is already shown. The stored value is never rewritten by the screen. There is no consent-denied state (no privacy toggle governs on-device insight) and no offline state (on-device).
Default display: new installs show ON, upgrades show the inherited briefing value (INV-23); the screen just renders the stored value. "Often no insight" is normal (INV-17/18); no copy promises an insight for every expense.

### 2.3 Warranty extraction toggle (recommendation: HIDE the row)
After Q1(a) and INV-7 the flag gates no data flow. Options considered: relabel (still implies control, misleading), disabled with "not active" (clutter, permanent dead row, extra translation), **hide**. Recommend hide: remove the `CapabilityMatrixRow` at `AiSettingsScreen.kt:244-250`. Stored explicit values stay untouched and inert; the enum, router and `ai_capability_warranty_extraction` string stay because `runtimeLabel`/`DebugScreen` still need them. Replace the lost transparency with one static caption at the end of the capability section:
`ai_warranty_local_note` "Warranty periods are read on your device from receipt text. No AI or cloud is used for warranties."
Do not mention the stored flag. If the owner prefers visible-but-inert, use the disabled variant with the same caption (see UX-Q-4 for the related runtime-summary leak).

### 2.4 Proactive toggle copy (B3)
Move nothing; keep the toggle in "Experimental". Relabel so users understand it controls only the notification (INV-24/25):
- `ai_toggle_proactive` "Notify me when my daily briefing is ready"
- new caption under it, `ai_proactive_desc`: "Turning this off does not hide your briefing. It still appears on Home. This only controls the notification."
Truthful for on-device-only users too (they get no briefing yet, so nothing is hidden or promised).
If notifications are blocked at system level (only if UX-Q-3 is approved): caption `ai_proactive_notifications_blocked` "Notifications are blocked for this app in system settings, so no alert will be shown. Your briefing still appears on Home." No new permission request; no button in this bundle.

## 3. Home briefing after B3
The "card" is the existing `DashboardWidget.NaturalLanguageInsight` slot (`HomeScreen.kt:527-582`), which shows the AI briefing text when `aiBriefing` is `Ready` and the deterministic text otherwise. B3 needs **no composable change**: the artifact now exists for notification-denied and proactive-off users, so the slot already renders it.
| `aiBriefing` state | Cause | Renders |
|---|---|---|
| Ready | READY artifact for today's key (INV-24: generated regardless of permission/proactive) | AI text with existing icon and optional runtime/diagnostic lines |
| Idle | no artifact yet (worker not run, cloud route unavailable, no key) | deterministic text; no spinner, no error |
| Loading | artifact RUNNING | deterministic text (existing) |
| Error | FAILED artifact or flow error | deterministic text; the composable never displays the error string, keep it that way (no `entity.errorMessage` on screen) |
| Disabled | `aiEnabled=false` or briefing off | deterministic text |
Consent-denied cases (notifications denied, cloud privacy toggle off) are not errors on Home: notifications denied now yields Ready; cloud off yields Idle/deterministic as today. Offline: Home reads the stored artifact, so offline changes nothing; generation simply does not happen and the slot stays deterministic. The slot shows no "AI" or "cloud" caption today; leave as is.

## 4. Warranty tracker

### 4.1 Card appearance per `WarrantyReviewState` (INV-10; one function feeds badge, border, count, filters)
| State | Border | Badge | Date line | Actions |
|---|---|---|---|---|
| ACTIVE (not in review) | none | days-left badge if <=30 days (existing) | `warranty_expires_date` | Mark claimed, Delete (existing) |
| AWAITING_REVIEW | error border (existing) | `warranty_badge_needs_review` (existing) | `warranty_expires_date` | **Confirm**, **Reject** (existing), no Claim |
| EXPIRED_UNCONFIRMED | **neutral outline** (no error colour: nothing is urgent any more) | NEW `warranty_badge_expired_unconfirmed` "Expired - not confirmed" | `warranty_expired_date` "Expired on %1$s" | NEW `warranty_action_keep_expired` "Keep in history" (calls `confirmWarranty`), **Reject**; no Claim, never "Reactivate" (INV-13: EXPIRED never returns to ACTIVE) |
| EXPIRED (confirmed) | none | `warranty_badge_expired` (existing) | `warranty_expired_date` | as today |
Precedence for the badge: EXPIRED_UNCONFIRMED, then AWAITING_REVIEW, then expired, then days-left. The AutoAwesome "auto-detected" marker and confidence line stay. The status chip for review must not rely on colour alone: every state has a text badge.
Confirm on AWAITING_REVIEW keeps label `warranty_action_confirm`. Result states: ACTIVE with `needsReview=false` (INV-13). `AlreadyConfirmed` is silent success (card just refreshes). Sort: AWAITING_REVIEW first, then by end date.

### 4.2 Destructive actions
- Reject deletes the warranty and its return window atomically (AE-21) and cannot be undone through any legal path, so Undo is not offered. Recommend a confirmation dialog for Reject in both review states (new; today the screen deletes immediately): title `warranty_reject_confirm_title` "Remove this warranty?", body `warranty_reject_confirm_body` "It will be deleted from your warranties. Your receipt is not affected.", buttons `warranty_reject_confirm_action` "Remove" / existing cancel. See UX-Q-2.
- "Keep in history" and Confirm are not destructive; no dialog.
- Buttons on a card are disabled while its id is in `mutatingWarrantyIds` (AE-3); show a small inline progress indicator in place of the label.

### 4.3 Filters, counts, empty states
- Banner count and "Review" shortcut count AWAITING_REVIEW only (AE-16). The "Needs review" filter lists AWAITING_REVIEW only.
- "Expired" chip must include EXPIRED_UNCONFIRMED rows even before reconciliation writes them (a PENDING_REVIEW row past its end date has status PENDING_REVIEW but state EXPIRED_UNCONFIRMED). This is not stated in INV-10; see UX-Q-1.
- Empty (no warranties at all): existing `warranty_empty_title` / `warranty_empty_message` ("Scan receipts to automatically detect warranties") stay true: detection is local regex.
- Empty after filtering (warranties exist, filter matches none): today the same "No warranties tracked" empty state appears, which is wrong. NEW `warranty_empty_filtered_title` "Nothing here" and `warranty_empty_filtered_message` "No warranties match this filter."; for the review filter `warranty_empty_review_message` "Nothing left to review."
- Loading: existing spinner. No offline or consent state (all local). Load-failure UI is not defined by the spec and is not changed here.

### 4.4 Confirm error surface (Part A, INV-2/4)
`mutationError` is never shown today. Add a snackbar host to the screen Scaffold and show the bounded message; clear via the existing `clearMutationError`. Snackbar is a polite live region (announced by TalkBack). Message keys:
| Key | EN copy | Produced by |
|---|---|---|
| `warranty_error_confirm_failed` | "Couldn't confirm this warranty. Please try again." | any unexpected failure (AE-3, SQL text never shown) |
| `warranty_error_blocked` | "Warranties can't be changed while a backup or restore is running. Try again shortly." | `DatabaseAccessBlockedException` (AE-20) |
| `warranty_error_not_found` | "This warranty no longer exists." | repository `NotFound` (AE-19) |
`CancellationException` produces no message (AE-4/INV-4).

## 5. Bounded error messages in AI settings (Part A, INV-2)
Replace `"Settings could not be saved: ${e.message}"` (`AiSettingsViewModel.kt:335`) with `ai_error_settings_save_failed` "Settings couldn't be saved. Please try again." (AE-2). The VM should expose a string resource (existing `UiText.StringResource` pattern used by `HomeViewModel`) instead of a literal. Same closed-set rule for all literals the spec lists (`:107-113,153-283`) plus the two screen literals:
| Key | EN copy (current literal) |
|---|---|
| `ai_cloud_blocked_format` | "Cloud AI blocked: %1$s" (screen `:129`) |
| `ai_cloud_active` | "Cloud AI is active" (screen `:137`) |
| `ai_blocked_ai_off` / `_cloud_off` / `_no_key` / `_no_network` / `_wifi_only` / `_privacy` | "AI is turned off" / "Cloud AI is turned off in settings" / "No API key configured" / "No network connection" / "Wi-Fi required (Wi-Fi-only mode)" / "Blocked by Privacy Settings" |
| `ai_key_enter_valid` / `_enter_new_or_remove` / `_test_before_save` / `_saved` / `_removed` / `_required_cloud` / `_enter_before_test` / `_enable_ai_first` / `_enable_cloud_to_test` | current literals at `:153,163,173,184,199,226,227,237,246,254` reworded without changing meaning |
| `ai_test_no_network` / `_wifi_only` / `_status_unavailable` / `_cloud_unavailable` / `_passed` / `_failed` | current literals at `:263-283` |
Rules: no key takes an exception argument; the only format arguments allowed are the blocked-reason string and the settings-label strings. `runtime.message` (router/status constants) is verified bounded and stays English (out of scope, spec §15.6).
Greek: `values-el` and `values-es` currently contain essentially none of these screens' keys. Every new or changed key above and in sections 2 and 4 needs a Greek entry; until then Android falls back to English (acceptable, not an error). See UX-Q-5.

## 6. Insight surface
Insight sentences surface only as the text of a recommendation card (`RecommendationCard.kt`, `recommendationText`, 7-day expiry, INV-20). There is no separate insight UI.
- **Generated:** the card shows the on-device sentence in place of the deterministic text. Same card, same priority dot, "RECOMMENDATION" label, dismiss button.
- **No insight (any `Skipped` reason, INV-19):** the card shows the deterministic text, identical layout. No error, no placeholder, no skeleton, no "AI unavailable" line, and no toast. The user cannot distinguish the two, which is intended: "mostly no insight" is acceptable (INV-17/18).
- The card appears once after the job completes (non-starvation INV-18), so there is no text swap or flicker.
- Caption: none. Recommendation records carry no reliable provenance flag (`sourceArtifactId` is `""` or `"0"`, spec §15.8), so an "on-device" caption would need a new field. The privacy value is already carried by the settings row copy (2.1). If the owner wants a caption later it needs a domain field first.
- Length: insight text may reach 600 chars. Recommend limiting the card body to 4 lines with ellipsis and showing the full text in the existing detail view (`HomeViewModel.selectedRecommendation`; detail rendering UNVERIFIED). Accessibility description (`a11y_recommendation_format`) keeps the full text.
- Money: the model writes any amount in its sentence from `currencyCode`; the deterministic fallback hard-codes "€" regardless of currency (spec §15.3, out of scope but user-visible: a non-EUR user's normal "no insight" card can show the wrong symbol). Flag to owner; no UX workaround.

## 7. Money display
No money values are added or changed. Rule for any copy that includes an amount: always shown with its currency code or symbol from the expense currency, never summed across currencies, never shown as zero when unavailable. Nothing in this bundle displays totals; warranty cards do not show money.

## 8. Accessibility
- Insight row follows `CapabilityMatrixRow` semantics: content description = label + on/off + runtime text (`ai_capability_cd_format`). Add the permanent description sentence as a separate text node so TalkBack reads: label, status badge, switch, runtime text, description. Disabled (unsupported) switch must announce "disabled" plus the reason caption (keep the reason in the same merged node).
- Proactive toggle uses `ToggleRow` (merged cd "label, enabled/disabled, double tap to toggle"); the new caption sits directly after it in reading order and is not merged into the switch.
- Warranty cards: badges are text, so colour is never the only signal (error border removed for expired-unconfirmed on purpose). Action buttons keep >= 48dp targets. Two weighted buttons in a row will wrap at 200% font scale with "Keep in history"; use a vertical stack when width is insufficient and allow multi-line labels (no truncation of action text).
- Snackbar: polite live region, text duration long enough for reading; the dismissal does not move focus. Reject dialog: focus on title, default focus on "Cancel", destructive button not default.
- Filtered-empty and "Nothing left to review" are plain text, readable, with no icon-only meaning.
- Contrast: use theme tokens (`onSurfaceVariant` on surface for captions, `labelSmall` captions must still pass 4.5:1); insight caption text uses `onSurfaceVariant`, not `secondary`.
- Fonts: all new text uses sp via typography styles; rows must grow, not clip.

## 9. Copy summary (new keys, EN; Greek required per section 5)
`ai_insight_label` "Transaction insight (on-device)" | `ai_insight_desc` "Adds one on-device sentence to a new recommendation when you add an expense while the app is open. Never uses the cloud." | `ai_insight_needs_ai` "Turn on AI above to use this." | `ai_insight_needs_on_device` "Turn on on-device AI above to use this." | `ai_warranty_local_note` | `ai_toggle_proactive` (changed) | `ai_proactive_desc` | `ai_proactive_notifications_blocked` (conditional) | `warranty_badge_expired_unconfirmed` | `warranty_expired_date` | `warranty_action_keep_expired` | `warranty_reject_confirm_*` | `warranty_empty_filtered_*` | `warranty_empty_review_message` | `warranty_error_*` | `ai_error_settings_save_failed` | the section 5 table.
No string, log or diagnostic shown to the user may contain raw OCR text, notification text, receipt text, exception messages, paths or SQL.

## 10. Spec coverage check
Spec states with no UI (by design): insight `TransactionInsightSkipReason` values (diagnostics ring buffer only, INV-19); `BriefingDeliveryOutcome` and `NOTIFICATION_PERMISSION_DENIED` (no screen; Home simply shows the briefing); `AlreadyConfirmed` (silent); `CONFIRMED` / batch `EXPIRED` lifecycle events; settings DataStore migration (INV-23); return windows (no UI by Q1(a)); `ROUTE_REJECTED` (defect guard).
UI states with no spec cause (added here; confirm): disabled insight switch for permanent Nano limits; filtered-empty warranty states; "mutating" disabled buttons (spec only names `mutatingWarrantyIds`); optional notifications-blocked caption.
Spec gap: INV-10 does not define the Expired filter or an expired-unconfirmed count (UX-Q-1).

## 11. Open questions
- **UX-Q-1 (spec gap, INV-10):** the Expired chip should include EXPIRED_UNCONFIRMED derived rows. Also: should anything surface their count? Recommend a secondary banner line "N expired without confirmation" only when > 0, tapping applies the Expired filter. Otherwise they are only found by browsing.
- **UX-Q-2:** add a confirmation dialog before Reject (deletion is irreversible, no undo path exists)? Recommend yes. It changes current behaviour for AWAITING_REVIEW cards too.
- **UX-Q-3:** may `AiSettingsViewModel` read the notification-enabled state (via the existing permission checker, no new permission) to show the "notifications blocked" caption? Recommend yes, caption only, no settings deep link.
- **UX-Q-4:** hiding the warranty row removes its UI, but `GetAiRuntimeStatusUseCase` may still include `WARRANTY_EXTRACTION` in `highestPriorityMessage` and the "Needs attention" guidance card (UNVERIFIED). The planner should confirm the unreachable capability is excluded from the user-facing summary, otherwise an invisible row could still drive guidance copy.
- **UX-Q-5:** Greek (and Spanish) scope: add translations only for new/changed keys now, or also backfill the existing untranslated `ai_*`/`warranty_*` keys? Recommend new/changed only in this bundle; backfill as a localization follow-up.
