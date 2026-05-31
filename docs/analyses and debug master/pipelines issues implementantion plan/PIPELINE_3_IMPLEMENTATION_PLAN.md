# Pipeline 3 — Receipt Capture / OCR / Email: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 3 — Receipt Capture / OCR / Email  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 3 — Receipt Capture / OCR / Email
Verdict: RED
Summary:
- 3 old issues FIXED, 2 PARTIAL, 7 TODO ONLY
- 3 issues FIXED by universal (NEW-P3-001/002/003 via U-PR1)
- 14 pipeline-local issues remain (7 P1 TODO, 2 P1 PARTIAL, 5 P2/P3 NEW)
- Core lifecycle coordinator exists but legacy paths still reachable
- Key gaps: matching not persisted, IGNORE conflict unchecked, EUR fallback, parse status wrong
- Bank statement dedupe weaker than legacy; batch path skips reviews
- Privacy leak in production logs (merchant/category)
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_3_CONSOLIDATED_ISSUES.md`, `new debugging session/pipeline3_static_debug_report_b6abe0a (1).md`

**Source files:** `ReceiptLifecycleCoordinator.kt`, `ReceiptLinkService.kt`, `ReceiptSideEffectDispatcher.kt`, `BankStatementLifecycleProcessor.kt`, `ReceiptRepository.kt`, `ScannedReceiptDao.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 3 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | Fixes NEW-P3-001/002/003 | No | ✅ Fixed |
| U-PR2 (TOCTOU) | No direct impact | No | N/A |
| U-PR3 (Money/Currency) | No direct impact | No | N/A |
| U-PR4 (Barrier) | Pipeline 3 uses RestoreMaintenanceMode directly | Optional — migrate to DatabaseWriteBarrier | ⚠ Adapter optional |
| U-PR5 (Privacy/RawStorageMode) | Receipt OCR storage mode needs per-source-type policy | Yes — adapter for rawOcrStorageMode | ⏳ Blocked |
| U-PR6 (Worker Guard) | No direct impact | No | N/A |
| U-PR7 (TimeProvider) | No direct impact | No | N/A |
| U-PR8 (Side Effects) | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P3-P0-01 | ✅ FIXED | None | None |
| P3-P1-01 | ✅ FIXED | None | None |
| P3-P1-02 | ✅ FIXED | U-PR4 compatible | Optional: migrate to DatabaseWriteBarrier |
| P3-P1-03 | ✅ FIXED | None | Match result persisted (NoMatch writes event) |
| P3-P1-04 | ⚠ PARTIAL | None | Remove deprecated legacy path or make it ERROR |
| P3-P1-05 | ⚠ PARTIAL | None | Add barrier to remaining direct DAO paths |
| P3-P1-06 | ✅ FIXED | None | ReceiptInsertResolver handles conflict |
| P3-P1-07 | ✅ FIXED | None | ProcessReceiptUseCase passes homeCurrency (P3-PR1 landed) |
| P3-P1-08 | ✅ FIXED | None | PARSE_FAILED correctly set in ReceiptRepository |
| P3-P1-09 | 📝 TODO | None | Enable review creation in batch path |
| P3-P1-10 | 📝 TODO | None | Strengthen bank statement dedupe |
| NEW-P3-001 | ✅ FIXED | U-PR1 | None |
| NEW-P3-002 | ✅ FIXED | U-PR1 | None |
| NEW-P3-003 | ✅ FIXED | U-PR1 | None |
| NEW-P3-004 | 🔴 OPEN | None | Remove double attachReceipt call |
| NEW-P3-005 | 🔴 OPEN | None | Move duplicate check inside transaction |
| NEW-P3-006 | 🔴 OPEN | None | Remove PII from production logs |
| NEW-P3-007 | 🔴 OPEN | None | Guard event write with existence check |
| NEW-P3-008 | 🔴 OPEN | None | Add timeout to homeCurrency() call |

---

## 5. New Issues / Regressions

