## Technical Plan
### Scope
- In: cancellation-correctness fixes for the exact A.7 file set only: `BudgetMonitor.kt`, `CategorizationAssistInputBuilder.kt`, `InterpretFinancialQueryUseCase.kt`, `DailyBriefingWorker.kt`, `SuggestReceiptExtractionUseCase.kt`, `SuggestCategoryFallbackUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `GenerateDashboardBriefingUseCase.kt`, `InsightsEngine.kt`, `AnomalyAlertOrchestrator.kt`, `ReceiptOcrService.kt`, and `WarrantyExpirationWorker.kt`.
- In: replacing the one suspend-path `runCatching { }.getOrElse { }` in `InterpretFinancialQueryUseCase.kt` with explicit cancellation-aware `try/catch` handling.
- In: adding explicit non-cancellation logging where the current A.7 paths silently downgrade or persist failures without logging.
- In: targeted unit-test updates for cancellation propagation and artifact/worker behavior, plus A.7-only registry/report updates.
- Out: timeout policy changes, retry-policy redesign, cache/sourceHash freshness, routing/redaction ordering, OCR parser logic, notification-id allocation, shared mutable state/thread-safety work (A.8), worker scheduling constraints, query-parsing heuristics, analytics math/model drift, or any Room/entity/schema changes.

### Files
- modify: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorStressTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilderTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineDeepTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineValidationTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineStressTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineEdgeCaseTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestratorTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt`
- modify (docs): `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify (docs): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md`
- modify (docs): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md`
- modify (docs): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md`
- modify (docs): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
- modify (docs, if needed for narrow defensive-hardening note only): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md`
- modify (docs): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-02-DEBUGGER.md`
- modify (docs): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-07-DEBUGGER.md`
- modify (docs): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-10-DEBUGGER.md`
- modify (docs): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36.md`
- modify (docs, only if reviewer wants to document defensive hardening without reclassifying the issue): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-45.md`
- modify (docs, same constraint): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-45-DEBUGGER.md`
- create: `docs/reviews/REVIEW-A7.md`

### Implementation Steps
#### 1. Objective & Blast Radius
- **The Core Issue:** A.7 is a cancellation-contract cleanup. Several suspend/coroutine entrypoints catch `Exception` or use suspend-path `runCatching`, which converts `CancellationException` into fallback values, `Result.success()`, logged no-ops, or persisted `FAILED` AI artifacts.
- **Blast Radius:**
  - **Budget/worker boundaries:** `BudgetMonitor`, `DailyBriefingWorker`, `WarrantyExpirationWorker`
  - **AI request/build/interpret flows:** `CategorizationAssistInputBuilder`, `InterpretFinancialQueryUseCase`
  - **AI artifact writers:** `SuggestReceiptExtractionUseCase`, `SuggestCategoryFallbackUseCase`, `ExplainPendingReviewUseCase`, `GenerateDashboardBriefingUseCase`
  - **Analytics/orchestration paths:** `InsightsEngine`, `AnomalyAlertOrchestrator`
  - **OCR processing service:** `ReceiptOcrService`
  - **Downstream behavioral surfaces:** WorkManager cancellation, structured concurrency, AI artifact status history, analytics cancellation semantics, and log visibility for real non-cancellation failures.
- **Assumptions / Unknowns:**
  - `BudgetMonitor` launches into its own singleton scope, so cancellation assertions in tests may depend on how the shared test dispatcher surfaces uncaught exceptions. Do not add new public APIs just to make that easier.
  - `ReceiptOcrService` has no direct unit test seam today. A.7 should not widen visibility or extract new public helpers only for testability.
  - The AI artifact use cases insert a `RUNNING` tombstone before provider execution. A.7 only prevents cancelled work from being rewritten to `FAILED`; it does **not** add tombstone cleanup or cancellation-status persistence.
  - Several affected files have adjacent non-A.7 findings (timeouts, cache freshness, routing drift, shared mutable state, OCR close/mutex coordination). Those remain deferred.

#### 2. Single Source of Truth (canonical cancellation-handling rule)
- **Canonical A.7 rule:** `CancellationException` is coroutine control flow, not an error result. No A.7 target may convert it into fallback data, `Unsupported`, `Result.success()`, `Result.retry()`, logged no-ops, or persisted `FAILED` artifacts.
- **Required handling standard:**
  1. Any generic `catch (Exception)` around suspend/cancellable work must rethrow `CancellationException` first.
  2. Suspend-path `runCatching { ... }.getOrElse { ... }` is forbidden when it encloses cancellable/provider work; use explicit `try/catch` instead.
  3. Only non-cancellation exceptions may be logged, mapped to fallback values, or persisted as `FAILED`.
  4. Existing non-cancellation business behavior should stay intact for this epic (same fallback values, same worker result policy, same artifact error text policy) unless compile/test precision forces a tiny local change.
  5. Logging must be explicit where current A.7 paths silently suppress non-cancellation failures (`CategorizationAssistInputBuilder`, `InterpretFinancialQueryUseCase`, `SuggestReceiptExtractionUseCase`, `SuggestCategoryFallbackUseCase`, `InsightsEngine`).
- **Out-of-scope guardrails:**
  - Do **not** rewrite singleton scopes, throttling, caches, or thread-safety in `BudgetMonitor`.
  - Do **not** alter worker timeout/retry policy in `DailyBriefingWorker` beyond cancellation passthrough.
  - Do **not** fix `sourceHash`, cache freshness, `force` cache bypass, route re-selection, or prompt redaction issues in AI use cases.
  - Do **not** touch `ReceiptOcrService.close()` vs `recognizerMutex` coordination; that belongs to the separate race-condition issue.
  - Do **not** change analytics formulas, merchant labels, anomaly baselines, recurring-frequency semantics, or query interpretation heuristics under A.7.

#### 3. File-by-file execution checklist grouped into safe micro-batches

##### Batch 1 — Worker and monitor entrypoints (3 files)
- **Scope:** `BudgetMonitor.kt`, `DailyBriefingWorker.kt`, `WarrantyExpirationWorker.kt`
- **Why first:** These are the smallest top-level coroutine boundaries and establish the standard for worker/service cancellation passthrough before touching artifact writers.
- **Validation focus:** existing result semantics remain unchanged for non-cancellation failures; cancellation no longer looks like a normal completion path.
- **Complete when:** each file rethrows cancellation before generic handling and existing non-cancellation behavior still matches today’s contract.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`
  - Add explicit cancellation passthrough at the top of the retry-loop catch path inside `checkBudgets()`.
  - Keep retry count, transient classification, delay timing, and logging for non-cancellation exceptions unchanged.
  - Do **not** address `serviceJob` lifecycle, unsynchronized cache state, or `cleanup()` behavior in A.7.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
  - Re-throw `CancellationException` before the generic catch returns `Result.success()`.
  - Keep the current non-cancellation success policy/comment intact; timeout/retry redesign is not part of this epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt`
  - Re-throw `CancellationException` before the generic catch returns `Result.retry()`.
  - Keep notification and retry behavior unchanged for non-cancellation exceptions.
- [ ] Compile-neighbor audits for Batch 1 (touch only if compile requires it)
  - `app/src/main/java/com/yourname/expensetracker/data/ai/worker/AiWorkSchedulerImpl.kt`
  - `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt` or other lifecycle callers of `BudgetMonitor.cleanup()`
  - `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorStressTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt`

> [!WARNING]
> - Do **not** convert `DailyBriefingWorker` to `Result.retry()` or add timeout logic in this batch.
> - Do **not** fix `BudgetMonitor` scope lifetime, stale cache, or synchronization here.

##### Batch 2 — AI builder/query front door (2 files)
- **Scope:** `CategorizationAssistInputBuilder.kt`, `InterpretFinancialQueryUseCase.kt`
- **Why second:** Small blast radius, direct A.7 catches, and one targeted suspend `runCatching` replacement.
- **Validation focus:** cancellation propagates out of the builder/query provider path; query heuristics and redaction behavior remain unchanged.
- **Complete when:** the builder no longer swallows cancellation, and provider-call fallback in query interpretation uses explicit cancellation-aware `try/catch` with logging.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilder.kt`
  - Update `fetchRecentTransactionHints(...)` so cancellation is rethrown before returning `emptyList()`.
  - Add explicit log output for non-cancellation failures.
  - Leave the local enum parse fallback (`DomainTransactionType.valueOf(...)`) behavior unchanged unless compile precision forces a tiny refactor.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt`
  - Replace the suspend/provider `runCatching { queryInterpretationService.interpret(input) }.getOrElse { ... }` path with explicit `try/catch`.
  - Re-throw `CancellationException` and log non-cancellation failures before returning `Unsupported(...)`.
  - Keep the current fallback parsing behavior untouched; do **not** fix the separate period/intent issues in this epic.
- [ ] Compile-neighbor audits for Batch 2 (touch only if compile requires it)
  - `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/ai/service/QueryInterpretationService.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilderTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt`

> [!WARNING]
> - Do **not** change query parsing heuristics, route-aware redaction, or multi-filter execution in this batch.
> - Do **not** blanket-replace every `runCatching` in these files; only the provider/cancellation-sensitive path is in scope.

##### Batch 3 — AI artifact writers (4 files)
- **Scope:** `SuggestReceiptExtractionUseCase.kt`, `SuggestCategoryFallbackUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `GenerateDashboardBriefingUseCase.kt`
- **Why third:** These files currently turn cancelled provider calls into `FAILED` artifacts or normal error results.
- **Validation focus:** a cancelled provider call must stop before the `FAILED` upsert/error mapping path; non-cancellation failures must still log/persist exactly as before.
- **Complete when:** cancellation bubbles to the caller and no cancelled call writes a new `FAILED` artifact.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`
  - Add explicit `CancellationException` passthrough before the generic catch around `receiptAssistService.suggest(input)`.
  - Add explicit logging for non-cancellation exceptions before the existing `FAILED` artifact upsert.
  - Keep OCR gating, `force`, cache/sourceHash, JSON parsing helpers, and route diagnostics unchanged.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
  - Add explicit cancellation passthrough before the generic catch around `categorizationAssistService.suggest(input)`.
  - Add explicit logging for non-cancellation exceptions before the existing `FAILED` artifact upsert.
  - Keep cached suggestion validation, `force`, and hash/cache issues out of scope for A.7.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCase.kt`
  - Add explicit `catch (CancellationException)` before the generic catch in the generation block.
  - Preserve existing non-cancellation logging and `FAILED` artifact persistence behavior.
  - Do **not** touch `sourceHash` or cache freshness rules here.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`
  - Add explicit `catch (CancellationException)` before the generic catch in the generation block.
  - Preserve existing non-cancellation logging and artifact persistence behavior.
  - Do **not** change time-key derivation, `sourceHash`, or routing metadata logic.
- [ ] Compile-neighbor audits for Batch 3 (touch only if compile requires it)
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelStressTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeViewModelStressTest.kt`

