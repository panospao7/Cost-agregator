# Phase 3 — Transaction Lifecycle Implementation Plan

## 0. Phase 3 Mission

Phase 3 should introduce a single, consistent transaction lifecycle layer so every `Expense` creation, update, deletion, deduplication check, source link, and post-create side effect follows one contract.

Current problem from the audit:

- 8 separate creation paths.
- 10 insert call sites.
- 8 direct DAO insert calls outside `ExpenseRepository`.
- 25+ direct DAO update/delete calls outside `ExpenseRepository`.
- 3 separate dedup strategies.
- Missing dedup in CSV, group/shared, and email receipt paths.
- Inconsistent validation across sources.
- Inconsistent post-creation side effects.
- No explicit `ExpenseSource`.
- No full lifecycle event ledger.
- Fake values such as `0.01`, `"Unknown"`, `"Parsing Failed"`, and hardcoded `"EUR"` leak into real transaction flows.

The target is not just to add another repository. The target is to create a lifecycle boundary that all write paths must use.

---

# 1. Target Architecture

## 1.1 New central owner

Create:

`TransactionLifecycleCoordinator`

Suggested package:

`domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`

This coordinator becomes the only production entry point for:

1. Creating expenses.
2. Updating expense business fields.
3. Deleting expenses.
4. Checking duplicates.
5. Writing lifecycle events.
6. Running standard post-creation side effects.
7. Linking expenses to source systems such as notifications, receipts, groups, email receipts, CSV imports, and future bank API transactions.

The coordinator should own lifecycle behavior, not UI screens, repositories, parser classes, or Android services.

---

## 1.2 Existing classes after Phase 3

### `ExpenseRepository`

After Phase 3, `ExpenseRepository` should primarily be:

- read/query facade
- compatibility wrapper for existing callers
- delegator for expense mutations

It should not remain the place where lifecycle rules are partially implemented.

Recommended long-term shape:

- `ExpenseRepository.observe...`
- `ExpenseRepository.get...`
- `ExpenseRepository.search...`
- `ExpenseRepository.createExpense(...)` delegates to `TransactionLifecycleCoordinator`
- `ExpenseRepository.updateExpense(...)` delegates to `TransactionLifecycleCoordinator`
- `ExpenseRepository.deleteExpense(...)` delegates to `TransactionLifecycleCoordinator`

### Source-specific repositories

These remain useful, but they stop inserting directly into `ExpenseDao`.

Examples:

- `ManualExpenseRepository`
- `ReviewQueueRepository`
- `ReceiptRepository`
- `NotificationProcessingPipeline`
- `GroupTransactionCoordinator`
- `EmailReceiptIngestionService`
- `CsvExpenseImporter`

They should convert source-specific data into a lifecycle request and call the coordinator.

---

# 2. Core Invariants

The coordinator must enforce these invariants for every real `Expense`.

## 2.1 Creation invariants

Every created expense must have:

1. Valid amount.
2. Explicit currency.
3. Valid date.
4. Normalized merchant key.
5. Generated dedupe key.
6. Explicit source.
7. Consistent transaction type.
8. Consistent ownership fields.
9. Consistent transfer fields.
10. Lifecycle event.
11. Deduplication attempt.
12. Atomic insert via `insertAtomic`.

No production path should call bare `expenseDao.insert()` for a real expense.

---

## 2.2 Validation invariants

### Amount

Apply everywhere:

- amount must be finite
- amount must be greater than `0`
- amount must be less than or equal to `1_000_000`
- no fallback amount such as `0.01` may be accepted as a real transaction amount unless the user explicitly entered it

### Merchant

Apply everywhere:

- merchant must be non-blank for real expenses
- `"Unknown"`, `"Unknown Merchant"`, `"Parsing Failed"`, and similar placeholders must not be accepted as normal merchant names for real expenses
- parser failures should create review/error states, not fake expenses

### Currency

Apply everywhere:

- currency must be explicit
- currency must be normalized
- no silent fallback to `"EUR"` inside lifecycle creation
- if source has no currency, caller must resolve it before calling the coordinator
- if currency cannot be resolved, return validation failure or create a pending review requiring user input

### Date

Apply everywhere:

- date must be positive and meaningful
- future actual expenses should be rejected by default
- if a source legitimately allows future-dated entries, it must request an explicit date policy
- no fallback to `timeProvider.now()` unless the source semantics truly mean “received now”

### Transaction type

Apply everywhere:

