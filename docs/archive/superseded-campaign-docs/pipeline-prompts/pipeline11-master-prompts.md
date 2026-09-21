# Pipeline 11 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P11 — Email Receipt Ingestion**

Sources checked:
- Commit: https://github.com/panospao7/Cost-agregator/commit/83b798e849b4408b2bf683f52cb2746d37f7af16
- P11 issue doc: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_11_CONSOLIDATED_ISSUES.md
- P11 implementation plan: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/pipelines%20issues%20implementantion%20plan/PIPELINE_11_IMPLEMENTATION_PLAN.md
- Master tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/PIPELINE_ISSUES_MASTER_TRACKER.md
- Universal tracker: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/analyses%20and%20debug%20master/UNIVERSAL_ISSUE_TRACKER.md
- Codebase inventory: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/CODEBASE_INVENTORY.md
- Codebase segments: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/docs/architecture/CODEBASE_SEGMENTS.md
- Email ingestion service: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt
- Email receipt entity: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/entity/EmailReceiptSource.kt
- Email DAO: https://github.com/panospao7/Cost-agregator/blob/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/data/database/dao/EmailReceiptDao.kt
- Email DI module: https://raw.githubusercontent.com/panospao7/Cost-agregator/83b798e849b4408b2bf683f52cb2746d37f7af16/app/src/main/java/com/yourname/expensetracker/di/EmailIngestionModule.kt

Important context:
- P11 is **Email Receipt Ingestion**.
- Core architecture segments involved:
  - Segment 4 — Receipt Scanning / OCR & Receipt Lifecycle
  - Segment 5 — AI Receipt Item Categorization, if item AI is triggered
  - Segment 9 — Core Expense Management
  - Segment 12 — Startup & Background Runtime, if any email worker/sync exists
  - Segment 16 — Currency & Exchange
  - Segment 18 — Export & Backup
  - Segment 20 — AI Platform, if email parsing/assist uses cloud AI
  - Segment 28 — Security / Privacy
  - Segment 29 — Debug & Diagnostics
  - Segment 30 — Dependency Injection
- P11 docs are internally stale/inconsistent:
  - consolidated doc says `4 FIXED, 5 PARTIAL, 0 TODO, 4 NEW open issues`, but its table also marks several NEW issues fixed.
  - implementation plan is older and still lists issues as open that appear fixed in current code.
- Therefore: **code at the target SHA is source of truth. Validate every tracker claim.**

---

## Prompt A — P11 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin, Room, receipt-lifecycle, email-ingestion, parser, privacy/redaction, restore-barrier, diagnostics, and financial-data-integrity architecture reviewer.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P11 — Email Receipt Ingestion

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

Audit Pipeline 11 end-to-end:

### Email intake scope
- email receipt entrypoints,
- manual email import if present,
- provider-specific email handling,
- batch ingestion,
- concurrency control,
- sender/subject/body/messageId handling,
- messageId hashing,
- raw email persistence policy,
- HTML cleanup,
- provider detection,
- parse errors and failure reporting.

### Parser scope
- Amazon parser,
- Uber parser,
- Apple parser,
- generic/base email parser,
- date parsing,
- amount parsing,
- locale/decimal handling,
- currency detection,
- order/trip ID extraction,
- line-item extraction,
- confidence calculation,
- parser canParse() precision,
- parser false-positive / false-negative behavior,
- parser performance and allocation hot spots.

### Dedupe / source tracking scope
- content fingerprint construction,
- messageId hash dedupe,
- sender-domain / provider / currency / order number dedupe,
- insert conflicts,
- `EmailReceiptSource` persistence,
- `EmailReceiptDao` conflict handling,
- idempotency under repeated import,
- idempotency under concurrent batch processing,
- source provenance and `EntitySourceLink` if used.

### Receipt lifecycle scope
- delegation to `ReceiptLifecycleCoordinator.processEmailReceipt`,
- receipt save,
- expense creation,
- receipt-to-expense linking,
- pending-review route for uncertain receipts,
- duplicate handling,
- post-save side effects,
- no double-dispatch,
- no direct receipt/expense DAO writes from email service,
- transaction lifecycle side effects triggered from created expenses.

### Privacy / raw storage scope
- raw email body,
- raw sender,
- subject,
- messageId,
- parsed merchant/item lines,
- diagnostics metadata,
- raw email retention/redaction,
- `EmailReceiptPersistencePayload`,
- `RawPersistencePolicyResolver`,
- `RawContentSanitizer`,
- `RawStorageMode`,
- `SafeEventMetadata`,
- no raw email PII in logs/diagnostics/export/backup.

### Restore / worker / diagnostics scope
- `DatabaseWriteBarrier`,
- `RestoreMaintenanceMode`,
- email ingestion during restore/backup,
- email workers if any,
- worker guard/run logging if any,
- diagnostic events/drop reasons,
- cancellation propagation,
- retry/idempotency if ingestion is backgrounded.

