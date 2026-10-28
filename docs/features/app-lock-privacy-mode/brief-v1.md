# Feature brief: app-lock-privacy-mode — App lock & privacy mode

Author: `feature-strategist` (2026-10-16). Persisted by orchestrator.
Horizon: post-1.0 (1.0.x). Brief only; changes nothing in the Release 1.0 tree.
Paths: `app/...` = `app/src/main/java/com/yourname/expensetracker/...`.

## Problem
Anyone holding the user's unlocked phone can open the app and see every transaction, budget and merchant — and can read amounts and merchant names on the lock screen without unlocking at all. The threat is physical access; deep links only navigate and return nothing to the caller (portfolio-critique evidence row "No app lock / PRV-16").

## Target users and situations
- Phone handed to a child/partner, left on a desk, or shared in a household.
- Using the app in public (commute, café, office) — shoulder surfing.
- Screen-sharing, casting or app switching where the recents thumbnail shows the last screen.
- Users who get amounts on the lock screen via notifications (bill due, unusual charge, budget alerts).

## Current state (verified)
- **No lock, biometrics or FLAG_SECURE anywhere.** Grep `BiometricPrompt|FLAG_SECURE|KeyguardManager|USE_BIOMETRIC` hits only the TODO at `ui/MainActivity.kt:157-169` (PRV-16). `MainActivity` is a plain `ComponentActivity`.
- `data/security/SecureKeyStorage.kt:22` claims a "Biometric protection option" but nothing implements it; master key `setUserAuthenticationRequired(false)` (`:41-46`); stores only cloud API keys.
- **Deep links unauthenticated**: exported + `BROWSABLE` (`AndroidManifest.xml:66-87`); `home` writes caller-supplied `briefingKey` and records a diagnostic before any auth (`MainActivity.kt:190-200`); `add` opens the add dialog (`:227-228`).
- **Notifications leak amounts/merchants** — no builder calls `setVisibility`/`setPublicVersion` (5 builders):
  - `service/reminder/BillReminderWorker.kt:185-193` — "<merchant> due: <amount> <currency>"
  - `domain/alerts/AnomalyAlertOrchestrator.kt:298-307` — "Unusual charge: <amount> at <merchant>"
  - `domain/budget/BudgetMonitor.kt:462-472` — spent, %, category, limit
  - `service/warranty/WarrantyExpirationWorker.kt:107-131` — product + merchant
  - `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt:43-47` — up to 180 chars of AI summary
  - Capture foreground notification is generic (`service/NotificationCaptureService.kt:314-321`).
  - Android default visibility is private, but under the common "show all content" setting full text shows on the lock screen.
- **No hide-amounts mode** (requested in `docs/features/loan-installments/ux-flows.md:1346`, OQ-14).
- Other entry points: no widgets, launcher shortcuts or incoming share intents (only outgoing `ACTION_SEND` in `ui/screens/export/ExportOptionsScreen.kt`); `RescueActivity` separate, non-exported (`AndroidManifest.xml:61-64`); Debug gated by `BuildConfig.DEBUG` (`MainActivity.kt:885`).
- `allowBackup="false"`; `minSdk = 26`.
- Owning segment: 28 (Security & API Key Management) + UI shell and privacy settings (`ui/screens/privacysettings/PrivacySettingsViewModel.kt`).

## Goals
1. A locked app shows nothing past its first frame without device credential/biometrics.
2. No amount or merchant leaves the app while locked: recents, screenshots, lock screen.
3. Background capture and workers keep working while locked.
4. No permanent self-lockout; no remote recovery.
5. Quick public-use masking (increment 2).

## Scope

**Increment 1 — lock shell**
- Opt-in app lock via system authentication: strong biometrics with device PIN/pattern/password always available as fallback. No app-owned PIN.
- Lock triggers: every cold start; return from background after auto-lock timeout. The lock covers everything the app renders, including dialogs and the add-expense sheet.
- Deep-link gating: a link received while locked is held and applied only after unlock; no side effects before unlock (no `briefingKey` write, no diagnostic record). Closes PRV-16 and portfolio-critique M5.
- Secure screen: blocks screenshots/screen recording and blanks the recents thumbnail.
- Discreet notifications: generic visible text ("Bill due", "Unusual charge detected — open to review") with no amount, merchant, product, category or AI summary, plus an always-generic lock-screen (public) version. Applies to all 5 builders.
- Bounded reason-code diagnostics only (lock shown, unlock succeeded/cancelled, device not secure) — never content.

**Increment 2 — hide-amounts mode**
- One-tap toggle (top app bar / quick settings).
- Masks every monetary amount on screen: lists, dashboard, budgets, chart value labels, Review, plans (honours loan-installments OQ-14).
- TalkBack announces "amount hidden".
- Display only: stored values, exports, backups and AI inputs untouched.

**Later**
- Optional app PIN for devices without a screen lock (owner decision).
- Per-section re-auth (e.g. backup/export).
- "Lock now" quick action.
- Offer lock during first-run onboarding (overlaps `onboarding-and-data-control`).

## Non-goals
- No cloud/account features, remote PIN reset, or new network use.
- No DB/file encryption bound to authentication — workers must open the DB while locked.
- No change to notification-listener capture, workers or receivers (beyond notification text policy).
- No masking inside free-text AI chat responses in increment 2 (UI states the caveat).
- No hidden accounts / decoy profiles.
- No changes to Release 1.0.

## User-visible behaviours and settings
New "App lock & privacy" section in Privacy settings.

| Setting | Default | Notes |
|---|---|---|
| App lock | **Off** | Disabled with explanation + link to system security settings if the device has no secure lock screen. Enabling requires one successful authentication. |
| Auto-lock after | 1 minute | Immediately / 30 s / 1 / 5 / 15 min. Cold start always locks. Returning from system pickers, camera, share sheet or the auth prompt itself does not re-lock within the timeout. |
| Secure screen | On while lock on | Can be enabled alone; explains screenshots of reports become impossible. |
| Discreet notifications | On while lock on | Can be enabled alone. |
| Hide amounts (inc 2) | Off | Persists across restarts until turned off. |

- **Lock screen:** neutral app screen with an explicit "Unlock" button; no auto-prompt on a timer; supports font scaling and TalkBack (system prompt accessible).
- **Recovery:** biometric re-enrolment/removal falls back to device credential; a forgotten device credential is an OS-level reset — the app holds nothing to recover remotely.
- **Device becomes non-secure** (screen lock removed): see open question 2; proposed — lock goes inactive with a persistent banner (removing a screen lock already requires the credential).

## Privacy fit and data impact
- **Net positive.** No network, cloud AI, OCR, receipt or notification-content processing; no new exported data.
- Settings are device-local preferences; proposed **not** included in `.costbackup`, so restoring onto another phone doesn't import an unsatisfiable lock. No Room schema change expected (domain-modeler to confirm).
- **Permissions:** `androidx.biometric` merges `USE_BIOMETRIC` (normal, install-time, no runtime prompt). The library may also merge deprecated `USE_FINGERPRINT` (normal) — owner decides keep or strip. No runtime permission changes; `POST_NOTIFICATIONS` unchanged.
- **Fail-closed:** unreadable lock state → show lock, not content. Discreet notifications are generated content-free, not a redaction pass. Notification permission keeps gating only posting (AGENTS.md).

## Market patterns (observed)
- **Revolut:** "Hide balances" blurs balances on shake/flip, on by default; hidden accounts need biometric check. [help](https://help.revolut.com/help/profile-and-plan/security-and-personal-data/why-is-my-balance-blurry/), [blog](https://www.revolut.com/blog/post/new-revolut-security-features/)
- **Monarch:** "Demo Mode" masks balances, net worth, transactions and charts on mobile and blocks edits. [help](https://help.monarch.com/hc/en-us/articles/46153716425876-Demo-Mode)
- **Toshl Finance:** passcode lock with fingerprint unlock; passcode fallback. [blog](https://toshl.com/blog/unlock-toshl-with-your-fingerprint/)
- **Wallet by BudgetBakers:** PIN + fingerprint/Face ID, PIN-reset help article. [help centre](https://support.budgetbakers.com/hc/en-us/categories/6946618103314-Help-center), [Finder](https://www.finder.com/uk/wallet-budgetbakers-review)
- **Monefy:** passcode/biometric protection (Pro in one review). [App Store](https://apps.apple.com/app/id1212024409), [review](https://joingerald.com/learn/financial-wellness/monefy-expense-tracker-review)
- **Demand signal:** Monzo users asking for blurred amounts. [community](https://community.monzo.com/t/ability-to-blur-transactions-amounts/179585)
- Difference: most use an app PIN; this brief uses the device credential — no stored secret, no PIN-recovery path.

## Risks
- **Older Android:** credential-only auth via the modern prompt needs API 30+; API 26-29 need a compatible fallback. Test across API levels.
- **Unwanted re-locks** during system pickers, camera, SAF backup/restore or the post-restore restart.
- **Partial masking (inc 2):** one unmasked screen breaks trust — complete coverage or stated gaps.
- **Protected code:** notification changes touch guard-protected workers (bill reminder, warranty) → `reviewer-strict`.
- **Lost screenshots:** secure screen blocks users screenshotting their own reports.

## Open questions for the owner
1. Device credential only in inc 1 (no app PIN)? Users without a screen lock can't enable the lock.
2. Screen lock removed after enabling: deactivate with banner (proposed) or block access (strict fail-closed, lockout risk)?
3. Secure screen default: tied to lock (proposed), on for everyone, or independent and off?
4. Generic lock-screen (public) version of notifications for every user, even with lock off? (No change for "hide sensitive content" users; hides content for "show all" users.)
5. Hide-amounts in inc 2, or pull into inc 1? Mask merchants too? Allow edits while masked (Monarch blocks)?
6. Is a 1-minute default timeout acceptable?
7. Should `RescueActivity` (DB recovery) sit behind the lock, given locking it could block recovery?
8. Should bill-reminder Snooze/Dismiss actions work from the lock screen without unlocking?

## Domain questions (for domain-modeler)
- Is there a single amount-display chokepoint (e.g. `CurrencyFormatter`) used by every screen and chart, or does masking need a sweep?
- Confirm settings storage is not Room (no schema/migration).
- Confirm lock settings excluded from `.costbackup` and restore.
- Single content policy for the 5 notification builders without moving notification ownership out of workers/coordinators?

## Success criteria (observable, no content telemetry)
- Lock on: cold start and return-after-timeout show the lock before any financial content; recents thumbnail blank; screenshots blocked; notification-tap deep link lands only after unlock with nothing persisted or recorded before unlock.
- Discreet notifications on: no posted notification contains an amount, merchant, product, category or AI text — lock screen or elsewhere.
- Notifications captured while locked appear in Review after unlock; workers' sent/skipped counts unchanged by lock state.
- After biometric re-enrolment the device credential still unlocks; no reachable state permanently denies the owner.
- (Inc 2) Hide amounts on: walk-through of every bottom tab and plan screen shows no numeric amount; TalkBack reads "amount hidden".

## Recommendation
**Pursue.** Self-contained trust fix, no network, no new runtime permissions; closes PRV-16 and the notification leak found in portfolio-critique M5.
