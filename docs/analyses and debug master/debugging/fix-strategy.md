# How to work on fixes without creating mixed old/new behavior

## 1. Do not organize only by pipeline

Pipeline-by-pipeline is good for debugging, but not always for fixing.

If you fix `notification → expense` first and invent a new transaction behavior there, but receipts/bank/email still use the old behavior, you create two apps inside one codebase.

So use this rule:

```text
If the bug is caused by a shared contract, fix the contract globally.
If the bug is isolated to one flow, fix that pipeline.
If the bug is a leaf typo/no-op/wrong formula, fix standalone.
```

---

# 2. Issue categories

## A. Universal architectural fixes

These must be applied everywhere, not per pipeline.

Examples:

### Transaction lifecycle

Problem:

```text
outer transaction → coordinator.createExpense() → side effects before final commit
```

Fix globally:

```text
TransactionLifecycleCoordinator supports deferred post-commit side effects.
```

Then migrate:

```text
notification
manual
receipt
email
bank
group
import
```

Do not make only one pipeline “new style.”

---

### Money/currency

Problem:

```text
raw Double totals
current-rate historical analytics
missing-rate warnings dropped
```

Fix globally:

```text
MoneyAggregate / normalized input / convertAsOf()
```

Then apply to:

```text
dashboard
analytics
budget
forecast
cashflow
groups
investment
tax
map
exports
```

---

### Restore/backup/write barrier

Problem:

```text
workers/services/repositories can write during backup/restore
```

Fix globally:

```text
RestoreMaintenanceMode + WorkerExecutionGuard + write guard
```

Then all writers obey it.

---

### Privacy/AI gates

Problem:

```text
PrivacySettings and AiSettings can disagree
```

Fix globally:

```text
CloudAiGuard / PrivacyGate is mandatory for all cloud providers.
```

Then all AI paths use it.

---

### Worker execution

Problem:

```text
some workers guarded, some not
BackgroundJobRun unused
WorkerSpec.version unused
```

Fix globally:

```text
WorkerScheduler + WorkerExecutionGuard + WorkerRunLogger
```

Then every worker uses the same wrapper.

---

### DAO/Room integrity

Problem:

```text
IGNORE insert result ignored
missing FK
stale schema assumptions
```

Fix as DB contracts, not feature-by-feature.

---

## B. Cross-cutting subsystem fixes

These affect several pipelines but not the whole app.

Examples:

### Receipt link correctness

Fix once in:

```text
ReceiptLinkService
ReceiptExpenseLinkDao
ScannedReceipt.matchStatus handling
```

Then it improves:

```text
receipt scan
email receipt
receipt matching worker
warranty
price protection
analytics no-double-count
```

---

### Recurring occurrence generation

Fix once:

```text
previewOccurrences() must not schedule reminders
materializeOccurrences() explicit reminder scheduling
```

Then it fixes:

```text
forecast
cashflow
financial weather
bill reminders
recurring screen
```

---

### Category / merchant normalization

Fix once:

```text
alias conflict handling
cache invalidation
case-insensitive category lookup
learning policy
```

Then it improves:

```text
notifications
receipts
bank import
email
analytics
budgets
map
```

---

## C. Pipeline fixes

Use pipeline-based work when the issue is truly flow-specific.

Examples:

```text
Notification extraction reads too few extras
Bank API stub imports fake data
Email parser reparses provider-parsed body
CSV importer cannot import app CSV
```

These can be fixed pipeline-by-pipeline.

---

## D. Standalone fixes

These are safe small fixes.

Examples:

```text
BillReminderWorker uses occurrenceId as amount
merchant extraction regex runs after lowercasing
subscription usage divides by zero
SubscriptionPriceHistory.recordedAt = 0
ReturnWindow.markAsReturned() misses refundCurrency
debug package ID typo
ViewModel button is no-op
hardcoded euro in report string
```

These can be quick PRs, but still add tests.

---

# 3. Recommended work order

## Phase 0 — Freeze

No features.

Allowed:

```text
bug fixes
tests
diagnostics
contract refactors
feature flags
deleting dead/demo behavior
```

---

## Phase 1 — Safety and observability

Fix first:

```text
1. Room/schema/DAO integrity report
2. debug diagnostics/drop reasons
3. BackgroundJobRun logging
4. restore critical recovery/write guards
5. feature flag demo/stub systems
```

Why: you need to see what is happening before deeper refactors.

---

## Phase 2 — Core contracts

Fix these before polishing pipelines:

```text
1. TransactionLifecycleCoordinator deferred side effects
2. Money/time primitives
3. Currency/MoneyAggregate propagation
4. RestoreMaintenanceMode write barrier
5. Privacy/CloudAiGuard
6. WorkerExecutionGuard
7. ReceiptLinkService correctness
8. Recurring preview vs materialize split
9. Category/merchant normalization contract
```

These are “architectural contracts.”

---

## Phase 3 — Golden pipelines

After contracts, stabilize pipelines in this order:

```text
1. notification → expense → dashboard
2. manual/review transaction lifecycle
3. receipt → link/create expense → analytics
4. recurring → actual payment → no double count
5. backup → restore → same totals
6. privacy setting → runtime gate → audit
7. export/import roundtrip
8. workers idempotency
```

---

## Phase 4 — Secondary features

Then:

```text
bank integration
email receipts
groups
investment
tax
warranty/subscription/negotiation
location/map
natural language/voice
```

---

# 4. How to avoid mixed behavior

Use this pattern.

## Step 1 — Define the contract

Example:

```text
All expense creation must go through TransactionLifecycleCoordinator.
Side effects must run only after final DB commit.
```

Write this in:

```text
docs/architecture/CONTRACTS.md
```

---

## Step 2 — Add guard tests

Before migration, add tests that fail if old behavior returns.

Examples:

```text
direct ExpenseDao insert guard
raw money sum guard
direct System.currentTimeMillis guard
worker without RestoreMaintenanceMode guard
cloud provider without PrivacyGate guard
DAO IGNORE result ignored guard
```

---

## Step 3 — Add adapter layer

Do not rewrite all callers immediately.

Example:

```kotlin
createExpenseImmediate(...)
createExpenseDeferred(...)
```

or:

```text
Legacy API delegates to new coordinator internally.
```

This lets you migrate safely.

---

## Step 4 — Migrate callers one by one

For each caller:

```text
old path → new contract
add scenario test
remove direct DAO call
```

---

## Step 5 — Delete old path

Do not leave both forever.

Add rule:

```text
No new usage of deprecated path.
```

---

# 5. What is deep architecture vs standalone?

## Deep/high architecture

Treat these as global projects:

```text
Transaction lifecycle side-effect boundary
Money/currency normalization
Time/period primitives
Room/schema/DAO integrity
Restore/backup/write barrier
Worker scheduling/execution/logging
Privacy/Cloud AI gate
Analytics normalized input
Receipt link/lifecycle ownership
Recurring occurrence/reminder separation
Export/import schema
Category/merchant normalization cache/learning
```

These need:

```text
contract doc
DB-backed tests
migration plan
CI/static guard
```

---

## More standalone

These can be small PRs:

```text
Bill reminder notification body bug
snoozed reminders query bug
merchant extraction regex bug
subscription divide-by-zero
missing recordedAt timestamps
refundCurrency missing update
hardcoded euro labels
debug simulation wrong package name
BankConnectionsViewModel no-op feature flag
PDF combined mixed-currency label
CSV preview header mismatch
```

But if a standalone bug touches money/privacy/DB lifecycle, still add a test.

---

# 6. Practical PR style

Use small PRs with one purpose.

Good PR names:

```text
contracts/transaction-deferred-side-effects
contracts/worker-execution-guard
contracts/money-aggregate-propagation
pipeline/notification-extract-all-extras
pipeline/receipt-link-service-correctness
bugfix/bill-reminder-body-uses-occurrence-amount
bugfix/subscription-price-history-timestamps
```

Bad PR:

```text
fix-all-pipelines
```

---

# 7. My recommended next 10 PRs

1. `Room/DAO integrity scanner + report`
2. `WorkerExecutionGuard + BackgroundJobRun`
3. `TransactionLifecycleCoordinator deferred side effects`
4. `ReceiptLinkService insert-result + matchStatus fix`
5. `RestoreJournal safetyBackupPath preservation`
6. `Notification diagnostics + full extras extraction`
7. `Money/ConvertedMoney/CurrencyCode primitive fixes`
8. `Recurring generateOccurrences side-effect split`
9. `Privacy CloudAiGuard unification`
10. `Analytics NormalizedAnalyticsInput skeleton`

---

# 8. Main rule

Do not ask:

```text
Which file should I fix?
```

Ask:

```text
Which contract is broken?
```

Then fix the contract once and migrate callers.

That is how you avoid having one pipeline with new behavior and another pipeline with old behavior.