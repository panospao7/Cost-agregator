# A.8 Review

## Batch 1

VERDICT: PASS

Issues:
- [ISSUE-1] RESOLVED

Coverage:
- Requirements met: yes — shared `SimpleDateFormat` state was removed from `WarrantyTextExtractor.kt` and `AccountingExporters.kt`, legacy formatter behavior in `WarrantyTextExtractor.kt` was restored narrowly with immutable `java.time` coverage for 2-digit years and full month names, and `DashboardBriefingInputBuilder.kt` remained unchanged and A.8-compliant.
- Testing adequate: yes — `:app:compileDebugKotlin`, `*WarrantyTextExtractorTest`, and `*CsvEscapingTest` passed. `GenerateDashboardBriefingUseCaseTest` was not needed because `DashboardBriefingInputBuilder.kt` remained unchanged. The provided evidence for `WarrantyTextExtractorTest` (8 total, 0 failures, 2 ignored, 6 passing) is consistent with the current re-check.

Scope-discipline assessment:
- Disciplined — the fix stayed inside Batch 1, added only the minimal immutable formatter compatibility needed to preserve the legacy OCR contract, left export semantics unchanged, and kept `DashboardBriefingInputBuilder.kt` untouched as required.

## Batch 2

VERDICT: PASS

Issues:
- [ISSUE-2] RESOLVED — `SpendingThresholdCalculator` now keeps a per-user generation counter behind the same `cacheMutex` used for cache lookup/store/remove. `calculatePercentiles()` captures the generation before recompute and only writes back when the generation still matches, so `refreshThresholds()` can invalidate an entry and reject stale post-refresh writeback from an older in-flight compute.
- [ISSUE-3] RESOLVED — `BudgetMonitor` now uses a single `stateLock = Any()` as the sole synchronization owner for `lastCheckTime`, `cachedStatuses`, and `cacheTimestamp`. Both the throttle path and cache path route through that one lock, and no retry/cooldown/API/lifecycle behavior changed.
- [ISSUE-4] RESOLVED — Batch 2 compile and focused test evidence is now recorded and correctly classified.
- [ISSUE-5] RESOLVED — expired cache entries are now replaced normally because TTL expiry does not bump the generation. That preserves normal cache refresh semantics while still blocking writeback from computations that began before an explicit `refreshThresholds()` invalidation.

Coverage:
- Requirements met: yes — `SpendingThresholdCalculator` cache lookup/update/remove is now owned by one `Mutex` with generation-based stale-write rejection, `BudgetMonitor` keeps throttle/cache state behind one coherent lock, and no unrelated `effectiveAmount`, DAO-query, retry, cooldown, lifecycle, or public-API changes slipped in.
- Testing adequate: yes — Batch 2 verification evidence is sufficient and correctly classified:
  1. `:app:compileDebugKotlin` ✅
  2. `*SpendingThresholdCalculatorTest` ✅ (10 tests, 0 failures, including targeted regression coverage for refresh invalidation and TTL replacement)
  3. `*BudgetMonitorTest` ✅
  4. `*BudgetMonitorStressTest` ✅
  5. `*BudgetAlertPipelineTest` ✅

Scope-discipline assessment:
- Disciplined — the final re-fix stayed within the Batch 2 file set, addressed only the synchronization/cache hazards called out in the plan, preserved business behavior and cache TTL semantics, and added focused calculator regression coverage without widening scope.

## Batch 3

VERDICT: PASS

Issues:
- [ISSUE-6] RESOLVED — re-review confirms the `usedImageInput(input)` override was removed entirely. `HybridReceiptAssistService` now relies on the `ReceiptAssistService` interface default (`return false`), which is the only safe stateless answer: it never over-reports image usage on ON_DEVICE / DISABLED / DETERMINISTIC_FALLBACK routes, and the canonical per-request truth remains `ReceiptAssistSuggestion.usedImageInput` from each delegate's `suggest()` call. Three focused regression tests were added to `HybridServiceDelegationTest.kt` covering: (1) always-false on CLOUD route even with image metadata present, (2) always-false on ON_DEVICE route with verify cloud service never consulted, (3) always-false on DISABLED/FALLBACK routes with verify cloud service never consulted.

