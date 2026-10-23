# Critique: app-lock-privacy-mode (2026-10-16)

Author: `feature-critic` (independent of strategist). Persisted by orchestrator.
Paths under `app/src/main/java/com/yourname/expensetracker/` unless noted.

**Verdict: RESHAPE.** Problem real, brief mostly accurate, but increment 1 bundles three things: (a) notification content policy, (b) deep-link hardening, (c) the actual lock + secure screen. (a) and (b) are lock-independent, small, and fix leaks that exist today. (c) has an API-level design defect and collides with MainActivity work queued for 1.0.1. Hide-amounts is a separate feature.

## Evidence spot-check

| Claim | Status | Evidence |
|---|---|---|
| No BiometricPrompt/FLAG_SECURE/KeyguardManager; only PRV-16 TODO | Holds | only `ui/MainActivity.kt:166` |
| MainActivity plain ComponentActivity | Holds | `ui/MainActivity.kt:92` |
| SecureKeyStorage advertises biometrics; `setUserAuthenticationRequired(false)` | Holds | `data/security/SecureKeyStorage.kt:22,44` |
| Deep links exported + BROWSABLE | Holds | `app/src/main/AndroidManifest.xml:66-87` |
| `home` writes briefingKey + diagnostic pre-auth | Holds, understated | `MainActivity.kt:192-200`; diagnostic embeds raw caller string (`:199`) |
| `add` opens add dialog | Holds | `MainActivity.kt:227-228` |
| (missing) `activity` host reads DB pre-auth | Omitted | `MainActivity.kt:203-206` |
| "5 builders" | Overstated | 5 composition sites, but 4 `NotificationCompat.Builder` calls in 2 owners: `data/service/AndroidNotificationService.kt:74,122,157`, `service/reminder/BillReminderWorker.kt:253`. Warranty posts via `sendBudgetAlert` on budget channel (`service/warranty/WarrantyExpirationWorker.kt:218`) |
| Message strings bill/anomaly/budget/warranty/briefing | Hold | `BillReminderWorker.kt:185-193`, `domain/alerts/AnomalyAlertOrchestrator.kt:298-307`, `domain/budget/BudgetMonitor.kt:462-472`, `WarrantyExpirationWorker.kt:107-131`, `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt:43-47` |
| Capture FGS notification generic | Holds | `service/NotificationCaptureService.kt:314-321` |
| RescueActivity separate, non-exported | Holds; OQ7 risk overstated | never launched (manifest only), compile-time disabled (`data/rescue/RescueConfig.kt:12`); manifest comment "reachable via notification/deep-link" stale |
| Debug gated by `BuildConfig.DEBUG` | Holds | `MainActivity.kt:885` |
| `allowBackup=false`, minSdk 26 | Holds | manifest `:32`, `app/build.gradle.kts:139` |
| "Credential-only needs API 30+; 26-29 need fallback" | Imprecise (library docs) | `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` unsupported on API 28-29; `DEVICE_CREDENTIAL` alone unsupported <30 |
| OQ4 public version "hides content for show-all users" | Wrong (platform semantics) | `setPublicVersion` applies only under "hide sensitive content"; "show all" users always see private text |
| Lock settings not in `.costbackup` | Plausible, unverified | `data/backup/` refs prefs only in `RestoreMaintenanceMode.kt`, `DataStoreMaintenanceSafeDiagnosticSink.kt` |

## Issues

### Blocking
1. **Authenticator combo impossible on API 28-29; wrong base activity.** On 28-29 options are `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` (accepts Class 2 face — downgrade) or deprecated `KeyguardManager.createConfirmDeviceCredentialIntent` (separate activity, see 11). `androidx.biometric.BiometricPrompt` needs `FragmentActivity`; MainActivity is `ComponentActivity` (`:92`) — base-class change is lifecycle-risky in the most contended file. Brief must name the per-API authenticator matrix.
2. **Holding a deep link until unlock doesn't fix the deep-link flaw.** Any web page can fire `expensetracker://dashboard?briefingKey=dashboard_home:<date>`; after unlock the write still happens and suppresses that day's briefing (`DeliverProactiveBriefingNotificationUseCase.kt:34-36`). The raw caller string lands in diagnostics today (`MainActivity.kt:197-200`) — violates bounded-reason-code rule lock on or off. Real fix is lock-independent: validate/allowlist params, drop raw value from diagnostic, explicit-component PendingIntents instead of implicit `ACTION_VIEW` URIs (`AndroidNotificationService.kt:112,147,172`), reconsider BROWSABLE for internal-only hosts.
3. **Goal 2 fails with secure screen off while lock on** (settings table allows it): content visible during auto-lock timeout; recents thumbnail captured at backgrounding. Force secure screen whenever lock on (`FLAG_SECURE` or API 33+ `setRecentsScreenshotEnabled(false)`).

