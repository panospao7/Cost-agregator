# Wave-2 discovery follow-up - 2026-09-28

VERDICT: FAIL for Wave-2 closure. Follow-up repair: IMPLEMENTED_UNVERIFIED.

One coding/review agent; no subagents. This report records a localized follow-up, not an independent approval or a successful validation. No builds, tests, guards, imports, syntax probes or other validation were executed by this agent.

## Decision and scope

Continue the evidence-backed repair rather than sign off the discovery failures as accepted limitations. The source supports narrower corrections than a general Kotlin resolver expansion: safe-call token handling, transport of unknown argument bindings, integration of existing collection compatibility into full positional matching, and one exact dependency type spelling. No Kotlin production change or ownership-policy reconciliation is needed for these corrections.

Author source read-back is complete. Independent strict/guardian review and human execution remain pending. Apply FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03, FG-06, FG-07 and FG-23; this report grants no waiver.

## Latest inspected human execution

- Run: vr-20260928-045203-34ba147b; static-guards; FAIL / exit 2 / E_COMMAND_FAILED; completion marker present.
- Started 2026-09-28T04:52:03.1426962Z; finished 2026-09-28T05:22:20.5450342Z; elapsed_seconds=1812.
- Recorded command: python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards.
- HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b.
- Equal start/end fingerprint: 51c97ae921147fd39aff0b450b58ddc6a71fbed38811aad866b6f96055936c09. This matches the preceding completed author handoff snapshot.
- Pytest: 4 failed / 4,619 passed / 28 skipped. The preserved module records independently count 65 semantics cases: 64 PASSED and 1 FAILED.
- Guard-test log: build/ci/static-guards/guard_tests.log; observed SHA-256 fb36924563a0d25ac3b2eeba389dd1f9ad55d3147cdd4491e0387d383fa60b4b.
- Suite summary: 18/25 pass, five blocking violations, two infrastructure errors. The five are time_boundaries, cancellation, event_writers, raw_money_aggregates and guard_tests; the two infra results are known_good_state and db_access. The new fixture failure is part of guard_tests, so not every current violation should be described as unrelated pre-existing debt.
- Summary JSON SHA-256: 9aa619b3410ee3c4e0bc7096d0e279f83ee55a7f4af538b592d024d48720cadc.

Four failing tests in that run:
- scripts/test_verify_db_access_boundaries.py::test_current_db_gate_activated_policy_real_config_pipeline
- scripts/test_verify_db_access_v2.py::test_default_project_root_uses_canonical_manifest
- scripts/test_verify_db_access_v2.py::test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict
- scripts/test_wave2_db_discovery_semantics.py::test_safe_call_named_lambda_on_bare_nullable_file

The shared build/ci paths can be overwritten by later runs. Associate these observations with the run ID, fingerprints and hashes, not a filename alone. The above run does not validate the new follow-up source.

## Corrections to the validator interpretation

The expanded failure at guard_tests.log:4666 reports 53 diagnostics: 31 blocking and 22 advisory. The blockers comprise TWO classes: seven DB_CALL_TARGET_AMBIGUOUS and twenty-four DB_SIGNATURE_UNRESOLVED. omittedDiagnosticCodeCount=45 refers to the bounded generic code list, not forty-five advisory diagnostics. omittedBlockingDiagnosticCount=0 exposes all blockers. Both compact pipeline summaries show eight blockers and correctly count twenty-three omitted blockers.

The expanded source-evidence report still has failedGroupCount=0 and omittedGroupCount=0. No ownership-policy drift is established by this evidence, and untrusted/withheld findings do not establish zero unauthorized writes.

RestoreMaintenanceMode:703 and AppStartupCoordinator:583 are absent from the new complete blocking list. RecurringRuleLifecycleCoordinator:466-468 and all twenty-four affected backup declarations remain. GroupTransactionCoordinator:985/989 and ReviewQueueRepository:303/446 are now present. They were absent from the preceding COMPLETE twenty-nine-blocker list, which also had omittedBlockingDiagnosticCount=0; they cannot simply be called previously hidden in an omitted code count. Source tracing identifies an argument-transport regression introduced by the prior unknown-shadow handling.

## Source diagnosis and implementation

### 1. Nullable named-lambda fixture: implementation defect, not an oracle change

The receiver parser retains the safe-call question mark in its returned text. The ordinary call path normalizes a bare receiver before lookup, but the lambda-binding path attempted to look up file? instead of file. The positive fixture is a supported File operation and its trusted/unauthorized-write expectation was left unchanged.

scripts/db_guard/scanner.py:2678-2685 now removes the dispatch question mark only when the remaining receiver is a single identifier. Unknown, qualified and wrapped receivers are not unwrapped into guessed identifiers. Additional named/implicit, spaced and negative receiver fixtures were added.

### 2. Recurring calls: compatibility existed but was not wired to this production layout

The previous MutableList<T>-to-List<T> rule was only reached through default-aware auxiliary metadata. scan_db_access builds that metadata from files with executable helper ranges. Pure interface DAOs can be absent from that source map, whereas the earlier synthetic fixture put its DAO and caller together. declaration_scanner.py:1458-1476 distinguishes DAO ranges and abstract declarations from executable helper ranges.

scripts/db_guard/scanner.py:3341-3361 now applies the existing closed compatibility rule to complete positional argument lists using ordered inventory parameter types. It requires exact arity and a unique accepted target. Named/default binding retains the existing source-name/default verification path. No DAO-source root expansion or new mutation permission was introduced.

The real recurring arguments at RecurringRuleLifecycleCoordinator.kt:466-468 are MutableList<Long/String> locals passed to List<Long/String> parameters. New separate-file DAO fixtures reproduce the previously missing integration path and require an unauthorized-write finding when no policy entry exists. Mismatched elements, reverse conversion and known Any arguments remain blocking.

### 3. Group/review target failures: None must remain unknown, not become a type

The prior patch correctly introduced scoped None markers so unknown lambda parameters shadow outer values. _argument_bindings then forwarded those markers as if they were type strings. That selected the known-but-mismatched overload path instead of the existing unresolved-argument path. This explains why four target diagnostics appeared after that patch.

scripts/db_guard/scanner.py:2190-2249 now returns an unresolved argument list for a non-string marker, just as for an absent binding. The established one-candidate mutation identity rule remains unchanged; it still proceeds to exact ownership authorization. Multiple candidate targets remain unresolved, and known mismatched types such as Any are not reclassified as unknown. Tests require unpermitted single-target writes to remain visible, unknown overloaded calls to block, and known mismatches to block with either one or multiple candidates.

This is transport consistency, not inference that an unknown parameter is Long, ExpenseGroup or any other particular type. Preserving the pre-existing unique-target rule is not an independent endorsement of every pre-existing scanner behavior; the wider strict review remains open.

### 4. Backup owner: an additional concrete unsupported parameter type

DatabaseBackupRepositoryImpl.kt:890-893 declares finishCancelledRestore with the fully qualified parameter kotlinx.coroutines.CancellationException, without an import for that name. The prior Result repair did not cover this parameter. The resolver accepted five external root packages but not kotlinx, so this concrete spelling still reaches TYPE_UNRESOLVED and poisons strict owner-wide discovery. The coroutines dependency is declared in app/build.gradle.kts:292-295.

scripts/kotlin_callable_parser.py:519-522 and :843 recognize that exact dependency spelling only. The whole kotlinx namespace, arbitrary same-named types, misspellings and invented members are not accepted. Existing project/import precedence and verbatim signature identity are preserved.

New coverage includes the actual production backup owner through the same manifest-root project type index, not just reduced callback fixtures. It requires the export, containment, cancellation, restore, import, reset and checkpoint headers to remain discoverable and pins the cancellation helper parameter identity. If parsing still fails, the assertion surfaces the controlled parser code. This source-traced correction has not been executed; no claim is made that all twenty-four affected declarations or all three pipeline tests now pass.

## Tests and preservation

