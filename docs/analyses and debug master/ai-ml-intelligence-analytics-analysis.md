# AI / ML Intelligence + Deep Analytics Engines Deep Analysis

Branch: `master-refactor`

Static review scope:

- analytics/insights engines
- anomaly detection
- advanced analytics screen/view model orchestration
- source-trust/confidence routing
- notification transaction classifier
- merchant/category ML classifier
- categorization engine
- recommendation/follow-through engine
- smart savings engine
- financial health score
- lifestyle inflation detector
- recommendation persistence/deduplication

---

# Executive verdict

This layer has a lot of promising logic:

- deterministic insights engine
- robust-ish anomaly detector using IQR/MAD/contextual logic
- confidence router combining parser, source trust, ML, and user corrections
- local Naive Bayes classifiers
- merchant/category hybrid categorization
- recommendation deduplication
- financial-health and safe-to-save scoring

But as a “deep intelligence layer,” it has a major systemic weakness:

> Most engines produce confident-looking results from raw `Double` amounts, inconsistent periods, weak lifecycle state, and uncalibrated confidence scores.

The biggest risks are:

1. mixed-currency analytics are raw-summed everywhere
2. analytics screens mix rolling and calendar period semantics
3. AI/ML recommendations can be based on stale, noisy, or fake training data
4. source trust and user-correction counters can drift
5. category classifier can return stale/deleted category IDs
6. recommendation dedupe fails for timestamp-varying filters
7. health/savings scores look authoritative but lack account-balance, currency, and bill-paid lifecycle data
8. assistant/dashboard insights may summarize data scopes that do not match the selected period

Highest-priority fix:

> Add a single analytics-money/period/data-quality contract used by every intelligence engine before it can produce high-confidence output.

---

# Architecture observed

## Analytics path

```text
AnalyticsViewModel
→ AnalyticsRepository
→ InsightsEngine
→ AdvancedAnalyticsEngine
→ AnomalyDetector
→ RecurringExpenseEngine
→ Location analytics engines
→ SpendingPersonalityClassifier
```

## Notification confidence path

```text
ParsedTransaction
→ ConfidenceRouter
→ TransactionClassifier
→ SourceStatsRepository
→ UserCorrectionRepository
→ RoutingDecision
```

## Category prediction path

```text
HybridExpenseClassifier
→ CategorizationEngine
→ MerchantCategoryRepository
→ ExpenseCategoryClassifier
→ FeatureExtractor
```

## Recommendation path

```text
DashboardFollowThroughEngine
→ RecommendationRepository
→ RecommendationDeduplicator
→ RecommendationDao
```

## Financial health / savings

```text
FinancialHealthScoreV2
→ BudgetRepository
→ ExpenseRepository
→ SavingsGoalRepository
→ RecurringExpenseEngine

SmartSavingsEngine
→ BudgetRepository
→ ExpenseRepository
→ MonteCarloSpendingSimulator
→ SavingsGoal
```

---

# Strong parts

## 1. Analytics filtering often uses personal effective spend

Many analytics paths use:

```text
effectiveAmount
!isNotMine
PURCHASE only
```

This is correct for personal spending.

## 2. AnomalyDetector uses robust methods

It uses:

- IQR
- MAD
- contextual day/time grouping

That is much better than simple mean/std-dev outlier detection.

## 3. ConfidenceRouter has layered confidence adjustment

It considers:

- parser confidence
- ML classifier
- source trust
- merchant rejection rate
- package rejection rate
- previous approvals
- unknown merchant penalty

Good structure.

## 4. Category prediction has deterministic-first architecture

`HybridExpenseClassifier` checks merchant dictionary first, then ML, then fallback.

Good. User-defined deterministic rules should usually beat ML.

## 5. Recommendation engine separates navigation logic from AI text

`DashboardFollowThroughEngine` states that AI only supplies text, while filters/navigation are deterministic.

Good safety boundary.

## 6. Recommendation repository caps active recommendations

It enforces a maximum of 5 active cards.

Good UX guard.

---

# Critical / high-priority findings

## 1. Analytics and intelligence engines raw-sum mixed currencies

### Where

Examples:

- `InsightsEngine`
- `MonthlyComparisonCalculator`
- `CategoryInsightEngine`
- `MerchantInsightEngine`
- `DayOfWeekAnalyzer`
- `AdvancedAnalyticsEngine`
- `AnalyticsRepository`
- `AnalyticsViewModel`
- `SmartSavingsEngine`
- `FinancialHealthScoreV2`
- `LifestyleInflationDetector`
- `DashboardFollowThroughEngine`

### Problem

Almost all analytics models use:

```kotlin
Double
```

without carrying currency.

So values like:

```text
€100 + $100 + ¥100
```

become:

```text
300
```

with no meaningful currency.

### Impact

This corrupts:

- monthly comparisons
- category analytics
- merchant analytics
- anomaly detection
- spending pace
- lifestyle inflation
- savings recommendations
- financial health score
- high-amount recommendations
- budget-vs-actual analytics
- salary/spending pattern detection

### Severity

**Critical if multi-currency is enabled**

### Fix

Create analytics money types:

```kotlin
AnalyticsMoney(
    amount: Double,
    currency: CurrencyCode,
    conversionStatus: ConversionStatus
)

AnalyticsMoneyBucket(
    currency: CurrencyCode,
    amount: Double
)
```

Rules:

- analytics totals must declare currency
- cross-currency rankings require conversion
- failed conversion lowers confidence
- raw `Double` money should not leave the data-access boundary

---

## 2. Analytics screen mixes rolling and calendar periods

### Where

`AnalyticsViewModel.getPeriodRange()`

Behavior:

```text
MONTH   → last 30 days
QUARTER → last 90 days
YEAR    → last 365 days
WEEK    → calendar week
TODAY   → calendar day
```

But other engines use calendar month/week/quarter/year.

### Impact

Different cards on the same analytics screen can answer different questions.

Example on April 26:

- “Month” totals may mean March 27–April 26.
- Insights may mean April 1–May 1.
- Advanced analytics may use custom rolling range.
- Budget status may use calendar budget period.

### Severity

**Critical UX / correctness**

### Fix

Define explicit period semantics:

```text
THIS_MONTH = calendar month
LAST_30_DAYS = rolling 30 days
THIS_QUARTER = calendar quarter
LAST_90_DAYS = rolling 90 days
THIS_YEAR = calendar year
LAST_365_DAYS = rolling year
```

Never label rolling windows as calendar periods.

---

## 3. `InsightsEngine.generateInsights()` always uses current calendar month

### Where

`InsightsEngine.generateInsights()`

It computes:

```kotlin
val currentMonth = getMonthPeriod(now)
```

regardless of the selected analytics period.

`AnalyticsViewModel` calls it for every selected period.

### Impact

If user selects:

- Week
- Quarter
- Year
- All

the insight cards still describe the current calendar month.

So a yearly analytics screen can show:

> “Spending up this month”

without clearly saying it is month-only.

### Severity

**High**

### Fix

Pass an explicit `AnalyticsPeriodRange` to `InsightsEngine`.

```kotlin
generateInsights(periodRange, categories, expenses)
```

If some insights are inherently monthly, label them as monthly.

---

## 4. Current/previous comparison uses raw millisecond duration

### Where

- `AnalyticsRepository.getSpendingSummary()`
- `AnalyticsViewModel.computeAnalyticsInternal()`

Previous period is:

```kotlin
previousStart = start - (end - start)
previousEnd = start
```

### Problem

For calendar months/quarters/years, equal milliseconds is not the same as previous calendar period.

March can compare to a period starting in late January depending on range length.

### Severity

**High**

### Fix

Use calendar-aware period previous ranges:

```text
this calendar month → previous calendar month
this calendar quarter → previous quarter
last 30 days → previous 30 days
```

---

## 5. Post-salary correlation is not month-aligned

### Where

`AnalyticsViewModel.computePostSalaryPattern()`
`LifestyleInflationDetector.calculateCorrelation()`

Patterns like:

```kotlin
incomeByMonth.map { it.value }
spendingByMonth.map { it.value }
```

are passed as separate lists.

### Problem

Map value order may not align by the same month. Missing months also make list sizes differ.

Example:

```text
income months: Jan, Feb, Mar
spending months: Jan, Mar
```

Correlation returns `0.0` due size mismatch, or worse, pairs the wrong months.

### Impact

Income/spending correlation and lifestyle inflation analytics can be wrong.

### Severity

**Critical for lifestyle analytics**

### Fix

Align by explicit sorted month keys:

```kotlin
val months = (incomeByMonth.keys + spendingByMonth.keys).distinct().sorted()
val incomeSeries = months.map { incomeByMonth[it] ?: 0.0 }
val spendingSeries = months.map { spendingByMonth[it] ?: 0.0 }
```

Then compute correlation.

---

## 6. Statistical anomaly detection compares mostly within the current month

### Where

`AnomalyDetector.detect()`

It filters current-month expenses and then runs IQR/MAD per category on those current-month values.

### Problem

This can detect outliers only relative to the current month’s distribution, not historical category behavior.

With small current-month samples, a normal recurring annual bill can look anomalous, or true anomalies can be hidden by a few large same-month purchases.

### Impact

False positives/negatives:

- rent deposit marked anomalous
- annual insurance marked anomalous
- several fraud charges mask each other

### Severity

**High**

### Fix

Use historical baselines:

```text
current period candidate
vs
same category/merchant historical distribution
```

Also suppress or label known recurring/planned bills.

---

## 7. Anomaly detector does not suppress known recurring/planned bills

### Where

- `AnomalyDetector`
- `InsightsEngine.findAnomalies()`
- `AnalyticsViewModel.computeVelocityAnomalies()`

### Problem

Known recurring expenses can be flagged as unusual simply because they are large.

Example:

- rent
- annual insurance
- yearly software subscription
- tuition
- tax payment

### Impact

The app can repeatedly warn about normal expected payments.

### Severity

**High**

### Fix

Before surfacing anomaly:

```text
if transaction matches confirmed recurring/planned occurrence:
  either suppress
  or label as "large expected bill"
```

---

## 8. Anomaly methods have inconsistent priority semantics

### Where

`AnomalyMethod` and `AnomalyDetector.detect()`

Comment says:

```text
MAD > IQR > CONTEXTUAL > MULTIPLIER
```

Enum order is:

```kotlin
MULTIPLIER, IQR, MAD, CONTEXTUAL
```

Merge uses ordinal comparison in some places, meaning `CONTEXTUAL` can outrank `MAD`.

### Impact

Method labels and severity may be misleading.

### Severity

**Medium / High**

### Fix

Add explicit priority:

```kotlin
enum class AnomalyMethod(val priority: Int)
```

Do not rely on enum ordinal.

---

## 9. Analytics uncategorized spend disappears from some category breakdowns

### Where

- `AnalyticsRepository.getCategoryBreakdown()`
- `TotalsAggregationEngine.getCategoryBreakdown()`
- `AdvancedAnalyticsEngine.getCategoryAnalytics()`

Some paths drop rows where category is missing or not found.

### Impact

Category totals can sum to less than total spending.

This makes analytics look inconsistent:

```text
Total spent: €1000
Categories shown: €780
```

### Severity

**High**

### Fix

Use an explicit virtual category:

```text
Uncategorized
```

Include missing/deleted category IDs in a diagnostic bucket.

---

## 10. Duplicate/suspect transaction detection is weak

### Where

`AnalyticsViewModel.detectSuspectTransactions()`

Near-duplicate logic uses:

```kotlin
abs(a.amount - b.amount) < 0.01
merchant string equals ignore-case
within 24h
```

### Problems

- uses gross `amount`, not `effectiveAmount`
- ignores currency
- ignores merchantKey
- ignores transaction type/source
- does not use canonical duplicate policy
- can flag legitimate recurring same-day transactions
- misses duplicates with slightly different merchant strings

### Impact

Bad duplicate/error cards.

### Severity

**High**

### Fix

Use the canonical transaction duplicate engine:

```text
merchantKey
currency
amount tolerance
date window
transaction type
source fingerprint
dedupe key
```

Also link each suspect to matched target and reason.

---

## 11. Source trust can be inflated by duplicates

### Where

`SourceStats.trustScore`

Trust score:

```kotlin
valid = acceptedAsExpense + duplicates
effectiveTotal = totalNotifications - autoRejected
trust = valid / effectiveTotal
```

### Problem

Duplicates are counted as valid.

A source that repeatedly sends duplicate transaction notifications can raise or maintain trust, even if duplicate volume is high.

### Impact

ConfidenceRouter may auto-accept future transactions from noisy sources more readily.

### Severity

**High**

### Fix

Track separately:

```text
accepted
duplicateExact
duplicateFinancial
rejectedUser
autoRejectedParser
pending
falsePositive
```

Trust should reward accepted transactions, not duplicate spam.

---

## 12. Source stats are mutable counters, not event-derived

### Where

- `SourceStats`
- `SourceStatsDao`
- `ConfidenceRouter`
- notification/review pipeline

### Problem

Stats are incremented/decremented by many paths.

Counters can drift due to:

- direct DAO methods
- migration repair
- delete/reset
- duplicate handling
- partial failures
- bulk operations

### Impact

Confidence routing can become biased by wrong stats.

### Severity

**High**

### Fix

Use event ledger:

```kotlin
SourceProcessingEvent(
    packageName,
    rawNotificationId,
    reviewId,
    expenseId,
    outcome,
    createdAt
)
```

Derive stats from events or periodically rebuild.

---

## 13. ConfidenceRouter cache can use stale rejection/approval data

### Where

`ConfidenceRouter`

Caches:

- source stats
- merchant rejection rate
- package rejection rate
- previous approvals

TTL is 60 seconds.

Invalidation methods exist, but correctness depends on every write path calling them.

### Impact

User rejects a merchant, but for up to 60 seconds similar notifications can still be routed using stale “previously approved” or lower rejection data.

### Severity

**High for auto-accept**

### Fix

Use event-driven invalidation from correction/source-stat repositories.

For auto-accept decisions, consider bypassing cache for merchant/package rejection data after recent user action.

---

## 14. Merchant rejection/approval keys use raw merchant string

### Where

`UserCorrectionDao`
`ConfidenceRouter.getCachedMerchantRejectionRate()`
`hasPreviousApprovals()`

### Problem

Merchant stats are keyed by exact `originalMerchant`.

Variants fragment:

```text
NETFLIX
Netflix.com
Netflix Europe
NETFLIX *1234
```

### Impact

A merchant repeatedly rejected under one spelling can still auto-accept under another.

### Severity

**High**

### Fix

Store and query by:

```text
merchantKey
canonicalMerchantName
source package
amount bucket
currency
```

---

## 15. TransactionClassifier model persistence is not durable on background

### Where

`TransactionClassifier`

Training schedules save after delay. `onBackground()` cancels pending save and retrain jobs.

### Problem

If the app backgrounds shortly after training, model changes may not be saved.

User corrections remain in DB, but the in-memory model changes can be lost, and retraining depends on later initialization/correction-count logic.

### Impact

The ML route can behave inconsistently across process death.

### Severity

**Medium / High**

### Fix

On background:

```text
flush pending model save
cancel retrain only after durable checkpoint
```

Or make model derived entirely from DB corrections at startup.

---

## 16. ML model files can leak sensitive vocabulary

### Where

- `TransactionClassifier`
- `ExpenseCategoryClassifier`

Model JSON files store learned word/bigram counts.

Training text includes:

- notification title
- notification text
- merchant
- original merchant

### Problem

Even if raw notifications are purged, the model can retain sensitive tokens:

- clinic names
- employer names
- merchant names
- locations
- private purchase terms

### Impact

Backup/export/debug file exposure can leak user financial/private data.

### Severity

**High / privacy**

### Fix

Treat model files as sensitive:

- include in encrypted backup only
- purge when user deletes AI/ML history
- optionally store hashed features instead of raw tokens
- add “Reset learned classifier” button

---

## 17. Category classifier can return stale/deleted category IDs

### Where

`ExpenseCategoryClassifier`
`HybridExpenseClassifier`

`ExpenseCategoryClassifier` persists category IDs in model JSON.

`HybridExpenseClassifier` does:

```kotlin
category = categories.find { it.id == best.categoryId }
categoryName = category?.name ?: "Unknown"
categoryId = best.categoryId
```

### Problem

If a category is deleted/merged, ML can still return its old ID.

The result still carries stale `categoryId`.

### Impact

A new expense can be categorized to a nonexistent/deleted category.

This can break:

- category analytics
- budget matching
- assistant category filters
- exports

### Severity

**Critical**

### Fix

Validate ML category IDs against current active categories.

If invalid:

```text
drop prediction
mark model stale
trigger retrain/migration
```

Also version model with category taxonomy hash.

---

## 18. Category ML only trains on merchant tokens

### Where

`ExpenseCategoryClassifier.train()`

Only:

```kotlin
features.merchantTokens
```

are used.

But `ExpenseFeatures` contains:

- amount bucket
- day of week
- hour
- source package
- weekend flag

### Problem

The model appears richer than it is. Amount/time/source features are ignored.

### Impact

Multi-category merchants are poorly handled:

- Amazon
- Walmart
- PayPal
- Apple
- Google
- pharmacies
- department stores

### Severity

**Medium / High**

### Fix

Either:

1. remove unused feature fields to avoid false confidence, or
2. include them in the classifier:
   - amount bucket tokens
   - source package tokens
   - text context
   - category-specific receipt/item context

---

## 19. Hybrid category classifier uses current time for classification features

### Where

`HybridExpenseClassifier.classify()`

It extracts features using:

```kotlin
eventTimeMillis = timeProvider.now()
```

### Problem

For imported or delayed transactions, the model receives current app time, not transaction time.

Even if current classifier mostly ignores time, this is still wrong design and will become dangerous if time features are activated.

### Severity

**Medium**

### Fix

Pass transaction/notification timestamp explicitly.

---

## 20. Category learning globally changes future behavior from one correction

### Where

`HybridExpenseClassifier.learnFromCorrection()`
`CategorizationEngine.learnMerchantCategory()`

A correction immediately teaches:

```text
merchant → category
```

globally.

### Impact

One edit can globally affect future categorization for multi-purpose merchants.

This was also found in budget/category analysis.

### Severity

**High**

### Fix

Ask user:

> “Use this category for future transactions from this merchant?”

Record rule confidence and scope:

```text
single correction = weak signal
bulk correction = strong signal
explicit future rule = high confidence
```

---

## 21. Recommendation dedupe fails for rolling timestamp filters

### Where

- `DashboardFollowThroughEngine`
- `RecommendationDeduplicator`

Dedup signature includes exact date ranges.

Recommendations like “recent transactions” use:

```kotlin
TimePeriodUtils.getLastNDaysRange(timeProvider.now(), 7)
```

### Problem

The same recommendation generated seconds/minutes apart has different start/end timestamps.

So the signature differs.

### Impact

Duplicate recommendation cards can keep appearing:

```text
Review your recent spending this week
Review your recent spending this week
```

because date range timestamps are slightly different.

### Severity

**High**

### Fix

Normalize recommendation signatures:

```text
LAST_7_DAYS
CURRENT_MONTH
CATEGORY:5:CURRENT_MONTH
MERCHANT:amazon:LAST_90_DAYS
```

Do not use raw millisecond ranges in dedupe signatures unless exact dates matter.

---

## 22. Recommendation Flow uses stale `nowMillis`

### Where

`RecommendationDao.observeActiveByUser()`

Default parameter:

```kotlin
nowMillis: Long = System.currentTimeMillis()
```

is captured when the DAO method is called.

### Problem

The Flow does not automatically update just because time passes.

A recommendation that expires later may remain in the active Flow until a DB invalidation happens.

### Impact

Expired recommendation cards can stay visible.

### Severity

**High**

### Fix

Do not use time as a static Flow parameter.

Options:

1. schedule periodic expiration worker
2. observe all active-ish rows and filter with a ticker in repository
3. update `status = EXPIRED` on open/resume

---

## 23. Recommendation persistence uses `REPLACE`

### Where

`RecommendationDao.insert()` and `insertAll()`

### Problem

`OnConflictStrategy.REPLACE` deletes and reinserts rows.

If IDs collide or future unique constraints are added, this can erase lifecycle fields.

### Impact

Could reset:

- dismissedAt
- status
- createdAt
- user dismissal history

### Severity

**Medium / High**

### Fix

Use explicit upsert semantics:

```text
insert new active
do not resurrect archived/dismissed rows unless explicit
```

Add unique recommendation signature if needed.

---

## 24. Dashboard follow-through uses gross amount and hardcoded euro

### Where

`DashboardFollowThroughEngine`

High amount rule:

```kotlin
if (transaction.amount > highAmountThreshold)
```

Text:

```text
€...
```

Filter:

```kotlin
minAmount = transaction.amount
```

### Problems

- uses gross amount, not effective amount
- no currency
- hardcoded euro
- minAmount filter is raw numeric
- high threshold likely has no currency context

### Impact

Shared transactions and non-EUR transactions create misleading recommendations.

### Severity

**High**

### Fix

Use:

```text
effectiveAmount
currency-aware threshold
money formatter
currency-aware filter
```

---

## 25. Financial Health Score is not based on real account balance

### Where

`FinancialHealthScoreV2.calculateRunwayScore()`

Runway uses:

```kotlin
totalSavings = savingsGoals.sumOf { it.currentAmount }
```

### Problem

Savings goals are not the same as actual liquid account balance.

A user can have:

- €10,000 in bank but no savings goals
- €5,000 vacation goal but no emergency fund
- stale manually-entered goal amount

### Impact

Runway score can be misleading.

### Severity

**Critical UX / financial advice risk**

### Fix

Use real account balances if available.

If not available, label clearly:

```text
Goal-funded runway estimate
```

or exclude runway from overall health confidence.

---

## 26. Bill reliability score is not actual bill reliability

### Where

`FinancialHealthScoreV2.calculateBillReliabilityScore()`

It uses recurring-pattern cadence/timing as a weak proxy.

No paid/missed/late occurrence state is used.

No patterns returns default score `75`.

### Impact

A user with no bill data receives “good” reliability.

A user with detected patterns but no paid-state data gets a reliability score from cadence, not actual payment behavior.

### Severity

**High**

### Fix

Use recurring occurrence lifecycle:

```text
due date
paid date
missed
skipped
linked expense
late days
```

If unavailable, show “not enough data,” not 75.

---

## 27. Financial health budget adherence can double-count budget hierarchy

### Where

`FinancialHealthScoreV2.calculateBudgetAdherenceScore()`

It sums every active budget:

```kotlin
totalBudget += status.budget.amount
```

### Problem

Overall and category budgets are hierarchical, not independent.

Summing both can distort adherence.

### Impact

A user with overall + category budgets can get a better or worse health score depending on how budgets overlap.

### Severity

**High**

### Fix

Use one budget scope policy:

- overall only, or
- category envelopes only, or
- hierarchical reconciled budget

---

## 28. Financial health history can be written repeatedly for same period

### Where

`FinancialHealthScoreV2.calculateHealthScore()`

It saves to history on every calculation.

I did not see a uniqueness guarantee in this review.

### Impact

Opening dashboard repeatedly can create duplicate health history records for the same period.

### Severity

**Medium / High**

### Fix

Use unique key:

```text
periodStart + periodEnd + scoreVersion
```

and update existing row instead of append, unless intentionally tracking every recalculation.

---

## 29. Smart savings ignores upcoming committed bills

### Where

`SmartSavingsEngine.runMonteCarloSimulation()`

It sets:

```kotlin
knownUpcoming = 0.0
```

### Problem

Safe-to-save calculation does not subtract upcoming known obligations.

### Impact

The app can recommend saving money that is actually needed for rent, bills, or subscriptions.

### Severity

**Critical**

### Fix

Use planned/recurring occurrence engine:

```text
knownUpcoming = committed future bills in horizon
```

Include unpaid overdue bills too.

---

## 30. Smart savings uses hardcoded currencyless caps

### Where

`SmartSavingsEngine.calculateWeightedSafeAmount()`

Caps:

```text
WEEK    75
MONTH   200
QUARTER 500
```

### Problem

No currency or user-income context.

`75 JPY`, `75 EUR`, and `75 USD` are not equivalent.

### Severity

**High with multi-currency**

### Fix

Make caps relative to:

- income
- budget
- display/base currency
- user settings
- confidence

---

## 31. Smart savings treats uncategorized as discretionary

### Where

`SmartSavingsEngine.isDiscretionaryCategory()`

```kotlin
return categoryName == null || categoryName !in essentialCategories
```

### Problem

Uncategorized spend can include rent, healthcare, insurance, or loan payments.

### Impact

Safe-to-save can be overestimated.

### Severity

**High**

### Fix

Uncategorized should lower confidence, not be assumed discretionary.

---

## 32. Lifestyle inflation detector uses merchant/notes keywords for discretionary classification

### Where

`LifestyleInflationDetector.isDiscretionaryExpense()`

It checks keywords in merchant/notes only.

### Problems

- ignores category
- English-heavy
- misses many discretionary purchases
- false positives from merchant names
- no user-configurable essential/discretionary mapping

### Impact

Lifestyle creep and hedonic adaptation scores are weak.

### Severity

**High**

### Fix

Use category metadata:

```text
category.discretionaryLevel
category.essentiality
merchant override
```

Fallback keyword inference should be low-confidence only.

---

## 33. Lifestyle detector uses `System.currentTimeMillis()` directly

### Where

`LifestyleInflationDetector.analyzeLifestyleInflation()`

### Problem

It does not use injected `TimeProvider`.

### Impact

Harder to test, and behavior can change unexpectedly around midnight/timezone.

### Severity

**Medium**

### Fix

Inject `TimeProvider`.

---

## 34. Analytics ViewModel performs heavy work and broad reads in UI orchestration

### Where

`AnalyticsViewModel`

It observes all expenses for freshness, then separately queries windows and performs multiple in-memory analytics.

### Impact

Large user histories can cause:

- slow analytics tab
- high memory usage
- repeated recomputation
- battery drain

### Severity

**High for large datasets**

### Fix

Move analytics computation into repository/use-case layer with SQL aggregates and stable input snapshots.

Use:

```text
period-scoped aggregate queries
currency buckets
stable analytics run id
```

---

## 35. Analytics cache invalidation uses full list flow versions

### Where

`AnalyticsViewModel`

It clears caches whenever the observed expense list changes.

### Problem

Any change, including irrelevant old data outside the selected period, clears all analytics caches.

### Impact

Unnecessary recomputation.

### Severity

**Medium**

### Fix

Use table invalidation + period-specific freshness:

```text
latestModifiedAt within period
count within period
budget/category version
```

---

# Cross-pipeline risks

## AI briefings

AI briefings can summarize analytics that are:

- mixed-currency raw totals
- current-month insights on year screen
- stale cached recommendation artifacts
- missing uncategorized spend
- based on weak confidence

So AI summaries should include analytics diagnostics and warnings.

## Expense lifecycle

Fake fallback transactions like `0.01 EUR` can train classifiers, affect source trust, and pollute anomaly/savings/health engines.

## Category corrections

Single manual category edits can train both dictionary and ML globally, causing downstream budget/analytics drift.

## Recurring/planned lifecycle

Analytics, savings, health, and anomaly suppression all need recurring occurrence lifecycle. Without it, intelligence confuses expected bills with anomalies and safe-to-save cash.

---

# Recommended fix order

## PR 1 — Add analytics money contract

Every analytics result should either:

- be currency-bucketed, or
- be converted to one declared currency, or
- be marked incomplete due conversion failure.

Do this before improving scoring formulas.

## PR 2 — Centralize analytics period semantics

Create:

```kotlin
AnalyticsPeriodRange(
    kind,
    startInclusive,
    endExclusive,
    comparisonRange,
    label
)
```

Use it everywhere.

No more hidden rolling/calendar mix.

## PR 3 — Add analytics data-quality metadata

Every major result should include:

```text
dataQuality
currencyCompleteness
periodCompleteness
sampleSize
sourceScope
warnings
confidence
```

Do not show high-confidence cards when data quality is low.

## PR 4 — Fix source-trust and correction ledger

Move from mutable counters to event-derived source stats.

Normalize merchant correction keys.

Invalidate confidence caches on correction events.

## PR 5 — Harden category ML lifecycle

- validate category IDs
- model version includes category taxonomy hash
- reset/retrain on category delete/merge
- prevent stale category predictions
- make ML persistence durable on background

## PR 6 — Fix recommendation dedupe/expiry

- normalized semantic signatures
- no raw timestamp signatures for rolling windows
- no stale Flow `nowMillis`
- DB-level unique active signature if possible

## PR 7 — Make health/savings scores honest

- no runway without account balance or explicit savings-goal framing
- no bill reliability without bill occurrence lifecycle
- safe-to-save must include upcoming committed bills
- all scores carry confidence and missing-data warnings

## PR 8 — Align anomaly detection with recurring/planned data

Suppress or relabel expected recurring/planned bills.

Use historical category/merchant baselines, not only current-month distribution.

---

# Regression tests to add

1. Analytics refuses or buckets `€100 + $100` instead of showing `200`.
2. “This month” uses calendar month; “last 30 days” uses rolling 30 days.
3. Year analytics does not show unlabeled current-month insights.
4. Previous calendar month comparison handles February/March correctly.
5. Post-salary correlation aligns income/spend by month key.
6. Uncategorized spending appears in category breakdown.
7. Known monthly rent is not flagged as anomaly.
8. Anomaly method priority is explicit and tested.
9. Duplicate suspect detection uses merchantKey/currency/type/date tolerance.
10. Duplicate notifications do not inflate source trust.
11. Source stats can be rebuilt from event ledger.
12. Merchant rejection rates use normalized merchant keys.
13. Confidence cache invalidates after reject/approve.
14. Transaction classifier flushes pending model save on background.
15. ML model reset/purge removes sensitive learned tokens.
16. Deleted category ID is never returned by category classifier.
17. Category taxonomy change marks model stale.
18. Recommendation recent-7-days dedupes across timestamp changes.
19. Expired recommendation disappears without unrelated DB mutation.
20. High-amount recommendation uses effective amount and currency.
21. Financial runway score warns when no account balance exists.
22. Bill reliability is “insufficient data” without bill occurrences.
23. Smart savings subtracts upcoming committed bills.
24. Uncategorized spending lowers safe-to-save confidence.
25. Lifestyle correlation aligns months and uses category-based discretionary classification.

---

# Top three fixes

If you only fix three things first:

1. **Make analytics and intelligence currency-safe.**
2. **Unify period semantics and pass explicit ranges into every analytics engine.**
3. **Harden ML/source-trust lifecycle: event-derived stats, normalized merchant keys, stale-category validation, and durable model handling.**

These remove the biggest “confident but wrong” risks.

---

# Sources reviewed

- `CODEBASE_SEGMENTS.md`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/docs/architecture/CODEBASE_SEGMENTS.md

- `InsightsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt

- `AnomalyDetector.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnomalyDetector.kt

- `AdvancedAnalyticsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/AdvancedAnalyticsEngine.kt

- `AnalyticsModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/AnalyticsModels.kt

- `SpendingPaceCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceCalculator.kt

- `MonthlyComparisonCalculator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/MonthlyComparisonCalculator.kt

- `CategoryInsightEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/CategoryInsightEngine.kt

- `MerchantInsightEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/MerchantInsightEngine.kt

- `DayOfWeekAnalyzer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/DayOfWeekAnalyzer.kt

- `SpendingPaceProjection.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/SpendingPaceProjection.kt

- `TotalsAggregationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/analytics/TotalsAggregationEngine.kt

- `AnalyticsRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/AnalyticsRepository.kt

- `AnalyticsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/analytics/AnalyticsViewModel.kt

- `ConfidenceRouter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ConfidenceRouter.kt

- `TransactionClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/intelligence/TransactionClassifier.kt

- `HybridExpenseClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/HybridExpenseClassifier.kt

- `ExpenseCategoryClassifier.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/ExpenseCategoryClassifier.kt

- `FeatureExtractor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/intelligence/ml/FeatureExtractor.kt

- `SourceStats.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/SourceStats.kt

- `SourceStatsDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/SourceStatsDao.kt

- `UserCorrection.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/UserCorrection.kt

- `UserCorrectionDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/UserCorrectionDao.kt

- `CategorizationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/categorization/CategorizationEngine.kt

- `DashboardFollowThroughEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/engine/DashboardFollowThroughEngine.kt

- `RecommendationRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/RecommendationRepository.kt

- `RecommendationDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecommendationDao.kt

- `RecommendationEntity.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecommendationEntity.kt

- `RecommendationDeduplicator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/service/RecommendationDeduplicator.kt

- `SmartSavingsEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/savings/SmartSavingsEngine.kt

- `FinancialHealthScoreV2.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt

- `LifestyleInflationDetector.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/lifestyle/LifestyleInflationDetector.kt