# Final Verification — Batch 06: AI Models, Policies, Router

## Scope
- `domain/ai/model/AiModels.kt`
- `domain/ai/model/AiRuntimeStatusModels.kt`
- `domain/ai/model/AiArtifactPresentation.kt`
- `domain/ai/model/OnDeviceRuntimePresentation.kt`
- `domain/ai/model/WarrantyExtractionModels.kt`
- `domain/ai/model/FinancialQueryModels.kt`
- `domain/ai/model/CaptureAssistModels.kt`
- `domain/ai/model/NotificationParsingModels.kt`
- `domain/ai/model/ReceiptItemCategorizationModels.kt`
- `domain/ai/model/SemanticDuplicateModels.kt`
- `domain/ai/model/ReviewPriorityModels.kt`
- `domain/ai/model/AiLoadState.kt`
- `domain/ai/policy/AiPolicy.kt`
- `domain/ai/policy/AiPolicyImpl.kt`
- `domain/ai/policy/DefaultAiCapabilityRouter.kt`
- `domain/ai/service/AiSettingsRepository.kt`
- `domain/ai/service/AiCapabilityRouter.kt`
- `domain/ai/service/AiEnvironmentMonitor.kt`
- `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt`
- `domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
- `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- `domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
- `domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
- `domain/ai/usecase/GenerateTransactionInsightUseCase.kt`
- `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- `domain/debug/AiRuntimeDiagnostics.kt`
- `data/repository/AiSettingsRepositoryImpl.kt`
- `data/repository/WarrantyTrackerRepository.kt`
- `data/database/entity/AiArtifactEntity.kt`
- `data/ai/provider/DefaultAiEnvironmentMonitor.kt`
- `data/ai/provider/CloudReceiptAssistService.kt`
- `data/ai/provider/SmartReceiptAssistService.kt`
- `data/ai/provider/HybridReceiptAssistService.kt`
- `data/ai/provider/CloudWarrantyExtractionService.kt`
- `ui/screens/aisettings/AiSettingsScreen.kt`
- `ui/screens/aisettings/AiSettingsViewModel.kt`
- `ui/screens/debug/DebugScreen.kt`
- `ui/screens/debug/DebugViewModel.kt`
- `ui/screens/assistant/AssistantViewModel.kt`
- `ui/screens/home/HomeViewModel.kt` (via report cross-reference / diagnostics usage)
- `ui/screens/review/ReviewViewModel.kt` (via report cross-reference / diagnostics usage)
- `ui/screens/receiptscan/ReceiptScanViewModel.kt` (via report cross-reference / diagnostics usage)

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `domain/ai/model/AiModels.kt:86` | High | Privacy / Defaults | `AiSettings` still defaults `aiEnabled = true`, while the architecture plan freezes AI defaults as conservative opt-in (`false`). Multiple ViewModels also use `AiSettings()` as initial state, so startup can briefly expose AI as enabled before DataStore hydration. | R | CONFIRMED | Restore opt-in defaults and centralize them in one canonical default-settings source reused by constructor, repository hydration, and UI initial state. |
| 2 | `domain/ai/model/AiModels.kt:93-94`<br>`data/repository/AiSettingsRepositoryImpl.kt:97-98` | High | Default Drift | `AiSettings()` enables `receiptAssistEnabled` and `receiptImageCloudEnabled`, but empty-DataStore hydration disables both. Default/test state therefore diverges from production behavior on a fresh install. | B | CONFIRMED | Define defaults once and reuse them from both the model and repository mapper. |
| 3 | `domain/ai/policy/DefaultAiCapabilityRouter.kt:33` | Low | UX / Messaging | Disabled-route reasons interpolate raw enum names (`$capability`), so user-facing runtime text can show internal identifiers such as `REVIEW_EXPLANATION`. | R | CONFIRMED | Use the existing `displayName()` mapping for disabled messages too. |
| 4 | `domain/ai/model/AiArtifactPresentation.kt:12-16`<br>`data/database/entity/AiArtifactEntity.kt:50`<br>`domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:95-100`<br>`domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt:78-83`<br>`domain/ai/usecase/SuggestCategoryFallbackUseCase.kt:163-168`<br>`domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:140-145`<br>`domain/ai/usecase/GenerateTransactionInsightUseCase.kt:168-173`<br>`domain/ai/usecase/ExplainPendingReviewUseCase.kt:83-88`<br>`domain/ai/usecase/CategorizeReceiptItemsUseCase.kt:101-105` | Medium | Diagnostics / Persistence | Persisted artifacts collapse `DETERMINISTIC_FALLBACK` and `DISABLED` into `AiMode.AUTO`, and presentation returns `null` for `AUTO`. Result: cached fallback/disabled artifacts lose route/provider/model diagnostics in UI. This is narrower than the debugger report: ON_DEVICE/CLOUD artifacts do keep diagnostics. | B | DOWNGRADED | Persist resolved `AiRoute` (preferred), or add explicit persisted route metadata for fallback/disabled executions and render it in presentation. |
| 5 | `domain/ai/model/AiRuntimeStatusModels.kt:3-13`<br>`domain/ai/usecase/GetAiRuntimeStatusUseCase.kt:27-35,58-63` | Medium | Runtime Modeling | Runtime status requires a non-null `OnDeviceModelStatus` for every capability, and the use case always derives actions from that status. Capabilities without a real on-device path can therefore show misleading local-model state/action text instead of “not applicable” or route-first guidance. | R | DOWNGRADED | Make on-device status nullable or add `NOT_APPLICABLE`, and derive action/message text from resolved route/context before falling back to local-model state. |
| 6 | `data/repository/AiSettingsRepositoryImpl.kt:56-57` | Medium | Resilience | `settings()` maps `dataStore.data` directly without `catch { IOException -> emptyPreferences() }`, so a corrupted AI prefs file can break all AI settings/routing consumers instead of recovering to defaults. | R | CONFIRMED | Add standard DataStore `IOException` recovery while preserving non-IO failures as fatal. |
| 7 | `domain/ai/policy/DefaultAiCapabilityRouter.kt:68-94,254-259` | Medium | Routing Logic | In `preferredMode = CLOUD`, on-device-only capabilities (`NOTIFICATION_PARSE`, `REVIEW_PRIORITIZATION`, `SEMANTIC_DEDUPE`) cannot use cloud and are excluded from low-risk on-device fallback, so they degrade to deterministic fallback even when local execution is available. | D | CONFIRMED | In cloud-preferred mode, route on-device-only capabilities to on-device when available, or include them in the fallback allowlist. |
| 8 | `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt:44-46` | Low | Semantics / Prioritization | `highestPriorityMessage` is just the first non-null message in caller order, not a severity-ranked message. The field name and downstream guidance text therefore overstate what is actually being computed. | D | CONFIRMED | Rank messages by severity before selecting one, or rename the field to reflect first-match semantics. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `data/ai/provider/CloudReceiptAssistService.kt:74-76,304-323` | High | Privacy / Policy Bypass | Cloud receipt assist uploads the raw receipt image whenever image mode is enabled and `receiptImageCloudEnabled` is true, even when `redactBeforeCloud` is true. This also violates the architecture plan’s first-release rule to keep receipt extraction text-only / keep receipt images local. | Move image-upload authorization into policy/router, force OCR-only requests whenever redaction is enabled, and require a separate explicit image-upload opt-in if image analysis is ever supported. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #3 | `domain/ai/policy/DefaultAiCapabilityRouter.kt:311-345` | The codebase has no actual on-device implementation for `LOCATION_SUMMARY` or `WARRANTY_EXTRACTION`; preventing ON_DEVICE routing is therefore correct. The misleading part is the stale model-name metadata, not the missing allowlist entries. |
| 2 | Debugger #5 / Cross-Component #5 | `domain/ai/policy/AiPolicyImpl.kt:52-53` | Current callers only use `shouldRedact()` for cloud-capable flows. No verified on-device-only path consults this method, so the reported behavior does not create an actual bug in the current codebase. |
| 3 | Debugger #7 | `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt:27-28` | `DefaultAiEnvironmentMonitor` caches one device-wide ML Kit status for 1.5s and ignores capability, so after the first lookup the remaining per-capability calls are cheap in-memory reads rather than a meaningful I/O waterfall. |
| 4 | Debugger #8 | `domain/ai/policy/DefaultAiCapabilityRouter.kt:289-307` | The `"unsupported"` provider/model names are unreachable in current behavior because policy hard-blocks cloud routing for those capabilities. The issue is hypothetical unless policy changes independently. |
| 5 | Debugger #9 | `domain/ai/model/AiModels.kt:120-124,148` | This is speculative hardening rather than a demonstrated defect in the analyzed call paths. No verified batch-06 flow feeds untrusted `NaN`/`Infinity`/out-of-range values into these models in a way that currently breaks behavior. |
| 6 | Debugger #10 | `domain/debug/AiRuntimeDiagnostics.kt:38-51` | `synchronized` is acceptable here: the methods are short, non-suspending JVM critical sections with no demonstrated coroutine deadlock path. |
| 7 | Debugger Cross-Component #4 | `domain/ai/policy/DefaultAiCapabilityRouter.kt:228-235,156-163` | The duplicated policy check is redundant but behaviorally correct; it is a cleanup opportunity, not a defect. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | `AiSettings.receiptImageCloudEnabled` → `AiPolicyImpl` / router policy decisions → receipt assist cloud providers | High | Privacy / Policy Drift | Privacy guarantees are enforced inconsistently across the pipeline. The plan says receipt extraction is text-only in the first release and receipt images stay local, but the live service path still uploads full images when image mode is enabled. | `domain/ai/model/AiModels.kt`, `domain/ai/policy/AiPolicyImpl.kt`, `data/ai/provider/CloudReceiptAssistService.kt`, `data/ai/provider/SmartReceiptAssistService.kt`, `data/ai/provider/HybridReceiptAssistService.kt` | Make image upload an explicit capability-level policy decision, and enforce it centrally before provider request construction. |

## Summary
- Total verified issues: 8
- Confirmed: 8 (Critical: 0, High: 2, Medium: 4, Low: 2)
- False positives: 7
- Missed issues found: 1
- Files affected: 18/43

## Key Patterns
- AI defaults are not centralized: constructor defaults, DataStore hydration, and architecture-plan defaults have drifted apart.
- The persistence layer stores preference-shaped metadata (`AiMode`) instead of resolved execution metadata (`AiRoute`), which hides fallback behavior after caching.
- Runtime status mixes capability routing with device-wide on-device availability, producing misleading status/action text for non-applicable capabilities.
- Privacy rules are documented in policy/architecture, but not enforced consistently at the provider boundary.
