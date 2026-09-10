# GUARDRAIL STATE REPORT — database write-barrier proof engine

**Status date:** 2026-09-10
**Branch:** `gr-14f-wip` @ `0f5cd860`
**Audience:** anyone continuing the GR-14 guardrail campaign, and anyone looking for
production database-write bugs.  This document is a *state* report: what the guardrails
are, what the measured state is, every known issue with its status, and the production-code
findings the guardrails have surfaced.  The companion `DEFERRED_ISSUES_AND_DEBUG_HANDOFF.md`
holds the full narrative detail per defect (sections A–E, D1–D9); this report is the index
into it and the summary that stands alone.

Everything numeric below is **measured**, not estimated.  Where something is inferred or
unverified it says so explicitly.

---

## 1. What the guardrails actually are

### 1.1 The write barrier (production mechanism)

`com.yourname.expensetracker.data.backup.DatabaseWriteBarrier` is the single production
guard for database writes outside normal operation.  Two forms:

| Form | Call | Meaning |
|---|---|---|
| direct check | `writeBarrier.checkWritesAllowed("Owner.method")` | Throws `DatabaseAccessBlockedException` unless `RestoreMaintenanceMode` is `NORMAL`. |
| guarded scope | `writeBarrier.runWrite(op) { ... }` | Same check, then runs the block. |

`RestoreMaintenanceMode` holds the current mode; writes are allowed only in `NORMAL`.
During backup restore / maintenance, any write reaching the barrier is rejected — this is
what stops a background writer from corrupting a database being restored.

Guard coverage today: **317 guard call sites across 67 production files.**

### 1.2 The canonical contract (the single source of truth)

`CANONICAL_BARRIER_CONTRACT_V2` (`scripts/db_guard/structural_analysis/barrier_proof.py`)
pins, immutably:

- `receiver_fqcn` = `...data.backup.DatabaseWriteBarrier`
- `direct_check_methods` = `("checkWritesAllowed",)`
- `guarded_scope_methods` = `("runWrite",)`
- `transparent_scope_wrappers` = `withTransaction` (Room `RoomDatabase`/`AppDatabase`),
  `runInTransaction` (`DomainTransactionRunner`), `withContext` (`kotlinx.coroutines`)

**Contract changes require a version bump (V2 → V3) and a dedicated reviewed diff.**  No
batch in this arc has needed one.

### 1.3 The proof engine (the guardrail *on* the guardrails)

The mediation engine asks, for every database mutation named by policy: *is there a canonical
guard that dominates this write on every reachable path?*  Pipeline:

```
production Kotlin  →  CallGraphBuilder        (call graph, receiver resolution)
                   →  MediationProver         (tiered proof over the graph)
                   →  _DirectSiteProver       (GR-12 dominance proof within one body)
                   →  policy row verdict
```

Tier-5 proof assigns each policy row one **proof state**:

| Proof state | Meaning | Safe? |
|---|---|---|
| `proven_helper` | Guarded by its own canonical barrier call. | ✅ |
| `proven_worker_mediated` | Worker path, guard via `WorkerExecutionGuard`. | ✅ |
| `unproven_ambiguous_call` | Some call edge in the path is not exact. | ⚠️ unknown |
| `unproven_async_or_escaping_callback` | Reached only through an async/escaping lambda. | ⚠️ unknown |
| `unproven_external_entry` | Zero inbound call sites (dead or externally entered). | ⚠️ unknown |
| `counterexample_unguarded_call_path` | **Definitely unguarded on a reachable path.** | ❌ |

**The design rule: fail closed.**  When the engine cannot decide, it reports *unproven* —
never *proven*.  An `unproven_*` row is not evidence of a bug; it is the absence of proof.
Only `counterexample_*` asserts a violation.

### 1.4 The GR-12 direct-site prover is tri-state (not boolean)

Within one callable, `_DirectSiteProver` returns one of:

- **`proven`** — a canonical barrier dominates the mutation site.
- **`unguarded`** — the body was fully modelled and no barrier dominates. → counterexample.
- **`unmodelable`** — the body could not be modelled **and** a canonical barrier call
  *precedes* the site (position-aware). → reported *unproven*, never a counterexample.

This tri-state exists because "not proven" in the body model is *absence of evidence*, not
evidence of absence.  Before it (pre-GR-14u15) every unmodelable body was reported as an
unguarded call path.  **This is why 24 current rows read `unproven` — see §3.4.**

### 1.5 Carrier classification

A lambda region is classified by its carrier, which decides whether it can launder guard
context:

- **transparent** — inline/structured carriers; context inherited, edge exact. Closed
  reviewed set `PRODUCTION_TRANSPARENT_INLINE_METHODS` (`let`, `apply`, `runCatching`,
  `use`, collection operators, `withLock`, `withTimeout`, `collect`, `withPermit`,
  `runOperation`, `setContent`, …) plus structured-launch receivers
  (`viewModelScope`, `lifecycleScope`, `serviceScope`, …).
- **canonical_direct / canonical_worker** — the guard scopes themselves.
- **async / unresolved_scope / escaping** — can launder context; edge stays uncertain.

Any member change to these closed sets requires a reviewed diff with fixture coverage and a
shadow before/after delta.

### 1.6 The Step-0 gate (the safety methodology)

Every engine change this campaign **must** be projected before landing:

1. Write a fixture pin that is **RED before** the change (proves it is load-bearing).
2. Re-run the board with the change monkeypatched at runtime and diff it against the
   committed board (`build/guard-debug/gr14w0/project_*.py` pattern).
3. **HARD STOP on any transition into `counterexample_*` or any `proven → unproven`** —
   triage before landing, never land through a flip.
4. Land only with: fixture pins, engine battery, `scripts/db_guard`, fixture_runner,
   targeted Kotlin compile, deterministic double-run, and unchanged policy/baseline bytes.
5. Record a batch manifest in `docs/ci/db-mediation/GR-*.yml`.

**This gate has caught real defects three times** (§5): GR-14u6 (5 unguarded writers),
GR-14u19 (18 false flips → the whole D7 chain), GR-14u20 (2 unguarded writers).  It is the
single most valuable practice in this campaign — do not skip it for "obvious" changes.

---

## 2. Measured state (2026-09-10)

### 2.1 Board

Report sha256 **`fe40d36915c153dc4894acf0905dc572cee8767bc9d49284f85c9b6082b7133a`**
(deterministic across a double run).  406 policy rows.

| Bucket | Count |
|---|---|
| `proven_helper` | **155** |
| `proven_worker_mediated` | 14 |
| `unproven_ambiguous_call` | 77 |
| `unproven_async_or_escaping_callback` | 133 |
| `unproven_external_entry` | 27 |
| **`counterexample_unguarded_call_path`** | **0** |
| **proven / unproven** | **169 / 237** |

Policy sha256 `7851adc2f21805246df175790993a0677dadcac4604acb8f78a98b89b7ab6a31` —
434 entries, 0 load errors.  Baseline/exception bytes unchanged.  Report is shadow-only
(`reportOnly: true`).

**Movement landed in this session** (each delta from its own manifest, not estimated):

| Batch | Effect | Board after |
|---|---|---|
| GR-14u17 | guard visibility (0 rows; enabled the rest) | helper 69 |
| GR-14u18 | guarded `migrateCategories` (0 rows alone) | helper 69 |
| GR-14u19 | multi-star resolution | helper 69 → 87 |
| GR-14u20 | arrow-aware parameter split + 2 guards | helper 87 → **155** |
| GR-14u21 | complete supertype traversal (latent, 0 rows) | helper 155 |

Net this session: **proven_helper 69 → 155** (proven 83 → 169), counterexamples **0
throughout** — the live board never carried one.  (The "18 counterexamples" seen during
GR-14u19 were a *projection* of the unlanded change, which is what triggered the D7
investigation.)

Campaign-wide, GR-14u5 → u21: proven_helper **48 → 155**.

### 2.2 Validation status

| Check | Result |
|---|---|
| engine battery (`scripts/ci/test_gr14*.py`, …) | **99 passed** |
| `scripts/db_guard` unit tests | **235 passed** |
| fixture scenarios | **58 / 58** |
| board determinism (double run) | byte-identical |
| Kotlin `:app:compileDebugKotlin` | PASS (last run GR-14u20) |

**The full Kotlin test suite is NOT a usable gate** — see §6.  Prefer targeted
`:app:compileDebugKotlin` and `--tests "*Class*"` filters.

### 2.3 Batch history

39 manifests in `docs/ci/db-mediation/` (GR-12, GR-13 ×2, GR-14a–t, GR-14u ×1 + u2–u21).
Each newer manifest carries its delta, evidence and validation status.

---

## 3. Board anatomy — why each unproven row is unproven

Post-GR-14u20 census (`build/guard-debug/gr14w0/census_u20.log`, exact reconstruction
fidelity: report status reproduced for **every** row).  Buckets sum: 133 + 27 + 77 = 237.

### 3.1 `unproven_async_or_escaping_callback` — 133 rows

All 133 are decided by an **async carrier**.  Spread across carriers with no dominant fix:
`coroutineScope`-region 37, privacy-gate lambda 23, `PostCommitAction` 19,
`navigation.launch` 17, `confirmQuickApprove` 9, `photon`-region 6, plus a long tail.

**Not the next win** — measured ceiling is ~0 rows, see §4.D9.

### 3.2 `unproven_external_entry` — 27 rows

Decided by `external_entry`: zero inbound call sites. Domain:
`ExpenseWriteStore` (6), `GroupLifecycleCoordinator` (6), `InvestmentTracker` (2),
`TransactionLifecycleCoordinator.deleteExpense`, `ExpenseGroupDao.insertGroupWithMembers`,
`BankApiIntegration.completeConnection`, `BudgetForecastingEngine.updateForecastAccuracy`,
`RecurringLifecycleEventWriter.writeDiagnostic`, others.

**Two sub-meanings** — a reader must not conflate them:
- *Genuinely dead code* (never called): safe to delete, but **owner decision** (§5.3).
- *Called only from an `init {}` block* (engine invisible): **active but unprovable** —
  do NOT delete.  See §5.2 Defect II.

### 3.3 `unproven_ambiguous_call` — 77 rows, by deciding resolution

| Deciding resolution | Rows | What it means |
|---|---|---|
| `exact_synchronous` | **24** | Guard present, body **unmodelable** — see §3.4 |
| `interface_dispatch` | 20 | Interface-typed receiver; see §4.D4 |
| `unresolved_target` | 19 | Receiver or target not resolved |
| `function_reference` | 14 | `::method` callbacks |

### 3.4 The 24 `exact_synchronous` rows — CORRECTLY unproven, not a defect

**Investigated this session; hypothesis falsified.**  These rows read
`unproven_ambiguous_call` *with* an exact deciding edge, which looks contradictory.  It is
not.  All 24 are the **GR-14u15 tri-state** working as designed
(`GR13_LOCAL_GUARD_UNMODELABLE`, `barrierMode=helper`, `localGuard=none`):

- **24 / 24 contain a canonical `checkWritesAllowed` call** — the guard *is* present.
- **20 / 24 contain `try` / `catch`**, which the GR-12 body model refuses (exception flow
  above all).
- The other 4 (`ExpenseRepository.updateExpenseCategoryBulk`,
  `SubscriptionManagerEngine.acceptCandidate` / `validateAndCreate`) place the guard first
  and mutate inside `withLock` / `database.withTransaction` lambdas — the CFG does not wire
  scope children, so the mutation node is disconnected and the body is unmodelable.

Representative (`BankStatementLifecycleProcessor.processBankStatement:139`):

```kotlin
try {
    writeBarrier.checkWritesAllowed("BankStatementLifecycleProcessor.processBankStatement")
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    return Result.failure(e)          // ← fail-closed: barrier blocks ⇒ no write happens
}
```

Because a barrier call **precedes** the mutation, "definitely unguarded" is not established,
so the engine correctly reports *unproven* rather than inventing a violation.  **These 24
rows are the lowest-risk unproven rows on the board** and should not be triaged as suspicious.
To ever prove them, the GR-12 model would have to handle exception flow — a large engine
change, not a quick fix.

---

## 4. Engine defects (known issues, with status)

| # | Defect | Severity | Status |
|---|---|---|---|
| D1-I | Self-scoped helper misreported as external entry | proof only | ✅ **FIXED** GR-14u5 |
| D1-II | Class `init {}` blocks invisible to the engine | **latent fail-OPEN** | ⏸️ DEFERRED (measured latent) |
| D2 | `ExpenseWriteStore` designed-but-unwired | proof only | ⏸️ OWNER DECISION |
| D3 | Name-match noise floor / `super` receiver | **hid real bugs** | ✅ **FIXED** GR-14u6 |
| D5 | Direct-site prover missed bars at the mutation offset | proof only | ✅ **FIXED** GR-14u7 |
| D6 | Multi-star imports unresolved (`RoomDatabase`) | hid 137 rows | ✅ **FIXED** GR-14u19 |
| D7 | The 18 D6 counterexample flips → 3 engine bugs + 1 real write | mixed | ✅ **FIXED** GR-14u11–u17 |
| D8 | Kotlin arrow (`->`) counted as a generic close bracket | hid 91 rows | ✅ **FIXED** GR-14u20 |
| D9 | Carrier admissions capped at ~0 rows | methodology | ✅ **MEASURED**, plan corrected |
| D4 | `interface_dispatch` residue (20 rows) | proof only | 🔶 **PARTIAL** — see below |
| D10 | `_inherits_from` followed only the first supertype | **latent fail-OPEN** | ✅ **FIXED** GR-14u21 |

### 4.D4 — `interface_dispatch` (20 rows) — the most interesting OPEN item

Every one of the 20 deciding edges already has exactly **one** override target, and a
complete implementor walk agrees (1 == 1) for all six receivers.  So an engine rule
"exactly one implementor ⇒ exact dispatch" would resolve all 20 **with no production
change** (so the A1 test-mocking blocker does not apply).

**It is unsound as-is, for two measured reasons:**

1. `_override_targets` under-counted implementors — **now fixed** (GR-14u21, D10).  The
   decisive false-unique was `WorkerDrainController.requestStopAndAwaitDrain`: engine
   reported 1 target, the complete walk finds 2.  A rule built on the old count would have
   claimed exactness on a two-implementor interface.
2. **Anonymous `object : Iface { }` implementors are not owners at all.**  The owner table
   holds **zero** anonymous entries, yet the corpus has 12 `object : PrivacyGate` and one
   `object : WorkerLeaseRegistry` (`RestoreMaintenanceMode.kt:36`).  So even a *complete*
   owner walk cannot see them; `WorkerLeaseRegistry` is a second false-unique by this route.

**Remaining work is now precisely scoped:** (a) ✅ done (GR-14u21); (b) teach the engine to
recognise `object : T { }` expressions as implementations of a resolved corpus interface —
a new capability needing its own fixture corpus and projection; (c) *then* the exactness
rule over named **and** anonymous implementors.  For the six row receivers a textual scan
shows zero anonymous implementors, so (c) would fix all 20 — but (b) must come first or it
is unsound elsewhere.

### 4.D9 — why carrier admissions are NOT the next win

`coroutineScope` was the largest deciding carrier (37 rows) and is squarely admissible on
the inline table's own criterion 2.  Projected and **not landed**:

