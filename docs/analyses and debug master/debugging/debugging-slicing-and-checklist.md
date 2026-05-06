# Debugging strategy for Cost-agregator

Target context: commit `53c915f`, after dependency-map docs were added.

## 1. Best slicing strategy

Use **three layers at the same time**:

## A. Vertical pipeline slices — best for runtime bugs

Use when something “doesn’t work” in the app.

Examples:

```text
notification → parser → review → transaction lifecycle → dashboard
receipt → OCR/parser → receipt lifecycle → matching → analytics
recurring rule → occurrence → reminder → actual expense → forecast/dashboard
backup → restore → schema verification → workers resume
privacy setting → gate → service/provider behavior → audit event
```

This is the most important debugging style for your app.

Reason: most instability is probably at boundaries between subsystems, not inside isolated functions.

---

## B. Horizontal system slices — best for instability

Use when many unrelated things feel flaky.

Examples:

```text
Hilt graph
Room DB / migrations
WorkManager
privacy gates
restore maintenance mode
money/currency normalization
time/period handling
Android permissions/services
```

These cut across many features.

A broken horizontal system can make many features look randomly broken.

---

## C. File-level inspection — only after narrowing

Do not start by reading random files.

Use file-level debugging only after you know:

```text
pipeline = notification capture
stage = filter
suspect = NotificationFilter / extras extraction / privacy gate
```

Then inspect files.

---

# 2. Debugging rule of thumb

For every bug ask:

```text
Input:
  What enters the app?

Gate:
  What can block it?

Transform:
  What changes it?

Storage:
  Where is it persisted?

Output:
  What should user see?

Observability:
  Where can I see success/failure/drop reason?

Test:
  What proves it does not break again?
```

If any answer is unclear, that is the debugging target.

---

# 3. Global debugging checklist

## 0. Reproducibility baseline

Check these before fixing logic:

- [ ] exact app version / commit
- [ ] fresh install vs upgraded install
- [ ] debug vs release build
- [ ] device Android version
- [ ] OEM battery restrictions
- [ ] notification listener permission
- [ ] runtime notification permission
- [ ] database version
- [ ] user settings / privacy settings
- [ ] restore maintenance mode
- [ ] logs available
- [ ] crash logs available
- [ ] feature flags/default settings known

If a bug is inconsistent, first make it reproducible.

---

## 1. Observability / diagnostics

Every important pipeline needs:

- [ ] last input seen timestamp
- [ ] last successful processing timestamp
- [ ] last drop reason
- [ ] last exception
- [ ] source package / source type
- [ ] privacy gate decision
- [ ] restore mode state
- [ ] DB write attempted yes/no
- [ ] DB write succeeded yes/no
- [ ] user-visible result

Add a standard enum:

```text
RECEIVED
DROPPED_FILTER
DROPPED_PRIVACY
DROPPED_RESTORE_MODE
DROPPED_DUPLICATE
PARSER_FAILED
REVIEW_CREATED
EXPENSE_CREATED
DB_ERROR
PIPELINE_ERROR
```

Without drop reasons, debugging becomes guessing.

---

# 4. Core app startup/debug checks

## Startup / app boot

Check:

- [ ] `MainApplication` created
- [ ] Hilt initialized
- [ ] database opens
- [ ] restore journal checked before workers
- [ ] `AppStartupDelegate` runs
- [ ] `AppStartupCoordinator` runs
- [ ] background lifecycle observer registered
- [ ] no startup crash swallowed
- [ ] workers are scheduled only after safe startup
- [ ] restore critical recovery blocks unsafe work

Important because architecture says startup is now:

```text
MainApplication
→ AppStartupDelegate
→ AppStartupCoordinator
→ AppBackgroundLifecycleObserver
→ WorkManager jobs
```

---

## Hilt / dependency injection

Check:

- [ ] app graph resolves
- [ ] all DAO providers resolve
- [ ] repositories resolve
- [ ] ViewModels resolve
- [ ] workers resolve
- [ ] privacy gates resolve
- [ ] AI providers resolve
- [ ] backup/restore services resolve
- [ ] no duplicate/ambiguous bindings
- [ ] no missing `@Qualifier`
- [ ] test graph has correct fakes

Add one Android smoke test:

```text
hilt_graph_all_modules_smoke
```

---

## Room database / schema

Check:

- [ ] `AppDatabase` version is `113`
- [ ] Gradle schema verifier is not stuck at `92`
- [ ] latest schema snapshot exists
- [ ] supported migration-start snapshots exist
- [ ] fresh install creates all required tables
- [ ] migrated DB equals fresh DB schema
- [ ] foreign keys enabled
- [ ] indexes exist
- [ ] unique constraints exist
- [ ] destructive migration is not accidentally used
- [ ] v104→v113 migrations tested
- [ ] backup/restore DB opens after restore

Critical DAO groups:

- [ ] `ExpenseDao`
- [ ] `TransactionEventDao`
- [ ] `RawNotificationDao`
- [ ] `PendingReviewDao`
- [ ] `ScannedReceiptDao`
- [ ] `ReceiptEventDao`
- [ ] `ReceiptExpenseLinkDao`
- [ ] `RecurringOccurrenceDao`
- [ ] `RecurringReminderDeliveryDao`
- [ ] `RecurringLifecycleEventDao`
- [ ] `PrivacyAuditDao`
- [ ] `BackgroundJobRunDao`
- [ ] `ExchangeRateDao`
- [ ] `BudgetDao`
- [ ] `CategoryDao`

---

# 5. Pipeline debugging checklist

## Pipeline 1 — Notification capture → expense → dashboard

Check in this exact order:

### Android listener layer

- [ ] notification listener permission enabled
- [ ] `onListenerConnected()` called
- [ ] `onListenerDisconnected()` logged
- [ ] `requestRebind()` called on disconnect
- [ ] `onNotificationPosted()` called
- [ ] app not force-stopped
- [ ] battery not restricted
- [ ] service not crashing during Hilt injection
- [ ] foreground-service restart strategy not masking listener state

### Notification extraction

- [ ] package name captured
- [ ] title captured
- [ ] text captured
- [ ] bigText captured
- [ ] subText captured
- [ ] infoText captured
- [ ] summaryText captured
- [ ] textLines captured
- [ ] messages captured if relevant
- [ ] extras keys logged in debug mode

### Gates

- [ ] blocked package check
- [ ] privacy notification gate
- [ ] restore maintenance mode
- [ ] duplicate/fingerprint check
- [ ] notification filter
- [ ] amount threshold / suspicious amount rules
- [ ] low-confidence route

### Parser/review

- [ ] parser registry chooses correct parser
- [ ] Greek bank parser works
- [ ] Revolut parser works
- [ ] generic fallback works
- [ ] amount/currency/date/merchant extracted
- [ ] confidence router result correct
- [ ] review item created when needed
- [ ] auto-accept only when safe

### Lifecycle/output

- [ ] expense created through `TransactionLifecycleCoordinator`
- [ ] `TransactionEvent.CREATED` inserted
- [ ] duplicate notification creates duplicate/skipped event
- [ ] budget recalculated
- [ ] dashboard total updated
- [ ] analytics category total updated
- [ ] user can see captured result

---

## Pipeline 2 — Transaction lifecycle

Check:

- [ ] all expense creation paths use `TransactionLifecycleCoordinator`
- [ ] manual expense create works
- [ ] notification expense create works
- [ ] receipt-created expense works
- [ ] bank sync expense works
- [ ] group expense works
- [ ] duplicate detection works
- [ ] update amount/category/merchant works
- [ ] delete/soft-delete works
- [ ] event log inserted for create/update/delete/duplicate
- [ ] side effects called once
- [ ] budget monitor triggered
- [ ] anomaly detection triggered
- [ ] merchant learning triggered
- [ ] recurring match attempted
- [ ] dashboard sees updated DB state
- [ ] analytics sees updated DB state

---

## Pipeline 3 — Receipt capture / OCR / email receipt

Check:

- [ ] camera/gallery/file/email sources work
- [ ] URI permission valid
- [ ] file size/MIME validation works
- [ ] receipt asset saved
- [ ] SHA/hash generated
- [ ] OCR result captured
- [ ] receipt parser extracts merchant/amount/date/items
- [ ] duplicate detector checks hash/text/semantic duplicate
- [ ] `ScannedReceipt` inserted
- [ ] `ReceiptEvent` inserted
- [ ] receipt linked to existing expense when matched
- [ ] no duplicate expense created
- [ ] receipt item categorization saved
- [ ] warranty side effect only when eligible
- [ ] price protection side effect only when eligible
- [ ] analytics counts expense once

