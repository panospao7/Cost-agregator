# Batch 16: AI & Cloud Services fixes (15 issues)

## Technical Plan (Advanced)
### Scope
- In:
  - ISSUE-1: Fix router bypass in `SmartReceiptAssistService` for `DETERMINISTIC_FALLBACK` path leakage into on-device attempts.
  - ISSUE-2: Add bounded retry loop for transient failures in `CloudReceiptAssistService`.
  - ISSUE-3: Normalize transient HTTP retry semantics across cloud AI providers.
  - ISSUE-4: Move blocking OkHttp `.execute()` calls onto `Dispatchers.IO` in all affected AI cloud providers.
  - ISSUE-5: Enforce redaction at the cloud boundary in receipt assist prompt construction.
  - ISSUE-6: Close pseudonym restoration gap in financial query interpretation flow.
  - ISSUE-7: Route cloud warranty extraction via `AiCapabilityRouter` instead of direct cloud-policy checks.
  - ISSUE-8: Add retry loop to `CloudCategorizationAssistService` (zero retries today).
  - ISSUE-9: Replace naive JSON extraction in `CloudCategorizationAssistService` with depth-aware parser.
  - ISSUE-10: Enforce merchant redaction in `CategorizationAssistInputBuilder`/`CloudCategorizationAssistService` when policy requires it.
  - ISSUE-11: Enforce merchant/packageName redaction in `ReviewExplanationInputBuilder`/`CloudReviewExplanationService` when policy requires it.
  - ISSUE-12: Fix `delay()` placement inside `response.use {}` in `CloudQueryInterpretationService`.
  - ISSUE-13: Standardize retry count in `CloudQueryInterpretationService` from 2 to 3.
  - ISSUE-14: Extract shared utilities (JSON parser, retry policy, PII sanitizer, correlation) from duplicated cloud service code.
  - ISSUE-15: Replace Room entity types with domain models in `CloudWarrantyExtractionService` API surface.
- Out:
  - No model prompt redesign beyond privacy/routing correctness.
  - No API/provider migration (still Gemini endpoint).
  - No broad AI feature expansion unrelated to the 15 defects.

### Complexity Assessment
- Estimated files touched: **28-36**
  - Production: ~18-22 files
  - Tests: ~10-14 files
- Risk level: **high**
- Cross-module impact: **yes** (domain policy/router, data providers, shared utility layer, models, repository flows, query interpretation models/tests)

---

### Batch Plan

#### Phase A — Foundation (shared utilities)

1. Batch name: **ISSUE-14 — Extract shared cloud service utilities**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt` [NEW]
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt` [NEW]
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt` [NEW]
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt` [NEW]
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicyTest.kt` [NEW]
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParserTest.kt` [NEW]
   - objective:
     - Extract ~350+ lines of duplicated code from 8 cloud services into tested shared utilities, creating the foundation for consistent behavior across all providers.
   - Root Cause Analysis:
     - **Why it happens:** Each cloud service was developed independently, copy-pasting JSON parsing, retry logic, backoff, PII regex, and correlation ID generation.
     - **Duplicated code locations (representative):**
       - `extractFirstJsonObject()` — duplicated in 7 files (~50 lines each)
       - `extractFencedJsonObject()` + `JSON_FENCE_REGEX` — duplicated in 6 files
       - `isRetryableHttpStatus()` — duplicated in 6 files (with different semantics!)
       - `backoffDelayMs()` — duplicated in 5 files
       - `sha256Prefix()` — duplicated in 5 files (with different signatures)
       - PII regex constants (`EMAIL_REGEX`, `IBAN_REGEX`, `CARD_REGEX`, `PHONE_REGEX`, `LONG_NUMBER_REGEX`) — duplicated in 6 files
       - `newCorrelationId()` — duplicated in 7 files
       - `optFiniteDoubleStrictOrNull()` / `optStrictLongStrictOrNull()` — duplicated in 3 files
     - **Impact:** Bug fixes require updating 7+ files; inconsistencies already caused ISSUE-3 and ISSUE-9.
   - Implementation Strategy:
     1. Create `CloudRetryPolicy` object with:
        - `isRetryableHttpStatus(code: Int): Boolean` = `code in 500..599 || code == 429 || code == 408`
        - `isRetryableIoException(e: IOException): Boolean` (timeout + connection reset)
        - `backoffDelayMs(attempt: Int): Long` with exponential + jitter
        - Constants: `MAX_RETRY_ATTEMPTS = 3`, `BASE_RETRY_BACKOFF_MS = 250L`, `MAX_RETRY_BACKOFF_MS = 1_500L`, `RETRY_JITTER_MS = 200L`
     2. Create `CloudJsonParser` object with:
        - `extractFirstJsonObject(text: String): String?` (depth-tracking, string-escape-aware)
        - `extractFencedJsonObject(text: String): String?`
        - `JSONObject.optFiniteDoubleStrictOrNull(key: String): Double?`
        - `JSONObject.optStrictLongStrictOrNull(key: String): Long?`
     3. Create `CloudPiiSanitizer` object with:
        - All PII regex constants (shared singleton `Regex` instances)
        - `sanitizeText(raw: String, maxChars: Int, fallbackPrefix: String): String`
        - `sanitizeMerchant(raw: String?, shouldRedact: Boolean): String`
        - `String.sha256Prefix(length: Int = 12): String`
     4. Create `CloudCorrelation` object with:
        - `newCorrelationId(): String`
     5. **Do NOT migrate providers yet** — only create the shared utilities and test them.
     - snippet (pseudocode):
       ```kotlin
       // CloudRetryPolicy.kt
       object CloudRetryPolicy {
           const val MAX_RETRY_ATTEMPTS = 3
           fun isRetryable(code: Int) = code in 500..599 || code == 429 || code == 408
           fun backoffMs(attempt: Int): Long { ... }
       }
       ```
   - Dependencies:
     - Independent. Must be completed first — ISSUE-2, 3, 8, 9, 12, 13 will consume these utilities.
   - Risk Assessment:
     - Low risk: additive-only, no existing code changes.
   - Verification Plan:
     - Unit tests for `CloudRetryPolicy`: verify 429/408/5xx return true; 4xx (non-408) return false; backoff values are within expected range.
     - Unit tests for `CloudJsonParser`: test depth-tracking parser with nested objects, escaped strings, fenced JSON, multi-object text.
   - Estimated Effort: **Medium**
   - completion criteria:
     - Shared utilities exist with comprehensive tests; no provider migration yet.

---

#### Phase B — Critical routing fix

2. Batch name: **ISSUE-1 — SmartReceiptAssistService router bypass for DETERMINISTIC_FALLBACK**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistServiceTest.kt`
   - objective:
     - Make routing authoritative so on-device attempts are not run when router selected deterministic fallback.
   - Root Cause Analysis:
     - **Why it happens:** `isOnDeviceRouteEligible(route)` currently returns true for both `ON_DEVICE` and `DETERMINISTIC_FALLBACK`.
     - **Exact location:** `SmartReceiptAssistService.kt:170-172`.
     - **Impact:** Deterministic fallback route still executes on-device AI attempts, violating route contract and producing policy-inconsistent behavior.
   - Implementation Strategy:
     1. Restrict on-device eligibility to `AiRoute.ON_DEVICE` only.
     2. Keep cloud eligibility unchanged (`AiRoute.CLOUD`).
     3. Ensure deterministic route falls directly to no-op deterministic provider.
     4. Update tests that currently assert on-device is allowed under deterministic route.
     - snippet (pseudocode):
       ```kotlin
       // planner pseudocode
       onDeviceEligible = (route == ON_DEVICE)
       ```
   - Dependencies:
     - Independent from other issues.
   - Risk Assessment:
     - Existing tests/behavior relying on implicit deterministic->on-device fallback will fail.
     - Mitigation: explicitly validate route matrix and adjust tests to match intended policy contract.
   - Verification Plan:
     - Unit tests:
       - Route `ON_DEVICE` => on-device called, cloud not called.
       - Route `CLOUD` => cloud called, on-device not called.
       - Route `DETERMINISTIC_FALLBACK` => no-op called, both AI providers not called.
       - Route `DISABLED` => no-op called only.
     - Manual: run receipt assist with route forced to deterministic and verify logs show only fallback attempt.
   - Estimated Effort: **Low**
   - completion criteria:
     - No execution path allows on-device attempts when route is deterministic fallback.

---

#### Phase C — Resilience standardization (retry + IO dispatcher)

3. Batch name: **ISSUE-2 — Add retry loop for transient failures in CloudReceiptAssistService**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistServiceTest.kt`
   - objective:
     - Add bounded retries for transient HTTP/network failures and preserve existing terminal error mapping.
   - Root Cause Analysis:
     - **Why it happens:** `suggest()` performs a single request without attempt loop.
     - **Exact location:** `CloudReceiptAssistService.kt:81-123`.
     - **Impact:** transient timeout/5xx/rate-limit failures fail immediately, reducing reliability for receipt extraction.
   - Implementation Strategy:
     1. Use `CloudRetryPolicy` from ISSUE-14 for constants and classifiers.
     2. Wrap call in `for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS)`.
     3. Retry on transient statuses and retryable I/O classes; return final mapped `AiServiceError` after attempts exhausted.
     4. Preserve current parsing behavior and success payload behavior.
     - snippet (pseudocode):
       ```kotlin
       // planner pseudocode
       for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS):
         execute call
         if CloudRetryPolicy.isRetryable(code) -> delay(backoffMs(attempt))
         else -> return terminal result
       ```
   - Dependencies:
     - Depends on ISSUE-14 (shared retry utilities).
     - Should be done alongside ISSUE-5 (same file).
   - Risk Assessment:
     - Over-retry can increase API usage.
     - Mitigation: keep attempts bounded (3), jittered backoff, and explicit transient classifier.
   - Verification Plan:
     - Unit tests:
       - `500 -> 200` succeeds on second attempt.
       - `429 -> 200` succeeds on second attempt.
       - Timeout once then success => retries.
       - Repeated transient failures => terminal mapped failure.
     - Manual: inspect logs for attempt count and correlation consistency.
   - Estimated Effort: **Medium**
   - completion criteria:
     - Receipt assist cloud call retries transient failures and remains bounded/observable.

4. Batch name: **ISSUE-8 — Add retry loop to CloudCategorizationAssistService**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistServiceTest.kt`
   - objective:
     - Add bounded retries for transient failures — currently this service has **zero** retry logic.
   - Root Cause Analysis:
     - **Why it happens:** `suggest()` performs a single `client.newCall(request).execute()` with a bare try/catch returning `null`.
     - **Exact location:** `CloudCategorizationAssistService.kt:50-72`.
     - **Impact:** Any transient failure (429, 5xx, timeout, connection-reset) immediately returns `null`, silently losing the categorization opportunity. This is the same class of bug as ISSUE-2 but in a different service.
   - Implementation Strategy:
     1. Use `CloudRetryPolicy` from ISSUE-14.
     2. Mirror the retry loop pattern from `CloudDashboardBriefingService` (gold standard).
     3. Add proper exception differentiation: `SocketTimeoutException` (retryable), `SSLException` (terminal), `IOException` with `isConnectionReset` (retryable), general `Exception` (terminal).
     4. Add correlation ID for observability.
     - snippet (pseudocode):
       ```kotlin
       val correlationId = CloudCorrelation.newCorrelationId()
       for (attempt in 1..CloudRetryPolicy.MAX_RETRY_ATTEMPTS) {
           try {
               val result = client.newCall(request).execute().use { ... }
               if (result != null) return result
               if (!retryable) return null
           } catch (e: SocketTimeoutException) { if (lastAttempt) return null }
           delay(CloudRetryPolicy.backoffMs(attempt))
       }
       ```
   - Dependencies:
     - Depends on ISSUE-14 (shared retry utilities).
     - Should be done alongside ISSUE-9 and ISSUE-10 (same file).
   - Risk Assessment:
     - Same as ISSUE-2: bounded retries limit API cost risk.
   - Verification Plan:
     - Unit tests:
       - Transient 5xx then success => retries and succeeds.
       - 429 then success => retries and succeeds.
       - Timeout then success => retries.
       - Non-retryable 4xx => immediate terminal failure.
       - All attempts exhausted => returns null.
   - Estimated Effort: **Medium**
   - completion criteria:
     - Categorization assist cloud call retries transient failures with bounded backoff and correlation logging.

5. Batch name: **ISSUE-3 + ISSUE-13 — Standardize transient HTTP retry semantics across all providers**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt` (verify/align only)
     - test files per provider
   - objective:
     - Migrate all cloud providers to the shared `CloudRetryPolicy` from ISSUE-14, killing per-service `isRetryableHttpStatus()`, `backoffDelayMs()`, and retry constants. Also normalize `CloudQueryInterpretationService` from 2 to 3 retry attempts (ISSUE-13).
   - Root Cause Analysis:
     - **Why it happens:** each provider has custom retry logic with inconsistent status sets and attempt counts.
     - **Exact locations (examples):**
       - `CloudDashboardBriefingService.kt:233-234` retries `500..599 || 429 || 408` *(correct)*
       - `CloudReviewExplanationService.kt:294`, `CloudDedupeJudgeService.kt:320`, `CloudWarrantyExtractionService.kt:375`, `CloudReceiptItemCategorizationService.kt:469` retry only `500..599` *(missing 429/408)*
       - `CloudQueryInterpretationService.kt:61` uses `maxRetries = 2` *(should be 3)*
     - **Impact:** same transient failures behave differently by feature, causing uneven UX and harder operations/debugging.
   - Implementation Strategy:
     1. Replace each provider's local `isRetryableHttpStatus()` with `CloudRetryPolicy.isRetryable()`.
     2. Replace each provider's local `backoffDelayMs()` with `CloudRetryPolicy.backoffMs()`.
     3. Replace each provider's local retry constants with `CloudRetryPolicy.MAX_RETRY_ATTEMPTS`.
     4. Replace each provider's local `isConnectionReset()` with `CloudRetryPolicy.isRetryableIoException()`.
     5. Delete the now-dead local implementations.
     6. For `CloudQueryInterpretationService`: fix attempt count from 2 to 3.
     - snippet (pseudocode):
       ```kotlin
       // Before (per-service):
       private fun isRetryableHttpStatus(code: Int): Boolean = code in 500..599
       // After (shared):
       CloudRetryPolicy.isRetryable(code) // includes 429, 408, 5xx
       ```
   - Dependencies:
     - Requires ISSUE-14 (shared utilities exist).
     - ISSUE-2 and ISSUE-8 should be done first (those services need retry loops added; this issue standardizes existing ones).
   - Risk Assessment:
     - Adding 429/408 to retry set means services now retry rate-limits, which is the correct behavior but increases retry frequency under load.
     - Mitigation: bounded by MAX_RETRY_ATTEMPTS=3 with jittered backoff.
   - Verification Plan:
     - Unit tests (table-driven where possible): verify each provider retries on 429/408/5xx and stops on non-retryable 4xx.
     - Regression tests for terminal error type mapping per provider.
   - Estimated Effort: **High**
   - completion criteria:
     - All cloud providers use `CloudRetryPolicy` for retry decisions and share the same attempt count, status classification, and backoff.

6. Batch name: **ISSUE-12 — Fix delay placement inside response.use{} in CloudQueryInterpretationService**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`
   - objective:
     - Move `delay()` outside the `response.use {}` lambda so the HTTP response is released before back-off sleep.
   - Root Cause Analysis:
     - **Why it happens:** The retry backoff `delay(delayMs)` executes inside `response.use { ... }`, holding the `Response` object alive during the sleep.
     - **Exact location:** `CloudQueryInterpretationService.kt:69-73`.
     - **Impact:** Response body stream and connection remain held during backoff delay (250ms-1.5s), wasting connection pool resources and potentially causing connection timeouts under concurrent usage.
   - Implementation Strategy:
     1. Restructure to use a flag/result pattern: return `null` from `use {}` to signal retry, check outside the block, then delay.
     2. Match the pattern in `CloudDashboardBriefingService` (gold standard) where `retryableHttpFailure` is checked **after** `use {}` returns.
     - snippet (pseudocode):
       ```kotlin
       val parsed = client.newCall(request).execute().use { response ->
           if (retryable) { retryableFlag = true; return@use null }
           parseBody(response)
       }
       if (parsed != null) return parsed
       if (!retryableFlag) return null
       // delay OUTSIDE use{} block
       delay(CloudRetryPolicy.backoffMs(attempt))
       ```
   - Dependencies:
     - Can be combined with ISSUE-3 + ISSUE-13 since they touch the same file.
   - Risk Assessment:
     - Low — structural change only, no behavioral change.
   - Verification Plan:
     - Existing tests pass unchanged.
     - Code review confirms `delay()` is not inside `use {}`.
   - Estimated Effort: **Low**
   - completion criteria:
     - `delay()` call is outside `response.use {}` in all retry paths.

7. Batch name: **ISSUE-4 — Move blocking `.execute()` calls onto Dispatchers.IO**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt`
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt`
     - (already compliant, verify only) `CloudReceiptItemCategorizationService.kt`, `CloudWarrantyExtractionService.kt`
   - objective:
     - Ensure all blocking OkHttp execution occurs on IO dispatcher at service boundary.
   - Root Cause Analysis:
     - **Why it happens:** several suspend methods call blocking `.execute()` without local dispatcher switch.
     - **Exact locations:** e.g., `CloudReceiptAssistService.kt:82`, `CloudCategorizationAssistService.kt:51`, `CloudDashboardBriefingService.kt:88`, `CloudQueryInterpretationService.kt:64`, `CloudReviewExplanationService.kt:66`, `CloudDedupeJudgeService.kt:68`.
     - **Impact:** potential main-thread/blocking contention when callers invoke from non-IO contexts.
   - Implementation Strategy:
     1. Wrap request/retry blocks with `withContext(Dispatchers.IO)` in affected providers.
     2. Avoid nested/duplicated context switches where already present.
     3. Keep coroutine cancellation behavior intact (no broad `runCatching` swallowing cancellation).
   - Dependencies:
     - Best done with ISSUE-2/ISSUE-3/ISSUE-8 to avoid repeated edits in same methods.
   - Risk Assessment:
     - Incorrect context placement can alter exception propagation or cancellation semantics.
     - Mitigation: keep `try/catch` boundaries unchanged, only move blocking call region.
   - Verification Plan:
     - Unit tests continue to pass unchanged.
     - Add focused test(s) that invocation from test main dispatcher does not trigger strict-mode/network-on-main violations (if framework available).
     - Manual: verify no regression in request lifecycle and retries.
   - Estimated Effort: **Medium**
   - completion criteria:
     - All AI cloud providers execute blocking network calls under `Dispatchers.IO`.

8. Batch name: **ISSUE-9 — Replace naive JSON extraction in CloudCategorizationAssistService**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
   - objective:
     - Replace naive `indexOf('{')/lastIndexOf('}')` JSON extraction with the shared depth-aware parser.
   - Root Cause Analysis:
     - **Why it happens:** `extractFirstJsonObject()` in this service uses `text.indexOf('{')` and `text.lastIndexOf('}')`. Unlike every other service, it doesn't track brace depth or string escapes.
     - **Exact location:** `CloudCategorizationAssistService.kt:197-201`.
     - **Impact:** If the model returns text containing multiple JSON objects, or trailing text after the JSON, this can return garbage JSON (e.g., two concatenated objects or non-JSON trailing text included between the first `{` and last `}`). This can cause `JSONException` during parsing or subtle data corruption.
   - Implementation Strategy:
     1. Replace the local `extractFirstJsonObject()` with a call to `CloudJsonParser.extractFirstJsonObject()` from ISSUE-14.
     2. Delete the local implementation.
     - snippet:
       ```kotlin
       // Before:
       private fun extractFirstJsonObject(text: String): String? {
           val start = text.indexOf('{')
           val end = text.lastIndexOf('}')
           if (start == -1 || end <= start) return null
           return text.substring(start, end + 1)
       }
       // After:
       // Use CloudJsonParser.extractFirstJsonObject(text) directly
       ```
   - Dependencies:
     - Depends on ISSUE-14 (shared JSON parser exists).
     - Should be done alongside ISSUE-8 and ISSUE-10 (same file).
   - Risk Assessment:
     - Low — the new parser is strictly more correct.
   - Verification Plan:
     - Unit test: input with multiple JSON objects returns only the first complete one.
     - Unit test: input with nested braces inside strings is parsed correctly.
     - Existing tests pass.
   - Estimated Effort: **Low**
   - completion criteria:
     - `CloudCategorizationAssistService` uses the shared depth-aware JSON parser.

---

#### Phase D — Privacy / Redaction boundary enforcement

9. Batch name: **ISSUE-5 — Enforce redaction at receipt assist cloud boundary**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
     - `app/src/main/java/com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt` (add `redactBeforeCloud` field to `ReceiptAssistInput`)
     - `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt` (or relevant builder — wire the flag)
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistServiceTest.kt`
   - objective:
     - Make cloud service enforce redaction itself, not rely exclusively on upstream builder behavior.
   - Root Cause Analysis:
     - **Why it happens:** prompt interpolates raw input fields (`parsedMerchant`, `rawOcrText`, `lineItemsJson`, `parsedTotal`, `parsedDate`, `parsedTaxAmount`) directly without any redaction check.
     - **Exact location:** `CloudReceiptAssistService.kt:218-227`.
     - **Impact:** if upstream sanitization is bypassed/regresses, sensitive values can leak to cloud prompts.
   - Implementation Strategy:
     1. Add `redactBeforeCloud: Boolean` to `ReceiptAssistInput` (default false).
     2. In `CloudReceiptAssistService.buildPrompt()`, apply `CloudPiiSanitizer.sanitizeText()` to `rawOcrText` and `lineItemsJson`, and `CloudPiiSanitizer.sanitizeMerchant()` to `parsedMerchant`, when `input.redactBeforeCloud` is true.
     3. Wire the flag from `SuggestReceiptExtractionUseCase` or `SmartReceiptAssistService` using `AiPolicy.shouldRedact()`.
     4. Keep redaction deterministic and reversible only where needed.
     - snippet (pseudocode):
       ```kotlin
       // planner pseudocode
       safeMerchant = if (input.redactBeforeCloud) CloudPiiSanitizer.sanitizeMerchant(input.parsedMerchant) else input.parsedMerchant
       safeOcr = if (input.redactBeforeCloud) CloudPiiSanitizer.sanitizeText(input.rawOcrText, MAX_CHARS, "ocr") else input.rawOcrText
       ```
   - Dependencies:
     - Depends on ISSUE-14 (shared PII sanitizer).
     - Pairs naturally with ISSUE-2 (same file).
   - Risk Assessment:
     - Over-redaction may reduce extraction quality.
     - Mitigation: redact only sensitive tokens/identifiers; keep non-sensitive structural context.
   - Verification Plan:
     - Unit tests:
       - when redaction enabled, prompt payload excludes raw emails/cards/IBAN/phones and hashes merchant names.
       - when redaction disabled, payload preserves current behavior.
     - Manual: inspect debug payload capture in a controlled test build.
   - Estimated Effort: **Medium**
   - completion criteria:
     - Prompt boundary cannot send non-redacted receipt facts when policy demands redaction.

10. Batch name: **ISSUE-10 — Enforce merchant redaction in categorization assist flow**
    - files:
      - `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
      - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
      - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilderTest.kt`
    - objective:
      - Ensure merchant name is redacted/pseudonymized when `shouldRedact` is true in the categorization assist flow.
    - Root Cause Analysis:
      - **Why it happens:** `CategorizationAssistInputBuilder.build()` nullifies `supportingText` when `shouldRedact` is true (line 109: `if (shouldRedact) return null`), which is correct. **However**, the raw `merchant` string is passed through unchanged (line 40: `merchant = merchant`). The cloud prompt at `CloudCategorizationAssistService.kt:140` then embeds `input.merchant` directly.
      - **Exact locations:**
        - `CategorizationAssistInputBuilder.kt:33,40` — raw merchant passed even when redacting
        - `CloudCategorizationAssistService.kt:140` — prompt uses `${input.merchant}` unredacted
      - **Impact:** Real merchant names (which can contain bank names, personal identifiers, employer names) are sent to the cloud even when the user's privacy settings require redaction. `supportingText` being nullified is only a partial protection.
    - Implementation Strategy:
      1. In `CategorizationAssistInputBuilder.build()`, apply `CloudPiiSanitizer.sanitizeMerchant()` to the merchant field when `shouldRedact` is true: `merchant = if (shouldRedact) "merchant_${merchant.sha256Prefix()}" else merchant`.
      2. Also redact `deterministicExplanation` when `shouldRedact` is true (it may contain merchant names from matching logic).
      3. No changes needed to `CloudCategorizationAssistService` prompt itself — it will receive already-sanitized data.
      - snippet:
        ```kotlin
        val safeMerchant = if (shouldRedact) {
            "merchant_${merchant.sha256Prefix()}"
        } else {
            merchant
        }
        ```
    - Dependencies:
      - Depends on ISSUE-14 (shared PII sanitizer for `sha256Prefix`).
      - Can be combined with ISSUE-8 and ISSUE-9 work on the same service.
    - Risk Assessment:
      - Pseudonymized merchant reduces categorization accuracy since the model can't identify the store.
      - Mitigation: `recentTransactionsWithSameMerchant` hints (which use category names, not merchant names) still provide signal. The tradeoff is privacy-correct — the user explicitly opted for redaction.
    - Verification Plan:
      - Unit test: when `shouldRedact` is true, `merchant` field contains hash prefix, not raw name.
      - Unit test: when `shouldRedact` is false, `merchant` field is unchanged.
      - Unit test: `deterministicExplanation` is null/redacted when `shouldRedact` is true.
    - Estimated Effort: **Low**
    - completion criteria:
      - No raw merchant name reaches cloud prompt when user's privacy policy requires redaction.

11. Batch name: **ISSUE-11 — Enforce merchant/packageName redaction in review explanation flow**
    - files:
      - `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt`
      - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilderTest.kt`
    - objective:
      - Ensure merchant, packageName, and explanation fields are also redacted when `shouldRedact` is true.
    - Root Cause Analysis:
      - **Why it happens:** `ReviewExplanationInputBuilder.build()` correctly nullifies `notificationText` and `notificationTitle` when `shouldRedact` is true (lines 31, 47-48). **However**, it passes `merchant`, `packageName`, and `explanation` raw (lines 38, 45-46). The cloud prompt at `CloudReviewExplanationService.kt:199,205,204` then embeds these directly: `${input.merchant}`, `${input.packageName}`, `${input.explanation}`.
      - **Exact locations:**
        - `ReviewExplanationInputBuilder.kt:38` — `merchant = review.suggestedMerchant` (no redaction)
        - `ReviewExplanationInputBuilder.kt:46` — `packageName = review.packageName` (no redaction)
        - `ReviewExplanationInputBuilder.kt:45` — `explanation = review.explanation` (may contain merchant names)
        - `CloudReviewExplanationService.kt:199,205` — prompt uses raw values
      - **Impact:** Merchant names, app package names (which identify banks/financial apps), and explanation text can reach the cloud even when the user has opted for privacy-redacted mode.
    - Implementation Strategy:
      1. Apply merchant pseudonymization: `merchant = if (shouldRedact) "merchant_${merchant.sha256Prefix()}" else merchant`.
      2. Pseudonymize `packageName`: `packageName = if (shouldRedact) "app_${review.packageName.sha256Prefix()}" else review.packageName`.
      3. Null out `explanation` when `shouldRedact` is true (it's generated by deterministic matching and may reference raw merchant names).
      - snippet:
        ```kotlin
        return ReviewExplanationInput(
            merchant = if (shouldRedact) "merchant_${review.suggestedMerchant.sha256Prefix()}" else review.suggestedMerchant,
            packageName = if (shouldRedact) "app_${review.packageName.sha256Prefix()}" else review.packageName,
            explanation = if (shouldRedact) null else review.explanation,
            notificationTitle = if (shouldRedact) null else review.notificationTitle,
            notificationText = safeNotificationText,
            ...
        )
        ```
    - Dependencies:
      - Depends on ISSUE-14 (shared PII sanitizer for `sha256Prefix`).
    - Risk Assessment:
      - Pseudonymized merchant/packageName reduces explanation quality.
      - Mitigation: explanation is user-facing text — reduced quality is acceptable when the user explicitly opted for privacy.
    - Verification Plan:
      - Unit test: when `shouldRedact` is true, `merchant` is pseudonymized, `packageName` is pseudonymized, `explanation` is null.
      - Unit test: when `shouldRedact` is false, all fields are raw.
    - Estimated Effort: **Low**
    - completion criteria:
      - No raw merchant/packageName/explanation reaches cloud prompt when user's privacy policy requires redaction.

---

#### Phase E — Query interpretation alias mapping

12. Batch name: **ISSUE-6 — Pseudonym restoration for query interpretation**
    - files:
      - `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
      - `app/src/main/java/com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
      - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`
      - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt` (if parser-level restoration chosen)
      - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilderTest.kt`
      - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt`
    - objective:
      - Preserve privacy in cloud prompts while restoring pseudonyms to real merchant/category labels in returned structured intent.
    - Root Cause Analysis:
      - **Why it happens:** builder redacts merchant/category context but does not persist alias mapping for post-response restoration.
      - **Exact location:** `FinancialQueryInterpretationInputBuilder.kt:32-50`.
      - **Impact:** interpreted filters may contain pseudonyms (e.g., `merchant_<hash>`) and fail to match actual repository data, degrading query accuracy.
    - Implementation Strategy:
      1. Add alias maps to `FinancialQueryInterpretationInput` (e.g., `merchantAliasMap: Map<String, String>` and `categoryAliasMap: Map<String, String>` — pseudonym→original).
      2. Build those maps in `FinancialQueryInterpretationInputBuilder` whenever redaction is enabled:
         ```kotlin
         val merchantAliases = mutableMapOf<String, String>()
         val sanitizedMerchants = merchants.map { raw ->
             val alias = "merchant_${raw.sha256Prefix()}"
             merchantAliases[alias] = raw
             alias
         }
         ```
      3. Post-process cloud parsed result to restore merchant/category tokens using alias map before returning structured intent.
      4. Keep no-op behavior when maps are empty (non-redacted/on-device paths).
      - snippet (pseudocode):
        ```kotlin
        // planner pseudocode
        restoredMerchants = parsedMerchants.map { aliasMap[it] ?: it }
        ```
    - Dependencies:
      - Independent from other cloud provider fixes.
    - Risk Assessment:
      - SHA-256 prefix collisions (12 chars = 48 bits → extremely unlikely with <100 merchants) could map to wrong merchant.
      - Mitigation: deterministic one-to-one map generation in a single builder pass; assert map integrity in tests.
    - Verification Plan:
      - Unit tests:
        - redaction enabled: pseudonymized input created with alias map.
        - simulated cloud result containing pseudonym merchant returns structured intent with restored real merchant.
        - redaction disabled: no restoration mutation.
      - Manual: ask assistant query targeting a known merchant under redaction-on settings; verify filtered results match real merchant.
    - Estimated Effort: **High**
    - completion criteria:
      - Query interpretation returns executable filters (real merchant/category labels) even when cloud context was pseudonymized.

---

#### Phase F — Warranty extraction architectural fix

13. Batch name: **ISSUE-7 — Route warranty extraction through AiCapabilityRouter**
    - files:
      - `app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`
      - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt` (service-level guard/logging alignment)
      - `app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt` *(add `AiCapability.WARRANTY_EXTRACTION`)*
      - `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt` *(add capability policy)*
      - `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt` *(add capability routing)*
      - `app/src/test/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepositoryTest.kt`
      - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionServiceTest.kt` (new)
    - objective:
      - Ensure cloud warranty extraction respects centralized routing decisions (route/mode/policy), not ad-hoc cloud checks.
    - Root Cause Analysis:
      - **Why it happens:** warranty extraction flow calls cloud service directly from repository after `aiPolicy.canUseCloudFor(...)` check and never consults router.
      - **Exact locations:**
        - Direct cloud call path: `WarrantyTrackerRepository.kt:117-123`
        - Cloud service has no route guard: `CloudWarrantyExtractionService.kt:34-55`
      - **Impact:** user-preferred routing and runtime route diagnostics can be bypassed for warranty extraction.
    - Implementation Strategy:
      1. **Decision: Choose Option B** (dedicated capability — cleaner long-term):
         - Add `WARRANTY_EXTRACTION` to `AiCapability` enum.
         - Add routing logic to `DefaultAiCapabilityRouter` for the new capability.
         - Add capability enable/disable toggle mapping in `isCapabilityEnabled()`.
      2. In repository, resolve route via `AiCapabilityRouter.decide(AiCapability.WARRANTY_EXTRACTION, settings)` before invoking cloud extractor.
      3. Execute cloud extractor only when resolved route is `AiRoute.CLOUD`; otherwise return `null`.
      4. Add route-aware logging for observability.
    - Dependencies:
      - Depends on router contracts being stable.
      - Touching the `AiCapability` enum requires exhaustiveness updates in `when` blocks across `DefaultAiCapabilityRouter`.
    - Risk Assessment:
      - Option B introduces broad enum exhaustiveness updates in multiple `when` expressions.
      - Mitigation: the compiler enforces exhaustiveness — non-exhaustive `when` blocks will fail to compile.
    - Verification Plan:
      - Unit tests:
        - route `CLOUD` => cloud extraction invoked.
        - route `ON_DEVICE`/`DETERMINISTIC_FALLBACK`/`DISABLED` => cloud extraction not invoked.
        - redaction flag still passed correctly in cloud route.
      - Manual: toggle preferred mode and confirm warranty extraction respects route decisions.
    - Estimated Effort: **High**
    - completion criteria:
      - Warranty cloud calls occur only when router explicitly allows cloud route.

14. Batch name: **ISSUE-15 — Replace Room entity types with domain models in warranty extraction API surface**
    - files:
      - `app/src/main/java/com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt` [NEW]
      - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt`
      - `app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt` (boundary mapping)
      - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionServiceTest.kt`
    - objective:
      - Decouple the AI service from Room `@Entity` types by introducing domain-level models for warranty extraction input/output.
    - Root Cause Analysis:
      - **Why it happens:** `CloudWarrantyExtractionService` directly imports and returns `data.database.entity.Warranty` and accepts `data.database.entity.ScannedReceipt` as input. This couples an AI cloud service to the Room database schema.
      - **Exact locations:**
        - `CloudWarrantyExtractionService.kt:3-4` — imports Room entities
        - `CloudWarrantyExtractionService.kt:51` — accepts `ScannedReceipt` entity
        - `CloudWarrantyExtractionService.kt:254-266` — constructs and returns `Warranty` entity
      - **Impact:** Schema changes in Room entities can silently break the AI service. The service cannot be tested without database entity knowledge. Violates the multi-implementation pattern where Cloud/OnDevice/Hybrid should share domain-level interfaces.
    - Implementation Strategy:
      1. Create `WarrantyExtractionInput` domain model (fields needed: receiptText, merchant, totalAmount, purchaseDate, currency).
      2. Create `WarrantyExtractionResult` domain model (fields: hasWarranty, productName, warrantyMonths, warrantyType, supportPhone, supportEmail, returnDays, returnConditions, confidence).
      3. Refactor `CloudWarrantyExtractionService` to accept `WarrantyExtractionInput` and return `WarrantyExtractionResult?`.
      4. Move the `WarrantyExtractionResult → Warranty` entity mapping to `WarrantyTrackerRepository`.
      - snippet:
        ```kotlin
        data class WarrantyExtractionInput(
            val receiptText: String,
            val merchant: String?,
            val totalAmount: Double?,
            val purchaseDate: Long?,
            val currency: String
        )
        data class WarrantyExtractionResult(
            val productName: String,
            val warrantyMonths: Int,
            val warrantyType: String,
            val supportPhone: String?,
            val supportEmail: String?,
            val returnConditions: String?,
            val confidence: Float
        )
        ```
    - Dependencies:
      - Best done alongside ISSUE-7 (both touch warranty extraction).
    - Risk Assessment:
      - Medium — requires updating all callers of `CloudWarrantyExtractionService.extractWarranty()`.
      - Mitigation: there's only one caller (`WarrantyTrackerRepository`), so the blast radius is small.
    - Verification Plan:
      - Unit test: `CloudWarrantyExtractionService` no longer imports Room entities.
      - Unit test: `WarrantyTrackerRepository` correctly maps `WarrantyExtractionResult` to `Warranty` entity.
      - Compilation succeeds (no Room entity references in AI service layer).
    - Estimated Effort: **Medium**
    - completion criteria:
      - `CloudWarrantyExtractionService` operates on domain models only; Room entity mapping is in repository.

---

### Dependencies & Execution Order
- Recommended execution sequence (to minimize merge conflicts and isolate risk):
  1. **ISSUE-14** (shared utilities — foundation for everything else)
  2. **ISSUE-1** (isolated, critical routing correctness)
  3. **ISSUE-2 + ISSUE-5** (same service file: `CloudReceiptAssistService`)
  4. **ISSUE-8 + ISSUE-9 + ISSUE-10** (same service file: `CloudCategorizationAssistService`)
  5. **ISSUE-3 + ISSUE-12 + ISSUE-13** (cross-provider retry standardization, touches `CloudQueryInterpretationService`)
  6. **ISSUE-4** (cross-provider dispatcher alignment — best after retry is stabilized)
  7. **ISSUE-11** (review explanation redaction — isolated builder change)
  8. **ISSUE-6** (query interpretation alias mapping — independent domain model change)
  9. **ISSUE-7 + ISSUE-15** (warranty extraction — router + domain model refactor)

- Known assumptions / unknowns:
  - Assumption: deterministic fallback route must never execute AI providers.
  - Assumption: transient retry set should include `429`, `408`, and `5xx` for all cloud providers.
  - ~~Unknown: for ISSUE-7, whether warranty extraction should use existing `RECEIPT_EXTRACTION` capability or a new dedicated capability.~~ **Decision: Option B — dedicated `WARRANTY_EXTRACTION` capability.**
  - Unknown: acceptable redaction-vs-accuracy tradeoff in receipt assist prompts when strict boundary enforcement is enabled (ISSUE-5).

### Rollback / Safety
- Implement and merge as **separate commits per issue** to allow surgical rollback.
- Add/adjust tests before merging each issue branch.
- Keep behavioral deltas narrow:
  - ISSUE-14 rollback: revert shared utilities, providers fall back to local implementations.
  - ISSUE-1 rollback: restore previous route eligibility if regression found.
  - ISSUE-2/8 rollback: revert retry loops while preserving logging improvements.
  - ISSUE-3/12/13 rollback: revert shared retry adoption, return to per-service implementations.
  - ISSUE-4 rollback: revert context wrapping only if cancellation/ordering regressions appear.
  - ISSUE-5/10/11 rollback: keep boundary sanitizer behind clearly scoped method so it can be tuned.
  - ISSUE-6 rollback: preserve alias map fields as no-op compatible to avoid schema churn.
  - ISSUE-7/15 rollback: if router integration causes feature drop, temporarily gate with conservative cloud-only fallback pending capability decision.
  - ISSUE-9 rollback: restore local naive parser (low risk, unlikely needed).

### Acceptance Criteria
- [ ] ISSUE-14: Shared utilities (`CloudRetryPolicy`, `CloudJsonParser`, `CloudPiiSanitizer`, `CloudCorrelation`) exist with tests.
- [ ] ISSUE-1: `SmartReceiptAssistService` does not run on-device attempts for `DETERMINISTIC_FALLBACK` route.
- [ ] ISSUE-2: `CloudReceiptAssistService` retries bounded transient failures and still returns correct terminal error types.
- [ ] ISSUE-8: `CloudCategorizationAssistService` retries bounded transient failures.
- [ ] ISSUE-3: All cloud AI providers use consistent transient retry classification (`429/408/5xx` + retryable network faults).
- [ ] ISSUE-12: `CloudQueryInterpretationService` does not hold response during backoff delay.
- [ ] ISSUE-13: `CloudQueryInterpretationService` uses 3 retry attempts (aligned with other providers).
- [ ] ISSUE-4: All blocking OkHttp `.execute()` calls in AI cloud providers are executed on `Dispatchers.IO`.
- [ ] ISSUE-9: `CloudCategorizationAssistService` uses depth-aware JSON extraction.
- [ ] ISSUE-5: Receipt assist prompt payload is redaction-enforced at cloud boundary.
- [ ] ISSUE-10: Categorization assist prompt receives pseudonymized merchant when redaction is enabled.
- [ ] ISSUE-11: Review explanation prompt receives pseudonymized merchant/packageName when redaction is enabled.
- [ ] ISSUE-6: Query interpretation outputs restored real merchant/category tokens when redaction pseudonyms were used in cloud context.
- [ ] ISSUE-7: Warranty extraction cloud invocation is router-governed and skipped for non-cloud routes, using dedicated `WARRANTY_EXTRACTION` capability.
- [ ] ISSUE-15: `CloudWarrantyExtractionService` operates on domain models, not Room entities.
- [ ] Updated/new tests for all touched modules pass locally.
