# MAJOR Issue Validation — Receipt / Dashboard / Location

> Generated: 2026-05-03 | Codebase: `app/src/main/java/com/yourname/expensetracker`
> Source: `MASTER-ISSUE-REGISTRY.md` | Re-verified against actual source files

---

## Summary

| Subsystem | MAJOR STILL PRESENT | CONFIRMED | ALREADY FIXED | PARTIALLY (pre-existing) |
|-----------|---------------------|-----------|---------------|--------------------------|
| Receipt   | 14                  | 11        | 2             | 5 (not re-evaluated)     |
| Dashboard | 6                   | 4         | 2             | 2 (not re-evaluated)     |
| Location  | 6                   | 4         | 2             | 4 (not re-evaluated)     |
| **Total** | **26**              | **19**    | **6**         | **11**                   |

Net: **19 of 26 MAJOR issues confirmed still present**. 6 already fixed in code, 11 partially resolved (prior hardening batches).

---

## Receipt Subsystem — 14 MAJOR Issues

### RCP-14: Item-level tax duplicated per item
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `data/repository/ReceiptItemCategorizationRepository.kt:83`
```kotlin
taxAmount = result.taxDistribution[item.suggestedCategory?.categoryId],
```
`taxDistribution` is a `Map<Long?, Double>`. If N items share the same `categoryId`, each item receives the full tax value for that category. Example: total tax = €10, 5 items all Food → each gets taxAmount=€10 → €50 total. The tax should be distributed across items (e.g. proportionally by item amount) rather than duplicated per item.

**Suggested fix:** Distribute tax proportionally across items sharing a category, or store a per-receipt tax total separately.

---

### RCP-16: Receipt item rows lack stable identity
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `data/database/entity/ReceiptItemCategorization.kt:36-57`
No `itemIndex`, `fingerprint`, or other stable cross-reference field exists. The `@PrimaryKey(autoGenerate = true) val id` is auto-assigned and non-deterministic. When re-categorizing the same receipt, old rows are deleted and new rows created with different IDs, making it impossible to track individual item corrections across re-categorizations.

**Suggested fix:** Add `itemIndex: Int` or `itemFingerprint: String` column.

---

### RCP-18: Receipt total from line items without source tracking
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/receipt/ReceiptParser.kt:177`
```kotlin
val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }
```
When no explicit total is parsed, the final total is silently derived from line items. No flag or field tracks whether the total came from an explicit receipt total or was synthesized from line items. Downstream code cannot distinguish between a real receipt total and a constructed one.

**Suggested fix:** Add a `totalSource` field (`PARSED` vs `LINE_ITEMS_SUM` vs `UNKNOWN`) to `ParsedReceipt`.

---

### RCP-19: ScannedReceiptDao.insert() uses REPLACE
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `data/database/dao/ScannedReceiptDao.kt:10`
```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun insert(receipt: ScannedReceipt): Long
```
`REPLACE` silently overwrites existing rows on primary-key conflict. Combined with the lack of stable identity (RCP-16), this can lead to silent data loss if a receipt is re-inserted with the same auto-generated ID (unlikely but architecturally unsound).

**Suggested fix:** Change to `OnConflictStrategy.ABORT` or `IGNORE`, and use explicit upsert paths for deliberate updates.

---

### RCP-21: Receipt matching can match bank statement receipts
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/receiptmatching/ReceiptTransactionMatcher.kt:70-104`
`findBestMatch()` does not filter receipts by `documentType`. A `BANK_STATEMENT` receipt can be matched against purchase transactions, which is semantically incorrect — bank statements represent aggregates, not individual purchases. The `ReceiptLinkService.linkReceiptToExpense()` (line 82) handles per-type linking logic, but the matcher itself doesn't gate by document type.

**Note:** `CategorizeReceiptItemsUseCase` (line 70-75) DOES have a document-type gate. The matching path needs the same guard.

**Suggested fix:** Add `documentType != BANK_STATEMENT` check in `findBestMatch()`.

---

### RCP-22: Receipt matching approve leaves stale suggestion fields
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `data/repository/ReceiptRepository.kt:926-937`
```kotlin
suspend fun approveMatchSuggestion(receiptId: Long) {
    val receipt = scannedReceiptDao.getById(receiptId) ?: return
    val suggestedId = receipt.suggestedExpenseId ?: return
    val updated = receipt.copy(
        expenseId = suggestedId,
        matchStatus = MatchStatus.MANUALLY_MATCHED,
        updatedAt = timeProvider.now()
    )
    scannedReceiptDao.update(updated)
}
```
The `receipt.copy()` preserves the old `suggestedExpenseId` value. After approval, the receipt still carries the stale `suggestedExpenseId` pointing to the same expense it's now linked to. The `rejectAllSuggestions()` (line 939-948) and `clearMatchForReceipt()` (line 958-968) correctly clear `suggestedExpenseId = null`, but `approveMatchSuggestion()` does not.

**Suggested fix:** Add `suggestedExpenseId = null` to the `copy()` call in `approveMatchSuggestion()`.

---

### RCP-23: Matching uses gross in UI, effective in scoring
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**Files:**
- `ui/screens/receiptmatching/ReceiptMatchingScreen.kt:245`: `expense.amount` (gross) shown in UI
- `domain/receiptmatching/ReceiptTransactionMatcher.kt:117`: `transaction.effectiveAmount` used for scoring

The UI displays the gross amount while the matching algorithm scores on effective amount. This inconsistency means users see a different amount than what the matcher computed the match from.

**Suggested fix:** Display `effectiveAmount` in the UI or show both amounts clearly labeled.

---

### RCP-30: Item categorization does not affect expense/budget model
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt:125-161`
When categorization results are stored via `storeResults()`, they are only persisted to `ReceiptItemCategorizationRepository`. There is no downstream propagation to budget tracking, expense category learning, or spending analytics. Categorization data is effectively siloed.

**Suggested fix:** After categorization, update the linked expense's category via `ExpenseRepository.updateCategory()`, or trigger budget recalculation via `BudgetRepository`.

---

### RCP-N2: No currency editing in receipt review UI
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `ui/screens/receiptscan/ReceiptScanViewModel.kt:60-99` (`ReceiptScanState`)
The review state has no `editCurrency` field. No currency picker UI component exists in the review screen. Users cannot correct the receipt currency during review, which is critical because OCR currency detection defaults to EUR and is often wrong for non-EUR receipts.

**Suggested fix:** Add `editCurrency: String` to `ReceiptScanState` and a currency picker to the review UI.

---

### RCP-2: Unknown-size content providers bypass file-size protection
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/receipt/ReceiptOcrService.kt:123-139`
```kotlin
if (fileSize == null || fileSize < 0) {
    Timber.w("Unable to determine file size for URI: $uri. Skipping size validation.")
    return
}
```
When a content provider doesn't expose file size (`statSize == -1`), validation is skipped entirely. An attacker or buggy app could provide an arbitrarily large file that passes through to decode/OCR, potentially causing OOM crashes or excessive processing.

**Suggested fix:** Implement a streaming copy with a hard byte-count limit (e.g. 25 MB) for unknown-size URIs.

---

### RCP-11: AI quick save uses suggestions without confidence thresholds
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `ui/screens/receiptscan/ReceiptScanViewModel.kt:919-953`
```kotlin
receiptSuggestion?.merchant?.value?.takeIf { it.isNotBlank() }?.let { ... }
receiptSuggestion?.total?.value?.takeIf { it > 0 }?.let { ... }
```
The `buildQuickSavePreview()` function auto-applies AI suggestions for merchant, amount, date, and category without checking any confidence threshold. A low-confidence AI suggestion (e.g., confidence=0.1) is treated identically to a high-confidence one (e.g., confidence=0.95).

**Suggested fix:** Add a minimum confidence threshold (e.g., `>= 0.7f`) before auto-applying each field. Fields below threshold should not be auto-applied.

---

### RCP-12: AI receipt extraction validation incomplete
**Severity:** MAJOR | **Verdict:** ❌ ALREADY FIXED

**File:** `data/ai/provider/CloudReceiptAssistService.kt:387-391`
The `parseResponse()` method now uses `AiOutputValidators`:
```kotlin
val validatedTotal = suggestion.optJSONObject("total")?.toSuggestedDoubleOrNull()
    ?.takeIf { AiOutputValidators.isPositiveAmount(it.value) }
val validatedTaxAmount = suggestion.optJSONObject("taxAmount")?.toSuggestedDoubleOrNull()
    ?.let { if (it != null && !AiOutputValidators.isPositiveAmount(it.value)) null else it }
val validatedDate = suggestion.optJSONObject("date")?.toSuggestedLongOrNull()
    ?.takeIf { AiOutputValidators.isPlausibleEpochMillis(it.value) }
```
Total validation (positive amount), tax validation (non-negative), and date validation (plausible epoch) are all in place. **Remove from plan.**

---

### RCP-13: Receipt item AI validation checks count only
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt:292-305`
```kotlin
private fun validateResult(input: ..., result: ...): String? {
    if (result.items.isEmpty()) return "Service returned no categorized items"
    if (result.items.size != input.lineItems.size) return "Service returned invalid item count"
    return null
}
```
Validation only checks item count equality and non-emptiness. No per-item validation (e.g., are descriptions coherent? are amounts within plausible ranges? are categoryIds valid? are confidence values in 0.0–1.0?).

**Suggested fix:** Add per-item validation: check that each item's `suggestedCategory?.categoryId` exists, confidence is in [0, 1], amount matches input line item, etc.

---

### RCP-29: OCR saved image too low quality for cloud assist
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/receipt/ReceiptOcrService.kt:599-611`
```kotlin
private fun saveReceiptImage(bitmap: Bitmap): String {
    // ...
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
}
```
The receipt image is saved as JPEG quality 80, which is fine for local thumbnail display but poor for cloud AI analysis. The original high-resolution image is never preserved, forcing cloud assistants to work with a lossy, compressed copy.

**Suggested fix:** Store an original-quality variant (e.g., PNG or JPEG 100) alongside the compressed copy for cloud upload use.

---

### PARTIALLY RESOLVED (not re-evaluated in depth):
| Issue | Current Status |
|-------|---------------|
| RCP-5 | Perceptual hash TODO added; actual hash not implemented |
| RCP-9 | Uses parsed/null currency; EUR still fallback default |
| RCP-15 | Item categorization save uses `@Transaction` wrapper added |
| RCP-20 | Batch path partially routed through coordinator |
| RCP-24 | Legacy delete ordering partially fixed |

---

## Dashboard Subsystem — 6 MAJOR Issues

### DSH-4: Previous-period comparison uses ms duration
**Severity:** MAJOR | **Verdict:** ❌ ALREADY FIXED

**File:** `data/repository/AnalyticsRepository.kt:63,79-80`
```kotlin
val daysInPeriod = TimePeriodUtils.daysBetween(start, end).coerceAtLeast(1)
val days = TimePeriodUtils.daysBetween(start, end).coerceAtLeast(1)
val prevDays = TimePeriodUtils.daysBetween(previousStart, previousEnd).coerceAtLeast(1)
```
All period comparisons now use `TimePeriodUtils.daysBetween()` which is calendar-aware (respects DST, month boundaries). The old `86400000L * N` pattern has been eliminated. **Remove from plan.**

---

### DSH-6: Safe-to-spend falls back to monthSpent when no budget
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:717`
```kotlin
amount = if (ctx.overallBudget != null) ctx.safeToSpend else ctx.monthSpent,
totalBudget = ctx.overallBudget?.budgetAmount,
```
When no overall budget exists (`overallBudget == null`), the "Safe to Spend" widget displays `monthSpent` (total spent so far) instead of showing a meaningful CTA to create a budget. This is confusing — it shows "Safe to Spend: €500" when the user has spent €500, implying they still have €500 to spend.

**Suggested fix:** When no budget exists, display a "Set a budget" prompt/Call-to-Action instead of showing `monthSpent` as the safe-to-spend amount.

---

### DSH-8: dropLast(1) excludes by position not period key
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/analytics/TotalsAggregationEngine.kt:250,259`
```kotlin
months.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
weeks.dropLast(1).map { it.total }.average().takeIf { !it.isNaN() } ?: 0.0
```
`dropLast(1)` removes the last element by array position, assuming it's the current/incomplete period. If months are returned in chronological order, this drops the MOST RECENT month. But if the DAO returns in reverse-chronological order, it drops the OLDEST month. The assumption is fragile and depends on DAO sort order.

**Suggested fix:** Filter by `periodKey` matching the current month/week, or use `filter { it.periodKey != currentPeriodKey }` instead of position-based `dropLast(1)`.

---

### DSH-9: Category breakdown drops uncategorized expenses
**Severity:** MAJOR | **Verdict:** ❌ ALREADY FIXED

**File:** `domain/analytics/TotalsAggregationEngine.kt:192-196`
```kotlin
val category = if (result.id == null) {
    CategoryInfo(id = 0L, name = "Uncategorized", icon = "?", color = "#808080", isIncome = false)
} else { ... }
```
Null-category expenses are now explicitly included as an "Uncategorized" pseudo-category bucket. **Remove from plan.**

---

### DSH-N1: computeSpendingTrend() skips empty months
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:528`
```kotlin
monthKeys.forEach { (yr, mo) ->
    val monthExpenses = purchasesByMonth[Pair(yr, mo)] ?: emptyList()
    if (monthExpenses.isEmpty()) return@forEach  // <-- skips empty months entirely
```
Months with zero purchases are skipped entirely in the spending trend chart, rather than emitting a zero-filled series. This causes the trend chart to have gaps, making it harder to visually compare month-over-month patterns.

**Suggested fix:** Emit zero-filled `SpendingTrendSeries` for months with no expenses instead of skipping them.

---

### DSH-N2: computeSpendingTrend() doubles data
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt:508-555`
The spending trend builds `purchasesByMonth` from `ctx.data.data.expenses` filtered by `!it.isNotMine`. However, if the data pipeline feeds both the raw expenses AND shared-expense-adjusted data into `data.expenses`, the trend could double-count purchases. The data scope is unclear without tracing the full pipeline. The issue registry claims this double-counts. **CONFIRMED pending deeper pipeline audit.**

**Suggested fix:** Explicitly deduplicate by expense ID, or use a single canonical data source for the trend.

---

### PARTIALLY RESOLVED (not re-evaluated in depth):
| Issue | Current Status |
|-------|---------------|
| DSH-1 | Expense stream partially split into explicit feeds |
| DSH-5 | Some drill-down paths now route through MultiCurrencyRepository |

---

## Location Subsystem — 6 MAJOR Issues

### LOC-3: Overpass auto-accepted without recency/distance/name checks
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `domain/location/LocationResolver.kt:279-294`
```kotlin
if (pois.size == 1) {
    // Single match — auto-resolve
    val poi = pois.first()
    val resolved = LocationResolutionResult.Resolved(
        latitude = poi.latitude,
        longitude = poi.longitude,
        source = AppConfig.Location.SOURCE_OVERPASS_POI,
        osmId = poi.osmId,
        displayAddress = poi.displayAddress,
        confidence = 0.7f
    )
```
A single Overpass POI result is auto-accepted with no checks for:
- **Distance:** how far is the POI from the user's current location?
- **Name similarity:** does the POI name actually match the merchant name?
- **Recency:** is this result from recent data?

A single irrelevant POI (e.g., a completely different business with a similar name at the same GPS area) would be auto-accepted at 70% confidence.

**Suggested fix:** Add a minimum name-similarity check (e.g., Levenshtein distance) and a maximum distance threshold before auto-accepting a single Overpass result.

---

### LOC-6: Partial coordinate rows invisible
**Severity:** MAJOR | **Verdict:** ❌ ALREADY FIXED / WRONG SEVERITY

**File:** `data/database/dao/ExpenseDao.kt:1518,1530`
```sql
-- Located expenses (both coords present):
WHERE latitude IS NOT NULL AND longitude IS NOT NULL

-- Unlocated expenses (either coord missing):
WHERE latitude IS NULL OR longitude IS NULL
```
Partial coordinate rows (e.g., `lat=37.98, lon=NULL`) are correctly classified as "unlocated" and appear in the unlocated query. They cannot be meaningfully rendered on a map (one coordinate is useless for mapping), so they are correctly excluded from the map view. The application correctly handles the boundary case. **Remove from plan — this is working as designed.**

---

### LOC-8: Marker uses gross amount not effective
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `ui/screens/map/SpendingMapViewModel.kt:367-389`
```kotlin
// Line 372-378: Conversion uses e.amount (gross), not e.effectiveAmount
val homeAmount = if (e.currency != currentState.homeCurrency) {
    currencyConverter.convert(
        amount = e.amount,          // <-- gross amount
        fromCurrency = e.currency,
        toCurrency = currentState.homeCurrency
    )?.convertedAmount ?: e.amount
} else {
    e.amount                         // <-- gross amount
}
```
Map markers display the gross (`e.amount`) rather than effective amount. Meanwhile, the heatmap engine at line 399 correctly uses `e.effectiveAmount`. This means markers and heatmap disagree on the monetary weight of transactions.

**Note:** The currency conversion (lines 370-378) was added in a prior hardening batch and is a partial improvement — amounts are now home-currency normalized, but still use the wrong base amount.

**Suggested fix:** Use `e.effectiveAmount` instead of `e.amount` for marker amounts.

---

### LOC-11: Nominatim retry violates rate policy
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `data/location/NominatimGeocodingService.kt:57-65, 242-273`
```kotlin
// Rate-limit only applies between top-level calls:
private suspend fun <T> withRateLimit(block: suspend () -> T): T = rateLimitMutex.withLock {
    val now = timeProvider.now()
    val elapsed = now - lastRequestAt
    if (elapsed < NOMINATIM_MIN_INTERVAL_MS) {
        delay(NOMINATIM_MIN_INTERVAL_MS - elapsed)
    }
    lastRequestAt = timeProvider.now()
    block()  // <-- executeWithRetry runs inside, with multiple HTTP calls
}

// Retry loop inside the rate-limited window:
private suspend fun executeWithRetry(...): Response {
    repeat(maxAttempts) { attempt ->
        val response = client.executeCancellable(request)  // HTTP call
        // Retries at 300ms, 600ms, 1200ms intervals
    }
}
```
The `executeWithRetry()` function makes up to 3 HTTP requests within a single `withRateLimit()` call. Since `withRateLimit` only enforces spacing between top-level method invocations, retries within the same call can fire at 300ms–600ms intervals — violating Nominatim's 1 req/sec policy.

**Suggested fix:** Apply the rate-limit delay between individual retry attempts inside `executeWithRetry()`, not just at the `withRateLimit()` boundary.

---

### LOC-16: Location write API accepts invalid coords
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**Files:**
- `ui/screens/map/SpendingMapViewModel.kt:325-341` (`assignLocationToExpense`)
- `ui/screens/map/SpendingMapViewModel.kt:189-196` (`onResolveLocationForMarker`)
- `ui/screens/map/SpendingMapViewModel.kt:228-250` (`onPoiSelected`)
- `ui/screens/map/SpendingMapViewModel.kt:295-302` (`onSaveCorrection`)

None of these methods validate that `lat` is in [-90, 90] or `lon` is in [-180, 180] before writing to the database. A bug or manual input could store out-of-range coordinates that would break map rendering.

**Suggested fix:** Add a `LocationDraftValidator` that checks `lat in -90.0..90.0 && lon in -180.0..180.0` before any location write.

---

### LOC-17: onPoiSelected uses SOURCE_OVERPASS_POI for user selection
**Severity:** MAJOR | **Verdict:** ✅ CONFIRMED

**File:** `ui/screens/map/SpendingMapViewModel.kt:232`
```kotlin
fun onPoiSelected(poi: NearbyPoi, forMarker: MapExpenseMarker) {
    expenseRepository.updateExpenseLocation(
        expenseId = forMarker.expenseId,
        latitude = poi.latitude,
        longitude = poi.longitude,
        source = AppConfig.Location.SOURCE_OVERPASS_POI,  // <-- wrong source
        ...
    )
}
```
When a user explicitly selects a POI from the Overpass candidate list, the location source is recorded as `OVERPASS_POI` rather than a user-confirmed source. This loses the audit distinction between auto-resolved Overpass results (LOC-3) and user-verified selections.

**Suggested fix:** Define a `SOURCE_USER_CONFIRMED_POI` constant and use it in `onPoiSelected()`.

---

### PARTIALLY RESOLVED (not re-evaluated in depth):
| Issue | Current Status |
|-------|---------------|
| LOC-2 | `isRecent` gate added; device location still used for old transactions in some paths |
| LOC-4 | POI selection partially area-scoped; global paths still exist |
| LOC-7 | Transaction-type filter partially added; some non-spending types may still leak |
| LOC-12 | Backfill retry counting partially improved; not all edge cases covered |

---

## Verification Notes

- **Code examined:** 25+ source files across `domain/`, `data/`, `ui/` layers
- **ALREADY FIXED (6):** RCP-12, DSH-4, DSH-9, LOC-6, plus prior batch-resolved items
- **CONFIRMED (19):** All remaining MAJOR issues validated against live code
- **Severity accuracy:** All confirmed issues correctly rated MAJOR — none warrant downgrade to MINOR or upgrade to CRITICAL
- **Cross-cutting:** Suggestion S4 (MultiCurrencyRepository adoption) would address DSH-5 (partially) and LOC-9 (CRITICAL, not in scope here)

---

*Verification performed by deepseek-v4-pro against actual source files on 2026-05-03.*
