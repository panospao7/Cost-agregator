# PR 7 — CSV/JSON import/export source links

## Baseline checked
- `ExportTransaction`, `ExpenseExportMapper`, `ExportDataRepository`, and `ExportOptionsViewModel` already drive generic export.
- They do **not** currently emit `sourceLinks`.
- `CsvExportImportRoundtripGoldenTest` is sanitizer-only; it is not a real import/export roundtrip.
- The pipeline-12 report says there is no real app-level CSV/JSON import pipeline visible in main source.
- PR1–PR6 source-link infrastructure is assumed merged.

## Goal
Make CSV/JSON export/import preserve durable expense provenance:
- export source links with each expense row
- import them back into `entity_source_links`
- preserve privacy-safe metadata
- remain idempotent on re-import

## Non-goals
- No receipt/email/bank/notification logic changes
- No accounting exporter changes
- No export ledger/checksum work
- No restore/backup redesign
- No functional `ReceiptExpenseLink` replacement

## Design decisions
1. Scope is **expense rows only**.
2. JSON exports embed `sourceLinks` as a nested array.
3. CSV exports use one `SourceLinksJson` column plus optional `SourceLinkCount`.
4. Export schema bumps to **v3**.
5. Import must go through `TransactionLifecycleCoordinator` / `SourceLinkWriter`, never direct DAO insert.
6. If an expense has no canonical source-link rows yet, synthesize legacy fallback links from existing expense fields so old data still roundtrips.

## Files to add/modify

### Export side
- `app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/SourceLinkExportRow.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/SourceLinkExportCodec.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportSourceLinkAssembler.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/EntitySourceLinkDao.kt`
- `app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt`
- `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`

### Import side
- `app/src/main/java/com/yourname/expensetracker/data/import/ImportCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/data/import/JsonExpenseImporter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/import/CsvExpenseImporter.kt`
- `app/src/main/java/com/yourname/expensetracker/data/import/ImportManifest.kt`
- `app/src/main/java/com/yourname/expensetracker/data/import/ImportExpenseRowMapper.kt`
- `app/src/main/java/com/yourname/expensetracker/data/import/SourceLinkImportMapper.kt`

### Tests
- `SourceLinkExportCodecTest.kt`
- `ExpenseExportSourceLinkAssemblerTest.kt`
- `JsonExpenseImporterTest.kt`
- `CsvExpenseImporterTest.kt`
- `ImportCoordinatorTest.kt`
- replace misleading `CsvExportImportRoundtripGoldenTest` with a real roundtrip suite

## Export implementation

### 1) Extend the export DTO
Add to `ExportTransaction`:
- `sourceLinks: List<SourceLinkExportRow> = emptyList()`
- optionally `sourceLinkCount: Int = 0`

### 2) Add a transport DTO
`SourceLinkExportRow` should include:
- `sourceType`
- `sourceEntityType`
- `sourceEntityLocalId`
- `sourceIdentityKey`
- `externalIdHash`
- `externalFingerprintHash`
- `providerId`
- `accountIdHash`
- `operationRunId`
- `importBatchId`
- `importRowNumber`
- `linkRole`
- `linkStatus`
- `confidence`
- `isPrimary`
- `createdAt`
- `createdBy`
- `correlationId`
- `metadataJson`
- `metadataSchemaVersion`

### 3) Batch-fetch source links
Add a DAO method like:
- `getForExpenseIds(expenseIds: List<Long>)`

Avoid N+1 queries during export.

### 4) Assemble canonical + legacy fallback links
For each expense page:
- fetch expense rows
- fetch source links for those expense IDs
- map them into `SourceLinkExportRow`
- if none exist, synthesize a legacy fallback row from `Expense.source` / legacy source fields
- order deterministically: primary first, then `createdAt`, then `sourceIdentityKey`

### 5) JSON export
Emit:
```json
"sourceLinks": [ ... ]
```
inside each expense row.

### 6) CSV export
Emit:
- `SourceLinksJson` column containing a compact JSON array
- optionally `SourceLinkCount`

Use `CsvCellSanitizer` on that cell so formula injection / quoting remain safe.

### 7) Manifest
Add export manifest data:
- `schemaVersion: 3`
- `sourceLinkEncoding: JSON_ARRAY`
- `privacyMode`
- `sourceArtifactPolicy`
- `omittedSourceArtifacts`
- `warnings`

