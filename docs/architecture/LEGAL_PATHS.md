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
    → TransactionEvent.CREATED
    → Post-commit side effects via TransactionSideEffectDispatcher

FORBIDDEN:
  ❌ ExpenseDao.insert() from any repository directly
  ❌ ExpenseDao.insertAll() outside debug/migration
  ❌ Any expense insert without TransactionEvent
```

```
UPDATE expense:
  → TransactionLifecycleCoordinator.updateCategory/updateMerchant/updateType/etc.
  → TransactionEvent.UPDATED
  → Post-update side effects

FORBIDDEN:
  ❌ ExpenseDao.update() from repositories directly
  ❌ ExpenseDao.updateCategory() outside coordinator
  ❌ Any expense update without TransactionEvent
```

```
DELETE expense:
  → TransactionLifecycleCoordinator.deleteExpense(id)
  → Loads snapshot INSIDE transaction
  → TransactionEvent.DELETED
  → Post-delete side effects (budget, recurring unlink)

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
  → Returns RecurringExpenseReconcileResult (Linked/Unlinked/Relinked/UpdatedLinkedSnapshot/NoMatch/Skipped)
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
  → Check EffectiveCloudAiPolicy via CloudAiPrivacyGate
  → If redactBeforeCloud: apply CloudPayloadPolicy via DefaultCloudPayloadPolicy.prepare(payload, purpose)
  → Send only PreparedCloudPayload (all 7 cloud providers use this contract)
  → Audit via CompositePrivacyGate final decision

RAW DATA storage:
  → Check RawStorageMode / emailReceiptStorageMode
  → Processing uses EPHEMERAL in-memory text
  → DB stores SANITIZED version per mode
  → DO_NOT_STORE = no raw text persisted, processing still works

FORBIDDEN:
  ❌ Cloud HTTP without privacy gate check
  ❌ Using AiSettings.redactBeforeCloud directly (use EffectiveCloudAiPolicy)
  ❌ Storing raw text when mode is DO_NOT_STORE/METADATA_ONLY
  ❌ Parsing from stored (sanitized) text instead of ephemeral
```

---

## Backup / Restore

```
BACKUP:
  → Enter BACKUP_EXPORTING mode (blocks all writes)
  → Checkpoint WAL (TRUNCATE)
  → Delete stale WAL/SHM
  → Copy DB file
  → Exit mode

RESTORE:
  → Enter RESTORE_PREPARING
  → Journal every state transition (atomic write: temp+rename)
  → Maintenance mode persisted with commit() (not apply())
  → Delete WAL/SHM before installing restored DB
  → On rollback failure: enter CRITICAL_RECOVERY_REQUIRED (fail-closed, persists across restarts)
  → On startup crash-recovery failure: enter CRITICAL_RECOVERY_REQUIRED (NOT reset on later restarts)
  → Forced restart after success (RESTORE_COMPLETE_RESTART_REQUIRED; auto-reset to NORMAL on next clean start)

FORBIDDEN:
  ❌ Any DB write during restore (DatabaseWriteBarrier blocks all)
  ❌ Using stale Room instance after DB swap (forced restart)
  ❌ Exiting maintenance to NORMAL after failed rollback
  ❌ Raw .db export in release builds
```

---

## Workers / Background Jobs

```
EVERY worker:
  → WorkerExecutionGuard.runGuarded() / runGuardedWithContext()
       [checks barrier FIRST, then logs run]
  → WorkerRunLogger records RUNNING → SUCCESS/FAILED/SKIPPED/RETRY
  → Checkpoint before long loops (ensureActive/writeBarrier)
  → Guard enforces requiresNotificationPermission via NotificationPermissionChecker
       (durable skip: NOTIFICATION_PERMISSION_DENIED)

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
  → TransactionEvent (expense lifecycle)
  → ReceiptEvent (receipt lifecycle)
  → RecurringLifecycleEvent (recurring lifecycle)
  → BackgroundJobRun (worker lifecycle)

FORBIDDEN:
  ❌ Timber-only logging for pipeline decisions (must also write durable event)
  ❌ Swallowing CancellationException (always rethrow)
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
