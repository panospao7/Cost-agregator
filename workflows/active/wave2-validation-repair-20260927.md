# Wave-2 validation handback — September 27, 2026

Status: two targeted test/infrastructure repairs authored and read back; post-repair validation NOT RUN. Same bug-fixes checkout at a48076322e57f3d312f34cf6a71139efc4fcc48b. No subagents, commits, pushes, production transaction rewrites, validation executions or global coroutine-setting changes in this repair pass.

This report supersedes the initial NOT RUN/empty-allowlist-blocker state in the September 26 authoring handoff. The human's authorized empty allowlist and assertion-import fix were preserved. Campaign JOURNAL.md and STATE.md were not edited by this coder.

## 1. Receipt identity failure: repair and evidence correction

The failure in vr-20260927-073522-2816bf62 is real: eventFailureRollsBackSuggestion fails assertSame for an injected IllegalStateException. However, the claimed cross-tree proof is not an equivalent-test comparison:

- The pre-batch rp-27 source contains nine tests, none named eventFailureRollsBackSuggestion and none exercising that new failure-identity assertion.
- Its vr-20260927-073858-e213fa0d PASS therefore does not establish that the new identity assertion passed before the CAS change. The batch added this test among six additional receipt lifecycle tests.
- The current production saveMatchSuggestion method has no catch, exception wrapper or replacement-throw construction. Its conditional update and durable event remain inside the existing Room transaction.
- DatabaseBackupRepositoryImplTest already documents and uses CopyableThrowable sentinels whose createCopy returns null, so strict identity assertions detect application wrapping rather than coroutine debug stacktrace copies. That is the source-grounded fixture convention used here. The precise runtime copying mechanism has not been newly reproduced by this coder; a post-repair run remains necessary.

Repair in app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt:

- The original eventFailureRollsBackSuggestion now injects a non-copying IllegalStateException sentinel. Its assertSame, full receipt snapshot comparison and zero-event assertion are unchanged.
- Added suspendedEventFailureRollsBackSuggestion: the event writer yields before throwing the identity sentinel; the original instance, rolled-back receipt and absence of an event are asserted.
- Added eventCancellationPreservesIdentityAndRollsBackSuggestion: a suspended event writer throws a non-copying CancellationException; identity and rollback remain strict assertions.
- Added callerCancellationDuringEventWriteRollsBackSuggestion: a CompletableDeferred handshake reaches the suspended writer, then the caller job is actually cancelled. The test checks cancelled completion, CancellationException and rolled-back database state. No 1ms timer, sleeps or timeout-helper workaround is involved.

No production exception-unwrapping was added: such a change would risk replacing a genuine transaction-finalization failure without evidence that application code is wrapping the exception. The Boolean CAS result, false/no-event branches, barrier and atomic update/event path are unchanged. These tests still fail if the service swallows or replaces the sentinel, emits an event on failure, or commits the tentative suggestion. If the rerun fails, stop and investigate the actual Room/coroutine boundary; do not relax assertSame or rollback assertions.

## 2. Pytest collection repair

Persisted build/ci/static-guards/guard_tests.log reports 4339 collected items and one collection error, then interruption: scripts/db_guard/mediation_analysis/test_models.py imported an unqualified top-level models module. Collection is not execution; the new Python guard regressions have no passing execution evidence from that run.

Repair in scripts/db_guard/mediation_analysis/test_models.py:

- Resolve the repository root from the test file, matching the neighboring parser-test bootstrap, and import scripts.db_guard.mediation_analysis.models explicitly.
- Preserve both recursive pytest collection and the file's existing direct-execution entry point.
- Add a module-identity assertion for the canonical package name and actual models.py path, preventing accidental binding to an unrelated top-level models module.
- Preserve every existing frozen-model/vocabulary assertion. No selection narrowing, skips, ignored collection failures, fallback import, baseline changes or guard exceptions.

The recursive scripts selection in both the suite and evidence capture is unchanged. Only the human-run full static-guards profile can establish that collection completes and these modules actually execute.

## 3. Six validator-side edits — scoped author source review

VERDICT: PASS (source review of these six edits only; not independent guardian approval or a new runtime PASS).

| Edit | Source evidence and disposition |
|---|---|
| CloudPiiSanitizer raw regex | app/src/main/java/com/yourname/expensetracker/data/ai/provider/internal/CloudPiiSanitizer.kt:116 uses a raw regex string for whitespace/phone punctuation grouping. Source and blame at 3b7cabfe checked. No redaction exemption was added by the string-literal repair. Retained. |
| SemanticKeywordMatcher raw regex | app/src/main/java/com/yourname/expensetracker/domain/categorization/SemanticKeywordMatcher.kt:69 uses a raw string for Unicode punctuation/symbol classes. Source and 3b7cabfe provenance checked; the normalization branches are retained. |
| CurrencySettingsRepository import | app/src/test/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt:5 imports the interface from domain.currency, where its declaration exists. c8f4c383 provenance checked. Retained. |
| Typed Uri matcher | app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStatementDuplicateTest.kt:139 uses any<Uri>() for processUri, which has Uri and String overloads. The exactly-zero invocation assertion remains intact. c8f4c383 provenance checked. Retained. |
| assertNull import | app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt:55 imports org.junit.Assert.assertNull for the query-failure cause assertion at line 1780. This restores compilation without changing the assertion. Retained. |
| Authorized empty allowlist | scripts/allowlists/ui_dao_allowlist.yml has an explicit empty list and zero exemptions. Human authorization is recorded in JOURNAL.md; vr-20260927-075548-69c06619 is PASS, exit 0. Retained unchanged by this coder. |

Issues: none identified in those six narrowly reviewed edits. The first four were already committed in CL-05; no duplicate edits were made. The separate owner-grace-period note expires October 1, 2026 and remains maintenance follow-up, not authority to broaden any exception. FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03/FG-06/FG-07/FG-23 remain in force.

## 4. Human-run evidence read back, not executed by this coder

| Evidence | Persisted result |
|---|---|
| vr-20260927-065201-c65ae48d | Production compile PASS, exit 0, before this test-only repair. |
| vr-20260927-072820-45f3207d | WorkerLeaseRegistryTest PASS, exit 0. |
| vr-20260927-072938-3d8cbcfd | WorkerRestoreRegressionTest PASS, exit 0. |
| vr-20260927-073103-32381c10 | WorkerExecutionGuardTest PASS, exit 0. |
| vr-20260927-073228-41d809da | MaintenanceOperationRunnerTest PASS, exit 0. |
| vr-20260927-073349-ab86c269 | DataRetentionWorkerTest PASS, exit 0. |
| vr-20260927-073522-2816bf62 | ReceiptMatchLifecycleServiceTest FAIL, exit 1, before the fixture repair. |
| rp-27 vr-20260927-073858-e213fa0d | Nine older receipt tests PASS; the new failing identity case was absent. |
| vr-20260927-074529-57e4e7b1 | Full static-guards FAIL, exit 2. Persisted summary: 25 legs, 16 pass, five blocking violations, four infrastructure errors. |
| vr-20260927-075548-69c06619 | allowlist_compliance isolated rerun PASS, exit 0, after the authorized empty-list edit. |

The full-suite artifact differs from the conversational 17-pass/two-infra tally: known_good_state and db_access also appear as infrastructure errors, alongside allowlist_compliance and guard_tests. The isolated allowlist PASS does not retroactively change that full-run summary. Five violation legs remain time_boundaries, db_artifact_sync, event_writers, raw_money_aggregates and cancellation; their pre-existing classification is the human's adjudication, not a new coder validation run. The additional infrastructure legs are not cleared or silently excluded by this repair.

The compile, receipt, full-guard and isolated-allowlist result.json records have matching start/end fingerprints for their respective runs. They are evidence for those earlier worktree states, not fresh evidence for the new repair.

WorkerRunLoggerTest remains under the human's documented both-tree environmental adjudication. Its timeout helper was not changed, and it is not relabeled PASS. The remaining targeted queue and broader profiles are still pending.

## 5. Repair source review and exact rerun handoff

- Read both changed test files back and inspected their diffs. Existing identity/rollback assertions, model assertions, recursive selection, production CAS/barrier logic and the authorized allowlist were preserved.
- Scoped git -c core.safecrlf=false diff --check returned exit 0 for the two repaired test files; whitespace only.
- Post-repair compile/tests/guards: NOT RUN. No new validation run ID or result is claimed. Independent strict/guardian gates remain pending.

From a quiescent repository root, the human should run these through the blocking wrapper, one at a time:

    pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptMatchLifecycleServiceTest'
    pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards

Stop the targeted sweep if the receipt class fails; on its PASS resume the queued sweep at ReceiptMatchingWorkerTest. The guard run is independent evidence: inspect guard_tests.log for completed collection and actual execution/results for the new modules, not merely guard script PASS results. Full-suite legacy failures or infrastructure errors remain failures even if the repaired collection leg succeeds. Poll an existing RUNNING result rather than launch another validation process.

## Files touched in this repair

- app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt
- scripts/db_guard/mediation_analysis/test_models.py
- This report, the combined handoff/validation/file inventory, and brief current-status pointers in the CL-09/CL-22 author notes and remaining-wave2 plan.

No application production file, Room schema, migration, guard selection, baseline, policy, exception list, WorkerRunLogger helper or authoritative campaign journal was changed in this repair pass. The six validator-side edits were reviewed and retained, not re-authored. Nothing is marked merged, closed or fully green.