### Cross-pipeline dependencies
- P3/P4 receipt lifecycle and OCR/raw-storage contracts.
- P8 privacy/redaction/raw-storage and sensitive diagnostics.
- P9 workers if email ingestion is scheduled/backgrounded.
- P7 backup/restore for `email_receipt_sources`, raw email fields, receipt rows, pending review rows.
- P5/P6 dashboard/budget consume expenses created by email receipts.
- P4 recurring reconciliation may be triggered by email-created actual expenses.
- P12 import/export must not leak raw email content.

Read first:
- `docs/analyses and debug master/PIPELINE_11_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_11_IMPLEMENTATION_PLAN.md`
- relevant universal implementation-plan docs, especially privacy/raw-storage, side-effect framework, restore barrier, cancellation, diagnostics.

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
- P11 consolidated issue doc and implementation plan disagree.
- Validate every issue against code and tests at this SHA.
- If code is fixed but tracker says open, report tracker drift.
- If tracker says fixed but code does not prove the invariant, report bug/partial.

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

For P11 specifically, pay special attention to:
- Segment 4 — Receipt Scanning / Receipt Lifecycle.
- Segment 9 — Core Expense Management.
- Segment 18 — Export & Backup.
- Segment 28 — Privacy / Security.
- Segment 29 — Diagnostics.
- `LEGAL_PATHS.md` receipt lifecycle and transaction lifecycle ownership.
- DB write ownership for `EmailReceiptSource`, `ScannedReceipt`, `PendingReview`, `Expense`, `ReceiptExpenseLink`, events, and diagnostics.

## 4. Build a pipeline file inventory

Do not rely only on this seed list.
Use `rg`, import graph, Hilt map, DAO map, callers/callees, and tests to build the real inventory.

### Email ingestion core
Review:
- `app/src/main/java/com/yourname/expensetracker/data/email/EmailReceiptIngestionService.kt`
- any `EmailReceiptIngestionCoordinator.kt` if present
- any email import/sync/service/worker files discovered by:
  - `rg -n "EmailReceipt|email receipt|EmailIngestion|EmailImport|EmailSync|processEmailReceipt|processBatch"`

### Provider parsers
Review:
- `app/src/main/java/com/yourname/expensetracker/data/email/provider/EmailReceiptParser.kt`
- `app/src/main/java/com/yourname/expensetracker/data/email/provider/AmazonReceiptParser.kt`
- `app/src/main/java/com/yourname/expensetracker/data/email/provider/UberReceiptParser.kt`
- `app/src/main/java/com/yourname/expensetracker/data/email/provider/AppleReceiptParser.kt`
- any `EmailDateParser.kt` if present; if not, map the issue to the current date parsing implementation and report tracker/code drift.

Search:
- `rg -n "parseLocalizedDate|DateTimeFormatter|canParse|ParsedEmailReceipt|BaseEmailParser|ReceiptItem|orderNumber|Trip ID|Order ID"`
- `rg -n "AmazonReceiptParser|UberReceiptParser|AppleReceiptParser|EmailReceiptParser"`

### Receipt lifecycle dependencies
Review:
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/EmailReceiptProcessResult.kt` if separate
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/EmailReceiptData.kt` if separate
- `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptParser.kt`
- receipt side-effect planner/dispatcher files
- receipt event writer files
- receipt asset store if email receipts can attach images/files
- receipt link service / receipt-expense link lifecycle
- pending-review creation path.

Search:
- `rg -n "processEmailReceipt|EmailReceiptData|EmailReceiptProcessResult|NeedsReview|EMAIL_AUTO_EXPENSE_MIN_CONFIDENCE|ReceiptLifecycleCoordinator|ReceiptEvent|ReceiptExpenseLink"`

### Transaction lifecycle dependencies
Review:
- `TransactionLifecycleCoordinator.kt`
- transaction side-effect planner/dispatcher files
- entity source-link/provenance writer files
- expense mutation inventory/legal path docs.

Search:
- `rg -n "TransactionLifecycleCoordinator|createExpense|createTransaction|EntitySourceLink|sourceLink|ReceiptSourceType.EMAIL|EMAIL_RECEIPT"`

### DAOs
Review:
- `EmailReceiptDao.kt`
- `ScannedReceiptDao.kt`
- `PendingReviewDao.kt`
- `ReceiptEventDao.kt`
- `ReceiptExpenseLinkDao.kt`
- `EntitySourceLinkDao.kt`
- `ExpenseDao.kt`
- `PipelineDiagnosticEventDao.kt`
- `OperationRunDao.kt` / `OperationRunEventDao.kt` if operation-run diagnostics are used
- `PrivacyAuditDao.kt` if email privacy audit is used.

### Room entities / schema touchpoints
Review:
- `EmailReceiptSource.kt`
- `ScannedReceipt.kt`
- `PendingReview.kt`
- `ReceiptEvent.kt`
- `ReceiptExpenseLink.kt`
- `EntitySourceLink.kt`
- `Expense.kt`
- `PipelineDiagnosticEvent.kt`
- `PrivacyAuditEvent.kt`
- `AppDatabase.kt`
- `DatabaseMigrations.kt`
- exported Room schema JSON if present.

