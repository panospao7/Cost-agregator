# Privacy UI Architecture

## Overview

The app enforces privacy settings through `PrivacyGate` checks at the ViewModel layer. When a capability is denied, the UI shows the user why and prevents the action. Privacy-denied states are modeled as the typed `PrivacyBlocked` sealed interface rather than ad-hoc error strings.

## Privacy Gate Integration Pattern

### Pattern 1: Action-time check (current approach)

ViewModels check the privacy gate when the user triggers an action:

```kotlin
// In ViewModel
val decision = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
if (decision.blocksExecution()) {
    val blocked = decision.toPrivacyBlocked(capability)
    _uiState.value = _uiState.value.copy(
        error = blocked?.reason ?: "Blocked by privacy settings"
    )
    return
}
// ... proceed with action
```

**Used by:** AiSettingsViewModel, ExportOptionsViewModel, SpendingMapViewModel, AssistantViewModel, BackupRestoreViewModel

### Pattern 2: Persistent blocked banner

`PrivacyBlockedCard` shows a persistent banner at the top of a screen using the typed `PrivacyBlocked` API:

```kotlin
@Composable
fun PrivacyBlockedCard(
    blocked: PrivacyBlocked,
    modifier: Modifier = Modifier
)
```

**Integrated in:**
- `PrivacySettingsScreen` — renders blocked cards for each disabled feature (cloud AI, receipt upload, geocoding, notification capture, backup, etc.)
- `AssistantSheet` — shows `PrivacyBlockedCard` when cloud AI is disabled
- `SpendingMapScreen` — shows GPS privacy blocked banner with dismiss action

## Screens with Privacy Gate Checks

| Screen | Capability Checked | Behavior on Deny |
|--------|-------------------|-----------------|
| AI Settings | `CLOUD_AI_GENERAL` | Connection test returns error string |
| Export Options | `EXPENSE_EXPORT` (plain) / `EXPENSE_EXPORT_ENCRYPTED` (encrypted) | Export action shows error, returns early |
| Spending Map | `EXTERNAL_GEOCODING`, `DEVICE_GPS_LOCATION` | Location features disabled; GPS blocked card with dismiss |
| Assistant | `CLOUD_AI_GENERAL` / per-assist capability | `PrivacyBlockedCard` rendered at top of sheet |
| Backup/Restore | (via repository) | Error message in UI state |
| Privacy Settings | All capabilities via `computeBlocked()` | `PrivacyBlockedCard` list at top of screen |

> **P12-REG-01 (fixed):** Export Options previously requested `RAWBACKUP_EXPORT`,
> which `ExportPrivacyGate` denies unconditionally — this made **every** normal
> export fail at runtime. Ordinary expense export is not a raw database backup, so
> it now uses the dedicated `EXPENSE_EXPORT` capability (and `EXPENSE_EXPORT_ENCRYPTED`
> for the encrypted path). `RAWBACKUP_EXPORT` remains owned solely by
> `ExportPrivacyGate` for true raw-database backup flows.

## Privacy Settings Screen

`PrivacySettingsScreen` provides toggles for all privacy settings. The ViewModel (`PrivacySettingsViewModel`) computes a `blocked: List<PrivacyBlocked>` list showing what's blocked when features are disabled. Each disabled feature renders a `PrivacyBlockedCard` at the top of the screen with the typed reason message.

The typed `PrivacyBlocked` set covers: `CloudAiDisabled`, `ReceiptImageUploadDisabled`, `ExternalGeocodingDisabled`, `NotificationCaptureDisabled`, `RawExportDisabled`, `DeviceGpsDisabled`, `BackgroundLocationDisabled`, `BankStatementAiDisabled`, `EncryptedBackupDisabled`, `OverpassDisabled`, `DebugDataPersistenceDisabled`, and `Custom`.

## Invariant

> If a privacy gate denies a capability, the user must see a clear reason why the action failed. The action must NOT proceed silently.

All current implementations satisfy this invariant via typed `PrivacyBlocked` states or error strings in UiState. Additionally, `PrivacyDecision.FailClosed` is treated as unconditionally blocking — the action never proceeds.

## Test Coverage

- `PrivacyGateEnforcementGoldenTest` — verifies gate deny + audit at DB level
- `PrivacyDoNotStoreTest` — verifies storage mode enforcement
- `PrivacyCapabilityHandlingPolicyTest` — ensures all capabilities have explicit handling policy
- `PrivacyBehavioralRegressionTest` — verifies CloudPayloadPolicy redaction, RawStorageMode behavior
- `PR5PrivacyContractTest` — contract tests for DatabaseWriteBarrier + privacy gate interactions
- ViewModel tests verify that denied state is exposed in UiState

## Recent Fixes (Slice 3 — Completed)

- `PrivacyBlockedCard` upgraded: typed `PrivacyBlocked` API, semantics, testTag, `displayLabel()`
- `PrivacySettingsViewModel` now exposes `blocked: List<PrivacyBlocked>` and `errorMessage`
- `PrivacySettingsScreen` renders blocked cards at top when features are disabled
- `BackupRestoreViewModel` null input stream now throws explicit error
- `BackupRestoreScreen` restart action extracted to `onRestartRequired` callback
- `PrivacyCapabilityHandlingPolicyTest` prevents new capabilities from being fail-open

### Subsequent completeness fixes (post-Slice 3)

- `PrivacyBlocked` sealed interface expanded to 12 typed subclasses (11 concrete + 1 Custom) covering all user-facing capabilities
- `SpendingMapViewModel` exposes `gpsPrivacyBlocked: PrivacyBlocked?` with dismiss
- `AssistantViewModel` exposes `privacyBlocked` for cloud AI denial in assistant sheet
- `toPrivacyBlocked()` extension maps any `PrivacyDecision` + capability to typed `PrivacyBlocked`
- `PrivacyDecision.FailClosed` handling added — maps to `PrivacyBlocked.Custom` with safety reason
