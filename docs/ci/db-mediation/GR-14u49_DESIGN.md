# GR-14u49 DESIGN — anonymous-object engine batch (owner sign-off vehicle)

STATUS: DESIGN — PENDING OWNER SIGN-OFF. Not implemented; no code change
and no board claim is made by this document.

## Scope and goal

Lift the u43 F-ANON-OBJECT family (16 unproven rows) to provable or
honestly-classified states WITHOUT weakening the detector, via three
coordinated engine changes in the mediation callgraph:

1. PHANTOM-CALL SUPPRESSION — stop treating `object : T {` as a call with
   a trailing lambda.
2. MEMBER MODELLING — anonymous-object member functions become corpus
   callables with deterministic owner identity.
3. DISPATCH ADMISSION (decision point, owner sign-off required) — bounded
   fan-out exact edges for fully-enumerable implementor sets.

The 16 rows (board 54278165 baseline):

- 10 x `unproven_async_or_escaping_callback` /
  `GR13_SITE_INSIDE_UNRESOLVED_LAMBDA`: every `RetentionModule.
  provideRetentionTargets` mutation row (AiArtifactDao.deleteExpired,
  AiChatMessageDao.deleteOlderThan, BackgroundJobRunDao.
  redactErrorMessagesOlderThan, BankStatementImportItemDao.
  redactMerchantOlderThan, EmailReceiptDao.redactSensitiveFieldsOlderThan,
  NotificationIntakeDao.purgeRawPayload, PendingReviewDao.
  redactNotificationTextOlderThan, PipelineDiagnosticEventDao.
  deleteOlderThan, RawNotificationDao.updateRawContentPurged,
  ScannedReceiptDao.updateRawOcrTextPurged).
- 6 x `unproven_async_or_escaping_callback` / `GR13_UNCERTAIN_CALL_EDGE`:
  PrivacyAuditLoggerImpl.logDecision, WarrantyTrackerRepository.
  toWarrantyEntityOrNull, RoomDiagnosticEventWriter.emit,
  RoomReceiptLifecycleEventWriter.write,
  RecurringPlanProjectionService.projectFromOccurrencesInCurrentTransaction,
  RoomTransactionLifecycleEventWriter.write — decided by the phantom async
  region over `CloudDashboardBriefingService.failClosedGate`'s
  `object : PrivacyGate { ... }` (companion).

## Verified mechanism

`_CALL_CANDIDATE_RE` (callgraph.py) matches `RetentionTarget {` /
`PrivacyGate {` inside `object : Type {` as a call with a trailing
lambda. The call record spawns a phantom async lambda region over the
object body, which:

(a) classifies mutation sites inside the object's member functions as
    tier-3 SITE_INSIDE_UNRESOLVED_LAMBDA (RetentionModule's 10 rows —
    the sites live in the object's `purge(...)` overrides but attribute
    to the enclosing provider callable), and
(b) gives the enclosing callable (`failClosedGate`) an async region whose
    outgoing edges decide the 6 PrivacyGate-phantom rows.

Existing u23 machinery (reusable, no new regex needed):
`_ANON_OBJECT_RE` matches `object\s*:\s*` (colon directly after `object`,
so NAMED `object Name : T` declarations never match — those are owners
already), and `_scan_anonymous_implementors` counts anonymous implementor
sites per corpus supertype. Anonymous objects never enter the owner table
today.

## Corpus census (verified, orchestrator probe)

- TOTAL anonymous-object sites in the production corpus: 178.
- Corpus-supertype sites: 30, across 10 corpus supertypes:
  PrivacyGate 12 (8 files), RetentionTarget 10 (1 file —
  RetentionModule.kt), and 8 singles (WorkerLeaseRegistry 1,
  WorkerLease 1, DiagnosticEventWriter 1, RestoreDatabaseOpener 1,
  OperationRunRecorder 1, PrivacySettingsRepository 1,
  AiSettingsRepository 1, PrivacyAuditLogger 1).
- External-supertype sites: 148 — androidx.room.migration.Migration 138
  (2 files), gson TypeToken 2, java.util.LinkedHashMap 2,
  RoomDatabase.Callback 1, okhttp3.Callback 1,
  android.speech.RecognitionListener 1, vico MarkerComponent 1, osmdroid
  MapEventsReceiver 1, android.view.View.OnLayoutChangeListener 1.
- SCOPE BOUNDARY (deliberate): member modelling is scoped to
  CORPUS-SUPERTYPE sites only (30 sites / 10 supertypes). External-
  supertype sites (148, dominated by the 138 Room `Migration` objects)
  keep the current phantom classification: their members implement
  EXTERNAL interfaces, so no corpus code dispatches into them — there is
  no corpus dispatch possibility to model, and modelling them would
  multiply the callable/owner tables by ~150 synthetic owners for zero
  proof value. Fail-closed rationale: an external supertype is exactly
  the case where the engine must not claim corpus reachability; leaving
  the phantom classification in place preserves today's (uncertain but
  honest) behavior for those sites.
- Historical context: the GR-14f-era ledger census counted 283 object
  expressions / 207 member functions corpus-wide — a different-scope
  measure (raw `object` keyword occurrences incl. named objects and test
  sources) recorded in the ledger at that time; the numbers above are the
  current production-corpus anonymous-site census and supersede it for
  this design.
- PrivacyGate: 12 anonymous sites (8 files) plus MANY named implementors
  (41 files mention `: PrivacyGate` including parameter types) — the
  implementor set is large and DI-wired, NOT a small closed world.
- RetentionTarget: 10 sites, ALL in RetentionModule.kt (single file —
  a truly closed world).

## The three changes

### (1) Phantom-call suppression

The call-extraction layer must not emit a call record for the supertype
token of an anonymous-object site. Implementation: where call records are
built, drop any candidate whose `name_start` sits at an `object :` site
identified by `_ANON_OBJECT_RE` with the candidate name equal to the
first supertype token. Fail-closed: ONLY exact `object :` shapes are
suppressed — a named `object Foo : T` (already an owner), a variable
named `object`, or a call whose receiver merely looks like a type are
untouched. Suppression is purely subtractive: no edge that currently
resolves exactly may change (pinned by the full-edge A/B diff, u45/u47
method).

### (2) Member modelling

Anonymous-object member functions become corpus callables:

- Owner FQCN scheme (proposed, deterministic):
  `<file-path>#anon<N>@<line-of-object>.<memberName>` where `N` is the
  1-based ordinal of the anonymous site within the file in source order
  and `<line>` is the 1-based line of the `object :` token. Two runs over
  identical input must byte-match (no dict-ordering, no hash-order
  dependence; sites sorted by offset). Two anonymous objects in one file
  get distinct N; the same object re-parsed gets the same N. EDGE CASE:
  two `object :` tokens on the SAME source line get distinct N (the
  ordinal disambiguates); the `<line>` component alone is not unique.
- Span containment: the member's body span is the override's brace region
  inside the object body; the callable's `file` is the same file; the
  owner table gains a synthetic owner entry of kind `anonymous_object`
  with the supertype FQCN as its single supertype.
- `this` inside the object resolves to the object's own type (the
  supertype FQCN) — so `this.x()` member calls dispatch against the
  supertype's member set like any named implementor.
- CAPTURE-TYPING RULE (explicit): a captured identifier — a free
  identifier that is not a member, member parameter, member local, or
  type — resolves against the ENCLOSING callable's already-modelled scope
  (its parameters and locals). If the captured binding's declared type
  resolves exactly (corpus FQCN or known external) AND the binding is
  effectively-final (`val`), the member is NOT capture-tainted and
  receiver resolution proceeds through the captured type exactly as it
  would in the enclosing scope. If the type is unresolvable OR the
  binding is mutable (`var`), the member is capture-tainted: its
  mutation sites KEEP their current uncertainty classification (no
  exactness is claimed through captures). Verified against the real
  target: RetentionModule.kt's objects capture `appDatabase: AppDatabase`
  and `timeProvider: TimeProvider` — both `val` PARAMETERS of
  `provideRetentionTargets` (L31-32) with exactly-resolvable corpus
  FQCNs (`com.yourname.expensetracker.data.database.AppDatabase`,
  `com.yourname.expensetracker.domain.util.TimeProvider`) — so the
  RetentionModule members qualify as NOT capture-tainted under this rule.
- B2 all-or-nothing ledger rule (GUARDRAIL_ISSUES_LEDGER.md §B2): member
  modelling and phantom suppression MUST land in the same diff.
  Suppressing the phantom call alone removes the async region that
  currently keeps the 10 RetentionModule rows classified; without member
  modelling the rows would fall to `unproven_external_entry` — the
  deletion trap on live privacy-cleanup code. The repo rule that privacy
  cleanup workers must stay runnable (do not gate cleanup on the
  capability it enforces) forbids any remediation that makes the purge
  path look dead.

### (3) DISPATCH ADMISSION — THE DECISION POINT

For interface types whose implementor set is FULLY ENUMERABLE from the
corpus (named implementors + all anonymous objects, no external
implementors possible), the N-way dispatch at the call site could emit
EXACT edges to every implementor member (bounded fan-out) instead of an
uncertain `interface_dispatch`.

Both interfaces are declared in app/src/main without an `internal`
modifier but are app-module types: no external module can add
implementors inside this corpus's scan boundary, and the engine rebuilds
the graph from source on every run — so "enumerable" is re-proven per
run, not assumed once.

- Option A (bounded fan-out — RECOMMENDED, CONDITIONAL ON CAPTURE-TYPING):
  when every corpus implementor is found (named owners + anonymous sites;
  `_scan_anonymous_implementors` counts match the sites actually
  modelled) and the interface has no external implementor possibility,
  emit exact edges to ALL implementor members. Soundness: completeness is
  re-derived on every run; a new implementor (named or anonymous) changes
  the counts and the admission re-derives — it cannot go stale silently.
  Effect: DataRetentionWorker's `target.purge(cutoff)` call (L133)
  becomes 10 exact edges; each anonymous member's mutation site becomes
  dominance-provable against the worker's canonical guard scope
  (DataRetentionWorker IS a registered @HiltWorker running through
  `executionGuard.runGuardedWithContext` — verified, so the canonical
  worker path applies). The 10 rows would move to proven_worker_mediated
  or proven_helper via the worker's guard.
  HONEST CONDITIONALITY: Option A is conditional on capture-typing. With
  the capture-typing rule above, the RetentionModule members'
  `appDatabase`/`timeProvider` captures (both effectively-final `val`
  parameters of provideRetentionTargets with resolvable types — verified)
  resolve and the 10 rows can prove under the worker's canonical guard.
  WITHOUT capture-typing, the members are capture-tainted, their mutation
  sites keep uncertainty, and Option A produces ZERO proven rows —
  Option B's honest-uncertainty outcome applies. The recommendation
  therefore couples the two: approve Option A together with the
  capture-typing rule, or neither.
  Honest risk (independent of capture-typing): exact fan-out to 10
  implementors means the proof must hold for EVERY implementor — one
  unguarded member body fails the whole set back to uncertainty
  (fail-closed, no silent weakening).
- Option B (interface stays uncertain): model members (change 2) but
  leave dispatch uncertain. The 10 rows move to honest unproven states
  (external-entry or interface-dispatch uncertainty) — NOT zero, NOT
  proven. This is the conservative floor if the owner rejects fan-out.
- NOT in scope: dispatch admission for PrivacyGate (12 anonymous sites +
  dozens of named implementors — not a closed world). The 6 phantom rows
  need only changes (1)+(2): the phantom async edge disappears; their
  next deciders must be probed post-change and may need follow-up batches
  (same pattern as u47's next-decider takeover). One recorded competing
  decider the post-change probe must expect: AccountingExportRepository.kt
  L162 `FileWriter(...).use { writer.write(...) }` was already identified
  (u43 F-TRANSPARENT-CHAIN-NOISE) as a competing decider for the two
  event-writer rows — clearing the phantom edge alone may hand the
  decision to that edge.

## Batch cap math

The u43 convention caps a batch at the graphs it coherently touches. This
batch touches: the shared call-extraction layer (1, global), the owner/
callable tables (1, global), and the dispatch admission rule (1, global)
— three engine mechanisms, but they are ONE feature chain (suppression
feeds modelling feeds admission). Callable-graph blast radius (with the
scope boundary above): RetentionModule.kt (1 file, 10 objects),
CloudDashboardBriefingService.kt (1 file), plus member modelling for the
other 8 corpus supertypes' sites (WorkerLeaseRegistry, WorkerLease,
DiagnosticEventWriter, RestoreDatabaseOpener, OperationRunRecorder,
PrivacySettingsRepository, AiSettingsRepository, PrivacyAuditLogger —
8 sites). The 148 external-supertype sites are OUT of scope (no new
callables). This is within the cap as one coherent batch; the PrivacyGate
dispatch admission is explicitly OUT (it would be a separate owner
decision).

## Required fixtures (structural corpus + call_binding)

1. anon-object member modelling positive: an `object : T { override fun
   purge(...) { dao.x() } }` fixture — the member becomes a callable, its
   DAO call edge resolves to the DAO member.
2. phantom suppression negative-pin: `object : T { ... }` produces NO
   call record for `T` and NO lambda region over the object body.
3. two-anon-objects-in-one-file naming determinism: two objects of the
   same supertype in one file get distinct deterministic keys; re-running
   the builder yields identical keys.
4. captured-local fail-closed: a member referencing an enclosing `var`
   (or an unresolvable capture) keeps its mutation site uncertain (no
   exactness through captures).
5. bounded fan-out positive (if Option A): interface with 2 named + 1
   anonymous implementor, dispatch call emits 3 exact edges.
6. fan-out completeness negative: adding an unmodelled anonymous site
   flips the admission back to uncertain (completeness re-derivation).
7. capture-typing positive: a member capturing an outer `val` parameter
   with a resolvable corpus type — the captured receiver resolves and the
   member's DAO edge is exact (no capture taint).
8. all-or-nothing fan-out fallback: 2 named + 1 anonymous implementors
   where ONE member body is unguarded — the whole set falls back to
   uncertainty (no partial exactness).
9. multi-supertype object: `object : A, B { }` — dispatch from either
   interface finds the same member; the naming key is the SITE (not the
   supertype), so both interfaces resolve to the same synthetic owner.
10. line-wrapped supertype list negative pin: `_ANON_OBJECT_RE` captures
    the supertype list only to end-of-line, so `object :\n  T { }`
    (wrapped list) under-counts today — pin the CURRENT behavior so the
    batch cannot silently change it (a fix would be a separate, disclosed
    change).

## Shadow delta expectations

- New edges: member-call edges from each anonymous member callable to its
  body targets — scoped to the 30 corpus-supertype sites (RetentionTarget
  10, PrivacyGate 12, 8 singles). The 148 external-supertype sites gain
  NOTHING (out of scope).
- Removed edges: the phantom `T {` call records for the 30 corpus-
  supertype sites and their async regions. External-supertype phantom
  records stay (unchanged classification).
- If Option A: DataRetentionWorker purge call gains 10 exact edges (the
  uncertain interface_dispatch edge is replaced).
- Gate: zero previously-exact edges may change; zero new uncertain edges
  outside the anon-object mechanism (full-edge A/B, u45/u47 method).

## Hard-stop checks (batch exit criteria)

- 0 counterexamples; 0 previously-proven rows regressing.
- No row may land in `unproven_external_entry` (the B2 deletion trap) —
  if suppression+modelling would produce it, the batch is mis-scoped and
  must stop.
- The 10 RetentionModule rows must end proven (Option A with
  capture-typing) or in an honest unproven state that is NOT
  external_entry (Option B, or Option A without capture-typing).
- The 6 PrivacyGate rows: phantom edge gone; new deciders reported
  verbatim (including the recorded AccountingExportRepository L162
  competing decider); no fabrication of movement.

## OPEN QUESTIONS FOR THE OWNER

(a) Approve Option A CONDITIONAL ON CAPTURE-TYPING (bounded fan-out exact
    edges for fully-enumerable implementor sets, completeness re-derived
    per run, captures typed through effectively-final resolvable
    bindings)? Or Option B (members modelled, dispatch stays uncertain)?
    Note the coupling: Option A without capture-typing yields zero proven
    rows.
(b) Is the deterministic owner naming scheme
    `<file>#anon<N>@<line>.<member>` acceptable (stability across runs,
    uniqueness within a file — including two `object :` tokens on the
    SAME line, disambiguated by N — and no collision with real FQCNs
    since `#` and `@` are illegal in Kotlin FQCNs)?
(c) Any concern with DataRetentionWorker's purge edges becoming exact?
    Verified context for the decision: DataRetentionWorker is a
    registered @HiltWorker running through WorkerExecutionGuard.
    runGuardedWithContext (canonical worker scope), and the purge path is
    privacy-cleanup code that must stay runnable — exact dispatch makes
    the writes PROVABLE under the existing guard, it does not gate or
    weaken anything.
(d) Confirm the PrivacyGate dispatch admission stays OUT of this batch
    (large DI-wired implementor set; not a closed world).
