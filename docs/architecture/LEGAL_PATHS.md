# Legal Paths — Architecture Law

> **Last updated:** 2026-09-21 (verified against code: DB v148, guarded-worker set, CI guard suite)
>
> **Purpose:** Define the ONE allowed implementation path for each major operation.  
> **Rule:** Any code that uses a different path is a bug, regardless of whether it "works."  
> **Enforcement:** CI static guard suite (`scripts/ci/run_static_guard_suite.py`, registry in `scripts/ci/guard_registry.py`; policy in `docs/ci/guard-policy.md`), DB ownership policy (`config/guards/db_ownership_policy.yml`), in-repo architecture guard tests (`app/src/test/java/com/yourname/expensetracker/architecture/`), `DeprecationLevel.ERROR`, and contract tests.

---

## Expense Mutations

```
CREATE expense:
  Any source (UI/notification/receipt/email/bank/import/group)
    → TransactionLifecycleCoordinator.createExpenseStandaloneV2() or
      createExpenseDbOnlyV2() (createExpense()/createExpenseStandalone()/
      createExpenseDbOnly() remain as deprecated delegates to the V2 paths)
    → ExpenseDao.insertAtomic() [ONLY from coordinator]
    → TransactionEvent with LifecycleEventType.CREATED
    → Post-commit side effects via TransactionSideEffectPlanner → TransactionSideEffectDispatcher

FORBIDDEN:
  ❌ ExpenseDao.insert() from any repository directly
  ❌ ExpenseDao.insertAll() outside debug/migration
  ❌ Any expense insert without TransactionEvent (LifecycleEventType.CREATED)

ENFORCEMENT:
  • Every direct ExpenseDao mutation call site must match an entry in
    config/guards/db_ownership_policy.yml (exact class + method + DAO +
    operation contract) or a structural exception — enforced by
    scripts/verify_db_access_boundaries.py (ratchet mode in CI).
  • Direct ExpenseDao mutation methods are annotated @RestrictedExpenseDaoMutation
    (opt-in); ExpenseDaoMutationAccessTest provides hard CI enforcement.
  • Legal writers wrap mutations in canonical write-barrier scopes —
    writeBarrier.checkWritesAllowed(...) at entry and writeBarrier.runWrite(...)
    around database.withTransaction. (The former ExpenseWriteStore facade was
    removed as a dead layer — GR-14u35.)
```

```
UPDATE expense:
  → TransactionLifecycleCoordinator.updateCategory/updateMerchant/updateType/etc.
  → TransactionEvent with LifecycleEventType.UPDATED
  → Post-update side effects via TransactionSideEffectPlanner.planUpdated()

FORBIDDEN:
  ❌ ExpenseDao.update() from repositories directly
  ❌ ExpenseDao.updateCategory() outside coordinator
  ❌ Any expense update without TransactionEvent (LifecycleEventType.UPDATED)
```

```
DELETE expense:
  → TransactionLifecycleCoordinator.deleteExpense(id)
  → Loads snapshot INSIDE transaction
  → TransactionEvent with LifecycleEventType.DELETED
  → Post-delete side effects via TransactionSideEffectPlanner.planDeleted()

FORBIDDEN:
  ❌ ExpenseDao.delete() from repositories directly
  ❌ Loading snapshot outside the delete transaction
```

```
DUPLICATE BLOCKING vs OWNERSHIP RESOLUTION (RP-11):
  Two deliberately separate ExpenseDao query families exist (P2-008 11c + RP-11 FIX 2):

  BLOCKING family — collision preflights, intentionally INCLUDES isNotMine rows:
  → existsByMerchantKeyInRangeCurrencyAware / existsByMerchantKeyPrefixInRangeCurrencyAware /
    existsByMerchantInRangeCurrencyAware (EXISTS prechecks behind isDuplicateCurrencyAware())
  → getBlockingCandidateByMerchantKeyInRangeCurrencyAware /
    getBlockingCandidateByMerchantKeyPrefixInRangeCurrencyAware /
    getBlockingCandidateByMerchantInRangeCurrencyAware (full-row candidates, no ownership filter)
  → findBlockingDuplicateIdCurrencyAware() — @Transaction, returns the colliding id or null
  → Matching (all tiers): exact merchantKey → prefix containment (both keys LENGTH >= 8,
    mirroring DuplicateDetectionPolicy.MIN_MERCHANT_KEY_PREFIX_LENGTH) → raw merchant;
    window [date - windowMs, date + windowMs + 1) (DUPLICATE_WINDOW_MS = 5 min),
    amount +/- AMOUNT_TOLERANCE (0.01), currency case-insensitive, UNKNOWN type matches any
  → Why include not-mine: a not-mine row occupying the target identity must abort the
    write; using the resolver here would let the write slip past the preflight and die on
    the raw dedupeKey unique index (unique Index on dedupeKey; insertAtomic
    OnConflictStrategy.IGNORE returns -1 on collision)

  RESOLVER family — ownership-scoped reads, SQL filters isNotMine = 0:
  → getDuplicateCandidateByMerchantKeyInRangeCurrencyAware /
    getDuplicateCandidateByMerchantKeyPrefixInRangeCurrencyAware /
    getDuplicateCandidateByMerchantInRangeCurrencyAware; findDuplicateIdCurrencyAware()
    — the fuzzy resolver must NEVER return a not-mine row; also feeds the
    suggestion/import candidate paths

  COLLISION PREFLIGHT call sites — all five run findBlockingDuplicateIdCurrencyAware()
  inside database.withTransaction (TOCTOU-safe) and abort with a typed
  DuplicateUpdateException when the colliding id differs from the target id:
  → updateExpense (key-fields-changed branch)
  → updateMerchant
  → updateType
  → updateTypeAndTransferDetails (preflight runs whenever the recomputed dedupeKey
    differs from the stored key)
  → bulkUpdateMerchant (preflights EVERY target key inside the transaction BEFORE any
    write; a collision outside the rename set aborts everything → Result.failure with
    the controlled BulkMerchantRenameFailure.MERCHANT_RENAME_DUPLICATE reason — no row
    written, no event, no dispatch; zero matching rows is a successful no-op with no
    event and no dispatch)

  Receipt-side atomic assignment:
  → ReceiptLinkService.linkReceiptToExpense() runs link insert + legacy field +
    warranty/return/item propagation + ReceiptEvent inside one DomainTransactionRunner
    transaction, with a fresh in-transaction receipt read; the AUTO_MATCH path uses the
    atomic compare-and-set ScannedReceiptDao.claimForAutoMatch — a 0-row claim throws
    ReceiptAlreadyClaimedException and rolls the link back; the category assignment
    committed inside the transaction dispatches its side effects only AFTER commit

FORBIDDEN:
  ❌ Using the resolver family (findDuplicateIdCurrencyAware) for mutation collision
     preflights or post-blocking duplicate audit lookups
  ❌ Adding an isNotMine filter to the blocking family
  ❌ Collision checks outside the coordinator transaction (TOCTOU)
```

---

## Receipt Mutations

```
PROCESS receipt (camera/gallery/file/PDF):
  → ReceiptLifecycleCoordinator.processReceiptInput()
  → ReceiptRepository.processReceipt() [OCR/parse + draft insert via
    ReceiptInsertResolver/ReceiptRecordWriter with pre-OCR image-hash dedup;
    coordinator owns lifecycle enrichment + events + side effects]
  → Coordinator owns: insert + metadata + fingerprints + event + side effects

CREATE expense FROM receipt:
  → ReceiptLifecycleCoordinator.createExpenseFromReceipt()
  → database.withTransaction { coordinator.createExpense(DEFER) + linkService.link() }
  → Throws on link failure → rollback

LINK/UNLINK receipt:
  → ReceiptLinkService.linkReceiptToExpense() / unlinkReceiptFromExpense()
  → Owns: join table + legacy field + warranty/return/itemCategorization + event

FORBIDDEN:
  ❌ ScannedReceiptDao.insert() outside coordinator/repository
  ❌ ReceiptRepository.linkReceiptToExpense() (deprecated)
  ❌ Direct ScannedReceipt.expenseId update
  ❌ Any receipt mutation without ReceiptEvent
```

```
MATCH receipt (suggest/approve/reject/clear):
  → ReceiptMatchLifecycleService.saveMatchSuggestion() / rejectAllSuggestions()
    / clearMatchForReceipt()
  → Approving (accepting) a suggestion is a LINK: ReceiptLinkService.linkReceiptToExpense()
    (ReceiptMatchLifecycleService has no approveMatchSuggestion method;
    ReceiptRepository.approveMatchSuggestion() is permanently disabled)
  → Each operation: DatabaseWriteBarrier check → withTransaction → ReceiptEvent
  → Events: MATCH_SUGGESTED / MATCH_APPROVED / MATCH_REJECTED / MATCH_CLEARED

AUTO-MATCH receipt (ReceiptMatchingWorker, periodic + manual runOnce):
  → ReceiptMatchLifecycleService writes durable events for every outcome:
      MATCH_ATTEMPTED / MATCH_NOT_FOUND / MATCH_SKIPPED_DOCUMENT_TYPE / AUTO_MATCH_LINK_FAILED
  → Concurrency invariant: per-receipt atomic claim ScannedReceiptDao.claimForAutoMatch
    (conditional UPDATE WHERE matchStatus IN ('UNMATCHED','SUGGESTED')) is the
    load-bearing overlap guard — concurrent periodic+manual runs cannot double-link.
    WorkerLeaseRegistry is a drain/registry mechanism, NOT mutual exclusion per worker.

FORBIDDEN:
  ❌ ReceiptRepository.saveMatchSuggestion() [DeprecationLevel.ERROR]
  ❌ ReceiptRepository.rejectAllSuggestions() [DeprecationLevel.ERROR]
  ❌ ReceiptRepository.clearMatchForReceipt() [DeprecationLevel.ERROR]
  ❌ Any match mutation without ReceiptEvent
  ❌ Relying on WorkerLeaseRegistry for auto-match mutual exclusion
```

```
PERSIST receipt structured data (RP-12 12b P3-007):
  → ReceiptStructuredDataPolicy.persistedItems() — the ONLY persisted
    representation of receipt line items
  → Single RawStorageMode transformer applied BEFORE the receipt row insert:
    STORE_RAW passes the parser JSON through unchanged; STORE_REDACTED
    persists the typed RedactedReceiptItem projection and drops the parsed
    merchant; STORE_METADATA_ONLY / DO_NOT_STORE persist no item JSON and
    no parsed merchant
  → Serialization failure yields null — most restrictive, fail-closed

CLEAN UP uncommitted receipt assets (RP-12 12c P3-008):
  → AssetCleanupCoordinator.cleanupUncommittedAsset()

FORBIDDEN:
  ❌ Persisting receipt item JSON outside ReceiptStructuredDataPolicy
```

```
DEBUG EXPORT receipt data:
  → ReceiptDebugExporter.debugReceipt() / exportParserDebugData()
  → Writes DiagnosticEvent (ALLOWED/DENIED with reason code)
  → Image paths redacted by default (includeImagePath=false)

FORBIDDEN:
  ❌ ReceiptRepository.debugReceipt() [DeprecationLevel.ERROR]
  ❌ ReceiptRepository.exportParserDebugData() [DeprecationLevel.ERROR]
  ❌ Exporting receipts without privacy consent check
  ❌ Including raw image paths without explicit consent
```

---

## Email Receipt Parsing

```
PARSE email receipt (RP-18):
  → EmailReceiptIngestionService.processEmailReceipt() — thin parser/delegate, owns NO mutation
  → DatabaseWriteBarrier check → Semaphore(3) bounded concurrency → EMAIL DiagnosticEvents
  → detectProvider(sender, subject, body): parser canParse() in fixed order amazon →
    uber → apple, then sender-domain fallbacks, then body-domain fallbacks; provider
    "unknown" tries all parsers in the same amazon → uber → apple order (there is NO
    pluggable parser registry — the order is hardcoded in the service)
  → Parser set (data/email/provider/): AmazonReceiptParser / UberReceiptParser /
    AppleReceiptParser, all extending BaseEmailParser (EmailReceiptParser.kt);
    canParse() is sender-gated for all three (subject/body only corroborate)

PARSING CONTRACT (as implemented, RP-18 18-A):
  → Total extraction is line-anchored, specific-labels-first: per-parser specific labels
    (e.g. Amazon "order total" / "grand total") → bare "total" LAST → any line containing
    a word-bounded "total" (never "Subtotal"; deliberately no trailing boundary so the
    "Totals:" recovery spelling also matches)
  → Total number grammar is strict: grouped thousands (optional cents) or a plain
    two-decimal number — bare integers ("Total items: 3") and VAT percentages ("19%")
    never match as totals
  → Unit-price and summary contexts are excluded: "2 x 5,00 €" / "45,90 x 2" /
    "2 @ $5.00" quantity×price forms and aggregate rows ("3 items, total 45,90") never
    become totals or line items
  → Currency resolution precedence: symbols (€ → EUR, £ → GBP, $ → USD, fixed priority)
    → word-bounded 3-letter ISO codes (conflicting codes fail) → per-parser trusted
    domain map (amazon.com→USD, amazon.co.uk→GBP, amazon.de/fr/it/es→EUR;
    apple.com/appstore.com→USD; Uber ships an EMPTY map — "uber.com" is not a currency
    signal). Bare two-letter country tokens are deliberately NOT signals.
    Unresolved → skip; NO default currency is ever guessed
  → Date: extracted date, falling back to receivedAt; amount must be > 0

PARSE OUTCOMES:
  → EmailParseOutcome sealed: Parsed / Skipped(EmailParseSkipReason) — controlled,
    payload-independent constants only: PARSE_FAILED, CURRENCY_UNRESOLVED
  → parse() keeps the stable null-on-failure contract (this is what the ingestion
    service consumes); parseWithOutcome() carries the typed skip reason

LEGAL PATH:
  → Parsed data funnels into ReceiptLifecycleCoordinator.processEmailReceipt() — the
    ONLY mutation path (receipt save + expense creation + linking + events + side
    effects); the service has NO inline fallback and never dispatches side effects
  → Pre-delegation dedup fingerprint: sha256(provider + normalized merchant +
    amount(2dp) + currency + senderDomain + 1-hour date bucket + orderNumber)
  → messageId is passed to the coordinator only as an HMAC-SHA256 prefix hash, never
    plaintext; HMAC failure fails closed (ParseError, no plaintext fallback)

DIAGNOSTICS:
  → Pipeline EMAIL DiagnosticEvents at front_door / provider_detection / parser /
    validation / outcome / dedupe / coordinator / ingestion stages
  → Controlled reason codes: RESTORE_BLOCKED, PARSER_FAILED (null parse — parser-level
    PARSE_FAILED and CURRENCY_UNRESOLVED both surface here), VALIDATION_FAILED,
    DUPLICATE, UNKNOWN_ERROR
  → messageId/sender recorded hashed only

TESTS:
  → app/src/test/java/com/yourname/expensetracker/data/email/provider/
    (EmailReceiptParserTest, AmazonReceiptParserTest, AppleReceiptParserTest,
    UberReceiptParserTest)
  → app/src/test/java/com/yourname/expensetracker/data/email/
    (EmailReceiptIngestionServiceTest, EmailReceiptIngestionServiceTransactionTest)
  → app/src/test/java/com/yourname/expensetracker/scenarios/EmailReceiptPipelineScenarioTest

FORBIDDEN:
  ❌ Email receipt mutation outside ReceiptLifecycleCoordinator.processEmailReceipt()
  ❌ Defaulting or guessing a currency when no trusted signal exists (must skip)
  ❌ Bare two-letter country tokens as currency signals (removed by design)
  ❌ Persisting or logging raw email bodies, senders or plaintext messageIds
```

---

## Recurring Rule Mutations