- Added 30 intended cases to scripts/test_wave2_db_discovery_semantics.py; manual expansion yields 95 intended cases including the original 65. Collection and execution of the additions are NOT RUN.
- Coverage: separate-file collection calls (5), absent/None argument transport (4), visible single-target writes (3), unknown overload blocking (3), known mismatches (2), nullable bare receivers (4), negative nullable receivers (2), exact coroutine type resolution (2), rejected unevidenced type names (4), real backup owner parsing (1).
- The original test file was reconstructed by removing only the new imports and appended section. Its SHA-256 exactly matches intake: df7c98aefaf5283ac475c6cfbb430d9a6018e9e2d24532aaafb78bf3358b898e. Thus the original assertions, including the failed nullable-file expectation, were preserved.
- No existing test was removed, skipped, relaxed or renamed. Recursive selection, baselines, allowlists, ownership policy, structural exceptions, RawQuery pins, Kotlin production and prior campaign/report records were not changed by this follow-up.

Intake: 2026-09-28T08:36:15.0287206+03:00; branch bug-fixes; HEAD a48076322e57f3d312f34cf6a71139efc4fcc48b; empty staged index; 112 tracked dirty and 65 untracked paths. Intake fingerprint: 8d9c5f61e44fdcbc46d3bb67959dc6ca118ec71d7a7c0c84fd771dbdf4d0cd24. The only dirty-path difference from the preceding author snapshot was the validator JOURNAL.md update, which was preserved.

Post-source read-back snapshot: 2026-09-28T08:50:07.7615075+03:00. Same branch, HEAD, empty index and path counts. Pre-report fingerprint: 4f0036764f3cda62a4a4b3b0db3e09d638cfbea233da4f54edabdd9fd6ec0fab. The full intake hash comparison found exactly three changed paths, no additions or removals; all 174 other intake dirty paths remained byte-identical.

- scripts/db_guard/scanner.py SHA-256 0c49b6ce1dbed8d3b2d13db3848a689f432e1d370be519d7c8e9ebbebdb98a4f; 188934 bytes.
- scripts/kotlin_callable_parser.py SHA-256 39374fe687a8beb4bc86cee789d08a45ddf8f0b53fe5efbf5df4ae50695ffa18; 74904 bytes.
- scripts/test_wave2_db_discovery_semantics.py SHA-256 5e61bc1dba805293de6b3ff50822b7566adcd2733baee715fd5fe42672c457a2; 21423 bytes.

Repeated quiescence checks found no lock, active STARTING/RUNNING result or validation process before writes. No subagents, validation, staging, commits, pushes, resets, stashes or branch/worktree operations were used. Creating this new report changes the fingerprint; the next run must use the final captured snapshot, not the pre-report value.

## Human validation and closure boundary

After the required independent strict/guardian review and a fresh quiescence check, freeze the entire tree including review artifacts. From the repository root, run one serialized full suite:

    pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
    if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; preserve and inspect the run artifacts." }

guard_tests is a suite segment, not a registered GuardId. Do not invoke pytest or guards directly, shrink recursive selection, duplicate a RUNNING run or edit the fingerprinted tree during execution. Preserve result.json, completion marker, logs and equal start/end fingerprints.

Require actual outcomes for all 95 intended semantics cases, the prior seventeen coordinate cases, the three real-config pipeline cases and the remaining recursive suite. Compare individual findings and blocking/advisory evidence, not just the headline tally. Newly exposed findings require adjudication; do not replace them with permission entries merely to pass. Missing, skipped, unknown or infrastructure evidence is not PASS (FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03). No no-growth or self-protection gate is waived (FG-06, FG-07, FG-23).

Wave-2 closure still requires independent strict/guardian approvals, device PDF/image/MerchantKeyBackfill gates, resolution or properly authorized disposition of remaining guard/infrastructure failures, and earlier functional/evidence gaps. In particular, W2-R3 / CA-P-03-003 receipt/PDF partial-result propagation, WorkerRunLogger evidence adjudication and the six missing CL-09 targeted-filter records remain open in the prior review handoffs. A successful db_access run alone will not close those issues. No commit or closure decision was made.

Resume from this handoff and workflows/active/wave2-db-discovery-semantics-20260928-handoff.md, retaining the original independent-review/adjudication findings as the wider scope authority.
