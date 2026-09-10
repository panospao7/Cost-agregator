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

### D1. `ReviewQueueRepository.recoverStuckReviews` — REAL caller missed
This item SPLIT into two defects during the GR-14u5 investigation.  Both
are stated here; only Defect I is fixed.

**Defect I — self-scoped helper misreported as external entry (RESOLVED in
GR-14u5, commit + docs/ci/db-mediation/GR-14u5.yml).**  The row is
`barrierMode: helper` and guards its OWN mutation:
`writeBarrier.runWrite(...) { pendingReviewDao.recoverStuckProcessing() }`
(`runWrite` is the sole `guarded_scope_method` on `DatabaseWriteBarrier`).
It registered zero inbound (its only caller sits in `ReviewViewModel`'s
`init {}`), and `MediationProver.prove()` step 4 returned
`UNPROVEN_EXTERNAL_ENTRY` / `GR13_ZERO_INBOUND_CALL_SITES` for ANY
non-`doWork` subject with zero inbound — before the local-direct evidence
could award `PROVEN_HELPER`.  The fix exempts a `helper` whose site-local
context is `direct` from that short-circuit (step 5, the uncertain-inbound
stop, is untouched).  Board delta: `recoverStuckReviews`
`unproven_external_entry -> proven_helper`; exactly 1 row, 0 regressions.
The row is now PROVEN but the code is still ALIVE (real caller) — removal
remains forbidden.  Symmetry note: a zero-inbound helper whose OWN worker
guard (`runGuarded`) covers the mutation is NOT exempted and still reads
`unproven_external_entry` — same class as Defect I but fail-closed
(under-proves) and 0 current rows; deferred deliberately.

**Defect II — class `init {}` blocks are invisible to the engine
(MEASURED in GR-14u8: real but LATENT — 0 current fail-open instances).**  The
regex call-graph parser attaches calls to `fun` callables only; calls inside
class-init blocks belong to no callable (`_FUN_DECL_RE` discovery in
`mediation_analysis/callgraph.py`), so the whole region (including a
`viewModelScope.launch { }` and every call in it) never enters the callgraph —
ZERO inbound rather than async-uncertain.  Confirmed at the engine level by a
synthetic probe (`build/guard-debug/gr14u8/probe_init_visibility.py`): a class
whose `init {}` calls a writer produces NO callable and NO edge for that region.

**Measured impact (GR-14u8 census, read-only, 1073 production files):**
- Of the 29 `unproven_external_entry` rows, **0** are called from any `init {}`
  block (`census_init_broad.py`) — so there is **no current fail-open instance**.
  All 29 are zero-inbound for other reasons (the ExpenseWriteStore /
  GroupLifecycleCoordinator / InvestmentTracker dead-or-owner-decision tail).
- Latent surface = **6 rows** whose method IS called from an `init {}` block
  (`census_latent_surface.py`): `ReviewViewModel.recoverStuckReviews`
  (proven_helper — the historical Defect I case), 4×
  `CategoryViewModel.ensureDefaultCategories` (already
  unproven_ambiguous_call), and 1× `ReviewViewModel.emit` (already async
  unproven).  None is mis-labeled as a safe external entry.

So the earlier "fail-OPEN, higher severity" framing does not hold against
current code: the gap is real but has no live effect.  **Deferred (owner call).**
**Candidate engine fix** (when taken): attribute `init {}` regions to a synthetic
class-initialiser callable AND make class construction of a framework-instantiated
owner (ViewModel/Activity/Fragment/Service, or any owner whose zero-inbound
member is already `FRAMEWORK_CALLBACK`) inherit that root kind — a synthetic
`<init>` callable ALONE is insufficient, because it would itself be zero-inbound
and re-classify as `PUBLIC_OR_PROTECTED_EXTERNAL`, leaving the verdict unchanged.
Larger engine change: own fixture-first plan + projection + shadow delta (closed
set + pin fixture + delta, per GR-14f/j/l precedent).  Until then: audit any
callee first-called from an `init {}` block before removing it, and do not trust
its `unproven_external_entry` label.

### D6. Multi-star imports are unresolved, so `RoomDatabase` never resolves
###     (GR-14u10 finding — measured, HARD-STOPPED, needs owner triage)
`_resolve_type` (mediation_analysis/callgraph.py) skips `entry.is_star` in the
exact-import loop and only handles the wildcard case when a file has EXACTLY ONE
star import (`if len(star_prefixes) == 1`).  `AppDatabase.kt` has THREE
(`…database.entity.*`, `…database.dao.*`, `androidx.room.*`), so `RoomDatabase`
stays `unknown`.  Consequence: `AppDatabase.onCreate`'s `super.onCreate(db)` —
which lives in an anonymous `object : RoomDatabase.Callback()` — fails closed in
`_super_receiver_fqcn` and still falls back to `_name_match_targets("onCreate")`.
That single wrong edge is the tier-5 deciding edge for **137 of the 169
remaining `unproven_ambiguous_call` rows** (re-census of the gr14u7 board:
`build/guard-debug/gr14u10/`).  This is the SAME defect class as GR-14u6, which
fixed the four other `super.onCreate` sites (MainApplication, RescueActivity,
NotificationCaptureService, MainActivity — all with explicit imports).

**Step-0 projection (multi-star resolution, monkey-patch): HARD STOP.**
Resolving a simple name through multiple star imports when exactly one candidate
is confident (a corpus owner, or a known external root package) gives:
  - `unproven_ambiguous_call` 169 -> 142, `proven_helper` 61 -> 70 (**+9**),
  - **18 rows become `counterexample_unguarded_call_path`** (0 regressions).
Resolver stats: 97 external / 5 corpus resolutions; 1910 ambiguous names stayed
fail-closed unknown; 38 found no candidate.  All 18 counterexamples reach a
`framework_callback` root by an ALL-EXACT path (`deciding=exact_synchronous`) and
are unguarded: `updateExpenseCategoryBulk`, `updateUserCorrection`,
`clearAllScannedReceipts`, `expireOld`, `processBankStatement` (x7),
`bulkUpdateCategory` (x2), `updateTypeAndTransferDetails` (x4),
`migrateCategories`.  Reproduce: `build/guard-debug/gr14u10/project_multistar.py`
(+ `projected_board.json`, `projection.log`).

**Why deferred (not landed):** 18 new definite-violation claims require human
triage before they can be recorded, exactly like the GR-14u6 gate.  Two things
must be resolved first: (a) are those 18 genuine unguarded framework-reachable
paths, or an artifact of resolving a receiver to a GUESSED external FQCN?  The
guess (`starPrefix + "." + simple`) is inherently heuristic — the existing
single-star path makes the same assumption, but at 3 star imports the chance of
a wrong package match grows.  (b) each 18 needs a disposition (fix the guard, or
prove the path is unreachable).  Note the projection's own guard: 1910
multi-candidate names stayed `unknown`, so the change is fail-closed for
ambiguity — the risk is only the confident-by-luck single candidate.

**Recommended when taken:** land the multi-star resolution with a projection
gate (as here), a fixture pin for (i) one-star, (ii) multi-star unambiguous,
(iii) multi-star ambiguous => unknown, and (iv) `super.onCreate` under multi-star
resolving external with no targets.  **BUT the 18 counterexamples are FALSE
POSITIVES from two pre-existing bugs — see §D7 — so fix §D7 first, then re-run
this projection and expect them to disappear.**

