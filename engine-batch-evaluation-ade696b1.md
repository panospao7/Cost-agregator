# Engine Batch Evaluation — through `ade696b1`

Reviewed commits:

```text
1544d2c1
c6227d35
f521788c
53d37fd1
6b1fbe8c
ade696b1
```

Sources:

- https://github.com/panospao7/Cost-agregator/commit/1544d2c1
- https://github.com/panospao7/Cost-agregator/commit/c6227d35
- https://github.com/panospao7/Cost-agregator/commit/f521788c
- https://github.com/panospao7/Cost-agregator/commit/53d37fd1
- https://github.com/panospao7/Cost-agregator/commit/6b1fbe8c
- https://github.com/panospao7/Cost-agregator/commit/ade696b1

Review type: static GitHub/code review, not local Gradle execution.

---

# 1. Executive verdict

This batch is **substantially better** than the earlier engine state.

You made real improvements in:

```text
✅ MoneyAmount finite-number validation
✅ PeriodRange / Money deprecation direction
✅ new WarrantyLifecycleEvent table + DAO
✅ new InvestmentTransaction table + DAO
✅ DB version bump 117 → 119 with migrations registered
✅ group current-user invariant
✅ group single-currency rejection for linked expenses
✅ group linked-expense normalization through TransactionLifecycleCoordinator
✅ category cache invalidation on category writes
✅ MerchantCategoryDao insert return type changed to Long/List<Long>
✅ merchant alias conflict detection
✅ TaxSettingsRepository added
✅ map money display/conversion work from previous commits retained
✅ investment add/update atomicity improved
✅ portfolio history carry-forward implemented
✅ direct time/raw money guard scripts now real-ish scanners
```

But I would **not yet call engines fully finalized**.

The remaining issues are now more specific:

```text
1. Tracker is stale and undercounts fixes/partials.
2. Assistant unified currency-aware helper exists but is not used everywhere.
3. Legacy Natural Language still does raw amount prefilter/fallback.
4. Subscription validateAndCreate is not atomic and likely does not set isSubscription=true.
5. AnalyticsInputAssembler exists but is not fully migrated/integrated.
6. Group lifecycle is still incomplete: no persistent settlement, hard delete still bypasses lifecycle.
7. Warranty lifecycle table exists but event writes are not clearly wired.
8. InvestmentTransaction table exists but ledger logic is not wired.
9. TaxSettingsRepository exists but tax/business engines are not fully using it.
10. CI guards may be noisy or too weak; they need seeded tests.
```

Overall engine state now:

```text
Core pipelines: strong
Money/currency foundation: strong but not perfect
Advanced engines: mixed
Tracker/documentation: stale
Tests/guards: started, not complete
```

---

# 2. Commit-by-commit evaluation

## 2.1 `1544d2c1` — Phase 0-13 finalization

### Good

This commit organized the remaining work and added several implementation plans.

Good direction:

```text
assistantFilteredExpensesCurrencyAware planned
legacy NL containment noted
analytics assembler improved
map warnings planned
groups/tax classified as design-heavy
guard scripts introduced
```

### Concern

It mixes:

```text
real fixes
implementation plans
deferred design notes
```

under a title that says:

```text
zero TODO_ONLY enabled P0/P1
```

But later tracker/code still shows many TODO-only/partial items.

So this commit was useful as **planning/reconciliation**, but not finalization.

---

## 2.2 `c6227d35` — Assistant/NL, Analytics, DAO types, Groups, Tax, CI guards

### Good

Real improvements:

```text
MerchantCategoryDao.insert(): Long
MerchantCategoryDao.insertAll(): List<Long>
TaxSettingsRepository created
AnalyticsInputAssembler improved
NaturalLanguage merchant extraction improved
GroupLifecycleCoordinator attempted
check_direct_time_calls.kts scanner added
```

### Remaining issues

#### Assistant helper exists but is not actually used everywhere

Latest `ExecuteFinancialQueryUseCase` still has:

```text
assistantFilteredExpensesCurrencyAware(...)
```

but the old helper remains:

```text
assistantFilteredExpenses(...)
```

and several paths still call the old helper:

```text
executeTotal
executeAverage
executeLargest
executeCategoryBreakdown
executeMerchantBreakdown
executeCount
```

`executeCount()` still passes raw `minAmount/maxAmount` to repository.

So the assistant fix is **partial**, not complete.

#### AnalyticsInputAssembler is still not production-complete

It is still an `object`, not injectable.

It still has:

```kotlin
isSharedExpense = false
staleRateCount = 0
```

So it is a good foundation, but not a completed migration.

#### GroupLifecycleCoordinator did not survive

Later commit notes it was removed because of a Dagger cycle. So group lifecycle is still not really solved.

---

## 2.3 `f521788c` — 15 actionable fixes

### Good

This added/verified meaningful items:

```text
forecast planned quality work
portfolio carry-forward algorithm
business report MoneyAggregate wiring
map warning banner
golden scenario fixture improvements
subscription ViewModel using validateAndCreate
investment/subscription atomicity verification
```

### Concern

Some items are “verified existing” or documented, not always fully fixed.

Important note from the commit:

```text
GroupLifecycleCoordinator removed — causes Dagger cycle
```

That means group lifecycle must still be treated as unresolved/contained.

---

## 2.4 `53d37fd1` — Batch 1 real code

### Good

Real improvements:

```text
G01 currentUserGroupKey enforced
G04 mixed-currency settlement rejected
G08/G09 permanent delete requires prior archive, partially
G10 runBlocking removed from SettlementCalculator
C04 category cache invalidation wired
W20 warranty half-open semantics documented
```

### Remaining issues

Group hard-delete still has TODOs:

```text
member delete validation TODO
hard delete bypasses lifecycle
no group lifecycle event
no persistent settlement table
```

Also `deleteGroupAtomic()` clears shared flags directly and explicitly says it is not routed through `TransactionLifecycleCoordinator`.

That may be okay for emergency cleanup, but not for normal user-visible deletion.

---

## 2.5 `6b1fbe8c` — Batch 2

### Good

Real improvements:

```text
AliasLinkResult sealed class
alias conflict detection
group linked-expense currency rejection
linked system expense normalization through TransactionLifecycleCoordinator.updateOwnership()
TaxSettingsRepository fiscal month validation
warranty display end date
```

### Remaining issues

Merchant/category still incomplete:

```text
normalizedCanonicalName uniqueness not enforced
MerchantCategoryDao has migration-plan comments
caller conflict handling may not be universal
cache invalidation does not cover every write path
```

Group policy is better, but still missing durable settlement and lifecycle/audit.

---

## 2.6 `ade696b1` — Batch 3 migration items

### Good

This is important architecture work.

Added:

```text
WarrantyLifecycleEvent entity + DAO
InvestmentTransaction entity + DAO
MIGRATION_117_118
MIGRATION_118_119
DB version 119
schema JSON 119
MoneyAmount rejects NaN/Infinity
domain.model.PeriodRange deprecated
domain.util.Money deprecated
EntityTimeValidation helper
InvestmentDao raw aggregate methods deprecated
```

Migration registration looks good:

```text
MIGRATION_117_118 and MIGRATION_118_119 are in ALL_MIGRATIONS
```

### Remaining issues

#### WarrantyLifecycleEvent table exists but is not necessarily used

Adding the table is good, but the real fix requires event writes from:

```text
warranty created
warranty claimed
warranty expired
warranty extended
warranty transferred
auto-detected confirmed/rejected
return-window changes if related
```

I did not see enough evidence that all those writes are now wired.

#### InvestmentTransaction table exists but ledger logic is not wired

The table is a foundation.

But full investment lot ledger still needs:

```text
record BUY/SELL/DIVIDEND transactions
calculate realized gains
calculate cost basis
connect addHolding/updatePrice to transaction ledger
```

If you defer lot ledger, the table should be marked as foundation only.

#### Migration tests are needed

Because DB moved 117 → 119, add tests:

```text
migrate117To118_createsWarrantyLifecycleEvents
migrate118To119_createsInvestmentTransactions
freshInstall119_hasBothTables
migration117To119_preservesExistingData
```

---

# 3. Important current correctness issues

## Issue 1 — Subscription creation still looks broken

Current `validateAndCreate()` creates:

```kotlin
ManualRecurringExpense(
    merchant = request.merchant,
    amount = request.amount,
    currency = request.currency.uppercase(),
    frequency = request.frequency,
    nextDate = request.startDate,
    createdAt = now,
    isActive = true
)
```

But `ManualRecurringExpense.isSubscription` defaults to:

```kotlin
false
```

And `getAllSubscriptions()` filters:

```kotlin
it.isSubscription && it.isActive
```

So subscriptions created by `validateAndCreate()` may not appear as subscriptions.

Also `validateAndCreate()` inserts subscription first, then price history outside `database.withTransaction`.

### Fix

```kotlin
database.withTransaction {
    val id = recurringExpenseRepository.insert(
        subscription.copy(isSubscription = true)
    )
    if (request.recordPriceHistory) {
        priceHistoryDao.insert(...)
    }
}
```

Priority: **P0/P1**.

---

## Issue 2 — Assistant helper is partial

`assistantFilteredExpensesCurrencyAware()` exists but is not used everywhere.

Current problems:

```text
executeCount still raw-filters min/max
executeTotal/average use old helper
breakdowns use old helper and only convert sort key
largest still does its own partial conversion
```

### Fix

Use one helper everywhere:

```text
executeList
executeCount
executeLargest
executeTotal
executeAverage
executeCategoryBreakdown
executeMerchantBreakdown
```

Rules:

```text
do not pass min/max to SQL
use convertAsOf(expense.date)
failed conversion excluded + dataQuality
```

Priority: **P1**.

---

## Issue 3 — Legacy NL still unsafe

Legacy `NaturalLanguageSearchEngine` still:

```text
passes minAmount/maxAmount into repository filtering
does raw fallback when conversion fails
documents category/location as not really applied
uses Calendar in default search window
```

It is marked feature-contained, which is acceptable **only if UI labels it beta/legacy or routes users to Assistant**.

### Fix options

Short-term:

```text
hide/label Smart Search as legacy beta
show unsupported filters warning
```

Better:

```text
route legacy NL execution through assistant query engine
```

Priority: **P1 if visible**, otherwise containment.

---

## Issue 4 — AnalyticsInputAssembler is still partial

Good foundation, but current issues:

```text
object instead of injected class
isSharedExpense=false hardcoded
staleRateCount=0
not clearly consumed by all analytics engines
excluded reasons are coarse
```

### Fix

Make it injectable and migrate consumers.

Priority: **P1** if analytics/dashboard are core.

---

## Issue 5 — Groups still not engine-stable

Group improvements are real, but still missing:

```text
persistent settlement table
settlement DAO
recordSettlement()
balance computation including settlements
hard delete confirmation flag
lifecycle/audit events for group mutations
member removal validation
full GroupLifecycleCoordinator
```

### Recommendation

Either:

```text
A. contain Groups as beta and disable “settle” durable claims
```

or implement a focused group sprint.

Priority: **P1 if Groups enabled as production**.

---

## Issue 6 — Tax/business still mostly foundation

`TaxSettingsRepository` exists, good.

But remaining:

```text
TaxEstimator not fully settings-driven
business report MoneyAggregate needs verification
CSV formula safety needs test
business/tax updates need lifecycle method
official/demo tax-rate separation still deferred
```

### Recommendation

Mark Tax as:

```text
Estimate/Beta
```

unless you finish the tax/business sprint.

---

## Issue 7 — CI guards need hardening

The guard scripts now exist and can fail.

Good.

But:

```text
raw-money guard skips any file containing MoneyAggregateBuilder or CurrencyConverter
direct-time guard may flag legitimate adapter or existing Calendar code
no seeded guard tests
no allowlist file
```

Also current `InvestmentTracker` still uses:

```text
Calendar.getInstance()
```

so if the direct-time guard is wired strictly, `./gradlew check` may fail unless allowlisted or fixed.

### Fix

Add:

```text
scripts/guards/allowlist.txt
guard self-tests
seeded violation tests
```

Priority: **P1**.

---

# 4. Tracker state is stale

Latest tracker still says:

```text
29 fixed
41 TODO-only
18 deferred
20 deferred-design
```

But the code has moved beyond that.

Examples stale or inconsistent:

```text
C05 says TODO, but DAO insert now returns Long/List<Long.
W14 says TODO, but merchant extraction was partially fixed.
W12 says fixed, likely okay.
I02 says TODO, but updatePrice is atomic now.
I03 says TODO, but portfolio carry-forward appears implemented.
T03 says deferred-design, but TaxSettingsRepository exists.
G01/G04 say deferred-design, but parts are implemented.
W07 says TODO, but recordPriceChange is atomic; validateAndCreate still not.
```

### Required next action

Do a tracker-only reconciliation commit.

Use statuses:

```text
FIXED
PARTIAL
CONTAINED
DEFERRED
DEFERRED_DESIGN
TODO_ONLY
```

This is important because otherwise you’ll keep re-fixing already-fixed areas and miss partial ones.

---

# 5. Are pipelines regressed?

I do **not** see a broad regression to the original pipelines.

Good signs:

```text
transaction lifecycle still central
receipt/currency/privacy foundations preserved
group linked ownership now routes through TransactionLifecycleCoordinator
DB migrations are registered
new tables are additive
MoneyAmount validation is additive
```

Possible localized risks:

```text
1. subscription creation may create non-subscription recurring rows
2. guard scripts may break check or be too weak
3. group hard delete still bypasses lifecycle
4. new warranty/investment tables are unused foundations
5. analytics assembler may give false confidence if consumers assume complete shared/stale quality
```

So: **no catastrophic pipeline regression obvious**, but run compile/tests.

---

# 6. What is left?

## Must-fix before calling engines stable

```text
1. Reconcile tracker.
2. Fix SubscriptionManagerEngine.validateAndCreate:
   - transaction
   - isSubscription=true
   - baseline history atomic
3. Use assistantFilteredExpensesCurrencyAware everywhere.
4. Either fix or contain legacy NL.
5. Make analytics migration real enough for dashboard/analytics consistency.
6. Add migration tests for 117→118→119.
7. Add guard self-tests / allowlists.
8. Wire warranty lifecycle event writes or mark table as foundation only.
```

## Must-fix only if features are production-enabled

```text
Groups:
- settlement persistence
- GroupLifecycleCoordinator
- group lifecycle/audit events
- member removal validation

Tax:
- TaxEstimator uses TaxSettings
- business reports MoneyAggregate end-to-end
- CSV sanitizer tests

Investment:
- InvestmentTransaction ledger use
- portfolio allocation aggregate-safe
- raw PortfolioSummary consumers removed
```

## Safe to defer

```text
MoneyAmount minor-unit rewrite
full investment lot ledger / realized gains
official tax-rate provider
multi-currency group settlement engine
canonical export/import schema
dedicated Cloud AI audit table
full backup privacy-mode redesign
```

---

# 7. Updated engine state estimate

After these commits:

```text
Core lifecycle/currency/privacy pipelines: 88–92%
Money primitives: 85–90%
Map/location: 85–90%
Assistant: 70–78%
Legacy NL: 50–60% or contained
Analytics: 60–70%
Forecast: 65–75%
Warranty/subscription: 75–85%, but subscription creation bug is important
Investment: 70–80%, ledger still foundation
Categorization/merchant: 70–78%
Groups: 55–65% if beta, not production-stable
Tax/business: 50–60% if beta
CI guards/tests: 55–65%
```

Overall:

```text
Backend/engine layer is close to beta-stable.
Not yet production-stable.
```

---

# 8. Recommended next PR order

## PR 1 — Tracker reconciliation

No code, just truth.

## PR 2 — Subscription creation fix

```text
validateAndCreate transaction
isSubscription=true
baseline history rollback test
```

## PR 3 — Assistant unified helper migration

```text
all execute* methods use currency-aware helper
count/total/average/breakdowns consistent
```

## PR 4 — Legacy NL containment/fix

Either:

```text
route to Assistant
```

or:

```text
remove raw SQL amount filters + no raw fallback
```

## PR 5 — Migration tests

```text
117→118
118→119
117→119
fresh 119
```

## PR 6 — Analytics integration

```text
make assembler injectable
preserve shared/stale quality
migrate dashboard/analytics consumers
```

## PR 7 — Guard hardening

```text
allowlist
seeded violation tests
false-positive cleanup
```

## PR 8 — Feature decision

Decide:

```text
Groups production now? If yes, implement settlements/lifecycle.
Tax production now? If yes, implement tax/business sprint.
Investment production now? If yes, wire ledger or hide lot-level claims.
```

---

# 9. Final answer

You have made **large real progress**. The engines are much better than before.

But what is left is not zero.

The final important remaining engine work is roughly:

```text
Core must-fix: ~8–12 items
Feature-dependent fixes: ~15–25 items
Deferred design: ~10–15 items
```

If you contain Groups/Tax/Investment advanced claims, the remaining core engine work is manageable.

My recommendation:

```text
Do not start UI yet.
Do 3 more backend PRs:
1. tracker reconciliation,
2. subscription + assistant consistency,
3. migration/guard tests.
```

After those, you can honestly say:

```text
Core engines are stable; advanced engines are beta/contained.
```