### Major
4. **Lock must prevent composition, not overlay.** Dialogs/sheets/popups are separate windows. MainScreen must not compose while locked; existing operational lock uses early return (`MainActivity.kt:425-449`). Define precedence app lock vs `AppOperationalLockScreen`; distinct naming.
5. **Discreet text contradicts "no change to workers".** Five composition sites incl. 2 guard-protected workers. Better: one content policy at the `NotificationService` boundary (2 builder owners); callers pass kind + content; service chooses discreet text; unreadable setting → discreet text, must not block posting (AGENTS.md notification rules).
6. **OQ4 based on wrong semantics.** Only generic visible text protects "show all" users, Wear OS mirroring, Android Auto.
7. **Household threat model weak.** Partners/family often know the device PIN — narrow stated value (strangers, children, shoulder-surfers) or revisit app PIN.
8. **Release-batch collisions:** R24 POST_NOTIFICATIONS re-prompt (`MainActivity.kt:370`), R17 listener access prompt/status, R21 V-C3 reminder burst in BillReminderWorker + HZ-05 F1 briefing scheduling, R19 privacy tile, HZ-03 F1 restore lock on destroyed Activity. Sequence lock work after R17/R24/R21; decide auth-prompt vs permission-prompt order.
9. **Snooze/Dismiss mutate lifecycle from lock screen** (`BillReminderWorker.kt:259-260`); `setAuthenticationRequired` only API 31+. OQ8 needs per-API answer.
10. **Hide-amounts has no chokepoint**: 196 `CurrencyFormatter.` calls across 47 UI files + 22 raw `"%.2f"` in 12 files; `CurrencyFormatter` is a static object without Compose context. Sweep overlaps R19/R20 UI edits. Doesn't belong here.

### Minor
11. Re-lock loop on API 26-29: confirm-credential activity triggers `onStop`; with "Immediately" re-locks. Needs exemption + device tests 26/28/29/30+.
12. Cleared-task / new-intent: budget/warranty taps `CLEAR_TASK` (`AndroidNotificationService.kt:173`), anomaly/briefing `CLEAR_TOP` via `onNewIntent`. Lock state at process level; gate both `onCreate` and `onNewIntent` (`MainActivity.kt:117-124,146-155`). One-shot consume clears `intent.data` so a held link is memory-only — process death while locked drops it; state in brief.
13. Post-restore restart is `Runtime.exit(0)` (`MainActivity.kt:433`) = cold start, always locks; listing it as "unwanted re-lock" contradicts "cold start always locks".
14. Unauthenticatable lock: lock on but no secure credential (or unreadable state on such device) = unescapable lock screen. Specify escape; OQ2 cannot resolve to "block".
15. Gaps: accessibility services read screen despite FLAG_SECURE (API 34 `accessibilityDataSensitive`); multi-window — start timer on `onStop`, not focus loss; casting/screen-sharing blank under FLAG_SECURE; verify Compose `DialogProperties`/`ModalBottomSheetProperties` `securePolicy` (Inherit default); clipboard reads while locked (`ClipboardAmountParser` in MainActivity shell).
16. OQ7 closable — RescueActivity out of scope (non-exported, never launched, compile-time disabled).

## Recommended scope changes
- **Inc 1a (small, lock-independent, 1.0.x):** notification content policy at the NotificationService boundary (discreet text, generic public version, `setAuthenticationRequired` on actions API 31+) + deep-link hardening (issue 2). Discreet notifications offered to everyone.
- **Inc 1b:** app lock + forced secure screen — per-API authenticator matrix, FragmentActivity migration, composition-preventing lock, precedence with operational lock; only after R17/R24/R21 merge.
- **Hide-amounts:** own brief with masking-chokepoint design first; answers loan-installments OQ-14.
- **Defaults:** lock off + 1-minute timeout fine; secure screen **forced** on while lock on; optional when lock off.

## Owner questions
1. API 28-29: accept `BIOMETRIC_WEAK` (face) or deprecated ConfirmDeviceCredential path?
2. Household (PIN-knowing partner) threat in scope? If yes, app PIN enters scope.
3. Discreet notifications for all users, independent of lock?
4. Remove BROWSABLE for internal hosts, or keep with parameter allowlisting?
5. API < 31: Snooze/Dismiss available from lock screen without unlocking?
6. Can lock work start before R24/R17 land in MainActivity, or wait?
7. Confirm RescueActivity and operational-lock screens out of scope (or define precedence).
