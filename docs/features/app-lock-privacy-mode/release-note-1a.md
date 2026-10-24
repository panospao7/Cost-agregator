# Release note: app lock & privacy, increment 1a (owner review pending)

Status: DRAFT for APP-4. Owner review pending. Adapted from the uncommitted
orchestrator draft. The ai-fixes B3a paragraph (briefing and warranty
reminders when notifications are off) is intentionally not here; it belongs to the ai-fixes release note.

Corrections made while adapting, checked against source on 2026-10-24:
- The lock-screen text is the generic pair "Reminder" / "Open the app to see details." (`notif_public_title`,
  `notif_public_text`), not "Expense Tracker".
- The setting is the "Discreet notifications" switch in the "App lock & privacy" section of the Privacy settings
  screen (`privacy_section_app_lock`, `privacy_toggle_discreet_notifications`).

## User-facing text

**Private notifications**
- If your phone hides sensitive notification content on the lock screen, the lock screen now shows only a generic
  "Reminder" notice; details appear after you unlock. For no details anywhere, turn on Discreet notifications.
- New setting: Privacy settings, "App lock & privacy", "Discreet notifications". When it is on, budget, bill,
  warranty, receipt, unusual-charge and briefing notifications show only a generic title and text (for example
  "Bill due" or "Budget alert"), even after you unlock. Turning it on also updates the notifications already in
  your notification shade, quietly and without a new sound.
- If the app cannot read this setting, it behaves as if discreet notifications were on, and the Privacy settings
  screen says so. If the saved setting is damaged, the screen asks you to review and save your choice.

**Bill reminders (Android 12 and later)**
- The Snooze and Dismiss buttons on bill reminders now ask you to unlock your phone first. This applies to
  everyone, whether or not discreet notifications are on. On Android 11 and earlier the buttons behave as before.
- The button labels now appear in your language (Greek and Spanish). They were English only before.

**Links**
- Links in web browsers no longer open the app. Tapping a notification works as before. Links are also checked
  more strictly: unknown or malformed links open the home screen, and a link never creates or fills in an expense.

## Notes for the owner (not for users)

- Greek and Spanish copy for the new strings is best effort and needs an owner spot-check
  (`strings_notification_presentation.xml`, `strings_privacy_section.xml`, plus the added `action_dismiss` and
  `notification_briefing_ready_title` entries).
- Wording constraint (APP-4): say "links from web browsers no longer open the app", not "links can't open the
  app". Other installed apps can still send a VIEW intent; validation is the boundary, not the manifest.
- The bill reminder title "Bill due" and its body remain English only in normal (non-discreet) mode. Pre-existing;
  tracked as a follow-up, not part of this release.
- Known accepted residual (spec 4.6): a notification the user dismisses in the instant before the "turn on"
  re-post can come back as a new notification and may make a sound on a high-importance channel. The content
  is discreet.
- Device checks owed at G1a, none yet run: AE-14, AE-16, API 31+ Snooze/Dismiss on a locked device, AE-12 (API 26),
  AE-15/AE-102, AE-112/AE-121 upgrade check, FG-22 malformed-link run, AE-32.
