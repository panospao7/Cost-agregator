# Final Verification — Batch 07: AI Use Cases - Input Builders

> **[RESOLVED BY A.3]** The non-deterministic default values issue (System.currentTimeMillis) has been fixed across the codebase.

## Scope
- Primary batch files analyzed:
  - `domain/ai/usecase/CategorizationAssistInputBuilder.kt`
  - `domain/ai/usecase/DedupeJudgeInputBuilder.kt`
  - `domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
  - `domain/ai/usecase/ReceiptAssistInputBuilder.kt`
  - `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
  - `domain/ai/usecase/ReviewExplanationInputBuilder.kt`
  - `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
  - `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`
  - `domain/ai/usecase/ExplainPendingReviewUseCase.kt`
  - `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
  - `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt`
  - `domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
  - `domain/ai/usecase/DashboardBriefingInputBuilder.kt` *(cited in debugger report and validated)*
- Supporting validation files analyzed:
  - `domain/ai/policy/AiPolicy.kt`
  - `domain/ai/policy/AiPolicyImpl.kt`
  - `domain/ai/policy/DefaultAiCapabilityRouter.kt`
  - `domain/ai/service/AiCapabilityRouter.kt`
  - `domain/ai/model/AiModels.kt`
  - `domain/ai/model/FinancialQueryModels.kt`
  - `domain/ai/model/CaptureAssistModels.kt`
  - `domain/ai/service/AiArtifactRepository.kt`
  - `data/repository/AiArtifactRepositoryImpl.kt`
  - `data/database/dao/AiArtifactDao.kt`
  - `data/database/entity/AiArtifactEntity.kt`
  - `data/repository/ExpenseRepository.kt`
  - `data/database/dao/ExpenseDao.kt`
  - `data/database/entity/Expense.kt`
  - `data/database/entity/PendingReview.kt`
  - `domain/util/TimePeriodUtils.kt`
  - `domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
  - `domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
  - `domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
  - `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
  - `domain/ai/service/DedupeJudgeService.kt`
  - `data/ai/provider/CloudDedupeJudgeService.kt`
  - `data/ai/provider/OnDeviceDedupeJudgeService.kt`
  - `data/ai/provider/CloudQueryInterpretationService.kt`
  - `data/ai/provider/OnDeviceQueryInterpretationService.kt`
  - `data/ai/provider/HybridQueryInterpretationService.kt`
  - `data/ai/provider/internal/CloudPiiSanitizer.kt`
  - `data/ai/provider/CloudCategorizationAssistService.kt`
  - `data/ai/provider/OnDeviceCategorizationAssistService.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `domain/ai/usecase/CategorizationAssistInputBuilder.kt:41-62` | High | Privacy | Redacted categorization inputs still include raw `recentTransactionsWithSameMerchant`; the cloud categorization prompt emits them as “Known merchant history”, leaking prior merchant/category context. | R | CONFIRMED | Omit or sanitize merchant-history hints when redaction is enabled, or make the builder route-aware. |
