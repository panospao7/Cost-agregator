# Deep Analysis — Batch 26: AI Providers — On-Device & NoOp (@debugger)

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

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | OnDeviceNotificationParser.kt | **HIGH** | Logic Error | Notification parser can construct invalid `ParsedTransaction` objects by setting `transferDirection` for `PURCHASE`/`WITHDRAWAL`, which violates the domain invariant and drops valid AI parses at runtime. | 1. Send a purchase notification through on-device parser. 2. Parser sets `transferDirection` on a PURCHASE type. 3. Downstream validation rejects the transaction. | Only populate transfer fields for supported transaction types and align the prompt/example so purchase responses do not emit transfer direction. |
| 2 | OnDeviceQueryInterpretationService.kt:89-90,123-133 | **HIGH** | Logic Error | Structured query parsing silently discards core fields (`categoryNames`, alias maps, and any time-period intent), so successful model outputs become incomplete/incorrect intents downstream. | 1. Ask "how much on groceries last week?" 2. On-device model returns structured response with category. 3. Parser discards category field. 4. Query executes as "all spending". | Parse and map category/merchant aliases back to domain IDs/names, add explicit period fields, and reject unsupported partial payloads instead of returning degraded structured results. |
| 3 | OnDeviceReceiptItemCategorizationService.kt | **HIGH** | Logic Error | Receipt-item categorization's overlap scorer coerces zero-overlap matches to `0.3`, which prevents keyword fallback from ever running after the first category and causes arbitrary category suggestions. | 1. Scan receipt with items not in known categories. 2. First category gets 0.3 floor score. 3. Keyword fallback never runs because 0.3 > fallback threshold. | Return `0f` for no overlap, separate raw matching from confidence flooring, and run keyword fallback only when no real match exists. |
| 4 | OnDeviceReceiptAssistService.kt:53-58,61-105 | **HIGH** | Logic Error | Receipt assist parsing uses `optDouble()`/`optLong()` coercion, so malformed numeric fields can become bogus `0.0`/`0L` suggestions instead of being rejected. | 1. On-device model returns malformed JSON with string where number expected. 2. `optDouble()` returns 0.0. 3. Receipt item shows €0.00 price. | Switch to strict numeric parsing/validation like the cloud provider and drop invalid fields rather than coercing them. |
| 5 | OnDeviceReceiptAssistService.kt | **MEDIUM** | Logic Error | The on-device receipt assist advertises "image analysis mode" but never reads `imagePath`/`imageMimeType` and always sends text-only input, so the on-device vision path is a false capability. | 1. Call receipt assist with image path. 2. Service ignores image and sends text-only prompt. 3. Results are text-only despite "vision mode" being advertised. | Either wire actual multimodal input into the provider or disable/remove the vision-mode branch until supported. |
| 6 | OnDeviceReviewPriorityScorer.kt | **MEDIUM** | Logic Error | Review-priority scoring returns inconsistent factor data: the AI-enhanced branch drops the computed `duplicateRisk`, and `calculateBaseScore()` never computes duplicate risk at all. | 1. Call priority scorer with AI-enhanced mode. 2. `duplicateRisk` is null in factors. 3. UI shows incomplete priority breakdown. | Build one `factorsWithDupes` object and reuse it in AI, non-AI, and base-score paths. |
| 7 | OnDeviceReviewPriorityScorer.kt | **MEDIUM** | Performance | Batch review scoring is O(n²) and repeatedly re-queries pending reviews for every item, which is avoidable repository/DB churn on queue loads. | 1. Load review queue with 100+ items. 2. Each item triggers a DB query for duplicate risk. 3. Total queries = O(n²). | Load the pending-review snapshot once per batch and precompute duplicate-risk inputs. |
| 8 | OnDeviceCategorizationAssistService.kt, OnDeviceDashboardBriefingService.kt, OnDeviceDedupeJudgeService.kt, OnDeviceQueryInterpretationService.kt, OnDeviceReceiptAssistService.kt, OnDeviceReviewExplanationService.kt, OnDeviceNotificationParser.kt | **MEDIUM** | Logic Error | Most on-device GenAI providers use a fragile greedy JSON extractor (`first '{'` to `last '}'`) that breaks on extra braces or multi-object responses, unlike the stricter cloud-side parsing. | 1. On-device model returns JSON with extra braces in text fields. 2. Greedy extractor captures wrong boundaries. 3. JSON parsing fails or returns corrupted data. | Replace the duplicated extractor with a shared balanced JSON/fenced-JSON parser. |
| 9 | OnDeviceCategorizationAssistService.kt, OnDeviceDashboardBriefingService.kt, OnDeviceDedupeJudgeService.kt, OnDeviceReceiptAssistService.kt, OnDeviceReviewExplanationService.kt, OnDeviceNotificationParser.kt | **MEDIUM** | Reliability | Only query interpretation enforces an inference timeout; the other on-device model-backed services can block indefinitely on model load/inference. | 1. On-device model fails to load. 2. `generateContent()` blocks indefinitely. 3. UI hangs waiting for response. | Wrap all `generateContent()` calls in bounded timeouts and return explicit timeout failures. |
| 10 | OnDeviceReceiptItemCategorizationService.kt, OnDeviceReviewPriorityScorer.kt, OnDeviceSemanticDuplicateDetector.kt | **LOW** | Architecture | Several classes presented as "on-device AI" are actually heuristic-only implementations, which makes routing/diagnostics misleading and behavior inconsistent with true model-backed providers. | N/A — misleading naming. | Reclassify them as deterministic fallbacks or implement actual on-device model calls with explicit fallback logic. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| C1 | OnDeviceQueryInterpretation → ExecuteFinancialQuery | **HIGH** | Filter Loss | Category and period filters are discarded during on-device query interpretation, so structured queries execute with widened scope. | Extend structured query schema and preserve all filter fields. |
| C2 | OnDeviceReceiptAssist → ReceiptScan ViewModel | **MEDIUM** | False Capability | Image analysis mode is advertised but never actually used, so users may expect vision-based parsing that never happens. | Either implement vision mode or remove the UI option. |
| C3 | OnDeviceReviewPriorityScorer → ReviewQueue | **MEDIUM** | Inconsistent Scoring | AI-enhanced and base scoring paths produce different factor structures, making priority comparisons inconsistent across modes. | Unify factor structure across all scoring paths. |
| C4 | All OnDevice Providers → AI Router | **MEDIUM** | Missing Timeouts | Most on-device providers have no timeout guard, so stalled local inference can hang indefinitely and block the AI pipeline. | Add consistent timeout wrapper to all on-device `generateContent()` calls. |

## Summary
- **Total issues: 14** (10 file-level + 4 cross-component)
- **Critical: 0**, **High: 4**, **Medium: 7**, **Low: 3**
- **Files with issues: 10/14**

## Key Patterns

### 1. Fragile JSON Extraction
All on-device providers use a greedy `{` to `}` JSON extractor that breaks on extra braces. This is a systemic issue that affects every on-device AI service and creates inconsistent behavior compared to the stricter cloud-side parsing.

### 2. Missing Timeouts
Only query interpretation has a timeout guard. All other on-device providers can block indefinitely if the model fails to load or inference hangs, creating a reliability gap in the AI pipeline.

### 3. Schema Incompleteness
On-device query interpretation discards critical fields (category, period, aliases) from structured responses, making complex queries execute with incomplete filters.

### 4. False Capabilities
Several services advertise capabilities they don't actually implement (image analysis, on-device AI for heuristic-only classes), creating misleading routing and user expectations.
