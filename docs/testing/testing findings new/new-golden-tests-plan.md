# Cost-agregator — New Golden Tests and Deep Coverage Plan

Target reviewed: GitHub commit `18482021294eba1d209afa2deb34aea6c107a52f`  
Caveat: your local branch may be ahead of GitHub, especially after your test overhaul.

## 1. Main recommendation

Do not create 100 random new tests.

Create **10–20 canonical golden tests** that prove the real application contracts:

```text
realistic input
→ legal production path
→ durable DB/event/diagnostic state
→ dashboard/analytics/budget/export/UI output
→ exact expected JSON
```

These tests should become your **release gate**.

Right now, the repo has many “golden-like” tests, but the visible `app/src/test/resources` only contains:

```text
OCR_TEST_DOCUMENT.txt
robolectric.properties
```

So the current golden layer is mostly Kotlin constants / scenario assertions, not true external expected-output golden files.

Fix that first.

---

# 2. Golden infrastructure to build first

Before adding new goldens, fix the infrastructure.

## 2.1 Strict golden verifier

Current problem: a missing golden file can pass.

Required policy:

```text
default CI:
  missing golden file = fail
  mismatch = fail

explicit update mode only:
  -PupdateGoldens=true
```

Required verifier features:

```text
JSON object/array comparison
numeric tolerance for money/forecast percentiles
ignored unstable fields
stable sorting for arrays where order is not business-critical
pretty diff output
update mode
clear expected resource path in failure message
```

## 2.2 Folder structure

Use:

```text
app/src/test/resources/golden/
  multicurrency_analytics_dashboard_budget/
    seed.json
    input.json
    expected-dashboard.json
    expected-analytics.json
    expected-budget.json
    expected-export.json
    expected-diagnostics.json

  notification_review_dashboard_budget/
    seed.json
    input.json
    expected-review.json
    expected-events.json
    expected-dashboard.json
    expected-budget.json
    expected-analytics.json

  receipt_matching_analytics/
    seed.json
    input.json
    expected-receipts.json
    expected-links.json
    expected-analytics.json
```

## 2.3 Scenario runner

You need a runner that separates:

```text
seedState = prepare background world
feedInputs = run real app inputs through production entry points
```

`feedInputs()` must not call DAOs directly.

Good:

```text
NotificationProcessingPipeline
ReviewQueueRepository
TransactionLifecycleCoordinator
ReceiptLifecycleCoordinator
RecurringLifecycleCoordinator
DatabaseBackupRepository
WorkerExecutionGuard
ImportCoordinator
```

Bad inside golden tests:

```text
expenseDao.insert(...)
scannedReceiptDao.insert(...)
recurringOccurrenceDao.insert(...)
```

Direct DAO insertion is fine only for background seed data.

---

# 3. Release-blocking golden tests to create

These are the most important ones.

---

## 1. `MulticurrencyAnalyticsDashboardBudgetGoldenTest`

## Priority

P0 — highest.

## Why

Your engine map says `CurrencyConverter` and `MoneyAggregate` affect dashboard, budget, forecast, export, analytics, groups, investment, and tax. This is foundational.

## Covers

```text
Currency & Exchange
MoneyAggregate
Dashboard totals
Analytics
Budget
Forecast confidence
Export
```

## Seed

```text
home currency = EUR

expenses:
1. 100 EUR groceries
2. 50 USD shopping, historical rate USD→EUR = 0.90
3. 40 GBP travel, historical rate GBP→EUR = 1.15
4. 20 CHF dining, missing rate
5. 30 USD old expense with stale rate
```

## Expected

```json
{
  "homeCurrency": "EUR",
  "sourceBuckets": {
    "EUR": "100.00",
    "USD": "80.00",
    "GBP": "40.00",
    "CHF": "20.00"
  },
  "displayTotal": "191.00",
  "isPartial": true,
  "conversionFailures": [
    { "currency": "CHF", "reason": "MISSING_RATE" },
    { "currency": "USD", "reason": "RATE_STALE" }
  ],
  "dashboardWarnings": ["PARTIAL_CONVERSION"],
  "analyticsWarnings": ["PARTIAL_CONVERSION"],
  "budgetUsesOnlySafeConvertedValues": true
}
```