> [!WARNING]
> - A.7 should stop cancelled work from becoming `FAILED`; it does **not** have to clean up the already-written `RUNNING` tombstone.
> - Do **not** touch `sourceHash`, cache-hit validation, `force` semantics, or route-selection behavior in this batch.

##### Batch 4 — Analytics and alert orchestration (2 files)
- **Scope:** `InsightsEngine.kt`, `AnomalyAlertOrchestrator.kt`
- **Why fourth:** both are coordinator-style files with broad catches; they need cancellation correctness without changing the domain math they orchestrate.
- **Validation focus:** cancellation now aborts the overall call; non-cancellation exceptions still degrade gracefully with explicit logs.
- **Complete when:** analytics branch wrappers and top-level alert orchestration no longer swallow cancellation, and current fallback values remain unchanged for real errors.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
  - Update each `async { try { ... } catch (e: Exception) { ... } }` wrapper in `generateInsights(...)` to rethrow `CancellationException`.
  - Log non-cancellation failures with branch-specific context before returning the existing fallback (`null` or `emptyList()`).
  - Preserve the existing fallback assembly in `InsightsSnapshot`; do **not** change merchant labels, anomaly baseline logic, recurring-frequency mapping, or injected-engine delegation in A.7.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestrator.kt`
  - Re-throw `CancellationException` before the current top-level generic catch logs and returns.
  - Keep all dedupe/cooldown/severity behavior unchanged.
- [ ] Compile-neighbor audits for Batch 4 (touch only if compile requires it)
  - `app/src/main/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/analytics/MerchantInsightEngine.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnomalyDetector.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineDeepTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineValidationTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineStressTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineEdgeCaseTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestratorTest.kt`

> [!WARNING]
> - Do **not** turn this batch into an analytics-engine rewrite.
> - Keep every existing fallback value the same for non-cancellation failures.

##### Batch 5 — OCR cancellation boundary only (1 file)
- **Scope:** `ReceiptOcrService.kt`
- **Why fifth:** this file has the highest chance of scope creep; isolating it keeps the batch safe.
- **Validation focus:** cancellation is no longer logged/handled as an OCR failure in suspend/retry paths; no parser/resource-management redesign is introduced.
- **Complete when:** the suspend catch sites and retry helper rethrow cancellation before any fallback/log/wrap behavior.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt`
  - Add explicit `CancellationException` passthrough in the suspend catch blocks that wrap cancellable work:
    - `extractPdfText(...)`
    - `renderPdfFirstPageThumbnail(...)`
    - `processPdfWithOcr(...)`
    - `runWithRetry(...)`
  - Preserve current non-cancellation fallback/wrapping behavior (`""`, `IllegalStateException`, retry/logging) for real errors.
  - Leave cleanup-only close/delete catches unchanged unless compile evidence shows they can actually intercept coroutine cancellation.
  - Do **not** change recognizer lifecycle locking (`recognizerMutex` vs `close()`), OCR parsing, bitmap logic, or helper visibility in A.7.
