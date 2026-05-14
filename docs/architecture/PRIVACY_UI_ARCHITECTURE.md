# Privacy UI Architecture

## Overview

The app enforces privacy settings through `PrivacyGate` checks at the ViewModel layer. When a capability is denied, the UI shows the user why and prevents the action.

## Privacy Gate Integration Pattern

### Pattern 1: Action-time check (current approach)

ViewModels check the privacy gate when the user triggers an action:

```kotlin
// In ViewModel
val decision = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
if (decision.blocksExecution()) {
    _uiState.value = _uiState.value.copy(
        error = "Blocked by privacy settings: ${decision.reason()}"
    )
    return
}
// ... proceed with action
```

**Used by:** AiSettingsViewModel, ExportOptionsViewModel, SpendingMapViewModel

### Pattern 2: Persistent blocked banner (available but unused)

`PrivacyBlockedCard` can show a persistent banner at the top of a screen:

```kotlin
@Composable
fun PrivacyBlockedCard(capability: String, reason: String, modifier: Modifier)
```

**Status:** Defined in `ui/components/PrivacyBlockedCard.kt` but not yet integrated into any screen. Available for future use when a screen should show a persistent "feature disabled" state rather than an action-time error.

## Screens with Privacy Gate Checks

| Screen | Capability Checked | Behavior on Deny |
|--------|-------------------|-----------------|
| AI Settings | `CLOUD_AI_GENERAL` | Connection test returns error string |
| Export Options | `RAWBACKUP_EXPORT` | Export action shows error, returns early |
| Spending Map | `EXTERNAL_GEOCODING` | Location features disabled |
| Backup/Restore | (via repository) | Error message in UI state |

## Privacy Settings Screen

`PrivacySettingsScreen` provides toggles for all privacy settings. The ViewModel computes `deniedFeatures` list showing what's blocked when settings are disabled.

## Invariant

> If a privacy gate denies a capability, the user must see a clear reason why the action failed. The action must NOT proceed silently.

All current implementations satisfy this invariant via error strings in UiState.

## Test Coverage

- `PrivacyGateEnforcementGoldenTest` — verifies gate deny + audit at DB level
- `PrivacyDoNotStoreTest` — verifies storage mode enforcement
- ViewModel tests should verify that denied state is exposed in UiState
