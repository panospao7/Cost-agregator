# Release note: app lock & privacy, increment 2 (app PIN) (owner review pending)

Status: DRAFT for the owner. Written in S28 from the S24-S27 source. Increments 1a and 1b are in release-note-1a.md and
release-note-1b.md and are not repeated here. G2 (device checks) must pass before release.

## User-facing text

**App PIN**
- New setting: Privacy settings, "App lock & privacy", "App PIN" (shown only while app lock is on). Setting a PIN first
  shows a warning and one "I understand..." checkbox, asks you to confirm with your screen lock, then asks you to enter the
  PIN twice on the app's own keypad.
- With a PIN set, the app asks for the PIN when it unlocks instead of the system prompt. Wrong PINs add a growing wait.
- You can change the PIN (enter the current one first) or remove it (enter the current one, then confirm). Removing the PIN
  returns the app to your screen lock. If the phone has no screen lock, removing the PIN turns app lock off, and the dialog
  says so.
- "Back up now" opens Backup & Restore. It does not make a backup by itself.

**If you forget the PIN**
- There is no in-app reset and no screen-lock fallback. A forgotten PIN means clearing the app's data, or restoring an
  exported backup (.costbackup) after clearing it. Back up before you set a PIN. Clearing app data removes everything
  that is not in a backup.
- The PIN is stored only on this phone and is never part of a backup. Restoring a backup does not restore the PIN.

**Moving to a new phone**
- Device-to-device transfer can copy the app lock setting but never the PIN or its keys. On the new phone the app uses your
  screen lock (or pauses app lock if there is none) until you set a PIN again. You are not locked out.

## Notes for the owner (not for users)

- Fingerprint unlock in PIN mode is NOT in this increment: its enable path is deferred to follow-up slice S26b, so the
  fingerprint button stays hidden. Do not advertise it.
- Owner decisions applied: UQ-1 in-app keypad (CharArray, no text field), UQ-4 single acknowledgement (no export-first),
  OD-19 Option A (no manifest change; app-wide backup rules remain an owner release-track item).
- No schema change and no new permission.
- Greek and Spanish copy for the PIN strings is DRAFT and needs owner sign-off.
- G2 still owes: PBKDF2 timing, Keystore security level, reboot delay (AE-73), Keystore loss (AE-80), PIN step plus
  background (AE-119) and the PIN UI smoke on a device.