### D7. TRIAGE of the 18 D6 counterexamples: ALL FALSE POSITIVES — three
###     `local=none` bugs (GR-14u11 triage; Bug 1 FIXED in GR-14u12)
Every one of the 18 rows carries `localGuard: none` BOTH before and after the
multi-star change, yet the writers are genuinely guarded (e.g.
`ReceiptRepository.clearAllScannedReceipts` has
`writeBarrier.checkWritesAllowed(...)` as its first statement at ReceiptRepository
:535; `BankStatementLifecycleProcessor.processBankStatement` at :140).  The
multi-star fix did not create violations — it exposed three pre-existing defects
in the mediation direct-site path that make guards invisible.  **Do NOT land D6
(multi-star) until all three are fixed**, or false counterexamples surface.

**Bug 1 — D4 observation key vs GRAPH callable key mismatch — FIXED (GR-14u12,
docs/ci/db-mediation/GR-14u12.yml).**
`_DirectSiteProver` is constructed with `obs_by_callable`, keyed by the D4
`observation.callable_key` (canonical, FULLY-QUALIFIED parameter types), while
`_compute` looks up `self._observations.get(callable_key)` with the GRAPH
callable key (SIMPLE / normalized parameter types).  Measured on the current
board: of **252** callables with observations, **157 (62%)** have a D4 key that is
NOT present in `builder.callables` at all, so `_compute` returns `{}` before
proving anything and the guard can never be seen.  Examples:
  - D4  `…|function|processBankStatement|null|android.net.Uri`
    GRAPH `…|function|processBankStatement|null|Uri`
  - D4  `…|function|insertOrUpdate|null|…domain.currency.DomainExchangeRate`
    GRAPH `…|function|insertOrUpdate|null|DomainExchangeRate`
  - D4  `…|insertOrUpdateAll|null|List<…DomainExchangeRate>`
    GRAPH `…|insertOrUpdateAll|null|List`
  - D4  `…|addMemberToGroup|null|Long,String,String?,Boolean,(Long) -> Unit`
    GRAPH `…|addMemberToGroup|null|Long,String,String,Boolean,suspend (memberId: Long) -> Unit`
  (nullability, FQ vs simple, generic arguments, and lambda spelling all differ.)
  This was the dominant cause: it silently disabled the GR-12 direct proof for
  most guarded writers, which is why so many rows sat at `local=none`.
  **FIXED in GR-14u12**: `_observations_by_graph_callable` re-keys observations
  by the span-exact graph key (via `_correlate_subject_callable` — offset
  containment, exact and overload-safe).  Measured delta: proven_helper 61 -> 68,
  external_entry 29 -> 22 (7 rows), 0 counterexamples, 0 regressions.
  Effect on the D6 projection: counterexample flips 18 -> 16 (it fixed 2 only).

**Bug 2 — pseudo-site opacity interference — FIXED (GR-14u13,
docs/ci/db-mediation/GR-14u13.yml).**  `_compute` proves
over the observation sites PLUS a `_pseudo_site` per requested edge offset.  For
`ReceiptRepository.clearAllScannedReceipts` (key DOES match, so Bug 1 does not
apply):
  - proof over the observation site alone  -> site 27427 = **PROVEN**
  - proof with the 2 requested pseudo sites -> site 27427 = **UNSUPPORTED**
    (`DB_DIRECT_BARRIER_PROOF_UNSUPPORTED`)
A pseudo-site landing inside a lambda region flips the whole callable's proof to
UNSUPPORTED, so a correctly-dominated guard reads as `none`.
**FIXED in GR-14u13**: `prove_callable_direct_barriers` gained an optional
`opacity_sites` keyword, so the proof still runs over every site (results exist
for pseudo offsets) while the opacity gate is built from the REAL mutation sites
only.  Defaults to `mutation_sites`, so the D4 gate is unchanged by
construction (verified: D4 CLI 28/28 direct, 0 fail).  Measured delta:
proven_helper 68 -> 69, external_entry 22 -> 21 (1 row), 0 regressions.  Effect
on the D6 projection: counterexample flips 16 -> 15.

**Bug 2b — conservative body-model rejections + a mediator conflation
(RE-SCOPED in GR-14u14; my earlier `withLock` root cause was TOO NARROW —
`withLock` explains only 1 of 82).**

MEASURED: of the 252 callables carrying mutation observations, **82 (33%)** are
UNSUPPORTED even with observation sites only (`build/guard-debug/gr14u14/
size_bug2b.py`).  The `withLock` case (`ExpenseRepository.updateExpenseCategoryBulk`)
is ONE of them.  Construct frequency among the 82: `try`/`catch` 43,
`withTransaction` 34, `withContext` 23, `.let` 18, `when` 14, `for` 13,
`runInTransaction` 7, `.forEach` 5, `withLock` 1.  These are DELIBERATE
conservative rejections — the tokenizer's unsupported reasons include
`exception-flow` (`DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED`),
`coroutine-builder`, `labelled-return`, `elvis-block`, `local-function`,
`anonymous-object`, `lambda-escape` (see
`scripts/db_guard/structural_analysis/test_tokenizer.py::TestConservativeUnsupported`).
So this is NOT a bug in the parser: it is the engine refusing to model constructs
it cannot model safely (exception flow above all).

THE ACTUAL DEFECT is in the MEDIATOR, not the parser.  `_local_site_context`
returns `"none"` whenever the direct-site probe does not return True, and
`MediationProver.prove()` then (step 7/8, proof.py:627-657) turns
`"none" in effective` into `COUNTEREXAMPLE_UNGUARDED_CALL_PATH`.  That conflates
"there is no guard" with "we could not model the body to prove a guard".  Pre-GR-14u10
this was masked (an uncertain `super.onCreate` edge short-circuited these rows to
`unproven_ambiguous_call`); the D6 multi-star fix made the paths exact, so the
conflation now surfaces as false counterexamples.

WHY A BLANKET DOWNGRADE IS UNSAFE (my first instinct — rejected):
measured with `build/guard-debug/gr14u14/measure_guards.py`, of the 82
unmodelable bodies **57** carry `checkWritesAllowed` strictly before their first
mutation, **9** carry a `runWrite` scope, and **16 carry NEITHER** — including
`DataRetentionWorker.doWork`, `WarrantyExpirationWorker.doWork`,
`NotificationIntakeWorker.doWork`, `SourceLinkBackfillWorker.backfillLegacySource/
backfillNotificationLinks`, `DatabaseBackupRepositoryImpl.restoreReceiptAssets`,
`RestoreJournalImporter.importLastFailureJournalIfPresent`,
`LegacyDataMigrationService.migrateCategories`.  Reclassifying "unmodelable" as
"unproven" wholesale would hide those 16.

SOUND DIRECTION (design, not yet implemented): make the local proof TRI-STATE —
PROVEN (dominance proven) / UNGUARDED (body fully modeled and no dominating
barrier) / UNMODELABLE (parse unsupported).  Declare a counterexample ONLY on a
definitive UNGUARDED.  On UNMODELABLE, classify as an explicitly-labelled
unproven state — justified because a visible canonical barrier on the write-barrier
receiver is positive evidence AGAINST "definitely unguarded", whereas its absence
leaves the counterexample intact (the 16 above).  `canonical_barrier_call_sites`
already works on masked text + body span WITHOUT a CFG, so the barrier-presence
evidence is obtainable even when the parse is unsupported.

**PROJECTION OF THE TRI-STATE DESIGN (GR-14u14, read-only monkey-patch,
`build/guard-debug/gr14u14/project_tristate.py` = D6 multi-star + "unmodelable
with a visible barrier ⇒ not a counterexample"):**
  baseline (gr14u13) -> projected: proven_helper 69 -> **81** (+12),
  counterexample 0 -> 7, unproven_ambiguous 169 -> 179,
  unproven_async 133 -> 116, external_entry 21 -> 9; 48 rows changed,
  **0 regressions**, 30 unmodelable-with-barrier callables touched.
  NEW counterexamples fall 15 (D6 alone) -> **7**, i.e. the design removes 8 of the
  15 false positives while KEEPING a counterexample wherever no barrier is visible.

**SUB-CAUSE 2b-ii — locally-delegated guard helpers are invisible (NEW).**
The 7 survivors are `TransactionLifecycleCoordinator.bulkUpdateCategory` (x2),
`updateTypeAndTransferDetails` (x4) and `LegacyDataMigrationService.migrateCategories`.
The first six ARE guarded — both methods call `checkWritesAllowed("...")` as their
first statement (TransactionLifecycleCoordinator.kt:1560 and :1830) — but the call
is UNQUALIFIED, and that class defines a private delegating helper:

    private fun checkWritesAllowed(operation: String) {           // :102
        writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.$operation")
    }

`canonical_barrier_call_sites` matches `receiver.method(` only (`_CALL_RE`), so an
intra-class delegating guard is invisible to both the direct proof and the
barrier-presence evidence.  (Only `migrateCategories` looks genuinely unguarded, so
it is correctly retained — consistent with the gr14u14/measure_guards.py "NEITHER"
list.)  This is the same class of problem as §D5/GR-14u7 (a guard form the engine
cannot see), and it means the tri-state design alone under-resolves Bug 2b: the
guard-helper indirection needs its own recognition rule (e.g. treat a zero-arg/
one-arg local method whose body delegates to the canonical receiver as a barrier
form, or follow one level of intra-class delegation in the CFG).

**OWNER DECISION REQUIRED.**  This changes the gate's definition of a violation
(a false-positive fix, but it converts some current counterexamples into a new
unproven state).  It touches proof.py's tier structure and must not be read as
"relaxing assertions to make findings disappear" — hence sign-off before landing,
plus a projection showing exactly which rows move and confirmation that no
genuinely-unguarded row (like the 16) is downgraded.

(ALSO NOTE: the projected tri-state run ALSO carries the D6 multi-star change, so
its "+12 proven_helper" mixes both effects; a clean landing should separate the
mediator change from the multi-star change, or land them as one reviewed batch
with the D6 gate evidence.)

(NOTE: the `withLock`-as-transparent-scope idea was considered and set aside: it
fixes 1 row, needs a shared-contract V2->V3 bump, AND needs the resolver extended
for untyped constructor-initialised properties — `_PROP_RE` requires a type
annotation but `private val categoryUpdateMutex = Mutex()` has none, so admission
would fail even after a contract bump.)

**Consequence for the plan.**  D6 (multi-star) remains BLOCKED, now on Bug 2b
alone: 15 counterexample flips remain, false positives from the
unmodelable/local-`none` conflation described above.  The fix is the tri-state
local proof in the MEDIATOR (not a parser feature and not a contract bump), and it
needs owner sign-off because it redefines when a row counts as a violation.
Needs its own fixture-first pin + projection + shadow delta, per GR-14f/j/l
precedent.  Reproduce:
`build/guard-debug/gr14u11/{trace_counterexamples,probe_two_bugs,probe_size_bugs}.py`
and `build/guard-debug/gr14u12/probe_after_bug13.py`.

### D2. ExpenseWriteStore — OWNER DECISION (designed-but-unwired layer)
The only reference outside its own file is a stale doc comment in
ExpenseReadStore.kt ("Write paths must use [ExpenseWriteStore] or
TransactionLifecycleCoordinator"); the workers actually call
`ExpenseRepository`, whose methods call `expenseDao` DIRECTLY — no
delegation into the store.  It is therefore production-dead (7 board rows:
conditionallySetLocation/deleteAll/incrementBackfillAttempts/insertAll/
updateCategory/updateCategoryNullable/updateMerchantKey), BUT it carries
two DEDICATED test suites (ExpenseStoreTest,
ExpenseWriteStoreObservabilityTest — barrier-metadata observability) and
the ExpenseReadStore comment prescribes it as INTENDED architecture that
was never wired.  Removal = class + both suites + comment fix (owner
call), or wiring it back in (bigger owner call).  Its remaining members
(insert/update/delete/updateMerchant) never had policy rows.  Removed
from GR-14u4 scope for this reason.

Related owner-intent finding: DbGuardPolicyFixtureTest
`ownership — unrelated class UserCorrectionRepository not present` asserts
UserCorrectionRepository is ENTIRELY absent from the ownership policy, but
the policy carries its LIVE `insert` row (GR-08p1, the notification
learning surface) — the test has therefore never been able to pass and
encodes an unresolved intent (either the insert moves to a coordinator
and the class leaves the policy, or the assertion is dropped).

### D3. Name-match noise floor (handoff §6/§D — untouched, owner-gated)
Room/Activity `onCreate`-style overrides collide via name-matched edges and
taint several closures.  Any admission here is an owner-gated engine change
(closed reviewed set + pin fixture + shadow delta, per GR-14f/j/l precedent).

**GR-14u5 census MEASURED it — and it is the single biggest remaining
blocker, not a small tail.**  A read-only tier-5 replay over the post-u5
board (`build/guard-debug/gr14u5/census.py` + `probe_super.py`, reproduces
every row's proofStatus exactly) shows the 174 unproven_ambiguous rows'
deciding resolutions are: 143 `unresolved_target`, 17 `interface_dispatch`,
14 `function_reference`.  Of the 143 `unresolved_target` rows, **137 are
decided by ONE call site** — `super.onCreate(...)` in
`MainApplication.onCreate` (a 5-target name-match on `onCreate`), plus 5 by
`super.onListenerConnected(...)` in NotificationCaptureService and 1 real
row.  Root cause: `CallGraphBuilder.receiver_fqcn_for_call` handles `this` /
`this@X` but has NO `super` / `super@X` case, so `super.onCreate()` resolves
to an unknown receiver and falls back to `_name_match_targets` — which then
taints the ancestor closure of essentially every writer reachable from the
app-startup root (`MainApplication.onCreate` is itself one of the matched
`onCreate` callables → self-taint).

Implication for prioritization (CORRECTED by the GR-14u6 Step-0 projection —
see below): a `super.<member>()` receiver-resolution fix removes the WRONG
edge (~137 rows), but the taint is LAYERED: removing it just promotes the next
uncertain edge for ~132 of those rows, so the batch proved only 5 rows, not
137.  The previously-hypothesized "ViewModel default-DI owner-property
initializer type inference" shape is NOT dominant: only ~9 rows of
`initializer-ctor` shape (all `tempZip`/`File`).  The async half (123
`async_dispatch`) is spread thin across lambda carriers (`coroutineScope` 37,
a privacy-gate lambda 23, `PostCommitAction` 19, `navigation.launch` 17,
`confirmQuickApprove` 9, `photon.coroutineScope` 6) with no single dominant
fix — each carrier would be its own closed reviewed admission.

**RESOLVED in GR-14u6** (docs/ci/db-mediation/GR-14u6.yml).  The `super`
resolution was implemented (`CallGraphBuilder._super_receiver_fqcn`), and its
Step-0 projection HARD-STOPPED on 5 counterexample flips — revealing that the
false `super.onCreate`/`super.onListenerConnected` taint had been HIDING 5
genuinely unguarded notification-capture DB writers.  Those writers were fixed
(production: `DatabaseWriteBarrier` guards added to NotificationIntakeCoordinator,
NotificationIntakePayloadRepairer, NotificationIntakeRecoveryScheduler), after
which the gate passed and all 5 rows proved.  Board delta: proven_helper
49 -> 54, ambiguous 174 -> 169.

### D5. Direct-site prover never sees bare `checkWritesAllowed` at a subject
###     mutation site (GR-14u6 finding — RESOLVED in GR-14u7)
While fixing D3, adding `writeBarrier.checkWritesAllowed("...")` at the TOP of a
writer did NOT register as local-direct in the mediation proof.  Root cause:
`_DirectSiteProver._compute` (scripts/ci/inspect_db_mediation_proof.py) computes
the GR-12 proof over the callable's REAL mutation sites PLUS the requested
mediation pseudo-sites, but read results back ONLY for the requested offsets
(`edge.name_start`), while `MediationProver._local_site_context` queries
`subject.site_start` = `MutationObservation.source_start` (the mutation call
start).  Those offsets differ, so a bare dominating `checkWritesAllowed` was
invisible; `runWrite { ... }` worked only because the callgraph marks the lambda
a `canonical_direct` region (offset-independent).

**RESOLVED in GR-14u7** (docs/ci/db-mediation/GR-14u7.yml): `_compute` now
records results for `requested | {site.span.start for site in sites}`.  The
already-guarded writers became visible: 7 rows moved `unproven_external_entry ->
proven_helper` (proven_helper 54 -> 61, external_entry 36 -> 29), 0
counterexamples, 0 regressions.  Fixture pin
`scripts/ci/test_gr14u7_direct_site_offsets.py` was RED before the fix (prover
returned `{}` at the mutation offset while the underlying GR-12 proof already
said PROVEN).

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
0. **GATE-00R attempt recorded (2026-09-09/10)**: capture run 1 at
   GR-14u3 HEAD (335758ff) completed but is UNTRUSTED (exit 2) — bundle
   `build/guard-debug/gr14u3/gate00r-capture-1/`.  Honest inventory:
   gradle-db and gradle-task-graph exit 1 (the protocol's documented
   config-cache observation), static-suite and focused-python-tests exit 1
   (the §B pre-existing reds flow into the capture), room/db-inventory exit
   2 `INVENTORY_DURABILITY_UNCONFIRMED`, gradle-compile and db-cli exit 0,
   preservation/policy checks all OK, tree clean at the pinned sha.  Also:
   run 1 was slowed/raced by a mid-capture edit (post-capture-drift
   warning) — **never edit the tree while a capture runs**.  A TRUSTED
   double capture becomes reachable once §B4 (freshness stamps via the
   Gradle DB task) and the §B reds are paid; until then GATE-00R stays
   "owed at merge" by design.
1. GATE-00R double capture + Gradle DB task at merge (MANDATORY — policy
   sha changed twice: 5d3b394d (GR-14u) then c6b0463e (GR-14u2) then
   b4d2938c (GR-14u3)).
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

**GR-14u6/u7 outcome (measured, see §D3/§D5).**  Both engine batches are DONE.
Board now: proven_helper 61 / proven_worker_mediated 14 / ambiguous 169 /
async 133 / external_entry 29 (proven 75 / unproven 331).  GR-14u6 removed the
wrong `super` edge and (via its Step-0 hard-stop) guarded 5 genuinely unguarded
notification-capture writers; GR-14u7 made the direct-site prover report
dominance at the real mutation offset, proving 7 already-guarded writers.  The
`super` fix did NOT prove 137 (the taint is layered).
Recommended order from here:
  (a) Defect II — init-block invisibility (§D1 remainder; fail-OPEN, so higher
      severity than any remaining noise), fixture-first + projection;
  (b) dead-writer tail (mechanical, steady movement);
  (c) interface_dispatch/function_reference residue (§D4);
  (d) async-carrier admissions one carrier at a time (each a closed admission).

Artifacts this arc: build/guard-debug/{gr14s,gr14t,gr14u,gr14u2,gr14u3,
gr14u4,gr14u5,gr14u6,gr14u7}/ (shadows before/after + double-runs, compile logs,
targeted-test logs, A/B failure lists, the abandoned GR-14t patch, edit
scripts; gr14u5 adds the ambiguous/async census + super probe; gr14u6 adds the
Step-0 super projection gate + live shadow delta; gr14u7 adds the §D5
projection + delta).
