# Phase 3 — Transaction Lifecycle Foundation: Final Implementation Plan

> **Generated**: 2026-05-01
> **Based on**: `transaction-lifecycle-audit.md`, `phase3-transaction-lifecycle-implementation-plan.md` (user template), and full codebase inspection.
> **Status**: Approved Plan — ready for execution.

---

## 0. Plan Evaluation & Decision

**Verdict**: The user template plan (`phase3-transaction-lifecycle-implementation-plan.md`) is comprehensive, architecturally sound, and correctly identifies all the problems documented in the audit. I **endorse it** with the following refinements:

| Area | Template Decision | Refinement |
|---|---|---|
| PR count | 16 PRs | Merge to **12 PRs** (see below) |
| Money/model integration | Uses bare `String` for currency | Integrate Phase 1's `CurrencyCode` value class |
| Amount validation | Uses `Double` | Validate via `MoneyAmount` where practical; keep `Double` in the DB entity |
| Date handling | Manual `Long` validation | Use Phase 2's `TimeProvider.nowMs()` + `PeriodRange` bounds where useful |
| `TransactionEvent` table timing | PR 2 | Correct — must exist before any path writes events |
| `CreateExpenseRequest` size | Monolithic (40+ fields) | Acceptable for v1; can be split into core/extensions in Phase 4 |
| Side-effect matrix (Section 7.3) | Per-source policy | Correct and matches audit findings |
| Fake value cleanup timing | PR 14 (late) | **Moved earlier** — fake value guards should be in the coordinator from PR 3 |

---

## 1. Phase 3 Mission

### What "Done" Means

Phase 3 is complete when:

1. **All 8 expense creation paths** route through `TransactionLifecycleCoordinator.createExpense()` — no path bypasses it.
2. **No production code** calls `expenseDao.insert()`, `insertAtomic()`, or `insertAll()` except the coordinator itself and approved debug/restore paths.
3. **Every real `Expense` row** has: a computed `dedupeKey`, a normalized `merchantKey`, an explicit `currency`, an explicit `source`, and a corresponding `TransactionEvent`.
4. **CSV import** — the most dangerous path — gains full deduplication, validation, and lifecycle audit.
5. **Group/shared expenses** gain standard deduplication and post-creation side effects (budget, anomaly).
6. **Placeholder/fake values** (`0.01`, `"Unknown"`, `"Parsing Failed"`, `confidence=1.0f`) can **never** produce real expenses without explicit user correction.
7. **MainActivity** no longer calls `expenseDao.insertAll()` directly.
8. **All create/update/delete operations** write a `TransactionEvent` record.
9. **Side effects** (budget check, anomaly alert, merchant-category learning, classifier training, source stats, etc.) are consistently dispatched per source policy through a single dispatcher.
10. **A CI/audit guardrail** flags any new `expenseDao.insert*/update*/delete*` calls outside the allowed set of files.

---

## 2. Target Architecture

### 2.1 Central Coordinator

```
All creation paths ──→ TransactionLifecycleCoordinator.createExpense(request)
                              │
                              ├─ 1. Validate (amount, currency, merchant, date, type, ownership)
                              ├─ 2. Normalize merchant → merchantKey
                              ├─ 3. Auto-classify category (if policy allows)
                              ├─ 4. Generate dedupeKey
                              ├─ 5. Range-based duplicate check (isDuplicateCurrencyAware)
                              ├─ 6. DB transaction:
                              │     ├─ insertAtomic(expense)
                              │     ├─ insert source links
                              │     └─ insert TransactionEvent
                              └─ 7. Post-commit: TransactionSideEffectDispatcher
                                    ├─ Budget check
                                    ├─ Anomaly alert
                                    ├─ Merchant-category learning
                                    ├─ Classifier training
                                    ├─ Source stats update
                                    ├─ Raw notification relevance
                                    ├─ Scanned receipt link
                                    └─ ... (per SourcePolicy)
```

### 2.2 Existing Classes After Phase 3

- **`ExpenseRepository`** → Read/query facade + compatibility delegator. Gains `createExpense()`, `updateExpense()`, `deleteExpense()` methods that delegate to the coordinator.
- **`ManualExpenseRepository`** → Converts UI state to `CreateExpenseRequest`, calls coordinator.
- **`ReviewQueueRepository`** → Converts `PendingReview` to `CreateExpenseRequest`, calls coordinator.
- **`NotificationProcessingPipeline`** → Parses/routes; for auto-accept, calls coordinator.
- **`ReceiptRepository`** → OCR persists; for confirmed saves, calls coordinator.
- **`CsvExpenseImporter`** → Parses rows into `CreateExpenseRequest`, calls coordinator per row.
- **`EmailReceiptIngestionService`** → Converts parsed receipt to `CreateExpenseRequest`, calls coordinator.
- **`GroupTransactionCoordinator`** → Validates group/payer/split, calls coordinator for expense insert.
- **`MainActivity`** → **NO** direct `expenseDao` access. Uses ViewModel → use case → coordinator.

---

## 3. New Domain Models

### 3.1 `ExpenseSource` Enum

```kotlin
// Package: com.yourname.expensetracker.domain.transaction

enum class ExpenseSource {
    MANUAL_ENTRY,
    NOTIFICATION_AUTO_ACCEPT,
    REVIEW_APPROVAL,
    RECEIPT_SCAN,
    RECEIPT_BATCH_REVIEW,
    BANK_STATEMENT_REVIEW,
    CSV_IMPORT,
    EMAIL_RECEIPT,
    GROUP_EXPENSE,
    BANK_API_SYNC,
    RECURRING_GENERATED,
    DEBUG_TOOL,
    MIGRATION,
    UNKNOWN
}
```

Stored as `String` in Room (not ordinal). Added as nullable column `source` on `Expense` table; backfilled by migration.

### 3.2 `LifecycleEventType` Enum

```kotlin
enum class LifecycleEventType {
    CREATE_ATTEMPTED,
    CREATED,
    CREATE_VALIDATION_FAILED,
    CREATE_DUPLICATE_SKIPPED,
    CREATE_INSERT_CONFLICT,
    UPDATED,
    BULK_UPDATED,
    DELETED,
    RESTORED_FROM_DEBUG_SNAPSHOT,
    SOURCE_LINKED,
    SIDE_EFFECT_FAILED,
    SOFT_DELETED       // reserved for future soft-delete
}
```

### 3.3 `CreateExpenseRequest`

A source-neutral data class. Required fields must be non-null. Optional fields allow null.

```kotlin
data class CreateExpenseRequest(
    // ── REQUIRED ──
    val merchant: String,
    val amount: Double,                // must be > 0, <= 1_000_000, finite
    val currency: String,              // ISO 4217, validated via CurrencyCode.parse()
    val date: Long,                    // epoch ms; validated via DateValidationPolicy
    val transactionType: TransactionType,
    val source: ExpenseSource,

    // ── OPTIONAL: Classification ──
    val categoryId: Long? = null,
    val categoryConfidence: Float? = null,
    val classifierLabel: String? = null,
    val isUserSelectedCategory: Boolean = false,

    // ── OPTIONAL: Display/Details ──
    val notes: String? = null,
    val description: String? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,

    // ── OPTIONAL: Transfer ──
    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null,

    // ── OPTIONAL: Ownership ──
    val isNotMine: Boolean = false,
    val ownerName: String? = null,
    val isSharedExpense: Boolean = false,
    val sharedWithName: String? = null,
    val mySharePercentage: Int? = null,
    val myShareAmount: Double? = null,

    // ── OPTIONAL: Location ──
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,
    val placeId: String? = null,
    val address: String? = null,

    // ── OPTIONAL: Source Links ──
    val rawNotificationId: Long? = null,
    val pendingReviewId: Long? = null,
    val scannedReceiptId: Long? = null,
    val emailReceiptSourceId: Long? = null,
    val groupId: Long? = null,
    val groupExpenseLinkInfo: GroupExpenseLinkInfo? = null,
    val bankTransactionId: String? = null,
    val csvImportBatchId: String? = null,
    val csvRowNumber: Int? = null,
    val externalFingerprint: String? = null,

    // ── OPTIONAL: Business/Expense Metadata ──
    val isBusinessExpense: Boolean = false,
    val businessPurpose: String? = null,
    val businessCategory: String? = null,
    val businessProject: String? = null,
    val requiresReceipt: Boolean = false,

    // ── OPTIONAL: Policy Overrides ──
    val deduplicationMode: DeduplicationMode = DeduplicationMode.STANDARD,
    val dateValidationPolicy: DateValidationPolicy = DateValidationPolicy.REJECT_FUTURE,
    val sideEffectPolicy: SideEffectPolicy = SideEffectPolicy.STANDARD,
    val idempotencyKey: String? = null,

    // ── OPTIONAL: Audit ──
    val actor: String? = null,          // user action label, e.g. "manual_save", "auto_accept"
    val reason: String? = null          // debug note, e.g. "imported from CSV row 5"
)
```

### 3.4 `CreateExpenseResult`

```kotlin
sealed class CreateExpenseResult {
    data class Created(val expenseId: Long) : CreateExpenseResult()
    data class DuplicateSkipped(
        val existingExpenseId: Long,
        val reason: String
    ) : CreateExpenseResult()
    data class ValidationFailed(
        val errors: List<ValidationError>
    ) : CreateExpenseResult()
    data class InsertConflict(
        val dedupeKey: String,
        val message: String
    ) : CreateExpenseResult()
    data class SourceLinkFailed(
        val expenseId: Long,
        val linkError: String
    ) : CreateExpenseResult()
    data class Failure(
        val exception: Throwable
    ) : CreateExpenseResult()
}

// Companion for bulk CSV results:
data class BatchCreateResult(
    val createdCount: Int,
    val duplicateCount: Int,
    val validationFailedCount: Int,
    val rowResults: List<Pair<Int, CreateExpenseResult>>  // row number → result
)
```

### 3.5 `ExpenseUpdates` (Patch Object)

```kotlin
data class ExpenseUpdates(
    val categoryId: Long? = null,          // null = no change
    val merchant: String? = null,
    val transactionType: TransactionType? = null,
    val transferDirection: TransferDirection? = null,
    val transferAccountName: String? = null,
    val isNotMine: Boolean? = null,
    val ownerName: String? = null,
    val isSharedExpense: Boolean? = null,
    val sharedWithName: String? = null,
    val mySharePercentage: Int? = null,
    val myShareAmount: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationSource: String? = null,
    val placeId: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val isBusinessExpense: Boolean? = null,
    val businessPurpose: String? = null,
    val businessCategory: String? = null,
    val businessProject: String? = null,
    val requiresReceipt: Boolean? = null,
    // Audit:
    val actor: String? = null,
    val reason: String? = null,
    val shouldRecomputeDedupeKey: Boolean = false,
    val shouldTriggerLearning: Boolean = true
)
```

### 3.6 `TransactionEvent` Entity

```kotlin
@Entity(
    tableName = "transaction_events",
    indices = [
        Index("expenseId"),
        Index("source"),
        Index("occurredAt"),
        Index("eventType")
    ]
)
data class TransactionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long?,                        // null for validation failures
    val eventType: String,                       // LifecycleEventType name
    val source: String?,                         // ExpenseSource name
    val actor: String?,                          // user action label
    val occurredAt: Long,                        // epoch ms from TimeProvider
    val idempotencyKey: String?,
    val dedupeKey: String?,
    val duplicateExpenseId: Long?,
    val beforeSnapshotJson: String?,             // JSON snapshot of Expense before mutation
    val afterSnapshotJson: String?,              // JSON snapshot of Expense after mutation
    val metadataJson: String?,                   // arbitrary metadata
    val reason: String?                          // human-readable reason
)
```

### 3.7 Policy Enums

```kotlin
enum class DeduplicationMode {
    STANDARD,             // normalize → dedupeKey → range-check → insertAtomic
    STRICT_EXTERNAL_ID,   // external ID first → then standard
    BULK_IMPORT,          // standard + batch summary (suppress per-row alerts)
    SKIP_FOR_DEBUG_RESTORE
}

enum class DateValidationPolicy {
    REJECT_FUTURE,        // future dates rejected
    ALLOW_FUTURE,         // e.g., planned expenses
    REQUIRE_PAST_OR_PRESENT
}

enum class SideEffectPolicy {
    STANDARD,             // run all applicable side effects
    BATCH_IMPORT,         // summary effects only
    DEBUG_RESTORE,        // no side effects
    MINIMAL               // only critical effects
}
```

### 3.8 `ValidationError` Model

```kotlin
data class ValidationError(
    val field: String,        // e.g. "amount", "merchant", "currency"
    val code: String,         // e.g. "AMOUNT_NOT_POSITIVE", "MERCHANT_IS_PLACEHOLDER"
    val message: String
)
```

---

## 4. Database / Room Changes

### 4.1 Add `source` column to `expenses`

```sql
ALTER TABLE expenses ADD COLUMN source TEXT DEFAULT NULL;
```

- Nullable initially. Made non-null after backfill and all callers proven.
- Backfill SQL (best-effort):
  ```sql
  UPDATE expenses SET source = 'MANUAL_ENTRY' WHERE isManualEntry = 1;
  UPDATE expenses SET source = 'NOTIFICATION_AUTO_ACCEPT'
    WHERE rawNotificationId IS NOT NULL AND source IS NULL;
  UPDATE expenses SET source = 'RECEIPT_SCAN'
    WHERE id IN (SELECT expenseId FROM scanned_receipts WHERE expenseId IS NOT NULL)
    AND source IS NULL;
  UPDATE expenses SET source = 'GROUP_EXPENSE'
    WHERE id IN (SELECT expenseId FROM group_expenses)
    AND source IS NULL;
  UPDATE expenses SET source = 'UNKNOWN' WHERE source IS NULL;
  ```

### 4.2 Add `transaction_events` table

Full DDL in the migration. Indices on `expenseId`, `source`, `occurredAt`, `eventType`.

### 4.3 No other schema changes in Phase 3 v1

- No soft-delete column yet (deferred to later phase).
- No `amount` column type change — keep `Double` in the DB entity. The coordinator validates via `MoneyAmount`-style checks but stores as `Double`.
- The `Expense` entity already has `currency`, `merchantKey`, `dedupeKey`, and ownership fields — no new columns needed beyond `source`.

---

## 5. Implementation Plan — 12 PRs (Consolidated)

### PR 0 — Baseline & Branch Protection

**Purpose**: Document pre-Phase-3 state before any behavior change.

**Actions**:
1. Create Phase 3 feature branch from latest `main`/`develop`.
2. Run full `./gradlew test` and `./gradlew assembleDebug`.
3. Record any pre-existing test failures in `docs/phases/phase3-baseline-failures.md`.
4. Audit and list all current direct-DAO call sites (already done in `transaction-lifecycle-audit.md`).
5. No code changes.

**Done when**: Baseline is documented and the branch is clean.

---

### PR 1 — Models, Contracts, DB Schema, and Coordinator Skeleton

*(Merges template PRs 1 + 2 + 3)*

**Purpose**: Deliver all new models, DB tables, and a working (but unused) coordinator.

**Files**:
- **Create**: `domain/transaction/ExpenseSource.kt`
- **Create**: `domain/transaction/lifecycle/LifecycleEventType.kt`
- **Create**: `domain/transaction/lifecycle/CreateExpenseRequest.kt`
- **Create**: `domain/transaction/lifecycle/CreateExpenseResult.kt`
- **Create**: `domain/transaction/lifecycle/ExpenseUpdates.kt`
- **Create**: `domain/transaction/lifecycle/DeduplicationMode.kt`
- **Create**: `domain/transaction/lifecycle/DateValidationPolicy.kt`
- **Create**: `domain/transaction/lifecycle/SideEffectPolicy.kt`
- **Create**: `domain/transaction/lifecycle/ValidationError.kt`
- **Create**: `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`
- **Create**: `data/database/entity/TransactionEvent.kt`
- **Create**: `data/database/dao/TransactionEventDao.kt`
- **Modify**: `data/database/entity/Expense.kt` — add `source: String? = null`
- **Modify**: `data/database/AppDatabase.kt` — bump version, add migration, register `TransactionEvent` entity, add DAO accessor

**Coordinator skeleton methods**:
```kotlin
class TransactionLifecycleCoordinator @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val transactionEventDao: TransactionEventDao,
    private val merchantNormalizer: MerchantNormalizer,
    private val timeProvider: TimeProvider,
    private val appScope: CoroutineScope,
    // side-effect dependencies injected later in PR 10
) {
    suspend fun createExpense(
        request: CreateExpenseRequest
    ): CreateExpenseResult { /* full flow: validate → normalize → dedup → insert → event */ }

    suspend fun updateExpense(
        expenseId: Long,
        updates: ExpenseUpdates
    ): Result<Unit> { /* skeleton only — full implementation in PR 6 */ }

    suspend fun deleteExpense(
        expenseId: Long,
        reason: String? = null
    ): Result<Unit> { /* skeleton only — full implementation in PR 7 */ }

    suspend fun checkDuplicate(
        amount: Double,
        merchant: String,
        date: Long,
        currency: String,
        transactionType: TransactionType
    ): Boolean
}
```

**Coordinator validation rules (implemented in this PR)**:
- Amount: must be finite, > 0, <= 1_000_000
- Currency: must be valid ISO 4217 (via `CurrencyCode.parse()`), non-blank
- Merchant: must be non-blank; must NOT be `"Unknown"`, `"Unknown Merchant"`, `"Parsing Failed"`, or `"Unknown Product"`
- Date: must be > 0; by default rejects future dates (use `DateValidationPolicy`)
- Transaction type: `TRANSFER` requires `transferDirection` and `transferAccountName`
- Ownership: cannot have both `isNotMine` and `isSharedExpense` true simultaneously
- DedupeKey: always generated via `DuplicateDetectionPolicy.generateDedupeKeyWithType()`
- Range duplicate check: always calls `expenseDao.isDuplicateCurrencyAware()` (unless `SKIP_FOR_DEBUG_RESTORE`)
- Atomic insert: always uses `expenseDao.insertAtomic()`
- Transaction event: always written for create/validation-failure/duplicate-skipped

**DB migration**: Add `source` column to `expenses`, create `transaction_events` table, backfill `source` for existing rows.

**Tests**:
- Coordinator unit tests for all validation rules (positive + negative)
- Duplicate detection test
- Lifecycle event written on create
- Migration test (existing data survives)
- Event DAO insert/read test

**Done when**: All new models compile, migration passes, coordinator can create expenses in test isolation. **No production path uses the coordinator yet.**

---

### PR 2 — Manual Entry Migration

*(Template PR 4)*

**Purpose**: Move the safest, most-validated path first.

**Files**:
- **Modify**: `ManualExpenseRepository.kt` — `addManualExpense()` builds `CreateExpenseRequest`, calls coordinator
- **Modify**: `AddExpenseViewModel.kt` — keep ViewModel validation for UI messaging; `save()` calls repository (which now calls coordinator)
- **Modify**: `ExpenseRepository.kt` — add `createExpense()` delegator method

**Preserve**:
- Recurring rule creation (if `isRecurring` checked) — done atomically via coordinator's recurring link info or post-create in repository
- ViewModel validation (friendly error messages to user)
- Side effects that currently fire (budget, anomaly, merchant-category learning, recommendations)

**Tests**:
- Manual expense creates through coordinator
- Duplicate detection works
- Recurring rule still created
- Transfer validation still enforced
- Side effects still fire

**Done when**: `ManualExpenseRepository` no longer calls `expenseDao.insertAtomic()` directly.

---

### PR 3 — Pending Review Approval Migration

*(Template PR 5)*

**Purpose**: Centralize review-to-expense approval.

**Files**:
- **Modify**: `ReviewQueueRepository.kt` — `approveReview()`, `approveReviewWithEdits()`, quick approve, approveAll → all call coordinator
- **Modify**: `ReviewViewModel.kt` — may need minor adjustments

**Critical new guard in this PR**: Fake value blocking.
- If `suggestedAmount == 0.01` → reject unless user explicitly corrected
- If `suggestedMerchant == "Unknown"` → reject unless user explicitly corrected
- If `confidence == 1.0f` but merchant is `"Unknown"` → reject (this is a parser failure, not real confidence)

**Preserve**:
- `PENDING → PROCESSING → APPROVED` status transition
- Source stats updates
- Raw notification relevance
- Scanned receipt link
- User correction insert
- Classifier retraining
- Merchant-category learning

**Tests**:
- Approval creates expense through coordinator
- Duplicate detection works
- Already-processed review cannot re-create
- Synthetic `0.01` amount rejected (without user edit)
- `"Unknown"` merchant rejected (without user edit)
- Source stats still update correctly
- Receipt link still works

**Done when**: `ReviewQueueRepository` no longer calls `expenseDao.insertAtomic()` for approval.

---

### PR 4 — Notification Auto-Accept Migration

*(Template PR 6)*

**Purpose**: Move notification auto-accept into coordinator.

**Files**:
- **Modify**: `NotificationProcessingPipeline.kt` — `handleAutoAcceptInTransaction()` builds `CreateExpenseRequest`, calls coordinator
- **Modify**: `NotificationRepository.kt` — may need minor adjustments

**Important**: Keep raw notification insertion and routing logic outside the coordinator. Only the final Expense insert goes through it. Avoid nesting DB transactions.

**Preserve**:
- Raw notification dedup
- Confidence routing (AUTO_ACCEPT / NEEDS_REVIEW / AUTO_REJECT)
- Amount overflow (parsed > 1_000_000 → downgrade to NEEDS_REVIEW)
- Pending duplicate check
- Source stats
- Raw notification relevance
- Classifier behavior
- Subscription detection (side effect)

**Tests**:
- Auto-accepted notification creates expense through coordinator
- Duplicate detection works
- Low-confidence goes to review (not auto-accepted)
- Amount overflow goes to review
- `"Unknown"` merchant does NOT get auto-accepted (goes to review)
- Source stats match old behavior

**Done when**: Notification expense path no longer directly calls `expenseDao.insertAtomic()`.

---

### PR 5 — Receipt Path Migration

*(Template PR 7)*

**Purpose**: Move user-confirmed receipt expense creation into coordinator.

**Files**:
- **Modify**: `ReceiptRepository.kt` — `createExpenseFromReceipt()` builds `CreateExpenseRequest`, calls coordinator
- **Modify**: `ReceiptScanViewModel.kt` — may need minor adjustments

**New validation for this path**:
- Amount must be > 0 and <= 1_000_000
- Merchant must not be `"Parsing Failed"` or `"Unknown Merchant"`
- Currency must be explicit
- Failed OCR parses must NOT create real expenses — they should create pending reviews or error states

**Preserve**:
- OCR scan persistence
- Scanned receipt link (done in coordinator via `scannedReceiptId` in request)
- Category classification
- Hybrid classifier learning
- Warranty extraction (fire-and-forget, does not block creation)

**Tests**:
- Receipt save creates expense through coordinator
- Duplicate detection works
- Scanned receipt linked correctly
- Invalid amount rejected
- `"Parsing Failed"` merchant rejected
- Missing currency handled

**Done when**: Receipt expense creation no longer directly calls `expenseDao.insertAtomic()`.

---

### PR 6 — CSV Import & Update Lifecycle

*(Merges template PRs 8 + 12 — the two "repair" PRs)*

**Purpose**: Fix the most dangerous creation path AND centralize updates.

#### Part A: CSV Import

**Current crime scene** (from audit):
- Calls bare `expenseDao.insert()` (NOT `insertAtomic`)
- No dedup key generated
- No merchant key normalized
- No dedup check
- No currency explicitly set (relies on entity default `"EUR"`)
- No lifecycle event written
- No side effects

**New behavior**:
- Each CSV row parsed into a `CreateExpenseRequest`
- CSV uses `DeduplicationMode.BULK_IMPORT`
- CSV result includes per-row `CreateExpenseResult`
- Currency: from CSV column if present → home currency → row is rejected (no silent `"EUR"`)
- `CsvExpenseImporter` **injected** with the coordinator (no direct DAO access)

**Files**:
- **Modify**: `CsvExpenseImporter.kt` — calls coordinator per row, returns `BatchCreateResult`
- **Modify**: `DebugViewModel.kt` — updates for new importer signature
- **Modify**: `DebugScreen.kt` — may need minor adjustments

#### Part B: Update Lifecycle

**Purpose**: Add coordinator update methods and eliminate direct DAO update bypasses.

**New coordinator methods**:
```kotlin
suspend fun updateExpenseCategory(expenseId: Long, newCategoryId: Long, actor: String?): Result<Unit>
suspend fun updateExpenseMerchant(expenseId: Long, newMerchant: String, actor: String?): Result<Unit>
suspend fun updateExpenseType(expenseId: Long, newType: TransactionType, actor: String?): Result<Unit>
suspend fun updateTransferDetails(expenseId: Long, direction: TransferDirection?, accountName: String?, actor: String?): Result<Unit>
suspend fun updateOwnership(expenseId: Long, isNotMine: Boolean, isSharedExpense: Boolean, ...): Result<Unit>
suspend fun updateExpenseLocation(expenseId: Long, lat: Double?, lon: Double?, source: String?, placeId: String?, address: String?): Result<Unit>
```

**Critical fix**: Remove `MainActivity.applyVisualSplitToExpense()` direct DAO access.
- Replace the `load → mutate → insertAll(REPLACE)` pattern with: ViewModel calls use case → use case calls coordinator → coordinator runs safe DAO update.
- This is a **high-risk change** — the REPLACE strategy means it currently just overwrites. Must verify the split visualization still works without data loss.

**Dedupe key recomputation**: When merchant, amount, date, currency, or transaction type changes, recompute the dedupe key and run a duplicate check before committing.

