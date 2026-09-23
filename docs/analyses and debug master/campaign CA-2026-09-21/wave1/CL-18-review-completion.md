# CL-18 review-completion — rp-20 / Wave 1

Date: 2026-09-23 (+03:00). Reviewer: astra xhigh.
Review target: `3cbff279b49f263e0182f5cab76296c3d2106e70`.
Base: `ad412aecb1347daebee7fef62aca745cf205f0aa`.
Spec: `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-privacy-gate-fail-closed-cancellation.md`, read in full, including the lifted gate.

**VERDICT: FAIL — completion blocked.** WI-1 is DONE-DEFECTIVE; WI-2 is PARTIAL (implementation matches the spec; execution/review gates remain). **0/2 DONE-CORRECT; 0 confirmed diff-introduced regressions.** There is one P1 retained contract defect and one P2 completion-evidence gap. These are not relabeled as newly introduced regressions.

## 1. Environment, scope, and evidence provenance

- Verified worktree: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-20`.
- `git rev-parse --abbrev-ref HEAD`: `rp-20-wip`.
- `git merge-base ad412aec HEAD`: `ad412aecb1347daebee7fef62aca745cf205f0aa` (the requested base).
- Initial `git status --short --untracked-files=all`: empty. No staged, unstaged, or untracked paths.
- No CL-18/rp-20 coder report or lane handoff found in the scoped campaign/docs and `workflows/active` locations, tracked or untracked. No stray coder report is eligible to add. Existing runner records are the available execution evidence; no prior-chat claims are assumed.
- Spec-pin-to-base diff for the three preflight production paths is empty (`37601232..ad412aec`); the spec's production starting points have not drifted.
- Read the relevant ownership/invariant sections of `CODEBASE_SEGMENTS.md` (segment 18 and privacy routing), `CODEBASE_INVENTORY.md`, `LEGAL_PATHS.md:364-443`, and `ENGINE_INTERACTION_MAP.md:183-197`; source takes precedence over inventory counts.
- Read every hunk in all four changed files, their relevant surrounding code, all six local runner stderr logs and result records, and sibling shared-file diffs.
- Reviewer ran **no builds, tests, lint, static guards, runner actions, or subagents**. Only this review artifact is authored. Production/test source, spec, campaign state, and JOURNAL are unchanged.

### Commit and file inventory

`git log --oneline ad412aec..HEAD` (newest first):

```text
3cbff279 fix: propagate privacy gate cancellation
1fbc1953 fix: fail closed backup exports
```

`git diff --stat ad412aec..HEAD`: 4 files, 249 insertions, 6 deletions.

| Changed file | Diff | Fence / reviewed hunks |
| --- | --- | --- |
| `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | +11 / -4 | ALLOWED. Diagnostic enum import, optional internal-constructor recorder injection, shared export blocking branch. |
| `app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt` | +3 / -0 | ALLOWED. Cancellation import and catch/rethrow before generic conversion. |
| `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` | +137 / -2 | ALLOWED. Six new cases/helper plus optional recorder fixture parameter. No existing test deleted. |
| `app/src/test/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGateTest.kt` | +98 / -0 | ALLOWED. Three focused cases and an in-memory audit recorder. |

### Source citation key

All source line numbers below are at review target `3cbff279`, unless explicitly prefixed **base** or **sibling**. Aliases resolve to these exact files:

- **Repo**: `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
- **RepoTest**: `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt`
- **Gate**: `app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt`
- **GateTest**: `app/src/test/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGateTest.kt`
- **Mode**: `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt`
- **Runner**: `app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceOperationRunner.kt`
- **V**: `build/validation-runs/` in **this worktree**, not the parent checkout.

## 2. Original work-item verdicts

| Spec work item | Verdict | Diff / source evidence and missing completion |
| --- | --- | --- |
| WI-1 — block every export entry point on FailClosed | **DONE-DEFECTIVE** | New `blocksExecution()` at **Repo:613-623** correctly blocks Denied and FailClosed, uses `PRIVACY_DENIED` / `PRIVACY_FAIL_CLOSED`, and returns `PrivacyDeniedException(ENCRYPTED_BACKUP)`. Both overloads use this helper (**Repo:530-571**), before entry/drain at **Repo:626-628**. However, return/throw still runs unconditional `exit(false)` at **Repo:770-772**, violating the spec's prohibition on maintenance-mode writes after blocking. Added assertions **RepoTest:248-255** omit `exit`, hiding the defect. Named tests never executed. |
| WI-2 — propagate cancellation before generic gate conversion | **PARTIAL — implementation correct, gates pending** | New **Gate:29-30** rethrows the exact cancellation before generic catch; generic errors still return `FailClosed(PRIVACY_GATE_FAILURE)` with only class/capability logged (**Gate:31-41**). Cancellation bypasses the audit call at **Gate:69**. **GateTest:25-43**, **47-64**, **68-89** cover the requested cancellation/audit, ordinary failure, and composition cases. No defect found in those changed production lines. Neither successful execution of the named class nor a recorded passed completion/privacy review is available; therefore not DONE-CORRECT. |

### Enumerated test coverage: authored is not executed

| Spec boundary | Actual named class / lines | Assessment |
| --- | --- | --- |
| Allowed file export | `DatabaseBackupRepositoryImplTest`, **RepoTest:132-147** | Asserts successful file creation and maintenance entry; authored, not executed. |
| Allowed SAF export | Same class, **RepoTest:150-173** | Asserts success, emitted bytes, and destination open; authored, not executed. |
| Denied blocks before maintenance | Same class, **RepoTest:176-179**, helper **228-255** | Both overloads, typed failure, no enter/WAL/SAF/file-destination directory. Missing no-exit assertion. |
| FailClosed blocks before maintenance | Same class, **RepoTest:181-184**, helper **228-255** | Same missing no-exit assertion; this is the newly added early-return route. |
| Both overloads / destination never invoked on either blocker | Same helper, **RepoTest:234-255** | Both invoked; SAF open verified zero; file destination directory remains absent. No explicit snapshot-side-effect assertion. |
| Controlled operation reason only | Same class, **RepoTest:186-225** | Exact enum-name strings for both decisions, hostile decision text supplied; file overload tested. Production helper is shared. |
| Gate cancellation propagates unchanged / no audit | `CompositePrivacyGateTest`, **GateTest:25-43** | Exact exception identity and empty audit list; authored, not executed. |
| Ordinary exception fails closed / retains audit | Same class, **GateTest:47-64** | Exact controlled reason and audit decision; authored, not executed. |
| Existing Denied / FailClosed / NotApplicable composition | Same class, **GateTest:68-89** | NotApplicable then Allowed; Denied then Allowed; FailClosed then Allowed. Existing production composition is unchanged. |
| Export boundary still propagates gate cancellation (WI-1 acceptance) | **Repo:765-766**; no repository case added | Rethrow exists, but an end-to-end repository test is missing and the finally still mutates maintenance. Close in WI-4 below. |

No tests were removed, renamed away, disabled, or assertions relaxed by this lane. The recorder seam is optional with the prior no-op default (**Repo:117-131**); production injection is unchanged. The fixture now supplies a relaxed recorder by default (**RepoTest:1059-1082**); runtime compatibility remains unproven, not a demonstrated regression.

## 3. Findings and regression hunt

### F-1 — [P1 / MAJOR] A blocked export still exits maintenance it never entered

**Evidence:** **Repo:613-623, 770-772**; **Mode:136-154, 206-212**; **RepoTest:248-255**.

A Denied/FailClosed return inside the export try necessarily executes its finally. That finally calls `restoreMaintenanceMode.exit(forceRestartRequired = false)`. On this lane, that writes NORMAL to SharedPreferences, publishes NORMAL, schedules workers, and resets the worker stop flag. None of those operations depends on this export having entered maintenance. Thus:

- A blocked export in NORMAL still performs a forbidden maintenance-mode write and reschedules workers.
- If another operation has a restore/recovery barrier active, a rejected export can reset that barrier to NORMAL without owning it.
- Gate cancellation also reaches this cleanup, despite being correctly rethrown.
- Catching cleanup exceptions with `runCatching` does not undo successful state writes.
- The new tests only check zero `enter`; they cannot detect zero-entry/nonzero-exit behavior.

**Attribution:** the unconditional cleanup already exists at **base Repo:763-765**. Denied and gate-throw paths were already affected; this lane introduces an additional early FailClosed return into that existing cleanup. The old FailClosed path was itself unsafe (it proceeded into export). This is a **retained acceptance/legal-path violation**, not evidence that the lane newly introduced the global exit bug. Count it as one blocking completion finding, **not** as a proven new regression.

**Direct fix:** restrict this helper's maintenance cleanup region to the post-privacy maintenance phase. Do not change Mode, restore/import handling, or shared runner behavior. See WI-3/WI-4.

### F-2 — [P2 / MAJOR completion gap] Neither specified test class has executed

**Evidence:** **V/vr-20260923-093113-0f25d265/result.json:10,20-25** and **stdout.log:39-42**; **V/vr-20260923-091700-37613719/result.json:10,20-25** and **stderr.log:1-17**.

Every targeted attempt stopped before the unit-test task ran. The only recorded PASS is production Kotlin compile; it cannot establish test behavior. The five remaining compile blockers are independently traced to the base in section 5. Do not hide them by excluding test sources, weakening checks, or editing outside this spec. See WI-5.

### New-code regression/privacy checklist

- **Confirmed new regressions: 0.** F-1 is a retained defect; F-2 is missing evidence.
- **Out-of-fence changed files: 0.** No worker, DAO, schema, migration, privacy-mode, cloud-routing, or UI change.
- **Raw exception/decision-text leakage newly added: 0 sites** in the two production-file additions. The previous `encryptedDecision.reason` interpolation is removed. The new operation reason is an enum name; it passes no throwable.
- **New cancellation swallowing: 0 sites.** The composite change restores propagation. Existing repository cleanup and adjacent raw-throwable logging are not newly added lines; their existence is not misreported as a new regression.
- **Reason mapping:** Denied -> `DiagnosticReasonCode.PRIVACY_DENIED.name`; FailClosed -> `DiagnosticReasonCode.PRIVACY_FAIL_CLOSED.name` (**Repo:615-619**). Ordinary composite failure -> `PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE` (**Gate:39**). All match CL-18.
- **WorkerExecutionGuard:** unchanged; no new bypass or policy branch found. F-1 can indirectly release its maintenance barrier, which is why it blocks completion.
- **Test weakening:** none observed. The early experimental GateTest inference errors in **V/vr-20260923-085854-5d2258c0/stderr.log:7-9** disappear in later logs and do not match the committed **GateTest:33**. Do not carry them forward as a current defect.
- **R2 mechanical sweep:** not applicable: this lane is CL-18/rp-20, not CL-17/rp-21. No claim is made that all 16 CL-17 files are clean; the zero count above is explicitly for rp-20's production additions.

## 4. Existing validation evidence (read-only audit)

All six directories contain `complete.marker`; their result records are terminal. Commands share the recorded suffix:

```text
--console=plain --no-parallel --max-workers=1 --no-daemon -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50
```

| Run under V | Recorded command before suffix | Result / exit | What actually happened |
| --- | --- | --- | --- |
| `vr-20260923-084247-14182b9b` | `gradlew.bat :app:testDebugUnitTest --tests *DatabaseBackupRepositoryImplTest` | FAIL / 1 | Wrapper lock access denied; **stderr.log:1-6**. No tests. |
| `vr-20260923-084918-85aa1316` | Same repository filter | FAIL / 1 | Android SDK location unavailable; **stderr.log:5-6**. No tests. |
| `vr-20260923-085854-5d2258c0` | Same repository filter | FAIL / 1 | Unit-test Kotlin compilation fails; five external files plus then-uncommitted GateTest inference errors; **stderr.log:1-20**. |
| `vr-20260923-091700-37613719` | `gradlew.bat :app:testDebugUnitTest --tests *CompositePrivacyGateTest` | FAIL / 1 | Five external-file compilation failures remain; **stderr.log:1-17**. No class execution. |
| `vr-20260923-092325-30b2b632` | `gradlew.bat :app:compileDebugKotlin` | PASS / 0 | Production compile only; **stdout.log:23-25** is UP-TO-DATE / BUILD SUCCESSFUL. |
| `vr-20260923-093113-0f25d265` | Repository filter above | FAIL / 1 | Exact reviewed HEAD, clean-diff fingerprint; **result.json:2,20-25**. Fails at `compileDebugUnitTestKotlin`, **stdout.log:39-42**. |

The first five records say `git_revision = ad412aec` while carrying nonempty worktree fingerprints: they describe pre-commit working-tree states, **not** a clean-base validation run. The last record is pinned to reviewed HEAD. Base attribution below therefore uses source/blob comparison, not a misleading interpretation of earlier run revisions.

No JUnit result files were found under this worktree's `app/build/test-results`. Conclusion: both named classes are **NOT RUN at the test-execution level**, notwithstanding recorded FAIL attempts. Reviewer validation: **NOT RUN (prohibited by assignment)**.

## 5. Five out-of-fence compile failures: decisive base attribution

Although the special R3 state-machine audit belongs to rp-22, these same five errors block rp-20, so each was investigated. Every listed test and relevant production contract has an **identical Git blob at ad412aec and reviewed HEAD**. The base source was read with `git show ad412aec:<path>`; no checkout or source edit occurred.

Compile-error references below all use **V/vr-20260923-093113-0f25d265/stderr.log**.

| Test / verdict | Compiler evidence | Base source comparison |
| --- | --- | --- |
| `app/src/test/java/com/yourname/expensetracker/data/privacy/KeystoreInstallationSecretHashingTest.kt` — **PRE-EXISTING** | Log **1-4**: test **196:77**, **208:32**, **209:31**: “Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'”; **199:36**: actual `String?`, expected `CharSequence`. | Base test **195-209** uses nullable `merchantField.value` without narrowing. Base/current `app/src/main/java/com/yourname/expensetracker/domain/privacy/CloudPayloadRedactor.kt:49-51` declares `RedactedField.value: String?`. No changed lane symbol causes this. |
| `app/src/test/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyCheckerTest.kt` — **PRE-EXISTING** | Log **5-6**, test **74:13**: `FakeReceiptEventDao` “does not implement abstract member: suspend fun deleteOlderThan(beforeMs: Long): Int”. | Base fake **74-83** only implements read/insert. Base/current `app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptEventDao.kt:22-23` already requires deletion. Neither file changed. |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt` — **PRE-EXISTING** | Log **7**, test **202:13**: “No parameter with name 'privacySettingsRepository' found.” | Base test **181-208** passes that named argument. Base/current `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:99-137` omits it and uses `rawPersistencePolicyResolver`. Neither file changed. |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` — **PRE-EXISTING** | Log **8-9**, test **189:19/55**: actual `Result<Unit>`, expected `Result<DatabaseImportResult>`; `Success` “does not have a companion object, so it cannot be used as an expression.” | Base test **189** already uses `Result.success(DatabaseImportResult.Success)` as an object. Base/current `app/src/main/java/com/yourname/expensetracker/domain/backup/DatabaseOperationResults.kt:14` requires `Success(summary)`. The backup interface/result contract is unchanged; rp-20's internal constructor recorder is unrelated. |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModelPrivacyDenialTest.kt` — **PRE-EXISTING** | Log **10**, test **75:13**: “No value passed for parameter 'exportJobSerializer'.” | Base test **66-76** omits it. Base/current `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt:75-86` already requires it. Neither file changed. |

