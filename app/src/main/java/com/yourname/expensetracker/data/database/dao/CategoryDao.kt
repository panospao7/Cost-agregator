package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.Category
import kotlinx.coroutines.flow.Flow

/**
 * Data-access object for [Category] entities.
 *
 * ## BUD-28: Category name uniqueness
 * Case-insensitive get-or-create is owned by [getOrInsertByNameNoCase], used
 * by the category repository. Room does not impose name uniqueness on raw
 * inserts: the historical supplemental `name COLLATE NOCASE` index was not
 * part of the entity schema and is absent from current supported schemas.
 * The [getByName] and [existsByName] queries use `COLLATE NOCASE` for
 * case-insensitive lookups.
 *
 * See also [Category.normalizedName] for the computed lowercase+trimmed form.
 */
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllFlow(): Flow<List<Category>>
    
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Category>

    /**
     * Direct insert that bypasses category name normalization.
     * Prefer [com.yourname.expensetracker.data.repository.CategoryRepository.normalizeAndInsert]
     * to ensure consistent naming.
     */
    @Deprecated("Use CategoryRepository.normalizeAndInsert() instead to apply name normalization")
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long
    
    @Update
    suspend fun update(category: Category)

    /**
     * BUD-30: Protected default categories. Before deleting, checks if the
     * category has `isDefault == true` and throws if so to prevent accidental
     * removal of system-default categories.
     *
     * Since Room's @Delete annotation cannot contain guard logic, this is
     * implemented as a @Transaction method that first reads the category
     * from the database and validates it.
     */
    @Transaction
    suspend fun delete(category: Category) {
        val existing = getById(category.id)
        require(existing != null) { "Category not found: id=${category.id}" }
        require(!existing.isDefault) { 
            "Cannot delete default category '${existing.name}' (id=${existing.id}). " +
            "Default categories are protected from deletion."
        }
        deleteInternal(category)
    }

    @Delete
    suspend fun deleteInternal(category: Category)
    
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCount(): Int
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>)

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): Category?

    /**
     * Atomically returns the existing case-insensitive category or inserts [category].
     *
     * This keeps the repository-level check-then-insert race-free even on upgraded
     * databases where the historical supplemental NOCASE unique index may not be
     * present in Room's entity schema.
     */
    @Transaction
    suspend fun getOrInsertByNameNoCase(category: Category): Category {
        getByName(category.name)?.let { return it }
        val id = insert(category)
        if (id > 0L) return category.copy(id = id)

        // If a DB-level unique constraint or another serialized writer won the
        // race, return the row that now exists instead of leaking id=0/-1.
        return getByName(category.name)
            ?: error("Category insert was ignored but no existing category was found for '${category.name}'")
    }

    @Query("SELECT COUNT(*) FROM categories WHERE name = :name COLLATE NOCASE")
    suspend fun existsByName(name: String): Int

    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    suspend fun getAll(): List<Category>

    /**
     * Atomically seed defaults if the table is empty.
     * Returns true if categories were actually inserted, false if the table was non-empty.
     *
     * B4: replaces the racy getCount() → insertAll() two-step in [CategoryRepository].
     * The @Transaction annotation ensures the check-then-insert is serialized under
     * Room's transaction lock, preventing duplicate seeding under concurrency.
     */
    @Transaction
    suspend fun seedDefaultsIfEmpty(defaults: List<Category>): Boolean {
        if (getCount() > 0) return false
        insertAll(defaults)
        return true
    }

    // ── Category rename / read-only helpers ───────────────────────────────────

    /**
     * Renames the category row. The caller owns trimming, validation and the
     * case-insensitive duplicate check (it runs inside the same transaction).
     * Returns the number of rows updated.
     */
    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun updateNameById(id: Long, name: String): Int

    /**
     * Read-only count of expenses currently assigned to [categoryId], used to
     * tell the user how many expenses a delete will move. Never a write path:
     * expense rows are re-assigned only through TransactionLifecycleCoordinator.
     */
    // spending-exempt: ROW_COUNT
    @Query("SELECT COUNT(*) FROM expenses WHERE categoryId = :categoryId")
    suspend fun countExpensesForCategory(categoryId: Long): Int

    // ── Category reference reassignment helpers (used by reassignAllReferencesExceptExpenses) ──

    @Query("""
        UPDATE budgets SET isActive = 0, activeOverallKey = NULL, activeCategoryKey = NULL
        WHERE categoryId = :sourceCategoryId AND isActive = 1
        AND EXISTS (SELECT 1 FROM budgets b2 WHERE b2.categoryId = :targetCategoryId AND b2.isActive = 1)
    """)
    suspend fun deactivateConflictingBudgets(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("""
        UPDATE budgets SET 
            categoryId = :targetCategoryId,
            activeCategoryKey = CASE WHEN isActive = 1 THEN :targetCategoryId ELSE NULL END
        WHERE categoryId = :sourceCategoryId
    """)
    suspend fun reassignBudgetsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE budget_adjustment_recommendations SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignBudgetAdjustmentsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE merchant_categories SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignMerchantCategoriesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE pending_reviews SET suggestedCategoryId = :targetCategoryId WHERE suggestedCategoryId = :sourceCategoryId")
    suspend fun reassignPendingReviewsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE receipt_item_categorizations SET suggestedCategoryId = :targetCategoryId WHERE suggestedCategoryId = :sourceCategoryId")
    suspend fun reassignReceiptItemCategorizationsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE recurring_occurrences SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignRecurringOccurrencesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE planned_expenses SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignPlannedExpensesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE spending_challenges SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignSpendingChallengesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE merchant_canonicals SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignMerchantCanonicalsToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE manual_recurring_expenses SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignManualRecurringExpensesToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE installment_plans SET categoryId = :targetCategoryId WHERE categoryId = :sourceCategoryId")
    suspend fun reassignInstallmentPlansToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE bank_connections SET defaultCategoryId = :targetCategoryId WHERE defaultCategoryId = :sourceCategoryId")
    suspend fun reassignBankConnectionDefaultCategoryToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE user_corrections SET originalCategoryId = :targetCategoryId WHERE originalCategoryId = :sourceCategoryId")
    suspend fun reassignUserCorrectionOriginalCategoryToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE user_corrections SET correctedCategoryId = :targetCategoryId WHERE correctedCategoryId = :sourceCategoryId")
    suspend fun reassignUserCorrectionCorrectedCategoryToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    @Query("UPDATE receipt_item_categorizations SET userCorrectedCategoryId = :targetCategoryId WHERE userCorrectedCategoryId = :sourceCategoryId")
    suspend fun reassignReceiptItemUserCorrectedCategoryToCategory(sourceCategoryId: Long, targetCategoryId: Long): Int

    /**
     * Moves every reference to [sourceCategoryId] EXCEPT expense rows over to
     * [targetCategoryId]: budgets, budget adjustment recommendations, merchant
     * mappings and canonicals, pending reviews, receipt item categorizations
     * (suggested and user-corrected), recurring occurrences, manual recurring
     * expenses, installment plans, planned expenses, spending challenges,
     * bank connection defaults, and user corrections (both columns).
     *
     * Expense rows are deliberately NOT touched here: they move only through
     * `TransactionLifecycleCoordinator.reassignCategoryBulkDbOnly` (lifecycle
     * event plus post-commit side effects). The repository calls both inside
     * one transaction, then deletes the source.
     *
     * **Budget conflict resolution:** budgets MERGE into the target. If both
     * the source and target have an active budget, the source's active budget
     * is deactivated first (to respect the unique active-category key), then
     * all remaining source budgets are re-pointed at the target. Merchant
     * mappings move before the source delete so the CASCADE cannot drop them.
     *
     * Runs in a single database transaction (joins an outer one when present).
     *
     * @throws IllegalArgumentException if either ID is missing or they are equal.
     */
    @Transaction
    suspend fun reassignAllReferencesExceptExpenses(sourceCategoryId: Long, targetCategoryId: Long) {
        require(sourceCategoryId != targetCategoryId) {
            "Cannot reassign a category into itself (id=$sourceCategoryId)"
        }
        require(getById(sourceCategoryId) != null) {
            "Source category not found: id=$sourceCategoryId"
        }
        require(getById(targetCategoryId) != null) {
            "Target category not found: id=$targetCategoryId"
        }

        // Budgets: deactivate conflicting source budgets first, then move the rest.
        deactivateConflictingBudgets(sourceCategoryId, targetCategoryId)
        reassignBudgetsToCategory(sourceCategoryId, targetCategoryId)

        // Merchant mappings move before the source delete (FK CASCADE would drop them).
        reassignMerchantCategoriesToCategory(sourceCategoryId, targetCategoryId)

        reassignBudgetAdjustmentsToCategory(sourceCategoryId, targetCategoryId)
        reassignPendingReviewsToCategory(sourceCategoryId, targetCategoryId)
        reassignReceiptItemCategorizationsToCategory(sourceCategoryId, targetCategoryId)
        reassignReceiptItemUserCorrectedCategoryToCategory(sourceCategoryId, targetCategoryId)
        reassignRecurringOccurrencesToCategory(sourceCategoryId, targetCategoryId)
        reassignManualRecurringExpensesToCategory(sourceCategoryId, targetCategoryId)
        reassignInstallmentPlansToCategory(sourceCategoryId, targetCategoryId)
        reassignPlannedExpensesToCategory(sourceCategoryId, targetCategoryId)
        reassignSpendingChallengesToCategory(sourceCategoryId, targetCategoryId)
        reassignMerchantCanonicalsToCategory(sourceCategoryId, targetCategoryId)
        reassignBankConnectionDefaultCategoryToCategory(sourceCategoryId, targetCategoryId)
        reassignUserCorrectionOriginalCategoryToCategory(sourceCategoryId, targetCategoryId)
        reassignUserCorrectionCorrectedCategoryToCategory(sourceCategoryId, targetCategoryId)
    }
}
