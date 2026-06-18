# Expanded testing review for Cost-agregator — commit `1848202`

Review type: static GitHub/code review, not local execution.

## 1. Core assessment

Your app has become a real multi-engine financial system. That means normal isolated unit tests are no longer enough.

The dangerous areas are now:

- money/currency conversion
- analytics normalization
- dashboard totals
- budget calculations
- recurring planned vs actual handling
- receipt matching
- notification/review/lifecycle flow
- backup/restore safety
- privacy fail-closed behavior
- group/shared-expense offsets
- background worker idempotency
- DI/wiring correctness

The most important thing is this:

> A passing test is only valuable if it validates the expected product contract, not the current accidental behavior.

So if the app currently sums EUR + USD + GBP as raw doubles and the test asserts that sum, the test is worse than no test. It freezes the bug.

---

# 2. Current testing problem

The repo now has useful infrastructure:

- `ScenarioSeed`
- `ScenarioSeeder`
- scenario tests
- `GoldenScenarioVerifier`
- DB-backed lifecycle tests
- many pure engine tests
- e2e flow tests

But the current scenario layer is still immature.

The biggest issue is:

```text
Many “scenario” tests are still DAO/seed tests,
not real multi-pipeline acceptance tests.
```

Example:

`ScenarioSeeder.feedInputs()` currently delegates to `seedState()`. That means it does not really feed:

```text
notification → parser → review → lifecycle → dashboard
```

It just inserts rows.

That is useful for fixture setup, but it is not enough to prove application behavior.

---

# 3. What a good test should prove

For this app, a high-value test should prove at least one of these:

## A. The legal production path was used

For example, expense creation should go through:

```text
TransactionLifecycleCoordinator.createExpense()
→ ExpenseDao.insertAtomic()
→ TransactionEvent.CREATED
→ side effects
```

A test that directly inserts `Expense` through `expenseDao.insert()` does not prove lifecycle behavior.

## B. The expected business output is correct

Not just:

```text
expense count == 1
```

But:

```text
dashboard monthly total == expected
analytics category total == expected
budget remaining == expected
conversion warning is present
duplicate event was written
```

## C. Cross-engine consistency is preserved

For the same seeded month, these should agree where their business contracts overlap:

```text
Dashboard total
Analytics period total
Budget spent
Forecast input total
Cashflow actual spending
Export converted total
```

If they do not agree, the app may look correct in one screen and wrong in another.

## D. Partial/unsafe data is visible

For financial software, silently producing a number is dangerous.

If currency conversion fails, stale rates are used, privacy blocks a provider, or a worker skips during restore, the output should expose that.

Examples:

```text
MoneyAggregate.isPartial == true
conversionFailures contains RATE_STALE
DataQualityReport.conversionConfidence < 1.0
dashboard warning is visible
privacy audit event was written
worker run logged SKIPPED_RESTORE_MODE
```

## E. The expected result is independent from production code

Do not calculate the expected value by calling the same engine under test.

Bad:

```text
expected = AdvancedAnalyticsEngine.calculate(...)
actual = AdvancedAnalyticsEngine.calculate(...)
assertEquals(expected, actual)
```

Good:

```text
input expenses:
- 100 EUR
- 50 USD with rate 0.90
- 40 GBP with rate 1.15
- 20 CHF missing rate

expected:
- source buckets: EUR 100, USD 50, GBP 40, CHF 20
- display total EUR: 191.00
- partial: true
- failure: CHF missing rate
```

---

# 4. Test categories you need

## Layer 1 — Pure engine tests

These are fast, deterministic, and should run on every PR.

Use them for:

- `MoneyAmount`
- `MoneyAggregate`
- `CurrencyCode`
- `CurrencyConverter`
- `AnalyticsCurrencyNormalizer`
- `TotalsAggregationEngine`
- `BudgetVsActualEngine`
- `DailyBucketEngine`
- `GroupBalanceCalculator`
- `SharedExpenseBudgetOffsetEngine`
- `TaxEstimator`
- `InvestmentTracker`
- `RecurringOccurrenceExpander`
- parser normalization

These tests should not use Room, Android, ViewModels, or broad mocks.

### Example: money/currency engine test

Input:

```text
home currency: EUR

expenses:
1. 100 EUR groceries
2. 50 USD shopping, rate USD→EUR = 0.90
3. 40 GBP travel, rate GBP→EUR = 1.15
4. 20 CHF dining, missing rate
```

Expected:

```text
source buckets:
- EUR: 100
- USD: 50
- GBP: 40
- CHF: 20

converted display total:
100 + 45 + 46 = 191 EUR

isPartial:
true

failure:
CHF missing rate

warning:
MISSING_RATE
```

This test is valuable because the expected result is explicit and independent.

---

## Layer 2 — Repository/Room contract tests

These prove persistence behavior.

Use them for:

- DAO insert/query/update/delete contracts
- foreign keys
- unique indices
- event log persistence
- transaction rollback
- migration correctness
- backup/restore table preservation

For your app, these are especially important because `AppDatabase` is huge: architecture says DB version is now `v124`, with many DAOs/entities.

Add or keep direct tests for:

```text
TransactionEventDao
ReceiptEventDao
ReceiptExpenseLinkDao
RecurringOccurrenceDao
RecurringReminderDeliveryDao
RecurringLifecycleEventDao
PrivacyAuditDao
BackgroundJobRunDao
PipelineDiagnosticEventDao
SourceStatsEventDao
GroupLifecycleEventDao
GroupSettlementDao
InvestmentTransactionDao
```

### Example: event log DAO contract

Input:

```text
expense id = 10
events:
- CREATED at t1
- UPDATED at t2
- DELETED at t3
```

Expected:

```text
events are returned ordered by occurredAt
event log is append-only
delete expense does not erase audit events unless contract says so
event contains source/reason/actor metadata
```

---

## Layer 3 — DB-backed lifecycle coordinator tests

These are stronger than DAO tests.

Use real Room DB, real lifecycle coordinator, but fake external side effects.

You already have `TransactionLifecycleCoordinatorDbContractTest`. That is good. Expand this style.

Needed tests:

```text
TransactionLifecycleCoordinatorDbContractTest
ReceiptLifecycleCoordinatorDbContractTest
RecurringLifecycleCoordinatorDbContractTest
GroupLifecycleCoordinatorDbContractTest
```

### Transaction lifecycle test should prove

Input:

```text
create manual expense
create duplicate
update amount/category
delete expense
```

Expected:

```text
1 expense created
duplicate skipped
event sequence:
- CREATED
- CREATE_DUPLICATE_SKIPPED
- UPDATED
- DELETED

dashboard total updates after create/update/delete
analytics category total updates
budget spent recalculates
recurring link hook called or skipped according to contract
```

Current DB-backed transaction test already checks row/event behavior. It should now add dashboard/analytics/budget assertions.

---

## Layer 4 — Multi-pipeline acceptance tests

This is the missing high-value layer.

A canonical scenario should use:

```text
GIVEN background DB state
WHEN real app input is fed through public production entry points
THEN assert final business outputs
```

Not:

```text
GIVEN expense row inserted directly
THEN count == 1
```

You need fewer of these, but they must be strong.

Target: 8–12 canonical scenarios.

---

# 5. Highest-value tests to add first

## 5.1 `notification_review_dashboard_budget`

This should replace the current shallow notification scenario.

### Input

```text
fixed now: 2026-05-01
home currency: EUR

category rules:
- SKLAVENITIS → Groceries
- AB BASILOPOULOS → Groceries
- Revolut merchant → Shopping

budget:
- Groceries monthly budget: 100 EUR

notifications:
1. Greek NBG notification: 45.50 EUR SKLAVENITIS
2. Revolut notification: 30.00 EUR Amazon
3. duplicate NBG notification
4. low-confidence notification requiring review
```

### Production path

```text
Raw notification
→ NotificationCaptureService / NotificationRepository
→ NotificationProcessingPipeline
→ parser registry
→ confidence router
→ review queue or auto-accept
→ ReviewQueueRepository approval
→ TransactionLifecycleCoordinator.createExpense()
→ TransactionEvent
→ DashboardRepository
→ AnalyticsRepository
→ BudgetRepository/BudgetMonitor
→ PipelineDiagnosticEvent
```

### Expected outputs

```text
raw notifications stored: 4
expenses created: 2 or 3 depending on review decision
duplicates skipped: 1
pending review count: 1 before approval
pending review count: 0 after approval
transaction events include CREATED and CREATE_DUPLICATE_SKIPPED
pipeline diagnostic events written
dashboard monthly total equals approved non-duplicate expenses only
analytics category total equals dashboard category total
budget remaining = 100 - groceries spend
budget state is NORMAL/WARNING/CRITICAL according to threshold
```

### Why this matters

This tests:

- parser
- dedupe
- review
- lifecycle
- dashboard
- analytics
- budget
- diagnostics

That is exactly the type of test your app needs.

---

## 5.2 `multicurrency_partial_rate_dashboard_analytics`

This is critical after the latest engine fixes.

### Input

```text
home currency: EUR

expenses:
1. 100 EUR groceries
2. 50 USD shopping, valid historical rate 0.90
3. 40 GBP travel, valid historical rate 1.15
4. 20 CHF dining, missing rate
5. 30 USD old transaction where only stale rate exists
```

### Expected outputs

```text
source buckets:
- EUR 100
- USD 80
- GBP 40
- CHF 20

converted display total:
100 + 45 + 46 + converted valid USD only
missing/stale values excluded or marked according to contract

isPartial:
true

failures:
- CHF MISSING_RATE
- old USD RATE_STALE

dashboard warning:
visible

analytics warning:
visible

budget:
uses only safely converted home-currency values

forecast:
confidence reduced or partial flag exposed

export:
original amount/currency and converted amount/warning both included
```

### Critical assertion

Never assert:

```text
dashboard total = 100 + 50 + 40 + 20
```

That is raw mixed-currency addition and should be forbidden.

---

## 5.3 `analytics_dashboard_budget_parity`

This is a cross-engine numeric consistency test.

### Input

```text
same month
same expenses
same categories
same exchange rates
same budget period
```

### Expected

```text
dashboard period total == analytics period total
dashboard category total for Groceries == analytics category total for Groceries
budget spent for Groceries == analytics Groceries total if both use same inclusion rules
cashflow purchase total == normalized purchase total
```

But be explicit where contracts differ.

For example:

```text
dashboard gross spending != budget net spending
```

is acceptable only if documented and asserted intentionally.

### Why this matters

Your architecture now says all analytics engines share `NormalizedAnalyticsInput`. This test proves that the shared normalization actually produces consistent downstream results.

---

## 5.4 `receipt_matching_analytics_no_double_count`

The current receipt DB test is useful, but it is DAO-level.

You need a real receipt pipeline test.

### Input

```text
existing bank transaction:
- Public, 89.99 EUR, Shopping, 2026-05-01

receipt OCR/email:
- merchant Public
- total 89.99
- same date
- item lines
- image hash / text fingerprint

fake AI item categorization:
- item A → Electronics
- item B → Accessories
```

### Production path

```text
Receipt input
→ ReceiptLifecycleCoordinator
→ ReceiptRepository OCR/parser draft
→ duplicate detector
→ receipt event log
→ ReceiptTransactionMatcher
→ ReceiptLinkService
→ ReceiptExpenseLink
→ item categorization
→ warranty/price-protection side effects if eligible
→ analytics
```

### Expected

```text
receipt created
receipt status sequence:
CAPTURED → OCR_COMPLETE → PARSED → MATCHED

receipt events written
receipt-expense link exists
no new duplicate expense created
analytics total counts 89.99 once
receipt item categorization rows exist
warranty/price-protection only run for eligible document type
```

### Why this matters

Without this, the app can pass receipt tests and still double-count matched receipts.

---

## 5.5 `recurring_planned_actual_no_double_count`

Current recurring test uses DAOs directly. Add coordinator-level behavior.

### Input

