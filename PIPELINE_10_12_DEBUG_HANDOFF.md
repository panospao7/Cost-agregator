# Pipeline 10–12: Debug Handoff + NEW Issues Report

> **Generated:** 2026-05-31  
> **Existing report issues:** P10: 30, P11: 22, P12: 25 (total 77)  
> **NEW issues found beyond reports:** 18 (4 HIGH, 8 MEDIUM, 6 LOW)  
> **Combined total:** 95 issues across Pipelines 10–12

---

## Executive Summary

| Pipeline | Verdict | Existing Issues | NEW Issues | Top NEW Risk |
|----------|---------|:---------------:|:----------:|--------------|
| 10 — Bank Integration | PROTOTYPE_SHELL | 30 (2 P0) | 4 | BankTokenCipher swallows key invalidation |
| 11 — Email Receipt | IMPROVED_BUT_NOT_CLEAN | 22 (3 P0) | 5 | ingestionMutex blocks all concurrent processing |
| 12 — Import/Export | IMPROVED_BUT_NOT_CLEAN | 25 (1 P0) | 7 | JSON export produces invalid JSON |

---

## NEW Issues — Pipeline 10 (Bank Integration)

| ID | Severity | Title | Impact |
|----|----------|-------|--------|
| NEW-P10-001 | MEDIUM | `BankApiConfig.isStubMode` is mutable global with no thread-safety | Non-deterministic tests; misconfigured release could run stubs |
| NEW-P10-002 | HIGH | `BankTokenCipher.decryptIfNeeded()` swallows `KeyPermanentlyInvalidatedException` | After biometric change, app silently treats invalidated keys as "no token" instead of requiring re-auth |
| NEW-P10-003 | MEDIUM | `BankStatementLifecycleProcessor` per-transaction catch swallows CancellationException | Cancelled batch continues writing to DB during restore |
| NEW-P10-004 | LOW | `generateMockTransactions()` uses unseeded random — non-reproducible test data | Integration tests are flaky |

---

## NEW Issues — Pipeline 11 (Email Receipt Ingestion)

| ID | Severity | Title | Impact |
|----|----------|-------|--------|
| NEW-P11-001 | HIGH | `ingestionMutex` serializes ALL email processing — batch blocks real-time ingestion | During 50-email backfill, real-time notifications queue for entire batch duration |
| NEW-P11-002 | MEDIUM | `AmazonReceiptParser.canParse()` matches ANY email containing "amazon.com" in body | Non-receipt emails parsed as Amazon receipts, creating false expenses |
| NEW-P11-003 | MEDIUM | `UberReceiptParser.canParse()` matches "order" in subject + "uber" in body | Restaurant order emails mentioning Uber parsed as Uber receipts |
| NEW-P11-004 | LOW | `parseLocalizedDate()` creates 176 DateTimeFormatter instances per date string | Batch processing unnecessarily slow |
| NEW-P11-005 | MEDIUM | Amazon parser regex patterns double-escaped in raw strings (`\\s` instead of `\s`) | Order number extraction fails — whitespace not matched, reducing dedup accuracy |

---

## NEW Issues — Pipeline 12 (Import/Export/Accounting)

| ID | Severity | Title | Impact |
|----|----------|-------|--------|
| NEW-P12-001 | HIGH | JSON export missing comma between null `businessPurpose` and `sourceLinks` | **Every personal expense row produces invalid JSON** — import tools fail |
| NEW-P12-002 | HIGH | `sourceLinksJson` double-escaped through `escapeJson()` | Source link provenance data corrupted in exports |
| NEW-P12-003 | HIGH | `CsvCellSanitizer` corrupts negative amounts in Xero/FreshBooks exports | All refunds/credits get `'` prefix — unparseable by accounting software |
| NEW-P12-004 | MEDIUM | `createExportFile()` doesn't validate extension parameter — path traversal | Future caller could write files outside export directory |
| NEW-P12-005 | MEDIUM | Accounting validation loads ALL expenses into memory before streaming | OOM crash on large accounting exports |
| NEW-P12-006 | LOW | `loadExpenseCount()` shows generic error during restore instead of restore-aware message | Poor UX during restore |
| NEW-P12-007 | MEDIUM | `sanitizeIif()` corrupts merchant names starting with `-` in QuickBooks | Duplicate/incorrect vendor entries in QuickBooks |

---

## Recommended Fix Priority

### Immediate (data corruption / security)
1. **NEW-P12-001** — Fix missing comma in JSON null path (one-character fix: `append("null,")`)
2. **NEW-P12-002** — Don't escape already-serialized sourceLinksJson
3. **NEW-P12-003** — Don't sanitize numeric fields through CsvCellSanitizer
4. **NEW-P10-002** — Distinguish `KeyPermanentlyInvalidatedException` from generic decrypt failure

### Next sprint
5. **NEW-P11-001** — Redesign ingestion mutex for batch vs real-time concurrency
6. **NEW-P11-005** — Fix double-escaped regex patterns in raw strings
7. **NEW-P11-002/003** — Tighten parser `canParse()` to require sender + content match
8. **NEW-P12-005** — Use aggregate SQL for accounting validation instead of full load
9. **NEW-P10-003** — Add CancellationException rethrow in statement processor loop

---

## Existing Report Summary (for reference)

### Pipeline 10 — Key existing issues:
- **P10-P0-01/02**: Feature is demo-only shell (release-safe but non-functional)
- **P10-P1-01–09**: No connection persistence, no OAuth, no sync ledger, no review routing, no metadata, no token refresh, no shared dedupe, no atomicity
- Total: 30 issues, mostly architectural gaps for a feature that doesn't exist yet in production

### Pipeline 11 — Key existing issues:
- **P11-P0-01**: EmailReceiptData name collision (compile risk)
- **P11-P0-02/03**: Raw subject leaks to Expense.notes; wrong privacy mode for email fields
- **P11-P1-04–12**: Message-ID dedupe broken under sanitization, source conflicts, double side-effects, no review route, CancellationException swallowed
- Total: 22 issues, concentrated in privacy, dedupe, and routing correctness

### Pipeline 12 — Key existing issues:
- **P12-P0-01**: No verified real import pipeline
- **P12-P1-02–13**: No true snapshot, encryption not wired, fields dropped, no receipt links, no manifest, accounting validation not snapshot-tied
- Total: 25 issues, concentrated in roundtrip completeness and export correctness