Check:
- unique constraints,
- nullable raw fields,
- messageId vs messageIdHash,
- contentFingerprintHash,
- foreign keys,
- cascade behavior,
- migration defaults,
- table inclusion in backup verifier.

### Privacy / raw storage / diagnostics
Review:
- `RawStorageMode.kt`
- `RawPersistencePolicy.kt`
- `RawPersistencePolicyResolver.kt`
- `EmailReceiptPersistencePayload.kt`
- `RawContentSanitizer.kt`
- `SafePrivacyMetadata.kt`
- `PrivacyAuditLogger.kt`
- `PrivacyAuditLoggerImpl.kt`
- `SafeEventMetadata`
- `EventMetadataSanitizer`
- `DiagnosticEventWriter`
- `PipelineDiagnosticEvent` path.

Search:
- `rg -n "EmailReceiptPersistencePayload|RawPersistencePolicy|RawStorageMode|RawContentSanitizer|emailStorageMode|rawOcrStorageMode|SafeEventMetadata|SafePrivacyMetadata|PrivacyAudit"`

### Restore / backup / export
Review:
- `DatabaseWriteBarrier.kt`
- `RestoreMaintenanceMode.kt`
- `RestoreInternalWriteScope.kt`
- `BackupVerifier.kt`
- backup/export serializers that include `email_receipt_sources`, `scanned_receipts`, `pending_reviews`, raw email fields.
- P12 export/import code that might expose email sources.

Search:
- `rg -n "email_receipt_sources|EmailReceiptSource|EmailReceiptDao|BackupVerifier|ExportAnonymizer|redacted export|emailSender|emailSubject|emailMessageId"`

### Workers / scheduling
If any email worker exists, review:
- worker class,
- `WorkerExecutionGuard`,
- `WorkerRunLogger`,
- `WorkerRegistry`,
- `WorkerSpec`,
- scheduling/cancel paths,
- privacy toggle interaction.

Search:
- `rg -n "class .*Email.*Worker|Email.*CoroutineWorker|EmailSync|EmailImport|enqueue.*Email|email.*Worker|EmailReceipt.*Worker"`

If no email worker exists, explicitly say “no P11 worker found” with search evidence.

### Hilt modules
Review:
- `EmailIngestionModule.kt`
- `DaoModule.kt`
- `DatabaseModule.kt`
- `ReceiptParsingModule.kt`
- `OcrImprovementsModule.kt`
- `PrivacyModule.kt`
- `DiagnosticsModule.kt`
- `DispatchersModule.kt`
- `TimeModule.kt`
- `WorkerModule.kt` if email workers exist.

Verify runtime bindings, not just module existence.

### UI / ViewModels
If email ingestion reaches UI, include:
- review screen,
- pending-review routes,
- receipt scan/review screens,
- email import screen if present,
- debug screens for email receipts,
- ViewModels,
- navigation routes,
- privacy-denied UI if relevant.

Search:
- `rg -n "EmailReceipt|Email Ingestion|PendingReview|ReviewScreen|ReceiptScan|email" app/src/main/java/com/yourname/expensetracker/ui`

If no P11-specific UI exists, explicitly say so and explain that output reaches generic Review/Receipt surfaces if true.

### Tests
Search the whole repo:
- `rg -n "EmailReceipt|EmailIngestion|AmazonReceipt|UberReceipt|AppleReceipt|EmailDate|processEmailReceipt|emailStorageMode|messageId|fingerprint|NeedsReview" app/src/test app/src/androidTest`

Include tests matching:
- `*EmailReceipt*`
- `*EmailIngestion*`
- `*AmazonReceipt*`
- `*UberReceipt*`
- `*AppleReceipt*`
- `*EmailDate*`
- `*ReceiptLifecycle*`
- `*RawStorage*`
- `*Privacy*`
- `*Restore*`
- `*Barrier*`
- `*Diagnostic*`

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
- Verify tests assert the important invariant, not just construct classes.
- If tracker says fixed/open/TODO, validate against code at this SHA.
- Treat raw email body/subject/sender/messageId as sensitive.
- Treat email-created duplicate expenses as P0/P1 risk.
- Treat any direct `ExpenseDao` or raw `ScannedReceiptDao` mutation bypassing lifecycle as a bug unless explicitly legal.
- Treat parser false-positive auto-expense creation as financial-integrity risk.
- Treat “privacy retention purges later” as insufficient if write-time policy requires redaction/drop.

Use searches like:
- `rg -n "processEmailReceipt|processBatch|EmailReceiptResult|EmailReceiptData|EmailReceiptProcessResult"`
- `rg -n "emailBody|sender|subject|messageId|emailMessageId|emailMessageIdHash|contentFingerprint|fingerprint"`
- `rg -n "AmazonReceiptParser|UberReceiptParser|AppleReceiptParser|canParse|parseLocalizedDate|DateTimeFormatter"`
- `rg -n "ReceiptLifecycleCoordinator|TransactionLifecycleCoordinator|ExpenseDao|ScannedReceiptDao|insert\\("`
- `rg -n "RawPersistencePolicy|emailStorageMode|rawOcrStorageMode|RawContentSanitizer|EmailReceiptPersistencePayload"`
- `rg -n "DatabaseWriteBarrier|RestoreMaintenanceMode|checkWritesAllowed|isWritesAllowed"`
- `rg -n "DiagnosticEvent|SafeEventMetadata|Timber\\.|Log\\.|println"`
- `rg -n "CancellationException|catch \\(e: Exception\\)|catch \\(t: Throwable\\)"`
- `rg -n "Semaphore|Mutex|withPermit|async|awaitAll"`
- `rg -n "OnConflictStrategy.IGNORE|insertOrIgnore|getByMessageId|getByMessageIdHash|getByFingerprint"`

## 6. Universal contracts to verify

Audit these for P11:

1. Restore/write barrier:
   - every email ingestion write path checks `DatabaseWriteBarrier`,
   - coordinator does not bypass shared barrier with raw `RestoreMaintenanceMode` unless architecture permits,
   - no email ingestion during restore/backup,
   - pending review/receipt/source writes fail closed during maintenance.

2. Worker guard and run logging:
   - if P11 has workers, they use `WorkerExecutionGuard`,
   - run start/success/skip/retry/failure logged,
   - privacy-disabled state maps to skip,
   - cancellation propagates.
   - If no worker exists, mark not applicable with evidence.

3. Privacy/redaction/raw-storage policy:
   - raw email body/sender/subject/messageId are persisted only according to email-specific policy,
   - email raw storage does not accidentally use OCR mode,
   - messageId stored as HMAC/hash where required,
   - diagnostics/logs avoid raw email content,
   - export/backup respects redacted mode.

4. Money/currency normalization:
   - parsed amount finite and positive,
   - currency non-blank and valid,
   - default currency is justified and not silently wrong,
   - email-created expenses reach money normalization via receipt/transaction lifecycle,
   - partial/missing currency state is handled.

5. Transaction lifecycle ownership:
   - email-created expenses are created only through legal transaction lifecycle via receipt coordinator,
   - no direct `ExpenseDao` writes,
   - duplicates do not create duplicate money records.

6. Receipt lifecycle/link ownership:
   - email service delegates receipt mutation to `ReceiptLifecycleCoordinator`,
   - receipt save, source insert, expense creation/link, events, diagnostics, and side effects are atomic as intended,
   - no double-dispatch of receipt side effects.

7. Recurring planned/actual reconciliation:
   - email-created actual expenses trigger recurring reconciliation if they match planned recurring obligations,
   - no bypass of transaction side-effect framework.

8. Diagnostics/drop reasons/events:
   - parser failures, validation failures, duplicates, restore blocks, coordinator errors, and needs-review outcomes have durable/sanitized diagnostics,
   - diagnostic failures do not abort core flow except cancellation,
   - correlation IDs are preserved.

9. Import/export schema/roundtrip:
   - `email_receipt_sources` survives backup/restore if intended,
   - raw fields are excluded/redacted when required,
   - restored email sources still link to receipts,
   - messageId hash/fingerprint survive roundtrip.

10. DAO conflict handling and timestamps:
   - `insertOrIgnore` result is checked,
   - messageId/hash conflicts return existing source or duplicate result, not orphan rows,
   - unique indexes match privacy mode,
   - timestamps are valid,
   - foreign keys/cascade behavior intentional.

## 7. P11-specific invariants to audit

### Email service boundary
Check:
- `EmailReceiptIngestionService` is a thin parser/delegate layer.
- It does not create expenses or receipts directly.
- It checks `DatabaseWriteBarrier`.
- It rethrows `CancellationException`.
- It emits sanitized diagnostic events.
- It hashes messageId before storage/coordinator if policy requires.
- It uses bounded concurrency and does not serialize whole batch unnecessarily.
- Batch processing actually runs concurrently if intended; mapping over `processEmailReceipt` sequentially may defeat semaphore benefits unless caller parallelizes.

### Provider detection
Check:
- Amazon/Uber/Apple `canParse()` are not overly broad.
- Body-only keyword match cannot misclassify unknown emails into high-confidence provider parsing.
- Sender-domain checks are strong enough and not spoof-prone if subject/body override exists.
- Unknown provider fallback does not auto-create false-positive expenses from random emails.

### Parser correctness
Check:
- amounts are parsed with locale-safe rules,
- currencies are detected correctly,
- default currency is explicit and tested,
- dates use deterministic time zone and year inference,
- date formatters are cached or allocation is acceptable,
- order/trip IDs extracted correctly,
- regex raw strings are not double-escaped,
- item line parsing avoids over-capturing PII,
- confidence values drive review routing.

### Dedupe / fingerprint
Check:
- content fingerprint distinguishes different orders from same merchant/amount/date,
- fingerprint includes provider, merchant, amount, currency, date bucket, order number where available, and maybe sender domain,
- date bucket width is intentional and documented,
- messageId is not the only dedupe input,
- messageId hash conflict handled,
- content fingerprint conflict handled,
- repeated import and concurrent import are idempotent.

### Receipt lifecycle / pending review
Check:
- low-confidence email receipt routes to `NeedsReview` / pending review.
- high-confidence receipt may auto-create expense only through lifecycle coordinator.
- existing duplicate is not treated as fatal failure.
- non-duplicate failures are not silently ignored.
- source row creation and receipt row creation are atomic or recoverable.
- side effects are dispatched exactly once by coordinator.

### Privacy
Check:
- email-specific storage mode is used, not OCR storage mode unless explicitly unified.
- raw body/sender/subject/messageId are redacted/dropped at write time when required.
- raw email is not logged through Timber or diagnostics.
- `EmailReceiptSource` nullable fields match policy.
- retention redacts fields without destroying dedupe/provenance.
- redacted export/backup excludes raw email values.

### Restore / backup
Check:
- service and coordinator use shared write barrier.
- backup verifier classifies email receipt source table appropriately.
- restore preserves receipt/source links.
- ingestion is blocked during restore and backup-export maintenance.
- post-restore duplicate detection still works.

### Diagnostics
Check:
- diagnostic metadata uses `SafeEventMetadata` and hashed IDs.
- exception messages are sanitized.
- parser failure reason codes are specific enough.
- final terminal event is written once.
- diagnostic catch blocks rethrow cancellation.

## 8. Known P11 issue set to validate

Read P11 consolidated issue doc and implementation plan, then validate each against code.

Old issues:
- `P11-P1-01`: duplicate fingerprint includes message ID / content fingerprint too coarse.
- `P11-P1-02`: existing expense duplicate treated as failure / non-duplicate failures ignored.
- `P11-P1-03`: service path only partially uses receipt lifecycle.
- `P11-P1-04`: raw email body/subject/sender persisted without correct privacy policy.
- `P11-P1-05`: restore barrier incomplete at email service/coordinator boundary.
- `P11-P1-06`: email source insert conflicts ignored / messageId-only conflict unresolved.
- `P11-P1-07`: receipt post-save side effects skipped or double-dispatched.
- `P11-P1-08`: no pending-review route for uncertain email receipts.

New issues:
- `NEW-P11-001`: `ingestionMutex` blocks all concurrent processing during batch.
- `NEW-P11-002`: `AmazonReceiptParser.canParse()` overly broad.
- `NEW-P11-003`: `UberReceiptParser.canParse()` overly broad.
- `NEW-P11-004`: `parseLocalizedDate()` creates excessive formatter instances.
- `NEW-P11-005`: Amazon parser regex double-escaped in raw strings.

Important:
- Current code may already contain fixes, e.g. semaphore, narrower canParse, fixed regex, barrier, HMAC messageId, and needs-review routing. Validate rather than assume.
- If code comments claim a fix, verify actual behavior and tests.
- If a fix exists but no test covers it, mark as fixed-with-test-gap or partial depending risk.
- If `processBatch()` is sequential despite semaphore, report the real concurrency behavior.

## 9. Review dimensions

Check:
- correctness,
- financial data integrity,
- duplicate-money prevention,
- parser precision,
- privacy fail-closed behavior,
- raw PII storage/logging,
- restore/export safety,
- atomicity/transactions,
- lifecycle bypasses,
- direct DAO writes,
- cancellation handling,
- coroutine races,
- idempotency,
- dedupe/conflict behavior,
- confidence/state transitions,
- timestamp/currency defaults,
- schema/migration compatibility,
- Hilt binding correctness,
- UI/pending-review consistency if relevant,
- diagnostics coverage,
- test coverage,
- performance risks,
- security/privacy risks.

## 10. Required output format

Produce this exact structure:

# Pipeline 11 Review — Email Receipt Ingestion

## 1. Pipeline summary
- What P11 does.
- Main data flow.
- Entry points and exits.
- Mermaid or text data-flow diagram.

## 2. File inventory
Create a table:
| Category | Files reviewed | Why relevant | Notes |

Include:
- entry points,
- provider parsers,
- services/coordinators,
- receipt lifecycle files,
- transaction lifecycle touchpoints,
- repositories if any,
- DAOs,
- Room entities,
- workers if any,
- privacy/raw-storage files,
- backup/export files,
- Hilt modules,
- ViewModels/UI if reached,
- tests,
- diagnostics/event writers,
- migrations/schema touchpoints.

Also list:
- files intentionally skipped and why,
- files discovered but not fully reviewed and why.

## 3. Architecture comparison
- Does code follow `LEGAL_PATHS.md`?
- Does code follow Segment 4 receipt lifecycle ownership?
- Does code follow privacy/raw-storage architecture?
- Does code follow sensitive diagnostics policy?
- Any doc/code drift?
- Any tracker/code drift?
- Any stale TODO or misleading comment?

## 4. Runtime flow / call graph
Include:
- email service entry,
- provider detection,
- parser path,
- dedupe/fingerprint path,
- receipt lifecycle coordinator path,
- pending review path,
- source insert path,
- expense creation/link path,
- restore/write gating,
- privacy/raw storage,
- diagnostics/events,
- batch/concurrency path.

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
- restore barrier,
- privacy/redaction/raw storage,
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

## 7. P11 issue reconciliation
Create table:
| Tracker issue | Tracker status | Code status at target SHA | Evidence | Final status | Notes |

Include all old and new P11 issues from `PIPELINE_11_CONSOLIDATED_ISSUES.md`.

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
- parser decision table,
- legal write path table,
- raw-storage policy table,
- before/after fix plan,
- commit plan split by safe PRs.

## 11. Final verdict
- GREEN / YELLOW / RED.
- Highest-risk remaining issue.
- Whether P11 is production-safe.
- What must be fixed before GREEN.

## 11. Severity rubric

Use:
- P0: data loss, corruption, privacy leak, broken restore, duplicate money records, irreversible wrong write.
- P1: major wrong behavior, race, lifecycle bypass, missing guard, broken critical flow.
- P2: edge-case bug, poor diagnostics, partial inconsistency, retry/idempotency weakness.
- P3: cleanup, docs drift, TODO, non-critical maintainability.

For P11:
- Duplicate email-created expenses are P0/P1.
- Raw email body/subject/sender/messageId stored or logged against policy is P0/P1.
- Direct expense writes bypassing lifecycle are P1.
- Parser false-positive auto-expense creation is P1/P2 depending likelihood.
- Missing tests for privacy/dedupe/lifecycle invariants are P2/P3 depending risk.

## 12. Completion criteria

The review is not complete until:
- P11 issue doc was read,
- master/universal trackers were read,
- architecture docs were checked,
- all relevant source files were inventoried,
- key callers/callees were traced,
- parser behavior was inspected,
- receipt lifecycle path was verified,
- raw email storage policy was verified,
- tests were found or missing tests were listed,
- cross-pipeline impacts were identified,
- every finding has evidence and a fix strategy,
- final verdict is justified.
```

---

## Prompt B — P11 Fix Implementation + Tests Prompt

Use this after Prompt A produces confirmed findings.

```text
You are a senior Android/Kotlin implementation agent specializing in email receipt ingestion, parser correctness, receipt lifecycle, Room idempotency, privacy/redaction, restore barriers, diagnostics, and test-driven fixes.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Commit baseline:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P11 — Email Receipt Ingestion

Mode:
Fix implementation + test writing + validation.
Only fix confirmed P11 issues.
Do not perform broad refactors.
Preserve architecture contracts and public behavior unless a bug requires change.

## 2. Required reading before editing

Read:
- `docs/analyses and debug master/PIPELINE_11_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_11_IMPLEMENTATION_PLAN.md`
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
- `docs/architecture/PRIVACY_UI_ARCHITECTURE.md` if UI/denied states touched.
- DB/restore/export docs if touching backup/import/export.

Do not trust docs over code.
If tracker status differs from code, fix code only if code is actually wrong.
If only docs are stale, report docs drift instead of changing code.

## 3. Implementation constraints

Follow P11 legal paths:
- email ingestion service is parser/delegate only,
- all receipt/expense mutations must go through `ReceiptLifecycleCoordinator`,
- email-created expenses must eventually use legal transaction lifecycle,
- all writes must check `DatabaseWriteBarrier`,
- raw email fields must obey email-specific raw-storage policy at write time,
- messageId should be hashed/HMACed when stored or used in diagnostics,
- diagnostics must use safe metadata and rethrow cancellation,
- no post-save side effects in email service if coordinator owns them,
- no direct `ExpenseDao` or unmanaged `ScannedReceiptDao` writes from email service.

General rules:
- Keep changes minimal and targeted.
- Add/update tests for every fixed issue.
- Do not introduce schema migration unless explicitly required and approved.
- Do not mask `CancellationException`.
- Do not store raw email content before sanitization if policy says redact/drop.
- Do not log raw email body/subject/sender/messageId.
- Do not default currency silently unless tested/documented.
- Do not make parser `canParse()` broad enough to claim unrelated emails.
- Do not weaken duplicate detection.

## 4. Candidate P11 fix areas

Validate first, then fix only if still broken.

### P11-PR1 — Dedupe, conflicts, and lifecycle failures
Candidate issues:
- `P11-P1-01`: fingerprint too coarse.
- `P11-P1-02`: non-duplicate failures ignored.
- `P11-P1-06`: messageId conflict unresolved.
- repeated/concurrent ingestion not idempotent.

Implementation intent:
1. Refine fingerprint:
   - provider,
   - normalized merchant,
   - rounded amount,
   - normalized currency,
   - sender domain if useful,
   - deterministic date bucket,
   - order/trip ID when available.
2. Use HMAC/hash messageId as primary privacy-safe dedupe key.
3. On `insertOrIgnore` conflict:
   - look up existing by messageId hash and/or raw messageId if policy permits,
   - return duplicate/existing source,
   - never create orphan receipt/source rows.
4. Handle non-duplicate coordinator failures:
   - emit sanitized diagnostic,
   - return explicit error,
   - do not silently ignore.
5. Ensure repeated import and concurrent batch cannot create duplicate receipts/expenses.

Required tests:
- `same_message_id_reimport_returns_duplicate`
- `same_receipt_concurrent_ingestion_is_idempotent`
- `same_merchant_amount_day_different_order_not_deduped`
- `same_amount_different_currency_not_deduped`
- `message_id_conflict_returns_existing_source`
- `coordinator_non_duplicate_error_emits_diagnostic`
- `email_created_expense_not_duplicated_on_retry`

### P11-PR2 — Restore barrier and receipt lifecycle ownership
Candidate issues:
- `P11-P1-03`: service path partially uses receipt lifecycle.
- `P11-P1-05`: restore barrier incomplete at service/coordinator boundary.
- `P11-P1-07`: side effects skipped or double-dispatched.
- `P11-P1-08`: uncertain email receipt lacks pending-review path.

Implementation intent:
1. Ensure email service delegates all mutations to `ReceiptLifecycleCoordinator`.
2. Replace direct `RestoreMaintenanceMode.isWritesAllowed()` checks in coordinator path with shared `DatabaseWriteBarrier` where architecture requires.
3. Ensure low-confidence email data returns `NeedsReview` / pending-review route.
4. Ensure side effects are dispatched exactly once by coordinator.
5. Ensure high-confidence path still creates receipts/expenses legally.

Required tests:
- `email_ingestion_uses_write_barrier`
- `email_coordinator_blocked_during_restore`
- `email_service_does_not_write_receipt_or_expense_directly`
- `low_confidence_email_creates_pending_review`
- `high_confidence_email_uses_receipt_lifecycle`
- `receipt_side_effects_dispatched_once`
- `duplicate_email_does_not_dispatch_side_effects_twice`

### P11-PR3 — Parser precision and performance
Candidate issues:
- `NEW-P11-002`: Amazon parser too broad.
- `NEW-P11-003`: Uber parser too broad.
- `NEW-P11-004`: date formatter allocation/performance.
- `NEW-P11-005`: Amazon raw-string regex double escaping.
- parser locale/currency/date edge cases.

Implementation intent:
1. `canParse()` should require trusted sender/domain or strong provider-specific marker.
2. Unknown provider fallback must not auto-create expense on weak body matches.
3. Fix raw string regex escaping.
4. Cache `DateTimeFormatter` lists in companion objects.
5. Add parser golden tests for real and near-miss emails.
6. Validate amount/currency/date extraction.

Required tests:
- `amazon_parser_accepts_real_amazon_sender`
- `amazon_parser_rejects_non_amazon_total_email`
- `uber_parser_accepts_real_uber_sender`
- `uber_parser_rejects_non_uber_trip_email`
- `apple_parser_rejects_unrelated_receipts`
- `amazon_order_regex_matches_digits`
- `email_date_formatters_reused_or_static`
- `localized_amount_parses_us_eu_formats`
- `unknown_provider_weak_match_needs_review_or_parse_error`

### P11-PR4 — Privacy/raw email storage
Candidate issues:
- `P11-P1-04`: wrong privacy mode for email fields.
- raw email diagnostics/logging leaks.
- export/backup leaks raw email fields.

Implementation intent:
1. Use email-specific raw-storage mode, e.g. `emailStorageMode`, not `rawOcrStorageMode`, unless intentionally unified and documented.
2. Route raw body/sender/subject/messageId through `EmailReceiptPersistencePayload` / `RawPersistencePolicyResolver`.
3. Apply drop/redact/raw at write time.
4. Store messageId hash for dedupe across all modes.
5. Use `SafeEventMetadata` / `SafePrivacyMetadata`.
6. Ensure export/backup redacted modes exclude raw email fields.

Required tests:
- `do_not_store_email_mode_drops_body_subject_sender`
- `store_redacted_email_mode_writes_redacted_fields`
- `store_raw_email_mode_preserves_raw_when_allowed`
- `message_id_hash_available_in_all_modes`
- `diagnostics_do_not_include_raw_email_body_subject_sender`
- `redacted_export_excludes_raw_email_fields`
- `backup_restore_preserves_email_hashes_and_links`

### P11-PR5 — Batch concurrency, cancellation, and diagnostics
Candidate issues:
- `NEW-P11-001`: ingestion mutex / ineffective concurrency.
- swallowed cancellation.
- weak diagnostics.

Implementation intent:
1. If bounded concurrency is intended, ensure `processBatch()` actually parallelizes with bounded concurrency, not sequential map.
2. Keep semaphore/parallelism bounded.
3. Rethrow `CancellationException` from every catch.
4. Emit terminal diagnostic event once.
5. Use hashed messageId and correlation IDs.

Required tests:
- `batch_processing_runs_with_bounded_parallelism`
- `batch_processing_does_not_exceed_max_concurrency`
- `email_ingestion_rethrows_cancellation`
- `diagnostic_failure_does_not_abort_success`
- `terminal_event_written_once`

## 5. Universal checks before/after every fix

Verify:
- no direct expense/receipt DAO lifecycle bypass,
- restore/write barrier on every mutation,
- email privacy mode applied at write time,
- no raw email content in logs/diagnostics,
- duplicate detection idempotent under retry/concurrency,
- receipt lifecycle side effects exactly once,
- pending-review route for low confidence,
- cancellation propagated,
- DAO conflict results checked,
- timestamps valid,
- Hilt bindings point to intended implementations,
- tests hit real runtime path.

## 6. Required validation commands

Run at minimum:
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*EmailReceipt*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*EmailIngestion*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AmazonReceipt*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*UberReceipt*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AppleReceipt*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*EmailDate*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptLifecycle*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*RawStorage*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Barrier*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Diagnostic*" --stacktrace
./gradlew :app:check --stacktrace
```

If a command cannot run, report:
- exact command,
- failure reason,
- whether failure is related to P11,
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
1. dedupe/conflicts/error handling,
2. restore barrier + receipt lifecycle + review route,
3. parser precision/performance,
4. privacy/raw-storage hardening,
5. diagnostics/concurrency polish,
6. docs/tracker sync.
```

---

## Prompt C — P11 Final Validation / Fixed-Claims Audit Prompt

Use this after fixes land.

```text
You are a senior validation/debugger agent specializing in email receipt ingestion, parser correctness, receipt lifecycle, privacy/raw-storage safety, and duplicate-money prevention.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Target:
Use the current working branch/commit provided by the user.
Baseline context commit:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P11 — Email Receipt Ingestion

Mode:
Validation of already-fixed claims.
Do not implement new fixes.
Verify whether P11 can be marked GREEN/YELLOW/RED.

## 2. Required reading

Read:
- P11 consolidated issue doc,
- P11 implementation plan,
- master tracker,
- universal tracker,
- all architecture docs listed in Prompt A,
- sensitive diagnostics policy,
- privacy UI architecture if UI touched,
- changed source files,
- changed tests,
- migration/schema files if touched,
- changed Hilt modules,
- changed privacy/raw-storage files,
- changed receipt lifecycle files,
- changed parser files,
- changed UI files if touched.

Do not trust PR descriptions or comments.
Validate against code and tests.

## 3. Claims to validate

Validate:
- all P11 old issues marked fixed,
- all P11 new issues marked fixed,
- all universal fixes that affect P11,
- all newly added tests,
- no new lifecycle bypasses introduced,
- no new raw email leak introduced,
- no new duplicate expense path introduced.

Specific P11 claims:
- email service is parser/delegate only,
- all receipt/expense mutations go through `ReceiptLifecycleCoordinator`,
- email-created expenses use legal transaction lifecycle,
- no direct `ExpenseDao` writes from email path,
- restore/write barrier blocks service and coordinator writes,
- low-confidence email receipts create pending review / `NeedsReview`,
- high-confidence receipts still create through lifecycle,
- side effects dispatched exactly once,
- duplicate emails do not dispatch side effects twice,
- messageId is HMAC/hashed and not logged raw,
- fingerprint distinguishes same merchant/amount/date but different order/currency/domain where intended,
- `insertOrIgnore` conflicts are handled,
- repeated import is idempotent,
- concurrent import is idempotent,
- Amazon/Uber/Apple `canParse()` are not overly broad,
- parser regex and date formatter issues are fixed if claimed,
- amount/currency/date parsing is covered by tests,
- raw email body/sender/subject/messageId obey email-specific storage policy at write time,
- diagnostics and logs contain no raw email content,
- cancellation propagates,
- batch concurrency is bounded and effective if claimed,
- backup/export handles email source table and raw fields correctly,
- Hilt binds intended parsers/service.

## 4. Required validation steps

1. Build exact file inventory.
2. Trace runtime flows.
3. Compare code to `LEGAL_PATHS.md`.
4. Run targeted tests.
5. Review test assertions for real coverage.
6. Check direct DAO writes.
7. Check receipt lifecycle ownership.
8. Check transaction lifecycle ownership.
9. Check restore/write barrier.
10. Check raw storage policy at write sites.
11. Check diagnostics/logging.
12. Check parser edge cases.
13. Check duplicate/idempotency scenarios.
14. Check backup/export/migration impact.
15. Check Hilt bindings.
16. Check UI/review route if touched.

## 5. Required output

Produce:

# P11 Fixed-Claims Validation

## 1. Verdict
GREEN / YELLOW / RED

## 2. Claims table
| Claim | Source doc/PR | Validated? | Evidence | Remaining risk |

## 3. Regression search
| Area | Search/check performed | Result |

Include at least:
- direct `ExpenseDao` writes,
- direct `ScannedReceiptDao` writes,
- raw email body/subject/sender/messageId storage,
- messageId hashing,
- fingerprint construction,
- insert conflict handling,
- parser `canParse`,
- cancellation catches,
- write barrier usage,
- diagnostics metadata,
- side-effect dispatch,
- pending review route,
- Hilt bindings.

## 4. Test validation
| Test | What it proves | Weakness/gap |

## 5. Contract audit
- restore barrier,
- privacy/raw storage,
- receipt lifecycle ownership,
- transaction lifecycle ownership,
- worker guard if applicable,
- money/currency,
- diagnostics/events,
- import/export/backup,
- DAO conflicts/timestamps,
- UI review route if applicable.

## 6. Remaining issues
| ID | Severity | Status | Evidence | Required next action |

## 7. Production safety
- Is P11 production-safe?
- Highest-risk issue.
- Required fix before GREEN.
```