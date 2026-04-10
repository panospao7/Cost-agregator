## Technical Plan
### Scope
- In: exact A.8 thread-safety/state cleanup for the supplied file set only: `WarrantyTextExtractor.kt`, `AccountingExporters.kt`, `DashboardBriefingInputBuilder.kt`, `SpendingThresholdCalculator.kt`, `BudgetMonitor.kt`, `HybridReceiptAssistService.kt`, `TransactionClassifier.kt`, `domain/groups/GroupTransactionCoordinator.kt`, `data/database/GroupTransactionCoordinator.kt`, `RecommendationStateManager.kt`, `ServiceDiagnostics.kt`, `LogSanitizer.kt`, and `LocationResolver.kt`.
- In: replacing shared `SimpleDateFormat` instance state with immutable `java.time` formatters, synchronizing singleton cache/throttle/shared state, removing cross-request singleton metadata, adding focused tests, and performing A.8-only documentation updates.
- Out: Room/entity/schema/migration changes; export-account or money-format correctness fixes; warranty age-window/regex logic changes; recommendation ranking/reactivity redesign; transaction-classifier lifecycle/model-drift fixes; location privacy-hash/cache-semantics fixes; GroupTransactionCoordinator TOCTOU/atomicity refactors; and `NotificationCaptureService.kt` (mentioned in the registry description but omitted from the supplied affected-file list, so do not widen into it under this epic).