| 2 | `domain/ai/usecase/CategorizationAssistInputBuilder.kt:34-40` | — | Correctness | The reported critical empty-merchant/NPE path is not present: `sanitizeMerchant()` falls back to `"Unknown"`, and no empty-string SHA/NPE occurs here. | D | FALSE_POSITIVE | No fix needed for the reported bug. |
| 3 | `domain/ai/usecase/CategorizationAssistInputBuilder.kt:116-117` | Medium | Concurrency | `fetchRecentTransactionHints()` catches `Exception` and swallows `CancellationException`, so cancelled parent jobs can keep running. | D | CONFIRMED | Re-throw `CancellationException` before returning `emptyList()`. **[RESOLVED BY A.7]** |
| 4 | `domain/ai/usecase/DedupeJudgeInputBuilder.kt:39-42,63-109` | High | Logic | Duplicate candidates are matched and summarized without transaction type, so same-day/same-amount purchases, transfers, deposits, etc. can be judged against each other as duplicates. | R | CONFIRMED | Filter to compatible types and include type in `DedupeCandidateSummary` / judge prompts. **[RESOLVED BY A.4]** `DedupeJudgeInputBuilder` now calls `getDuplicateCandidatesInWindow()` with explicit `transactionType`, and pending-review candidates are filtered via `DuplicateDetectionPolicy.areTypesCompatible()`. Transaction type is included in `DedupeCandidateSummary`. |
| 5 | `domain/ai/usecase/DedupeJudgeInputBuilder.kt:113-117` | High | Logic | The AI judge is skipped when there is exactly one candidate, even though one subject↔candidate pair is still a valid duplicate decision. | D | DOWNGRADED | Replace `candidates.size < 2` with `candidates.isEmpty()`. |
| 6 | `domain/ai/usecase/DedupeJudgeInputBuilder.kt:26-28`; `domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt:27-29`; `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt:27-29` | — | Privacy | The reported “router can later send unredacted data to cloud when `canUseCloudFor=false`” path is not real in current code: the router uses the same policy gate and same settings object. | D | FALSE_POSITIVE | No fix for the reported leak path; address route-before-build redaction separately (see pipeline issues). |
| 7 | `domain/ai/usecase/DashboardBriefingInputBuilder.kt:24,52,57` | Low | Concurrency | `SimpleDateFormat` is stored as mutable instance state and used unsafely; concurrent calls can corrupt formatted dates. | D | DOWNGRADED | Use `DateTimeFormatter` or create the formatter per call. **[A.8 AUDIT: already compliant, no code change required]** |
| 8 | `domain/ai/usecase/ReceiptAssistInputBuilder.kt:68-76` | High | Privacy | OCR sanitization omits email/phone redaction (and normalization), so common receipt identifiers can still be forwarded to cloud receipt extraction. | B | CONFIRMED | Reuse `CloudPiiSanitizer.sanitizeText()` or add the missing regexes and whitespace normalization. |
| 9 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:53-119` | High | Logic | Category/merchant breakdowns and “largest” use purchase-only period aggregates and ignore most filters, so filtered queries can return broadened answers. | B | CONFIRMED | Add repository queries that accept the full filter set, or compute results from the fully filtered transaction set. |
| 10 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:181-192` | High | Correctness | `loadFilteredExpenses()` only loads one page of 500 rows, but total/count/average treat that page as the complete result set. | R | CONFIRMED | Page through all matches or add filtered aggregate queries. |
| 11 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:111,139,146,172` | Medium | Correctness | Result strings hardcode `EUR`, so non-EUR answers are mislabeled. | D | CONFIRMED | Use actual transaction/query currency, or return a mixed-currency-safe result. |
| 12 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:186-188` | High | Logic | Multi-value merchant/category/type filters are dropped by `singleOrNull()`, silently widening valid structured queries. | D | CONFIRMED | Preserve set-based filters end-to-end instead of collapsing them to a single scalar. |
| 13 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:113-117` | — | Design | The reported drilldown narrowing is intentional drilldown construction, not a correctness or NPE bug. | D | FALSE_POSITIVE | No fix needed for the reported issue. |
| 14 | `domain/ai/usecase/InterpretFinancialQueryUseCase.kt:42-48` | Medium | Concurrency | `runCatching { ... }.getOrElse { ... }` swallows `CancellationException` and turns cancellation into `Unsupported`. | D | CONFIRMED | Use `try/catch` and rethrow `CancellationException`. **[RESOLVED BY A.7]** |
| 15 | `domain/ai/usecase/InterpretFinancialQueryUseCase.kt:105-109,204-209` | High | Date logic | Local fallback misinterprets period phrases: `this week` becomes a trailing 7-day range, and generic `week`/`month` branches can override more specific phrases like `last week` / `last month`. | B | UPGRADED | Remove the ad-hoc special cases and consistently use `TimePeriodUtils.getWeekRange()/getMonthRange()` with specific phrase ordering. |
| 16 | `domain/ai/usecase/InterpretFinancialQueryUseCase.kt:105` | — | Logic | The cited operator-precedence bug is not real; Kotlin evaluates this condition normally. The problem is heuristic phrase matching, not precedence. | D | FALSE_POSITIVE | No precedence fix required. |
| 17 | `domain/ai/usecase/InterpretFinancialQueryUseCase.kt:99` | — | Performance | Duplicate `Calendar.getInstance()` creation is a micro-optimization issue, not a bug. | D | FALSE_POSITIVE | Optional cleanup only. |
| 18 | `domain/ai/usecase/ExplainPendingReviewUseCase.kt:66-77` | High | Cache correctness | Cache reuse checks TTL/prompt version only and ignores the newly computed `sourceHash`, so stale READY artifacts can be reused after review data changes. | R | CONFIRMED | Compare `existing.sourceHash` with the current source hash before skipping regeneration. |
| 19 | `domain/ai/usecase/ExplainPendingReviewUseCase.kt:77` | Low | Robustness | `sourceHash = input.hashCode().toString()` is only a 32-bit fingerprint, which is weak for cache identity / unique indexing. | D | CONFIRMED | Use a stable content digest (for example SHA-256 of serialized input). |
| 20 | `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:68-79` | High | Cache correctness | Same stale-cache bug as above: same-day READY artifacts are reused without verifying that the dashboard snapshot is unchanged. | R | CONFIRMED | Include `sourceHash` in the cache-hit validation path. |
| 21 | `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:79` | Low | Robustness | `input.hashCode()` is also used as the dashboard briefing source fingerprint, making cache identity weaker than intended. | D | CONFIRMED | Use a stable content digest. |
| 22 | `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt:27-42` | Low | Performance | Capability status checks are awaited sequentially, so refresh latency grows linearly with the number of capabilities. | R | CONFIRMED | Fetch model statuses in parallel (`async` / `awaitAll`). |
| 23 | `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt:44` | — | Reliability | The reported notification-ID collision is only a theoretical `String.hashCode()` concern for a single daily key and is not a material defect in current behavior. | D | FALSE_POSITIVE | No fix needed for the reported issue. |
| 24 | `domain/ai/usecase/DedupeJudgeInputBuilder.kt:42` | — | Heuristic | The reported sort-weighting problem is a tuning preference, not a demonstrated correctness bug. | D | FALSE_POSITIVE | Only retune weights if product behavior proves poor. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/ai/usecase/InterpretFinancialQueryUseCase.kt:84-115` | High | Intent parsing | The early special-case returns for `last month` / `this week` / `current week` collapse richer queries into plain TOTAL intents, discarding merchant/category/grouping/metric cues (for example, `largest grocery purchase last month` becomes a month total). | Remove the broad early returns and resolve period + metric + filters in one parsing path. |
| 2 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:53-172` | High | Currency correctness | Query totals/breakdowns/largest/averages aggregate raw amounts across whatever currencies exist in the dataset, with no conversion or per-currency separation. The hardcoded `EUR` labels hide a deeper mixed-currency math bug. | Restrict answers to one currency, convert through an FX layer, or group/query results by currency before aggregating. |
| 3 | `domain/ai/usecase/ExplainPendingReviewUseCase.kt:125-134`; `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:117-126` | Medium | Concurrency | Both generation use cases catch `Exception` around provider calls and swallow `CancellationException`, potentially writing FAILED artifacts for cancelled jobs. | Re-throw `CancellationException` before persisting failure artifacts. **[RESOLVED BY A.7]** |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #1 | `CategorizationAssistInputBuilder.kt:34` | `CloudPiiSanitizer.sanitizeMerchant()` falls back to `"Unknown"`; there is no empty-string SHA or NPE path here. |
| 2 | Debugger #4/#5/#6 and Pipeline P2 | `DedupeJudgeInputBuilder.kt:26-28`; `FinancialQueryInterpretationInputBuilder.kt:27-29`; `ReceiptItemCategorizationInputBuilder.kt:27-29` | The claimed cloud-leak path requires the router to ignore `canUseCloudFor()`, but the current router uses the same gate and same settings object, so that route cannot occur. |
| 3 | Debugger #10 | `ExecuteFinancialQueryUseCase.kt:115` | Narrowing the drilldown to the selected result is intentional behavior, not a correctness defect. |
| 4 | Debugger #12 | `InterpretFinancialQueryUseCase.kt:105` | The expression is not suffering from operator-precedence breakage; the real issue is broad string matching. |
| 5 | Debugger #13 | `InterpretFinancialQueryUseCase.kt:99` | Duplicate `Calendar` construction is wasteful but not buggy. |
| 6 | Debugger #18 | `DedupeJudgeInputBuilder.kt:42` | Candidate ordering weight is heuristic tuning, not a proven logical fault. |
| 7 | Debugger #20 | `DeliverProactiveBriefingNotificationUseCase.kt:44` | The hash-collision scenario is purely theoretical for one daily target key and does not represent a material current defect. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Input builders → router/provider selection | High | Route / redaction ordering | Several builders redact before the actual route is known. When on-device is ultimately chosen, local models receive unnecessarily redacted merchants/OCR/context even though nothing leaves the device. This is confirmed for the reviewer-reported builders and also present in the same pattern for dedupe/query builders. | `CategorizationAssistInputBuilder.kt`, `ReviewExplanationInputBuilder.kt`, `ReceiptAssistInputBuilder.kt`, `ReceiptItemCategorizationInputBuilder.kt`, `DedupeJudgeInputBuilder.kt`, `FinancialQueryInterpretationInputBuilder.kt`, `SuggestCategoryFallbackUseCase.kt`, `SuggestReceiptExtractionUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `InterpretFinancialQueryUseCase.kt` | Decide route first, or pass the resolved route into builders so redaction is applied only for cloud executions. |
| 2 | Query interpretation builder → provider parser → executor | Critical | Filter loss / widening | Alias maps are never reversed, provider prompt/parser do not carry period/category/type filters end-to-end, and execution later drops multi-value filters. Structured AI outputs can therefore lose filters, widen filters, or arrive without an executable period. | `FinancialQueryInterpretationInputBuilder.kt`, `CloudQueryInterpretationService.kt`, `OnDeviceQueryInterpretationService.kt`, `InterpretFinancialQueryUseCase.kt`, `ExecuteFinancialQueryUseCase.kt` | Extend the schema/parser to include period/category/type filters, map aliases back to local IDs, and preserve set-based filters in execution. |
| 3 | Dedupe candidate builder → duplicate judge use case | High | Candidate bypass | The pipeline short-circuits when exactly one candidate exists, skipping AI duplicate judging for the most common duplicate-pair shape. | `DedupeJudgeInputBuilder.kt`, `JudgePendingReviewDuplicateUseCase.kt` | Judge whenever `candidates.isNotEmpty()` rather than requiring 2+ candidates. |

## Summary
- Total verified issues: 24
- Confirmed: 17 (Critical: 0, High: 10, Medium: 3, Low: 4)
- False positives: 7
- Missed issues found: 3
- Files affected: 12/13

## Key Patterns
- Route selection happens after input building, so privacy redaction and local-context fidelity are coupled incorrectly.
- The financial-query pipeline loses semantics at multiple stages (prompt schema, parser, fallback parsing, executor), so filters are often widened or dropped.
- Cache invalidation is partially implemented (`sourceHash` exists) but not consistently enforced before artifact reuse.
- Cancellation handling is inconsistent across the batch; several paths catch broad exceptions and suppress coroutine cancellation.
