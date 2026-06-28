# Import / Export Contract Implementation Plan

Last updated: 2026-06-16  
Scope: MIT-047, MIT-048, MIT-055, MIT-072, MIT-073, MIT-080  
Goal: define exactly what import/export supports before patching code, then rebuild import/export around lifecycle, provenance, idempotency, privacy, and accounting correctness.

---

## 1. Executive Summary

Do **not** keep patching CSV/JSON import/export ad hoc.

First define a formal contract:

> What fields can be exported?  
> What fields can be imported?  
> What fields roundtrip exactly?  
> What fields are preserved as metadata only?  
> What fields are rejected?  
> What source-specific entities require backup/restore instead of normal import?

Then implement import/export against that contract.

The current canonical interpretation is:

> Import exists as utility-level code, but not as a production-safe, lifecycle-owned, barrier-checked, idempotent import pipeline.

This plan turns it into a defined product feature.

---

## 2. Master Issues Covered

| MIT | Issue | Covered Here |
|---|---|
| MIT-047 | Rebuild import support as safe lifecycle-owned pipeline | Yes |
| MIT-048 | Fix CSV/JSON import semantics and parser safety | Yes |
| MIT-055 | Harden export/accounting correctness | Yes |
| MIT-072 | Export schema completeness | Yes |
| MIT-073 | Resolve import architecture contradiction | Yes |
| MIT-080 | Import/export supported-field contract | Yes |

Related but not owned here:

| MIT | Relationship |
|---|---|
| MIT-030 | Barriers must block import/export during restore/reset |
| MIT-033 | DB idempotency constraints support import row/source-link dedupe |
| MIT-034 | Cancellation safety required in import/export flows |
| MIT-045 | Import raw persistence sanitization |
| MIT-050/053 | Money correctness assumptions |
| MIT-060/063/083 | UI import/export action safety |
| MIT-078 | Historical migration tests |

---

## 3. Affected Pipelines

| Pipeline | Impact |
|---|---|
| P2 | Expense lifecycle/source-link duplicate behavior |
| P3 | Receipt links and receipt provenance |
| P5 | Money/dashboard export/import semantics |
| P10 | Bank source provenance |
| P11 | Email receipt source provenance |
| P12 | Export/accounting/import architecture |
| P13 | DB constraints and import source-link uniqueness |
| P14 | UI import/export actions |
| P18 | CSV/JSON util import safety |

---

## 4. Current Problems

Known issues:

- P12 says expected production import architecture was absent/incomplete.
- P18 says util-level import exists but is unsafe.
- CSV import sets `source = CSV_IMPORT` but lacks `csvImportBatchId` / `csvRowNumber`.
- Importers create categories directly through DAO.
- Failed row can leave category behind.
- Import may mutate during restore.
- JSON import reuses exported `source` unsafely.
- Exported `sourceLinks` are ignored.
- JSON idempotency key ineffective under standard dedupe mode.
- CSV lacks stable row identity.
- Missing currency falls back to EUR.
- CSV date parsing uses system timezone.
- CSV parser is naive and non-streaming.
- Import errors can expose raw file content.
- Formula-leading values can be stored and later re-exported.
- Export lacks or partially handles conversion status, receipt links, shared/not-mine flags.
- Export may not be point-in-time snapshot.
- Accounting export materializes rows and validates only sample subset.
- Export output may use device timezone/locale without contract.
- Roundtrip loses many fields.

---

## 5. Architecture Decision

### Decision 1 — Separate three concepts

The app must distinguish:

1. **Backup/restore**
   - Full-fidelity app state.
   - May include DB, assets, source-specific entities, receipt files, bank/email metadata.
   - Requires restore lifecycle and likely hard restart.

2. **Accounting export**
   - Stable read-only output for external accounting/reporting.
   - Does not imply re-import fidelity.
   - Prioritizes correctness, snapshot semantics, timezone/currency clarity.

3. **Expense import/export**
   - User-facing CSV/JSON file exchange.
   - Supports a defined subset of expense fields.
   - Must declare unsupported/lossy fields before import.

### Decision 2 — Use schema versioned contracts

Define explicit formats:

```text
cost-export-json-v1
cost-import-json-v1
cost-import-csv-v1
accounting-export-v1
```

Future incompatible changes require `v2`.

### Decision 3 — Importers are pure parsers

CSV/JSON importer classes must not mutate DB.

They only produce:

```text
ParsedImportFile
ParsedImportRow
ImportValidationIssue
```

Only `ImportCoordinator` may write DB.

### Decision 4 — Source-specific rows are not restored by normal import

If a JSON export row says source was `BANK`, `EMAIL`, `RECEIPT`, `GROUP`, etc., normal import must not pretend it restored full bank/email/receipt/group source entities unless the contract supports those source entities.

Default rule:

- original source is preserved as metadata,
- imported expense source becomes `BULK_IMPORT` / `CSV_IMPORT` / `JSON_IMPORT`,
- original source links are either:
  - imported as safe metadata source links, or
  - rejected with loss report,
  - never silently ignored.

### Decision 5 — Loss must be explicit

Import preview must show:

- fields imported,
- fields preserved as metadata,
- fields ignored,
- fields rejected,
- fields requiring backup/restore instead.

No silent loss of financially/accounting-relevant data.

---

## 6. Non-Negotiable Invariants

After this plan:

- [ ] Import/export contract is documented before code patching.
- [ ] Import util path is promoted into lifecycle-owned coordinator or hidden/disabled.
- [ ] Importers do not directly call DAOs.
- [ ] Import checks read/write barrier before mutation.
- [ ] Import has operation run, file hash, batch ID, row ledger, and row identity.
- [ ] CSV rows set `fileImportRunId`, `csvImportBatchId`, `csvRowNumber`, and row fingerprint.
- [ ] Failed row cannot leave category/expense/source-link partial mutations.
- [ ] No hardcoded EUR fallback.
- [ ] Date/time parsing uses explicit timezone contract.
- [ ] CSV parser is streaming and RFC-4180 compliant.
- [ ] Formula-leading values are escaped/rejected.
- [ ] Errors do not expose raw file content.
- [ ] Export schema includes required accounting/money/source fields.
- [ ] Export snapshot semantics are documented and tested.
- [ ] JSON output is valid and fully parsed in tests.
- [ ] Export/import roundtrip behavior is documented by field.

---

# 7. Contract Model

## 7.1 Field Support Levels

Every field must be classified as one of:

| Level | Meaning |
|---|---|
| `REQUIRED` | Import row invalid without it |
| `SUPPORTED_EXACT` | Imported/exported exactly |
| `SUPPORTED_NORMALIZED` | Imported after normalization |
| `PRESERVED_METADATA` | Stored as metadata/source link, not active domain state |
| `DERIVED_ON_IMPORT` | Recomputed during import |
| `UNSUPPORTED_REJECT_ROW` | Row rejected if present/required |
| `UNSUPPORTED_LOSS_REPORT` | Allowed but shown as lost/ignored |
| `BACKUP_RESTORE_ONLY` | Only full backup restore can preserve it |

---

## 7.2 Required Contract Documents

Create:

```text
docs/import-export/IMPORT_EXPORT_PRODUCT_CONTRACT.md
docs/import-export/IMPORT_FIELD_SUPPORT_MATRIX.md
docs/import-export/EXPORT_SCHEMA_V1.md
docs/import-export/CSV_IMPORT_V1.md
docs/import-export/JSON_IMPORT_EXPORT_V1.md
docs/import-export/ACCOUNTING_EXPORT_CONTRACT.md
docs/import-export/IMPORT_LOSS_REPORT_POLICY.md
```

---

## 7.3 Field Support Matrix

Create a table like this:

```md
| Field | Export JSON | Export CSV | Import JSON | Import CSV | Roundtrip | Notes |
```

Mandatory fields to classify:

### Core expense

- `id`
- `amount`
- `currency`
- `date`
- `merchant`
- `description`
- `category`
- `notes`
- `transactionType`
- `paymentMethod`
- `source`
- `createdAt`
- `updatedAt`

### Money/conversion

- `sourceAccountName`
- `originalCurrency`
- `originalAmount`
- `homeCurrency`
- `baseAmount`
- `baseCurrency`
- `exchangeRateUsed`
- `conversionRateUsed`
- `conversionStatus`
- `rateTimestamp`
- `staleRate`
- `missingRate`

### Business/shared

- `businessCategory`
- `businessProject`
- `requiresReceipt`
- `isSharedExpense`
- `isNotMine`
- `sharedAmount`
- `groupId`
- `splitId`
- `payer/payee fields`

### Source/provenance

- `sourceLinks`
- `sourceType`
- `externalId`
- `csvImportBatchId`
- `csvRowNumber`
- `fileImportRunId`
- `originalSource`
- `bankTransactionId`
- `emailMessageHash`
- `receiptId`
- `notificationFingerprint`

### Attachments/receipts

- receipt links
- receipt asset references
- OCR text
- parsed items
- receipt status

### Import metadata

- file hash
- row fingerprint
- parser version
- timezone
- locale
- schema version

---

# 8. Target Architecture

## 8.1 `ImportCoordinator`

Only owner of import DB writes.

Responsibilities:

- acquire operation run,
- check barriers,
- compute file hash,
- assign batch ID,
- stream parse file,
- validate rows,
- produce preview/loss report,
- execute approved import,
- write row ledger,
- call legal repositories/coordinators,
- map conflicts to typed row outcomes,
- sanitize errors,
- rethrow `CancellationException`.

---

## 8.2 Pure Import Parsers

Classes:

- `CsvExpenseParser`
- `JsonExpenseParser`

They may:

- stream input,
- parse rows,
- normalize field names,
- produce parse issues,
- produce raw values only inside in-memory parse result.

They may not:

- call DAOs,
- create categories,
- create expenses,
- write operation rows,
- log raw rows,
- perform lifecycle decisions.

---

## 8.3 `ImportRunLedger`

Tables/entities:

```text
FileImportRun
FileImportRow
FileImportRowIssue
FileImportSourceLink
```

Minimum run fields:

- run ID,
- file hash,
- file type,
- schema version,
- parser version,
- import mode,
- startedAt,
- completedAt,
- status,
- timezone,
- locale,
- row count,
- success count,
- failed count,
- duplicate count,
- skipped count.

Minimum row fields:

- row ID,
- run ID,
- row number,
- row fingerprint,
- status,
- created expense ID if any,
- sanitized issue code,
- source metadata,
- external fingerprint.

---

## 8.4 `ExportCoordinator`

Only owner of export operation.

Responsibilities:

- check read barrier,
- define snapshot/read consistency,
- page/stream output,
- use explicit timezone/locale,
- use export mappers,
- validate full output,
- write operation run,
- sanitize failures,
- handle cancellation.

---

## 8.5 `AccountingExportCoordinator`

Separate from user import/export.

Responsibilities:

- accounting-specific schema,
- deterministic ordering,
- explicit timezone/currency basis,
- global validation,
- streaming output,
- optional snapshot/maintenance mode.

---

# 9. Implementation Phases

---

## Phase 0 — Resolve Import Architecture Contradiction

### Tasks

- [ ] Document canonical status: util import exists but is not production-safe.
- [ ] Decide:
  - promote util import into lifecycle-owned import pipeline, or
  - hide/disable/remove it until rebuilt.
- [ ] Update P12/P18 docs and master tracker.
- [ ] Add temporary guard so UI cannot call unsafe util import directly.
- [ ] Create `docs/import-export/IMPORT_ARCHITECTURE_DECISION.md`.

### Recommendation

Promote parsers, but demote current util `ImportCoordinator` into pure parsing/adapter code if unsafe.

### Acceptance Criteria

- [ ] Import status is unambiguous.
- [ ] Unsafe import path cannot be exposed as production feature.

---

## Phase 1 — Product Contract and Field Matrix

### Tasks

- [ ] Define import/export product goals.
- [ ] Define CSV v1 columns.
- [ ] Define JSON v1 schema.
- [ ] Define accounting export v1 schema.
- [ ] Build field support matrix.
- [ ] Classify every MIT-080 field.
- [ ] Decide source-specific behavior.
- [ ] Decide receipt/source-link behavior.
- [ ] Decide money/conversion behavior.
- [ ] Decide shared/group behavior.
- [ ] Define loss report policy.
- [ ] Review contract before code changes.

### Acceptance Criteria

- [ ] Every exported field has an import decision.
- [ ] Every unsupported field has user/developer-visible behavior.
- [ ] Backup/restore-only fields are clearly marked.

---

## Phase 2 — Import Lifecycle Architecture

### Tasks

- [ ] Build `ImportCoordinator`.
- [ ] Add operation run.
- [ ] Add file hash.
- [ ] Add batch ID.
- [ ] Add row ledger.
- [ ] Add row fingerprint.
- [ ] Add cancellation-safe execution.
- [ ] Add read/write barrier checks.
- [ ] Add duplicate-action protection.
- [ ] Add sanitized diagnostics.
- [ ] Add import preview before mutation.

### Acceptance Criteria

- [ ] Import has durable lifecycle state.
- [ ] Import can be retried/idempotent by file/row.
- [ ] Import mutations are blocked during restore/reset.

---

## Phase 3 — Make Importers Pure Parsers

### Tasks

- [ ] Remove DAO dependencies from CSV/JSON importers.
- [ ] Move category creation to coordinator/repository.
- [ ] Move expense creation to lifecycle owner.
- [ ] Parsers return typed parsed rows and parse issues.
- [ ] Add static guard against DAO usage in importers.
- [ ] Add parser-only unit tests.

### Acceptance Criteria

- [ ] CSV/JSON importer classes cannot mutate DB.
- [ ] Failed parse cannot create side effects.

---

## Phase 4 — CSV Import v1 Safety

### Tasks

- [ ] Use streaming RFC-4180 parser.
- [ ] Add file size limit.
- [ ] Add row count limit.
- [ ] Add column count limit.
- [ ] Support quoted fields/newlines/escaped quotes.
- [ ] Define header requirements.
- [ ] Parse dates using declared timezone or UTC.
- [ ] Reject/escape formula-leading values:
  - `=`
  - `+`
  - `-`
  - `@`
  - tab/control prefix.
- [ ] Remove hardcoded EUR fallback.
- [ ] Require currency or use explicit import setting with preview warning.
- [ ] Set `csvImportBatchId`.
- [ ] Set `csvRowNumber`.
- [ ] Compute row fingerprint.
- [ ] Sanitize row errors.

### Acceptance Criteria

- [ ] CSV import cannot cause memory blowup, timezone drift, formula injection, or silent EUR defaults.

---

## Phase 5 — JSON Import/Export v1 Semantics

### Tasks

- [ ] Define JSON schema.
- [ ] Include schema version.
- [ ] Include export metadata.
- [ ] Include explicit timezone.
- [ ] Include currency/conversion fields.
- [ ] Include sourceLinks array with safe schema.
- [ ] Include receipt link fields if supported.
- [ ] Include shared/not-mine fields.
- [ ] Validate full JSON output by parsing it in tests.
- [ ] Avoid manual unsafe JSON string concatenation.
- [ ] Treat original `source` as metadata unless source entity restore exists.
- [ ] Reject or loss-report unsupported source-specific rows.
- [ ] Preserve date-only rows without timestamp drift.

### Acceptance Criteria

- [ ] Exported JSON is valid.
- [ ] Importing JSON cannot create invalid source provenance.
- [ ] Date-only/timestamp semantics are stable.

---

## Phase 6 — Source Links and Provenance

### Tasks

- [ ] Define supported source link types for import.
- [ ] Add import-created source link:
  - file hash,
  - run ID,
  - row number,
  - row fingerprint.
- [ ] For original source links:
  - import as metadata if safe,
  - reject if source entity missing and required,
  - never silently ignore.
- [ ] Implement duplicate-source link-to-existing policy.
- [ ] Add tests for exported source links.
- [ ] Add tests for unsupported bank/email/receipt source links.

### Acceptance Criteria

- [ ] Source provenance is never silently dropped.
- [ ] Imported expenses can be traced to file and row.

---

## Phase 7 — Category Creation and Row Transactions

### Tasks

- [ ] Route category lookup/create through `CategoryRepository` or import-safe owner.
- [ ] Normalize category names.
- [ ] Use transaction for category creation + expense creation + source link + row status.
- [ ] If expense row fails, category creation rolls back unless category already existed.
- [ ] Add row-level failure tests.
- [ ] Add duplicate category tests.

### Acceptance Criteria

- [ ] Failed import row cannot leave stray category.
- [ ] Category cache/normalization/lifecycle rules are respected.

---

## Phase 8 — Export Schema Completeness

### Tasks

- [ ] Add/export:
  - `conversionStatus`,
  - stale-rate/missing-rate info,
  - receipt links,
  - shared/not-mine flags,
  - source links,
  - original/base/home currency fields,
  - business fields,
  - payment method,
  - transaction type.
- [ ] Resolve `amount` vs `effectiveAmount` semantics.
- [ ] Define `amount` as original transaction amount unless contract says otherwise.
- [ ] Define `effectiveAmount` / share amount separately.
- [ ] Update mapper tests to match contract.
- [ ] Add full JSON parse tests.

### Acceptance Criteria

- [ ] Export schema preserves required financial/accounting meaning.
- [ ] Mapper tests agree with documented semantics.

---

## Phase 9 — Export Snapshot and Accounting Correctness

### Tasks

- [ ] Decide snapshot semantics:
  - maintenance/snapshot export, or
  - documented non-snapshot streaming.
- [ ] For accounting export, prefer stable snapshot/transaction if feasible.
- [ ] Stream rows instead of materializing all rows.
- [ ] Use deterministic ordering.
- [ ] Validate globally, not sampled subset only.
- [ ] Use explicit UTC/configured timezone.
- [ ] Document PDF locale/timezone behavior.
- [ ] Recheck read barrier before category/source reads.
- [ ] Re-throw `CancellationException`.
- [ ] Add export operation ledger.

### Acceptance Criteria

- [ ] Export is deterministic under documented conditions.
- [ ] Accounting export validates all rows.
- [ ] Export does not OOM on large datasets.

---

## Phase 10 — Loss Report and Import Preview

### Tasks

- [ ] Build import preview output:
  - total rows,
  - valid rows,
  - invalid rows,
  - duplicate rows,
  - unsupported fields,
  - lossy fields,
  - source-specific unsupported rows.
- [ ] Show exact field names, not raw sensitive values.
- [ ] Require user confirmation if lossy import.
- [ ] Save sanitized loss report to run ledger.
- [ ] Add tests for loss report.

### Acceptance Criteria

- [ ] User/developer knows what will be lost before import.
- [ ] No raw file content appears in loss report.

---

## Phase 11 — Idempotency and Conflict Handling

### Tasks

- [ ] Use import mode:
  - `BULK_IMPORT`
  - `STRICT_EXTERNAL_ID`
  - `PREVIEW_ONLY`
  - `ALLOW_DUPLICATES`
- [ ] Define duplicate behavior per mode.
- [ ] Enforce file/row uniqueness in DB.
- [ ] Map DB conflicts to row-level duplicate outcomes.
- [ ] Re-import same file should be idempotent by default.
- [ ] Add tests:
  - same file twice,
  - same row fingerprint different file,
  - duplicate external ID,
  - duplicate source link,
  - concurrent import.

### Acceptance Criteria

- [ ] Duplicate races are DB-prevented and user-visible.
- [ ] Re-import does not create accidental duplicates.

---

## Phase 12 — UI Integration Contract

### Tasks

- [ ] UI must call `ImportCoordinator`, not util importers.
- [ ] UI must show preview/loss report before mutation.
- [ ] Disable duplicate import/export taps.
- [ ] Show cancellable progress where supported.
- [ ] User cancellation maps to typed cancelled state.
- [ ] Snackbar/errors sanitized.
- [ ] Import/export blocked during restore/restart-required.
- [ ] Add UI tests.

### Acceptance Criteria

- [ ] UI cannot trigger unsafe import/export path.
- [ ] Duplicate taps/cancellation cannot corrupt operation state.

---

# 10. Testing Strategy

## 10.1 Contract Tests

- [ ] Every field in support matrix has a test or explicit unsupported assertion.
- [ ] CSV v1 required columns tested.
- [ ] JSON v1 schema tested.
- [ ] Accounting export schema tested.
- [ ] Loss report tested.

## 10.2 Parser Tests

CSV:

- [ ] quoted fields,
- [ ] commas in quoted fields,
- [ ] newlines in quoted fields,
- [ ] escaped quotes,
- [ ] malformed rows,
- [ ] missing headers,
- [ ] large file streaming,
- [ ] formula-leading values,
- [ ] timezone/date cases.

JSON:

- [ ] null fields,
- [ ] unknown fields,
- [ ] missing required fields,
- [ ] source links,
- [ ] receipt links,
- [ ] multipage export,
- [ ] date-only rows,
- [ ] numeric timestamp rows.

## 10.3 Lifecycle Tests

- [ ] import blocked during restore,
- [ ] import blocked during restart-required,
- [ ] cancellation rethrows and records cancelled state,
- [ ] failed row rollback,
- [ ] duplicate action rejected,
- [ ] operation run terminal state correct.

## 10.4 Roundtrip Tests

For supported fields:

- [ ] export -> import -> export stable enough by contract.
- [ ] supported exact fields match.
- [ ] normalized fields match normalized value.
- [ ] unsupported fields appear in loss report.

## 10.5 Export Tests

- [ ] full JSON parse,
- [ ] sourceLinks JSON valid,
- [ ] no malformed embedded source-link content,
- [ ] explicit timezone output,
- [ ] global validation,
- [ ] streaming large dataset,
- [ ] snapshot/non-snapshot behavior.

---

# 11. Static Guards Required

- [ ] Importer DAO usage guard.
- [ ] UI direct util importer call guard.
- [ ] Import mutation without coordinator guard.
- [ ] Hardcoded EUR fallback guard.
- [ ] System timezone import/export guard.
- [ ] Naive CSV parser usage guard.
- [ ] Raw file content in error/log guard.
- [ ] Manual unsafe JSON concatenation guard.
- [ ] Export schema field contract drift guard if feasible.
- [ ] Broad `catch(Exception)` in import/export guard.

---

# 12. Rollout PR Plan

## PR 1 — Architecture Decision and Contract Docs

Includes:

- import contradiction resolution,
- product contract,
- field matrix,
- schema v1 drafts.

Acceptance:

- [ ] Import/export behavior is unambiguous before code changes.

---

## PR 2 — Import Coordinator Skeleton

Includes:

- operation run,
- file hash,
- batch ID,
- row ledger,
- barrier checks,
- preview-only mode.

Acceptance:

- [ ] Import has lifecycle owner but may not yet support all fields.

---

## PR 3 — Pure CSV/JSON Parsers

Includes:

- remove DAO writes from importers,
- streaming CSV parser,
- JSON parser schema validation,
- parser tests.

Acceptance:

- [ ] Parsers are side-effect-free.

---

## PR 4 — CSV Provenance and Idempotency

Includes:

- `fileImportRunId`,
- `csvImportBatchId`,
- `csvRowNumber`,
- row fingerprint,
- import source links,
- duplicate row behavior.

Acceptance:

- [ ] Valid CSV rows pass provenance validation and are idempotent.

---

## PR 5 — Category/Expense Row Transaction

Includes:

- category legal owner,
- row transaction,
- source link write,
- rollback tests.

Acceptance:

- [ ] Failed row leaves no stray category/expense/source link.

---

## PR 6 — JSON Source/Receipt/Shared Semantics

Includes:

- original source as metadata,
- sourceLinks import/unsupported behavior,
- receipt/shared/not-mine field handling,
- loss report.

Acceptance:

- [ ] JSON import does not silently lose source-specific meaning.

---

## PR 7 — Export Schema Completeness

Includes:

- conversion status,
- receipt links,
- shared/not-mine,
- source links,
- amount/effectiveAmount contract,
- full JSON parse tests.

Acceptance:

- [ ] Export schema is contract-complete.

---

## PR 8 — Accounting Export Hardening

Includes:

- snapshot decision,
- streaming,
- global validation,
- timezone/locale policy,
- operation ledger.

Acceptance:

- [ ] Accounting export is deterministic under documented conditions.

---

## PR 9 — UI Integration

Includes:

- preview/loss report UI,
- duplicate action protection,
- cancellation state,
- sanitized errors.

Acceptance:

- [ ] UI cannot call unsafe import path.

---

## PR 10 — Static Guards and Final Regression Suite

Includes:

- import/export guards,
- roundtrip tests,
- large-file tests,
- restore/cancellation tests,
- tracker updates.

Acceptance:

- [ ] MIT-047, MIT-048, MIT-055, MIT-072, MIT-073, MIT-080 can close.

---

# 13. Edge Cases

## JSON row has source `BANK`

Expected:

- original source preserved as metadata,
- imported expense source becomes import source,
- bank transaction entity not recreated unless contract supports it,
- user sees loss report if bank-specific fields are unsupported.

## CSV row has no currency

Expected:

- reject row or require explicit import-level default selected by user,
- no silent EUR fallback.

## User imports same file twice

Expected:

- second import produces duplicate/idempotent row results by default.

## Export happens while user edits expenses

Expected:

- either snapshot prevents drift,
- or non-snapshot behavior is documented and visible.

## Formula-leading merchant field

Expected:

- reject or store escaped safe value,
- later export cannot trigger spreadsheet formula injection.

---

# 14. Metrics

| Metric | Target |
|---|---|
| Importer DAO calls | 0 |
| UI direct importer calls | 0 |
| Import rows without run/batch/row ID | 0 |
| Hardcoded EUR fallbacks | 0 |
| System-timezone date parsing without contract | 0 |
| Raw file content in errors | 0 |
| Unsupported fields silently dropped | 0 |
| Export JSON parse failures | 0 |
| Roundtrip contract failures | 0 |
| Duplicate import race failures | 0 |

---

# 15. Definition of Done by MIT

## MIT-047

- [ ] ImportCoordinator owns all DB mutations.
- [ ] Import run/row ledger exists.
- [ ] CSV provenance fields populated.
- [ ] Category creation goes through legal owner.
- [ ] Import is barrier-safe and idempotent.

## MIT-048

- [ ] CSV parser is streaming/RFC-4180 safe.
- [ ] JSON source semantics defined.
- [ ] No hardcoded EUR fallback.
- [ ] Date/timezone contract implemented.
- [ ] Formula injection prevented.
- [ ] Errors sanitized.

## MIT-055

- [ ] Export snapshot/non-snapshot semantics documented.
- [ ] Accounting export streams and validates globally.
- [ ] Timezone/locale contract implemented.
- [ ] Export operation ledger exists.

## MIT-072

- [ ] Export includes conversion status, receipt links, shared/not-mine flags, source links.
- [ ] Full JSON parse tests pass.
- [ ] `amount` vs `effectiveAmount` semantics resolved.

## MIT-073

- [ ] P12/P18 import contradiction resolved in docs.
- [ ] Unsafe util import path promoted or hidden.
- [ ] Tracker links updated.

## MIT-080

- [ ] Field support matrix exists.
- [ ] Every exported field has import behavior.
- [ ] Unsupported fields produce loss report/reject behavior.
- [ ] Roundtrip tests cover supported fields.

---

# 16. Final Completion Checklist

This plan is complete when:

- [ ] Import/export architecture decision is documented.
- [ ] Field support matrix exists.
- [ ] CSV/JSON/accounting schemas are versioned.
- [ ] ImportCoordinator owns lifecycle and DB writes.
- [ ] Parsers are pure and streaming-safe.
- [ ] Import run/row provenance is durable.
- [ ] Category/expense/source-link writes are transactional.
- [ ] Source-specific semantics are not faked.
- [ ] Export schema is complete.
- [ ] Accounting export is deterministic and scalable.
- [ ] Loss report is implemented.
- [ ] UI uses coordinator/preview/cancellation states.
- [ ] Static guards block unsafe paths.
- [ ] Roundtrip/export/import tests pass.
- [ ] Master tracker is updated with closing SHAs.

---

# 17. Recommended First Action

Start with:

```text
PR 1 — Import/Export Architecture Decision and Field Contract
```

Do not patch the current util importers first.

The first code PR should only come after the product contract answers:

- what CSV supports,
- what JSON supports,
- what export means,
- what import means,
- what roundtrip guarantees exist,
- what must be backup/restore only.