```
CREATE rule:
  → RecurringRuleLifecycleCoordinator.createRule()
  → Atomic: inserts rule + generates 12 months of occurrences + reminders + planned rows
  → DatabaseWriteBarrier check + durable lifecycle event

UPDATE rule:
  → RecurringRuleLifecycleCoordinator.updateRule()
  → Atomic: updates rule + reconciles occurrences in single transaction
    (RP-04 A2 reconcileUpdateInCurrentTransaction; fixed-anchor semantics,
    golden no-double-count)
  → DatabaseWriteBarrier check + durable lifecycle event

ACTIVATE rule:
  → RecurringRuleLifecycleCoordinator.activateRule()
  → Atomic: activates + generates future state in single transaction

DEACTIVATE rule:
  → RecurringRuleLifecycleCoordinator.deactivateRule()
  → Atomic: deactivates + DELETES (not cancels) open PLANNED occurrences + planned rows + suppresses reminders
  → Clean regeneration on reactivation (no CANCELLED rows to skip)

DELETE rule:
  → RecurringRuleLifecycleCoordinator.deleteRule()
  → Atomic: deletes reminders + planned + occurrences + rule + lifecycle event

GENERATE occurrences:
  → RecurringLifecycleCoordinator.generateOccurrences()
  → Uses OccurrenceGenerationOptions (controls reminder creation, windows, past-due allowance)
  → Rejects inactive rules
  → Terminal statuses (PAID, CANCELLED, SKIPPED, MISSED, IGNORED) never auto-downgraded
  → RecurringOccurrenceMaterializer.materializeInCurrentTransaction() for use
    inside existing transactions

LINK expense to occurrence:
  → RecurringLifecycleCoordinator.linkExpenseToOccurrenceDetailed()
  → Returns RecurringExpenseReconcileResult (Linked/Unlinked/Relinked/UpdatedLinkedSnapshot/NoMatch/Skipped/Error)
  → Atomic conditional claim (WHERE status=PLANNED AND linkedExpenseId IS NULL)
  → Fulfills planned + suppresses reminders

UNLINK expense from occurrence:
  → RecurringLifecycleCoordinator.unlinkExpenseFromOccurrenceDetailed()
  → Returns RecurringExpenseReconcileResult
  → Reopens PLANNED occurrence status

UPDATE occurrence status:
  → RecurringLifecycleCoordinator.updateOccurrenceStatus(occurrenceId, RecurringOccurrenceStatus, reason)
  → Uses RecurringOccurrenceTransitionPolicy.requireAllowed() for validation
  → Typed RecurringOccurrenceStatus enum (PLANNED, PAID, SKIPPED, MISSED, CANCELLED, IGNORED)
  → Typed RecurringOccurrenceTransitionReason (MATERIALIZER_RESOLUTION, ACTUAL_EXPENSE_LINKED, etc.)

RECONCILE linked expenses after bulk update:
  → RecurringLifecycleCoordinator.reconcileAllLinkedExpensesAfterBulkUpdate()
  → Returns BulkRecurringReconcileResult with per-category counts
  → Triggered by TransactionUpdateKind values: AMOUNT, DATE, CURRENCY, OWNERSHIP, PAYMENT_CORE

DISPATCH reminder:
  → BillReminderWorker → RecurringLifecycleCoordinator.getDispatchableClaimedReminder()
  → Post-claim revalidation: verify occurrence still PLANNED
  → sendNotification() returns NotificationSendResult.Sent/Failed
  → Runtime settings check (enabled/quiet hours via BillReminderSettingsRepository)

FORBIDDEN:
  ❌ ManualRecurringExpenseDao.insert/update/delete outside coordinator
  ❌ RecurringOccurrenceDao.update() outside materializer/coordinator
  ❌ BillReminderManager.markBillPaid() [REMOVED — use createExpense + linkExpenseToOccurrence]
  ❌ Raw String status in updateOccurrenceStatus() (must use RecurringOccurrenceStatus)
  ❌ Direct DAO for critical lifecycle events (must use RecurringLifecycleEventWriter)
  ❌ Any recurring rule mutation outside RecurringRuleLifecycleCoordinator
  ❌ 0L placeholder occurrenceId in reconcile results
  ❌ Bulk reconciliation using global PAID scan

ENFORCEMENT:
  • RecurringArchitectureGuardTest (app/src/test/.../architecture/) enforces the
    single-writer principal: no direct recurring DAO mutation outside
    RecurringRuleLifecycleCoordinator, no legacy markBillPaid, critical events
    only via RecurringLifecycleEventWriter.
```

---

## Privacy / Cloud AI

```
CLOUD AI call:
  → Check EffectiveCloudAiPolicy via CloudAiPrivacyGate (covers CLOUD_AI_GENERAL,
    DAILY_BRIEFING, RECEIPT_ASSIST, BANK_STATEMENT, etc.)
  → If redactBeforeCloud: apply CloudPayloadPolicy via DefaultCloudPayloadPolicy.prepareText()
    / prepareReceiptAssist() / prepareBankStatementValidation() (no generic prepare())
  → PreparedCloudPayload contract used by all 8 cloud providers
    (data/ai/provider/Cloud*Service)
  → Audit via CompositePrivacyGate final decision

RAW DATA storage:
  → Check RawStorageMode (STORE_RAW / STORE_REDACTED / STORE_METADATA_ONLY / DO_NOT_STORE)
  → RawContentSanitizer applies per-mode sanitization for every source:
    email, notification, bank statement, OCR text
  → Processing uses EPHEMERAL in-memory text; DB stores SANITIZED version per mode
  → DO_NOT_STORE = no raw text persisted, processing still works

PRIVACY BLOCKED states:
  → PrivacyBlocked sealed interface with typed subclasses:
    CloudAiDisabled, ReceiptImageUploadDisabled, ExternalGeocodingDisabled,
    NotificationCaptureDisabled, RawExportDisabled, DeviceGpsDisabled,
    BackgroundLocationDisabled, BankStatementAiDisabled, EncryptedBackupDisabled,
    OverpassDisabled, DebugDataPersistenceDisabled, Custom
  → PrivacyDecision.FailClosed: never proceed; blocks execution unconditionally
  → toPrivacyBlocked() maps any denial + capability to a typed PrivacyBlocked
  → 39 call sites across 27 files use blocksExecution() before proceeding
    (blocksExecution() metric; `PrivacyGate.check(...)` itself is invoked from
    51 call sites across 34 production files — different metric, both current)

FORBIDDEN:
  ❌ Cloud HTTP without privacy gate check (must pass through CompositePrivacyGate)
  ❌ Using AiSettings.redactBeforeCloud directly (use EffectiveCloudAiPolicy)
  ❌ Storing raw text when mode is DO_NOT_STORE / METADATA_ONLY
  ❌ Parsing from stored (sanitized) text instead of ephemeral
  ❌ Using raw strings instead of typed PrivacyBlocked for UI states
  ❌ Silently proceeding when PrivacyDecision.FailClosed is returned
```

---

## Backup / Restore

```
BACKUP:
  → Enter BACKUP_EXPORTING mode (blocks all writes, pauses workers)
  → Checkpoint WAL (TRUNCATE)
  → Delete stale WAL/SHM
  → Copy DB file
  → Exit BACKUP_EXPORTING mode (workers rescheduled)

RESTORE:
  → 11 maintenance modes, persisted via SharedPreferences (commit() not apply()):
    NORMAL / BACKUP_EXPORTING / RESTORE_PREPARING / RESTORE_STAGING /
    RESTORE_SWAPPING / RESTORE_VERIFYING / RESTORE_ROLLING_BACK /
    ASSETS_RESTORING / RESETTING_DATABASE /
    RESTORE_COMPLETE_RESTART_REQUIRED / CRITICAL_RECOVERY_REQUIRED
  → 9-state RestoreJournal (append-only file, atomic temp+rename):
    PREPARING → STAGED → SAFETY_BACKUP_CREATED → SWAPPING →
    VERIFYING → ASSETS_RESTORING → COMPLETE
    (on failure: ROLLING_BACK → FAILED)
  → Delete WAL/SHM before installing restored DB
  → On rollback failure: enter CRITICAL_RECOVERY_REQUIRED (fail-closed, persists across restarts)
  → On startup crash-recovery failure: enter CRITICAL_RECOVERY_REQUIRED (NOT reset on later restarts)
  → Forced restart after success (RESTORE_COMPLETE_RESTART_REQUIRED; auto-reset to NORMAL on next clean start)
  → DatabaseReadBarrier / DatabaseWriteBarrier gate all reads and writes during backup/restore
  → Barrier violations throw DatabaseAccessBlockedException (typed access type,
    operation, and current mode); writes are allowed ONLY in NORMAL mode
  → Reads use explicit DatabaseReadPolicy: NORMAL_APP_READ (NORMAL only),
    EXPORT_OR_BACKUP_SNAPSHOT_READ (NORMAL or BACKUP_EXPORTING),
    RESTORE_INTERNAL_STAGED_DB_READ (always denied through the app singleton)

FORBIDDEN:
  ❌ Any DB write outside NORMAL mode (DatabaseWriteBarrier blocks all non-NORMAL modes)
  ❌ Any DB read during restore stages (DatabaseReadBarrier denies during restore)
  ❌ Using stale Room instance after DB swap (forced restart)
  ❌ Exiting maintenance to NORMAL after failed rollback
  ❌ Raw .db export in release builds
```

---

## Accounting Export

```
EXPORT expenses:
  → AccountingExportPolicy determines allowed formats (CSV, QIF, IIF)
  → ExportPrivacyGate checks typed capabilities:
       EXPENSE_EXPORT (plain CSV — always allowed)
       EXPENSE_EXPORT_ENCRYPTED (requires encryptedBackupEnabled)
       EXPENSE_EXPORT_REDACTED (always safe — sensitive fields stripped)
       EXPENSE_EXPORT_RAW (requires debugDataPersistenceEnabled)
       DEBUG_RAW_EXPORT (debug build + consent)
       RAW_DATABASE_EXPORT (debug build + consent — release-denied)
  → CsvCellSanitizer neutralizes formula injection (=, +, -, @) for every CSV/IIF cell
  → ExportOptionsViewModel orchestrates gate + export + diagnostics

FORBIDDEN:
  ❌ EXPENSE_EXPORT_RAW without debugDataPersistenceEnabled consent
  ❌ RAWBACKUP_EXPORT for normal expense export (use EXPENSE_EXPORT)
  ❌ Unsanitized CSV cells (must use CsvCellSanitizer.sanitize / sanitizeIif)
  ❌ Encrypted export privacy check bypass
```

---

## Workers / Background Jobs

```
EVERY worker:
  → WorkerExecutionGuard.runGuarded() / runGuardedWithContext()
       [checks write barrier FIRST, then logs run]
  → All 10 production CoroutineWorkers route through the guard, including
       NotificationIntakeWorker (previously allowlisted, now gated) and the
       SnoozeReminderActionWorker / DismissReminderActionWorker pair.
  → WorkerRunLogger records RUNNING → SUCCESS/FAILED/SKIPPED/RETRY
  → Checkpoint before long loops (ensureActive / writeBarrier.checkWritesAllowed)
  → Guard enforces requiresNotificationPermission via NotificationPermissionChecker
       (durable skip: NOTIFICATION_PERMISSION_DENIED)
  → PrivacyRuntimeWorkerPolicy checks per-worker privacy consent

RETRY CONTRACT:
  → To request a WorkManager retry, THROW RetryableWorkerException.
  → Guard catch precedence:
       WorkerTimeoutPolicy (RETRY → WORKER_TIMEOUT / PROPAGATE_CANCELLATION →
         WORKER_CANCELLED + rethrow) → CancellationException (recorded
         CANCELLED, rethrow) → WorkerCheckpointBlockedException (applies
         BlockedPolicy) → RetryableWorkerException (Retry; sanitized
         reasonCode takes precedence — PR12J-1)
         → classifyTransient(message/IOException) (Retry, WORKER_TRANSIENT_ERROR)
         → Failed (PERMANENT, WORKER_UNHANDLED_EXCEPTION).
  → classifyTransient matches only: timeout / interrupted / deadlock /
       SQLITE_BUSY / database is locked (case-insensitive) OR IOException.
  → A plain RuntimeException with a non-transient message is PERMANENT
       (burns the attempt budget) — do NOT use it to signal "retry".

SCHEDULING:
  → WorkerRegistry.scheduleAll() for startup
  → WorkerSpecScheduler.scheduleFromSpec() for periodic (WorkerSpec.existingWorkPolicy)
  → WorkerSpecScheduler.scheduleAtMidnight() for one-shot (WorkerSpec.oneShotPolicy;
       uses actual worker class; CANCELS existing unique work when spec is disabled)
  → A spec version bump always forces REPLACE over either policy.
  → DailyBriefing reschedules next midnight on Success AND incidental Skips
       (fresh-artifact/no-work/privacy-denied/restore-blocked); only an explicit
       spec-disable ("Worker disabled by spec") stops the chain.

FORBIDDEN:
  ❌ WorkManager.enqueue() outside WorkerRegistry/WorkerSpecScheduler
  ❌ runBlocking inside suspend worker code
  ❌ Writing BackgroundJobRun before checking write barrier
  ❌ Throwing a plain RuntimeException to mean "retry" (it is PERMANENT)
  ❌ Bypassing WorkerExecutionGuard in any CoroutineWorker
       (enforced by WorkerGuardArchitectureGuardTest; its allowlist is empty)
```

---

## Money / Currency

```
AGGREGATE financial totals:
  → MoneyAggregate (preserves source buckets, conversion failures, isPartial)
  → MoneyAggregateBuilder.fromBuckets() for per-currency aggregation
  → AnalyticsCurrencyNormalizer for per-row historical conversion
  → MultiCurrencyRepository for safe aggregate APIs

DASHBOARD display:
  → Use MoneyAggregate.displayAmount + isPartial + warningMessage
  → Propagate quality through adapter chain
  → Show warning when isPartial=true

FORBIDDEN:
  ❌ sumOf { effectiveAmount } across currencies without conversion
  ❌ Raw Double totals in public domain/UI models without currency context
  ❌ Dropping MoneyAggregate.isPartial/warningMessage in adapter mapping
```

---

## Diagnostics

```
EVERY pipeline exit must write a durable event:
  → PipelineDiagnosticEvent (notification, receipt, email, worker)
  → TransactionEvent (expense lifecycle — LifecycleEventType)
  → ReceiptEvent (receipt lifecycle)
  → RecurringLifecycleEvent (recurring lifecycle)
  → WarrantyLifecycleEvent (warranty lifecycle)
  → BackgroundJobRun (worker lifecycle)
  → BankStatementImportRun (bank statement lifecycle)
  (GroupLifecycleEvent / InvestmentEvent no longer exist in code — group
   audit events go through TransactionLifecycleEventWriter where required)

Exception messages sanitized via EventMetadataSanitizer.sanitizeExceptionMessage():
  → Digit sequences (12+), IBANs, JWT tokens, Bearer tokens → [REDACTED]
  → File paths → [PATH] (not [REDACTED])
  → Messages truncated to MAX_STRING_LENGTH (256 chars)
  → URLs and email addresses are NOT explicitly matched (may be caught incidentally)

FORBIDDEN:
  ❌ Timber-only logging for pipeline decisions (must also write durable event)
  ❌ Swallowing CancellationException (always rethrow)
  ❌ Logging unsanitized exception messages to durable storage
```

---

## Lifecycle Events

```
CRITICAL event (provenance — OCCURRENCE_PAID, PLANNED_FULFILLED):
  → RecurringLifecycleEventWriter.writeCritical()
  → Always writes, returns event ID
  → Must be called for all state-changing operations

DIAGNOSTIC event (informational — REMINDER_SCHEDULE_SKIPPED, etc.):
  → RecurringLifecycleEventWriter.writeDiagnostic()
  → Best-effort: swallows exceptions
  → Acceptable to lose on transient failure

FORBIDDEN:
  ❌ Writing lifecycle events directly through DAO insert
  ❌ Swallowing writeCritical() failures (must fail the operation)
```

---

## Investment Mutations

```
ADD HOLDING:
  → InvestmentTracker.addHolding(investment)
  → DatabaseWriteBarrier check → validation (symbol/name/quantity/purchasePrice/currency/purchaseDate/currentPrice/purchaseFees)
  → database.withTransaction {
      InvestmentDao.insert(validated)
      InvestmentValueDao.insert(initial snapshot with purchasePrice * quantity)
      InvestmentTransactionDao.insert(type="BUY")
    }
  → Result.success(id) or Result.failure(IllegalArgumentException)

UPDATE PRICE:
  → InvestmentTracker.updatePrice(investmentId, newPrice)
  → DatabaseWriteBarrier check
  → require(newPrice > 0 finite)
  → database.withTransaction {
      InvestmentDao.updatePrice(id, newPrice, timestamp)
      InvestmentValueDao.insert(snapshot with dayChange/dayChangePercent)
    }

FORBIDDEN:
  ❌ InvestmentDao.insert() outside InvestmentTracker
  ❌ InvestmentDao.update() outside InvestmentTracker
  ❌ InvestmentDao.updatePrice() directly (must pass through tracker validation + value history)
  ❌ InvestmentValueDao.insert() outside updatePrice/addHolding transaction
  ❌ InvestmentTransactionDao.insert() outside addHolding transaction
  ❌ InvestmentDao.getTotalPortfolioValue/getTotalUnrealizedGainLoss/getTotalInvestedAmount()
      [all @Deprecated — raw Double may mix currencies; use getPortfolioSummaryAggregate()]
  ❌ InvestmentTracker.getPortfolioSummary() [Deprecated — raw Double; use getPortfolioSummaryAggregate()]
  ❌ Summing investment values across different currencies without MoneyAggregateBuilder
```

---

## Group Mutations

```
CREATE group:
  → SharedExpenseManager.createGroup(name, description, memberNames, defaultCurrency, currentUserName)
  → SharedExpenseDataPort.createGroupWithMembers() → SharedExpenseDataPortAdapter
    (write-barrier checked)
  → GroupTransactionCoordinator.createGroupWithMembersAtomic()
  → Atomic inserts: ExpenseGroupDao.insert + GroupMemberDao.insertAll
    (single-currentUser validation, joinedAt normalization, currentUserGroupKey invariant)
  → NOTE: the planned GroupLifecycleCoordinator wrapper (PR-E15) was never built
    and was removed (GR-14u36, owner decision 2026-09-11); group lifecycle
    operations run through SharedExpenseManager / SharedExpenseDataPortAdapter.

ADD member:
  → SharedExpenseManager.addMember(groupId, name, email)
  → GroupTransactionCoordinator.addMemberToGroup()
  → Validates: group exists + active, no duplicate name, no second currentUser
  → GroupMemberDao.insert within transaction

REMOVE member:
  → SharedExpenseManager.removeMember(member)
  → Validates: member exists, no paid expenses, no split references
  → GroupMemberDao leftAt update via SharedExpenseDataPortAdapter.removeMember()

ADD expense to group (standalone — no system link):
  → SharedExpenseManager.addExpense(groupId, expenseId, ...)
  → Validates: description/amount, payer is member, split payload valid (SHR-17)
  → SharedExpenseDataPort.addExpense() → GroupTransactionCoordinator.addExpenseToGroup()
  → Validates: group active, payer is active member, custom split format,
    single-currency group policy (E4-005)
  → GroupExpenseDao.insert within transaction (idempotency key PR8)

ADD expense to group (with system expense link):
  → GroupTransactionCoordinator.addExpenseWithLink(groupId, systemExpenseId, ...)
  → GroupExpenseDao.insert with expenseId FK + ownership update via
    TransactionLifecycleCoordinator.updateOwnershipDbOnlyV2() in one transaction
  → PostCommitActionBatch side effects run only after the outer commit

CREATE system expense AND link to group (atomic):
  → GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()
  → database.withTransaction { TransactionLifecycleCoordinator.createExpenseDbOnlyV2(DEFER side effects) + GroupExpenseDao.insert }
  → Throws on failure → rollback both (GroupExpenseAtomicRollback)

RECORD settlement:
  → REMOVED — no production settlement writer exists; GroupSettlementDao is
    read-only (consumed by GroupBalanceCalculator / SettlementCalculator)

ARCHIVE / RESTORE / DELETE group:
  → SharedExpenseManager.archiveGroup() / restoreGroup() / deleteGroup()
  → archiveGroup: sets isActive=false (soft delete)
  → deleteGroup → GroupTransactionCoordinator.permanentlyDeleteGroup()
    → deleteGroupAtomic: hard delete with cascade cleanup (GroupExpenseDao +
      GroupMemberDao + ExpenseGroupDao), clears shared-expense flags on linked
      system expenses, writes TransactionLifecycleEvent (BULK_UPDATED)

CALCULATE balances:
  → GroupBalanceCalculator.calculateMemberBalance(groupId, memberId)
  → Read-only: sums paidTotal, owedShareTotal via SplitCalculator, settlements
  → Returns GroupMemberBalance (isSettled when |netBalance| <= 0.01)
  → SharedExpenseManager.calculateBalances() for the all-member snapshot

FORBIDDEN:
  ❌ ExpenseGroupDao.insert() outside GroupTransactionCoordinator
  ❌ GroupMemberDao.insert()/insertAll() outside GroupTransactionCoordinator
  ❌ GroupExpenseDao.insert() outside GroupTransactionCoordinator
  ❌ GroupSettlementDao.insert() — no legal writer exists today
  ❌ GroupLifecycleEventDao.insert() (DAO is dormant — no production writer;
     group audit events go through TransactionLifecycleEventWriter where required)
  ❌ Reviving the removed GroupLifecycleCoordinator wrapper (GR-14u36)
  ❌ Any group mutation without a barrier-checked entry (SharedExpenseManager
     facade or GroupTransactionCoordinator)
  ❌ Hard-deleting a group without checking for linked system expenses

ENFORCEMENT:
  • The domain `GroupTransactionCoordinator` interface is implemented by
    `data/database/GroupTransactionCoordinator.kt`, which executes atomic group/
    member/expense writes inside `DomainTransactionRunner` (Room withTransaction)
    with `DatabaseWriteBarrier` checks, `TransactionLifecycleCoordinator` for
    system-expense creation, and `PostCommitActionRunner` for deferred side effects.
  • Legal writer set enumerated in config/guards/db_ownership_policy.yml;
    verified by scripts/verify_db_access_boundaries.py (ratchet) in CI.
```

---

## Subscription Mutations

```
CREATE / ACCEPT subscription:
  → SubscriptionManagerEngine.validateAndCreate(CreateSubscriptionRequest)
    (candidates accepted via acceptCandidate())
  → DatabaseWriteBarrier check
  → Atomic: inserts subscription + price history + candidate resolution + usage baseline
  → Uses RecurringExpenseRepository + SubscriptionPriceHistoryDao + SubscriptionUsageDao
  → Returns Result<ManualRecurringExpense>

RECORD price change:
  → SubscriptionManagerEngine.recordPriceChange(subscriptionId, newPrice, effectiveDate)
  → Atomic: updates subscription.currentPrice + inserts SubscriptionPriceHistory row
  → Returns Result<Unit>

RECORD usage:
  → SubscriptionManagerEngine.recordUsage(subscriptionId, usageData)
  → SubscriptionUsageDao.insert within transaction scope

ANALYZE subscription health:
  → SubscriptionManagerEngine.analyzeSubscription(subscriptionId)
  → Read-only: computes health score 0-100 (price fairness + usage + renewal risk + market rate)
  → Generates recommendations list

CALCULATE savings:
  → SubscriptionManagerEngine.calculatePotentialSavings()
  → @Deprecated (DeprecationLevel.WARNING) — raw Double; NO MoneyAggregate
    alternative exists yet (REC-19: max savings per subscription, not summed)
  → Use getTotalMonthlySubscriptionCostAggregate() for currency-safe cost totals

FORBIDDEN:
  ❌ SubscriptionPriceHistoryDao.insert() outside recordPriceChange
  ❌ SubscriptionCandidateDao.insert/delete outside validateAndCreate/acceptCandidate
  ❌ SubscriptionManagerEngine.getTotalMonthlySubscriptionCost() [Deprecated — raw Double across currencies]
  ❌ SubscriptionManagerEngine.calculatePotentialSavings() [Deprecated WARNING — raw Double; no aggregate alternative exists yet]
  ❌ Direct DAO mutations bypassing engine validation
```

---

## Categorization / Merchant Learning

```
CATEGORIZE expense (auto):
  → CategorizationEngine.categorize(merchant) /
    categorizeWithContext(merchant, amount, timestamp)
  → Read-only: 6-layer cascade (Exact → Canonical → Greeklish → Fuzzy → Semantic → Context)
  → Returns CategorizationResult with MatchType + confidence
  → No persistent side effects

LEARN merchant category (user feedback):
  → CategorizationEngine.learnMerchantCategory(merchantName, categoryId)
  → DatabaseWriteBarrier check
  → MerchantCategoryRepository.insert(merchantName → categoryId)
  → Invalidates all caches

DEBUG categorize:
  → CategorizationEngine.debugCategorize(merchantName, amount)
  → Same 6-layer cascade with full trace logging
  → Returns DebugCategorizationResult (prediction + layerDebugStack + candidateDebugInfo)

FORBIDDEN:
  ❌ MerchantCategoryRepository.insert() outside CategorizationEngine.learnMerchantCategory()
  ❌ MerchantCanonicalizer.canonicalize() used for writes (read-only normalization)
  ❌ Direct cache map mutation (must go through invalidateAllCaches)
  ❌ Skipping layers in the cascade (must respect 6-layer priority order)
```

---

## Bank Statement Mutations

```
PROCESS bank statement (image/PDF):
  → BankStatementLifecycleProcessor.processBankStatement(uri)
    [UI entry: ReceiptLifecycleCoordinator.processBankStatement() delegates here;
     ReceiptRepository.processBankStatement() is deprecated]
  → SHA-256 pre-OCR dedup check against BankStatementImportRunDao
  → OCR execution → transaction parsing
  → AiSettings.AI_BANK_STATEMENT privacy check
  → ValidateBankStatementTransactionsUseCase.validateTransactions()
  → PendingReviewDao.insert for human review
  → Three-layer dedup against ExpenseDao + BankStatementImportItemDao
  → BankStatementImportRunDao.insertRun() ledger entry
  → PendingReviewDao.batchInsert() all validated items
  → Full lifecycle: import is NOT a separate step — everything happens in processBankStatement()

FORBIDDEN:
  ❌ BankStatementImportItemDao.insert outside processor
  ❌ BankStatementImportRunDao.insertRun outside processor
  ❌ Bypassing pre-OCR dedup (creates duplicate import runs)
  ❌ Bypassing AI validation when AiSettings.AI_BANK_STATEMENT is enabled
  ❌ Skipping three-layer dedup (expense-level + item-level + run-level)
```

---

## Split / Template Mutations

```
CREATE split template:
  → EnhancedSplitManager.createTemplate(name, totalSplits, splitType, shares)
  → DatabaseWriteBarrier check → validation
  → SplitTemplateDao.insertTemplate with serialized shares
  → Returns template ID
  → EnhancedSplitManager is also the legal writer for deleteTemplate() /
    setDefaultTemplate() (SplitTemplateDao writes)

ASSIGN split items to participants:
  → REMOVED — EnhancedSplitManager.assignItemsToParticipants() no longer exists;
    SplitItemAssignmentDao mutation methods (deleteAllForExpense /
    insertAssignments) have NO production writer today. Assignment reads remain
    via getAssignmentsForExpense() / getParticipantTotals().

FORBIDDEN:
  ❌ SplitTemplateDao.insertTemplate outside EnhancedSplitManager.createTemplate()
  ❌ Reintroducing SplitItemAssignmentDao mutation callers outside a documented
     transactional writer (currently none exists)
  ❌ Splitting expenses with raw Double (must use Money/BigDecimal precision via Money)
```

---

## Notification Capture

```
CAPTURE notification (system/messaging):
  → NotificationIntakeCoordinator.capture(notificationData, source)
  → Computes dedup fingerprint (packageName + tag + key + hash)
  → Checks RawStorageMode: if DO_NOT_STORE/METADATA_ONLY, encrypts/redacts payload
  → NotificationIntakeDao.insert with dedupeKey + encrypted payload
  → Enqueues NotificationIntakeWorker via WorkManager (enqueueUniqueWork, awaited
    with atomic failure transition on enqueue failure — RP-10 10b)

CAPTURE for retry:
  → NotificationIntakeCoordinator.captureForRetry(notificationData, source)
  → Same flow with 5-second enqueue delay

FORBIDDEN:
  ❌ NotificationIntakeDao.insert outside coordinator
  ❌ Storing raw notification text when RawStorageMode is DO_NOT_STORE
  ❌ Skipping dedup fingerprint computation
  ❌ Direct WorkManager enqueue outside capture flow
```

```
CAPTURE HYGIENE (RP-10 10b/10c):
  → In-memory dedup: NotificationCaptureDeduper.tryStart(key, windowMs) — atomic
    check-and-insert under a lock; timestamps come from MonotonicTimeProvider.nowNanos()
    and are stored in NANOSECONDS (windowMs converted once per call), so the dedupe
    window is immune to wall-clock jumps — a backward jump cannot suppress identical
    re-posts and a forward jump cannot disable the window
  → Deduper hygiene: access-order LinkedHashMap capped at 1000 entries (oldest evicted);
    remove(key) on error/cancellation paths; cleanupExpired(maxAgeMs) on service cycle
  → Transient payload framing: NotificationTransientPayloadCrypto — AES-256/GCM/NoPadding
    (Keystore-backed key, random 12-byte nonce per encryption, 128-bit GCM tag). The
    five payload fields are framed BEFORE encryption: per field a presence byte
    (0 = null / 1 = present) then, when present, a 4-byte big-endian length + UTF-8
    bytes — null vs empty stays distinct and NUL-containing content round-trips exactly
    (replaces the colliding NUL-join framing; the legacy NUL-split parse remains only
    as a decrypt fallback for in-flight old-framed rows; both parses failing throws
    GeneralSecurityException with a fixed, content-free message)
  → Sensitive extras key policy: NotificationCaptureService.SENSITIVE_EXTRAS_KEYS — a
    fixed key set (Android system/media keys, MessagingStyle content keys, and
    financial/personal keys in snake_case and camelCase) filtered case-insensitively
    out of extrasJson; values >= 2000 chars dropped (bitmap guard); serialization
    failure yields "{}"
  → Retry backoff: NotificationIntakeRetryPolicy.backoffFor(attempt) is the single
    ladder (30s → 2m → 10m → 30m → 1h, attempt 1-based, clamped to ladder bounds),
    consumed by BOTH NotificationIntakeWorker per-attempt retry scheduling AND
    NotificationIntakeCoordinator enqueue-failure transitions
    (nextAttemptAt = now + backoffFor(attempts + 1)) — one source, no drift

FORBIDDEN:
  ❌ Reading wall-clock time for dedupe windows (must use MonotonicTimeProvider)
  ❌ Framing transient payloads with delimiter joins (NUL or otherwise)
  ❌ Persisting extras keys matching SENSITIVE_EXTRAS_KEYS
  ❌ Duplicating the backoff ladder locally in the worker or coordinator
```

---

## Anomaly Alerting

```
CHECK AND ALERT:
  → AnomalyAlertOrchestrator.checkAndAlert(expense)
  → Guard: skips non-PURCHASE and isNotMine expenses
  → In-flight dedup via inFlightExpenseIds set
  → AnomalyDetector checks amount vs 90-day category history
  → Cooldown check: 24h per merchant, 12h per category
  → Dedup check against recent alerts (same expenseId)
  → Severity filter: only HIGH severity triggers notification
  → NotificationService.send() if all checks pass
  → AnomalyAlertDao.insert for audit trail

FORBIDDEN:
  ❌ Bypassing cooldown window for alert creation
  ❌ Sending notifications for LOW/MEDIUM severity alerts
  ❌ AnomalyAlertDao.insert outside orchestrator
  ❌ Skipping dedup check against recent alerts
  ❌ Skipping in-flight dedup check (inFlightExpenseIds)
```

---

## Bill Negotiation

```
ANALYZE negotiation opportunities:
  → SmartBillNegotiationEngine.analyzeNegotiationOpportunities()
  → Read-only: detects service type (MOBILE/INTERNET/STREAMING/INSURANCE/ENERGY/etc.)
  → Queries MarketRateProvider for comparable rates
  → Calculates negotiation power score + savings potential
  → Generates negotiation scripts + retention offers
  → Returns List<NegotiationOpportunity>

RECORD outcome:
  → SmartBillNegotiationEngine.recordNegotiationOutcome(subscriptionId, outcome, newPrice, savings, notes)
  → DatabaseWriteBarrier check → validation (oldAmount, currency, newPrice)
  → Atomic within database.withTransaction:
      NegotiationOutcomeDao.insert(outcomeEntity)
      If SUCCESS/PARTIAL + valid newPrice: priceHistoryDao.insert() + recurringExpenseRepository.update()
  → Returns Result<Unit>
  → Negotiation history via getNegotiationHistory()

FORBIDDEN:
  ❌ MarketRateProvider queries for non-eligible service types
  ❌ SubscriptionPriceHistoryDao.insert outside recordNegotiationOutcome
  ❌ Skipping negotiation power validation before generating offers
  ❌ NegotiationOutcomeDao.insert() outside recordNegotiationOutcome
  ❌ Skipping input validation (oldAmount, currency, newPrice)
```

---

## Warranty Auto-Create

```
AUTO-CREATE warranty from receipt:
  → AutoCreateWarrantyFromReceiptUseCase.execute(receiptId, ocrText)
  → WarrantyTextExtractor extracts warranty terms via regex patterns
  → Confidence threshold: high ≥ 70%, medium ≥ 40%, low < 40%
  → Checks existing warranties on same receipt (dedup)
  → High confidence → auto-create Warranty record
  → Low/medium confidence → creates WarrantyReviewDraft for user approval
  → Half-open (exclusive) end-date semantics
  → PrivacyGate.check() before accessing receipt data

FORBIDDEN:
  ❌ Creating warranty without confidence assessment
  ❌ Auto-creating warranty below 70% confidence threshold
  ❌ Creating duplicate warranties for same receipt
  ❌ Accessing receipt data without PrivacyGate check
  ❌ Using inclusive end-date semantics for warranty expiry
```

---

## Location Resolution

```
RESOLVE location for merchant/expense:
  → LocationResolver.resolve(merchantName, addressHint, lat/lng hint)
  → Priority cascade:
      1. User correction override → return immediately
      2. LocationCache → return cached result
      3. GPS bias → Nominatim with GPS coordinates
      4. Name-only → Nominatim with merchant name
      5. Overpass POIs → query nearby points of interest
      6. Unresolved → return null with UNRESOLVED status
  → Privacy gates: DeviceGpsDisabled, ExternalGeocodingDisabled, OverpassDisabled
  → Haversine distance + Null Island filter (0,0)
  → Merchant cluster affinity for grouped location suggestions
  → Cache write on successful resolution

FORBIDDEN:
  ❌ Skipping privacy gate checks for GPS/geocoding/Overpass
  ❌ Using location resolution when ExternalGeocodingDisabled is active
  ❌ Returning Null Island (0,0) coordinates without filtering
  ❌ Cache write without successful resolution
  ❌ Bypassing user correction priority
```

---

## Business Reports / Tax

```
GENERATE business expense report:
  → BusinessExpenseReportGenerator.generateReport(year, project/category filters)
  → Read-only: aggregates expenses by category/project
  → Includes mileage deduction reports from business mileage log
  → Identifies missing receipts for audit trail
  → Enforces purchase-only filtering at boundary (excludes transfers)
  → Returns BusinessExpenseReport (text + CSV ready)

ESTIMATE taxes:
  → TaxEstimator.estimateTaxes(fiscalYear, income, deductions)
  → Configurable tax rates + progressive brackets
  → VAT calculations per jurisdiction
  → MoneyAggregate for multi-currency income/deductions
  → [DEFERRED_DESIGN] — placeholder implementation, rates are configurable not hardcoded

GENERATE CSV export:
  → BusinessExpenseReportGenerator.generateCSVExport(report)
  → CsvCellSanitizer.sanitize() on all cell values (formula injection guard)
  → Returns CSV string for file write

FORBIDDEN:
  ❌ Including non-purchase transactions in business report
  ❌ CsvCellSanitizer bypass for any CSV export cell
  ❌ TaxEstimator with hardcoded tax rates (must use configuration)
  ❌ Single-currency assumption for multi-currency business expenses
```

---

## Analytics

```
CATEGORY analytics:
  → AdvancedAnalyticsEngine.getCategoryAnalytics(normalizedInput)
  → Requires NormalizedAnalyticsInput (from AnalyticsInputAssembler.build())
  → Returns: spending totals, trends, sparklines, percentiles, velocity, category comparisons
  → [Deprecated self-fetching overload exists at DeprecationLevel.WARNING]

MERCHANT analytics:
  → AdvancedAnalyticsEngine.getMerchantAnalytics(normalizedInput)
  → Requires NormalizedAnalyticsInput
  → Returns: visit frequency, loyalty score, price trends, consistency, streaks, day-of-week distribution
  → [Deprecated self-fetching overload exists at DeprecationLevel.ERROR]

SPENDING patterns:
  → AdvancedAnalyticsEngine.getSpendingPatterns(normalizedInput)
  → Requires NormalizedAnalyticsInput
  → Returns: day-of-week, time-of-day, detected patterns
  → [Deprecated self-fetching overload exists at DeprecationLevel.WARNING]

STATISTICAL insights:
  → AdvancedAnalyticsEngine.getStatisticalInsights(normalizedInput)
  → Requires NormalizedAnalyticsInput
  → Returns: histogram, percentiles, volatility, coefficient of variation
  → [Deprecated self-fetching overload exists at DeprecationLevel.WARNING]

ASSEMBLE analytics input:
  → AnalyticsInputAssembler.build(filters)
  → Fetches expenses, filters (spending-only, exclude-not-mine)
  → Normalizes via AnalyticsCurrencyNormalizer
  → Categorizes included/excluded expenses
  → Computes data quality metrics (confidence penalty/multiplier)
  → Returns NormalizedAnalyticsInput (self-contained)

FINANCIAL health score:
  → FinancialHealthCalculator.calculateHealthScores(expenses, budgetStatuses, pendingReviews, todayStreak, weekStreak, monthStreak, noSpendStreak)
  → Combines: budget health (max 25) + spending control (max 25) + cleanliness (max 10) + bonus (max 10)
  → Composite: Today 20% + Week 30% + Month 50% → score 0-100
  → Returns HealthScoreResult

FORBIDDEN:
  ❌ AdvancedAnalyticsEngine.getMerchantAnalytics() with self-fetching [DeprecationLevel.ERROR]
  ❌ AdvancedAnalyticsEngine.getCategoryAnalytics/getSpendingPatterns/getStatisticalInsights with self-fetching
  ❌ Bypassing AnalyticsInputAssembler for analytics computations
  ❌ Using raw Double totals across different currencies in analytics output
  ❌ Dropping data quality metrics (confidence penalty/multiplier) in adapter mapping
```

---

## Import

```
IMPORT expenses from file/content:
  → ImportCoordinator.importFromContent(content, sourceFormat)
  → Detects format: CSV_LEGACY / CSV_FULL / JSON_V1 / JSON_V2 / UNKNOWN
  → Delegates to CsvExpenseImporter or JsonExpenseImporter
  → Each importer: parses → validates → calls TransactionLifecycleCoordinator.createExpense() per row
    (also inserts missing categories via CategoryDao)
  → Returns ImportResult(success, importedCount, skippedCount, errorCount, errors, expenseIds)

FORBIDDEN:
  ❌ CsvExpenseImporter/JsonExpenseImporter used outside ImportCoordinator
  ❌ Importing without format detection
  ❌ Bypassing TransactionLifecycleCoordinator for imported expense creation
  ❌ Skipping validation errors (must report in ImportResult)
```

---

## Financial Rescue Path

