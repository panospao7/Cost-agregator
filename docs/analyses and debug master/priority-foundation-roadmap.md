# Priority Fix Roadmap for Consolidating Cross-Pipeline Functionality

## Core idea

Your issue count is high because the app has many advanced features, but many of them depend on the same missing foundations.

Do **not** fix every screen independently.

Instead, build shared foundations that become mandatory contracts for every pipeline:

```text
Money
Time periods
Lifecycle state
Privacy gates
Database invariants
Background idempotency
Backup/restore safety
```

Once those are in place, the feature-specific bugs become much easier to fix.

---

# Recommended strategy

## Rule 1 — Foundation first, feature cleanup second

Avoid doing this:

```text
fix budget currency
fix analytics currency
fix forecast currency
fix receipt currency
fix export currency
```

That creates five different solutions.

Do this instead:

```text
create MoneySnapshot foundation
migrate DB
make budget/analytics/forecast/export consume it
```

## Rule 2 — Make invalid states hard to represent

Bad current examples:

```text
amount = 0.01 because parsing failed
currency = EUR because unknown
latitude set but longitude null
confirmed warranty still PENDING_REVIEW
planned expense with no source/status/currency
```

Better model:

```text
amount: MoneyDraft? // nullable until confirmed
currencySource: UNKNOWN / OCR / USER / HOME_DEFAULT
location: GeoPoint?
status: PENDING_REVIEW / ACTIVE / REJECTED
sourceType + sourceId + occurrenceDate
```

## Rule 3 — One coordinator per lifecycle

If many places create or mutate the same kind of data, create a coordinator.

Needed coordinators:

```text
TransactionLifecycleCoordinator
ReceiptLifecycleCoordinator
RecurringOccurrenceCoordinator
LocationUpdateCoordinator
BackupRestoreCoordinator
CategoryCorrectionCoordinator
```

Repositories/DAOs should not independently perform lifecycle transitions.

## Rule 4 — Deprecate dangerous direct DAO methods

Mark dangerous methods internal/private where possible:

```kotlin
@Deprecated("Use TransactionLifecycleCoordinator")
suspend fun insertAtomic(...)
```

Then gradually remove direct callers.

---

# Phase 0 — Stabilization before major refactors

## Goal

Prevent new damage while you refactor.

## Do first

1. Ensure production has no destructive migration:

```kotlin
// Do NOT use in production
fallbackToDestructiveMigration()
```

2. Add debug integrity scanner:

```text
duplicate active budgets
duplicate group expense links
multiple current users per group
planned expense duplicates
raw notification duplicates
expenses with null dedupeKey
partial lat/lon rows
invalid currency values
fake 0.01 EUR rows
```

3. Add a “diagnostics” screen or log export for integrity results.

4. Add migration test fixtures from real old databases if possible.

5. Add a freeze rule:

> No new feature may insert expenses, receipts, planned expenses, warranties, or recurring events without going through a coordinator.

## Deliverable

A safety net, not a full fix.

---

# Phase 1 — Money / Currency foundation

## Why first

This root cause appears everywhere:

- dashboard
- budgets
- analytics
- forecasts
- exports
- receipts
- warranties
- subscriptions
- smart savings
- maps
- search
- AI briefings

## Add core model

```kotlin
@JvmInline
value class CurrencyCode(val value: String)

data class Money(
    val amountMinor: Long,
    val currency: CurrencyCode
)

data class MoneySnapshot(
    val originalAmountMinor: Long,
    val originalCurrency: CurrencyCode,
    val baseAmountMinor: Long?,
    val baseCurrency: CurrencyCode?,
    val exchangeRateUsed: Double?,
    val exchangeRateTimestamp: Long?,
    val conversionStatus: ConversionStatus
)

enum class ConversionStatus {
    NOT_REQUIRED,
    CONVERTED,
    MISSING_RATE,
    UNSUPPORTED_CURRENCY,
    STALE_RATE
}
```

Prefer minor units over `Double` for stored/critical values.

If changing everything to minor units is too large immediately, use a transitional model:

```kotlin
data class MoneyDecimal(
    val amount: BigDecimal,
    val currency: CurrencyCode
)
```

## DB changes

Add to `expenses`:

```text
originalAmount
originalCurrency
baseAmount
baseCurrency
exchangeRateUsed
exchangeRateTimestamp
conversionStatus
```

Add to `budgets`:

```text
currency
baseAmount/baseCurrency if needed
```

Add to `planned_expenses`:

```text
currency
baseAmount
baseCurrency
conversionStatus
```

Add to forecasts/analytics snapshots:

```text
currency
conversionStatus
```

Change `exchange_rates` from latest-only to historical:

```text
fromCurrency
toCurrency
rate
validDate
fetchedAt
source
```

Unique key:

```text
fromCurrency + toCurrency + validDate + source
```

## Service contract

Create:

```kotlin
interface MoneyConverter {
    suspend fun convert(
        money: Money,
        targetCurrency: CurrencyCode,
        atMillis: Long
    ): ConversionResult
}
```

## Rules

1. No financial aggregate may sum raw `Double`.
2. Every total must be:
   - one declared currency, or
   - grouped by currency, or
   - marked incomplete.
3. Cross-currency ranking requires conversion.
4. Conversion failures lower confidence.

## First consumers to migrate

Priority order:

1. dashboard totals
2. budgets
3. analytics
4. forecasting/cashflow
5. exports/search
6. warranties/price protection/maps

## Acceptance tests

```text
€100 + $100 is not shown as 200 unless converted.
Budget in EUR converts USD expenses before comparison.
Largest transaction across currencies uses base amount.
Export declares currency.
Analytics returns currency buckets if conversion unavailable.
```

---

# Phase 2 — Time / Period semantics foundation

## Why

`TimePeriodUtils` helps, but it is not strict enough. The app still mixes:

```text
calendar month
last 30 days
raw millisecond previous period
inclusive end
exclusive end
System.currentTimeMillis()
LocalDate.now()
```

## Add typed period model

```kotlin
data class PeriodRange(
    val kind: PeriodKind,
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long,
    val zoneId: ZoneId,
    val label: String
)

enum class PeriodKind {
    TODAY,
    THIS_WEEK,
    LAST_WEEK,
    LAST_7_DAYS,
    THIS_MONTH,
    LAST_MONTH,
    LAST_30_DAYS,
    THIS_QUARTER,
    LAST_QUARTER,
    THIS_YEAR,
    LAST_YEAR,
    CUSTOM
}
```

## Add provider

```kotlin
interface TimeProvider {
    fun nowMillis(): Long
    fun today(zoneId: ZoneId): LocalDate
}
```

## Rules

1. All ranges are half-open:

```text
[startInclusive, endExclusive)
```

2. UI labels must match semantics:

```text
"This month" != "Last 30 days"
```

3. Previous periods must be calendar-aware when period is calendar-based.

4. No engine should call:

```kotlin
System.currentTimeMillis()
LocalDate.now()
```

directly except `TimeProvider`.

## Migrate consumers

Priority:

1. budgets
2. analytics
3. forecasting
4. reports/exports
5. search/query interpretation
6. reminders

## Acceptance tests

```text
This month on April 26 = April 1 to May 1.
Last 30 days on April 26 = March 27 to April 27.
March previous month = February, not same milliseconds.
End boundary is not double-counted.
```

---

# Phase 3 — Transaction lifecycle foundation

## Why

Expenses are the source of truth. Every feature depends on them.

Current risk:

```text
notification path strong
markAsRelevant weaker
receipt path separate
manual/import/AI paths can bypass validation
```

## Create coordinator

```kotlin
class TransactionLifecycleCoordinator {
    suspend fun createFromNotification(...)
    suspend fun approvePendingReview(...)
    suspend fun createFromReceipt(...)
    suspend fun createManual(...)
    suspend fun createFromImport(...)
    suspend fun markRawNotificationRelevant(...)
    suspend fun editExpense(...)
    suspend fun deleteExpense(...)
}
```

## Responsibilities

```text
validate draft
normalize money
assign currency
generate merchantKey
generate dedupeKey
canonical duplicate check
insert expense
link source
link receipt
update pending review
update source stats/event ledger
emit post-commit events
```

## Add draft model

```kotlin
data class ExpenseDraft(
    val amount: Money?,
    val merchant: String?,
    val dateMillis: Long?,
    val transactionType: TransactionType,
    val source: ExpenseSource,
    val categoryId: Long?,
    val confidence: Float,
    val missingFields: Set<ExpenseField>
)
```

## Important

Stop using fake values:

```text
0.01 EUR
Unknown Product with confidence 1.0
EUR because unknown
```

Use incomplete drafts instead.

## Add validator

```kotlin
ExpenseDraftValidator.validateForApproval(...)
```

Rules:

```text
amount finite and positive
currency valid
merchant non-blank
date plausible
transaction type valid
location pair valid
dedupeKey required
```

## Acceptance tests

```text
double approve creates one expense
parse failure creates incomplete review, not 0.01 EUR
markAsRelevant cannot bypass duplicate policy
receipt-created expense uses same validator
currency can be corrected before approval
```

---

# Phase 4 — Receipt lifecycle foundation

## Why

Receipts feed:

```text
expenses
pending reviews
item categorizations
warranties
return windows
price protection
AI assist
exports/backups
```

Currently these drift.

## Create coordinator

```kotlin
class ReceiptLifecycleCoordinator {
    suspend fun processReceiptImage(...)
    suspend fun processStatementDocument(...)
    suspend fun createExpenseFromReceipt(...)
    suspend fun linkReceiptToExpense(...)
    suspend fun unlinkReceipt(...)
    suspend fun deleteReceipt(...)
    suspend fun saveItemCategorization(...)
}
```

## Add source document concept

Receipts and bank statements are not the same.

```kotlin
SourceDocument(
    id,
    type = RECEIPT_IMAGE / RECEIPT_PDF / BANK_STATEMENT,
    imagePath,
    rawText,
    createdAt
)

SourceExtractedTransaction(
    sourceDocumentId,
    rowIndex,
    pendingReviewId,
    expenseId
)
```

## Add receipt line items

```kotlin
ReceiptLineItem(
    id,
    receiptId,
    lineIndex,
    description,
    quantity,
    unitPrice,
    totalPrice,
    currency,
    fingerprint
)
```

AI item categorization should reference line item IDs.

## Fix linking

When receipt links to expense, coordinator updates:

```text
scanned_receipts.expenseId
receipt_item_categorizations.expenseId
warranties.expenseId
return_windows.expenseId
price protection records
```

## Acceptance tests

```text
receipt match links item rows
receipt match updates warranty/return expenseId
statement with many transactions does not overwrite one receipt expenseId
duplicate receipt image detected
raw OCR can be purged while preserving audit metadata
```

---

# Phase 5 — Recurring / Planned / Reminder lifecycle foundation

## Why

This fixes:

```text
duplicate reminders
forecast double counting
planned vs actual drift
subscription math
cashflow errors
```

## Add occurrence table

```kotlin
RecurringOccurrence(
    id,
    sourceType,
    sourceId,
    dueDate,
    status = PLANNED / PAID / SKIPPED / MISSED / CANCELLED,
    linkedExpenseId,
    expectedAmount,
    expectedCurrency,
    paidAt,
    paidAmount,
    paidCurrency
)
```

Unique:

```text
sourceType + sourceId + dueDate
```

## Add reminder state

```kotlin
ReminderState(
    sourceType,
    sourceId,
    occurrenceDate,
    stage,
    lastSentAt,
    dismissedAt,
    snoozedUntil,
    notificationId
)
```

Unique:

```text
sourceType + sourceId + occurrenceDate + stage
```

## Add occurrence expander

```kotlin
ForecastOccurrenceExpander.expand(
    rule,
    startInclusive,
    endExclusive
)
```

Use it in:

```text
forecasts
cashflow
budgets
reminders
smart savings
financial health
calendar/block party
```

## Acceptance tests

```text
weekly bill appears 4/5 times in month
same overdue bill does not notify repeatedly
actual paid bill suppresses predicted occurrence
planned + recurring + actual not counted three times
annual subscription monthly equivalent is correct
```

---

# Phase 6 — Privacy capability gates

## Why

Privacy-sensitive systems currently depend too much on caller discipline.

Needed gates:

```kotlin
NotificationCaptureGate
CloudAiGate
ExternalLocationGate
BackupExportGate
RawDataRetentionGate
```

## Rules

A provider/service must check its own gate before touching sensitive data.

Do not rely only on UI settings.

## Example

```kotlin
class CloudReceiptAssistService(
    private val cloudAiGate: CloudAiGate
) {
    suspend fun suggest(input: ReceiptAssistInput): Result {
        cloudAiGate.requireAllowed(
            capability = RECEIPT_ASSIST,
            payloadKind = IMAGE_OR_OCR
        )
        ...
    }
}
```

## Settings to centralize

```text
notification capture enabled
cloud AI enabled
redaction before cloud
receipt image cloud enabled
external geocoding enabled
background location backfill enabled
raw OCR retention
raw notification retention
encrypted backup requirement
```

## Acceptance tests

```text
cloud disabled => provider cannot send request
external geocoding disabled => no HTTP call
notification capture disabled => listener ignores posts
raw OCR purge removes OCR but keeps parsed fields
backup export warns/encrypts sensitive data
```

---

# Phase 7 — Database invariants and migration parity

## Why

Repository-only rules are not enough for finance data.

## Add fresh-vs-migrated parity tests

Compare fresh vLatest DB and migrated old DB:

```text
tables
columns
indexes
triggers
FKs
CHECK constraints
critical invalid insert behavior
```

## Restore DB-level protection

Use unique indexes where Room supports them:

```text
group_expenses.expenseId unique
raw_notifications.dedupeFingerprint unique
planned occurrence generatedKey unique
```

Use triggers for partial rules:

```text
one active overall budget
one active category budget
one current user per group
paidById belongs to same group
```

## Add integrity scanner

Runs in debug and optionally on startup after migrations.

It should detect, not silently destroy:

```text
duplicate budgets
duplicate group links
orphaned warranties
invalid currencies
partial locations
duplicate planned items
invalid health/recommendation rows
```

## Acceptance tests

```text
fresh and migrated DB reject same invalid data
old DB migrates without loss
duplicate active budgets rejected/detected
one expense cannot link to two group expenses
planned generated duplicate rejected
```

---

# Phase 8 — Background worker and idempotency foundation

## Why

Workers amplify bugs silently.

## Add worker spec registry

```kotlin
WorkerSpec(
    name,
    version,
    enabled,
    constraints,
    repeatInterval,
    policy
)
```

If spec version changes:

```text
cancel and re-enqueue
```

or use update policy.

## Add background job run table

```kotlin
BackgroundJobRun(
    workerName,
    startedAt,
    finishedAt,
    status,
    rowsScanned,
    rowsUpdated,
    notificationsSent,
    retryReason
)
```

## Rules

1. Workers must be idempotent.
2. Retry must not duplicate notifications/items.
3. Workers must check privacy/settings gates.
4. Workers must not overwrite user-confirmed data.
5. Permanent failures should not retry forever.

## Acceptance tests

```text
worker setting change updates constraints
location worker cannot overwrite manual location
warranty reminder sends once per stage
AI worker missing key does not retry forever
duplicate WorkManager schedules do not appear
```

---

# Phase 9 — Backup / restore foundation

## Why

This protects users while schema evolves.

## Replace raw DB export with encrypted bundle

```text
.costbackup
  manifest.json
  database.sqlite.enc
  files/
  checksums.json
```

Manifest:

```json
{
  "backupFormatVersion": 1,
  "databaseVersion": 92,
  "createdAt": "...",
  "includes": {
    "database": true,
    "receiptImages": true,
    "rawNotifications": false,
    "rawOcr": false
  }
}
```

## Add restore maintenance mode

During restore:

```text
pause workers
stop notification ingestion
block writes
close DB
swap safely
force restart
```

## Add crash-safe restore journal

```text
RESTORE_STARTED
STAGED_READY
SWAPPING
VERIFYING
COMPLETED
ROLLBACK_REQUIRED
```

## Acceptance tests

```text
wrong password does not touch live DB
crash during restore recovers
restore preserves all user-owned tables
backup includes receipt images
plaintext DB export hidden from normal production UI
```

---

# Phase 10 — Analytics / Forecast / AI cleanup on top of foundations

Only after money/time/lifecycle foundations exist, clean up engines.

## Analytics

Use:

```text
MoneySnapshot
PeriodRange
DataQualityReport
```

## Forecasting

Use:

```text
RecurringOccurrence
PlannedExpense.status
Actual matched expenses
Money conversion
```

## AI/ML

Use:

```text
event-derived source stats
normalized merchant keys
valid category taxonomy hash
privacy gates
data-quality warnings
```

## Smart savings / health

Require:

```text
known upcoming bills
real account balance or explicit “estimate only”
currency-safe caps
confidence warnings
```

---

# Efficient PR breakdown

Do not create one giant refactor PR.

Use small, stacked PRs.

## PR 1 — Add core types without changing behavior

```text
CurrencyCode
MoneySnapshot
PeriodRange
TimeProvider
DataQualityWarning
```

No major consumers yet.

## PR 2 — Add validators

```text
ExpenseDraftValidator
BudgetDraftValidator
LocationDraftValidator
ReceiptSuggestionValidator
```

Still minimal behavior change.

## PR 3 — Add DB columns for money/currency

Migrate safely with defaults and conversion status:

```text
UNKNOWN / NOT_CONVERTED
```

Do not attempt perfect conversion immediately.

## PR 4 — Dashboard/budget money migration

Make dashboard and budget totals currency-aware first.

## PR 5 — TransactionLifecycleCoordinator

Move notification approval and pending review approval into it.

## PR 6 — Remove fake money fallbacks

Pending reviews and receipts support incomplete drafts.

## PR 7 — ReceiptLifecycleCoordinator

Centralize receipt link/create/delete.

## PR 8 — RecurringOccurrence + ReminderState

Stop duplicate reminders and create forecast occurrence identity.

## PR 9 — PeriodRange migration

Update analytics/budget/forecast/search/report date handling.

## PR 10 — Privacy gates

Provider-side enforcement for cloud AI, geocoding, notification capture.

## PR 11 — Schema hardening

Triggers, indexes, parity tests, integrity scanner.

## PR 12 — Worker spec versioning

Make background scheduling updateable and settings-aware.

## PR 13 — Encrypted backup bundle

Raw DB export becomes debug/advanced only.

## PR 14+ — Feature cleanups

Now fix individual engines with much smaller blast radius.

---

# How to organize code

Recommended packages:

```text
domain/core/money
domain/core/time
domain/core/privacy
domain/core/validation
domain/lifecycle/transaction
domain/lifecycle/receipt
domain/lifecycle/recurring
domain/lifecycle/location
domain/diagnostics
domain/integrity
```

Avoid putting foundational logic in UI/repository classes.

Repositories should persist/query.

Coordinators should own state transitions.

Engines should calculate only.

---

# Migration strategy for existing users

## Do not force perfect backfill immediately

For old expenses:

```text
originalAmount = amount
originalCurrency = existing currency or home currency if known
baseAmount = null
conversionStatus = MISSING_HISTORICAL_RATE or UNKNOWN
```

Then gradually backfill if rates are available.

## Mark uncertain rows

Avoid pretending old data is perfect.

Use statuses:

```text
currencyStatus = ASSUMED_HOME_CURRENCY
conversionStatus = MISSING_RATE
sourceIntegrity = LEGACY_IMPORTED
```

## Add user-visible diagnostics

Example:

> “Some older transactions have assumed currency. Reports may be incomplete until reviewed.”

This is better than silently producing wrong totals.

---

# How to avoid getting overwhelmed

## Create an issue matrix

Every found issue should be tagged by root foundation:

```text
MONEY
TIME
TRANSACTION_LIFECYCLE
RECEIPT_LIFECYCLE
RECURRING_OCCURRENCE
PRIVACY_GATE
DB_INVARIANT
WORKER_IDEMPOTENCY
BACKUP_RESTORE
AI_DATA_QUALITY
```

Then sort by foundation, not by feature.

## Example

Instead of:

```text
Budget raw-sums currency
Analytics raw-sums currency
Forecast raw-sums currency
Map raw-sums currency
Warranty raw-sums currency
```

Track one epic:

```text
MONEY-001: introduce currency-safe aggregation
```

Then sub-tasks:

```text
MONEY-001A dashboard
MONEY-001B budgets
MONEY-001C analytics
MONEY-001D forecast
```

## Measure progress by deleted duplicate logic

A good refactor should remove local logic.

Examples:

```text
remove local duplicate checks
remove local date period math
remove local currency sums
remove direct insert paths
remove fake fallback values
```

---

# Definition of done for foundations

## Money foundation done when

```text
No financial aggregate raw-sums mixed currencies.
Every total declares currency.
Conversion failure is visible.
Budgets compare in budget currency.
Exports declare currency.
```

## Time foundation done when

```text
All periods are typed.
All ranges are half-open.
No direct System.currentTimeMillis in engines.
Calendar vs rolling labels are explicit.
Previous periods are calendar-aware.
```

## Transaction lifecycle done when

```text
Every expense insert path uses coordinator.
No fake amount/currency placeholders.
Duplicate resolution is recorded.
Approval supports currency correction.
Direct DAO footguns removed.
```

## Receipt lifecycle done when

```text
Receipt linking updates items/warranty/return records.
Statements are not modeled as one-expense receipts.
OCR retention is configurable.
Item categorizations have stable item IDs.
```

## Recurring foundation done when

```text
Recurring occurrences have unique identity.
Reminder stages are persisted.
Planned/actual/recurring cannot double-count.
Occurrence expansion is shared everywhere.
```

## Privacy foundation done when

```text
Cloud AI, geocoding, notification capture, backup, raw retention all have hard gates.
Providers check gates internally.
Settings changes resync workers.
```

---

# Recommended priority order

If you want the most efficient sequence:

```text
1. Money foundation
2. Transaction lifecycle
3. Receipt lifecycle
4. Recurring occurrence/reminder lifecycle
5. Time/Period semantics
6. DB invariants/migration parity
7. Privacy gates
8. Worker idempotency
9. Backup/restore
10. Analytics/forecast/AI cleanup
```

Why this order?

- Money fixes the broadest financial correctness issue.
- Transaction lifecycle protects the source of truth.
- Receipt lifecycle fixes one of the biggest data-ingestion paths.
- Recurring lifecycle prevents double counting and repeated notifications.
- Time semantics then makes analytics/forecasting consistent.
- DB invariants lock the rules down.
- Privacy/worker/backup harden operations around the data.
- Analytics cleanup becomes reliable only after the data foundation is stable.

---

# Practical warning

Do not try to make all old data perfect in one migration.

For financial apps, it is safer to preserve old data with uncertainty flags than to “fix” it aggressively.

Prefer:

```text
legacy row + warning + review option
```

over:

```text
silent conversion / silent merge / silent deletion
```

---

# Final recommendation

Treat the next development cycle as a **foundation consolidation milestone**, not a feature milestone.

The milestone goal should be:

> “All major pipelines use shared Money, PeriodRange, Lifecycle Coordinators, Privacy Gates, and DB integrity checks.”

Once that is done, the app’s advanced features become much easier to trust, test, and evolve.