# Pipeline 12 Debugging Report — Import / Export / Accounting Roundtrip

Target commit: `53c915f09cbc92137b5b84d5839bdbf1cd321c16`  
Review type: static GitHub code review, not local execution.

## 1. Executive summary

Pipeline 12 is intended to be:

```text
ExportOptionsScreen / accounting export
→ ExportOptionsViewModel or AccountingExportRepository
→ ExportDataRepository / DeterministicExpenseExportPager
→ ExpenseDao deterministic range query
→ CSV / JSON / Xero / QuickBooks / FreshBooks / PDF output

CSV import
→ CsvExpenseImporter
→ parse CSV
→ category lookup/create
→ TransactionLifecycleCoordinator
→ Expense / TransactionEvent
→ dashboard / analytics / budget
```

The codebase has useful improvements:

- CSV escaping and formula-injection mitigation exist.
- Accounting exports validate single-currency + purchase-only datasets.
- Deterministic export paging exists.
- `AccountingExportRepository` writes through temp file + rename.
- CSV import now routes rows through `TransactionLifecycleCoordinator`.
- Export mapper uses `effectiveAmount` for accounting exports.
- PDF report groups by currency instead of raw mixed-currency totals.

But Pipeline 12 has major correctness gaps.

Highest-risk findings:

1. **The app’s own generic CSV export cannot be imported by `CsvExpenseImporter`.**
2. **Export and import schemas are not versioned against each other.**
3. **There are two export paths with different behavior: `ExportOptionsViewModel` and `AccountingExportRepository`.**
4. **`ExportOptionsViewModel` writes final files directly, without atomic temp-file rename.**
5. **Generic CSV/JSON exports lose important fields: transaction type, ownership/shared fields, original/home conversion fields, payment method, source, receipt links, tax/business fields.**
6. **CSV import cannot parse the app’s exported currency column, ISO currency codes, comma decimals, thousands separators, deposits, transfers, refunds, or ownership fields.**
7. **Export paging is deterministic but offset-based and not snapshot-isolated, so concurrent changes can skip/duplicate rows.**
8. **Several tests are stale relative to the current exporter headers and coordinator-based importer.**
9. **Accounting exporters materialize full strings and can still OOM on large datasets.**
10. **Privacy/redaction behavior for export is inconsistent or absent outside backup redaction.**

Main recommendation:

> Define a versioned canonical app export schema and make import consume that same schema. Treat accounting exports as one-way external reports, not roundtrip backups.

---

# 2. Intended architecture contract

A safe export/import architecture should have three distinct modes:

## A. App roundtrip export

Purpose:

```text
app → file → same app/fresh DB
```

Must preserve:

```text
expense identity/source
transaction type
amount/currency
effective amount
ownership/shared fields
category
merchant/merchantKey
date/timestamp
notes
receipt links
recurring links
tax/business fields
conversion metadata
schema version
unsupported-field warnings
```

## B. Accounting export

Purpose:

```text
app → external accounting system
```

Should be intentionally lossy but explicit:

```text
purchase-only
single-currency or converted-home-currency
account/category mapping
vendor/merchant
reference
tax/business fields where supported
```

## C. Privacy/anonymized export

Purpose:

```text
shareable report
```

Should redact:

```text
raw notification text
raw OCR/email text
sensitive notes
merchant/person/location fields depending policy
receipt image references
AI artifacts
```

Current code mixes these concepts.

---

# 3. Actual code path summary

## 3.1 ExportOptionsViewModel path

`ExportOptionsViewModel.generateExport()`:

```text
load categories
create export file in cache/exports
fetch all expenses via ExportDataRepository.getExpensesBetweenForExport()
if accounting format, validate AccountingExportPolicy
write selected format:
  csv
  json
  xero
  quickbooks
  freshbooks
set exportPreview + exportFilePath
```

Important details:

- UI formats: CSV, JSON, Xero, QuickBooks, FreshBooks.
- No PDF option in this ViewModel, even though `AccountingExportRepository` supports PDF.
- Generic CSV/JSON are written directly in the ViewModel.
- Accounting formats call exporter `writeHeader()` / `writeExpense()` row-by-row.
- Output file is created directly; no temp-file + atomic rename.
- Preview headers for accounting formats are hardcoded separately from actual exporter headers.

Sources:

- `ExportOptionsViewModel.kt`
- `ExportDataRepository.kt`
- `AccountingExporters.kt`

---

## 3.2 AccountingExportRepository path

`AccountingExportRepository.exportExpenses()`:

```text
validate date range
fetch deterministic paged expenses
map accounting transactions
validate accounting policy if needed
load categories
create timestamped filename
write to temp file
rename temp → final file
return FileProvider URI + file path
```

Supported formats:

```text
QUICKBOOKS_IIF
XERO_CSV
FRESHBOOKS_CSV
ACCOUNTANT_REPORT_PDF
```

Good:

- date range validation,
- temp file + rename,
- FileProvider URI,
- accounting policy,
- PDF report support.

Risk:

- separate from UI export path,
- accounting exporters still often build full output strings before writing,
- PDF has its own semantics,
- no privacy gate/redaction.

Source:

- `AccountingExportRepository.kt`

---

## 3.3 Export data retrieval

`ExportDataRepository.getExpensesBetweenForExport()` calls:

```text
DeterministicExpenseExportPager.fetchAllBetween()
```

The pager repeatedly calls:

```text
ExpenseRepository.getExpensesBetweenPagedForDeterministicExport()
→ ExpenseDao.getExpensesBetweenForExport()
```

DAO ordering:

```sql
ORDER BY date ASC, id ASC, merchant COLLATE NOCASE ASC
LIMIT :limit OFFSET :offset
```

Good:

- deterministic order,
- exhaustive paging,
- avoids old fixed row limit.

Risk:

- offset paging,
- no snapshot isolation,
- all rows collected into a `List`,
- export count can diverge from actual rows if data changes between count and export.

Sources:

- `DeterministicExpenseExportPager.kt`
- `ExpenseRepository.kt`
- `ExpenseDao.kt`

---

## 3.4 CSV import path

`CsvExpenseImporter.importFromContent()` expects old format:

```csv
date,amount,merchant,category,description
2024-01-15,25.50,Starbucks,Coffee,Morning coffee
```

It does:

```text
split rows
skip header if first line contains date/amount
parse columns:
  0 date
  1 amount
  2 merchant
  3 category
  4 description
detect currency from amount symbol only
fallback to home currency
get or create category via CategoryDao
CreateExpenseRequest(...)
TransactionLifecycleCoordinator.createExpense()
return per-row result
```

Good:

- routes through lifecycle coordinator,
- handles quoted commas,
- detects €/$/£/¥ symbols,
- reports per-row result.

Risk:

- does not import current app export CSV,
- no schema/version support,
- no transaction type support,
- no ISO currency column support,
- weak amount parsing,
- no idempotency from exported ID/source,
- direct deprecated `CategoryDao.insert()`.

Source:

- `CsvExpenseImporter.kt`

---

# 4. Major findings

## Finding P0-1 — The app’s CSV export cannot be imported by its CSV importer

Current generic CSV export header:

```csv
Date,Merchant,Amount,Currency,Category,Notes,ID
```

Current importer expects:

```csv
date,amount,merchant,category,description
```

So if the app exports:

```csv
2026-05-01,Amazon,29.99,EUR,Shopping,Order,123
```

the importer reads:

```text
date = 2026-05-01
amountStr = Amazon
merchant = 29.99
categoryName = EUR
description = Shopping
```

Result:

```text
Invalid amount: Amazon
```

This means the app’s own CSV export/import roundtrip is broken.

### Fix

Create a versioned canonical app CSV format:

```csv
schemaVersion,id,timestamp,date,merchant,amount,currency,transactionType,category,categoryId,notes,paymentMethod,source,originalAmount,originalCurrency,homeAmount,homeCurrency,conversionRate,isSharedExpense,myShareAmount,mySharePercentage,isNotMine,receiptIds
```

Then update importer to detect schema:

```text
v1 old CSV: date,amount,merchant,category,description
v2 app CSV: schemaVersion,...
accounting CSV: import not supported unless explicitly mapped
```

Priority: highest.

---

## Finding P0-2 — There is no true roundtrip export schema

Generic JSON export has:

```text
schemaVersion
exportType
generatedAt
dateRange
rowCount
rows: id/date/timestamp/merchant/amount/currency/category/notes
```

This is better than CSV, but still loses:

```text
transactionType
paymentMethod
categoryId
merchantKey
source
dedupe/idempotency
ownership/shared fields
transferDirection/account
original vs effective amount
home currency conversion
tax/business fields
receipt links
recurring links
group links
privacy/redaction flags
```

So even JSON cannot restore the same user state.

### Fix

Define:

```text
AppExportSchema v2
```

Sections:

```json
{
  "schemaVersion": 2,
  "appDatabaseVersion": 113,
  "exportMode": "APP_ROUNDTRIP",
  "generatedAt": "...",
  "homeCurrency": "EUR",
  "dateRange": {...},
  "expenses": [...],
  "categories": [...],
  "receiptLinks": [...],
  "recurringLinks": [...],
  "groups": [...],
  "unsupported": [...]
}
```

Roundtrip import should report:

```text
created
updated
duplicates
unsupported fields
warnings
```

Priority: highest.

---

## Finding P0-3 — Two export paths can diverge

There are two export owners:

```text
ExportOptionsViewModel
AccountingExportRepository
```

They differ:

| Concern | ExportOptionsViewModel | AccountingExportRepository |
|---|---|---|
| generic CSV/JSON | yes | no |
| PDF | no | yes |
| temp-file atomic write | no | yes |
| FileProvider URI | no | yes |
| date range validation | weak | yes |
| accounting policy | yes | yes |
| preview support | yes | no |
| direct file path | yes | yes |
| output schema | separate code | separate code |

This creates inconsistent behavior and future bugs.

### Example

`AccountingExportRepository` fixed atomic temp-file write.  
`ExportOptionsViewModel` still writes directly to final file.

### Fix

Create one use case:

```kotlin
ExportExpensesUseCase
```

It should own:

```text
date validation
query snapshot/paging
format selection
privacy/redaction policy
atomic temp-file output
preview collection
FileProvider URI
result metadata
```

UI should call the use case, not write export files directly.

Priority: highest.

---

## Finding P0-4 — ExportOptionsViewModel writes non-atomically

`ExportOptionsViewModel.generateExport()` does:

```kotlin
val exportFile = exportDataRepository.createExportFile(...)
exportFile.writer().use { writer -> ... }
```

If the app is killed mid-write, the final export file can be partial/corrupt.

`AccountingExportRepository` already has the safer pattern:

```text
write temp file
rename temp → final
```

### Fix

Move `ExportOptionsViewModel` to the repository/use-case atomic writer.

Priority: highest.

---

## Finding P0-5 — Import tests are stale relative to production code

`CsvExpenseImporter` constructor accepts:

```text
CategoryDao
TransactionLifecycleCoordinator
CurrencySettingsRepository
```

The importer creates expenses via:

```text
coordinator.createExpense(request)
```

But `CsvExpenseImporterTest` still:

- declares `ExpenseDao`,
- stubs `expenseDao.insert(any())`,
- verifies `expenseDao.insert(any())`,
- captures `Expense` inserted through `ExpenseDao`.

That is no longer the production path.

These tests either fail or do not protect the current importer behavior.

### Fix

Rewrite tests to verify:

```text
coordinator.createExpense(CreateExpenseRequest(...))
```

and add DB-backed tests proving:

```text
expense row inserted
TransactionEvent.CREATED inserted
duplicate row skipped
category created/used correctly
```

Priority: highest.

Sources:

- `CsvExpenseImporter.kt`
- `CsvExpenseImporterTest.kt`

---

## Finding P1-1 — Accounting/export tests appear stale against current headers

Current `XeroCSVExporter.writeHeader()` writes:

```csv
Date,Description,Amount,Currency,Account,Reference,OriginalCurrency,HomeCurrency,ConversionRate,OriginalAmount
```

But `CsvEscapingTest` expects old header shape:

```csv
Date,Description,Amount,Account,Reference
```

Current `FreshBooksExporter.writeHeader()` writes:

```csv
date,description,amount,currency,category,vendor,originalCurrency,homeCurrency,conversionRate,originalAmount
```

But tests expect:

```csv
date,description,amount,category,vendor
```

`AccountingExportRepositoryTest` also checks a header-only FreshBooks file against the old 5-column header.

### Why this matters

The tests may fail, or if ignored/relaxed, they are no longer proving the real export schema.

### Fix

Update tests to the current headers, or better: centralize each exporter’s header contract in one place and test against that source of truth.

Priority: high.

---

## Finding P1-2 — Generic CSV/JSON export uses raw `expense.amount`, not `effectiveAmount`

Generic CSV:

```kotlin
Amount = expense.amount
```

Generic JSON:

```kotlin
"amount": expense.amount
```

Accounting exports use:

```text
ExpenseExportMapper.map()
→ amount = expense.effectiveAmount
→ originalAmount = expense.amount
```

So exports disagree.

For shared expenses:

