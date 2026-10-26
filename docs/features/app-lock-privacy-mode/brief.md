# Feature brief: app-lock-privacy-mode — App lock & privacy mode (v2)

Author: `feature-strategist` (single reshape pass, 2026-10-16). Persisted by orchestrator; v1 kept as `brief-v1.md`.
Inputs: v1 brief, `critique.md` (RESHAPE), owner answers in `workflows/active/app-lock-privacy-mode-handoff.md`.
Horizon: post-1.0. Paths: `app/...` = `app/src/main/java/com/yourname/expensetracker/...`.

Strategist notes to orchestrator: API matrix checked against androidx.biometric source (combo unsupported on 28-29; DEVICE_CREDENTIAL alone unsupported <30; platform guide says both unsupported ≤29; library accepts combo on 26-27 only via legacy dialog + separate credential activity — moot given owner's "API < 30 unavailable"). BiometricPrompt constructors accept only FragmentActivity/Fragment (source-checked). Deep-link internal callers = only the 3 implicit ACTION_VIEW PendingIntents in `AndroidNotificationService.kt:112,147,172` (no widgets/shortcuts); external callers (docs, integrations) still to check. Interpretation: delegated default "discreet notifications auto-on while lock on" turned into "forced while lock on" — listed as owner question 2.

## Problem
If someone picks up the user's phone while it is unlocked, they can open the app and see every transaction, budget and merchant. Today notifications can also show amounts and merchants on the lock screen, Wear OS and Android Auto. Separately, any web page can fire the app's deep links: that can suppress the day's briefing, and the raw caller text is written into diagnostics.

## Target users and threat model (narrowed)
- **In scope (Inc 1a/1b):** strangers, children, shoulder-surfers, recents thumbnails, screen sharing/casting, lock-screen and mirrored-device glances.
- **Not covered by Inc 1b:** a partner/family member who knows the device PIN. A device-credential lock cannot stop them. Only an app PIN (Inc 2) addresses this, partly.

## Current state (corrected)
- No lock, biometric code or FLAG_SECURE anywhere; only the PRV-16 TODO at `ui/MainActivity.kt:166`. `MainActivity` is a plain `ComponentActivity` (`:92`).
- **Notifications:** 4 `NotificationCompat.Builder` calls in 2 owners — `data/service/AndroidNotificationService.kt:74,122,157`, `service/reminder/BillReminderWorker.kt:253` — serving 5 composition sites: bill (`BillReminderWorker.kt:185-193`), anomaly (`domain/alerts/AnomalyAlertOrchestrator.kt:298-307`), budget (`domain/budget/BudgetMonitor.kt:462-472`), warranty (`service/warranty/WarrantyExpirationWorker.kt:107-131`, posts via `sendBudgetAlert` `:218`), briefing (`domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt:43-47`). None sets visibility or a public version. Bill Snooze/Dismiss actions mutate lifecycle state (`BillReminderWorker.kt:259-260`).
- **Deep links:** exported + `BROWSABLE`, 8 hosts (`AndroidManifest.xml:66-87`).
  - `home`/`dashboard` writes caller-supplied `briefingKey` → suppresses that day's briefing (`DeliverProactiveBriefingNotificationUseCase.kt:34-36`); records raw caller string in diagnostics (`MainActivity.kt:192-200`).
  - `activity` reads the DB (`:203-206`); `add` opens the add dialog (`:227-228`).
  - Notification PendingIntents are implicit `ACTION_VIEW` URIs (`AndroidNotificationService.kt:112,147,172`) — the only in-code callers.
- **Existing operational/restore lock:** early return in the shell (`MainActivity.kt:425-449`); post-restore restart is `Runtime.exit(0)` (`:433`) = cold start.
- `RescueActivity`: non-exported, never launched, compile-time disabled (`data/rescue/RescueConfig.kt:12`) — out of scope.
- `allowBackup=false`; minSdk 26 (stays 26).

## Goals
1. No amount, merchant, product, category or AI text in notification surfaces once the user opts in, and never while the lock is on (1a).
2. Deep links cannot cause side effects or diagnostic content from outside callers (1a).
3. While locked, the app composes no financial UI; recents, screenshots and casting show nothing (1b).
4. Capture and workers keep running while locked.
5. No state can lock the owner out permanently.

## Increment 1a — notification content policy + deep-link hardening (lock-independent)
- **One policy at the `NotificationService` boundary (2 builder owners).** Callers pass notification kind + content; the service chooses the text. Workers/coordinators keep owning when and whether to post.
- **Discreet mode:** generic visible text ("Bill due", "Unusual charge — open to review") — the only protection for "show all content" users, Wear and Auto. Setting available to everyone; on automatically while the lock is on.
- **Generic public version on every notification, always** (matters only under "hide sensitive content").
- **Failure handling:** unreadable setting → discreet text; must never block or delay posting (AGENTS.md notification rules).
- **Notification actions:** API 31+ Snooze/Dismiss require authentication; API 30 with lock on → no actions posted; lock off → unchanged.
- **Deep links:** remove `BROWSABLE` from internal-only hosts after confirming no external caller; notification PendingIntents become explicit-component; parameters (`briefingKey`, `expenseId`, `period`, `location`) validated against an allowlist; diagnostics record bounded reason codes only.
- **Dependency:** the present-day diagnostic/briefing-suppression part is release advisory **C4** (`docs/features/_discovery/opportunity-scan/release-suggestions.md`). Inc 1a re-checks C4's status at impact-map time and covers what remains; it assumes neither fixed nor open.

## Increment 1b — app lock + forced secure screen

**API matrix (verified, androidx.biometric):**

| API | `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` | Decision |
|---|---|---|
| 26–27 | Library accepts via legacy custom fingerprint dialog + separate confirm-credential activity | **Unavailable** — setting disabled with explanation |
| 28–29 | Unsupported: `build()` throws (library source); platform guide says unsupported on all ≤29 | **Unavailable** |
| 30+ | Supported, system prompt | **Available** |

`BIOMETRIC_WEAK` (Class 2 face) never used (owner decision). Library constructors accept only `FragmentActivity`/`Fragment`; `MainActivity` is a `ComponentActivity`. **Risk, not design:** host-type change touches lifecycle code in the most contended file; impact-map decides handling.

**Behaviour:**
- Opt-in; enabling requires one successful authentication.
- **Locks on:** every cold start (incl. post-restore restart) and every return after the auto-lock timeout. Timer starts on `onStop`, not focus loss (multi-window). The app's own auth prompt and system pickers within the timeout never re-lock.
- **Locking prevents composition, not an overlay:** while locked no `MainScreen`, dialog, sheet or popup composes, and no clipboard amount parsing runs.
- **Precedence:** app lock outermost; operational/restore lock screen renders only after unlock and never bypasses it; distinct names.
- **Gating:** both `onCreate` and `onNewIntent` (`CLEAR_TASK` and `CLEAR_TOP` paths); lock state at process level. A link arriving while locked is held in memory and applied after unlock; **process death while locked drops the held link** (user lands on default screen).
- **Secure screen forced on while lock is on** (FLAG_SECURE; also blanks casting/screen sharing). Impact-map checks Compose dialog/sheet `securePolicy` inherits it.
- **Permission-prompt order:** permission prompts (R24 `POST_NOTIFICATIONS`, R17 listener access) only after unlock.
- **No unescapable state:** lock on but device can no longer authenticate (screen lock removed, nothing enrolled) → lock goes inactive, persistent banner, bounded reason code. Lock state unreadable → treated as locked if the device can authenticate; otherwise inert + banner.
- **Lock screen:** neutral, explicit "Unlock" button, no timed auto-prompt; TalkBack and font scaling.
- **Diagnostics:** bounded reason codes only (lock shown, unlocked, cancelled, unavailable).
- **Stated gaps:** user-granted accessibility services can still read the screen (API 34 sensitive-data flag deferred); forgotten device credential = OS-level reset.

## Increment 2 — optional app PIN (recommended, conditional)
**Sequence: 1a → 1b → 2.**
- 1b covers strangers/children/shoulder-surfers with no stored secret and no recovery problem.
- An app PIN is the only answer to the household threat but costs: **secret storage** (salted slow hash, never the PIN); **brute-force limits** (escalating delays); **recovery without remote reset** — resetting via device credential would defeat the purpose (partner knows it), so a forgotten app PIN = clear app data, or restore from `.costbackup` if one exists.
- **Biometrics in app-PIN mode:** off by default — any enrolled fingerprint unlocks, including a partner's.
- Reuses the 1b shell (lock screen, timeout, gating).
- **Below API 30:** an app PIN could technically enable the lock on 26–29; recommendation: keep Inc 2 at API 30+ (owner accepted older versions unsupported; avoids reintroducing the old-API test matrix).

## Moved out — hide-amounts
Separate future brief (also answers loan-installments OQ-14). No single amount-display chokepoint: 196 `CurrencyFormatter.` calls across 47 UI files + 22 raw `"%.2f"` in 12 files; `CurrencyFormatter` is a static object without Compose context. The future brief must first design one masking chokepoint, plan around R19/R20 UI edits, and decide merchant masking, editing while masked, and the AI-chat caveat.

## Non-goals
Cloud/accounts/remote reset/new network use; DB encryption bound to auth (workers must run while locked); changes to capture or worker posting decisions; face/Class 2 unlock; raising minSdk; `RescueActivity`; hidden/decoy profiles; changes to Release 1.0.

## Settings (Privacy settings → "App lock & privacy")

| Setting | Default | Notes |
|---|---|---|
| Discreet notifications (1a) | Off | Available to everyone; forced on while lock is on |
| Generic public version (1a) | Always | No setting |
| App lock (1b) | Off | API 30+ with secure device; else disabled with explanation + link to system security settings |
| Auto-lock after (1b) | 1 minute | Immediately / 30 s / 1 / 5 / 15 min; cold start always locks |
| Secure screen (1b) | Off | Forced on while lock is on |
| App PIN (2) | Off | Recovery = clear data or restore |

Settings are device-local preferences, not Room, excluded from `.costbackup` (domain-modeler to confirm).

## Privacy fit and permissions
- **Net positive:** no network, cloud AI, OCR/receipt/notification-content processing, or new exported data; attack surface shrinks (BROWSABLE removed).
- **Permissions:** `androidx.biometric` merges `USE_BIOMETRIC` (normal, install-time). Lock is API 30+ only, so deprecated `USE_FINGERPRINT` can be stripped (verify at impact-map). No runtime permission changes.
- **Fail-closed:** discreet text generated content-free, not redacted after the fact; unreadable lock state shows the lock, subject to the inert-lock escape.

## Market patterns (observed)
- Monarch Demo Mode masks balances ([help](https://help.monarch.com/hc/en-us/articles/46153716425876-Demo-Mode)); Revolut balance blur ([help](https://help.revolut.com/help/profile-and-plan/security-and-personal-data/why-is-my-balance-blurry/)).
- Toshl, Wallet by BudgetBakers: app passcode + fingerprint + PIN-reset help — supports Inc 2 as a common pattern ([Toshl](https://toshl.com/blog/unlock-toshl-with-your-fingerprint/), [BudgetBakers](https://support.budgetbakers.com/hc/en-us/categories/6946618103314-Help-center)).
- Platform: device-credential combinations restricted on older Android ([Android guide](https://developer.android.com/identity/sign-in/biometric-auth); androidx `BiometricPrompt` source).

## Risks
- `MainActivity` host-type change (FragmentActivity requirement).
- Unwanted re-locks from system UI on API 30+; device tests on API 30, 31, 33, 34+.
- Notification changes touch guard-protected workers and bill lifecycle → `reviewer-strict`.
- Users lose screenshots of their own reports while locked.
- App PIN: data loss when PIN forgotten and no backup.

## Release-batch collisions (sequence lock work after R17/R21/R24 land)
- **1a:** R21 V-C3 reminder burst (`BillReminderWorker`); HZ-05 F1 briefing scheduling; advisory C4 (deep-link diagnostics).
- **1b:** R24 `POST_NOTIFICATIONS` re-prompt (`MainActivity.kt:370`); R17 listener-access prompt/status; HZ-03 F1 restore lock on destroyed Activity; R19 privacy tile (settings screen).

## Open questions for the owner
1. Household (PIN-knowing partner) threat in scope? If so, is Inc 2 acceptable with "forgotten app PIN = clear data or restore" as the only recovery?
2. While lock is on: discreet notifications forced (proposed) or on-by-default and user-disableable?
3. Deep links: remove only `BROWSABLE` (decided), or remove the `VIEW` intent-filter entirely once PendingIntents are explicit?
4. Can Inc 1a ship in 1.0.x ahead of 1b, regardless of what happens to C4?

## Success criteria (observable, no content telemetry)
- **1a:** discreet mode or lock on → no posted notification contains amount, merchant, product, category or AI text on any surface; every notification has a generic public version; posting counts unchanged when the setting is unreadable; browser-fired `expensetracker://` link does nothing; invalid parameters rejected with a reason code; no raw value in diagnostics.
- **1b:** API 30+: cold start and return-after-timeout show the lock before any composition; recents blank; screenshots/casting blocked; held links apply after unlock. API 26–29: setting shown unavailable. Notifications captured while locked appear in Review after unlock. No test path reaches an un-passable lock screen.
- **2:** PIN never stored in plaintext; escalating delays on failures; recovery works as stated.

## Changes from v1
- Split into 1a / 1b / 2; hide-amounts and `RescueActivity` (minor 16) out.
- Critique 1: API matrix; FragmentActivity flagged as risk. 2: "hold the link" → deep-link hardening + C4 dependency. 3: secure screen forced with lock. 4: composition-preventing lock + precedence. 5: single policy at `NotificationService` boundary. 6: public-version semantics corrected. 7: threat model narrowed; app PIN = Inc 2. 8: collision list. 9: actions per API. 10: hide-amounts moved out.
- Minor 11: moot below API 30; device tests 30+. 12: gating + held-link loss stated. 13: restore restart = cold start. 14: inert-lock escape. 15: onStop timer, casting, securePolicy, clipboard covered; accessibility deferred as stated gap.
- Current state corrected: 4 builders in 2 owners, 5 composition sites.
- Recommendation: pursue 1a then 1b; Inc 2 conditional on Q1.

Sources: [Android Developers — biometric auth](https://developer.android.com/identity/sign-in/biometric-auth); [androidx BiometricPrompt.java](https://raw.githubusercontent.com/androidx/androidx/androidx-main/biometric/biometric/src/main/java/androidx/biometric/BiometricPrompt.java); [AndroidX Biometric Overview (Seven Peaks)](https://sevenpeakssoftware.com/blog/androidx-biometric-overview/); [CommonsWare security chapter](https://commonsware.com/R/pages/chap-security-002); market sources as listed above.