Coverage:
- Requirements met: yes — `HybridReceiptAssistService` no longer stores `lastUsedImageInput`, `suggest()` routing/delegation stayed unchanged, no broader receipt-assist refactor slipped in, and the `usedImageInput` compatibility shim is now conservative and stateless (interface default `false`).
- Testing adequate: yes — `:app:compileDebugKotlin` ✅ and `:app:testDebugUnitTest --tests "*HybridServiceDelegationTest"` ✅ (all tests pass, including 3 new regression tests for the compatibility shim).

Scope-discipline assessment:
- Disciplined and complete — the fix stayed inside the Batch 3 file set (only `HybridReceiptAssistService.kt` and `HybridServiceDelegationTest.kt`), used the smallest safe behavior (drop override → interface default `false`), and added targeted regression coverage without widening scope.

## Batch 4

VERDICT: PASS

Issues:
- [ISSUE-7] RESOLVED — The dual-owner (`stateMutex` + `jvmStateLock`) design was replaced with a single JVM monitor (`stateLock = Any()`). ALL mutations to `_recommendations`, `currentUserId`, and `stateGeneration` in every code path (`refreshForUser`, `dismiss`, `removeFromState`, `clear`, `clearForUser`) now go through `synchronized(stateLock)`. The coroutine paths perform repository I/O outside the lock (no monitor held across suspension points), then acquire the same `stateLock` for the brief state write — eliminating any possibility of a remove racing a coroutine-path mutation through a different lock.
- [ISSUE-8] RESOLVED — With the one-owner design, the existing comprehensive `CompletableDeferred`-gated overlap tests now deterministically exercise the single shared guard. The stale-refresh gate tests (`overlap - stale user1 refresh blocked by gate is discarded after removeFromState`, etc.) now correctly prove that `removeFromState`'s generation bump under `stateLock` is the same guard checked by coroutine publish paths — no separate lock path can escape the generation check.
- [ISSUE-9] RESOLVED (retained) — `removeFromState()` remains behaviorally synchronous: mutation completes inline under `synchronized(stateLock)` before the method returns. `getCurrentUserId()` is also synchronous, reading under `synchronized(stateLock)` for a consistent view.
- [ISSUE-10] RESOLVED — `clear()` now mutates `_recommendations`, `currentUserId`, and `stateGeneration` inline under the same `stateLock` owner instead of dispatching through `scope.launch`. The new regression test `clear is immediately synchronous - state empty and userId null without advanceUntilIdle` verifies the state is cleared before the method returns, restoring the original synchronous public contract.

Coverage:
- Requirements met: yes — the current code now uses one consistent state owner (`stateLock`) for `currentUserId`, `_recommendations`, and `stateGeneration`; stale refreshes are generation-guarded; `dismiss`, `removeFromState`, `clear`, and `clearForUser` all route through the same owner; and public APIs remain stable, including synchronous behavior for `removeFromState()`, `getCurrentUserId()`, and `clear()`.
- Testing adequate: yes — `:app:compileDebugKotlin` ✅; `*RecommendationStateManagerTest` ✅ (28 tests, 0 failures, including deterministic overlap coverage plus immediate-synchronous regressions for `removeFromState()` and `clear()`); `*RecommendationLifecycleManagerTest` ✅; `*RecommendationDismissalHandlerTest` ✅. `*HomeViewModelRecommendationTest` still has the same single unrelated pre-existing failure (`navigateToRecommendation handles null filter criteria`).

Scope-discipline assessment:
- Disciplined and complete — the fix stayed narrowly inside Batch 4, used the minimum synchronization change needed (unify on one JVM monitor and restore synchronous public-method behavior), and did not broaden into any reactivity, ranking, or repository-observation refactor.

## Batch 5

VERDICT: PASS

