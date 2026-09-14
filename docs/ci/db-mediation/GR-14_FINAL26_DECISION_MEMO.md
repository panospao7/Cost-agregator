# GR-14 FINAL26 DECISION MEMO — the last 26 unproven rows (GR-15 gate input)

Purpose: the consolidated owner-decision memo for the 26 rows the GR-14
campaign leaves unproven, as the gate input for GR-15.  Board
`dc8c91b88b5561af` (post GR-14u53): proven_helper 338,
proven_restore_internal 1, proven_worker_mediated 14,
unproven_ambiguous_call 3, unproven_async_or_escaping_callback 23,
counterexamples 0.  Program context: 20 batches (u22-u53), unproven
105 -> 26, zero counterexamples, zero previously-proven regressions in
the final state.  Every number below is probe-verified against the
current board and source; the two open design questions were scouted
read-only (F2 wrapper shape; F4 fan-out gate blocker) and the findings
are incorporated.

## FAMILY F1 — 11 rows, async_dispatch from ReceiptRepository.processBatch
(the withPermit family)

Rows: ReceiptLifecycleCoordinator.processReceiptInput x4
(PendingReviewDao.deleteByScannedReceiptId, PendingReviewDao.insert,
ScannedReceiptDao.delete, ScannedReceiptDao.update),
OperationRunRecorder.Handle.event (OperationRunEventDao.insert),
RoomOperationRunRecorder.start (OperationRunDao.insert — the
SafeEventMetadata Handle.start variant), ReceiptInsertResolver.
insertOrResolve (ScannedReceiptDao.insert),
RoomRecurringLifecycleEventWriter.writeCritical
(RecurringLifecycleEventDao.insert), SourceLinkWriterImpl.linkTarget
(EntitySourceLinkDao.insert), TransactionLifecycleCoordinator.
createExpenseMutation (TransactionEventDao.insert).

Deciding edge: ReceiptRepository.kt ~L591 (processBatch),
async_dispatch inside carrier=transparent method=withPermit.  The call
`receiptLifecycleCoordinator.get().processReceiptInput(...)` sits inside
`coroutineScope { map { async { withContext {
semaphore.withPermit { ... } } } } }`.

Verified mechanism: `_uncertain_region_state` walks innermost-first;
`withPermit`/`withContext` are transparent (pass through), but the
enclosing `async { }` region is NOT admitted, so the region state returns
ASYNC_DISPATCH with name-match targets.  The DI-get-chain unwrap never
runs for calls inside async regions — CORRECT engine behavior: calls
inside async lambdas genuinely may run after the enclosing function
returns.  This is not a parser gap.

Remediation options:
  (a) SOURCE restructure — sequential processing instead of
      coroutineScope/async.  Behavior-sensitive: changes the concurrency
      semantics of batch receipt processing (parallel fan-out to N
      targets becomes serial); needs owner judgment on the performance
      tradeoff and on whether any caller depends on the parallelism.
  (b) ENGINE admit coroutineScope/async — REJECTED by ledger B5
      (measured negative: +0 proven, 1 counterexample).  Not available.
  (c) OWNER ACCEPT — document as accepted-async-debt.

Recommendation input: the concurrency here is bounded
(semaphore.withPermit gates the parallelism) and the callee is a
lifecycle coordinator whose own entry is barrier-checked; the
uncertainty is REAL (the engine cannot prove a synchronous ancestor for
genuinely concurrent work), not a defect.  (c) is the honest default;
(a) only if the owner values the proof over the concurrency.

## FAMILY F2 — 6 rows, async_dispatch from
HybridDedupeJudgeService.safeExecute (the AI dedupe judge wrapper)

Rows: PrivacyAuditLoggerImpl.logDecision (PrivacyAuditDao.insert),
RoomDiagnosticEventWriter.emit (PipelineDiagnosticEventDao.insert),
RoomReceiptLifecycleEventWriter.write (ReceiptEventDao.insert),
RecurringPlanProjectionService.projectFromOccurrencesInCurrentTransaction
(PlannedExpenseDao.insertPlannedExpense),
RoomTransactionLifecycleEventWriter.write (TransactionEventDao.insert),
WarrantyTrackerRepository.toWarrantyEntityOrNull
(WarrantyLifecycleEventDao.insert).

