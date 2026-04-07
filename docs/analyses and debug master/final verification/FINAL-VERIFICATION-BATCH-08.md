# Final Verification — Batch 08: AI Use Cases - Navigation, Review, Sync

## Scope
- `com/yourname/expensetracker/domain/ai/usecase/JudgePendingReviewDuplicateUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/PrioritizeReviewItemsUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/SyncProactiveBriefingWorkUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`
- `com/yourname/expensetracker/domain/ai/usecase/ReceiptAssistInputBuilder.kt`
- `com/yourname/expensetracker/domain/ai/model/AiModels.kt`
- `com/yourname/expensetracker/domain/ai/model/CaptureAssistModels.kt`
- `com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt`
- `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt`
- `com/yourname/expensetracker/domain/ai/policy/AiPolicyImpl.kt`
- `com/yourname/expensetracker/domain/ai/policy/DefaultAiCapabilityRouter.kt`
- `com/yourname/expensetracker/domain/ai/service/CategorizationAssistService.kt`
- `com/yourname/expensetracker/domain/ai/service/DashboardBriefingService.kt`
- `com/yourname/expensetracker/domain/ai/service/QueryInterpretationService.kt`
- `com/yourname/expensetracker/domain/ai/service/ReviewPriorityScorer.kt`
- `com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudDedupeJudgeService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt`
- `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/NoOpReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceCategorizationAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceDashboardBriefingService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceDedupeJudgeService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceQueryInterpretationService.kt`
- `com/yourname/expensetracker/data/ai/provider/OnDeviceReceiptAssistService.kt`
- `com/yourname/expensetracker/data/ai/provider/SmartReceiptAssistService.kt`
- `com/yourname/expensetracker/data/repository/AiSettingsRepositoryImpl.kt`
- `com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt`
- `com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
- `com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt`
- `com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt`
- `com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt`
- `com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`
- `com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`
- `com/yourname/expensetracker/ExpenseTrackerApp.kt`

## Verified Issues
| # | File:Line | Severity | Type | Description | Source | Status | Suggested Fix |
|---|-----------|----------|------|-------------|--------|--------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:69-87` | High | Logic | `force=true` does not bypass the READY-artifact fast path, so manual retry returns stale cached receipt suggestions instead of regenerating them. | B | CONFIRMED | Add `!force &&` to the cache-hit branch before reusing the artifact. |
| 2 | `com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt:56-60` | Medium | Requirements drift | The non-forced path no longer checks whether AI is actually needed; the use case now attempts AI for any receipt with usable OCR. Current UI callers mostly pass `force=true`, so this is less severe than reported, but the use case itself no longer enforces the approved gate. | R | DOWNGRADED | Restore a deterministic `needsAssist()` gate for `!force` calls and keep `force=true` as the explicit override. |
| 3 | `com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt:20-22` | Medium | Logic | `singleOrNull()` silently drops multi-value category, merchant, and transaction-type filters, so drill-down can open a broader transaction list than the interpreted intent. | B | DOWNGRADED | Return `null` when filters are multi-valued or extend `DomainTransactionFilter` to support sets. |
| 4 | `com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt:13` | Low | Compatibility | `QueryMetric.MIN` is explicitly rejected here. The underlying problem is broader than reported—`MIN` is not supported end-to-end—but this file is still one incompatible hop in that pipeline. | D | DOWNGRADED | Either implement `MIN` across interpretation/execution/navigation or remove it from the AI schema until supported. |
| 5 | `com/yourname/expensetracker/domain/ai/usecase/PrioritizeReviewItemsUseCase.kt:30-50` | Medium | Integration | The prioritization use case has no production call site under `app/src/main/java`, so the review-priority feature is currently dead code. | R | DOWNGRADED | Wire it into review-queue loading/UI ordering or remove the unused feature. |
| 6 | `com/yourname/expensetracker/domain/ai/usecase/DedupeJudgeInputBuilder.kt:113-117` | High | Logic | The builder returns `NotNeeded` when there is exactly one candidate, which skips the most common duplicate-judging case. | D | UPGRADED | Change the threshold to `candidates.isEmpty()` so a single candidate still reaches the judge. |
| 7 | `com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt:180-191` | Low | Error handling | When the assist service returns `null`, the use case records the routing reason as the artifact error and surfaces a generic “no supported category” message, masking the real provider failure. | D | CONFIRMED | Distinguish provider failure from “no supported category”, ideally by returning a typed service result instead of nullable output. |
| 8 | `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt:74-76,304-323` | High | Privacy | Cloud receipt assist still uploads the raw receipt image whenever image mode is enabled, even when `redactBeforeCloud=true`. | R | CONFIRMED | Disable image upload when redaction is enabled, or gate it behind a separate explicit opt-in that documents the privacy trade-off. |
| 9 | `com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt:308-313` | Medium | Performance | `buildImageInlineData()` reads the full file into memory before checking `MAX_INLINE_IMAGE_BYTES`, so oversized images pay the allocation cost anyway. | B | DOWNGRADED | Check `file.length()` first and avoid `readBytes()` for files already over the inline limit. |
| 10 | `com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt:50-63` | Low | Testability | Priority time-sensitivity uses `System.currentTimeMillis()` directly instead of the app time abstraction, making tests and time-dependent behavior non-deterministic. | D | DOWNGRADED | Thread `currentTimeMs`/`TimeProvider` through scoring. |
| 11 | `com/yourname/expensetracker/data/ai/provider/OnDeviceDashboardBriefingService.kt:101-106` | Medium | Parsing robustness | JSON extraction uses the first `{` and last `}`, so mixed model output can produce an invalid merged blob and fail parsing. | D | CONFIRMED | Reuse the balanced-brace JSON extractor already used by the cloud parsers. |

## Missed Issues (found during verification but not in either report)
| # | File:Line | Severity | Type | Description | Suggested Fix |
|---|-----------|----------|------|-------------|---------------|
| 1 | `com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt:68-79` | Medium | Cache freshness | The cache reuse check ignores `sourceHash`, so same-day dashboard data changes will keep reusing a stale briefing until TTL expiry even though the use case computes and stores a hash for freshness. | Compute `sourceHash` before the cache check and require `existing.sourceHash == sourceHash` before returning early. |
| 2 | `com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:29-37` | Medium | Logic | `QueryMetric.MIN` is advertised by the interpretation schema but is never executed; structured “smallest/cheapest” queries fall through to `Unsupported`. | Implement a `MIN` execution path (and matching drill-down behavior), or remove `MIN` from the interpretation schema until it is supported. |

## False Positives (issues in original reports that are NOT actually bugs)
| # | Original Report | File:Line | Why It's False Positive |
|---|----------------|-----------|------------------------|
| 1 | Debugger #2 | `SuggestCategoryFallbackUseCase.kt:36` | The report frames this as a medium thread-safety bug. In practice this is an unscoped use case with atomic reference writes; the code may recompute a cheap cache, but there is no demonstrated race-induced corruption or crash. |
| 2 | Debugger #4 | `PrioritizeReviewItemsUseCase.kt:44` | The step-function around `0.7f` matches the documented “high priority first” rule. It may be debatable tuning, but it is not an implementation bug. |
| 3 | Debugger #7 | `CloudCategorizationAssistService.kt:180-191` | Sending raw prompt text when `redactBeforeCloud=false` is the user-selected opt-out behavior. The real privacy bug is the separate merchant-history leak that happens even when redaction is enabled. |
| 4 | Debugger #8 | `JudgePendingReviewDuplicateUseCase.kt:103` | `withRouteDiagnostics` is explicitly defined on `String?`; there is no null crash or functional bug here. |
| 5 | Debugger #9 / CP-4 | `CloudDashboardBriefingService.kt:48`, `CloudQueryInterpretationService.kt:42` | These helper instances are only used for prompt/parsing helpers and never call `getOrCreateModel()`, so they do not instantiate duplicate ML models or double memory use as reported. |
| 6 | Debugger #12 | `CloudDedupeJudgeService.kt:234` | Invalid AI enum values already fail parsing; the report's exception-path description is inaccurate, and the proposed fallback to `UNCERTAIN` is a product choice rather than a correctness bug. |
| 7 | Debugger #15 | `SuggestReceiptExtractionUseCase.kt:169-170` | This depends on a database row violating the non-null Room/entity contract. It is not a bug in normal application behavior. |
| 8 | Debugger #16 | `CloudCategorizationAssistService.kt:60` | The report itself notes this is “not a bug”; replaying the same immutable request during retries is acceptable behavior. |
| 9 | Debugger #17 | `JudgePendingReviewDuplicateUseCase.kt:56` | The `hashCode()` collision argument is purely theoretical here and too speculative to treat as a concrete defect. |

## Cross-Component Pipeline Issues
| # | Pipeline | Severity | Type | Description | Affected Files | Suggested Fix |
|---|----------|----------|------|-------------|----------------|---------------|
| 1 | Receipt assist retry pipeline | High | Cache / UX | The retry path (`force=true`) is broken because receipt-assist cache reuse ignores `force`, so explicit retry actions can return stale data. | `SuggestReceiptExtractionUseCase.kt`, `ReceiptScanViewModel.kt`, `ReviewViewModel.kt` | Add `!force &&` to cache reuse so retry always regenerates. |
| 2 | Single-candidate dedupe pipeline | High | Logic | The dedupe flow short-circuits before AI judging when exactly one candidate exists, which is the common “one suspicious near-match” case. | `DedupeJudgeInputBuilder.kt`, `JudgePendingReviewDuplicateUseCase.kt`, `ReviewViewModel.kt` | Allow one candidate through to the judge; only return `NotNeeded` when the candidate list is empty. |
| 3 | Category assist error propagation | Medium | Error handling | The nullable `CategorizationAssistService` contract collapses missing API key, HTTP/network failure, parse failure, and “no supported category” into `null`, and the use case then persists routing diagnostics as if they were the error. | `CategorizationAssistService.kt`, `CloudCategorizationAssistService.kt`, `OnDeviceCategorizationAssistService.kt`, `SuggestCategoryFallbackUseCase.kt` | Replace the nullable contract with `AiServiceResult<CategoryAssistSuggestion>` and propagate real failure causes. |
| 4 | Category assist cloud redaction | High | Privacy | Redaction is incomplete: merchant/supporting text may be sanitized, but recent same-merchant history is still sent raw to cloud prompts. | `CategorizationAssistInputBuilder.kt`, `CloudCategorizationAssistService.kt` | Sanitize or drop merchant-history hints whenever cloud redaction is enabled. |
| 5 | Query interpretation → execution/navigation | High | Logic | Cloud-redacted aliases are not restored, and the shared structured parser ignores category/period/type constraints, so AI interpretations can broaden queries or fail to match real merchants/categories. | `FinancialQueryInterpretationInputBuilder.kt`, `CloudQueryInterpretationService.kt`, `OnDeviceQueryInterpretationService.kt`, `ExecuteFinancialQueryUseCase.kt`, `MapFinancialQueryToNavigationUseCase.kt` | Restore aliases before returning structured results and extend the parser/schema to preserve category, period, and transaction-type filters. |
| 6 | Query metric support | Medium | Compatibility | The interpretation schema advertises `MIN`, but downstream execution/navigation do not support it. | `OnDeviceQueryInterpretationService.kt`, `CloudQueryInterpretationService.kt`, `ExecuteFinancialQueryUseCase.kt`, `MapFinancialQueryToNavigationUseCase.kt` | Either implement `MIN` end-to-end or remove it from the prompt/schema until supported. |
| 7 | Dashboard briefing cloud redaction | High | Privacy | Raw budget warnings and upcoming-item descriptions are forwarded to cloud briefing prompts even when `redactBeforeCloud=true`. | `DashboardBriefingInputBuilder.kt`, `GenerateDashboardBriefingUseCase.kt`, `CloudDashboardBriefingService.kt` | Build a redacted cloud-safe briefing input or force briefing to on-device/deterministic mode when redaction is enabled. |
| 8 | Dedupe verdict trust boundary | High | Integrity | Model-emitted `matchedTargetType` / `matchedTargetId` values are trusted without checking that they belong to the bounded candidate set supplied to the model. | `CloudDedupeJudgeService.kt`, `OnDeviceDedupeJudgeService.kt`, `JudgePendingReviewDuplicateUseCase.kt` | Validate the returned match against `input.candidates`; clear or downgrade invalid matches before persisting. |
| 9 | Proactive briefing scheduling | Low | Efficiency | Daily briefing work is scheduled from boolean settings alone, even when no dashboard-briefing route can actually run and the worker will just wake up and no-op. | `SyncProactiveBriefingWorkUseCase.kt`, `DefaultAiCapabilityRouter.kt`, `AiWorkSchedulerImpl.kt`, `DailyBriefingWorker.kt`, `GenerateDashboardBriefingUseCase.kt` | Check route availability before scheduling recurring work. |
| 10 | AI settings defaults | Medium | Consistency | `AiSettings()` defaults `receiptAssistEnabled` / `receiptImageCloudEnabled` to `true`, but repository hydration defaults both to `false`, so tests/default UI state diverge from production reads. | `AiModels.kt`, `AiSettingsRepositoryImpl.kt`, `AssistantViewModel.kt` | Make constructor defaults and repository defaults match. |

## Summary
- Total verified issues: 18
- Confirmed: 18 (Critical: 0, High: 7, Medium: 7, Low: 4)
- False positives: 9
- Missed issues found: 2
- Files affected: 24/46

## Key Patterns
- Redaction is applied inconsistently: several cloud paths sanitize some fields but still leak raw contextual data or images.
- Structured AI outputs are under-validated: alias restoration, bounded-candidate checks, and schema completeness are all weaker than the surrounding pipeline assumes.
- Sibling AI use cases do not share consistent cache/retry semantics, leading to user-visible behavior drift (`force=true` works in some flows but not others).
- Configuration defaults and runtime availability checks are not centralized, which creates mismatches between model objects, persisted settings, and background scheduling.
