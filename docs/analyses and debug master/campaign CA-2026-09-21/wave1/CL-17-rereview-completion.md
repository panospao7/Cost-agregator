# CL-17 Wave-1 re-review completion

**VERDICT: FAIL — lane is not mergeable.** Review date: **2026-09-25**. Source reviewed: `18d2551a7ee322a9be90040007496cb173e43aa9`, branch `rp-21-wip`. This closes the requested review, not the implementation.

**Score: 1/11 DONE-CORRECT; 9 PARTIAL; 1 DONE-DEFECTIVE. R-1 is fixed. One confirmed functional regression in the completion delta: R-2, loss of the genuine duplicate-transaction presentation. B-1 remains open.**

The completion standard is the first review's continuation/direct-fix plan, including its final completed-code-fingerprint execution requirement. There is real historical execution evidence; it must not be described as compilation alone or as tests that never ran. However, no saved run identifies the current merged source revision, and the historical dirty-tree fingerprint cannot be authenticated as the current completed worktree from the retained records. Accordingly, behavior WIs are not promoted to DONE-CORRECT on historical PASS labels alone. Several also have specific remaining acceptance gaps below.

## 1. Authority, environment, and scope

- Governing document read in full: `CL-17-review-completion.md`, including all direct-fix work items and WI-12. Original `CL-17-bounded-diagnostics-ui-error-leakage.md` is background; its reason-code table and fences remain binding.
- Verified working directory: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-21`.
- `git rev-parse --abbrev-ref HEAD` returned `rp-21-wip`. Initial `git status --porcelain=v1` was empty.
- Both `git merge-base --is-ancestor ead80016 HEAD` and `git merge-base --is-ancestor ead80016 7b8bc1cc` returned exit 0: the requested base is present, including the test-recovery prerequisite.
- `git log --oneline 7b8bc1cc..HEAD` contains exactly `18d2551a fix: bound CL-17 diagnostics and UI errors (WI-10b pending)`.
- `git diff --stat 7b8bc1cc..HEAD`: **27 files, 1,116 insertions, 119 deletions**: 11 production files and 16 focused test files. Full file diffs were read, with relevant surrounding code and consumers. No schema, DAO, migration, guard, baseline, lifecycle coordinator, build-script, or campaign-document change is in this completion delta.
- Architecture context: scoped segment/inventory entries and the privacy, backup/restore, worker, money, diagnostics, and budget legal paths; relevant engine/pipeline interactions. The source determined conclusions.
- No subagents were used. **No validation runner action, build, test, lint, or guard was executed by this reviewer.** The mechanical sweep is read-only source inspection requested by the user, not a static-guard run.
- Reviewer writes are restricted to this new artifact. The first review, JOURNAL, and all other campaign/source/test files are untouched.

Notation: `M/` = `app/src/main/java/com/yourname/expensetracker/`; `T/` = `app/src/test/java/com/yourname/expensetracker/`. Source/test line references below are at the source revision above, not the historical review's line numbers. `V01`–`V16` identify the exact remaining runner commands in section 7.

## 2. Per-WI verdicts at current HEAD

| WI | Current verdict | Evidence, carry-forward disposition, and remaining work |
|---|---|---|
| WI-1 | **PARTIAL** | Worker production behavior is unchanged in this delta; `M/service/reminder/BillReminderWorker.kt:126-174,266-273` retains controlled codes, cancellation, permission handling, and guarded execution. `T/service/reminder/BillReminderWorkerTest.kt:89-93,268-314` now checks null ShadowLog throwables for both diagnostic-writer sites and the outer coordinator failure. The generic notification-failure case at `:245-265` still has outcome assertions but no sink assertion; the permission fixture should explicitly assert its permission code, not only the diagnostic-writer code. Historical execution: 10 PASS method lines, V01 suite. Add those exact assertions and obtain final-fingerprint execution. |
| WI-2 | **PARTIAL** | Engine `M/domain/budget/BudgetForecastingEngine.kt:112-149,181-185,338-349` and ViewModel `M/ui/screens/budget/BudgetForecastingViewModel.kt:63-87,125-130` use fixed unavailable text and code/class logs; the UNIQUE versus other-constraint decision remains unchanged. The aggregate-unavailable fixture is added at `T/domain/budget/BudgetForecastingEngineDiagnosticsTest.kt:197-211`; suspended cancellation and late-old-request tests are added at `T/ui/screens/budget/BudgetForecastingViewModelTest.kt:296-336`. Historical suites actually executed, 5 and 10 PASS lines. Explicit log-sink assurance for the newly bounded insert catches/ViewModel generic failure remains absent from these focused suites. Complete that assurance and V02/V03 at the final fingerprint. |
| WI-3 | **PARTIAL — execution gate only** | No gratuitous synthesis production edit. `M/domain/logic/SynthesisEngine.kt:180-206` retains fallback and rethrows cancellation. `T/domain/logic/SynthesisEngineTest.kt:52-111` covers real synthesis fallback with log capture and now asserts exact cancellation identity/no fallback diagnostic. Historical V04 has 12 PASS lines. The source/test acceptance work appears complete; current completed-fingerprint execution is not established. |
| WI-4 | **PARTIAL** | The original durable free-text hole is closed at `M/data/backup/RestoreJournal.kt:82,132-134,633-637,773-784` and `M/data/backup/RestoreJournalImporter.kt:173`: new writes, direct serialization, legacy reads, and ledger import all use a finite code mapping. `T/data/backup/RestoreJournalDurabilityTest.kt:123-166` and `T/data/backup/RestoreJournalImporterFailureTest.kt:129-201,292-312` exercise hostile SQL/path/URI/financial text, known/absent values, idempotency, partial-event retry, and cancellation. Historical V05/V07 ran, 11/8 PASS lines. **The new allowlist additionally accepts `VALIDATION_FAILED` (`RestoreJournal.kt:775`; test `:126`), which is not in the binding six-code restore table.** Align it with the table or obtain an explicit contract amendment; do not silently redefine the spec. Then run V05/V07 at the final fingerprint. This mismatch is controlled text, not a surviving arbitrary-text leak. |
| WI-5 | **PARTIAL** | All 13 previously surviving throwable-log calls in the original changed-file set are removed; staging-path logging is removed at `RestoreJournal.kt:760`. Repository `:1276,2008,2647,2805,2817-2831` now maps typed read/write denial and worker-drain timeout at the actual catch, fixing the old outer-drain misclassification. Malformed-journal/staging fixtures (`T/data/backup/RestoreJournalDurabilityTest.kt:63-80`) and startup recovery fixture (`T/startup/AppStartupCoordinatorRecoveryTest.kt:659-671`) are added. V05/V06/V07 have historical 11/17/8 PASS lines. The required read/write/drain/parse/unknown mapping matrix and injected recovery/open/close log/cancellation cases are not all present. The mapper has no parser or established maintenance-denial branch; verify reachable cases rather than classify arbitrary IllegalStateException text. If repository-only cases cannot be reached through authorized fixtures, explicitly document that coverage boundary for owner acceptance as WI-5a permits, without broadening production architecture. Final V05/V06/V07 remains required. |
| WI-6 | **PARTIAL; R-1 fixed** | `M/data/ai/provider/CloudQueryInterpretationService.kt:162-190` now consults the existing IO retry predicate for SSL, preserves the attempt cap/backoff, and separates JSON from unknown errors. Provider gate logs are bounded; receipt text-assist has a JSON catch at `CloudReceiptAssistService.kt:392-398`. Historical V08/V09/V10 executed 7/8/12 methods, including all three R-1 SSL cases. The governing full matrix is still not complete: query standalone timeout/HTTP/IO retry boundaries, null/empty body cases, artifact-facing serialization/readable error output, and explicit no-terminal-log cancellation checks are not all asserted by the named suites. Complete the missing cases, then V08–V10 at the final fingerprint. No retry-policy/routing file was changed. |
| WI-7 | **PARTIAL — execution gate only** | `M/domain/currency/CurrencyConverter.kt:291` is now `MISSING_RATE stage=historical_conversion` without currency/date payloads. The real-converter fixture at `T/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt:117-167` forces distinct-currency lookups, exercises personal/shared/reimbursed branches, checks three failures and code-only warnings, and captures Timber/Android logs. Shared calculation/ownership/rounding behavior is unchanged. Historical V11 has 10 PASS lines and the shared V12 suite has 30. The old nested-log defect D-3 is closed in source; final-fingerprint V11/V12 is outstanding. |
| WI-8 | **PARTIAL — execution gate only** | Aggregate production logic at `M/domain/currency/CurrencyConverter.kt:485-517` is unchanged in the delta. `T/domain/currency/CurrencyConversionTest.kt:207-249` now covers supported-currency missing rates, stale-only and mixed failures, preserved typed failure details, exact count/code logs, and totals. The success fixture now stubs the actual latest-rate API with a controlled clock (`:144-164`), not different production money logic. Historical V12 has 30 PASS lines. Run V12 at the final fingerprint; no further money rewrite is justified. |
| WI-9 | **PARTIAL** | The production analytics catches/fallbacks remain unchanged: Totals `:443-449,673-703`; Insights `:297-326`. `T/domain/analytics/TotalsAggregationEngineTest.kt:64-104` now exercises all three failure catches and subsequent Flow emissions; `T/domain/analytics/InsightsEngineTest.kt:63-85` exercises seven degraded branches and exact safe logs. Historical V13/V14 executed 51/5 methods. Cancellation is injected only through the recurring Insights branch (`:131-157`); the other six branches and the three Totals catches lack the requested cancellation matrix. Complete those cases and execute V13/V14 at the final fingerprint. |
| WI-10 | **DONE-DEFECTIVE; B-1 open** | Both exact test classes now exist, and the ViewModels remove raw exception text and add cancellation rethrows. Receipt thrown-scan failures clear raw/debug text and accumulated logs (`M/ui/screens/receiptscan/ReceiptScanViewModel.kt:474-515`); OCR-failure raw fields are empty (`:329-373`). However `:1274-1281` replaces every atomic-save failure with generic Error, making genuine DuplicateTransaction presentation unreachable: **R-2**. The producer still erases DuplicateSkipped into ordinary IllegalStateException (`M/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:1590-1591`). There is no typed-owner fix or approved fence/acceptance amendment. The workaround stays in the allowed ViewModel file but does not satisfy B-1. All transaction operation failures currently use UNKNOWN_ERROR and lack the specified operation-code/class diagnostic mapping. Four tests in each suite are not the every-operation/OCR/parser/source-link/stale/thrown-and-wrapped-cancellation/typed-duplicate matrix. V15/V16 historical execution is real, but cannot close these defects. |
| WI-11 | **DONE-CORRECT** | `M/domain/usecase/expense/ExpenseUseCases.kt` remains deleted. Current three-symbol `git grep` across `HEAD -- app/src` returned no matches (exit 1); the exact command is below. No replacement facade, caller, binding, or lifecycle-guard change appears in the completion delta. Carry the first review's explicit compile/reference alternative; this WI does not require invented runtime tests. The final lane-wide compile is still listed below. |

“Execution gate only” acknowledges satisfactory local implementation and test-source work; it does not claim there was no historical execution. The merged prerequisite repair is available now. The old five-file compilation blocker is **not** a current reason to defer running the named suites.

WI-11 reference command (executed as read-only source inspection):

```text
git grep -n -E 'GetExpensesBetweenDatesUseCase|GetExpenseStatisticsUseCase|ReviewExpenseUseCase' HEAD -- app/src
```

## 3. Persisted execution evidence: inspected, never rerun

All following directories are under `build/validation-runs/`. Each listed run has terminal `PASS`, exit 0, `request.json`, `result.json`, stdout/stderr, and `complete.marker`; start/end fingerprints match. Counts are actual named `Class > method PASSED` lines read from stdout, not counts inferred from compile success. Together these 16 historical targeted runs show **204 passing method lines**.

| Queue / WIs | Historical run directory | Class | PASS method lines |
|---|---|---|---:|
| V01 / WI-1 | `vr-20260924-124853-ff26496a` | BillReminderWorkerTest | 10 |
| V02 / WI-2 | `vr-20260924-125033-e0147c49` | BudgetForecastingEngineDiagnosticsTest | 5 |
| V03 / WI-2 | `vr-20260924-125133-165df94d` | BudgetForecastingViewModelTest | 10 |
| V04 / WI-3 | `vr-20260924-125242-72c9e802` | SynthesisEngineTest | 12 |
| V05 / WI-4, WI-5 | `vr-20260924-125345-44088d0e` | RestoreJournalImporterFailureTest | 11 |
| V06 / WI-5 | `vr-20260924-125451-20782142` | AppStartupCoordinatorRecoveryTest | 17 |
| V07 / WI-4, WI-5 | `vr-20260924-125600-f4413c1f` | RestoreJournalDurabilityTest | 8 |
| V08 / WI-6 | `vr-20260924-125657-c25a5ba7` | CloudDashboardBriefingServiceTest | 7 |
| V09 / WI-6 | `vr-20260924-125753-af77501f` | CloudReceiptAssistServiceTest | 8 |
| V10 / WI-6, R-1 | `vr-20260924-125856-72f877ff` | CloudQueryInterpretationServiceTest | 12 |
| V11 / WI-7 | `vr-20260924-125956-9753746b` | SharedExpenseBudgetOffsetEngineTest | 10 |
| V12 / WI-7, WI-8 | `vr-20260924-130059-c340b592` | CurrencyConversionTest | 30 |
| V13 / WI-9 | `vr-20260924-130155-4931a346` | TotalsAggregationEngineTest | 51 |
| V14 / WI-9 | `vr-20260924-130258-fb70166c` | InsightsEngineTest | 5 |
| V15 / WI-10 | `vr-20260924-124606-ed40e767` | TransactionsViewModelTest | 4 |
| V16 / WI-10 | `vr-20260924-123527-1e20133b` | ReceiptScanViewModelTest | 4 |

Evidence attribution limits:

1. All these runs identify `git_revision=f46174e18152ce3660f83ff256aaab7a25e04924` (the pre-rebase first-review commit), with dirty fingerprint `3daf7acda415426c1685d42bf3a76d6a64f0ae6bf6f85f302e32a5d25594bc5b`. The same fingerprint appears in final historical compile `vr-20260924-130523-c563e15f` (PASS/0); that compile contributes **no execution evidence**.
2. Old completion commit `ad6c55e88871888cab83301f89db76b73d25741a` and current source HEAD have identical CL-17 production/focused-test blobs. Their six-file difference consists of five test-recovery files and `docs/testing/generated/TRIAGE-2026-09-test-recovery.md`. Thus the historical tests are relevant evidence, not discarded as unrelated.
3. Nevertheless, the retained run records do not enumerate the dirty snapshot's file contents. Reconstructing the 27 completion paths from current checkout bytes gives fingerprint `11b263455d610dfe181580a546acc8532cf52ec1ff863eec1b9a7bcd2c1b650d`, not the saved fingerprint; LF normalization also does not match. This does **not** prove a production mismatch or invalidate historical test execution, but prevents certifying complete snapshot equivalence. No `result.json` in this worktree identifies source HEAD `18d2551a...`.
4. The first review's WI-12 requires terminal named-test PASS at the completed code fingerprint, with counts/logs and final compile. This acceptance gate remains outstanding after the merged-base change. Do not turn historical dirty-tree PASS, cached compilation, or rp-25's prerequisite repair into a claim of final CL-17 validation.
5. Earlier exploratory failures remain historical records, not newly attributed regressions. The latest successful historical runs above supersede them only for that historical snapshot. No separate current privacy-guardian PASS is established by these runner files or the completion commit.

## 4. Mechanical gate and reason-code audit

The governing first review clarified that the shorthand “16 files” means 16 original changed production paths, including the deleted use-case file, plus two WI-10 ViewModels. This review checked **all 18 paths (17 extant)**. Read-only searches covered nullable/non-null `.message`, message/stack tokens, multiline/tagged Timber throwable overloads, Android Log throwable arguments, and non-literal Timber first arguments. Initial regex attempts that errored were discarded; only the successful corrected sweep and source inspection support these counts.

| Set | Actual exception-message reads | Throwable-to-log pass-throughs | Unjustified survivors |
|---|---:|---:|---:|
| Original 16 paths, 15 extant | 1 | 0 | 0 |
| Two WI-10 ViewModels | 0 | 0 | 0 |
| Entire specified path set | **1** | **0** | **0** |

**Gate disposition: satisfied with one explicit, already-approved local-classification exception; not literally zero raw-message reads.**

- The one actual survivor is `M/domain/budget/BudgetForecastingEngine.kt:344`: `SQLiteConstraintException.message` determines UNIQUE versus other constraints. The governing review explicitly instructed retaining it. It does not feed log/UI/journal output; both adjacent log calls now use the permitted `UNKNOWN_ERROR` and class only (`:345,348`). Removing it would conflate foreign-key failures with duplicates.
- The broad search yields five text matches, not five exception leaks: the survivor above; a comment in `RestoreJournalImporter.kt:33`; typed `AnalyticsConversionWarning.message` at `BudgetForecastingEngine.kt:456`; the explicitly permitted typed return-list `outcome.message` at `CurrencyConverter.kt:497`; and `OwnershipValidator.ValidationResult.Invalid.message` at `TransactionsViewModel.kt:635`. The latter producer (`M/ui/util/OwnershipValidator.kt:28-54`) supplies fixed validation strings, not throwable text. The analytics-warning contract is the first review's adjacent, pre-existing concern, not a newly proved universal-safety guarantee.
- Non-literal Timber arguments at repository `:1503,1523` are locally built filename-bearing strings, **not throwables**. They are unchanged from the delta base. Record them as pre-existing asset-diagnostic scope caveats, not additional delta regressions or permission for an asset/state-machine rewrite. Likewise the receipt success-path amount log (`ReceiptScanViewModel.kt:1208`) and converter diagnostics outside the targeted aggregate/historical failure path are not newly introduced by this delta. The mechanical gate is not a blanket claim that every pre-existing log is payload-free.
- Reminder, budget, synthesis/analytics, shared FX, and provider changed failure codes are controlled and within their tables. Restore mapping remains incomplete as described under WI-5; the new journal acceptance of `VALIDATION_FAILED` is outside the specified restore set. Transaction/receipt generic text is bounded, but boundedness alone does not satisfy operation-specific mapping or typed duplicate behavior.

## 5. Delta-only regression findings

### R-1 — FIXED: eligible query SSL failures consult the retry predicate

At `M/data/ai/provider/CloudQueryInterpretationService.kt:162-166`, SSL now returns only when the unchanged `CloudRetryPolicy.isRetryableIoException(e)` rejects it or the attempt limit is exhausted. Retryable SSL reaches the same common backoff at `:187-190`. The policy file is unchanged; it accepts timeout/reset/aborted IO and nested SocketTimeoutException (`M/data/ai/provider/internal/CloudRetryPolicy.kt:18-32`).

The focused tests cover ordinary terminal SSL, nested-timeout SSL succeeding on attempt 2, and reset SSL exhausting 3 attempts (`T/data/ai/provider/CloudQueryInterpretationServiceTest.kt:289-368`). Their actual historical PASS lines are `vr-20260924-125856-72f877ff/stdout.log:63,71,79`; `:83` records successful completion. This is affirmative source and historical execution evidence that R-1 is fixed, while the final-fingerprint/provider-matrix gate remains open.

### R-2 — MAJOR / introduced: genuine atomic-save duplicates now become generic errors

- **Changed site:** `M/ui/screens/receiptscan/ReceiptScanViewModel.kt:1274-1281`. The delta removes the old DuplicateTransaction assignment and always assigns `SaveReceiptResult.Error`.
- **Real producer:** the unchanged coordinator still turns `CreateExpenseResult.DuplicateSkipped` into `Result.failure(IllegalStateException("Duplicate transaction detected"))` at `M/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:1590-1591`. No typed status reaches this consumer.
- **Behavior difference from 7b8bc1cc:** that genuine producer failure previously selected DuplicateTransaction; it now selects generic Error. The dedicated UI card at `M/ui/screens/receiptscan/ReceiptScanScreen.kt:1164-1176` is no longer selected by this atomic-save path. The database duplicate protection itself is not removed.
- **Why the added test is insufficient:** `T/ui/screens/receiptscan/ReceiptScanViewModelTest.kt:81-90` proves that misleading duplicate text does not cause a false positive, but tests neither the real positive producer case nor an available typed signal. It cannot justify losing correct duplicate presentation.
- **Minimum legal resolution:** obtain the separately reviewed owner-supplied typed duplicate contract, or an explicit human amendment to the fence/acceptance criterion; then map it locally and test true duplicate, ordinary IllegalStateException, misleading text, and cancellation. Do not restore substring matching, classify all IllegalStateException as duplicates, add a facade nobody calls, insert a preflight duplicate query, or split atomic create/link.

**Confirmed functional regression count in `7b8bc1cc..18d2551a`: 1 (R-2).** R-1 is resolved and is not counted as a surviving regression. Incomplete matrices, final execution attribution, and code-table conformance gaps are separately reported rather than inflated into additional proven runtime regressions.

No other introduced money arithmetic, worker-guard bypass, cloud routing, cancellation swallowing, journal IO/durability, or lifecycle mutation change was established in the delta. The changed weekly-test expectations (`T/domain/analytics/TotalsAggregationEngineTest.kt:386-400`) match unchanged production's one-row-per-returned-week and ordinal `W${index + 1}` behavior (`M/domain/analytics/TotalsAggregationEngine.kt:148-179`); this is not evidence of a new analytics runtime regression. Exact assertions remain, rather than being deleted or made permissive.

## 6. Cross-lane flags — merge-order interactions only

Read-only sibling tips inspected: rp-20 `577c9e56d344b65527def875d149aca53f34c036`; rp-22 `2e8bf205b4f227cbebb8d13e3d903e58124ce5ed`. This is not a completion review of either lane.

- Preserve human merge order **rp-20 -> rp-21 -> rp-22**. In shared `DatabaseBackupRepositoryImpl.kt`, rp-20's bounded `blocksExecution()` export preflight is outside the maintenance cleanup scope and has an injectable recorder fixture. Do not replace that hunk wholesale with rp-21's base-derived file or duplicate/remove imports/fixture parameters while resolving diagnostics changes.
- rp-22's JournalDurabilityException early returns and reset safety/state transitions overlap repository catches/reset code touched by CL-17. Typed durability handling must retain precedence over CL-17's generic classification; preserve code-only summaries, cancellation rethrows, and bounded close/rollback logs. Do not let an UNKNOWN_ERROR mapping turn uncertain durability into exit-to-NORMAL.
- The same composition issue applies to `RestoreJournal.kt` serialization/failJournal and `AppStartupCoordinator.kt` recovery. Keep rp-22's durability/recovery semantics alongside CL-17's bounded error representation. No state-machine fix or approval is supplied by this artifact. Recheck the bounded-log sweep and relevant focused tests after human integration.

## 7. Exact remaining path to mergeability

1. Resolve **B-1/R-2** through the owner/human decision above. No in-fence workaround at HEAD restores the required true-duplicate behavior. WI-10 cannot be closed by running its current four-test fixture.
2. Finish only the specific in-fence assurance/mapping gaps in section 2: WI-1 notification sink/code checks; WI-2 log-sink checks; WI-4 restore-code-table alignment; WI-5 typed mapping and recovery/open/close coverage (or its explicitly permitted coverage-boundary decision); WI-6 full provider/error-consumer matrix; WI-9 all cancellation branches; WI-10 operation mapping and the full focused failure matrix. Preserve WI-3/WI-7/WI-8 production behavior and WI-11's deletion. Do not expand into unrelated cleanup, schemas, guards, privacy routing, or lifecycle/state-machine ownership.
3. Obtain strict and privacy acceptance after the corrections. This re-review is FAIL, not a substitute PASS or guardian approval. This reviewer did not delegate or run validation.
4. Have the sole validation owner execute the following exact profiles **serially**, at the completed merged source fingerprint. The test-recovery prerequisite is already in the base; do not reapply its five test repairs, exclude tests, weaken assertions, or alter the runner.

### Runner preflight and polling (NOT executed here)

```powershell
$env:ANDROID_HOME = 'C:\Users\panos\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action List
# After EACH Start below, use its returned run id and wait for terminal status.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId '<returned-run-id>'
```

Repeat Wait for RUNNING; never start a duplicate or overlap another validation process. These commands are a queue, **not an unattended block to launch in parallel**. Ask before expensive execution as the repository requires.

### Targeted commands, with WI ownership

```powershell
# V01 — WI-1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*BillReminderWorkerTest'
# V02 — WI-2
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*BudgetForecastingEngineDiagnosticsTest'
# V03 — WI-2
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*BudgetForecastingViewModelTest'
# V04 — WI-3
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*SynthesisEngineTest'
# V05 — WI-4 and WI-5
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*RestoreJournalImporterFailureTest'
# V06 — WI-5
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*AppStartupCoordinatorRecoveryTest'
# V07 — WI-4 and WI-5
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*RestoreJournalDurabilityTest'
# V08 — WI-6
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*CloudDashboardBriefingServiceTest'
# V09 — WI-6
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*CloudReceiptAssistServiceTest'
# V10 — WI-6 / R-1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*CloudQueryInterpretationServiceTest'
# V11 — WI-7
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*SharedExpenseBudgetOffsetEngineTest'
# V12 — WI-7 and WI-8 (one shared run)
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*CurrencyConversionTest'
# V13 — WI-9
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*TotalsAggregationEngineTest'
# V14 — WI-9
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*InsightsEngineTest'
# V15 — WI-10
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*TransactionsViewModelTest'
# V16 — WI-10, after the approved typed-duplicate dependency and complete fixtures
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*ReceiptScanViewModelTest'
```

After all required targeted runs and review acceptance, run the final compile (including WI-11's compile/reference alternative):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
```

Record command, source revision, start/end fingerprint, terminal result/exit code, run directory, stdout/stderr, completion marker, and named test counts/XML. Missing, stale, timed-out, unknown, or infrastructure-error results are not PASS. A compile PASS is never runtime evidence. Recheck the mechanical sweep and shared-file composition at the final source version; do not modify baselines/allowlists or convert pending review gates into green.

## 8. Delivery

Files touched by this reviewer: **only `docs/analyses and debug master/campaign CA-2026-09-21/wave1/CL-17-rereview-completion.md`**. Source and tests remain read-only. Validation by this reviewer: **NOT RUN**, by explicit instruction. Historical results are separately credited above. First-review artifact and JOURNAL remain untouched. This artifact is intended for the requested docs-only lane commit; its commit SHA and the requested journal handoff line are returned to the caller, not appended to JOURNAL.
