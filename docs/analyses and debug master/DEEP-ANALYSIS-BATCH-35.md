# Deep Analysis — Batch 35: AI Services — Remaining & Use Cases (@reviewer)

## Scope
- `domain/ai/service/DedupeJudgeService.kt`
- `domain/ai/service/NotificationFallbackParser.kt`
- `domain/ai/service/QueryInterpretationService.kt`
- `domain/ai/service/ReceiptAssistService.kt`
- `domain/ai/service/ReceiptItemCategorizationService.kt`
- `domain/ai/service/ReviewExplanationService.kt`
- `domain/ai/service/ReviewPriorityScorer.kt`
- `domain/ai/service/SemanticDuplicateDetector.kt`
- `domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`
- `domain/ai/usecase/DashboardBriefingInputBuilder.kt`
- `domain/ai/usecase/DedupeJudgeInputBuilder.kt`
- `domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt`
- `domain/ai/usecase/DetectSemanticDuplicateUseCase.kt`
- `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`
- `domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- `domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
- `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `domain/ai/usecase/GenerateTransactionInsightUseCase.kt`
- `domain/ai/usecase/GetAiRuntimeStatusUseCase.kt`
- `domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
- `domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
- `domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt`
- `domain/ai/usecase/PrioritizeReviewItemsUseCase.kt`

## Issues Found
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt:131-149` | HIGH | State management | `itemCategorizationStatus` is set to `ANALYZING` before the AI call, but when the service returns `null` the use case returns `CategorizationResult.Error` without restoring the receipt status. That leaves the receipt stuck in an “analyzing” state even though no work is running. | Funnel all non-success exits through a common failure path that updates the artifact **and** resets the receipt status back to `PENDING`. |
| 2 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:57-75,78-99,102-119` | HIGH | Logic | Filtered/grouped queries are not executed against the interpreted filter set. `executeCategoryBreakdown()` only applies `categoryIds`, `executeMerchantBreakdown()` only post-filters an already top-8 list, and `executeLargest()` ignores merchant/category/ownership/amount filters entirely. Queries like “largest shared grocery expense last month” can return unrelated data. | Introduce repository methods that accept the same `ExpenseQueryFilters` contract, or derive grouped/max results from a fully filtered query plan instead of special-case repository calls. |
| 3 | `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:122-192` | HIGH | Incorrect calculation | Filtered `TOTAL`, `COUNT`, and `AVERAGE` queries are computed from `loadFilteredExpenses()`, which hard-caps the dataset at 500 rows. Any user with more than 500 matching transactions gets undercounted totals/counts/averages. | Replace the 500-row shortcut with SQL aggregate queries, or paginate until exhaustion before aggregating. |
| 4 | `domain/ai/usecase/InterpretFinancialQueryUseCase.kt:84-115,204-214` | HIGH | Query parsing | The local fallback has broad shortcut branches (`contains("last month")`, `contains("this week")`) that immediately force a plain `TOTAL` query, and `resolvePeriod()` treats any `week` token as the current week. That misparses requests such as “top merchants last month” and “last week spending”. | Remove the early hard-coded `TOTAL` returns, parse period phrases independently, and let metric/grouping resolution run for the original query intent. |
| 5 | `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:68-93` | HIGH | Cache freshness | The use case computes a `sourceHash`, but the cache-hit path never compares it. Any READY artifact for the same day suppresses regeneration until TTL expiry, even if the dashboard snapshot changed after new expenses/reviews arrived. | Compute `sourceHash` before the freshness check and require `promptVersion` **and** `sourceHash` to match before reusing a cached artifact. |
| 6 | `domain/ai/usecase/ExplainPendingReviewUseCase.kt:66-96` | HIGH | Cache freshness | Review explanations are treated as fresh solely by `promptVersion` and TTL. If the pending review changes (merchant/category/confidence/explanation), the old artifact is reused for up to 30 days because `sourceHash` is ignored during cache validation. | Include `sourceHash` in the freshness check, mirroring the stricter logic already used in `JudgePendingReviewDuplicateUseCase`. |
| 7 | `domain/ai/usecase/DedupeJudgeInputBuilder.kt:113-117` | HIGH | Logic | The builder refuses to invoke AI when there is exactly one deterministic duplicate candidate. That is the primary case where an AI judge is useful, so the feature gets skipped precisely when a user needs a verdict on one suspicious near-match. | Treat “no candidates” as `NotNeeded`; allow a single candidate to produce a `DedupeJudgeInput`. |
| 8 | `domain/ai/usecase/GenerateTransactionInsightUseCase.kt:75-117,139-165` | HIGH | Privacy / architecture | This use case repurposes the `DASHBOARD_BRIEFING` route to send raw per-transaction merchant/amount text and log it (`weatherSummary`) without going through `AiPolicy` redaction or a dedicated transaction-insight capability. That bypasses the privacy model used elsewhere for cloud-bound inputs. | Introduce a dedicated capability/input builder for transaction insights, apply the normal redaction policy before any cloud call, and remove raw transaction values from logs. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | Query interpretation → execution → navigation | HIGH | Contract drift | `InterpretFinancialQueryUseCase`, `ExecuteFinancialQueryUseCase`, and `MapFinancialQueryToNavigationUseCase` do not share one validated query plan. The interpreter can emit period/filter/grouping intent that execution ignores or truncates, and navigation can silently widen multi-filter drilldowns. | Define one normalized `QueryPlan`/validator that both execution and drilldown mapping consume, and fail fast when a query cannot be represented exactly. |
| 2 | Input builders / use cases → artifact cache | HIGH | Consistency | `DashboardBriefingInputBuilder` and `ReviewExplanationInputBuilder` produce deterministic inputs, but the corresponding use cases compute `sourceHash` after the cache short-circuit and never use it to decide freshness. The artifact layer therefore stores hashes it does not enforce. | Centralize artifact freshness checks in a shared helper that requires `targetKey + capability + promptVersion + sourceHash`. |
| 3 | Cloud privacy sanitization across AI use cases | MEDIUM | Architecture | Redaction/hash logic is duplicated across multiple builders (`DedupeJudgeInputBuilder`, `FinancialQueryInterpretationInputBuilder`, `ReceiptItemCategorizationInputBuilder`) and provider internals (`CloudPiiSanitizer`). `GenerateTransactionInsightUseCase` bypasses that pipeline entirely. | Move sanitization primitives into a single domain-level policy utility and require every cloud-bound AI path to build inputs through it. |

## Summary
- Total issues: 8
- Critical: 0, High: 8, Medium: 0, Low: 0
- Files with issues: 7/24

## Key Patterns
- Cache freshness is implemented inconsistently: some use cases persist `sourceHash` but never validate it before reusing artifacts.
- The financial-query pipeline is fragmented: interpretation, execution, and drilldown mapping each apply different subsets of the query contract.
- Privacy/redaction logic is duplicated across layers, and at least one use case (`GenerateTransactionInsightUseCase`) bypasses the established sanitization path.
- Failure cleanup is not centralized, which leads to state-machine bugs such as receipts remaining in `ANALYZING` after a null-service failure.
