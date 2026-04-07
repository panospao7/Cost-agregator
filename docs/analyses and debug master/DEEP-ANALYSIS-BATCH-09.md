# Deep Analysis — Batch 09: AI Services - Cloud & OnDevice (@reviewer)

## Scope
- domain/ai/service/CloudReceiptItemCategorizationService.kt
- domain/ai/service/CloudReviewExplanationService.kt
- domain/ai/service/CloudWarrantyExtractionService.kt
- domain/ai/service/OnDeviceCategorizationAssistService.kt
- domain/ai/service/OnDeviceDashboardBriefingService.kt
- domain/ai/service/OnDeviceDedupeJudgeService.kt
- domain/ai/service/OnDeviceQueryInterpretationService.kt
- domain/ai/service/OnDeviceReceiptAssistService.kt
- domain/ai/service/OnDeviceReviewExplanationService.kt
- domain/ai/service/HybridCategorizationAssistService.kt
- domain/ai/service/HybridDashboardBriefingService.kt
- domain/ai/service/HybridDedupeJudgeService.kt
- domain/ai/service/HybridQueryInterpretationService.kt
- domain/ai/service/HybridReceiptAssistService.kt

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `CloudReceiptItemCategorizationService.kt:247-250` | HIGH | Reliability / Performance | The cloud request uses `AppConfig.Ai.ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS` as its output cap. That on-device-sized budget is too small for multi-item cloud JSON payloads and can truncate otherwise valid responses on medium/large receipts. | Add a dedicated cloud max-output constant for receipt-item categorization and use that instead of the on-device token budget. |
| 2 | `CloudReceiptItemCategorizationService.kt:183-190,333-336` | HIGH | Logic / Privacy | When redaction is enabled, item descriptions sent to the model are sanitized/truncated aliases, but the parser later persists the model-returned `description` as if it were the original line item text. This breaks item-to-result mapping and can store redacted placeholders/truncated text in downstream artifacts. | Include a stable item index/ID in the prompt/response contract, validate returned cardinality/order, and restore original descriptions from `input.lineItems` instead of trusting model text. |
| 3 | `CloudWarrantyExtractionService.kt:72-78` | MEDIUM | Configuration / Security | The service hardcodes both the model (`gemini-2.0-flash`) and token budget instead of routing through `AppConfig`/router metadata. Model rotation, rollout control, and telemetry will drift from what the router reports. | Introduce dedicated `AppConfig.Ai` constants for warranty extraction and build the URL/generation config from those shared settings. |
| 4 | `OnDeviceReceiptAssistService.kt:53-58,61-105` | HIGH | Logic | `buildRequest()` always sends a single `TextPart(prompt)` and never uses `imagePath` / `imageMimeType`. The advertised “image analysis mode” is therefore text-only, while higher-level orchestration treats it as a vision-capable path. | Either attach the actual image to the ML Kit request, or remove the image-analysis branch and stop routing this provider as an on-device vision attempt until multimodal input is real. |
| 5 | `OnDeviceReceiptAssistService.kt:142-157` | MEDIUM | JSON Parsing | Numeric parsing is overly lenient: `optDouble` / `optLong` will coerce malformed or stringified values into `NaN` / `0`, so bad model output can silently become bogus totals or epoch dates instead of being rejected. | Parse numeric fields strictly, reject non-finite / non-integer values, and return `null` (or a parse failure) for invalid suggestions. |
| 6 | `OnDeviceQueryInterpretationService.kt:89-90,123-133` | HIGH | Logic | The structured JSON schema has no period field, and `parseStructured()` never reconstructs one. Any AI-produced structured answer for “this month / last week / today” therefore loses its date constraint and can be executed as an all-time query. | Extend the schema with explicit period semantics (or normalized date bounds), parse them into `ExpenseQueryFilters.period`, and reject structured results that omit required temporal constraints. |
| 7 | `OnDeviceQueryInterpretationService.kt:125-133` | HIGH | Logic / Redaction | `parseStructured()` only keeps merchant names and ignores `categoryNames`, `categoryAliasMap`, and `merchantAliasMap`. Under redacted cloud-compatible inputs, hashed aliases can leak into execution, while category-focused queries lose their category ID filter entirely. | Reverse-map aliases back to canonical merchant/category names and resolve categories to stable IDs before returning a structured result. |
| 8 | `HybridReceiptAssistService.kt:24-45` | HIGH | Concurrency / State | `lastUsedImageInput` is mutable singleton state, `usedImageInput(input)` ignores its parameter, and line 30 derives the flag from input/settings rather than the actual request outcome. Concurrent calls can overwrite each other and report the wrong metadata for a receipt. | Remove shared mutable state and carry `usedImageInput` only in the returned `ReceiptAssistSuggestion` / `AiServiceResult`, or key any cache by request/receipt ID. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Description | Suggested Fix |
|---|----------|----------|-------------|---------------|
| 1 | `OnDeviceCategorizationAssistService` + `OnDeviceDashboardBriefingService` + `OnDeviceDedupeJudgeService` + `OnDeviceQueryInterpretationService` + `OnDeviceReceiptAssistService` + `OnDeviceReviewExplanationService` | MEDIUM | All six on-device parsers use naive `first '{'` → `last '}'` extraction. On-device models are the least reliable about wrapping prose around JSON, so a second object, brace in prose, or trailing content can cause the parser to swallow too much text and fail unpredictably. | Replace all six with one shared brace-depth JSON extractor (same style as the cloud services / `CloudJsonParser`) and cover multi-object / fenced / trailing-text responses in tests. |
| 2 | On-device inference lifecycle (`OnDeviceCategorizationAssistService`, `OnDeviceDashboardBriefingService`, `OnDeviceDedupeJudgeService`, `OnDeviceReceiptAssistService`, `OnDeviceReviewExplanationService`) | MEDIUM | Query interpretation has a timeout, but the other on-device services call `generateContent()` without any timeout/cancellation guard. A stalled local model can block the calling coroutine indefinitely and create inconsistent UX across AI features. | Apply a consistent timeout policy to every on-device provider and surface timeout-specific errors the same way the cloud providers do. |
| 3 | Financial query pipeline (`FinancialQueryInterpretationInputBuilder` → `OnDeviceQueryInterpretationService` / `CloudQueryInterpretationService` → `HybridQueryInterpretationService` → `InterpretFinancialQueryUseCase`) | HIGH | The same broken structured contract is reused on both routes: period is not modeled, aliases are not reversed, and category IDs are not restored. Because the hybrid/cloud path delegates to the same parser helper, both routes can return “structured” intents that execute with the wrong merchants/categories and no time window. | Redesign the structured schema to include period + canonical dimension references, centralize alias reversal/ID resolution, and add end-to-end tests for redacted and time-bounded queries on both routes. |
| 4 | Warranty pipeline (`CloudWarrantyExtractionService` → `WarrantyTrackerRepository`) | HIGH | The cloud service returns `null` whenever `hasWarranty=false` or `warrantyMonths<=0`, and the repository only derives a `ReturnWindow` when a `Warranty` entity exists. Any receipt that has a valid return policy but no warranty is dropped before downstream code can use the extracted return window. | Allow partial extraction results for return-policy-only cases, or split warranty extraction from return-policy extraction so `ReturnWindow` creation does not depend on a warranty record. |

## Summary
- Total issues: 12
- Critical: 0, High: 8, Medium: 4, Low: 0
- Files with issues: 10/14

## Key Patterns
- Prompt/response contracts are not consistently bounded: cloud services mostly use stricter JSON extraction, while on-device services still rely on brittle substring parsing.
- Query interpretation is the weakest pipeline in this batch: the schema cannot represent time filters, and redaction aliases are produced upstream but never normalized back downstream.
- Receipt-assist orchestration has drift between advertised capability and actual implementation: the on-device “vision” path is text-only, and the hybrid image-usage flag is tracked with unsafe shared state.
- Configuration is not fully centralized: warranty extraction still hardcodes model/runtime parameters instead of respecting the same config/router surface used elsewhere.
