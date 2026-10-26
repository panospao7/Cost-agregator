# Impact map: app-lock-privacy-mode

Author: `impact-mapper` (2026-10-16). Persisted by orchestrator.
Mapped against domain-spec INV-1..43 / OD-1..20 (pre-amendment text) and ux-flows.md. Source baseline: release candidate 0a9cab1c + docs-only commits; R16..R25 not landed (e.g. `NotificationListenerAccessChecker` (R17) absent). Spec facts F1..F21 spot-checked — all hold except F19 (§9).
Paths: `M/` = `app/src/main/java/com/yourname/expensetracker/`, `T/` = `app/src/test/java/com/yourname/expensetracker/`.

## Headline findings
1. **Sixth notification composition site omitted by brief and spec.** `M/service/receiptmatching/ReceiptMatchingWorker.kt:108` posts "Receipt Auto-Matched" via `notificationService.postBudgetAlert(...)`; message `receipt_matching_auto_matched_message_format` ("Receipt from %1$s matched…") with %1$s = `receipt.parsedMerchant` → merchant reaches the lock screen today. Same path as warranty → needs its own kind; `NotificationKind` has five values (INV-12, AE-13) → owner decision.
2. **`NotificationService` is non-suspend, but the policy read must be bounded by `T_read` and can fail.** `M/domain/service/NotificationService.kt:11-85` plain `fun`; `AndroidNotificationService` Context-only singleton (`:16-18`). INV-9 needs a bounded, possibly suspending read. Planner options: suspend interface (breaks ~9 fakes/mocks); resolve presentation in callers and pass down; non-suspend cached snapshot (fail-closed DISCREET until first read). `runBlocking` forbidden in worker code (LEGAL_PATHS Workers). Domain layer has no `R`; `DeliverProactiveBriefingNotificationUseCase.BRIEFING_NOTIFICATION_TITLE` is a literal (`:65`). Discreet constants must be localized (en/el/es) → data layer or string provider, not a pure domain `NotificationContentPolicy`.
3. **Spec F19 wrong.** `app/build.gradle.kts:357` `testImplementation(libs.androidx.ui.test.junit4)`, `:364` androidTest equivalent, `:371` `debugImplementation(ui.test.manifest)`. No `createComposeRule` test exists yet; no Hilt android-test setup. No new Gradle test dep needed; keep `AppLockGate` Hilt-free (state in, content slot) so `createComposeRule` works under Robolectric.
4. **OD-6 re-post cannot rebuild tap targets from ids.** _(Orchestrator note 2026-10-17: the "budget 1-9999" range below is wrong for budgets — `BudgetMonitor.kt:354,365,376 → :474` posts with raw `budget.id.toInt()`; see domain-spec F23 / TECH-3.)_ `M/domain/util/NotificationIdGenerator.kt` ids are range+hash (lossy). Re-post in place must read `NotificationManager.getActiveNotifications()` and reuse posted `contentIntent`/action PendingIntents; do not use `recoverBuilder` (copies detailed text/extras). Kind by channel + id range: budget 1-9999, warranty 10000-19999, receipt 20000-29999, bill 30000-39999 (budget/warranty/receipt share `budget_alerts` channel); anomaly and briefing by channel only.
5. **Pre-existing, confirmed (S6):** `AndroidNotificationService.kt:84,133,168` call `notify()` with no `SecurityException` handling; only `areNotificationsEnabled()` checked — AGENTS.md rule violation.
6. **Pre-existing PII log:** `M/domain/alerts/AnomalyAlertOrchestrator.kt:199-205` logs merchant + formatted amount via `Timber.i`; in a file this feature edits → FG-16 / G-PII-01.

## Increment 1a — notification policy + deep-link hardening

