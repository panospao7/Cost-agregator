# Slice 3 Debug Report — Privacy / Security UI

Commit reviewed: `ea3f716eebba8c513edeeba40db394c10ca829cb`

Scope:
- `ui/components/PrivacyBlockedCard.kt`
- `ui/screens/privacysettings/*`
- `ui/screens/backup/*`
- privacy-facing AI/settings/export/map states
- `domain/privacy/*` as dependency surface

---

# Executive Summary

Slice 3 is **partially fixed but still has important privacy UX and enforcement gaps**.

Good progress:
- `PrivacyBlockedCard` now uses typed `PrivacyBlocked`.
- `PrivacyDecision.blocksExecution()` correctly blocks both `Denied` and `FailClosed`.
- `CompositePrivacyGate` converts thrown gate exceptions into `FailClosed`.
- `PrivacySettingsScreen` now renders blocked cards.
- `AiSettingsViewModel` preflights cloud-provider connection tests with `PrivacyGate`.
- `ExportOptionsViewModel` preflights export generation.
- `SpendingMapViewModel` preflights device GPS access.
- Backup restore input-stream failure now surfaces an explicit error.
- Restart action was extracted as a callback.
- `PrivacyCapabilityHandlingPolicyTest` now guards against unclassified enum additions.

Remaining high-impact issues:
1. `PrivacySettingsViewModel.computeBlocked()` only covers 4 disabled capabilities.
2. Raw storage privacy modes exist in `PrivacySettings` but have no UI controls.
3. `ExportOptionsViewModel` checks `RAWBACKUP_EXPORT` even for encrypted export.
4. Map GPS privacy denial stores only a boolean, losing the denial reason.
5. External geocoding / Overpass privacy enforcement is still not structurally guaranteed.
6. `BackupRestoreViewModel.restoreBackup()` can leave the UI stuck if repository restore throws.
7. Backup restore tests likely use an unsafe mocked `InputStream`.
8. `AssistantViewModel` has no direct visible `PrivacyGate` preflight or typed blocked UI state.
9. Many privacy/security strings are hardcoded and not localized.
10. Privacy settings save state is unused, allowing rapid overlapping updates.

Recommended fix order:
1. Fix privacy blocked-state modeling and coverage.
2. Fix export privacy gate selection.
3. Fix backup restore exception/finally handling.
4. Fix map/location denied-state reason handling.
5. Add missing privacy settings controls.
6. Harden tests.

---

# Status of Previously Known Slice 3 Findings

## S3-PREV-001 — Typed `PrivacyBlockedCard`

**Status:** Mostly resolved.

Evidence:
- `PrivacyBlockedCard` accepts `PrivacyBlocked`.
- It shows a capability label and reason.
- It has a `privacy_blocked_card` test tag.
- It exposes a content description.

Remaining issues:
- Strings are hardcoded.
- Capability labels live in UI code.
- Typed blocked cases are incomplete.
- No visible Compose test was found for semantics / reason rendering.

---

## S3-PREV-002 — Privacy settings screen displays blocked feature cards

**Status:** Partially resolved.

Evidence:
- `PrivacySettingsScreen` renders `PrivacyBlockedCard` when `uiState.blocked` is non-empty.
- `PrivacySettingsViewModel` exposes `blocked: List<PrivacyBlocked>`.

Problem:
`computeBlocked()` only reports:
- Cloud AI disabled
- Receipt image upload disabled
- External geocoding disabled
- Notification capture disabled

It does not report:
- Bank statement AI disabled
- Background location backfill disabled
- Device GPS disabled
- Encrypted backup disabled
- Raw export mode implications
- Raw notification/OCR storage modes
- Debug data persistence disabled
- Overpass API blocked through geocoding
- Timber PII logging policy

So the UI says “Disabled Features”, but it is not a complete disabled-feature inventory.

---

## S3-PREV-003 — `FailClosed` blocks like `Denied`

**Status:** Domain resolved, UI partial.

