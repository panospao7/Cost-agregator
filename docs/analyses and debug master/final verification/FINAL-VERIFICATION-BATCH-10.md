# Final Verification — Batch 10: AI Services - Hybrid + Workers

> **[RESOLVED BY A.3]** The non-deterministic default values issue (System.currentTimeMillis) has been fixed across the codebase.

## Scope
- `com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/NoOpDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/NoOpReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt`
- `com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
- `com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt`
- `com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
- `com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/model/AiModels.kt`
- `com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
- `com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt`
- `com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt`
- `com/yourname/expensetracker/domain/ai/service/ReceiptAssistService.kt`
- `com/yourname/expensetracker/domain/ai/service/ReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/di/AiModule.kt`

Not present in current tree:
- `com/yourname/expensetracker/domain/ai/service/HybridServiceDelegationModels.kt`
- `com/yourname/expensetracker/data/ai/worker/AiWorkerModels.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt:24-29,78-80,93-95,108-109,121-122,178-180`; `com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt:102-146,327-345` | High | Logic | The “smart” receipt retry chain is route-family locked: once routing picks CLOUD or ON_DEVICE, the later attempts never cross to the other provider family. In AUTO mode, receipt extraction is on-device-first, so the documented cloud-first 5-step chain is not what actually runs. | B | CONFIRMED | Treat the router result as the first preferred attempt, then fall through across allowed providers in a single ordered retry plan. |
| 2 | `com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt:24-45` | Low | Concurrency | `lastUsedImageInput` is mutable singleton state, so concurrent requests can overwrite each other’s execution metadata. This class is currently not the active `ReceiptAssistService` binding, so impact is lower than reported. | D | DOWNGRADED | Remove shared mutable state and return execution metadata in the result object itself. **[RESOLVED BY A.8]** |
| 3 | `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt:93-97,121-126`; `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt:53-58` | Medium | Capability mismatch | Attempts 2 and 4 are labeled as on-device “vision”/image-aware fallbacks, but the on-device provider only sends a `TextPart(prompt)` and never consumes image bytes or image paths. | R | CONFIRMED | Either wire real image input into the on-device model or rename/collapse these steps so diagnostics match reality. |
| 4 | `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt:12-23` | High | Privacy | `sanitizeText()` truncates before redaction, so PII cut at the boundary can survive as an unredacted fragment in cloud-bound text. | R | DOWNGRADED | Redact on the full raw string first, then normalize and truncate the sanitized output. |
| 5 | `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt:9,15-20` | Low | Data quality | `PHONE_REGEX` is broad enough to redact non-phone numeric text such as long amounts or IDs, which can unnecessarily strip useful receipt context before AI parsing. | D | DOWNGRADED | Tighten phone matching or only apply it to clearly phone-shaped regions. |
| 6 | `com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt:12-18,42-49` | Medium | Robustness | `extractFirstJsonObject()` returns the first brace-balanced block, not the first valid JSON object, so a malformed `{...}` prefix can mask a valid JSON object later in the model output. | R | CONFIRMED | Validate each candidate before returning, or continue scanning when parsing the first candidate fails. |
| 7 | `com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt:13-16,38-42` | Medium | Retry policy | Retry handling ignores server-provided backoff hints (`Retry-After`/equivalents), so `429`/`408` responses are retried on a local 250-1700 ms schedule even when the server asked for longer. | R | DOWNGRADED | Add header-aware delay calculation and prefer the server’s retry delay when present. |
| 8 | `com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt:5-6` | Low | Observability | Correlation IDs keep only 8 characters of a UUID, leaving 32 bits of entropy and making log collisions much easier than necessary. | R | DOWNGRADED | Use the full UUID, or at least a materially longer stable token per top-level request. |
| 9 | `com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt:44-53`; `com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:30,57`; `com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt:31,40` | High | Time coordination | The worker derives `dateKey` from `System.currentTimeMillis()`, while briefing generation/artifact timestamps come from `TimeProvider`. Around midnight or under injected clocks, generation and delivery can target different day keys and silently skip the notification. | R | CONFIRMED | Derive the day key once from the same shared time source and propagate it through generation and delivery. |
| 10 | `com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt:56-60` | Medium | Cancellation | `catch (e: Exception)` also catches `CancellationException`, so worker cancellation is swallowed and reported as success. | D | CONFIRMED | Re-throw `CancellationException` before the generic exception handler returns `Result.success()`. **[RESOLVED BY A.7]** |
| 11 | `com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt:20-24`; `com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt:56-60` | Medium | Scheduling / reliability | The daily briefing work has no network/Wi-Fi constraints, and transient failures are converted to success. In cloud-only or Wi-Fi-only setups, a run at the wrong connectivity state can miss the whole day’s briefing. | B | CONFIRMED | Add connectivity constraints that match AI settings, or explicitly retry on transient generation preconditions. |
| 12 | `com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt:40-41,83,118-124`; `com/yourname/expensetracker/di/AiModule.kt:148-152,194-203`; `com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt:22-29` | High | DI / logic | `CategorizeReceiptItemsUseCase` injects two unqualified `ReceiptItemCategorizationService` dependencies, but Hilt binds that interface only to `HybridReceiptItemCategorizationService`. Both “cloud” and “on-device” calls therefore route back through the same hybrid wrapper, re-reading settings and re-routing a second time. | R | CONFIRMED | Inject the concrete cloud/on-device services (or qualified interface bindings) so routing happens exactly once. |
| 13 | `com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt:54-57,78-90,101`; `com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt:24-30` | Medium | Metadata drift | The review-explanation use case computes artifact mode/provider metadata from one `AiRouteDecision`, then `HybridReviewExplanationService` fetches settings again and re-routes before execution. A connectivity/settings change can make stored diagnostics disagree with the provider that actually ran. | R | DOWNGRADED | Pass the precomputed route decision through to execution, or call the selected concrete provider directly from the use case. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:57-58,84-87,98`; `com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt:24-30` | Medium | Metadata drift | The dashboard briefing pipeline has the same double-routing flaw as review explanations: artifact metadata is based on one route decision, but the hybrid service re-reads settings and can execute a different route. | Route once per request and pass that decision into the concrete provider call instead of routing again inside the hybrid service. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `Debugger #3` | `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt:79-122` | The attempt list records actual execution attempts; missing “skipped” entries are a diagnostics preference, not a correctness bug. |
| 2 | `Debugger #4` | `com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt:38-42` | Current production callers only pass attempts `1..3`; the overflow scenario is speculative and not exercised in this codebase. |
| 3 | `Debugger #5` | `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt:8` | The cited reproduction is incorrect: `\b` does match between `:` and the first digit, so `Total:4111111111111111` is still matched. |
| 4 | `Debugger #7` | `com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt:181-215` | Production calls go through `ReviewExplanationInputBuilder`, which already redacts/pseudonymizes merchant, package, and notification fields before the cloud service sees them. |
| 5 | `Debugger #9 / Cross-1` | `com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt:46-48` | The “never emits” hang is speculative; no concrete non-emitting upstream was identified in the reviewed dashboard flow pipeline. |
| 6 | `Reviewer #5 / Debugger #10` | `com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt:26-29` | Returning `null` for unavailable routing states matches the current nullable service contract. The real defect here is the separate DI double-routing problem, not this branch shape by itself. |
| 7 | `Debugger #11` | `com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt:57-62` | The fenced-JSON helpers can recurse through nested fenced blocks, but no infinite loop was demonstrated and model output sizes keep depth bounded in practice. |
| 8 | `Debugger #12` | `com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt:76-89` | The reported large-integer repro does not match normal `org.json` integer parsing for current call sites, so this is not a concrete defect here. |
| 9 | `Debugger #14` | `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt:31-35` | Hashing the fallback unknown-merchant token is awkward but does not create a meaningful privacy or logic bug in this pipeline. |
| 10 | `Debugger Cross-2` | `com/yourname/expensetracker/di/AiModule.kt:124-128` | DI is not ambiguous here: `ReceiptAssistService` is bound only to `SmartReceiptAssistService`, so `HybridReceiptAssistService` is not the active interface implementation. |
| 11 | `Debugger Cross-3` | `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt:78-141`; `com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt:7-44` | Layered retries increase latency, but they are bounded and intentional; this is a trade-off, not a correctness defect. |
| 12 | `Debugger Cross-4` | `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt:5-35` | The duplication is real maintainability debt, but the report’s claim of already-divergent sanitization behavior is not supported by the reviewed code. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Receipt assist retry pipeline | High | Logic / capability drift | The smart receipt path does not actually perform the advertised cross-provider fallback chain, and its on-device “vision” steps are text-only in practice. | `SmartReceiptAssistService.kt`, `OnDeviceReceiptAssistService.kt`, `DefaultAiCapabilityRouter.kt` | Build one ordered fallback plan across providers and align attempt labels with what the provider can really consume. |
| 2 | Receipt item categorization execution pipeline | High | DI / routing | The use case tries to choose a provider-specific path, but unqualified interface injection sends both branches back through the hybrid wrapper and re-routes again. | `CategorizeReceiptItemsUseCase.kt`, `AiModule.kt`, `HybridReceiptItemCategorizationService.kt` | Use qualifiers or concrete provider injection so routing happens once and metadata matches execution. |
| 3 | Review explanation execution pipeline | Medium | Metadata drift | Artifact diagnostics are captured before execution, but the hybrid service re-routes at call time, so stored provider/mode can drift from the actual executor. | `ExplainPendingReviewUseCase.kt`, `HybridReviewExplanationService.kt` | Compute the route once and pass it through to the selected provider. |
| 4 | Dashboard briefing execution pipeline | Medium | Metadata drift | The dashboard briefing path has the same double-routing pattern as review explanations, so artifact/provider metadata can be wrong when runtime conditions change mid-call. | `GenerateDashboardBriefingUseCase.kt`, `HybridDashboardBriefingService.kt` | Reuse the original route decision instead of routing again inside the hybrid service. |
| 5 | Proactive briefing scheduling pipeline | Medium | Availability | Work is scheduled without connectivity constraints, while transient failures are treated as success, so cloud-only/Wi-Fi-only runs can be lost until the next daily window. | `AiWorkSchedulerImpl.kt`, `DailyBriefingWorker.kt`, `DefaultAiCapabilityRouter.kt` | Match WorkManager constraints to cloud requirements or retry transient precondition failures. |
| 6 | Proactive briefing delivery pipeline | High | Time coordination | The worker and delivery lookup do not share a single day key / clock source, so midnight boundaries or injected clocks can suppress same-day delivery even when generation succeeded. | `DailyBriefingWorker.kt`, `DashboardBriefingInputBuilder.kt`, `GenerateDashboardBriefingUseCase.kt`, `DeliverProactiveBriefingNotificationUseCase.kt` | Derive one target day key from the shared time abstraction and reuse it end-to-end. |

## Summary
- Total verified issues: 13
- Confirmed: 13 (Critical: 0, High: 4, Medium: 6, Low: 3)
- False positives: 12
- Missed issues found: 1
- Files affected: 18/37

## Key Patterns
- Several AI pipelines compute routing twice: once for artifact/diagnostic metadata, then again inside a hybrid wrapper. That makes metadata drift a recurring design flaw.
- The receipt-assist stack documents richer image/provider fallbacks than the implementation actually supports.
- Privacy hardening is fragile: sanitization order is wrong in one core utility, while some regexes over-redact useful numeric context.
- Worker orchestration assumes “log and succeed” for transient problems, which makes scheduling constraints and shared clock usage much more important than the current code reflects.
