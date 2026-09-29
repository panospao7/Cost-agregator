# Wave-2 S1 — generic-PDF partiality repair handoff

Date: 2026-09-28. Batch status: **IMPLEMENTED_UNVERIFIED**. Wave-2 closure verdict: **FAIL — required work and approval/evidence gates remain open.**

This is the first bounded batch in `workflows/active/wave2-closure-series-20260928-plan.md`, not a declaration that the whole requested series is complete. One agent; no subagents. No validation, staging, commit, policy/baseline/allowlist edit or RawQuery repin was performed.

## 1. Authority and snapshot

- Original finding: CA-P-03-003, CL-15, including the camera/PDF route explicitly identified in `docs/analyses and debug master/campaign CA-2026-09-21/cell-P-03-audit.md:67-75`, especially :72. The narrower bank-statement-only author specification does not remove this route from the approved finding.
- Owning path: Segment 4 receipt/OCR lifecycle; interactive scan and batch review consumers; Segment 38 matching is an inspected downstream consumer, not a new mutation owner. `docs/architecture/LEGAL_PATHS.md:123-177` remains the routing authority.
- Intake: `bug-fixes`, HEAD `a48076322e57f3d312f34cf6a71139efc4fcc48b`; empty index; 124 tracked dirty and 77 untracked paths. Intake fingerprint `9df28a9a3f7dfd4fc2428ea7f8376773482b4f1a623d2a1349e73ec6d3db2498`.
- Source-inspected pre-handoff snapshot: `ab4bf6895d20b34de89945bb35ad2f2d7d2b59bfc1bbe1c2f782dba9d0acc392`, captured 2026-09-28T15:40:07.0259988+03:00; 132 tracked dirty and 80 untracked paths; same branch/HEAD and empty index.
- Of the 201 intake dirty/untracked paths, only the already-dirty ReceiptOcrService.kt, ReviewViewModel.kt and strings.xml changed in this batch, by localized additions/replacements. The other 198 remained byte-identical. Eight previously clean tracked files became modified; two Kotlin files and the series plan are new. No original path was removed. The table below is the exact 14-path pre-handoff batch, not the entire accumulated worktree.
- This new handoff itself changes the worktree fingerprint. Use the post-handoff fingerprint reported with this packet, or record a fresh runner fingerprint while verifying every listed SHA-256. Do not equate HEAD alone with the tested snapshot.

## 2. Behavior and source anchors

Paths in this section are relative to `app/src/main/java/com/yourname/expensetracker/` unless stated otherwise.

1. `domain/receipt/ReceiptOcrCoverage.kt:4-12` provides a typed page-count-only contract. Failed pages OR processed-less-than-total means partial; missing counts for an ordinary image do not. `ReceiptOcrService.kt:94-96` delegates the existing isPartial semantics to this value. The earlier successfully-recognized-page counting repair is preserved.
2. `data/repository/ReceiptRepository.kt:163-175`, :270-276 and :307-313 now carry failedPages as well as processed/total pages through both parse-success and parse-failure outcomes. New defaulted fields are appended rather than inserted into the existing positional constructor contract.
3. `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:253-263`, :463-468, :621-646 and :715-721 consume the coverage. Successful partial recognition persists `OCR_PARTIAL` in the existing string status column, while OCR_FAILED and PARSE_FAILED retain precedence. The existing RECEIPT_SAVED event receives only typed boolean/count fields and the controlled PDF_PARTIAL code, through the context-aware event writer inside the existing receipt transaction. Arbitrary legacy event metadata remains excluded. No new DAO writer, entity, schema, migration or DI path was introduced.
4. `ui/screens/receiptscan/ReceiptScanViewModel.kt:125`, :276, :304-322 and :442 carry/reset the warning flag. `ReceiptScanScreen.kt:1136-1160` renders it on the review screen, with `app/src/main/res/values/strings.xml:393`. Surviving recognized amounts remain available for manual verification rather than being erased.
5. `ReceiptRepository.kt:568-574` and :679-688 count partial results as a subset of newly saved receipts, not additional saves/failures. Already-existing duplicates are counted separately. `ui/screens/review/ReviewViewModel.kt:1020-1045` reports the partial count plus any duplicate/failure counts and preserves cancellation instead of manufacturing a batch-failure message.

Duplicates do not receive new save events or post-commit batches. A persisted OCR_PARTIAL row retains partiality when returned without fresh page counts. This batch does not backfill historical rows or reconstruct missing old page counts. PARSE_FAILED rows keep that failure status; new partial parse failures also retain their coverage in the saved-event metadata and current outcome.

## 3. Tests authored — NOT RUN

| Test file under app/src/test/java/com/yourname/expensetracker/ | New methods | Meaningful assertions |
|---|---:|---|
| domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt | 10 | Real repository -> coordinator -> Room transaction/receipt/event writer; failed and capped pages; legacy attempted-page producer; complete/image controls; parse-failure precedence; no raw text/path in partial event metadata; cancellation; duplicate replay; event-write rollback; real batch propagation. |
| ui/screens/receiptscan/ReceiptScanViewModelTest.kt | 3 | Recognized amount survives with warning; partial parse-failure outcome warns; next complete scan clears the old warning. |
| data/repository/ReceiptRepositoryBatchDuplicateTest.kt | 2 | Partial count is a subset of inserted saves; saves + duplicates + failures conserve input count; duplicate partial receipts are not counted as new partial saves. |
| ui/screens/review/ReviewViewModelBatchDuplicateMessageTest.kt | 3 | User-facing partial summary; simultaneous duplicate/failure counts; cancellation is not displayed as processing failure. |

Total: **18 newly authored methods**, not 18 executed/passing methods. Existing assertions were retained. The real-Room pipeline fixture controls OCR/parser/asset inputs and optional side effects; it does not pretend to execute real PDF rendering, file persistence or cloud recognition. It does not stub the repository result or the receipt transaction. Device rendering remains a separate gate.

## 4. Author-side inspection and approval limits

- Inspected the bounded diff, production call sites, constructor contracts, string-status consumers, event writer, cancellation and duplicate paths. The new rollback test verifies the real PostCommitActionRunner.run member, not a non-imported static extension. Its throwable assertion checks failure type rather than unstable exception-instance identity across coroutine/Room boundaries.
- ReceiptSideEffectPlanner keeps the existing OCR_FAILED/PARSE_FAILED/DUPLICATE_DETECTED exclusions. ReceiptMatchingWorker and CategorizeReceiptItemsUseCase retain their existing document/failure gates. No new enum-exhaustive consumer or ordinal persistence was identified in the inspected production references. This batch does not silently change matching, categorization or automatic side-effect eligibility.
- The partial diagnostic contains controlled scalars only. The pre-existing toLifecycleEvent privacy boundary is not broadened to pass arbitrary JSON, OCR text, paths or exception messages.
- Room/migration source assessment: no schema change; transaction/event ownership preserved. This is an author assessment, not guardian approval or runtime proof.
- Independent strict review: **NOT RUN**. Architecture/privacy/Room guardian approvals: **NOT RUN**. The single-author inspection cannot approve itself. The user prohibited subagents, and none were used.
- `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07 and FG-23 remain applicable. No skipped/unknown result is accepted as PASS; no guard, policy, baseline, allowlist or pin was changed to make checks green.

## 5. Serialized human validation packet

**Result: NOT RUN.** No compile, Gradle test, Python test, guard or other validation was launched by this agent. The earlier 11/11 recovery runs at fingerprint d736eca4... remain historical evidence for their own snapshot, not this S1 tree.

First obtain the required independent review of this batch without editing it during a run. Then verify the manifest, global runner quiescence and the exact full-tree fingerprint. Freeze all fingerprinted files, including review artifacts, throughout the sweep. Run the following from PowerShell 5.1; it uses only the repository validation runner through its human blocking wrapper, one run at a time. No -Raw and no direct Gradle/pytest invocation.

```powershell
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath 'C:\Users\panos\Desktop\cost agregator\ExpenseTracker'
$common = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
    'scripts/vrun.ps1', '-Worktree', '.', '-MaxTotalMinutes', '130')
& powershell.exe @common -Profile compile
if ($LASTEXITCODE -ne 0) { throw 'S1 compile did not PASS. Stop; inspect the existing run.' }
$filters = @(
    '*ReceiptPartialOcrPipelineTest',
    '*OcrResultPartialTest',
    '*ReceiptLifecycleCoordinatorTest',
    '*ReceiptRepositoryBatchDuplicateTest',
    '*ReceiptScanViewModelTest',
    '*ReceiptScanViewModelStressTest',
    '*ReviewViewModelBatchDuplicateMessageTest',
    '*ReceiptSideEffectPlanner*',
    '*BankStatementCompletionStatusTest',
    '*ReceiptOcrRetryIsolationTest'
)
foreach ($filter in $filters) {
    & powershell.exe @common -Profile targeted-unit-test -TestFilter $filter
    if ($LASTEXITCODE -ne 0) {
        throw ("S1 halted at " + $filter + ". Inspect/poll that run; do not rerun a live run.")
    }
}
```

This is compile plus ten serialized targeted filters. The planner wildcard deliberately includes the StructuredData, EphemeralItems and Claim classes; it does not narrow their existing selection. Each run must retain its run ID, result.json, command/filter, terminal status, exit code, completion marker and start/end fingerprint. Check actual test-task execution, not only a compile PASS. Preserve per-class XML/test counts before subsequent Gradle executions overwrite the working result directory; otherwise state the evidence limit. Canonical run logs are under build/validation-runs/<run-id>/. A timeout/unknown/RUNNING result halts this chain and requires polling the original run, not launching another.

The full static-guards closure rerun remains sequenced behind the separately gated ownership/advisory work. Nothing here converts the prior guard findings into passes.

## 6. Exact pre-handoff batch manifest

SHA-256 hashes are of current file bytes. The handoff itself is intentionally not self-hashed in this table. No generated validation output is part of this patch.

| Path | Bytes | SHA-256 |
|---|---:|---|
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrCoverage.kt` | 455 | `cebd4132aaac40e7e7db523e12a357554557ede32b46910d7ba44bce2f328720` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt` | 40361 | `43a2b99e0e9c6d5665f8b029e7dfb3833c5dff61b076e6979c19f4d9e9eefe3e` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptProcessingStatus.kt` | 365 | `636425d8f4b4c5352e122d38e48462a5dbfa4918082a73242d1beb222c18f3a0` |
| `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt` | 46646 | `2de2c92bded12f5abe9f364212a4cb8f060b24553e331a11f90bf6dce7545495` |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | 93566 | `b8a6ab51948cd101f440ef2441c6fde3c4c35c00ed92bf1daf0f69e9f306cdb4` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt` | 69270 | `4a9432aa37be205cd23742db571e87a685ce83e860a85fa71b41b06659ef50ba` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt` | 64971 | `a7f93f5a8298d752b238a52ffe6252f9d8239b0b1576f077eedc5831cc82e0c7` |
| `app/src/main/res/values/strings.xml` | 177020 | `21a88782b7dc1e205b052e53e51b1d23e29bbe915b9fb623e72598cd163b4cf7` |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt` | 61673 | `447364ee7999e4a379d3d9dfc765aa0f6470cb34f00b2ca132721ac36ced763d` |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt` | 17055 | `769390c0c39584e248d905dc6d7f46a17039a1f4d6c15f8e4d255fafd500bed1` |
| `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryBatchDuplicateTest.kt` | 10601 | `d88b60e0e3980cefcfa5b89eeda4f3f15cd33152bdc449fcf315d0f037b07a87` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModelTest.kt` | 17985 | `3b1b626843e211b332d84c59b1d2283f6f8eb1335049a4727f4fd0faea34799e` |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModelBatchDuplicateMessageTest.kt` | 8654 | `3f12db0fece67c31c1cc6c476255f458656225704b4aeb6d5b5709774c460836` |
| `workflows/active/wave2-closure-series-20260928-plan.md` | 4055 | `371b5bccba3f96021a4ff268fbc0134f7445f234001823789a6081158079295a` |

## 7. Remaining series — not silently completed

1. **S1 gate:** independent review, compile, the ten targeted filters and later device evidence. On any failure, diagnose preserved output and fix the contract, not the assertion merely to obtain green.
2. **S2, separate bounded batch:** full RP-03 asset identity/hash/size and durable crash-state/replay contract. Preserve pointer CAS, current-DB/internal ownership, collision rejection and restart requirements; handle fsync failures rather than ignoring them. Current PENDING/COMPLETED/FAILED plus a passing CAS sweep is not that full contract. Do not begin this larger state-machine patch on top of an unreviewed/unvalidated S1 batch.
3. **S3:** the four precise ownership reconciliations in `workflows/active/wave2-recovery-contract-repair-20260928-handoff.md` section 4, plus separate advisory 20-versus-21 identity/evidence adjudication. The recovery mutation contracts already have execution evidence; policy must describe their true owners and scopes. Do not broaden permissions or repin the advisory counter without the required evidence/decision.
4. **S4:** address the evidence inventory in `workflows/active/wave2-closure-progress-20260928-review.md`: six missing current-batch CL-09 filters (ExpenseRepositoryMerchantKeyBackfillTest, MerchantKeyBackfillWorkerTest, WarrantyReminderDeliveryDaoTest, WarrantyExpirationWorkerTest, ReceiptMatchingViewModelTest, ReceiptLinkServiceColumnScopeTest), the saved WorkerRunLoggerTest failure diagnosis, and fresh recursive Python/guard execution after the applicable repairs. A class name or environmental label is not passing evidence.
5. **S5:** independent strict/guardian reviews of the accumulated seven-cluster work, all original finding/rider dispositions, device PDF/image/backfill gates, and the final evidence matrix tied to the exact reviewed/tested tree. The full RP-03 remainder cannot be declared complete; any deliberate scope separation must be explicit and preserve its release blocker. Commit remains a separate human decision.

Next action: review and execute S1 using this exact manifest; then proceed to S2 under the same single-agent, small-batch, human-validation rules. Wave 2 remains open.