Evidence:
- `PrivacyDecision.blocksExecution()` returns true for `Denied` and `FailClosed`.
- `CompositePrivacyGate` catches gate exceptions and returns `FailClosed`.

Remaining issue:
Some UI states do not preserve the typed decision/reason. Example:
- map GPS denial only sets `gpsPrivacyBlocked = true`.

Fix:
Use a typed blocked state, not booleans.

---

## S3-PREV-004 — Backup null input stream handled

**Status:** Mostly resolved.

Evidence:
- `BackupRestoreViewModel.restoreBackup()` now handles `openInputStream(uri) == null` with an explicit error.

Remaining issue:
- Not tested.
- Repository restore exceptions can still escape and leave `isRestoring = true`.

---

## S3-PREV-005 — Restart action extracted from backup screen

**Status:** Partially resolved.

Evidence:
- `BackupRestoreScreen` accepts `onRestartRequired`.

Remaining issue:
- Default value still calls `Runtime.getRuntime().exit(0)`.
- ViewModel still exposes deprecated `dismissRestartRequired()`.
- Existing test still verifies the deprecated dismiss path.

Fix:
Remove default process-kill behavior from the composable API. Require the host to provide restart handling.

---

## S3-PREV-006 — Capability policy test added

**Status:** Partial.

Evidence:
- `PrivacyCapabilityHandlingPolicyTest` maps every `PrivacyCapability` to `GATE_HANDLED` or `LOCAL_ONLY`.

Limit:
This test only proves that the enum is classified. It does **not** prove the runtime `CompositePrivacyGate` actually handles every `GATE_HANDLED` capability.

Need additional test:
- Instantiate composite with fake gates.
- Assert every `GATE_HANDLED` capability returns non-`NotApplicable`.

---

# Issues Found

---

## S3-001 — Privacy settings blocked summary is incomplete

**Severity:** High  
**Files:**
- `PrivacySettingsViewModel.kt`
- `PrivacySettingsScreen.kt`
- `PrivacyBlocked.kt`

## Problem

`computeBlocked(settings)` only reports 4 disabled features.

But Slice 3’s invariant is:

> If a privacy capability is blocked, the user should see a clear reason.

The current summary misses many privacy-relevant disabled settings.

## Impact

Users may disable GPS, background location, bank statement AI, encrypted backup, raw retention/storage, etc., but the privacy settings summary will not clearly explain what is blocked.

## Fix Strategy

Expand `PrivacyBlocked` typed states:

```kotlin
data class DeviceGpsDisabled(...) : PrivacyBlocked
data class BackgroundLocationDisabled(...) : PrivacyBlocked
data class BankStatementAiDisabled(...) : PrivacyBlocked
data class EncryptedBackupDisabled(...) : PrivacyBlocked
data class OverpassDisabled(...) : PrivacyBlocked
data class RawNotificationStorageDisabled(...) : PrivacyBlocked
data class RawOcrStorageDisabled(...) : PrivacyBlocked
data class DebugDataPersistenceDisabled(...) : PrivacyBlocked
```

Then update `computeBlocked()` to cover all user-facing privacy controls.

## Acceptance Criteria

- Every disabled privacy setting that blocks or changes behavior appears in the summary.
- Every summary card has:
  - capability label,
  - user-readable reason,
  - optional CTA if actionable.
- Add `PrivacySettingsViewModelTest`.

---

## S3-002 — `PrivacyBlocked` model is too small for current capability set

**Severity:** Medium/High  
**Files:**
- `PrivacyBlocked.kt`
- `PrivacyCapability.kt`
- `PrivacyBlockedCard.kt`

## Problem

`PrivacyCapability` has many values, but `PrivacyBlocked` has only a few typed blocked states plus `Custom`.

`Custom` is useful as a fallback, but if most features use it or are omitted, the app loses standardized privacy messaging.

## Fix Strategy

Add typed blocked classes for common user-facing capabilities.

