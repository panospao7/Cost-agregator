# Universal issues implementation plan — HEAD `c424274`

## Executive order

### Hotfix first
1. **U9 import/export roundtrip regression**
2. **U1/U2 running-worker barrier hardening**
3. **U6/U3 email lifecycle unification + privacy closure**
4. **U10 timestamps/conflict semantics**
5. **U5 transaction mutation-path audit**
6. **U7 recurring closure audit**
7. **U8 diagnostics normalization + docs sync**
8. **U4 verification-only pass**

Why this order:
- **U9** is an active user-facing regression now.
- **U1/U2/U10** are data-integrity risks.
- **U6/U3** are split-owner/privacy risks.
- **U5/U7/U8** are closure passes once the foundations are stable.

---

## PR0 — Hotfix the CSV v2 roundtrip regression
**Priority:** Critical  
**Files:**
- `util/ImportCoordinator.kt`
- `util/CsvExpenseImporter.kt`
- `ui/screens/export/ExportOptionsViewModel.kt`
- `test/.../ExportImportRoundtripTest.kt`

### Changes
1. In `ImportCoordinator.detectFormat()`:
   - skip BOM, blank lines, and comment lines starting with `#`
   - detect format from the **first non-comment line**
   - distinguish:
     - `CSV_LEGACY`
     - `CSV_FULL`
     - `JSON_V1`
     - `JSON_V2`

2. In `CsvExpenseImporter`:
   - stop using fixed legacy column positions as the only parser
   - add a header-driven parser:
     - `parseLegacyRow(headerMap, row)`
     - `parseFullRow(headerMap, row)`
   - support the full exported header:
     - `ID,Date,CreatedAt,Merchant,Amount,EffectiveAmount,...`
   - ignore comment lines anywhere before header

3. Add **real** roundtrip tests:
   - CSV v2 export -> import
   - JSON v2 export -> import
   - CSV with metadata comment line
   - duplicate re-import is idempotent
   - business fields survive JSON v2 roundtrip

### Done when
- An app-generated CSV v2 file imports without manual editing.
- `ExportImportRoundtripTest` is no longer a DTO smoke test only.

---

## PR1 — U1/U2 restore barrier + worker hardening
**Priority:** Critical  
**Files:**
- `domain/workers/WorkerExecutionGuard.kt`
- `data/location/LocationBackfillWorker.kt`
- `data/privacy/DataRetentionWorker.kt`
- `service/receiptmatching/ReceiptMatchingWorker.kt`
- other workers with loops/batches

### Gaps to close
- `checkpoint()` comment says it checks cancellation, but the current implementation only re-checks writes.
- long-running workers are not using checkpoints consistently.

### Changes
1. Redesign `runGuarded()` to provide a small worker scope:
   - `checkpoint(operation)`
   - metric reporting (`rowsScanned`, `rowsUpdated`, `notificationsSent`)
2. Make `checkpoint()` do both:
   - `writeBarrier.checkWritesAllowed(operation)`
   - coroutine cancellation check (`ensureActive()` / equivalent)
3. Insert checkpoints:
   - before each DAO write
   - between batch fetches
   - after expensive external calls
4. Feed `WorkerRunLogger` real metrics from workers.
5. Add integration tests where restore mode flips **mid-run**.

### Done when
- no worker can continue mutating after restore mode flips, beyond the next checkpoint.
- `BackgroundJobRun` rows contain meaningful counts.

---

## PR2 — U6/U3 email single-owner lifecycle + privacy closure
**Priority:** Critical/Large  
**Files:**
- `data/email/EmailReceiptIngestionService.kt`
- `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`
- `domain/privacy/RawContentSanitizer.kt`
- `domain/privacy/PrivacySettings.kt`
- `data/privacy/PrivacySettingsRepositoryImpl.kt`
- `data/privacy/DataRetentionWorker.kt`

### Changes
1. **Make the coordinator the only owner**
   - thin `EmailReceiptIngestionService` to:
     - detect provider
     - parse provider email into structured `EmailReceiptData`
     - compute fingerprint
     - delegate to `ReceiptLifecycleCoordinator.processEmailReceipt(...)`
   - remove inline receipt save / expense create / link logic from the service

2. **Preserve current behavior while moving ownership**
   - move any useful `processReceiptUseCase` / categorization / notes-building logic into the coordinator
   - do not regress category inference or item-based notes

3. **Fix message ID privacy**
   - stop storing raw `emailMessageId`
   - store `emailMessageIdHash` (or equivalent deterministic hash)
   - switch dedup lookups to hash-based methods
   - also stop using raw `sourceFingerprint = messageId`

4. **Add explicit email privacy policy**
   - add `rawEmailStorageMode`
   - optionally add `rawEmailRetentionDays`
   - keep email separate from OCR policy

5. **Finish retention coverage**
   - extend `DataRetentionWorker` to purge:
     - email source raw fields
     - AI artifacts
     - AI chat messages
     - debug/service diagnostics as planned

6. **Normalize insert conflicts**
   - no unchecked `insertOrIgnore()`
   - wrap it in a helper/repository method that resolves `-1` into duplicate or failure explicitly

7. **Confidence routing**
   - low-confidence email parses go to review/pending-review instead of auto-creating approved expenses

### Tests
- duplicate by message ID hash
- duplicate by content fingerprint
- duplicate by semantic fingerprint
- low-confidence -> review
- side effects fire post-commit
- restore-blocked path
- privacy modes redact/null/hash as expected

### Done when
- there is only one end-to-end email receipt owner.
- no raw message IDs persist.
- retention worker covers the remaining raw/privacy artifacts.

---

## PR3 — U10 timestamps + conflict semantics
**Priority:** High  
**Files:**
- receipt lifecycle files
- bank connection completion path
- recurring CRUD path
- any DAO wrappers using `insertOrIgnore` / `REPLACE`

### Changes
1. Create a shared timestamp policy:
   - `createdAt = existing.createdAt ?: now`
   - never persist `createdAt == 0L`
2. Audit all entity creation paths for:
   - `ScannedReceipt`
   - bank connection entities
   - recurring rules/occurrences
   - any forecast/import-created entities
3. Fix the tracker-known bank gap:
   - `completeConnection()` must `dao.insert()` and stamp `createdAt/updatedAt`
4. Add a shared conflict wrapper:
   - `InsertOutcome.Created`
   - `InsertOutcome.Duplicate(existingId)`
   - `InsertOutcome.Error`
5. Add CI grep guardrails:
   - unchecked `insertOrIgnore(`
   - direct `createdAt = 0L`
   - direct `ExpenseDao` writes outside approved lifecycle owners

### Done when
- no persisted business entity reaches the DB with `createdAt == 0`.
- conflict behavior is explicit, not ad hoc.

---

## PR4 — U5 transaction lifecycle closure audit
**Priority:** High  
**Files:** repo-wide audit of `ExpenseDao` callsites

### Changes
1. Inventory every write path to expenses:
   - create
   - update
   - bulk update
   - delete
2. For each non-test direct caller:
   - migrate to `TransactionLifecycleCoordinator`
   - or document why it is intentionally lower-level
3. Verify side-effect parity:
   - events
   - dedupe
   - recurring link/relink
   - aggregate recalculation

### Grep targets
- `expenseDao.insert`
- `expenseDao.update`
- `expenseDao.delete`
- direct DAO injection into ViewModels/services

### Done when
- expense mutations have one approved owner path.

---

## PR5 — U7 recurring closure audit
**Priority:** High  
**Main tracker-adjacent gaps still worth closing**
- recurring CRUD bypasses lifecycle/events
- expense->occurrence linking not globally guaranteed
- PAID occurrence downgrade risk
- reminder-window default behavior
- occurrence-key collision risk (deferred but should be re-evaluated)

### Changes
1. route recurring rule CRUD through one lifecycle service
2. ensure every expense creation/update path eventually triggers recurring reconciliation
3. harden materialization so PAID cannot be downgraded
4. make reminder generation behavior explicit even with empty windows
5. re-evaluate whether `occurrenceKey` now needs `sourceType`

### Done when
- planned/actual/reminder state remains stable no matter which expense path created/updated the actual.

---

## PR6 — U9 export completeness/privacy/snapshot hardening
**Priority:** High  
**Files:**
- `ExportOptionsViewModel.kt`
- export repository/pager files
- `ExportTransaction` / exporters
- PDF/accounting export path

### Changes
1. **Snapshot anchor**
   - freeze export to an anchor at start (`asOf` timestamp or max cursor)
   - page queries must stay bounded to that anchor
2. **Wire actual encryption**
   - `generateExport(encryptExport = true)` must call the encryption path
   - add UI/settings toggle
3. **Field coverage audit**
   - confirm exports include remaining fields the tracker still flags
   - add receipt-link metadata if missing
4. **Mixed-currency PDF**
   - totals by currency, or convert via base/home currency
   - never raw-sum mixed currencies
5. **Security follow-up**
   - close the importer TODO for hardened CSV cell handling if still needed

### Done when
- exports are deterministic, privacy-aware, and not schema-incomplete.

---

## PR7 — U8 diagnostics normalization + docs reconciliation
**Priority:** Medium  
**Changes**
1. define a shared event taxonomy:
   - `pipeline`
   - `stage`
   - `outcome`
   - `reason`
   - `entityType/entityId`
2. make email/import/export/bank/worker/recurring paths emit the same shape
3. update the master tracker only **after code + tests are green**
4. annotate stale items that were superseded by code

### Done when
- the docs match HEAD, and diagnostic events are queryable across pipelines.

---

## U4 money/currency
I would treat this as **verification-only**, not a refactor target right now.

### Add tests for
- original/home/base currency propagation
- exchange-rate audit fields in exports
- mixed-currency totals
- timezone/canonical-period boundaries

---

## Suggested test files to add
- `ImportCoordinatorFormatDetectionTest`
- `CsvExpenseImporterFullSchemaTest`
- `ExportImportRoundtripIntegrationTest`
- `WorkerExecutionGuardCheckpointTest`
- `RestoreMaintenanceModeWorkerIntegrationTest`
- `EmailReceiptLifecycleIntegrationTest`
- `EmailPrivacyRetentionTest`
- `TimestampPolicyIntegrationTest`
- `RecurringLifecycleReconciliationTest`

---

## Exit criteria by universal contract

- **U1:** restore-sensitive reads/writes guarded; active workers stop mutating after checkpoint
- **U2:** all workers use guarded scope + metrics
- **U3:** explicit email privacy mode; no raw message IDs; retention complete
- **U4:** audit tests green
- **U5:** expense write inventory closed
- **U6:** one email lifecycle owner
- **U7:** recurring reconciliation stable across all create/update paths
- **U8:** shared diagnostics taxonomy in use
- **U9:** real CSV/JSON roundtrip green; snapshot/encryption fixed
- **U10:** no zero `createdAt`; no unchecked insert conflicts

---

## Sources
- Architecture:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/architecture/ARCHITECTURE.md
- Master tracker:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Strategy doc:  
  https://github.com/panospao7/Cost-agregator/blob/master-refactor/docs/analyses%20and%20debug%20master/new%20debugging%20session/response%20%283%29.md
- HEAD commit:  
  https://github.com/panospao7/Cost-agregator/commit/c4242742dd49d83ab1fc4996f87b85b668639926
- Key code inspected:
  - https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerExecutionGuard.kt
  - https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt
  - https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
  - https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
  - https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/util/ImportCoordinator.kt
  - https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt
  - https://github.com/panospao7/Cost-agregator/blob/master-refactor/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt