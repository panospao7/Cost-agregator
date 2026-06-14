Here is the **Pipeline 3 — Receipt Capture / OCR / Email** master prompt. I aligned it with the P3 issue registry, master tracker, receipt lifecycle dependency map, codebase segment map, and legal receipt paths. Sources: P3 registry【turn0view0†L0-L, master P3 section, receipt lifecycle dependency map, codebase segment map, legal receipt paths.

<pipeline-3-master-debug-review-prompt.md>
# Master Debug/Review Prompt — Pipeline 3: Receipt Capture / OCR / Email

You are an expert Kotlin/Android architecture debugger and reviewer. Your task is to perform an extensive debug/review of **Pipeline 3 — Receipt Capture / OCR / Email** in this repository.

## 0. Target repository and version

Repository:

```text
https://github.com/panospao7/Cost-agregator
```

Pinned commit:

```text
83b798e849b4408b2bf683f52cb2746d37f7af16
```

Do not review a different branch/commit unless explicitly told. If the local checkout differs, stop and report the mismatch.

## 1. Mission

Review **Pipeline 3 — Receipt Capture / OCR / Email** end-to-end.

Primary goals:

1. Understand the actual runtime flow from receipt capture/import/email/bank statement input to saved receipt, OCR parse, matching, source link, pending review, expense creation, side effects, diagnostics, or terminal failure.
2. Verify that all previously reported P3 issues are truly fixed in code.
3. Find any remaining correctness, privacy, lifecycle, atomicity, restore-safety, matching, side-effect, currency, dedupe, worker, diagnostic, or test gaps.
4. Compare actual code against architecture docs and legal paths.
5. Produce a structured issue report with exact evidence and recommended fixes.

This is a **deep code audit**, not a shallow docs summary.

## 2. Important warning about docs/status drift

Read both:

```text
docs/analyses and debug master/PIPELINE_3_CONSOLIDATED_ISSUES.md
docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md
```

The P3 consolidated file may show older statuses, including partial/open/TODO items. The master tracker may claim later fixes are complete. Do **not** trust either blindly.

Your job is to reconcile:

- P3 consolidated issue doc,
- master tracker,
- universal tracker,
- architecture docs,
- actual source code,
- actual tests.

If tracker and code disagree, report **doc/code drift** or **tracker/status drift**.

## 3. Docs to read first

Read these pipeline/debug docs:

```text
docs/analyses and debug master/PIPELINE_3_CONSOLIDATED_ISSUES.md
docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md
docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md
```

Read these architecture docs:

```text
docs/architecture/ARCHITECTURE.md
docs/architecture/CODEBASE_SEGMENTS.md
docs/architecture/DEPENDENCY_MAP.md
docs/architecture/LEGAL_PATHS.md
docs/architecture/ENGINE_INTERACTION_MAP.md
docs/architecture/COMPLETE-BACKEND-MAP.md
docs/architecture/BACKEND-MAP-INDEX.md
docs/architecture/CODEBASE_INVENTORY.md
docs/architecture/dao-map.md
docs/architecture/hilt-bindings-map.md
docs/architecture/import-graph.json
```

Read these cross-cutting docs if present:

```text
docs/DB_WRITE_OWNERSHIP.md
docs/expense-mutation-inventory.md
docs/DATABASE_BASELINE_POLICY.md
docs/backup-restore-barrier-contract.md
docs/SENSITIVE_DIAGNOSTICS_POLICY.md
docs/PRIVACY_UI_ARCHITECTURE.md
```

If any listed doc does not exist, note it and continue.

## 4. Pipeline definition

Pipeline 3 covers **Receipt Capture / OCR / Email / Bank Statement Receipt Lifecycle**:

```text
Receipt source:
  camera / gallery / file / PDF / email / bank statement image
→ input validation
→ restore/write barrier
→ asset persistence and hash/fingerprint
→ OCR extraction if needed
→ raw-content privacy sanitization/redaction
→ receipt parsing
→ merchant/date/amount/currency/document-type extraction
→ duplicate detection
→ atomic ScannedReceipt insert/update
→ ReceiptEvent lifecycle log
→ pending review creation if needed
→ receipt-to-transaction matching
→ receipt-expense link/unlink
→ optional expense creation through TransactionLifecycleCoordinator
→ post-commit receipt side effects
→ diagnostics / final lifecycle result
```

Expected terminal outcomes include:

- receipt captured/saved,
- OCR completed,
- parse failed,
- duplicate skipped/resolved,
- pending review created,
- match suggested,
- match approved,
- match rejected,
- match not found,
- auto-match skipped,
- receipt linked/unlinked,
- receipt-created expense committed,
- receipt deleted,
- privacy blocked,
- restore/write barrier blocked,
- side-effect skipped/failed,
- validation failed,
- worker retry/failure,
- diagnostic emitted.

## 5. Architecture expectations / legal paths

The required architecture path for receipt processing is:

```text
PROCESS receipt:
source input
→ ReceiptLifecycleCoordinator.processReceiptInput()
→ ReceiptRepository.processReceipt() for OCR/parse-only draft behavior if applicable
→ coordinator owns insert + metadata + fingerprints + ReceiptEvent + post-commit side effects
```

The required architecture path for creating an expense from a receipt is:

```text
CREATE expense FROM receipt:
ReceiptLifecycleCoordinator.createExpenseFromReceipt()
→ database.withTransaction {
      TransactionLifecycleCoordinator.createExpense(..., sideEffectMode = DEFER)
      ReceiptLinkService.linkReceiptToExpense(...)
  }
→ link failure must rollback if link is required
→ post-commit side effects only after transaction commit
```

The required architecture path for link/unlink is:

```text
LINK/UNLINK receipt:
ReceiptLinkService.linkReceiptToExpense()
ReceiptLinkService.unlinkReceiptFromExpense()
→ write barrier
→ database transaction
→ receipt_expense_links join table
→ legacy matchedExpenseId/expenseId field if maintained
→ ReceiptEvent
→ downstream receipt item / warranty / return-window updates if applicable
```

The required architecture path for matching is:

```text
MATCH receipt:
ReceiptMatchLifecycleService.saveMatchSuggestion()
ReceiptMatchLifecycleService.approveMatchSuggestion()
ReceiptMatchLifecycleService.rejectAllSuggestions()
ReceiptMatchLifecycleService.clearMatchForReceipt()
→ write barrier
→ database.withTransaction
→ ScannedReceipt match state update
→ ReceiptEvent
```

Forbidden unless explicitly documented, guarded, and tested:

```text
ScannedReceiptDao.insert() outside lifecycle owner
ReceiptRepository.linkReceiptToExpense() if deprecated bypass
Direct ScannedReceipt.expenseId or matchedExpenseId updates
Receipt mutation without ReceiptEvent
Receipt-created ExpenseDao insert outside TransactionLifecycleCoordinator
Source link / receipt link written outside the owning transaction when atomicity is required
Raw OCR/email text logged or stored contrary to privacy policy
```

## 6. Initial source file inventory

Start with these production files, then expand using `rg`, imports, callers, and tests. Do not assume this list is complete.

### Receipt domain

```text
app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/EmailReceiptData.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/EnhancedMerchantExtractor.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/MerchantRulesPolicy.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/OcrLanguageProcessor.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/OcrPreprocessingPipeline.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptDocumentType.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptProcessingStatus.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptSource.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptSourceType.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/WarrantyTextExtractor.kt
```

### Receipt lifecycle

```text
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/EmailReceiptProcessResult.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptAssetStore.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptDuplicateDetector.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptInputValidator.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleEventTypes.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectInput.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectPlanner.kt
app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptTimestampPolicy.kt
```

### Receipt matching

```text
app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt
app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt
```

### Receipt repositories

```text
app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRecordWriter.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptInsertResolver.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptItemCategorizationRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/ExpenseRepository.kt
app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt
```

### Email ingestion cross-pipeline files

Pipeline 11 owns broader email ingestion, but P3 must audit email receipt handoff into receipt lifecycle:

```text
app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
app/src/main/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt
app/src/main/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt
app/src/main/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt
app/src/main/java/com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt
```

### Transaction lifecycle cross-pipeline files

Receipt-created expenses must route through transaction lifecycle:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseResult.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseSource.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/DeduplicationMode.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/SideEffectMode.kt
```

### Side-effect framework

```text
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunner.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionRunnerImpl.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/PostCommitActionBatch.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/MutationResult.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/SideEffectTriggerType.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/SideEffectPriority.kt
app/src/main/java/com/yourname/expensetracker/domain/sideeffect/SideEffectCategory.kt
```

### Receipt side-effect consumers to trace

Search and inspect concrete locations for:

```text
AutoCreateWarrantyFromReceiptUseCase
ReceiptItemCategorizationService
CategorizeReceiptItemsUseCase
ReceiptTransactionMatcher
PriceProtectionTracker
WarrantyDao
ReturnWindowDao
ReceiptItemCategorizationDao
```

### DAOs

```text
app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptExpenseLinkDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptItemCategorizationDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/EntitySourceLinkDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/PipelineDiagnosticEventDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/PrivacyAuditDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyDao.kt
app/src/main/java/com/yourname/expensetracker/data/database/dao/ReturnWindowDao.kt
```

### Entities

```text
app/src/main/java/com/yourname/expensetracker/data/database/entity/ScannedReceipt.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptExpenseLink.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/ReceiptItemCategorization.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/PendingReview.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/EntitySourceLink.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/PipelineDiagnosticEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/PrivacyAuditEvent.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/Warranty.kt
app/src/main/java/com/yourname/expensetracker/data/database/entity/ReturnWindow.kt
```

### Database/migrations/schema

```text
app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt
app/src/main/java/com/yourname/expensetracker/data/database/DatabaseMigrations.kt
app/schemas/com.yourname.expensetracker.data.database.AppDatabase/
```

Confirm current DB schema baseline/version and whether receipt tables have required indexes, FKs, conflict strategies, and migration coverage.

### UI/ViewModel entry points

```text
app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt
app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt
app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingScreen.kt
app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModel.kt
app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt
app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt
```

### Hilt/DI

```text
app/src/main/java/com/yourname/expensetracker/di/OcrImprovementsModule.kt
app/src/main/java/com/yourname/expensetracker/di/ReceiptParsingModule.kt
app/src/main/java/com/yourname/expensetracker/di/EmailIngestionModule.kt
app/src/main/java/com/yourname/expensetracker/di/DiagnosticsModule.kt
app/src/main/java/com/yourname/expensetracker/di/PrivacyModule.kt
app/src/main/java/com/yourname/expensetracker/di/DaoModule.kt
app/src/main/java/com/yourname/expensetracker/di/DispatchersModule.kt
```

### Restore/privacy/worker cross-cutting dependencies

Find concrete files for:

```text
DatabaseWriteBarrier
DatabaseReadBarrier
RestoreMaintenanceMode
DatabaseAccessBlockedException
WorkerExecutionGuard
WorkerRunLogger
WorkerRegistry
WorkerLeaseRegistry
PrivacyDecision
RawStorageMode
PrivacySettingsRepository
PrivacyGate
RawContentSanitizer
RedactionSanitizer
CloudPayloadPolicy
DiagnosticEventWriter
ReceiptLifecycleEventWriter
PostCommitActionRunner
TimeProvider
UserCurrencyProvider
CurrencySettingsRepository
CurrencyConverter
```

## 7. Tests to inspect

Start with:

```text
app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinatorTest.kt
app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleBugFixesTest.kt
app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleHardeningTest.kt
app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleServiceTest.kt
app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStatementDuplicateTest.kt
app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStressTest.kt
app/src/test/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorkerTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/CancellationPropagationContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/LifecycleBarrierContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/MoneyContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/PrivacyStorageContractTest.kt
app/src/test/java/com/yourname/expensetracker/contracts/SideEffectContractTest.kt
```

Then search for additional tests:

```bash
rg -n "ReceiptLifecycleCoordinator|ReceiptLinkService|ReceiptMatchLifecycleService|ReceiptSideEffect|ReceiptDuplicateDetector|ReceiptAssetStore|ReceiptInputValidator|ReceiptParser|ReceiptOcrService|ScannedReceipt|ReceiptEvent|ReceiptExpenseLink|ReceiptRepository|ReceiptMatchingWorker|BankStatementLifecycleProcessor|EmailReceiptIngestionService|ReceiptInsertResolver|PARSE_FAILED|OCR_COMPLETED|MATCH_NOT_FOUND|MATCH_APPROVED|MATCH_REJECTED|AUTO_MATCH" app/src/test app/src/androidTest
```

## 8. Required search commands

Run broad searches before finalizing conclusions:

```bash
rg -n "ReceiptLifecycleCoordinator|processReceiptInput|createExpenseFromReceipt|deleteReceipt|ReceiptLinkService|linkReceiptToExpense|unlinkReceiptFromExpense|ReceiptMatchLifecycleService|saveMatchSuggestion|approveMatchSuggestion|rejectAllSuggestions|clearMatch" app/src/main app/src/test app/src/androidTest

rg -n "ScannedReceiptDao\\.|scannedReceiptDao\\.(insert|insertOrIgnore|update|delete|update.*Expense|update.*Match|claimForAutoMatch)|ReceiptEventDao|ReceiptExpenseLinkDao|ReceiptItemCategorizationDao" app/src/main app/src/test app/src/androidTest

rg -n "ReceiptEvent|ReceiptLifecycleEvent|OCR_COMPLETED|PARSE_FAILED|CAPTURED|MATCH_SUGGESTED|MATCH_APPROVED|MATCH_REJECTED|MATCH_CLEARED|MATCH_NOT_FOUND|AUTO_MATCH_LINK_FAILED|DELETED" app/src/main app/src/test

rg -n "DatabaseWriteBarrier|DatabaseReadBarrier|RestoreMaintenanceMode|checkWritesAllowed|checkReadsAllowed|DatabaseAccessBlockedException|maintenance|restore" app/src/main app/src/test

rg -n "withTransaction|database.withTransaction|TransactionLifecycleCoordinator|createExpense\\(|createExpenseStandalone|SideEffectMode.DEFER|PostCommitActionRunner|ReceiptSideEffectPlanner|ReceiptSideEffectDispatcher" app/src/main app/src/test

rg -n "CancellationException|catch \\(e: Exception\\)|runCatching|NonCancellable|SupervisorJob|launch|async|withContext|withTimeout|withTimeoutOrNull|Flow.first" app/src/main/java/com/yourname/expensetracker/domain/receipt app/src/main/java/com/yourname/expensetracker/data/repository app/src/main/java/com/yourname/expensetracker/service/receiptmatching app/src/main/java/com/yourname/expensetracker/data/email app/src/test

rg -n "RawContentSanitizer|RedactionSanitizer|PrivacyDecision|RawStorageMode|DO_NOT_STORE|REDACTED|METADATA|PII|Timber\\.|Log\\.|merchant|category|ocrText|rawText|emailBody|imagePath|assetPath" app/src/main app/src/test

rg -n "homeCurrency|UserCurrencyProvider|CurrencySettingsRepository|CurrencyConverter|EUR|USD|currency fallback|baseAmount|exchangeRate|Money" app/src/main app/src/test

rg -n "dedupe|duplicate|hash|fingerprint|computeUriHash|ReceiptDuplicateDetector|ReceiptInsertResolver|INSERT OR IGNORE|OnConflictStrategy.IGNORE|claimForAutoMatch" app/src/main app/src/test

rg -n "EmailReceiptIngestionService|EmailReceiptParser|AmazonReceiptParser|AppleReceiptParser|UberReceiptParser|EmailReceiptData|EmailReceiptSource" app/src/main app/src/test

rg -n "BankStatementParser|BankStatementLifecycleProcessor|bank statement|statement import|attachReceipt|statement duplicate" app/src/main app/src/test

rg -n "ReceiptMatchingWorker|WorkerExecutionGuard|WorkerRunLogger|WorkerRegistry|runGuarded|RetryableWorkerException|claimForAutoMatch|manual runOnce|periodic" app/src/main app/src/test
```

## 9. Previous P3 issues to verify

Verify each issue from the P3 issue doc and master tracker against actual code.

For each issue, report:

```text
ID
claimed status in P3 doc
claimed status in master tracker
actual status in code
evidence: file + function + line range
test coverage
remaining gap, if any
```

### Old issues

```text
P3-P0-01 — Scanned receipts saved with createdAt = 0
P3-P1-01 — Receipt save/update/event not atomic
P3-P1-02 — ReceiptLinkService lacks restore guard
P3-P1-03 — Matching result computed but not persisted
P3-P1-04 — Receipt-created expense + link not atomic
P3-P1-05 — Direct repository methods bypass lifecycle
P3-P1-06 — ScannedReceiptDao.insert() IGNORE conflict not checked
P3-P1-07 — Currency fallback hardcoded EUR in OCR parse
P3-P1-08 — Parse failures classified as OCR_COMPLETED
P3-P1-09 — Batch receipt import no longer creates pending reviews
P3-P1-10 — Bank statement lifecycle dedupe weaker than legacy
```

### New issues from deep audit

```text
NEW-P3-001 — CancellationException swallowed in ReceiptSideEffectDispatcher
NEW-P3-002 — CancellationException swallowed in BankStatementLifecycleProcessor per-item
NEW-P3-003 — CancellationException swallowed in ReceiptLinkService.unlinkReceiptFromExpense
NEW-P3-004 — Double attachReceipt call in BankStatementLifecycleProcessor
NEW-P3-005 — Race in post-OCR duplicate path
NEW-P3-006 — Privacy leak — merchant/category logged in production
NEW-P3-007 — deleteReceipt writes event for non-existent receipt
NEW-P3-008 — homeCurrency() inside withContext may cause thread starvation
```

## 10. Universal contracts to audit for P3

### Restore/write barrier

Verify:

- `ReceiptLifecycleCoordinator` checks `DatabaseWriteBarrier` before receipt writes.
- `ReceiptLinkService` checks write barrier before link/unlink.
- `ReceiptMatchLifecycleService` checks write barrier before match mutations.
- `BankStatementLifecycleProcessor` checks write barrier before receipt/statement writes.
- `EmailReceiptIngestionService` or its lifecycle handoff respects write barrier.
- `ReceiptMatchingWorker` respects restore/maintenance mode through `WorkerExecutionGuard` or equivalent.
- No receipt write occurs during restore/export/maintenance mode.
- Blocked writes emit durable lifecycle/diagnostic evidence where expected.

### Receipt lifecycle ownership

Verify:

- all receipt insert/update/delete/link/match operations route through lifecycle owner/service,
- direct DAO writes are explicitly legal, test-only, debug-only, or backfill-only,
- every real receipt mutation has a `ReceiptEvent`,
- deprecated repository methods cannot silently bypass lifecycle,
- direct `ScannedReceipt.expenseId` / `matchedExpenseId` mutations are not used illegally.

### Transaction lifecycle integration

Verify receipt-created expenses:

- use `TransactionLifecycleCoordinator`,
- pass correct `ExpenseSource`,
- pass idempotency/dedupe fields,
- link to receipt atomically if required,
- do not call `ExpenseDao.insert()` directly,
- do not dispatch transaction side effects before commit,
- preserve source/provenance and source-link expectations.

### Atomicity / transaction boundaries

Verify:

- receipt insert + event are atomic,
- receipt update + event are atomic,
- receipt delete + event are atomic and only happens if receipt exists,
- receipt-created expense + receipt link are atomic,
- link/unlink + legacy fields + events are atomic,
- match state updates + events are atomic,
- duplicate checks occur inside the same transaction when needed,
- side effects run post-commit only,
- no external/network/file I/O occurs inside Room transaction unless explicitly justified.

### Privacy/redaction/raw storage

Verify:

- raw OCR text, email body, merchant/category, image path, asset path, parser errors, and extracted fields are not logged in production if sensitive,
- OCR/email raw text is sanitized before storage if policy requires,
- privacy fail-closed behavior exists for AI/OCR/receipt-assist paths,
- receipt item AI categorization uses privacy gate / payload policy,
- diagnostics/lifecycle metadata is safe and redacted,
- `DO_NOT_STORE` / redacted modes are respected where applicable,
- image/file assets do not leak outside app-controlled storage.

### Dedupe/idempotency

Verify:

- exact asset hash dedupe,
- OCR text/semantic dedupe,
- receipt insert conflict handling,
- bank statement duplicate detection,
- email duplicate handoff,
- retry/idempotency behavior,
- duplicate receipt does not create duplicate expense/link/review,
- two distinct receipts do not collapse incorrectly.

### Matching lifecycle

Verify:

- auto-match writes durable events for every terminal outcome,
- no-match is persisted,
- suggested/approved/rejected/cleared states are consistent,
- `claimForAutoMatch` or equivalent prevents concurrent double-link,
- manual and periodic matching cannot race into duplicate links,
- link failure during auto-match is durable and safe.

### Worker guard/run logging

Verify:

- `ReceiptMatchingWorker` uses `WorkerExecutionGuard`,
- worker run is logged,
- retry/permanent-failure classification is correct,
- worker respects restore/maintenance mode,
- worker is idempotent,
- worker has bounded scanning and safe concurrency.

### Money/currency

Verify:

- OCR parser does not hardcode EUR except as explicit user/home fallback,
- `UserCurrencyProvider` / `CurrencySettingsRepository` reads have timeout or safe fallback,
- amount parsing avoids precision loss,
- bank statement and email amounts/currencies are normalized,
- receipt-created expense has correct amount/currency/base fields,
- parse failures do not produce fake successful money records.

### Diagnostics/lifecycle events

Verify every terminal path has durable evidence:

- validation failed,
- asset persistence failed,
- OCR failed,
- parse failed,
- duplicate skipped,
- insert conflict,
- receipt captured,
- receipt deleted,
- match attempted,
- match suggested,
- match not found,
- match approved/rejected/cleared,
- link/unlink success/failure,
- receipt-created expense success/failure,
- restore/privacy blocked,
- worker skipped/retry/failure,
- side-effect failed.

### DAO conflict/timestamp handling

Verify:

- `createdAt`, `updatedAt`, `processedAt`, event timestamps are non-zero and consistent,
- `ReceiptTimestampPolicy` is used correctly,
- `OnConflictStrategy.IGNORE` results are checked,
- uniqueness/index constraints match dedupe and query behavior,
- migrations preserve receipt tables and indexes.

## 11. Deep review checklist

### Receipt input/capture

Check:

- camera/gallery/file/PDF URI handling,
- MIME/size validation,
- app-private copy/storage,
- file cleanup on failure,
- lifecycle cancellation handling,
- restore barrier before DB writes,
- asset hash before OCR if used for dedupe.

Questions:

- Can a receipt be accepted but lost?
- Can asset file be saved but DB insert fail with orphaned file?
- Can DB row be saved but asset path invalid?
- Are invalid/oversized/unsupported inputs rejected with event/diagnostic?

### OCR and preprocessing

Check:

- `ReceiptOcrService`,
- `OcrPreprocessingPipeline`,
- `OcrLanguageProcessor`,
- image/PDF handling,
- error classification,
- cancellation propagation,
- privacy of raw OCR text.

Questions:

- Are OCR failures marked as OCR failed / parse failed, not completed?
- Is raw OCR text stored/logged safely?
- Are timeouts/cancellations handled correctly?
- Is preprocessing deterministic and testable?

### Parsing

Check:

- `ReceiptParser`,
- merchant extraction,
- date extraction,
- total/subtotal/tax parsing,
- currency fallback,
- line items,
- document type classification,
- email/bank statement parser handoff.

Questions:

- Can parse failure create a receipt as successful?
- Is confidence propagated to pending review?
- Are amounts precise and currency-aware?
- Are dates/timestamps timezone-safe?

### Duplicate detection

Check:

- `ReceiptDuplicateDetector`,
- `ReceiptInsertResolver`,
- `computeUriHash`,
- text/semantic hash,
- DB unique indexes,
- duplicate resolution status.

Questions:

- Is duplicate check inside transaction?
- Can concurrent scans create duplicates?
- Can duplicate receipt create duplicate pending review or expense?
- Is conflict result checked and represented in lifecycle result?

### Receipt persistence

Check:

- `ReceiptLifecycleCoordinator.processReceiptInput`,
- `ReceiptRecordWriter`,
- `ScannedReceiptDao`,
- `ReceiptEventDao`,
- status transitions,
- event metadata,
- createdAt/updatedAt.

Questions:

- Is receipt insert/update/event atomic?
- Are all status transitions legal?
- Is event written only if mutation actually happened?
- Are missing receipt deletes guarded?

### Expense creation from receipt

Check:

- `createExpenseFromReceipt`,
- transaction lifecycle call,
- receipt link call,
- source/provenance,
- source link,
- side effect deferral,
- rollback on link failure.

Questions:

- Can an expense be created without receipt link?
- Can a receipt link be created for failed expense insert?
- Can source-link failure orphan provenance?
- Are receipt-created expenses deduped/idempotent?

### Link/unlink

Check:

- `ReceiptLinkService.linkReceiptToExpense`,
- `unlinkReceiptFromExpense`,
- join table,
- legacy fields,
- category propagation,
- warranty/return updates,
- events.

Questions:

- Are link/unlink guarded by restore barrier?
- Are link/unlink atomic?
- Are duplicate links idempotent?
- Does unlink remove or update downstream state correctly?
- Are cancellation exceptions rethrown?

### Matching

Check:

- `ReceiptTransactionMatcher`,
- `ReceiptMatchLifecycleService`,
- `ReceiptMatchingWorker`,
- UI approval/rejection,
- auto-match/manual-match overlap.

Questions:

- Is no-match persisted?
- Can two workers match/link the same receipt?
- Does `claimForAutoMatch` prevent overlap?
- Are manual and auto paths consistent?
- Are match scores and chosen expense explainable?

### Bank statement lifecycle

Check:

- `BankStatementParser`,
- `BankStatementLifecycleProcessor`,
- bank statement item dedupe,
- statement receipt attachment,
- expense/review interactions.

Questions:

- Is dedupe stronger than pending-review-only?
- Was double `attachReceipt` removed?
- Are per-item failures isolated without swallowing cancellation?
- Are statement parser currencies and dates safe?

### Email receipt handoff

Check:

- `EmailReceiptIngestionService`,
- provider parsers,
- `EmailReceiptData`,
- lifecycle coordinator call,
- email source/fingerprint,
- privacy/redaction,
- duplicate message/content handling.

Questions:

- Does email path route through receipt lifecycle?
- Does fallback bypass coordinator?
- Are email fields sanitized?
- Can duplicate email create duplicate receipt/expense?
- Are all failure outcomes represented?

### Side effects

Check:

- `ReceiptSideEffectPlanner`,
- `ReceiptSideEffectDispatcher`,
- `PostCommitActionRunner`,
- warranty,
- return windows,
- receipt item categorization,
- price protection,
- matching.

Questions:

- Are side effects post-commit only?
- Are side-effect failures durable but non-corrupting?
- Do side effects use legal write paths?
- Are AI/receipt item categorization paths privacy-gated?
- Are cancellation exceptions propagated?

### UI/ViewModel

Check:

- scan screen state,
- review screen interactions,
- matching screen

:warning: The provider stream ended early, so this response may be incomplete.