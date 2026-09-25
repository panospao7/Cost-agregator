# CL-18 implementation report — `rp-20-wip`

Date: 2026-09-23
Spec: `wave1/CL-18-privacy-gate-fail-closed-cancellation.md`
Session: direct implementation; no subagents

## Verdict

**Implementation complete (2/2 work items); validation incomplete; not yet merge-ready.** Production compilation passed, but targeted tests never executed: whole-test-source Kotlin compilation fails on unrelated, out-of-fence tests. Strict review and the spec's privacy-security guardian gate have not run. Do not treat the targeted tests as passing.

## Work items

| Item | Status | Changes |
|---|---|---|
| WI-1 | Done; tests unexecuted | Both `createCostBackup` overloads still delegate to `runCostBackupExport`. Its single privacy check now calls `blocksExecution()`; Denied and FailClosed terminate before maintenance/WAL/snapshot/destination work, return the existing typed `PrivacyDeniedException(ENCRYPTED_BACKUP)`, and finalize the operation run using only `PRIVACY_DENIED` or `PRIVACY_FAIL_CLOSED`. A test-only repository constructor parameter exposes the existing operation recorder for assertions; production injection remains unchanged. |
| WI-2 | Done; tests unexecuted | `CompositePrivacyGate.check` rethrows `CancellationException` before generic exception conversion, without issuing a composite audit decision; other exceptions still become `FailClosed(PRIVACY_GATE_FAILURE)` and log class and capability only. |

Pre-implementation checks: clean `rp-20-wip` at entry; GATE LIFTED; `git diff 37601232..HEAD` on the three spec-named production paths showed no pin drift; target functions and both public overloads were re-read at HEAD.

## Files changed and commits

- `1fbc1953` — WI-1: `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`; `app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt`.
- `3cbff279` — WI-2: `app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt`; new `app/src/test/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGateTest.kt`.

The lane worktree was clean after both commits. No forbidden code files, guard baselines, allowlists, campaign journal, or campaign state files were edited by the implementation session.

## Tests added

`DatabaseBackupRepositoryImplTest`: allowed File and SAF exports; Denied and FailClosed through both overloads; blocked maintenance and WAL, no File output directory and no SAF stream opening; controlled operation-run failure codes for both outcomes.

`CompositePrivacyGateTest`: exact cancellation instance propagates without an audit decision; ordinary `IllegalStateException` returns and audits `FailClosed(PRIVACY_GATE_FAILURE)`; Denied/FailClosed/NotApplicable/Allowed composition.

The tests above were authored and compiled as part of whole-test-source compilation attempts, but **did not run**. Other test-source compile errors stop Gradle before the test task.

## Validation (runner only)

All Gradle work was launched by `scripts/validation-runner.ps1`, serially. `result.json` and the completion marker are in each run directory.

| Profile / real command | Result | Log |
|---|---|---|
| `targeted-unit-test`: `gradlew.bat :app:testDebugUnitTest --tests *DatabaseBackupRepositoryImplTest --console=plain --no-parallel --max-workers=1 --no-daemon -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50` | FAIL, exit 1; `vr-20260923-093113-0f25d265` | `build/validation-runs/vr-20260923-093113-0f25d265/stderr.log` |
| `targeted-unit-test`: `gradlew.bat :app:testDebugUnitTest --tests *CompositePrivacyGateTest --console=plain --no-parallel --max-workers=1 --no-daemon -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50` | FAIL, exit 1; `vr-20260923-091700-37613719` | `build/validation-runs/vr-20260923-091700-37613719/stderr.log` |
| `compile`: `gradlew.bat :app:compileDebugKotlin --console=plain --no-parallel --max-workers=1 --no-daemon -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50` | PASS, exit 0; `vr-20260923-092325-30b2b632` | `build/validation-runs/vr-20260923-092325-30b2b632/stdout.log` |

The final targeted run stopped at `:app:compileDebugUnitTestKotlin` on errors in `KeystoreInstallationSecretHashingTest`, `LegacyDataConsistencyCheckerTest`, `ReceiptLifecycleCoordinatorTest`, `BackupRestoreViewModelPrivacyDenialTest`, and `ExportOptionsViewModelPrivacyDenialTest`. These tests were not named by CL-18 and are outside its blast-radius fence; none was changed. A first sandboxed runner attempt could not access the shared Gradle wrapper cache; an approved runner attempt then required `ANDROID_HOME`. Subsequent attempts used the installed SDK and produced the results above.

## Reviewer focus / follow-up

- Run the spec's strict/privacy-security guardian review: ensure both entry points hit the shared check, FailClosed cannot enter maintenance or open a destination, cancellation is rethrown before conversion, and neither decision reasons nor exception text are logged or persisted.
- Repair the unrelated test compilation failures in their owning lanes, then rerun both targeted filters via the validation runner. The added runtime tests have not yet provided execution evidence.
- Production compile passed before the final test-only assertion was added; no post-commit compile rerun was performed.

## Journal line for human append (not appended here)

`2026-09-23T12:30+03:00 | coder (direct session, sol high) | WAVE1-IMPLEMENT | CL-18 | 2/2 work items, tests DatabaseBackupRepositoryImplTest, CompositePrivacyGateTest, validation FAIL (pre-existing test compile errors; compile PASS) | rp-20-wip`

Note: the spec's journal template requested `2026-09-22T<HH:MM>+03:00`, while the execution environment and commits were dated 2026-09-23. The human should confirm the appropriate journal timestamp before appending.