**Segments:** 11 Notifications (policy, `NotificationService`, `AndroidNotificationService`); 12/36/34/38 workers (`BillReminderWorker`, `WarrantyExpirationWorker`, `ReceiptMatchingWorker`); 20 AI (`DeliverProactiveBriefingNotificationUseCase`, new `MarkDashboardBriefingOpenedUseCase`); 28 proposed (`AppLockSettingsRepository` + store); navigation/UI shell (`MainActivity`, `ui/navigation/DeepLinkParser.kt`); 29 diagnostics (`DiagnosticReasonCode`). Segment 28 today = "Security & API Key Management" (`CODEBASE_SEGMENTS.md:554`) — adding lock/PIN owners is a doc change for architecture-guardian.

**Production files:**
- `M/domain/service/NotificationService.kt` — kind + presentation in signatures; `post*` wrappers `:60-85`; new `NotificationKind` (WARRANTY mandatory; receipt-match kind per headline 1); `NotificationContentPolicy` placed per headline 2.
- `M/data/service/AndroidNotificationService.kt` — builders `:74,122,157`: visibility PRIVATE + public version on every builder; drop `BigTextStyle` in DISCREET (`:126,161`); explicit-component PendingIntents at `:112,147,172` keeping data URI, flags, request codes (`mainActivityIntent` request code 0); `SecurityException` handling (S6); OD-6 re-post executor.
- `M/service/reminder/BillReminderWorker.kt` — only builder outside the service (`:253-261`); literal title `:105`, body `:185-193`; visibility, public version, hardcoded "Snooze"/"Dismiss" (`:259-260`) → resources; auth-required actions API 31+; constructor injection (`:34-42`); all edits inside `runGuardedWithContext` lambda (`:47-177`); keep claim/mark/`addNotificationsSent` order and `SecurityException` mapping (`:266`).
- `M/service/warranty/WarrantyExpirationWorker.kt` — `deliverReminder` `:218` stops posting as implicit budget alert, passes WARRANTY kind; keep DAO call sequence and method names (Guards).
- `M/service/receiptmatching/ReceiptMatchingWorker.kt:108` — same treatment.
- `M/domain/budget/BudgetMonitor.kt:474` (composition `:354-376`, `:462-472`) and `M/domain/alerts/AnomalyAlertOrchestrator.kt:193` — pass kind only.
- `M/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt:44` — pass kind; `targetKey` / lastDelivered logic (`:32-37`, `:54`) unchanged.
- `M/ui/MainActivity.kt` — `handleIntent` (`:170-251`) → `DeepLinkPolicy` + applier; no raw values in diagnostic (`:197-200`) or `Timber.w` (`:245`); `activity` lookup failure degrades (INV-22); `onCreate` consume `:117-124`, `onNewIntent` `:146-155`, TODO PRV-16 `:157-169`.
- `M/ui/navigation/DeepLinkParser.kt` → pure `DeepLinkPolicy`; `parseDeepLink` has no production caller (only `DeepLinkParserTest`); `RequireConfirmation` can go (S5).
- `M/ui/screens/analytics/AnalyticsScreen.kt:1841-1851` — extract period allowlist to share.
- New `MarkDashboardBriefingOpenedUseCase` + read in `domain/ai/service/AiEngagementRepository.kt`; `setLastOpenedDashboardBriefingKey` called raw at `MainActivity.kt:194`; `AiEngagementRepositoryImpl` is DataStore (`ai_engagement`), not Room.
- `M/domain/diagnostics/DiagnosticReasonCode.kt` + emitter for NOTIF_*, DL_*, lock codes; `AppPipeline.PRIVACY` exists; reason code persisted as TEXT (`DiagnosticEventWriter.kt:55`); only `GlobalDurableDiagnosticsGoldenTest.kt:174-184` inspects the enum ("required names exist") → adding values safe.
- New `AppLockSettingsRepository` (OD-11a), own DataStore with corruption sentinel mirroring `data/privacy/PrivacySettingsRepositoryImpl.kt:34-48,186-209`; dedicated store avoids `PrivacySettings` / `FAIL_CLOSED_DEFAULTS` / `PrivacyRuntimeWorkerPolicy` coupling (`:160-184`); DI in `di/PrivacyModule.kt` or new module.
- Settings UI: `ui/screens/privacysettings/PrivacySettingsScreen.kt` + `PrivacySettingsViewModel.kt` discreet row (1b adds lock rows); VM has `repository.observeLoadState()` at `:43` but doesn't render the corruption warning.

