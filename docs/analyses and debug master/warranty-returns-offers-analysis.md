# Warranty / Returns / Price Protection / Bill Negotiation Deep Analysis

Branch: `master-refactor`

Scope reviewed:

- warranty extraction from receipts
- return-window creation and lifecycle
- warranty reminder worker
- warranty tracker UI/ViewModel
- price protection tracker
- deals/coupons/credit-card benefit suggestions
- bill negotiation engine/UI
- receipt linkage implications

This is a static review.

---

## Executive verdict

This area is useful but currently not safe enough to treat as a reliable “asset protection” system.

The biggest issue is that warranties, return windows, price protection, and receipt matching are connected loosely through `receiptId` / `expenseId`, but there is no single lifecycle coordinator.

Highest-risk problems:

1. receipt → expense matching does not propagate to warranties/return windows
2. confirming low-confidence warranties does not make them active
3. rejected warranty drafts can leave stale return windows
4. manual warranties are backed by fake placeholder receipts
5. price protection uses receipt scan date instead of purchase date
6. price protection/deals/coupons are simulated but shown like real opportunities
7. warranty/return values raw-sum amounts/currencies
8. warranty reminders can repeat daily without sent-state

Core fix:

> Create one `ReceiptAssetCoordinator` that owns warranty, return-window, price-protection, and receipt/expense linkage updates.

---

# Architecture observed

## Warranty creation path A — local regex extraction

```text
ReceiptRepository.processReceipt()
→ save ScannedReceipt
→ AutoCreateWarrantyFromReceiptUseCase.execute(receiptId, ocrText)
→ WarrantyTextExtractor
→ WarrantyTrackerRepository.addWarrantyIgnoreConflicts()
→ WarrantyTrackerRepository.upsertReturnWindowForReceipt()
```

This is the normal receipt-processing path.

## Warranty creation path B — cloud extraction

```text
WarrantyTrackerRepository.extractWarrantyFromReceipt()
→ AiCapabilityRouter.decide(WARRANTY_EXTRACTION)
→ CloudWarrantyExtractionService.extractWarranty()
→ WarrantyExtractionResult.toWarrantyEntityOrNull()
```

This is a separate path and has different validation/metadata behavior.

## Return-window path

```text
WarrantyTrackerRepository.upsertReturnWindowForReceipt()
→ optional cloud extraction
→ default merchant return days
→ insert/update ReturnWindow
```

## Reminder path

```text
WarrantyExpirationWorker
→ reconcileExpiredItems()
→ getWarrantiesExpiringSoon(7)
→ send notification
→ getWarrantiesExpiringSoon(30)
→ send notification
```

## Price protection path

```text
PriceProtectionViewModel
→ PriceProtectionTracker.getPriceProtectedItems()
→ ScannedReceipt.parsedItems JSON
→ simulated price/deal/coupon/benefit checks
```

## Bill negotiation path

```text
BillNegotiationViewModel
→ SmartBillNegotiationEngine.analyzeNegotiationOpportunities()
→ ManualRecurringExpenseDao.getAll()
→ hardcoded market rate table
→ negotiation opportunities/scripts
```

---

# Critical / high-priority findings

## 1. Receipt-to-expense matching does not update warranty/return `expenseId`

### Where

- `ReceiptRepository.linkReceiptToExpense()`
- `ReceiptRepository.approveMatchSuggestion()`
- `ReceiptRepository.createExpenseFromReceipt()`
- `Warranty.expenseId`
- `ReturnWindow.expenseId`

### Problem

When a receipt is linked to an expense, only `ScannedReceipt.expenseId` is updated.

Existing `Warranty` and `ReturnWindow` rows created before matching keep their old `expenseId`, often `null`.

### Impact

A receipt can be matched to an expense, but:

- warranty still has `expenseId = null`
- return window still has `expenseId = null`
- protected value query misses the linked expense
- audit trail is incomplete
- deleting/clearing match can leave inconsistent asset links

### Example

1. Receipt scanned.
2. Warranty auto-created while receipt is unmatched.
3. Later receipt is matched to expense `123`.
4. `scanned_receipts.expenseId = 123`.
5. `warranties.expenseId` remains `null`.

### Severity

**Critical**

### Fix

Every receipt match/unmatch must propagate to receipt-derived assets.

Add coordinator method:

```kotlin
ReceiptAssetCoordinator.onReceiptLinkedToExpense(receiptId, expenseId)
ReceiptAssetCoordinator.onReceiptUnlinked(receiptId)
```

Update:

- warranties
- return windows
- price-protection item records if persisted later
- receipt item categorizations if needed

---

## 2. Confirming a low-confidence warranty does not set status to `ACTIVE`

### Where

`WarrantyTrackerViewModel.confirmWarranty()`

### Problem

The UI confirm action does:

```kotlin
warranty.copy(
    needsReview = false,
    updatedAt = System.currentTimeMillis()
)
```

It does **not** set:

```kotlin
status = WarrantyStatus.ACTIVE
```

But low-confidence drafts are created with:

```kotlin
status = PENDING_REVIEW
needsReview = true
```

### Impact

A user can “confirm” a warranty and it remains `PENDING_REVIEW`.

Result:

- not counted as active
- not returned by active warranty queries
- not included in expiring reminders
- not included in protected value
- UI state becomes misleading

### Severity

**Critical**

### Fix

Confirm should validate/correct fields and then promote:

```kotlin
status = WarrantyStatus.ACTIVE
needsReview = false
updatedAt = timeProvider.now()
```

Better: call `AutoCreateWarrantyFromReceiptUseCase.createWarrantyForReview()` or a coordinator method instead of directly updating the entity.

---

## 3. Rejecting an auto-detected warranty can leave a stale return window

### Where

- `AutoCreateWarrantyFromReceiptUseCase.createReviewDraftWarranty()`
- `persistReturnWindow()`
- `WarrantyTrackerViewModel.rejectAutoDetectedWarranty()`

### Problem

Low-confidence warranty drafts can create a return window.

Rejecting the warranty only deletes the warranty:

```kotlin
warrantyRepository.deleteWarranty(warranty)
```

It does not delete or mark rejected the associated `ReturnWindow`.

### Impact

A false warranty extraction can still leave a return reminder or returnable item.

Example:

- OCR falsely detects warranty for “Unknown Product”
- return window is created for same receipt
- user rejects warranty
- return window remains

### Severity

**Critical**

### Fix

Reject should call:

```kotlin
ReceiptAssetCoordinator.rejectWarrantyDraft(warrantyId)
```

Policy options:

1. delete associated auto-generated return window if it came from the same extraction
2. keep return window only if independently confirmed
3. mark both as rejected/dismissed

Need source metadata on return windows:

```text
source = OCR / CLOUD_AI / MANUAL / MERCHANT_DEFAULT
needsReview
extractionConfidence
linkedWarrantyId
```

---

## 4. Manual warranties create fake placeholder receipts

### Where

- `WarrantyTrackerViewModel.addManualWarranty()`
- `WarrantyTrackerRepository.createManualPlaceholderReceipt()`

### Problem

Manual warranty creation requires a `receiptId`, so the repository creates a fake `ScannedReceipt`:

```text
rawOcrText = "Manual warranty entry: ..."
currency = "EUR"
confidence = 1f
```

### Impact

This fake receipt can appear in receipt lists, receipt matching, debug exports, backup exports, and downstream receipt-based features.

It also hardcodes currency to EUR.

### Severity

**High**

### Fix

Change schema/model so warranties do not require a receipt.

Recommended:

```kotlin
receiptId: Long?
sourceType: MANUAL / RECEIPT / EMAIL / IMPORT
```

Use `ON DELETE SET NULL`, not `CASCADE`, for receipt links if the warranty should survive receipt deletion.

Manual warranty should not create a fake receipt.

---

## 5. Deleting a receipt deletes warranties and return windows

### Where

- `Warranty.receiptId` FK uses `ON DELETE CASCADE`
- `ReturnWindow.receiptId` FK uses `ON DELETE CASCADE`

### Problem

If a user deletes a receipt image/record for privacy or cleanup, the warranty and return-window records are deleted too.

### Impact

Asset-protection history can disappear when the user thinks they are deleting only a receipt.

### Severity

**High**

### Fix

Use:

```text
receiptId nullable
ON DELETE SET NULL
```

and keep immutable source metadata:

```text
sourceReceiptMerchant
sourceReceiptDate
sourceReceiptTotal
sourceReceiptCurrency
```

For hard delete, show explicit warning:

> “This will also delete warranty and return records.”

---

## 6. One warranty per receipt is too restrictive

### Where

`Warranty` entity:

```kotlin
Index(value = ["receiptId"], unique = true)
```

### Problem

A receipt can contain multiple warrantied items, but schema allows only one warranty per receipt.

The cloud prompt also asks for the “most expensive or main item.”

### Impact

A receipt with a laptop and camera can only track one warranty.

### Severity

**High**

### Fix

Model warranties per receipt item:

```text
warranty.receiptId
warranty.receiptItemId / itemFingerprint
productName
purchaseAmount
currency
```

Unique key should be something like:

```text
receiptId + itemFingerprint
```

not just `receiptId`.

---

## 7. Return-window uniqueness is inconsistent

### Where

`ReturnWindow` entity:

```kotlin
Index(value = ["receiptId"])
Index(value = ["expenseId"], unique = true)
```

DAO:

```kotlin
getReturnWindowByReceiptId(receiptId): ReturnWindow?
```

### Problem

Schema allows multiple return windows per receipt, but the repository treats receipt lookup as if there is only one.

Also, only one return window per expense is allowed.

### Impact

Multi-item receipts cannot represent different return deadlines.

DAO behavior becomes ambiguous if multiple rows exist for one receipt.

### Severity

**High**

### Fix

Decide model:

Option A — one return window per receipt  
Make `receiptId` unique.

Option B — one return window per item  
Add item identity and use:

```text
receiptId + itemFingerprint
```

Recommended: **Option B**.

---

## 8. Warranty protected value raw-sums gross expense amount

### Where

`WarrantyDao.getTotalProtectedValue()`

Query sums:

```sql
SUM(COALESCE(e.amount, 0))
```

### Problems

- uses gross `amount`, not `effectiveAmount`
- raw-sums currencies
- ignores manual warranties without linked expense
- ignores receipt parsed total if warranty is not linked to expense
- no protected item-level value

### Impact

Protected value can be financially wrong.

Example:

- €100 shared item, my share €25 → protected value shows €100
- €100 + $100 → protected value shows 200
- manual warranty → protected value 0

### Severity

**Critical if shown as financial value**

### Fix

Store warranty item purchase value:

```text
purchaseAmount
currency
baseAmount
baseCurrency
```

Then aggregate using normalized/base money.

---

## 9. Warranty end-date semantics expire too early

### Where

- `WarrantyTextExtractor.calculateWarrantyEndDate()`
- `AutoCreateWarrantyFromReceiptUseCase.calculateWarrantyEndDate()`
- `WarrantyTrackerRepository.toCalendarMonthEndDate()`
- `WarrantyDao.getActiveWarranties()`

### Problem

Warranty end dates are stored at start of day after adding months.

Active query uses:

```sql
warrantyEndDate > currentTime
```

If `warrantyEndDate` is `2026-04-26 00:00`, the warranty is expired for the whole displayed date `Apr 26`.

### Impact

UI may show “expires Apr 26,” but the app treats it as expired at the first millisecond of Apr 26.

### Severity

**High**

### Fix

Use half-open semantics explicitly:

```text
validFrom = purchaseStart
validUntilExclusive = startOfDayAfterLastCoveredDay
```

Display the last covered day:

```text
validUntilExclusive - 1 day
```

Or store date-only fields instead of raw millis.

---

## 10. Worker reminder text is inaccurate

### Where

`WarrantyExpirationWorker`

It sends:

- “expires in 7 days” for every warranty returned by `getWarrantiesExpiringSoon(7)`
- “expires in 30 days” for every warranty returned by `getWarrantiesExpiringSoon(30)` excluding 7-day list

### Problem

`getWarrantiesExpiringSoon(7)` returns a window, not exactly day 7.

A warranty expiring tomorrow can get “expires in 7 days.”

### Severity

**High**

### Fix

Compute actual days remaining per warranty and format:

```text
expires today
expires tomorrow
expires in N days
```

If you want stages, query exact stage windows.

---

## 11. Warranty reminders can repeat every day

### Where

`WarrantyExpirationWorker`

### Problem

No persisted reminder state exists:

- stage sent
- dismissed
- snoozed
- last sent
- notification id

### Impact

The same warranty can notify every daily worker run.

### Severity

**High**

### Fix

Add:

```kotlin
WarrantyReminderState(
    warrantyId,
    stageDays,
    warrantyEndDate,
    lastSentAt,
    dismissedAt,
    snoozedUntil,
    notificationId
)
```

Unique:

```text
warrantyId + warrantyEndDate + stageDays
```

---

## 12. `markWarrantyAsClaimed()` does not set `claimedAt`

### Where

`WarrantyTrackerRepository.markWarrantyAsClaimed()`

### Problem

It updates status to `CLAIMED`, but `Warranty.claimedAt` remains null.

### Impact

Claim history is incomplete.

### Severity

**High**

### Fix

DAO method should update:

```text
status = CLAIMED
claimedAt = now
updatedAt = now
```

---

## 13. Return “marked returned” is not linked to a refund expense

### Where

`WarrantyTrackerRepository.markAsReturned()`
`ReturnWindowDao.markAsReturned()`

### Problem

Marking returned stores:

```text
status = RETURNED
returnedAt
refundAmount
```

But it does not:

- create a refund/deposit expense
- link to an existing refund
- store refund currency
- validate refund amount
- affect budgets/cash flow

### Impact

The app can say an item was returned but financial history may not include the refund.

### Severity

**High**

### Fix

Create `ReturnResolution` or extend `ReturnWindow`:

```text
linkedRefundExpenseId
refundAmount
refundCurrency
refundStatus
returnedAt
```

Offer:

1. link existing refund
2. create refund transaction
3. mark returned without financial refund, explicitly

---

## 14. Return refund amount has no currency

### Where

`ReturnWindow.refundAmount`

### Problem

`refundAmount` is a raw `Double`.

### Impact

A refund of `50` has no currency meaning.

### Severity

**High with multi-currency**

### Fix

Add:

```text
refundCurrency
refundBaseAmount
conversionStatus
```

---

## 15. Cloud warranty extraction bypasses the stronger AI abstraction

### Where

`WarrantyTrackerRepository.extractWarrantyResult()`

It injects concrete:

```kotlin
CloudWarrantyExtractionService
```

and only runs when router returns `CLOUD`.

### Problems

- no hybrid provider abstraction
- no on-device/local fallback in this path
- cloud service itself only checks API key
- provider-side `CloudAiGate` missing
- cloud route failure returns null instead of using deterministic fallback

### Severity

**High / privacy + reliability**

### Fix

Use:

```kotlin
WarrantyExtractionService
HybridWarrantyExtractionService
CloudWarrantyExtractionService
OnDeviceWarrantyExtractionService
NoOpWarrantyExtractionService
```

Every cloud provider should call `CloudAiGate`.

---

## 16. Cloud warranty extraction ignores confidence when creating warranty

### Where

`WarrantyTrackerRepository.toWarrantyEntityOrNull()`

### Problem

`WarrantyExtractionResult.confidence` is validated as 0–1, but entity creation does not use it for:

- thresholding
- `needsReview`
- `autoDetected`
- `extractionConfidence`
- `extractionSource`

### Impact

A low-confidence cloud result can create a warranty as if it were manual/default.

### Severity

**High**

### Fix

Apply thresholds:

```text
>= 0.80 → ACTIVE
0.40–0.80 → PENDING_REVIEW
< 0.40 → skip
```

Persist:

```text
autoDetected = true
extractionSource = "cloud_ai"
extractionConfidence = confidence * 100
needsReview = confidence < threshold
```

---

## 17. Cloud vs local confidence scales are inconsistent

### Where

- `WarrantyTextExtractor` returns confidence `0..100`
- `WarrantyExtractionResult` returns confidence `0..1`
- `Warranty.extractionConfidence` is a raw `Double`
- UI displays `extractionConfidence.toInt()` as percent

### Impact

A confidence of `0.95` can display as `0%` if persisted directly.

A confidence of `95` and `0.95` are both possible unless normalized.

### Severity

**High**

### Fix

Use one convention:

```text
confidence: Float in 0.0..1.0
```

Format as percent only at UI.

Migrate existing rows carefully.

---

## 18. Low-confidence local drafts create default fake warranty values

### Where

`AutoCreateWarrantyFromReceiptUseCase.createReviewDraftWarranty()`

### Problem

If duration/date are missing, it creates fallback values:

```text
durationMonths = 12
productName = "Unknown Product"
merchantName = "Unknown Merchant"
```

### Impact

The review queue can contain plausible but invented warranty data.

Since confirm currently only clears `needsReview`, the user can accidentally confirm a fake 12-month warranty.

### Severity

**High**

### Fix

Drafts should support missing fields:

```text
durationMonths: Int?
warrantyEndDate: Long?
missingFields
```

UI should require correction before activation.

---

## 19. Warranty review UI cannot edit extracted fields

### Where

`WarrantyTrackerScreen`

### Problem

For `needsReview`, actions are only:

- Confirm
- Reject

No edit/correction UI for:

- product name
- merchant
- purchase date
- warranty duration
- warranty type
- support info

### Impact

Users can only accept or delete bad extracted data.

### Severity

**High**

### Fix

Confirm should open an edit form prefilled with extracted data.

Only after validation should it activate the warranty.

---

## 20. Manual warranty path is not transactional

### Where

`WarrantyTrackerViewModel.addManualWarranty()`

Flow:

```text
createManualPlaceholderReceipt()
addWarranty()
```

### Problem

If warranty insert fails after placeholder receipt creation, a fake receipt remains.

### Severity

**Medium / High**

### Fix

Repository method:

```kotlin
createManualWarranty(...)
```

inside one DB transaction, or remove fake receipt requirement entirely.

---

# Price protection findings

## 21. Price protection uses receipt scan date instead of purchase date

### Where

`PriceProtectionTracker`

It uses:

```kotlin
receipt.createdAt
```

for:

- recent receipt filter
- `purchaseDate`
- eligibility calculation
- days remaining

### Impact

An old receipt scanned today can appear eligible for price protection.

Example:

- Bought TV January 1.
- Scanned receipt April 26.
- App treats April 26 as purchase date and says price protection is active.

### Severity

**Critical**

### Fix

Use:

```kotlin
receipt.parsedDate ?: receipt.createdAt
```

But if `parsedDate` is missing, mark eligibility uncertain and require user confirmation.

---

## 22. Price-protection window ignores merchant-specific return window

### Where

`PriceProtectionTracker.isEligibleForPriceProtection()`
`PriceDropAlert.daysRemaining`

### Problem

Eligibility uses hardcoded 30 days.

But `getReturnWindow()` returns merchant-specific windows like 14, 15, 30, 90.

### Impact

Apple/Best Buy items can be over-shown as eligible.
Costco/Walmart/Target items can be under-counted or days remaining wrong depending policy.

### Severity

**High**

### Fix

Use item/merchant policy consistently:

```text
eligibleUntil = purchaseDate + merchantPolicy.priceProtectionDays
```

Do not reuse return window as price protection unless policy says so.

---

## 23. Deals/coupons/price drops are simulated but UI presents them as real

### Where

`PriceProtectionTracker`

Examples:

- `getCurrentPrice()` simulates drops
- `findBetterPrice()` returns `"Competitor Store"`
- `findCoupons()` returns `"SAVE10"`
- alerts include `isSimulated = true`

### Problem

UI does not visibly label simulation.

### Impact

Users may believe:

- a refund is available
- a coupon is valid
- a competitor deal exists
- a claim URL applies

when it is generated placeholder data.

### Severity

**Critical UX / trust**

### Fix

Until real providers exist:

- hide this feature behind debug flag, or
- label every card as “demo/simulated,” or
- remove fake offers from production builds.

Do not show “available refunds” from simulated prices.

---

## 24. Removing item from price tracking is not persistent and does not affect alerts

### Where

`PriceProtectionViewModel`

`excludedTrackingKeys` is only an in-memory `MutableStateFlow`.

`priceDrops` stream still comes directly from `priceTracker.monitorPriceDrops()` and is not filtered by excluded keys.

### Impact

- removed items return after screen recreation
- price drop alerts still show removed items
- no user preference is stored

### Severity

**High**

### Fix

Persist:

```text
PriceProtectionTrackingPreference(
    receiptId,
    itemFingerprint,
    enabled
)
```

Filter all price-drop and protected-item views through it.

---

## 25. Price protection has no persisted item identity

### Where

`PriceProtectedItem`

Tracking key:

```text
receiptId:itemName:purchaseDate
```

### Problem

No stable item id/fingerprint exists.

If OCR item name changes, duplicate item names exist, or receipt is rescanned, tracking breaks.

### Severity

**High**

### Fix

Persist receipt items:

```text
ReceiptLineItem(
    receiptId,
    itemIndex,
    normalizedName,
    amount,
    currency,
    quantity,
    fingerprint
)
```

Use `receiptId + itemIndex` or stable fingerprint.

---

## 26. Price protection is not currency-safe

### Where

`PriceProtectionTracker`
`PriceProtectionScreen`

### Problems

- `PriceProtectedItem` has no currency
- `PriceDropAlert` has no currency
- UI uses `NumberFormat.getCurrencyInstance(Locale.getDefault())`
- receipt currency is ignored

### Impact

A USD purchase may display as EUR/local currency.

Savings totals raw-sum mixed currencies.

### Severity

**High / Critical with multi-currency**

### Fix

Carry currency through:

```text
purchasePrice + currency
currentPrice + currency
priceDrop + currency
```

Convert only through the app money layer.

---

## 27. Credit-card benefits are generic and not tied to actual cards/payment method

### Where

`PriceProtectionTracker.getCreditCardBenefits()`

### Problem

Benefits like “Dining Rewards Card” or “Purchase Protection” are generated from merchant/amount only.

They do not check:

- actual payment method
- user cards
- card benefit rules
- card currency/country
- eligibility period
- exclusions

### Impact

The app can recommend benefits the user does not have.

### Severity

**Medium / High UX**

### Fix

Only show generic education unless actual card metadata exists.

Label:

> “Possible benefit — verify with your card issuer.”

---

# Bill negotiation findings

## 28. Negotiation engine uses hardcoded market rates

### Where

`SmartBillNegotiationEngine`

### Problem

Market rates are hardcoded for providers and prices.

### Impact

Rates go stale and are region-specific.

### Severity

**High**

### Fix

Add market-rate source metadata:

```text
source
region
currency
validFrom
lastUpdated
confidence
```

If no verified data, label as estimate.

---

## 29. Negotiation math ignores billing frequency

### Where

`SmartBillNegotiationEngine.createNegotiationOpportunity()`

It uses:

```kotlin
currentPrice = subscription.amount
potentialYearlySavings = monthlySavings * 12
```

### Problem

`subscription.amount` may be weekly, monthly, quarterly, annual, etc.

### Impact

Annual subscriptions look like monthly bills.

Example:

- annual €120 service
- competitive monthly €8
- engine treats €120/month vs €8/month

### Severity

**Critical**

### Fix

Normalize to monthly equivalent:

```kotlin
currentMonthlyPrice = RecurrenceCalculator.toMonthlyAmount(subscription.amount, subscription.frequency)
```

Use original billing cycle separately for display.

---

## 30. Negotiation engine is currency-hardcoded to euros

### Where

`SmartBillNegotiationEngine`
`BillNegotiationScreen`

Examples:

- scripts use `"€"`
- market rates are untyped
- UI uses locale currency, not subscription currency

### Impact

USD/GBP subscriptions display wrong savings and scripts.

### Severity

**High**

### Fix

Add currency to `MarketRate` and opportunity model.

Format with app money formatter.

---

## 31. Service-type detection can misclassify mobile/internet

### Where

`SmartBillNegotiationEngine.detectServiceType()`

### Problem

The internet condition checks provider names like `COSMOTE`, `VODAFONE`, `WIND` before the mobile condition.

A mobile provider string can match internet first.

### Impact

Wrong market rate, competitors, script, and savings.

### Severity

**Medium / High**

### Fix

Use ordered, specific patterns:

1. explicit mobile plan keywords
2. explicit internet plan keywords
3. provider-only fallback with ambiguity flag

If ambiguous, do not create a high-confidence recommendation.

---

## 32. Customer value is based on price history count

### Where

`SmartBillNegotiationEngine.calculateCustomerValue()`

### Problem

It estimates months active using:

```kotlin
priceHistoryCount.coerceAtLeast(1)
```

Price-history rows are not months.

### Impact

A 3-year customer with no price history looks new.
A new customer with many price changes can look loyal.

### Severity

**Medium / High**

### Fix

Use:

- subscription created date
- first detected payment date
- number of paid occurrences
- tenure in months

---

# Strong parts

## 1. Warranty extraction has both local and cloud foundations

`WarrantyTextExtractor` provides a deterministic local baseline.

Cloud extraction can enrich return policy and warranty details.

Good direction.

## 2. Warranty uniqueness by receipt prevents obvious duplicate inserts

The unique `receiptId` index prevents repeated auto-extraction from creating unlimited warranty rows.

This is useful, though too restrictive for multi-item receipts.

## 3. Low-confidence warranty drafts are flagged

The concept of `needsReview` is good.

It just needs a real edit/confirm workflow.

## 4. Expiry reconciliation exists

`reconcileExpiredItems()` updates expired warranties and return windows.

Good foundation.

## 5. Price protection model already carries `isSimulated`

The domain model knows when data is simulated.

The UI just needs to display or suppress it.

---

# Recommended fix order

## PR 1 — Receipt asset coordinator

Create:

```kotlin
ReceiptAssetCoordinator
```

Responsibilities:

- on receipt scanned
- on receipt linked to expense
- on receipt unlinked
- on receipt deleted
- on warranty confirmed/rejected
- on return marked returned
- on receipt item changed

This prevents receipt/warranty/return/expense drift.

## PR 2 — Fix warranty review lifecycle

- confirm sets `status = ACTIVE`
- confirm requires editable validated fields
- reject handles linked return window
- use `TimeProvider`, not `System.currentTimeMillis()`

## PR 3 — Stop fake placeholder receipts

Make `Warranty.receiptId` nullable.

Manual warranties should be first-class records, not fake scanned receipts.

## PR 4 — Fix price protection date/currency/simulation

- use purchase date, not scan date
- carry currency
- label or hide simulated results
- persist tracking preferences

## PR 5 — Add reminder state

Add `WarrantyReminderState` and send each stage once unless snoozed/dismissed.

## PR 6 — Normalize confidence and extraction metadata

One confidence scale: `0.0..1.0`.

Persist extraction source and confidence for both local and cloud paths.

## PR 7 — Item-level warranty/return model

Move from one warranty/return per receipt to item-level records.

## PR 8 — Frequency/currency-safe negotiation

Normalize subscriptions to monthly equivalent and carry currency through opportunities/scripts.

---

# Regression tests to add

1. Linking a receipt to an expense updates existing warranty `expenseId`.
2. Linking a receipt to an expense updates existing return-window `expenseId`.
3. Clearing a receipt match clears or updates warranty/return links by policy.
4. Confirming a pending-review warranty sets status to `ACTIVE`.
5. Rejecting a warranty draft removes/dismisses its auto-generated return window.
6. Manual warranty creation does not create fake scanned receipt.
7. Deleting a receipt does not delete warranty unless explicitly requested.
8. One receipt can track two warrantied items.
9. Protected value uses effective/base money, not raw `Expense.amount`.
10. Warranty expiring today is displayed and classified correctly.
11. 7-day reminder text uses actual days remaining.
12. Same warranty reminder stage is not sent twice.
13. Claimed warranty sets `claimedAt`.
14. Marking return as returned can link/create refund expense.
15. Price protection uses parsed purchase date, not scan date.
16. Old receipt scanned today is not shown as newly price-protected.
17. Simulated price drops/coupons are hidden or labeled in production.
18. Removed price-tracking item stays removed after app restart.
19. USD receipt does not display price protection values as EUR/local currency.
20. Annual subscription negotiation uses monthly equivalent.
21. Mobile provider is not misclassified as internet.
22. Negotiation scripts use subscription currency, not hardcoded `€`.

---

# Top three fixes

If you only fix three things first:

1. **Propagate receipt match/unmatch changes to warranties and return windows.**
2. **Fix warranty review confirm/reject lifecycle.**
3. **Disable or clearly label simulated price protection/deals/coupons in production.**

Those remove the biggest trust and data-integrity issues.

---

# Sources reviewed

- `WarrantyTrackerRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt

- `Warranty.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/Warranty.kt

- `ReturnWindow.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ReturnWindow.kt

- `WarrantyDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyDao.kt

- `ReturnWindowDao.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/dao/ReturnWindowDao.kt

- `ScannedReceipt.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt

- `ReceiptRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt

- `AutoCreateWarrantyFromReceiptUseCase.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt

- `WarrantyTextExtractor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt

- `CloudWarrantyExtractionService.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt

- `WarrantyExtractionModels.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt

- `WarrantyExpirationWorker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/service/warranty/WarrantyExpirationWorker.kt

- `WarrantyTrackerViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerViewModel.kt

- `WarrantyTrackerScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerScreen.kt

- `PriceProtectionTracker.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/price/PriceProtectionTracker.kt

- `PriceProtectionViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/price/PriceProtectionViewModel.kt

- `PriceProtectionScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/price/PriceProtectionScreen.kt

- `SmartBillNegotiationEngine.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/negotiation/SmartBillNegotiationEngine.kt

- `BillNegotiationViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/negotiation/BillNegotiationViewModel.kt

- `BillNegotiationScreen.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/negotiation/BillNegotiationScreen.kt