No regressions from universal fixes. The `ReceiptLinkService` uses `RestoreMaintenanceMode` directly rather than `DatabaseWriteBarrier` — functionally equivalent but inconsistent with the universal pattern.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| P3-P1-03 | P1 | Matching result not persisted / no MATCH_NOT_FOUND event | Matching | P3-PR1 |
| P3-P1-06 | P1 | ScannedReceiptDao.insert() IGNORE conflict unchecked | Insert safety | P3-PR1 |
| P3-P1-08 | P1 | Parse failures classified as OCR_COMPLETED | Status correctness | P3-PR1 |
| P3-P1-07 | P1 | Currency fallback hardcoded EUR | Currency | P3-PR1 |
| P3-P1-04 | P1 | Legacy receipt+expense path not atomic | Lifecycle | P3-PR2 |
| P3-P1-05 | P1 | Direct repository methods bypass lifecycle | Lifecycle | P3-PR2 |
| P3-P1-09 | P1 | Batch import skips pending reviews | Batch | P3-PR2 |
| P3-P1-10 | P1 | Bank statement dedupe weaker than legacy | Dedupe | P3-PR2 |
| NEW-P3-004 | P2 | Double attachReceipt in bank statement | Bug | P3-PR3 |
| NEW-P3-005 | P2 | Race in post-OCR duplicate path | Atomicity | P3-PR3 |
| NEW-P3-006 | P2 | Privacy leak — PII in production logs | Privacy | P3-PR3 |
| NEW-P3-007 | P2 | Event written for non-existent receipt | Correctness | P3-PR3 |
| NEW-P3-008 | P3 | homeCurrency() thread starvation risk | Performance | P3-PR3 |



---

## 7. PR Organization

### P3-PR1 — Core Pipeline Correctness

```
PR name: fix(p3): persist match results, check insert conflicts, fix parse status, use home currency
Goal: Fix fundamental correctness gaps in receipt processing
Issues fixed: P3-P1-03, P3-P1-06, P3-P1-07, P3-P1-08
Universal dependencies: None
Files likely touched:
  - ReceiptSideEffectDispatcher.kt (matching persistence)
  - ScannedReceiptDao.kt / ReceiptRepository.kt (insert conflict)
  - ReceiptOcrService.kt / ReceiptLifecycleCoordinator.kt (parse status)
  - ReceiptParser.kt (currency fallback)
Implementation steps:
  1. P3-P1-03: In ReceiptSideEffectDispatcher, write MATCH_NOT_FOUND event when findBestMatch returns null; persist matchStatus=NO_MATCH on receipt
  2. P3-P1-06: After scannedReceiptDao.insert(), check return value; if 0 (IGNORE conflict), look up existing by fingerprint and return existing ID or throw
  3. P3-P1-07: Replace hardcoded "EUR" in ReceiptParser.parse() with injected homeCurrency from UserCurrencyProvider
  4. P3-P1-08: Wrap parse call in try/catch; on parse exception, set status=PARSE_FAILED (not OCR_COMPLETED); write PARSE_FAILED event
Tests:
  - no_match_writes_MATCH_NOT_FOUND_event
  - insert_conflict_returns_existing_receipt_id
  - parse_failure_sets_PARSE_FAILED_status
  - currency_uses_home_currency_not_EUR
Risks: Low — correctness fixes with clear boundaries
Acceptance criteria:
  - Every receipt has a terminal match status (MATCHED/SUGGESTED/NO_MATCH)
  - Insert conflict never leaves receiptId=0 in downstream code
  - Parse exceptions produce PARSE_FAILED status and event
  - No hardcoded EUR in receipt parsing
```

### P3-PR2 — Lifecycle Hardening

```
PR name: fix(p3): remove legacy paths, enable batch reviews, strengthen bank dedupe
Goal: Close lifecycle bypass gaps and strengthen batch/bank paths
Issues fixed: P3-P1-04, P3-P1-05, P3-P1-09, P3-P1-10
Universal dependencies: None
Files likely touched:
  - ReceiptRepository.kt (legacy path removal)
  - ReceiptLifecycleCoordinator.kt (batch review creation)
  - BankStatementLifecycleProcessor.kt (dedupe strengthening)
Implementation steps:
  1. P3-P1-04: Elevate deprecated ReceiptRepository.createExpenseFromReceipt() to DeprecationLevel.HIDDEN or remove entirely; ensure no production caller references it
  2. P3-P1-05: Add writeBarrier.checkWritesAllowed() to remaining direct DAO mutation paths (delete, match update); annotate internal-only methods
  3. P3-P1-09: Change batch receipt import to pass autoCreateReview=true; coordinator creates pending review for low-confidence parses
  4. P3-P1-10: Strengthen bank statement dedupe: check both pending reviews AND existing expenses by amount+date+merchant window (same as notification pipeline dedupe)
Tests:
  - legacy_createExpenseFromReceipt_not_callable_from_production
  - direct_delete_respects_write_barrier
  - batch_import_creates_pending_review_for_low_confidence
  - bank_statement_dedupe_catches_existing_expenses
Risks: Medium — removing legacy paths may break callers; verify no production references
Acceptance criteria:
  - No production code path reaches deprecated receipt+expense creation
  - All receipt mutations respect write barrier
  - Batch imports produce reviews for uncertain parses
  - Bank statement dedupe matches legacy strength
```

### P3-PR3 — Bug Fixes & Cleanup

```
PR name: fix(p3): double attach, duplicate race, PII leak, event guard, timeout
Goal: Fix remaining P2/P3 issues
Issues fixed: NEW-P3-004, NEW-P3-005, NEW-P3-006, NEW-P3-007, NEW-P3-008
Universal dependencies: None
Files likely touched:
  - BankStatementLifecycleProcessor.kt
  - ReceiptLifecycleCoordinator.kt
Implementation steps:
  1. NEW-P3-004: Remove duplicate attachReceipt call in BankStatementLifecycleProcessor success path; verify single attach
  2. NEW-P3-005: Move post-OCR duplicate check inside withTransaction block to prevent race
  3. NEW-P3-006: Replace Timber.d/i calls that log merchant/category with hashed or redacted versions; use SafeEventMetadata pattern
  4. NEW-P3-007: In deleteReceipt, check receipt exists before writing RECEIPT_DELETED event; return early if not found
  5. NEW-P3-008: Wrap homeCurrency() call with withTimeoutOrNull(3000) { ... } ?: DEFAULT_CURRENCY
Tests:
  - bank_statement_attaches_receipt_exactly_once
  - concurrent_ocr_duplicate_check_is_atomic
  - production_logs_do_not_contain_merchant_names
  - delete_nonexistent_receipt_writes_no_event
Risks: Low — targeted fixes
Acceptance criteria:
  - No double receipt attachment
  - Duplicate check atomic with insert
  - Zero PII in production log output
  - No phantom events for non-existent entities
```

---

## 8. Detailed Implementation Plan

### P3-PR1 Step-by-Step

1. **Open** `ReceiptSideEffectDispatcher.kt`
   - Find `findBestMatch()` call and its result handling
   - Add: when result is null/NoMatch, write `ReceiptEvent(type=MATCH_NOT_FOUND, receiptId=receipt.id)`
   - Update receipt: `scannedReceiptDao.updateMatchStatus(receiptId, MatchStatus.NO_MATCH)`

2. **Open** `ReceiptRepository.kt` or wherever `scannedReceiptDao.insert()` is called
   - After insert, check: `if (insertedId == 0L)` → look up existing by content fingerprint
   - Return existing ID or throw `ReceiptInsertConflictException`

3. **Open** `ReceiptParser.kt`
   - Find `"EUR"` default/fallback
   - Inject `UserCurrencyProvider`; replace with `userCurrencyProvider.getHomeCurrency()`

4. **Open** `ReceiptLifecycleCoordinator.kt` or `ReceiptOcrService.kt`
   - Find where OCR result is processed and status set
   - Wrap parse in try/catch; on exception: set `processingStatus = PARSE_FAILED`, write event

### P3-PR2 Step-by-Step

1. **Open** `ReceiptRepository.kt`
   - Find `createExpenseFromReceipt()` — change to `@Deprecated(level = HIDDEN)` or delete
   - Grep for callers; ensure none in production code

2. **Find** direct DAO mutation methods without barrier
   - Add `writeBarrier.checkWritesAllowed()` to each

3. **Open** batch import path
   - Find `autoCreateReview = false` parameter
   - Change to `autoCreateReview = true` for low-confidence results

4. **Open** `BankStatementLifecycleProcessor.kt`
   - Find dedupe logic; add expense table check alongside pending review check

### P3-PR3 Step-by-Step

1. **Open** `BankStatementLifecycleProcessor.kt` — find double `attachReceipt` call; remove one
2. **Open** `ReceiptLifecycleCoordinator.kt` — find post-OCR duplicate check; move inside `withTransaction`
3. **Grep** for `Timber.*merchant|Timber.*category` in receipt files; replace with hashed/redacted
4. **In** `deleteReceipt()` — add `val exists = scannedReceiptDao.getById(id); if (exists == null) return`
5. **Wrap** `homeCurrency()` with timeout

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 3 Adapter/Follow-up |
|---|---|
| U-PR5 (Privacy/RawStorageMode) | Required: Wire `RawContentPolicy.ocrStorageMode` for receipt OCR text; ensure bank statement raw text uses correct mode |
| U-PR4 (Barrier) | Optional: Migrate ReceiptLinkService from RestoreMaintenanceMode to DatabaseWriteBarrier for consistency |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 3 targeted tests
./gradlew :app:testDebugUnitTest --tests "*ReceiptLifecycle*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptLink*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptSideEffect*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BankStatement*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptOcr*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptMatch*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P3-PR1: Match results persisted; insert conflicts handled; parse status correct; home currency used
- [ ] P3-PR2: Legacy paths removed; all mutations barrier-guarded; batch creates reviews; bank dedupe strengthened
- [ ] P3-PR3: No double attach; duplicate race fixed; PII removed from logs; phantom events prevented
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] U-PR5 adapter landed (after U-PR5 merges): OCR/bank storage modes correct
- [ ] Pipeline 3 status upgraded to GREEN in master tracker
