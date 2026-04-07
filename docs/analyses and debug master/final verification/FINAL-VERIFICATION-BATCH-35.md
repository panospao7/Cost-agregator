# Final Verification — Batch 35: AI Services — Remaining & Use Cases

## Scope
- `com/yourname/expensetracker/domain/ai/service/DedupeJudgeService.kt`
- `com/yourname/expensetracker/domain/ai/service/NotificationFallbackParser.kt`
- `com/yourname/expensetracker/domain/ai/service/QueryInterpretationService.kt`
- `com/yourname/expensetracker/domain/ai/service/ReceiptAssistService.kt`
- `com/yourname/expensetracker/domain/ai/service/ReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/domain/ai/service/ReviewExplanationService.kt`
- `com/yourname/expensetracker/domain/ai/service/ReviewPriorityScorer.kt`
- `com/yourname/expensetracker/domain/ai/service/SemanticDuplicateDetector.kt`
- `com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DetectSemanticDuplicateUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/GetAiRuntimeStatusUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/PrioritizeReviewItemsUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt`
- `com/yourname/expensetracker/di/AiModule.kt`
- `com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt`
- `com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`
- `com/yourname/expensetracker/data/database/entity/AiArtifactEntity.kt`
- `com/yourname/expensetracker/data/database/entity/Expense.kt`
- `com/yourname/expensetracker/data/repository/ExpenseRepository.kt`
- `com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
- `com/yourname/expensetracker/domain/ai/model/ReceiptItemCategorizationModels.kt`
- `com/yourname/expensetracker/domain/util/TimePeriodUtils.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt:131-149` | High | State management | After setting the receipt to `ANALYZING`, the null-service path returns `CategorizationResult.Error` without restoring the receipt status, leaving the receipt stuck in an in-progress state. | R | CONFIRMED | Route every post-`ANALYZING` failure exit through one helper that marks the artifact failed and resets the receipt back to `PENDING`. |
| 2 | `com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:53-119` | High | Logic | Grouped and largest-expense queries are executed with special-case repository helpers that ignore parts of the interpreted filter set. Category breakdown ignores merchant/type/ownership/amount filters, merchant breakdown filters only after fetching the top 8 merchants for the whole period, and `MAX` ignores all non-period filters. | R | CONFIRMED | Build grouped/max answers from the same fully-filtered query plan, or add repository queries that accept the full `ExpenseQueryFilters` contract. |
| 3 | `com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:181-188` | High | Data loss | `singleOrNull()` silently drops any multi-value merchant/category/type filter when loading filtered expenses, widening results instead of honoring the interpreted intent. | D | CONFIRMED | Preserve set-valued filters in repository APIs, or reject unsupported multi-value intents explicitly instead of widening them. |
| 4 | `com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:122-192` | High | Incorrect calculation | Filtered `TOTAL`, `COUNT`, and `AVERAGE` answers are computed from `getExpensesPagedDynamic(limit = 500)`, so any query matching more than 500 rows undercounts totals and counts and skews averages. | R | CONFIRMED | Use SQL aggregates/count queries for filtered results, or paginate until exhaustion before aggregating. |
| 5 | `com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:106-111`<br>`com/yourname/expensetracker/data/database/dao/ExpenseDao.kt:581-590` | Medium | Incorrect ranking | “Largest purchase” selects the row with the greatest raw `amount`, then displays `effectiveAmount`. Shared-expense rows can therefore win the SQL query but display a smaller user-owned amount than another transaction. | D | CONFIRMED | Order by the same effective-amount expression used elsewhere for spending calculations. |
| 6 | `com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt:84-115,204-214` | High | Query parsing | Local fallback shortcuts hijack queries containing phrases like `last month`/`this week` and force them into plain `TOTAL` intents, while `resolvePeriod()` treats any `week` token as the current week. Queries such as “top merchants last month” and “last week spending” are misinterpreted. | B | CONFIRMED | Remove the hard-coded `TOTAL` shortcut branches, parse period phrases independently, and resolve `last week`/`this week` explicitly before defaulting. |
| 7 | `com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:68-93` | High | Cache freshness | Dashboard briefing cache reuse checks prompt version and TTL but ignores `sourceHash`, so a READY artifact for the same day suppresses regeneration even when the underlying dashboard snapshot changes. | R | CONFIRMED | Compute the source hash before cache lookup and require `promptVersion + sourceHash` to match before reusing a READY artifact. |
| 8 | `com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt:66-96` | High | Cache freshness | Review explanation cache reuse likewise ignores `sourceHash`, so explanations can stay stale until TTL expiry after the review data changes. | R | CONFIRMED | Apply the same freshness contract used by dedupe judging: `targetKey + capability + promptVersion + sourceHash + TTL`. |
| 9 | `com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt:113-117` | High | Logic | The AI dedupe judge is skipped when there is exactly one candidate, even though the model compares one subject against a candidate list and a single-candidate decision is valid. | B | CONFIRMED | Treat only the empty-candidate case as `NotNeeded`; allow one or more candidates to produce input. |
| 10 | `com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCase.kt:75-165` | High | Privacy / capability misuse | Transaction insights are generated by fabricating a `DashboardBriefingInput`, routing it through `AiCapability.DASHBOARD_BRIEFING`, and logging raw merchant/amount text. That both bypasses the normal cloud-redaction policy and sends a transaction-level use case through the wrong prompt/capability contract. | B | CONFIRMED | Add a dedicated transaction-insight capability/input/service, apply normal `AiPolicy` redaction before any cloud call, and remove raw transaction values from logs. |
| 11 | `com/yourname/expensetracker/domain/ai/usecase/CategorizeReceiptItemsUseCase.kt:33-42`<br>`com/yourname/expensetracker/di/AiModule.kt:148-203`<br>`com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt:15-31` | Medium | DI / design | Both constructor parameters are injected as the same unqualified `ReceiptItemCategorizationService` binding, so use-case-level ON_DEVICE/CLOUD routing is redundant and misleading rather than truly selecting different implementations. | D | DOWNGRADED | Inject a single hybrid service, or add qualifiers and inject the concrete on-device and cloud implementations explicitly. |
| 12 | `com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt:24-57` | Low | Concurrency | `SimpleDateFormat` is kept as shared mutable state inside the builder; concurrent calls can corrupt formatting output because the formatter is not thread-safe. | D | DOWNGRADED | Replace it with `DateTimeFormatter` or create a local formatter per call. |
| 13 | `com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt:8,35-36,80-81`<br>`com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt:3,84-112`<br>`com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt:3,40-58` | Medium | Architecture | Domain-layer builders import `data.ai.provider.internal` sanitization/hash helpers directly, creating upward dependencies on data-layer internals and coupling domain policy code to provider implementation details. | D | CONFIRMED | Move hashing/redaction primitives into a domain/shared utility or policy component and depend on that from both domain and data layers. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:41-49,178-192` | Medium | Incorrect result count | `executeList()` reports `previewCount = preview.size`, but the preview comes from the same 500-row capped query used elsewhere. Once matches exceed 500, the assistant underreports how many transactions were found. | Use a dedicated count query for list results, or rename/present this value as a capped preview size rather than total matches. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | D-1 | `InterpretFinancialQueryUseCase.kt:105` | The problematic behavior is the shortcut branch itself, not Kotlin operator precedence. The expression does not create a separate logic bug beyond the confirmed period/intent misparse already captured above. |
| 2 | D-5 | `ExplainPendingReviewUseCase.kt:78-96` | `targetId` is optional on `AiArtifactEntity`, and the reviewed code retrieves explanation artifacts by `targetKey + capability`, not by `targetId`. No broken lookup path was found. |
| 3 | D-8 | `ExecuteFinancialQueryUseCase.kt:84-85` | The normalized merchant list is rebuilt inside a filter, but it is applied to a result set already capped at 8 rows. This is negligible overhead, not a material performance defect. |
| 4 | D-12 | `CategorizeReceiptItemsUseCase.kt:70-72` | Returning `CategorizationResult.Error` for “no line items” may be a product/API design choice, but the report does not establish it as an actual logic bug in the current codebase. |
| 5 | D-13 | `InterpretFinancialQueryUseCase.kt:95-104` | The duplicated branch is maintenance debt, but the real defect is the branch’s behavior, which is already captured by the confirmed parsing issue. Duplication alone is not a separate bug here. |
| 6 | D-17 | `CategorizeReceiptItemsUseCase.kt:109` | `hashCode().toString()` is a weak artifact hash in general, but this specific use case does not use `sourceHash` for cache freshness checks, so the reported cache-freshness failure mode is not evidenced at this call site. |
| 7 | D-18 | `DeliverProactiveBriefingNotificationUseCase.kt:44` | Using `targetKey.hashCode()` as an Android notification ID is acceptable here; the report describes only a theoretical collision risk, and the suggested fix would not remove collisions anyway. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Query interpretation → execution → navigation | High | Contract drift | The pipeline can express period/grouping/multi-filter intents that downstream code cannot faithfully execute. Fallback interpretation emits intents that execution partially ignores, `singleOrNull()` widens multi-value filters, and navigation drilldowns can only carry one category/merchant/type. | `InterpretFinancialQueryUseCase.kt`, `ExecuteFinancialQueryUseCase.kt`, `MapFinancialQueryToNavigationUseCase.kt`, `FinancialQueryModels.kt` | Define one validated query-plan contract shared by interpretation, execution, and navigation, and reject intents that cannot be represented exactly. |
| 2 | Dashboard/review generation → artifact cache reuse | High | Staleness | `sourceHash` is persisted as part of artifact identity but ignored by cache-hit checks in dashboard briefing and review explanation generation, so the cache layer stores freshness metadata it does not enforce. | `GenerateDashboardBriefingUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `AiArtifactEntity.kt` | Centralize artifact freshness checking in one helper that requires `targetKey + capability + promptVersion + sourceHash + TTL`. |
| 3 | Receipt item categorization routing ↔ DI | Medium | Redundant layering | Routing is decided in the use case and again inside the hybrid service, while unqualified DI collapses both injected service parameters to the same binding. The code suggests two independently selectable engines, but the actual wiring does not match that contract. | `CategorizeReceiptItemsUseCase.kt`, `AiModule.kt`, `HybridReceiptItemCategorizationService.kt` | Either inject one hybrid service and remove duplicate routing, or qualify and inject distinct concrete services. |
| 4 | Cloud AI sanitization / prompt preparation | Medium | Architecture / privacy | Redaction and hashing logic are duplicated across multiple builders, some domain builders depend on `data.ai.provider.internal`, and transaction insight generation bypasses the shared policy path entirely. | `DedupeJudgeInputBuilder.kt`, `CategorizationAssistInputBuilder.kt`, `FinancialQueryInterpretationInputBuilder.kt`, `ReviewExplanationInputBuilder.kt`, `ReceiptItemCategorizationInputBuilder.kt`, `GenerateTransactionInsightUseCase.kt` | Extract sanitization/hash primitives into a single domain-level policy utility and require every cloud-bound AI path to use it. |
| 5 | Artifact hashing across AI outputs | Low | Cache reliability | Several scoped artifact-producing use cases derive `sourceHash` from `hashCode().toString()`, which is weaker and less stable than a canonical content hash for long-lived cache identity. | `GenerateDashboardBriefingUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `JudgePendingReviewDuplicateUseCase.kt`, `GenerateTransactionInsightUseCase.kt`, `CategorizeReceiptItemsUseCase.kt` | Standardize on SHA-256 of canonical serialized input content. |
| 6 | AI use case error/report formatting | Low | Maintainability | `toReadableMessage()`, route-diagnostic formatting, and failure-message assembly are duplicated across multiple AI use cases, increasing drift risk. | `GenerateDashboardBriefingUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `JudgePendingReviewDuplicateUseCase.kt` | Move shared formatting helpers into common extensions/utilities. |

## Summary
- Total verified issues: 13
- Confirmed: 13 (Critical: 0, High: 9, Medium: 3, Low: 1)
- False positives: 7
- Missed issues found: 1
- Files affected: 18/35

## Key Patterns
- Query interpretation, execution, and drilldown mapping do not enforce one shared contract, so intent gets widened or dropped as it flows through the pipeline.
- Artifact freshness logic is inconsistent: some use cases persist `sourceHash`, but only some of them actually use it to decide reuse.
- Transaction insight generation is still a borrowed dashboard-briefing implementation, which creates both privacy and semantic-contract problems.
- Sanitization/hash utilities are duplicated and, in several places, live in the wrong architectural layer.
- Failure cleanup is not centralized, which leads to user-visible state-machine bugs such as receipts remaining stuck in `ANALYZING`.
