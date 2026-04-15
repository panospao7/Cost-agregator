## Technical Plan
### Scope
- In: Batch 9 runtime-hygiene fixes for the three confirmed B.10 high issues only — durable `ExpenseCategoryClassifier` persistence, cold-start-safe `HybridExpenseClassifier` ML usage after restart, and non-destructive `TransactionClassifier` lifecycle handling during app background transitions.
- In: focused regression tests, compile/test verification after each micro-batch, and exact-row documentation closure for the linked B41/B42 reports.
- Out: `FeatureExtractor` timestamp drift, `TransactionClassifier` vocabulary-reset/model-drift cleanup, `HybridExpenseClassifier` stale category cache, dead feature-pipeline expansion, repository heuristics changes, screen/UI redesign, Room/schema work, and any public API break that is avoidable through an additive or compatibility path.

### Files
- create: `docs/plans/PLAN-B10-Batch9-execution.md`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/ExpenseCategoryClassifier.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/intelligence/TransactionClassifier.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifierTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml/ExpenseCategoryClassifierTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/intelligence/TransactionClassifierTest.kt`
- modify (only if required by wiring/constructor compatibility): `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt`
- modify (audit only / expected no-op): `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
- modify (audit only / expected no-op): `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
- modify (audit only / expected no-op): `app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt`
- modify (audit only / expected no-op): `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
- modify (docs after verification PASS): `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify (docs after verification PASS): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41.md`
- modify (docs after verification PASS): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41-DEBUGGER.md`
- modify (docs after verification PASS): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-42.md`
- modify (docs after verification PASS): `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-42-DEBUGGER.md`
- modify (docs after verification PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
- modify (docs after verification PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-42.md`

### Implementation Steps
1. **Objective & Blast Radius**
   - **The Core Issue:** the classifier runtime contract is currently unsafe in three ways: learned category state can be lost because persistence is deferred and fire-and-forget, cold-start ML categorization ignores a valid on-disk model because readiness is inferred from in-memory counters only, and app backgrounding permanently disables `TransactionClassifier` scheduled work by canceling its singleton scope.
   - **Blast Radius:**
     - Domain engines/services: `ExpenseCategoryClassifier`, `HybridExpenseClassifier`, `TransactionClassifier`, `ConfidenceRouter`
     - Downstream repositories/pipelines that consume categorization output: `NotificationProcessingPipeline`, `ReceiptRepository`, `ManualExpenseRepository`, `ReviewQueueRepository`
     - App/application lifecycle wiring: `ExpenseTrackerApp` / `LifecycleObserver`
     - User-visible indirect surfaces: notification auto-processing, manual expense category defaults, review queue suggestions, receipt/reparse categorization, and any screen fed by those repositories
     - Direct UI-file edits currently expected: none beyond application lifecycle wiring
   - **Assumptions / unknowns to keep explicit:**
     - Current production usage shows `ExpenseTrackerApp` as the only in-tree caller of `TransactionClassifier.cleanup()`; if another caller appears during execution, read it before changing semantics.
     - No dedicated production unit tests currently exist for `ExpenseCategoryClassifier` or `TransactionClassifier`; creating focused tests is the safest path.
     - No `docs/reviews/REVIEW-B10*.md` file exists in the current tree; documentation closure must use the existing deep-analysis and final-verification files instead of inventing a new review artifact.

2. **The Single Source of Truth (The Standard)**
   - **Canonical rule:** classifier owners, not callers, must own load state, readiness, persistence durability, and lifecycle safety.
   - Apply one standard across all three fixes:
     1. A learned-model mutation must enter one bounded durable-save path owned by the classifier; persistence cannot depend on an arbitrary 100-sample threshold.
     2. An explicit save API must not return before the write completes successfully or fails visibly.
     3. Runtime “ready/not ready” decisions must consult loaded persisted state, not only live in-memory counters.
     4. Routine app backgrounding may flush or cancel pending jobs, but it must not permanently cancel a process-lifetime scope that later schedules saves/retrains.
   - Preferred implementation direction for compatibility: keep public method signatures stable where possible, move readiness logic inside the classifier boundary, and add an additive lifecycle-safe helper if destructive `cleanup()` semantics cannot be changed safely in place.

3. **File-by-File Execution Checklist**
   - **Micro-batch 1 — Domain Layer: durable category-model persistence**
     - **Dependency:** none
     - **Validation after batch:** `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, targeted `ExpenseCategoryClassifierTest`
     - **Complete when:** a training mutation is persisted through a bounded durable path, `saveModel()` only returns after disk persistence completes, and restart reload uses the just-saved model.
     - **Failure / rollback note:** if a synchronous save introduces main-thread risk, keep the save awaited but move the actual I/O onto an explicit I/O dispatcher; do not revert to fire-and-forget writes.
     - [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/ExpenseCategoryClassifier.kt`
       - Replace the current “persist only after 100 unsaved samples” behavior with a bounded persistence strategy that protects learned corrections from ordinary process death.
       - Make `saveModel()` a real durability boundary: it must await the file write and surface/log failures from the actual I/O path.
       - Preserve current file name and backward compatibility for existing JSON model files; if atomic temp-file + rename is introduced, keep it in the same directory and keep loader compatibility with already-saved files.
       - Do **not** change classifier scoring math, category thresholds, feature extraction surface, or external constructor shape unless an additive/defaulted path is required.
     - [ ] `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml/ExpenseCategoryClassifierTest.kt`
       - Add focused tests for: explicit `saveModel()` awaiting disk completion, persistence of learned state below the old 100-sample threshold, and restart reload from an on-disk model.

     > [!WARNING]
     > - Do **not** widen this batch into `FeatureExtractor.kt`, dead-feature-pipeline work, or category-cache freshness.
     > - Do **not** rename `expense_category_model.json` or break existing model-file readability.

   - **Micro-batch 2 — Domain Layer: cold-start ML readiness in the hybrid classifier**
     - **Dependency:** Micro-batch 1
     - **Validation after batch:** targeted `HybridExpenseClassifierTest`, then `NotificationExpenseDashboardPipelineTest` as an integration guard
     - **Complete when:** after app restart, a dictionary miss can still use a previously persisted NB model without requiring fresh in-memory training in the current process.
     - **Failure / rollback note:** if a readiness fix starts forcing public API churn, stop and move the load-aware decision fully inside the domain classifier boundary instead of changing repository/app callers.
     - [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt`
       - Remove or neutralize the external `nbClassifier.isReady()` runtime gate so persisted on-disk state is not skipped on cold start.
       - Keep `classify(...)` public signature, threshold semantics, fallback order, and caller contract stable.
       - Let the classifier-owned path decide whether ML results are available after load; callers should consume returned ML results or an empty-list/fallback outcome, not infer readiness from raw counters.
       - Do **not** use this batch to fix stale category caching or widen into category repository reactivity.
     - [ ] `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifierTest.kt`
       - Update tests so they validate cold-start persisted-model behavior and empty-result fallback behavior rather than relying on the old `isReady()` gate contract.
     - [ ] `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt`
       - Update only if constructor/setup compatibility requires it; otherwise run unchanged to prove the real classifier wiring still works end-to-end.

     > [!WARNING]
     > - Do **not** convert `ExpenseCategoryClassifier.isReady()` into a breaking suspend/API change unless there is no additive alternative.
     > - Do **not** push readiness work down into repository callers just to preserve the current hybrid bug.

   - **Micro-batch 3 — Domain Layer: `TransactionClassifier` lifecycle hygiene**
     - **Dependency:** none conceptually, but validate after Micro-batch 2 so the full classifier call graph still compiles cleanly
     - **Validation after batch:** targeted `TransactionClassifierTest`, then `ConfidenceRouterTest` and `ConfidenceRouterEdgeCaseTest`
     - **Complete when:** at least one background transition no longer permanently disables later save/retrain scheduling in the same process.
     - **Failure / rollback note:** if changing `cleanup()` semantics is risky, add a clearly named additive lifecycle-safe method and keep `cleanup()` as a compatibility wrapper/deprecation path instead of deleting it.
     - [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/TransactionClassifier.kt`
       - Separate routine background handling from true scope destruction.
       - Preserve the current public surface where possible; if semantics change, do it compatibly and document the new intended usage.
       - Ensure pending save/retrain jobs can be canceled/replaced without killing the parent scope required for future schedules.
       - Do **not** widen this batch into the separate vocabulary-reset bug, broader logging cleanup, or ML feature-engineering changes unless a tiny supporting adjustment is required by touched code.
     - [ ] `app/src/test/java/com/yourname/expensetracker/domain/intelligence/TransactionClassifierTest.kt`
       - Add coverage for repeated background transitions, future training after cleanup/background-safe lifecycle calls, and continued save/retrain scheduling.
     - [ ] `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouterTest.kt`
       - Run as a regression guard; update only if the transaction-classifier compatibility path requires fixture changes.
     - [ ] `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouterEdgeCaseTest.kt`
       - Run as a regression guard; update only if compile/test wiring proves necessary.

     > [!WARNING]
     > - Do **not** introduce `runBlocking`, main-thread file I/O, or long blocking waits from lifecycle callbacks just to force a save.
     > - Do **not** refactor `ConfidenceRouter` heuristics in this batch.

   - **Micro-batch 4 — Data Layer: compatibility audit only**
     - **Dependency:** Micro-batches 1-3 compile successfully
     - **Validation after batch:** compile plus `NotificationExpenseDashboardPipelineTest`
     - **Complete when:** downstream repository/pipeline consumers still compile without signature-breaking workarounds.
     - **Failure / rollback note:** if a repository edit appears necessary, read the file first and keep the change strictly compatibility-only; otherwise stop and re-plan rather than broadening the batch.
     - [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt`
       - Audit call-site compatibility only.
       - Do **not** alter duplicate detection, parser routing, or confidence heuristics here.
     - [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
       - Audit call-site compatibility only.
       - Do **not** change receipt matching/correction logic here.
     - [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt`
       - Audit call-site compatibility only.
       - Do **not** change manual-expense category fallback rules here.
     - [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`
       - Audit call-site compatibility only.
       - Do **not** change review insertion or semantic-deduplication behavior here.

     > [!WARNING]
     > - This batch should not require repository or DAO edits. If you feel compelled to change one, re-read the domain contract first.

   - **Micro-batch 5 — UI / Application Layer: non-destructive background wiring**
     - **Dependency:** Micro-batch 3
     - **Validation after batch:** compile, `TransactionClassifierTest`, and `NotificationExpenseDashboardPipelineTest`
     - **Complete when:** `onStop()` no longer destroys future transaction-classifier work while all other app-start and budget-monitor behavior remains unchanged.
     - **Failure / rollback note:** if lifecycle wiring becomes ambiguous, prefer removing/repointing the destructive transaction-classifier call over adding blocking app-lifecycle work.
     - [ ] `app/src/main/java/com/yourname/expensetracker/ExpenseTrackerApp.kt`
       - Update `LifecycleObserver.onStop()` so normal backgrounding no longer invokes destructive transaction-classifier shutdown semantics.
       - Preserve the `BudgetMonitor` cleanup path, StrictMode setup, WorkManager configuration, and startup scheduling behavior.
       - Do **not** introduce new synchronous disk work or unrelated lifecycle observers.

     > [!WARNING]
     > - No screen/ViewModel changes are expected in this batch.
     > - Do **not** alter unrelated startup or WorkManager behavior.

4. **Verification Plan**
   - **Unit Tests:**
     - Create and run `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml/ExpenseCategoryClassifierTest.kt`
       - Verify explicit save waits for the write to complete.
       - Verify learned state persists before the old 100-sample threshold would have fired.
       - Verify a fresh classifier instance can load and use the persisted model.
     - Update and run `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifierTest.kt`
       - Verify dictionary miss + persisted on-disk model still produces ML categorization after restart.
       - Verify empty ML results still fall back safely.
     - Create and run `app/src/test/java/com/yourname/expensetracker/domain/intelligence/TransactionClassifierTest.kt`
       - Verify repeated cleanup/background-safe lifecycle calls do not permanently cancel future save/retrain scheduling.
       - Verify training after a background transition still produces a future save path.
     - Run existing regression guards:
       - `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouterTest.kt`
       - `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouterEdgeCaseTest.kt`
       - `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt`
   - **Build / syntax after each micro-batch:**
     - `./gradlew.bat :app:compileDebugKotlin`
     - `./gradlew.bat :app:compileDebugUnitTestKotlin`
     - Then run only the smallest targeted tests for that micro-batch before moving on.
   - **Suggested batch-by-batch command order:**
     - After Micro-batch 1:
       - `./gradlew.bat :app:testDebugUnitTest --tests "*ExpenseCategoryClassifierTest"`
     - After Micro-batch 2:
       - `./gradlew.bat :app:testDebugUnitTest --tests "*HybridExpenseClassifierTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*NotificationExpenseDashboardPipelineTest"`
     - After Micro-batch 3:
       - `./gradlew.bat :app:testDebugUnitTest --tests "*TransactionClassifierTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*ConfidenceRouterTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*ConfidenceRouterEdgeCaseTest"`
     - After all code batches:
       - `./gradlew.bat :app:testDebugUnitTest`
   - **Syntax/Lint:** ensure no imports or constructor call sites were broken in source or tests, especially around coroutine APIs, file-I/O utilities, and any lifecycle helper added for `TransactionClassifier`. If lint is already part of the branch workflow, run it only after compile/test is green; do not substitute lint for compile.

5. **Documentation & Registry Updates (CRITICAL)**
   - **Registry Update:** in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, under `### B.10: Categorization/Intelligence Pipeline` → `- **HIGH:**`, mark only the following three bullets as resolved with `**[RESOLVED BY B.10-Batch9]**` (currently the rows at lines 565-567):
     1. `ExpenseCategoryClassifier` deferred/fire-and-forget persistence issue
     2. `HybridExpenseClassifier` cold-start `isReady()` gate issue
     3. `TransactionClassifier.cleanup()` singleton-scope cancellation issue
   - Do **not** mark the entire `### B.10` section as resolved, and do **not** touch adjacent B41/B42 bullets such as vocabulary drift, feature timestamp drift, stale category cache, or dead feature pipeline.
   - **Batch Reports:** after code and tests pass, update only the exact mirrored rows in the affected batch reports:
     - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41.md` — issue 14 (`ExpenseCategoryClassifier` persistence)
     - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41-DEBUGGER.md` — issue 21 (`ExpenseCategoryClassifier` async save race)
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md` — issue 29 (`ExpenseCategoryClassifier` persistence)
     - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-42.md` — issue 8 (`TransactionClassifier.cleanup()`) and issue 10 (`ExpenseCategoryClassifier` async persistence)
     - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-42-DEBUGGER.md` — issue 1 (`ExpenseCategoryClassifier` fire-and-forget save)
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-42.md` — issue 8 (`TransactionClassifier.cleanup()`), issue 10 (`ExpenseCategoryClassifier` async persistence), and Missed Issue #1 (`HybridExpenseClassifier` cold-start persisted-model bug)
   - If no exact matching row exists in another doc, do **not** create a new batch report entry. No `REVIEW-B10*.md` file currently exists; do not invent one unless explicitly requested.

### Risks
- Awaited model persistence can accidentally regress into main-thread I/O if dispatcher boundaries are not enforced.
- Changing readiness behavior can create unnecessary API churn; keep the fix inside the classifier boundary whenever possible.
- Lifecycle cleanup changes can be misinterpreted as a full shutdown contract; preserve compatibility or add a deprecation path instead of silently breaking callers.
- Atomic/compatibility changes to model files can break reload if backward compatibility is not preserved.
- Documentation drift risk is high because only three B.10 bullets are in scope; over-marking the section would be incorrect.

### Acceptance Criteria
- [ ] `ExpenseCategoryClassifier` no longer relies on the old 100-sample-only persistence window for durability, and explicit `saveModel()` does not return before the write completes.
- [ ] A fresh `HybridExpenseClassifier` instance can use a persisted NB model immediately after restart on a dictionary miss.
- [ ] `TransactionClassifier` can still schedule save/retrain work after at least one app background transition in the same process.
- [ ] `ExpenseTrackerApp` no longer destroys transaction-classifier background work during routine `onStop()` handling.
- [ ] No public constructor or caller contract was broken for repository, app, or test consumers without a compatibility path.
- [ ] `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, targeted classifier tests, and the full `:app:testDebugUnitTest` lane pass.
- [ ] `MASTER-ISSUE-REGISTRY.md` and the exact mirrored B41/B42 batch-report rows are updated with `**[RESOLVED BY B.10-Batch9]**` only after verification succeeds.
