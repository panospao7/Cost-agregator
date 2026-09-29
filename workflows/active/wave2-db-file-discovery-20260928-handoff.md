# Wave-2 backup File discovery follow-up — 2026-09-28

VERDICT: FAIL — Wave-2 closure remains blocked.

Patch status: IMPLEMENTED_UNVERIFIED. Validation of this patch: NOT RUN. Independent strict/guardian approval: PENDING. This is an author diagnosis and handoff, not an independent approval of the entire batch.

## Scope and files touched in this follow-up

- scripts/db_guard/scanner.py — narrowly scoped discovery for nullable File cleanup and the reset operation's File-list iteration.
- scripts/test_wave2_db_file_discovery.py — new, standalone public-scanner regression module; 55 intended parameterized cases, not collected or executed by this agent.
- workflows/active/wave2-db-file-discovery-20260928-handoff.md — this new report.

No Kotlin production file, existing test, callable parser, ownership policy, structural exception, expected-method inventory, baseline, allowlist, RawQuery pin, campaign record or earlier handoff was edited. No subagents, validation execution, staging, commit, push, reset, stash, branch switch or worktree operation was used.

## Verified incoming evidence

Human-run validation: vr-20260928-061847-da4a3fd9.

- Result: FAIL, exit 2, E_COMMAND_FAILED; profile static-guards; completion marker present.
- Result timestamps: 2026-09-28T06:18:47.0374432Z through 2026-09-28T06:52:33.7164921Z. Recorded elapsed_seconds: 2022.
- Underlying recorded command: python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards. This is evidence of the human's runner execution, not a command for agents to bypass the runner.
- Tested HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b.
- Equal start/end fingerprint: 1180f45f8cce73ecb75e7470f5b4f89c7bbf28b6e5fc9a10047be3683b2a7dd8.
- Recursive pytest: 3 failed / 4650 passed / 28 skipped. The preserved log contains 95 PASSED records for scripts/test_wave2_db_discovery_semantics.py.
- Suite: 18/25 passed; five blocking violations were time_boundaries, cancellation, event_writers, raw_money_aggregates and guard_tests. The two infrastructure errors were known_good_state and db_access. The five must not all be described as unrelated pre-existing violations: guard_tests includes the three current pipeline failures.

The expanded summary at build/ci/static-guards/guard_tests.log:4696 reports 23 diagnostics: two blocking and 21 advisory; omittedBlockingDiagnosticCount=0. Both other pipeline summaries identify the same two blockers. The source-evidence section has failedGroupCount=0 and omittedGroupCount=0. An untrusted report withholding findings does NOT prove that there are no unauthorized structural operations.

The three still-failing tests in that incoming run are:

1. scripts/test_verify_db_access_boundaries.py::test_current_db_gate_activated_policy_real_config_pipeline
2. scripts/test_verify_db_access_v2.py::test_default_project_root_uses_canonical_manifest
3. scripts/test_verify_db_access_v2.py::test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict

Evidence SHA-256 values:

| Artifact | SHA-256 |
| --- | --- |
| build/validation-runs/vr-20260928-061847-da4a3fd9/result.json | e81d375c72d55211bd3482f6cec8f62449fe6e780be1a92710b91ab986b2da08 |
| build/ci/static-guards/guard_tests.log | 6aaa86a1c2303c64e47e658eaa3951a0609f43e1ab6c63eaa9df4be20aa8f788 |
| build/ci/static-guards/summary.json | 15200b7885e6c41118067fc9c72714c8e6e86d8e7aa14b7b6ffa690d4b9eb78d |

The shared build/ci paths can be overwritten by a later run; preserve the run ID, fingerprints and hashes when comparing results. These results do not validate the new source or new 55-case module.

## Diagnosis and source repair

### DatabaseBackupRepositoryImpl.kt:1407 — nullable File structural discovery

The actual operation is runCatching { tempDir?.deleteRecursively() } inside cleanupRestoreStaging(String, File?), at lines 1405–1407. The ordinary call path already normalizes a bare safe-call receiver, but the structural classifier compared the retained type File? against File and rejected it.

scripts/db_guard/scanner.py:2160 and :3305–3307 now carry the actual safe-call fact into the structural receiver check. Only a bare receiver whose declared type is File? or java.io.File? receives this additional support. Unknown, wrapped and foreign nullable types do not receive a guessed identity. A direct non-safe nullable call is not promoted by this rule. Property-access classification retains its prior default.

Classification remains separate from authorization: the existing exact structural tuple check is unchanged.

### DatabaseBackupRepositoryImpl.kt:2909 — File-list iteration, not a DAO mutation

At line 2837, liveDbFile comes from context.getDatabasePath. Lines 2905–2906 construct dbWalFile and dbShmFile as File values. Lines 2908–2909 iterate listOf(liveDbFile, dbWalFile, dbShmFile) and call File.exists/delete. The old loop inference did not support a non-generic listOf expression, so the shared operation name caused an unresolved DAO-scope diagnostic.

scripts/db_guard/scanner.py:470–507 and :2616–2736 add one closed shape: a braced loop over a nonempty listOf of bare, lexically resolved non-null File/java.io.File values. Arguments are read from raw source so masking a string cannot turn a mixed list into an apparently homogeneous one. Lookup is at the loop header; it does not borrow sibling/later declarations or the name-global fallback. Factory imports and project declarations are conservatively checked before enabling the rule. Both production resolver callers receive the flag (:3239 and :3755); the only other tracked callers are existing direct resolver tests.

The repair also preserves unknown shadows, confines the loop binding to its body, and fixes the exclusive-parenthesis-end offset for a body brace immediately following the header. Real DAO writes remain discoverable and require their existing exact ownership permission.

Explicit limits are intentional: mixed/unknown elements, arbitrary expression elements, spreads, trailing-comma forms, ambiguous factory imports/declarations, unresolved enclosing lambda bindings, implicit it, destructuring and braceless loops are not newly inferred. Unsupported forms remain blocking rather than being guessed. Unknown body-local or callback-parameter shadows cannot inherit a newly inferred File. This is not a general Kotlin type-inference engine or a new DAO permission mechanism.

## Important additional finding: resolving discovery does not imply a green pipeline

The current structural policy has NO exact cleanupRestoreStaging/deleteRecursively entry:

- config/guards/db_structural_exceptions.yml
- config/guards/db_structural_exceptions_expected_methods.yml

The existing restoreCostBackup/deleteRecursively permission at config/guards/db_structural_exceptions.yml:249–255 is a different callable. It cannot authorize the helper. It must not simply be renamed away: current direct calls remain in restoreCostBackup at DatabaseBackupRepositoryImpl.kt:981, :1012, :1034 and :1278.

Both the helper's nullable deletion and the reset File-list loop already exist at HEAD a48076322e57f3d312f34cf6a71139efc4fcc48b. The helper is also absent from HEAD's structural exception file. This is evidence of pre-existing source/policy coverage debt, not proof that this follow-up introduced a runtime deletion defect, and not a waiver of the missing ownership record.

Source-based expectation, NOT an executed result: once discovery is trusted, the helper should become a DB_FORBIDDEN_STRUCTURAL_OPERATION finding unless its exact permission is legitimately reconciled. Thus the three assert-exit-zero pipeline tests may still fail for a real finding rather than for these discovery diagnostics. Do not suppress that finding or relax the tests to obtain green.

Any follow-up exception must be explicitly approved and reviewed for the exact helper/path/operation with a true owner and reason, plus its corresponding expected-method contract. Keep the still-used caller permission. No such reconciliation or approval is claimed here. See FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-06, FG-07 and FG-23.

## Regression coverage authored, not executed

| Coverage | Intended cases |
| --- | ---: |
| Nullable File discovery, retained structural finding | 4 |
| No permission / caller-only permission / exact helper permission | 3 |
| Unknown, wrapped, non-safe or foreign nullable receiver rejection | 5 |
| Braced File lists with spacing/import variants | 9 |
| Real reset-shaped platform-path and constructor locals | 1 |
| Empty/mixed/unknown/expression elements, masked literals and unsupported syntax | 12 |
| DAO list must not be treated as File values | 1 |
| Sibling/later locals, lambda/local shadows, nested loops and scope restoration | 8 |
| Competing imports and project/local factory shadows | 6 |
| Braceless bodies, body shadows, destructuring and implicit-name limits | 6 |
| Total | 55 |

Positive File cases include an unrelated DAO write and require its DB_UNAUTHORIZED_MUTATION finding, not an empty report. Structural fixtures require DB_FORBIDDEN_STRUCTURAL_OPERATION without the exact tuple; a caller's tuple is insufficient. Negative cases require trusted=false, withheld findings and the actual source line. Synthetic roots alone use the explicit empty RawQuery fixture policy. No production policy is replaced by fixture data.

The existing 95-case semantics module and all earlier assertions were left byte-identical. Public scanner fixtures, both production helper call sites and the changed source were read back. This is author inspection, not execution or independent strict/guardian approval.

## Snapshot and preservation

Intake: 2026-09-28T10:03:15.6241414+03:00; branch bug-fixes; HEAD a48076322e57f3d312f34cf6a71139efc4fcc48b; staged index empty; 112 tracked dirty and 66 untracked paths. Intake fingerprint: 594cd4d565353273583eb4dceb5ac55a3133a9aedb022fd3b0ae886255f5ae5c.

Only the validator's JOURNAL.md update differed from the preceding author snapshot before this follow-up began. It was preserved.

Post-source snapshot: 2026-09-28T10:26:39.2122730+03:00; same branch/HEAD and empty index; 112 tracked dirty and 67 untracked paths. Pre-report fingerprint: c4dc934aec4865399ab1c344b3fd05a990687c6e7cd3e8b7062744722953888f.

The complete intake dirty-path SHA-256 comparison found only scanner.py changed, the new test module added, and no paths removed. All 177 other intake dirty paths remained byte-identical. This includes validator edits, policies, existing tests and prior records. The scanner contains mixed CRLF/LF endings; in-memory byte-reconstruction attempts did not reproduce its intake checksum, so they are not represented as byte-for-byte preservation proof for the edited file. No reconstruction attempt wrote to disk. The applied spans and source diff were inspected; independent review of the scanner delta remains required.

| Changed source | Bytes | SHA-256 |
| --- | ---: | --- |
| scripts/db_guard/scanner.py | 195892 | 3ccb4b936d8b14a7f1e44adf17618169dcc77e356b0639daf45841f28a049e17 |
| scripts/test_wave2_db_file_discovery.py | 12516 | f38c596b549993a4510436a7a484bfce099f87ae402ec2f92f445512d96fce52 |

Repeated checks found no validation lock, active STARTING/RUNNING result or validation process before writes. Adding this report changes the fingerprint; the next validation must record its own exact start/end snapshot. Do not edit even this report during validation.

## Human validation and remaining closure gates

After the required review and a fresh quiescence check, freeze the entire worktree and run one serialized full suite through the wrapper:

    pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
    if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; preserve and inspect the run artifacts." }

This can verify the discovery repair even before a policy decision, but a clean pipeline is NOT promised while the helper permission remains unresolved. guard_tests is a suite segment, not a standalone registered GuardId. Do not run pytest/guards directly or shrink recursive selection. Poll the same RUNNING run; do not start another. Preserve result.json, completion marker, full logs and equal fingerprints.

Require actual execution of the 55 new cases, preservation of the 95 existing semantics cases and prior scanner contracts, and the three real-config pipeline tests. Inspect complete blocking/advisory diagnostics and findings, not only totals. Missing/skipped/unknown/infrastructure outcomes are not passing evidence: FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03. The non-weakening and self-protection gates remain FG-06, FG-07 and FG-23.

Wave-2 closure also still requires independent strict/guardian approvals of the actual final snapshot; device PDF/image/MerchantKeyBackfill gates; resolution or explicitly approved disposition of remaining guard/infrastructure failures; W2-R3 / CA-P-03-003 receipt/PDF partial-result propagation; WorkerRunLogger evidence adjudication; and the six previously missing CL-09 targeted-filter records:

- ExpenseRepositoryMerchantKeyBackfillTest
- MerchantKeyBackfillWorkerTest
- WarrantyReminderDeliveryDaoTest
- WarrantyExpirationWorkerTest
- ReceiptMatchingViewModelTest
- ReceiptLinkServiceColumnScopeTest

Retain workflows/active/wave2-independent-deep-review-20260927-100231Z.md, wave2-post-validation-adjudication-20260927-124138Z.md and wave2-db-discovery-followup-20260928-handoff.md as the wider review/provenance record. A successful db_access run would not close these other findings. No commit or closure decision was made.

