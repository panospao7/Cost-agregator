# S6c discovery (scout, 2026-10-26, read-only, nothing compiled)

Paths are relative to app/src/main/java/com/yourname/expensetracker/ unless noted. Flagged needs-confirm where the scout did not verify.

## 1. TLC post-commit hook
- `domain/transaction/lifecycle/TransactionSideEffectPlanner.kt`
  - It is a @Singleton that already injects `Lazy<RecurringLifecycleCoordinator>` (:28).
  - Hook points: `planUpdated(expenseId, source, correlationId, kind)` :53-95 and `planDeleted` :97-108. `planBulkUpdated` :110 has no per-expense hook.
  - Precedents: `makeRecurringReconcileAction` :350-389 and `makeRecurringUnlinkAction` :391-426. Both are a PostCommitAction lambda that rethrows CancellationException and returns FailedRetryable on other exceptions.
  - `SideEffectCategory` has no INSTALLMENT value.
- Update kinds: `LOCATION_ONLY` gives an empty batch, and SPENDING_INCLUSION / CATEGORY_ONLY / BUSINESS_FLAGS_ONLY get no recurring action. The installment hook therefore needs its own kind gate, covering type and currency changes (R4).
- Callers:
  - TLC calls `planUpdated` at about 10 sites and `planDeleted` at :3077.
  - `TransactionSideEffectDispatcher` :51/60/65 wraps the planner calls.
  - `GroupTransactionCoordinator` :1021 uses `planBulkUpdated`.
- Coordinator entry points (S6a): `onExpenseChanged(expenseId)` :1039, `onExpenseDeleted(expenseId)` :1046 and `reconcile()` :1068.
  - Each returns `Done(report, batches)`. The caller MUST dispatch the `Done.postCommit` batches.
  - Each calls `writeBarrier.checkWritesAllowed` first.
- Loop risk: the coordinator's own TLC DbOnly calls emit `planUpdated` / `planCreated`, so the hook fires on the coordinator's own writes. `reconcileForExpense` is idempotent, but this needs a test.
- Use `Lazy` for the planner → coordinator dependency, because the coordinator depends on TLC.

## 2. Recurring link policy
- `domain/schedule/ExpenseScheduleLinkPolicy.kt` exists. It is a pure object.
  - API: `mayLinkToRecurringOccurrence(occurrenceId, relations)` :153, `evaluateLink`, `evaluateInstallmentLink`, `evaluateDeclaration`.
  - `ExpenseScheduleRelations(linkedInstallmentEntryId, linkedRecurringOccurrenceId, isPlanPurchaseRecord)`.
- `RecurringLifecycleCoordinator` has zero references to the policy. Its link sites:
  - auto `linkExpenseToOccurrence` :351;
  - manual `linkExpenseToSpecificOccurrence` :405;
  - both run through `claimLinkSuppressInWrite` :518-623 (`runWrite { withTransaction }`);
  - `reconcileExpenseLinkAfterUpdate` :683.
- The single minimal gate is the private `isExpenseEligibleForRecurring` :799-805.
- The policy needs the installment entry link, which is a new read inside the recurring transaction. The recurring coordinator has no installment DAO or reader today. The S6a seam is `InstallmentExpenseFactsReader` (Impl in data/repository). Whether it can be injected into the recurring coordinator is needs-confirm.
- The purchase-record side comes from `spendingExclusionReason` on the row that is already loaded.

## 3. D-15: pattern detector
- There is no class named "detector". The detector is `domain/logic/RecurringExpenseEngine.kt`.
  - `getPatterns()` :33 calls `expenseRepository.getExpenseSnapshotsSince` (ExpenseRepository :980, which uses ExpenseDao.getExpensesSince :284).
  - The pipeline continues through `getPatternsFromSnapshots` :44 and `detectPatternsFromSnapshots` :82.
  - The only candidate filter is PURCHASE.
- `ExpenseSnapshot` has no link or exclusion field.
  - Plain paid installment payments carry no flag on the expense row.
  - The only marker is `installment_entries.linkedExpenseId`.
  - So the exclusion needs the set of entry-linked expense ids. That is a new read, either a query plus a db_raw_query_classification entry or a reader method.
