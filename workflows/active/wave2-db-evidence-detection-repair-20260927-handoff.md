# Wave 2: mutation detection and bounded evidence repair

Date: September 27, 2026. Status: IMPLEMENTED_UNVERIFIED. Wave-2 closure remains blocked.

## Scope and safety

This is the approved first repair batch following workflows/active/wave2-db-source-evidence-adjudication-20260927.md. One agent; no delegation. No validation was executed. No commits, staging, resets, stashes, branch changes or destructive operations were performed.

The six-file delta repairs a demonstrated detection omission and exposes bounded evidence needed for accurate policy reconciliation. It does not claim to resolve all three real-tree pipeline failures. No production Kotlin, ownership policy, baseline, allowlist, structural manifest, Room RawQuery pin, generated artifact or existing campaign/review record was changed in this batch.

## Reviewed snapshot and preservation

- Branch: bug-fixes. HEAD: a48076322e57f3d312f34cf6a71139efc4fcc48b. Index empty.
- Intake: 164 dirty/untracked paths; runner-compatible fingerprint 2be3d34426ace48a5f73b83f55d897d596b9aa4571ea094be2ffd56ad63b7d90.
- Source read-back snapshot: 2026-09-27T20:13:55.6351677+03:00; 166 dirty/untracked paths; runner-compatible fingerprint ab84bbfdfa47c91a352cf9495945ab1a7d4bf2d620470f12272ce1b4eca4d661.
- The two additional dirty paths are previously clean tracked files policy_parsing.py and test_db_guard_policy_v2_evidence.py, not deleted or discarded work.
- Hash comparison against intake found changes only in the six intended paths below, no removed paths, and no unexpected changes. All other existing dirty/untracked file bytes were preserved.
- Quiescence check 2026-09-27T20:13:56.8194098+03:00: no active lock, RUNNING/STARTING result, unreadable result, or matching validation process.
- This new handoff itself changes the final worktree fingerprint. The source fingerprint above intentionally precedes handoff creation; use the validation runner start/end fingerprint for subsequent execution evidence.

| Reviewed file | Post-edit SHA-256 |
| --- | --- |
| scripts/db_guard/policy_parsing.py | 9538fb1a301ff7f59ee57734cd78c44b56f935593febdde7593ea587c91985a7 |
| scripts/verify_db_access_boundaries.py | e6fedee412be3a6c7c9f5ad5833b79c312af1e306cffdb3d71853735f1606f0d |
| scripts/ci/guard_test_diagnostics.py | cd9884dbda51798fabeb347fc6c323fa090c52cf4e93c876f337ccc0eee1ed92 |
| scripts/test_guard_test_diagnostics.py | 88a97aac4e40d1bf854de0da7eaeeaa0710400c7f6dff59aa43959cd74fc8300 |
| scripts/test_db_guard_policy_v2_evidence.py | 93b652cacd354c14c99f6fb96837902e86eba959ccc807d0f220b887a54da96f |
| scripts/test_verify_db_access_boundaries.py | 445bc13dabc7d37310b7304a39d18dd8f1d206e13f58e3a1cd7b461a1d6fc191 |

Protected ownership-policy SHA-256 remains ff6e6a038d26aa7ea4829a61ac374e8d81db6e30a4f056a44a873c6a6254028e. The preceding adjudication remains 96e48287527103022e996b3833c4c82c04e0ece44d0aa48886249b84d8bff851.

## What changed

1. scripts/db_guard/policy_parsing.py:737-738 and scripts/verify_db_access_boundaries.py:197-198 add nullSnapshotsOlderThan to the maintained mutation-detection vocabularies. RetentionModule.provideRetentionTargets still invokes this real TransactionEventDao SQL UPDATE; deleting its live policy row would have hidden a detector defect. No generic null-prefix rule was added. Detection still extracts the full operation name; authorization still compares exact operations.
2. scripts/ci/guard_test_diagnostics.py adds opt-in expanded evidence: up to 64 failed callable groups and eight sanitized actual mutation identities per group, with truthful omitted counts. Compact callers retain eight groups and their existing shape. Only literal True enables expansion. No raw source, signature, exception context, subprocess streams or absolute paths are added. Verdicts and input reports are not modified.
3. scripts/test_verify_db_access_boundaries.py:5832-5835 enables that detail only in the existing real-tree assertion. The pass-through spy still observes the actual production evidence call once; it does not rescan, replace evidence or bypass a gate. The exit-zero assertion remains unchanged.
4. Regression coverage was authored in test_guard_test_diagnostics.py, test_db_guard_policy_v2_evidence.py and test_verify_db_access_boundaries.py. The new parameterizations represent 36 intended pytest nodes by source inspection, not executed or collected evidence. One existing exact-operation loop also gains a case.