- [ ] Compile-neighbor audits for Batch 5 (touch only if compile requires it)
  - `app/src/test/java/com/yourname/expensetracker/domain/receipt/BitmapConcurrencyTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStressTest.kt`
  - `app/src/test/java/com/yourname/expensetracker/e2e/ReceiptProcessingPipelineTest.kt`

> [!WARNING]
> - Do **not** fix the `close()`/`recognizerMutex` race here; that is a separate issue track.
> - Do **not** widen API visibility just to test `runWithRetry()`.

##### Batch 6 — Registry and final-verification docs (5 files)
- **Scope:** `MASTER-ISSUE-REGISTRY.md`, `FINAL-VERIFICATION-BATCH-02.md`, `FINAL-VERIFICATION-BATCH-07.md`, `FINAL-VERIFICATION-BATCH-10.md`, `FINAL-VERIFICATION-BATCH-36.md`
- **Why sixth:** these are the concrete verified A.7 rows that should move with the code in the same epic commit.
- **Validation focus:** only A.7 rows are marked resolved or annotated; no unrelated timeout/cache/thread-safety rows are closed.
- **Complete when:** the registry and only the confirmed A.7 verification rows reflect the implemented cancellation fix.

- [ ] `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
  - Append `[RESOLVED BY A.7]` to the exact A.7 block only (the block beginning at `### A.7: Fire-and-Forget Coroutine Anti-Pattern`).