- `TRANSFER` requires transfer direction and transfer account name
- income/deposit/expense semantics must remain explicit
- dedupe key must include transaction type when known

### Ownership

Apply everywhere:

- cannot be both `isNotMine` and a normal personal expense
- shared expense fields must be internally consistent
- group expenses must store only the user’s share where the existing app model expects that behavior

---

# 3. New Domain Models

## 3.1 `ExpenseSource`

Add explicit source tracking.

Recommended enum values:

- `MANUAL_ENTRY`
- `NOTIFICATION_AUTO_ACCEPT`
- `REVIEW_APPROVAL`
- `RECEIPT_SCAN`
- `RECEIPT_BATCH_REVIEW`
- `BANK_STATEMENT_REVIEW`
- `CSV_IMPORT`
- `EMAIL_RECEIPT`
- `GROUP_EXPENSE`
- `BANK_API_SYNC`
- `RECURRING_GENERATED`
- `DEBUG_TOOL`
- `MIGRATION`
- `UNKNOWN`

Store as `String` in Room, not ordinal.

---

## 3.2 `CreateExpenseRequest`

Create a source-neutral request object.

Fields should include:

### Required fields

- merchant
- amount
- currency
- date
- transaction type
- source

### Optional classification fields

- category ID
- category confidence
- classifier label
- whether category was user selected or auto-classified

### Optional display/details fields

- notes
- description
- payment method

### Transfer fields

- transfer direction
- transfer account name

### Ownership fields

- `isNotMine`
- owner name
- `isSharedExpense`
- shared-with name
- my share percentage
- my share amount

### Location fields

- latitude
- longitude
- location source
- place ID
- address

### Source-link fields

- raw notification ID
- pending review ID
- scanned receipt ID
- email receipt source ID
- group ID
- group expense draft/link info
- bank transaction ID
- CSV import batch ID
- CSV row number
- external/source fingerprint

### Policy fields

- deduplication mode
- date validation policy
- classification policy
- side-effect policy
- idempotency key
- actor/user action label
- reason/debug note

---

## 3.3 `CreateExpenseResult`

Use an explicit result type instead of only returning `Long`.

Recommended result variants:

- Created with expense ID
- Duplicate skipped with duplicate information
- Validation failed with structured errors
- Insert conflict / ignored by dedupe key
- Source link failed
- Unexpected failure

This makes CSV/import flows much cleaner because each row can report why it failed.

---

## 3.4 `ExpenseUpdates`

For update lifecycle, define a patch-style object.

Possible fields:

- category change
- merchant change
- transaction type change
- transfer details
- ownership details
- shared expense details
- location details
- notes/details
- date correction
- amount correction
- currency correction, if supported after currency Phase 1

Each update should include:

- actor/source
- reason
- whether to recompute dedupe key
- whether to trigger learning/side effects

---

## 3.5 `TransactionEvent`

Add a lifecycle event ledger.

Suggested table:

`transaction_events`

Fields:

- ID
- expense ID, nullable for failed attempts
- event type
- source
- actor
- occurred at
- request ID / idempotency key
- dedupe key
- duplicate expense ID, nullable
- before snapshot JSON, nullable
- after snapshot JSON, nullable
- metadata JSON, nullable
- reason, nullable

Event types:

- `CREATE_ATTEMPTED`
- `CREATED`
- `CREATE_VALIDATION_FAILED`
- `CREATE_DUPLICATE_SKIPPED`
- `CREATE_INSERT_CONFLICT`
- `UPDATED`
- `BULK_UPDATED`
- `DELETED`
- `RESTORED_FROM_DEBUG_SNAPSHOT`
- `SOURCE_LINKED`
- `SIDE_EFFECT_FAILED`

Important: do not block normal expense creation if a non-critical post-commit side effect fails. But lifecycle event insertion for the primary create/update/delete should be part of the DB transaction.

---

# 4. Database / Room Changes

## 4.1 Add `source` to `Expense`

Add nullable column first:

- `source: String?`

Backfill existing rows:

- if `isManualEntry = 1`, source = `MANUAL_ENTRY`
- if `rawNotificationId IS NOT NULL`, source = `NOTIFICATION_AUTO_ACCEPT` or `REVIEW_APPROVAL` if distinguishable
- if linked from `scanned_receipts`, source = `RECEIPT_SCAN`
- if linked from group tables, source = `GROUP_EXPENSE`
- else source = `UNKNOWN`

Do not make source non-null until after backfill and app compatibility are stable.

---

## 4.2 Add `transaction_events` table

Indexes:

- `expenseId`
- `source`
- `occurredAt`
- `requestId` or `idempotencyKey`, if used
- `eventType`

This gives you the lifecycle trace the app currently lacks.

---

## 4.3 Optional later: source fingerprint index

For bank/email/import idempotency, consider a source fingerprint table or source fingerprint column.

Do not overbuild this in the first PR unless needed.

---

# 5. Coordinator Internal Flow

The coordinator creation flow should be:

1. Capture `now` once from `TimeProvider`.
2. Normalize/validate request.
3. Normalize currency.
4. Validate amount.
5. Validate merchant.
6. Validate date.
7. Validate transaction type.
8. Validate ownership fields.
9. Normalize merchant through `MerchantNormalizer`.
10. Resolve category:
    - use provided category if present
    - otherwise classify if policy allows
    - otherwise allow uncategorized only if current schema permits it
11. Generate dedupe key using `DuplicateDetectionPolicy.generateDedupeKeyWithType`.
12. Run range-based duplicate check unless explicitly skipped for debug restore.
13. Start DB transaction.
14. Re-run or finalize duplicate-sensitive insert with `insertAtomic`.
15. Insert source links inside the same DB transaction where required.
16. Insert lifecycle event.
17. Commit.
18. Run post-commit side effects.
19. Return structured result.

Important: expensive or non-DB side effects should not happen inside the DB transaction.

---

# 6. Deduplication Design

## 6.1 One dedup policy

`DuplicateDetectionPolicy` should remain the canonical key generator, but the coordinator should become the canonical caller.

Every normal creation path should do both:

1. range-based duplicate check through `isDuplicateCurrencyAware`
2. race-condition guard through `insertAtomic`

The unique `dedupeKey` index is the final guard, not the only guard.

---

## 6.2 Deduplication modes

Recommended modes:

### `STANDARD`

Used by most paths.

- normalize merchant
- generate dedupe key
- range-check duplicate
- insert atomic

### `STRICT_EXTERNAL_ID`

Used later for bank API or email imports if a stable external transaction ID exists.

- first check source external ID/idempotency key
- then run standard dedupe

### `BULK_IMPORT`

Used by CSV and statement imports.

- run standard dedupe
- aggregate duplicate results
- avoid noisy per-row alerts
- return row-level result summary

### `SKIP_FOR_DEBUG_RESTORE`

Only for trusted debug restore/snapshot restore.

Must write lifecycle event saying dedup was intentionally skipped.

---

# 7. Side Effect Policy

## 7.1 Problem

Currently side effects differ by path:

- group expenses get no budget/anomaly checks
- email receipt expenses get no standard side effects
- notification auto-accept gets subscription detection
- receipt gets receipt link and warranty extraction
- manual entry gets recommendations
- source stats only apply to notification/review

This causes inconsistent app behavior.

---

## 7.2 Solution

Create a `TransactionSideEffectDispatcher`.

It receives a committed lifecycle result and runs post-commit effects.

Standard side effects:

- budget monitor
- anomaly alert
- merchant-category learning
- classifier learning where applicable
- recommendation generation where applicable
- subscription detection where applicable
- source stats updates where applicable
- raw notification relevance update where applicable
- scanned receipt link where applicable
- confidence router cache invalidation where applicable

---

## 7.3 Default side-effect matrix

### Manual entry

Enable:

- budget monitor
- anomaly alert
- merchant-category learning
- recommendation generation
- recurring rule creation if requested

Disable:

- source stats
- raw notification relevance

### Notification auto-accept

Enable:

- budget monitor
- anomaly alert
- source stats accepted
- classifier training if currently used
- confidence cache invalidation
- raw notification relevance
- subscription detection
- transfer analytics if currently used

Consider enabling merchant-category learning if category is reliable.

### Review approval

Enable:

- budget monitor
- anomaly alert
- source stats accepted/decrement pending
- raw notification relevance if linked
- scanned receipt link if linked
- user correction event
- merchant-category learning
- classifier retraining
- confidence cache invalidation

### Receipt scan

Enable:

- budget monitor
- anomaly alert
- scanned receipt link
- merchant-category learning
- hybrid classifier learning if user corrected category
- warranty extraction should remain source-specific and should not block expense creation

### CSV import

Enable:

- dedupe
- normalization
- category resolution
- lifecycle events

Use batch/summarized side effects:

- avoid one alert per imported row
- run budget recalculation/refresh after batch
- optionally suppress anomaly notifications during import

### Email receipt

Enable:

- dedupe
- budget monitor
- anomaly alert
- receipt/source link
- merchant-category learning if reliable

### Group expense

Enable:

- dedupe
- budget monitor
- anomaly alert
- group link creation
- ownership normalization

### Debug restore

Disable normal side effects.

Write explicit debug lifecycle events.

---

# 8. Implementation PR Plan

## PR 0 — Baseline and branch protection

### Goal

Prepare the branch before behavior changes.

### Actions

1. Rebase Phase 3 branch after currency Phase 1 and time Phase 2 decisions are stable.
2. Run baseline compile and tests.
3. Record existing failures.
4. Add a temporary audit doc:
   - current direct insert sites
   - current direct update/delete sites
   - current dedup gaps
5. Do not change behavior yet.

### Done when

- Baseline status is documented.
- You know which failures pre-exist Phase 3.

---

## PR 1 — Lifecycle contracts only

### Goal

Add models and interfaces without moving paths yet.

### Add

- `ExpenseSource`
- `LifecycleEventType`
- `CreateExpenseRequest`
- `CreateExpenseResult`
- `ExpenseUpdates`
- `DeduplicationMode`
- `DateValidationPolicy`
- `SideEffectPolicy`
- `LifecycleActor`
- validation error model

### Rules

No call sites moved yet.

### Done when

- Code compiles.
- No behavior change.
- Models are covered by lightweight unit tests where useful.

---

## PR 2 — Database lifecycle foundation

### Goal

Add persistent source/event tracking.

### Changes

1. Add nullable `source` column to `Expense`.
2. Add `transaction_events` table.
3. Add DAO for transaction events.
4. Add Room migration.
5. Add backfill logic for existing expenses.

### Backfill strategy

Use best effort:

- manual flag → `MANUAL_ENTRY`
- raw notification ID → notification/review-derived source if possible
- receipt link → `RECEIPT_SCAN`
- group link → `GROUP_EXPENSE`
- otherwise `UNKNOWN`

### Tests

- migration test
- event DAO insert/read test
- existing expenses survive migration
- source column is populated reasonably

### Done when

- Room migration passes.
- No existing data loss.
- Existing app behavior unchanged.

---

## PR 3 — Coordinator skeleton with validation and dedup

### Goal

Implement coordinator creation flow but use it only in tests.

### Coordinator responsibilities in this PR

- validate request
- normalize currency
- normalize merchant
- generate dedupe key
- run `isDuplicateCurrencyAware`
- call `insertAtomic`
- write transaction event
- return structured result

### Do not yet run all side effects.

Use minimal side effects or none initially to reduce risk.

### Tests

Create coordinator tests for:

- valid manual-style request inserts
- amount <= 0 rejected
- amount > 1,000,000 rejected
- NaN/infinite amount rejected
- blank merchant rejected
- placeholder merchant rejected
- missing currency rejected
- future date rejected by default
- transfer missing transfer fields rejected
- duplicate detected before insert
- insert conflict becomes duplicate/conflict result
- lifecycle event written on create
- lifecycle event written on validation failure if supported

### Done when

- Coordinator can create an expense in isolation.
- No existing production path uses it yet.

---

## PR 4 — Manual entry path migration

### Goal

Move the safest path first.

### Files

- `AddExpenseViewModel`
- `ManualExpenseRepository`
- related manual-entry tests

### Change

`ManualExpenseRepository.addManualExpense()` should build a `CreateExpenseRequest` and call `TransactionLifecycleCoordinator`.

Keep ViewModel validation for user-friendly messages, but coordinator remains the final authority.

### Preserve

- recurring rule creation
- merchant normalization
- category learning
- budget check
- anomaly alert
- recommendation generation

### Important

If recurring rule creation must be atomic with expense creation, either:

1. include recurring rule link info in the lifecycle request, or
2. let `ManualExpenseRepository` run a source-specific transaction wrapper around coordinator internals.

Preferred long-term: coordinator owns the transaction and accepts a recurring rule link plan.

### Tests

- valid manual expense still creates
- duplicate manual expense skipped
- recurring manual expense still creates recurrence rule
- transfer validation still works
- ownership validation still works
- side effects still fire

### Done when

- Manual entry no longer calls `expenseDao.insertAtomic` directly.
- Behavior is equivalent or stricter.

---

## PR 5 — Pending review approval migration

### Goal

Move review approval to the coordinator.

### Files

- `ReviewQueueRepository`
- `ReviewViewModel`
- pending review tests

### Change

`approveReview`, `approveReviewWithEdits`, quick approve, and approve all should all eventually call the same coordinator path.

