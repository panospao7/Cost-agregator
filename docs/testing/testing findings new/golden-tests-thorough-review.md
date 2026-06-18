# Thorough golden-test review

Target checked: GitHub commit `18482021294eba1d209afa2deb34aea6c107a52f`  
Caveat: your local overhaul may differ. If your local branch has changed, run the commands at the end and send me the inventory.

## 1. Main conclusion

There are more golden/golden-like tests than the first pass mentioned.

Found important additional golden tests:

```text
domain/budget/BudgetCalculatorGoldenTest.kt
domain/currency/CurrencyConverterGoldenTest.kt
domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt
domain/health/HealthScoreGoldenTest.kt
domain/logic/SplitCalculatorGoldenTest.kt
domain/logic/SynthesisEngineGoldenTest.kt
domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt
metrics/GoldenAnalyticsDatasetTest.kt
verification/GoldenMasterVerificationTest.kt
```

Plus previously discussed:

```text
domain/analytics/SpendingPaceGoldenTest.kt
scenarios/GoldenScenarioSmokeTest.kt
scenarios/GroupGoldenScenarioTest.kt
scenarios/InvestmentGoldenScenarioTest.kt
scenarios/TaxGoldenScenarioTest.kt
testfixtures/golden/GoldenScenarioVerifier.kt
metrics/GoldenAnalyticsDataset.kt
```

But almost all are **golden-style Kotlin snapshot/fixture tests**, not real golden-file tests.

A true golden test should compare actual production output against committed expected JSON/resources. Your `app/src/test/resources` currently only shows:

```text
OCR_TEST_DOCUMENT.txt
robolectric.properties
```

So there is no visible committed `golden/` or `scenarios/` expected-output resource folder at this commit.

Also, `GoldenScenarioVerifier` currently accepts missing golden files as passing. That must be fixed before golden tests can become release gates.

---

# 2. Golden infrastructure finding

## `testfixtures/golden/GoldenScenarioVerifier.kt`

Verdict: **critical infrastructure, but currently unsafe**

Current behavior:

```text
missing golden file -> warning -> return true
```

That means a “golden” test can pass without any golden expected output.

Required fix:

```text
default CI:
  missing golden = fail
  mismatch = fail

explicit update mode:
  -PupdateGoldens=true
```

Also add:

- JSON array support
- numeric tolerances
- ignored unstable fields
- sorted arrays by key
- readable diff output
- explicit expected resource path in failure message

Until this is fixed, do not treat any file-based golden result as trustworthy.

---

# 3. Golden/golden-like tests found and evaluation

## 3.1 `domain/budget/BudgetCalculatorGoldenTest.kt`

Verdict: **KEEP, but rename or expand**

What it tests:

- calendar monthly period range
- rolling monthly anchored period
- yearly anniversary window

Value: **high for date/budget-boundary correctness**

Weakness:

- not really a full budget golden
- only period range, not budget status, severity, rollovers, category budgets, shared-expense offsets, or multi-currency budget normalization
- uses `Calendar.getInstance()`, so timezone behavior may affect it

Recommended action:

Keep as:

```text
BudgetPeriodRangeContractTest
```

Add a real golden:

```text
BudgetStatusGoldenTest
```

Expected golden should include:

```json
{
  "periodStart": "2026-03-01",
  "periodEndExclusive": "2026-04-01",
  "limit": 1000.00,
  "spent": 738.49,
  "remaining": 261.51,
  "percentUsed": 73.849,
  "severity": "ON_TRACK"
}
```

---

## 3.2 `domain/currency/CurrencyConverterGoldenTest.kt`

Verdict: **KEEP and expand**

What it tests:

- same-currency conversion returns unchanged amount
- GBP→JPY cross-rate via EUR works

Value: **high**

This is important because multi-currency is one of the most dangerous parts of the app.

Weakness:

- no missing-rate case
- no stale-rate case
- no historical transaction-date rate case
- no partial aggregate propagation to dashboard/analytics
- mocked exchange store, no repository/DB rate lookup

Add tests:

```text
historical transaction date uses historical rate
stale rate returns RATE_STALE failure
missing rate returns MISSING_RATE failure
partial conversion propagates to MoneyAggregate
```

Expected example:

```json
{
  "input": "100 USD on 2026-02-01",
  "homeCurrency": "EUR",
  "rateUsed": 0.90,
  "converted": 90.00,
  "rateDate": "2026-02-01",
  "isPartial": false
}
```

---

## 3.3 `domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt`

Verdict: **KEEP**

What it tests:

- deterministic Monte Carlo output with seed `42`
- exact p10/p25/p50/p75/p90-ish snapshot with tolerance
- iteration count

Value: **high**

This is a good golden-style test because probabilistic code needs deterministic snapshots.

Weakness:

- distribution and quality assessor are mocked
- not connected to forecast input assembler
- not connected to recurring/planned expenses
- no missing/partial currency confidence degradation

Add a higher-level golden:

```text
ForecastPipelineGoldenTest
```

Path:

```text
expenses + recurring + planned + budget
→ ForecastInputAssembler
→ MonteCarloSpendingSimulator
→ dashboard forecast card
```

Expected:

```json
{
  "p10": 1781.63,
  "p50": 2072.41,
  "p90": 2484.39,
  "confidence": "HIGH",
  "knownUpcoming": 300.00,
  "partialCurrency": false
}
```

---

## 3.4 `domain/health/HealthScoreGoldenTest.kt`

Verdict: **KEEP, but it is mock-heavy**

What it tests:

- March fixture health score = 57
- new user default score = 55
- deposits/shared expense effective amount are represented in input

Value: **medium-high**

This covers an under-tested area: financial health scoring.

Weakness:

- many repositories mocked
- no real budgets/goals/recurring/cashflow
- no currency partial-rate handling
- no persisted health history/trend scenario

Add a real golden:

```text
FinancialHealthScenarioGoldenTest
```

Expected:

```json
{
  "overallScore": 57,
  "savingsRateScore": 100,
  "runwayScore": 0,
  "budgetAdherenceScore": 50,
  "billReliabilityScore": 75,
  "trend": "STABLE",
  "warnings": []
}
```

Also add variant:

```text
partial currency data lowers confidence or marks score partial
```

---

## 3.5 `domain/logic/SplitCalculatorGoldenTest.kt`

Verdict: **KEEP. This is a good pure golden/contract test.**

What it tests:

- equal split of 100 among 3
- equal split of 100 among 7
- percentage split with cent preservation
- tie-break remainder behavior

Value: **high**

This protects financial cent-level correctness.

Weakness:

- uses `Double`
- not connected to group settlement/budget offset
- no negative/refund split
- no currency enforcement

Add:

```text
negative reimbursement split
zero amount split
single participant split
group settlement golden using these splits
```

This file should stay as pure engine coverage.

---

## 3.6 `domain/logic/SynthesisEngineGoldenTest.kt`

Verdict: **KEEP and expand**

What it tests:

- recurring confidence classification
- biweekly recurrence tolerance
- discretionary base-rate logic with strict savings reserve

Value: **high**

This protects important forecast synthesis behavior.

Weakness:

- no DB/repository path
- not connected to recurring lifecycle
- not connected to dashboard forecast
- no partial currency input
- no planned-vs-actual no-double-count case

Add true scenario:

```text
RecurringForecastSynthesisGoldenTest
```

Path:

```text
recurring rule
→ occurrence generation
→ planned expense
→ actual linked payment
→ SynthesisEngine
→ forecast/dashboard
```

Expected:

```json
{
  "committed": 120.00,
  "likely": 80.00,
  "excludedLowConfidence": 60.00,
  "forecastConfidence": 0.85,
  "dashboardCountsActualOnce": true
}
```

---

## 3.7 `domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt`

Verdict: **KEEP, but it is minimal**

What it tests:

- round-up 17.30 to nearest 5.00 gives 2.70

Value: **medium-high**

Good because the expected value is clear and independent.

Weakness:

- only one rule
- repositories mostly mocked
- no persisted idempotency
- no goal update
- no currency normalization
- no duplicate transaction guard

Add:

```text
AutomatedSavingsGoldenScenarioTest
```

Expected:

```json
{
  "expense": 17.30,
  "roundUpTo": 5.00,
  "savingsAmount": 2.70,
  "goalContributionCreated": true,
  "duplicateEvaluationSkipped": true
}
```

---

## 3.8 `metrics/GoldenAnalyticsDataset.kt`

Verdict: **KEEP as fixture data, not as a golden test**

What it contains:

- basic monthly total
- split effective amount
- mixed transaction types
- half-open date boundaries
- empty period
- shared expense filtering
- category breakdown

Value: **high as canonical fixture**

Good:

- uses UTC
- explicit scenario IDs
- clear expected numbers

Weakness:

- limited to simple analytics
- no multi-currency
- no budget/dashboard parity
- no recurring/receipt/group/privacy
- expected outputs are Kotlin constants, not JSON resources

Recommended:

Use this as `Dataset A`, then add:

```text
GoldenAnalyticsDatasetV2
```

with:

```text
multi-currency partial
receipt match
recurring planned/actual
group reimbursement
privacy denied
backup/restore
```

---

## 3.9 `metrics/GoldenAnalyticsDatasetTest.kt`

Verdict: **REWRITE partially**

What it does:

- validates fixture totals using local helper functions inside the test

Value: **medium**

Problem:

It does not call production analytics engines. It reimplements:

```text
purchaseMetrics
cashFlowMetrics
categoryBreakdown
```

inside the test.

That makes it more of a fixture sanity test than a production golden test.

Keep one fixture sanity test, but add production golden tests:

```text
GoldenAnalyticsDatasetAgainstTotalsEngineTest
GoldenAnalyticsDatasetAgainstDashboardTest
GoldenAnalyticsDatasetAgainstBudgetTest
```

---

## 3.10 `verification/GoldenMasterVerificationTest.kt`

Verdict: **important but risky; split it**

Value: **potentially very high**

This is the broadest golden/master-style test. It compares/parities across:

- InsightsEngine
- AdvancedAnalyticsEngine
- TotalsAggregationEngine
- Dashboard
- SpendingPaceCalculator
- BudgetForecastingEngine
- SmartSavingsEngine
- Monte Carlo mock

This is the kind of cross-engine parity you need.

But it has major issues:

1. It is too large.
2. It mixes parity tests, divergence tests, edge cases, anomaly tests, and mocked predictor tests.
3. Some tests seem to freeze existing divergence rather than expected product behavior.
4. The most concerning one is:

```text
DIVERGENCE - dashboard uses raw amount while analytics engines use effectiveAmount
```

If dashboard truly uses raw amount and analytics uses effective amount, that may be a bug unless the product explicitly defines dashboard as gross spending and analytics as effective spending. A golden test should not preserve accidental divergence.

Recommended split:

```text
AnalyticsDashboardParityGoldenTest
AnalyticsExpectedDivergenceContractTest
SpendingPaceGoldenTest
SmartSavingsGoldenTest
AnomalyDetectionGoldenTest
ForecastDivergenceContractTest
```

For every divergence, require a business reason field:

```json
{
  "divergence": "dashboardGrossVsAnalyticsEffective",
  "isExpected": true,
  "businessReason": "Dashboard card intentionally shows gross total; budget uses user-share effective amount"
}
```

If you cannot write that reason, the divergence test should fail and the product behavior should be fixed.

---

## 3.11 `scenarios/GoldenScenarioSmokeTest.kt`

Verdict: **DELETE or collapse**

This is not a real golden test.

It manually constructs:

```text
MoneyAggregate.partial(...)
fake PrivacyGate
```

It mostly proves the test fixture works.

Replace with real:

```text
MulticurrencyPartialRateGoldenTest
PrivacyAiRedactionGoldenTest
```

---

## 3.12 `scenarios/GroupGoldenScenarioTest.kt`

Verdict: **KEEP, but upgrade**

Value: **high**

It touches an important weak/complex area: group lifecycle.

But it is not true golden because no expected JSON and no full group financial output.

Upgrade into:

```text
GroupSettlementBudgetOffsetGoldenTest
```

Expected should include:

```json
{
  "groupBalances": {
    "Alice": 30.00,
    "Bob": 0.00,
    "Carol": -30.00
  },
  "settlementSuggestions": [
    {
      "from": "Carol",
      "to": "Alice",
      "amount": 30.00,
      "currency": "EUR"
    }
  ],
  "grossSpend": 90.00,
  "budgetEffectiveSpend": 30.00,
  "events": [
    "GROUP_EXPENSE_ADDED",
    "SETTLEMENT_RECORDED"
  ]
}
```

---

## 3.13 `scenarios/InvestmentGoldenScenarioTest.kt`

Verdict: **KEEP and strengthen**

Value: **medium-high**

This covers a historically weak area: investment tracking.

Weakness:

- not enough exact numeric assertions
- no missing/stale price
- no FX partial warning
- no dashboard investment card assertion

Add:

```text
InvestmentPortfolioMulticurrencyGoldenTest
```

Expected:

```json
{
  "sourceBuckets": {
    "EUR": 950.00,
    "USD": 780.00
  },
  "displayCurrency": "EUR",
  "displayTotal": 1667.60,
  "isPartial": false,
  "gainLoss": 123.45
}
```

Add missing-price variant:

```json
{
  "isPartial": true,
  "warnings": ["MISSING_PRICE", "STALE_FX_RATE"]
}
```

---

## 3.14 `scenarios/TaxGoldenScenarioTest.kt`

Verdict: **SPLIT**

Contains:

1. weak tax aggregate test
2. good mileage fallback test
3. useless TaxRateProvider-not-null test

Recommended:

```text
taxEstimatePopulatesMoneyAggregateFields -> rewrite as exact TaxEstimatorGoldenTest
mileageFallbackUsesDistanceTimesRate -> keep as repository contract
tax estimate uses TaxRateProvider as supplementary source -> delete or rewrite
```

True tax golden should assert exact:

```json
{
  "filingCurrency": "EUR",
  "deductibleExpenses": 1613.00,
  "vatPortion": 312.19,
  "sourceBuckets": {
    "EUR": 1245.00,
    "USD": 400.00
  },
  "isPartial": false
}
```

---

# 4. Other golden-like scenario tests that should become real golden tests

These are not named golden, but they are strong candidates.

## 4.1 `scenarios/MixedCurrencyCoreFinancialScenarioTest.kt`

Verdict: **convert into true golden or move to domain money**

Current weakness:

It seeds DB, then manually builds `MoneyAggregate`.

Good intent:

- missing rates
- stale rate reason
- single-currency clean aggregate

But it does not prove the real dashboard/analytics/currency pipeline.

Upgrade into:

```text
MulticurrencyAnalyticsDashboardGoldenTest
```

Path:

```text
expenses + rates
→ currency normalizer
→ analytics
→ dashboard
→ budget
```

---

## 4.2 `scenarios/MoneyAggregateConversionScenarioTest.kt`

Verdict: **split**

It covers:

- warranties
- subscriptions
- investments
- `MoneyAmount` cross-currency safety
- partial aggregates

But much is manually built.

Split into:

```text
MoneyAmountContractTest
WarrantyMoneyAggregateGoldenTest
SubscriptionMoneyAggregateGoldenTest
InvestmentPortfolioGoldenTest
```

Keep the cross-currency-addition test as pure domain.

---

## 4.3 `scenarios/BackupRestoreMoneyIntegrityScenarioTest.kt`

Verdict: **not enough for backup/restore golden**

It checks:

- migration entries exist
- DAOs are non-null
- one seeded expense reads back

This is not a real backup/restore roundtrip.

Add:

```text
BackupRestoreFullAppGoldenTest
```

Expected:

```json
{
  "before": {
    "dashboardTotal": 1234.56,
    "receiptLinks": 3,
    "groupSettlements": 2,
    "privacyAuditEvents": 4
  },
  "after": {
    "dashboardTotal": 1234.56,
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
  ]
}
```

---

## 4.4 `scenarios/PrivacyGateEnforcementScenarioTest.kt`

Verdict: **good contract, not full golden**

Good:

- uses real privacy gates
- checks denials
- verifies audit logger interactions

Missing:

- persisted privacy settings
- provider call prevention across AI/location/notification/backup
- redaction output
- raw sensitive text storage policy
- user-facing denial state

Add:

```text
PrivacyAiRedactionGoldenTest
```

Expected:

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
  "rawSensitiveTextStored": false
}
```

---

# 5. Areas with no or weak golden coverage

## Highest-priority missing golden tests

### 1. Notification → review → lifecycle → dashboard/budget/analytics

Current state:

There is `NotificationPipelineScenarioTest`, but it is not a real golden and likely still shallow/parser-heavy.

Needed:

```text
NotificationReviewDashboardBudgetGoldenTest
```

Must assert:

```text
raw notification count
review queue count
approved expense count
duplicate skipped count
transaction events
dashboard total
budget remaining/severity
analytics category totals
pipeline diagnostics
```

---

### 2. Receipt matching → analytics no-double-count

Current state:

There is receipt DAO/scenario coverage, but no strong golden.

Needed:

```text
ReceiptMatchingAnalyticsGoldenTest
```

Must assert:

```text
receipt status sequence
receipt events
receipt-expense link
no duplicate expense
analytics total counted once
warranty/price side effects gated
```

---

### 3. Recurring planned → actual no-double-count

Current state:

You have recurring tests, but no true golden.

Needed:

```text
RecurringPlannedActualGoldenTest
```

Must assert:

```text
occurrence statuses
reminder deliveries
actual linked to occurrence
dashboard counts actual once
forecast includes future planned only
budget current period uses actual once
```

---

### 4. Bank sync failure/recovery lifecycle

Current state:

There is `BankSyncScenarioTest`, but it likely does direct DB insert.

Needed:

```text
BankSyncFailureRecoveryGoldenTest
```

Must assert:

```text
expired token state
partial sync safety
duplicate skipped
low-confidence review item
approved expense via lifecycle
dashboard only approved non-duplicates
```

---

### 5. Email receipt lifecycle/warranty/price

Current state:

Email parser tests exist, but no full golden.

Needed:

```text
EmailReceiptLifecycleWarrantyGoldenTest
```

Must assert:

```text
email parser output
redacted raw email storage
receipt lifecycle events
receipt-expense match
no duplicate expense
warranty/price protection created only if eligible
analytics counted once
```

---

### 6. Backup/restore full app roundtrip

Current state:

Only partial backup/restore money integrity.

Needed:

```text
BackupRestoreFullAppGoldenTest
```

Must include:

```text
expenses
transaction events
receipts
receipt links
groups
settlements
recurring occurrences
exchange rates
privacy audit events
background job runs
```

---

### 7. Worker restore barrier/idempotency

Current state:

There is `domain/workers/WorkerIdempotencyTest.kt`, but no golden.

Needed:

```text
WorkerRestoreBarrierGoldenTest
```

Must assert:

```text
restore mode active
workers skip writes
BackgroundJobRun statuses
no duplicate reminders
no duplicate receipt links
workers resume after restore
```

---

### 8. Negotiation

Current state:

There is `domain/negotiation/NegotiationEngineTest.kt`, but no golden.

Needed:

```text
BillNegotiationGoldenTest
```

Expected:

```json
{
  "billIncreaseDetected": true,
  "recommendationGenerated": true,
  "providerFailureHandled": true,
  "privacyDeniedSuppressesCloudCall": true,
  "userFacingState": "NEGOTIATION_AVAILABLE"
}
```

---

### 9. Config/DTO compatibility

Current state:

There are `AppConfigTest.kt` and `DtoContractTest.kt`.

No true golden needed unless these DTOs cross backup/export/API boundaries. If they do, add:

```text
DtoBackwardCompatibilityGoldenTest
```

---

### 10. UI/navigation golden/snapshot

Current state:

No visible true golden for routed UI state.

Needed only as lightweight state golden, not screenshot-heavy:

```text
NavigationRouteGoldenTest
DashboardUiStateGoldenTest
```

Expected:

```text
route serialization
deep link resolution
ViewModel state for partial currency warning
privacy denied state
review required state
```

---

# 6. Recommended golden test hierarchy

## Tier 0 — fix first

```text
GoldenScenarioVerifier.kt
```

Missing expected file must fail.

## Tier 1 — keep/fix current high-value golden tests

```text
SplitCalculatorGoldenTest.kt
CurrencyConverterGoldenTest.kt
MonteCarloSpendingSimulatorGoldenTest.kt
SynthesisEngineGoldenTest.kt
SpendingPaceGoldenTest.kt
GoldenMasterVerificationTest.kt
```

## Tier 2 — rewrite weak golden tests

```text
GoldenScenarioSmokeTest.kt
TaxGoldenScenarioTest.kt
GoldenAnalyticsDatasetTest.kt
```

## Tier 3 — convert scenario candidates into real golden files

```text
MixedCurrencyCoreFinancialScenarioTest.kt
MoneyAggregateConversionScenarioTest.kt
PrivacyGateEnforcementScenarioTest.kt
BackupRestoreMoneyIntegrityScenarioTest.kt
GroupGoldenScenarioTest.kt
InvestmentGoldenScenarioTest.kt
```

---

# 7. First 10 golden tests I would make release-blocking

In this exact order:

```text
1. MulticurrencyAnalyticsDashboardGoldenTest
2. NotificationReviewDashboardBudgetGoldenTest
3. AnalyticsDashboardBudgetParityGoldenTest
4. ReceiptMatchingAnalyticsGoldenTest
5. RecurringPlannedActualGoldenTest
6. GroupSettlementBudgetOffsetGoldenTest
7. BackupRestoreFullAppGoldenTest
8. PrivacyAiRedactionGoldenTest
9. ForecastSynthesisGoldenTest
10. BankSyncFailureRecoveryGoldenTest
```

These cover the biggest multi-pipeline failure modes.

---

# 8. Local command to find all golden tests in your current branch

Because your local branch may be ahead of GitHub, run:

```bash
find app/src/test app/src/androidTest -type f \( -name "*Test.kt" -o -name "*Test.java" \) \
  | sort \
  | xargs grep -nEi "golden|snapshot|expected|fixture|master" \
  > build/golden-test-candidates.txt
```

Also list golden-named files:

```bash
find app/src/test app/src/androidTest -type f \
  | grep -Ei "golden|master|snapshot" \
  | sort
```

And list golden resources:

```bash
find app/src/test/resources app/src/androidTest/assets app/src/androidTest/resources -type f \
  | sort
```

If that last command does not show expected JSON files, then your “golden” layer is still mostly Kotlin constants, not real golden-file testing.

---

# Sources checked

- Scenario directory:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios

- Test resources directory:  
  https://github.com/panospao7/Cost-agregator/tree/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/resources

- Golden verifier:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/testfixtures/golden/GoldenScenarioVerifier.kt

- Budget golden:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/budget/BudgetCalculatorGoldenTest.kt

- Currency golden:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/currency/CurrencyConverterGoldenTest.kt

- Monte Carlo golden:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulatorGoldenTest.kt

- Health golden:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/health/HealthScoreGoldenTest.kt

- Split golden:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/logic/SplitCalculatorGoldenTest.kt

- Synthesis golden:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/logic/SynthesisEngineGoldenTest.kt

- Automated savings golden:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/domain/savings/AutomatedSavingsRuleEngineGoldenTest.kt

- Golden analytics dataset/test:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/metrics/GoldenAnalyticsDataset.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/metrics/GoldenAnalyticsDatasetTest.kt

- Golden master verification:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/verification/GoldenMasterVerificationTest.kt

- Tax golden scenario:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/TaxGoldenScenarioTest.kt

- Money/backup/privacy scenario candidates:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/MixedCurrencyCoreFinancialScenarioTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/MoneyAggregateConversionScenarioTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/BackupRestoreMoneyIntegrityScenarioTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/18482021294eba1d209afa2deb34aea6c107a52f/app/src/test/java/com/yourname/expensetracker/scenarios/PrivacyGateEnforcementScenarioTest.kt