---

## Pipeline 4 — Recurring expenses / bill reminders

Check:

- [ ] recurring rule saved
- [ ] occurrence expansion correct
- [ ] planned expense materialized
- [ ] reminder delivery created once
- [ ] worker rerun is idempotent
- [ ] paid actual expense links to occurrence
- [ ] paid occurrence suppresses duplicate reminders
- [ ] dashboard does not double-count planned + actual
- [ ] forecast includes future planned costs
- [ ] recurring lifecycle event inserted

---

## Pipeline 5 — Currency / dashboard / analytics

Check:

- [ ] home currency setting loaded
- [ ] original currency preserved
- [ ] exchange rate lookup works
- [ ] historical rates used correctly
- [ ] stale rate detected
- [ ] missing rate detected
- [ ] source buckets preserved
- [ ] no raw cross-currency sum
- [ ] partial aggregate flag shown
- [ ] dashboard warning shown
- [ ] analytics warning shown
- [ ] budget uses normalized values safely
- [ ] export includes original + converted money fields
- [ ] forecast confidence reduced if data partial

---

## Pipeline 6 — Budget / forecasting / cash flow

Check:

- [ ] budget CRUD works
- [ ] category budget and overall budget distinct
- [ ] category delete restricted when active budget exists
- [ ] budget rollover works
- [ ] budget alert threshold correct
- [ ] budget monitor called after expense changes
- [ ] forecast uses expenses + budgets + recurring
- [ ] Monte Carlo handles sparse data
- [ ] deterministic forecast stable with fixed clock
- [ ] cash-flow calendar does not double-count planned and actual

---

## Pipeline 7 — Backup / restore

Check:

- [ ] backup bundle created
- [ ] manifest included
- [ ] DB included
- [ ] receipt files included
- [ ] checksums valid
- [ ] encryption/decryption works
- [ ] wrong password fails safely
- [ ] tampered bundle rejected
- [ ] restore journal starts
- [ ] workers paused during restore
- [ ] notification capture paused during restore
- [ ] DB writes blocked during unsafe restore mode
- [ ] restore completes
- [ ] workers resume after success
- [ ] failed restore leaves recoverable state
- [ ] restored dashboard equals original dashboard
- [ ] restored analytics equals original analytics
- [ ] receipt links preserved
- [ ] recurring state preserved
- [ ] privacy audit preserved

---

## Pipeline 8 — Privacy gates / AI / redaction

Check:

- [ ] privacy settings load from DataStore
- [ ] settings persist across app restart
- [ ] cloud AI disabled blocks cloud provider
- [ ] notification capture disabled blocks capture
- [ ] geocoding disabled blocks external location calls
- [ ] backup/export disabled blocks raw export
- [ ] audit event written for allow/deny
- [ ] redaction sanitizer runs before cloud calls
- [ ] raw notification/OCR text not stored when policy forbids
- [ ] AI provider fallback deterministic
- [ ] denied state visible to user

---

## Pipeline 9 — Workers / background jobs

Check each worker:

- [ ] `DailyBriefingWorker`
- [ ] `LocationBackfillWorker`
- [ ] `MerchantKeyBackfillWorker`
- [ ] `WarrantyExpirationWorker`
- [ ] `BillReminderWorker`
- [ ] `ReceiptMatchingWorker`
- [ ] `DataRetentionWorker`

For each:

- [ ] Hilt construction works
- [ ] scheduled at correct interval
- [ ] idempotent
- [ ] logs `BackgroundJobRun`
- [ ] retry reason saved
- [ ] stale running jobs recovered
- [ ] respects restore maintenance mode
- [ ] respects privacy gates
- [ ] does not duplicate notifications/events
- [ ] failure is visible

---

## Pipeline 10 — Bank integration / imports

Check:

- [ ] bank connection created
- [ ] auth/token failure surfaced
- [ ] expired token does not corrupt sync
- [ ] partial sync safe
- [ ] duplicate bank transaction skipped
- [ ] low-confidence transaction goes to review
- [ ] approved transaction uses lifecycle coordinator
- [ ] source/origin preserved
- [ ] dashboard only includes approved non-duplicates

---

## Pipeline 11 — Email receipt ingestion

Check:

- [ ] provider parser works for Amazon/Apple/Uber/etc.
- [ ] email source stored
- [ ] receipt lifecycle used
- [ ] matching to expense works
- [ ] warranty/price/subscription effects gated
- [ ] duplicate email skipped
- [ ] no duplicate expense
- [ ] analytics counts once

---

## Pipeline 12 — Import/export/accounting

Check:

- [ ] CSV escaping safe
- [ ] special characters roundtrip
- [ ] multi-currency fields exported
- [ ] tax/business fields exported
- [ ] receipt links represented
- [ ] private raw text redacted
- [ ] import into fresh DB works
- [ ] totals match after roundtrip
- [ ] unsupported fields reported, not silently lost

---

# 6. Feature/engine-specific debugging checklist

## Analytics engines

Check:

- [ ] monthly total
- [ ] daily average
- [ ] category totals
- [ ] merchant totals
- [ ] trends
- [ ] anomalies
- [ ] spending pace
- [ ] month comparison
- [ ] day-of-week analysis
- [ ] insights engine
- [ ] empty data
- [ ] date boundaries
- [ ] multi-currency partial data
- [ ] refunds/transfers if supported
- [ ] shared expenses/reimbursements

---

## Categorization / merchant normalization

Check:

- [ ] exact merchant rules
- [ ] aliases
- [ ] Greeklish normalization
- [ ] canonicalization
- [ ] semantic keywords
- [ ] contextual inference by amount/time
- [ ] learned corrections
- [ ] cache invalidation
- [ ] unknown merchant fallback
- [ ] category deletion/rename behavior

---

## Money/time primitives

Check:

- [ ] cross-currency addition forbidden
- [ ] rounding rules
- [ ] zero/negative amounts
- [ ] partial conversion state
- [ ] stale/missing rate failure reasons
- [ ] day/week/month period ranges
- [ ] DST
- [ ] leap day
- [ ] half-open interval boundaries
- [ ] fixed clock in tests

---

## Groups / shared expenses

Check:

- [ ] group CRUD
- [ ] member CRUD
- [ ] payer split
- [ ] custom split
- [ ] reimbursements
- [ ] settlement suggestions
- [ ] budget offset
- [ ] dashboard gross/net contract
- [ ] analytics not corrupted
- [ ] deleting member/group restrictions

---

## Investment tracking

Check:

- [ ] holding create/update/delete
- [ ] price history
- [ ] multi-currency values
- [ ] stale/missing price
- [ ] gain/loss calculation
- [ ] dashboard card state
- [ ] empty/error state

---

## Tax

Check:

- [ ] tax category assignment
- [ ] business/personal split
- [ ] export fields
- [ ] country config
- [ ] missing config fallback
- [ ] report totals

---

## Warranty / subscription / bill negotiation

Check:

- [ ] warranty creation from eligible receipt
- [ ] return window creation
- [ ] subscription candidate detection
- [ ] price history
- [ ] usage tracking
- [ ] bill increase detection
- [ ] negotiation recommendation
- [ ] no-offer state
- [ ] privacy/AI denied state

---

## Location / map

Check:

- [ ] merchant location lookup
- [ ] geocoder fallback
- [ ] API failure
- [ ] privacy gate
- [ ] cache behavior
- [ ] correction persistence
- [ ] map aggregation
- [ ] export redaction

---

## Natural language / voice

Check:

- [ ] speech gateway emits text
- [ ] query interpreter works
- [ ] denied cloud AI fallback
- [ ] route resolver output
- [ ] sensitive query redaction
- [ ] no raw query stored when forbidden

---

# 7. UI/navigation debugging checklist

Check:

- [ ] every `NavigationDestination` route roundtrips
- [ ] parameterized routes work
- [ ] deep links work
- [ ] every route maps to screen
- [ ] every routed ViewModel resolves
- [ ] loading state
- [ ] empty state
- [ ] success state
- [ ] error state
- [ ] partial/warning state
- [ ] permission denied state
- [ ] privacy denied state
- [ ] user action updates DB/state
- [ ] process recreation does not lose critical state

Screens to check from route map:

- [ ] Home
- [ ] Transactions
- [ ] Review
- [ ] Budget
- [ ] Analytics
- [ ] Spending Map
- [ ] Add Expense
- [ ] Scan Receipt
- [ ] Recurring Expenses
- [ ] Manual Recurring Expense
- [ ] Savings Goals
- [ ] Carbon Footprint
- [ ] Warranty Tracker
- [ ] Price Protection
- [ ] Bill Negotiation
- [ ] Smart Search
- [ ] Receipt Matching
- [ ] Investment Portfolio
- [ ] Bank Connections
- [ ] Bill Reminders
- [ ] Spending Challenges
- [ ] Advanced Analytics
- [ ] Cash Flow Calendar
- [ ] Lifestyle Inflation
- [ ] Split Templates
- [ ] Visual Split Editor
- [ ] Currency Management
- [ ] Subscription Management
- [ ] Tax Configuration
- [ ] Export Options
- [ ] Backup/Restore
- [ ] Shared Expense Groups
- [ ] Budget Forecasting
- [ ] Category Management
- [ ] AI Settings
- [ ] Privacy Settings, even if no route currently maps to it

---

# 8. Data integrity debugging checklist

Add seeded diagnostics for:

- [ ] orphan receipt links
- [ ] orphan group expense links
- [ ] orphan recurring occurrences
- [ ] duplicate active budgets
- [ ] duplicate category names case-insensitive
- [ ] duplicate notification fingerprints
- [ ] invalid currency codes
- [ ] stale running background jobs
- [ ] missing transaction lifecycle events
- [ ] missing receipt lifecycle events
- [ ] foreign key violations
- [ ] broken indexes
- [ ] planned/actual double-count risk

---

# 9. Test/CI debugging checklist

Check:

- [ ] real `.github/workflows/ci.yml`
- [ ] fast JVM test task
- [ ] integration test task
- [ ] instrumented test task
- [ ] nightly/stress task
- [ ] schema verifier wired into CI
- [ ] ignored-test-count guard
- [ ] DAO coverage guard
- [ ] lifecycle bypass guard
- [ ] direct Room builder guard
- [ ] docs drift guard
- [ ] secret scan
- [ ] test inventory generated from `src/test/java`, `src/test/kotlin`, and `src/androidTest`
- [ ] coverage matrix generated
- [ ] slowest tests reported
- [ ] flaky tests tracked

---

# 10. Recommended debugging order

If you want the app stable fastest, debug in this order:

1. Repo/CI safety
2. App startup
3. Hilt graph
4. Room open + schema/migrations
5. Restore maintenance mode
6. Notification listener health
7. Notification → raw notification
8. Raw notification → parser/review
9. Review/auto-accept → transaction lifecycle
10. Transaction lifecycle → dashboard/budget/analytics
11. Receipt lifecycle
12. Recurring lifecycle
13. Currency partial-rate behavior
14. Backup/restore roundtrip
15. Privacy/AI gates
16. Workers
17. UI route/ViewModel matrix
18. Secondary features: investment, tax, negotiation, challenges, carbon, savings

---

# 11. Debug ticket template

Use this for every bug:

```text
Bug:
Expected:
Actual:
Commit/build:
Device/Android version:

Pipeline:
Entry point:
Last successful stage:
First failing/drop stage:

Input sample:
DB seed/state:
Privacy settings:
Restore mode:
Battery/background state:

Logs:
Drop reason:
Exception:

Files touched:
Tests added:
Regression scenario:
```

---

# 12. Final answer

You should debug:

- **by pipeline** for real app behavior,
- **by horizontal subsystem** for instability,
- **by feature/engine** for pure logic,
- **by file** only after the pipeline trace identifies the suspect.

For your app, the most important golden debug paths are:

1. notification → expense → dashboard
2. receipt → link/match → analytics
3. recurring → actual payment → no double count
4. multi-currency → dashboard/analytics partial warning
5. privacy setting → runtime gate → audit event
6. backup → restore → same totals
7. worker run → idempotent DB effect
8. route → ViewModel → screen state

Sources:
- Commit `53c915f`: https://github.com/panospao7/Cost-agregator/commit/53c915f09cbc92137b5b84d5839bdbf1cd321c16
- Dependency map: https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/DEPENDENCY_MAP.md
- Segment map: https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/CODEBASE_SEGMENTS.md
- Architecture guide: https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/docs/architecture/ARCHITECTURE.md
- DAO map: https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/build/reports/architecture/dao-map.md
- Hilt bindings map: https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/build/reports/architecture/hilt-bindings-map.md
- Route/ViewModel map: https://raw.githubusercontent.com/panospao7/Cost-agregator/53c915f09cbc92137b5b84d5839bdbf1cd321c16/build/reports/architecture/route-viewmodel-map.md