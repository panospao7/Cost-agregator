# GR-14 OWNER ACCEPTANCE RECORD — the campaign endpoint decision

Records the owner decision that closes the GR-14 campaign at its honest
endpoint and is the authoritative acceptance evidence for the GR-15
gate amendment (`GR-15_GATE_AMENDMENT.md`).

Decision chain: `GR-14_FINAL20_DECISION_MEMO.md` recommendations ->
corrected by `GR-14_FINAL20_CORRECTION_ADDENDUM.md` (commit 5a41f116;
promotion mechanism falsified, Family E scope corrected, gate
arithmetic corrected) -> owner sanction 2026-09-14: accept per the
addendum.  Every number below was orchestrator-verified and
independently re-verified (single-text-basis full-pipeline runs,
2026-09-14).

## ENDPOINT

Final board `03acbb044721e9dc` (post GR-14u57b, 379 entries):

| bucket | count |
|---|---|
| proven_helper | 344 |
| proven_worker_mediated | 14 |
| proven_restore_internal | 1 |
| owner_accepted | 20 |
| unproven | 0 |
| counterexample | 0 |

The 20 accepted = Families A-F (18 rows) + the ambiguous pair (2 rows)
below.  (The interim "floor 19" figure is superseded — it predates the
Family E recommendation flip; see addendum Correction 3.)

## THE 18 FAMILY ROWS — accepted per the FINAL20 recommendations

- **Family A — 6 rows** (ReceiptLinkService.linkReceiptToExpense:
  ReceiptExpenseLinkDao.insert, ReceiptItemCategorizationDao.
  linkToExpense, ReturnWindowDao.updateExpenseIdByReceiptId,
  ScannedReceiptDao.claimForAutoMatch, ScannedReceiptDao.update,
  WarrantyDao.updateExpenseIdByReceiptId): option (3), accepted async
  debt.  Basis: deliberate post-commit architecture — the lambdas are
  stored and executed by PostCommitActionRunnerImpl.run after commit
  (temporal, not concurrent, escape); the runner is already the legal
  owner.
- **Family B — 4 rows** (PrivacyAuditLoggerImpl.logDecision,
  RoomReceiptLifecycleEventWriter.write,
  RoomTransactionLifecycleEventWriter.write,
  WarrantyTrackerRepository.toWarrantyEntityOrNull): option (1),
  honest async uncertainty.  Basis: `combine` re-invokes its transform
  per emission — exactly-once fails structurally; the engine is
  correctly refusing.
- **Family C — 3 rows** (OperationRunRecorder.Handle.event,
  OperationRunRecorder.Handle.start, WorkerRunLogger.start): option
  (1), framework-escaping.  Basis: setOnClickListener lambdas are
  invoked by the Android framework on user interaction; timing is
  statically unknowable.
- **Family D — 3 rows** (RecurringLifecycleEventWriter.writeCritical,
  SourceLinkWriterImpl.linkTarget,
  TransactionLifecycleCoordinator.createExpenseMutation): option (1),
  honest async uncertainty.  Basis: runWithRetry conditionally
  re-invokes its block (data-dependent invocation count);
  factually inadmissible.
- **Family E — 1 row** (RecurringPlanProjectionService.
  projectFromOccurrencesInCurrentTransaction): accepted AS-IS (memo
  option 3), per addendum Correction 2.  Basis: the row's closure
  carries ~204 honest deciders (unresolved_target 180 + async_dispatch
  24 + function_reference 2 over a 271-callable closure) — the L210
  interface_dispatch edge is honest (0 targets) and one of many, not a
  removable fiction.  The preservation-rule refinement is PARKED as a
  future properly-scoped batch, not a campaign deliverable.
- **Family F — 1 row** (DiagnosticEventWriter.emit): option (2),
  permanent honest uncertainty.  Basis: `flow {}`/emit escapes per
  collector and per re-collection — exactly-once fails structurally;
  the emit receiver is an external lambda receiver the engine cannot
  type.  No capability batch is sanctioned for one row.

## THE 2 AMBIGUOUS ROWS — accepted with MANDATORY caveats

Rows: RoomOperationRunRecorder.Handle.finalizeNonCancellable
(OperationRunDao.finalizeIfRunning), WorkerRunLoggerImpl.Handle.terminal
(BackgroundJobRunDao.completeTerminal).

Accepted as owner-accepted honest-uncertainty.  The FINAL20 "interface
promotion" option is CANCELLED — falsified by the addendum Correction
1: the interfaces already declare the members, and emulating the real
fix (typing the 8 untyped receiver sites) flips BOTH rows to
counterexamples (verified twice, including one single-text-basis
disk-patched full-pipeline run).  Proving them would require wrapping
best-effort diagnostic writes in canonical barriers (an
architecture-contract change to working code) or a diagnostic-channel
admission exemption (a trust-rule weakening) — neither sanctioned.

The acceptance record for these two rows MUST carry both caveats
verbatim:

1. **Mask caveat:** the current `unproven_ambiguous_call` label masks
   what the engine would report as `counterexample_unguarded_call_path`
   (finalizeIfRunning; reaching_root_kinds framework_callback +
   public_or_protected_external) and `counterexample_non_worker_root`
   (completeTerminal) if the typing noise were removed.  The paths are
   unguarded BY DESIGN — DDL-81-07: a diagnostic write must never fail
   a business operation, so these writers sit outside canonical
   barriers deliberately.
2. **Stability caveat:** this acceptance is stable only while the
   engine stays uncertain.  ANY future engine typing improvement that
   resolves the 8 untyped-receiver decider sites re-opens both rows —
   and what re-surfaces is counterexamples, not proofs, requiring an
   architecture-level owner decision at that time.

## RE-OPENING AND SCOPE

- The gate's counterexample tolerance remains ZERO — this record does
  not weaken it.
- Families A-F may be re-opened by owner decision (the FINAL20
  remediation options remain enumerated there).
- The pair re-opens automatically in the sense above: it cannot be
  held closed against an improved engine except by a new explicit
  owner decision after the counterexamples surface.
- These acceptances are decisions about PROOF SEMANTICS, not safety
  claims: they record honest uncertainty and deliberate design, not
  proven safety.

## VERIFICATION STATUS

- Baseline board reproduced 2026-09-14 (cold single-basis full
  pipeline): proven_helper 344, proven_worker_mediated 14,
  proven_restore_internal 1, unproven_async_or_escaping_callback 18,
  unproven_ambiguous_call 2, counterexamples 0 — matches
  `03acbb044721e9dc`.
- Patched-emulation run (the cancelled fix path): both rows flip to
  counterexamples, all 17 other rows unchanged — the evidence behind
  Corrections 1 and the caveats above.
- Tooling: `build/guard-debug/harness.py` (debug only; final acceptance
  remains a cold `scripts/ci/inspect_db_mediation_proof.py` run).
