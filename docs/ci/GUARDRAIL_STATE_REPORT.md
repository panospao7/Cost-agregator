# GUARDRAIL STATE REPORT — database write-barrier proof engine

**Status date:** 2026-09-11
**Branch:** `gr-14f-wip` @ `20fb4278` (GR-14u22…u25 + money fix committed)
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

**Contract changes require a version bump and a dedicated reviewed diff.**

- **V3 (current, GR-14u25)** supersedes V2 to add a **third** canonical scope form:
  `restore_scope_receiver_fqcn` = `...data.backup.RestoreInternalWriteScope` and
  `restore_scope_methods` = `("run",)`.  This form is **receiver-exact** (like the worker
  guard), mode-gated to restore windows, and deliberately overlaps the transparent-inline
  method name `run` — which is safe precisely because admission is receiver-exact.  Its
  proof state is `PROVEN_RESTORE_INTERNAL`.  V1 and V2 objects are left untouched.
- V2 = V1 plus the transparent-scope wrappers above.  Both are still exported and pinned so
  the older contracts cannot silently drift.

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
| `proven_restore_internal` | Inside the receiver-exact, mode-gated `RestoreInternalWriteScope` (V3); the write is sanctioned *because* a canonical barrier throws during restore by design. | ✅ |
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
unguarded call path.  **This is why 32 current rows read `unproven` — see §3.4.**

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

## 2. Measured state (2026-09-11)

### 2.1 Board

Report sha256 **`35de3592eaedbc2e90d7da33ca9bcc0ffdef8b8863805f14b03dd0e7b5933106`**
(deterministic across a double run).  406 policy rows.

| Bucket | Count |
|---|---|
| `proven_helper` | **173** |
| `proven_restore_internal` | **1** |
| `proven_worker_mediated` | 14 |
| `unproven_ambiguous_call` | 58 |
| `unproven_async_or_escaping_callback` | 133 |
| `unproven_external_entry` | 27 |
| **`counterexample_unguarded_call_path`** | **0** |
| **proven / unproven** | **188 / 218** |

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
| GR-14u22 (2026-09-11) | DI-wrapper receiver unwrap | helper 155 → **164** |
| GR-14u23 (2026-09-11) | anonymous-implementor counting (latent, 0 rows) | helper 164 |
| GR-14u24 (2026-09-11) | single-implementor interface exactness | 9 rows → helper (vs u22/u23) |
| GR-14u25 (2026-09-11) | **contract V3**: restore-internal scope | restore row → **proven_restore_internal** |

Net through GR-14u21: **proven_helper 69 → 155** (proven 83 → 169), counterexamples **0
throughout** — the live board never carried one.  (The "18 counterexamples" seen during
GR-14u19 were a *projection* of the unlanded change, which is what triggered the D7
investigation.)

GR-14u22 → u25 (the last four batches) move the board from `fe40d369` to `35de3592` by
**exactly 19 rows**: 18 `unproven_ambiguous_call → proven_helper` and 1
`unproven_ambiguous_call → proven_restore_internal`, with 0 counterexamples and 0
proven→unproven.  Two of those four batches (u23, and u25 on its own) are **latent** —
verified byte-identical with their activator neutralised.

Campaign-wide, GR-14u5 → u25: proven_helper **48 → 173**.

The u24 Step-0 HARD STOP is **resolved**: the single counterexample became a proof rather
than being suppressed.  See §4.D4 and §5.1.1.

### 2.2 Validation status

| Check | Result |
|---|---|
| engine battery (`scripts/ci/`) | **1220 passed / 13 failed / 14 skipped** |
| ↳ of the 13 | **all PRE-EXISTING and A/B-proven** — stashing this session's work and re-running the same files at HEAD yields the identical 13 (§6.B4) |
| `scripts/db_guard` unit tests | **235 passed** |
| fixture scenarios | **61 / 61** (consolidated rows 69) |
| board determinism (double run) | byte-identical (`35de3592`) |
| Kotlin `:app:compileDebugKotlin` | PASS (last run GR-14u20) |

**The full Kotlin test suite is NOT a usable gate** — see §6.  Prefer targeted
`:app:compileDebugKotlin` and `--tests "*Class*"` filters.

### 2.3 Batch history

41 manifests through GR-14u25 (`docs/ci/db-mediation/`:
GR-12, GR-13 ×2, GR-14a–t, GR-14u ×1 + u2–u25).
Each newer manifest carries its delta, evidence and validation status.

---

## 3. Board anatomy — why each unproven row is unproven

Post-GR-14u20 census (`build/guard-debug/gr14w0/census_u20.log`, exact reconstruction
fidelity: report status reproduced for **every** row).  Buckets sum: 133 + 27 + 77 = 237.
GR-14u22 (§3.3.1) then proved 9 of those 77 ambiguous rows, leaving 229 unproven on the
live board `6a18b4bf`.

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

### 3.3 `unproven_ambiguous_call` — 58 rows (post-GR-14u25)

| Deciding resolution | Rows (live board) | What it means |
|---|---|---|
| `exact_synchronous` | **32** | Guard present, body **unmodelable** — see §3.4 |
| `function_reference` | 14 | `::method` callbacks — triaged §3.3.1; next natural batch (§9 item 2b) |
| `unresolved_target` | 12 | Receiver or target not resolved — triaged §3.3.1 |
| ~`interface_dispatch` | **0** | **DRAINED** by GR-14u23/u24/u25 (§4.D4) — was 20 |

**Where the 20 `interface_dispatch` rows actually went** (measured per row, not inferred —
`build/guard-debug/gr14u25/transition_check.py`):

| Outcome | Rows |
|---|---|
| `interface_dispatch → proven_helper` | **9** |
| `interface_dispatch → proven_restore_internal` | **1** |
| `interface_dispatch → exact_synchronous` (still ambiguous) | **8** |
| `interface_dispatch → unresolved_target` (still ambiguous) | **2** |

Only **10 of the 20 proved.** The other 10 stayed unproven because making the interface edge
exact merely **promoted the next uncertain edge** — and for all 8 that landed on
`exact_synchronous` the newly-deciding edge is `GR13_LOCAL_GUARD_UNMODELABLE`, i.e. those
rows were *always* unmodelable and the interface edge had been masking it. That is the same
"the taint is layered" effect documented in §4.D6/D7, and it is why this bucket's 20 rows
were never going to yield 20 proofs.

#### 3.3.1 The 19 `unresolved_target` + 14 `function_reference` rows — triaged 2026-09-10

Every deciding edge was recovered with the engine's own tier-5 selection
(`build/guard-debug/gr14w0/probe_deciding_edges_u21.log`, board `fe40d369`).  Results:

- **11 rows are guarded in their own body and stuck on engine limitations only:**
  - 9 × `RecurringRuleLifecycleCoordinator` (activateRule / advanceNextDate /
    deactivateRule) — every caller goes through
    `ruleLifecycleCoordinator.get().method(...)`, a **`dagger.Lazy` chained receiver**
    the engine cannot unwrap (`ManualRecurringExpenseRepository.kt:33/36/40`).  A
    transparent-unwrap rule for `dagger.Lazy.get()` (closed-set change, Step-0 gated)
    would move all 9.  **→ DONE as GR-14u22 (2026-09-11): all 9 proved `proven_helper`
    (board `6a18b4bf`), projection clean, 0 counterexamples, manifest
    `docs/ci/db-mediation/GR-14u22.yml`.**
  - 2 × `NotificationRepository.save` / `RecommendationRepository.save` — blocked by a
    **same-name uncertain edge** `debugDataStorage.save(data)`
    (`ReviewViewModel.kt:1020`, an unrelated `DebugDataStorage`).  Name-collision noise;
    both real methods are guard-first.
- **14 `function_reference` rows are all guarded in-body.**  The deciding edges are
  `viewModel::method` callbacks in composables (`ReviewScreen.kt:542` alone blocks all 7
  `ReviewQueueRepository.approveReview` rows).  Resolving `expr::name` alone would only
  re-label these rows `interface_dispatch` — the probe shows those interface edges
  already waiting behind the references — so real movement requires the §4.D4 chain
  (anonymous-object tracking + exactness rule) as well.
- **8 rows are genuine unguarded-at-site writers** (no canonical barrier in the file):
  `RestoreJournalImporter` ×4, `OperationRunRecorder.increment` ×1,
  `CsvExpenseImporter.getOrCreateCategory` / `JsonExpenseImporter.parseV1Row/parseV2Row`
  ×3.  Mitigations exist but are non-canonical: the restore-journal importer runs only
  behind `restoreMaintenanceMode.isWritesAllowed()` in `AppStartupCoordinator.initialize`,
  the CSV/JSON importers are reachable only from the debug import UI, and the
  OperationRun handle is worker-gated upstream.  Same hardening class as the GR-14u18
  `migrateCategories` fix (which moved 0 rows alone — the inbound edges stay uncertain
  regardless).  Owner decision whether to add canonical guards.

### 3.4 The 32 `exact_synchronous` rows — CORRECTLY unproven, not a defect

**Investigated this session; hypothesis falsified.**  These rows read
`unproven_ambiguous_call` *with* an exact deciding edge, which looks contradictory.  It is
not.  All 32 are the **GR-14u15 tri-state** working as designed
(`GR13_LOCAL_GUARD_UNMODELABLE`, `barrierMode=helper`, `localGuard=none`):

- **32 / 32 contain a canonical `checkWritesAllowed` call** — the guard *is* present.
- **24 / 32 contain `try` / `catch`**, which the GR-12 body model refuses (exception flow
  above all).
- The other **8** (4 distinct methods — `ExpenseRepository.updateExpenseCategoryBulk`,
  `GroupTransactionCoordinator.deleteGroupAtomic`,
  `SubscriptionManagerEngine.acceptCandidate` / `validateAndCreate`) place the guard first
  and mutate inside `withLock` / `database.withTransaction` lambdas — the CFG does not wire
  scope children, so the mutation node is disconnected and the body is unmodelable.
  Measured: 16 of the 32 carry `withContext`, 1 carries `withLock`.
  (`deleteGroupAtomic` is one of the 8 that arrived from the `interface_dispatch` bucket —
  see §3.3: the interface edge was masking an already-unmodelable body.)

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
so the engine correctly reports *unproven* rather than inventing a violation.  **These 32
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
| D4 | `interface_dispatch` residue (20 rows) | proof only | ✅ **RESOLVED** u23/u24/u25 |
| D10 | `_inherits_from` followed only the first supertype | **latent fail-OPEN** | ✅ **FIXED** GR-14u21 |
| D13 | restore-internal scope inexpressible in the contract | proof only | ✅ **FIXED** GR-14u25 (V3) |
| D14 | `restore_internal` collapsed to `worker` in context propagation | proof only | ✅ **FIXED** GR-14u25 |
| D15 | scan coverage excludes the `debug`/`release` source sets | — | ✅ **NOT A DEFECT** — deliberate, enforced 3 ways, already tested (§5.5). Do not widen. |

### 4.D4 — `interface_dispatch` — RESOLVED ACROSS u23 / u24 / u25 (LANDED)

**GR-14u23 (landed, latent, 0 rows):** anonymous `object : T { }` implementor sites are
now counted per corpus type (`_scan_anonymous_implementors`).  The owner table holds
zero anonymous entries, and the corpus carries such sites for 10 interfaces
(PrivacyGate ×12, RetentionTarget ×10, WorkerLeaseRegistry ×1, …) — the exact
false-unique shape that made a naive exactness rule unsound.

**GR-14u24 (landed):** the single-implementor exactness rule (complete named walk +
transitive anonymous counts + overload guard) resolves a single-implementor
interface dispatch as an exact edge.  Its projection moved **9** rows to `proven_helper`
and surfaced **1 counterexample** — `DatabaseBackupRepositoryImpl.restoreReceiptAssets`
— which is why it was HELD at the Step-0 gate instead of landed.
*(The u24 manifest's "18 rows" figure counts against `fe40d369`, which is the pre-u22 board;
9 of those 18 belong to GR-14u22. Against u24's actual predecessor the delta is 9. See
"Attribution" below.)*

**GR-14u25 (landed, contract V3) — the resolution.**  The counterexample was triaged as
**not a production defect** (§5.1.1): the write targets the *restore* database (`freshDb`)
inside the formal mode-gated `RestoreInternalWriteScope`
(`require(mode ∈ {ASSETS_RESTORING, RESTORE_VERIFYING})`), which V2 could not express.
Rather than suppress the finding, V3 makes the second guard form expressible, so the
counterexample becomes an explicit **`proven_restore_internal`** proof.  With that, the
flip no longer exists and u24 lands under the never-land-through-a-flip invariant.

**Landed effect (vs `fe40d369`):** exactly 19 rows — 18 `unproven_ambiguous_call →
proven_helper` plus 1 `unproven_ambiguous_call → proven_restore_internal`; 0 counterexamples,
0 proven→unproven.  Verified: `build/guard-debug/gr14u25/verify_baselines.py` (VERDICT A PASS).

**Attribution — read this before quoting any per-batch number.**  The 19 rows of the landed
effect are **not** all u24/u25's.  Measured per batch (helper counts, and the per-row
transition in `build/guard-debug/gr14u25/transition_check.py`):

| Batch | Rows | Of which |
|---|---|---|
| GR-14u22 | 9 | `unresolved_target` (the `dagger.Lazy.get()` chain) |
| GR-14u24 | 9 | `interface_dispatch → proven_helper` |
| GR-14u25 | 1 | `interface_dispatch → proven_restore_internal` |
| **total** | **19** | |

And **10 of the 20 `interface_dispatch` rows did NOT prove** — 8 moved to
`exact_synchronous` and 2 to `unresolved_target`, all still unproven (§3.3).  So this bucket
was never worth 20 proofs; the honest yield is 10.  Two separate conflations produced
inflated figures earlier in the campaign (both now corrected here and in the manifests):
`fe40d369` vs `6a18b4bf` are different baselines, and u24's projection board already
contained u22's effect.

**Latency, measured both ways.**  u25 *alone*, with the u24 rule neutralised, is
byte-identical to `6a18b4bf` (0 changed rows) — u25 is inert until u24 makes the production
edge exact.  And u24's effect vs the pre-u24 board is 10 rows (9 helper + 1 restore), the
other 9 of the "19" belonging to u22.  Both baselines are recorded because conflating them
was itself a source of confusion in the handoff (`verify_baselines.py`, VERDICT B PASS).

Full triage and options: `docs/ci/db-mediation/GR-14u24.yml`, `GR-14u25.yml`.

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

- ✅ **Re-measured 2026-09-10** against the current board (`fe40d369`, 27 rows → 22
  distinct zero-inbound methods, 1073 production files):
  `build/guard-debug/gr14w0/census_init_u21.py` found **0** init-block calls into
  zero-inbound methods.  The no-live-fail-open-instance conclusion holds at the current
  state.
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

### 5.1.1 GR-14u24 flag, RESOLVED as NOT a defect — the restore-internal writer

The exactness-rule projection flagged `DatabaseBackupRepositoryImpl.restoreReceiptAssets`
(`scannedReceiptDao.update` on the **fresh restore database**) as a counterexample.
Triage: the write sits inside the formal mode-gated `RestoreInternalWriteScope`
(`require(mode ∈ {ASSETS_RESTORING, RESTORE_VERIFYING})`), targets `freshDb` — never the
live DB — and the method is only reachable inside the restore flow.  A canonical barrier
**cannot** be added (it throws in restore modes by design).  This is a *second, sanctioned
guard form* the V2 contract could not express.

**RESOLVED 2026-09-11 by contract V3 (GR-14u25):** the owner chose option A, so the form is
now *modelled* rather than excused — the row lands as **`proven_restore_internal`**, not as
a counterexample and not as a suppressed finding.  No policy exception, no per-row
disposition.  See §4.D4 and `GR-14u25.yml`.

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
   **→ FIXED 2026-09-11** (commit `53b825a6`): `EnhancedSplitManager` now allocates
   percentage splits by largest remainder in integer cents (mirroring
   `SplitCalculator.calculateAmountsFromPercentages`), in both `calculatePercentageSplit`
   and the `generateVisualSplitData` PERCENTAGE branch; the test mirror and two exact-sum
   assertions were tightened, not weakened.  Targeted run: `*SplitCalculationPrecisionTest*`
   **23 / 23 PASSED** (RED baseline before the fix: 1 failed, exit 1).  Strict review
   verdict: PASS (money-math rules checked; termination guard added for the
   negative-remainder branch on inputs outside the validated non-negative domain).
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

The 32 `exact_synchronous` rows (§3.4) are all "guard present at the top, body unmodelable".
They are **low risk**.  Conversely, `checkWritesAllowed` placed *after* a mutation does not
cover it (the tri-state is position-aware) — a real ordering requirement for new code.

### 5.5 Production audit of UNSCANNED source sets (2026-09-11) — NOT a blind spot

Run because the guardrails only ever see what the scanner reads, and a writer outside that
set would be invisible no matter how the engine behaves.  Method: enumerate the Gradle source
sets, compare against `config/guards/production_source_roots.yml` (which lists exactly one
root — `app/src/main/java`), and inspect everything in the difference.

**Two hypotheses tested, BOTH FALSIFIED.**  Recording the negative results so neither is
re-opened without new evidence.

**1. "The `migrateCategories` pattern has a source-set sibling."**  The only files outside
the scanned root are one `DebugDataStorage.kt` in `app/src/debug/` and its twin in
`app/src/release/`.  It does **not** write the database — it writes a **file**
(`File(context.filesDir, "last_debug_data.json")`), and `DatabaseWriteBarrier` governs
database writes, not file writes.  Verified: neither file references any DB-layer marker
(`Dao`, `AppDatabase`, `RoomDatabase`, `@Dao`/`@Query`/`@Entity`, `DatabaseWriteBarrier`,
`checkWritesAllowed`, `runWrite`, `withTransaction`, `RestoreMaintenanceMode`).  The release
variant is a compiled no-op stub.  **Not an unguarded DB write.**

**2. "Scan coverage of `debug`/`release` is an accidental blind spot worth closing."**
It is not accidental and it must **not** be widened.  The main-only scope is a deliberate
boundary, enforced independently in three places — this is now a *confirmed* design property,
not a gap:

| Enforcement | Evidence |
|---|---|
| Manifest schema rejects it | `sourceSet` must be exactly `main`; the path must end `/src/main/java` or `/src/main/kotlin`, and `test`/`debug`/`release`/`androidTest`/`generated`/`build` segments are **explicitly rejected** (`production_source_scope.py:494-499`). A `debug` root returns `DB_SOURCE_ROOT_MANIFEST_INVALID` with reason `unsupported-tail`. |
| Policy paths cannot reach it | The legacy approved-roots contract is `("app/src/main/java",)`; an `app/src/debug/...` or `app/src/release/...` policy path is rejected `POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT` (`db_guard/source_roots.py`). So a variant file could not be granted a policy row even if it were scanned. |
| A test already pins it | `scripts/test_db_guard_room_inventory.py`: `test_production_root_includes_only_production_dao` writes a `@Dao DebugDao` and `@Dao ReleaseDao` into those roots and asserts they are **never inventoried**; a sibling test asserts no inventoried path starts with `app/src/{test,androidTest,debug,release}/`. |

So the residual risk is real but already **designed for and mechanically tested**: the
mitigation is the existing boundary, and the standing rule is *do not put database access in
a variant source set*.  No config change is warranted.  (Also worth knowing: every
Kotlin architecture guard under `app/src/test/.../architecture/` likewise scans
`src/main/java` only — the guarded production surface is main-only by design.)

**Separate, still-open item — the debug variant persists financial payload unencrypted (low
severity, debug-only).**  `DebugDataStorage.save` (debug only) writes JSON containing parsed
transaction rows (amount, currency, merchant, date), the parsing logs, and a **200-character
preview** of the raw statement text (`DebugData.toJson` emits `rawText.take(200)`), to
app-private storage in cleartext.  Mitigations that make this low severity: the release
variant is a no-op, the location is app-private (`filesDir`), and only a preview rather than
the full statement is persisted.  What still deserves an explicit decision: AGENTS.md forbids
persisting raw statement text and user financial payloads at all, the file survives until
`clear()` is called explicitly, and nothing encrypts it even though `BackupEncryptionService`
exists for the backup path.  This is a **privacy-posture question, not a guardrail gap** —
it is not a new bug and not user-reachable in release.

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
| B3 | `SplitCalculationPrecisionTest` | **FIXED 2026-09-11 — see §5.3.1** | largest-remainder allocation |
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

1. ~~**§5.3.1 `SplitCalculationPrecisionTest` rounding**~~ — **FIXED 2026-09-11** (§5.3.1;
   largest-remainder allocation, 23/23 targeted green, strict review PASS).
2. ~~**§4.D4 interface bucket (20 rows)**~~ — **DONE 2026-09-11** across GR-14u23
   (anonymous-implementor counting, landed latent), GR-14u24 (single-implementor exactness
   rule) and GR-14u25 (**contract V3**, which turned the batch's one counterexample into a
   `proven_restore_internal` proof instead of suppressing it).  Landed effect vs `fe40d369`:
   18 rows → `proven_helper` + 1 → `proven_restore_internal`, 0 counterexamples.
   See §4.D4, §5.1.1, `GR-14u24.yml`, `GR-14u25.yml`.

2b. **NEW — `function_reference` (14 rows)** — now the largest *resolution* bucket and
   unblocked by the D4 chain: `expr::name` callables are still emitted as one uncertain
   `FUNCTION_REFERENCE` edge with name-matched targets (callgraph.py `_resolve_call`).  A
   natural next batch (GR-14u26): resolve the receiver expression of `::` and bind the
   reference to the single corpus member, keeping ambiguity fail-closed.  Pins first.
3. ~~**§3.3.1 `dagger.Lazy.get()` transparent unwrap**~~ — **DONE as GR-14u22
   (2026-09-11)**: 9 rows ambiguous → proven_helper, board `6a18b4bf`, 0 counterexamples.
4. **§5.3.2 `BankApiIntegrationTest`** — run it alone at HEAD; stale-test vs regression.
4b. **DO NOT "close" the main-only scan scope (§5.5).**  Audited 2026-09-11 and confirmed a
   deliberate, triply-enforced, already-tested design boundary — not a blind spot.  Widening
   `production_source_roots.yml` is impossible under the schema (`sourceSet` must be `main`)
   and would defeat the boundary that `test_production_root_includes_only_production_dao`
   exists to pin.  The only residual item here is a **privacy decision** on the debug-only
   plaintext `DebugDataStorage` payload (§5.5), which is a posture question, not a guardrail
   gap.
5. **§5.2 dead/owner-decision tail** — mechanical row movement, needs owner sign-off.
6. **GATE-00R** — the actual "done" gate; blocked by §6.
7. **§4.D1-II `init {}` invisibility** — measured latent (0 live instances, re-confirmed
   2026-09-10 §4.D1-II); fixture-first engine change when taken.
8. **§4.D9 carrier admissions** — last, and only if a census shows one whose calls already
   resolve exactly.

**Do not start with** the async/carrier bucket despite its size (133 rows) — measured at ~0
rows per admission.

---

## 10. Invariants to preserve

- **The guarded production surface is `app/src/main` ONLY — by design.**  Variant source sets
  (`debug`/`release`) and test roots (`test`/`androidTest`) are deliberately outside the scan
  scope, enforced by the manifest schema, the approved-roots contract, and
  `test_production_root_includes_only_production_dao`.  Do not widen it; put database access
  in `main`, never in a variant (§5.5).
- **A negative result is a deliverable.**  Record what was tested and falsified, with the
  evidence, so it is not re-investigated — and so a later reader can tell a designed boundary
  from an oversight.
- **Fail closed.** Never up-rank to proven on uncertainty. Unproven ≠ bug; only
  `counterexample_*` asserts a violation.
- **Never land through a Step-0 flip.** Triage first.
- **Contract changes require a version bump** (V1 → V2 → V3, GR-14u25) and a reviewed diff;
  older versions stay exported and pinned.
- **A sanctioned guard form must be MODELLED, not excused.**  GR-14u25 is the precedent: the
  restore-internal writer was neither forced through the global barrier nor given a policy
  exception — the contract learned the form, so the row became a *proof*.  Prefer this over
  a per-row disposition whenever a whole class of writers shares the shape.
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
| Canonical contract **V3** (current) | `scripts/db_guard/structural_analysis/barrier_proof.py` (V2 at :229, V3 at :266) |
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
