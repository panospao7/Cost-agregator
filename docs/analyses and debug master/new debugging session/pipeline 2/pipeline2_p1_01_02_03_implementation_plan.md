# Pipeline 2 dedicated implementation plan — P2-P1-01, P2-P1-02, P2-P1-03

Target baseline: commit `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Scope:

- `P2-P1-01`: Business/tax lifecycle update is still partial.
- `P2-P1-02`: Failed/blocked create attempts are not fully durable/diagnosable.
- `P2-P1-03`: `STRICT_EXTERNAL_ID` conflict handling is mostly fixed but has dedupe/audit gaps.

Primary files:

- `TransactionLifecycleCoordinator.kt`
- `CreateExpenseRequest.kt`
- `CreateExpenseResult.kt`
- `LifecycleEventType.kt`
- `TransactionEvent.kt`
- `TransactionEventDao.kt`
- `ExpenseDao.kt`
- `SourceLinkEventMetadataBuilder.kt`
- diagnostics files under `domain/diagnostics`

Relevant current evidence:

- `TransactionLifecycleCoordinator.createExpense()` still directly checks `restoreMaintenanceMode.isWritesAllowed()` and has TODOs for restore-blocked diagnostics and strict attempt dedupe-key mismatch.
- `updateBusinessFlags()` accepts `businessUsePercent`, `taxCategory`, and `vatEligible` but logs and ignores them.
- `ExpenseDao` already has `findIdByDedupeKey()` and `findDuplicateIdCurrencyAware()`.
- `LifecycleEventType` already includes create attempt/failure/duplicate/conflict event types.
- Generic diagnostics infrastructure exists with `DiagnosticEventWriter`, `AppPipeline.TRANSACTION`, `EventOutcome.BLOCKED`, `EventOutcome.DUPLICATE`, and `DiagnosticReasonCode.RESTORE_BLOCKED`.

---

# PR structure

Implement as **three PRs**, in this order:

1. **PR A — Business/tax lifecycle contract**
   - Fixes `P2-P1-01`.

2. **PR B — Create diagnostics and restore-blocked visibility**
   - Fixes most of `P2-P1-02`.

3. **PR C — Strict/external dedupe and insert-conflict resolution**
   - Fixes `P2-P1-03` and remaining duplicate/conflict diagnostics from `P2-P1-02`.

Do not mix unrelated Pipeline 2 work into these PRs.

---

# PR A — Business/tax lifecycle contract

## Problem

Current `updateBusinessFlags()` is not honest:

```kotlin
businessUsePercent
taxCategory
vatEligible
```

are accepted as parameters but are not persisted. The method only persists:

```kotlin
isBusinessExpense -> Expense.isBusinessExpense
receiptRequired -> Expense.requiresReceipt
```

This creates a data-loss illusion: callers think tax/business fields were saved when they were dropped.

## Goal

No business/tax update parameter may be silently ignored.

Supported fields should persist.

Unsupported fields should return a structured rejection and optionally write a durable validation/rejection event.

## Required design

Add a new patch/result contract.

Recommended new file:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/BusinessExpensePatch.kt
```

Suggested content:

```kotlin
package com.yourname.expensetracker.domain.transaction

data class BusinessExpensePatch(
    val isBusinessExpense: Boolean? = null,
    val requiresReceipt: Boolean? = null,
    val businessPurpose: String? = null,
    val businessCategory: String? = null,
    val businessProject: String? = null,

    // Legacy unsupported inputs.
    // These are intentionally modelled only so callers can be told they are unsupported.
    val businessUsePercent: Double? = null,
    val taxCategory: String? = null,
    val vatEligible: Boolean? = null
) {
    fun unsupportedFields(): List<String> = buildList {
        if (businessUsePercent != null) add("businessUsePercent")
        if (taxCategory != null) add("taxCategory")
        if (vatEligible != null) add("vatEligible")
    }

    fun isEmpty(): Boolean =
        isBusinessExpense == null &&
        requiresReceipt == null &&
        businessPurpose == null &&
        businessCategory == null &&
        businessProject == null &&
        businessUsePercent == null &&
        taxCategory == null &&
        vatEligible == null
}
```

Recommended new file:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/BusinessExpenseUpdateResult.kt
```

Suggested content:

```kotlin
package com.yourname.expensetracker.domain.transaction

sealed interface BusinessExpenseUpdateResult {
    data class Updated(
        val expenseId: Long,
        val changedFields: Set<String>
    ) : BusinessExpenseUpdateResult

    data object NoChange : BusinessExpenseUpdateResult

    data object NotFound : BusinessExpenseUpdateResult

    data class UnsupportedFields(
        val fields: List<String>
    ) : BusinessExpenseUpdateResult

    data class ValidationFailed(
        val errors: List<String>
    ) : BusinessExpenseUpdateResult
}
```

## Lifecycle event enum

Add one event type to `LifecycleEventType.kt`:

```kotlin
UPDATE_VALIDATION_FAILED
```

This is useful for unsupported business/tax fields and later update validation work.

No DB migration should be needed because `eventType` is stored as `String`.

## Coordinator API

Add new method to `TransactionLifecycleCoordinator`:

```kotlin
suspend fun updateBusinessExpensePatch(
    expenseId: Long,
    patch: BusinessExpensePatch,
    source: String = "BUSINESS_TAX_UPDATE",
    reason: String? = null,
    correlationId: String? = null
): BusinessExpenseUpdateResult
```

Implementation rules:

1. Call:

```kotlin
writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateBusinessExpensePatch")
```

Do **not** use `restoreMaintenanceMode.isWritesAllowed()` directly.

2. If `patch.isEmpty()`:
   - return `NoChange`;
   - do not write an `UPDATED` event.

3. If `patch.unsupportedFields().isNotEmpty()`:
   - do not mutate `expenses`;
   - write `UPDATE_VALIDATION_FAILED` best-effort event with:
     - `expenseId`,
     - `source`,
     - `correlationId`,
     - metadata JSON:
       ```json
       {
         "unsupportedFields": ["businessUsePercent", "taxCategory"],
         "operation": "updateBusinessExpensePatch"
       }
       ```
   - return `UnsupportedFields(fields)`.

4. Load existing expense:

```kotlin
val existing = expenseDao.getById(expenseId) ?: return BusinessExpenseUpdateResult.NotFound
```

5. Build updated row:

```kotlin
val updated = existing.copy(
    isBusinessExpense = patch.isBusinessExpense ?: existing.isBusinessExpense,
    requiresReceipt = patch.requiresReceipt ?: existing.requiresReceipt,
    businessPurpose = patch.businessPurpose ?: existing.businessPurpose,
    businessCategory = patch.businessCategory ?: existing.businessCategory,
    businessProject = patch.businessProject ?: existing.businessProject
)
```

6. Compute changed fields exactly:

```kotlin
val changedFields = mutableSetOf<String>()
if (updated.isBusinessExpense != existing.isBusinessExpense) changedFields += "isBusinessExpense"
if (updated.requiresReceipt != existing.requiresReceipt) changedFields += "requiresReceipt"
if (updated.businessPurpose != existing.businessPurpose) changedFields += "businessPurpose"
if (updated.businessCategory != existing.businessCategory) changedFields += "businessCategory"
if (updated.businessProject != existing.businessProject) changedFields += "businessProject"
```

7. If `changedFields.isEmpty()`:
   - return `NoChange`;
   - do not write fake `UPDATED`.

8. Inside one `database.withTransaction`:
   - `expenseDao.update(updated)`
   - insert `TransactionEvent(UPDATED)` with before/after snapshots.
   - metadata:
     ```json
     {
       "changedFields": ["isBusinessExpense", "requiresReceipt"],
       "operation": "updateBusinessExpensePatch"
     }
     ```

9. After transaction commits:
   - run side effects with:
     ```kotlin
     planner.planUpdated(expenseId, source, correlationId, TransactionUpdateKind.BUSINESS_FLAGS_ONLY)
     ```
   - use existing `runner.runBestEffortAfterCommit(...)`.

## Backward-compatible wrapper

Change existing `updateBusinessFlags(...)` to delegate to the new method.

Current method returns `Unit`. It should return `BusinessExpenseUpdateResult`.

Kotlin callers that ignore the return value will still compile in most cases. If there are callsites explicitly expecting `Unit`, update them.

Recommended:

```kotlin
@Deprecated(
    message = "Use updateBusinessExpensePatch(). Legacy tax fields are rejected instead of ignored.",
    replaceWith = ReplaceWith("updateBusinessExpensePatch(expenseId, BusinessExpensePatch(...))")
)
suspend fun updateBusinessFlags(...): BusinessExpenseUpdateResult {
    return updateBusinessExpensePatch(
        expenseId = expenseId,
        patch = BusinessExpensePatch(
            isBusinessExpense = isBusinessExpense,
            requiresReceipt = receiptRequired,
            businessUsePercent = businessUsePercent,
            taxCategory = taxCategory,
            vatEligible = vatEligible
        ),
        source = source
    )
}
```

## Tests

Add tests under the existing transaction/lifecycle test area. If no test class exists, create:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorBusinessPatchTest.kt
```

Required tests:

```text
business_patch_persists_isBusinessExpense
business_patch_persists_requiresReceipt
business_patch_persists_businessPurpose_businessCategory_businessProject
business_patch_no_change_writes_no_updated_event
business_patch_unsupported_businessUsePercent_returns_UnsupportedFields
business_patch_unsupported_taxCategory_returns_UnsupportedFields
business_patch_unsupported_vatEligible_returns_UnsupportedFields
business_patch_unsupported_fields_do_not_mutate_expense
business_patch_unsupported_fields_writes_UPDATE_VALIDATION_FAILED
business_patch_restore_mode_uses_writeBarrier_and_blocks
```

## Acceptance criteria

- No supported business field is silently dropped.
- Unsupported legacy fields are rejected explicitly.
- No no-op update writes fake `UPDATED`.
- Every actual mutation writes one `UPDATED` event.
- Restore/write blocking goes through `DatabaseWriteBarrier`.

---

# PR B — Create diagnostics and restore-blocked visibility

## Problem

Current create flow has events for:

```text
CREATE_ATTEMPTED
CREATE_VALIDATION_FAILED
CREATE_DUPLICATE_SKIPPED
CREATE_INSERT_CONFLICT
```

But restore-blocked create returns `CreateExpenseResult.Error` and only logs with Timber.

Also, diagnostic events can be rollback-prone when create is called inside a caller-owned Room transaction.

## Goal

Every create attempt has a traceable diagnostic outcome, including restore-blocked creates.

For now, use existing `DiagnosticEventWriter` for durable pipeline-level diagnostics and keep `transaction_events` for lifecycle/audit events.

## Important constraint

Do **not** write normal transaction lifecycle rows when `DatabaseWriteBarrier` blocks business writes during restore unless the project explicitly allows diagnostics during restore.

Preferred behavior:

- normal create is blocked by `writeBarrier`;
- a generic transaction diagnostic event is emitted best-effort:
  - pipeline = `TRANSACTION`
  - stage = `CREATE_EXPENSE`
  - outcome = `BLOCKED`
  - reasonCode = `RESTORE_BLOCKED` or `WRITE_BARRIER_DENIED`.

If the existing `DiagnosticEventWriter` writes to the same Room DB and is also unsafe during restore, it must fail best-effort and not crash the blocked create path.

## Inject diagnostics

Modify `TransactionLifecycleCoordinator` constructor:

```kotlin
private val diagnosticEventWriter: DiagnosticEventWriter
```

Imports:

```kotlin
import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.EventSeverity
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
```

## Add helper methods

Inside `TransactionLifecycleCoordinator`:

```kotlin
private suspend fun emitCreateDiagnosticBestEffort(
    request: CreateExpenseRequest,
    outcome: EventOutcome,
    severity: EventSeverity,
    reasonCode: DiagnosticReasonCode?,
    correlationId: String,
    metadata: SafeEventMetadata = SafeEventMetadata.empty(),
    exception: Throwable? = null,
    isTerminal: Boolean = false
) {
    try {
        diagnosticEventWriter.emit(
            DiagnosticEvent(
                pipeline = AppPipeline.TRANSACTION,
                stage = "CREATE_EXPENSE",
                outcome = outcome,
                severity = severity,
                reasonCode = reasonCode,
                entityType = "Expense",
                entityId = null,
                sourceType = request.source.name,
                correlationId = correlationId,
                metadata = metadata,
                exception = exception,
                isTerminal = isTerminal
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Failed to emit create diagnostic event")
    }
}
```

Also add metadata builder:

```kotlin
private fun createDiagnosticMetadata(
    request: CreateExpenseRequest,
    extra: Map<String, Any?> = emptyMap()
): SafeEventMetadata {
    val builder = SafeEventMetadata.builder()
        .put("source", request.source.name)
        .put("deduplicationMode", request.deduplicationMode.name)
        .put("transactionType", request.transactionType.name)
        .put("currency", request.currency)
        .put("hasIdempotencyKey", request.idempotencyKey != null)
        .put("hasExternalFingerprint", request.externalFingerprint != null)

    extra.forEach { (k, v) -> builder.put(k, v) }
    return builder.build()
}
```

Do not include raw merchant, raw external fingerprint, raw notes, raw provider IDs, or raw receipt/email data in generic diagnostics.

## Replace restore check

Current code:

```kotlin
if (!restoreMaintenanceMode.isWritesAllowed()) {
    Timber.w(...)
    return CreateExpenseResult.Error(...)
}
```

Replace with:

```kotlin
val correlationId = request.correlationId ?: CorrelationIds.newId()

try {
    writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.createExpense")
} catch (blocked: Exception) {
    emitCreateDiagnosticBestEffort(
        request = request,
        outcome = EventOutcome.BLOCKED,
        severity = EventSeverity.WARNING,
        reasonCode = DiagnosticReasonCode.RESTORE_BLOCKED,
        correlationId = correlationId,
        metadata = createDiagnosticMetadata(
            request,
            mapOf("blockedOperation" to "createExpense")
        ),
        exception = blocked,
        isTerminal = true
    )
    return CreateExpenseResult.Error(blocked)
}
```

Important:

- Generate `correlationId` before barrier check.
- Reuse the same `correlationId` for all later transaction events.
- Do not generate a second correlation ID later.

## Emit diagnostics for major non-created outcomes

Add best-effort diagnostics for these outcomes:

1. Validation failed:

```kotlin
emitCreateDiagnosticBestEffort(
    request = request,
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.INFO,
    reasonCode = DiagnosticReasonCode.VALIDATION_FAILED,
    correlationId = correlationId,
    metadata = createDiagnosticMetadata(
        request,
        mapOf("errorCount" to errors.size)
    ),
    isTerminal = true
)
```

2. Duplicate skipped:

```kotlin
emitCreateDiagnosticBestEffort(
    request = request,
    outcome = EventOutcome.DUPLICATE,
    severity = EventSeverity.INFO,
    reasonCode = DiagnosticReasonCode.DUPLICATE,
    correlationId = correlationId,
    metadata = createDiagnosticMetadata(
        request,
        mapOf(
            "existingExpenseId" to duplicateId,
            "duplicateReason" to reason
        )
    ),
    isTerminal = true
)
```

3. Insert conflict unresolved:

```kotlin
emitCreateDiagnosticBestEffort(
    request = request,
    outcome = EventOutcome.FAILED_FINAL,
    severity = EventSeverity.WARNING,
    reasonCode = DiagnosticReasonCode.DUPLICATE,
    correlationId = correlationId,
    metadata = createDiagnosticMetadata(
        request,
        mapOf(
            "dedupeKeyPresent" to (expense.dedupeKey != null),
            "deduplicationMode" to dedupMode.name,
            "conflictResolved" to false
        )
    ),
    isTerminal = true
)
```

4. Created:

Optional but recommended:

```kotlin
emitCreateDiagnosticBestEffort(
    request = request,
    outcome = EventOutcome.CREATED,
    severity = EventSeverity.INFO,
    reasonCode = null,
    correlationId = correlationId,
    metadata = createDiagnosticMetadata(
        request,
        mapOf("expenseId" to insertedId)
    ),
    isTerminal = true
)
```

## Keep transaction event policy

Do not weaken this policy:

- `CREATED` must remain in the same DB transaction as expense insert.
- `SOURCE_LINKED` must remain in the same DB transaction as source-link writes.
- If source-link write fails, throw and rollback expense creation.

Attempt/failure events can remain best-effort, but diagnostics should make outcomes visible even if lifecycle event insertion fails.

## Optional result type improvement

Add a more specific result to `CreateExpenseResult.kt`:

```kotlin
data class Blocked(
    val reason: String,
    val exception: Throwable? = null,
    val diagnosticLogged: Boolean = false
) : CreateExpenseResult()
```

Then restore-blocked create can return:

```kotlin
CreateExpenseResult.Blocked(
    reason = "Database writes blocked during restore",
    exception = blocked,
    diagnosticLogged = true
)
```

If this change causes too many callsite updates, keep returning `CreateExpenseResult.Error`.

## Tests

Create or update:

```text
TransactionLifecycleCoordinatorCreateDiagnosticsTest.kt
```

Required tests:

```text
restore_blocked_create_returns_error_or_blocked_result
restore_blocked_create_emits_TRANSACTION_BLOCKED_diagnostic
restore_blocked_create_does_not_insert_expense
validation_failed_create_writes_CREATE_VALIDATION_FAILED_event
validation_failed_create_emits_FAILED_FINAL_diagnostic
duplicate_create_writes_CREATE_DUPLICATE_SKIPPED_event
duplicate_create_emits_DUPLICATE_diagnostic
insert_conflict_unresolved_writes_CREATE_INSERT_CONFLICT_event
insert_conflict_unresolved_emits_FAILED_FINAL_diagnostic
created_create_uses_same_correlationId_for_attempt_created_source_linked_events
```

If using fake `DiagnosticEventWriter`, assert received events directly.

## Acceptance criteria

- Restore-blocked create is visible beyond Timber logs.
- `writeBarrier` is used for create blocking.
- Diagnostics never crash production create flow.
- Cancellation is rethrown.
- Correlation ID is stable across diagnostics and transaction events.

---

# PR C — Strict/external dedupe and insert-conflict resolution

## Problem

Current strict external ID handling is only mostly fixed.

Known gaps:

1. `CREATE_ATTEMPTED.dedupeKey` uses standard generated key even in `STRICT_EXTERNAL_ID` mode.
2. Strict external persisted dedupe key uses:
   ```text
   idem:{source}:{idempotencyKey or externalFingerprint}
   ```
   but the attempt event can show a different key.
3. Insert conflict currently writes `CREATE_INSERT_CONFLICT` first, then resolves strict duplicate. This creates noisy/confusing audit.
4. Standard/BULK insert races can still return `InsertConflict` without resolving the existing expense ID.

## Goal

One canonical key derivation path.

All resolvable insert conflicts should return:

```kotlin
CreateExpenseResult.DuplicateSkipped(existingExpenseId = realId)
```

Only truly unresolved conflicts should return:

```kotlin
CreateExpenseResult.InsertConflict(...)
```

## Add key helper

Inside `TransactionLifecycleCoordinator`:

```kotlin
private fun strictExternalIdentityKey(request: CreateExpenseRequest): String? {
    return request.idempotencyKey ?: request.externalFingerprint
}

private fun strictExternalDedupeKey(request: CreateExpenseRequest): String? {
    val key = strictExternalIdentityKey(request) ?: return null
    return "idem:${request.source.name}:$key"
}

private fun standardCreateDedupeKey(request: CreateExpenseRequest): String {
    return DuplicateDetectionPolicy.generateDedupeKeyWithType(
        amount = request.amount,
        merchant = request.merchant,
        date = request.date,
        currency = request.currency,
        transactionType = request.transactionType
    )
}

private fun createAttemptDedupeKey(request: CreateExpenseRequest): String? {
    return when (request.deduplicationMode) {
        DeduplicationMode.STRICT_EXTERNAL_ID ->
            strictExternalDedupeKey(request)
        else ->
            standardCreateDedupeKey(request)
    }
}
```

Use `createAttemptDedupeKey(request)` for:

- `CREATE_ATTEMPTED`
- `CREATE_VALIDATION_FAILED`
- strict missing-key validation event, where it may be `null`
- any pre-insert diagnostic metadata

## Strict missing-key validation

Current strict missing-key branch writes validation failure after deduplication starts.

Keep behavior, but use the canonical helper.

If strict key is missing:

- write `CREATE_VALIDATION_FAILED`
- dedupeKey = `null`
- diagnostic reason = `VALIDATION_FAILED`
- return `ValidationFailed`

Do not persist an expense with a standard dedupe key in strict mode.

## Use same key for persisted expense

In strict branch:

```kotlin
val strictKey = strictExternalDedupeKey(request)
    ?: return validationFailed(...)

expense = expense.copy(dedupeKey = strictKey)
```

Do not reconstruct the strict key separately.

## Add conflict resolver

Add helper:

```kotlin
private suspend fun resolveExistingIdAfterInsertConflict(
    expense: Expense,
    dedupMode: DeduplicationMode
): Long? {
    // 1. Exact dedupe key lookup first.
    val byDedupe = expense.dedupeKey
        ?.takeIf { it.isNotBlank() }
        ?.let { expenseDao.findIdByDedupeKey(it) }

    if (byDedupe != null) return byDedupe

    // 2. For standard/bulk, resolve by same currency-aware duplicate policy.
    // For STRICT_EXTERNAL_ID, do not fall back to fuzzy/range matching unless
    // product explicitly wants strict retries to match non-strict historical rows.
    if (dedupMode == DeduplicationMode.STRICT_EXTERNAL_ID) {
        return null
    }

    return expenseDao.findDuplicateIdCurrencyAware(
        amount = expense.amount,
        merchant = expense.merchant,
        date = expense.date,
        currency = expense.currency,
        transactionType = expense.transactionType.name,
        merchantKey = expense.merchantKey,
        dedupeKey = expense.dedupeKey
    )
}
```

## Refactor insert conflict flow

Current flow:

1. insertAtomic returns <= 0
2. write `CREATE_INSERT_CONFLICT`
3. if strict, try find existing
4. maybe return duplicate

Change to:

1. insertAtomic returns <= 0
2. call `resolveExistingIdAfterInsertConflict(...)`
3. if existing ID found:
   - write `CREATE_DUPLICATE_SKIPPED`
   - emit duplicate diagnostic
   - return `DuplicateSkipped(existingId, ...)`
4. only if no existing ID found:
   - write `CREATE_INSERT_CONFLICT`
   - emit unresolved conflict diagnostic
   - return `InsertConflict`

Pseudo-code:

```kotlin
if (insertedId <= 0L) {
    val existingId = resolveExistingIdAfterInsertConflict(expense, dedupMode)

    if (existingId != null) {
        val eventLogged = writeDuplicateEvent(
            expense = expense,
            request = request,
            occurredAt = now,
            duplicateExpenseId = existingId,
            reason = when (dedupMode) {
                DeduplicationMode.STRICT_EXTERNAL_ID ->
                    "STRICT_EXTERNAL_ID idempotent retry resolved to existing expense"
                DeduplicationMode.BULK_IMPORT ->
                    "BULK_IMPORT insert conflict resolved to existing expense"
                else ->
                    "Insert conflict resolved to existing duplicate expense"
            }
        )

        emitCreateDiagnosticBestEffort(
            request = request,
            outcome = EventOutcome.DUPLICATE,
            severity = EventSeverity.INFO,
            reasonCode = DiagnosticReasonCode.DUPLICATE,
            correlationId = correlationId,
            metadata = createDiagnosticMetadata(
                request,
                mapOf(
                    "existingExpenseId" to existingId,
                    "conflictResolved" to true,
                    "deduplicationMode" to dedupMode.name
                )
            ),
            isTerminal = true
        )

        return CreateExpenseResult.DuplicateSkipped(
            existingExpenseId = existingId,
            reason = "Insert conflict resolved to existing expense $existingId",
            eventLogged = eventLogged
        )
    }

    writeInsertConflictEvent(...)
    emit unresolved conflict diagnostic...
    return CreateExpenseResult.InsertConflict(expense.dedupeKey ?: "unknown")
}
```

## Improve pre-check duplicate path

Current standard/bulk duplicate precheck can return:

```kotlin
DuplicateSkipped(existingExpenseId = duplicateId ?: -1L)
```

Do not return `-1L` if it can be avoided.

Add helper:

```kotlin
private suspend fun findDuplicateIdForExpense(expense: Expense): Long? {
    return expenseDao.findDuplicateIdCurrencyAware(
        amount = expense.amount,
        merchant = expense.merchant,
        date = expense.date,
        currency = expense.currency,
        transactionType = expense.transactionType.name,
        merchantKey = expense.merchantKey,
        dedupeKey = expense.dedupeKey
    ) ?: expense.dedupeKey?.let { expenseDao.findIdByDedupeKey(it) }
}
```

Then use it in STANDARD and BULK duplicate branches.

If still null:

- return `DuplicateSkipped(existingExpenseId = -1L)` only as last resort;
- include metadata:
  ```json
  {
    "existingIdResolved": false
  }
  ```

## Source-link duplicate policy

Do not write source links to existing duplicates in this PR unless already supported safely.

For now:

- record source-link payloads in duplicate metadata;
- leave actual duplicate source linking to a separate provenance PR.

## Tests

Create/update:

```text
TransactionLifecycleCoordinatorDedupTest.kt
```

Required tests:

```text
strict_external_create_attempt_uses_idem_dedupe_key
strict_external_missing_key_validation_event_has_null_or_no_dedupe_key
strict_external_first_create_persists_idem_dedupe_key
strict_external_retry_returns_DuplicateSkipped_with_existing_id
strict_external_retry_writes_CREATE_DUPLICATE_SKIPPED_not_CREATE_INSERT_CONFLICT
standard_insert_conflict_resolves_existing_id_by_dedupe_key
standard_insert_conflict_resolves_existing_id_by_currency_aware_duplicate_lookup
bulk_insert_conflict_resolves_existing_id_when_possible
unresolved_insert_conflict_writes_CREATE_INSERT_CONFLICT
unresolved_insert_conflict_returns_InsertConflict
duplicate_skipped_event_contains_duplicateExpenseId
attempt_created_duplicate_events_share_correlationId
```

## Acceptance criteria

- Strict external attempt key equals persisted strict key.
- Strict retry produces duplicate event, not conflict noise.
- Standard/BULK insert races resolve to existing ID when possible.
- `InsertConflict` is reserved for truly unresolved conflicts.
- No raw source payloads are added to event metadata.

---

# Final combined validation checklist

After all three PRs:

Run:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Also run targeted tests if available:

```bash
./gradlew testDebugUnitTest --tests '*TransactionLifecycleCoordinator*'
./gradlew testDebugUnitTest --tests '*BusinessPatch*'
./gradlew testDebugUnitTest --tests '*Dedup*'
```

Manual grep checks:

```bash
grep -R "businessUsePercent.*ignored" app/src/main/java
grep -R "taxCategory.*ignored" app/src/main/java
grep -R "vatEligible.*ignored" app/src/main/java
grep -R "restoreMaintenanceMode.isWritesAllowed" app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
grep -R "P2-CURRENT-011\|P2-CURRENT-012\|P2-CURRENT-014" app/src/main/java
```

Expected:

- no ignored business/tax field warnings remain;
- create path uses `writeBarrier`;
- strict external TODOs are removed;
- tests prove diagnostics/dedup behavior.

---

# Definition of done

## P2-P1-01 done when

- `updateBusinessFlags()` no longer silently drops fields.
- New patch API persists all supported fields.
- Unsupported fields return `UnsupportedFields`.
- Rejected update writes `UPDATE_VALIDATION_FAILED` or transaction diagnostic.
- No-op patch writes no fake `UPDATED`.

## P2-P1-02 done when

- Restore-blocked create emits a transaction diagnostic.
- Validation failed emits lifecycle event and diagnostic.
- Duplicate skipped emits lifecycle event and diagnostic.
- Unresolved insert conflict emits lifecycle event and diagnostic.
- Correlation ID ties attempt/failure/diagnostic together.

## P2-P1-03 done when

- Strict external attempt key matches strict persisted dedupe key.
- Strict retry returns existing ID.
- Strict retry writes duplicate event instead of noisy conflict event.
- Standard/BULK insert races resolve existing ID where possible.
- `InsertConflict` only means “unresolved conflict”.

---

# Sources used

- Commit: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `TransactionLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `CreateExpenseRequest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
- `CreateExpenseResult.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseResult.kt
- `LifecycleEventType.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
- `ExpenseDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
- `TransactionEventDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/dao/TransactionEventDao.kt
- `TransactionEvent.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt
- `DatabaseWriteBarrier.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt
- `SourceLinkEventMetadataBuilder.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkEventMetadataBuilder.kt
- `DiagnosticEventWriter.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticEventWriter.kt
- `DiagnosticReasonCode.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/DiagnosticReasonCode.kt
- `EventOutcome.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/diagnostics/EventOutcome.kt