- `AnalyticsViewModel` (~608) passes its own snapshots to `getPatternsFromSnapshots`. A repository-query-only filter would therefore miss it, so the filter belongs in the engine.
- Other engine consumers: MergedRecurringPatternsProvider :30, InsightsEngine, FinancialHealthScoreV2 and RecurringExpensesScreen.
- `RecurringPlanProjectionService` has one production consumer, `RecurringRuleLifecycleCoordinator` :46. The weather card uses `MergedRecurringPatternsProvider`.
- The output contract (`RecurringPattern`) can stay unchanged.

## 4. D-18: restore
- `data/repository/DatabaseBackupRepositoryImpl.restoreCostBackup` :1021 goes through the modes PREPARING → STAGING → SWAPPING → VERIFYING (BackupVerifier :1352 on a fresh Room instance) → ASSETS_RESTORING → `exit(forceRestartRequired = true)` :1398 → SuccessNeedsRestart.
- `importDatabase` :1915 follows the same flow.
- The barrier blocks coordinator writes in every non-NORMAL mode (DatabaseWriteBarrier :30-39). Restore does not go through TLC, and the process restarts afterwards.
- So the post-restore reconcile can only run after the restart.
- `recordDueEntries` does not exist yet (S7a). LEGAL_PATHS :905 and :1669 say "reconcile after restore", but there is no hook in source.

## 5. Owner item 17 / F8: backup pre-reconcile
- `DatabaseBackupRepositoryImpl.runCostBackupExport` :621 (shared by createCostBackup :552/:586) runs in this order:
  1. the privacy gate (ends ~:670);
  2. `maintenanceOperationRunner.enterAndDrain(BACKUP_EXPORTING, failOnTimeout = true)` :678;
  3. the WAL checkpoint :685;
  4. the snapshot :745;
  5. `collectTableCountsStrict` :769;
  6. `BackupVerifier.verify` :781 (the orphan checks are at BackupVerifier ~:544-556).
- The only workable insertion point is between ~:670 and :678, while the mode is still NORMAL.
  - Once BACKUP_EXPORTING is entered, `checkWritesAllowed` throws.
  - `enterAndDrain` → `checkNotInsideCountedWrite` (MaintenanceOperationRunner :46) throws MAINTENANCE_DRAIN_REENTRANT, so reconcile must return before `enterAndDrain` and must not run inside a runWrite.
- Barrier tension: this point is just before the barrier, not under it. A write between the reconcile and the drain can reintroduce a transient orphan, and the verify then fails closed. The F8 handoff note (:566) already reads item 17 this way.
- `DatabaseBackupRepositoryImpl` has no coordinator dependency today. It needs `Lazy` injection, and the secondary test ctor (:112-147) must be updated.
- Other maintenance entries: `exportDatabase` :378/:435 (debug raw path) and `createSafetyBackup` :2849. Coverage of these is undecided.
- Failure policy: the report fields `plansFailed` / `orphanFlagsFailed` > 0 mean "not reconciled" (handoff :644).

## 6. Guards and docs
- Calling the coordinator from the planner or the backup repo adds no DAO writes there. Likely no new db_ownership_policy rows; pins stay at 452 (needs-confirm).
- A new read query needs a `config/guards/db_raw_query_classification.yml` entry.
- `InstallmentPlanArchitectureGuardTest` scans only domain/installment, service/installment and ui/screens/installments.
- LEGAL_PATHS :899-907, :1667 and :1669 describe "planned S6c". Lines are missing for:
  - the backup pre-reconcile;
  - the recurring policy consultation;
  - the detector exclusion.
- ENGINE_INTERACTION_MAP and CODEBASE_SEGMENTS were not read.

## 7. Plan write-set mismatches
1. The restore hook "before the first recordDueEntries" has no site yet (S7a).
2. The backup pre-reconcile (`DatabaseBackupRepositoryImpl`, data layer) is not in the S6c write set.
3. The detector exclusion needs a new read and engine input, which are not in the write set.
4. The planner needs a coordinator `Lazy` dependency and a SideEffectCategory choice.
