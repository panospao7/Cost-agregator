# Pipeline 12 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P12 — Import / Export / Accounting**

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
- P12 issue doc: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_12_CONSOLIDATED_ISSUES.md
- P12 implementation plan: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/pipelines%20issues%20implementantion%20plan/PIPELINE_12_IMPLEMENTATION_PLAN.md
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Universal tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/UNIVERSAL_ISSUE_TRACKER.md
- Codebase segments: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/CODEBASE_SEGMENTS.md
- Legal paths: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/LEGAL_PATHS.md
- Export repository: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt
- Export UI ViewModel: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt
- Export domain folder: https://github.com/panospao7/Cost-agregator/tree/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/export
- Accounting exporters: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt
- Accounting export policy: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExportPolicy.kt
- CSV sanitizer: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/export/CsvCellSanitizer.kt
- Export transaction DTO: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt
- Export mapper: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt

Important context:
- P12 is **Import / Export / Accounting**.
- Core architecture segments involved:
  - Segment 18 — Export & Backup
  - Segment 17 — Tax Calculation & Reporting
  - Segment 16 — Currency & Exchange
  - Segment 9 — Core Expense Management
  - Segment 4 — Receipt Scanning / Receipt Lifecycle
  - Segment 28 — Security / Privacy
  - Segment 29 — Debug & Diagnostics
  - Segment 30 — Dependency Injection
- P12 issue docs are stale/inconsistent:
  - The consolidated doc says `2 FIXED, 4 PARTIAL, 4 TODO, 7 NEW open issues`, but some NEW issue rows are marked fixed.
  - The implementation plan says P12 is **RED** with 15 remaining items, but current code at this commit contains later-looking fixes/comments for read barriers, encryption, source links, filename/extension sanitization, bounded validation, and streaming export.
- Current code still advertises important limitations:
  - Export streaming/keyset pagination is deterministic but **not a true point-in-time snapshot**.
  - `ExportTransaction` still has a TODO around receipt links.
  - Accounting exporters use `ZoneId.systemDefault()` for date rendering, so timezone determinism needs review.
  - Accounting validation appears bounded/sample-based, so global validation vs OOM tradeoff must be validated.
- Therefore: **do not trust tracker status. Validate every P12 issue against code and tests at the target SHA.**

---

## Prompt A — P12 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin, Room, import/export, accounting, privacy/security, financial-data-integrity, backup/restore, and diagnostics architecture reviewer.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P12 — Import / Export / Accounting

Mode:
Review only + issue discovery + validation of already-fixed claims.
Do NOT implement code changes unless explicitly asked later.
You may propose exact fixes and tests, but this run is an audit/debug review.

Checkout command:
git clone https://github.com/panospao7/Cost-agregator.git
cd Cost-agregator
git checkout 83b798e849b4408b2bf683f52cb2746d37f7af16

If the checkout is dirty or not exactly at this SHA, stop and report it.

## 2. Pipeline scope

Audit Pipeline 12 end-to-end:

### Generic export scope
- CSV export,
- JSON export,
- export schema/version,
- row count correctness,
- field completeness,
- source/provenance links,
- receipt links,
- category names,
- business/tax fields,
- created/updated timestamps,
- shared/not-mine fields,
- original/effective/base/home amounts,
- currency conversion audit fields,
- conversion status / partial conversion state,
- deterministic ordering,
- snapshot consistency,
- concurrent write behavior,
- file output atomicity,
- cancellation cleanup,
- temp file handling,
- preview generation,
- path/filename safety.

### Accounting export scope
- QuickBooks IIF,
- Xero CSV,
- FreshBooks CSV,
- accountant PDF report,
- accounting validation,
- single-currency requirements,
- transaction-type requirements,
- purchase-only constraints,
- business/tax classification,
- mixed-currency totals,
- timezone/date policy,
- CSV/IIF escaping,
- spreadsheet formula injection,
- negative amount preservation,
- merchant/name/memo sanitization,
- large dataset behavior.

### Import / roundtrip scope
- CSV import if present,
- JSON import if present,
- app-level import coordinator if present,
- schema compatibility,
- version migration,
- export → import → verify roundtrip,
- conflict handling,
- idempotency,
- duplicate prevention,
- provenance/source-link restore,
- receipt link restore,
- transaction lifecycle usage,
- category/merchant/payment-method mapping,
- currency/exchange-rate handling,
- partial/failure reporting.

### Privacy / security scope
- plaintext export default,
- encrypted export,
- passphrase requirement,
- raw database backup vs normal expense export capability,
- privacy gate capability selection,
- redacted export if present,
- no raw sensitive data in logs/diagnostics,
- file path traversal,
- external/shareable path handling,
- backup/export overlap,
- formula injection and CSV injection.

### Restore / barrier / diagnostics scope
- export blocked during restore/restart-required state,
- repository-level read barrier,
- ViewModel/UI-level barrier,
- write barrier for import,
- diagnostic/drop reasons,
- restore-specific error messages,
- cancellation propagation,
- progress reporting,
- event/run logging if any worker/background export exists.

### Cross-pipeline dependencies
- P1/P2/P3/P10/P11-created expenses must export through the same normalized path.
- P4 recurring/planned links may affect source/provenance and actual expense state.
- P5/P6 money/currency normalization affects exported financial totals.
- P7 backup/restore barriers must block unsafe import/export.
- P8 privacy/redaction/encryption applies to exports and raw data.
- P9 worker guard applies if import/export is backgrounded.
- Receipt lifecycle links from P3/P11 must not be dropped if roundtrip is claimed.
- Bank import/provenance from P10 must survive export if supported.

Read first:
- `docs/analyses and debug master/PIPELINE_12_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_12_IMPLEMENTATION_PLAN.md`
- relevant universal implementation-plan docs, especially money/currency, privacy/encryption, restore barrier, diagnostics.

The master tracker says the methodology was:
Scout → Planner → Coder → Tester → Reviewer → Debugger.

Follow that method:
1. Scout files and flows.
2. Plan review coverage.
3. Inspect code deeply.
4. Inspect tests.
5. Compare with architecture.
6. Debug mismatches.
7. Produce evidence-backed findings.

Shared contracts must be validated before pipeline-local conclusions.

Important tracker caveat:
- P12 consolidated issue doc and P12 implementation plan disagree with each other and likely with code.
- Validate every issue against code and tests at this SHA.
- If code is fixed but tracker says open, report tracker drift.
- If tracker says fixed but code does not prove the invariant, report bug/partial.
- If code comments claim a limitation, treat it as a live issue until proven otherwise.

## 3. Architecture docs to read

Always read:
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`

Conditional docs to read:
- UI pipeline:
  - `COMPREHENSIVE_UI_MAP.md`
  - `VIEWMODEL_INJECTION_MAP.md`
  - `route-viewmodel-map.md`
- Privacy/diagnostics:
  - `PRIVACY_UI_ARCHITECTURE.md`
  - `SENSITIVE_DIAGNOSTICS_POLICY.md`
- DB/restore/import/export:
  - `DATABASE_BASELINE_POLICY.md`
  - `DB_WRITE_OWNERSHIP.md`
  - `backup-restore-barrier-contract.md`
  - `expense-mutation-inventory.md`

For P12 specifically, pay special attention to:
- Segment 18 — Export & Backup.
- Segment 17 — Tax Calculation & Reporting.
- Segment 16 — Currency & Exchange.
- Segment 9 — Core Expense Management.
- Segment 4 — Receipt Lifecycle.
- Segment 28 — Privacy / Security.
- `LEGAL_PATHS.md` transaction lifecycle and restore/import boundaries.
- DB write ownership for expense/category/source-link/receipt-link/import tables.
- Privacy/security docs for export encryption and redaction.
- Backup/restore barrier contract for read/export modes.

## 4. Build a pipeline file inventory

Do not rely only on this seed list.
Use `rg`, import graph, Hilt map, DAO map, callers/callees, tests, UI routes, and architecture docs to build the real inventory.

### Export repository / paging
Review:
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/DeterministicExpenseExportPager.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt`
- any other export repositories found by:
  - `rg -n "class .*Export.*Repository|interface .*Export.*Repository|exportData|exportFile|createExportFile|countExpensesBetween|fetchPage"`

### Export domain
Review:
- `app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExportPolicy.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/CsvCellSanitizer.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/SourceLinkExportRef.kt`

Search current equivalents if docs mention old names:
- `JsonExporter.kt`
- `CsvExporter.kt`
- `IifExporter.kt`
- `AccountingValidation.kt`
- `ExportFileManager.kt`
- `ImportCoordinator.kt`
- `JsonExpenseImporter.kt`
- `CsvExpenseImporter.kt`

If a tracker-named file does not exist, map the issue to current implementation and report tracker/code drift.

Search:
- `rg -n "JsonExporter|CsvExporter|IifExporter|AccountingValidation|ExportFileManager|ImportCoordinator|JsonExpenseImporter|CsvExpenseImporter|Roundtrip"`

### Export UI / ViewModel
Review:
- `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsScreen.kt`
- route/navigation entries for export/import screens.
- any debug/export screens exposing raw export.

Search:
- `rg -n "ExportOptions|generateExport|encryptExport|exportPreview|ImportScreen|ImportOptions|roundtrip" app/src/main/java/com/yourname/expensetracker/ui`

### Import pipeline
Inventory any import implementation:
- import coordinator,
- JSON importer,
- CSV importer,
- accounting importer if any,
- import preview/review UI,
- import DAO/ledger,
- file picker/SAF code,
- schema/version reader,
- conflict resolver,
- import diagnostics.

Search:
- `rg -n "Import|Importer|importFile|importExpenses|roundtrip|Csv.*Import|Json.*Import|readJson|readCsv"`
- `rg -n "export.*import|import.*export|schemaVersion|rowCount|sourceLinksJson"`

If no app-level import pipeline exists, classify `P12-P0-01` as still TODO/open with evidence.

### Privacy / encryption / redaction
Review:
- `BackupEncryptionService.kt`
- `ExportAnonymizer.kt`
- `ExportPrivacyGate.kt`
- `BackupPrivacyGate.kt`
- `PrivacyGate.kt`
- `PrivacyCapability.kt`
- `PrivacyDecision.kt`
- `SafePrivacyMetadata.kt`
- `EventMetadataSanitizer`
- redacted export classes if present.

Search:
- `rg -n "EXPENSE_EXPORT|EXPENSE_EXPORT_ENCRYPTED|RAWBACKUP_EXPORT|encryptExport|BackupEncryptionService|ExportAnonymizer|PrivacyGate|PrivacyCapability"`

### Backup / restore / barriers
Review:
- `DatabaseReadBarrier.kt`
- `DatabaseReadBarrierFlowExt.kt`
- `DatabaseWriteBarrier.kt`
- `RestoreMaintenanceMode.kt`
- `RestoreInternalWriteScope.kt`
- `BackupVerifier.kt`
- `MaintenanceOperationRunner.kt`
- `backup-restore-barrier-contract.md`

Search:
- `rg -n "DatabaseReadBarrier|DatabaseReadPolicy|EXPORT_OR_BACKUP_SNAPSHOT_READ|DatabaseWriteBarrier|RestoreMaintenanceMode|restore"`

### DAOs
Review DAOs used or needed by export/import:
- `ExpenseDao.kt`
- `CategoryDao.kt`
- `EntitySourceLinkDao.kt`
- `ReceiptExpenseLinkDao.kt`
- `ScannedReceiptDao.kt`
- `EmailReceiptDao.kt` if receipt/email provenance exported.
- `ManualRecurringExpenseDao.kt` / `RecurringOccurrenceDao.kt` if recurring provenance exported.
- `ExchangeRateDao.kt`
- `BudgetDao.kt` if broader app export claims include budgets.
- `TaxSettings` related DAO if present.
- `PipelineDiagnosticEventDao.kt`
- `OperationRunDao.kt` if diagnostics/run ledger is used.

Search:
- `rg -n "EntitySourceLinkDao|ReceiptExpenseLinkDao|ExchangeRateDao|Tax.*Dao|Import.*Dao|Export.*Dao"`

### Room entities / schema touchpoints
Review:
- `Expense.kt`
- `Category.kt`
- `EntitySourceLink.kt`
- `ReceiptExpenseLink.kt`
- `ScannedReceipt.kt`
- `EmailReceiptSource.kt`
- `ExchangeRate.kt`
- `Budget.kt`
- `ManualRecurringExpense.kt`
- `RecurringOccurrence.kt`
- `PipelineDiagnosticEvent.kt`
- `PrivacyAuditEvent.kt`
- `AppDatabase.kt`
- `DatabaseMigrations.kt`
- exported Room schema JSON if present.

Check:
- fields exported vs omitted,
- business/tax fields,
- receipt/source links,
- createdAt/updatedAt,
- base/original currency fields,
- effective amount/shared fields,
- transaction type/payment method,
- uniqueness/conflict behavior,
- FK/cascade behavior.

### Money / currency dependencies
Review:
- `MoneyNormalizationEngine.kt`
- `MoneyAggregate.kt`
- `MoneyAggregateResult.kt`
- `RateBasis.kt`
- `StaleRatePolicy.kt`
- `HomeCurrencyForMoneyMath.kt`
- `CurrencySettingsRepository.kt`
- `CurrencyRatesRepository.kt`
- `ExchangeRateContracts.kt`

Search:
- `rg -n "conversionStatus|MoneyNormalizationEngine|MoneyAggregate|baseAmount|baseCurrency|exchangeRateUsed|originalCurrency|homeCurrency"`

### Tax / business dependencies
Review:
- `app/src/main/java/com/yourname/expensetracker/domain/tax/TaxConfiguration.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/tax/TaxEstimator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/tax/TaxRateProvider.kt`
- `TaxSettingsRepository.kt`
- business expense fields in `Expense`.

Search:
- `rg -n "Tax|tax|businessPurpose|businessCategory|businessProject|requiresReceipt|isBusinessExpense"`

### Receipt lifecycle / links
Review:
- receipt link entity/DAO,
- receipt lifecycle coordinator,
- receipt record writer,
- source-link/provenance writer,
- pending review/source link paths.

Search:
- `rg -n "ReceiptExpenseLink|ScannedReceipt|ReceiptLifecycleCoordinator|EntitySourceLink|sourceLinksJson|receiptLinks"`

### Workers / background
If import/export is backgrounded, review:
- worker class,
- `WorkerExecutionGuard`,
- `WorkerRunLogger`,
- `WorkerRegistry`,
- `WorkerSpec`,
- scheduling/cancel paths.

Search:
- `rg -n "class .*Export.*Worker|class .*Import.*Worker|CoroutineWorker|WorkManager|enqueue.*Export|enqueue.*Import"`

If P12 has no workers, explicitly say “no P12 worker found” with search evidence.

### Hilt modules
Review modules that provide/bind:
- export repository,
- accounting exporters,
- import coordinator/importers,
- DAOs/database,
- money/currency services,
- privacy gate/encryption,
- backup/read/write barriers,
- diagnostics,
- dispatchers,
- TimeProvider,
- PDF/export utilities.

Search:
- `rg -n "Export|Import|Accounting|Csv|Json|IIF|FreshBooks|Xero|QuickBooks" app/src/main/java/com/yourname/expensetracker/di`

Likely modules:
- `ExportModule.kt`
- `BackupRepositoryModule.kt`
- `PrivacyModule.kt`
- `SecurityModule.kt`
- `DaoModule.kt`
- `DatabaseModule.kt`
- `CurrencyModule.kt`
- `DiagnosticsModule.kt`
- `DispatchersModule.kt`
- `TimeModule.kt`

### Tests
Search the whole repo:
- `rg -n "Export|Import|CsvCell|Accounting|JsonExport|IIF|QuickBooks|Xero|FreshBooks|PDF|Roundtrip|Snapshot|sourceLinks|receiptLinks|conversionStatus|PrivacyGate|Encryption|Restore" app/src/test app/src/androidTest`

Include tests matching:
- `*Export*`
- `*Import*`
- `*CsvCell*`
- `*Accounting*`
- `*Json*`
- `*Iif*`
- `*QuickBooks*`
- `*Xero*`
- `*FreshBooks*`
- `*Pdf*`
- `*Roundtrip*`
- `*Snapshot*`
- `*Privacy*`
- `*Barrier*`
- `*Backup*`
- `*Currency*`

Do not stop at known names. Search the entire repo.

## 5. Code-reading rules

Mandatory:
- Do not trust docs over code.
- If docs and code disagree, report the mismatch.
- Do not review only filenames; open implementation and tests.
- Trace actual runtime flow, not package structure.
- Search direct and indirect callers.
- Include cross-pipeline dependencies.
- Mark uncertainty clearly.
- Verify Hilt-injected runtime path, not just constructor signatures.
- Verify tests assert the important invariant, not merely output a file.
- If tracker says fixed/open/TODO, validate against code at this SHA.
- Treat invalid export output as data corruption.
- Treat duplicate/missing rows under claimed snapshot semantics as data-integrity risk.
- Treat plaintext sensitive export without explicit consent/gate/encryption option as privacy/security risk.
- Treat import-created expenses that bypass lifecycle as duplicate-money/data-corruption risk.
- Treat “deterministic pagination” as not equivalent to “point-in-time snapshot” unless proven.
- Treat “sample validation” as not equivalent to “global dataset validation” unless documented/accepted.
- Treat `ZoneId.systemDefault()` in machine-readable exports as a reproducibility risk unless intentionally documented.
- Treat `catch (Exception)` without `CancellationException` handling as a bug.

Use searches like:
- `rg -n "generateExport|writeStreamHeader|writeStreamFooter|writePage|streamExpensesToWriter|PreviewCollector"`
- `rg -n "schemaVersion|rowCount|rows|sourceLinksJson|receiptLinks|conversionStatus"`
- `rg -n "CsvCellSanitizer|sanitizeIif|escapeCsv|escapeJson|formatJsonNumber|JSONObject|JSONArray"`
- `rg -n "ZoneId.systemDefault|DateTimeFormatter|Instant.ofEpochMilli|TimeProvider"`
- `rg -n "effectiveAmount|baseAmount|baseCurrency|exchangeRateUsed|originalCurrency|homeCurrency|conversionRate"`
- `rg -n "Business|businessPurpose|requiresReceipt|Tax|tax"`
- `rg -n "ReceiptExpenseLink|EntitySourceLink|ScannedReceipt|sourceLink"`
- `rg -n "DatabaseReadBarrier|DatabaseWriteBarrier|EXPORT_OR_BACKUP_SNAPSHOT_READ|RestoreMaintenanceMode"`
- `rg -n "PrivacyCapability|EXPENSE_EXPORT|EXPENSE_EXPORT_ENCRYPTED|RAWBACKUP_EXPORT|encryptExport|passphrase"`
- `rg -n "createExportFile|File\\(|copyTo|renameTo|delete\\(|\\.tmp_|\\.enc|path|canonical"`
- `rg -n "CancellationException|catch \\(e: Exception\\)|catch \\(t: Throwable\\)"`
- `rg -n "Import|Importer|importExpenses|roundtrip|readCsv|readJson"`

## 6. Universal contracts to verify

Audit these for P12:

1. Restore/read/write barrier:
   - every export read checks `DatabaseReadBarrier`,
   - every import/write checks `DatabaseWriteBarrier`,
   - repository-level barriers exist, not only ViewModel checks,
   - export blocked during restore/restart-required/critical maintenance,
   - import blocked during restore/backup/export modes,
   - error messages are specific and user-safe.

2. Worker guard and run logging:
   - if import/export workers exist, they use `WorkerExecutionGuard`,
   - runs are logged start/success/skip/retry/failure,
   - cancellation propagates,
   - repeated runs are idempotent.
   - If no workers exist, mark not applicable with evidence.

3. Privacy/redaction/raw-storage policy:
   - normal expense export uses correct privacy capability, not raw backup capability,
   - encrypted export requires user passphrase and fails closed,
   - plaintext temp files are deleted on success/failure/cancellation,
   - redacted export removes sensitive/raw fields if available,
   - no raw PII/tokens/email/OCR/bank details in logs/diagnostics,
   - CSV formula injection neutralized.

4. Money/currency normalization:
   - exported amount fields are explicit: original, effective, base/home,
   - conversion status/partial/missing-rate state is included if claimed,
   - mixed-currency totals are grouped or normalized correctly,
   - no raw summing across currencies,
   - NaN/Infinity cannot enter output.

5. Transaction lifecycle ownership:
   - import-created expenses go through `TransactionLifecycleCoordinator`,
   - direct `ExpenseDao` writes are absent unless explicit restore/internal import path,
   - duplicate imported rows cannot create duplicate money records,
   - import preserves source/provenance.

6. Receipt lifecycle/link ownership:
   - receipt links exported if roundtrip completeness is claimed,
   - receipt links imported/restored through legal link ownership,
   - receipt-created/email-created expenses retain provenance.

7. Recurring planned/actual reconciliation:
   - imported actual expenses trigger recurring reconciliation if created as expenses,
   - export/import does not drop recurring source/provenance if claimed.
   - If P12 does not include recurring tables, classify as intentionally out of scope.

8. Diagnostics/drop reasons/events:
   - export/import failures have clear sanitized reasons,
   - parser/import row failures are durable if import exists,
   - diagnostic failures do not abort core flow except cancellation,
   - sensitive exception messages are sanitized.

9. Import/export schema/roundtrip:
   - JSON/CSV schemas are versioned,
   - roundtrip preserves all supported fields,
   - unsupported fields are explicitly documented,
   - receipt/source links survive roundtrip if claimed,
   - schema changes are backward compatible or version-gated.

10. DAO conflict handling and timestamps:
   - import conflict handling is idempotent,
   - `IGNORE`/upsert results are checked,
   - createdAt/updatedAt preserved or intentionally regenerated,
   - source-link/receipt-link uniqueness preserved.

## 7. P12-specific invariants to audit

### Export file lifecycle
Check:
- generated files are written to safe app-private paths or SAF destinations.
- path traversal impossible for extension/filename/user input.
- output is written to temp file first.
- final file appears only after complete successful write.
- cancellation/failure deletes temp plaintext.
- encrypted export never leaves plaintext at final shareable path.
- partial encrypted file deleted on encryption failure.
- final extension/MIME matches content.
- export preview cannot leak more than intended.

### JSON export
Check:
- JSON is valid for null notes and every optional field.
- comma handling is correct across page boundaries.
- row count matches actual row array when no concurrent writes.
- source links are JSON objects/arrays, not double-escaped strings unless intentionally encoded for flat formats.
- all strings escaped safely.
- finite numbers only.
- schemaVersion present and meaningful.
- date/time fields deterministic.
- all DTO fields included or explicitly omitted with documented schema.

### CSV export
Check:
- RFC-4180 escaping for commas, quotes, CR/LF.
- spreadsheet formula injection neutralized.
- negative numeric amounts are preserved as numbers.
- dangerous strings starting with `=`, `+`, `@`, or suspicious `-` are neutralized.
- all columns have stable order and headers.
- sourceLinks/receiptLinks encoding is parseable.
- line endings consistent.
- large export streaming does not load all rows.

### Accounting export
Check:
- Xero/FreshBooks/QuickBooks fields are correctly escaped.
- IIF tab/newline handling does not corrupt legitimate merchant names.
- accounting validation is global if it claims to be global.
- sample-only validation is reported as partial risk.
- mixed-currency totals are grouped or normalized.
- purchase-only restrictions are enforced.
- date rendering timezone policy is deterministic and documented.
- business/tax fields included where required.
- PDF totals do not raw-sum currencies.
- output can be imported by target accounting tools.

### Snapshot consistency
Check:
- export reads are from one stable snapshot if claimed.
- keyset pagination is not falsely described as snapshot consistency.
- row count, category map, source links, receipt links, and rows all come from same snapshot or documented best-effort view.
- concurrent insert/update/delete during export cannot produce corrupted file.
- if no true snapshot exists, classify `P12-P1-04` as open/design.
- verify whether a planned `export_snapshot_rows` or operationId table exists.

### Field completeness
Check exported DTO vs source entities:
- id,
- date/timestamp,
- createdAt/updatedAt,
- merchant,
- notes,
- category id/name,
- amount/effectiveAmount/originalAmount/baseAmount,
- currency/originalCurrency/homeCurrency/baseCurrency,
- exchangeRateUsed/conversionRateUsed/conversionStatus,
- transactionType,
- paymentMethod/sourceAccountName,
- source,
- source links/provenance,
- receipt links,
- business/tax fields,
- shared/not-mine/myShare fields,
- recurring/planned link/provenance if claimed.

### Import / roundtrip
Check:
- app-level CSV/JSON import exists.
- import schema matches export schema.
- import validates schemaVersion.
- import handles missing/extra fields.
- import resolves categories/merchants/payment methods deterministically.
- import creates expenses through legal lifecycle.
- import restores source/receipt links.
- import is idempotent under repeated file import.
- import has item-level failure reporting.
- roundtrip tests compare field equality for all supported fields.
- unsupported fields are explicitly documented.

### Privacy/encryption
Check:
- privacy gate checks `EXPENSE_EXPORT` for normal export.
- encrypted export checks `EXPENSE_EXPORT_ENCRYPTED`.
- raw database backup capability is not used for normal export.
- redacted mode removes raw OCR/email/notification/bank statement fields.
- no logs print full paths if considered sensitive.
- no exported file includes tokens/API keys/account numbers unless explicitly allowed/masked.
- passphrase blank/default/constant is impossible.

### Restore / error UX
Check:
- `loadExpenseCount` and `generateExport` show restore-specific messages.
- repository-level barrier prevents bypass from tests/other callers.
- import/export disabled during restart-required state if contract says so.
- cancellation is user-visible but does not log as failure.
- all catch blocks rethrow or handle `CancellationException` correctly.

## 8. Known P12 issue set to validate

Read P12 consolidated issue doc and implementation plan, then validate each against code.

Old issues:
- `P12-P0-01`: No app-level CSV/JSON import roundtrip pipeline.
- `P12-P1-01`: Xero/FreshBooks CSV exporters do not do real CSV escaping.
- `P12-P1-02`: Accounting validation is per-page/sample, not global.
- `P12-P1-03`: Multi-currency export fields incomplete / missing conversion status.
- `P12-P1-04`: Export snapshot consistency is not real.
- `P12-P1-05`: Normal exports plaintext and not privacy-gated.
- `P12-P1-06`: Export silently drops many app fields.
- `P12-P1-07`: Receipt links not represented in exports.
- `P12-P1-08`: Business/tax fields not exported.
- `P12-P1-09`: Accountant PDF has raw mixed-currency combined total.
- `P12-P1-10`: Export can run during restore/restart-required state.

New issues:
- `NEW-P12-001`: JSON export produces invalid JSON, e.g. missing comma around null field.
- `NEW-P12-002`: `sourceLinksJson` double-escaped.
- `NEW-P12-003`: `CsvCellSanitizer` corrupts negative amounts.
- `NEW-P12-004`: `createExportFile` path traversal risk.
- `NEW-P12-005`: Accounting validation loads all expenses / OOM risk.
- `NEW-P12-006`: `loadExpenseCount` generic error during restore.
- `NEW-P12-007`: `sanitizeIif` corrupts merchant names starting with `-`.

Also check likely current-code issues not clearly in tracker:
- machine-readable export dates use `ZoneId.systemDefault()`;
- sample-only validation may miss later invalid rows;
- encrypted export may be present but plaintext temp cleanup must be proven on cancellation;
- source links may be exported but receipt links still TODO;
- keyset pagination may be deterministic but not snapshot-consistent;
- normal export privacy capability must be checked at runtime;
- no import pipeline may still exist.

Important:
- If code comments say fixed, verify tests and behavior.
- If implementation plan says open but code is fixed, report tracker drift.
- If code is fixed but tests are weak/missing, mark fixed-with-test-gap or partial.
- If a feature is intentionally not supported, require explicit documentation and schema contract.

## 9. Review dimensions

Check:
- correctness,
- financial data integrity,
- roundtrip integrity,
- duplicate-money prevention on import,
- atomicity/transactions,
- lifecycle bypasses,
- direct DAO writes,
- restore/export safety,
- privacy fail-closed behavior,
- encryption/passphrase safety,
- raw PII storage/logging/export,
- cancellation handling,
- coroutine races,
- idempotency,
- dedupe/conflict behavior,
- snapshot consistency,
- schema/version compatibility,
- timestamp/currency defaults,
- timezone determinism,
- Hilt binding correctness,
- UI state consistency,
- diagnostics coverage,
- test coverage,
- performance/OOM risks,
- security/privacy risks.

## 10. Required output format

Produce this exact structure:

# Pipeline 12 Review — Import / Export / Accounting

## 1. Pipeline summary
- What P12 does.
- Main data flow.
- Entry points and exits.
- Mermaid or text data-flow diagram.

## 2. File inventory
Create a table:
| Category | Files reviewed | Why relevant | Notes |

Include:
- export entry points,
- import entry points,
- repositories,
- pagers/snapshot helpers,
- exporters/writers,
- mappers/DTOs,
- validators,
- privacy/encryption files,
- barriers/backup files,
- DAOs,
- Room entities,
- workers if any,
- Hilt modules,
- ViewModels/UI,
- tests,
- diagnostics/event writers,
- migrations/schema touchpoints.

Also list:
- files intentionally skipped and why,
- files discovered but not fully reviewed and why.

## 3. Architecture comparison
- Does code follow `LEGAL_PATHS.md`?
- Does code follow Segment 18 export/import ownership?
- Does code follow privacy/security docs?
- Does code follow restore barrier contract?
- Any doc/code drift?
- Any tracker/code drift?
- Any stale TODO or misleading comment?

## 4. Runtime flow / call graph
Include:
- export screen → ViewModel → repository → pager → mapper → writer → file,
- JSON export,
- CSV export,
- accounting export,
- PDF export if present,
- encrypted export,
- privacy gate,
- read barrier,
- import flow if present,
- roundtrip flow if present,
- diagnostics/events,
- cancellation/failure cleanup.

## 5. Issue table
Use columns:
| ID | Severity P0/P1/P2/P3 | Status bug/partial/TODO/fixed/design | File(s) | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |

Every finding must have concrete evidence:
- file path,
- method name,
- relevant condition,
- why it violates contract.

## 6. Universal contract audit
Subsections:
- restore/read/write barrier,
- privacy/redaction/encryption,
- lifecycle ownership,
- worker guard/run logging,
- money/currency normalization,
- diagnostics/events,
- import/export/backup,
- DAO conflict/timestamps.

For each, verdict:
- PASS,
- FAIL,
- PARTIAL,
- NOT APPLICABLE,
with evidence.

## 7. P12 issue reconciliation
Create table:
| Tracker issue | Tracker status | Code status at target SHA | Evidence | Final status | Notes |

Include all old and new P12 issues from `PIPELINE_12_CONSOLIDATED_ISSUES.md`.

## 8. Test coverage review
- Existing tests found.
- What each test proves.
- Missing tests.
- Weak tests that do not assert the important invariant.

## 9. Test plan
Include:
- unit tests,
- integration tests,
- regression tests,
- instrumentation/UI tests if needed,
- manual validation scenarios.

## 10. Optional deliverables
Include at least one:
- Mermaid/text data-flow diagram,
- call graph,
- export schema field-coverage matrix,
- import/export roundtrip matrix,
- legal write/read path table,
- privacy/encryption table,
- before/after fix plan,
- commit plan split by safe PRs.

## 11. Final verdict
- GREEN / YELLOW / RED.
- Highest-risk remaining issue.
- Whether P12 is production-safe.
- What must be fixed before GREEN.

## 11. Severity rubric

Use:
- P0: data loss, corruption, privacy leak, broken restore, duplicate money records, irreversible wrong write.
- P1: major wrong behavior, race, lifecycle bypass, missing guard, broken critical flow.
- P2: edge-case bug, poor diagnostics, partial inconsistency, retry/idempotency weakness.
- P3: cleanup, docs drift, TODO, non-critical maintainability.

For P12:
- Invalid JSON/CSV that corrupts exported financial data is P0/P1.
- Import creating duplicate expenses/money records is P0.
- Missing import roundtrip where product claims import/export is P0/P1 depending release claim.
- Plaintext sensitive export without privacy gate/encryption option is P0/P1.
- Snapshot inconsistency causing missing/duplicate rows is P1.
- Missing receipt/source links is P1 if roundtrip completeness is claimed.
- Timezone/system-default drift is P2 unless it causes wrong tax/accounting dates.

## 12. Completion criteria

The review is not complete until:
- P12 issue doc was read,
- master/universal trackers were read,
- architecture docs were checked,
- all relevant source files were inventoried,
- key callers/callees were traced,
- every export writer was inspected,
- import existence/absence was proven,
- privacy/encryption and barriers were verified,
- tests were found or missing tests were listed,
- cross-pipeline impacts were identified,
- every finding has evidence and a fix strategy,
- final verdict is justified.
```

---

## Prompt B — P12 Fix Implementation + Tests Prompt

Use this after Prompt A produces confirmed findings.

```text
You are a senior Android/Kotlin implementation agent specializing in import/export correctness, accounting formats, Room consistency, privacy/encryption, snapshot safety, and test-driven financial-data fixes.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Commit baseline:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P12 — Import / Export / Accounting

Mode:
Fix implementation + test writing + validation.
Only fix confirmed P12 issues.
Do not perform broad refactors.
Preserve architecture contracts and public behavior unless a bug requires change.

## 2. Required reading before editing

Read:
- `docs/analyses and debug master/PIPELINE_12_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_12_IMPLEMENTATION_PLAN.md`
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`
- `docs/architecture/SENSITIVE_DIAGNOSTICS_POLICY.md`
- `docs/architecture/PRIVACY_UI_ARCHITECTURE.md` if UI/privacy touched.
- DB/restore/export docs:
  - `DATABASE_BASELINE_POLICY.md`
  - `DB_WRITE_OWNERSHIP.md`
  - `backup-restore-barrier-contract.md`
  - `expense-mutation-inventory.md`

Do not trust docs over code.
If tracker status differs from code, fix code only if code is actually wrong.
If only docs are stale, report docs drift instead of changing code.

## 3. Implementation constraints

Follow P12 legal paths:
- export reads must go through `ExportDataRepository` or another barrier-checked export boundary,
- import-created expenses must go through `TransactionLifecycleCoordinator` or documented legal import boundary,
- repository-level `DatabaseReadBarrier` / `DatabaseWriteBarrier` must enforce restore safety,
- export privacy decisions must use `PrivacyGate`,
- normal expense export must not request raw backup capability,
- encrypted export must require user-supplied passphrase and fail closed,
- all writers must escape/encode according to format,
- all money fields must be finite and explicit,
- all import/export schema fields must be documented,
- diagnostics/logs must be sanitized,
- cancellation must propagate and cleanup temp files.

General rules:
- Keep changes minimal and targeted.
- Add/update tests for every fixed issue.
- Do not introduce schema migration unless explicitly required and approved.
- Do not mask `CancellationException`.
- Do not raw-sum cross-currency totals.
- Do not add plaintext export paths without explicit privacy gate.
- Do not create import expenses through `ExpenseDao`.
- Do not claim snapshot consistency unless implemented.
- Do not silently drop fields without schema documentation.
- Do not break existing exported format compatibility without versioning.

## 4. Candidate P12 fix areas

Validate first, then fix only if still broken.

### P12-PR1 — Critical export output correctness

Candidate issues:
- `NEW-P12-001`: invalid JSON around null/optional fields.
- `NEW-P12-002`: `sourceLinksJson` double-escaped.
- `NEW-P12-003`: negative amounts corrupted by CSV sanitizer.
- `NEW-P12-007`: IIF sanitizer corrupts leading-dash merchant names.
- `P12-P1-01`: CSV escaping not RFC-4180-compliant.

Implementation intent:
1. Use safe JSON builders (`JSONObject`, `JSONArray`, kotlinx serialization, or equivalent) instead of fragile string concatenation.
2. For flat formats, decide whether `sourceLinksJson` is a string field or nested JSON; do not double-encode.
3. Preserve negative numeric amounts.
4. Neutralize formula-dangerous strings without corrupting legitimate data.
5. Ensure CSV quotes commas, quotes, CR/LF.
6. Ensure IIF strips tabs/newlines but preserves intended merchant text unless formula-dangerous.

Required tests:
- `json_export_valid_with_null_notes`
- `json_export_valid_across_multiple_pages`
- `source_links_not_double_escaped`
- `csv_negative_amounts_preserved`
- `csv_formula_strings_neutralized`
- `csv_rfc4180_quotes_commas_quotes_newlines`
- `iif_leading_dash_merchant_preserved_or_safely_escaped`
- `quickbooks_xero_freshbooks_escape_special_chars`

### P12-PR2 — Restore/barrier, path safety, cancellation cleanup

Candidate issues:
- `P12-P1-10`: export can run during restore/restart-required state.
- `NEW-P12-004`: path traversal in export file creation.
- `NEW-P12-006`: generic restore error in load count.
- temp plaintext cleanup gaps.
- cancellation swallowed or misclassified.

Implementation intent:
1. Add repository-level read barrier to every export read, category read, source-link read, count read, and page read.
2. Add import write barrier if import exists.
3. Guard UI and repository paths; do not rely on ViewModel only.
4. Sanitize extension/filename and verify final canonical path stays under export directory.
5. Return restore-specific error messages.
6. Use temp file + atomic rename/copy only after full success.
7. Delete temp plaintext in `finally`, including cancellation and encryption failure.
8. Rethrow or explicitly handle `CancellationException`.

Required tests:
- `export_repository_blocked_during_restore`
- `export_count_blocked_during_restore_has_specific_error`
- `source_link_export_read_uses_read_barrier`
- `path_traversal_filename_or_extension_rejected`
- `final_export_path_inside_export_directory`
- `cancelled_export_deletes_temp_plaintext`
- `failed_encryption_deletes_partial_ciphertext`
- `cancellation_exception_not_logged_as_generic_failure`

### P12-PR3 — Performance and validation correctness

Candidate issues:
- `NEW-P12-005`: accounting validation loads all expenses / OOM.
- `P12-P1-02`: validation is sample/per-page, not global.
- large export writer memory pressure.
- accounting exporters still materialize full list.

Implementation intent:
1. Stream generic export page-by-page.
2. For accounting validation, choose one:
   - true paginated global validation across all rows, or
   - explicitly classify as bounded sample and surface partial-validation warning.
3. Prefer global paginated validation for accounting formats:
   - validate single-currency and transaction types incrementally,
   - stop at first fatal error or cap collected errors.
4. Avoid loading all rows for large datasets.
5. Add progress/cancellation checks between pages.

Required tests:
- `accounting_validation_global_detects_invalid_row_after_10000`
- `accounting_validation_large_dataset_no_oom`
- `streaming_export_uses_bounded_memory`
- `large_export_progresses_page_by_page`
- `validation_cancellation_propagates`
- `accounting_format_does_not_materialize_unbounded_rows_or_documents_limit`

### P12-PR4 — Export completeness: currency, business/tax, source links, receipt links

Candidate issues:
- `P12-P1-03`: multi-currency export missing conversion status.
- `P12-P1-06`: export drops app fields.
- `P12-P1-07`: receipt links not exported.
- `P12-P1-08`: business/tax fields incomplete.
- possible timezone/date policy gap.

Implementation intent:
1. Create an export schema field matrix:
   - source entity field,
   - DTO field,
   - JSON field,
   - CSV field,
   - accounting field or documented N/A,
   - import field if roundtrip supported.
2. Add `conversionStatus` or equivalent:
   - exact,
   - identity,
   - missing rate,
   - stale rate,
   - partial,
   - unknown.
3. Include all relevant money fields:
   - original amount/currency,
   - effective amount,
   - base/home amount/currency,
   - exchange rate used.
4. Include source links and receipt links.
5. Include business/tax fields.
6. Add deterministic timezone policy:
   - UTC for machine-readable export, or
   - configured export timezone included in manifest.
7. Version schemas when adding fields.

Required tests:
- `export_includes_conversion_status`
- `export_includes_original_effective_base_amounts`
- `export_includes_business_tax_fields`
- `export_includes_source_links`
- `export_includes_receipt_links`
- `all_export_transaction_fields_accounted_for_in_schema_matrix`
- `json_export_dates_deterministic_across_timezones`
- `csv_export_dates_use_declared_timezone_policy`
- `mixed_currency_pdf_groups_or_normalizes_totals`

### P12-PR5 — Privacy/encryption/redacted export

Candidate issues:
- `P12-P1-05`: plaintext default / encryption not wired.
- wrong privacy capability used for normal export.
- raw export capability confusion.
- redacted export gaps.

Implementation intent:
1. Normal export checks `PrivacyCapability.EXPENSE_EXPORT`.
2. Encrypted export checks `PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED`.
3. Raw backup/export capability is not used for normal expense export.
4. Encrypted export requires non-blank user passphrase.
5. No default/constant passphrase.
6. Plaintext temp exists only in app-private temp location and is always deleted.
7. Redacted export excludes raw OCR/email/notification/bank text and sensitive account identifiers if such fields are in scope.
8. Diagnostics/logs never include raw exported row content.

Required tests:
- `normal_export_uses_expense_export_capability`
- `encrypted_export_uses_encrypted_export_capability`
- `normal_export_does_not_use_rawbackup_capability`
- `encrypted_export_requires_passphrase`
- `encrypted_export_no_default_passphrase`
- `plaintext_temp_removed_after_success_failure_and_cancel`
- `redacted_export_excludes_sensitive_fields`
- `diagnostics_do_not_log_export_rows_or_raw_sensitive_data`

### P12-PR6 — Import / roundtrip feature

Candidate issues:
- `P12-P0-01`: no app-level CSV/JSON import roundtrip.
- `P12-P1-04`: snapshot consistency if roundtrip exports must be consistent.
- missing import schema/versioning.
- import lifecycle bypass risk.

Implementation intent:
1. If import is product-required, implement explicit import coordinator:
   - validate schemaVersion,
   - parse JSON/CSV,
   - preview/validate,
   - map categories/payment methods/currencies,
   - detect conflicts,
   - write through legal lifecycle,
   - restore source/receipt links,
   - produce item-level result.
2. Add idempotency:
   - source link/external ID/fingerprint,
   - import operation ID,
   - conflict resolver.
3. Add roundtrip tests:
   - seed DB,
   - export,
   - import into empty DB,
   - compare supported fields,
   - verify links/provenance.
4. If import is not planned, update docs/product wording and mark P12-P0-01 as design/TODO, not “fixed.”

Required tests:
- `json_roundtrip_preserves_supported_fields`
- `csv_roundtrip_preserves_supported_fields`
- `roundtrip_preserves_source_links`
- `roundtrip_preserves_receipt_links`
- `import_created_expense_uses_transaction_lifecycle`
- `reimport_same_file_is_idempotent`
- `import_conflict_reports_item_level_error`
- `unsupported_schema_version_rejected_safely`
- `import_blocked_during_restore`

### P12-PR7 — Snapshot consistency

Candidate issues:
- `P12-P1-04`: export snapshot consistency not real.
- rowCount can disagree with streamed rows.
- concurrent writes can appear/miss mid-export.

Implementation intent:
Choose one architecture and implement carefully:
1. Room transaction snapshot if feasible:
   - read count, categories, links, rows from one read transaction,
   - avoid holding huge transaction too long if unsafe.
2. Export snapshot table:
   - create operationId,
   - materialize stable row IDs and maybe category/link snapshots,
   - stream by operationId,
   - cleanup after export.
3. SQLite backup/snapshot read replica:
   - checkpoint/snapshot DB,
   - export from snapshot DB.
4. If only best-effort deterministic pagination is supported, update UI/docs/schema manifest to say non-snapshot and mark issue open/design.

Required tests:
- `export_row_count_matches_rows_under_no_concurrent_writes`
- `export_snapshot_consistent_under_concurrent_insert`
- `export_snapshot_consistent_under_concurrent_update`
- `export_snapshot_consistent_under_concurrent_delete`
- `category_and_source_links_from_same_snapshot`
- `snapshot_cleanup_after_success_failure_cancel`

## 5. Universal checks before/after every fix

Verify:
- export reads pass through read barrier,
- import writes pass through write barrier,
- no direct expense DAO lifecycle bypass on import,
- privacy gate capability correct,
- encrypted export fail-closed,
- plaintext temp cleanup,
- no invalid JSON/CSV/IIF output,
- no raw-summing currencies,
- all numeric values finite,
- cancellation propagated,
- source/receipt links preserved or explicitly documented unsupported,
- timestamps and timezone policy deterministic,
- tests hit real runtime path.

## 6. Required validation commands

Run at minimum:
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Export*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Import*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CsvCell*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Accounting*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*JsonExport*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Iif*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Xero*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*FreshBooks*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*QuickBooks*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Pdf*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Roundtrip*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Snapshot*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Encryption*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Barrier*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Currency*" --stacktrace
./gradlew :app:check --stacktrace
```

If a command cannot run, report:
- exact command,
- failure reason,
- whether failure is related to P12,
- what still needs manual validation.

## 7. Required output

Produce:

## Summary
- Issues fixed.
- Issues confirmed already fixed.
- Issues deferred/design-only.
- Issues not touched and why.

## Changed files
| File | Change | Issue IDs | Tests |

## Issue reconciliation
| ID | Before | After | Evidence | Tests |

## Test results
- Commands run.
- Pass/fail.
- Relevant logs.

## Remaining risks
- Highest risk.
- Cross-pipeline impacts.
- Any migration/design follow-up.

## Commit plan
Split into safe PRs:
1. critical writer correctness,
2. barrier/path/cancellation safety,
3. validation/performance,
4. completeness/currency/links/timezone,
5. privacy/encryption/redaction,
6. import roundtrip,
7. snapshot consistency,
8. docs/tracker sync.
```

---

## Prompt C — P12 Final Validation / Fixed-Claims Audit Prompt

Use this after fixes land.

```text
You are a senior validation/debugger agent specializing in import/export correctness, accounting output, financial roundtrip integrity, privacy/encryption, snapshot consistency, and restore safety.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Target:
Use the current working branch/commit provided by the user.
Baseline context commit:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P12 — Import / Export / Accounting

Mode:
Validation of already-fixed claims.
Do not implement new fixes.
Verify whether P12 can be marked GREEN/YELLOW/RED.

## 2. Required reading

Read:
- P12 consolidated issue doc,
- P12 implementation plan,
- master tracker,
- universal tracker,
- all architecture docs listed in Prompt A,
- sensitive diagnostics policy,
- privacy UI architecture if UI touched,
- backup/restore barrier contract,
- changed source files,
- changed tests,
- migration/schema files if touched,
- changed Hilt modules,
- changed UI files,
- changed privacy/encryption files,
- changed import/export writers,
- changed DAOs/entities if touched.

Do not trust PR descriptions or comments.
Validate against code and tests.

## 3. Claims to validate

Validate:
- all P12 old issues marked fixed,
- all P12 new issues marked fixed,
- all universal fixes that affect P12,
- all newly added tests,
- no new lifecycle bypasses introduced,
- no new plaintext/privacy leak introduced,
- no new corrupt output path introduced.

Specific P12 claims:
- JSON export is valid for null/optional fields and multi-page output,
- CSV output is RFC-4180-compliant,
- CSV/IIF formula injection is neutralized,
- negative numeric amounts are preserved,
- sourceLinksJson is not double-escaped,
- receipt links are exported/imported if claimed,
- all DTO fields are either exported or explicitly documented unsupported,
- business/tax fields exported where required,
- conversion status included and accurate if claimed,
- mixed-currency totals are grouped/normalized correctly,
- accounting validation is global if claimed,
- validation does not OOM on large datasets,
- export uses true snapshot if claimed,
- rowCount matches exported rows under snapshot semantics,
- concurrent writes do not corrupt exported dataset if snapshot claimed,
- normal export uses correct privacy capability,
- encrypted export requires user passphrase,
- plaintext temp is removed on success/failure/cancel,
- path traversal impossible,
- export blocked during restore/restart-required state,
- repository-level barriers exist,
- load count/generate export show restore-specific error,
- import pipeline exists if claimed,
- import-created expenses use transaction lifecycle,
- reimport is idempotent,
- roundtrip preserves supported fields,
- unsupported fields are schema-documented,
- date/timezone policy deterministic,
- no raw sensitive data in diagnostics/logs,
- Hilt binds intended implementations.

## 4. Required validation steps

1. Build exact file inventory.
2. Trace runtime flows.
3. Compare code to `LEGAL_PATHS.md`.
4. Run targeted tests.
5. Review test assertions for real coverage.
6. Check direct DAO writes.
7. Check export read barriers.
8. Check import write barriers.
9. Check privacy/encryption paths.
10. Check temp-file cleanup.
11. Check JSON/CSV/IIF/PDF outputs with parsers/golden tests.
12. Check money/currency fields.
13. Check source/receipt link export/import.
14. Check snapshot behavior.
15. Check roundtrip behavior.
16. Check diagnostics/logging.
17. Check Hilt bindings.
18. Check UI states if touched.

## 5. Required output

Produce:

# P12 Fixed-Claims Validation

## 1. Verdict
GREEN / YELLOW / RED

## 2. Claims table
| Claim | Source doc/PR | Validated? | Evidence | Remaining risk |

## 3. Regression search
| Area | Search/check performed | Result |

Include at least:
- direct `ExpenseDao` import writes,
- JSON string concatenation,
- `sourceLinksJson`,
- receipt link export,
- `ZoneId.systemDefault`,
- CSV/IIF sanitizer,
- `createExportFile` path safety,
- temp plaintext cleanup,
- privacy capabilities,
- read/write barrier usage,
- cancellation catches,
- rowCount/snapshot code,
- validation row limits,
- conversionStatus,
- all export DTO fields,
- Hilt bindings.

## 4. Test validation
| Test | What it proves | Weakness/gap |

## 5. Contract audit
- restore/read/write barrier,
- privacy/encryption/redaction,
- lifecycle ownership,
- worker guard if applicable,
- money/currency,
- diagnostics/events,
- import/export/backup,
- DAO conflicts/timestamps,
- UI state if applicable.

## 6. Remaining issues
| ID | Severity | Status | Evidence | Required next action |

## 7. Production safety
- Is P12 production-safe?
- Highest-risk issue.
- Required fix before GREEN.
```