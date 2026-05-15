# Slice 11 Debug Report — AI Assistant + AI Settings

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- `ui/screens/assistant/*`
- `ui/screens/aisettings/*`
- `ui/components/ai/*`
- `domain/ai/*`
- AI routing/settings/history/runtime status
- cloud/on-device/fallback routing
- assistant financial-query interpretation/execution/navigation
- AI settings API-key and privacy/cloud controls

Sources inspected:
- Assistant folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/assistant
- `AssistantViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt
- `AssistantSheet.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantSheet.kt
- AI settings folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings
- `AiSettingsViewModel.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModel.kt
- `AiSettingsScreen.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsScreen.kt
- AI components folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai
- `AssistantResultCard.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai/AssistantResultCard.kt
- `AiChatBubble.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/components/ai/AiChatBubble.kt
- AI domain folder: https://github.com/panospao7/Cost-agregator/tree/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai
- `AiModels.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt
- `FinancialQueryModels.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt
- `GetAiRuntimeStatusUseCase.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GetAiRuntimeStatusUseCase.kt
- `InterpretFinancialQueryUseCase.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt
- `ExecuteFinancialQueryUseCase.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt
- `FinancialQueryInterpretationInputBuilder.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt
- `MapFinancialQueryToNavigationUseCase.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt
- `HybridRouter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt
- `AiPolicy.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicy.kt
- `AiPolicyImpl.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt
- `DefaultAiCapabilityRouter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt
- Existing tests:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/test/java/com/yourname/expensetracker/ui/screens/assistant/AssistantViewModelTest.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/test/java/com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModelTest.kt

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 11 is privacy-sensitive and financially sensitive. It owns:
- natural-language financial query interpretation,
- assistant query execution,
- drilldown to Transactions,
- AI runtime routing,
- AI capability toggles,
- API-key storage/testing,
- conversation history,
- cloud/on-device/fallback behavior.

The implementation has good foundations:
- AI is off for many capabilities by default.
- Cloud is opt-in by `allowCloudAi`.
- The router prevents cloud if no stored Gemini key.
- redaction exists in `FinancialQueryInterpretationInputBuilder`.
- assistant history can be disabled.
- API-key save requires a successful connection test.
- tests already cover several Assistant and AI Settings paths.

But several critical issues remain:

1. AI routing does not appear to integrate the app-level `PrivacyGate`; `AiPolicyImpl` only reads `AiSettings`.
2. AI Settings and Privacy Settings have split-brain cloud policy with no effective policy UI.
3. `AiSettingsViewModel` performs real HTTP connection tests internally, making tests flaky and violating clean architecture.
4. First-time API-key testing can be logically blocked by runtime status because runtime routing checks stored key, not the typed candidate key.
5. Blank “Save API key” can delete an existing key without explicit confirmation.
6. Runtime refresh and connection-test operations lack request IDs/in-flight guards and visible failure states.
7. Assistant runtime diagnostics expose raw query text and provider/model/route details directly in UI.
8. Assistant history stores raw queries/results, has no TTL/retention controls, and disabling history does not clear existing history.
9. Redaction has a likely leak: in redacted mode, `categoryNameToIdMap` is populated with raw category-name keys as well as aliases.
10. `AssistantResultCard` and financial query execution still have hardcoded/fallback EUR paths.
11. Financial query period and amount-filter semantics are inconsistent with other slices.
12. Assistant query execution is not cancelled if AI/privacy settings are disabled mid-query.
13. UI files are monolithic and lack focused component tests/test tags.
14. Several user-visible messages remain raw strings from ViewModels/domain.
15. `AiSettingsScreen` appears to omit a UI toggle for `receiptItemCategorizationEnabled`, despite a ViewModel setter and model flag.

Recommended strategy:
- Do not rewrite the AI platform.
- First add policy/privacy and deterministic network-test contracts.
- Then fix key management, redaction, history, and currency issues.
- Then split Assistant/AI Settings UI into pure components.
- Update docs last.

---

## 2. Baseline commands

Run first:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted Slice 11 tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*AssistantViewModelTest" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AiSettingsViewModelTest" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*InterpretFinancialQuery*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ExecuteFinancialQuery*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*FinancialQueryInterpretationInputBuilder*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AiCapabilityRouter*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AiPolicy*" --stacktrace
```

Inventory tests:

```bash
find app/src/test app/src/androidTest \
  -iname "*Assistant*" -o \
  -iname "*AiSettings*" -o \
  -iname "*FinancialQuery*" -o \
  -iname "*AiPolicy*" -o \
  -iname "*AiCapability*" -o \
  -iname "*QueryInterpretation*"
```

If Compose tests are configured:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

Stop at first compile failure.

---

## 3. Current architecture map

### Assistant query pipeline

```text
AssistantSheet
        ↓
AssistantViewModel.submitQuery(query)
        ↓
AiSettingsRepository.settings().first()
        ↓
optional AiChatRepository.createSession()
        ↓
persist user turn if history enabled
        ↓
AiChatRepository.observeMessages(sessionId).first()
        ↓
InterpretFinancialQueryUseCase
        ↓
FinancialQueryInterpretationInputBuilder
        ↓
QueryInterpretationService
        ↓
ExecuteFinancialQueryUseCase
        ↓
MapFinancialQueryToNavigationUseCase
        ↓
AssistantResultCard
        ↓
optional Transactions drilldown event
```

### AI settings/runtime pipeline

```text
AiSettingsScreen
        ↓
AiSettingsViewModel
        ↓
AiSettingsRepository.settings()
        ↓
GetAiRuntimeStatusUseCase
        ↓
AiEnvironmentMonitor + AiCapabilityRouter
        ↓
AiRuntimeStatusSummary
        ↓
capability rows/runtime guidance
```

### Cloud route policy

```text
AiSettings
        ↓
AiPolicyImpl
        ↓
DefaultAiCapabilityRouter
        ↓
environment monitor + stored API key
        ↓
AiRoute: CLOUD / ON_DEVICE / DETERMINISTIC_FALLBACK / DISABLED
```

Important: app-level `PrivacyGate` is used in `AiSettingsViewModel.probeCloudProviderConnection`, but not visibly inside `AiPolicyImpl`, `DefaultAiCapabilityRouter`, or `HybridRouter`.

---

# 4. Issues

