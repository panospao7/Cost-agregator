# Settings / Privacy / Permissions — Cross-Check Review

**Analysis reviewed:** `settings-privacy-permissions-analysis.md`  
**Branch under review:** current worktree  
**Review date:** 2026-05-02  

---

## VERDICT: FAIL

Significant progress has been made — a full privacy-gate architecture with `PrivacyGate`, `PrivacyCapability`, `PrivacyDecision`, `PrivacySettings`, and dedicated gate implementations now exists. However, several issues remain unresolved or partially resolved, and the receivers/startup paths still bypass privacy checks.

---

# Issue Status Summary

| # | Issue | Status |
|---|-------|--------|
| 1 | Notification capture lacks app-level privacy gate | PARTIALLY RESOLVED |
| 2 | Finance-app notifications captured unconditionally | STILL PRESENT |
| 3 | Notification posting vs reading permission confusion | STILL PRESENT |
| 4 | AI settings defaults inconsistent | RESOLVED |
| 5 | AI settings allow contradictory states | PARTIALLY RESOLVED |
| 6 | Disabling cloud AI doesn't handle stored API keys | PARTIALLY RESOLVED |
| 7 | Cloud AI lacks hard provider-side gate | RESOLVED |
| 8 | Location/geocoding lacks external-service consent | RESOLVED |
| 9 | Background workers not consistently synced | STILL PRESENT |
| 10 | Foreground service type `location` on notification service | STILL PRESENT |
| 11 | POST_NOTIFICATIONS on first launch, not JIT | STILL PRESENT |
| 12 | Raw notification data retention not user-controlled | RESOLVED |
| 13 | Backups can include sensitive raw data | RESOLVED |
| 14 | DataStore corruption resets AI settings silently | STILL PRESENT |
| 15 | Conversation history toggle lacks purge semantics | PARTIALLY RESOLVED |
| 16 | Deep links exported through custom scheme | STILL PRESENT |
| **N1** | Photon/Geoapify/GooglePlaces bypass privacy gate | **NEW — MAJOR** |
| **N2** | `saveApiKey()` blank-input deletes key without user intent | **NEW — MINOR** |

---

# Detailed Findings

## [ISSUE-1] PARTIALLY RESOLVED — Notification capture privacy gate

**Status:** The `NotificationPrivacyGate` now exists and `NotificationCaptureService.onNotificationPosted()` and `processNotificationBypassDedupe()` both call `privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE)`. `PrivacySettings.notificationCaptureEnabled` defaults to `false`. `PrivacySettingsScreen` provides a master toggle.

**Remaining gaps:**
- `BootReceiver.kt` starts the service unconditionally (line 20: `context.startForegroundService(serviceIntent)`) without checking `privacyGate`.
- `ServiceRestartReceiver.kt` does the same (line 17).
- `NotificationCaptureService.onCreate()` schedules the restart alarm (line 153) and `onStartCommand()` starts the foreground (line 199) before any gate check — the service itself always launches.
- The gate check is inside the `workTracker.launch(serviceScope)` coroutine (line 320), meaning the notification has already been deduplicated before the check. If the gate denies, the notification was still parsed for hash computation.

**Files:** `BootReceiver.kt`, `ServiceRestartReceiver.kt`, `NotificationCaptureService.kt`

---

## [ISSUE-2] STILL PRESENT — Finance-app notifications captured unconditionally

**Status:** `NotificationFilter.kt` lines 13–22: `FINANCE_PACKAGES` still contains 8 banking/finance apps, and line 84 returns `true` unconditionally for all of them. No deny-keyword list (OTP, login, verify, code, balance, etc.) has been added. No per-package "capture all from this app" advanced setting exists.

**Risk:** Any notification from Revolut, Eurobank, NBG, etc. — including OTP codes, login verifications, account balances, marketing — is captured and stored as `RawNotification` with full title/text/bigText.

**File:** `NotificationFilter.kt`

---

## [ISSUE-3] STILL PRESENT — Notification posting vs reading permission confusion

**Status:** `MainActivity.kt` lines 416–423 still only prompt for `POST_NOTIFICATIONS` on first launch. `NotificationPermissionDialog.kt` exclusively handles `POST_NOTIFICATIONS`. There is no dedicated onboarding flow for the notification-listener system permission, no explanation that denying POST_NOTIFICATIONS doesn't affect notification reading, and no in-app button to open the Android notification-listener settings page.

**Files:** `MainActivity.kt`, `NotificationPermissionDialog.kt`

---

## [ISSUE-4] RESOLVED — AI settings defaults inconsistent

**Status:** `AiModels.kt` `AiSettings` model defaults now match the DataStore fallback defaults in `AiSettingsRepositoryImpl.kt`:
- `receiptAssistEnabled`: `false` (was `true`)
- `receiptImageCloudEnabled`: `false` (was `true`)
- All other defaults are consistent across model and DataStore fallback.
- `AiSettingsUiState` defaults to `AiSettings()` which matches.

**Files:** `AiModels.kt`, `AiSettingsRepositoryImpl.kt`

---

## [ISSUE-5] PARTIALLY RESOLVED — AI settings allow contradictory states

**Status:** The `AiSettingsViewModel` setter methods (e.g., `setAllowCloudAi()`, `setReceiptImageCloudEnabled()`) still toggle each setting independently with no cross-field validation. However, the `CloudAiPrivacyGate` now enforces runtime invariants:
- If `cloudAiEnabled` is false → receipt image cloud upload is denied.
- If `redactBeforeCloud` is true → receipt image cloud upload is denied (images can't be meaningfully redacted).

**Remaining gaps:** The UI still allows contradictory combinations (e.g., cloud disabled + receipt image cloud enabled). There is no guard UI that disables dependent controls.

**Files:** `AiSettingsViewModel.kt`, `AiSettingsScreen.kt`, `CloudAiPrivacyGate.kt`

---

## [ISSUE-6] PARTIALLY RESOLVED — Disabling cloud AI doesn't handle stored API keys

**Status:** `AiSettingsViewModel.saveApiKey()` now requires a successful connection test before saving a new API key (lines 143–150). This is a good improvement. However:
- Blank input still deletes the stored key via `secureKeyStorage.deleteKey()` (lines 132–141).
- There is no explicit "Remove key" button.
- The UI does not indicate that a stored key remains when cloud AI is disabled.

**File:** `AiSettingsViewModel.kt`

---

## [ISSUE-7] RESOLVED — Cloud AI lacks hard provider-side settings gate

**Status:** `CloudAiPrivacyGate` exists and is wired into the `CompositePrivacyGate` via `PrivacyModule.kt`. It guards `CLOUD_AI_RECEIPT_ASSIST`, `CLOUD_AI_ITEM_CATEGORIZATION`, `CLOUD_AI_WARRANTY_EXTRACTION`, `CLOUD_AI_DAILY_BRIEFING`, `CLOUD_AI_GENERAL`, and `RECEIPT_IMAGE_CLOUD_UPLOAD`. All cloud providers can check via `privacyGate.check()`.

**Files:** `CloudAiPrivacyGate.kt`, `PrivacyModule.kt`

---

## [ISSUE-8] RESOLVED — Location/geocoding lacks external-service consent gate

**Status:** `LocationPrivacyGate` exists and guards `EXTERNAL_GEOCODING`, `BACKGROUND_LOCATION_BACKFILL`, `DEVICE_GPS_LOCATION`, and `OVERPASS_API`. The following services check it:
- `NominatimGeocodingService` — checks before all three methods (`search`, `searchMultiple`, `reverseGeocode`)
- `LocationBackfillWorker` — checks `BACKGROUND_LOCATION_BACKFILL`
- `OverpassNearbyService` — checks `OVERPASS_API`
- `PrivacySettingsScreen` provides separate toggles for each capability

**Files:** `LocationPrivacyGate.kt`, `NominatimGeocodingService.kt`, `LocationBackfillWorker.kt`, `OverpassNearbyService.kt`, `PrivacySettingsScreen.kt`

---

## [ISSUE-9] STILL PRESENT — Background workers not consistently synced

**Status:** Only `SyncProactiveBriefingWorkUseCase` exists and is called when AI-enabled, dashboard-briefing-enabled, or proactive-briefings change. No sync use cases exist for:
- Cloud AI settings changes (`allowCloudAi`, `wifiOnlyForCloud`)
- Preferred mode changes
- On-device AI changes
- Redaction changes
- Notification capture changes
- Location/geocoding setting changes
- Data retention setting changes

**Files:** `AiSettingsViewModel.kt`, `SyncProactiveBriefingWorkUseCase.kt`

---

## [ISSUE-10] STILL PRESENT — Foreground service type `location` on notification service

**Status:** `AndroidManifest.xml` line 81: `android:foregroundServiceType="dataSync|location"`. `NotificationCaptureService.kt` lines 239–241: starts foreground with `DATA_SYNC | LOCATION` on Android 14+. The comment says this is required for reading location from a foreground service, but it broadens the permission surface.

**Files:** `AndroidManifest.xml`, `NotificationCaptureService.kt`

---

## [ISSUE-11] STILL PRESENT — POST_NOTIFICATIONS on first launch, not JIT

**Status:** `MainActivity.kt` lines 416–423 still trigger the POST_NOTIFICATIONS permission dialog on first launch via `LaunchedEffect(Unit)`. No just-in-time approach.

**File:** `MainActivity.kt`

---

## [ISSUE-12] RESOLVED — Raw notification data retention user-controlled

**Status:** `PrivacySettings` includes `rawNotificationRetentionDays` (default 30) and `rawOcrRetentionDays` (default 30). `DataRetentionWorker` runs daily and purges old data. `PrivacySettingsScreen` provides sliders for both retention values. `ExportAnonymizer` strips raw data from exports. Audit events are logged for each purge operation.

**Files:** `PrivacySettings.kt`, `DataRetentionWorker.kt`, `PrivacySettingsScreen.kt`, `ExportAnonymizer.kt`

---

## [ISSUE-13] RESOLVED — Backups can include sensitive raw data

**Status:** `BackupEncryptionService` provides AES-256-GCM encryption with PBKDF2 key derivation. `ExportAnonymizer` strips raw OCR text and notification content from export copies. `DatabaseBackupRepositoryImpl` checks `BackupPrivacyGate` before export. `.costbackup` format supports redacted/password-protected bundles. `PrivacySettings.encryptedBackupEnabled` defaults to `true`.

**Files:** `BackupEncryptionService.kt`, `ExportAnonymizer.kt`, `DatabaseBackupRepositoryImpl.kt`, `BackupPrivacyGate.kt`

---

## [ISSUE-14] STILL PRESENT — DataStore corruption resets AI settings silently

**Status:** `AiSettingsRepositoryImpl.kt` line 26: `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }`. Empty preferences map to defaults including `aiEnabled = true`. No fail-closed behavior, no warning to user.

**File:** `AiSettingsRepositoryImpl.kt`

---

## [ISSUE-15] PARTIALLY RESOLVED — Conversation history toggle lacks purge semantics

**Status:** `AssistantViewModel.clearAllHistory()` exists (line 305) and delegates to `AiChatRepository.clearAllHistory()`. The AssistantSheet UI has a clear-history button (DeleteSweep icon). However, the AI settings screen (`AiSettingsScreen.kt`) does not expose "Delete AI conversation history", "Delete AI artifacts", or "View stored AI data" actions. The toggle only controls future persistence; users cannot purge old data from the settings page.

**Files:** `AiSettingsScreen.kt`, `AssistantViewModel.kt`

---

## [ISSUE-16] STILL PRESENT — Deep links exported through custom scheme

**Status:** `AndroidManifest.xml` lines 62–74: `expensetracker://` scheme is broadly exported with no authentication or user confirmation for sensitive deep links (e.g., `expensetracker://activity?expenseId=...`).

**File:** `AndroidManifest.xml`

---

## [ISSUE-N1] NEW — MAJOR — Photon/Geoapify/GooglePlaces bypass privacy gate

**Finding:** Only `NominatimGeocodingService` and `OverpassNearbyService` check the privacy gate before making external HTTP calls. `PhotonGeocodingService`, `GeoapifyGeocodingService`, and `GooglePlacesGeocodingService` do **not** check `privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)`. When `CompositeGeocodingService.searchMultiple()` fires them in parallel (or when they are used as fallback providers in `search()`), merchant names can be sent to Photon, Geoapify, or Google Places even when the user has disabled external geocoding.

**Fix:** Add `privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)` at the entry of `search()` and `searchMultiple()` in `PhotonGeocodingService`, `GeoapifyGeocodingService`, and `GooglePlacesGeocodingService`.

**Files:** `PhotonGeocodingService.kt`, `GeoapifyGeocodingService.kt`, `GooglePlacesGeocodingService.kt`

---

## [ISSUE-N2] NEW — MINOR — Blank API-key input deletes key without confirmation

**Finding:** `AiSettingsViewModel.saveApiKey()` lines 132–141: when the API key input is blank, `secureKeyStorage.deleteKey()` is called immediately with no confirmation dialog. Combined with the fact that the input field may appear blank (when a stored key exists), a user tapping "Save API Key" could accidentally delete their stored key.

**Fix:** Either show a confirmation dialog before deleting, or require a separate explicit "Remove Key" button.

**File:** `AiSettingsViewModel.kt`

---

# Coverage

- **Requirements met:** Partially. The privacy gate architecture is solid and 7 of 16 original issues are fully resolved. However, critical gaps remain in receiver/startup bypasses, unconditional finance-app capture, worker sync, and Photon/Geoapify/GooglePlaces gate checks.
- **Testing adequate:** No. The analysis listed 20 regression tests; the current codebase has no dedicated privacy-gate tests visible in the source tree. Tests for `NotificationFilter` deny-keywords, receiver gate checks, Photon/Geoapify gate integration, and DataStore-corruption fallback are needed.

---

# Recommended Priority Order

1. **[ISSUE-N1]** Add privacy gate checks to `PhotonGeocodingService`, `GeoapifyGeocodingService`, `GooglePlacesGeocodingService` — direct merchant-name leak to external services.
2. **[ISSUE-1]** Add gate checks to `BootReceiver` and `ServiceRestartReceiver` — service can start unconditionally after boot/update.
3. **[ISSUE-2]** Add deny-keyword filtering to `NotificationFilter` for finance-app OTP/auth/balance notifications.
4. **[ISSUE-9]** Create sync use cases for cloud AI, location, and notification settings to update WorkManager constraints on setting changes.
5. **[ISSUE-14]** Change DataStore corruption handler to fail closed (safe defaults + warning).
6. **[ISSUE-N2]** Add confirmation before API key deletion on blank input.
7. **[ISSUE-3]** [ISSUE-11] Separate POST_NOTIFICATIONS from notification-listener permission UX; make POST_NOTIFICATIONS just-in-time.
8. **[ISSUE-5]** [ISSUE-6] [ISSUE-10] [ISSUE-15] [ISSUE-16] Remaining improvements.