```
RESCUE database (last resort — bypasses migration chain):
  → FinancialRescueCoordinator.runRescueIfNeeded()
  → Guard: RescueConfig.ENABLE_FINANCIAL_RESCUE must be true [compile-time toggle, default false]
  → Guard: rescue_completed.txt marker check (one-shot; returns ALREADY_DONE if present)
  → Guard: DB file existence check (returns SKIPPED/NO_DB if no file)
  → Context: the Room migration baseline is v145 (see DatabaseSchemaPolicy:
    MIGRATION_BASELINE = 145, UNSUPPORTED_VERSIONS = 1..<145); databases below
    the baseline are intentionally NOT migrated — this rescue path is their
    only supported upgrade route.

  STEP 1 — Read user version:
    → Raw SQLiteDatabase.openDatabase(READ_ONLY) on old DB
    → Read db.version (Room schema version)

  STEP 2 — Snapshot financial tables:
    → Raw SELECT * on 6 tables (categories, expenses, expense_groups, group_members, group_expenses, split_item_assignments)
    → Dynamic column mapping via PRAGMA table_info (handles schema drift)
    → Gracefully skips missing tables
    → Returns FinancialRescueSnapshot

  STEP 3 — Write JSON safety net:
    → Serializes snapshot to {filesDir}/rescue_snapshot.json

  STEP 4 — Backup DB files:
    → Copies *.db / *.db-wal / *.db-shm / *.db-journal → {filesDir}/db_backups/*.rescue_backup

  STEP 5 — Move aside old DB:
    → Renames *.db → *.legacy.<timestamp> (removes from Room's view)
    → Room creates fresh database on next access

  STEP 6 — Create fresh Room DB + import:
    → AppDatabase.fileBuilder(context).build() (empty tables, latest schema)
    → BEGIN TRANSACTION:
        importCategories: INSERT OR REPLACE (sanitized name/icon/color)
        importExpenses: INSERT OR REPLACE (FK validated, nulls inapplicable columns)
        importExpenseGroups: INSERT OR REPLACE
        importGroupMembers: INSERT OR REPLACE (dedup by groupId+name, single currentUser)
        importGroupExpenses: INSERT OR REPLACE (FK validated against valid groups/members/expenses)
        importSplitItemAssignments: INSERT OR REPLACE (FK validated against valid expenses)
    → COMMIT

  STEP 7 — Mark done:
    → Write rescue_completed.txt with timestamp

  ON FAILURE (any exception):
    → Rollback transaction (fresh DB stays clean)
    → Restore moved-aside files → original names
    → Return FAILURE(error)

FORBIDDEN:
  ❌ RescueConfig.ENABLE_FINANCIAL_RESCUE = true in production builds (compile-time default false)
  ❌ Running rescue when rescue_completed.txt already exists
  ❌ Skipping backup before moving DB files
  ❌ Skipping JSON snapshot before destructive operations
  ❌ Importing without FK validation (orphaned rows produce data corruption)
  ❌ Using Room migrations instead of raw SQLite for rescue path (by design)
  ❌ Manual invocation outside RescueActivity
  ❌ Leaving ENABLE_FINANCIAL_RESCUE = true after rescue completes
  ❌ Any rescue operation without rollback capability
```

---

## Category Assignment

```
ASSIGN default category to expense:
  → DefaultExpenseCategoryAssignmentService.assignCategoryIfUnset(expenseId, categoryId, source, correlationId)
      [implements ExpenseCategoryAssignmentPort; returns CategoryAssignmentOutcome:
       Assigned / SkippedAlreadySet / SkippedExpenseMissing / Failed]
  → DatabaseWriteBarrier check
  → Guard: skips if expense already has a category set
  → database.withTransaction {
      ExpenseDao.updateCategory(expenseId, categoryId)
      TransactionEventDao.insert with eventType "EXPENSE_CATEGORY_ASSIGNED" + correlationId
    }
  → CancellationException rethrown; other failures mapped to CategoryAssignmentOutcome.Failed

FORBIDDEN:
  ❌ ExpenseDao.updateCategory() outside DefaultExpenseCategoryAssignmentService / TransactionLifecycleCoordinator
  ❌ Skipping TransactionEvent write during category assignment
  ❌ Assigning category without checking if category already set
```

---

## Budget Forecasting

```
GENERATE spending forecast:
  → BudgetForecastingEngine.generateForecastResult(budget, forecastPeriodDays)
    [UI caller: BudgetForecastingViewModel]
  → Reads historical expense data via ExpenseDao + ExpenseRepository
  → Normalizes via AnalyticsCurrencyNormalizer
  → Computes projected spending using time-series patterns
  → insertForecast() → BudgetForecastDao.insertWithDeactivation() persists result
    (write-barrier checked; UNIQUE-conflict maps to DuplicateInSameInstant)

FORBIDDEN:
  ❌ BudgetForecastDao insert/update outside BudgetForecastingEngine.insertForecast()
  ❌ Forecasting without historical expense normalization
  ❌ Persisting forecasts without AnalyticsCurrencyNormalizer normalization
```

---

## Shared Expense Management (Groups — Domain Facade)

```
CREATE shared group expense:
  → SharedExpenseManager.createGroup(name, description, memberNames, defaultCurrency)
  → SharedExpenseDataPort.createGroupWithMembers() → SharedExpenseDataPortAdapter
    → GroupTransactionCoordinator.createGroupWithMembersAtomic() (multi-table atomic write)

ADD shared expense:
  → SharedExpenseManager.addExpense(groupId, expenseId, ...)
  → SplitCalculator computes member shares (myShareAmount recompute, SHR-12)
  → SharedExpenseDataPort.addExpense() → GroupTransactionCoordinator
    (addExpenseToGroup / addExpenseWithLink — multi-table atomic write)

REMOVE shared expense member:
  → SharedExpenseManager.removeMember(member)
  → SharedExpenseDataPort.removeMember()

FORBIDDEN:
  ❌ SharedExpenseDataPort mutations outside SharedExpenseManager
     (this facade is now the PRIMARY group path — see Group Mutations;
      GroupLifecycleCoordinator no longer exists)
```

---

## Recurring Plan Projection

```
PROJECT future occurrences (read-only):
  → RecurringLifecycleCoordinator.projectOccurrences(ruleId, startDate, endDate)
  → Returns transient (unsaved) occurrences for the window; inactive rules
    project nothing
  → Deliberately NO write-barrier check (read-only projection)
  → Consumers: CashFlowCalculator, FinancialStressForecastEngine,
    ForecastInputAssembler

PERSIST planned rows for projected occurrences:
  → RecurringPlanProjectionService.projectFromOccurrencesInCurrentTransaction()
  → PlannedExpenseDao.insertPlannedExpense() for PLANNED occurrences without one
    (deduped by sourceOccurrenceKey)
  → ONLY called from inside RecurringRuleLifecycleCoordinator rule-mutation
    transactions (projectFromRule was deleted — RP-04 P4-006 dead-code disposition)

FORBIDDEN:
  ❌ PlannedExpenseDao.insertPlannedExpense() outside
     RecurringPlanProjectionService inside a rule-mutation transaction
  ❌ Projecting occurrences without validating rule is active
  ❌ Duplicate planned rows (must check sourceOccurrenceKey first)
```

---

## Spending Challenges

```
READ active challenges + progress:
  → SpendingChallengeManager.getActiveChallengesSnapshot()
  → SpendingChallengeManager.getChallengeProgress(challenge)
  → Read-only; NOTE: progress spend is currently computed via deprecated raw
    Double ExpenseDao SUM aggregates (getTotalSpentBetween / getCategorySpentInPeriod
    — @Deprecated "Use MultiCurrencyRepository"; ownership-filtered (isNotMine = 0)
    but not currency-safe — marked for MultiCurrencyRepository migration)
  → Returns progress percentage against challenge target

CREATE challenge:
  → SpendingChallengeManager.createChallenge(...)

DEACTIVATE expired challenges:
  → SpendingChallengeRepository.deactivateChallenges(challengeIds, updatedAt)
       [write-barrier check inside repository, then SpendingChallengeDao.deactivateChallenges]

FORBIDDEN:
  ❌ SpendingChallengeRepository.deactivateChallenges() without a write-barrier check
  ❌ Adding new raw Double SUM aggregation paths for challenge progress
     (must migrate to MultiCurrencyRepository — ownership- and currency-safe)
```