## S11-001 — AI routing does not visibly integrate app-level PrivacyGate

Severity: Critical privacy/security  
Files:
- `AiPolicyImpl.kt`
- `DefaultAiCapabilityRouter.kt`
- `HybridRouter.kt`
- `AiSettingsViewModel.kt`
- Slice 3 privacy domain

Evidence:
- `AiPolicyImpl.canUseCloud(...)` only checks `AiSettings.aiEnabled` and `AiSettings.allowCloudAi`.
- `DefaultAiCapabilityRouter.canUseCloud(...)` checks AI settings, environment, Wi-Fi, and stored API key.
- `AiSettingsViewModel.probeCloudProviderConnection(...)` checks `PrivacyGate.CLOUD_AI_GENERAL`, but this is only for settings provider probe.
- `HybridRouter.execute(...)` chooses cloud based on router decision and does not itself check `PrivacyGate`.

Problem:
If Privacy Settings disable cloud AI but AI Settings allow cloud AI, normal AI feature routing may still choose cloud unless each downstream service independently checks privacy. That is too fragile.

Fix strategy:
Make `PrivacyGate` part of the central AI routing policy.

Implementation plan:
1. Introduce effective policy resolver:

```kotlin
data class EffectiveAiPolicy(
    val cloudAllowed: Boolean,
    val cloudBlocked: PrivacyBlocked?,
    val onDeviceAllowed: Boolean,
    val disabledReasons: List<UiText>
)

class EffectiveAiPolicyResolver @Inject constructor(
    private val privacyGate: PrivacyGate,
    private val secureKeyStorage: SecureKeyStorage,
    private val environmentMonitor: AiEnvironmentMonitor
) {
    suspend fun resolve(settings: AiSettings, capability: AiCapability): EffectiveAiPolicy
}
```

2. Update `DefaultAiCapabilityRouter` to use this resolver.
3. If `privacyGate.check(CLOUD_AI_GENERAL)` or capability-specific privacy gate blocks, route to:
   - on-device if available and allowed,
   - deterministic fallback otherwise,
   - never cloud.
4. Add audit/logging at router decision level.

Acceptance:
- privacy-disabled cloud never returns `AiRoute.CLOUD`.
- tests cover every cloud-capable capability.
- provider probe and normal assistant query use the same effective policy.
- no cloud service call can be made unless effective policy permits it.

---

## S11-002 — Split-brain cloud AI UX between AI Settings and Privacy Settings

Severity: High  
Files:
- `AiSettingsScreen.kt`
- `AiSettingsViewModel.kt`
- `PrivacySettingsScreen.kt`
- `EffectiveCloudAiPolicy.kt`

Problem:
Users can enable Cloud AI in AI Settings but still be blocked by Privacy Settings, or vice versa. AI Settings does not clearly show the effective cloud policy and blocking source.

Fix strategy:
Expose effective cloud policy in AI Settings.

Implementation plan:
```kotlin
data class AiSettingsUiState(
    ...
    val effectiveCloudPolicy: EffectiveCloudPolicyUi = EffectiveCloudPolicyUi.Loading
)

sealed interface EffectiveCloudPolicyUi {
    data object Loading : EffectiveCloudPolicyUi
    data object Allowed : EffectiveCloudPolicyUi
    data class BlockedByAiSettings(val reason: UiText) : EffectiveCloudPolicyUi
    data class BlockedByPrivacy(val blocked: PrivacyBlocked) : EffectiveCloudPolicyUi
    data class BlockedByEnvironment(val reason: UiText) : EffectiveCloudPolicyUi
}
```

Render near the Cloud AI toggle:
- “Cloud AI allowed”
- “Blocked by Privacy Settings”
- “No API key configured”
- “Wi‑Fi-only is enabled and Wi‑Fi is unavailable”

Acceptance:
- AI Settings shows the true effective cloud status.
- Cloud AI toggle alone does not imply cloud is active.
- tests cover:
  - privacy off + AI on,
  - privacy on + AI off,
  - no API key,
  - network unavailable,
  - Wi-Fi-only blocked.

---

## S11-003 — Provider connection test performs real HTTP inside ViewModel

Severity: Critical testability/architecture  
File:
- `AiSettingsViewModel.kt`
- `AiSettingsViewModelTest.kt`

Evidence:
`AiSettingsViewModel` lazily builds `OkHttpClient` and calls Gemini models endpoint directly in `probeCloudProviderConnection(...)`.

Problem:
Unit tests can hit real network or depend on hidden mock behavior. The existing “stores typed key after successful connection test” test can pass/fail based on stubs rather than a deterministic fake provider tester.

Fix strategy:
Inject a provider tester abstraction.

Implementation plan:
```kotlin
interface CloudProviderConnectionTester {
    suspend fun testGemini(apiKey: String): CloudProviderConnectionResult
}

sealed interface CloudProviderConnectionResult {
    data object Success : CloudProviderConnectionResult
    data class Failure(val reason: UiText, val debugMessage: String? = null) : CloudProviderConnectionResult
}
```

Production implementation wraps OkHttp.

ViewModel uses:
```kotlin
private val connectionTester: CloudProviderConnectionTester
```

Tests fake:
```kotlin
class FakeConnectionTester : CloudProviderConnectionTester {
    var calls = 0
    var result: CloudProviderConnectionResult = Success
}
```

Acceptance:
- `AiSettingsViewModelTest` never performs real HTTP.
- connection outcomes are deterministic.
- provider probe can be tested for success, 401, 429, 5xx, IOException.
- no OkHttp construction in ViewModel.

---

## S11-004 — First-time typed API key testing can be blocked by stored-key runtime check

Severity: High  
Files:
- `AiSettingsViewModel.kt`
- `DefaultAiCapabilityRouter.kt`

Evidence:
`testConnection()` allows using typed unsaved key as `keyToUse`, but it also calls `GetAiRuntimeStatusUseCase`. The router’s `canUseCloud()` requires a stored key in `SecureKeyStorage`.

Problem:
For a first-time key, runtime status can say “Gemini API key is not configured” because the typed candidate key is not stored yet. This can prevent testing the candidate key before saving, which conflicts with the ViewModel’s save rule: “Run a successful connection test before saving.”

Fix strategy:
Separate “runtime status for saved configuration” from “candidate key test.”

Implementation plan:
1. Validate AI/global/cloud/Wi-Fi/network/privacy policy without checking stored API key.
2. Probe provider using `keyToUse`.
3. Only after successful probe allow save.
4. Optionally update runtime summary after saving.

Add:
```kotlin
suspend fun resolveCloudTestPreconditions(
    settings: AiSettings,
    candidateKeyPresent: Boolean
): CloudTestPrecondition
```

Acceptance:
- first-time typed valid key can be tested without being stored.
- stored-key absence does not block candidate-key probe.
- no key still blocks test.
- test covers new user with no stored key.

---

## S11-005 — Blank Save API key deletes stored key without explicit confirmation

Severity: High security UX  
Files:
- `AiSettingsViewModel.kt`
- `AiSettingsScreen.kt`

Evidence:
`saveApiKey()` deletes the stored key if trimmed input is blank.

Problem:
A normal “Save API key” action can remove the credential unintentionally. Deletion should be explicit and confirmable.

Fix strategy:
Separate save/update from remove.

Implementation plan:
1. Change `saveApiKey()`:
   - blank input with stored key does not delete,
   - shows “Enter a new key or use Remove key.”
2. Add:
```kotlin
fun requestRemoveApiKey()
fun confirmRemoveApiKey()
fun cancelRemoveApiKey()
```
3. UI:
   - show “Remove key” only when stored key exists,
   - confirmation dialog required.

Acceptance:
- blank save never deletes stored key.
- remove requires explicit confirmation.
- tests verify `deleteKey` only called from confirm remove.

---

## S11-006 — Runtime refresh can race and has no visible error state

Severity: High  
Files:
- `AiSettingsViewModel.kt`
- `AiSettingsScreen.kt`

Evidence:
- Every settings emission launches `refreshRuntimeStatus()`.
- `refreshRuntimeStatus()` launches a new coroutine internally.
- No request ID/cancellation guard.
- Exceptions are not mapped to UI error; `finally` clears loading but the thrown exception is otherwise just a coroutine failure.

Problem:
A slow old refresh can overwrite a newer refresh. Runtime failures can disappear or crash the coroutine silently.

Fix strategy:
Use one refresh job/request ID and a typed runtime state.

Implementation plan:
```kotlin
sealed interface RuntimeRefreshState {
    data object Idle : RuntimeRefreshState
    data object Loading : RuntimeRefreshState
    data class Ready(val summary: AiRuntimeStatusSummary) : RuntimeRefreshState
    data class Error(val message: UiText, val lastSummary: AiRuntimeStatusSummary?) : RuntimeRefreshState
}
```

ViewModel:
```kotlin
private var runtimeRefreshJob: Job? = null
private var runtimeRefreshSeq = 0L
```

Acceptance:
- slow refresh A cannot overwrite fast refresh B.
- failure shows retry/error.
- repeated settings emissions do not spawn unbounded refreshes.
- tests use fake delayed use case.

---

## S11-007 — AI settings writes have no saving/error state

Severity: High UX/debuggability  
Files:
- `AiSettingsViewModel.kt`
- `AiSettingsScreen.kt`

Evidence:
All toggles call `update(...)`. `update(...)` launches and does not catch/report repository or proactive-sync failures.

Problem:
If DataStore update or proactive work scheduling fails, user sees toggle interaction but no trustworthy result. Rapid toggles can enqueue multiple writes without UI feedback.

Fix strategy:
Add per-setting mutation state.

Implementation:
```kotlin
data class AiSettingsMutationState(
    val settingKey: String? = null,
    val isSaving: Boolean = false,
    val error: UiText? = null
)
```

`update(settingKey, transform, sync...)`:
- set saving,
- run repository update,
- sync work if needed,
- on failure set error and let repository flow restore actual state.

Acceptance:
- failed update visible.
- toggles disabled while their setting is saving, or queue policy defined.
- proactive briefing sync failure visible/retryable.
- tests cover repository exception and sync exception.

---

## S11-008 — Missing AI Settings toggle for receipt item categorization

Severity: Medium/High feature drift  
Files:
- `AiModels.kt`
- `AiSettingsViewModel.kt`
- `AiSettingsScreen.kt`

Evidence:
`AiSettings` has `receiptItemCategorizationEnabled`. ViewModel exposes `setReceiptItemCategorizationEnabled(...)`. `AiSettingsScreen` capability section shows receipt assist, receipt image cloud, categorization fallback, and duplicate detection, but no visible receipt item categorization toggle in the inspected source.

Problem:
The feature exists in settings/model and Slice 7, but users may not be able to enable/disable it from AI Settings.

Fix strategy:
Add a capability row.

Implementation:
```kotlin
CapabilityMatrixRow(
    label = stringResource(R.string.ai_capability_receipt_item_cat),
    enabled = settings.receiptItemCategorizationEnabled,
    onEnabledChange = viewModel::setReceiptItemCategorizationEnabled,
    runtime = uiState.runtimeSummary.capabilities.find {
        it.capability == AiCapability.RECEIPT_ITEM_CATEGORIZATION
    },
    cloudFallbackAvailable = settings.aiEnabled && settings.allowCloudAi
)
```

Also include this capability in `refreshRuntimeStatus()` list.

Acceptance:
- settings screen exposes receipt item categorization.
- runtime status includes it.
- tests verify toggle calls repository update.

---

## S11-009 — Assistant diagnostics expose raw query text and provider details in UI

Severity: High privacy/security  
Files:
- `AssistantViewModel.kt`
- `AssistantSheet.kt`
- `AiRuntimeStatusModels.kt`

Evidence:
`QueryDiagnostics.toDisplayString()` includes the raw query and durations/routes/errors. `AssistantSheet` renders `runtimeDiagnostics` directly. `routeDisplayText()` exposes route/provider/model.

Problem:
User queries can contain merchant names, locations, account fragments, medical/personal purchases, etc. Showing raw diagnostics in normal UI is a privacy leak and can confuse non-debug users.

Fix strategy:
Separate user-safe status from debug diagnostics.

Implementation:
```kotlin
data class AssistantRuntimeUi(
    val userMessage: UiText?,
    val routeSummary: UiText?,
    val debugDiagnostics: String?
)
```

Rules:
- release UI shows only user-safe route summary if useful.
- debug diagnostics require `BuildConfig.DEBUG` or diagnostics setting.
- query text in diagnostics must be redacted/hash-only.

Acceptance:
- raw query is not shown in normal UI.
- provider/model details are hidden unless diagnostics mode enabled.
- test verifies no raw query in `uiState.runtimeStatusMessage` / normal diagnostics.

---

## S11-010 — Assistant history stores raw queries/results and disabling history does not clear existing history

Severity: Critical privacy  
Files:
- `AssistantViewModel.kt`
- `AiChatRepository`
- AI settings

Evidence:
When `storeConversationHistory` is true, user and assistant turns are stored. Toggling history off changes future behavior but does not clear existing history. `clearAllHistory()` exists but is immediate and no confirmation is visible in `AssistantSheet`.

Problem:
Financial chat history is sensitive. Users need clear retention semantics.

Fix strategy:
Add explicit history retention policy.

Implementation plan:
1. Add UI text near history toggle:
   - what is stored,
   - where,
   - how to delete.
2. On toggling history off, prompt:
   - “Turn off and keep existing”
   - “Turn off and delete existing”
3. Add TTL option or default retention:
```kotlin
val assistantHistoryRetentionDays: Int = 30
```
4. Add confirmation for clear all history.

Acceptance:
- disabling history does not silently leave old history without informing user.
- clear all has confirmation.
- tests verify history is not persisted when disabled and existing history policy is honored.

---

## S11-011 — Redacted query builder likely leaks raw category names through `categoryNameToIdMap`

Severity: Critical privacy  
File:
- `FinancialQueryInterpretationInputBuilder.kt`

Evidence:
In redacted mode, the builder creates aliases for categories, but also populates `categoryNameToIdMap` with raw category name keys before adding alias keys.

Problem:
If the query interpretation service serializes the full `FinancialQueryInterpretationInput` to a cloud provider, raw category names can leak despite redaction being enabled.

Fix strategy:
When `shouldRedact == true`, never include raw category or merchant names in any field sent to a cloud service.

Implementation:
```kotlin
if (shouldRedact) {
    val alias = sanitizeCategoryContext(category.name, true)
    if (alias.isNotBlank()) {
        categoryAliases[alias] = category.name // local reverse map only if not sent
        categoryLookup[alias] = category.id
        categoryNameToId[alias] = category.id
    }
} else {
    categoryNameToId[category.name] = category.id
}
```

Better:
Separate model input from local resolution metadata:
```kotlin
data class FinancialQueryInterpretationInput(
    val promptContext: FinancialQueryPromptContext,
    val localResolutionContext: FinancialQueryLocalResolutionContext
)
```

Acceptance:
- cloud-bound input contains no raw category or merchant names when redaction enabled.
- unit test scans serialized cloud payload for raw merchant/category strings.
- local alias-to-ID mapping still works.

---

## S11-012 — Redaction coverage is incomplete for arbitrary user query content

Severity: High privacy  
File:
- `FinancialQueryInterpretationInputBuilder.kt`

Evidence:
The builder replaces known recent merchant/category names and regexes for email, IBAN, cards, phones, long numbers. Unknown merchant names, addresses, notes, and short account fragments may remain.

Problem:
A user can ask “how much did I spend at Dr Smith Clinic on Main Street” and the unknown sensitive terms may be sent to cloud.

Fix strategy:
Add cloud-bound query minimization.

Implementation options:
1. For cloud interpretation, use local deterministic pre-parser first and only send normalized tokens/aliases.
2. Add privacy mode:
   - “strict redaction”: no raw free text to cloud; send only extracted categories/period/amount tokens.
3. Expand sanitizer with address/order/account patterns, but do not rely only on regex.

Acceptance:
- strict redaction test proves arbitrary merchant text is not cloud-bound.
- cloud input has bounded context and aliases only.
- if query cannot be interpreted safely, fallback asks clarification locally.

---

## S11-013 — Assistant query is not cancelled when AI/privacy settings become disabled mid-flight

Severity: High privacy/security  
Files:
- `AssistantViewModel.kt`
- `AiSettingsRepository`
- Privacy policy from Slice 3

Problem:
`submitQuery()` checks settings at start. If the user disables AI/cloud/history while a query is in flight, the current query continues.

Fix strategy:
Observe effective policy and cancel in-flight query on blocking changes.

Implementation:
```kotlin
viewModelScope.launch {
    effectiveAssistantPolicy.collect { policy ->
        if (!policy.canRunAssistant) {
            cancelCurrentQuery(reason = policy.reason)
        }
    }
}
```

Acceptance:
- disabling assistant cancels current query.
- disabling cloud/privacy cancels cloud-bound query before provider call if possible.
- UI shows “Assistant disabled; query cancelled.”
- tests simulate settings flow emission during delayed query.

---

## S11-014 — Assistant query timing uses system clock and nanoTime directly

Severity: Medium testability  
File:
- `AssistantViewModel.kt`

Evidence:
`System.currentTimeMillis()` and `System.nanoTime()` are used for diagnostics and IDs.

Problem:
Tests are nondeterministic. Diagnostics cannot be validated with fixed time. The app already has `TimeProvider`.

Fix strategy:
Inject `TimeProvider` and an ID generator.

Implementation:
```kotlin
interface AssistantIdGenerator {
    fun nextId(prefix: String): String
}
```

Acceptance:
- no direct `System.currentTimeMillis()` / `System.nanoTime()` in AssistantViewModel.
- tests use fixed time/IDs.
- diagnostics durations can be asserted or disabled.

---

## S11-015 — `AssistantResultCard` hardcodes EUR fallback formatting

Severity: Critical multi-currency correctness  
File:
- `AssistantResultCard.kt`

Evidence:
For breakdown rows, if `row.valueText` is null and `row.amount` exists, UI uses `amount_eur_format_long`.

Problem:
Even if query execution computes USD/GBP/mixed-currency values, UI fallback can display EUR.

Fix strategy:
Make financial query result rows carry typed display money or preformatted value only.

Implementation:
```kotlin
data class MoneyDisplay(
    val amount: Double,
    val currency: String,
    val formatted: String
)

data class Row(
    val label: String,
    val money: MoneyDisplay? = null,
    val count: Int? = null,
    val valueText: String? = null
)
```

Short-term:
- remove fallback EUR format.
- display `value_not_available` unless `valueText` is provided.

Acceptance:
- AssistantResultCard has no hardcoded EUR.
- tests render USD/mixed currency.
- no amount row is displayed without explicit currency.

---

## S11-016 — `ExecuteFinancialQueryUseCase` still falls back to EUR on home-currency failure

Severity: Critical financial correctness  
File:
- `ExecuteFinancialQueryUseCase.kt`

Evidence:
Several paths use `currencySettingsRepository.homeCurrency().first()` with fallback default `"EUR"`.

Problem:
A non-EUR user can receive assistant results computed or filtered against EUR when currency settings fail or are delayed.

Fix strategy:
Fail closed or return degraded result.

Implementation:
```kotlin
sealed interface FinancialQueryExecutionResult {
    data class Success(val result: FinancialQueryResult) : FinancialQueryExecutionResult
    data class CurrencyUnavailable(val message: UiText) : FinancialQueryExecutionResult
}
```

Or in `FinancialQueryResult`:
```kotlin
dataQuality = FinancialQueryDataQuality(
    isPartial = true,
    warnings = listOf("Home currency unavailable")
)
```

Recommended:
- if currency is required for filters/sorting, return clarification/error;
- if per-currency raw totals are intentional, do not call home currency.

Acceptance:
- no hidden EUR fallback in assistant financial query execution.
- tests fail home currency flow and verify no EUR output.
- query result clearly reports degraded/currency unavailable.

---

## S11-017 — Financial query amount-filter and navigation semantics are inconsistent

Severity: High financial correctness  
Files:
- `FinancialQueryModels.kt`
- `ExecuteFinancialQueryUseCase.kt`
- `MapFinancialQueryToNavigationUseCase.kt`
- Transactions filter model from Slice 5

Evidence:
Docs in `FinancialQueryModels` warn min/max are raw/non-currency-aware. `ExecuteFinancialQueryUseCase` has some currency-aware in-memory filtering for list queries. `MapFinancialQueryToNavigationUseCase` passes min/max directly to `DomainTransactionFilter`.

Problem:
Assistant answer count and Transactions drilldown can disagree for queries like “show expenses over $50” in mixed-currency data.

Fix strategy:
Define one money-filter policy.

Implementation:
```kotlin
data class QueryAmountFilter(
    val min: Double?,
    val max: Double?,
    val currency: String,
    val basis: AmountFilterBasis = HOME_CURRENCY_NORMALIZED
)
```

Navigation filter must carry basis/currency or use a precomputed result set.

Acceptance:
- assistant count equals Transactions drilldown count for mixed-currency fixtures.
- min/max filters never compare raw JPY/EUR/USD numbers without explicit policy.
- data-quality warnings are consistent.

---

## S11-018 — Local financial query period fallback is inconsistent and uses raw Calendar

Severity: Medium/High date correctness  
File:
- `InterpretFinancialQueryUseCase.kt`

Evidence:
Fallback parsing uses `Calendar.getInstance()` for last month/this week in some branches and `TimePeriodUtils` elsewhere. “This week” bare-period branch appears to use last seven days, while `resolvePeriod()` uses week range.

Problem:
Assistant totals and Transaction filters can disagree with Analytics/Home period boundaries.

Fix strategy:
Use one period resolver.

Implementation:
```kotlin
class FinancialQueryPeriodResolver @Inject constructor(
    private val timeProvider: TimeProvider,
    private val zoneProvider: ZoneProvider
) {
    fun resolve(query: String): PeriodRange
}
```

Acceptance:
- no raw Calendar in query interpretation.
- “this week,” “last month,” “this month” match app period policy.
- tests cover DST, leap year, month boundaries.

---

## S11-019 — Assistant history can inject stale/unsafe context into cloud interpretation

Severity: High privacy/correctness  
Files:
- `AssistantViewModel.kt`
- `FinancialQueryInterpretationInputBuilder.kt`

Problem:
When history is enabled, `observeMessages(sessionId).first()` is sent into input builder. Redaction strips payload JSON only when `shouldRedact` is true. But old raw user text remains stored and can influence future cloud prompts after settings changes.

Fix strategy:
Use a sanitized history snapshot for model context, distinct from stored history.

Implementation:
```kotlin
class AssistantHistoryContextBuilder {
    fun buildForModel(
        messages: List<AiChatMessage>,
        settings: AiSettings,
        route: AiRoute
    ): List<AiChatMessage>
}
```

Rules:
- max turns,
- no payload JSON for cloud,
- redact/minimize raw merchant/category/location,
- drop old turns after TTL,
- drop failed/error messages unless needed.

Acceptance:
- cloud-bound history contains no raw sensitive data under redaction.
- disabling history stops model context from using stored history.
- tests cover settings change from history-on to history-off.

---

## S11-020 — Assistant clear-all-history has no visible confirmation

Severity: Medium/High privacy UX  
File:
- `AssistantSheet.kt`

Evidence:
Delete-sweep icon calls `viewModel.clearAllHistory()` directly.

Problem:
A destructive privacy action should be confirmed, especially if history is useful to user.

Fix strategy:
Add confirmation dialog/sheet.

Acceptance:
- tapping delete shows confirmation.
- cancel keeps history.
- confirm clears all history and active session.
- tests cover both paths.

---

## S11-021 — Assistant has cancel capability but no visible cancel button

Severity: Medium UX/debuggability  
Files:
- `AssistantViewModel.kt`
- `AssistantSheet.kt`

Evidence:
`cancelCurrentQuery()` exists, but the UI only shows typing indicator; no cancel action.

Problem:
Long cloud/on-device query cannot be cancelled except by clearing session/dismissing. This is poor for privacy and cost control.

Fix strategy:
Show cancel action while loading.

Implementation:
```kotlin
if (uiState.isLoading) {
    AiTypingIndicator()
    TextButton(onClick = viewModel::cancelCurrentQuery) {
        Text(stringResource(R.string.assistant_cancel))
    }
}
```

Acceptance:
- cancel button visible while query in flight.
- cancel stops use-case job and clears loading.
- cancellation does not append error result unless product wants visible cancelled message.

---

## S11-022 — Assistant UI and AI Settings UI are monolithic

Severity: Medium/High  
Files:
- `AssistantSheet.kt`
- `AiSettingsScreen.kt`

Problem:
Both files mix route/state collection, UI sections, local dialogs, formatting, settings rows, and result rendering. This makes Compose tests hard.

Fix strategy:
Split route/content/components.

Assistant:
```text
AssistantRoute.kt
AssistantSheetContent.kt
AssistantHeader.kt
AssistantMessageList.kt
AssistantInputBar.kt
AssistantStarterPrompts.kt
AssistantDiagnosticsBanner.kt
AssistantHistoryConfirmDialog.kt
```

AI Settings:
```text
AiSettingsRoute.kt
AiSettingsContent.kt
AiGeneralSection.kt
AiProviderSection.kt
AiCapabilityMatrix.kt
AiPrivacySection.kt
AiRuntimeSection.kt
AiApiKeyDialog.kt
AiEffectivePolicyCard.kt
```

Acceptance:
- route files collect ViewModel state only.
- content components are pure state + callbacks.
- component tests can render without Hilt.

---

## S11-023 — AI Settings uses hardcoded SemanticColors/BaseNavy

Severity: Medium  
File:
- `AiSettingsScreen.kt`

Evidence:
`Scaffold` and top bar use `SemanticColors.BaseNavy` and `SemanticColors.TextPrimary`.

Problem:
This continues Slice 2 theme inconsistency. AI Settings may render poorly in light/dark/dynamic themes.

Fix strategy:
Use `MaterialTheme.colorScheme` or app theme adapter.

Acceptance:
- AI Settings light/dark smoke tests pass.
- no dark-only semantic background in normal screen unless intentionally global app shell.
- contrast is accessible.

---

## S11-024 — Runtime strings/errors are raw strings instead of UiText/resources

Severity: Medium  
Files:
- `AiSettingsViewModel.kt`
- `AssistantViewModel.kt`
- `GetAiRuntimeStatusUseCase.kt`
- `DefaultAiCapabilityRouter.kt`
- `AiRuntimeStatusModels.kt`

Evidence:
Many messages are hardcoded in ViewModels/domain:
- API key validation,
- connection failures,
- route reasons,
- assistant errors,
- runtime action labels.

Problem:
Not localizable and brittle for tests. Some exception messages may be user-visible.

Fix strategy:
Use typed error/status codes and map to `UiText` in UI.

Implementation:
```kotlin
enum class AiRuntimeBlockReason {
    AI_DISABLED,
    CAPABILITY_DISABLED,
    CLOUD_DISABLED,
    API_KEY_MISSING,
    NETWORK_UNAVAILABLE,
    WIFI_REQUIRED,
    PRIVACY_BLOCKED,
    ON_DEVICE_UNAVAILABLE
}
```

Acceptance:
- static strings moved to resources or debug-only.
- tests assert reason codes, not English.
- debug details separated from user-safe messages.

---

## S11-025 — Provider/model diagnostics are shown in release UI

Severity: Medium privacy/product  
Files:
- `AiSettingsScreen.kt`
- `AiRuntimeStatusModels.kt`
- `AssistantSheet.kt`

Problem:
Provider/model names can be useful but may be unnecessary in release UX. They also expose implementation details.

Fix strategy:
Use debug/advanced disclosure:
- default: “Cloud route ready” / “On-device route ready”
- expanded/debug: provider/model.

Acceptance:
- release UI does not show raw provider/model unless product explicitly wants transparency.
- tests cover debug vs release policy.

---

## S11-026 — API key visibility reveal is local-only but has no timeout/clear-on-dismiss policy

Severity: Medium security UX  
File:
- `AiSettingsScreen.kt`

Problem:
The API key field can be revealed; if the screen remains open, key stays visible until toggled. This is normal but should be time-bounded or cleared on screen leaving.

Fix strategy:
- auto-hide on save/test/dismiss,
- optionally auto-hide after timeout,
- never display stored key, only typed input.

Acceptance:
- reveal state resets after save/test/dismiss.
- screenshot risk reduced.
- test verifies stored key is never shown.

---

## S11-027 — AI Settings connection test lacks in-flight guard

Severity: Medium/High  
File:
- `AiSettingsViewModel.kt`

Evidence:
`testConnection()` sets `isTestingConnection = true` but does not return early if already testing.

Problem:
Double tap can start multiple provider probes.

Fix strategy:
Guard:
```kotlin
if (_uiState.value.isTestingConnection) return
```

Acceptance:
- double tap calls tester once.
- button disabled and ViewModel guarded.

---

## S11-028 — `refreshRuntimeStatus()` may run too often from settings collector

Severity: Medium  
File:
- `AiSettingsViewModel.kt`

Problem:
Each settings emission triggers a runtime refresh. Rapid toggles can create many refreshes and diagnostics writes.

Fix strategy:
Debounce or derive runtime with `flatMapLatest`.

Implementation:
```kotlin
aiSettingsRepository.settings()
    .distinctUntilChanged()
    .debounce(250)
    .flatMapLatest { settings -> flow { emit(loadRuntime(settings)) } }
```

Acceptance:
- rapid setting changes result in last runtime state only.
- manual refresh still works.

---

## S11-029 — Assistant message accessibility can read sensitive full query aloud

Severity: Medium privacy/accessibility  
File:
- `AiChatBubble.kt`

Evidence:
content description includes full text: speaker + message.

Problem:
TalkBack may announce sensitive spending queries aloud in public. The visual text is necessary, but accessibility behavior should be deliberate.

Fix strategy:
Use speaker role plus optional redacted summary in semantics, or allow normal text semantics without duplicating full content description.

Acceptance:
- no duplicate sensitive content in contentDescription.
- screen reader behavior tested.
- privacy mode can hide/redact sensitive details if desired.

---

## S11-030 — Starter prompt chip rows can overflow on small widths

Severity: Low/Medium  
File:
- `AssistantSheet.kt`

Problem:
Starter prompts are laid out in two fixed `Row`s. On small screens / large font, chips may overflow.

Fix strategy:
Use `FlowRow` or LazyHorizontalGrid/vertical list.

Acceptance:
- large font/small width render test passes.
- all starter prompts accessible.

---

## S11-031 — Domain AI router has hardcoded capability/provider strings

Severity: Medium maintainability  
Files:
- `DefaultAiCapabilityRouter.kt`
- `AiSettingsScreen.kt`

Problem:
Provider/model/capability mappings are duplicated across router, runtime screen, and docs. New capabilities can miss settings UI or policy coverage.

Fix strategy:
Create AI capability metadata table.

Implementation:
```kotlin
data class AiCapabilityMeta(
    val capability: AiCapability,
    val settingKey: AiSettingKey,
    val supportsCloud: Boolean,
    val supportsOnDevice: Boolean,
    val cloudProvider: String?,
    val cloudModel: String?,
    val onDeviceModel: String?,
    val privacyCapability: PrivacyCapability?
)
```

Acceptance:
- all capabilities have metadata.
- settings screen generated from metadata where practical.
- tests fail when a new `AiCapability` lacks policy/UI metadata.

---

## S11-032 — Query interpretation category matching is naive

Severity: Medium correctness  
File:
- `InterpretFinancialQueryUseCase.kt`

Evidence:
Local fallback matches categories with `normalized.contains(category.name.lowercase(...))`.

Problem:
Can false-match substrings and miss aliases/plurals. Example: short category names can match unrelated words.

Fix strategy:
Use tokenized matching and alias registry.

Acceptance:
- category match tests cover substring false positives.
- aliases/plurals are tested.
- provider result enrichment does not override explicit provider values.

---

# 5. Recommended new tests

## JVM/ViewModel/domain tests

### `AiEffectivePolicyResolverTest`
Required cases:
- cloud allowed only when AI settings, privacy gate, API key, network, and Wi-Fi policy all allow.
- privacy denied blocks all cloud-capable capabilities.
- ON_DEVICE preferred never falls back to cloud.
- no stored API key prevents normal cloud routing.
- candidate typed API key can still be tested.
- each `AiCapability` has metadata/policy.

### `AiSettingsConnectionTest`
Required cases:
- no key blocks test.
- invalid key blocks test.
- first-time typed key can be tested with fake tester.
- privacy denied does not call tester.
- AI disabled does not call tester.
- cloud disabled does not call tester.
- Wi-Fi-only/no Wi-Fi does not call tester.
- tester success sets success.
- tester 401/429/5xx/IO maps to typed failures.
- double test calls tester once.

### `AiSettingsApiKeyLifecycleTest`
Required cases:
- blank save with stored key does not delete.
- remove requires confirmation.
- successful candidate test then save stores key.
- editing key after successful test resets success.
- stored key is never exposed in UI state.
- save failure from secure storage is visible.

### `AiSettingsRuntimeRefreshTest`
Required cases:
- settings emission refreshes runtime once after debounce.
- rapid settings emissions last result wins.
- refresh failure sets error and clears loading.
- manual refresh cancels/replaces previous refresh.
- runtime diagnostic write failure is non-fatal.

### `AiSettingsMutationTest`
Required cases:
- each toggle calls repository transform.
- repository failure shows error.
- proactive briefing sync called only for relevant toggles.
- sync failure visible.
- receipt item categorization toggle exists.

### `AssistantViewModelQueryPipelineTest`
Required cases:
- disabled AI blocks query before interpretation.
- submit blank ignored.
- structured result adds user + result.
- clarification adds options.
- unsupported adds unsupported result.
- interpretation exception becomes safe error.
- execution exception becomes safe error.
- double submit calls pipeline once.
- cancel stops delayed interpretation/execution.
- settings disabled mid-flight cancels query.
- retry last uses last user query.
- navigation event emitted only when filter exists.

### `AssistantHistoryPrivacyTest`
Required cases:
- history disabled creates no session and persists nothing.
- history enabled persists user/assistant turns.
- toggling history off stops future persistence.
- clear all requires explicit confirmation at UI level.
- cloud-bound history is redacted/minimized.
- raw payload JSON is not sent to cloud under redaction.
- retention/TTL policy enforced if added.

### `FinancialQueryInputRedactionTest`
Required cases:
- redacted cloud input contains no raw merchant names.
- redacted cloud input contains no raw category names.
- `categoryNameToIdMap` contains aliases only.
- email/card/phone/IBAN/order-like numbers redacted.
- unknown sensitive merchant/address strict mode not cloud-bound.
- local alias resolution still maps category to ID.

### `FinancialQueryCurrencyTest`
Required cases:
- home-currency failure does not fallback to EUR.
- breakdown row requires explicit `valueText` or typed money.
- assistant result card never formats EUR by default.
- min/max filter uses home-currency normalized basis.
- drilldown Transactions count equals assistant count.

### `FinancialQueryPeriodResolverTest`
Required cases:
- today/week/month/last month/quarter/year match app policy.
- bare “this week” equals normal “this week.”
- DST/leap-year cases.
- no raw `Calendar.getInstance()` remains in query resolver.

### `FinancialQueryNavigationTest`
Required cases:
- category query maps categoryId and dateRange.
- merchant query maps merchant and dateRange.
- ownership maps MINE/NOT_MINE/SHARED/TRANSFER.
- amount filter includes basis/currency after fix.
- unsupported filters do not create misleading drilldown.

---

## Compose/component tests

### `AssistantSheetContentTest`
- disabled state renders.
- starter prompts render and wrap.
- typing indicator + cancel button render while loading.
- error + retry render.
- clear session callback.
- clear all history confirmation.
- input send disabled while blank/loading.
- navigation button callback for drilldown.

### `AssistantResultCardTest`
- summary renders title/primary/supporting.
- breakdown rows render provided `valueText`.
- no EUR fallback.
- transaction list shows count and drilldown button.
- clarification chips call callback.
- unsupported renders safe reason.
- test tags present.

### `AiChatBubbleTest`
- user/assistant alignment.
- semantics does not duplicate sensitive full text if policy changed.
- large text wraps.

### `AiSettingsContentTest`
- general toggles render.
- effective cloud policy card renders allowed/blocked states.
- provider section masks stored key.
- remove key confirmation.
- capability matrix includes receipt item categorization.
- runtime error state and retry.
- settings mutation error visible.
- light/dark smoke.

### `AiProviderSectionTest`
- reveal/hide typed key.
- save disabled/blocked until successful test.
- test button disabled while testing.
- remove key button shown only when stored key exists.
- secure note visible.

### `AiRuntimeSectionTest`
- ready/needs-attention badges.
- provider/model hidden in release policy.
- debug diagnostics shown only in debug policy.
- last refreshed formatting stable.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile and current tests.
2. Inventory AI tests:
```bash
find app/src/test app/src/androidTest \
  -iname "*Assistant*" -o \
  -iname "*AiSettings*" -o \
  -iname "*FinancialQuery*" -o \
  -iname "*AiPolicy*"
```

3. Inventory hidden EUR/default strings:
```bash
grep -R '"EUR"\|amount_eur' \
  app/src/main/java/com/yourname/expensetracker/ui/screens/assistant \
  app/src/main/java/com/yourname/expensetracker/ui/components/ai \
  app/src/main/java/com/yourname/expensetracker/domain/ai
```

4. Inventory cloud routes without PrivacyGate:
```bash
grep -R "AiRoute.CLOUD\|cloudFn\|allowCloudAi\|canUseCloud" \
  app/src/main/java/com/yourname/expensetracker/domain/ai \
  app/src/main/java/com/yourname/expensetracker/data
```

## Phase B — Add critical contract tests first

Add:
```text
AiEffectivePolicyResolverTest.kt
AiSettingsConnectionTest.kt
AiSettingsApiKeyLifecycleTest.kt
AssistantHistoryPrivacyTest.kt
FinancialQueryInputRedactionTest.kt
FinancialQueryCurrencyTest.kt
FinancialQueryPeriodResolverTest.kt
```

## Phase C — Fix privacy and key-management bugs

1. Add effective AI policy resolver with `PrivacyGate`.
2. Update router to respect privacy blocks.
3. Extract cloud provider connection tester.
4. Fix first-time typed-key test path.
5. Separate API-key remove from save.
6. Add history retention/clear confirmation policy.
7. Fix redacted category-name leak.

## Phase D — Fix query correctness

1. Remove EUR fallbacks in assistant query execution/UI.
2. Add typed money/display rows.
3. Define currency-aware amount-filter basis.
4. Update navigation filter mapping to include basis/currency.
5. Replace raw Calendar period fallback with shared resolver.
6. Add data-quality warnings for partial/currency-degraded answers.

## Phase E — Runtime/settings robustness

1. Add runtime refresh request ID/job.
2. Add runtime error state.
3. Add settings mutation state.
4. Guard connection-test double taps.
5. Cancel assistant query when effective policy disables AI.
6. Add visible cancel button.

## Phase F — UI extraction

Assistant:
- `AssistantRoute`
- `AssistantSheetContent`
- `AssistantHeader`
- `AssistantMessageList`
- `AssistantInputBar`
- `AssistantStarterPrompts`
- `AssistantDiagnosticsBanner`
- `AssistantHistoryConfirmDialog`

AI Settings:
- `AiSettingsRoute`
- `AiSettingsContent`
- `AiGeneralSection`
- `AiEffectivePolicyCard`
- `AiProviderSection`
- `AiCapabilityMatrix`
- `AiPrivacySection`
- `AiRuntimeSection`

## Phase G — Localization/theme/accessibility

1. Convert raw strings to `UiText`/resources.
2. Remove dark-only SemanticColors where not required.
3. Add test tags.
4. Hide debug/provider diagnostics behind debug/advanced mode.
5. Review TalkBack semantics for sensitive text.

---

# 7. Cross-slice golden scenarios after local tests pass

Add only after Slice 11 local tests are green:

1. Privacy disables cloud AI → Assistant cannot call cloud.
2. AI Settings cloud enabled but Privacy cloud disabled → effective policy card says blocked.
3. First-time API key test succeeds with fake tester and save stores key.
4. Blank save does not delete stored key; confirmed remove does.
5. Assistant “show groceries this month” returns result and Transactions drilldown count matches.
6. Mixed-currency “expenses over $50” answer count equals Transactions filter count.
7. Home/Analytics/Assistant month totals agree for same fixture.
8. Assistant history disabled stores no messages.
9. History enabled then disabled prompts clear/keep policy.
10. Redaction enabled → cloud-bound prompt has no raw merchant/category names.
11. Settings disabled mid-query cancels assistant query.
12. Receipt item categorization toggle controls Slice 7 item analysis availability.

---

# 8. Acceptance checklist for Slice 11 green

Slice 11 is green when:

- [ ] `:app:compileDebugKotlin` passes.
- [ ] `:app:compileDebugUnitTestKotlin` passes.
- [ ] Assistant ViewModel tests pass.
- [ ] AI Settings ViewModel tests pass.
- [ ] Effective AI policy tests pass.
- [ ] Provider connection tests are deterministic/offline.
- [ ] Cloud routing centrally respects `PrivacyGate`.
- [ ] Effective cloud policy is visible in AI Settings.
- [ ] First-time typed API key can be tested before saving.
- [ ] Blank save cannot delete stored API key.
- [ ] Runtime refresh cannot race and has error state.
- [ ] Settings updates have saving/error state.
- [ ] Assistant query cancels when settings/privacy disables it.
- [ ] Assistant diagnostics do not expose raw query in normal UI.
- [ ] History retention/delete behavior is explicit and tested.
- [ ] Redacted cloud input contains no raw merchant/category names.
- [ ] No Assistant/AI query path silently falls back to EUR.
- [ ] Assistant result rows require explicit currency/formatted value.
- [ ] Financial query amount filters match Transactions drilldown.
- [ ] Period resolution matches app-wide date policy.
- [ ] Receipt item categorization toggle exists in AI Settings.
- [ ] Assistant and AI Settings UI are split into testable components.
- [ ] Docs are updated only after source/tests are green.

---

# 9. Agent guardrails

Do:
- Treat cloud AI as privacy-gated at the central router.
- Use fake provider testers; never hit real network in unit tests.
- Keep API-key deletion explicit and confirmable.
- Redact/minimize cloud-bound query/history context.
- Remove hidden EUR fallbacks.
- Make data-quality warnings visible.
- Use fixed `TimeProvider` and fake FX rates in tests.
- Add tests before UI extraction.

Do not:
- Let ViewModels construct OkHttp clients.
- Let cloud routing depend only on AI Settings.
- Store or send raw history without an explicit retention/redaction policy.
- Display raw diagnostics/query text in normal UI.
- Let assistant answer counts differ from drilldown filters.
- Add new AI capabilities before metadata/policy/UI coverage exists.

Main invariant:

> AI Assistant and AI Settings must never call cloud without effective user/privacy permission, never persist/delete secrets silently, never leak raw sensitive query/history data through diagnostics or prompts, and must answer/drill down on one explicit financial/currency/date basis.