# Deep Analysis — Batch 25: AI Providers — Cloud & Hybrid (@reviewer)

## Scope

### Existing batch files analyzed
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReviewExplanationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptItemCategorizationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDedupeJudgeService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudRetryPolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudCorrelation.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudJsonParser.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/DefaultAiEnvironmentMonitor.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt` *(requested as `data/ai/provider/DefaultAiCapabilityRouter.kt`; actual file lives here)*

### Requested files not present in the repository
- `data/ai/provider/DefaultAiProvider.kt`
- `data/ai/provider/AiProvider.kt`
- `data/ai/provider/AiProviderModels.kt`
- `data/ai/provider/AiProviderRegistry.kt`
- `data/ai/provider/AiProviderRouter.kt`

### Supporting dependency files checked for cross-component verification
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `data/ai/provider/SmartReceiptAssistService.kt:65-76,79-132,178-180` | HIGH | Logic | The “smart retry chain” is not actually implemented. Routing is decided once up front, then every later attempt is filtered by that same single route, so cloud failures never fall back to on-device and on-device failures never fall back to cloud. The bound production service therefore behaves like “one route + deterministic fallback”, not the documented 5-step chain. | Build an ordered attempt plan from settings/policy (cloud vision → on-device vision → cloud text → on-device text → deterministic fallback) and execute the next allowed provider after a failed or low-confidence attempt instead of hard-gating every later step on the first route decision. |
| 2 | `data/ai/provider/HybridReceiptAssistService.kt:24-45` | MEDIUM | Thread safety / state leakage | `lastUsedImageInput` is mutable singleton state shared across calls. `usedImageInput(input)` ignores its argument and returns the last global value, so concurrent requests can race and one receipt can observe another receipt’s image-usage state. | Remove mutable shared state; return image usage as part of the result only, or track it per request instead of storing it on the singleton service. **[RESOLVED BY A.8]** |
| 3 | `data/ai/provider/CloudReceiptItemCategorizationService.kt:183-190` | MEDIUM | Functional bug | Item lines are always rendered as `€amount` even though the input carries `currency`. Non-EUR receipts are sent to the model with the wrong currency symbol, which can skew categorization and tax reasoning. | Use `input.currency` when formatting item amounts, or avoid hardcoded currency symbols entirely. |
| 4 | `data/ai/provider/CloudReceiptItemCategorizationService.kt:247-250` | LOW | Configuration / capacity | The cloud request uses `AppConfig.Ai.ON_DEVICE_RECEIPT_ITEM_MAX_TOKENS`. That caps a cloud response with an on-device token budget, increasing truncation risk on larger receipts and coupling two unrelated configurations. | Introduce a dedicated cloud max-output-token constant for receipt-item categorization and use that here. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `ReceiptAssistInputBuilder` → `CloudReceiptAssistService` | HIGH | Privacy / PII | `redactBeforeCloud` only sanitizes OCR text. When an image exists, `ReceiptAssistInputBuilder` still keeps `imagePath`/`imageMimeType` (`ReceiptAssistInputBuilder.kt:27-52`) and `CloudReceiptAssistService` uploads the raw receipt image bytes (`CloudReceiptAssistService.kt:176-180,304-322`). That bypasses the user’s redaction setting and sends the full receipt image—including any visible PII—to the cloud. | When redaction is enabled, either disable cloud image upload entirely or add an explicit separate consent toggle for raw image upload. Do not treat text redaction as sufficient when the image remains unredacted. |
| 2 | `CategorizationAssistInputBuilder` → `CloudCategorizationAssistService` | HIGH | Privacy / PII | The categorization fallback path still leaks raw user context when redaction is enabled. `CategorizationAssistInputBuilder` keeps raw category names and recent merchant/category history (`CategorizationAssistInputBuilder.kt:41-63,105-115,162-168`), and `CloudCategorizationAssistService` injects both directly into the cloud prompt (`CloudCategorizationAssistService.kt:149-150,170,183-194`). Custom category labels and prior merchant history can be sensitive. | Add cloud-safe aliases for categories/history (as done in receipt-item categorization) or omit those fields entirely when `redactBeforeCloud=true`. |
| 3 | `FinancialQueryInterpretationInputBuilder` → `CloudQueryInterpretationService` / `OnDeviceQueryInterpretationService` | HIGH | Functional bug | The input builder prepares `merchantAliasMap`/`categoryAliasMap` for redacted prompts, but the parsing path never maps aliases back and does not parse category filters at all. `CloudQueryInterpretationService` delegates to `OnDeviceQueryInterpretationService.parseResponse(...)` (`CloudQueryInterpretationService.kt:149-165`), and `parseStructured(...)` only reads merchants/ownership/amounts (`OnDeviceQueryInterpretationService.kt:115-135`). Category-driven assistant queries therefore lose their category filter, and redacted alias outputs cannot be rehydrated. | Parse `categoryNames`, map category aliases back through `categoryAliasMap`, and remap merchant aliases through `merchantAliasMap` before constructing `FinancialQueryIntent`. Add tests for redacted merchant/category queries. |
| 4 | `CloudWarrantyExtractionService` → `WarrantyTrackerRepository` | MEDIUM | Data loss / integration gap | The cloud warranty extractor asks for and parses `returnDays`/`returnConditions` (`CloudWarrantyExtractionService.kt:177-195,250-259`), but the repository ignores that return-policy output and later derives return windows from hardcoded merchant defaults instead (`WarrantyTrackerRepository.kt:152-172,184-205`). The AI-generated return-policy data is computed and then dropped. | Persist AI-extracted return-policy fields and use them when creating `ReturnWindow`, falling back to merchant defaults only when extraction is absent. |

## Summary
- Total issues: 8
- Critical: 0, High: 4, Medium: 3, Low: 1
- Files with issues: 8/22 existing batch files directly or via verified pipeline dependencies

## Key Patterns
- Privacy controls are inconsistent across pipelines: some cloud paths redact text, but adjacent fields (receipt images, category labels, merchant-history hints) still leak sensitive context.
- Several “hybrid/smart” abstractions do not match their advertised behavior. The routing layer says one thing, while the bound service behavior is effectively narrower.
- Query interpretation has a builder/parser mismatch: redaction aliases are prepared, but the parser does not reconstruct them, and category filters are silently dropped.
- Batch scope is incomplete relative to the requested file list: the `AiProvider*` / `DefaultAiProvider` abstraction files were not present in the repository.
