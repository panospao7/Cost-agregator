# Cost-agregator test evaluation — commit `983e963`

Reviewed commit: `983e963116f4c2c21baf88511e47e285386fa591`  
Review type: static GitHub review, not local execution.

## 1. Executive verdict

This commit is a **major step forward**.

You added the right things:

- external golden JSON files under `app/src/test/resources/golden/`
- strict `GoldenScenarioVerifier`
- deterministic fixed-time fixtures
- UTC fixes in `GoldenDataSets`
- many release-gate style golden tests
- removal of `GoldenScenarioSmokeTest`
- stronger coverage around:
  - multi-currency
  - stale rates
  - transaction lifecycle
  - recurring planned/actual
  - receipt no-double-count
  - privacy gate
  - backup/write barriers
  - CSV safety
  - group budget offset
  - merchant dedupe

This is no longer “random tests.” It is becoming a real test architecture.

But the current golden layer has one important limitation:

> Most tests still simulate pipelines by directly writing DAOs. They prove persistence/repository contracts, not full production flows.

So I would classify the new suite like this:

```text
Golden infrastructure:       much improved, but needs hardening
Numeric/currency goldens:    good and valuable
Lifecycle goldens:           useful DB-contract tests, not full lifecycle yet
Pipeline goldens:            mostly simulated, not real pipeline yet
Runtime/privacy goldens:     useful but not full end-to-end
CI integration:              documented, but not yet strongly separated
```

Overall grade:

```text
Before: C-
Now:    B-
Target: A- / A
```

The commit moves you a lot closer to a high-confidence suite, but the next step is to replace “simulated production effects” with “real public production entry points.”

---

# 2. Golden infrastructure evaluation

## 2.1 `GoldenScenarioVerifier.kt`

Path:

```text
app/src/test/java/com/yourname/expensetracker/testfixtures/golden/GoldenScenarioVerifier.kt
```

## Verdict

**Good improvement. Keep and harden.**

## What improved

This fixes the biggest previous problem:

```text
missing golden file = failure
```

That is critical. Before, a missing golden silently passed. Now the verifier can fail if expected JSON is absent.

Good features:

- JSON object comparison
- JSON array comparison
- numeric tolerance
- ignored fields
- optional array sorting
- update mode
- diff output

This is the right foundation.

## Problems / improvements needed

### Problem 1 — update mode flag is inconsistent

The verifier reads:

```text
System.getProperty("updateGoldens")
```

Gradle forwards:

```kotlin
it.systemProperty("updateGoldens", project.findProperty("updateGoldens") ?: "false")
```

So this works:

```bash
./gradlew :app:testDebugUnitTest -PupdateGoldens=true
```

But docs/verifier messages also mention:

```bash
-DupdateGoldens=true
```

That may not be reliably propagated into the test JVM because Gradle does not automatically forward all system properties to tests.

Fix:

```kotlin
val updateGoldens =
    project.findProperty("updateGoldens")
        ?: System.getProperty("updateGoldens")
        ?: "false"

unitTests.all {
    it.systemProperty("updateGoldens", updateGoldens)
}
```

And standardize docs to prefer:

```bash
-PupdateGoldens=true
```

### Problem 2 — allow accidental update mode in CI

Add a guard:

```kotlin
if (UPDATE_MODE && System.getenv("CI") == "true") {
    error("Golden update mode is forbidden in CI")
}
```

### Problem 3 — numeric tolerance is global

For forecast you use tolerance `1.0`. That tolerance applies to every numeric field in the JSON, including counts/flags if represented as numbers.

Better:

```text
money tolerance = 0.01
forecast percentile tolerance = 1.0
counts/IDs = exact
percentages = 0.0001 or explicit
```

Later improvement: path-specific tolerances.

### Problem 4 — golden JSON files are one-line

They are valid, but harder to review in diffs.

Prefer pretty-printed committed JSON:

```json
{
  "dashboardTotal": 220,
  "categoryTotalsSum": 220
}
```

This matters because the human review of golden diffs is the real safety process.

---

# 3. Base fixture evaluation

## `GoldenTestBase.kt`

## Verdict

**Useful, but one important fix needed.**

Good:

- fixed clock
- in-memory Room
- helper categories
- helper expense builder
- real database
- clear rule: mock external services only

Problem:

It uses:

```kotlin
Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
```

Earlier we discussed using the official app DB builder:

```kotlin
AppDatabase.inMemoryBuilder(context)
```

or:

```kotlin
AppDatabaseTestFactory.create(context)
```

Reason: direct Room builders can bypass production callbacks, supplemental indexes, fresh-install invariants, and configuration that your actual app builder applies.

Recommendation:

Change `GoldenTestBase` to use the official DB factory.

```kotlin
database = AppDatabaseTestFactory.create(context)
```

or:

```kotlin
database = AppDatabase.inMemoryBuilder(context).build()
```

Direct `Room.inMemoryDatabaseBuilder` should be allowlisted only for migration/fresh-install parity tests.

---

# 4. Evaluation by golden test

## 4.1 `MulticurrencyAnalyticsDashboardBudgetGoldenTest.kt`

## Verdict

**Keep. One of the best new tests.**

## What it proves

This test uses:

- real Room DB
- real `ExchangeRateDao`
- real `CurrencyConverter`
- real `MultiCurrencyRepository`
- fixed home currency
- external expected JSON

It verifies:

```text
100 EUR
50 USD @ 0.90 = 45 EUR
40 GBP @ 1.15 = 46 EUR
20 CHF missing rate
displayTotal = 191 EUR
isPartial = true
conversionFailures includes CHF MISSING_RATE
source buckets preserve original currencies
deposit excluded
```

This is exactly the kind of test you need.

## Weakness

The name says:

```text
Analytics Dashboard Budget
```

But the test mainly exercises:

```text
MultiCurrencyRepository purchase totals + category totals
```

It does not yet test:

- actual dashboard repository/card state
- budget monitor/status
- analytics engine output
- forecast confidence
- export mapping

## Recommendation

Keep this as:

```text
MulticurrencyRepositoryGoldenTest
```

Then add a higher-level test:

```text
MulticurrencyAnalyticsDashboardBudgetEndToEndGoldenTest
```

That asserts:

```text
DashboardState.warning == PARTIAL_CONVERSION
AnalyticsResult.isPartial == true
BudgetStatus.usesOnlySafeConvertedValues == true
Export includes original and converted values
```

---

## 4.2 `StaleRateCurrencyConversionGoldenTest.kt`

## Verdict

**Keep. High-value regression test.**

This directly protects a dangerous bug class:

```text
stale exchange rate must become RATE_STALE, not silently converted or MISSING_RATE
```

Good:

- real DB rates
- real converter
- real repository
- exact expected output
- source buckets and conversion failure asserted

Add next:

```text
exact 24h boundary test
historical transaction-date rate test
missing vs stale disambiguation test
category total partial propagation test
ViewModel/dashboard warning propagation test
```

This is a strong test.

---

## 4.3 `AnalyticsDashboardBudgetParityGoldenTest.kt`

## Verdict

**Keep, but rewrite part of it.**

Good intent:

You are testing a very important contract:

```text
dashboard total == analytics/category total == budget actual
```

This is one of the most important golden tests.

Problem:

The budget path manually normalizes non-EUR:

```kotlin
if (exp.currency == "EUR") exp.amount else exp.amount * 0.90
```

That means the test reimplements conversion logic in the test body instead of calling the real analytics normalizer.

That is dangerous because:

- it may diverge from production normalization
- it does not test stale/missing rates
- it does not prove all engines use the same `NormalizedAnalyticsInput`
- it can accidentally validate the test’s own math instead of production behavior

Recommendation:

Replace manual normalization with the real production normalizer/assembler.

Target path:

```text
expenses + exchange rates
→ AnalyticsCurrencyNormalizer / NormalizedAnalyticsInput builder
→ DashboardRepository
→ BudgetVsActualEngine
→ AnalyticsEngine
```

Keep the golden JSON, but make the actual path more production-like.

---

## 4.4 `NotificationReviewDashboardBudgetGoldenTest.kt`

## Verdict

**Useful DB-contract test, but not a real notification pipeline golden yet.**

Good:

- tests dedupe key uniqueness
- transaction events exist
- dashboard total excludes duplicate
- category totals are checked
- expected event sequence is committed

