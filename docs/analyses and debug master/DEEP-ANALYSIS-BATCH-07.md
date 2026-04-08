# Deep Analysis — Batch 07: AI Use Cases - Input Builders

## Scope
- domain/ai/usecase/CategorizationAssistInputBuilder.kt
- domain/ai/usecase/DedupeJudgeInputBuilder.kt
- domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt
- domain/ai/usecase/ReceiptAssistInputBuilder.kt
- domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt
- domain/ai/usecase/ReviewExplanationInputBuilder.kt
- domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt
- domain/ai/usecase/ExecuteFinancialQueryUseCase.kt
- domain/ai/usecase/ExplainPendingReviewUseCase.kt
- domain/ai/usecase/GenerateDashboardBriefingUseCase.kt
- domain/ai/usecase/GetAiRuntimeStatusUseCase.kt
- domain/ai/usecase/InterpretFinancialQueryUseCase.kt

## @reviewer Findings

### Issues Found
| # | File | Severity | Type | Description | Suggested Fix |
|---|------|----------|------|-------------|---------------|
| 1 | `domain/ai/usecase/CategorizationAssistInputBuilder.kt` | MAJOR | Privacy / PII leakage | When redaction is enabled, `fetchRecentTransactionHints()` still emits raw merchant names and category names. Those hints are later embedded into the cloud categorization prompt as “Known merchant history”, so prior spending context is leaked even though the main merchant field was redacted. | Drop `recentTransactionsWithSameMerchant` from redacted cloud inputs, or pseudonymize merchants and replace category names with cloud-safe aliases before the prompt is built. |
| 2 | `domain/ai/usecase/DedupeJudgeInputBuilder.kt` | MAJOR | Logic | Duplicate candidates are matched only by merchant/date/amount and the generated summaries omit transaction type entirely. A purchase, transfer, or deposit with the same amount on the same day can therefore be sent to the AI as a duplicate candidate even though it is a different kind of transaction. | Include transaction type in `DedupeCandidateSummary` and pre-filter candidates to compatible types before sending them to the judge service. **[RESOLVED BY A.4]** `DedupeJudgeInputBuilder` now queries canonical duplicate candidates with explicit transaction type and includes transaction type in `DedupeCandidateSummary`. |
| 3 | `domain/ai/usecase/ReceiptAssistInputBuilder.kt` | MAJOR | Privacy / PII leakage | `sanitizeOcrText()` only removes IBANs, card-like strings, and 10+ digit numbers. Emails, formatted phone numbers, and other common receipt identifiers can still be forwarded to cloud receipt extraction. | Reuse the shared cloud sanitizer or add the missing email/phone redaction patterns and whitespace normalization here. |
| 4 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt` | MAJOR | Logic | `executeCategoryBreakdown()`, `executeMerchantBreakdown()`, and `executeLargest()` bypass the full filter set by calling broad period aggregates. Queries such as “top merchants for groceries”, “shared spending by category”, or “largest grocery purchase” will return answers for all purchases in the period instead of the filtered subset. | Add repository aggregations that accept the full filter set, or derive grouped/largest results from the already-filtered transaction set. |
| 5 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt` | MAJOR | Correctness / Performance | `loadFilteredExpenses()` always loads a single page capped at 500 rows, but `executeTotal()`, `executeCount()`, and `executeAverage()` treat that page as the full result set. Any query with more than 500 matches will undercount and under-sum silently. | Page through all matches, or add dedicated filtered `SUM`/`COUNT`/`AVG` repository queries so summary answers are computed from the full dataset. |
| 6 | `domain/ai/usecase/ExplainPendingReviewUseCase.kt` | MAJOR | Cache correctness | Cache reuse checks only TTL/prompt version and ignores the newly computed `sourceHash`. If the pending review changes before expiry, the use case can keep serving an old explanation for stale review data. | Compare `existing.sourceHash` with the current `sourceHash` before treating a READY artifact as fresh. |
| 7 | `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt` | MAJOR | Cache correctness | Same cache bug as above: the use case skips regeneration for any READY same-day artifact within TTL even when the dashboard snapshot has changed materially. That can leave users with outdated briefings for the rest of the day. | Require a `sourceHash` match in the cache-hit path, or include a stronger data fingerprint in the artifact key. |
| 8 | `domain/ai/usecase/InterpretFinancialQueryUseCase.kt` | MAJOR | Date logic | The local fallback interprets `this week` as “now minus 7 days” instead of the current calendar week, and `last week` falls through to the generic `week` branch which resolves to the current week. Common time-period queries will therefore return the wrong range. | Remove the ad-hoc week special case and use `TimePeriodUtils.getWeekRange()` consistently for both current-week and last-week phrases. |
| 9 | `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt` | MINOR | Performance | Runtime status is fetched sequentially for each capability, so refresh latency grows linearly with the number of capability rows shown. | Fetch per-capability statuses in parallel (for example with `async`/`awaitAll`) before building the summary. |

### Cross-Component Issues
| # | Components | Severity | Description | Suggested Fix |
|---|-----------|----------|-------------|---------------|
| 1 | `ReviewExplanationInputBuilder` / `CategorizationAssistInputBuilder` / `ReceiptItemCategorizationInputBuilder` / `ReceiptAssistInputBuilder` ↔ routing use cases | MAJOR | Multiple builders decide redaction before route selection and key use cases build inputs before asking the router. With `redactBeforeCloud = true` (the default), on-device executions can receive cloud-redacted merchants, OCR, notification text, or stripped supporting context even though the data never leaves the device. | Resolve the route first, then redact only when the chosen route is actually cloud, or pass the resolved route into the builders so they can preserve full local context for on-device execution. |
| 2 | `FinancialQueryInterpretationInputBuilder` ↔ query interpretation services ↔ `ExecuteFinancialQueryUseCase` | MAJOR | The builder creates merchant/category alias maps for redacted cloud use, but the parsing/execution pipeline never applies those maps and provider category names are not turned back into category IDs. That means provider-produced structured filters can be lost or widened before execution. | Parse provider category/merchant outputs back through the alias maps, resolve category names to IDs, and validate the final intent before handing it to `ExecuteFinancialQueryUseCase`. |

### Summary
- Total issues: 11
- Files with issues: 8/12
