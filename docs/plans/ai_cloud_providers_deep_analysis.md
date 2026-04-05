# AI / Cloud Provider Batch — Deep Analysis

**Scope**: `data/ai/provider/*` (10 files), `domain/ai/usecase/*` (3 input builders), `domain/ai/policy/*` (2 files), `domain/ai/service/AiCapabilityRouter.kt`  
**Date**: April 5, 2026  
**Batch ID**: AI-CLOUD-01

---

## Files Reviewed

| # | File | Lines |
|---|------|-------|
| 1 | [SmartReceiptAssistService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt) | 268 |
| 2 | [CloudReceiptAssistService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt) | 409 |
| 3 | [CloudQueryInterpretationService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt) | 156 |
| 4 | [CloudDashboardBriefingService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt) | 244 |
| 5 | [CloudReviewExplanationService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt) | 316 |
| 6 | [CloudDedupeJudgeService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt) | 342 |
| 7 | [CloudReceiptItemCategorizationService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt) | 559 |
| 8 | [CloudCategorizationAssistService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt) | 219 |
| 9 | [CloudWarrantyExtractionService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt) | 393 |
| 10 | [HybridReceiptItemCategorizationService.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt) | 33 |
| 11 | [FinancialQueryInterpretationInputBuilder.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt) | 111 |
| 12 | [DedupeJudgeInputBuilder.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt) | 160 |
| 13 | [ReceiptItemCategorizationInputBuilder.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt) | 108 |
| 14 | [AiPolicy.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicy.kt) | 28 |
| 15 | [DefaultAiCapabilityRouter.kt](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt) | 342 |

---

## Pre-Identified Issues — Verdict

### ISSUE-1 ✅ CONFIRMED — Router bypass in SmartReceiptAssistService

- **Severity**: CRITICAL
- **File:Line**: [SmartReceiptAssistService.kt:170-172](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt#L170-L172)
- **Type**: Architecture / Regression
- **Description**: `isOnDeviceRouteEligible()` returns `true` for **both** `AiRoute.ON_DEVICE` **and** `AiRoute.DETERMINISTIC_FALLBACK`. When the router explicitly chose `DETERMINISTIC_FALLBACK` (meaning both cloud and on-device were evaluated and rejected), `SmartReceiptAssistService` still attempts on-device AI via `shouldAttemptOnDeviceVision()` and `shouldAttemptOnDeviceText()`. This bypasses the router's centralised control and can invoke on-device inference even when the router already determined it isn't viable (e.g., model not installed).
- **Suggested Fix**:
```kotlin
private fun isOnDeviceRouteEligible(route: AiRoute): Boolean {
    return route == AiRoute.ON_DEVICE
}
```
Also consider: when route == `DETERMINISTIC_FALLBACK` or `DISABLED`, skip directly to `noOpReceiptAssistService.suggest(input)`.

---

### ISSUE-2 ✅ CONFIRMED — No retry in CloudReceiptAssistService

- **Severity**: HIGH (MAJOR)
- **File:Line**: [CloudReceiptAssistService.kt:81-123](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt#L81-L123)
- **Type**: Bug / Resilience
- **Description**: `CloudReceiptAssistService.suggest()` executes a single `client.newCall(request).execute()` — no retry loop whatsoever. Every other cloud provider has a `for (attempt in 1..MAX_RETRY_ATTEMPTS)` pattern with backoff+jitter. Transient 429/408/5xx responses or `SocketTimeoutException`/connection-reset IOExceptions cause immediate failure.
- **Suggested Fix**: Add the same retry pattern used in `CloudDashboardBriefingService` (3 attempts, exponential backoff + jitter, `isRetryableHttpStatus` checking 429/408/5xx, timeout + connection-reset retry).

---

### ISSUE-3 ✅ CONFIRMED (PARTIALLY FIXED) — Inconsistent retry semantics

- **Severity**: HIGH
- **File:Line**: Multiple — see table below
- **Type**: Bug / Resilience
- **Description**: The retry HTTP status predicate `isRetryableHttpStatus()` is inconsistent:

| Service | Retries 5xx | Retries 429 | Retries 408 | Has retry loop |
|---------|:-----------:|:-----------:|:-----------:|:--------------:|
| CloudDashboardBriefingService | ✅ | ✅ | ✅ | ✅ (3 attempts) |
| CloudReviewExplanationService | ✅ | ❌ | ❌ | ✅ (3 attempts) |
| CloudDedupeJudgeService | ✅ | ❌ | ❌ | ✅ (3 attempts) |
| CloudReceiptItemCategorizationService | ✅ | ❌ | ❌ | ✅ (3 attempts) |
| CloudWarrantyExtractionService | ✅ | ❌ | ❌ | ✅ (3 attempts) |
| CloudQueryInterpretationService | ✅ | ❌ | ❌ | ✅ (2 attempts) |
| CloudCategorizationAssistService | ❌ | ❌ | ❌ | ❌ (no retry) |
| CloudReceiptAssistService | ❌ | ❌ | ❌ | ❌ (no retry) |

> Only `CloudDashboardBriefingService` has the complete retry policy. All others miss 429 (rate-limit) and 408 (timeout).

- **Suggested Fix**: Extract a shared `isRetryableHttpStatus` function into a companion/util:
```kotlin
fun isRetryableHttpStatus(code: Int): Boolean =
    code in 500..599 || code == 429 || code == 408
```
Apply this + the full retry loop pattern to **all** cloud services.

---

### ISSUE-4 ✅ CONFIRMED (PARTIALLY FIXED) — Blocking `.execute()` without `Dispatchers.IO`

- **Severity**: HIGH
- **File:Line**: See table below
- **Type**: Performance / ANR Risk
- **Description**: OkHttp's `call.execute()` is a blocking I/O call. In `suspend` functions called from arbitrary coroutine contexts, this can block the main thread and cause ANR if the caller didn't switch dispatchers.

| Service | Uses `withContext(Dispatchers.IO)`? |
|---------|:-----------------------------------:|
| CloudReceiptItemCategorizationService | ✅ (wraps in `Dispatchers.IO`) |
| CloudWarrantyExtractionService | ✅ (wraps in `Dispatchers.IO`) |
| CloudReceiptAssistService | ❌ |
| CloudQueryInterpretationService | ❌ |
| CloudDashboardBriefingService | ❌ |
| CloudReviewExplanationService | ❌ |
| CloudDedupeJudgeService | ❌ |
| CloudCategorizationAssistService | ❌ |

> 6 out of 8 cloud services execute blocking network calls on the **caller's dispatcher**.

- **Suggested Fix**: Wrap the entire network call block in `withContext(Dispatchers.IO) { ... }` in every cloud service. Alternatively, add `@IoDispatcher` to constructor and use it consistently. Two services already do this correctly — follow the same pattern.

---

### ISSUE-5 ✅ CONFIRMED — No boundary redaction in CloudReceiptAssistService

- **Severity**: HIGH
- **File:Line**: [CloudReceiptAssistService.kt:218-227](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt#L218-L227)
- **Type**: Security / Privacy
- **Description**: `CloudReceiptAssistService.buildPrompt()` always embeds `input.parsedMerchant`, `input.parsedTotal`, `input.parsedDate`, `input.parsedTaxAmount`, `input.lineItemsJson`, and `input.rawOcrText` directly into the prompt without any redaction. Compare with `CloudReceiptItemCategorizationService` and `CloudWarrantyExtractionService` which both sanitize text when `shouldRedact` is true.

The `SmartReceiptAssistService` caller constructs a `ReceiptAssistInput` but never applies redaction transforms. If the user has "Cloud AI with privacy" mode, raw PII (merchant names, amounts, OCR text potentially containing names/addresses) is sent to the Gemini API in cleartext.

- **Suggested Fix**:
  1. Add a `redactBeforeCloud: Boolean` field to `ReceiptAssistInput` (like `ReceiptItemCategorizationInput` already has).
  2. Add a `ReceiptAssistInputBuilder` (parallel to `ReceiptItemCategorizationInputBuilder`) that sanitises fields when `shouldRedact` is true.
  3. Apply `sanitizeCloudText()` to `parsedMerchant`, `rawOcrText`, and `lineItemsJson` in the prompt builder.

---

### ISSUE-6 ✅ CONFIRMED — Pseudonym/alias reverse-mapping gap in query interpretation

- **Severity**: HIGH
- **File:Line**: [FinancialQueryInterpretationInputBuilder.kt:32-50](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt#L32-L50), [CloudQueryInterpretationService.kt:145](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt#L145)
- **Type**: Bug / Privacy
- **Description**: When `shouldRedact` is true, `FinancialQueryInterpretationInputBuilder` replaces category names with `category_<sha256>` and merchant names with `merchant_<sha256>`. These are **one-way hashes** (SHA-256 prefix). After `CloudQueryInterpretationService` parses the model response (line 145), the returned `FinancialQueryInterpretationResult` can contain these hashed aliases as the parsed merchant/category filter values. There is **no reverse-mapping** to recover the original names, so downstream use cases (e.g. `ExecuteFinancialQueryUseCase`) may try to filter by `"merchant_a1b2c3d4e5f6"` instead of the actual merchant name, causing filters to silently return nothing.

Compare: `CloudReceiptItemCategorizationService` has proper reverse-mapping via `mapCloudCategoryNameToRaw()` and `cloudCategoryOptionsForPrompt()`. The query interpretation path is missing this.

- **Suggested Fix**:
  1. Change `sha256Prefix()` to a **reversible** pseudonym scheme in `FinancialQueryInterpretationInputBuilder` — carry a `Map<String, String>` (alias → original) alongside the input.
  2. After cloud response parsing, map any returned alias back to the original name using this map.
  3. Alternatively, use the `CloudCategoryOption` pattern (ID-based) used in receipt item categorisation — pass category IDs through and resolve back.

---

### ISSUE-7 ✅ CONFIRMED — CloudWarrantyExtractionService bypasses router

- **Severity**: HIGH
- **File:Line**: [CloudWarrantyExtractionService.kt:34-55](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt#L34-L55)
- **Type**: Architecture
- **Description**: `CloudWarrantyExtractionService` is directly injectable and has no routing through `AiCapabilityRouter`. Every other AI capability goes through a Hybrid service that consults the router. `CloudWarrantyExtractionService`:
  - Does NOT check `AiRoute` — only checks if the API key is blank.
  - Does NOT respect `AiMode` settings (ON_DEVICE, CLOUD, AUTO).
  - Does NOT respect wifi-only constraints.
  - Does NOT respect capability enable/disable toggle (no `WARRANT_EXTRACTION` in `AiCapability` enum).
  - Has no `WarrantyExtractionService` interface — it's a concrete class used directly.
  - In DI, it's provided via `AiModule.provideCloudWarrantyExtractionService()` without any Hybrid wrapper.
- **Suggested Fix**: This needs an architectural refactor:
  1. Add `AiCapability.WARRANTY_EXTRACTION` to the enum.
  2. Create `WarrantyExtractionService` interface in `domain/ai/service/`.
  3. Create `HybridWarrantyExtractionService` with router decision logic.
  4. In DI, bind the interface to the hybrid implementation.
  5. Keep `CloudWarrantyExtractionService` as a leaf-only provider.

---

## Newly Discovered Issues

### ISSUE-8 [NEW] — `CloudCategorizationAssistService` has NO retry logic at all

- **Severity**: HIGH
- **File:Line**: [CloudCategorizationAssistService.kt:50-72](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt#L50-L72)
- **Type**: Bug / Resilience
- **Description**: Like `CloudReceiptAssistService`, this service has **zero** retry logic. A single `client.newCall(request).execute()` with no retry loop, no retryable status check, no backoff. Any transient failure (429, 5xx, timeout, connection-reset) immediately returns `null`, silently losing the categorization opportunity.
- **Suggested Fix**: Add the standard retry pattern with `isRetryableHttpStatus(code) = code in 500..599 || code == 429 || code == 408` and exponential backoff + jitter (3 attempts).

---

### ISSUE-9 [NEW] — Naive JSON extraction in `CloudCategorizationAssistService`

- **Severity**: MEDIUM
- **File:Line**: [CloudCategorizationAssistService.kt:197-201](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt#L197-L201)
- **Type**: Bug
- **Description**: `extractFirstJsonObject()` uses a simplistic `text.indexOf('{')` / `text.lastIndexOf('}')` approach. This breaks if the text contains **multiple JSON objects** or if there's trailing text after the first JSON object. Every other cloud service uses the correct depth-tracking brace parser with string-escape awareness. This service can return garbage JSON (e.g., two concatenated JSON objects) on certain model outputs.
- **Suggested Fix**: Replace with the proper depth-tracking `extractFirstJsonObject()` implementation used in all other services (with brace counting, string tracking, and fenced-JSON support).

---

### ISSUE-10 [NEW] — `CloudCategorizationAssistService` has no PII redaction

- **Severity**: HIGH
- **File:Line**: [CloudCategorizationAssistService.kt:139-151](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt#L139-L151)
- **Type**: Security / Privacy
- **Description**: `buildPrompt()` embeds `input.merchant`, `input.amount`, `input.transactionType`, `input.supportingText`, `input.notificationTitle`, `input.notificationText` directly without any redaction. The `CategorizationAssistInput` model has no `redactBeforeCloud` field and no sanitization is applied. If the user's privacy mode requires redaction, raw notification text (which can contain bank account details, card numbers, names) is sent to the cloud.

Compare: `DedupeJudgeInputBuilder` sanitises all labels and previews. `ReceiptItemCategorizationInputBuilder` has full redaction support.

- **Suggested Fix**: Add `redactBeforeCloud` to `CategorizationAssistInput`, build sanitised fields in a `CategorizationAssistInputBuilder` analogous to other builders, apply `sanitizeCloudText()` to merchant, supportingText, notificationTitle, and notificationText.

---

### ISSUE-11 [NEW] — `CloudReviewExplanationService` sends raw notification text to cloud

- **Severity**: HIGH
- **File:Line**: [CloudReviewExplanationService.kt:199-208](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt#L199-L208)
- **Type**: Security / Privacy
- **Description**: Similar to ISSUE-10. The prompt includes `input.merchant`, `input.amount`, `input.packageName`, `input.notificationTitle`, `input.notificationText`, and `input.explanation` — all as raw text. There is no redaction check. `ReviewExplanationInput` has no redaction field. Notification text frequently contains bank names, card last-4-digits, and personal names.
- **Suggested Fix**: Apply the same pattern — add `CategorizationAssistInputBuilder`-style sanitisation via a `ReviewExplanationInputBuilder` that applies `sanitizeLabel()` / `sanitizeFreeText()` when `shouldRedact` is true. The `ReviewExplanationInputBuilder.kt` already exists but should apply redaction to merchant, notificationTitle, and notificationText fields.

---

### ISSUE-12 [NEW] — `CloudQueryInterpretationService` retry uses `delay()` inside `execute().use {}` response handler

- **Severity**: MEDIUM
- **File:Line**: [CloudQueryInterpretationService.kt:69-73](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt#L69-L73)
- **Type**: Bug / Performance
- **Description**: When a 5xx response is received, the code calls `delay(delayMs)` **inside** the `response.use {}` lambda. This means the HTTP response body is kept alive (held open) during the backoff delay. While OkHttp may have already buffered the body, the `use {}` block holds a reference to the `Response` object, preventing its release. This is a resource management concern. Additionally, `return@use null` after the delay means the `repeat` loop continues, but the request object is reused — OkHttp should handle this, but it's fragile.

Compare: `CloudDashboardBriefingService` correctly delays **outside** the `use {}` block (after `outcome` is checked at line 173-175).

- **Suggested Fix**: Restructure to match the pattern in `CloudDashboardBriefingService`: return `null` from `use {}` to signal retry, check for `null` outside the block, then delay.

---

### ISSUE-13 [NEW] — CloudQueryInterpretationService only retries 2 times

- **Severity**: LOW  
- **File:Line**: [CloudQueryInterpretationService.kt:61](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt#L61)
- **Type**: Inconsistency
- **Description**: Uses `maxRetries = 2` while every other provider uses `MAX_RETRY_ATTEMPTS = 3`. This inconsistency means query interpretation has fewer retry opportunities under identical transient failure conditions.
- **Suggested Fix**: Standardise to `MAX_RETRY_ATTEMPTS = 3` to match the other providers.

---

### ISSUE-14 [NEW] — Massive code duplication across cloud services

- **Severity**: MEDIUM
- **File:Line**: All 8 cloud service files
- **Type**: Architecture / Maintainability
- **Description**: The following code is copy-pasted across 6-8 cloud services with minor variations:
  - `extractFirstJsonObject()` — identical depth-tracking JSON parser (50 lines × 7 files = 350 duplicated lines)
  - `extractFencedJsonObject()` + `JSON_FENCE_REGEX` — identical fenced JSON extraction
  - `optFiniteDoubleStrictOrNull()` / `optStrictLongStrictOrNull()` — strict JSON number parsers (duplicated in 3 files)
  - `isRetryableHttpStatus()` — should be shared (with the correct definition)
  - `backoffDelayMs()` — identical exponential backoff calculation
  - `newCorrelationId()` — identical UUID generation
  - `sha256Prefix()` — identical digest utility (duplicated in 4 files, with slightly different signatures)
  - PII regex constants (`EMAIL_REGEX`, `IBAN_REGEX`, `CARD_REGEX`, `PHONE_REGEX`, `LONG_NUMBER_REGEX`) — duplicated in 5 files

This makes bug fixes require updating 7+ files and increases the chance of inconsistencies (as demonstrated by ISSUE-3, ISSUE-9).

- **Suggested Fix**: Extract shared utilities into:
  - `CloudAiResponseParser` — JSON extraction, strict number parsing
  - `CloudAiRetryPolicy` — retry logic, backoff, retryable status codes
  - `CloudAiSanitizer` — PII regex patterns, sanitization helpers
  - `CloudAiCorrelation` — correlation ID generation

---

### ISSUE-15 [NEW] — `CloudWarrantyExtractionService` returns data-layer entity directly

- **Severity**: MEDIUM
- **File:Line**: [CloudWarrantyExtractionService.kt:3-4](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt#L3-L4), [CloudWarrantyExtractionService.kt:254-266](file:///c:/Users/panos/Desktop/cost%20agregator/ExpenseTracker/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt#L254-L266)
- **Type**: Architecture
- **Description**: `CloudWarrantyExtractionService` directly imports and returns `data.database.entity.Warranty` and `data.database.entity.ScannedReceipt` as its API types. Cloud AI services should work with domain models, not database entities. This:
  - Couples the service to Room schema.
  - Prevents the service from being used without database knowledge.
  - Violates the multi-implementation pattern (Cloud/OnDevice/Hybrid should share a common domain-level interface).

Compare: Every other cloud service returns domain models like `ReceiptAssistSuggestion`, `DashboardBriefing`, `DedupeJudgeSuggestion`, etc.

- **Suggested Fix**: Create domain models `WarrantyExtractionInput` and `WarrantyExtractionResult` and convert at the boundary. Map to/from `Warranty` entity only in the repository or use case layer.

---

## Summary Matrix

| # | Severity | Type | File (basename) | Status |
|---|----------|------|-----------------|--------|
| 1 | **CRITICAL** | Architecture/Regression | SmartReceiptAssistService | ✅ Pre-identified, confirmed |
| 2 | **HIGH** | Bug/Resilience | CloudReceiptAssistService | ✅ Pre-identified, confirmed |
| 3 | **HIGH** | Bug/Resilience | Multiple (6 files) | ✅ Pre-identified, confirmed |
| 4 | **HIGH** | Performance | Multiple (6 files) | ✅ Pre-identified, confirmed |
| 5 | **HIGH** | Security/Privacy | CloudReceiptAssistService | ✅ Pre-identified, confirmed |
| 6 | **HIGH** | Bug/Privacy | FinancialQueryInterpretationInputBuilder + CloudQueryInterp. | ✅ Pre-identified, confirmed |
| 7 | **HIGH** | Architecture | CloudWarrantyExtractionService | ✅ Pre-identified, confirmed |
| 8 | **HIGH** | Bug/Resilience | CloudCategorizationAssistService | 🆕 New |
| 9 | **MEDIUM** | Bug | CloudCategorizationAssistService | 🆕 New |
| 10 | **HIGH** | Security/Privacy | CloudCategorizationAssistService | 🆕 New |
| 11 | **HIGH** | Security/Privacy | CloudReviewExplanationService | 🆕 New |
| 12 | **MEDIUM** | Bug/Performance | CloudQueryInterpretationService | 🆕 New |
| 13 | **LOW** | Inconsistency | CloudQueryInterpretationService | 🆕 New |
| 14 | **MEDIUM** | Architecture/Maintainability | All 8 cloud services | 🆕 New |
| 15 | **MEDIUM** | Architecture | CloudWarrantyExtractionService | 🆕 New |

---

## What's Working Well

- ✅ **SecureKeyStorage**: All services correctly use `SecureKeyStorage` for API key retrieval — the CRITICAL-1 BuildConfig exposure fix is consistently applied.
- ✅ **Correlation IDs**: Most services generate and log correlation IDs for traceability.
- ✅ **`CloudDashboardBriefingService`** has the gold-standard retry implementation (3 attempts, 429/408/5xx, exponential backoff + jitter, proper SSLException handling, correct `delay()` placement outside `use {}`).
- ✅ **Structured error typing**: `AiServiceResult.Failure` with typed `AiServiceError` variants (HttpError, Timeout, SslError, Offline, ParseError, Disabled, Unknown) is consistently used.
- ✅ **`ReceiptItemCategorizationInputBuilder` + `CloudReceiptItemCategorizationService`** have the best redaction implementation with reversible category mapping.
- ✅ **`DedupeJudgeInputBuilder`** has thorough redaction with `sanitizeLabel()` and `sanitizePreview()`.
- ✅ **`response.use {}`** is consistently used across all services — no response body leaks.

---

## Batch Score: **55 / 100**

### Score Breakdown

| Category | Weight | Score | Notes |
|----------|--------|-------|-------|
| **Routing correctness** | 15% | 6/15 | CRITICAL router bypass (ISSUE-1), warranty unrouted (ISSUE-7) |
| **Retry resilience** | 15% | 4/15 | 2 services have zero retry; only 1 of 6 has correct retry policy |
| **Privacy / Redaction** | 20% | 8/20 | 3 services (Receipt, Categ., Review) send raw PII; query interp. has irreversible hashing |
| **Performance / Threading** | 10% | 3/10 | 6/8 services block without IO dispatcher |
| **Error handling** | 10% | 8/10 | Generally solid typed errors; minor issues with delay-in-use |
| **Security** | 10% | 9/10 | SecureKeyStorage is correctly applied everywhere |
| **Architecture** | 10% | 5/10 | Massive code duplication; entity leakage in warranty service |
| **Code quality** | 10% | 7/10 | Inconsistent retry constants, naive JSON parser in one service |

---

## Recommended Fix Priority

> [!IMPORTANT]
> **Priority 1 (Critical — fix before next release)**
> - ISSUE-1: Fix `isOnDeviceRouteEligible()` to exclude `DETERMINISTIC_FALLBACK`
> - ISSUE-5, 10, 11: Add boundary redaction to CloudReceiptAssist, CloudCategorizationAssist, CloudReviewExplanation
> - ISSUE-2, 8: Add retry loops to CloudReceiptAssist and CloudCategorizationAssist

> [!WARNING]
> **Priority 2 (High — fix in next sprint)**
> - ISSUE-3: Standardise `isRetryableHttpStatus` across all services (add 429/408)
> - ISSUE-4: Wrap all blocking `.execute()` calls in `withContext(Dispatchers.IO)`
> - ISSUE-6: Add reversible alias map to `FinancialQueryInterpretationInputBuilder`
> - ISSUE-7: Create routed `WarrantyExtractionService` abstraction with Hybrid wrapper

> [!NOTE]
> **Priority 3 (Medium — technical debt)**
> - ISSUE-9: Fix naive JSON extraction in CloudCategorizationAssistService
> - ISSUE-12: Move `delay()` outside `use {}` in CloudQueryInterpretationService
> - ISSUE-14: Extract shared utilities (parser, retry, sanitiser, correlation)
> - ISSUE-15: Replace entity return types with domain models in warranty extraction
> - ISSUE-13: Standardise retry count to 3

---

## Coverage Assessment

- **Requirements met**: **No** — routing, retry uniformity, redaction boundary guarantees, and dispatcher safety are not consistently satisfied across the reviewed subsystem.
- **Testing adequate**: **No** — targeted unit tests may exist for individual services, but systematic coverage is missing for:
  - Cross-provider retry policy consistency
  - Dispatcher/threading safety (verifying `Dispatchers.IO` usage)
  - Boundary redaction enforcement under various `AiSettings` / `AiPolicy` configurations
  - Alias reverse-mapping in query interpretation
  - Router bypass regression (`DETERMINISTIC_FALLBACK` allowing on-device)