Also add mapper:

```kotlin
fun PrivacyDecision.toPrivacyBlocked(
    capability: PrivacyCapability
): PrivacyBlocked?
```

Rules:
- `Allowed` -> null
- `NotApplicable` -> null or diagnostic
- `Denied(reason)` -> typed blocked state if known, else `Custom`
- `FailClosed(reason)` -> `Custom(capability, "Privacy check failed safely: $reason")`

## Acceptance Criteria

- ViewModels do not manually concatenate privacy denial strings.
- Fail-closed privacy denial has consistent UI copy.
- Contract test ensures every `GATE_HANDLED` capability maps to displayable UI.

---

## S3-003 — Encrypted export is blocked by raw-export privacy gate

**Severity:** High  
**File:** `ExportOptionsViewModel.kt`

## Problem

`generateExport(encryptExport: Boolean = false)` always checks:

```kotlin
PrivacyCapability.RAWBACKUP_EXPORT
```

This happens even when `encryptExport == true`.

Given `BackupPrivacyGate`, `RAWBACKUP_EXPORT` is denied when encrypted backups are enabled. That means the safer encrypted export path can still be blocked by the raw-export rule.

## Correct Policy

- If `encryptExport == false`, check `RAWBACKUP_EXPORT`.
- If `encryptExport == true`, check `ENCRYPTED_BACKUP` or a dedicated encrypted export capability.

## Fix Strategy

```kotlin
val capability = if (encryptExport) {
    PrivacyCapability.ENCRYPTED_BACKUP
} else {
    PrivacyCapability.RAWBACKUP_EXPORT
}

val decision = privacyGate.check(
    capability,
    mapOf(
        "operation" to "export",
        "encrypted" to encryptExport.toString()
    )
)
```

Better:
Create separate capability:

```kotlin
ENCRYPTED_ACCOUNTING_EXPORT
RAW_ACCOUNTING_EXPORT
```

because backup and accounting export are not the same operation.

## Acceptance Criteria

- Raw export denied when raw export disabled.
- Encrypted export allowed when encrypted export is enabled.
- Encrypted export denied when encrypted export disabled.
- Tests cover both branches.

---

## S3-004 — Map GPS privacy denial loses the reason

**Severity:** Medium/High  
**File:** `SpendingMapViewModel.kt`

## Problem

GPS privacy denial currently sets only:

```kotlin
gpsPrivacyBlocked = true
```

It discards:
- capability,
- denial type,
- reason,
- whether denial was `Denied` or `FailClosed`.

## Impact

UI can only show generic copy. This violates the “clear reason” invariant.

## Fix Strategy

Replace boolean with typed state:

```kotlin
data class SpendingMapUiState(
    val gpsPrivacyBlocked: PrivacyBlocked? = null,
    ...
)
```

When denied:

```kotlin
_state.update {
    it.copy(
        gpsPrivacyBlocked = decision.toPrivacyBlocked(
            PrivacyCapability.DEVICE_GPS_LOCATION
        )
    )
}
```

When allowed:

```kotlin
_state.update { it.copy(gpsPrivacyBlocked = null) }
```

## Acceptance Criteria

- Denied GPS shows the exact reason.
- FailClosed GPS shows a safe failure reason.
- Re-enabling GPS clears blocked state after successful check.
- ViewModel test verifies no call to `locationProvider` when denied.

---

## S3-005 — External geocoding / Overpass privacy enforcement is not structurally guaranteed

**Severity:** High  
**Files:**
- `LocationPrivacyGate.kt`
- `SpendingMapViewModel.kt`
- geocoding / Overpass services

## Problem

`LocationPrivacyGate.kt` itself contains a TODO saying geocoding call sites can bypass the gate if a developer forgets to call it.

Current visible map preflight confirms device GPS gating, but external geocoding / Overpass enforcement should be centralized.

## Fix Strategy

Use a privacy-aware wrapper:

```kotlin
class PrivacyAwareGeocodingService(
    private val privacyGate: PrivacyGate,
    private val delegate: GeocodingService
) {
    suspend fun geocode(...) {
        val decision = privacyGate.check(PrivacyCapability.EXTERNAL_GEOCODING)
        if (decision.blocksExecution()) throw PrivacyBlockedException(decision)
        return delegate.geocode(...)
    }
}
```

Or use token-based enforcement:

```kotlin
sealed class PrivacyAllowedToken private constructor(...)
```

Only `LocationPrivacyGate` can create the token.

## Acceptance Criteria

- No UI/ViewModel can call geocoding service directly.
- All external location calls require privacy-approved wrapper/token.
- Static test or architecture test prevents direct injection of raw geocoding service.

---

## S3-006 — `BackupRestoreViewModel.restoreBackup()` can leave UI stuck if repository throws

**Severity:** High  
**File:** `BackupRestoreViewModel.kt`

## Problem

`restoreBackup()` handles repository `Result.failure`, but if `databaseBackupRepository.restoreCostBackup()` throws instead of returning `Result.failure`, the coroutine exits before:
- `isRestoring` is reset,
- temp file is deleted,
- user sees an error.

## Fix Strategy

Wrap restore in `try/finally`.

```kotlin
val result = try {
    databaseBackupRepository.restoreCostBackup(tempFile, password)
} catch (t: Throwable) {
    Result.failure(t)
} finally {
    tempFile.delete()
}
```

Then process `result.fold(...)`.

## Acceptance Criteria

- `isRestoring` always returns false.
- Temp file is always deleted.
- Thrown repository error becomes visible UI error.
- Add test for thrown restore exception.

---

## S3-007 — Backup restore test likely uses unsafe mocked `InputStream`

**Severity:** High / test infra  
**File:**
- `BackupRestoreViewModelTest.kt`

## Problem

The test stubs:

```kotlin
openInputStream(uri) returns mockk(relaxed = true)
```

A relaxed `InputStream.read()` may return `0`, which can make `copyTo()` loop indefinitely or behave unrealistically.

## Fix Strategy

Use real input streams:

```kotlin
ByteArrayInputStream("backup-content".toByteArray())
```

Also test null input stream:

```kotlin
coEvery { contentResolver.openInputStream(uri) } returns null
```

## Acceptance Criteria

- Restore tests never mock raw `InputStream`.
- Null input stream is tested.
- Copy failure is tested.
- Thrown restore repository error is tested.

---

## S3-008 — Privacy settings `isSaving` is unused and updates can overlap

**Severity:** Medium  
**Files:**
- `PrivacySettingsViewModel.kt`
- `PrivacySettingsScreen.kt`

## Problem

The ViewModel has `isSaving`, but the screen does not disable switches or show saving state.

Rapid toggles can launch overlapping `repository.updateSettings()` calls.

## Fix Strategy

Option A:
- Disable all toggles while saving.

Option B:
- Use per-setting saving state.

Option C:
- Serialize updates with a `Mutex`.

Recommended:

```kotlin
private val updateMutex = Mutex()

private fun update(...) {
    viewModelScope.launch {
        updateMutex.withLock {
            ...
        }
    }
}
```

UI:

```kotlin
Switch(
    checked = checked,
    enabled = !uiState.isSaving,
    onCheckedChange = onCheckedChange
)
```

## Acceptance Criteria

- Rapid toggling cannot reorder persisted privacy settings.
- UI communicates saving.
- Test simulates two quick updates and verifies final setting.

---

## S3-009 — Raw storage privacy modes have no UI controls

**Severity:** High  
**Files:**
- `PrivacySettings.kt`
- `PrivacySettingsScreen.kt`
- `PrivacySettingsViewModel.kt`

## Problem

`PrivacySettings` includes:
- `rawNotificationStorageMode`
- `rawOcrStorageMode`
- `emailReceiptStorageMode`

But `PrivacySettingsScreen` only exposes retention-day sliders.

Users cannot choose:
- do not store raw,
- store redacted,
- store raw.

## Impact

This weakens privacy UX because core storage policies are hidden from users.

## Fix Strategy

Add radio/dropdown controls:

```kotlin
RawStorageMode.DO_NOT_STORE
RawStorageMode.STORE_REDACTED
RawStorageMode.STORE_RAW
```

For:
- notification raw text,
- OCR raw text,
- email receipt content.

Add warnings:
- `STORE_RAW` should be explicitly marked sensitive.
- Switching to `DO_NOT_STORE` should explain future-only vs cleanup behavior.

## Acceptance Criteria

- All raw storage modes are configurable.
- UI explains security tradeoff.
- ViewModel tests cover updates.
- Existing `PrivacyDoNotStoreTest` is paired with UI state tests.

---

## S3-010 — Risky privacy changes need confirmation or warning

**Severity:** Medium  
**Files:**
- `PrivacySettingsScreen.kt`
- `PrivacySettingsViewModel.kt`

## Problem

Some toggles materially increase privacy risk:
- enabling cloud AI,
- disabling redaction before cloud,
- enabling receipt image cloud upload,
- enabling GPS,
- enabling raw storage/debug persistence.

The UI currently uses plain switches with no confirmation or risk copy.

## Fix Strategy

Add confirmation dialogs for high-risk transitions:

```kotlin
if (setting == redactBeforeCloud && newValue == false) {
    showConfirmDialog(...)
}
```

At minimum:
- Show supporting text under risky switches.
- Add “requires restart” indicator where applicable.

## Acceptance Criteria

- High-risk enablement requires explicit confirmation.
- Disabling redaction before cloud has warning copy.
- Tests verify confirm/cancel behavior at ViewModel level if dialog state is in VM.

---

## S3-011 — Assistant privacy behavior is not visible as a typed UI state

**Severity:** Medium/High  
**File:**
- `AssistantViewModel.kt`

## Problem

Search found no direct `privacyGate.check` / `blocksExecution()` in `AssistantViewModel`.

This may be intentional if AI routing/use cases enforce privacy, but the UI still needs a clear blocked state.

## Risk

If cloud AI is blocked by privacy settings:
- Assistant may show a generic failure.
- Assistant may silently fall back to local/heuristic mode without explaining capability loss.
- There may be no typed `PrivacyBlockedCard`.

## Fix Strategy

Add explicit preflight or consume typed router denial.

Preferred:
- AI domain returns `AiRouteDecision.Blocked(PrivacyBlocked)`.
- Assistant UI state has:

```kotlin
val privacyBlocked: PrivacyBlocked? = null
```

Then render `PrivacyBlockedCard`.

## Acceptance Criteria

- Cloud AI disabled -> assistant shows blocked reason.
- FailClosed -> assistant shows safe failure reason.
- No cloud request is attempted.
- Test verifies denied state.

---

## S3-012 — `CompositePrivacyGate` still defaults unhandled capabilities to allowed

**Severity:** Medium  
**File:**
- `CompositePrivacyGate.kt`

## Problem

If no concrete gate handles a capability, composite logs a warning and returns `Allowed`.

This is dangerous for capabilities classified as `GATE_HANDLED`.

## Fix Strategy

Inject a policy classifier into `CompositePrivacyGate`.

```kotlin
if (!anyGateHandled && policy[capability] == GATE_HANDLED) {
    return PrivacyDecision.FailClosed("No privacy gate handled $capability")
}
```

Local-only capabilities may remain allowed.

## Acceptance Criteria

- Every `GATE_HANDLED` capability either handled or fail-closed.
- Local-only capabilities are explicitly allowed.
- Test proves adding a new capability cannot silently allow runtime checks.

---

## S3-013 — Privacy gate docs are stale

**Severity:** Low/Medium  
**Files:**
- `PrivacyGate.kt`
- `CompositePrivacyGate.kt`
- `PRIVACY_UI_ARCHITECTURE.md`