```
proven_helper 155 -> 155 (+0)   unproven_ambiguous 77 -> 82   async 133 -> 128
5 changed rows, ALL async -> ambiguous;  0 counterexamples, 0 regressions
```

Cause: the GR-14f **resolution-preservation rule** deliberately keeps the name-matched
uncertain edge when an admitted carrier's calls do not bind to corpus targets (otherwise
reverse-reachability shrinks and real inbound evidence is lost).  So a carrier admission can
only help where resolution *already* works.  **Do not spend cycles on
`PrivacyGate` / `PostCommitAction` / `navigation.launch` / `confirmQuickApprove` expecting
row movement** — each is capped the same way and costs a pinned-set change.

### 4.D1-II — `init {}` invisibility (LATENT; 0 live instances as of GR-14u8)

Regex call-graph parsing attaches calls to `fun` callables only, so a call inside a class
`init {}` block belongs to no callable and never enters the graph (zero inbound rather than
async-uncertain).  Measured (GR-14u8, read-only, 1073 files):

- **0** of that era's `unproven_external_entry` rows were called from an `init {}` block — so
  there is **no live fail-open instance**.  All of them are zero-inbound for other reasons.
  ⚠️ *This was measured at GR-14u8 when the bucket held 29 rows; the current bucket is 27 and
  the zero-instance conclusion has not been re-measured against it.  Re-run
  `build/guard-debug/gr14u8/census_init_broad.py` before relying on it.*
- **Latent surface = 6 rows** whose method *is* called from an `init {}`:
  `ReviewViewModel.recoverStuckReviews` (proven helper), 4×
  `CategoryViewModel.ensureDefaultCategories` (already ambiguous), 1×
  `ReviewViewModel.emit` (already async-unproven).  None is mislabelled safe.

**Rule for humans (important):** before deleting any callee-first-called from an `init {}`
block, audit it manually — do not trust its `unproven_external_entry` label.  A synthetic
`<init>` callable alone would not fix it (it would itself be zero-inbound); construction of a
framework-instantiated owner must inherit that owner's root kind.  Larger engine change —
fixture-first plan required.

### 4.D10 — `_inherits_from` first-supertype walk (FIXED, latent)

`_inherits_from` walked only the **first** resolvable corpus supertype per hop, so
`class Impl : Other, Iface` never reached `Iface`.  Both call sites are override
enumeration.  Two under-approximations, **both in the fail-OPEN direction**:

- `_override_targets` under-counted implementors: 11 (interface, method) pairs.  Decisive
  false-unique: `WorkerDrainController.requestStopAndAwaitDrain` → engine 1, complete 2.
- `_has_override_named` could return False although an override exists, letting
  `_resolve_invocation` (`callgraph.py:1968`) fall through to an **EXACT** edge on an
  overridden member — the engine would prove a virtually-dispatched call exact.

Fixed in **GR-14u21**: traversal is now complete and transitive, cycle-safe.  **Zero board
movement by design** (projected: 0 changed rows; live board byte-identical afterwards), so
no live row was ever mis-proved.  Landed as its own batch with its own pins precisely so it
is not credited with a delta it does not have.

---

## 5. Production-code findings (the value for the real codebase)

These are findings about **production code**, surfaced by the guardrails.  Each was verified
against source, not inferred from the board alone.

### 5.1 FIXED — genuinely unguarded database writes

All five were found because the Step-0 gate HARD-STOPPED on counterexample flips.  Each was
a real unguarded write on a reachable path, now guarded with the canonical form and
confirmed by `:app:compileDebugKotlin`.

| # | Location | The unguarded write | Found by |
|---|---|---|---|
| 1 | `NotificationIntakeCoordinator`, `NotificationIntakePayloadRepairer`, `NotificationIntakeRecoveryScheduler` | 5 unguarded notification-capture DB writers, hidden by a false `super.onCreate` taint | GR-14u6 |
| 2 | `TransactionLifecycleCoordinator` (14 call sites) | guards went through an **unqualified private wrapper**, invisible to the receiver-based scan → guards were not enforced as written in the analysis | GR-14u17 |
| 3 | `LegacyDataMigrationService.migrateCategories` | wrote `categoryDao.insert` into the **live** DB with **zero** barrier references in the file; debug-only path but real DB writes during a restore | GR-14u18 |
| 4 | `ExpenseRepository.updateExpenseMerchant` | `pendingReviewDao.bulkRenameMerchant` unguarded on the `applyToAll` branch (the expense write routed through the coordinator; this cross-table write did not). Reachable: `TransactionsViewModel.updateMerchant` → user edits merchant "apply to all" | GR-14u20 |
| 5 | `BankApiIntegration.refreshToken` | `bankConnectionDao.updateToken` unguarded on the stub token-refresh path. Reachable: `BankConnectionsViewModel.syncConnection` → `BankConnectionLifecycleCoordinator` → `syncTransactions` → `refreshToken` | GR-14u20 |

**Pattern worth internalising:** in every case the code *looked* guarded — the class injected
`DatabaseWriteBarrier` and guarded its other methods.  The gap was always in one branch, one
wrapper form, or one cross-table side effect.

### 5.2 OPEN — needs an owner decision (real code, unproven or dead)

| # | Finding | Nature | Evidence |
|---|---|---|---|
| 1 | `ExpenseWriteStore` — a designed-but-unwired write layer | Entire class has **no** inbound caller outside itself; only reference is a stale doc comment | D2, engine zero-inbound |
| 2 | `GroupLifecycleCoordinator` — `archiveGroup`, `removeMember`, `recordSettlement`, `deleteGroupPermanently`; `SettlementCalculator.recordSettlement` | Covered by contract/scenario tests only; the `GroupTransactionCoordinator` routing was **never built** | GR-14u21 measurement + handoff §E.3 |
| 3 | `InvestmentTracker` (`addHolding`, `updatePrice`) | Production-dead, test-covered | §E.3 |
| 4 | `NotificationRepository.deleteAllNotifications`, `BankApiIntegration.completeConnection`, `SubscriptionManagerEngine.recordPriceChange`, `AiArtifactRepositoryImpl.deleteByTargetKey` | Production-dead, test-covered | §E.3 |
| 5 | `ExpenseGroupDao.insertGroupWithMembers`, `RecurringLifecycleEventWriter.writeDiagnostic`, `BudgetForecastingEngine.updateForecastAccuracy` | Zero inbound | §3.2 |

**Do not delete these on the engine's word alone.**  A zero-inbound verdict is only
trustworthy now that receiver resolution works (post GR-14u19/u20) — but §5.3 Defect II means
an `init {}`-only caller is still invisible.  Audit before removal.

### 5.3 OPEN — potential production money/logic bugs (not guardrail issues)

These came up in the same arc and are **not** write-barrier problems, but they look like real
defects.  Listed here so they are not lost.

1. **`SplitCalculationPrecisionTest` — percentage split rounding.** `expected 0.03 but was
   0.04, outside tolerance 0.01`.  Float/rounding semantics in percentage split allocation.
   **AGENTS.md money rules apply**: analyse the rounding mode and the sum-of-parts invariant;
   do NOT fix by weakening the tolerance.  Likely needs largest-remainder allocation.  *This
   is the most likely real production money bug on this list.*
2. **`BankApiIntegrationTest` — 2 failures that look like stale expectations** vs the current
   `STRICT_EXTERNAL_ID` hashing behaviour (`same provider transaction id yields stable strict
   dedupe identity` expected 1 got 2; `low confidence …` expected `low-conf-1` got a hex
   hash).  The sibling test `mapTransactionToExpense uses STRICT_EXTERNAL_ID with hashed
   provider identity` PASSES.  **Never observed passing in a clean run** — first task for
   whoever picks this up is to run `*BankApiIntegrationTest*` alone at HEAD and decide
   stale-test vs regression.
3. **`ReceiptLifecycleCoordinatorTest` — 7 failures**, root-caused as mockk-RELAXED artifacts
   (tests never stub `createExpenseDbOnlyV2`, so a relaxed mock returns a generic-erased
   value → `ClassCastException`).  Test bug, not production — but it means that path has
   **no effective test coverage**.

### 5.4 NOT a bug — but worth knowing

The 24 `exact_synchronous` rows (§3.4) are all "guard present at the top, body unmodelable".
They are **low risk**.  Conversely, `checkWritesAllowed` placed *after* a mutation does not
cover it (the tri-state is position-aware) — a real ordering requirement for new code.

---

## 6. Test-infrastructure blockers (environment-level)

These block broad test runs and therefore the merge gate.  Pre-existing; A/B-proved.

| # | Issue | Effect | Fix |
|---|---|---|---|
| A1 | Byte-buddy agent self-attach fails → **mockk cannot mock final classes**; poisons the whole test JVM (`ExceptionInInitializerError`, then `OutOfMemoryError`) | 67 cascading failures in a trial run | Add `-Djdk.attach.allowAttachSelf=true` (+ `-XX:+EnableDynamicAgentLoading`) to test JVM args — **cheapest**; or fake-based doubles for ~20 files |
| A2 | `AndroidKeyStore` unavailable in the unit-test JVM | 2 `BankApiIntegrationTest` paths fail | Robolectric/instrumented, or inject the cipher |
| A3 | Gradle daemon native-OOM after many heavy runs | "daemon disappeared" | `./gradlew --stop`, retry |
| A4 | `WarrantyTrackerRepositoryTest` executor OOM/JPLIS crash | same family as A1 | same owner decision as A1 |
| B1 | `ReceiptLifecycleCoordinatorTest` 7 failures | listed in §5.3 | stub `createExpenseDbOnlyV2`; `null()` vs `isNull()` matcher |
| B2 | `SavingsGoalsViewModelTest` 3 failures | — | per handoff §B2 |
| B3 | `SplitCalculationPrecisionTest` | **see §5.3.1** | money-safe rounding |
| B4 | `verify_known_good_state` 11 failures | freshness "stamp=missing" → **GATE-00R debt**, not a code bug | goes green once the merge-time recapture runs |

**Measured scale:** `TEST_FAILURE_LEDGER.md` records **121 pre-existing `domain.*` + 53
`data.*` failures** plus a JVM instrumentation-agent crash that suppressed result XMLs.  The
`scenarios` package was never run.

---

## 7. Mandatory gates owed at merge

**GATE-00R double capture + Gradle DB task (MANDATORY).**  Policy sha has changed repeatedly
(`5d3b394d` → `c6b0463e` → `b4d2938c` → … → now `7851adc2`).  An attempt at GR-14u3 HEAD is
recorded but **UNTRUSTED (exit 2)**: gradle-db and gradle-task-graph exit 1 (documented
config-cache observation), static-suite and focused-python-tests exit 1 (the §6 pre-existing
reds flow in), room/db-inventory exit 2 `INVENTORY_DURABILITY_UNCONFIRMED`; compile and db-cli
exit 0; preservation/policy checks OK.  A trusted double capture becomes reachable once §6.B4
(freshness stamps) and the §6 reds are paid.

**Operational rule:** never edit the tree while a capture runs (it corrupted run 1).

**Also owed:** `GR-15 must NOT start until the GR-14 zero gate passes.**

---

## 8. How to reproduce

```bash
# Board (shadow-only, ~7 min/run; run twice for determinism)
python scripts/ci/inspect_db_mediation_proof.py --output build/guard-debug/gr14w0/board.json

# Engine battery + guard suites + fixtures
python -m pytest scripts/ci/test_gr14*.py scripts/ci/test_inspect_db_mediation_proof.py \
  scripts/ci/test_gr10b_source_scope_matrix.py -q
