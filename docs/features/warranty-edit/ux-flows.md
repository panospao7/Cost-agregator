# UX flows: warranty-edit (UF-02)

Source: `Cost-agregator-int` @ e6967db1. SCR = `ui/screens/warranty/WarrantyTrackerScreen.kt`, FC = `ui/components/feature/FormComponents.kt`, S = `res/values/strings.xml`.
Designed for the domain-spec REC on every O-item (O-1 six fields, O-2 edit of in-review row = "Save and confirm", O-3 picker + months shared by add and edit, O-4 type dropdown, O-5 EXPIRED->ACTIVE on future end, O-9 Edit on ACTIVE/PENDING_REVIEW/EXPIRED). Builds on QF-2d (empty-state "Add manually" opens `showManualAddDialog`, scan wired).
No new permission, route, deep link or network use. No money field exists in the form (spec section 2): nothing to show in currency, no FX, no rounding.

## 1. Entry points and navigation
- Only entry: the Edit action on a `WarrantyCard` (section 2). No long-press, no card tap, no deep link, no new nav route; the form is a dialog over the Warranty Tracker screen (same host as add, SCR:386).
- Add entries unchanged: top-bar `+` (SCR:111), FAB (SCR:121), empty-state "Add manually" (QF-2d). All open the SAME shared form in add mode.
- Back (system, gesture, scrim tap, Cancel) = one rule: clean form closes; dirty form shows the discard confirm (section 5); while Saving it is ignored.
- Reminder notifications and the review summary card (`Review` button, SCR:216) are untouched; Review only filters the list, the user then taps Edit or Confirm on a card.

## 2. Edit entry placement (decision: labelled "Edit" button, in the card's existing action area)
Reason: an icon-only header button would squeeze the title and badge at large font scale and is less discoverable; the action area already wraps/stacks.
```
ACTIVE (SCR:719, was Row)        PENDING_REVIEW / expired-unconfirmed (SCR:678, stack)
 FlowRow gap 8dp                   [ v  Confirm | Keep in history ]  filled, full width
 [pencil Edit] [Mark Claimed] [bin] [ pencil  Edit            ]       outlined, full width (NEW, middle)
 (wraps to 2nd line at >=130%)     [ x  Reject                ]       outlined, last (destructive, has confirm)
EXPIRED, confirmed (no actions today)     CLAIMED / TRANSFERRED
 [pencil Edit]  TextButton, start-aligned   no action row, no Edit (O-9)
```
- Order on review cards: Confirm, Edit, Reject, so the destructive action stays last and furthest from the primary. Edit on a review card is the "fix, then confirm" path.
- ACTIVE row becomes `FlowRow` (not `Row`) so Edit, Mark Claimed and the 48dp Delete icon wrap instead of truncating at 200% and in el/es.
- Edit button: min height 48dp, `Icons.Default.Edit` (decorative) + `action_edit` (S:13). Disabled while `warranty.id in mutatingWarrantyIds` (same flag that disables Confirm/Reject, SCR:687). Note: Claim/Delete on ACTIVE cards ignore `isMutating` today (SCR:724-729); recommend disabling them too (tiny diff, same flag). Flag to owner only if scope-sensitive.
- Edit and Confirm are both enabled on a review card; Confirm (no changes) and "Save and confirm" (changes) end in the same state, so no extra explanation is needed on the card.

## 3. The shared form (`WarrantyFormDialog`, add and edit)
Scrollable `AlertDialog` (keeps the add dialog's look, SCR:424); body is `verticalScroll` + `imePadding`, so the keyboard and 200% font never hide fields. Title: add = `warranty_manual_add_title` (S:1649), edit = NEW `warranty_edit_title`.
```
+------------------------------------------+
| Edit warranty                            |
| [Auto-detected from receipt] Confidence: 82%   <- read-only, bodySmall, onSurfaceVariant
| Linked to a receipt. Your receipt is not changed.
| [banner: Couldn't save the change...]    <- only after a failed save, errorContainer, live region
| Product name  [______________________]   <- supportingText = field error
| Merchant      [______________________]
| Purchase date [ Oct 26, 2026       [cal]]<- read-only field, whole field tappable
| Duration (months) [ 24 ]   Warranty type [ Manufacturer v ]  (stacked, one per row)
| Support phone (optional) [____________]
| Expires: Oct 25, 2028                    <- live preview, end-1ms day
| (i) Saving confirms this warranty ...    <- outcome line, only when it applies
|              [Cancel]  [Save and confirm]|
+------------------------------------------+
```
- Fields: reuse `FormDateField` (FC:134, M3 `DatePickerDialog`, `UiTimeUtils` picker conversions already UTC-safe) and `FormDropdown` (FC:82, disables+closes on `enabled=false`, S2-017). Required extensions (implementer): date field clickable as a whole, not only the icon (48dp target); `selectableDates` limited to 1970-01-01..today+365d (O-11), so `PURCHASE_DATE_OUT_OF_RANGE` is normally unreachable and acts as a safety net; dropdown accepts `isError`/supportingText and a selected-item marker.
- Duration: numeric keyboard, digits-only filter (SCR:453), keeps label `warranty_manual_duration_months`. Default 24 in add.
- Type: 4 values, Manufacturer default in add; label NEW `warranty_field_type`, value labels NEW (4).
- Provenance line (read-only, never a field): `autoDetected` -> existing `warranty_badge_auto_detected` (S:1631) + `warranty_confidence_format` (S:1633) as two Text in a Row (no string concatenation); else NEW `warranty_edit_provenance_manual`. If `receiptId != null`, second line NEW `warranty_edit_provenance_receipt_linked`. Shown in edit mode only. Edited auto rows keep the auto badge on the card (spec section 7).
- Coverage preview: derived with the same pure `exclusiveEnd` function (INV-1), displays `end - 1 ms` day; if purchase day and months equal the stored values it shows the STORED end (INV-2). Copy reuse: `warranty_expires_date` "Expires: %1$s" (S:1619) when end is in the future, `warranty_expired_date` "Expired on %1$s" (S:1638) when past, formatted with the card's `MMM dd, yyyy` formatter (SCR:78). Hidden while duration is blank/invalid (never shown as 0 or a placeholder date).
- Outcome line (NEW, `Info` icon + text, polite live region), computed from row state and the preview end vs `referenceNowMillis`:
  - review row, end future: "Saving confirms this warranty and keeps it active."
  - review row incl. expired-unconfirmed, end past: "Saving confirms this warranty. It will be listed as expired."
  - EXPIRED row and edited end future (O-5): "This warranty will become active again."
  - ACTIVE row and edited end past: "This warranty will be marked expired."
  - otherwise nothing.
- Primary button label: add = `action_save` (S:6); edit ACTIVE/EXPIRED-confirmed = `action_save`; edit PENDING_REVIEW or expired-unconfirmed = NEW `warranty_edit_save_and_confirm`. Label derives from the live row (`reviewStateOf`), so it updates if the row leaves review mid-dialog.
- Button enabled when: validator passes AND (dirty OR row is in review). In-review rows may save pristine ("Save and confirm", INV-8), all other rows need a change (INV-10); pristine = disabled.
- Add mode: dirty = any field differs from the defaults. Defaults/initial values are TimeProvider-based (`referenceNowMillis`, SCR:413).

## 4. UI state model
Screen state (`WarrantyTrackerUiState`) is unchanged (loading/empty/filtered-empty/content, SCR:279-340). New sealed `WarrantyFormUiState` (hoisted in the VM or a saver-backed holder):
| State | Producer (spec) | UI |
|---|---|---|
| `Closed` | - | no dialog |
| `Editing(mode, draft, errors, banner?)` pristine | open | Save disabled (review row: enabled) |
| `Editing` dirty/valid | - | Save enabled, preview + outcome line live |
| `Editing` field errors | validator, `WarrantyEditError` (INV-4) | per-field message below; Save disabled |
| `Saving(draft)` | `id in mutatingWarrantyIds` (INV-15) | Save shows 16dp spinner (pattern SCR:693) + stateDescription; ALL fields, Cancel, scrim/back disabled; card actions disabled |
| `Editing + banner Failed` | exception, `reportMutationFailure` (INV-5/14) | dialog stays, input kept, Save re-enabled, banner `warranty_mutation_failed` (S:1608) |
| `Editing + banner Blocked` | `DatabaseAccessBlockedException` -> `UI_WRITE_BLOCKED_MESSAGE` | banner `ui_write_blocked_maintenance` (S:2716), input kept, retry allowed |
| event `Saved(leftReview, reactivated)` | `Edited` | dialog closes, snackbar, card re-derives, stats reload (INV-15) |
| event `NoChange` | `NoChange` (INV-10, race only) | dialog closes silently |
| event `NotFound` | `NotFound` (INV-7, delete/reject mid-dialog) | dialog closes, snackbar `warranty_error_not_found` (S:1648) |
| event `NotEditable` | `NotEditable` (INV-7, claimed mid-dialog) | dialog closes, snackbar NEW `warranty_edit_error_not_editable` |
| cancellation | CE (INV-6) | no message; `Saving` returns to `Editing`, draft intact |
Field-error map (shown after the field was touched or Save attempted; also used when the repository returns `Invalid` after UI validation passed, e.g. clock/race, with `warranty_edit_error_invalid` as banner if the field is unknown):
`PRODUCT_BLANK` -> product field; `MERCHANT_BLANK` -> merchant; `NAME_TOO_LONG` -> whichever of product/merchant is over 120; `PHONE_TOO_LONG` -> phone (>40); `PURCHASE_DATE_OUT_OF_RANGE` -> date field; `DURATION_OUT_OF_RANGE` (blank, 0, >120, overflow) -> duration. Error text always plain text (not colour only), message in `supportingText`, field `isError`.
Not applicable, stated so none is invented: offline (local only), consent/permission-denied (no PrivacyGate capability), loading of the target (opened from a loaded row), unavailable (no data dependency; the only derived value, the preview, is hidden, not zero).
Spec states with no UI: none. UI states with no spec cause (UI-only, derived from the validator/dirty flag): pristine-disabled, discard confirm, inline banner. Unrelated pre-existing gap: protected value falls back to 0 on aggregate failure (VM:112-120), out of scope.

## 5. Destructive / irreversible actions
- Edit itself is overwrite with no undo (last-writer-wins, O-8); there is no snackbar "Undo" because the old values are not retained. Mitigation: explicit Save, preview and outcome line before saving.
- Discard-changes confirm when dirty and the user cancels/back/scrim: `AlertDialog`, title `confirm_discard_title` (S:144), body `confirm_discard_message` (S:145; no Kotlin reference found in main, so check the resource is not stripped by lint), confirm NEW `warranty_edit_discard_action` "Discard", dismiss NEW `warranty_edit_keep_editing`. Pattern = the Reject confirm (SCR:363). Closing a clean form needs no confirm.
- Confirming through Save on a review row is irreversible in the same sense as Confirm today (it exits review); the outcome line says so before the tap.
- Reject/Delete/Claim unchanged.

## 6. Draft restore (rotation, process death)
- Rotation: dialog stays open; draft in `rememberSaveable` with a Saver of the six fields (strings, purchase epoch-day Long, months text, type name) plus `editingWarrantyId: Long?`, `showDiscardConfirm`; scroll state is already saveable. The M3 picker and dropdown menu are transient and may close on rotation; the chosen values are in the draft. `showManualAddDialog` (SCR:79) must become `rememberSaveable` for add mode to survive too.
- Process death: dialog restored from saved state; the target row is re-resolved by id from the list Flow. If it is gone: dialog closes with the NotFound snackbar. If the row changed meanwhile, the draft wins (O-8); dirty baseline is recomputed from the fresh row.
- Draft holds user text only in the saved-state Bundle, never logged, cleared on Saved/Discard. A `Saving` form restored after process death comes back as `Editing` (result unknown: the user can Save again; a repeat of an applied edit is NoChange or idempotent, spec section 6).

## 7. Accessibility
- Edit button content description NEW `warranty_action_edit_cd_format` "Edit warranty %1$s" (several Edit buttons share one list; pattern S:1976 `recurring_delete_planned_cd_format`); visible text remains "Edit". Recommend the same product-name context on Confirm/Reject (existing gap, owner call).
- Date field: merged node, role Button, description reuse `add_expense_date_selector_cd_format` (S:1949: "Date selector, current date %1$s, double tap to change"), onClickLabel `a11y_select_date` (S:1104); the calendar icon is decorative. M3 DatePicker is natively accessible; focus returns to the field on close.
- Type dropdown: `ExposedDropdownMenuBox` read-only anchor (role dropdown, announces label + current value); menu items expose selected state (check icon + semantics `selected`, `cd_selected` S:1292).
- Errors: `error()` semantics on the field plus supportingText; first error announced on Save attempt. Preview and outcome line are polite live regions (announce on change, debounced to the settled value). Saving: button `stateDescription` NEW `warranty_edit_saving_cd` "Saving".
- TalkBack order: title, provenance, banner, product, merchant, date, duration, type, phone, preview, outcome line, Cancel, Save. Cancel before Save as in all dialogs.
- Touch targets >= 48dp (buttons, date field, dropdown, Delete icon). Contrast: M3 scheme roles only (error/onErrorContainer on errorContainer); no colour-only meaning (icons + text).
- Font scale 200%: dialog body scrolls; AlertDialog action buttons wrap vertically; labels never `maxLines=1` except where M3 text-field labels force it. Long el/es labels ("Save and confirm", duration label, type values) must fit at 130% on 360dp without ellipsis; if a field label ellipsizes the implementer shortens the translation or moves the unit into supportingText. Card text and snackbars wrap.

## 8. Copy
Reused (exact, S = en): `action_edit` S:13, `action_save` S:6, `action_cancel` S:7, `warranty_manual_add_title` S:1649, `warranty_manual_product_name`/`merchant_name`/`purchase_date`/`duration_months`/`support_phone_optional` S:1650-1654, `warranty_badge_auto_detected` S:1631, `warranty_confidence_format` S:1633, `warranty_expires_date` S:1619, `warranty_expired_date` S:1638, `warranty_mutation_failed` S:1608, `warranty_error_not_found` S:1648, `ui_write_blocked_maintenance` S:2716, `confirm_discard_title/message` S:144-145, `add_expense_date_selector_cd_format` S:1949, `a11y_select_date` S:1104, `cd_selected` S:1292.
New keys (EN draft; el/es drafted by the implementer, owner signs off):
| Key | EN |
|---|---|
| `warranty_edit_title` | Edit warranty |
| `warranty_edit_save_and_confirm` | Save and confirm |
| `warranty_action_edit_cd_format` | Edit warranty %1$s |
| `warranty_field_type` | Warranty type |
| `warranty_type_manufacturer` / `_extended` / `_store` / `_third_party` | Manufacturer / Extended / Store / Third party |
| `warranty_edit_provenance_manual` | Added manually |
| `warranty_edit_provenance_receipt_linked` | Linked to a receipt. Your receipt is not changed. |
| `warranty_edit_outcome_confirm_active` | Saving confirms this warranty and keeps it active. |
| `warranty_edit_outcome_confirm_expired` | Saving confirms this warranty. It will be listed as expired. |
| `warranty_edit_outcome_reactivate` | This warranty will become active again. |
| `warranty_edit_outcome_will_expire` | This warranty will be marked expired. |
| `warranty_edit_error_product_blank` | Enter a product name. |
| `warranty_edit_error_merchant_blank` | Enter a merchant. |
| `warranty_edit_error_too_long` | Use %1$d characters or fewer. |
| `warranty_edit_error_date_range` | Choose a date from 1970 to one year from today. |
| `warranty_edit_error_duration_range` | Enter a duration from %1$d to %2$d months. |
| `warranty_edit_error_invalid` | Some details need fixing. |
| `warranty_edit_error_not_editable` | This warranty can no longer be edited. |
| `warranty_edit_saved` | Warranty saved |
| `warranty_edit_saved_confirmed` | Warranty saved and confirmed |
| `warranty_edit_discard_action` | Discard |
| `warranty_edit_keep_editing` | Keep editing |
| `warranty_edit_saving_cd` | Saving |
Total 26 keys x en/el/es (the 1970/limit numbers in the two range strings come from the validator constants as format args if the owner changes O-11; the date string avoids a hard-coded year if so). Errors, banners and cd strings are static resources: never include product/merchant/phone text, dates from the user, exception messages or OCR text. Only the TalkBack Edit cd and the card itself carry the product name (UI only, never logged).

## 9. Open UX questions (recommendations)
1. Form container: scrollable AlertDialog (REC, minimal diff) vs full-screen dialog (better at 200% on small phones; larger diff).
2. Failure/blocked feedback: spec says snackbar; a snackbar sits under the dialog scrim. REC: in-dialog banner while the form is open, snackbar for card-level actions and for results after close.
3. Success feedback: silent close vs snackbar. REC: snackbar `warranty_edit_saved`/`_saved_confirmed` (a confirmed row can vanish from a "needs review" filter).
4. Legacy manual rows (receiptId -> MANUAL_PLACEHOLDER) would show "Linked to a receipt". REC: accept for v1 (rare, pre-WRN-4), or hide the line when `autoDetected=false`; owner picks.
5. Preview reuses "Expires/Expired on" instead of a new "Coverage ends" string. REC: reuse.
6. Disable Claim/Delete on `isMutating` and add product name to Confirm/Reject cd: REC yes for Claim/Delete (one flag, same card), Confirm/Reject cd as a follow-up.
7. Add flow gains discard confirm, picker, type, saveable draft (O-3 scope growth). REC: accept; shared form means one behaviour.

## Appendix A: alternatives the owner might pick
- O-2 B (independent "confirmed" toggle on review rows): form gets a Switch "Mark as confirmed" (default on for review rows) and Save label is always `action_save`. Needs a new visible state "edited, still awaiting review" (badge text + border variant on the card, a new string) and an overwrite-protection rule in the repository; outcome lines 1-2 become conditional on the switch; the 'Review' counter semantics change. Not designed further; +4 strings.
- O-3 alt (text date for add, picker only for edit): the form takes `dateInputMode`; add keeps the `yyyy-MM-dd` field (SCR:443), which needs a NEW format-error string (e.g. `warranty_edit_error_date_format`) because the picker no longer prevents bad input; the preview and PURCHASE_DATE_OUT_OF_RANGE then become reachable in add. Two date UIs in one dialog, higher test surface; not recommended.
