# GR-14 FINAL20 DECISION MEMO — the last 20 unproven rows (GR-15 gate input)

Supersedes `GR-14_FINAL26_DECISION_MEMO.md` (the campaign has advanced
since that memo; its F1/F2 surfaces and one F4 row no longer exist, its
F3 family survives refined as Family A below, and its remaining
open-interface rows are the ambiguous pair below).

Purpose: the consolidated owner-decision memo for the 20 rows the GR-14
campaign leaves unproven, as the gate input for GR-15 — the plan
requires `STILL UNPROVEN: 0`, so every row below must end the campaign
either PROVEN or carrying an explicit, recorded owner decision.  Board
`03acbb044721e9dc` (post GR-14u57b): proven_helper 344,
proven_restore_internal 1, proven_worker_mediated 14,
unproven_ambiguous_call 2, unproven_async_or_escaping_callback 18,
counterexamples 0 (379 entries).  Campaign arc: unproven 105 -> 50 ->
20 across 38 commits, 7 true positives found and fixed, zero
counterexamples, zero previously-proven regressions in the final state.
The 20 rows = 2 ambiguous + 18 async; the 18 map to 6 decider families
(the u57b post-fix prover-exact census, refined by the u58a probe).
Every number below is orchestrator-verified against that board and
source.

## FAMILY A — 6 rows, async_dispatch from ReceiptSideEffectPlanner
PostCommitAction (the post-commit family)

Rows: ReceiptLinkService.linkReceiptToExpense x6 (ReceiptExpenseLinkDao.
insert, ReceiptItemCategorizationDao.linkToExpense,
ReturnWindowDao.updateExpenseIdByReceiptId,
ScannedReceiptDao.claimForAutoMatch, ScannedReceiptDao.update,
WarrantyDao.updateExpenseIdByReceiptId).

Deciding edge: ReceiptSideEffectPlanner.kt L305, carrier=async
method=PostCommitAction.

Verified mechanism (u58a refinement of the FINAL26 F3 account): the
PostCommitAction lambdas are STORED (data-class property) and executed
later by PostCommitActionRunnerImpl.run AFTER the DB transaction
commits.  The escape is TEMPORAL — the lambdas run synchronously within
the runner's execution frame, not concurrently — but they still run
outside the enclosing function's lifetime, which is exactly what the
prover cannot admit.  This is deliberate architecture (the documented
post-commit side-effect design), not a defect.

Remediation options:
  (1) ENGINE — model the runner execution frame: the action.execute
      dispatch plus the runner chain, so tier-5 resolves the stored
      lambdas inside the runner's frame.  The planner's lambda-wrappers
      are corpus classes — implementability must be assessed before this
      is a schedulable batch.  A design question, not a bug fix.
  (2) SOURCE — the GR-14 plan's Pattern D: typed commands instead of
      stored lambdas.  A bounded refactor of the post-commit
      architecture; behavior-adjacent and outside the engine's remit.
  (3) OWNER ACCEPT — document as accepted-async-debt.

Recommendation input: the runner IS the legal owner — the plan's
"make legal owner decide and perform mutation" principle is arguably
already satisfied by the PostCommitActionRunnerImpl.run design, so
acceptance is defensible on the merits.  Option 1 is a design question
with unassessed implementability; option 2 is a real refactor.  Absent
owner appetite for either, (3) is the honest default.

## FAMILY B — 4 rows, async_dispatch from FinancialWeatherRepository
getFinancialWeather L67 (the combine family)

Rows: PrivacyAuditLoggerImpl.logDecision (PrivacyAuditDao.insert),
RoomReceiptLifecycleEventWriter.write (ReceiptEventDao.insert),
RoomTransactionLifecycleEventWriter.write (TransactionEventDao.insert),
WarrantyTrackerRepository.toWarrantyEntityOrNull
(WarrantyLifecycleEventDao.insert).

Deciding edge: FinancialWeatherRepository.kt L67, carrier=async — the
whole getFinancialWeather body sits inside a `combine { }` lambda.

Verified mechanism: combine's transform lambda is invoked per
combination of latest values — RE-INVOKED per emission, so the
exactly-once bar FAILS STRUCTURALLY (the same class as `flow {}`).
NOT admissible as a transparent carrier.  The engine is correctly
refusing; this is not a parser gap.