### Preserve

- `PENDING → PROCESSING → APPROVED` status transition
- duplicate handling
- raw notification relevance update
- source stats accepted/rejected/duplicate counts
- scanned receipt link
- user correction insert
- classifier retraining
- merchant category learning
- confidence router invalidation

### Fix while migrating

Do not allow fallback pending review values to become real expenses without user correction.

If `suggestedAmount` is synthetic `0.01`, coordinator should reject it unless the user explicitly confirmed/edited amount.

If merchant is `"Unknown"`, require user edit before approval.

### Tests

- approval creates expense through coordinator
- duplicate approval does not create second expense
- already-processed review cannot create duplicate
- synthetic amount review cannot approve without edit
- unknown merchant review cannot approve without edit
- source stats update correctly
- raw notification marked relevant
- scanned receipt linked

### Done when

- `ReviewQueueRepository` no longer directly calls `expenseDao.insertAtomic` for approval.
- Approval lifecycle is audited.

---

## PR 6 — Notification auto-accept migration

### Goal

Move notification auto-accept expense creation into coordinator.

### Files

- `NotificationProcessingPipeline`
- `NotificationRepository`
- notification pipeline tests

### Change

`handleAutoAcceptInTransaction()` should build a lifecycle request and call coordinator.

### Preserve

- raw notification dedup
- confidence routing
- pending review fallback
- amount > 1,000,000 downgrade to review
- pending duplicate check
- source stats
- raw notification relevance
- classifier behavior
- subscription detection
- anomaly/budget checks

### Important

Notification pipeline currently has transactional logic. Avoid nesting DB transactions incorrectly.

Recommended approach:

- keep raw notification insertion and routing outside coordinator if needed
- coordinator handles only final Expense insert lifecycle
- source stats and raw notification relevance can be part of coordinator link/side-effect policy

### Tests

- auto-accept notification creates expense
- duplicate notification does not create duplicate expense
- low-confidence notification goes to review
- amount over limit goes to review
- unknown merchant does not become real auto-accepted expense unless policy explicitly allows reviewed flow
- source stats match old behavior

### Done when

- notification expense creation does not directly call `expenseDao.insertAtomic`.

---

## PR 7 — Receipt path migration

### Goal

Move user-confirmed receipt expense creation into coordinator.

### Files

- `ReceiptScanViewModel`
- `ReceiptRepository.createExpenseFromReceipt`
- receipt tests

### Change

`createExpenseFromReceipt()` should build a lifecycle request and call coordinator.

### Preserve

- OCR scan persistence
- scanned receipt link
- category classification
- duplicate check
- budget monitor
- anomaly alert
- merchant-category learning
- hybrid classifier learning where applicable
- warranty extraction flow

### Fix while migrating

Receipt-created real expenses must validate:

- amount > 0
- amount <= 1,000,000
- merchant non-blank
- currency explicit
- no `"Parsing Failed"` merchant for real expense

Failed parses should create review/error states, not fake expenses.

### Tests

- user-confirmed receipt creates expense
- receipt duplicate skipped
- scanned receipt linked after creation
- invalid amount rejected
- missing currency rejected
- parsing failed placeholder cannot create real expense

### Done when

- receipt expense creation no longer directly calls `expenseDao.insertAtomic`.

---

## PR 8 — CSV import migration

### Goal

Fix the most dangerous creation path.

### Files

- `CsvExpenseImporter`
- `DebugScreen`
- `DebugViewModel`
- CSV import tests

### Current problem

CSV import currently:

- calls `expenseDao.insert()` directly
- has no dedupe
- has no dedupe key
- has no merchant key
- has no explicit currency
- relies on `"EUR"` default
- skips lifecycle side effects
- skips lifecycle event

### New behavior

CSV importer should parse rows into lifecycle requests.

CSV result should include:

- imported count
- duplicate count
- validation error count
- per-row errors
- created expense IDs if useful

### Currency policy

Options:

1. CSV has currency column.
2. If missing, use current home currency from currency settings.
3. If neither available, row fails validation.

No silent entity default.

### Dedup policy

Use `BULK_IMPORT`.

### Side effect policy

Use batch-safe side effects:

- no per-row anomaly notification spam
- no per-row recommendation spam
- write lifecycle events
- optionally refresh/recalculate after batch

### Tests

- duplicate CSV row skipped
- merchant key generated
- dedupe key generated
- explicit currency used
- invalid date produces row error
- invalid amount produces row error
- no direct DAO insert remains

