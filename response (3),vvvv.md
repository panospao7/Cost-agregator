Yes. At this size, you need **complexity-control infrastructure**, not only bug fixes.

I’d add these things:

## 1. A “legal paths” document

For every major operation, define the only allowed path.

Example:

```text
Create expense:
UI/worker/parser
 -> TransactionLifecycleCoordinator
 -> ExpenseDao
 -> TransactionEvent
 -> post-commit side effects
```

Then mark everything else illegal:

```text
ExpenseDao.insert() from random repository = forbidden
ReceiptRepository.linkReceiptToExpense() direct legacy path = forbidden
Raw Double totals in dashboard = forbidden
```

This becomes your architecture law.

## 2. Static architecture guards

Add tests/scripts that fail CI for bad patterns:

```text
No ExpenseDao mutators outside TransactionLifecycleCoordinator
No ScannedReceiptDao mutators outside ReceiptLifecycleCoordinator
No cloud HTTP without PreparedCloudPayload
No System.currentTimeMillis outside TimeProvider
No raw sumOf { effectiveAmount } outside normalized context
No direct worker enqueue outside WorkerRegistry
No allow-all PrivacyGate in main source
```

This will stop old mistakes returning.

## 3. State machines for complex domains

For things like receipt, recurring, backup, worker run, bank sync, use explicit states.

Example:

```text
Receipt:
RECEIVED -> OCR_DONE -> PARSED -> DUPLICATE | REVIEW | EXPENSE_CREATED | FAILED
```

Then reject illegal transitions. This prevents “half-success” bugs.

## 4. Contract tests per subsystem

Do not only test classes. Test contracts:

```text
Privacy contract:
DO_NOT_STORE stores no raw text but parsing still works.

Restore contract:
During restore, no DB write can happen.

Money contract:
Historical report uses transaction-date rate.

Side-effect contract:
No side effect fires before outer transaction commit.
```

These are more valuable than normal unit tests.

## 5. One diagnostics/event model

Every important pipeline should answer:

```text
Did input arrive?
Was it blocked?
Was it parsed?
Was it deduped?
Was it written?
Were side effects run?
Why did it fail?
```

Use durable events, not Timber only.

## 6. Make old APIs impossible to use

Do not leave old paths as “deprecated warning” forever.

Use:

```kotlin
@Deprecated("Use XCoordinator", level = DeprecationLevel.ERROR)
```

Then delete after migration.

## 7. Build a dependency interaction map

Create a living doc:

```text
Money engine affects: dashboard, budget, tax, export, groups, investment
Privacy affects: notification, receipt, email, bank, AI, backup
Merchant normalization affects: dedupe, categorization, analytics, recurring
```

Before fixing an engine, check all affected pipelines.

## 8. Add golden end-to-end scenarios

Example:

```text
Receipt -> expense -> budget -> dashboard -> export
Notification -> expense -> recurring match -> reminder suppression
Backup -> restore -> dashboard totals unchanged
Email receipt -> duplicate existing expense -> receipt linked once
```

These catch regressions across boundaries.

## Bottom line

Your next phase should be:

```text
less feature work
more boundary enforcement
more contract tests
more typed states/outcomes
more deletion of legacy paths
```

The goal is to reduce “many ways to do the same thing” into **one legal implementation per domain action**.