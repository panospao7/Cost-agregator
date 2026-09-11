# GUARDRAIL ISSUES LEDGER — every open issue, its evidence, and what would close it

**Status date:** 2026-09-11
**Branch:** `gr-14f-wip` @ `2742e547` (GR-14u26/u27/u28 landed)
**Measured board:** `a9976208` — proven **301** / unproven **105**, counterexamples **0**

**Purpose.**  `GUARDRAIL_STATE_REPORT.md` is the *state* snapshot (what the guardrails are, what
the board says, the board anatomy).  This ledger is the *issue registry*: one entry per open
issue, with its evidence, its blast radius, the exact thing that would close it, and — where a
past claim turned out to be wrong — an explicit retraction so nobody re-raises it.

**Relationship to other docs.**  `DEFERRED_ISSUES_AND_DEBUG_HANDOFF.md` holds the original
per-defect narrative (sections A–E) written during the campaign; `GUARDRAIL_STATE_REPORT.md`
holds the current measurement and the defect register D1–D19.  This ledger **supersedes neither**
— it is the actionable index over both, plus the corrections that were made after them.

**Authority.**  This document is **`PLAN_OR_BACKLOG`**: it describes open issues and the work that
would close them.  It is **not** current-state authority — for current guard status, commands,
policy authority and baseline meaning, the authority chain is
`docs/ci/GUARD_EVIDENCE_INDEX.yml` and the generated references
(`docs/ci/GUARD_STATUS.generated.md`, `docs/ci/GUARD_COMMANDS.generated.md`).  Counts here are
reproducible from §G but should be re-measured rather than quoted stale.

**Evidence discipline.**  Every number here is either **measured** (a probe was run and its
output recorded under `build/guard-debug/`) or **verified** (a file was read and the claim is
quoted from source).  Anything **inferred** says so inline.  Where a claim rests on a heuristic
rather than a proof, the limitation is stated in the entry, not hidden.

---

## 0. How to read an entry

| Field | Meaning |
|---|---|
| **Status** | `OPEN` · `FIXED` · `RETRACTED` · `BLOCKED` · `OWNER-DECISION` · `LATENT` |
| **Class** | `ENGINE` (the proof engine) · `PRODUCTION` (app code) · `ANALYSIS` (methodology) · `INFRA` (build/test) · `CONTRACT` (barrier contract V1–V3) |
| **Blast radius** | what changes if this is acted on, and what breaks if it is acted on *wrongly* |
| **Closes with** | the specific artifact that would move this to `FIXED` |
| **Do NOT** | the failure mode to avoid — usually the mistake that produced the issue |

The 105 unproven rows decompose as follows.  **Careful: two different counts appear in this
ledger — ROWS and refusal FINDINGS.**  One row can emit several refusal findings, so the finding
counts in §B1 do **not** sum to the row counts here.  The table below is ROWS, and it does sum
to 105.

| Rows | Bucket | Evidence | Owner |
|---|---|---|---|
| 24 | zero-inbound — likely dead code | `GR13_ZERO_INBOUND_CALL_SITES` | **you** (owner decision, §C1) |
| 24 | unmodelable body — barrier present, `try`/`catch` blocks the model | `triage_remediation.py` class B | engine (§B1) |
| 22 | async-decided, local guard unprovable | class B | engine |
| 10 | D19 anonymous-object blind spot | `GR13_SITE_INSIDE_UNRESOLVED_LAMBDA` | engine, all-or-nothing (§B2) |
| 5 | unmodelable body — blocked by CFG scope wiring only | class C | engine/contract (§B3) |
| 20 | "no barrier in that file" — **RETRACTED**, see §E1 | class A | mostly resolved; 2 remain (§C2) |
| **105** | | | |

Cross-check by board bucket: ambiguous **37** = 24 + 5 + 8 (the 8 being class A) · async **44** =
22 + 12 (class A) + 10 (D19) · external **24** = 24.  ✅

---

## A. `ANALYSIS` — engine blind spots that produce **false alarms**

These are the most important entries in this ledger, because each one caused a *confident wrong
conclusion* during the campaign.  They are not bugs in the app; they are ways the analysis
misleads its own reader.

### A1 — File-level barrier counting is not "is this writer guarded?"

**Status:** RETRACTED-CLAIM · **Class:** ANALYSIS

