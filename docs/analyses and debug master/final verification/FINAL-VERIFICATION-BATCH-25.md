# Final Verification — Batch 25: AI Providers — Cloud & Hybrid

## Scope
- `com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridDedupeJudgeService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt`
- `com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt`
- `com/yourname/expensetracker/data/ai/provider/DefaultAiEnvironmentMonitor.kt`
- `com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt`
- `com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt:64-180` | High | Logic | The “smart” receipt chain still commits to one router decision up front. In practice it retries only within the chosen family, so cloud failures do not fall through to on-device and vice versa. | B | CONFIRMED | Build an ordered attempt plan and advance to the next eligible provider after failure or low confidence. |
| 2 | `com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt:24-45` | Medium | Thread safety | `lastUsedImageInput` is singleton mutable state shared across requests, and `usedImageInput(input)` ignores its argument. Concurrent receipt scans can leak metadata across calls. | B | CONFIRMED | Remove shared mutable state and return image-usage metadata per request/result. |
| 3 | `com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt:183-190` | Medium | Functional | Item prompts hardcode `€` for every line item, so non-EUR receipts are sent with the wrong currency symbol. | B | CONFIRMED | Format amounts with `input.currency` or omit hardcoded symbols. |
| 4 | `com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt:247-250` | Low | Configuration | The cloud request uses `ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS`, coupling unrelated limits and unnecessarily constraining cloud output. | B | CONFIRMED | Add a dedicated cloud output-token constant for receipt-item categorization. |
| 5 | `com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt:27-52`; `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt:75-76,176-180,304-322` | Critical | Privacy | Reported as a redaction bypass because images still upload when `redactBeforeCloud=true`. Actual code separates text redaction from image upload behind the explicit `receiptImageCloudEnabled` setting, so this behavior is intentional rather than a verified bug. | B | FALSE_POSITIVE | No code fix required; optionally clarify UX/help text that text redaction and image upload are controlled separately. |
| 6 | `com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt:41-63,105-115,162-168`; `com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt:149-150,170,183-194` | High | Privacy | When redaction is enabled, cloud categorization still receives raw category labels and recent merchant/category history. Only the primary merchant field is pseudonymized. | B | CONFIRMED | Alias or omit category names and merchant-history context when `redactBeforeCloud=true`. |
| 7 | `com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt:32-81`; `com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt:42-165`; `com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt:72-135` | High | Schema mismatch | The builder prepares merchant/category alias maps, but the parser never remaps them back. It also drops category filters entirely, and the prompt/schema never captures period intent, so structured queries lose key filters. | B | CONFIRMED | Extend the schema/parser for categories and period, and remap merchant/category aliases before constructing `FinancialQueryIntent`. |
| 8 | `com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt:250-259`; `com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt:152-153,171,184-205` | Medium | Data loss | `returnDays` and `returnConditions` are extracted from cloud AI, but the repository only persists warranty data and later derives return windows from merchant defaults instead. | B | CONFIRMED | Persist and use extracted return-policy fields before falling back to hardcoded defaults. |
| 9 | `com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt:12-22` | Medium | Privacy | `sanitizeText()` truncates before redaction. If sensitive text is cut at the boundary, a partial PII fragment can survive because the regexes only see the truncated tail. | D | DOWNGRADED | Redact first, then normalize and truncate. Also centralize sanitization to avoid duplicate buggy helpers. |
| 10 | `com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt:8-11,38-42` | Low | Logic | Report claimed exponential backoff is uncapped. The implementation already caps delay with `MAX_RETRY_BACKOFF_MS = 1_500L`. | D | FALSE_POSITIVE | No fix needed. |
| 11 | `com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt:5-6` | Low | Security | Report claimed plaintext correlation IDs in logs are a security defect. The IDs are already short random opaque trace tokens; no concrete vulnerability was verified here. | D | FALSE_POSITIVE | No fix needed. |
| 12 | `com/yourname/expensetracker/data/ai/provider/DefaultAiEnvironmentMonitor.kt:67-97` | Low | Logic | Report claimed model download state is ignored. The monitor already maps `DOWNLOADABLE` to `NOT_INSTALLED` and `DOWNLOADING` to `DOWNLOADING` via `checkStatus()`. | D | FALSE_POSITIVE | No fix needed. |
| 13 | `com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt:68-95,254-259,334-345` | Medium | Routing | In `AiMode.CLOUD`, capabilities that are cloud-disallowed but implemented on-device (for example notification parsing / review prioritization / semantic dedupe) can still be sent straight to deterministic fallback because `chooseCloudPreferred()` only allows on-device fallback for a small hardcoded subset. | D | CONFIRMED | When cloud is unavailable because the capability is cloud-disallowed, prefer on-device if it is implemented and available. |
| 14 | `com/yourname/expensetracker/data/ai/provider/AiProviderModels.kt:—` | Low | Design | Report referenced `AiProviderModels.kt`, but that file does not exist in this repository, so the issue cannot be validated against actual code. | D | FALSE_POSITIVE | No fix needed. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt:30-31,45`; `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt:65-66,304-315` | Medium | Metadata accuracy | Even without concurrency, `HybridReceiptAssistService` can report `usedImageInput=true` when cloud receipt assist actually fell back to text-only, because it precomputes the flag from path/mime presence instead of the real request payload outcome. | Derive image-usage metadata from the returned suggestion / actual request payload, not from a preflight heuristic. |
| 2 | `com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt:469-486`; `com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt:280-293` | Medium | Privacy | Two provider-local sanitizers repeat the same truncate-before-redact mistake as `CloudPiiSanitizer`, so boundary-cut PII fragments can leak in item-categorization and warranty-extraction prompts too. | Fix the order once and reuse a shared sanitizer instead of duplicating local redaction logic. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `R Cross#1 / D#1 / D Cross#C1` | `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt:176-180,304-322` | The codebase intentionally separates text redaction from image upload. Image upload is gated by the dedicated `receiptImageCloudEnabled` user setting, so this is not an accidental bypass of the redaction flag. |
| 2 | `D#11` | `com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt:8-11,38-42` | Backoff already has a hard cap via `MAX_RETRY_BACKOFF_MS`. |
| 3 | `D#12` | `com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt:5-6` | The logged value is an opaque random trace token, not sensitive user data or a predictable session identifier. |
| 4 | `D#13` | `com/yourname/expensetracker/data/ai/provider/DefaultAiEnvironmentMonitor.kt:67-97` | `checkStatus()` already distinguishes available, downloadable, and downloading states; the reported gap is not present. |
| 5 | `D#15` | `com/yourname/expensetracker/data/ai/provider/AiProviderModels.kt:—` | The referenced file does not exist in the repository. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Smart receipt extraction routing | High | Fallback chain | The advertised smart chain does not actually move across provider families after failure; routing is fixed too early. | `data/ai/provider/SmartReceiptAssistService.kt` | Build and execute an ordered attempt plan instead of hard-gating later steps on the first route. |
| 2 | Categorization redaction pipeline | High | Privacy | Redaction stops at the primary merchant field; raw category names and merchant-history context still reach the cloud prompt. | `domain/ai/usecase/CategorizationAssistInputBuilder.kt`, `data/ai/provider/CloudCategorizationAssistService.kt` | Pseudonymize or omit category/history context when cloud redaction is enabled. |
| 3 | Financial query interpretation pipeline | High | Functional | Builder-prepared aliases and filters are not preserved through prompt/schema/parse, so category and time-scope intent are widened or dropped. | `domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`, `data/ai/provider/CloudQueryInterpretationService.kt`, `data/ai/provider/OnDeviceQueryInterpretationService.kt` | Keep schema, parser, and alias restoration in sync with the input builder contract. |
| 4 | Warranty extraction → return-window creation | Medium | Data loss | Return-policy fields are extracted from AI but discarded before the return-window entity is built. | `data/ai/provider/CloudWarrantyExtractionService.kt`, `data/repository/WarrantyTrackerRepository.kt` | Carry extracted return-policy data through persistence and use merchant defaults only as fallback. |
| 5 | Cloud-mode routing for on-device-only capabilities | Medium | Routing | In cloud-preferred mode, some cloud-disallowed capabilities skip viable on-device providers and drop straight to deterministic fallback. | `domain/ai/policy/DefaultAiCapabilityRouter.kt` | Treat “cloud not allowed for this capability” as a signal to try on-device first when implemented and available. |
| 6 | Cloud redaction helpers | Medium | Privacy | Redaction logic is duplicated across helpers and local provider methods, and multiple copies share the same truncate-before-redact flaw. | `data/ai/provider/internal/CloudPiiSanitizer.kt`, `data/ai/provider/CloudReceiptItemCategorizationService.kt`, `data/ai/provider/CloudWarrantyExtractionService.kt` | Centralize sanitization in one tested helper and redact before truncating everywhere. |

## Summary
- Total verified issues: 14
- Confirmed: 9 (Critical: 0, High: 3, Medium: 5, Low: 1)
- False positives: 5
- Missed issues found: 2
- Files affected: 13/30

## Key Patterns
- Routing abstractions over-promise: both smart receipt assist and cloud-preferred routing advertise broader fallback behavior than the code actually executes.
- Redaction is inconsistent and duplicated: one pipeline still leaks raw category/history context, and several sanitizers share the same truncate-before-redact flaw.
- Query interpretation has builder/parser drift: alias maps and intended filters are prepared upstream but discarded downstream.
- Some providers compute richer metadata than the rest of the pipeline preserves, causing silent loss of return-policy data and inaccurate image-usage reporting.
