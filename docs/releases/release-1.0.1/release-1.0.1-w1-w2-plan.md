# Release 1.0.1 — wave W1 and W2 plan

```text
Source: release-planner (read-only), 2026-10-19, on e2e134fc. Lead decisions added below.
Status: PLAN. Nothing in it is approved for coding until B0 exists
  (test-suite merge into bug-fixes, gated).
Test paths are "to confirm after the test-suite merge": that branch rewrites ~345 test files.
Production paths are relative to app/src/main/java/com/yourname/expensetracker/.
```

## Overlap with the test-suite branch (snapshot; its work is uncommitted)

The test-suite branch changes 10 production files plus `scripts/validation-runner.ps1`:

- CompositeOperationRunRecorder
- NotificationCaptureService
- DashboardBriefingResponseParser
- CloudRetryPolicy
- six `Cloud*Service` files

Two of them overlap the waves below:

- `NotificationCaptureService.kt` overlaps W1-3 (PRIV-14).
- `CompositeOperationRunRecorder.kt` is DIAG-11, which is fixed on that branch.

**B0 must contain B1R-4**, which shrinks the CompositeOperationRunRecorder entries in
`config/baselines/cancellation.json`. Without it, static-guards fails in every wave.

## W1 — quick wins

Write sets are disjoint from each other and from W2.

| Slice | Item | Production write set | Test intent | Review |
| --- | --- | --- | --- | --- |
| W1-1 | MONEY-01 Greek receipt VAT | `domain/receipt/ReceiptParser.kt`. Only the first `extractTax` pattern (~:771): allow an optional `NN[,.]NN%` rate before the amount; never capture a number followed by `%`. | Flip the KNOWN DEFECT pin (expect 9.68). Add rows: `ΦΠΑ: 4,14`, `ΦΠΑ 24%: 4,14`, `VAT / ΦΠΑ: €2.76`, OCR `0.n.A 24,00% 9,68`. A rate-only line gives null. | strict |
| W1-2 | MONEY-05 + MONEY-07 | `domain/currency/CurrencyConverter.kt`: use the absolute rate age at :184/:216 (as `convertOutcome` :448-460 does); map failure types at :566-570. `domain/core/money/MoneyMappers.kt` :60-63/:93-96. TO-CONFIRM: `FailureReason` members. | A future-dated rate is stale. An invalid currency is invalid, not missing. Valid totals are unchanged. | strict |
| W1-3 | PRIV-14 (+ PRIV-07 ride-along) | `service/NotificationCaptureService.kt` only, written against the B0 version. Cancellation branch: call `deduper.remove(dedupeKey)` before rethrow. PRIV-07: literal in place of `e.message` in the extras JSON. | Cancellation releases the key; one CANCELLED event; the CE still propagates. | strict + privacy guardian |
| W1-4 | DIAG-07 | `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` :337-343. Check "Unsupported MIME" before the generic MIME match. | "Unsupported MIME" maps to MIME_UNSUPPORTED. | in the W1 strict diff |
| W1-5 | F-R24b-a | `ui/permissions/NotificationPromptPolicy.kt` (KDoc) | One policy row pinning the dismiss-without-choice case. | cheap |
| W1-6 | F-R32-a | test-only, after B0 | Check whether B0 already covers mode-only RESTART_REQUIRED/CRITICAL startup. If yes, close it; if not, add the test. | strict |
| W1-7 | TB-2 | `config/validation/known-hanging-tests.json` | Renew HANG-001..003 `review_by` (owner Q1). Resolving them would change runner behaviour (legacy filter becomes empty). | cheap |

## W2 — cancellation and guard hygiene

W2-4 goes last, because it alone edits the guard file.

| Slice | Item | Write set | Notes |
| --- | --- | --- | --- |
| W2-1 | DIAG-04 | `data/backup/RestoreDiagnosticsSink.kt` (:104/:124 rethrow CE) | See note W2-1. |
| W2-2 | MONEY-04 CE catch | `domain/tax/TaxEstimator.kt` :127-132, plus shrinking the TaxEstimator counts in `config/baselines/cancellation.json` | The unit bug (percent vs fraction) waits for tax enablement. |
| W2-3 | Small runCatching fixes | `SubscriptionManagerEngine` (1), `NetCashflowBalanceProvider` (2), optionally `DefaultCloudPayloadPolicy` (1). Paths TO-CONFIRM. | One cancellation test per file. |
| W2-4 | MISC-01 | `app/src/test/.../architecture/CancellationSafetyArchitectureGuardTest.kt`. See note W2-4. | strict + architecture guardian |
| W2-5 | HZ-08 F2 | **Deferred to the TB-3 owner review** (lead decision) | — |

**Note W2-1.** Before coding, a read-only debugger checks the 11 `finalizeRunFailed` callers in
`DatabaseBackupRepositoryImpl` (:1080-1485). If a required step (maintenance exit, journal failure,
cleanup) runs after a sink call, stop: fixing it would mean editing the restore lifecycle.

**Note W2-4.** The guard changes in four steps:

- (a) The expiry tests use the real date. The fixed dates stay only for the negative controls.
- (b) Remove the whole-file CompositeOperationRunRecorder entry (needs DIAG-11 in B0). A narrow
  replacement entry may be needed (owner Q8).
- (c) Expired entries:
  - Delete as stale: ValidateBankStatementTransactionsUseCase, AdvancedAnalyticsEngine,
    FinancialStressForecastEngine.
  - Delete after W2-3: SubscriptionManagerEngine, NetCashflowBalanceProvider and
    DefaultCloudPayloadPolicy (if fixed).
  - Delete after W2-1: the RestoreDiagnosticsSink entry.
  - Renew only with owner approval (Q2): RestoreJournalImporter, AnalyticsRepository,
    CarbonFootprintCalculator, InvestmentTracker, DiagnosticsRepository, DatabaseBackupRepositoryImpl.
- (d) Side effect: with the real clock, about 100 entries expire on 2027-01-01, so the TB-3 review
  must happen before then.

Moved out of W1/W2:

- PRIV-02 and HZ-08 F1 go to W3, alongside RET-01/LOG-02/DIAG-10.
- F-R20-a is already fixed in 1.0 (`FinancialStressForecastEngine` uses `runCatchingCancellable`).
  Close it after confirming.

## Validation per wave

The runner takes **one filter pattern per `targeted-unit-test`** (wildcards OK). The first run
compiles; follow-up runs reuse the build (about 15–20 s each). Each wave runs:

1. static-guards
2. a targeted run per package
3. one app-check

Do not use `data.backup.*`, because it includes HANG-003 (ExportReadBarrierTest).

**W1 filters:**

- `domain.receipt.ReceiptParser*`
- `domain.currency.*`
- MoneyMappers/money consumers
- `service.NotificationCaptureService*`
- `domain.notification.capture.*`
- `domain.receipt.lifecycle.ReceiptLifecycleCoordinator*`
- `ui.permissions.*`
- `architecture.CancellationSafetyArchitectureGuardTest`
- `contracts.CancellationPropagationContractTest`
- `contracts.PrivacyStorageContractTest`

**W2 filters:**

- the cancellation guard
- CancellationPropagationContractTest
- CancellationSafeTest
- `data.backup.RestoreDiagnosticsSink*`
- `data.backup.RestoreMaintenanceMode*`
- `data.repository.DatabaseBackupRepositoryImpl*`
- `domain.tax.*`
- `domain.diagnostics.*`
- the W2-3 test classes

## Decisions

Lead decisions (standing approval):

- **Q5:** no backfill of the tax amounts on receipts already saved.
- **Q7:** PRIV-07 rides along with PRIV-14.
- **Q9:** tax units wait for tax enablement.
- **Q4:** HZ-08 F2 is deferred to TB-3.

Owner questions:

- **Q1:** renew the HANG-001..003 review date (recommended: 2027-03-01, citing the B0 legacy run plus
  9+ clean runs).
- **Q2:** renew the six live expired cancellation-guard entries, each with a date and an owner.
- **Q3:** hold the TB-3 guard review session before 2026-12-31.
- **Q6 (W3):** the PRIV-02 window rule. Recommended: the shorter of the user window and 30 days,
  without blanking the text a pending review needs.
- **Q8:** a narrow replacement entry for CompositeOperationRunRecorder, if needed (net narrowing).
