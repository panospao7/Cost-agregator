# Category delete and rename: plan (feature-planner, read at 80251ea3). Tier T3, no schema change.

Budgets on a deleted category MERGE into the target, using the existing `CategoryDao.deactivateConflictingBudgets` then `reassignBudgetsToCategory` (:126-139).

## C1 domain/data (Sonnet writer, separate test-author)
- **TLC:** add `suspend fun reassignCategoryBulkDbOnly(fromId, toId, source="USER_EDIT", correlationId=null): MutationResult<Int>`, following the DbOnly pattern of `setSpendingExclusionDbOnly` (~:2662).
  - `checkWritesAllowed`, then `runWrite` + `withTransaction`, then `expenseDao.updateCategoryForCategory` (ExpenseDao:361).
  - Write one BULK_UPDATED event only if the count is above 0. Metadata: fromCategoryId, toCategoryId, affectedCount (no merchant names).
  - Return the post-commit batch from `planner.planBulkUpdated(setOf(CATEGORY))`.
  - Add `suspend fun runPostCommit(batch)`: best effort, rethrows cancellation, modelled on `dispatchBulkPostCommitSideEffects` (~:2830).
- **CategoryDao:**
  - Remove `reassignExpensesToCategory` (:123).
  - Replace `mergeCategories` with a @Transaction `reassignAllReferencesExceptExpenses(source, target)`. Budget order: deactivate conflicts, then reassign. MerchantCategory moves before the CASCADE.
  - New reassign* queries: manual_recurring_expenses, installment_plans, bank_connections.defaultCategoryId, user_corrections (both columns), receipt_item_categorizations.userCorrectedCategoryId.
  - `updateNameById`, and a read-only `countExpensesForCategory`.
- **CategoryRepository:**
  - Inject `dagger.Lazy<TLC>`.
  - `deleteCategory(id, replacementId): DeleteCategoryResult`, adding ReplacementInvalid and SameAsSource.
  - `renameCategory(id, name): RenameCategoryResult` with Renamed, EmptyName, InvalidCharacters, Duplicate (NOCASE), CannotRenameDefault, NotFound.
  - Delete and merge share one path: `runWrite` + `withTransaction { TLC DbOnly; DAO reassigns; delete }`. After the commit: `TLC.runPostCommit`, `invalidateCache`, `invalidateCategorySnapshot`. Cancellation is rethrown.
- **Docs and config:** LEGAL_PATHS (the delete, rename and merge flow). Policy rows for the new TLC and CategoryRepository methods; update the pins (DbGuardPolicyFixtureTest:1778, test_verify_db_access_boundaries).
- **Tests:**
  - CategoryDaoTest (Room): every referencing table moves, plus the budget conflict case.
  - CategoryRepositoryTest: rename cases, default protection, invalidation, barrier.
  - New TransactionLifecycleCoordinatorCategoryReassignTest: one event, no merchant text, no event when 0 rows, post-commit only after the commit, rollback on failure, cancellation.
- **Filter:** `*CategoryDaoTest,*CategoryRepositoryTest,*TransactionLifecycleCoordinatorCategoryReassignTest,*DbGuardPolicyFixtureTest,*CategoryViewModelWriteBlockedTest`, plus the db_access pytest, db_access, and static-guards.

## C2 UI (one Sonnet writer, who also writes the tests)
- CategoryScreen: an overflow menu on non-default rows, RenameCategoryDialog with inline errors, and DeleteCategoryDialog with the expense count and a replacement picker (default Uncategorized, source excluded).
- CategoryViewModel: `renameCategory`, `requestDelete`, `confirmDelete`, through `guardedWrite`.
- `strings_category_manage.xml` (~12 keys), with el/es DRAFT.
- Tests: new CategoryViewModelManageTest, and extend CategoryViewModelWriteBlockedTest.

Deferred: CSV import (B), icon and colour editing, undo.