For redacted export:
- omit raw local source IDs when source artifacts are absent
- keep hashes and source types
- mark links `REDACTED` or `LEGACY_PARTIAL`

## Import implementation

### 1) Add import coordinator
`ImportCoordinator` should:
- read manifest/header
- choose JSON or CSV importer
- parse rows streaming
- send each expense row through `TransactionLifecycleCoordinator`

### 2) Parse source links
`JsonExpenseImporter`:
- read nested `sourceLinks` array

`CsvExpenseImporter`:
- parse `SourceLinksJson` with a real JSON parser
- do **not** use naive string splitting

### 3) Map to coordinator request
`ImportExpenseRowMapper` should build `CreateExpenseRequest` with:
- core expense fields
- `sourceLinks`
- legacy fallbacks only when needed

If explicit `sourceLinks` exist, prefer them and do not duplicate legacy mappings.

### 4) Legacy compatibility
If importing v2 or older rows with no `sourceLinks`:
- synthesize `LEGACY_SOURCE_ONLY` from `Expense.source`
- synthesize `RAW_NOTIFICATION` / `CSV_IMPORT_ROW` / other legacy link types if those fields exist in the row
- mark as `LEGACY_PARTIAL`

### 5) Preserve provenance fidelity
If the export contains:
- `createdAt`
- `createdBy`
- `correlationId`
- `metadataJson`

restore them when possible via an import-specific source-link payload/helper. If that is too invasive in the first pass, preserve the logical link and carry the missing chronology as a follow-up.

### 6) Idempotency
Import must be idempotent:
- dedupe by `sourceIdentityKey`
- let `EntitySourceLink` unique index absorb repeated inserts
- treat `AlreadyExists` as success
- on re-import, duplicates should resolve via the existing PR2 duplicate policy

### 7) Error policy
- malformed `sourceLinksJson` => row-level import error
- unsupported fields => warning in manifest / preview
- missing source artifacts => import partial provenance, not failure, unless strict mode is enabled

## Privacy rules
- never export raw external IDs in plaintext
- never export raw notification/email/bank text in source-link metadata
- `metadataJson` must be allowlisted/sanitized
- CSV source-link JSON must still be quoted/sanitized
- redacted export should keep hashes and source types, not raw local IDs when artifacts are omitted

## Tests

### Export tests
- `json_export_includes_source_links`
- `csv_export_includes_source_links_json_column`
- `export_source_links_are_deterministically_ordered`
- `legacy_expense_without_source_links_gets_synthesized_link`
- `redacted_export_keeps_hashes_and_omits_raw_local_ids`

### Import tests
- `json_import_preserves_source_links`
- `csv_import_preserves_source_links`
- `legacy_v2_import_creates_partial_source_links`
- `malformed_source_links_json_fails_row_import`
- `import_reimport_is_idempotent`
- `import_does_not_leak_raw_sensitive_metadata`

### Roundtrip tests
- rename the sanitizer-only golden test
- add a real `json_csv_source_links_roundtrip` golden suite
- include local, external, and legacy-fallback source links

## Suggested implementation order
1. Add `SourceLinkExportRow` and `SourceLinkExportCodec`
2. Add batch source-link fetching in DAO/repository
3. Extend `ExpenseExportMapper` and export writers
4. Add manifest/source-link encoding metadata
5. Add import coordinator and file importers
6. Wire import rows to `TransactionLifecycleCoordinator`
7. Add legacy fallback synthesis
8. Add roundtrip and redaction tests
9. Rename the misleading sanitizer-only golden test

## Acceptance criteria
- JSON export includes durable source links per expense
- CSV export roundtrips source links through a JSON cell
- Import restores source links into `entity_source_links`
- Legacy exports without canonical links still produce partial provenance
- Redacted exports remain privacy-safe
- Re-import is idempotent
- No raw sensitive source data leaks into export/import metadata

## Sources checked
- Current export DTO:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt
- Export mapper:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt
- Export repository:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt
- Export UI / writer flow:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt
- Misleading roundtrip test:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/test/java/com/yourname/expensetracker/golden/CsvExportImportRoundtripGoldenTest.kt
- Pipeline 12 static debug report:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/pipeline12_static_debug_report_b6abe0a.md
- Global source-links plan:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/docs/analyses%20and%20debug%20master/new%20debugging%20session/global_source_links_provenance_plan.md