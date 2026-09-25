# CL-17 Wave-1 completion review and direct-fix plan

**VERDICT: FAIL — completion not approved.** Reviewed on **2026-09-24**, not September 23, against source HEAD `d27483124f4e9a3989496d2a51fde167234c12c8` on `rp-21-wip`.

**Score: 1/11 DONE-CORRECT; 1 DONE-DEFECTIVE; 8 PARTIAL; 1 NOT-STARTED. One confirmed introduced regression.** Remaining privacy defects are distinguished below from newly introduced regressions. No source edits, subagents, builds, tests, lint, or guards were run by this reviewer. This file is the sole new artifact; JOURNAL and campaign status/spec files are untouched.

## 1. Environment, authority, and inventory

- Verified working directory and Git top level: `C:\Users\panos\Desktop\cost agregator\ExpenseTracker\build\worktrees\rp-21`.
- `git rev-parse --abbrev-ref HEAD`: `rp-21-wip`.
- `git merge-base ad412aec HEAD`: `ad412aecb1347daebee7fef62aca745cf205f0aa` (the requested base).
- Initial `git status --short` and later `git status --porcelain=v1 --untracked-files=all`: empty. **No uncommitted/untracked paths**, and no stray CL-17 coder report to include in the commit. No CL-17 handoff/implementation report was located in the worktree; the commits and durable runner records are the available implementation evidence.
- Read `wave1/CL-17-bounded-diagnostics-ui-error-leakage.md` in full. The unchanged GATE paragraph is superseded by `stage3-wave1-coder-prompts.md:25` and the independent findings in `verification-2026-09-22-wave1.md:46-52,106-140`. This is not a claim that implementation review has passed.
- Architecture context inspected: scoped portions of `docs/architecture/CODEBASE_SEGMENTS.md`, `CODEBASE_INVENTORY.md`, `LEGAL_PATHS.md` (privacy, backup/restore, workers, money, diagnostics), and `ENGINE_INTERACTION_MAP.md`. Source, not map method lists, determined findings.
- `git diff --stat ad412aec..HEAD`: **25 files, 398 insertions, 236 deletions**. All 25 full file diffs were read, followed by relevant callers, return models, tests, and surrounding code. All changed production/test files fall within the CL-17 file fence. No schema, DAO, guard configuration, privacy-routing, money-calculation, or lifecycle-mutation changes were made by the lane.

`git log --oneline ad412aec..HEAD` (newest first):

```text
d2748312 CL-17 WI-9 Bound analytics failure logs
93560a25 CL-17 WI-8 Aggregate conversion failure logs
71ade640 CL-17 WI-7 Remove shared FX payload logs
4ba2291f CL-17 WI-6 Bound cloud provider failure fields
928812da CL-17 WI-4 partial and WI-5 Bound reset restore diagnostics
d6c8aeab CL-17 WI-3 Remove synthesis throwable logging
160fba27 CL-17 WI-2 Bound budget forecast failure UI
3b18c12b CL-17 WI-1 Bound reminder worker failure logs
1539b5d0 Remove unused unsafe expense use cases (CL-17 WI-11)
```

### Evidence path notation and full diff inventory

Below, `M/` means `app/src/main/java/com/yourname/expensetracker/`; `T/` means `app/src/test/java/com/yourname/expensetracker/`. Unless explicitly marked base or sibling, every `file:line` refers to the reviewed source HEAD above. Line numbers will move after completion edits.

Production files read in full diff:

```text
M/data/ai/provider/CloudDashboardBriefingService.kt
M/data/ai/provider/CloudQueryInterpretationService.kt
M/data/ai/provider/CloudReceiptAssistService.kt
M/data/backup/RestoreJournal.kt
M/data/backup/RestoreJournalImporter.kt
M/data/repository/DatabaseBackupRepositoryImpl.kt
M/domain/analytics/InsightsEngine.kt
M/domain/analytics/TotalsAggregationEngine.kt
M/domain/budget/BudgetForecastingEngine.kt
M/domain/currency/CurrencyConverter.kt
M/domain/groups/SharedExpenseBudgetOffsetEngine.kt
M/domain/logic/SynthesisEngine.kt
M/domain/usecase/expense/ExpenseUseCases.kt [deleted]
M/service/reminder/BillReminderWorker.kt
M/startup/AppStartupCoordinator.kt
M/ui/screens/budget/BudgetForecastingViewModel.kt
```

Test files read in full diff:

```text
T/data/ai/provider/CloudDashboardBriefingServiceTest.kt
T/data/ai/provider/CloudQueryInterpretationServiceTest.kt
T/data/ai/provider/CloudReceiptAssistServiceTest.kt
T/domain/budget/BudgetForecastingEngineDiagnosticsTest.kt
T/domain/currency/CurrencyConversionTest.kt
T/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt
T/domain/logic/SynthesisEngineTest.kt
T/service/reminder/BillReminderWorkerTest.kt
T/ui/screens/budget/BudgetForecastingViewModelTest.kt
```

The two WI-10 ViewModels are **not in the diff** and were separately read. The spec enumerates 18 production paths, not merely the shorthand “16-file sweep”: 16 changed paths (including one deletion), plus two unchanged WI-10 paths.

## 2. Per-work-item completion verdicts

`PARTIAL` includes implemented production hunks whose required test matrix or execution evidence is incomplete. It does not imply a demonstrated functional defect in every such hunk. `DONE-CORRECT` is not being awarded on compilation alone to behavior changes. WI-11 explicitly permits compile/reference evidence for its deletion.

| WI | Verdict | Diff/source evidence and remaining work |
|---|---|---|
| WI-1 | **PARTIAL** | `M/service/reminder/BillReminderWorker.kt:126-174,266-273` replaces throwable logs with the specified constants; cancellation rethrows, `permission_denied`, guard invocation `:47-56`, and `guardResult.toWorkerResult()` `:179` are preserved. `T/service/reminder/BillReminderWorkerTest.kt:262-286` adds diagnostic-writer failure and cancellation. Existing permission/generic notification tests remain `:191-258`. Missing full log-sink assertions for permission, notification and outer coordinator exceptions; new ShadowLog assertion checks message text but not `throwable`. No test executed. |
| WI-2 | **PARTIAL** | Engine `M/domain/budget/BudgetForecastingEngine.kt:112-149` removes upstream details; ViewModel `M/ui/screens/budget/BudgetForecastingViewModel.kt:79-87,125-130` maps codes and unexpected errors to fixed text. Existing engine unavailable aggregate text `:181-185` is fixed. Tests `T/domain/budget/BudgetForecastingEngineDiagnosticsTest.kt:162-194` and `T/ui/screens/budget/BudgetForecastingViewModelTest.kt:257-292` check bounded text. Engine aggregate-unavailable, ViewModel cancellation/stale-request cases and sink checks are missing. Two multiline throwable logs survive in the engine `:345-359`. |
| WI-3 | **PARTIAL** | `M/domain/logic/SynthesisEngine.kt:180-206` preserves fallback and cancellation while replacing the sole fallback throwable log. `T/domain/logic/SynthesisEngineTest.kt:52-97` adds real conversion-failure fallback and cancellation cases, captures Timber, and asserts null throwable. Frozen time `:48` places planned expenses inside the forecast. No implementation defect found in this hunk, but the required tests have never executed. |
| WI-4 | **PARTIAL** | Reset catch `M/data/repository/DatabaseBackupRepositoryImpl.kt:2753-2765,2802-2828` removes raw error detail from reset journal/event/finalization calls. **Boundary work is missing:** `M/data/backup/RestoreJournal.kt:632-636` still stores arbitrary `errorMessage`; importer `M/data/backup/RestoreJournalImporter.kt:173-190` still copies `entry.error` verbatim. The importer diff changes logs only. Existing test `T/data/backup/RestoreJournalImporterFailureTest.kt:95-111` still asserts verbatim free text. No SQL/path/URI/financial payload tests were added. |
| WI-5 | **PARTIAL** | Changed catches in `RestoreJournal.kt:491-526,615-655`, repository `:992,1096,1215,1276,1628,2008,2162,2647,2717,2820-2826`, and startup `:221-255,735-746` now avoid throwable overloads. Four restore close/rollback calls still pass throwables (`repository:2169,2171,2584,2586`); other survivors are enumerated below. Typed read/write/drain failures often get generic `UNKNOWN_ERROR` instead of the table's specific code. No required journal/startup diagnostic coverage was added. |
| WI-6 | **DONE-DEFECTIVE** | Main error models/logs are bounded at dashboard `:229-271`, receipt `:212-253,377-403`, and query `:156-178`. But the new query SSL catch `:161-163` bypasses the existing retry predicate: **R-1**. The timeout/SSL/IO/JSON/generic/cancellation/persistence matrix is not covered by the small assertion additions and one new SSL test (`T/.../CloudQueryInterpretationServiceTest.kt:287-300`). |
| WI-7 | **PARTIAL** | Shared-engine payload messages and Android Log calls are deleted at `M/domain/groups/SharedExpenseBudgetOffsetEngine.kt:77-79,126-128,157-159,173-175`. Counts and partial/totals logic remain `:181-218`; new test `T/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt:73-106` asserts three failed branches and code-only warnings. However, its mocked converter masks the real `convertAsOf` log at `M/domain/currency/CurrencyConverter.kt:291`, which still prints currencies and the expense timestamp on this exact path. No tests executed. |
| WI-8 | **PARTIAL** | `M/domain/currency/CurrencyConverter.kt:485-517` preserves totals/failure entries and replaces per-item payload logging with a single count/code event. `T/domain/currency/CurrencyConversionTest.kt:180-202` checks mixed success/failure, preserved original amount, exact log and null throwable. No defect found in the changed aggregate hunk; execution evidence is absent. Complete stale/mixed-code boundary assurance in the same class rather than changing conversion math. |
| WI-9 | **PARTIAL** | All three Totals catches `M/domain/analytics/TotalsAggregationEngine.kt:443-449,673-679,697-703` and seven Insights branches `M/domain/analytics/InsightsEngine.kt:297-326` replace throwables with `UNKNOWN_ERROR`, fixed stage and class. Fallback/Flow/cancellation logic is unchanged. **No analytics test diff exists**. Existing average fallback test `T/domain/analytics/TotalsAggregationEngineTest.kt:272` and top-level Insights cancellation `T/domain/analytics/InsightsEngineTest.kt:95-118` are not the required branch/Flow/log-sink matrix. |
| WI-10 | **NOT-STARTED** | Both ViewModels and their spec-named focused test classes have no diff; those exact focused classes do not yet exist (only stress classes exist). `M/ui/screens/transactions/TransactionsViewModel.kt:461,477,499,522,560,587,657,705,717` still surfaces exception text. `M/ui/screens/receiptscan/ReceiptScanViewModel.kt:477,495,505-510,1269-1284` still retains raw failure text and matches duplicates by message. A **real fence blocker** exists for the required typed duplicate signal; see B-1. |
| WI-11 | **DONE-CORRECT** | Full deletion hunk removes all 105 lines of `M/domain/usecase/expense/ExpenseUseCases.kt` (base declarations `:25,44,89`); no replacement API. `git grep -n -E 'GetExpensesBetweenDatesUseCase|GetExpenseStatisticsUseCase|ReviewExpenseUseCase' HEAD -- app/src` finds no references, covering production, bindings and tests. Saved HEAD compile PASS supports the spec's compile/reference alternative, not runtime-test execution. No lifecycle guard changes. |

## 3. Regression hunt and unfinished defects

### R-1 — MAJOR / introduced: query SSL errors lose eligible retries

- **New line:** `M/data/ai/provider/CloudQueryInterpretationService.kt:161-163` catches every `SSLException` and returns immediately.
- **Base behavior:** at `ad412aec`, query `:154-165` handled it as an `IOException` and consulted `CloudRetryPolicy.isRetryableIoException`. The unchanged policy `M/data/ai/provider/internal/CloudRetryPolicy.kt:18-32` returns true for timeout/connection-reset/connection-aborted text or a nested `SocketTimeoutException`.
- Thus an SSL transport failure with a retryable cause that formerly got up to three attempts now gets one, contrary to WI-6's explicit unchanged retry-count requirement. Ordinary nonretryable SSL remains one attempt; not every SSL error is affected.
- Added test `T/data/ai/provider/CloudQueryInterpretationServiceTest.kt:287-300` uses an ordinary SSL exception and checks only the result, so it does not detect the regression.
- Direct fix: WI-6a. No need to edit the shared retry policy or cloud routing.

**Confirmed introduced regression count: 1.** The intermediate missing `expenseId` test error in run `vr-20260923-113708-ac98f0f1/stderr.log:7` is repaired in current `SharedExpenseBudgetOffsetEngineTest.kt:90-91` and absent from the final HEAD stderr; do not count it as a surviving regression. Early missing repository imports are also repaired. No assertions were deleted to force green; changing raw-error expectations to exact safe text is appropriate, not test weakening. No new cancellation swallowing or WorkerExecutionGuard bypass was found in changed hunks.

### D-1 — CRITICAL / unfinished WI-4: arbitrary journal text still reaches durable ledger

The updated reset caller alone cannot protect historical failure journals or other callers. `RestoreJournal.failJournal:632-636` accepts arbitrary String, `JournalEntry.toJson:81` persists it, `fromJson:131-132` reads it, and importer `:173,190` forwards it. A legacy `error` containing SQL, a URI/path or merchant/amount text still becomes `OperationRun.errorSummary`. This is the original unfixed boundary, **not a new leak introduced by the diff**. Replacing importer catch logs does not fix it. Prefer a finite controlled-code boundary and code-only legacy import, not partial regular-expression redaction.

### D-2 — MAJOR / unfinished WI-5 and mechanical gate: surviving throwable/path logs

The R2 sweep is not zero. Detailed inventory is in section 4. In particular, live DB close and rollback close still send throwables to Timber, and `RestoreJournal.cleanStagingFiles:758` still prints the staging path. Removing only top-level catch logs does not satisfy the restore/recovery guarantee.

### D-3 — MAJOR / unfinished WI-7: converter still logs on the real shared-FX failure path

The shared engine calls the real `CurrencyConverter.convertAsOf` at `:117-121,149-153,165-169`. Its null-rate path logs source/target currency and `atMillis` at converter `:291`. The new shared test stubs `convertAsOf` to null, so it proves warning content, not absence of the nested log. This is a pre-existing downstream leak on the specified path, not an arithmetic regression. Delete/bound that log in the already-allowed converter file without changing rates, rounding or return values.

### D-4 — MODERATE / incomplete reason-code mapping

The reset TIMEOUT mapping is placed in the inner destructive catch `repository:2805-2812`, but worker drain happens in the outer try at `:2745-2747`; its `WorkerDrainTimeoutException` reaches `:2825-2826` and is labeled `UNKNOWN_ERROR`. `TimeoutCancellationException` is already rethrown at `:2803`, so its later mapping is unreachable (correctly do **not** persist cancellation to make that branch reachable). Likewise stats and outer restore/import catch logs hardcode `UNKNOWN_ERROR` even for typed access denial. Select from the spec table using exception type/access type at the actual catch; do not change barriers or state transitions.

### D-5 — MAJOR / assurance gap: no named behavioral tests ran

Production compilation is supported; behavior, absence of logged throwable objects, cancellation and durable safe summaries are not demonstrated. Many enumerated cases are missing entirely. See sections 5 and 7. No privacy-security-guardian or strict implementation-review PASS artifact was found. This completion review is FAIL, not a substitute PASS.

### B-1 — blocking architectural mismatch: WI-10 typed duplicate cannot be completed within the current fence

`M/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:1552-1554` returns `Result<Long>`. At `:1590-1591` it destroys `CreateExpenseResult.DuplicateSkipped`'s type and returns `IllegalStateException("Duplicate transaction detected")`; other failures also return `IllegalStateException` (`:1583-1586,1594-1595`). No typed duplicate reaches `ReceiptScanViewModel` at `:1241-1243`.

The spec simultaneously requires typed duplicate handling and forbids changes to receipt/transaction lifecycle mutation paths; the coordinator is not an allowed CL-17 production file. **Do not invent a ViewModel-only exception nobody throws, classify every IllegalStateException as duplicate, compare raw message substrings, add a racy duplicate precheck, or bypass the atomic create/link path.** A human architecture decision and separately approved owner change must expose a typed signal. WI-10 can be partially sanitized in-fence, but its duplicate acceptance criterion remains blocked until that dependency exists.

## 4. R2 mechanical sweep and reason-code audit

Read-only search used `Select-String` plus multiline `[regex]::Matches` over the 16 changed production paths (15 extant), then the two unchanged WI-10 ViewModels. Patterns covered `e/err/it/error/exception/throwable/t.message`, nullable access, `Timber.[vdiew](throwable, ...)`, `Timber.tag(...).[ew](throwable, ...)`, and Android Log throwable overloads. Multiline matches matter: a line-only grep misses the two budget calls. All logging calls were also inspected for variable/positional arguments. This was source inspection, **not execution of a static guard**.

### Surviving matches, counted and justified

| File | Throwable-to-log sites | Raw exception-message reads | Disposition |
|---|---:|---:|---|
| `M/data/repository/DatabaseBackupRepositoryImpl.kt` | **8**: `488,513,764,1361,2169,2171,2584,2586` | 0 | Unfixed. Four close/rollback sites are directly WI-5; remaining export/legacy/asset-collection logs are same-file survivors of the requested diagnostics sweep. Bound the log arguments only; no export gate/state changes. |
| `M/domain/budget/BudgetForecastingEngine.kt` | **2**: calls `345-351,354-359` | **1** at `344` | Remove throwable/date diagnostic payloads. The `SQLiteConstraintException.message` read itself is **justified as unchanged local UNIQUE classification**, not display/log/persistence; preserve its semantics, which distinguish foreign-key failures. |
| `M/startup/AppStartupCoordinator.kt` | **3**: `79,97,712` | 0 | Existing stale-bank/intake/worker recovery catches. Same allowed file, log-only follow-up; do not alter those recovery operations. |
| Other 12 extant changed files | 0 | 0 | No raw-exception matches. `ExpenseUseCases.kt` is deleted, so has no survivors. |
| `M/ui/screens/transactions/TransactionsViewModel.kt` (unchanged) | 0 | **13**: `181,257,461,477,499,522,560,587,657,705,717,739,765` | Outstanding WI-10 operation-error sites, including load/provenance and recurring-update error presentation. Limit changes to failure representation. |
| `M/ui/screens/receiptscan/ReceiptScanViewModel.kt` (unchanged) | **4**: `663,760,1252,1269` | **9**: `477,495,666,764,1270,1275,1284,1369,1401` | Scan/save sites are outstanding WI-10; assist/item-analysis catches are adjacent pre-existing errors outside the spec's enumerated scan/save targets, recorded rather than silently expanded into a UI-wide cleanup. `1270` duplicate substring is not justified as completion: B-1 blocks its replacement. |

**Changed-file total: 13 surviving throwable logs and 1 actual exception-message read. Entire spec-file total: 17 throwable logs and 23 exception-message reads.** All are accounted for; this is **not a zero-match pass**. Of the 23 reads, 21 feed UI/debug output; the other two are budget UNIQUE classification and receipt duplicate classification. No new raw `e.message` or throwable log call was added by this diff.

False positives / explicitly different contracts:

- `RestoreJournalImporter.kt:33` says `e.message` in a comment; not an executable occurrence.
- `CurrencyConverter.kt:497` retains `outcome.message` inside the typed **return** failure list, expressly permitted by WI-8. It is not logged by `convertMultiple` anymore.
- `BudgetForecastingEngine.kt:467` reads an `AnalyticsConversionWarning.message`, not a Throwable. It feeds existing forecast quality metadata outside the WI-2 unavailable-error sites. This is not proof of universal payload safety: producer `AnalyticsCurrencyNormalizer.kt:101` can include a currency code. Record as an adjacent pre-existing concern; do not rewrite the normalizer or forecast quality contract under this plan.
- Recovery-only internal paths in `RestoreJournal.JournalEntry.toJson:75-80` are required state, not exception summaries; **do not strip them** as a privacy shortcut. The staging-path Timber call `:758` is different and should be removed.
- Converter logs outside `convertMultiple` remain at `:162,193,207,291,532,561`. `:291` is directly exercised by WI-7 and must be fixed; unrelated rate-store diagnostics are recorded, not a license to change money policy.

### Spec code-table comparison

| Sites | Actual changed code | Result |
|---|---|---|
| Reminder diagnostic/notification/outer catches | `SIDE_EFFECT_EXCEPTION`, `WORKER_NOTIFICATION_PERMISSION_DENIED`, `WORKER_UNHANDLED_EXCEPTION` | Matches specified set; outcome tokens remain separate and unchanged. |
| Forecast unavailable and generic catch | `HOME_CURRENCY_UNAVAILABLE`, `LIMIT_CONVERSION_FAILED`, `MISSING_RATE`; `UNKNOWN_ERROR` | Matches; displayed text is fixed. Existing throwable insert logs still fail the sweep. |
| Synthesis and all changed analytics catches | `UNKNOWN_ERROR` with class/fixed operation | Matches; fallback behavior unchanged. |
| Journal parsing vs other changed journal/importer/startup catches | `PARSER_FAILED` vs `UNKNOWN_ERROR` | Changed constants are bounded; broad catches still need typed denial/timeout distinctions where applicable. |
| Restore/import/stats/reset | Mostly `UNKNOWN_ERROR`; reset inner branch adds `TIMEOUT`, `WRITE_BARRIER_DENIED`, `READ_BARRIER_DENIED` | Set is allowed by the final privacy table, but classification is incomplete (D-4). No `RESTORE_BLOCKED` classification added. `PARSER_FAILED` for unreadable source validation is controlled. |
| Dashboard/receipt/query providers | `PROVIDER_DISABLED`, `UNKNOWN_ERROR`, `NETWORK_UNAVAILABLE`, `TIMEOUT`, `PARSER_FAILED` | Changed constants match the final table. Dashboard/receipt `ParseError("PARSER_FAILED")` contains a controlled code, not parse detail; no need to restore raw messages. Query generic exceptions are all labeled parser failures; distinguish JSON from other exceptions in the follow-up. Receipt text-assist JSON also still falls into generic Unknown. |
| Shared FX and converter | `MISSING_RATE`; converter picks `STALE_RATE` if any failure is stale | Allowed; count and return contracts preserved. Shared historical nullable conversion supplies no typed stale result, so do not invent one or change rate semantics. |
| Transactions/receipt UI | No implementation | Fails; use only original spec codes/fixed user strings. |

Unchanged cloud gate-reason debug statements (`dashboard:136,148`; `receipt:127,285`; `query:90`) still interpolate decision text. At the named provider boundary, replace diagnostic text with a controlled disabled code if completing the full error sweep; leave gate/payload-routing semantics to CL-18/CL-21. Do not claim three services are universally payload-free based only on exception catches.

## 5. Persisted validation evidence — inspected, not rerun

All paths below are under this worktree's `build/validation-runs/`. Each directory has `request.json`, `result.json`, stdout/stderr and `complete.marker`. All recorded results are terminal; none is being inferred from a running process.

| Run directory | Profile / requested filter | Result / exit | Evidence |
|---|---|---|---|
| `vr-20260923-103651-2d60cf6c` | targeted-unit-test / `*BillReminderWorkerTest` | FAIL / 1 | `stderr.log:5-6`: missing Android SDK; no test execution. |
| `vr-20260923-103802-f33c5845` | same | FAIL / 1 | `stderr.log:1-5`: intermediate production import/type errors in reset catch; corrected at HEAD. |
| `vr-20260923-104220-7f01ecdc` | same | FAIL / 1 | `stderr.log:1-10,15`: five out-of-fence test compile failures. |
| `vr-20260923-105558-fe71466c` | compile | PASS / 0 | Historical dirty-tree fingerprint; not unit-test evidence. |
| `vr-20260923-111808-7d8d3402` | compile | PASS / 0 | Historical dirty-tree fingerprint; not unit-test evidence. |
| `vr-20260923-113708-ac98f0f1` | targeted-unit-test / `*BillReminderWorkerTest` | FAIL / 1 | `stderr.log:7` adds intermediate shared-test missing `expenseId`; `:1-6,8-11` reproduce external errors. |
| `vr-20260923-115545-68dca3ac` | targeted-unit-test / `*BillReminderWorkerTest` | **FAIL / 1** | `result.json` pins **d2748312**, clean start/end fingerprint; `stderr.log:15` is `:app:compileDebugUnitTestKotlin` failure. No selected test ran. |
| `vr-20260923-120525-545a27cf` | compile | **PASS / 0** | `result.json` pins **d2748312**, clean start/end fingerprint. `stdout.log` ends `compileDebugKotlin UP-TO-DATE`, successful cached build. Production compile evidence only. |

The saved commands are, respectively, `gradlew.bat :app:testDebugUnitTest --tests *BillReminderWorkerTest` or `gradlew.bat :app:compileDebugKotlin`, with `--console=plain --no-parallel --max-workers=1 --no-daemon -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50`, as recorded by the runner. **These are descriptions of old logs, not instructions to invoke Gradle directly.** Earlier records with `git_revision=ad412aec` have nonempty dirty-tree fingerprints; do not describe them as pristine-base test executions.

There is no durable successful test execution for BillReminderWorker, nor any requested run of the other named CL-17 tests. Compile PASS does not close WI-1 through WI-10.

### Five out-of-fence compile failures: base comparison verdicts

Although the prompt labels this exercise R3, these exact failures also exist in **this R2 lane's** saved stderr, so they were adjudicated here. Reference log **L** = `build/validation-runs/vr-20260923-115545-68dca3ac/stderr.log`.

Each of the five test blobs is byte-identical between `ad412aec` and HEAD (`git rev-parse base:path HEAD:path`), and the relevant production contract blobs are also identical. Base test contents were read with `git show ad412aec:<path>`. This is source-and-log attribution, not an unperformed base build.

| Test file under T/ | Verdict | Exact compile-error evidence and base contract |
|---|---|---|
| `data/privacy/KeystoreInstallationSecretHashingTest.kt` | **PRE-EXISTING at ad412aec** | L:1,3,4: `Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'` at test `:196,208,209`; L:2 `actual type is 'String?', but 'CharSequence' was expected` at `:199`. Base calls use nullable `merchantField.value` directly. Unchanged `M/domain/privacy/CloudPayloadRedactor.kt:49-50` declares `RedactedField.value: String?`. |
| `domain/consistency/LegacyDataConsistencyCheckerTest.kt` | **PRE-EXISTING at ad412aec** | L:5-6: fake class `:74` does not implement `suspend fun deleteOlderThan(beforeMs: Long): Int`. Base fake `:74-83` implements only get/insert; unchanged `M/data/database/dao/ReceiptEventDao.kt:23` already requires deleteOlderThan. |
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt` | **PRE-EXISTING at ad412aec** | L:7: `:202:13 No parameter with name 'privacySettingsRepository' found.` Base test passes that argument; unchanged coordinator constructor `M/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:99-133` has `rawPersistencePolicyResolver`, not that parameter. |
| `ui/screens/backup/BackupRestoreViewModelPrivacyDenialTest.kt` | **PRE-EXISTING at ad412aec** | L:8-9: `:189` has `actual type is 'Result<Unit>', but 'Result<DatabaseImportResult>' was expected` and `Success ... does not have a companion object`. Base uses `Result.success(DatabaseImportResult.Success)` without construction. Unchanged `M/domain/backup/DatabaseOperationResults.kt:12-15` already defines `data class Success(val summary: DatabaseImportSummary)`. The backup interface is unchanged too. |
| `ui/screens/export/ExportOptionsViewModelPrivacyDenialTest.kt` | **PRE-EXISTING at ad412aec** | L:10: `:75:13 No value passed for parameter 'exportJobSerializer'.` Base test constructor `:66-76` omits it; unchanged `M/ui/screens/export/ExportOptionsViewModel.kt:85` already requires it. |

**None references a symbol introduced/removed by this lane.** Do not fix these five forbidden tests in CL-17, skip them, weaken assertions, or change build source sets. Completion needs their owning lane/human-approved prerequisite repair, then actual CL-17 test runs.

## 6. Cross-lane flags — human merge order only

Compared `git diff --name-only ad412aec..rp-20-wip`, `..rp-22-wip`, `..rp-23-wip`, and `..rp-24-wip`. Snapshot tips: rp-20 `a4469a5786f59451f9f40aaea95a7a7138eadfb0`; rp-22 `d3e8fa25689b76f67abcfa1ad55c3e5c517e3e4e`; rp-23 `0b955ec1e2f06f371a98cd0ba4162dd0c3eb8595`; rp-24 `cbb0a54eff6a2d5bae1327e7c82501b8d1ba25d9`. Sibling diffs were read only where necessary to establish interactions; this is not a completion verdict for those lanes.

1. **rp-20 -> rp-21: `DatabaseBackupRepositoryImpl.kt`.** CL-18 adds `DiagnosticReasonCode` import, an optional recorder constructor parameter, and `blocksExecution()` + bounded denial codes in `runCostBackupExport`. CL-17 also adds that import and changes diagnostics elsewhere. Preserve CL-18's gate and fixture injection; do not resolve the shared file wholesale in favor of either lane. CL-17 completion must not rewrite the export FailClosed behavior.
2. **rp-21 -> rp-22: `RestoreJournal.kt`, `DatabaseBackupRepositoryImpl.kt`, `AppStartupCoordinator.kt`.** CL-19 changes journal parsing/durability, catch precedence, reset safety transitions and asset-rollback exit. CL-17 changes logs in overlapping journal catches, adds cancellation rethrows, and bounds reset summaries. CL-19's base-derived `readJournalResult` catch retains a throwable log and its generic durability conversion can lose CL-17's cancellation rethrow if a conflict is resolved incorrectly. Preserve both fail-closed state semantics and CL-17's bounded representation/cancellation. New CL-17 `failJournal` bounding must compose with CL-19's throwing durability contract, not swallow it or delete recovery evidence.
3. **Repository reset/restore/import catches:** CL-19 typed journal-durability early returns must precede generic diagnostic mapping. A CL-17 code-only-summary fix is not permission to exit NORMAL after uncertain durability or to revive deleted throwable overloads. Conversely, adopting CL-19's entire reset hunk would lose CL-17's removal of raw summary text.
4. **rp-23 / CL-01 and rp-24 / CL-29:** no identical-file intersection with this lane's changed set. Group offset reads `GroupsRepository`, so human integration should retain the combined group behavior tests; no split/member/sign changes are authorized here.

The R3-specific persisted-NORMAL scheduling window, present-but-null mode, post-destructive rollback/import durability, seven-worker scheduling-before-observable-NORMAL, and `DatabaseBackupRepositoryImplTest` state-machine gaps belong to CL-19. This lane does not modify `RestoreMaintenanceMode`, and its importer/state transitions are unchanged except diagnostic/cancellation representation. **No R3 state-machine approval is implied.** Flag those questions to the CL-19 completion reviewer; do not repair them here. The backup repository test is unchanged in this lane as well, so it supplies no new reset-diagnostic evidence.

## 7. DIRECT-FIX completion work items

Execute one bounded batch at a time, retaining original WI numbering with suffixes. All locations below are at the reviewed HEAD. **Original fences remain in force:** only the exact CL-17 production files and named focused tests; `EventMetadataSanitizer.kt` only if a narrowly shared helper is genuinely required. No schema/DAO/migration/guard/baseline changes, no privacy-routing/FailClosed changes, no restore durability/state-machine or bank-caller edits, no group arithmetic/member/sign changes, and no transaction/receipt lifecycle edits. Do not broaden the fence to unblock tests or B-1. This plan does not authorize commits/merges by the completion coder unless separately requested.

### WI-4a — Enforce bounded journal error representation end to end (D-1, CRITICAL)

- **Location:** `M/data/backup/RestoreJournal.kt:60,81,131-132,632-636`; `M/data/backup/RestoreJournalImporter.kt:165-190`; `M/data/repository/DatabaseBackupRepositoryImpl.kt:2753-2760,2802-2826`.
- **Change:** Bound the failure summary at `failJournal`, not only reset's caller. Map to the original spec's finite restore reason-code set, with `UNKNOWN_ERROR` for arbitrary/legacy free text; keep optional exception class separate and never read message text to manufacture a reason. Preserve the callable contract if external callers need it; do not force edits outside the fence. Import legacy journal errors as controlled values instead of `entry.error` verbatim, retaining existing correlation/event idempotency. Do not rewrite recovery state, required internal paths, or rename/fsync behavior. Coordinate the narrow representation hunk with CL-19.
- **Acceptance criteria:** SQL, local paths, URI and merchant/amount sentinels supplied as failure text are absent from the error summary in failure JSON and OperationRun; known allowed codes survive; arbitrary/empty/legacy values fail to a controlled value. Existing import retry/idempotency holds. Cancellation remains cancellation and does not create a terminal failure record.
- **Tests:** Update `T/data/backup/RestoreJournalImporterFailureTest.kt` (especially verbatim expectation `:111`), plus the spec-authorized focused restore-journal diagnostic test (a diagnostics-focused extension of `RestoreJournalDurabilityTest` is acceptable). Cover new failure write, legacy bytes import, two imports, partial event retry, barrier/DAO cancellation, and hostile SQL/path/URI/financial text; inspect serialized error fields, not recovery path fields.
- **Validation profile:** `targeted-unit-test`, exact importer and journal diagnostic classes, then final `compile` after all completion shards.

### WI-5a — Finish restore/recovery log sweep and typed reason selection (D-2/D-4)

- **Location:** repository throwable sites `:488,513,764,1361,2169,2171,2584,2586` and changed catches `:1276,2008,2647,2825`; journal staging log `:758`; startup `:79,97,712` and changed recovery/import catches.
- **Change:** Replace remaining throwable-to-log overloads with fixed operation/stage, a permitted reason code and class only; remove staging path logging. At affected catches select `WRITE_BARRIER_DENIED`/`READ_BARRIER_DENIED` from typed access, `RESTORE_BLOCKED` from established maintenance denial, `TIMEOUT` from WorkerDrainTimeoutException, `PARSER_FAILED` from parsing, otherwise `UNKNOWN_ERROR`. Apply mapping where failure actually arrives, including reset's outer drain catch. Preserve existing cancellation rethrows before generic classification; do not move TimeoutCancellationException into durable failure handling. Do not change recover/exit/retry/IO control flow.
- **Acceptance criteria:** No remaining throwable log in the named restore/startup files; no staging paths in log text. Correct controlled code on read/write/drain/parse/unknown fixtures. Identical state/outcome behavior to the lane's owning state-machine contract. No new helper in a forbidden file.
- **Tests:** `T/startup/AppStartupCoordinatorRecoveryTest.kt`, `T/data/backup/RestoreJournalImporterFailureTest.kt`, and the same restore journal diagnostic fixture. Add malformed-journal log capture, recovery/open/close failure class-only assertions, cancellation, and typed mapping cases. If a repository-only behavior cannot be reached by these authorized fixtures without altering production architecture, report the coverage boundary instead of expanding the fence unilaterally.
- **Validation profile:** serialized `targeted-unit-test` for these named classes; final `compile`.

### WI-6a — Restore query retry parity; complete provider error matrix (R-1)

- **Location:** `M/data/ai/provider/CloudQueryInterpretationService.kt:156-178`; dashboard `:229-271`; receipt `:212-253,377-403`; existing gate-reason logs listed in section 4.
- **Change:** Keep typed safe timeout/SSL results but consult the existing retry policy for SSL IO just as the base query implementation did; no edit to CloudRetryPolicy itself. Preserve attempt limits/backoff/cancellation. Distinguish JSON parsing from generic failures using `PARSER_FAILED` vs `UNKNOWN_ERROR`; text-assist JSON should use the existing ParseError variant without raw details. Replace provider-boundary reason interpolation in logs with fixed safe code, without changing gate decisions/payload preparation. No raw provider/exception messages in returned errors.
- **Acceptance criteria:** Retryable SSL exception with timeout/reset cause attempts the same number as the base policy, may succeed on the next attempt, and exhausts safely; ordinary SSL stays terminal. HTTP/IO/timeout retry limits unchanged. JSON/generic/result-to-artifact-safe strings contain controlled codes, never hostile payload text. Cancellation propagates without a terminal error/log throwable.
- **Tests:** All three original named classes: `CloudDashboardBriefingServiceTest`, `CloudReceiptAssistServiceTest`, `CloudQueryInterpretationServiceTest`. Cover timeout/SSL/IO/JSON/generic failures, retry success/exhaustion, cancellation, null/empty body and actual error-field serialization/readable-message output used by artifact consumers. Capture Timber and assert throwable is null. For receipt cover both suggest and suggestFromText. Explicitly stub PrivacyGate.Allowed so failure injection reaches the provider; verify execute call counts. Do not replace named classes with unrelated tests.
- **Validation profile:** three separate serialized `targeted-unit-test` filters; final `compile`.

### WI-7a — Close the nested historical-FX log leak and prove all three branches

- **Location:** `M/domain/currency/CurrencyConverter.kt:291`; `M/domain/groups/SharedExpenseBudgetOffsetEngine.kt:117-175`; `T/domain/groups/SharedExpenseBudgetOffsetEngineTest.kt:73-106`.
- **Change:** Delete the historical missing-rate log or make it code-only in the already-allowed converter file. Keep the shared engine's warning codes/counts and all existing calculation/return semantics. Do not modify group ownership/splitting, rate selection, currency fallback, precision or rounding.
- **Acceptance criteria:** Personal/shared/reimbursed failures still count three and mark partial; neither the engine nor the real converter logs merchant, amounts, currencies or timestamps on these paths; warning list remains code-only.
- **Tests:** Extend `SharedExpenseBudgetOffsetEngineTest` with a real CurrencyConverter backed by a mocked exchange-rate store (not a mocked convertAsOf implementation) and log capture; ensure distinct currencies force lookup rather than identity conversion. Preserve the existing mocked branch/count case and success totals.
- **Validation profile:** `targeted-unit-test -TestFilter '*SharedExpenseBudgetOffsetEngineTest'`, then `'*CurrencyConversionTest'` serially; final `compile`.

### WI-1a / WI-2a / WI-3a / WI-8a / WI-9a — Close the exact missing assurance cases

- **Locations and changes:**
  - **WI-1a:** `T/service/reminder/BillReminderWorkerTest.kt:191-286`; add outer coordinator non-cancellation failure and log assertions for permission, notification and both diagnostic-writer sites. Assert ShadowLog throwable fields, not only text; retain success metrics and failure/permission/cancellation outcomes. No worker-guard or scheduling changes.
  - **WI-2a:** engine throwable logs `M/domain/budget/BudgetForecastingEngine.kt:345-359` become permitted code/class-only diagnostics; preserve local UNIQUE-vs-foreign-key classification. Add aggregate-unavailable engine case and generic/cancellation/stale-request ViewModel tests in the two original classes. Test cancellation while the engine is suspended and an older failing request completing after a newer request; do not redesign successful forecast generation.
  - **WI-3a:** `T/domain/logic/SynthesisEngineTest.kt:52-97`; run the two added tests as written, strengthen with exact cancellation identity/no fallback log and no leaked message if needed. Do not rewrite correct production fallback.
  - **WI-8a:** `T/domain/currency/CurrencyConversionTest.kt:180-202`; retain exact log and mixed totals/failure-list assertions, add actual supported-currency missing rate, stale-only and mixed stale/missing cases with unchanged typed return details. Existing XYZ fixture primarily exercises invalid-source classification. No money production rewrite.
  - **WI-9a:** `T/domain/analytics/TotalsAggregationEngineTest.kt` (existing `:272`) and `T/domain/analytics/InsightsEngineTest.kt` (existing `:95`); add three named average/reactive/category catch branches and all seven Insights branch failures/cancellation cases, invoking public flows where helpers are private. Check unchanged zero/empty/degraded fallback, subsequent Flow behavior, exact class/code and null Timber throwable. No new analytics persistence path.
- **Acceptance criteria:** Each original enumerated boundary has a real test assertion and an executed result; tests do not merely call mocked engines. No lifecycle, worker outcome, UI success contract, calculation or Flow fallback changes. WI-3/WI-8 need evidence, not gratuitous source edits.
- **Tests:** Exactly the classes named above; they are the original focused suites or existing analytics/aggregate equivalents expressly permitted by the spec.
- **Validation profile:** each named `targeted-unit-test` filter in sequence; final `compile`. Do not treat a compile failure before selection as a passing test.

### WI-10a — Implement in-fence transaction and receipt failure presentation

- **Location:** `M/ui/screens/transactions/TransactionsViewModel.kt` operation-error sites listed in section 4; `M/ui/screens/receiptscan/ReceiptScanViewModel.kt:472-511,1252,1268-1285`.
- **Change:** Map load/delete/update errors to fixed user-facing text with controlled operation codes; use fixed OCR/parser/source-link/unknown text at receipt scan/save boundaries. Keep rawOcrText and debug rawText empty on failure; prevent accumulated parsingLogs from retaining failure payloads. Rethrow cancellation before UI mapping and in Result/onFailure callbacks. Use only local presentation/diagnostic changes, not repository/coordinator mutation changes. Do not assume `DiagnosticReasonCode` has transaction-specific enum members: it does not; fixed local presentation constants are allowed, but persisted diagnostics must use existing permitted enum values rather than adding enum/schema values outside the fence.
- **Acceptance criteria:** Hostile exception text never appears in targeted UI error, parsingLogs, debugData or SaveReceiptResult.Error. Stale scan requests stay suppressed; cancellation leaves no failure UI. Success/save/link ownership and atomicity unchanged. **Do not claim duplicate acceptance passes** until WI-10b's dependency is resolved.
- **Tests:** Create the exact spec-named `T/ui/screens/transactions/TransactionsViewModelTest.kt` and `T/ui/screens/receiptscan/ReceiptScanViewModelTest.kt`; existing `...StressTest` classes are not substitutes. Cover every listed operation failure, OCR/parser/save failure, hostile SQL/path/URI/receipt text, Result-wrapped cancellation and thrown cancellation. After typed dependency approval/arrival, add the duplicate typed-failure test in this same class.
- **Validation profile:** serialized `targeted-unit-test` for the two exact classes; final `compile`.

### WI-10b — STOP for typed-duplicate owner dependency (B-1; no authorized lifecycle edit)

- **Location/evidence:** `M/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt:1590-1595` (read-only dependency); consumer `ReceiptScanViewModel.kt:1270-1275`.
- **Required action:** Escalate the contradiction to the human/architecture owner. Obtain a separately reviewed typed failure/status contract from the coordinator owner, or an explicit revision of the original fence/acceptance criterion. This completion plan does **not** grant either. Do not introduce a duplicate-detection facade or two-step mutation.
- **Acceptance criteria:** A real producer supplies an unambiguous typed duplicate signal; only then may the allowed ViewModel map it to DuplicateTransaction without reading a message. Until then, WI-10 remains incomplete/blocked regardless of other safe-text changes.
- **Tests:** Existing spec's `ReceiptScanViewModelTest`: duplicate typed failure vs ordinary IllegalStateException, misleading text containing “Duplicate” must not produce a duplicate card, cancellation preserved. Producer-side tests belong to the separately authorized owner, not CL-17.
- **Validation profile:** `targeted-unit-test` for that consumer class after the approved dependency is available, then final `compile`; no validation is claimed while blocked.

### WI-12 — Final serial execution and completion gates (no production scope expansion)

- **Location:** the original named focused tests, the saved failures in section 5, and `scripts/validation-runner.ps1` **as an execution interface only**.
- **Change/action:** First obtain the external five-file test-compile repair through its authorized owner; do not edit/exclude those files or relax build/guard policy here. Finish authorized batches, request the original strict and privacy review gates, then have the sole validation owner run targeted profiles. No new broad feature or refactor work.
- **Acceptance criteria:** Every named test actually executes with terminal PASS at the completed code fingerprint; targeted logs/XML identify tests and counts; final compile PASS follows; no pending/failing reviewer/guardian gate. Missing, timed-out, stale, unknown or infra-failed results block completion. Recheck surviving logs, cancellation and merge interactions at the final source version. Keep WI-10 blocked if typed producer is still missing.
- **Tests/profile queue:** BillReminderWorkerTest; BudgetForecastingEngineDiagnosticsTest; BudgetForecastingViewModelTest; SynthesisEngineTest; RestoreJournalImporterFailureTest; AppStartupCoordinatorRecoveryTest; focused RestoreJournalDurabilityTest/diagnostic cases; three Cloud provider classes; SharedExpenseBudgetOffsetEngineTest; CurrencyConversionTest; TotalsAggregationEngineTest; InsightsEngineTest; TransactionsViewModelTest; ReceiptScanViewModelTest. Preserve WI-11's no-reference evidence and unchanged lifecycle guards. Any additional guard evidence must use its existing runner profile, never direct scripts or altered allowlists.
- **Runner command template (completion owner, NOT executed by reviewer):**

```powershell
# Establish SDK environment and inspect existing runner ownership first.
$env:ANDROID_HOME = 'C:\Users\panos\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action List
# For each exact class above, one run at a time:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile targeted-unit-test -TestFilter '*BillReminderWorkerTest'
# Poll the returned run id; repeat Wait for RUNNING, never start its duplicate.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Wait -RunId '<returned-run-id>'
# Only after all required targeted runs and source review gates:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
```

Record each actual command, revision/fingerprint, exit code, run directory, stdout/stderr and test counts. Ask before expensive validation as required by the repository. Do not run overlapping Gradle/guard work. Do not mark completion green on the historical cached compile.

## 8. Delivery / journal handoff

Files touched by this reviewer: **only this review-completion artifact**. Validation: **NOT RUN by reviewer**, by explicit request; saved results are reported separately. Risks: unfinished journal/UI privacy boundary, one query retry regression, failed test compilation, missing runtime assurance, and typed-duplicate fence dependency. Human merge order remains `rp-20 -> rp-21 -> rp-22`; no merge was attempted.

Suggested journal line (actual review date is September 24; the prompt's September 23 prefix describes the prior implementation day):

```text
2026-09-24T09:38+03:00 | review-completion (astra xhigh) | WAVE1-REVIEW | CL-17 | 1/11 DONE-CORRECT, 1 regressions, out-of-fence verdicts Keystore=PRE-EXISTING;LegacyConsistency=PRE-EXISTING;ReceiptLifecycle=PRE-EXISTING;BackupPrivacy=PRE-EXISTING;ExportPrivacy=PRE-EXISTING | rp-21-wip
```
