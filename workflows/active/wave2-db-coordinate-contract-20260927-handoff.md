# Wave 2: coordinate-contract regression adjudication

Date: 2026-09-27. VERDICT: FAIL for db_access / Wave-2 closure. This two-file test-expectation repair is IMPLEMENTED_UNVERIFIED. No new validation was executed.

## Evidence-based correction

The increase from 3 to 20 pytest failures is real, but the proposed scanner fail-closed regression diagnosis is not supported by the actual failure reports. Sixteen CLI failures show exactly the same unresolved diagnostic code, path, null symbol, empty findings, trusted=false and exit 2 as expected. Their only report difference is the newly added controlled_context.line versus an expected empty context. The seventeenth, the D4 mixed-debt test, stops at its old empty-context assertion; source inspection identifies its DB declaration at fixture line 10. Its later trust assertions were not reached in that failed execution.

The preceding diagnostic-only patch missed these exact report expectations. That omission is the author-side defect corrected here. Removing the new coordinates, accepting arbitrary metadata, weakening report equality, changing trust classification, or authorizing unknown receivers would be the wrong repair.

git diff for scripts/db_guard/scanner.py against HEAD shows only coordinate metadata added at diagnostic emission sites, not changed resolver conditions. The scanner was left byte-identical throughout this repair. This finding does not certify every pre-existing scanner behavior: the three real-tree pipeline failures remain unresolved.

The D4 mixed-debt test already exists at HEAD; it is not a new test authored by the preceding coordinate patch.

## Preserved human-run evidence

- Run: vr-20260927-201712-c7284591; static-guards; FAIL / exit 2; completion marker present.
- Started 2026-09-27T20:17:12.7055766Z; finished 2026-09-27T20:41:30.2787510Z; elapsed 1,453 seconds.
- Recorded command: python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards.
- HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b; branch bug-fixes.
- Equal recorded start/end fingerprint: 8eb188f99480dd6450f7f89bb4e2dbaa9a181c553a43fb9a76108fbff67ce209. This matches the preceding completed diagnostic-repair handoff snapshot, not merely its branch name.
- Pytest: 20 failed / 4,538 passed / 28 skipped. Skipped cases are not passing evidence.
- Guard-test log: build/ci/static-guards/guard_tests.log; SHA-256 caa496b8515a2c06fc3d2a0ac741686d7c0e7fae013df84ca19f094b5cf17dd5.
- All twenty failure blocks were examined, including complete actual/expected reports for the sixteen CLI coordinate mismatches. Failure blocks begin at log line 4598; the complete failing-test list is at 4914-4933.
- All seven tests in scripts/test_db_discovery_diagnostic_details.py and the thirty newly added diagnostic-summary cases are individually recorded PASSED. Their execution does not substitute for the failing existing contracts.

## Complete disposition of the seventeen additional failures

| Test | Cases | Exact expected source coordinate | Disposition |
|---|---:|---:|---|
| test_overloaded_dao_with_argument_matching_no_overload_is_not_authorized | 1 | 19 | Exact metadata expectation repaired; execution pending |
| test_qualified_dao_receiver_is_unresolved_in_structured_report | 10 | 13 | All ten variants retained; exact metadata expectation repaired; execution pending |
| test_unknown_argument_expression_is_not_authorized_by_arity | 1 | 18 | Exact metadata expectation repaired; execution pending |
| test_mixed_scanner_debt_blocks_only_the_db_callable_cli | 1 | 10 | Blocking coordinate pinned; UI advisory expectation unchanged; execution pending |
| test_accessor_unknown_constructor_expression_is_not_authorized | 1 | 13 | Exact metadata expectation repaired; execution pending |
| test_receiver_scope_does_not_cross_sibling_methods | 1 | 17 | Exact metadata expectation repaired; execution pending |
| test_receiver_scope_prefers_local_shadow_and_rejects_ambiguous_shadow | 1 | 19 | Exact metadata expectation repaired; execution pending |
| test_mixed_debt_blocks_only_the_db_touching_callable | 1 | 10 | Exact coordinate and explicit DB diagnostic-code assertions added; execution pending |

The ten qualified-receiver variants still cover holder(...), array indexing, map indexing, ordinary qualified access, qualified safe calls, nested receivers and parenthesized receivers. None was removed or reclassified. CLI exit-2 assertions, full report equality, untrusted status, empty findings, sibling/shadow coverage and advisory-only distinctions remain intact. Expected coordinates are literal fixture facts, not values copied from the scanner result at runtime.

## Files touched in this repair

- scripts/test_verify_db_access_v2.py: seven function-level exact context expectations, covering sixteen executed cases; one corresponding docstring correction.
- scripts/test_db_guard_scanner_d4.py: one exact context expectation and an additional explicit DB_SIGNATURE_UNRESOLVED code assertion. The earlier independent line-6 expectation was preserved.
- workflows/active/wave2-db-coordinate-contract-20260927-handoff.md: this new report; no prior report overwritten.

The pre-existing diagnostic-summary import and both real-tree failure-summary additions in test_verify_db_access_v2.py were retained. _report still requires exact equality; no helper strips coordinates or ignores unexpected keys.

## The three persistent pipeline failures: current evidence

The instrumented real-config failure reports source-evidence failedGroupCount=0 and omittedGroupCount=0. DB_POLICY_SOURCE_EVIDENCE_INVALID is not the current root reported here. The later discovery stage is untrusted. The other two pipeline assertions use compact summaries and must not be mistaken for complete inventories.

All three report 51 diagnostics: 29 blocking and 22 advisory. The expanded real-config summary exposes all 29 blockers with omittedBlockingDiagnosticCount=0. Each compact summary exposes eight and correctly reports 21 omitted blockers. The old four path-only blocking records expanded into distinct coordinate records; the larger diagnostic count is not evidence of new policy drift.

| Diagnostic | Current source coordinates | Status |
|---|---|---|
| DB_CALL_TARGET_AMBIGUOUS | app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt:466,467,468 | NOT_FIXED |
| DB_DAO_SCOPE_UNRESOLVED | app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt:703 | NOT_FIXED |
| DB_DAO_SCOPE_UNRESOLVED | app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt:583 | NOT_FIXED |
| DB_SIGNATURE_UNRESOLVED | app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt:159,183,201,220,372,498,601,924,1405,1415,1426,1482,1828,2196,2279,2304,2349,2611,2681,2737,2785,2833,2988,3032 | NOT_FIXED |

Source-review leads, not executed root-cause proofs:

- The three recurring calls pass local mutableListOf<Long/String> values to DAO List<Long/String> parameters. The scanner infers MutableList while its overload matching compares normalized type tuples. Investigate exact collection assignability without authorizing mismatched element types, reverse conversions or ambiguous overloads. Do not change the ownership policy to conceal this mismatch.
- RestoreMaintenanceMode:703 is parent.exists() inside sentinel.parentFile?.let { parent -> ... }. AppStartupCoordinator:583 is sourceFile.exists(), where sourceFile comes from a nullable File-producing let expression. These are file-operation source sites, not evidence that transaction-helper DAO accessor permissions need broadening. Any inference repair must preserve unknown-receiver and lexical-shadow failures.
- The twenty-four backup coordinates identify declarations affected by owner-wide callable parsing. They do not prove twenty-four malformed signatures or policy entries. The actual failing header still needs isolation before a semantic repair; the generic helper alone is not established as its cause.

No speculative resolver, Kotlin, policy, baseline, allowlist, RawQuery-pin or generated-artifact change was made. An untrusted report with findings withheld is not proof that the tree contains no unauthorized database writes.

## Preservation and review snapshot

- Intake: 2026-09-27T23:47:23.2473128+03:00; 111 tracked dirty paths plus 62 untracked; staged index empty.
- Intake fingerprint: 4df79b46a5fd36023f4a6bc8f7018b0a6ef789746d01f36fb72f0c2aa0d20e0f.
- Read-back snapshot before this report: 2026-09-27T23:54:46.9850899+03:00; same HEAD, branch, counts and empty index; fingerprint 6412001f8a39ac50966ba06834f15db6a735cdbdb9ffd794a45e67b0006a6f33.
- Full dirty-path hash comparison against intake found only the two test files changed, no additions before this report and no removals. All other pre-existing work was preserved.
- Reviewed test_db_guard_scanner_d4.py SHA-256: 06b78fd128690a984d3c96f9d8af151f756cba6c05ab61dc1f60029e0212637f.
- Reviewed test_verify_db_access_v2.py SHA-256: 9aca452171ff243ccc0f9a752ef7f2f19777c74df6a1ddde3a6ae93dbcee565f.
- Unchanged scanner.py SHA-256: 760e5b052bf60e4961ef67910feb6d698a82508dae8f240097bb321749efe0bc.
- Quiescence checks before edits and before report creation found no validation lock, active STARTING/RUNNING result or validation process. An initial patch context mismatch made no changes; target hashes were re-read before the corrected patch.
- No validation, subagents, staging, commits, pushes, resets, stashes, branch changes or existing campaign-record edits. This author read-back is not independent strict/guardian approval.
- Creating this report changes the whole-tree fingerprint; use the final captured fingerprint or the subsequent runner start/end fingerprint, not the pre-report fingerprint above, for the next run.

## Human validation and next work

New execution: NOT RUN. After required review and a fresh quiescence check, freeze the entire tree including review artifacts. One serialized full-suite command from the repository root is:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; preserve and inspect the run artifacts." }
```

guard_tests is a suite segment, not a standalone registered GuardId. A RUNNING result must be polled under the same run ID, never rerun. Preserve result.json, logs, completion marker and equal start/end fingerprints. Require the seventeen corrected cases to execute, retain the previous cases and selection, and treat missing/skipped/unknown infrastructure evidence as failure.

This patch deliberately does not fix the three discovery pipeline failures: another full run now is verification of the coordinate-contract repair, not an expected Wave-2 closure run. The localized discovery defects need a separately reviewed minimal semantic repair with positive and adversarial fixtures; do not repeatedly reconcile already-clean policy rows.

Governing references: FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03, FG-06, FG-07 and FG-23. No baseline growth, exception additions, recursive-selection reduction, self-check weakening or advisory reclassification is justified by this report.

Other closure items remain open: independent strict/guardian approvals, device/instrumented gates, existing guard violations/infrastructure failures and prior functional/evidence gaps. In particular, this repair does not close W2-R3 / CA-P-03-003 receipt/PDF partial-result propagation, WorkerRunLogger evidence adjudication or the six missing CL-09 targeted-filter records identified in the prior independent-review handoffs. No commit or closure decision was taken.