Remediation options:
  (1) OWNER ACCEPT — honest async uncertainty: the concurrency and
      reactivity are real.
  (2) SOURCE restructure — move the DB writes out of the combine lambda
      into the guarded frame.  Behavior-sensitive: changes when the
      writes run relative to the flow's emission lifecycle.

Recommendation: (1) — the reactivity is genuine; the engine is
correctly refusing.

## FAMILY C — 3 rows, async_dispatch from RescueActivity.onCreate L31
(the framework UI callback family)

Rows: OperationRunRecorder.Handle.event (OperationRunEventDao.insert),
OperationRunRecorder.Handle.start (OperationRunDao.insert — the
SafeEventMetadata variant per the FINAL26 account),
WorkerRunLogger.start.

Deciding edge: RescueActivity.kt L31, carrier=async
method=setOnClickListener.

Verified mechanism: framework UI callbacks — the lambdas escape into
the Android framework and are invoked on user interaction, with timing
unknowable statically.  The exactly-once/synchronous-ancestor bar
cannot be met by any static admission; the engine is correctly
refusing.

Remediation options:
  (1) OWNER ACCEPT — framework-escaping, honest.
  (2) SOURCE restructure — move the diagnostic writes out of the click
      handlers into a guarded path.  Behavior-sensitive: changes when
      the diagnostics run relative to the interaction.

Recommendation: (1) — UI event timing is genuinely static-unknowable.

## FAMILY D — 3 rows, async_dispatch from ReceiptOcrService L237
(the runWithRetry family)

Rows: RecurringLifecycleEventWriter.writeCritical
(RecurringLifecycleEventDao.insert), SourceLinkWriterImpl.linkTarget
(EntitySourceLinkDao.insert), TransactionLifecycleCoordinator.
createExpenseMutation (TransactionEventDao.insert).

Deciding edge: ReceiptOcrService.kt L237, carrier=async
method=runWithRetry.

Verified mechanism: runWithRetry conditionally re-invokes its block on
retry (data-dependent invocation count) — the exactly-once bar FAILS;
factually inadmissible.  The B5 census settled this in u56d; the engine
is correctly refusing.

Remediation options:
  (1) OWNER ACCEPT — honest async uncertainty: retry loops are
      genuinely multi-invocation.
  (2) SOURCE restructure — single-invocation attempt plus explicit
      retry at the caller.  Behavior-sensitive: reshapes the retry
      semantics the callee owns today.

Recommendation: (1) — the retry semantics are real.

## FAMILY E — 1 row, the RestoreJournalImporter L210 preservation-rule
fiction (the name-match pollution family)

Row: RecurringPlanProjectionService.projectFromOccurrencesInCurrentTransaction.

Deciding edge: RestoreJournalImporter.kt L210 (the runCatching chain),
propagating by name-match into the row's ancestor closure.

Verified mechanism: the u46-claimed `runCatching{...}.onFailure{...}`
chain region is transparent; the call `operationRunEventDao.insert(...)`
resolves its receiver to the OperationRunEventDao interface (0 corpus
implementors — the Room impl is build-generated) -> interface_dispatch
with 0 targets.  The GR-14f preservation rule then replaces that honest
edge with async_dispatch name-match noise (59 corpus `insert` callables), and one of the 59 happens to sit in the
RecurringPlanProjectionService row's ancestor closure -> the row is
decided unproven by FICTION, not by its own evidence.

Scope note (u58a-verified): the importer's OWN rows are PROVEN
(barrierMode helper, localGuard direct — the u52 receiver hints plus
the u51b barrier checks).  The L210 fiction only pollutes OTHER rows'
closures via name-match; it does not touch the importer's own proof
state.

Remediation options:
  (1) ENGINE — a @Dao-scoped zero-implementor dispatch rule.  DAO
      interfaces are Room-generated; their implementor sets are
      build-generated and CANNOT be lambdas or anonymous objects — the
      reviewer's RetentionTarget counterexample does not apply to @Dao
      interfaces — and the interface's own member set is the
      statically-known dispatch target.  This is NEW ADMISSION
      SEMANTICS -> owner sign-off required (the reviewer previously
      REJECTED the BLANKET zero-implementor rule; a @Dao-scoped rule is
      narrower and soundness-arguable, but it is still a semantics
      change).
  (2) ENGINE — a preservation-rule refinement: do not replace
      interface_dispatch edges with name-match noise.  The edge stays
      interface_dispatch (honest uncertainty), the name-match noise
      disappears, and the row's fate depends on its own local evidence
      rather than fiction.  Also semantics-adjacent -> owner sign-off.
  (3) OWNER ACCEPT — the row stays honest-uncertain under the fiction.

Recommendation: option 2 — it removes the fiction without introducing
new dispatch semantics: the honest interface_dispatch uncertainty is
preserved, the 59-callable noise fan-out disappears from the graph, and
the row is judged on its own evidence.  It remains an
uncertainty-semantics change, so owner sign-off is required either way.

## FAMILY F — 1 row, async_dispatch from DatabaseReadBarrierFlowExt L19
(the guardedDatabaseRead emit family — the u57b D2 deferral)

Row: DiagnosticEventWriter.emit (PipelineDiagnosticEventDao.insert).

Deciding edge: DatabaseReadBarrierFlowExt.kt L19, the
guardedDatabaseRead `collect { emit }` shape, carrier=async.

Verified mechanism (the u57b D2 deferral, now surfaced as a row
decider): `flow {}` can never be admitted as a transparent carrier —
the block is invoked ONCE PER COLLECTOR and re-invoked on every
re-collection, so the exactly-once bar fails STRUCTURALLY (not
corpus-observationally).  Additionally the emit receiver is the
FlowCollector implicit `this` — an EXTERNAL lambda RECEIVER the engine
cannot type (u45/u55b type lambda PARAMS, not receivers).  Census: 8
flow{}-emit async edges corpus-wide; only guardedDatabaseRead L19
decides a row today.

Remediation options:
  (1) ENGINE — external lambda-receiver typing.  A new engine
      capability (receiver typing alongside the existing param typing).
  (2) OWNER ACCEPT — the row stays honest-uncertain.

Recommendation: option 2 — it is 1 row, and the capability is a large
feature to build for a single row.

## THE 2 AMBIGUOUS ROWS — Handle members not declared on their
interfaces (the u54b ISSUE-7 pair)

Rows: RoomOperationRunRecorder.Handle.finalizeNonCancellable,
WorkerRunLoggerImpl.Handle.terminal — both
`unproven_ambiguous_call` / GR13_UNCERTAIN_CALL_EDGE.

Verified mechanism: the members are NOT declared on their interfaces —
they are defined directly on the Handle classes — so the u54b ISSUE-7
override gate correctly refuses fan-out.  The gate refusal is CORRECT
engine behavior, not a defect.

Remediation options:
  (1) SOURCE — promote the methods to the interfaces.  Small and
      precedented; every implementor needs the member, so the
      implementor sets must be checked before the change lands.  Once
      the members are on the interfaces, the u54b gate can admit the
      fan-out and the rows are expected to prove.
  (2) OWNER ACCEPT — the rows stay honest-uncertain.

Recommendation: option 1 — small and precedented.

## SUMMARY DECISION TABLE

