# Pipeline 11 — Email Receipt Ingestion: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 11 — Email Receipt Ingestion  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 11 — Email Receipt Ingestion
Verdict: RED
Summary:
- 2 old issues FIXED, 5 PARTIAL, 1 TODO
- U-PR8 verified no double-dispatch (P11-P1-07 closed)
- 11 pipeline-local issues remain (6 P1, 3 P2, 1 P3, 1 TODO)
- Key gaps: fingerprint too coarse, wrong privacy mode for email, mutex bottleneck
- Parser canParse() methods overly broad — false-positive matching
- Amazon parser regex broken (double-escaped in raw strings)
- Blocked by U-PR5 for email privacy mode fix
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_11_CONSOLIDATED_ISSUES.md`

**Source files:** `EmailReceiptIngestionCoordinator.kt`, `AmazonReceiptParser.kt`, `UberReceiptParser.kt`, `EmailDateParser.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 11 | Adapter Needed | Status |
|---|---|---|---|
| U-PR1 (CancellationException) | P11-CURRENT-012 already addressed | No | ✅ Compatible |
| U-PR5 (Privacy) | **Critical** — email fields use wrong storage mode | Yes — adapter for emailStorageMode | ⏳ Blocked |
| U-PR8 (Side Effects) | Verified P11-P1-07 NOT A BUG — no double-dispatch | No | ✅ Confirmed |
| Others | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P11-P1-01 | ⚠ PARTIAL | None | Refine fingerprint granularity |
| P11-P1-02 | ⚠ PARTIAL | None | Handle non-duplicate failures |
| P11-P1-03 | ✅ FIXED | None | None |
| P11-P1-04 | ⚠ PARTIAL | U-PR5 | Use emailStorageMode (not rawOcrStorageMode) |
| P11-P1-05 | ⚠ PARTIAL | U-PR4 | Migrate to DatabaseWriteBarrier |
| P11-P1-06 | ⚠ PARTIAL | None | Handle messageId conflict properly |
| P11-P1-07 | ✅ FIXED | U-PR8 confirmed | None |
| P11-P1-08 | 📝 TODO | None | Add confidence-based review route |
| NEW-P11-001 | 🔴 OPEN | None | Replace mutex with semaphore |
| NEW-P11-002 | 🔴 OPEN | None | Narrow canParse() |
| NEW-P11-003 | 🔴 OPEN | None | Narrow canParse() |
| NEW-P11-004 | 🔴 OPEN | None | Cache formatters |
| NEW-P11-005 | 🔴 OPEN | None | Fix double-escaped regex |

---

## 5. New Issues / Regressions

No regressions from universal fixes. U-PR8 confirmed that the suspected double-dispatch in email receipt side effects does NOT exist in current code.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| P11-P1-01 | P1 | Fingerprint too coarse | Dedupe | P11-PR1 |
| P11-P1-02 | P1 | Non-duplicate failures ignored | Error handling | P11-PR1 |
| P11-P1-06 | P1 | messageId conflict unresolved | Insert safety | P11-PR1 |
| NEW-P11-001 | P1 | ingestionMutex blocks all processing | Performance | P11-PR1 |
| P11-P1-05 | P1 | Coordinator uses RestoreMaintenanceMode directly | Barrier | P11-PR2 |
| P11-P1-08 | P1 | No review route for uncertain receipts | Feature | P11-PR2 |
| NEW-P11-002 | P2 | AmazonReceiptParser.canParse() too broad | Parser | P11-PR3 |
| NEW-P11-003 | P2 | UberReceiptParser.canParse() too broad | Parser | P11-PR3 |
| NEW-P11-005 | P2 | Amazon regex double-escaped | Parser | P11-PR3 |
| NEW-P11-004 | P3 | 176 formatter instances per date | Performance | P11-PR3 |
| P11-P1-04 | P1 | Wrong privacy mode for email | Privacy | Blocked by U-PR5 |

---

## 7. PR Organization

### P11-PR1 — Core Correctness (Dedupe, Errors, Concurrency)

```
PR name: fix(p11): refine fingerprint, handle failures, resolve conflicts, bounded concurrency
Goal: Fix P1 correctness issues in email ingestion
Issues fixed: P11-P1-01, P11-P1-02, P11-P1-06, NEW-P11-001
Universal dependencies: None
Files likely touched:
  - EmailReceiptIngestionCoordinator.kt
  - EmailReceiptSource entity/DAO
Implementation steps:
  1. P11-P1-01: Add more fields to fingerprint: include currency, sender domain, and narrower date bucket (hour instead of day)
  2. P11-P1-02: On non-DuplicateSkipped failures (e.g. validation error, insert conflict), log structured error and write diagnostic event; don't silently ignore
  3. P11-P1-06: On messageId conflict in insertOrIgnore, look up existing by messageId; return existing source ID; don't create orphan
  4. NEW-P11-001: Replace single Mutex with Semaphore(MAX_CONCURRENT = 3); each email processes independently
Tests:
  - similar_receipts_different_currency_not_deduped
  - validation_failure_produces_diagnostic_event
  - messageId_conflict_returns_existing_source
  - concurrent_emails_process_in_parallel
Risks: Medium — fingerprint change may affect existing dedup behavior
Acceptance criteria:
  - Fingerprint distinguishes receipts with same merchant+amount but different currency/time
  - All failure types produce observable diagnostics
  - messageId conflicts resolved (not orphaned)
  - Batch throughput improved (not serialized)
```

### P11-PR2 — Lifecycle & Review Route

```
PR name: fix(p11): migrate to DatabaseWriteBarrier, add confidence-based review route
Goal: Fix barrier inconsistency and add review route for uncertain receipts
Issues fixed: P11-P1-05, P11-P1-08
Universal dependencies: None
Files likely touched:
  - EmailReceiptIngestionCoordinator.kt
  - ReceiptLifecycleCoordinator.kt (review creation)
Implementation steps:
  1. P11-P1-05: Replace RestoreMaintenanceMode.isWritesAllowed() with writeBarrier.checkWritesAllowed() in coordinator
  2. P11-P1-08: After parsing, check confidence; if below threshold (e.g. 0.7), route to PendingReview instead of auto-creating expense; use same review creation pattern as notification pipeline
Tests:
  - email_ingestion_uses_write_barrier
  - low_confidence_email_creates_pending_review
  - high_confidence_email_creates_expense_directly
Risks: Low — additive changes
Acceptance criteria:
  - Coordinator uses shared DatabaseWriteBarrier
  - Uncertain email receipts go to review queue
  - High-confidence receipts still auto-create expenses
```

### P11-PR3 — Parser Fixes & Performance

```
PR name: fix(p11): narrow parser canParse, fix regex, cache date formatters
Goal: Fix parser false-positives and performance
Issues fixed: NEW-P11-002, NEW-P11-003, NEW-P11-005, NEW-P11-004
Universal dependencies: None
Files likely touched:
  - AmazonReceiptParser.kt
  - UberReceiptParser.kt
  - EmailDateParser.kt
Implementation steps:
  1. NEW-P11-002: Narrow AmazonReceiptParser.canParse() — require Amazon-specific markers (order ID format, "amazon.com" domain in sender)
  2. NEW-P11-003: Narrow UberReceiptParser.canParse() — require Uber-specific markers (trip ID, "uber.com" domain)
  3. NEW-P11-005: Fix double-escaped regex in raw strings: change `\\\\d` to `\\d` in raw string literals (triple-quoted strings don't need double escaping)
  4. NEW-P11-004: Cache DateTimeFormatter instances in companion object; reuse across calls instead of creating 176 per parse
Tests:
  - amazon_parser_rejects_non_amazon_emails
  - uber_parser_rejects_non_uber_emails
  - amazon_regex_matches_actual_order_ids
  - date_parsing_reuses_formatters
Risks: Low — parser fixes
Acceptance criteria:
  - Parsers only claim emails from their respective services
  - Regex correctly matches digits (not literal backslash+d)
  - Date parsing allocates O(1) formatters (not O(N))
```

---

## 8. Detailed Implementation Plan

### P11-PR1 Step-by-Step

1. **Open** `EmailReceiptIngestionCoordinator.kt` — find fingerprint computation; add currency and sender domain to hash input; narrow date bucket from day to hour
2. **Find** failure handling after expense creation attempt; add diagnostic event for non-duplicate failures
3. **Find** `insertOrIgnore` for EmailReceiptSource; after insert returns 0, query by messageId to get existing ID
4. **Replace** `private val ingestionMutex = Mutex()` with `private val ingestionSemaphore = Semaphore(3)`

### P11-PR3 Step-by-Step

1. **Open** `AmazonReceiptParser.kt` — find `canParse()`; add sender domain check (`@amazon.com` or `@amazon.co.uk` etc.)
2. **Open** `UberReceiptParser.kt` — find `canParse()`; add sender domain check (`@uber.com`)
3. **Find** raw string regex patterns with `\\\\d`; replace with `\\d`
4. **Open** `EmailDateParser.kt` — move formatter creation to `companion object { val FORMATTERS = listOf(...) }`

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 11 Adapter/Follow-up |
|---|---|
| U-PR5 (Privacy/RawStorageMode) | **Required:** Wire `RawContentPolicy.emailStorageMode` for email body/subject/sender; replace current `rawOcrStorageMode` usage with email-specific mode |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 11 targeted tests
./gradlew :app:testDebugUnitTest --tests "*EmailReceipt*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AmazonReceipt*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*UberReceipt*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*EmailDate*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P11-PR1: Fingerprint refined; failures handled; conflicts resolved; concurrency improved
- [ ] P11-PR2: Write barrier used; review route for uncertain receipts
- [ ] P11-PR3: Parsers narrow; regex correct; formatters cached
- [ ] U-PR5 adapter landed: Email privacy mode correct
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] Pipeline 11 status upgraded to GREEN in master tracker