**What happened.** `triage_remediation.py` classified unproven rows by grepping the row's **file**
for `checkWritesAllowed|runWrite`, and reported that 20 rows sit in files with *no barrier
reference at all* — read as "writers that do not participate in the mediation model", i.e.
unguarded production writes.  That was escalated as the one real risk remaining.

**Why it was wrong.** A writer's guard does not have to live in its own file.  Three
indirections defeat the file-level check, all verified present in this codebase:

1. **Decorator** — the guard lives in a `Composite*` wrapper and the interface binds to it
   (see A2).
2. **Collaborator** — the writer is only ever called from inside an already-guarded coordinator,
   so the *operation's* guard covers it (`ReceiptInsertResolver.insertOrResolve`: 7/7 callers
   guard).
3. **Registration** — the barrier appears in the file only as a DI-provided field, or not at all.

**Evidence.** `build/guard-debug/gr14w0/triage_remediation.py` (the wrong classification) versus
`build/guard-debug/gr14w0/sweep_decorators.py` (the caller-aware one).  The sweep's verdict on the
20 in-scope rows: **3** have all callers guarding, **7** mixed, **8** no guarding caller at 1 hop,
**2** zero-inbound — and reading the callers, the mixed/no-guard sets are dominated by categories
that are *supposed* to write during maintenance (backup/restore/export), sibling self-calls, the
audit path, and debug-only importers.

**Closes with.** Nothing further — the alarm is retracted.  See §E1.

**Do NOT** conclude "unguarded in production" from "no barrier in that file".  Count *bindings*,
not files.

### A2 — The guard can live in a maintenance-safe decorator

**Status:** OPEN (standing hazard) · **Class:** ANALYSIS / PRODUCTION

**What it is.** Several write interfaces are implemented twice: a raw `Room*` implementation that
writes to Room, and a `Composite*` wrapper that (a) checks `RestoreMaintenanceMode`, (b) calls
`checkWritesAllowed`, and (c) on `DatabaseAccessBlockedException` falls back to
`MaintenanceSafeDiagnosticSink` — a **DataStore ring buffer** rather than Room.

The engine resolves the mutation to the **raw** implementation, which legitimately contains no
barrier, so the row reads `unproven`.  That is an analysis limitation, not a missing guard.

**Verified instances** (read in full, 2026-09-11):

| Decorator | Covers | Shape |
|---|---|---|
| `CompositeDiagnosticEventWriter` | `RoomDiagnosticEventWriter.emit` (1 row) | mode check → `checkWritesAllowed` → `catch (DatabaseAccessBlockedException)` → safe sink |
| `CompositeOperationRunRecorder` | `RoomOperationRunRecorder.start` (4 rows) | same, plus `SafeSinkOperationRunHandle` |
| `WorkerExecutionGuard` | `WorkerRunLoggerImpl` (2 rows) | holds the logger; the guard itself is in the 67-file barrier list |

**Why the fallback is deliberate, not a bug.** If a diagnostic were blocked by the barrier, the
record *of the block* would be lost — you would lose exactly the evidence you want.  The
DataStore sink is the sanctioned escape hatch.

**Blast radius.** Acting on A1's original advice would have (i) added ~7 redundant guards where a
decorator already guards, and (ii) **broken the fallback**, because a raw-impl barrier would throw
inside the decorator's `try` and divert the record instead of writing it.

**Closes with.** If the engine should model this, the contract needs to know that an interface
binds to a decorator that guards — a modelling feature, not a guard to add.  Not currently
planned; the rows are honest `unproven`.

**Do NOT** add `checkWritesAllowed` to a diagnostic/audit/event sink that a decorator already
covers.

### A3 — The 1-hop caller heuristic is order-of-work strength, not proof strength

**Status:** OPEN (documented limitation) · **Class:** ANALYSIS

**What it is.** `sweep_decorators.py` decides "is this writer reached only from a guarding
caller?" by looking **one hop** up and asking whether the caller's **file** references the
barrier.  Both halves are approximations:

- a file may guard an *unrelated* method → **false "guarded"**;
- a guard may live **two or more** hops up, or on a path the sweep did not enumerate → **false
  "unguarded"**.

**Concrete instance of the second failure.** `OperationRunRecorder.finalizeNonCancellable` and
`WorkerRunLogger.terminal` are called **only by sibling methods in their own class**, so their
1-hop verdict ("NO CALLER GUARDS") carries no information; the class's real entry
(`CompositeOperationRunRecorder.start`, `WorkerExecutionGuard.startRunSafely`) does guard.

**Blast radius.** The sweep is strong enough to **retract** an alarm and to **order** work.  It is
not strong enough to justify editing a specific path.

**Closes with.** Before any change to one of these paths: read it **transitively, end to end**.
A transitive version of the sweep would be a real improvement — noted as a possible small task.

**Do NOT** cite this sweep as proof that a given path is guarded.

### A4 — `unproven_external_entry` is no longer a dead-code enumeration

**Status:** OPEN (standing rule) · **Class:** ANALYSIS

**What it is.** That bucket used to be usable as "these writers look unreachable".  Two changes
destroyed that reading:

1. **GR-14u27** — a self-guarded zero-inbound writer now **proves** (reason
   `GR13_ALL_PATHS_GUARDED`) instead of being reported zero-inbound, so the bucket no longer lists
   it.
2. **GR-14u28** — three `InvestmentTracker.addHolding` rows left the bucket because their
   `external_entry` label was an artefact of an unmodelable body, not evidence of dead code.

The bucket fell **27 → 24 with no writer changing**.

**Blast radius.** Deleting code on this label would delete live code.  The known false-"dead"
producers are: a caller inside an `init {}` block (§B4), members of an anonymous `object : T { }`
declared inside a function body (§B2), and any writer whose guard-decorator makes it look
unreferenced.

**Closes with.** Deadness must be re-derived per writer from source by call-site search, not read
off the board.

**Do NOT** delete a writer on an `external_entry` label alone.  Privacy-cleanup paths are the
sharpest case (AGENTS.md: cleanup must be able to run so it *can* delete raw data).

### A5 — "No barrier in file" on a restore/export path is expected, not a defect

**Status:** OPEN (documented) · **Class:** ANALYSIS

**What it is.** A whole family of callers legitimately runs **inside** maintenance windows:
`CostbackupBundle.buildZip`, `RestoreJournal.writeTextSynced`, `BackupEncryptionService.encrypt`,
`AccountingExportRepository.exportExpenses`,
`DatabaseBackupRepositoryImpl.{createCostBackup,importDatabase,restoreCostBackup,resetDatabase}`,
`RestoreDiagnosticsSink.event`.  `checkWritesAllowed` **throws** outside `NORMAL`, so adding a
guard here would not protect anything — it would break the feature.

**Closes with.** A modelled sanctioned form, in the style of GR-14u25 (`RestoreInternalWriteScope`
was *modelled*, not excused, and the row became `proven_restore_internal`).  Only worth doing if a
row matters for another reason.

**Do NOT** add a canonical guard to a restore/export path.

---

## B. `ENGINE` — open engine issues, with scope

### B1 — GR-12 control-flow refusals: four-plus features, not one

**Status:** OPEN · **Class:** ENGINE · **Rows:** **24** (of the 29 unmodelable); the numbering
below counts refusal **findings**, which is a different quantity — 29 rows produced 48 findings.

**What it is.** 29 rows read `unproven_ambiguous_call` with `localGuard == "none"` — the GR-14u15
tri-state working as designed: a barrier call *precedes* the mutation, so "definitely unguarded"
is not established, and the engine honestly reports *unproven*.  To prove them, the body model
must stop refusing.  Probe `build/guard-debug/gr14w0/probe_exception_flow.py` ran the real
tokenizer over all 29 bodies and enumerated the refusals (**findings**, not rows):

| Refusal code | Findings | Reasons |
|---|---|---|
| `DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED` | **19** | `labelled-return` 14, `coroutine-builder` 4, `elvis-block` 1 |
| `DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED` | **14** | `dangling-clause` 14 |
| `DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE` | 10 | `lambda-escape` 10 — **these 10 rows are §B2, not this entry** |
| `DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED` | 5 | `unknown-construct` 5 |

`triage_remediation.py` splits the same 29 rows into **24** blocked by `try`/`catch` and **5**
blocked by CFG scoping alone (§B3).

**Why it is not "just support try/catch".** The dominant shape is the campaign's most-repeated
guard idiom:

```kotlin
try {
    writeBarrier.checkWritesAllowed("...")
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    return Result.failure(e)          // ← requires labelled-return / dangling-clause handling
}
```

Proving it needs **both** the `return`-inside-`catch` handling *and* exception-flow modelling
before barrier-precedes-mutation dominance can be computed.  Supporting exception flow alone
moves almost nothing.

**Blast radius.** This is the largest single engine workstream (≈19 rows).  Each refusing feature
is a fail-closed check; relaxing one wrongly can up-rank an unguarded mutation to *proven*.

**Closes with.** **One fixture-first batch per feature**, each with its own Step-0 projection
against the then-current board, hard-stopping on any counterexample flip or proven→unproven
regression.  Suggested order by row yield: `labelled-return` (14) → `dangling-clause` (14) →
`unknown-construct` (5) → `coroutine-builder` (4) → `elvis-block` (1).

**Do NOT** attempt them as one diff.  Do not relax a refusal without a projection showing zero
counterexample flips.

### B2 — D19: anonymous-object members are not modelled as callables

**Status:** OPEN · **Class:** ENGINE · **Rows:** 10 mislabelled today, 44 member functions unmodelled

**What it is.** `object : T { ... }` is a **declaration**, but the call regex reads it as a **call
to the supertype with a trailing lambda**.  Two consequences:

1. the object body becomes a phantom `async` lambda region — measured **16** corpus-wide
   (`RetentionTarget` ×10, `WorkerLease` ×2, `PrivacyGate`, `Callback`, `RecognitionListener`,
   `PrivacySettingsRepository`, `AiSettingsRepository` ×1 each);
2. members declared inside a **function body** are never modelled as callables.  Census: **283**
   object expressions, **114** declaring `fun`s, **207** member functions total — **163 modelled,
   44 not**.

**Why it is deliberately NOT fixed alone (measured).** Dropping the phantom call on the u28 board:

```
10 rows  unproven_async_or_escaping_callback -> unproven_external_entry
 0 rows  -> proven_helper
 0 counterexamples, 0 regressions
```

It buys **zero proofs** and reclassifies 10 **live** privacy-cleanup targets
(`RetentionModule.provideRetentionTargets`, which performs the retention purges) as zero-inbound
code that looks safe to delete.  Combined with A4, that is the deletion trap.

**Blast radius.** Half-fixing it makes live privacy code look dead.  All-or-nothing.

**Closes with.** The member-modelling half in the **same diff**: callable discovery must descend
into function bodies, and an anonymous member's owner FQCN must be decided (so the members carry
inbound edges from the object-construction site and the rows land *guarded*, not *dead*).

**Do NOT** land the parser half alone.  Do not treat `RetentionModule.provideRetentionTargets` as
dead.

### B3 — CFG scope-wiring for the residual 5 rows

**Status:** OPEN (low value) · **Class:** ENGINE/CONTRACT · **Rows:** 5

**What it is.** `GroupTransactionCoordinator.deleteGroupAtomic` ×4 (mutations inside
`group?.let` / `linkedExpenses.forEach`) and `ExpenseRepository.updateExpenseCategoryBulk`
(`withLock`).  These already carry a preceding `checkWritesAllowed`; the mutation node is
disconnected because the CFG does not wire those lambdas as transparent scopes.

**Measured options (both projected clean, both declined as disproportionate):**

| Option | Yield | Cost |
|---|---|---|
| Admit 12 common inline names (`let`, `forEach`, `run`, `map`, `withLock`, …) name-exact | **+1 net row** | requires removing the anti-shadowing guard — these are *default-imported*, so there is no exact import to check; real fail-open surface for one row |
| Add one `withLock` wrapper (receiverless + exact import `kotlinx.coroutines.sync.withLock`) | **+1 row** | contract version bump for one row |

**Note:** the original estimate for this heading was "8 rows, CFG scope-wiring" — the real
defect was the **parser** bug fixed in GR-14u28 (bound scope not a candidate), which yielded 6.
See §E6.

**Closes with.** Only worth taking bundled with another contract change.

**Do NOT** remove the anti-shadowing import guard to buy one row.

### B4 — D1-II: `init {}` blocks are invisible to the engine

**Status:** LATENT (measured 0 live instances) · **Class:** ENGINE

**What it is.** Regex call-graph parsing attaches calls to `fun` callables only, so a call inside
a class `init {}` belongs to no callable and never enters the graph — the callee reads
**zero-inbound** rather than async-uncertain.  That is a **fail-open** shape: it looks like dead
code.

**Current measurement.** Re-measured 2026-09-10 over 1073 production files:
`build/guard-debug/gr14w0/census_init_u21.py` found **0** init-block calls into zero-inbound
methods.  Latent surface = 6 rows whose method *is* called from an `init {}`, none mislabelled
safe (`ReviewViewModel.recoverStuckReviews` is a proven helper; 4×
`CategoryViewModel.ensureDefaultCategories` already ambiguous; 1× `ReviewViewModel.emit` already
async-unproven).

**Why a synthetic `<init>` callable would not fix it.** It would itself be zero-inbound.
Construction of a framework-instantiated owner must inherit that owner's root kind — a larger
change.

**Closes with.** Fixture-first engine change when taken.

**Do NOT** delete any callee-first-called from an `init {}` block; audit it manually — do not
trust its `external_entry` label (A4).

### B5 — D9: carrier admission is a dead end (re-measured)

**Status:** OPEN (negative result — recorded) · **Class:** ENGINE

**What it is.** Admitting more lambda-carrier method names as `transparent` does not move rows,
because the GR-14f **resolution-preservation rule** deliberately keeps the name-matched uncertain
edge when an admitted carrier's calls do not bind to corpus targets.  A carrier admission can
only help where resolution *already* works.

**Re-measured 2026-09-11 on the u26 board:** admitting `coroutineScope` → **+0 proven, 1
counterexample**; the project suspend wrappers (`runWithRetry`, `withRateLimit`, `safeExecute`,
`safeLookup`, `guardTerminal`, `withBoundedTerminalWrite`) → **+0 rows**.

**Blast radius.** Spending cycles here yields nothing and costs a pinned-set change.

**Closes with.** Only revisit if a census shows a carrier whose calls already resolve exactly.

**Do NOT** start with "admit more async carriers".

---

## C. `PRODUCTION` / owner issues

### C1 — Zero-inbound writers: dead code, or mislabelled?

**Status:** OWNER-DECISION · **Class:** PRODUCTION · **Rows:** 24

**What it is.** The `unproven_external_entry` bucket.  Domains (pre-u28 counts): `ExpenseWriteStore`
(6), `GroupLifecycleCoordinator` (6), `InvestmentTracker`, `TransactionLifecycleCoordinator.deleteExpense`,
`ExpenseGroupDao.insertGroupWithMembers`, `BankApiIntegration.completeConnection`,
`BudgetForecastingEngine.updateForecastAccuracy`, `RecurringLifecycleEventWriter.writeDiagnostic`,
others.

**Two sub-meanings — do not conflate:**
- *genuinely dead* (never called) → safe to delete, **but verify by hand first** (A4);
- *called only from an `init {}`* → **active but unprovable** (§B4) → do NOT delete.

**Known-notable examples:** `ExpenseWriteStore` is a designed-but-unwired write layer (no inbound
caller outside itself; the only reference is a stale doc comment — defect D2).
`GroupLifecycleCoordinator.{archiveGroup,removeMember,recordSettlement,deleteGroupPermanently}` and
`SettlementCalculator.recordSettlement` are covered by contract/scenario tests only; the
`GroupTransactionCoordinator` routing they were designed for **was never built**.

**Closes with.** A per-writer owner decision backed by a hand call-site search.

**Do NOT** bulk-delete from the board.

### C2 — The two unexplained rows

**Status:** OPEN · **Class:** PRODUCTION · **Rows:** 2

After the sweep (§A1/A3), every residual "no-barrier-in-file" row falls into a legitimate category
*except two*, both already in the §C1 bucket:
`ExpenseGroupDao.insertGroupWithMembers` (zero-inbound) and
`RecurringLifecycleEventWriter.writeDiagnostic` (zero-inbound).

**Closes with.** Part of the C1 decision.  These are the only true unknowns in the production set.

### C3 — Debug-only plaintext financial payload (`DebugDataStorage`)

**Status:** OWNER-DECISION (privacy posture) · **Class:** PRODUCTION

**What it is.** `DebugDataStorage.save` (**debug variant only**) writes JSON containing parsed
transaction rows (amount, currency, merchant, date), parsing logs, and a **200-character preview**
of raw statement text (`DebugData.toJson` emits `rawText.take(200)`) to app-private storage in
**cleartext**.

**Why it is low severity.** The release variant is a compiled no-op stub; the location is
app-private (`filesDir`); only a preview, not the full statement, is persisted.

**Why it still deserves a decision.** AGENTS.md forbids persisting raw statement text and user
financial payloads at all; the file survives until `clear()` is called explicitly; and nothing
encrypts it even though `BackupEncryptionService` exists for the backup path.

**Blast radius.** Privacy posture, not a guardrail gap.  Not user-reachable in release.

**Closes with.** An explicit owner decision (encrypt, redact the preview, shorten retention, or
accept).

**Do NOT** treat this as a guardrail finding — the main-only scan scope is correct and must not be
widened to reach it (§E3).

### C4 — `GroupLifecycleCoordinator` routing was never built

**Status:** OWNER-DECISION · **Class:** PRODUCTION

Four coordinator methods plus `SettlementCalculator.recordSettlement` exist and are covered only
by contract/scenario tests; the `GroupTransactionCoordinator` routing intended to call them does
not exist.  Either the routing is pending work or the methods are dead — only the owner knows.
Related to the "production-dead but test-covered" tail in C1.

---

## D. `INFRA` — test-infrastructure blockers

These block broad test runs and therefore the merge gate.  Pre-existing and A/B-proved.  Full
detail: `GUARDRAIL_STATE_REPORT.md` §6.

| # | Issue | Effect | Note |
|---|---|---|---|
| A1 | byte-buddy agent self-attach fails → mockk cannot mock final classes; poisons the whole test JVM | 67 cascading failures in a trial run | **does NOT block GATE-00R** — see §E5 |
| A2 | `AndroidKeyStore` unavailable in the unit-test JVM | 2 `BankApiIntegrationTest` paths fail | environmental, deterministic |
| A3 | Gradle daemon native-OOM after many heavy runs | "daemon disappeared" | `./gradlew --stop`, retry |
| A4 | `WarrantyTrackerRepositoryTest` executor OOM / JPLIS crash | same family as A1 | same owner decision |
| B1 | `ReceiptLifecycleCoordinatorTest` 7 failures | — | stub `createExpenseDbOnlyV2`; `null()` vs `isNull()` matcher |
| B2 | `SavingsGoalsViewModelTest` 3 failures | — | per handoff §B2 |
| B3 | `SplitCalculationPrecisionTest` | **FIXED 2026-09-11** | largest-remainder allocation in integer cents |
| B4 | `verify_known_good_state` 11 failures | freshness "stamp=missing" | **GATE-00R debt**, not a code bug |

**Measured scale:** `TEST_FAILURE_LEDGER.md` records **121 pre-existing `domain.*` + 53 `data.*`**
failures plus a JVM instrumentation-agent crash that suppressed result XMLs.  The `scenarios`
package was never run.

**Current Python battery:** `scripts/ci` = **1244 passed / 13 failed / 14 skipped**; the 13 are the
identical pre-existing set (1 `test_test_result_freshness`, 11 `test_verify_known_good_state`,
1 `test_verify_production_source_roots`).  `scripts/db_guard` = **235 passed**.

### D2 — GATE-00R: what actually blocks it, in dependency order

**Status:** BLOCKED · **Class:** INFRA

1. **B4 freshness stamps** — require the Gradle DB task to run and stamp.
2. **The Python reds** — must be paid or formally dispositioned so `focused-python-tests` stops
   exiting 1 (they flow into the capture).
3. **`room/db-inventory`** must confirm inventory durability (currently exit 2
   `INVENTORY_DURABILITY_UNCONFIRMED`).

Also recorded from the attempt at `335758ff`: `gradle-db` and `gradle-task-graph` exit 1 (the
protocol's documented config-cache observation).  Operational rule: **never edit the tree while a
capture runs** — it corrupted run 1.

**Notably not on this list:** the mockk flag (§E5).

---

## E. RETRACTED / falsified claims — do not re-raise

Each of these was concluded during the campaign and later found wrong.  They are recorded so the
next reader does not spend cycles re-deriving the error, and so the *reason* is available.

### E1 — "20 unguarded production writers" → ≈0–2 rows

**Retracted 2026-09-11.**  Produced by the file-level counting error (A1).  After the caller-aware
sweep: 3 rows have all callers guarding, and the rest decompose into decorator-guarded (A2),
restore/backup/export paths that *should* be unguarded (A5), sibling self-calls, the audit path,
and debug-only importers.  Only the 2 zero-inbound rows are unexplained (§C2).  **No production
guard batch is warranted on current evidence.**

### E2 — "Async is spread with no dominant fix; measured ceiling ~0 rows"

**Half-wrong, corrected 2026-09-11.**  The *carrier-admission* ceiling is real and re-measured
(B5).  But the conclusion that the bucket was therefore capped was badly wrong: **89 of the 133
async rows already had `localGuard == "direct"`** — the dominance engine had *already proved* their
guard dominates the mutation, and the prover discarded it because a caller was uncertain.  That
was D17, the largest batch of the campaign (94 rows).

**Lesson worth keeping:** when a bucket looks capped, check whether the rows are blocked by
*missing evidence* or by *evidence the engine refuses to use*.  Measure `localGuard` before
declaring a ceiling.

### E3 — "Widen the scan scope to `debug`/`release` — a one-line manifest change"

**Refuted 2026-09-11 (D15).**  Audited: the main-only scope is a **deliberate, triply-enforced,
already-tested** boundary — the manifest schema rejects a non-`main` `sourceSet`
(`production_source_scope.py:494-499`), the approved-roots contract rejects variant policy paths
(`POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT`), and
`test_production_root_includes_only_production_dao` pins it.  Widening it would defeat the
boundary the test exists to protect.  **No config change is warranted.**  The only residual item
is C3, which is a privacy decision, not a guardrail gap.

### E4 — "`exact_synchronous` fell 32 → 27 after GR-14u27"

**Corrected 2026-09-11.**  Wrong.  `exact_synchronous` stayed at **32** through u27 — those rows
are `localGuard == "none"` and u27 does not reach them.  The 5 rows u27 proved from
`unproven_ambiguous_call` were the **`unresolved_target`** population (13 → 8).  `exact_synchronous`
later fell to **29** via u28 (bound-scope recognition).  Lesson: verify a per-bucket delta against
the board; a bucket total moving does not tell you *which* sub-population moved.

### E5 — "Fixing test-infra A1 unblocks GATE-00R"

**Corrected 2026-09-11.**  Wrong, and the distinction matters for sequencing.  A1 affects broad
*Kotlin unit-test* runs; the GATE-00R capture runs different components (D2), none of which is the
mockk path.  A1 is real but separate: its only identified consumer (GR-14t) was abandoned as
zero-row, and the handoff records its fix as an **owner decision requiring its own A/B batch**.
The one-line fix location is `app/build.gradle.kts` (`unitTests.all { … jvmArgs(…) }`, hook at
:206) — deliberately **not** applied, because a build-config change on a speculative basis is not
justified by evidence.

### E6 — "CFG scope-wiring: 8 rows, 4 methods"

**Corrected 2026-09-11.**  The estimate was wrong and the diagnosis was wrong.  The real defect
was a **parser** bug: `_RE_TS_SCOPE` was anchored on `receiver.method {`, so a **bound** scope
(`val id = db.withTransaction { }`) was never a scope candidate, its lambda escaped, and the whole
callable was rejected as unmodelable — discarding a barrier that already dominated the mutation.
Allowing a declaration/assignment prefix fixed **6 rows** with **no** contract change, because
admission stays exact.  What remains under this heading is 5 rows (B3), not 8.

### E7 — "The `interface_dispatch` bucket is worth 20 proofs"

**Corrected earlier in the campaign.**  Honest yield was **10** of 20; the other 10 stayed unproven
because making the interface edge exact merely **promoted the next uncertain edge**, and for 8 of
those the newly-deciding edge was `GR13_LOCAL_GUARD_UNMODELABLE` — i.e. those rows were *always*
unmodelable and the interface edge had been masking it.  Attribution error was compounded by
comparing against the wrong baseline (`fe40d369` is pre-u22; u24's projection already contained
u22's effect).

---

## F. FIXED — for completeness (each with its board)

| ID | Defect | Batch | Board after |
|---|---|---|---|
| D1-I | self-scoped helper misreported as external entry | GR-14u5 | — |
| D2 | `ExpenseWriteStore` designed-but-unwired (**still an owner decision**, §C1) | — | — |
| D3 | name-match noise floor / `super` receiver | GR-14u6 | — |
| D5 | direct-site prover missed bars at the mutation offset | GR-14u7 | — |
| D6 | multi-star imports unresolved (`RoomDatabase`) — hid 137 rows | GR-14u19 | — |
| D7 | 18 D6 flips → 3 engine bugs + 1 real write | GR-14u11–u17 | — |
| D8 | Kotlin arrow (`->`) counted as a generic close bracket — hid 91 rows | GR-14u20 | — |
| D9 | carrier admissions ≈0 rows | measured | — |
| D4 | `interface_dispatch` residue (20 rows → 10 proofs) | u23/u24/u25 | `6a18b4bf` |
| D10 | `_inherits_from` walked only the first supertype (**latent fail-open**) | GR-14u21 | latent, 0 rows |
| D13 | restore-internal scope inexpressible in the contract | GR-14u25 (V3) | — |
| D14 | `restore_internal` collapsed to `worker` in propagation | GR-14u25 | — |
| D16 | `::` references carried no receiver — hid 14 rows | GR-14u26 | `04e6a21a` |
| D17 | **a proved local guard was discarded when any inbound edge was uncertain — hid 94 rows** | GR-14u27 | `8d813a25` |
| D18 | a bound transparent scope was not a scope candidate — hid 6 rows | GR-14u28 | `a9976208` |

**Five real unguarded production writes** were found and fixed earlier in the campaign (all by
Step-0 HARD STOPs on counterexample flips): the notification-capture writers (u6), the
`TransactionLifecycleCoordinator` unqualified-wrapper guards (u17), `LegacyDataMigrationService.migrateCategories`
(u18), `ExpenseRepository.updateExpenseMerchant` (u20), `BankApiIntegration.refreshToken` (u20).
**Pattern in every case: the code looked guarded — the class injected `DatabaseWriteBarrier` and
guarded its other methods.  The gap was one branch, one wrapper form, or one cross-table side
effect.**

---

## G. How to reproduce every number in this ledger

```bash
# The board (shadow-only, ~5 min/run; run twice — they must be byte-identical)
python scripts/ci/inspect_db_mediation_proof.py --output build/guard-debug/gr14u28/board_run1.json
# expect sha256 a997620894f506a568dec19da7cf6e66d1ae6a7665f67064096dbb5bae2a8d37
#   proven_helper 286 | restore_internal 1 | worker 14 | ambiguous 37 | async 44 | external 24

# Bucket -> remediation-class mapping (§0 table, §B1)
python build/guard-debug/gr14w0/triage_remediation.py

# GR-12 refusal census (§B1)
python build/guard-debug/gr14w0/probe_exception_flow.py

# Anonymous-object census (§B2)
python build/guard-debug/gr14w0/probe_anon_objects.py

# Caller-aware decorator/guard sweep (§A1, §A3)
python build/guard-debug/gr14w0/sweep_decorators.py

# The exact-rows dump used by the sweep (§C2)
python build/guard-debug/gr14w0/dump_prod_rows.py

# Engine + guard batteries
python -m pytest scripts/ci -q
python scripts/db_guard/mediation_analysis/fixture_runner.py     # 64/64
python -m pytest scripts/db_guard -q                             # 235
```

**Board determinism is a gate, not a nicety:** if a double run differs, the change is not
committable until it is deterministic.

---

## H. Standing rules distilled from this ledger

1. **Count bindings, not files** (A1).  "No barrier in that file" ≠ "unguarded".
2. **Check for a decorator before adding a guard** (A2).  `Composite*` +
   `MaintenanceSafeDiagnosticSink` is the guard, and the fallback is the point.
3. **A 1-hop/file-level sweep orders work; it does not justify a change** (A3).  Read the path
   transitively before editing it.
4. **Never delete a writer on an `external_entry` label alone** (A4).  Three known false-"dead"
   producers: `init {}` callers, anonymous-object members, decorator-guarded sinks.
5. **Never add a guard to a restore/export/maintenance path or a privacy-cleanup path** (A5,
   AGENTS.md).  Those must be able to run; model the form instead (GR-14u25 precedent).
6. **Measure `localGuard` before declaring a bucket capped** (E2).
7. **One feature per batch; project before landing; hard-stop on any counterexample flip or
   proven→unproven regression.**
8. **Don't claim a fix without a board sha and a deterministic double run.**
9. **Record negative results** — the D9/B5 and D15/E3 entries exist so they are not re-investigated.
10. **A bucket total moving does not tell you which sub-population moved** — verify per-bucket
    deltas against the board (E4).