**Tests**:
- CSV duplicate detection works
- CSV merchant key generated
- CSV explicit currency required
- CSV invalid rows produce row-level errors
- Update writes lifecycle event
- Merchant update recomputes dedupe key
- Type update recomputes dedupe key
- `MainActivity` no longer touches `ExpenseDao` directly

**Done when**: CSV no longer bypasses lifecycle. No production UI layer directly writes to `ExpenseDao`.

---

### PR 7 — Delete & Email Receipt Migration

*(Merges template PRs 9 + 13)*

#### Part A: Email Receipt

**Purpose**: Add missing dedup and side effects to email-created expenses.

**File**: `EmailReceiptIngestionService.kt`

- Keep email-specific dedup (messageId UNIQUE, fingerprint dedup)
- ADD range-based `isDuplicateCurrencyAware` check
- ADD standard `dedupeKey` generation
- ADD lifecycle event writing
- ADD budget/anomaly side effects

#### Part B: Delete Lifecycle

**Purpose**: Make deletes auditable.

**New behavior**:
1. Load current expense
2. Write `DELETED` lifecycle event with before-snapshot JSON
3. Delete expense
4. Post-delete: update source stats, invalidate caches

**Soft-delete decision**: **NOT in Phase 3.** Keep hard delete + snapshot in event. Soft delete requires updating every query to filter deleted rows — too large for this phase.

**Files**:
- **Modify**: `ExpenseRepository.kt` — `deleteExpense()` writes event
- **Modify**: `NotificationRepository.kt` — `deleteAll()` → goes through coordinator (or marked as DEBUG)
- **Modify**: Debug snapshot restore — marked as `DEBUG_TOOL` events

**Tests**:
- Email duplicate by range skipped
- Email lifecycle event written
- Delete writes lifecycle event
- Delete removes expense
- Debug restore marked as debug event

**Done when**: Email creation is lifecycle-compliant. Deletion is auditable.

---

### PR 8 — Group/Shared Expense Migration

*(Template PR 10)*

**Purpose**: Fix missing dedup and missing side effects in group expense creation.

**Current problem** (from audit):
- Uses `insertAtomic` but does NOT call `isDuplicateCurrencyAware`
- No budget monitoring
- No anomaly alert
- Writes group link outside the expense insert transaction

**New behavior**:
- Group validation stays in `GroupTransactionCoordinator` (group exists, active, payer is member, split valid)
- Expense creation calls coordinator with `source = GROUP_EXPENSE`
- Coordinator handles: amount validation, currency, merchant normalization, dedup, source event, side effects
- Group link (`GroupExpense`) is inserted inside the same DB transaction as the expense

**Atomicity requirement**: Expense insert + GroupExpense link must be in one transaction. Solve by either:
1. Including `groupExpenseLinkInfo` in `CreateExpenseRequest` and having the coordinator insert both, or
2. Having the coordinator expose a `@Transaction` method that accepts both expense + link info.

**Files**:
- **Modify**: `GroupTransactionCoordinator.kt` — calls coordinator for expense creation
- **Modify**: `AddGroupExpenseUseCase.kt` — adjusts to new flow
- **Modify**: `SharedExpenseGroupsViewModel.kt` — may need minor adjustments

**Tests**:
- Group expense and group link created atomically
- Duplicate group expense skipped
- Budget/anomaly side effects fire
- Group default currency passed explicitly
- Invalid split fails before coordinator call

**Done when**: Group expenses get standard lifecycle (dedup + side effects). No direct DAO insert.

---

### PR 9 — Bank API Lifecycle Path

*(Template PR 11)*

**Purpose**: Prepare bank API creation path. Even if the current API is stubbed, define the lifecycle.

**Files**:
- Bank API integration files (if they exist) — or create a stub coordinator method

**Required design**:
- Source: `BANK_API_SYNC`
- External transaction ID → `idempotencyKey`
- Bank-provided currency is explicit
- `DeduplicationMode.STRICT_EXTERNAL_ID` → check external ID first, then standard range check

**Tests**: Bank transaction request maps to lifecycle request. Same external ID cannot insert twice.

**Done when**: Future bank sync has a defined lifecycle path through the coordinator. No live bank API needed.

---

### PR 10 — Side Effect Consolidation

*(Template PR 15)*

**Purpose**: All post-creation side effects routed through `TransactionSideEffectDispatcher`.

**Create**: `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt`

```kotlin
class TransactionSideEffectDispatcher @Inject constructor(
    private val budgetMonitor: BudgetMonitor,
    private val anomalyAlertOrchestrator: AnomalyAlertOrchestrator,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val classifier: TransactionClassifier,
    private val hybridClassifier: HybridExpenseClassifier,
    private val confidenceRouter: ConfidenceRouter,
    private val sourceStatsDao: SourceStatsDao,
    private val rawNotificationDao: RawNotificationDao,
    private val scannedReceiptDao: ScannedReceiptDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val recommendationGenerator: RecommendationGenerator,
    private val subscriptionDetector: SubscriptionDetector,
    private val transferAnalytics: TransferDirectionAnalytics,
    private val merchantNormalizer: MerchantNormalizer,
    private val timeProvider: TimeProvider,
    private val appScope: CoroutineScope
) {
    suspend fun dispatchCreateSideEffects(
        expenseId: Long,
        request: CreateExpenseRequest,
        policy: SideEffectPolicy
    ) { /* ... */ }

    suspend fun dispatchUpdateSideEffects(
        expenseId: Long,
        updates: ExpenseUpdates
    ) { /* ... */ }

    suspend fun dispatchDeleteSideEffects(
        expenseId: Long,
        beforeSnapshot: Expense
    ) { /* ... */ }
}
```

**Default side-effect matrix** (from template Section 7.3, confirmed correct):

| Side Effect | Manual | Notification | Review | Receipt | CSV | Email | Group |
|---|---|---|---|---|---|---|---|
| Budget monitor | ✅ | ✅ | ✅ | ✅ | batch | ✅ | ✅ |
| Anomaly alert | ✅ | ✅ | ✅ | ✅ | batch | ✅ | ✅ |
| Merchant-category learning | ✅ | — | ✅ | ✅ | — | ✅ | — |
| Classifier training | — | ✅ | ✅ | — | — | — | — |
| AI Recommendation gen | ✅ | ✅ | — | — | — | — | — |
| Subscription detection | — | ✅ | — | — | — | — | — |
| Source stats update | — | ✅ | ✅ | — | — | — | — |
| Raw notification relevance | — | ✅ | ✅ | — | — | — | — |
| Scanned receipt link | — | — | ✅ | ✅ | — | — | — |
| Confidence cache invalidate | — | ✅ | ✅ | — | — | — | — |
| User correction | — | — | ✅ | — | — | — | — |
| Recurring rule creation | ✅ | — | — | — | — | — | — |
| Warranty extraction | — | — | — | ✅ | — | — | — |

**Rules**:
- Side effects are always POST-COMMIT (after DB transaction succeeds)
- Side-effect failures must NOT roll back the already-committed expense
- Side-effect failures write a `SIDE_EFFECT_FAILED` lifecycle event
- Batch CSV import uses summarized side effects (no per-row alert spam)
- Debug restore has NO side effects

**Files modified**: `TransactionLifecycleCoordinator` now calls dispatcher. Source-specific repositories no longer run side effects independently.

**Done when**: Side effects are consistently dispatched through one place. Differences across sources are intentional (by `SourcePolicy`), not accidental (due to different code paths).

---

### PR 11 — Placeholder/Fake Value Cleanup

*(Template PR 14 — moved earlier for safety)*

**Purpose**: Eliminate fake parser values from real transaction flows.

**Rules enforced**:
- `0.01` fallback amount: May exist only as UI hint in pending review. Coordinator **rejects** approval until user edits amount.
- `"Unknown"` / `"Unknown Merchant"`: May exist only in pending review. Coordinator **rejects** real expense creation with these values.
- `"Parsing Failed"`: Never allowed in real expenses. Must create review/error state instead.
- `confidence = 1.0f` fallback: Do not assign perfect confidence to parser failures. Use `0f` or explicit `parseStatus` field.
- Hardcoded `"EUR"`: No production creation path may use it as a silent default. Must use: parsed currency → user/home currency → group default currency → explicit import currency → **validation failure** (not silent default).

**Files**:
- `ReviewQueueRepository.kt`: Remove/adjust fallback value construction
- `ReceiptRepository.kt`: Remove/adjust fallback value construction
- `NotificationProcessingPipeline.kt`: Adjust `"Unknown"` merchant handling
- `ProcessReceiptUseCase.kt`: Adjust `"Unknown"` fallback
- `WarrantyTrackerRepository.kt`: Adjust `"Unknown Product"` fallback
- Entity defaults: `Expense.kt` `currency = "EUR"` default may remain for Room schema compatibility BUT the coordinator must never rely on it

**Note on entity defaults**: The `Expense.kt` `currency: String = "EUR"` default cannot be removed without a DB migration to change the column default. For Phase 3, keep the entity default but ensure the coordinator ALWAYS sets currency explicitly before insert. The entity default becomes a safety net, not an active fallback.

**Tests**:
- Fallback `0.01` amount rejected by coordinator
- `"Unknown"` merchant rejected by coordinator
- `"Parsing Failed"` merchant rejected by coordinator
- Missing currency fails validation (does not silently use `"EUR"`)
- Parser failure confidence is NOT `1.0f`

**Done when**: Zero fake values can enter the real `Expense` table through the coordinator. Existing pending reviews may still contain fake values as UI hints, but they cannot be approved without correction.

---

### PR 12 — Direct DAO Guardrails & Final Audit

*(Template PR 16 + remaining cleanup)*

**Purpose**: Prevent regression and verify completeness.

#### Part A: CI/Audit Guardrail

Add a lint check or build script that flags these calls outside approved files:

| Banned call | Approved files |
|---|---|
| `expenseDao.insert(` | `TransactionLifecycleCoordinator`, `ExpenseDao` (self), tests, migrations |
| `expenseDao.insertAtomic(` | `TransactionLifecycleCoordinator` only |
| `expenseDao.insertAll(` | `TransactionLifecycleCoordinator` (debug restore), tests |
| `expenseDao.update*` | `TransactionLifecycleCoordinator`, `ExpenseDao` (self) |
| `expenseDao.delete*` | `TransactionLifecycleCoordinator`, `ExpenseDao` (self) |
| Hardcoded `"EUR"` in creation paths | None (must be explicit) |
| `0.01` as fallback amount in creation | None |
| `"Unknown"` / `"Parsing Failed"` as real merchant | None |

Implementation: Add a Kotlin script (e.g., `scripts/guardrails/dao-access-check.main.kts`) or a custom lint rule.

#### Part B: Final audit pass

1. Run full grep for `expenseDao.insert` across all production code — verify only coordinator uses it.
2. Run full grep for hardcoded `"EUR"` in creation paths — verify only used with explicit intent.
3. Verify all 8 creation paths route through coordinator.
4. Verify `MainActivity` has zero `ExpenseDao` references.
5. Update `transaction-lifecycle-audit.md` to reflect the new state.

**Done when**: Guardrail script runs successfully. Zero direct DAO bypasses in production code.

---

## 6. Consolidated PR Order Summary

| PR | Title | ~Template PRs | Risk | Blast Radius |
|---|---|---|---|---|
| 0 | Baseline & Branch Protection | 0 | None | None |
| **1** | **Models, DB, Coordinator Skeleton** | 1+2+3 | Medium | None (unused) |
| **2** | Manual Entry Migration | 4 | Low | Manual entry UI |
| **3** | Pending Review Approval Migration | 5 | Medium | Review screen |
| **4** | Notification Auto-Accept Migration | 6 | High | Background notifications |
| **5** | Receipt Path Migration | 7 | Medium | Receipt scanning |
| **6** | CSV Import + Update Lifecycle | 8+12 | **Highest** | CSV import, all edits, MainActivity |
| **7** | Delete + Email Receipt | 9+13 | Medium | Delete, email ingestion |
| **8** | Group/Shared Expense | 10 | Medium | Group expenses |
| **9** | Bank API Lifecycle | 11 | Low | Stub only |
| **10** | Side Effect Consolidation | 15 | Medium | All paths |
| **11** | Placeholder/Fake Value Cleanup | 14 | Low | Parser/review code |
| **12** | Direct DAO Guardrails & Audit | 16 | Low | Build pipeline |

**Risk-ordered rationalization**: Manual entry (PR 2) first because it's the simplest, most-tested, and least risky. Then review (PR 3) and notification (PR 4) — both well-tested. CSV import (PR 6) is the most dangerous cleanup, done after the coordinator is proven on simpler paths. MainActivity fix is bundled with PR 6 because the visual split update shares update lifecycle concerns.

---

## 7. Risk Assessment

### 🔴 Critical Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Notification pipeline broken** by coordinator migration | Auto-expense creation stops working for ALL notifications | Keep old code path as fallback behind a feature flag (boolean config); test extensively with real notification capture |
| **CSV import creates data inconsistency** during migration | Corrupted expenses with null dedupeKey, missing merchantKey | Run CSV import with coordinator first in integration tests; validate every field post-import |
| **MainActivity REPLACE insert removed** but split visualization still works | Broken visual split feature | Before removing, verify the update path produces identical output to the old replace path |
| **Pending review fake-value blocking** breaks existing reviews | Users can't approve legitimately ambiguous pending reviews | Only block when values are clearly synthetic (`0.01`, `"Unknown"`, `confidence=1.0f` with `"Unknown"` merchant). Allow borderline cases. |

### 🟡 High Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **DB migration corrupts data** | Loss of existing expenses | Test migration on copies of real DBs. Use backup-first approach. |
| **Side-effect dispatch failure** creates expense but doesn't fire budget alert | User misses budget warnings | Side effects are post-commit (no rollback risk). Write `SIDE_EFFECT_FAILED` events so failures are visible. |
| **`dedupeKey` recomputation on update** creates false unique-key conflicts | Legitimate edit fails because dedupeKey collides | Only recompute when fields that affect dedup change (merchant, amount, date, currency, type). Run duplicate check before committing. |
| **Group expense link atomicity** broken | Expense created but not linked to group (orphaned expense) | Wrap both inserts in `@Transaction`. If either fails, rollback both. |

### 🟡 Medium Risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Circular dependency** in DI: Coordinator injects everything | DI graph fails to build | Use Dagger/Hilt `@Singleton` + constructor injection. Coordinator depends on DAOs and services; no service should depend on coordinator. |
| **Performance regression** from always running range duplicate check | Slower expense creation (extra DB query) | The check is already done by most paths. For CSV, batch the checks or use `BULK_IMPORT` mode that optimizes. |
| **16→12 PRs** consolidation causes merge conflicts | Harder to review, potential for conflicts | Each PR is still focused on one concern. Merge in strict order. |

---

## 8. Dependencies

### Must Complete Before Phase 3 Starts

- ✅ **Phase 1 (Money/Currency)**: `CurrencyCode`, `MoneyAmount`, `SupportedCurrency`, `ExchangeRateStore` — all used by the coordinator for currency validation.
- ✅ **Phase 2 (Time/Period Semantics)**: `TimeProvider`, `PeriodRange`, `PeriodKind` — used by coordinator for date validation and `nowMs()`.

### Within Phase 3

```
PR 0 (baseline)
  └── PR 1 (models, DB, coordinator skeleton) ──────────────┐
       ├── PR 2 (manual entry migration) ───────────────────┤
       │    ├── PR 3 (review approval migration) ───────────┤
       │    │    ├── PR 4 (notification migration) ─────────┤
       │    │    │    ├── PR 5 (receipt migration) ─────────┤
       │    │    │    │    ├── PR 6 (CSV + update lifecycle) ┤
       │    │    │    │    │    ├── PR 7 (delete + email) ───┤
       │    │    │    │    │    │    ├── PR 8 (group) ───────┤
       │    │    │    │    │    │    │    ├── PR 9 (bank) ───┤
       │    │    │    │    │    │    │    │    ├── PR 10 (side effects)
       │    │    │    │    │    │    │    │    │    ├── PR 11 (fake cleanup)
       │    │    │    │    │    │    │    │    │    │    └── PR 12 (guardrails)
```

PRs 2-6 are strictly sequential (each migrates a path). PR 9 (bank stub) can be done anytime after PR 1. PRs 10 (side effects) and 11 (fake cleanup) depend on the coordinator existing (PR 1) but could be done in parallel with path migrations — however, they're ordered late because side-effect consolidation is easier after all paths are migrated and fake-value guards should be enforced from the start (they're in coordinator validation from PR 1).

---

## 9. Testing Strategy

### 9.1 Coordinator Unit Tests (PR 1, expanded per PR)

| Test Category | Count | Examples |
|---|---|---|
| Amount validation | 6 | `> 0`, `<= 0`, `> 1_000_000`, `NaN`, `Infinity`, `0.01` rejected |
| Currency validation | 4 | Valid ISO, blank, invalid, null |
| Merchant validation | 5 | Normal, blank, `"Unknown"`, `"Parsing Failed"`, `"Unknown Merchant"` |
| Date validation | 3 | Past, future (rejected), future (allowed with policy) |
| Transfer validation | 3 | TRANSFER with direction+account, missing direction, missing account |
| Ownership validation | 4 | Conflicting flags, valid shared, valid not-mine, both null |
| Dedup | 4 | No duplicate, exact duplicate, range duplicate, insert conflict |
| Events | 4 | Created event, validation failed event, duplicate event, insert conflict event |
| **Total** | **~29** | |

### 9.2 Integration Tests by Path

| Path | Test Cases |
|---|---|
| Manual entry (PR 2) | Create, duplicate, recurring, transfer, ownership, side effects fire |
| Review approval (PR 3) | Approve, approve with edits, quick approve, synthetic amount blocked, unknown merchant blocked, duplicate, source stats |
| Notification (PR 4) | Auto-accept, duplicate, low-confidence→review, overflow→review, unknown merchant→review, source stats |
| Receipt (PR 5) | Save confirmed, duplicate, parse-failed blocked, missing currency, scanned receipt linked |
| CSV import (PR 6) | Import, duplicate row, invalid row, currency from column, currency from home, per-row results |
| Email receipt (PR 7) | Create, messageId dedup, range dedup, lifecycle event |
| Group expense (PR 8) | Create with link, duplicate, budget/anomaly fire, atomic link |
| Bank API (PR 9) | Map to request, idempotency key, same ID blocked |

### 9.3 Regression Tests (from audit gaps)

1. CSV import of same row twice → one expense + one duplicate result ✅
2. CSV expense has `merchantKey` and `dedupeKey` ✅
3. CSV expense has explicit `currency` ✅
4. Group expense duplicate is skipped ✅
5. Group expense triggers budget/anomaly ✅
6. Email receipt duplicate by range skipped ✅
7. Pending review with `0.01` cannot approve unchanged ✅
8. Pending review with `"Unknown"` merchant cannot approve unchanged ✅
9. Receipt parse failure cannot create `"Parsing Failed"` expense ✅
10. `MainActivity` has no `ExpenseDao` references ✅
11. Updating merchant recomputes `dedupeKey` ✅
12. Updating type recomputes `dedupeKey` ✅
13. Delete writes lifecycle event ✅
14. Direct DAO insert audit finds no production bypasses ✅