python -m pytest scripts/db_guard -q --ignore=scripts/db_guard/mediation_analysis/test_models.py
python scripts/db_guard/mediation_analysis/fixture_runner.py

# Census of the unproven buckets (needs GR14U5_SHADOW=<board.json>)
set GR14U5_SHADOW=build/guard-debug/gr14w0/board.json && python build/guard-debug/gr14u5/census.py

# Residual triage by deciding resolution / per-bucket detail
python build/guard-debug/gr14w0/triage_resolution.py
python build/guard-debug/gr14w0/triage.py

# Kotlin (targeted; the full suite is not a usable gate)
gradlew.bat :app:compileDebugKotlin --console=plain
```

Notes: `test_models.py` is excluded (pre-existing bare `import models`, runs only from its own
directory).  On Windows, redirecting `gradlew.bat` output can yield an **empty** log — verify
via artifact timestamps.  Do not run two Gradle commands concurrently.

---

## 9. Prioritized backlog

1. **§5.3.1 `SplitCalculationPrecisionTest` rounding** — the most likely *real production
   money bug* here.  Money rules apply; needs the sum-of-parts invariant.  Independent of the
   guardrail work.
2. **§4.D4 interface bucket (20 rows)** — concrete, bounded: build anonymous-`object`
   implementor tracking, then the exactness rule.  No production change needed.
3. **§5.3.2 `BankApiIntegrationTest`** — run it alone at HEAD; stale-test vs regression.
4. **§5.2 dead/owner-decision tail** — mechanical row movement, needs owner sign-off.
5. **GATE-00R** — the actual "done" gate; blocked by §6.
6. **§4.D1-II `init {}` invisibility** — measured latent (0 live instances); fixture-first
   engine change when taken.
7. **§4.D9 carrier admissions** — last, and only if a census shows one whose calls already
   resolve exactly.

**Do not start with** the async/carrier bucket despite its size (133 rows) — measured at ~0
rows per admission.

---

## 10. Invariants to preserve

- **Fail closed.** Never up-rank to proven on uncertainty. Unproven ≠ bug; only
  `counterexample_*` asserts a violation.
- **Never land through a Step-0 flip.** Triage first.
- **Contract changes require a version bump** (V2 → V3) and a reviewed diff.
- **Closed sets** (inline carriers, structured-launch receivers, worker guards) change only
  with fixture coverage + shadow delta.
- **Pins must be RED before the fix** — otherwise they are not load-bearing.
- **Position matters.** A guard after a mutation does not cover it.
- **Land latent fixes as their own batch**, with pins, and state that they move 0 rows.
- **Do not weaken a test, tolerance or guard to make a build pass.**
- **Only one agent may run Gradle at a time.**

---

## Appendix A — guardrail-relevant quick reference

| Concept | Where |
|---|---|
| Production barrier | `data/backup/DatabaseWriteBarrier.kt` |
| Canonical contract V2 | `scripts/db_guard/structural_analysis/barrier_proof.py:199` |
| Inline carrier set | `scripts/db_guard/mediation_analysis/callgraph.py:172` |
| Structured launch receivers | `scripts/ci/inspect_db_mediation_proof.py:110` |
| Tier-5 proof + states | `scripts/db_guard/mediation_analysis/proof.py` |
| Tri-state local proof | `proof.py:237` (`_local_guard_unmodelable`), proof.py:673 |
| Direct-site prover | `scripts/ci/inspect_db_mediation_proof.py:158` |
| Board generator | `scripts/ci/inspect_db_mediation_proof.py` |
| Batch manifests | `docs/ci/db-mediation/GR-*.yml` |
| Per-defect narrative | `docs/ci/DEFERRED_ISSUES_AND_DEBUG_HANDOFF.md` |
| Test red inventory | `TEST_FAILURE_LEDGER.md`, `docs/testing/generated/TEST_FAILURE_TRACKER.md` |
| Board artifacts + probes | `build/guard-debug/gr14u*/`, `build/guard-debug/gr14w0/` |
