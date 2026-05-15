Prepared Slice 3 as an agent-ready debugging/report plan.

<slice-3-privacy-security-ui-debug-report.md>
# Slice 3 Debug Report — Privacy/Security UI

Target: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/components/PrivacyBlockedCard.kt`
- `ui/screens/privacysettings/*`
- `ui/screens/backup/*`
- `ui/screens/aisettings/*`
- connected privacy/security domain surfaces:
  - `domain/privacy/*`
  - `data/privacy/*`
  - `data/security/*`
  - `di/PrivacyModule.kt`

Sources inspected:
- `PrivacyBlockedCard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/PrivacyBlockedCard.kt
- `PrivacySettingsScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/privacysettings/PrivacySettingsScreen.kt
- `PrivacySettingsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/privacysettings/PrivacySettingsViewModel.kt
- `BackupRestoreScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt
- `BackupRestoreViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt
- `AiSettingsScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsScreen.kt
- `AiSettingsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModel.kt
- `PrivacyCapability.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyCapability.kt
- `PrivacyDecision.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt
- `PrivacyGate.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyGate.kt
- `CompositePrivacyGate.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt
- `CloudAiPrivacyGate.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudAiPrivacyGate.kt
- `BackupPrivacyGate.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt
- `LocationPrivacyGate.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/LocationPrivacyGate.kt
- `EffectiveCloudAiPolicy.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/EffectiveCloudAiPolicy.kt
- `PrivacyBlocked.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyBlocked.kt
- `PrivacySettingsRepositoryImpl.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacySettingsRepositoryImpl.kt
- `PrivacyModule.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/di/PrivacyModule.kt
- `BackupRestoreViewModelTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelTest.kt
- `AiSettingsViewModelTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/test/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModelTest.kt
- UI component library: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/UI_COMPONENT_LIBRARY.md
- UI map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- Segments: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md

Note: This is static debugging from GitHub source. The resolving agent must still run Gradle locally.

---

## 1. Executive summary

Slice 3 is important because privacy/security gates protect financial, receipt, AI, backup, location, and notification data.

The current implementation has solid domain foundations:
- `PrivacyCapability`
- `PrivacyDecision`
- `PrivacyGate`
- `CompositePrivacyGate`
- `PrivacyBlocked`
- `EffectiveCloudAiPolicy`
- `PrivacySettingsRepository`

But the UI integration is incomplete.

Main findings:

1. `PrivacyBlockedCard` exists but is not actually wired into `PrivacySettingsScreen` or `BackupRestoreScreen`, despite docs saying it is.
2. `PrivacySettingsViewModel` computes denied features, but the screen does not display them.
3. Privacy-denied states are inconsistent: some screens show snackbars, some show plain runtime messages, and none consistently use `PrivacyBlocked`.
4. AI cloud permission has split-brain UX: `PrivacySettings.cloudAiEnabled` and `AiSettings.allowCloudAi` must both permit cloud usage, but the screens do not clearly explain or synchronize that.
5. `AiSettingsViewModelTest` is likely flaky or broken because provider connection testing can hit real network code and privacy gate behavior is not explicitly mocked.
6. API-key saving has a risky UX: blank input can delete a stored key through the normal save path.
7. Backup/restore UI allows the user to attempt actions even when privacy policy should block them; it relies too much on downstream repository failure.
8. `PrivacyGate` docs and implementation conflict about who writes audit logs.
9. Privacy capabilities are not covered by an exhaustive policy contract; new capabilities can become fail-open unless an agent remembers to add a gate.
10. There are too few UI/component tests for privacy-denied states.

Recommended strategy:
- Do not rewrite privacy architecture.
- Keep `PrivacyGate` / `PrivacyDecision` / `PrivacyBlocked`.
- Make `PrivacyBlockedCard` the single visual primitive.
- Add contract tests before broad UI changes.
- Inject/test network-bound security pieces.
- Update docs last.

---

## 2. Baseline commands

Agent should start with:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted Slice 3 tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BackupRestoreViewModelTest" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AiSettingsViewModelTest" --stacktrace
```

If Compose UI tests are configured:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Robolectric Compose tests are configured:

```bash
./gradlew :app:testDebugUnitTest --tests "*PrivacyBlockedCard*" --stacktrace
```

Stop on first compile failure. Do not patch behavior until production and test Kotlin compile.

---

## 3. Current architecture map

### Privacy settings UI

Files:
- `PrivacySettingsScreen.kt`
- `PrivacySettingsViewModel.kt`

Current behavior:
- Observes `PrivacySettingsRepository.observeSettings()`.
- Renders toggles for:
  - notification capture
  - cloud AI
  - redaction before cloud
  - receipt image cloud upload
  - bank statement AI
  - external geocoding
  - background location backfill
  - device GPS
  - encrypted backups
  - raw notification retention
  - raw OCR retention
  - debug persistence
- ViewModel computes a list of denied feature labels.
- Screen does not display denied features.

### Backup/restore UI

Files:
- `BackupRestoreScreen.kt`
- `BackupRestoreViewModel.kt`

Current behavior:
- Creates encrypted `.costbackup` bundle.
- Restores `.costbackup`.
- Shows snackbar success/error.
- Shows non-dismissible restart-required banner after restore requiring restart.
- Does not visibly preflight privacy denial.
- Does not use `PrivacyBlockedCard`.

### AI settings UI

Files:
- `AiSettingsScreen.kt`
- `AiSettingsViewModel.kt`

Current behavior:
- Manages AI settings, provider API key input, runtime status, capability toggles.
- Uses `SecureKeyStorage`.
- Uses `PrivacyGate` before probing cloud provider.
- Uses real `OkHttpClient` inside ViewModel.
- Does not clearly show effective cloud AI policy from both privacy and AI settings.

### Domain privacy

Files:
- `PrivacyCapability.kt`
- `PrivacyDecision.kt`
- `PrivacyGate.kt`
- `CompositePrivacyGate.kt`
- `CloudAiPrivacyGate.kt`
- `BackupPrivacyGate.kt`
- `LocationPrivacyGate.kt`
- `NotificationPrivacyGate.kt`
- `EffectiveCloudAiPolicy.kt`
- `PrivacyBlocked.kt`

Current behavior:
- Composite gate combines concrete gates.
- Concrete gates return `NotApplicable` for unsupported capabilities.
- Composite returns allowed if no gate handles a capability, with warning log.
- `PrivacyDecision` includes `Allowed`, `NotApplicable`, `Denied`, `FailClosed`.
- `blocksExecution()` correctly blocks `Denied` and `FailClosed`.

---

# 4. Issues

## S3-001 — Docs claim `PrivacyBlockedCard` is consumed, but source does not use it

Severity: High  
Type: source/docs drift + broken privacy UX  
Files:
- `ui/components/PrivacyBlockedCard.kt`
- `ui/screens/privacysettings/PrivacySettingsScreen.kt`
- `ui/screens/backup/BackupRestoreScreen.kt`
- `docs/reference/UI_COMPONENT_LIBRARY.md`
- `docs/reference/COMPREHENSIVE_UI_MAP.md`

Evidence:
- Docs list `PrivacyBlockedCard` as a privacy/security component and say it is consumed by privacy settings and backup/restore.
- Source for `PrivacySettingsScreen.kt` and `BackupRestoreScreen.kt` does not import or render `PrivacyBlockedCard`.

Problem:
The privacy-denied visual standard exists only as a component, not as actual UI behavior. Agents reading docs will assume blocked states are visible, but users see toggles/snackbars instead.

Fix strategy:
Wire `PrivacyBlockedCard` into real screens.

Implementation plan:
1. Add typed blocked-state list to `PrivacySettingsUiState`.
2. Render blocked-state summary near top of `PrivacySettingsScreen`.
3. Add backup privacy status to `BackupRestoreUiState`.
4. Render `PrivacyBlockedCard` above disabled backup actions.
5. Add component test and screen-level smoke tests.

Acceptance:
- `PrivacyBlockedCard` appears when at least one privacy-relevant capability is blocked.
- Docs match source after tests pass.
- Grep confirms actual usage:

```bash
grep -R "PrivacyBlockedCard" app/src/main/java/com/yourname/expensetracker/ui
```

Expected result should include at least:
- `PrivacySettingsScreen.kt`
- `BackupRestoreScreen.kt`
- possibly `AiSettingsScreen.kt`

---

## S3-002 — `PrivacyBlockedCard` API is too weak for a cross-cutting privacy primitive

Severity: High  
File:
- `ui/components/PrivacyBlockedCard.kt`

Current design:
- parameters: `capability: String`, `reason: String`
- `capability` is unused
- visible title is hardcoded
- reason is raw string
- no action
- no semantics/test tag
- no typed connection to `PrivacyBlocked` or `PrivacyCapability`

Problem:
This makes the component easy to misuse and hard to test. It cannot distinguish capability types, cannot expose consistent accessibility text, and cannot offer recovery navigation.

Fix strategy:
Introduce typed API while preserving backward compatibility.

Implementation plan:

```kotlin
@Composable
fun PrivacyBlockedCard(
    blocked: PrivacyBlocked,
    modifier: Modifier = Modifier,
    onOpenPrivacySettings: (() -> Unit)? = null
) {
    PrivacyBlockedCardContent(
        title = stringResource(R.string.privacy_feature_disabled_title),
        capabilityLabel = blocked.capability.displayLabel(),
        reason = blocked.reason,
        modifier = modifier,
        onOpenPrivacySettings = onOpenPrivacySettings
    )
}

@Deprecated("Use typed PrivacyBlocked overload")
@Composable
fun PrivacyBlockedCard(
    capability: String,
    reason: String,
    modifier: Modifier = Modifier
) {
    PrivacyBlockedCardContent(
        title = stringResource(R.string.privacy_feature_disabled_title),
        capabilityLabel = capability,
        reason = reason,
        modifier = modifier,
        onOpenPrivacySettings = null
    )
}
```

Add:
- `PrivacyCapability.displayLabel()`
- string resources
- semantics:
  - merged content description
  - test tag e.g. `"privacy_blocked_card"`
- optional settings button:
  - `"Review privacy settings"`

Acceptance:
- capability is displayed or included in accessibility text.
- no hardcoded visible English strings remain in the component.
- component has unit/Compose test:
  - renders title
  - renders reason
  - exposes capability
  - optional action invokes callback.

---

## S3-003 — `PrivacySettingsScreen` ignores ViewModel’s denied-state data

Severity: High  
Files:
- `PrivacySettingsScreen.kt`
- `PrivacySettingsViewModel.kt`

Evidence:
- ViewModel computes `deniedFeatures`.
- Screen reads `settings` but does not render `deniedFeatures`.

Problem:
The user can turn off major capabilities but receives no summary of what is blocked. The UI also misses the chance to reuse the standardized blocked card.

Fix strategy:
Render a blocked-capability summary section.

Implementation plan:
1. Replace raw denied strings with typed blocked entries:

```kotlin
data class PrivacySettingsUiState(
    val settings: PrivacySettings = PrivacySettings(),
    val isSaving: Boolean = false,
    val blocked: List<PrivacyBlocked> = emptyList(),
    val errorMessage: String? = null
)
```

2. Replace `computeDeniedFeatures` with:

```kotlin
private fun computeBlocked(settings: PrivacySettings): List<PrivacyBlocked> = buildList {
    if (!settings.cloudAiEnabled) {
        add(PrivacyBlocked.CloudAiDisabled("Cloud AI is disabled in privacy settings"))
    }
    if (!settings.receiptImageCloudEnabled) {
        add(PrivacyBlocked.ReceiptImageUploadDisabled())
    }
    if (!settings.externalGeocodingEnabled) {
        add(PrivacyBlocked.ExternalGeocodingDisabled())
    }
    if (!settings.notificationCaptureEnabled) {
        add(PrivacyBlocked.NotificationCaptureDisabled())
    }
    if (!settings.encryptedBackupEnabled) {
        add(PrivacyBlocked.Custom(
            PrivacyCapability.ENCRYPTED_BACKUP,
            "Encrypted backup is disabled"
        ))
    }
}
```

3. In `PrivacySettingsScreen`, add near top:

```kotlin
if (uiState.blocked.isNotEmpty()) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.privacy_disabled_features_title),
                style = MaterialTheme.typography.titleSmall
            )
            uiState.blocked.forEach {
                PrivacyBlockedCard(blocked = it)
            }
        }
    }
}
```

4. Add snackbar or inline error for failed updates.

Acceptance:
- blocked summary appears when disabling each major category.
- screen has test for blocked summary.
- `deniedFeatures: List<String>` is removed or deprecated.

---

## S3-004 — Privacy settings save failures are silent to users

Severity: Medium/High  
File:
- `PrivacySettingsViewModel.kt`

Current behavior:
- `update` catches exceptions and logs.
- UI has no `errorMessage`.
- `isSaving` exists but screen does not use it.

Problem:
If DataStore update fails, the user sees a toggle change attempt but no clear failure. Privacy settings must be explicit and trustworthy.

Fix strategy:
Expose save failure and disable controls while saving.

Implementation plan:
1. Extend UI state:

```kotlin
val errorMessage: String? = null
```

2. On failure:
```kotlin
_uiState.value = _uiState.value.copy(
    errorMessage = "Could not update privacy setting. Please retry."
)
```

3. Add `clearError()`.
4. In screen:
   - show snackbar or inline error.
   - disable toggles while `isSaving`.

5. Improve update model to identify setting:

```kotlin
private fun update(
    settingName: String,
    transform: (PrivacySettings) -> PrivacySettings
)
```

Acceptance:
- failed update is visible.
- tests cover repository exception.
- controls do not rapidly enqueue multiple conflicting writes while saving.

---

## S3-005 — Cloud AI privacy has split-brain UX between Privacy Settings and AI Settings

Severity: High  
Files:
- `PrivacySettingsScreen.kt`
- `AiSettingsScreen.kt`
- `AiSettingsViewModel.kt`
- `EffectiveCloudAiPolicy.kt`

Evidence:
- `EffectiveCloudAiPolicy` allows cloud only if privacy settings and AI settings both permit it.
- `PrivacySettingsScreen` toggles `PrivacySettings.cloudAiEnabled`.
- `AiSettingsScreen` toggles `AiSettings.allowCloudAi`.
- Neither screen clearly explains that both must be enabled.

Problem:
A user may enable Cloud AI in one screen and still be blocked by the other screen. This will look like a bug.

Fix strategy:
Expose effective cloud policy to UI and show the blocking source.

Implementation options:

### Option A — Recommended short-term
Keep both settings but show effective policy in both screens.

Add use case:
```kotlin
class GetEffectiveCloudAiPolicyUseCase @Inject constructor(
    private val resolver: EffectiveCloudAiPolicyResolver
) {
    suspend operator fun invoke(): EffectiveCloudAiPolicy = resolver.resolve()
}
```

In `AiSettingsViewModel`, expose:
```kotlin
val effectiveCloudPolicy: EffectiveCloudAiPolicyUi
```

Render:
- "Cloud AI blocked by Privacy Settings"
- "Cloud AI blocked by AI Settings"
- "Cloud AI allowed"

### Option B — Larger refactor
Make one screen the owner of global cloud opt-in and the other only owns provider/capability details.

Acceptance:
- AI Settings shows why cloud is unavailable.
- Privacy Settings shows that AI Settings may also block cloud.
- Tests cover:
  - privacy off + AI on = blocked by privacy
  - privacy on + AI off = blocked by AI settings
  - both on = allowed.

---

## S3-006 — `AiSettingsViewModel` provider connection test is not fully testable and may hit real network

Severity: High  
Files:
- `AiSettingsViewModel.kt`
- `AiSettingsViewModelTest.kt`

Evidence:
- ViewModel constructs an `OkHttpClient` internally.
- `testConnection()` can call `probeCloudProviderConnection()`.
- Existing tests create a relaxed `PrivacyGate` mock but do not explicitly return `PrivacyDecision.Allowed`.
- A “successful connection test” unit test may perform a real HTTP request or fail on relaxed privacy gate behavior.

Problem:
Unit tests for security settings must be deterministic and offline.

Fix strategy:
Inject a small provider tester abstraction.

Implementation plan:

```kotlin
interface CloudProviderConnectionTester {
    suspend fun testGemini(apiKey: String): CloudProviderConnectionResult
}

sealed interface CloudProviderConnectionResult {
    data object Success : CloudProviderConnectionResult
    data class Failure(val message: String) : CloudProviderConnectionResult
}
```

Production implementation wraps OkHttp.

ViewModel becomes:

```kotlin
class AiSettingsViewModel @Inject constructor(
    ...
    private val cloudProviderConnectionTester: CloudProviderConnectionTester,
    private val privacyGate: PrivacyGate
)
```

Then:

```kotlin
val gateCheck = privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)
if (gateCheck.blocksExecution()) {
    setFailure("Cloud AI is blocked by privacy settings: ${gateCheck.reason()}")
    return
}

val providerFailure = runtimeFailure ?: when (
    val result = cloudProviderConnectionTester.testGemini(keyToUse)
) {
    Success -> null
    is Failure -> result.message
}
```

Tests:
- privacy denied -> provider tester not called.
- no network -> provider tester fake returns success/failure.
- invalid key -> provider tester not called.
- cloud disabled -> provider tester not called.
- Wi-Fi-only blocked -> provider tester not called.

Acceptance:
- `AiSettingsViewModelTest` never uses real network.
- `privacyGate.check(...)` is explicitly stubbed.
- test names document no key persistence on failed test.

---

## S3-007 — API key save path can silently delete a stored key

Severity: High security UX  
Files:
- `AiSettingsScreen.kt`
- `AiSettingsViewModel.kt`

Current behavior:
- If key input is blank, `saveApiKey()` deletes the stored key.
- UI shows a normal save button.
- If a key is stored and input is blank, pressing Save can remove the key.

Problem:
This is risky and surprising. Key deletion should be explicit and confirmable.

Fix strategy:
Separate save/update from delete.

Implementation plan:
1. Change `saveApiKey()`:
   - If blank and stored key exists, do not delete.
   - Set validation message: “Enter a new key or use Remove key.”
2. Add explicit:

```kotlin
fun removeApiKeyConfirmed()
```

3. UI:
   - Show “Remove key” button only when `hasStoredApiKey`.
   - Confirmation dialog:
     - title: Remove API key?
     - confirm: Remove
     - cancel: Cancel

Acceptance:
- blank save never deletes stored key.
- remove requires explicit confirmation.
- tests:
  - blank save with stored key does not call `deleteKey`.
  - remove confirmed calls `deleteKey`.
  - remove clears `hasStoredApiKey`.

---

## S3-008 — Backup/restore screen does not preflight or display privacy-blocked backup state

Severity: High  
Files:
- `BackupRestoreScreen.kt`
- `BackupRestoreViewModel.kt`
- `BackupPrivacyGate.kt`

Current behavior:
- `BackupPrivacyGate` can deny encrypted backup when disabled.
- `BackupRestoreScreen` still shows enabled encrypted-backup create UI unless blocked later by repository failure.
- No `PrivacyBlockedCard`.

Problem:
Privacy-denied backup action should be visible before the user enters password and taps create.

Fix strategy:
Preflight backup capability in ViewModel and expose typed blocked UI.

Implementation plan:
Inject `PrivacyGate`:

```kotlin
@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseBackupRepository: DatabaseBackupRepository,
    private val restoreMaintenanceMode: RestoreMaintenanceMode,
    private val privacyGate: PrivacyGate
)
```

Extend state:

```kotlin
data class BackupRestoreUiState(
    ...
    val backupBlocked: PrivacyBlocked? = null,
    val restoreBlocked: PrivacyBlocked? = null
)
```

On init and before action:

```kotlin
private suspend fun refreshPrivacyState() {
    val decision = privacyGate.check(PrivacyCapability.ENCRYPTED_BACKUP)
    _uiState.value = _uiState.value.copy(
        backupBlocked = if (decision.blocksExecution()) {
            PrivacyBlocked.Custom(PrivacyCapability.ENCRYPTED_BACKUP, decision.reason())
        } else null
    )
}
```

Before create:
```kotlin
val decision = privacyGate.check(PrivacyCapability.ENCRYPTED_BACKUP)
if (decision.blocksExecution()) {
    _uiState.value = _uiState.value.copy(
        isBackingUp = false,
        backupBlocked = PrivacyBlocked.Custom(PrivacyCapability.ENCRYPTED_BACKUP, decision.reason()),
        errorMessage = null
    )
    return
}
```

Screen:
```kotlin
uiState.backupBlocked?.let {
    PrivacyBlockedCard(blocked = it)
}
Button(
    enabled = !uiState.isBackingUp &&
              createPassword.isNotBlank() &&
              uiState.backupBlocked == null
)
```

Acceptance:
- create-backup button disabled when encrypted backup blocked.
- blocked card explains reason.
- no repository call occurs when privacy gate denies.
- test verifies `createCostBackup` not called on privacy denial.

---

## S3-009 — Backup restore file read can create an empty temp backup on null input stream

Severity: Medium/High  
File:
- `BackupRestoreViewModel.kt`

Current behavior:
- Opens input stream from URI.
- If stream is null, code can still create temp file and continue.

Problem:
A null content stream should be a user-visible read failure, not a restore attempt with an empty file.

Fix strategy:
Explicitly require non-null stream.

Implementation plan:
```kotlin
val tempFile = runCatching {
    val temp = File.createTempFile("restore_", ".costbackup", context.cacheDir)
    val input = context.contentResolver.openInputStream(uri)
        ?: error("Could not open selected backup file")
    input.use { src ->
        temp.outputStream().use { dst -> src.copyTo(dst) }
    }
    temp
}.getOrElse { error ->
    _uiState.value = _uiState.value.copy(
        isRestoring = false,
        errorMessage = "Failed to read backup file: ${error.message}"
    )
    return@launch
}
```

Acceptance:
- null stream produces read error.
- repository restore not called.
- temp file cleanup is safe.

---

## S3-010 — Restart action is process-kill logic embedded directly in Compose UI

Severity: Medium  
File:
- `BackupRestoreScreen.kt`

Current behavior:
- restart-required banner button calls `Runtime.getRuntime().exit(0)` directly in UI.

Problem:
This is hard to test, hard to substitute, and dangerous if accidentally invoked in tests/previews.

Fix strategy:
Inject or pass restart action from host layer.

Implementation plan:
Change screen signature:

```kotlin
@Composable
fun BackupRestoreScreen(
    onNavigateBack: () -> Unit,
    onRestartRequired: () -> Unit = { Runtime.getRuntime().exit(0) },
    viewModel: BackupRestoreViewModel = hiltViewModel()
)
```

Button:
```kotlin
Button(onClick = onRestartRequired)
```

Tests can pass fake lambda.

Acceptance:
- UI test can verify button callback without killing process.
- production behavior unchanged.

---

## S3-011 — `PrivacyGate` audit contract conflicts with implementation

Severity: Medium/High  
Files:
- `PrivacyGate.kt`
- `CompositePrivacyGate.kt`
- concrete gates
- `PrivacyModule.kt`

Evidence:
- `PrivacyGate` documentation says every check call must be recorded by each implementation.
- `CompositePrivacyGate` documentation says only composite writes the final audit event.
- Concrete gates inject `PrivacyAuditLogger` but do not log.

Problem:
Conflicting contracts confuse agents and future contributors. If a concrete gate is accidentally injected directly, audit logging may be lost.

Fix strategy:
Make composite the only injectable `PrivacyGate` and update docs.

Implementation plan:
1. Update `PrivacyGate.kt` contract:
   - concrete gates return decisions only.
   - composite is responsible for final audit.
2. Remove unused `auditLogger` from concrete gates, or use `@Suppress("unused")` with comment if kept for future.
3. Introduce internal marker interface:

```kotlin
internal interface PrivacySubGate {
    suspend fun check(...)
}
```

Better:
- Keep public `PrivacyGate` only for composite.
- Concrete implementations implement `PrivacySubGate`.

This may be larger, so short-term patch:
- update comments
- add DI test/contract ensuring injected `PrivacyGate` is `CompositePrivacyGate`.

Acceptance:
- no conflicting documentation.
- audit behavior tested once at composite level.
- no direct Hilt binding of concrete gate as `PrivacyGate`.

---

## S3-012 — New `PrivacyCapability` values can fail open if no gate handles them

Severity: High security maintainability  
Files:
- `PrivacyCapability.kt`
- `CompositePrivacyGate.kt`
- concrete gates

Current behavior:
- Composite logs warning if no gate handled a capability and defaults to allowed.

Problem:
For a privacy/security system, a new capability being accidentally unhandled should not silently allow execution.

Fix strategy:
Add explicit capability policy inventory.

Implementation plan:

```kotlin
enum class PrivacyCapabilityHandling {
    HANDLED_BY_GATE,
    EXPLICITLY_PUBLIC_OR_LOCAL,
    DEPRECATED
}

fun PrivacyCapability.handlingPolicy(): PrivacyCapabilityHandling = when (this) {
    NOTIFICATION_CAPTURE,
    NOTIFICATION_PACKAGE_ALLOWLIST,
    CLOUD_AI_RECEIPT_ASSIST,
    CLOUD_AI_ITEM_CATEGORIZATION,
    CLOUD_AI_WARRANTY_EXTRACTION,
    CLOUD_AI_BANK_STATEMENT,
    AI_BANK_STATEMENT_PARSING,
    CLOUD_AI_DAILY_BRIEFING,
    CLOUD_AI_GENERAL,
    RECEIPT_IMAGE_CLOUD_UPLOAD,
    EXTERNAL_GEOCODING,
    BACKGROUND_LOCATION_BACKFILL,
    DEVICE_GPS_LOCATION,
    RAWBACKUP_EXPORT,
    ENCRYPTED_BACKUP,
    OVERPASS_API -> HANDLED_BY_GATE

    RAW_NOTIFICATION_RETENTION,
    RAW_OCR_RETENTION,
    DEBUG_DATA_PERSISTENCE,
    TIMBER_PII_LOGGING -> EXPLICITLY_PUBLIC_OR_LOCAL
}
```

Add test:
```

:warning: The provider stream ended early, so this response may be incomplete.