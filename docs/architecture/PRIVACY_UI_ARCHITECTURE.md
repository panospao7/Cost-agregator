# Privacy UI Architecture

> Last updated: 2026-09-21 (re-verified against code)

## Overview

The app enforces privacy settings through `PrivacyGate` checks at the ViewModel, domain, and data/service layers. When a capability is denied, the UI shows the user why and prevents the action. Privacy-denied states are modeled as the typed `PrivacyBlocked` sealed interface rather than ad-hoc error strings.

Gate composition (as of 2026-09-21): five concrete gates — `NotificationPrivacyGate`, `LocationPrivacyGate`, `CloudAiPrivacyGate`, `BackupPrivacyGate`, `ExportPrivacyGate` (all in `domain/privacy/`) — are chained by `CompositePrivacyGate` (wired in `di/PrivacyModule.kt`). `PrivacyGate.check(...)` is invoked from 48 call sites across 32 production files.

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

**Used by (direct `privacyGate.check()` callers):** AiSettingsViewModel, ExportOptionsViewModel, SpendingMapViewModel, ReviewViewModel (geocoding search)

Related but not direct callers:
- `AssistantViewModel` derives a typed `PrivacyBlocked.CloudAiDisabled` state from the settings flag (no gate call); the underlying cloud assistant services enforce `CLOUD_AI_*` capabilities via the gate at call time.
- `BackupRestoreViewModel` surfaces privacy denials via repository results (`DatabaseBackupRepositoryImpl` performs the `RAWBACKUP_EXPORT` / `ENCRYPTED_BACKUP` gate checks) and maps "denied" errors to user-facing messages.

### Pattern 2: Persistent blocked banner

`PrivacyBlockedCard` shows a persistent banner at the top of a screen using the typed `PrivacyBlocked` API:

```kotlin
@Composable
fun PrivacyBlockedCard(
    blocked: PrivacyBlocked,
    modifier: Modifier = Modifier,
    onOpenPrivacySettings: (() -> Unit)? = null
)
```

**Integrated in:**
- `PrivacySettingsScreen` — renders blocked cards for each disabled feature (cloud AI, receipt upload, geocoding, notification capture, backup, etc.)
- `AssistantSheet` — shows `PrivacyBlockedCard` when cloud AI is disabled
- `SpendingMapScreen` — does NOT use `PrivacyBlockedCard`; it shows the GPS denial (`state.gpsPrivacyBlocked`) as a Snackbar with a "Dismiss" action

## Screens with Privacy Gate Checks

| Screen | Capability Checked | Behavior on Deny |
|--------|-------------------|-----------------|
| AI Settings | `CLOUD_AI_GENERAL` | Connection test returns error string |
| Export Options | `EXPENSE_EXPORT` (plain) / `EXPENSE_EXPORT_ENCRYPTED` (encrypted) | Export action shows error, returns early |
| Spending Map | `EXTERNAL_GEOCODING`, `DEVICE_GPS_LOCATION` | Location features disabled; GPS blocked Snackbar with Dismiss action (`SpendingMapScreen` `LaunchedEffect(state.gpsPrivacyBlocked)`) |
| Assistant | `CLOUD_AI_*` (enforced at cloud service layer) | Typed `PrivacyBlockedCard` at top of sheet when AI disabled by settings; gate enforced per-call in cloud services |
| Review | `EXTERNAL_GEOCODING` | Location search in review flow returns early with error string |
| Backup/Restore | (via repository: `RAWBACKUP_EXPORT` / `ENCRYPTED_BACKUP`) | Error message in UI state ("Backup denied by privacy settings") |
| Privacy Settings | All capabilities via `computeBlocked()` | `PrivacyBlockedCard` list at top of screen |

> **P12-REG-01 (fixed):** Export Options previously requested `RAWBACKUP_EXPORT`,
> which `ExportPrivacyGate` denies unconditionally — this made **every** normal
> export fail at runtime. Ordinary expense export is not a raw database backup, so
> it now uses the dedicated `EXPENSE_EXPORT` capability (and `EXPENSE_EXPORT_ENCRYPTED`
> for the encrypted path). `RAWBACKUP_EXPORT` remains owned solely by
> `ExportPrivacyGate` for true raw-database backup flows.

## Background Worker Privacy Gating

Privacy toggles also gate background work through two mechanisms (both in `domain/workers/`):

1. **Toggle change policy — `PrivacyRuntimeWorkerPolicy`**: declarative single source of truth mapping toggles to workers.
   - `CLOUD_AI` → `ai_daily_briefing`
   - `BACKGROUND_LOCATION_BACKFILL` → `location_backfill`
   - `NOTIFICATION_CAPTURE` → `receipt_matching`, `warranty_expiration_check`, `bill_reminder_periodic`
   - Disabling a toggle cancels the mapped workers; re-enabling reschedules them symmetrically via `WorkerRegistry` (a disabled `WorkerSpec.enabled` still cancels rather than enqueues).
   - `cancelExemptWorkers = {data_retention}` — the retention/cleanup worker is NEVER cancelled by a privacy toggle, so it can purge data already collected.
2. **Execution-time re-check — `WorkerExecutionGuard`**: before running a worker's block, the guard evaluates `request.requiredCapabilities` through the privacy gate. `Denied` → `WORKER_PRIVACY_DENIED`, `FailClosed` → `WORKER_PRIVACY_FAIL_CLOSED` (fail closed).

All 10 production `CoroutineWorker`s run through `WorkerExecutionGuard.runGuarded`/`runGuardedWithContext`; `WorkerGuardArchitectureGuardTest` enforces this with an empty allowlist (`ALLOWLISTED_WORKERS = emptySet()`).

## Privacy Settings Screen

`PrivacySettingsScreen` provides toggles for all privacy settings. The ViewModel (`PrivacySettingsViewModel`) computes a `blocked: List<PrivacyBlocked>` list showing what's blocked when features are disabled. Each disabled feature renders a `PrivacyBlockedCard` at the top of the screen with the typed reason message.

The typed `PrivacyBlocked` set covers: `CloudAiDisabled`, `ReceiptImageUploadDisabled`, `ExternalGeocodingDisabled`, `NotificationCaptureDisabled`, `RawExportDisabled`, `DeviceGpsDisabled`, `BackgroundLocationDisabled`, `BankStatementAiDisabled`, `EncryptedBackupDisabled`, `OverpassDisabled`, `DebugDataPersistenceDisabled`, and `Custom`.

## Invariant

> If a privacy gate denies a capability, the user must see a clear reason why the action failed. The action must NOT proceed silently.

All current implementations satisfy this invariant via typed `PrivacyBlocked` states or error strings in UiState. Additionally, `PrivacyDecision.FailClosed` is treated as unconditionally blocking — the action never proceeds.

## Test Coverage

- `PrivacyGateEnforcementGoldenTest` (`app/src/test/.../golden/`) — verifies gate deny + audit at DB level
- `PrivacyDoNotStoreTest` (`app/src/test/.../golden/`) — verifies storage mode enforcement
- `PrivacyCapabilityHandlingPolicyTest` (`app/src/test/.../domain/privacy/`) — ensures all capabilities have explicit handling policy
- `PrivacyBehavioralRegressionTest` (`app/src/test/.../domain/privacy/`) — verifies CloudPayloadPolicy redaction, RawStorageMode behavior
- `PR5PrivacyContractTest` (`app/src/test/.../domain/privacy/`) — contract tests for DatabaseWriteBarrier + privacy gate interactions
- `PrivacyRuntimeWorkerPolicyTest` (`app/src/test/.../domain/workers/`) — validates the toggle→worker mapping against `WorkerSpec.DEFAULTS` and the `data_retention` exemption
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

## Status Review (2026-09-07)

Re-verified against source:

- `PrivacyBlocked` still has exactly 12 subclasses (11 concrete + `Custom`) — `domain/privacy/PrivacyBlocked.kt`.
- The P12-REG-01 fix remains in place: `ExportOptionsViewModel` uses `EXPENSE_EXPORT` / `EXPENSE_EXPORT_ENCRYPTED`; `ExportPrivacyGate` (in `domain/privacy/`, not the data layer) still denies `RAWBACKUP_EXPORT` in both branches and is its sole owner per the `PrivacyGate` contract KDoc.
- `AssistantViewModel.privacyBlocked` is constructed directly from the settings flag (`PrivacyBlocked.CloudAiDisabled()`), not via `toPrivacyBlocked()` — a minor deviation from the "single entry point" KDoc contract, which that KDoc scopes to cloud providers.
- No UI-level screenshot protection (`FLAG_SECURE`) or blur/redaction overlay exists in production UI code; privacy UI enforcement is limited to the typed blocked states, gating, and export redaction described above.
- Worker toggle→worker mapping and `data_retention` exemption unchanged (see "Background Worker Privacy Gating").

Re-verified 2026-09-21: all items above still hold; no changes to gates, call-site counts (48 across 32 files), `PrivacyBlocked` subclasses, worker policy, or test files.