### Done when

- CSV no longer bypasses lifecycle.
- CSV import cannot create undeduped expenses.

---

## PR 9 — Email receipt migration

### Goal

Add missing standard dedupe and side effects to email-created expenses.

### Files

- `EmailReceiptIngestionService`
- email ingestion tests

### Change

Email receipt creation calls coordinator.

### Preserve

- message ID dedup
- email fingerprint dedup
- scanned receipt/source linkage

### Add

- range-based `isDuplicateCurrencyAware`
- standard dedupe key
- standard lifecycle event
- budget/anomaly side effects unless intentionally suppressed

### Tests

- same message ID does not create duplicate
- same merchant/amount/date/currency range duplicate skipped
- email receipt links preserved
- explicit currency required or resolved

### Done when

- email creation no longer directly calls `expenseDao.insertAtomic`.

---

## PR 10 — Group/shared expense migration

### Goal

Fix missing dedup and missing side effects in group expense creation.

### Files

- `GroupTransactionCoordinator`
- `AddGroupExpenseUseCase`
- `SharedExpenseGroupsViewModel`
- group expense tests

### Current problem

Group expense creation:

- uses `insertAtomic`
- does not run `isDuplicateCurrencyAware`
- does not run budget monitor
- does not run anomaly alert
- writes group link separately

### New behavior

Group creation should call coordinator with source `GROUP_EXPENSE`.

Group-specific validation stays in `GroupTransactionCoordinator`:

- group exists
- group active
- payer is member
- split valid
- current user share resolved

Coordinator handles:

- amount validation
- currency validation
- merchant normalization
- dedupe
- source event
- side effects

### Atomic link requirement

Expense insert and `GroupExpense` link should be atomic.

Recommended approach:

- lifecycle request includes group link info, or
- coordinator exposes a group creation mode that inserts both inside the same DB transaction

Avoid:

- insert expense first, then group link outside transaction

### Tests

- group expense creates expense and group link atomically
- duplicate group expense skipped
- budget/anomaly side effects fire
- group default currency is passed explicitly
- invalid split fails before coordinator call

### Done when

- group creation does not directly call `expenseDao.insertAtomic`.
- group expenses receive standard lifecycle behavior.

---

## PR 11 — Bank API future-proofing

### Goal

Prepare bank API creation path even if current bank API is stubbed.

### Files

- bank API integration/stub files
- future sync service/use case

### Required design

Bank-created expenses should use:

- source `BANK_API_SYNC`
- external transaction ID as idempotency key
- explicit bank-provided currency
- transaction date from bank feed
- strict external ID dedup first
- standard range dedup second

### Tests

Even if bank API remains mock/stub:

- bank transaction request maps to lifecycle request
- same external transaction ID cannot insert twice
- missing currency fails

### Done when

- future bank sync has a defined lifecycle path.

---

## PR 12 — Update lifecycle migration

### Goal

Move expense updates into coordinator and remove direct update bypasses.

### Files

- `ExpenseRepository`
- `GroupTransactionCoordinator.normalizeLinkedSystemExpense`
- `MainActivity.applyVisualSplitToExpense`
- update tests

### Current problem

Most edits go through `ExpenseRepository`, but some bypass it:

- `GroupTransactionCoordinator` direct update calls
- `MainActivity.applyVisualSplitToExpense()` uses `insertAll` with REPLACE from UI layer

### New behavior

Add coordinator update methods for:

- category update
- merchant update
- type update
- transfer update
- ownership update
- shared expense update
- location update
- notes/details update
- bulk category update
- bulk merchant update

### Critical fix

Remove `MainActivity` direct DAO access.

Replace:

- load expense
- mutate object
- call `insertAll` with REPLACE

With:

- ViewModel/use case call
- coordinator update method
- DAO update statement or safe repository method
- lifecycle event

### Dedupe key recomputation

Recompute dedupe key when these fields change:

- merchant
- amount
- date
- currency
- transaction type

For update paths that can change these fields, run duplicate check before committing.

### Tests

- category update writes event
- merchant update recomputes merchant key and dedupe key
- type update recomputes dedupe key
- ownership update writes event
- visual split update no longer uses replace insert
- group normalization uses coordinator/update use case
- duplicate-causing update fails or requires explicit override

### Done when

- no production UI layer directly writes to `ExpenseDao`.
- update lifecycle is auditable.

---

## PR 13 — Delete lifecycle

### Goal

Make deletes auditable and centralized.

### Files

- `ExpenseRepository`
- delete callers
- debug tools
- transaction event DAO

### New behavior

`deleteExpense` should:

1. load current expense
2. write delete lifecycle event with before snapshot
3. delete expense
4. run post-delete side effects if needed

### Soft delete decision

Do not introduce soft delete in the first Phase 3 pass unless you are ready to update all queries.

Soft delete requires:

- new `deletedAt` column
- every query to filter deleted rows
- restore path
- aggregation changes
- UI changes

Recommended Phase 3 approach:

- keep hard delete
- add lifecycle event snapshot
- consider soft delete later

### Tests

- delete writes lifecycle event
- delete removes expense
- delete all debug path is marked debug
- restore snapshot is marked debug/migration event

### Done when

- expense deletion is traceable.

---

## PR 14 — Placeholder/fake value cleanup

### Goal

Stop fake parser values from becoming real transactions.

### Files

- `ReviewQueueRepository`
- `ReceiptRepository`
- `NotificationProcessingPipeline`
- `ProcessReceiptUseCase`
- `WarrantyTrackerRepository`
- parser/review tests

### Rules

Fake values may exist only as UI hints or review placeholders, never as accepted real expense fields without user confirmation.

### Replace patterns

#### `0.01` fallback amount

Preferred:

- allow pending review amount to be nullable, or
- add a field indicating amount is missing/synthetic

If schema change is too large now:

- keep legacy fallback only inside pending review
- mark it as synthetic
- coordinator rejects approval until user edits amount

#### `"Unknown"` merchant

Real expenses should reject it.

Pending review can show:

- “Merchant missing”
- requires user input

#### `"Parsing Failed"` merchant

Never create a real expense with this merchant.

#### `confidence = 1.0f` fallback

Do not assign perfect confidence to parser failure.

Use low confidence or explicit parse-status fields.

#### hardcoded `"EUR"`

Do not use hardcoded EUR in lifecycle creation.

Use:

- parsed currency
- user/home currency
- group default currency
- explicit import currency
- validation failure

### Tests

- fallback amount cannot be approved unchanged
- unknown merchant cannot be approved unchanged
- parse failed receipt cannot create real expense
- missing currency fails or requires user selection
- parser failure confidence is not perfect

### Done when

- fake values no longer contaminate real expense lifecycle.

---

## PR 15 — Side effect consolidation

### Goal

Move post-create behavior behind one dispatcher.

### Files

- `ManualExpenseRepository`
- `ReviewQueueRepository`
- `NotificationProcessingPipeline`
- `ReceiptRepository`
- `EmailReceiptIngestionService`
- `GroupTransactionCoordinator`
- new `TransactionSideEffectDispatcher`

### Change

After a coordinator commit, side effects are dispatched from one place according to source policy.

### Requirements

- post-commit only
- failures logged as lifecycle side-effect events
- failures should not roll back already-created expense unless side effect is source-link-critical
- use app/application coroutine scope for fire-and-forget tasks
- batch import should use summary/batch effects

### Tests

- manual side effects fire
- notification side effects fire
- group side effects now fire
- email side effects now fire
- side-effect failure does not duplicate expense
- side-effect failure writes event/log

### Done when

- side effect differences are intentional, not accidental.

---

## PR 16 — Direct DAO access guardrails

### Goal

Prevent regression.

### Add a simple audit/CI check for production code.

Flag these outside approved files:

- `expenseDao.insert`
- `expenseDao.insertAtomic`
- `expenseDao.insertAll`
- `expenseDao.update`
- `expenseDao.delete`
- `expenseDao.deleteAll`

Approved files should be minimal:

- `TransactionLifecycleCoordinator`
- `ExpenseDao`
- migration/debug snapshot code, if explicitly allowed
- tests

### Also scan for

- hardcoded `"EUR"` in creation paths
- fallback `0.01`
- `"Unknown"` as real merchant
- `"Parsing Failed"` as real merchant
- direct update from `MainActivity`
- `insertAll` with REPLACE for expense mutation

### Done when

- build or audit script catches new bypasses.

---

# 9. Path-by-Path Final Target

## Manual entry

Final state:

- UI validates for friendly messages.
- Repository builds lifecycle request.
- Coordinator creates expense.
- Side effects run from dispatcher.
- Recurring rule is linked atomically or through explicit post-create handling.

No direct DAO insert.

---

## Notification auto-accept

Final state:

- Notification pipeline parses/routes.
- If auto-accepted, it calls coordinator.
- Coordinator validates/dedups/inserts/events.
- Source stats and raw notification relevance are handled through source policy.

No direct DAO insert.

---

## Pending review approval

Final state:

- Approval path calls coordinator.
- Synthetic/fake review values are blocked unless user corrected.
- User correction and source stats are still recorded.
- Receipt/raw-notification links preserved.

No direct DAO insert.

---

## Receipt scan

Final state:

- OCR/parser may create review candidates.
- User-confirmed save calls coordinator.
- Scanned receipt is linked atomically.
- Failed parses do not create fake expenses.

No direct DAO insert.

---

## CSV import

Final state:

- CSV rows become lifecycle requests.
- Explicit currency is resolved.
- Dedup is applied.
- Per-row results are returned.
- Batch-safe side effects are used.

No bare `expenseDao.insert`.

---

## Email receipt

Final state:

- Message/fingerprint dedup remains.
- Standard lifecycle dedup is added.
- Coordinator creates expense.
- Receipt/source links preserved.

No direct DAO insert.

---

## Group/shared

Final state:

- Group validation remains in group coordinator/use case.
- Expense creation goes through lifecycle coordinator.
- Group link is atomic.
- Dedup and side effects apply.

No direct DAO insert.

---

## Bank API

Final state:

- Bank sync maps external transaction to lifecycle request.
- External transaction ID is idempotency key.
- Currency is explicit.
- Standard lifecycle applies.

---

# 10. Testing Strategy

## 10.1 Coordinator unit tests

Must cover:

- validation success
- amount validation
- merchant validation
- currency validation
- date validation
- transfer validation
- ownership validation
- dedupe key generation
- range duplicate check
- insert conflict
- lifecycle event creation
- source-specific policy selection

---

## 10.2 Integration tests by source

Add or update tests for:

- manual entry creation
- pending review approval
- notification auto-accept
- receipt save
- CSV import
- email receipt
- group expense
- debug restore

---

## 10.3 Regression tests for known audit bugs

Specific test cases:

1. CSV import of same row twice creates one expense and one duplicate result.
2. CSV imported expense has merchant key and dedupe key.
3. CSV imported expense has explicit currency.
4. Group expense duplicate is skipped.
5. Group expense triggers budget/anomaly policy.
6. Email receipt duplicate by range is skipped.
7. Pending review with synthetic `0.01` cannot approve unchanged.
8. Pending review with `"Unknown"` merchant cannot approve unchanged.
9. Receipt parse failure cannot create `"Parsing Failed"` merchant expense.
10. `MainActivity` no longer writes to `ExpenseDao`.
11. Updating merchant recomputes dedupe key.
12. Updating transaction type recomputes dedupe key.
13. Delete writes lifecycle event.
14. Direct DAO insert scan finds no production bypasses.

---

# 11. Acceptance Criteria

Phase 3 is complete when:

1. All expense creation paths call `TransactionLifecycleCoordinator`.
2. No production path directly calls `expenseDao.insert`, `insertAtomic`, or `insertAll` except the coordinator/debug restore exception.
3. CSV import uses dedupe, merchant normalization, explicit currency, and lifecycle events.
4. Group/shared expenses use standard dedupe and post-create side effects.
5. Email receipt expenses use standard lifecycle dedupe.
6. Manual, notification, review, receipt, CSV, email, group, and future bank paths use one validation contract.
7. Every real expense has a source.
8. Every create/update/delete writes a lifecycle event.
9. Placeholder values cannot become real expenses without user correction.
10. Direct UI-layer DAO mutation is removed.
11. Dedupe key generation is centralized.
12. Range duplicate check is applied consistently.
13. Side effects are centrally configured and post-commit.
14. Tests cover all migrated creation paths.
15. Audit guardrail prevents new direct DAO bypasses.

---

# 12. Recommended Implementation Order Summary

Recommended order:

1. Add lifecycle models.
2. Add source/event schema.
3. Implement coordinator in isolation.
4. Migrate manual entry.
5. Migrate pending review approval.
6. Migrate notification auto-accept.
7. Migrate receipt save.
8. Migrate CSV import.
9. Migrate email receipt.
10. Migrate group/shared.
11. Prepare bank API lifecycle path.
12. Centralize update lifecycle.
13. Centralize delete lifecycle.
14. Remove fake placeholder leakage.
15. Consolidate side effects.
16. Add direct DAO guardrails.
17. Update docs/audit checklist.

This order reduces risk because manual entry is the cleanest path, while CSV, group, email, and UI direct DAO access are the highest-risk bypasses and should be fixed after the coordinator has already been proven.