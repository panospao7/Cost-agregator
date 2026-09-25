# CL-18 re-review-completion — rp-20 / RR1

Date: 2026-09-25 (+03:00). Reviewer: astra high.
Reviewed implementation HEAD: `577c9e56d344b65527def875d149aca53f34c036`.
Completion delta: `86e7f244bbc9b99ef9458656787b9de2532f7d1a..577c9e56d344b65527def875d149aca53f34c036`.
Governing document: `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-review-completion.md`, read in full and left unchanged. Its WI-3/WI-4/WI-5 continuation requirements govern this review; the original CL-18 implementation spec is background, not a replacement completion checklist.

**VERDICT: FAIL — not mergeable. 0/4 requested plan items DONE-CORRECT; 1 confirmed delta-introduced functional regression.** The original preflight `exit(false)` defect is removed in source, but moving preflight outside the entire exception handler introduces R-1. Required boundary tests remain incomplete and execution evidence is not bound to the reviewed completion state. One additional, behavior-neutral test-file change is outside the approved fence.

## 1. Environment and review boundaries

- Worktree verified: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-20`.
- Branch: `rp-20-wip`; `git merge-base --is-ancestor ead80016 HEAD` succeeded.
- Initial `git status --porcelain` was empty; it was rechecked after the interruption and immediately before authoring this artifact. HEAD remained the implementation SHA above.
- The rp-25 test-compilation repairs and `docs/testing/generated/TRIAGE-2026-09-test-recovery.md` are already in the ancestor base. The first review's five-file compilation prerequisite must not be carried forward as an unresolved prerequisite.
- Read the scoped segment/inventory ownership, `LEGAL_PATHS.md:364-443`, `ENGINE_INTERACTION_MAP.md:183-197`, all three delta files' full diffs, relevant surrounding implementation/tests/callers, persisted validation records, and the shared-file sibling hunks. No matching CL-18/rp-20 handoff or passed current guardian report was found in the scoped active-handoff/campaign material.
- **No subagents, validation-runner actions, builds, tests, lint, or static guards were run.** This is source review plus read-only inspection of already-existing logs. The only authored file is this new artifact. No production/test source, first review, JOURNAL, or other campaign file is changed by the reviewer.
- Line references below are at the reviewed implementation HEAD unless explicitly labeled baseline, historical log, or sibling. The artifact-delivery commit is separate and is returned after the docs-only commit.

### Delta inventory

`git log --oneline 86e7f244..HEAD`:

```text
577c9e56 fix: complete CL-18 review remediation
```

`git diff --stat 86e7f244..HEAD`: **3 files, 33 insertions, 21 deletions**.

| File | Exact delta | Fence / hunk assessment |
| --- | --- | --- |
| `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` | +20 / -20 | In fence. Moves privacy preflight before the maintenance-phase try/catch/finally. Fixes preflight cleanup ownership but also removes ordinary-preflight exception conversion. |
| `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt` | +1 / -0 | In fence. Adds only `verify(exactly = 0) { mockRestoreMaintenanceMode.exit(any()) }` to the existing shared blocking helper. No new test method. |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` | +12 / -1 | **Outside fence.** Imports `DatabaseImportSummary` and expands an already-correct success fixture. Equivalent values and assertions; not a new compilation repair or behavioral fix. |

### Source citation key

- **Repo**: `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
- **RepoTest**: `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt`
- **Gate**: `app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt`
- **GateTest**: `app/src/test/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGateTest.kt`
- **Audit**: `app/src/main/java/com/yourname/expensetracker/data/privacy/PrivacyAuditLoggerImpl.kt`
- **PrivacyDI**: `app/src/main/java/com/yourname/expensetracker/di/PrivacyModule.kt`
- **VM**: `app/src/main/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt`
- **VMTest**: `app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt`
- **Mode**: `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt`
- **MaintenanceRunner**: `app/src/main/java/com/yourname/expensetracker/data/backup/MaintenanceOperationRunner.kt`
- **RunRecorder**: `app/src/main/java/com/yourname/expensetracker/domain/diagnostics/OperationRunRecorder.kt`
- **V**: this worktree's `build/validation-runs/`, not the parent checkout.

## 2. Per-plan-work-item verdicts

The denominator is the four requested items: WI-1 fix, WI-3, WI-4, WI-5. A source-level correction is distinguished from completion of its required tests and gates.

| Plan item | Verdict | Current-HEAD evidence / remaining acceptance |
| --- | --- | --- |
| **WI-1 fix — remove the retained preflight maintenance release** | **PARTIAL** | **The specific first-review F-1 defect is fixed in source.** Both overloads still share the helper (**Repo:530-571**). Denied/FailClosed return at **612-622**, and a gate-thrown cancellation at **608-611** propagates, before the cleanup scope begins at **624**. The `exit(false)` statement remains at **770-771**, but is no longer reachable from those preflight returns/throws. Exact typed failure and enum mappings survive. Full endpoint completion remains unearned: missing WI-3/WI-4 boundary coverage, no current-state-bound execution record, and R-1. |
| **WI-3 — isolate preflight from maintenance cleanup** | **DONE-DEFECTIVE** | The intended cleanup isolation and new zero-exit assertion are present (**Repo:604-624; RepoTest:253**). Allowed entry/drain still occurs inside the cleanup region (**Repo:624-628, 770-771**), preserving cleanup reachability for entry/drain exceptions. However, the relocation also moves the production privacy call outside ordinary exception-to-`Result.failure` handling, introducing **R-1**. Representative pre-existing recovery-state and repository cancellation tests required by governing review **170-178** are absent. |
| **WI-4 — close exact boundary-test gaps** | **PARTIAL** | The six existing repository privacy cases and three composite cases remain; the shared blocker helper gains a zero-exit assertion. No repository cancellation case, explicit no-snapshot/no-encryption observation, or blocked/cancelled export in an existing non-NORMAL state is added. **RepoTest:176-256, 1060-1084** still uses the old fixture and only final-output-directory absence. **GateTest:25-89** is unchanged and retains exact cancellation identity/no audit, ordinary failure/audit, and composition assertions. |
| **WI-5 — named-class execution, review gates, final compile** | **PARTIAL** | Historical named-class execution **does exist**: 36 repository tests and 3 composite tests passed on September 24 in the records detailed below. They reference old review revision `a4469a57` plus an unarchived dirty-worktree fingerprint, not the reviewed clean/rebased HEAD. Missing boundary cases could not have been executed. No post-remediation/current-state final compile-profile PASS or passed current privacy-security-guardian gate was found. The rp-25 compilation prerequisite is already merged; serial named-class runs and final compile remain actionable, not externally compilation-blocked. |

**Original WI-2 context (not a fifth counted item):** no delta changes to Gate/GateTest. **Gate:29-30** still rethrows the exact cancellation before generic conversion; **31-41** retains controlled fail-closed conversion and **69** audits non-cancelled decisions. Its three authored cases have historical execution evidence, but this does not waive current completion/review gates.

### Required coverage versus actual authored tests

| Boundary | Evidence at HEAD | Assessment |
| --- | --- | --- |
| Allowed file and SAF export | **RepoTest:133-173** | Both success paths retained; file case checks entry, SAF checks emitted bytes/open. Neither adds an explicit successful-export exit assertion. |
| Denied and FailClosed, both overloads | **RepoTest:177-183, 228-256** | Typed failure, zero enter, **new zero exit**, zero WAL query, zero SAF open, absent final output directory. New assertion would detect the old F-1 call in either blocking case. |
| Pre-existing recovery/non-NORMAL state untouched | Blocking helper **228-256** and setup **104-124** | No representative state is seeded or asserted for blocked/cancelled export. Unrelated restore-asset mode stubbing is not this coverage. |
| Gate cancellation through both repository overloads | RepoTest method/import scan; **228-256** | **Absent.** GateTest's direct composite cancellation test cannot substitute for crossing the repository boundary. |
| No snapshot and no encryption for each blocking decision | **RepoTest:228-256** | **Explicit evidence absent.** No controlled cache/snapshot observation or encryption/bundle invocation assertion; absent `costbackups` alone does not prove a snapshot was never created and cleaned. |
| Controlled operation failure reason | **RepoTest:187-225** | Exact `PRIVACY_DENIED` / `PRIVACY_FAIL_CLOSED` assertions retained with hostile input reason. |
| Composite cancellation identity/no audit; ordinary failure/audit; composition | **GateTest:25-89** | All three cases preserved, historically executed; unchanged by delta. |
| Ordinary preflight failure remains a returned failure | New exposure at **Repo:608-624** | No focused regression test; add for R-1 in the named repository class. |

Current named-class method counts are **36 RepoTest / 3 GateTest**. No tests/assertions are deleted, ignored, loosened, or renamed away by the delta; the new assertion strengthens coverage but does not complete WI-4.

## 3. Regression findings and completion gaps

### R-1 — [P1 / MAJOR] Ordinary privacy-preflight failures now escape the repository result contract

**Changed lines:** **Repo:608-624**. **Supporting unchanged call chain:** **PrivacyDI:77-103; Gate:27-41, 69; Audit:26-60; VM:100-145; RunRecorder:89-111**.

At baseline `86e7f244`, the privacy call was inside the export `try`; an ordinary exception reached the catch, recorded `BACKUP_EXPORT_FAILED`, and returned `Result.failure(e)`. At reviewed HEAD, the call is before that try. The catch at **Repo:765-769** cannot see any exception from it.

This is reachable with the production dependency graph, not just an arbitrarily throwing test double: PrivacyDI supplies CompositePrivacyGate with PrivacyAuditLoggerImpl; the composite's final `auditLogger.logDecision(...)` is outside its delegate catch, and Audit performs `dao.insert(...)` without an exception wrapper. For example, a non-cancellation SQLite audit-write failure can escape even when the delegate produced a normal privacy decision.

Consequences of that trigger:

1. Both repository overloads throw an ordinary exception instead of returning their previous failure result. They still perform no backup destination work; this is not a fail-open export finding.
2. The already-started `BACKUP_EXPORT` operation no longer reaches this helper's terminal failure call, leaving its started run without that finalization. RunRecorder's `start` creates a RUNNING row; this helper does not use `runOperation`.
3. The actual SAF caller sets `isBackingUp = true`, then invokes the repository directly in `viewModelScope.launch`. There is no surrounding catch/finally. The error bypasses `result.fold` and its busy-state reset, escaping the launch as an unhandled failure; if the process survives an outer handler, the busy state remains set.

**Attribution:** introduced by `577c9e56` moving the preflight outside the *catch as well as the finally*. The audit implementation and unguarded caller pre-existed; their formerly handled failure is what becomes newly exposed. Count this as **one** regression, not three separate issues. This is a source-proven failure path; the reviewer did not execute a reproducer.

**Minimal remaining fix:** keep preflight outside maintenance cleanup but retain ordinary-error conversion/finalization around it, with cancellation rethrown unchanged. An outer error boundary with an inner maintenance-only finally, or a small equivalent preflight error boundary, can preserve both contracts. New diagnostics must use a controlled reason/class only, not exception messages, decision text, or throwable logging. Do not reintroduce unconditional preflight exit or modify Mode/restore state handling. Add a repository test for an ordinary gate/audit failure on both overloads, checking returned failure, bounded terminal recording, and zero maintenance/snapshot/encryption/destination work.

### G-1 — [P2 / MAJOR completion gap] WI-3/WI-4 required boundary coverage is still missing

The actual test delta is one assertion, not the continuation plan's cancellation and side-effect test batch. Evidence and exact missing rows are in section 2. This is incomplete planned work, not an additional functional regression. In particular, mock absence of entry/exit in default setup is not the requested representative recovery-state case.

### G-2 — [P2 / MAJOR completion gap] Historical PASS is not current completion certification

Section 4 records real historical execution without discarding it or mislabeling it compile-only. It cannot prove execution of absent tests or supply a source-bound final gate for the rebased completion. Missing current strict/privacy acceptance and the final compile remain required. This is not a new runtime regression.

### S-1 — [P3 / MINOR scope violation] Redundant out-of-fence UI test formatting

**VMTest:8, 194-208**, compared with baseline `86e7f244`, imports/expands an already-instantiated `DatabaseImportSummary` with the same five zero counts. The compile repair is already present through `9e8353bf` in ancestor `ead80016`. No assertion changes and no behavioral regression are demonstrated. Nevertheless, the governing WI-3/WI-4 fence excludes this file; the first review explicitly placed it in the external-prerequisite group, not in the CL-18 remediation fence. Remove only this redundant completion-delta hunk, or obtain an explicit human exception. **Do not undo rp-25's actual summary/cache-directory/input-stream repairs.**

### Delta-only safety checklist

- **Confirmed functional regressions: 1 (R-1). Out-of-fence files: 1 (S-1).** Missing tests/evidence are counted separately, not inflated into regressions.
- No new raw decision reason, password, URI, path, financial payload, exception-message interpolation, or throwable logging call appears in the added production lines. The moved bounded Denied/FailClosed reason mapping is preserved.
- No new cancellation-swallowing branch is added. Gate cancellation now avoids maintenance cleanup entirely; the maintenance-phase cancellation rethrow remains. Existing raw-throwable logging/cleanup suppression outside the delta is not mislabeled newly introduced.
- No worker/guard/schema/DAO/migration/Room-version change, test-source exclusion, baseline broadening, or weakened assertion in the delta.
- The old preflight `exit(false)` bug is **not** still present for rejection/cancellation just because the same finally statement still exists. The changed scope, not statement deletion, removes that route.
- The rp-25 triage document's backup/restore UI denial-contract failures are baseline findings, not caused by this delta's equivalent VMTest formatting. They remain ownership/guardian context, not new rp-20 regressions.

## 4. Existing execution evidence — read, not run

The four September 24 local records were inspected together with their request files, result records, completion markers, and relevant stdout/stderr. All four have `complete.marker`. The successful named-class logs contain actual per-test PASSED lines; they are not merely compiler success messages.

| Local run under V | Recorded task/filter | Recorded status / exit | Execution evidence / limitation |
| --- | --- | --- | --- |
| `vr-20260924-062322-fd135633` | `:app:testDebugUnitTest --tests *DatabaseBackupRepositoryImplTest` | FAIL / 1 | Wrapper lock access denied, stderr; no class execution in this attempt. |
| `vr-20260924-065650-eb318370` | Same repository filter | **PASS / 0** | **36 PASSED, 0 FAILED/SKIPPED test-case lines**, stdout **1840-1910**; includes both blockers, both allowed destinations and exact reason-code tests. No WI-4 cancellation cases exist in this inventory. |
| `vr-20260924-071038-124d529e` | `:app:testDebugUnitTest --tests *CompositePrivacyGateTest` | **PASS / 0** | **3 PASSED, 0 FAILED/SKIPPED test-case lines**, stdout **50-54**; test task executed. |
| `vr-20260924-071559-4de5294d` | `:app:testDebugUnitTest --tests *KeystoreInstallationSecretHashingTest` | FAIL / 1 | 18 tests completed, 5 failed; unrelated class, not either CL-18 named gate. Does not erase the earlier passes or establish current completion. |

All four records identify revision `a4469a5786f59451f9f40aaea95a7a7138eadfb0` (the old first-review commit) and equal start/end dirty-worktree fingerprints `e83a351712633c81b2cbe546295977c9ad750ba9ab805b0d44da7338428f5671`. The runner hashes changed/untracked file bytes (**scripts/validation-runner.ps1:203-227**), so the recorded revision alone does **not** mean a clean `a4469a57` was tested. Conversely, the opaque hash with no archived changed-file manifest/patch does not prove the reviewed clean HEAD was the tested state. The requests contain filters, not the source snapshot.

Comparing that old revision with reviewed HEAD shows the repository helper relocation and new zero-exit assertion; Gate/GateTest blobs are unchanged in that comparison. The records are therefore acknowledged as historical named-class execution, not silently relabeled FAIL or runner `STALE_RESULT`, and not promoted to a current source-bound PASS. Matching test names cannot establish which version of their helper ran.

The older September 23 records include a production-compile PASS (`vr-20260923-092325-30b2b632`, exit 0) and failed named-test compilation attempts already analyzed in the first review. That production compile predates remediation and is **not** named-test execution or the required final post-test compile. No later compile-profile record was present among the ten local run directories. The only retained local JUnit XML was the later Keystore result; earlier named-class evidence is in the durable logs above.

**Why current acceptance execution is still absent:** missing WI-3/WI-4 cases, no persisted current revision/fingerprint linkage for the historical passes, no current passed privacy gate, and no final current compile profile. It is **not** justified by the old five-file compilation blockade: `ead80016` already contains the authorized rp-25 repairs and triage. This reviewer is prohibited from running validation; a later coder/validation-runner must supply the remaining evidence.

## 5. Cross-lane flags — preserve merge order rp-20 → rp-21 → rp-22

Sibling refs inspected read-only: `rp-21-wip` at `18d2551a7ee322a9be90040007496cb173e43aa9`; `rp-22-wip` at `2e8bf205b4f227cbebb8d13e3d903e58124ce5ed`. These are snapshots for interaction warnings, not approvals of sibling lanes.

1. **rp-20 → rp-21, shared Repo export helper:** rp-21 changes the export catch to bounded class-only logging. Preserve that sanitization when restoring the R-1 error boundary, and preserve rp-20's shared `blocksExecution`, exact reason codes, test recorder seam, and maintenance-only finally. A textually clean merge still leaves R-1 if nobody restores preflight error conversion. Avoid duplicate DiagnosticReasonCode imports and whole-side replacement of the helper.
2. **rp-21 → rp-22, shared Repo restore/reset catches:** rp-21 sanitizes reset/restore diagnostics; rp-22 introduces JournalDurabilityException handling and pre-destructive journal transitions. Retain both behaviors; selecting one side wholesale can restore raw logging or discard durability handling. These restore/import paths remain outside this lane's edit authority.
3. **rp-22 changes the meaning/failure modes of Mode.exit(false):** sibling **Mode:168-205, 268-287** persists NORMAL before scheduling/publication and can throw for persistence or scheduling failures; critical recovery is sticky. rp-20 still has `runCatching { exit(false) }` after a successful result/terminal success (**Repo:760-771**). On integration, an exit failure can remain hidden while the barrier stays locked. This is a shared-state integration risk, **not a second regression introduced by this rp-20 delta**. The no-entry/no-exit preflight guarantee must survive integration; coordinate allowed-path cleanup-failure semantics with CL-19 ownership rather than changing Mode here.

No merge, cherry-pick, cross-lane source edit, or attempt to resolve these flags was performed.

## 6. Exact remaining steps to make the lane mergeable

1. **Repair R-1 locally in Repo:** preserve ordinary preflight `Result.failure`/bounded operation finalization while keeping cancellation unchanged and preflight entirely outside maintenance cleanup. Preserve cleanup for already-entered/entry-drain failure paths. No Mode, restore/import, audit implementation, or ViewModel redesign is authorized.
2. **Complete WI-3/WI-4 in the named tests:** keep the six existing repository cases and three composite cases; exercise both overloads with the same gate cancellation and exact identity; assert zero enter/exit/WAL/snapshot/encryption/file/SAF work. Cover representative existing recovery state, explicit no-snapshot/no-encryption for Denied and FailClosed, and proper allowed-path cleanup. Add R-1 ordinary preflight failure coverage with controlled diagnostics. Do not weaken assertions, exclude sources, substitute classes, or repair unrelated baseline behavior inside CL-18.
3. **Remove/explicitly authorize S-1:** limit the remedy to the redundant VMTest completion-delta formatting, preserving all merged rp-25 repairs.
4. **Obtain passing strict review and the named privacy-security-guardian gate on the completed delta before validation.** This rereview is FAIL, not that approval. The no-subagent/no-validation constraints applied to this review session; they are not a waiver of the governing completion gates.
5. **Have the sole validation-runner owner execute the exact required profiles serially from this worktree.** First check active validation/global lock and SDK availability; poll any RUNNING result rather than restarting or overlapping. Commands below are instructions for that later owner and were **not executed by this reviewer**:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action List

# Start the next command only after the preceding run is terminal PASS.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter "*DatabaseBackupRepositoryImplTest"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter "*CompositePrivacyGateTest"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile

# After each Start that reports RUNNING, substitute its real run ID; repeat Wait as needed.
# powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId <returned-run-id>
```

6. **Record acceptance evidence:** actual revision and worktree fingerprint; exact command/filter; terminal status and exit code; durable result/stdout/stderr/complete.marker paths; nonzero named-class test counts including the added cases with zero relevant failures/skips. Current RepoTest must contain the new cases, not just the historical 36-method inventory. A compile PASS is separate and must follow the named executions. Missing, stale, timed-out, infra-failed, or unexecuted results cannot be GREEN. Repeat review if validation drives edits. No broad unit suite, app-check, or static-guard profile is newly required by this artifact.

If a merged-base behavioral failure surfaces, preserve its assertion and record a precise baseline/ownership comparison; do not revive the obsolete compilation-blocker claim or silently waive a named-class failure. Cross-lane integration must retain the guarantees in section 5 and be reviewed on the integrated source.

## 7. Delivery

- Files touched by this reviewer: only `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-rereview-completion.md`.
- Source/test changes by reviewer: none. Validation execution by reviewer: **NOT RUN — prohibited**. Existing executions are explicitly attributed in section 4.
- First review artifact and JOURNAL remain unchanged. The docs-only lane commit SHA and the requested journal line are returned after commit verification; the reviewed implementation remains pinned above.
- Remaining blockers: R-1, incomplete WI-3/WI-4 coverage, current-state execution/review/final-compile gates, and the out-of-fence hunk. Shared-file merge risks are flags, not unauthorized repairs or extra regression counts.