| Family | Rows | Recommended action | Owner decision needed | The specific question |
|---|---|---|---|---|
| A PostCommitAction | 6 | (3) OWNER ACCEPT as default; (1)/(2) only on owner appetite | YES | Accept the 6 rows as honest-uncertain (deliberate temporal escape; the runner is already the legal owner), sanction the runner-frame modelling design question (option 1), or sanction the Pattern D typed-commands refactor (option 2)? |
| B combine | 4 | (1) OWNER ACCEPT as honest async uncertainty | YES | Accept the 4 rows as permanent honest-uncertain (combine's per-emission re-invocation is real reactivity), or sanction moving the DB writes out of the combine lambda (behavior-sensitive)? |
| C setOnClickListener | 3 | (1) OWNER ACCEPT as framework-escaping | YES | Accept the 3 rows as permanent honest-uncertain (UI event timing is statically unknowable), or sanction moving the diagnostic writes out of the click handlers (behavior-sensitive)? |
| D runWithRetry | 3 | (1) OWNER ACCEPT as honest async uncertainty | YES | Accept the 3 rows as permanent honest-uncertain (retry loops are genuinely multi-invocation), or sanction the single-invocation-attempt restructure (behavior-sensitive)? |
| E RestoreJournalImporter L210 | 1 | (2) preservation-rule refinement | YES | Approve the preservation-rule refinement (interface_dispatch edges are no longer replaced by name-match noise — an uncertainty-semantics change), the narrower @Dao-scoped dispatch rule (new admission semantics), or accept the row as-is? |
| F guardedDatabaseRead emit | 1 | (2) OWNER ACCEPT | YES | Accept the row as permanent honest-uncertain, or sanction the external lambda-receiver typing capability (a large engine feature for one row)? |
| Ambiguous Handle members | 2 | (1) SOURCE interface promotion | YES | Approve promoting finalizeNonCancellable/terminal to their Handle interfaces (small, precedented; implementor sets must carry the member), or accept the 2 rows as honest-uncertain? |

## GR-15 GATE MATH

If every recommendation above is accepted:

- A: 6 rows remain (accepted async debt; they move only if the owner
  sanctions option 1 or 2 and it lands).
- B + C + D + F: 11 rows remain (accepted honest uncertainty — the
  engine is correctly refusing in all four families).
- E: the row remains honest-uncertain under the refinement — the
  fiction is removed, but the row's fate reverts to its own local
  evidence, which is an interface_dispatch (0-target) uncertainty.
- Ambiguous: expected to PROVE via interface promotion, conditional on
  the implementor sets carrying the member and the u54b gate then
  admitting the fan-out.

Floor: 18 rows (6 + 4 + 3 + 3 + 1 + 1) — the minimum the board can
reach with all recommendations accepted.

What `STILL UNPROVEN: 0` would require: every family must take its
non-default leg —

- A: option 1 (engine runner-frame modelling — a design question with
  unassessed implementability) or option 2 (the Pattern D refactor);
- B: option 2 (move the DB writes out of the combine lambda —
  behavior-sensitive);
- C: option 2 (move the diagnostic writes out of the click handlers —
  behavior-sensitive);
- D: option 2 (single-invocation attempt plus explicit caller retry —
  behavior-sensitive);
- E: option 1 (the @Dao-scoped dispatch rule — new admission
  semantics), or option 2 plus the row's local evidence resolving the
  interface_dispatch uncertainty (it currently does not);
- F: option 1 (external lambda-receiver typing — a new engine
  capability);
- ambiguous: option 1 (interface promotion).

Every one of these is an owner-sign-off surface: new admission
semantics, a behavior-sensitive source restructure, or a new engine
capability.  Alternatively, the GR-15 gate definition itself would have
to be amended to credit owner-accepted rows — a gate-semantics decision
that belongs to the owner, not to this campaign.  Under the current
gate semantics and the recommendations above, the honest campaign
endpoint is 18 accepted rows plus 2 proven rows, with the path to 0
fully enumerated and fully gated.

## NO SILENT PARSER GAPS REMAIN

Every engine mechanism the campaign identified is now landed, disclosed,
or an owner decision — no silent parser gaps remain.  The LANDED set
(the transparent-inline set extensions per the u38/u48/u54 precedent,
the u56e withRateLimit extension, the u57a typed-val hoisting, the
u57b preservation-rule scope exclusion, the u45/u55b receiver/param
typing, the u51b barrier checks and u52 receiver hints) is pinned by
fixtures and tests.  The DISCLOSED set (the board-inert deciders —
suggest L143, the extension-carve-out x preservation-rule interaction,
the F-TRANSPARENT-CHAIN-NOISE family) is documented in the batch
manifests.  The OWNER-DECISION set is exactly the table above.  No
row's unproven state is caused by an unidentified parser defect: each
is real reactivity (B), framework escaping (C), genuine retry semantics
(D), deliberate temporal architecture (A), an honest interface-dispatch
uncertainty polluted by a now-understood preservation-rule fiction (E),
or an untypeable external lambda receiver (F) — plus the 2 ambiguous
rows whose gate refusal is correct behavior awaiting a small source
change.