**Outcome: 5 PRE-EXISTING / 0 REGRESSION.** This is a source-supported attribution, not a claim that a clean-base build was run. Their repairs require separately authorized ownership; none of these five test files is inside CL-18's fence.

## 6. Cross-lane flags — human merge order rp-20 -> rp-21 -> rp-22

Ran `git diff --name-only ad412aec..rp-2X-wip` for all four sibling W1 lanes and read the shared repository hunks. Ref snapshot:

| Sibling | Reviewed tip | Intersection with rp-20's four files |
| --- | --- | --- |
| rp-21-wip / CL-17 | `d27483124f4e9a3989496d2a51fde167234c12c8` | Repo only |
| rp-22-wip / CL-19 | `d3e8fa25689b76f67abcfa1ad55c3e5c517e3e4e` | Repo only |
| rp-23-wip / CL-01 | `0b955ec1e2f06f371a98cd0ba4162dd0c3eb8595` | None |
| rp-24-wip / CL-29 | `cbb0a54eff6a2d5bae1327e7c82501b8d1ba25d9` | None |

1. **Repo shared by rp-20/21/22:** preserve rp-20's shared privacy preflight, enum mapping, optional test recorder seam, and eventual export-only cleanup fix. CL-17 adds the same DiagnosticReasonCode import plus bounded restore/reset logging; avoid duplicate imports or overwriting the privacy branch during conflict resolution.
2. **rp-21 + rp-22 overlap in Repo restore/reset catches:** CL-17 replaces throwable/decision-text diagnostics; CL-19 adds JournalDurabilityException branches and pre-destructive journal transitions. Both must survive; selecting an entire side can restore raw logging or discard fail-closed durability handling. This is a semantic merge warning, not a proposed rp-20 edit.
3. **CL-19 changes Mode.exit(false):** its sibling diff delays in-memory NORMAL publication until after scheduling, introduces persistence exceptions, and makes critical recovery sticky. rp-20's existing unconditional export-finally can call this without entry and swallows exceptions; a successful export can also have an exit failure that is hidden. Coordinate the export-only fix with CL-19; do not assume a textually clean merge proves behavior.
4. **R3-specific risk ownership:** persisted-NORMAL/process-death during scheduling, present-but-null modes, post-destructive rollback/import paths, and scheduling before NORMAL observation remain CL-19 review responsibilities. The sibling Mode diff shows the persisted-before-schedule ordering and retained null fallback; no rp-20 repair is authorized. `DatabaseBackupRepositoryImplTest` **is** updated by rp-20 but absent from rp-22's diff, so human integration must reconcile CL-18 tests with CL-19 behavior rather than treating this lane's assertions as restore-state coverage.

No merge/cherry-pick or cross-lane source modification was performed.

## 7. DIRECT-FIX completion plan

Continue original numbering after WI-2. Execute the following in order, one bounded batch at a time. This is a completion plan, not permission to expand the original fence.

### WI-3 — Isolate preflight rejection/cancellation from export maintenance cleanup (F-1, P1)

- **Location at CURRENT HEAD:** **Repo:595-628, 765-772**; **RepoTest:176-255**. These are the shared .costbackup export helper and its named fixture, not restore/import/reset.
- **Defect:** privacy returns/throws are inside the scope of an unconditional maintenance exit.
- **Change:** place the maintenance-phase try/finally after the nonblocking privacy preflight, or make an equivalently local ownership distinction so no preflight rejection/throw invokes maintenance cleanup. Keep typed blocking results, exact enum reasons, gate cancellation propagation, and both overloads through one helper. Preserve the existing allowed-path entry/drain and cleanup behavior, including exceptions during entry/drain; do not move cleanup so an already-entered barrier is accidentally stranded. Do not rewrite shared Mode/Runner or restore/import state handling.
- **Acceptance criteria:** Denied, FailClosed, and a gate-thrown CancellationException never call either `enter` **or** `exit`; no mode write/scheduling, WAL query, snapshot, file bundle, or SAF destination occurs. Existing barrier state is untouched. Denied/FailClosed remain typed ENCRYPTED_BACKUP failures with controlled operation reasons. Allowed file/SAF success remains possible with proper cleanup.
- **Tests:** strengthen the two existing blocker cases in `DatabaseBackupRepositoryImplTest` to assert zero exit on both overloads. Include representative existing non-NORMAL/recovery state without editing the state manager; a mock can prove absence of mutation. Add repository boundary cancellation coverage as specified in WI-4. Preserve all existing allowed and reason-code cases.
- **Validation profile:** `targeted-unit-test -TestFilter "*DatabaseBackupRepositoryImplTest"` via validation-runner after strict/privacy review and the external prerequisite in WI-5. No standalone Gradle.
- **Fence:** only Repo and RepoTest. If a fix requires changing shared maintenance semantics, stop for architecture/human review instead of crossing into CL-19.

### WI-4 — Close exact spec boundary-test gaps without weakening the fixture (P2)

- **Location at CURRENT HEAD:** **RepoTest:132-255, 1059-1082**; **GateTest:25-89**.
- **Change:** preserve the six new repository tests and three composite tests. Add explicit no-snapshot/no-encryption side-effect evidence to both blocking decisions using controlled fixture directories/call observations (do not rely only on the final output directory). Exercise gate cancellation through both repository overloads, assert the same exception, and assert zero enter/exit/WAL/destination work. Maintain exact reason-only operation assertions; do not accept arbitrary nonempty strings or catch-all failures.
- **Acceptance criteria:** every original test-table row above has explicit coverage in the named classes; blocker/cancellation assertions detect F-1 before the fix. No source exclusions, suppressed tests, loosened asserts, renamed-away filters, or alternative surrogate classes. Existing GateTest ordinary-failure/audit and composition cases remain intact; do not rewrite correct production gate behavior.
- **Tests:** `DatabaseBackupRepositoryImplTest` and `CompositePrivacyGateTest`, exactly. Keep exact exception identity/no audit for cancellation; controlled failure reason/audit for ordinary exceptions; existing Denied/FailClosed/NotApplicable behavior. Repository cancellation covers WI-1's explicit cross-boundary acceptance, not a new feature.
- **Validation profile:** serial `targeted-unit-test` runs for each named class via validation-runner, subject to WI-5. A compiled class without test-case execution does not satisfy acceptance.
- **Fence:** focused named tests only, plus the already approved Repo fix in WI-3. Do not edit unrelated fixture contracts.

### WI-5 — Obtain real named-class execution and final compile evidence (P2; external prerequisite)

- **Location at CURRENT HEAD:** runner records in section 4; named tests above; original spec Validation and Review gate.
- **External prerequisite:** the five base-stale test files in section 5 prevent `:app:compileDebugUnitTestKotlin` for either target filter. Escalate them to the human/owning completion lane for a separately approved prerequisite repair. **Do not fix them inside CL-18, cherry-pick on assumption, exclude them from compilation, or change Gradle/guards/baselines to sidestep them.** If unresolved, deliver the in-fence fixes as conditional and stop; execution remains blocked.
- **Change:** after WI-3/WI-4 and an approved prerequisite are present, obtain strict review and the spec's privacy-security-guardian gate before execution. Then have the sole validation-runner owner run the serial commands below from this worktree. Check for an active validation first; a RUNNING record must be polled, not restarted. Ensure SDK access is configured for the runner. Record actual revision/fingerprint, result, command, exit code, log directory, completion marker, and named-class test counts.
- **Acceptance criteria:** both actual named classes execute with nonzero test counts, no relevant skipped cases/failures, and durable PASS records; then production compile PASS. Failed, missing, stale, timed-out, or infrastructure-error records are not PASS. Repeat strict/privacy review if runtime evidence requires further changes. Only then reconsider original WI verdicts.
- **Tests / validation profiles (instructions only; reviewer did not execute):**

```powershell
# Run serially; wait for the terminal result of each before starting the next.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter "*DatabaseBackupRepositoryImplTest"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter "*CompositePrivacyGateTest"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
# For a RUNNING result, use the returned run ID:
# powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId <returned-run-id>
```

- **Fence:** no source changes are authorized by this validation WI. No broad unit-suite/app-check/static-guard run is added to the original spec's required profiles.

### Unchanged blast-radius fence

ALLOWED: Repo, Gate, `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacyDecision.kt` only if a type-level helper is strictly required, and the focused named tests. No helper change is needed for the identified fix.

FORBIDDEN: RestoreMaintenanceMode, RestoreJournal, DatabaseWriteBarrier, restore/import state handling (CL-19); image/privacy-mode contract (CL-20); cloud routing (CL-21); UI-only privacy comparisons; unrelated providers/workers; the five external test files; schema/migrations; guard/baseline weakening. Cross-lane flags do not broaden this fence.

## 8. Delivery / remaining risk

- Artifact: `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-review-completion.md`.
- Delivery is a docs-only commit on rp-20-wip; the actual commit SHA is returned after committing (the reviewed implementation SHA remains pinned above).
- No stray coder report exists in this worktree to include. No source or test edit, validation execution, or JOURNAL modification by this reviewer.
- Remaining risk: retained preflight maintenance release, unexecuted runtime paths, base test-compilation blockers, and shared-file/state semantics during rp-20 -> rp-21 -> rp-22 integration.
- Journal line is returned to the human separately. Do not mark this lane DONE/GREEN based on the compile-only PASS.

