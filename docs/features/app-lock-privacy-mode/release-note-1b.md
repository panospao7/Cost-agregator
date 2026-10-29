# Release note: app lock & privacy, increment 1b (owner review pending)

Status: DRAFT for the owner (APP-4 style review). Written in S23 from the S20-S22 source and the S22 plan amendment.
Increment 1a (discreet notifications, stricter links) is in release-note-1a.md and is not repeated here.

**Gating.** S20 never ships without S22: the lock engine (S20) and the settings UI (S22) are one atomic candidate
(owner-approvals item 55a), and G1b (device matrix) must pass before release. Reverting S22 alone would strand users
behind a lock they cannot turn off, so the rollback unit is {S22, S21, S20}; after release use a forward fix only.

Permission impact: `USE_BIOMETRIC` is a normal install-time permission (no runtime prompt) that came with S20 (item 9),
merged from androidx.biometric; no other new permission. `USE_FINGERPRINT` is not declared. The release evidence (`release-artifact-evidence`) must list
`USE_BIOMETRIC` and not `USE_FINGERPRINT`. No other manifest, network or data change.

## User-facing text

**App lock**
- New setting: Privacy settings, "App lock & privacy", "App lock". Turning it on explains the trade-offs first, then asks
  you to confirm with your phone's fingerprint or screen lock. Turning it off asks you to confirm the same way (there is
  no extra dialog).
- When app lock is on, the app asks you to unlock it when you open it and after you have been away longer than the
  time you choose. Your notifications show only generic text and screenshots are blocked while it is on.
- "Lock after leaving the app": Immediately, 30 seconds, 1 minute (default), 5 minutes or 15 minutes. Choosing a longer
  time asks you to confirm first; choosing a shorter time does not. The camera, file pickers and the share sheet count as
  time away.
- "Secure screen": hides the app's content in recent apps and blocks screenshots, screen recording and casting (the app
  still appears in the recent-apps list). It is on and cannot be changed while
  app lock is on. You can turn it on or off yourself while app lock is off.
- If the app cannot read your saved privacy settings, the review card now lets you choose app lock, the lock time,
  discreet notifications and the secure screen together, then save once.
- Links from notifications that arrive while the app is locked, or while you are confirming an app lock change, wait and
  open after you unlock (up to 5 minutes; they are never saved).
- Exporting your data asks you to confirm with your screen lock first when app lock is on.

**Where it is not available**
- App lock needs Android 11 or newer. On older versions the switch is off, with an "Open security settings" shortcut, and
  discreet notifications and the secure screen keep working.
- If your phone has no screen lock, the switch is unavailable with the same shortcut. If you remove your screen lock
  later, app lock pauses and shows a notice; you can still turn it off.
- On Android 11 and lower (API 30 or lower), bill reminders do not show Snooze or Dismiss while app lock is on, and also
  whenever the privacy settings cannot be read or resolved in time (fail-closed, including a policy exception); the
  buttons cannot ask you to unlock there. On Android 12 and later they ask you to unlock first, as in 1a.
- Face unlock: the app unlocks with your screen lock or a strong biometric (fingerprint, or strong/Class 3 face where the
  device has it); basic (Class 2) face unlock is not accepted.

## Notes for the owner (not for users)

- Known limits (accepted): a held link is kept in memory only, so process death drops it; the lock timer uses a monotonic
  clock; the app's own confirmation prompt never re-locks the app; a background cancel of the enable prompt shows "Couldn't turn on app lock. Try
  again." (decision B-4, kept for v1); a cancelled turn-off shows no message; the lock-on review save with an unavailable
  phone shows the generic "Couldn't save the setting" line.
- Greek and Spanish copy for the S22 strings is DRAFT and needs owner sign-off (O-2), including the corrected face-unlock
  line (`privacy_app_lock_enable_point_face`) and `applock_timeout_paused_note`.
- Wording constraints: do not promise that every unlock path is biometric (strong biometric or the screen lock only; basic
  face unlock is not used); do not say links "cannot" open the app (1a constraint stays).
- G1b still owes: API 30/31/33/34 emulator images (O-3), the real-device checks listed in the plan's G1b matrix, and the
  1a G1a post-sweep items. Without API 30 + 31, 1b stays PARTIAL.
- Release order: S20 never ships without S22; run G1b before any release.
