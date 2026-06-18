# Legal Paths — Architecture Law

> **Purpose:** Define the ONE allowed implementation path for each major operation.  
> **Rule:** Any code that uses a different path is a bug, regardless of whether it "works."  
> **Enforcement:** Static guards (Detekt/grep) + DeprecationLevel.ERROR + contract tests.

---

## Expense Mutations

```
CREATE expense:
  Any source (UI/notification/receipt/email/bank/import/group)
    → TransactionLifecycleCoordinator.createExpense() or createExpenseStandalone()
    → ExpenseDao.insertAtomic() [ONLY from coordinator]
    → TransactionEvent with LifecycleEventType.CREATED
    → Post-commit side effects via TransactionSideEffectPlanner → TransactionSideEffectDispatcher

FORBIDDEN:
  ❌ ExpenseDao.insert() from any repository directly
  ❌ ExpenseDao.insertAll() outside debug/migration
  ❌ Any expense insert without TransactionEvent (LifecycleEventType.CREATED)
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

---

## Receipt Mutations

```
PROCESS receipt (camera/gallery/file/PDF):
  → ReceiptLifecycleCoordinator.processReceiptInput()
  → ReceiptRepository.processReceipt() [OCR/parse only, returns draft]
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
  → ReceiptMatchLifecycleService.saveMatchSuggestion() / approveMatchSuggestion()
  → ReceiptMatchLifecycleService.rejectAllSuggestions() / clearMatchForReceipt()
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

## Recurring Rule Mutations

```
CREATE rule:
  → RecurringRuleLifecycleCoordinator.createRule()
  → Atomic: inserts rule + generates 12 months of occurrences + reminders + planned rows
  → DatabaseWriteBarrier check + durable lifecycle event

UPDATE rule:
  → RecurringRuleLifecycleCoordinator.updateRule()
  → Atomic: updates rule + regenerates occurrences in single transaction
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
  → materializeInCurrentTransaction() for use inside existing transactions

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
```

---

## Privacy / Cloud AI

