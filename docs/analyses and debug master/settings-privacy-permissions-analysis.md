# Settings / Privacy / Permissions Deep Analysis

Branch: `master-refactor`

## Scope reviewed

Main areas:

- AI settings and provider configuration
- Secure API-key storage
- Android notification posting permission
- notification-listener capture service
- location/map permission and geocoding privacy
- boot/restart receivers
- backup/export privacy
- manifest permission surface

This is a static review; I did not execute the app.

---

# Executive verdict

This area needs a hardening pass.

The app has good building blocks:

- AI settings are centralized in `AiSettingsRepository`.
- API keys are stored in `EncryptedSharedPreferences`.
- POST_NOTIFICATIONS is requested at runtime.
- geocoding logs mostly use hashed/anonymized identifiers.
- database backup export defaults to app-private storage.
- `android:allowBackup="false"` is set.

But the privacy model is not yet strict enough.

The biggest issue:

> Several sensitive features are controlled by Android permissions or informal UI toggles, but there is no single “privacy capability gate” that every service, worker, provider, and background path must pass before touching sensitive data or network.

Highest-risk areas:

1. notification capture has no obvious app-level master opt-out
2. finance-app notifications are captured unconditionally
3. cloud/local AI settings can become contradictory
4. AI settings defaults differ between model and DataStore
5. location/geocoding can send merchant names to third parties without a clear external-service setting
6. background workers are not consistently rescheduled/cancelled when privacy settings change
7. backup/export can include very sensitive raw data unless encrypted/redacted

---

# Critical / high-priority findings

## 1. Notification capture lacks a clear app-level privacy gate

### Where

- `NotificationCaptureService.kt`
- `BootReceiver.kt`
- `ServiceRestartReceiver.kt`
- `NotificationFilter.kt`

### Problem

The notification listener starts/restarts through:

- boot receiver
- package-replaced receiver
- service restart alarm
- notification listener binding

But I did not see a central user setting like:

```text
notificationCaptureEnabled
```

that every entry path checks before processing notifications.

There is Android notification-listener permission, and there is `repository.isPackageBlocked(packageName)`, but those are not the same as an in-app master privacy control.

### Impact

A user may grant notification-listener permission once, then expect the app to be pausable from inside settings.

Without an app-level gate:

- service can resume after boot
- service can resume after package update
- restart alarm can restart service
- active notifications can be refreshed
- financial notification content can continue being stored

### Severity

**Critical / privacy**

### Fix

Add a central setting:

```kotlin
notificationCaptureEnabled: Boolean
```

Then check it in:

- `NotificationCaptureService.onCreate`
- `onStartCommand`
- `onListenerConnected`
- `onNotificationPosted`
- `refreshActiveNotifications`
- `BootReceiver`
- `ServiceRestartReceiver`
- startup scheduler

Also add UI controls:

- enable/disable notification capture
- open Android notification-listener settings
- pause capture temporarily
- clear captured raw notifications
- per-package allow/block list

---

## 2. Finance-app notifications are captured unconditionally

### Where

`NotificationFilter.kt`

Finance packages bypass heuristics:

```text
every notification from these apps is assumed financial
```

### Problem

Finance apps can send non-transaction notifications:

- OTP / login approval
- card verification
- account balance
- security warnings
- marketing offers
- failed login attempts
- payment requests
- personal bank messages

Capturing every notification from those apps can store more sensitive data than necessary.

### Impact

Raw notification title/text/bigText is persisted through `RawNotification`.

This can create a privacy risk if:

- user exports database
- debug viewer shows raw notifications
- AI accidentally receives notification text
- backup is shared
- device is compromised

### Severity

**Critical**

### Fix

Change finance packages from unconditional capture to high-confidence capture.

Recommended rule:

- transaction-like notification → capture
- OTP/auth/security/balance/marketing → ignore or redact
- unknown finance notification → optionally store minimal metadata only

Add deny keywords:

```text
OTP, one-time password, login, verify, code, balance, statement, offer, promo
```

Also add a per-package “capture all from this app” advanced setting, default off.

---

## 3. Notification posting permission is confused with notification-reading permission

### Where

- `MainActivity.kt`
- `NotificationPermissionDialog.kt`
- `AndroidManifest.xml`

### Problem

The app asks for Android 13+ `POST_NOTIFICATIONS`.

That permission allows the app to **send** notifications.

But expense ingestion depends on notification-listener access, which is a special Android settings permission.

The current dialog appears to cover posting notifications only. I did not see a dedicated onboarding flow for notification-listener permission.

### Impact

Users may think they enabled expense capture by allowing notifications, when they only enabled app reminders/alerts.

Or they may think they denied expense capture by denying POST_NOTIFICATIONS, while notification reading can still remain enabled from system settings.

### Severity

**High / privacy + UX**

### Fix

Separate permission UX:

1. “Allow app alerts” → POST_NOTIFICATIONS
2. “Read transaction notifications” → open notification-listener settings
3. “Use device location on map” → location runtime permission
4. “Use camera for receipts” → camera runtime permission

Each permission should show:

- what data is accessed
- why it is needed
- where it is stored
- how to turn it off
- how to delete existing data

---

## 4. AI settings defaults are inconsistent

### Where

- `AiModels.kt`
- `AiSettingsRepositoryImpl.kt`
- `AiSettingsViewModel.kt`

### Problem

`AiSettings` model defaults include:

- `receiptAssistEnabled = true`
- `receiptImageCloudEnabled = true`

But DataStore fallback defaults use:

- `receiptAssistEnabled = false`
- `receiptImageCloudEnabled = false`

Also `AiSettingsUiState` starts with `AiSettings()`, so the UI/model can briefly represent different defaults from persisted settings.

### Impact

Fresh installs, tests, previews, and initial UI state can disagree.

Privacy-sensitive example:

- model default says receipt image cloud is enabled
- real DataStore default says it is disabled

Even if persisted behavior is safer, inconsistent defaults make route decisions and tests unreliable.

### Severity

**High**

### Fix

Create one canonical default object:

```kotlin
DefaultAiSettings.value
```

Use it in:

- `AiSettings`
- `AiSettingsRepositoryImpl`
- `AiSettingsUiState`
- tests
- previews
- migrations/default seeding

Recommended privacy default:

- AI enabled: optional
- cloud AI: off
- receipt image cloud: off
- redaction: on
- conversation history: off
- quick-save / quick-approve: off

---

## 5. AI settings allow contradictory states

### Where

`AiSettingsScreen.kt`, `AiSettingsViewModel.kt`, `AiModels.kt`

### Problem

The UI allows toggles independently:

- AI disabled but cloud enabled
- cloud disabled but receipt image cloud enabled
- redaction enabled but image cloud enabled
- quick-save enabled while receipt assist disabled
- quick-approve enabled while review explanation disabled
- preferred mode cloud while cloud disabled
- preferred mode on-device while on-device disabled

### Impact

The runtime router may resolve these later, but the user-facing settings state becomes confusing and hard to reason about.

Privacy problem:

> Users should not need to understand internal router behavior to know whether data can leave the device.

### Severity

**High**

### Fix

Enforce invariants at settings write time.

Examples:

```text
if aiEnabled=false:
  disable cloud/on-device capabilities or mark them inactive

if allowCloudAi=false:
  receiptImageCloudEnabled=false

if redactBeforeCloud=true:
  block receipt image upload

if preferredMode=CLOUD:
  require allowCloudAi=true

if preferredMode=ON_DEVICE:
  require allowOnDeviceAi=true
```

Also show disabled controls instead of allowing meaningless combinations.

---

## 6. Disabling cloud AI does not clearly handle stored API keys

### Where

- `AiSettingsViewModel.kt`
- `SecureKeyStorage.kt`
- `AiSettingsScreen.kt`

### Problem

Disabling cloud AI does not delete the stored Gemini API key.

That may be intended, but the UI should make the retention explicit.

More importantly, the current “Save API key” behavior can delete a stored key when the input field is blank.

`saveApiKey()` treats blank input as delete.

### Impact

Two risks:

1. Privacy: user disables cloud but API key remains stored.
2. UX/data loss: user sees “key saved”, leaves field blank, taps save, and removes the key.

### Severity

**Medium / High**

### Fix

Separate actions:

- Save/update key
- Test key
- Remove key

If cloud AI is disabled, show:

> “Cloud key is stored but inactive.”

Add setting:

```text
Delete cloud API key when disabling cloud AI?
```

or an explicit “Remove cloud key” button.

---

## 7. Cloud AI lacks a hard provider-side settings gate

### Where

- AI policy/settings layer
- cloud provider classes from previous AI audit

### Problem

The settings screen and router try to decide whether cloud is allowed, but cloud providers themselves should still check the latest settings before request creation.

Relying only on callers is fragile.

### Impact

A future direct injection or refactor could call a cloud provider even when:

- cloud AI is off
- Wi-Fi-only is on but device is on mobile data
- redaction is required
- image upload is disabled
- API key is missing
- capability is disabled

### Severity

**Critical / privacy**

### Fix

Add mandatory:

```kotlin
CloudAiGate.requireAllowed(capability, payloadKind)
```

Every cloud provider must call it internally before building prompts or requests.

Gate checks:

- global AI enabled
- cloud AI enabled
- capability enabled
- preferred mode
- network
- Wi-Fi-only
- API key exists
- redaction requirement
- receipt image upload permission

---

## 8. Location/geocoding has no clear external-service consent gate

### Where

- `SpendingMapScreen.kt`
- `SpendingMapViewModel.kt`
- `CompositeGeocodingService.kt`
- `NominatimGeocodingService.kt`
- `LocationBackfillWorker.kt`

### Problem

Map screen asks for Android location permission for device position.

But geocoding merchant names is separate. It can send merchant names and optional location/city hints to external services:

- Nominatim
- Photon
- Geoapify
- optionally Google Places

This can happen even without device GPS permission, because merchant-name geocoding does not require Android location permission.

### Impact

A user may deny location permission but still have merchant names sent to geocoding providers for map enrichment or background backfill.

That is a privacy expectation bug.

### Severity

**High / privacy**

### Fix

Add separate settings:

```text
locationInsightsEnabled
externalGeocodingEnabled
backgroundLocationBackfillEnabled
usePaidGeocodingProviders
useGooglePlaces
```

Rules:

- no external geocoding unless user explicitly enables it
- background geocoding should have separate opt-in
- paid/provider-key services should have explicit opt-in
- denying Android location should not necessarily disable map display, but should disable GPS bias

---

## 9. Background privacy settings are not consistently synced to workers

### Where

- `AiSettingsViewModel.kt`
- `SyncProactiveBriefingWorkUseCase`
- startup/background workers from previous audit

### Problem

Only some AI setting changes call `syncProactiveBriefingWorkUseCase`:

- AI enabled
- dashboard briefing enabled
- proactive briefings enabled

Other privacy-impacting changes do not trigger worker resync:

- allow cloud AI
- Wi-Fi-only
- preferred mode
- allow on-device AI
- store conversation history
- redaction

Similarly, location/warranty/background settings do not appear to have their own sync use cases.

### Impact

Workers can keep old scheduling constraints after settings change.

Example:

- user enables Wi-Fi-only cloud
- existing DailyBriefingWorker keeps old unconstrained WorkSpec
- lower AI gates may still block cloud, but worker scheduling does not reflect user intent

### Severity

**High**

### Fix

Create sync use cases:

```kotlin
SyncAiWorkUseCase
SyncLocationBackfillWorkUseCase
SyncWarrantyReminderWorkUseCase
SyncNotificationCaptureWorkUseCase
```

Call them whenever relevant settings change.

---

## 10. NotificationCaptureService uses foreground service type `location`

### Where

- `AndroidManifest.xml`
- `NotificationCaptureService.kt`

Manifest declares:

```text
android:foregroundServiceType="dataSync|location"
```

The service also starts foreground with `DATA_SYNC | LOCATION` on Android 14+.

### Problem

The notification listener service is primarily for transaction notification capture, not location.

Combining notification capture and location foreground service type broadens the permission surface and can create Android 14+ foreground-service restrictions.

### Impact

Potential issues:

- service start failure if location requirements are not met
- confusing privacy disclosure
- Play policy review concerns
- user sees location-related permission for a notification-capture service

### Severity

**High**

### Fix

Separate responsibilities:

- NotificationCaptureService → `dataSync` only
- Location foreground work, if truly needed → separate service with location type
- background geocoding should use WorkManager, not a notification listener foreground service

---

## 11. POST_NOTIFICATIONS is requested on first launch, not just-in-time

### Where

`MainActivity.kt`

### Problem

The app shows notification permission rationale on first launch if Android 13+ permission is missing.

But the user may not yet know why they need app notifications.

### Impact

Early permission prompts have lower trust and higher denial rate.

Also, this prompt is unrelated to notification capture.

### Severity

**Medium**

### Fix

Ask just-in-time:

- when enabling budget alerts
- when enabling AI proactive briefings
- when enabling bill reminders
- when enabling warranty reminders

Show a clear feature-specific rationale.

---

## 12. Raw notification data retention is not user-controlled enough

### Where

