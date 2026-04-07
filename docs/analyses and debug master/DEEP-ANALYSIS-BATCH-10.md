# Deep Analysis — Batch 10: AI Services - Hybrid + Workers



## Batch Scope
- domain/ai/service/HybridReviewExplanationService.kt
- domain/ai/service/HybridReceiptItemCategorizationService.kt
- domain/ai/service/HybridServiceDelegationModels.kt
- domain/ai/service/SmartReceiptAssistService.kt
- domain/ai/provider/internal/CloudJsonParser.kt
- domain/ai/provider/internal/CloudPiiSanitizer.kt
- domain/ai/provider/internal/CloudCorrelation.kt
- domain/ai/provider/internal/CloudRetryPolicy.kt
- data/ai/worker/DailyBriefingWorker.kt
- data/ai/worker/AiWorkerModels.kt

Deep Analysis — Batch 10: AI Services - Hybrid + Workers
@reviewer Findings
Verdict: FAIL
Issues Found
#	File	Severity	Type	Description	Suggested Fix
1	app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt	MAJOR	Logic	The “hybrid retry chain” is not actually hybrid. Once AiCapabilityRouter picks a route, all later attempts are hard-gated to that same provider family (CLOUD only tries cloud, ON_DEVICE only tries on-device). That means cloud failures never fall back to on-device, and in AUTO mode receipt extraction is typically on-device-first, so the documented cloud-first/image-first strategy is never executed.	Treat router output as the preferred first attempt, not an exclusive gate. Build an ordered list of allowed attempts from policy/settings and fall through across providers when earlier attempts fail or are low-confidence.
2	app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt	CRITICAL	Security/Privacy	sanitizeText() truncates with take(maxChars) before running redaction. If sensitive data is cut at the boundary, regexes stop matching and partial PII can be sent upstream (e.g. partial card/email/phone fragments).	Redact on the full raw string first, then normalize/truncate the sanitized result. Apply the same fix to duplicated sanitization logic elsewhere.
3	app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt	MAJOR	Robustness	extractFirstJsonObject() returns the first brace-balanced substring, not the first valid JSON object. A response like prefix {not-json} {"ok":1} will return {not-json} and cause parsing failure even though valid JSON exists later.	Validate each candidate before returning (or continue scanning after parse failure). Add a regression test for non-JSON brace blocks before valid JSON.
4	app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt	MAJOR	Edge Case	The worker derives dateKey from System.currentTimeMillis(), while briefing generation derives its key later through TimeProvider/DashboardBriefingInputBuilder. Around midnight or under injected test clocks, generation and notification lookup can target different dates, silently skipping delivery.	Derive the day key once from a shared time source and pass it through both generation and notification delivery, or return the generated target key from the use case.
Cross-Component Issues
#	Components	Severity	Description	Suggested Fix
1	SmartReceiptAssistService + OnDeviceReceiptAssistService	MAJOR	Attempts 2 and 4 are presented as distinct “on-device vision” and “on-device text” paths, but the on-device provider only sends TextPart(prompt) and never consumes image bytes/path. So the service advertises image-aware fallback that does not actually exist, and diagnostics are misleading.	Either pass real image input into the on-device model (if supported), or collapse/rename the step so it is clearly OCR-text-only.
2	DailyBriefingWorker + AiWorkSchedulerImpl	MAJOR	The periodic work is scheduled with no network/Wi-Fi constraints, while the worker always returns Result.success(). In cloud-only / Wi-Fi-only configurations, a run at the wrong connectivity state can miss the whole day’s briefing and will not be retried when connectivity returns.	Add constraints that match cloud settings (CONNECTED / UNMETERED as needed), or explicitly Result.retry() for transient pre-generation failures.
Summary
- Total issues: 6
- Files with issues: 4/10
- Note: HybridServiceDelegationModels.kt and AiWorkerModels.kt were not present in the current tree; review covered the 8 located batch files plus related runtime/model dependencies.

---

## Addendum — Missing File Coverage

Reviewed missing-file set:
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt`

Still not present in the current tree:
- `domain/ai/service/HybridServiceDelegationModels.kt`
- `data/ai/worker/AiWorkerModels.kt`

Additional Issues Found
#	File	Severity	Type	Description	Suggested Fix
5	app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt	MAJOR	Logic	`categorizeItems()` drops both `AiRoute.DETERMINISTIC_FALLBACK` and `AiRoute.DISABLED` to `null` (lines 25-29). That turns a valid router outcome into an indistinguishable service failure for any caller that uses the bound `ReceiptItemCategorizationService` directly, and it is inconsistent with the other hybrid services in this batch that delegate fallback/disabled routes explicitly.	Handle `DETERMINISTIC_FALLBACK` explicitly via a deterministic/no-op implementation (or return a sealed disabled/fallback result instead of `null`) so callers do not have to reverse-engineer routing state.
6	app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt	MAJOR	Robustness / Retry	`CloudRetryPolicy` only exposes status classification and fixed exponential+jitter backoff (lines 13-16, 38-42). Because it has no `Retry-After`/server-backoff support, all cloud callers retry `429`/`408` after only 250-1700 ms, which can exhaust the whole retry budget while the upstream is still throttling.	Extend the policy with header-aware delay calculation (`Retry-After` seconds/date, optionally vendor-specific retry-ms headers) and have callers prefer the server-provided delay over the local default.
7	app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt	MINOR	Observability	`newCorrelationId()` truncates `UUID.randomUUID()` to 8 characters (line 6), leaving only 32 bits of entropy. Under repeated cloud retries / concurrent requests this makes collisions plausible enough to blur log attribution across separate requests.	Use the full UUID (or at least a longer 16-32 hex character token) and keep one correlation ID per top-level request.

Additional Cross-Component Issues
#	Components	Severity	Description	Suggested Fix
3	HybridReceiptItemCategorizationService + CategorizeReceiptItemsUseCase + AiModule	MAJOR	The receipt-item path is wired so the use case cannot actually force provider-specific execution. `CategorizeReceiptItemsUseCase` injects two unqualified `ReceiptItemCategorizationService` dependencies (lines 40-41) and switches on its own router result (lines 83, 118-124), but `AiModule` binds that interface only to `HybridReceiptItemCategorizationService` (lines 148-152). The concrete cloud/on-device providers are only exposed as concrete types (lines 194-203). In practice, both `onDeviceService` and `cloudService` resolve to the same hybrid wrapper, which then re-reads settings and re-runs routing again (HybridReceiptItemCategorizationService lines 22-29). That can make stored artifact mode/provider metadata diverge from the provider that actually ran.	Inject concrete provider classes (or qualified on-device/cloud interface bindings) into the use case, and compute routing exactly once per request.
4	HybridReviewExplanationService + ExplainPendingReviewUseCase	MAJOR	The review-explanation path also double-routes. `ExplainPendingReviewUseCase` builds diagnostics and artifact metadata from one `AiRouteDecision` (lines 54-57, 83-90), then calls `ReviewExplanationService`, which is bound to `HybridReviewExplanationService`; that service fetches settings again and calls `router.decide(...)` a second time (HybridReviewExplanationService lines 24-30). A connectivity/settings change between those calls can store “Route: CLOUD / provider X” while generation actually ran on-device or no-op.	Pass the precomputed `AiRouteDecision` through to execution, or bypass the hybrid wrapper from the use case and call the selected concrete provider directly.

Coverage Update
- Additional issues in this addendum: 5
- Updated total issues: 11
- Updated files with issues in batch scope: 8/10
- Testing gap: `HybridReceiptItemCategorizationServiceTest` only covers the ON_DEVICE happy path; there is still no test for fallback/disabled behavior, no qualifier/DI wiring test for the two receipt-item provider injections, no test for review-explanation route consistency, and no retry-policy test covering `429` + `Retry-After` handling.
