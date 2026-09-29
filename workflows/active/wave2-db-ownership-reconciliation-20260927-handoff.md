# Wave 2: exact DB ownership-policy reconciliation

Date: 2026-09-27. Status: **IMPLEMENTED_UNVERIFIED**. This is a scoped implementation handoff, not independent strict/guardian approval and not Wave-2 closure.

## Scope and safety

- Single agent; no subagents. Human-run validation policy preserved. No builds, pytest, guards, imports, syntax checks or other validation executed by this agent.
- Preserved existing dirty/untracked implementation, validator edits, campaign records and git history. No commits, staging, resets, stashes, branch changes or worktree operations.
- Only the active ownership policy, its exact-cardinality assertions, one new regression module and this new handoff were changed in this batch. No production Kotlin, detector/guard implementation, baseline, allowlist, RawQuery pin, structural manifest or generated artifact changed.
- Governing references: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07 and FG-23. No skipped/unknown result is accepted as passing; no scanner bypass, blanket permission or reduced recursive test selection was introduced.

## Verified incoming evidence

- Full human run: `vr-20260927-172227-c23b5516`; completion marker present; FAIL / exit 2 / E_COMMAND_FAILED.
- Started 2026-09-27T17:22:27.1988884Z; finished 2026-09-27T17:38:16.7546607Z.
- Executed command recorded by the runner: `python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards`.
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`. Start and end fingerprint: `6c4ef46727ea84c5ac38891c9d2857883b050899110d056ca2fd200ae24b36c9`.
- 18/25 guards passed. Violations: time_boundaries, cancellation, event_writers, raw_money_aggregates, guard_tests. Infrastructure failures: known_good_state, db_access.
- Pytest: 4,495 collected; 4,464 passed; 3 failed; 28 skipped. All 36 detector/diagnostic regression cases were individually found passing in the preserved log; this is evidence for the preceding repair, not the policy changes below.
- The preserved failed-group summary has failedGroupCount 11 and omittedGroupCount 0, with no omitted actual mutations. The RetentionModule nullSnapshotsOlderThan permission remains intact after the detector repair.
- The three remaining pipeline tests are test_current_db_gate_activated_policy_real_config_pipeline, test_default_project_root_uses_canonical_manifest, and test_fixture_manifest_mismatch_is_fail_closed_and_production_defaults_stay_strict.
- DB_POLICY_SOURCE_EVIDENCE_INVALID is an untrusted/incomplete scan. Its findingCount 0 does NOT prove absence of unauthorized DAO access in later stages.

## Reconciliation matrix: all eleven reported groups

Anchors below refer to the current source inspected for this batch. Paths are indexed in the following section. Every repair remains IMPLEMENTED_UNVERIFIED until execution against the repaired snapshot.

| # | Reported group | Source-backed reconciliation | Source anchors |
|---|---|---|---|
| 1 | DataRetentionWorker.doWork / auditDao.insert NOT_FOUND | Move the exact permission to emitRetentionAudit, with its four actual parameter types and direct barrier mode. The helper checks the barrier locally; doWork no longer receives a raw insert grant. | DataRetentionWorker 409-435; barrier 423; insert 428; callers 208, 288 |
| 2 | SubscriptionManagementRepository.deleteSubscriptionById NOT_FOUND | Remove obsolete direct deleteById grant; preserve actual RecurringRuleLifecycleCoordinator.deleteRule ownership and all five of its operations. | SubscriptionManagementRepository 64-74; real deleteRule contract retained |
| 3 | SubscriptionManagementRepository.updateSubscription NOT_FOUND | Remove obsolete direct update grant; the repository delegates to the rule lifecycle coordinator. Preserve/reconcile its actual transaction helper instead. | SubscriptionManagementRepository 64-74; rule updateRule 335-357 |
| 4 | BankApiIntegration.refreshToken / updateToken NOT_FOUND | Replace with the exact updateTokenIfConnected operation, preserving the BankConnection signature and owner. Do not authorize unconditional updateToken. | BankApiIntegration 546-572; barrier 557; BankConnectionDao conditional SQL 59 |
| 5 | RecurringRuleLifecycleCoordinator.updateRule NOT_FOUND | Remove all five obsolete raw grants, not only the first reported event mutation. Add six exact writes to reconcileUpdateInCurrentTransaction(ManualRecurringExpense, ManualRecurringExpense, Long). Preserve critical event ownership in RoomRecurringLifecycleEventWriter.writeCritical. | updateRule 335-357; helper writes 463-483; critical event delegation 512 |
| 6 | BankConnectionLifecycleCoordinator.disconnectConnection UNLISTED | Preserve disconnect; add only pendingReviewDao.deleteByBankConnectionScope under the same Long signature and transaction helper contract. | 207-222; barrier/transaction 217; scoped delete 219; disconnect 221 |
| 7 | NotificationIntakeCoordinator.capture UNLISTED | Preserve insertOrIgnore; add markEnqueueFailed with the exact existing thirteen-parameter callable identity. | enqueue-failure barrier 230; write 234 |
| 8 | RecurringLifecycleCoordinator.markReminderFailed UNLISTED | Preserve event insert and markFailedFromClaimed; add cancelClaimedDelivery. Correct stale reasons: permission denial cancels a claimed delivery, whereas other failures use the transient-failure transition. | 1122-1179 |
| 9 | WarrantyExpirationWorker.deliverReminder UNLISTED | Preserve four existing operations; add only markFailedByKey with the exact five-parameter signature and workerMediated mode. Correct the existing markFailed reason to cover parent-ineligible and post-not-delivered branches. | 163-229; keyed fallback 201; WarrantyReminderDeliveryDao 140-150 restricts key and CLAIMED status |
| 10 | NotificationIntakeCoordinator.captureForRetry PARSER_UNCERTAIN | Correct eight to nine parameter types by including DeferredCaptureStorageSnapshot. Enumerate insertOrIgnore, markEnqueueFailed and markFinalFailure. Correct the stale fingerprint/storage reason rather than weakening callable resolution. | signature 281-291; barrier/transaction 403-406; writes 423, 430; enqueue failure 501-505 |
| 11 | RecurringPlanProjectionService.projectFromRule CALLABLE_MISSING | Remove deleted non-atomic entry point. Preserve projectFromOccurrencesInCurrentTransaction; correct its inaccurate reason because it rechecks the barrier while the caller owns the transaction. | current projection entry point and barrier at 65 |

### Two additional missing methods found in the same affected owners

The failed-group inventory enumerates policy-side evidence failures; a method with no policy row can be absent from that list. Tracing the affected owners found two such methods that would otherwise remain unmapped:

1. `SubscriptionManagementRepository.updateSubscriptionCategory(Long, String)`: one direct grant for ManualRecurringExpenseDao.updateSubscriptionCategory. Source 97-101 updates subscriptionCategory only; it does not mutate rule scheduling semantics. The original narrow carveout is documented in `docs/analyses and debug master/remediation/RP-04-recurring-subscriptions.md` 27-34 and 217-225, and `docs/analyses and debug master/campaign CA-2026-09-21/cell-P-04-audit.md` 81. Consumer inspection found display usage, not occurrence/matching semantics.
2. `BankConnectionLifecycleCoordinator.persistOutcome(Long, BankSyncOutcome)`: two direct grants for BankConnectionDao.updateSyncStatus and updateSyncStatusOnly. Source 170-190 checks the barrier at 173; success/partial advances lastSync, while failures persist terminal status only. This implements RP-17 D12, not a new bypass: `docs/analyses and debug master/remediation/RP-17-bank-sync.md` 8, 18, 39 and `docs/analyses and debug master/remediation/RP-00-DECISIONS-RECORDED.md` 33.

All added rows retain the existing owning metadata and linked issue for the applicable owner, with exact path, owner FQCN, callable kind, receiver, full parameter types, DAO accessor/type, operation and barrier mode. No wildcard authorization was added. Independent policy-delta review under FG-06/FG-07/FG-23 remains required.

### Cardinality and revoked permissions

Active entries: **413 -> 420**. Three identities are moved/replaced one-for-one; eight obsolete entries are removed (two subscription, five updateRule, one deleted projection); fifteen live exact entries are added. Thus 413 - 8 + 15 = 420. Including identity moves, the identity-set delta is eleven removals and eighteen additions. The two exact cardinality assertions were updated with this derivation; this is not a baseline or ratchet update. Full production-pipeline success assertions remain strict.

## Source path index

All paths below start with `app/src/main/java/com/yourname/expensetracker/`:

- `data/privacy/DataRetentionWorker.kt`
- `data/repository/SubscriptionManagementRepository.kt`
- `domain/bank/BankApiIntegration.kt`
- `domain/bank/BankConnectionLifecycleCoordinator.kt`
- `domain/notification/capture/NotificationIntakeCoordinator.kt`
- `domain/recurring/RecurringPlanProjectionService.kt`
- `domain/recurring/lifecycle/RecurringRuleLifecycleCoordinator.kt`
- `domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt`
- `domain/recurring/lifecycle/RecurringLifecycleEventWriter.kt` (RoomRecurringLifecycleEventWriter)
- `service/warranty/WarrantyExpirationWorker.kt`

## Regression coverage authored, not executed

New module: `scripts/test_wave2_db_ownership_reconciliation.py`. Source inspection yields **54 intended pytest cases**; collection/execution is NOT claimed:

- 10 exact reconciled callable contracts and 3 preserved-owner contracts (lines 176-183).
- 26 permission cases, each rejecting nine near-miss identity fields rather than accepting approximate matches (189-206).
- 5 deleted/delegating callable denial cases; unconditional token operation and old deferred-capture signature denial (209-232).
- Real production source and canonical Room inventory prove 13 callable groups / 33 mutations, with no fixture policy substitution (235-265).
- 6 remove-one-permission counterfactuals must still produce UNLISTED_MUTATION from the real source-evidence API (268-289).
- The old eight-parameter deferred signature must remain PARSER_UNCERTAIN under real callable resolution (292-298).

No existing test assertion was relaxed, recursive selection narrowed, production API monkeypatched, guard exception added or skip introduced. The previously failing full-production pipeline tests remain acceptance gates. Source review/read-back is complete; it is not execution evidence or an independent reviewer verdict.

## Snapshot and preservation evidence

- Branch `bug-fixes`; HEAD `a48076322e57f3d312f34cf6a71139efc4fcc48b`; index empty.
- Turn intake: 167 dirty paths; fingerprint `48e0bea5b4519786c2513e36b07d58eafd4059d8f6e00e16962d839369669d3b`. Compared with the incoming tested snapshot, only the campaign JOURNAL.md had changed; its hash was `f036aed489ab7d237b75ce2c2b73ff3cbb09512b0ff4b1dc32c7657300921f49`.
- Reviewed pre-handoff source snapshot: 2026-09-27T21:22:47.7115704+03:00; 168 dirty paths; fingerprint `381f9d918c485ff2d1bf15100e396b2d1d99dc346c935752251975463f618e01`.
- Hash comparison to intake found exactly the following three changed/added files and no removals. All other pre-existing dirty-file hashes were unchanged. This new handoff is the fourth touched path; writing it necessarily changes the full worktree fingerprint.

| File | Reviewed SHA-256 |
|---|---|
| `config/guards/db_ownership_policy.yml` | `9d91711177bea287c44ad5f7f28d4857805f6ac0f3f66ee022a890c50e4917e2` |
| `scripts/test_verify_db_access_boundaries.py` | `d2f5964d512600ea076598dd8b088a8de7f9ccfbc7d88cd0e4423727f3b6d924` |
| `scripts/test_wave2_db_ownership_reconciliation.py` | `2343c9045340d2128b15734f81fd9a2b17ccc24c36affbfa11715fb34d1fa2a3` |

Quiescence checks found no active lock, STARTING/RUNNING result or validation process before writes. A separate localhost Python HTTP server was not a validation process and was left untouched. The next runner result must capture its own exact start/end fingerprint including this report; matching branch/HEAD alone is insufficient.

### Provenance correction

The four newly visible recurring/warranty failed groups are not all newly introduced by the remaining 128-file implementation. Current dirty diffs change activateRule CAS and warranty parent eligibility, not the older update helper or keyed delivery recovery. RecurringLifecycleCoordinator and the deleted projectFromRule path trace to RP-04 commit `9101aab3c3f6cc4cbcc28413bd8934b970ade8be`; keyed warranty recovery traces to RP-16 commit `1bca7e5c896de237a3eee8e98c75902a0afdb355`. Later CL-05 currency work also touched the projection file. File overlap alone does not establish defect provenance.

## Human validation handoff - NOT RUN

After required review and a quiescence check, freeze the entire tree, including review artifacts, and execute one serialized full profile from the repository root:

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Static guards did not PASS; inspect the persisted run." }
```

The human wrapper uses the validation runner Start/Wait and global serialization. Do not launch direct pytest or a parallel run. If a run remains RUNNING, poll that same run ID; do not rerun. guard_tests is a full-suite segment, not a standalone registered GuardId.

Required evidence:

1. Durable result.json, completion marker, run ID and equal start/end worktree fingerprints for the repaired bytes.
2. Actual collection and execution of all 54 new cases and the preceding 36 detector/diagnostic regression cases; no unexpected new skips or reduced discovery.
3. All three previously failing production-pipeline tests execute; db_access must produce a trusted, complete result. New downstream findings, if exposed, must be adjudicated rather than suppressed.
4. Full-suite comparison against the incoming run by guard/test identity, not only aggregate counts. Other known failures remain blocking even if db_access is repaired (FG-03).

## Remaining Wave-2 closure gates

- This policy repair has no post-edit execution evidence yet. Removing stale stage-4 groups can expose additional stage-5/6 failures; a clean DB result is not promised.
- Existing time_boundaries, cancellation, event_writers and raw_money_aggregates violations, plus known_good_state infrastructure failure, remain outside this repair. Do not describe the whole suite as green.
- Independent strict and guardian approvals, including protected-base/head policy-delta scrutiny under FG-23, remain pending. This author source review is not their substitute.
- The earlier independent review still marks W2-R3 / CA-P-03-003 partial: generic PDF receipt partiality is not carried through the complete persisted/UI path. See `workflows/active/wave2-independent-deep-review-20260927-100231Z.md` 69-73, 384-390 and 652. No production change in this batch fixes that.
- Earlier targeted-evidence discrepancies remain unresolved by a static-guards run. See `workflows/active/wave2-post-validation-adjudication-20260927-124138Z.md`: WorkerRunLogger environmental adjudication was not fully established, and six CL-09 filters lacked current evidence (ExpenseRepositoryMerchantKeyBackfillTest, MerchantKeyBackfillWorkerTest, WarrantyReminderDeliveryDaoTest, WarrantyExpirationWorkerTest, ReceiptMatchingViewModelTest, ReceiptLinkServiceColumnScopeTest). Do not reuse a bare 52/52 statement as proof.
- Device/instrumented PDF, image and MerchantKeyBackfill gates remain pending. No commit was made; commit/closure requires its own explicit decision after the evidence and approval gates.

Prior immutable handoffs were retained: `workflows/active/wave2-db-source-evidence-adjudication-20260927.md` and `workflows/active/wave2-db-evidence-detection-repair-20260927-handoff.md`.
