# Final Verification — Batch 26: AI Providers — On-Device & NoOp

## Scope
- `com/yourname/expensetracker/data/ai/provider/OnDeviceCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceDedupeJudgeService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReviewExplanationService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReviewPriorityScorer.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceSemanticDuplicateDetector.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceNotificationParser.kt`
- `com/yourname/expensetracker/data/ai/provider/NoOpCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/NoOpDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/NoOpDedupeJudgeService.kt`
- `com/yourname/expensetracker/data/ai/provider/NoOpQueryInterpretationService.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/data/ai/provider/OnDeviceNotificationParser.kt:123-139,190-203` | High | Logic / contract violation | The prompt explicitly teaches the model to emit `direction` for purchases and the parser always forwards it into `ParsedTransaction.transferDirection`. `ParsedTransaction` rejects `transferDirection` unless `type` is `TRANSFER` or `DEPOSIT`, so valid purchase/withdrawal parses can be dropped at construction time. | B | CONFIRMED | Only set transfer fields for supported transaction types, and update the prompt/example so `PURCHASE`/`WITHDRAWAL` outputs do not include transfer direction. |
| 2 | `com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt:84-90,115-136` | Medium | Logic / filter loss | Structured parsing keeps only merchants, ownership, and min/max amounts. Category filters are discarded, alias maps are never resolved, and the schema has no period field at all. In the current pipeline this often degrades into an unnecessary follow-up clarification instead of a correct answer, while redacted aliases remain unusable. | B | DOWNGRADED | Extend the schema and parser to preserve period/category filters, resolve merchant/category aliases back to canonical values, and reject unsupported partial payloads instead of returning incomplete intents. |
| 3 | `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptItemCategorizationService.kt:76-90,131-147` | High | Logic bug | `calculateMatchScore()` floors every overlap score into `0.3..0.7`. Zero-overlap categories therefore still score `0.3`, which prevents the keyword fallback path from running after the first category and yields arbitrary suggestions for unknown items. | B | CONFIRMED | Return `0f` for no overlap, keep raw similarity separate from confidence flooring, and only run the fallback after a genuine no-match result. |
| 4 | `com/yourname/expensetracker/data/ai/provider/OnDeviceReviewPriorityScorer.kt:66-79,109-111` | Medium | Logic / inconsistent scoring | The scorer computes `duplicateRisk`, but the AI-enhanced branch returns `baseFactors` without it, and `calculateBaseScore()` never applies duplicate risk either. `priorityScore`, `factors`, `urgencyReason`, and `quickScore()` can therefore disagree for the same review. | B | CONFIRMED | Build one `factorsWithDupes` object and use it consistently in AI, non-AI, and base-score paths. |
| 5 | `com/yourname/expensetracker/data/ai/provider/OnDeviceReviewPriorityScorer.kt:57-68,138-159` | Medium | Performance | Batch scoring recalculates duplicate risk by re-reading `reviewQueueRepository.getPendingReviews().first()` for every review and then scanning the full pending list each time, creating repeated flow/DB work and O(n²) duplicate checks. | B | CONFIRMED | Load the pending-review snapshot once per batch, precompute duplicate-risk inputs, and pass the shared snapshot into per-review scoring. |
| 6 | `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt:142-157` | High | JSON parsing | Numeric suggestions use `optDouble()` / `optLong()` directly, so malformed model output can be coerced into bogus `0.0` / `0L` values instead of being rejected. Those zero-valued suggestions are then indistinguishable from valid parsed fields downstream. | B | CONFIRMED | Use strict numeric parsing/validation like the cloud provider and drop invalid numeric fields instead of coercing them. |
| 7 | `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt:53-58,63-79` | Medium | Capability mismatch | “Image analysis mode” only changes the prompt text. The service never reads `imagePath` or `imageMimeType`, always sends a single `TextPart`, and therefore cannot actually consume receipt images despite being used as an on-device vision attempt. | B | CONFIRMED | Either wire real multimodal/image input into the on-device provider or disable the on-device vision route until image input is supported. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt:89-90,124-133` | Medium | Schema omission | The structured-query schema has no way to express `transactionTypes`, even though `ExpenseQueryFilters` supports them and the local fallback resolves purchase/withdrawal/deposit/transfer intent. Structured on-device results therefore cannot preserve transaction-type filters and can widen queries once period handling is fixed. | Add `transactionTypes` to the prompt schema and map them into `ExpenseQueryFilters.transactionTypes`; reject structured outputs that mention unsupported transaction kinds. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| — | — | — | None. Every reported issue was validated as real; the only adjustment was lowering the severity of the structured-query filter-loss issue because the current executor usually falls back to clarification when period data is missing. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | On-device query interpretation → financial query execution | Medium | Filter loss | The on-device structured-query path cannot preserve the full filter vocabulary (period, categories, aliases, transaction types), so assistant queries either widen unexpectedly later or degrade into avoidable clarification prompts now. | `OnDeviceQueryInterpretationService.kt`, `FinancialQueryInterpretationInputBuilder.kt`, `ExecuteFinancialQueryUseCase.kt` | Make the structured schema match `ExpenseQueryFilters`, resolve aliases before returning, and reject incomplete structured payloads. |
| 2 | On-device receipt assist → smart retry chain / receipt UI | Medium | False capability | The retry chain exposes an “on-device vision” stage, but the provider is text-only. Routing and attempt diagnostics therefore imply image-aware analysis that the implementation cannot perform. | `OnDeviceReceiptAssistService.kt`, `SmartReceiptAssistService.kt`, `ReceiptAssistService.kt`, `ReceiptScanViewModel.kt` | Remove the on-device vision branch or implement actual image input support and report image usage explicitly. |
| 3 | Review-priority scoring → review queue ordering | Medium | Inconsistent scoring | Batch scoring, single-item scoring, and quick scoring do not share the same factor set, so duplicate risk can affect ordering but disappear from the factor breakdown or quick preview path. | `OnDeviceReviewPriorityScorer.kt`, `PrioritizeReviewItemsUseCase.kt` | Centralize factor construction and reuse the same scored factor object across all entry points. |
| 4 | On-device model-backed providers → AI router / callers | Medium | Reliability | Only query interpretation wraps `generateContent()` in a timeout. The other ML Kit-backed on-device providers can stall indefinitely on model load or inference hangs. | `OnDeviceCategorizationAssistService.kt`, `OnDeviceDashboardBriefingService.kt`, `OnDeviceDedupeJudgeService.kt`, `OnDeviceReceiptAssistService.kt`, `OnDeviceReviewExplanationService.kt`, `OnDeviceNotificationParser.kt` | Add a shared bounded-timeout wrapper around every on-device `generateContent()` call. |
| 5 | On-device providers → JSON parsing pipeline | Medium | Robustness | Multiple providers duplicate the same greedy `first '{'` / `last '}'` extractor, which is less robust than the balanced cloud-side parser and can fail on extra braces or mixed prose/JSON responses. | `OnDeviceCategorizationAssistService.kt`, `OnDeviceDashboardBriefingService.kt`, `OnDeviceDedupeJudgeService.kt`, `OnDeviceQueryInterpretationService.kt`, `OnDeviceReceiptAssistService.kt`, `OnDeviceReviewExplanationService.kt`, `OnDeviceNotificationParser.kt` | Replace the duplicated extractor with one shared balanced JSON/fenced-JSON parser used by both cloud and on-device providers. |
| 6 | Heuristic-only “on-device AI” providers → routing / diagnostics | Low | Architecture | Several providers are surfaced as on-device AI components even though they never load or call a model. That makes routing and diagnostics overstate actual AI involvement. | `OnDeviceReceiptItemCategorizationService.kt`, `OnDeviceReviewPriorityScorer.kt`, `OnDeviceSemanticDuplicateDetector.kt` | Reclassify them as deterministic fallbacks or add explicit model-backed implementations with heuristic fallback layers. |

## Summary
- Total verified issues: 7
- Confirmed: 7 (Critical: 0, High: 3, Medium: 4, Low: 0)
- False positives: 0
- Missed issues found: 1
- Files affected: 10/14

## Key Patterns
- Prompt/schema contracts and downstream domain contracts are not aligned, especially for notification parsing and structured query interpretation.
- On-device providers duplicate weaker parsing and timeout behavior instead of reusing the hardened cloud-side helpers.
- Capability routing currently overstates what some “on-device AI” paths can actually do, especially for receipt vision and heuristic-only providers.
- Existing tests mainly cover happy-path prompt/JSON parsing and miss malformed model output plus end-to-end contract mismatches.
