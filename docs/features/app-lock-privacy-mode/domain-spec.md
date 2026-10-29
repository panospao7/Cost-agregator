# Domain spec: app-lock-privacy-mode — App lock & privacy mode

Author: `domain-modeler`, 2026-10-16. Status: Gate 2 DECIDED 2026-10-16 (`gate2-decisions.md`: all recommendations
accepted). Amended 2026-10-16 (stage 3b) to close the UX reconciliation gaps (`ux-flows.md` 13.2 G-1…G-13, flags R-1
and R-4). Amended 2026-10-17 (post-Gate-2, AMD-17…AMD-21) to record the Gate 2 outcomes, add the sixth notification
kind `RECEIPT_MATCH` (NEW-1a) and resolve the spec-side conflicts in `impact-map.md` §9. Amended 2026-10-17 (AMD-22)
for the feature-planner request on `AUTHENTICATING` per purpose and the legacy-notification question on TECH-3. Every
change is listed under "Amendments" below.
Inputs: `brief.md` (v2, Gate 1 GO, binding scope), `critique.md`, `workflows/active/app-lock-privacy-mode-handoff.md`
("Human decisions"), `AGENTS.md` (Privacy, Worker, Notification permission, Room), `docs/architecture/LEGAL_PATHS.md`
(Backup/Restore, Workers, Diagnostics, Anomaly Alerting), `docs/architecture/CODEBASE_SEGMENTS.md` (Segments 11, 12, 18,
28), `FINAL_CI_GUARD_ACCEPTANCE_GATE.md`, and the source files cited below.

## Amendments (AMD-n), stage 3b, post-Gate-2 and plan stage

Stage 3b (AMD-1…AMD-16) sources: `ux-flows.md` 13.2 (spec gaps G-1…G-13 and the UX designer's assumptions) and
12.3 (flags R-1, R-4), checked against `brief.md` v2. Existing ids keep their numbers. New ids are appended: INV-44…INV-52, T26…T31, AE-85…AE-110,
OD-21, S8…S11. Changed text carries the tag `(AMD-n)`.

| AMD | Closes | Disposition | Change | Where |
|---|---|---|---|---|
| AMD-1 | G-1 | UX assumption accepted, made precise | Defines the fail-closed effective record F used while settings are unreadable (lock on, `IMMEDIATE`, discreet on, secure on). While unreadable, per-field writes are rejected. For `CORRUPTED`/`INVALID_VALUE` the only write is a full-record review-save that replaces the store and clears the sentinel. A review-save is weakening when its effective values are weaker than F. A weakening save needs one fresh auth if the app can authenticate, and none when `INERT`. Lock-on is accepted only if the lock could be active. New code `LOCK_SETTINGS_REVIEW_SAVED`. **UX delta:** saving lock-on in the review card is not a separate enable flow, because the lock is already effectively on. In `INERT` the lock choice is off-only (ux-flows 2.1 note "enabling the lock inside this state uses the normal enable flow"). | 2.3, 3.1, T27, 3.3, 3.5, INV-44, INV-45, AE-85…AE-90, 10 |
| AMD-2 | G-2 | Accepted | Every PIN verification surface shares one attempt counter and delay: unlock, change, remove, disable, mode switch, enabling fingerprint, any confirm-change, and the review-save. A correct PIN on any surface resets the counter. | 6.2, INV-39, AE-91, AE-92 |
| AMD-3 | G-3 | Accepted | An enable request with API < 30 or capability ≠ `AVAILABLE` is refused. Capability is evaluated at request time. There is no prompt and no state change, and one refusal code is recorded per cause per process. New code `LOCK_UNAVAILABLE_CAPABILITY(HW_UNAVAILABLE \| UNKNOWN)`. Retrying re-evaluates. | T26, INV-23, AE-93, 10 |
| AMD-4 | G-4 | Accepted as a rule, not an OD | Removing the PIN, switching to `DEVICE_AUTH` and disabling the lock in PIN mode are one ordered operation: delete the verifier set first, then write the settings. If capability ≠ `AVAILABLE`, removal turns the lock off. Reason: INV-23 forbids enabling a device-auth lock that cannot authenticate, and leaving it `INERT` would do exactly that. Effective mode is derived from whether a verifier exists, so an interrupted operation falls back to device-auth rules and never shows a PIN pad without a verifier. **UX delta:** in PIN mode the turn-off flow (ux-flows 2.2c, 3.3) must say the PIN is removed. | 2.3, 6.4, T30, 3.5, INV-41, INV-49, AE-94…AE-96, AE-110 |
| AMD-5 | G-5 | Accepted | `IO_ERROR` and `TIMEOUT` are not sticky. Settings are re-read at cold start, on settings retry, and at each foreground while the cause is transient. A successful re-read while the app is open applies the stored values without auth. A re-read never relaxes `LOCKED`. Only the existing T6b (late cold-start read) and the capability escapes T7b/T11 can. INV-33's "every cold start until re-saved" now covers the persistent causes only. New code `LOCK_SETTINGS_RECOVERED(cause)`. | T28, T29, INV-33, INV-47, AE-97, AE-98, 10 |
| AMD-6 | G-6 | Accepted; placement stays UQ-2 | At most one `LockNotice` is shown at a time, in open states only. Priority: inert > review settings > fingerprint turned off. The inert and review notices cannot be dismissed. The fingerprint notice can be dismissed and is backed by a persisted pending flag, so process death cannot lose it. | 2.7, 6.3, INV-51, AE-99…AE-101 |
| AMD-7 | G-7 | Accepted | The in-place discreet re-post is silent: it alerts once and keeps the original post time. It runs only after a committed write, re-posts only notifications that are currently active, and never counts as a delivery. New code `NOTIF_REPOST_FAILED`. | 4.6, INV-13, AE-102, 10 |
| AMD-8 | G-8 | Accepted | Forced discreet and secure-screen values are read-only while forced. The stored value applies again when forcing ends. | INV-48, AE-103 |
| AMD-9 | G-9 | Confirmed out of scope | PIN setup reads no backup state. Any "Back up now" shortcut is navigation only. A "last exported backup" indicator is scope growth S8. | 6.5, AE-104, 15 |
| AMD-10 | G-10 | Accepted, generalised | Commit-before-effect applies to every settings field. Consumers see committed state only. `LOCK_SETTING_PERSIST_FAILED` gains a field enum. | INV-3 (pointer), INV-46, AE-105, 10 |
| AMD-11 | G-11 | Accepted | An unreadable attempt state is repaired durably and tagged `counterOrigin = REPAIRED_UNREADABLE`. The exposed delay reason is `ATTEMPT_STATE_UNREADABLE`. No remaining-tries value is exposed until a real verification outcome. `ux-flows.md` owns the wording. | 2.7, 6.2, INV-39, AE-106 |
| AMD-12 | G-12 | Accepted as a rule per the brief; the alternative is S11 | PIN setup is allowed only from `UNLOCKED`, with the lock on, effective `DEVICE_AUTH`, API ≥ 30 and capability `AVAILABLE`, after one device auth. It is never allowed in `DISABLED` or `INERT`. Commit order: `lockMode` first, then the verifier. **UX delta:** the App PIN row, which is shown while the lock is on, must be unavailable while `INERT`. | 6.4, T31, INV-50, AE-107, 15 |
| AMD-13 | G-13 | Accepted with a constraint | Notices may show above operational screens. While the operational state is not `Normal`/`BackupExporting`, they offer no in-app navigation and enqueue no navigation request. | INV-52, AE-108 |
| AMD-14 | R-1 | Aligned with the brief; the former rule is kept as OD-21 option (b) | The public version becomes one app-wide generic constant pair that names no kind, matching brief v2's "generic public version". The former rule (the kind's discreet constants) becomes OD-21 (b). **UX delta:** ux-flows 2.7 and 9.4 need a generic public pair under OD-21 (a). | 2.5, 4.3, INV-5, INV-7, AE-1, AE-109, 13 |
| AMD-15 | R-4 | Confirmed out of scope | All stay out of scope: in-app Snooze/Dismiss (S2), in-app erase (S9), last-export indicator (S8), Inc 2 below API 30 (S10), and PIN-only enabling without a screen lock (S11). | 15 |
| AMD-16 | (none) | Bookkeeping | Owners, the legal-path proposal, reason codes, required tests and the summary are updated for the changes above. | 8.1, 8.2, 10, 14.2, Summary |
| AMD-17 | NEW-1a (Gate 2); `impact-map.md` headline 1 and §9 item 2; `ux-flows.md` 13.4 owed items (1) and (3) | Decided at Gate 2: option (a) | Adds a sixth `NotificationKind`, `RECEIPT_MATCH`, for the auto-match notification that `ReceiptMatchingWorker` posts today as an implicit budget alert with the merchant in its text (F22). The worker passes `RECEIPT_MATCH`. It keeps the `budget_alerts` channel, its `NotificationId.forReceipt` id (20000–29999) and its tap target (`home`, CLEAR_TASK). Discreet text never contains the merchant. Like `WARRANTY`, it never carries the BUDGET discreet constants ("never reads as Budget alert"). Every kind list and every "five kinds" count becomes six. No reason code carries a kind, so the code list is unchanged. | F22, 2.5, 4.1, 4.3, 4.6, 4.7, 5.6, 9, 10, INV-4, INV-7, INV-8, INV-12, INV-13, AE-5, AE-11, AE-13, AE-32, AE-109, AE-111, 14.2 |
| AMD-18 | Gate 2 outcomes (`gate2-decisions.md` §A, §B, §D) | Bookkeeping | OD-1…OD-21 are marked "DECIDED at Gate 2 (2026-10-16): option (a)" in a new column of section 13. OD-14 is "as written". OD-19 is escalated to the release track (option (a)). The option text is kept. `[OD-n]` tags now mark the decided option, and text that describes a rejected option is not in force. TECH-1 and TECH-2 stay open for the guardians at plan stage (13.1). | Header, Conventions, 1, 10, 13, 13.1, Summary |
| AMD-19 | `impact-map.md` §9 item 4; `ux-flows.md` 2.7, 9.4 and 13.4 owed item (2) | Accepted; UX owns the copy | Section 4.3 drops the illustrative discreet strings. `ux-flows.md` 9.4 is the single source of truth for discreet and public copy, and the 4.3 table names string ids only. The BRIEFING discreet title is "Your AI briefing is ready", which reuses the existing `notification_briefing_ready_title` (F24). The public version is the single generic pair `notif_public_title` / `notif_public_text` for all six kinds (OD-21 a). | 4.3, AE-6 |
| AMD-20 | `impact-map.md` §9 items 1, 3 and 5 (headlines 2 and 3) | Accepted; the mechanism is TECH-2 | F19 is corrected: the Compose UI-test dependencies already exist, so S7 is withdrawn and gate tests need no new dependency. Discreet and public constants are localized string resources, resolved to text outside the pure domain layer, which has no `R` (F24). Which component resolves them is TECH-2. Translation requirements: every 9.4 id ships in en, el and es. `action_dismiss` has no `values-el` entry, and `notification_briefing_ready_title` has no `values-el` or `values-es` entry (F24, F25). The hardcoded bill action labels move to resources. §9 items 7 (stale LEGAL_PATHS step) and 8 (segment placement) are architecture-doc matters for architecture-guardian, not spec-side. Item 9 was closed by AMD-1…AMD-13. Item 6 is AMD-21. | F19, F24, F25, 4.1, 4.4, INV-5, 14.2, 15 (S7) |
| AMD-21 | `impact-map.md` headline 4 and §9 item 6 | Accepted, with a source finding | The re-post sweep detects the kind by channel, then by id range on `budget_alerts`: warranty 10000–19999, receipt match 20000–29999, budget otherwise. Bill, anomaly and briefing are detected by channel alone. Other channels are never touched. The sweep builds a fresh notification from the decision. It reuses only the posted id, channel, content intent, action PendingIntents (with the action policy re-applied) and post time, and it never copies text or extras from the active notification. Finding (F23): `BudgetMonitor` posts with the raw `budget.id.toInt()`, not `NotificationId.forBudget`, so a budget alert whose budget id is in 10000–29999 gets the wrong kind from the range check. The result is still content-free (INV-5); only the kind label is wrong. Removing this residual is the new technical item TECH-3 (13.1). | F23, 4.6, INV-13, AE-112, 13.1 |
| AMD-22 | `workflows/active/app-lock-privacy-mode-plan.md` §8 "Spec bookkeeping" (the plan's provisional "AMD-18") and its "Orchestrator reconciliation note" items 2 and 4; plan RP-13; `ux-flows.md` 2.2, 3.2, 3.3, 4.1, 4.2 | Adopted, with timer, host-loss and PIN-step rules. Legacy-marker rule added. | **Composition.** INV-26, INV-51 and 3.6 now cover `AUTHENTICATING(UNLOCK)` only. `AUTHENTICATING(ENABLE)` starts only from `DISABLED` and `AUTHENTICATING(CONFIRM_CHANGE)` only from `UNLOCKED`. Both keep `MainScreen` composed and render as their origin state (INV-53). Reasons: the origin already showed this content to the person who started the prompt, so nothing new is exposed. `FLAG_SECURE` follows committed state only, so it stays set under forcing. The prompt carries constant text only. Tearing `MainScreen` down would rebuild the app and replay one-shot effects (F7, R2) on every enable or weakening prompt. This matches the designed UX: the system prompt is shown over the settings screen (`ux-flows.md` 4.2), and the lock surface's `AuthPromptOpen` is `AUTHENTICATING(UNLOCK)` (4.1). **Timer.** No time accrues while a prompt is shown in the foreground. The first background during an open-origin prompt is recorded. After an unlock-equivalent success it is discarded (no re-lock, no loop, AE-42). After any other outcome it is handed to the timer, which is fail-closed (INV-54, T32–T34). Pending-background consumption is now explicit (3.4). **Host loss.** The new event `PROMPT_HOST_LOST` ends an attempt whose host Activity is destroyed outside a configuration change, for any purpose. No `AUTHENTICATING` state can then get stuck (T35). **PIN steps.** [2] In effective `APP_PIN` mode, PIN confirmations are in-app steps inside `UNLOCKED`, not `AUTHENTICATING`. A background during one is an ordinary background (INV-54). **Legacy notifications (TECH-3).** 4.6 did not say how a notification without a kind marker is classified; only the TECH-3 option (a) text in 13.1 did. A binding marker-precedence and legacy-fallback rule is added to 4.6. **UX delta:** the `ux-flows.md` 5 layer diagram must read `Authenticating(UNLOCK)`. The 3.8 row "Auth prompt open when app goes to background" must say that a settings prompt returns to the settings screen, or to the lock screen once the timeout has elapsed. The 4.2 `PIN_*` purposes are UI states, not spec `AUTHENTICATING`. | Conventions, 2.4, 3.1, T6b, T9, T10, T20, T29, T32–T35, 3.3, 3.4, 3.5, 3.6, 3.7, 4.6, INV-25, INV-26, INV-34, INV-51, INV-53, INV-54, 8.1, AE-99, AE-113…AE-121, 13.1, 14.2, Summary |

Stage 3b net effect: 9 new invariants (52 in total), 6 new transitions, 26 new acceptance examples (110 in total) and
1 new open decision (21 in total). Schema impact is still **none**. Inc 2's device-local PIN store gains two small additive fields.
Neither is Room or `.costbackup`.

Post-Gate-2 (AMD-17…AMD-21, 2026-10-17) sources: `gate2-decisions.md` (DECIDED 2026-10-16, including NEW-1a),
`impact-map.md` (headlines 1–4, §9) and `ux-flows.md` 2.7, 9.4 and 13.4, checked against source. New ids are appended:
F22…F25, AE-111…AE-112 and TECH-3. Net effect: no new invariants (52 in total), no new transitions, 2 new acceptance
examples (112 in total), 4 new source facts and one corrected fact (F19). All 21 open decisions are decided; OD-19 is
escalated to the release track. Still open: TECH-1 and TECH-2 (plan stage) and TECH-3 (raised by AMD-21). Schema impact
is still **none**.

Plan stage (AMD-22, 2026-10-17) sources: the feature-planner request in `workflows/active/app-lock-privacy-mode-plan.md`
(§8 "Spec bookkeeping" and the "Orchestrator reconciliation note") and `ux-flows.md` 2.2, 3.2, 3.3, 3.8, 4.1 and 4.2.
New ids are appended: INV-53…INV-54, T32…T35, AE-113…AE-121, the event `PROMPT_HOST_LOST` and the companion field
`promptBackgroundAt`. Net effect: 2 new invariants (54 in total), 4 new transitions (T1–T35), 9 new acceptance examples
(121 in total). There are no new open decisions. TECH-1…TECH-3 stay open; TECH-3's legacy fallback is now a binding rule
in 4.6. Schema impact is still **none**.

## Conventions

- `app/...` = `app/src/main/java/com/yourname/expensetracker/...`.
- Increment tags: **[1a]** notification policy + deep-link hardening, **[1b]** app lock + forced secure screen,
  **[2]** optional app PIN.
- **[OD-n]** marks a rule written per the recommended option of a decision in section 13. (AMD-18) All of OD-1…OD-21
  were decided at Gate 2 (2026-10-16) per those recommendations, with OD-19 escalated to the release track. Every
  [OD-n] rule is therefore settled. Text that describes a rejected option (for example "under OD-21 (b)") is kept for
  traceability only and is not in force.
- "Lock enabled" = the persisted setting is on. "Lock active" = lock enabled AND it can currently protect
  (section 2.3). "Open" UI states = `DISABLED`, `UNLOCKED`, `INERT`.
  (AMD-22) An "open-origin prompt" is `AUTHENTICATING(ENABLE)` or `AUTHENTICATING(CONFIRM_CHANGE)`. It is not an open
  state for deep links or the timer: links are held (T20) and T32 replaces T13. It is rendered as its origin open state,
  though (INV-53).
- Component names are proposed names for owners and boundaries. They are not class designs. The impact map
  chooses the structure.
- INV-n refers to section 7. AE-n refers to section 12. OD-n refers to section 13. AMD-n refers to the Amendments
  section; changed text carries `(AMD-n)`.

---

## 0. Source-verified facts this spec relies on

| # | Fact | Evidence |
|---|---|---|
| F1 | `.costbackup` contains only `manifest.json`, `database.sqlite`, `files/receipts/*`, `checksums.json`. No DataStore or SharedPreferences. | `data/backup/CostbackupBundle.kt:21-26, 577-642` |
| F2 | `.costbackup` encryption uses a user password (PBKDF2-HMAC-SHA256, 600k iterations), not a device key, so a backup can still be restored after "clear data". | `data/privacy/BackupEncryptionService.kt:23, 51` |
| F3 | Internal backups are written under `filesDir`. They are deleted by "clear data". Only exported copies (SAF) survive. | `data/repository/DatabaseBackupRepositoryImpl.kt:545` |
| F4 | Privacy settings live in a DataStore with a corruption sentinel and a fail-closed load state. This is the pattern to mirror. | `data/privacy/PrivacySettingsRepositoryImpl.kt:34-48, 186-209` |
| F5 | `MainScreen` creates `ReviewViewModel` (`:368`), collects the pending count (`:364`), runs a visual-split DB lookup (`:383-386`) and starts the navigation collector (`:394-410`) **before** the operational-lock early return (`:425-461`). So the app-lock gate must sit **outside** `MainScreen`, not next to the operational lock. | `ui/MainActivity.kt` |
| F6 | Clipboard amount parsing runs in the AddExpense overlay (`:618-621`) and in the SmartFAB listener (`:920-957`). Both are inside `MainScreen`. | `ui/MainActivity.kt` |
| F7 | The POST_NOTIFICATIONS check is `LaunchedEffect(Unit)` inside `MainScreen` (`:471-478`). It re-runs every time `MainScreen` re-enters composition. | `ui/MainActivity.kt` |
| F8 | Navigation requests go through a buffered `Channel` (`:46-49`). A request sent before `MainScreen` composes is delivered later. | `ui/MainViewModel.kt` |
| F9 | `MainActivity` is exported through its LAUNCHER filter. Any installed app can start it explicitly with any data URI, whatever the VIEW filter says. **Manifest changes are not the security boundary. Parameter validation is.** | `AndroidManifest.xml:66-87` |
| F10 | A second deep-link parser, `parseDeepLink`, exists but is used only by its test. It duplicates the rules in `handleIntent`. | `ui/navigation/DeepLinkParser.kt`, `app/src/test/.../ui/navigation/DeepLinkParserTest.kt` |
| F11 | The `lastOpenedDashboardBriefingKey` written by the deep link also selects which briefing Home displays. A caller-supplied key changes Home content as well as suppressing the notification. | `ui/screens/home/HomeViewModel.kt:284`; `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt:32-37` |
| F12 | `map?location` only sets a local highlight. No network call. | `ui/screens/map/SpendingMapViewModel.kt:765-767` |
| F13 | The `analytics?period` allowlist is private to `AnalyticsScreen` (`today/week/month/quarter/year/all`). | `ui/screens/analytics/AnalyticsScreen.kt:1841-1851` |
| F14 | Deep-link diagnostics go to `AiRuntimeDiagnostics`, an in-memory 100-event ring shown in Debug, and the raw caller string is embedded (`MainActivity.kt:197-200`). `Timber.w` logs the raw host (`:245`). | `domain/debug/AiRuntimeDiagnostics.kt:37-54` |
| F15 | Warranty posts through `sendBudgetAlert`, so the boundary cannot tell warranty from budget today. | `service/warranty/WarrantyExpirationWorker.kt:218-222` |
| F16 | Bill notifications have no content intent. Snooze/Dismiss are explicit broadcasts to non-exported receivers. No in-app snooze/dismiss surface exists (no match under `ui/screens/reminder`). | `service/reminder/BillReminderWorker.kt:226-261`; manifest `:117-124` |
| F17 | `targetSdk = 35`, `allowBackup="false"`, no `dataExtractionRules`. | `app/build.gradle.kts:140`; manifest `:32` |
| F18 | No `androidx.biometric` dependency. AppCompat is present (`RescueActivity : AppCompatActivity`). `security-crypto:1.1.0-alpha06` is used by `SecureKeyStorage`. | `app/build.gradle.kts:278`; `data/rescue/RescueActivity.kt:18` |
| F19 | (AMD-20, corrected; the earlier "no Compose UI-test dependency" was wrong) The Compose UI-test dependencies already exist: `testImplementation(libs.androidx.ui.test.junit4)`, the `androidTestImplementation` equivalent, and `debugImplementation(libs.androidx.ui.test.manifest)`. No `createComposeRule` test exists yet, and there is no Hilt android-test setup. Robolectric 4.11.1 is available. Instrumented tests exist only for DAOs and migrations. | `app/build.gradle.kts:348, 357, 364, 371`; `app/src/androidTest`; `impact-map.md` headline 3 |
| F20 | No widgets, shortcuts or tiles. The capture FGS notification uses constant generic text. | `service/NotificationCaptureService.kt:314-321` |
| F21 | Restore and backup run in `viewModelScope`, which is Activity-scoped, so they survive `MainScreen` leaving composition. | `ui/screens/backup/BackupRestoreViewModel.kt:165-173` |
| F22 | (AMD-17) Receipt auto-match is a sixth composition site. After a successful link, `ReceiptMatchingWorker` posts through `postBudgetAlert` on `budget_alerts` with id `NotificationId.forReceipt(receiptId)` (20000–29998). The title is `receipt_matching_auto_matched_title` ("Receipt Auto-Matched" with a leading check-mark emoji). The text is `receipt_matching_auto_matched_message_format` with `receipt.parsedMerchant`, so the merchant reaches the shade and lock screen today. Its tap target is the shared `mainActivityIntent()`: `expensetracker://home`, NEW_TASK + CLEAR_TASK, request code 0. The worker runs inside `runGuardedWithContext` with `requiresNotificationPermission = false` and a local permission check. | `service/receiptmatching/ReceiptMatchingWorker.kt:42-45, 100-130`; `values/strings.xml:2431-2432`; `data/service/AndroidNotificationService.kt:65-86, 171-181` |
| F23 | (AMD-21) Notification ids and channels. `NotificationIdGenerator` reserves budget 1–9999, warranty 10000–19999 (7-day 10000–14999, 30-day 15000–19999), receipt 20000–29999, bill 30000–39999, and general 40000–49999 (anomaly uses `forGeneral`). Ids are range + hash and cannot be mapped back to a row. Channels: `budget_alerts` (budget, warranty, receipt match), `bill_reminders`, `anomaly_alerts` and `ai_briefings`. **`BudgetMonitor` does not use `NotificationId.forBudget`. It posts with the raw `budget.id.toInt()`, so a budget alert's id leaves 1–9999 once budget ids reach 10000** and can then fall in, or collide with, the warranty or receipt ranges (the collision is pre-existing). | `domain/util/NotificationIdGenerator.kt:14-31, 38-67`; `domain/budget/BudgetMonitor.kt:354, 365, 376, 474`; `domain/alerts/AnomalyAlertOrchestrator.kt:176`; `data/service/AndroidNotificationService.kt:21-29`; `service/reminder/BillReminderWorker.kt:224, 295` |
| F24 | (AMD-19, AMD-20) The domain layer has no `R`. The briefing FULL title is the domain literal `BRIEFING_NOTIFICATION_TITLE = "Your AI briefing is ready"`. A resource with the same English text, `notification_briefing_ready_title`, exists only in `values` (no `values-el`, no `values-es`) and nothing references it today. | `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt:46, 65`; `values/strings.xml:2428` |
| F25 | (AMD-20) The bill action labels "Snooze" and "Dismiss" are hardcoded English. `action_dismiss` exists in `values` and `values-es` but has no `values-el` entry. | `service/reminder/BillReminderWorker.kt:259-260`; `values/strings.xml:19`; `values-es/strings.xml:24`; `values-el/strings.xml` (absent) |

---

## 1. Scope recap

**Binding (brief v2, Gate 1 GO):** increments 1a → 1b → 2. No `BIOMETRIC_WEAK`. The lock is unavailable below API 30
and minSdk stays 26. The authenticator is `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` (API 30+). The lock prevents
composition rather than overlaying UI. Secure screen is forced while the lock is on. Lock state lives at process level.
Process death while locked drops a held link. The inert-lock escape applies. Discreet notifications are available to
everyone. A generic public version is always set. Notification actions follow per-API rules. Deep links get BROWSABLE
removal, explicit-component PendingIntents, a parameter allowlist and bounded reason codes. Settings are device-local.
Out of scope: hide-amounts, RescueActivity, DB encryption, new network use, capture/worker posting decisions.

**Provisional (handoff, not settled):** OQ1–OQ4 → OD-1…OD-4. (AMD-18) All four were DECIDED at Gate 2 (2026-10-16):
option (a) each.

---

## 2. Entities and state

### 2.1 `AppLockPrivacySettings` (persisted, device-local)

| Field | Type | Default | Inc | Invalid persisted value → |
|---|---|---|---|---|
| `discreetNotifications` | Boolean | false | 1a | true |
| `appLockEnabled` | Boolean | false | 1b | the whole load state becomes `Unreadable(INVALID_VALUE)` (2.2) |
| `autoLockTimeout` | enum token `IMMEDIATE` (0 s), `S30`, `M1`, `M5`, `M15` | `M1` | 1b | `IMMEDIATE` (most restrictive) |
| `secureScreen` | Boolean | false | 1b | true |
| `lockMode` | enum `DEVICE_AUTH`, `APP_PIN` | `DEVICE_AUTH` | 2 | `APP_PIN` if a PIN verifier exists, else `DEVICE_AUTH` |
| `biometricInPinMode` | Boolean | false | 2 | false |
| load-state marker | `NORMAL` / `CORRUPTED` sentinel | absent on first run | 1a | n/a |

- Timeouts are stored as enum tokens, not milliseconds, so unknown values can be detected.
- A key that is absent while other keys are present takes that field's default. A newer app version adding a key
  must never reset the whole store.
- Owner: `AppLockSettingsRepository` (domain interface, data implementation). It is the only writer.
- Storage location: OD-11 (recommendation: a dedicated store with its own corruption sentinel).

### 2.2 Settings load state (returned by the repository on every read)

`Loaded(settings)` | `FirstRunDefault(settings)` | `Unreadable(cause)`, where
`cause ∈ {IO_ERROR, CORRUPTED, TIMEOUT, INVALID_VALUE}`.

`Unreadable` is never collapsed into the defaults. Callers apply the fail-closed rules in 2.3. A `CORRUPTED`
sentinel persists until the user explicitly saves these settings again (INV-33).

### 2.3 Derived effective values (computed, never persisted)

| Value | Rule | Inc |
|---|---|---|
| `effectiveDiscreet` | 1a: `discreetNotifications OR loadState is Unreadable OR read exceeded T_read`. 1b adds `OR appLockEnabled`, including inert and API-unsupported cases [OD-2]. | 1a/1b |
| `effectiveSecureScreen` | `secureScreen OR appLockEnabled OR loadState is Unreadable OR state = RESOLVING` | 1b |
| `capability` (`DeviceAuthCapability`) | `UNSUPPORTED_API` if API < 30. Otherwise `canAuthenticate(BIOMETRIC_STRONG \| DEVICE_CREDENTIAL)` is mapped to `AVAILABLE`, `NO_CREDENTIAL`, `HW_UNAVAILABLE` or `UNKNOWN` (covers `UNSUPPORTED`, `STATUS_UNKNOWN`, `SECURITY_UPDATE_REQUIRED`). | 1b |
| `effectiveLockMode` (AMD-4) | `APP_PIN` if a PIN verifier record exists, whether readable or unreadable. Otherwise `DEVICE_AUTH`. The stored `lockMode` never overrides this. The orderings in INV-49 and INV-50 keep the two consistent. If a crash leaves them disagreeing, device-auth rules apply when there is no verifier and PIN rules apply when there is one. An absent record is not the same as an unreadable one. | 2 |
| `lockActive` | Evaluated on `effectiveLockMode` (AMD-4). `DEVICE_AUTH`: `appLockEnabled AND capability = AVAILABLE`. `APP_PIN`: `appLockEnabled AND PIN verifier present`; it does not depend on device capability. | 1b/2 |
| `F`, the fail-closed effective record (AMD-1) | Applies whenever the load state is `Unreadable` (any cause): `appLockEnabled = true`, `autoLockTimeout = IMMEDIATE`, `discreetNotifications = true`, `secureScreen = true`, mode = `effectiveLockMode`, `biometricInPinMode = false`. It drives `lockActive`, the timer (T14) and the weakening baseline for a review-save (INV-45). It is never written. | 1a/1b |

### 2.4 `AppLockState` (process memory only)

`RESOLVING` | `DISABLED` | `LOCKED(cause)` | `AUTHENTICATING(attemptId, purpose)` | `UNLOCKED` | `INERT(reason)`.
- `cause ∈ {COLD_START, TIMEOUT, SETTINGS_UNREADABLE}`.
- `purpose ∈ {UNLOCK, ENABLE, CONFIRM_CHANGE}`.
  (AMD-22) The purpose fixes the **origin**, which is the state every non-success outcome returns to:
  - `UNLOCK` comes from `LOCKED(cause)`, and the cause is kept;
  - `ENABLE` comes from `DISABLED`;
  - `CONFIRM_CHANGE` comes from `UNLOCKED`.

  `ENABLE` and `CONFIRM_CHANGE` are the open-origin prompts. They render as their origin (INV-53). [2] PIN confirmations
  in effective `APP_PIN` mode are not `AUTHENTICATING` states (INV-54).
- `reason ∈ {NO_CREDENTIAL, HW_UNAVAILABLE, UNKNOWN, API_UNSUPPORTED, SETTINGS_UNREADABLE_NO_AUTH}`.

Companion process-memory fields:
- `lastBackgroundedAt`: monotonic `elapsedRealtime`, nullable. (AMD-22) It holds a pending background, which the next
  foreground evaluation consumes (3.4).
- `inFlightAttemptId`: nullable.
- `promptBackgroundAt` (AMD-22): monotonic, nullable, bound to `inFlightAttemptId`. It records the first background
  during an open-origin prompt (T32). It is cleared when that attempt ends.
- `heldLink`: see 2.6.

Owner: `AppLockController`, a process-level singleton and the only writer. Nothing is persisted. Every process start
begins in `RESOLVING`.

### 2.5 `NotificationKind` and `NotificationPresentationDecision` (values)

- Kinds (AMD-17: six): `BILL`, `ANOMALY`, `BUDGET`, `WARRANTY`, `RECEIPT_MATCH`, `BRIEFING`. `RECEIPT_MATCH` was
  added at Gate 2 (NEW-1a, F22). The capture FGS notification is not a kind (INV-12).
- Decision fields:
  - `mode`: `FULL` | `DISCREET`.
  - `visibleTitle` and `visibleText`: either the caller's detailed text (FULL) or the kind's discreet constants.
  - `expandedStyleAllowed`: Boolean.
  - `visibility`: always `PRIVATE`.
  - `publicVersion` (AMD-14): [OD-21 a] the app-wide generic public constants, identical for all six kinds (AMD-17)
    and naming no kind. Under OD-21 (b) (rejected at Gate 2), the kind's discreet constants. No actions, no expanded
    style.
  - `actionPolicy`: `UNCHANGED` | `AUTH_REQUIRED` | `NONE`.
  - `fallbackReason`: nullable bounded code.
- Owner: `NotificationContentPolicy`.

### 2.6 `DeepLinkRequest` and held link (values)

- `DeepLinkRequest` = `host` (closed enum `HOME`, `DASHBOARD`, `ACTIVITY`, `REVIEW`, `PLAN`, `ADD`, `ANALYTICS`, `MAP`)
  plus validated, typed parameters (5.3). It carries no raw string from the URI except a validated `location`.
- `HeldDeepLink` = `{request, heldAtElapsed}`. There is one slot per process. It is held in memory only and never
  persisted, logged or put in a Bundle.
- Owners: `DeepLinkPolicy` creates requests. `AppLockController` holds them.

### 2.7 `AppPinVerifier` and `AppPinAttemptState` [2] (persisted, device-local)

- Verifier: `{verifierVersion, kdfId, kdfParams, salt (≥16 B), hash (≥32 B), keystoreBound: Boolean}`.
- Attempt state: `{consecutiveFailures: Int, currentDelayMs: Long, delayStartedElapsed: Long, delayStartedBootCount: Int,
  counterOrigin: FAILURES | REPAIRED_UNREADABLE}`. `counterOrigin` was added by AMD-11 (6.2).
- Biometric-in-PIN-mode Keystore key alias, when enabled [OD-16].
- `biometricInvalidatedNoticePending: Boolean`, default false (AMD-6, INV-51).
- Owner: `AppPinStore` (Segment 28).

### 2.8 Storage and backup matrix

| Item | Where | Room? | In `.costbackup`? | Restore effect | "Clear data" | Cloud backup | Device-to-device (D2D) |
|---|---|---|---|---|---|---|---|
| `AppLockPrivacySettings` | device-local preferences | No | No (F1) | none | deleted → defaults (all off) | excluded (`allowBackup=false`) | **verify (OD-19)** |
| `AppLockState`, `lastBackgroundedAt`, held link | process memory | No | No | n/a (restart = cold start) | n/a | n/a | n/a |
| PIN verifier, attempt state, notice flag [2] | device-local, app-private | No | No | none | deleted (PIN removed) | excluded | verify (OD-19). If Keystore-bound, unusable on another device (INV-38) |
| Keystore keys [2] | Android Keystore | No | No | none | deleted | never | never |
| Diagnostic events | per OD-12 (recommended: existing `PipelineDiagnosticEvent` table) | existing table, no schema change | follows the existing table's backup behaviour | follows the existing table | deleted | excluded | as DB |

**Conclusion: no Room schema change.** Restore, import and DB reset never read or write lock settings or the PIN
(INV-1). No in-app code clears preference stores (grep: no `deleteSharedPreferences` or datastore-directory deletion).

---

## 3. App lock state machine [1b] (PIN substates [2])

### 3.1 Events

- `PROCESS_START`.
- `SETTINGS_RESOLVED(loadState)`, `RESOLVE_TIMEOUT`, `LATE_SETTINGS_RESOLVED(loadState)`.
- `APP_BACKGROUNDED`: all app activities stopped. Configuration changes are excluded.
- `APP_FOREGROUNDED`, and `LOCK_QUERY` (a deep link asks whether it may apply now; 3.4).
- `UNLOCK_TAPPED`.
- `AUTH_SUCCEEDED(attemptId)`, `AUTH_CANCELLED(attemptId)`, `AUTH_ERROR(attemptId, code)`.
- `PROMPT_HOST_LOST(attemptId)` (AMD-22): the Activity hosting the prompt was destroyed while not changing
  configurations, and no outcome for `attemptId` has arrived (T35).
- `ENABLE_REQUESTED`, `DISABLE_REQUESTED`, `WEAKENING_CHANGE_REQUESTED` [OD-8].
- `DEEP_LINK_RECEIVED(request)`.
- `PIN_SUBMITTED` [2].
- `SETTINGS_REREAD(loadState)` (AMD-5): a read after resolution, from settings retry or a foreground while the cause
  is transient (INV-47).
- `REVIEW_SAVE_REQUESTED(record)` (AMD-1).
- `PIN_SETUP_REQUESTED`, `PIN_REMOVE_REQUESTED` [2] (AMD-12, AMD-4).
- Implicit process death.

### 3.2 Transitions

| # | From | Event / guard | To | Side effects |
|---|---|---|---|---|
| T1 | (none) | `PROCESS_START` | `RESOLVING` | `effectiveSecureScreen` = true. Neutral surface. No financial UI. |
| T2 | `RESOLVING` | resolved, lock disabled | `DISABLED` | secure screen per setting. Apply held link. |
| T3 | `RESOLVING` | resolved, enabled, `lockActive` | `LOCKED(COLD_START)` | `LOCK_SHOWN_COLD_START` |
| T4 | `RESOLVING` | resolved, enabled, `DEVICE_AUTH`, capability ≠ `AVAILABLE` | `INERT(reason)` | banner, `LOCK_INERT_*`. Apply held link. |
| T5 | `RESOLVING` | `Unreadable` or `RESOLVE_TIMEOUT`, AND (capability `AVAILABLE` OR verifier present [2]) | `LOCKED(SETTINGS_UNREADABLE)` | `LOCK_SHOWN_SETTINGS_UNREADABLE` |
| T6 | `RESOLVING` | `Unreadable` or `RESOLVE_TIMEOUT`, capability ≠ `AVAILABLE`, no verifier | `INERT(SETTINGS_UNREADABLE_NO_AUTH)` | banner. Apply held link. |
| T6b | `LOCKED(SETTINGS_UNREADABLE)` caused by `RESOLVE_TIMEOUT` only | `LATE_SETTINGS_RESOLVED(Loaded, disabled)` | `DISABLED` | Not while `AUTHENTICATING` (here always `AUTHENTICATING(UNLOCK)`, AMD-22). Not if the cause was `CORRUPTED`, `IO_ERROR` or `INVALID_VALUE`. |
| T7 | `LOCKED` | `UNLOCK_TAPPED`, no attempt in flight, capability `AVAILABLE` | `AUTHENTICATING(new id, UNLOCK)` | launch system prompt |
| T7b | `LOCKED` | `UNLOCK_TAPPED`, `DEVICE_AUTH`, capability ≠ `AVAILABLE` on re-check | `INERT(reason)` | banner |
| T8 | `AUTHENTICATING(id, UNLOCK)` | `AUTH_SUCCEEDED(id)` | `UNLOCKED` | `LOCK_UNLOCKED`. Apply held link once. |
| T9 | `AUTHENTICATING(id, *)` | `AUTH_CANCELLED(id)` | previous state (`LOCKED`, or unchanged for ENABLE/CONFIRM). (AMD-22) That is the purpose's origin (2.4): `LOCKED(cause)`, `DISABLED` or `UNLOCKED`. | `LOCK_AUTH_CANCELLED`. No auto re-prompt. (AMD-22) For ENABLE/CONFIRM_CHANGE the timer handoff and link release follow T34. |
| T10 | `AUTHENTICATING(id, *)` | `AUTH_ERROR(id, lockout / timeout / system-cancel / hw / other)` | previous state (AMD-22: the origin, as T9) | `LOCK_AUTH_LOCKOUT` or `LOCK_AUTH_ERROR(bucket)`. (AMD-22) For ENABLE/CONFIRM_CHANGE, T34 applies as well. |
| T11 | `AUTHENTICATING(id, UNLOCK)` | `AUTH_ERROR(id, NO_DEVICE_CREDENTIAL)` and capability ≠ `AVAILABLE` on re-check | `INERT(NO_CREDENTIAL)` | banner |
| T12 | any | `AUTH_*` with a stale or unknown id | unchanged | ignored |
| T13 | `UNLOCKED`, `INERT` | `APP_BACKGROUNDED` | unchanged | `lastBackgroundedAt := now` (monotonic) |
| T14 | `UNLOCKED`, `INERT` | `APP_FOREGROUNDED` or `LOCK_QUERY`, `lockActive`, elapsed ≥ timeout | `LOCKED(TIMEOUT)` | `LOCK_SHOWN_TIMEOUT`. In-flight link → held. |
| T15 | `UNLOCKED`, `INERT` | `APP_FOREGROUNDED` or `LOCK_QUERY`, `lockActive`, elapsed < timeout | `UNLOCKED` | clear the inert banner if it was shown |
| T16 | `UNLOCKED` | `APP_FOREGROUNDED`, enabled, `DEVICE_AUTH`, capability ≠ `AVAILABLE` | `INERT(reason)` | banner |
| T17 | `DISABLED` | `ENABLE_REQUESTED`, API ≥ 30, capability `AVAILABLE` | `AUTHENTICATING(id, ENABLE)` → on success: durable write `appLockEnabled=true` → `UNLOCKED` | write fails → `DISABLED`, `LOCK_SETTING_PERSIST_FAILED` |
| T18 | `UNLOCKED` | `DISABLE_REQUESTED` → fresh auth [OD-8] → durable write false | `DISABLED` | write fails → stays `UNLOCKED` and enabled |
| T19 | `INERT` | `DISABLE_REQUESTED` (no auth possible) → durable write false | `DISABLED` | |
| T20 | `LOCKED`, `AUTHENTICATING` (any purpose, AMD-22), `RESOLVING` | `DEEP_LINK_RECEIVED(r)` | unchanged | `heldLink := r` (replaces any previous). `DL_HELD_LOCKED` or `DL_HELD_REPLACED`. (AMD-22) A link held during an open-origin prompt is released through `LOCK_QUERY` when the prompt ends (T33, T34). |
| T21 | `DISABLED`, `UNLOCKED`, `INERT` | `DEEP_LINK_RECEIVED(r)` | after `LOCK_QUERY` (T14/T15) | apply now if still open, else T20 |
| T22 | any | process death | (next start T1) | held link lost. Attempt state [2] persists. |
| T23 [2] | `LOCKED` (`APP_PIN`) | `PIN_SUBMITTED`, no active delay, verifier correct | `UNLOCKED` | reset counter (durable first). Apply held link. |
| T24 [2] | `LOCKED` (`APP_PIN`) | `PIN_SUBMITTED`, wrong | `LOCKED` | durable `consecutiveFailures+1` and delay tier **before** showing the result |
| T25 [2] | `LOCKED` (`APP_PIN`) | `PIN_SUBMITTED` during an active delay | `LOCKED` | rejected without evaluation. Entry disabled in UI. |
| T26 (AMD-3) | `DISABLED` | `ENABLE_REQUESTED`, and API < 30 or capability ≠ `AVAILABLE`, evaluated at request time | `DISABLED` | No prompt. One refusal code per cause per process: `LOCK_UNAVAILABLE_API`, `LOCK_UNAVAILABLE_NOT_SECURE` (`NO_CREDENTIAL`), `LOCK_UNAVAILABLE_CAPABILITY(HW_UNAVAILABLE \| UNKNOWN)`. |
| T27 (AMD-1) | `UNLOCKED` or `INERT(*)`, with load state `Unreadable(CORRUPTED \| INVALID_VALUE)` | `REVIEW_SAVE_REQUESTED(record)`, then auth per INV-45, then the full-record write (INV-44). With the lock off and a verifier present, INV-49's ordered removal runs first. | lock off → `DISABLED`; lock on → `UNLOCKED` | `LOCK_SETTINGS_REVIEW_SAVED`. Cancel, auth error, rejection or write failure → unchanged, sentinel kept. |
| T28 (AMD-5) | `UNLOCKED` or `INERT(*)`, with load state `Unreadable(IO_ERROR \| TIMEOUT)` | `SETTINGS_REREAD(Loaded \| FirstRunDefault)` | Per stored values: lock off → `DISABLED`. Lock on and `lockActive` → `UNLOCKED`. Lock on, `DEVICE_AUTH`, capability ≠ `AVAILABLE` → `INERT(reason)`. | `LOCK_SETTINGS_RECOVERED(cause)`. Effective values are recomputed. No auth. Never locks mid-session. |
| T29 (AMD-5) | `LOCKED`, `AUTHENTICATING` (any purpose, AMD-22) | `SETTINGS_REREAD(any)` | unchanged | The new load state is kept for use after unlock. A settings read moves `LOCKED` to an open state without auth only through T6b. The capability escapes T7b/T11 are unaffected. (AMD-22) During an open-origin prompt the kept load state is applied by T28 once the prompt has ended in an open state. |
| T30 [2] (AMD-4) | `UNLOCKED`, effective `APP_PIN` | `PIN_REMOVE_REQUESTED` or `DISABLE_REQUESTED` with the correct current PIN (INV-39). T18 in effective `APP_PIN` mode runs as this row. | Remove with capability `AVAILABLE` → `UNLOCKED` (`DEVICE_AUTH`, lock on). Remove otherwise, or disable → `DISABLED`. | INV-49 order: (1) delete the verifier set; (2) write the settings. `PIN_REMOVED`, plus `LOCK_DISABLED` when the lock goes off. Failure in (1) → unchanged. Failure after (1) → effective `DEVICE_AUTH` with the lock still on → `UNLOCKED` or `INERT(reason)`, plus `LOCK_SETTING_PERSIST_FAILED(LOCK_MODE)`. |
| T31 [2] (AMD-12) | `UNLOCKED`, lock on, effective `DEVICE_AUTH`, API ≥ 30, capability `AVAILABLE` | `PIN_SETUP_REQUESTED`, then `AUTHENTICATING(id, CONFIRM_CHANGE)` succeeds, then a PIN is entered twice and accepted (OD-13) | `UNLOCKED` (effective `APP_PIN`) | INV-50 order: write `lockMode = APP_PIN`, then the verifier. `PIN_SET`. Cancel, failure or interruption before the verifier write leaves effective `DEVICE_AUTH`, so protection is unchanged. |
| T32 (AMD-22) | `AUTHENTICATING(id, ENABLE \| CONFIRM_CHANGE)` | `APP_BACKGROUNDED`, `APP_FOREGROUNDED` or `LOCK_QUERY` | unchanged | The first `APP_BACKGROUNDED` of the attempt sets `promptBackgroundAt := now` (monotonic). Later backgrounds do not move it. No lock evaluation happens while the prompt is in flight; it happens at the outcome (T33, T34). `LOCK_QUERY` holds its link (T20). `MainScreen` stays composed (INV-53). |
| T33 (AMD-22) | `AUTHENTICATING(id, ENABLE \| CONFIRM_CHANGE)` | `AUTH_SUCCEEDED(id)` | As the purpose's owning row, including that row's write-failure outcome. ENABLE: T17. CONFIRM_CHANGE: T18, T27, T31 or an INV-3 weakening write. | Codes per the owning row. If the prompt is unlock-equivalent (INV-54), `promptBackgroundAt` is discarded and `lastBackgroundedAt := null`. Otherwise, after the owning row's outcome, the background is handed to the timer as in T34. Then a held link is released through `LOCK_QUERY` (T21). |
| T34 (AMD-22) | `AUTHENTICATING(id, ENABLE \| CONFIRM_CHANGE)` | `AUTH_CANCELLED(id)` (T9), `AUTH_ERROR(id, *)` (T10) or `PROMPT_HOST_LOST(id)` (T35) | origin: `DISABLED` for ENABLE, `UNLOCKED` for CONFIRM_CHANGE | Nothing is written and the change is not applied. With origin `UNLOCKED` and `promptBackgroundAt` set: `lastBackgroundedAt := promptBackgroundAt`. T14/T15/T16 are then evaluated at once if the app is in the foreground, otherwise at the next `APP_FOREGROUNDED` or `LOCK_QUERY`. With origin `DISABLED`, `promptBackgroundAt` is dropped. A held link is then released through `LOCK_QUERY` (T21). `AUTH_ERROR(NO_DEVICE_CREDENTIAL)` has no INERT row here: after ENABLE the next request re-evaluates (T26); after CONFIRM_CHANGE, T16 applies at the next foreground. |
| T35 (AMD-22) | `AUTHENTICATING(id, *)` | `PROMPT_HOST_LOST(id)` | origin, as `AUTH_CANCELLED(id)`. UNLOCK: T9, so the state stays `LOCKED` and Unlock is enabled again. ENABLE/CONFIRM_CHANGE: T34. | `LOCK_AUTH_CANCELLED`. Any later callback for `id` is stale (T12). A configuration change such as rotation is not host loss (3.5). |

Re-activation after `INERT` happens only at the next lock trigger: a cold start (T3) or a timeout (T14). It never
happens mid-session. The lock setting itself is never auto-disabled.

### 3.3 Illegal transitions (each must be rejected and covered by a test)

- `LOCKED` → `UNLOCKED` or open UI without `AUTH_SUCCEEDED` with the matching id, or without a correct PIN [2].
- `RESOLVING` → `UNLOCKED`.
- `DISABLED` → `LOCKED` without a durable enable.
- Any operational/restore state changing `AppLockState`.
- Any worker, receiver, service or action worker changing `AppLockState`.
- A late `LATE_SETTINGS_RESOLVED` overriding a `CORRUPTED`, `IO_ERROR` or `INVALID_VALUE` fallback.
- (AMD-5) Any `SETTINGS_REREAD` moving `LOCKED` or `AUTHENTICATING` to an open state (T29).
- (AMD-1, AMD-8) A per-field settings write while the load state is `Unreadable`. A write to a forced stored value.
- (AMD-1) A successful review-save that leaves the sentinel in place, or a failed one that clears it.
- (AMD-12) PIN setup from `DISABLED` or any `INERT` state, or on API < 30.
- (AMD-4) Effective `APP_PIN` mode with no verifier record, which would show a PIN pad with nothing to verify.
- (AMD-4) A PIN removal that leaves a `DEVICE_AUTH` lock enabled while capability ≠ `AVAILABLE`.
- (AMD-22) Entering a purpose from anything but its origin:
  - `AUTHENTICATING(UNLOCK)` from anything but `LOCKED`;
  - `AUTHENTICATING(ENABLE)` from anything but `DISABLED`;
  - `AUTHENTICATING(CONFIRM_CHANGE)` from anything but `UNLOCKED`. No prompt is launched from `INERT` or `RESOLVING`.
- (AMD-22) An open-origin prompt that removes `MainScreen` from composition or composes the lock surface. An
  `AUTHENTICATING(UNLOCK)` that composes `MainScreen`.
- (AMD-22) Applying an outcome across purposes: an ENABLE or CONFIRM_CHANGE success that unlocks `LOCKED`, or an UNLOCK
  success that applies a settings change.
- (AMD-22) An open-origin prompt that ends without success while a background was recorded, and returns to `UNLOCKED`
  without the timer evaluating that background (T34).
- (AMD-22) An `AUTHENTICATING` state that outlives its host Activity outside a configuration change (T35).

### 3.4 Timer rules

- Clock: `elapsedRealtime`, which is monotonic and includes deep sleep. Wall-clock, time zone and DST changes have no
  effect.
- The timer starts on `APP_BACKGROUNDED` (process-level stop). It never starts on focus loss, multi-window, the
  notification shade, configuration changes (rotation, locale, font scale), or an app-initiated auth prompt being shown.
- The boundary is inclusive: elapsed ≥ timeout locks. `IMMEDIATE` = 0, so any background followed by a foreground
  locks.
- Pickers, camera, share sheet and system settings opened by the app are subject to the timeout like any other
  background ("within the timeout never re-lock"). The app's own auth prompt is always exempt.
- **Order independence:** deep-link handling issues `LOCK_QUERY`. The controller evaluates any pending timeout itself,
  so whether `onNewIntent` runs before or after `onStart` cannot cause a link to apply when the lock should engage.
- (AMD-22) **Pending background.** T13 records a pending background in `lastBackgroundedAt`. The next foreground
  evaluation (T14, T15 or T16, from `APP_FOREGROUNDED` or `LOCK_QUERY`) consumes it: `lastBackgroundedAt := null`.
  Every auth success that ends in an open state also clears it. So a `LOCK_QUERY` while the app is in use never measures
  from an old background.
- (AMD-22) **Open-origin prompts** (`ENABLE`, `CONFIRM_CHANGE`). The prompt being shown starts no timer, as above, and no
  time accrues while it is shown in the foreground, however long that takes. A real background during the prompt is
  recorded (T32) and resolved at the outcome (INV-54). After an unlock-equivalent success it is discarded (T33). After
  anything else it goes to the timer (T34). The `UNLOCK` prompt needs no such rule, because its non-success outcomes stay
  `LOCKED`.

### 3.5 Retry, cancellation, process death

- Retry = a new `UNLOCK_TAPPED` with a new `attemptId`. At most one prompt is in flight. The prompt is launched only by
  the explicit Unlock action. There is no timed or automatic prompt.
- System cancellation (Home pressed during the prompt, Activity paused or destroyed) → `AUTH_ERROR`/`CANCELLED` → back
  to `LOCKED`.
- Rotation during a prompt: a success delivered to the recreated Activity is accepted only if the id matches.
  Otherwise the state stays `LOCKED`.
- Process death in any state → `RESOLVING` at next start, then `LOCKED` if active. A held link is lost.
- Enable interrupted before the durable write → the lock stays off. After the write → enabled, and the next start
  locks.
- Disable interrupted before the durable write → the lock stays on.
- (AMD-1) Review-save interrupted before its single write → nothing saved, sentinel kept. After the write → saved.
- (AMD-4) PIN removal or disable in PIN mode interrupted between deleting the verifier and the settings write → the
  next start evaluates `DEVICE_AUTH` rules with the lock on: `LOCKED` with the device prompt, or `INERT` with T19
  available (AE-96).
- (AMD-12) PIN setup interrupted before the verifier write → effective `DEVICE_AUTH`. The stored `lockMode = APP_PIN`
  is harmless because effective mode follows the verifier (2.3). A later setup or removal overwrites it.
- (AMD-22) Backgrounding during an ENABLE or CONFIRM_CHANGE prompt: the system cancels the prompt, as for UNLOCK. The
  state returns to the origin with nothing written, and the background goes to the timer (T34). A return at or after
  the timeout therefore locks. If the platform keeps the prompt alive (an OEM credential UI, AE-42) and it later
  succeeds, the change applies with no re-lock (T33).
- (AMD-22) Host loss: if the Activity hosting any prompt is destroyed while not changing configurations, and no outcome
  has arrived, the attempt ends as cancelled (T35). An Unlock button or settings switch can therefore never stay disabled
  for good. A rotation keeps the attempt.
- (AMD-22) Process death during an open-origin prompt: the change did not happen (INV-3). The next start follows T1–T6:
  the lock is off after an interrupted enable, and `LOCKED` after an interrupted confirm while the lock is on.
- (AMD-22) Retrying an open-origin prompt is a new request with a new `attemptId`. There is no automatic re-prompt
  (INV-34).

### 3.6 Precedence with the operational/restore lock

| `AppLockState` | `AppOperationalState` | Rendered |
|---|---|---|
| `RESOLVING` | any | neutral surface (no financial UI) |
| `LOCKED`, `AUTHENTICATING(UNLOCK)` (AMD-22) | any | app-lock surface |
| `DISABLED`, `UNLOCKED`, `INERT` | `Normal`, `BackupExporting` | `MainScreen` |
| `DISABLED`, `UNLOCKED`, `INERT` | `RestoreInProgress`, `RestartRequiredAfterRestore`, `CriticalRecoveryRequired` | existing `AppOperationalLockScreen` (unchanged, inside `MainScreen`) |
| `AUTHENTICATING(ENABLE)`, `AUTHENTICATING(CONFIRM_CHANGE)` (AMD-22) | any | as the origin (`DISABLED` or `UNLOCKED`) in the two rows above. `MainScreen` stays composed, and the OS draws the system prompt over it (INV-53). |

- The post-restore "Restart Now" (`Runtime.exit(0)`, `MainActivity.kt:433`) is a cold start → T1 → `LOCKED`.
- Lock settings are not in the DB, so restore cannot change them.
- Naming: "app lock" (this feature) versus "operational lock" (existing). The two are never merged.

### 3.7 UI state while re-locked [OD-9]

- Re-locking removes `MainScreen` from composition. Composition-scoped coroutines (`rememberCoroutineScope`) are
  cancelled at that point.
- Long-running operations must run in ViewModel or application scope. Restore and backup already do (F21).
- Recommended: preserve navigation state and saveable UI state (including pending activity-result registrations)
  without composing, so an in-progress add-expense or picker result survives unlock.
- Effects keyed on `Unit` inside `MainScreen` re-run after every unlock (F7). Effects that must run once per process
  need their own guard (risk R2).
- (AMD-22) Only a re-lock (T14) or a cold start removes `MainScreen`. An ENABLE or CONFIRM_CHANGE prompt is not a re-lock
  and never removes it (INV-53).

---

## 4. Notification content policy [1a] (lock terms [1b])

### 4.1 Ownership split

- **Workers and coordinators** (`BillReminderWorker`, `AnomalyAlertOrchestrator`, `BudgetMonitor`,
  `WarrantyExpirationWorker`, `ReceiptMatchingWorker` (AMD-17), `DeliverProactiveBriefingNotificationUseCase`) keep
  sole ownership of **whether and when** to post. That covers thresholds, cooldowns, dedupe, claim/mark, quiet hours
  and the permission gate. They pass **kind + their existing detailed text**. Their composition strings are unchanged.
- **`NotificationContentPolicy`** (single owner) decides presentation: mode, visible text, expanded style, visibility,
  public version and action policy.
- **The two builder owners** apply the decision: `AndroidNotificationService` for budget, warranty, receipt match
  (AMD-17), anomaly and briefing, and `BillReminderWorker` for bill. Moving the bill builder behind
  `NotificationService` is an option for the impact map, not a requirement.
- `WarrantyExpirationWorker` must pass `WARRANTY`, not post as an implicit budget (F15). The channel is unchanged
  (`budget_alerts`).
- (AMD-17) `ReceiptMatchingWorker` must pass `RECEIPT_MATCH`, not post as an implicit budget (F22). The channel
  (`budget_alerts`), the id (`NotificationId.forReceipt`) and the tap target are unchanged. Its post/no-post logic,
  `DeliveryResult` handling, suppression records and the receipt-link call (`receiptLinkService.linkReceiptToExpense`)
  are unchanged.
- (AMD-20) **Localized constants, resolved outside the pure domain.** Discreet and public constants are Android
  string resources in `values`, `values-el` and `values-es`, with the ids listed in `ux-flows.md` 9.4. They are
  resolved to text outside the pure domain layer, which has no `R` (F24). If `NotificationContentPolicy` stays in the
  domain layer, its decision identifies the constants (for example by kind) and never carries literal copy. Which
  component resolves the text, and how the bounded settings read fits the non-suspend `NotificationService`
  (`impact-map.md` headline 2), is TECH-2 (13.1). The rules in 4.2–4.6 bind whichever mechanism is chosen.
- (AMD-20) **Translation requirements.** Every 9.4 id ships in en, el and es (lint MissingTranslation, FG-20). Known
  gaps today: `action_dismiss`, reused for the bill Dismiss label, has no `values-el` entry (F25), and
  `notification_briefing_ready_title`, reused for the BRIEFING title, has neither a `values-el` nor a `values-es` entry
  (F24). The bill action labels, hardcoded in English today, become resources (4.4).

### 4.2 Mode resolution (evaluated per post)

| Inputs | Mode |
|---|---|
| `Loaded` or `FirstRunDefault`, discreet off, (1b) lock not enabled | `FULL` |
| discreet on | `DISCREET` |
| (1b) `appLockEnabled`, including inert and API-unsupported [OD-2] | `DISCREET` |
| `Unreadable(any)`, or read exceeded `T_read` | `DISCREET` + `NOTIF_POLICY_SETTINGS_UNREADABLE(cause)` |
| policy threw a non-cancellation exception | `DISCREET` + `NOTIF_POLICY_FALLBACK_EXCEPTION(class name only)` |
| `CancellationException` during the read | propagates. No post, no fallback. |

### 4.3 Per-kind presentation

(AMD-19) **Copy source of truth: `ux-flows.md` 9.4.** That table is the only place the discreet and public wording
is defined. This spec defines the kinds, the content limits and the string ids, and carries no illustrative wording.
The one exception is the BRIEFING discreet title, "Your AI briefing is ready", which reuses the existing
`notification_briefing_ready_title` (F24). The content limits apply to every discreet and public string: the copy may
name the **kind only** [OD-10]. It may not contain amounts, currency, merchant, product, category, severity, window or
dates, counts, or AI text.

(AMD-14, AMD-19) The public version [OD-21 a, decided at Gate 2] is the single generic pair `notif_public_title` /
`notif_public_text`, shared by all six kinds (AMD-17). It names no kind and carries none of the items above. Under
OD-21 (b) (rejected at Gate 2), the public version would equal the kind's discreet constants.

| Kind | FULL visible (unchanged today) | DISCREET title / text (string ids; wording in `ux-flows.md` 9.4) (AMD-19) | Public version (always) [OD-21 a] (AMD-14) | Expanded style | Content intent |
|---|---|---|---|---|---|
| BILL | "Bill due" / "{merchant} due: {amount} {ccy}" (`BillReminderWorker.kt:105, 185-193`) | `notif_discreet_bill_title` / `notif_discreet_bill_text` | generic pair, no actions | none (as today) | none (unchanged, F16) |
| ANOMALY | "Unusual Charge Detected" / "Unusual charge: {amount} at {merchant} ({reason}). Tap to review." (`AnomalyAlertOrchestrator.kt:193-198, 298-307`) | `notif_discreet_anomaly_title` / `notif_discreet_anomaly_text` | generic pair | FULL: BigText (as today). DISCREET: none. | explicit `MainActivity`, `activity?expenseId=` |
| BUDGET | severity title / spent, %, category, limit (`BudgetMonitor.kt:354-376, 462-472`) | `notif_discreet_budget_title` / `notif_discreet_budget_text` | generic pair | none | explicit `MainActivity`, `home`, CLEAR_TASK |
| WARRANTY | 7-day or 30-day title / product (+ merchant) (`WarrantyExpirationWorker.kt:107-131`) | `notif_discreet_warranty_title` / `notif_discreet_warranty_text` | generic pair | none | explicit `MainActivity`, `home`, CLEAR_TASK |
| RECEIPT_MATCH (AMD-17) | "Receipt Auto-Matched" with a leading check-mark emoji / "Receipt from {merchant} matched to transaction automatically" (`ReceiptMatchingWorker.kt:108-112`, F22) | `notif_discreet_receipt_match_title` / `notif_discreet_receipt_match_text`. Never the merchant. | generic pair | none (as today) | explicit `MainActivity`, `home`, CLEAR_TASK (the shared `mainActivityIntent()`, unchanged) |
| BRIEFING | "Your AI briefing is ready" / AI summary ≤180 chars (`DeliverProactiveBriefingNotificationUseCase.kt:43-49`) | "Your AI briefing is ready" = `notification_briefing_ready_title` (AMD-19, F24) / `notif_discreet_briefing_text` | generic pair | FULL: BigText (as today). DISCREET: none. | explicit `MainActivity`, `dashboard?briefingKey=`, CLEAR_TOP |

Rules that apply to every kind:
- Visibility is `PRIVATE`. `VISIBILITY_PUBLIC` is never set. Channels never set `lockscreenVisibility` to public.
- In DISCREET mode, every user-visible text field of the posted notification holds only discreet constants. That
  includes title, text, big text, sub-text, ticker and the corresponding notification extras.
- Discreet texts are **constants per kind**. The public text is a constant [OD-21] (AMD-14). Neither is ever derived
  from, truncated from, or redacted from the detailed text ("generated content-free").
- PendingIntent payloads (expenseId, briefing key) are not user-visible surfaces and stay as they are.
- (AMD-17) **Never reads as Budget alert.** `WARRANTY` and `RECEIPT_MATCH` share `budget_alerts` with `BUDGET`, but
  neither ever carries the BUDGET discreet constants (AE-5, AE-111). In DISCREET mode a `RECEIPT_MATCH` notification
  never contains the merchant, in any text field, text extra or its public version (INV-8).
- (AMD-19) The BRIEFING discreet title equals today's FULL title. `ux-flows.md` 9.4 also reuses
  `notification_briefing_ready_title` for the FULL title. The English text does not change, so this localizes an
  existing string and is not a composition change (4.1). How the domain use case obtains the resource follows TECH-2.

### 4.4 Actions (BILL only)

| API | Lock state | Snooze / Dismiss on the private version | Public version |
|---|---|---|---|
| 31+ | any | present, `authenticationRequired = true` (device keyguard, not the app lock) [OD-5: unconditional from 1a] | no actions |
| 30 | `appLockEnabled`, or settings `Unreadable` | **not posted** (`NOTIF_ACTIONS_SUPPRESSED_LOCK`) | no actions |
| 30 | lock off, settings readable | unchanged | no actions |
| 26–29 | lock can only be enabled via transferred settings (INERT, AE-54) → as the API 30 row | as the API 30 rows | no actions |

Action PendingIntents are otherwise unchanged: explicit broadcast, `FLAG_IMMUTABLE`, existing request codes. Snooze and
Dismiss workers and the receivers are untouched. (AMD-20) The action labels, hardcoded in English today (F25), become
string resources per `ux-flows.md` 9.4: a new `notif_action_snooze`, and the existing `action_dismiss` for Dismiss,
which needs a `values-el` entry. The labels carry no content. **Consequence:** API 30 users with the lock on cannot
snooze or dismiss bills, because no in-app equivalent exists (F16, risk R9).

### 4.5 Failure handling

- The settings read is bounded by `T_read`. On timeout, IO error, corruption or an invalid value → DISCREET, and the
  post proceeds.
- The policy never throws, except `CancellationException`. It never changes the post/no-post decision,
  `DeliveryResult`, claim/mark transitions (`markReminderSent`, `markSentFromClaimed`, `markFailed`),
  `ctx.addNotificationsSent()`, or cooldown writes (`updateExceededNotification` etc.).
- AGENTS.md notification rules are unchanged: check permission locally, catch `SecurityException`, count metrics only
  after a real post. `sendBudgetAlert` and `sendAnomalyAlert` do not catch `SecurityException` on `notify` today. That
  is pre-existing; see S6.

### 4.6 Already-posted notifications when discreet mode or the lock is switched on [OD-6]

Recommended (decided at Gate 2, OD-6 a): when `effectiveDiscreet` becomes true, active notifications of the six kinds
(AMD-17) are re-posted in place.
- Same id, channel, content intent and actions. The action policy is re-applied.
- The new content is the kind's discreet constants. Kind is identified by channel and id range (AMD-21, below).
- No lifecycle state changes. No detailed content is retained.

Turning discreet mode off never re-posts.

Re-post rules (AMD-7, closes G-7):
- **Trigger:** the sweep runs only after a committed write (INV-46) that turns `effectiveDiscreet` from false to true.
  That is discreet switched on, the lock enabled, or a review-save. A read failure is not a trigger. Read failures
  already give DISCREET for each new post (4.2), and detecting them for a sweep would need polling.
- **Silent:** the re-post alerts only once. It makes no sound, vibration or light and shows no heads-up. The original
  post time is kept where the platform exposes it, so shade order does not change.
- **Active only:** the sweep queries active notifications once and re-posts only those. A notification the user
  dismisses between the query and its re-post may reappear once in discreet form. The window is bounded by
  N x 300 ms (the re-post pacing, `SWEEP_REPOST_SPACING_MS`), about 15 s at 50 notifications. This is an accepted
  residual: it is bounded and content-free, though it may alert on a high-importance channel.
- **Further accepted residuals:** (i) a FULL decision resolved just before the setting commit but posted after the
  sweep's single query stays FULL (a millisecond window; `postForKind` and `BillReminderWorker`); (ii) process death
  mid-sweep leaves the remaining notifications FULL until they are replaced. G1b verification re-posts any sweep-kind
  notification whose presentation token is not "D" (an absent token counts), which covers both.
- **Not a delivery:** no `notificationsSent`, `DeliveryResult`, claim/mark or cooldown change.
- **Failure:** a missing permission or a `SecurityException` skips that notification and records
  `NOTIF_REPOST_FAILED` (exception class name only). There is no retry loop. `CancellationException` propagates.

Kind detection and rebuild (AMD-21, closes `impact-map.md` headline 4 and §9 item 6):
- The sweep reads each active notification's channel and id from the platform's active-notification list. It cannot
  start from source rows, because ids are range + hash and cannot be mapped back (F23).
- Classification (first matching row wins):

  | Channel | Id | Kind |
  |---|---|---|
  | `bill_reminders` | any (allocated 30000–39999) | `BILL` |
  | `anomaly_alerts` | any | `ANOMALY` |
  | `ai_briefings` | any | `BRIEFING` |
  | `budget_alerts` | 10000–19999 | `WARRANTY` |
  | `budget_alerts` | 20000–29999 | `RECEIPT_MATCH` |
  | `budget_alerts` | any other id (allocated 1–9999) | `BUDGET` |
  | any other channel, including the capture FGS channel | any | not one of the six kinds; never touched |

- **Rebuild, never copy:** the re-post is a new notification built from the policy decision for the detected kind.
  From the active notification it reuses only the id, channel, content intent, action PendingIntents (with the 4.4
  action policy re-applied) and post time. It never copies text, extras or style from it (for example through a
  builder recovered from the posted notification), because those hold the detailed content (INV-8).
- **Residual (F23):** `BudgetMonitor` posts with the raw budget id. A budget alert whose budget id is in 10000–29999
  falls in the warranty or receipt range and is re-posted with that kind's discreet constants. Ids of 30000 or more
  fall through to `BUDGET` and are classified correctly. The result is still
  content-free (INV-5); only the kind label is wrong. This is accepted until TECH-3 (13.1) is decided. AE-112 tests
  only ids inside their allocated ranges.

Kind marker and legacy notifications (AMD-22). Before this amendment, 4.6 did not say how a notification without a
marker is classified; only the TECH-3 option text did. This rule binds whichever TECH-3 option is chosen:
- Under TECH-3 (b) or (c) there is no marker, and the table above is the whole rule.
- Under TECH-3 (a), the marker decides the kind only when all three hold:
  - it is present;
  - it is a known kind;
  - it names a kind allowed on the notification's channel. Allowed kinds: on `budget_alerts`, `BUDGET`, `WARRANTY` and
    `RECEIPT_MATCH`; on `bill_reminders`, `BILL`; on `anomaly_alerts`, `ANOMALY`; on `ai_briefings`, `BRIEFING`.

  In every other case the channel and id-range table above applies. That covers a notification with no marker (posted
  before the upgrade, or by a path that did not stamp one), an unknown value, and a kind not allowed on the channel. A
  marker never brings a notification on another channel into the sweep.
- The marker carries the kind enum name only, with no id, amount or text. It is never a user-visible field or a text
  extra. It does not change the notification's identity (id and tag), so a later post or re-post still replaces a
  pre-upgrade notification with the same id.
- Residual: a pre-upgrade budget alert with a raw id in 10000–29999 has no marker. It can be mislabelled (F23) until it
  is replaced or dismissed. It stays content-free (INV-5). (AE-121)

### 4.7 Guards and legal paths touched

- FG-11 worker guard: edits to `BillReminderWorker`, `WarrantyExpirationWorker` and `ReceiptMatchingWorker` (AMD-17)
  must stay inside the `runGuardedWithContext` lambda.
- (AMD-17) LEGAL_PATHS Receipts: the auto-match link through `receiptLinkService` (receipt lifecycle) is unchanged.
  Only the presentation of the notification posted after it changes.
- FG-10 cancellation.
- FG-16 PII: sentinel tests on notification text. No raw values in logs.
- FG-07 ratchets: `BillReminderWorker` uses `android.util.Log`, so touching it may interact with PII/logging baselines.
- LEGAL_PATHS Workers, Anomaly Alerting (`NotificationService.send()` step), Warranty, and Recurring (the reminder
  claim/mark path through `RecurringLifecycleCoordinator` is unchanged).
- AGENTS.md Notification permission rules.

---

## 5. Deep links [1a] (locked behaviour [1b])

### 5.1 Boundary

Removing BROWSABLE stops browser-originated links. Removing the VIEW filter would stop implicit resolution. **Neither
stops an installed app from starting the exported `MainActivity` explicitly with any data URI (F9).** The policy
therefore treats every incoming URI as untrusted, internal notification taps included.

Considered and not chosen: an HMAC-signed internal token. The state-equality rule (INV-16) removes the only side
effect without new key material.

### 5.2 Pipeline

1. `onCreate` (when `savedInstanceState == null`) or `onNewIntent` receives an intent.
2. Only `ACTION_VIEW` with scheme `expensetracker` is a deep link. Any other action carrying a data URI is ignored
   (`DL_REJECTED_ACTION`). A launcher `MAIN` intent without data is not a link.
3. `DeepLinkPolicy` is pure: no IO and no side effects. It returns a `DeepLinkRequest` (possibly with parameters
   dropped) or a rejection that maps to `HOME` with a reason code.
4. The intent data is consumed one-shot, as today (`MainActivity.kt:117-124, 150-154`).
5. `LOCK_QUERY` to `AppLockController`: hold (T20) or apply (T21).
6. Apply after unlock or immediately:
   - Re-validate state-dependent rules.
   - Perform the only permitted side effect (INV-16) and any read-only lookup (`activity`).
   - Navigate through `MainViewModel`.
   - Emit bounded diagnostics.

### 5.3 Per-host rules

| Host | Allowed params | Validation | Permitted effect | Default on drop or failure |
|---|---|---|---|---|
| `home` | `briefingKey` (kept for compatibility) | as `dashboard` | as `dashboard` | Home |
| `dashboard` | `briefingKey` | exactly `dashboard_home:` + a real calendar date `yyyy-MM-dd` (ASCII, ≤32 chars) | Write `lastOpenedDashboardBriefingKey` **only if** the key equals the current `lastDeliveredDashboardBriefingKey` (read at apply time). Otherwise no write. | Home |
| `activity` | `expenseId` | 1–19 ASCII digits, value 1..`Long.MAX_VALUE`, no sign, single occurrence | read-only lookup → Transactions filtered to the expense's local day (existing derivation) | Transactions list |
| `review` | none | — | navigate | Review |
| `plan` | none | — | navigate | Budget |
| `add` | none | — | open an **empty** add sheet. Clipboard behaviour is unchanged and runs only in open UI states. | add sheet |
| `analytics` | `period` | case-insensitive ∈ {today, week, month, quarter, year, all}. One allowlist shared with `AnalyticsScreen` (F13), never duplicated. | navigate | Analytics default period |
| `map` | `location` | trimmed, 1–100 chars, no Unicode control or format characters; used for local highlight only (F12) | navigate | Map with no highlight |
| bank hosts | — | existing RP-17 rejection (`BankFeatureAvailability.isBankDeepLinkHost`) moved into the policy, not duplicated | none | Home |
| any other | — | — | none | Home |

Per-parameter rules:
- Unknown keys are dropped (`DL_PARAM_UNKNOWN_DROPPED`).
- Duplicate keys, or values that fail validation, cause that parameter to be dropped (`DL_PARAM_INVALID_DROPPED`). The
  host's default applies.
- Intent extras are always ignored.

### 5.4 Structural validation

The whole link is rejected, landing on Home with `DL_REJECTED_MALFORMED`, when any of these holds:
- the URI is longer than 2048 chars;
- it is not hierarchical;
- it has userinfo, a port, a fragment, or a path other than empty or "/";
- it contains non-ASCII in the host.

Malformed values must never crash navigation (FG-22 runtime proof).

### 5.5 Behaviour while locked [1b]

- The link is held: one slot, latest wins, memory only.
- No lookup, no write and no navigation happens while held. The lock surface does not describe the held link.
- After unlock the link is applied exactly once, re-validating INV-16 at that time.
- It is dropped on process death (brief) or after `T_held` [OD-7].
- While `RESOLVING`, the link is held until resolution and then applied or kept per T2–T6.
- In `DISABLED`, `UNLOCKED` (within timeout) and `INERT`, the link applies immediately.

### 5.6 Manifest and PendingIntents

- Remove `BROWSABLE` from the `expensetracker` filter. If the impact map finds a documented external **browser**
  caller for a host, that host moves to a separate BROWSABLE filter only by human decision, and only if it has zero
  side effects. Keeping or removing the VIEW filter is OD-3.
- The documents that mention the scheme (`docs/architecture/NAVIGATION_ARCHITECTURE.md`, `UI_REFERENCE_INDEX.md`,
  `COMPREHENSIVE_UI_MAP.md`, `CODEBASE_INVENTORY.md`, `pr-e-actual-release-verification-plan.md` §6.4) are internal and
  are not external callers. They need updating.
- Every app-created PendingIntent that targets an activity sets the component explicitly to `MainActivity` and stays
  `FLAG_IMMUTABLE`. The current implicit `ACTION_VIEW` sites are `AndroidNotificationService.kt:112, 147, 172`. The
  data URI is kept so that one parser handles all links. Flags are unchanged: CLEAR_TOP for anomaly and briefing,
  CLEAR_TASK for budget, warranty and receipt match (AMD-17; all three use the shared `mainActivityIntent()`, F22).

### 5.7 Deep-link reason codes (closed set)

| Code | When | Allowed metadata |
|---|---|---|
| `DL_APPLIED` | link applied | host enum, `afterUnlock` Boolean |
| `DL_HELD_LOCKED` | held while locked or resolving | host enum |
| `DL_HELD_REPLACED` | a newer link replaced a held one | host enums (old, new) |
| `DL_HELD_EXPIRED` | TTL elapsed [OD-7] | host enum |
| `DL_REJECTED_ACTION` | non-VIEW intent carrying data | — |
| `DL_REJECTED_MALFORMED` | 5.4 | — |
| `DL_REJECTED_UNKNOWN_HOST` | host not in the enum | — (the host string is **never** recorded) |
| `DL_REJECTED_BANK_HOST` | RP-17 (replaces the free-text log; keeps the `FEATURE_UNAVAILABLE` meaning) | `bankSyncAvailable` Boolean |
| `DL_PARAM_UNKNOWN_DROPPED` | unknown key | host enum, count |
| `DL_PARAM_INVALID_DROPPED` | invalid or duplicate value | host enum, parameter enum |
| `DL_BRIEFING_KEY_NOT_DELIVERED` | valid format, does not equal last delivered | — |
| `DL_BRIEFING_STATE_UNREADABLE` | engagement store unreadable → no write | — |
| `DL_TARGET_NOT_FOUND` | `expenseId` has no row | — |
| `DL_TARGET_READ_BLOCKED` | read barrier or exception → fallback | exception class name only |
| `DL_DIAGNOSTICS_SUPPRESSED` | the rate limiter dropped events | reason code, suppressed count |

The following are never recorded in any diagnostic, log, `AiRuntimeDiagnostics` entry or exception message: the raw
URI, host string, query string, any parameter value, briefing key or `expenseId`. The existing
`recordInteraction("phase4_open", …)` keeps only a constant message.

### 5.8 Dependency on release advisory C4

C4 (`docs/features/_discovery/opportunity-scan/release-suggestions.md`, section C+) covers the present-day raw
diagnostic and briefing suppression. The target state in this spec (INV-14…INV-22) holds whatever C4's status is.
- At impact-map time, re-check C4.
- If the release track shipped part of it (for example, dropping the raw value), Inc 1a adopts that code and delivers
  the rest.
- If C4 is untouched, Inc 1a delivers all of it.

---

## 6. App PIN [2]

### 6.1 Storage (requirements, not implementation)

- The PIN is never stored. Only a verifier is: a salted slow hash.
  - Salt: ≥16 bytes from a CSPRNG, unique per PIN.
  - KDF: a standard password-hashing function. Memory-hard is preferred. PBKDF2-HMAC-SHA256 is acceptable and
    consistent with F2.
  - Output: ≥32 bytes.
  - Cost: calibrated so one verification takes about 200 ms–1 s on the slowest supported API-30 device class.
  - Algorithm id and parameters are stored with the hash. Parameters are upgraded on the next successful verify.
- The verifier is additionally bound to a non-exportable Android Keystore key (hardware-backed where available). This
  key does not require user authentication and is not invalidated by screen-lock changes. The binding means a copied
  data directory cannot be brute-forced offline.
  - Context: 10^6 six-digit PINs are trivial to search offline without it.
- Comparison runs in constant time.
- PIN input is held as char or byte arrays and zeroed after use (best effort). It is never placed in a `String` log,
  Bundle, `SavedStateHandle`, diagnostic or crash report.
- Do not newly depend on `EncryptedSharedPreferences`/`security-crypto` (alpha, upstream deprecation to verify) without
  an explicit decision (R13).

### 6.2 Attempt limiting [OD-14]

- Recommended schedule:
  - failures 1–4: no delay;
  - failure 5: 30 s;
  - failure 6: 1 min;
  - failure 7: 5 min;
  - failure 8: 15 min;
  - failure 9 and every later failure: 60 min (cap).
- **No data wipe, ever.**
- State is persisted durably **before** the failure result is shown (T24). It survives app kill and restart.
- Remaining delay is measured with `elapsedRealtime`. If the boot count differs from `delayStartedBootCount` (a
  reboot happened), the current tier's full delay restarts. This is conservative and bounded at one tier. Wall-clock
  changes have no effect.
- An unreadable attempt state is treated as `consecutiveFailures = 4` with one 30 s delay before entry
  (`PIN_ATTEMPT_STATE_UNREADABLE`). This is fail-closed but bounded.
- A correct PIN resets the counter to 0 (durable first). A successful biometric unlock in PIN mode also resets it.
- (AMD-2, closes G-2) **One counter for every PIN surface.** Unlock, Change PIN, Remove PIN, disabling the lock,
  switching mode, enabling biometric-in-PIN-mode, any `CONFIRM_CHANGE` in PIN mode, and the review-save PIN entry all
  verify through the single `AppPinStore` verify. They share one counter and one delay. While a delay is active, every
  surface rejects entry without evaluating it (as T25). A correct PIN on any surface resets the counter. Without this,
  an unlocked session would be an unthrottled guessing surface for the current PIN.
- (AMD-11, closes G-11) **Repair of an unreadable attempt state.** The repaired record `{consecutiveFailures = 4,
  counterOrigin = REPAIRED_UNREADABLE, one 30 s delay}` is written durably before entry is allowed. If that write
  fails, each process start applies the same single 30 s delay. While `counterOrigin = REPAIRED_UNREADABLE`:
  - the exposed delay reason is `ATTEMPT_STATE_UNREADABLE`, not `FAILURES`;
  - no remaining-tries value is exposed, so the UI cannot claim failed attempts that may not have happened;
  - the next real failure sets `counterOrigin = FAILURES` (failure 5 → 30 s); the next success resets everything.

  `ux-flows.md` owns the wording.

### 6.3 Biometrics in PIN mode [OD-16]

- Off by default. Enabling it requires the current PIN.
- The prompt uses `BIOMETRIC_STRONG` only, **never** `DEVICE_CREDENTIAL` (a device credential would defeat the
  household purpose). It has a "Use PIN" negative button.
- Recommended: bind the prompt to a Keystore key created with user-authentication-required and
  invalidated-by-new-enrolment.
  - Reason: a partner who knows the device PIN can enrol their own fingerprint.
  - When a new enrolment invalidates the key, the biometric option turns off, the PIN is required, a banner is shown
    and `PIN_BIOMETRIC_INVALIDATED` is recorded.
  - (AMD-6) Turning biometric unlock off and setting `biometricInvalidatedNoticePending = true` happen in one
    `AppPinStore` write. The flag is cleared only when the user dismisses the notice or the PIN is removed (INV-51).
    Reason: a new enrolment may be the partner's, so the owner must reliably learn of it. A process-memory notice could
    be lost before it is seen, and afterwards the condition can no longer be detected.

### 6.4 Mode rules

- Mode switches:
  - Switching `DEVICE_AUTH` → `APP_PIN` requires the current device auth (if the lock is enabled), then PIN entry twice.
  - Switching `APP_PIN` → `DEVICE_AUTH`, or disabling the lock, requires the PIN.
  - Changing the PIN requires the current PIN.
- (AMD-12, closes G-12) **Setup preconditions** (INV-50): only from `UNLOCKED`, with the lock on, effective
  `DEVICE_AUTH`, API ≥ 30 and capability `AVAILABLE`. One device auth (`CONFIRM_CHANGE`) is required first. Setup is
  never offered in `DISABLED` or `INERT`. A device with no screen lock therefore cannot start in PIN mode. If the screen
  lock is removed later, PIN mode keeps working (AE-81).
- (AMD-4, closes G-4) **Leaving PIN mode** (INV-49): removing the PIN, switching to `DEVICE_AUTH`, and disabling the
  lock in PIN mode are one operation. It deletes the verifier set, then writes the settings. With capability ≠
  `AVAILABLE`, removal turns the lock off and the confirmation copy says so. No device-auth prompt is asked: the lock
  does not go from off to on, and the PIN has already proved the owner. A verifier exists only while PIN mode is the
  committed choice. That is what makes INV-41's "verifier present ⇒ PIN mode" inference sound.
- In `APP_PIN` mode the device credential never unlocks the app.
- PIN mode works when the device has no screen lock. The inert rule does not apply (2.3).
- Mode is determined fail-closed. If settings are `Unreadable` and a verifier record exists → PIN mode `LOCKED`.
- Verifier unreadable (corrupt, or Keystore key lost) [OD-15, recommended]:
  - The app stays `LOCKED` in PIN mode and cannot be unlocked. The screen explains recovery.
    `PIN_VERIFIER_UNREADABLE` is recorded.
  - There is no device-credential fallback. That fallback would let a device-PIN holder bypass the app PIN, including
    via D2D transfer to their own phone (OD-19).
- Notification actions in PIN mode are kept, with device-auth-required on 31+ [OD-17].
- Available on API 30+ only [OD-18].

### 6.5 Recovery and restore

- There is no in-app PIN reset. A forgotten PIN means clearing app data (or reinstalling). That deletes the DB,
  settings, the verifier and internal backups (F3).
- Data can then be recovered by restoring an **exported** `.costbackup` with its password (F2). The PIN setup screen
  must say this before the first PIN is saved.
- `.costbackup` never contains the PIN or lock settings (F1). Restore never sets or clears them. Consequences:
  - after an in-app restore, the same PIN is still required;
  - a backup taken on a PIN-protected device restores into an unprotected install without a PIN.
- Redacted backups (privacy mode) restore with reduced data. Recovery quality depends on the backup's mode.
- (AMD-9, closes G-9) PIN setup reads no backup state. It does not tell the user whether an exported backup exists,
  because that would need backup-history tracking (scope growth S8). A "Back up now" shortcut, if present, is
  navigation only, to the existing Backup & Restore destination. It never starts a backup itself, so the backup legal
  path is unchanged. The required warning text is the safeguard.

---

## 7. Invariants

Each invariant names one owner. A rule never lives in two layers.

**Settings and persistence**

- **INV-1 Device-local only.** Lock settings, discreet and secure-screen settings, the PIN verifier and attempt state
  live only in app-private device-local storage. They are never in Room and never in `.costbackup`. Restore, import and
  DB reset never read or write them. Owner: `AppLockSettingsRepository` (settings) and `AppPinStore` (PIN). [1a/1b/2]
  (AE-61, AE-78)
- **INV-2 Typed load state.** Every read returns `Loaded | FirstRunDefault | Unreadable(cause)`. An invalid field
  maps to its most restrictive value (2.1). Unreadable never maps to permissive defaults. Owner:
  `AppLockSettingsRepository`. [1a] (AE-7, AE-51)
- **INV-3 Durable before effect.** Enabling or disabling the lock, changing mode, and changing the PIN take effect only
  after a successful durable write. A failed write leaves the previous state. While the lock is enabled, any weakening
  change needs a fresh authentication [OD-8]. Weakening changes are: disabling the lock, a longer timeout, turning off
  an unforced discreet or secure-screen setting, and switching mode. Owner: `AppLockController`. [1b] (AE-36, AE-37,
  AE-70)
  (AMD-10, AMD-1, AMD-8) This invariant covers authentication and `AppLockState` effects. Commit semantics for every
  settings field are in INV-46. Review-save authorisation is in INV-45. Forced values are in INV-48.

**Notification content policy**

- **INV-4 Single presentation owner.** For the six kinds (AMD-17), `NotificationContentPolicy` alone decides visible text,
  expanded style, visibility, public version and action policy. The builder owners only apply the decision.
  Composition sites pass kind + detailed text. Owner: `NotificationContentPolicy`. [1a] (AE-1, AE-5)
- **INV-5 Content-free by construction.** Discreet texts are per-kind constants. The public text is a constant
  [OD-21] (AMD-14). Neither is ever derived from detailed content. Owner: `NotificationContentPolicy`. [1a]
  (AE-2…AE-6, AE-109, AE-111)
  (AMD-20) The constants are localized string resources (ids in `ux-flows.md` 9.4), resolved to text outside the
  pure domain layer. The mechanism is TECH-2 (4.1).
- **INV-6 Effective discreet.** The mode is DISCREET exactly when 2.3 `effectiveDiscreet` holds: the setting is on, OR
  the settings are unreadable or timed out, OR (1b) the lock is enabled [OD-2]. Owner: `NotificationContentPolicy`.
  [1a, lock term 1b] (AE-7, AE-65)
- **INV-7 Public version always.** (AMD-14) Every notification of the six kinds (AMD-17) is `VISIBILITY_PRIVATE` and
  carries a public version, in both modes, with no actions and no expanded style. [OD-21 a] The public version is the
  app-wide generic public constants: identical for all six kinds and naming no kind (brief v2: "generic public
  version"). Under OD-21 (b) (rejected at Gate 2) it would be the kind's discreet constants. Owner:
  `NotificationContentPolicy`. [1a] (AE-1, AE-14, AE-109, AE-111)
- **INV-8 No detail anywhere in DISCREET.** In DISCREET mode no text field or text extra of the posted notification
  contains detailed content. Owner: `NotificationContentPolicy` (decision), checked at both builder owners. [1a]
  (AE-2, AE-3, AE-6, AE-111)
- **INV-9 Posting never blocked or altered.** Policy resolution is bounded by `T_read`. Any failure gives DISCREET.
  It never throws except `CancellationException`, which propagates. It never changes post/no-post, `DeliveryResult`,
  claim/mark, cooldown writes or metrics. Owner: `NotificationContentPolicy`. [1a] (AE-7…AE-11)
- **INV-10 Action rules.** On API ≥31, bill actions have `authenticationRequired = true` [OD-5]. On API ≤30, when the
  lock is enabled or settings are unreadable, no actions are posted. On API ≤30 with the lock off and settings
  readable, actions are unchanged. Owner: `NotificationContentPolicy` (decision); `BillReminderWorker` applies it.
  [1a for 31+; 1b for ≤30] (AE-1, AE-12, AE-64)
- **INV-11 Workers own when and whether.** Posting decisions, cooldowns, dedupe, claims, quiet hours and permission
  gates are unchanged by this feature. Owner: the existing workers and coordinators. [1a] (AE-11)
- **INV-12 Kind registry.** Every app notification except the capture FGS notification declares a kind known to the
  policy. The FGS notification keeps its constant generic text. Owner: `NotificationContentPolicy` (proposed guard,
  section 8). [1a] (AE-13)
  (AMD-17) The registry is exactly the six kinds of 2.5, and each of the six composition sites (4.1) declares exactly
  one of them. `ReceiptMatchingWorker` declares `RECEIPT_MATCH`. (AE-13, AE-111)
- **INV-13 Already-posted notifications.** When `effectiveDiscreet` becomes true, active notifications of the six
  kinds (AMD-17) are re-posted in place with discreet constants. Ids, intents and lifecycle are unchanged [OD-6]. Owner:
  `NotificationContentPolicy` (decision); `AndroidNotificationService` executes. [1a/1b] (AE-15)
  (AMD-7) The re-post runs only after a committed write that turns `effectiveDiscreet` true. It is silent: alert
  once, no sound, vibration, light or heads-up, and the original post time is kept. It touches only notifications
  active at the sweep and is never a delivery (no metrics, claim/mark, cooldown or `DeliveryResult` change). Failures
  are skipped with `NOTIF_REPOST_FAILED` and never retried in a loop (4.6). (AE-102)
  (AMD-21) The kind is detected with the 4.6 channel and id-range table. The rebuilt notification reuses only the id,
  channel, content intent, action PendingIntents and post time, and copies no text, extras or style from the active
  notification. Notifications on any other channel are never touched. (AE-112)

**Deep links**

- **INV-14 Single pure parser.** Every `ACTION_VIEW` `expensetracker` intent, from `onCreate` and `onNewIntent`, is
  parsed only by `DeepLinkPolicy`, which extends `ui/navigation/DeepLinkParser.kt`. No inline parsing remains in
  `MainActivity`. Parsing does no IO. Non-VIEW intents are ignored. Owner: `DeepLinkPolicy`. [1a] (AE-31)
- **INV-15 Allowlist and degrade.** Per-host parameters follow 5.3. Unknown or invalid parameters are dropped to the
  host default. A malformed link goes to Home. No input crashes navigation. Owner: `DeepLinkPolicy`. [1a] (AE-19,
  AE-22, AE-23, AE-25, AE-26, AE-29, AE-30)
- **INV-16 No caller-controlled side effect.** The only persistent write reachable from a link is
  `lastOpenedDashboardBriefingKey`. It happens only when the validated key equals the persisted
  `lastDeliveredDashboardBriefingKey` at apply time. Otherwise, or if the state is unreadable, nothing is written.
  Owner: `MarkDashboardBriefingOpenedUseCase` (AI engagement domain). [1a] (AE-17…AE-20)
- **INV-17 No creation or prefill.** No link creates, edits, deletes or prefills financial data. Owner:
  `DeepLinkPolicy`. [1a] (AE-27)
- **INV-18 Bounded diagnostics.** Deep-link diagnostics and logs contain only 5.7 codes, the host and parameter enums,
  booleans and counts. They are rate-limited (`DIAG_RATE`). Owner: the deep-link diagnostics emitter (single, per
  OD-12). [1a] (AE-21, AE-29, AE-33)
- **INV-19 Explicit PendingIntents.** Every app PendingIntent that targets an activity names the component and is
  immutable. Owner: `AndroidNotificationService`. [1a] (AE-32)
- **INV-20 Not BROWSABLE.** No `expensetracker` host is BROWSABLE unless a human decision is recorded. Owner:
  application manifest (architecture-guardian). [1a] (AE-16)
- **INV-21 Bank hosts rejected.** The RP-17 rule is moved into the policy, not duplicated. Owner: `DeepLinkPolicy`
  (using `BankFeatureAvailability`). [1a] (AE-28)
- **INV-22 Lookup failures degrade.** For `activity`, not-found, barrier denial or an exception leads to fallback
  navigation plus a code. `CancellationException` propagates. Owner: the deep-link applier (MainActivity shell via
  `MainViewModel`). [1a] (AE-24)

**App lock**

- **INV-23 Availability.** The lock can be enabled only on API ≥30, with capability `AVAILABLE`, after one successful
  `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` authentication in the enabling flow. `BIOMETRIC_WEAK` is never requested.
  Owner: `AppLockController` (using `DeviceAuthCapability`). [1b] (AE-34…AE-36)
  (AMD-3) API level and capability are evaluated when the enable is requested, never from a value cached when the
  screen opened. A refused request (T26) launches no prompt, changes no state, and records one code per cause per
  process: `LOCK_UNAVAILABLE_API`, `LOCK_UNAVAILABLE_NOT_SECURE`, or `LOCK_UNAVAILABLE_CAPABILITY(HW_UNAVAILABLE |
  UNKNOWN)`. A retry re-evaluates. (AE-93)
- **INV-24 One process-level state.** Each process has exactly one `AppLockState`, starting at `RESOLVING`. New
  Activity instances (CLEAR_TASK, recreation) share it. While the lock is active, `UNLOCKED` is reachable only through
  an in-process auth success. Owner: `AppLockController`. [1b] (AE-38, AE-59)
- **INV-25 Lock triggers.** While the lock is active, every process start locks, including the post-restore restart.
  So does every foreground with elapsed ≥ timeout since the last background (monotonic clock). Configuration changes,
  focus loss, multi-window and the app's own prompt never start the timer. Owner: `AppLockController`. [1b] (AE-40…AE-43,
  AE-61)
  (AMD-22) The exemption covers the prompt being shown, not a real departure. For ENABLE and CONFIRM_CHANGE prompts,
  INV-54 decides what happens to a background recorded during the prompt: it is discarded after an unlock-equivalent
  success and handed to the timer after any other outcome. (AE-116, AE-117)
- **INV-26 Nothing financial while not open.** In `RESOLVING`, `LOCKED` and `AUTHENTICATING(UNLOCK)` (AMD-22), none of
  the following is composed, created or launched: `MainScreen` or any descendant (dialogs, sheets, popups, assistant),
  screen ViewModels, clipboard reads or listeners, permission or special-access prompts, or deep-link lookups. Owner:
  `AppLockGate`. [1b] (AE-38, AE-39)
  (AMD-22) `AUTHENTICATING(ENABLE)` and `AUTHENTICATING(CONFIRM_CHANGE)` start only from an open state and are rendered
  as that state, with `MainScreen` still composed (INV-53). This invariant does not cover them, because the content was
  already open to the person who started the prompt. (AE-113, AE-114)
- **INV-27 Precedence.** The app lock wraps the operational lock (3.6). No operational state bypasses or replaces the
  app lock. Owner: `AppLockGate`. [1b] (AE-61…AE-63)
- **INV-28 Independent of the DB.** Lock decisions never read Room and never depend on read or write barriers.
  Locking never cancels backup, restore or worker operations. Owner: `AppLockController`. [1b] (AE-63)
- **INV-29 Secure screen.** `FLAG_SECURE` is on whenever `effectiveSecureScreen` holds. It is set before the first
  frame. All dialog, sheet and popup windows inherit it (no `SecureOff`). Owner: `AppLockGate`. [1b] (AE-38, AE-66,
  AE-67)
- **INV-30 Held link.** While the app is not open, one validated link is held in memory (latest wins) with no side
  effects. It is applied exactly once after unlock, re-validating INV-16. It is dropped on process death [and after
  `T_held`, OD-7]. Owner: `AppLockController`. [1b] (AE-55…AE-57, AE-60)
- **INV-31 Order independence.** Link application always goes through `LOCK_QUERY`, which evaluates pending timeouts
  itself. Owner: `AppLockController`. [1b] (AE-58)
- **INV-32 Inert escape.** If the lock is enabled in `DEVICE_AUTH` mode and the device cannot authenticate
  (capability ≠ `AVAILABLE`, prompt error `NO_DEVICE_CREDENTIAL`, or API <30), the state is `INERT`:
  - the UI is open and a persistent banner plus a reason code are shown;
  - `appLockEnabled` is unchanged;
  - discreet notifications and secure screen stay forced;
  - the lock re-activates at the next lock trigger.

  Owner: `AppLockController`. [1b] (AE-48…AE-50, AE-54)
- **INV-33 Unreadable settings.** Unreadable settings lead to `LOCKED` if the app can authenticate (or a verifier
  exists [2]), otherwise to `INERT`. (AMD-5) For the persistent causes (`CORRUPTED`, `INVALID_VALUE`) this repeats on
  every cold start until a review-save succeeds (INV-44, INV-45). The transient causes (`IO_ERROR`, `TIMEOUT`) last
  only until a successful read (INV-47). Settings are never silently rewritten to "off". Only a `RESOLVE_TIMEOUT`
  fallback may be superseded by a late successful read while `LOCKED` (T6b). Owner: `AppLockController`. [1b]
  (AE-51…AE-53, AE-97)
- **INV-34 Auth outcomes.** Only a matching `AUTH_SUCCEEDED` unlocks. Cancel, error, lockout and stale callbacks leave
  the app locked. At most one prompt is in flight. The prompt is launched only by explicit user action. Owner:
  `AppLockController`. [1b] (AE-44…AE-47)
  (AMD-22) Only a matching `UNLOCK` success unlocks. For ENABLE and CONFIRM_CHANGE, cancel, error, lockout, host loss
  and stale callbacks return to the open origin with nothing written (T34, T35, INV-54). (AE-118)
- **INV-35 Lock surface content-free.** The lock surface shows no amounts, counts, badges, notification-derived text
  or held-link description. It supports TalkBack and font scaling. Owner: `AppLockGate`. [1b] (AE-69)
- **INV-36 Background work unaffected.** The capture service, workers, receivers and action workers never consult or
  change lock state. Owner: `AppLockController` (its API is not exposed to them; enforced by test). [1b] (AE-68)
- **INV-37 Bounded lock diagnostics.** Lock diagnostics use only section 10 codes. They are best effort and never
  block or delay unlock or resolution. Owner: `AppLockController`. [1b] (AE-38, AE-44)

**App PIN**

- **INV-38 PIN secrecy.** No plaintext PIN is ever stored, logged, put in diagnostics or saved state. The verifier
  meets 6.1 and is Keystore-bound. Owner: `AppPinStore`. [2] (AE-71)
- **INV-39 Persisted attempt limiting.** The counter and delay are written durably before a result is shown. Delays
  escalate per 6.2 and are monotonic and boot-aware. There is never a data wipe. Owner: `AppPinStore` (state); the
  controller enforces it. [2] (AE-73…AE-75, AE-84)
  (AMD-2) Every PIN verification surface (6.2) shares this one counter and delay. While a delay is active, every
  surface rejects without evaluating. A correct PIN on any surface resets the counter. (AMD-11) A repaired
  unreadable state is tagged `REPAIRED_UNREADABLE`. It exposes the delay reason `ATTEMPT_STATE_UNREADABLE` and no
  remaining-tries value until a real outcome. (AE-91, AE-92, AE-106)
- **INV-40 PIN mode excludes the device credential.** In `APP_PIN` mode the device credential never unlocks. Biometric
  unlock only if enabled with the PIN, `BIOMETRIC_STRONG` only, and invalidated by new enrolment [OD-16]. PIN mode does
  not depend on device security. Owner: `AppLockController`. [2] (AE-72, AE-76, AE-81)
- **INV-41 Fail-closed mode.** A verifier record implies PIN mode even if settings are unreadable. An unreadable
  verifier leaves the app in PIN `LOCKED`, with no device-credential fallback [OD-15]. Owner: `AppLockController`. [2]
  (AE-79, AE-80)
  (AMD-4) Only a verifier record that is present (readable or not) implies PIN mode. An absent record never does;
  without one, device-auth rules apply (2.3 `effectiveLockMode`, INV-49).
- **INV-42 Recovery.** There is no in-app reset. Changing or removing the PIN requires the current PIN. A forgotten PIN
  means clearing data. Backup and restore never carry or alter the PIN. Owner: `AppPinStore`. [2] (AE-77, AE-78, AE-82)
- **INV-43 API gate.** App PIN is offered only on API ≥30 [OD-18]. Owner: `AppLockController`. [2] (AE-83)

**Amendment invariants (stage 3b)**

- **INV-44 Writes while unreadable.** (AMD-1, G-1) While the load state is `Unreadable` (any cause), every per-field
  write is rejected (`Rejected(UNREADABLE)`) and writes nothing.
  - For the persistent causes (`CORRUPTED`, `INVALID_VALUE`), the only accepted write is the review-save. It is one
    atomic write that replaces the whole store with a complete record: every 2.1 field explicit, marker `NORMAL`.
  - Only a successful review-save clears the sentinel. A failed one leaves the store and the sentinel untouched.
  - The transient causes (`IO_ERROR`, `TIMEOUT`) accept no write. They recover by re-read (INV-47).
  - In a 1a-only build the record carries the 1a fields only.

  Owner: `AppLockSettingsRepository`. [1a] (AE-85, AE-88, AE-89)
- **INV-45 Review-save authorisation.** (AMD-1, G-1)
  - A review-save is **weakening** if any effective value its record would produce (2.3) is weaker than `F`: lock
    off, a timeout longer than `IMMEDIATE`, discreet off, or secure screen off. Values hidden by forcing do not count.
    For example, discreet off with the lock on is not weaker.
  - A weakening review-save needs one fresh authentication when the app can authenticate now. That is the device
    prompt, or PIN entry in effective `APP_PIN` mode under INV-39. It needs none in `INERT`, where nothing can
    authenticate (as T19) [OD-8; under OD-8 (b) no review-save needs auth].
  - A record with `appLockEnabled = true` is accepted only if `lockActive` would hold for it. That means capability
    `AVAILABLE` (INV-23), or a verifier present. Otherwise it is rejected (`Rejected(CAPABILITY)`) and nothing is
    written. In `INERT` the lock choice is therefore off-only.
  - The review cannot choose `lockMode`, because mode follows the verifier (2.3). It writes `biometricInPinMode =
    false`; re-enabling goes through 6.3. A lock-off record with a verifier present runs INV-49's ordered removal
    first.
  - Outcomes follow T27.
  - In a 1a-only build there is no lock, so no auth applies.

  Owner: `AppLockController`. [1b] (AE-85…AE-87)
- **INV-46 Committed state only.** (AMD-10, G-10) Every settings write, meaning each 2.1 field and the review-save,
  returns success only after a durable commit. Otherwise it returns a typed failure. Effective values (2.3),
  notification decisions, `FLAG_SECURE`, the timer and the re-post sweep (INV-13) observe committed state only. There
  is no optimistic value. A failed write leaves the previous committed value in force and records
  `LOCK_SETTING_PERSIST_FAILED(field)`. Owner: `AppLockSettingsRepository`. [1a] (AE-89, AE-105)
- **INV-47 Transient unreadable recovers by re-read.** (AMD-5, G-5) `IO_ERROR` and `TIMEOUT` are not sticky.
  - A re-read happens at every cold start, on the settings section's retry, and at each `APP_FOREGROUNDED` while the
    load state is transient-unreadable.
  - A successful re-read while the app is open applies the stored values at once, without auth (T28).
  - A re-read never moves `LOCKED` or `AUTHENTICATING` to an open state (T29). Among settings reads, only T6b may.
    The capability escapes T7b/T11 are unchanged.
  - The foreground lock evaluation never waits for the re-read. It uses the state held at that moment, which is `F`
    while unreadable.
  - Transient causes produce no review notice.

  Owner: `AppLockController`; the load state comes from `AppLockSettingsRepository`. [1b; in 1a the notification
  policy already reads per post] (AE-97, AE-98)
- **INV-48 Forced values are read-only.** (AMD-8, G-8) While a value is forced, writes to its stored value are
  rejected (`Rejected(FORCED)`). Nothing is written and no auth is asked.
  - Forced means: `secureScreen` while `appLockEnabled`, and `discreetNotifications` while `appLockEnabled` [OD-2 a;
    under OD-2 (b) only `secureScreen` is forced]. This includes `INERT` and API-unsupported states.
  - The stored value is kept and applies again when forcing ends (T18, T19, T30).
  - Exception: the review-save writes the full record (INV-44).

  Owner: `AppLockController`. [1b] (AE-103)
- **INV-49 Leaving PIN mode removes the PIN.** (AMD-4, G-4) Removing the PIN, switching to `DEVICE_AUTH`, and
  disabling the lock in effective `APP_PIN` mode are one ordered operation. It runs after a correct current PIN
  (INV-39):
  1. Delete the verifier, the attempt state, the biometric key and the notice flag.
  2. Write the settings.

  Rules:
  - A removal with capability `AVAILABLE` writes `lockMode = DEVICE_AUTH` with the lock still enabled. No device
    prompt is needed.
  - Otherwise, and always for a disable, step 2 writes `appLockEnabled = false`. The confirmation copy must say that
    the lock turns off.
  - A removal never leaves a `DEVICE_AUTH` lock enabled on a device that cannot authenticate, because INV-23 forbids
    enabling one.
  - A crash or write failure between steps 1 and 2 leaves an enabled `APP_PIN` setting with no verifier. That is
    evaluated as `DEVICE_AUTH` (2.3): `LOCKED` with the device prompt, or `INERT` with T19 available. The state is
    never unescapable, and no PIN pad is shown without a verifier.
  - If OD-11 puts both records in one store, steps 1 and 2 are a single atomic write.

  Owner: `AppLockController`. `AppPinStore` deletes; `AppLockSettingsRepository` writes. [2] (AE-94…AE-96, AE-110)
- **INV-50 PIN setup preconditions.** (AMD-12, G-12) An app PIN can be set up only:
  - from `UNLOCKED`, with the lock enabled, effective `DEVICE_AUTH`, API ≥ 30 and capability `AVAILABLE`;
  - after one fresh `AUTHENTICATING(CONFIRM_CHANGE)` success in the setup flow.

  It is not offered in `DISABLED`, in any `INERT` state, or below API 30. Reasons:
  - brief v2 makes app lock require a secure device;
  - in `INERT`, anyone holding the open phone could set a PIN and lock the owner out of their own data, and the only
    recovery would be clear data.

  Commit order: `lockMode = APP_PIN` first, then the verifier. An interruption before the verifier write leaves
  effective `DEVICE_AUTH`. After setup, PIN mode no longer depends on device capability (AE-81). Owner:
  `AppLockController`. [2] (AE-107)
- **INV-51 One lock notice, by priority.** (AMD-6, G-6) The controller derives at most one `LockNotice`, and only for
  open states (`DISABLED`, `UNLOCKED`, `INERT`). It never derives one for `RESOLVING`, `LOCKED` or
  `AUTHENTICATING(UNLOCK)` (AMD-22).
  - (AMD-22) During `AUTHENTICATING(ENABLE | CONFIRM_CHANGE)` it derives the origin state's notice, unchanged, so the
    slot neither disappears nor jumps while the prompt is open (INV-53, AE-115).
  - Priority: `INERT(reason)` > `REVIEW_SETTINGS` (persistent unreadable, until a review-save) >
    `BIOMETRIC_INVALIDATED`. A lower-priority notice waits; it is not dropped.
  - `INERT` and `REVIEW_SETTINGS` cannot be dismissed. They end only when their condition ends.
  - `BIOMETRIC_INVALIDATED` can be dismissed. It is backed by `biometricInvalidatedNoticePending` (2.7, 6.3), so
    process death cannot lose it.
  - Notices follow the INV-35 content limits.
  - Transient unreadable causes produce no notice.
  - Where the notice is drawn (every screen, or Home and settings) is a UX choice (UQ-2).
  - A 1a-only build has no gate. The settings card is its only surface.

  Owner: `AppLockController` (derivation); `AppLockGate` renders. [1b/2] (AE-99…AE-101)
- **INV-52 Notices never bypass the operational lock.** (AMD-13, G-13) While `AppOperationalState` is
  `RestoreInProgress`, `RestartRequiredAfterRestore` or `CriticalRecoveryRequired`:
  - a notice may be shown, but it offers no in-app navigation action;
  - it enqueues no navigation request. The `MainViewModel` channel is buffered (F8), so a queued request would apply
    after the state clears;
  - system-intent actions (for example, security settings) stay available.

  In-app actions return when the state is `Normal` or `BackupExporting`. Owner: `AppLockGate`. [1b] (AE-108)

**Amendment invariants (plan stage, AMD-22)**

- **INV-53 Open-origin prompts keep the open composition.** (AMD-22) An open-origin prompt is `AUTHENTICATING(ENABLE)`,
  which starts only from `DISABLED`, or `AUTHENTICATING(CONFIRM_CHANGE)`, which starts only from `UNLOCKED`. While one is
  in flight:
  - The gate renders the origin state unchanged. `MainScreen` stays composed and is neither disposed nor rebuilt when
    the prompt opens or closes. Composition-scoped coroutines are not cancelled, `remember` state is kept, and
    `LaunchedEffect(Unit)` blocks do not re-run (F7, R2). OD-9a's saveable-state preservation is not needed.
  - The lock surface is not composed. The notice slot shows the origin's notice (INV-51). Operational precedence is the
    origin's row in 3.6.
  - The gate starts nothing because of the prompt: no clipboard read, no permission or special-access prompt, no
    navigation and no deep-link lookup. Links are held (T20).
  - `FLAG_SECURE` follows committed state only (INV-29, INV-46), never `AppLockState`:
    - it stays set for the whole of a `CONFIRM_CHANGE` while the lock is enabled or the settings are unreadable;
    - for `ENABLE` it follows the stored `secureScreen` until the enable write commits, and is set from the next frame
      after that;
    - a weakening change clears it only after its write commits. The prompt itself never clears it.
  - The system prompt shows only constant, resource-backed text: a title naming the action, and at most a constant
    subtitle naming the method (`ux-flows.md` 9.2). It has no description. It never carries amounts, merchants, counts,
    a destination or a held-link hint (the INV-35 limits).

  Privacy reasoning:
  - The origin already showed this content to the person who started the prompt, so keeping it composed exposes
    nothing new. `ENABLE` starts from an unprotected `DISABLED` app. `CONFIRM_CHANGE` starts from an authenticated
    `UNLOCKED` session.
  - Under forced `FLAG_SECURE` the app window under the system prompt stays secure for recents, screenshots and
    casting.
  - Accessibility readers are the stated gap (R15) in every open state.
  - Tearing the app down instead would rebuild it and replay one-shot effects on every enable or weakening prompt.

  Owner: `AppLockGate`. [1b] (AE-113…AE-115)
- **INV-54 Open-origin prompt outcomes and the timer.** (AMD-22)
  - **Purpose-bound attempts.** An attempt id is bound to its purpose. An `ENABLE` or `CONFIRM_CHANGE` success applies
    only its own change (T33) and never unlocks `LOCKED`. An `UNLOCK` success never applies a settings change. Every
    non-success outcome, including host loss (T35), returns to the origin with nothing written (T34). At most one prompt
    is in flight across all purposes (INV-34).
  - **No time while shown.** No time accrues while the prompt is shown in the foreground (3.4). The first
    `APP_BACKGROUNDED` during the attempt is recorded in `promptBackgroundAt`. No lock evaluation happens while the
    prompt is in flight (T32).
  - **Unlock-equivalent success discards it.** After the success of an unlock-equivalent prompt, the recorded background
    is discarded, so the app's own prompt never re-locks or loops (AE-42). Unlock-equivalent means the prompt used the
    UNLOCK authenticators for the current effective mode, which is `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` in effective
    `DEVICE_AUTH`. Every ENABLE prompt and every `DEVICE_AUTH` CONFIRM_CHANGE prompt qualifies. Its success is a fresh
    authentication at least as strong as an unlock (T8).
  - [2] A system prompt shown in effective `APP_PIN` mode never qualifies. An example is a prompt that binds the
    fingerprint key (6.3), if the design uses one. Neither the device credential nor a newly enrolled finger is the PIN
    (INV-40). After its success the change applies and the background goes to the timer, as below.
  - **Any other outcome hands it to the timer.** With origin `UNLOCKED`, `lastBackgroundedAt := promptBackgroundAt`.
    T14/T15/T16 then decide at once if the app is in the foreground, otherwise at the next foreground or `LOCK_QUERY`.
    Measuring from the first background can only over-count time away, which is fail-closed. With origin `DISABLED` there
    is no timer, and the value is dropped.
  - [2] **PIN steps are not prompts.** Confirmations by PIN in effective `APP_PIN` mode (6.2, INV-39, INV-45, INV-49)
    are in-app steps inside `UNLOCKED`, not `AUTHENTICATING` states, because they show no system UI. 6.2's "any
    `CONFIRM_CHANGE` in PIN mode" means such a step. A background during a PIN step is an ordinary T13 background, and
    T14 may re-lock. The step's input is never in saved state (6.1), so after unlock the step starts empty and nothing
    has changed.

  Owner: `AppLockController`. [1b/2] (AE-116…AE-120)

---

## 8. Ownership and proposed architecture-doc changes

### 8.1 Owners

| Proposed owner | Segment | Owns | Inc |
|---|---|---|---|
| `AppLockSettingsRepository` | 28 (proposed) | settings and load state (2.1–2.2); commit semantics (INV-46); writes while unreadable and the review-save write (INV-44) (AMD-16) | 1a/1b/2 |
| `NotificationContentPolicy` | 11 | presentation decision (4) | 1a |
| `AndroidNotificationService`, `BillReminderWorker` | 11, 12/36 | apply the decision; explicit PendingIntents | 1a |
| `DeepLinkPolicy` (extends `ui/navigation/DeepLinkParser.kt`) | navigation | parsing and validation (5.2–5.4) | 1a |
| `MarkDashboardBriefingOpenedUseCase` | 20 (AI) | the briefing-opened rule (INV-16) | 1a |
| `DeviceAuthCapability` | 28 | API level + `canAuthenticate` mapping | 1b |
| `AppLockController` | 28 | state machine, timer, held link, enable/disable/mode operations; review-save authorisation, forced values, re-read recovery, PIN setup/removal ordering, `LockNotice` derivation (INV-45, INV-47…INV-51) (AMD-16); open-origin prompt outcomes, prompt-background handoff and host loss (INV-54, T32–T35) (AMD-22) | 1b/2 |
| `AppLockGate` | UI shell (`MainActivity`) | composition gate, `FLAG_SECURE`, prompt launch, notice slot (renders `LockNotice`; INV-52 action rule); origin rendering during open-origin prompts and constant prompt text (INV-53) (AMD-22) | 1b |
| `AppPinStore` | 28 | verifier, attempt state (incl. `counterOrigin`), biometric key, notice flag | 2 |
| Diagnostics emitter | per OD-12 | bounded codes, rate limit | 1a/1b/2 |

The `DeepLinkParser.RequireConfirmation` variant has no production use (F10). The lock gating replaces its intent. The
impact map decides whether to remove it. Its test expectations will change, and tests must be modified, not deleted,
without approval (R11).

### 8.2 Proposed additions to `LEGAL_PATHS.md` (proposals only; this spec does not edit the doc)

```
NOTIFICATION PRESENTATION:
  → Worker/coordinator decides whether/when (unchanged)
  → passes NotificationKind + detailed text to a builder owner
    (AndroidNotificationService | BillReminderWorker)
  → NotificationContentPolicy.decide(kind, settingsLoadState, sdkInt) → apply decision
FORBIDDEN:
  ❌ NotificationCompat.Builder outside the two builder owners and the capture FGS
  ❌ Visible/public text that is not the policy decision; VISIBILITY_PUBLIC
  ❌ Discreet/public text derived from detailed content

DEEP LINKS:
  → ACTION_VIEW expensetracker intent → DeepLinkPolicy (pure) → AppLockController LOCK_QUERY
  → hold (locked) | apply: navigation via MainViewModel; briefing-opened via
    MarkDashboardBriefingOpenedUseCase (key == last delivered only)
FORBIDDEN:
  ❌ Raw URI/host/parameter values in logs, diagnostics, exceptions
  ❌ Side effects or DB lookups before unlock
  ❌ Implicit ACTION_VIEW PendingIntents to activities

APP LOCK:
  → AppLockController is the only writer of lock state and lock settings
  → AppLockGate is the only caller of MainScreen
  → AppLockSettingsRepository is the only writer of settings storage; while Unreadable only the
    full-record review-save is accepted (INV-44)                                   (AMD-16)
FORBIDDEN:
  ❌ Workers/receivers/services reading or writing lock state
  ❌ Lock decisions reading Room
  ❌ A PIN verifier existing without APP_PIN committed (INV-49/INV-50 orderings)   (AMD-16)
```

### 8.3 Proposed guards

1. `NotificationCompat.Builder` allowed only in the two owners plus `NotificationCaptureService`.
2. Activity PendingIntents must set a component. No `Uri.parse("expensetracker:` inside an implicit intent.
3. Extend the FG-16 PII sentinel tests to deep-link values and notification detailed text.
4. `MainScreen(` is invoked only from `AppLockGate`.
5. No `VISIBILITY_PUBLIC` and no `SecureFlagPolicy.SecureOff`.

### 8.4 `CODEBASE_SEGMENTS.md`

- Segment 28 gains the lock and PIN owners.
- Segment 11 gains the policy.
- Navigation docs gain the deep-link policy.

---

## 9. Edge cases

- **Money, mixed currencies, FX, rounding, unavailable vs zero:** not applicable. This feature does no money math.
  FULL-mode text keeps the callers' existing formatting, including the BudgetMonitor partial-data qualifier. DISCREET
  text carries no amounts, so it never shows "0" in place of "unavailable".
- **Time zones and DST:** the timer and PIN delays use the monotonic clock. Briefing-key validation checks format and
  calendar validity and then string equality, with no TZ arithmetic. The `activity` day derivation is unchanged (system
  zone).
- **Month-end and recurrence:** not applicable. Reminder scheduling is unchanged.
- **Edits or deletes of past items:** a link to a deleted expense → `DL_TARGET_NOT_FOUND` → Transactions. A bill
  action on a delivery the user paid in the meantime → existing action-worker semantics.
- **Partial or failed receipt processing:** not applicable. Capture and receipt work continue while locked. (AMD-17)
  The receipt auto-match notification is still posted only after a successful link (F22). A failed or unmatched
  receipt posts nothing, as today. Only the presentation of the posted notification follows 4.3 `RECEIPT_MATCH`.
- **Duplicates and idempotency:**
  - Repeated identical links each navigate (idempotent).
  - Duplicate auth callbacks are guarded by `attemptId`.
  - The held link is applied once.
  - Re-posting uses the same id (idempotent).
  - Enabling twice is a no-op.
  - A WorkManager retry re-evaluates the policy. Content may legitimately change if the setting changed between
    attempts.
- **Empty and first-run states:** `FirstRunDefault` means everything off and no lock. No held link, no active
  notifications, no PIN.
- **App update or reboot:** process restart = cold start → lock. Added keys use defaults per key.
- **Screen recording or casting already running when the lock is enabled:** `FLAG_SECURE` applies from the next frame.
- **Third-party notification listeners and watch companions:** in DISCREET mode they read only constants.
- **Capability lost between check and prompt:** prompt error → T11 → `INERT`.
- **Device credential changed but not removed (1b):** no effect.
- **Accessibility services:** stated gap (brief). The API 34 sensitive-data flag is deferred.
- **Multiple Android users or work profile:** settings are per user (per app-data directory).

---

## 10. Persistence impact and privacy

**Persistence.**
- Room: none. Schema version unchanged. No migration, no backfill, nothing destructive.
- New device-local preference store(s) (additive). OD-11 decides between a dedicated store and extending
  `privacy_settings`.
- New controlled diagnostic codes, if added to `DiagnosticReasonCode` (OD-12), are stored as TEXT names
  (`RoomDiagnosticEventWriter`, `reasonCode?.name`), so there is no schema change. The impact map should confirm that no
  guard pins the enum list.
- `room-migration-guardian` is not required unless the impact map changes this.
- (AMD-6, AMD-11) The Inc 2 PIN store gains `counterOrigin` and `biometricInvalidatedNoticePending`. Both are
  device-local, additive and default-safe: an absent `counterOrigin` reads as `FAILURES`, and an absent flag reads as
  false. Neither is in Room or `.costbackup`.

**Privacy.**
- Stored: booleans, enum tokens, the PIN verifier (salted slow hash, Keystore-bound), attempt counters, boot count.
- Never stored: the PIN, the held link, raw deep-link values, notification detailed text (beyond what workers already
  hold).
- Exported: nothing new. Not in `.costbackup`, CSV or other exports.
- Network: none.
- Permissions: `USE_BIOMETRIC` (normal, merged by `androidx.biometric`). Strip `USE_FINGERPRINT` if merged (verify).
  (AMD-18) Whether the library or the app manifest declares it depends on TECH-1 (13.1).
  This affects the FG-22 exact permission policy and the release-evidence permission list
  (`scripts/ci/release_artifact_evidence.py`).
- Diagnostics: bounded codes only. Allowed metadata: reason code, host/parameter/cause enums, booleans, counts, SDK int,
  exception class name. Durable emission is best effort and rate-limited.

**Reason codes: lock, notification, PIN (deep-link codes are in 5.7).**

| Group | Codes |
|---|---|
| Lock | `LOCK_SHOWN_COLD_START`, `LOCK_SHOWN_TIMEOUT`, `LOCK_SHOWN_SETTINGS_UNREADABLE`, `LOCK_UNLOCKED`, `LOCK_AUTH_CANCELLED`, `LOCK_AUTH_LOCKOUT`, `LOCK_AUTH_ERROR` (bucket: `HW_UNAVAILABLE` / `TIMEOUT` / `SYSTEM_CANCELED` / `OTHER`), `LOCK_UNAVAILABLE_API`, `LOCK_UNAVAILABLE_NOT_SECURE`, `LOCK_INERT_NO_CREDENTIAL`, `LOCK_INERT_HW_UNAVAILABLE`, `LOCK_INERT_UNKNOWN`, `LOCK_INERT_API_UNSUPPORTED`, `LOCK_INERT_SETTINGS_UNREADABLE`, `LOCK_ENABLED`, `LOCK_DISABLED`, `LOCK_SETTING_PERSIST_FAILED` (field enum: `APP_LOCK_ENABLED` / `AUTO_LOCK_TIMEOUT` / `DISCREET` / `SECURE_SCREEN` / `LOCK_MODE` / `BIOMETRIC_IN_PIN_MODE` / `REVIEW_SAVE`; AMD-10), `LOCK_SETTINGS_UNREADABLE` (cause enum), `LOCK_UNAVAILABLE_CAPABILITY` (bucket: `HW_UNAVAILABLE` / `UNKNOWN`; AMD-3), `LOCK_SETTINGS_REVIEW_SAVED` (AMD-1), `LOCK_SETTINGS_RECOVERED` (cause: `IO_ERROR` / `TIMEOUT`; AMD-5) |
| Notification | `NOTIF_POLICY_SETTINGS_UNREADABLE` (cause), `NOTIF_POLICY_FALLBACK_EXCEPTION` (class name), `NOTIF_ACTIONS_SUPPRESSED_LOCK`, `NOTIF_REPOSTED_DISCREET` (count) [OD-6], `NOTIF_REPOST_FAILED` (class name; AMD-7), `NOTIF_REPOST_UNCONFIRMED` (count only, no content; G1b-0) |
| PIN [2] | `PIN_SET`, `PIN_CHANGED`, `PIN_REMOVED`, `PIN_UNLOCKED`, `PIN_FAILED` (bucket 1–4 / 5–8 / 9+), `PIN_DELAY_ENFORCED` (tier index), `PIN_ATTEMPT_STATE_UNREADABLE`, `PIN_VERIFIER_UNREADABLE`, `PIN_BIOMETRIC_ENABLED`, `PIN_BIOMETRIC_DISABLED`, `PIN_BIOMETRIC_INVALIDATED` |

Lock and inert codes are emitted once per process per cause, not per frame.

(AMD-17) No notification code carries a kind, so `RECEIPT_MATCH` adds no code. The worker's existing
`RECEIPT_MATCH_NOTIFICATION_SUPPRESSED_NOT_DELIVERED` record is unchanged, because the policy never changes
`DeliveryResult` (INV-9).

---

## 11. Parameters for impact-map confirmation (not owner decisions)

| Parameter | Recommended |
|---|---|
| `T_resolve` (cold-start settings resolution bound) | 2 s |
| `T_read` (settings read bound at post time) | 500 ms |
| Max URI length / `location` length / `briefingKey` length | 2048 / 100 / 32 chars |
| `T_held` [OD-7] | 5 min (monotonic) |
| `DIAG_RATE` | ≤1 durable event per reason code per 60 s per process, plus `DL_DIAGNOSTICS_SUPPRESSED` with a count |
| KDF cost [2] | 200 ms–1 s per verify on the slowest supported API-30 device; salt ≥16 B; output ≥32 B |

---

## 12. Acceptance examples

All data is synthetic. Defaults: API 34, notifications permitted, settings `Loaded`, lock off, discreet off, unless
stated. "No detail" means none of the sentinel strings appear in any title, text, big-text or sub-text field or text
extra of the posted notification or its public version.

### 1a — Notification policy

**AE-1 Bill, FULL (INV-4, INV-7, INV-10)**
- Given a due reminder for "Demo Power Co", 84.20 EUR.
- When `BillReminderWorker` posts.
- Then the visible text is "Demo Power Co due: 84.20 EUR" (as today). Visibility is `PRIVATE`. (AMD-14) The public
  version is the generic public constants [OD-21 a, decided; BILL discreet constants under the rejected option (b)]
  with no actions. Snooze and Dismiss have `authenticationRequired = true`.

**AE-2 Bill, DISCREET (INV-5, INV-8)**
- Given AE-1 with discreet on.
- Then the title and text are the BILL discreet constants, with no detail ("Demo Power Co", "84.20", "EUR").

**AE-3 Anomaly, DISCREET + explicit intent (INV-5, INV-8, INV-19)**
- Given an anomaly for expense 77, "€412.00 at Demo Electronics (unusually high amount)", with discreet on.
- Then the ANOMALY discreet constants are shown, with no BigText containing detail.
- The content intent's component is `MainActivity`. Its data is `expensetracker://activity?expenseId=77`, with
  `FLAG_IMMUTABLE` and CLEAR_TOP.

**AE-4 Budget severities (INV-5, OD-10)**
- Given "Groceries" at 80%, 95% and 110%, with discreet on.
- Then all three show identical BUDGET discreet constants, with no detail ("Groceries", "%", amounts, "Exceeded").

**AE-5 Warranty kind (INV-4, INV-5)**
- Given "Demo Blender" from "Demo Store" expiring in 7 days, with discreet on.
- Then the kind is `WARRANTY` (not BUDGET) and the channel is still `budget_alerts`. No detail ("Demo Blender",
  "Demo Store", "7 days").
- (AMD-17) The title and text are the WARRANTY discreet constants, never the BUDGET ones. AE-111 states the same for
  `RECEIPT_MATCH`.

**AE-6 Briefing (INV-5, INV-8)**
- Given a READY artifact whose summary contains the sentinel "SENTINEL-AI-7", with discreet on.
- Then the BRIEFING discreet constants are shown, with no BigText and no sentinel.
- (AMD-19) The title is `notification_briefing_ready_title` ("Your AI briefing is ready") and the text is
  `notif_discreet_briefing_text`.

**AE-7 Unreadable settings (INV-2, INV-6, INV-9)**
- Given the store throws an IO error.
- When the bill worker posts one reminder.
- Then it posts DISCREET. `markReminderSent` is called once. `notificationsSent` = 1.
  `NOTIF_POLICY_SETTINGS_UNREADABLE(IO_ERROR)` is recorded.

**AE-8 Slow read (INV-9)**
- Given the read hangs.
- Then the post happens DISCREET within `T_read` plus normal build time.

**AE-9 Cancellation (INV-9)**
- Given the worker is stopped during the settings read.
- Then `CancellationException` propagates, nothing is posted, and the guard records it as cancelled.

**AE-10 Policy exception (INV-9)**
- Given the policy throws `IllegalStateException`.
- Then the post goes ahead DISCREET. `NOTIF_POLICY_FALLBACK_EXCEPTION` carries the class name only.

**AE-11 Parity (INV-9, INV-11)**
- For each of the six composition sites (AMD-17), with discreet off, on and unreadable:
  - the number of `notify` calls is identical;
  - `DeliveryResult`, claim/mark calls and cooldown writes are identical;
  - for `ReceiptMatchingWorker`, the receipt-link call, `addNotificationsSent`, `addRowsSkipped` and
    `recordNotificationSuppressed` calls are identical.

**AE-12 Older APIs, lock off (INV-10)**
- On API 26, 28, 29 and 30 with lock off and settings readable, bill actions are present without
  `authenticationRequired`.

**AE-13 FGS (INV-12)**
- The capture FGS notification text is "Expense Tracker Active" / "Monitoring your transactions" in every setting
  state.
- (AMD-17) The kind registry holds exactly six kinds. Each composition site declares exactly one:
  `BillReminderWorker` → `BILL`, `AnomalyAlertOrchestrator` → `ANOMALY`, `BudgetMonitor` → `BUDGET`,
  `WarrantyExpirationWorker` → `WARRANTY`, `ReceiptMatchingWorker` → `RECEIPT_MATCH`,
  `DeliverProactiveBriefingNotificationUseCase` → `BRIEFING`. The FGS notification declares none.

**AE-14 Device: lock screen (INV-7)**
- Given the device's "hide sensitive content" setting.
- Then a budget alert on the secure lock screen shows only the public version, with no actions.

**AE-15 Already posted (INV-13, OD-6)**
- Given an active FULL anomaly notification.
- When discreet is turned on, then the same id now shows the ANOMALY discreet constants with the same content intent.
- When discreet is turned off, then nothing is re-posted.

### 1a — Deep links

**AE-16 Browser (INV-20)**
- Given a web page link `expensetracker://dashboard?briefingKey=dashboard_home:2027-03-12`.
- When the user taps it in a browser.
- Then the app does not open (device test).

**AE-17 Key not delivered (INV-16)**
- Given `lastDelivered = dashboard_home:2027-03-11`.
- When another app sends VIEW `expensetracker://dashboard?briefingKey=dashboard_home:2027-03-12`.
- Then the app navigates Home and `lastOpened` is unchanged. `DL_BRIEFING_KEY_NOT_DELIVERED` is recorded.
- Then the 2027-03-12 briefing is still delivered later.

**AE-18 Key delivered (INV-16)**
- Given `lastDelivered = dashboard_home:2027-03-12`.
- When the notification tap carries that key.
- Then `lastOpened := dashboard_home:2027-03-12`, and nothing else is written.

**AE-19 Malformed key (INV-15, INV-16)**
- Given `briefingKey` = `dashboard_home:2027-13-40`, a 500-char value, or `x';DROP`.
- Then the parameter is dropped (`DL_PARAM_INVALID_DROPPED`), the app goes Home, and nothing is written.

**AE-20 Engagement store unreadable (INV-16)**
- Given a valid key and an engagement store that cannot be read.
- Then nothing is written. `DL_BRIEFING_STATE_UNREADABLE` is recorded. The app goes Home.

**AE-21 No raw values (INV-18)**
- After AE-17…AE-20, no diagnostic row, Timber line or `AiRuntimeDiagnostics` event contains "dashboard_home",
  "2027-03-12", "briefingKey" or "expensetracker://".

**AE-22 Activity valid (INV-15)**
- Given `activity?expenseId=77`, where expense 77 is dated 2027-03-10 14:32 local.
- Then Transactions opens filtered to 2027-03-10 00:00 through 2027-03-11 00:00.

**AE-23 Activity invalid (INV-15)**
- Given `expenseId` = `-5`, `abc`, `0`, `99999999999999999999` or `1&expenseId=2`.
- Then the Transactions list opens and `DL_PARAM_INVALID_DROPPED` is recorded. No lookup happens.

**AE-24 Lookup failure (INV-22)**
- Given expense 78 does not exist → `DL_TARGET_NOT_FOUND`.
- Given the read barrier denies the read → `DL_TARGET_READ_BLOCKED` (class name only).
- In both cases the Transactions list opens and nothing crashes.

**AE-25 Analytics (INV-15)**
- `period=MONTH` → Analytics with MONTH.
- `period=decade` → Analytics with its default period, plus `DL_PARAM_INVALID_DROPPED`.

**AE-26 Map (INV-15)**
- `location=Demo%20Cafe` → Map with the "Demo Cafe" highlight.
- A `location` with `%00`, a bidi control character, or 101 chars → Map with no highlight.

**AE-27 Add (INV-17)**
- `add?amount=50&merchant=X` → an empty add sheet, plus `DL_PARAM_UNKNOWN_DROPPED` (count 2). No expense is created.

**AE-28 Bank host (INV-21)**
- A bank deep-link host → Home, plus `DL_REJECTED_BANK_HOST`.

**AE-29 Unknown host (INV-15, INV-18)**
- `expensetracker://evilhost` → Home, plus `DL_REJECTED_UNKNOWN_HOST`. No record contains "evilhost".

**AE-30 Malformed structure (INV-15)**
- A link with a path (`expensetracker://home/x`), a fragment (`#f`), userinfo, a port, or longer than 2048 chars
  → Home, plus `DL_REJECTED_MALFORMED`.

**AE-31 Non-VIEW (INV-14)**
- Action `MAIN` with data `expensetracker://add` → no add sheet, plus `DL_REJECTED_ACTION`.

**AE-32 PendingIntents (INV-19)**
- For every activity PendingIntent the app creates (anomaly, briefing, budget, warranty, receipt match (AMD-17)), the
  component is non-null and equals `MainActivity`, and `FLAG_IMMUTABLE` is set.

**AE-33 Flood (INV-18)**
- Given 1,000 links rejected as unknown host within 10 s.
- Then at most `DIAG_RATE` durable events are written for that code, plus one `DL_DIAGNOSTICS_SUPPRESSED` with
  count ≥ 999 per window.

### 1b — Availability and enabling

**AE-34 API 26 / 28 / 29 (INV-23)**
- The App lock setting is disabled with an explanation, plus `LOCK_UNAVAILABLE_API`.
- Discreet notifications and secure screen are still available [OD-20].

**AE-35 API 30, no screen lock (INV-23)**
- The setting is disabled with "set a screen lock" and a link to security settings, plus
  `LOCK_UNAVAILABLE_NOT_SECURE`.

**AE-36 Enable (INV-23, INV-3)**
- On API 31, when the user enables the lock and then cancels the prompt, then the lock stays off.
- When the user enables it again and the prompt succeeds, then `appLockEnabled = true` is persisted, the state is
  `UNLOCKED` (no immediate lock screen), and `LOCK_ENABLED` is recorded.

**AE-37 Persist failure (INV-3)**
- Given the prompt succeeds but the write fails.
- Then the lock stays off, an error is shown, and `LOCK_SETTING_PERSIST_FAILED` is recorded.

### 1b — Locking

**AE-38 Cold start (INV-24, INV-26, INV-29, INV-37)**
- Given the lock is on (API 33).
- When the app starts.
- Then:
  - the first frame is neutral or the lock surface, and `FLAG_SECURE` is set before it;
  - `MainScreen` is composed 0 times;
  - no `ReviewViewModel` is created;
  - no clipboard read or listener runs;
  - no POST_NOTIFICATIONS dialog appears;
  - no Room query comes from the UI;
  - `LOCK_SHOWN_COLD_START` is recorded.

**AE-39 Unlock (INV-26)**
- After a successful auth, `MainScreen` composes. Only then may the POST_NOTIFICATIONS prompt appear, under the
  existing or R24 rules.

**AE-40 Timeout boundary (INV-25)**
- With timeout `M1`, the app goes to background at t=0.
- Foreground at t=59.999 s → `UNLOCKED`.
- Foreground at t=60.000 s → `LOCKED(TIMEOUT)`.

**AE-41 Immediately (INV-25)**
- With `IMMEDIATE`, rotation, pulling down the shade, and losing focus in split-screen do not lock.
- Pressing Home and returning after 1 s locks.

**AE-42 Own prompt (INV-25)**
- Given the OEM stops the host Activity during the credential UI.
- Then after success there is no re-lock and no loop.

**AE-43 Clock change (INV-25)**
- Given timeout `M1` and a 10 s background with the wall clock moved forward 2 h → no lock.
- Given a 2 min background with the wall clock moved back 1 h → lock.

**AE-44 Cancel (INV-34, INV-37)**
- The user cancels → the lock surface stays, the Unlock button is enabled, and there is no automatic re-prompt.
  `LOCK_AUTH_CANCELLED` is recorded.

**AE-45 Lockout (INV-34)**
- The OS reports a lockout → `LOCKED` and `LOCK_AUTH_LOCKOUT`. Later taps retry.

**AE-46 Double tap (INV-34)**
- Tapping Unlock twice quickly → exactly one prompt.

**AE-47 Stale success (INV-34)**
- A success for attempt 1 arrives after attempt 1 was cancelled and the state was re-locked → ignored, still `LOCKED`.

### 1b — Escapes

**AE-48 Screen lock removed (INV-32)**
- Given the lock is on and the user removes the device screen lock while the app is in background.
- When the app returns to the foreground, then the state is `INERT`:
  - the UI is usable and a persistent banner is shown;
  - `LOCK_INERT_NO_CREDENTIAL` is recorded;
  - `appLockEnabled` is still true;
  - notifications are still DISCREET and `FLAG_SECURE` is still set.

**AE-49 Screen lock re-added (INV-32)**
- Given the user re-adds a screen lock while `INERT` and in the app.
- Then the banner stays for the session.
- Then a background longer than the timeout leads to `LOCKED(TIMEOUT)` on return.

**AE-50 Prompt says no credential (INV-32)**
- `AUTH_ERROR(NO_DEVICE_CREDENTIAL)` and capability ≠ `AVAILABLE` → `INERT`.

**AE-51 Corrupt settings, secure device (INV-2, INV-33)**
- Given the store carries the CORRUPTED sentinel.
- Then the app starts `LOCKED(SETTINGS_UNREADABLE)`. Notifications are DISCREET and `FLAG_SECURE` is set.
- After unlock, a "review settings" banner is shown.
- The next cold start, before any re-save, is `LOCKED` again. After the user saves the settings, normal rules apply.

**AE-52 Corrupt settings, non-secure device (INV-33)**
- `INERT(SETTINGS_UNREADABLE_NO_AUTH)` with a banner. The app is usable.

**AE-53 Late read (INV-33)**
- Given the lock is off and the cold-start read exceeds `T_resolve`, so the state is `LOCKED(SETTINGS_UNREADABLE)`.
- When the read then completes as `Loaded`/disabled, then the state becomes `DISABLED` without any auth.

**AE-54 Transferred setting on API 29 (INV-32)**
- `appLockEnabled = true` on API 29 → `INERT(API_UNSUPPORTED)` with a banner. The app is usable. Notifications are
  DISCREET and bill actions are not posted.

### 1b — Deep links while locked

**AE-55 Held and applied once (INV-30)**
- While `LOCKED`, the user taps the anomaly notification for expense 77 (`onNewIntent`).
- Then there is no lookup and no navigation, and `DL_HELD_LOCKED` is recorded. The user rotates the device.
- After unlock, Transactions opens for expense 77's day exactly once.

**AE-56 Latest wins (INV-30)**
- While locked, the anomaly link is followed by a budget tap (`home`, CLEAR_TASK) → only Home is applied, and
  `DL_HELD_REPLACED` is recorded.

**AE-57 Process death (INV-30)**
- While a link is held, the process is killed → the next start is `LOCKED` → unlock → default Home. No navigation to
  expense 77.

**AE-58 Ordering (INV-31)**
- Given `UNLOCKED`, a 10 min background with timeout `M1`, and then a tap on the anomaly notification.
- Then the link is held and the lock is shown. It is applied after unlock.

**AE-59 CLEAR_TASK within timeout (INV-24)**
- Given `UNLOCKED` and a 10 s background, the user taps a budget notification.
- Then a new Activity instance starts with no lock screen and opens Home.

**AE-60 TTL (INV-30, OD-7)**
- A link held for 6 min → after unlock, Home opens and `DL_HELD_EXPIRED` is recorded.

### 1b — Precedence and restore

**AE-61 Post-restore restart (INV-1, INV-25, INV-27)**
- Given the lock is on and the user restores a backup made on a device with the lock off, then taps "Restart Now".
- Then the app cold starts `LOCKED`. After unlock the operational state is Normal and `MainScreen` shows.
  `appLockEnabled` is still true.

**AE-62 Critical recovery (INV-27)**
- Given a persisted `CriticalRecoveryRequired` and the lock on.
- Then the app lock shows first. The critical screen appears only after unlock.

**AE-63 Restore during re-lock (INV-27, INV-28)**
- Given a restore is running and the app is backgrounded past the timeout.
- When the app returns, then it is `LOCKED` and the restore continues.
- After unlock, the RestoreInProgress or RestartRequired screen shows.

### 1b — Notifications and secure screen

**AE-64 Actions with the lock on (INV-10)**
- API 31: actions are present with `authenticationRequired`.
- API 30: no actions, plus `NOTIF_ACTIONS_SUPPRESSED_LOCK`.
- API 30 with settings unreadable: no actions.

**AE-65 Forced discreet (INV-6, OD-2)**
- Given the lock is on and the discreet setting is off.
- Then every kind posts DISCREET, and the settings UI shows discreet as forced.

**AE-66 Forced secure screen (INV-29, device)**
- Given the lock is on and the secure-screen setting is off.
- Then the recents thumbnail is blank, screenshots are blocked, and cast or screen-share shows black.

**AE-67 Window inheritance (INV-29, device)**
- With the lock on, the AddExpense sheet, assistant sheet, a `DropdownMenu` and an `AlertDialog` all carry
  `FLAG_SECURE`.

**AE-68 Background work while locked (INV-36)**
- While `LOCKED`, a bank notification is captured and `BillReminderWorker` posts (DISCREET).
- After unlock, the captured item is in Review. Worker runs are recorded as normal.

**AE-69 Lock surface (INV-35)**
- The lock surface shows no amounts, review count, badges or held-link hint.
- TalkBack announces an "Unlock" button. At 200% font scale the button text is not truncated.

**AE-70 Disable needs auth (INV-3, OD-8)**
- The user taps "disable lock" and cancels the prompt → the lock stays enabled.

### 2 — App PIN

**AE-71 Set PIN (INV-38)**
- When the user enters 482915 twice.
- Then a verifier with stored KDF parameters and salt exists.
- Then "482915" is not present in any preference or DataStore file, log, diagnostic or saved-state Bundle.

**AE-72 Device credential excluded (INV-40)**
- In PIN mode, no prompt offers the device credential. With biometric off, only the PIN pad is shown.

**AE-73 Delays persist (INV-39)**
- Four wrong PINs → no delay. The fifth wrong PIN → 30 s delay.
- The app is killed at 10 s and restarted → 20 s remain.
- A reboot → 30 s from boot.
- The wall clock is moved +1 h → no change.

**AE-74 Reset (INV-39)**
- A correct PIN after the delay → `UNLOCKED`, and the counter is 0 (persisted).

**AE-75 No wipe (INV-39)**
- After 100 wrong PINs, data is intact and the delay is 60 min.

**AE-76 New enrolment (INV-40, OD-16)**
- Given biometric-in-PIN-mode was enabled with the PIN, and then a new fingerprint is enrolled.
- Then biometric unlock is off, the PIN is required, a banner is shown, and `PIN_BIOMETRIC_INVALIDATED` is recorded.

**AE-77 Forgotten PIN (INV-42)**
- There is no in-app reset. The user clears app data, so the app is fresh with the lock off and no PIN.
- The user restores an exported `.costbackup` with its password → data is back, and there is no PIN.

**AE-78 In-app restore keeps the PIN (INV-1, INV-42)**
- An in-app restore on a PIN-protected install → after restart, the same PIN is required.

**AE-79 Unreadable settings with a verifier (INV-41)**
- Settings are corrupt and a verifier exists → PIN-mode `LOCKED`, not device auth and not `INERT`.

**AE-80 Unreadable verifier (INV-41, OD-15)**
- The Keystore key is lost → `LOCKED`, the recovery explanation is shown, and `PIN_VERIFIER_UNREADABLE` is recorded.
  There is no device-credential option.

**AE-81 Non-secure device (INV-40)**
- In PIN mode, with the device screen lock removed, the PIN still unlocks. The state is not `INERT`.

**AE-82 Change or remove (INV-42)**
- Changing or removing the PIN without entering the current PIN is impossible.

**AE-83 API 29 (INV-43)**
- The App PIN option is unavailable [OD-18].

**AE-84 Attempt state unreadable (INV-39)**
- Corrupt attempt state → one 30 s delay before entry, and `PIN_ATTEMPT_STATE_UNREADABLE` is recorded.

### Amendments (stage 3b) — AE-85…AE-110

Defaults as above. In addition: API 33, capability `AVAILABLE`, `DEVICE_AUTH`, unless stated.

**AE-85 Review-save, weakening (INV-44, INV-45, OD-8) — AMD-1**
- Given the store carries the CORRUPTED sentinel and the user unlocked from `LOCKED(SETTINGS_UNREADABLE)`.
- When the user saves {lock off, `M1`, discreet off, secure off} and cancels the prompt.
- Then nothing is written, the sentinel remains, and the next cold start is `LOCKED(SETTINGS_UNREADABLE)` again.
- When the user saves the same record again and the prompt succeeds.
- Then one atomic write stores every field with marker `NORMAL`, and the load state is `Loaded`. The state is
  `DISABLED`, `FLAG_SECURE` is cleared, and the next bill posts FULL. `LOCK_SETTINGS_REVIEW_SAVED` is recorded.

**AE-86 Review-save, not weakening (INV-45) — AMD-1**
- Given AE-85's starting point.
- When the user saves {lock on, `IMMEDIATE`, discreet off, secure off}.
- Then no prompt appears, because the effective values equal `F` (discreet and secure are forced by the lock).
- The state stays `UNLOCKED`, and the next cold start is `LOCKED(COLD_START)`, not `SETTINGS_UNREADABLE`.

**AE-87 Review-save while INERT (INV-45) — AMD-1**
- Given the CORRUPTED sentinel on API 31 with no screen lock, so the state is `INERT(SETTINGS_UNREADABLE_NO_AUTH)`.
- When a record with the lock on is saved → `Rejected(CAPABILITY)`, and nothing is written.
- When {lock off, discreet on, secure off} is saved → no prompt, the record is written, the state is `DISABLED`, and
  the inert notice is gone.

**AE-88 Per-field write while unreadable (INV-44) — AMD-1**
- Given the CORRUPTED sentinel, or a read that failed with `IO_ERROR`.
- When the discreet setter is called.
- Then the result is `Rejected(UNREADABLE)`, nothing is written, and the sentinel (if any) remains.

**AE-89 Review-save write failure (INV-44, INV-46) — AMD-1, AMD-10**
- Given AE-85 with a successful prompt and a failing write.
- Then the store, the sentinel and the state are unchanged, an error is shown, and
  `LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)` is recorded.

**AE-90 Fail-closed timeout while unreadable (2.3 `F`) — AMD-1**
- Given the CORRUPTED sentinel and `UNLOCKED` after an unlock.
- When the app is backgrounded for 1 s and returns.
- Then the state is `LOCKED(TIMEOUT)`, because the effective timeout is `IMMEDIATE`.

**AE-91 Shared attempt counter (INV-39) — AMD-2**
- Given PIN mode and four wrong unlock PINs (counter 4, persisted).
- When a wrong current PIN is entered in Change PIN.
- Then it counts as failure 5, and the 30 s delay is persisted before the result is shown.
- When the process is killed and restarted at 10 s, then the lock screen shows the delay with 20 s left.
- While the delay is active, each of these rejects entry without evaluating it: Remove PIN, disable lock, enable
  fingerprint, a longer timeout, and a review-save PIN entry.

**AE-92 Success on any surface resets (INV-39) — AMD-2**
- Given counter 3.
- When the correct current PIN is entered in Change PIN.
- Then the counter is 0 (persisted) before the next step is shown.

**AE-93 Enable refused for capability (INV-23, T26) — AMD-3**
- Given API 31 and capability `UNKNOWN` (repeat with `HW_UNAVAILABLE`).
- When the user requests enable.
- Then no prompt is launched, the state stays `DISABLED`, and `appLockEnabled` stays false.
  `LOCK_UNAVAILABLE_CAPABILITY(UNKNOWN)` is recorded once per process, even after repeated requests.
- When capability later reads `AVAILABLE` and the user retries, then T17 applies.

**AE-94 Remove PIN without a screen lock (INV-49) — AMD-4**
- Given PIN mode, lock on, and capability `NO_CREDENTIAL`.
- When the user enters the correct current PIN and confirms removal.
- Then the verifier, attempt state, biometric key and notice flag are deleted first. After that, `appLockEnabled =
  false` and `lockMode = DEVICE_AUTH` are written.
- The state is `DISABLED`, not `INERT`. `PIN_REMOVED` and `LOCK_DISABLED` are recorded. The next cold start shows no
  lock.

**AE-95 Remove PIN with a screen lock (INV-49) — AMD-4**
- Given PIN mode, lock on, and capability `AVAILABLE`.
- When the user removes the PIN with the correct current PIN.
- Then `lockMode = DEVICE_AUTH` is written with the lock still enabled, and no device prompt is asked.
- The state stays `UNLOCKED`. The next cold start is `LOCKED` and uses the system prompt.

**AE-96 Interrupted removal (INV-49, 2.3) — AMD-4**
- Given AE-94 or AE-95, and the process is killed after the verifier is deleted but before the settings write.
- Then the next start reads `appLockEnabled = true`, `lockMode = APP_PIN` and no verifier. It evaluates this as
  `DEVICE_AUTH`:
  - with capability `AVAILABLE` → `LOCKED(COLD_START)` with the device prompt;
  - otherwise → `INERT(NO_CREDENTIAL)` with the notice, and turning the lock off needs no auth (T19).
- No PIN pad is shown in either case.

**AE-97 Transient unreadable recovers (INV-47, T28) — AMD-5**
- Given the cold-start read fails with `IO_ERROR`, and the stored settings are {lock off, discreet off, secure off}.
- Then the start is `LOCKED(SETTINGS_UNREADABLE)`, and the user unlocks.
- When the settings retry reads `Loaded`.
- Then the state is `DISABLED`, `FLAG_SECURE` is cleared, the next post is FULL, and
  `LOCK_SETTINGS_RECOVERED(IO_ERROR)` is recorded. No review notice is shown at any point. The next cold start shows
  no lock.

**AE-98 Re-read never unlocks (INV-47, T29) — AMD-5**
- Given `LOCKED(SETTINGS_UNREADABLE)` caused by `IO_ERROR`.
- When the app returns to the foreground and the re-read gives `Loaded` with the lock off.
- Then the state stays `LOCKED` until an auth success. After unlock, T28 applies the stored values.

**AE-99 Notice priority (INV-51) — AMD-6**
- Given PIN mode, the CORRUPTED sentinel, and a pending fingerprint notice.
- Then after a PIN unlock only the review notice is shown. After a review-save, the fingerprint notice is shown.
- Given the CORRUPTED sentinel in `DEVICE_AUTH` mode and `UNLOCKED`.
- When the screen lock is removed and the app returns (T16), then only the inert notice is shown.
- In `RESOLVING`, `LOCKED` and `AUTHENTICATING` (AMD-22: `AUTHENTICATING(UNLOCK)`), no notice is composed. AE-115
  covers open-origin prompts.

**AE-100 Fingerprint notice survives process death (INV-51, OD-16) — AMD-6**
- Given AE-76: a new enrolment turned biometric unlock off.
- When the process is killed before the user dismisses the notice.
- Then the notice is shown again after the next PIN unlock.
- When the user dismisses it, then the flag is cleared durably and the notice does not return.

**AE-101 Review notice persists (INV-51, INV-33) — AMD-6**
- Given the CORRUPTED sentinel.
- Then the review notice offers no dismiss action. It is shown after every unlock and every cold start until a
  review-save succeeds.

**AE-102 Silent re-post (INV-13, OD-6) — AMD-7**
- Given an active FULL budget notification posted at 09:00 on a channel with sound, and a second budget notification
  that the user dismissed at 09:02.
- When the lock is enabled at 09:05 and the write commits.
- Then the active notification is re-posted with the same id and the BUDGET discreet constants. There is no sound,
  vibration or heads-up, and its post time stays 09:00. The dismissed notification is not re-posted.
- `notificationsSent`, `DeliveryResult`, claim/mark and cooldown state are unchanged.
  `NOTIF_REPOSTED_DISCREET(count = 1)` is recorded.
- Given `notify` throws `SecurityException` for one of two active notifications.
- Then that one is skipped with `NOTIF_REPOST_FAILED(SecurityException)`, the other is re-posted, and nothing
  loops. The G1b-0 post-sweep verification re-posts a skipped or unconfirmed item once (one settle of 750 ms, one
  re-read, one paced re-post, no loop), then emits the COUNT-only `NOTIF_REPOST_UNCONFIRMED` for any still
  unconfirmed.

**AE-103 Forced values are read-only (INV-48, OD-2) — AMD-8**
- Given the lock on and stored discreet = false.
- When a write sets discreet to true, or to false → `Rejected(FORCED)`. The stored value stays false and no prompt
  appears.
- When the lock is then disabled (T18), then effective discreet is false and the next post is FULL.

**AE-104 PIN setup has no backup dependency (6.5) — AMD-9**
- Given a fake backup repository.
- When the user completes PIN setup, once with a backup present and once with none.
- Then the flow and its result are identical, and the backup repository receives no call.
- The "Back up now" shortcut, if present, only emits a navigation request to the existing Backup & Restore
  destination.

**AE-105 Plain toggle write failure (INV-46) — AMD-10**
- Given discreet off and a store whose writes fail.
- When the user turns discreet on.
- Then the committed value stays off, and the next post is FULL. No re-post sweep runs. An error is shown, and
  `LOCK_SETTING_PERSIST_FAILED(DISCREET)` is recorded.
- The same holds for secure screen (`FLAG_SECURE` unchanged) and for the timeout (the old timeout still decides the
  AE-40 boundary).

**AE-106 Unreadable attempt state, neutral reason (INV-39) — AMD-11**
- Given a corrupt attempt record.
- Then the repaired record {failures 4, `counterOrigin = REPAIRED_UNREADABLE`, one 30 s delay} is persisted before
  entry is allowed.
- The exposed delay reason is `ATTEMPT_STATE_UNREADABLE`, and no remaining-tries value is exposed.
- After the 30 s, one wrong PIN → failure 5, `counterOrigin = FAILURES`, and a 30 s delay with reason `FAILURES`.
- If the repair write fails, then each process start applies one 30 s delay with reason `ATTEMPT_STATE_UNREADABLE`.

**AE-107 PIN setup preconditions (INV-50, T31) — AMD-12**
- In `DISABLED`, in `INERT(NO_CREDENTIAL)`, and on API 29, PIN setup is refused (`Rejected(PRECONDITION)`) and
  nothing is written.
- On API 30 with the lock on, setup first asks for one device prompt. Cancelling it stores nothing.
- After a successful setup, `lockMode = APP_PIN` is committed before the verifier. If the process is killed between
  the two writes, the next start is `LOCKED` with the device prompt.

**AE-108 Notice during an operational state (INV-52) — AMD-13**
- Given `INERT(NO_CREDENTIAL)` and `RestoreInProgress`.
- Then the inert notice is visible with system-intent actions only. The `MainViewModel` navigation channel receives
  nothing from the notice.
- When the operational state returns to `Normal`, then the in-app settings action is offered.

**AE-109 Generic public version (INV-5, INV-7, OD-21 a) — AMD-14**
- For each of the six kinds (AMD-17), in FULL and in DISCREET mode:
  - the public version's title and text are the app-wide generic constants (`notif_public_title` /
    `notif_public_text`, AMD-19) and are equal across all kinds;
  - they differ from every kind's discreet title and contain none of the sentinels of AE-1…AE-6 and AE-111;
  - there are no actions and no expanded style.
- Under OD-21 (b) (rejected at Gate 2), the public version would equal the kind's discreet constants instead.

**AE-110 Disabling in PIN mode removes the PIN (INV-49, INV-41) — AMD-4**
- Given PIN mode, lock on, and capability `AVAILABLE`.
- When the user turns the lock off with the correct PIN.
- Then the verifier set is deleted first, then `appLockEnabled = false` is written, and the state is `DISABLED`.
- When the settings later become CORRUPTED, then the next cold start is `LOCKED(SETTINGS_UNREADABLE)` with the device
  prompt (there is no verifier, so `DEVICE_AUTH`), not a PIN pad.

### Amendments (post-Gate-2) — AE-111…AE-112

Defaults as above (API 34, notifications permitted, settings `Loaded`, lock off, discreet off).

**AE-111 Receipt match kind (INV-4, INV-5, INV-7, INV-8, INV-12, INV-19) — AMD-17**
- Given receipt 42 with parsed merchant "Demo Bakery SENTINEL-RM-3", auto-matched and linked to expense 77.
- When `ReceiptMatchingWorker` posts with discreet off.
- Then the kind is `RECEIPT_MATCH`, the channel is `budget_alerts`, and the id is `NotificationId.forReceipt(42)` =
  20042. The visible title and text are today's (`receipt_matching_auto_matched_title` /
  "Receipt from Demo Bakery SENTINEL-RM-3 matched to transaction automatically"). The public version is the generic
  pair with no actions.
