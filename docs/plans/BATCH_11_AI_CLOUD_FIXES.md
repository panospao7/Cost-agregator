# Batch 11: AI & Cloud Services Fixes (H1, H2, M1)

## Technical Plan (Advanced)
### Scope
- In:
  - **H1**: Fix `SmartReceiptAssistService` on-device attempt gating so router decisions are not bypassed (`SmartReceiptAssistService.kt:147-162`).
  - **H2**: Expand retryable HTTP statuses in `CloudDashboardBriefingService` to include **429** and **408** (`CloudDashboardBriefingService.kt:94-104,233`).
  - **M1**: Close reverse-mapping fallback gap for redacted cloud category names in `CloudReceiptItemCategorizationService` (`CloudReceiptItemCategorizationService.kt:496-523`).
  - Add/adjust unit tests for all three fixes.
- Out:
  - No architecture-level router redesign.
  - No global retry utility extraction across all cloud services (can be follow-up hardening batch).
  - No prompt redesign/model tuning beyond what is required for correctness.

### Complexity Assessment
- Estimated files touched: **6-8**
  - Production: 3 core provider files
  - Tests: 3 existing test files (or +1 helper test file if needed)
  - Docs: this plan
- Risk level: **medium**
- Cross-module impact: **yes** (routing policy behavior, cloud retry semantics, redaction mapping in parsing path)

### Assumptions & Unknowns
- Assumption: For `SmartReceiptAssistService`, **router decision is authoritative** for whether cloud/on-device paths are allowed in the current invocation.
- Assumption: Retrying 429/408 in briefing flow is desired even without explicit `Retry-After` header handling.
- Unknown: Whether product wants cloud->on-device fallback inside `SmartReceiptAssistService` when route is `CLOUD` (current issue wording suggests this should *not* happen).
- Unknown: Whether model may return cloud category aliases with case/whitespace variation (plan includes defensive normalization).

### Batch Plan
1. Batch name: **H1 – Router-authoritative on-device gating in SmartReceiptAssistService**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistServiceTest.kt`
   - objective:
     - Ensure on-device attempts do not run unless permitted by the route decision, removing router bypass behavior.
   - root cause analysis:
     - **Why it happens:**
       - `shouldAttemptOnDeviceVision(...)` and `shouldAttemptOnDeviceText(...)` only check input/policy, not route.
       - Current logic allows on-device attempts even when router picks `CLOUD`, `DETERMINISTIC_FALLBACK`, or `DISABLED`.
     - **Exact location:**
       - `SmartReceiptAssistService.kt:147-155` and `:159-161`.
     - **Impact:**
       - Violates router contract and can run AI paths that should be blocked by mode/policy/context.
       - Causes inconsistent behavior and potentially unnecessary on-device calls in fallback/disabled routes.
   - implementation strategy:
     1. Make on-device attempt predicates route-aware (accept `route: AiRoute` argument).
     2. Gate on-device paths behind `route == AiRoute.ON_DEVICE` (plus existing policy/input checks).
     3. Keep cloud path gating as-is (`route == AiRoute.CLOUD`).
     4. Verify deterministic fallback path is reached directly when route is fallback/disabled.
     5. Add unit tests for route combinations.
     - pseudo-snippet (illustrative only):
       ```kotlin
       // non-final pseudocode
       shouldAttemptOnDeviceVision = (route == ON_DEVICE) && imageMode && hasImage && onDeviceAllowed
       shouldAttemptOnDeviceText   = (route == ON_DEVICE) && onDeviceAllowed
       ```
   - dependencies:
     - No dependency on H2/M1.
     - Depends on existing router behavior in `DefaultAiCapabilityRouter` remaining unchanged.
   - risks:
     - Behavioral change could remove previously implicit cloud->on-device fallback.
     - Tests may need additional mocking for no-op fallback branch.
   - mitigation:
     - Add explicit tests for each route (`CLOUD`, `ON_DEVICE`, `DETERMINISTIC_FALLBACK`).
     - Confirm expected route semantics with product/architecture owner before merge (if ambiguity persists).
   - validation:
     - Unit tests:
       - Route `ON_DEVICE`: cloud not called; on-device called.
       - Route `CLOUD`: on-device not called.
       - Route `DETERMINISTIC_FALLBACK`: neither cloud nor on-device called; deterministic fallback called.
     - Manual sanity check:
       - Run receipt assist in a scenario forcing fallback route; verify no AI provider invocation in logs.
   - estimated effort: **Medium**
   - completion criteria:
     - On-device invocation is impossible unless route is `ON_DEVICE`.
     - Existing ON_DEVICE behavior preserved.
     - New route-coverage tests pass.

2. Batch name: **H2 – Retry policy expansion for 429/408 in CloudDashboardBriefingService**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt`
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingServiceTest.kt`
   - objective:
     - Treat HTTP 429 (rate limit) and 408 (request timeout) as transient retryable statuses, not terminal failures on first response.
   - root cause analysis:
     - **Why it happens:**
       - `isRetryableHttpStatus(code)` currently returns true only for `500..599`.
       - Retry branch at `:94-104` is therefore skipped for 429/408.
     - **Exact location:**
       - `CloudDashboardBriefingService.kt:94-104` (retry branch use) and `:233` (retryable predicate).
     - **Impact:**
       - Rate-limited or upstream timeout responses fail fast despite being transient.
       - Lower resilience and avoidable user-facing failures.
   - implementation strategy:
     1. Extend retryable status predicate to include `429` and `408` in addition to 5xx.
     2. Keep existing backoff/jitter mechanism unchanged for now.
     3. Preserve current max attempts and terminal error behavior.
     4. Add tests covering 429 and 408 recovery behavior.
     - pseudo-snippet (illustrative only):
       ```kotlin
       // non-final pseudocode
       retryable = (code in 500..599) || (code == 429) || (code == 408)
       ```
   - dependencies:
     - Independent of H1 and M1.
   - risks:
     - Slightly higher request volume during sustained throttling.
     - Without `Retry-After` handling, retries may still be suboptimal under heavy rate limiting.
   - mitigation:
     - Keep retries bounded (`MAX_RETRY_ATTEMPTS=3`).
     - Follow-up optional enhancement: respect `Retry-After` when present.
   - validation:
     - Unit tests:
       - 429 then 200 => succeeds after retry (attempt count = 2).
       - 408 then 200 => succeeds after retry (attempt count = 2).
       - repeated 429 through max attempts => terminal `HttpError(429)`.
     - Manual check:
       - Inspect logs to confirm retry branch triggers for 429/408.
   - estimated effort: **Low**
   - completion criteria:
     - 429/408 are retried identically to 5xx.
     - Existing 5xx retry behavior remains intact.
     - Test coverage includes both new status codes.

3. Batch name: **M1 – Redaction reverse-mapping fallback hardening in CloudReceiptItemCategorizationService**
   - files:
     - `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
     - `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationServiceTest.kt`
   - objective:
     - Ensure redacted cloud category aliases map back to user-visible category names, even when `cloudCategoryOptions` input is empty and model omits `categoryId`.
   - root cause analysis:
     - **Why it happens:**
       - `cloudCategoryOptionsForPrompt(...)` synthesizes fallback aliases (`cat_<id>`) when redaction is enabled and options are empty.
       - `mapCloudCategoryNameToRaw(...)` only reverse-resolves via `input.cloudCategoryOptions` (not synthesized fallback aliases) when `categoryId` is null.
     - **Exact location:**
       - Fallback alias generation: `CloudReceiptItemCategorizationService.kt:496-506`.
       - Reverse mapping gap: `:508-523`.
     - **Impact:**
       - UI/artifact may display pseudonymized alias (e.g., `cat_123`) instead of real category name.
       - Degraded UX and inconsistent post-processing in redacted mode.
   - implementation strategy:
     1. Build an **effective cloud options map** from `cloudCategoryOptionsForPrompt(input)` and reuse for both prompt and reverse mapping.
     2. In reverse mapping:
        - First resolve by explicit `categoryId` if present.
        - Else resolve `rawCategoryName` against effective cloud options (not only `input.cloudCategoryOptions`).
        - If resolved to categoryId, map to `userCategories` name.
        - Else keep raw name unchanged.
     3. Add defensive normalization (`trim`, optional case-insensitive comparison) for alias matching.
     4. Ensure alternatives mapping path uses the same resolver.
     - pseudo-snippet (illustrative only):
       ```kotlin
       // non-final pseudocode
       effectiveOptions = cloudCategoryOptionsForPrompt(input)
       resolvedId = categoryId ?: effectiveOptions.findByCloudName(rawCategoryName)?.categoryId
       mappedName = userCategories.findById(resolvedId)?.name ?: rawCategoryName
       ```
   - dependencies:
     - Independent of H1/H2.
   - risks:
     - Incorrect normalization could over-map genuine new category suggestions.
     - If model outputs noisy alias strings, mapping might still fail.
   - mitigation:
     - Keep fallback behavior safe: unresolved names remain raw.
     - Add tests for both successful alias mapping and unresolved-name passthrough.
   - validation:
     - Unit tests:
       - Redaction ON + empty `cloudCategoryOptions` + response `categoryName="cat_<id>"`, `categoryId=null` => mapped to real user category name.
       - Same scenario for `alternatives` entries.
       - Unknown alias remains unchanged.
     - Manual check:
       - Trigger categorization in redacted mode and inspect resulting category names for readability.
   - estimated effort: **Medium**
   - completion criteria:
     - No leaked fallback aliases (`cat_<id>`) in resolved output when reversible mapping exists.
     - Reverse-mapping works for both primary suggestion and alternatives.
     - Existing redaction payload test remains green.

### Dependencies
- **Execution order recommendation:** H1 -> H2 -> M1 (all logically independent; this order prioritizes high-severity routing correctness first).
- Shared dependency: stable unit test infrastructure for OkHttp interceptors and mockk-based service tests.
- No blocking dependency on another batch.

### Rollback / Safety
- Keep changes isolated per issue and commit separately for clean rollback.
- If H1 causes unexpected behavior regression, rollback only routing-gate changes while retaining tests to document expected policy.
- If H2 causes API pressure concerns, rollback retry predicate expansion only.
- If M1 mapping causes false remaps, rollback normalization layer while retaining effective-options lookup.
- Add targeted regression tests before merge to reduce rollback likelihood.

### Acceptance Criteria
- [ ] **H1:** `SmartReceiptAssistService` no longer executes on-device attempts when router route is not `ON_DEVICE`.
- [ ] **H1:** Route-based behavior is covered by tests for ON_DEVICE, CLOUD, and DETERMINISTIC_FALLBACK outcomes.
- [ ] **H2:** `CloudDashboardBriefingService` retries on HTTP 429 and 408 (bounded by existing retry count/backoff).
- [ ] **H2:** New retry tests pass and existing 5xx retry test still passes.
- [ ] **M1:** Redacted alias names are reverse-mapped via effective cloud options even when input `cloudCategoryOptions` is empty.
- [ ] **M1:** Reverse-mapping works for primary category and alternatives, with safe passthrough for unknown aliases.
- [ ] All touched tests pass in local CI scope for provider and routing modules.
