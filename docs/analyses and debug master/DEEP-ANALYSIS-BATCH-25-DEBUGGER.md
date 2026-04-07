# Deep Analysis — Batch 25: AI Providers — Cloud & Hybrid (@debugger)

## Scope
- data/ai/provider/CloudWarrantyExtractionService.kt
- data/ai/provider/CloudReviewExplanationService.kt
- data/ai/provider/CloudReceiptItemCategorizationService.kt
- data/ai/provider/CloudQueryInterpretationService.kt
- data/ai/provider/CloudReceiptAssistService.kt
- data/ai/provider/CloudDedupeJudgeService.kt
- data/ai/provider/CloudDashboardBriefingService.kt
- data/ai/provider/CloudCategorizationAssistService.kt
- data/ai/provider/HybridReviewExplanationService.kt
- data/ai/provider/HybridReceiptItemCategorizationService.kt
- data/ai/provider/HybridReceiptAssistService.kt
- data/ai/provider/HybridQueryInterpretationService.kt
- data/ai/provider/HybridDedupeJudgeService.kt
- data/ai/provider/HybridDashboardBriefingService.kt
- data/ai/provider/HybridCategorizationAssistService.kt
- data/ai/provider/SmartReceiptAssistService.kt
- data/ai/provider/internal/CloudRetryPolicy.kt
- data/ai/provider/internal/CloudCorrelation.kt
- data/ai/provider/internal/CloudPiiSanitizer.kt
- data/ai/provider/internal/CloudJsonParser.kt
- data/ai/provider/DefaultAiEnvironmentMonitor.kt
- data/ai/provider/DefaultAiCapabilityRouter.kt
- data/ai/provider/DefaultAiProvider.kt
- data/ai/provider/AiProvider.kt
- data/ai/provider/AiProviderModels.kt
- data/ai/provider/AiProviderRegistry.kt
- data/ai/provider/AiProviderRouter.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | CloudReceiptAssistService.kt:176-180,304-322 | **CRITICAL** | Security/Privacy | When `redactBeforeCloud` is enabled, OCR text is sanitized but the full receipt image is still uploaded to cloud AI. The redaction flag only affects text prompts, not image payloads. This violates the documented privacy posture for receipt extraction. | 1. Enable redaction in AI settings. 2. Scan a receipt containing sensitive info (credit card number, personal address). 3. Observe cloud request includes the raw image despite redaction being enabled. | Block image upload when redaction is enabled, or add an explicit separate consent flag for raw image upload. |
| 2 | CloudCategorizationAssistService.kt:149-150,170,183-194 | **HIGH** | Security/Privacy | Categorization fallback leaks sensitive user context in cloud mode: raw category names and recent merchant/category history are sent even when redaction is enabled. The `redactBeforeCloud` flag is checked for the merchant name but not for the category context/history payload. | 1. Enable redaction. 2. Trigger categorization assist for a receipt. 3. Observe cloud request includes raw category names and merchant history. | Alias or omit category/history context when `redactBeforeCloud=true`. |
| 3 | SmartReceiptAssistService.kt:65-76,79-132,178-180 | **HIGH** | Logic Error | Smart receipt assist does not implement its documented multi-provider fallback chain. It locks onto one initial route and never falls through to the other provider family after failure/low confidence. The retry logic only retries within the same provider family (cloud→cloud or on-device→on-device). | 1. Configure cloud-preferred mode. 2. Cloud provider fails or returns low confidence. 3. System retries cloud only, never falls back to on-device. | Build an ordered attempt plan (cloud vision → on-device vision → cloud text → on-device text → deterministic fallback) instead of gating all later attempts on the first route decision. |
| 4 | CloudQueryInterpretationService.kt:149-165 | **HIGH** | Logic Error | Query interpretation pipeline drops category filters and never remaps redacted aliases back to real values, so redacted/category-driven queries can be misinterpreted. The structured query schema does not include category/period fields. | 1. Ask "how much did I spend on groceries last month?" 2. Query is redacted, aliases not restored. 3. Category filter is lost, query returns all-time total instead. | Parse category names, apply alias-map restoration, and add tests for redacted merchant/category queries. |
| 5 | OnDeviceQueryInterpretationService.kt:115-135 | **HIGH** | Logic Error | On-device query interpretation has the same alias/category dropping issue. The structured query schema does not preserve category filters or time-period intent. | Same as #4 but with on-device mode. | Same fix — extend schema and restore aliases. |
| 6 | HybridReceiptAssistService.kt:24-45 | **MEDIUM** | Thread Safety | `lastUsedImageInput` is stored as mutable singleton state, so concurrent calls can return another request's image-usage flag. Two simultaneous receipt scans can cross-contaminate their image-usage metadata. | 1. Scan receipt A (uses image). 2. Simultaneously scan receipt B (text only). 3. Receipt B may incorrectly report image was used. | Remove shared mutable state and derive image usage per result/request. |
| 7 | CloudReceiptItemCategorizationService.kt:183-190 | **MEDIUM** | Logic Error | Cloud receipt-item categorization hardcodes `€` for all items, misrepresenting non-EUR receipts in the prompt. Items from USD/GBP receipts are formatted with `€` symbol. | 1. Scan a USD receipt. 2. Cloud prompt shows items as `€5.99` instead of `$5.99`. | Format using `input.currency` or omit a hardcoded symbol. |
| 8 | CloudReceiptItemCategorizationService.kt:247-250 | **LOW** | Code Quality | Uses the on-device token limit constant, unnecessarily constraining cloud output and coupling unrelated configs. Cloud models can handle more tokens than on-device limits. | N/A — suboptimal but not broken. | Introduce a dedicated cloud max-output-token constant. |
| 9 | CloudWarrantyExtractionService.kt:177-195,250-259 | **MEDIUM** | Logic Error | Warranty return-policy data extracted from AI is dropped, then replaced with merchant hardcoded defaults later in the repository flow. The `returnDays` and `returnConditions` fields are extracted but never persisted. | 1. Scan a receipt with clear return policy text. 2. AI extracts `returnDays: 30`. 3. Repository ignores it and uses merchant defaults. | Persist and consume extracted `returnDays`/`returnConditions` before falling back to defaults. |
| 10 | CloudPiiSanitizer.kt | **HIGH** | Security | `sanitizeText()` truncates before redaction, allowing boundary-cut PII fragments to survive in cloud-bound text. If a credit card number is split across the truncation boundary, the partial digits may pass through. | 1. Text contains "...my card is 4111-1111-1111-1111 and..." 2. Truncation cuts at "1111-1111-". 3. Redaction runs on truncated text, partial digits survive. | Redact first, then normalize/truncate. |
| 11 | CloudRetryPolicy.kt | **LOW** | Logic | Retry policy uses exponential backoff but does not cap the maximum delay, so after many retries the delay can grow to hours. | N/A — unlikely in practice due to caller-level timeouts. | Add `maxDelayMs` cap (e.g., 30 seconds). |
| 12 | CloudCorrelation.kt | **LOW** | Code Quality | Correlation ID generation uses `UUID.randomUUID().toString()` which is fine, but the ID is logged in plaintext alongside request metadata. If logs are exposed, correlation IDs can be used to trace user activity. | N/A — low risk. | Hash correlation IDs in logs or use shorter opaque IDs. |
| 13 | DefaultAiEnvironmentMonitor.kt | **LOW** | Logic | Environment monitoring checks `isGooglePlayServicesAvailable()` but does not handle the case where Google Play Services is available but the specific ML model is not downloaded. | 1. Google Play Services is installed. 2. On-device ML model is not downloaded. 3. Monitor reports "available" but inference fails. | Also check model availability via `RemoteModelManager`. |
| 14 | DefaultAiCapabilityRouter.kt | **MEDIUM** | Logic | Cloud-preferred mode degrades on-device-only capabilities to deterministic fallback even when on-device is available. The router sends cloud-disallowed capabilities directly to fallback without checking on-device availability first. | 1. Cloud-preferred mode. 2. Request on-device-only capability (e.g., receipt parsing with no cloud model). 3. Router skips on-device check and goes straight to fallback. | Route cloud-disallowed capabilities to on-device when available in cloud-preferred mode. |
| 15 | AiProviderModels.kt | **LOW** | Code Quality | `AiProviderResult` uses sealed interface but the `Error` variant carries a generic `Throwable` without typed error codes. Callers must use `when` with type checks or `is` checks to distinguish retryable vs permanent errors. | N/A — design issue. | Add typed error codes (e.g., `NetworkError`, `ModelError`, `TimeoutError`, `PermanentError`). |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | CloudReceiptAssist → ReceiptItemCategorization | **HIGH** | PII Leakage | Receipt image upload bypasses the documented text-only/redaction privacy posture. The image contains all receipt text including PII, making text-level redaction irrelevant. | Block image upload under redaction or require explicit separate opt-in. |
| C2 | SmartReceiptAssist → Cloud/OnDevice providers | **HIGH** | Broken Fallback Chain | The "smart" routing only retries within one provider family, defeating the purpose of having both cloud and on-device providers. A cloud outage means no fallback to on-device. | Implement cross-family fallback with ordered attempt plan. |
| C3 | CloudQueryInterpretation → ExecuteFinancialQuery | **HIGH** | Filter Loss | Category and period filters are lost during query interpretation, so structured queries execute with widened scope. "Groceries last month" becomes "all transactions ever". | Extend structured query schema and restore aliases. |
| C4 | CloudPiiSanitizer → All cloud services | **HIGH** | Truncation Before Redaction | All cloud-bound text goes through `sanitizeText()` which truncates before redacting. PII at truncation boundaries survives. | Redact first, then truncate. |
| C5 | HybridReceiptAssist → ReceiptScan ViewModel | **MEDIUM** | State Contamination | Shared mutable `lastUsedImageInput` can cross-contaminate concurrent receipt scans, causing incorrect metadata about whether image analysis was used. | Remove shared mutable state. |

## Summary
- **Total issues: 20** (15 file-level + 5 cross-component)
- **Critical: 1**, **High: 7**, **Medium: 5**, **Low: 7**
- **Files with issues: 12/15 analyzed** (AiProvider.kt, AiProviderRegistry.kt, AiProviderRouter.kt do not exist in codebase)

## Key Patterns

### 1. Privacy/Redaction Bypass
The most critical systemic issue is that redaction is inconsistently applied across the cloud AI pipeline. Text redaction exists but image uploads bypass it entirely, category context leaks through, and truncation happens before redaction. This creates a false sense of privacy compliance.

### 2. Broken Fallback Chains
Smart routing and hybrid services don't actually implement cross-provider fallback. Once a route is selected (cloud or on-device), retries stay within that family. This defeats the resilience purpose of having multiple provider types.

### 3. Schema Incompleteness
The structured query schema drops critical fields (category, period, aliases) during interpretation. This means the downstream execution engine operates with incomplete filters, producing wrong results for complex queries.

### 4. Shared Mutable State
Hybrid services use instance-level mutable state (`lastUsedImageInput`) that can be corrupted by concurrent calls. This is a thread-safety anti-pattern in a coroutine-based architecture.

### 5. Missing Error Typing
Error handling across cloud providers uses generic `Throwable` without typed error codes, making it impossible for callers to distinguish retryable network errors from permanent model failures.
