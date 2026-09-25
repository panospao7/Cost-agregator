# CL-01 review-completion — rp-23 / Wave 1

Date: 2026-09-25 (+03:00). First reviewer: astra high.
Reviewed implementation HEAD: `cbf2a01199d5301e63ba23b96203416bd5cbbc75` (`rp-23-wip`).
Required base: `ad412aecb1347daebee7fef62aca745cf205f0aa`.
Spec: `CL-01-notification-capture-privacy-extraction.md` in this directory, read in full.

**VERDICT: FAIL — 0/2 DONE-CORRECT. WI-1 PARTIAL; WI-2 DONE-DEFECTIVE. One confirmed diff-introduced test regression; no confirmed new production regression.** Both requested production changes match the spec by inspection, but the live-path assertion captures the wrong coordinator parameter, boundary proofs remain incomplete, and neither named class has execution evidence. Compile success is not test execution.

## 1. Environment, scope and provenance

- Verified directory `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-23`, branch `rp-23-wip`, HEAD above, and clean initial/pre-artifact status. `git merge-base --is-ancestor ead80016 HEAD` returned 0.
- Read scoped segment/inventory ownership (notification/capture/privacy), `docs/architecture/LEGAL_PATHS.md:838-892`, and the privacy/notification impact matrix in `ENGINE_INTERACTION_MAP.md`. Read all hunks of the required diff, both test fixtures and the supporting gate, extractor, filter and coordinator. No active handoff files were present in `workflows/active`.
- **No subagents, builds, tests, lint, guards or validation-runner actions were run.** Existing validation files were read only. Only this new artifact is authored; production/test source, JOURNAL and other campaign documents remain unchanged by the reviewer.
- The full required comparison includes inherited rp-25 repairs and CL-18 work. CL-01 itself consists of `55d245ca` and `cbf2a011`, parent `edc4275f`. Its delta is exactly the three allowed CL-01 paths: **504 insertions, 103 deletions**. The full base-to-HEAD diff has **15 files, 1,443 insertions, 124 deletions**; its 12 outside-fence paths are all inherited, not CL-01-authored scope violations. Exact inventories follow in the appendix.
- Inherited CL-18 reviews describe their historical pinned revisions, not current CL-01 completion. This review does not recertify CL-18 or prescribe undoing merged prerequisites. The obsolete five-file test-compilation blocker is already repaired in ancestor `ead80016`.

### Citation key — line numbers at reviewed HEAD

- **Service**: `app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt`
- **DeferredTest**: `app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceDeferredPolicyTest.kt`
- **FallbackTest**: `app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceFallbackTest.kt`
- **Coordinator**: `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationIntakeCoordinator.kt`
- **CaptureGate**: `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationCaptureGate.kt`
- **Parts**: `app/src/main/java/com/yourname/expensetracker/domain/notification/capture/NotificationTextParts.kt`
- **Filter**: `app/src/main/java/com/yourname/expensetracker/service/NotificationFilter.kt`
- **V**: this worktree's `build/validation-runs/`, not the parent checkout.

## 2. Per-work-item verdicts

DONE-CORRECT requires the specified implementation, boundary coverage and completion evidence. PARTIAL distinguishes correct-looking source from missing proof/gates; DONE-DEFECTIVE identifies a concrete defect in the delivered item.

| Spec item | Verdict | HEAD diff evidence / remaining acceptance |
| --- | --- | --- |
| WI-1 — authorize deferred capture before extraction | **PARTIAL** | `55d245ca`: Service:456-511 checks capability, one fresh settings snapshot/master toggle, then fresh DAO lookup, each under its own 300-ms timeout. All exits precede extraction at 514 and app-name/handoff at 525-540. All three cancellation catches account then rethrow; helpers at 749-784 use controlled codes and hashed metadata. Initial restore/shutdown checks and storage handoff remain. DeferredTest has 21 authored cases but does not observe zero extraction, cancellation completion/rethrow, or all forwarded fields/exactly-once handoff. Named tests never executed. |
| WI-2 — feed combinedBody to live filter | **DONE-DEFECTIVE** | Service:571 changes only the fourth argument. Extraction:548, fingerprint:552 and capture:653 share the same `parts.combinedBody`. `cbf2a011` adds eight real-service tests, but FallbackTest:83 captures coordinator argument **7 (text)** rather than **8 (combinedBody)**: R-1. Extended exclusion reasons and a guaranteed messages-only fixture also lack decisive assertions. Named tests never executed. |

### Enumerated boundary coverage — authored is not executed

| Spec boundary | Authored evidence | Assessment |
| --- | --- | --- |
| WI-1.1 Denied / FailClosed / NotApplicable | DeferredTest:150-188 | Separate cases verify no later settings/DAO/handoff and await terminal accounting. No extraction/app-name observation. |
| WI-1.2 Allowed then disabled toggle, STORE_RAW retained | DeferredTest:190-203 | Correct fixture; no DAO/handoff. Extraction unobserved. |
| WI-1.3 enabled + blocked | DeferredTest:205-218 | Fresh DAO verified, no handoff; extraction unobserved. |
| WI-1.4 ordinary exception and local timeout at every read | DeferredTest:247-320 | All six cases, controlled terminal code, no handoff; runtime unproven. |
| WI-1.5 parent cancellation at each read | DeferredTest:322-355,486-497 | Mocks throw CancellationException and await CANCELLED accounting. No actual parent cancellation or child completion-cause assertion; replacing rethrow with return could pass. This improves the old sleep/earlier-event check but does not prove propagation. |
| WI-1.6 ordering, fresh DAO, single snapshot | DeferredTest:220-245 | coVerifyOrder, exactly one settings/DAO lookup and REDACTED mode. Handoff not verified exactly once; title/text/body/subText equality not checked. |
| WI-1.7 all four modes | DeferredTest:391-483 | RAW sensitive extras excluded, REDACTED marker, METADATA_ONLY/DO_NOT_STORE null extras. DO_NOT_STORE mock returns NotStored. Actual unchanged Coordinator:302-318 returns before encryption/insert; the service fixture alone is not a DB test. |
| WI-2.1 TEXT_LINES-only | FallbackTest:96-103 | Correct fixture/expectation, blocked by R-1. Correcting the slot makes reverted fourth-argument wiring lose the only money signal. |
| WI-2.2 INFO_TEXT / SUMMARY_TEXT; null/blank bigText | FallbackTest:105-122 | Both representative cases exist, blocked by R-1. The five-space expectation reflects existing Parts:85-89; do not normalize production whitespace to satisfy tests. |
| WI-2.3 real MessagingStyle message only | FallbackTest:124-135 | Real builder used, but no removal/assertion of builder-produced top-level text/bigText. Messages-only precondition is not established; add explicit fixture checks. |
| WI-2.4 top-level and repeated values | FallbackTest:137-146 | Real extraction/dedup retained, but R-1 compares the wrong field. Separate text-only/bigText-only controls prevent one masking the other. |
| WI-2.5 no amount / extended security / promotion | FallbackTest:148-173; helper:87-94 | Checks only FILTER_REJECTED. Security/promo fixtures have no qualifying top-level payment; reverting the fourth argument still rejects as NO_AMOUNT. Does not prove extended exclusion reasons. |
| WI-2.6 exact accepted combinedBody and revert sensitivity | FallbackTest:81-84 | Wrong parameter observed (R-1). Fix with named arguments and keep exact body expectations. |

## 3. Regression hunt and remaining findings

### R-1 — [P2 / MAJOR] New live-path helper captures text instead of combinedBody

**Introduced by `cbf2a011`; FallbackTest:81-84; authoritative signature Coordinator:76-89.** Six `any()` arguments precede `capture(combinedBody)`, placing it in slot 7 (`text`); combinedBody is slot 8. All five positive cases use the helper. In the repeat-field case the captured text is `Paid EUR 12.00 at Cafe`, while expected is `Bank Paid EUR 12.00 at Cafe`: equality cannot hold. In the lines/info/summary-only cases text is null, so a non-null slot cannot establish the expected body and may fail verification first. This is a **source-demonstrated test regression**, not an executed result or a production-forwarding defect.

Minimal fix: named coordinator arguments with `combinedBody = capture(bodySlot)`; exactly one accepted handoff. Do not weaken the expected body to top-level text or mock out the filter. See WI-3.

### G-1 — [P2 / MAJOR completion gap] Boundary assertions are weaker than their names

DeferredTest never observes extraction/app-name access and does not establish cancellation rethrow. FallbackTest's generic rejection assertions pass under the old wiring because NO_AMOUNT is also rejected; the MessagingStyle fixture does not establish exclusive message-field placement. These are incomplete planned proofs, not additional confirmed production regressions. See WI-3/WI-4.

### G-2 — [P2 / MAJOR completion gap] Neither named class reached execution

Section 4 records only failed pre-execution attempts and one old production-compile PASS. Merged rp-25 repairs make new named-class runs the required next step; they are not an unresolved prerequisite. This review is FAIL and does not claim a passed privacy-security-guardian gate.

### Safety / attribution checklist

- **Confirmed regressions: 1, test failure R-1. Confirmed new production regressions: 0.** Do not inflate missing coverage/evidence into additional runtime regressions.
- No newly added raw notification content, package/key, exception-message, stacktrace or free-form decision reason in CL-01 production diagnostics. Helpers use existing DiagnosticReasonCode constants and hashed package metadata. Existing adjacent raw-package/throwable logging is not newly introduced and is not swept into this fence.
- No new swallowed CancellationException: all three deferred catches emit controlled accounting then rethrow. Local timeouts drop fail-closed. Authorization is outside the NonCancellable handoff. Restore/shutdown guards remain; no POST_NOTIFICATIONS capture gate was added.
- No direct intake DAO/scheduler/worker bypass, schema, migration or guard/baseline modification. Coordinator barrier/transaction/encryption/storage semantics are unchanged. The similarly named privacy NotificationPrivacyGate:23-33 only checks the master toggle; the explicit fresh isBlocked query is necessary and present.
- Handwritten fallback expressions were replaced per spec, not disabled to hide failures. The cancellation test now waits for terminal accounting rather than sleeping. Its retained class comment at DeferredTest:60-61 incorrectly says cancellation must never become a terminal diagnostic; correct that comment during focused test work.
- Inherited CompositePrivacyGate cancellation repair is present and must be preserved. No CL-01-authored outside-fence source changes or weakened exclusions/assertions were found. Sequential authorization does not promise atomic exclusion against later setting changes.

## 4. Persisted validation evidence — read only

All ten local runs have completion markers. All identify `ad412aec` with dirty-worktree fingerprints, not the reviewed clean SHA. Nine targeted attempts record FAIL/exit 1; none has named test-case PASSED/FAILED/SKIPPED lines. No JUnit XML was found under this worktree's test-results location.

| Run under V | Profile / filter | Status / exit | Evidence |
| --- | --- | --- | --- |
| vr-20260923-150531-034be89a | targeted-unit-test / *NotificationCaptureServiceDeferredPolicyTest | FAIL / 1 | Wrapper distribution lock access denied; no tests. |
| vr-20260923-153128-b9d776e9 | deferred filter | FAIL / 1 | compileDebugUnitTestKotlin failure. |
| vr-20260923-164530-df1c42ca | deferred filter | FAIL / 1 | compileDebugUnitTestKotlin failure. |
| vr-20260923-165126-2114ec57 | deferred filter | FAIL / 1 | compileDebugUnitTestKotlin failure. |
| vr-20260923-181716-d6aa2701 | targeted-unit-test / *NotificationCaptureServiceFallbackTest | FAIL / 1 | compileDebugUnitTestKotlin failure. |
| vr-20260923-182110-6dc82988 | compile | PASS / 0 | Production compile only; no tests, not current certification. |
| vr-20260923-182225-e68a5e72 | fallback filter | FAIL / 1 | compileDebugUnitTestKotlin failure. |
| vr-20260923-182630-c8b3e6ef | deferred filter | FAIL / 1 | compileDebugUnitTestKotlin failure. |
| vr-20260923-183221-67829ef6 | fallback filter | FAIL / 1 | Latest fallback attempt, same five unrelated stale test contracts. |
| vr-20260923-183639-9af32efe | deferred filter | FAIL / 1 | Latest deferred attempt, same compile errors; no execution. |

Each row is backed by `V/<run-id>/result.json`, `stdout.log`, `stderr.log`, and `complete.marker`. Latest stderr concerns Keystore nullability, LegacyDataConsistencyChecker's DAO fake, ReceiptLifecycleCoordinator's constructor, BackupRestoreViewModel's success fixture and ExportOptionsViewModel's serializer. Their repairs already arrived through `9e8353bf`/`00ca7e1e` in `ead80016`, confirmed in the full diff. Do not prescribe those repairs again, exclude their sources, or call these compile attempts runtime test results.

## 5. DIRECT-FIX completion plan

Continue original numbering. Execute bounded batches; do not rewrite correct-looking production merely because tests are incomplete.

### WI-3 — Repair live-body assertion and discriminating filter fixtures (R-1/G-1, P2)

- **Location:** FallbackTest:81-173; read-only Coordinator:76-89, Parts:36-101, Service:546-585,645-659.
- **Defect:** wrong captured parameter; generic negative result does not prove extended exclusion; messages-only premise unestablished.
- **Change:** use named arguments and capture actual combinedBody; verify one accepted call. Retain exact body expectations and real service/extractor/filter. Keep a real MessagingStyle message bundle but remove any mirrored top-level payment and assert no other field contains it. For extended security/promotion, supply an otherwise accepted top-level payment and place the deny text only in the extended field; assert filterReason SECURITY_OR_AUTH or PROMOTION and zero handoff. Retain distinct NO_AMOUNT, text-only, bigText-only and repeated-value controls.
- **Acceptance criteria:** correct wiring meets body equality. Reverting only the service's fourth argument would reject extended-only positives and accept the extended-deny controls, so assertions detect the wrong wiring. No expectations weakened to top-level text.
- **Tests / validation:** existing NotificationCaptureServiceFallbackTest; exact named run in WI-5, after review. No tests changed or executed by this reviewer.
- **Fence:** FallbackTest only; no extractor/filter/coordinator policy edits.

### WI-4 — Prove pre-extraction authorization and cancellation boundary (G-1, P2)

- **Location:** DeferredTest:85-147,150-355,391-497; Service:456-541 read-only unless a new failing boundary case proves a local defect.
- **Change:** observe extraction and app-name access so every rejection/unresolved authorization asserts zero content access, not just zero handoff. Use a call-through extraction spy or guarded fixture that preserves real successful extraction. Test actual parent cancellation while suspended at each read; retain thrown-cancellation cases and observe child completion with cancellation, not merely the terminal event. Await completion/accounting, never a preceding nonterminal event or sleep. Assert one settings snapshot/DAO lookup/handoff and exact title/text/body/subText plus storage snapshot. Correct the stale class comment.
- **Acceptance criteria:** moving extraction before authorization or replacing rethrow with return fails a focused assertion. Four storage modes, controlled cancellation/drop diagnostics, fail-closed short circuit and no sensitive diagnostic payload remain intact.
- **Tests / validation:** existing NotificationCaptureServiceDeferredPolicyTest; retain the 21 cases or strengthened equivalents and add required missing boundaries. Named execution in WI-5.
- **Fence:** DeferredTest; only an evidence-required local Service fix if necessary. No coordinator/gate/composite/worker/schema/global coroutine refactor.

### WI-5 — Execute named classes and final compile on completed source (G-2, P2)

- **Location:** lane runner records and original Validation/Review gate.
- **Change:** after WI-3/WI-4 obtain passing strict/privacy-security review. No-subagent/no-validation restrictions apply to this review session and do not waive later gates. The sole validation-runner owner checks active runs/global lock and executes the following serially. The merged test-compilation repair is present; obtain expensive-run authorization where repository rules require it.
- **Acceptance criteria:** both classes actually execute with nonzero case counts including corrected/new cases, zero relevant failures/skips and terminal PASS; then compile PASS. Record exact revision/fingerprint, command, exit, result/log/marker paths. Poll RUNNING, never overlap/restart it. Missing/stale/timeout/infra-error is not PASS. Triage baseline failures without relaxing assertions. No broad suite or added guard profile prescribed.
- **Commands (instructions only; NOT EXECUTED):**

```powershell
Set-Location -LiteralPath 'C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-23'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action List
# Start the next profile only after the preceding run is terminal PASS.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*NotificationCaptureServiceDeferredPolicyTest'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*NotificationCaptureServiceFallbackTest'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
# For RUNNING, substitute the actual returned ID and repeat Wait until terminal:
# powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId <returned-run-id>
```

- **Fence:** no source changes authorized by this evidence item; no unrelated tests/guards or JOURNAL/campaign status edits. Re-review if execution requires changes.

## 6. Delivery

Only `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-01-review-completion.md` is added. Delivery SHA and journal line are returned separately after commit verification. The pinned implementation remains the review target. Validation by reviewer: **NOT RUN (prohibited)**. Completion remains blocked by R-1 and coverage/execution gates.

## Appendix — exact required Git inventory

### git log --oneline ad412aec..HEAD
```text
cbf2a011 test: cover notification combined-body filtering
55d245ca fix: authorize deferred notification capture
edc4275f test(backup): remove unrelated privacy fixture formatting delta
36d499db test(backup): preserve cancellation sentinel identity across coroutine recovery
8383fdcf test(backup): complete CL-18 export boundary coverage
984596ed fix(backup): preserve preflight failure result without maintenance cleanup
20e42214 docs: record CL-18 completion rereview
577c9e56 fix: complete CL-18 review remediation
86e7f244 docs: add CL-18 completion review
b10af5f6 fix: propagate privacy gate cancellation
83918e0f fix: fail closed backup exports
ead80016 merge: rp-25 test-recovery — unit-test compilation repairs + behavioral triage
00ca7e1e test: record trusted-tests + Phase B baseline triage (CANCEL-01 production violations, OOM-bounded baseline 5230P/214F)
9e8353bf test: repair unit-test compilation at HEAD (5 files, assertions unchanged) + triage behavioral failures
```

### git diff --stat ad412aec..HEAD
```text
 .../repository/DatabaseBackupRepositoryImpl.kt     |  40 ++-
 .../domain/privacy/CompositePrivacyGate.kt         |   3 +
 .../service/NotificationCaptureService.kt          | 130 +++++---
 .../KeystoreInstallationSecretHashingTest.kt       |   7 +-
 .../repository/DatabaseBackupRepositoryImplTest.kt | 327 ++++++++++++++++++++-
 .../LegacyDataConsistencyCheckerTest.kt            |   6 +
 .../domain/privacy/CompositePrivacyGateTest.kt     |  98 ++++++
 .../lifecycle/ReceiptLifecycleCoordinatorTest.kt   |   1 -
 ...NotificationCaptureServiceDeferredPolicyTest.kt | 286 ++++++++++++++++--
 .../NotificationCaptureServiceFallbackTest.kt      | 191 +++++++++---
 .../BackupRestoreViewModelPrivacyDenialTest.kt     |  10 +-
 .../ExportOptionsViewModelPrivacyDenialTest.kt     |   1 +
 .../wave1/CL-18-rereview-completion.md             | 178 +++++++++++
 .../wave1/CL-18-review-completion.md               | 221 ++++++++++++++
 .../generated/TRIAGE-2026-09-test-recovery.md      |  68 +++++
 15 files changed, 1443 insertions(+), 124 deletions(-)
```

### git diff --numstat ad412aec..HEAD
```text
27	13	app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt
3	0	app/src/main/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGate.kt
91	39	app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt
4	3	app/src/test/java/com/yourname/expensetracker/data/privacy/KeystoreInstallationSecretHashingTest.kt
324	3	app/src/test/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImplTest.kt
6	0	app/src/test/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyCheckerTest.kt
98	0	app/src/test/java/com/yourname/expensetracker/domain/privacy/CompositePrivacyGateTest.kt
0	1	app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt
265	21	app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceDeferredPolicyTest.kt
148	43	app/src/test/java/com/yourname/expensetracker/service/NotificationCaptureServiceFallbackTest.kt
9	1	app/src/test/java/com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt
1	0	app/src/test/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModelPrivacyDenialTest.kt
178	0	docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-rereview-completion.md
221	0	docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-18-review-completion.md
68	0	docs/testing/generated/TRIAGE-2026-09-test-recovery.md
```
