# Pipeline 12 — Import / Export / Accounting: Post-Universal Implementation Plan

> **Generated:** 2026-05-31  
> **Pipeline:** Pipeline 12 — Import / Export / Accounting  
> **Universal fixes baseline:** U-PR1 ✅, U-PR2 ✅, U-PR3 ✅, U-PR4 ✅, U-PR5 ⏳, U-PR6 ✅, U-PR7 ✅, U-PR8 ✅  
> **Scope:** Pipeline-local remaining work after universal fixes

---

## 1. Executive Verdict

```
Pipeline: 12 — Import / Export / Accounting
Verdict: RED
Summary:
- 2 old issues FIXED, 4 PARTIAL, 4 TODO ONLY
- 0 issues directly fixed by universal (U-PR3 normalized currency but export paths still incomplete)
- 15 pipeline-local issues remain (2 P0, 9 P1, 3 P2, 1 P3)
- P0: JSON export produces INVALID JSON — broken output file
- P0: No real import roundtrip verified
- Critical bugs: CsvCellSanitizer corrupts negative amounts, sourceLinks double-escaped
- Many P1 issues are feature gaps (encryption, receipt links, field completeness)
- Blocked by U-PR5 for export privacy/encryption
```

---

## 2. Sources Reviewed

**Docs:** `UNIVERSAL_ISSUE_TRACKER.md`, `PIPELINE_ISSUES_MASTER_TRACKER.md`, `PIPELINE_12_CONSOLIDATED_ISSUES.md`

**Source files:** `JsonExporter.kt`, `CsvCellSanitizer.kt`, `ExportDataRepository.kt`, `IifExporter.kt`, `ExportFileManager.kt`, `AccountingValidation.kt`

---

## 3. Universal Fix Impact Summary

| Universal ID | Impact on Pipeline 12 | Adapter Needed | Status |
|---|---|---|---|
| U-PR3 (Money/Currency) | Export should use normalized amounts | Yes — wire conversionStatus | Partial |
| U-PR5 (Privacy) | Export privacy/encryption | Yes — adapter for export redaction | ⏳ Blocked |
| Others | No direct impact | No | N/A |

---

## 4. Consolidated Issue Reconciliation

| Pipeline Issue ID | Current Status | Universal Relation | Remaining Work |
|---|---|---|---|
| P12-P0-01 | 📝 TODO | None | Verify import roundtrip end-to-end |
| P12-P1-01 | ✅ FIXED | None | None |
| P12-P1-02 | ⚠ PARTIAL | None | Tie validation to snapshot |
| P12-P1-03 | ⚠ PARTIAL | U-PR3 | Add conversionStatus field |
| P12-P1-04 | 📝 TODO | None | Implement snapshot consistency |
| P12-P1-05 | 📝 TODO | U-PR5 | Wire encryption |
| P12-P1-06 | 📝 TODO | None | Add missing fields to export |
| P12-P1-07 | 📝 TODO | None | Export receipt links |
| P12-P1-08 | ⚠ PARTIAL | None | Complete writer field coverage |
| P12-P1-09 | ✅ FIXED | None | None |
| P12-P1-10 | ⚠ PARTIAL | U-PR4 | Add barrier to repository |
| NEW-P12-001 | 🔴 OPEN | None | Fix JSON comma |
| NEW-P12-002 | 🔴 OPEN | None | Fix double-escaping |
| NEW-P12-003 | 🔴 OPEN | None | Fix negative amount sanitization |
| NEW-P12-004 | 🔴 OPEN | None | Sanitize filename |
| NEW-P12-005 | 🔴 OPEN | None | Paginate validation |
| NEW-P12-006 | 🔴 OPEN | None | Better error during restore |
| NEW-P12-007 | 🔴 OPEN | None | Fix IIF merchant sanitization |

---

## 5. New Issues / Regressions

No regressions from universal fixes. U-PR3 normalized currency in forecast/dashboard but export paths still use raw amounts without conversionStatus.

---

## 6. Open Issue Master List

| ID | Severity | Title | Area | Suggested PR |
|---|---|---|---|---|
| NEW-P12-001 | P0 | JSON export invalid (missing comma) | Export | P12-PR1 |
| NEW-P12-003 | P1 | CsvCellSanitizer corrupts negative amounts | Export | P12-PR1 |
| NEW-P12-002 | P1 | sourceLinksJson double-escaped | Export | P12-PR1 |
| NEW-P12-007 | P2 | sanitizeIif corrupts merchant with leading dash | Export | P12-PR1 |
| P12-P1-10 | P1 | Repository doesn't check restore state | Safety | P12-PR1 |
| NEW-P12-004 | P2 | Path traversal in createExportFile | Security | P12-PR2 |
| NEW-P12-005 | P2 | Validation loads ALL expenses (OOM) | Performance | P12-PR2 |
| NEW-P12-006 | P3 | Generic error during restore | UX | P12-PR2 |
| P12-P1-03 | P1 | Missing conversionStatus | Currency | P12-PR3 |
| P12-P1-08 | P1 | Writers omit DTO fields | Completeness | P12-PR3 |
| P12-P0-01 | P0 | No real import roundtrip | Feature | P12-PR4 (feature) |
| P12-P1-04 | P1 | No snapshot consistency | Feature | P12-PR4 (feature) |
| P12-P1-05 | P1 | No encryption | Feature | Blocked by U-PR5 |
| P12-P1-06 | P1 | Fields dropped | Feature | P12-PR3 |
| P12-P1-07 | P1 | Receipt links not exported | Feature | P12-PR3 |

---

## 7. PR Organization

### P12-PR1 — Critical Export Bugs (Must Land First)

```
PR name: fix(p12): invalid JSON, negative amount corruption, double-escaped links, IIF dash, barrier
Goal: Fix P0/P1 bugs that produce corrupted export files
Issues fixed: NEW-P12-001, NEW-P12-003, NEW-P12-002, NEW-P12-007, P12-P1-10
Universal dependencies: None
Files likely touched:
  - JsonExporter.kt
  - CsvCellSanitizer.kt
  - ExportDataRepository.kt
  - IifExporter.kt
Implementation steps:
  1. NEW-P12-001: Find JSON assembly where null field omits comma; fix: use proper JSON serialization (JSONObject or kotlinx.serialization) instead of string concatenation
  2. NEW-P12-003: In CsvCellSanitizer, don't trigger formula injection guard for leading `-` when followed by digit (negative number); only guard `-` followed by formula characters (=, +, @)
  3. NEW-P12-002: Find sourceLinksJson serialization; it's being JSON-encoded twice (once to JSON string, then that string is JSON-encoded again); remove outer encoding
  4. NEW-P12-007: In sanitizeIif, don't strip leading `-` from merchant names; only sanitize IIF-specific control characters
  5. P12-P1-10: Add writeBarrier.checkWritesAllowed() (or readBarrier for exports) at repository level, not just ViewModel
Tests:
  - json_export_produces_valid_json_with_null_fields
  - csv_negative_amounts_preserved_correctly
  - source_links_not_double_escaped
  - merchant_with_leading_dash_preserved_in_iif
  - export_blocked_during_restore
Risks: Low — targeted fixes to output formatting
Acceptance criteria:
  - JSON export parseable by standard JSON parser
  - Negative amounts like "-15.50" appear correctly in CSV
  - sourceLinks readable as JSON (not escaped string)
  - Merchant "−Pizza" preserved in IIF output
  - Export fails gracefully during restore mode
```

### P12-PR2 — Safety & Performance

```
PR name: fix(p12): path traversal guard, paginated validation, restore error message
Goal: Fix security and performance issues
Issues fixed: NEW-P12-004, NEW-P12-005, NEW-P12-006
Universal dependencies: None
Files likely touched:
  - ExportFileManager.kt
  - AccountingValidation.kt
  - ExportDataRepository.kt
Implementation steps:
  1. NEW-P12-004: Sanitize filename — strip path separators, "..", and special characters; validate final path is within expected export directory
  2. NEW-P12-005: Paginate validation query — load expenses in batches of 500; validate incrementally; abort on first failure or accumulate up to MAX_ERRORS
  3. NEW-P12-006: When restore mode is active, return specific error message "Export unavailable during restore" instead of generic error
Tests:
  - path_traversal_filename_rejected
  - validation_handles_large_dataset_without_oom
  - restore_mode_shows_specific_error
Risks: Low — defensive improvements
Acceptance criteria:
  - No file created outside export directory regardless of filename input
  - Validation works on 100k+ expense datasets without OOM
  - User sees clear message during restore
```

### P12-PR3 — Export Completeness

```
PR name: fix(p12): add conversionStatus, complete writer fields, export receipt links
Goal: Make exports complete and accurate
Issues fixed: P12-P1-03, P12-P1-06, P12-P1-07, P12-P1-08
Universal dependencies: U-PR3 (already landed — conversion data available)
Files likely touched:
  - ExportTransaction.kt (DTO)
  - CsvExporter.kt, JsonExporter.kt, IifExporter.kt (writers)
  - ExportDataRepository.kt (receipt link query)
Implementation steps:
  1. P12-P1-03: Add `conversionStatus` field to ExportTransaction; populate from MoneyNormalizationEngine conversion outcome
  2. P12-P1-06/08: Audit ExportTransaction DTO fields vs writer output; add missing fields to each writer format
  3. P12-P1-07: Query receipt links for each expense; include in export as nested array (JSON) or additional columns (CSV)
Tests:
  - export_includes_conversionStatus
  - all_dto_fields_present_in_csv_output
  - receipt_links_exported_with_expenses
Risks: Medium — changes export schema; may break consumers
Acceptance criteria:
  - conversionStatus populated for all multi-currency exports
  - No DTO field silently dropped by any writer
  - Receipt links traceable in exported data
```

### P12-PR4 — Import Roundtrip (Feature)

```
PR name: feat(p12): verify import roundtrip end-to-end, snapshot consistency
Goal: Prove import/export roundtrip works
Issues fixed: P12-P0-01, P12-P1-04
Universal dependencies: None
Files likely touched:
  - ImportCoordinator.kt
  - JsonExpenseImporter.kt, CsvExpenseImporter.kt
  - ExportDataRepository.kt (snapshot)
Implementation steps:
  1. P12-P0-01: Create integration test that exports → imports → verifies field equality for all supported formats
  2. P12-P1-04: Before export, enter read-only snapshot mode (or use Room checkpoint); ensure no concurrent writes during export
Tests:
  - json_roundtrip_preserves_all_fields
  - csv_roundtrip_preserves_all_fields
  - export_snapshot_consistent_under_concurrent_writes
Risks: High — snapshot consistency is architectural
Acceptance criteria:
  - Roundtrip test passes for JSON and CSV
  - Export produces consistent data even under concurrent writes
NOTE: Snapshot consistency may be deferred if too complex
```

---

## 8. Detailed Implementation Plan

### P12-PR1 Step-by-Step (Priority — fixes broken output)

1. **Open** `JsonExporter.kt` — find string concatenation for JSON; replace with `JSONObject` or `kotlinx.serialization.json.buildJsonObject`; this automatically handles null fields and commas
2. **Open** `CsvCellSanitizer.kt` — find formula injection guard; change condition from `startsWith("-")` to `startsWith("-") && !content.matches(Regex("-?\\d.*"))` (allow negative numbers)
3. **Open** `ExportDataRepository.kt` — find `sourceLinksJson` field population; if it does `JSONObject(sourceLinks).toString()` and then that string gets JSON-encoded again by the exporter, remove one layer
4. **Open** `IifExporter.kt` — find `sanitizeIif`; remove leading-dash stripping for merchant names
5. **Add** `writeBarrier.checkWritesAllowed()` or `readBarrier` check at repository export entry points

---

## 9. Pipeline-Local Follow-up After Universal Work

| Universal PR | Pipeline 12 Adapter/Follow-up |
|---|---|
| U-PR3 (Money/Currency) | Required: Wire conversionStatus from normalization engine into ExportTransaction |
| U-PR5 (Privacy) | Required: Wire export encryption; apply retention/redaction scope to exported data; gate raw export behind privacy settings |

---

## 10. Validation Commands

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace

# Pipeline 12 targeted tests
./gradlew :app:testDebugUnitTest --tests "*Export*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Import*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CsvCell*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Accounting*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*JsonExport*" --stacktrace

./gradlew :app:check --stacktrace
```

---

## 11. Final Definition of Done

- [ ] P12-PR1: JSON valid; negatives preserved; links not double-escaped; IIF correct; barrier enforced
- [ ] P12-PR2: Path traversal blocked; validation paginated; restore error clear
- [ ] P12-PR3: conversionStatus populated; all fields exported; receipt links included
- [ ] P12-PR4: Roundtrip verified (if prioritized); snapshot consistent
- [ ] U-PR5 adapter landed: Export encryption and privacy gating
- [ ] All existing tests pass
- [ ] Build succeeds
- [ ] Pipeline 12 status upgraded to GREEN in master tracker