- When the same post happens with discreet on (repeat with the lock on, and with settings unreadable).
- Then the title and text are `notif_discreet_receipt_match_title` / `notif_discreet_receipt_match_text`, and not the
  BUDGET discreet constants. No text field, text extra or public version contains "Demo Bakery", "SENTINEL-RM-3" or
  "Receipt from".
- In every mode, the content intent's component is `MainActivity`, its data is `expensetracker://home`, and it has
  `FLAG_IMMUTABLE` and CLEAR_TASK. The receipt link, `DeliveryResult` and `addNotificationsSent` are identical across
  modes.

**AE-112 Re-post kind detection (INV-13, 4.6) — AMD-21**
- Given these active notifications: on `budget_alerts`, ids 7, 10003, 15003 and 20042; on `bill_reminders`, id 30011;
  on `anomaly_alerts`, id 40077; one on `ai_briefings`; and the capture FGS notification on its own channel. Each was
  posted FULL with the sentinels of AE-1…AE-6 and AE-111.
- When discreet is switched on and the write commits.
- Then they are re-posted with the BUDGET, WARRANTY, WARRANTY, RECEIPT_MATCH, BILL, ANOMALY and BRIEFING discreet
  constants respectively. Each keeps its id, channel, content intent and post time. The bill notification's actions
  follow 4.4 for the current API.
- The FGS notification is not re-posted.
- No re-posted notification contains any sentinel in any text field, text extra or style. Nothing is copied from the
  original notification.

### Amendments (plan stage) — AE-113…AE-121

Defaults as above, plus: API 33, capability `AVAILABLE`, effective `DEVICE_AUTH`, unless stated. "Composition count"
means the number of times the `MainScreen` content slot entered composition, counted by a test probe. "Disposed" means
the slot left composition.

**AE-113 Enable keeps MainScreen composed (INV-53, INV-26, T17) — AMD-22**
- Given `DISABLED` and stored secure screen off. The user is on Privacy Settings, with composition count 1 and a
  non-saveable `remember` probe value "P-113" in the settings screen.
- When the user taps Continue in the enable explainer, so the state is `AUTHENTICATING(id, ENABLE)`.
- Then the slot is not disposed and the composition count stays 1. The lock surface is composed 0 times, the probe
  still reads "P-113", and the POST_NOTIFICATIONS effect does not run again. `FLAG_SECURE` is not set (committed lock
  off).
- When the prompt succeeds and the write commits.
- Then the state is `UNLOCKED`, the count is still 1 with no dispose, and the probe still reads "P-113". `FLAG_SECURE`
  is set from the next frame. `LOCK_ENABLED` is recorded.
- When instead the prompt is cancelled.
- Then the state is `DISABLED`, the count is still 1, nothing is written, and "App lock was not turned on." is shown.

**AE-114 Weakening confirm: composition kept, FLAG_SECURE committed-only, constant prompt text (INV-53, INV-29, INV-46,
T18) — AMD-22**
- Given `UNLOCKED`, lock on, stored secure screen off (forced on), timeout `M1`.
- When the user turns the lock off, so the state is `AUTHENTICATING(id, CONFIRM_CHANGE)`.
- Then `MainScreen` stays composed and `FLAG_SECURE` stays set for the whole prompt. The prompt title is
  `applock_prompt_title_disable`, with no subtitle and no description.
- When the prompt succeeds and the write commits.
- Then the state is `DISABLED` and `FLAG_SECURE` is cleared from the next frame after the commit, not before it.
- When instead the prompt succeeds and the write fails.
- Then the state stays `UNLOCKED`, `FLAG_SECURE` stays set, and `LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)` is
  recorded.

**AE-115 Notice during a review-save prompt, versus the unlock prompt (INV-51, INV-53, T27) — AMD-22**
- Given the CORRUPTED sentinel and `UNLOCKED`. The REVIEW_SETTINGS notice is shown.
- When the user saves a weakening review record, so the state is `AUTHENTICATING(id, CONFIRM_CHANGE)`.
- Then the review notice stays shown: it is not removed and shown again. `MainScreen` stays composed.
- Given instead `LOCKED(SETTINGS_UNREADABLE)` and a tap on Unlock, so the state is `AUTHENTICATING(id, UNLOCK)`.
- Then no notice is derived and `MainScreen` is composed 0 times, as in AE-38 and AE-99.

**AE-116 Background during a confirm prompt goes to the timer (INV-54, INV-25, T32, T34) — AMD-22**
- Given `UNLOCKED`, lock on, timeout `M1`. The user asks for a longer timeout (`M5`), so the state is
  `AUTHENTICATING(id, CONFIRM_CHANGE)`.
- When the prompt stays open in the foreground for 10 min and is then cancelled.
- Then the state is `UNLOCKED`, the timeout is still `M1`, and nothing locks.
- When instead the user presses Home at t=0 and the system cancels the prompt.
- Then the state is `UNLOCKED`, the timeout is still `M1`, and `lastBackgroundedAt` = 0.
- When the app returns at t=59.999 s, then it is `UNLOCKED`.
- When it returns at t=60.000 s instead, then it is `LOCKED(TIMEOUT)`, and `MainScreen` leaves composition.
- The same holds whether the cancel callback arrives before or after `APP_BACKGROUNDED`.

**AE-117 Own-prompt exemption on success (INV-54, T33, AE-42) — AMD-22**
- Given `UNLOCKED`, lock on, timeout `IMMEDIATE`. The user asks for `M5`.
- When an OEM credential UI stops the host Activity (`APP_BACKGROUNDED` at t=0), the user authenticates at t=20 s, and
  the host restarts (`APP_FOREGROUNDED`).
- Then `M5` is committed, the state is `UNLOCKED`, and there is no `LOCKED(TIMEOUT)` and no loop. This holds whether
  the success arrives before or after `APP_FOREGROUNDED`.
- Given instead `DISABLED` with a stored timeout of `IMMEDIATE`, and the same OEM behaviour during an ENABLE prompt.
- Then after the success the state is `UNLOCKED`, with no immediate lock screen (as AE-36).

**AE-118 Host loss ends the attempt (T35, T34, T12, T20) — AMD-22**
- Given `UNLOCKED`, lock on, and a CONFIRM_CHANGE prompt in flight.
- When the host Activity is finished before any outcome, for example because a budget notification tap (CLEAR_TASK)
  replaces the task.
- Then the attempt ends as cancelled. The state is `UNLOCKED`, the change is not applied, and the settings switch is
  enabled again.
- A finishing host (finishing and not a configuration change) cancels the library prompt silently: the system prompt
  window is gone without Back, and no outcome is delivered for the attempt (the end is the Abandoned host loss, not a
  failure). Its echo is dropped. A rotation or a stop that is not finishing never cancels the prompt (AE-36, AE-117,
  AE-120).
- The budget link is applied once (Home), whether it arrived before or after the host loss.
- When a late `AUTH_SUCCEEDED` for the abandoned id arrives, then it is ignored, and no change and no unlock happen.
  This holds even if the app has re-locked in the meantime.
- Repeat with an UNLOCK prompt: the state stays `LOCKED` and the Unlock button is enabled again.
- Repeat with a rotation instead of host loss: the attempt stays in flight, and a matching success on the recreated
  Activity is accepted (3.5).

**AE-119 [2] A PIN confirmation step is not a prompt (INV-54) — AMD-22**
- Given PIN mode, lock on, `UNLOCKED`, timeout `M1`. The user opens "Turn off app lock" and is on the "Enter your
  current PIN" step with 3 digits typed.
- When the user presses Home at t=0 and returns at t=60 s.
- Then the state is `LOCKED(TIMEOUT)` with the PIN pad. Nothing was removed or written. After unlock the confirmation
  step, if shown again, starts empty. No digit is in any saved-state Bundle.
- When the user returns at t=30 s instead, then the state is `UNLOCKED` and the step is still shown.

**AE-120 [2] A prompt that is not unlock-equivalent keeps the background (INV-54, T33) — AMD-22**
- This applies only if the Inc 2 design shows a system prompt when fingerprint unlock is enabled (6.3).
- Given PIN mode, lock on, `UNLOCKED`, timeout `IMMEDIATE`. The correct PIN has been entered to enable fingerprint
  unlock, and a `BIOMETRIC_STRONG`-only prompt is shown.
- When the host is stopped during the prompt (`APP_BACKGROUNDED` at t=0), the prompt succeeds at t=5 s, and the host
  restarts.
- Then fingerprint unlock is committed, and the foreground evaluation gives `LOCKED(TIMEOUT)` in PIN mode. The
  background was not discarded.

**AE-121 Legacy notifications and marker precedence (4.6, INV-13, TECH-3 a) — AMD-22**
- This applies only if TECH-3 (a) is adopted.
- Given these active notifications, each posted FULL with the sentinels of AE-1…AE-6 and AE-111:
  - `budget_alerts` id 15003 with marker `BUDGET` (an alert for budget id 15003, posted after the upgrade);
  - `budget_alerts` id 10003 with no marker (a warranty posted before the upgrade);
  - `budget_alerts` id 20042 with marker `BILL`;
  - `bill_reminders` id 30011 with no marker;
  - `budget_alerts` id 7 with the unknown marker value "X".
- When discreet is switched on and the write commits.
- Then they are re-posted with the BUDGET, WARRANTY, RECEIPT_MATCH, BILL and BUDGET discreet constants respectively.
  Each keeps its id, channel, content intent and post time. No sentinel appears, and nothing is copied from the
  originals.

---

## 13. Open decisions (all decided at Gate 2)

(AMD-18) Status: all 21 were decided at Gate 2 on 2026-10-16. The owner's verbatim answer was "accept all
reccomendations" (`gate2-decisions.md`). Each OD resolves to its Recommendation column. OD-19 is escalated to the release
track. The question, option and recommendation text is kept unchanged for traceability. Items still open after Gate 2
are in 13.1.

| OD | Question | Options | Recommendation | Gate 2 outcome (AMD-18) |
|---|---|---|---|---|
| OD-1 (OQ1) | Is the household (PIN-knowing partner) threat in scope via Inc 2? | (a) build Inc 2 last as an option; (b) drop Inc 2 | (a), conditional on OD-15, OD-16 and OD-19 being accepted. Without them a device-PIN holder has bypasses (biometric enrolment, D2D). | DECIDED at Gate 2 (2026-10-16): option (a), with OD-15 (a), OD-16 (a) and the OD-19 escalation |
| OD-2 (OQ2) | Discreet notifications while the lock is on | (a) forced; (b) on by default, user can turn off | (a): one fail-closed rule, matching the forced secure screen | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-3 (OQ3) | Manifest | (a) remove BROWSABLE only; (b) also remove the VIEW filter | (a) now. Neither changes reachability by installed apps (F9), so validation (INV-14…INV-16) is the boundary. Revisit (b) after explicit PendingIntents ship and external callers are checked. | DECIDED at Gate 2 (2026-10-16): option (a) (technical, `gate2-decisions.md` §B); revisit (b) later as recommended |
| OD-4 (OQ4) | Ship 1a ahead of 1b regardless of C4 | (a) yes; (b) wait | (a): 1a has no dependency on the lock. Reconcile with C4 at the impact map (5.8). | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-5 | API 31+ authentication-required actions | (a) unconditional from 1a; (b) only when discreet or the lock is on | (a): 1a has no lock, and lock-screen mutation is a risk on its own. Note: the brief's wording "lock off → unchanged" is read as applying to API 30 only. | DECIDED at Gate 2 (2026-10-16): option (a); release note required at 1a (APP-4) |
| OD-6 | Already-posted notifications when discreet mode or the lock turns on | (a) re-post in place with discreet constants; (b) leave them and say so in copy; (c) cancel them | (a): the only option that meets Goal 1 literally. It retains no content and changes no lifecycle. Flagged as a mechanic beyond the brief's explicit list (S1). | DECIDED at Gate 2 (2026-10-16): option (a), so S1 is in scope |
| OD-7 | Held-link expiry | (a) 5 min TTL; (b) until consumed, replaced or process death; (c) drop on background while locked | (a): avoids stale navigation hours later. (c) risks dropping during OEM credential UIs. | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-8 | Do weakening changes require a fresh auth while the lock is enabled? | (a) yes (INV-3 list); (b) only enabling needs auth | (a) | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-9 | UI state across a re-lock | (a) preserve navigation and saveable state without composing; (b) discard | (a): keeps in-progress input and pending activity results. (b) loses them silently. | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-10 | Discreet copy granularity | (a) kind only; (b) kind + severity or window | (a). `ux-flows.md` owns the wording within the limits in 4.3. | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-11 (architecture) | Settings store | (a) a dedicated device-local store with its own sentinel; (b) extend `privacy_settings` / `PrivacySettings` | (a): isolates corruption impact and avoids touching `PrivacyRuntimeWorkerPolicy` coupling. Architecture-guardian decides. | DECIDED at Gate 2 (2026-10-16): option (a) (technical, `gate2-decisions.md` §B) |
| OD-12 (architecture/privacy) | Diagnostics sink | (a) durable `DiagnosticEventWriter` (`AppPipeline.PRIVACY` + controlled codes), best effort, rate-limited; (b) in-memory only | (a). The sink writes to Room, so it is denied during restore. Drop silently with a class-name-only log. | DECIDED at Gate 2 (2026-10-16): option (a) (technical, `gate2-decisions.md` §B). Codes travel in exported backups (privacy-security-guardian note) |
| OD-13 [2] | PIN format | (a) numeric, 6–12 digits, reject all-same and simple sequences; (b) 4+ digits | (a) | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-14 [2] | Delay schedule; unreadable attempt state | as in 6.2 / alternatives | 6.2 as written. Never wipe. | DECIDED at Gate 2 (2026-10-16): as written (6.2) |
| OD-15 [2] | Verifier unreadable | (a) hard lock (clear-data recovery); (b) fall back to device credential | (a). (b) is a bypass for device-PIN holders, including via D2D. | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-16 [2] | Biometric unlock in PIN mode | (a) Keystore `CryptoObject` invalidated by new enrolment; (b) plain prompt | (a): (b) lets a partner enrol a finger with the device PIN | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-17 [2] | Notification actions in PIN mode | (a) keep (device-auth-required on 31+); (b) remove | (a), documented as a gap: the actions reveal nothing and only change reminder state | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-18 [2] | Inc 2 below API 30 | (a) API 30+ only; (b) 26+ | (a) (brief recommendation; avoids the old-API test matrix) | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-19 (app-wide, scope growth) | With targetSdk 35, `allowBackup=false` is understood **not** to stop device-to-device transfer (verify). The DB and preferences may move to another phone. | (a) escalate to the privacy/release track for an app-wide `dataExtractionRules` decision; (b) feature-only exclusion of lock/PIN files | (a). (b) alone is ineffective, because the DB would transfer without its lock. Until decided, Inc 2's household protection relies on OD-15 (a). | DECIDED at Gate 2 (2026-10-16): option (a), escalated to the release track (`gate2-decisions.md` §D, with C5 / HZ-06 F1). The app-wide outcome is pending there. Until then Inc 2 relies on OD-15 (a) |
| OD-20 | Discreet notifications and the secure-screen setting on API 26–29 | (a) available; (b) API 30+ only | (a). The brief table restricts only the App lock row. | DECIDED at Gate 2 (2026-10-16): option (a) |
| OD-21 (AMD-14, closes R-1) | Content of the public version, which the system shows only under "hide sensitive content" | (a) one app-wide generic constant pair, identical for all five kinds and naming no kind; (b) the kind's discreet constants (spec v1 INV-7; `ux-flows.md` 2.7 as drafted) | (a). It is the literal brief v2 rule ("generic public version", owner-delegated decision (a)). Users who picked "hide sensitive content" asked the OS to show less, so the public version should disclose the least. Kind text such as "Unusual charge detected" or "Bill due" tells a lock-screen viewer what kind of financial event happened. The cost is one extra constant pair. Discreet visible text still names the kind for "show all content" users (OD-10). (b) is simpler (one constant set per kind) and tells the owner what to open. Choosing (b) restores the spec v1 wording of INV-5/INV-7 and AE-1, and needs no UX change. Choosing (a) needs a generic pair in `ux-flows.md` 2.7 and 9.4. | DECIDED at Gate 2 (2026-10-16): option (a). It applies to all six kinds (AMD-17); the pair is `notif_public_title` / `notif_public_text` (`ux-flows.md` 9.4, AMD-19) |

### 13.1 Still open after Gate 2 (technical; for the guardians at plan stage) (AMD-18, AMD-21)

None of these needs an owner product decision, and none changes scope. The spec rules they cite bind whichever option
is chosen.

| Item | Question | Options | Owner | Binding spec rules |
|---|---|---|---|---|
| TECH-1 (`gate2-decisions.md` §B) | Prompt API for the device-auth and fingerprint prompts | `androidx.biometric` (needs `MainActivity` → `FragmentActivity`, a new dependency, and stripping `USE_FINGERPRINT`) vs the platform `BiometricPrompt` (fits API 30+; no host change, no dependency) | architecture-guardian + privacy-security-guardian at plan stage | 2.3 capability mapping; INV-23 (`BIOMETRIC_STRONG \| DEVICE_CREDENTIAL`, never `BIOMETRIC_WEAK`); INV-34 and 3.5 (`attemptId`, rotation); INV-40 and 6.3 (`BIOMETRIC_STRONG` only in PIN mode, enrolment-invalidated key); 10 Permissions; R1 |
| TECH-2 (`gate2-decisions.md` §B) | `NotificationService` contract: it is non-suspend, but the settings read must be bounded and may fail, and the constants are localized and cannot live in the pure domain | a suspend interface; a decision resolved in the callers and passed down; a cached snapshot | feature-planner proposes; architecture-guardian decides | INV-6, INV-9 (`T_read` bound, never blocks, `CancellationException` propagates, no `runBlocking` in workers), INV-46 (committed state only), 4.1 (AMD-20, localized constants outside the pure domain). A cached snapshot counts as `Unreadable` (DISCREET) until its first successful read. |
| TECH-3 (new, raised by AMD-21) | Exact kind for budget-channel re-posts when a budget id is in 10000–29999 (F23) | (a) the builder owner stamps a non-visible kind token (not a text extra) on each notification at post time; the sweep reads it and uses the 4.6 table only for notifications without one, such as those posted before the upgrade. No id changes. (b) `BudgetMonitor` allocates ids through `NotificationId.forBudget` (1–9999). This changes budget-alert ids: a notification posted before the upgrade is not replaced by the next post, and budgets whose ids differ by a multiple of 9999 share an id. (c) Accept the residual; it is content-free. | feature-planner proposes; architecture-guardian decides; privacy-security-guardian reviews (a) | INV-5, INV-8, INV-13, 4.6. Recommendation: (a). It keeps ids and dedupe unchanged and touches only the builder owners that 1a already edits. (AMD-22) The legacy fallback for notifications without a marker, and marker precedence, are now a binding 4.6 rule (AE-121). The plan (`app-lock-privacy-mode-plan.md`) calls this item TECH-12. |

---

## 14. Risks, required tests, reviewers

### 14.1 Risks

- **R1** `MainActivity` host type (`FragmentActivity` is required by `BiometricPrompt`). This is the most contended
  file and collides with R17, R24 and HZ-03 F1.
  - A separate auth activity would be a new component and legal path, so it needs a human decision.
  - A translucent activity causes `onPause` only, which matters for timer correctness.
- **R2** Composition gating re-runs `LaunchedEffect(Unit)` blocks on every unlock (F7). The POST_NOTIFICATIONS dialog
  would re-appear after every unlock, which collides with R24. `remember` state and pending activity results are lost
  unless OD-9 (a) is chosen.
- **R3** System UI on API 30+ (OEM credential activities, permission dialogs, SAF, camera, share sheet) can cause
  unwanted re-locks or loops. Needs device lanes on API 30, 31, 33 and 34/35.
- **R4** Edits to guard-protected workers (bill, warranty) and the bill action surface (FG-11, Recurring lifecycle) →
  `reviewer-strict`.
- **R5** A future notification site could bypass the policy → guard 8.3(1) and the INV-12 registry.
- **R6** D2D undermines Inc 2's household value (OD-19).
- **R7** The inert escape opens the app on any non-`AVAILABLE` capability, including a transient `STATUS_UNKNOWN`. This
  is an accepted trade-off for Goal 5. It is monitored by `LOCK_INERT_*` counts.
- **R8** Data loss for a forgotten PIN with no exported backup. Redacted backups restore partially.
- **R9** API 30 users with the lock on lose Snooze/Dismiss entirely, because there is no in-app equivalent (F16).
- **R10** No screenshots while the lock is on, including the user's own reports.
- **R11** Consolidating parsers changes `DeepLinkParserTest` expectations (`RequireConfirmation`). Modify; do not delete
  without approval.
- **R12** The durable diagnostics sink is unavailable during restore, and external callers could flood it → best
  effort plus `DIAG_RATE`.
- **R13** `security-crypto` is an alpha with an upstream deprecation to verify. Do not extend its use without a decision.
- **R14** Startup latency from `RESOLVING`, bounded by `T_resolve`. A pre-warmed read is recommended.
- **R15** The accessibility-service read gap is stated, not fixed.

### 14.2 Required tests

**Unit (JVM/Robolectric; `targeted-unit-test` profile, then the named shard):**
- `NotificationContentPolicy` decision table: 6 kinds (AMD-17) × FULL/DISCREET/unreadable/timeout/exception × lock on/off ×
  SDK 26/28/29/30/31/34, with sentinel-PII assertions on all text fields and extras (AE-1…AE-12).
- Builder-owner tests (Robolectric up to SDK 34): visibility, public-version fields, action `authenticationRequired`,
  explicit component, `FLAG_IMMUTABLE` (AE-1, AE-3, AE-32, AE-64).
- Worker parity and cancellation for `BillReminderWorker`, `WarrantyExpirationWorker` and `ReceiptMatchingWorker`
  (AMD-17) inside the guard (AE-7…AE-11, AE-111).
- `DeepLinkPolicy`: every host, parameter case, structural rejection and non-VIEW action. Update
  `DeepLinkParserTest` (AE-16…AE-31).
- `MarkDashboardBriefingOpenedUseCase` (AE-17…AE-20).
- Diagnostics sentinel tests and the rate limiter (AE-21, AE-29, AE-33).
- `AppLockController` with a fake monotonic clock, capability, settings and boot count: every transition T1–T25, every
  illegal transition in 3.3, timeout boundaries, `attemptId` staleness, held-link lifecycle and TTL (AE-36…AE-60).
- `AppLockSettingsRepository` load states: corruption sentinel, IO error, invalid values, absent keys (AE-51…AE-53).
- `AppPinStore`: KDF parameters stored, constant-time comparison, persistence ordering, delay math across simulated
  kill and reboot, unreadable states (AE-71…AE-84).
- Gate composition tests (`MainScreen` content slot composition count = 0 while not open; no `ReviewViewModel`).
  (AMD-20) They need no new dependency: the Compose UI-test dependencies already exist (F19, corrected). There is no
  Hilt android-test setup, so `impact-map.md` headline 3 recommends a Hilt-free `AppLockGate` (state in, content slot)
  that `createComposeRule` can drive under Robolectric.
- (AMD-16) Amendment coverage:
  - `AppLockSettingsRepository`: unreadable-write rejection, review-save atomicity and sentinel clearing, committed
    state only (AE-85, AE-88, AE-89, AE-105).
  - `AppLockController` with fakes: T26–T31; review-save authorisation against `F`; re-read recovery and
    no-relax-while-locked; forced values; PIN removal and setup orderings, including a kill between steps; notice
    derivation and priority (AE-85…AE-87, AE-90, AE-93…AE-101, AE-103, AE-107, AE-110).
  - `AppPinStore`: shared counter across surfaces, repair with `counterOrigin`, notice-flag persistence (AE-91, AE-92,
    AE-100, AE-106).
  - Policy and builder: generic public version per OD-21; silent re-post and its skip-on-failure (AE-102, AE-109).
  - Gate: notice actions during operational states (AE-108). This uses the same existing Compose UI-test
    infrastructure (F19 corrected; S7 withdrawn, AMD-20).
  - PIN setup flow: no backup dependency (AE-104).
- (AMD-17, AMD-21) Post-Gate-2 coverage:
  - Kind registry: exactly six kinds, one per composition site (AE-13).
  - Policy and builder: `RECEIPT_MATCH` FULL and DISCREET, no merchant, never the BUDGET constants, unchanged tap target
    (AE-111). `ReceiptMatchingWorkerTest` is modified, never deleted.
  - Re-post executor: channel and id-range classification, other channels untouched, rebuild without copying (AE-112).
- (AMD-22) Plan-stage coverage:
  - Gate (Hilt-free `AppLockGate`, `createComposeRule`): the content slot stays composed and is never disposed across
    ENABLE/CONFIRM_CHANGE prompts. The lock surface is composed 0 times. The origin notice is kept. `FLAG_SECURE`
    follows committed state only. Prompt text is constant (AE-113…AE-115).
  - `AppLockController` with a fake clock: T32–T35, purpose-bound attempt ids, the background handoff in both callback
    orders, host loss for every purpose, and PIN steps as ordinary backgrounds (AE-116…AE-120).
  - Re-post executor: marker precedence and legacy fallback under TECH-3 (a) (AE-121).
  - Device lane: an OEM credential UI during a settings prompt (AE-117), alongside AE-42.

**Device / API lanes (manual or new instrumented; API 26, 28, 29, 30, 31, 33, 34/35):**
- Unavailability on 26–29 (AE-34).
- Prompt flows: cancel, lockout, OEM credential UI, rotation during the prompt (AE-42…AE-47).
- Re-lock with system UI (R3).
- `FLAG_SECURE` on recents, screenshots and casting, and dialog inheritance (AE-66, AE-67).
- Lock-screen public version and action behaviour on 30 vs 31+ (AE-14, AE-64).
- BROWSABLE removal from a real browser (AE-16).
- `onNewIntent` ordering, CLEAR_TASK, process death via adb with a held link (AE-55…AE-59).
- Inert paths: remove or re-add the screen lock (AE-48, AE-49).
- Post-restore restart (AE-61).
- TalkBack and 200% font scale (AE-69).
- Inc 2: Keystore binding, enrolment invalidation, reboot delay (AE-73, AE-76, AE-80).

Static guards (`static-guards` profile) apply only if the 8.3 guards are adopted. No migration tests are needed (no
schema change).

### 14.3 Reviewers and guardians

- **architecture-guardian:**
  - the new owners and proposed LEGAL_PATHS sections (8.2);
  - parser consolidation (F10);
  - the bill builder staying in the worker or moving;
  - the `MainActivity` host-type change;
  - OD-11 and OD-12;
  - segment updates.
- **privacy-security-guardian:**
  - notification content and public versions;
  - deep-link trust model and diagnostics;
  - lock fail-closed and escape paths;
  - PIN cryptography and attempt limiting;
  - D2D (OD-19);
  - the permission policy change (`USE_BIOMETRIC`, FG-22).
- **reviewer-strict:**
  - worker edits (FG-11, FG-10);
  - the bill reminder action surface (Recurring lifecycle);
  - cross-module `MainActivity` changes;
  - privacy and security code paths.
- **room-migration-guardian:** not required (no Room change).

---

## 15. Scope-growth flags (not added; for the orchestrator or owner)

- **S1** In-place discreet re-post of active notifications (OD-6). It goes beyond the brief's explicit mechanics but
  follows from Goal 1.
- **S2** In-app snooze/dismiss for API 30 lock users (R9).
- **S3** App-wide `dataExtractionRules` to exclude D2D transfer (OD-19).
- **S4** Bill notifications have no content intent (F16). Unchanged.
- **S5** Removing the unused `DeepLinkParser.RequireConfirmation` (8.1).
- **S6** `AndroidNotificationService.sendBudgetAlert`/`sendAnomalyAlert` do not catch `SecurityException` on `notify`
  (AGENTS.md notification rule 2). Pre-existing. The impact map decides whether the touched methods fix it.
- **S7** Compose UI-test dependency for gate tests (F19). (AMD-20) Withdrawn: the dependency already exists (F19,
  corrected). The id is kept for stability.

R-4 confirmation (AMD-15). The UX flags in `ux-flows.md` 12.3 R-4 all stay **out of scope**. None is specified,
designed or implied by an invariant:
- **S2** (re-confirmed) In-app Snooze/Dismiss for API 30 lock users. API 30 users with the lock on keep the stated
  loss (R9). They get no substitute surface.
- **S8** (AMD-9, G-9) A "last exported backup" indicator in PIN setup. It needs backup-history tracking. PIN setup
  reads no backup state (6.5).
- **S9** In-app erase of app data (a "Forgot PIN" shortcut). It would be a new destructive path with its own legal
  path. Recovery stays "clear data in Android settings" (INV-42).
- **S10** Inc 2 below API 30 (OD-18 option b). It re-adds the old-API test matrix. INV-43 keeps API ≥ 30.
- **S11** (AMD-12, G-12 alternative) PIN-only enabling on an API 30+ device with no screen lock, for example a shared
  tablet. It conflicts with the brief's settings table ("API 30+ with secure device; else disabled") and needs a new
  enable path. INV-50 keeps setup behind an enabled device-auth lock.

---

## Summary for orchestrator

- **Invariants:** 54 (43 + 9 added in stage 3b + 2 added by AMD-22). 1a: INV-1, INV-2, INV-4…INV-22, INV-44, INV-46.
  1b: INV-3, INV-23…INV-37, INV-45, INV-47, INV-48, INV-51, INV-52, INV-53, INV-54. 2: INV-38…INV-43, INV-49, INV-50.
  INV-51 and INV-54 also cover Inc 2.
- **Acceptance examples:** 121 (AE-1…AE-121; AE-85…AE-110 added in stage 3b, AE-111…AE-112 post-Gate-2, AE-113…AE-121
  by AMD-22). They cover all three increments, API 26–29 unavailability, and every fail-closed and escape path,
  including review-save, re-read recovery, interrupted PIN operations and open-origin prompts.
- **Plan-stage amendment (AMD-22, 2026-10-17):** the feature-planner request is adopted.
  - INV-26, INV-51 and 3.6 cover `AUTHENTICATING(UNLOCK)` only. ENABLE and CONFIRM_CHANGE prompts start from an open
    state and keep `MainScreen` composed (INV-53).
  - Outcomes and timer: a background during such a prompt is discarded after an unlock-equivalent success and handed
    to the timer otherwise (INV-54).
  - Host loss ends any attempt (T35). PIN confirmations are in-app steps, not prompts.
  - The legacy-notification fallback for the TECH-3 kind marker is now binding in 4.6.

  New ids: INV-53, INV-54, T32…T35, AE-113…AE-121, the event `PROMPT_HOST_LOST` and the field `promptBackgroundAt`.
- **Post-Gate-2 amendments (2026-10-17):** AMD-17…AMD-21.
  - AMD-17: the sixth kind `RECEIPT_MATCH` (NEW-1a).
  - AMD-18: Gate 2 outcomes recorded.
  - AMD-19: 4.3 points to `ux-flows.md` 9.4 for copy; the briefing title is "Your AI briefing is ready".
  - AMD-20: F19 corrected; localized constants outside the pure domain; translation gaps.
  - AMD-21: re-post kind detection, and the `BudgetMonitor` raw-id finding.

  No new invariants and no new transitions.
- **Stage 3b amendments:** AMD-1…AMD-16 close G-1…G-13, R-1 and R-4 (see "Amendments" at the top). Four small UX
  deltas follow for `ux-flows.md`:
  - a generic public pair under OD-21 (a) (2.7, 9.4);
  - PIN-mode turn-off copy that says the PIN is removed (2.2c, 3.3);
  - lock-on in the review card is not a separate enable flow, and is off-only while `INERT` (2.1);
  - the App PIN row is unavailable while `INERT` (2.1).
- **Schema impact: none.** Settings, PIN and lock state are device-local or process memory. `.costbackup` contains only
  the DB, receipts, manifest and checksums (verified). Restore never touches them. New diagnostic codes are TEXT. Inc 2's
  PIN store gains two additive, default-safe fields (`counterOrigin`, `biometricInvalidatedNoticePending`).
- **Open decisions (21):**
  - provisional OQ1–4 → OD-1…OD-4;
  - 1a/1b → OD-5…OD-12;
  - Inc 2 → OD-13…OD-18;
  - app-wide D2D → OD-19;
  - API 26–29 settings → OD-20;
  - public-version content (R-1) → OD-21.

  (AMD-18) All 21 were decided at Gate 2 on 2026-10-16: option (a), OD-14 as written, and OD-19 escalated to the
  release track. Still open, all technical and for the plan stage: TECH-1, TECH-2 and TECH-3 (13.1). AMD-22 adds no
  open decision.
- **Key findings beyond the brief:**
  - The exported LAUNCHER activity makes manifest changes non-protective, so the briefing-key "equals last delivered"
    rule is the real fix.
  - The lock gate must wrap `MainScreen` from outside, because `MainScreen` reads data before the operational-lock
    return.
  - The partner-enrols-a-fingerprint and D2D bypasses of Inc 2.
  - API 30 lock users lose Snooze/Dismiss entirely.
  - (AMD-17, AMD-21) Receipt auto-match was a sixth composition site that leaked the merchant (F22). `BudgetMonitor`
    posts with raw budget ids, so the id-range kind detection mislabels budget ids 10000–29999 (F23, TECH-3).
