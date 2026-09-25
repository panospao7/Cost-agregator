# CL-19 Wave 1 implementation report — rp-22-wip

**VERDICT: PARTIAL — NOT READY FOR MERGE.** The branch contains commits `4c1de21d` (WI-4) and `d3e8fa25` (partial WI-1/2/3/5). The working tree was clean after the commits. No reviewer-strict gate has passed.

## Work items

- WI-1: **partial**. Added checked mode commits, critical in-memory lock, and unknown/blank mode decoding; persistence-failure, read-exception, and scheduling boundaries are not exhaustively tested. Review the transient persisted NORMAL window during `exit(false)` scheduling and handling of present-but-null mode values.
- WI-2: **partial**. Added explicit absent/valid/corrupt active-journal classification and critical startup handling; the malformed-input and second-startup matrix is not complete.
- WI-3: **partial**. Journal write/preservation now propagates durability errors; reset records safety and SWAPPING before live-file deletion. Post-destructive rollback/verification, import/reset failure paths, and deterministic fault-injection coverage remain incomplete. Do not treat this as an approved state-machine redesign or as proof that every failure edge is safe.
- WI-4: **implemented, not test-executed**. Coordinator checks the write barrier immediately before terminal DAO status writes and preserves the original typed outcome on denial.
- WI-5: **partial**. Verified asset rollback calls `exit(false)` rather than `reset()`; added a worker scheduling assertion but no successful test execution or repeated-recovery/commit-failure coverage.

## Files changed

`app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt`, `RestoreMaintenanceMode.kt`; `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`; `app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt`; `app/src/main/java/com/yourname/expensetracker/domain/bank/BankConnectionLifecycleCoordinator.kt`; named tests `DatabaseBarrierTest.kt`, `P7BugFixesTest.kt`, `RestoreJournalDurabilityTest.kt`, `BankConnectionLifecycleCoordinatorOutcomeTest.kt`, `WorkerRestoreRegressionTest.kt`, `BackupRestoreContractTest.kt`, and `AppStartupCoordinatorRecoveryTest.kt`. `DatabaseBackupRepositoryImplTest.kt` was **not** updated. No forbidden campaign, schema, guard, or baseline files were edited.

## Validation

- `scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*RestoreJournalDurabilityTest'`: **FAIL**, exit 1; `build/validation-runs/vr-20260923-141036-941fb222/result.json`, `stderr.log`. Unit-test compilation fails in **unnamed/out-of-fence** tests: `KeystoreInstallationSecretHashingTest`, `LegacyDataConsistencyCheckerTest`, `ReceiptLifecycleCoordinatorTest`, `BackupRestoreViewModelPrivacyDenialTest`, and `ExportOptionsViewModelPrivacyDenialTest`. None were modified. Same failures also appear in `build/validation-runs/vr-20260923-140217-f5a8a50d/stderr.log` for `*Restore*Test`; no named test actually ran.
- `scripts/validation-runner.ps1 -Action Start -Profile compile`: **PASS**, exit 0; `build/validation-runs/vr-20260923-141932-f82c2c80/result.json`, `stdout.log`, `complete.marker`. The runner's actual command was `gradlew.bat :app:compileDebugKotlin --console=plain --no-parallel --max-workers=1 --no-daemon -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`.
- First non-escalated runner attempt failed with access denied on the shared Gradle cache (NOT RUN); an earlier targeted run revealed a local Kotlin compile error that was repaired before the final compile. `git diff --check` reported no whitespace errors before committing.

## Reviewer attention / remaining risk

Strict review is **FAIL / not performed** until all five work items and the enumerated tests are finished and targeted tests actually execute. In particular, scrutinize pre- and post-swap durability failure handling, `restoreCostBackup` success/failure journal preservation, reset/import after a destructive point, startup's critical-mode persistence, and worker scheduling before NORMAL is observable. Never label this branch GREEN based on the compile PASS.

## Journal line for the human

`2026-09-22T17:27+03:00 | coder (direct session, sol high) | WAVE1-IMPLEMENT | CL-19 | 1/5 work items, tests DatabaseBarrierTest/P7BugFixesTest/RestoreJournalDurabilityTest/BankConnectionLifecycleCoordinatorOutcomeTest/WorkerRestoreRegressionTest/BackupRestoreContractTest/AppStartupCoordinatorRecoveryTest, validation compile PASS; targeted tests FAIL (out-of-fence compilation) | rp-22-wip`

This report is deliberately **outside** the campaign directory: the spec expressly forbids edits to anything under that directory, including `JOURNAL.md`. The human must copy the journal line; no campaign artifact was modified.
