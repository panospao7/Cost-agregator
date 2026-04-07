# PLAN A.3 — Non-deterministic Default Values

## 1. Objective & Blast Radius
- **The Core Issue:** Several domain/data/UI paths still read wall-clock time directly via `System.currentTimeMillis()` or implicit “now” logic, instead of using the app’s injected time abstraction. This makes tests non-deterministic, breaks fake-clock/time-travel scenarios, creates equality drift in filter value objects, and can produce midnight-boundary mismatches when different components capture `now` at different moments.
- **Blast Radius:**
  - **AI review prioritization:** `ReviewPriorityModels.kt`, `OnDeviceReviewPriorityScorer.kt`, `PrioritizeReviewItemsUseCase` call paths
  - **AI briefing scheduling + delivery:** `DailyBriefingWorker.kt`, `DashboardBriefingInputBuilder.kt`, `GenerateDashboardBriefingUseCase.kt`, `DeliverProactiveBriefingNotificationUseCase.kt`
  - **Navigation/filter serialization + restoration:** `DomainTransactionFilter.kt`, `TransactionFilter.kt`, `TransactionFilterSerializer.kt`, `TransactionFilterUiMapper.kt`, `MainActivity.kt`, downstream recommendation/navigation flows
  - **Challenge + group expense creation:** `SpendingChallengeManager.kt`, `AddGroupExpenseUseCase.kt`, `SharedExpenseManager.kt`, `GroupsModule.kt`, groups UI/viewmodel compile-neighbors
  - **Investment + routing + ML feature extraction:** `InvestmentTracker.kt`, `ConfidenceRouter.kt`, `FeatureExtractor.kt`, `HybridExpenseClassifier.kt`
  - **Notification/ID audit surfaces:** `NotificationIdGenerator.kt`, any caller still feeding timestamp-derived IDs into it
  - **Audit-only files from the epic that may already be clean but must be rechecked in the same pass:** `ReviewExplanationInputBuilder.kt`, `SharedExpenseBudgetOffsetEngine.kt`
- **Assumptions / unknowns:**
  - `NotificationIdGenerator.kt`, `ReviewExplanationInputBuilder.kt`, and `SharedExpenseBudgetOffsetEngine.kt` may be no-op/audit-only if the current code already has no live wall-clock dependency there; do not force edits just to “touch” the file.
  - `SpendingChallenge` appears to be created in-memory first; if no persistence-backed ID exists, use an explicit non-time-based creation-boundary strategy instead of another timestamp default.
  - The briefing pipeline fix must preserve the existing `dashboard_home:yyyy-MM-dd` target-key contract.

## 2. The Single Source of Truth (The Standard)
- **Canonical rule:** All time references in domain/data/worker code must come from one of two sources only:
  1. a single captured `timeProvider.now()` value obtained at the service/use-case/worker boundary, or
  2. an explicit timestamp argument that already represents the event/record being processed.
- **No direct wall clock:** No direct `System.currentTimeMillis()` calls are allowed in the A.3 target files after this fix.
- **No implicit current time:** No helper may derive “current” date/time via `Calendar.getInstance()`/`Date()` unless it is immediately seeded from an explicit timestamp or captured `timeProvider.now()`.
- **Pure-model rule:** Pure helpers/models (for example `ReviewPriorityModels`) must not inject `TimeProvider`; they must accept `nowMillis` explicitly from a caller that already owns the clock.
- **Value-object rule:** `DomainTransactionFilter` / `TransactionFilter` must not generate trace metadata in primary-constructor defaults from time. If `correlationId` remains on the model, its default must be deterministic and serialization/restoration must preserve explicit values.
- **ID rule:** Timestamp-based IDs are forbidden. Prefer persisted DB IDs, stable existing business keys/hashes, or explicit non-time-based generation at the creation boundary. Do **not** replace one bad default with `UUID.randomUUID()` in a data-class/model constructor default.

## 3. File-by-File Execution Checklist

### Execution order / safe batches
1. **Batch 1 — Deterministic value objects and review-priority clock flow**
   - **Scope:** `ReviewPriorityModels.kt`, `OnDeviceReviewPriorityScorer.kt`, `DomainTransactionFilter.kt`, `TransactionFilter.kt`, `TransactionFilterSerializer.kt`, `TransactionFilterUiMapper.kt`, `MainActivity.kt`
   - **Dependency notes:** Do this first because serializer/state-restoration compatibility must be settled before downstream navigation/recommendation tests can be trusted.
   - **Validation:** `TransactionFilterSerializerTest`, `NavigationTargetResolverTest`, `PrioritizeReviewItemsUseCaseTest`, plus a new focused review-priority determinism test if no direct model test exists.
   - **Complete when:** identical logical filters compare/round-trip deterministically, and review-priority time sensitivity depends only on injected/caller-supplied time.
2. **Batch 2 — Single-capture briefing/ML/routing time propagation**
   - **Scope:** `DailyBriefingWorker.kt`, `DashboardBriefingInputBuilder.kt`, `GenerateDashboardBriefingUseCase.kt`, `DeliverProactiveBriefingNotificationUseCase.kt`, `ConfidenceRouter.kt`, `FeatureExtractor.kt`, `HybridExpenseClassifier.kt`, audit `ReviewExplanationInputBuilder.kt`
   - **Dependency notes:** Worker and generation/delivery must move together; fixing only one side leaves the midnight bug alive.
   - **Validation:** `DailyBriefingWorkerTest`, `GenerateDashboardBriefingUseCaseTest`, `DeliverProactiveBriefingNotificationUseCaseTest`, `ConfidenceRouterTest`, `ConfidenceRouterEdgeCaseTest`, `FeatureExtractorTest`.
   - **Complete when:** one captured `now`/`dateKey` drives the whole daily-briefing run and no targeted AI/routing/feature path reads the real wall clock directly.
3. **Batch 3 — Challenge/group/investment timestamp injection and non-time-based IDs**
   - **Scope:** `SpendingChallengeManager.kt`, `InvestmentTracker.kt`, `AddGroupExpenseUseCase.kt`, `SharedExpenseManager.kt`, `GroupsModule.kt`, audit `NotificationIdGenerator.kt`, audit `SharedExpenseBudgetOffsetEngine.kt`
   - **Dependency notes:** Update constructor injection + direct-instantiation tests in the same batch to avoid a half-migrated build.
   - **Validation:** `GroupUseCasesTest`, `SharedExpenseManagerTest`, `SharedExpenseBudgetOffsetEngineTest`, direct-instantiation integration tests, plus new focused tests for `InvestmentTracker` and `SpendingChallengeManager` if none exist.
   - **Complete when:** no targeted creation path uses time-based IDs/default timestamps and all constructor/DI call sites compile cleanly.
4. **Batch 4 — Docs, registry, and batch-report sync**
   - **Scope:** registry block, affected final-verification files, matching deep-analysis mirrors, architecture maps if injection/time-flow docs changed.
   - **Validation:** only A.3-linked lines are tagged resolved; unrelated A.1/A.2/A.4 findings remain untouched.
   - **Complete when:** the docs reflect the new canonical time-source rule without false-positive resolution tags.

