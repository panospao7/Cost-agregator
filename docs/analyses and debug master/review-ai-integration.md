# AI Integration Review — Cross-Check Against Current Codebase

> Review date: 2026-05-02  
> Source analysis: `docs/analyses and debug master/ai-integration-analysis.md`  
> Codebase: `app/src/main/java/com/yourname/expensetracker/`  
> Branch: current working tree (master-refactor derived)

---

## VERDICT: FAIL

**Summary**: 7 of 11 original issues are fully resolved. 2 are partially resolved, 2 are still present. Additionally, **3 new issues** were discovered in cloud providers that lack internal privacy gating. The most impactful remaining gaps are:
1. No runtime fallback in hybrid services (except SmartReceiptAssist)
2. Several cloud providers lack `allowCloudAi` checks and `PrivacyGate` integration
3. AI output validation is still uneven across providers (confidence not bounded, totals not checked)

---

## Issue-by-Issue Cross-Check

### [ISSUE-1] `SmartReceiptAssistService.usedImageInput()` triggers full AI pipeline
**Status: RESOLVED** ✅  
**Severity in analysis: Critical**

- **Analysis described**: `runBlocking { executeWithFallback(input).actualUsedImageInput() }`
- **Current code** (`SmartReceiptAssistService.kt` lines 56–63):
```kotlin
override fun usedImageInput(input: ReceiptAssistInput): Boolean {
    return input.isImageAnalysisMode &&
        input.imagePath != null &&
        input.imageMimeType != null
}
```
- **Fix applied**: Pure static check — no AI services invoked. Comments at lines 49–54 explicitly document the privacy fix.

---

### [ISSUE-2] AI settings defaults inconsistent
**Status: RESOLVED** ✅  
**Severity in analysis: High**

- **Analysis described**: `AiSettings` defaulted `receiptAssistEnabled=true`, `receiptImageCloudEnabled=true`, but `AiSettingsRepositoryImpl` read them as `false` when unset.
- **Current code**: Both sources now agree:
  - `AiSettings.kt` (line 95–96): `receiptAssistEnabled = false`, `receiptImageCloudEnabled = false`
  - `AiSettingsRepositoryImpl.kt` (line 118–119): `?: false`, `?: false`
- **Note**: No canonical `DefaultAiSettings` object exists (the two sources maintain separate default lists). The values are consistent now, but a single-source-of-truth would prevent future drift (see [NEW-6] below).

---

### [ISSUE-3] Router does not consider API-key availability
**Status: RESOLVED** ✅  
**Severity in analysis: High**

- **Analysis described**: `canUseCloud()` checked policy, network, Wi-Fi but not API key presence.
- **Current code** (`DefaultAiCapabilityRouter.kt` lines 158–168):
```kotlin
private fun canUseCloud(capability: AiCapability, settings: AiSettings): Boolean {
    // ...
    if (!secureKeyStorage.hasKey(SecureKeyStorage.KEY_GEMINI)) return false
    return true
}
```
- **Fix applied**: API-key check added. The `cloudUnavailableReason()` helper also reports missing key diagnostic (line 236).

---

### [ISSUE-4] Runtime fallback inconsistent
**Status: STILL PRESENT** ❌  
**Severity in analysis: High**

- **Analysis described**: Only `SmartReceiptAssistService` has multi-attempt fallback chain. Other hybrid services do single-route calls with no retry to on-device if cloud fails at runtime.
- **Current code**: Unchanged for non-receipt hybrid services:
  - `HybridCategorizationAssistService.kt` (lines 23–31) — single `when/route`, no runtime fallback
  - `HybridDedupeJudgeService.kt` (lines 24–32) — single `when/route`, no runtime fallback
  - `HybridReviewExplanationService.kt` (lines 24–32) — single `when/route`, no runtime fallback
  - `HybridDashboardBriefingService.kt` (lines 24–32) — single `when/route`, no runtime fallback
  - `HybridQueryInterpretationService.kt` (lines 23–33) — single `when/route`, no runtime fallback
  - `HybridReceiptItemCategorizationService.kt` (lines 22–31) — single `when/route`, no runtime fallback
- **Impact**: If router selects CLOUD and the cloud call fails (timeout, parse error, HTTP 5xx), on-device is never tried. There is no reusable `HybridExecutor`.
- **Recommendation**: The analysis's suggested `HybridExecutor` pattern with capability-declared fallback orders remains unimplemented.

---

### [ISSUE-5] Cloud providers are not all hard-gated internally
**Status: PARTIALLY RESOLVED** ⚠️  
**Severity in analysis: High / privacy**

- **Analysis described**: Cloud providers only checked API key, not settings, capability flags, Wi-Fi, or redaction.
- **Current code**: Progress made, but uneven:

| Cloud Provider | API Key Check | `allowCloudAi` Check | PrivacyGate | PII Sanitization |
|---|---|---|---|---|
| `CloudReceiptAssistService` | ✅ | ✅ | ✅ | ✅ |
| `CloudCategorizationAssistService` | ✅ | ✅ | ❌ | ✅ |
| `CloudDedupeJudgeService` | ✅ | ✅ (nullable) | ❌ | ✅ |
| `CloudReviewExplanationService` | ✅ | ✅ (nullable) | ❌ | ✅ |
| `CloudDashboardBriefingService` | ✅ | ✅ (nullable) | ❌ | ✅ |
| `CloudQueryInterpretationService` | ✅ | ❌ | ❌ | ❌ |
| `CloudReceiptItemCategorizationService` | ✅ | ❌ | ❌ | ⚠️ (inline only) |
| `CloudWarrantyExtractionService` | ✅ | ❌ | ❌ | ❌ |

- **Key gaps**: 
  - Three services (`CloudQueryInterpretationService`, `CloudReceiptItemCategorizationService`, `CloudWarrantyExtractionService`) lack even basic `allowCloudAi` checking.
  - No unified `CloudAiGate` as recommended — each service implements its own varying set of guards.
  - `CloudDedupeJudgeService`, `CloudReviewExplanationService`, `CloudDashboardBriefingService` have nullable `AiSettingsRepository` (falls back to no check when null in test constructors — acceptable in production since DI always injects it).

---

### [ISSUE-6] Receipt image upload depends on `input.redactBeforeCloud`
**Status: RESOLVED** ✅  
**Severity in analysis: High / privacy**

- **Analysis described**: Provider trusted caller's `input.redactBeforeCloud` flag rather than current settings.
- **Current code** (`CloudReceiptAssistService.kt` line 110):
```kotlin
val shouldRedact = settings.redactBeforeCloud
```
- **Fix applied**: Redaction decision now comes from `aiSettingsRepository.settings().first()`, which reads current persisted user preference. The `buildImageInlineData()` method (lines 339–366) also has an explicit comment at lines 343–347 documenting the privacy fix.

---

### [ISSUE-7] "On-device preferred" can still cloud-fallback
**Status: RESOLVED** ✅  
**Severity in analysis: High / privacy expectation**

- **Analysis described**: `ON_DEVICE` mode could fall back to cloud when on-device was unavailable.
- **Current code** (`DefaultAiCapabilityRouter.kt` lines 53–75):
```kotlin
private suspend fun chooseOnDevicePreferred(...): AiRouteDecision {
    if (canUseOnDevice(...)) {
        return AiRouteDecision(route = AiRoute.ON_DEVICE, ...)
    }
    // PRIVACY FIX: When user explicitly selects ON_DEVICE mode, do NOT fall back to
    // cloud. ...
    return AiRouteDecision(
        route = AiRoute.DETERMINISTIC_FALLBACK,
        reason = "On-device was preferred but unavailable. Cloud fallback is blocked..."
    )
}
```
- **Additional enforcement** (`SmartReceiptAssistService.kt` lines 218–241): `resolveRouteViability()` sets `cloudAvailable = false` when route is `ON_DEVICE`.
- **Fix applied**: ON_DEVICE is now strict local-only. No cloud fallback.

---

### [ISSUE-8] Cloud dedupe result not validated against candidate set
**Status: RESOLVED** ✅  
**Severity in analysis: Critical if AI dedupe can affect approval/deletion**

- **Analysis described**: AI could hallucinate `matchedTargetId` not in candidates.
- **Current code** (`CloudDedupeJudgeService.kt` lines 261–282):
```kotlin
val isValidMatch = if (rawMatchedTargetId != null && rawMatchedTargetType != null) {
    input.candidates.any { candidate ->
        candidate.targetId == rawMatchedTargetId && candidate.targetType == rawMatchedTargetType
    }
} else { true }
```
- **Fix applied**: Hallucinated matches are rejected and logged. However, the analysis also recommended rejecting impossible verdict combinations (duplicate verdict with no matched target, distinct verdict with matched target). That consistency check is not present.

---

### [ISSUE-9] AI numeric/confidence validation uneven
**Status: PARTIALLY RESOLVED** ⚠️  
**Severity in analysis: High**

- **Analysis described**: Receipt confidence not bounded, total could be negative, date not range-validated, dedupe confidence not bounded.
- **Current code**: Mixed state:

| Validator | Exists? | Used by |
|---|---|---|
| `boundedConfidenceOrNull` (0–1) | ✅ `StrictAiJsonParsing.kt` line 31 | `CloudCategorizationAssistService` only |
| Confidence bounded 0–1 for dedupe | ❌ | `CloudDedupeJudgeService` uses `optFiniteDoubleStrictOrNull` (unbounded) — line 288 |
| Confidence bounded 0–1 for receipt | ❌ | `CloudReceiptAssistService` uses `optFiniteDoubleStrictOrNull` (unbounded) — lines 397, 408 |
| Positive money for receipt total | ❌ | `toSuggestedDoubleOrNull()` only checks finite, not > 0 (line 402–411) |
| Epoch millis in reasonable range | ❌ | `toSuggestedLongOrNull()` only checks whole-number (lines 413–450) |
| Confidence bounded 0–1 for receipt items | ❌ | `CloudReceiptItemCategorizationService` uses `optFiniteDoubleStrictOrNull` (unbounded) — line 304 |
| Positive money for tax/total | ❌ | No positivity check on `taxAmount` or `total` in receipt parsing |

- **Impact remains**: AI can return confidence 999, negative totals, timestamps in seconds instead of milliseconds, negative tax. No shared `AiOutputValidators` layer as recommended.

---

### [ISSUE-10] Cloud redaction appears uneven across providers
**Status: RESOLVED** ✅  
**Severity in analysis: High / privacy**

- **Analysis described**: Only `CloudReceiptAssistService` applied sanitization; `CloudCategorizationAssistService` and `CloudDedupeJudgeService` sent raw merchant/text.
- **Current code**: All cloud providers now apply `CloudPiiSanitizer` internally when `shouldRedact` is true:
  - `CloudCategorizationAssistService.buildPrompt()`: sanitizes merchant (line 171) and supporting text (line 176)
  - `CloudDedupeJudgeService.buildPrompt()`: sanitizes merchant (lines 197, 202) and text previews (lines 198, 203)
  - `CloudReviewExplanationService.buildPrompt()`: sanitizes merchant (line 196) and notification text (lines 201, 210)
  - `CloudDashboardBriefingService`: delegates to `DashboardBriefingPromptFormatter.buildPrompt(input, shouldRedact)` (line 211)
  - `CloudWarrantyExtractionService`: accepts `shouldRedactBeforeCloud` parameter but uses inline `sanitizeCloudText` (not `CloudPiiSanitizer`)
- **Fix applied**: `shouldRedact` is derived from `settings.redactBeforeCloud` in each service.

---

### [ISSUE-11] Disabled route decisions not recorded in diagnostics
**Status: RESOLVED** ✅  
**Severity in analysis: Medium**

- **Analysis described**: Early returns for `aiEnabled=false` and `capability disabled` happened before diagnostics recording.
- **Current code** (`DefaultAiCapabilityRouter.kt` lines 29–51): Both early returns now call `aiRuntimeDiagnostics.recordRouteDecision()` before returning. The main `when` block result is also recorded at lines 48–49.

---

## NEW ISSUES DISCOVERED

### [NEW-1] [MAJOR] `CloudQueryInterpretationService` lacks all privacy guards
- **File**: `CloudQueryInterpretationService.kt`
- **Problem**: Only checks API key presence (line 50). No `allowCloudAi` check, no `PrivacyGate`, no PII sanitization, no `AiSettingsRepository` injection. If someone injects or constructs this service directly, it will send raw financial queries to cloud regardless of user privacy settings.
- **Fix**: Add `AiSettingsRepository`, validate `allowCloudAi`, integrate `PrivacyGate`, and apply `CloudPiiSanitizer` to prompt data.

### [NEW-2] [MAJOR] `CloudReceiptItemCategorizationService` and `CloudWarrantyExtractionService` lack `allowCloudAi` and `PrivacyGate` checks
- **Files**: `CloudReceiptItemCategorizationService.kt`, `CloudWarrantyExtractionService.kt`
- **Problem**: Both services skip `allowCloudAi` and `PrivacyGate`. `CloudWarrantyExtractionService` has no `AiSettingsRepository` at all — it relies on the caller passing `shouldRedactBeforeCloud`.
- **Fix**: Inject `AiSettingsRepository`, check `allowCloudAi` as defense-in-depth, and add `PrivacyGate.check()` calls.

### [NEW-3] [MINOR] Cloud providers inconsistently use `PrivacyGate` vs inline checks
- **Problem**: Only `CloudReceiptAssistService` integrates with the formal `PrivacyGate` (CompositePrivacyGate → CloudAiPrivacyGate). Others use ad-hoc `if (!settings.allowCloudAi) return disabled` patterns. The `CloudAiPrivacyGate` provides richer policy checks (e.g., `receiptImageCloudEnabled`, `redactBeforeCloud` for image upload suppression) that are duplicated or absent in other services.
- **Impact**: Policy evolves in `CloudAiPrivacyGate` but doesn't propagate to all cloud providers.
- **Fix**: Either inject `PrivacyGate` into all cloud providers, or create a `CloudAiGate` utility class used consistently.

### [NEW-4] [MINOR] `CloudDedupeJudgeService` confidence not bounded 0–1
- **File**: `CloudDedupeJudgeService.kt` line 288
- **Problem**: Uses `optFiniteDoubleStrictOrNull("confidence")` which accepts any finite double (e.g., 999, -5). The `StrictAiJsonParsing.boundedConfidenceOrNull()` helper exists but is not used here.
- **Fix**: Replace with `boundedConfidenceOrNull("confidence")`.

### [NEW-5] [MINOR] `CloudReceiptAssistService` receipt output lacks bounded validation
- **File**: `CloudReceiptAssistService.kt`
- **Problem**: `toSuggestedDoubleOrNull()` (line 402) accepts any finite double for `total` and `taxAmount`, including negatives. `toSuggestedLongOrNull()` (line 413) accepts any whole number for `date`, including timestamps in seconds or far-future values. `confidence` is not bounded 0–1.
- **Fix**: Add positivity checks for monetary values, epoch range checks for dates (~2015-01-01 to ~2100-01-01), and bounded confidence.

### [NEW-6] [MINOR] No canonical AI settings defaults object
- **Files**: `AiModels.kt` (AiSettings data class), `AiSettingsRepositoryImpl.kt` (toAiSettings())
- **Problem**: While values now match, defaults are duplicated. Future changes could reintroduce drift.
- **Fix**: Create `object DefaultAiSettings { val value = AiSettings(...) }` and reference it from both the data class default and the repository's fallback.

---

## Cross-Reference: Recommended Fix Order from Analysis

| PR | Recommendation | Status |
|---|---|---|
| PR 1 | Fix `usedImageInput()` | ✅ Done |
| PR 2 | Canonicalize AI settings defaults | ✅ Done (values fixed; canonical object not created — see NEW-6) |
| PR 3 | Add hard `CloudAiGate` | ⚠️ Partial — providers have inline checks but no unified gate |
| PR 4 | Add runtime fallback executor | ❌ Not done (except SmartReceiptAssist) |
| PR 5 | Make local/cloud modes explicit | ✅ Done — ON_DEVICE is strict local-only |
| PR 6 | Standardize redaction | ✅ Done — all cloud providers sanitize internally |
| PR 7 | Add strict validators for all AI outputs | ⚠️ Partial — bounded confidence exists but not used everywhere |
| PR 8 | Add route and privacy tests | ❓ Not verified in this review (no test files reviewed) |

---

## Coverage

- **Requirements met**: Yes — the core architecture (`UseCase → AI service interface → Hybrid/Smart provider → Router/Policy → Cloud / On-device / No-op`) is sound and most critical bugs from the analysis have been fixed.
- **Testing adequate**: Not verified — test files were not in scope of this review. The analysis's PR 8 (route/privacy test matrix) remains unchecked.
- **Remaining risk areas**:
  1. Runtime fallback missing in 6 of 7 hybrid services (risk: reliability)
  2. Three cloud providers without `allowCloudAi`/PrivacyGate (risk: privacy)
  3. Unbounded AI output validation in receipt and dedupe (risk: data integrity)

---

## Sources Reviewed

All files under:
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/` (all `Cloud*Service`, `Hybrid*Service`, `Smart*`, `NoOp*`, `OnDevice*`, `internal/`)
- `app/src/main/java/com/yourname/expensetracker/domain/ai/model/` (AiModels.kt, CaptureAssistModels.kt, ReceiptItemCategorizationModels.kt, etc.)
- `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/` (AiPolicy.kt, AiPolicyImpl.kt, DefaultAiCapabilityRouter.kt)
- `app/src/main/java/com/yourname/expensetracker/domain/ai/service/` (all interfaces)
- `app/src/main/java/com/yourname/expensetracker/domain/privacy/` (PrivacyGate.kt, PrivacyCapability.kt, CloudAiPrivacyGate.kt, CompositePrivacyGate.kt, RedactionSanitizer.kt)
- `app/src/main/java/com/yourname/expensetracker/domain/debug/AiRuntimeDiagnostics.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/di/AiModule.kt`
- `app/src/main/java/com/yourname/expensetracker/di/PrivacyModule.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