Deciding edge: HybridDedupeJudgeService.kt L79-89 (safeExecute),
carrier=async method=safeExecute (probe-verified: the judge callables'
lambda regions all classify `carrier: async method: safeExecute`).

Scout finding (read-only, file:line verified): `safeExecute` is a
PRIVATE SUSPEND FUN wrapper whose body is
`return try { block() } catch (e: Exception) { Timber.e(...);
AiServiceResult.Failure(...) }` — the block is invoked EXACTLY ONCE
INLINE (L84), never stored, never conditionally invoked, never escapes.
This is the u38-precedent set-extension shape (the same evidence bar
guardTerminal satisfied in u48): (a) invoked exactly once inline, (b) no
storage/escape, (c) no conditional invocation, (d) the u48 precedent's
reviewed-diff + pin-amendment + fixtures + shadow-delta process.

NOTE: the u43 hypothesis for this family (the CloudDashboardBriefingService
PrivacyGate phantom) was DISPROVEN by the u49-u53 progression — the
phantom was suppressed in u49 and these rows did not move; the real
decider is safeExecute.

Remediation options:
  (i) u54 set-extension batch — add `safeExecute` to
      PRODUCTION_TRANSPARENT_INLINE_METHODS per the u38/u48 precedent.
      The evidence bar is ALREADY MET by the scout findings; the batch
      would need the standard pin amendment, fixtures, and shadow-delta
      gate.  Expected effect: the 6 rows' deciding async regions become
      transparent, the inner calls resolve through the ordinary paths,
      and the rows move per their next deciders (they may prove or
      surface the next uncertainty — the u47 lesson applies).
  (ii) OWNER ACCEPT — honest-uncertain.

Recommendation: (i) is precedented, evidence-complete, and low-risk
(transparent admission does not change uncertainty semantics — it
removes a FALSE async classification for an exactly-once-inline
wrapper).  Owner decision needed: YES (set extensions touch the closed
carrier set).

## FAMILY F3 — 6 rows, async_dispatch from ReceiptSideEffectPlanner
PostCommitAction (the F-POSTCOMMIT family)

Rows: ReceiptLinkService.linkReceiptToExpense x6
(ReceiptExpenseLinkDao.insert, ReceiptItemCategorizationDao.
linkToExpense, ReturnWindowDao.updateExpenseIdByReceiptId,
ScannedReceiptDao.claimForAutoMatch, ScannedReceiptDao.update,
WarrantyDao.updateExpenseIdByReceiptId).

Deciding edge: ReceiptSideEffectPlanner.kt L305 area, carrier=async
method=PostCommitAction.

Verified mechanism: the PostCommitAction lambdas are STORED (data-class
property) and executed LATER by PostCommitActionRunner after the DB
transaction commits — genuinely escaping BY DESIGN (the documented
post-commit side-effect architecture; SideEffectOutcome.Completed
contract).

Remediation options:
  (a) ENGINE/OWNER — model the runner's execution context: if
      PostCommitActionRunner runs the stored lambdas inside a canonical
      guard scope, tier-5 could resolve via the runner's context.  This
      requires the runner to be modelable AND the dispatch enumerable —
      a design question, not a bug fix.
  (b) OWNER ACCEPT — the escaping is deliberate architecture; the GR-14
      charter forbids broad cross-pipeline refactors, and changing the
      post-commit design is out of scope.

Recommendation input: the uncertainty is ARCHITECTURAL, not a defect —
the engine is correctly reporting that these writes run outside the
enclosing function's lifetime.  (b) is the honest default; (a) only if
the owner wants the post-commit pipeline provable and is willing to
sanction a runner-context admission design.

## FAMILY F4 — 3 rows, interface_dispatch from BankApiIntegration
(the open-interface rows)

Rows: Handle.finalizeNonCancellable (OperationRunDao.finalizeIfRunning),
Handle.increment (OperationRunDao.incrementCounters), Handle.terminal
(BackgroundJobRunDao.completeTerminal) — all `unproven_ambiguous_call` /
GR13_UNCERTAIN_CALL_EDGE.

Deciding edge: BankApiIntegration.syncTransactions' `run.event` /
`run.increment` calls; the receiver resolves EXACTLY to
OperationRunHandle via u45 Shape A (probe-verified: receiver FQCN
`com.yourname.expensetracker.domain.diagnostics.OperationRunHandle`,
known=True), and the interface_dispatch edges carry the full 3-target
implementor set (RoomOperationRunRecorder.Handle,
SafeSinkOperationRunHandle, NoOpOperationRunHandle).

Scout finding (the exact fan-out blocker): `_bounded_fanout_targets`
returns [] at its SECOND gate — `if scanned_anon <= 0: return []`.  The
gate was written for the u49 RetentionTarget anonymous-object shape and
requires at least one ANONYMOUS implementor site.  OperationRunHandle
has 3 NAMED corpus implementors and 0 anonymous sites — the implementor
set IS enumerable-complete (3 corpus owners, re-derived per run by the
same completeness argument the u49 admission rests on), the receiver
resolves, every implementor owns exactly one override member (the
ISSUE-7 gate would pass), but the anon-precondition rejects the
admission before completeness is ever evaluated.  WorkerRunHandle has 1
named implementor (WorkerRunLoggerImpl.Handle) — same blocker.

Remediation options:
  (i) u54 engine extension — drop the anon-precondition: admit bounded
      fan-out when the implementor set is enumerable-complete regardless
      of anonymous-site presence.  Soundness: the same per-run
      completeness re-derivation applies (a new implementor changes the
      set and the admission re-derives); the all-or-nothing proof
      behavior is unchanged; the ISSUE-7 override-only gate still
      applies.  Expected effect: the run.event/run.increment edges
      become exact 3-target fan-out; the 3 ambiguous rows' closures lose
      the interface_dispatch uncertainty and move per their next
      deciders (likely proven under the worker guard, mirroring the u52
      outcome for the RetentionModule rows).
  (ii) OWNER ACCEPT — the rows stay honest-uncertain.

Recommendation input: this is a SMALL, precedented engine change (the
u49 machinery already exists; one gate condition relaxes), but it IS a
new admission-semantics decision — dispatch to a fully-enumerable
implementor set becomes exact for NAMED-only interfaces, which the
charter reserves for owner sign-off.  Owner decision needed: YES.

## SUMMARY DECISION TABLE

| Family | Rows | Recommended action | Owner decision needed | The specific question |
|---|---|---|---|---|
| F1 withPermit | 11 | (c) OWNER ACCEPT as accepted-async-debt | YES | Accept the 11 rows as permanent honest-uncertain (real concurrency), or sanction a source restructure of processBatch (behavior-sensitive)? |
| F2 safeExecute | 6 | (i) u54 set-extension batch | YES | Approve adding `safeExecute` to PRODUCTION_TRANSPARENT_INLINE_METHODS (evidence-complete: exactly-once-inline, no storage/escape/conditional — HybridDedupeJudgeService.kt L79-89)? |
| F3 PostCommitAction | 6 | (b) OWNER ACCEPT (architectural escaping) | YES | Accept the 6 rows as honest-uncertain (deliberate post-commit architecture), or sanction a runner-context admission design? |
| F4 open interfaces | 3 | (i) u54 fan-out gate extension | YES | Approve dropping the anon-precondition from `_bounded_fanout_targets` (named-only enumerable-complete interfaces gain bounded fan-out — new admission semantics)? |

## WHAT CANNOT BE DONE WITHOUT OWNER SIGN-OFF

Per the GR-14 charter ("no silent semantics change"): ANY new admission
semantics — set extensions (F2), dispatch-admission gate changes (F4),
runner-context modelling (F3), or async-region admission (F1 option b,
already rejected by ledger B5) — requires explicit owner sign-off before
implementation.  Source restructures (F1 option a) are behavior changes
outside the engine's remit.  Everything the engine could do WITHOUT new
semantics has been done: 105 -> 26 unproven across 20 batches, 0
counterexamples, 0 previously-proven regressions, and every remaining
row's uncertainty is either real concurrency (F1), deliberate
architecture (F3), a sanctioned-semantics decision (F2/F4), or an
open-interface classification (the 3 ambiguous rows inside F4).