```text
recurring rule:
- Netflix
- 12.99 EUR
- monthly
- due May 1, June 1, July 1

actual expense:
- Netflix 12.99 EUR on May 1
```

### Production path

```text
RecurringLifecycleCoordinator.generateOccurrences()
→ RecurringOccurrenceMaterializer
→ reminder delivery
→ TransactionLifecycleCoordinator.createExpense()
→ RecurringLifecycleCoordinator.linkExpenseToOccurrence()
→ dashboard
→ forecast
→ budget
```

### Expected

```text
May occurrence generated
reminder delivery created once
actual expense linked to May occurrence
May occurrence status = PAID or MATCHED
dashboard May total includes 12.99 once, not 25.98
forecast includes future June/July planned occurrences
budget current period uses actual once
second worker run is idempotent
```

---

## 5.6 `shared_expense_reimbursement_budget_offset`

### Input

```text
group dinner:
- payer: Alice
- Bob owes 30
- Carol owes 30
- total 90 EUR

reimbursement:
- Bob pays Alice 30

budget:
- Dining budget 200 EUR
```

### Expected

```text
group balances correct
settlement suggestion correct
gross spending total explicit
budget-offset net amount correct
reimbursement does not appear as ordinary income unless contract says so
analytics is not corrupted
group lifecycle event written
```

### Why this matters

Shared expenses are numerically dangerous because gross spending, net spending, reimbursements, and budget offsets are all different concepts.

---

## 5.7 `backup_restore_roundtrip_workers_paused`

### Input

Seed DB with:

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

Then:

```text
start backup
enter backup exporting mode
restore into fresh DB
simulate pending workers
```

### Expected

```text
writes blocked during restore
workers paused
restore journal transitions persisted
restored dashboard equals original dashboard
restored analytics equals original analytics
receipt links preserved
recurring state preserved
exchange rates preserved
privacy audit preserved
workers resume only after safe completion
failure leaves app in fail-closed restart-required state
```

### Why this matters

Backup/restore bugs are release blockers. Passing repository tests alone is not enough.

---

## 5.8 `privacy_ai_redaction_gate`

### Input

```text
privacy settings:
- cloud AI disabled
- receipt image cloud upload disabled
- external geocoding disabled
- notification capture disabled

sensitive inputs:
- bank notification
- receipt OCR text
- natural-language query
- merchant/location text
```

### Expected

```text
cloud provider is not called
location provider is not called
notification capture skips DB write if disabled
audit event written with specific PrivacyBlocked type
allowed cloud calls receive redacted payload
raw sensitive text not stored when policy forbids it
UI state exposes privacy-denied reason
```

### Why this matters

Architecture says fail-closed privacy propagation now affects 30+ callers. You need scenario tests proving that.

---

## 5.9 `bank_sync_failure_recovery_lifecycle`

Current `BankSyncScenarioTest` is mostly DAO-level. Add a real ingestion scenario.

### Input

```text
bank connection active
first sync response:
- expired token error
second sync response:
- partial success
- one duplicate transaction
- one low-confidence merchant
- one valid transaction
```

### Expected

```text
auth failure state visible
partial sync does not corrupt DB
duplicate skipped
low-confidence item goes to review
approved review creates expense through TransactionLifecycleCoordinator
source = BANK_API_SYNC
transaction event written
dashboard includes only approved non-duplicates
pipeline diagnostic events written
```

---

## 5.10 `email_receipt_lifecycle_warranty_price`

Current email receipt scenario inserts DAO rows. Add the real flow.

### Input

```text
Amazon/Apple/Uber email fixture
existing matching card transaction
warranty-eligible item
price-protection-eligible item
privacy raw storage mode = STORE_REDACTED or METADATA_ONLY
```

### Expected

```text
email parser extracts merchant/total/date/items
email ingestion service sanitizes stored raw content
receipt lifecycle creates receipt
receipt linked to existing expense
no duplicate expense
warranty created only for eligible item
price protection created only for eligible item
analytics counts once
privacy audit/diagnostic event written
```

---

# 6. Tests specifically needed after commit `1848202`

The commit summary says it fixed dangerous engine issues around analytics, money, merchant categorization, and group currency policy.

Add regression tests for these exact issues.

## 6.1 Historical fallback marks partial with `RATE_STALE`

### Input

```text
expense: 100 USD on 2026-05-01
latest available USD/EUR rate is too old
```

### Expected

```text
aggregate.isPartial == true
conversion failure reason == RATE_STALE
dashboard/analytics exposes warning
PeriodTotal keeps partial flag
```

## 6.2 Category breakdown uses transaction-date normalization

### Input

```text
Jan expense:
- 100 USD
- Jan rate 0.90

Feb expense:
- 100 USD
- Feb rate 0.80

Category: Shopping
```

### Expected

```text
category total uses Jan rate for Jan expense
category total uses Feb rate for Feb expense
summary total and category total agree
percentages sum to 100%
```

This catches the exact class of bug where one engine uses current rate and another uses transaction-date rate.

## 6.3 `PeriodTotal` propagates partial/warning

### Input

```text
period contains one convertible expense and one stale-rate expense
```

### Expected

```text
PeriodTotal.amount displays converted safe total
PeriodTotal.isPartial == true
PeriodTotal.warningMessage is not null
ViewModel/UI state does not drop that warning
```

## 6.4 Weekly/daily transaction counts are preserved

### Input

```text
3 expenses Monday
0 Tuesday
2 Wednesday
one missing-rate transaction
```

### Expected

```text
daily bucket Monday transactionCount = 3
Tuesday = 0
Wednesday = 2
partial warning attached to affected bucket/aggregate
```

## 6.5 Group single-currency enforcement

### Input

```text
group default currency = EUR
attempt to add USD expense
```

### Expected

```text
operation fails
no group expense inserted
no linked system expense inserted
no settlement/balance mutation
group lifecycle diagnostic event written if contract requires it
```

Test both public group method and lower-level atomic method if both exist.

## 6.6 Merchant alias conflict update

### Input

```text
insert alias:
- merchant alias "Sklavenitis"
- canonical "SKLAVENITIS"
insert same alias again
```

### Expected

```text
no duplicate alias row
occurrenceCount increments
lastUsedAt updates
merchant category cache invalidated
subsequent categorization reads updated row
```

---

# 7. Golden expected-output strategy

Golden files are useful only if missing/mismatched files fail CI.

Current `GoldenScenarioVerifier` accepts a missing file. That is dangerous.

Change the policy:

```text
default CI:
- missing golden = fail
- mismatch = fail

explicit update mode:
- only with -PupdateGoldens=true
```

## Recommended golden scenario structure

```text
app/src/test/resources/scenarios/
  notification_review_dashboard_budget/
    seed.json
    input.json
    expected-dashboard.json
    expected-analytics.json
    expected-budget.json
    expected-events.json
    expected-diagnostics.json

  multicurrency_partial_rate/
    seed.json
    expected-money.json
    expected-dashboard.json
    expected-warnings.json

  receipt_matching_analytics/
    seed.json
    input.json
    expected-receipts.json
    expected-links.json
    expected-analytics.json

  backup_restore_roundtrip/
    seed.json
    expected-before.json
    expected-after.json
    expected-journal.json
```

## Golden files should assert stable contracts

Good golden fields:

```text
dashboardMonthlyTotal
homeCurrency
sourceCurrencyBuckets
conversionFailures
isPartial
warningTypes
categoryTotals
budgetRemaining
budgetSeverity
transactionEventTypes
receiptEventTypes
receiptExpenseLinkCount
recurringOccurrenceStatuses
groupBalances
settlementCount
privacyAuditCount
pipelineDiagnosticEventCount
backgroundJobRunStatuses
```

Avoid golden fields like:

```text
auto-generated IDs
exact ordering unless business-critical
raw timestamps unless part of contract
UI copy
implementation-private metadata
```

---

# 8. Scenario seed vs feed input

You need both.

## `seedState`

Use for background state:

```text
categories
exchange rates
budgets
existing expenses
existing receipts
existing recurring rules
existing groups
privacy settings
bank connections
```

Direct DAO insert is fine here because you are preparing the world.

## `feedInputs`

Use for real app behavior:

```text
notification text
receipt image/OCR/email
bank statement import
CSV import
manual expense creation
recurring rule creation
restore command
privacy setting change
worker run
```

`feedInputs` should never call `expenseDao.insert()` directly.

It should call real production entry points:

```text
NotificationProcessingPipeline
ReviewQueueRepository
TransactionLifecycleCoordinator
ReceiptLifecycleCoordinator
RecurringLifecycleCoordinator
ImportCoordinator
DatabaseBackupRepository
WorkerExecutionGuard
```

---

# 9. Numeric correctness testing rules

Because the app is financial, numeric tests need stricter rules.

## Rule 1 — never test raw `Double` total without currency context

Bad:

```text
sumOf { amount }
```

Good:

```text
MoneyAggregate:
- source buckets
- display amount
- display currency
- partial flag
- conversion failures
```

## Rule 2 — expected values should be hand-authored

Do not compute expected results using the same production engine.

Use simple independently verifiable tables.

Example:

```text
100 EUR = 100 EUR
50 USD × 0.90 = 45 EUR
40 GBP × 1.15 = 46 EUR
20 CHF = missing

expected display total = 191 EUR
partial = true
```

## Rule 3 — define rounding once

Add tests for:

```text
0.005 rounding
large amount formatting
negative refund
zero amount
multi-line CSV export
tax percentage rounding
budget percentage threshold
forecast percentile tolerance
```

## Rule 4 — test inclusion/exclusion rules

Financial totals differ by context.

Examples:

```text
dashboard gross spending may include group gross amount
budget net spending may subtract reimbursements
analytics may exclude transfers
cashflow may include deposits
forecast may include future planned recurring costs
```

Each scenario should explicitly say which rule it expects.

---

# 10. Data integrity tests to add

Add seeded violation tests for diagnostics.

## `database_integrity_seeded_violations`

Seed intentionally broken states:

```text
orphan receipt link
duplicate active budget
duplicate current group user
invalid currency code
stale running background job
missing transaction event for expense
duplicate raw notification fingerprint
planned occurrence linked to deleted expense
privacy audit missing for denied AI call
```

Expected:

```text
DatabaseIntegrityScanner reports every violation
healthy DB has zero false positives
diagnostic severity is correct
```

This is important because with many pipelines, bugs often leave “almost valid” data.

---

# 11. Worker/runtime tests to add

## `worker_restore_barrier_contract`

Input:

```text
restore mode active
run all 7 workers
```

Expected:

```text
each worker checks restore barrier before writes
BackgroundJobRun logged SKIPPED
no DB mutation occurs
CancellationException is rethrown
workers resume after mode returns NORMAL
```

## `worker_idempotency_contract`

Input:

```text
run receipt matching worker twice
run bill reminder worker twice
run daily briefing worker twice
```

Expected:

```text
no duplicate receipt links
no duplicate reminder deliveries
no duplicate notifications
background run table has separate runs with correct statuses
side effects happen once
```

---

# 12. DI / Android smoke tests

Because architecture lists many Hilt modules and 59 DAOs, add small instrumented smoke tests.

## `HiltGraphSmokeTest`

Resolve:

```text
AppDatabase
all DAOs
TransactionLifecycleCoordinator
ReceiptLifecycleCoordinator
RecurringLifecycleCoordinator
GroupLifecycleCoordinator
NotificationProcessingPipeline
DatabaseBackupRepository
Privacy gates
AI providers
workers
key ViewModels
```

Expected:

```text
graph starts
no missing bindings
no dependency cycle
all worker factories construct
```

This catches a class of bugs that mock-based unit tests cannot catch.

## `NavigationRouteSmokeTest`

Expected:

```text
every NavigationDestination serializes/deserializes
deep links map to expected destination
every routed screen has ViewModel or explicit no-ViewModel reason
```

---

# 13. Tests that should be rewritten before adding more

Based on current repo inspection:

## Rewrite first

