# Pipeline 11 — Email Receipt Ingestion: Consolidated Issue Registry

> **Last validated:** 2026-06-01 against local HEAD code  
> **Status:** 4 FIXED, 5 PARTIAL, 0 TODO, 4 NEW open issues  
> **Total open items:** 9

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P11-P1-01 | P1 | Duplicate fingerprint includes message ID | ⚠ PARTIAL | ⚠ **PARTIAL** | Fingerprint content-only but too coarse (merchant+amount+date bucket) |
| P11-P1-02 | P1 | Existing expense duplicate treated as failure | ⚠ PARTIAL | ⚠ **PARTIAL** | `DuplicateSkipped` handled; other failures ignored |
| P11-P1-03 | P1 | Service path only partially uses receipt lifecycle | ✅ FIXED | ✅ **FIXED** | Coordinator owns mutations |
| P11-P1-04 | P1 | Raw email body/subject/sender persisted without privacy policy | ⚠ PARTIAL | ⚠ **PARTIAL** | Sanitizer used but wrong mode for email fields |
| P11-P1-05 | P1 | Restore barrier incomplete at email service boundary | ⚠ PARTIAL | ⚠ **PARTIAL** | Service checks barrier; coordinator uses `RestoreMaintenanceMode` directly |
| P11-P1-06 | P1 | Email source insert conflicts ignored | ⚠ PARTIAL | ⚠ **PARTIAL** | Checks `insertOrIgnore` but messageId-only conflict unresolved |
| P11-P1-07 | P1 | Receipt post-save side effects skipped in service path | ✅ FIXED | ✅ **FIXED** | Side effects dispatched correctly; double-dispatch verified NOT present (U-PR8) |
| P11-P1-08 | P1 | No pending-review route for uncertain email receipts | ✅ FIXED (P11-PR2) | ✅ **FIXED** | `ReceiptLifecycleCoordinator` line 1078 checks `emailData.confidence <= EMAIL_AUTO_EXPENSE_MIN_CONFIDENCE` (0.75); low-confidence receipts return `NeedsReview` instead of auto-creating expense. Verified 2026-06-01 |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P11-001 | P1 | `ingestionMutex` blocks all concurrent processing during batch | EmailReceiptIngestionService.kt | ✅ FIXED (P11-PR1) | Replaced with `Semaphore(3)` in `EmailReceiptIngestionService.kt` line 77. Verified 2026-06-01 |
| NEW-P11-002 | P2 | `AmazonReceiptParser.canParse()` overly broad | AmazonReceiptParser.kt | ✅ FIXED (P11-PR3) |
| NEW-P11-003 | P2 | `UberReceiptParser.canParse()` overly broad | UberReceiptParser.kt | ✅ FIXED (P11-PR3) |
| NEW-P11-004 | P3 | `parseLocalizedDate()` 176 formatter instances per date | EmailDateParser.kt | ✅ FIXED (P11-PR3) |
| NEW-P11-005 | P2 | Amazon parser regex double-escaped in raw strings | AmazonReceiptParser.kt | ✅ FIXED (P11-PR3) |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 3 |
| ⚠ PARTIAL (old issues) | 5 |
| 📝 TODO ONLY (old issues) | 0 |
| 🔴 OPEN (new issues) | 4 |
| **Total open work** | **9** |

---

## Priority Order for Remaining Work

### P1 (must fix)
1. ~~**NEW-P11-001** — `ingestionMutex` blocks all concurrent processing during batch~~ ✅ FIXED (Semaphore(3))
2. **P11-P1-01** — Fingerprint too coarse (false-positive dedup)
3. **P11-P1-02** — Non-duplicate failures ignored
4. **P11-P1-04** — Sanitizer wrong mode for email fields
5. **P11-P1-05** — Coordinator uses `RestoreMaintenanceMode` directly (not barrier)
6. **P11-P1-06** — messageId-only conflict unresolved
7. ~~**P11-P1-07 remainder** — Double-dispatch bug in side effects~~ ✅ Verified NOT A BUG (U-PR8)
8. ~~**P11-P1-08** — No pending-review route for uncertain email receipts~~ ✅ FIXED (confidence ≤ 0.75 routes to NeedsReview)

### P2 (should fix)
9. **NEW-P11-002** — `AmazonReceiptParser.canParse()` overly broad
10. **NEW-P11-003** — `UberReceiptParser.canParse()` overly broad
11. **NEW-P11-005** — Amazon parser regex double-escaped in raw strings

### P3 (cleanup)
12. **NEW-P11-004** — `parseLocalizedDate()` 176 formatter instances per date
