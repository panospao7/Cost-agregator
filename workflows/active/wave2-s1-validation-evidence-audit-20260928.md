# Wave-2 S1 validation-evidence audit — 2026-09-28

VERDICT: FAIL — the claim of complete S1 validation / clearance for the next implementation stage is not established. This is an evidence audit, not a finding that the S1 partial-OCR implementation failed, and not an independent strict/guardian approval.

## 1. Corrected result

- All eleven recorded runner invocations really ended PASS, exit 0, with completion markers and frozen, matching fingerprints.
- The preserved JUnit XML contains 123 test cases: **104 passed, 19 skipped, zero failures, zero errors**. It does not prove 123 tests executed.
- Every one of the 18 newly authored S1 test methods is present exactly once and passed without a skip. This includes all ten real-Room ReceiptPartialOcrPipelineTest methods.
- The nineteen skipped cases are the entire ReceiptScanViewModelStressTest class. The Gradle test task ran, but those test bodies did not.
- Independent strict/guardian review remains pending. Runtime results do not substitute for that gate. The S2 production-implementation precondition is therefore not satisfied.

## 2. Snapshot and preservation

- Branch: `bug-fixes`.
- HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`.
- Validated fingerprint, shared by all eleven runs: `6119a5cf599a61b5e392c499bcd442035a340aaa47a4893ceead95e16e0c0fdb`.
- Audit pre-write fingerprint: `41f5d8fe0522a968f376f2e721d1220de3feac0824c6da416cd13d359134bd42` (captured 2026-09-28T17:25:00.1522537+03:00).
- Audit pre-write dirty paths: 132 tracked and 82 untracked; staged paths: none.
- Comparing the saved validated manifest with the fresh audit inventory found exactly one changed existing path: `docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md`. No paths were added or removed. Every other manifest path retained its bytes/hash.
- All fourteen entries in the S1 handoff manifest match the current inventory by path, size and SHA-256. Source/test evidence is unchanged; the full dirty-tree fingerprint is not unchanged because the validator subsequently wrote the journal.
- Runner quiescence before this report: 2026-09-28T17:26:58.2435976+03:00; no lock, active runs or validation processes reported.
- This audit creates only this new report. It does not modify production code, tests, configuration, policy, baselines, allowlists, RawQuery pins, existing handoffs, the journal, validation artifacts or git history. Adding this report necessarily creates a new full-tree fingerprint; it does not retroactively alter the tested snapshot.

## 3. Run-by-run execution evidence

For each row, the durable evidence is `build/validation-runs/<run-id>/result.json`, `complete.marker` and `stdout.log`. All eleven results identify the HEAD and validated fingerprint above. The relevant compile/test task appears in stdout; targeted test tasks are not merely UP-TO-DATE.

| Full run ID | Profile / filter | Runner status | Passed test bodies | Skipped cases |
|---|---|---|---:|---:|
| `vr-20260928-134207-a22a69fa` | `compile` | PASS | — | — |
| `vr-20260928-134831-ae84a2be` | `*ReceiptPartialOcrPipelineTest` | PASS | 10 | 0 |
| `vr-20260928-135830-62cf74f0` | `*OcrResultPartialTest` | PASS | 4 | 0 |
| `vr-20260928-140001-b36c8fec` | `*ReceiptLifecycleCoordinatorTest` | PASS | 35 | 0 |
| `vr-20260928-140135-a7d5c64d` | `*ReceiptRepositoryBatchDuplicateTest` | PASS | 5 | 0 |
| `vr-20260928-140306-10d0290d` | `*ReceiptScanViewModelTest` | PASS | 17 | 0 |
| `vr-20260928-140431-3574c7fe` | `*ReceiptScanViewModelStressTest` | PASS | 0 | 19 |
| `vr-20260928-140548-0b71c722` | `*ReviewViewModelBatchDuplicateMessageTest` | PASS | 7 | 0 |
| `vr-20260928-140716-14a4aa86` | `*ReceiptSideEffectPlanner*` | PASS | 13 | 0 |
| `vr-20260928-140838-4c48ca89` | `*BankStatementCompletionStatusTest` | PASS | 5 | 0 |
| `vr-20260928-140955-98950852` | `*ReceiptOcrRetryIsolationTest` | PASS | 8 | 0 |
| Total | Ten targeted filters, twelve class XML files | 11/11 runner PASS including compile | **104** | **19** |

Underlying commands recorded by the runner:

```text
gradlew.bat :app:compileDebugKotlin --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon
gradlew.bat :app:testDebugUnitTest --tests <filter> --console=plain --no-parallel --max-workers=1 -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50 --no-daemon
```

These are evidence of previous human-run validation, not authorization to invoke Gradle directly. This audit ran no validation.

## 4. The missing stress coverage and provenance

- `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt:57` has the class-level annotation `@Ignore("Stress test: may hang in CI, run manually")`.
- Reading the same file with `git show HEAD:<path>` finds the identical annotation. Its presence predates S1; this is not evidence of a new S1 runtime regression.
- `build/validation-runs/vr-20260928-140431-3574c7fe/stdout.log:47` shows the actual test task. Lines 49–85 report all nineteen cases SKIPPED, followed by BUILD SUCCESSFUL at line 87.
- `build/validation-runs/s1-xml-evidence/6-ReceiptScanViewModelStressTest/TEST-com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanViewModelStressTest.xml` declares tests=19, skipped=19, failures=0 and errors=0. All nineteen testcase elements contain a skipped child.
- That XML SHA-256 is `7a59a8a2562b11ac0049a44f856821f756076c88d0547e653b49f3dd11b713b6`.
- The runner PASS is an authentic exit-zero result. It must not be rewritten as a fictional runner infrastructure error, but it is also not passing behavioral evidence for those nineteen test bodies.
- Author-side planning omission: the S1 handoff included this filter without identifying the pre-existing class-level Ignore. That omission belongs in the correction alongside the validator counting error; rerunning an unchanged ignored class will not close it.

### Existing record requiring an additive correction

`docs/analyses and debug master/campaign CA-2026-09-21/JOURNAL.md:115`, entry `WAVE2-S1-PDF-PARTIALITY-VERIFIED`, records “TOTAL 123 executed / 0 failed / 0 skipped.” That statement is contradicted by the preserved XML and stdout. This audit leaves the existing record intact and supplies the correction: **104 passed / 19 skipped / 0 failed / 0 errors; all 18 new S1 methods passed**.

The actual XML directories have numbered class-name suffixes (for example `6-ReceiptScanViewModelStressTest`), not directories literally named only `1..10`.

## 5. New-method evidence

Each method below was matched by name against the preserved XML, exactly once, with no skipped/failure/error child:

- `ReceiptPartialOcrPipelineTest.failedPageSurvivesRepositoryCoordinatorAndRoomWrites` — PASS.
- `ReceiptPartialOcrPipelineTest.failedPageIsPartialEvenWhenAnOlderProducerCountsAttemptedPages` — PASS.
- `ReceiptPartialOcrPipelineTest.cappedPdfIsPartialWithoutInventingFailedPages` — PASS.
- `ReceiptPartialOcrPipelineTest.completePdfRetainsOrdinarySuccessStatus` — PASS.
- `ReceiptPartialOcrPipelineTest.imageWithoutPageMetadataIsNotPartial` — PASS.
- `ReceiptPartialOcrPipelineTest.parseFailureRetainsItsStatusAndThePartialMetadata` — PASS.
- `ReceiptPartialOcrPipelineTest.cancellationLeavesNoReceiptOrSavedEvent` — PASS.
- `ReceiptPartialOcrPipelineTest.duplicateRetainsPersistedPartialityWithoutAnotherOcrOrSaveEvent` — PASS.
- `ReceiptPartialOcrPipelineTest.eventFailureRollsBackThePartialReceipt` — PASS.
- `ReceiptPartialOcrPipelineTest.batchUsesTheRealPipelineAndReportsPartialSavedRows` — PASS.
- `ReceiptScanViewModelTest.persistedPartialScanKeepsRecognizedDataAndShowsWarning` — PASS.
- `ReceiptScanViewModelTest.partialCoverageSurvivesParseFailureStatus` — PASS.
- `ReceiptScanViewModelTest.aCompleteNewScanClearsThePreviousPartialWarning` — PASS.
- `ReceiptRepositoryBatchDuplicateTest.partialCoverageIsASubsetOfNewSavesNotDuplicateOrFailureCounts` — PASS.
- `ReceiptRepositoryBatchDuplicateTest.anAlreadySavedPartialReceiptIsNotCountedAsANewPartialSave` — PASS.
- `ReviewViewModelBatchDuplicateMessageTest.partialBatchReportsIncompletePagesInsteadOfUnqualifiedSuccess` — PASS.
- `ReviewViewModelBatchDuplicateMessageTest.partialBatchStillReportsDuplicateAndFailureCounts` — PASS.
- `ReviewViewModelBatchDuplicateMessageTest.batchCancellationDoesNotBecomeAFailureMessage` — PASS.

This supports the authored S1 unit-level cases, not real-device PDF rendering, exhaustive correctness, or independent architecture/privacy review.

## 6. Dispositions and next work

| Item | Disposition | Required next action |
|---|---|---|
| S1 compile and 104 non-skipped unit cases | Execution-verified for the recorded snapshot | Preserve evidence; do not discard successful coverage. |
| Eighteen newly authored S1 methods | Execution-verified at unit level | Independent review must still evaluate assertions and production-path fidelity. |
| Nineteen stress cases | NOT EXECUTED; coverage gap | Diagnose the existing test fixture/hang risk; make a bounded, reviewed harness repair if needed, retaining meaningful assertions and all intended coverage, then obtain genuine serialized execution. Do not blindly remove Ignore, delete cases, drop the filter or count skips as PASS. |
| S1 independent strict/guardian review | PENDING | Obtain a genuinely independent review; the S1 author cannot approve its own implementation. No subagents were spawned. |
| S2 six asset-recovery gaps | NOT IMPLEMENTED | Resume the approved S2 slice only after the S1 gates are actually satisfied; use `workflows/active/wave2-s2-asset-recovery-readiness-20260928.md`. |
| S3 ownership rows/advisory drift | OPEN | Preserve exact ownership-policy scope and human authorization requirements; adjudicate the 20-versus-21 evidence gap rather than repinning blindly. |
| S4 outstanding CL-09 / worker evidence | OPEN | Execute the six missing filters and adjudicate WorkerRunLoggerTest evidence through the human runner after required review. |
| S5 full Wave-2 closure | OPEN | Complete finding/rider traceability, independent strict/guardian reviews, required device gates and final evidence reconciliation; commit only on explicit instruction. |

Acceptance references: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` — FG-03 (do not treat missing/skipped evidence as passing), FG-06/FG-07 (no unauthorized broadening or exceptions), FG-23 (no weakening a change's own checks). The distinction between a runner status and behavioral coverage is retained rather than relabeling either.

### Human validation after a reviewed stress-fixture repair

Do not run the following against the unchanged ignored class and expect coverage. First resolve the fixture/Ignore issue without relaxing assertions. Confirm global runner quiescence and a frozen snapshot. Use the repository runner only, PowerShell 5.1, no `-Raw`, and stop on failure:

```powershell
$ErrorActionPreference = 'Stop'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/vrun.ps1 -Worktree . -Profile compile -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Compile gate failed; stop." }
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter "*ReceiptScanViewModelStressTest" -MaxTotalMinutes 130
if ($LASTEXITCODE -ne 0) { throw "Stress validation failed; stop." }
```

Inspect the durable terminal result, start/end fingerprints, task execution and per-case XML. Require the intended nineteen cases to actually execute, not merely an exit-zero task. Preserve XML before the next test run overwrites it. If the repair changes a shared fixture or production behavior, review its consumers and rerun the affected S1 packet against the new snapshot; the old all-tree fingerprint must not be relabeled as current.

## 7. Verified S1 manifest

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

## 8. Preserved JUnit XML identities

Paths below are relative to `build/validation-runs/s1-xml-evidence/`.

| XML | Test cases | Skipped | SHA-256 |
|---|---:|---:|---|
| `1-ReceiptPartialOcrPipelineTest/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptPartialOcrPipelineTest.xml` | 10 | 0 | `a810683d8509205cdb4d04ffb253f6859acfa1e4828903779f5e8f731c43f80e` |
| `10-ReceiptOcrRetryIsolationTest/TEST-com.yourname.expensetracker.domain.receipt.ReceiptOcrRetryIsolationTest.xml` | 8 | 0 | `f3d37dd2a4a1057a7500a2defa67bd8dd9b653f0db07bc4863416383ab237a9d` |
| `2-OcrResultPartialTest/TEST-com.yourname.expensetracker.domain.receipt.OcrResultPartialTest.xml` | 4 | 0 | `32391fcea9de5720052275a2e652fe1005afde7be4e96c6568c21d22bc971214` |
| `3-ReceiptLifecycleCoordinatorTest/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinatorTest.xml` | 35 | 0 | `25942e4dbe3fd46ec867627c6724b50459cc16c070081c9a8466d953268854a6` |
| `4-ReceiptRepositoryBatchDuplicateTest/TEST-com.yourname.expensetracker.data.repository.ReceiptRepositoryBatchDuplicateTest.xml` | 5 | 0 | `1be7844c26915c6099dafd853271ec166aa23b574bdc1bb8bc81d04940cf21d2` |
| `5-ReceiptScanViewModelTest/TEST-com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanViewModelTest.xml` | 17 | 0 | `8db5a997d9c95668db1c019bf202f64971a38e99ab71263b03401093d6c1d4dd` |
| `6-ReceiptScanViewModelStressTest/TEST-com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanViewModelStressTest.xml` | 19 | 19 | `7a59a8a2562b11ac0049a44f856821f756076c88d0547e653b49f3dd11b713b6` |
| `7-ReviewViewModelBatchDuplicateMessageTest/TEST-com.yourname.expensetracker.ui.screens.review.ReviewViewModelBatchDuplicateMessageTest.xml` | 7 | 0 | `07c924774191fb43c7e8a88f01ad819a22fe96d0d8c60f88b6b990fab2c156e2` |
| `8-ReceiptSideEffectPlanner/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptSideEffectPlannerClaimTest.xml` | 4 | 0 | `52d638d68bec7d362e07bc572276487d82d0b8b33425188448116be302489b20` |
| `8-ReceiptSideEffectPlanner/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptSideEffectPlannerEphemeralItemsTest.xml` | 7 | 0 | `6a8a3621cb005aef0dbdaaab8932a477c63a7fc13929e6112d78faaf5af9f173` |
| `8-ReceiptSideEffectPlanner/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptSideEffectPlannerStructuredDataTest.xml` | 2 | 0 | `1a1826b2529401301ecb8476092b22f191609f87f9411244930ee3e9b6006364` |
| `9-BankStatementCompletionStatusTest/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.BankStatementCompletionStatusTest.xml` | 5 | 0 | `2c05a3c01afc337df27f91f8ffcfe5a3bfc9272ddecac1c79263e7710adedb2f` |

End state: S1 has substantial, genuine unit evidence, but its complete-validation claim is PARTIAL and Wave 2 remains open. No validation or production/test repair was performed by this audit.

