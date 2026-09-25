# CL-17 implementation report — `rp-21-wip`

Date: 2026-09-23  
Session: direct coding session; no subagents  
Spec: `wave1/CL-17-bounded-diagnostics-ui-error-leakage.md`

## Verdict

The implementation is **partial and not merge-ready**. Four work items are complete (WI-3, WI-7, WI-8, WI-11); the remaining items are deviated, blocked by source/test-contract mismatches, or lack the complete enumerated test coverage. The spec gate was explicitly overridden by the user for this session. No strict reviewer or privacy guardian gate was run.

## Work-item report

| Work item | Status | Result |
|---|---|---|
| WI-1 | Deviated | Reminder worker throwable logging was replaced with bounded reason/class logging; worker guard, cancellation, permission branch, and outcome classification were preserved. Test coverage does not cover every specified logging branch. |
| WI-2 | Deviated | Budget forecast engine/ViewModel now expose controlled reason text and generic `Forecast unavailable`; cancellation and stale-request handling remain. Full specified engine/ViewModel boundary coverage is incomplete. |
| WI-3 | Done | Synthesis fallback logs controlled code/class only and preserves cancellation propagation and degraded fallback behavior. |
| WI-4 | Blocked/partial | Reset failure persistence now uses controlled codes and avoids exception text. `RestoreJournal.failJournal` was not globally sanitized because an existing out-of-fence `BackupRestoreContractTest` requires raw supplied text; importer therefore cannot be claimed fully compliant. |
| WI-5 | Deviated | Restore/import/startup throwable log arguments were bounded at the changed sites; the specified restore/recovery test set was not fully added or run. |
| WI-6 | Deviated | Cloud provider result/error fields and provider logs were bounded to controlled codes, attempts, stages, and exception classes; the complete timeout/SSL/IO/JSON/retry/cancellation test matrix is incomplete. |
| WI-7 | Done | Shared FX payload-bearing warning construction and Android logging were removed; failed conversions retain partial/count/code-only metadata. |
| WI-8 | Done | `convertMultiple` now emits one aggregate code/count diagnostic while preserving typed failure contents and totals. |
| WI-9 | Deviated | Named analytics throwable logs were replaced with controlled code/class logging; the complete average/flow/category/insights test matrix was not added or run. |
| WI-10 | Blocked | Receipt duplicate handling is encoded as `Result.failure(IllegalStateException("Duplicate transaction detected"))` in the forbidden receipt lifecycle file. Typed duplicate detection would require a forbidden mutation-path edit, so transaction/receipt ViewModels were left unchanged. |
| WI-11 | Done | Deleted unused `ExpenseUseCases.kt`; current-HEAD production reference search found no callers or bindings. |

## Commits

- `1539b5d0` — Remove unused unsafe expense use cases (WI-11)
- `3b18c12b` — Bound reminder worker failure logs (WI-1)
- `160fba27` — Bound budget forecast failure UI (WI-2)
- `d6c8aeab` — Remove synthesis throwable logging (WI-3)
- `928812da` — Partial WI-4 and bounded WI-5 restore diagnostics
- `4ba2291f` — Bound cloud provider failure fields (WI-6)
- `71ade640` — Remove shared FX payload logs (WI-7)
- `93560a25` — Aggregate conversion failure logs (WI-8)
- `d2748312` — Bound analytics failure logs (WI-9)

The lane branch was `rp-21-wip` and the final lane worktree was clean. No campaign journal or state file was edited.

## Files and tests

Production changes are limited to the spec-named worker, budget, synthesis, backup/restore, startup, cloud provider, currency, shared-FX, analytics, and budget-UI files. The named focused tests updated or added were:

- `BillReminderWorkerTest`
- `BudgetForecastingViewModelTest`
- `BudgetForecastingEngineDiagnosticsTest`
- `SynthesisEngineTest`
- `CloudDashboardBriefingServiceTest`
- `CloudReceiptAssistServiceTest`
- `CloudQueryInterpretationServiceTest`
- `SharedExpenseBudgetOffsetEngineTest`
- `CurrencyConversionTest`

No transaction/receipt ViewModel tests were changed because WI-10 was stopped at the typed-signal mismatch.

## Validation

Targeted validation was run only through `scripts/validation-runner.ps1`:

```text
Profile: targeted-unit-test
Command: gradlew.bat :app:testDebugUnitTest --tests *BillReminderWorkerTest --console=plain --no-parallel --max-workers=1 --no-daemon -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50
Result: FAIL, exit 1
Run: vr-20260923-115545-68dca3ac
Log: build/validation-runs/vr-20260923-115545-68dca3ac/stderr.log
```

The targeted run stopped during whole-test-source compilation on unrelated, out-of-fence tests (`KeystoreInstallationSecretHashingTest`, `LegacyDataConsistencyCheckerTest`, `ReceiptLifecycleCoordinatorTest`, `BackupRestoreViewModelPrivacyDenialTest`, and `ExportOptionsViewModelPrivacyDenialTest`). The scoped tests did not execute.

```text
Profile: compile
Command: gradlew.bat :app:compileDebugKotlin --console=plain --no-parallel --max-workers=1 --no-daemon -PvalidationMaxParallelForks=1 -PvalidationForkEvery=50
Result: PASS, exit 0
Run: vr-20260923-120525-545a27cf
Log: build/validation-runs/vr-20260923-120525-545a27cf/stdout.log
```

## Reviewer focus

Inspect WI-4's legacy `failJournal` contract, WI-10's untyped duplicate result and forbidden-path boundary, cancellation preservation in changed catches, controlled cloud/diagnostic reason codes, and the incomplete enumerated test coverage. A strict reviewer and privacy-security guardian must pass before this work can be considered complete.

## Journal line for human append

`2026-09-22T15:06+03:00 | coder (direct session, sol high) | WAVE1-IMPLEMENT | CL-17 | 4/11 work items, tests BillReminderWorkerTest/BudgetForecastingViewModelTest/BudgetForecastingEngineDiagnosticsTest/SynthesisEngineTest/CloudDashboardBriefingServiceTest/CloudReceiptAssistServiceTest/CloudQueryInterpretationServiceTest/SharedExpenseBudgetOffsetEngineTest/CurrencyConversionTest, validation targeted FAIL (out-of-fence compile), compile PASS | rp-21-wip`

> The requested journal format uses 2026-09-22, while the execution environment date was 2026-09-23. Correct the timestamp before appending.