```text
gross amount = 100
my share = 50
```

Generic CSV/JSON exports `100`; accounting exports `50`.

For roundtrip import, the ownership/share fields are missing, so importing the generic CSV creates a full 100 expense and corrupts user totals.

### Fix

Canonical app export should include both:

```text
grossAmount
effectiveAmount
ownership model
myShareAmount
mySharePercentage
isNotMine
isSharedExpense
```

Generic “human report” CSV should clearly label:

```text
GrossAmount
EffectiveAmount
```

Priority: high.

---

## Finding P1-3 — Importer cannot parse current currency/export fields

Importer currency detection:

```text
symbol in amount string:
  € → EUR
  $ → USD
  £ → GBP
  ¥ → JPY
else home currency
```

It ignores a CSV `Currency` column because the expected old schema has no currency column.

It also cannot parse:

```text
"1,234.56"
"1.234,56"
"12,50"
"USD 12.50"
"12.50 USD"
negative amounts
parentheses accounting amounts
```

### Fix

Use a robust amount parser:

```text
amount column
currency column
locale
decimal separator
thousands separator
accounting negative
```

If currency is missing:

```text
fallback home currency + warning
```

Priority: high.

---

## Finding P1-4 — Importer only imports PURCHASE transactions

Every row becomes:

```kotlin
transactionType = TransactionType.PURCHASE
```

So roundtrip loses:

```text
DEPOSIT
TRANSFER
WITHDRAWAL
UNKNOWN
refunds
income
```

For transfers, required fields:

```text
transferDirection
transferAccountName
```

are not supported.

### Fix

Versioned import schema must include:

```text
transactionType
transferDirection
transferAccountName
paymentMethod
source
```

Low-confidence or unsupported types should go to review, not be imported as purchases.

Priority: high.

---

## Finding P1-5 — Importer does not use exported ID/source as idempotency key

Importer builds `CreateExpenseRequest` without:

```text
idempotencyKey
externalFingerprint
STRICT_EXTERNAL_ID
```

So re-import relies on fuzzy lifecycle duplicate detection.

That is unsafe for roundtrip.

### Fix

For canonical app export:

```text
sourceExportId = original app + original expense id
exportBatchId
rowHash
```

Importer:

```kotlin
deduplicationMode = STRICT_EXTERNAL_ID
idempotencyKey = "app-export:$exportBatchId:$sourceExpenseId"
externalFingerprint = rowHash
```

For old CSV without ID, keep fuzzy dedupe.

Priority: high.

---

## Finding P1-6 — Category import bypasses CategoryRepository normalization

`CsvExpenseImporter.getOrCreateCategory()` calls:

```text
CategoryDao.getByName()
CategoryDao.insert(Category(...))
```

But `CategoryDao.insert()` is deprecated because it bypasses category name normalization.

Also, if `insert()` returns `-1` due to unique constraint race, importer can return `-1` as category ID.

Blank category name can also throw due `Category` invariant.

### Fix

Inject `CategoryRepository` and call:

```text
normalizeAndInsert()
getOrCreateNormalized()
```

Handle blank category:

```text
Uncategorized
```

Handle insert conflict:

```text
re-query by normalized name
```

Priority: high.

---

## Finding P1-7 — Export paging is deterministic but not snapshot-stable

Pager uses:

```text
LIMIT/OFFSET
```

If rows are inserted/deleted/updated during export:

```text
page 1 reads rows 1..2000
row inserted near top
page 2 offset 2000 now starts at old row 2000 again or skips one
```

So an export can skip or duplicate rows.

Also, count shown in UI can differ from actual exported rows.

### Fix

Best:

```text
read inside one DB transaction/snapshot
```

or keyset pagination:

```sql
WHERE (date > :lastDate)
   OR (date = :lastDate AND id > :lastId)
ORDER BY date ASC, id ASC
LIMIT :limit
```

Also freeze an export manifest:

```text
rowCount
firstRowKey
lastRowKey
queryHash
```

Priority: high.

---

## Finding P1-8 — Accounting exporters still materialize full strings

`QuickBooksIIFExporter.export()`, `XeroCSVExporter.export()`, and `FreshBooksExporter.export()` return a full `String`.

`AccountingExportRepository` writes:

```kotlin
writer.write(xeroExporter.export(exportTransactions, categories))
```

So for large exports, memory usage is:

```text
List<Expense>
List<ExportTransaction>
full CSV String
file buffer
```

`ExportOptionsViewModel` is more streaming for accounting row output, but still holds the full expense list.

### Fix

Make exporter interface streaming-only:

```kotlin
interface AccountingExporter {
    fun writeHeader(writer: Appendable)
    fun writeRow(writer: Appendable, tx: ExportTransaction, categories: Map<Long,String>)
}
```

Remove full-string `export()` from production path or keep only for tests/small previews.

Priority: medium-high.

---

## Finding P1-9 — PDF “Combined Total (base)” is misleading

`AccountantReportPdfExporter` groups by currency, which is good.

But if multiple currencies exist, it writes:

```text
Combined Total (base)
```

computed by raw-summing `effectiveAmount` across currencies and using the first currency key.

Even labeled “base”, this can be misunderstood as a real converted total.

Example:

```text
EUR 100 + JPY 10000 = “EUR 10100”
```

### Fix

Do not show raw combined total.

Options:

```text
show per-currency only
```

or:

```text
show converted home-currency total using MoneyAggregate and display partial warning
```

Priority: medium-high.

---

## Finding P1-10 — Export privacy/redaction is not centralized

Backup has `ExportAnonymizer`, but normal exports do not visibly check:

```text
PrivacyGate
redaction settings
raw notes policy
location privacy
AI artifact privacy
receipt image/link privacy
```

Generic CSV/JSON exports raw:

```text
merchant
notes
category
IDs
```

This is user-initiated, but the app should still have explicit modes:

```text
full private export
redacted export
accountant export
```

### Fix

Add `ExportPrivacyPolicy`:

```text
includeNotes
includeMerchant
includeLocation
includeReceiptLinks
includeRawText
redactMerchant
hashIds
```

Audit export decisions through privacy audit.

Priority: medium-high.

---

## Finding P2-1 — ExportOptionsViewModel preview headers are wrong

For Xero:

```kotlin
val header = "Date,Description,Amount,Account,Reference\n"
xeroExporter.writeHeader(writer)
preview.append(header)
```

But actual exporter header includes currency/original/home/conversion fields.

QuickBooks and FreshBooks preview headers also omit current columns.

So preview can show a different schema from the actual file.

### Fix

Capture header from exporter itself:

```kotlin
val header = buildString { xeroExporter.writeHeader(this) }
writer.append(header)
preview.append(header)
```

Priority: medium.

---

## Finding P2-2 — Export UI does not expose PDF/accountant repository path

`AccountingExportRepository` supports:

```text
ACCOUNTANT_REPORT_PDF
```

`ExportOptionsViewModel` formats do not include PDF.

So either:

- PDF is used elsewhere,
- or it is an orphaned feature.

### Fix

Either expose PDF in export UI or document repository-only usage.

Priority: medium.

---

## Finding P2-3 — File retention/cleanup unclear

Exports are written to:

```text
cacheDir/exports
```

There is no visible cleanup policy.

If users export often, cache can grow until Android clears it. That may be acceptable, but the UI should communicate file lifetime or provide cleanup.

### Fix

Add:

```text
ExportCleanupWorker or cache retention policy
```

Priority: low-medium.

---

# 5. Debugging checklist for Pipeline 12

## Export mode selection

Check:

- [ ] app roundtrip export,
- [ ] accounting export,
- [ ] PDF/report export,
- [ ] redacted export,
- [ ] selected mode shown clearly to user.

## Export schema

Check:

- [ ] schema version,
- [ ] app DB version,
- [ ] export mode,
- [ ] generatedAt,
- [ ] home currency,
- [ ] date range,
- [ ] row count,
- [ ] unsupported fields,
- [ ] stable row order.

## Expense fields

Check exported/importable support for:

- [ ] id/source id,
- [ ] timestamp and local date,
- [ ] merchant,
- [ ] merchantKey,
- [ ] gross amount,
- [ ] effective amount,
- [ ] currency,
- [ ] original currency,
- [ ] home currency,
- [ ] conversion rate,
- [ ] transaction type,
- [ ] payment method,
- [ ] category ID/name,
- [ ] notes,
- [ ] transfer direction/account,
- [ ] ownership/shared fields,
- [ ] tax/business fields,
- [ ] receipt links,
- [ ] group links,
- [ ] recurring links.

## CSV safety

Check:

- [ ] comma/quote/newline escaping,
- [ ] formula injection mitigation,
- [ ] leading whitespace before formula chars,
- [ ] UTF-8 output,
- [ ] CRLF vs LF policy,
- [ ] huge field behavior,
- [ ] field count stable.

## Import parsing

Check:

- [ ] old CSV schema,
- [ ] new app CSV schema,
- [ ] currency column,
- [ ] decimal comma,
- [ ] thousands separators,
- [ ] negative/accounting amounts,
- [ ] blank categories,
- [ ] duplicate categories,
- [ ] invalid rows produce per-row error,
- [ ] row numbers in errors,
- [ ] progress total excludes blank lines.

## Roundtrip

Check:

- [ ] export → import fresh DB,
- [ ] dashboard totals match,
- [ ] analytics category totals match,
- [ ] budget spend matches,
- [ ] shared expense effective amount preserved,
- [ ] multi-currency fields preserved,
- [ ] transaction types preserved,
- [ ] receipt links preserved or reported unsupported,
- [ ] duplicates skipped on second import.

---

# 6. Recommended fix plan

## PR 1 — Create canonical export/import schema

Add:

```text
AppExpenseExportSchema v2
```

Support:

```text
CSV v2
JSON v2
```

Importer must detect:

```text
legacy CSV
app CSV v2
app JSON v2
unsupported accounting CSV
```

Acceptance:

```text
generic app CSV export imports successfully into a fresh DB.
```

Priority: P0.

---

## PR 2 — Unify export execution path

Create:

```kotlin
ExportExpensesUseCase
```

Responsibilities:

```text
date validation
schema selection
privacy policy
snapshot/paging
atomic temp file write
preview generation
FileProvider URI
export result
```

`ExportOptionsViewModel` and `AccountingExportRepository` should call this shared path or one should replace the other.

Acceptance:

```text
all export formats use atomic temp-file write and one row/query contract.
```

Priority: P0.

---

## PR 3 — Fix CSV importer for current app exports

Add column mapping by header name:

```text
Date / date
Amount / amount / GrossAmount / EffectiveAmount
Currency
Merchant
Category
Notes
TransactionType
```

Do not rely on fixed old column order when a header exists.

Acceptance:

```text
Date,Merchant,Amount,Currency,Category,Notes,ID imports correctly.
```

Priority: P0.

---

## PR 4 — Fix stale tests

Rewrite:

```text
CsvExpenseImporterTest
CsvEscapingTest
AccountingExportRepositoryTest
ExportOptionsViewModelTest
```

against current headers and coordinator path.

Acceptance:

```text
tests verify TransactionLifecycleCoordinator calls or DB-backed lifecycle rows, not ExpenseDao.insert.
```

Priority: P0/P1.

---

## PR 5 — Add roundtrip scenario

Create:

```text
csv_json_export_import_roundtrip_contract
```

Acceptance:

```text
seed DB → export → import into fresh DB → dashboard/analytics/budget match.
```

Priority: P1.

---

## PR 6 — Snapshot-safe paging

Move from offset paging to:

```text
keyset pagination
or transaction snapshot
```

Acceptance:

```text
concurrent insert during export cannot skip/duplicate rows.
```

Priority: P1.

---

## PR 7 — Streaming exporter interface

Refactor accounting exporters to stream rows.

Acceptance:

```text
large exports do not materialize full output string.
```

Priority: P1/P2.

---

## PR 8 — Export privacy policy

Add modes:

```text
Full private export
Redacted export
Accountant export
App roundtrip export
```

Acceptance:

```text
redacted export strips notes/location/raw identifiers according to policy.
```

Priority: P1/P2.

---

# 7. Tests to add

## `AppCsvExportImportRoundtripTest`

Seed:

```text
purchase 50 EUR
deposit 1000 EUR
transfer 200 EUR outgoing
shared expense gross 100, my share 40
USD purchase with conversion metadata
notes with commas/newlines/formula prefix
```

Run:

```text
export app CSV v2
import into fresh DB
```

Assert:

```text
transaction count matches
transaction types preserved
dashboard spend matches
income/deposit preserved
shared effective amount preserved
notes escaped/restored
currency preserved
second import creates no duplicates
```

---

## `AppJsonExportImportRoundtripTest`

Same as CSV but checks richer JSON schema.

Assert:

```text
schemaVersion = 2
dbVersion = 113
unsupported fields listed
receipt/group/recurring links preserved or explicitly reported unsupported
```

---

## `LegacyCsvImportContractTest`

Input old format:

```csv
date,amount,merchant,category,description
2024-01-15,25.50,Starbucks,Coffee,Morning coffee
```

Assert:

```text
still imports as PURCHASE
home currency fallback warning if no symbol/currency column
```

---

## `CurrentCsvImportRegressionTest`

Input current generic export:

```csv
Date,Merchant,Amount,Currency,Category,Notes,ID
2026-05-01,Amazon,29.99,EUR,Shopping,Order,123
```

Assert:

```text
amount = 29.99
merchant = Amazon
currency = EUR
category = Shopping
source idempotency key uses ID
```

---

## `CsvFormulaInjectionContractTest`

Cases:

```text
=cmd
+SUM(...)
-10+cmd
@HYPERLINK(...)
leading whitespace + formula char
quoted formula field
```

Assert:

```text
exported CSV cell is neutralized
field count remains stable
```

OWASP identifies CSV/formula injection as a risk when untrusted input is embedded in CSV cells, so this stays important.

---

## `ExportSnapshotStabilityTest`

Simulate:

```text
page 1 read
concurrent insert/delete/update
page 2 read
```

Assert chosen contract:

```text
snapshot export stable
```

or:

```text
export detects concurrent modification and retries/fails
```

---

## `AccountingExportPolicyContractTest`

Cases:

```text
single currency purchase dataset → allowed
mixed currency purchase dataset → rejected
deposit/transfer dataset → rejected
empty dataset → allowed for header-only accounting export
```

---

## `ExportPrivacyRedactionContractTest`

Seed:

```text
merchant
notes with sensitive text
location
receipt link
raw source IDs
```

Run redacted export.

Assert:

```text
sensitive fields redacted
safe fields preserved
audit event written
```

---

# 8. Suggested canonical scenario

## `csv_accounting_export_import_roundtrip`

Seed:

```text
home currency EUR

categories:
  Groceries
  Salary
  Transfer
  Dining

expenses:
  purchase:
    merchant = SKLAVENITIS
    amount = 45.50 EUR
    category = Groceries
    notes = "formula =HYPERLINK(...)"

  deposit:
    merchant = Employer
    amount = 1250 EUR
    transactionType = DEPOSIT

  transfer:
    merchant = Savings
    amount = 200 EUR
    transactionType = TRANSFER
    transferDirection = OUTGOING
    transferAccountName = Savings Account

  shared purchase:
    merchant = Restaurant
    gross amount = 100 EUR
    myShareAmount = 40 EUR

  foreign purchase:
    merchant = Amazon
    amount = 10 USD
    home amount = 9 EUR
    conversionRate = 0.90
```

Run:

```text
1. export app JSON v2
2. export app CSV v2
3. import each into fresh DB
4. run dashboard/analytics/budget
5. export Xero/FreshBooks/QuickBooks accounting report
```

Expected:

```text
app JSON/CSV imports preserve transaction types
shared expense effective amount preserved
foreign currency metadata preserved
dashboard monthly spend matches original
income/deposit not counted as spending
transfer semantics preserved
formula notes neutralized in CSV
second import creates zero new duplicates
accounting export rejects mixed-currency unless user filters/converts
accounting export rejects non-PURCHASE rows
```

This should become the Pipeline 12 fed-DB acceptance test.

---

# 9. Most likely real instability sources

Ranked:

1. **Current CSV export schema does not match CSV importer schema.**
2. **No versioned roundtrip schema.**
3. **Split export ownership between ViewModel and repository.**
4. **Non-atomic file write in ViewModel export path.**
5. **Importer tests are stale and still expect ExpenseDao insert.**
6. **Generic export loses transaction type / shared / currency conversion metadata.**
7. **Importer cannot parse ISO currency columns or non-simple amounts.**
8. **Offset paging can skip/duplicate rows under concurrent changes.**
9. **Accounting/PDF exports still have raw/misleading multi-currency semantics.**
10. **Export privacy/redaction not centralized.**

---

# 10. Final recommendation

Stabilize Pipeline 12 in this order:

```text
1. Define canonical AppExportSchema v2.
2. Make app CSV/JSON export and import use the same schema.
3. Unify export execution behind one use case/repository.
4. Make all export writes atomic.
5. Rewrite importer tests to current coordinator architecture.
6. Add DB-backed export/import roundtrip scenario.
7. Fix amount/currency/type/category import parsing.
8. Move from offset paging to snapshot/keyset export.
9. Add export privacy/redaction modes.
10. Keep accounting exports one-way and explicitly policy-validated.
```

Guiding rule:

> Accounting exports are reports. App exports are roundtrip data. Do not use the same loose CSV shape for both.

Second guiding rule:

> A user should be able to export from the app and import into a fresh database without losing spending totals, transaction types, ownership shares, currency metadata, or duplicate safety.

---

# 11. Verification & Fix Log (2026-05-06)

## Finding P0-1 — App's own CSV export cannot be imported by CsvExpenseImporter
**STATUS: CONFIRMED — NOT FIXED (requires versioned roundtrip schema)**

## Finding P0-2 — Export and import schemas not versioned
**STATUS: CONFIRMED — NOT FIXED (requires schema versioning)**

## Finding P0-3 — Two export paths with different behavior
**STATUS: CONFIRMED — NOT FIXED (ExportOptionsViewModel vs AccountingExportRepository)**

## Finding P0-4 — ExportOptionsViewModel writes directly without atomic rename
**STATUS: CONFIRMED — FIXED**
- Export now writes to a temporary file (`.tmp_` prefix) and renames to the final path on success.
- On failure, the temp file is deleted, preventing partial/corrupt export files from being visible.

## Finding P0-5 — Generic exports lose important fields
**STATUS: CONFIRMED — NOT FIXED (requires export schema expansion)**

## Finding P0-6 — CSV import cannot parse many valid formats
**STATUS: CONFIRMED — NOT FIXED (requires import parser expansion)**

## Finding P0-7 — Export paging not snapshot-isolated
**STATUS: CONFIRMED — NOT FIXED (requires cursor-based snapshot isolation)**

## Finding P1-1 — Stale tests
**STATUS: CONFIRMED — NOT FIXED (testing strategy being refactored separately)**

## Finding P1-2 — Generic CSV/JSON export uses raw expense.amount, not effectiveAmount
**STATUS: CONFIRMED — FIXED**
- All 4 generic export code paths (CSV preview, CSV streaming, JSON preview, JSON streaming) now use `expense.effectiveAmount` instead of `expense.amount`.
- `effectiveAmount` correctly handles shared expenses (`myShareAmount`/`mySharePercentage`) and not-mine expenses (`0.0`).

## Finding P1-2b — Accounting exporters can OOM on large datasets
**STATUS: CONFIRMED — NOT FIXED (requires streaming for accounting exporters)**

## Finding P1-3 — Privacy/redaction absent from export
**STATUS: CONFIRMED — NOT FIXED (requires PrivacyGate integration in export paths)**

---

# 12. New issues discovered

No additional issues beyond those in the original report were found during code verification.

---

# 13. Applied fixes summary

| Fix | File(s) | Finding |
|-----|---------|---------|
| Atomic temp-file + rename for export | `ExportOptionsViewModel.kt` | P0-4 |
| Use effectiveAmount in generic CSV/JSON export | `ExportOptionsViewModel.kt` | P1-2 |

---

# 14. Remaining work priority

1. **P0-1**: Define versioned canonical app export schema that matches import format
2. **P0-5**: Expand generic CSV/JSON export to include transaction type, ownership, conversion fields
3. **P0-6**: Expand CSV import to parse ISO currency codes, comma decimals, etc.
4. **P0-7**: Implement snapshot isolation for export paging
5. **P1-2**: Stream accounting export output instead of materializing full strings
6. **P1-3**: Integrate PrivacyGate in export paths for redaction

---

# Sources

Repository sources:

- Dependency map:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md

- `ExportOptionsViewModel.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt

- `ExportOptionsScreen.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsScreen.kt

- `ExportDataRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt

- `AccountingExportRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt

- `DeterministicExpenseExportPager.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/DeterministicExpenseExportPager.kt

- `ExpenseRepository.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt

- `ExpenseDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt

- `AccountingExporters.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt

- `AccountingExportPolicy.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExportPolicy.kt

- `ExpenseExportMapper.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt

- `ExportTransaction.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt

- `AccountantReportPdfExporter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt

- `CsvExpenseImporter.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt

- `CategoryDao.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/dao/CategoryDao.kt

- `Category.kt`:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/main/java/com/yourname/expensetracker/data/database/entity/Category.kt

- Existing tests:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/data/repository/AccountingExportRepositoryTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/util/CsvExpenseImporterTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/export/CsvEscapingTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/export/AccountingExportPolicyTest.kt  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/app/src/test/java/com/yourname/expensetracker/domain/export/ExpenseExportMapperTest.kt

External reference:

- OWASP CSV Injection / Formula Injection:  
  https://owasp.org/www-community/attacks/CSV_Injection