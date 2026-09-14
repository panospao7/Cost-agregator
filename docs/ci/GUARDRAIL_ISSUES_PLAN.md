# GUARDRAIL ISSUES → EXECUTION PLAN — what to do, in what order, and why

**Status date:** 2026-09-11
**Inputs:** `GUARDRAIL_ISSUES_LEDGER.md` @ `d2af4f94` (board `a9976208` — proven **301** /
unproven **105**, counterexamples **0**) · `GUARDRAIL_STATE_REPORT.md` §6/§7/§9 ·
`DEFERRED_ISSUES_AND_DEBUG_HANDOFF.md` §E · `docs/guardrails/guardrail_next_pr_roadmap.md`.
**Branch:** `gr-14f-wip` (this worktree).  **Status of this document:** `PLAN_OR_BACKLOG` —
it adds sequencing and batch structure over the ledger; it changes no claim made there.
Where this plan and the ledger disagree, **the ledger wins** and this plan must be corrected.

**The two questions this plan answers:**

1. **Guardrail-wise** — how the engine, the board, and the gates get from the current
   105-unproven / GATE-00R-blocked state to a trusted, mergeable, zero-gate state, and then
   on to GR-15 activation.
2. **Codebase-wise** — which production and test-infrastructure decisions are owed, who owes
   them (owner vs engine), and in what order they unblock the guardrail work rather than
   race it.

---

## 0. Where we actually are (measured, do not re-derive)

| Quantity | Value | Source |
|---|---|---|
| Board | proven 301 / unproven 105 / counterexamples 0, sha `a9976208…` | ledger §G |
| Unproven decomposition | 24 zero-inbound · 24 try/catch-unmodelable · 22 async `localGuard=none` · 10 D19 · 5 CFG-wiring · 20 class-A (2 unexplained) | ledger §0 |
| Python battery | `scripts/ci` 1244 P / 13 F / 14 S — the 13 are pre-existing (1 freshness, 11 known-good-state, 1 production-source-roots) | ledger §D |
| Engine battery | `scripts/db_guard` 235 passed · fixtures 64/64 | ledger §D |
| Kotlin | full suite NOT a usable gate; targeted compile green | state report §8 |
| GATE-00R | **BLOCKED** — freshness stamps + Python reds + inventory durability | ledger §D2 |
| GR-15 | step 2 exists on `gr-00-local` (`d48409c0`) but is **NOT in this lineage**; entry gate (GR-14 zero gate) not passed | state report §7 |

**Already settled (do not reopen):** the retractions ledger §E (E1–E7), the carrier-admission
dead end (§B5), the main-only scan scope (§E3), and every FIXED row in §F.  The plan below
only schedules **open** work.

---

## 1. Per-batch protocol (applies to every engine batch in §2)

Every batch in this plan is executed the way the successful GR-14u batches were:

1. **Step-0 projection** against the then-current board *before* any edit lands.
2. **Hard stop** on any counterexample flip or any proven→unproven regression — triage what
   surfaced before proceeding (this is how all 5 real unguarded writes were found, §F).
3. **Deterministic double run** of the board; both runs byte-identical, sha recorded in the
   batch note (`docs/ci/db-mediation/GR-14<batch>.yml`).
4. **Batteries green** after the change: `scripts/ci` (minus the 13 documented pre-existing
   reds), `scripts/db_guard`, fixture corpus.
5. **Batch cap** per PR-GR-14: one feature per diff; no bundled relaxations.
6. Board determinism difference ⇒ not committable (ledger §G).

---

## 2. TRACK A — Engine batches to the GR-14 zero gate (the 105 rows)

Ordered by expected row yield per unit of risk, using the ledger's own measurements.  All
row counts are *expected movements*; the Step-0 projection is the authority on what a batch
actually buys before it lands.

### A1. `labelled-return` support — ledger §B1 (≈14 findings; part of the 24 try/catch rows)

The dominant refusal is the campaign's own guard idiom: `return Result.failure(e)` inside
`catch`.  Model labelled/early returns in the body CFG so the barrier-precedes-mutation
dominance can be computed through the catch path.
**Closes:** the largest slice of the 24 unmodelable rows.
**Do NOT:** land exception-flow modelling "while we're in there" — it is a separate feature
(A2) with its own projection.

### A2. `dangling-clause` / exception-flow modelling — ledger §B1 (14 findings)

**MEASURED MOOT (GR-14u30, 2026-09-11): zero dangling-clause findings remain on the
subject rows.**  The 14 u28 findings were derivative artifacts of the A1 misfire — when the
whole `return try …` statement was refused early, its trailing `catch` part appeared
unclaimed and produced a second finding (the pre-fix fixture shows both reasons on one
statement).  A1's fix cleared both.  The corpus-wide sweep found 31 dangling-clause
findings in 26 **non-subject** callables (edge-fidelity candidates, outside A2's defined
scope) — recorded in `GR-14u30.yml` as candidate engine work "GR-14-dc", requiring its own
Step-0 before any parser change.

### A3. `unknown-construct` (5 findings) → A4. `coroutine-builder` (4) → A5. `elvis-block` (1)

**A3 DONE as GR-14u31 (2026-09-11, board `8b634e35…`):** the root cause was branch
precedence, not a missing feature — the barrier branches (`.search()` on the whole
statement text) captured transparent-scope statements whose lambda bodies contain barrier
text and refused them before the transparent-scope branch was reached.  The fix hoists the
transparent-scope admission above the barrier branches.  17 rows proved (incl. 4 of the 5
ledger-§B3 CFG-wiring rows and 7 zero-inbound rows via the u27 self-guarded rule);
0 regressions, 0 counterexamples.  Residual subject refusals: `processBankStatement`
(D19/lambda-escape family — A6), `ensureDefaultCategories` (coroutine-builder — A4),
`migrateCategories` (elvis-block + lambda-escape — A5), `updateExpenseCategoryBulk`
(the §B3 declined `withLock` row).  These fold into A4–A6 below.

Remaining §B1 refusal families in descending yield.  Each is its own fixture-first batch; the
elvis batch is last and may be bundled with A6 **only if** its projection shows zero
interaction with anonymous-object parsing (otherwise it stays separate).

### A6. D19 anonymous objects — ALL-OR-NOTHING — ledger §B2 (10 rows)

Parser half (stop reading `object : T {}` as a supertype call) **and** member-modelling half
(callable discovery descends into function bodies; anonymous-member owner FQCN decided so
members inherit inbound edges from the construction site) **in the same diff**.  The measured
half-fix moves 10 rows `async → external_entry`, proves nothing, and makes live
privacy-cleanup code (`RetentionModule.provideRetentionTargets`) look deletable.
**Closes:** 10 D19 rows + removes the 16 phantom async regions.
**Do NOT:** land the parser half alone; do not treat `provideRetentionTargets` as dead.

### A7. The 22 async rows with `localGuard == "none"` — per-row triage, not one batch

After A1–A6 the async bucket should contain only these.  Triage each row into exactly one
disposition (GR-08q rule set):

| Disposition | When | Producer |
|---|---|---|
| **canonical guard in the writer** | the write is a real production write reached async | production change — separate small batches, batch cap applies |
| **modelled sanctioned form** | restore/backup/export/maintenance/privacy-cleanup paths that MUST run ungated | GR-14u25 `restore_internal` precedent — model, never excuse |
| **exact policy row** | metadata genuinely encodes a reviewed authorization the engine cannot see | one row, reviewed, linked |
| **carrier modelling** | only if a census shows the carrier's calls already resolve exactly | §B5 negative result stands until then |

**Do NOT:** start with carrier admissions (§B5); add guards to privacy-cleanup or
restore/export paths (§A5, AGENTS.md).

### A8. The 5 CFG scope-wiring rows — ledger §B3 (decision owed, not work owed)

Both measured options buy +1 row each at disproportionate cost (fail-open import relaxation
or a contract bump).  **Decision:** (a) bundle the `withLock` wrapper with the next contract
change if one happens anyway, or (b) carry them as the only explicitly-owner-approved
residual if the zero gate accepts documented dispositions.  Do NOT remove the anti-shadowing
import guard.  Resolve this decision **before** declaring the zero gate, since it determines
whether 0-unproven is reachable without a contract change.

### A9. Zero-inbound owner decisions — ledger §C1/C2 (24 + 2 rows) — runs IN PARALLEL with A1–A6

These are not engine work.  Per-writer protocol, mechanical, owner sign-off per row:

1. hand call-site search from source (never the board label — §A4);
2. classify: *genuinely dead* → delete the writer (removes the row) · *init{}-reached* →
   active-but-unprovable, keep and disposition (§B4 latent rule) · *live and guardable* →
   exact policy row or guard;
3. record one line per writer in the batch note.

Named cases already queued (handoff §E.3 + ledger §C1/C2/C4): `ExpenseWriteStore`
(designed-but-unwired), the `GroupLifecycleCoordinator` quartet + `SettlementCalculator.
recordSettlement` (routing never built — see §4 C4), `InvestmentTracker.addHolding`,
`NotificationRepository.deleteAllNotifications`, `BankApiIntegration.completeConnection`,
`SubscriptionManagerEngine.recordPriceChange`, `AiArtifactRepositoryImpl.deleteByTargetKey`,
`BudgetForecastingEngine.updateForecastAccuracy`,
`TransactionLifecycleCoordinator.deleteExpense`, and the two unexplained rows
(`ExpenseGroupDao.insertGroupWithMembers`, `RecurringLifecycleEventWriter.writeDiagnostic`).
**Do NOT:** bulk-delete from the board.

### A10. Zero-gate verification

When the board shows 0 unproven (or the A8-approved residual exactly), run: double board,
full batteries, and record the board sha as the GR-14 zero-gate evidence.  **GR-15 must not
start before this exists** (state report §7).

---

## 3. TRACK B — GATE-00R unblock and the merge gate (infra-wise)

The ledger §D2 dependency order, expanded into steps.  B1→B5 are sequential; B6 is a
scheduling decision that should be made early even though it executes late.

### B1. Run the Gradle DB task to produce freshness stamps

The 11 `verify_known_good_state` reds + 1 `test_result_freshness` red are "stamp=missing",
not code bugs — they clear once the Gradle DB task runs and stamps.  Obey the one-Gradle-
command-at-a-time rule and the empty-log-on-Windows caveat (verify via artifact timestamps).

### B2. Pay the one real Python red: `test_verify_production_source_roots`

Investigate root cause; fix the guard, the fixture, or the manifest — whichever the evidence
supports.  This is the only non-stamp red blocking `focused-python-tests` from exiting 0.
If it turns out to be an intentional boundary (like §E3), convert it into an explicit
disposition record instead of leaving it red.

### B3. Resolve `room/db-inventory` exit 2 `INVENTORY_DURABILITY_UNCONFIRMED`

Define what "inventory durability" means for the capture (regenerate vs verify), execute it,
and get the component to a definitive exit.  Fail-closed rules apply: a missing artifact must
fail, not warn.

### B4. Settle the config-cache protocol question

The capture records `gradle-db` / `gradle-task-graph` exit 1 as "the protocol's documented
config-cache observation".  Decide once, in writing: either the protocol accepts this
documented exit (then the capture's trust rules say so), or the custom Gradle tasks need the
R3-style refactor (remove `Project` captures from task actions — recovery directive Phase 2).
Do not leave it ambiguous into FIN-00, where it would resurface as an unexplained red.

### B5. GATE-00R double capture at the final SHA

Two byte-identical captures, no tree edits while a capture runs (a mid-capture edit already
corrupted run 1 once).  This produces the merge-gate evidence the whole campaign owes.

### B6. Branch reconciliation — decide early, execute at merge

The guardrail campaign lives on `gr-14f-wip`; `atomicity-pr21-enforcement-final` (main
worktree) carries separate uncommitted work (email/notification parser changes + RP doc sync
+ agent config).  Any production change landing there **moves the board and the policy SHAs**
and invalidates GATE-00R evidence for this lineage.  Decide the merge direction and freeze
the loser's production edits before B5, or B5 will have to run twice.

---

## 4. TRACK C — Codebase decisions (production + test debt)

Owner-level items that the guardrails surfaced but cannot decide.  None of them block
Track A except where noted.

### C1. `DebugDataStorage` plaintext payload — ledger §C3 (privacy posture)

Debug-variant-only JSON with financial fields + a 200-char raw-statement preview in
cleartext.  Decide: encrypt (reuse `BackupEncryptionService`), redact the preview, shorten
retention, or explicitly accept and document.  AGENTS.md's raw-text prohibition makes
"accept" a recorded exception, not a default.

### C2. `GroupLifecycleCoordinator` routing — ledger §C4 (build or delete)

Four coordinator methods + `SettlementCalculator.recordSettlement` are covered only by
contract/scenario tests; the `GroupTransactionCoordinator` routing designed to call them was
never built.  Either schedule the routing as real feature work (outside the guardrail
program) or delete the methods with their tests.  Feeds A9's row dispositions either way.

### C3. Kotlin test-stabilization workstream — ledger §D, handoff §A/§B

Separate from the guardrail program (recovery-directive R4 rules: family-based commits,
shrinking quarantine with owner/reason/expiry, no quarantine of release-critical suites):

| Item | Action |
|---|---|
| A1/A4 mockk final-class poisoning (67 cascading failures) | owner decision: one-line JVM flag via its own A/B batch, **or** fake doubles; NOT a gate unblocker (§E5) |
| `ReceiptLifecycleCoordinatorTest` (7) | stub `createExpenseDbOnlyV2`; `null()`→`isNull()` matcher fix |
| `SavingsGoalsViewModelTest` (3) | per handoff §B2 |
| `BankApiIntegrationTest` (2 paths) | AndroidKeyStore environmental — Robolectric/instrumented or cipher injection; also backlog §9.4 stale-vs-regression check at HEAD |
| 121 `domain.*` + 53 `data.*` pre-existing failures; `scenarios` never run | measure → shard → family-based fix batches; do not bulk-skip |

Take A1/A4 **only if** broad Kotlin runs are wanted for their own sake — its one identified
guardrail consumer (GR-14t) was abandoned as zero-row.

---

## 5. TRACK D — After the zero gate: GR-15 and the program tail

1. **GR-15 steps 1–8** (`PR-GR-15_enforce_proven_mediation_plan.md`).  Note: step 2 (v3 typed
   model + fail-closed loader + 12 loader tests) was landed on `gr-00-local` (`d48409c0`) and
   is **not** in this lineage — port it forward or re-derive it on the zero-gate board; do
   not assume it exists here.  Entry gate: A10 evidence + B5 trusted capture.
2. **GUARD-QA-00** — guard contract/test-coverage audit.
3. **MIG-00 → MIG-01** — migration-evidence audit, then blocking migration proof.
4. **REL-00 + CI-11** — release-artifact verification; workflow/required-check integrity.
5. **FIN-00** — final adversarial acceptance, twice, at one exact SHA (+ FIN-01 if branch
   protection is missing).

These are unchanged from `guardrail_next_pr_roadmap.md`; this plan adds nothing to them.

---

## 6. Master sequencing

```text
NOW ── A9 owner triage (parallel, mechanical; unblocks 26 rows)
   ├─ A1 labelled-return ─ A2 dangling-clause ─ A3..A5 residual refusals   (engine, serial)
   ├─ A6 D19 all-or-nothing                                               (engine, independent*)
   ├─ B1 stamps ─ B2 source-roots red ─ B3 inventory ─ B4 config-cache     (infra, serial)
   └─ B6 branch-reconciliation decision (decide now, execute before B5)
        ↓
   A7 async-22 per-row triage (needs A1–A6 board)   A8 residual-5 decision
        ↓
   A10 ZERO GATE (board 0 unproven / approved residual, double-run sha)
        ↓
   B5 GATE-00R trusted double capture ── merge gate
        ↓
   D: GR-15 (port step 2 → steps 1,3..8) → GUARD-QA-00 → MIG-00/01 → REL-00+CI-11 → FIN-00

   C1/C2/C3 owner decisions and the Kotlin test workstream run alongside,
   feeding A9/C4 and the merge gate respectively.
```

*A6 is independent of A1–A5 in mechanism but shares the board; land it between A-batches,
each with its own projection — never two engine features in one diff.

---

## 7. What NOT to do (binding, from the ledger)

- Do not add guards to restore/export/maintenance/privacy-cleanup paths (§A5) or to
  decorator-covered sinks (§A2) — count bindings, not files.
- Do not delete any writer on an `external_entry` label, above all
  `RetentionModule.provideRetentionTargets` (§A4/B2).
- Do not admit more async carriers, widen the scan scope, or regenerate/absorb baselines
  (§B5, §E3; GR-09's empty DB baseline stays empty).
- Do not attempt B1's refusal families as one diff; do not half-fix D19.
- Do not relax the anti-shadowing import guard for one row (§B3).
- Do not claim any fix without a board sha and a deterministic double run (§H.8).

---

## 8. Evidence commands

Unchanged — see ledger §G and state report §8.  Re-measure rather than quoting stale counts;
every batch note records its own board sha.
