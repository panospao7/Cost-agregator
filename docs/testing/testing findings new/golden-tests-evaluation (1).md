# Cost-agregator golden test evaluation

Reviewed against visible commit: `18482021294eba1d209afa2deb34aea6c107a52f`  
Important caveat: your local overhaul may be ahead of GitHub. If you send the current failing test report or updated branch, I can re-check the exact current files.

## 1. Executive opinion

Golden tests should be the **truth layer** of the app:

> “Given this realistic input world, the app must produce these exact business outputs.”

For Cost-agregator, golden tests are extremely important because the app now has:

- multiple ingestion paths
- multi-currency normalization
- analytics engines
- budget engines
- group/shared expense engines
- recurring engines
- receipt matching
- tax/export logic
- privacy/AI gates
- backup/restore
- many numeric dependencies

So yes: golden tests should be treated as **release-safety tests**.

But the current golden tests are mixed:

| File | Real value | True golden? | Verdict |
|---|---:|---:|---|
| `SpendingPaceGoldenTest.kt` | High | Partial | Keep and improve |
| `GroupGoldenScenarioTest.kt` | High | No | Keep, rename/rewrite as contract/golden |
| `InvestmentGoldenScenarioTest.kt` | Medium-high | No | Keep but strengthen |
| `TaxGoldenScenarioTest.kt` | Mixed | No | Split: keep mileage, rewrite tax, delete weak mock test |
| `GoldenScenarioSmokeTest.kt` | Low | No | Delete/collapse |
| `GoldenScenarioVerifier.kt` | Critical infra but unsafe | No | Fix urgently |
| `GoldenDataSets.kt` / `ExpectedResults.kt` | Useful fixture idea | Partial | Keep but harden |

The biggest problem:

> Most current “golden” tests do not load external golden files and do not compare a full actual output object against an independent expected output.

They are mostly deterministic scenario/contract tests with “golden” in the name.

That is not bad, but it is not enough.

---

# 2. What a true golden test should be

A true golden test should have this structure:

```text
GIVEN:
  fixed clock
  fixed home currency
  deterministic seed/input data
  deterministic fake external providers

WHEN:
  run the real production path

THEN:
  serialize actual business output to stable JSON

COMPARE:
  actual JSON == expected golden JSON
```

Example:

```text
notification_review_dashboard_budget/
  seed.json
  input.json
  expected-dashboard.json
  expected-analytics.json
  expected-budget.json
  expected-events.json
  expected-diagnostics.json
```

The golden expected output should be independent from the production implementation. It should say:

```json
{
  "homeCurrency": "EUR",
  "dashboardMonthlyTotal": 145.50,
  "categoryTotals": {
    "Groceries": 95.50,
    "Shopping": 50.00
  },
  "budget": {
    "Groceries": {
      "limit": 100.00,
      "spent": 95.50,
      "remaining": 4.50,
      "severity": "WARNING"
    }
  },
  "transactionEvents": [
    "CREATED",
    "CREATE_DUPLICATE_SKIPPED",
    "CREATED_FROM_REVIEW"
  ],
  "duplicatesSkipped": 1,
  "isPartial": false
}
```

This is much stronger than asserting:

```kotlin
assertThat(result).isNotNull()
assertThat(rows).hasSize(2)
```

---

# 3. Evaluation of current golden tests

## 3.1 `GoldenScenarioVerifier.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/testfixtures/golden/GoldenScenarioVerifier.kt

## Verdict

**Fix urgently. Current behavior is unsafe.**

## What it does

It tries to compare actual `JSONObject` output against:

```text
golden/{scenarioName}.json
```

## Main issue

If the golden file is missing, it prints a warning and returns success.

That means CI can pass even when the expected output file does not exist.

This defeats the point of golden testing.

Current behavior:

```text
Golden file not found
→ accept actual output as baseline
→ return true
```

## Why this is dangerous

A test can look like a golden test but validate nothing.

Example:

```kotlin
val passed = verifier.verifyJson(actual)
assertTrue(passed)
```

If the file is missing, this always passes.

## Required change

Default behavior must be:

```text
missing golden file = test failure
```

Only allow baseline generation with an explicit flag:

```text
-PupdateGoldens=true
```

## Also missing

The verifier should support:

- `JSONArray`
- numeric tolerance
- ignored unstable fields
- sorted arrays by key where ordering is not contractually important
- stable pretty-printed diffs
- update mode
- fail-fast in CI
- clear error message showing expected path

## Recommended action

**KEEP as infrastructure, but rewrite before trusting any golden tests.**

---

## 3.2 `GoldenScenarioSmokeTest.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/GoldenScenarioSmokeTest.kt

## Verdict

**Delete or collapse into one tiny fixture smoke test.**

## What it tests

It verifies:

- `ScenarioSeeder.seedState()` inserts a simple expense.
- A manually built `MoneyAggregate.partial(...)` has expected fields.
- A fake inline `PrivacyGate` returns denied/allowed decisions.

## What is good

The intent is good:

- no raw mixed-currency totals
- partial money aggregate
- privacy gate denied path

## What is weak

It does **not** test real app behavior.

The multi-currency test manually constructs:

```kotlin
MoneyAggregate.partial(...)
```

That does not prove:

- repository aggregation works
- currency converter is called
- stale/missing rates are detected
- dashboard exposes warnings
- analytics preserves partial state
- budget handles normalized values

The privacy test uses a fake gate inside the test. It proves the fake object returns what the fake object was coded to return.

## Recommended action

Delete this as a “golden” test.

Replace with two real tests:

```text
MulticurrencyPartialRateGoldenTest
PrivacyAiRedactionGoldenTest
```

---

## 3.3 `SpendingPaceGoldenTest.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/analytics/SpendingPaceGoldenTest.kt

## Verdict

**Keep. This is one of the better golden-style tests.**

## What it tests

It creates a realistic March/February dataset and checks:

- current month spent
- days elapsed
- projected total
- pace percentage
- pace status
- last-day projection equals actual month spend

## Why it is valuable

This is close to a real golden test because it has:

- deterministic input data
- explicit expected numeric outputs
- real production calculator
- fixed clock
- realistic edge cases:
  - deposits
  - shared expense effective amount
  - previous month comparison

This is the right direction.

## Weaknesses

It is still not a full golden file test.

Expected outputs are embedded in Kotlin instead of external JSON.

Also, it uses `Calendar.getInstance()` / default timezone behavior indirectly. That can be dangerous for date-boundary analytics.

## Improvements

Add assertions for:

```text
deposits excluded
shared expense uses effective amount
transaction count
previous month baseline
warning/partial flags if currency is partial
```

Move dataset into shared fixture or JSON.

Recommended future golden output:

```json
{
  "scenario": "spending_pace_march_2026_day_15",
  "currentMonthSpent": 991.79,
  "daysElapsed": 15,
  "projectedTotal": 2049.70,
  "pacePercentage": 175.0,
  "paceStatus": "OVER_PACE",
  "excludedDeposits": 1,
  "sharedExpenseEffectiveAmountUsed": true
}
```

## Action

**KEEP and upgrade.**

---

## 3.4 `GroupGoldenScenarioTest.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/GroupGoldenScenarioTest.kt

## Verdict

**Keep, but it is not really golden yet. Rename or upgrade.**

## What it tests

It uses real Room DB and `GroupLifecycleCoordinator`.

It covers:

- settlement persistence
- foreign-currency group expense rejection
- member removal when no balance
- hard delete blocked unless archived
- member removal blocked when unsettled
- group lifecycle event persistence

## Why it is valuable

This is a good test because it exercises real production-ish group lifecycle code and real DB persistence.

The best part is:

```text
groupRejectsForeignCurrencyExpense
```

This protects a critical financial invariant: group expenses should not mix currencies unsafely.

## Weaknesses

It is not a true golden test because:

- no golden expected file
- no serialized full output
- direct seeding bypasses some group creation logic
- balance calculator is mocked
- budget monitor is mocked
- dashboard/analytics/budget offset behavior is not checked
- settlement “affects balance” is in the test name, but the test mostly checks settlement row persistence

## What to add

A true group golden should assert:

```text
group balances
settlement suggestions
gross spending total
net budget-offset amount
group lifecycle events
system expense created through lifecycle
reimbursement treatment
dashboard contract
analytics contract
```

Example scenario:

```text
Alice pays 90 EUR dinner
Bob owes 30
Carol owes 30
Bob reimburses Alice 30
Dining budget = 200
```

Expected:

```json
{
  "groupBalances": {
    "Alice": 30.00,
    "Bob": 0.00,
    "Carol": -30.00
  },
  "settlements": [
    {
      "from": "Carol",
      "to": "Alice",
      "amount": 30.00,
      "currency": "EUR"
    }
  ],
  "grossDiningSpend": 90.00,
  "netBudgetSpendForCurrentUser": 30.00,
  "events": [
    "GROUP_CREATED",
    "GROUP_EXPENSE_ADDED",
    "SETTLEMENT_RECORDED"
  ]
}
```

## Action

**KEEP, rename to `GroupLifecycleContractTest`, then add a real `GroupSettlementGoldenTest`.**

---

## 3.5 `InvestmentGoldenScenarioTest.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/InvestmentGoldenScenarioTest.kt

## Verdict

**Keep, but strengthen significantly.**

## What it tests

It covers:

- adding holding atomically
- value snapshot creation
- BUY transaction creation
- portfolio summary aggregate with EUR and USD holdings
- investment performance data-quality field

## Why it is useful

This is important because investment tracking is a weakly covered area. The test verifies real DB side effects:

```text
Investment row
InvestmentValue snapshot
InvestmentTransaction BUY row
```

That is good.

## Weaknesses

The portfolio aggregate test is too shallow.

It checks:

```text
sourceBuckets has EUR and USD
dataQuality.isPartial == false
```

But it does not assert the exact converted display value.

Also, the currency converter is mocked with a simplistic conversion. It appears to multiply all amounts by `0.92`, which may hide whether EUR amounts are being treated correctly.

## What should be asserted

For this input:

```text
Siemens: 5 × 190 EUR = 950 EUR
Tesla:   3 × 260 USD = 780 USD
USD→EUR = 0.92
```

Expected:

```text
source buckets:
- EUR 950
- USD 780

display total EUR:
950 + 717.60 = 1667.60

partial:
false
```

Also add stale/missing price cases:

```text
crypto holding missing price
USD stock stale FX rate
```

Expected:

```text
isPartial = true
warning includes MISSING_PRICE or RATE_STALE
```

## Action

**KEEP but rewrite the aggregate assertions.**

---

## 3.6 `TaxGoldenScenarioTest.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/TaxGoldenScenarioTest.kt

## Verdict

**Split this file. It contains one good test, one weak test, and one nearly useless test.**

## Test 1: `taxEstimatePopulatesMoneyAggregateFields`

### Value

Medium-low.

### Problem

It uses mocks for the DAO, converter, settings, and rate provider. It mostly asserts:

```text
deductibleAggregate is not null
sourceBuckets not empty
deductibleExpenses > 0
vatAggregate not null
displayAmount equals deductibleExpenses
```

That proves wiring, not tax correctness.

It does not assert exact:

- deductible total
- VAT portion
- country-specific rate
- filing currency behavior
- mixed-currency partial behavior
- tax category treatment
- business expense inclusion/exclusion

### Rewrite as true golden

Input:

```text
home/filing currency: EUR
country: GR
business expenses:
- 1200 EUR
- 400 USD at 0.92 = 368 EUR
VAT rate: 24%
mileage: 150 km × 0.30 = 45 EUR
```

Expected:

```json
{
  "deductibleExpenses": 1613.00,
  "deductibleAggregate": {
    "displayAmount": 1613.00,
    "displayCurrency": "EUR",
    "sourceBuckets": {
      "EUR": 1245.00,
      "USD": 400.00
    }
  },
  "estimatedVatPortion": 312.19,
  "isPartial": false
}
```

The exact VAT math depends on your contract: VAT included vs added. The point is: define it and assert exact values.

---

## Test 2: `mileageFallbackUsesDistanceTimesRate`

### Value

High.

This is a good contract test.

It uses real DB and verifies:

```text
if calculatedDeduction is null:
  use distanceKm × deductionRatePerKm

if calculatedDeduction exists:
  use stored value
```

This protects real financial behavior.

### Action

Keep this, but move it to:

```text
BusinessExpenseRepositoryMileageTest.kt
```

or keep in tax package as a repository contract test.

---

## Test 3: `tax estimate uses TaxRateProvider as supplementary source`

### Value

Very low.

It mocks `TaxRateProvider`, stubs a return, then only asserts the mock is not null.

This should be deleted or rewritten.

A real test should call `estimateTaxes()` and assert the provider result changes the tax estimate.

## Action summary

```text
taxEstimatePopulatesMoneyAggregateFields → REWRITE
mileageFallbackUsesDistanceTimesRate → KEEP
tax estimate uses TaxRateProvider... → DELETE or fully rewrite
```

---

## 3.7 `GoldenDataSets.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/analytics/fixtures/GoldenDataSets.kt

## Verdict

**Useful idea, but harden before relying on it.**

## What is good

It centralizes deterministic datasets:

- simple month
- split transaction
- excluded transaction
- percentage split
- previous month
- recurring merchant
- mixed transaction types
- day-of-week spread
- anomaly scenario
- complex scenario

This is good. A shared canonical dataset prevents every test from inventing its own inputs.

## Problems

### Problem 1 — uses system default timezone

It uses:

```kotlin
ZoneId.systemDefault()
```

Golden datasets should use:

```kotlin
ZoneOffset.UTC
```

or one explicit business timezone.

Otherwise results can differ across machines.

### Problem 2 — uses `System.currentTimeMillis()`

The helper assigns IDs/createdAt using current time in some places.

Golden tests should never use real current time.

Use fixed IDs and fixed timestamps.

### Problem 3 — expected period constants look suspicious

`ExpectedResults.MonthPeriods` claims March 2026, but values like `1767224400000L` are around January 2026 depending on timezone.

That means the expected constants may be stale or wrong.

This is exactly why golden fixtures need strict validation.

### Problem 4 — Kotlin fixture, not external golden

Kotlin constants are okay for engine tests, but for true golden scenario tests you want JSON expected output resources.

## Action

Keep the concept, but refactor:

```text
GoldenDataSets.kt → deterministic fixture builder
ExpectedResults.kt → generated/readable golden JSON
```

---

## 3.8 `ExpectedResults.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/analytics/fixtures/ExpectedResults.kt

## Verdict

**Keep, but verify and move toward JSON golden files.**

## What is good

It gives independent expected totals like:

```text
simple month total = 60
split effective = 50
excluded total = 100
mixed purchase total = 30
complex total = 260
```

This is exactly the right pattern.

## Weaknesses

It is incomplete for the current app complexity.

Missing expected outputs:

- currency source buckets
- converted display totals
- partial flags
- conversion failures
- warning types
- budget status
- dashboard cards
- event logs
- diagnostic events
- recurring occurrence statuses
- group balances
- privacy audit events

## Action

Keep for pure analytics engine tests, but create external expected JSON for multi-pipeline scenarios.

---

# 4. Are these tests deep enough?

## Short answer

No, not yet.

They are useful, but most are not deep enough for the complexity of your app.

Current golden tests mostly cover:

```text
single engine or single coordinator behavior
```

They do not yet strongly cover:

```text
input pipeline → lifecycle → DB/event log → analytics/dashboard/budget/export
```

That cross-pipeline layer is where your real bugs will happen.

---

# 5. What golden tests should be added

These are the golden tests I would add first.

## 5.1 `multicurrency_analytics_dashboard_golden`

## Why

This is probably the most important numeric golden test.

## Input

```text
home currency: EUR
expenses:
- 100 EUR groceries
- 50 USD shopping, rate 0.90
- 40 GBP travel, rate 1.15
- 20 CHF dining, missing rate
```

## Expected

```json
{
  "homeCurrency": "EUR",
  "sourceBuckets": {
    "EUR": 100.00,
    "USD": 50.00,
    "GBP": 40.00,
    "CHF": 20.00
  },
  "displayTotal": 191.00,
  "isPartial": true,
  "conversionFailures": [
    {
      "currency": "CHF",
      "reason": "MISSING_RATE"
    }
  ],
  "dashboardWarning": "PARTIAL_CONVERSION",
  "analyticsWarning": "PARTIAL_CONVERSION"
}
```

## Must prove

- no raw mixed-currency sum
- source buckets preserved
- partial warning propagated
- dashboard and analytics agree

---

## 5.2 `notification_review_dashboard_budget_golden`

## Input

```text
Greek bank notification
Revolut notification
duplicate notification
low-confidence notification
monthly budget
category rules
```

## Expected

```json
{
  "rawNotifications": 4,
  "pendingReviewBeforeApproval": 1,
  "expensesCreated": 3,
  "duplicatesSkipped": 1,
  "transactionEvents": [
    "CREATED",
    "CREATED",
    "CREATE_DUPLICATE_SKIPPED",
    "CREATED_FROM_REVIEW"
  ],
  "dashboardMonthlyTotal": 145.50,
  "analyticsCategoryTotals": {
    "Groceries": 95.50,
    "Shopping": 50.00
  },
  "budget": {
    "Groceries": {
      "spent": 95.50,
      "remaining": 4.50,
      "severity": "WARNING"
    }
  }
}
```

## Must prove

- real parser
- real review/lifecycle path
- real dedupe
- real dashboard/analytics/budget output

---

## 5.3 `receipt_matching_analytics_golden`

## Input

```text
existing bank transaction:
- Public, 89.99 EUR

receipt OCR/email:
- Public
- 89.99 EUR
- same day
- line items
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
  "receiptExpenseLinks": 1,
  "newExpensesCreated": 0,
  "analyticsTotal": 89.99,
  "countedOnce": true,
  "receiptEvents": [
    "CAPTURED",
    "PARSED",
    "MATCHED"
  ]
}
```

## Must prove

- matching does not double-count
- receipt link is persisted
- analytics sees one transaction only

---

## 5.4 `recurring_planned_actual_no_double_count_golden`

## Input

```text
Netflix recurring rule: 12.99 EUR monthly
planned May occurrence
actual May payment
future June/July occurrences
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
  "dashboardMayTotal": 12.99,
  "forecastFutureRecurringTotal": 25.98,
  "doubleCounted": false
}
```

## Must prove

- actual linked to planned occurrence
- current dashboard counts actual once
- future forecast still includes future planned cost

---

## 5.5 `analytics_dashboard_budget_parity_golden`

## Why

This should become one of your most important tests.

## Input

Same seeded month used by:

- dashboard
- analytics
- budget
- forecast input
- export

## Expected

```json
{
  "dashboardTotal": 1030.00,
  "analyticsTotal": 1030.00,
  "budgetSpent": 1030.00,
  "categoryTotalsMatch": true,
  "normalizationInputHash": "stable-fixture-v1"
}
```

Where contracts differ, assert the difference explicitly:

```json
{
  "dashboardGrossGroupSpend": 90.00,
  "budgetNetGroupSpend": 30.00,
  "differenceIsExpected": true,
  "reason": "BUDGET_USES_CURRENT_USER_SHARE"
}
```

---

## 5.6 `backup_restore_roundtrip_golden`

## Input

Seed DB with:

```text
expenses
transaction events
receipts
receipt links
recurring occurrences
groups
settlements
exchange rates
privacy audit events
background job runs
```

## Expected

```json
{
  "beforeRestore": {
    "dashboardMonthlyTotal": 1234.56,
    "receiptLinks": 3,
    "groupBalancesHash": "..."
  },
  "afterRestore": {
    "dashboardMonthlyTotal": 1234.56,
    "receiptLinks": 3,
    "groupBalancesHash": "..."
  },
  "restoreJournal": [
    "STARTED",
    "WORKERS_PAUSED",
    "DB_RESTORED",
    "VERIFIED",
    "WORKERS_RESUMED"
  ]
}
```

## Must prove

- restored app outputs match original outputs
- restore does not corrupt financial state
- workers pause/resume correctly

---

## 5.7 `privacy_ai_redaction_gate_golden`

## Input

```text
cloud AI disabled
external geocoding disabled
sensitive receipt/notification/query
```

## Expected

```json
{
  "cloudProviderCalls": 0,
  "locationProviderCalls": 0,
  "privacyAuditEvents": [
    "CLOUD_AI_DENIED",
    "LOCATION_DENIED"
  ],
  "rawSensitiveTextStored": false,
  "redactedPayloadContainsSensitiveCard": false,
  "userFacingState": "PRIVACY_DENIED"
}
```

## Must prove

- privacy fails closed
- audit is written
- sensitive data is not leaked

---

# 6. How to fix current 195 failing tests using golden priority

Do not try to fix all 195 equally.

Use this order:

## P0 — golden infrastructure

Fix first:

```text
GoldenScenarioVerifier.kt
GoldenDataSets.kt
ExpectedResults.kt
```

Because if the golden infrastructure lies, all golden tests are suspect.

## P1 — true numeric golden tests

Fix/keep first:

```text
SpendingPaceGoldenTest.kt
Multicurrency/currency aggregate tests
Analytics/dashboard/budget parity tests
```

## P2 — lifecycle golden/contract tests

Fix/keep:

```text
GroupGoldenScenarioTest.kt
InvestmentGoldenScenarioTest.kt
TransactionLifecycleCoordinatorDbContractTest.kt
Receipt lifecycle scenario
Recurring no-double-count scenario
```

## P3 — rewrite misleading golden tests

Rewrite:

```text
GoldenScenarioSmokeTest.kt
TaxGoldenScenarioTest.kt
```

## P4 — delete weak tests

Delete or quarantine:

```text
mock-not-null tests
source text assertion tests
fake scenario tests that only seed DAO rows
```

---

# 7. My exact recommendations by file

## Keep and improve

```text
domain/analytics/SpendingPaceGoldenTest.kt
scenarios/GroupGoldenScenarioTest.kt
scenarios/InvestmentGoldenScenarioTest.kt
domain/analytics/fixtures/GoldenDataSets.kt
domain/analytics/fixtures/ExpectedResults.kt
```

## Rewrite before trusting as golden

```text
testfixtures/golden/GoldenScenarioVerifier.kt
scenarios/TaxGoldenScenarioTest.kt
```

## Delete or collapse

```text
scenarios/GoldenScenarioSmokeTest.kt
```

## Split

```text
scenarios/TaxGoldenScenarioTest.kt
```

Suggested split:

```text
domain/tax/TaxEstimatorGoldenTest.kt
data/repository/BusinessExpenseMileageDeductionTest.kt
```

Delete the current “TaxRateProvider is not null” style test unless it is rewritten to assert real estimator output.

---

# 8. Final assessment

Your instinct is correct: golden tests are the most important tests.

But right now, the current golden layer is not yet strong enough to be the backbone of the suite.

The goal should be:

```text
10–15 canonical golden tests
+ strict golden verifier
+ deterministic fixture data
+ exact expected JSON outputs
+ real production paths
```

If you achieve that, you can safely delete many shallow mock/outdated tests because the golden tests will protect actual business behavior better.

The best first PR would be:

1. Fix `GoldenScenarioVerifier` so missing files fail.
2. Add `-PupdateGoldens=true` support.
3. Move golden expected outputs to `app/src/test/resources/golden/`.
4. Convert `SpendingPaceGoldenTest` to emit/compare stable JSON.
5. Convert `GroupGoldenScenarioTest` into either:
   - `GroupLifecycleContractTest`, or
   - a real `group_settlement_budget_offset` golden scenario.
6. Delete `GoldenScenarioSmokeTest`.

After that, start adding the missing canonical golden scenarios:
   
```text
multicurrency_analytics_dashboard_golden
notification_review_dashboard_budget_golden
receipt_matching_analytics_golden
recurring_planned_actual_no_double_count_golden
backup_restore_roundtrip_golden
privacy_ai_redaction_gate_golden
analytics_dashboard_budget_parity_golden
```

Those will give much more value than maintaining 195 outdated shallow tests.