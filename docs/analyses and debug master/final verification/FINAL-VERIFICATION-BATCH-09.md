# Final Verification — Batch 09: AI Services - Cloud & OnDevice

## Scope
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceDedupeJudgeService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridDedupeJudgeService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `CloudReceiptItemCategorizationService.kt:249` | Medium | Reliability / Configuration | Cloud receipt-item categorization uses `ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS` (300) for `maxOutputTokens`, which can truncate multi-item JSON responses. | B | DOWNGRADED | Add a cloud-specific receipt-item output-token constant and use it here. |
| 2 | `CloudReceiptItemCategorizationService.kt:183-190,333-336` | High | Logic / Privacy | When redaction is enabled, the prompt sends sanitized item descriptions but the parser persists the model-returned `description` verbatim, so aliases/truncated placeholders can be stored as real item text and item/result mapping can drift. | R | CONFIRMED | Include a stable item index/ID in the contract and rebuild persisted descriptions from `input.lineItems`. |
| 3 | `CloudWarrantyExtractionService.kt:72-78` | Low | Configuration Drift | Warranty extraction hardcodes both model name and token budget instead of using shared `AppConfig`/router metadata, so rollout/config changes will not propagate here. | B | DOWNGRADED | Introduce warranty-specific config constants and build the request from them. |
| 4 | `OnDeviceReceiptAssistService.kt:53-58,61-105` | High | Logic | The on-device receipt-assist request always sends only `TextPart(prompt)` and never attaches `imagePath`/`imageMimeType`, so the advertised on-device “image analysis” path is actually text-only. | R | CONFIRMED | Either attach the image to the on-device request or stop routing this provider as a vision attempt. |
| 5 | `OnDeviceReceiptAssistService.kt:142-157` | Medium | Data Validation | `optDouble`/`optLong` are used for parsed numeric suggestions, so malformed model output can silently become `NaN` or `0` instead of being rejected. | R | CONFIRMED | Parse numeric fields strictly and reject non-finite or non-integer values. |
| 6 | `OnDeviceQueryInterpretationService.kt:89-90,123-133` | High | Logic | The structured query schema has no period field, and `parseStructured()` never reconstructs one, so time-bounded queries can be executed as all-time queries. | R | CONFIRMED | Extend the schema with normalized time bounds/period semantics and populate `ExpenseQueryFilters.period`. |
| 7 | `OnDeviceQueryInterpretationService.kt:125-133` | High | Logic / Redaction | `parseStructured()` keeps merchant names only; it never restores category IDs, category aliases, or merchant aliases, so category filters are lost and redacted aliases can leak into execution. | R | CONFIRMED | Reverse-map aliases and resolve stable category IDs before returning a structured intent. |
| 8 | `OnDeviceDedupeJudgeService.kt:92-95` | Medium | Robustness | Raw `Enum.valueOf()` calls for `verdict` and `matchedTargetType` make parsing brittle: casing differences or unknown enum strings cause the whole otherwise-usable suggestion to be discarded. | D | DOWNGRADED | Use case-insensitive safe enum lookup and fall back to `null`/unsupported values instead of hard-failing. |
| 9 | `OnDeviceCategorizationAssistService.kt:123-126` | Medium | Data Validation | Lenient numeric parsing can emit `categoryId = 0` and `confidence = NaN` for malformed model output, producing invalid suggestions instead of rejecting them. | D | CONFIRMED | Require numeric/finite values before constructing `CategoryAssistSuggestion`. |
| 10 | `CloudReviewExplanationService.kt:71` | Low | Observability | A new correlation ID is generated per failed retry attempt instead of once per logical request, which fragments retry tracing in logs. | D | DOWNGRADED | Generate one correlation ID before entering the retry loop and reuse it for all attempts. |
| 11 | `CloudWarrantyExtractionService.kt:254-258` | Low | Data Validation | The parser accepts literal string placeholders like `"null"` for contact fields and does not reject non-finite confidence values, so bad model output can leak through as semantically invalid data. | D | DOWNGRADED | Filter string placeholders and validate confidence with a finite/range check before returning results. |
| 12 | `CloudReceiptItemCategorizationService.kt:189` | Low | Prompt Quality | The prompt hardcodes `€` for every line item, so non-EUR receipts are described with the wrong currency symbol. | D | CONFIRMED | Use the input currency or a currency-neutral format in the prompt. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `OnDeviceDashboardBriefingService.kt:93` | Low | Data Validation | `confidence` is parsed with `optDouble(...).toFloat()` and is never checked for finiteness, so malformed model output can propagate `Float.NaN`. | Parse confidence strictly and drop non-finite values. |
| 2 | `OnDeviceDedupeJudgeService.kt:96-97` | Medium | Data Validation | `matchedTargetId` and `confidence` use lenient `optLong`/`optDouble`, so malformed values can silently become `0`/`NaN` instead of being rejected. | Use strict integer/finite parsing and return `null` for invalid values. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | `R#8 / D#5` | `HybridReceiptAssistService.kt:24-45` | `HybridReceiptAssistService` is not the bound `ReceiptAssistService`; `AiModule` binds `SmartReceiptAssistService`, and no runtime consumers of `HybridReceiptAssistService` were found. This is dead-path code, not an active production bug. |
| 2 | `D#3` | `CloudReviewExplanationService.kt:203-213` | In the actual pipeline, `ExplainPendingReviewUseCase` builds input via `ReviewExplanationInputBuilder`, which already hashes/nulls sensitive fields when redaction is enabled and clamps notification text length before the cloud provider sees it. |
| 3 | `D#4` | `OnDeviceReviewExplanationService.kt:66-74` | The same `ReviewExplanationInputBuilder` already clamps `notificationText`, so the claimed “uncapped prompt” issue is not present in the runtime path; the PII concern is also speculative for an on-device provider. |
| 4 | `D#6` | `CloudWarrantyExtractionService.kt:214-224` | The code does use throwing JSON getters, but they are wrapped by the method-level `try/catch` and degrade to `null`. This is an observability/style concern, not the reported live crash/NPE risk. |
| 5 | `D#12 / D-C5` | `OnDeviceCategorizationAssistService.kt:27-34` | The “stale model handle after OTA/AICore restart” claim is speculative. The code alone does not establish that `Generation.getClient()` becomes invalid or must be recreated. |
| 6 | `D#14` | `CloudReviewExplanationService.kt:152-163` | Other working Gemini request builders in this codebase also omit `role`; there is no codebase evidence that this request shape is rejected here. |
| 7 | `D#17` | `CloudReviewExplanationService.kt:43-46` | The secondary constructor mutates a test-only override after delegation, but nothing in the primary constructor reads `apiKeyOverride` before that assignment. It is not a functional bug. |
| 8 | `D#19` | `OnDeviceQueryInterpretationService.kt:119` | `normalizedQuery` is metadata only in this path and is not used downstream for matching/execution, so the locale-sensitive lowercase call does not currently affect behavior. |
| 9 | `D-C2` | `HybridCategorizationAssistService.kt:23-25; HybridDashboardBriefingService.kt:24-26; HybridDedupeJudgeService.kt:24-26; HybridQueryInterpretationService.kt:23-27; HybridReceiptAssistService.kt:26-28` | `AiSettingsRepositoryImpl.settings()` is a DataStore-backed flow that emits current/default settings; `.first()` is normal snapshot retrieval here, not the reported indefinite race/hang. |
| 10 | `D-C3` | `CloudReviewExplanationService.kt:57-69` | These requests use in-memory string bodies via `toRequestBody(...)`, so reusing the same `Request` across retries is safe in this implementation. |
| 11 | `D-C4` | `AiModule.kt:124-128` | DI already resolves the “two orchestrators” question: `SmartReceiptAssistService` is explicitly bound as the canonical `ReceiptAssistService`. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | On-device JSON parsing | Medium | Robustness | All six on-device AI parsers still use greedy `first '{'` → `last '}'` extraction, so any extra brace/object in model prose can corrupt the parse. | `OnDeviceCategorizationAssistService.kt`, `OnDeviceDashboardBriefingService.kt`, `OnDeviceDedupeJudgeService.kt`, `OnDeviceQueryInterpretationService.kt`, `OnDeviceReceiptAssistService.kt`, `OnDeviceReviewExplanationService.kt` | Replace them with one shared balanced-brace extractor, matching the cloud parser behavior. |
| 2 | On-device inference lifecycle | Medium | Timeout / Resilience | Only query interpretation applies a timeout; the other on-device services can block indefinitely on stalled local generation. | `OnDeviceCategorizationAssistService.kt`, `OnDeviceDashboardBriefingService.kt`, `OnDeviceDedupeJudgeService.kt`, `OnDeviceReceiptAssistService.kt`, `OnDeviceReviewExplanationService.kt` | Apply a consistent timeout/cancellation policy to every on-device provider. |
| 3 | Financial query interpretation pipeline | High | Contract / Logic | Cloud and on-device query interpretation share the same broken structured contract, so period/category/alias information can be lost before execution. | `OnDeviceQueryInterpretationService.kt`, `HybridQueryInterpretationService.kt`, `CloudQueryInterpretationService.kt`, `FinancialQueryInterpretationInputBuilder.kt`, `InterpretFinancialQueryUseCase.kt` | Redesign the structured schema to carry time bounds and canonical dimension references, then centralize alias reversal/ID resolution. |
| 4 | Warranty extraction → return-window creation | High | Logic | Return-policy-only receipts are dropped because the cloud extractor returns `null` when no valid warranty exists, and the repository only creates `ReturnWindow` objects when a `Warranty` was produced. | `CloudWarrantyExtractionService.kt`, `WarrantyTrackerRepository.kt` | Allow partial extraction results for return-policy-only cases, or split warranty extraction from return-policy extraction. |
| 5 | Warranty extraction → return-window mapping | Medium | Logic | Even when AI successfully extracts `returnDays`/`returnConditions`, the repository ignores them and recreates merchant-default return windows instead, making the extracted return-policy fields effectively dead code. | `CloudWarrantyExtractionService.kt`, `WarrantyTrackerRepository.kt` | Propagate extracted return-policy fields alongside warranty data and use them when building `ReturnWindow` entities. |

## Summary
- Total verified issues: 27
- Confirmed: 16 (Critical: 0, High: 6, Medium: 6, Low: 4)
- False positives: 11
- Missed issues found: 3
- Files affected: 10/14

## Key Patterns
- The biggest real problems are contract mismatches: prompts and parsers do not preserve enough structured identity/time information across provider boundaries.
- On-device providers are materially less robust than cloud ones: weaker JSON extraction and missing timeouts create inconsistent failure modes.
- Several debugger findings were overstated because upstream builders/DI wiring already constrain runtime behavior; validation has to follow the actual call path, not just the provider method in isolation.
- The warranty pipeline extracts more return-policy data than it actually uses, so downstream behavior is still partly hardcoded even when AI extraction succeeds.
