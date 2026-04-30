# AI Integration Analysis — `master-refactor`

## Executive verdict

The AI layer has a good overall shape:

`UseCase → AI service interface → Hybrid/Smart provider → Router/Policy → Cloud / On-device / No-op`

But the most important risks are:

1. **cloud fallback can happen in places a user may expect local-only**
2. **some cloud providers rely too much on callers/router for privacy gating**
3. **receipt AI has a serious metadata method bug that can trigger real AI work**
4. **runtime cloud failures do not consistently fall back to on-device**
5. **AI output validation is uneven across providers**

The highest-risk area is not the existence of cloud AI itself; it is that **routing, privacy, and validation rules are duplicated across multiple layers instead of enforced by one hard gate**.

---

## AI architecture map

### Core model

Main domain files:

- `AiModels.kt`
- `AiPolicy.kt`
- `AiPolicyImpl.kt`
- `DefaultAiCapabilityRouter.kt`
- `AiSettingsRepository.kt`
- `AiSettingsRepositoryImpl.kt`

Core concepts:

- `AiCapability`
  - dashboard briefing
  - review explanation
  - query interpretation
  - receipt extraction
  - warranty extraction
  - categorization fallback
  - dedupe judge
  - notification parse
  - review prioritization
  - semantic dedupe
  - receipt item categorization

- `AiMode`
  - `ON_DEVICE`
  - `CLOUD`
  - `AUTO`

- `AiRoute`
  - `ON_DEVICE`
  - `CLOUD`
  - `DETERMINISTIC_FALLBACK`
  - `DISABLED`

### DI bindings

`AiModule.kt` binds most app-facing AI interfaces to hybrid/smart services:

- `DashboardBriefingService → HybridDashboardBriefingService`
- `ReviewExplanationService → HybridReviewExplanationService`
- `ReceiptAssistService → SmartReceiptAssistService`
- `CategorizationAssistService → HybridCategorizationAssistService`
- `DedupeJudgeService → HybridDedupeJudgeService`
- `QueryInterpretationService → HybridQueryInterpretationService`
- `ReceiptItemCategorizationService → HybridReceiptItemCategorizationService`

This is a good pattern because normal app code should depend on interfaces, not cloud providers directly.

---

# Critical / high-priority findings

## 1. `SmartReceiptAssistService.usedImageInput()` can execute the full AI pipeline

In `SmartReceiptAssistService`:

```kotlin
override fun usedImageInput(input: ReceiptAssistInput): Boolean =
    runBlocking { executeWithFallback(input).actualUsedImageInput() }
```

This is dangerous.

A method named `usedImageInput()` sounds like a cheap metadata check, but it can:

- block the calling thread
- call the router
- call cloud AI
- upload receipt data/image if allowed
- retry multiple providers
- perform duplicate expensive work if `suggest()` is called afterwards

This is the most concrete bug I saw.

### Impact

A UI or analytics call asking “did this use an image?” can accidentally trigger the entire receipt AI workflow.

### Fix

Make `usedImageInput()` pure and non-side-effecting.

Recommended:

```kotlin
override fun usedImageInput(input: ReceiptAssistInput): Boolean = false
```

or:

```kotlin
override fun usedImageInput(input: ReceiptAssistInput): Boolean =
    input.isImageAnalysisMode &&
    input.imagePath != null &&
    input.imageMimeType != null &&
    !input.redactBeforeCloud
```

But the truly correct answer should come from `ReceiptAssistSuggestion.usedImageInput` after `suggest()` completes.

Severity: **Critical**

---

## 2. AI settings defaults are inconsistent

`AiSettings` default model says:

- `receiptAssistEnabled = true`
- `receiptImageCloudEnabled = true`
- `warrantyExtractionEnabled = true`

But `AiSettingsRepositoryImpl.toAiSettings()` reads missing preferences as:

- `receiptAssistEnabled = false`
- `receiptImageCloudEnabled = false`
- `warrantyExtractionEnabled = true`

So fresh app behavior and direct `AiSettings()` behavior differ.

### Impact

Tests, constructors, previews, or fallback paths using `AiSettings()` can behave differently from a real fresh install.

This can cause:

- AI receipt assist enabled in tests but disabled in app
- image cloud enabled in direct model defaults but disabled in stored settings
- route decisions that are hard to reproduce

### Fix

Create one canonical default:

```kotlin
object DefaultAiSettings {
    val value = AiSettings(...)
}
```

Use it in:

- `AiSettings`
- `AiSettingsRepositoryImpl`
- tests
- previews
- migrations/default seeding

Severity: **High**

---

## 3. Router does not consider API-key availability

`DefaultAiCapabilityRouter.canUseCloud()` checks:

- policy
- network
- Wi-Fi-only setting

But it does **not** check whether a Gemini API key exists.

Cloud services later check `SecureKeyStorage.getGeminiKey()` and fail if missing.

### Impact

Hybrid services can select `AiRoute.CLOUD`, then the cloud provider returns disabled/failure because no key exists.

For simple hybrid services, there is no second-stage fallback after that.

Example pattern:

```kotlin
when (router.decide(...).route) {
    CLOUD -> cloudService.call(...)
    ON_DEVICE -> onDeviceService.call(...)
    FALLBACK/DISABLED -> noOp.call(...)
}
```

So if router chooses cloud but cloud fails due to missing key, on-device may never run.

### Fix

Add API-key availability to environment/policy:

```kotlin
AiEnvironmentMonitor.hasCloudApiKey()
```

Then router cloud availability should require:

- `allowCloudAi`
- capability enabled
- network available
- Wi-Fi rule satisfied
- API key present

Severity: **High**

---

## 4. Runtime fallback is inconsistent

`SmartReceiptAssistService` has a multi-attempt fallback chain:

1. cloud vision
2. on-device vision
3. cloud text
4. on-device text
5. deterministic fallback

But other hybrid services usually do only one routed call.

Examples:

- `HybridCategorizationAssistService`
- `HybridDedupeJudgeService`
- `HybridReviewExplanationService`
- likely other `Hybrid*Service`

If router selects cloud and the cloud call fails at runtime, these do not necessarily try on-device.

### Impact

Cloud outage, timeout, parse error, missing key, SSL failure, or rate limit can make the feature fail even when on-device or deterministic fallback exists.

### Fix

Introduce a reusable `HybridExecutor`:

```kotlin
route primary provider
if runtime failure:
    try allowed secondary provider
if still failure:
    deterministic fallback
```

Each capability should declare:

- primary order
- fallback order
- failure classes that allow fallback
- failure classes that should stop immediately

Severity: **High**

---

## 5. Cloud providers are not all hard-gated internally

The router/policy layer controls normal route decisions, but several cloud provider classes are injectable concrete services and mostly check only the API key.

For example:

- `CloudCategorizationAssistService`
- `CloudDedupeJudgeService`
- `CloudReceiptAssistService`

`CloudReceiptAssistService` reads settings for `receiptImageCloudEnabled`, but the actual redaction decision comes from `input.redactBeforeCloud`.

### Impact

If any caller injects or constructs a cloud service directly, it can bypass:

- `allowCloudAi`
- capability enabled flags
- Wi-Fi-only rule
- redaction policy
- route decision logging

DI currently binds interfaces to hybrid/smart providers, which helps, but it is not a hard privacy boundary.

### Fix

Add a mandatory cloud gate used by every cloud provider:

```kotlin
CloudAiGate.requireAllowed(capability, payloadKind)
```

It should check:

- global AI enabled
- cloud enabled
- capability enabled
- network/Wi-Fi
- API key
- redaction requirement
- image upload permission

Cloud services should not rely only on the router.

Severity: **High / privacy**

---

## 6. Receipt image upload depends on `input.redactBeforeCloud`

`CloudReceiptAssistService.buildImageInlineData()` suppresses image upload if `input.redactBeforeCloud` is true.

That is good, but the provider does not recompute this from current settings/policy. It trusts the input.

### Impact

If a caller builds `ReceiptAssistInput` incorrectly or with stale settings, an image upload may happen even when current settings require redaction.

### Fix

Inside cloud provider:

```kotlin
val effectiveRedact = aiPolicy.shouldRedact(currentSettings, RECEIPT_EXTRACTION)
```

Then suppress image upload using `effectiveRedact`, not only input data.

Severity: **High / privacy**

---

## 7. “On-device preferred” can still cloud-fallback

Router behavior allows fallback from on-device to cloud when on-device is unavailable.

`SmartReceiptAssistService` also builds a fallback chain that can try cloud after on-device if cloud is viable.

This may be intended technically, but UX-wise it is risky.

### Impact

A user choosing “on-device” may reasonably interpret that as “never send to cloud.”

Current behavior seems closer to:

- “prefer on-device, but cloud may be used if local fails”

### Fix

Separate modes:

- `LOCAL_ONLY`
- `CLOUD_ONLY`
- `AUTO`
- `PREFER_LOCAL`
- `PREFER_CLOUD`

Or keep `ON_DEVICE` as strict local-only and add another mode for “prefer local.”

Severity: **High / privacy expectation**

---

## 8. Cloud dedupe result is not validated against candidate set

`CloudDedupeJudgeService` asks the model to choose from a bounded candidate set. But the parser accepts:

- `matchedTargetType`
- `matchedTargetId`

without verifying the returned pair actually exists in `input.candidates`.

### Impact

The AI can hallucinate a target ID and the app may treat an unrelated transaction as a duplicate.

### Fix

After parsing, validate:

```kotlin
if verdict == LIKELY_DUPLICATE:
    matched target type/id must exist in candidates
else:
    matched target should be null
```

Also reject impossible combinations:

- duplicate verdict with no matched target
- distinct verdict with matched target
- confidence outside 0–1

Severity: **Critical if AI dedupe can affect approval/deletion**

---

## 9. AI numeric/confidence validation is uneven

Some providers use stricter parsing. For example, categorization validates category IDs against allowed categories and uses bounded confidence helpers.

But receipt and dedupe parsing are weaker:

- receipt confidence uses finite numeric parsing, not bounded 0–1
- receipt total can be negative or unrealistic
- receipt date is only whole-number validation, not range validation
- dedupe confidence is finite numeric, not bounded 0–1

### Impact

AI can return:

- confidence `999`
- total `-20`
- date timestamp in seconds instead of milliseconds
- nonsense tax amount
- duplicate confidence outside normal range

### Fix

Create one shared validation layer:

```kotlin
AiOutputValidators.boundedConfidence()
AiOutputValidators.positiveMoney()
AiOutputValidators.epochMillisInReasonableRange()
AiOutputValidators.allowedCandidateId()
```

Severity: **High**

---

## 10. Cloud redaction appears uneven across providers

`CloudReceiptAssistService` has clear redaction/sanitization for:

- merchant
- OCR text
- line items

But `CloudCategorizationAssistService` prompt includes:

- merchant
- amount/currency
- supporting text
- recent merchant history

`CloudDedupeJudgeService` prompt includes:

- subject merchant
- candidate merchant
- source label
- text preview

I did not see the same sanitizer pattern in those two providers.

### Impact

Even when `redactBeforeCloud = true`, some cloud prompts may still include sensitive merchant or notification text unless input builders sanitize before calling.

### Fix

Do not rely on builders. Each cloud provider should apply `CloudPiiSanitizer` or a shared `RedactionSanitizer` internally.

Severity: **High / privacy**

---

## 11. Disabled route decisions are not recorded in diagnostics

`DefaultAiCapabilityRouter.decide()` records diagnostics after normal decision, but early returns happen before recording:

- global AI disabled
- capability disabled

### Impact

Diagnostics can under-report disabled decisions, making it harder to debug “why AI did nothing.”

### Fix

Wrap every return through a helper:

```kotlin
return recorded(capability, decision)
```

Severity: **Medium**

---

# Strong parts worth keeping

## 1. Centralized router/policy concept

The existence of:

- `AiPolicy`
- `DefaultAiCapabilityRouter`
- `AiSettingsRepository`

is the right direction.

## 2. Secure API-key storage

Cloud providers use `SecureKeyStorage.getGeminiKey()` instead of compiled `BuildConfig` keys. Good.

## 3. Cloud image suppression when redaction is required

Receipt image upload is suppressed when `input.redactBeforeCloud` is true. This is an important privacy guard; it just needs to be enforced from current policy too.

## 4. No cloud for sensitive on-device-only capabilities

Policy says no cloud for:

- notification parse
- review prioritization
- semantic dedupe

That is a good privacy default.

## 5. Category AI validates against allowed categories

`CloudCategorizationAssistService` does not blindly accept invented categories. It maps back to known candidate categories.

Keep this pattern and apply similar bounded validation elsewhere.

---

# Recommended fix order

## PR 1 — Fix side-effecting `usedImageInput()`

Change `SmartReceiptAssistService.usedImageInput()` immediately.

This is the most concrete bug.

## PR 2 — Canonicalize AI settings defaults

Create a single default source and use it everywhere.

## PR 3 — Add a hard `CloudAiGate`

Every cloud provider must call it before building/sending requests.

Gate should check:

- settings
- capability
- network
- Wi-Fi
- API key
- redaction
- image-upload permission

## PR 4 — Add runtime fallback executor

Do not rely only on route selection.

If cloud fails due to missing key, timeout, parse error, SSL, or HTTP 429/5xx, try permitted fallback provider.

## PR 5 — Make local/cloud modes explicit

Decide whether `ON_DEVICE` means:

- strict local-only, or
- prefer local with cloud fallback

I recommend strict local-only.

## PR 6 — Standardize redaction

Every cloud provider should sanitize inside the provider, not only in input builders.

## PR 7 — Add strict validators for all AI outputs

Especially:

- receipt totals
- tax
- timestamps
- confidence
- dedupe matched target IDs
- duplicate verdict consistency

## PR 8 — Add route and privacy tests

Test matrix:

1. cloud disabled + API key present → no cloud call
2. cloud enabled + no key → router does not choose cloud
3. Wi-Fi-only + mobile data → no cloud call
4. redaction enabled + receipt image present → no image upload
5. local-only mode + cloud available → no cloud fallback
6. cloud timeout → on-device fallback
7. AI dedupe returns fake target ID → reject
8. AI receipt returns confidence 999 → reject
9. `usedImageInput()` does not call providers

---

# Overall priority

If you only fix three things:

1. **Fix `SmartReceiptAssistService.usedImageInput()`**
2. **Add a hard cloud privacy gate inside every cloud provider**
3. **Add runtime fallback from failed cloud calls to on-device/no-op**

Those three will remove the biggest privacy, cost, and reliability risks.

---

# Sources reviewed

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/docs/architecture/CODEBASE_SEGMENTS.md

- `AiModule.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/di/AiModule.kt

- `AiModels.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt

- `AiPolicy.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicy.kt

- `AiPolicyImpl.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt

- `DefaultAiCapabilityRouter.kt`  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt

- `AiSettingsRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt

- `SmartReceiptAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt

- `CloudReceiptAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `HybridCategorizationAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt

- `CloudCategorizationAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt

- `HybridDedupeJudgeService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDedupeJudgeService.kt

- `CloudDedupeJudgeService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt

- `AiChatRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AiChatRepositoryImpl.kt