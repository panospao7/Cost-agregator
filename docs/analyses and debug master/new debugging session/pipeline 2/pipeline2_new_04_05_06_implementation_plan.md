# Pipeline 2 implementation plan — P2-NEW-04, P2-NEW-05, P2-NEW-06

Target baseline: commit `ad91767a9f30db77b6d4b6d8410d788eeaa610c0`

Issues covered:

| ID | Severity | Issue |
|---|---:|---|
| P2-NEW-04 | P2 | `STRICT_EXTERNAL_ID` attempt event dedupe key mismatch |
| P2-NEW-05 | P2 | Standard/BULK insert race can return unresolved `InsertConflict` |
| P2-NEW-06 | P2 | Business/tax API silently drops accepted fields |

Relevant current evidence:

- `TransactionLifecycleCoordinator.createExpense()` computes `attemptDedupeKey` using the standard amount/merchant/date/currency/type formula even when `DeduplicationMode.STRICT_EXTERNAL_ID` later persists `dedupeKey = "idem:${source}:${key}"`.
- Insert conflict path currently writes `CREATE_INSERT_CONFLICT` before resolving strict external conflict. Standard/BULK conflict resolution does not try hard enough to find the existing ID.
- `updateBusinessFlags()` accepts `businessUsePercent`, `taxCategory`, and `vatEligible`, logs that they are ignored, and persists only `isBusinessExpense` and `receiptRequired -> requiresReceipt`.
- `Expense` already has real business fields: `isBusinessExpense`, `businessPurpose`, `businessCategory`, `businessProject`, and `requiresReceipt`.

---

# Recommended PR slicing

Implement as **three PRs**:

1. **PR A — Canonical create dedupe key helpers**
   - Fixes P2-NEW-04.
   - Also fixes duplicate-event correlation consistency.

2. **PR B — Insert conflict resolver for Standard/BULK/Strict**
   - Fixes P2-NEW-05.
   - Depends on PR A helpers.

3. **PR C — Business/tax patch contract**
   - Fixes P2-NEW-06.
   - Independent from PR A/B.

If you want fewer PRs, combine PR A + PR B because both touch create/dedup logic. Keep PR C separate.

---

# PR A — Canonical create dedupe key helpers

## Goal

Every create attempt should use the same dedupe key in:

- `CREATE_ATTEMPTED`,
- `CREATE_VALIDATION_FAILED`,
- `CREATE_DUPLICATE_SKIPPED`,
- persisted `Expense.dedupeKey`,
- conflict diagnostics/events.

For `STRICT_EXTERNAL_ID`, the attempt key must match the persisted strict key:

```text
idem:{source}:{idempotencyKey or externalFingerprint}
```

---

## Files to modify

Primary:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
```

Tests:

```text
app/src/test/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinatorStrictExternalDedupeTest.kt
```

---

## Step A1 — Add canonical helper methods

Inside `TransactionLifecycleCoordinator`, add:

```kotlin
private fun strictExternalIdentityKey(request: CreateExpenseRequest): String? {
    return request.idempotencyKey
        ?.takeIf { it.isNotBlank() }
        ?: request.externalFingerprint?.takeIf { it.isNotBlank() }
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
        DeduplicationMode.STRICT_EXTERNAL_ID -> strictExternalDedupeKey(request)
        else -> standardCreateDedupeKey(request)
    }
}
```

Rules:

- `STRICT_EXTERNAL_ID` with missing key returns `null`.
- Do **not** fall back to the standard key for strict mode.
- Use `.takeIf { it.isNotBlank() }` so blank keys are treated as missing.

---

## Step A2 — Replace current attempt key computation

Current logic:

```kotlin
val attemptDedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(...)
```

Replace with:

```kotlin
val attemptDedupeKey = createAttemptDedupeKey(request)
```

Use this value for:

- `CREATE_ATTEMPTED`,
- normal `CREATE_VALIDATION_FAILED`,
- strict missing-key `CREATE_VALIDATION_FAILED`,
- insert-conflict metadata if it refers to attempted dedupe key.

---

## Step A3 — Use same strict helper for persisted row

Current strict branch effectively does:

```kotlin
val key = request.idempotencyKey ?: request.externalFingerprint
expense = expense.copy(dedupeKey = "idem:${request.source.name}:$key")
```

Replace with:

```kotlin
val strictKey = strictExternalDedupeKey(request)
if (strictKey == null) {
    runCatching {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.CREATE_VALIDATION_FAILED.name,
                source = request.source.name,
                actor = null,
                occurredAt = now,
                dedupeKey = null,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = JSONObject().apply {
                    put("errors", "STRICT_EXTERNAL_ID mode requires idempotencyKey or externalFingerprint")
                    put("deduplicationMode", DeduplicationMode.STRICT_EXTERNAL_ID.name)
                }.toString(),
                reason = "STRICT_EXTERNAL_ID missing key",
                correlationId = correlationId
            )
        )
    }
    return CreateExpenseResult.ValidationFailed(
        listOf("STRICT_EXTERNAL_ID mode requires idempotencyKey or externalFingerprint")
    )
}

expense = expense.copy(dedupeKey = strictKey)
```

Important:

- The strict missing-key validation event should have `dedupeKey = null`.
- Do not use the standard attempt key for strict missing-key failures.
- Do not persist a standard dedupe key in strict mode.

---

## Step A4 — Fix duplicate-event correlation consistency

Current `writeDuplicateEvent()` regenerates a correlation ID when `request.correlationId == null`, which can make duplicate events diverge from the attempt event correlation ID generated earlier in `createExpense()`.

Change helper signature from:

```kotlin
private suspend fun writeDuplicateEvent(
    expense: Expense,
    request: CreateExpenseRequest,
    occurredAt: Long,
    duplicateExpenseId: Long?,
    reason: String
): Boolean
```

to:

```kotlin
private suspend fun writeDuplicateEvent(
    expense: Expense,
    request: CreateExpenseRequest,
    occurredAt: Long,
    duplicateExpenseId: Long?,
    reason: String,
    correlationId: String
): Boolean
```

Then use:

```kotlin
correlationId = correlationId
```

inside the inserted `TransactionEvent`.

Update all callsites:

```kotlin
writeDuplicateEvent(
    expense = expense,
    request = request,
    occurredAt = now,
    duplicateExpenseId = duplicateId,
    reason = "Standard duplicate",
    correlationId = correlationId
)
```

---

## PR A tests

Required tests:

```text
strict_external_attempt_event_uses_idem_key_from_idempotencyKey
strict_external_attempt_event_uses_idem_key_from_externalFingerprint_when_idempotencyKey_missing
strict_external_missing_key_attempt_event_has_null_dedupeKey
strict_external_missing_key_validation_event_has_null_dedupeKey
strict_external_first_create_persists_same_idem_key_as_attempt_event
standard_create_attempt_event_still_uses_standard_dedupe_key
bulk_import_attempt_event_still_uses_standard_dedupe_key
duplicate_event_uses_same_correlationId_as_attempt_event
```

Implementation hints:

- Use `CreateExpenseRequest(deduplicationMode = DeduplicationMode.STRICT_EXTERNAL_ID, idempotencyKey = "abc")`.
- Query `transaction_events` by `correlationId`.
- Assert `CREATE_ATTEMPTED.dedupeKey == "idem:${source.name}:abc"`.
- Assert created expense row has the same `dedupeKey`.

---

## PR A acceptance criteria

- `P2-CURRENT-012` TODO is removed.
- Strict external attempt event key equals persisted expense key.
- Strict external missing-key failures do not use a standard dedupe key.
- Duplicate events share the same correlation ID as the create attempt.

---

# PR B — Insert conflict resolver for Standard/BULK/Strict

## Goal

If `expenseDao.insertAtomic(expense)` returns `<= 0`, do not immediately return unresolved `InsertConflict`.

Instead:

1. Try to resolve the existing expense ID.
2. If found, write `CREATE_DUPLICATE_SKIPPED`.
3. Return `CreateExpenseResult.DuplicateSkipped(existingExpenseId = id)`.
4. Only write `CREATE_INSERT_CONFLICT` and return `InsertConflict` if the existing row cannot be resolved.

---

## Files to modify

Primary:

```text
TransactionLifecycleCoordinator.kt
ExpenseDao.kt
```

`ExpenseDao.kt` likely already has:

```kotlin
findIdByDedupeKey(dedupeKey: String): Long?
findDuplicateIdCurrencyAware(...): Long?
```

Only add DAO methods if local code lacks a needed exact lookup.

Tests:

```text
TransactionLifecycleCoordinatorInsertConflictResolutionTest.kt
```

---

## Step B1 — Add duplicate ID helper

Inside `TransactionLifecycleCoordinator`:

```kotlin
private suspend fun findDuplicateIdForExpense(expense: Expense): Long? {
    val byPolicy = expenseDao.findDuplicateIdCurrencyAware(
        amount = expense.amount,
        merchant = expense.merchant,
        date = expense.date,
        currency = expense.currency,
        transactionType = expense.transactionType.name,
        merchantKey = expense.merchantKey,
        dedupeKey = expense.dedupeKey
    )

    if (byPolicy != null) return byPolicy

    return expense.dedupeKey
        ?.takeIf { it.isNotBlank() }
        ?.let { expenseDao.findIdByDedupeKey(it) }
}
```

Use this in the STANDARD and BULK precheck paths instead of repeating `findDuplicateIdCurrencyAware(...)`.

---

## Step B2 — Add insert-conflict resolver

Inside `TransactionLifecycleCoordinator`:

```kotlin
private suspend fun resolveExistingIdAfterInsertConflict(
    expense: Expense,
    dedupMode: DeduplicationMode
): Long? {
    // 1. Exact dedupe-key lookup first. This is the most likely cause of insertAtomic IGNORE.
    val byDedupeKey = expense.dedupeKey
        ?.takeIf { it.isNotBlank() }
        ?.let { expenseDao.findIdByDedupeKey(it) }

    if (byDedupeKey != null) return byDedupeKey

    // 2. STRICT_EXTERNAL_ID should not fuzzy-match. If exact key failed, unresolved.
    if (dedupMode == DeduplicationMode.STRICT_EXTERNAL_ID) {
        return null
    }

    // 3. Debug restore intentionally skips dedupe. Do not fuzzy-resolve unless exact key matched.
    if (dedupMode == DeduplicationMode.SKIP_FOR_DEBUG_RESTORE) {
        return null
    }

    // 4. STANDARD/BULK fallback: resolve by the same policy as duplicate precheck.
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

---

## Step B3 — Use helper in STANDARD/BULK precheck

Current STANDARD/BULK duplicate branch does:

```kotlin
val duplicateId = expenseDao.findDuplicateIdCurrencyAware(...)
return DuplicateSkipped(existingExpenseId = duplicateId ?: -1L, ...)
```

Change to:

```kotlin
val duplicateId = findDuplicateIdForExpense(expense)

val eventLogged = writeDuplicateEvent(
    expense = expense,
    request = request,
    occurredAt = now,
    duplicateExpenseId = duplicateId,
    reason = "Standard duplicate",
    correlationId = correlationId
)

return CreateExpenseResult.DuplicateSkipped(
    existingExpenseId = duplicateId ?: -1L,
    reason = if (duplicateId != null) {
        "Duplicate expense detected: existingExpenseId=$duplicateId"
    } else {
        "Duplicate expense detected but existing ID could not be resolved"
    },
    eventLogged = eventLogged
)
```

Do the same for `BULK_IMPORT`.

Optional but recommended:

- Add metadata field in duplicate event when `duplicateId == null`:
  ```json
  { "existingIdResolved": false }
  ```
- This may require extending `SourceLinkEventMetadataBuilder.duplicateMetadata(...)` or wrapping with a merged JSON object.

---

## Step B4 — Refactor insert conflict flow

Current flow:

1. `insertAtomic()` returns `<= 0`.
2. Write `CREATE_INSERT_CONFLICT`.
3. For strict mode only, lookup existing by dedupe key.
4. Maybe return duplicate.
5. Otherwise return `InsertConflict`.

Replace with:

```kotlin
if (insertedId <= 0L) {
    val existingId = resolveExistingIdAfterInsertConflict(
        expense = expense,
        dedupMode = dedupMode
    )

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
                DeduplicationMode.STANDARD ->
                    "STANDARD insert conflict resolved to existing expense"
                DeduplicationMode.SKIP_FOR_DEBUG_RESTORE ->
                    "Insert conflict resolved to existing expense"
            },
            correlationId = correlationId
        )

        return CreateExpenseResult.DuplicateSkipped(
            existingExpenseId = existingId,
            reason = "Insert conflict resolved to existing expense $existingId",
            eventLogged = eventLogged
        )
    }

    runCatching {
        transactionEventDao.insert(
            TransactionEvent(
                expenseId = null,
                eventType = LifecycleEventType.CREATE_INSERT_CONFLICT.name,
                source = request.source.name,
                actor = null,
                occurredAt = now,
                dedupeKey = expense.dedupeKey,
                duplicateExpenseId = null,
                beforeSnapshot = null,
                afterSnapshot = null,
                metadata = SourceLinkEventMetadataBuilder.insertConflictMetadata(
                    dedupMode = dedupMode,
                    dedupeKey = expense.dedupeKey,
                    payloads = sourceLinkPayloads
                ),
                reason = "Unresolved insert conflict for dedupeKey=${expense.dedupeKey}",
                correlationId = correlationId
            )
        )
    }

    return CreateExpenseResult.InsertConflict(expense.dedupeKey ?: "unknown")
}
```

Important:

- `CREATE_INSERT_CONFLICT` is now only for unresolved conflict.
- Resolved races should produce `CREATE_DUPLICATE_SKIPPED`.
- Strict external retry should no longer write both conflict and duplicate events.

---

## Step B5 — Consider adding richer result only if needed

Current result:

```kotlin
data class InsertConflict(val dedupeKey: String)
```

Keep it for now.

Do not introduce a new result type unless tests/callsites require it.

---

## PR B tests

Required tests:

```text
strict_external_retry_returns_DuplicateSkipped_with_existing_id
strict_external_retry_writes_CREATE_DUPLICATE_SKIPPED
strict_external_retry_does_not_write_CREATE_INSERT_CONFLICT

standard_insert_conflict_resolves_existing_id_by_dedupeKey
standard_insert_conflict_resolves_existing_id_by_currencyAwareDuplicateLookup
standard_resolved_insert_conflict_returns_DuplicateSkipped
standard_resolved_insert_conflict_writes_CREATE_DUPLICATE_SKIPPED
standard_resolved_insert_conflict_does_not_write_CREATE_INSERT_CONFLICT

bulk_insert_conflict_resolves_existing_id_by_dedupeKey
bulk_insert_conflict_resolves_existing_id_by_currencyAwareDuplicateLookup
bulk_resolved_insert_conflict_returns_DuplicateSkipped
bulk_resolved_insert_conflict_writes_CREATE_DUPLICATE_SKIPPED
bulk_resolved_insert_conflict_does_not_write_CREATE_INSERT_CONFLICT

unresolved_insert_conflict_returns_InsertConflict
unresolved_insert_conflict_writes_CREATE_INSERT_CONFLICT
unresolved_insert_conflict_does_not_write_CREATE_DUPLICATE_SKIPPED
```

Testing hints:

- For strict retry, use real DB:
  1. Create with strict idempotency key.
  2. Create again with same request.
  3. Assert duplicate result and events.

- For Standard/BULK insert-race tests, integration tests may be hard because precheck can catch duplicates before insert.
  Options:
  1. Use a fake/mock `ExpenseDao` where `insertAtomic()` returns `-1` and lookup methods return a known ID.
  2. Or directly test `resolveExistingIdAfterInsertConflict()` by making it `internal` with `@VisibleForTesting`.
  3. Or insert a row manually with a conflicting `dedupeKey` that bypasses the normal precheck.

Preferred:

```kotlin
@VisibleForTesting
internal suspend fun resolveExistingIdAfterInsertConflictForTest(...)
```

only if project allows this pattern. Otherwise use fake DAO.

---

## PR B acceptance criteria

- `P2-CURRENT-005` TODO is removed or replaced with a narrow follow-up if concurrency testing remains.
- Resolvable insert conflicts return `DuplicateSkipped(existingId)`.
- Strict retry writes duplicate event, not conflict event.
- Standard/BULK races resolve existing ID when possible.
- `InsertConflict` means “we could not identify the existing row.”

---

# PR C — Business/tax patch contract

## Goal

`updateBusinessFlags()` must stop silently dropping accepted inputs.

Supported fields should persist.

Unsupported fields should be rejected explicitly.

---

## Files to modify

Primary:

```text
TransactionLifecycleCoordinator.kt
LifecycleEventType.kt
```

New files:

```text
app/src/main/java/com/yourname/expensetracker/domain/transaction/BusinessExpensePatch.kt
app/src/main/java/com/yourname/expensetracker/domain/transaction/BusinessExpenseUpdateResult.kt
```

Tests:

```text
TransactionLifecycleCoordinatorBusinessPatchTest.kt
```

---

## Step C1 — Add `BusinessExpensePatch`

Create:

```kotlin
package com.yourname.expensetracker.domain.transaction

data class BusinessExpensePatch(
    val isBusinessExpense: Boolean? = null,
    val requiresReceipt: Boolean? = null,
    val businessPurpose: String? = null,
    val businessCategory: String? = null,
    val businessProject: String? = null,

    // Legacy unsupported fields. These must not be silently ignored.
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

---

## Step C2 — Add `BusinessExpenseUpdateResult`

Create:

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

    data class Error(
        val message: String,
        val causeClass: String? = null
    ) : BusinessExpenseUpdateResult
}
```

If the project Kotlin version does not support `data object`, use:

```kotlin
object NoChange : BusinessExpenseUpdateResult
object NotFound : BusinessExpenseUpdateResult
```

---

## Step C3 — Add lifecycle event type

Modify `LifecycleEventType.kt`:

```kotlin
UPDATE_VALIDATION_FAILED
```

No DB migration should be required because event type is stored as a string.

---

## Step C4 — Add metadata helper

Inside coordinator:

```kotlin
private fun businessPatchMetadata(
    operation: String,
    changedFields: Set<String> = emptySet(),
    unsupportedFields: List<String> = emptyList()
): String {
    return JSONObject().apply {
        put("operation", operation)
        if (changedFields.isNotEmpty()) {
            put("changedFields", changedFields.joinToString(","))
        }
        if (unsupportedFields.isNotEmpty()) {
            put("unsupportedFields", unsupportedFields.joinToString(","))
        }
    }.toString()
}
```

Privacy rule:

- Do not include business purpose/category/project values in metadata.
- Field names are safe; raw values are not needed.

---

## Step C5 — Add new coordinator method

Add:

```kotlin
suspend fun updateBusinessExpensePatch(
    expenseId: Long,
    patch: BusinessExpensePatch,
    source: String = "BUSINESS_TAX_UPDATE",
    reason: String? = null,
    correlationId: String? = null
): BusinessExpenseUpdateResult
```

Implementation:

```kotlin
suspend fun updateBusinessExpensePatch(
    expenseId: Long,
    patch: BusinessExpensePatch,
    source: String,
    reason: String?,
    correlationId: String?
): BusinessExpenseUpdateResult {
    // Use the central write guard if P2-NEW-02 has landed.
    // Otherwise preserve the current restore guard but do not add new bypasses.
    if (!restoreMaintenanceMode.isWritesAllowed()) {
        throw IllegalStateException("Database writes blocked during restore")
    }

    if (patch.isEmpty()) {
        return BusinessExpenseUpdateResult.NoChange
    }

    val unsupported = patch.unsupportedFields()
    if (unsupported.isNotEmpty()) {
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
                    metadata = businessPatchMetadata(
                        operation = "updateBusinessExpensePatch",
                        unsupportedFields = unsupported
                    ),
                    reason = "Unsupported business/tax fields: ${unsupported.joinToString(",")}",
                    correlationId = correlationId
                )
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Timber.w(it, "Failed to write UPDATE_VALIDATION_FAILED for business patch")
        }

        return BusinessExpenseUpdateResult.UnsupportedFields(unsupported)
    }

    val existing = expenseDao.getById(expenseId)
        ?: return BusinessExpenseUpdateResult.NotFound

    val updated = existing.copy(
        isBusinessExpense = patch.isBusinessExpense ?: existing.isBusinessExpense,
        requiresReceipt = patch.requiresReceipt ?: existing.requiresReceipt,
        businessPurpose = patch.businessPurpose ?: existing.businessPurpose,
        businessCategory = patch.businessCategory ?: existing.businessCategory,
        businessProject = patch.businessProject ?: existing.businessProject
    )

    val changedFields = buildSet {
        if (updated.isBusinessExpense != existing.isBusinessExpense) add("isBusinessExpense")
        if (updated.requiresReceipt != existing.requiresReceipt) add("requiresReceipt")
        if (updated.businessPurpose != existing.businessPurpose) add("businessPurpose")
        if (updated.businessCategory != existing.businessCategory) add("businessCategory")
        if (updated.businessProject != existing.businessProject) add("businessProject")
    }

    if (changedFields.isEmpty()) {
        return BusinessExpenseUpdateResult.NoChange
    }

    val now = timeProvider.now()
    val beforeSnapshot = expenseToSnapshot(existing)

    database.withTransaction {
        expenseDao.update(updated)

        transactionEventDao.insert(
            TransactionEvent(
                expenseId = expenseId,
                eventType = LifecycleEventType.UPDATED.name,
                source = source,
                actor = null,
                occurredAt = now,
                dedupeKey = existing.dedupeKey,
                duplicateExpenseId = null,
                beforeSnapshot = beforeSnapshot,
                afterSnapshot = expenseToSnapshot(expenseId, updated),
                metadata = businessPatchMetadata(
                    operation = "updateBusinessExpensePatch",
                    changedFields = changedFields
                ),
                reason = reason,
                correlationId = correlationId
            )
        )
    }

    val batch = planner.planUpdated(
        expenseId,
        source,
        correlationId,
        TransactionUpdateKind.BUSINESS_FLAGS_ONLY
    )

    runner.runBestEffortAfterCommit(
        batch = batch,
        logMessage = "Non-critical: side effects failed after updating business/tax fields for expense",
        targetId = expenseId
    )

    return BusinessExpenseUpdateResult.Updated(
        expenseId = expenseId,
        changedFields = changedFields
    )
}
```

If P2-NEW-02 has already landed, replace the restore check with:

```kotlin
checkWritesAllowed("updateBusinessExpensePatch")
```

or:

```kotlin
writeBarrier.checkWritesAllowed("TransactionLifecycleCoordinator.updateBusinessExpensePatch")
```

---

## Step C6 — Convert old `updateBusinessFlags()` into wrapper

Change old method to return `BusinessExpenseUpdateResult`:

```kotlin
@Deprecated(
    message = "Use updateBusinessExpensePatch(). Legacy tax fields are rejected instead of ignored.",
    replaceWith = ReplaceWith("updateBusinessExpensePatch(expenseId, BusinessExpensePatch(...))")
)
suspend fun updateBusinessFlags(
    expenseId: Long,
    isBusinessExpense: Boolean? = null,
    businessUsePercent: Double? = null,
    taxCategory: String? = null,
    vatEligible: Boolean? = null,
    receiptRequired: Boolean? = null,
    source: String = "BUSINESS_TAX_UPDATE"
): BusinessExpenseUpdateResult {
    return updateBusinessExpensePatch(
        expenseId = expenseId,
        patch = BusinessExpensePatch(
            isBusinessExpense = isBusinessExpense,
            requiresReceipt = receiptRequired,
            businessUsePercent = businessUsePercent,
            taxCategory = taxCategory,
            vatEligible = vatEligible
        ),
        source = source,
        reason = null,
        correlationId = null
    )
}
```

Important:

- Remove Timber warnings saying fields are ignored.
- Unsupported fields are now rejected and no expense mutation happens.
- Kotlin callers can ignore the returned result if they do not care, but tests should assert the result.

---

## Step C7 — Optional callsite migration

Search:

```bash
grep -R "updateBusinessFlags" app/src/main/java
```

For each callsite:

- If it only updates `isBusinessExpense` or `receiptRequired`, either keep wrapper or migrate to `updateBusinessExpensePatch`.
- If it passes `businessUsePercent`, `taxCategory`, or `vatEligible`, update UI/domain layer to handle `UnsupportedFields`.

Preferred new call:

```kotlin
transactionLifecycleCoordinator.updateBusinessExpensePatch(
    expenseId = expenseId,
    patch = BusinessExpensePatch(
        isBusinessExpense = true,
        businessPurpose = purpose,
        businessCategory = category,
        businessProject = project,
        requiresReceipt = true
    ),
    source = "USER_EDIT"
)
```

---

## PR C tests

Required tests:

```text
business_patch_empty_returns_NoChange
business_patch_empty_writes_no_UPDATED_event
business_patch_not_found_returns_NotFound

business_patch_updates_isBusinessExpense
business_patch_updates_requiresReceipt
business_patch_updates_businessPurpose
business_patch_updates_businessCategory
business_patch_updates_businessProject
business_patch_writes_UPDATED_event_with_changedFields
business_patch_dispatches_side_effects_after_commit

business_patch_same_values_returns_NoChange
business_patch_same_values_writes_no_UPDATED_event
business_patch_same_values_dispatches_no_side_effects

business_patch_businessUsePercent_returns_UnsupportedFields
business_patch_taxCategory_returns_UnsupportedFields
business_patch_vatEligible_returns_UnsupportedFields
business_patch_multiple_unsupported_fields_returns_all_fields
business_patch_unsupported_fields_do_not_mutate_expense
business_patch_unsupported_fields_write_UPDATE_VALIDATION_FAILED
business_patch_unsupported_fields_dispatch_no_side_effects

legacy_updateBusinessFlags_delegates_to_patch_api
legacy_updateBusinessFlags_receiptRequired_maps_to_requiresReceipt
legacy_updateBusinessFlags_unsupported_tax_fields_are_rejected_not_ignored
```

---

## PR C acceptance criteria

- `P2-CURRENT-014` TODO is removed.
- `updateBusinessFlags()` no longer silently ignores accepted parameters.
- Supported business fields persist:
  - `isBusinessExpense`,
  - `requiresReceipt`,
  - `businessPurpose`,
  - `businessCategory`,
  - `businessProject`.
- Unsupported legacy fields return `UnsupportedFields`.
- Unsupported legacy fields write `UPDATE_VALIDATION_FAILED`.
- No-op patches do not write fake `UPDATED` events.
- Actual patches write one `UPDATED` event and one post-commit side-effect batch.

---

# Combined validation checklist

Run after all PRs:

```bash
./gradlew compileDebugKotlin
./gradlew testDebugUnitTest
```

Targeted:

```bash
./gradlew testDebugUnitTest --tests '*StrictExternalDedupe*'
./gradlew testDebugUnitTest --tests '*InsertConflictResolution*'
./gradlew testDebugUnitTest --tests '*BusinessPatch*'
```

Manual grep checks:

```bash
grep -R "P2-CURRENT-012" app/src/main/java
grep -R "P2-CURRENT-005" app/src/main/java
grep -R "P2-CURRENT-014" app/src/main/java
grep -R "businessUsePercent.*ignored" app/src/main/java
grep -R "taxCategory.*ignored" app/src/main/java
grep -R "vatEligible.*ignored" app/src/main/java
```

Expected:

- no strict attempt-key TODO remains,
- no unresolved insert-race TODO remains unless replaced with narrower concurrency-test TODO,
- no business/tax ignored-field warnings remain.

---

# Definition of done

## P2-NEW-04 done when

- Strict external create attempt event uses `idem:{source}:{key}`.
- Strict external persisted expense uses the same key.
- Strict missing-key validation uses `dedupeKey = null`.
- Attempt, validation, duplicate, and created events share the same correlation ID.

## P2-NEW-05 done when

- Insert conflict path resolves existing ID by exact dedupe key first.
- Standard/BULK conflicts fall back to currency-aware duplicate lookup.
- Resolved conflicts write `CREATE_DUPLICATE_SKIPPED`.
- Resolved conflicts return `DuplicateSkipped(existingId)`.
- `CREATE_INSERT_CONFLICT` is written only for unresolved conflicts.

## P2-NEW-06 done when

- Business/tax update has an explicit patch/result contract.
- Unsupported fields are rejected, not ignored.
- Supported business fields persist.
- Rejected updates do not mutate expenses.
- Rejected updates write `UPDATE_VALIDATION_FAILED`.
- No-op updates write no fake `UPDATED`.

---

# Sources checked

- Commit: https://github.com/panospao7/Cost-agregator/commit/ad91767a9f30db77b6d4b6d8410d788eeaa610c0
- `TransactionLifecycleCoordinator.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt
- `ExpenseDao.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt
- `CreateExpenseRequest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt
- `CreateExpenseResult.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseResult.kt
- `LifecycleEventType.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/domain/transaction/LifecycleEventType.kt
- `Expense.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/ad91767a9f30db77b6d4b6d8410d788eeaa610c0/app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt