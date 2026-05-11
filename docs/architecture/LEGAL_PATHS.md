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

---

## Recurring Rule Mutations

```
CREATE/UPDATE rule:
  → ManualRecurringExpenseRepository (with DatabaseWriteBarrier + timestamps + event)

DEACTIVATE rule:
  → RecurringRuleLifecycleCoordinator.deactivateRule()
  → Atomic: isActive=false + cancel PLANNED occurrences + suppress reminders + cancel planned

DELETE rule:
  → RecurringRuleLifecycleCoordinator.deleteRule()
  → Atomic: delete reminders + planned + occurrences + rule + event

GENERATE occurrences:
  → RecurringLifecycleCoordinator.generateOccurrences()
  → Rejects inactive rules
  → Materializer respects terminal status guards

LINK expense to occurrence:
  → RecurringLifecycleCoordinator.linkExpenseToOccurrence()
  → Atomic conditional claim (WHERE status=PLANNED AND linkedExpenseId IS NULL)
  → Fulfills planned + suppresses reminders

FORBIDDEN:
  ❌ ManualRecurringExpenseDao.insert/update/delete outside repository/coordinator
  ❌ RecurringOccurrenceDao.update() outside materializer/coordinator
  ❌ BillReminderManager.markBillPaid() [DeprecationLevel.ERROR]
```

---

## Privacy / Cloud AI

```
CLOUD AI call:
  → Check EffectiveCloudAiPolicy via CloudAiPrivacyGate
  → If redactBeforeCloud: apply CloudPayloadRedactor.redactText(text, purpose)
  → Send only redacted/prepared payload
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
  → On rollback failure: stay in RESTART_REQUIRED (fail-closed)
  → Forced restart after success

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
  → WorkerExecutionGuard.runGuarded() [checks barrier FIRST, then logs run]
  → WorkerRunLogger records RUNNING → SUCCESS/FAILED/SKIPPED
  → Checkpoint before long loops (ensureActive/writeBarrier)

SCHEDULING:
  → WorkerRegistry.scheduleAll() for startup
  → WorkerSpecScheduler.scheduleFromSpec() for periodic
  → WorkerSpecScheduler.scheduleAtMidnight() for one-shot (uses actual worker class)

FORBIDDEN:
  ❌ WorkManager.enqueue() outside WorkerRegistry/WorkerSpecScheduler
  ❌ runBlocking inside suspend worker code
  ❌ Writing BackgroundJobRun before checking write barrier
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
