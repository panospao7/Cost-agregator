# Pipeline 12 Static Debug Report — Import / Export / Accounting

Commit reviewed: `b6abe0ac50c271f5c37a89c8981cb774ca543bba`  
Mode: static GitHub/code-doc review only. I did **not** run Gradle/tests locally.

## Executive verdict

Pipeline 12 is **improved on export/accounting**, but it is **not closed**.

The export side has real progress:

```text
CsvCellSanitizer is now RFC-4180-aware
Xero/FreshBooks use the shared sanitizer
generic CSV/JSON export schema expanded to v2
ExportTransaction now carries more currency/business metadata
accounting validation now checks the full loaded dataset before streaming
ExportOptionsViewModel checks PrivacyGate and DatabaseReadBarrier
AccountantReportPdfExporter no longer shows a raw mixed-currency combined total
```

But the pipeline still fails its main product promise:

```text
export -> import into fresh DB -> totals/links/metadata preserved
```

Highest remaining user-impact risks:

1. **No real app-level CSV/JSON import pipeline is visible in main source.**
2. **The “roundtrip” golden test is not a real import/export roundtrip; it only tests CSV sanitizer behavior.**
3. **Export snapshot consistency is still not real; count and rows can diverge under concurrent writes.**
4. **Normal export privacy/encryption is unsafe: default plaintext, hardcoded encryption password when enabled, no user-facing encryption passphrase, no redaction mode.**
5. **Generic CSV/JSON still drop many app fields: receipt links, shared/ownership fields, location, transfer metadata, business category/project, requiresReceipt, source links.**
6. **Accounting validation loads all rows into memory before streaming, undermining large-export safety.**
7. **Accounting exporters can still be called directly without enforcing validation.**
8. **Accounting exports use gross `amount`, not effective/shared ownership amount, which can overstate shared expenses.**
9. **Export read/restore/privacy guards live mostly in the ViewModel, not in repository/exporter boundary.**
10. **No durable export/import operation ledger, checksum, manifest, or unsupported-field report exists.**

Current status: **yellow/orange**. Export is usable as a beta utility, but Pipeline 12 is not production-grade import/export/accounting yet.

---

# Sources checked

- Commit:  
  https://github.com/panospao7/Cost-agregator/commit/b6abe0ac50c271f5c37a89c8981cb774ca543bba

- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md

- Previous Pipeline 12 report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline-12-import-export-accounting-debug-report.md

- Current files:
  - `ExportOptionsViewModel.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt
  - `ExportDataRepository.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt
  - `DeterministicExpenseExportPager.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/repository/DeterministicExpenseExportPager.kt
  - `AccountingExporters.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt
  - `CsvCellSanitizer.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/export/CsvCellSanitizer.kt
  - `ExportTransaction.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt
  - `ExpenseExportMapper.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt
  - `AccountingExportPolicy.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExportPolicy.kt
  - `AccountantReportPdfExporter.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt
  - `ExportAnonymizer.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt
  - `DatabaseReadBarrier.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseReadBarrier.kt
  - `BackupPrivacyGate.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/main/java/com/yourname/expensetracker/domain/privacy/BackupPrivacyGate.kt
  - `CsvExportImportRoundtripGoldenTest.kt`  
    https://raw.githubusercontent.com/panospao7/Cost-agregator/b6abe0ac50c271f5c37a89c8981cb774ca543bba/app/src/test/java/com/yourname/expensetracker/golden/CsvExportImportRoundtripGoldenTest.kt

---

# 1. Tracker reconciliation

Master tracker currently says:

| ID | Tracker status |
|---|---|
| P12-P0-01 | partial |
| P12-P1-01 | fixed |
| P12-P1-02 | fixed |
| P12-P1-03 | fixed |
| P12-P1-04 | TODO |
| P12-P1-05 | TODO |
| P12-P1-06 | TODO |
| P12-P1-07 | TODO |
| P12-P1-08 | fixed |
| P12-P1-09 | TODO |
| P12-P1-10 | partial |

My current status:

