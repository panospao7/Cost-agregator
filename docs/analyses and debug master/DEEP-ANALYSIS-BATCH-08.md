# Deep Analysis — Batch 08: AI Use Cases - Navigation, Review, Sync

## Scope
- domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt
- domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt
- domain/ai/usecase/PrioritizeReviewItemsUseCase.kt
- domain/ai/usecase/SuggestCategoryFallbackUseCase.kt
- domain/ai/usecase/SuggestReceiptExtractionUseCase.kt
- domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt
- domain/ai/usecase/AiUseCaseModels.kt (not found in codebase)
- domain/ai/service/CloudCategorizationAssistService.kt
- domain/ai/service/CloudDashboardBriefingService.kt
- domain/ai/service/CloudDedupeJudgeService.kt
- domain/ai/service/CloudQueryInterpretationService.kt
- domain/ai/service/CloudReceiptAssistService.kt

## @reviewer Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt` | MAJOR | Logic | `force=true` is ignored on the receipt-assist cache fast path. The use case still returns a fresh cached artifact because the cache branch does not check `force`, so manual retries cannot actually force regeneration. | Add `!force` to the cache reuse condition, matching the other AI generation use cases in this batch. |
| 2 | `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt` | MAJOR | Architecture / Performance | The use case explicitly removed the non-forced `needsAssist` gate and now attempts AI for any receipt that has OCR text. That expands cost/privacy exposure and violates the approved flow, which only offers AI when key fields are missing or parser confidence is weak. | Restore a deterministic `needsAssist()` check for normal calls and keep `force=true` as the explicit override. |
| 3 | `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt` | MAJOR | Navigation | `categoryIds`, `merchants`, and `transactionTypes` are collapsed with `singleOrNull()`. Multi-value intents therefore silently drop those filters and open a broader transaction list than the interpreted query/result described. | Either return `null` for multi-value filters or extend the navigation/domain filter model to support sets instead of silently broadening the drilldown. |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 4 | `SuggestCategoryFallbackUseCase` + `CloudCategorizationAssistService` | MAJOR | Categorization assist still uses a nullable `CategoryAssistSuggestion?` contract. Missing API key, HTTP/network failure, retry exhaustion, parse failure, and “no supported category” all collapse to `null`, and the use case then records the router reason as the artifact error. Real cloud failure causes are lost. | Change the categorization assist contract to `AiServiceResult<CategoryAssistSuggestion>` and propagate concrete failures through the use case/artifact layer. |
| 5 | `CategorizationAssistInputBuilder` + `CloudCategorizationAssistService` | MAJOR | Redaction is incomplete for cloud categorization. The top-level merchant/supporting text are sanitized, but `recentTransactionsWithSameMerchant` stays raw and `buildMerchantContext()` sends that local merchant/category history to the cloud prompt. | Sanitize or omit recent merchant-history hints when redaction is enabled, ideally in the input builder so the cloud service never receives raw history. |
| 6 | `FinancialQueryInterpretationInputBuilder` + `CloudQueryInterpretationService` + `OnDeviceQueryInterpretationService` | MAJOR | Alias maps are added to `FinancialQueryInterpretationInput`, but parsed cloud results are never restored back to real merchant/category names. With cloud redaction enabled, structured intents can contain `merchant_<hash>` / `category_<hash>`, which then break query execution and navigation matching. | Post-process structured interpretation results with `merchantAliasMap` / `categoryAliasMap` before returning them from the parser/service path. |
| 7 | `AiModels.kt` + `AiSettingsRepositoryImpl.kt` | MAJOR | `AiSettings()` defaults `receiptAssistEnabled` and `receiptImageCloudEnabled` to `true`, while repository hydration still defaults both to `false` on empty DataStore. Code/tests that instantiate `AiSettings()` directly therefore exercise a different behavior than production settings reads. | Make the repository defaults and `AiSettings` constructor defaults match so the app has a single source of truth for initial AI behavior. |

### Summary
- Total issues: 7
- Files with issues: 6/12

---

## Addendum — Missing File Review (@reviewer)

### Additional Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 8 | `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt` | MAJOR | Security / Privacy | `suggest()` still enables cloud image upload whenever `input.isImageAnalysisMode && settings.receiptImageCloudEnabled` (`74-76`), and `buildImageInlineData()` then sends the raw receipt file (`304-323`) even when `input.redactBeforeCloud` is `true`. That bypasses the global redaction policy for the most sensitive payload in this flow: the full receipt image. | When redaction is enabled, force OCR-only cloud prompts (no image upload), or introduce an explicit separate opt-in that clearly states receipt images cannot be redacted before cloud processing. |
| 9 | `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt` | MAJOR | Performance / Stability | `buildImageInlineData()` reads the entire file with `file.readBytes()` before enforcing `MAX_INLINE_IMAGE_BYTES` (`308-313`). Large camera images are therefore fully loaded into memory and only then rejected, which is an avoidable memory/battery spike and potential OOM path. | Check `file.length()` before reading, and downsample/stream the image instead of calling `readBytes()` on the full file. |
| 10 | `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/PrioritizeReviewItemsUseCase.kt` | MAJOR | Requirements / Integration | The prioritization use case is implemented (`30-77`), but there are no production call sites under `app/src/main/java` beyond the class itself. The planned review-priority feature is therefore dead code and never affects the review queue ordering. | Wire `execute()` into the review queue loading/ViewModel path (or remove the unused feature until it is actually integrated). |

### Additional Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 11 | `DashboardBriefingInputBuilder` + `OnDeviceDashboardBriefingService` + `CloudDashboardBriefingService` | MAJOR | Dashboard briefing cloud prompts ignore the app's redaction policy. `DashboardBriefingInputBuilder` includes raw budget warnings/upcoming item descriptions (`DashboardBriefingInputBuilder.kt:39-54`), `OnDeviceDashboardBriefingService.buildPrompt()` injects them verbatim (`OnDeviceDashboardBriefingService.kt:65-75`), and `CloudDashboardBriefingService.buildRequestBody()` forwards that prompt unchanged (`CloudDashboardBriefingService.kt:185-211`). With cloud briefing enabled, detailed personal finance summaries still leave the device even when `redactBeforeCloud=true`. | Add a redacted/aliased dashboard input for cloud use, or force dashboard briefing to on-device only whenever cloud redaction is enabled. |
| 12 | `CloudQueryInterpretationService` + `OnDeviceQueryInterpretationService` | MAJOR | Structured AI query results can silently drop key constraints. `CloudQueryInterpretationService.parseResponse()` accepts any structured parse (`CloudQueryInterpretationService.kt:149-165`), but the shared prompt schema omits period/type fields (`OnDeviceQueryInterpretationService.kt:89-90`) and `parseStructured()` only populates merchants/ownership/min/max (`OnDeviceQueryInterpretationService.kt:124-129`), ignoring category names as well. Queries like “groceries this month” or “deposits last month” can therefore look successfully interpreted while executing against a broader dataset than requested. | Extend the prompt/schema/parser to carry period, category, and transaction-type filters (with current-time context), and reject structured AI results that cannot preserve the user’s constraints. |
| 13 | `JudgePendingReviewDuplicateUseCase` + `CloudDedupeJudgeService` + `OnDeviceDedupeJudgeService` | MAJOR | The dedupe flow trusts hallucinated match references. Both parsers accept any `matchedTargetType` / `matchedTargetId` pair the model emits (`CloudDedupeJudgeService.kt:233-240`, `OnDeviceDedupeJudgeService.kt:91-98`), and `JudgePendingReviewDuplicateUseCase` persists that suggestion without verifying it belongs to the bounded candidate set (`JudgePendingReviewDuplicateUseCase.kt:97-105`). A malformed model response can therefore store a duplicate match pointing at an unrelated record. | Validate the returned match against `input.candidates` before persisting. If it is not one of the supplied candidates, clear the match and downgrade the verdict to `UNCERTAIN` (or treat it as a parse failure). |
| 14 | `SyncProactiveBriefingWorkUseCase` + `DefaultAiCapabilityRouter` + `GenerateDashboardBriefingUseCase` | MINOR | Proactive briefing work is scheduled from three booleans alone (`SyncProactiveBriefingWorkUseCase.kt:15-23`) and never checks whether `AiCapability.DASHBOARD_BRIEFING` can actually route anywhere. If both cloud and on-device briefing are unavailable/disabled, the app still keeps a periodic worker that wakes up daily only to no-op during generation/delivery. | Inject the capability router (or equivalent availability check) and only schedule daily work when dashboard briefing can route to a non-`DISABLED` implementation. |

### Addendum Notes
- Requested path `domain/ai/usecase/AiUseCaseModels.kt` is not present in the current tree. The relevant AI contracts are split across `domain/ai/model/AiModels.kt`, `CaptureAssistModels.kt`, `FinancialQueryModels.kt`, and `ReviewPriorityModels.kt`.

### Updated Summary
- Additional issues in this addendum: 7
- Updated total issues: 14
- Reviewed requested existing files: 7
