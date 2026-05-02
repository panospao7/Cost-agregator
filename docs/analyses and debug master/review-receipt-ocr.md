# Receipt / OCR / Itemization / Receipt Matching — Cross-Check Review

**Source analysis**: `docs/analyses and debug master/receipt-ocr-itemization-matching-analysis.md`  
**Review date**: May 2, 2026  
**Branch**: `master` (current workspace)  
**Review scope**: 30 issues from the analysis document cross-checked against the current codebase at `app/src/main/java/com/yourname/expensetracker/`

---

## Executive Summary

The codebase has made substantial progress since the original analysis. The most impactful change is the introduction of a `ReceiptLifecycleCoordinator` with supporting services (`ReceiptLinkService`, `ReceiptDuplicateDetector`, `ReceiptAssetStore`, `ReceiptSideEffectDispatcher`, `BankStatementLifecycleProcessor`, `ReceiptInputValidator`). New entity types (`ReceiptSourceType`, `ReceiptDocumentType`, `ReceiptProcessingStatus`, `ReceiptEvent`, `ReceiptExpenseLink`) provide the semantic foundations requested by the analysis.

**Major wins**:
- `ReceiptLinkService` with `ReceiptExpenseLink` join table resolves the statement multi-transaction problem (Issue 5)
- `ReceiptDuplicateDetector` with content hash/text/semantic fingerprinting addresses content dedup (Issue 20)
- `ReceiptAssetStore` provides UUID-based collision-proof filenames (Issue 1 partial)
- Raw OCR purge infrastructure exists (Issue 25)
- Debug currency now uses parsed currency, not hardcoded EUR (Issue 26)
- `ReceiptLifecycleCoordinator.deleteReceipt()` uses safe delete ordering: DB first, asset after commit (Issue 24 partial)

**However**: the migration is incomplete. Many old code paths (`ReceiptRepository` legacy methods, `ReceiptMatchingViewModel`, `ReceiptOcrService.saveReceiptImage()`) still run the old problematic logic. The new coordinator is wired into `ReceiptScanViewModel.processImageUri()` but the batch path, matching path, and bank statement view-model path still use the legacy `ReceiptRepository` methods directly.

---

## Per-Issue Status

| # | Issue | Status | Evidence |
|---|-------|--------|----------|
| 1 | Receipt image filenames can collide | **PARTIALLY RESOLVED** | `ReceiptAssetStore.persistReceiptAsset()` uses `{timestamp}_{UUID}.jpg`. But `ReceiptOcrService.saveReceiptImage()` (line 591) still uses `receipt_${System.currentTimeMillis()}.jpg` — the old collision-prone pattern. The lifecycle coordinator delegates file persistence to `ReceiptRepository`/OCR, so the old save path is still exercised. |
| 2 | Unknown-size content providers bypass file-size protection | **STILL PRESENT** | `ReceiptOcrService.validateFileSize()` (lines 130-133) still skips validation when `statSize <= 0`. `ReceiptInputValidator` validates size but also skips when `statSize` is null or negative. No streaming copy limit exists. |
| 3 | Failed parsing creates fake `0.01 EUR` pending reviews | **PARTIALLY RESOLVED** | `FALLBACK_SUGGESTED_AMOUNT = 0.01` still exists (ReceiptRepository line 91). The comment documents it as "UI-PLACEHOLDER ONLY" and notes that `approveReview()` blocks approval without user override. But a fake monetary value is still invented. The analysis called for `suggestedAmount: Double?` (nullable) with `extractionState = PARSE_FAILED` — not implemented. |
| 4 | Manual scan triggers warranty extraction before user confirmation | **PARTIALLY RESOLVED** | `ReceiptSideEffectDispatcher.dispatchAfterSave()` (line 72-73) gates warranty on `status != OCR_FAILED && status != PARSE_FAILED`. But the old `ReceiptRepository.processReceipt()` (line 195) still runs `warrantyUseCase.execute()` unconditionally for all receipts, including manual scans with `autoCreateReview = false`. The coordinator calls `processReceipt()` which triggers this unconditionally. |
| 5 | Statement screenshot uses one ScannedReceipt for many transactions | **RESOLVED** | `BankStatementLifecycleProcessor` creates statements with `documentType = BANK_STATEMENT`. `ReceiptLinkService.linkReceiptToExpense()` allows multiple links for BANK_STATEMENT receipts (line 86-87). The `ReceiptExpenseLink` join table with `unique(receiptId, expenseId)` handles the many-to-many relationship. Legacy `ScannedReceipt.expenseId` is NOT updated for bank statements. |
| 6 | Receipt item categorizations not linked when receipt becomes expense | **STILL PRESENT** | `ReceiptItemCategorizationDao.linkToExpense()` EXISTS (line 52 of DAO) but NOBODY calls it. `ReceiptLinkService.linkReceiptToExpense()` manages `ReceiptExpenseLink` and legacy `ScannedReceipt.expenseId` but does NOT propagate to item categorizations. `ReceiptRepository.createExpenseFromReceipt()` calls `receiptLinkService.linkReceiptToExpense()` which also doesn't link items. |
| 7 | Receipt matching ignores currency | **STILL PRESENT** | `ReceiptTransactionMatcher.calculateMatchScore()` (lines 91-127) uses `transaction.effectiveAmount` for amount comparison without any currency check. A 100 USD receipt can match a 100 EUR expense. |
| 8 | Multiple receipts can link to one expense | **RESOLVED** | `ReceiptExpenseLink` entity (line 30) has `Index(value = ["receiptId", "expenseId"], unique = true)`. `ReceiptLinkService.linkReceiptToExpense()` checks for existing non-BANK_STATEMENT links and blocks relinking unless `allowRelink = true`. |
| 9 | Receipt currency defaults to EUR too aggressively | **PARTIALLY RESOLVED** | `ReceiptParser.detectCurrency()` now returns `null` when no currency detected (line 707). `ReceiptLifecycleCoordinator` catastrophic failure path uses `currencySettingsRepository.homeCurrency()` with EUR as last-resort fallback. But `ReceiptRepository.saveManualReceiptRecord()` (line 281) and parse-failure branch (line 231) still hardcode `currency = "EUR"`. |
| 10 | Receipt review cannot edit currency | **STILL PRESENT** | `ReceiptScanState` has no `editCurrency` field. The UI has no currency picker/editor. `saveExpenseInternal()` uses `parsedReceipt?.currency ?: defaultCurrency` — the user cannot correct it. |
| 11 | AI quick save uses suggestions without confidence thresholds | **STILL PRESENT** | `ReceiptScanViewModel.buildQuickSavePreview()` (lines 918-953) accepts AI suggestions when draft fields are blank, with no confidence threshold check on `SuggestedValue.confidence`. |
| 12 | AI receipt extraction output validation is incomplete | **STILL PRESENT** | `CloudReceiptAssistService` uses `optFiniteDoubleStrictOrNull` which checks `isFinite()` but does NOT validate: confidence ∈ [0,1], total > 0, tax ≥ 0, tax ≤ total, date plausible, merchant non-blank after normalization, amount max bound. No shared `ReceiptAssistSuggestionValidator` exists. |
| 13 | Receipt item AI validation checks only item count | **STILL PRESENT** | `CategorizeReceiptItemsUseCase.validateResult()` (lines 292-305) checks only: `items.isEmpty()` and `items.size != input.lineItems.size`. No validation of: item identity, amounts matching input, category IDs existing, confidence bounds, tax sums, or hallucinated values. |
| 14 | Item-level tax can be duplicated per item | **STILL PRESENT** | `ReceiptItemCategorizationRepository.saveCategorizationResult()` line 67: `taxAmount = result.taxDistribution[item.suggestedCategory?.categoryId]` — copies category-level tax total to every item in that category. Three food items each get the full €3.00 tax = €9.00 total instead of €3.00. |
| 15 | Receipt item categorization save is not transactional | **PARTIALLY RESOLVED** | Items still inserted one-by-one in a loop (no `@Transaction` wrapper). However `CategorizeReceiptItemsUseCase` now checks `savedCount > 0` before updating receipt status to READY (line 148-154) and `force` mode clears old rows first (line 62). Partial failure handling improved but not atomic. |
| 16 | Receipt item rows lack stable item identity | **STILL PRESENT** | `ReceiptItemCategorization` entity has no `itemIndex`, `itemFingerprint`, `originalLineItemId`, `quantity`, or `unitPrice` fields. Cannot safely map AI output back to parsed input line items. |
| 17 | Receipt line item parser defines patterns it does not use | **STILL PRESENT** | `ReceiptParser.extractLineItems()` (lines 615-662) uses `lineItemPatterns[0]` and `lineItemPatterns[1]` only. Patterns [2] (`item @ unitPrice totalPrice`) and [3] (`qty x item @ unitPrice totalPrice`) are defined but never invoked. |
| 18 | Receipt total from line items without safeguards | **STILL PRESENT** | `ReceiptParser.parse()` line 151: `val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }`. No total-source tracking (TOTAL_KEY vs LINE_ITEM_SUM vs AI vs USER). Line-item-sum totals are treated with equal confidence. |
| 19 | ScannedReceiptDao.insert() uses REPLACE | **STILL PRESENT** | `ScannedReceiptDao.insert()` line 10: `@Insert(onConflict = OnConflictStrategy.REPLACE)`. No separate `insertNew()` / `updateParsedFields()` methods exist. |
| 20 | Duplicate receipt scanning not detected by content | **PARTIALLY RESOLVED** | `ReceiptLifecycleCoordinator.processReceiptInput()` computes file hash and runs `ReceiptDuplicateDetector` with EXACT_HASH → TEXT_FINGERPRINT → SEMANTIC → EXTERNAL_ID strategies. But `ReceiptRepository.processBatch()` (line 467) still only uses `uris.distinctBy { it.toString() }` — no content-based dedup in batch path. |
| 21 | Receipt matching can match statement receipts | **STILL PRESENT** | `ScannedReceiptDao.getUnmatchedReceipts()` (line 46): `WHERE matchStatus = 'UNMATCHED'` — does NOT filter out `documentType = 'BANK_STATEMENT'`. Statement receipts default `matchStatus` to `UNMATCHED`. `ReceiptMatchingViewModel.runAutoMatching()` will attempt to match statement container records. |
| 22 | Receipt matching approve leaves stale suggestion fields | **STILL PRESENT** | `ReceiptRepository.approveMatchSuggestion()` (lines 906-915) sets `expenseId = suggestedId` and `matchStatus = MANUALLY_MATCHED` but does NOT clear `suggestedExpenseId` or update `matchConfidence = 1.0`. |
| 23 | Receipt matching uses gross amount in UI and effective amount in scoring | **STILL PRESENT** | `ReceiptTransactionMatcher` uses `transaction.effectiveAmount` for scoring (line 97). `ReceiptMatchingScreen` displays `expense.amount` (gross, line 246). For shared/not-mine expenses the scoring basis and display disagree. |
| 24 | Receipt image deletion before DB deletion | **PARTIALLY RESOLVED** | `ReceiptLifecycleCoordinator.deleteReceipt()` (lines 490-536): DB operations in `@Transaction` first → delete links and row → POST-COMMIT asset deletion. Correct ordering. But `ReceiptRepository.deleteReceipt()` (lines 446-449) still deletes image first, then DB row — the old unsafe path remains. |
| 25 | Raw OCR text retained indefinitely | **RESOLVED** | `ScannedReceipt` has `rawOcrTextPurgedAt` field. `ScannedReceiptDao` has `purgeRawOcrText()`, `getUnpurgedScannedReceiptsOlderThan()`, and `updateRawOcrTextPurged()` which sets `rawOcrText = ''` on purge. Retention infrastructure built. |
| 26 | Receipt debug data hardcodes EUR | **RESOLVED** | `ReceiptScanViewModel` line 310: `currency = parsed.currency` — correctly uses the parsed currency instead of hardcoded `"EUR"`. |
| 27 | PDF processing silently limits to first 5 pages | **STILL PRESENT** | `extractPdfText()` line 251: `val pageLimit = minOf(document.numberOfPages, 5)`. `processPdfWithOcr()` line 382: `val pageLimit = 5`. No user-visible warning. No configurable limit. No per-source-type policy. |
| 28 | OCR retry is inconsistent | **STILL PRESENT** | `processImage()` line 156: `runWithRetry(maxAttempts = 3) { recognizeText(inputImage) }` — has retry. `processPdfWithOcr()` line 408: `recognizeText(inputImage)` — no retry wrapper. PDF pages get one shot. |
| 29 | OCR saved image may be too low quality for cloud/image assist | **STILL PRESENT** | `ReceiptOcrService.loadAndCorrectBitmap()` downsamples to 384-1024px. `saveReceiptImage()` compresses to JPEG 80. Cloud/image assist analyzes this low-res copy. No original-quality variant is stored. |
| 30 | Item categorization does not affect expense/category/budget model | **STILL PRESENT** | Item categorizations are stored separately. `createExpenseFromReceipt()` assigns one `categoryId` per expense. No item-level budget allocations exist. Analysis's Option A (informational only) is implicitly the current behavior, but it is not explicitly labeled as such. |

---

## Summary Counts

| Status | Count | Issue Numbers |
|--------|-------|---------------|
| **RESOLVED** | 4 | 5, 8, 25, 26 |
| **PARTIALLY RESOLVED** | 7 | 1, 3, 4, 9, 15, 20, 24 |
| **STILL PRESENT** | 19 | 2, 6, 7, 10, 11, 12, 13, 14, 16, 17, 18, 19, 21, 22, 23, 27, 28, 29, 30 |

---

## New Issues Discovered During Review

### [NEW-1] [MAJOR] ReceiptMatchingViewModel uses legacy paths, bypassing ReceiptLinkService

**Where**: `ReceiptMatchingViewModel.runAutoMatching()` (lines 84-99), `manualMatch()` (lines 150-155), `rerunForReceipt()` (lines 165-187)

**Problem**: The matching flow calls `receiptRepository.linkReceiptToExpense()` (the legacy method at ReceiptRepository lines 876-889) which directly sets `ScannedReceipt.expenseId` and `matchStatus` but does NOT create a `ReceiptExpenseLink` row. This means auto-matched and manually-matched receipts have no proper join-table link, breaking the new linking architecture.

**Fix**: Replace all calls to the legacy `ReceiptRepository.linkReceiptToExpense()` with `ReceiptLinkService.linkReceiptToExpense()`.

---

### [NEW-2] [MAJOR] No currency editing capability in receipt review UI

**Where**: `ReceiptScanState` (lines 60-99), `ReceiptScanViewModel.saveExpenseInternal()` (line 995)

**Problem**: `ReceiptScanState` has no `editCurrency` field. The UI has no currency picker. The `saveExpenseInternal()` method silently uses `parsedReceipt?.currency ?: defaultCurrency`. If the parser misdetects currency (Issue 9), the user has no way to correct it.

**Fix**: Add `editCurrency: String` to `ReceiptScanState`, expose a currency picker in the review UI, and pass it to the save request.

---

### [NEW-3] [MEDIUM] Batch processing does not use ReceiptLifecycleCoordinator

**Where**: `ReceiptRepository.processBatch()` (lines 466-516)

**Problem**: The batch path calls `processReceipt(uri, autoCreateReview = true)` directly, bypassing `ReceiptLifecycleCoordinator.processReceiptInput()`. This means batch receipts don't get: file hash computation, duplicate detection (content-based), lifecycle metadata (sourceType, documentType, processingStatus, fingerprints), lifecycle events, or post-save side effects via the dispatcher.

**Fix**: Route batch processing through `ReceiptLifecycleCoordinator.processReceiptInput()`.

---

### [NEW-4] [MEDIUM] `receipt_item_categorizations` insert also uses REPLACE strategy

**Where**: `ReceiptItemCategorizationDao.insert()` (line 13)

**Problem**: Uses `OnConflictStrategy.REPLACE` like `ScannedReceiptDao`. During a force re-analysis that clears old rows with `deleteByReceiptId()`, if a new insert somehow conflicts on a residual primary key from an incomplete delete, it would silently replace rather than fail.

**Fix**: Use `OnConflictStrategy.ABORT` or `IGNORE` and handle conflicts explicitly.

---

## VERDICT: FAIL

**19 of 30 analyzed issues remain fully unresolved.** While significant architectural improvements have been made (lifecycle coordinator, link service, duplicate detector, asset store, raw OCR purge), these new systems are not yet wired into all the code paths that need them. The legacy paths in `ReceiptRepository`, `ReceiptMatchingViewModel`, `ReceiptTransactionMatcher`, and `ReceiptOcrService` continue to operate with the pre-existing defects.

**Critical gaps that should be fixed in priority order**:
1. **Issue 6** — Link item categorizations to expenses when receipt is linked (data integrity)
2. **Issue 7** — Add currency awareness to receipt matching (financial correctness)
3. **NEW-1** — Wire `ReceiptMatchingViewModel` to use `ReceiptLinkService` (architectural consistency)
4. **Issue 14** — Fix tax duplication across items (financial correctness)
5. **Issue 3** — Remove fake `0.01 EUR` fallback and model incomplete states (financial correctness)
6. **Issue 10** + **NEW-2** — Add currency editing to receipt review UI (user correctness)