- `NotificationCaptureService.kt`
- `RawNotification`
- `NotificationRepository`

### Problem

Notification capture stores raw title/text/bigText and sanitized extras JSON.

There should be clear retention settings:

- keep raw notification text?
- keep only parsed transaction?
- auto-delete raw notifications after N days?
- delete raw data after approval?
- purge all raw notifications

I did not see such a settings surface in this review.

### Impact

Sensitive text can live indefinitely in the DB.

### Severity

**High**

### Fix

Add retention settings:

```text
rawNotificationRetentionDays
deleteRawAfterParsed
deleteRawAfterApproved
storeOnlyHashedNotificationFingerprint
```

Default should be minimal retention.

---

## 13. Backups can include sensitive raw data

### Where

- `DatabaseBackupRepositoryImpl.kt`
- `ExportOptionsScreen.kt`
- `AndroidManifest.xml`

### Strong part

The app sets:

```text
android:allowBackup="false"
```

and database export defaults to app-private storage.

It also detects old public backups in Downloads and warns that they may be readable by other apps.

### Problem

A full `.db` export likely contains:

- expenses
- raw notifications
- receipt OCR text
- merchant/location history
- AI artifacts
- pending reviews
- budgets
- possibly scanned receipt metadata

The exported database is not obviously encrypted/password-protected.

### Impact

If a user shares or moves the backup file, it can expose sensitive financial history.

### Severity

**High**

### Fix

Offer backup modes:

1. encrypted full backup
2. redacted backup
3. CSV/accountant export
4. developer/debug export

For full DB backup:

- require password or Android Keystore-wrapped encryption
- warn clearly that it contains raw financial data
- include an option to exclude raw notifications/OCR/AI artifacts

---

## 14. DataStore corruption resets AI settings silently

### Where

`AiSettingsRepositoryImpl.kt`

### Problem

The DataStore corruption handler replaces corrupted preferences with empty preferences.

Empty preferences then map to defaults.

Some defaults are privacy-sensitive:

- AI enabled defaults true
- on-device AI defaults true
- warranty extraction defaults true

Cloud remains false, which is good, but silent reset is still problematic.

### Impact

A user’s explicit privacy settings can be lost without visible warning.

### Severity

**Medium / High**

### Fix

Fail closed on corruption:

- AI disabled
- cloud disabled
- image upload disabled
- history disabled
- quick actions disabled
- redaction enabled

Record a diagnostic and show:

> “AI/privacy settings were reset due to a storage error. Please review them.”

---

## 15. Conversation history toggle needs purge semantics

### Where

- `AiSettingsScreen.kt`
- `AiSettingsRepositoryImpl.kt`
- AI chat/artifact repositories

### Problem

There is a setting:

```text
storeConversationHistory
```

But the settings UI does not clearly offer:

- delete existing chat history
- delete AI artifacts
- delete cloud-generated summaries
- export AI data
- show what is stored

Turning history off should prevent future storage, but users also need control over old stored content.

### Impact

User may think disabling history deletes prior conversations, while old history remains.

### Severity

**High / privacy UX**

### Fix

Add actions:

- “Delete AI conversation history”
- “Delete AI generated artifacts”
- “Delete AI diagnostics”
- “View stored AI data”

And enforce:

```text
storeConversationHistory=false
```

inside repositories, not only UI.

---

## 16. Deep links are exported through a custom scheme

### Where

`AndroidManifest.xml`, `MainActivity.kt`

### Problem

The app accepts external links like:

```text
expensetracker://activity?expenseId=...
expensetracker://map?location=...
expensetracker://add
```

Any app can launch these.

### Impact

Probably no direct data exfiltration, but another app can bring the app to a sensitive screen if the phone is unlocked.

### Severity

**Medium**

### Fix

For sensitive deep links:

- require explicit user confirmation
- avoid opening specific expense IDs from external links unless generated by app notifications
- include signed/internal-only pending intent extras instead of public custom scheme where possible

---

## Strong parts to keep

## 1. API keys are not compiled into BuildConfig

`SecureKeyStorage` uses AndroidX Security `EncryptedSharedPreferences` and Android Keystore-backed `MasterKey`.

Good.

## 2. Backup defaults to app-private storage

`DatabaseBackupRepositoryImpl.exportDatabase()` writes to `context.filesDir/exports`.

Good privacy baseline.

## 3. Legacy public backup warning exists

The app checks Downloads for old public backup files and warns that they may be readable.

Good.

## 4. Geocoding logs avoid raw query strings

