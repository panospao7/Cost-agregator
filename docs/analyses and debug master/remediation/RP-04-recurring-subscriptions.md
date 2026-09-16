# RP-04: Recurring and Subscription Lifecycle Remediation

**Status:** implementation-ready, not implemented
**Scope:** Segment 7 recurring expenses, Segment 34 subscriptions, Segment 36 bill reminders, and their Segment 9 transaction-reconciliation hooks.
**Mode:** strict. Recurring rule mutation, reminders, lifecycle events, and date/amount/currency reconciliation are correctness-sensitive.

## Ground truth

- `RecurringRuleLifecycleCoordinator` is the single writer for recurring rule `create`, `update`, `activate`, `deactivate`, `delete`, and `advanceNextDate`. This is the legal path even when the caller is a subscription screen or subscription engine.
- `RecurringLifecycleCoordinator` owns occurrence generation, occurrence status transitions, expense link/unlink, reminder dispatch/revalidation, and linked-expense reconciliation.
- `RecurringOccurrence` has no foreign key to `ManualRecurringExpense`; `RecurringReminderDelivery` cascades only from an occurrence. Rule deletion therefore requires the coordinator purge, not a schema-only fix.
- `ManualRecurringExpense.nextDate` is the only persisted date anchor. There is no persisted original start date or anchor day. The current schema is v148. Do not claim that current `nextDate` restores an original day unless a separately approved schema migration adds that data.
- Occurrence identity is currently `sourceType|sourceId|dayStart|frequency`, with a unique index on `occurrenceKey`. Amount and currency are not identity fields.
- Occurrence terminal states are `PAID`, `SKIPPED`, `MISSED`, `CANCELLED`, and `IGNORED`. They must not be silently deleted, downgraded, or replaced by regeneration. `PAID` rows may carry a linked actual expense and payment snapshot.
- Planned rows are keyed by `sourceOccurrenceKey`; an open planned row must correspond to exactly one `PLANNED` occurrence. Reminders are keyed by occurrence and reminder window.

## Required outcome

Every recurring rule mutation, from every caller, must enter `RecurringRuleLifecycleCoordinator`. No repository, ViewModel, subscription engine, worker, migration helper, or DAO may directly insert, update, delete, activate, deactivate, or advance a `ManualRecurringExpense`.

The coordinator must perform the rule mutation and all affected occurrence, planned-expense, reminder, and critical lifecycle-event changes in one Room transaction. If any required write fails, the rule and all derived rows remain unchanged. Best-effort diagnostic events may not replace a required critical event.

## P4-001/002/007: close every rule write bypass

### Implementation

1. Inject `dagger.Lazy<RecurringRuleLifecycleCoordinator>` into `SubscriptionManagementRepository`, matching `ManualRecurringExpenseRepository` and `RecurringExpenseRepository`.
2. Replace `SubscriptionManagementRepository.updateSubscription()` with coordinator `updateRule()`. It must not call `ManualRecurringExpenseDao.update()`.
3. Replace `insertSubscription()` with coordinator `createRule()`. It must not call `ManualRecurringExpenseDao.insert()`.
4. Replace `deleteSubscriptionById()` with coordinator `deleteRule()` and route active-state changes through `activateRule()`/`deactivateRule()`.
5. Audit `SubscriptionManagerEngine.validateAndCreate()`, `acceptCandidate()`, `recordPriceChange()`, `NotificationSubscriptionDetector`, `ManualRecurringExpenseRepository`, `RecurringExpenseRepository`, and all ViewModels. Each rule insert/update must call the coordinator, directly or through a repository whose only write path is the coordinator.
6. Keep subscription-only price-history, usage, and candidate writes in their owning atomic operations, but update the recurring rule through `updateRule()` in the same transaction boundary or expose a coordinator operation that owns the complete combined transaction. Do not claim atomicity if the price-history transaction and rule reconciliation are separate.
7. Preserve the display-only `subscriptionCategory` behavior only after verifying no occurrence generator or matcher consumes it. If it is display-only, document that fact on the repository API; otherwise include it in the coordinator update.
8. Extend architecture guards to detect aliases and injected fields of `ManualRecurringExpenseDao`, not only one variable name. Forbid all production DAO mutators outside `RecurringRuleLifecycleCoordinator` and approved migration/debug code.
9. Use `RecurringLifecycleEventWriter.writeCritical()` for required recurring lifecycle provenance. Direct event-DAO inserts in the rule coordinator are either migrated to the writer or explicitly approved as the writer’s transaction implementation; they must not be duplicated across paths.

### Required behavior

- Delete removes the rule, all occurrences of that rule in every status, their deliveries, open/fulfilled planned rows as defined by the existing ownership contract, and writes one `RULE_DELETED` critical event atomically. It must not affect another rule with the same merchant or numeric ID.
- Deactivate sets the rule inactive and removes only the derived open planned state that the product contract defines as disposable. The current contract deletes `PLANNED` occurrences and planned rows rather than converting them to `CANCELLED`; retain that behavior only with explicit tests and KDoc. Terminal occurrences remain untouched.
- Reactivation regenerates only missing logical open occurrences and deliveries; it must not duplicate rows or recreate terminal occurrences.
- Missing-rule calls are idempotent only where the public contract says so. Do not report a successful mutation for a rule that was required to exist unless the existing API explicitly defines no-op behavior.

## P4-003/004: logical occurrence reconciliation

The existing “delete all PLANNED, then regenerate” algorithm is not sufficient. It can erase overdue obligations and can create duplicate or conflicting planned obligations after date/frequency edits. Implement one reconciliation operation inside `RecurringRuleLifecycleCoordinator.updateRule()` (or a private coordinator-owned service called only from that transaction).

### Logical identity

For reconciliation, define a logical occurrence as:

`(sourceType = RECURRING_RULE, sourceId = ruleId, recurrence slot, due local date)`.

The recurrence slot is the sequence position produced from the rule’s schedule and anchor. The persisted `occurrenceKey` remains a storage/deduplication key, but must not be the only matching rule when frequency or due date changes. The reconciler must build old and new slot maps and produce a one-to-one old-to-new mapping. A candidate is never inserted if its logical slot is already represented by any existing occurrence, planned row, or linked actual expense.

### State policy

- `PAID`, `SKIPPED`, `MISSED`, `CANCELLED`, and `IGNORED` are terminal. Preserve their row, status, lifecycle history, and linked expense. Never regenerate a terminal row as `PLANNED`.
- A `PAID` row linked to an expense remains linked even if the rule amount, currency, merchant, category, or future schedule changes. Its paid snapshot is historical; do not rewrite it from the new rule.
- An open `PLANNED` occurrence at or before the reconciliation reference day is overdue/past-due. Preserve it as an obligation and preserve its old expected amount/currency snapshot unless it is explicitly matched by a new logical slot. Do not delete it merely because the new regeneration start is today or later.
- Future open `PLANNED` occurrences may adopt the new rule snapshot only through the mapping. If a future slot is unchanged, update its expected amount/currency/merchant/category/date in place and reconcile its planned row and reminder set. If the slot moved, atomically retire the old derived open row and create the new one only when no terminal/link conflict exists.
- If an edit moves a new slot onto an overdue obligation, map them to one occurrence rather than retaining two planned obligations. If two existing rows map to one new slot, preserve the terminal row and keep at most one open row; record a controlled conflict diagnostic and stop if the data cannot be resolved without choosing between actual obligations.
- Amount changes update future open occurrence snapshots, planned rows, and reminder metadata together. Currency changes use the same rule: future open rows change atomically; paid snapshots and actual expense currency never change as a side effect of rule editing.
- Date and frequency changes must reconcile old slots before insertion. No “delete then expand” gap is allowed within the transaction, and no new row may coexist with an old open row for the same logical obligation.
- Existing reminders for preserved open occurrences are updated/replaced atomically by `(occurrenceId, reminderWindow)`. Sent/dismissed terminal delivery history is not replayed. Future scheduled/cancelled/transient deliveries may be reconciled; overdue delivery behavior follows the explicit `allowPastDueReminderDeliveries` option.
- A linked actual expense must be reconciled through `RecurringLifecycleCoordinator.reconcileExpenseLinkAfterUpdate()` or its bulk equivalent. Rule update must not unlink an actual expense merely because the rule snapshot changed. Expense amount/date/currency changes remain transaction-lifecycle inputs and must continue to trigger recurring reconciliation through `TransactionUpdateKind.affectsRecurringMatch()`.

### Transaction boundary

Within one `database.withTransaction`/`DomainTransactionRunner` operation:

1. Check the write barrier and load the old rule plus all occurrences, deliveries, planned rows, and relevant linked-expense references.
2. Validate the new rule and normalize date-only values.
3. Compute old/new logical slot maps and classify terminal, overdue open, future open, moved, conflicted, and new slots.
4. Apply the rule row update, occurrence updates/inserts/deletes permitted by the state policy, planned-row updates/inserts/fulfilment links, reminder reconciliation, and the critical lifecycle event.
5. Assert uniqueness/invariants before commit: one occurrence per logical slot, no open planned row without a `PLANNED` occurrence, no planned row for an inactive/deleted rule, and no reminder for a missing/non-planned occurrence.

On conflict, fail closed with a controlled result/diagnostic and roll back. Do not “best effort” by leaving both obligations.

## P4-005: month-end anchor semantics, reconciled with D7

Implement an additive calendar helper, for example `TimePeriodUtils.advanceMonthAnchor(anchorDayOfMonth, fromMs, months)`, and use it in recurring expansion and `RecurrenceCalculator` for monthly, quarterly, and semi-annual calendar intervals. Keep generic `TimePeriodUtils.addMonths()` unchanged because other callers rely on its clamping semantics.

The no-schema policy is **Option A**:

- Derive `anchorDayOfMonth` once from the `anchorDate` passed to one expansion operation.
- Advance every subsequent period from that fixed day, clamped to the target month’s length. Thus Jan 31 -> Feb 28/29 -> Mar 31, rather than Jan 31 -> Feb 28 -> Mar 28.
- Apply the same rule to `advanceDate()` and `RecurrenceCalculator.addFrequencyInterval()` so expansion, next-date calculation, and projection agree.
- Weekly and annual behavior remains unchanged except for shared date normalization.

This does **not** restore an original anchor for an already-drifted persisted rule: the schema has no original anchor day. If `nextDate` is already February 28 after historical drift, Option A necessarily derives 28. D7 must say “prevents new drift from the current expansion anchor,” not “snaps existing rules back to their original day.” Restoring an original day is a separate schema migration requiring an anchor field, database version/migration/snapshot updates, and approval. Do not add that migration in RP-04.

Historical occurrence rows and keys are immutable. Corrected future keys may coexist with historical keys, but the logical reconciler must prevent duplicate open obligations in the active window.

## P4-006: reminder failure status

Stop emitting `FAILED_PERMISSION` from `RecurringLifecycleCoordinator.markReminderFailed()`. Permission denial is a notification-posting failure, not a recurring-rule failure: transition the delivery to the existing terminal/cancellable status (`CANCELLED`) with controlled reason code `PERMISSION_REVOKED`, and write a sanitized diagnostic. Catch and rethrow `CancellationException` wherever a broad exception handler remains. If data inspection finds historical `FAILED_PERMISSION` rows, include that literal in terminal-status handling; do not create a new live/retry path for it.

Reminder dispatch must continue to check permission locally, catch `SecurityException`, and count a notification only after a successful post. Notification denial must not block occurrence generation, expense reconciliation, or database repair.

## Dead-code and latent-code disposition

- `RecurringPlanProjectionService.projectFromRule()` has no production caller and uses a separate non-atomic coordinator-plus-insert path. Delete this method only after proving the caller inventory is still empty. Keep `projectFromOccurrencesInCurrentTransaction()` because the rule coordinator uses it.
- Remove stale `SynthesisEngine.kt` KDoc that claims to use `projectFromRule()`.
- `RecurringLifecycleCoordinator.ensureOccurrencesGeneratedForReconciliation()` (or equivalent past-window helper) is dead if the production call-site audit remains empty. Do not delete it until tests and grep prove no caller; then either remove it or make it the coordinator-owned primitive used by the new reconciler, not a second generation path.
- `RecurringLifecycleFixesTest.kt` is an empty ignored stub. Delete it in the designated RP-20 test-hygiene change, or move its specific cases into the named lifecycle tests before deletion. Never use it as evidence of coverage.
- Keep `RecurringExpenseDao` only if its read/deprecation compatibility is required. Its mutation surface must remain forbidden and unreferenced by production rule writes.
- Do not delete `advanceNextDate()`, `projectOccurrences()`, or typed occurrence status APIs: they have distinct legal read/update responsibilities. Audit each caller before changing contracts.

## Exact test plan

Add or update tests before implementation is considered complete.

### Architecture and routing

- `RecurringArchitectureGuardTest.allRecurringRuleMutationsRouteThroughRuleLifecycleCoordinator`
- `RecurringArchitectureGuardTest.subscriptionDaoAliasCannotMutateManualRecurringExpense`
- `RecurringArchitectureGuardTest.noProductionRecurringRuleDaoMutationOutsideCoordinator`
- `SubscriptionManagementRepositoryTest.updateSubscriptionDelegatesToUpdateRule`
- `SubscriptionManagementRepositoryTest.insertSubscriptionDelegatesToCreateRule`
- `SubscriptionManagementRepositoryTest.deleteSubscriptionDelegatesToDeleteRule`
- `SubscriptionManagementRepositoryTest.setActiveDelegatesToActivateOrDeactivateRule`
- `SubscriptionManagerEngineTest.validateAndCreateUsesCoordinatorRuleCreation`
- `SubscriptionManagerEngineTest.acceptCandidateUsesCoordinatorRuleCreation`
- `SubscriptionManagerEngineTest.priceChangeCannotLeaveRuleAndPriceHistoryPartiallyUpdated`

### Atomic rule lifecycle

- `RecurringRuleLifecycleCoordinatorTest.deleteRuleRemovesAllStatusesDeliveriesAndPlannedRowsAndWritesRuleDeleted`
- `RecurringRuleLifecycleCoordinatorTest.deactivateRemovesOpenDerivedStateButPreservesTerminalOccurrences`
- `RecurringRuleLifecycleCoordinatorTest.activateRegeneratesMissingOpenStateWithoutDuplicates`
- `RecurringRuleLifecycleCoordinatorTest.ruleMutationRollsBackRuleDerivedRowsAndCriticalEventOnFailure`
- `SubscriptionManagementViewModelTest.deleteAndToggleUseLifecycleRepositoryPath`

### Logical reconciliation

- `RecurringRuleLifecycleCoordinatorTest.updateAmountPreservesOverdueOpenOccurrenceAndUpdatesFutureOpenOccurrences`
- `RecurringRuleLifecycleCoordinatorTest.updateCurrencyPreservesPaidSnapshotAndAtomicallyUpdatesFutureRows`
- `RecurringRuleLifecycleCoordinatorTest.updateDateMapsMovedOpenSlotWithoutDuplicatePlannedObligation`
- `RecurringRuleLifecycleCoordinatorTest.updateFrequencyMapsSlotsWithoutDuplicateOrConflictingOpenRows`
- `RecurringRuleLifecycleCoordinatorTest.updatePreservesPaidSkippedMissedCancelledAndIgnoredOccurrences`
- `RecurringRuleLifecycleCoordinatorTest.updatePreservesLinkedExpenseAndDoesNotRewritePaidSnapshot`
- `RecurringRuleLifecycleCoordinatorTest.updateReconcilesPlannedRowsAndReminderWindowsAtomically`
- `RecurringRuleLifecycleCoordinatorTest.overdueOccurrenceRemainsClaimableAndReminderPolicyIsExplicit`
- `RecurringRuleLifecycleCoordinatorTest.logicalSlotCollisionFailsClosedAndRollsBack`
- `RecurringRuleLifecycleCoordinatorTest.repeatedIdenticalUpdateIsIdempotent`
- `RecurringLifecycleCoordinatorTest.expenseAmountDateCurrencyUpdateReconcilesLinkedOccurrenceExactlyOnce`
- `RecurringLifecycleCoordinatorTest.unlinkAfterExpenseDeleteReopensOnlyTheLinkedOccurrence`
- `RecurringPlannedActualNoDoubleCountGoldenTest.ruleUpdateDoesNotDoubleCountPlannedAndActual`
- `RecurringBillPaymentMatchTest.updatedRuleStillMatchesTheCorrectOccurrence`
- `ConcurrentOccurrenceClaimTest.concurrentReconcileCannotClaimOneOccurrenceTwice`

### Anchor and date behavior

- `RecurringOccurrenceExpanderTest.monthlyJan31UsesFixedExpansionAnchorAcrossLeapYear`
- `RecurringOccurrenceExpanderTest.quarterlyJan31ClampsEachTargetMonthWithoutCumulativeDrift`
- `RecurringOccurrenceExpanderTest.semiAnnualAnchorDoesNotDrift`
- `RecurringOccurrenceExpanderTest.expansionFromPersistedDriftedNextDateDoesNotClaimOriginalAnchorRestoration`
- `RecurrenceCalculatorTest.monthAndQuarterIntervalsMatchOccurrenceExpander`
- `RecurringOccurrenceExpanderTest.dateBoundaryIsHalfOpenAndDateOnly`

### Reminders and cancellation safety

- `BillReminderWorkerTest.permissionDenialCancelsDeliveryWithoutBlockingCoreRecurringWork`
- `RecurringLifecycleCoordinatorTest.permissionFailureUsesControlledCancellationReason`
- `RecurringLifecycleCoordinatorTest.historicalFailedPermissionDeliveryIsTerminal`
- `BillReminderWorkerTest.postClaimRevalidationSuppressesReminderAfterPaymentOrCancellation`
- `RecurringLifecycleCoordinatorTest.cancellationExceptionPropagatesFromReconciliation`

### Invariant/property checks

- After every create/update/activate/deactivate/delete test, assert no duplicate `occurrenceKey`, no duplicate logical open slot, no orphan open planned row, no delivery for a missing/non-planned occurrence, and no active derived state for a deleted/inactive rule.
- Assert event type, old/new status, and controlled metadata for every critical mutation. Do not assert raw exception messages or user financial payloads in diagnostics.

## Stop conditions

Stop the slice and request architecture review if any of the following occurs:

- A production rule mutation still writes `ManualRecurringExpenseDao` directly or bypasses `RecurringRuleLifecycleCoordinator`.
- The implementation requires changing `RecurringOccurrenceStatus` terminal semantics, deleting linked actual expenses, or rewriting paid snapshots without an approved product decision.
- A date/frequency/amount/currency update can leave two open rows for one logical slot, an orphan planned row, or a reminder whose occurrence is no longer dispatchable.
- Overdue/past-due obligations would be deleted or silently changed without an explicit reconciliation rule and test.
- A conflict cannot be resolved without choosing between two actual/terminal obligations; fail closed and preserve the transaction rather than guessing.
- Anchor restoration requires an unapproved schema change, or documentation claims restoration not supported by persisted data.
- A Room schema change becomes necessary. Stop for version, migration, schema-snapshot, backup/restore, and migration-test review; RP-04 Option A is no-schema.
- A required critical lifecycle event is best-effort, an exception handler swallows `CancellationException`, or notification permission blocks unrelated recurring/database work.
- Existing tests depend on the old delete-all behavior in a way that contradicts terminal preservation or logical reconciliation. Update the contract deliberately; do not weaken assertions.

## Sequencing

1. **Discovery gate:** inventory every recurring rule mutator and caller; update the architecture guard first. Confirm the dead-code call-site results and whether any Room schema change is actually required.
2. **Routing slice:** add coordinator injection and route subscription create/update/activate/deactivate/delete/next-date paths. Land routing tests and guards before semantic changes.
3. **Atomic reconciliation slice:** implement logical slot mapping, terminal/overdue policy, planned-row/reminder reconciliation, invariant assertions, and rollback tests. Keep all writes inside the rule coordinator transaction.
4. **Transaction crossover slice:** verify expense create/update/delete hooks use `RecurringLifecycleCoordinator` detailed reconciliation and preserve cancellation/claim semantics. Run recurring golden and concurrency tests.
5. **Anchor slice:** add the fixed-anchor helper and update expander/calculator together. Land leap-year, quarterly, semi-annual, boundary, and already-drifted-rule tests. Do not alter generic `addMonths()`.
6. **Reminder/dead-code slice:** close permission status handling, cancellation propagation, stale-status compatibility, and approved dead-code cleanup. Remove stale documentation only after the implementation and tests pass.
7. **Review gate:** inspect `git diff`, changed files, architecture docs, all affected tests, event/privacy boundaries, and migration implications. Only then run the focused Gradle command listed below.

## Validation

Not run by this documentation-only change. Do not run Gradle during this slice. After implementation and review, run the exact focused suite:

```text
./gradlew :app:testDebugUnitTest --tests "*RecurringRuleLifecycleCoordinator*" --tests "*RecurringLifecycleCoordinator*" --tests "*RecurringOccurrenceExpander*" --tests "*RecurrenceCalculator*" --tests "*SubscriptionManagement*" --tests "*RecurringArchitectureGuard*" --tests "*BillReminderWorker*" --tests "*RecurringPlannedActualNoDoubleCount*" --tests "*RecurringBillPaymentMatch*" --tests "*ConcurrentOccurrenceClaim*" --console=plain
```

If and only if implementation introduces a Room schema change, stop the no-schema plan and additionally require the repository’s migration, schema snapshot, backup/restore, and migration-test procedure before acceptance.