```text
NotificationPipelineScenarioTest
ReceiptLifecycleDbContractTest
RecurringNoDoubleCountScenarioTest
BankSyncScenarioTest
EmailReceiptPipelineScenarioTest
MulticurrencyPartialRateScenarioTest
GoldenScenarioSmokeTest
```

Reason:

They are either parser-only, DAO-only, fixture-only, or they assert unsafe fixture behavior.

Do not delete all logic blindly. Convert useful parts into:

```text
parser tests
DAO tests
real scenario tests
```

## Keep and expand

```text
TransactionLifecycleCoordinatorDbContractTest
MoneyAggregateBuilderTest
PrivacyGateContractTest
GroupLifecycleScenarioTest
currency/analytics regression tests
migration/schema tests
```

But move pure domain tests out of `scenarios/`.

---

# 14. CI gates you should add

## PR gate

Run:

```text
fast unit tests
pure money/currency/analytics tests
parser tests
selected scenario smoke tests
schema verifier
direct DAO bypass guard
direct time usage guard
ignored-test-count guard
golden-file verifier
raw mixed-currency sum guard
```

## Main/release gate

Run:

```text
all JVM tests
all canonical scenarios
migration/fresh-install parity
backup/restore roundtrip
privacy fail-closed scenarios
Hilt graph smoke
Worker construction smoke
instrumented DAO tests
```

## Nightly

Run:

```text
large DB corpus
stress/concurrency
backup/restore large asset bundle
worker idempotency over repeated runs
performance thresholds
```

---

# 15. Implementation order I recommend

## Step 1 — make scenario infrastructure honest

1. Make `GoldenScenarioVerifier` fail on missing files.
2. Add `ScenarioRunner`.
3. Split `seedState` from real `feedInputs`.
4. Add assertion helpers for:
   - money aggregate
   - partial conversion
   - dashboard total
   - budget status
   - transaction events
   - receipt events
   - recurring occurrences
   - group balances
   - privacy audit
   - pipeline diagnostics

## Step 2 — convert one scenario fully

Start with:

```text
notification_review_dashboard_budget
```

This gives the most coverage per test.

## Step 3 — add the latest engine regression tests

Because commit `1848202` changed analytics/money/group behavior, add:

```text
stale rate partial propagation
category breakdown transaction-date rates
PeriodTotal warning propagation
daily/weekly transaction counts
group single-currency rejection
merchant alias conflict update
```

## Step 4 — convert receipt and recurring

Add:

```text
receipt_matching_analytics_no_double_count
recurring_planned_actual_no_double_count
```

## Step 5 — add backup/privacy/workers

Add:

```text
backup_restore_roundtrip_workers_paused
privacy_ai_redaction_gate
worker_restore_barrier_contract
```

---

# 16. Final principle

For this project, a good test should answer:

```text
What did we feed into the app?
Which legal production path handled it?
What durable DB/event/diagnostic state was produced?
What did dashboard/analytics/budget/export show?
Is that the expected business contract?
Would this fail if someone reintroduced raw doubles, bypassed lifecycle,
dropped partial warnings, or skipped privacy/restore guards?
```

If the answer is yes, the test is valuable.

If the test only proves:

```text
a DAO inserted a row
a mock was called
a file contains text
a raw sum equals a raw sum
a fake verifier accepted missing golden output
```

then it should be deleted, moved, or rewritten.

---

# Sources reviewed

- Commit `1848202`:  
  https://github.com/panospao7/Cost-agregator/commit/18482021294eba1d209afa2deb34aea6c107a52f

- Architecture guide:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/docs/architecture/ARCHITECTURE.md

- Segment map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/docs/architecture/CODEBASE_SEGMENTS.md

- Engine interaction map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/docs/architecture/ENGINE_INTERACTION_MAP.md

- Legal paths:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/docs/architecture/LEGAL_PATHS.md

- Scenario tests directory:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios

- Test fixtures:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/testfixtures

- `ScenarioSeeder`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/testfixtures/scenario/ScenarioSeeder.kt

- `GoldenScenarioVerifier`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/testfixtures/golden/GoldenScenarioVerifier.kt

- `AppDatabase`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt