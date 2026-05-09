# Pipeline 12 Debug Report — Import / Export / Accounting

Baseline: `71fbbf9aed221a7446f99967b49b6e9ebeb51946`  
Mode: static GitHub/code review, not local Gradle/device execution.

## Verdict

Pipeline 12 is **not clean/stable yet**.

The export side has improved:

- generic CSV export is streamed;
- JSON export is versioned;
- export uses keyset pagination;
- temporary-file writing is used;
- generic CSV has proper quoting/formula neutralization;
- accounting exporters exist for Xero, QuickBooks, FreshBooks;
- accounting exports have some single-currency/purchase-only validation;
- export preview is generated from the same stream;
- export file encryption helper exists.

But the full pipeline is still **orange/red** because it is mostly **export-only** and not a true roundtrip/import pipeline.

Main blockers:

1. no app-level CSV/JSON import into a fresh DB;
2. no roundtrip contract;
3. accounting CSV escaping is broken for commas/quotes;
4. accounting validation is per-page, not global;
5. exported multi-currency fields are incomplete/wrongly populated;
6. many app fields are silently dropped;
7. normal exports are plaintext and not privacy-gated;
8. export snapshot consistency is not real despite comments claiming it;
9. receipt links/business/tax/source metadata are not exported;
10. accountant PDF still has raw mixed-currency combined total caveat.

Best current label: **usable beta export utility, not production-grade import/export/accounting pipeline**.

---

# Severity scale

- **P0 / Critical:** required pipeline capability missing or impossible to verify.
- **P1 / High:** common export/import data loss, wrong accounting output, privacy risk, or broken financial semantics.
- **P2 / Medium:** edge correctness, diagnostics, UX, consistency, maintainability.
- **P3 / Low:** cleanup/polish.

---

# Pipeline checklist status

| Checklist item | Status |
|---|---|
| CSV escaping safe | Partial. Generic CSV is mostly safe; Xero/FreshBooks CSV are not fully escaped. |
| Special characters roundtrip | Partial. Generic CSV quotes commas/quotes/newlines; accounting CSV does not. |
| Multi-currency fields exported | Partial/weak. Xero/FreshBooks include columns but mapper does not populate real home/base conversion data. Generic CSV lacks them. |
| Tax/business fields exported | Mostly missing. Business fields exist on `Expense`, but normal exports omit them. |
| Receipt links represented | Missing. No receipt link IDs/status/link type in CSV/JSON/accounting exports. |
| Private raw text redacted | Partial. Raw OCR/notification DB export redaction exists; normal expense export has no privacy mode and exports notes/merchant raw. |
| Import into fresh DB works | Missing for CSV/JSON/app export. Backup restore exists but that is Pipeline 7, not app import/export. |
| Totals match after roundtrip | Not provable because no import/roundtrip pipeline exists. |
| Unsupported fields reported | Missing. Export silently drops many fields; no unsupported-field manifest/report. |

---

# Positive findings to preserve

## PF-01 — Generic CSV escaping is mostly correct

`ExportOptionsViewModel.escapeCsv()`:

- quotes fields containing comma, quote, newline, carriage return;
- doubles embedded quotes;
- neutralizes formula-leading `=`, `+`, `-`, `@`.

This is the correct base for generic CSV.

## PF-02 — JSON export has a schema wrapper

JSON export writes:

```text
schemaVersion
exportType
generatedAt
dateRange
rowCount
rows
```

This is a good start for a future roundtrip contract.

## PF-03 — Export streams pages instead of loading everything

`ExportOptionsViewModel.streamExpensesToWriter()` uses `ExportDataRepository.getExpensesPage()`, which delegates to `DeterministicExpenseExportPager.fetchPage()`.

This reduces memory risk for large exports.

## PF-04 — Temporary-file writing exists

The ViewModel writes to:

```text
.tmp_{finalName}
```

then renames/copies to final file. This is better than writing directly to the final export path.

## PF-05 — Accounting export policy exists

`AccountingExportPolicy` validates:

```text
single currency
purchase transactions only
```

This is the right concept for QuickBooks/Xero/FreshBooks-style exports.

## PF-06 — Export mapper guards NaN/Infinity

`ExpenseExportMapper` replaces non-finite amounts with `0.0` before serialization.

That prevents obvious corrupted CSV/JSON values.

## PF-07 — Raw database backup anonymizer exists

`ExportAnonymizer` strips:

```text
scanned_receipts.rawOcrText
raw_notifications title/text/bigText/subText/extrasJson/parseResult
```

from temporary DB export copies.

This should be extended rather than removed.

---

# Issue P0-01 — No app-level CSV/JSON import or roundtrip pipeline

## Severity

P0 / Critical

## Evidence

The codebase has export UI and database backup/restore, but no discovered app-level importer for the app’s own exported CSV/JSON rows.

The export UI supports:

```text
CSV
JSON
Xero
QuickBooks
FreshBooks
```

but there is no corresponding:

```text
ImportOptionsScreen
ExpenseImportRepository
CsvExpenseImporter
JsonExpenseImporter
ImportPreview
ImportResult
```

for these app export formats.

## Impact

Pipeline 12 cannot satisfy:

```text
import into fresh DB works
totals match after roundtrip
unsupported fields reported
```

Backup/restore is not a substitute for CSV/JSON app import. It restores a whole DB; it does not validate a portable transaction export/import contract.

## Fixing strategy

Create a first-class app import lifecycle.

## Implementation plan

1. Define canonical app export schema:

```kotlin
data class ExpenseExportRowV2(
    val schemaVersion: Int,
    val exportedAt: String,
    val sourceExpenseId: Long?,
    val date: Long,
    val transactionType: String,
    val amount: Double,
    val currency: String,
    val effectiveAmount: Double,
    val baseAmount: Double?,
    val baseCurrency: String?,
    val exchangeRateUsed: Double?,
    val merchant: String,
    val merchantKey: String?,
    val categoryName: String?,
    val notes: String?,
    val source: String?,
    val paymentMethod: String?,
    val isBusinessExpense: Boolean,
    val businessPurpose: String?,
    val businessCategory: String?,
    val businessProject: String?,
    val requiresReceipt: Boolean,
    val receiptLinks: List<ReceiptLinkExportRef>,
    val unsupportedFields: Map<String, String> = emptyMap()
)
```

2. Add import components:

```text
ExpenseImportParser
ExpenseImportPreviewBuilder
ExpenseImportCoordinator
ExpenseImportResult
ExpenseImportError
```

3. Import should route accepted rows through:

```text
TransactionLifecycleCoordinator.createExpense()
```

not direct `ExpenseDao.insert`.

4. Add category resolver:

```text
category name exact
case-insensitive match
create category if policy allows
or mark row as needs mapping
```

5. Add duplicate/idempotency policy:

```text
source export id
dedupe key
merchant/date/amount/currency/type
```

6. Add tests:

```text
export_json_then_import_fresh_db_preserves_totals
export_csv_then_import_fresh_db_preserves_totals
import_preview_reports_unsupported_columns
import_rejects_invalid_currency
import_duplicate_rows_are_skipped_or_linked
```

---

# Issue P1-02 — Xero/FreshBooks CSV exporters do not do real CSV escaping

## Severity

P1 / High

## Evidence

`XeroCSVExporter` and `FreshBooksExporter` use `CsvCellSanitizer.sanitize()`.

That sanitizer:

```text
strips tab/newline/carriage return
neutralizes formula-leading = + - @
```

but it does **not** quote fields containing:

```text
comma
double quote
delimiter-like characters
```

Then exporters concatenate fields with commas manually.

## Impact

A merchant/category/note like:

```text
"Coffee, Snacks"
ACME "North"
```

can break column alignment in Xero/FreshBooks CSV.

This violates:

```text
CSV escaping safe
special characters roundtrip
```

## Fixing strategy

Use one RFC-4180-safe CSV writer for all CSV-like exports.

## Implementation plan

1. Replace `CsvCellSanitizer` with:

```kotlin
object CsvCellWriter {
    fun encode(field: String, formulaSafe: Boolean = true): String
}
```

Rules:

```text
- neutralize formula-leading chars after leading whitespace check
- quote if field contains comma, quote, CR, LF
- double embedded quotes
- preserve user text where possible
```

2. Apply to:

```text
generic CSV
Xero CSV
FreshBooks CSV
any future tax/accounting CSV
```

3. Keep IIF tab escaping separate.

4. Tests:

```text
xero_merchant_with_comma_stays_one_column
freshbooks_category_with_quote_escapes_correctly
formula_injection_neutralized_in_all_csv_exporters
newlines_do_not_break_row_count
```

---

# Issue P1-03 — Accounting validation is per-page, not global

## Severity

P1 / High

## Evidence

`ExportOptionsViewModel.generateExport()` validates the first page for accounting formats.

`streamExpensesToWriter()` then validates pages 2+ individually.

But this only proves:

```text
each page is internally single-currency and purchase-only
```

It does not prove:

```text
the whole export is single-currency
```

Example:

```text
page 1 = EUR purchases
page 2 = USD purchases
```

Each page passes, but the final Xero/FreshBooks/QuickBooks file is mixed-currency.

## Impact

Accounting exports can violate their own policy and produce files that accounting software may reject or import incorrectly.

## Fixing strategy

Validate the entire dataset before streaming rows.

## Implementation plan

1. Add repository-level aggregate validation:

```kotlin
data class ExportDatasetValidation(
    val rowCount: Int,
    val distinctCurrencies: Set<String>,
    val transactionTypes: Set<TransactionType>,
    val unsupportedRowCount: Int
)
```

2. Query with SQL:

```sql
SELECT DISTINCT currency FROM expenses WHERE date >= ? AND date < ?
SELECT DISTINCT transactionType FROM expenses WHERE date >= ? AND date < ?
```

3. For accounting formats, fail before file creation if:

```text
distinctCurrencies.size > 1
transactionTypes contains non-PURCHASE
```

4. Tests:

```text
xero_export_fails_when_page1_EUR_page2_USD
quickbooks_export_fails_when_any_transfer_exists
freshbooks_export_validation_runs_before_streaming
```

---

# Issue P1-04 — Multi-currency export fields are incomplete or incorrectly populated

## Severity

P1 / High

## Evidence

`ExportTransaction` supports:

```text
originalCurrency
homeCurrency
conversionRateUsed
originalAmount
```

But `ExpenseExportMapper.map()` sets:

```text
currency = expense.currency
originalCurrency = expense.currency
originalAmount = expense.amount
```

and does not populate:

```text
homeCurrency from CurrencySettingsRepository
baseAmount
baseCurrency
exchangeRateUsed
```

Generic CSV exports only:

```text
Date, Merchant, Amount, Currency, Category, Notes, ID
```

JSON exports similarly omit conversion fields.

## Impact

The exported file cannot answer:

```text
what was the original transaction amount?
what is the home/reporting amount?
what conversion rate was used?
was conversion partial/missing/stale?
```

For multi-currency users, exported accounting data is not audit-ready.

## Fixing strategy

Make export rows currency-complete.

## Implementation plan

1. Replace `ExpenseExportMapper.map(expense)` with an injected mapper:

```kotlin
class ExpenseExportMapper @Inject constructor(
    private val currencySettingsRepository: CurrencySettingsRepository
)
```

2. Map:

```text
originalAmount = expense.amount
originalCurrency = expense.currency
effectiveOriginalAmount = expense.effectiveAmount
homeAmount = expense.baseAmount
homeCurrency = expense.baseCurrency
conversionRateUsed = expense.exchangeRateUsed
conversionStatus = OK / IDENTITY / MISSING / STALE
```

3. Add fields to generic CSV and JSON.

4. Tests:

```text
generic_csv_exports_original_and_home_currency_fields
json_exports_exchangeRateUsed_and_baseAmount
usd_expense_home_eur_exports_both_amounts
identity_currency_exports_rate_1_or_identity_status
```

---

# Issue P1-05 — Export snapshot consistency is not real

## Severity

P1 / High

## Evidence

`ExportDataRepository` comments say the pager anchors on a fixed set of IDs.

But `DeterministicExpenseExportPager` explicitly says:

```text
rows inserted with higher cursor can be seen
rows inserted behind cursor can be missed
count is not snapshot anchored
```

`ExportOptionsViewModel` also computes `rowCount` separately before streaming.

## Impact

During export, concurrent expense edits/inserts/deletes can cause:

```text
rowCount in JSON does not match actual rows
totals differ from exported rows
new rows appear mid-export
rows deleted mid-export disappear
```

This breaks auditability and roundtrip testing.

## Fixing strategy

Create an actual export snapshot.

## Implementation plan

1. Add temporary/persistent export snapshot table:

```sql
export_snapshot_rows(
  operationId TEXT,
  ordinal INTEGER,
  expenseId INTEGER,
  PRIMARY KEY(operationId, ordinal)
)
```

2. At export start, inside DB transaction:

```sql
INSERT INTO export_snapshot_rows(operationId, ordinal, expenseId)
SELECT :operationId, ROW_NUMBER()..., id
FROM expenses
WHERE date >= :start AND date < :end
ORDER BY date ASC, id ASC
```

3. Stream by joining snapshot IDs:

```sql
SELECT e.*
FROM export_snapshot_rows s
JOIN expenses e ON e.id = s.expenseId
WHERE s.operationId = :operationId
ORDER BY s.ordinal
LIMIT :limit OFFSET/keyset
```

4. Write manifest:

```text
operationId
snapshotRowCount
schemaVersion
filters
checksum
```

5. Cleanup snapshot rows after success/failure.

6. Tests:

```text
insert_during_export_not_included
delete_during_export_does_not_change_snapshot_policy
json_rowCount_matches_rows
export_checksum_stable_for_same_snapshot
```

---

# Issue P1-06 — Normal exports are plaintext and not privacy-gated

## Severity

P1 / High

## Evidence

`ExportDataRepository.createExportFile()` writes export files under:

```text
files/exports/expenses_timestamp.csv/json/iif
```

`encryptExportFile()` exists, but `ExportOptionsViewModel.generateExport()` does not call it.

The normal export flow does not check:

```text
PrivacyGate
BackupPrivacyGate
raw export policy
redaction policy
```

## Impact

Expense exports can contain:

```text
merchant names
notes
business purpose/project
category names
transaction dates
amounts
```

and are written in plaintext.

Even if app-private storage is safer than public Downloads, these files can be exposed by device backup, debug extraction, compromised device, or later sharing.

## Fixing strategy

Add explicit export privacy policy.

## Implementation plan

1. Define:

```kotlin
enum class ExpenseExportPrivacyMode {
    FULL_PLAINTEXT,
    REDACT_NOTES_AND_MERCHANTS,
    METADATA_ONLY,
    ENCRYPTED
}
```

2. Before generating export:

```kotlin
privacyGate.check(PrivacyCapability.RAWBACKUP_EXPORT or new EXPENSE_EXPORT)
```

3. If encrypted mode:

```kotlin
exportDataRepository.encryptExportFile(file, password)
```

4. If redacted mode:
   - hash/redact merchant;
   - remove notes;
   - remove business purpose/project;
   - optionally bucket dates/months.

5. Tests:

```text
export_denied_when_privacy_gate_denies
encrypted_export_deletes_plaintext
redacted_export_contains_no_notes
metadata_only_export_contains_no_merchant_names
```

---

# Issue P1-07 — Export silently drops many app fields

## Severity

P1 / High

## Evidence

`Expense` contains many important fields:

```text
transactionType
source
paymentMethod
createdAt
rawNotificationId
transferDirection
transferAccountName
ownership fields
location fields
business fields
split fields
baseAmount/baseCurrency/exchangeRateUsed
```

Generic CSV and JSON export only include:

```text
id
date/timestamp
merchant
amount
currency
category
notes
```

Accounting exports include even fewer app-specific fields.

## Impact

The export looks complete but silently loses data needed for:

```text
fresh DB import
auditing
tax reports
receipt matching
ownership/shared expenses
transfers/deposits
business deductions
currency conversion audit
```

## Fixing strategy

Make export format explicit about supported and unsupported fields.

## Implementation plan

1. Add export manifest:

```json
{
  "schemaVersion": 2,
  "exportType": "expenses",
  "includedFields": [...],
  "excludedFields": [
    {"field": "rawNotificationId", "reason": "privacy"},
    {"field": "latitude", "reason": "location privacy"}
  ],
  "privacyMode": "FULL_PLAINTEXT",
  "rowCount": 123
}
```

2. For app JSON, include all non-raw app fields by default.

3. For CSV, include a wider canonical column set.

4. For accounting formats, keep accounting-specific fields but write a sidecar manifest.

5. Tests:

```text
json_export_includes_transactionType_source_paymentMethod
json_export_includes_business_fields
csv_export_includes_base_currency_fields
export_manifest_reports_excluded_location_fields
```

---

# Issue P1-08 — Receipt links are not represented

## Severity

P1 / High

## Evidence

Pipeline 12 checklist requires receipt links represented.

No inspected export row includes:

```text
receiptId
receipt link type
match status
receipt image path/reference
receipt source
```

`ReceiptExpenseLink` is not used by the export flow.

## Impact

After export/import, the app cannot reconstruct:

```text
which receipt proves this expense
whether the receipt was auto-matched/manual
warranty/return-window evidence
receipt audit trail
```

## Fixing strategy

Export receipt link metadata separately from receipt image binaries.

## Implementation plan

1. Add row fields:

```text
receiptIds
primaryReceiptId
receiptMatchStatus
receiptLinkTypes
```

2. For JSON:

```kotlin
data class ReceiptLinkExportRef(
    val receiptId: Long,
    val linkType: String,
    val confidence: Double?,
    val source: String?
)
```

3. If export privacy mode allows assets, include:
   - receipt metadata;
   - optional asset reference;
   - never raw OCR unless policy allows.

4. Import should:
   - create receipt rows if present;
   - link using `ReceiptLinkService`;
   - report missing receipt assets as warnings.

5. Tests:

```text
json_export_includes_receipt_links
import_recreates_receipt_expense_links
redacted_export_includes_link_metadata_but_no_raw_ocr
missing_receipt_asset_reported_not_silently_lost
```

---

# Issue P1-09 — Business/tax fields are not exported in normal transaction exports

## Severity

P1 / High

## Evidence

`Expense` has business/tax-relevant fields:

```text
isBusinessExpense
businessPurpose
businessCategory
businessProject
requiresReceipt
```

`TaxEstimator` can compute tax summaries, and `AccountantReportPdfExporter` can produce a PDF report.

But normal CSV/JSON/accounting transaction exports do not include these fields.

## Impact

Accounting/tax workflows lose:

```text
deductibility
business purpose
project/client attribution
receipt-required flag
business category
```

This weakens accountant handoff and makes import roundtrip lossy.

## Fixing strategy

Add business/tax columns to app export and optional accounting sidecar.

## Implementation plan

1. Add columns:

```text
IsBusinessExpense
BusinessPurpose
BusinessCategory
BusinessProject
RequiresReceipt
TaxCategory
FilingCurrency
```

2. For Xero/FreshBooks/QuickBooks:
   - include business category as tracking class/tag if supported;
   - otherwise write a sidecar CSV/JSON.

3. Tests:

```text
csv_export_preserves_business_fields
json_export_preserves_business_fields
accounting_export_sidecar_contains_business_fields
import_restores_business_fields
```

---

# Issue P1-10 — Accountant PDF still has raw mixed-currency combined total

## Severity

P1 / High for accountant-facing reports

## Evidence

`AccountantReportPdfExporter.export()` groups expenses by currency and displays per-currency totals, which is good.

But if multiple currencies exist, it also writes:

```text
Combined Total (base)
```

by raw-summing all `effectiveAmount` values and formatting it using the first currency key.

The code comment calls this intentional, but mathematically it is not a converted total.

## Impact

An accountant/user can misread:

```text
100 EUR + 100 USD = 200 EUR
```

even though no conversion happened.

The label “base” is not strong enough for financial reporting.

## Fixing strategy

Remove raw mixed-currency combined total or replace it with `MoneyAggregate`.

## Implementation plan

1. For multi-currency PDF:
   - show per-currency totals only, or
   - show converted home-currency total using `MoneyAggregate`.

2. If conversion is partial:
   - display warning;
   - list failed currency buckets.

3. Tests:

```text
pdf_multi_currency_does_not_raw_sum_combined_total
pdf_moneyaggregate_total_has_home_currency
pdf_shows_partial_conversion_warning
```

---

# Issue P1-11 — Export can run during restore/restart-required state

## Severity

P1 / High

## Evidence

`ExportOptionsViewModel` and `ExportDataRepository` do not visibly check `RestoreMaintenanceMode`.

If the user starts an export while a restore is preparing, swapping, verifying, or restart-required, the export may read from a stale or changing Room instance.

## Impact

Possible outcomes:

```text
corrupt/truncated export
export from old DB while restore succeeds
export from restored DB with stale DAO references
plaintext export during maintenance state
```

## Fixing strategy

Use a read/export barrier.

## Implementation plan

1. Add:

```kotlin
DatabaseReadBarrier.checkReadsAllowed("expense_export")
```

2. Block export when:

```text
RESTORE_PREPARING
RESTORE_SWAPPING
RESTORE_VERIFYING
RESTORE_COMPLETE_RESTART_REQUIRED
```

3. Optionally allow export during normal mode only.

4. Tests:

```text
restore_mode_blocks_expense_export
restart_required_blocks_expense_export
export_after_restart_reads_restored_db
```

---

# Issue P2-12 — Date/time policy is inconsistent and not deterministic

## Severity

P2 / Medium

## Evidence

Export code uses `ZoneId.systemDefault()` for:

```text
generic CSV date
JSON dateRange dates
Xero date
FreshBooks date
QuickBooks IIF date
PDF report dates
```

Some comments mention UTC export policy, but implementation uses local system time zone.

## Impact

The same DB exported on two devices/time zones can produce different date strings for transactions near midnight.

That breaks deterministic golden tests and roundtrip comparisons.

## Fixing strategy

Define export time-zone policy.

## Implementation plan

1. Choose one:
   - UTC for portable machine-readable export;
   - user-configured local zone for human/accounting export.

2. Store it in manifest:

```json
"timeZone": "UTC"
```

3. For JSON, always include raw epoch timestamp.

4. Tests:

```text
json_export_same_in_UTC_and_Athens_for_timestamp_field
csv_export_uses_configured_export_timezone
date_range_manifest_records_timezone
```

---

# Issue P2-13 — Export has no durable operation ledger/checksum

## Severity

P2 / Medium

## Evidence

Export success is only UI state:

```text
exportFilePath
exportPreview
exportSuccess
```

No durable `export_events` / `export_runs` table exists.

No checksum is written for exported files.

## Impact

Debugging and audit are weak:

```text
when was export generated?
which filters?
how many rows?
which privacy mode?
did file checksum match?
was it cancelled?
```

## Fixing strategy

Add export operation metadata.

## Implementation plan

1. Add entity:

```kotlin
ExportRun(
    id,
    format,
    startDate,
    endDate,
    rowCount,
    privacyMode,
    fileName,
    checksumSha256,
    status,
    error,
    createdAt,
    finishedAt
)
```

2. Write:

```text
STARTED
SNAPSHOT_CREATED
FILE_WRITTEN
ENCRYPTED
COMPLETED
FAILED
CANCELLED
```

3. Tests:

```text
successful_export_writes_completed_run
cancelled_export_writes_cancelled_run
failed_export_deletes_temp_and_writes_failed_run
checksum_matches_file_bytes
```

---

# Issue P2-14 — Redacted DB export does not cover all sensitive tables

## Severity

P2 / Medium

## Evidence

`ExportAnonymizer` strips raw OCR and raw notification fields.

It does not cover all sensitive artifacts discussed in Pipeline 8, such as:

```text
email receipt source subject/sender/body-derived fields
AI artifacts/prompts
chat messages
debug diagnostics
location/address fields
business purpose/project notes
bank token/source metadata
```

## Impact

Redacted backup/export can still expose sensitive non-raw fields.

## Fixing strategy

Move redaction to a registry-based anonymizer.

## Implementation plan

1. Add:

```kotlin
interface ExportRedactionTarget {
    val tableName: String
    fun redact(db: SQLiteDatabase): RedactionResult
}
```

2. Register targets:

```text
RawNotificationRedactor
ReceiptOcrRedactor
EmailReceiptSourceRedactor
AiArtifactRedactor
LocationRedactor
DebugDiagnosticsRedactor
BankTokenRedactor
BusinessNotesRedactor optional
```

3. Include redaction summary in manifest.

4. Tests:

```text
redacted_export_removes_email_subject
redacted_export_removes_ai_prompts
redacted_export_removes_location_addresses_when_policy_requires
redacted_export_manifest_lists_redacted_tables
```

---

# Recommended fixing order

## PR 1 — CSV writer hardening

Files:

```text
CsvCellSanitizer.kt
AccountingExporters.kt
ExportOptionsViewModel.kt
```

Fix:

```text
- one RFC-4180 CSV encoder
- formula neutralization
- commas/quotes/newlines preserved safely
```

## PR 2 — Global accounting dataset validation

Files:

```text
ExportDataRepository.kt
ExpenseDao.kt
AccountingExportPolicy.kt
ExportOptionsViewModel.kt
```

Fix:

```text
- global distinct currency/type checks before streaming
- fail before file creation for invalid accounting exports
```

## PR 3 — Canonical export schema v2

Files:

```text
ExpenseExportMapper.kt
ExportTransaction.kt
ExportOptionsViewModel.kt
new ExpenseExportRowV2.kt
```

Fix:

```text
- transactionType/source/paymentMethod
- original/effective/base amounts
- business/tax fields
- receipt link refs
- schema manifest
```

## PR 4 — Real export snapshot

Files:

```text
DeterministicExpenseExportPager.kt
ExportDataRepository.kt
ExpenseDao.kt
new ExportSnapshotDao.kt/entity
```

Fix:

```text
- stable operation snapshot IDs
- rowCount matches rows
- concurrent inserts do not affect active export
```

## PR 5 — Privacy/encryption for normal exports

Files:

```text
ExportOptionsViewModel.kt
ExportDataRepository.kt
PrivacyGate
PrivacySettings
```

Fix:

```text
- export privacy mode
- plaintext/export gate
- encrypted export option actually used
```

## PR 6 — App import pipeline

Files:

```text
new ImportOptionsScreen.kt
new ExpenseImportCoordinator.kt
new CsvExpenseImporter.kt
new JsonExpenseImporter.kt
TransactionLifecycleCoordinator.kt
ReceiptLinkService.kt
```

Fix:

```text
- preview
- validation
- import summary
- row-level errors
- lifecycle create
- receipt link restore
```

## PR 7 — Accountant PDF mixed-currency fix

Files:

```text
AccountantReportPdfExporter.kt
MoneyAggregateBuilder.kt
```

Fix:

```text
- remove raw mixed-currency combined total
- show MoneyAggregate total + partial warnings
```

## PR 8 — Export diagnostics

Files:

```text
new ExportRun.kt
new ExportRunDao.kt
ExportOptionsViewModel.kt
ExportDataRepository.kt
```

Fix:

```text
- durable run ledger
- checksums
- failure/cancel events
```

---

# Golden tests to add

```text
generic_csv_escapes_comma_quote_newline
xero_csv_escapes_comma_quote_newline
freshbooks_csv_escapes_comma_quote_newline
all_csv_exports_neutralize_formula_injection
accounting_export_rejects_mixed_currency_across_pages
accounting_export_rejects_non_purchase_anywhere_in_dataset
json_export_includes_transactionType_source_paymentMethod
json_export_includes_original_and_base_money_fields
csv_export_includes_business_tax_fields
json_export_includes_receipt_link_refs
export_snapshot_rowCount_matches_rows_under_concurrent_insert
export_denied_during_restore_mode
plaintext_export_denied_when_privacy_policy_disallows
encrypted_export_deletes_plaintext_file
pdf_multi_currency_does_not_raw_sum_combined_total
json_export_then_import_fresh_db_preserves_count_and_totals
csv_export_then_import_fresh_db_preserves_count_and_totals
import_reports_unsupported_columns
import_routes_created_rows_through_transaction_lifecycle
import_duplicate_rows_are_skipped_with_summary
```

---

# AI implementation checklist

Before coding, run:

```bash
grep -R "escapeCsv" app/src/main/java
grep -R "CsvCellSanitizer" app/src/main/java
grep -R "toExportTransaction" app/src/main/java
grep -R "ExportTransaction(" app/src/main/java
grep -R "createExportFile" app/src/main/java
grep -R "encryptExportFile" app/src/main/java
grep -R "getExpensesPage" app/src/main/java
grep -R "Combined Total (base)" app/src/main/java
grep -R "ImportOptions" app/src/main/java
grep -R "CsvImporter" app/src/main/java
```

Definition of done:

```text
- All CSV exporters use one safe CSV writer.
- Accounting exports validate the whole dataset, not each page independently.
- Export schema includes original/effective/base money fields.
- Export schema includes transaction type, source, payment method, business/tax fields, and receipt link refs.
- Export snapshot is stable under concurrent writes.
- Normal exports respect privacy gate and can be encrypted.
- Export is blocked during restore/restart-required states.
- Accountant PDF never raw-sums mixed currencies.
- App CSV/JSON import exists with preview, row errors, unsupported-field report, and lifecycle creation.
- JSON/CSV export → fresh DB import golden tests pass.
```

---

# Source files inspected

- Commit baseline:  
  https://github.com/panospao7/Cost-agregator/commit/71fbbf9aed221a7446f99967b49b6e9ebeb51946

- `ExportOptionsViewModel.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt

- `ExportDataRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt

- `DeterministicExpenseExportPager.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/DeterministicExpenseExportPager.kt

- `AccountingExporters.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt

- `CsvCellSanitizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/export/CsvCellSanitizer.kt

- `ExpenseExportMapper.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt

- `ExportTransaction.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt

- `AccountingExportPolicy.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExportPolicy.kt

- `AccountantReportPdfExporter.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt

- `Expense.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt

- `ExportAnonymizer.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/privacy/ExportAnonymizer.kt

- `DatabaseBackupRepositoryImpl.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/71fbbf9aed221a7446f99967b49b6e9ebeb51946/app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt