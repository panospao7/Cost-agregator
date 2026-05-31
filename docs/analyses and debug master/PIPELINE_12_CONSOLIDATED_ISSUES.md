# Pipeline 12 — Import/Export/Accounting: Consolidated Issue Registry

> **Last validated:** 2026-05-31 against local HEAD code  
> **Status:** 2 FIXED, 4 PARTIAL, 4 TODO, 7 NEW open issues  
> **Total open items:** 15

---

## Old Issues (from master tracker) — Validated Status

| ID | Sev | Title | Tracker Said | **Actual Status** | Notes |
|----|-----|-------|-------------|-------------------|-------|
| P12-P0-01 | P0 | No app-level CSV/JSON import roundtrip pipeline | 📝 TODO ONLY | 📝 **TODO ONLY** | No real import pipeline verified end-to-end |
| P12-P1-01 | P1 | Xero/FreshBooks CSV exporters don't do real CSV escaping | ✅ FIXED | ✅ **FIXED** | RFC-4180 compliant `CsvCellSanitizer` |
| P12-P1-02 | P1 | Accounting validation is per-page, not global | ⚠ PARTIAL | ⚠ **PARTIAL** | Validation loads all data but not snapshot-tied |
| P12-P1-03 | P1 | Multi-currency export fields incomplete | ⚠ PARTIAL | ⚠ **PARTIAL** | Fields added but no `conversionStatus` |
| P12-P1-04 | P1 | Export snapshot consistency is not real | 📝 TODO ONLY | 📝 **TODO ONLY** | No true snapshot; concurrent writes cause issues |
| P12-P1-05 | P1 | Normal exports plaintext and not privacy-gated | 📝 TODO ONLY | 📝 **TODO ONLY** | Plaintext default; encryption not wired |
| P12-P1-06 | P1 | Export silently drops many app fields | 📝 TODO ONLY | 📝 **TODO ONLY** | Fields still dropped |
| P12-P1-07 | P1 | Receipt links not represented in exports | 📝 TODO ONLY | 📝 **TODO ONLY** | Receipt links not exported |
| P12-P1-08 | P1 | Business/tax fields not exported | ⚠ PARTIAL | ⚠ **PARTIAL** | DTO has fields; writers omit some |
| P12-P1-09 | P1 | Accountant PDF has raw mixed-currency combined total | ✅ FIXED | ✅ **FIXED** | PDF groups by currency |
| P12-P1-10 | P1 | Export can run during restore/restart-required state | ⚠ PARTIAL | ⚠ **PARTIAL** | ViewModel checks; repository doesn't |

---

## New Issues (from deep audit 2026-05-31)

| ID | Sev | Title | File | Status |
|----|-----|-------|------|--------|
| NEW-P12-001 | P0 | JSON export produces invalid JSON (missing comma on null) | JsonExporter.kt | 🔴 OPEN |
| NEW-P12-002 | P1 | `sourceLinksJson` double-escaped | ExportDataRepository.kt | 🔴 OPEN |
| NEW-P12-003 | P1 | CsvCellSanitizer corrupts negative amounts in accounting | CsvCellSanitizer.kt | 🔴 OPEN |
| NEW-P12-004 | P2 | `createExportFile` path traversal risk | ExportFileManager.kt | 🔴 OPEN |
| NEW-P12-005 | P2 | Accounting validation loads ALL expenses (OOM) | AccountingValidation.kt | 🔴 OPEN |
| NEW-P12-006 | P3 | `loadExpenseCount` generic error during restore | ExportDataRepository.kt | 🔴 OPEN |
| NEW-P12-007 | P2 | `sanitizeIif` corrupts merchant names starting with `-` | IifExporter.kt | 🔴 OPEN |

---

## Summary by Status

| Status | Count |
|--------|------:|
| ✅ FIXED (old issues) | 2 |
| ⚠ PARTIAL (old issues) | 4 |
| 📝 TODO ONLY (old issues) | 4 |
| 🔴 OPEN (new issues) | 7 |
| **Total open work** | **15** |

---

## Priority Order for Remaining Work

### P0 (critical)
1. **NEW-P12-001** — JSON export produces invalid JSON (missing comma on null — broken output)
2. **P12-P0-01** — No real import pipeline verified end-to-end

### P1 (must fix)
3. **NEW-P12-002** — `sourceLinksJson` double-escaped (corrupted export data)
4. **NEW-P12-003** — CsvCellSanitizer corrupts negative amounts in accounting
5. **P12-P1-02** — Validation not snapshot-tied
6. **P12-P1-03** — Multi-currency export missing `conversionStatus`
7. **P12-P1-04** — Export snapshot consistency is not real
8. **P12-P1-05** — Plaintext default; encryption not wired
9. **P12-P1-06** — Export silently drops many app fields
10. **P12-P1-07** — Receipt links not exported
11. **P12-P1-08** — Writers omit some DTO fields
12. **P12-P1-10** — Repository doesn't check restore state

### P2 (should fix)
13. **NEW-P12-004** — `createExportFile` path traversal risk
14. **NEW-P12-005** — Accounting validation loads ALL expenses (OOM)
15. **NEW-P12-007** — `sanitizeIif` corrupts merchant names starting with `-`

### P3 (cleanup)
16. **NEW-P12-006** — `loadExpenseCount` generic error during restore
