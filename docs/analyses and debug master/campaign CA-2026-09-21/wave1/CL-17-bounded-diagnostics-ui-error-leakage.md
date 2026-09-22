# CL-17 Implementation Spec — Bounded diagnostics and UI error leakage
Provenance: 2026-09-22; pin 37601232 verified; astra xhigh Stage-3 session; source cluster-map.md.

## GATE
- Implementation is blocked until CA-E-04-001 and CA-P-07-006 (P0, unverified) and CA-P-06-003 and CA-P08-003 (P1, unverified) are independently revalidated. All other members are audited evidence; this gate still applies to the cluster because the map requires the named P0/P1 revalidation.

## Context for the coder
- This cluster covers failure reporting across reminder workers, budget/analytics engines, restore/reset diagnostics, cloud AI providers, currency conversion, shared-expense FX, and UI state. Users should see a stable failure category or unavailable state, while durable diagnostics contain bounded reason codes and exception class names only.
- Root cause: broad catches pass throwables, `e.message`, merchant/amount/currency/date payloads, or raw failure strings to Android logs, UI state, restore journals, operation ledgers, or AI result models. The approved placement is shared policy through `EventMetadataSanitizer`; ad hoc scrubbing is out of scope. CA-E-04-001 requires deletion of financial payload logging, and CA-I-05-004 requires deleting dead use cases.

## Pre-implementation checks (coder MUST run, in order)
1. `git diff 37601232..HEAD -- app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt app/src/main/java/com/yourname/expensetracker/startup/AppStartupCoordinator.kt app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudReceiptAssistService.kt app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt app/src/main/java/com/yourname/expensetracker/domain/usecase/expense/ExpenseUseCases.kt` — read every hunk.
2. Re-read every target function below at current HEAD. Function names/signatures are authoritative; pin line numbers are advisory.

## Legal path constraints
- Worker outcomes remain inside `WorkerExecutionGuard`; do not bypass it or write worker diagnostics directly to DAOs.
- Cloud calls remain on `EffectiveCloudAiPolicy`/`CloudPayloadPolicy` and `PreparedCloudPayload`; CL-17 only changes failure representation/logging at provider boundaries.
- Restore/reset failure persistence remains `RestoreJournal` → `RestoreJournalImporter` → operation ledger; sanitize before persistence and do not create a second journal path.
- Analytics and money engines remain read/calculation paths; do not add mutation or UI-specific business rules.
- Group expense mutations remain through `SharedExpenseManager`/`GroupTransactionCoordinator`; this work changes only diagnostics.

## Work items
### WI-1 — Bound reminder worker diagnostics (CA-P-04-007, P2, verified)
- Location: `app/src/main/java/com/yourname/expensetracker/service/reminder/BillReminderWorker.kt` — `doWork()` ≈L44-180 and `sendNotification(...)` ≈L211-270 @pin.
- Defect: `Log.w/e(..., e)` exposes throwable messages/stacks for coordinator, diagnostic-writer, permission, and notification failures.
- Change: preserve existing cancellation rethrows and worker outcome classification. Replace throwable overloads with controlled reason code plus `e::class.java.simpleName` only where class is needed. Use `WORKER_NOTIFICATION_PERMISSION_DENIED` for `SecurityException`, `WORKER_UNHANDLED_EXCEPTION` for generic worker/notification failures, and `SIDE_EFFECT_EXCEPTION` for diagnostic-writer failures. Do not include reminder IDs in free text beyond existing bounded numeric metadata; never log notification body/merchant/amount/currency.
- Acceptance criteria: cancellation still propagates; permission failure remains the existing `permission_denied` branch; generic failures retain retry/failure behavior while logs contain no throwable object/message/stack.

### WI-2 — Map budget forecast failures to controlled UI reasons (CA-P-06-003, P2, unverified)
- Location: `app/src/main/java/com/yourname/expensetracker/domain/budget/BudgetForecastingEngine.kt` — `generateForecastResult(...)` ≈L93-149; `app/src/main/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModel.kt` — `generateForecast(...)` ≈L62-123 @pin.
- Defect: `Unavailable.reason` interpolates `resolution.reason`/`outcome.message`, and the ViewModel catch renders `e.message` directly.
- Change: keep the typed `BudgetForecastResult.Unavailable.reasonCode` values (`HOME_CURRENCY_UNAVAILABLE`, `LIMIT_CONVERSION_FAILED`, and existing missing-rate/unavailable code) as the only UI error source. Set `reason` to a stable user-facing string selected from that controlled code, never the underlying message. In the ViewModel generic catch, map to a fixed `FORECAST_UNAVAILABLE`/existing unavailable code and class-only diagnostic; preserve cancellation rethrow and stale-request suppression.
- Acceptance criteria: home-currency failure, limit conversion failure, unavailable spend aggregate, and unexpected exception all produce bounded UI text with no exception detail; cancellation is not converted to UI error; existing Available flow is unchanged.

### WI-3 — Remove synthesis throwable logging (CA-P-06-004, P2, verified)
- Location: `app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt` — internal `synthesize(...)` ≈L170-208 @pin.
- Defect: fallback catches non-cancellation exceptions and calls `Timber.e(e, ...)`.
- Change: retain the cancellation rethrow and fallback result contract, but log only controlled `UNKNOWN_ERROR` (or the existing synthesis-specific reason if present) plus exception class name through the shared diagnostics policy. Do not pass `e` to Timber and do not add exception text to the fallback model.
- Acceptance criteria: non-cancellation failure still returns the existing degraded fallback; captured log arguments contain no throwable/message/stack; cancellation propagates.

### WI-4 — Sanitize reset journal and importer error summaries (CA-P-07-006, P0, unverified)
- Location: `app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt` — `resetDatabase()` ≈L2718-2820; `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournal.kt` — `failJournal(...)` ≈L624-631; `app/src/main/java/com/yourname/expensetracker/data/backup/RestoreJournalImporter.kt` — `importLastFailureJournalIfPresent()` ≈L151-210 @pin.
- Defect: reset passes `e.message` into the failure journal and importer copies it verbatim to `OperationRun.errorSummary`.
- Change: at the reset catch site derive a controlled failure code (`UNKNOWN_ERROR`, `WRITE_BARRIER_DENIED`, `RESTORE_BLOCKED`, or `TIMEOUT` when the typed cause establishes it) and exception class name. Persist only the bounded summary through `EventMetadataSanitizer.sanitizeExceptionMessage()` if a short human-readable detail is genuinely required; prefer a code-only summary. Make `failJournal` accept/use the bounded representation and ensure importer writes the already-bounded value, never the original throwable message. Preserve cancellation rethrow and journal durability behavior.
- Acceptance criteria: a message containing SQL, file paths, URI, or financial payload is absent from failure JSON and `OperationRun.errorSummary`; importer remains idempotent; cancellation is not persisted as failure.

### WI-5 — Replace restore/recovery throwable logs (CA-P-07-008, P2, verified)
- Location: `RestoreJournal.readJournal()`/`writeJournal()` ≈L482-530, `DatabaseBackupRepositoryImpl` restore/import/stats/reset catches ≈L1272, L2004, L2643, L2800, and `AppStartupCoordinator.checkRestoreJournal` recovery catches ≈L222-252 @pin.
- Defect: `Timber.*(e, ...)` exposes raw messages and stack traces.
- Change: remove throwable overloads. Emit a controlled per-site code: `RESTORE_BLOCKED` for barrier/maintenance denial, `WRITE_BARRIER_DENIED` for write admission, `READ_BARRIER_DENIED` for blocked reads, `PARSER_FAILED` for journal parse, `UNKNOWN_ERROR` for other restore/recovery failures, and `TIMEOUT` for typed timeout. Include exception class name only as a bounded field. Do not use `EventMetadataSanitizer` as a reason-code generator; sanitize only bounded optional text.
- Acceptance criteria: no restore/recovery catch passes a throwable to Timber; journal and startup recovery continue their existing state transitions; no path logs database paths, URIs, SQL, or user data.

### WI-6 — Bound cloud provider failure models and logs (CA-P08-003, P1, unverified)
- Location: `CloudDashboardBriefingService` failure catches ≈L240-270, `CloudReceiptAssistService` failure catches ≈L225-250 and ≈L390-400, and `CloudQueryInterpretationService.interpret(...)` ≈L77-180 @pin.
- Defect: providers pass throwables to Timber and return `e.message` in `AiServiceError.ParseError`, `Unknown`, or `unsupported(...)`; those values can persist in `ai_artifacts`.
- Change: preserve `CancellationException` rethrow wherever present. Map failures to the existing typed error variants without message payloads: `Timeout`, `SslError`, `Offline`, `ParseError` with no detail, or `Unknown` with a controlled `CLOUD_PROVIDER_FAILURE`/`UNKNOWN_ERROR` code. Log only provider stage, controlled code, attempt count, and exception class name; no throwable overload. For query interpretation, replace `unsupported("Network error: ...")` and parse-detail strings with fixed user-safe text/code. Keep `CloudPayloadPolicy` ownership unchanged.
- Acceptance criteria: provider result/error fields contain no raw exception text; persisted AI artifact error messages are controlled; retry counts and cancellation behavior remain unchanged; no provider logs stack traces.

### WI-7 — Remove financial payloads from shared FX logs (CA-E-04-001, P0, unverified)
- Location: `app/src/main/java/com/yourname/expensetracker/domain/groups/SharedExpenseBudgetOffsetEngine.kt` — `calculateEffectiveBudgetSpend(...)` ≈L72-190 @pin.
- Defect: failed personal/shared/reimbursed conversions log merchant, amount, source/target currency, and date, and pass exceptions to Android `Log`.
- Change: delete payload-bearing message construction and all corresponding `Log.w` calls. Keep `failedConversionCount` and bounded `conversionWarnings` only if the returned model requires them, using controlled `MISSING_RATE`/`STALE_RATE` codes without merchant, amount, currency, or date. If a diagnostic is required, write one structured event per aggregate with `MISSING_RATE`/`STALE_RATE`, count, and `partial=true`; never gate on build type as a substitute for deletion.
- Acceptance criteria: failed conversions still mark partial and count failures; no log or warning contains merchant, amount, currency, or date; no throwable reaches Android logging.

### WI-8 — Remove raw financial amounts from converter logs (CA-E-01-006, P2, verified)
- Location: `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt` — `convertMultiple(...)` ≈L468-510 @pin.
- Defect: each failed conversion logs exact amount and source/target currencies plus outcome message.
- Change: replace the per-item Timber call with bounded aggregate diagnostics: failure count (or one event after the loop), controlled `MISSING_RATE` or `STALE_RATE`, and exception/failure class only if available. Do not include amount, source currency, target currency, or `outcome.message`; preserve `failedConversions` contents for the typed return contract if callers need them, but do not log those payload fields.
- Acceptance criteria: conversion totals and failure list behavior remain unchanged; logs have count/code only and never exact financial values or raw outcome text.

### WI-9 — Bound analytics engine exception logs (CA-E-02-006, P2, verified)
- Location: `TotalsAggregationEngine.getAverageForPeriodType(...)` ≈L384-455 and `reactiveFlow`/`reactiveCategoryBreakdownFlow` ≈L664-705; `InsightsEngine.generateInsightsForPeriods(...)` ≈L283-335; `SynthesisEngine` is covered by WI-3 @pin.
- Defect: catches pass full throwables to Timber, exposing stack/message in debug logcat.
- Change: retain cancellation rethrows and fallback values, but log controlled `UNKNOWN_ERROR` (or `MISSING_RATE` when the typed failure identifies it), operation/stage, and exception class name only. Use `EventMetadataSanitizer` only for bounded optional text; do not pass `e` to Timber and do not change analytics legal paths.
- Acceptance criteria: all named catch sites preserve their current fallback/Flow behavior, cancellation propagates, and no throwable/message/stack reaches Timber.

### WI-10 — Replace raw ViewModel error state and receipt debug text (CA-I-05-002, P2, verified)
- Location: `app/src/main/java/com/yourname/expensetracker/ui/screens/transactions/TransactionsViewModel.kt` — delete/load/update catch sites ≈L469-717; `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt` — scan catch ≈L465-510 and atomic save failure ≈L1255-1295 @pin.
- Defect: UI state and retained receipt parsing logs interpolate `e.message`.
- Change: preserve cancellation rethrows. Map transaction failures to fixed user-safe strings keyed by operation (`TRANSACTION_LOAD_FAILED`, `TRANSACTION_DELETE_FAILED`, `TRANSACTION_UPDATE_FAILED`) and emit a controlled diagnostic with reason code plus exception class. For receipt scan, keep `rawOcrText` empty on failure and replace parsing/debug/save error strings with fixed `OCR_FAILED`, `PARSER_FAILED`, `SOURCE_LINK_FAILED`, or `UNKNOWN_ERROR` text; duplicate detection must use typed/domain status, not substring matching on `e.message`. Never include OCR, receipt, merchant, amount, path, SQL, or provider text.
- Acceptance criteria: UI never displays exception text; receipt `parsingLogs`, `debugData`, and `SaveReceiptResult.Error` contain controlled text only; cancellation remains cancellation; duplicate behavior remains correct through a typed signal.

### WI-11 — Delete dead unsafe expense use cases (CA-I-05-004, P3, verified)
- Location: `app/src/main/java/com/yourname/expensetracker/domain/usecase/expense/ExpenseUseCases.kt` — `GetExpensesBetweenDatesUseCase`, `GetExpenseStatisticsUseCase`, and `ReviewExpenseUseCase` ≈L25-104 @pin.
- Defect: repository-wide production search found no callers/bindings, while the example APIs preserve raw `e.message` and an unsafe unnormalized amount fallback.
- Change: delete the three unused classes and their now-unused imports/file if no current-HEAD caller appears in the mandatory pre-check. Do not replace them with a new facade or wire them into ViewModels; expense mutations remain on the legal transaction lifecycle path. If a caller has appeared since the pin, stop and obtain architecture review rather than reviving the unsafe contracts.
- Acceptance criteria: no production reference, Hilt binding, or test compile dependency remains; no new use case API is introduced; lifecycle guard tests remain unchanged.

## Tests
- WI-1: `app/src/test/java/com/yourname/expensetracker/service/reminder/BillReminderWorkerTest.kt` plus a log-sink/architecture assertion: diagnostic-writer exception, permission denial, notification exception, outer coordinator exception; cancellation propagation; no throwable logging.
- WI-2: `app/src/test/java/com/yourname/expensetracker/ui/screens/budget/BudgetForecastingViewModelTest.kt` and focused engine tests: home-currency failure, limit conversion failure, unavailable aggregate, generic exception, cancellation, stale request; assert fixed reason text and no raw message.
- WI-3: focused `SynthesisEngine` test: non-cancellation fallback and cancellation; capture Timber arguments and assert no throwable/message.
- WI-4/WI-5: `RestoreJournalImporterFailureTest`, `AppStartupCoordinatorRecoveryTest`, and a restore journal diagnostic test: SQL/path/URI/financial exception message is absent from JSON and ledger; malformed journal uses controlled code; importer remains idempotent; cancellation propagates.
- WI-6: `CloudDashboardBriefingServiceTest`, `CloudReceiptAssistServiceTest`, `CloudQueryInterpretationServiceTest`: timeout/SSL/IO/JSON/generic failures, retry exhaustion, cancellation, persisted result/error fields contain codes only, no throwable log calls.
- WI-7: `SharedExpenseBudgetOffsetEngineTest`: personal/shared/reimbursed conversion failures retain counts/partial state, warning metadata has code/count only, no merchant/amount/currency/date.
- WI-8: `CurrencyConverterTest` or existing conversion aggregate tests: mixed success/failure totals and failure list unchanged; captured log has count/code only.
- WI-9: targeted analytics tests for average/Flow/category branch failures and Insights branch failures: fallback behavior unchanged, cancellation rethrows, logs contain class/code only.
- WI-10: `TransactionsViewModelTest` and `ReceiptScanViewModelTest`: each listed operation failure, OCR/parser failure, save failure, duplicate typed failure, cancellation; assert no `e.message` in UI/debug state.
- WI-11: compile/reference guard or existing architecture test proving the three classes have no production references after deletion.

## Validation
- Use `validation-runner` profile `targeted-unit-test` first, in serialized shards covering the named tests.
- Then use `validation-runner` profile `compile`.
- The coder must not invoke Gradle directly.

## Blast-radius fence
- ALLOWED: the exact production files named in WI-1 through WI-11, `EventMetadataSanitizer.kt` only for a narrowly shared helper if existing API cannot express bounded text, and the named focused tests.
- FORBIDDEN: `CompositePrivacyGate.kt`/export FailClosed behavior (CL-18); restore state-machine/barrier durability and bank caller (CL-19); cloud payload policy routing (CL-21); group split/member validation and repayment signs (CL-29); transaction/receipt lifecycle mutation paths; schema, migration, DAO, or unrelated ViewModel cleanup.

## Review gate
- `privacy-security-guardian`: inspect every listed catch/log/result/UI site, verify controlled codes and class-only logging, deletion of CA-E-04-001 payload fields, no raw AI/receipt/financial payload retention, and no sanitizer bypass.
- `reviewer-strict`: verify WI-11 deletion has no production callers, cancellation behavior is preserved, and worker/cloud/restore legal paths remain intact.

## Privacy constraints
- Controlled reason-code set per site:
  - BillReminderWorker: `WORKER_NOTIFICATION_PERMISSION_DENIED`, `WORKER_UNHANDLED_EXCEPTION`, `SIDE_EFFECT_EXCEPTION`.
  - Budget forecast engine/ViewModel: `HOME_CURRENCY_UNAVAILABLE`, `LIMIT_CONVERSION_FAILED`, existing typed missing-rate/unavailable code, or `UNKNOWN_ERROR` for unexpected failure.
  - Synthesis/analytics: `UNKNOWN_ERROR` or typed `MISSING_RATE`/`STALE_RATE` only.
  - Restore/reset journal/import/startup: `RESTORE_BLOCKED`, `WRITE_BARRIER_DENIED`, `READ_BARRIER_DENIED`, `PARSER_FAILED`, `TIMEOUT`, or `UNKNOWN_ERROR`.
  - Cloud providers: `NETWORK_UNAVAILABLE`, `TIMEOUT`, `PARSER_FAILED`, `PROVIDER_DISABLED`, or `UNKNOWN_ERROR`; no provider exception text.
  - Shared FX and converter: `MISSING_RATE` or `STALE_RATE`, plus bounded failure count.
  - Transactions/receipt UI: `TRANSACTION_LOAD_FAILED`, `TRANSACTION_DELETE_FAILED`, `TRANSACTION_UPDATE_FAILED`, `OCR_FAILED`, `PARSER_FAILED`, `SOURCE_LINK_FAILED`, or `UNKNOWN_ERROR` (use existing enum/constants where present; do not invent free-text reason values in persisted diagnostics).
- Never log/persist raw exception messages, stack traces, SQL, file paths, URIs, OCR/receipt text, notification bodies, merchant names, amounts, currencies, dates, cloud prompts/responses, or financial payloads.
- `EventMetadataSanitizer.sanitizeExceptionMessage()` is fallback for bounded needed text only. Deletion beats redaction, especially CA-E-04-001 and unused use-case contracts.

## Out of scope
- CA-P-07-001 and CA-P08-001 privacy gate semantics: CL-18.
- CA-P-07-003/004/005 restore durability/state machine and CA-P-10-001 bank persist barrier: CL-19.
- CA-P08-002 cloud policy routing: CL-21.
- CA-E-04-002/003 group active-member filtering, unequal mismatch rejection, and repayment signs: CL-29.
- Any new finding, broad UI-wide refactor, or mutation-path change.