## Must fail if

```text
dashboard raw-sums EUR + USD + GBP
partial flag is dropped
warning disappears in ViewModel/UI state
analytics and dashboard disagree
stale rate is treated as clean
```

---

## 2. `NotificationReviewDashboardBudgetGoldenTest`

## Priority

P0.

## Why

This is one of the most important real app flows.

## Covers

```text
notification capture
parser registry
Greek/Revolut parser
deduplication
review queue
merchant categorization
transaction lifecycle
dashboard
budget
analytics
diagnostics
```

## Input

```text
1 Greek bank notification: 45.50 EUR SKLAVENITIS
1 Revolut notification: 30.00 EUR Amazon
1 duplicate Greek notification
1 low-confidence notification requiring review
monthly groceries budget = 100 EUR
merchant rules:
  SKLAVENITIS -> Groceries
  Amazon -> Shopping
```

## Production path

```text
Raw notification
→ NotificationProcessingPipeline
→ parser registry
→ ConfidenceRouter
→ auto-accept or ReviewQueue
→ review approval
→ TransactionLifecycleCoordinator
→ TransactionEvent
→ DashboardRepository
→ AnalyticsRepository
→ BudgetMonitor
→ PipelineDiagnosticEvent
```

## Expected

```json
{
  "rawNotifications": 4,
  "duplicatesSkipped": 1,
  "pendingReviewBeforeApproval": 1,
  "pendingReviewAfterApproval": 0,
  "expensesCreated": 3,
  "transactionEvents": [
    "CREATED",
    "CREATED",
    "CREATE_DUPLICATE_SKIPPED",
    "CREATED_FROM_REVIEW"
  ],
  "dashboardMonthlyTotal": "145.50",
  "analyticsCategoryTotals": {
    "Groceries": "95.50",
    "Shopping": "50.00"
  },
  "budget": {
    "Groceries": {
      "limit": "100.00",
      "spent": "95.50",
      "remaining": "4.50",
      "severity": "WARNING"
    }
  },
  "pipelineDiagnosticsWritten": true
}
```

## Must fail if

```text
review approval bypasses TransactionLifecycleCoordinator
duplicate creates expense
category total differs from dashboard
budget uses wrong category amount
no durable diagnostic event is written
```

---

## 3. `AnalyticsDashboardBudgetParityGoldenTest`

## Priority

P0.

## Why

Your app has many engines that can produce different numbers. This test proves they agree when they should.

## Covers

```text
NormalizedAnalyticsInput
DashboardRepository
TotalsAggregationEngine
AdvancedAnalyticsEngine
BudgetVsActualEngine
ForecastInputAssembler
Export mapping
```

## Seed

Use one deterministic month with:

```text
normal purchases
refund
transfer
shared expense
receipt-linked transaction
recurring actual
multi-currency converted transaction
one missing-rate transaction
```

## Expected

```json
{
  "period": "2026-05",
  "dashboardTotal": "1030.00",
  "analyticsTotal": "1030.00",
  "budgetSpent": "1030.00",
  "categoryTotalsMatch": true,
  "partial": true,
  "warningTypes": ["PARTIAL_CONVERSION"],
  "expectedDivergences": [
    {
      "field": "groupGrossVsBudgetNet",
      "reason": "budget uses current-user effective share"
    }
  ]
}
```

## Rule

If dashboard and analytics differ, the golden must document **why**.

No undocumented divergence should pass.

---

## 4. `TransactionLifecycleFullContractGoldenTest`

## Priority

P0.

## Why

Legal paths say all expense create/update/delete must go through `TransactionLifecycleCoordinator`.

## Input

```text
manual expense create
duplicate create attempt
update amount/category/merchant
delete expense
```

## Expected

```json
{
  "results": [
    "Created",
    "DuplicateSkipped",
    "Updated",
    "Deleted"
  ],
  "eventSequence": [
    "CREATED",
    "CREATE_DUPLICATE_SKIPPED",
    "UPDATED",
    "DELETED"
  ],
  "finalActiveExpenseCount": 0,
  "dashboardTotalAfterCreate": "50.00",
  "dashboardTotalAfterUpdate": "75.00",
  "dashboardTotalAfterDelete": "0.00",
  "budgetRecalculated": true,
  "analyticsRecalculated": true
}
```

## Must fail if

```text
ExpenseDao.insert/update/delete is used directly
event log is missing
dashboard/budget/analytics are stale
delete loads snapshot outside transaction
```

---

## 5. `ReceiptMatchingAnalyticsNoDoubleCountGoldenTest`

## Priority

P0.

## Why

Receipt matching is a classic double-count risk.

## Input

```text
existing bank transaction:
  Public, 89.99 EUR, Shopping

receipt OCR/email:
  Public
  89.99 EUR
  same date
  line items
```

## Production path

```text
ReceiptLifecycleCoordinator
→ ReceiptRepository OCR/parser draft
→ ReceiptDuplicateDetector
→ ReceiptEvent
→ ReceiptTransactionMatcher
→ ReceiptLinkService
→ ReceiptExpenseLink
→ item categorization
→ analytics
```

## Expected

```json
{
  "receiptStatusSequence": [
    "CAPTURED",
    "OCR_COMPLETE",
    "PARSED",
    "MATCHED"
  ],
  "receiptEvents": [
    "CAPTURED",
    "PARSED",
    "MATCHED"
  ],
  "receiptExpenseLinks": 1,
  "newExpensesCreated": 0,
  "analyticsTotal": "89.99",
  "countedOnce": true,
  "warrantySideEffectRun": true,
  "priceProtectionSideEffectRun": true
}
```

## Must fail if

```text
receipt creates duplicate expense
analytics counts receipt and bank transaction separately
receipt link is missing
receipt mutation happens without ReceiptEvent
```

---

## 6. `RecurringPlannedActualNoDoubleCountGoldenTest`

## Priority

P0/P1.

## Input

```text
Netflix recurring rule:
  12.99 EUR monthly

May planned occurrence
May actual payment
June/July future occurrences
reminder worker run twice
```

## Expected

```json
{
  "occurrences": {
    "2026-05": "MATCHED",
    "2026-06": "PLANNED",
    "2026-07": "PLANNED"
  },
  "reminderDeliveries": 1,
  "dashboardMayTotal": "12.99",
  "forecastFutureRecurringTotal": "25.98",
  "budgetMaySpent": "12.99",
  "doubleCounted": false,
  "workerIdempotent": true
}
```

## Must fail if

```text
planned + actual are counted together
reminder worker creates duplicates
actual is not linked to occurrence
forecast loses future planned items
```

---

## 7. `GroupSettlementBudgetOffsetGoldenTest`

## Priority

P1.

## Why

Shared expenses have dangerous numeric semantics: gross, net, reimbursement, budget offset.

## Input

```text
Alice pays 90 EUR dinner
Bob owes 30
Carol owes 30
Bob reimburses Alice 30
Dining budget = 200 EUR
```

## Expected

```json
{
  "grossDiningSpend": "90.00",
  "currentUserBudgetEffectiveSpend": "30.00",
  "groupBalances": {
    "Alice": "30.00",
    "Bob": "0.00",
    "Carol": "-30.00"
  },
  "settlementSuggestions": [
    {
      "from": "Carol",
      "to": "Alice",
      "amount": "30.00",
      "currency": "EUR"
    }
  ],
  "events": [
    "GROUP_EXPENSE_ADDED",
    "SETTLEMENT_RECORDED"
  ],
  "analyticsNotCorruptedByReimbursement": true
}
```