### Domain Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/model/ReviewPriorityModels.kt`
  - Change `ReviewPriorityFactors.fromReview(...)` / `calculateTimeSensitivity(...)` so the caller supplies `nowMillis`; do not read the wall clock inside the companion.
  - Keep the existing time-sensitivity buckets/weights intact unless a tiny guard (for example future timestamps clamping to zero age) is needed for deterministic behavior.
  - Do **not** inject `TimeProvider` directly into this model file.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/util/NotificationIdGenerator.kt`
  - Audit only: confirm this file no longer depends on timestamp-derived inputs for notification IDs.
  - If any helper is added, base it on stable IDs/hashes, not on `timeProvider.now()` or `System.currentTimeMillis()`.
  - Do **not** change existing Int ranges or convert Android notification IDs to UUID/string types.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt`
  - Replace the temporary timestamp-based challenge ID with a non-time-based creation strategy.
  - Capture `now = timeProvider.now()` once in `createChallenge()` and reuse it for `startDate` and `endDate`.
  - Leave challenge-progress math, no-spend logic, and existing model fields unchanged apart from determinism/ID creation.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/model/navigation/DomainTransactionFilter.kt`
  - Remove the time-based default from `correlationId`; keep the field but make the default deterministic (`0L`/sentinel) or require explicit boundary generation while preserving compatibility.
  - Keep all semantic filter fields and names stable.
  - Do **not** change the domain-owned enum types introduced by A.2 in this epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt`
  - Inject `TimeProvider` into the constructor.
  - Replace each direct `System.currentTimeMillis()` with `timeProvider.now()`.
  - Capture `now` once per method and derive all related ranges/timestamps from that single value.
  - Do **not** fold in fee math, all-time-high naming, N+1, or history-aggregation fixes from batch 41 while doing the clock swap.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/FeatureExtractor.kt`
  - Stop deriving notification temporal features from implicit current time.
  - Add an explicit event-time path and/or injected `TimeProvider` so `extractFromNotification(...)` is deterministic.
  - Keep `extractFromExpense(...)` driven by `expense.date`.
  - Do **not** change tokenization, stop words, amount buckets, or feature names.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouter.kt`
  - Replace the remaining `System.currentTimeMillis()` write in `ensureSourceStats()` with `timeProvider.now()`.
  - Keep cache TTL, routing thresholds, and heuristic weights unchanged.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilder.kt`
  - Audit only: verify no direct clock access is introduced by adjacent refactors.
  - Do **not** add `TimeProvider` here unless a compile-neighbor signature change truly forces it.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`
  - Preserve `TimeProvider` as the canonical time source for briefing input creation.
  - Add a compile-safe way to reuse a worker-captured `nowMillis`/`dateKey` so generation and delivery cannot drift across midnight.
  - Keep prompt input fields, sorting, caps, locale, and text formatting behavior stable.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/groups/usecase/AddGroupExpenseUseCase.kt`
  - Remove the `System.currentTimeMillis()` default parameter.
  - Keep backward-compatible call ergonomics by using an overload or internal null-resolution path that falls back to injected `timeProvider.now()` when `date` is omitted.
  - Preserve explicitly supplied `date` values exactly.
  - Do **not** change repository contracts or fold in unrelated validation/boundary refactors.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt`
  - Audit only: confirm this file does not regress by reintroducing direct wall-clock reads.
  - Do **not** use A.3 to rewrite split authority or accrual math here.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt`
  - Inject `TimeProvider`.
  - Replace `System.currentTimeMillis()` when constructing `SharedGroupExpense` with `timeProvider.now()`.
  - Capture the timestamp once per add-expense operation and reuse it.
  - Do **not** change split parsing, balance math, or member-removal behavior in this epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt` *(supporting dependency discovered during audit)*
  - Stop constructing `FeatureExtractor()` directly if the extractor becomes time-aware/injected.
  - Pass through an explicit event timestamp if one is introduced.
  - Do **not** alter classifier thresholds or dictionary/ML fallback order.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCase.kt` *(supporting dependency required for single-clock briefing flow)*
  - Add an overload or internal path that accepts the worker-captured time/day-key so artifact target keys match delivery lookup exactly.
  - Keep target-key format, TTL math, artifact statuses, and cache-freshness semantics unchanged.
- [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCase.kt` *(supporting dependency / regression guard)*
  - Preserve the explicit `dateKey` / `startedAt` contract and ensure upstream changes still compare against the same captured run timestamp.
  - Do **not** change notification copy, engagement gating, or diagnostics semantics.

> [!WARNING]
> Do **not** change the `TimeProvider` interface, create a second clock abstraction, or instantiate `SystemTimeProvider()` manually inside target classes.

> [!WARNING]
> Do **not** replace timestamp-based defaults with `UUID.randomUUID()` or other random generation inside data-class/model constructor defaults. If a non-time-based ID is required, generate it explicitly at the creation boundary or use persisted IDs.

### Data / Infrastructure Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/OnDeviceReviewPriorityScorer.kt`
  - Inject `TimeProvider` and pass `timeProvider.now()` into the deterministic review-priority helper once per scoring operation.
  - Make both batch and single-item base-score paths use the same explicit-time entry point.
  - Do **not** change deterministic/AI blend weights or duplicate-risk heuristics.
- [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorker.kt`
  - Inject `TimeProvider`.
  - Capture `runStartedAt = timeProvider.now()` exactly once at the start of `doWork()`.
  - Derive `dateKey` from that captured value and pass the same captured time/day-key through generation and delivery.
  - Do **not** “fix” this by letting the worker, builder, and use case each call `now()` independently.
  - Do **not** widen this batch into WorkManager retry/constraint policy changes unless a tiny compile-safe overload is unavoidable.
- [ ] `app/src/main/java/com/yourname/expensetracker/di/GroupsModule.kt`
  - Update the manual provider for `AddGroupExpenseUseCase` so `TimeProvider` is supplied.
  - Keep existing repository bindings and scopes unchanged.
- [ ] `app/src/main/java/com/yourname/expensetracker/service/TransactionFilterSerializer.kt`
  - Serialize and deserialize `correlationId` explicitly if it remains on the shared filter model.
  - Preserve backward compatibility for legacy JSON that lacks `correlationId` by using the deterministic sentinel, not a fresh timestamp.
  - Keep current JSON keys and version handling stable unless a version bump is truly necessary; if bumped, the old payload must still deserialize.

> [!WARNING]
> Do **not** change Room entity definitions, `@Entity` annotations, DAO schemas, or migrations to solve A.3.

> [!WARNING]
> For the briefing pipeline, do **not** ship a partial fix in only `DailyBriefingWorker` or only `DashboardBriefingInputBuilder`/`GenerateDashboardBriefingUseCase`. The same captured `now`/`dateKey` must flow end-to-end.

### UI / Presentation Layer
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionFilter.kt`
  - Remove the time-based default from `correlationId` and match the deterministic default chosen for `DomainTransactionFilter`.
  - Keep field order/names stable so state-saving and navigation callers still compile.
  - Do **not** change UI filter semantics or enum sources in this epic.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/mappers/TransactionFilterUiMapper.kt` *(supporting dependency)*
  - Preserve explicit `correlationId` mapping in both directions.
  - Do **not** synthesize a new correlation ID during mapping.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt` *(supporting dependency)*
  - Update saved-state restoration so a missing `correlationId` restores to the deterministic sentinel instead of `System.currentTimeMillis()`.
  - Keep saved field ordering and overall saver shape unchanged for backward compatibility.

> [!WARNING]
> Do **not** remove `correlationId` from the shared filter flow unless serializer, mapper, saved-state restoration, and recommendation/navigation callers are all updated in the same batch. A half-removal will silently drop traceability.

### Rollback / failure-containment notes
- If `ReviewPriorityModels` signature changes ripple too broadly, add a new explicit-time overload and migrate internal callers first; do not keep a `System.currentTimeMillis()` fallback.
- If filter serialization/backward compatibility fails, keep the old payload shape readable and default missing `correlationId` to the deterministic sentinel rather than generating a new value.
- If worker-time propagation fans out through too many public APIs, add overloads that preserve existing call sites while routing all internal paths through the explicit captured-time path.
- Land constructor-injection changes (`SharedExpenseManager`, `AddGroupExpenseUseCase`, `InvestmentTracker`, `FeatureExtractor`, `OnDeviceReviewPriorityScorer`) together with DI/test updates in the same batch so the project never sits in a half-wired state.

## 4. Verification Plan
- **Unit Tests:** update and/or run these tests as the minimum verification set for A.3:
  - `app/src/test/java/com/yourname/expensetracker/service/TransactionFilterSerializerTest.kt`
    - Add `correlationId` round-trip coverage and legacy JSON-without-`correlationId` fallback coverage.
  - `app/src/test/java/com/yourname/expensetracker/service/NavigationTargetResolverTest.kt`
    - Verify deserialized navigation filters preserve explicit `correlationId` and do not synthesize a new one after round-trip.
  - `app/src/test/java/com/yourname/expensetracker/data/ai/worker/DailyBriefingWorkerTest.kt`
    - Inject fake time and assert the worker uses one captured timestamp for both `startedAt` and `dateKey`.
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/GenerateDashboardBriefingUseCaseTest.kt`
    - Assert the stored artifact target key stays aligned with the worker-captured day.
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/DeliverProactiveBriefingNotificationUseCaseTest.kt`
    - Verify delivery still gates on the passed `startedAt` and matching day key.
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/ReviewExplanationInputBuilderTest.kt`
    - Run as regression only; confirm surrounding signature changes did not break this builder.
  - `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/PrioritizeReviewItemsUseCaseTest.kt`
    - Run as regression after the review-priority helper API change.
  - **Create if no direct test exists:** `app/src/test/java/com/yourname/expensetracker/domain/ai/model/ReviewPriorityModelsTest.kt`
    - Verify `timeSensitivity` is stable for a fixed `nowMillis` and does not depend on the wall clock.
  - `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouterTest.kt`
    - Add/adjust coverage so `ensureSourceStats()` uses the injected clock.
  - `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouterEdgeCaseTest.kt`
    - Replace live-time test setup with a fixed/mock time source.
  - `app/src/test/java/com/yourname/expensetracker/domain/intelligence/ml/FeatureExtractorTest.kt`
    - Update constructor/API usage and assert day/hour/weekend features from a fixed event timestamp.
  - **Create if absent:** `app/src/test/java/com/yourname/expensetracker/domain/investment/InvestmentTrackerTest.kt`
    - Verify all time windows/timestamps come from `FakeTimeProvider`, especially `updatePrice()` and history-range methods.
  - **Create if absent:** `app/src/test/java/com/yourname/expensetracker/domain/challenge/SpendingChallengeManagerTest.kt`
    - Verify `createChallenge()` uses injected time for `startDate`/`endDate` and no longer relies on a timestamp-based ID.
  - `app/src/test/java/com/yourname/expensetracker/domain/groups/usecase/GroupUseCasesTest.kt`
    - Update constructor injection for `AddGroupExpenseUseCase`; test omitted-date fallback and explicit-date pass-through.
  - `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerTest.kt`
    - Update constructor injection and assert added expenses use fake time.
  - `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt`
    - Run as regression only; no budget-math changes should appear.
  - `app/src/test/java/com/yourname/expensetracker/domain/util/NotificationIdGeneratorTest.kt`
    - Run as regression only; if any helper changes are required, preserve range/collision guarantees.
  - Update direct-instantiation compile-neighbor tests if constructor signatures change:
    - `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/e2e/GroupSettlementPipelineTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/consistency/FinancialArithmeticPrecisionTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/verification/CrossGroupIntegrationTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/verification/SharedExpenseTest.kt`
  - Run UI regression tests that rely on `TransactionFilter` equality/defaults:
    - `app/src/test/java/com/yourname/expensetracker/ui/MainViewModelStressTest.kt`
    - `app/src/test/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModelStressTest.kt`
- **Syntax/Lint:**
  - Re-run import cleanup and ensure no targeted file still imports or calls `System.currentTimeMillis()` after the fix.
  - Verify no targeted file introduces `UUID.randomUUID()` as a constructor/default-value replacement.
  - Rebuild generated Hilt/worker wiring after constructor changes; minimum bar is a clean `:app:compileDebugKotlin`.
  - Run at least `:app:testDebugUnitTest` after all A.3 edits land.
  - Confirm legacy serialized filters without `correlationId` still deserialize successfully.
  - Confirm no imports were broken by moving to injected `TimeProvider` / explicit `nowMillis` paths.

## 5. Documentation & Registry Updates (CRITICAL)
- **Registry Update:**
  - In `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, mark **only** the exact A.3 block from the supplied registry text as resolved: the heading `### A.3: Non-deterministic Default Values (System.currentTimeMillis, UUID.randomUUID)` plus its `Batches affected`, `Severity`, `Description`, `Affected files`, and `Suggested fix` lines should append `[RESOLVED BY A.3]` once implementation and regression coverage are complete.
  - Do **not** mark adjacent A.x epics resolved.
- **Batch Reports:**
  - Update only the A.3-related issue rows/sections in these final verification files:
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-01.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-16.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-17.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-24.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-34.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-38.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-40.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
    - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-47.md`
  - Be explicit about scope:
    - **Batch 10:** mark only the daily-briefing shared-clock/day-key issue(s).
    - **Batch 16:** mark only the `TransactionFilter` `correlationId` time-based-default issue; do not mark unrelated date-range boundary bugs unless separately fixed.
    - **Batch 24 / 34:** mark only the `ReviewPriorityModels` time determinism issue(s) and `DomainTransactionFilter` correlation/default/serializer issues that are actually fixed by A.3.
    - **Batch 38:** mark only the `SpendingChallengeManager` timestamp-based challenge ID issue.
    - **Batch 40:** mark only the `SharedExpenseManager` / `AddGroupExpenseUseCase` clock-injection findings; do not mark unrelated validation/architecture/split-authority issues.
    - **Batch 41:** mark only the `ConfidenceRouter` clock, `FeatureExtractor` implicit current-time features, and `InvestmentTracker` direct-clock findings; do not mark calculation or N+1 items.
    - **Batch 47:** mark only the non-deterministic default/correlation-id findings for `DomainTransactionFilter`; do not mark ownership-signature/dedup issues unless they are also fixed.
    - **Batches 01 / 07 / 17 / 36:** update only if those reports explicitly mention the same A.3 determinism/clock/default issue family.
  - Update matching deep-analysis mirrors only where they explicitly describe the same A.3 issue family:
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-01.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-01-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-07.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-07-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-10.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-10-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-16.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-16-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-17.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-17-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-24.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-24-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-34.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-34-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-36-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-38.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-38-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-40.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-40-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-41-DEBUGGER.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-47.md`
    - `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-47-DEBUGGER.md`
  - Do **not** bulk-edit unrelated findings in those reports.
- **Architecture Maps:**
  - If this fix changes constructor-injection surfaces or introduces explicit captured-time propagation across the briefing pipeline, update these docs so the canonical time-source rule is visible:
    - `docs/reference/BACKEND-MAP-INDEX.md`
    - `docs/reference/BACKEND-DEPENDENCIES.md`
    - `docs/reference/COMPLETE-BACKEND-MAP.md`
    - `docs/reference/backend-domain-map.md`
    - `docs/reference/backend-di-infrastructure-map.md`
    - `docs/architecture/ARCHITECTURE.md`
    - `docs/architecture/CODEBASE_SEGMENTS.md`
    - mirrored copies under `docs/analyses and debug master/` if those references are maintained in sync
  - The doc update must state explicitly that:
    - `TimeProvider` is the only wall-clock source for the A.3 paths
    - pure model helpers accept explicit `nowMillis` instead of reading the system clock
    - filter correlation metadata no longer uses time-based constructor defaults
    - briefing generation/delivery reuse one captured run timestamp/day key end-to-end
