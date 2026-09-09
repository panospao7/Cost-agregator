# DEFERRED ISSUES & DEBUG HANDOFF — GR-14 campaign state (2026-09-09)

Audience: the next agent/engineer continuing the GR-14 mediation campaign or
picking up test-infra repairs.  Everything below was investigated during the
GR-14s/u/u2 arc (commits 72064061, ba353d69, 42f464f0, 7815d0d4) and is
**deliberately unresolved** — each item carries what we know, what we ruled
out, the exact feedback output, and candidate next steps.  Nothing here is
speculative debt: every "pre-existing" claim was stash-A/B-proved at the
pre-batch state during this arc.

Board state after GR-14u2 (build/guard-debug/gr14u2/shadow_after.json,
sha 82e4fc9f..., double-run byte-identical):
proven_helper 48 / proven_worker_mediated 14 / counterexample **0** /
unproven_ambiguous 174 / unproven_async 133 / unproven_external_entry 54 /
function_reference 14 (census labels).  Active policy: 451 entries, sha
c6b0463e...; **GATE-00R double capture + Gradle DB task are MANDATORY at
the next merge** (policy bytes changed in GR-14u and GR-14u2).

---

## A. Test-infrastructure blockers (environment-level — fix before any
##    batch that needs final-class mocking or Android Keystore in tests)

### A1. Byte-buddy agent self-attach fails → mockk cannot mock FINAL classes
- **What happens**: any test that builds `mockk<SomeFinalClass>()` triggers
  `net.bytebuddy.agent.ByteBuddyAgent.installExternal`, which fails with
  `IllegalStateException: Could not self-attach to current VM using external
  process`.  The first failure poisons `io.mockk.impl.JvmMockKGateway`
  (`ExceptionInInitializerError` / `NoClassDefFoundError`) — every mockk
  call in the same test JVM then fails, including plain interface mocks,
  and repeated external-attach attempts end in `OutOfMemoryError: Java heap
  space`.
- **Evidence**: GR-14t trial run (build/guard-debug/gr14t/test_targeted.log):
  67 cascading failures across unrelated classes after the first
  `mockk<CompositePrivacyGate>()`.
- **Why it matters**: GR-14t-style concretization (interface field → final
  impl class) forces tests to mock the final class.  Interface mocks never
  touch the agent, which is why the pre-concretization suites work.
- **Candidate fixes (owner decision, each needs its own A/B batch)**:
  1. Add to the unit-test JVM args: `-Djdk.attach.allowAttachSelf=true`
     (and on newer JDKs `-XX:+EnableDynamicAgentLoading`).  Cheapest fix;
     touches `app/build.gradle.kts` test config; validate no timing/memory
     side effects on other suites.
  2. Fake-based (non-mockk) test doubles for concretized types — GR-14t
     estimated ~20 files.
  3. mockk subclass mock-maker config — fragile, not recommended.
- **Ruled out**: "just don't use relaxed mocks" — the production types are
  final; Mockito-style proxies cannot subclass them at all.

### A2. AndroidKeyStore unavailable in the unit-test JVM
- **What happens**: `BankTokenCipher.getOrCreateSecretKey` calls
  `KeyStore.getInstance("AndroidKeyStore")` → `java.security.KeyStoreException:
  AndroidKeyStore not found`.  Any BankApiIntegrationTest path that reaches
  token encryption fails (observed: `refreshToken persists new tokens`,
  `completeConnection returns persisted connection with id`).
- **Analysis**: environmental, deterministic in a plain JUnit JVM; not a
  logic failure.  The 2 other BankApiIntegrationTest failures
  (`same provider transaction id yields stable strict dedupe identity`
  expected 1 but was 2; `low confidence bank transaction triggers pending
  review on sync` expected `low-conf-1` but was a hex hash
  `774c823a341e0f4b6e8041cf`) appeared in the same runs — they look like
  STALE EXPECTATIONS vs the current STRICT_EXTERNAL_ID hashing behaviour
  (note the sibling test "mapTransactionToExpense uses STRICT_EXTERNAL_ID
  with hashed provider identity" PASSES), but they were never observed
  passing in a clean run this arc — **first task for the next agent: run
  `*BankApiIntegrationTest*` alone at HEAD and decide stale-test vs
  regression**; the class was not touched by GR-14s/u/u2 except one fake
  override line (GR-14u).
- **Candidate fixes**: Robolectric or instrumented tests for Keystore
  paths; or inject the cipher abstraction so unit tests can use a fake.

### A3. Gradle daemon native-OOM under memory pressure
- **What happens**: `hs_err_pid*.log`: "insufficient memory ... mmap failed
  to map ... for G1 virtual space" → "Gradle build daemon disappeared
  unexpectedly".  Observed once during GR-14u compiles after many
  consecutive daemon-heavy runs.
- **Mitigation used**: `./gradlew --stop` (killed 2 stale daemons) then
  retry — clean.  Keep this in the playbook; consider lowering daemon
  `-Xmx` or running heavy suites less concurrently.

### A4. WarrantyTrackerRepositoryTest executor OOM/JPLIS crash
- Pre-existing, A/B-proved again in GR-14h (identical family to A1 — the
  test's executor crashes the agent).  Skipped re-running in GR-14u/u2:
  compile covers the touched code, and no removed method is referenced by
  any test (grep-verified).  Fix belongs to the same owner decision as A1.

---

## B. Red unit tests (pre-existing, A/B-proved this arc — fixable, with
##    root causes already identified)

### B1. ReceiptLifecycleCoordinatorTest — 7 failures (A/B-identical set)
All are mockk-RELAXED artifacts, not production bugs:
1-4. `high_confidence_email_creates_expense_directly`,
     `processEmailReceipt post-commit cancellation rethrows`,
     `processEmailReceipt invokes resolveHomeCurrency once`,
     `processEmailReceipt post-commit failure ...` —
     `ClassCastException: class java.lang.Object cannot be cast to class
     CreateExpenseResult` at the `when (mutation.value)` on
     `transactionLifecycleCoordinator.createExpenseDbOnlyV2(request)`.
     **Root cause**: the tests never stub `createExpenseDbOnlyV2`; the
     relaxed mock returns a generic-erased value.  **Candidate fix**: stub
     it in each of those tests (or in setup):
     `coEvery { transactionLifecycleCoordinator.createExpenseDbOnlyV2(any()) }
     returns MutationResult(... Created(...) ...)` — see
     TransactionLifecycleCoordinator tests (GR-14q repaired the same
     pattern there) for the exact MutationResult shape.
5. `messageId_conflict_returns_existing_source` — `expected:<5> but
     was:<0>`: relaxed `scannedReceiptDao.getBySourceFingerprint(...)`
     returns a NON-null relaxed ScannedReceipt (id=0), so the top dedup
     block returns `Duplicate(0)` before reaching the messageId conflict
     path.  **Candidate fix**: `coEvery { scannedReceiptDao.getBySource
     Fingerprint(any()) } returns null` in that test.
6-7. `processReceiptInput post-commit cancellation rethrows` (expected
     CancellationException, completed successfully) and `processReceiptInput
     validates and persists receipt` (mockk matcher mismatch on
     `receiptRepository.processReceipt(eq(uri), eq(false), null(), any())`).
     Path untouched by any GR-14 batch since the f1758149 migration;
     needs fresh eyes on the processReceipt stub signature (an extra
     nullable param is not matching — likely `null()` vs `isNull()` for a
     String param).

### B2. SavingsGoalsViewModelTest — 3 failures (A/B-identical set)
`progress update reflects in UI`, `contributeToGoal uses atomic
addToGoalAmount`, `add goal updates state` — all:
`NullPointerException: Cannot invoke "TimeProvider.now()" because
"<parameter1>.timeProvider" is null` at
SavingsContributionHistoryRepository.recordContribution$default(:36) via
SavingsGoalsViewModel.contributeToGoal (:259).
**Root cause**: the test constructs the real
SavingsContributionHistoryRepository with a null TimeProvider; Kotlin's
default-arg `timestamp = timeProvider.now()` NPEs.  **Candidate fix**: build
that repository with a stubbed TimeProvider (`every { now() } returns now`)
and a relaxed dao in the test setup.  (GR-14u2 removed one inert
`updateGoalAmount` mockk stub from this file's setup — zero relation to
these failures; A/B-identical.)

### B3. SplitCalculationPrecisionTest — `percentage split with very small
###     amount` (A/B-identical)
`expected 0.03 but was 0.04, outside tolerance 0.01`.  Float/rounding
semantics in percentage split allocation.  **AGENTS.md money rules apply**:
analyse the rounding mode and the sum-of-parts invariant before touching;
do NOT "fix" by weakening the tolerance.  Likely needs largest-remainder
allocation in the split calculator.

### B4. verify_known_good_state — 11 failures (A/B-identical set)
All `TestTestResultFreshnessRow`/freshness "stamp=missing" rows: the
known-good-state validator requires FRESH Gradle-produced test-result
stamps.  This is the **GATE-00R / Gradle DB task debt** materialising —
it will go green once the merge-time recapture runs.  Not a code bug.

---

## C. Architecture-guard debts (partly fixed this arc — remainder here)

### C1. FIXED in the guard-hygiene batch (uncommitted at writing time —
###     commit together with this document)
- BankConnectionLifecycleCoordinator.kt: the two-catch idiom
  (`catch (ce: CancellationException) throw ce` + `catch (e: Exception)`)
  is functionally correct but the CancellationSafetyArchitectureGuardTest
  general scanner only accepts CE evidence INSIDE the broad catch body
  (`ceGuardEvidence = CancellationException|rethrowIfCancellation`; the
  sibling-lookback fallback exists only in the launch-block scan).
  Converted all 3 sites to `catch (e: Exception) { if (e is
  CancellationException) throw e; ... }` — behaviour-identical.
  Note for the next agent: if you see this idiom flagged elsewhere, either
  convert it the same way or extend the general scanner with the
  sibling-lookback fallback used by the launch-block scan.
- CancellationSafetyArchitectureGuardTest KNOWN_VIOLATIONS: removed the
  `DebugDataStorage.kt` entry — no such FILE exists (the DebugDataStorage
  class is declared inside ReviewViewModel.kt, which already carries its
  own allowlist entry; the file-mapping assert failed on the stale name).
- DirectEventDaoInsertGuardTest: the 8 LEGACY_REPOSITORY entries expired
  2026-08-15 (today was 2026-09-09).  Per-file re-check against the
  guard's GUARDED_DAO_NAMES: ExpenseRepository is CLEAN → entry removed;
  the other 7 (WarrantyTracker, Receipt, ReviewQueue, BankApiIntegration,
  RecurringExpense, ManualRecurringExpense, Notification) still have LIVE
  direct event-dao inserts → entries RENEWED to 2026-11-20 (inside the
  75-day cap enforced by `legacy repository entries have short expiry`).
  **DEBT**: they expire again on 2026-11-20.  The real fix is completing
  the legacy→coordinator event-write migration for those 7 files (each is
  named with its MIT-031/041/043 issue in the allowlist).

### C2. CancellationSafetyArchitectureGuardTest — 1 residual failure
`every broad catch in suspend functions rethrows CancellationException`
still lists `BankConnectionLifecycleCoordinator.kt:48/53/67` line numbers
from BEFORE the C1 conversion in older logs; at the guard-batch working
tree it passes.  If it re-flags: the scanner counts a catch BODY without
the literal strings — prefer `if (e is CancellationException) throw e`
inside the body, or the allowlist with owner+issue+expiry.

---

## D. Mediation-engine issues (proof bugs / visibility gaps — do NOT remove
##    the code below; it is ALIVE despite engine zero-inbound)

### D1. ReviewQueueRepository.recoverStuckReviews — REAL caller missed
The engine classifies it `unproven_external_entry` (zero inbound), but
`ReviewViewModel.kt:218` calls `reviewQueueRepository.recoverStuckReviews()`.
Investigate why the ViewModel→repository edge is invisible (call site's
enclosing construct: check whether it sits in a construct the lambda-region
admission treats as opaque, or a dispatch the resolver misses).  Until
understood, this row must stay unproven — removal is forbidden.

### D2. ExpenseWriteStore facade/worker chains missed
`conditionallySetLocation`, `incrementBackfillAttempts`,
`updateMerchantKey`, (likely) `updateCategory` are engine zero-inbound but
are called via `LocationBackfillWorker`/`MerchantKeyBackfillWorker` →
`ExpenseRepository` facade → `ExpenseWriteStore`.  The delegation hop
(ExpenseRepository → ExpenseWriteStore) evidently does not produce an
inbound edge for the store method.  Same investigation shape as D1.

### D3. Name-match noise floor (handoff §6/§D — untouched, owner-gated)
Room/Activity `onCreate`-style overrides collide via name-matched edges and
taint several closures.  Any admission here is an owner-gated engine change
(closed reviewed set + pin fixture + shadow delta, per GR-14f/j/l precedent).

### D4. Interface-dispatch residue after the GR-14t negative (17 rows)
Rows decided by `interface_dispatch` live in: GroupTransactionCoordinator
 callers (addExpenseToGroup, createGroupWithMembers[Atomic],
 deleteGroupAtomic, removeMember, archiveGroup, restoreGroup),
 MerchantLocationRepository (getCachedLocation[ForArea]),
 SharedExpenseDataPortAdapter, DatabaseBackupRepositoryImpl
 (restoreReceiptAssets).  **Scout finding**: `GroupTransactionCoordinator`
 exists BOTH as a domain interface and as a final `@Inject` impl class
 (data/database/GroupTransactionCoordinator.kt:149) — the exact
 single-binding concretization shape; BUT concretization is blocked until
 A1 (final-class mocking) is resolved or its tests are faked.  Check the
 binding count first (`grep -rn "GroupTransactionCoordinator" app/src/main
 --include=*.kt | grep -i binds`), then the remaining interfaces per row
 (CurrencySettingsRepository is also interface-typed in several closures).

---

## E. Queue state & how to resume
1. GATE-00R double capture + Gradle DB task at merge (MANDATORY — policy
   sha changed twice: 5d3b394d (GR-14u) then c6b0463e (GR-14u2)).
2. GR-14u3 dead tranche (≤10 keys) from the GR-14u.yml remainder list —
   SourceStatsRepository ×9 is the big block (callers bypass the wrapper
   and hit SourceStatsDao directly; verify the class's read methods before
   removing), plus ReceiptRepository.clearMatchForReceipt /
   writeReceiptEvent and ReceiptMatchLifecycleService.approveMatchSuggestion.
3. Owner decisions: production-dead-but-test-covered callables
   (GroupLifecycle archiveGroup/removeMember/recordSettlement/
   deleteGroupPermanently + SettlementCalculator.recordSettlement —
   contract/scenario suites only, the GroupTransactionCoordinator routing
   was never built; InvestmentTracker; NotificationRepository.
   deleteAllNotifications; BankApiIntegration.completeConnection;
   SubscriptionManagerEngine.recordPriceChange; AiArtifactRepositoryImpl.
   deleteByTargetKey).
4. GR-14s2/v tail: function_reference (14, `::method` callbacks),
   virtual_dispatch residue, `.PostCommitAction`/`.finally` census labels
   (evidence-unclear — investigate before any admission),
   RetentionModule.provideRetentionTargets (DI module pattern).
5. GR-15 must NOT start until the GR-14 zero gate (§8 of the handoff).

Artifacts this arc: build/guard-debug/{gr14s,gr14t,gr14u,gr14u2}/
(shadows before/after + double-runs, compile logs, targeted-test logs,
A/B failure lists, the abandoned GR-14t patch, edit scripts).
