# Deep Analysis — Batch 26: AI Providers — On-Device & NoOp (@reviewer)

## Scope
- data/ai/provider/OnDeviceCategorizationAssistService.kt
- data/ai/provider/OnDeviceDashboardBriefingService.kt
- data/ai/provider/OnDeviceDedupeJudgeService.kt
- data/ai/provider/OnDeviceQueryInterpretationService.kt
- data/ai/provider/OnDeviceReceiptAssistService.kt
- data/ai/provider/OnDeviceReceiptItemCategorizationService.kt
- data/ai/provider/OnDeviceReviewExplanationService.kt
- data/ai/provider/OnDeviceReviewPriorityScorer.kt
- data/ai/provider/OnDeviceSemanticDuplicateDetector.kt
- data/ai/provider/OnDeviceNotificationParser.kt
- data/ai/provider/NoOpCategorizationAssistService.kt
- data/ai/provider/NoOpDashboardBriefingService.kt
- data/ai/provider/NoOpDedupeJudgeService.kt
- data/ai/provider/NoOpQueryInterpretationService.kt

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `data/ai/provider/OnDeviceNotificationParser.kt:122-139,172-203` | HIGH | Logic / contract violation | The prompt explicitly asks the model to emit `direction` for purchases and withdrawals, and the parser always forwards that field into `ParsedTransaction.transferDirection`. `ParsedTransaction` rejects `transferDirection` unless `type` is `TRANSFER` or `DEPOSIT`, so common purchase responses that follow the prompt example will throw during object construction and get dropped as `null`. | Only set `transferDirection` for transaction types that support it, and update the prompt/example so `PURCHASE`/`WITHDRAWAL` responses do not include transfer fields. |
| 2 | `data/ai/provider/OnDeviceQueryInterpretationService.kt:89-90,115-135` | HIGH | Logic / data loss | Structured parsing ignores `categoryNames`, `categoryAliasMap`, `merchantAliasMap`, and any notion of time period. The schema advertises category names, but `parseStructured()` only keeps merchants/ownership/min-max amounts; month/category filters from the model are silently discarded, and redacted aliases cannot be resolved back to real names. | Extend the schema with period fields, map category names back to category IDs, resolve merchant/category aliases through the input maps, and reject unsupported structured payloads instead of returning partially-applied intents. |
| 3 | `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt:77-90,140-147` | HIGH | Logic bug | `calculateMatchScore()` coerces every word-overlap score into `0.3..0.7`. For zero overlap it still returns `0.3`, which means `bestScore < 0.3f` becomes false after the first category and the keyword fallback path never runs. Unknown items therefore get an arbitrary category suggestion with a synthetic confidence floor. | Return `0f` when there is no overlap, keep raw overlap scores separate from confidence floors, and run keyword fallback only after a genuine no-match result. |
| 4 | `data/ai/provider/OnDeviceReviewPriorityScorer.kt:67-79,109-111` | MEDIUM | Logic / inconsistent scoring | The scorer computes a real `duplicateRisk`, but the AI-enhanced branch returns `baseFactors` instead of `baseFactors.copy(duplicateRisk = duplicateRisk)`, and `calculateBaseScore()` never computes duplicate risk at all. That makes `priorityScore`, `factors`, `urgencyReason`, and `quickScore()` disagree for the same review. | Build a single `factorsWithDupes` object and use it in both AI and non-AI branches, plus in `calculateBaseScore()`. |
| 5 | `data/ai/provider/OnDeviceReviewPriorityScorer.kt:65-67,138-159` | MEDIUM | Performance | Batch scoring re-reads `reviewQueueRepository.getPendingReviews().first()` for every review, then re-scans the whole pending list inside each call. This creates repeated flow/DB work and O(n²) duplicate checks when loading the review queue. | Load pending reviews once per batch, precompute duplicate statistics, and pass the shared snapshot into per-review scoring. |
| 6 | `data/ai/provider/OnDeviceReceiptAssistService.kt:142-157` | HIGH | JSON parsing | Numeric suggestions use `optDouble()` / `optLong()` directly. On malformed model output, these APIs coerce invalid values to `0.0` / `0L` instead of failing, so strings like `"12.34 EUR"` or `"2026-03-01"` can become bogus zero-valued suggestions that look valid downstream. | Use strict numeric parsing like the cloud provider (`Number`-only checks, finite validation, whole-number validation for dates), and drop invalid fields instead of coercing them. |
| 7 | `data/ai/provider/OnDeviceReceiptAssistService.kt:53-58,63-79` | MEDIUM | Architecture / false capability | “Image analysis mode” only changes prompt wording. The request always sends a single `TextPart`, and `imagePath` / `imageMimeType` are never read, so the on-device “vision” path is still OCR-only text inference. This conflicts with the input builder and retry chain, which treat it as image-aware analysis. | Either wire actual multimodal/image input into the provider or remove/disable the on-device vision path until the model/API can consume receipt images. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | `OnDeviceCategorizationAssistService.kt:136-140`; `OnDeviceDashboardBriefingService.kt:101-105`; `OnDeviceDedupeJudgeService.kt:106-110`; `OnDeviceQueryInterpretationService.kt:148-152`; `OnDeviceReceiptAssistService.kt:125-129`; `OnDeviceReviewExplanationService.kt:103-107`; `OnDeviceNotificationParser.kt:210-214` | MEDIUM | JSON extraction robustness | All of these providers use the same “first `{` / last `}`” extractor. It is greedy, not brace-balanced, and will fail on valid responses containing extra braces, multiple JSON objects, or explanatory text after the payload. The cloud implementations already use stricter extraction, so route changes can produce inconsistent parse success. | Replace the duplicated extractor with one shared balanced JSON extractor (or fenced-JSON parser) and reuse it across both cloud and on-device providers. |
| 2 | `OnDeviceCategorizationAssistService.kt:39-41`; `OnDeviceDashboardBriefingService.kt:34-36`; `OnDeviceDedupeJudgeService.kt:36-38`; `OnDeviceReceiptAssistService.kt:36-38`; `OnDeviceReviewExplanationService.kt:34-36`; `OnDeviceNotificationParser.kt:86-91` | MEDIUM | Reliability / timeout handling | Only the query interpreter wraps `generateContent()` in a timeout. The other on-device model-backed providers can block indefinitely on model load/inference failures, which is risky for UI-triggered flows and workers. | Add bounded timeouts and explicit timeout errors around every on-device `generateContent()` call, matching the query service’s pattern. |
| 3 | `OnDeviceReceiptItemCategorizationService.kt:17-27`; `OnDeviceReviewPriorityScorer.kt:161-198`; `OnDeviceSemanticDuplicateDetector.kt:230-291` | MEDIUM | Architecture / routing mismatch | These classes are surfaced as on-device AI providers, but the implementations are pure heuristics and never load or call a model. Routing/model diagnostics therefore imply AI-backed behavior even when the system is running deterministic logic only. | Either rename/reclassify them as deterministic fallbacks or implement actual ML Kit-backed on-device providers with an explicit heuristic fallback layer. |

## Summary
- Total issues: 10
- Critical: 0, High: 4, Medium: 6, Low: 0
- Files with issues: 10/14

## Key Patterns
- Multiple on-device providers duplicate fragile parsing infrastructure instead of sharing the stricter cloud-side helpers.
- Prompt contracts and downstream domain contracts are not aligned (for example notification `direction`, structured query fields, and image-analysis claims).
- Several classes labeled as on-device AI are currently heuristic placeholders, which makes routing behavior, diagnostics, and failure handling inconsistent across the batch.
- Test coverage is skewed toward prompt/JSON happy paths; I did not find direct tests for `OnDeviceNotificationParser`, `OnDeviceReviewPriorityScorer`, `OnDeviceSemanticDuplicateDetector`, or the real matching behavior inside `OnDeviceReceiptItemCategorizationService`.
