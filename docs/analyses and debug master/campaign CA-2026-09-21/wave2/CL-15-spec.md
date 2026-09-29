# CL-15 — failure semantics (rp-29)

Status: source verification, implementation, targeted test authoring, and author static review recorded for the eight finding anchors. Live validation and independent gates remain pending. This is the single-session author's implementation specification, not independent approval or cluster closure.

## Scope and current-source decisions

| Finding | Current owner | Minimal contract |
| --- | --- | --- |
| CA-P-03-003 | ReceiptOcrService / BankStatementLifecycleProcessor | Count successfully recognized PDF pages, retain failed-page metadata, and propagate partial recognition to the existing pdfPartial ledger fields and COMPLETED_WITH_SKIPS status. Preserve successfully staged reviews; do not fabricate failed transaction counts or add a schema column. |
| CA-P-05-003 | ComputeMoneyRadarUseCase / MoneyRadarWidget | A failed required signal produces explicit UNAVAILABLE with no numeric urgency score, healthy reassurance, or action derived from incomplete evidence. Legitimate empty data/no budget remains GREEN. Cancellation propagates. |
| CA-P-05-004 | ComputeDashboardWidgetsUseCase | The savings widget's own three-second deadline omits that widget only. Caller cancellation still propagates; no blanket swallowing of TimeoutCancellationException. |
| CA-P-06-005 | FinancialStressForecastEngine / FinancialStressForecastCard | Calculation failure produces a typed unavailable result, no invented horizons/probabilities or MODERATE risk. Currency-settings failure is inside this boundary; cancellation propagates. Preserve successful forecast calculations and existing partial-conversion metadata. |
| CA-P-07-002 | DatabaseBackupRepositoryImpl / backup and debug consumers | Admit statistics through the existing DatabaseReadBarrier. Blocked/query-failed reads raise a controlled typed error, never successful zero counts. Consumers distinguish unavailable from empty/no backup and never expose exception payloads. |
| CA-P-07-009 | BackupVerifier | Each required semantic query must execute and return a valid count. Query failure or missing result makes verification fail with bounded diagnostics, not a skipped check. |
| CA-P-12-003 | CsvExpenseImporter | The first non-skippable malformed record is a malformed-header error, not an empty successful import. Keep legitimate blank/comment-only input and malformed data-row accounting contracts. |
| CA-E-01-004 | MoneyAggregateBuilder | Both overloads explicitly classify all failed buckets as UNAVAILABLE. Successful zero, net-zero, and count-agnostic buckets remain successful. Preserve every source/failure bucket, counts, rate basis, and CL-27 quality metadata. |

## Dependencies and fences

- CL-27 and CL-05 are already merged. CL-05 review section 8 governs all-failed semantics: do not classify success using amount, included transaction count, or zero-valued totals. Do not tighten CurrencyCode globally or rewrite the narrow display adapter.
- A1's historical AppStartupCoordinator catch is already cancellation-safe in current source (the broad-catch repair is in 3b7cabfe). No duplicate startup edit is needed.
- Preserve this session's A2 typed denial transport in the overlapping backup repository/ViewModel files. Re-review their combined diff.
- Keep all writes on existing lifecycle paths. No Room schema migration, baseline growth, guard exception, new worktree, commit, or independent-review waiver.
- Guard hardening CL-22 follows this lane. Unrelated failure-tail, raw-money, migration-artifact, and emulator debts remain excluded.

## Required evidence

Author tests for each changed failure boundary: success/empty controls, partial versus all-failed outcomes, cancellation identity, local timeout versus caller cancellation, blocked stats with no DAO access, required query failure, malformed header, and OCR partial metadata/status. Read changed callers and update only assertions invalidated by the corrected contract.

Per the campaign coder wrapper, live validation is human-owned and serialized through scripts/vrun.ps1. Compile, focused tests, independent strict review, and applicable guardians remain PENDING until actual evidence exists. Static author review and whitespace inspection do not satisfy those gates. No cluster closure is implied by this specification.
