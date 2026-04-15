# REVIEW-B3-Batches6B6C.md

## VERDICT: ✅ PASS

## ✅ Correctly Implemented

### Batch 6B (Bank Statement Parser Correctness)

**Batch 1 - Generic Statement Amount/Date Correctness:**
- ✅ Extended `DateColumnInfo` with `TransactionDateOrder` enum (`FIRST`, `SECOND`, `UNKNOWN`)
- ✅ Reworked `detectDateColumns()` to use header keyword positions for transaction-date order inference
- ✅ Removed magnitude-based amount selection (`thenBy { kotlin.math.abs(it.parsed) }`)
- ✅ Position-aware amount selection prefers transaction-amount column over running-balance column
- ✅ Date order threaded into generic extraction path with proper fallback
- ✅ No public API changes - `parse(List<TextBlock>)` signature unchanged
- ✅ 3 new regression tests added

**Batch 2 - Internal Revolut Statement Row Correctness:**
- ✅ Revolut amount parsing now uses `AmountUtils.parseAmount()` instead of manual comma replacement
- ✅ Added `classifyRevolutStatementType()` private helper with proper keyword detection
- ✅ Transfer → TRANSFER (not DEPOSIT)
- ✅ ATM/Withdrawal → WITHDRAWAL (not PURCHASE)
- ✅ Refund/Top-up/Promo → DEPOSIT
- ✅ 12 new comprehensive Revolut tests (total now 17 tests)
- ✅ All tests passing

**Batch 3 - Statement Import Blast-Radius Audit:**
- ✅ `ReceiptRepository.processStatement()` consumes correct parser output fields
- ✅ All `ParsedTransaction` field mappings already handled by existing `ParserEnumMappers.kt`
- ✅ No schema expansion needed
- ✅ No UI behavior changes required
- ✅ Type-aware dedup will actually improve with corrected transaction types

### Batch 6C (SMS/Revolut Parser Thousands-Separated Amounts)

**Batch 1 - Shared Grouped-Amount Token + RevolutParser:**
- ✅ Added `GROUPED_AMOUNT_TOKEN` to `CommonPatterns.kt` (supports US/EU formats)
- ✅ Updated `RevolutParser` to use shared token in all 4 patterns
- ✅ Amount parsing still goes through `AmountUtils.parseAmount()` (already was)
- ✅ 8 new grouped-amount tests + 1 registry routing test
- ✅ All tests passing

**Batch 2 - SmsParser Grouped Amounts + Ambiguous Transfer Direction:**
- ✅ Updated `SmsParser` amount pattern to use `GROUPED_AMOUNT_TOKEN`
- ✅ Fixed `detectSmsDirection()` to return `null` for ambiguous transfers (not default to INCOMING)
- ✅ For deposits, `INCOMING` only when explicit evidence exists
- ✅ 9 new tests covering grouped amounts and direction ambiguity
- ✅ All tests passing

**Batch 3 - Notification-Ingestion Audit:**
- ✅ Grouped-amount fixes flow through `NotificationProcessingPipeline` and `ReviewQueueRepository` correctly
- ✅ Transfer direction ambiguity properly preserved via `null` handling
- ✅ `TransferDirectionBadge` has `UnknownBadge` branch for null direction
- ✅ Pre-existing gaps in direction persistence noted but out of scope

## ✅ No Issues Found

All plan items from both batches have been correctly implemented:
- No Room entity/schema changes
- No public API breaks
- No constraint violations
- No regressions detected
- Comprehensive regression tests added

## Documentation & Registry Updates Required

Per the plans, these bullets need `[RESOLVED BY B.3 — Batch 6B]` or `[RESOLVED BY B.3 — Batch 6C]`:
- B.3 line ~226: BankStatementParser amount selection breaks ties
- B.3 line ~227: Revolut statement parsing strips currency symbols
- B.3 line ~228: Revolut statement emits only DEPOSIT or PURCHASE
- B.3 line ~243: BankStatementParser header/date-column detection never used
- B.3 line ~249: SmsParser.detectSmsDirection() returns INCOMING on tie
- B.6 line ~397: SmsParser and RevolutParser amount regex only accepts single decimal separator

## Final Status

**B.3 Batch 6B and Batch 6C: READY FOR COMMIT**