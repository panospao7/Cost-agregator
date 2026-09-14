# GR-14 FINAL20 CORRECTION ADDENDUM — the promotion mechanism is falsified; Family E is a mass, not one fiction

Corrects two sections of `GR-14_FINAL20_DECISION_MEMO.md` (committed as
99e86311): **THE 2 AMBIGUOUS ROWS** and **FAMILY E**, plus the
**GR-15 GATE MATH** consequences.  Corrections were found by the
orchestrator's pre-flight verification (u58 session) and independently
re-verified the same day with a single-text-basis full-pipeline run
(`build/guard-debug/harness.py prove` / `prove --patched`; method note
at the end).  Nothing in this addendum is projected — every number was
reproduced on the current gr-14f tree.  No source file was modified.

## CORRECTION 1 — THE 2 AMBIGUOUS ROWS: the promotion account is wrong
at every step; option (1) must NOT be executed

FINAL20 said: "the members are NOT declared on their interfaces — so
the u54b ISSUE-7 override gate correctly refuses fan-out; promote the
methods to the interfaces (small and precedented) and the rows are
expected to prove."  That is false at every step:

1. **The interfaces already declare the members.**  `OperationRunHandle`
   declares `success/partialSuccess/failedFinal/failedRetryable/
   cancelled` (`domain/diagnostics/OperationRunRecorder.kt:43-47`) and
   `WorkerRunHandle` declares `success/skipped/retry/failure/cancelled/
   staleAborted` (`domain/workers/WorkerRunLogger.kt:44-49`).  There is
   nothing to promote; the u54b gate already admits the members.
   Promotion is a no-op and cannot change either row.
2. **The true deciders are unresolved_target name-match fan-outs from
   untyped local receivers at 8 EXTERNAL call sites**, verified present
   verbatim in source:
   - `DatabaseBackupRepositoryImpl.kt` x4 — `val run =
     operationRunRecorder.start("BACKUP_EXPORT"|"RESTORE_COSTBACKUP"|
     "RESTORE_LEGACY_DB"|"RESET_DATABASE", actor = "user")` (the u50
     chain resolver rejects the argument-bearing segments, so `run`'s
     type is never inferred);
   - `WorkerExecutionGuard.kt:149,331` — `val run = when (val
     startResult = startRunSafely(request, leaseId)) {` (when-expression
     initializer, untypable);
   - `RestoreDiagnosticsSink.kt:102,122` — `val handle =
     operationRunHandle` (bare property copy, untypable).
   Calls through these receivers (`run.failedFinal(...)` etc.) fan out
   by name-match, and some of the guessed targets sit in the two rows'
   ancestor closures.  (Count is 8 sites, not 7 as first reported.)
3. **Emulating the real fix (type annotations at all 8 sites) flips
   BOTH rows to counterexamples.**  Verified twice — in-session, then
   independently via a disk-patched single-text-basis full-pipeline run:
   - Baseline board: proven_helper 344, proven_worker_mediated 14,
     proven_restore_internal 1, unproven_async_or_escaping_callback 18,
     **unproven_ambiguous_call 2** (finalizeIfRunning,
     completeTerminal), counterexamples 0.
   - Patched board: all 17 other rows unchanged; the pair becomes
     **counterexample_unguarded_call_path** (`OperationRunDao|
     finalizeIfRunning`; reaching_root_kinds framework_callback +
     public_or_protected_external — the diagnostic finalization writes
     are reachable from MainActivity.onCreate with no canonical barrier
     anywhere, which is DDL-81-07 best-effort by design) and
     **counterexample_non_worker_root** (`BackgroundJobRunDao|
     completeTerminal`).
   - Secondary quirk confirmed: even after the annotations, 6
     unresolved_target deciders remain on the worker side
     (`Handle::success/skipped/retry/failure/staleAborted/cancelled ->
     toOutcome` — the generic-member -> ambiguous_target shape), so the
     worker-side wiring needs more than annotations.
4. **The mask finding.**  In the baseline, tier-5 uncertainty stops the
   proof early and reports `unproven_ambiguous_call`.  Once honest
   edges land, the prover finally asks "is every path to this write
   guarded?" and the honest answer for these two best-effort diagnostic
   writers is NO — by design (DDL-81-07: a diagnostic write must never
   fail a business operation, so they are deliberately not wrapped in
   canonical barriers).  **The u-pair labels were masking a
   known-by-design unguarded diagnostic path.  Any future engine typing
   improvement re-opens both rows, and what surfaces is
   counterexamples, not proofs.**

Consequences for the memo's decision table row "Ambiguous Handle
members": option (1) SOURCE interface promotion is CANCELLED — it is a
proof-destroying change, not a small precedented fix.  Option (2) OWNER
ACCEPT is the only safe endpoint, with two mandatory caveats recorded
in the acceptance entry: (a) the current label masks what the engine
would report as counterexamples if the typing noise were removed;
(b) the acceptance is stable only while the engine stays uncertain —
any typing improvement re-opens the rows.  Proving them would instead
require wrapping best-effort diagnostics in canonical barriers (an
architecture-contract change to working, behavior-sensitive code) or a
new "best-effort diagnostic channel" admission semantics (a trust-rule
weakening this campaign refused throughout).  Neither is recommended.

## CORRECTION 2 — FAMILY E: 204 deciders, not one L210 fiction

FINAL20 said the projection row
(`RecurringPlanProjectionService.projectFromOccurrencesInCurrentTransaction`)
is decided by the RestoreJournalImporter L210 preservation-rule fiction
alone.  The u58 census (reproduced via harness `closure` in 1.3s):

- the row's ancestor closure is **271 callables**;
- uncertain decider edges targeting that closure: **unresolved_target
  180 + async_dispatch 24 + function_reference 2 (~206)**, corpus-wide
  (LogSanitizer, date parsers, geocoders, RestoreDiagnosticsSink,
  CostbackupBundle, RestoreJournalImporter itself, ...);
- the L210 `interface_dispatch` edge exists and is honest (0 targets),
  but it is 1 of ~206 deciders, not the single fiction.

Consequence: the "preservation-rule refinement" as scoped would NOT
flip the row — it would remain unproven by mass honest uncertainty
instead of one fiction.  Zero rows gained, at real corpus-wide
edge-shape risk (the same class of change that exposed Correction 1).
The recommendation flips to the memo's option (3): accept the row
as-is, document the L210 fiction, and park the refinement as a future
properly-scoped batch, not a campaign deliverable.

## CORRECTION 3 — GR-15 GATE MATH

- The pair no longer "expected to PROVE": it moves to owner-accepted.
- Explicit arithmetic (FINAL20's floor of 18 already included Family E
  as honest-uncertain, so it does not carry over unchanged):
  A 6 + B 4 + C 3 + D 3 + F 1 = 17 accepted; + the pair 2 = 19; with
  Family E accepted as-is per the revised Correction 2 recommendation,
  **the campaign endpoint is 20 owner-accepted rows, 0 unproven,
  0 counterexamples, 0 proven from the pair.**  (An earlier interim
  figure of "floor 19" counted A/B/C/D/F + the pair only and predates
  the Family E recommendation flip — do not stage the gate with 19.)
- The gate text must carry Correction 1's mask caveat verbatim, so the
  number is not misread as "everything is proven safe": it means
  "everything is proven or honestly accepted with recorded reasons,
  two of which are known to be masking counterexamples".

## METHOD NOTE (for future sessions)

The in-session verification patched the corpus in memory while the
scanner and declaration index still read unpatched disk (dual text
basis: observation offsets baseline, graph offsets patched).  The
independent re-verification used
`python build/guard-debug/harness.py -f verify_u58_flips.txt`:
patches applied ON DISK (worktree must be clean; byte-verified restore
in a finally block), then the full census pipeline runs on one text
basis.  All numbers cited here are from the single-basis runs; both
methods agree on the verdicts, but future emulations must use the
single-basis path.  Cost facts: full pipeline ~210s, of which
`scan_db_access` ~190s; warm harness graph queries ~1.4s; post-patch
graph rebuild ~11-16s.  The harness is debug tooling — final
acceptance still requires one cold
`scripts/ci/inspect_db_mediation_proof.py` run.

## STATUS

- Verification runs: PASS (two independent methods, same verdicts).
- Promotion option: CANCELLED — do not execute.
- Owner decisions still PENDING: record the pair as owner-accepted with
  the mask caveats; record Family E acceptance as-is; amend the GR-15
  gate (floor 19) with the caveat; GR-15 handoff after both records
  land.
- Worktree state: clean except this addendum (uncommitted).
