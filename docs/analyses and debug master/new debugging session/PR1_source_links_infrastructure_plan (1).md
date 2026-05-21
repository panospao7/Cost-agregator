# PR 1 — Schema + core source-link infrastructure

## Baseline check
- Current DB: `AppDatabase` v35.
- `Expense` still has only `rawNotificationId` as a durable source FK.
- `PendingReview` and `ScannedReceipt` already hold narrow source fields, but there is no generic provenance layer yet.
- Latest visible commit (`6fee004`, May 21, 2026) is docs-only.

## Goal
Add a durable, privacy-safe, queryable source-link substrate that later pipelines can write to without changing business behavior yet.

## Non-goals
- No `TransactionLifecycleCoordinator` wiring yet.
- No review/receipt/bank/import/export/UI changes.
- No backfill worker.
- No removal of legacy columns.
- No `SOURCE_LINKED` event integration yet.

## Files to add
- `app/src/main/java/com/yourname/expensetracker/data/database/entity/EntitySourceLink.kt`
- `app/src/main/java/com/yourname/expensetracker/data/database/dao/EntitySourceLinkDao.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkEnums.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkPayload.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkWriter.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkWriterImpl.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/provenance/SafeProvenanceMetadata.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/provenance/ProvenanceHashingService.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceIdentityKeyFactory.kt`

## Files to modify
- `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`
- Room schema export files under `app/schemas/...`

## Schema contract
`EntitySourceLink` should be TEXT-backed and stable:

- `id`
- `targetEntityType`
- `targetEntityId`
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

### Indices
- `(targetEntityType, targetEntityId)`
- `(sourceType)`
- `(sourceEntityType, sourceEntityLocalId)`
- `(sourceIdentityKey)`
- `(operationRunId)`
- `(correlationId)`
- unique: `(targetEntityType, targetEntityId, sourceIdentityKey)`

### Enum strategy
Do **not** store enums as ordinals. Store string names in TEXT columns.

## Core API design
### `SourceLinkPayload`
Holds the intent to create a link; contains raw inputs and safe metadata, not persistence details.

### `SourceLinkWriter`
Single entry point for future callers. It should:
1. validate payload
2. build deterministic `sourceIdentityKey`
3. hash external IDs / fingerprints
4. validate/redact metadata
5. insert with IGNORE on conflict
6. return a write result, not throw on duplicates

### `SourceLinkWriteResult`
Use something like:
- `Inserted`
- `AlreadyExists`
- `Rejected`

### `SafeProvenanceMetadata`
Fail closed. Reject raw sensitive keys such as:
- raw text/body/subject
- bank description/reference
- tokens/secrets
- file paths
- account/card/iban-like values

Allow only safe summary fields like:
- providerId
- parserVersion
- confidence
- importFormat
- statementPageNumber
- correlationId
- transaction/status flags

### `ProvenanceHashingService`
Keep hashing behind an interface. Prefer a keyed deterministic hash abstraction so tests can stub it and later export/import behavior can evolve cleanly.

### `SourceIdentityKeyFactory`
Canonicalize identities consistently:
- `local:raw_notification:<id>`
- `local:pending_review:<id>`
- `local:scanned_receipt:<id>`
- `import:csv:<batchId>:row:<rowNumber>`
- `external:bank_transaction:<provider>:<hash>`

No plaintext external IDs in persisted provenance fields.

## Migration strategy
- Bump Room version `35 -> 36`.
- Add `MIGRATION_35_36` creating `entity_source_links` and all indices.
- Register `EntitySourceLinkDao` in `AppDatabase`.
- Regenerate schema JSON and commit it.

## Tests
### DAO / entity
- insert/select round-trip
- `exists()` works
- unique index prevents duplicates
- query-by-target and query-by-source work

### Writer
- hashes external IDs
- rejects unsafe metadata
- returns `AlreadyExists` on duplicate insert
- does not open its own transaction

### Migration
- schema migration from 35 to 36 succeeds
- table and indices exist after migration

## Acceptance criteria
- New table exists and is queryable.
- Duplicate source-link inserts are idempotent.
- No raw sensitive external IDs are persisted.
- No pipeline behavior changes yet.
- Room schema export is updated and tests pass.

## Open decisions before coding
- HMAC vs plain digest implementation
- exact allowlist for metadata
- whether `createdBy` is free text or a small enum
- whether `operationRunId` is included in PR1 entity now or just reserved in payload

## Why this PR stays narrow
This PR only creates the substrate. The later PRs should map:
- notification/review
- receipt/email
- bank/import
- recurring/planned
- export/import/backfill

onto this one generic model.

## Sources
- Latest commit: https://github.com/panospao7/Cost-agregator/commit/6fee004aa141878820db9240d751ea22f20c4a52
- Repo home: https://github.com/panospao7/Cost-agregator
- Commit history: https://github.com/panospao7/Cost-agregator/commits/bug-fixes/
- App architecture: https://github.com/panospao7/Cost-agregator/blob/bug-fixes/app/ARCHITECTURE.md
- Comprehensive analysis: https://github.com/panospao7/Cost-agregator/blob/bug-fixes/COMPREHENSIVE_ISSUE_ANALYSIS.md
- Expense entity: https://github.com/panospao7/Cost-agregator/blob/bug-fixes/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt
- AppDatabase: https://github.com/panospao7/Cost-agregator/blob/bug-fixes/app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
- Your attached `global_source_links_provenance_plan.md`