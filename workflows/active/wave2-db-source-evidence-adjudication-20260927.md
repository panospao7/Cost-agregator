# Wave-2 DB source-evidence handback adjudication — September 27, 2026

VERDICT: FAIL — the proposed blanket eight-site policy reconciliation is not sufficiently justified. The completed validation is real, but its summary omits four failed groups and misclassifies at least one live mutation as absent.

## Scope and safety

Single-reviewer, read-only source/log/provenance adjudication. No subagents, live validation, production edits, test edits, guard edits, policy edits, baseline/allowlist changes, artifact regeneration or Git history operations. This new report is the only file created in this step. Prior reports and campaign records remain untouched.

## 1. Verified run and snapshot

- Run: `vr-20260927-162320-b157cd3c`; profile `static-guards`; completed September 27, 2026 at 16:42:43Z (19:42:43 +03:00).
- Result: FAIL, exit 2, E_COMMAND_FAILED; completion marker present. Actual command: `python.exe scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards` (executed by the human-owned validation runner, not by this reviewer).
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`; start and end runner fingerprints both `e16a31f8a71fe3e8f0aa4489731d61a714999ecac50dd4206db70ea826af5ba4`.
- Suite: 18/25 PASS, five blocking violations, two infrastructure failures. These are suite-level classifications, not proof that violation fingerprints are unchanged.
- Pytest log explicitly says **4459 collected, 4428 passed, 3 failed, 28 skipped**. The forwarded count of 4456 is not the collected total.
- All 16 newly authored receipt-policy parameterized cases have individual PASSED lines: five exact contracts, five obsolete full-row denials, six near-miss identities. This establishes execution of those policy regressions, not full application correctness or a passing DB-access gate.
- The three failing assertions remain the real-config in-process pipeline and the two tests sharing the canonical real-tree subprocess.

Artifacts read: `build/validation-runs/vr-20260927-162320-b157cd3c/{result.json,stdout.log,stderr.log,complete.marker}` and `build/ci/static-guards/guard_tests.log`.

Artifact SHA256:
- result.json: `dc34a0d3c0ddda1f276520f2bee4d4a252480db8d8ae83db339fafba7dbde9a4`
- guard_tests.log: `74377c61e2cc1bd5ad8aa84a37bd6919d8bff4f34bb8adfcb716ba2105ef3044`

Review intake: branch `bug-fixes`, index empty, 163 dirty paths, fingerprint `d71ab076830c5786ba2d3cf3a7e5ee623eae985f85ac495c217de01cf2bd01f6`. Compared with the tested receipt-handoff snapshot, only campaign JOURNAL.md changed; its current SHA256 is `8296908a4670a2957dedd0d92991c2b1c55a2cf9ba9d4fbd04b96fceda0d51e2`. The reviewed implementation remained unchanged. No active lock or matching validation process was observed. The same fingerprint was reconfirmed at 19:55:06 +03:00 before preparing this report.

## 2. Corrections to the validator handback

### A. Untrusted zero findings are not a clean scan

`scripts/verify_db_access_boundaries.py:3685–3697` rejects untrusted source evidence at stage 4. The full discovery/authorization scan at `:3721–3725` is reached only after trusted evidence and the structural gate. On infrastructure failure, `:3767–3772` deliberately emits an empty findings array with trusted=false. Therefore findingCount=0 does NOT establish that no raw-DAO violations exist. Downstream findings remain unknown. Apply `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03.

### B. The displayed eight groups are not the complete inventory

The actual assertion at `build/ci/static-guards/guard_tests.log:4474` reports failedGroupCount=12 and omittedGroupCount=4. `scripts/ci/guard_test_diagnostics.py:16,59–62,89–94` intentionally shows at most eight groups and retains the omission count. Its output is a bounded summary, not the full internal diagnostic JSON. The common umbrella code is not proof of a single underlying defect. No complete current-run source-evidence artifact was located in the scoped build/ci and build/guard-debug inspection; the per-run cache is memory-only (`scripts/run_cache.py`).

### C. The retention mutation still exists; do not delete its permission

`app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt:470–473` still calls `transactionEventDao.nullSnapshotsOlderThan(cutoffMs)` from the registered target. `data/database/dao/TransactionEventDao.kt:43–50` defines the set-based UPDATE that nulls beforeSnapshot/afterSnapshot. The exact active grant is `config/guards/db_ownership_policy.yml:3703–3720`.

The actual source-evidence verifier imports its extractor from `scripts/db_guard/policy_parsing.py` (`policy_v2_evidence.py:152–157`). That module’s detection vocabulary (`policy_parsing.py:709–737`) omits nullSnapshotsOlderThan and any matching null-prefixed token; the legacy duplicate at `scripts/verify_db_access_boundaries.py:169–197` has the same gap. This is a concrete detection omission, not evidence that the production mutation vanished. Preserve the live grant and repair detection with positive and fail-closed negative coverage. Merely dropping the row could hide an existing privacy-retention write.

### D. Parser-uncertain is also used for stale exact signatures

`NotificationIntakeCoordinator.captureForRetry` has nine parameters, including DeferredCaptureStorageSnapshot (`NotificationIntakeCoordinator.kt:281–291`); its active policy row still lists eight (`db_ownership_policy.yml:4155–4178`) and describes the older DEFERRED-fingerprint behavior. The evidence verifier deliberately maps same-name signature mismatch to PARSER_UNCERTAIN (`policy_v2_evidence.py:867–959`; regression contract documented in `test_db_guard_policy_v2_evidence.py:108–115`). Do not relax overload/signature matching or assume a parser implementation bug from that code alone.

## 3. Source-backed adjudication of the eight displayed groups

Production paths below are relative to `app/src/main/java/com/yourname/expensetracker/`.

| Reported group | Current source evidence | Correct repair direction, subject to full review |
| --- | --- | --- |
| DataRetentionWorker.doWork / auditDao.insert | `data/privacy/DataRetentionWorker.kt:409–435`: the insert moved to emitRetentionAudit, with its own write-barrier check; doWork calls that helper. Policy still grants doWork at line 402. | Reconcile the exact helper identity and barrier ownership; do not simply erase audit-write coverage. |
| SubscriptionManagementRepository.deleteSubscriptionById / subscriptionDao.deleteById | `data/repository/SubscriptionManagementRepository.kt:68–74` delegates to RecurringRuleLifecycleCoordinator.deleteRule. | Remove obsolete direct-repository authorization only after confirming legal coordinator coverage and negative tests preventing bypass. |
| SubscriptionManagementRepository.updateSubscription / subscriptionDao.update | Same file `:60–66` delegates to updateRule. | Same single-writer rule; no restoration of direct DAO mutation to satisfy stale policy. |
| RetentionModule.provideRetentionTargets / nullSnapshotsOlderThan | Live source call and mutating SQL remain, as detailed above. | Fix the detection gap; retain the exact existing permission. |
| BankApiIntegration.refreshToken / updateToken | `domain/bank/BankApiIntegration.kt:557–572` uses updateTokenIfConnected, maps zero affected rows to Disconnected, and does not resurrect disconnected tokens. DAO SQL has WHERE id=:id AND isConnected=1 (`BankConnectionDao.kt:53–66`). | Reconcile the exact conditional-operation identity; retain CAS behavior and denial of obsolete operation identity. |
| BankConnectionLifecycleCoordinator.disconnectConnection / unlisted mutation | `domain/bank/BankConnectionLifecycleCoordinator.kt:216–222` deletes connection-scoped pending reviews and disconnects atomically; existing row covers disconnect only. `PendingReviewDao.kt:684–691` scopes deletion by bankConnectionScopeHash. | Review a separate exact pendingReviewDao.deleteByBankConnectionScope grant; existence in source alone does not authorize it. |
| NotificationIntakeCoordinator.capture / unlisted mutation | `domain/notification/capture/NotificationIntakeCoordinator.kt:230–240` calls intakeDao.markEnqueueFailed inside runWrite; current row covers insertOrIgnore only. | Review exact enqueue-failure transition authorization and its state/attempt predicates; no blanket capture-method permission. |
| NotificationIntakeCoordinator.captureForRetry / parser uncertain | Nine-parameter declaration versus eight-parameter policy. Current body also has markFinalFailure (`:423–428`), insertOrIgnore (`:430`), and markEnqueueFailed (`:505–511`), not just the old single insert. | Correct exact signature and assess every mutation after the signature stage; do not stop after making the parser stage pass. |

**Four additional failed groups are not identified by the bounded saved summary. Their identities, causes and proposed permissions remain UNKNOWN. This is not a complete twelve-group repair plan.**

## 4. Provenance — not new remaining-batch production mutations

The six implicated production files have no uncommitted diff. Exact line provenance and ancestry checks show these changes already existed at HEAD:
- Retention audit helper: `1bca7e5c896de237a3eee8e98c75902a0afdb355`, September 21, 2026, RP-16.
- Subscription delegation: `9101aab3c3f6cc4cbcc28413bd8934b970ade8be`, September 20, 2026, RP-04.
- Live snapshot-null retention target: `fea8cdfe015dcca10a2a0479c514e1c0f3206594`, September 21, 2026, RP-14.
- Conditional token update and scoped disconnect cleanup: `7afaf1acc78815a7eefc113b4a3c50efaa03b693`, September 21, 2026, RP-17.
- Notification enqueue-failure mutation: `442411b829ae771e04034799f0531c5e3e3796cf`, September 16, 2026, RP-10 10b.
- Deferred capture signature and legacy transition: `09680da5e5ceb6f9ed14d5110b86871b748d3d10`, September 15, 2026, RP-10 10a.

All listed commits were verified ancestors of HEAD. Historical provenance establishes when code landed, not automatic approval of new guard authorizations or proof of behavioral correctness.

## 5. Safe next repair batch

1. Capture all twelve failed source-evidence groups through bounded, sanitized pagination or a bounded diagnostic artifact during the normal test/runner path. Preserve the verdict, exact assertions, recursive selection and public fail-closed protocol; do not run an unapproved ad hoc verifier.
2. Repair the demonstrated nullSnapshotsOlderThan detector gap, covering both maintained extraction paths. Tests must prove live exact calls are detected, comment/string lookalikes are not evidence, absent/wrong identities remain rejected, and an unlisted live mutation still fails.
3. Trace every actual mutation to approved lifecycle ownership, transaction/barrier semantics, callers and approved RP decisions. Reconcile obsolete identities only after that review; separately document and obtain explicit approval for any new or broadened authorization under `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-06/FG-07. An owner and reason field alone do not satisfy that approval boundary. The earlier human approval covered the five-to-six receipt reconciliation, not blanket completion of these other scopes.
4. Preserve protected-base/head and policy-delta evidence under FG-23. Do not alter baselines, RawQuery pins or production behavior just to satisfy a policy name.
5. After the repair/review gates, the human can run the complete serialized profile from the repository root: `pwsh scripts/vrun.ps1 -Worktree . -Profile static-guards -MaxTotalMinutes 130`; stop on nonzero exit. guard_tests is a suite segment, not a standalone registered guard. Freeze the worktree during execution and retain matching start/end fingerprints plus the completion marker.

This reviewer ran none of those validation commands. No promise is made that reconciling the displayed rows will make the next full scan pass: stage-6 discovery was not reached and four source-evidence groups remain omitted.

## 6. Wider Wave-2 closure remains open

Clearing this infrastructure failure would not close the outstanding generic receipt/PDF partial-OCR propagation defect (W2-R3 / CA-P-03-003), reconcile all targeted-test evidence, satisfy device gates, or supply independent strict/guardian and FG-23 reviews. Existing non-DB guard debt also needs its own adjudication. No Wave-2 completion or commit is authorized or claimed.
