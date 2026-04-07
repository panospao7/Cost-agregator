# Deep Analysis — Batch 35: AI Services — Remaining & Use Cases (@debugger)

## Scope
- domain/ai/service/DedupeJudgeService.kt
- domain/ai/service/NotificationFallbackParser.kt
- domain/ai/service/QueryInterpretationService.kt
- domain/ai/service/ReceiptAssistService.kt
- domain/ai/service/ReceiptItemCategorizationService.kt
- domain/ai/service/ReviewExplanationService.kt
- domain/ai/service/ReviewPriorityScorer.kt
- domain/ai/service/SemanticDuplicateDetector.kt
- domain/ai/usecase/CategorizationAssistInputBuilder.kt
- domain/ai/usecase/CategorizeReceiptItemsUseCase.kt
- domain/ai/usecase/DashboardBriefingInputBuilder.kt
- domain/ai/usecase/DedupeJudgeInputBuilder.kt
- domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt
- domain/ai/usecase/DetectSemanticDuplicateUseCase.kt
- domain/ai/usecase/ExecuteFinancialQueryUseCase.kt
- domain/ai/usecase/ExplainPendingReviewUseCase.kt
- domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt
- domain/ai/usecase/GenerateDashboardBriefingUseCase.kt
- domain/ai/usecase/GenerateTransactionInsightUseCase.kt
- domain/ai/usecase/GetAiRuntimeStatusUseCase.kt
- domain/ai/usecase/InterpretFinancialQueryUseCase.kt
- domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt
- domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt
- domain/ai/usecase/PrioritizeReviewItemsUseCase.kt

## Issues Found

| # | File:Line | Severity | Type | Description | Reproduction Steps | Suggested Fix |
|---|-----------|----------|------|-------------|-------------------|---------------|
| 1 | InterpretFinancialQueryUseCase.kt:105 | CRITICAL | Logic Error | Operator precedence bug: `normalized == "this week" || normalized == "current week" || normalized.contains("this week") && !normalized.contains("last")` — `&&` binds tighter than `||`. The expression is fragile and the real problem is the inconsistent "this week" period computation. | Query: `"this week"` — takes early-return path with "last 7 days" instead of proper week range | Remove early-return block and let unified `resolvePeriod()` handle it. |
| 2 | InterpretFinancialQueryUseCase.kt:109 | HIGH | Logic Error | Early-return "this week" path calculates period as rolling 7-day window, while `resolvePeriod()` uses Monday–Sunday calendar week. "this week" always gets the wrong period. | Enter query: `"this week"` — returns Wednesday to Wednesday instead of Monday to Sunday. | Remove the early-return block or change it to use `TimePeriodUtils.getWeekRange(now, 0)`. |
| 3 | CategorizeReceiptItemsUseCase.kt:40-41 | HIGH | DI Ambiguity | Constructor takes two params of type `ReceiptItemCategorizationService` but Hilt only binds one implementation (Hybrid). No `@Named` or `@Qualifier` annotations exist. Both params receive the same Hybrid instance, making routing meaningless. | Call `invoke()` with `force=true`. Both ON_DEVICE and CLOUD branches invoke the same Hybrid service. | Add `@Named` qualifiers or refactor to inject a single `ReceiptItemCategorizationService`. |
| 4 | ExecuteFinancialQueryUseCase.kt:186-188 | HIGH | Logic Error | `singleOrNull()` returns `null` when the set has >1 element. Multi-category queries ("groceries and dining") silently drop the filter and return ALL transactions. | Interpret a query matching 2+ categories, then execute it. Expected: filtered. Actual: unfiltered. | Pass the full set down or iterate over multiple filters. |
| 5 | ExplainPendingReviewUseCase.kt:78-96 | MEDIUM | Missing Field | `AiArtifactEntity` created without `targetId = review.id`. Breaks any query that joins on `targetId` for pending review artifacts. | Generate explanation for review #42; query `ai_artifacts` by `targetId = 42` — no results found. | Add `targetId = review.id` to the `AiArtifactEntity` constructor call. |
| 6 | DedupeJudgeInputBuilder.kt:113 | HIGH | Logic Error | `if (candidates.size < 2)` skips AI dedup when only 1 candidate exists. But the AI compares `subject` against `candidates`. With 1 candidate, the comparison is perfectly valid. This check should be `< 1`, not `< 2`. | A pending review has exactly 1 matching expense within 24h. AI dedup is skipped. | Change to `if (candidates.isEmpty())`. |
| 7 | ExecuteFinancialQueryUseCase.kt:106-111 | MEDIUM | Data Inconsistency | `getLargestExpenseForPeriod` SQL orders by raw `amount DESC`, but display uses `effectiveAmount`. For shared expenses, the query returns the expense with highest raw amount but displays the (lower) effective amount. | Create shared expense €200 (your share: €100) and regular expense €150. Query "largest purchase" — returns €200 but shows €100. | Modify SQL to order by effective amount expression. |
| 8 | ExecuteFinancialQueryUseCase.kt:84-85 | MEDIUM | Performance | `intent.filters.merchants.map { MerchantKeyGenerator.generate(it) }` computed inside `.filter{}` lambda, creating a new list for every iteration. O(N×M). | Query merchant breakdown with non-empty merchant filter. | Hoist the map computation before the filter call. |
| 9 | DashboardBriefingInputBuilder.kt:24 | MEDIUM | Thread Safety | `SimpleDateFormat` stored as instance field is not thread-safe. If `build()` is called concurrently, date formatting may produce corrupt output. | Two coroutines call `build()` simultaneously on the same builder instance. | Use `java.time.format.DateTimeFormatter` or create `SimpleDateFormat` locally. |
| 10 | GenerateTransactionInsightUseCase.kt:92-164 | MEDIUM | Semantic Misuse | Repurposes `DashboardBriefingInput` for individual transaction insights by populating it with fake weather/budget data. The AI model receives data that doesn't match the prompt template. | Call `invoke(expense)` — AI receives a "dashboard briefing" prompt with fake weather data. | Create a dedicated `TransactionInsightInput` model and service. |
| 11 | GenerateTransactionInsightUseCase.kt:104 | LOW | Capability Misuse | Uses `AiCapability.DASHBOARD_BRIEFING` for transaction insights. TTL, gating, and artifact queries are all wrong for this use case. | Check artifact table — transaction insights appear as DASHBOARD_BRIEFING artifacts. | Use a dedicated `AiCapability.TRANSACTION_INSIGHT`. |
| 12 | CategorizeReceiptItemsUseCase.kt:70-72 | LOW | Error Semantics | When `parsedItems` is null/blank, returns `CategorizationResult.Error`. This isn't really an error — it's an expected state. | Scan a receipt with no line items → `Error` shown instead of benign "no items" state. | Add a `CategorizationResult.NoItems` sealed variant. |
| 13 | InterpretFinancialQueryUseCase.kt:95-104 | LOW | Code Duplication | "last month" early-return block duplicates logic already in `resolvePeriod()`. | N/A — redundant code path. | Remove early-return blocks and let unified general path handle all queries. |
| 14 | CategorizationAssistInputBuilder.kt:8 | MEDIUM | Layer Violation | Domain layer imports from `data.ai.provider.internal.CloudPiiSanitizer`. Upward dependency from domain → data layer. | N/A — architecture issue. | Extract `CloudPiiSanitizer` into a domain-level interface. |
| 15 | ReviewExplanationInputBuilder.kt:3 / FinancialQueryInterpretationInputBuilder.kt:3 | MEDIUM | Layer Violation | Both files import `data.ai.provider.internal.sha256Prefix`. Domain layer depends on data layer internal utility. | N/A — architecture issue. | Move `sha256Prefix` to a domain-level utility package. |
| 16 | DedupeJudgeInputBuilder.kt:155 / ReceiptItemCategorizationInputBuilder.kt:95 | LOW | Code Duplication | `sha256Prefix()` is duplicated in 5 separate files. PII regex patterns duplicated in 4 files. | N/A — maintenance burden. | Extract to shared `domain.util.PiiSanitizer`. |
| 17 | CategorizeReceiptItemsUseCase.kt:109 | LOW | Fragile Hash | `sourceHash = input.hashCode().toString()` — `hashCode()` is not a reliable content hash. Collection ordering changes produce different hashes. | Two identical inputs with different internal collection ordering produce different hashes → cache miss. | Use a deterministic content hash (SHA-256 of serialized input JSON). |
| 18 | DeliverProactiveBriefingNotificationUseCase.kt:44 | LOW | Hash Collision Risk | `notificationId = targetKey.hashCode()` — 32-bit collision space. Two different target keys could produce the same notification ID. | Theoretical: two `targetKey` values with same `hashCode()`. | Use `abs(targetKey.hashCode())` or more robust ID generation. |

## Cross-Component Pipeline Issues

| # | Pipeline | Severity | Type | Description | Suggested Fix |
|---|----------|----------|------|-------------|---------------|
| 1 | InterpretFinancialQueryUseCase → ExecuteFinancialQueryUseCase | HIGH | Data Loss | Multi-value filter sets (e.g., `categoryIds = setOf(1, 5)`) are silently dropped by `singleOrNull()`. The entire interpret→execute pipeline silently degrades from filtered to unfiltered queries. | Extend `getExpensesPagedDynamic` to accept `Set<Long>` or add validation to reject multi-filter intents. |
| 2 | CategorizeReceiptItemsUseCase routing ↔ DI | HIGH | Dead Code | The on-device/cloud routing logic is dead code because both services are injected as the same Hybrid instance. The use case's routing is redundant and broken. | Remove the dual-service pattern. Inject a single `ReceiptItemCategorizationService`. |
| 3 | DedupeJudgeInputBuilder → JudgePendingReviewDuplicateUseCase | HIGH | False Negative | The `candidates.size < 2` threshold means the most common dedup scenario (1 matching transaction) is skipped. | Change threshold to `candidates.isEmpty()`. |
| 4 | GenerateTransactionInsightUseCase → DashboardBriefingService | MEDIUM | Semantic Mismatch | Transaction insights are generated by feeding fake dashboard data into the briefing service. The AI model receives data that doesn't match the prompt template. | Create a dedicated `TransactionInsightService` or different prompt template. |
| 5 | All Use Cases → AiArtifactEntity sourceHash | MEDIUM | Cache Reliability | Multiple use cases use `input.hashCode().toString()` as `sourceHash`. 32-bit hash is collision-prone and ordering-sensitive. | Standardize on SHA-256 of canonical JSON serialization. |
| 6 | Multiple Use Cases → AiServiceError.toReadableMessage() | LOW | Code Duplication | `toReadableMessage()`, `failureMessage()`, and `toRouteDiagnosticLine()` are duplicated as private functions in 3 files with near-identical logic. | Extract to a shared `AiArtifactHelper` or extension functions. |

## Summary
- **Total issues: 24** (18 file-level + 6 cross-component)
- **Critical: 1**, **High: 6**, **Medium: 8**, **Low: 9**
- **Files with issues: 12/24**

## Key Patterns

### 1. Silent Filter Degradation
The `singleOrNull()` pattern in `ExecuteFinancialQueryUseCase` is a systemic problem: any multi-value filter is silently dropped. Users get broader results than they asked for with no warning.

### 2. Inconsistent "This Week" Definition
Two code paths for "this week" with different definitions (rolling 7 days vs. calendar week). The early-return path always wins for exact matches, so users always get the wrong period.

### 3. DI Ambiguity / Routing Confusion
`CategorizeReceiptItemsUseCase` attempts its own routing via two constructor-injected services, but DI only provides one binding. Dead code that gives a false impression of routing control.

### 4. Duplicated Utility Code
`sha256Prefix()` (5 copies), PII regex patterns (4 copies), `toReadableMessage()` (3 copies) represent significant code duplication.

### 5. Domain → Data Layer Violations
Three domain-layer files import from `data.ai.provider.internal` for `sha256Prefix` and `CloudPiiSanitizer`.

### 6. hashCode() as Content Hash Anti-Pattern
Using `input.hashCode().toString()` for `sourceHash` across all artifact-producing use cases is unreliable. Undermines the cache-freshness mechanism.
