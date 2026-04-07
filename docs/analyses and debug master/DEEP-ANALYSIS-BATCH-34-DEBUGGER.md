# Deep Analysis — Batch 34: AI Models, Policies & Services (@debugger)

## Scope
- domain/ai/model/AiModels.kt
- domain/ai/model/AiRuntimeStatusModels.kt
- domain/ai/model/AiArtifactPresentation.kt
- domain/ai/model/AiLoadState.kt
- domain/ai/model/CaptureAssistModels.kt
- domain/ai/model/FinancialQueryModels.kt
- domain/ai/model/NotificationParsingModels.kt
- domain/ai/model/OnDeviceRuntimePresentation.kt
- domain/ai/model/ReceiptItemCategorizationModels.kt
- domain/ai/model/ReviewPriorityModels.kt
- domain/ai/model/SemanticDuplicateModels.kt
- domain/ai/model/WarrantyExtractionModels.kt
- domain/ai/policy/AiPolicy.kt
- domain/ai/policy/AiPolicyImpl.kt
- domain/ai/policy/DefaultAiCapabilityRouter.kt
- domain/ai/service/AiArtifactRepository.kt
- domain/ai/service/AiCapabilityRouter.kt
- domain/ai/service/AiChatRepository.kt
- domain/ai/service/AiEngagementRepository.kt
- domain/ai/service/AiEnvironmentMonitor.kt
- domain/ai/service/AiSettingsRepository.kt
- domain/ai/service/AiWorkScheduler.kt
- domain/ai/service/CategorizationAssistService.kt
- domain/ai/service/DashboardBriefingService.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | ReviewPriorityModels.kt:63 | MEDIUM | Logic Bug | `calculateTimeSensitivity` performs integer division. Sub-hour reviews all map to 0.2f with no gradient. Negative `createdAt` values from corrupted data produce incorrect results. | Create a PendingReview with `createdAt = System.currentTimeMillis() + 3600000`. Observe `timeSensitivity = 0.2f`. | Add guard: `val ageHours = ((System.currentTimeMillis() - createdAt) / (1000L * 60 * 60)).coerceAtLeast(0)` |
| 2 | ReviewPriorityModels.kt:73-80 | LOW | Logic Bug | `calculateAmountSignificance` does not handle negative amounts. `-500.0 < 10.0` is true → returns 0.2f instead of 0.8f. | Pass `suggestedAmount = -500.0`. Observe `amountSignificance = 0.2f`. | Use `kotlin.math.abs(amount)` in the when expression. |
| 3 | AiArtifactPresentation.kt:11-16 | MEDIUM | Semantic Bug | `toDiagnosticsOrNull()` returns `null` for `AiMode.AUTO`, but all use-cases map both `DETERMINISTIC_FALLBACK` and `DISABLED` routes to `AiMode.AUTO`. Artifacts from deterministic fallback paths are silently indistinguishable from AUTO-mode artifacts. | Trigger a capability with cloud and on-device both unavailable. Observe stored artifact has `mode = AUTO`. `toDiagnosticsOrNull()` returns `null`. | Map `DETERMINISTIC_FALLBACK` to a new `AiMode.DETERMINISTIC` value, or handle `AiMode.AUTO` with routeLabel = "Fallback". |
| 4 | DefaultAiCapabilityRouter.kt:334-345 | HIGH | Missing Capability | `WARRANTY_EXTRACTION` is NOT in `ON_DEVICE_IMPLEMENTED_CAPABILITIES`. When user sets `preferredMode = ON_DEVICE`, warranty extraction always falls back to deterministic, even if cloud is available. | Set `preferredMode = ON_DEVICE`, enable all AI. Call `decide(WARRANTY_EXTRACTION, settings)`. Get `DETERMINISTIC_FALLBACK`. | Add `WARRANTY_EXTRACTION` to `ON_DEVICE_IMPLEMENTED_CAPABILITIES` or fix `chooseOnDevicePreferred` to fall back to cloud. |
| 5 | DefaultAiCapabilityRouter.kt:48-66 | HIGH | Missing Fallback | `chooseOnDevicePreferred` does NOT fall back to cloud. If on-device is unavailable, it goes directly to `DETERMINISTIC_FALLBACK`, ignoring a potentially available cloud route. | Set `preferredMode = ON_DEVICE`, on-device model = `NOT_INSTALLED`, network available. Call `decide(DASHBOARD_BRIEFING, settings)`. Get `DETERMINISTIC_FALLBACK` instead of `CLOUD`. | Add cloud fallback to `chooseOnDevicePreferred` for capabilities where cloud is allowed. |
| 6 | AiPolicyImpl.kt:52-53 | MEDIUM | Policy Bug | `shouldRedact` always returns `settings.redactBeforeCloud` regardless of capability. On-device-only capabilities that never send data to cloud will trigger unnecessary redaction, wasting CPU. | Set `redactBeforeCloud = true`. Call `shouldRedact(settings, NOTIFICATION_PARSE)`. Returns `true` even though this capability is on-device only. | Add a guard: `if (capability in on-device-only set) return false`. |
| 7 | AiModels.kt:93-94 | LOW | Default Value Risk | `receiptAssistEnabled = true` and `receiptImageCloudEnabled = true` as defaults mean new users have AI receipt assist with cloud image analysis enabled by default. | New install. User toggles only `allowCloudAi = true`. Receipt images are now cloud-eligible without explicit opt-in. | Consider setting `receiptImageCloudEnabled = false` by default. |
| 8 | ReceiptItemCategorizationModels.kt:56,75 | MEDIUM | Type Inconsistency | `ReceiptItemCategorizationResult.taxDistribution` uses `Map<Long, Double>` (categoryId), but `ReceiptItemCategorizationPayload.taxDistribution` uses `Map<String, Double>` (categoryName). Serialization is lossy if two categories share a name. | Create two categories with the same name but different IDs. Serialize → deserialize. Map collapses to one entry. | Use consistent keys (both by ID), or use a `List<Pair<Long, String, Double>>`. |
| 9 | CaptureAssistModels.kt:91-105 | LOW | Missing Validation | `CategorizationAssistInput.amount` is `Double` with no domain constraints. Could be negative, zero, `NaN`, or `Infinity`. | Pass `amount = Double.NaN`. Observe AI prompt contains "NaN". | Add `init { require(amount > 0 && amount.isFinite()) }`. |
| 10 | FinancialQueryModels.kt:140-145 | LOW | Non-deterministic Default | `AiChatSession.id = 0` and `AiChatMessage.id = 0` use 0 as default for Room auto-generated IDs. Comparing by `id` before persistence treats all un-persisted items as identical. | Create two `AiChatSession` instances without persisting. Put them in a `Set`. Only one survives. | Document that these objects should not be compared by identity before persistence. |
| 11 | NotificationParsingModels.kt:34 | LOW | Model-Contract Mismatch | `NotificationParseResult.amount` is documented as "always positive" but has no `init` block enforcing this. | Create `NotificationParseResult(amount = -5.0, ...)`. No error. | Add `init { require(amount > 0) }`. |
| 12 | NotificationParsingModels.kt:39 | LOW | Model-Contract Mismatch | `NotificationParseResult.confidence` is documented as "0.0-1.0" but has no enforcement. Same pattern in multiple model classes. | Create `NotificationParseResult(confidence = 5.0f, ...)`. No error. | Add `init { require(confidence in 0f..1f) }`. |
| 13 | DefaultAiCapabilityRouter.kt:289-293 | LOW | Cloud Provider for On-Device-Only | `defaultCloudProviderName()` and `defaultCloudModelName()` return `"unsupported"` for on-device-only capabilities. Could leak into logs or diagnostics. | Change `canUseCloudFor` for NOTIFICATION_PARSE from `false` to `true`. Router uses `"unsupported"` as provider/model name. | Use a more explicit mechanism — throw `UnsupportedOperationException` or return a sealed class. |
| 14 | WarrantyExtractionModels.kt:19 | LOW | Non-Negative Constraint Missing | `WarrantyExtractionResult.warrantyMonths` is `Int` with no constraint. A negative value from an AI hallucination would be accepted and stored. | AI returns `warrantyMonths = -1`. No validation. | Add `init { require(warrantyMonths >= 0) }`. |
| 15 | AiArtifactPresentation.kt:3 | MEDIUM | Layer Violation | `AiArtifactPresentation.kt` is in `domain.ai.model` but imports `data.database.entity.AiArtifactEntity`. Dependency inversion violation: domain should not depend on data layer. | N/A — architectural observation. | Move `toDiagnosticsOrNull()` to a mapper in the data layer. |
| 16 | ReviewPriorityModels.kt:62-63 | LOW | Testability | `calculateTimeSensitivity` calls `System.currentTimeMillis()` directly, making it non-deterministic and untestable. | Write a unit test for `fromReview()` — result depends on when the test runs. | Accept a `nowMs: Long = System.currentTimeMillis()` parameter, or use an injected `Clock`. |
| 17 | DefaultAiCapabilityRouter.kt:237-251 vs AiPolicyImpl.kt:33-49 | MEDIUM | Duplicated Logic | `isCapabilityEnabled()` in router duplicates the `when` logic in `AiPolicyImpl.shouldAllowOnDevice()` and `canUseCloudFor()`. All three have the same mapping of capability→setting but are maintained independently. | Add a new `AiCapability` enum value. All three `when` blocks must be updated consistently. | Extract a single `fun AiCapability.isEnabledIn(settings: AiSettings): Boolean` function and reuse it. |
| 18 | AiModels.kt:107-110 | LOW | Missing Reset Logic | `AiEngagementState` tracks `lastDeliveredDashboardBriefingKey` but there's no field or method to reset/clear engagement state (e.g., when user logs out). | User clears app data. Engagement state persists in DataStore. Old briefing keys may prevent re-delivery. | Add `suspend fun clearEngagementState()` to `AiEngagementRepository`. |
| 19 | AiSettingsRepositoryImpl.kt | HIGH | Missing Error Handling | DataStore settings flow lacks `IOException` recovery, so corrupted AI prefs can break all AI settings consumers. | Corrupt AI preferences file. All AI settings consumers crash. | Add `catch { if (it is IOException) emit(emptyPreferences()) else throw it }` before mapping. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | Router → Artifact Storage → Diagnostics | HIGH | Data Flow Gap | `AiMode.AUTO` sentinel value flows from router decisions into `AiArtifactEntity.mode`, but `toDiagnosticsOrNull()` discards this as `null`. Deterministic-fallback artifacts lose their diagnostics chain entirely. | Introduce `AiMode.FALLBACK` as a distinct mode value, or handle `AUTO` in diagnostics with a meaningful label. |
| 2 | AiPolicy → Router → On-Device-Only Capabilities | MEDIUM | Policy Asymmetry | Three capabilities are marked on-device-only via three separate mechanisms with no single source of truth. If any check becomes inconsistent, cloud routing could silently activate for a privacy-sensitive capability. | Create a `val ON_DEVICE_ONLY_CAPABILITIES = setOf(...)` constant and derive all three checks from it. |
| 3 | ReceiptItemCategorization: Result → Payload | MEDIUM | Key Mismatch | `ReceiptItemCategorizationResult.taxDistribution` is `Map<Long, Double>` (by category ID), but `ReceiptItemCategorizationPayload.taxDistribution` is `Map<String, Double>` (by category name). Serialization path converts ID→name, but deserialization back would require name→ID lookup. | Use category IDs consistently in both types. |
| 4 | DuplicateVerdict ↔ SemanticDuplicateResult | LOW | Overlapping Concepts | `CaptureAssistModels.kt` defines `DuplicateVerdict` for the DedupeJudge pipeline. `SemanticDuplicateModels.kt` defines `DuplicateSuggestion` for the SemanticDuplicate pipeline. Two parallel duplicate detection systems with different result types but overlapping semantics. | Consider unifying or at least documenting the mapping between the two systems. |
| 5 | ReviewPriorityFactors.fromReview → ReviewPriorityScorer | LOW | Contract Overlap | `ReviewPriorityFactors.fromReview()` (model layer) and `ReviewPriorityScorer.calculateBaseScore()` (service layer) both compute deterministic priority scores from a `PendingReview`. Their relationship is unclear. | Clarify which is the canonical deterministic scoring path. |

## Summary
- **Total issues: 24** (19 file-level + 5 cross-component)
- **Critical: 0**, **High: 3**, **Medium: 8**, **Low: 13**
- **Files with issues: 12/24**

## Key Patterns

### 1. Sentinel Value Anti-Pattern (AiMode.AUTO)
`AiMode.AUTO` is overloaded: it means both "let the router decide" (user preference) and "no real AI was invoked" (artifact storage). This dual meaning causes `toDiagnosticsOrNull()` to return `null` for legitimate artifacts, breaking the diagnostics pipeline for deterministic-fallback results.

### 2. Triplicated Capability→Setting Mapping
The mapping from `AiCapability` to its corresponding `AiSettings` boolean is repeated in three independent `when` blocks. While Kotlin's exhaustive `when` catches missing branches at compile time, it doesn't prevent logical inconsistencies.

### 3. Missing Domain Invariants on Model Classes
Financial model classes document value ranges (e.g., "0.0-1.0 confidence", "always positive amount") in KDoc but don't enforce them with `init` blocks or value classes. This allows AI hallucinations or corrupted data to flow silently through the system.

### 4. On-Device Preferred Mode Missing Cloud Fallback
The router's `chooseOnDevicePreferred` goes directly to `DETERMINISTIC_FALLBACK` when on-device is unavailable, unlike `chooseCloudPreferred` which has an on-device fallback path. This asymmetry penalizes users who prefer on-device AI.

### 5. Layer Violations — Domain Depends on Data
Across the `domain.ai` package, 34 files import from `data.database.entity`. The `AiArtifactPresentation.kt` file in the model package is the clearest example in this batch.