## Must fail if

```text
reimbursement is counted as ordinary income incorrectly
budget uses gross when contract says current-user net
group balance is wrong
foreign-currency group expense is allowed
```

---

## 8. `BackupRestoreFullAppRoundtripGoldenTest`

## Priority

P0 for release.

## Why

Backup/restore protects user trust.

## Seed DB

```text
expenses
transaction events
receipts
receipt links
recurring occurrences
group settlements
exchange rates
privacy audit events
background job runs
AI artifacts
```

## Production path

```text
enter BACKUP_EXPORTING mode
backup
enter RESTORE_PREPARING
restore into fresh DB
verify
resume
```

## Expected

```json
{
  "before": {
    "dashboardMonthlyTotal": "1234.56",
    "receiptLinks": 3,
    "groupSettlements": 2,
    "privacyAuditEvents": 4
  },
  "after": {
    "dashboardMonthlyTotal": "1234.56",
    "receiptLinks": 3,
    "groupSettlements": 2,
    "privacyAuditEvents": 4
  },
  "restoreJournal": [
    "STARTED",
    "WORKERS_PAUSED",
    "DB_RESTORED",
    "VERIFIED",
    "WORKERS_RESUMED"
  ],
  "workersPausedDuringRestore": true
}
```

## Must fail if

```text
restored dashboard differs
receipt links are lost
exchange rates are lost
workers write during restore
failed restore exits to NORMAL
```

---

## 9. `PrivacyAiRedactionGateGoldenTest`

## Priority

P0/P1.

## Input

```text
cloud AI disabled
external geocoding disabled
notification capture disabled
backup raw export disabled

sensitive notification/receipt/query text
```

## Expected

```json
{
  "cloudProviderCalls": 0,
  "locationProviderCalls": 0,
  "notificationWrites": 0,
  "auditEvents": [
    "CLOUD_AI_DENIED",
    "LOCATION_DENIED",
    "NOTIFICATION_CAPTURE_DENIED"
  ],
  "rawSensitiveTextStored": false,
  "redactedPayloadContainsSensitiveData": false,
  "userFacingState": "PRIVACY_DENIED"
}
```

## Must fail if

```text
cloud call happens without privacy gate
raw sensitive text is persisted against policy
audit event missing
UI drops denied state
```

---

## 10. `WorkerRestoreBarrierIdempotencyGoldenTest`

## Priority

P1.

## Input

```text
restore mode active
run all important workers:
  receipt matching
  bill reminder
  daily briefing
  warranty expiration
  location backfill
  data retention

then restore mode normal
run workers twice
```

## Expected

```json
{
  "restoreModeRuns": {
    "receiptMatching": "SKIPPED_RESTORE_MODE",
    "billReminder": "SKIPPED_RESTORE_MODE",
    "dailyBriefing": "SKIPPED_RESTORE_MODE"
  },
  "dbMutationsDuringRestore": 0,
  "duplicateReceiptLinks": 0,
  "duplicateReminderDeliveries": 0,
  "backgroundJobRunRowsWritten": true,
  "cancellationRethrown": true
}
```

---

## 11. `BankSyncFailureRecoveryGoldenTest`

## Priority

P1.

## Input

```text
bank connection active
sync 1: expired token
sync 2: partial success
  duplicate transaction
  low-confidence merchant
  valid transaction
review approval
```

## Expected

```json
{
  "authFailureState": "TOKEN_EXPIRED",
  "partialSyncDidNotCorruptDb": true,
  "duplicatesSkipped": 1,
  "reviewItemsCreated": 1,
  "approvedExpensesCreated": 1,
  "source": "BANK_API_SYNC",
  "dashboardIncludesOnlyApprovedNonDuplicates": true,
  "diagnosticEventsWritten": true
}
```

---

## 12. `EmailReceiptLifecycleWarrantyPriceGoldenTest`

## Priority

P1.

## Input

```text
Amazon/Apple/Uber email fixture
existing matching card transaction
warranty-eligible item
price-protection-eligible item
privacy raw storage mode = METADATA_ONLY or STORE_REDACTED
```

## Expected

```json
{
  "emailParsed": true,
  "rawEmailStored": false,
  "receiptCreated": true,
  "receiptLinkedToExistingExpense": true,
  "newExpensesCreated": 0,
  "warrantyCreated": true,
  "priceProtectionCreated": true,
  "analyticsCountedOnce": true
}
```

---

## 13. `CsvAccountingExportImportRoundtripGoldenTest`

## Priority

P1.

## Input

```text
multi-currency expenses
CSV dangerous cells: =, +, -, @
receipt links
tax/business fields
group/split expenses
refunds/transfers
```

## Expected

```json
{
  "csvFormulaInjectionNeutralized": true,
  "importIntoFreshDbSucceeded": true,
  "dashboardTotalMatchesOriginal": true,
  "categoryTotalsMatchOriginal": true,
  "businessTaxFieldsPreserved": true,
  "unsupportedFieldsReported": true,
  "privateRawTextLeaked": false
}
```

---

## 14. `ForecastSynthesisGoldenTest`

## Priority

P1.

## Covers

```text
SynthesisEngine
MonteCarloSpendingSimulator
recurring planned future costs
budget forecast
cashflow runway
forecast confidence
partial currency degradation
```

## Expected

```json
{
  "p10": "1781.63",
  "p50": "2072.41",
  "p90": "2484.39",
  "knownUpcoming": "300.00",
  "recurringFutureIncluded": true,
  "currentActualNotDoubleCounted": true,
  "confidence": "HIGH",
  "partialCurrency": false
}
```

---

## 15. `InvestmentPortfolioMulticurrencyGoldenTest`

## Priority

P2/P1 if investment feature is live.

## Input

```text
home EUR
Siemens: 5 × 190 EUR = 950 EUR
Tesla: 3 × 260 USD = 780 USD
USD→EUR = 0.92
crypto holding with missing price variant
```

## Expected

```json
{
  "sourceBuckets": {
    "EUR": "950.00",
    "USD": "780.00"
  },
  "displayCurrency": "EUR",
  "displayTotal": "1667.60",
  "isPartial": false,
  "missingPriceVariant": {
    "isPartial": true,
    "warnings": ["MISSING_PRICE"]
  }
}
```

---

## 16. `TaxBusinessMileageExportGoldenTest`

## Priority

P1/P2.

## Input

```text
business expenses:
  1200 EUR
  400 USD at 0.92 = 368 EUR
mileage:
  150 km × 0.30 = 45 EUR
VAT rate contract defined
```

## Expected

```json
{
  "filingCurrency": "EUR",
  "deductibleExpenses": "1613.00",
  "sourceBuckets": {
    "EUR": "1245.00",
    "USD": "400.00"
  },
  "isPartial": false,
  "mileageFallbackUsed": true,
  "exportContainsOriginalAndConvertedFields": true
}
```

---

## 17. `MerchantCategorizationDedupeGoldenTest`

## Priority

P1.

## Why

Engine map says merchant normalizer affects notification dedupe, transaction dedupe, receipt matching, recurring matching, email, and analytics.

## Input

```text
SKLAVENITIS
Sklavenitis
ΣΚΛΑΒΕΝΙΤΗΣ
Greeklish variant
same amount/time near duplicate
receipt merchant variant
recurring merchant variant
```

## Expected

```json
{
  "canonicalMerchant": "SKLAVENITIS",
  "dedupeSkipped": true,
  "receiptMatched": true,
  "recurringMatched": true,
  "analyticsMerchantGroup": "SKLAVENITIS",
  "aliasOccurrenceCountUpdated": true
}
```

---

## 18. `LocationMapPrivacyGoldenTest`

## Priority

P2.

## Input

```text
merchant location enrichment
ambiguous merchant
geocoding success/failure
privacy location export denied
```

## Expected

```json
{
  "mapAggregateTotal": "123.45",
  "currencyNormalized": true,
  "failedGeocodeNonFatal": true,
  "privateLocationExportRedacted": true,
  "aiPayloadDoesNotContainRawLocation": true
}
```

---

# 4. Areas of codebase that must be thoroughly checked

## 4.1 Legal production paths

This is non-negotiable.

Check that goldens use the legal paths from the architecture:

```text
expense CUD → TransactionLifecycleCoordinator
receipt processing/linking → ReceiptLifecycleCoordinator / ReceiptLinkService
recurring generation/linking → RecurringLifecycleCoordinator
cloud AI → PrivacyGate + redactor
backup/restore → maintenance mode + journal + barriers
workers → WorkerExecutionGuard
money totals → MoneyAggregate / MultiCurrencyRepository
```

Any golden that bypasses these with direct DAO writes is not a real golden.

---

## 4.2 Money/currency foundation

Deeply check:

```text
CurrencyConverter
MoneyAmount
MoneyAggregate
MoneyAggregateBuilder
AnalyticsCurrencyNormalizer
MultiCurrencyRepository
ExchangeRate validDate/staleness
DataQualityReport propagation
```

Must cover:

```text
missing rate
stale rate
historical transaction-date rate
source buckets
partial aggregate
warnings not dropped
no raw cross-currency sum
original + converted amount in export
```

---

## 4.3 Analytics/dashboard/budget parity

This is probably the most important cross-engine area.

Check:

```text
DashboardRepository
TotalsAggregationEngine
AdvancedAnalyticsEngine
DailyBucketEngine
BudgetVsActualEngine
BudgetMonitor
ForecastInputAssembler
ComputeDashboardWidgetsUseCase
HomeViewModel mapping
```

Must prove:

```text
same input gives same category totals where contract says so
differences are documented business divergences
partial currency warnings survive ViewModel mapping
daily/weekly/monthly buckets use same period boundaries
refunds/transfers/shared expenses have explicit inclusion rules
```

---

## 4.4 Transaction/receipt/recurring lifecycle

Deeply check:

```text
TransactionEvent
ReceiptEvent
ReceiptExpenseLink
RecurringOccurrence
RecurringReminderDelivery
RecurringLifecycleEvent
PipelineDiagnosticEvent
```

Every lifecycle golden should assert:

```text
final rows
event sequence
diagnostic events
side effects
no double count
idempotency
rollback on link failure
```

---

## 4.5 Persistence, migration, backup/restore

Because `AppDatabase` is version `124` and exposes many DAOs/entities, do not trust only compile success.

Check:

```text
schema v124 snapshots
migration-start versions
fresh install vs migrated schema parity
foreign keys
unique indexes
event tables
restore journal
write/read barriers
backup bundle verifier
```

Add a release test:

```text
MigrationFreshInstallParityV124GoldenOrContractTest
```

This can be a contract test rather than JSON golden, but it should be release-blocking.

---

## 4.6 Privacy/security

Deeply check:

```text
CloudAiPrivacyGate
LocationPrivacyGate
NotificationPrivacyGate
BackupPrivacyGate
CompositePrivacyGate
RedactionSanitizer / CloudPayloadRedactor
PrivacyAuditLogger
PrivacySettingsRepository
DataRetentionWorker
ExportAnonymizer
```

Must prove:

```text
denied means provider not called
audit written
redaction before cloud call
raw storage policy enforced
export/backup privacy mode respected
UI shows denied state
```

---

## 4.7 Workers/runtime/startup

Deeply check:

```text
AppStartupCoordinator
RestoreMaintenanceMode
WorkerExecutionGuard
WorkerRunLogger
WorkerRegistry
WorkerSpecScheduler
all important workers
```

Must prove:

```text
restore checked before write
workers skipped during restore
BackgroundJobRun recorded
no duplicate side effects on rerun
startup schedules idempotently
CancellationException rethrown
```

---

## 4.8 Import/export/accounting

Deeply check:

```text
CsvCellSanitizer
AccountingExportPolicy
AccountingExportRepository
ImportCoordinator
JsonExpenseImporter
ExpenseExportMapper
```

Must prove:

```text
CSV formula injection neutralized
multi-currency policy enforced
original and converted money exported
privacy fields redacted
roundtrip does not change dashboard/analytics totals
unsupported fields reported, not silently lost
```

---

## 4.9 DI/Hilt and Android smoke

Not golden, but mandatory quality tests.

Add:

```text
HiltGraphSmokeTest
WorkerConstructionSmokeTest
NavigationRouteSmokeTest
PrivacySettingsDataStoreAndroidTest
BackupRestoreFilesystemAndroidTest
SecureKeyStorageAndroidTest
```

These catch wiring failures that golden JVM tests may miss.

---

# 5. Depth checklist for every golden test

A golden test is only good if it checks all of this:

```text
fixed clock
fixed timezone
fixed IDs where needed
real production entry point
real Room DB where persistence matters
fake only external providers
explicit expected JSON
missing golden fails
money has currency
partial data has warning
events are asserted
diagnostics are asserted
dashboard/analytics/budget/export outputs are asserted
bad/negative variant included
```

Bad golden smells:

```text
expected output generated by same engine under test
direct DAO insert pretending to be pipeline
mock returns value and test only checks that value
assertNotNull only
no event-log assertions
no partial-warning assertions
raw Double totals
golden file missing but test passes
```

---

# 6. Suggested implementation order

## PR 1 — Golden infrastructure

```text
fix GoldenScenarioVerifier
add /resources/golden structure
add ScenarioRunner
add stable actual-output serializers
add -PupdateGoldens=true
```

## PR 2 — Numeric foundation

```text
MulticurrencyAnalyticsDashboardBudgetGoldenTest
AnalyticsDashboardBudgetParityGoldenTest
CurrencyConverter historical/stale/missing regression goldens
```

## PR 3 — Lifecycle core

```text
TransactionLifecycleFullContractGoldenTest
NotificationReviewDashboardBudgetGoldenTest
ReceiptMatchingAnalyticsNoDoubleCountGoldenTest
RecurringPlannedActualNoDoubleCountGoldenTest
```

## PR 4 — Runtime/user-trust flows

```text
BackupRestoreFullAppRoundtripGoldenTest
PrivacyAiRedactionGateGoldenTest
WorkerRestoreBarrierIdempotencyGoldenTest
```

## PR 5 — Feature breadth

```text
GroupSettlementBudgetOffsetGoldenTest
BankSyncFailureRecoveryGoldenTest
EmailReceiptLifecycleWarrantyPriceGoldenTest
CsvAccountingExportImportRoundtripGoldenTest
InvestmentPortfolioMulticurrencyGoldenTest
TaxBusinessMileageExportGoldenTest
```

---

# 7. If you only create 5 new golden tests first

Do these:

```text
1. MulticurrencyAnalyticsDashboardBudgetGoldenTest
2. NotificationReviewDashboardBudgetGoldenTest
3. ReceiptMatchingAnalyticsNoDoubleCountGoldenTest
4. RecurringPlannedActualNoDoubleCountGoldenTest
5. BackupRestoreFullAppRoundtripGoldenTest
```

These give the best protection for the most dangerous multipipeline bugs.

---

# 8. Sources checked

- Root/test tree at commit `1848202`:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f

- Codebase segments / 38 segment ownership:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/docs/architecture/CODEBASE_SEGMENTS.md

- Engine interaction map / dangerous engines:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/docs/architecture/ENGINE_INTERACTION_MAP.md

- Legal production paths:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/docs/architecture/LEGAL_PATHS.md

- AppDatabase v124/entities/DAO list:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt

- Test resources currently visible:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/resources

- Scenario tests directory:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios

- Metrics/golden dataset tests directory:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/metrics

- Golden master verification directory:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/verification