```
CLOUD AI call:
  → Check EffectiveCloudAiPolicy via CloudAiPrivacyGate (covers CLOUD_AI_GENERAL,
    DAILY_BRIEFING, RECEIPT_ASSIST, BANK_STATEMENT, etc.)
  → If redactBeforeCloud: apply CloudPayloadPolicy via DefaultCloudPayloadPolicy.prepareText()
    / prepareReceiptAssist() / prepareBankStatementValidation() (no generic prepare())
  → PreparedCloudPayload contract used by all 7 cloud providers
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
  → 30+ callers use blocksExecution() before proceeding

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
  → WorkerRunLogger records RUNNING → SUCCESS/FAILED/SKIPPED/RETRY
  → Checkpoint before long loops (ensureActive / writeBarrier.checkWritesAllowed)
  → Guard enforces requiresNotificationPermission via NotificationPermissionChecker
       (durable skip: NOTIFICATION_PERMISSION_DENIED)
  → PrivacyRuntimeWorkerPolicy checks per-worker privacy consent

RETRY CONTRACT:
  → To request a WorkManager retry, THROW RetryableWorkerException.
  → Guard catch precedence:
       CancellationException (rethrow) → RetryableWorkerException (Retry)
       → classifyTransient(message/IOException) (Retry) → Failed (PERMANENT).
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
  → GroupLifecycleEvent (group lifecycle)
  → InvestmentEvent (investment lifecycle)
  → BackgroundJobRun (worker lifecycle)
  → BankStatementImportRun (bank statement lifecycle)

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
  → GroupLifecycleCoordinator.createGroup(name, members, defaultCurrency)
  → GroupTransactionCoordinator.createGroupWithMembersAtomic()
  → Atomic inserts: ExpenseGroupDao.insert + GroupMemberDao.insertAll
  → GroupLifecycleEvent (eventType="GROUP_CREATED")

ADD member:
  → GroupLifecycleCoordinator.addMember(groupId, name)
  → GroupTransactionCoordinator.addMemberToGroup()
  → Validates: group exists + active, name not blank, no duplicate name, max members
  → GroupMemberDao.insert within transaction
  → GroupLifecycleEvent (eventType="MEMBER_ADDED")

REMOVE member:
  → GroupLifecycleCoordinator.removeMember(groupId, memberId)
  → Validates: not the only member, not currentUser if others exist
  → GroupMemberDao.delete or leftAt update
  → GroupLifecycleEvent (eventType="MEMBER_REMOVED")

ADD expense to group (standalone — no system link):
  → GroupLifecycleCoordinator.addExpense(groupId, expenseInput)
  → GroupTransactionCoordinator.addExpenseToGroup()
  → Validates: group active, members exist, split valid
  → GroupExpenseDao.insert within transaction
  → GroupLifecycleEvent (eventType="EXPENSE_ADDED")

ADD expense to group (with system expense link):
  → TransactionLifecycleCoordinator.createExpense() → get expenseId
  → GroupTransactionCoordinator.addExpenseWithLink(groupId, expenseId, ...)
  → Ownership update on system expense
  → GroupExpenseDao.insert with expenseId FK

CREATE system expense AND link to group (atomic):
  → GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()
  → database.withTransaction { TransactionLifecycleCoordinator.createExpense(DEFER) + GroupExpenseDao.insert }
  → Throws on failure → rollback both

RECORD settlement:
  → GroupLifecycleCoordinator.recordSettlement(groupId, fromMemberId, toMemberId, amount)
  → GroupSettlementDao.insert
  → GroupLifecycleEvent (eventType="SETTLEMENT_RECORDED")

ARCHIVE / DELETE group:
  → GroupLifecycleCoordinator.archiveGroup() / deleteGroupPermanently()
  → archiveGroup: sets isActive=false (soft delete)
  → deleteGroupPermanently: GroupTransactionCoordinator.permanentlyDeleteGroup()
    → Hard delete with cascade cleanup via ExpenseGroupDao + GroupExpenseDao + GroupMemberDao + GroupSettlementDao
  → GroupLifecycleEvent (eventType="GROUP_ARCHIVED" / "GROUP_PERMANENTLY_DELETED")

CALCULATE balances:
  → GroupBalanceCalculator.calculateMemberBalance(groupId, memberId)
  → Read-only: sums paidTotal, owedShareTotal via SplitCalculator, settlements
  → Returns GroupMemberBalance (isSettled when |netBalance| <= 0.01)

FORBIDDEN:
  ❌ ExpenseGroupDao.insert() outside GroupTransactionCoordinator
  ❌ GroupMemberDao.insert() outside GroupTransactionCoordinator
  ❌ GroupExpenseDao.insert() outside GroupTransactionCoordinator
  ❌ GroupSettlementDao.insert() outside GroupLifecycleCoordinator
  ❌ GroupLifecycleEventDao.insert() directly (must go through coordinator)
  ❌ Any group mutation without GroupLifecycleEvent
  ❌ Hard-deleting a group without checking for linked system expenses
```

---

## Subscription Mutations

```
CREATE / ACCEPT subscription:
  → SubscriptionManagerEngine.validateAndCreate(subscription input / candidate)
  → DatabaseWriteBarrier check
  → Atomic: inserts subscription + price history + candidate resolution + usage baseline
  → Uses RecurringExpenseRepository + SubscriptionPriceHistoryDao + SubscriptionUsageDao
  → Returns Result<Long>

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
  → Returns MoneyAggregate (preserves currency safety across subscriptions)

FORBIDDEN:
  ❌ SubscriptionPriceHistoryDao.insert() outside recordPriceChange
  ❌ SubscriptionCandidateDao.insert/delete outside validateAndCreate/acceptCandidate
  ❌ SubscriptionManagerEngine.getTotalMonthlySubscriptionCost() [Deprecated — raw Double across currencies]
  ❌ SubscriptionManagerEngine.calculatePotentialSavings() using raw Double [uses MoneyAggregate now]
  ❌ Direct DAO mutations bypassing engine validation
```

---

## Categorization / Merchant Learning

```
CATEGORIZE expense (auto):
  → CategorizationEngine.categorize(merchantName, amount, categoryContext, existingCategory)
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

ASSIGN split items to participants:
  → EnhancedSplitManager.assignItemsToParticipants(expenseId, assignments)
  → DatabaseWriteBarrier check
  → Atomic within database.withTransaction:
      SplitItemAssignmentDao.deleteAllForExpense(expenseId)
      SplitItemAssignmentDao.insertAssignments(assignments)

FORBIDDEN:
  ❌ SplitTemplateDao.insertTemplate outside EnhancedSplitManager.createTemplate()
  ❌ SplitItemAssignmentDao.insertAssignments without clearing old assignments for same expense
  ❌ Direct SplitItemAssignmentDao.deleteAllForExpense + insertAssignments without transaction wrapping
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
  → Enqueues NotificationIntakeWorker via WorkManager

CAPTURE for retry:
  → NotificationIntakeCoordinator.captureForRetry(notificationData, source)
  → Same flow with 5-second enqueue delay

FORBIDDEN:
  ❌ NotificationIntakeDao.insert outside coordinator
  ❌ Storing raw notification text when RawStorageMode is DO_NOT_STORE
  ❌ Skipping dedup fingerprint computation
  ❌ Direct WorkManager enqueue outside capture flow
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
  → Returns ImportResult(imported, skipped, errors, total)

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
  → DefaultExpenseCategoryAssignmentService.assignDefaultCategory(expenseId, categoryId)
  → DatabaseWriteBarrier check
  → Guard: skips if expense already has a category set
  → database.withTransaction {
      ExpenseDao.updateCategory(expenseId, categoryId)
      TransactionEventDao.insert with LifecycleEventType.UPDATED + category-change metadata
    }
  → Returns Unit

FORBIDDEN:
  ❌ ExpenseDao.updateCategory() outside DefaultExpenseCategoryAssignmentService
  ❌ Skipping TransactionEvent write during category assignment
  ❌ Assigning category without checking if category already set
```

---

## Budget Forecasting

```
GENERATE spending forecast:
  → BudgetForecastingEngine.generateAndSaveForecast(budgetId, period)
  → Reads historical expense data via ExpenseDao + ExpenseRepository
  → Normalizes via AnalyticsCurrencyNormalizer
  → Computes projected spending using time-series patterns
  → BudgetForecastDao.saveBudgetForecast() persists result

FORBIDDEN:
  ❌ BudgetForecastDao.saveBudgetForecast() outside BudgetForecastingEngine
  ❌ Forecasting without historical expense normalization
  ❌ Persisting forecasts without AnalyticsCurrencyNormalizer normalization
```

---

## Shared Expense Management (Groups — Alternative Facade)

```
CREATE shared group expense:
  → SharedExpenseManager.createGroup(name, members)
  → SharedExpenseDataPort.createGroup() → delegates to multi-table write

ADD shared expense:
  → SharedExpenseManager.addExpense(groupId, input)
  → SplitCalculator computes member shares
  → SharedExpenseDataPort.createExpense() → multi-table atomic write

REMOVE shared expense member:
  → SharedExpenseManager.removeMember(groupId, memberId)
  → SharedExpenseDataPort.removeMember()

FORBIDDEN:
  ❌ SharedExpenseDataPort.createExpense() outside SharedExpenseManager
  ❌ SharedExpenseManager CRUD outside GroupLifecycleCoordinator (preferred path)
```

---

## Recurring Plan Projection

```
PROJECT future occurrences:
  → RecurringPlanProjectionService.projectOccurrences(ruleId, windowStart, windowEnd)
  → Reads recurring rule + existing occurrences
  → Computes projected dates using RecurringLifecycleCoordinator
  → PlannedExpenseDao.insert() for each projected occurrence
  → Used by UI to show upcoming planned expenses before materialization

FORBIDDEN:
  ❌ PlannedExpenseDao.insert() outside RecurringPlanProjectionService
  ❌ Projecting occurrences without validating rule is active
  ❌ Duplicate projection without clearing stale planned rows first
```

---

## Spending Challenges

```
DEACTIVATE expired challenges:
  → SpendingChallengeManager.refreshChallenges()
  → Queries ExpenseDao for per-challenge spending aggregates
  → SpendingChallengeRepository.deactivateChallenges() for expired challenges
  → Returns updated challenge progress list

CALCULATE challenge progress:
  → SpendingChallengeManager.calculateProgress(challenge)
  → Read-only: aggregates expense amounts matching challenge criteria
  → Returns progress percentage against challenge target

FORBIDDEN:
  ❌ SpendingChallengeRepository.deactivateChallenges() directly without expense check
  ❌ Challenge progress calculation without considering isNotMine/isReimbursable flags
```