Location services use hashed/anonymized logging for merchant/query values.

Good. Keep this pattern.

## 5. Notification extras are partially sanitized

`NotificationCaptureService.buildExtrasJson()` skips many sensitive keys and avoids huge values.

Good, though raw title/text/bigText still need stricter controls.

## 6. AI settings screen exposes runtime status

The runtime section shows network/Wi-Fi/provider readiness.

Good UX direction. It just needs stricter enforcement behind it.

---

# Recommended fix order

## PR 1 — Add privacy capability gates

Create centralized gates:

```kotlin
PrivacyCapabilityGate
CloudAiGate
ExternalLocationGate
NotificationCaptureGate
BackupExportGate
```

Every sensitive path must check a gate at runtime.

## PR 2 — Add notification capture settings

Add:

- master notification capture toggle
- package allow/block list
- raw notification retention
- purge raw notifications
- clear explanation of Android notification-listener permission

## PR 3 — Fix AI settings defaults and invariants

- one canonical default source
- cloud off by default
- receipt image cloud off by default
- redaction on by default
- prevent contradictory settings

## PR 4 — Split notification posting vs reading permission UX

Separate onboarding flows for:

- POST_NOTIFICATIONS
- notification listener access
- location
- camera

## PR 5 — Add location/geocoding privacy settings

Separate:

- device location permission
- map feature
- external geocoding
- background backfill
- Google/paid provider usage

## PR 6 — Worker sync on setting changes

Settings changes should schedule/update/cancel affected workers.

## PR 7 — Encrypted/redacted backup modes

Add password-encrypted backups and redacted exports.

## PR 8 — Data retention and purge controls

For:

- raw notifications
- OCR text
- receipt images
- AI chat/artifacts
- location corrections/history
- diagnostics

---

# Regression tests to add

1. Notification capture disabled → service ignores posted notifications.
2. Notification capture disabled → boot receiver does not start service.
3. Finance-app OTP notification is not captured.
4. POST_NOTIFICATIONS denied does not imply notification capture disabled.
5. Notification-listener disabled is surfaced in UI.
6. `AiSettings()` equals empty DataStore settings.
7. Cloud disabled → no cloud provider can send request.
8. Redaction enabled → receipt image upload blocked.
9. `receiptImageCloudEnabled` cannot be true when cloud is false.
10. Local-only mode never calls cloud.
11. Disabling cloud does not accidentally delete API key.
12. Blank API-key field does not delete stored key unless user taps explicit remove.
13. External geocoding disabled → no Nominatim/Photon/Geoapify/Google HTTP call.
14. Background location backfill disabled → worker cancelled.
15. Wi-Fi-only cloud setting change updates worker constraints.
16. Exported full backup is encrypted or clearly marked sensitive.
17. Redacted backup excludes raw notifications/OCR/AI artifacts.
18. Turning off conversation history stops future chat persistence.
19. “Delete AI history” removes old AI chat/artifacts.
20. DataStore corruption falls back to safe privacy defaults and shows warning.

---

# Top three fixes

If you only fix three things first:

1. **Add a master notification-capture privacy gate and package allowlist.**
2. **Add hard provider-side gates for cloud AI and external geocoding.**
3. **Canonicalize AI settings defaults and enforce valid setting combinations.**

Those remove the biggest privacy-surprise risks.

---

# Sources reviewed

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/docs/architecture/CODEBASE_SEGMENTS.md

- `AndroidManifest.xml`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/AndroidManifest.xml

- `MainActivity.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/refs/heads/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt

- `NotificationPermissionDialog.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/components/NotificationPermissionDialog.kt

- `NotificationCaptureService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/refs/heads/master-refactor/app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt

- `NotificationFilter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt

- `BootReceiver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/receiver/BootReceiver.kt

- `ServiceRestartReceiver.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/receiver/ServiceRestartReceiver.kt

- `AiSettingsScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsScreen.kt

- `AiSettingsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModel.kt

- `AiSettingsRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt

- `AiModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt

- `AiPolicyImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt

- `SecureKeyStorage.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/security/SecureKeyStorage.kt

- `DefaultAiEnvironmentMonitor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/DefaultAiEnvironmentMonitor.kt

- `AndroidNotificationService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/service/AndroidNotificationService.kt

- `SpendingMapScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapScreen.kt

- `SpendingMapViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt

- `CompositeGeocodingService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt

- `NominatimGeocodingService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/location/NominatimGeocodingService.kt

- `DatabaseBackupRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt