# AI Integration Remedy Plan — `master-refactor`

## Goal

Make AI behavior predictable, privacy-safe, cheap to run, and easy to debug across local, cloud, and hybrid services.

Core principle:

> No cloud AI call should happen unless the latest settings, capability policy, network state, API-key state, and redaction rules all allow it at the moment of request.

---

## Priority order

1. Fix side-effecting `usedImageInput()`
2. Canonicalize AI settings defaults
3. Add hard cloud gate
4. Make local/cloud mode semantics explicit
5. Add reusable hybrid fallback executor
6. Standardize redaction
7. Standardize AI output validation
8. Add route/privacy/cost diagnostics
9. Add regression tests

---

## PR 1 — Fix `SmartReceiptAssistService.usedImageInput()`

### Problem

`usedImageInput()` currently calls the full receipt AI fallback pipeline through `runBlocking`.

That means a cheap metadata method can accidentally:

- block the UI thread
- call cloud AI
- upload receipt data/image
- consume API quota
- run duplicate AI work if `suggest()` is called later

### Files

- `SmartReceiptAssistService.kt`
- `ReceiptAssistService` interface, if needed

### Fix

Make `usedImageInput()` pure and side-effect-free.

Recommended behavior:

- return only what is knowable from input, or
- deprecate/remove the method and use `ReceiptAssistSuggestion.usedImageInput` after `suggest()` finishes.

Do **not** call `executeWithFallback()` from this method.

### Acceptance criteria

- Calling `usedImageInput()` never calls cloud provider.
- Calling `usedImageInput()` never calls on-device provider.
- Calling `usedImageInput()` never blocks on suspend work.
- Existing UI still gets actual image-use status from final suggestion metadata.

Severity: **Critical**

---

## PR 2 — Canonicalize AI settings defaults

### Problem

`AiSettings` model defaults differ from `AiSettingsRepositoryImpl` DataStore fallback defaults.

Example:

- `AiSettings.receiptAssistEnabled = true`
- DataStore missing key maps `receiptAssistEnabled = false`

Same issue exists for receipt image cloud default.

### Files

- `AiModels.kt`
- `AiSettingsRepositoryImpl.kt`
- AI settings tests/previews

### Fix

Create one canonical default source.

Example concept:

- `DefaultAiSettings.value`
- `AiSettings()` should align with it
- DataStore fallback should read from it
- tests should use it

### Decision needed

Choose intended fresh-install behavior:

Option A — conservative privacy default:

- receipt assist: off
- receipt image cloud: off
- cloud AI: off
- redact before cloud: on

Option B — assisted default:

- receipt assist: on
- image cloud still off unless user explicitly enables cloud

Recommended: **Option A or hybrid of A**. Cloud/image upload should never be enabled by default.

### Acceptance criteria

- `AiSettings()` equals DataStore empty-preferences result.
- Fresh install behavior is documented.
- Tests no longer rely on different defaults than real app.

Severity: **High**

---

## PR 3 — Add hard `CloudAiGate`

### Problem

The router decides whether cloud is allowed, but concrete cloud providers can still be injected directly and mostly rely on API-key checks plus caller-provided input flags.

That is not a hard privacy boundary.

### New component

Create a mandatory cloud gate used inside every cloud provider.

Suggested responsibility:

- read latest `AiSettings`
- verify global AI enabled
- verify cloud AI enabled
- verify capability enabled
- verify policy allows this capability in cloud
- verify API key exists
- verify network available
- verify Wi-Fi-only rule
- verify image upload permission
- verify redaction requirement
- produce a reason when denied

### Files

Add:

- `CloudAiGate.kt`
- maybe `CloudAiGateImpl.kt`
- maybe extend `AiEnvironmentMonitor`

Update:

- `CloudReceiptAssistService.kt`
- `CloudCategorizationAssistService.kt`
- `CloudDedupeJudgeService.kt`
- cloud dashboard/review/query/warranty providers

### Acceptance criteria

- Every cloud provider calls the gate before building prompt/request payload.
- No cloud provider sends a request just because an API key exists.
- Disabled cloud AI blocks all cloud providers, even if directly injected.
- Missing API key is reported as a gate denial, not a late provider failure.

Severity: **High / privacy**

---

## PR 4 — Router must know API-key availability

### Problem

`DefaultAiCapabilityRouter.canUseCloud()` checks policy, network, and Wi-Fi, but not whether a Gemini key exists.

So it can choose `AiRoute.CLOUD`, then the provider fails because no key is present.

### Files

- `DefaultAiCapabilityRouter.kt`
- `AiEnvironmentMonitor`
- implementation of environment monitor
- `SecureKeyStorage`

### Fix

Add cloud credential availability to routing environment.

Router cloud availability should require:

- policy allows cloud
- network available
- Wi-Fi rule satisfied
- API key present

### Acceptance criteria

- No API key → router does not choose `CLOUD`.
- Route reason explains missing key.
- Hybrid services can choose on-device/no-op instead of failing cloud first.

Severity: **High**

---

## PR 5 — Make AI modes explicit

### Problem

Current `AiMode.ON_DEVICE` can still cloud-fallback if on-device is unavailable.

That may surprise users. “On-device” usually means “do not send this to cloud.”

### Files

- `AiModels.kt`
- `DefaultAiCapabilityRouter.kt`
- settings UI
- settings copy/text

### Fix

Either:

Option A — make existing modes strict:

- `ON_DEVICE` = local only
- `CLOUD` = cloud only
- `AUTO` = allowed fallback

Option B — add clearer modes:

- `LOCAL_ONLY`
- `CLOUD_ONLY`
- `AUTO`
- `PREFER_LOCAL`
- `PREFER_CLOUD`

Recommended: **Option A** for simpler UX.

### Acceptance criteria

- Local-only never calls cloud.
- Cloud-only never calls on-device unless user selected Auto.
- Auto can fallback according to capability policy.
- Settings UI text clearly states behavior.

Severity: **High / privacy expectation**

---

## PR 6 — Add reusable hybrid fallback executor

### Problem

`SmartReceiptAssistService` has multi-step fallback, but many `Hybrid*Service` classes do a single routed call.

If cloud is selected and fails due to timeout, rate limit, parse error, or missing key, on-device may not run.

### Add

A shared executor that handles:

- route decision
- primary provider call
- runtime failure classification
- permitted fallback provider
- deterministic fallback
- diagnostics

### Applies to

- `HybridCategorizationAssistService`
- `HybridDedupeJudgeService`
- `HybridReviewExplanationService`
- `HybridDashboardBriefingService`
- `HybridQueryInterpretationService`
- `HybridReceiptItemCategorizationService`
- maybe receipt service after simplification

### Fallback rules

Fallback only when allowed by mode and policy.

Examples:

- `AUTO`: cloud failure may fallback to on-device
- `LOCAL_ONLY`: on-device failure may fallback only to deterministic/no-op
- `CLOUD_ONLY`: cloud failure may fallback only to deterministic/no-op
- disabled capability: no provider call

### Acceptance criteria

- Cloud timeout can fallback to on-device in Auto.
- Cloud 429/5xx can fallback to on-device/no-op.
- Parse failure can fallback.
- Local-only never cloud-fallbacks.
- Diagnostics record every attempt.

Severity: **High**

---

## PR 7 — Standardize redaction inside every cloud provider

### Problem

Some providers sanitize carefully, especially receipt prompts, but others may include merchant/supporting text/history without the same internal sanitizer guarantees.

Relying on input builders is fragile.

### Files

- `CloudPiiSanitizer`
- `CloudReceiptAssistService`
- `CloudCategorizationAssistService`
- `CloudDedupeJudgeService`
- cloud dashboard/review/query providers

### Fix

Every cloud provider should sanitize internally before prompt construction.

Rules:

- provider reads effective redaction policy from current settings/gate
- if redaction is required, sanitize prompt inputs inside provider
- receipt image upload is blocked when redaction is required
- raw notification text should never go to cloud unless policy explicitly allows it

### Acceptance criteria

- `redactBeforeCloud = true` means no raw merchant/OCR/notification text in cloud prompt.
- Receipt images are not uploaded when redaction is required.
- Sanitization is provider-enforced, not caller-dependent.

Severity: **High / privacy**

---

## PR 8 — Standardize AI output validation

### Problem

AI JSON parsing is uneven. Some paths validate IDs/confidence well, others allow impossible values.

### Add

Shared validator utilities for:

- confidence: finite, 0.0–1.0
- money: finite, non-negative, reasonable max
- timestamps: epoch millis, plausible date range
- category IDs: must exist in candidate list
- dedupe match IDs: must exist in candidate list
- verdict consistency
- bounded notes/explanations length

### Highest priority validation

#### Dedupe judge

If AI says duplicate:

- matched target type/id must exist in input candidates
- confidence must be bounded
- duplicate verdict must not have null target
- distinct verdict should not have matched target

#### Receipt extraction

- total cannot be negative
- tax cannot be negative
- tax should not exceed total
- confidence must be 0–1
- date must be plausible epoch millis
- reject seconds-vs-millis timestamps

#### Categorization

Keep existing category allowlist validation and reuse the pattern elsewhere.

### Acceptance criteria

- AI hallucinated dedupe target is rejected.
- Confidence `999` is rejected or clamped by policy.
- Negative receipt total is rejected.
- Invalid dates are rejected.
- Rejected AI output falls back safely instead of mutating app state.

Severity: **High**

---

## PR 9 — AI result application boundary

### Problem

AI suggestions should not directly become state changes unless validated and user/policy approved.

### Fix

Create a consistent “AI suggestion → validated suggestion → applied action” boundary.

Rules:

- AI returns suggestions, not commands.
- Suggestions are validated.
- High-impact actions require user confirmation unless explicitly enabled.
- Quick-save / quick-approve must have stricter confidence and audit trail.

### Applies to

- receipt assist
- receipt item categorization
- dedupe judge
- review explanation
- categorization fallback
- warranty extraction

### Acceptance criteria

- Low-confidence AI cannot auto-apply.
- AI dedupe cannot delete/merge without validated candidate.
- Manual user edits are never overwritten silently by AI.

Severity: **High**

---

## PR 10 — Diagnostics and observability

### Problem

Disabled route decisions may not always be recorded. Runtime fallback attempts are also not consistently visible across providers.

### Files

- `DefaultAiCapabilityRouter.kt`
- `AiRuntimeDiagnostics`
- hybrid services
- receipt attempt metadata

### Fix

Record:

- disabled route decisions
- selected route
- gate denial reason
- provider attempts
- fallback attempt reason
- final provider used
- whether image input was used
- whether redaction was applied
- error class, not raw payload

Never log:

- raw OCR text
- receipt image path if sensitive
- notification text
- API key
- full prompt

### Acceptance criteria

- User/developer can answer: “Why didn’t AI run?”
- User/developer can answer: “Did this go to cloud?”
- User/developer can answer: “Was image data uploaded?”
- Logs do not contain sensitive raw content.

Severity: **Medium / debugging**

---

## PR 11 — Test matrix

### Unit tests

Add fake/spying providers:

- fake cloud provider
- fake on-device provider
- fake no-op provider
- fake cloud gate
- fake environment monitor
- fake secure key storage

### Required test cases

1. AI disabled → no provider call.
2. Capability disabled → no provider call.
3. Cloud disabled + API key present → no cloud call.
4. Cloud enabled + no API key → router does not choose cloud.
5. Wi-Fi-only + mobile data → no cloud call.
6. Local-only + cloud available → no cloud call.
7. Auto + cloud timeout → on-device fallback.
8. Auto + cloud parse error → on-device/no-op fallback.
9. Redaction enabled + receipt image present → no image upload.
10. Receipt image cloud disabled → no image upload.
11. `usedImageInput()` → no provider call.
12. AI dedupe returns fake target ID → reject.
13. AI receipt returns confidence `999` → reject.
14. AI receipt returns negative total → reject.
15. Cloud prompt sanitizer removes raw OCR/notification text.
16. Route diagnostics records disabled decisions.

### Integration tests

Use in-memory/fake stack to verify:

- receipt assist cloud/local/no-op behavior
- categorization fallback
- dedupe judge
- dashboard briefing
- review explanation
- receipt item categorization

### Acceptance criteria

- Route behavior is deterministic under every setting combination.
- Privacy-sensitive tests fail if any cloud provider bypasses gate.

---

## Recommended implementation sequence

### Sprint 1 — Safety hotfixes

1. PR 1: `usedImageInput()` side-effect fix
2. PR 2: canonical defaults
3. PR 4: router API-key availability
4. minimal tests for these three

### Sprint 2 — Privacy hardening

5. PR 3: `CloudAiGate`
6. PR 7: provider-side redaction
7. tests for no-cloud/no-image/no-raw-text cases

### Sprint 3 — Reliability

8. PR 5: strict mode semantics
9. PR 6: hybrid fallback executor
10. tests for cloud failure → allowed fallback

### Sprint 4 — Data correctness

11. PR 8: output validators
12. PR 9: result application boundary
13. tests for hallucinated/invalid outputs

### Sprint 5 — Observability

14. PR 10: diagnostics
15. PR 11: full route matrix tests

---

## Most valuable quick patch

If you want the smallest immediate patch:

1. Change `SmartReceiptAssistService.usedImageInput()` so it never calls `executeWithFallback()`.
2. Make `DefaultAiCapabilityRouter.canUseCloud()` return false when API key is missing.
3. Add a cloud-provider guard that checks `allowCloudAi` before request creation.
4. Make `AiMode.ON_DEVICE` strict local-only.
5. Reject AI dedupe matches where the returned target is not in the candidate list.

That gives the biggest privacy/cost/reliability gain quickly.

---

## Sources

- `SmartReceiptAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt

- `AiModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/model/AiModels.kt

- `AiSettingsRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt

- `DefaultAiCapabilityRouter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt

- `CloudReceiptAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt

- `CloudDedupeJudgeService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt

- `CloudCategorizationAssistService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt