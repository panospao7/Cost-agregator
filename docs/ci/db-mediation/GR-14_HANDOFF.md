# GR-14 CAMPAIGN HANDOFF — read this fully before touching anything

You are continuing a proof-engine remediation campaign on branch
`gr-14f-wip` (worktree: `build/worktrees/gr-14f`). The mission: drive the
mediation-shadow board to zero unproven/counterexample rows so GR-15 may
begin (GR-15 is explicitly gated on GR-14 completion — do not start it).

## 1. MISSION AND CURRENT STATE

The app's DB-write guard engine (the "mediation shadow") proves that every
DB mutation is behind a canonical write barrier. Every policy mutation row
must end `proven_helper` or `proven_worker_mediated` — or be removed.

**Board after GR-14r (commit 88870818), from
`build/guard-debug/gr14r/shadow_after.json`:**

| bucket | count |
|---|---|
| proven_helper | 46 |
| proven_worker_mediated | 14 |
| counterexample (unscoped writers, scope-ready) | 4 |
| unproven_async_or_escaping_callback | 133 |
| unproven_ambiguous_call | 174 |
| unproven_external_entry | 72 |

`localGuard=direct` on 90 rows (canonical scopes already laid — these are
"loaded spring" rows that flip to proven as soon as their upstream taint
resolves). Diagnostics clean. Active policy: 471 entries, sha256
51f7d40a..., candidate==active (zero drift). Baseline untouched.

**Committed batches this arc (all on gr-14f-wip, oldest first):**
GR-14i `f2acc657`, GR-14j `25c225f1`, GR-14k `dd9cf5f1`, GR-14l `da9dfb6d`,
GR-14m `7221563c`, GR-14n `de1d3ac5`, GR-14o `e5e47086`, GR-14p `67ca7c9f`,
GR-14q `6bef2545`, GR-14r `88870818`. Manifests: `docs/ci/db-mediation/
GR-14{a..r}.yml` — READ GR-14i through GR-14r manifests first; they contain
the accumulated evidence and rationale.

## 2. HOW THE ENGINE THINKS (required knowledge)

Mediation shadow CLI: `python scripts/ci/inspect_db_mediation_proof.py
--output <path>.json` — exit 0 = all proven, exit 1 = unproven rows exist
(NORMAL), exit 2 = infrastructure. Takes ~5 min. Baseline/after pairs per
batch live under `build/guard-debug/gr14{x}/`.

Proof tiers in `scripts/db_guard/mediation_analysis/proof.py::prove()` —
ORDER MATTERS:
1. recursion → UNPROVEN_RECURSION
2. owner ambiguity → UNPROVEN_AMBIGUOUS
3. local site inside an unresolved lambda → async/ambiguous
4. zero inbound edges → UNPROVEN_EXTERNAL_ENTRY
5. **any uncertain edge reaching the subject's ancestor closure →
   UNPROVEN_ASYNC (async_dispatch/escaping_lambda) or UNPROVEN_AMBIGUOUS
   (unresolved_target/interface/virtual/function_reference)** — this
   PREEMPTS the local proof. This is why scope wraps alone don't flip
   rows that still have uncertain upstream edges.
6. no exact production path → EXTERNAL_ENTRY
7. context derivation: `localGuard=direct` → effective {direct}
8. all-guarded → PROVEN_HELPER / PROVEN_WORKER_MEDIATED; unguarded exact
   path → COUNTEREXAMPLE (definite violation, honestly classified)

Carrier classification (which lambdas are "uncertain"): every lambda
defaults to async-uncertain EXCEPT the admitted sets in
`callgraph.py`: canonical worker guard (`WorkerExecutionGuard.runGuarded`),
canonical direct scope (`DatabaseWriteBarrier.runWrite`), transparent
wrappers (withContext, Room withTransaction, DomainTransactionRunner.
runInTransaction), GR-14f `PRODUCTION_TRANSPARENT_INLINE_METHODS` (36
entries: kotlin scope/error/collection fns, withLock/withTimeout/
withTimeoutOrNull/collect/withPermit, project wrappers runOperation/
runCatchingCancellable/runPostCommitSafely/safeRecordMatchEvent/
setContent), GR-14j structured launches (`launch` on receivers
viewModelScope/scope/workTracker/serviceScope/diagnosticScope/
lifecycleScope/asyncScope/applicationScope), GR-14l composable lambdas
(non-launch lambdas inside `@Composable` callables) +
`_propagate_nested_transparency`.

Dispatch resolution (`_resolve_invocation`): interface-typed receiver →
INTERFACE_DISPATCH (uncertain); final-class receiver → GR-14r exact
(own or nearest-inherited implementation); open/override →
VIRTUAL_DISPATCH (uncertain); unknown receiver → UNRESOLVED_TARGET.
`_resolve_type` strips `" = "` default-value assignments (GR-14n) and
`?.` safe-call markers are stripped at call extraction (GR-14o).

## 3. ESTABLISHED PATTERNS (copy these, do not invent)

### Pattern A — canonical scope (the workhorse)
```kotlin
// imports: com.yourname.expensetracker.data.backup.DatabaseAccessOperation
writeBarrier.runWrite(
    DatabaseAccessOperation("<SimpleClassName>.<methodName>")
) { ...existing mutation block unchanged... }
```
Requires a constructor-injected `private val writeBarrier:
DatabaseWriteBarrier`. If the class lacks it, either inject it (if
@Inject-constructible; update tests) or SKIP and report. Do not re-indent
bodies. Real examples: git show dd9cf5f1 (clearForUser), 88870818 parent
(updateType). Counters: RecurringOccurrenceMaterializer has NO writeBarrier
— needs constructor injection first (GR-14r queue).

### Engine admission (closed reviewed sets)
New carrier types get admitted ONLY with: evidence grep, a closed
name-exact set + pin test, fixture coverage, shadow delta. Precedents:
GR-14f (inline table), GR-14j (structured launches), GR-14l (composables),
GR-14r (finality).

### Concretization
Single-@Binds interface → concrete class in injected fields. Verify
binding count FIRST (grep modules). Precedent: GR-14q (4 AI use-case
fields → Hybrid* impls). SKIP when composite/manual construction
(PrivacyGate — see GR-14t below).

### Pattern E — dead-writer removal
Triple-proof dead (engine zero-inbound + repo grep zero call sites +
compile success after removal), then remove code AND policy rows via
generation inputs ONLY (`docs/ci/db-findings/GR-08-seeds.yml` +
`config/guards/db_ownership_policy.legacy.yml`, then
`python scripts/migrate_db_policy_signatures.py --generate --seed-rows
docs/ci/db-findings/GR-08-seeds.yml`; NEVER hand-edit
`config/guards/db_ownership_policy.yml` — active realignment follows the
GR-14c direct-edit precedent, see GR-14h commit). Precedent: GR-14h.

## 4. VERIFICATION LOOP (every batch, no exceptions)

1. Baseline shadow (before edits), saved to `build/guard-debug/gr14<x>/`.
2. Edits.
3. `./gradlew :app:compileDebugKotlin --console=plain` (one Gradle at a
   time — AGENTS.md rule).
4. After shadow + delta compare: every changed row must be attributable;
   an EMPTY delta where you expected change is a DEFECT SIGNAL (it caught
   a mangled-regex bug in GR-14l).
5. Python batteries: `python -m
   scripts.db_guard.mediation_analysis.fixture_runner` (from repo root;
   currently 58/58, corpus 66 rows); mediation suites from
   `scripts/db_guard/mediation_analysis/` cwd (27 tests); CLI/carrier/
   launch/composable/finality modules via repo-root pytest.
6. Manifest `docs/ci/db-mediation/GR-14<x>.yml` (copy GR-14r.yml shape)
   with shadow sha256s, then commit.

## 5. THE REMAINING QUEUE (in order)

### GR-14s (IN PROGRESS — start here)
Scope the 2 processEmailReceipt counterexamples:
`ReceiptLifecycleCoordinator.kt::processEmailReceipt` (line ~738, ~350
lines long) rows `emailReceiptDao.insertOrIgnore` (line ~877) and
`pendingReviewDao.insert` (line ~1068). The entry
`checkWritesAllowed` already exists (try/catch at function top). Read the
FULL body first to find the enclosing mutation block structure (the two
sites appear to sit in a late block — the function has early-return dedup
sections before it). Wrap per Pattern A. Also consider: `recoverStale...`
pair REASSIGNED out of this batch — see GR-14u.
REASSIGNED to GR-14u: `RoomOperationRunRecorder.recoverStaleRunning-
OperationRuns` ×2 — grep-proven zero production callers (only a no-op
override at DatabaseBackupRepositoryImpl.kt:126) → removal candidate.

### GR-14t — PrivacyGate concretization (needs owner-approval premise)
`PrivacyModule.providePrivacyGate` manually builds `CompositePrivacyGate`
(no @Inject ctor). Plan: change provider return type to
CompositePrivacyGate + add `@Binds` for PrivacyGate, then concretize the
~31 injected `privacyGate: PrivacyGate` fields. This drains the
interface-dispatch class (2154 edges app-wide). File list:
grep -rln "private val privacyGate: PrivacyGate" app/src/main/java.
Full investigation in the gr-14q-a agent report (agent transcript) —
re-verify the binding map before editing.

### GR-14u — entry family (72 rows)
Per `docs/ci/db-mediation/GR-14H_PLAN.md`: dead-writer removals (Pattern
E triple-proof), framework-entry dispositions, plus:
recoverStaleRunningOperationRuns pair (zero callers), and the
name-match noise floor (Room/Activity `onCreate` overrides collide via
name-matched edges — the closure-taint source for several rows).

### GR-14s2/v — per-site tail
function_reference rows (14, `::method` callbacks — inline or
lambda-ize), interface_dispatch residue after GR-14t,
virtual_dispatch (2), `.PostCommitAction` (9 edges) and `.finally`
(19 edges) census labels (evidence-unclear — investigate before any
admission), RetentionModule.provideRetentionTargets (10 rows, DI module —
likely a different pattern, investigate).

### GATES (mandatory at merge)
GATE-00R double capture + Gradle DB task (policy bytes changed in
GR-14h — active sha 51f7d40a...), plus the unit-test-infra batch:
DbGuardPolicyFixtureTest residual 20 failures (parser fixed, count pins
owed), TLC hang residue, WarrantyTrackerRepositoryTest OOM/JPLIS
(pre-existing, A/B-proved twice).

## 6. GOTCHAS (learned the hard way — read twice)

1. **Tool-transport escape mangling**: `\b` in patch text can arrive as a
   literal backspace (0x08), silently breaking regexes. Write regex
   escapes via `chr(92)` assembly or verify with a direct unit probe.
   SYMPTOM: an engine change that "does nothing" (GR-14l empty first
   shadow; GR-14r open-class misdetected final).
2. **Empty delta = defect signal**, not success. Always verify the
   expected movements row-by-row.
3. **Relaxed writeBarrier mocks never invoke runWrite blocks** — tests
   exercising scoped writers need the pass-through stub (see
   RecommendationRepositoryTest setup):
   `coEvery { writeBarrier.runWrite(any<DatabaseAccessOperation>(),
   any<suspend () -> Any?>()) } coAnswers { secondArg<suspend () ->
   Any?>().invoke() }`.
4. `_resolve_type` cannot resolve FQCN-spelled property types — fixtures
   must use simple names + imports (production style).
5. Mediation test files (test_models/test_parser_spans/
   test_worker_dispositions) use flat imports — run pytest from
   `scripts/db_guard/mediation_analysis/` cwd.
6. Stale `__pycache__` after engine edits → clear under scripts/ if
   imports behave oddly.
7. Worktrees need `local.properties` copied from the main repo (SDK path).
8. Known PRE-EXISTING failures (A/B-proved, do not chase in GR-14
   batches): WarrantyTrackerRepositoryTest (executor OOM/JPLIS),
   TLC test hangs (partial), CrossGroupIntegrationTest x3,
   RecurringLifecycleCoordinatorTest.generateOccurrences x2,
   RecommendationRepositoryTest.saveAll (FIXED in GR-14q — 17/0).
9. `python` on PATH has pyyaml + pytest; use `.venv/Scripts/python.exe`
   only if you need its env.
10. Never run two Gradle commands concurrently. Never push. Never edit
    `config/guards/db_ownership_policy.yml` by hand (see GR-14h for the
    one sanctioned exception and its procedure).

## 7. MULTI-AGENT TOPOLOGY (proven, use for mechanical tranches)

One worktree + branch per agent off gr-14f-wip, FILE-DISJOINT partitions,
each agent: baseline shadow → edits → compile → after shadow → report
(no commits). Coordinator: apply diffs via `git -C <agent-wt> diff >
x.patch && git apply`, combined compile, ONE combined shadow, review
row-by-row, single commit. Two completed waves; agents may hit the
10-min inactivity timeout during Gradle waits — their worktrees keep the
work; verify and merge manually. Expected: ~2-2.5x wall-clock
compression vs sequential. Token cost ~5M/agent — budget accordingly.

## 8. DEFINITION OF DONE (GR-14 series, from the plan)

GR-12 direct counterexamples = 0 (met), GR-12 direct unsupported = 0
(met), GR-13 helper/worker unproven+counterexample = 0 (IN PROGRESS —
the 384-board), all active policy mutations have a proof result, THEN
GATE-00R recapture + Gradle DB task pass, truth-sync docs, and GR-15
begins per its own plan.
