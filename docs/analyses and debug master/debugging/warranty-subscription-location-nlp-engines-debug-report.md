# Warranty / Subscription / Bill Negotiation, Location / Map, and Natural Language / Voice Engines Debug Report

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local/device execution.

## 1. Executive summary

This report covers three feature clusters:

```text
A. Warranty / return windows / price protection / subscription / bill negotiation
B. Location / map / geocoding / heatmap / travel insights
C. Natural language / voice / assistant query engines
```

These are “secondary” features, but they touch sensitive and financially meaningful data:

```text
receipt OCR
raw email/notification text
merchant names
location coordinates
voice queries
AI prompts
expense totals
subscription cost
bill negotiation savings
dashboard/map analytics
```

Overall pattern:

- The subsystems are useful and partially tested.
- Several engines have clear comments documenting known limitations.
- But many paths still use raw `Double`, raw text, direct DAO writes, placeholder persistence, and inconsistent privacy/currency semantics.

Highest-risk findings:

1. Warranty protected value raw-sums `Expense.amount` and is not currency/effective-amount safe.
2. Warranty/return-window creation has no lifecycle event table and several direct DAO mutation paths.
3. Return-window refund currency exists but `markAsReturned()` does not update it.
4. Subscription price history often records `recordedAt = 0L`.
5. Subscription usage average can divide by zero for histories shorter than 30 days.
6. Subscription and bill-negotiation totals raw-sum mixed currencies.
7. Bill negotiation outcome recording is a placeholder and does not persist.
8. Bill negotiation compares monthly market rates against raw non-monthly subscription prices in UI/outcome flows.
9. Spending map markers, heatmap, and location insights use inconsistent amount semantics.
10. Location insights include non-spending transactions and mixed-currency raw totals.
11. Map/location flows can access device location without checking the app-level GPS privacy gate.
12. Merchant location correction insert uses `IGNORE` but returns `Unit`, so correction conflicts are silent.
13. Legacy natural-language search ignores parsed category/location filters.
14. Legacy merchant extraction is broken because the query is lowercased before using a capitalized-word regex.
15. Natural-language and assistant query amount filters are not currency-semantically safe.
16. Voice recognizer is not destroyed/released and raw voice text can flow into query/history/cloud paths.

Main recommendation:

> Stabilize these engines by adding explicit contracts for money, privacy, persistence, and lifecycle side effects. Then add scenario tests that prove receipt → warranty, subscription → negotiation, map totals, and voice/NL query results match dashboard/analytics contracts.

---

# 2. Warranty / return window / price protection

## 2.1 Intended architecture

Expected flow:

```text
ReceiptLifecycleCoordinator
→ ReceiptSideEffectDispatcher
→ AutoCreateWarrantyFromReceiptUseCase
→ WarrantyTextExtractor or CloudWarrantyExtractionService
→ WarrantyTrackerRepository
→ WarrantyDao / ReturnWindowDao
→ WarrantyTrackerViewModel
→ WarrantyExpirationWorker
→ NotificationService
```

Related paths:

```text
ReceiptLinkService
→ update warranties / return windows with expenseId

PriceProtectionTracker
→ recent retail/email receipts
→ parsed receipt items
→ price protection candidates
```

---

## 2.2 Strengths

Good pieces:

- Warranty and return-window entities exist.
- Both use `SET_NULL` for receipt/expense foreign keys, preserving records if receipt/expense is deleted.
- Unique `receiptId` index prevents duplicate warranty/return-window per receipt.
- `AutoCreateWarrantyFromReceiptUseCase` avoids bank statements/manual placeholders/OCR-failed receipts.
- Low-confidence warranty extraction can create a review draft.
- `WarrantyTrackerRepository.reconcileExpiredItems()` can mark expired warranties and return windows.
- `WarrantyExpirationWorker` exists and is scheduled.
- `PriceProtectionTracker` correctly filters to retail/email receipts instead of bank statements.

---

## 2.3 Finding P0-1 — Warranty protected value is not currency/effective-amount safe

`WarrantyDao.getTotalProtectedValue()` does:

```sql
SUM(COALESCE(e.amount, 0))
```

Problems:

- uses `Expense.amount`, not `effectiveAmount`,
- raw-sums mixed currencies,
- ignores home currency,
- ignores conversion failures,
- ignores shared/not-mine ownership semantics.

Example:

```text
Warranty A linked to €100 expense
Warranty B linked to $100 expense
```

The app can show:

```text
total protected value = 200
```

without currency meaning.

### Fix

Use `MultiCurrencyRepository` or a warranty-specific aggregate:

```kotlin
WarrantyProtectedValue(
    total: MoneyAggregate,
    linkedWarrantyCount: Int,
    unlinkedWarrantyCount: Int,
    warnings: List<String>
)
```

Priority: P0.

---

## 2.4 Finding P0-2 — Return-window refund currency is not updated

`ReturnWindow` has:

```kotlin
refundAmount: Double?
refundCurrency: String?
```

But `ReturnWindowDao.markAsReturned()` updates:

```text
status
returnedAt
refundAmount
updatedAt
```

It does not update:

```text
refundCurrency
```

So a multi-currency refund can be recorded without currency.

### Fix

Change DAO:

```kotlin
markAsReturned(
    returnWindowId: Long,
    returnedAt: Long,
    refundAmount: Double?,
    refundCurrency: String?,
    updatedAt: Long
)
```

If `refundCurrency == null`, infer from linked expense currency and store it explicitly.

Priority: P0.

---

## 2.5 Finding P0-3 — Warranty/return-window lifecycle has no event log

Transaction, receipt, and recurring flows now have event tables. Warranty/return-window does not.

Important state changes are currently just DAO updates:

```text
warranty created
low-confidence draft created
warranty reviewed
warranty expired
warranty claimed
return window created
return expired
item returned
expense linked
receipt deleted / FK set null
```

### Impact

You cannot debug:

```text
why warranty appeared
whether AI or regex created it
whether it was reviewed
whether worker expired it
whether receipt link changed expenseId
```

### Fix

Add:

```text
WarrantyLifecycleEvent
WarrantyLifecycleEventDao
```

Events:

```text
WARRANTY_CREATED
WARRANTY_DUPLICATE_SKIPPED
WARRANTY_REVIEW_DRAFT_CREATED
WARRANTY_REVIEW_APPROVED
WARRANTY_EXPIRED
WARRANTY_CLAIMED
RETURN_WINDOW_CREATED
RETURN_WINDOW_EXPIRED
RETURN_WINDOW_RETURNED
LINKED_TO_EXPENSE
UNLINKED_FROM_EXPENSE
SIDE_EFFECT_FAILED
```

Priority: P0/P1.

---

## 2.6 Finding P1-1 — `WarrantyTrackerRepository.addWarranty()` can persist unset timestamps

`Warranty` documents:

```text
createdAt / updatedAt must be set
0L = unset sentinel
```

`AutoCreateWarrantyFromReceiptUseCase` sets timestamps, good.

But `WarrantyTrackerRepository.addWarranty(warranty)` directly calls:

```kotlin
warrantyDao.insertWarranty(warranty)
```

It does not enforce timestamps.

Manual or UI-created warranties can accidentally persist:

```text
createdAt = 0
updatedAt = 0
```

### Fix

Repository should normalize:

```kotlin
val now = timeProvider.now()
warranty.copy(
    createdAt = warranty.createdAt.takeIf { it > 0 } ?: now,
    updatedAt = now
)
```

Priority: P1.

---

## 2.7 Finding P1-2 — Warranty AI extraction relies on AI settings path, not full privacy contract

`WarrantyTrackerRepository.extractWarrantyResult()` decides route via:

```text
AiSettingsRepository
AiCapabilityRouter
AiPolicy
```

Then calls cloud extraction.

Given Pipeline 8’s issue, `AiSettings` and `PrivacySettings` can drift. If the cloud provider itself checks `PrivacyGate`, that helps, but the repository path should still use one effective `CloudAiGuard`.

### Fix

Use:

```text
CloudAiGuard.requireAllowed(CLOUD_AI_WARRANTY_EXTRACTION)
```

and ensure redaction metadata/audit are captured.

Priority: P1.

---

## 2.8 Finding P1-3 — Warranty end-date semantics are ambiguous

Warranty end date uses:

```kotlin
purchaseDate.plusMonths(durationMonths).atStartOfDay()
```

If purchase is 2026-01-15 and duration is 12 months, this gives:

```text
2027-01-15 00:00
```

Many users expect warranty valid through the end of that day, or through 2027-01-14. The contract is not explicit.

### Fix

Use a half-open range model:

```text
warrantyStartInclusive
warrantyEndExclusive
```

Display as:

```text
valid through warrantyEndExclusive - 1 day
```

Priority: P1.

---

## 2.9 Finding P1-4 — Manual placeholder receipt hardcodes EUR and stores raw product text

`createManualPlaceholderReceipt()` creates:

```text
rawOcrText = "Manual warranty entry: $productName"
currency = "EUR"
```

Problems:

- currency ignores home/user selected currency,
- product name is stored as raw OCR text,
- documentType may remain default if not explicitly set,
- privacy retention may treat it like OCR.

### Fix

Use a dedicated manual warranty entity path, or set:

```text
documentType = MANUAL_PLACEHOLDER
rawOcrText = ""
currency = homeCurrency
metadata = sanitized product summary
```

Priority: P1.

---

# 3. Subscription management / bill negotiation

## 3.1 Intended architecture

Expected flow:

```text
recurring expense / detected candidate
→ SubscriptionManagementRepository
→ SubscriptionManagerEngine
→ SubscriptionPriceHistoryDao
→ SubscriptionUsageDao
→ recommendations
→ SmartBillNegotiationEngine
→ negotiation opportunity
→ outcome history
→ recurring amount update / savings tracking
```

---

## 3.2 Strengths

Good pieces:

- Subscriptions reuse `ManualRecurringExpense`, which aligns them with recurring/budget/forecast flows.
- `SubscriptionPriceHistory` and `SubscriptionUsage` exist.
- `SubscriptionCandidate` exists for detected candidates.
- `SubscriptionManagerEngine.recordPriceChange()` updates the recurring amount after recording a price change.
- Monthly-equivalent normalization exists in both subscription and negotiation engines.
- UI supports accepting/rejecting detected candidates.
- Bill negotiation UI is reasonably complete: opportunity cards, script dialog, outcome dialog.

---

## 3.3 Finding P0-1 — Subscription price history often has `recordedAt = 0L`

`SubscriptionPriceHistory` documents:

```text
recordedAt must be set
0L = unset sentinel
```

But these creation paths do not set it:

```kotlin
SubscriptionManagerEngine.recordPriceChange()
SubscriptionManagementViewModel.addSubscription()
SubscriptionManagementViewModel.acceptCandidate()
```

They create:

```kotlin
SubscriptionPriceHistory(subscriptionId = id, amount = amount, ...)
```

with default `recordedAt = 0L`.

### Impact

- price history ordering wrong,
- recent price-increase detection wrong,
- negotiation power wrong,
- UI price-change info wrong,
- initial and later prices can collapse to the same timestamp.

### Fix

All price history inserts must set:

```kotlin
recordedAt = timeProvider.now()
```

Repository should enforce it if callers forget.

Priority: P0.

---

## 3.4 Finding P0-2 — Subscription usage average can divide by zero

`SubscriptionManagerEngine.calculateUsageStats()` computes:

```kotlin
val monthsActive = TimePeriodUtils.daysBetween(oldestUsage, now).coerceAtLeast(1) / 30
averageUsesPerMonth = totalUses.toDouble() / monthsActive
```

If usage history is shorter than 30 days:

```text
daysBetween = 1..29
monthsActive = 0
```

Then:

```text
averageUsesPerMonth = total / 0
```

That can produce Infinity or crash depending type/coercion behavior.

### Fix

Use floating months:

```kotlin
val monthsActive = (daysBetween / 30.4375).coerceAtLeast(1.0)
```

or calendar months:

```kotlin
val monthsActive = monthsBetweenInclusive(oldestUsage, now).coerceAtLeast(1)
```

Priority: P0.

---

## 3.5 Finding P0-3 — Subscription totals raw-sum mixed currencies

`SubscriptionManagerEngine.getTotalMonthlySubscriptionCost()` and `SubscriptionManagementViewModel.totalMonthlyCost` sum `Double` values.

No `MoneyAggregate`, no source buckets, no conversion failures.

Example:

```text
Netflix 12.99 EUR
AWS 20 USD
Spotify 10 GBP
```

The UI can show one total in home currency even if not converted.

### Fix

Return:

```kotlin
MoneyAggregate totalMonthlySubscriptionCost
```

and display partial warnings.

Priority: P0.

---

## 3.6 Finding P0-4 — Price change update is not atomic

`SubscriptionManagerEngine.recordPriceChange()` does:

```text
insert SubscriptionPriceHistory
update ManualRecurringExpense.amount
```

without a transaction.

If update fails after history insert:

```text
history says new price
subscription still old price
```

If history insert fails but update succeeds in another path, also inconsistent.

### Fix

Move into repository transaction:

```text
database.withTransaction {
    insert price history
    update subscription amount
}
```

Priority: P0/P1.

---

## 3.7 Finding P1-1 — Subscription creation misses `createdAt`, currency, and validation

`SubscriptionManagementViewModel.addSubscription()` creates:

```kotlin
ManualRecurringExpense(
    merchant = merchant,
    amount = amount,
    frequency = frequency,
    nextDate = nextDate,
    isSubscription = true
)
```

It does not set:

```text
currency
createdAt
updatedAt
merchantKey
categoryId
```

Earlier recurring pipeline also found manual recurring defaults to EUR and createdAt sentinel.

### Fix

Use repository/factory:

```kotlin
SubscriptionRepository.createSubscription(command)
```

It should enforce:

```text
amount > 0
currency valid
createdAt/updatedAt set
merchant key generated
nextDate normalized
```

Priority: P1.

---

## 3.8 Finding P1-2 — Candidate accepted date calculation uses fixed millis

Accepting candidate computes next date by adding:

```text
7 / 14 / 30 / 90 / 180 / 365 days
```

This is not calendar-safe for monthly/quarterly/annual schedules.

### Fix

Use `RecurrenceCalculator.nextOccurrence()` or recurring lifecycle expander.

Priority: P1.

---

## 3.9 Finding P1-3 — Candidate uniqueness is wider than intended

`SubscriptionCandidate` documents that a partial unique index would be ideal, but Room uses:

```text
unique(canonicalMerchant, userAction)
```

This prevents multiple accepted or rejected historical candidates for the same canonical merchant/action.

This may be acceptable, but it weakens audit/history.

### Fix

Use raw SQL migration to add partial unique index for pending only, or introduce a candidate ledger/history table.

Priority: P1/P2.

---

## 3.10 Finding P0-5 — Bill negotiation outcome recording is a placeholder

`SmartBillNegotiationEngine.recordNegotiationOutcome()` says:

```text
would save to a negotiation history table
for now placeholder
```

So the UI can record outcome, but it is lost.

### Impact

- user thinks outcome saved,
- no historical savings,
- no recurring price update,
- no learning from failed/successful calls,
- no audit/debug.

### Fix

Add:

```text
NegotiationOutcomeEntity
NegotiationOutcomeDao
```

On successful/partial outcome:

```text
record outcome
update subscription amount if newMonthlyRate exists
insert price history
optionally record savings contribution
```

Priority: P0.

---

## 3.11 Finding P0-6 — Bill negotiation UI mixes monthly and raw billing-cycle prices

`SmartBillNegotiationEngine` computes:

```text
monthlyEquivalentPrice
potentialMonthlySavings
```

but returns:

```kotlin
currentPrice = subscription.amount
```

Then UI compares:

```text
currentPrice vs competitivePrice
```

where:

```text
currentPrice may be annual/quarterly/raw
competitivePrice is monthly
```

Example:

```text
annual subscription 120 EUR/year
currentPrice shown as 120
target 7.99/month
```

This is misleading.

### Fix

Opportunity should contain both:

```kotlin
rawBillingAmount
rawBillingFrequency
monthlyEquivalentPrice
competitiveMonthlyPrice
```

UI should compare monthly-to-monthly.

Priority: P0.

---

## 3.12 Finding P1-4 — Bill negotiation market rates are hardcoded and EUR-like

Market rate table is hardcoded with Greek providers and EUR-style values.

Problems:

- no region/country setting,
- no currency attached to market rate,
- no freshness timestamp,
- provider matching can misclassify COSMOTE mobile vs internet,
- no external data validation,
- no user override.

### Fix

Create:

```text
MarketRateProvider
MarketRate(currency, region, validFrom, source, confidence)
```

Until then, label the feature as heuristic/demo.

Priority: P1.

---

# 4. Location / map / geocoding

## 4.1 Intended architecture

Expected flow:

```text
SpendingMapViewModel
→ ExpenseRepository located/unlocated flows
→ LocationResolver
   → user correction
   → merchant cache
   → Nominatim geocoding
   → Overpass nearby POI
→ MerchantLocationRepository / MerchantLocationDao
→ Expense.location fields
→ SpendingHeatmapEngine
→ LocationInsightsEngine
→ AreaSpendingEngine
→ TravelDetectionEngine
```

Privacy-sensitive paths:

```text
device GPS
external geocoding
Overpass API
location cache/corrections
background location backfill
```

---

## 4.2 Strengths

Good pieces:

- `LocationResolver` has clear priority order.
- External geocoding and Overpass calls use `PrivacyGate`.
- Logs anonymize merchant names.
- Resolver rejects “Null Island” in resolved results.
- Cache is area-scoped.
- User corrections are area-scoped and prioritized.
- `LocationBackfillWorker` exists.
- Map ViewModel reacts to located-expense flow, not a capped one-time query.
- Heatmap filters spending-only transactions before calling engine.
- Tests exist for `LocationResolver`, geocoding services, map ViewModel, and location engines.

---

## 4.3 Finding P0-1 — Device GPS access is not gated by app-level GPS privacy

`SpendingMapViewModel.onPermissionResult()` calls:

```kotlin
locationProvider.getLastKnownLocation()
```

if Android permission is granted.

`LocationResolver` also calls `locationProvider.getLastKnownLocation()` before geocoding decisions.

But the app has privacy capabilities such as GPS/background location. Android permission is not the same as app-level privacy consent.

### Impact

User can disable location privacy in app settings, but map/resolver may still read device location if Android permission is granted.

### Fix

Before any `ForegroundLocationProvider` call:

```kotlin
privacyGate.check(PrivacyCapability.DEVICE_GPS_LOCATION)
```

or define:

```text
FOREGROUND_DEVICE_LOCATION
```

Priority: P0.

---

## 4.4 Finding P0-2 — Location insights include non-spending transactions

`SpendingMapViewModel` filters spending-only for heatmap:

```text
heatmapExpenses = spendingOnly
```

Good.

But it passes all located expenses to `LocationInsightsEngine.compute(domainExpenses)`.

`LocationInsightsEngine` sums all amounts and does not filter transaction types.

So place insights can include:

```text
deposits
transfers
withdrawals
unknowns
not-mine
```

### Fix

Use same canonical spending filter for:

```text
markers if desired
heatmap
place insights
area insights
travel spending
```

At least labels must distinguish “transactions at place” vs “spending at place.”

Priority: P0.

---

## 4.5 Finding P0-3 — Map/insight amounts are not consistently currency-normalized

Current map flow:

- Marker amount:
  - converts `e.amount`, not `effectiveAmount`,
  - fallback to raw amount on conversion failure,
  - no warning.
- Heatmap amount:
  - uses `e.effectiveAmount`,
  - no conversion.
- Location insights:
  - uses `e.effectiveAmount`,
  - no conversion.
- Area/travel engines:
  - use raw `effectiveAmount`.

So the map can show different totals for the same dataset.

### Fix

Introduce:

```kotlin
LocatedMoneyExpense(
    expenseId,
    location,
    originalAmount,
    originalCurrency,
    homeAmount?,
    homeCurrency,
    conversionStatus,
    transactionType,
    ownership
)
```

All map engines should consume this.

Priority: P0.

---

## 4.6 Finding P0-4 — Manual correction insert can silently fail

`MerchantLocationDao.upsertCorrection()` is:

```kotlin
@Insert(onConflict = IGNORE)
suspend fun upsertCorrection(correction: MerchantLocationCorrection)
```

The comment says callers should check return value, but return type is `Unit`.

If a correction conflicts, the UI will still proceed and `MerchantLocationRepository.saveCorrection()` will update cache as if correction persisted.

### Fix

Change to:

```kotlin
suspend fun upsertCorrection(correction): Long
```

If `-1`, update existing correction or return failure.

Priority: P0.

---

## 4.7 Finding P1-1 — Date-range filtering uses inclusive end

Map filtering uses:

```kotlin
expense.date <= dateRangeEndMs
```

Most other app period logic uses half-open ranges:

```text
[startInclusive, endExclusive)
```

Inclusive end can double-count boundary transactions between adjacent ranges.

### Fix

Use:

```kotlin
expense.date < dateRangeEndMs
```

Priority: P1.

---

## 4.8 Finding P1-2 — `LocationResolver` may fetch device location earlier than necessary

Resolver computes `deviceLocation` before checking global cache fallback and before determining whether GPS bias is needed.

This can be privacy/battery-sensitive.

### Fix

Fetch device location only when:

```text
GPS-bias branch needed,
Overpass branch needed,
area-scoped correction/cache requires it and GPS privacy allowed.
```

Priority: P1.

---

## 4.9 Finding P1-3 — Cache/correction coordinate validation is incomplete

Resolver rejects null-island geocoder results, but `MerchantLocationRepository.saveCorrection()` and `saveLocation()` do not visibly validate:

```text
lat finite
lon finite
lat in -90..90
lon in -180..180
not null island
```

### Fix

Add `GeoCoordinate` value object or validation in repository.

Priority: P1.

---

## 4.10 Finding P1-4 — Area and travel engines raw-sum mixed currencies

`AreaSpendingEngine` and `TravelDetectionEngine` sum `effectiveAmount`.

No currency conversion, no partial warnings.

### Fix

Use normalized located expense input as above.

Priority: P1.

---

# 5. Natural language / voice / assistant queries

## 5.1 Intended architecture

There are two overlapping query systems:

### Legacy Smart Search

```text
NaturalLanguageSearchViewModel
→ NaturalLanguageSearchEngine
→ NaturalLanguageExpenseQueryRepository
→ ExpenseDao date-bounded paging
→ in-memory filters
```

### AI Assistant

```text
AssistantViewModel
→ InterpretFinancialQueryUseCase
→ HybridQueryInterpretationService
→ Cloud/OnDevice/NoOp provider
→ ExecuteFinancialQueryUseCase
→ ExpenseRepository filtered DAO queries
→ MapFinancialQueryToNavigationUseCase
```

Voice:

```text
NaturalLanguageSearchEngine
→ SpeechInputGateway
→ AndroidSpeechInputGateway
→ SpeechRecognizer
```

---

## 5.2 Strengths

Good pieces:

- AI assistant has a structured intent model.
- Assistant can map to transaction drilldown filters.
- Assistant persists conversation only when configured.
- Cloud query interpretation checks privacy gate.
- Natural-language tests exist.
- Voice gateway checks `RECORD_AUDIO` permission and recognizer availability.
- Legacy NL search comments explicitly document several limitations.
- `ExecuteFinancialQueryUseCase` pushes many filters down into repository queries.

---

## 5.3 Finding P0-1 — Legacy merchant extraction is effectively broken

`NaturalLanguageSearchEngine.interpretQuery()` does:

```kotlin
val normalized = query.lowercase()
val merchants = extractMerchants(normalized)
```

`extractMerchants()` uses:

```regex
(?:at|from)\s+([A-Z][a-zA-Z]+)
```

But the input is lowercased, so `[A-Z]` never matches.

### Impact

Queries like:

```text
show purchases at Amazon
how much did I spend at Lidl
```

will not extract merchants.

### Fix

Run merchant extraction on original query, or use case-insensitive merchant matching against known merchants.

Priority: P0.

---

## 5.4 Finding P0-2 — Legacy category/location filters are parsed but ignored

The engine comments already say:

```text
category and location filters are parsed but not applied
```

Execution only filters merchants and amounts.

Queries like:

```text
find food expenses in Paris
show groceries over 50
```

can return all date-range transactions except merchant/amount filtering.

### Fix

Push filters to repository/DAO:

```text
categoryIds
location bounding/search
merchant
amount
transaction type
ownership
```

Priority: P0.

---

## 5.5 Finding P0-3 — Amount filter currency semantics are unsafe

Legacy NL:

- extracts numeric amount,
- does not preserve currency,
- compares expense amounts normalized to home currency,
- if conversion fails, falls back to raw amount.

Assistant query:

- `ExecuteFinancialQueryUseCase` explicitly says amount filters use raw `effectiveAmount` regardless of currency.

So:

```text
“expenses over $50”
“expenses over €50”
```

can behave the same or wrong depending path.

### Fix

Use:

```kotlin
ExtractedAmount(
    value,
    currency,
    comparison
)
```

Then compare using:

```text
convert expense to filter currency as-of expense.date
or convert both to home with explicit policy
```

If conversion fails, exclude from numeric comparison and return partial warning.

Priority: P0.

---

## 5.6 Finding P0-4 — Cloud query interpretation can send raw query text without central redaction

`CloudQueryInterpretationService` checks `PrivacyGate(CLOUD_AI_GENERAL)`, good.

But request body builds prompt from input and does not visibly apply a central redactor for:

```text
merchant names
people names
IBAN/account/card
salary/employer terms
medical/pharmacy references
free-text query
```

This repeats Pipeline 8.

### Fix

Use:

```text
CloudAiGuard
CloudPayloadRedactor.redactFinancialQuery()
```

Audit:

```text
redactionApplied
payloadHash
rawTextIncluded=false
```

Priority: P0.

---

## 5.7 Finding P1-1 — Legacy NL repository does date-only broad paging

`NaturalLanguageExpenseQueryRepositoryImpl.getExpensesBetween()` pages all expenses in a date range, then filters in memory.

This can be slow and inconsistent for large datasets.

### Fix

Use repository/DAO filtered query similar to `ExecuteFinancialQueryUseCase`.

Priority: P1.

---

## 5.8 Finding P1-2 — Offset paging is not snapshot-stable

Natural-language repository uses offset paging.

Concurrent inserts/deletes can skip or duplicate rows during query.

### Fix

Use keyset pagination or one DB transaction snapshot.

Priority: P1.

---

## 5.9 Finding P1-3 — Assistant “largest” query is raw mixed-currency

`ExecuteFinancialQueryUseCase.executeLargest()` chooses:

```kotlin
maxByOrNull { effectiveAmount }
```

So:

```text
100 JPY
10 EUR
```

can select 100 JPY as larger.

### Fix

Normalize before ranking or show per-currency largest.

Priority: P1.

---

## 5.10 Finding P1-4 — Assistant query totals do not expose conversion partial state

Category/merchant breakdowns use `convertMultiple()` for sorting, but displayed totals are per-currency strings.

That is transparent, but sorting can silently exclude failed conversions or use current rates. No warning is returned.

### Fix

Return:

```text
FinancialQueryResult.dataQuality
conversionWarnings
sortPolicy
```

Priority: P1.

---

## 5.11 Finding P1-5 — Conversation history can store raw sensitive queries

`AssistantViewModel.persistUserTurn()` stores raw user query when history is enabled.

Examples:

```text
salary from employer
payments to doctor
IBAN/account query
merchant/person names
```

### Fix

Add settings:

```text
storeRawAssistantQueries
storeSanitizedAssistantQueries
assistantHistoryRetentionDays
```

If raw retention disabled:

```text
store redacted query + hash
```

Priority: P1.

---

## 5.12 Finding P1-6 — Voice recognizer lifecycle is incomplete

`AndroidSpeechInputGateway` creates and stores `SpeechRecognizer`, but there is no visible:

```text
destroy()
release()
ViewModel onCleared integration
```

It requests partial results but ignores `onPartialResults`.

It also permits repeated `startListening()` without stopping/destroying previous listener state.

### Fix

Add:

```kotlin
fun destroy()
```

and call from ViewModel/screen lifecycle.

Handle:

```text
already listening
partial results
timeout/no speech
permission revoked mid-session
```

Priority: P1.

---

# 6. Cross-cutting debugging checklist

## Warranty/subscription/bill negotiation

Check:

- [ ] warranty created once per receipt,
- [ ] return window created once per receipt/expense,
- [ ] refund currency stored,
- [ ] warranty/return lifecycle events exist,
- [ ] protected value uses `effectiveAmount` + currency conversion,
- [ ] expiry worker writes events and respects delivery result,
- [ ] subscription price history timestamps set,
- [ ] price update atomic with history insert,
- [ ] subscription totals are `MoneyAggregate`,
- [ ] usage average cannot divide by zero,
- [ ] negotiation outcome persists,
- [ ] negotiation updates recurring amount/price history when successful,
- [ ] bill negotiation compares monthly-to-monthly.

## Location/map

Check:

- [ ] GPS privacy gate before device location access,
- [ ] external geocoding privacy gate,
- [ ] Overpass privacy gate,
- [ ] manual correction insert result checked,
- [ ] invalid coordinates rejected,
- [ ] heatmap/place/area/travel use same transaction-type policy,
- [ ] all map totals are currency-normalized or visibly partial,
- [ ] date range is half-open,
- [ ] cache stale eviction tested,
- [ ] user correction updates future resolution.

## Natural language/voice

Check:

- [ ] merchant extraction works after lowercasing fix,
- [ ] category filters applied,
- [ ] location filters applied or clearly unsupported,
- [ ] amount currency parsed and preserved,
- [ ] conversion failures visible,
- [ ] assistant and legacy search agree for same query,
- [ ] largest/average queries are currency-safe,
- [ ] raw query retention setting,
- [ ] cloud query redaction,
- [ ] speech recognizer destroyed,
- [ ] voice permission denial handled.

---

# 7. Recommended fix plan

## PR 1 — Subscription/warranty timestamp and money safety

Fix:

```text
SubscriptionPriceHistory.recordedAt
SubscriptionUsage average divide-by-zero
Warranty add timestamps
ReturnWindow refundCurrency
Warranty protected value MoneyAggregate
```

Priority: P0.

---

## PR 2 — Bill negotiation persistence and monthly contract

Add:

```text
NegotiationOutcomeEntity
NegotiationOutcomeDao
monthlyEquivalentPrice field
rawBillingAmount field
marketRate currency/region
```

Priority: P0.

---

## PR 3 — Location privacy and amount normalization

Add:

```text
GPS privacy gate
LocatedMoneyExpense
MoneyAggregate map engine inputs
manual correction insert result handling
```

Priority: P0.

---

## PR 4 — Natural language correctness

Fix:

```text
merchant extraction
category filter
amount currency parsing
conversion failure warnings
assistant largest query normalization
```

Priority: P0/P1.

---

## PR 5 — Privacy/retention for voice and assistant

Add:

```text
redacted query storage
assistant history retention
cloud query redactor
voice recognizer destroy
```

Priority: P1.

---

# 8. Tests to add

## Warranty/subscription/bill negotiation

```text
WarrantyProtectedValueMultiCurrencyTest
ReturnWindowRefundCurrencyTest
WarrantyLifecycleEventDbTest
SubscriptionPriceHistoryTimestampTest
SubscriptionUsageShortHistoryNoDivideByZeroTest
SubscriptionPriceChangeAtomicityTest
SubscriptionMonthlyMoneyAggregateTest
BillNegotiationMonthlyContractTest
BillNegotiationOutcomePersistenceTest
```

## Location/map

```text
MapDeviceLocationPrivacyGateTest
MerchantLocationCorrectionConflictTest
MapMoneyNormalizationContractTest
LocationInsightsSpendingOnlyTest
LocationDateRangeHalfOpenTest
TravelDetectionMultiCurrencyPartialTest
LocationCorrectionFutureResolutionScenarioTest
```

## Natural language/voice

```text
NaturalLanguageMerchantExtractionRegressionTest
NaturalLanguageCategoryFilterExecutionTest
NaturalLanguageAmountCurrencyFilterTest
AssistantLargestMultiCurrencyTest
AssistantQueryRedactionCloudContractTest
AssistantRawHistoryRetentionTest
SpeechRecognizerLifecycleTest
VoiceQueryEndToEndContractTest
```

---

# 9. Suggested canonical scenarios

## Scenario A — `receipt_to_warranty_return_window_contract`

Seed:

```text
home currency EUR
receipt:
  merchant = Apple
  total = 999 USD
  parsedDate = 2026-05-01
  OCR text says "1 year warranty, 14 day returns"
exchange rate:
  USD→EUR = 0.90
linked expense:
  effectiveAmount = 899.10 EUR
```

Expected:

```text
Warranty row created once
ReturnWindow row created once
WarrantyLifecycleEvent.WARRANTY_CREATED
ReturnWindow refundCurrency stored when returned
protected value = 899.10 EUR
expiration worker marks expired only when due
backup/restore preserves warranty + return window
```

---

## Scenario B — `subscription_negotiation_monthly_contract`

Seed:

```text
annual subscription:
  merchant = Netflix
  amount = 120 EUR
  frequency = ANNUALLY
price history:
  initial 100 EUR/year at valid timestamp
  increase 120 EUR/year at valid timestamp
usage:
  one usage in last 10 days
```

Expected:

```text
monthly equivalent = 10 EUR
usage average finite
price history sorted correctly
negotiation compares 10 EUR/month vs market monthly rates
outcome SUCCESS persists
new monthly/annual amount updates recurring rule according to policy
```

---

## Scenario C — `location_map_privacy_currency_contract`

Seed:

```text
privacy:
  DEVICE_GPS_LOCATION disabled
  EXTERNAL_GEOCODING disabled
expenses:
  EUR purchase located
  USD purchase located with rate
  GBP purchase located missing rate
  EUR deposit located
```

Expected:

```text
no device GPS call
no external geocoder call
heatmap excludes deposit
place insights exclude deposit or label non-spend explicitly
EUR+USD converted to home
GBP missing produces partial warning
manual correction insert conflict is visible
```

---

## Scenario D — `voice_natural_language_query_contract`

Input voice query:

```text
"Show Amazon expenses over 50 dollars last month"
```

Expected:

```text
speech permission checked
recognizer result becomes text
merchant = Amazon extracted
amount = 50 USD extracted
period = previous calendar month
amount comparison converts expenses to USD or common currency
category/location filters if present are applied
cloud redaction applied if cloud interpretation used
raw query stored only if user allows history
drilldown filter matches result count
```

---

# 10. Final recommendation

Stabilize in this order:

```text
1. Fix subscription timestamp/divide-by-zero bugs.
2. Fix warranty/return-window money and refund-currency contracts.
3. Persist bill negotiation outcomes and fix monthly-vs-raw display.
4. Add GPS privacy gate before all device-location access.
5. Normalize all map/location totals with MoneyAggregate.
6. Fix location correction conflict handling.
7. Fix legacy NL merchant extraction and category/location filtering.
8. Make NL/assistant amount filters currency-aware.
9. Redact/retain assistant and voice queries according to privacy settings.
10. Add the four canonical scenarios above.
```

Guiding rule:

> These engines should never present raw mixed-currency, privacy-sensitive, or placeholder-persisted data as trustworthy.

Second guiding rule:

> If a feature generates advice — warranty expiry, cancel subscription, negotiate bill, visit map, ask assistant — it must expose its data quality and privacy assumptions.

---

# Sources

## Architecture / maps

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- Route/ViewModel map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/build/reports/architecture/route-viewmodel-map.md

- Test inventory:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docsplans/_all_rel_paths.txt

## Warranty / price / subscription / negotiation

- `WarrantyTrackerRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt

- `AutoCreateWarrantyFromReceiptUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt

- `Warranty.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/Warranty.kt

- `ReturnWindow.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReturnWindow.kt

- `WarrantyDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyDao.kt

- `ReturnWindowDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReturnWindowDao.kt

- `WarrantyExpirationWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt

- `WarrantyTextExtractor.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt

- `PriceProtectionTracker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/price/PriceProtectionTracker.kt

- `SubscriptionManagerEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt

- `SubscriptionManagementRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/SubscriptionManagementRepository.kt

- `SubscriptionManagementViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/subscription/SubscriptionManagementViewModel.kt

- `SubscriptionPriceHistory.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionPriceHistory.kt

- `SubscriptionUsage.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionUsage.kt

- `SubscriptionCandidate.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt

- `SmartBillNegotiationEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/negotiation/SmartBillNegotiationEngine.kt

- `BillNegotiationViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/negotiation/BillNegotiationViewModel.kt

## Location / map

- `SpendingMapViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt

- `LocationResolver.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/location/LocationResolver.kt

- `CompositeGeocodingService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/CompositeGeocodingService.kt

- `OverpassNearbyService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/OverpassNearbyService.kt

- `LocationBackfillWorker.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/location/LocationBackfillWorker.kt

- `MerchantLocationRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/MerchantLocationRepository.kt

- `MerchantLocation.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/MerchantLocation.kt

- `MerchantLocationDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/MerchantLocationDao.kt

- `SpendingHeatmapEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/location/SpendingHeatmapEngine.kt

- `LocationInsightsEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/location/LocationInsightsEngine.kt

- `AreaSpendingEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt

- `TravelDetectionEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt

## Natural language / voice / assistant

- `NaturalLanguageSearchViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt

- `NaturalLanguageSearchEngine.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt

- `NaturalLanguageExpenseQueryRepositoryImpl.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/NaturalLanguageExpenseQueryRepositoryImpl.kt

- `AndroidSpeechInputGateway.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/speech/AndroidSpeechInputGateway.kt

- `AssistantViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt

- `InterpretFinancialQueryUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/InterpretFinancialQueryUseCase.kt

- `ExecuteFinancialQueryUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt

- `HybridQueryInterpretationService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/HybridQueryInterpretationService.kt

- `CloudQueryInterpretationService.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudQueryInterpretationService.kt

- `MapFinancialQueryToNavigationUseCase.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt

- `NaturalLanguageModule.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/di/NaturalLanguageModule.kt