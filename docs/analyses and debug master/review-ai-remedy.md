# AI Integration Remedy Review — Cross-Check Against Current Codebase

**Date:** 2026-05-02
**Reviewer:** Automated Code Review
**Status:** FAIL — critical gaps remain

---

## Summary Verdict: FAIL

The codebase shows substantial progress on PRs 1, 2, 4, and 5 (the "safety hotfixes"). However, the privacy hardening (PRs 3, 7), reliability (PR 6), data correctness (PR 8), and test coverage (PR 11) remain significantly incomplete. Several cloud providers bypass the privacy gate entirely, and there is no runtime fallback from cloud-to-on-device in hybrid services.

---

## Issue Matrix

| PR | Title | Severity | Status |
|----|-------|----------|--------|
| 1  | Fix `usedImageInput()` | Critical | **RESOLVED** ✅ |
| 2  | Canonicalize AI defaults | High | **RESOLVED** ✅ |
| 3  | Hard `CloudAiGate` | High/Privacy | **PARTIALLY RESOLVED** ⚠️ |
| 4  | Router API-key availability | High | **RESOLVED** ✅ |
| 5  | Explicit AI modes | High/Privacy | **RESOLVED** ✅ |
| 6  | Reusable hybrid fallback executor | High | **STILL PRESENT** ❌ |
| 7  | Standardize redaction in cloud providers | High/Privacy | **PARTIALLY RESOLVED** ⚠️ |
| 8  | Standardize AI output validation | High | **PARTIALLY RESOLVED** ⚠️ |
| 9  | AI result application boundary | High | **STILL PRESENT** ❌ |
| 10 | Diagnostics and observability | Medium | **PARTIALLY RESOLVED** ⚠️ |
| 11 | Test matrix | — | **PARTIALLY RESOLVED** ⚠️ |

---

## Detailed Findings

### [PR-1] `usedImageInput()` side-effect fix — RESOLVED ✅

**File:** `SmartReceiptAssistService.kt` (line 56–63)

The method now performs a pure, static check:
```kotlin
override fun usedImageInput(input: ReceiptAssistInput): Boolean {
    return input.isImageAnalysisMode &&
        input.imagePath != null &&
        input.imageMimeType != null
}
```
- No `runBlocking` call.
- No `executeWithFallback()` call.
- No cloud/on-device provider invocation.
- No blocking on suspend work.

The comment block (lines 48–54) documents the fix. This is fully resolved.

---

### [PR-2] Canonical AI settings defaults — RESOLVED ✅

**Files:** `AiModels.kt` (line 87–108), `AiSettingsRepositoryImpl.kt` (line 110–133)

All defaults now match between the model and the DataStore fallback:

| Setting | `AiSettings()` default | DataStore fallback | Match? |
|---------|----------------------|-------------------|--------|
| `aiEnabled` | `true` | `true` | ✅ |
| `allowCloudAi` | `false` | `false` | ✅ |
| `receiptAssistEnabled` | `false` | `false` | ✅ |
| `receiptImageCloudEnabled` | `false` | `false` | ✅ |
| `redactBeforeCloud` | `true` | `true` | ✅ |
| `warrantyExtractionEnabled` | `true` | `true` | ✅ |
| `preferredMode` | `AUTO` | `AUTO` | ✅ |
| All other flags | `false` | `false` | ✅ |

Conservative defaults are in place (cloud/image upload off by default, redaction on by default). Tests (`AiPolicyTest` line 144–148) verify the defaults produce correct policy behavior.

**Minor note:** `warrantyExtractionEnabled = true` is somewhat at odds with the conservative privacy posture recommended in the plan. This is a design choice, not a misalignment, but worth noting.

---

### [PR-3] Hard `CloudAiGate` — PARTIALLY RESOLVED ⚠️

**Infrastructure exists:** `CloudAiPrivacyGate` (`domain/privacy/CloudAiPrivacyGate.kt`) is a well-designed gate that checks `cloudAiEnabled`, `receiptImageCloudEnabled`, and `redactBeforeCloud`. It logs every decision via `PrivacyAuditLogger`. It is composed via `CompositePrivacyGate`.

**Critical gap: Not all cloud providers call the gate.**

| Provider | API key check | `allowCloudAi` check | `privacyGate.check()` | Redaction |
|----------|:---:|:---:|:---:|:---:|
| `CloudReceiptAssistService` | ✅ | ✅ | ✅ | ✅ |
| `CloudDedupeJudgeService` | ✅ | ✅ | ❌ | ✅ |
| `CloudCategorizationAssistService` | ✅ | ✅ | ❌ | ✅ |
| `CloudDashboardBriefingService` | ✅ | ✅ | ❌ | ⚠️ via formatter |
| `CloudReviewExplanationService` | ✅ | ✅ | ❌ | ✅ |
| `CloudQueryInterpretationService` | ✅ | ❌ | ❌ | ❌ |
| `CloudWarrantyExtractionService` | ✅ | ❌ | ❌ | ✅ |
| `CloudReceiptItemCategorizationService` | ✅ | ❌ | ❌ | ✅ |

**Specifically egregious:** `CloudQueryInterpretationService` has **no** `allowCloudAi` check, **no** privacy gate call, and **no** redaction. It sends raw query text directly to Gemini with only a key check. This is a **privacy vulnerability**.

**Also missing from the gate concept:** The remedy plan wanted the gate to verify network availability, Wi-Fi-only rules, and capability-specific permissions. The existing `CloudAiPrivacyGate` only checks the privacy settings layer; network/capability checks remain in the router (which is fine for routing, but not a defense-in-depth measure inside providers).

---

### [PR-4] Router API-key availability — RESOLVED ✅

**File:** `DefaultAiCapabilityRouter.kt` (line 158–168)

```kotlin
private fun canUseCloud(capability: AiCapability, settings: AiSettings): Boolean {
    ...
    if (!secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI)) return false
    return true
}
```

- The router now checks API key presence before routing to `CLOUD`. ✅
- Missing key is reported in the route reason. ✅
- Tested: `DefaultAiCapabilityRouterTest` line 366–383 (`decide returns DETERMINISTIC_FALLBACK when cloud preferred but API key is missing`). ✅

---

### [PR-5] Explicit AI modes — RESOLVED ✅

**Files:** `DefaultAiCapabilityRouter.kt`, `SmartReceiptAssistService.kt`

**`ON_DEVICE` mode is now strict:**
```kotlin
// chooseOnDevicePreferred (line 53–75):
// When on-device unavailable → DETERMINISTIC_FALLBACK (not cloud!)
return AiRouteDecision(
    route = AiRoute.DETERMINISTIC_FALLBACK,
    reason = "...Cloud fallback is blocked by ON_DEVICE mode for privacy..."
)
```

**`SmartReceiptAssistService.resolveRouteViability`** (line 218–241):
```kotlin
AiRoute.ON_DEVICE -> RouteViability(
    cloudAvailable = false, // PRIVACY: No cloud when user chose ON_DEVICE
    onDeviceAvailable = true
)
```

- `AUTO` mode: Proper fallback in both directions. ✅
- `CLOUD` mode: Can fallback to on-device (acceptable — privacy-preserving). ✅
- Tested: `DefaultAiCapabilityRouterTest` line 343–363 verifies no cloud leak in ON_DEVICE mode. ✅

---

### [PR-6] Reusable hybrid fallback executor — STILL PRESENT ❌

**All hybrid services follow this pattern:**
```kotlin
// e.g., HybridCategorizationAssistService.kt
override suspend fun suggest(input: ...): ... {
    val settings = aiSettingsRepository.settings().first()
    return when (router.decide(..., settings).route) {
        AiRoute.CLOUD -> cloudService.suggest(input)
        AiRoute.ON_DEVICE -> onDeviceService.suggest(input)
        else -> noOpService.suggest(input)
    }
}
```

**What's missing:**
- If cloud is selected but **fails at runtime** (timeout, 429, parse error), no on-device fallback is attempted.
- No shared fallback executor component exists.
- No diagnostics are recorded for fallback attempts.
- No runtime error classification to decide whether fallback is appropriate.

**Affected services:**
- `HybridCategorizationAssistService`
- `HybridDedupeJudgeService`
- `HybridReviewExplanationService`
- `HybridDashboardBriefingService`
- `HybridQueryInterpretationService`
- `HybridReceiptItemCategorizationService`

`SmartReceiptAssistService` is the **only** service with multi-step fallback, and it does so correctly. The pattern should be extracted and shared.

---

### [PR-7] Standardize redaction in cloud providers — PARTIALLY RESOLVED ⚠️

**Strong points:**
- `CloudPiiSanitizer` exists with regex-based PII removal (email, IBAN, card, phone, long numbers). ✅
- Most providers use it when `shouldRedact` is true. ✅
- `CloudReceiptAssistService` correctly suppresses image upload when redaction is required (line 344). ✅
- `CloudReviewExplanationService` redacts merchant, notification title, and notification text. ✅

**Gaps:**

1. **`CloudQueryInterpretationService`** — **No redaction whatsoever.** No `shouldRedact` parameter, no `CloudPiiSanitizer` usage, no `allowCloudAi` check. Raw query text (which may contain merchant names, amounts, etc.) is sent to cloud with only a key check. **This is a privacy issue.**

2. **`CloudDashboardBriefingService`** — Redacts via `DashboardBriefingPromptFormatter.buildPrompt(input, shouldRedact)`. The formatter should be verified for sanitization, but the infrastructure is there. Acceptable.

3. **`CloudWarrantyExtractionService`** — Has its own `sanitizeReceiptText` and `sanitizeMerchant` with inline regexes (does not reuse `CloudPiiSanitizer`, but functionally equivalent). No `allowCloudAi` check. Acceptable sanitization but lacks the settings gate.

4. **`CloudReceiptItemCategorizationService`** — Has its own `sanitizeCloudText` with inline regexes. No `allowCloudAi` check. Acceptable sanitization.

---

### [PR-8] Standardize AI output validation — PARTIALLY RESOLVED ⚠️

**Strong points:**
- `StrictAiJsonParsing` provides `boundedConfidenceOrNull` (0.0–1.0), `positiveIdOrNull`, `finiteFloatOrNull`, `enumOrNull`. ✅
- `CloudDedupeJudgeService` validates matched target exists in candidate set (hallucination fix, line 261–270). ✅
- `CloudCategorizationAssistService` validates categoryId exists in candidate list (line 266–271). ✅
- `DashboardBriefingResponseParser` uses `boundedConfidenceOrNull`. ✅

**Gaps:**

1. **Receipt extraction (`CloudReceiptAssistService`):**
   - No validation that `total` is non-negative.
   - No validation that `taxAmount` is non-negative.
   - No validation that `taxAmount ≤ total` for non-zero totals.
   - Date is parsed as `Long` but no epoch plausibility check (seconds vs milliseconds).
   - Only checks `optFiniteDoubleStrictOrNull` for finiteness, not business rules.

2. **Warranty extraction (`CloudWarrantyExtractionService`):**
   - Uses `warrantyJson.optDouble("confidence", 0.0).toFloat()` — **no bounds validation** on confidence. AI could return confidence 999 and it would be accepted.
   - `warrantyMonths` has no upper bound check.
   - No check that `returnDays` is plausible.

3. **Receipt item categorization (`CloudReceiptItemCategorizationService`):**
   - Confidence validated for finiteness but not explicitly bounded to [0,1] in all code paths (the prompt asks for 0–1, but the parser doesn't enforce it).

4. **Review explanation (`CloudReviewExplanationService`):**
   - Does not use `StrictAiJsonParsing` for confidence/validation.
   - Output text is only bounded by character limits in `AppConfig`.

---

### [PR-9] AI result application boundary — STILL PRESENT ❌

The remedy plan calls for a consistent "AI suggestion → validated suggestion → applied action" boundary with:
- AI returns suggestions, not commands.
- Low-confidence AI cannot auto-apply.
- AI dedupe cannot delete/merge without validated candidate. (✅ dedupe candidate validation exists)
- Manual user edits are never overwritten silently by AI.

**What exists:**
- `AiSettings` has `receiptQuickSaveEnabled` and `reviewQuickApproveEnabled` flags. ✅
- Dedupe candidate validation exists (hallucination fix). ✅
- AI results are wrapped in `AiServiceResult<*>` with success/failure semantics. ✅

**What's missing:**
- No centralized validation layer between AI output and state mutation.
- `SmartReceiptAssistService.isGoodResult()` checks confidence ≥ 0.70, but this threshold is hardcoded and only for receipt extraction — other capabilities lack such checks.
- No systematic check that `QuickSaveEnabled` or `QuickApproveEnabled` gates high-confidence before auto-apply.
- No audit trail for AI-applied changes.
- No protection against AI silently overwriting user edits.

This is an architectural gap that affects data integrity across all AI-assisted features.

---

### [PR-10] Diagnostics and observability — PARTIALLY RESOLVED ⚠️

**What exists:**
- `AiRuntimeDiagnostics` records route decisions (line 20–28). ✅
- All route decisions in `DefaultAiCapabilityRouter` are recorded. ✅
- Correlation IDs (`CloudCorrelation.newCorrelationId()`) are generated for cloud requests. ✅
- `CloudPiiSanitizer` and inline sanitizers ensure logs don't leak raw PII. ✅

**What's missing from the plan's acceptance criteria:**

| Question | Answerable? |
|----------|:-----------:|
| "Why didn't AI run?" | ✅ Route decision + reason recorded |
| "Did this go to cloud?" | ✅ Route tracked |
| "Was image data uploaded?" | ❌ Not systematically recorded |
| "What provider was actually used?" | ❌ Fallback chains not tracked |
| "Was redaction applied?" | ❌ Not recorded per-request |
| "What error class occurred?" | ⚠️ Only last route recorded, not per-attempt |

- Per-request diagnostics (gate denial reason, provider attempt, fallback attempt, final provider, image usage, redaction applied) are **not systematically captured**.
- `SmartReceiptAssistService` has good internal logging but doesn't feed into `AiRuntimeDiagnostics`.
- Hybrid services have **zero** diagnostics beyond the router's route decision.

---

### [PR-11] Test matrix — PARTIALLY RESOLVED ⚠️

**Existing tests:**
- `DefaultAiCapabilityRouterTest` — 16 tests covering router behavior. ✅
- `AiPolicyTest` — 11 tests covering policy decisions. ✅
- Plus supporting tests for models, repositories, use cases. ✅

**Coverage against the 16 required test cases:**

| # | Test case | Covered? |
|---|-----------|:--------:|
| 1 | AI disabled → no provider call | ✅ Router test |
| 2 | Capability disabled → no provider call | ⚠️ Implicit in policy tests |
| 3 | Cloud disabled + API key present → no cloud | ✅ AiPolicyTest |
| 4 | Cloud enabled + no API key → router rejects | ✅ Router test line 366 |
| 5 | Wi-Fi-only + mobile data → no cloud | ✅ Router test line 190 |
| 6 | Local-only + cloud available → no cloud | ✅ Router test line 343 |
| 7 | Auto + cloud timeout → on-device fallback | ❌ No fallback executor |
| 8 | Auto + cloud parse error → on-device/no-op | ❌ No fallback executor |
| 9 | Redaction + image → no upload | ❌ No test |
| 10 | Image cloud disabled → no upload | ❌ No test |
| 11 | `usedImageInput()` → no provider call | ❌ No test |
| 12 | AI dedupe fake target → reject | ❌ No test |
| 13 | AI receipt confidence 999 → reject | ❌ No test |
| 14 | AI receipt negative total → reject | ❌ No test |
| 15 | Sanitizer removes raw OCR/notification | ❌ No test |
| 16 | Diagnostics records disabled decisions | ❌ No test |

**8 of 16 test cases are completely uncovered.** The router/policy tests are strong, but provider-level and integration tests are missing.

---

## Additional Issues Not in the Remedy Plan

### [ISSUE-A] [HIGH] `CloudQueryInterpretationService` has zero privacy guards

**File:** `CloudQueryInterpretationService.kt`

This service:
- Does NOT check `allowCloudAi` setting
- Does NOT call `privacyGate.check()`
- Does NOT apply any redaction/sanitization
- Only checks API key presence

The query text sent to cloud can contain merchant names, amounts, dates, and other financial context. This is a **privacy vulnerability** — the query interpretation path bypasses all privacy controls.

**Suggested fix:** Add `allowCloudAi` check, `privacyGate.check(PrivacyCapability.CLOUD_AI_GENERAL)`, and pass `shouldRedact` flag through `OnDeviceQueryInterpretationService.buildPrompt()` with sanitization.

---

### [ISSUE-B] [HIGH] `CloudWarrantyExtractionService` and `CloudReceiptItemCategorizationService` bypass the privacy gate

**Files:** `CloudWarrantyExtractionService.kt`, `CloudReceiptItemCategorizationService.kt`

Neither service receives `PrivacyGate` or checks `allowCloudAi`. They can be directly injected and called, bypassing the privacy layer. While the hybrid services would normally route through the router, direct DI injection of these classes could bypass all gates.

**Suggested fix:** Inject `PrivacyGate` and add `privacyGate.check()` and `allowCloudAi` checks before any network call, matching the pattern in `CloudReceiptAssistService`.

---

### [ISSUE-C] [MEDIUM] Confidence not validated in warranty extraction

**File:** `CloudWarrantyExtractionService.kt` (line 262)

```kotlin
confidence = warrantyJson.optDouble("confidence", 0.0).toFloat()
```

No bounds check. AI could return `confidence: 999` and it would be accepted. Also, `warrantyMonths` has no upper bound.

**Suggested fix:** Use `StrictAiJsonParsing.boundedConfidenceOrNull()` or equivalent.

---

### [ISSUE-D] [MEDIUM] Receipt extraction lacks business rule validation

**File:** `CloudReceiptAssistService.kt`

The parser accepts any finite numeric value for `total`, `taxAmount`, and `date`. No checks for:
- `total ≥ 0`
- `taxAmount ≥ 0`
- `taxAmount ≤ total` (when both present)
- Date plausibility (epoch millis vs seconds)

**Suggested fix:** Add post-parse validation in `parseResponse()` that rejects impossible values.

---

### [ISSUE-E] [MINOR] `AiPolicyTest` has misleading comment

**File:** `AiPolicyTest.kt` (line 144–148)

```kotlin
val defaults = AiSettings() // aiEnabled=false, allowCloudAi=false, redactBeforeCloud=true
```

Comment says `aiEnabled=false` but `AiSettings()` defaults `aiEnabled = true`. The test passes because `allowCloudAi` is `false`, but the comment is incorrect.

**Suggested fix:** Update comment to `aiEnabled=true, allowCloudAi=false, redactBeforeCloud=true`.

---

### [ISSUE-F] [LOW] `CloudDashboardBriefingService` logs full URL with base path

**File:** `CloudDashboardBriefingService.kt` (line 99)

```kotlin
Timber.d("CloudDashboardBriefingService: URL: ${AppConfig.Ai.GEMINI_BASE_URL}/v1beta/models/...")
```

While the base URL isn't a secret, the full model path is verbose and unnecessary in logs. The PR 10 acceptance criteria say "Never log ... full prompt" — this is borderline but not a direct violation.

---

## Coverage Summary

| Category | Status |
|----------|--------|
| Requirements met | Partially — 5 of 11 PRs fully resolved, 4 partially resolved, 2 still present |
| Testing adequate | No — only router/policy tests exist; provider, integration, and privacy-specific tests are largely missing |

---

## Recommended Priority Actions

1. **IMMEDIATE:** Fix `CloudQueryInterpretationService` — add `allowCloudAi` check and redaction (ISSUE-A).
2. **IMMEDIATE:** Add `PrivacyGate` checks to `CloudWarrantyExtractionService` and `CloudReceiptItemCategorizationService` (ISSUE-B).
3. **HIGH:** Add `PrivacyGate` to `CloudDedupeJudgeService`, `CloudDashboardBriefingService`, `CloudReviewExplanationService` for defense-in-depth (PR 3).
4. **HIGH:** Implement runtime fallback in hybrid services (PR 6).
5. **HIGH:** Add business rule validation to receipt extraction parser (PR 8, ISSUE-D).
6. **HIGH:** Add confidence bounds validation to warranty extraction (ISSUE-C).
7. **MEDIUM:** Add provider-level integration tests for the 8 uncovered test cases (PR 11).
8. **MEDIUM:** Implement AI result application boundary (PR 9).
9. **LOW:** Fix misleading test comment (ISSUE-E).