- [ ] `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md`
  - Update only row 14 (`BudgetMonitor.kt:82-93`) as resolved by A.7.
  - Leave row 13 (shared mutable state) and row 15 (scope lifecycle) untouched.
- [ ] `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md`
  - Update only row 3 (`CategorizationAssistInputBuilder.kt:116-117`), row 14 (`InterpretFinancialQueryUseCase.kt:42-48`), and Missed Issue 3 (`ExplainPendingReviewUseCase.kt:125-134`; `GenerateDashboardBriefingUseCase.kt:117-126`).
  - Do **not** mark the query-parsing, cache, or redaction-order issues resolved under A.7.
- [ ] `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md`
  - Update only row 10 (`DailyBriefingWorker.kt:56-60`).
  - Leave row 9 time-key coordination and row 11 scheduling/reliability untouched.
- [ ] `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
  - Update only row 5 (`SuggestReceiptExtractionUseCase.kt:155-166`), row 13 (`InsightsEngine.kt:53-77`), Missed Issue 1 (`SuggestCategoryFallbackUseCase.kt:179-218`), and Missed Issue 3 (`AnomalyAlertOrchestrator.kt:69-145`).
  - Do **not** mark cache/sourceHash, merchant-label, anomaly-baseline, or race-condition items resolved.

> [!WARNING]
> - Do **not** mark Batch 21’s timeout/retry row resolved under A.7; cancellation passthrough is narrower than that issue.
> - Do **not** close Batch 45’s false-positive note unless the reviewer explicitly wants a narrow defensive-hardening annotation without changing its false-positive classification.

##### Batch 7 — Deep-analysis mirror sync (4 primary files + 45 audit-only)
- **Scope:** `DEEP-ANALYSIS-BATCH-02-DEBUGGER.md`, `DEEP-ANALYSIS-BATCH-07-DEBUGGER.md`, `DEEP-ANALYSIS-BATCH-10-DEBUGGER.md`, `DEEP-ANALYSIS-BATCH-36.md`; audit Batch 45 docs only if a narrow note is truly required.
- **Why seventh:** these are the deep-analysis mirrors with explicit A.7 cancellation findings.
- **Validation focus:** only the cancellation-handling rows move; broader reliability/logic findings stay open.
- **Complete when:** the deep-analysis mirrors align with the final-verification A.7 updates, and any Batch 45 note preserves the existing false-positive nuance.

- [ ] `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-02-DEBUGGER.md`
  - Update only Issue 5 (`BudgetMonitor.kt:82-93`) as resolved by A.7.
- [ ] `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-07-DEBUGGER.md`
  - Update only Issue 2 (`CategorizationAssistInputBuilder.kt:116`) and Issue 14 (`InterpretFinancialQueryUseCase.kt:42-48`).
- [ ] `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-10-DEBUGGER.md`
  - Update only Issue 8 (`DailyBriefingWorker.kt:56-61`).
- [ ] `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36.md`
  - Update only Issue 5 (`SuggestReceiptExtractionUseCase.kt:155-166`) and Issue 13 (`InsightsEngine.kt:53-77`).
- [ ] `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-45.md` and `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-45-DEBUGGER.md` — audit only
  - If documentation must mention the new `ReceiptOcrService` cancellation passthrough, add a narrow “defensive hardening landed under A.7” note.
  - Do **not** rewrite the batch history into a confirmed reproduced bug if the reviewer still considers the original “retries cancelled work” claim a false positive.

> [!WARNING]
> - Do **not** touch `DEEP-ANALYSIS-BATCH-21.md` / `DEEP-ANALYSIS-BATCH-21-DEBUGGER.md` unless you can isolate a pure A.7 cancellation sub-row without implying the timeout/retry issue is fixed.

#### 4. Verification Plan
- **Compile after every micro-batch:**
  - `./gradlew.bat :app:compileDebugKotlin`
- **Full unit-test lane after all code batches (Batches 1-5) land:**
  - `./gradlew.bat :app:testDebugUnitTest`

- **Focused verification by batch:**

##### Batch 1 focused verification
- `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
- `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt`
- Add/adjust cancellation assertions where practical:
  - `DailyBriefingWorkerTest`: cancelled generation/delivery should bubble `CancellationException`, not return `Result.success()`.
  - `WarrantyExpirationWorkerTest`: cancelled repository calls should bubble `CancellationException`, not map to `Result.retry()`.
  - `BudgetMonitor` tests: prefer a scheduler-surfaced cancellation assertion if reliable; otherwise keep tests focused on “no fallback/retry branch exercised” and do **not** add API just for testability.

##### Batch 2 focused verification
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/CategorizationAssistInputBuilderTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCaseTest.kt`
- Add cancellation tests that:
  - cancel `expenseRepository.getRecentTransactionsForMerchant(...)` and expect `build(...)` to propagate cancellation
  - make `queryInterpretationService.interpret(...)` throw `CancellationException` and expect `invoke(...)` to propagate instead of returning `Unsupported`

##### Batch 3 focused verification
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/SuggestReceiptExtractionUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/SuggestCategoryFallbackUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ExplainPendingReviewUseCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt`
- Add one cancellation-path test per use case that verifies:
  - provider/service cancellation bubbles to the caller
  - the pre-provider `RUNNING` upsert may still exist
  - no `FAILED` artifact upsert is written on cancellation
  - existing non-cancellation failure tests still pass unchanged

##### Batch 4 focused verification
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineDeepTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineValidationTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineStressTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/analytics/InsightsEngineEdgeCaseTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/alerts/AnomalyAlertOrchestratorTest.kt`
- Add targeted cancellation tests that verify:
  - one cancelled analytics branch cancels `generateInsights(...)` instead of returning a degraded snapshot
  - `AnomalyAlertOrchestrator.checkAndAlert(...)` propagates cancellation instead of logging and swallowing it

##### Batch 5 focused verification
- No direct `ReceiptOcrService` unit test is required unless a minimal in-package test is possible without changing visibility/API.
- Run compile and audit-only neighbor tests if touched:
  - `app/src/test/java/com/yourname/expensetracker/domain/receipt/BitmapConcurrencyTest.kt`
- Prefer static verification over seam-creating refactors for this file.

- **Static / grep checks after Batches 1-5:**
  - Confirm every A.7 code file that still has a generic catch around cancellable work now explicitly rethrows `CancellationException` first.
  - Confirm `InterpretFinancialQueryUseCase.kt` no longer uses suspend-path `runCatching` for `queryInterpretationService.interpret(input)`.
  - Confirm `SuggestReceiptExtractionUseCase`, `SuggestCategoryFallbackUseCase`, `ExplainPendingReviewUseCase`, and `GenerateDashboardBriefingUseCase` do not write a `FAILED` artifact on cancellation.
  - Confirm `InsightsEngine` logs non-cancellation branch failures instead of silently returning fallbacks.
  - Confirm no Room entities, DAO signatures, or public repository APIs changed.

#### 5. Documentation & Registry Updates
- **Registry update:**
  - In `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, mark only the exact A.7 block as resolved with `[RESOLVED BY A.7]`.

- **Final-verification updates — A.7 rows only:**
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md`
    - Resolve only row 14 for `BudgetMonitor` cancellation swallowing.
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md`
    - Resolve only row 3 (`CategorizationAssistInputBuilder`), row 14 (`InterpretFinancialQueryUseCase`), and Missed Issue 3 (`ExplainPendingReviewUseCase` / `GenerateDashboardBriefingUseCase`).
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md`
    - Resolve only row 10 for `DailyBriefingWorker` cancellation swallowing.
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
    - Resolve only row 5 (`SuggestReceiptExtractionUseCase`), row 13 (`InsightsEngine`), Missed Issue 1 (`SuggestCategoryFallbackUseCase`), and Missed Issue 3 (`AnomalyAlertOrchestrator`).
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md`
    - Default: leave untouched because the batch currently classifies the retry symptom as a false positive.
    - If the reviewer wants documentation of the hardening, add a narrow note only; do **not** rewrite the false-positive verdict into a resolved confirmed bug.

- **Deep-analysis updates — A.7 rows only:**
  - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-02-DEBUGGER.md`
    - Resolve only Issue 5 (`BudgetMonitor`).
  - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-07-DEBUGGER.md`
    - Resolve only Issue 2 (`CategorizationAssistInputBuilder`) and Issue 14 (`InterpretFinancialQueryUseCase`).
  - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-10-DEBUGGER.md`
    - Resolve only Issue 8 (`DailyBriefingWorker`).
  - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36.md`
    - Resolve only Issue 5 (`SuggestReceiptExtractionUseCase`) and Issue 13 (`InsightsEngine`).
  - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-45.md`
  - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-45-DEBUGGER.md`
    - Audit only; if updated, note defensive cancellation hardening without reversing the batch’s false-positive nuance.

