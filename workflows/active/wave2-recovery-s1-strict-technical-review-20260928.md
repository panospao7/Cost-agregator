# Wave-2 recovery + S1 strict technical review — 2026-09-28

**VERDICT: FAIL — S1 is not ready to unlock S2. Wave 2 remains open.**

This is a single-reviewer source-and-evidence review of the recovery-contract repair, its fake repair, S1 generic-PDF partiality and affected consumers. No subagents were used. No production/test/configuration/policy/baseline/allowlist/history edits or validation executions were made. This new report is the only intended write.

This reviewer also authored work in the preceding implementation conversation. This is therefore an adversarial author-side technical review, not an independent approval or guardian sign-off. Newly present campaign journal entries record separate independent PASS assertions; section 4 adjudicates them against source. This report does not replace the accumulated seven-cluster review or complete original-finding/rider closure matrix.

## 1. Scope, provenance and exact snapshot

- Repository: C:/Users/panos/Desktop/cost agregator/ExpenseTracker.
- Branch: `bug-fixes`; HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`.
- Index empty; no staged changes.
- Pre-report inventory: 132 tracked dirty + 83 untracked = 215 paths. These are not all unrelated changes, nor all newly introduced by these repair batches. All are preserved.
- Intake: 2026-09-28T18:13:47.7064205+03:00, fingerprint `1ebb3cfd6cb3f28a6a1621de9b8977a7197109ef9b784eb93e1ddfcc5a5d65f9`.
- Final pre-report capture: 2026-09-28T18:42:06.1198220+03:00, fingerprint `af007ef39060967557d64dc7906370d09546ff621a2ee998f282d87a64a7fc03`.
- Quiescence: 2026-09-28T18:42:05.2419031+03:00; no global lock, active validation run or matching validation process detected. No validation was launched.
- JOURNAL.md changed during the read-only review. All 29 reviewed source/test/resource hashes remained unchanged. The final snapshot incorporates those journal additions; this was not one frozen whole-tree fingerprint throughout the review interval.
- Relative to the S1 validated snapshot `6119a5cf599a61b5e392c499bcd442035a340aaa47a4893ceead95e16e0c0fdb`, the only existing-file change is the campaign JOURNAL.md and the only new pre-report path is workflows/active/wave2-s1-validation-evidence-audit-20260928.md. No path was removed.
- All 15 recovery manifest files match. The fake repair hash is `283ad01eaeb340fa3834328b88c405a397f2bef9f85639890235801f38a6c225`, unchanged from S1 validation. All 13 S1 source/test/resource files and its plan match. Appendices identify the scope and complete dirty byte inventory. Adding this report necessarily creates a subsequent fingerprint.

Read-only ancestry checks confirmed CL-27 merge `0ea0316a1f7d5fac4587857f3645340e01592ede`, CL-05 merge `03f197d162d81e5a8821e2d82b116d71b04b9e99` and audit pin `37601232b9778170c57a656a245b199ab6d7d965` are ancestors of HEAD. Their committed work is not newly introduced by the remaining batch. Shared-file HEAD-to-worktree diffs also contain earlier CL-09/CL-15/privacy hunks; whole-file hashes establish bytes, not hunk authorship.

## 2. Authority, method and limits

Read AGENTS.md, local scope/review-strict/fix-ci/handoff guidance and the strict/architecture/privacy/Room reviewer guidance without delegation. Used CODEBASE_SEGMENTS.md, CODEBASE_INVENTORY.md, LEGAL_PATHS.md and ENGINE_INTERACTION_MAP.md to locate owners before reading exact implementations, callers and assertions.

Primary requirements:
- docs/analyses and debug master/campaign CA-2026-09-21/cell-P-03-audit.md:67-75, CA-P-03-003. Its line 72 explicitly includes the camera/PDF route. The narrower bank-only coder specification cannot replace this original requirement.
- docs/analyses and debug master/remediation/RP-17-bank-sync.md:77-83: stale operation/statement recovery, cancellation and actual mutation accounting.
- docs/analyses and debug master/remediation/RP-03-backup-restore.md:24-96: pointer protection and the larger asset identity/integrity/durable replay contract.
- docs/architecture/LEGAL_PATHS.md:123-207, 406-446, 471-521, 544-591 and 785-811.
- workflows/active/wave2-recovery-contract-repair-20260928-handoff.md; wave2-recovery-fake-dao-repair-20260928-handoff.md; wave2-generic-pdf-partiality-20260928-handoff.md; wave2-closure-series-20260928-plan.md:15-22; wave2-s2-asset-recovery-readiness-20260928.md.
- FINAL_CI_GUARD_ACCEPTANCE_GATE.md: FG-03, FG-06, FG-07 and FG-23. No skipped execution is credited as behavioral proof; no suppression, policy broadening or weaker guard is proposed merely to obtain green.

Path shorthand below: M/ = app/src/main/java/com/yourname/expensetracker/; T/ = app/src/test/java/com/yourname/expensetracker/. All line anchors refer to the current hashed files.

Documentation discrepancy: CODEBASE_INVENTORY.md describes database v148; M/data/database/AppDatabase.kt:43 and :118 use 149, and DatabaseSchemaPolicy.kt:14 delegates to that constant. This is existing documentation drift, not a schema change introduced by these batches. ScannedReceipt.kt:86 stores processingStatus as a String; OCR_PARTIAL does not itself change the schema.

The review covered the 29 batch source/test/resource files and their affected startup, DI, DAO, transaction, event, privacy and UI consumers. It did not perform a fresh diff-level review of all other dirty paths or all committed money/currency code. The complete dirty inventory is a preservation record, not an approval of every path. No validation was executed.

## 3. Concrete findings

### S1-REV-01 — MAJOR: partial OCR plus parse failure loses partiality on duplicate retry

Disposition: NOT_FIXED for this acceptance case; CA-P-03-003 / S1 remains PARTIAL. This is a deterministic source-path defect, not a runtime reproduction claimed by this review.

Primary location: app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:253-263 and :355-362.

Proof:
1. M/data/repository/ReceiptRepository.kt:247-276 returns a PARSE_FAILED draft with page counts and failedPages when surviving OCR text cannot be parsed.
2. M/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:463-468 correctly preserves PARSE_FAILED ahead of OCR_PARTIAL. Do not remove that precedence to disguise the defect.
3. The first save writes partial=true, PDF_PARTIAL and controlled counts through the transactional event writer at :621-646. Its first outcome carries coverage at :715-721, so isPartial=true.
4. The same bytes on retry take ReceiptRepository.kt:199-206, returning the existing PARSE_FAILED row without coverage. The coordinator duplicate exit at :355-362 supplies no coverage either.
5. ReceiptProcessOutcome.isPartial at :259-262 recognizes only in-memory coverage or stored OCR_PARTIAL status. Both are false for this existing PARSE_FAILED row. Its durable partial event is not consulted. Other duplicate exits (:447-450 and :737-740, plus text/semantic duplicate paths) also default coverage.
6. M/ui/screens/receiptscan/ReceiptScanViewModel.kt:304-322 and :436-442 consumes outcome.isPartial. An unlinked, linkable existing receipt can continue into REVIEW with hasPartialOcr=false even though missing pages were never recovered. Linked duplicates also receive the false flag. This is distinct from the separate cosmetic absence of a partial banner on the DUPLICATE composable.

Why green tests miss it: T/domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt:285-294 covers partial + parse failure on the first save. Lines 310-320 cover a duplicate of an ordinary OCR_PARTIAL row. Neither combines parse failure and duplicate retry. The ViewModel partial-parse test supplies a prebuilt outcome with coverage and does not reconstruct it from the persisted PARSE_FAILED row.

Required repair:
- Preserve or recover typed, bounded partiality for the existing receipt through its owning lifecycle/read contract. Do not rerun OCR, insert another event or execute duplicate side effects merely to reconstruct the fact.
- Preserve PARSE_FAILED precedence, idempotency and legal owners. Do not introduce a UI-to-DAO bypass or quietly add a schema column without its migration/design review.
- Add a real repository -> coordinator -> Room regression combining partial/capped pages, a parser exception and exact-hash rescan. Assert true partiality on both outcomes, unchanged PARSE_FAILED, same ID, one saved row/event, one OCR invocation and no duplicate side effects.
- Cover other duplicate-return paths or their shared reconstruction contract. Partiality must belong to the returned existing receipt, not an unrelated new attempt.
- Add the unlinked-duplicate ViewModel warning regression, retaining complete-PDF/image controls.

### S1-REV-02 — MAJOR evidence gap: ignored stress suite has obsolete fixtures

Disposition: PARTIAL / execution unverified. Provenance: pre-existing test debt, not an S1 production regression. The S1 packet included the filter as a gate without disclosing its class-level Ignore.

- T/ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt:57 ignores the whole class. Preserved XML shows 19 skipped cases, zero executed bodies. The authentic runner PASS does not establish those bodies passed.
- Its fixture at :100-119 injects anonymous unstubbed strict mocks for the lifecycle coordinator, link service, parser and other required collaborators.
- The fallback test at :157-181 stubs old ReceiptRepository.processReceipt/saveManualReceiptRecord calls. Production ReceiptScanViewModel.kt:295-304 instead calls ReceiptLifecycleCoordinator.processReceiptInput.
- Assertions at :228 and :236 expect an old sentinel raw OCR string and old wording. Production :337-370 intentionally uses empty raw text and a bounded OCR_FAILED message. Retain the meaningful reset/privacy contract, not an obsolete representation.
- The quick-save test at :706-720 stubs ReceiptRepository.createExpenseFromReceipt. Production saveExpenseInternal at :1198/:1257 uses ReceiptLinkService and ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt. The DONE/artifact assertions at :770-780 lack a correctly wired success fixture.

The Ignore comment is not a proven current hang diagnosis. No runtime hang reproduction or root-cause claim is made. Simply deleting Ignore will not repair the source-visible fixture mismatches.

Required repair: retain all 19 cases and their meaningful assertions; use named, explicitly stubbed collaborators on the actual lifecycle path; provide deterministic scheduling and appropriate ViewModel/scope cleanup; diagnose remaining hangs from serialized runner evidence. Do not delete the class, drop its filter, rely on relaxed-mock fallbacks, or weaken assertions. After a bounded reviewed repair, require real execution with zero skips and preserved XML.

## 4. Adjudication of the newly recorded external PASS reviews

JOURNAL.md acquired WAVE2-S1-STRICT-REVIEW-PASS and WAVE2-RECOVERY-STRICT-REVIEW-PASS entries during this read-only review. Their complete entries were read as assertions, not substituted for source inspection. Their recorded independence was not independently established here; this session spawned no reviewers.

- The recovery PASS is consistent with the bounded pointer-CAS/snapshot and stale-run contracts inspected here. It explicitly excludes the full RP-03 crash contract. No additional new defect was verified in those bounded mutation contracts.
- The S1 PASS correctly describes ordinary partial-row duplicates, status precedence, legal writes, controlled metadata and many tested cases. It does not address the combined PARSE_FAILED + duplicate counterexample. Its blanket requirements-met conclusion cannot close S1 until S1-REV-01 is repaired or technically refuted through that exact execution path.
- The 19 skipped stress cases remain a separate agreed gate even though Ignore predates S1. Provenance does not turn skipped bodies into passing evidence.
- Cleanup of a published file after CAS conflict must not become unconditional deletion. S2 cleanup must prove ownership and protect files referenced by current rows. The present implementation retains the final on conflict; this review does not falsely describe it as deleting a newer user's file.
- The two bounded PASS entries do not provide accumulated seven-cluster or guardian approval. This author-side review cannot independently approve itself either.

No campaign record was overwritten; this disagreement is recorded additively.

## 5. Bounded requirements and execution-path traceability

The dispositions apply to the stated sub-contracts, not blanket completion of RP-03, RP-17 or all seven Wave-2 clusters.

| Requirement/source | Owner and actual legal path | Implementation, tests and meaningful assertions | Execution evidence | Disposition and residual |
|---|---|---|---|---|
| W2-MUT-01; RP-17 stale bank operation runs | Startup -> BankSyncStartupRecovery -> required StaleBankOperationRunRecovery binding -> RoomOperationRunRecorder -> OperationRunDao; segments 12/14 and diagnostics owner | M/domain/bank/BankSyncStartupRecovery.kt:1-88; M/domain/diagnostics/OperationRunRecorder.kt:64-111; M/di/DiagnosticsModule.kt. Admission/checkpoints, finally-close lease, conditional terminal updates and actual row sums. Tests cover sealed admission without DAO access, zero rows/races, failure/cancellation and lease release. | R1, R2, R9 at d736eca4... | FIXED_AND_VERIFIED for this narrow mutation contract; policy reconciliation remains pending, not a complete bank-sync approval. |
| W2-MUT-02; RP-17 stale statement runs | Admitted startup recovery -> BankStatementImportRunDao | M/data/database/dao/BankStatementImportRunDao.kt:1-68. ID + RUNNING + strict startedAt cutoff, Int affected rows. Real-Room BankRecoveryDaoContractTest covers cutoff, concurrent terminal state, deletion/repetition and row counts. | R1/R2 at d736eca4... | FIXED_AND_VERIFIED for conditional recovery. Ordinary live-finalization semantics were not silently redefined. |
| W2-MUT-03; RP-03 pointer snapshot and CAS | Primary restore and startup replay -> fresh DB + RestoreInternalWriteScope -> ScannedReceiptDao.updateImagePathIfUnchanged | DAO :26-36; RestoreJournal.kt:35-200; AppStartupCoordinator.kt:630-678; DatabaseBackupRepositoryImpl.kt:1541-1596, :1713-1755. Captured-null versus legacy absence, wrong-type rejection, durable capture before mutation, null-safe column-only CAS and affected-row/read-back checks. Real-Room pointer tests, real journal round trips and producer tests exercise those properties. | R3-R8 at d736eca4... | FIXED_AND_VERIFIED for the bounded pointer/snapshot contract. Full integrity/crash replay is PARTIAL. |
| W2-MUT-03 interface-growth fake repair | FakeScannedReceiptDao implementation | T/domain/consistency/LegacyDataConsistencyCheckerTest.kt:35 returns 0 for its unused write, consistent with sibling no-op writes without claiming a mutation. | Test sources compiled in R1; R10 has 5 passing results and retained last-run XML | FIXED_AND_VERIFIED for the identified compile break. |
| CA-P-03-003 / S1 generic-PDF partiality | ReceiptOcrService -> draft-only ReceiptRepository -> ReceiptLifecycleCoordinator/Room event transaction -> scan/batch UI; segment 4 | Typed coverage, failed-page propagation on parsed/parse-failed results, controlled transactional metadata, review warning and partial-count subset. Real pipeline assertions cover row/event consistency, rollback, cancellation, capped/complete/image controls and ordinary duplicates. | S1-S5, S7-S10; all 18 new methods matched to passing XML at 6119a5cf... | PARTIAL overall: tested first-save paths work, but S1-REV-01 loses partiality on a parse-failed duplicate; device PDF evidence remains missing. |
| S1 duplicate retains independent partiality | Existing-row duplicate exits -> ReceiptProcessOutcome -> scan UI | Coordinator :253-263, :355-362, :447-450, :737-740; absent combined test at T/.../ReceiptPartialOcrPipelineTest.kt:285-320 | Source-proven counterexample; no new execution claimed | NOT_FIXED for S1-REV-01. |
| S1 scheduled stress evidence | ReceiptScanViewModel -> actual lifecycle contracts, not old repository writes | StressTest :57, :100-119, :157-181, :706-720; ignored class and stale fixtures | S6 runner PASS; XML 0 passed / 19 skipped | PARTIAL, not behavioral verification; repair and execute without reducing coverage. |
| RP-03 full asset integrity/crash contract | Bundle -> durable journal -> restore/startup fresh DB -> pointer owner | Six existing S2 seams in section 8 | Prior tests prove their assertions, not the missing crash/hash matrix | PARTIAL; no waiver inferred from the bounded recovery PASS. |

Architecture/privacy/Room checks:
- Recovery DI binds the required interface to RoomOperationRunRecorder, not a default no-op. BankSyncStartupRecoveryTest uses the actual writer implementation with mocked DAOs; BankRecoveryDaoContractTest separately supplies real-Room SQL evidence. Those are not interchangeable claims.
- Restore mutations are column-scoped and use fresh DB/internal ownership with affected-row and read-back checks. The new nullable snapshot remains private journal state: toDiagnosticsJson strips it, and RestoreJournalImporter imports controlled event fields rather than copying the task snapshot wholesale.
- S1 retains draft-only repository output and coordinator-owned save/event transactions. The real pipeline event-failure test observes rollback, not a mocked successful transaction.
- New event fields are controlled booleans/counts/reason constants; arbitrary legacy metadata is not forwarded. This checks the changed boundary, not every historical logger in these large files.
- Status storage remains String-based. No entity/schema/migration change is introduced by these batches; no ordinal-based break was found in affected consumers and the side-effect planner.
- Cancellation and post-commit behavior were followed through the changed seams. No new cancellation swallow was found in this bounded delta; broader existing cancellation debt remains outside that conclusion.


## 6. Saved execution evidence checked

**No validation was executed by this reviewer.** These are preserved human-run results, not new runs.

### 6.1 Recovery

All 11 result.json records are terminal PASS/exit 0, with completion markers and equal start/end fingerprints. Shared tested snapshot: `d736eca4da0676c108cc987c921fa57d16ad3609accd9e09a93243c440e067d5`.

The archived stdout retains 187 PASSED case-result lines across the ten filters, with no FAILED/SKIPPED case lines found. This is stronger than terminal-PASS-only evidence, but is stdout evidence, not an invented aggregate JUnit archive. Earlier XML was overwritten by subsequent Gradle runs; the last LegacyDataConsistencyCheckerTest XML remains available.

| Ref | Full run ID | Profile/filter | Result | Case evidence |
|---|---|---|---|---|
| R0 | `vr-20260928-112651-10c80c7d` | compile | PASS | Production compile UP-TO-DATE |
| R1 | `vr-20260928-112716-9749fc34` | `*BankSyncStartupRecoveryTest` | PASS | 10 PASSED lines / 0 SKIPPED lines |
| R2 | `vr-20260928-113324-0d8fba75` | `*BankRecoveryDaoContractTest` | PASS | 5 PASSED lines / 0 SKIPPED lines |
| R3 | `vr-20260928-113442-6b2de1e5` | `*RestoreImagePathDaoContractTest` | PASS | 6 PASSED lines / 0 SKIPPED lines |
| R4 | `vr-20260928-113551-a77687f8` | `*RestoreAssetSnapshotJournalTest` | PASS | 4 PASSED lines / 0 SKIPPED lines |
| R5 | `vr-20260928-113657-884d1b11` | `*AppStartupCoordinatorRecoveryTest` | PASS | 38 PASSED lines / 0 SKIPPED lines |
| R6 | `vr-20260928-113831-45d82002` | `*DatabaseBackupRepositoryImplTest` | PASS | 65 PASSED lines / 0 SKIPPED lines |
| R7 | `vr-20260928-114038-6d2261d5` | `*RestoreJournalDurabilityTest` | PASS | 24 PASSED lines / 0 SKIPPED lines |
| R8 | `vr-20260928-114154-e81fd8f5` | `*AssetRestoreAtomicityTest` | PASS | 6 PASSED lines / 0 SKIPPED lines |
| R9 | `vr-20260928-114257-66926702` | `*WorkerLeaseRegistryTest` | PASS | 24 PASSED lines / 0 SKIPPED lines |
| R10 | `vr-20260928-114400-31054a39` | `*LegacyDataConsistencyCheckerTest` | PASS | 5 PASSED lines / 0 SKIPPED lines |

The saved underlying test command is gradlew.bat :app:testDebugUnitTest --tests <filter> --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon, launched through the repository runner. R0 uses :app:compileDebugKotlin with the same flags and an UP-TO-DATE production task. This records provenance, not permission to invoke Gradle directly.

### 6.2 S1

All 11 runner results are authentic terminal PASS/exit 0, with completion markers and equal start/end fingerprints. Shared tested snapshot: `6119a5cf599a61b5e392c499bcd442035a340aaa47a4893ceead95e16e0c0fdb`.

Preserved XML shows **123 listed cases = 104 passed + 19 skipped; zero failures/errors**. All 18 newly authored methods were individually matched to non-skipped passing cases in workflows/active/wave2-s1-validation-evidence-audit-20260928.md. The entire stress class accounts for the skips. Zero failures and authentic runner PASS do not turn skipped bodies into executed tests.

| Ref | Full run ID | Profile/filter | Runner result | XML evidence |
|---|---|---|---|---|
| S0 | `vr-20260928-134207-a22a69fa` | compile | PASS | N/A |
| S1 | `vr-20260928-134831-ae84a2be` | `*ReceiptPartialOcrPipelineTest` | PASS | 10 passed / 0 skipped |
| S2 | `vr-20260928-135830-62cf74f0` | `*OcrResultPartialTest` | PASS | 4 passed / 0 skipped |
| S3 | `vr-20260928-140001-b36c8fec` | `*ReceiptLifecycleCoordinatorTest` | PASS | 35 passed / 0 skipped |
| S4 | `vr-20260928-140135-a7d5c64d` | `*ReceiptRepositoryBatchDuplicateTest` | PASS | 5 passed / 0 skipped |
| S5 | `vr-20260928-140306-10d0290d` | `*ReceiptScanViewModelTest` | PASS | 17 passed / 0 skipped |
| S6 | `vr-20260928-140431-3574c7fe` | `*ReceiptScanViewModelStressTest` | PASS | 0 passed / 19 skipped |
| S7 | `vr-20260928-140548-0b71c722` | `*ReviewViewModelBatchDuplicateMessageTest` | PASS | 7 passed / 0 skipped |
| S8 | `vr-20260928-140716-14a4aa86` | `*ReceiptSideEffectPlanner*` | PASS | 13 passed / 0 skipped |
| S9 | `vr-20260928-140838-4c48ca89` | `*BankStatementCompletionStatusTest` | PASS | 5 passed / 0 skipped |
| S10 | `vr-20260928-140955-98950852` | `*ReceiptOcrRetryIsolationTest` | PASS | 8 passed / 0 skipped |

XML was copied before later Gradle runs overwrote the working directory; Appendix C pins those copies. The pipeline test uses real repository/coordinator/Room wiring with a controlled OCR provider. It is not real-device PDF renderer/image-decoder evidence.

Current reviewed bytes match the manifests, but the whole-tree fingerprint differs because later reports/journal entries exist. Historical results remain evidence for their named snapshots and unchanged paths, not a fresh current-tree full-suite PASS.

## 7. Immediate repair sequence and serialized human commands

1. Repair S1-REV-01 with combined persistence/duplicate/UI regressions, preserving precedence, idempotency and legal owners.
2. Repair the stress harness while retaining all 19 cases and meaningful assertions. Diagnose any actual hang from preserved runner evidence rather than assuming the Ignore comment explains it.
3. Obtain strict review of the bounded fixes and publish their exact manifest. The separately recorded S1 PASS must adjudicate the new counterexample. Author inspection cannot supply independent approval.
4. Once the runner is quiescent, freeze all fingerprinted files, including review/journal artifacts, and have the human execute the packet below. It was NOT RUN here. Do not start S2 before these gates are satisfied.

The existing ten-filter selection and planner wildcard are retained. This is the approved PowerShell 5.1 wrapper form, with no runner -Raw and no direct Gradle/pytest. The human evidence checkpoints are required in addition to the wrapper exit checks; this is not permission to accept an unattended zero-execution PASS.

```powershell
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath 'C:\Users\panos\Desktop\cost agregator\ExpenseTracker'
$common = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
    'scripts/vrun.ps1', '-Worktree', '.', '-MaxTotalMinutes', '130')
& powershell.exe @common -Profile compile
if ($LASTEXITCODE -ne 0) { throw 'Compile did not PASS. Stop and inspect the existing run.' }
$filters = @(
    '*ReceiptPartialOcrPipelineTest',
    '*OcrResultPartialTest',
    '*ReceiptLifecycleCoordinatorTest',
    '*ReceiptRepositoryBatchDuplicateTest',
    '*ReceiptScanViewModelTest',
    '*ReceiptScanViewModelStressTest',
    '*ReviewViewModelBatchDuplicateMessageTest',
    '*ReceiptSideEffectPlanner*',
    '*BankStatementCompletionStatusTest',
    '*ReceiptOcrRetryIsolationTest'
)
foreach ($filter in $filters) {
    & powershell.exe @common -Profile targeted-unit-test -TestFilter $filter
    if ($LASTEXITCODE -ne 0) {
        throw ('S1 repair validation halted at ' + $filter + '. Inspect/poll the original run.')
    }
    # Human evidence checkpoint BEFORE the next run:
    # Preserve this run's XML, verify real execution and zero unexpected skips.
    # Stop acceptance on missing/skipped expected cases even if runner exit is zero.
}
```

At each checkpoint, copy that run’s XML into a new evidence directory before another Gradle run overwrites it. Retain full run ID, command/filter, result, exit code, marker, HEAD, start/end fingerprint and actual test-task evidence. Require all expected bodies, especially the 19 stress cases and new combined regressions, to execute. Do not overwrite historical evidence. Missing/unknown/timed-out/RUNNING results halt the chain; poll the original live run instead of launching another. An unexpected skip halts acceptance even if the wrapper exits zero. Expand reviewed test selection if the production fix changes an additional owner.

Full static-guards remains sequenced after separate ownership/advisory work. FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/FG-06/FG-07/FG-23 remain in force.

## 8. Known remainder, separate from the new S1 finding

### S2 / RP-03 remains PARTIAL

The six previously recorded readiness gaps remain supported by source. These are not invented regressions in the narrow CAS repair.

| Existing item | Current anchors under M/ | Required outcome |
|---|---|---|
| S2-R1 identity/hash/size | data/backup/CostbackupBundle.kt:49-98, :521-544; data/repository/DatabaseBackupRepositoryImpl.kt:1426-1438, :1482-1798 | Carry authenticated identity/hash/exact size into durable tasks and verify copy/replay bytes. Extraction-time verification alone does not prove later output. |
| S2-R2 owned-output crash replay | data/backup/RestoreJournal.kt:45-56; startup/AppStartupCoordinator.kt:618-627; DatabaseBackupRepositoryImpl.kt:1678-1684 | Durable intermediate states and ownership proof; resume this operation's valid published file without accepting foreign collisions. |
| S2-R3 file durability | AppStartupCoordinator.kt:652; DatabaseBackupRepositoryImpl.kt:1692 | Handle fsync failures; do not mutate pointers before final integrity/durability is proven. |
| S2-R4 task ledger/completion durability | DatabaseBackupRepositoryImpl.kt:1541-1596, :1755, :1820 | Merge stable tasks without dropping missing sources; no claimed durable completion after its journal write failed. |
| S2-R5 atomic journal publication | RestoreJournal.kt:582-585 and :312-314 | Prove old-or-new journal publication through interruption; direct overwrite fallback is not that proof. |
| S2-R6 filename/path diagnostics | DatabaseBackupRepositoryImpl.kt:1608-1610, :1629-1631; RestoreJournal.kt:93-114 | Controlled diagnostics without raw filenames/private pointers; preserve private CAS state needed for recovery. |

Also required: completed-task integrity checks on repeat recovery; failure/cancellation at actual file boundaries; source loss; foreign collision; newer/null pointers; DB update before journal completion; task/event merge preservation; device kill/relaunch evidence. Existing PENDING round trips and an AssetRestoreAtomicityTest class name do not establish that matrix. The separate RP-03 semantic-equivalence/checkpoint remainder also stays open.

### S3/S4/S5

- Ownership: reconcile only the four declared recovery mutation identities through their actual owners. config/guards/db_ownership_policy.yml is unchanged by this review, SHA-256 9d91711177bea287c44ad5f7f28d4857805f6ac0f3f66ee022a890c50e4917e2. No baseline, allowlist or RawQuery pin was changed.
- Advisory drift: the 20-pinned versus 21-current question still needs identity-level historical evidence/adjudication. A missing artifact is not permission to repin a number or promote an untrusted report to trusted.
- S4 evidence: current-batch results remain owed for ExpenseRepositoryMerchantKeyBackfillTest, MerchantKeyBackfillWorkerTest, WarrantyReminderDeliveryDaoTest, WarrantyExpirationWorkerTest, ReceiptMatchingViewModelTest and ReceiptLinkServiceColumnScopeTest, plus evidence-backed WorkerRunLoggerTest diagnosis. This review did not run or clear those lanes.
- Guards: old static-guard results do not become current-tree green because these Kotlin filters passed. Recursive Python execution, full suite, exact finding identities and policy-delta/trusted-base protection must meet FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/FG-06/FG-07/FG-23.
- Reviews/devices: the two newly journaled bounded strict PASS entries are not accumulated seven-cluster/guardian approval. Device PDF/image/backfill and full restore crash evidence remain open. The final original finding/rider matrix, including committed CL-27/CL-05 interactions and A1/A2/A5/A7 plus A3/A4/A6/A8 dispositions, still requires full-scope reconciliation.
- Git: staging/commit is a separate explicit human decision. No commit, push, reset, stash, branch/worktree operation or history modification was performed.

## 9. Conclusion

The bounded recovery contracts have valid execution evidence, and S1’s 18 new methods really ran. Nevertheless, the parse-failed duplicate counterexample and nonexecuted, stale-wired stress suite keep the immediate S1 gate open. Fix and review those first, then obtain serialized human validation. Only after those gates pass should the separate S2 state-machine batch start.

This report preserves valid progress and its limits. It neither dismisses a bug because another reviewer said PASS nor rebrands unrelated inherited debt as a regression in this batch.

## Appendix A — focused manifest checks

All 29 rows matched their handed-off hashes. Exact current byte hashes are in Appendix B. Additional unchanged dependencies were inspected but are not dirty batch paths.

| Batch | Path | Hash comparison |
|---|---|---|
| Recovery | `app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt` | MATCH |
| Recovery | `app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt` | MATCH |
| Recovery | `app/src/main/java/com/yourname/expensetracker/data/database/dao/BankStatementImportRunDao.kt` | MATCH |
| Recovery | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecovery.kt` | MATCH |
| Recovery | `app/src/test/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecoveryTest.kt` | MATCH |
| Recovery | `app/src/test/java/com/yourname/expensetracker/data/database/dao/BankRecoveryDaoContractTest.kt` | MATCH |
| Recovery | `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt` | MATCH |
| Recovery | `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt` | MATCH |
| Recovery | `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreInternalWriteScope.kt` | MATCH |
| Recovery | `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt` | MATCH |
| Recovery | `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | MATCH |
| Recovery | `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` | MATCH |
| Recovery | `app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt` | MATCH |
| Recovery | `app/src/test/java/com/yourname/expensetracker/data/database/dao/RestoreImagePathDaoContractTest.kt` | MATCH |
| Recovery | `app/src/test/java/com/yourname/expensetracker/data/backup/RestoreAssetSnapshotJournalTest.kt` | MATCH |
| Fake repair | `app/src/test/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyCheckerTest.kt` | MATCH |
| S1 | `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrCoverage.kt` | MATCH |
| S1 | `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt` | MATCH |
| S1 | `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptProcessingStatus.kt` | MATCH |
| S1 | `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt` | MATCH |
| S1 | `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | MATCH |
| S1 | `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt` | MATCH |
| S1 | `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt` | MATCH |
| S1 | `app/src/main/res/values/strings.xml` | MATCH |
| S1 | `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt` | MATCH |
| S1 | `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt` | MATCH |
| S1 | `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryBatchDuplicateTest.kt` | MATCH |
| S1 | `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModelTest.kt` | MATCH |
| S1 | `app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelBatchDuplicateMessageTest.kt` | MATCH |

## Appendix B — complete pre-report dirty snapshot

Capture: 2026-09-28T18:42:06.1198220+03:00. HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`. Fingerprint: `af007ef39060967557d64dc7906370d09546ff621a2ee998f282d87a64a7fc03`. Index empty. SHA-256 hashes are over file bytes; unchanged tracked files are defined by HEAD. The runner-style fingerprint uses the sorted dirty/untracked path and hash records. Inclusion is a preservation record, not attribution or approval. This report does not self-hash.

| State | Repository-relative path | Bytes | SHA-256 |
|---|---|---:|---|
| Untracked | `.codex/agents/explorer-lite.toml` | 1676 | `04645298e64b0ec47241bf29c66e94c9e4c09d2360d402c2d2253b9ce20bdf9b` |
| Tracked dirty | `.codex/agents/orchestrator.toml` | 13776 | `ca156e28f1ca3c02559af0f62035131af2671afdd2b4f289ba41b5166200401c` |
| Tracked dirty | `.github/workflows/ci.yml` | 18844 | `4289990a1fd3d0d83ac35161bbb4b1286bab65eff66a682207ce5d9a1c63041c` |
| Tracked dirty | `app/src/androidTest/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt` | 4773 | `87eeb2da61a737c89d49e182a9ad057bd6772ff10f54b56fba0ed1b3c3866faa` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt` | 12626 | `677eea069aac9deae39f9ec7518559700eb1d4c109026f869378dfbeca98fec5` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridCategorizationAssistService.kt` | 1688 | `de196d23e96940c45a33426af0e9bf8d20be26c958e5e7804afdad14fd27ae10` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridDashboardBriefingService.kt` | 1721 | `9d457b66b2cf6e3f6137268467668def3bb28fc15da2a989ee45a2f69195de28` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt` | 1713 | `7bcf5c4b2d7000da90ae85ed539bca43584d8ad4a85062006e792b2fd947952e` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReceiptItemCategorizationService.kt` | 1584 | `d8f87c13eca0e1e939eb10d24d6155da771a190800b615d259e60c632085e925` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridReviewExplanationService.kt` | 1721 | `79c0d2840488811f2b64e9bfc323b7a76ba70b5c3b6595e82d9fbf67f16c3501` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/backup/BackupVerifier.kt` | 28220 | `574ee78413ab341f5217094221eb9228f94f18bfb0c6f54b30545acd04ab4af1` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreInternalWriteScope.kt` | 1141 | `7761e3f43593aeeaa9b09f2667dda7f9048f53bf866bbbe196356ee4a7cf7e36` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt` | 41272 | `0b9cac3238d2f5526438783f8d685ddb82db39ab41c7fefb6473a7f5ceffafcb` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/database/dao/BankStatementImportRunDao.kt` | 2316 | `8628738be1f8659d9792be77162ac28eafa93fa0bcae4ee4b24f633a3d94fa5f` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt` | 114500 | `efb05f5a2ddd53d5eabcaad96cdcc566f1d6024d604364939cb6fbbc603d264e` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/database/dao/GroupSettlementDao.kt` | 801 | `10df4f2f9f8776b34e4f34aef8d40c776c763aa3d6dc706d054ad0a6f881c0ca` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/database/dao/ManualRecurringExpenseDao.kt` | 4741 | `86920ef4d61ee61865f2529471ee9cd1d7a2e7229e796374ba5eee4c597535dc` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt` | 13985 | `c3192a91fca0818414a0657e663c6e6af5a148623b41a042801bac663e7b51eb` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDao.kt` | 7334 | `e47859334156928a7544295d7201e34d10023684ab89d31171692e01b39dde67` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorker.kt` | 7251 | `38cadb28cef425ffca84396131e9bab51940ff8dfe3c2dbd9e1e6793010c3206` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | 166936 | `402cbe59ff8301d75fdc6878bd596dd76e47d5cb78a1a79f641b4629abd59383` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt` | 50960 | `061cf92c1e52a6457cb1e6ffee8c701fd9833a71915a42e479aa972f895e9a8f` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepository.kt` | 2887 | `f6dd17806a51762a4442b7bbde49c17e78e27c9fc9341f0986a26dd16b0a95bf` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImpl.kt` | 15402 | `348f00a9a6daf96500e22488eb86aa0642cf28a01753282b90bafc99e52cf2f3` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt` | 46646 | `2de2c92bded12f5abe9f364212a4cb8f060b24553e331a11f90bf6dce7545495` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/data/repository/SharedExpenseDataPortAdapter.kt` | 10580 | `c500b754350ce30f6c3ca83b6e7bdde2454d2ae2c552f6cf306042f8cbb1422f` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt` | 4387 | `6e55716b76b23cd461b585981b99f20b82fc380f4318035daf2caef27d77d095` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/ai/HybridRouter.kt` | 3135 | `80f2888ce0caa0eb3ef6bb69e3adfa62d37353c10d3c1d29c2e97281422cffda` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseBackupRepository.kt` | 6045 | `c0a49edc0954059e1419f9705cce6cb4152a1013c011fc0bfc0f7839ff3b4de7` |
| Untracked | `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseStatsUnavailableException.kt` | 324 | `5ea763794c7c4f883a756485a2692d74dc166506e7a5b088153415518b4a47f6` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecovery.kt` | 4280 | `822b6d163e52fe6effb87ba4476d21a2bb0f515fb5e31af9e67cdcf163e1add1` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilder.kt` | 12318 | `f3f4d84e945cb69509c69201bb5488797d95b2d039ce4cc04881e09cec6ba26b` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt` | 16203 | `c34a2eab37a993277efb7a84ec26e6d415908035389a8219f37f9d96cd062b6a` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt` | 40560 | `b0159d0157637be68aca727246eb96dfd3855746947c9e42713b77f206958794` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupBalanceCalculator.kt` | 2912 | `fa0c06528f5ceed3beb25406be644944907a5fa594a9af5937514a94c2ef2668` |
| Untracked | `app/src/main/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicy.kt` | 2066 | `3786ffabc99ad2917df2c3cfa7558662161e673f13a70355748aed989435d076` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseManager.kt` | 22242 | `bb66d61e5a0debb2aa48e01daa7731ba30066ef3d404cc10654bf823deda155f` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpensePort.kt` | 3597 | `69857ac1381f35f5b5332982055b08772396b2b4d6e6a46d08fba00f4d62f03e` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedException.kt` | 994 | `bbd647b32e7c0910c9314a3cfa562b45d964ba6d480e5103675bfff31a02cf92` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` | 63146 | `868a8844cd591262b7892099f79b01c71448549d6704078073b732f0708a37e0` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | 93566 | `b8a6ab51948cd101f440ef2441c6fde3c4c35c00ed92bf1daf0f69e9f306cdb4` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt` | 12145 | `0891e516c824359ab8433b0319d88038f3a3f58fb50e65e18549b670410fe5fd` |
| Untracked | `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrCoverage.kt` | 455 | `cebd4132aaac40e7e7db523e12a357554557ede32b46910d7ba44bce2f328720` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt` | 40361 | `43a2b99e0e9c6d5665f8b029e7dfb3833c5dff61b076e6979c19f4d9e9eefe3e` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptProcessingStatus.kt` | 365 | `636425d8f4b4c5352e122d38e48462a5dbfa4918082a73242d1beb222c18f3a0` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt` | 31554 | `1751ea6a1c3cb245060275e696ffd5018f496704ac04361e081353fa30e440db` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | 72584 | `c595722741b9dccafd56711a1fa2fdfc812c76fb4e410a59574efa27f9738908` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` | 20932 | `633ae470cd9c8583e2c643652003f0645ce3b622726f19bb192a9bad6d091827` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryImpl.kt` | 5176 | `668af7e5db8d808d55a0b8defadeaeb216c1e05ac16c50e0c1f1c4633c39d75f` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt` | 16833 | `0fc22d75cb03d2003c9ee1e40fdb9a1b1fc9709ec15d47442bb4c77926db4331` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt` | 20070 | `b35dad56a65aa7ce93a176be951eec06b521f6f201999bd2e76e2af84b9429f8` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt` | 12327 | `74901ad4fa2d53b480c04fdfcaaf4c3dea6af816db1d72852e0c2db191349636` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt` | 43330 | `1cabe9e7aeb041dc5a064d0eeaf292ce10566aab321e76d1f14840ecd57ae2cc` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/components/dashboard/MoneyRadarWidget.kt` | 18615 | `667191bbe391822219e81caf3919b2b73b71ada954f36e32010b8dfbfcc7355d` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/components/FinancialStressForecastCard.kt` | 15232 | `a544a2a887559e929e06a1b0db5544f905473493503f6584489c114e1ea8d154` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreScreen.kt` | 17481 | `0827b20e5ba180ba1dbc9d20479c8db6ea623a297243052141b4b418f657a085` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt` | 19645 | `46b5da5d1fce829ed01ccf773eeded32643bc7ac60cebc06671ebb8fdfa48f21` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt` | 86352 | `a6ba4d04d24a8de7b510115565a14f4fad9262f8637096b57d4eccda14172f4d` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModel.kt` | 25237 | `e1d3854970d890d5dd04a59a033c0fe03555809e6362fd71078185e379967153` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt` | 17676 | `60c4e9e0b1ac7025e735b0929ff4c6e4cbea711ea9a3be13fca1d095012d835c` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt` | 64971 | `a7f93f5a8298d752b238a52ffe6252f9d8239b0b1576f077eedc5831cc82e0c7` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt` | 69270 | `4a9432aa37be205cd23742db571e87a685ce83e860a85fa71b41b06659ef50ba` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt` | 61673 | `447364ee7999e4a379d3d9dfc765aa0f6470cb34f00b2ca132721ac36ced763d` |
| Tracked dirty | `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt` | 17076 | `66dfde120a93658c00013085287489d6b57009b39077d8f46d5859154bf981ac` |
| Tracked dirty | `app/src/main/res/values/strings.xml` | 177020 | `21a88782b7dc1e205b052e53e51b1d23e29bbe915b9fb623e72598cd163b4cf7` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProof.kt` | 6796 | `e1c92676d6abacd5950e181b3b4c64a308b315c95961d64b0f1d46ad11607f57` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/architecture/WorkerEntryPointProofTest.kt` | 4614 | `018e7825717b0a0427101776671f5884dfe6aa6a3df41f7eb9d81c31e2f2a9ae` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/architecture/WorkerGuardArchitectureGuardTest.kt` | 5592 | `6fb1189b2b224c4c40613c969a16dc2404730a70cda87350de0185990ddaa682` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/architecture/WorkerSourceMask.kt` | 2999 | `62c416748b1c4a585301040b6aa86c7df71cdcdb3169061283fbf1d5250f112f` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudProviderTransportPayloadTest.kt` | 13536 | `7648dd83f2987c77d443886199e3a71eb4da80d86c1751e022d2c5cad7d34489` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationServiceTest.kt` | 25514 | `ddd92179c56fa76d794a3abb5d412b1d391e68ba9fe10379eef9780ddab93344` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/ai/provider/CloudQueryPreparedPayloadTest.kt` | 8828 | `6bcb9443add66d5c5f76624951e7ea2948b05078d9534b82788e45fac3ddcc55` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/ai/provider/HybridRouterIntegrationTest.kt` | 9570 | `472ab479b09db130f6dd980978b88c31880844b63f2bc7d1d5b8217a46945db2` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/backup/BackupVerifierRequiredSemanticQueryTest.kt` | 4618 | `4c221c253010b5d94342e5bb6030cf47950cd484dcf7012393b6e7c7704db5a5` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/backup/RestoreAssetSnapshotJournalTest.kt` | 4155 | `f0cbf582f3d4931f415e4e481c4f64c83740a781ae4026b05b9ed2b73cd9d55f` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/database/dao/BankRecoveryDaoContractTest.kt` | 4401 | `d1ecb09560d2f2f37c466a4b320c61a86250e528d68cf86dec26d0b625002b0e` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/database/dao/RestoreImagePathDaoContractTest.kt` | 4357 | `fbec78858af24cbd03d45c35908b022ab76ed9731cb38648ed60adca9ee2435e` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/data/database/dao/WarrantyReminderDeliveryDaoTest.kt` | 12203 | `fea4db43d1ae602b79d8063eb8eb67575d4251075e5c3c529c80ea26277ee3e6` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/data/database/GroupTransactionCoordinatorTest.kt` | 63142 | `fd48bf4b5a64eaeaa15aed9026fa2d1b1b09436287575f5ca996777a88466cbf` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/data/location/MerchantKeyBackfillWorkerTest.kt` | 9318 | `aaefe92879843d012f654834c18bc595181d55a23bff9f37cb97064305c4b9ad` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` | 120536 | `f72272bef013ccf919295665f63fe4d4912a1d1194d8bd9560494c58b4de5c58` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/repository/ExpenseRepositoryMerchantKeyBackfillTest.kt` | 6666 | `48b88063a1ed0b169d00d3404ddf6fd4dcb5c9e005ef608d6821976cee94cedf` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/data/repository/GroupsRepositoryImplTest.kt` | 27437 | `18cf20bd1c4810ea44c13c1949490197c12b36a980bb1b41b034e1763c15708a` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryBatchDuplicateTest.kt` | 10601 | `d88b60e0e3980cefcfa5b89eeda4f3f15cd33152bdc449fcf315d0f037b07a87` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/data/repository/SharedExpenseSettlementReadTest.kt` | 2055 | `e970ae8df3ecbc02ed3dbd59cb2950322646784bb3c9e30bd8e7740105ce57d7` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/domain/ai/HybridRouterTest.kt` | 5132 | `24bd95c9c73007945e9755fd25c3cc5f182408de7b8e6c6db6c5afd276659468` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/bank/BankSyncStartupRecoveryTest.kt` | 11445 | `e2a56059edc6df36f611248e0a0728ec5f787af6f193423ef1f48ab54a465f88` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyCheckerTest.kt` | 24607 | `283ad01eaeb340fa3834328b88c405a397f2bef9f85639890235801f38a6c225` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/core/money/MoneyAggregateBuilderRestrictionTest.kt` | 16573 | `bced6298d3817071f1f017ec8c186723f789ed24dea0d8e0afedcfb309d3e76d` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngineTest.kt` | 37583 | `0852af0cbc9977759720f0f4544143fc83ef25c4dd19919f43542f058c8f7e60` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/domain/groups/GroupSettlementBalancePolicyTest.kt` | 2275 | `a561d25956e952ca9685c243222f9c73fc4be19abf7ffbcc955d17ed9ffdeac6` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/domain/groups/SharedExpenseManagerSettlementTest.kt` | 3987 | `d35ac5d791ba9f6b9c8634872c6dcd427cb5a5aeb0a00508e00f493ab4792592` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/privacy/CloudProviderPreparedPayloadTest.kt` | 5849 | `c05b3680731c9d0f0bd781c53a46cc12f463c47c5fc285ac36f8c52f02a06140` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/domain/privacy/PrivacyDeniedExceptionTest.kt` | 1609 | `e974145fa9ed8024c7543eb057b1bb8d41f9cfe80241ea913fb83793dab0b119` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementCompletionStatusTest.kt` | 1856 | `2c960ed0475ade974e587c5e0113dd81c9d0fccd95df3cf98ccbf54794211e36` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt` | 17324 | `8b942ea217d30355685b9d80da70954f709bf5bf67867c1453f4ddfa6f61a24b` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt` | 17055 | `769390c0c39584e248d905dc6d7f46a17039a1f4d6c15f8e4d255fafd500bed1` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/domain/receipt/OcrResultPartialTest.kt` | 1112 | `2f648954b8f66f7f78e0d2696be25511b40be0ca74ee3123d9528c24e25c9086` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinatorTest.kt` | 44136 | `c4d8a6788f134babf7cf492d721a0c5de69022c4e8b1c1a3068aa8f8c6c20255` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt` | 19189 | `0ee9524945f6ea0b4873c8207d3570e5c5781c1bc09381343439577bd9a59208` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt` | 29728 | `84fc68f519cff22b330ef2b2560b2c882fd7de9e24ed81e6359d14c61f1a280b` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerLeaseRegistryTest.kt` | 16184 | `e54bf2b971cc7ec03b3eebc47645b32034966f1cbfe1ec5be76e11536b29b1a3` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRestoreRegressionTest.kt` | 27314 | `f5ba4227c4b0d4199601410a9d45b7cfbc75776d2565ccaa44bf2207f21f84a2` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/domain/workers/WorkerRunLoggerTest.kt` | 48708 | `3e0991afd01d7a31fb2523e3e351c3b018dc141009e515db1c1d99c91a822a0b` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/e2e/NotificationExpenseDashboardPipelineTest.kt` | 34257 | `1d6bd170f8f0fd34d113aae8e51cd7e9cbfdb3a5c0fb3327ec666b4aa1cd763b` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt` | 38239 | `cf94f172c839f653869977d66b15c3f7a2478fc3a03a51f0e1292bc3bfcdde37` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorkerTest.kt` | 19352 | `bb78094c7efd3985c71b7343eccee493c3df66223f1de73d145802367d2daffe` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/startup/AppStartupCoordinatorRecoveryTest.kt` | 77427 | `048d728ebfee9b4bdd81ea5f8f8f7286e1f073ac37549037435ff44de4216dac` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` | 15403 | `9ae3fd99ecfcc8f2044136c1ec548a538643010a0301063c8efb3ed10c57284d` |
| Untracked | `app/src/test/java/com/yourname/expensetracker/ui/screens/debug/DebugViewModelDatabaseStatsTest.kt` | 7837 | `d23d06f07f82258231ad5a94c13319ffe417eeb66663144178283ab113a07315` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModelTest.kt` | 34043 | `0dfe535da453a3e4498e791c8ade6a3329eaedaefa7bf155ea9d821704023c2c` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt` | 10529 | `04ce37e2c5afcd81d0268d849b80da98869b1607bfd665245ea1079d95708342` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModelTest.kt` | 17985 | `3b1b626843e211b332d84c59b1d2283f6f8eb1335049a4727f4fd0faea34799e` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelBatchDuplicateMessageTest.kt` | 8654 | `3f12db0fece67c31c1cc6c476255f458656225704b4aeb6d5b5709774c460836` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelPrivacyDenialTest.kt` | 8699 | `9ba57cabd64a0594c794dff9228371864fa466cd649edac0d570673041d12276` |
| Tracked dirty | `app/src/test/java/com/yourname/expensetracker/util/CsvImportRfc4180Test.kt` | 11512 | `f9c74f75b6c4288cc7e9a5deb629ea3f206bce2e728d752e6ec5876dac404394` |
| Tracked dirty | `config/guards/db_ownership_policy.signatures.accounting.json` | 403312 | `d93da596d674ff5cfa44af687041dbd9736bb202c82ebcb3af7c86380a1b915b` |
| Tracked dirty | `config/guards/db_ownership_policy.signatures.candidate.yml` | 313973 | `d057d4fab03d31a13f030239e2958fb5aaa8939f68b179d95b73b75b41671017` |
| Tracked dirty | `config/guards/db_ownership_policy.yml` | 332994 | `9d91711177bea287c44ad5f7f28d4857805f6ac0f3f66ee022a890c50e4917e2` |
| Tracked dirty | `config/guards/db_structural_exceptions.yml` | 26915 | `c9d5ef6ed50c0a1448ca1cdc46e29a8202be14f11f46b3ff940661a5e758b15a` |
| Tracked dirty | `config/guards/db_structural_exceptions_expected_methods.yml` | 28772 | `367b0ec6b3fbdfbb8e281152b6b60cf69e053d6ba0d4dbcca4fdbcd78247f8a4` |
| Tracked dirty | `docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md` | 83887 | `cb34ac3305fad1f7976b6bf07182cc6f51b745905267e65338c9d34dde5eb6ed` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A2-denial-contract.md` | 3365 | `3bf8dc1a98385ed81c22c3742a6eb74e481b1d7302d8fb551fafd20029a15b65` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-self-review.md` | 4041 | `aeae818741c12c2f85475d127edb79915cee838270e0f3c1b624627de8e030b0` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/A7-group-balances-spec.md` | 3306 | `b76ffa0b6e42e561d3d31bf7a4ae9b32ea74f94a5393141f774b5371237e76f6` |
| Tracked dirty | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-05-review.md` | 29013 | `13b19217a6dd807df94371784a1c171f91d2d0dcffafcf093f2ccb0cfa834da5` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-self-review.md` | 4293 | `6d3f6e004a66aa1939ad5b15b5453ef72af369ab818125bc551afb0b243ce65a` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-09-spec.md` | 8022 | `4eca24002f9de4caf8cff6f3bba422d78b83623d93d656cb6b7d9cb66be1c5b0` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-self-review.md` | 7081 | `db08aaf10ec0da8d217b368287bfef037eb574b9d2374b2b61a843eff2e56ead` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-15-spec.md` | 4307 | `2af81380894bd1689d28c50bb9ed232caf118c6b2ed21a5f178050f94c2e5744` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-self-review.md` | 4341 | `6e7e7cf0d49888eab58e82e99dbdcf641308357a581fca62933563cae4d6c507` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-21-spec.md` | 4688 | `5fbaeb87e8dabbdb25920fc486e7d6cfa1d28f3a9018022d8166e45d39b53461` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-self-review.md` | 8495 | `5a204b954c492dcf89ada462d5858c7cfcbf7cdf1be0ca9c0da5c9f319a08e54` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-22-spec.md` | 5433 | `e53d2c44c560965154b611548718d5663729d2daf7ac599fad470ea92c862232` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-self-review.md` | 3623 | `b16072b7b9fd7392b0bea9706cd0852071f20c6178c60e25181b8b469fbafb90` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/CL-23-spec.md` | 6653 | `731cbb28887cef53aa409728a09f3aa5a1357049b85a96bff4a3b65640c81975` |
| Untracked | `docs/analyses and debug master/campaign CA-2026-09-21/wave2/remaining-wave2-plan.md` | 7340 | `05637fe213b06d86875ce37a0a3b8513a33c13e70f046d6d992a4179720a86ec` |
| Untracked | `docs/analyses and debug master/CL-29-wave1-implementation-report.md` | 4994 | `e26b82b86bc4bd7f243a7d505cde197e42f67379f1ba521081e59f616b436d4a` |
| Untracked | `docs/architecture/COVERAGE_MATRIX.md` | 32948 | `edc6edde036ff454bc78eb8d8675be1383cbe97b10cedac7f2da78bfb88a0c4d` |
| Tracked dirty | `docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md` | 23470 | `ae7685d47100bed31744f545415f10d8306886ab5c87b171c7c9b7f835788808` |
| Tracked dirty | `scripts/allowlists/ui_dao_allowlist.yml` | 398 | `fa74538a2792dc4a786243a33e3bccea1d77f423a82531f1fc47b9356cb88374` |
| Tracked dirty | `scripts/ci/capture_db_guard_evidence.py` | 190307 | `1ae59e95f10d6b97b9eb7a41798ca74823c5f7055e66ac78a09ca118a37ec9c4` |
| Tracked dirty | `scripts/ci/guard_ratchet.py` | 91792 | `b77e8cf946b7fea6df7f61307b0f441b12f5f429ff6d1083d73c98fffb06b26b` |
| Untracked | `scripts/ci/guard_test_diagnostics.py` | 9363 | `d3838e0ddd0d91a762d8a60b0578458b0c47b6d0482c471223c7d09061408a2d` |
| Tracked dirty | `scripts/ci/run_static_guard_suite.py` | 39854 | `da3b46af40c93046d56850cf517d0a7122ced3a9aac8e91b029039c72b8f14ed` |
| Tracked dirty | `scripts/ci/test_capture_db_guard_evidence.py` | 237978 | `a76ddf913009e6143b847f6c4d13a7c2445080c5b0e4f190cd41dd176912dbdc` |
| Tracked dirty | `scripts/ci/test_ci_workflow_evidence_gate.py` | 5662 | `e9633f5bf396cc8c960aa4668027f2d332ab653e988a837765a12412339c7bac` |
| Untracked | `scripts/ci/test_guard_ratchet_occurrences.py` | 4582 | `b79bc4fffbe64b5ef525893165e6dd48d9b26f2520def93e563ecc8a36681806` |
| Tracked dirty | `scripts/ci/test_run_static_guard_suite.py` | 99780 | `5b13b4d6738aad52f50878a3ef789e30becec22aa801f5e05d1cd98e232cdd7c` |
| Tracked dirty | `scripts/ci/test_verify_known_good_state.py` | 55048 | `2e6af83f86fa965e7037ada8fb61d897b26a6258be47270c55d341974e91d077` |
| Untracked | `scripts/ci/test_wave2_guard_wiring.py` | 1757 | `092a5b2fd523d534e76beca1319c3ab17853872bcc9a90844b91c6773424cc94` |
| Tracked dirty | `scripts/ci/verify_known_good_state.py` | 40954 | `4ad0ebe791f0a50339d71d761da6b5d5d5754be75f2e89023aa6612a1b7c68ce` |
| Tracked dirty | `scripts/db_guard/mediation_analysis/test_models.py` | 4040 | `83c97e3e8f77b1c4fc347e7bff9ce2fe75d5382e322d0d56fcf03f2531de9db8` |
| Tracked dirty | `scripts/db_guard/policy_parsing.py` | 49710 | `9538fb1a301ff7f59ee57734cd78c44b56f935593febdde7593ea587c91985a7` |
| Tracked dirty | `scripts/db_guard/scanner.py` | 195892 | `3ccb4b936d8b14a7f1e44adf17618169dcc77e356b0639daf45841f28a049e17` |
| Tracked dirty | `scripts/db_guard/sql_classifier.py` | 83300 | `fc32415cdd53c98d5307376127b3d93856e5fbd443c9b5e60a79747058b9fb58` |
| Untracked | `scripts/guardrails/cloud_payload_proof.py` | 31403 | `904654f677729f490a7dbce1fc76c0e586313a8e9f9869a4b805cf5a3bad2e71` |
| Untracked | `scripts/guardrails/test_cloud_payload_proof.py` | 12916 | `d7a9b290cd3780338478d00ef2f193d8f8f9983d92020b461672d8b8a2169938` |
| Tracked dirty | `scripts/kotlin_callable_parser.py` | 74904 | `39374fe687a8beb4bc86cee789d08a45ddf8f0b53fe5efbf5df4ae50695ffa18` |
| Untracked | `scripts/test_cloud_payload_guard_integration.py` | 4309 | `422727c0f8895292ce9aa3762b99ba3d616cabde14d20df1d0c164ac7b38a3b2` |
| Untracked | `scripts/test_db_discovery_diagnostic_details.py` | 3780 | `f7785af69b483b42b5257881f287bcb2d154c89956cbb3ee0965fab9e512b58c` |
| Tracked dirty | `scripts/test_db_guard_policy_v2_evidence.py` | 128729 | `93b652cacd354c14c99f6fb96837902e86eba959ccc807d0f220b887a54da96f` |
| Tracked dirty | `scripts/test_db_guard_room_inventory.py` | 154588 | `db447c2d158aa0c5a320d72720855ac6aefb07bc0add3254426372e0aa4fc17a` |
| Tracked dirty | `scripts/test_db_guard_run_cache.py` | 16686 | `0760e84989a427b471a9b5ebd3900c1e5a4fc36fbe2c641b8fe7fdd58ea8f0d3` |
| Tracked dirty | `scripts/test_db_guard_scanner_d4.py` | 136935 | `06b78fd128690a984d3c96f9d8af151f756cba6c05ab61dc1f60029e0212637f` |
| Tracked dirty | `scripts/test_db_guard_sql_classifier.py` | 60463 | `f9e278cd7028e19af539b9b06d5ac2abf56dac51f386b67414a7611bb8130e37` |
| Untracked | `scripts/test_guard_test_diagnostics.py` | 15219 | `5b5895aeefb9f8055d7aee2cc91772b222c1e4ebe7c402cf911d1c9af3adddf8` |
| Tracked dirty | `scripts/test_migrate_db_policy_seed_rows.py` | 442929 | `365791d881ecf505a5425d4e9ad52390e1958858714b8479752720f9a3d5368f` |
| Tracked dirty | `scripts/test_migrate_db_policy_signatures.py` | 141848 | `2775ff725b392bee68ac09545ed32eb5ca9885d2deb316bd9fd1ff4c16a9bc02` |
| Untracked | `scripts/test_verify_allowlist_compliance_fail_closed.py` | 3535 | `6e1c1ae0b4f7a9ba9773492e50de4942653b25b3e3c338caaf755b42f129490c` |
| Untracked | `scripts/test_verify_cancellation_allowlist_scope.py` | 6144 | `fed4dac4efa53b78ca73e166da3bd68039da7a688fd82b0c68843f1144b1d202` |
| Tracked dirty | `scripts/test_verify_cancellation_boundaries.py` | 26052 | `24ab49a27c7e018ea07b29b5bc6a2033301eb4d089d185a00babc14d81941ffa` |
| Tracked dirty | `scripts/test_verify_cloud_payload_boundaries.py` | 12028 | `69c0e25fb1ad784c8b194ed576cc760a5f2c01daed007ea5bdb1e5ac97407396` |
| Tracked dirty | `scripts/test_verify_db_access_boundaries.py` | 258034 | `7f9f3fb7dcd5ad6725e495c30166c798186e04c25ab9b75a008b027473a26b3f` |
| Tracked dirty | `scripts/test_verify_db_access_v2.py` | 130427 | `9aca452171ff243ccc0f9a752ef7f2f19777c74df6a1ddde3a6ae93dbcee565f` |
| Untracked | `scripts/test_verify_worker_entrypoint_proof.py` | 4521 | `2c34a9d7a76ebc568faea57b4897f3f47dbbc17b46b5b71eab466c6d54025569` |
| Untracked | `scripts/test_wave2_db_discovery_semantics.py` | 21423 | `5e61bc1dba805293de6b3ff50822b7566adcd2733baee715fd5fe42672c457a2` |
| Untracked | `scripts/test_wave2_db_file_discovery.py` | 13596 | `2029c2ffad9eced5aad1ec6adea2a7e3eb65c26fced5fd5befd7da8ba8f9da5b` |
| Untracked | `scripts/test_wave2_db_ownership_reconciliation.py` | 14374 | `2343c9045340d2128b15734f81fd9a2b17ccc24c36affbfa11715fb34d1fa2a3` |
| Tracked dirty | `scripts/verify_allowlist_compliance.py` | 18069 | `c5d13efad18e82b233d8f46002b28b147e390edb6f9afb561d282b512d8946fb` |
| Tracked dirty | `scripts/verify_cancellation_boundaries.py` | 22731 | `671db49813976969db18d99a6cc3278c045c75083f379f5554cf4606744af45c` |
| Tracked dirty | `scripts/verify_cloud_payload_boundaries.py` | 10878 | `49ab0d18f0203878fce46819162c7463f9b44b5820f6f07f60027f7712398426` |
| Tracked dirty | `scripts/verify_db_access_boundaries.py` | 180405 | `583512f7a0bb57de4c0e1b93d963dd03480bec18e8659e8623be2277af30b00c` |
| Tracked dirty | `scripts/verify_event_writers.py` | 11761 | `18fa0e0d90eef570dcb081926c0d45b1037d2f0e8fccfccf42c33d9673fcfa4a` |
| Tracked dirty | `scripts/verify_privacy_boundaries.py` | 25500 | `6f73f3bea2ce48f88832c47dbe0640095bbf2b46a73c768452ed8daa6c0309d1` |
| Tracked dirty | `scripts/verify_worker_boundaries.py` | 26027 | `f40564c1e3441677f5569f4c57f4fbd23e875278337e324d10424ab63976eb0e` |
| Untracked | `workflows/active/wave2-backup-cleanup-policy-20260928-handoff.md` | 11511 | `bd3296471a5aecf36a39a326256771333610d1a1af6a3b1639ca3f9e35ffd9ba` |
| Untracked | `workflows/active/wave2-closure-progress-20260928-review.md` | 48599 | `81bc166ff860c0abd0026ae8cc2bb5df17ad8e7da5aacdcfd9cd7b736f64dd00` |
| Untracked | `workflows/active/wave2-closure-series-20260928-plan.md` | 4055 | `371b5bccba3f96021a4ff268fbc0134f7445f234001823789a6081158079295a` |
| Untracked | `workflows/active/wave2-db-coordinate-contract-20260927-handoff.md` | 11753 | `4c57e1ba51fdda53513df36020643ce737b6e8f38650b60f5566397738ac5803` |
| Untracked | `workflows/active/wave2-db-discovery-diagnostics-20260927-handoff.md` | 14900 | `2393c7949b46b5ee3f081ae1028aaa26d8965688f9737a6cb9697c4bc37eeb51` |
| Untracked | `workflows/active/wave2-db-discovery-followup-20260928-handoff.md` | 13605 | `30ef7830337f9eb224a64502b924c1c2098898981220cc4b0315c363bbd88f81` |
| Untracked | `workflows/active/wave2-db-discovery-semantics-20260928-handoff.md` | 12448 | `1e46872150c9ffc45b93595dab613448e344f96875f787f950c8f2da7c048eec` |
| Untracked | `workflows/active/wave2-db-evidence-detection-repair-20260927-handoff.md` | 9411 | `141b96940ffa8f19ef7a3bd472ac78c26fce90242f4815c522542d337c273fba` |
| Untracked | `workflows/active/wave2-db-file-discovery-20260928-handoff.md` | 13639 | `9e0bd6dee820407846c2409a6b2400f2fcbe68b53c67b3d2820fba88679b8af1` |
| Untracked | `workflows/active/wave2-db-guard-followup-20260927-handoff.md` | 52322 | `d7d20dd2fdb8b28843b79c53015f4fcc247720a68303819cb045857542b1a29d` |
| Untracked | `workflows/active/wave2-db-guard-repair-20260927-handoff.md` | 59438 | `051f6db09fe4ff5fe3b302e35779070270c6b39663c8f4dbf1637d52a4e83cf6` |
| Untracked | `workflows/active/wave2-db-ownership-reconciliation-20260927-handoff.md` | 15950 | `230abd7ecbe7d2552a64268ee989a3c9530ceb602273ba39093487325fdd850e` |
| Untracked | `workflows/active/wave2-db-pipeline-diagnostics-20260927-handoff.md` | 50578 | `d2c08d39761b81ec34805647052d094562405624c47472021e21cd8baf2e4305` |
| Untracked | `workflows/active/wave2-db-source-evidence-adjudication-20260927.md` | 12398 | `96e48287527103022e996b3833c4c82c04e0ece44d0aa48886249b84d8bff851` |
| Untracked | `workflows/active/wave2-finalization-repair-20260927-batch1.md` | 15234 | `31e41d8f72a55d9ccef924a7655756b5020f0f2635536b064ade2f3fabd51d66` |
| Untracked | `workflows/active/wave2-generic-pdf-partiality-20260928-handoff.md` | 15295 | `46e1086c4e1fa223887f87b1cf5cbf2606cd82f193eaaa2889abdfa51ac68ee9` |
| Untracked | `workflows/active/wave2-independent-deep-review-20260927-100231Z.md` | 169752 | `a6c17cd4081dee93b351bbc167223eb9fcbf4db83ce382f4f2072bd1a96dc83d` |
| Untracked | `workflows/active/wave2-mutation-advisory-adjudication-20260928.md` | 26615 | `7d801496f7a1a3c9190708af66b5a835f27e0b804b65042f30b27ff86d1f3267` |
| Untracked | `workflows/active/wave2-post075653-adjudication-20260928-handoff.md` | 11898 | `bbd7254699bb935a946cfb1012bab1dbb3fd20d064248c33ed8c98822bac6b91` |
| Untracked | `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md` | 60453 | `6b9d75c287c23a917d80c1262dc96241de0bb0f3e2c3de5d6002d0c046100e76` |
| Untracked | `workflows/active/wave2-receipt-policy-reconciliation-20260927-handoff.md` | 49011 | `15718df600a9a97336851d61f71240d93a843f1ab6dd84c73304ff21a3ef147d` |
| Untracked | `workflows/active/wave2-recovery-contract-repair-20260928-handoff.md` | 18988 | `27857e6b07d7be8381c9cd1640f53f0c185a98aee2c9eed363ee5d9735c3297f` |
| Untracked | `workflows/active/wave2-recovery-fake-dao-repair-20260928-handoff.md` | 4561 | `207d12a8e4e37ce3bbb57703861ae8caacf550c4706bfe30bcc00d83bd469f64` |
| Untracked | `workflows/active/wave2-remaining-files.md` | 11480 | `1bd01939a0e4e30fce314890678162daeb4fd96fdba06aa735cf1113a0fe0456` |
| Untracked | `workflows/active/wave2-remaining-handoff.md` | 10535 | `0d47563109df0e6f37559ab670e56fb10e8bf1c2bf56df492f66ca525d88ef48` |
| Untracked | `workflows/active/wave2-remaining-validation.md` | 8707 | `3fe0ec8e4e6e83a65dbbd5ce9d3596476ed0080d527259687646fa8b6fdcfc7c` |
| Untracked | `workflows/active/wave2-s1-validation-evidence-audit-20260928.md` | 17120 | `b24703f333b3ea2547f17ff746b685a9d788501537c16b3f2580465161f669f5` |
| Untracked | `workflows/active/wave2-s2-asset-recovery-readiness-20260928.md` | 13982 | `bd4d9d5761baf9131e4c927964af150a3299e34110d56d4b8428186049a1369c` |
| Untracked | `workflows/active/wave2-validation-repair-20260927.md` | 11022 | `b95431469690db4523310e2383d37c89bdf21471d79e5e1126cec3a317362156` |

## Appendix C — preserved S1 XML identities

Listed cases include skipped bodies. The three planner XMLs are one wildcard filter. These historical files were read, not generated by this review.

| Evidence path | SHA-256 | Cases | Skipped |
|---|---|---:|---:|
| `build/validation-runs/s1-xml-evidence/1-ReceiptPartialOcrPipelineTest/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptPartialOcrPipelineTest.xml` | `a810683d8509205cdb4d04ffb253f6859acfa1e4828903779f5e8f731c43f80e` | 10 | 0 |
| `build/validation-runs/s1-xml-evidence/10-ReceiptOcrRetryIsolationTest/TEST-com.yourname.expensetracker.domain.receipt.ReceiptOcrRetryIsolationTest.xml` | `f3d37dd2a4a1057a7500a2defa67bd8dd9b653f0db07bc4863416383ab237a9d` | 8 | 0 |
| `build/validation-runs/s1-xml-evidence/2-OcrResultPartialTest/TEST-com.yourname.expensetracker.domain.receipt.OcrResultPartialTest.xml` | `32391fcea9de5720052275a2e652fe1005afde7be4e96c6568c21d22bc971214` | 4 | 0 |
| `build/validation-runs/s1-xml-evidence/3-ReceiptLifecycleCoordinatorTest/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinatorTest.xml` | `25942e4dbe3fd46ec867627c6724b50459cc16c070081c9a8466d953268854a6` | 35 | 0 |
| `build/validation-runs/s1-xml-evidence/4-ReceiptRepositoryBatchDuplicateTest/TEST-com.yourname.expensetracker.data.repository.ReceiptRepositoryBatchDuplicateTest.xml` | `1be7844c26915c6099dafd853271ec166aa23b574bdc1bb8bc81d04940cf21d2` | 5 | 0 |
| `build/validation-runs/s1-xml-evidence/5-ReceiptScanViewModelTest/TEST-com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanViewModelTest.xml` | `8db5a997d9c95668db1c019bf202f64971a38e99ab71263b03401093d6c1d4dd` | 17 | 0 |
| `build/validation-runs/s1-xml-evidence/6-ReceiptScanViewModelStressTest/TEST-com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanViewModelStressTest.xml` | `7a59a8a2562b11ac0049a44f856821f756076c88d0547e653b49f3dd11b713b6` | 19 | 19 |
| `build/validation-runs/s1-xml-evidence/7-ReviewViewModelBatchDuplicateMessageTest/TEST-com.yourname.expensetracker.ui.screens.review.ReviewViewModelBatchDuplicateMessageTest.xml` | `07c924774191fb43c7e8a88f01ad819a22fe96d0d8c60f88b6b990fab2c156e2` | 7 | 0 |
| `build/validation-runs/s1-xml-evidence/8-ReceiptSideEffectPlanner/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptSideEffectPlannerClaimTest.xml` | `52d638d68bec7d362e07bc572276487d82d0b8b33425188448116be302489b20` | 4 | 0 |
| `build/validation-runs/s1-xml-evidence/8-ReceiptSideEffectPlanner/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptSideEffectPlannerEphemeralItemsTest.xml` | `6a8a3621cb005aef0dbdaaab8932a477c63a7fc13929e6112d78faaf5af9f173` | 7 | 0 |
| `build/validation-runs/s1-xml-evidence/8-ReceiptSideEffectPlanner/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptSideEffectPlannerStructuredDataTest.xml` | `1a1826b2529401301ecb8476092b22f191609f87f9411244930ee3e9b6006364` | 2 | 0 |
| `build/validation-runs/s1-xml-evidence/9-BankStatementCompletionStatusTest/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.BankStatementCompletionStatusTest.xml` | `2c05a3c01afc337df27f91f8ffcfe5a3bfc9272ddecac1c79263e7710adedb2f` | 5 | 0 |

