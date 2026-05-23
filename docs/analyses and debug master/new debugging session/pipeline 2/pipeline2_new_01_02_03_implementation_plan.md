# Pipeline 2 implementation plan — P2-NEW-01, P2-NEW-02, P2-NEW-03

Target baseline: commit `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Issues covered:

| ID | Severity | Issue |
|---|---:|---|
| P2-NEW-01 | P1 | Full-row `updateExpense()` lacks create-equivalent validation |
| P2-NEW-02 | P1/P2 | `TransactionLifecycleCoordinator` injects `writeBarrier` but still uses `restoreMaintenanceMode` directly |
| P2-NEW-03 | P2 | Restore-blocked create has no durable diagnostic |

Mode: static code-review-based plan. Implement exactly this scope; do not mix dedupe, receipt, group, source-link, or SideEffectMode cleanup into this PR set unless required for compilation.

---

# Current evidence

## P2-NEW-01

`TransactionLifecycleCoordinator.createExpense()` validates create requests through a private `validate(request)` method. Current validation covers:

- amount positive/finite and max amount,
- merchant nonblank / not placeholder,
- currency format,
- date positive and not too far in future,
- transfer metadata for `TRANSFER`,
- ownership conflict,
- latitude/longitude pair.

`updateExpense(expense)` does **not** reuse that validation. It:

1. checks restore mode directly,
2. loads existing row,
3. recomputes dedupe/merchant key if key fields changed,
4. recomputes conversion snapshot,
5. writes `expenseDao.update(finalExpense)`,
6. writes `UPDATED`.

This allows a full-row update to persist a final state that create would reject.

## P2-NEW-02

`TransactionLifecycleCoordinator` has a TODO saying `writeBarrier` is injected but direct `restoreMaintenanceMode.isWritesAllowed()` checks remain. Current code uses direct checks in create/update methods.

`DatabaseWriteBarrier.checkWritesAllowed(...)` already exists and throws structured `DatabaseAccessBlockedException` when mode is not `NORMAL`.

## P2-NEW-03

When create is blocked by restore mode, current code logs with Timber and returns:

```kotlin
CreateExpenseResult.Error(IllegalStateException("Database writes blocked during restore"))
```

No durable `PipelineDiagnosticEvent` is emitted.

Diagnostics infrastructure exists:

- `DiagnosticEventWriter`
- `DiagnosticEvent`
- `AppPipeline`
- `EventOutcome.BLOCKED`
- `EventSeverity.WARNING`
- `DiagnosticReasonCode.RESTORE_BLOCKED`
- `DiagnosticReasonCode.WRITE_BARRIER_DENIED`
- `SafeEventMetadata`

---

# Recommended PR slicing

Implement as **three PRs**, in this order:

1. **PR A — Shared transaction validation for create/update**
   - Fixes P2-NEW-01.

2. **PR B — Coordinator write-barrier normalization**
   - Fixes P2-NEW-02.

3. **PR C — Restore-blocked create diagnostics**
   - Fixes P2-NEW-03.

If you want one PR, keep commits separated by these sections.

---

# PR A — Shared transaction validation for create/update

## Goal

No update path may persist an `Expense` final state that create would reject.

`createExpense()` and `updateExpense()` must share one validator.

---

## Files to modify

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
```

New files:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionValidator.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionValidationError.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/validation/TransactionValidationException.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/validation/TransactionValidatorTest.kt
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorUpdateValidationTest.kt
```

Use existing test package/style if different.

---

## Step A1 — Add validation error model

Create:

```kotlin
package com.yourname.expensetracker.domain.transaction.validation

data class TransactionValidationError(
    val code: String,
    val message: String,
    val field: String? = null
)
```

Create:

```kotlin
package com.yourname.expensetracker.domain.transaction.validation