**Manifest/resources/build/tests:**
- Manifest `app/src/main/AndroidManifest.xml:74-86`: remove `BROWSABLE` (`:77`); keep VIEW filter (OD-3a); `MainActivity` stays exported via LAUNCHER (`:66-73`).
- Strings en/el/es for discreet constants, action labels, settings row (lint MissingTranslation).
- Tests modified, never deleted: `T/ui/navigation/DeepLinkParserTest.kt` (`RequireConfirmation` `:34-54`, `:91`); `T/data/service/AndroidNotificationServiceTest.kt`; `T/service/reminder/BillReminderWorkerTest.kt` (hand-built worker `:104`, Robolectric sdk 28, mocks `NotificationManagerCompat`); `T/service/warranty/WarrantyExpirationWorkerTest.kt`; `T/service/receiptmatching/ReceiptMatchingWorkerTest.kt`; `T/domain/budget/BudgetMonitorTest.kt`, `BudgetMonitorStressTest.kt`, `T/e2e/BudgetAlertPipelineTest.kt`; `T/domain/alerts/AnomalyAlertOrchestratorTest.kt`; `T/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt`; `T/architecture/SourceScanningArchitectureGuardTest.kt` (notification-worker rules `:392-432`, negative fixtures `:627-641` key on `sendBudgetAlert` and `requiresNotificationPermission = true`); possibly `T/data/service/*`.
- New tests: policy decision table (Robolectric 4.11.1 caps SDK 34 → SDK 26, 28-31, 34 possible; no 35); `DeepLinkPolicy`; `MarkDashboardBriefingOpenedUseCase`; `AppLockSettingsRepository` load states (mirror `PrivacySettingsRepositoryImplCorruptionTest`); PII sentinel over notification text fields/extras; PendingIntent explicit-component + immutable (AE-32).
- Docs: `docs/architecture/NAVIGATION_ARCHITECTURE.md`, `UI_REFERENCE_INDEX.md`, `COMPREHENSIVE_UI_MAP.md`, `CODEBASE_INVENTORY.md` (scheme mentions); `LEGAL_PATHS.md`.

