# CL-15 — author static review

Status: implementation and targeted test authoring recorded for all eight findings. Independent strict review, applicable guardian approval, compilation, and runtime validation remain PENDING. This is not a merge approval or a cluster-closure record.

## Inspected changes

| Finding | Source-level result | Regression coverage authored |
| --- | --- | --- |
| CA-P-03-003 | OCR reports successful rather than attempted pages. Failed-page evidence and truncation both set isPartial; the existing ledger records PDF_PARTIAL and COMPLETED_WITH_SKIPS without inventing failed transaction counts. The coordinator preserves the result and ReviewViewModel labels partial imports. | OcrResultPartialTest, BankStatementCompletionStatusTest, ReviewViewModelPrivacyDenialTest. The terminal-policy tests exercise the helper actually called by the processor; they are not full Room/OCR integration tests. |
| CA-P-05-003 | Failed required radar inputs produce UNAVAILABLE with a null score, no healthy reassurance, and no recommendation. Valid empty inputs retain GREEN. Cancellation propagates; touched diagnostics no longer carry the budget-risk object or exception payload. | ComputeMoneyRadarUseCaseTest: required inputs, cancellation, unavailable simulation, unsuccessful impact outcomes, failed income, and retained healthy controls. |
| CA-P-05-004 | Only the savings widget's own deadline is converted to omission via withTimeoutOrNull. Caller deadlines and dependency cancellation propagate. | ComputeDashboardWidgetsUseCasePaceWiringTest: real dashboard assembly, own deadline, caller deadline, cancellation, and ordinary dependency failure. |
| CA-P-06-005 | Calculation/settings failures return typed unavailability with no fabricated risk, probability, horizons, or advice. The card renders an unavailable state instead of a healthy subtitle. | FinancialStressForecastEngineTest: calculation failure, settings failure, cancellation, explicit-currency success, and retained calculation controls. |
| CA-P-07-002 | Statistics consult the canonical read barrier before DAO access, then return genuine counts or a bounded typed exception. Both consumers distinguish unavailable from zero/no previous backup and propagate cancellation. | DatabaseBackupRepositoryImplTest, BackupRestoreViewModelPrivacyDenialTest, DebugViewModelDatabaseStatsTest. |
| CA-P-07-009 | Required semantic queries cannot be skipped on exception, empty/null cursor, or invalid count. Verification fails closed; cancellation propagates. | BackupVerifierRequiredSemanticQueryTest uses the public verifier and a real complete SQLite fixture, with narrowly intercepted failure queries. The existing backup fixture now includes the required reference columns. |
| CA-P-12-003 | A malformed first header is an import error, while legitimate empty/comment-only content remains a successful empty import. Data-row behavior is preserved. | CsvImportRfc4180Test. |
| CA-E-01-004 | Both builder overloads classify all failed buckets as UNAVAILABLE using bucket outcomes, not totals or transaction counts. Source buckets, failures, rate basis, and count metadata remain intact. | MoneyAggregateBuilderRestrictionTest covers all-failed, zero/count-agnostic success, net-zero success, and empty/identity controls. |

## Cross-lane and preservation checks

- CL-05 section 8 remains authoritative: successful zero and zero-count buckets are not unavailable. CurrencyCode and the narrow display adapter were not globally rewritten.
- The existing BudgetForecastingEngine UNAVAILABLE check and its no-insert regression were inspected; no duplicate forecast-path change was needed.
- A1's startup cancellation repair already exists in current source; no duplicate edit was made.
- A2's typed denial reason transport remains intact in the overlapping backup files.
- A4 is not silently closed: the current ReviewViewModel denial fixture supplies a non-empty AiSettings flow, but the historical failure requires fresh runtime/independent verification. New statement tests preserve that fixture and cancel ViewModel scopes during teardown.
- Source inspection caught and corrected a missing logger qualification in DebugViewModel before this record. This was an inspection finding, not a compiler result.
- No Room entity/schema version, migration, guard baseline, allowlist, or production settlement writer was changed by this slice.

## Validation status and remaining evidence

- Whitespace inspection: git diff --check -- app returned exit 0 after the final consumer test was authored. This is not compilation, a test run, or a guard run.
- New compile/tests/guards: NOT RUN, following the campaign's human-run validation boundary.
- Independent strict review and privacy/architecture guardians: PENDING; author inspection does not replace them.
- Device-dependent PDF/image recognition remains a separately recorded campaign follow-up. New OCR tests cover metadata and terminal policy, not physical page rendering or a full processor/Room transaction.
- The historical full-suite failure tail remains unresolved and excluded; no green baseline is assumed.

From the repository root, run the following through the wrapper, one at a time. Stop on failure or unknown infrastructure state; poll an existing RUNNING result rather than launching another process.

```powershell
pwsh scripts/vrun.ps1 -Worktree . -Profile compile
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*MoneyAggregateBuilderRestrictionTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*CsvImportRfc4180Test'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*BackupVerifierRequiredSemanticQueryTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*DatabaseBackupRepositoryImplTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*OcrResultPartialTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptOcrRetryIsolationTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*BankStatementCompletionStatusTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ComputeMoneyRadarUseCaseTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*FinancialStressForecastEngineTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ComputeDashboardWidgetsUseCasePaceWiringTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*BackupRestoreViewModelPrivacyDenialTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReviewViewModelPrivacyDenialTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*DebugViewModelDatabaseStatsTest'
pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*BudgetForecastingEngineDiagnosticsTest'
```

Record actual runner IDs, exit codes, logs, fingerprints, and independent review outcomes before any closure decision.