class TransactionValidationException(
    val errors: List<TransactionValidationError>
) : IllegalArgumentException(
    errors.joinToString("; ") { it.message }
)
```

Reason:

- `CreateExpenseResult.ValidationFailed` can still return `List<String>`.
- Update paths can throw a structured exception without changing all callsites immediately.

---

## Step A2 — Add `TransactionValidator`

Create:

```kotlin
package com.yourname.expensetracker.domain.transaction.validation

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionValidator @Inject constructor(
    private val timeProvider: TimeProvider
) {
    fun validateCreate(request: CreateExpenseRequest): List<TransactionValidationError> {
        return validateFields(
            amount = request.amount,
            merchant = request.merchant,
            currency = request.currency,
            date = request.date,
            transactionType = request.transactionType,
            transferDirectionPresent = request.transferDirection != null,
            transferAccountName = request.transferAccountName,
            isNotMine = request.isNotMine,
            isSharedExpense = request.isSharedExpense,
            latitude = request.latitude,
            longitude = request.longitude
        )
    }

    fun validateFinalExpenseState(expense: Expense): List<TransactionValidationError> {
        return validateFields(
            amount = expense.amount,
            merchant = expense.merchant,
            currency = expense.currency,
            date = expense.date,
            transactionType = expense.transactionType,
            transferDirectionPresent = expense.transferDirection != null,
            transferAccountName = expense.transferAccountName,
            isNotMine = expense.isNotMine,
            isSharedExpense = expense.isSharedExpense,
            latitude = expense.latitude,
            longitude = expense.longitude
        )
    }

    private fun validateFields(
        amount: Double,
        merchant: String,
        currency: String,
        date: Long,
        transactionType: TransactionType,
        transferDirectionPresent: Boolean,
        transferAccountName: String?,
        isNotMine: Boolean,
        isSharedExpense: Boolean,
        latitude: Double?,
        longitude: Double?
    ): List<TransactionValidationError> {
        val errors = mutableListOf<TransactionValidationError>()
        val now = timeProvider.now()

        if (!amount.isFinite() || amount <= 0) {
            errors += TransactionValidationError(
                code = "AMOUNT_NOT_POSITIVE_FINITE",
                field = "amount",
                message = "Amount must be positive and finite"
            )
        }

        if (amount > 1_000_000) {
            errors += TransactionValidationError(
                code = "AMOUNT_EXCEEDS_MAX",
                field = "amount",
                message = "Amount exceeds maximum (1,000,000)"
            )
        }

        if (merchant.isBlank()) {
            errors += TransactionValidationError(
                code = "MERCHANT_BLANK",
                field = "merchant",
                message = "Merchant cannot be blank"
            )
        }

        if (merchant == "Unknown" || merchant == "Parsing Failed") {
            errors += TransactionValidationError(
                code = "MERCHANT_PLACEHOLDER",
                field = "merchant",
                message = "Merchant placeholder not allowed for real expenses"
            )
        }

        if (currency.isBlank()) {
            errors += TransactionValidationError(
                code = "CURRENCY_REQUIRED",
                field = "currency",
                message = "Currency is required"
            )
        } else if (!CURRENCY_ISO_PATTERN.matches(currency)) {
            errors += TransactionValidationError(
                code = "CURRENCY_INVALID_ISO",
                field = "currency",
                message = "Currency must be a 3-letter ISO code (e.g. EUR, USD)"
            )
        }

        if (date <= 0) {
            errors += TransactionValidationError(
                code = "DATE_NOT_POSITIVE",
                field = "date",
                message = "Date must be positive"
            )
        }

        if (date > TimePeriodUtils.addDays(now, 1)) {
            errors += TransactionValidationError(
                code = "DATE_IN_FUTURE",
                field = "date",
                message = "Date cannot be in the future"
            )
        }

        if (transactionType == TransactionType.TRANSFER) {
            if (!transferDirectionPresent) {
                errors += TransactionValidationError(
                    code = "TRANSFER_DIRECTION_REQUIRED",
                    field = "transferDirection",
                    message = "Transfer direction is required for TRANSFER transactions"
                )
            }

            if (transferAccountName.isNullOrBlank()) {
                errors += TransactionValidationError(
                    code = "TRANSFER_ACCOUNT_REQUIRED",
                    field = "transferAccountName",
                    message = "Transfer account name is required for TRANSFER transactions"
                )
            }
        }

        if (isNotMine && isSharedExpense) {
            errors += TransactionValidationError(
                code = "OWNERSHIP_CONFLICT",
                field = "ownership",
                message = "Cannot be both not-mine and shared"
            )
        }

        if (latitude != null && longitude == null) {
            errors += TransactionValidationError(
                code = "LATITUDE_REQUIRES_LONGITUDE",
                field = "longitude",
                message = "Latitude requires longitude"
            )
        }

        if (longitude != null && latitude == null) {
            errors += TransactionValidationError(
                code = "LONGITUDE_REQUIRES_LATITUDE",
                field = "latitude",
                message = "Longitude requires latitude"
            )
        }

        if (latitude != null && latitude !in -90.0..90.0) {
            errors += TransactionValidationError(
                code = "LATITUDE_OUT_OF_RANGE",
                field = "latitude",
                message = "Latitude out of range"
            )
        }

        if (longitude != null && longitude !in -180.0..180.0) {
            errors += TransactionValidationError(
                code = "LONGITUDE_OUT_OF_RANGE",
                field = "longitude",
                message = "Longitude out of range"
            )
        }

        return errors
    }

    companion object {
        private val CURRENCY_ISO_PATTERN = Regex("^[A-Z]{3}$")
    }
}
```

Notes:

- Keep the same behavior as current create validation first.
- Do not introduce configurable future-date policy in this PR unless trivial.
- Do add latitude/longitude range checks because `updateLocation()` already enforces them and final-state validation should not allow impossible coordinates.

---

## Step A3 — Inject validator into coordinator

Modify constructor:

```kotlin
private val transactionValidator: TransactionValidator
```

Imports:

```kotlin
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidator
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidationException
```

---

## Step A4 — Replace private `validate(request)`

In `TransactionLifecycleCoordinator`, change current private `validate(request): List<String>` to delegate:

```kotlin
private fun validate(request: CreateExpenseRequest): List<String> {
    return transactionValidator.validateCreate(request).map { it.message }
}
```

Important:

- Keep this wrapper temporarily to minimize create-flow diff.
- Remove the old inline validation body and `CURRENCY_ISO_PATTERN` companion if no longer used.

---

## Step A5 — Add update validation lifecycle event type

Modify:

```text
LifecycleEventType.kt
```

Add:

```kotlin
UPDATE_VALIDATION_FAILED
```

No migration needed if event type is stored as string.

---

## Step A6 — Add helper to write update validation failure event

Inside `TransactionLifecycleCoordinator`:

```kotlin
private suspend fun writeUpdateValidationFailedEventBestEffort(
    expenseId: Long,
    source: String,
    reason: String?,
    correlationId: String?,
    errors: List<TransactionValidationError>
) {
    runCatching {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = expenseId,
                eventType = LifecycleEventType.UPDATE_VALIDATION_FAILED.name,
                source = source,
                actor = null,
                occurredAt = timeProvider.now(),
                dedupeKey = null,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = JSONObject().apply {
                    put("operation", "updateExpense")
                    put("errorCount", errors.size)
                    put("errorCodes", errors.joinToString(",") { it.code })
                    put("fields", errors.mapNotNull { it.field }.distinct().joinToString(","))
                }.toString(),
                reason = reason ?: "Update validation failed: ${errors.firstOrNull()?.message}",
                correlationId = correlationId
            )
        )
    }.onFailure {
        Timber.w(it, "Failed to write UPDATE_VALIDATION_FAILED for expense %d", expenseId)
    }
}
```

Privacy rule:

- Do **not** include merchant, notes, receipt text, raw addresses, or raw source payloads in metadata.
- Error codes/field names are safe.

---

## Step A7 — Normalize and validate `updateExpense()`

Current `updateExpense()` writes `finalExpense` without create-equivalent validation.

Modify flow:

1. Check write barrier — PR B will centralize this; for PR A keep existing check if PR B not landed.
2. Load existing.
3. Normalize ownership.
4. Recompute dedupe/merchant key if key fields changed.
5. Recompute conversion snapshot.
6. Validate final row.
7. If invalid:
   - write `UPDATE_VALIDATION_FAILED` best-effort,
   - do not call `expenseDao.update`,
   - throw `TransactionValidationException(errors)`.
8. Only then write `UPDATED`.

Pseudo patch around final state:

```kotlin
val candidateInput = expense.copy(
    id = existing.id,
    createdAt = existing.createdAt
).normalizeOwnership()
```

Use `candidateInput` instead of raw `expense` for key comparison and update.

Then after conversion snapshot:

```kotlin
val validationErrors = transactionValidator.validateFinalExpenseState(finalExpense)
if (validationErrors.isNotEmpty()) {
    writeUpdateValidationFailedEventBestEffort(
        expenseId = expense.id,
        source = source,
        reason = reason,
        correlationId = correlationId,
        errors = validationErrors
    )
    throw TransactionValidationException(validationErrors)
}
```

Important:

- Preserve `createdAt` from existing row.
- Do not mutate DB if validation fails.
- Do not dispatch side effects if validation fails.
- Keep duplicate-collision check after dedupe recomputation.
- If `normalizeOwnership()` resolves `isNotMine && isSharedExpense`, final validation should pass because normalized row is valid.

---

## Step A8 — Validate atomic type/transfer update

`updateTypeAndTransferDetails()` can currently set `newType = TRANSFER` with null transfer fields.

Before writing:

```kotlin
val updated = existing.copy(
    transactionType = newType,
    dedupeKey = newDedupeKey,
    transferDirection = transferDirection,
    transferAccountName = transferAccountName
).normalizeOwnership()

val errors = transactionValidator.validateFinalExpenseState(updated)
if (errors.isNotEmpty()) {
    writeUpdateValidationFailedEventBestEffort(
        expenseId = expenseId,
        source = source,
        reason = "Type/transfer update validation failed",
        correlationId = null,
        errors = errors
    )
    throw TransactionValidationException(errors)
}
```

Then proceed.

---

## Step A9 — Validate transfer-only update when existing type is TRANSFER

`updateTransferDetails()` should validate final row too:

```kotlin
val updated = existing.copy(
    transferDirection = transferDirection,
    transferAccountName = transferAccountName
).normalizeOwnership()

val errors = transactionValidator.validateFinalExpenseState(updated)
if (errors.isNotEmpty()) {
    writeUpdateValidationFailedEventBestEffort(...)
    throw TransactionValidationException(errors)
}
```

This prevents clearing transfer metadata on an existing `TRANSFER`.

---

## Step A10 — Optional: validate merchant/type/category wrappers where final row can become invalid

Minimum required for P2-NEW-01:

- `updateExpense()`
- `updateTypeAndTransferDetails()`
- `updateTransferDetails()`

Optional but recommended if quick:

- `updateMerchant(...)` should reject blank/placeholder merchant.
- `updateLocation(...)` already checks range but should use validator to ensure final row is valid.

Do not over-expand the PR if this triggers many unrelated tests.

---

## PR A tests

### `TransactionValidatorTest`

Required:

```text
validateCreate_rejects_negative_amount
validateCreate_rejects_nan_amount
validateCreate_rejects_amount_over_max
validateCreate_rejects_blank_merchant
validateCreate_rejects_placeholder_merchant
validateCreate_rejects_blank_currency
validateCreate_rejects_lowercase_currency
validateCreate_rejects_invalid_future_date
validateCreate_rejects_transfer_without_direction
validateCreate_rejects_transfer_without_account
validateCreate_rejects_conflicting_ownership
validateCreate_rejects_lat_without_lon
validateCreate_rejects_lon_without_lat
validateFinalExpenseState_matches_create_rules_for_core_fields
validateFinalExpenseState_rejects_latitude_out_of_range
validateFinalExpenseState_rejects_longitude_out_of_range
```

### `TransactionLifecycleCoordinatorUpdateValidationTest`

Required:

```text
updateExpense_rejects_negative_amount_and_does_not_mutate
updateExpense_rejects_blank_merchant_and_does_not_mutate
updateExpense_rejects_invalid_currency_and_does_not_mutate
updateExpense_rejects_future_date_and_does_not_mutate
updateExpense_rejects_transfer_without_direction_and_does_not_mutate
updateExpense_rejects_transfer_without_account_and_does_not_mutate
updateExpense_rejects_lat_without_lon_and_does_not_mutate
updateExpense_invalid_final_state_writes_UPDATE_VALIDATION_FAILED
updateExpense_invalid_final_state_does_not_write_UPDATED
updateExpense_invalid_final_state_does_not_run_side_effects
updateExpense_normalizes_conflicting_ownership_before_validation
updateTypeAndTransferDetails_rejects_TRANSFER_without_metadata
updateTransferDetails_rejects_clearing_metadata_when_existing_type_is_TRANSFER
```

Implementation hints:

- Use fake `PostCommitActionRunner` to assert side effects not run.
- Use DAO reads before/after to assert row unchanged.
- Query `transaction_events` for `UPDATE_VALIDATION_FAILED`.

---

## PR A acceptance criteria

- Create and update share one validator.
- Full-row update cannot persist a state create would reject.
- Transfer metadata cannot be cleared on `TRANSFER`.
- Invalid update writes `UPDATE_VALIDATION_FAILED`.
- Invalid update writes no `UPDATED`.
- Invalid update dispatches no side effects.
- `normalizeOwnership()` is applied before final validation.
- Tests prove row remains unchanged after rejected update.

---

# PR B — Coordinator write-barrier normalization

## Goal

All write guards inside `TransactionLifecycleCoordinator` must use `DatabaseWriteBarrier`, not `restoreMaintenanceMode.isWritesAllowed()`.

This gives one centralized write-blocking contract and structured exceptions.

---

## Files to modify

Primary:

```text
TransactionLifecycleCoordinator.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorWriteBarrierTest.kt
app/src/test/java/com/yourname/expensetracker/architecture/TransactionLifecycleCoordinatorBarrierUsageTest.kt
```

---

## Step B1 — Add helper method

Inside coordinator:

```kotlin
private fun checkWritesAllowed(operation: String) {
    writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.$operation")
}
```

Use this in every mutating coordinator method.

---

## Step B2 — Replace direct restore checks

Replace every pattern:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    throw IllegalStateException("Database writes blocked during restore")
}
```

with:

```kotlin
checkWritesAllowed("methodName")
```

Known methods from current file include at least:

```text
createExpense
updateExpense
updateCategory
updateLocation
updateBusinessFlags
updateTransferDetails
updateTypeAndTransferDetails
updateOwnershipDbOnlyV2
bulk update methods
delete methods
```

Agent must run local search:

```bash
grep -n "restoreMaintenanceMode.isWritesAllowed" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

Expected after PR:

```text
no results
```

---

## Step B3 — Remove direct dependency if unused

After replacing all direct reads, remove from constructor:

```kotlin
private val restoreMaintenanceMode: RestoreMaintenanceMode
```

Remove import:

```kotlin
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
```

If some non-write informational use remains, keep it. But for this issue, no direct write permission checks should remain.

Update Hilt/tests/fakes accordingly.

---

## Step B4 — Preserve create behavior temporarily

Before PR C, create can still catch/return `CreateExpenseResult.Error`, but it must call barrier.

Temporary create pattern:

```kotlin
try {
    checkWritesAllowed("createExpense")
} catch (e: Exception) {
    return CreateExpenseResult.Error(e)
}
```

PR C will add durable diagnostic and narrow catch type.

---

## Step B5 — Architecture test

Create:

```kotlin
class TransactionLifecycleCoordinatorBarrierUsageTest {

    @Test
    fun coordinator_does_not_directly_query_restore_maintenance_mode_for_write_permission() {
        val file = Path.of(
            "src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt"
        ).readText()

        assertFalse(
            "Use DatabaseWriteBarrier.checkWritesAllowed instead of RestoreMaintenanceMode.isWritesAllowed",
            file.contains("restoreMaintenanceMode.isWritesAllowed")
        )
    }
}
```

If project test root is different, adjust path.

Optional stricter checks:

```text
- no import RestoreMaintenanceMode in TransactionLifecycleCoordinator.kt
- no constructor parameter named restoreMaintenanceMode
```

Only enforce these if the dependency is truly unused.

---

## PR B tests

Required:

```text
createExpense_when_writeBarrier_blocks_returns_error_or_blocked_result
updateExpense_when_writeBarrier_blocks_throws_DatabaseAccessBlockedException
updateCategory_when_writeBarrier_blocks_throws_DatabaseAccessBlockedException
updateLocation_when_writeBarrier_blocks_throws_DatabaseAccessBlockedException
updateBusinessFlags_when_writeBarrier_blocks_throws_DatabaseAccessBlockedException
updateTransferDetails_when_writeBarrier_blocks_throws_DatabaseAccessBlockedException
updateTypeAndTransferDetails_when_writeBarrier_blocks_throws_DatabaseAccessBlockedException
updateOwnershipDbOnlyV2_when_writeBarrier_blocks_throws_DatabaseAccessBlockedException
coordinator_has_no_restoreMaintenanceMode_isWritesAllowed_usage
```

Implementation hints:

- Use `RestoreMaintenanceMode.enter(Mode.RESTORE_STAGING)` or fake barrier if tests support it.
- Assert no DB mutation happened.
- If `createExpense()` still returns `Error`, assert `result.exception` is `DatabaseAccessBlockedException`.

---

## PR B acceptance criteria

- No direct `restoreMaintenanceMode.isWritesAllowed()` remains in coordinator.
- Mutating methods call `DatabaseWriteBarrier.checkWritesAllowed`.
- Blocked updates throw structured barrier exception.
- Blocked create returns existing error contract or new blocked result.
- Static test prevents regression.

---

# PR C — Restore-blocked create durable diagnostic

## Goal

A create attempt blocked by restore/write barrier must be durably visible in pipeline diagnostics, not only Timber.

---

## Files to modify

Primary:

```text
TransactionLifecycleCoordinator.kt
```

Possibly:

```text
CreateExpenseResult.kt
```

Tests:

```text
TransactionLifecycleCoordinatorCreateBlockedDiagnosticTest.kt
```

---

## Step C1 — Inject `DiagnosticEventWriter`

Modify coordinator constructor:

```kotlin
private val diagnosticEventWriter: DiagnosticEventWriter
```

Imports:

```kotlin
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
```

If `DatabaseAccessBlockedException` package differs, locate it with:

```bash
grep -R "class DatabaseAccessBlockedException" app/src/main/java
```

---

## Step C2 — Add safe metadata helper

Inside coordinator:

```kotlin
private fun createBlockedDiagnosticMetadata(
    request: CreateExpenseRequest,
    operation: String,
    blocked: Throwable
): SafeEventMetadata {
    return SafeEventMetadata.builder()
        .put("operation", operation)
        .put("source", request.source.name)
        .put("deduplicationMode", request.deduplicationMode.name)
        .put("transactionType", request.transactionType.name)
        .put("currency", request.currency)
        .put("hasIdempotencyKey", request.idempotencyKey != null)
        .put("hasExternalFingerprint", request.externalFingerprint != null)
        .put("exceptionClass", blocked.javaClass.simpleName)
        .build()
}
```

Privacy rules:

- Do **not** include merchant.
- Do **not** include amount.
- Do **not** include notes.
- Do **not** include raw idempotency key.
- Do **not** include raw external fingerprint.
- Do **not** include source payloads.

---

## Step C3 — Add best-effort diagnostic emitter

Inside coordinator:

```kotlin
private suspend fun emitCreateBlockedDiagnosticBestEffort(
    request: CreateExpenseRequest,
    correlationId: String,
    blocked: Throwable
): Boolean {
    return try {
        diagnosticEventWriter.emit(
            DiagnosticEvent(
                pipeline = AppPipeline.TRANSACTION,
                stage = "CREATE_EXPENSE",
                outcome = EventOutcome.BLOCKED,
                severity = EventSeverity.WARNING,
                reasonCode = when (blocked) {
                    is DatabaseAccessBlockedException -> DiagnosticReasonCode.RESTORE_BLOCKED
                    else -> DiagnosticReasonCode.WRITE_BARRIER_DENIED
                },
                entityType = "Expense",
                entityId = null,
                sourceType = request.source.name,
                correlationId = correlationId,
                metadata = createBlockedDiagnosticMetadata(
                    request = request,
                    operation = "createExpense",
                    blocked = blocked
                ),
                exception = blocked,
                isTerminal = true
            )
        )
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Failed to emit restore-blocked create diagnostic")
        false
    }
}
```

---

## Step C4 — Generate correlation before barrier check

In `createExpense(...)`, move/generate correlation ID before the write barrier call:

```kotlin
val correlationId = request.correlationId
    ?: com.yourname.expensetracker.domain.diagnostics.CorrelationIds.newId()
```

Then:

```kotlin
try {
    checkWritesAllowed("createExpense")
} catch (blocked: DatabaseAccessBlockedException) {
    emitCreateBlockedDiagnosticBestEffort(
        request = request,
        correlationId = correlationId,
        blocked = blocked
    )
    return CreateExpenseResult.Error(blocked)
}
```

If `checkWritesAllowed()` can throw a different runtime exception from a fake barrier in tests, either:

```kotlin
catch (blocked: RuntimeException)
```

or keep production narrow and adjust tests. Preferred production catch:

```kotlin
catch (blocked: DatabaseAccessBlockedException)
```

Do not catch `CancellationException`.

---

## Step C5 — Optional result improvement

Optional but recommended if callsites tolerate it:

Modify `CreateExpenseResult.kt`:

```kotlin
data class Blocked(
    val reason: String,
    val diagnosticLogged: Boolean,
    val exception: Throwable
) : CreateExpenseResult()
```

Then return:

```kotlin
CreateExpenseResult.Blocked(
    reason = "Database writes blocked",
    diagnosticLogged = diagnosticLogged,
    exception = blocked
)
```

If this causes too many callsite updates, keep `CreateExpenseResult.Error(blocked)` and only assert diagnostics.

Do not block this PR on result-type cleanup.

---

## Step C6 — Do not write `transaction_events` while blocked

Important:

- Do **not** insert `CREATE_ATTEMPTED` after write barrier failure.
- Do **not** insert transaction lifecycle rows during restore-blocked create.
- Use `PipelineDiagnosticEvent` via `DiagnosticEventWriter`.
- Diagnostic write is best-effort. If diagnostics are also blocked during restore, the create result must still be blocked and no expense should be inserted.

---

## PR C tests

Create fake `DiagnosticEventWriter`:

```kotlin
class RecordingDiagnosticEventWriter : DiagnosticEventWriter {
    val events = mutableListOf<DiagnosticEvent>()
    override suspend fun emit(event: DiagnosticEvent) {
        events += event
    }
}
```

Also create throwing fake:

```kotlin
class ThrowingDiagnosticEventWriter : DiagnosticEventWriter {
    override suspend fun emit(event: DiagnosticEvent) {
        throw RuntimeException("diagnostic write failed")
    }
}
```

Required tests:

```text
restore_blocked_create_emits_transaction_blocked_diagnostic
restore_blocked_create_diagnostic_has_RESTORE_BLOCKED_reason
restore_blocked_create_diagnostic_has_WARNING_severity
restore_blocked_create_diagnostic_uses_request_or_generated_correlationId
restore_blocked_create_diagnostic_metadata_is_privacy_safe
restore_blocked_create_does_not_insert_expense
restore_blocked_create_does_not_write_CREATE_ATTEMPTED_event
restore_blocked_create_returns_Error_or_Blocked
restore_blocked_create_still_returns_blocked_when_diagnostic_writer_fails
restore_blocked_create_rethrows_cancellation_from_diagnostic_writer
```

Privacy-safe metadata assertion:

- Assert metadata contains:
  - `operation`
  - `source`
  - `deduplicationMode`
  - `transactionType`
  - `currency`
  - `hasIdempotencyKey`
  - `hasExternalFingerprint`
- Assert metadata does **not** contain:
  - raw merchant string,
  - raw amount,
  - notes,
  - idempotency key value,
  - external fingerprint value.

---

## PR C acceptance criteria

- Restore-blocked create writes durable diagnostic when possible.
- Create-blocked diagnostic uses `AppPipeline.TRANSACTION`.
- Outcome is `BLOCKED`.
- Reason code is `RESTORE_BLOCKED` or `WRITE_BARRIER_DENIED`.
- Diagnostic metadata is privacy-safe.
- Diagnostic failure does not unblock or crash create path, except cancellation.
- No expense row is inserted.
- No `CREATE_ATTEMPTED` transaction event is written after barrier denial.

---

# Cross-PR sequencing notes

## If PR B lands before PR A

In PR A, use existing `checkWritesAllowed("updateExpense")` helper.

## If PR A lands before PR B

In PR A, keep existing restore checks temporarily, but PR B must replace them immediately after.

## If PR C lands before PR B

PR C should introduce `checkWritesAllowed("createExpense")` locally for create only, but PR B must still sweep all coordinator methods.

Preferred order remains:

```text
PR A -> PR B -> PR C
```

---

# Final combined grep checklist

Run after all three PRs:

```bash
grep -R "restoreMaintenanceMode.isWritesAllowed" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
grep -R "CURRENCY_ISO_PATTERN" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
grep -R "UPDATE_VALIDATION_FAILED" app/src/main/java
grep -R "emitCreateBlockedDiagnosticBestEffort" app/src/main/java
```

Expected:

- no `restoreMaintenanceMode.isWritesAllowed` in coordinator,
- no coordinator-local `CURRENCY_ISO_PATTERN` if validator owns it,
- `UPDATE_VALIDATION_FAILED` exists and is used,
- create-blocked diagnostic helper exists and is used.

---

# Final validation commands

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*TransactionValidatorTest*'
./gradlew testDebugUnitTest --tests '*UpdateValidation*'
./gradlew testDebugUnitTest --tests '*WriteBarrier*'
./gradlew testDebugUnitTest --tests '*CreateBlockedDiagnostic*'
```

---

# Definition of done

## P2-NEW-01 done when

- `createExpense()` and `updateExpense()` share `TransactionValidator`.
- `updateExpense()` rejects invalid final states:
  - invalid amount,
  - blank/placeholder merchant,
  - invalid currency,
  - invalid/future date,
  - invalid transfer metadata,
  - invalid ownership,
  - invalid location pair/range.
- Rejected update writes `UPDATE_VALIDATION_FAILED`.
- Rejected update writes no `UPDATED`.
- Rejected update mutates no expense row.
- Rejected update dispatches no side effects.

## P2-NEW-02 done when

- `TransactionLifecycleCoordinator` no longer uses `restoreMaintenanceMode.isWritesAllowed()`.
- All coordinator write paths call `DatabaseWriteBarrier.checkWritesAllowed`.
- Static test prevents regression.

## P2-NEW-03 done when

- Restore-blocked create emits durable `DiagnosticEvent`.
- Diagnostic is `AppPipeline.TRANSACTION`, `stage = CREATE_EXPENSE`, `outcome = BLOCKED`.
- Reason code is restore/write-barrier related.
- Diagnostic metadata is privacy-safe.
- Diagnostic write failure does not allow create to proceed.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `TransactionLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `Expense.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt
- `CreateExpenseResult.kt`: https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseResult.kt
- `LifecycleEventType.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
- `DatabaseWriteBarrier.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt
- `RestoreMaintenanceMode.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/backup/RestoreMaintenanceMode.kt
- Diagnostics directory: https://github.com/panospao7/Cost-agregator/tree/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics
- `DiagnosticEventWriter.kt`: https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticEventWriter.kt
- `DiagnosticReasonCode.kt`: https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticReasonCode.kt
- `EventOutcome.kt`: https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventOutcome.kt
- `EventSeverity.kt`: https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventSeverity.kt
- `SafeEventMetadata.kt`: https://github.com/panospao7/Cost-agregator/blob/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/SafeEventMetadata.kt