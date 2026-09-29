# Wave 2 — authorized backup-cleanup structural policy reconciliation

Date: 2026-09-28. Status: **IMPLEMENTED_UNVERIFIED**. Wave-2 closure remains **PENDING / NOT ACCEPTED**.

One agent performed this work; no subagents or validation processes were launched. This is an implementation handoff and author read-back, not an independent strict review or guardian approval.

## 1. Authorization and exact scope

The human answered “yes i authorise” to adding only the exact `cleanupRestoreStaging` / `deleteRecursively` structural entry and its matching expected-method record, while retaining `restoreCostBackup`'s still-used entry. This authorizes one additional exact structural permission, not a blanket backup exception or a general baseline refresh.

The new identity is:

- Path: `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt`
- Class: `DatabaseBackupRepositoryImpl`
- Method: `cleanupRestoreStaging`
- Operation: `deleteRecursively`
- Owner: `@panospao7`; linked issue: `MIT-003` (matching the existing backup structural ownership).
- Reason: best-effort cleanup of the restore extraction workspace.

Apply `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-06/FG-07 to this bounded human approval; FG-03 and FG-23 remain mandatory. No matching algorithm, classification enforcement, recursive test selection, exit-code rule, or unknown-result handling was relaxed.

## 2. Source basis and retained permissions

`DatabaseBackupRepositoryImpl.kt:1405–1407` declares `cleanupRestoreStaging(stagedDbPath: String, tempDir: File?)` and directly invokes `runCatching { tempDir?.deleteRecursively() }`. The helper cleans the extraction workspace supplied by the restore operation; its staged DB-trio cleanup is a separate call, not another grant added here.

`restoreCostBackup` still directly invokes `deleteRecursively` at source lines 981, 1012, 1034 and 1278. Its existing permission must therefore remain; moving that permission to the helper would remove authorization from live callers. The retained tuple is still at `config/guards/db_structural_exceptions.yml:249–255`; the additional helper tuple is at lines 257–264.

This follows the discovery repair in `workflows/active/wave2-db-file-discovery-20260928-handoff.md`. Discovering a File operation is not permission to perform it. The nullable-File and closed File-list discovery changes and their 55 intended regression cases were not modified or executed in this turn. No Kotlin production code changed.

## 3. Files touched and contract delta

1. `config/guards/db_structural_exceptions.yml`: one exact helper entry; all prior entries retained.
2. `config/guards/db_structural_exceptions_expected_methods.yml`: matching additional-method record in `fixtures`, not a historical reclassification into `expected`; exact structural count 64 → 65.
3. `scripts/verify_db_access_boundaries.py`: the same exact fixture identity added to the immutable contract; exact count pin 64 → 65. The historical expected set remains 60; fixtures increase only 4 → 5.
4. `scripts/test_verify_db_access_boundaries.py`: corresponding exact count/fixture arithmetic updated without relaxing negative assertions; seven new intended cases cover this authorization.
5. `scripts/ci/verify_known_good_state.py`: only the structural row's matching count pins/comments synchronized to 65 = 60 + 5. Other known-good expectations, debt, outcomes and timeout behavior are unchanged; this is not a declaration that the scorecard now passes.
6. `scripts/ci/test_verify_known_good_state.py`: matching structural fixtures/count assertions updated, the out-of-range fixture case kept out of range, and two new intended boundary cases added.
7. This new handoff; no prior report or campaign record overwritten.

The two independent structural pin consumers would otherwise reject the explicitly authorized addition merely because they still hard-code 64 entries. Their updates express exactly the same single approved delta, not permission for arbitrary future count growth. Existing immutable classification, tuple equality, duplicate rejection and source checks remain intact.

No ownership-policy, RawQuery classification/pin, allowlist, file under `config/baselines`, production Kotlin, Gradle/configuration, or previous campaign record changed in this turn.

## 4. Authored regression coverage — NOT RUN

- `test_backup_cleanup_structural_approval_is_exact_and_retains_caller`: one case. Requires exactly one matching policy/fixture row with matching metadata; preserves caller classification; forbids other helper operations. It additionally uses the production source-evidence verifier to require direct operation evidence in BOTH real method bodies. The isolated source check does not reclassify the production manifest.
- `test_backup_cleanup_approval_rejects_nearby_identity_or_operation`: six parameterized cases. Wrong path, wrong class, adjacent/near-miss methods and other operations must produce the immutable fixture-classification failure.
- `TestStructuralFailBranches.test_authorized_cleanup_delta_does_not_accept_old_or_extra_grants`: two cases. Internally consistent totals of 64/4 and 66/6 still fail against the exact approved 65/5 pin; relational count agreement alone cannot authorize growth.

Total: nine intended new cases. This is an authored-case count, not pytest collection or execution evidence. Existing missing/extra/duplicate/changed-operation assertions remain negative; their synthetic entry arithmetic now matches the approved 65-entry canonical count. No tests were removed or skipped.

## 5. Snapshot and preservation evidence

- Branch: `bug-fixes`.
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`.
- Intake fingerprint: `ae8ae02d6cd667cd50f84ffa80ba0ad8b5faae0976e04ac66766189f1c65c0b3`.
- Intake: 112 tracked dirty files, 68 untracked files, no staged changes.
- Post-edit/pre-handoff fingerprint: `aab54105956e384fffd37c45a477659d39064ce50d25046b2b6d1f2538244aa8`.
- Post-edit/pre-handoff capture: `2026-09-28T10:48:30.2935108+03:00`; 114 tracked dirty files, 68 untracked files, no staged changes.
- Quiescence checks before writing and before this handoff found no runner lock, STARTING/RUNNING result or validation process.
- Four previously dirty paths changed; two previously clean scorecard paths became dirty. All 176 other intake dirty/untracked paths remained byte-identical; no intake paths disappeared.
- Every edited file was read back, and reversing only the recorded edits in memory reproduced its exact original SHA-256. Bytes outside the intended edits, including existing line endings and earlier work, were preserved.
- No staging, commits, history operations, branch/worktree changes, or destructive cleanup occurred.

The handoff itself is a new fingerprinted file. The final response records the post-handoff fingerprint rather than putting a self-referential fingerprint inside this document.

| File | SHA-256 before this turn | SHA-256 after this turn |
|---|---|---|
| `config/guards/db_structural_exceptions.yml` | `15bdb87d796470dd6a42db70e07dc0b2bbc153352e3a6050fec1b2e1a5a4cc36` | `c9d5ef6ed50c0a1448ca1cdc46e29a8202be14f11f46b3ff940661a5e758b15a` |
| `config/guards/db_structural_exceptions_expected_methods.yml` | `ed33a235204288647276bc6f9d263f438b5266ded99a618bbc42af47450b7c2f` | `367b0ec6b3fbdfbb8e281152b6b60cf69e053d6ba0d4dbcca4fdbcd78247f8a4` |
| `scripts/verify_db_access_boundaries.py` | `e6fedee412be3a6c7c9f5ad5833b79c312af1e306cffdb3d71853735f1606f0d` | `583512f7a0bb57de4c0e1b93d963dd03480bec18e8659e8623be2277af30b00c` |
| `scripts/test_verify_db_access_boundaries.py` | `d2f5964d512600ea076598dd8b088a8de7f9ccfbc7d88cd0e4423727f3b6d924` | `7f9f3fb7dcd5ad6725e495c30166c798186e04c25ab9b75a008b027473a26b3f` |
| `scripts/ci/verify_known_good_state.py` | `17038a567375e0239aa4298289bafda65f9177b45f3cf0758389d72357de77d7` | `4ad0ebe791f0a50339d71d761da6b5d5d5754be75f2e89023aa6612a1b7c68ce` |
| `scripts/ci/test_verify_known_good_state.py` | `e2feba6ca0178f2f8df1d40b767319c716f18b8de838d7ecaf9349d25264375b` | `2e6af83f86fa965e7037ada8fb61d897b26a6258be47270c55d341974e91d077` |

## 6. Execution evidence and next human command

Validation for this snapshot: **NOT RUN**. No Gradle, pytest, guard, compiler, import/AST probe, or validation-runner execution was performed by this agent. File reads, hashes and exact edit read-backs are not substitute test evidence.

The latest previously inspected run was `vr-20260928-061847-da4a3fd9`: FAIL / exit 2, pytest 3 failed / 4,650 passed / 28 skipped, with equal start/end fingerprint `1180f45f8cce73ecb75e7470f5b4f89c7bbf28b6e5fc9a10047be3683b2a7dd8`. It predates both the subsequent File-discovery repair and this policy delta. Its 95 passing semantics cases do not verify these later changes.

After the required review and a fresh quiescence check, freeze the whole tree, including review artifacts, and have the human run from the repository root:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; preserve and inspect the run artifacts." }
```

Use the full serialized profile. `guard_tests` is a suite segment, not a standalone registered guard. Do not reduce recursive selection, invoke pytest directly, launch overlapping validation, or rerun a RUNNING job. Poll the existing run to terminal and preserve its result, completion marker, full logs and matching start/end dirty-worktree fingerprints.

The next evidence must establish actual execution of the nine new policy cases, the preceding 55 File-discovery cases and existing semantic coverage. In particular inspect:

- `test_verify_db_access_boundaries.py::test_current_db_gate_activated_policy_real_config_pipeline`
- `test_verify_db_access_v2.py::test_default_project_root_uses_canonical_manifest`
- `test_verify_db_access_v2.py::test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict`

Check that both previous backup discovery blockers have genuinely cleared, the helper is authorized only through its exact new tuple, and fail-closed negative cases remain effective. Untrusted reports with withheld findings are not proof of zero violations. Missing/skipped/unknown/infrastructure results cannot be called PASS under FG-03.

## 7. Remaining closure gates

This scoped repair does not close Wave 2. The actual changed snapshot still needs independent strict review and required guardian approval; required PDF/image/MerchantKeyBackfill device evidence; disposition of other guard/infra failures; and the separately recorded functional/evidence gaps. Do not group the failing `guard_tests` segment with unrelated pre-existing violations.

Carry forward the earlier reports' open receipt/PDF partial-result propagation issue (W2-R3 / CA-P-03-003), WorkerRunLogger evidence adjudication, and missing snapshot-linked records for the six CL-09 filters: `ExpenseRepositoryMerchantKeyBackfillTest`, `MerchantKeyBackfillWorkerTest`, `WarrantyReminderDeliveryDaoTest`, `WarrantyExpirationWorkerTest`, `ReceiptMatchingViewModelTest`, and `ReceiptLinkServiceColumnScopeTest`. This turn neither adjudicates nor closes those items.

Author read-back is complete; independent review is PENDING. Any commit remains a separate human decision. No promise is made that this must be the last repair iteration: source correctness and preserved fail-closed behavior, not a green-only outcome, determine acceptance.