### Files
- modify: `app/src/main/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt`
- modify (audit only / likely no-op): `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingThresholdCalculator.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/service/RecommendationStateManager.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/debug/ServiceDiagnostics.kt`
- modify (audit only / likely no-op): `app/src/main/java/com/yourname/expensetracker/domain/intelligence/TransactionClassifier.kt`
- modify (audit only / likely no-op): `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt`
- modify (audit only / likely no-op): `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
- modify (audit only / likely no-op): `app/src/main/java/com/yourname/expensetracker/data/location/internal/LogSanitizer.kt`
- modify (audit only / likely no-op): `app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractorTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/export/CsvEscapingTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingThresholdCalculatorTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetMonitorStressTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/e2e/BudgetAlertPipelineTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/data/ai/provider/HybridServiceDelegationTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/service/RecommendationLifecycleManagerTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/service/RecommendationDismissalHandlerTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/ui/screens/home/HomeViewModelRecommendationTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelStressTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/location/LocationResolverTest.kt`
- modify (tests as needed): `app/src/test/java/com/yourname/expensetracker/domain/location/LocationResolverStressTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/service/RecommendationStateManagerTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/domain/debug/ServiceDiagnosticsTest.kt`
- modify (docs after review PASS): `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify (docs after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-01.md`
- modify (docs after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md`
- modify (docs after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md`
- modify (docs after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md`
- modify (docs after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-25.md`
- modify (docs after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md`
- modify (docs after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md`
- modify (docs audit only after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-11.md`
- modify (docs audit only after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-15.md`
- modify (docs audit only after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-27.md`
- modify (docs audit only after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-28.md`
- modify (docs audit only after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-34.md`
- modify (docs audit only after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-41.md`
- modify (docs audit only after review PASS): `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-42.md`
- modify (docs after review PASS): matching `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-01*.md`, `02*.md`, `07*.md`, `10*.md`, `25*.md`, `36*.md`, and `45*.md` files that contain the exact A.8 rows for the changed files
- modify (docs audit only after review PASS): matching `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-11*.md`, `15*.md`, `27*.md`, `28*.md`, `34*.md`, `41*.md`, and `42*.md` files only if reviewer confirms an exact A.8 row was actually resolved

### Implementation Steps
1. **Objective & Blast Radius**
   - **The Core Issue:** several singleton/domain service classes still keep shared mutable formatter, cache, or request-metadata state that can be touched from concurrent coroutine/service entrypoints. A.8 fixes only those shared-state hazards; it is not a general concurrency or architecture rewrite.
   - **Blast Radius:** warranty receipt import, accounting export generation, adaptive analytics/budget monitoring, receipt-assist metadata delivery, recommendation UI state publication, and persisted service diagnostics.
   - **Assumptions / unknowns to keep explicit during execution:**
     - `DashboardBriefingInputBuilder.kt` already appears compliant because it uses `DateTimeFormatter`; expect audit-only/no code change unless a hidden mutable formatter or shared state is discovered.
     - `TransactionClassifier.kt` already has a `Mutex`; expect audit-only/no code change unless a mutable map escapes the existing lock discipline.
     - Two `GroupTransactionCoordinator.kt` files exist. Under A.8, only shared mutable-state/thread-safety concerns are in scope; do **not** broaden into validation/transaction-boundary fixes unless a genuine state-sharing bug is present in the current file.
     - `LogSanitizer.kt` and `LocationResolver.kt` look tied to privacy/cache semantics, not shared mutable-state defects. Audit them, but do not opportunistically land batch-30/42 fixes under A.8.
     - `AccountingExporters.kt`, `ServiceDiagnostics.kt`, and `RecommendationStateManager.kt` may map to verification rows outside the supplied batch list. Documentation updates must be exact-row, reviewer-confirmed exceptions only; do not invent or over-resolve unrelated rows.

2. **Single Source of Truth (canonical thread-safety/state rule)**
   - **Canonical A.8 rule:** any state stored longer than one call on a singleton/service/builder must be either immutable, eliminated from the object entirely, or guarded by one clear synchronization owner.
   - Apply these standards consistently:
     1. Shared date formatting/parsing state must use immutable `java.time` formatters. No shared `SimpleDateFormat` fields remain in A.8 target files.
     2. Coroutine-owned multi-field mutable state (cache + timestamp, user-id + publish token, etc.) must live behind one `Mutex` or one explicit synchronized state owner. No mixed guarded/unguarded writes.
     3. Independent keyed caches may use `ConcurrentHashMap` only when the operation is truly key-local. If correctness depends on multiple fields moving together, prefer one guarded state block instead.
     4. Non-suspend framework callbacks (for example, `SharedPreferences` diagnostics counters) should use the narrowest compatible JVM lock/atomic approach rather than introducing blocking coroutine bridges.
     5. Request-scoped execution metadata must be returned in immutable result models, not remembered in singleton `var` state.
     6. If a current file is already compliant, leave it unchanged and carry that no-op confirmation into review/docs. Do **not** manufacture edits.

3. **File-by-file execution checklist grouped into safe micro-batches**

   #### Batch 1 — Formatter thread safety (3 files)
   - **Scope:** `WarrantyTextExtractor.kt`, `AccountingExporters.kt`, `DashboardBriefingInputBuilder.kt`
   - **Why first:** isolated formatter-state fixes, low API risk, and easiest place to establish the A.8 immutable-formatter standard.
   - **Validation:** `WarrantyTextExtractorTest.kt`, `CsvEscapingTest.kt`; compile-neighbor only for `GenerateDashboardBriefingUseCaseTest.kt` if builder code changes.
   - **Complete when:** no shared `SimpleDateFormat` field remains in the modified files, and `DashboardBriefingInputBuilder.kt` is either untouched with explicit audit confirmation or still uses only immutable formatter state.
   - **Failure / rollback note:** if `java.time` parsing changes OCR acceptance because of case/locale behavior, re-scope to a narrower case-insensitive `DateTimeFormatter` conversion rather than rewriting warranty parsing rules.

   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt`
     - Replace the shared `SimpleDateFormat` formatter list with immutable `java.time` formatter definitions.
     - Preserve the current supported date patterns and locale intent (`Locale.getDefault()` vs `Locale.US`).
     - Preserve current purchase-date sanity-window and regex behavior; do **not** fold in batch-45 logic fixes about one-year cutoffs or multiline matching.
     - Keep warranty extraction API and return model unchanged.
   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt`
     - Replace the three shared `SimpleDateFormat` fields with immutable `java.time` formatters.
     - Preserve exporter constructors, headers, escaping rules, and existing amount string behavior.
     - Do **not** address TRNS/SPL account semantics or fixed-point money formatting under A.8.
   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/DashboardBriefingInputBuilder.kt`
     - Audit the file first.
     - If it already uses only immutable `DateTimeFormatter` state, leave it untouched and record it as A.8-compliant.
     - Do **not** change `dateKey` semantics, timezone choice, or briefing input contents just to “touch” the file.

   > [!WARNING]
   > - Do **not** change warranty extraction business rules or export correctness issues in this batch.
   > - Do **not** refactor `DashboardBriefingInputBuilder` if the current branch is already compliant.

   #### Batch 2 — Cache and throttle synchronization (2 files)
   - **Scope:** `SpendingThresholdCalculator.kt`, `BudgetMonitor.kt`
   - **Why second:** these are the concrete singleton cache/throttle hotspots named in the epic and have focused test surfaces.
   - **Validation:** `SpendingThresholdCalculatorTest.kt`, `BudgetMonitorTest.kt`, `BudgetMonitorStressTest.kt`, `BudgetAlertPipelineTest.kt`, `DashboardFollowThroughEngineTest.kt`.
   - **Complete when:** cache/throttle state is no longer read/written/removed through unsynchronized singleton fields.
   - **Failure / rollback note:** if one synchronization strategy forces method-signature changes or deadlocks, fall back to a narrower split (for example, atomic scalar + guarded cache block) instead of re-architecting service scope/lifecycle behavior.

   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingThresholdCalculator.kt`
     - Protect `cache` lookup/update/remove through one dedicated synchronization primitive.
     - Keep TTL, user-keying, percentile math, and the current DAO query untouched.
     - Do **not** fix the separate `effectiveAmount` vs `amount` issue under A.8.
   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetMonitor.kt`
     - Guard `lastCheckTime`, `cachedStatuses`, and `cacheTimestamp` as one coherent shared state owner.
     - Preserve the existing public API (`checkBudgets()`, `cleanup()`), retry rules, cooldown rules, cache duration, and notification text.
     - Do **not** widen into service-scope lifetime, cleanup semantics, or broader stale-cache invalidation redesign.

   > [!WARNING]
   > - Do **not** change `BudgetMonitor` business logic, retry policy, or lifecycle semantics here.
   > - Do **not** change `SpendingThresholdCalculator` query semantics or user-model assumptions here.

   #### Batch 3 — Request-scoped receipt metadata (1 file)
   - **Scope:** `HybridReceiptAssistService.kt`
   - **Why third:** this is a small, self-contained singleton-state leak with an existing result model that already carries the correct metadata.
   - **Validation:** `HybridServiceDelegationTest.kt`; compile-neighbor audit of `ReceiptAssistService.kt`, `CaptureAssistModels.kt`, and receipt-assist use cases if signatures or assumptions shift.
   - **Complete when:** `HybridReceiptAssistService` no longer stores `lastUsedImageInput` or any equivalent cross-request mutable singleton metadata.
   - **Failure / rollback note:** if interface compatibility becomes unclear, preserve the existing public method surface and move active callers to result metadata instead of introducing a new shared cache or request map.

   - [ ] `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptAssistService.kt`
     - Remove the singleton `lastUsedImageInput` state.
     - Treat `ReceiptAssistSuggestion.usedImageInput` as the canonical request-scoped source of truth.
     - Keep routing/delegation behavior unchanged.
     - Preserve `ReceiptAssistService` compatibility; if `usedImageInput(input)` must remain, make it stateless/compatibility-only.

   > [!WARNING]
   > - Do **not** refactor `SmartReceiptAssistService`, cloud/on-device retry policy, or DI binding selection under A.8.

   #### Batch 4 — Recommendation state synchronization (1 file + new test)
   - **Scope:** `RecommendationStateManager.kt`
   - **Why fourth:** this is the highest-risk state-publication hotspot because background refresh jobs and in-memory UI state can race each other.
   - **Validation:** create `RecommendationStateManagerTest.kt`; rerun `RecommendationLifecycleManagerTest.kt`, `RecommendationDismissalHandlerTest.kt`, and `HomeViewModelRecommendationTest.kt`.
   - **Complete when:** current-user state ownership and refresh publication are synchronized so stale refreshes cannot overwrite newer state unsafely.
   - **Failure / rollback note:** if a fix tries to grow into a full reactivity redesign, stop at publish-guarding/serialization and defer broader freshness/ranking behavior to the owning issues.

   - [ ] `app/src/main/java/com/yourname/expensetracker/service/RecommendationStateManager.kt`
     - Serialize or generation-guard shared state changes involving `currentUserId` and `_recommendations` publication.
     - Prevent an older refresh job from publishing after a newer refresh or user switch.
     - Route `dismiss`, `removeFromState`, `clear`, and `clearForUser` mutations through the same state owner/guard if they can race the refresh path.
     - Keep public APIs stable.
     - Do **not** use A.8 to fix priority ordering, always-refresh policy, or repository observation architecture unless a tiny current-user consistency check is absolutely required by the synchronization change.
   - [ ] `app/src/test/java/com/yourname/expensetracker/service/RecommendationStateManagerTest.kt`
     - Add focused concurrency/state-ownership coverage for stale publish prevention and current-user-safe in-memory mutation.

   > [!WARNING]
   > - Do **not** broaden into the batch-20/21 recommendation reactivity backlog beyond the minimum synchronization guard.

   #### Batch 5 — Diagnostics counter synchronization (1 file + new test)
   - **Scope:** `ServiceDiagnostics.kt`
   - **Why fifth:** small change surface, but it needs a dedicated guard because the API is non-suspend and backed by `SharedPreferences`.
   - **Validation:** create `ServiceDiagnosticsTest.kt`; rerun `DebugViewModelStressTest.kt`.
   - **Complete when:** counter increments/resets/snapshots can no longer lose updates because of unsynchronized read-modify-write on shared preferences.
   - **Failure / rollback note:** if the first test harness is too heavy, keep the code change narrow and verify through one small Robolectric test plus compile; do not replace the storage backend under this epic.

   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/debug/ServiceDiagnostics.kt`
     - Add a single private synchronization primitive around counter writes and snapshot reads.
     - Preserve method signatures and preference keys.
     - Ensure `getStats()` reads a consistent snapshot under the same guard.
   - [ ] `app/src/test/java/com/yourname/expensetracker/domain/debug/ServiceDiagnosticsTest.kt`
     - Add focused coverage for repeated counter updates and consistent snapshot reads using a real/shared-preference-backed test harness.

   > [!WARNING]
   > - Do **not** move the class between layers or replace `SharedPreferences` with a new store under A.8.

   #### Batch 6 — Audit-only / no-change confirmation (5 files)
   - **Scope:** `TransactionClassifier.kt`, `domain/groups/GroupTransactionCoordinator.kt`, `data/database/GroupTransactionCoordinator.kt`, `LogSanitizer.kt`, `LocationResolver.kt`
   - **Why sixth:** the supplied epic file list includes these files, but the current branch appears to tie them to other concerns. They must be explicitly audited so coding agents do not broaden scope.
   - **Validation:** compile; if any of these files is touched unexpectedly, rerun the smallest relevant neighbors (`GroupTransactionCoordinatorTest.kt`, `LocationResolverTest.kt`, `LocationResolverStressTest.kt`).
   - **Complete when:** each file is either left untouched with a documented no-op rationale or changed only for a genuinely discovered A.8 shared-state defect.
   - **Failure / rollback note:** if any file in this batch turns out to need a larger privacy/cache/transaction/lifecycle fix, stop and defer it to the owning epic or verification issue instead of folding it into A.8.

   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/intelligence/TransactionClassifier.kt`
     - Audit current mutable maps and state accesses.
     - If existing `Mutex` coverage already protects the mutable model state, leave the file unchanged.
     - Do **not** touch lifecycle, vocabulary-drift, or logging issues here.
   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupTransactionCoordinator.kt`
     - Audit only.
     - Do **not** change API contracts or expand into transactional/TOCTOU fixes under A.8 unless a real shared mutable-state issue exists in the current file.
   - [ ] `app/src/main/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinator.kt`
     - Audit only.
     - Do **not** convert validation/write flows to broader transactional refactors under this epic.
   - [ ] `app/src/main/java/com/yourname/expensetracker/data/location/internal/LogSanitizer.kt`
     - Audit only.
     - No code change is expected for A.8; do **not** land privacy-hash hardening under this epic.
   - [ ] `app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt`
     - Audit only.
     - No code change is expected for A.8 unless a hidden shared mutable-state field is found.
     - Do **not** address cache semantics or local log-anonymizer strength here.

   > [!WARNING]
   > - Audit-only means audit-only. If no A.8 state-sharing defect is present, leave the file untouched.

4. **Verification Plan**
   - **Compile after every code batch (Batches 1-6):**
     - `./gradlew.bat :app:compileDebugKotlin`
   - **Full unit-test lane after all code batches land:**
     - `./gradlew.bat :app:testDebugUnitTest`
   - **Focused verification by batch:**
     - **Batch 1:**
       - `./gradlew.bat :app:testDebugUnitTest --tests "*WarrantyTextExtractorTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*CsvEscapingTest"`
       - If `DashboardBriefingInputBuilder.kt` changes at all: `./gradlew.bat :app:testDebugUnitTest --tests "*GenerateDashboardBriefingUseCaseTest"`
     - **Batch 2:**
       - `./gradlew.bat :app:testDebugUnitTest --tests "*SpendingThresholdCalculatorTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*BudgetMonitorTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*BudgetMonitorStressTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*BudgetAlertPipelineTest"`
     - **Batch 3:**
       - `./gradlew.bat :app:testDebugUnitTest --tests "*HybridServiceDelegationTest"`
     - **Batch 4:**
       - `./gradlew.bat :app:testDebugUnitTest --tests "*RecommendationStateManagerTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*RecommendationLifecycleManagerTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*RecommendationDismissalHandlerTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*HomeViewModelRecommendationTest"`
     - **Batch 5:**
       - `./gradlew.bat :app:testDebugUnitTest --tests "*ServiceDiagnosticsTest"`
       - `./gradlew.bat :app:testDebugUnitTest --tests "*DebugViewModelStressTest"`
     - **Batch 6:**
       - No dedicated new tests unless one of the audit-only files is actually modified.
       - If modified unexpectedly, run the smallest relevant test target only (`*GroupTransactionCoordinatorTest`, `*LocationResolverTest`, `*LocationResolverStressTest`).
   - **Static/reviewer anti-pattern checks after Batches 1-6:**
     - Confirm `WarrantyTextExtractor.kt` and `AccountingExporters.kt` no longer store shared `SimpleDateFormat` state.
     - Confirm `HybridReceiptAssistService.kt` no longer contains `lastUsedImageInput` or equivalent singleton metadata.
     - Confirm `SpendingThresholdCalculator.kt` no longer uses an unguarded singleton cache path.
     - Confirm `BudgetMonitor.kt` no longer performs unguarded access to `lastCheckTime`, `cachedStatuses`, and `cacheTimestamp`.
     - Confirm `RecommendationStateManager.kt` no longer publishes stale refresh results without a current-user/generation guard.
     - Confirm no Room entities, schemas, migrations, or public repository APIs changed.

5. **Documentation & Registry Updates**
   - **Documentation order (must follow the playbook):**
     1. `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
     2. exact final-verification A.8 rows only
     3. matching deep-analysis mirror rows only
   - **Registry update:**
     - Mark only the exact `### A.8: Shared Mutable State / Thread Safety Gaps` block as `[RESOLVED BY A.8]` after code batches complete and the reviewer gives PASS.
   - **Final-verification files expected to receive A.8 row updates (only rows that mention the changed A.8 symptom/file):**
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-01.md` — `SpendingThresholdCalculator` cache thread-safety row only.
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-02.md` — `BudgetMonitor` shared mutable-state/cache row only.
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-07.md` — `DashboardBriefingInputBuilder` formatter-state row only, and only if reviewer confirms the current branch is already A.8-compliant.
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-10.md` — `HybridReceiptAssistService` singleton metadata row only.
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-25.md` — `HybridReceiptAssistService` singleton metadata row only.
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-36.md` — `SpendingThresholdCalculator` cache-concurrency row only.
     - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-45.md` — `WarrantyTextExtractor` shared formatter/thread-safety row only.
   - **Final-verification audit-only files from the supplied A.8 batch list:**
     - `FINAL-VERIFICATION-BATCH-11.md`, `15.md`, `27.md`, `28.md`, `34.md`, `41.md`, `42.md`
     - Read them during the documentation phase only.
     - Update nothing unless reviewer confirms that an exact A.8 row for one of the touched files was truly resolved by this epic.
   - **Deep-analysis mirror targets (same exact-row rule):**
     - Update only the matching A.8 rows in the `DEEP-ANALYSIS-BATCH-01*.md`, `02*.md`, `07*.md`, `10*.md`, `25*.md`, `36*.md`, and `45*.md` files that actually contain the relevant thread-safety/shared-state notes.
     - Audit `DEEP-ANALYSIS-BATCH-11*.md`, `15*.md`, `27*.md`, `28*.md`, `34*.md`, `41*.md`, and `42*.md` only if reviewer identifies an exact A.8 row that was resolved.
   - **Doc-precision exception rule:**
     - If a reviewer confirms that one edited file’s only A.8 verification row lives outside the supplied batch set, update only that exact A.8 row and note in the review/doc handoff that it was a documentation-precision exception, not a scope expansion.
   - **Do not update documentation for:**
     - exporter correctness issues,
     - recommendation ordering/reactivity bugs,
     - location privacy hashing,
     - GroupTransactionCoordinator transactional/TOCTOU issues,
     - TransactionClassifier lifecycle/model-drift issues,
     - warranty age-window business rules,
     - any row that is not specifically an A.8 shared-mutable-state / thread-safety row.

### Risks
- `java.time` parsing in `WarrantyTextExtractor` can diverge from old `SimpleDateFormat` behavior if case-insensitive month parsing and default-zone conversion are not preserved carefully.
- `BudgetMonitor` is non-suspend at the public entrypoint, so over-engineering the lock strategy can easily leak into lifecycle behavior or deadlock-prone refactors.
- `HybridReceiptAssistService` still exposes a compatibility `usedImageInput(input)` method while the real source of truth already lives in the result model; preserving API without reintroducing shared state is the main design constraint.
- `RecommendationStateManager` has adjacent freshness/ordering issues in other batches; an A.8 fix must stay narrowly on synchronization/publish safety and avoid accidental backlog collapse.
- `ServiceDiagnostics` has no existing dedicated test file, so the first pass may need a small new Robolectric/JVM test harness.
- Several supplied A.8 files/batches appear already compliant or tied to different epics; documentation must be reviewer-gated so unrelated rows are not marked resolved.

### Acceptance Criteria
- [ ] `WarrantyTextExtractor.kt` and `AccountingExporters.kt` no longer store shared `SimpleDateFormat` instance state.
- [ ] `DashboardBriefingInputBuilder.kt` is explicitly audited and either left untouched as already compliant or still uses only immutable formatter state.
- [ ] `SpendingThresholdCalculator.kt` no longer uses an unguarded singleton cache path.
- [ ] `BudgetMonitor.kt` no longer reads/writes `lastCheckTime`, `cachedStatuses`, and `cacheTimestamp` through unsynchronized shared state.
- [ ] `HybridReceiptAssistService.kt` no longer stores request metadata in singleton mutable state.
- [ ] `RecommendationStateManager.kt` uses synchronized/current-user-safe state publication without public API breakage.
- [ ] `ServiceDiagnostics.kt` guards counter updates and snapshot reads against lost updates.
- [ ] Audit-only files (`TransactionClassifier`, both `GroupTransactionCoordinator` files, `LogSanitizer`, `LocationResolver`) are either unchanged with explicit rationale or changed only for a genuinely discovered A.8 shared-state defect.
- [ ] No Room entity/schema/migration changes or unrelated architecture refactors are introduced.
- [ ] `./gradlew.bat :app:compileDebugKotlin` passes after each code micro-batch.
- [ ] `./gradlew.bat :app:testDebugUnitTest` passes after all code batches land.
- [ ] Registry and only A.8-related final-verification/deep-analysis rows are updated in the same epic closeout.
