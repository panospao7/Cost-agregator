# Warranty / Returns / Offers — Cross-Check Review

**Date:** 2026-05-02  
**Analysis reviewed:** `docs/analyses and debug master/warranty-returns-offers-analysis.md`  
**Codebase scanned:** `app/src/main/java/com/yourname/expensetracker/`  

---

## Executive verdict

**All 32 issues from the original analysis remain STILL PRESENT.**  
No issue has been RESOLVED or even PARTIALLY RESOLVED.

Some infrastructure work has been done (ReceiptLinkService, ReceiptAssetStore, new entity fields for PENDING_REVIEW/autoDetected/extractionConfidence/extractionSource), but **none of these changes address the core data-integrity, UX, or currency-safety problems** described in the original analysis. The recommended `ReceiptAssetCoordinator` was not created.

The codebase has *regressed in a minor way*: there are now two parallel receipt-linking paths (old `ReceiptRepository` methods vs. new `ReceiptLinkService`) that can diverge, and neither propagates to warranties/return windows.

---

## Per-issue status

| # | Issue | Status | Evidence |
|---|-------|--------|----------|
| 1 | Receipt-to-expense matching does not propagate to warranty/return `expenseId` | **STILL PRESENT** | `ReceiptLinkService.linkReceiptToExpense()` (L62–135) updates only `ScannedReceipt.expenseId`. No warranty/return-window update. `ReceiptLinkService.unlinkReceiptFromExpense()` (L152–208) also does not touch warranties/return windows. The old `ReceiptRepository.linkReceiptToExpense()` (L876–889) also only updates `ScannedReceipt`. No `ReceiptAssetCoordinator` exists. |
| 2 | Confirming low-confidence warranty does not set `status = ACTIVE` | **STILL PRESENT** | `WarrantyTrackerViewModel.confirmWarranty()` (L151–159) only sets `needsReview = false`. Does NOT set `status = ACTIVE`. The `createWarrantyForReview`/`promoteReviewDraft` path in the use case does set ACTIVE, but the ViewModel does not call it. |
| 3 | Rejecting auto-detected warranty leaves stale return window | **STILL PRESENT** | `WarrantyTrackerViewModel.rejectAutoDetectedWarranty()` (L163–167) calls `warrantyRepository.deleteWarranty(warranty)` only. No return-window deletion or dismissal. |
| 4 | Manual warranties create fake placeholder receipts | **STILL PRESENT** | `WarrantyTrackerViewModel.addManualWarranty()` (L119–147) calls `createManualPlaceholderReceipt()` which constructs a fake `ScannedReceipt` with `rawOcrText = "Manual warranty entry: …"` and hardcoded `currency = "EUR"`. |
| 5 | Deleting a receipt deletes warranties and return windows | **STILL PRESENT** | `Warranty.receiptId` FK: `onDelete = ForeignKey.CASCADE` (Warranty.kt L16). `ReturnWindow.receiptId` FK: `onDelete = ForeignKey.CASCADE` (ReturnWindow.kt L16). |
| 6 | One warranty per receipt too restrictive | **STILL PRESENT** | `Index(value = ["receiptId"], unique = true)` in Warranty.kt L26. |
| 7 | Return-window uniqueness inconsistent | **STILL PRESENT** | `ReturnWindow`: receiptId index NOT unique, expenseId index IS unique. DAO `getReturnWindowByReceiptId()` returns single `ReturnWindow?`. Schema allows multiple per receipt, DAO assumes one. |
| 8 | Protected value raw-sums gross expense amount | **STILL PRESENT** | `WarrantyDao.getTotalProtectedValue()` (L62–71): `SUM(COALESCE(e.amount, 0))` — uses `amount`, not `effectiveAmount`. No currency normalization/normalized money. |
| 9 | Warranty end-date semantics expire too early | **STILL PRESENT** | `calculateWarrantyEndDate()` stores start-of-day. Active query: `warrantyEndDate > currentTime`. Last covered day is excluded. Both local (`WarrantyTextExtractor.calculateWarrantyEndDate()` L261–271) and cloud (`toCalendarMonthEndDate()` L301–309) paths have same issue. |
| 10 | Worker reminder text inaccurate | **STILL PRESENT** | `WarrantyExpirationWorker` (L71–107) uses hardcoded strings `warranty_expires_in_7_days_format` and `warranty_expires_in_30_days_format` regardless of actual days remaining within the range. |
| 11 | Warranty reminders repeat every day | **STILL PRESENT** | No `WarrantyReminderState` entity exists. Worker uses only in-memory `notifiedThisRun` set per invocation (L68). No persistence of sent/dismissed/snoozed state. |
| 12 | `markWarrantyAsClaimed()` does not set `claimedAt` | **STILL PRESENT** | `WarrantyTrackerRepository.markWarrantyAsClaimed()` (L80–85) calls `updateWarrantyStatus()` which updates `status` and `updatedAt` only. DAO SQL (L49–54): `UPDATE warranties SET status = :status, updatedAt = :updatedAt`. No `claimedAt`. |
| 13 | Marked returned not linked to refund expense | **STILL PRESENT** | `ReturnWindowDao.markAsReturned()` (L46–53) sets `status`, `returnedAt`, `refundAmount`, `updatedAt`. No `linkedRefundExpenseId`. `ReturnWindow` entity has no such field. |
| 14 | Return refund amount has no currency | **STILL PRESENT** | `ReturnWindow.refundAmount` is `Double?` (L46). No `refundCurrency` field. |
| 15 | Cloud extraction bypasses stronger AI abstraction | **STILL PRESENT** | `WarrantyTrackerRepository` directly injects `CloudWarrantyExtractionService` (L32). `extractWarrantyResult()` (L195–225) only runs when route=`CLOUD`; on-device fallback is not available in this path. (Positive: `CloudWarrantyExtractionService` now uses `SecureKeyStorage`.) |
| 16 | Cloud extraction ignores confidence thresholds | **STILL PRESENT** | `toWarrantyEntityOrNull()` (L227–245) checks only `warrantyMonths != null`. Does NOT use `confidence` for thresholding, `needsReview`, `autoDetected`, or `extractionConfidence`. |
| 17 | Confidence scales inconsistent (0..100 vs 0..1) | **STILL PRESENT** | `WarrantyTextExtractor.calculateConfidence()` returns 0..100 (L533–571). `WarrantyExtractionResult.confidence` is validated 0..1 (L27–29). `Warranty.extractionConfidence` is raw `Double`. Local path persists 0..100; cloud path leaves default 0.0. UI uses `.toInt()` which would break for 0..1 values. |
| 18 | Low-confidence drafts create fake default values | **STILL PRESENT** | `AutoCreateWarrantyFromReceiptUseCase.createReviewDraftWarranty()` (L225–277) falls back to `"Unknown Product"`, `"Unknown Merchant"`, `durationMonths = 12`. |
| 19 | Review UI cannot edit extracted fields | **STILL PRESENT** | `WarrantyTrackerScreen` (L539–564): review actions are Confirm/Reject only. No edit form. |
| 20 | Manual warranty path not transactional | **STILL PRESENT** | `addManualWarranty()` calls `createManualPlaceholderReceipt()` then `addWarranty()` in separate DB calls (L129, L145). No transaction wrapping. |
| 21 | Price protection uses scan date instead of purchase date | **STILL PRESENT** | `PriceProtectionTracker.getPriceProtectedItems()`: `since = TimePeriodUtils.getLastNCalendarDaysRange(now, 30).first` based on `receipt.createdAt`. `parsePriceProtectedItems()` sets `purchaseDate = receipt.createdAt` (L50). `isEligibleForPriceProtection()` uses `receipt.createdAt` (L78). |
| 22 | Price-protection window ignores merchant-specific return window | **STILL PRESENT** | `isEligibleForPriceProtection()` (L75–84): hardcoded 30 days. Merchant-specific `getReturnWindow()` exists (L160–171) but is used only for `returnWindowDays` display, not eligibility. |
| 23 | Deals/coupons/drops are simulated but shown as real | **STILL PRESENT** | `PriceProtectionTracker`: `getCurrentPrice()` returns simulated 92%/95% values (L205–215). `findBetterPrice()` returns `"Competitor Store"` at 85% price (L255–267). `findCoupons()` returns simulated `"SAVE10"` (L270–288). All have `isSimulated = true` set on the data model, but **the UI (`PriceProtectionScreen`) never reads or displays `isSimulated`**. |
| 24 | Removing item from tracking is not persistent | **STILL PRESENT** | `PriceProtectionViewModel._excludedTrackingKeys` is `MutableStateFlow<Set<String>>` (L50). Not persisted. `priceDrops` stream is not filtered by excluded keys. |
| 25 | No persisted item identity for price protection | **STILL PRESENT** | `trackingKey()` (L122–124): `"${item.receiptId}:${item.itemName.lowercase()}:${item.purchaseDate}"`. No stable fingerprint. |
| 26 | Price protection not currency-safe | **STILL PRESENT** | `PriceProtectedItem` has no currency field. `PriceDropAlert` has no currency. UI uses `CurrencyFormatter.format(price, homeCurrency)` which converts from home currency only. Purchase price has no stored currency context. |
| 27 | Credit-card benefits generic, not tied to actual cards | **STILL PRESENT** | `getCreditCardBenefits()` (L291–335): benefits generated from merchant/amount patterns only. No check of actual user cards, payment methods, or benefit rules. |
| 28 | Negotiation engine uses hardcoded market rates | **STILL PRESENT** | `SmartBillNegotiationEngine.marketRates` (L21–121): hardcoded `Map<String, MarketRate>`. No source/region/currency metadata. No staleness check. |
| 29 | Negotiation math ignores billing frequency | **STILL PRESENT** | `createNegotiationOpportunity()` (L184–233): `currentPrice = subscription.amount` without frequency normalization. `potentialYearlySavings = potentialSavings * 12` assumes monthly billing. |
| 30 | Negotiation engine currency-hardcoded to euros | **STILL PRESENT** | Scripts use `"€"` hardcoded (L324, L328, L336, L339, L342). `MarketRate` has no currency field. UI uses `CurrencyFormatter` from home currency, but scripts are static. |
| 31 | Service-type detection can misclassify mobile/internet | **STILL PRESENT** | `detectServiceType()` (L166–178): `"VODAFONE"` and `"COSMOTE"` appear in both the INTERNET and MOBILE `containsAny` lists. INTERNET check runs first (L169), so a provider like Vodafone always matches INTERNET. |
| 32 | Customer value based on price history count, not tenure | **STILL PRESENT** | `calculateCustomerValue()` (L265–276): `monthsActive = priceHistoryCount.coerceAtLeast(1)`. Price-history rows ≠ months of tenure. |

---

## Additional findings (not in original analysis)

### NEW-1: ReceiptLinkService.unlink doesn't clean up warranty/return links
**Severity: MAJOR**  
**Where:** `ReceiptLinkService.unlinkReceiptFromExpense()` (L152–208)  

When a receipt is unlinked from an expense, `ScannedReceipt.expenseId` is cleared but warranties and return windows are not updated. If warranties/return windows were linked to the same expense (either manually or via propagation), the unlink creates orphaned references.

**Fix:** Extend `unlinkReceiptFromExpense` to also nullify `Warranty.expenseId` and `ReturnWindow.expenseId` for the unlinked receipt.

---

### NEW-2: Dual receipt-linking paths create split-brain risk
**Severity: MAJOR**  
**Where:** `ReceiptRepository.linkReceiptToExpense()` (L876–889) vs `ReceiptLinkService.linkReceiptToExpense()` (L62–135)  

`ReceiptRepository` still has legacy `linkReceiptToExpense(receiptId, expenseId, confidence: Double)` that directly updates `ScannedReceipt` without going through `ReceiptLinkService`. Similarly, `approveMatchSuggestion()` (L906–916) and `clearMatchForReceipt()` (L936–945) bypass `ReceiptLinkService`. If both paths are called, one may overwrite the other's state. The legacy methods are not marked `@Deprecated` and have no migration warnings.

**Fix:** Deprecate/remove `ReceiptRepository` linking methods, route all linking through `ReceiptLinkService`.

---

### NEW-3: Manual placeholder receipt has wrong `documentType`
**Severity: MINOR**  
**Where:** `WarrantyTrackerRepository.createManualPlaceholderReceipt()` (L331–348)  

The fake `ScannedReceipt` defaults to `documentType = "UNKNOWN"` (entity default). Downstream gating checks look for `"MANUAL_PLACEHOLDER"` (e.g., in `upsertReturnWindowForReceipt` L154 and `AutoCreateWarrantyFromReceiptUseCase.execute` L62). If something else were to process this receipt, it would not be recognized as a manual placeholder.

**Fix:** Set `documentType = "MANUAL_PLACEHOLDER"` on the placeholder receipt.

---

### NEW-4: `createWarrantyForReview` exists but is unused by ViewModel
**Severity: MINOR**  
**Where:** `AutoCreateWarrantyFromReceiptUseCase.createWarrantyForReview()` (L125–141) vs `WarrantyTrackerViewModel.confirmWarranty()` (L151–159)  

The use case has a `createWarrantyForReview` method that properly promotes a PENDING_REVIEW draft to ACTIVE via `promoteReviewDraft()` (which sets `status = ACTIVE`, `needsReview = false`). But `confirmWarranty()` in the ViewModel does not call it — it directly copies the warranty entity. The proper fix path exists in code, just isn't wired.

---

## Infrastructure changes observed (positive, but insufficient)

Since the analysis was written, the codebase has added:

1. **`ReceiptLinkService`** — A centralized receipt-expense link coordinator with atomic transactions and event logging. This is a step toward the recommended `ReceiptAssetCoordinator`, but it only handles `ReceiptExpenseLink` rows and does not propagate to warranties, return windows, or price protection.

2. **`ReceiptAssetStore`** — Centralized file operations for receipt images. Addresses asset file management but is not a lifecycle coordinator.

3. **New Warranty fields** — `autoDetected`, `extractionConfidence`, `extractionSource`, `needsReview`, and `PENDING_REVIEW` status exist in the entity. The local extraction path populates these correctly. The cloud path does not.

4. **Confidence thresholds in use case** — `AutoCreateWarrantyFromReceiptUseCase` uses 70%/40% thresholds and properly creates review drafts for medium-confidence extractions.

5. **`SecureKeyStorage` in cloud service** — `CloudWarrantyExtractionService` now retrieves API keys from encrypted storage instead of `BuildConfig` (improves security).

6. **`TimeProvider` used consistently** — No raw `System.currentTimeMillis()` calls in new code paths.

**However, none of these address the 32 core issues.** The infrastructure is partially built but not yet serving its coordinating purpose.

---

## Recommended fix order (unchanged)

The original fix order from the analysis remains valid:

1. **PR 1 — Receipt asset coordinator**: Create `ReceiptAssetCoordinator` that propagates receipt match/unmatch to warranties, return windows, and price protection.
2. **PR 2 — Fix warranty review lifecycle**: `confirmWarranty` must set `status = ACTIVE`; `rejectAutoDetectedWarranty` must handle linked return windows.
3. **PR 3 — Stop fake placeholder receipts**: Make `Warranty.receiptId` nullable.
4. **PR 4 — Fix price protection date/currency/simulation**: Use purchase date, carry currency, label/hide simulated results.
5. **PR 5 — Add reminder state**: Persist `WarrantyReminderState`.
6. **PR 6 — Normalize confidence**: One scale 0.0..1.0 throughout.
7. **PR 7 — Item-level warranty/return model**: Support multi-item receipts.
8. **PR 8 — Frequency/currency-safe negotiation**: Normalize billing frequency; carry currency.

---

## Test gaps (unchanged)

All 22 regression tests listed in the original analysis remain unimplemented.

---

## Summary

```
Total issues from analysis: 32
  RESOLVED:              0
  PARTIALLY RESOLVED:    0
  STILL PRESENT:        32
New issues found:        4
  NEW-1: Unlink doesn't clean warranty/return links
  NEW-2: Dual receipt-linking paths
  NEW-3: Manual placeholder wrong documentType
  NEW-4: createWarrantyForReview exists but unused by ViewModel
```
