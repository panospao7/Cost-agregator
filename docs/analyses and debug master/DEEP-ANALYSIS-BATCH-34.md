# Deep Analysis — Batch 34: AI Models, Policies & Services (@reviewer)

## Scope
- `domain/ai/model/AiModels.kt`
- `domain/ai/model/AiRuntimeStatusModels.kt`
- `domain/ai/model/AiArtifactPresentation.kt`
- `domain/ai/model/AiLoadState.kt`
- `domain/ai/model/CaptureAssistModels.kt`
- `domain/ai/model/FinancialQueryModels.kt`
- `domain/ai/model/NotificationParsingModels.kt`
- `domain/ai/model/OnDeviceRuntimePresentation.kt`
- `domain/ai/model/ReceiptItemCategorizationModels.kt`
- `domain/ai/model/ReviewPriorityModels.kt`
- `domain/ai/model/SemanticDuplicateModels.kt`
- `domain/ai/model/WarrantyExtractionModels.kt`
- `domain/ai/policy/AiPolicy.kt`
- `domain/ai/policy/AiPolicyImpl.kt`
- `domain/ai/policy/DefaultAiCapabilityRouter.kt`
- `domain/ai/service/AiArtifactRepository.kt`
- `domain/ai/service/AiCapabilityRouter.kt`
- `domain/ai/service/AiChatRepository.kt`
- `domain/ai/service/AiEngagementRepository.kt`
- `domain/ai/service/AiEnvironmentMonitor.kt`
- `domain/ai/service/AiSettingsRepository.kt`
- `domain/ai/service/AiWorkScheduler.kt`
- `domain/ai/service/CategorizationAssistService.kt`
- `domain/ai/service/DashboardBriefingService.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/ai/model/AiModels.kt:85-105` | HIGH | Privacy / Defaults | `AiSettings()` enables `aiEnabled`, `receiptAssistEnabled`, and `receiptImageCloudEnabled` by default. Multiple consumers construct `AiSettings()` before DataStore emits, so startup/default state can temporarily treat AI as opted in, which conflicts with the documented opt-in privacy posture. | Make constructor defaults conservative, and centralize one canonical default source reused by both the model and repository hydration. |
| 2 | `domain/ai/model/AiArtifactPresentation.kt:11-16` | MEDIUM | Observability / Routing | `toDiagnosticsOrNull()` drops all diagnostics when `mode == AiMode.AUTO`. Artifact writers persist `DETERMINISTIC_FALLBACK` and `DISABLED` executions as `AiMode.AUTO`, so cached artifacts lose route/provider provenance exactly when fallback behavior matters most. | Persist the resolved `AiRoute`, or at minimum render `AiMode.AUTO` as an explicit fallback/disabled label instead of returning `null`. |
| 3 | `domain/ai/model/ReceiptItemCategorizationModels.kt:3-4,13,64` | MEDIUM | Architecture | This domain model imports `data.database.entity.Category` and `data.database.entity.ReceiptItemCategorization`, which makes the domain layer depend directly on Room/data-layer entities. That breaks layering and makes the contract harder to evolve or test independently. | Replace data-layer entity types with domain DTOs and keep Room/entity mapping in the data layer. |
| 4 | `domain/ai/model/ReviewPriorityModels.kt:62-70` | MEDIUM | Determinism / Testability | `calculateTimeSensitivity()` uses `System.currentTimeMillis()` directly inside the model companion. The same review item therefore gets different factor values depending on wall-clock time, bypassing the app's injected `TimeProvider` and making scoring harder to test deterministically. | Pass `now` into `fromReview(...)`, or move time-based scoring into a service/use case that already depends on `TimeProvider`. |
| 5 | `domain/ai/model/ReviewPriorityModels.kt:50-58` | MEDIUM | Logic Bug | `merchantClarity` only treats the exact string `"Unknown"` as unclear. Other producers emit `"Unknown Merchant"` and blank-like values, so unresolved receipt merchants can be scored as clear (`0.8f`) and sorted too low in the review queue. | Normalize missing-merchant handling in one place (nullable/enum preferred), or treat all known placeholders/blank values as unclear. |
| 6 | `domain/ai/policy/AiPolicyImpl.kt:14-30,52-53` | HIGH | Policy / Privacy | The capability-aware policy is effectively capability-agnostic for cloud privacy: `canUseCloudFor()` does not enforce requirements like redaction for `REVIEW_EXPLANATION`, and `shouldRedact()` ignores `capability` entirely. This leaves documented per-capability privacy rules unenforced. | Encode capability-specific cloud rules in policy (for example, require redaction for review explanation, define receipt-extraction image/text rules explicitly, and fail closed when a capability's privacy requirements are unmet). |
| 7 | `domain/ai/policy/DefaultAiCapabilityRouter.kt:68-95,254-259,334-345` | HIGH | Routing Bug | When `preferredMode == CLOUD`, on-device-only capabilities (`NOTIFICATION_PARSE`, `REVIEW_PRIORITIZATION`, `SEMANTIC_DEDUPE`) never fall back to on-device because `chooseCloudPreferred()` only allows on-device fallback for `isLowRiskOnDeviceFallback()`, which excludes them. The router therefore degrades to deterministic fallback even when a local model is available. | In cloud-preferred mode, route cloud-unsupported capabilities to on-device whenever it is available, or introduce an explicit `isOnDeviceOnlyCapability()` fast path before deterministic fallback. |
| 8 | `domain/ai/service/AiArtifactRepository.kt:3,15,18,24` | MEDIUM | Architecture | The domain repository contract exposes `AiArtifactEntity` from the data layer. This reverses the intended dependency direction and couples domain callers to Room/entity concerns. | Define a domain artifact model (or a minimal contract DTO) and keep entity mapping inside the data repository implementation. |
| 9 | `domain/ai/model/NotificationParsingModels.kt:33-40`; `domain/ai/model/SemanticDuplicateModels.kt:36-42`; `domain/ai/model/WarrantyExtractionModels.kt:17-25` | MEDIUM | Missing Domain Invariants | These models document bounded/validated fields (`confidence` in `0..1`, positive warranty months/return days, positive transaction amount), but the constructors accept any values. Malformed provider output can therefore flow downstream into ranking, duplicate handling, or warranty-date math without being rejected. | Add `init` validation / clamping, or introduce validated factory functions so provider output is normalized before entering the domain layer. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `AiSettings` defaults ↔ `AiSettingsRepositoryImpl` ↔ ViewModel initial state | HIGH | Default Drift | `AiSettings()` defaults differ from repository hydration for receipt features, and both still conflict with the architecture's opt-in baseline. Any screen using `initialValue = AiSettings()` can show/rout AI behavior that later flips when persisted settings load. | Define one authoritative default-settings source and reuse it everywhere (constructor, repository hydration, tests, ViewModel initial state). |
| 2 | `AiPolicyImpl` ↔ `CloudReceiptAssistService` ↔ AI architecture privacy rules | HIGH | Privacy Gap | Policy allows cloud receipt extraction broadly, while the provider can still attach the raw receipt image when `receiptImageCloudEnabled` is true. That contradicts the documented first-release rule of text-only receipt extraction and weakens the meaning of `redactBeforeCloud`. | Move image-upload authorization into explicit capability-specific policy, and hard-block image uploads unless there is a separate, user-visible opt-in that clearly overrides text-only mode. |
| 3 | Artifact writers (`GenerateDashboardBriefingUseCase`, `ExplainPendingReviewUseCase`, `SuggestReceiptExtractionUseCase`, `SuggestCategoryFallbackUseCase`, `JudgePendingReviewDuplicateUseCase`, `CategorizeReceiptItemsUseCase`) ↔ `AiArtifactPresentation` | MEDIUM | Lost Provenance | Writers collapse fallback/disabled routes to `AiMode.AUTO`, and presentation hides `AUTO`. Live runtime status can explain why routing fell back, but the persisted artifact path drops that same information. | Store resolved route metadata on artifacts and render it consistently in both runtime and persisted paths. |
| 4 | `ReceiptRepository` / `ReviewQueueRepository` placeholder merchants ↔ `ReviewPriorityModels` scoring | MEDIUM | Inconsistent Sentinels | Different producers use different placeholder values (`"Unknown"`, `"Unknown Merchant"`), but priority scoring only recognizes one of them. Review-ordering behavior therefore depends on which pipeline produced the review item. | Replace string sentinels with a normalized missing-merchant representation, or funnel all placeholders through a shared normalizer before scoring. |
| 5 | `AiPolicyImpl` / `DefaultAiCapabilityRouter` ↔ `WarrantyTrackerRepository` | MEDIUM | Capability Coupling | `WARRANTY_EXTRACTION` is gated through `receiptAssistEnabled`, even though the warranty pipeline runs as a separate post-scan feature. Users cannot control warranty extraction independently, and turning off receipt assist silently disables warranty extraction too. | Add a dedicated warranty-extraction setting/policy branch, or explicitly document and test the intentional coupling if it is product-approved. |

## Summary
- Total issues: 9
- Critical: 0, High: 3, Medium: 6, Low: 0
- Files with issues: 10/24

## Key Patterns
- Privacy posture has drifted from the architecture plan: defaults are no longer strictly opt-in, policy is not capability-sensitive enough, and receipt-image cloud behavior is not enforced centrally.
- The domain layer leaks data-layer entities in multiple contracts, which increases coupling and makes these APIs harder to evolve safely.
- Route/provenance information is modeled inconsistently: runtime routing is rich, but persisted artifact metadata collapses important fallback states.
- Several AI result models rely on comments instead of enforced invariants, so malformed provider output can enter downstream logic unchecked.
