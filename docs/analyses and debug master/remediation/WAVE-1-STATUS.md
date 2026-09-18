# Wave-1 Remediation Status

> **WAVE 1 COMPLETE (2026-09-15).** All three lanes merged into the
> integration line with verification. Living status note; decisions:
> `RP-00-DECISIONS-RECORDED.md`. Lane branches: `rp-01-wip`, `rp-02-wip`,
> `rp-03-wip` (one worktree each); integration line:
> `atomicity-pr21-enforcement-final`. Nothing pushed.

## Landed

| Lane | Batch | Commit | Contents |
|---|---|---|---|
| rp-01 | 1 (U-001) | `ee6de6b9` | CE propagation through create/update conversion + 5 event writes; `bestEffortEvent` helper; contract entries + 4 passing runtime cancellation tests (5th `@Ignore`d — see RP-21 feed) |
| rp-02 | 1 (U-004) | `402ee2e1` | Alias-proof class-level write-barrier + recurring guards; 24-entry temporary exemption ratchet (owner/issue/expiry enforced); `SourceTextSanitizer` shared helper |
| rp-03 | 3a (P7-003/005) | `0835b5c7` | SAF `.costbackup` destination; storage permissions removed; fsync'd safety copy; envelope pinned by 11 tests |
| rp-01 | 2 (U-002/U-003/U-006) | `83652778` | Guard rewritten (balanced-paren scan, executable-only evidence, sanitized text, FQN-safe sibling check); both allowlist expiries enforced; FHS2 ×4 + coordinator ×3 + startup ×2 CE fixes; stale entry + coordinator exclusion removed; 12 owner-tagged temporary exemptions expiring 2026-12-31 |
| rp-03 | 3b (restore state machine) | `e48aed43` | P7-002 resume (idempotent asset-ledger resume, journal consumed, restart reachable-to-completion); P7-004 `.pre_restore` recovery source; P7-006 staging cleanup on all 9 failure paths; P7-008 journal-honoring exits + dedicated CE catch; P7-009 rename-before-DB-update; deferred fsync sites |
| rp-02 | 2 (writer ownership) | `6f415329` | All 8 writers barrier-adopted; ratchet 28→20 (8 RP-02 exemptions removed); UNASSIGNED triage table (RP-03/10/11/12/14/15/16/19/20); withTransaction mock defect independently corroborated for RP-21 |

## Wave-1 merge endgame (integration line)

| Commit | Contents |
|---|---|
| `44cb16fe` | merge rp-01-wip. Coordinator conflict: kept gr-14f's restructured structure, re-ported all 10 U-001 CE-safety sites onto it |
| `c57d37f1` | merge rp-02-wip. gr-14f had ALREADY mediated RetentionModule / NotificationIntakeCoordinator / RecurringOccurrenceMaterializer via `runWrite(DatabaseAccessOperation)` — HEAD versions kept (supersede the lane's check-only work); genuine gap ported: barrier on `RoomRecurringLifecycleEventWriter.writeCritical` (gr-14f removed the caller-less `writeDiagnostic`) |
| `367213d7` | merge rp-03-wip (clean auto-merge) |
| `16093145` | fix E2E constructor arg duplicated by auto-merge |
| `c0f2434b` | align writer tests to the gr-14f mediated contract (real-barrier harness; intake: `capture`→typed `Dropped`, `captureForRetry`→silent skip); AppStartupCoordinator raw runCatching → `CancellationSafe.runCatchingCancellable` |
| `8c11ecb3` | acceptance registry regenerated 20→18: `SourceLinkWriterImpl.linkTarget` + `RoomRecurringLifecycleEventWriter.writeCritical` became PROVEN via barrier adoption (proven_helper 344→346); GR-15 suite 15/15 |

## Merged-tree verification

- Battery (16 test families): **206 PASSED**; 8 failures all proven
  pre-existing on pristine lane HEADs (5 DatabaseBackupRepositoryImplTest
  via rp-03 stash-baseline, 3 BackupRestoreViewModelTest via 3a-era logs).
- Cancellation guard suite green on the merged tree (20/20), now directly
  enforcing the coordinator (exclusion removed in the same merge).
- Mediation gate: applied **18**, mismatches **0**; board 346+14+1 proven
  + 18 owner_accepted, 0 unproven, 0 counterexamples.

## Carried debts (post-wave-1)

1. **GR-engine debt (holds mediation gate at exit 2, fail-closed):**
   `DatabaseBackupRepositoryImpl.restoreReceiptAssets` ScannedReceiptDao.update
   emits `GR13_NO_D4_OBSERVATION` after the RP-03 P7-009 reorder inside
   RestoreInternalWriteScope; scan diagnostic `DB_DAO_SCOPE_UNRESOLVED` is new.
   NOT owner-acceptable (unsupported ≠ unproven). Owner: parked GR-engine
   typing batch. Evidence: `wave1-merged-board2.json`, registry header note.
2. **DbGuardPolicyFixtureTest drift (pre-existing since the gr-14f merge):**
   fixture expects old-format `daos: [privacyAuditDao]` pins; gr-14f rewrote
   the policy YAML to the new `daoAccessor:` format. 5 tests red on the
   integration line before wave 1. Owner: GR program close-out.
3. **RP-21 feed:** withTransaction hang family (root cause confirmed twice —
   relaxed-mock `withTransaction` stubbing cannot intercept the static Room
   extension; candidate fix `mockkStatic("androidx.room.RoomDatabaseKt")`;
   logs `rp01-baseline-pristine3.log`, `rp01-isolation.log`,
   `rp02-batch2-*`); DirectEvent expired allowlist (8 entries, 2026-08-15);
   cancellation raw-runCatching allowlist expires **2026-10-01**; rp-01/rp-02
   temporary exemptions expire 2026-10-31 / 2026-12-31.

## Wave 2 (next)

RP-05→06→07→08→09 money chain ∥ RP-10 ∥ RP-12→13 (RP-13 carries the one
Room-schema bump of the wave). Cut lanes from the current integration tip.

### Wave 2 progress — 2026-09-15

Integration line (`atomicity-pr21-enforcement-final`): `6fe6294f` guard
deterministic U-003 expiry clock + negative controls (22/22); `0f3fe9ac`
skills/agent-config guard hardening; `0c563b42` this status doc.

Lane commits (lanes based at `8c11ecb3`, serial merges + registry
regeneration still owed at endgame):

- `rp-05-wip`: `bb4bd9f1` shared-expense round-trip test (P5-004);
  `6b95ce7a` batch 2 — P5-005 six-month trend window, P5-003 completed-
  history synthesis baseline (`historicalMonthAggregates`, MTD never the
  baseline, null keeps confidence penalty), P5-012 uncategorized pseudo-
  category with sum-complete percentages. Validated: 111 PASSED incl. 7 new
  tests; 7 failures pre-existing at lane base (4 × P5AnalyticsFixesTest
  statically proven; 3 × ForecastInputAssemblerTest statically proven
  independent of the diff).
- `rp-10-wip`: `09680da5` batch 10a — canonical deferred intake fingerprint,
  atomic legacy transition (`LEGACY_DEFERRED_SUPERSEDED`) in one
  DomainTransactionRunner transaction inside the barrier `runWrite`,
  DO_NOT_STORE fail-closed, controlled deferred reason codes. 23/23 + guards.
- `rp-12-wip`: `7c825df6` batch 12a — `ReceiptProcessOutcome` typed contract,
  five duplicate exits with no side-effect batch, in-transaction planning +
  single post-commit dispatch, `resolveRawStorageMode` fail-closed, P3-009
  unmatched-claim CAS, batch `duplicateCount` surfaced; two stale-protective
  RP-12 CE allowlist entries removed (guard green without them). New tests
  11/11 + guard 20/20; the 6 remaining coordinator failures are the
  baseline-proven pre-existing email-path set, owned by the 12b follow-up.
  Note: the new `ReviewViewModelBatchDuplicateMessageTest` initially did not
  compile (missing `override setup()`/`super.setup()`; constructor param is
  `repository`) — fixed in the same commit.
  `b25dc3d8` batch 12b (P3-004 only) — privacy-resurrection fix: eligibility
  reads moved inside the link/unlink transactions; full-row `@Update` calls in
  link/unlink/suggestion paths replaced with five column-scoped DAO
  operations (match status, link ids, confidence, timestamps only); events
  built from the fresh in-transaction row. Plan-required interleaving DB test
  added (real in-memory Room + real `RoomDomainTransactionRunner`): purge +
  concurrent categorization commit before link/unlink/suggestion; purged
  fields stay purged. 3/3 new tests pass; 10 failures in the battery all
  proven pre-existing — the 6 email-path ones plus ReceiptMatchingViewModel
  Test x3 and ReceiptMatchingE2ETest x1, proven by running both classes at
  the 12a tip `7c825df6` in a scratch detached worktree with identical
  results (relaxed `transactionRunner` stub in the E2E harness; StateFlow
  conflation order-dependence in the ViewModel tests — carried
  test-robustness debt for the RP-21 feed). 12b P3-007 core landed in
  `f305103e`: single `ReceiptStructuredDataPolicy` transformer on both insert
  paths (STORE_RAW passthrough; STORE_REDACTED = typed RedactedReceiptItem
  JSON {schema REDACTED_V1: quantity/unitPrice/totalPrice/currency} replacing
  the "[REDACTED_ITEMS]" marker; restricted modes persist nothing; fail
  closed), and mode-gated item-dependent side effects — categorization and
  price protection skip with the new controlled
  STRUCTURED_RECEIPT_DATA_UNAVAILABLE reason under every mode except
  STORE_RAW (also the post-restart behavior). ReceiptScanViewModel never
  parses the redacted wrapper. Validated: 9/9 new tests (7 policy + 2
  planner gating) + column-scope/12a/guard suites green; only the 6
  baseline email-path failures remain. 12b conditional-complete (`e5711069`
  plan status); conditional remainder: ephemeral item pass-through into
  categorization (needs CategorizeReceiptItemsUseCase API change).
  12c P3-008 landed in `0a96e99a` (plan status `37cd5f88`): new
  `AssetCleanupCoordinator` — reference-counted
  (ScannedReceiptDao.countReferencesToImagePath) and attempt-claimed cleanup
  under a write transaction — now owns every uncommitted-asset exit: both
  exact-hash duplicate deletes, the post-OCR duplicate delete, the insert-
  rollback path (DuplicateReceiptInsertException now carries
  attemptedAssetPath), and caller cancellation (NonCancellable best-effort
  before rethrow). Typed OcrRecognitionFailedException carries the attempt's
  owned path so ReceiptRepository never double-saves an image. AssetCleanup
  CoordinatorTest 5/5 with real Room + real files; all coordinator asset pins
  held. A newly visible ReceiptRepositoryStatementDuplicateTest failure
  (bank-statement count assertion, previously outside the battery filters)
  cannot reach this diff and is recorded for the RP-21 feed. RP-12 core
  sequence 12a-12b-12c complete.

- `rp-10-wip`: `442411b8` batch 10b (plan status `c39dddb1`) — awaited
  WorkManager enqueue inside the NonCancellable region; ONE atomic,
  idempotent markEnqueueFailed transition (attempts increment once,
  FAILED_FINAL at maxAttempts else FAILED_RETRYABLE on the shared
  NotificationIntakeRetryPolicy ladder, controlled code, locks cleared,
  status-conditional); EnqueueFailed result + controlled diagnostics; the
  recovery scheduler's promised app-start hook is now concrete via
  AppStartupCoordinator; cancellation accounting (one bounded terminal
  CAPTURE_CANCELLED diagnostic from NonCancellable, rethrow — never a
  retry/success); unused SHUTDOWN_DRAIN_TIMEOUT_MS removed. Validated:
  RetryPolicy 2/2, Coordinator 18/18, RestoreBarrier 5/5, worker suites green.
- `rp-10-wip`: batch 10c (P1-005/006/008 hygiene) landed (pending human
  validation) on branch `rp-10c-wip` — P1-005 (4 MessagingStyle keys added to
  SENSITIVE_EXTRAS_KEYS + end-to-end buildExtrasJson filter test), P1-006
  (deduper switched to MonotonicTimeProvider.nowNanos() with internal nanos
  storage and ms→ns conversion; new NotificationCaptureDeduperTest), P1-008
  (presence-bit + 4-byte length-prefixed framing with legacy NUL-split decrypt
  fallback for in-flight old-framed rows; new NotificationTransientPayload
  CryptoTest incl. embedded-NUL, null/empty distinction, truncated-frame
  negative, legacy fallback conflation pin). Strict static review GREEN
  (overflow-proof bounds check in parseFrame included). First live execution
  2026-09-17 (validation-runner; repo relocation had invalidated stale
  OneDrive-era build caches — app/build regenerated, compile PASS
  vr-20260917-092331-2f2b5303): DeduperTest initially 6/6 FAIL (the 419e38c0
  rewrite inverted tryStart polarity; file restored to b0141f43 semantics,
  9 tests) and CleanupTest initially 8/9 (latent suspend-reflection bug since
  f4aac79f: processNotification reflects 6 Kotlin params + Continuation;
  test now pins 7 reflected params). Final: DeduperTest 9/9, CryptoTest 8/8,
  CleanupTest 9/9 all PASS (vr-20260917-102030 / -103742 / -110151); strict
  review of the test-only fix PASS. RP-10c validation debt CLOSED.
  Next planned item: the RP-06→07→08→09 money chain scout (scout
  completed 2026-09-17: all 8 RP-06 source contracts verified, no blocking
  discrepancies; slice planning next).

### Hang-family findings (RP-21 feed, thread-dump evidence, 2026-09-15/16)

The 10b work surfaced three concrete members of the known MockK + suspend
hang family: (1) a RELAXED mock's suspend extension (Operation.await) never
resumes; (2) creating a mock inside a coEvery answer deadlocks the recorder
(JvmAutoHinter runBlocking); (3) intercepting a REAL suspend extension
(OperationKt.await) deadlocks MockK's auto-hint recording — the working
pattern is a hand-written real Operation fake (CompletedOperation, private
SUCCESS ctor via reflection). Additionally, DeferredPolicyTest NEVER
COMPILED at 10a (proven via scratch worktree at 09680da5) — its 5 tests are
unvalidated carryover and its first run hangs in the same family;
EnqueueFailureTest (new, real Room) hangs identically and is @Ignore'd with
evidence. Both belong to the RP-21 withTransaction/mock-hang workstream.
Next: RP-10c hygiene, then the RP-06 chain.

Next in wave 2: RP-12b P3-007 (structured-data mode matrix), then 12c
(asset cleanup); RP-10b; then the RP-06→07→08→09 chain; RP-13 schema bump
last. RP-04/RP-11 wave-1 lane tails still owed (RP-04 exemption expires
2026-10-31). Inert worktree dirs pending user-approved force-clear:
`build/worktrees/rp-01|02|03` leftovers and `build/worktrees/scratch-12b-baseline`
(unregistered, checked out at committed tips, only Gradle output inside —
Windows "Filename too long" blocks plain removal).

---

## 2026-09-17 — RP-10c landed on `bug-fixes`; battery findings (RP-21 feed)

Mainline note: `bug-fixes` is now the integration line — `atomicity-pr21-enforcement-final`
is fully absorbed (0/23) and unmoved. Landed since the last entry: PR #12
(`rp-10c-wip`, the serialized validation-runner infrastructure, `69c5bf19` WIP),
`gr-00-local` merge `cbf1eeae` (GR-14e counterexamples 11→1, GR-14f dispatch
5→1 seed 408→406, GR-15 step 2 v3 proof-contract loader), and RP-10c
notification hygiene `73a50936` (the stranded `rp-10-wip` 10c pair — the
similarly named PR #12 branch does NOT contain it).

Validation (`validation-runner`, tree `73a50936`): Deduper PASS, transient
crypto PASS, CleanupTest 6/7 with the single failure proven pre-existing at
`cbf1eeae` (stale P1-009 reflection guard: asserts JVM parameterCount 6 for a
`suspend` method whose compiled signature carries `Continuation`, so 7; see
RP-10 doc). GR-15 v3 loader tests: 20/20 via pytest (standalone — not yet
wired into the static-guard suite; wiring is GR-15 step 3).

Full-battery finding (`vr-20260917-201052-d75db0e2`, `*Notification*` filter,
TIMEOUT by no-output): 16 real failures in `data/repository`
`NotificationProcessingPipeline{Atomicity(6),Reliability(8),SourceLink(2)}Test`
— suites untouched since the pipeline-11 era and never re-run after 10a/10b
changed the intake semantics they pin; all three use MockK/runTest. The run
then hung with no output after `NotificationProcessingPipelineStressTest`
(all SKIPPED); prime suspect is `NotificationCaptureServiceDeferredPolicyTest`
— the documented first-run MockK+suspend hang — which is still NOT in
`known-hanging-tests.json` and NOT `@Ignore`d (ledger additions need human
approval). These belong to the RP-21 workstream together with the stale P1-009
guard; none of it is 10c surface (10c touched only `domain/notification/capture`).

Next: RP-21 (hang family + pipeline test debt) before trusting the pipeline
suites; then the RP-06→07→08→09 chain; RP-13 schema bump last. RP-04/RP-11
wave-1 tails still owed (RP-04 exemption expires 2026-10-31). Cancellation
allowlist expires 2026-10-01.

---

## 2026-09-18 — rp-06-wip refreshed onto reconciled mainline; P5-006 ported

`bug-fixes` reconciled with a parallel session's independent RP-10c + RP-06 6a
landings (merge `a0c4ae0d`; origin implementations won all overlaps — see
`RP-06-pace-synthesis.md`). The lane's superseded 6a variant is archived at
`rp-06-6a-superseded`. Ported onto the refreshed lane: P5-006
(one-reload-per-currency-change), the assembler/calculator parity test, the
@Ignore'd P5-006 spec test, and re-verified REVAL-8 zero-caller evidence.
Remaining: 6b synthesis suspend migration, then 6c.