- **Audit-only / expected no-change docs:**
  - `FINAL-VERIFICATION-BATCH-05.md`, `FINAL-VERIFICATION-BATCH-16.md`, `FINAL-VERIFICATION-BATCH-17.md`, `FINAL-VERIFICATION-BATCH-18.md`, `FINAL-VERIFICATION-BATCH-19.md`, `FINAL-VERIFICATION-BATCH-21.md`, `FINAL-VERIFICATION-BATCH-35.md`, `FINAL-VERIFICATION-BATCH-42.md`, `FINAL-VERIFICATION-BATCH-48.md`
  - `DEEP-ANALYSIS-BATCH-21.md`, `DEEP-ANALYSIS-BATCH-21-DEBUGGER.md`, `DEEP-ANALYSIS-BATCH-35*.md`, `DEEP-ANALYSIS-BATCH-42*.md`, `DEEP-ANALYSIS-BATCH-48*.md`
  - Reason: either no explicit A.7 row exists, or the batch issue is broader than A.7 and should remain open.

### Risks
- `BudgetMonitor` cancellation behavior is hard to assert because the file launches into a private singleton scope; avoid API churn to make that easier.
- AI artifact tests must distinguish “RUNNING written before provider call” from the real A.7 fix (“no `FAILED` write on cancellation”).
- `ReceiptOcrService` has many catch sites; over-editing cleanup/resource catches risks unnecessary churn and crossing into B45/A.8 work.
- Worker/report docs contain broader reliability findings; accidentally marking those resolved would overstate what A.7 fixed.
- `InsightsEngine` has multiple adjacent confirmed issues; adding logging/passthrough must not become a coordinator refactor.

### Acceptance Criteria
- [ ] All 12 A.7 code files now preserve `CancellationException` instead of swallowing it.
- [ ] `InterpretFinancialQueryUseCase.kt` no longer uses suspend-path `runCatching` around `queryInterpretationService.interpret(...)`.
- [ ] `SuggestReceiptExtractionUseCase`, `SuggestCategoryFallbackUseCase`, `ExplainPendingReviewUseCase`, and `GenerateDashboardBriefingUseCase` no longer persist `FAILED` artifacts for cancelled provider calls.
- [ ] `DailyBriefingWorker` and `WarrantyExpirationWorker` propagate cancellation instead of mapping it to `Result.success()` / `Result.retry()`.
- [ ] `InsightsEngine` logs non-cancellation branch failures and rethrows cancellation while preserving current fallback values for genuine errors.
- [ ] `ReceiptOcrService` rethrows cancellation in its retry/suspend catch paths without introducing OCR-service API changes.
- [ ] No Room schema/entity/API drift is introduced.
- [ ] `./gradlew.bat :app:compileDebugKotlin` passes after each micro-batch.
- [ ] `./gradlew.bat :app:testDebugUnitTest` passes after all A.7 code batches land.
- [ ] Registry and only A.7-related verification/deep-analysis rows are updated in the same epic commit.
