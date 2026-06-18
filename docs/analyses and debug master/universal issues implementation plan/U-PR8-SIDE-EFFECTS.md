# U-PR8 — Transaction Side-Effect Semantics

## 1. Issue Summary

| ID | Priority | Title |
|----|----------|-------|
| U-SIDEEFFECT-01 | P1 | Email receipt service dispatches transaction post-creation side effects AGAIN after coordinator already did |
| U-SIDEEFFECT-02 | P2 | `TransactionSideEffectPlanner` hardcodes `EXPENSE_CREATED` trigger type for merchant learning/stats actions even on UPDATE paths; idempotency key collision |

**Affected Pipelines:** 2, 11

## 2. Root Cause Analysis

### U-SIDEEFFECT-01
Examining `EmailReceiptIngestionService.processEmailReceipt()`:

The service's class-level KDoc explicitly states:
> "This service is a thin **parser/delegate** layer — it does NOT create expenses or receipts directly. All mutation is delegated to [ReceiptLifecycleCoordinator]. No post-commit side-effect dispatch happens in this service; the coordinator owns the complete dispatch lifecycle."

And in the success handling:
> "Side effects are dispatched by ReceiptLifecycleCoordinator — do NOT dispatch again here"

**Current code analysis:** The `EmailReceiptIngestionService` does NOT dispatch side effects. It delegates entirely to `receiptLifecycleCoordinator.processEmailReceipt()` and only emits diagnostic events on the result. The issue description appears to reference a **previously fixed** bug — the comments in the code are defensive documentation of the fix.

However, the issue may refer to the `ReceiptLifecycleCoordinator.processEmailReceipt()` itself dispatching side effects that overlap with what `TransactionLifecycleCoordinator.createExpense()` already dispatches internally. If the coordinator calls `createExpense()` (which dispatches side effects) AND THEN dispatches additional side effects, there's duplication.

**Verification needed:** Read `ReceiptLifecycleCoordinator.processEmailReceipt()` to confirm.

### U-SIDEEFFECT-02
In `TransactionSideEffectPlanner`:

**`makeMerchantCategoryLearningAction()`** (line ~170):
```kotlin
triggerType = SideEffectTriggerType.EXPENSE_CREATED,
idempotencyKey = "expense:$expenseId:created:merchant_category_learning",
```

**`makeMerchantCanonicalStatsAction()`** (line ~195):
```kotlin
triggerType = SideEffectTriggerType.EXPENSE_CREATED,
idempotencyKey = "expense:$expenseId:created:merchant_stats",
```

These are called from BOTH `planCreated()` AND `planUpdated()` (for FULL/MERCHANT/etc. update kinds). When called from `planUpdated()`:
- The `triggerType` is hardcoded to `EXPENSE_CREATED` even though the actual trigger is an UPDATE
- The `idempotencyKey` contains `:created:` even on update paths

**Consequences:**
1. **Semantic incorrectness:** Diagnostic events show `EXPENSE_CREATED` trigger for what was actually an update
2. **Idempotency key collision:** If an expense is created and then updated, both the creation side effect and the update side effect produce the SAME idempotency key (`expense:123:created:merchant_category_learning`). If the side-effect runner uses idempotency keys to deduplicate, the update's merchant learning action will be silently dropped as a "duplicate" of the creation's action.

## 3. Affected Files

| File | Changes Required |
|------|-----------------|
| `EmailReceiptIngestionService.kt` | Verify no duplicate dispatch (may be already fixed) |
| `TransactionSideEffectPlanner.kt` | Fix hardcoded trigger type and idempotency keys in merchant learning/stats actions |
| `TransactionSideEffectDispatcher.kt` | No changes needed (thin facade) |

## 4. Verification of Issues in Source

### U-SIDEEFFECT-01 — NOT CONFIRMED IN CURRENT CODE
The `EmailReceiptIngestionService` does NOT call `TransactionSideEffectDispatcher` or `TransactionSideEffectPlanner` directly. It delegates to `receiptLifecycleCoordinator.processEmailReceipt()` and the success handler only emits a diagnostic event. The class KDoc and inline comments confirm this was intentionally designed to avoid double-dispatch.

**Possible interpretation:** The issue may be that `ReceiptLifecycleCoordinator.processEmailReceipt()` internally calls `TransactionLifecycleCoordinator.createExpense()` which dispatches side effects, AND the coordinator also dispatches receipt-specific side effects that overlap (e.g., both trigger merchant learning). This would require reading `ReceiptLifecycleCoordinator` to confirm.

**Status:** Issue exists at the coordinator level, not in `EmailReceiptIngestionService` itself. The fix location is `ReceiptLifecycleCoordinator`.

### U-SIDEEFFECT-02 — CONFIRMED
- `makeMerchantCategoryLearningAction()` line ~170: `triggerType = SideEffectTriggerType.EXPENSE_CREATED` — hardcoded regardless of caller
- `makeMerchantCanonicalStatsAction()` line ~195: `triggerType = SideEffectTriggerType.EXPENSE_CREATED` — hardcoded regardless of caller
- `planUpdated()` calls both of these for FULL/MERCHANT/TYPE/etc. update kinds
- Idempotency keys use `:created:` literal in both methods

## 5. Implementation Plan

### U-SIDEEFFECT-01 Fix

**Strategy:** Verify the coordinator doesn't double-dispatch. If it does, ensure the coordinator uses `TransactionLifecycleCoordinator.createExpenseWithoutSideEffects()` (or equivalent) and handles all side effects itself.

Since the current `EmailReceiptIngestionService` code is already correct (no dispatch), the fix is to:
1. Verify `ReceiptLifecycleCoordinator.processEmailReceipt()` doesn't double-dispatch
2. If it does, refactor to use a create-without-side-effects path and dispatch once

**Minimal fix if double-dispatch exists in coordinator:**
```kotlin
// In ReceiptLifecycleCoordinator.processEmailReceipt():
// Use createExpenseRaw() (no side effects) instead of createExpense()
val expenseId = transactionLifecycleCoordinator.createExpenseRaw(request)
// Then dispatch side effects ONCE from the coordinator level
val batch = sideEffectPlanner.planCreated(expenseId, ExpenseSource.EMAIL_RECEIPT, correlationId)
postCommitActionRunner.run(batch)
```

### U-SIDEEFFECT-02 Fix

**Strategy:** Pass the trigger type as a parameter to `makeMerchantCategoryLearningAction()` and `makeMerchantCanonicalStatsAction()`, and derive the idempotency key from the actual trigger.

```kotlin
private fun makeMerchantCategoryLearningAction(
    expenseId: Long,
    source: String,
    correlationId: String,
    triggerType: SideEffectTriggerType  // ADD parameter
): PostCommitAction {
    return PostCommitAction(
        // ...
        triggerType = triggerType,  // FIX: was hardcoded EXPENSE_CREATED
        idempotencyKey = "expense:$expenseId:${triggerType.name.lowercase()}:merchant_category_learning",  // FIX
        // ...
    )
}

private fun makeMerchantCanonicalStatsAction(
    expenseId: Long,
    source: String,
    correlationId: String,
    triggerType: SideEffectTriggerType  // ADD parameter
): PostCommitAction {
    return PostCommitAction(
        // ...
        triggerType = triggerType,  // FIX: was hardcoded EXPENSE_CREATED
        idempotencyKey = "expense:$expenseId:${triggerType.name.lowercase()}:merchant_stats",  // FIX
        // ...
    )
}
```

**Update callers:**

```kotlin
// In planCreated():
fun planCreated(expenseId: Long, source: ExpenseSource, correlationId: String?): PostCommitActionBatch {
    val corrId = correlationId ?: CorrelationIds.newId()
    val actions = listOf(
        makeBudgetCheckAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_CREATED),
        makeAnomalyAlertAction(expenseId, source.name, corrId, SideEffectTriggerType.EXPENSE_CREATED),
        makeMerchantCategoryLearningAction(expenseId, source.name, corrId, SideEffectTriggerType.EXPENSE_CREATED),  // pass trigger
        makeMerchantCanonicalStatsAction(expenseId, source.name, corrId, SideEffectTriggerType.EXPENSE_CREATED),    // pass trigger
        makeRecurringMatchingAction(expenseId, source, corrId)
    )
    return PostCommitActionBatch(corrId, actions)
}

// In planUpdated() (FULL/MERCHANT/etc. branch):
actions.add(makeMerchantCategoryLearningAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_UPDATED))  // pass UPDATED
actions.add(makeMerchantCanonicalStatsAction(expenseId, source, corrId, SideEffectTriggerType.EXPENSE_UPDATED))    // pass UPDATED
```

This ensures:
1. Trigger type is semantically correct (CREATED vs UPDATED)
2. Idempotency keys are distinct between create and update paths (`expense:123:expense_created:merchant_category_learning` vs `expense:123:expense_updated:merchant_category_learning`)
3. Both the create and update side effects will execute (no false-positive dedup)

## 6. Execution Order

1. **U-SIDEEFFECT-02** (P2) — Fix hardcoded trigger types and idempotency keys (self-contained)
2. **U-SIDEEFFECT-01** (P1) — Verify/fix coordinator double-dispatch (requires reading ReceiptLifecycleCoordinator)

## 7. Testing Strategy

### Unit Tests

**For U-SIDEEFFECT-02:**
```kotlin
@Test
fun `planCreated uses EXPENSE_CREATED trigger for merchant learning`() {
    val batch = planner.planCreated(1L, ExpenseSource.MANUAL, "corr-1")
    val merchantAction = batch.actions.first { it.name == "merchant_category_pattern_learning" }
    assertEquals(SideEffectTriggerType.EXPENSE_CREATED, merchantAction.triggerType)
    assertTrue(merchantAction.idempotencyKey.contains("expense_created"))
}

@Test
fun `planUpdated uses EXPENSE_UPDATED trigger for merchant learning`() {
    val batch = planner.planUpdated(1L, "manual", "corr-1", TransactionUpdateKind.FULL)
    val merchantAction = batch.actions.first { it.name == "merchant_category_pattern_learning" }
    assertEquals(SideEffectTriggerType.EXPENSE_UPDATED, merchantAction.triggerType)
    assertTrue(merchantAction.idempotencyKey.contains("expense_updated"))
}

@Test
fun `create and update idempotency keys are distinct for same expense`() {
    val createBatch = planner.planCreated(1L, ExpenseSource.MANUAL, "corr-1")
    val updateBatch = planner.planUpdated(1L, "manual", "corr-2", TransactionUpdateKind.FULL)
    
    val createKey = createBatch.actions.first { it.name == "merchant_category_pattern_learning" }.idempotencyKey
    val updateKey = updateBatch.actions.first { it.name == "merchant_category_pattern_learning" }.idempotencyKey
    assertNotEquals(createKey, updateKey)
}
```

**For U-SIDEEFFECT-01:**
```kotlin
@Test
fun `email receipt processing does not dispatch side effects independently`() {
    // Process an email receipt
    val result = emailReceiptIngestionService.processEmailReceipt(...)
    // Verify TransactionSideEffectDispatcher was NOT called directly
    verify(sideEffectDispatcher, never()).dispatchOnCreated(any(), any(), any())
}
```

### Integration Tests
- Create expense via email receipt → verify merchant learning fires exactly once
- Create expense then update it → verify both create and update merchant learning fire (distinct keys)

## 8. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Changing idempotency keys causes previously-deduped actions to re-fire | Medium | Low | Only affects future actions; existing completed actions are not re-run |
| Removing double-dispatch breaks a side effect that only fired from the duplicate path | Low | Medium | Verify all side effects are covered by the single dispatch path |
| Trigger type change breaks downstream event consumers | Low | Low | Trigger type is metadata, not routing logic |

## 9. Rollback Plan

- U-SIDEEFFECT-02: Revert trigger type parameter; hardcode back to EXPENSE_CREATED (original behavior, with known key collision)
- U-SIDEEFFECT-01: If coordinator refactoring causes issues, revert to the double-dispatch path (side effects fire twice but are idempotent)

## 10. Dependencies

- No new dependencies
- `SideEffectTriggerType` enum already has `EXPENSE_CREATED` and `EXPENSE_UPDATED` values
- `PostCommitAction` already accepts `triggerType` as a parameter
- May need to read `ReceiptLifecycleCoordinator` for U-SIDEEFFECT-01 verification

## 11. Migration / Data Impact

- No database migration required
- No data format changes
- Historical `BackgroundJobRun` / diagnostic events with wrong trigger types are not retroactively fixed (acceptable — they're audit records)
- Future side-effect executions will have correct trigger types and distinct idempotency keys

## 12. Performance Impact

- Zero performance impact — same number of side effects fire, just with correct metadata
- If U-SIDEEFFECT-01 removes a double-dispatch, performance improves slightly (one fewer side-effect batch per email receipt)

## 13. Documentation Updates

- Add inline comment in `TransactionSideEffectPlanner` explaining the trigger type parameter contract
- Document the idempotency key format: `expense:{id}:{trigger_type_lowercase}:{action_name}`
- Update `EmailReceiptIngestionService` KDoc to reference this fix if coordinator changes are made

## 14. Acceptance Criteria

- [ ] `makeMerchantCategoryLearningAction()` accepts `triggerType` parameter (not hardcoded)
- [ ] `makeMerchantCanonicalStatsAction()` accepts `triggerType` parameter (not hardcoded)
- [ ] `planCreated()` passes `EXPENSE_CREATED` to both methods
- [ ] `planUpdated()` passes `EXPENSE_UPDATED` to both methods
- [ ] Idempotency keys include the actual trigger type (not hardcoded `:created:`)
- [ ] Create + Update for same expense produce distinct idempotency keys
- [ ] Email receipt processing dispatches side effects exactly once (no double-dispatch)
- [ ] All existing side-effect tests pass
- [ ] New tests verify trigger type correctness and key uniqueness
