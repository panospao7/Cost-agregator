# Final Verification — Batch 34: AI Models, Policies & Services

## Scope
### Primary scoped files
- `com/yourname/expensetracker/domain/ai/model/AiModels.kt`
- `com/yourname/expensetracker/domain/ai/model/AiRuntimeStatusModels.kt`
- `com/yourname/expensetracker/domain/ai/model/AiArtifactPresentation.kt`
- `com/yourname/expensetracker/domain/ai/model/AiLoadState.kt`
- `com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
- `com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
- `com/yourname/expensetracker/domain/ai/model/NotificationParsingModels.kt`
- `com/yourname/expensetracker/domain/ai/model/OnDeviceRuntimePresentation.kt`
- `com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt`
- `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt`
- `com/yourname/expensetracker/domain/ai/model/SemanticDuplicateModels.kt`
- `com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt`
- `com/yourname/expensetracker/domain/ai/policy/AiPolicy.kt`
- `com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt`
- `com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt`
- `com/yourname/expensetracker/domain/ai/service/AiArtifactRepository.kt`
- `com/yourname/expensetracker/domain/ai/service/AiCapabilityRouter.kt`
- `com/yourname/expensetracker/domain/ai/service/AiChatRepository.kt`
- `com/yourname/expensetracker/domain/ai/service/AiEngagementRepository.kt`
- `com/yourname/expensetracker/domain/ai/service/AiEnvironmentMonitor.kt`
- `com/yourname/expensetracker/domain/ai/service/AiSettingsRepository.kt`
- `com/yourname/expensetracker/domain/ai/service/AiWorkScheduler.kt`
- `com/yourname/expensetracker/domain/ai/service/CategorizationAssistService.kt`
- `com/yourname/expensetracker/domain/ai/service/DashboardBriefingService.kt`

### Supporting validation files read during verification
- `com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/AiArtifactRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/AiEngagementRepositoryImpl.kt`
- `com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- `com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
- `com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`
- `com/yourname/expensetracker/data/database/entity/AiArtifactEntity.kt`
- `com/yourname/expensetracker/data/database/entity/Category.kt`
- `com/yourname/expensetracker/data/database/entity/PendingReview.kt`
- `com/yourname/expensetracker/data/database/entity/ReceiptItemCategorization.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/data/ai/provider/DefaultAiEnvironmentMonitor.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceNotificationParser.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReviewPriorityScorer.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceSemanticDuplicateDetector.kt`
- `com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DetectSemanticDuplicateUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/GetAiRuntimeStatusUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/PrioritizeReviewItemsUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`
- `com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModel.kt`
- `com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt`
- `com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt`
- `com/yourname/expensetracker/ui/screens/home/HomeViewModel.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/model/AiModels.kt:85-105`; `com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt:89-110`; `com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt:66-67`; `com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt:105-109`; `com/yourname/expensetracker/ui/screens/aisettings/AiSettingsViewModel.kt:27-29,52-56` | High | Privacy / default drift | `AiSettings()` defaults enable AI receipt features, but repository hydration defaults them off. Because several `stateIn(..., initialValue = AiSettings())` sites use the constructor directly, startup state can transiently behave as opt-in before DataStore emits. | B | DOWNGRADED | Define one conservative default source and reuse it for the model constructor, repository hydration, and ViewModel initial state. |
| 2 | `com/yourname/expensetracker/domain/ai/model/AiArtifactPresentation.kt:11-16`; `com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:140-145`; `com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt:83-88`; `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:95-100`; `com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt:163-168`; `com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt:78-83`; `com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt:101-105`; `com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCase.kt:168-173` | Medium | Diagnostics / provenance loss | Artifact writers collapse `DETERMINISTIC_FALLBACK`/`DISABLED` into `AiMode.AUTO`, and `toDiagnosticsOrNull()` drops `AUTO`. Persisted artifacts therefore lose the exact route/provider/model provenance that live runtime status still knows. | B | CONFIRMED | Persist the resolved route explicitly (or add a dedicated fallback mode) and render it instead of returning `null` for `AUTO`. |
| 3 | `com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt:3-4,13,64` | Medium | Layer violation | This domain model depends directly on `Category` and `ReceiptItemCategorization` Room entities, so the domain API is coupled to the data layer. | R | CONFIRMED | Introduce domain DTOs and keep Room/entity mapping in the data layer. |
| 4 | `com/yourname/expensetracker/domain/ai/model/AiArtifactPresentation.kt:3` | Medium | Layer violation | `AiArtifactPresentation.kt` lives in the domain model package but imports `AiArtifactEntity` from the data layer. | D | CONFIRMED | Move the mapper into the data layer, or map through a domain-facing artifact DTO first. |
| 5 | `com/yourname/expensetracker/domain/ai/service/AiArtifactRepository.kt:3,15,18,24` | Medium | Layer violation | The domain repository contract exposes `AiArtifactEntity`, leaking Room/data-layer concerns to all domain callers. | R | CONFIRMED | Return domain models or minimal DTOs from the interface and keep entity mapping inside the implementation. |
| 6 | `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt:50-63` | Medium | Determinism / testability | `ReviewPriorityFactors.fromReview()` computes time sensitivity with `System.currentTimeMillis()` directly, making scores wall-clock dependent and bypassing the app's injected time abstraction. | B | CONFIRMED | Pass `now` into `fromReview(...)` or move time-based scoring into a service/use case that depends on `TimeProvider`. |
| 7 | `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt:54`; `com/yourname/expensetracker/data/repository/ReceiptRepository.kt:140`; `com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt:371-372`; `com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt:57,73` | Medium | Logic / sentinel mismatch | `merchantClarity` treats only the exact string `"Unknown"` as unclear, but other producers emit `"Unknown Merchant"` and similar placeholders. The same missing-merchant condition therefore receives different priority scores depending on producer. | R | CONFIRMED | Normalize missing-merchant representation centrally, or treat all known placeholders/blank values as unclear. |
| 8 | `com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt:14-30,52-53`; `com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt:29-59`; `com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt:26-65`; `com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt:27-103`; `com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt:27-81`; `com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt:27-64`; `com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt:25-122`; `com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt:123-153` | High | Privacy / policy gap | `canUseCloudFor()` is mostly a feature-toggle mirror, and `shouldRedact()` ignores both capability and final route. Because builders rely on that policy before or without route resolution, the code cannot enforce capability-specific cloud privacy rules reliably and can also over-redact on-device requests. | B | CONFIRMED | Make privacy policy capability-specific and route-aware (or decide route before building inputs), including explicit rules for review explanation, receipt images, and other sensitive capabilities. |
| 9 | `com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt:68-95,254-259` | High | Routing bug | In `CLOUD` preferred mode, cloud-unsupported/on-device-only capabilities (`NOTIFICATION_PARSE`, `REVIEW_PRIORITIZATION`, `SEMANTIC_DEDUPE`) do not fall back to on-device even when a local model is available; they drop straight to deterministic fallback. | R | CONFIRMED | Add an on-device-only fast path, or allow any available on-device implementation to win before deterministic fallback. |
| 10 | `com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt:48-66` | High | Routing bug | In `ON_DEVICE` preferred mode, the router never falls back to cloud for cloud-capable features when the local model is unavailable. | D | CONFIRMED | After on-device rejection, try cloud whenever policy and connectivity permit it. |
| 11 | `com/yourname/expensetracker/domain/ai/model/NotificationParsingModels.kt:33-40`; `com/yourname/expensetracker/domain/ai/model/SemanticDuplicateModels.kt:36-42`; `com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt:17-25` | Medium | Missing invariants | These models accept invalid confidence/similarity/amount/duration values, so malformed provider output can reach downstream ranking, duplicate handling, and warranty-date math unchecked. | B | CONFIRMED | Add `init` validation/clamping or validated factory functions before values enter the domain layer. |
| 12 | `com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt:91-105` | Low | Missing invariant | `CategorizationAssistInput.amount` accepts `NaN`, `Infinity`, zero, and negative values, so invalid numbers can be pushed directly into AI prompts. | D | CONFIRMED | Require finite positive amounts, or normalize invalid values before constructing the model. |
| 13 | `com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt:56-57` | High | Error handling | The AI settings DataStore flow lacks `IOException` recovery. A corrupted preferences file can terminate all AI settings consumers instead of degrading to empty/default preferences. | D | CONFIRMED | Add `catch { if (it is IOException) emit(emptyPreferences()) else throw it }` before mapping. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt:3`; `com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt:3`; `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt:3`; `com/yourname/expensetracker/domain/ai/model/SemanticDuplicateModels.kt:3` | Medium | Layer violation | Additional scoped domain AI models also import data-layer entity types (`TransactionType`, `PendingReview`) directly. The layer leak is broader than the specific files called out in the original reports. | Move shared enums/types to a domain-owned package, or introduce domain DTOs and keep entity conversions in the data layer. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #1 | `ReviewPriorityModels.kt:63` | The integer division is intentional bucketization into coarse age bands. The real issue in this function is the direct wall-clock dependency, which is already captured separately. |
| 2 | Debugger #2 | `ReviewPriorityModels.kt:73-80` | The report relies on invalid negative `PendingReview.suggestedAmount` input. In the reviewed pipelines, amounts are modeled as positive values with type/direction carried separately. |
| 3 | Debugger #4 | `DefaultAiCapabilityRouter.kt:334-345` | `WARRANTY_EXTRACTION` is not in `ON_DEVICE_IMPLEMENTED_CAPABILITIES` because there is no on-device warranty extractor in the codebase. The real bug is missing cloud fallback in `chooseOnDevicePreferred()`, already captured above. |
| 4 | Debugger #8 | `ReceiptItemCategorizationModels.kt:56,75` | The live serialization path uses category IDs encoded as JSON object keys (`CloudReceiptItemCategorizationService` and `CategorizeReceiptItemsUseCase`). `ReceiptItemCategorizationPayload` is unused and its `categoryName` comment is stale, not an active lossy pipeline. |
| 5 | Debugger #10 | `FinancialQueryModels.kt:140-155` | Kotlin `data class` equality is not based on `id` alone; two unsaved chat objects are only equal if all properties match. The claimed Set-collision behavior is not caused by the default `id = 0`. |
| 6 | Debugger #13 | `DefaultAiCapabilityRouter.kt:289-307` | The router never reaches the `"unsupported"` provider/model names because `canUseCloudFor()` hard-blocks those capabilities. The report describes a hypothetical future regression, not a current bug. |
| 7 | Debugger #17 | `DefaultAiCapabilityRouter.kt:237-251` / `AiPolicyImpl.kt:33-49` | This is a maintainability smell, but no concrete incorrect routing behavior follows from the duplication beyond the separate real routing bugs already captured. |
| 8 | Debugger #18 | `AiModels.kt:107-110` | Clearing app data also clears the backing DataStore, so the report's reproduction is incorrect. No logout/session-reset requirement is implemented in this batch. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `AiSettings` defaults ↔ DataStore hydration ↔ ViewModel initial state | High | Default drift | Constructor defaults and repository defaults disagree, and UI state holders seed from the constructor directly. That creates transient opt-in behavior before persisted settings arrive. | `domain/ai/model/AiModels.kt`, `data/repository/AiSettingsRepositoryImpl.kt`, `ui/screens/debug/DebugViewModel.kt`, `ui/screens/assistant/AssistantViewModel.kt`, `ui/screens/aisettings/AiSettingsViewModel.kt` | Share a single authoritative default-settings source across model, repository, and UI. |
| 2 | Policy ↔ input builders ↔ router/services | High | Privacy / redaction sequencing | Builders decide redaction from settings/policy alone, not from the resolved route. That makes privacy enforcement inconsistent and can degrade on-device prompts unnecessarily. | `domain/ai/policy/AiPolicyImpl.kt`, `domain/ai/usecase/ReviewExplanationInputBuilder.kt`, `domain/ai/usecase/ReceiptAssistInputBuilder.kt`, `domain/ai/usecase/CategorizationAssistInputBuilder.kt`, `domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`, `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`, `domain/ai/usecase/DedupeJudgeInputBuilder.kt`, `data/repository/WarrantyTrackerRepository.kt` | Resolve route first or make policy route-aware, then build sanitized inputs from the resolved execution path. |
| 3 | Router ↔ artifact persistence ↔ diagnostics UI | Medium | Lost provenance | Execution falls back through multiple routes, but artifact persistence collapses fallback/disabled states to `AiMode.AUTO` and presentation hides `AUTO`. Runtime diagnostics and persisted diagnostics therefore disagree. | `domain/ai/policy/DefaultAiCapabilityRouter.kt`, `domain/ai/model/AiArtifactPresentation.kt`, `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`, `domain/ai/usecase/ExplainPendingReviewUseCase.kt`, `domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`, `domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`, `domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`, `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`, `domain/ai/usecase/GenerateTransactionInsightUseCase.kt` | Persist resolved routes (not user preference modes) and render them consistently in cached artifact diagnostics. |
| 4 | Receipt/review producers ↔ review-priority scoring | Medium | Sentinel inconsistency | Different pipelines emit different placeholder merchants (`Unknown`, `Unknown Merchant`), but review-priority scoring only recognizes one placeholder value. | `data/repository/ReceiptRepository.kt`, `data/repository/ReviewQueueRepository.kt`, `domain/ai/model/ReviewPriorityModels.kt`, `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt` | Normalize missing merchants centrally instead of scoring raw sentinel strings. |
| 5 | Policy/router gating ↔ warranty extraction repository | Medium | Capability coupling | `WARRANTY_EXTRACTION` is controlled by `receiptAssistEnabled`, so turning off receipt assist silently disables warranty extraction too. | `domain/ai/policy/AiPolicyImpl.kt`, `domain/ai/policy/DefaultAiCapabilityRouter.kt`, `data/repository/WarrantyTrackerRepository.kt` | Add a dedicated warranty setting/policy branch, or explicitly document and test the coupling if it is intentional. |
| 6 | `ReviewPriorityFactors.fromReview()` ↔ `ReviewPriorityScorer.calculateBaseScore()` | Low | Contract mismatch | Batch scoring computes deterministic duplicate risk before scoring, but `calculateBaseScore()` uses the raw factors object with the placeholder `duplicateRisk = 0.5f`. Different deterministic entry points therefore score the same review differently. | `domain/ai/model/ReviewPriorityModels.kt`, `domain/ai/service/ReviewPriorityScorer.kt`, `data/ai/provider/OnDeviceReviewPriorityScorer.kt`, `domain/ai/usecase/PrioritizeReviewItemsUseCase.kt` | Make one deterministic scoring path canonical and have both single-item and batch scoring use it. |
| 7 | Preferred-mode routing ↔ concrete providers | High | Routing asymmetry | Both manual preference modes can discard an actually available AI path: `CLOUD` mode skips available on-device-only implementations, while `ON_DEVICE` mode skips cloud fallback for cloud-capable features. | `domain/ai/policy/DefaultAiCapabilityRouter.kt`, `data/ai/provider/OnDeviceNotificationParser.kt`, `data/ai/provider/OnDeviceReviewPriorityScorer.kt`, `data/ai/provider/OnDeviceSemanticDuplicateDetector.kt`, `data/repository/WarrantyTrackerRepository.kt` | Treat preference as ordering, not as an exclusive route lockout. Try the other valid route before deterministic fallback. |

## Summary
- Total verified issues: 13
- Confirmed: 13 (Critical: 0, High: 5, Medium: 7, Low: 1)
- False positives: 8
- Missed issues found: 1
- Files affected: 11/24 scoped files (+1 supporting implementation)

## Key Patterns
- **`AiMode.AUTO` is overloaded**: it represents a user preference in settings, but it is also used as a persisted execution result for fallback/disabled artifacts. That collapses important provenance.
- **Privacy logic is too shallow**: capability toggles, redaction, and route selection are not modeled together, so the code cannot cleanly express capability-specific cloud rules.
- **Domain/data boundaries are porous**: multiple domain AI models and contracts still import Room/data-layer types directly.
- **Routing is asymmetric**: both non-`AUTO` preferred modes can suppress an otherwise valid AI path and fall back too early.
- **Model contracts rely on comments instead of enforced invariants**: several AI result models document ranges/positivity requirements but accept invalid values unchecked.