Issues:
- [ISSUE-11] RESOLVED — Re-review confirms all bare `assert(...)` calls were replaced with JUnit `assertTrue(message, condition)` assertions (timestamp range checks in `recordServiceStart updates last restart time`, `recordServiceKilled updates last kill time`, and the positivity checks in `getStats returns consistent snapshot of all counters`). A new concurrent snapshot regression test `getStats never returns impossible mixed snapshot under contention` was added: 2 start-writer threads, 2 kill-writer threads, a periodic resetter thread, and a parallel reader thread all race together across 200 snapshot reads; the invariant checked is that `startCount > 0 → lastRestartTime > 0` and `killedCount > 0 → lastKillTime > 0` — any mixed snapshot would be proof of lock failure.

Coverage:
- Requirements met: yes — `ServiceDiagnostics.kt` routes writes, reads, `getStats()`, and `resetStats()` through one private lock, preserving the `SharedPreferences` backend, existing keys, method signatures, and layer placement.
- Testing adequate: yes — re-check confirms `:app:compileDebugKotlin` ✅ and `*ServiceDiagnosticsTest` ✅ (11 tests, 0 failures, including the new concurrent snapshot contention test). `*DebugViewModelStressTest` remains skipped/manual-only / unchanged, so it is classified as non-blocking supporting evidence rather than executed functional coverage.

Scope-discipline assessment:
- Disciplined and complete — only `ServiceDiagnosticsTest.kt` was changed; no production code touched; fix stays narrowly on replacing ineffective JVM `assert` with JUnit assertions and adding the contention snapshot regression, exactly as required by the remedy plan.

## Batch 6

VERDICT: PASS

Issues:
- [ISSUE-12] RESOLVED — `TransactionClassifier` now uses `private val jobLock = Any()` as the single synchronization owner for `saveJob` / `retrainJob` cancel-and-replace access. `cleanup()`, `retrainFromCorrections()`, and `scheduleSave()` all route job-handle mutation through that owner, eliminating the previously unguarded singleton job-state race while staying narrowly inside A.8 scope.

Coverage:
- Requirements met: yes — `TransactionClassifier.kt` no longer leaves `saveJob` / `retrainJob` as unsynchronized shared singleton state, and the prior no-op conclusions for `domain/groups/GroupTransactionCoordinator.kt`, `data/database/GroupTransactionCoordinator.kt`, `LogSanitizer.kt`, and `LocationResolver.kt` remain justified because those files still do not expose an A.8 shared-mutable-state defect within this epic's scope.
- Testing adequate: yes — provided evidence shows `:app:compileDebugKotlin` ✅. For this audit-only batch, source re-inspection is the primary verification method, and no additional focused tests were required because no other Batch 6 files changed.

Scope-discipline assessment:
- Disciplined and complete — the fix stayed local to `TransactionClassifier.kt`, addressed only the real A.8 shared mutable job-state hazard, and correctly left the other Batch 6 audit-only files untouched instead of widening into group-transaction, location-cache, privacy-hash, or classifier-lifecycle work.

## Final Epic Gate

VERDICT: PASS

Issues:
- [ISSUE-1] RESOLVED
- [ISSUE-2] RESOLVED
- [ISSUE-3] RESOLVED
- [ISSUE-4] RESOLVED
- [ISSUE-5] RESOLVED
- [ISSUE-6] RESOLVED
- [ISSUE-7] RESOLVED
- [ISSUE-8] RESOLVED
- [ISSUE-9] RESOLVED
- [ISSUE-10] RESOLVED
- [ISSUE-11] RESOLVED
- [ISSUE-12] RESOLVED
- [ISSUE-13] RESOLVED
- [ISSUE-14] RESOLVED — Focused A.8 verification is green. Full `:app:testDebugUnitTest` remains red only because of pre-existing unrelated failures: `BudgetRolloverTest` (infinite virtual-time/OOM), `HomeViewModelRecommendationTest` (null-filter helper bug), and `ExpenseRepositoryTest` (query assertion mismatch). Other observed reds are OOM cascade victims. A.8 is approved under a documented verification waiver only; the full unit-test lane is not green.

Coverage:
- Requirements met: yes — A.8 scope items are complete and the `WarrantyTextExtractor` ambiguous-date regression is fixed.
- Testing adequate: yes, with waiver — `:app:compileDebugKotlin` passed and focused A.8 suites passed; full `:app:testDebugUnitTest` is waived only for the unrelated failures listed above.