---

## 10. Acceptance Criteria

Phase 3 is **DONE** when ALL of the following are true:

- [ ] **AC-1**: `TransactionLifecycleCoordinator` is the single entry point for all expense creation.
- [ ] **AC-2**: Zero production code calls `expenseDao.insert()`, `insertAtomic()`, or `insertAll()` outside the coordinator (excluding debug/restore paths with explicit approval).
- [ ] **AC-3**: CSV import uses deduplication, merchant normalization, explicit currency, and lifecycle events.
- [ ] **AC-4**: Group/shared expenses use standard deduplication and post-create side effects.
- [ ] **AC-5**: Email receipt expenses use standard lifecycle deduplication.
- [ ] **AC-6**: All 8 creation paths use the same validation contract.
- [ ] **AC-7**: Every real `Expense` row has a `source` value.
- [ ] **AC-8**: Every create/update/delete writes a `TransactionEvent`.
- [ ] **AC-9**: Placeholder values (`0.01`, `"Unknown"`, `"Parsing Failed"`, `confidence=1.0f` fallback) cannot produce real expenses without user correction.
- [ ] **AC-10**: `MainActivity` has zero direct `ExpenseDao` references.
- [ ] **AC-11**: Dedupe key generation is centralized (called only from coordinator).
- [ ] **AC-12**: Range duplicate check (`isDuplicateCurrencyAware`) is applied to ALL paths.
- [ ] **AC-13**: Side effects are dispatched from a single `TransactionSideEffectDispatcher` per source policy.
- [ ] **AC-14**: All 29+ coordinator unit tests pass.
- [ ] **AC-15**: All 14 regression tests pass.
- [ ] **AC-16**: CI guardrail flags any new direct DAO bypass.
- [ ] **AC-17**: The `transaction-lifecycle-audit.md` document is updated to reflect the new state.

---

## 11. File Change Summary

### New Files (to create)

| File | PR |
|---|---|
| `domain/transaction/ExpenseSource.kt` | 1 |
| `domain/transaction/lifecycle/LifecycleEventType.kt` | 1 |
| `domain/transaction/lifecycle/CreateExpenseRequest.kt` | 1 |
| `domain/transaction/lifecycle/CreateExpenseResult.kt` | 1 |
| `domain/transaction/lifecycle/ExpenseUpdates.kt` | 1 |
| `domain/transaction/lifecycle/DeduplicationMode.kt` | 1 |
| `domain/transaction/lifecycle/DateValidationPolicy.kt` | 1 |
| `domain/transaction/lifecycle/SideEffectPolicy.kt` | 1 |
| `domain/transaction/lifecycle/ValidationError.kt` | 1 |
| `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` | 1 |
| `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt` | 10 |
| `data/database/entity/TransactionEvent.kt` | 1 |
| `data/database/dao/TransactionEventDao.kt` | 1 |
| `scripts/guardrails/dao-access-check.main.kts` | 12 |
| `docs/admin/phase3-baseline-failures.md` | 0 |

### Modified Files (by PR)

#### PR 1
- `data/database/entity/Expense.kt` — add `source` field
- `data/database/AppDatabase.kt` — bump version to 95, add migration 94→95, register `TransactionEvent` entity, add `transactionEventDao()`

#### PR 2
- `data/repository/ManualExpenseRepository.kt`
- `ui/screens/addexpense/AddExpenseViewModel.kt`
- `data/repository/ExpenseRepository.kt` — add `createExpense()` delegator

#### PR 3
- `data/repository/ReviewQueueRepository.kt`
- `ui/screens/review/ReviewViewModel.kt`

#### PR 4
- `domain/notification/NotificationProcessingPipeline.kt`
- `data/repository/NotificationRepository.kt`

#### PR 5
- `data/repository/ReceiptRepository.kt`
- `ui/screens/receiptscan/ReceiptScanViewModel.kt`

#### PR 6
- `data/importer/CsvExpenseImporter.kt`
- `ui/screens/debug/DebugViewModel.kt`
- `ui/screens/debug/DebugScreen.kt`
- `data/repository/ExpenseRepository.kt` — update path consolidation
- `ui/MainActivity.kt` — remove direct DAO access
- `data/database/GroupTransactionCoordinator.kt` — remove direct update calls

#### PR 7
- `data/email/EmailReceiptIngestionService.kt`
- `data/repository/ExpenseRepository.kt` — delete lifecycle

#### PR 8
- `domain/groups/GroupTransactionCoordinator.kt`
- `domain/groups/AddGroupExpenseUseCase.kt`
- `ui/screens/groups/SharedExpenseGroupsViewModel.kt`

#### PR 9
- `data/bank/BankApiIntegration.kt` (or equivalent stub file)

#### PR 10
- `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` — wire dispatcher
- `data/repository/ManualExpenseRepository.kt` — remove direct side effects
- `data/repository/ReviewQueueRepository.kt` — remove direct side effects
- `domain/notification/NotificationProcessingPipeline.kt` — remove direct side effects
- `data/repository/ReceiptRepository.kt` — remove direct side effects
- `data/email/EmailReceiptIngestionService.kt` — remove direct side effects
- `domain/groups/GroupTransactionCoordinator.kt` — remove direct side effects

#### PR 11
- `data/repository/ReviewQueueRepository.kt` — adjust fallback values
- `data/repository/ReceiptRepository.kt` — adjust fallback values
- `domain/notification/NotificationProcessingPipeline.kt` — adjust merchant placeholder
- `domain/receipt/ProcessReceiptUseCase.kt` — adjust fallback
- `domain/warranty/WarrantyTrackerRepository.kt` — adjust product placeholder

#### PR 12
- `scripts/guardrails/dao-access-check.main.kts` (new)
- `docs/analyses and debug master/transaction-lifecycle-audit.md` — update
- `build.gradle.kts` or CI config — wire guardrail

---

## 12. Rollback Strategy

If Phase 3 causes critical regressions:

1. **Feature flag**: Each path migration can be wrapped in a boolean config (`TransactionLifecycleConfig.useCoordinatorForManualEntry`, etc.) that flips between old direct-DAO path and new coordinator path. This allows per-path rollback.
2. **DB migration**: The `source` column is nullable. The `transaction_events` table is new. Both can exist without the coordinator being active. No downgrade migration needed unless events table must be removed.
3. **Git revert**: Each PR is independent enough to revert individually (except PR 1 which all others depend on).

---

*End of Phase 3 Implementation Plan*

@orchestrator The Advanced Technical Plan is ready. Please begin execution of Batch 1.