## Increment 1b — app lock + forced secure screen
- New `AppLockController` (process singleton), `DeviceAuthCapability`, `AppLockGate`; clock and boot-count behind interfaces. `lifecycle-process` 2.8.7 present; `AppStartupCoordinator.kt:702` registers a `ProcessLifecycleOwner` observer. ON_STOP dispatched ~700 ms after last activity stops and absorbs config changes (helps INV-25) — planner must account for the delay vs `IMMEDIATE` and `S30`.
- `M/ui/MainActivity.kt`: host change (§7); wrap `MainScreen` from outside (call `:134`). `MainScreen` composes `ReviewViewModel` (`:368`), visual-split DB lookup (`:383-386`), navigation collector (`:394-410`), `LaunchedEffect(Unit)` POST_NOTIFICATIONS (`:471-478`), clipboard in add overlay (`:618-621`), SmartFAB listener (`:920-957`); operational lock early-returns at `:425-461` after all except clipboard → gate must sit outside `MainScreen`.
- `FLAG_SECURE` on window before `setContent`; none of `FLAG_SECURE`/`SecureFlagPolicy`/`securePolicy` exists today; no platform `Dialog`/`DatePickerDialog`/`PopupWindow`/`Toast` imports → Compose windows inherit by default.
- Navigation state: `ProvideNavigationController` (`NavigationController.kt:472-508`) holds `rememberSaveable` outside `MainScreen` → OD-9(a) works if gate sits inside it; outside loses nav state. `MainScreen`'s own `rememberSaveable` (`activeTransactionFilter`, `showNotificationPermissionDialog`) lost on removal unless `SaveableStateHolder`.
- Held link vs `MainViewModel.navigateTo`: channel `MainViewModel.kt:46-49` is buffered and delivered when `MainScreen` composes → a link would apply even though it should be held.
- Keep `MainActivity.kt` home of Gson `PersistedVisualSplit` consumer (`GsonR8KeepRulesContractTest.kt:105`).
- `PrivacySettingsScreen/ViewModel`: lock rows, timeout picker, banners, dialogs. `AndroidNotificationService` + `BillReminderWorker`: lock terms of policy (forced DISCREET, API 30 action suppression). Diagnostics lock codes.
- Build: add `androidx.biometric` — 1.1.0 stable (combined authenticators API 30+ only) vs 1.2.0-alpha05 (repo already carries `security-crypto:1.1.0-alpha06` `:278`, R13). No `verification-metadata.xml`.
- Manifest: `USE_BIOMETRIC` merged; `USE_FINGERPRINT` with `maxSdkVersion` likely merged — verify merged manifest, strip with `tools:node="remove"`. Permission list only captured as evidence by `scripts/ci/release_artifact_evidence.py:142-152`; FG-22 enforced by review/evidence.
- Strings en/el/es for `applock_*` (existing `privacy_*` lack el/es today).
- Tests: `AppLockController` T1-T25 + illegal transitions (3.3); repository; gate composition via `createComposeRule` (content slot composed zero times, no `ReviewViewModel`); policy tests with `appLockEnabled`; INV-36 source-scanning test (no worker/receiver/service injects `AppLockController`).

## Increment 2 — app PIN
- `AppPinStore` (Segment 28): salted PBKDF2-class verifier bound to a Keystore key; persisted attempt state; boot-count source. PIN pad + setup/change/remove/forgot in `ui/screens/privacysettings/` or new `ui/screens/applock/`. Biometric `CryptoObject` (OD-16a). `AppLockController` PIN substates (T23-T25).
- Do not reuse `SecureKeyStorage` (`data/security/SecureKeyStorage.kt:48-55`, `EncryptedSharedPreferences`); R13: don't extend `security-crypto` without a decision.
- HZ-06 F1 (`release-1.0-phase3-4-findings-ledger.md:100-101`): D2D can copy Room DB and preferences but not Keystore keys → copied Keystore-bound verifier unreadable → OD-15a hard lock, clear-data recovery only; transferred lock setting hits INERT (AE-54).
- Tests: unit (constant-time compare, durable-before-result, delay math across kill/reboot, unreadable states); device lane for Keystore binding + enrolment invalidation (not JVM-testable alone).

## 2. Legal paths and coordinators

| Operation | Path | Status |
|---|---|---|
| Notification posting (budget, warranty, receipt-match, anomaly, briefing) | worker/coordinator decides when → `NotificationService` impl → `AndroidNotificationService` | existing; **new documented legal path** `NOTIFICATION PRESENTATION` |
| Bill notification build | `BillReminderWorker.sendNotification` (`:216-274`) | existing; second builder owner; apply-only |
| Bill Snooze/Dismiss | `SnoozeReminderReceiver`/`DismissReminderReceiver` → one-shot workers via `WorkerExecutionGuard` → `RecurringLifecycleCoordinator` | unchanged; only action PendingIntent presentation changes; no in-app Snooze/Dismiss exists |
| Deep-link parse/apply | `MainActivity` → `DeepLinkPolicy` → `AppLockController` hold/apply → `MainViewModel` | new documented path `DEEP LINKS` |
| `lastOpenedDashboardBriefingKey` | `MarkDashboardBriefingOpenedUseCase` → `AiEngagementRepository` (DataStore) | only persistent write reachable from a link; no coordinator today |
| Briefing delivery / `briefingKey` | `DailyBriefingWorker:108` → use case; `HomeViewModel.kt:284` uses lastOpened | semantics unchanged by 1a |
| Lock settings persistence | `AppLockSettingsRepository` (single writer) | new |
| Diagnostics | `DiagnosticEventWriter.emit` (`RoomDiagnosticEventWriter`) | existing; new codes only |
| Lock state | `AppLockController` (memory) | new documented path `APP LOCK` |

No expense/receipt/recurring lifecycle write introduced or altered; no coordinator change. New paths need `LEGAL_PATHS.md` entries (spec 8.2). LEGAL_PATHS Anomaly Alerting names non-existent `NotificationService.send()` (already stale).

## 3. Workers and rules
- Touched: `BillReminderWorker`, `WarrantyExpirationWorker`, `ReceiptMatchingWorker` (edit); `DailyBriefingWorker` via use case (no direct edit). Not touched: capture service, Snooze/Dismiss action workers, receivers, `BootReceiver`.
- Budget/anomaly posts come from `TransactionSideEffectPlanner.kt:182,218,451` (capture/lifecycle post-commit path) — consistent with INV-36, but the policy settings read runs there too.
- At stake: edits inside `WorkerExecutionGuard` lambda; no `WorkerGuardRequest` field change (`requiresNotificationPermission = true` bill/warranty; optional-with-local-check receipt matching); policy reads no Room; permission gate unchanged; `SecurityException` mapping kept in bill worker, added at the three service `notify()` sites; `addNotificationsSent()` only after real post; DISCREET fallback never changes post/no-post, `DeliveryResult`, or claim/mark order (`markReminderSent`, `markSentFromClaimed`, `markFailed`); rethrow `CancellationException` before broad catch; no `runBlocking`.

## 4. Schema / Room
**Confirmed: no Room schema change; room-migration-guardian not required.**
- `.costbackup` = `manifest.json`, `checksums.json`, `database.sqlite`, `files/receipts/` (`M/data/backup/CostbackupBundle.kt:21-26`); no DataStore/SharedPreferences.
- DB snapshot includes `PipelineDiagnosticEvent` → lock/deep-link diagnostic rows (codes/enums only) travel in exported backups — flag for privacy-security-guardian.
- New reason-code names are TEXT in an existing column. Restore/DB reset never touch DataStore files.
- Migration only if OD-12 moved to a Room table or rate-limit state persisted in Room (spec does neither).

## 5. FG guards
- **FG-07:** `config/baselines/cancellation.json:38` fingerprints `AnomalyAlertOrchestrator.kt` (G-CANCEL-02); a new broad catch = finding in a baselined file (FG-07 test 10).
- **FG-10:** `catch (CancellationException)` before any broad catch in policy, bill, warranty, receipt-matching edits.
- **FG-11:** `scripts/verify_worker_boundaries.py` (G-WORKER-01), `T/architecture/WorkerGuardArchitectureGuardTest.kt`; `scripts/allowlists/worker_allowlist.yml:50-71` symbol entries for `BillReminderWorker.broadCatch`, `WarrantyExpirationWorker.daoMutation`/`.broadCatch` (expire 2027-01-01) — new catches under these symbols silently covered; review anyway.
- **FG-12:** gate/settings UI via repositories; `config/db_access_allowlist.yml:239` already permits `BillReminderWorker` `pipelineDiagnosticEventDao` insert.
- **FG-13:** `config/guards/db_ownership_policy.yml` path+method rows for `WarrantyExpirationWorker.deliverReminder` — keep DAO sequence and method names; rename needs sanctioned amendment + owner approval.
- **FG-14:** `PipelineDiagnosticEvent` constructed only in `RoomDiagnosticEventWriter`; no new construction site.
- **FG-16:** `scripts/verify_pii_logging_boundaries.py` (G-PII-01); allowlist `scripts/allowlists/pii_logging_allowlist.yml` empty; new code logs class names only; sentinel tests.
- **FG-18:** `scripts/allowlists/di_release_allowlist.yml` if new DI module/binding.
- **FG-19/FG-20:** no `@Ignore`; lint MissingTranslation with `app/lint-baseline.xml`.
- **FG-22:** exported components and permission policy (`BROWSABLE` removal, `USE_BIOMETRIC`); runtime proof "safe deep links do not crash, invalid fail safely" directly on this feature.
- **FG-23/FG-02/FG-05:** only if spec 8.3 guards adopted; guard registry additions frozen ("guard hardening deferred to 2026-12-01") → explicit owner approval.
- **FG-26:** docs traceability for new LEGAL_PATHS / SEGMENTS sections.

**Owner-approval items:** any guard/baseline/allowlist addition (incl. 8.3 guards); `db_ownership_policy` amendment if `deliverReminder` changes shape; exhaustive `GlobalDurableDiagnosticsGoldenTest` change; sixth notification kind; `NotificationService` contract change.
**Pinned tests:** `T/architecture/GsonR8KeepRulesContractTest.kt:97-107` pins `MainActivity.kt` in `KNOWN_GSON_CONSUMER_FILES` (`PersistedVisualSplit`, `MainActivity.kt:254-257,293`) — splitting `MainActivity.kt` must keep the consumer there or change the list with owner approval (R15). `DeepLinkParserTest` etc. modified, never deleted.

## 6. Collision map with Release 1.0 batches R16..R25
Detailed plans exist only for R16/R17 (remediation plan §21 `:451-513`); R18..R25 listed without file plans.

| File / area | Batch & evidence | Collision |
|---|---|---|
| `M/ui/MainActivity.kt` | R24 POST_NOTIFICATIONS re-prompt (`:471-494`); R18 HZ-03 F1 restore lock (R18 or 1.0.1); R19 tile hides (`:732-736`, `:857-867`); R17 references `:947` pattern only | **High** (R24 certain; R18/R19 possible) |
| `PrivacySettingsScreen.kt` / `ViewModel` | R17 card + "Manage access"; R23 part 1 exchange-rate network setting (screen, VM, `PrivacySettings`) | **High** on screen; dedicated store (OD-11a) avoids `PrivacySettings` collision with R23 |
| `M/ui/navigation/FeatureConfig.kt` (`:236-241`) | R19 privacy tile label + tile hiding | Medium (tile = ux entry E1) |
| `M/ui/screens/home/HomeScreen.kt` | R17 Home card | None unless inert banner moves to Home |
| `M/ui/screens/home/HomeViewModel.kt` | R18 (`:557-567`), R20 | None expected (`:284` read-only) |
| `BillReminderWorker.kt` + test | R21 V-C3 (SKIP_SUCCESS policy at guard request `:47-56`), V-C1/V-C2 | **High** — same file and test |
| `AndroidNotificationService`, `WarrantyExpirationWorker`, `ReceiptMatchingWorker`, `AnomalyAlertOrchestrator`, `BudgetMonitor` | none listed (R24 touches `WarrantyTrackerViewModel` only) | Low |
| Briefing delivery use case | R21 HZ-05 F1 scheduling files, not the use case; shared `DailyBriefingWorkerTest` | Low |
| `strings.xml` en/el/es | R17, R19, R23, R24 | textual merge conflicts |
| `AndroidManifest.xml` | R17 "stop if manifest changes"; C5 would touch `<application>` | Low |
| `app/build.gradle.kts` | conditional MockWebServer androidTest dep | Low |

**Sequencing:** implementation waits for Release 1.0 acceptance and R17/R21/R24; must not land on the release line. Order: (1) R17, R21, R24 (+R19, R23, R18 if same files); (2) Inc 1a — after R21 (`BillReminderWorker` + test); (3) Inc 1b — after R24, R17, R19, R23 (`MainActivity`, Privacy settings screen); (4) Inc 2. Start code from the post-R24 tip of the release line, not from the current docs branch.

**Advisories:**
- **C4** untriaged; nearest release item V-I-10 (P3, unauthenticated deep links, `findings-ledger.md:377`) deferred to 1.0.x. Under spec 5.8 Inc 1a delivers all of it (map assumes so). If promoted to a release fix → `MainActivity.kt:197-200` edit colliding with R24; shrinks 1a.
- **C5** independently found as HZ-06 F1 (`findings-ledger.md:100-101`), P3 NEEDS_RUNTIME, deferred to 1.0.x, open. App-wide `dataExtractionRules` decision (OD-19/S3), outside this feature; materially affects Inc 2 value. If it lands first, OD-19 → (a).

## 7. MainActivity host: ComponentActivity → FragmentActivity
- Current: `class MainActivity : ComponentActivity()` (`MainActivity.kt:92`), `@AndroidEntryPoint`; no `launchMode`/`configChanges` (`:66-87`); theme `Theme.Material3.DayNight.NoActionBar` (`res/values/themes.xml:3`); `RescueActivity : AppCompatActivity` (`data/rescue/RescueActivity.kt:18`) shows appcompat/fragment on classpath via `material:1.12.0`.
- Blast radius low technically, high contention: one declaration + import; referenced only by manifest, KDoc in `BackupRestoreViewModel.kt:335` and `NotificationCaptureService.kt:94`, and `GsonR8KeepRulesContractTest.kt:105`; no test instantiates it. `FragmentActivity` extends `ComponentActivity` — `enableEdgeToEdge()`, `setContent`, `viewModels()`, `BackHandler`, `rememberLauncherForActivityResult` keep working; Hilt supports it. Fragment ~1.3.6 via appcompat/material alongside activity 1.9.3 — confirm resolved versions after adding `androidx.biometric`.
- Alternatives: (1) `FragmentActivity` — minimal, recommended if androidx prompt used; (2) `AppCompatActivity` — more surface, no gain; (3) **platform `android.hardware.biometrics.BiometricPrompt`** (API 28+, `setAllowedAuthenticators` 30+) — lock is API 30+ only, so no `FragmentActivity`, no `androidx.biometric`, no `USE_FINGERPRINT` merge; trade-offs: no androidx OEM-quirk workarounds, manual `CancellationSignal` across recreation, fewer test shadows; not discussed in brief/spec → architecture + privacy guardians decide; (4) separate translucent auth Activity — new component, new legal path, `onPause`-only timer (spec R1 human decision).
- Risk: type change low; real risks are merge contention, unlock/recreation handling of prompt callback (attemptId rule), R15 Gson pin if file split.

## 8. Risk tier, guardians, validation

| Increment | Tier | Why |
|---|---|---|
| 1a | **T3** | workers + notification privacy surface; cross-segment contract change (`NotificationService`, settings repo); deep-link trust boundary; manifest change; guard-adjacent files with allowlists/policy rows |
| 1b | **T3** | permissions/privacy (biometric); `MainActivity` host change in most contended file; fail-closed lock state machine; new dependency; FG-22 permission evidence |
| 2 | **T3** | secret storage/Keystore, brute-force limiting, unrecoverable-lockout design, D2D interaction |

All three: planner-first; `reviewer-strict`; `architecture-guardian`; `privacy-security-guardian`; `room-migration-guardian` not required.

Validation (validation-runner, serial; `targeted-unit-test` one `--tests` pattern per run):
- 1a: `*NotificationContentPolicy*`, `*AndroidNotificationService*`, `*BillReminderWorker*`, `*WarrantyExpirationWorker*`, `*ReceiptMatchingWorker*`, `*AnomalyAlertOrchestrator*`, `*BudgetMonitor*`, `*BudgetAlertPipeline*`, `*DeliverProactiveBriefing*`, `*DeepLink*`, `*MarkDashboardBriefingOpened*`, `*AppLockSettingsRepository*`, `*SourceScanningArchitectureGuardTest*`, `*WorkerGuardArchitectureGuardTest*`, `*GsonR8KeepRulesContractTest*`.
- 1b: `*AppLockController*`, `*AppLockGate*`, `*PrivacySettings*`. 2: `*AppPinStore*`.
- Per increment: `unit-tests`, `static-guards`, `app-check` (lint MissingTranslation), `assemble-release` + `release-artifact-evidence` for manifest/permission changes.
- Device lanes (`connected-test-class`, `-DeviceSerial`): existing AVD `wave5-api35` (API 35), approved API 26 AVD, owner's spare phone (OS unrecorded). API 30/31/33/34 images **not approved** — spec's device matrix needs new owner approval. Manual: browser-fired link (AE-16), FLAG_SECURE on recents/casting/dialogs, OEM credential UIs, re-lock with system UI, post-restore restart.
- Robolectric 4.11.1 (max SDK 34) for policy and gate unit tests.

## 9. Spec conflicts and open decisions
**Conflicts with source:**
1. F19: Compose UI-test dependency already exists.
2. Five kinds vs six composition sites (receipt matching) — INV-12, AE-13, AE-5-style tests need the extra kind.
3. Spec 4.1 puts discreet constants in domain `NotificationContentPolicy`, but domain has no resources and `NotificationService` is non-suspend.
4. Briefing discreet title: spec "Your briefing is ready" vs ux-flows "Your AI briefing is ready" (ux owns copy; documents disagree).
5. F16/INV-10: bill notification has no content intent (`BillReminderWorker.kt:253-261`) — confirmed; action labels hardcoded English (`:259-260`).
6. INV-13/OD-6: re-post not derivable from ids; depends on `getActiveNotifications()`.
7. LEGAL_PATHS Anomaly Alerting names non-existent `NotificationService.send()` (`:938`).
8. Spec 8.1 places `AppLockController` + settings in Segment 28 (today key storage + privacy policy); UI shell has no segment owner (`MainActivity` in no segment).
9. UX gaps G-1..G-13 for the pending spec amendment (G-3, G-12 would change capability and diagnostic code lists).

**Open decisions that change the map:** OD-3 (keeping VIEW filter keeps already-posted implicit PendingIntents working; removing breaks them); OD-5 (unconditional auth on API 31+ = visible change for all users, R-2); OD-6/S1 (re-post executor via `getActiveNotifications()`); OD-9 (gate inside `ProvideNavigationController` or `SaveableStateHolder`); OD-11/OD-12 (dedicated DataStore avoids `PrivacySettings`/R23; durable diagnostic sink is Room-backed, denied during restore; lock-cause codes ride in exported backups); OD-16/OD-15/OD-19 (Keystore + D2D; OD-19 independently flagged by release assurance, unresolved); OD-20 (settings gating only); dependency choice (androidx.biometric version vs platform prompt, §7); **new decision not in spec: sixth notification kind for receipt matching.**

Key files: `M/ui/MainActivity.kt`, `M/ui/MainViewModel.kt`, `M/ui/navigation/DeepLinkParser.kt`, `M/ui/navigation/NavigationController.kt`, `M/domain/service/NotificationService.kt`, `M/data/service/AndroidNotificationService.kt`, `M/service/reminder/BillReminderWorker.kt`, `M/service/warranty/WarrantyExpirationWorker.kt`, `M/service/receiptmatching/ReceiptMatchingWorker.kt`, `M/data/privacy/PrivacySettingsRepositoryImpl.kt`, `M/data/repository/AiEngagementRepositoryImpl.kt`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `config/baselines/cancellation.json`, `scripts/allowlists/worker_allowlist.yml`, `T/architecture/GsonR8KeepRulesContractTest.kt`, `T/ui/navigation/DeepLinkParserTest.kt`.
