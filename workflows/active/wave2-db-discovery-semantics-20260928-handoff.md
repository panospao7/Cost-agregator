# Wave-2 discovery semantics repair - 2026-09-28

VERDICT: FAIL for Wave-2 closure. This localized repair is IMPLEMENTED_UNVERIFIED.

One reviewer/coder; no subagents. This handoff records the new discovery-layer patch, not a completed independent approval or a passing validation run. No validation was executed by this agent.

## Scope and evidence boundary

The previous coordinate-contract repair and the latest human-run evidence were read before changing source. Unlike that earlier metadata-only repair, this patch changes discovery semantics and therefore requires its own adversarial execution and independent review under FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03 and FG-23.

Latest inspected human run: vr-20260927-210933-f8c83a5f, static-guards, FAIL / exit 2 / E_COMMAND_FAILED; completion marker present. Started 2026-09-27T21:09:33.9255038Z; finished 2026-09-27T21:36:02.4049842Z; reported elapsed 1,583 seconds. Command recorded by the runner: python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards.

Run HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b. Equal recorded start/end fingerprint: 34801c653ba3783801bd11561c987476fd594b21b6bccfa72ccb6195ea91c487.

Inspected guard_tests.log records 3 failed / 4,555 passed / 28 skipped. All seventeen coordinate-expectation cases are recorded PASSED. Log path: build/ci/static-guards/guard_tests.log; observed SHA-256: e4936c393e78fe3b596a71891391c729fdcad8d331772827a049f9d8ff0049ee. Shared build/ci output paths may be replaced by later runs; associate these observations with the run ID, fingerprint and log hash, not the path alone.

The remaining three failures are:
- scripts/test_verify_db_access_boundaries.py::test_current_db_gate_activated_policy_real_config_pipeline
- scripts/test_verify_db_access_v2.py::test_default_project_root_uses_canonical_manifest
- scripts/test_verify_db_access_v2.py::test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict

All three summaries report 51 diagnostics: 29 blocking and 22 advisory. The expanded real-config summary exposes all 29 blockers (omittedBlockingDiagnosticCount=0) and source-evidence failedGroupCount=0 / omittedGroupCount=0. The two compact summaries omit 21 blockers each. These observations do not establish new ownership-policy drift. An untrusted report with findings withheld is not evidence of zero unauthorized writes.

## Source diagnosis and localized changes

These mechanisms were traced through source and callers; their repair has not yet been executed. Kotlin production files and the four affected source coordinates were left unchanged.

### 1. Recurring collection call targets

At app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt:466-468, mutableListOf<String/Long> locals are passed to List<String/Long> DAO parameters. The scanner infers MutableList, while the existing binding matcher required normalized equality.

scripts/db_guard/scanner.py:1250 now permits only directional MutableList<T> to List<T> matching with an identical normalized element spelling. It does not add reverse conversion, arbitrary covariance, arity authorization, foreign qualified-type matching or multiple-overload selection. The existing unique-candidate and ownership checks remain in the path.

### 2. Restore File parent and named lambda binding

At app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt:703, parent.exists() occurs inside sentinel.parentFile?.let { parent -> ... }. Discovery lacked the closed File.parentFile return fact and fabricated only implicit it bindings.

scripts/db_guard/scanner.py:765 adds the File parentFile fact after explicit declared-property lookup. The lambda binding code at approximately 2644-2721 recognizes a single named parameter, resolves its receiver at the dispatch location, and carries lexical scopes. Unsupported or unknown parameter bindings retain unknown shadows rather than borrowing an outer binding. A parameter-shaped prefix is required so a function type in the lambda body is not mistaken for its header. Existing constructor/closed-chain resolution remains in place; arbitrary unresolved receivers remain unsupported.

### 3. Startup File-producing let expression

At app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt:583, sourceFile comes from sourceDir?.let { File(it, task.sourceRelativePath) }. The initializer was not among the recognized closed forms.

scripts/db_guard/scanner.py:1523 adds _file_constructor_let_type, called by initializer inference near 1719. It recognizes only a File-typed simple receiver and a whole File constructor as the sole lambda result. Unknown receivers, arbitrary known receiver types, factories, foreign qualified File constructors, return/throw paths, trailing result expressions and non-let dispatch are rejected by this helper. The normal scanner still examines calls inside constructor arguments; this is not an exclusion zone.

### 4. Backup callback Result type resolution

At app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt, runCostBackupExport and containRestoreOperation use (CostBackupSnapshot) -> Result<T> and () -> Result<T>. The callable resolver lacked Kotlin default Result handling, and its global simple-name fallback encounters unrelated project Result declarations. An owner-wide parse failure affects multiple declarations; it is not twenty-four independent policy defects.

scripts/kotlin_callable_parser.py:498 and :886 add a closed default-import Result rule. Existing lexical/same-file, explicit import, alias and type-variable handling stays ahead of it; indexed wildcard and same-package project Result types are checked before the default. Ambiguous in-scope candidates remain unresolved. Result is not added to the broad builtin-root nesting rule. The existing wildcard/same-package resolver ordering was preserved rather than redesigned.

The latest executed evidence identifies 24 affected backup declaration lines: 159, 183, 201, 220, 372, 498, 601, 924, 1405, 1415, 1426, 1482, 1828, 2196, 2279, 2304, 2349, 2611, 2681, 2737, 2785, 2833, 2988, 3032. Clearing their common parse blocker may expose additional downstream diagnostics or real findings; those must be investigated, not suppressed.

## Regression coverage authored, not run

New file: scripts/test_wave2_db_discovery_semantics.py. Manual parameter-expansion count: 65 intended cases. Neither collection nor execution has been performed, so this is not a pytest result.

- Result callback resolution with competing foreign types, in-scope precedence, ambiguous imports, unknown type names and sibling mutation discovery.
- Exact-element collection binding, both real Long/String scanner paths, mismatches, reverse conversion and multiple accepting overloads.
- File parent named/implicit parameters, safe-call receivers, unknown shadows, sibling/out-of-scope bindings, nested DAO lambdas and function types inside bodies.
- File-producing let inference, rejected receiver/result shapes, explicit preservation of writes inside constructor arguments and unrelated core writes.

Positive public scan_db_access fixtures deliberately have no ownership permission and require exactly DB_UNAUTHORIZED_MUTATION with the expected DAO/accessor/operation. Resolution must expose the write, not authorize it. Negative scanner fixtures require trusted=false, no findings, the expected blocking code/path and a positive line coordinate. Existing exact coordinate tests and their stronger full-report/CLI assertions were not modified.

The explicit empty RawQuery policy is confined to isolated synthetic source-root fixtures; no production RawQuery pin or policy file changed. Direct helper tests supplement public scanner/parser fixtures rather than replacing real-config pipeline coverage.

## Preservation and reviewed snapshot

- Intake: 2026-09-28T07:19:26.8819912+03:00; bug-fixes; HEAD a48076322e57f3d312f34cf6a71139efc4fcc48b; empty staged index; 111 tracked dirty paths and 63 untracked.
- Intake fingerprint: d4099f5dfefecd33b558cbfa0b35ad99a228649ad5d67b92ad399e100135f6c6. It differs from the latest human-tested fingerprint and must not inherit that run as validation of this dirty tree.
- Post-source read-back: 2026-09-28T07:40:39.6600080+03:00; same branch, HEAD and empty index; 112 tracked dirty paths plus 64 untracked, before this new report.
- Post-source/pre-report fingerprint: 3992950d60fdd2e2f0fb293cb532ccfa487a0f41f7372318342b4fd64492d8fa.
- Full dirty-path hash comparison found one changed intake path (scanner.py), the newly dirty previously-clean parser, and the new test module. No intake paths disappeared. All 173 other intake dirty paths remained byte-identical, including prior tests, handoffs, policy artifacts and validator edits.
- scanner.py already contained the prior coordinate metadata additions at intake; those were preserved. The semantic changes above are this turn's delta, not attribution of its entire HEAD diff to this repair.
- scripts/db_guard/scanner.py SHA-256: dcd803aaea412fd40663bb455f2d5079d8e6b39aefd1c9ef74aff71697864720; 186995 bytes.
- scripts/kotlin_callable_parser.py SHA-256: 7409f87746d644c20b2acfec10920aa1a4fc0d9914ef04e09c8af38c9ba5c6d9; 74556 bytes.
- scripts/test_wave2_db_discovery_semantics.py SHA-256: df7c98aefaf5283ac475c6cfbb430d9a6018e9e2d24532aaafb78bf3358b898e; 14324 bytes.
- Repeated quiescence checks before source/report writes found no validation lock, STARTING/RUNNING run or validation process. No validation, subagents, staging, commits, pushes, resets, stashes, branch switches, production Kotlin changes or edits to previous campaign records were performed.
- This new handoff changes the whole-tree fingerprint. Use the final captured fingerprint and the next runner start/end fingerprint rather than the pre-report value for subsequent evidence.

## Human validation handoff

Validation of this patch: NOT RUN. After the required independent strict/guardian review and a fresh quiescence check, freeze the whole tree including documentation. From the repository root, run exactly one serialized profile:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; preserve and inspect the run artifacts." }
```

The runner has no scoped Python-test profile; guard_tests is a suite segment, not a registered GuardId. Do not invoke pytest/guards directly or reduce recursive selection. A RUNNING result must be polled under its existing run ID, never duplicated.

Require preserved result.json, completion marker, logs, equal start/end fingerprints and actual outcomes for all 65 intended new cases, the prior seventeen coordinate cases, the three real-config pipeline cases and the existing suite. Missing, skipped, unknown, timed-out or infrastructure evidence is not PASS (FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03). Do not treat an unchanged headline tally as proof of unchanged individual violations.

No policy permissions, baselines, allowlists, RawQuery pins, exceptions or guard selection were altered. Continue to apply FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-06, FG-07 and FG-23. Author source read-back is not independent strict/guardian approval and does not satisfy the protected-check acceptance gate.

## What remains open

- Execution proof for this semantic repair and the three previously failing discovery pipelines; no claim that they now pass.
- Independent strict/guardian approvals against the actual final dirty snapshot.
- Device/instrumented PDF, image and MerchantKeyBackfill gates.
- Previously reported guard violations/infrastructure failures; this patch neither waives nor adjudicates them.
- Earlier functional/evidence gaps, including W2-R3 / CA-P-03-003 receipt/PDF partial-result propagation, WorkerRunLogger evidence adjudication, and six missing CL-09 targeted-filter records identified by prior independent-review handoffs.
- Explicit commit and closure decisions after all applicable gates. No commit was made or authorized by inference.

Resume from this handoff together with workflows/active/wave2-db-coordinate-contract-20260927-handoff.md and the prior independent-review/adjudication reports. Do not replace original requirements or their finding-level dispositions with this localized repair report.