## Meaningful regression assertions

- Both maintained extractors detect direct, multiline, database-chain and typed-alias snapshot nulling calls; comments, ordinary/raw strings and unrelated nullableSnapshotCount calls are not mutations.
- The production v2 evidence API accepts the exact fixture permission with matching DAO inventory identity and a direct barrier.
- Missing or textual-only operations remain MUTATION_NOT_FOUND; an unlisted real operation remains UNLISTED_MUTATION; a prefix near-match cannot use the exact permission; removing the direct barrier remains BARRIER_METADATA_INCONSISTENT.
- Expanded diagnostics show all twelve synthetic failed groups, retain exit 2 and trusted false, preserve report/evidence inputs, reject malformed identities, disclose truncation beyond both bounds, and sanitize groups beyond the old eight-row page.
- The minimal RoomInventory fixture proves DAO identity checking, not device SQL execution or real retention behavior.

## Evidence adjudication and remaining policy work

The last completed human static-guards run inspected was vr-20260927-162320-b157cd3c: FAIL, exit 2, 18/25 guards passing, five violations and two infrastructure failures. Its start/end fingerprint was e16a31f8a71fe3e8f0aa4489731d61a714999ecac50dd4206db70ea826af5ba4. Pytest collected 4459 tests: 4428 passed, three failed, 28 skipped. That is historical evidence, not validation of this patch.

The saved real-tree failure reported twelve failed callable groups, but the former diagnostic bound displayed eight and omitted four. The validator handback was therefore incomplete. In addition, trusted:false with findingCount:0 is not proof of zero DAO violations: source-evidence failure stops the pipeline before the full scan and the infrastructure report clears findings.

No ownership-policy reconciliation is included here. Obtain the next complete bounded failed-group inventory first; do not invent the missing four identities or delete live grants to make the guard pass. Follow the prior adjudication for the eight displayed sites: moved audit helper, delegated subscription operations, still-live snapshot nulling, updateTokenIfConnected, bank-scoped pending-review deletion, notification failure bookkeeping, and the nine-parameter captureForRetry identity. Verify exact owner, callable signature, DAO operation, barrier and replacement coverage before any policy delta.

The detector correction may change which stage fails next; neither an exact remaining failure count nor a passing suite is promised. If more than 64 groups or eight mutations per group remain, the omission counts must be addressed explicitly, not treated as complete evidence.

## Review and acceptance

Author source review/read-back completed for the six-file delta, including both extractors, exact matching, evidence serialization, fixture expectations, output bounds and preservation. This is not independent strict-review or guardian approval. No execution-based PASS is claimed.

Reference FINAL_CI_GUARD_ACCEPTANCE_GATE.md: FG-03 remains fail-closed; FG-06/FG-07 prohibit unauthorized permission or exception expansion; FG-23 still requires self-protection evidence. Negative tests are authored, not a substitute for execution or base/head review. Recursive test selection, existing assertions and all permission files remain intact.

## Human-only validation handoff

After the required review and a fresh global quiescence check, freeze the entire worktree, including reports, and run from the repository root:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; inspect the persisted run." }
```

Result for this batch: NOT RUN. This is the full recursive suite through the serialized runner. guard_tests is a suite segment, not a standalone registered GuardId. Do not invoke pytest or guards directly, narrow selection, run concurrent validation, or convert skipped/unknown/infra outcomes into passes. If a run remains RUNNING, poll that run rather than starting another.

Preserve result.json, completion marker, stdout/stderr and the guard_tests failure blocks; associate them with the exact start/end fingerprints. Verify new test-node execution individually, inspect the expanded evidence in test_current_db_gate_activated_policy_real_config_pipeline, and reconcile every remaining group against source. An expected diagnostic failure is still FAIL, never GREEN.

## Other closure items remain open

This batch does not close W2-R3 / CA-P-03-003 generic receipt/PDF partial-OCR propagation, the separately documented targeted-evidence gaps and WorkerRunLogger adjudication, device gates, independent strict/guardian reviews, or unrelated guard/infrastructure debt. See the immutable independent review and subsequent adjudications under workflows/active. A commit requires separate explicit authorization. Wave 2 must not be marked complete.