## Problem

`PrivacyGate.kt` docs say unrecognized capabilities return `Allowed` and every gate logs every check. Current implementation uses:
- `NotApplicable` from concrete gates,
- audit logging only in composite.

## Fix Strategy

Update docs:
- Concrete gates return `NotApplicable` when they do not handle a capability.
- Composite logs final audit event.
- Composite fail-closed behavior should be documented.

## Acceptance Criteria

- Docs match implementation.
- Agents are not instructed to add fail-open concrete gates.

---

## S3-014 — Privacy/security UI strings are hardcoded

**Severity:** Medium  
**Files:**
- `PrivacyBlockedCard.kt`
- `PrivacySettingsScreen.kt`
- `PrivacySettingsViewModel.kt`
- `BackupRestoreViewModel.kt`
- `BackupRestoreScreen.kt`

## Problem

Many user-facing privacy/security strings are hardcoded:
- privacy setting labels,
- blocked reasons,
- backup error messages,
- password visibility descriptions,
- select/change labels.

## Fix Strategy

Move to `strings.xml`.

Use typed reason resource IDs where possible:

```kotlin
data class PrivacyBlockedUiText(
    @StringRes val titleRes: Int,
    @StringRes val reasonRes: Int
)
```

## Acceptance Criteria

- No hardcoded user-facing privacy/security text.
- Tests can assert resource-backed copy or stable semantic tags.

---

## S3-015 — Backup screen default restart action still kills process

**Severity:** Medium  
**File:**
- `BackupRestoreScreen.kt`

## Problem

`onRestartRequired` has a default that exits the process.

Even though the callback is injectable, a composable should not embed a destructive default side effect.

## Fix Strategy

Require callback:

```kotlin
fun BackupRestoreScreen(
    onNavigateBack: () -> Unit,
    onRestartRequired: () -> Unit,
    ...
)
```

Or safe default:

```kotlin
onRestartRequired: () -> Unit = {}
```

Better:
- Host handles restart via activity-level coordinator.

## Acceptance Criteria

- No process kill as composable default.
- Production host explicitly wires restart action.
- Test verifies restart button invokes callback.

---

## S3-016 — Backup operations need ViewModel-level duplicate guards

**Severity:** Medium  
**File:**
- `BackupRestoreViewModel.kt`

## Problem

Buttons disable while backing up/restoring, but the ViewModel methods can still be called repeatedly from tests, recomposition, or future UI paths.

## Fix Strategy

Guard in ViewModel:

```kotlin
if (_uiState.value.isBackingUp) return
if (_uiState.value.isRestoring) return
```

Also prevent backup during restore and restore during backup.

## Acceptance Criteria

- Duplicate backup calls create one repository call.
- Restore cannot start during backup.
- Backup cannot start during restore.

---

## S3-017 — Privacy settings screen has no loading state

**Severity:** Medium  
**Files:**
- `PrivacySettingsViewModel.kt`
- `PrivacySettingsScreen.kt`

## Problem

The screen immediately renders default `PrivacySettings()` before repository state is observed.

This can momentarily show incorrect privacy state.

## Fix Strategy

Add:

```kotlin
val isLoading: Boolean = true
```

Set false after first settings emission.

## Acceptance Criteria

- Screen shows loading/skeleton until real settings arrive.
- No default-state flicker.
- Test verifies initial loading then loaded state.

---

## S3-018 — Missing Slice 3 tests

**Severity:** High / test gap**

Missing or insufficient tests:
- `PrivacySettingsViewModelTest`
- `PrivacyBlockedCardTest`
- `PrivacyDecisionUiMapperTest`
- `ExportOptionsPrivacyGateTest`
- `SpendingMapPrivacyDeniedTest`
- `BackupRestoreViewModelThrownRestoreTest`
- `AssistantPrivacyBlockedStateTest`
- `CompositePrivacyGateFailClosedForUnhandledCapabilityTest`

## Fix Strategy

Prioritize JVM tests first. Use Compose tests only for:
- `PrivacyBlockedCard` semantics,
- blocked card visibility,
- restart button callback.

---

# Implementation Plan for Agent

## Phase 1 — Standardize privacy blocked UI model

Files:
- `PrivacyBlocked.kt`
- `PrivacyBlockedCard.kt`
- new `PrivacyDecisionUiMapper.kt`
- `strings.xml`

Steps:
1. Add missing typed `PrivacyBlocked` cases.
2. Add mapper from `PrivacyDecision + PrivacyCapability` to `PrivacyBlocked`.
3. Localize labels/reasons.
4. Update `PrivacyBlockedCard` to use resource-backed text.
5. Add mapper tests.

Acceptance:
- Every denied/fail-closed decision can be rendered consistently.

---

## Phase 2 — Complete Privacy Settings screen

Files:
- `PrivacySettingsViewModel.kt`
- `PrivacySettingsScreen.kt`
- `PrivacySettings.kt`
- `RawStorageMode.kt`

Steps:
1. Add `isLoading`.
2. Expand `computeBlocked()`.
3. Add controls for raw storage modes.
4. Disable or serialize updates while saving.
5. Add confirmation state for risky toggles.
6. Add ViewModel tests.

Acceptance:
- Privacy settings screen represents all user-facing privacy settings.
- Blocked summary is complete.
- Rapid updates are safe.

---

## Phase 3 — Fix privacy preflight call sites

Files:
- `ExportOptionsViewModel.kt`
- `SpendingMapViewModel.kt`
- `AssistantViewModel.kt`
- geocoding services/wrappers

Steps:
1. Export: choose capability based on encrypted/raw export mode.
2. Map: replace boolean blocked state with `PrivacyBlocked`.
3. Geocoding: add privacy-aware wrapper or token.
4. Assistant: expose typed privacy blocked state.
5. Add tests for `Denied` and `FailClosed`.

Acceptance:
- Denied privacy action never proceeds.
- UI always shows reason.
- FailClosed displays safe blocked reason.
- No raw geocoding service bypass.

---

## Phase 4 — Backup robustness

Files:
- `BackupRestoreViewModel.kt`
- `BackupRestoreScreen.kt`
- `BackupRestoreViewModelTest.kt`

Steps:
1. Wrap restore repository call in `try/finally`.
2. Always delete temp file.
3. Always reset `isRestoring`.
4. Remove dangerous default restart action.
5. Add VM-level duplicate-operation guards.
6. Replace mocked input streams with `ByteArrayInputStream`.
7. Add null stream and thrown restore tests.

Acceptance:
- Restore cannot leave UI stuck.
- Tests do not hang.
- Restart UX is host-controlled.

---

## Phase 5 — Runtime privacy gate fail-closed hardening

Files:
- `CompositePrivacyGate.kt`
- `PrivacyCapabilityHandlingPolicyTest.kt`
- new runtime composite test

Steps:
1. Move policy map to production or test-visible object.
2. If `GATE_HANDLED` capability is unhandled, return `FailClosed`.
3. Keep local-only capabilities explicitly allowed.
4. Add tests.

Acceptance:
- New privacy capabilities cannot fail open at runtime.

---

# Recommended Test List

## `PrivacySettingsViewModelTest`

Cases:
- default disabled cloud AI appears in blocked list.
- disabled GPS appears in blocked list.
- disabled bank statement AI appears in blocked list.
- raw storage mode updates.
- saving failure exposes error.
- rapid updates are serialized.

## `PrivacyDecisionUiMapperTest`

Cases:
- `Denied` maps to typed blocked state.
- `FailClosed` maps to safe blocked state.
- unknown capability maps to `Custom`.
- `Allowed` maps to null.

## `ExportOptionsPrivacyGateTest`

Cases:
- raw export checks `RAWBACKUP_EXPORT`.
- encrypted export checks encrypted capability.
- denied raw export does not create export file.
- fail-closed export shows error.

## `SpendingMapPrivacyDeniedTest`

Cases:
- device GPS denied does not call provider.
- fail-closed GPS does not call provider.
- denied reason appears in state.
- allowed GPS clears blocked state.

## `BackupRestoreViewModelTest` additions

Cases:
- null input stream shows explicit error.
- throwing restore repository resets `isRestoring`.
- temp file deletion is attempted in finally.
- duplicate restore ignored.
- duplicate backup ignored.

## `AssistantPrivacyBlockedStateTest`

Cases:
- cloud AI disabled shows blocked card/state.
- fail-closed cloud AI shows safe reason.
- no cloud call attempted.

## `CompositePrivacyGateHandlingTest`

Cases:
- every `GATE_HANDLED` capability is handled.
- unhandled gate-handled capability returns `FailClosed`.
- local-only capability is explicitly allowed.

---

# Final Severity Table

| ID | Severity | Status | Summary |
|---|---:|---|---|
| S3-001 | High | Unresolved | Privacy settings blocked summary only covers 4 capabilities |
| S3-002 | Med/High | Unresolved | `PrivacyBlocked` typed model incomplete |
| S3-003 | High | Unresolved | Encrypted export uses raw-export privacy gate |
| S3-004 | Med/High | Unresolved | Map GPS denial loses reason |
| S3-005 | High | Unresolved | External geocoding / Overpass enforcement not structural |
| S3-006 | High | Unresolved | Restore throw can leave UI stuck/temp file leaked |
| S3-007 | High | Test gap | Restore test likely mocks `InputStream` unsafely |
| S3-008 | Medium | Unresolved | `isSaving` unused; privacy setting updates can overlap |
| S3-009 | High | Unresolved | Raw storage privacy modes missing from UI |
| S3-010 | Medium | UX gap | Risky privacy toggles lack confirmation/warnings |
| S3-011 | Med/High | Needs verification | Assistant lacks typed privacy-blocked UI state |
| S3-012 | Medium | Unresolved | Composite gate can allow unhandled capabilities |
| S3-013 | Low/Med | Docs drift | Privacy gate docs do not match NotApplicable/composite audit model |
| S3-014 | Medium | Unresolved | Privacy/security strings hardcoded |
| S3-015 | Medium | Partial | Backup restart callback exists but default kills process |
| S3-016 | Medium | Unresolved | Backup/restore duplicate-operation guards missing in VM |
| S3-017 | Medium | Unresolved | Privacy settings has no loading state |
| S3-018 | High | Test gap | Missing focused Slice 3 tests |

---

# Immediate Agent Task List

## Task A — Fix privacy blocked-state coverage
- Expand `PrivacyBlocked`.
- Add `PrivacyDecisionUiMapper`.
- Expand `computeBlocked()`.
- Add tests.

## Task B — Fix export privacy preflight
- Use raw capability only for raw export.
- Use encrypted capability for encrypted export.
- Add tests for both.

## Task C — Fix backup restore safety
- Wrap restore in try/finally.
- Replace mocked input streams in tests.
- Add thrown/null stream tests.

## Task D — Fix map privacy denied state
- Replace `gpsPrivacyBlocked: Boolean` with `PrivacyBlocked?`.
- Preserve reason and fail-closed state.
- Add tests.

## Task E — Complete privacy settings UI
- Add raw storage controls.
- Add loading state.
- Serialize saves.
- Add risky-setting confirmation.

---

# Source Files Reviewed

- `docs/architecture/PRIVACY_UI_ARCHITECTURE.md`
- `app/src/main/java/com/yourname/expensetracker/ui/components/PrivacyBlockedCard.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/privacysettings/PrivacySettingsScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/privacysettings/PrivacySettingsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/*`
- `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyCapabilityHandlingPolicyTest.kt`
- `app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelTest.kt`