| ID | My status | Reason |
|---|---:|---|
| P12-P0-01 | **Open / not convincingly partial** | I did not find `ImportCoordinator`, `CsvExpenseImporter`, `JsonExpenseImporter`, `ImportOptionsScreen`, or real app import files in main source. The visible “roundtrip” test only tests CSV sanitizer. |
| P12-P1-01 | **Mostly fixed** | `CsvCellSanitizer` is now RFC-4180-safe and Xero/FreshBooks call it. Generic CSV still has duplicate local `escapeCsv()` instead of one shared writer. |
| P12-P1-02 | **Partial** | UI validates the whole loaded dataset before streaming, but does so by loading all expenses into memory; direct exporter APIs do not enforce policy. |
| P12-P1-03 | **Partial** | DTO/mapper now carry more currency fields, but no conversion status/quality, and accounting exports still use gross amount. |
| P12-P1-04 | **Open** | Pager explicitly says keyset pagination is not a true atomic snapshot; count is not snapshot-anchored. |
| P12-P1-05 | **Partial / privacy-risky** | Privacy gate and optional encryption exist, but default is plaintext, encryption password is `"default"`, and raw export policy is conceptually wrong. |
| P12-P1-06 | **Partial** | CSV/JSON v2 includes more fields, but many app fields are still dropped with no manifest. |
| P12-P1-07 | **Open** | `ExportTransaction` TODO says receipt links are not exported. |
| P12-P1-08 | **Partial, not fixed** | DTO has business fields, but CSV/JSON writers only emit `isBusinessExpense` and `businessPurpose`; category/project/requiresReceipt are omitted. |
| P12-P1-09 | **Mostly fixed** | PDF no longer raw-sums mixed-currency combined total; it shows per-currency totals only. |
| P12-P1-10 | **Partial** | ViewModel checks read/restore state, but repository/exporter direct call paths are unguarded; `loadExpenseCount()` also lacks read barrier. |

---

# 2. Original issue evaluation

## P12-P0-01 — No app-level CSV/JSON import roundtrip pipeline

### Current state

Still open.

The source tree for `domain/export` only contains export-related files:

```text
AccountantReportPdfExporter.kt
AccountingExportPolicy.kt
AccountingExporters.kt
CsvCellSanitizer.kt
ExpenseExportMapper.kt
ExportTransaction.kt
```

I did not find visible main-source equivalents for:

```text
ImportCoordinator
JsonExpenseImporter
CsvExpenseImporter
ImportResult
ImportFormat
ImportOptionsScreen
ImportPreview
```

The test `CsvExportImportRoundtripGoldenTest` is named like a roundtrip test, but it only verifies:

```text
CsvCellSanitizer neutralizes formula injection
CsvCellSanitizer quotes comma/quote/newline cells
numeric strings parse back
```

It does **not**:

```text
export rows
parse exported file
insert into fresh DB
route through TransactionLifecycleCoordinator
compare totals
compare receipt links
compare categories/business/currency metadata
```

### User impact

The app still cannot prove that exported CSV/JSON can be imported into a fresh install without data loss.

### Fix strategy

Build real import lifecycle:

```kotlin
sealed interface ImportResult {
    data class Preview(...)
    data class Imported(...)
    data class Failed(...)
}

interface ExpenseImporter {
    val format: ImportFormat
    suspend fun preview(input: InputStream): ImportPreview
    suspend fun import(input: InputStream, options: ImportOptions): ImportResult
}
```

All imported rows must route through:

```text
TransactionLifecycleCoordinator.createExpense()
ReceiptLinkService.linkReceiptToExpense()
CategoryResolver
DuplicateDetectionPolicy
```

---

## P12-P1-01 — Xero/FreshBooks CSV escaping

### Current state

Mostly fixed.

`CsvCellSanitizer.sanitize()` now does:

```text
strip null/vertical-tab
neutralize formula-leading = + - @
quote fields containing comma, quote, CR, LF
double embedded quotes
```

`XeroCSVExporter` and `FreshBooksExporter` call `CsvCellSanitizer.sanitize(...)`.

### Remaining caveats

- Generic CSV still has a local `escapeCsv()` with similar but not identical rules.
- QuickBooks IIF uses separate tab-delimited escaping, which is acceptable, but should call `sanitizeIif()` for consistency.
- There are no visible tests proving `XeroCSVExporter` and `FreshBooksExporter` preserve row/column counts with comma/quote/newline values.

### Fix strategy

Use one writer everywhere:

```kotlin
CsvCellWriter.encode(...)
CsvCellWriter.encodeIif(...)
```

Acceptance:

```text
generic/xero/freshbooks all pass identical formula/quote/comma/newline tests
```

---

## P12-P1-02 — Accounting validation per-page, not global

### Current state

Partially fixed.

`ExportOptionsViewModel.generateExport()` now does full-dataset validation before streaming accounting exports:

```kotlin
val allExpenses = exportDataRepository.getExpensesBetween(startDate, endDate)
accountingExportPolicy.validateAccountingDataset(
    allExpenses.map { it.toExportTransaction() },
    format.accountingExportDisplayName()
)
```

This catches cross-page mixed currency/type issues.

But:

1. It loads the whole dataset into memory, defeating streaming for accounting exports.
2. Validation lives in the ViewModel, not the repository/export operation boundary.
3. Direct calls to:
   - `XeroCSVExporter.export(...)`
   - `FreshBooksExporter.export(...)`
   - `QuickBooksIIFExporter.export(...)`
   can bypass validation.
4. It validates after file object creation, though before writing; not terrible, but not ideal.

### User impact

Very large accounting exports can still OOM during validation. Developer/direct API call paths can produce invalid accounting files.

### Fix strategy

Add SQL aggregate validation:

```sql
SELECT DISTINCT currency ...
SELECT DISTINCT transactionType ...
SELECT COUNT(*) ...
```

And enforce it in `ExportCoordinator`, not only ViewModel.

---

## P12-P1-03 — Multi-currency export fields incomplete

### Current state

Partial.

Good:

`ExportTransaction` includes:

```text
amount
effectiveAmount
currency
originalCurrency
originalAmount
homeCurrency
baseAmount
baseCurrency
exchangeRateUsed
conversionRateUsed
```

`ExpenseExportMapper` populates these from `Expense`.

Problems:

1. There is no `conversionStatus`:
   ```text
   OK / IDENTITY / MISSING / STALE / RAW_FALLBACK
   ```
2. `baseAmount = 0.0` when no conversion is recorded, which is ambiguous:
   ```text
   zero-value transaction?
   missing conversion?
   same-currency identity?
   failed conversion?
   ```
3. `homeCurrency = expense.baseCurrency`; if `baseCurrency` is blank/stale, export has no settings fallback or warning.
4. Accounting exporters use `expense.amount`, not `effectiveAmount`. Shared expenses can be exported at gross amount.
5. Accounting exporters do not include partial/missing conversion warnings.

### User impact

Multi-currency export looks more complete but still cannot reliably answer whether conversion was valid.

### Fix strategy

Add:

```kotlin
enum class ExportConversionStatus {
    IDENTITY,
    CONVERTED,
    MISSING_RATE,
    STALE_RATE,
    RAW_FALLBACK,
    UNKNOWN
}
```

And include in CSV/JSON/accounting sidecar manifest.

---

## P12-P1-04 — Export snapshot consistency is not real

### Current state

Open.

`ExportDataRepository` comments say the pager provides stable ID-based snapshot consistency, but `DeterministicExpenseExportPager` explicitly says:

```text
keyset pagination is not a true atomic snapshot
concurrent inserts can be partially visible
count is not snapshot-anchored
```

`ExportOptionsViewModel` still computes:

```text
expenseCount = countExpensesBetween(...)
```

then separately streams pages.

### User impact

During export:

```text
JSON rowCount can disagree with rows.length
CSV metadata rowCount can be wrong
new rows can appear mid-export
deleted rows can disappear
roundtrip totals can fail
```

### Fix strategy

Create export snapshot rows:

```text
export_snapshot_rows(operationId, ordinal, expenseId)
```

Export reads only snapshot IDs. Include manifest checksum.

---

## P12-P1-05 — Normal exports plaintext and not privacy-gated

### Current state

Partial, with serious caveats.

Good:

`ExportOptionsViewModel.generateExport()` checks `PrivacyGate`.

For unencrypted export:

```text
PrivacyCapability.RAWBACKUP_EXPORT
```

For encrypted export:

```text
PrivacyCapability.ENCRYPTED_BACKUP
```

It can call:

```kotlin
exportDataRepository.encryptExportFile(exportFile, "default")
```

Problems:

1. `encryptExport` defaults to `false`.
2. Comment says encryption is not wired to UI/settings.
3. Encrypted export uses hardcoded password `"default"`.
4. Plaintext file is written first, then encrypted; if encryption fails, plaintext may remain.
5. The capability is wrong: expense export is not necessarily raw DB backup export.
6. `BackupPrivacyGate` allows `RAWBACKUP_EXPORT` when `encryptedBackupEnabled = false`, which means disabling encrypted backup permits plaintext export.
7. No redaction modes exist for normal expense export.
8. Exported CSV/JSON include merchant and notes by default.

### User impact

Sensitive financial data can be written as plaintext despite privacy expectations.

### Fix strategy

Add dedicated capability:

```kotlin
PrivacyCapability.EXPENSE_EXPORT
PrivacyCapability.EXPENSE_EXPORT_RAW
PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED
```

Use real user passphrase/key flow. Never use `"default"`.

---

## P12-P1-06 — Export silently drops many app fields

### Current state

Partial.

Generic CSV/JSON now include more fields than before:

```text
transactionType
source
paymentMethod
original/home/base currency fields
isBusinessExpense
businessPurpose
```

Still omitted:

```text
businessCategory
businessProject
requiresReceipt
receipt links
transferDirection
transferAccountName
rawNotificationId/source links
ownership fields:
  isSharedExpense
  sharedWithName
  mySharePercentage
  myShareAmount
  isNotMine
  ownerName
location fields:
  latitude
  longitude
  locationSource
  resolvedAddress
  placeId
recurring/budget/group/import source references
conversion quality/warnings
```

Also, the export DAO filters:

```sql
isNotMine = 0
```

which means “not mine” rows are excluded entirely instead of exported with ownership metadata.

### User impact

User thinks they have a full app export, but many fields cannot be restored or audited.

### Fix strategy

Add a schema manifest:

```json
{
  "schemaVersion": 3,
  "includedFields": [...],
  "excludedFields": [
    {"field": "latitude", "reason": "privacy_mode_location_redacted"}
  ],
  "privacyMode": "...",
  "rowCount": 123
}
```

---

## P12-P1-07 — Receipt links are not represented

### Current state

Open.

`ExportTransaction` itself says receipt links are TODO and not exported.

No visible export path joins:

```text
ReceiptExpenseLink
ScannedReceipt
EmailReceiptSource
```

### User impact

After export/import:

```text
proof-of-purchase linkage lost
receipt matching audit lost
warranty/return evidence weakened
receipt-created expenses lose source link
```

### Fix strategy

Add:

```kotlin
data class ReceiptLinkExportRef(
    val receiptId: Long,
    val linkType: String,
    val confidence: Double?,
    val source: String?,
    val receiptSourceType: String?,
    val assetReference: String?
)
```

---

## P12-P1-08 — Business/tax fields not exported

### Current state

Partial.

`ExportTransaction` includes:

```text
isBusinessExpense
businessPurpose
businessCategory
businessProject
requiresReceipt
```

But generic CSV writer only outputs:

```text
IsBusinessExpense
BusinessPurpose
```

JSON writer also only outputs:

```text
isBusinessExpense
businessPurpose
```

So the tracker’s “fixed” status is too optimistic.

### User impact

Tax-relevant data is still partially lost:

```text
business category
project/client
receipt-required flag
```

### Fix strategy

Add all business/tax fields to CSV and JSON. For accounting formats, include either supported tracking columns or a sidecar manifest.

---

## P12-P1-09 — Accountant PDF raw mixed-currency total

### Current state

Mostly fixed.

`AccountantReportPdfExporter` now:

```text
groups by currency
shows per-currency totals
if multiple currencies, writes note that combined total is not shown
```

This fixes the original raw-sum bug.

Remaining caveat:

`generateDeductibleAggregate()` exists and uses `MoneyAggregateBuilder`, but the normal `export()` PDF path does not expose converted deductible/filing-currency totals. If accountant-facing tax totals are required, they still need explicit conversion quality.

### Suggested status

Mostly fixed.

---

## P12-P1-10 — Export can run during restore/restart-required state

### Current state

Partial.

Good:

`ExportOptionsViewModel.generateExport()` checks:

```text
restoreMaintenanceMode.isWritesAllowed()
readBarrier.checkReadAllowed("export_generate")
```

`DatabaseReadBarrier` blocks all restore modes except:

```text
NORMAL
BACKUP_EXPORTING
```

Problems:

1. `loadExpenseCount()` does not call `readBarrier`.
2. `ExportDataRepository` methods do not check read barrier; direct callers can bypass the ViewModel.
3. Exporters themselves have no guard.
4. `restoreMaintenanceMode.isWritesAllowed()` is semantically a write check used for a read/export operation.
5. Allowing export during `BACKUP_EXPORTING` may be okay, but concurrent backup+expense export should be an explicit policy.

### Fix strategy

Move guard to an `ExportCoordinator` or repository boundary:

```kotlin
readBarrier.checkReadAllowed("expense_export")
privacyGate.check(EXPENSE_EXPORT)
```

All export entrypoints should go through it.

---

# 3. New/current issues found

## P12-NEW-01 — Encrypted export uses hardcoded password `"default"`

### Severity

P1 privacy/security.

### Evidence

`ExportOptionsViewModel` calls:

```kotlin
exportDataRepository.encryptExportFile(exportFile, "default")
```

### Impact

If encryption is enabled, every export has a predictable passphrase.

### Fix

Require user passphrase or use app-managed key wrapping. Never use a constant.

---

## P12-NEW-02 — Export privacy capability is wrong

### Severity

P1/P2.

### Evidence

Normal expense export uses:

```text
PrivacyCapability.RAWBACKUP_EXPORT
```

But an expense CSV/JSON export is not the same thing as raw database backup export.

### Impact

Backup settings can unintentionally govern regular transaction export in unsafe ways.

### Fix

Add dedicated export capabilities and policies.

---

## P12-NEW-03 — `BackupPrivacyGate` permits plaintext raw export when encrypted backup is disabled

### Severity

P1 privacy.

### Evidence

`BackupPrivacyGate` allows `RAWBACKUP_EXPORT` if `encryptedBackupEnabled` is false.

### Impact

Turning off encrypted backup becomes permission for raw plaintext export.

### Fix

Use explicit export policy:

```kotlin
enum class ExportPrivacyPolicy {
    DISABLED,
    ENCRYPTED_ONLY,
    REDACTED_ALLOWED,
    RAW_DEBUG_ONLY
}
```

---

## P12-NEW-04 — Accounting validation defeats streaming by loading full dataset

### Severity

P1/P2.

### Evidence

Before streaming accounting export, ViewModel calls:

```kotlin
exportDataRepository.getExpensesBetween(startDate, endDate)
```

which fetches all rows.

### Impact

Large accounting export can OOM even though the row writer streams pages.

### Fix

Use aggregate SQL validation and stream rows only once.

---

## P12-NEW-05 — Direct accounting exporters do not self-validate

### Severity

P2/P1 for future callsites.

### Evidence

`XeroCSVExporter.export(expenses, categories)` simply writes all given rows. Policy validation happens only in the ViewModel path.

### Impact

Future service/API caller can produce invalid mixed-currency/non-purchase accounting export.

### Fix

Route accounting exports only through `AccountingExportCoordinator`, or inject policy into exporters.

---

## P12-NEW-06 — Shared/ownership data is lost or misrepresented

### Severity

P1.

### Evidence

Export query filters `isNotMine = 0`; export schema does not include shared ownership fields. Accounting exporters use `amount`, not `effectiveAmount`.

### Impact

Shared expenses can be exported at gross amount; not-mine rows disappear.

### Fix

Export ownership metadata and decide per-format amount semantics:

```text
grossAmount
effectiveAmount
ownershipMode
myShareAmount
```

---

## P12-NEW-07 — Export file has no operation ledger/checksum

### Severity

P2/P1 for accounting/audit.

### Evidence

Export success is UI state only:

```text
exportFilePath
exportPreview
exportSuccess
```

No `ExportRun`, manifest checksum, or durable run record was visible.

### Impact

Cannot prove what was exported, with what filters/privacy mode, or whether the file was truncated.

### Fix

Add `ExportRun` table and sidecar manifest.

---

## P12-NEW-08 — Date/time policy still uses `ZoneId.systemDefault()`

### Severity

P2.

### Evidence

Export and accounting/PDF date formatting use device local timezone.

### Impact

Same DB exported on two devices/timezones can produce different date strings near midnight.

### Fix

Machine-readable JSON should include epoch and fixed UTC date; human accounting exports should declare timezone in manifest.

---

## P12-NEW-09 — Redacted DB export scope still too narrow

### Severity

P2/P1 privacy.

### Evidence

`ExportAnonymizer` only strips:

```text
scanned_receipts.rawOcrText
raw_notifications title/text/bigText/subText/extrasJson/parseResult
```

TODO lists missing sensitive tables.

### Impact

Redacted exports can still contain:

```text
email receipt source details
AI artifacts/prompts
chat messages
locations/addresses
business purpose/project
bank tokens/source metadata
diagnostics
```

### Fix

Registry-based redaction targets.

---

## P12-NEW-10 — “CsvExportImportRoundtripGoldenTest” is misleading

### Severity

P2.

### Evidence

The test named “ExportImportRoundtrip” only tests sanitizer behavior and numeric string parsing.

### Impact

Tracker can incorrectly mark import/roundtrip as partially implemented.

### Fix

Rename to:

```text
CsvCellSanitizerGoldenTest
```

Add real:

```text
JsonExportImportRoundtripGoldenTest
CsvExportImportRoundtripGoldenTest
```

---

# 4. Actual bugs vs architectural work

## Actual user-affecting bugs

Prioritize:

1. **No real import pipeline / no roundtrip guarantee.**
2. **Default plaintext export with weak/incorrect privacy policy.**
3. **Hardcoded encryption password if export encryption is enabled.**
4. **Snapshot count/row mismatch under concurrent writes.**
5. **Receipt links not exported.**
6. **Shared/ownership fields lost or accounting amount overstated.**
7. **Business category/project/requiresReceipt omitted from writers.**
8. **Accounting validation loads full dataset and can OOM.**
9. **Repository/exporter direct paths bypass privacy/read barriers.**
10. **No export run ledger/checksum for audit.**

## Architectural / hardening work

Important but lower immediate urgency:

1. ExportCoordinator single entrypoint.
2. ImportCoordinator with preview/result/error model.
3. Export snapshot table.
4. Export manifest schema v3.
5. Privacy modes/redaction registry.
6. Typed conversion quality in export rows.
7. Accounting sidecar manifest.
8. Timezone policy.
9. Static guard against direct exporter calls.
10. Rename misleading sanitizer test.

---

# 5. Recommended implementation plan

## PR 1 — Dedicated export coordinator + privacy/read barrier

### Goal

No export can bypass privacy/restore/read policy.

### Files

- new `ExportCoordinator.kt`
- `ExportOptionsViewModel.kt`
- `ExportDataRepository.kt`
- `PrivacyCapability.kt`
- `BackupPrivacyGate.kt`

### Tasks

1. Add dedicated capabilities:
   ```text
   EXPENSE_EXPORT
   EXPENSE_EXPORT_RAW
   EXPENSE_EXPORT_ENCRYPTED
   ```
2. Move privacy/read barrier checks into coordinator.
3. Remove hardcoded `"default"` password.
4. Add real encryption option/passphrase.
5. Define redaction modes.

### Acceptance tests

```text
export_denied_when_expense_export_privacy_denied
restart_required_blocks_export_at_coordinator
encrypted_export_requires_non_default_secret
plaintext_export_rejected_when_policy_encrypted_only
```

---

## PR 2 — True export snapshot

### Goal

Row count, rows, checksum, and totals refer to one stable snapshot.

### Files

- new `ExportSnapshotRow.kt`
- new `ExportSnapshotDao.kt`
- `ExportDataRepository.kt`
- `DeterministicExpenseExportPager.kt`
- migration

### Tasks

1. Create snapshot operation ID.
2. Insert ordered expense IDs at export start.
3. Count from snapshot table.
4. Stream joins through snapshot rows.
5. Cleanup on success/failure.
6. Add checksum.

### Acceptance tests

```text
insert_during_export_not_included
delete_during_export_does_not_change_rowCount
json_rowCount_matches_rows_length
same_snapshot_checksum_stable
```

---

## PR 3 — Real app import pipeline

### Goal

CSV/JSON export can import into fresh DB through lifecycle.

### Files

- new `ImportCoordinator.kt`
- new `JsonExpenseImporter.kt`
- new `CsvExpenseImporter.kt`
- new `ImportOptionsScreen/ViewModel`
- `TransactionLifecycleCoordinator.kt`
- `ReceiptLinkService.kt`

### Tasks

1. Parse schema v2/v3 CSV and JSON.
2. Preview rows/errors.
3. Resolve categories.
4. Deduplicate.
5. Route creates through transaction lifecycle.
6. Recreate receipt links when present.
7. Return row-level result.

### Acceptance tests

```text
json_export_then_import_fresh_db_preserves_count
csv_export_then_import_fresh_db_preserves_totals
import_routes_rows_through_transaction_lifecycle
import_duplicate_rows_are_skipped_with_summary
import_reports_unsupported_columns
```

---

## PR 4 — Canonical export schema v3

### Goal

No silent data loss.

### Files

- `ExportTransaction.kt`
- `ExpenseExportMapper.kt`
- `ExportOptionsViewModel.kt`
- new manifest model

### Tasks

1. Add:
   - ownership/shared fields,
   - transfer fields,
   - location fields gated by privacy,
   - business category/project/requiresReceipt,
   - receipt links,
   - source links,
   - conversion status/quality.
2. Add `includedFields`/`excludedFields` manifest.
3. Add sidecar manifest for accounting formats.

### Acceptance tests

```text
json_export_includes_ownership_fields
csv_export_includes_business_category_project_requiresReceipt
json_export_includes_transfer_metadata
manifest_reports_excluded_location_fields
```

---

## PR 5 — Receipt link export/import

### Goal

Receipt proof survives export/import.

### Files

- receipt DAOs
- `ExpenseExportMapper.kt`
- import coordinator
- `ReceiptLinkService.kt`

### Tasks

1. Export `ReceiptLinkExportRef`.
2. Export receipt metadata, not raw OCR unless policy allows.
3. Optional asset references.
4. Import recreates links via `ReceiptLinkService`.
5. Missing asset becomes warning.

### Acceptance tests

```text
json_export_includes_receipt_links
import_recreates_receipt_expense_links
redacted_export_has_link_metadata_no_raw_ocr
missing_receipt_asset_reported
```

---

## PR 6 — Accounting validation + amount semantics

### Goal

Accounting files are valid, memory-safe, and ownership-correct.

### Files

- `AccountingExportPolicy.kt`
- `ExpenseDao.kt`
- `ExportCoordinator.kt`
- `AccountingExporters.kt`

### Tasks

1. Add SQL aggregate validation.
2. Enforce validation in coordinator.
3. Make direct exporter APIs internal or validation-aware.
4. Decide accounting amount:
   - gross amount,
   - effective amount,
   - both with explicit columns/sidecar.
5. Include conversion warnings.

### Acceptance tests

```text
xero_export_rejects_mixed_currency_without_loading_all_rows
freshbooks_rejects_non_purchase_from_sql_validation
shared_expense_exports_effective_amount_or_declares_gross
direct_exporter_call_cannot_bypass_policy
```

---

## PR 7 — Export operation ledger

### Goal

Exports are auditable and diagnosable.

### Files

- new `ExportRun.kt`
- new `ExportRunDao.kt`
- `ExportCoordinator.kt`
- migration

### Tasks

1. Record:
   - STARTED,
   - SNAPSHOT_CREATED,
   - FILE_WRITTEN,
   - ENCRYPTED,
   - COMPLETED,
   - FAILED,
   - CANCELLED.
2. Store:
   - filters,
   - privacy mode,
   - schema version,
   - row count,
   - checksum,
   - warning count.
3. Surface in UI/debug.

### Acceptance tests

```text
successful_export_writes_completed_run
failed_export_deletes_temp_and_writes_failed_run
cancelled_export_writes_cancelled_run
checksum_matches_file_bytes
```

---

## PR 8 — Redaction registry for DB/export privacy

### Goal

Redacted exports do not leak sensitive data in secondary tables.

### Files

- `ExportAnonymizer.kt`
- new redaction target registry
- privacy settings

### Tasks

1. Add targets for:
   - email receipt sources,
   - AI artifacts,
   - AI chat messages,
   - locations,
   - business notes/purpose,
   - bank tokens/source metadata,
   - diagnostics.
2. Include redaction summary in manifest.
3. Respect export privacy mode.

### Acceptance tests

```text
redacted_export_removes_email_subject_sender
redacted_export_removes_ai_prompts
redacted_export_removes_location_addresses
redacted_export_manifest_lists_redacted_tables
```

---

## PR 9 — Timezone policy

### Goal

Exports are deterministic across devices.

### Files

- `ExportOptionsViewModel.kt`
- `AccountingExporters.kt`
- `AccountantReportPdfExporter.kt`
- manifest model

### Tasks

1. JSON: include epoch timestamp and UTC date.
2. Human/accounting: use configured export timezone.
3. Manifest includes timezone.
4. Tests run under multiple zones.

### Acceptance tests

```text
json_export_same_timestamp_in_UTC_and_Athens
accounting_export_manifest_records_timezone
date_near_midnight_has_deterministic_machine_field
```

---

# 6. Suggested tracker updates

Update Pipeline 12 tracker:

| ID | Suggested status |
|---|---|
| P12-P0-01 | TODO / open |
| P12-P1-01 | Mostly fixed |
| P12-P1-02 | Partial |
| P12-P1-03 | Partial |
| P12-P1-04 | TODO / open |
| P12-P1-05 | Partial / privacy-risky |
| P12-P1-06 | Partial |
| P12-P1-07 | TODO / open |
| P12-P1-08 | Partial |
| P12-P1-09 | Mostly fixed |
| P12-P1-10 | Partial |

Add new items:

| New ID | Severity | Title |
|---|---:|---|
| P12-NEW-01 | P1 | Encrypted export uses hardcoded password `"default"` |
| P12-NEW-02 | P1/P2 | Export privacy capability is wrong |
| P12-NEW-03 | P1 | `BackupPrivacyGate` permits plaintext raw export when encrypted backup is disabled |
| P12-NEW-04 | P1/P2 | Accounting validation defeats streaming by loading full dataset |
| P12-NEW-05 | P2/P1 | Direct accounting exporters do not self-validate |
| P12-NEW-06 | P1 | Shared/ownership data is lost or misrepresented |
| P12-NEW-07 | P2/P1 | Export file has no operation ledger/checksum |
| P12-NEW-08 | P2 | Date/time policy still uses `ZoneId.systemDefault()` |
| P12-NEW-09 | P2/P1 | Redacted DB export scope still too narrow |
| P12-NEW-10 | P2 | `CsvExportImportRoundtripGoldenTest` is misleading |

---

# 7. Golden tests for Pipeline 12

Add or verify:

```text
generic_csv_escapes_comma_quote_newline
xero_csv_escapes_comma_quote_newline
freshbooks_csv_escapes_comma_quote_newline
all_csv_exports_neutralize_formula_injection
accounting_export_rejects_mixed_currency_from_sql_validation
accounting_export_rejects_non_purchase_without_loading_all_rows
direct_accounting_exporter_cannot_bypass_policy
export_snapshot_rowCount_matches_rows_under_concurrent_insert
export_snapshot_stable_under_delete_during_export
plaintext_export_denied_when_policy_encrypted_only
encrypted_export_requires_user_secret_not_default
encrypted_export_deletes_plaintext_on_success
encrypted_export_failure_cleans_or_reports_plaintext
json_export_includes_ownership_transfer_business_receipt_fields
csv_export_includes_business_category_project_requiresReceipt
json_export_includes_receipt_link_refs
json_export_then_import_fresh_db_preserves_count_and_totals
csv_export_then_import_fresh_db_preserves_count_and_totals
import_routes_created_rows_through_transaction_lifecycle
import_recreates_receipt_expense_links
import_reports_unsupported_columns
pdf_multi_currency_does_not_raw_sum_combined_total
export_run_completed_has_checksum
redacted_export_removes_email_ai_location_bank_sensitive_fields
json_export_timezone_deterministic_across_devices
```

---

# 8. AI implementation checklist

Before coding, run:

```bash
grep -R "generateExport" app/src/main/java
grep -R "encryptExportFile" app/src/main/java
grep -R "\"default\"" app/src/main/java/com/yourname/expensetracker/ui/screens/export
grep -R "RAWBACKUP_EXPORT" app/src/main/java
grep -R "CsvCellSanitizer" app/src/main/java
grep -R "escapeCsv" app/src/main/java
grep -R "toExportTransaction" app/src/main/java
grep -R "ExportTransaction(" app/src/main/java
grep -R "getExpensesBetweenForExportKeyset" app/src/main/java
grep -R "countExpensesBetween" app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt
grep -R "receiptLinks" app/src/main/java
grep -R "isSharedExpense" app/src/main/java/com/yourname/expensetracker/domain/export app/src/main/java/com/yourname/expensetracker/ui/screens/export
grep -R "businessCategory" app/src/main/java/com/yourname/expensetracker/domain/export app/src/main/java/com/yourname/expensetracker/ui/screens/export
grep -R "ZoneId.systemDefault" app/src/main/java/com/yourname/expensetracker/domain/export app/src/main/java/com/yourname/expensetracker/ui/screens/export
grep -R "ImportCoordinator" app/src/main/java
grep -R "CsvExpenseImporter" app/src/main/java
grep -R "JsonExpenseImporter" app/src/main/java
grep -R "ExportRun" app/src/main/java
```

Allowed export entrypoint should eventually be:

```text
ExportCoordinator only
```

Definition of done:

```text
- CSV/JSON export can import into a fresh DB.
- Import uses TransactionLifecycleCoordinator, not direct ExpenseDao insert.
- Export snapshot is stable under concurrent writes.
- Expense export has dedicated privacy capability/policy.
- No hardcoded export encryption password exists.
- Export schema includes ownership, transfer, business/tax, receipt link, source-link, and conversion-quality metadata.
- Accounting exports validate through SQL/global policy and are memory-safe.
- Accountant PDF does not raw-sum mixed currencies.
- Export is blocked during restore/restart-required at repository/coordinator boundary.
- Export/import operations have ledger rows and checksums.
- Redacted exports cover all sensitive tables.
```

---

# 9. Agent-ready priority order

Do this order:

1. **Fix export privacy/encryption policy** — remove `"default"` password and raw-backup capability misuse.
2. **Create `ExportCoordinator` single entrypoint with read/privacy barriers.**
3. **Implement true export snapshot table.**
4. **Build real CSV/JSON import pipeline and rename misleading sanitizer test.**
5. **Expand export schema v3 for ownership/business/receipt/source/conversion quality.**
6. **Receipt link export/import.**
7. **SQL/global accounting validation and direct exporter guard.**
8. **Export run ledger/checksum/manifest.**
9. **Redaction registry for sensitive DB/export fields.**
10. **Timezone policy and deterministic golden tests.**