Weakness:

The test directly does this:

```kotlin
expenseDao.insertAtomic(...)
transactionEventDao.insert(...)
```

So it does **not** prove:

```text
NotificationProcessingPipeline
parser registry
confidence routing
review queue
review approval
TransactionLifecycleCoordinator
merchant categorization
pipeline diagnostic event
budget alert
```

It simulates the final writes.

Recommendation:

Keep this but rename mentally as:

```text
NotificationPipelineDbContractGoldenTest
```

Then add the real one:

```text
NotificationReviewLifecycleDashboardGoldenTest
```

Real path:

```text
RawNotification
→ NotificationProcessingPipeline
→ parser
→ confidence router
→ pending review or auto accept
→ approve review
→ TransactionLifecycleCoordinator
→ dashboard
→ budget
→ analytics
→ pipeline diagnostics
```

This is probably the most important missing upgrade.

---

## 4.5 `TransactionLifecycleFullContractGoldenTest.kt`

## Verdict

**Good DB-contract test, but still bypasses lifecycle coordinator.**

Good:

- create/duplicate/update/delete sequence
- dashboard total after each state
- transaction event log survives deletion
- exact JSON expected output

Weakness:

The test directly calls:

```kotlin
expenseDao.insertAtomic()
expenseDao.update()
expenseDao.delete()
transactionEventDao.insert()
```

So it does not prove:

```text
TransactionLifecycleCoordinator enforces all rules
side effects happen after commit
duplicate policy comes from lifecycle
rollback behavior
recurring hook integration
budget invalidation/recalculation hook
```

Recommendation:

Add a second test:

```text
TransactionLifecycleCoordinatorGoldenTest
```

It should call the real coordinator:

```kotlin
transactionLifecycle.create(...)
transactionLifecycle.update(...)
transactionLifecycle.delete(...)
```

Then assert the same golden JSON.

When that exists, this current test can remain as a lower-level DAO/event contract test.

---

## 4.6 `ReceiptMatchingNoDoubleCountGoldenTest.kt`

## Verdict

**Good persistence/no-double-count test, not full receipt lifecycle.**

Good:

- existing expense + receipt link
- unique link rejection
- analytics total counts once
- receipt event written
- no new expense created

This protects an important data integrity invariant.

Weakness:

It directly inserts:

```text
ScannedReceipt
ReceiptExpenseLink
ReceiptEvent
```

It does not test:

```text
ReceiptLifecycleCoordinator
OCR/parser stage
ReceiptDuplicateDetector
ReceiptTransactionMatcher
ReceiptLinkService
item categorization
warranty/price side effects
status sequence CAPTURED → OCR_COMPLETE → PARSED → MATCHED
```

Recommendation:

Keep this as:

```text
ReceiptLinkNoDoubleCountDbGoldenTest
```

Add:

```text
ReceiptLifecycleMatchingGoldenTest
```

That feeds OCR/email input through production receipt services.

---

## 4.7 `RecurringPlannedActualNoDoubleCountGoldenTest.kt`

## Verdict

**Good DAO/DB-contract test, not full recurring lifecycle yet.**

Good:

- planned occurrence
- actual expense
- claim occurrence
- planned expense fulfilled
- dashboard counts actual once
- future occurrences remain planned

Weakness:

It directly calls:

```kotlin
recurringOccurrenceDao.claimForExpense(...)
plannedExpenseDao.fulfillByOccurrenceKey(...)
```

It does not test:

```text
RecurringLifecycleCoordinator
occurrence generator
reminder delivery worker
actual matching policy
idempotent worker rerun
forecast future planned cost
budget current period behavior
recurring lifecycle events
```

Recommendation:

Keep it, but add:

```text
RecurringLifecycleCoordinatorGoldenTest
```

and:

```text
RecurringReminderWorkerIdempotencyGoldenTest
```

---

## 4.8 `GroupSettlementBudgetOffsetGoldenTest.kt`

## Verdict

**Good effective-amount golden, but not full group settlement golden.**

This is valuable because it verifies:

```text
shared expense uses myShareAmount
isNotMine excluded
deposit reimbursement excluded from spending
budget uses effective share
dashboard total = 80 not gross 140
```

That is important.

Weakness:

It does not use the group lifecycle system:

```text
GroupLifecycleCoordinator
GroupExpenseDao
GroupSettlementDao
settlement suggestions
group balances
group lifecycle events
foreign-currency group rejection
```

It also manually constructs `NormalizedExpense` for budget instead of using the production analytics input assembler.

Recommendation:

Keep as an effective-amount/budget-offset golden.

Add real:

```text
GroupLifecycleSettlementGoldenTest
```

Expected JSON should include:

```json
{
  "groupBalances": {
    "Alice": "30.00",
    "Bob": "0.00",
    "Carol": "-30.00"
  },
  "settlementSuggestions": [
    {
      "from": "Carol",
      "to": "Alice",
      "amount": "30.00"
    }
  ],
  "grossSpend": "90.00",
  "budgetEffectiveSpend": "30.00",
  "events": [
    "GROUP_EXPENSE_ADDED",
    "SETTLEMENT_RECORDED"
  ]
}
```

---

## 4.9 `BackupRestoreRoundtripGoldenTest.kt`

## Verdict

**Misnamed. Useful write-barrier test, not backup/restore roundtrip.**

Good:

- tests non-normal restore modes block writes
- normal mode allows writes
- dashboard total preserved across mode checks
- all blocked modes listed in JSON

Weakness:

Despite the name, it does **not** test:

```text
backup bundle creation
restore into fresh DB
restore journal
worker pause/resume
DB verification
receipt file preservation
exchange-rate restoration
privacy audit preservation
background job preservation
```

It also uses a mocked maintenance mode for most of the barrier checks.

Recommendation:

Rename or classify as:

```text
RestoreWriteBarrierGoldenTest
```

Then add a real:

```text
BackupRestoreFullAppRoundtripGoldenTest
```

Path:

```text
seed original DB
→ backup bundle
→ restore into fresh DB
→ verify dashboard/analytics/receipt links/groups/rates/privacy audit equal original
→ verify workers paused/resumed
```

---

## 4.10 `WorkerRestoreBarrierIdempotencyGoldenTest.kt`

## Verdict

**Useful barrier contract, not worker runtime test.**

Good:

- every named worker operation is blocked by barrier
- barrier is idempotent
- exception includes operation name
- allowed after restore

Weakness:

It does not run actual workers.

It does not prove:

```text
ReceiptMatchingWorker checks barrier before write
BillReminderWorker checks barrier before notification
DailyBriefingWorker checks barrier before AI work
BackgroundJobRun rows are written
no duplicate side effects on rerun
CancellationException is rethrown
```

Recommendation:

Keep as `DatabaseWriteBarrierContractTest`.

Add actual worker tests:

```text
ReceiptMatchingWorkerRestoreBarrierTest
BillReminderWorkerIdempotencyTest
DailyBriefingWorkerRestoreBarrierTest
WorkerRunLoggerGoldenTest
```

---

## 4.11 `PrivacyGateEnforcementGoldenTest.kt`

## Verdict

**Good focused privacy contract. Needs full runtime privacy scenario next.**

Good:

- real `CompositePrivacyGate`
- real `LocationPrivacyGate`
- real `PrivacyAuditLoggerImpl`
- audit rows persisted
- denied decisions block execution
- allowed decision does not block

Weakness:

Only location capabilities are covered.

Missing:

```text
Cloud AI provider not called
notification capture denied means no raw notification persisted
backup/export denied means no raw export
redaction before allowed cloud calls
raw sensitive text not stored
UI state exposes privacy denied reason
```

Recommendation:

Keep.

Add:

```text
PrivacyAiRedactionRuntimeGoldenTest
```

Expected:

```json
{
  "cloudProviderCalls": 0,
  "notificationWrites": 0,
  "rawSensitiveTextStored": false,
  "auditEvents": [
    "CLOUD_AI_DENIED",
    "NOTIFICATION_CAPTURE_DENIED"
  ]
}
```

---

## 4.12 `CsvExportImportRoundtripGoldenTest.kt`

## Verdict

**Important, but currently freezes a questionable result. Fix this.**

This test found/contains a very important issue.

Golden JSON says:

```json
"allDangerousNeutralized": false
```

because the `=HYPERLINK("http://evil.com")` case is sanitized as:

```text
"'=HYPERLINK(""http://evil.com"")"
```

That output is quoted as CSV, so checking:

```kotlin
sanitized.trimStart().startsWith("'")
```

returns false because the first character is `"`, not `'`.

There are two possibilities:

1. Sanitizer is correct because after CSV parsing the field value starts with `'`.
2. Sanitizer is unsafe because Excel/Sheets may still interpret it badly.

But the golden test should **not** accept:

```text
allDangerousNeutralized = false
```

as a passing expected output.

That is exactly the kind of “passing test with broken expectation” you were worried about.

Fix test logic:

- parse the CSV cell back to logical value, then assert it starts with `'`
- or assert either:
  - raw output starts with `'`, or
  - raw output is quoted and decoded field starts with `'`

Expected should become:

```json
"allDangerousNeutralized": true
```

If it cannot become true, fix the sanitizer.

Also: the test is not a true export/import roundtrip. It only tests the cell sanitizer.

Rename:

```text
CsvCellSanitizerGoldenTest
```

Then add actual:

```text
CsvExportImportRoundtripGoldenTest
```

with DB export/import and dashboard totals matching.

---

## 4.13 `ForecastSynthesisGoldenTest.kt`

## Verdict

**Good deterministic Monte Carlo golden. Not full synthesis yet.**

Good:

- seed-like deterministic output
- expected percentiles committed
- confidence asserted
- known upcoming included
- p10/p50/p90 ordering checked

Weakness:

It mocks `HistoricalSpendingDistribution`.

It does not test:

```text
ForecastInputAssembler
recurring future planned expenses
actual vs planned no-double-count
partial currency confidence degradation
dashboard forecast card
budget probability through UI state
```

Recommendation:

Keep this as:

```text
MonteCarloSimulatorGoldenTest
```

Add:

```text
ForecastPipelineGoldenTest
```

---

## 4.14 `BankSyncFailureRecoveryGoldenTest.kt`

## Verdict

**Useful bank-connection state test, not bank sync pipeline.**

Good:

- failed/partial/success states
- disconnect clears token
- dashboard total remains sane

Weakness:

It directly updates DAO status and inserts expenses.

It does not test:

```text
bank API client
expired token handling
refresh flow
partial sync transaction import
duplicate bank transaction skip
low confidence review
approval through lifecycle coordinator
source = BANK_API_SYNC
diagnostic events
```

Recommendation:

Keep as:

```text
BankConnectionStateGoldenTest
```

Add real:

```text
BankSyncFailureRecoveryLifecycleGoldenTest
```

---

## 4.15 `MerchantCategorizationDedupeGoldenTest.kt`

## Verdict

**Useful, but tighten the assertions.**

Good:

- Greek/Latin variants normalize to same merchant key
- duplicate detection uses merchant key
- merchant total grouping is checked

Weakness:

The grouping query/aggregation seems to still use raw merchant names in filtering logic inside the test:

```kotlin
filter { it.merchant.lowercase().contains("sklav") || it.merchant.contains("ΣΚΛΑΒ") }
```

That weakens the claim “grouped by merchantKey.”

Also field name typo:

```text
sklavenitsTransactionCount
```

Not fatal, but fix it before this becomes a stable contract.

Add:

```text
merchant alias row upsert
occurrenceCount increment
lastUsedAt update
category cache invalidation
receipt matching merchant variant
recurring matching merchant variant
```

---

## 4.16 `HiltGraphSmokeTest.kt`

## Verdict

**Not a real Hilt graph test. Keep as smoke, but rename.**

This manually constructs some classes and checks DAOs are non-null.

That is useful, but it does not test Hilt.

It will not catch:

```text
missing @Provides binding
wrong qualifier
dependency cycle
worker factory binding
ViewModel binding
Android component graph failure
```

Recommendation:

Rename:

```text
CoreConstructionSmokeTest
```

Add instrumented test:

```text
app/src/androidTest/.../di/HiltGraphSmokeTest.kt
```

It should actually use Hilt and resolve:

```text
AppDatabase
all DAOs
repositories
lifecycle coordinators
workers
privacy gates
AI providers
key ViewModels
```

Navigation smoke is also too shallow: only checks 3 destinations exist. Add route serialization/deep-link coverage.

---

# 5. Extra tests in `golden/` that are not true golden

These are useful but should be classified properly.

## `MultiCurrencyDashboardConsistencyTest.kt`

Verdict: **delete or replace with the new multicurrency golden.**

This is now mostly redundant and weaker than `MulticurrencyAnalyticsDashboardBudgetGoldenTest`.

It manually computes:

```kotlin
expectedTotal = 100.0 + (110.0 * 0.91)
```

but does not actually call the converter/repository for the total.

Recommendation:

Delete after the stronger golden is passing.

## `ConcurrentOccurrenceClaimTest.kt`

Verdict: **keep as DAO atomic-claim contract, but rename.**

It is not really concurrent. It performs two sequential claims.

Rename:

```text
RecurringOccurrenceAtomicClaimDaoTest
```

Add actual concurrent variant later if needed.

## `RecurringBillPaymentMatchTest.kt`

Verdict: **keep, but move/rename.**

Good DAO contract for occurrence claim, planned expense fulfillment, reminder suppression.

But it is not a golden scenario. Move to:

```text
data/database/dao/RecurringLifecycleDaoContractTest.kt
```

or:

```text
domain/recurring/lifecycle/RecurringLifecyclePersistenceContractTest.kt
```

## `RuleDeactivationCleanupTest.kt`

Verdict: **keep/rewrite as lifecycle contract.**

It directly performs DAO cleanup steps. Good persistence checks, but not proving the real rule deactivation use case.

Add real lifecycle method test:

```text
RecurringRuleDeactivationLifecycleTest
```

## `PrivacyDoNotStoreTest.kt`

Verdict: **keep. Good policy test.**

This is a useful privacy storage contract.

But it should live under:

```text
domain/privacy
```

or:

```text
data/notification/privacy
```

Not under `golden`.

## `RestoreBlocksAllWritesTest.kt`

Verdict: **mostly redundant with Worker/Backup barrier tests.**

Keep one write barrier contract, not three overlapping versions.

Potentially delete after merging useful cases into:

```text
DatabaseWriteBarrierContractTest
```

---

# 6. Updated scorecard

| Area | Current status | Depth |
|---|---|---|
| Golden verifier | Much improved | B+ |
| External expected JSON | Added | B |
| Multi-currency repository | Strong | A- |
| Stale rate regression | Strong | A- |
| Analytics/dashboard/budget parity | Good intent, manual normalization | B- |
| Transaction lifecycle | DB-contract only | B- |
| Notification pipeline | Simulated, not real pipeline | C+ |
| Receipt no-double-count | Good DB/link contract | B |
| Recurring no-double-count | Good DB contract | B |
| Group budget offset | Good effective-amount contract | B |
| Backup/restore | Misnamed write-barrier test | C+ |
| Privacy | Good gate contract, not full runtime | B |
| Workers | Barrier only, no actual workers | C+ |
| Bank sync | DAO/status only | C+ |
| CSV | Important but expectation issue | C until fixed |
| Hilt/navigation | Not real Hilt, shallow nav | C |
| Investment | Improved assertions, still mock converter oddity | B- |
| Tax | Mileage good, tax aggregate still shallow | C+ |

---

# 7. Highest-priority fixes after this commit

## P0 — Fix the CSV golden

Do not let this remain:

```json
"allDangerousNeutralized": false
```

Either fix the sanitizer or fix the test’s CSV-decoding check.

This is security/export related.

## P0 — Switch GoldenTestBase to official DB factory

Replace direct Room builder with app DB builder/test factory.

## P0 — Separate “real golden” from “DAO contract”

Rename/move tests so you know what they really prove.

Example:

```text
NotificationReviewDashboardBudgetGoldenTest
```

currently should be considered:

```text
NotificationPipelineDbContractGoldenTest
```

because it does not run the pipeline.

## P1 — Add real production-entry-point versions

Add these next:

```text
NotificationReviewLifecycleDashboardGoldenTest
TransactionLifecycleCoordinatorGoldenTest
ReceiptLifecycleMatchingGoldenTest
RecurringLifecycleCoordinatorGoldenTest
BackupRestoreFullAppRoundtripGoldenTest
BankSyncLifecycleGoldenTest
PrivacyAiRedactionRuntimeGoldenTest
```

## P1 — Add actual Hilt android smoke

Current Hilt test is not Hilt.

## P1 — Improve parity test to use real normalizer

Remove manual `amount * 0.90`.

---

# 8. What I would keep, move, rewrite, delete

## Keep as release-gate goldens

```text
MulticurrencyAnalyticsDashboardBudgetGoldenTest.kt
StaleRateCurrencyConversionGoldenTest.kt
AnalyticsDashboardBudgetParityGoldenTest.kt  // after normalizer rewrite
ForecastSynthesisGoldenTest.kt
PrivacyGateEnforcementGoldenTest.kt
```

## Keep but classify as DB/persistence contract, not full golden

```text
TransactionLifecycleFullContractGoldenTest.kt
ReceiptMatchingNoDoubleCountGoldenTest.kt
RecurringPlannedActualNoDoubleCountGoldenTest.kt
RecurringBillPaymentMatchTest.kt
ConcurrentOccurrenceClaimTest.kt
RuleDeactivationCleanupTest.kt
```

## Rewrite/upgrade

```text
NotificationReviewDashboardBudgetGoldenTest.kt
BackupRestoreRoundtripGoldenTest.kt
WorkerRestoreBarrierIdempotencyGoldenTest.kt
BankSyncFailureRecoveryGoldenTest.kt
GroupSettlementBudgetOffsetGoldenTest.kt
MerchantCategorizationDedupeGoldenTest.kt
CsvExportImportRoundtripGoldenTest.kt
HiltGraphSmokeTest.kt
```

## Move out of `golden/`

```text
PrivacyDoNotStoreTest.kt
RestoreBlocksAllWritesTest.kt
ConcurrentOccurrenceClaimTest.kt
RecurringBillPaymentMatchTest.kt
RuleDeactivationCleanupTest.kt
HiltGraphSmokeTest.kt
MultiCurrencyDashboardConsistencyTest.kt
```

## Delete after replacement

```text
MultiCurrencyDashboardConsistencyTest.kt
RestoreBlocksAllWritesTest.kt
```

Reason: largely superseded by stronger tests.

---

# 9. Final assessment

This commit is a **strong and useful milestone**. You did the right thing by moving toward golden expected outputs.

But do not overestimate the current depth.

The current suite mostly proves:

```text
DB rows + repository totals + selected engines
```

It does not yet fully prove:

```text
real user/app input
→ production coordinator/pipeline
→ event logs
→ diagnostics
→ dashboard/analytics/budget/export/UI state
```

That is the next phase.

The most important conceptual cleanup is:

```text
DAO-contract golden != multi-pipeline golden
```

Both are valuable. But they must be named and gated honestly.

If you now add real production-entry-point scenarios on top of this commit, your test suite will become genuinely high-confidence instead of just high-volume.

# Sources reviewed

- Commit: https://github.com/panospao7/Cost-agregator/commit/983e963116f4c2c21baf88511e47e285386fa591
- Golden tests directory: https://github.com/panospao7/Cost-agregator/tree/983e963116f4c2c21baf88511e47e285386fa591/app/src/test/java/com/yourname/expensetracker/golden
- Golden resources: https://github.com/panospao7/Cost-agregator/tree/983e963116f4c2c21baf88511e47e285386fa591/app/src/test/resources/golden
- Golden verifier: https://raw.githubusercontent.com/panospao7/Cost-agregator/983e963116f4c2c21baf88511e47e285386fa591/app/src/test/java/com/yourname/expensetracker/testfixtures/golden/GoldenScenarioVerifier.kt
- CI workflow: https://raw.githubusercontent.com/panospao7/Cost-agregator/983e963116f4c2c21baf88511e47e285386fa591/.github/workflows/ci.yml
- Golden CI docs: https://raw.githubusercontent.com/panospao7/Cost-agregator/983e963116f4c2c21baf88511e47e285386fa591/docs/testing/GOLDEN_TESTS_CI_GATE.md